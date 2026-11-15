"""Export the bundled food database (the Android app's assets/foods.db) to compact JSON.

Output: a JSON array of [fdcId, name, category, {nutrientId: per100g}, [[portionLabel, grams], ...]],
with [..., ref, brand, keywords, barcode, hidden] appended for NutriMe curated foods (ref is their "nm:<key>"
id), for USDA foods with a brand, and for hidden duplicates (hidden = 1: found by id, never by search).
Used at Docker build time, so the web app searches the same offline foods as the phone app.
"""
import json
import sqlite3
import sys

src, dst = sys.argv[1], sys.argv[2]
db = sqlite3.connect(src)
cols = {r[1] for r in db.execute("PRAGMA table_info(food)")}
extra = ", ref, brand, keywords, barcode" if "ref" in cols else ", NULL, NULL, NULL, NULL"
extra += ", hidden" if "hidden" in cols else ", 0"
rows = []
for fid, name, category, nutrients, portions, ref, brand, keywords, barcode, hidden in db.execute(
    f"SELECT id, name, category, nutrients, portions{extra} FROM food ORDER BY id"
):
    n = {k: (round(v, 4) if isinstance(v, float) else v) for k, v in json.loads(nutrients).items()}
    p = [[x["l"], x["g"]] for x in json.loads(portions)]
    row = [fid, name, category, n, p]
    if ref or brand or hidden:
        row += [ref, brand, keywords, barcode]
        if hidden:
            row.append(1)
    rows.append(row)
with open(dst, "w", encoding="utf-8") as f:
    json.dump(rows, f, separators=(",", ":"), ensure_ascii=False)
print(f"Exported {len(rows)} foods to {dst}")

# Search words: synonyms ("yoghurt" -> "yogurt") and the word list for typing-error correction.
tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
search = {
    "syn": db.execute("SELECT word, same FROM food_syn").fetchall() if "food_syn" in tables else [],
    "vocab": db.execute("SELECT word, n FROM food_vocab").fetchall() if "food_vocab" in tables else [],
    # Hidden duplicate -> the food shown in its place, so usage of the old id counts for the new one.
    "alias": db.execute("SELECT old_id, new_id FROM food_alias").fetchall() if "food_alias" in tables else [],
}
search_dst = dst[:-5] + "-search.json" if dst.endswith(".json") else dst + "-search.json"
with open(search_dst, "w", encoding="utf-8") as f:
    json.dump(search, f, separators=(",", ":"), ensure_ascii=False)
print(f"Exported {len(search['syn'])} synonym pairs, {len(search['vocab'])} words and {len(search['alias'])} aliases to {search_dst}")
