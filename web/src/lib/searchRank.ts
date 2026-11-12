/**
 * One ranking for every food search result, typing-error tolerance, and amounts typed with a food.
 * A copy of shared/.../model/SearchRank.kt and FoodQuality.kt.
 */
import type { Food } from "./foods";
import { N } from "./nutrients";
import type { DiaryEntry, Serving } from "./types";
import { dayDiff } from "./range";
import { addDays } from "./util";

const LABEL = new Set<number>([N.ENERGY, N.PROTEIN, N.CARBS, N.FAT, N.FIBER, N.SUGARS, N.ADDED_SUGARS, N.SAT_FAT, N.TRANS_FAT, N.CHOLESTEROL, N.SODIUM]);
const STOP = new Set(["and", "with", "in", "of", "the", "a", "an", "or", "to"]);

export const words = (s: string) => s.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w && !STOP.has(w));

const PRIOR: Record<string, number> = { Custom: 1, Recipe: 1, NutriMe: 0.95, USDA: 0.85, "USDA Branded": 0.45, "Open Food Facts": 0.3 };

export function qualityScore(f: Food): number {
  const micros = Object.keys(f.nutrients).filter((k) => !LABEL.has(Number(k))).length;
  return 0.6 * Math.min(1, micros / 25) + 0.4 * (PRIOR[f.source] ?? 0.5);
}

export function matchedWords(q: string[], f: Food): number {
  const ws = words(`${f.name} ${f.brand ?? ""}`);
  return q.filter((t) => ws.some((w) => w.startsWith(t) || (w.length >= 4 && t.startsWith(w)))).length;
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

export function textScore(q: string[], f: Food, query: string): number {
  if (!q.length) return 0;
  let s = matchedWords(q, f) / q.length;
  if (words(f.name)[0]?.startsWith(q[0])) s += 0.1;
  if (f.name.trim().toLowerCase() === query.trim().toLowerCase()) s += 0.2;
  return s;
}

export interface RankOptions {
  usage: Map<string, FoodUsage>;
  meal?: number;
  favorites: Set<string>;
  corrected?: Set<string>;
}

export function score(f: Food, q: string[], query: string, o: RankOptions): number {
  const u = o.usage.get(f.id);
  const us = u ? usageScore(u) : 0;
  const share = u && o.meal != null ? mealShare(u, o.meal) : 0;
  return textScore(q, f, query) + 0.35 * us + 0.15 * share * us + 0.3 * qualityScore(f) + (o.favorites.has(f.id) ? 0.15 : 0) - (o.corrected?.has(f.id) ? 0.25 : 0);
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
