# NutriMe

NutriMe is an open source calorie and nutrition tracker. It tracks calories, macros and **40+
micronutrients** against targets personalized to your body and goals. It has an Android app, a web
app, and a small server that you host yourself for sync and an optional AI assistant.

- **Offline first**: the Android app works without a network, with 13,600+ foods bundled.
- **Self-hosted**: your diary syncs only to the server you run. There is no third-party account.
- **One codebase for the logic**: nutrient targets, the learned daily burn, goals, search ranking and
  insights live in shared Kotlin code, with checked TypeScript copies for the web.

## Features

- **Food diary** with breakfast, lunch, dinner and snacks; edit, swipe to delete, copy yesterday's foods,
  saved meals, favourites, day notes and a "day complete" mark.
- **Detailed nutrients**: energy, macros, fiber, sugars, fat breakdown (saturated, mono, poly, omega-3/6,
  trans), cholesterol, 13 vitamins, 10 minerals, water, caffeine and alcohol, against your targets, with
  upper limits flagged.
- **Food sources**
  - Bundled offline: USDA SR Legacy and Foundation Foods, USDA FNDDS (about 5,400 cooked and prepared
    foods) and NutriMe's curated foods ([`data/foods/`](data/foods/README.md)), which rank first in search.
  - Online through your server: USDA FoodData Central (branded products) and Open Food Facts.
  - Barcode scanning, custom foods from a nutrition label, and recipes built from ingredients.
- **Fast logging**: one ranked search (your usual foods first, synonyms, typing correction, amounts such
  as "200 g rice" or "2 eggs"), usual foods for each meal, one-tap add, and "scan next".
- **Learned daily burn**: a Kalman filter learns your real daily energy use from your food log and
  weigh-ins, shows how sure it is, and asks at a weekly check-in before it changes your target.
- **Personalized targets**: Mifflin-St Jeor × activity, the learned burn, or your own value; a goal weight
  with a weekly rate and projected date; macros as percentages or protein in g/kg; a weekday calorie
  schedule; Dietary Reference Intakes for your age and sex; per-nutrient overrides.
- **Trends**: ranges from a week to all time or a custom range, each compared with the period before;
  a smoothed trend weight with a goal projection; where each nutrient comes from; weekday and meal patterns.
- **Android extras**: Health Connect (weigh-ins, steps and active energy in; nutrition out), a home screen
  widget, and meal, weigh-in and check-in reminders.
- **Your data**: export everything as CSV and JSON; import Cronometer, MyFitnessPal or NutriMe exports on
  the web.
- **AI assistant** (optional, Gemini): ask about your diary and trends, or describe or photograph a meal
  or a nutrition label to log it. It proposes changes; nothing is saved until you tap **Confirm**.
- Material You dynamic colors, dark mode, metric or imperial units.

## Repository layout

| Path | Contents |
| --- | --- |
| [`app/`](app) | Android app (Kotlin, Jetpack Compose, Room, WorkManager, Glance) |
| [`web/`](web/README.md) | Web app (React, TypeScript, Vite), served by nginx |
| [`server/`](server/README.md) | Sync and AI assistant server (Ktor, Exposed, PostgreSQL), Docker Compose stack |
| [`shared/`](shared) | Kotlin logic used by the app and the server: nutrients, DRI targets, profile math, learned burn, goals, ranges, search ranking, insights, export |
| [`shared/testdata/`](shared/testdata) | Test vectors that the Kotlin and TypeScript copies must both pass |
| [`data/foods/`](data/foods/README.md) | NutriMe's curated foods (JSON, with the source of every value) |
| [`design/tokens.json`](design/tokens.json) | The design system shared by both apps |
| [`tools/`](tools/README.md) | Scripts that build the food database, design tokens and icons |

Inside the Android app (`app/src/main/java/com/nutrime/app/`): `data/` (Room database, repositories,
remote clients), `sync/` (sync manager and worker), `health/` (Health Connect), `widget/`, `reminders/`,
and `ui/` (Compose screens by feature).

## Quick start

### 1. Run the server and web app

Requirements: Docker with Compose.

```bash
cd server
cp .env.example .env     # set POSTGRES_PASSWORD and JWT_SECRET (openssl rand -hex 32)
docker compose -f docker-compose.yml -f docker-compose.local.yml up --build
```

Open http://localhost:8080 and create an account. To deploy on a server with HTTPS (for example with
Coolify), see [`server/README.md`](server/README.md).

