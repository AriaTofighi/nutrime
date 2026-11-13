/**
 * One ranking for every food search result, typing-error tolerance, and amounts typed with a food.
 * A copy of shared/.../model/SearchRank.kt and FoodQuality.kt.
 */
import type { Food } from "./foods";
import { N } from "./nutrients";
import type { DiaryEntry, Serving } from "./types";
import { dayDiff } from "./range";
import { addDays } from "./util";
import { displayName, isShouted } from "./foodNames";

const LABEL = new Set<number>([N.ENERGY, N.PROTEIN, N.CARBS, N.FAT, N.FIBER, N.SUGARS, N.ADDED_SUGARS, N.SAT_FAT, N.TRANS_FAT, N.CHOLESTEROL, N.SODIUM]);
const STOP = new Set(["and", "with", "in", "of", "the", "a", "an", "or", "to"]);

export const words = (s: string) => s.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w && !STOP.has(w));

/** A simple singular form, kept conservative: "tenderloins" → "tenderloin", "tomatoes" → "tomato", "hummus" stays. */
export function singular(w: string): string {
  if (w.length < 4) return w;
  if (w.length >= 5 && /(x|z|ch|sh|o)es$/.test(w)) return w.slice(0, -2);
  if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) return w.slice(0, -1);
  return w;
}

/** A query word matches a name word by prefix, either as typed or in singular form. */
export function wordMatches(t: string, w: string): boolean {
  if (w.startsWith(t) || (w.length >= 4 && t.startsWith(w))) return true;
  const st = singular(t), sw = singular(w);
  return sw.startsWith(st) || (sw.length >= 4 && st.startsWith(sw));
}

const PRIOR: Record<string, number> = { Custom: 1, Recipe: 1, NutriMe: 0.95, USDA: 0.85, "USDA Branded": 0.45, "Open Food Facts": 0.3 };

export const micronutrients = (f: Food) => Object.keys(f.nutrients).filter((k) => !LABEL.has(Number(k))).length;
export const isComplete = (f: Food) => micronutrients(f) >= 12;

export function qualityScore(f: Food): number {
  return 0.6 * Math.min(1, micronutrients(f) / 25) + 0.4 * (PRIOR[f.source] ?? 0.5);
}

export function matchedWords(q: string[], f: Food): number {
  const ws = words(`${f.name} ${f.brand ?? ""}`);
  return q.filter((t) => ws.some((w) => wordMatches(t, w))).length;
}

/** The same word, ignoring a plural ("tenderloins" = "tenderloin"). */
export const sameWord = (t: string, w: string) => singular(t) === singular(w);

/**
 * Name words that count for the fit, each once: brand words, details in brackets, and descriptors that
 * plain foods carry ("raw", "cooked", "no added fat") are left out unless the query has them.
 */
const NEUTRAL = new Set(["raw", "fresh", "plain", "whole", "nfs", "ns", "cooked", "boiled", "unsalted", "without", "salt", "no", "added", "fat", "prepared", "unprepared", "regular"]);
/** The name without details in brackets: "Salmon (Atlantic, farmed), raw" → "Salmon, raw". */
const withoutBrackets = (name: string) => name.replace(/\([^)]*\)/g, " ");
function fitWords(name: string, brand: string | undefined, q: string[]): string[] {
  const b = new Set(words(brand ?? "").map(singular));
  const all = [...new Set(words(withoutBrackets(name)).map(singular))];
  const kept = all.filter((w) => !b.has(w) && (!NEUTRAL.has(w) || q.some((t) => sameWord(t, w))));
  return kept.length ? kept : all;
}

/** 0..1: share of the name's words that are query words. "Chicken tenderloin" → 1; "Cracker Barrel, chicken tenderloin platter, fried" → 2/6. */
export function nameFit(q: string[], f: Food): number {
  const ws = fitWords(f.name, f.brand, q);
  if (!q.length || !ws.length) return 0;
  return ws.filter((w) => q.some((t) => sameWord(t, w))).length / ws.length;
}

/** Query words found as whole words (not only as the start of a longer word: "egg" in "eggplant"). */
export function wholeWords(q: string[], f: Food): number {
  const ws = words(`${f.name} ${f.brand ?? ""}`);
  return q.filter((t) => ws.some((w) => sameWord(t, w))).length;
}

