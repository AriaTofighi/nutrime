#!/usr/bin/env python3
"""Add the prepared-foods and NutriMe curated layers to the bundled food database.

    python3 tools/update_food_db.py --fndds <fndds_csv_dir> [--curated data/foods] [--db app/src/main/assets/foods.db]
    python3 tools/update_food_db.py --check     # validate data/foods and check foods.db is up to date (CI)

The database keeps its USDA SR Legacy and Foundation rows (built by build_food_db.py) untouched, so
ids already in people's diaries stay valid. This script replaces two layers on top:

- "fndds": USDA FNDDS (Food and Nutrient Database for Dietary Studies), about 5,400 foods as eaten
  (cooked dishes, prepared foods), public domain. --fndds is optional when the layer is already in the db.
- "nutrime": NutriMe's own curated foods from data/foods/**/*.json (see data/foods/README.md). Each one
  is derived from a USDA food, a recipe of USDA foods, or a package label, and is validated here.

Curated foods get stable string ids ("nm:<key>") stored in the `ref` column; USDA rows keep "usda:<id>".
"""
import argparse
import csv
import glob
import json
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(__file__))
from build_food_db import CANONICAL, find_dir, fmt, read  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..")
DEFAULT_DB = os.path.join(ROOT, "app", "src", "main", "assets", "foods.db")
DEFAULT_CURATED = os.path.join(ROOT, "data", "foods")
SYNONYMS = os.path.join(ROOT, "data", "foods", "synonyms.json")
# Docids for curated rows (the app addresses them by `ref`, so these only need to be unique).
CURATED_ID_BASE = 900_000_000

# Label fields (per serving) -> canonical nutrient id and multiplier to the stored unit.
LABEL = {
    "calories": (1008, 1), "fat_g": (1004, 1), "saturated_fat_g": (1258, 1), "trans_fat_g": (1257, 1),
    "cholesterol_mg": (1253, 1), "sodium_mg": (1093, 1), "carbs_g": (1005, 1), "fiber_g": (1079, 1),
    "sugars_g": (2000, 1), "added_sugars_g": (1235, 1), "protein_g": (1003, 1), "vitamin_d_mcg": (1114, 1),
    "calcium_mg": (1087, 1), "iron_mg": (1089, 1), "potassium_mg": (1092, 1),
}


# ---------------------------------------------------------------------------------------------
# FNDDS
# ---------------------------------------------------------------------------------------------

def load_fndds(base):
    """FNDDS foods as (fdc_id, name, category, nutrients, portions). Nutrients use legacy numbers."""
    d = find_dir(base)
    nbr_to_id = {r["nutrient_nbr"]: int(r["id"]) for r in read(os.path.join(d, "nutrient.csv")) if r["nutrient_nbr"]}
    cats = {r["wweia_food_category"]: r["wweia_food_category_description"]
            for r in read(os.path.join(d, "wweia_food_category.csv"))}
    foods = {}
    for r in read(os.path.join(d, "food.csv")):
        if r["data_type"] != "survey_fndds_food":
            continue
        foods[r["fdc_id"]] = {"name": r["description"].strip(), "category": cats.get(r["food_category_id"], ""),
                              "raw": {}, "portions": []}
    for r in read(os.path.join(d, "food_nutrient.csv")):
        f = foods.get(r["fdc_id"])
        if f is None or not r["amount"]:
            continue
        # FNDDS files key nutrients by their legacy number (208 = energy); newer ones by id.
        key = r["nutrient_id"]
        nid = nbr_to_id.get(key) if key in nbr_to_id and int(key) < 1000 else int(key)
        if nid is not None:
            f["raw"][nid] = float(r["amount"])
    for r in read(os.path.join(d, "food_portion.csv")):
        f = foods.get(r["fdc_id"])
        if f is None:
            continue
        try:
            grams = float(r["gram_weight"])
        except ValueError:
            continue
        label = r["portion_description"].strip()
        if grams <= 0 or not label or label.lower().startswith("quantity not specified"):
            continue
        f["portions"].append((int(r["seq_num"] or 0), label, grams))

    rows = []
    for fdc_id, f in foods.items():
        n = canonical(f["raw"])
        if 1008 not in n:
            continue
        seen, portions = set(), []
        for _seq, label, g in sorted(f["portions"]):
            if label not in seen:
                seen.add(label)
                portions.append({"l": label, "g": fmt(g)})
        rows.append({"id": int(fdc_id), "name": f["name"], "category": f["category"], "nutrients": n, "portions": portions})
    return rows


