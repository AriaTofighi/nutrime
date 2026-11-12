/**
 * Import data from other apps (web only): Cronometer servings and biometrics, MyFitnessPal nutrition
 * summary and measurements, and NutriMe's own export ZIP.
 *
 * Column names follow the services' published export formats. A column that is not known is listed in
 * the preview and ignored. Nothing is written until the user confirms the preview.
 */
import { N, encodeNutrients, type NutrientMap } from "./nutrients";
import { LB_PER_KG } from "./profile";
import { store } from "./store";
import type { CollectionName, Collections, DiaryEntry, WeightRecord } from "./types";
import { unzip } from "./export";

export type ImportSource = "cronometer-servings" | "cronometer-biometrics" | "mfp-nutrition" | "mfp-measurements" | "nutrime";

export interface ImportPlan {
  source: ImportSource;
  label: string;
  entries: DiaryEntry[];
  weights: WeightRecord[];
  /** For a NutriMe export: records of every collection. */
  records?: { [C in CollectionName]?: Collections[C][] };
  ignored: string[];
  from?: string;
  to?: string;
  /** Days in the file that already have diary entries. */
  existingDays: string[];
  /** MyFitnessPal measurements have no unit; the user picks it. */
  needsWeightUnit?: boolean;
}

/** RFC 4180 CSV: quoted cells, doubled quotes, new lines inside quotes. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let q = false;
  const s = text.replace(/^﻿/, "");
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (q) {
      if (c === '"') {
        if (s[i + 1] === '"') { cell += '"'; i++; } else q = false;
      } else cell += c;
    } else if (c === '"') q = true;
    else if (c === ",") { row.push(cell); cell = ""; }
    else if (c === "\n" || c === "\r") {
      if (c === "\r" && s[i + 1] === "\n") i++;
      row.push(cell); cell = "";
      if (row.some((x) => x.trim() !== "")) rows.push(row);
      row = [];
    } else cell += c;
  }
  row.push(cell);
  if (row.some((x) => x.trim() !== "")) rows.push(row);
  return rows;
}

/** "Vitamin D (IU)" -> { key: "vitamin d", unit: "iu" } */
function header(h: string): { key: string; unit: string | null } {
  const m = /^(.*?)\s*\(([^()]*)\)\s*$/.exec(h.trim());
  const key = (m ? m[1] : h).trim().toLowerCase().replace(/\s+/g, " ");
  return { key, unit: m ? m[2].trim().toLowerCase() : null };
}

/** Column name (lowercase, without the unit) -> nutrient id. Both Cronometer and MyFitnessPal names. */
const COLUMN_TO_NUTRIENT: Record<string, number> = {
  energy: N.ENERGY, calories: N.ENERGY,
  protein: N.PROTEIN,
  carbs: N.CARBS, carbohydrates: N.CARBS,
  fat: N.FAT,
  fiber: N.FIBER,
  sugars: N.SUGARS, sugar: N.SUGARS,
  "added sugars": N.ADDED_SUGARS,
  water: N.WATER,
  caffeine: N.CAFFEINE,
  alcohol: N.ALCOHOL,
  saturated: N.SAT_FAT, "saturated fat": N.SAT_FAT,
  monounsaturated: N.MONO_FAT, "monounsaturated fat": N.MONO_FAT,
  polyunsaturated: N.POLY_FAT, "polyunsaturated fat": N.POLY_FAT,
  "trans-fats": N.TRANS_FAT, "trans fat": N.TRANS_FAT, "trans fats": N.TRANS_FAT,
  cholesterol: N.CHOLESTEROL,
  "omega-3": N.OMEGA3_ALA, "omega-6": N.OMEGA6_LA,
  "vitamin a": N.VIT_A, "vitamin c": N.VIT_C, "vitamin d": N.VIT_D, "vitamin e": N.VIT_E, "vitamin k": N.VIT_K,
  "b1 (thiamine)": N.THIAMIN, "b2 (riboflavin)": N.RIBOFLAVIN, "b3 (niacin)": N.NIACIN, "b5 (pantothenic acid)": N.PANTOTHENIC,
  "b6 (pyridoxine)": N.B6, "b12 (cobalamin)": N.B12, folate: N.FOLATE, choline: N.CHOLINE,
  calcium: N.CALCIUM, copper: N.COPPER, iron: N.IRON, magnesium: N.MAGNESIUM, manganese: N.MANGANESE,
  phosphorus: N.PHOSPHORUS, potassium: N.POTASSIUM, selenium: N.SELENIUM, sodium: N.SODIUM, zinc: N.ZINC,
};

