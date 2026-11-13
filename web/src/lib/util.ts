/** Local-time ISO date (yyyy-MM-dd), matching java.time.LocalDate.toString(). */
export function isoDate(d: Date = new Date()): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

export function parseIso(s: string): Date {
  const [y, m, d] = s.split("-").map(Number);
  return new Date(y, m - 1, d);
}

export function addDays(iso: string, n: number): string {
  const d = parseIso(iso);
  d.setDate(d.getDate() + n);
  return isoDate(d);
}

export function friendlyDate(iso: string): string {
  const today = isoDate();
  if (iso === today) return "Today";
  if (iso === addDays(today, -1)) return "Yesterday";
  if (iso === addDays(today, 1)) return "Tomorrow";
  return parseIso(iso).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" });
}

export const shortDate = (iso: string) => parseIso(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });

export function fmtQty(q: number): string {
  if (Number.isInteger(q)) return String(q);
  return String(Math.round(q * 100) / 100);
}

/**
 * A logged weight as the user typed it: up to 2 decimals, trailing zeros removed (185.4, 72.45, 80).
 * Rounding to 2 decimals first keeps a lb -> kg -> lb round trip equal to the typed value. Same as the app.
 */
export function fmtWeight(v: number): string {
  if (!Number.isFinite(v)) return "";
  return String(Math.round(v * 100) / 100);
}

/** A calculated weight (trend, goal, change): always one decimal ("72.0"). */
export const fmtWeight1 = (v: number): string => v.toFixed(1);

/** Meal the app suggests for the current time of day. */
export function defaultMealForNow(): number {
  const h = new Date().getHours();
  if (h < 11) return 0;
  if (h < 15) return 1;
  if (h >= 17 && h <= 21) return 2;
  return 3;
}

export function toNum(s: string): number | undefined {
  const v = parseFloat(s.replace(",", "."));
  return Number.isFinite(v) ? v : undefined;
}

export function timeAgo(t?: number): string {
  if (!t) return "never";
  const s = Math.round((Date.now() - t) / 1000);
  if (s < 10) return "just now";
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return new Date(t).toLocaleDateString();
}

/** "135 g", "2 oz", or "1.5 × large" for a logged amount. */
export function amountLabel(quantity: number, servingLabel: string): string {
  return servingLabel === "g" || servingLabel === "oz" || servingLabel === "ml" ? `${fmtQty(quantity)} ${servingLabel}` : `${fmtQty(quantity)} × ${servingLabel}`;
}