def canonical(raw):
    n = {}
    for canon, ids in CANONICAL.items():
        for sid in ids:
            if sid in raw:
                n[canon] = raw[sid]
                break
    if 1114 not in n and 1110 in raw:
        n[1114] = raw[1110] / 40.0
    return n


# ---------------------------------------------------------------------------------------------
# Curated foods
# ---------------------------------------------------------------------------------------------

class Invalid(Exception):
    pass


def load_curated(base):
    entries = []
    for path in sorted(glob.glob(os.path.join(base, "**", "*.json"), recursive=True)):
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        for e in data.get("foods", []):
            e["_file"] = os.path.relpath(path, base)
            entries.append(e)
    return entries


def resolve(entry, usda):
    """Per-100 g nutrients for one curated entry, from its `from`, `recipe` or `label` basis."""
    kinds = [k for k in ("from", "recipe", "label") if k in entry]
    if len(kinds) != 1:
        raise Invalid("needs exactly one of from / recipe / label")
    if "from" in entry:
        base = usda.get(entry["from"]["fdc"])
        if base is None:
            raise Invalid(f"fdc {entry['from']['fdc']} isn't in the database")
        n = dict(base)
        for nid, amount in entry["from"].get("override", {}).items():
            n[int(nid)] = amount
        return n
    if "recipe" in entry:
        r = entry["recipe"]
        total = {}
        raw_g = 0.0
        for ing in r["ingredients"]:
            base = usda.get(ing["fdc"])
            if base is None:
                raise Invalid(f"ingredient fdc {ing['fdc']} isn't in the database")
            raw_g += ing["g"]
            for nid, v in base.items():
                total[nid] = total.get(nid, 0.0) + v * ing["g"] / 100.0
        cooked = r.get("cooked_g", raw_g)
        if cooked <= 0:
            raise Invalid("cooked_g must be positive")
        n = {nid: v * 100.0 / cooked for nid, v in total.items()}
        # Water lost or gained in cooking changes only the water.
        if 1051 in n or cooked != raw_g:
            n[1051] = max(0.0, n.get(1051, 0.0) + (cooked - raw_g) * 100.0 / cooked)
        return n
    lab = entry["label"]
    g = lab.get("serving_g")
    if not g or g <= 0:
        raise Invalid("label needs serving_g")
    if "calories" not in lab:
        raise Invalid("label needs calories")
    unknown = set(lab) - set(LABEL) - {"serving_label", "serving_g"}
    if unknown:
        raise Invalid(f"unknown label fields {sorted(unknown)}")
    return {LABEL[k][0]: v * LABEL[k][1] * 100.0 / g for k, v in lab.items() if k in LABEL}


def validate(entry, n):
    for field in ("key", "name", "basis"):
        if not entry.get(field):
            raise Invalid(f"missing {field}")
    if entry["basis"].strip().upper().startswith("TODO"):
        raise Invalid("basis still says TODO: check the values and say where they came from")
    if not all(c.isalnum() or c == "-" for c in entry["key"]) or entry["key"] != entry["key"].lower():
        raise Invalid("key must be lowercase letters, digits and dashes")
    kcal = n.get(1008)
    if kcal is None or not 0 <= kcal <= 900:
        raise Invalid(f"calories per 100 g out of range: {kcal}")
    p, c, f, a = n.get(1003, 0), n.get(1005, 0), n.get(1004, 0), n.get(1018, 0)
    if p + c + f > 100.5:
        raise Invalid(f"protein + carbs + fat is {p + c + f:.1f} g per 100 g")
    atwater = 4 * p + 4 * c + 9 * f + 7 * a
    # Labels round, and fiber/sugar alcohols count less than 4 kcal/g, so allow some slack.
    if kcal > 20 and abs(atwater - kcal) > max(20, 0.15 * kcal):
        raise Invalid(f"{kcal:.0f} kcal per 100 g but macros add up to {atwater:.0f}")
    for s in entry.get("servings", []):
        if not s.get("label") or not 0 < s.get("g", 0) <= 2000:
            raise Invalid(f"bad serving {s}")
    if "label" in entry and not entry.get("barcode") and not entry.get("brand"):
        raise Invalid("label-based foods need a brand or barcode")


def same(label):
    """Serving labels compared loosely: "1 cup" and "cup" are the same serving."""
    label = label.lower().strip()
    return label[2:] if label.startswith("1 ") else label


