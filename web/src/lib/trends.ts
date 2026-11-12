/** Data for Trends, the Diary check-in and export: day totals, the learned burn, logged items. Cached per store version. */
import { estimate, type BurnEstimate, type DayLog } from "./burn";
import { entryNutrients } from "./foods";
import type { LoggedItem } from "./insights";
import { N, sum, type NutrientMap } from "./nutrients";
import { parseProfile, type Profile } from "./profile";
import { store } from "./store";
import { isoDate } from "./util";

let cache: { version: number; totals: Map<string, NutrientMap>; items: LoggedItem[]; burn?: BurnEstimate | null; burnKey?: string } | null = null;

function load() {
  if (cache && cache.version === store.version) return cache;
  const byDay = new Map<string, NutrientMap[]>();
  const items: LoggedItem[] = [];
  for (const e of store.all("diary")) {
    const n = entryNutrients(e);
    (byDay.get(e.date) ?? byDay.set(e.date, []).get(e.date)!).push(n);
    items.push({ date: e.date, meal: e.meal, foodId: e.foodId, name: e.foodName, nutrients: n });
  }
  const totals = new Map<string, NutrientMap>();
  for (const [d, l] of byDay) totals.set(d, sum(l));
  cache = { version: store.version, totals, items };
  return cache;
}

/** Totals of every day with entries. */
export const dayTotals = () => load().totals;
export const loggedItems = (from: string, to: string) => load().items.filter((i) => i.date >= from && i.date <= to);
export const firstDate = () => [...load().totals.keys()].sort()[0] ?? null;
export const profileNow = (): Profile => parseProfile(store.get("profile", "profile")?.json);

/** The learned daily burn and trend weight (same filter as the app). */
export function burnEstimate(profile = profileNow()): BurnEstimate | null {
  const c = load();
  const key = JSON.stringify([profile.weightKg, profile.heightCm, profile.birthYear, profile.sex, profile.activity, profile.calorieOverride, profile.burnMode, profile.adaptiveBurnKcal, profile.manualBurnKcal, profile.goalKgPerWeek, profile.calorieSchedule]);
  if (c.burn !== undefined && c.burnKey === key) return c.burn;
  const days = new Map(store.all("days").map((d) => [d.id, d]));
  const dates = new Set([...c.totals.keys(), ...[...days.values()].filter((d) => d.complete).map((d) => d.id)]);
  const logs: DayLog[] = [...dates].map((d) => {
    const t = c.totals.get(d);
    return { date: d, kcal: t?.[N.ENERGY] ?? 0, carbsG: t?.[N.CARBS] ?? 0, complete: days.get(d)?.complete === true, hasEntries: !!t };
  });
  const byDate = new Map<string, { kg: number; t: number }>();
  for (const w of store.all("weights")) {
    const cur = byDate.get(w.date);
    if (!cur || w.updatedAt > cur.t) byDate.set(w.date, { kg: w.weightKg, t: w.updatedAt });
  }
  c.burn = estimate(profile, logs, [...byDate].map(([date, v]) => ({ date, kg: v.kg })), isoDate());
  c.burnKey = key;
  return c.burn;
}

export const conv = (p: Profile) => (kg: number) => (p.imperial ? kg * 2.20462 : kg);
export const unitOf = (p: Profile) => (p.imperial ? "lb" : "kg");

export function burnSource(p: Profile, e?: BurnEstimate | null): string {
  if (p.burnMode === "ADAPTIVE" && p.adaptiveBurnKcal != null) return `learned from your log${e ? ` · ${e.knownDays} days` : ""}`;
  if (p.burnMode === "MANUAL" && p.manualBurnKcal != null) return "your own value";
  return "from your body and activity";
}