Optional server settings: `USDA_API_KEY` for online USDA search (otherwise the limited `DEMO_KEY` is
used) and `GEMINI_API_KEY` to turn on the AI assistant.

### 2. Build the Android app

Requirements: JDK 17+ and the Android SDK (or Android Studio).

```bash
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

The app talks to one sync server, set at build time with `NUTRIME_SERVER_URL`. The default,
`http://10.0.2.2:8080`, is the local server above as seen from the Android emulator. For a phone, build
with your server's URL:

```bash
NUTRIME_SERVER_URL=https://nutrime.example.com ./gradlew :app:assembleDebug
```

In the app, open **Profile → Sync & account** to sign in. The app syncs on launch, shortly after each
change, and hourly in the background.

### 3. Develop the web app

```bash
cd web
npm ci
npm run foods    # export the bundled food database for the browser (needs python3)
npm run dev      # http://localhost:5173, proxies /api to http://localhost:8080
```

## Android app

### Release builds

Release builds read the signing key from the environment. Without these variables, a release build is
signed with your local debug key.

| Variable | Description |
| --- | --- |
| `NUTRIME_KEYSTORE_FILE` | Path to the keystore (`.jks`) |
| `NUTRIME_KEYSTORE_PASSWORD` | Keystore password |
| `NUTRIME_KEY_ALIAS` | Key alias (default `nutrime`) |
| `NUTRIME_KEY_PASSWORD` | Key password (default: the keystore password) |
| `NUTRIME_SERVER_URL` | The sync server URL built into the app |
| `NUTRIME_BUILD_NUMBER` | `versionCode`; it must increase with each release |

The version name is `appVersion` in [`gradle.properties`](gradle.properties).

The repository has one GitHub Actions workflow, [`apk-release.yml`](.github/workflows/apk-release.yml).
A maintainer starts it by hand (**Actions → Build APK release → Run workflow** on `main`). It builds the
signed APK and publishes `nutrime.apk` to GitHub Releases. It needs the repository secrets
`NUTRIME_KEYSTORE_BASE64` (the keystore, base64-encoded) and `NUTRIME_KEYSTORE_PASSWORD` (optionally
`NUTRIME_KEY_ALIAS` and `NUTRIME_KEY_PASSWORD`) and the repository variable `NUTRIME_SERVER_URL`.
In a fork, use your own key and server.

### Install a release

1. Download `nutrime.apk` from **Releases** on your phone.
2. Open it and allow installs from your browser or file manager when Android asks.
3. To update, install a newer `nutrime.apk` over the old one. Your data is kept.

## Tests and checks

```bash
./gradlew :app:testDebugUnitTest          # Android unit and Robolectric tests (screenshots in build/screenshots/)
(cd server && ./gradlew test)              # server tests (in-memory H2) and shared-logic tests
(cd web && npm ci && npx tsc --noEmit)     # web type check
(cd web && npx tsx scripts/burn_vectors.ts) # web copy of the learned burn against shared vectors
python3 tools/gen_design_tokens.py --check # generated design files are up to date
python3 tools/update_food_db.py --check    # curated foods are valid and foods.db is current
```

Set `SYNC_TEST_URL=http://<server>:8080` to also run the end-to-end sync test against a live server.

## Design system

`design/tokens.json` is the single source of truth for colors (light and dark, four accents, the macro
palette), type, spacing, radii and motion. `python3 tools/gen_design_tokens.py` generates:

| File | Used by |
| --- | --- |
| `web/src/theme/tokens.css`, `web/src/theme/tokens.ts` | Web app |
| `app/src/main/java/com/nutrime/app/ui/theme/DesignTokens.kt` | Android Compose theme |
| `app/src/main/res/values{,-night}/design_tokens.xml` | Android window background and launcher icon |

Both apps use the Inter typeface and Lucide icons (`tools/gen_icons.py` converts them to Compose vectors).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). To report a security problem, see [SECURITY.md](SECURITY.md).

## License

NutriMe is released under the [MIT License](LICENSE).

Third-party data and assets:

- USDA FoodData Central: public domain (CC0 1.0)
- Open Food Facts: Open Database License (ODbL 1.0)
- Inter typeface: SIL Open Font License 1.1
- Lucide icons: ISC License

Nutrient targets are based on the US National Academies' Dietary Reference Intakes. They are general
guidance, not medical advice.