def curated_rows(base, usda):
    """usda: {fdc_id: (nutrients, portions)} from usda_foods()."""
    nutrients = {k: v[0] for k, v in usda.items()}
    rows, errors, keys = [], [], set()
    for i, e in enumerate(load_curated(base)):
        where = f"{e['_file']}: {e.get('key', '#' + str(i))}"
        try:
            n = resolve(e, nutrients)
            validate(e, n)
            if e["key"] in keys:
                raise Invalid("duplicate key")
            keys.add(e["key"])
        except (Invalid, KeyError, TypeError, ValueError) as ex:
            errors.append(f"{where}: {ex}")
            continue
        servings = list(e.get("servings", []))
        if "from" in e and not e["from"].get("no_base_servings"):
            # Then the base food's own USDA servings, unless a hand-written one has the same label.
            labels = {same(s["label"]) for s in servings}
            servings += [{"label": p["l"], "g": p["g"]} for p in usda[e["from"]["fdc"]][1] if same(p["l"]) not in labels]
        if "label" in e:
            lab = e["label"]
            servings.insert(0, {"label": lab.get("serving_label") or "serving", "g": lab["serving_g"]})
        rows.append({
            "ref": "nm:" + e["key"], "name": e["name"], "brand": e.get("brand"), "barcode": e.get("barcode"),
            "category": e.get("category", ""), "keywords": " ".join(e.get("aliases", [])),
            "nutrients": {k: v for k, v in n.items() if v is not None},
            "portions": [{"l": s["label"], "g": fmt(s["g"])} for s in servings],
        })
    return rows, errors


# ---------------------------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------------------------

def ensure_schema(db):
    cols = {r[1] for r in db.execute("PRAGMA table_info(food)")}
    for col in ("ref", "brand", "barcode", "keywords"):
        if col not in cols:
            db.execute(f"ALTER TABLE food ADD COLUMN {col} TEXT")
    db.execute("CREATE INDEX IF NOT EXISTS food_ref ON food(ref)")


def usda_foods(db):
    """USDA rows as {fdc_id: (nutrients, portions)}."""
    has_ref = "ref" in {r[1] for r in db.execute("PRAGMA table_info(food)")}
    sql = "SELECT id, nutrients, portions FROM food" + (" WHERE ref IS NULL" if has_ref else "")
    return {fid: ({int(k): v for k, v in json.loads(n).items()}, json.loads(p)) for fid, n, p in db.execute(sql)}


