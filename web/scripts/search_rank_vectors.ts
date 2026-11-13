/**
 * Fixed food-search ranking cases, shared by the Kotlin and TypeScript code.
 *
 *   npx tsx scripts/search_rank_vectors.ts      # check searchRank.ts against shared/testdata/search_rank.json
 *
 * The Kotlin test (server/src/test/.../SharedLogicTest.kt) checks SearchRank.kt against the same file.
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { Food, FoodSource } from "../src/lib/foods";
import { countryName, displayName } from "../src/lib/foodNames";
import { collapse, matchedWords, rank, splitMoreResults, words, type FoodUsage } from "../src/lib/searchRank";

const FILE = join(dirname(fileURLToPath(import.meta.url)), "../../shared/testdata/search_rank.json");

interface VFood { id: string; source: string; name: string; brand?: string; category?: string; kcal: number; micros: number; servings?: [string, number][]; countries?: string[] }
interface VCase { query: string; country?: string; usage?: Record<string, number>; favorites?: string[]; first?: string; before?: [string, string][]; hidden?: string[]; shown?: string[]; moreResults?: string[] }

const SOURCES: Record<string, FoodSource> = { USDA: "USDA", NUTRIME: "NutriMe", USDA_BRANDED: "USDA Branded", OPEN_FOOD_FACTS: "Open Food Facts", CUSTOM: "Custom", RECIPE: "Recipe" };
/** Vitamin and mineral ids (not label nutrients), in a fixed order. */
const MICROS = [1051, 1057, 1018, 1292, 1293, 1404, 1278, 1272, 1316, 1106, 1162, 1114, 1109, 1185, 1165, 1166, 1167, 1170, 1175, 1190, 1178, 1180, 1087, 1098, 1089, 1090, 1101, 1091, 1092, 1103];

export function toFood(v: VFood): Food {
  const nutrients: Record<number, number> = { 1008: v.kcal };
  MICROS.slice(0, v.micros).forEach((id) => (nutrients[id] = 1));
  return { id: v.id, source: SOURCES[v.source], name: v.name, brand: v.brand, category: v.category, nutrients, servings: (v.servings ?? []).map(([label, grams]) => ({ label, grams })), countries: v.countries };
}

const data = JSON.parse(readFileSync(FILE, "utf8")) as { foods: VFood[]; cases: VCase[] };
const foods = data.foods.map(toFood);
let failures = 0;
for (const c of data.cases) {
  const q = words(c.query);
  const usage = new Map<string, FoodUsage>(Object.entries(c.usage ?? {}).map(([id, n]) => [id, { foodId: id, count: n, daysSince: 1, byMeal: [n, 0, 0, 0], last: undefined as never }]));
  const favorites = new Set(c.favorites ?? []);
  const candidates = foods.filter((f) => matchedWords(q, f) > 0);
  const ranked = collapse(rank(candidates, c.query, { usage, favorites, country: c.country }), { used: usage, favorites, country: c.country });
  const { main, more } = splitMoreResults(ranked, usage, favorites);
  const ids = ranked.map((f) => f.id);
  const errs: string[] = [];
  if (c.first && main[0]?.id !== c.first) errs.push(`first is ${main[0]?.id}, expected ${c.first}`);
  for (const [a, b] of c.before ?? []) {
    const i = ids.indexOf(a), j = ids.indexOf(b);
    if (i < 0 || j < 0 || i > j) errs.push(`${a} (at ${i}) must come before ${b} (at ${j})`);
  }
  for (const h of c.hidden ?? []) if (ids.includes(h)) errs.push(`${h} must be merged into another row`);
  for (const s of c.shown ?? []) if (!ids.includes(s)) errs.push(`${s} must be shown`);
  for (const m of c.moreResults ?? []) if (!more.some((f) => f.id === m)) errs.push(`${m} must be under "More results"`);
  const label = `${c.query}${c.usage ? " (with usage)" : ""}${c.favorites ? " (with favourite)" : ""}`;
  if (errs.length) {
    failures++;
    console.log(`FAIL ${label}\n  ${errs.join("\n  ")}\n  main: ${main.map((f) => f.id).join(", ")}\n  more: ${more.map((f) => f.id).join(", ")}`);
  } else console.log(`ok   ${label}`);
}
if (failures) {
  console.log(`${failures} case(s) failed`);
  process.exit(1);
}

// Display names: the same examples as SharedLogicTest.foodNamesForDisplay.
const NAMES: [string, string | null, string][] = [
  ["CRACKER BARREL, chicken tenderloin platter, fried", null, "Cracker Barrel, chicken tenderloin platter, fried"],
  ["CHICKEN TENDERLOINS", "Great Value", "Chicken tenderloins"],
  ["chicken  tenderloin ", null, "Chicken tenderloin"],
  ["Greek Yogurt, Plain - Kirkland Signature - Kirkland Signature", "Kirkland Signature", "Greek Yogurt, Plain"],
  ["Kirkland Signature Greek Yogurt", "Kirkland Signature", "Greek Yogurt"],
  ["APPLEBEE'S, chicken tenders", null, "Applebee's, chicken tenders"],
  ["Bananas, raw", null, "Bananas, raw"],
];
for (const [name, brand, want] of NAMES) {
  const got = displayName(name, brand);
  if (got !== want) { console.log(`FAIL name ${JSON.stringify(name)}: ${JSON.stringify(got)}, expected ${JSON.stringify(want)}`); process.exitCode = 1; }
}
for (const [code, want] of [["CA", "canada"], ["us", "united-states"], ["XX1", undefined]] as const) {
  if (countryName(code) !== want) { console.log(`FAIL country ${code}: ${countryName(code)}`); process.exitCode = 1; }
}
if (!process.exitCode) console.log("ok   display names and countries");
