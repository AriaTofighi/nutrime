import { N } from "./nutrients";

export type Sex = "MALE" | "FEMALE";
export type Activity = "SEDENTARY" | "LIGHT" | "MODERATE" | "VERY" | "EXTRA";

export const ACTIVITIES: { id: Activity; label: string; description: string; factor: number }[] = [
  { id: "SEDENTARY", label: "Sedentary", description: "Little or no exercise", factor: 1.2 },
  { id: "LIGHT", label: "Lightly active", description: "Exercise 1–3 days/week", factor: 1.375 },
  { id: "MODERATE", label: "Moderately active", description: "Exercise 3–5 days/week", factor: 1.55 },
  { id: "VERY", label: "Very active", description: "Hard exercise 6–7 days/week", factor: 1.725 },
  { id: "EXTRA", label: "Extra active", description: "Physical job or 2x/day training", factor: 1.9 },
];

export type BurnMode = "FORMULA" | "ADAPTIVE" | "MANUAL";
export type ProteinMode = "PERCENT" | "PER_KG";

/** Same shape (and JSON field names) as the Android app's Profile. */
export interface Profile {
  sex: Sex;
  birthYear: number;
  heightCm: number;
  weightKg: number;
  activity: Activity;
  goalKgPerWeek: number;
  proteinPct: number;
  carbsPct: number;
  fatPct: number;
  calorieOverride?: number | null;
  targetOverrides: Record<string, number>;
  imperial: boolean;
  configured: boolean;
  burnMode: BurnMode;
  /** The learned daily burn the user last accepted. */
  adaptiveBurnKcal?: number | null;
  /** ISO date of the last weekly check-in. */
  adaptiveBurnDate?: string | null;
  burnModeChosen: boolean;
  manualBurnKcal?: number | null;
  goalWeightKg?: number | null;
  proteinMode: ProteinMode;
  proteinGPerKg: number;
  /** kcal added to the target on each weekday, Monday first; adds up to 0. */
  calorieSchedule?: number[] | null;
  autoAcceptBurn: boolean;
  favoriteFoodIds: string[];
  [extra: string]: unknown;
}

export const DEFAULT_PROFILE: Profile = {
  sex: "MALE",
  birthYear: 1990,
  heightCm: 175,
  weightKg: 75,
  activity: "LIGHT",
  goalKgPerWeek: 0,
  proteinPct: 25,
  carbsPct: 45,
  fatPct: 30,
  targetOverrides: {},
  imperial: false,
  configured: false,
  burnMode: "FORMULA",
  burnModeChosen: false,
  proteinMode: "PERCENT",
  proteinGPerKg: 1.8,
  autoAcceptBurn: false,
  favoriteFoodIds: [],
};

export const LB_PER_KG = 2.20462;
export const CM_PER_IN = 2.54;

export function parseProfile(json: string | undefined): Profile {
  if (!json) return { ...DEFAULT_PROFILE };
  try {
    const p = JSON.parse(json);
    return { ...DEFAULT_PROFILE, ...p, targetOverrides: p.targetOverrides ?? {}, favoriteFoodIds: p.favoriteFoodIds ?? [] };
  } catch {
    return { ...DEFAULT_PROFILE };
  }
}

export function encodeProfile(p: Profile): string {
  const out: Record<string, unknown> = { ...p };
  // Like the app (explicitNulls = false): leave out empty optional fields.
  for (const k of ["calorieOverride", "adaptiveBurnKcal", "adaptiveBurnDate", "manualBurnKcal", "goalWeightKg", "calorieSchedule"]) {
    if (out[k] == null) delete out[k];
  }
  return JSON.stringify(out);
}

export const age = (p: Profile) => Math.min(120, Math.max(1, new Date().getFullYear() - p.birthYear));
export const activityFactor = (p: Profile) => ACTIVITIES.find((a) => a.id === p.activity)?.factor ?? 1.375;

/** Mifflin-St Jeor resting energy expenditure. */
export const bmr = (p: Profile) => 10 * p.weightKg + 6.25 * p.heightCm - 5 * age(p) + (p.sex === "MALE" ? 5 : -161);
/** Daily burn from the formula: resting burn × activity factor. */
export const formulaTdee = (p: Profile) => bmr(p) * activityFactor(p);
/** The daily burn used for targets: learned, typed, or the formula. */
export const tdee = (p: Profile) =>
  p.burnMode === "ADAPTIVE" ? p.adaptiveBurnKcal ?? formulaTdee(p) : p.burnMode === "MANUAL" ? p.manualBurnKcal ?? formulaTdee(p) : formulaTdee(p);