def nutrients_json(n):
    return json.dumps({str(k): fmt(v) for k, v in sorted(n.items())}, separators=(",", ":"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fndds", help="extracted FNDDS CSV folder (FoodData_Central_survey_food_csv_*)")
    ap.add_argument("--curated", default=DEFAULT_CURATED)
    ap.add_argument("--db", default=DEFAULT_DB)
    ap.add_argument("--check", action="store_true", help="validate curated foods and check the db already has them (no writes)")
    args = ap.parse_args()

    db = sqlite3.connect(args.db)
    if not args.check:
        ensure_schema(db)
    if args.fndds:
        fndds = load_fndds(args.fndds)
        existing = {r[0] for r in db.execute("SELECT id FROM food WHERE source != 'fndds'")}
        clash = existing & {r["id"] for r in fndds}
        if clash:
            sys.exit(f"FNDDS ids already used by other rows: {sorted(clash)[:5]}")
        if not args.check:
            db.execute("DELETE FROM food WHERE source = 'fndds'")
            db.executemany(
                "INSERT INTO food(id, name, category, source, nutrients, portions) VALUES (?,?,?,?,?,?)",
                [(r["id"], r["name"], r["category"], "fndds", nutrients_json(r["nutrients"]),
                  json.dumps(r["portions"], separators=(",", ":"))) for r in fndds])
            print(f"FNDDS: {len(fndds)} foods")

    rows, errors = curated_rows(args.curated, usda_foods(db))
    if errors:
        print("Curated foods with problems:\n  " + "\n  ".join(errors), file=sys.stderr)
        sys.exit(1)
    if args.check:
        # The bundled db must contain exactly these curated foods, or someone forgot to rebuild it.
        has_ref = "ref" in {r[1] for r in db.execute("PRAGMA table_info(food)")}
        bundled = {r[0]: r[1:] for r in db.execute(
            "SELECT ref, name, nutrients, portions, brand, barcode, keywords FROM food WHERE source = 'nutrime'")} if has_ref else {}
        wanted = {r["ref"]: (r["name"], nutrients_json(r["nutrients"]), json.dumps(r["portions"], separators=(",", ":")),
                             r["brand"], r["barcode"], r["keywords"] or None) for r in rows}
        if bundled != wanted:
            stale = sorted(k for k in wanted.keys() | bundled.keys() if wanted.get(k) != bundled.get(k))
            sys.exit(f"{args.db} is out of date for {len(stale)} curated foods (e.g. {stale[:3]}). "
                     "Run: python3 tools/update_food_db.py")
        print(f"{len(rows)} curated foods OK and bundled")
        check_search_words(db)
        return

    db.execute("DELETE FROM food WHERE source = 'nutrime'")
    db.executemany(
        "INSERT INTO food(id, name, category, source, nutrients, portions, ref, brand, barcode, keywords) VALUES (?,?,?,?,?,?,?,?,?,?)",
        [(CURATED_ID_BASE + i, r["name"], r["category"], "nutrime", nutrients_json(r["nutrients"]),
          json.dumps(r["portions"], separators=(",", ":")), r["ref"], r["brand"], r["barcode"], r["keywords"] or None)
         for i, r in enumerate(sorted(rows, key=lambda r: r["ref"]))])
    print(f"NutriMe curated: {len(rows)} foods")

    # Search index: name, plus brand and aliases for curated foods.
    db.execute("DROP TABLE IF EXISTS food_fts")
    db.execute("CREATE VIRTUAL TABLE food_fts USING fts4(name, keywords, tokenize=unicode61)")
    db.execute("INSERT INTO food_fts(docid, name, keywords) SELECT id, name, TRIM(COALESCE(brand, '') || ' ' || COALESCE(keywords, '')) FROM food")
    write_search_words(db)
    counts = dict(db.execute("SELECT source, COUNT(*) FROM food GROUP BY source").fetchall())
    db.execute("INSERT OR REPLACE INTO meta VALUES ('version', '3')")
    db.execute("INSERT OR REPLACE INTO meta VALUES ('count', ?)", (str(sum(counts.values())),))
    db.execute("INSERT OR REPLACE INTO meta VALUES ('sources', ?)", (json.dumps(counts),))
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"wrote {args.db}: {counts} ({os.path.getsize(args.db) // 1024} KiB)")


def synonym_pairs(path=SYNONYMS):
    """Lower-case (word, same) pairs in both directions, from data/foods/synonyms.json."""
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        pairs = json.load(f)["pairs"]
    out = set()
    for a, b in pairs:
        a, b = a.strip().lower(), b.strip().lower()
        if a and b and a != b:
            out.add((a, b))
            out.add((b, a))
    return sorted(out)


def vocabulary(db):
    """Each search word of 4+ letters and how many foods contain it (for typing-error correction)."""
    import re
    word = re.compile(r"[^\W_]+", re.UNICODE)
    counts = {}
    for name, keywords, brand in db.execute("SELECT name, keywords, brand FROM food"):
        words = {w for w in word.findall(f"{name} {keywords or ''} {brand or ''}".lower()) if len(w) >= 4 and not w.isdigit()}
        for w in words:
            counts[w] = counts.get(w, 0) + 1
    return sorted(counts.items())


def write_search_words(db):
    db.execute("DROP TABLE IF EXISTS food_vocab")
    db.execute("CREATE TABLE food_vocab(word TEXT PRIMARY KEY, n INTEGER NOT NULL)")
    db.executemany("INSERT INTO food_vocab VALUES (?, ?)", vocabulary(db))
    db.execute("DROP TABLE IF EXISTS food_syn")
    db.execute("CREATE TABLE food_syn(word TEXT NOT NULL, same TEXT NOT NULL)")
    db.execute("CREATE INDEX food_syn_word ON food_syn(word)")
    db.executemany("INSERT INTO food_syn VALUES (?, ?)", synonym_pairs())
    n = db.execute("SELECT COUNT(*) FROM food_vocab").fetchone()[0]
    print(f"search words: {n} words, {len(synonym_pairs())} synonym pairs")


def check_search_words(db):
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
    if "food_vocab" not in tables or "food_syn" not in tables:
        sys.exit("foods.db has no search words. Run: python3 tools/update_food_db.py")
    if sorted(db.execute("SELECT word, same FROM food_syn").fetchall()) != synonym_pairs():
        sys.exit("foods.db synonyms are out of date. Run: python3 tools/update_food_db.py")
    if db.execute("SELECT word, n FROM food_vocab ORDER BY word").fetchall() != vocabulary(db):
        sys.exit("foods.db word list is out of date. Run: python3 tools/update_food_db.py")
    print("search words OK")


if __name__ == "__main__":
    main()