/** The same words as the query, ignoring case, plurals, punctuation and "raw"-like words: "Chicken Tenderloins" = "chicken tenderloin". */
function sameWords(name: string, query: string): boolean {
  const y = words(query).map(singular);
  const x = words(withoutBrackets(name)).map(singular).filter((w) => !NEUTRAL.has(w) || y.includes(w));
  return x.length > 0 && x.length === y.length && x.every((w, i) => w === y[i]);
}

const RESTAURANT_CATEGORIES = new Set(["Restaurant Foods", "Fast Foods"]);
const RESTAURANT_NAME = /\b(fast ?foods?|restaurant)/i;

/** A restaurant or fast-food item: the USDA categories, or a name that says so ("…, from fast food"). */
export const isRestaurant = (f: Food) => (f.category != null && RESTAURANT_CATEGORIES.has(f.category)) || RESTAURANT_NAME.test(f.name);

/** True when the query names the restaurant: a word of a SHOUTED leading brand ("CRACKER BARREL, …") or of the brand, or "restaurant"/"fast". */
function asksForRestaurant(q: string[], f: Food): boolean {
  const lead = f.name.split(",")[0];
  const part = [...(isShouted(lead) ? words(lead) : []), ...words(f.brand ?? ""), "restaurant", "fast"];
  return q.some((t) => part.some((w) => wordMatches(t, w)));
}

export interface FoodUsage {
  foodId: string;
  count: number;
  daysSince: number;
  byMeal: number[];
  last: DiaryEntry;
}

export const usageScore = (u: FoodUsage) => Math.min(1, Math.log(1 + u.count) / Math.log(31)) * Math.pow(0.5, u.daysSince / 45);
export const mealShare = (u: FoodUsage, meal: number) => (u.count ? (u.byMeal[meal] ?? 0) / u.count : 0);

/** Usage of each food over the last 180 days, from the diary. */
export function foodUsage(entries: DiaryEntry[], today: string): Map<string, FoodUsage> {
  const since = addDays(today, -180);
  const out = new Map<string, FoodUsage>();
  for (const e of entries) {
    if (e.deleted || e.date < since || e.foodId.startsWith("quick:") || e.foodId.startsWith("import:")) continue;
    let u = out.get(e.foodId);
    if (!u) { u = { foodId: e.foodId, count: 0, daysSince: Infinity, byMeal: [0, 0, 0, 0], last: e }; out.set(e.foodId, u); }
    u.count++;
    if (e.meal >= 0 && e.meal < 4) u.byMeal[e.meal]++;
    if (e.createdAt > u.last.createdAt) u.last = e;
  }
  for (const u of out.values()) u.daysSince = Math.max(0, dayDiff(u.last.date > today ? today : u.last.date, today));
  return out;
}

/** Name match: share of query words found, the first word leading, the same words, and how much of the name the query covers. */
export function textScore(q: string[], f: Food, query: string): number {
  if (!q.length) return 0;
  let s = matchedWords(q, f) / q.length;
  const first = words(f.name)[0];
  if (first != null && (first.startsWith(q[0]) || singular(first) === singular(q[0]))) s += 0.1;
  if (sameWords(f.name, query)) s += 0.2;
  return s + 0.15 * wholeWords(q, f) / q.length + 0.25 * nameFit(q, f);
}

export interface RankOptions {
  usage: Map<string, FoodUsage>;
  meal?: number;
  favorites: Set<string>;
  /** Open Food Facts country name ("canada"), see countryName(). */
  country?: string;
  corrected?: Set<string>;
}

/** Weight of data quality in the ranking. */
export const QUALITY_WEIGHT = 0.2;

/**
 * Penalties that are not about the words: restaurant items the query did not ask for, and products
 * without a brand. Foods the user has logged get none.
 */
export function penalties(q: string[], f: Food): number {
  return (q.length && isRestaurant(f) && !asksForRestaurant(q, f) ? 0.2 : 0) + (f.source === "Open Food Facts" && !f.brand?.trim() ? 0.1 : 0);
}

