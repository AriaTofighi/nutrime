/** Trends periods and chart buckets. A copy of shared/.../model/TrendRange.kt. */
import { addDays, parseIso, isoDate } from "./util";

export type TrendRange = { kind: "last"; days: number } | { kind: "all" } | { kind: "custom"; from: string; to: string };
export const MAX_CUSTOM_DAYS = 730;
export const PRESETS = [7, 30, 90, 180, 365];

export const dayDiff = (a: string, b: string) =>
  Math.round((Date.UTC(+b.slice(0, 4), +b.slice(5, 7) - 1, +b.slice(8, 10)) - Date.UTC(+a.slice(0, 4), +a.slice(5, 7) - 1, +a.slice(8, 10))) / 86400000);

export function encodeRange(r: TrendRange): string {
  return r.kind === "last" ? `${r.days}d` : r.kind === "all" ? "all" : `${r.from}..${r.to}`;
}

const ISO = /^\d{4}-\d{2}-\d{2}$/;

export function customRange(a: string, b: string): TrendRange | null {
  const [from, to] = a > b ? [b, a] : [a, b];
  const n = dayDiff(from, to) + 1;
  return n >= 1 && n <= MAX_CUSTOM_DAYS ? { kind: "custom", from, to } : null;
}

export function decodeRange(s: string | null | undefined): TrendRange | null {
  if (!s) return null;
  if (s === "all") return { kind: "all" };
  if (s.endsWith("d")) {
    const n = Number(s.slice(0, -1));
    return Number.isInteger(n) && n >= 1 && n <= 3650 ? { kind: "last", days: n } : null;
  }
  const [a, b] = s.split("..");
  return a && b && ISO.test(a) && ISO.test(b) ? customRange(a, b) : null;
}

export function bounds(r: TrendRange, today: string, firstData?: string | null): [string, string] {
  if (r.kind === "last") return [addDays(today, -(r.days - 1)), today];
  if (r.kind === "custom") return [r.from, r.to];
  let f = firstData && firstData < today ? firstData : today;
  const min = addDays(today, -MAX_CUSTOM_DAYS * 5);
  if (f < min) f = min;
  return [f, today];
}

export function rangeName(r: TrendRange): string {
  if (r.kind === "all") return "All time";
  if (r.kind === "custom") return `${shortDay(r.from)} – ${shortDay(r.to)}`;
  return ({ 7: "This week", 30: "This month", 90: "Last 3 months", 180: "Last 6 months", 365: "Last year" } as Record<number, string>)[r.days] ?? `Last ${r.days} days`;
}

const shortDay = (iso: string) => parseIso(iso).toLocaleDateString(undefined, { month: "short", day: "numeric", year: iso.slice(0, 4) === isoDate().slice(0, 4) ? undefined : "numeric" });

export type BucketSize = "DAY" | "WEEK" | "MONTH";
export interface Bucket {
  from: string;
  to: string;
  size: BucketSize;
}

export const sizeFor = (days: number): BucketSize => (days <= 31 ? "DAY" : days <= 182 ? "WEEK" : "MONTH");

export function buckets(from: string, to: string): Bucket[] {
  const size = sizeFor(dayDiff(from, to) + 1);
  const out: Bucket[] = [];
  let d = from;
  while (d <= to) {
    let end: string;
    if (size === "DAY") end = d;
    else if (size === "WEEK") end = addDays(d, (7 - parseIso(d).getDay()) % 7); // to Sunday
    else {
      const x = parseIso(d);
      end = isoDate(new Date(x.getFullYear(), x.getMonth() + 1, 0));
    }
    if (end > to) end = to;
    out.push({ from: d, to: end, size });
    d = addDays(end, 1);
  }
  return out;
}

/** Days from..to inclusive. */
export function eachDay(from: string, to: string): string[] {
  const out: string[] = [];
  for (let d = from; d <= to; d = addDays(d, 1)) out.push(d);
  return out;
}

export function bucketAverage<T>(bs: Bucket[], values: Map<string, T>, counted: Set<string>, mean: (xs: T[]) => T): (T | null)[] {
  return bs.map((b) => {
    const inside = eachDay(b.from, b.to).filter((d) => counted.has(d) && values.has(d)).map((d) => values.get(d)!);
    return inside.length ? mean(inside) : null;
  });
}

export function previous(from: string, to: string): [string, string] {
  const n = dayDiff(from, to) + 1;
  return [addDays(from, -n), addDays(from, -1)];
}

export function bucketLabel(b: Bucket, short = false): string {
  const d = parseIso(b.from);
  if (b.size === "MONTH") return d.toLocaleDateString(undefined, { month: short ? "narrow" : "short" });
  return d.toLocaleDateString(undefined, { month: "numeric", day: "numeric" });
}
