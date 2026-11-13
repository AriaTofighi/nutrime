# Bundled food database

`app/src/main/assets/foods.db` is a SQLite database (with an FTS4 search index) used by the Android app, the
web app (exported to `foods.json` at build time) and the server's assistant. It has three layers:

- **USDA SR Legacy** (April 2018, about 7,800 foods) and **Foundation Foods** (about 400), built by
  `build_food_db.py`. Their ids must stay stable, so this layer is only rebuilt deliberately.
- **USDA FNDDS** 2021–2023 (about 5,400 foods as eaten: cooked and prepared foods), added by `update_food_db.py`.
- **NutriMe curated foods** from `data/foods/` (see `data/foods/README.md`), added by `update_food_db.py`.

## Curated foods (the usual change)

```bash
python3 tools/update_food_db.py           # rebuild the curated layer from data/foods
python3 tools/update_food_db.py --check   # validate and check foods.db is up to date (local)
```

No downloads needed. Bump `BundledFoodDb.ASSET_VERSION` so existing installs copy the new file.

## FNDDS

Download `FoodData_Central_survey_food_csv_*.zip` from https://fdc.nal.usda.gov/download-datasets, extract it,
then:

```bash
python3 tools/update_food_db.py --fndds path/to/FoodData_Central_survey_food_csv_2024-10-31
```

## SR Legacy and Foundation

1. Download the CSV zips for both datasets from https://fdc.nal.usda.gov/download-datasets.
   Use the latest Foundation release that contains `food.csv`.
2. Extract each zip, then run the commands below. This replaces the whole file, so add the other layers again.

```bash
python3 tools/build_food_db.py path/to/sr_legacy_csv_dir path/to/foundation_csv_dir
python3 tools/update_food_db.py --fndds path/to/fndds_csv_dir
```

3. Bump `BundledFoodDb.ASSET_VERSION`.

## USDA Branded Foods (server catalog, not foods.db)

`import_usda_branded.py` prepares the FDC Branded Foods CSV download for the server's PostgreSQL catalog. It
does not change `foods.db`. See "USDA Branded Foods (bulk import)" in `server/README.md` for the commands.