export function score(f: Food, q: string[], query: string, o: RankOptions): number {
  const u = o.usage.get(f.id);
  const us = u ? usageScore(u) : 0;
  const share = u && o.meal != null ? mealShare(u, o.meal) : 0;
  return textScore(q, f, query) + 0.35 * us + 0.15 * share * us + QUALITY_WEIGHT * qualityScore(f) +
    (o.favorites.has(f.id) ? 0.15 : 0) + (o.country && f.countries?.includes(o.country) ? 0.08 : 0) -
    (o.corrected?.has(f.id) ? 0.25 : 0) - (u ? 0 : penalties(q, f));
}

export function rank(foods: Food[], query: string, o: RankOptions): Food[] {
  const q = words(query);
  const seen = new Set<string>();
  return foods
    .filter((f) => (seen.has(f.id) ? false : (seen.add(f.id), true)))
    .map((f, i) => ({ f, i, s: score(f, q, query, o) }))
    .sort((a, b) => b.s - a.s || a.i - b.i)
    .map((x) => x.f);
}

const LOCAL_SOURCES = new Set(["Custom", "Recipe", "NutriMe"]);
const sameEnergy = (a: number, b: number) => Math.abs(a - b) <= 0.05 * Math.max(Math.abs(a), Math.abs(b));
const productKey = (f: Food) => words(displayName(f.name, f.brand)).map(singular).join(" ") + "|" + words(f.brand ?? "").join(" ");

export interface CollapseOptions {
  /** Foods the user has logged: never hidden. */
  used: Set<string> | Map<string, unknown>;
  favorites: Set<string>;
  country?: string;
}

/**
 * One row per product: foods in rank order with the same cleaned name and brand and energy within 5%
 * are one product. The row kept (at the place of the best-ranked one) is the user's own or used food,
 * then a favourite, then the one with more nutrients, with a serving, sold in the user's country.
 * Custom, recipe and NutriMe foods are never merged, and a food the user logged or made a favourite
 * is never hidden.
 */
export function collapse(ranked: Food[], o: CollapseOptions): Food[] {
  const kept = (f: Food) => o.used.has(f.id) || o.favorites.has(f.id);
  const groups: { key: string; kcal: number; members: Food[] }[] = [];
  for (const f of ranked) {
    const kcal = f.nutrients[N.ENERGY] ?? 0;
    if (LOCAL_SOURCES.has(f.source)) { groups.push({ key: "", kcal, members: [f] }); continue; }
    const key = productKey(f);
    const g = groups.find((x) => x.key === key && sameEnergy(x.kcal, kcal) && !(kept(f) && x.members.some(kept)));
    if (g) g.members.push(f);
    else groups.push({ key, kcal, members: [f] });
  }
  const tie = (f: Food) => [o.used.has(f.id) ? 1 : 0, o.favorites.has(f.id) ? 1 : 0, Object.keys(f.nutrients).length, f.servings.length ? 1 : 0, o.country && f.countries?.includes(o.country) ? 1 : 0];
  return groups.map((g) => {
    let best = g.members[0];
    for (const f of g.members.slice(1)) {
      const a = tie(f), b = tie(best);
      const i = a.findIndex((v, k) => v !== b[k]);
      if (i >= 0 && a[i] > b[i]) best = f;
    }
    return best;
  });
}

/**
 * Open Food Facts as a fallback: the main list has every other source plus Open Food Facts products the
 * user has logged or made a favourite; the rest go under "More results". The order inside each is kept.
 */
export function splitMoreResults(ranked: Food[], used: Set<string> | Map<string, unknown>, favorites: Set<string>): { main: Food[]; more: Food[] } {
  const main: Food[] = [], more: Food[] = [];
  for (const f of ranked) (f.source !== "Open Food Facts" || used.has(f.id) || favorites.has(f.id) ? main : more).push(f);
  return { main, more };
}

export function usualFor(meal: number, usage: Iterable<FoodUsage>, limit = 6): FoodUsage[] {
  return [...usage].filter((u) => mealShare(u, meal) > 0).sort((a, b) => mealShare(b, meal) * usageScore(b) - mealShare(a, meal) * usageScore(a)).slice(0, limit);
}

// ---------------------------------------------------------------------------------------------
// Typing errors
// ---------------------------------------------------------------------------------------------

/** Damerau-Levenshtein (optimal string alignment) distance. */
export function distance(a: string, b: string): number {
  const d: number[][] = Array.from({ length: a.length + 1 }, (_, i) => Array.from({ length: b.length + 1 }, (_, j) => (i === 0 ? j : j === 0 ? i : 0)));
  for (let i = 1; i <= a.length; i++)
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
    }
  return d[a.length][b.length];
}

