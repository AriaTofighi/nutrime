/** Where nutrients come from, and eating patterns. A copy of shared/.../model/Insights.kt. */
import { N, type NutrientMap } from "./nutrients";
import { dayDiff } from "./range";
import { weekdayIndex } from "./profile";

export interface LoggedItem {
  date: string;
  meal: number;
  foodId: string;
  name: string;
  nutrients: NutrientMap;
}

export interface Source {
  key: string;
  name: string;
  amount: number;
  share: number;
  times: number;
}

export function topSources(items: LoggedItem[], nutrientId: number, limit = 8): Source[] {
  const groups = new Map<string, LoggedItem[]>();
  for (const it of items) {
    const k = it.foodId.startsWith("quick:") || it.foodId.startsWith("import:") ? "name:" + it.name.toLowerCase().trim() : it.foodId;
    (groups.get(k) ?? groups.set(k, []).get(k)!).push(it);
  }
  const sums = [...groups].map(([k, list]) => ({ k, list, amount: list.reduce((s, i) => s + (i.nutrients[nutrientId] ?? 0), 0) })).filter((x) => x.amount > 0);
  const total = sums.reduce((s, x) => s + x.amount, 0);
  if (total <= 0) return [];
  return sums
    .sort((a, b) => b.amount - a.amount)
    .slice(0, limit)
    .map(({ k, list, amount }) => ({ key: k, name: list.reduce((a, b) => (b.date > a.date ? b : a)).name, amount, share: amount / total, times: list.length }));
}

export interface Patterns {
  weekdayKcal: number | null;
  weekendKcal: number | null;
  loggedDays: number;
  totalDays: number;
  currentRun: number;
  mealShares: number[];
  /** 0 = Monday. */
  steadiestDay: number | null;
  mostVariableDay: number | null;
}

const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length;

/** dayKcal: calories of each counted day in the range. */
export function patterns(items: LoggedItem[], dayKcal: Map<string, number>, from: string, to: string): Patterns {
  const wd: number[] = [];
  const we: number[] = [];
  for (const [d, k] of dayKcal) (weekdayIndex(d) <= 4 ? wd : we).push(k);
  let run = 0;
  const days = [...dayKcal.keys()].sort();
  if (days.length) {
    let d = days[days.length - 1];
    const set = new Set(days);
    while (set.has(d)) {
      run++;
      const x = new Date(d + "T12:00:00");
      x.setDate(x.getDate() - 1);
      d = `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, "0")}-${String(x.getDate()).padStart(2, "0")}`;
    }
  }
  const meal = [0, 0, 0, 0];
  for (const i of items) if (dayKcal.has(i.date) && i.meal >= 0 && i.meal < 4) meal[i.meal] += i.nutrients[N.ENERGY] ?? 0;
  const tot = meal.reduce((a, b) => a + b, 0);
  const total = dayDiff(from, to) + 1;
  let steady: number | null = null;
  let variable: number | null = null;
  if (total >= 28) {
    const by = new Map<number, number[]>();
    for (const [d, k] of dayKcal) (by.get(weekdayIndex(d)) ?? by.set(weekdayIndex(d), []).get(weekdayIndex(d))!).push(k);
    const spread = [...by].filter(([, l]) => l.length >= 3).map(([w, l]) => {
      const m = mean(l);
      return { w, s: Math.sqrt(l.reduce((a, v) => a + (v - m) ** 2, 0) / l.length) };
    });
    if (spread.length >= 2) {
      spread.sort((a, b) => a.s - b.s);
      steady = spread[0].w;
      variable = spread[spread.length - 1].w;
    }
  }
  return {
    weekdayKcal: wd.length ? mean(wd) : null,
    weekendKcal: we.length ? mean(we) : null,
    loggedDays: dayKcal.size,
    totalDays: total,
    currentRun: run,
    mealShares: meal.map((m) => (tot > 0 ? m / tot : 0)),
    steadiestDay: steady,
    mostVariableDay: variable,
  };
}

export const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
