import { useSyncExternalStore } from "react";
import { ACCENTS, DEFAULT_ACCENT, THEME_COLOR, type Accent } from "./tokens";

export { ACCENTS, type Accent };

/**
 * Display preferences, stored in this browser: color mode, dark style, accent (a preset or a custom hue),
 * text size, and what the diary summary shows. Theme values are applied as data attributes and CSS variables
 * on <html> (see tokens.css and tailwind.css).
 */

export type ThemeMode = "system" | "light" | "dark";
export type DarkStyle = "dim" | "black";
export type TextSize = "small" | "default" | "large" | "larger";
export type RingValue = "remaining" | "consumed";
export type MacroValue = "consumed" | "remaining";

export const TEXT_SIZES: { value: TextSize; label: string; scale: number }[] = [
  { value: "small", label: "Small", scale: 0.9 },
  { value: "default", label: "Default", scale: 1 },
  { value: "large", label: "Large", scale: 1.12 },
  { value: "larger", label: "Larger", scale: 1.25 },
];

/** Saturation for a custom accent hue. */
export const CUSTOM_SATURATION = 70;

/** Macro colors, as CSS variables so charts follow the theme. */
export const COLORS = {
  energy: "var(--energy)",
  protein: "var(--protein)",
  carbs: "var(--carbs)",
  fat: "var(--fat)",
  alcohol: "var(--alcohol)",
  accent: "var(--accent)",
  track: "var(--track)",
};

const KEY = "nutrime.theme";
export interface Prefs {
  mode: ThemeMode;
  accent: Accent;
  /** A custom accent hue (0..359); null uses [accent]. */
  customHue: number | null;
  darkStyle: DarkStyle;
  textSize: TextSize;
  ring: RingValue;
  macros: MacroValue;
  /** Color the diary ring by protein, carbs and fat. */
  ringSplit: boolean;
}

const DEFAULTS: Prefs = {
  mode: "system",
  accent: DEFAULT_ACCENT,
  customHue: null,
  darkStyle: "dim",
  textSize: "default",
  ring: "remaining",
  macros: "consumed",
  ringSplit: false,
};

function read(): Prefs {
  try {
    const p = JSON.parse(localStorage.getItem(KEY) ?? "{}") as Partial<Prefs>;
    return {
      ...DEFAULTS,
      ...p,
      accent: ACCENTS.some((a) => a.id === p.accent) ? (p.accent as Accent) : DEFAULT_ACCENT,
      customHue: typeof p.customHue === "number" ? Math.round(p.customHue) % 360 : null,
    };
  } catch {
    return { ...DEFAULTS };
  }
}

let prefs = read();
const listeners = new Set<() => void>();
const media = window.matchMedia("(prefers-color-scheme: dark)");

function resolved(): "light" | "dark" {
  return prefs.mode === "system" ? (media.matches ? "dark" : "light") : prefs.mode;
}

function apply() {
  const root = document.documentElement;
  const mode = resolved();
  root.dataset.theme = mode;
  root.dataset.accent = prefs.accent;
  if (mode === "dark" && prefs.darkStyle === "black") root.dataset.dark = "black";
  else delete root.dataset.dark;
  if (prefs.customHue == null) {
    root.style.removeProperty("--accent-h");
    root.style.removeProperty("--accent-s");
  } else {
    root.style.setProperty("--accent-h", String(prefs.customHue));
    root.style.setProperty("--accent-s", `${CUSTOM_SATURATION}%`);
  }
  const scale = TEXT_SIZES.find((t) => t.value === prefs.textSize)?.scale ?? 1;
  if (scale === 1) root.style.removeProperty("font-size");
  else root.style.fontSize = `${scale * 100}%`;
  const color = mode === "dark" && prefs.darkStyle === "black" ? "#000000" : THEME_COLOR[mode];
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", color);
}

export function initTheme() {
  apply();
  media.addEventListener("change", () => {
    if (prefs.mode === "system") {
      apply();
      listeners.forEach((l) => l());
    }
  });
}

export function setTheme(patch: Partial<Prefs>) {
  prefs = { ...prefs, ...patch };
  try {
    localStorage.setItem(KEY, JSON.stringify(prefs));
  } catch {
    /* ignore */
  }
  apply();
  listeners.forEach((l) => l());
}

let snapshot = { ...prefs, resolved: resolved() };
export function useTheme() {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    () => {
      const r = resolved();
      if ((Object.keys(prefs) as (keyof Prefs)[]).some((k) => snapshot[k] !== prefs[k]) || snapshot.resolved !== r)
        snapshot = { ...prefs, resolved: r };
      return snapshot;
    },
  );
}
