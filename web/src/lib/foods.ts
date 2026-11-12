import { browserCountry } from "./assistant";
import { N, encodeNutrients, forGrams, parseNutrients, sum, type NutrientMap } from "./nutrients";
import { newId, store } from "./store";
import type { DiaryEntry, FoodKind, FoodRecord, RecipeIngredient, Serving } from "./types";

export type FoodSource = "USDA" | "NutriMe" | "USDA Branded" | "Open Food Facts" | "Custom" | "Recipe";

export interface Food {
  /** "usda:<fdcId>", "nm:<key>" (curated), "off:<barcode>", or "custom:<uuid>" — same ids as the Android app. */
  id: string;
  source: FoodSource;
  name: string;
  brand?: string;
  /** Per 100 g. */
  nutrients: NutrientMap;
  servings: Serving[];
  barcode?: string;
  category?: string;
}

/** Nutrients a nutrition label prints; anything else is a vitamin or mineral from a lab analysis. Mirrors FoodQuality.kt. */
const LABEL_NUTRIENTS = new Set<number>([N.ENERGY, N.PROTEIN, N.CARBS, N.FAT, N.FIBER, N.SUGARS, N.ADDED_SUGARS, N.SAT_FAT, N.TRANS_FAT, N.CHOLESTEROL, N.SODIUM]);

/** True for branded products that list only label values (no vitamins or minerals). */
export function isLabelOnly(f: Food): boolean {
  if (f.source !== "Open Food Facts" && f.source !== "USDA Branded") return false;
  return Object.keys(f.nutrients).filter((k) => !LABEL_NUTRIENTS.has(Number(k))).length < 12;
}

export function allServings(f: Food): Serving[] {
  const out = [...f.servings];
  if (!out.some((s) => s.label === "g")) out.push({ label: "g", grams: 1 });
  if (!out.some((s) => s.label === "oz")) out.push({ label: "oz", grams: 28.3495 });
  if (!out.some((s) => s.label === "100 g")) out.push({ label: "100 g", grams: 100 });
  return out;
}
export const defaultServing = (f: Food): Serving => f.servings[0] ?? { label: "g", grams: 1 };
export const defaultQuantity = (f: Food) => (f.servings.length === 0 ? 100 : 1);

const KIND_TO_SOURCE: Record<FoodKind, FoodSource> = {
  custom: "Custom",
  recipe: "Recipe",
  off: "Open Food Facts",
  usda_branded: "USDA Branded",
  usda: "USDA",
};

function parseServings(json: string | undefined): Serving[] {
  try {
    const v = JSON.parse(json ?? "[]");
    return Array.isArray(v) ? v.filter((s) => s && typeof s.label === "string" && s.grams > 0) : [];
  } catch {
    return [];
  }
}

export function recordToFood(r: FoodRecord): Food {
  return {
    id: r.id,
    source: KIND_TO_SOURCE[r.kind] ?? "USDA",
    name: r.name,
    brand: r.brand,
    nutrients: parseNutrients(r.nutrientsJson),
    servings: parseServings(r.servingsJson),
    barcode: r.barcode,
  };
}

// -----------------------------------------------------------------------------------------------
// Bundled database (USDA SR Legacy, Foundation and FNDDS, plus NutriMe curated foods), exported from
// the Android app's foods.db by scripts/export_foods.py
// -----------------------------------------------------------------------------------------------

/** [fdcId, name, category, nutrients, portions], plus [ref, brand, keywords, barcode] for curated foods. */
type BundledRow = [number, string, string, Record<string, number>, [string, number][], string?, (string | null)?, (string | null)?, (string | null)?];
interface Bundled {
  rows: BundledRow[];
  words: string[][];
  /** App id ("usda:<fdcId>" or "nm:<key>") -> row. */
  byId: Map<string, BundledRow>;
}

const bundledId = (r: BundledRow) => r[5] || `usda:${r[0]}`;
let bundled: Promise<Bundled> | null = null;

export function loadBundled(): Promise<Bundled> {
  if (!bundled) {
    bundled = fetch("/foods.json")
      .then((r) => {
        if (!r.ok) throw new Error("Couldn't load the food database");
        return r.json() as Promise<BundledRow[]>;
      })
      .then((rows) => ({
        rows,
        words: rows.map((r) => tokenize([r[1], r[6], r[7]].filter(Boolean).join(" "))),
        byId: new Map(rows.map((r) => [bundledId(r), r])),
      }))
      .catch((e) => {
        bundled = null;
        throw e;
      });
  }
  return bundled;
}

