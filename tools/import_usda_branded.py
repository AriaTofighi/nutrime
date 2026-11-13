#!/usr/bin/env python3
"""Prepare USDA FoodData Central "Branded Foods" for NutriMe's server catalog.

    python3 tools/import_usda_branded.py FoodData_Central_branded_food_csv_2025-04-24.zip --out branded.jsonl.gz
    python3 tools/import_usda_branded.py <zip> --stats                     # counts and size estimate only
    python3 tools/import_usda_branded.py <zip> --out f.jsonl.gz --country "United States" --min-brand-foods 20

Input: the FDC Branded Foods bulk download, CSV format (a .zip, or the extracted folder). It is public
domain (CC0 1.0): https://fdc.nal.usda.gov/download-datasets. Do not commit the download or the output.

Output: gzip JSON lines. The first line describes the release; each other line is one food, with
nutrients per 100 g already in NutriMe's ids and units. The server loads it into its catalog (ids
"usda:<fdcId>", so diary entries and FoodCatalog.get keep working):

    nutrime-server import-branded branded.jsonl.gz

Rules, the same as the server's live USDA parser (FoodProviders.parseUsda, UsdaNutrients.canonicalize):
- Nutrients: the canonical ids of build_food_db.CANONICAL with their fallbacks; energy in kJ is not
  used as kcal; energy from kJ (1062) / 4.184 when kcal is missing; vitamin D from IU (1110) / 40.
- Rejected: no energy, energy below 0 or above 900 kcal per 100 g, any negative or non-finite value.
- Serving: serving size in g or ml (up to 2000) with the household text, as "1 cup (240 g)".
- Discontinued products (discontinued_date set) are dropped.
- One food per GTIN/UPC: the newest (publication, available or modified date; then the higher fdcId).
- Then products with the same brand, the same name and energy within 5% are one product (the newest).
Names are cleaned on the server with the shared FoodNames cleaner when it imports the file.
"""
import argparse
import csv
import gzip
import io
import json
import math
import os
import re
import sqlite3
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
from build_food_db import CANONICAL, fmt  # noqa: E402

csv.field_size_limit(1 << 30)
SOURCE_IDS = {s for ids in CANONICAL.values() for s in ids} | {1062, 1110}
WORD = re.compile(r"[^\w]+", re.UNICODE)
STOP = {"and", "with", "in", "of", "the", "a", "an", "or", "to"}


class Source:
    """CSV files from the zip or an extracted folder (the files may sit in a subfolder)."""

    def __init__(self, path):
        self.path = path
        if os.path.isdir(path):
            self.names = {}
            for root, _dirs, files in os.walk(path):
                for f in files:
                    self.names.setdefault(f, os.path.join(root, f))
            self.zip = None
        else:
            self.zip = zipfile.ZipFile(path)
            self.names = {}
            for n in self.zip.namelist():
                self.names.setdefault(os.path.basename(n), n)

    def rows(self, name):
        if name not in self.names:
            sys.exit(f"{name} not found in {self.path}: is this the FDC Branded Foods CSV download?")
        if self.zip is None:
            fh = open(self.names[name], newline="", encoding="utf-8")
        else:
            fh = io.TextIOWrapper(self.zip.open(self.names[name]), encoding="utf-8", newline="")
        with fh:
            yield from csv.DictReader(fh)


def release_of(path, given):
    if given:
        return given
    m = re.search(r"(\d{4}-\d{2}-\d{2})", os.path.basename(path.rstrip("/")))
    return m.group(1) if m else None


def num(s):
    try:
        v = float(s)
    except (TypeError, ValueError):
        return None
    return v


def norm_words(s):
    return [w for w in WORD.split((s or "").lower()) if w and w != "_" and w not in STOP]


def product_key(brand, name):
    """Brand and name as words, with a leading brand removed from the name ("Tyson chicken" = "chicken")."""
    b = norm_words(brand)
    n = norm_words(name)
    if b and n[:len(b)] == b and len(n) > len(b):
        n = n[len(b):]
    return " ".join(b) + "|" + " ".join(n)


