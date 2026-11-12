/** Day records (complete flag, note) and saved meals. Same records as the Android app. */
import { encodeNutrients, parseNutrients } from "./nutrients";
import { newId, store } from "./store";
import type { DayRecord, DiaryEntry, RecipeIngredient, SavedMealRecord } from "./types";

export const dayRecord = (date: string): DayRecord | undefined => store.get("days", date);

function editDay(date: string, patch: Partial<DayRecord>) {
  const cur = store.get("days", date) ?? { id: date, complete: false, updatedAt: 0, deleted: false };
  store.put("days", { ...cur, ...patch, id: date, deleted: false });
}

export const setComplete = (date: string, complete: boolean) => editDay(date, { complete });
export const setNote = (date: string, note: string | null) => editDay(date, { note: note?.trim() || undefined });

export interface SavedMeal {
  id: string;
  name: string;
  items: RecipeIngredient[];
  defaultMeal?: number;
  kcal: number;
}

export function savedMeals(): SavedMeal[] {
  return store
    .all("meals")
    .map((m) => {
      let items: RecipeIngredient[] = [];
      try {
        items = JSON.parse(m.itemsJson);
      } catch {
        /* skip */
      }
      const kcal = items.reduce((s, i) => s + ((i.nutrients["1008"] ?? 0) * i.servingGrams * i.quantity) / 100, 0);
      return { id: m.id, name: m.name, items, defaultMeal: m.defaultMeal, kcal };
    })
    .sort((a, b) => a.name.localeCompare(b.name));
}

export function saveMeal(name: string, entries: DiaryEntry[], defaultMeal?: number, id?: string, items?: RecipeIngredient[]): string {
  const list: RecipeIngredient[] =
    items ??
    entries.map((e) => ({
      foodId: e.foodId,
      name: e.foodName,
      servingLabel: e.servingLabel,
      servingGrams: e.servingGrams,
      quantity: e.quantity,
      nutrients: Object.fromEntries(Object.entries(parseNutrients(e.nutrientsJson)).map(([k, v]) => [k, v])),
    }));
  const rec: SavedMealRecord = { id: id ?? newId(), name: name.trim() || "Meal", itemsJson: JSON.stringify(list), defaultMeal, updatedAt: Date.now(), deleted: false };
  store.put("meals", rec);
  return rec.id;
}

/** Log a saved meal: one entry per item. Returns the new ids (for undo). */
export function logMeal(m: SavedMeal, date: string, meal: number): string[] {
  const t = Date.now();
  const entries: DiaryEntry[] = m.items.map((i, k) => ({
    id: newId(),
    date,
    meal,
    foodId: i.foodId,
    foodName: i.name,
    servingLabel: i.servingLabel,
    servingGrams: i.servingGrams,
    quantity: i.quantity,
    nutrientsJson: encodeNutrients(Object.fromEntries(Object.entries(i.nutrients).map(([k2, v]) => [Number(k2), v]))),
    createdAt: t + k,
    updatedAt: t + k,
    deleted: false,
  }));
  store.putMany("diary", entries);
  return entries.map((e) => e.id);
}

export const deleteMeal = (id: string) => store.remove("meals", id);