function bundledToFood(r: BundledRow): Food {
  const nutrients: NutrientMap = {};
  for (const k in r[3]) nutrients[Number(k)] = r[3][k];
  return {
    id: bundledId(r),
    source: r[5] ? "NutriMe" : "USDA",
    name: r[1],
    brand: r[6] || undefined,
    category: r[2],
    nutrients,
    servings: r[4].map(([label, grams]) => ({ label, grams })),
    barcode: r[8] || undefined,
  };
}

const tokenize = (q: string) => q.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);

/** Prefix match on every word, ranked like the app: curated first, then name starts with the first word, then "raw", then shorter. */
export async function searchBundled(query: string, limit = 60): Promise<Food[]> {
  const tokens = tokenize(query);
  if (!tokens.length) return [];
  const db = await loadBundled();
  const hits: { row: BundledRow; score: number }[] = [];
  for (let i = 0; i < db.rows.length; i++) {
    const words = db.words[i];
    if (!tokens.every((t) => words.some((w) => w.startsWith(t)))) continue;
    const name = db.rows[i][1].toLowerCase();
    const score = (db.rows[i][5] ? 0 : 100000) + (name.startsWith(tokens[0]) ? 0 : 10000) + (name.includes("raw") ? 0 : 1000) + name.length;
    hits.push({ row: db.rows[i], score });
  }
  hits.sort((a, b) => a.score - b.score);
  return hits.slice(0, limit).map((h) => bundledToFood(h.row));
}

/** Synonym pairs and the word list from foods-search.json (made with foods.json; see scripts/export_foods.py). */
interface SearchWords {
  syn: Map<string, string[]>;
  vocab: [string, number][];
}
let searchWords: Promise<SearchWords> | null = null;

function loadSearchWords(): Promise<SearchWords> {
  if (!searchWords) {
    searchWords = fetch("/foods-search.json")
      .then((r) => (r.ok ? (r.json() as Promise<{ syn: [string, string][]; vocab: [string, number][] }>) : { syn: [], vocab: [] }))
      .catch(() => ({ syn: [] as [string, string][], vocab: [] as [string, number][] }))
      .then((d) => {
        const syn = new Map<string, string[]>();
        for (const [a, b] of d.syn) (syn.get(a) ?? syn.set(a, []).get(a)!).push(b);
        return { syn, vocab: d.vocab };
      });
  }
  return searchWords;
}

/** The query with one word or two-word phrase replaced by a synonym (at most 4 variants). */
function synonymVariants(tokens: string[], syn: Map<string, string[]>): string[][] {
  const out: string[][] = [];
  for (let n = 1; n <= 2; n++)
    for (let i = 0; i + n <= tokens.length; i++)
      for (const same of syn.get(tokens.slice(i, i + n).join(" ")) ?? []) out.push([...tokens.slice(0, i), ...tokenize(same), ...tokens.slice(i + n)]);
  const seen = new Set<string>();
  return out.filter((v) => (seen.has(v.join(" ")) ? false : (seen.add(v.join(" ")), true))).slice(0, 4);
}

export interface BundledResults {
  foods: Food[];
  corrected: Set<string>;
  correctedQuery?: string;
}

/** Like the app: synonyms always, and typing-error correction when fewer than 5 foods match. */
export async function searchBundledDetailed(query: string, limit = 60): Promise<BundledResults> {
  const tokens = tokenize(query);
  if (!tokens.length) return { foods: [], corrected: new Set() };
  const words = await loadSearchWords();
  const found = new Map<string, Food>();
  for (const v of [tokens, ...synonymVariants(tokens, words.syn)]) for (const f of await searchBundled(v.join(" "), limit)) if (!found.has(f.id)) found.set(f.id, f);
  if (found.size >= 5 || !words.vocab.length) return { foods: [...found.values()].slice(0, limit), corrected: new Set() };
  const { corrections } = await import("./searchRank");
  let changed = false;
  const fixed = tokens.map((t) => {
    if (t.length < 4 || words.vocab.some(([w]) => w.startsWith(t))) return t;
    const c = corrections(t, words.vocab)[0];
    if (c) changed = true;
    return c ?? t;
  });
  if (!changed) return { foods: [...found.values()].slice(0, limit), corrected: new Set() };
  const corrected = new Set<string>();
  for (const v of [fixed, ...synonymVariants(fixed, words.syn)])
    for (const f of await searchBundled(v.join(" "), limit))
      if (!found.has(f.id)) {
        found.set(f.id, f);
        corrected.add(f.id);
      }
  return { foods: [...found.values()].slice(0, limit), corrected, correctedQuery: corrected.size ? fixed.join(" ") : undefined };
}