def canonical(raw, units):
    """raw: {nutrient_id: amount}; units: {nutrient_id: unit name}. Same rules as UsdaNutrients.canonicalize."""
    n = {}
    for canon, ids in CANONICAL.items():
        for sid in ids:
            if sid not in raw:
                continue
            if canon == 1008 and units.get(sid, "").upper() == "KJ":
                continue
            n[canon] = raw[sid]
            break
    if 1008 not in n and 1062 in raw:
        n[1008] = raw[1062] / 4.184
    if 1114 not in n and 1110 in raw:
        n[1114] = raw[1110] / 40.0
    return n


def serving(r):
    unit = (r.get("serving_size_unit") or "").strip().lower()
    size = num(r.get("serving_size"))
    if size is None or not math.isfinite(size) or not 0 < size <= 2000 or unit not in ("g", "grm", "ml", "mlt"):
        return []
    household = (r.get("household_serving_fulltext") or "").strip()
    g = fmt(size)
    g_text = str(int(g)) if g == int(g) else f"{g:.1f}"
    return [{"label": f"{household} ({g_text} g)" if household else f"serving ({g_text} g)", "grams": g}]


def country(r):
    c = (r.get("market_country") or "").strip().lower()
    c = re.sub(r"[^a-z]+", "-", c).strip("-")
    return [c] if c else []


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", help="FDC Branded Foods CSV download (.zip or extracted folder)")
    ap.add_argument("--out", help="output file (.jsonl.gz)")
    ap.add_argument("--stats", action="store_true", help="print counts and a size estimate; write nothing")
    ap.add_argument("--release", help="release date (yyyy-mm-dd); read from the file name when not given")
    ap.add_argument("--country", help='keep only this market country, as USDA writes it (e.g. "United States")')
    ap.add_argument("--min-brand-foods", type=int, default=0, help="keep only brands with at least this many products")
    a = ap.parse_args()
    if not a.out and not a.stats:
        ap.error("give --out FILE, or --stats")
    src = Source(a.input)
    release = release_of(a.input, a.release)

    # 1. Products: one per GTIN (the newest), without discontinued ones.
    dates = {}
    names = {}
    for r in src.rows("food.csv"):
        if r.get("data_type") == "branded_food":
            dates[r["fdc_id"]] = r.get("publication_date") or ""
            names[r["fdc_id"]] = (r.get("description") or "").strip()
    products = {}
    total = discontinued = foreign = 0
    by_gtin = {}
    for r in src.rows("branded_food.csv"):
        fid = r["fdc_id"]
        if fid not in names:
            continue
        total += 1
        if (r.get("discontinued_date") or "").strip():
            discontinued += 1
            continue
        if a.country and (r.get("market_country") or "").strip().lower() != a.country.lower():
            foreign += 1
            continue
        newest = max(dates.get(fid, ""), (r.get("available_date") or "").strip(), (r.get("modified_date") or "").strip())
        rank = (newest, int(fid))
        brand = (r.get("brand_name") or "").strip() or (r.get("brand_owner") or "").strip()
        info = {"rank": rank, "brand": brand, "gtin": re.sub(r"\D", "", r.get("gtin_upc") or ""),
                "servings": serving(r), "countries": country(r)}
        gtin = info["gtin"].lstrip("0")
        if gtin:
            old = by_gtin.get(gtin)
            if old is not None and products[old]["rank"] >= rank:
                continue
            if old is not None:
                del products[old]
            by_gtin[gtin] = fid
        products[fid] = info
    after_gtin = len(products)

    if a.min_brand_foods > 1:
        counts = {}
        for p in products.values():
            counts[p["brand"].lower()] = counts.get(p["brand"].lower(), 0) + 1
        products = {k: p for k, p in products.items() if counts.get(p["brand"].lower(), 0) >= a.min_brand_foods}
    after_brands = len(products)

    # 2. Nutrients, kept in a temporary SQLite file (food_nutrient.csv has tens of millions of rows).
    units = {int(r["id"]): r.get("unit_name") or "" for r in src.rows("nutrient.csv")}
    tmp = tempfile.NamedTemporaryFile(suffix=".sqlite", delete=False)
    tmp.close()
    db = sqlite3.connect(tmp.name)
    try:
        db.execute("CREATE TABLE n(fdc INTEGER, nid INTEGER, amount REAL)")
        batch = []
        for r in src.rows("food_nutrient.csv"):
            fid = r["fdc_id"]
            if fid not in products:
                continue
            try:
                nid = int(r["nutrient_id"])
            except (TypeError, ValueError):
                continue
            if nid not in SOURCE_IDS:
                continue
            v = num(r.get("amount"))
            if v is None:
                continue
            batch.append((int(fid), nid, v))
            if len(batch) >= 50000:
                db.executemany("INSERT INTO n VALUES (?,?,?)", batch)
                batch = []
        if batch:
            db.executemany("INSERT INTO n VALUES (?,?,?)", batch)
        db.execute("CREATE INDEX n_fdc ON n(fdc)")
        db.commit()

        # 3. Valid foods, then one per (brand, name, energy within 5%): the newest.
        rejected = 0
        groups = {}
        foods = {}
        current, raw = None, {}

        def finish(fid, raw):
            nonlocal rejected
            n = canonical(raw, units)
            kcal = n.get(1008)
            if kcal is None or any(not math.isfinite(v) or v < 0 for v in n.values()) or kcal > 900:
                rejected += 1
                return
            p = products[str(fid)]
            foods[fid] = {k: fmt(v) for k, v in n.items()}
            groups.setdefault(product_key(p["brand"], names[str(fid)]), []).append((p["rank"], fid, kcal))

        for fid, nid, amount in db.execute("SELECT fdc, nid, amount FROM n ORDER BY fdc"):
            if fid != current:
                if current is not None:
                    finish(current, raw)
                current, raw = fid, {}
            raw[nid] = amount
        if current is not None:
            finish(current, raw)
    finally:
        db.close()
        os.unlink(tmp.name)
    no_nutrients = after_brands - len(foods) - rejected

    keep = []
    for members in groups.values():
        members.sort(reverse=True)  # newest first
        kept = []
        for rank, fid, kcal in members:
            if any(abs(kcal - k) <= 0.05 * max(abs(kcal), abs(k)) for k in kept):
                continue
            kept.append(kcal)
            keep.append(fid)
    keep.sort()

    lines = []
    for fid in keep:
        p = products[str(fid)]
        lines.append(json.dumps({
            "fdcId": fid, "description": names[str(fid)], "brand": p["brand"] or None,
            "gtin": p["gtin"] or None, "nutrients": foods[fid], "servings": p["servings"], "countries": p["countries"],
        }, separators=(",", ":"), ensure_ascii=False))
    avg = sum(len(x) for x in lines) / max(1, len(lines))
    # Catalog row: payload (about the line size), search text, ids and a trigram index entry.
    estimate_mb = len(lines) * (avg * 1.1 + 250) / 1e6

    print(f"release: {release or 'unknown'}")
    print(f"branded products: {total}")
    print(f"  discontinued, dropped: {discontinued}")
    if a.country:
        print(f"  other countries, dropped: {foreign}")
    print(f"  after one per GTIN: {after_gtin}")
    if a.min_brand_foods > 1:
        print(f"  after brands with at least {a.min_brand_foods} products: {after_brands}")
    print(f"  rejected (energy missing or out of range, bad values): {rejected}")
    print(f"  without nutrients: {no_nutrients}")
    print(f"  after merging same brand, name and energy: {len(keep)}")
    print(f"average food: {avg:.0f} bytes; catalog size about {estimate_mb:.0f} MB in PostgreSQL (with the search index)")
    if a.stats:
        return
    with gzip.open(a.out, "wt", encoding="utf-8") as out:
        out.write(json.dumps({"source": "usda-branded", "release": release, "foods": len(lines)}) + "\n")
        for line in lines:
            out.write(line + "\n")
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
