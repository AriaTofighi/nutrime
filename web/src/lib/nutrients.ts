/** Nutrient ids are USDA FoodData Central nutrient ids; values are stored per 100 g. Mirrors the Android app. */
export const N = {
  ENERGY: 1008,
  PROTEIN: 1003,
  FAT: 1004,
  CARBS: 1005,
  FIBER: 1079,
  SUGARS: 2000,
  ADDED_SUGARS: 1235,
  WATER: 1051,
  CAFFEINE: 1057,
  ALCOHOL: 1018,
  SAT_FAT: 1258,
  MONO_FAT: 1292,
  POLY_FAT: 1293,
  TRANS_FAT: 1257,
  CHOLESTEROL: 1253,
  OMEGA3_ALA: 1404,
  EPA: 1278,
  DHA: 1272,
  OMEGA6_LA: 1316,
  VIT_A: 1106,
  VIT_C: 1162,
  VIT_D: 1114,
  VIT_E: 1109,
  VIT_K: 1185,
  THIAMIN: 1165,
  RIBOFLAVIN: 1166,
  NIACIN: 1167,
  PANTOTHENIC: 1170,
  B6: 1175,
  FOLATE: 1190,
  B12: 1178,
  CHOLINE: 1180,
  CALCIUM: 1087,
  COPPER: 1098,
  IRON: 1089,
  MAGNESIUM: 1090,
  MANGANESE: 1101,
  PHOSPHORUS: 1091,
  POTASSIUM: 1092,
  SELENIUM: 1103,
  SODIUM: 1093,
  ZINC: 1095,
} as const;

export type NutrientMap = Record<number, number>;

export type NutrientGroup = "General" | "Carbohydrates" | "Lipids" | "Protein" | "Vitamins" | "Minerals";
export const GROUPS: NutrientGroup[] = ["General", "Carbohydrates", "Lipids", "Protein", "Vitamins", "Minerals"];

export interface NutrientDef {
  id: number;
  name: string;
  unit: string;
  group: NutrientGroup;
  short?: string;
}

const d = (id: number, name: string, unit: string, group: NutrientGroup, short?: string): NutrientDef => ({ id, name, unit, group, short });