export const maxDistance = (w: string) => (w.length >= 8 ? 2 : 1);

export function corrections(word: string, vocab: [string, number][], limit = 3): string[] {
  if (word.length < 4) return [];
  const max = maxDistance(word);
  return vocab
    .filter(([w]) => w && w[0] === word[0] && Math.abs(w.length - word.length) <= max && w !== word)
    .map(([w, n]) => ({ w, n, d: distance(word, w) }))
    .filter((x) => x.d <= max)
    .sort((a, b) => a.d - b.d || b.n - a.n)
    .slice(0, limit)
    .map((x) => x.w);
}

// ---------------------------------------------------------------------------------------------
// Amount in the query
// ---------------------------------------------------------------------------------------------

export interface QueryAmount {
  query: string;
  amount: number;
  unit: string | null;
}

const UNITS: Record<string, string> = {
  g: "g", gr: "g", gram: "g", grams: "g", kg: "kg", oz: "oz", ounce: "oz", ounces: "oz",
  lb: "lb", lbs: "lb", pound: "lb", pounds: "lb", ml: "ml", l: "l",
  cup: "cup", cups: "cup", tbsp: "tbsp", tablespoon: "tbsp", tablespoons: "tbsp", tsp: "tsp", teaspoon: "tsp", teaspoons: "tsp",
};
const FRACTIONS: Record<string, number> = { "½": 0.5, "¼": 0.25, "¾": 0.75, "⅓": 1 / 3, "⅔": 2 / 3 };
const NUM = String.raw`(\d+(?:[.,]\d+)?|\d+/\d+|[½¼¾⅓⅔])`;
const UNIT_RE = Object.keys(UNITS).sort((a, b) => b.length - a.length).join("|");
const LEAD = new RegExp(String.raw`^${NUM}\s*(?:(${UNIT_RE})\b\.?|x\b|×)?\s*(?:of\s+)?(.+)$`, "i");
const TRAIL = new RegExp(String.raw`^(.+?)\s+${NUM}\s*(${UNIT_RE})?\.?$`, "i");
const GRAMS: Record<string, number> = { g: 1, kg: 1000, oz: 28.3495, lb: 453.592, ml: 1, l: 1000 };

function num(s: string): number | null {
  if (s in FRACTIONS) return FRACTIONS[s];
  if (s.includes("/")) {
    const [a, b] = s.split("/").map(Number);
    return b ? a / b : null;
  }
  const v = Number(s.replace(",", "."));
  return Number.isFinite(v) ? v : null;
}

export function parseQueryAmount(input: string): QueryAmount | null {
  const s = input.trim();
  const m = LEAD.exec(s);
  if (m) {
    const n = num(m[1]);
    const unit = m[2] ? UNITS[m[2].toLowerCase()] ?? null : null;
    const rest = m[3].trim();
    if (n != null && rest && !/^\d/.test(rest) && n > 0 && n <= 5000) return { query: rest, amount: n, unit };
  }
  const t = TRAIL.exec(s);
  if (t) {
    const n = num(t[2]);
    const unit = t[3] ? UNITS[t[3].toLowerCase()] ?? null : null;
    if (n != null && unit && n > 0 && n <= 5000) return { query: t[1].trim(), amount: n, unit };
  }
  return null;
}

export const amountLabel = (a: QueryAmount) => `${Number.isInteger(a.amount) ? a.amount : +a.amount.toFixed(2)}${a.unit ? " " + a.unit : ""}`;

/** The serving and quantity to open the food with. */
export function servingFor(a: QueryAmount, f: Food): { serving: Serving; quantity: number } {
  if (a.unit && a.unit in GRAMS) return { serving: { label: "g", grams: 1 }, quantity: a.amount * GRAMS[a.unit] };
  if (a.unit) {
    const s = f.servings.find((x) => x.label.toLowerCase().includes(a.unit!));
    if (s) return { serving: s, quantity: a.amount };
    return { serving: f.servings[0] ?? { label: "g", grams: 1 }, quantity: f.servings.length ? 1 : 100 };
  }
  const s = f.servings[0];
  return s ? { serving: s, quantity: a.amount } : { serving: { label: "g", grams: 1 }, quantity: 100 * a.amount };
}