/** Cronometer writes B vitamins as "B1 (Thiamine) (mg)": the name keeps its own parentheses. */
function nutrientFor(h: string): { id: number; factor: number } | null {
  const { key, unit } = header(h);
  const id = COLUMN_TO_NUTRIENT[key];
  if (id == null) return null;
  // MyFitnessPal lists vitamins A and C, calcium and iron as % daily value: not amounts.
  if (unit === "%" || unit === "% dv") return null;
  let factor = 1;
  if (id === N.VIT_D && unit === "iu") factor = 0.025;
  else if (id === N.VIT_A && unit === "iu") factor = 0.3;
  else if (unit === "kj") factor = 1 / 4.184;
  return { id, factor };
}

const MEAL_NAMES: Record<string, number> = { breakfast: 0, lunch: 1, dinner: 2, snacks: 3, snack: 3 };
const mealOf = (s: string | undefined) => {
  const k = (s ?? "").trim().toLowerCase();
  return MEAL_NAMES[k] ?? (/^meal ?(\d)/.exec(k) ? Math.min(3, Number(/\d/.exec(k)![0]) - 1) : 3);
};

function hash(s: string): string {
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193;
  for (let i = 0; i < s.length; i++) {
    h1 = Math.imul(h1 ^ s.charCodeAt(i), 16777619) >>> 0;
    h2 = Math.imul(h2 ^ s.charCodeAt(i), 2246822519) >>> 0;
  }
  return h1.toString(16).padStart(8, "0") + h2.toString(16).padStart(8, "0");
}

/** "2026-03-01", "03/01/2026", "1/3/2026" (month first, as both services write in English). */
function isoOf(s: string): string | null {
  const t = s.trim();
  let m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(t);
  if (m) return `${m[1]}-${m[2].padStart(2, "0")}-${m[3].padStart(2, "0")}`;
  m = /^(\d{1,2})\/(\d{1,2})\/(\d{2,4})/.exec(t);
  if (m) {
    const y = m[3].length === 2 ? "20" + m[3] : m[3];
    return `${y}-${m[1].padStart(2, "0")}-${m[2].padStart(2, "0")}`;
  }
  return null;
}

const num = (s: string | undefined) => {
  const v = Number((s ?? "").replace(/,/g, "").trim());
  return Number.isFinite(v) ? v : null;
};

function entryFrom(date: string, meal: number, name: string, amount: string, totals: NutrientMap, order: number): DiaryEntry {
  const id = "import:" + hash([date, meal, name.toLowerCase(), amount, order].join("|"));
  // Grams in the amount ("100.00 g", "1 cup - 158 g") give a real weight; otherwise store totals as one 100 g unit.
  const g = /([\d.]+)\s*g\b(?!\w)/.exec(amount);
  const grams = g ? Number(g[1]) : 0;
  const per100: NutrientMap = {};
  const scale = grams > 0 ? 100 / grams : 1;
  for (const [k, v] of Object.entries(totals)) per100[Number(k)] = v * scale;
  const t = Date.now();
  return {
    id,
    date,
    meal,
    foodId: id,
    foodName: name || "Imported food",
    servingLabel: grams > 0 ? "g" : amount || "imported",
    servingGrams: grams > 0 ? 1 : 100,
    quantity: grams > 0 ? grams : 1,
    nutrientsJson: encodeNutrients(per100),
    createdAt: t + order,
    updatedAt: t,
    deleted: false,
  };
}

