# NutriMe web app

A browser version of NutriMe (React + TypeScript + Vite) that signs in to the same self-hosted sync
server as the Android app. Your diary, custom foods, recipes, weights and profile sync both ways.

- **Diary**: one log for the day (food and weigh-ins), an energy summary (consumed, burned, remaining), macro targets, all 40+ nutrients against your targets, and a calendar
- **Food search** over the same 13,600+ bundled foods as the app, plus USDA Branded and Open Food
  Facts online, and barcode lookup by number
- **Custom foods** from a nutrition label and **recipes** built from ingredients
- **Trends**: average intake against the target first, then calories by macro, macros, weight, and the few
  nutrients furthest below target (all nutrients and the weigh-in history open separately)
- **Profile**: body stats, goal, macro split, imperial/metric, and custom nutrient targets
- Local-first: data is cached in IndexedDB, edits sync a moment later (and on focus, and every minute)

## Design system

All styling goes through design tokens in [`src/theme/tokens.css`](src/theme/tokens.css): a gray scale and
accent per theme, semantic colors (`--surface`, `--text-secondary`, `--protein`, …), type scale, spacing,
radii, shadows and motion. Components in `src/styles/` (one file per area, imported in order by `index.css`) use only these variables. Light, dark and
system modes, a dim or black dark theme, eight accent colors or a custom hue, and the text size are set on `<html>`
(`data-theme`, `data-dark`, `data-accent`, `--accent-h`, `font-size`) by
[`src/theme/theme.ts`](src/theme/theme.ts); the choice, and the diary summary options, are saved per browser
(Profile → Appearance, or the toggle in the sidebar).

The UI uses [shadcn/ui](https://ui.shadcn.com) components (`src/components/shadcn/`, config in `components.json`)
with Tailwind CSS v4 ([`src/styles/tailwind.css`](src/styles/tailwind.css)): buttons, inputs, text areas, native
selects, switches, sliders, toggle groups, badges, cards and dialogs (a bottom sheet on phones). Tailwind's colors,
radii and shadows map to the design tokens, so the components follow the theme. Tailwind's preflight is not used;
the older layout CSS is in the `app` cascade layer, so utility classes override it (use a Tailwind variant such as
`max-[860px]:hidden`, not an `app` rule, to hide a component). Shared building blocks (`Card`, `Modal`, `Segmented`,
rings, meters, calendar) live in `src/components/ui.tsx` and wrap the shadcn components. Inter is bundled; icons are Lucide.

## How it talks to the backend

The web app is the `web` service in [`server/docker-compose.yml`](../server/docker-compose.yml), deployed
together with the sync server and its database. Its nginx serves the built app and reverse-proxies:

| Path | Goes to |
| --- | --- |
| `/api/*` | `NUTRIME_API_URL` (default `http://api:8080`, the `api` service on the compose network) |

So one domain serves both the web app and the API (`https://your-domain/api/...`), which is also the
server URL you build the Android app with. The browser only talks to its own origin, so the sync server
needs no CORS settings. Online food search uses authenticated `/api/foods/*` routes; provider keys, caching and limits are managed by the API.

See [`server/README.md`](../server/README.md) for deployment steps.

## Develop

```bash
cd web
npm install
npm run foods          # export ../app/src/main/assets/foods.db to public/foods.json and public/foods-search.json (needs python3)
npm run dev            # http://localhost:5173, proxies /api to http://localhost:8080
```

Set `NUTRIME_API=https://your-server npm run dev` to use another sync server.

`src/lib/burn.ts`, `goal.ts`, `range.ts`, `searchRank.ts`, `insights.ts` and `export.ts` are copies of the
Kotlin code in `shared/`. When you change one side, change the other. The learned-burn filter has shared
test vectors: `npx tsx scripts/burn_vectors.ts` checks this copy, and `--write` makes new vectors.
