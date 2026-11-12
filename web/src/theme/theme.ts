import { useSyncExternalStore } from "react";
import { ACCENTS, DEFAULT_ACCENT, THEME_COLOR, type Accent } from "./tokens";

export { ACCENTS, type Accent };

/** Theme preferences: color mode and accent. Applied as data attributes on <html> (see tokens.css). */

export type ThemeMode = "system" | "light" | "dark";
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
interface Prefs {
  mode: ThemeMode;
  accent: Accent;
}

function read(): Prefs {
  try {
    const p = JSON.parse(localStorage.getItem(KEY) ?? "{}");
    return { mode: p.mode ?? "system", accent: p.accent ?? DEFAULT_ACCENT };
  } catch {
    return { mode: "system", accent: DEFAULT_ACCENT };
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
  root.dataset.theme = resolved();
  root.dataset.accent = prefs.accent;
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", THEME_COLOR[resolved()]);
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
      if (snapshot.mode !== prefs.mode || snapshot.accent !== prefs.accent || snapshot.resolved !== resolved())
        snapshot = { ...prefs, resolved: resolved() };
      return snapshot;
    },
  );
}