function finish(plan: Omit<ImportPlan, "from" | "to" | "existingDays">): ImportPlan {
  const dates = [...plan.entries.map((e) => e.date), ...plan.weights.map((w) => w.date)].sort();
  const fileDays = new Set(plan.entries.map((e) => e.date));
  const existingDays = [...new Set(store.all("diary").filter((e) => fileDays.has(e.date) && !e.id.startsWith("import:")).map((e) => e.date))].sort();
  return { ...plan, from: dates[0], to: dates[dates.length - 1], existingDays };
}

function weight(date: string, kg: number, source: string): WeightRecord {
  return { id: "import:" + hash(`w|${source}|${date}`), date, weightKg: Math.round(kg * 100) / 100, updatedAt: Date.now(), deleted: false };
}

function fromCsv(text: string): ImportPlan {
  const rows = parseCsv(text);
  if (rows.length < 1) throw new Error("The file is empty.");
  const head = rows[0].map((h) => h.trim());
  const lower = head.map((h) => h.toLowerCase());
  const col = (name: string) => lower.indexOf(name);
  const body = rows.slice(1);

  // Cronometer servings: Day, Time, Group, Food Name, Amount, Energy (kcal), …
  if (col("day") >= 0 && col("food name") >= 0) {
    const nut = head.map(nutrientFor);
    const ignored = head.filter((_, i) => !nut[i] && !["day", "time", "group", "food name", "amount", "category"].includes(lower[i]));
    const entries = body.map((r, i) => {
      const date = isoOf(r[col("day")] ?? "");
      if (!date) return null;
      const totals: NutrientMap = {};
      nut.forEach((n, j) => { const v = n && num(r[j]); if (n && v) totals[n.id] = (totals[n.id] ?? 0) + v * n.factor; });
      return entryFrom(date, mealOf(r[col("group")]), r[col("food name")] ?? "", r[col("amount")] ?? "", totals, i);
    }).filter((e): e is DiaryEntry => e != null);
    return finish({ source: "cronometer-servings", label: "Cronometer food log", entries, weights: [], ignored });
  }
  // Cronometer biometrics: Day, Group, Metric, Unit, Amount
  if (col("day") >= 0 && col("metric") >= 0) {
    const weights = body.filter((r) => /weight/i.test(r[col("metric")] ?? "") && !/fat/i.test(r[col("metric")] ?? "")).map((r) => {
      const date = isoOf(r[col("day")] ?? "");
      const v = num(r[col("amount")]);
      if (!date || !v) return null;
      const lb = /lb/i.test(r[col("unit")] ?? "");
      return weight(date, lb ? v / LB_PER_KG : v, "cronometer");
    }).filter((w): w is WeightRecord => w != null);
    return finish({ source: "cronometer-biometrics", label: "Cronometer weights", entries: [], weights, ignored: [] });
  }
  // MyFitnessPal nutrition summary: Date, Meal, Calories, Fat (g), …
  if (col("date") >= 0 && col("meal") >= 0 && col("calories") >= 0) {
    const nut = head.map(nutrientFor);
    const ignored = head.filter((_, i) => !nut[i] && !["date", "meal", "note", "time"].includes(lower[i]));
    const entries = body.map((r, i) => {
      const date = isoOf(r[col("date")] ?? "");
      if (!date) return null;
      const totals: NutrientMap = {};
      nut.forEach((n, j) => { const v = n && num(r[j]); if (n && v) totals[n.id] = (totals[n.id] ?? 0) + v * n.factor; });
      const meal = r[col("meal")] ?? "";
      return entryFrom(date, mealOf(meal), `${meal || "Meal"} (MyFitnessPal)`, "", totals, i);
    }).filter((e): e is DiaryEntry => e != null);
    return finish({ source: "mfp-nutrition", label: "MyFitnessPal nutrition summary", entries, weights: [], ignored });
  }
  // MyFitnessPal measurements: Date, Weight
  if (col("date") >= 0 && col("weight") >= 0) {
    const weights = body.map((r) => {
      const date = isoOf(r[col("date")] ?? "");
      const v = num(r[col("weight")]);
      return date && v ? weight(date, v, "mfp") : null;
    }).filter((w): w is WeightRecord => w != null);
    return finish({ source: "mfp-measurements", label: "MyFitnessPal weights", entries: [], weights, ignored: [], needsWeightUnit: true });
  }
  throw new Error("NutriMe doesn't recognise this file. Use a Cronometer servings or biometrics export, a MyFitnessPal nutrition or measurement export, or a NutriMe export.");
}

