// GENERATED from design/tokens.json by tools/gen_design_tokens.py. Do not edit by hand.

export type Accent = "emerald" | "indigo" | "rose" | "amber";

export const ACCENTS: { id: Accent; label: string; swatch: string }[] = [
  { id: "emerald", label: "Emerald", swatch: "hsl(158 64% 40%)" },
  { id: "indigo", label: "Indigo", swatch: "hsl(238 72% 58%)" },
  { id: "rose", label: "Rose", swatch: "hsl(346 74% 52%)" },
  { id: "amber", label: "Amber", swatch: "hsl(32 90% 48%)" },
];

export const DEFAULT_ACCENT: Accent = "emerald";

/** Page background per theme, for the browser's theme-color. */
export const THEME_COLOR = { light: "#f6f6f4", dark: "#17181c" };
