# Contributing to NutriMe

Thank you for your help. Issues and pull requests are welcome.

## Before you start

- For a large change, open an issue first so we can agree on the approach.
- Read [`AGENTS.md`](AGENTS.md): it lists the rules that keep the Android app, the web app and the
  server consistent. They apply to people and coding agents.

## Rules that are easy to miss

- **Shared logic has two copies.** The Kotlin code in `shared/` has TypeScript copies in `web/src/lib/`
  (`burn.ts`, `goal.ts`, `range.ts`, `searchRank.ts`, `insights.ts`, `export.ts`). Change both together.
  The learned-burn filter has shared test vectors in `shared/testdata/`.
- **A new sync collection** needs the server's `COLLECTIONS`, the app's `SyncManager` and Room schema
  (with a migration), and `web/src/lib/types.ts`.
- **Food data**: never invent nutrient values. Every curated food needs a `basis` that says where its
  numbers come from. Read [`data/foods/README.md`](data/foods/README.md) before adding foods.
- **Design**: change `design/tokens.json` and run `python3 tools/gen_design_tokens.py`. Do not edit the
  generated files by hand.

## Checks

Run the checks for the parts you changed (see "Tests and checks" in the [README](README.md)) and say in
your pull request which ones you ran.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
