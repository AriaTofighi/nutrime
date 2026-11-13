# Notes for coding agents

NutriMe is an Android app (`app/`, Kotlin + Compose), a web app (`web/`, React + Vite) and a sync/AI
server (`server/`, Ktor, its own Gradle build). Shared models and logic live in `shared/`. See `README.md`.

The web app has TypeScript copies of shared logic (`web/src/lib/burn.ts`, `goal.ts`, `range.ts`,
`searchRank.ts`, `foodNames.ts`, `insights.ts`, `export.ts`). Change both sides together. A new sync collection needs the
server's `COLLECTIONS`, the app's `SyncManager` and Room schema (with a migration), and `web/src/lib/types.ts`.

## Finishing a task: always merge to main

This applies to the owner's own agent sessions. External contributors open a pull request instead
(see `CONTRIBUTING.md`). Unless the owner says otherwise for that task:

1. Start from current `main`. Before you push an older branch, merge current `main` into it and
   retain the manual-only workflow policy below.
2. Work on your feature branch and commit there.
3. Review the changes. Run application tests only when the owner explicitly requests them.
   TypeScript type checking is allowed without a separate request.
4. Merge the branch into `main` (fast-forward or a merge commit; no PR needed) and push `main`.
   Preserve other contributors' changes. Do not wait for GitHub CI: automatic CI is disabled.
5. Report what changed and which checks ran. Merging code does not authorize an APK release.

## APK releases: explicit request only

- Build or publish a release APK only when the owner explicitly asks for a new APK or release.
  A request to implement, fix, commit, push, or merge code is not a release request.
- GitHub Actions has one active workflow: `.github/workflows/apk-release.yml` (Build APK release).
  It runs only through `workflow_dispatch` on `main`. It builds the signed APK and publishes
  `nutrime.apk` to GitHub Releases in one job. It does not run tests.
- Do not dispatch or rerun that workflow without an explicit release request.
- Do not add push, pull-request, tag, or scheduled build/test triggers. Keep tests and Docker checks
  off GitHub Actions.
- The release signing key is a GitHub secret (`NUTRIME_KEYSTORE_BASE64`, `NUTRIME_KEYSTORE_PASSWORD`).
  Never commit a keystore, a password, or a `.env` file. The app's server URL comes from the
  `NUTRIME_SERVER_URL` repository variable; do not hard-code a deployment's domain in the code.
- Keep the same signing key and an increasing Android version code so updates install over
  installed releases. The manual workflow sets `NUTRIME_BUILD_NUMBER` to `1100 + github.run_number` (the offset keeps it
  above the builds from the earlier repository, which reached 1006).
  Do not reset that counter or rename the release workflow without preserving version continuity.

## Checks outside GitHub Actions

Do not run application tests unless explicitly requested. When requested, run them locally or in
a development cloud environment with the required tools, not in GitHub Actions. If the environment
cannot run a check, report that limit instead of starting a GitHub workflow. Keep existing test code.

- Server tests: `cd server && ./gradlew test` (uses `../app/src/main/assets/foods.db`; `SharedLogicTest`
  covers the shared logic and the learned-burn vectors in `shared/testdata/`).
- Android tests: `./gradlew :app:testDebugUnitTest` (requires an Android SDK).
- Web type check: `cd web && npm ci && npx tsc --noEmit`.
- Learned-burn vectors (web copy): `cd web && npx tsx scripts/burn_vectors.ts`.
- Search ranking cases (web copy): `cd web && npx tsx scripts/search_rank_vectors.ts` (`shared/testdata/search_rank.json`;
  `SharedLogicTest` checks the Kotlin side).
- Generated design files: `python3 tools/gen_design_tokens.py --check`.
- Server and web Docker images are built by the deployment platform when it deploys them;
  GitHub Actions does not need to build another copy for each code change.

Reply to the owner in ASD-STE100 Simplified Technical English.

## Food database

The bundled `app/src/main/assets/foods.db` (USDA SR Legacy, Foundation, FNDDS, plus NutriMe's curated foods)
is shared by the app, the web app and the server. NutriMe's curated foods live in `data/foods/*.json`; read
`data/foods/README.md` before adding any. In short:

- Every entry needs a `basis` saying where its numbers come from. Never invent nutrient values.
- Branded foods come from a checked package label. `GET /api/foods/candidates` (admin) lists what people
  log most, with drafts to check.
- After editing, run `python3 tools/update_food_db.py`, bump `BundledFoodDb.ASSET_VERSION`, and commit the
  JSON and `foods.db` together. Run `--check` locally when authorized. Never rename or reuse a `key`: diary entries point to it.

## Gradle downloads

Both builds (`settings.gradle.kts`, `server/settings.gradle.kts`, `server/build.gradle.kts`) fetch
Maven Central artifacts from Google's mirror
(`https://maven-central.storage-download.googleapis.com/maven2/`) first, because Maven Central itself
often answers CI and cloud machines with HTTP 429. Keep the mirror first when adding repositories, and
don't add `mavenCentral()` ahead of it.
