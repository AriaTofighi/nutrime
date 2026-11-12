# NutriMe curated foods

NutriMe's own food database: entries we've checked, under the names people actually search for. It sits
on top of the USDA data in the bundled database (`app/src/main/assets/foods.db`), which every part of
NutriMe uses: the Android app, the web app and the assistant on the server.

| Layer | Source | Foods | Ids |
|---|---|---|---|
| USDA SR Legacy + Foundation | lab-analyzed basic foods | ~8,200 | `usda:<fdcId>` |
| USDA FNDDS 2021–2023 | foods as eaten: cooked, prepared, mixed dishes | ~5,400 | `usda:<fdcId>` |
| **NutriMe curated** (this folder) | checked entries derived from the above or from package labels | grows | `nm:<key>` |

Curated foods show as source "NutriMe" and rank first in search. Their ids never change once released,
because diary entries point to them: fix an entry in place, and don't rename or reuse a `key`.

## Files

- `prepared.json`: everyday prepared foods (cooked rices, grilled chicken, steamed vegetables…).
- `branded/*.json`: branded products, one file per brand or store (e.g. `branded/kirkland.json`).

Each file is `{"about": "...", "foods": [ ...entries ]}`.

- `synonyms.json`: search words only, no foods: `{"pairs": [["yoghurt", "yogurt"], ...]}`. Each pair
  makes either word also find foods named with the other (British and US names, short names). The build
  writes them, and a list of every search word (for typing-error correction), into `foods.db`.

## An entry

```json
{
  "key": "rice-basmati-cooked",
  "name": "Basmati rice, white, cooked, unsalted",
  "category": "Grains and pasta",
  "aliases": ["basmati", "basmati rice"],
  "from": {"fdc": 169757},
  "servings": [{"label": "1 cup", "g": 158}],
  "basis": "USDA SR Legacy 169757: \"Rice, white, long-grain, regular, unenriched, cooked without salt\". Basmati is a long-grain white rice; ..."
}
```

- `key`: lowercase letters, digits and dashes. Becomes the id `nm:<key>`.
- `name`: what people would call it. `brand` and `barcode` for packaged products.
- `aliases`: extra search words (they're searched but not shown).
- `servings`: household servings in grams. Entries built `from` a USDA food also get that food's servings.
- `basis`: **required**. Where the numbers come from, specific enough for someone else to check them.

Exactly one of these says where the nutrients come from:

1. **`from`**: a USDA food by FDC id (any layer above), all nutrients copied. Optional `override`
   (`{"<nutrientId>": amount}`) for a documented correction.
2. **`recipe`**: USDA ingredients by weight, `{"ingredients": [{"fdc": 2346396, "g": 40}, ...], "cooked_g": 300}`.
   Nutrients are summed and scaled to `cooked_g` (the finished weight, if cooking changes it; only the water
   changes). Leave out `cooked_g` for no-cook recipes.
3. **`label`**: a package's nutrition facts per serving, exactly as printed:
   `{"serving_label": "1 scoop", "serving_g": 31, "calories": 120, "fat_g": 1.5, "saturated_fat_g": 1,
   "cholesterol_mg": 30, "sodium_mg": 130, "carbs_g": 3, "fiber_g": 0, "sugars_g": 1, "protein_g": 24}`.
   Other fields: `trans_fat_g`, `added_sugars_g`, `vitamin_d_mcg`, `calcium_mg`, `iron_mg`, `potassium_mg`.
   Needs a `brand` or `barcode`.

## Rules (checked by the build)

- Every entry has a `basis`, and it doesn't start with "TODO".
- Calories match the macros: 4 × protein + 4 × carbs + 9 × fat (+ 7 × alcohol) within 15% (or 20 kcal).
- At most 900 kcal per 100 g, and protein + carbs + fat at most 100 g per 100 g.
- Keys are unique; servings are 0–2000 g.

Don't add numbers you can't point to. If USDA has no analysis of the exact food (say, jasmine rice), use the
closest USDA food and say so in `basis`.

## Adding or changing foods

```bash
# edit data/foods/*.json, then:
python3 tools/update_food_db.py          # validates, then rebuilds the curated layer in foods.db
python3 tools/update_food_db.py --check  # validate that foods.db is up to date (local check)
```

Commit the JSON and `app/src/main/assets/foods.db` together, and bump `BundledFoodDb.ASSET_VERSION` in the
app so phones copy the new database.

## Growing the branded list

The server lists the branded foods people actually log, most used first, each with a draft entry:

```bash
curl -H "Authorization: Bearer <admin token>" "https://<server>/api/foods/candidates?limit=50"
```

(Admins are set with `ADMIN_EMAILS` on the server; a token comes from `/api/auth/login`.) Candidates come from
USDA Branded and Open Food Facts foods anyone has logged (public data; only counts are shared), plus the admin's
own custom foods. Each has `flags` for obvious problems (e.g. calories that don't match the macros).

For each candidate worth adding:

1. Check the draft's `label` against the package, a photo of it, or the maker's site. Online values are often
   wrong (crowd-sourced typos, old recipes).
2. Fix what differs, set `basis` to where you checked it (e.g. "Package label, photographed 2026-11-10"),
   and add aliases people would search for.
3. Add it to `branded/<brand>.json`, rebuild, commit.

Once a branded food is curated, the assistant and search prefer it, and its numbers are the checked ones.


## Automatically saved provider results

The server retains public USDA and Open Food Facts search results in its PostgreSQL catalog. This grows
with searches from Android, web and the assistant and survives deployments. These entries keep their
provider IDs, source URL, license and fetch date; they are not automatically marked as checked NutriMe
foods. The admin candidates route includes these entries even if nobody has logged them. It does not
expose search queries or account identities. Check the label and record a basis before adding a curated
entry here. Preserve Open Food Facts attribution and ODbL provenance when reusing its database content.