async function fromNutrimeZip(buf: ArrayBuffer): Promise<ImportPlan> {
  const files = await unzip(buf);
  const json = files["nutrime.json"];
  if (!json) {
    // Other ZIPs (MyFitnessPal sends one): import the first CSV we recognise.
    const plans = Object.values(files).flatMap((t) => { try { return [fromCsv(t)]; } catch { return []; } });
    if (!plans.length) throw new Error("No file in this ZIP can be imported.");
    return plans.reduce((a, b) => finish({ ...a, label: a.label + " + " + b.label, entries: [...a.entries, ...b.entries], weights: [...a.weights, ...b.weights], ignored: [...a.ignored, ...b.ignored], needsWeightUnit: a.needsWeightUnit || b.needsWeightUnit }));
  }
  const records = JSON.parse(json) as ImportPlan["records"];
  return finish({ source: "nutrime", label: "NutriMe export", entries: records?.diary ?? [], weights: records?.weights ?? [], records, ignored: [] });
}

export async function readImport(file: File): Promise<ImportPlan> {
  const buf = await file.arrayBuffer();
  const head = new Uint8Array(buf, 0, Math.min(4, buf.byteLength));
  if (head[0] === 0x50 && head[1] === 0x4b) return fromNutrimeZip(buf);
  return fromCsv(new TextDecoder().decode(buf));
}

/** Write the plan. Returns the number of entries and weigh-ins written. */
export function applyImport(plan: ImportPlan, opts: { skipExistingDays: boolean; weightsInLb?: boolean }): { entries: number; weights: number } {
  const skip = opts.skipExistingDays ? new Set(plan.existingDays) : new Set<string>();
  const entries = plan.entries.filter((e) => !skip.has(e.date));
  if (plan.records) {
    for (const c of Object.keys(plan.records) as CollectionName[]) {
      const list = (plan.records[c] ?? []) as Collections[typeof c][];
      const keep = c === "diary" ? (list as DiaryEntry[]).filter((e) => !skip.has(e.date)) : list;
      // Last writer wins: only records newer than ours replace them.
      const newer = keep.filter((r) => { const mine = store.get(c, r.id) as { updatedAt: number } | undefined; return !mine || mine.updatedAt < r.updatedAt; });
      if (newer.length) store.putMany(c, newer as never);
    }
    return { entries: entries.length, weights: plan.weights.length };
  }
  const weights = plan.weights.map((w) => (opts.weightsInLb ? { ...w, weightKg: Math.round((w.weightKg / LB_PER_KG) * 100) / 100 } : w));
  // Never replace a weigh-in the user logged in NutriMe on the same day.
  const ownDays = new Set(store.all("weights").filter((w) => !w.id.startsWith("import:")).map((w) => w.date));
  const newWeights = weights.filter((w) => !ownDays.has(w.date));
  if (entries.length) store.putMany("diary", entries);
  if (newWeights.length) store.putMany("weights", newWeights);
  return { entries: entries.length, weights: newWeights.length };
}
