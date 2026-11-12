/** Goal weight projections and warnings. A copy of shared/.../model/Goal.kt. */
import { goalKcalPerDay, raisedToFloor, tdee, type Profile } from "./profile";
import { addDays } from "./util";

export type GoalWarning = "FAST_RATE" | "LARGE_DEFICIT" | "WRONG_DIRECTION" | "AT_FLOOR";
export const MAX_LOSS = 1.0;
export const MAX_GAIN = 0.5;
export const REACHED_KG = 0.2;

export function weeksToGoal(trendKg: number, goalKg: number, kgPerWeek: number): number | null {
  const diff = goalKg - trendKg;
  if (Math.abs(diff) < REACHED_KG) return 0;
  if (Math.abs(kgPerWeek) < 0.01 || diff * kgPerWeek < 0) return null;
  return diff / kgPerWeek;
}

export function projectedDate(today: string, trendKg: number, goalKg: number, kgPerWeek: number): string | null {
  const w = weeksToGoal(trendKg, goalKg, kgPerWeek);
  return w == null ? null : addDays(today, Math.ceil(w * 7));
}

export const reached = (trendKg: number, goalKg?: number | null) => goalKg != null && Math.abs(goalKg - trendKg) < REACHED_KG;

export function warnings(p: Profile, trendKg = p.weightKg): GoalWarning[] {
  const out: GoalWarning[] = [];
  if (Math.abs(p.goalKgPerWeek) > 0.01 * trendKg) out.push("FAST_RATE");
  if (goalKcalPerDay(p) < 0 && -goalKcalPerDay(p) > 0.25 * tdee(p)) out.push("LARGE_DEFICIT");
  const g = p.goalWeightKg;
  if (g != null && Math.abs(g - trendKg) >= REACHED_KG && p.goalKgPerWeek !== 0 && (g - trendKg) * p.goalKgPerWeek < 0) out.push("WRONG_DIRECTION");
  if (raisedToFloor(p)) out.push("AT_FLOOR");
  return out;
}

export const WARNING_TEXT: Record<GoalWarning, string> = {
  FAST_RATE: "This rate is more than 1% of your body weight a week. A slower rate is easier to keep and keeps more muscle.",
  LARGE_DEFICIT: "This deficit is more than a quarter of your daily burn.",
  WRONG_DIRECTION: "Your goal weight is in the other direction from your weekly goal.",
  AT_FLOOR: "Raised to 1,200 kcal, the lowest suggested target.",
};

/** Change one weekday and spread the opposite amount over untouched days, so the week adds up to 0. */
export function editSchedule(schedule: number[] | null | undefined, day: number, value: number, touched: Set<number>): number[] {
  const s = schedule?.length === 7 ? [...schedule] : new Array(7).fill(0);
  s[day] = value;
  let free = [0, 1, 2, 3, 4, 5, 6].filter((i) => i !== day && !touched.has(i));
  if (!free.length) free = [0, 1, 2, 3, 4, 5, 6].filter((i) => i !== day);
  const rest = -s.filter((_, i) => !free.includes(i)).reduce((a, b) => a + b, 0);
  const each = Math.trunc(rest / free.length);
  let rem = rest - each * free.length;
  for (const i of free) {
    let extra = 0;
    if (rem !== 0) { extra = rem > 0 ? 1 : -1; rem -= extra; }
    s[i] = each + extra;
  }
  return s;
}

export function higherWeekends(extra: number): number[] {
  const weekday = Math.trunc(-(extra * 2) / 5);
  const s = new Array(7).fill(weekday);
  s[5] = extra;
  s[6] = extra;
  s[0] -= s.reduce((a: number, b: number) => a + b, 0);
  return s;
}
