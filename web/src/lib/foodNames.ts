/**
 * Food names for display, and the country form used for ranking. A copy of
 * shared/src/main/kotlin/com/nutrime/app/model/FoodNames.kt; change both together.
 * Names are cleaned only for display: stored diary entries and ids never change.
 */

/** Letters only upper case, at least two of them ("CRACKER BARREL", "APPLEBEE'S"). */
export function isShouted(s: string): boolean {
  const letters = s.replace(/[^\p{L}]/gu, "");
  return letters.length >= 2 && letters === letters.toUpperCase() && letters !== letters.toLowerCase();
}

const isLower = (s: string) => {
  const letters = s.replace(/[^\p{L}]/gu, "");
  return letters.length >= 2 && letters === letters.toLowerCase() && letters !== letters.toUpperCase();
};

/** "CRACKER BARREL" → "Cracker Barrel", "APPLEBEE'S" → "Applebee's", "CHICK-FIL-A" → "Chick-Fil-A". */
export function titleCase(s: string): string {
  return s.toLowerCase().replace(/(^|[\s\-/&(])(\p{L})/gu, (_, sep: string, ch: string) => sep + ch.toUpperCase());
}

/** "CHICKEN TENDERLOINS" or "chicken tenderloins" → "Chicken tenderloins". */
export function sentenceCase(s: string): string {
  const lower = s.toLowerCase();
  return lower.replace(/\p{L}/u, (ch) => ch.toUpperCase());
}

/** Remove parts repeated with " - " ("Greek Yogurt - Kirkland Signature - Kirkland Signature"), and a part equal to the brand. */
export function cleanFoodName(name: string, brand?: string | null): string {
  const b = brand?.trim().toLowerCase() || undefined;
  const parts = name.split(" - ").map((p) => p.trim()).filter(Boolean);
  if (parts.length < 2) return name.trim();
  const seen = new Set<string>();
  const kept = parts.filter((p) => {
    const k = p.toLowerCase();
    if (k === b || seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  return kept.length ? kept.join(" - ") : parts[0];
}

/**
 * The name to show: trimmed, single spaces, repeated parts and a leading brand removed (the brand is
 * shown on its own), all-caps or all-lowercase names in sentence case, and a SHOUTED leading brand
 * segment ("CRACKER BARREL, chicken tenderloin platter") in title case.
 */
export function displayName(name: string, brand?: string | null): string {
  let s = cleanFoodName(name.replace(/\s+/g, " ").trim(), brand);
  const b = brand?.replace(/\s+/g, " ").trim();
  if (b && s.length > b.length && s.toLowerCase().startsWith(b.toLowerCase())) {
    const rest = s.slice(b.length).replace(/^[\s,:\-–]+/, "");
    if (/^[\s,:\-–]/.test(s.slice(b.length)) && rest.replace(/[^\p{L}]/gu, "").length >= 3) s = rest;
  }
  if (isShouted(s) || isLower(s)) return sentenceCase(s);
  const comma = s.indexOf(",");
  if (comma > 0 && isShouted(s.slice(0, comma)) && !isShouted(s.slice(comma + 1))) return titleCase(s.slice(0, comma)) + s.slice(comma);
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * The name on a search result row: the brand first when the shown name does not already contain it
 * ("Kirkland Signature, Greek Yogurt"). A SHOUTED brand is shown in title case.
 */
export function nameWithBrand(name: string, brand?: string | null): string {
  const shown = displayName(name, brand);
  const b = brand?.replace(/\s+/g, " ").trim();
  if (!b || shown.toLowerCase().includes(b.toLowerCase())) return shown;
  return (isShouted(b) ? titleCase(b) : b) + ", " + shown;
}

/** Grams as a row shows them: at most one decimal ("170", "28.4"). */
export const gramsText = (g: number) => String(Math.round(g * 10) / 10);

/** The grey line under a result row's name: the default serving and its weight ("1 container - 170 g"), or "100 g". */
export function servingLine(servings: { label: string; grams: number }[]): string {
  const s = servings[0];
  if (!s) return "100 g";
  const weight = `${gramsText(s.grams)} g`;
  const label = s.label.trim();
  if (label === "g" || label.toLowerCase() === weight.toLowerCase()) return weight;
  return (/^\d/.test(label) ? label : `1 ${label}`) + " - " + weight;
}

/** An ISO country code ("CA") as the country name Open Food Facts uses in its tags ("canada"). */
export function countryName(code?: string | null): string | undefined {
  const c = code?.trim().toUpperCase();
  if (!c || !/^[A-Z]{2}$/.test(c)) return undefined;
  try {
    const name = new Intl.DisplayNames(["en"], { type: "region" }).of(c);
    if (!name || name.toUpperCase() === c) return undefined;
    return name.toLowerCase().replace(/[^a-z]+/g, "-").replace(/^-+|-+$/g, "") || undefined;
  } catch {
    return undefined;
  }
}