export const NUTRIENTS: NutrientDef[] = [
  d(N.ENERGY, "Energy", "kcal", "General", "Calories"),
  d(N.WATER, "Water", "g", "General"),
  d(N.ALCOHOL, "Alcohol", "g", "General"),
  d(N.CAFFEINE, "Caffeine", "mg", "General"),
  d(N.CARBS, "Carbohydrates", "g", "Carbohydrates", "Carbs"),
  d(N.FIBER, "Fiber", "g", "Carbohydrates"),
  d(N.SUGARS, "Sugars", "g", "Carbohydrates"),
  d(N.ADDED_SUGARS, "Added Sugars", "g", "Carbohydrates"),
  d(N.FAT, "Fat", "g", "Lipids"),
  d(N.SAT_FAT, "Saturated", "g", "Lipids", "Sat. Fat"),
  d(N.MONO_FAT, "Monounsaturated", "g", "Lipids"),
  d(N.POLY_FAT, "Polyunsaturated", "g", "Lipids"),
  d(N.OMEGA3_ALA, "Omega-3 (ALA)", "g", "Lipids"),
  d(N.EPA, "Omega-3 (EPA)", "g", "Lipids"),
  d(N.DHA, "Omega-3 (DHA)", "g", "Lipids"),
  d(N.OMEGA6_LA, "Omega-6 (LA)", "g", "Lipids"),
  d(N.TRANS_FAT, "Trans Fat", "g", "Lipids"),
  d(N.CHOLESTEROL, "Cholesterol", "mg", "Lipids"),
  d(N.PROTEIN, "Protein", "g", "Protein"),
  d(N.VIT_A, "Vitamin A", "µg", "Vitamins"),
  d(N.THIAMIN, "B1 (Thiamine)", "mg", "Vitamins"),
  d(N.RIBOFLAVIN, "B2 (Riboflavin)", "mg", "Vitamins"),
  d(N.NIACIN, "B3 (Niacin)", "mg", "Vitamins"),
  d(N.PANTOTHENIC, "B5 (Pantothenic Acid)", "mg", "Vitamins"),
  d(N.B6, "B6 (Pyridoxine)", "mg", "Vitamins"),
  d(N.B12, "B12 (Cobalamin)", "µg", "Vitamins"),
  d(N.FOLATE, "Folate", "µg", "Vitamins"),
  d(N.VIT_C, "Vitamin C", "mg", "Vitamins"),
  d(N.VIT_D, "Vitamin D", "µg", "Vitamins"),
  d(N.VIT_E, "Vitamin E", "mg", "Vitamins"),
  d(N.VIT_K, "Vitamin K", "µg", "Vitamins"),
  d(N.CHOLINE, "Choline", "mg", "Vitamins"),
  d(N.CALCIUM, "Calcium", "mg", "Minerals"),
  d(N.COPPER, "Copper", "mg", "Minerals"),
  d(N.IRON, "Iron", "mg", "Minerals"),
  d(N.MAGNESIUM, "Magnesium", "mg", "Minerals"),
  d(N.MANGANESE, "Manganese", "mg", "Minerals"),
  d(N.PHOSPHORUS, "Phosphorus", "mg", "Minerals"),
  d(N.POTASSIUM, "Potassium", "mg", "Minerals"),
  d(N.SELENIUM, "Selenium", "µg", "Minerals"),
  d(N.SODIUM, "Sodium", "mg", "Minerals"),
  d(N.ZINC, "Zinc", "mg", "Minerals"),
];

export const NUTRIENT_BY_ID: Record<number, NutrientDef> = Object.fromEntries(NUTRIENTS.map((n) => [n.id, n]));

/** Nutrients shown in the custom-food editor (as printed on a nutrition label). */
export const LABEL_NUTRIENTS: number[] = [
  N.ENERGY, N.FAT, N.SAT_FAT, N.TRANS_FAT, N.CHOLESTEROL, N.SODIUM, N.CARBS, N.FIBER,
  N.SUGARS, N.ADDED_SUGARS, N.PROTEIN, N.VIT_D, N.CALCIUM, N.IRON, N.POTASSIUM,
];

export function fmtNum(value: number): string {
  const abs = Math.abs(value);
  if (abs >= 100) return value.toFixed(0);
  if (abs >= 1) return value.toFixed(1);
  if (abs === 0) return "0";
  return value.toFixed(2);
}

export const fmtNutrient = (value: number, unit: string) => `${fmtNum(value)} ${unit}`;

export function scale(m: NutrientMap, factor: number): NutrientMap {
  const out: NutrientMap = {};
  for (const k in m) out[k] = m[k] * factor;
  return out;
}

export const forGrams = (per100: NutrientMap, grams: number) => scale(per100, grams / 100);

export function sum(maps: Iterable<NutrientMap>): NutrientMap {
  const out: NutrientMap = {};
  for (const m of maps) for (const k in m) out[k] = (out[k] ?? 0) + m[k];
  return out;
}

export function parseNutrients(json: string | undefined | null): NutrientMap {
  if (!json) return {};
  try {
    const raw = JSON.parse(json) as Record<string, number>;
    const out: NutrientMap = {};
    for (const k in raw) if (typeof raw[k] === "number") out[Number(k)] = raw[k];
    return out;
  } catch {
    return {};
  }
}

/** Serialized the way the Android app writes it (string keys, numeric values). */
export function encodeNutrients(m: NutrientMap): string {
  const out: Record<string, number> = {};
  for (const k in m) if (Number.isFinite(m[k])) out[k] = m[k];
  return JSON.stringify(out);
}
