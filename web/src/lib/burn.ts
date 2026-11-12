/**
 * Learns the user's true daily burn from logged food and weigh-ins. A copy of
 * shared/src/main/kotlin/com/nutrime/app/model/AdaptiveBurn.kt — keep the two the same
 * (shared/testdata/burn_vectors.json, checked by scripts/check_burn.ts).
 */
import { bmr, calorieTargetOn, formulaTdee, tdee, type Profile } from "./profile";
import { addDays } from "./util";

export interface DayLog {
  date: string;
  kcal: number;
  carbsG?: number;
  /** The user marked the day's log complete (this includes a day with no food). */
  complete?: boolean;
  hasEntries?: boolean;
}

export interface WeighIn {
  date: string;
  kg: number;
}

export interface BurnDay {
  date: string;
  trendKg: number;
  trendSd: number;
  burnKcal: number;
  burnSd: number;
  intakeKcal: number | null;
  scaleKg: number | null;
}

export type Confidence = "LEARNING" | "MEDIUM" | "HIGH";

export interface BurnEstimate {
  days: BurnDay[];
  burnKcal: number;
  burnSd: number;
  confidence: Confidence;
  trendKg: number;
  ratePerWeekKg: number;
  knownDays: number;
  weighIns: number;
  spanDays: number;
  formulaKcal: number;
}

export const KCAL_PER_KG = 7700;
const SIGMA_E0 = 300;
const Q_E = 12;
const Q_W = 0.03;
const SIGMA_I = 150;
const SIGMA_I_MISSING = 500;
const MAX_DAYS = 365;
export const MIN_SPAN = 14;
export const MIN_WEIGH_INS = 6;
export const MIN_KNOWN = 10;
export const MAX_STEP = 150;
export const CHECK_IN_DAYS = 7;
export const MIN_CHANGE = 40;

const dayDiff = (a: string, b: string) => Math.round((Date.UTC(+b.slice(0, 4), +b.slice(5, 7) - 1, +b.slice(8, 10)) - Date.UTC(+a.slice(0, 4), +a.slice(5, 7) - 1, +a.slice(8, 10))) / 86400000);

export function isKnown(log: DayLog | undefined, target: number, today: string): boolean {
  if (!log) return false;
  if (log.complete) return true;
  const hasEntries = log.hasEntries ?? log.kcal > 0;
  return log.date < today && hasEntries && log.kcal >= 0.5 * target;
}

export const measurementSd = (kg: number) => Math.max(0.4, 0.008 * kg);

export function slopePerWeek(values: number[]): number {
  const n = values.length;
  if (n < 2) return 0;
  const mx = (n - 1) / 2;
  const my = values.reduce((a, b) => a + b, 0) / n;
  let num = 0;
  let den = 0;
  values.forEach((v, i) => {
    num += (i - mx) * (v - my);
    den += (i - mx) * (i - mx);
  });
  return den === 0 ? 0 : (num / den) * 7;
}

export function missing(e: BurnEstimate): string[] {
  const out: string[] = [];
  if (e.spanDays < MIN_SPAN) out.push(`${MIN_SPAN - e.spanDays} more days`);
  if (e.weighIns < MIN_WEIGH_INS) out.push(`${MIN_WEIGH_INS - e.weighIns} more weigh-ins`);
  if (e.knownDays < MIN_KNOWN) out.push(`${MIN_KNOWN - e.knownDays} more fully logged days`);
  return out;
}