/** ~7700 kcal per kg of body weight. */
export const KCAL_PER_KG = 7700;
export const MIN_CALORIES = 1200;
export const goalKcalPerDay = (p: Profile) => (p.goalKgPerWeek * KCAL_PER_KG) / 7;
export const computedCalories = (p: Profile) => Math.round(Math.max(MIN_CALORIES, tdee(p) + goalKcalPerDay(p)));
export const calorieTarget = (p: Profile) => p.calorieOverride ?? computedCalories(p);
export const raisedToFloor = (p: Profile) => p.calorieOverride == null && tdee(p) + goalKcalPerDay(p) < MIN_CALORIES;

/** Monday = 0 … Sunday = 6 for an ISO date. */
export const weekdayIndex = (iso: string) => (new Date(iso + "T12:00:00").getDay() + 6) % 7;

/** The calorie target on a date, with the weekday schedule applied. */
export function calorieTargetOn(p: Profile, iso?: string): number {
  const add = iso && p.calorieSchedule?.length === 7 ? p.calorieSchedule[weekdayIndex(iso)] : 0;
  return Math.max(800, calorieTarget(p) + add);
}

export interface MacroGrams {
  protein: number;
  carbs: number;
  fat: number;
}

/** Macro grams for kcal; in PER_KG mode carbohydrate takes the calories that protein and fat leave. */
export function macroGrams(p: Profile, kcal = calorieTarget(p)): MacroGrams {
  if (p.proteinMode === "PER_KG") {
    const protein = p.proteinGPerKg * p.weightKg;
    const fat = (kcal * p.fatPct) / 100 / 9;
    return { protein, carbs: Math.max(0, (kcal - protein * 4 - fat * 9) / 4), fat };
  }
  return { protein: (kcal * p.proteinPct) / 100 / 4, carbs: (kcal * p.carbsPct) / 100 / 4, fat: (kcal * p.fatPct) / 100 / 9 };
}

export interface Target {
  min?: number;
  max?: number;
}

/** Dietary Reference Intakes (RDA/AI) for adults, from the US National Academies. */
export function targets(p: Profile, iso?: string): Record<number, Target> {
  const male = p.sex === "MALE";
  const a = age(p);
  const kcal = calorieTargetOn(p, iso);
  const t: Record<number, Target> = {};
  const set = (id: number, min?: number, max?: number) => (t[id] = { min, max });

  set(N.ENERGY, kcal);
  const m = macroGrams(p, kcal);
  set(N.PROTEIN, m.protein);
  set(N.CARBS, m.carbs);
  set(N.FAT, m.fat);
  set(N.FIBER, (14 * kcal) / 1000);
  set(N.SAT_FAT, undefined, (kcal * 0.1) / 9);
  set(N.ADDED_SUGARS, undefined, (kcal * 0.1) / 4);
  set(N.TRANS_FAT, undefined, 2);
  set(N.CHOLESTEROL, undefined, 300);
  set(N.CAFFEINE, undefined, 400);
  set(N.WATER, male ? 3700 : 2700);
  set(N.OMEGA3_ALA, male ? 1.6 : 1.1);
  set(N.OMEGA6_LA, male && a <= 50 ? 17 : male ? 14 : a <= 50 ? 12 : 11);

  set(N.VIT_A, male ? 900 : 700, 3000);
  set(N.VIT_C, male ? 90 : 75, 2000);
  set(N.VIT_D, a > 70 ? 20 : 15, 100);
  set(N.VIT_E, 15, 1000);
  set(N.VIT_K, male ? 120 : 90);
  set(N.THIAMIN, male ? 1.2 : 1.1);
  set(N.RIBOFLAVIN, male ? 1.3 : 1.1);
  set(N.NIACIN, male ? 16 : 14, 35);
  set(N.PANTOTHENIC, 5);
  set(N.B6, a <= 50 ? 1.3 : male ? 1.7 : 1.5, 100);
  set(N.FOLATE, 400);
  set(N.B12, 2.4);
  set(N.CHOLINE, male ? 550 : 425, 3500);

  set(N.CALCIUM, (male && a > 70) || (!male && a > 50) ? 1200 : 1000, a > 50 ? 2000 : 2500);
  set(N.COPPER, 0.9, 10);
  set(N.IRON, !male && a <= 50 ? 18 : 8, 45);
  set(N.MAGNESIUM, male && a <= 30 ? 400 : male ? 420 : a <= 30 ? 310 : 320);
  set(N.MANGANESE, male ? 2.3 : 1.8, 11);
  set(N.PHOSPHORUS, 700, a > 70 ? 3000 : 4000);
  set(N.POTASSIUM, male ? 3400 : 2600);
  set(N.SELENIUM, 55, 400);
  set(N.SODIUM, 1500, 2300);
  set(N.ZINC, male ? 11 : 8, 40);

  for (const [id, v] of Object.entries(p.targetOverrides ?? {})) t[Number(id)] = { min: v, max: t[Number(id)]?.max };
  return t;
}
