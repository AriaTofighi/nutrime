# NutriMe sync server

A small Kotlin/Ktor API with PostgreSQL that stores your NutriMe data and syncs it between devices.

- Email/password accounts (bcrypt), with JWT tokens that are valid for one year
- Generic, local-first sync: the app pushes changed records and pulls everything newer than its cursor.
  Conflicts resolve last-writer-wins by modification time.
- Each user's data is isolated
- Auth endpoints are rate-limited; registration always requires a single-use, email-bound invitation
- An optional AI assistant (Gemini) that works with each user's synced data, see [AI assistant](#ai-assistant)

## Deploy on Coolify

The compose stack runs three services: `web` (the NutriMe web app, nginx), `api` (this server), and `db`
(PostgreSQL). One domain serves everything: the web app at `/`, and the sync API at `/api/*`. The web
container forwards `/api` to `api` over the private compose network, so only `web` gets a domain.

1. In Coolify, go to **Projects → (your project) → + New → Docker Compose**, then choose this GitHub
   repository (public repo, or private via the GitHub App).
2. Set **Base Directory** to `/server` and **Docker Compose Location** to `/docker-compose.yml`.
3. Under **Environment Variables**, add:
   - `POSTGRES_PASSWORD`: a long random password
   - `JWT_SECRET`: a long random string, e.g. the output of `openssl rand -hex 32` (at least 32 characters)
   - `ALLOW_REGISTRATION`: `true` to accept invitations, or `false` to block all new accounts
   - `ADMIN_EMAILS`: the email address of your existing owner account (or the first account you will invite)
   - `USDA_API_KEY` (optional): free key from https://fdc.nal.usda.gov/api-key-signup for online USDA
     search (web app and assistant). It stays on the server; without it the heavily rate-limited `DEMO_KEY` is used.
   - `GEMINI_API_KEY` (optional): turns on the AI assistant. Get one at https://aistudio.google.com/apikey.
4. Set the **Domain** of the `web` service to something like `https://nutrime.yourdomain.com` (port 80).
   Leave `api` and `db` without a domain. Coolify obtains the HTTPS certificate automatically.
5. **Deploy**. Then open `https://nutrime.yourdomain.com/api/health`; it should return `{"status":"ok",...}`,
   and `https://nutrime.yourdomain.com` shows the web app's sign-in page.