export function estimate(profile: Profile, logs: DayLog[], weights: WeighIn[], today: string): BurnEstimate | null {
  const windowStart = addDays(today, -(MAX_DAYS - 1));
  const scale = new Map<string, number>();
  for (const w of weights) if (w.date >= windowStart && w.date <= today && w.kg > 0) scale.set(w.date, w.kg);
  if (!scale.size) return null;
  const start = [...scale.keys()].sort()[0];
  const logByDate = new Map(logs.map((l) => [l.date, l]));
  const n = dayDiff(start, today) + 1;
  const dates = Array.from({ length: n }, (_, i) => addDays(start, i));

  const bmrAt = (w: number) => bmr({ ...profile, weightKg: w });
  const w0 = scale.get(start)!;
  const formula0 = formulaTdee({ ...profile, weightKg: w0 });

  const known = new Array<boolean>(n).fill(false);
  const intake = new Array<number>(n).fill(0);
  const carbs = new Array<number>(n).fill(NaN);
  for (let i = 0; i < n; i++) {
    const log = logByDate.get(dates[i]);
    if (isKnown(log, calorieTargetOn(profile, dates[i]), today)) {
      known[i] = true;
      intake[i] = log!.kcal;
      carbs[i] = log!.carbsG ?? 0;
    }
  }
  const rBoost = new Array<number>(n + 4).fill(1);
  for (let i = 0; i < n; i++) {
    if (!known[i]) continue;
    let sum = 0;
    let cnt = 0;
    for (let j = Math.max(0, i - 7); j < i; j++) if (known[j]) { sum += carbs[j]; cnt++; }
    if (cnt >= 3 && Math.abs(carbs[i] - sum / cnt) > 100) for (let k = 1; k <= 3; k++) rBoost[i + k] = 4;
  }

  const xf: number[][] = [];
  const pf: number[][] = [];
  const xp: number[][] = [];
  const pp: number[][] = [];
  const fB = new Array<number>(n).fill(0);
  const used: (number | null)[] = new Array(n).fill(null);

  let x = [w0, formula0];
  let p = [0, 0, 0, SIGMA_E0 * SIGMA_E0];
  let weighIns = 0;
  for (let i = 0; i < n; i++) {
    if (i === 0) {
      const r = measurementSd(w0);
      p[0] = r * r;
    }
    xp[i] = [...x];
    pp[i] = [...p];
    const z = scale.get(dates[i]);
    if (z !== undefined) {
      weighIns++;
      if (i > 0) {
        const sd = measurementSd(x[0]);
        let r = sd * sd * rBoost[i];
        const v = z - x[0];
        let s = p[0] + r;
        if (v * v > 9 * s) { r *= (v * v) / (9 * s); s = p[0] + r; }
        const k0 = p[0] / s;
        const k1 = p[2] / s;
        x = [x[0] + k0 * v, x[1] + k1 * v];
        p = [p[0] - k0 * p[0], p[1] - k0 * p[1], p[2] - k1 * p[0], p[3] - k1 * p[1]];
      }
    }
    const b0 = bmrAt(x[0]);
    x[1] = Math.min(Math.max(x[1], 0.9 * b0), 2.5 * b0);
    xf[i] = [...x];
    pf[i] = [...p];
    if (i === n - 1) break;

    let sigmaI: number;
    let b: number;
    let iUse: number | null;
    if (known[i]) {
      iUse = intake[i]; sigmaI = SIGMA_I; b = -1 / KCAL_PER_KG;
    } else {
      let sum = 0;
      let cnt = 0;
      for (let j = Math.max(0, i - 14); j < i; j++) if (known[j]) { sum += intake[j]; cnt++; }
      sigmaI = SIGMA_I_MISSING;
      if (cnt > 0) { iUse = sum / cnt; b = -1 / KCAL_PER_KG; } else { iUse = null; b = 0; }
    }
    used[i] = known[i] ? intake[i] : null;
    fB[i] = b;
    const w = iUse !== null ? x[0] + (iUse - x[1]) / KCAL_PER_KG : x[0];
    const p00 = p[0] + b * (p[2] + p[1]) + b * b * p[3];
    const p01 = p[1] + b * p[3];
    const p10 = p[2] + b * p[3];
    const p11 = p[3];
    const qW = (sigmaI / KCAL_PER_KG) ** 2 + Q_W * Q_W;
    x = [w, x[1]];
    p = [p00 + qW, p01, p10, p11 + Q_E * Q_E];
  }

  const xs = xf.map((v) => [...v]);
  const ps = pf.map((v) => [...v]);
  for (let i = n - 2; i >= 0; i--) {
    const b = fB[i];
    const pi = pf[i];
    const a00 = pi[0] + pi[1] * b;
    const a01 = pi[1];
    const a10 = pi[2] + pi[3] * b;
    const a11 = pi[3];
    const q = pp[i + 1];
    const det = q[0] * q[3] - q[1] * q[2];
    if (Math.abs(det) < 1e-12) continue;
    const i00 = q[3] / det, i01 = -q[1] / det, i10 = -q[2] / det, i11 = q[0] / det;
    const c00 = a00 * i00 + a01 * i10;
    const c01 = a00 * i01 + a01 * i11;
    const c10 = a10 * i00 + a11 * i10;
    const c11 = a10 * i01 + a11 * i11;
    const dx0 = xs[i + 1][0] - xp[i + 1][0];
    const dx1 = xs[i + 1][1] - xp[i + 1][1];
    xs[i] = [xf[i][0] + c00 * dx0 + c01 * dx1, xf[i][1] + c10 * dx0 + c11 * dx1];
    const d00 = ps[i + 1][0] - q[0], d01 = ps[i + 1][1] - q[1];
    const d10 = ps[i + 1][2] - q[2], d11 = ps[i + 1][3] - q[3];
    const e00 = c00 * d00 + c01 * d10, e01 = c00 * d01 + c01 * d11;
    const e10 = c10 * d00 + c11 * d10, e11 = c10 * d01 + c11 * d11;
    ps[i] = [
      pi[0] + e00 * c00 + e01 * c01, pi[1] + e00 * c10 + e01 * c11,
      pi[2] + e10 * c00 + e11 * c01, pi[3] + e10 * c10 + e11 * c11,
    ];
  }

  const days: BurnDay[] = dates.map((date, i) => ({
    date,
    trendKg: xs[i][0],
    trendSd: Math.sqrt(Math.max(ps[i][0], 0)),
    burnKcal: xs[i][1],
    burnSd: Math.sqrt(Math.max(ps[i][3], 0)),
    intakeKcal: used[i],
    scaleKg: scale.get(date) ?? null,
  }));
  const last = days[days.length - 1];
  const knownDays = known.filter(Boolean).length;
  const confidence: Confidence =
    n < MIN_SPAN || weighIns < MIN_WEIGH_INS || knownDays < MIN_KNOWN || last.burnSd > 200 ? "LEARNING" : last.burnSd < 120 ? "HIGH" : "MEDIUM";
  return {
    days,
    burnKcal: Math.round(last.burnKcal),
    burnSd: Math.round(last.burnSd),
    confidence,
    trendKg: last.trendKg,
    ratePerWeekKg: slopePerWeek(days.slice(-14).map((d) => d.trendKg)),
    knownDays,
    weighIns,
    spanDays: n,
    formulaKcal: Math.round(formulaTdee(profile)),
  };
}

/** Weekly check-in: the new accepted burn, limited to a 150 kcal step. */
export function nextAccepted(current: number, learned: number): { value: number; limited: boolean } {
  const step = Math.min(Math.max(learned - current, -MAX_STEP), MAX_STEP);
  return { value: Math.round(current + step), limited: Math.abs(learned - current) > MAX_STEP };
}

export function checkInDue(p: Profile, est: BurnEstimate | null, today: string): boolean {
  if (!est || est.confidence === "LEARNING") return false;
  if (p.burnModeChosen && p.burnMode !== "ADAPTIVE") return false;
  if (p.adaptiveBurnDate && dayDiff(p.adaptiveBurnDate, today) < CHECK_IN_DAYS) return false;
  return Math.abs(est.burnKcal - tdee(p)) >= MIN_CHANGE;
}
