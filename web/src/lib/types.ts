/**
 * Synced record shapes. Field names and types match the Android app's Room entities exactly
 * (DiaryEntryEntity, FoodEntity, WeightEntity, ProfileEntity) so phone and web share one account.
 */

export interface DiaryEntry {
  id: string;
  /** ISO date yyyy-MM-dd. */
  date: string;
  /** 0 breakfast, 1 lunch, 2 dinner, 3 snacks. */
  meal: number;
  foodId: string;
  foodName: string;
  brand?: string;
  servingLabel: string;
  servingGrams: number;
  quantity: number;
  /** Per-100 g nutrient snapshot at logging time (JSON map). */
  nutrientsJson: string;
  createdAt: number;
  updatedAt: number;
  deleted: boolean;
}

export type FoodKind = "custom" | "recipe" | "usda_branded" | "usda" | "off";

export interface FoodRecord {
  id: string;
  kind: FoodKind;
  name: string;
  brand?: string;
  barcode?: string;
  /** Per 100 g (JSON map). */
  nutrientsJson: string;
  /** JSON list of {label, grams}. */
  servingsJson: string;
  /** Recipes only: JSON list of RecipeIngredient. */
  ingredientsJson?: string;
  recipeServings?: number;
  updatedAt: number;
  deleted: boolean;
}

export interface WeightRecord {
  id: string;
  date: string;
  weightKg: number;
  bodyFatPct?: number;
  note?: string;
  updatedAt: number;
  deleted: boolean;
}

export interface ProfileRecord {
  id: "profile";
  /** JSON-encoded Profile. */
  json: string;
  updatedAt: number;
  deleted: boolean;
}

/** One record per calendar date (id = the ISO date). */
export interface DayRecord {
  id: string;
  /** The user says the food log for this day is complete. */
  complete: boolean;
  note?: string;
  steps?: number;
  activeKcal?: number;
  updatedAt: number;
  deleted: boolean;
}

/** A named list of foods, logged as separate diary entries. */
export interface SavedMealRecord {
  id: string;
  name: string;
  /** JSON list of RecipeIngredient. */
  itemsJson: string;
  defaultMeal?: number;
  updatedAt: number;
  deleted: boolean;
}

export interface RecipeIngredient {
  foodId: string;
  name: string;
  servingLabel: string;
  servingGrams: number;
  quantity: number;
  /** Per-100 g snapshot. */
  nutrients: Record<string, number>;
}

export interface Collections {
  diary: DiaryEntry;
  foods: FoodRecord;
  weights: WeightRecord;
  profile: ProfileRecord;
  days: DayRecord;
  meals: SavedMealRecord;
}
export type CollectionName = keyof Collections;
export const COLLECTION_NAMES: CollectionName[] = ["diary", "foods", "weights", "profile", "days", "meals"];

export interface Serving {
  label: string;
  grams: number;
}

export const MEALS = ["Breakfast", "Lunch", "Dinner", "Snacks"];