6. If this is a new server, create the first invitation from its API container console:
   `/app/bin/nutrime-server invite owner@example.com 7`. Use the same address as `ADMIN_EMAILS`.
   Copy the code privately and enter it when you create your account on the web app or in Android
   under **Profile → Sync & account**. Existing accounts do not need invitations. The Android
   app talks to the server it was built for: build it with `NUTRIME_SERVER_URL=https://nutrime.yourdomain.com`
   (see the main [README](../README.md#android-app)).
7. Sign in as the owner and open **Profile → Invitations** to invite friends. No account can be
   created without a matching, unexpired code. Set `ALLOW_REGISTRATION=false` and redeploy if you
   also want to pause valid invitations. Existing accounts keep working.

Data lives in the `nutrime-db` volume. Back it up with Coolify's scheduled database backups, or with
`docker exec <db-container> pg_dump -U nutrime nutrime > backup.sql`.

## Invitations

Registration requires an invitation on every server. `ALLOW_REGISTRATION=true` never enables open
registration. Existing accounts and login tokens are retained; this change adds only an `invitations`
table. Deploy the updated server and web app before the first public APK release. Older Android
versions can still sign in, but cannot create accounts because they have no invitation field.

Set `ADMIN_EMAILS` to the email of your trusted owner account, then redeploy. In the web app, open
**Profile → Invitations**, enter a friend's email, and choose an expiry (1, 7 or 30 days). Copy the code
when it appears and send it privately to that friend. They must register with that email. Codes are
shown once, stored as SHA-256 hashes, expire, and can be used only once. Recent invitations can be
revoked before use. No email is sent automatically. Revocation does not disable an existing account.
Treat the code as a password; do not put it in a URL, source file, issue, screenshot or server log.

For the first account, or if you cannot access the admin page, use the trusted **API container console**:

```sh
/app/bin/nutrime-server invite owner@example.com 7
```

This writes an invitation to the server database and shows its code in that console only. It does not
create the account or grant admin rights. For a local development database, with its environment
variables already set, use `./gradlew createInvite -PinviteEmail=owner@example.com -PinviteDays=7`.
Do not run this command in public CI logs. Never publish a real code in an example.

Admin APIs (all require a signed-in account in `ADMIN_EMAILS`):

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| GET | `/api/admin/invitations` | – | Latest 200 invitations, without codes or hashes |
| POST | `/api/admin/invitations` | `{email, days:7}` | `{invitation, code}`; code shown once |
| POST | `/api/admin/invitations/{id}/revoke` | `{}` | `{status:"ok"}` |

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `JWT_SECRET` | required | Signs login tokens. Changing it signs everyone out. |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/nutrime` | JDBC URL |
| `DATABASE_USER` / `DATABASE_PASSWORD` | – | DB credentials |
| `ALLOW_REGISTRATION` | `true` | Accept valid invitations; `false` blocks all new accounts, including invitations |
| `PORT` | `8080` | HTTP port |
| `USDA_API_KEY` | `DEMO_KEY` | USDA FoodData Central key for the shared food catalog (Android, web and assistant) |
| `GEMINI_API_KEY` | – | Gemini API key. The assistant is off without it |
| `GEMINI_MODEL` | `gemini-3.8-flash` | Gemini model id |
| `GEMINI_THINKING_LEVEL` | model default | Optional `minimal`, `low`, `medium` or `high`; lower is faster and cheaper |
| `AI_DAILY_MESSAGES` | `150` | Assistant messages each user can send per 24 hours |
| `ADMIN_EMAILS` | (none) | Comma-separated owner/admin account emails; admins can manage invitations and list candidates for the curated food database at `GET /api/foods/candidates` (see `data/foods/README.md`) |
| `FOODS_DB_PATH` | `/app/foods.db` | The bundled USDA food database (copied into the image from the Android app) |

## Run locally

```bash
cp .env.example .env    # edit the values
docker compose -f docker-compose.yml -f docker-compose.local.yml up --build
```

The local overlay publishes the web app on http://localhost:8080 (API at `/api`).

To run without Docker: `./gradlew run` with `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, and
`JWT_SECRET` set. For a throwaway database, use H2:
`DATABASE_URL="jdbc:h2:./nutrime;MODE=PostgreSQL"`.

## API

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| GET | `/api/health` | – | `{status, registration, invitationRequired}` |
| POST | `/api/auth/register` | `{email, password, invitationCode}` | `{token, userId, email}` |
| POST | `/api/auth/login` | `{email, password}` | `{token, userId, email}` |
| POST | `/api/sync` (Bearer token) | `{since, changes:[{collection,id,updatedAt,deleted,data}]}` | `{cursor, changes:[...], hasMore}` |

Sync collections: `diary`, `foods`, `weights`, `profile`, `days` (one record per date: "day complete",
note, steps and active energy) and `meals` (saved meals). Deploy the server before clients that use a new
collection: the server answers HTTP 400 for a collection it does not know.
| GET | `/api/ai/status` | – | `{enabled, model, dailyLimit, usedToday}` |
| POST | `/api/ai/chat` | `{conversationId?, text, images:[{mimeType,data}], today, localTime}` | server-sent events (below) |
| GET | `/api/ai/conversations` | – | `[{id, title, updatedAt}]` |
| GET / DELETE | `/api/ai/conversations/{id}` | – | `{id, title, messages:[{role, blocks}]}` |
| POST | `/api/ai/proposals/{id}/apply` or `/dismiss` | – | the proposal, with its new status |

All `/api/ai/*` endpoints need the Bearer token.

## AI assistant

The assistant runs here, on the server, so the Android app and the web app share one implementation
and one conversation history. Each turn:

1. The app syncs first, then posts the message (and any photos, downscaled to 1280 px) to `/api/ai/chat`.
2. The server builds a system prompt from the user's synced records (profile, targets, today's totals and
   gaps, the last 7 days) and streams the conversation to Gemini with a set of tools: `get_day`,
   `get_nutrient_report`, `get_weight_history`, `get_frequent_foods`, `search_foods` (several foods per
   call: the user's foods, the bundled USDA database, then the USDA API and Open Food Facts when local
   results are few or miss a word such as a brand), `get_food`, `get_my_foods` (custom foods and recipes with
   their ingredients), and `search_diary` (foods logged on any day, by name).
3. The model calls tools (those in one step run in parallel), the server runs them and sends back the
   results. A turn may make 30 tool calls or 10 model calls; after that the model gets a few more calls
   with only the `propose_*` tools, so it still ends with a proposal rather than stopping. Text, tool
   steps and proposals stream to the app as server-sent events: `conversation`, `text`, `tool`,
   `proposal`, `error`, `done`.
4. Changes are only proposed (`propose_log_foods`, `propose_quick_add`, `propose_edit_entries` (amount, meal,
   day or food), `propose_copy_entries`, `propose_delete_entries`, `propose_log_weight`, `propose_create_food`,
   `propose_save_recipe` (new recipe, or a custom food turned into a recipe with the same id),
   `propose_update_food`, `propose_delete_foods`, `propose_update_targets`). Each message has **one card**:
   every `propose_*` call in a turn adds to it (an item for the same food, meal and day replaces the earlier
   one), and the server sends it once the reply is complete. Food entries can carry the calories the user
   gave (`expected_kcal`) and the name of what they stand in for (`substitute_for`). Entries more than 25%
   off are sent back to the model to check the serving first; smaller differences go on the card. On the
   card the user can change an item's amount or meal, swap it for another match from the same search, or
   remove it (`POST /api/ai/proposals/{id}/items/{index}`). On **Confirm** the server writes ordinary sync
   records, and every device picks them up on its next sync. It also keeps how each record looked before
   (`ai_proposal_undo`), so **Undo** (`POST /api/ai/proposals/{id}/undo`) puts them back and makes the card
   pending again; records changed since then are left as they are.

The apps send their region (`country`, an ISO code) with chat messages and online food searches. Results are
ranked by `FoodQuality` (shared/): name match first, then how complete the data is (lab analyses list vitamins
and minerals; most Open Food Facts and branded entries copy only the label), then the source, with a small lift
for products sold in that country (Open Food Facts `countries_tags`, USDA `marketCountry`).

Conversations are stored in Postgres (`ai_conversations`, `ai_messages`, `ai_proposals`), including
Gemini's thought signatures, which Gemini requires on later turns. The code is in `src/main/kotlin/…/ai/`.

**Traces.** Admins (see `ADMIN_EMAILS`) get a *Traces* page in the web app that shows every step of
their own assistant chats: model text, each function call with its arguments, and each result
(`GET /api/admin/ai/conversations`, `GET /api/admin/ai/conversations/{id}/trace`). Other users' chats
are never shown.

**Evaluation.** `evals/cases.json` holds fixed requests with the expected card (foods, calories, at
most one card, no estimates). `GEMINI_API_KEY=... ./gradlew evalAssistant` runs them with the real
model and prints what passed. It calls the Gemini API, so it is not part of `test`; run it only when
needed. `EVAL_ONLINE=1` also lets food search call USDA and Open Food Facts.


## Shared food catalog

Android, web and the assistant share one provider gateway. The bundled database and private saved foods
remain local-first. Sign-in is required for online food lookup:

- `GET /api/foods/search?q=...` returns `foods`, `errors`, and optional `retryAfterSeconds`.
- `GET /api/foods/barcode/{code}` uses saved products first, then Open Food Facts and USDA.
- `GET /api/foods/item?id=off:...` (or `usda:...`) resolves a provider food id.

Provider foods and successful query mappings are stored permanently in PostgreSQL. Empty results expire
after 24 hours; failures expire after their retry delay. Normalized queries share cache entries across
users and the assistant. A database lease prevents concurrent duplicate requests, including across
server processes. New queries also search previously saved products. This is a cache of public provider
responses, not a copy of private foods, diaries or account search history. Product source URLs, licenses
and fetch times are retained. Cached records do not become checked NutriMe entries automatically.

The cache deliberately does not refresh successful queries in the background. Package recipes can change;
check a label before curating. There is no automatic refresh or client cache-bypass option. Provider
corrections require reviewed database maintenance while retaining source IDs and provenance. Back up
these tables with the existing PostgreSQL database.

Limits use database-locked rolling windows and survive restarts: Open Food Facts search **8/minute**
(shared by the new search endpoint and legacy fallback), product lookup **12/minute**; USDA **900/hour**,
or **30/hour and 50/day** for DEMO_KEY. Every upstream attempt consumes a slot. HTTP 429 pauses requests
for Retry-After (seconds or HTTP date); other failures pause the affected request group. Cache hits remain
available. Limits cover this NutriMe database; other software using the same public IP or USDA key can
still consume upstream capacity. Do not run independent NutriMe databases behind one IP without dividing
these budgets. Provider limits can change; keep these conservative caps in sync with their documentation.

Open Food Facts uses Search-a-licious first, with a limited legacy fallback for unavailable endpoints.
The server accepts both string and array brand fields. Missing serving weights stay missing; the app
uses grams rather than inventing a household serving. Errors log only provider names and status/type.

`GET /api/foods/candidates` includes cached public products for admin review, with source URL, license,
fetch time and unreviewed status. Check values before promoting them to `data/foods`. Open Food Facts
records retain their ODbL provenance; independent package-label curation needs its own documented basis.
See [Open Food Facts API limits](https://openfoodfacts.github.io/openfoodfacts-server/api/#rate-limits),
[search API](https://openfoodfacts.github.io/search-a-licious/users/ref-openapi/), and
[USDA API limits](https://fdc.nal.usda.gov/api-guide/).

## USDA Branded Foods (bulk import)

USDA FoodData Central "Branded Foods" is a public-domain (CC0 1.0) bulk download of manufacturer label data.
Imported into the catalog, it gives search one row per branded product without a live USDA call per query.
Do not copy data or product lists from proprietary databases. The import is an operator task: run it only
when you decide to, and back up PostgreSQL first.

1. Download the **Branded Foods, CSV** file from https://fdc.nal.usda.gov/download-datasets (do not commit it).
2. Prepare it on any machine with Python 3 (from the repository root). First look at the counts and the size:

   ```bash
   python3 tools/import_usda_branded.py FoodData_Central_branded_food_csv_<date>.zip --stats
   # Optional filters: --country "United States"   --min-brand-foods 20
   python3 tools/import_usda_branded.py FoodData_Central_branded_food_csv_<date>.zip --out branded.jsonl.gz
   ```

   The tool keeps one product per GTIN (the newest), drops discontinued products, applies the same nutrient
   rules as the live USDA parser (energy missing, negative or above 900 kcal per 100 g is rejected), and
   merges products with the same brand, name and energy within 5%.
3. Copy the file into the running `api` container and import it (Coolify: the api service's terminal, or
   `docker cp` on the host). The command uses the container's `DATABASE_URL`:

   ```bash
   docker cp branded.jsonl.gz <api-container>:/tmp/branded.jsonl.gz
   docker exec <api-container> /app/bin/nutrime-server import-branded /tmp/branded.jsonl.gz
   docker exec <api-container> rm /tmp/branded.jsonl.gz
   ```

Foods keep the ids `usda:<fdcId>`, so diary entries and `GET /api/foods/item` keep working. The import is
repeatable for each USDA release: rows are upserted by id, older foods stay, and the release date is saved in
`catalog_imports`. Names are cleaned with the shared `FoodNames` cleaner. After an import, USDA searches use
the stored catalog only; the live USDA API is still used for a single food id that is not stored (with the
provider budget). Open Food Facts stays live for barcode misses and for "More results".

On PostgreSQL the server creates a `pg_trgm` trigram index on `catalog_foods.search_text` at startup, so
`LIKE '%word%'` search stays fast. If the extension cannot be created, the server logs a warning and search
works without the index.

## Food database admin panel

Set `ADMIN_EMAILS` to the comma-separated email addresses of existing NutriMe accounts, then redeploy.
Leave it empty to disable admin access. Sign in to the web app and open **Database** (`#/database`).
Access is checked on the server against the signed-in user's stored email on every data request.
The navigation link is hidden for other accounts; hiding the link is not the access control.

The read-only panel separates NutriMe curated entries, permanently saved provider records, and the
bundled USDA reference library. Search and pagination read only SQLite and PostgreSQL; opening or
searching this panel makes no food-provider API requests. Food details include stored nutrients per
100 g, servings, source notes, aliases, and provider provenance where available. Cached records are
marked unreviewed. The panel does not expose users' foods, diaries, or account records.

- `GET /api/admin/status`: authenticated account's `admin` flag.
- `GET /api/admin/foods?collection=curated&q=rice&page=1`: admin-only, 25 records per page; collections
  are `curated`, `cached`, and `library`. Responses contain `items`, `total`, `page`, and `pageSize`.
- `FOODS_DB_PATH` supplies the bundled database. `CURATED_FOODS_PATH` supplies the directory of
  `data/foods/*.json` source notes. The production image includes both. For local server runs, point
  these to `../app/src/main/assets/foods.db` and `../data/foods` respectively.

This panel cannot edit or promote records. Curated changes still follow the process in `data/foods/README.md`.
