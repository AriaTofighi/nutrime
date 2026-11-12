/**
 * Test vectors for the learned-burn filter, shared by the Kotlin and TypeScript code.
 *
 *   npx tsx scripts/burn_vectors.ts --write   # regenerate shared/testdata/burn_vectors.json from burn.ts
 *   npx tsx scripts/burn_vectors.ts           # check burn.ts against the file
 *
 * The Kotlin test (server/src/test/.../SharedLogicTest.kt) checks AdaptiveBurn.kt against the same file.
 * Profiles store an age, not a birth year, so the vectors don't change from year to year.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { estimate, type DayLog, type WeighIn } from "../src/lib/burn";
import { DEFAULT_PROFILE, type Profile } from "../src/lib/profile";
import { addDays } from "../src/lib/util";

const FILE = join(dirname(fileURLToPath(import.meta.url)), "../../shared/testdata/burn_vectors.json");

interface Vector {
  name: string;
  age: number;
  profile: Partial<Profile>;
  today: string;
  logs: DayLog[];
  weights: WeighIn[];
  /** The simulated person's true burn at the end, and how close the estimate must be. */
  truth?: { burnKcal: number; tolerance: number };
  expect: { burnKcal: number; burnSd: number; confidence: string; trendKg: number; ratePerWeekKg: number; knownDays: number; weighIns: number; spanDays: number } | null;
}

/** Deterministic random numbers (mulberry32). */
function rng(seed: number) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const gauss = (r: () => number) => Math.sqrt(-2 * Math.log(r() || 1e-9)) * Math.cos(2 * Math.PI * r());

/** A synthetic person: true burn, intake, scale noise, missed logs and weigh-ins. */
function person(o: { seed: number; days: number; burn: (d: number) => number; intake: number; weighShare: number; logShare: number; bias?: number; outlier?: number }) {
  const r = rng(o.seed);
  const today = "2026-06-30";
  const start = addDays(today, -(o.days - 1));
  let w = 80;
  const logs: DayLog[] = [];
  const weights: WeighIn[] = [];
  for (let d = 0; d < o.days; d++) {
    const date = addDays(start, d);
    if (r() < o.weighShare || d === 0) weights.push({ date, kg: +(w + 0.5 * gauss(r) + (o.outlier === d ? 3 : 0)).toFixed(2) });
    const eaten = o.intake + 150 * gauss(r);
    if (r() < o.logShare) logs.push({ date, kcal: Math.round(eaten * (1 + (o.bias ?? 0))), carbsG: Math.round(250 + 20 * gauss(r)) });
    w += (eaten - o.burn(d)) / 7700;
  }
  return { today, logs, weights };
}

const base: Partial<Profile> = { sex: "MALE", heightCm: 180, weightKg: 80, activity: "LIGHT" };
const cases: Omit<Vector, "expect">[] = [
  { name: "no weigh-ins", age: 35, profile: base, today: "2026-06-30", logs: [{ date: "2026-06-29", kcal: 2200 }], weights: [] },
  { name: "daily weigh-ins, complete logs, formula 300 kcal high", age: 35, profile: base, truth: { burnKcal: 2300, tolerance: 100 }, ...person({ seed: 1, days: 42, burn: () => 2300, intake: 1900, weighShare: 1, logShare: 1 }) },
  { name: "3 weigh-ins a week, 80% logged", age: 35, profile: base, truth: { burnKcal: 2600, tolerance: 150 }, ...person({ seed: 2, days: 42, burn: () => 2600, intake: 2200, weighShare: 0.43, logShare: 0.8 }) },
  // Logging 10% low: the learned burn is low by the same 10% of intake (2,500 − 210), so the target stays right.
  { name: "logging 10% low", age: 35, profile: base, truth: { burnKcal: 2290, tolerance: 100 }, ...person({ seed: 3, days: 42, burn: () => 2500, intake: 2100, weighShare: 1, logShare: 1, bias: -0.1 }) },
  { name: "burn drops by 200 on day 30", age: 35, profile: base, truth: { burnKcal: 2400, tolerance: 100 }, ...person({ seed: 4, days: 60, burn: (d) => (d < 30 ? 2600 : 2400), intake: 2300, weighShare: 1, logShare: 1 }) },
  { name: "one weigh-in 3 kg off", age: 35, profile: base, truth: { burnKcal: 2500, tolerance: 60 }, ...person({ seed: 5, days: 42, burn: () => 2500, intake: 2200, weighShare: 1, logShare: 1, outlier: 30 }) },
  { name: "short history (learning)", age: 50, profile: { ...base, sex: "FEMALE", heightCm: 165, weightKg: 70 }, ...person({ seed: 6, days: 8, burn: () => 2000, intake: 1800, weighShare: 1, logShare: 1 }) },
];

function run(c: Omit<Vector, "expect">): Vector["expect"] {
  const year = new Date().getFullYear();
  const profile = { ...DEFAULT_PROFILE, ...c.profile, birthYear: year - c.age } as Profile;
  const e = estimate(profile, c.logs, c.weights, c.today);
  if (!e) return null;
  return {
    burnKcal: e.burnKcal, burnSd: e.burnSd, confidence: e.confidence, trendKg: +e.trendKg.toFixed(4),
    ratePerWeekKg: +e.ratePerWeekKg.toFixed(4), knownDays: e.knownDays, weighIns: e.weighIns, spanDays: e.spanDays,
  };
}

if (process.argv.includes("--write")) {
  const vectors: Vector[] = cases.map((c) => ({ ...c, expect: run(c) }));
  writeFileSync(FILE, JSON.stringify({ about: "Generated by web/scripts/burn_vectors.ts. Inputs and expected outputs of the learned-burn filter.", vectors }, null, 1) + "\n");
  console.log(`wrote ${vectors.length} vectors to ${FILE}`);
} else {
  const { vectors } = JSON.parse(readFileSync(FILE, "utf-8")) as { vectors: Vector[] };
  let bad = 0;
  for (const v of vectors) {
    const got = run(v);
    const ok = JSON.stringify(got) === JSON.stringify(v.expect) ||
      (got && v.expect && Math.abs(got.burnKcal - v.expect.burnKcal) <= 1 && Math.abs(got.trendKg - v.expect.trendKg) < 1e-3 && got.confidence === v.expect.confidence);
    if (!ok) { bad++; console.log(`FAIL ${v.name}: expected ${JSON.stringify(v.expect)}, got ${JSON.stringify(got)}`); }
    if (v.truth && got && Math.abs(got.burnKcal - v.truth.burnKcal) > v.truth.tolerance) { bad++; console.log(`FAIL ${v.name}: ${got.burnKcal} is more than ${v.truth.tolerance} kcal from ${v.truth.burnKcal}`); }
  }
  console.log(bad ? `${bad} of ${vectors.length} vectors differ` : `${vectors.length} vectors OK`);
  process.exit(bad ? 1 : 0);
}