export function searchMine(query: string): Food[] {
  const tokens = tokenize(query);
  if (!tokens.length) return [];
  return store
    .all("foods")
    .filter((f) => {
      const hay = `${f.name} ${f.brand ?? ""}`.toLowerCase();
      return tokens.every((t) => hay.includes(t));
    })
    .map(recordToFood);
}

// -----------------------------------------------------------------------------------------------
// Online sources use the authenticated server catalog and its shared cache/budgets.
// -----------------------------------------------------------------------------------------------

type ServerFood = Omit<Food, "source"> & { source: string };
interface CatalogResults { foods: ServerFood[]; errors: string[]; retryAfterSeconds?: number }
const SERVER_SOURCES: Record<string, FoodSource> = {
  USDA: "USDA", NUTRIME: "NutriMe", USDA_BRANDED: "USDA Branded",
  OPEN_FOOD_FACTS: "Open Food Facts", CUSTOM: "Custom", RECIPE: "Recipe",
};

async function catalogLookup(path: string): Promise<{ foods: Food[]; errors: string[] }> {
  const token = store.account?.token;
  if (!token) throw new Error("Sign in to search online. Offline foods are still available.");
  let response: Response;
  try {
    response = await fetch(`/api/foods/${path}`, { headers: { Authorization: `Bearer ${token}` } });
  } catch { throw new Error("Cannot reach NutriMe. Check your connection."); }
  if (!response.ok) {
    if (response.status === 401) throw new Error("Sign in again to search online.");
    const error = await response.json().catch(() => null);
    throw new Error(error?.error ?? `Food search is unavailable (HTTP ${response.status}).`);
  }
  const result = await response.json() as CatalogResults;
  const foods = result.foods.map((food): Food => ({ ...food, source: SERVER_SOURCES[food.source] ?? "USDA" }));
  foods.forEach(remember);
  return { foods, errors: result.errors };
}

export async function searchOnline(query: string): Promise<{ foods: Food[]; errors: string[] }> {
  const country = browserCountry();
  try { return await catalogLookup(`search?q=${encodeURIComponent(query)}${country ? `&country=${country}` : ""}`); }
  catch (e) { return { foods: [], errors: [e instanceof Error ? e.message : "Food search is unavailable."] }; }
}

/** Own saved foods first, then the server's persistent provider catalog. */
export async function byBarcode(code: string): Promise<Food | null> {
  const clean = code.replace(/\D/g, "");
  if (!clean) return null;
  const mine = store.all("foods").find((f) => f.barcode && f.barcode.replace(/^0+/, "") === clean.replace(/^0+/, ""));
  if (mine) return recordToFood(mine);
  const result = await catalogLookup(`barcode/${clean}`);
  if (!result.foods.length && result.errors.length) throw new Error(result.errors.join(" "));
  return result.foods[0] ?? null;
}

// -----------------------------------------------------------------------------------------------
// Lookup & persistence
// -----------------------------------------------------------------------------------------------

const cache = new Map<string, Food>();
export function remember(f: Food) {
  cache.set(f.id, f);
}

export async function getFood(id: string): Promise<Food | null> {
  const mine = store.get("foods", id);
  if (mine) return recordToFood(mine);
  if (cache.has(id)) return cache.get(id)!;
  if (id.startsWith("usda:") || id.startsWith("nm:")) {
    const db = await loadBundled().catch(() => null);
    const row = db?.byId.get(id);
    if (row) return bundledToFood(row);
  }
  return null;
}

/** Persist online foods when used so they appear in search, work offline, and sync — like the app. */
async function ensureSaved(food: Food) {
  if (food.source === "Custom" || food.source === "Recipe") return;
  if (store.tables.foods.has(food.id)) return;
  // Bundled foods (USDA and NutriMe curated) are on every device already.
  if (food.source === "USDA" || food.source === "NutriMe") {
    const db = await loadBundled().catch(() => null);
    if (db?.byId.has(food.id)) return;
  }
  const kind: FoodKind = food.source === "Open Food Facts" ? "off" : food.source === "USDA Branded" ? "usda_branded" : "usda";
  store.put("foods", {
    id: food.id,
    kind,
    name: food.name,
    brand: food.brand,
    barcode: food.barcode,
    nutrientsJson: encodeNutrients(food.nutrients),
    servingsJson: JSON.stringify(food.servings),
    updatedAt: Date.now(),
    deleted: false,
  });
}

