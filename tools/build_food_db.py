#!/usr/bin/env python3
"""Build the bundled offline food database (app/src/main/assets/foods.db).

Sources: USDA FoodData Central "SR Legacy" and "Foundation Foods" CSV releases
(public domain). Usage:

    python3 tools/build_food_db.py <sr_legacy_csv_dir> <foundation_csv_dir> [out.db]

The CSV directories are the extracted zip folders from
https://fdc.nal.usda.gov/download-datasets (see tools/README.md).

Nutrient values are stored per 100 g keyed by USDA nutrient id, using the
canonical ids the app understands (see Nutrients.kt). Fallback ids are folded
into their canonical id (e.g. Atwater energy -> 1008).
"""
import csv
import json
import os
import sqlite3
import sys

# canonical id -> ordered list of source ids (first present wins)
CANONICAL = {
    1008: [1008, 2048, 2047],          # Energy kcal
    1003: [1003],                      # Protein
    1004: [1004, 1085],                # Fat
    1005: [1005, 1050],                # Carbohydrate
    1079: [1079],                      # Fiber
    2000: [2000, 1063],                # Sugars
    1235: [1235],                      # Added sugars
    1051: [1051],                      # Water
    1057: [1057],                      # Caffeine
    1018: [1018],                      # Alcohol
    1258: [1258],                      # Saturated
    1292: [1292],                      # Monounsaturated
    1293: [1293],                      # Polyunsaturated
    1257: [1257],                      # Trans
    1253: [1253],                      # Cholesterol
    1404: [1404, 1270],                # ALA omega-3
    1278: [1278],                      # EPA
    1272: [1272],                      # DHA
    1316: [1316, 1269],                # Linoleic omega-6
    1106: [1106],                      # Vitamin A RAE
    1162: [1162],                      # Vitamin C
    1114: [1114],                      # Vitamin D ug
    1109: [1109],                      # Vitamin E
    1185: [1185],                      # Vitamin K
    1165: [1165],                      # Thiamin
    1166: [1166],                      # Riboflavin
    1167: [1167],                      # Niacin
    1170: [1170],                      # Pantothenic acid
    1175: [1175],                      # B6
    1190: [1190, 1177],                # Folate DFE
    1178: [1178],                      # B12
    1180: [1180],                      # Choline
    1087: [1087],                      # Calcium
    1098: [1098],                      # Copper
    1089: [1089],                      # Iron
    1090: [1090],                      # Magnesium
    1101: [1101],                      # Manganese
    1091: [1091],                      # Phosphorus
    1092: [1092],                      # Potassium
    1103: [1103],                      # Selenium
    1093: [1093],                      # Sodium
    1095: [1095],                      # Zinc
}
SOURCE_IDS = {s for ids in CANONICAL.values() for s in ids} | {1110}


def find_dir(base):
    """Return the folder that actually contains food.csv."""
    for root, _dirs, files in os.walk(base):
        if "food.csv" in files:
            return root
    sys.exit(f"food.csv not found under {base}")


def read(path):
    with open(path, newline="", encoding="utf-8") as f:
        yield from csv.DictReader(f)


def fmt(v):
    return float(f"{v:.4g}")


def load(dirpath, source, only_ids=None):
    d = find_dir(dirpath)
    cats = {r["id"]: r["description"] for r in read(os.path.join(d, "food_category.csv"))}
    units = {r["id"]: r["name"] for r in read(os.path.join(d, "measure_unit.csv"))}
    foods = {}
    for r in read(os.path.join(d, "food.csv")):
        if only_ids is not None and r["fdc_id"] not in only_ids:
            continue
        foods[r["fdc_id"]] = {
            "name": r["description"].strip(),
            "category": cats.get(r["food_category_id"], ""),
            "raw": {},
            "portions": [],
        }
    for r in read(os.path.join(d, "food_nutrient.csv")):
        f = foods.get(r["fdc_id"])
        if f is None or not r["amount"]:
            continue
        nid = int(r["nutrient_id"])
        if nid in SOURCE_IDS:
            f["raw"][nid] = float(r["amount"])
    for r in read(os.path.join(d, "food_portion.csv")):
        f = foods.get(r["fdc_id"])
        if f is None:
            continue
        try:
            grams = float(r["gram_weight"])
            amount = float(r["amount"] or 1) or 1.0
        except ValueError:
            continue
        if grams <= 0:
            continue
        unit = units.get(r["measure_unit_id"], "")
        if unit == "undetermined":
            unit = ""
        parts = [p for p in (unit, r["portion_description"].strip(), r["modifier"].strip()) if p]
        label = ", ".join(dict.fromkeys(parts)) or "serving"
        if label.lower().startswith("quantity not specified"):
            label = "serving"
        f["portions"].append((r.get("seq_num") or "", label, grams / amount))

    out = []
    for fdc_id, f in foods.items():
        raw = f["raw"]
        n = {}
        for canon, ids in CANONICAL.items():
            for sid in ids:
                if sid in raw:
                    n[canon] = raw[sid]
                    break
        if 1114 not in n and 1110 in raw:
            n[1114] = raw[1110] / 40.0  # IU -> ug
        if 1008 not in n and any(k in n for k in (1003, 1004, 1005)):
            n[1008] = 4 * n.get(1003, 0) + 4 * n.get(1005, 0) + 9 * n.get(1004, 0)
        if 1008 not in n:
            continue
        seen = set()
        portions = []
        for _seq, label, g in sorted(f["portions"], key=lambda p: (p[0] == "", p[0])):
            if label in seen:
                continue
            seen.add(label)
            portions.append({"l": label, "g": fmt(g)})
        out.append((int(fdc_id), f["name"], f["category"], source,
                    json.dumps({str(k): fmt(v) for k, v in sorted(n.items())}, separators=(",", ":")),
                    json.dumps(portions, separators=(",", ":"))))
    return out


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    sr_dir, fnd_dir = sys.argv[1], sys.argv[2]
    out_path = sys.argv[3] if len(sys.argv) > 3 else os.path.join(
        os.path.dirname(__file__), "..", "app", "src", "main", "assets", "foods.db")

    fnd_ids = {r["fdc_id"] for r in read(os.path.join(find_dir(fnd_dir), "foundation_food.csv"))}
    rows = load(fnd_dir, "foundation", fnd_ids) + load(sr_dir, "sr_legacy")

    if os.path.exists(out_path):
        os.remove(out_path)
    db = sqlite3.connect(out_path)
    db.executescript("""
        PRAGMA page_size=4096;
        CREATE TABLE food(
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            category TEXT NOT NULL,
            source TEXT NOT NULL,
            nutrients TEXT NOT NULL,
            portions TEXT NOT NULL
        );
        CREATE VIRTUAL TABLE food_fts USING fts4(name, tokenize=unicode61);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    """)
    db.executemany("INSERT INTO food VALUES (?,?,?,?,?,?)", rows)
    db.execute("INSERT INTO food_fts(docid, name) SELECT id, name FROM food")
    db.execute("INSERT INTO meta VALUES ('version', '1')")
    db.execute("INSERT INTO meta VALUES ('count', ?)", (str(len(rows)),))
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"wrote {len(rows)} foods to {out_path} ({os.path.getsize(out_path) // 1024} KiB)")


if __name__ == "__main__":
    main()