export async function logFood(date: string, meal: number, food: Food, serving: Serving, quantity: number) {
  await ensureSaved(food);
  const t = Date.now();
  store.put("diary", {
    id: newId(),
    date,
    meal,
    foodId: food.id,
    foodName: food.name,
    brand: food.brand,
    servingLabel: serving.label,
    servingGrams: serving.grams,
    quantity,
    nutrientsJson: encodeNutrients(food.nutrients),
    createdAt: t,
    updatedAt: t,
    deleted: false,
  });
}

export function updateEntry(e: DiaryEntry, meal: number, serving: Serving, quantity: number) {
  store.put("diary", { ...e, meal, servingLabel: serving.label, servingGrams: serving.grams, quantity });
}

export function copyDay(from: string, to: string) {
  const t = Date.now();
  const entries = store.all("diary").filter((e) => e.date === from);
  store.putMany(
    "diary",
    entries.map((e, i) => ({ ...e, id: newId(), date: to, createdAt: t + i, updatedAt: t + i, deleted: false })),
  );
  return entries.length;
}

/** Log an entry again (same food and amount) on [to]. Returns the new entry's id. */
export function copyEntry(e: DiaryEntry, to: string): string {
  const t = Date.now();
  const id = newId();
  store.put("diary", { ...e, id, date: to, createdAt: t, deleted: false }, t);
  return id;
}

/**
 * Put a day's entries in this order. The diary is ordered by createdAt, so the entries swap their
 * createdAt values (kept strictly increasing); other devices see the new order after sync.
 */
export function reorderEntries(ordered: DiaryEntry[]) {
  const slots = ordered.map((e) => e.createdAt).sort((a, b) => a - b);
  for (let i = 1; i < slots.length; i++) if (slots[i] <= slots[i - 1]) slots[i] = slots[i - 1] + 1;
  const t = Date.now();
  const changed = ordered.map((e, i) => ({ ...e, createdAt: slots[i], updatedAt: t + i })).filter((e, i) => e.createdAt !== ordered[i].createdAt);
  if (changed.length) store.putMany("diary", changed);
}

export function entryNutrients(e: DiaryEntry): NutrientMap {
  return forGrams(parseNutrients(e.nutrientsJson), e.servingGrams * e.quantity);
}

export function saveCustomFood(
  id: string | undefined,
  name: string,
  brand: string,
  barcode: string,
  per100: NutrientMap,
  servings: Serving[],
): string {
  const fid = id ?? `custom:${newId()}`;
  store.put("foods", {
    id: fid,
    kind: "custom",
    name,
    brand: brand.trim() || undefined,
    barcode: barcode.trim() || undefined,
    nutrientsJson: encodeNutrients(per100),
    servingsJson: JSON.stringify(servings),
    updatedAt: Date.now(),
    deleted: false,
  });
  return fid;
}

export function recipeIngredients(r: FoodRecord): RecipeIngredient[] {
  try {
    return JSON.parse(r.ingredientsJson ?? "[]");
  } catch {
    return [];
  }
}

export const ingredientNutrients = (i: RecipeIngredient) =>
  forGrams(parseNutrients(JSON.stringify(i.nutrients)), i.servingGrams * i.quantity);

export function saveRecipe(id: string | undefined, name: string, servings: number, ingredients: RecipeIngredient[]): string {
  const rid = id ?? `custom:${newId()}`;
  const total = Math.max(0.0001, ingredients.reduce((g, i) => g + i.servingGrams * i.quantity, 0));
  const totals = sum(ingredients.map(ingredientNutrients));
  const per100: NutrientMap = {};
  for (const k in totals) per100[k] = (totals[k] * 100) / total;
  store.put("foods", {
    id: rid,
    kind: "recipe",
    name,
    nutrientsJson: encodeNutrients(per100),
    servingsJson: JSON.stringify([
      { label: "serving", grams: total / Math.max(0.0001, servings) },
      { label: "whole recipe", grams: total },
    ]),
    ingredientsJson: JSON.stringify(ingredients),
    recipeServings: servings,
    updatedAt: Date.now(),
    deleted: false,
  });
  return rid;
}
