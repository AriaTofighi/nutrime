#!/usr/bin/env python3
"""Generate platform design tokens from design/tokens.json (the single source of truth).

Outputs:
  web/src/theme/tokens.css                                   CSS custom properties for the web app
  web/src/theme/tokens.ts                                    accent list + theme-color values for theme.ts
  app/src/main/java/com/nutrime/app/ui/theme/DesignTokens.kt Kotlin constants for the Android app
  app/src/main/res/values{,-night}/design_tokens.xml         Android colors used before Compose draws

Usage:
  python3 tools/gen_design_tokens.py           # regenerate
  python3 tools/gen_design_tokens.py --check   # exit 1 if any output is stale (used in CI)
"""
import json
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "design", "tokens.json")
OUT_CSS = os.path.join(ROOT, "web", "src", "theme", "tokens.css")
OUT_TS = os.path.join(ROOT, "web", "src", "theme", "tokens.ts")
OUT_RES = os.path.join(ROOT, "app", "src", "main", "res", "values", "design_tokens.xml")
OUT_RES_NIGHT = os.path.join(ROOT, "app", "src", "main", "res", "values-night", "design_tokens.xml")
OUT_KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "nutrime", "app", "ui", "theme", "DesignTokens.kt")

HEADER = "GENERATED from design/tokens.json by tools/gen_design_tokens.py. Do not edit by hand."


def num(v):
    """Format a number without a trailing .0."""
    v = round(float(v), 6)
    return str(int(v)) if v.is_integer() else repr(v)


def kebab(name):
    return "".join("-" + c.lower() if c.isupper() else c for c in name)


def pascal(name):
    """'2xl' -> 'Xl2', 'textSecondary' -> 'TextSecondary', '50' -> 'G50'."""
    if name[0].isdigit():
        digits = "".join(c for c in name if c.isdigit())
        rest = name[len(digits):]
        return (rest[:1].upper() + rest[1:] + digits) if rest else "G" + digits
    return name[0].upper() + name[1:]


def hex_rgb(h):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


# ---------------------------------------------------------------------------------------------
# CSS
# ---------------------------------------------------------------------------------------------

def css_color(value, theme):
    if isinstance(value, str):
        if value.startswith("gray."):
            return f"var(--gray-{value[5:]})"
        return value
    if "mix" in value:
        return f"color-mix(in srgb, var(--gray-{value['mix'][5:]}) {num(value['alpha'] * 100)}%, transparent)"
    r, g, b = hex_rgb(value["rgb"])
    return f"rgba({r}, {g}, {b}, {num(value['alpha'])})"


def gen_css(t):
    ty = t["typography"]
    lines = [
        "/*",
        f" * {HEADER}",
        " *",
        " * NutriMe design tokens. Every color, size, radius, shadow and duration in the UI comes from here.",
        " * Components never use raw values. Two layers:",
        " *   1. Primitive scales (--gray-*, accent scales) that change per theme/accent.",
        " *   2. Semantic tokens (--bg, --surface, --text, --accent, --protein, ...) used by components.",
        " *",
        " * Theme: <html data-theme=\"light|dark\"> (set from the user's choice or the OS, see theme.ts).",
        " * Accent: <html data-accent=\"" + "|".join(t["accents"]["options"]) + "\">.",
        " */",
        "",
        ":root {",
        "  /* ---------- typography ---------- */",
        f"  --font-sans: {ty['fontFamily']};",
    ]
    for k, px in ty["size"].items():
        lines.append(f"  --text-{k}: {num(px / 16)}rem; /* {num(px)} */")
    for k, w in ty["weight"].items():
        lines.append(f"  --weight-{k}: {w};")
    for k, em in ty["tracking"].items():
        lines.append(f"  --tracking-{k}: {num(em)}em;")
    lines += ["", "  /* ---------- spacing (4px grid) ---------- */"]
    for k, px in t["space"].items():
        lines.append(f"  --space-{k}: {num(px)}px;")
    lines += ["", "  /* ---------- shape ---------- */"]
    for k, px in t["radius"].items():
        lines.append(f"  --radius-{k}: {num(px)}px;")
    m = t["motion"]
    lines += [
        "",
        "  /* ---------- motion ---------- */",
        f"  --ease-out: cubic-bezier({', '.join(num(x) for x in m['easeOut'])});",
        f"  --duration-fast: {m['durationFast']}ms;",
        f"  --duration: {m['duration']}ms;",
        f"  --duration-slow: {m['durationSlow']}ms;",
        "",
        "  /* ---------- controls ---------- */",
    ]
    c = t["control"]
    lines += [
        f"  --control-h-sm: {c['hSm']}px;",
        f"  --control-h: {c['h']}px;",
        f"  --control-h-lg: {c['hLg']}px;",
        f"  --row-h: {c['rowH']}px;",
        "",
        "  /* ---------- layout ---------- */",
    ]
    for k, px in t["layout"].items():
        lines.append(f"  --{kebab(k)}: {num(px)}px;")
    acc = t["accents"]
    default = acc["options"][acc["default"]]
    lines += [
        "",
        "  /* ---------- accents (overridden by data-accent) ---------- */",
        f"  --accent-h: {default['h']};",
        f"  --accent-s: {default['s']}%;",
        "}",
        "",
    ]
    for name, a in acc["options"].items():
        if name == acc["default"]:
            continue
        lines += [f'[data-accent="{name}"] {{', f"  --accent-h: {a['h']};", f"  --accent-s: {a['s']}%;", "}"]
    for theme_name, th in t["themes"].items():
        sel = ":root,\n[data-theme=\"light\"]" if theme_name == "light" else f'[data-theme="{theme_name}"]'
        lines += ["", f"/* ---------- {theme_name} ---------- */", f"{sel} {{", f"  color-scheme: {theme_name};"]
        for k, v in th["gray"].items():
            lines.append(f"  --gray-{k}: {v};")
        lines.append("")
        for k, v in th["semantic"].items():
            lines.append(f"  --{kebab(k)}: {css_color(v, th)};")
        a = th["accent"]
        hs = "var(--accent-h) var(--accent-s)"
        lines += [
            "",
            f"  --accent: hsl({hs} {a['l']}%);",
            f"  --accent-hover: hsl({hs} {a['hoverL']}%);",
            f"  --accent-soft: hsl({hs} {a['l']}% / {num(a['softAlpha'])});",
            f"  --accent-softer: hsl({hs} {a['l']}% / {num(a['softerAlpha'])});",
            f"  --on-accent: {a['on']};",
            "",
        ]
        for k, v in th["palette"].items():
            lines.append(f"  --{kebab(k)}: {css_color(v, th)};")
        lines.append("")
        for k, v in th["shadow"].items():
            lines.append(f"  --shadow-{k}: {v};")
        lines.append(f"  --ring-focus: 0 0 0 3px hsl({hs} {a['ringL']}% / {num(a['ringAlpha'])});")
        lines.append("}")
    return "\n".join(lines) + "\n"


def gen_ts(t):
    acc = t["accents"]
    entries = ",\n".join(
        f'  {{ id: "{k}", label: "{a["label"]}", swatch: "hsl({a["h"]} {a["s"]}% {a["swatchL"]}%)" }}'
        for k, a in acc["options"].items()
    )
    ids = " | ".join(f'"{k}"' for k in acc["options"])
    return (
        f"// {HEADER}\n\n"
        f"export type Accent = {ids};\n\n"
        f"export const ACCENTS: {{ id: Accent; label: string; swatch: string }}[] = [\n{entries},\n];\n\n"
        f'export const DEFAULT_ACCENT: Accent = "{acc["default"]}";\n\n'
        "/** Page background per theme, for the browser's theme-color. */\n"
        f'export const THEME_COLOR = {{ light: "{t["themes"]["light"]["gray"]["50"]}", dark: "{t["themes"]["dark"]["gray"]["0"]}" }};\n'
    )


# ---------------------------------------------------------------------------------------------
# Kotlin
# ---------------------------------------------------------------------------------------------

def kt_color(value, th):
    """Resolve a token to a Compose Color expression with a concrete ARGB value."""
    if isinstance(value, str):
        if value.startswith("gray."):
            value = th["gray"][value[5:]]
        r, g, b = hex_rgb(value)
        return f"Color(0xFF{r:02X}{g:02X}{b:02X})"
    base = th["gray"][value["mix"][5:]] if "mix" in value else value["rgb"]
    r, g, b = hex_rgb(base)
    a = round(value["alpha"] * 255)
    return f"Color(0x{a:02X}{r:02X}{g:02X}{b:02X})"


def gen_kt(t):
    ty, acc = t["typography"], t["accents"]
    out = [
        f"// {HEADER}",
        "package com.nutrime.app.ui.theme",
        "",
        "import androidx.compose.animation.core.CubicBezierEasing",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.unit.dp",
        "import androidx.compose.ui.unit.sp",
        "",
        "/** Design tokens shared with the web app (see design/tokens.json). */",
        "object DesignTokens {",
        "    object TextSize {",
    ]
    for k, px in ty["size"].items():
        out.append(f"        val {pascal(k)} = {num(px)}.sp" if float(px).is_integer() else f"        val {pascal(k)} = {px}f.sp")
    out += ["    }", "", "    /** Variable-font weights (the static Inter files round to the nearest 100). */", "    object Weight {"]
    for k, w in ty["weight"].items():
        out.append(f"        const val {pascal(k)} = {w}")
    out += ["    }", "", "    /** Letter spacing in em. */", "    object Tracking {"]
    for k, em in ty["tracking"].items():
        out.append(f"        const val {pascal(k)} = {em}f")
    out += ["    }", "", "    object Space {"]
    for k, px in t["space"].items():
        out.append(f"        val S{k} = {num(px)}.dp")
    out += ["    }", "", "    object Radius {"]
    for k, px in t["radius"].items():
        out.append(f"        val {pascal(k)} = {num(px)}.dp")
    m = t["motion"]
    out += [
        "    }",
        "",
        "    object Motion {",
        f"        val EaseOut = CubicBezierEasing({', '.join(num(x) + 'f' for x in m['easeOut'])})",
        f"        const val DurationFast = {m['durationFast']}",
        f"        const val Duration = {m['duration']}",
        f"        const val DurationSlow = {m['durationSlow']}",
        "    }",
        "",
        "    object Control {",
    ]
    c = t["control"]
    out += [
        f"        val HeightSm = {c['hSm']}.dp",
        f"        val Height = {c['h']}.dp",
        f"        val HeightLg = {c['hLg']}.dp",
        f"        val RowHeight = {c['rowH']}.dp",
        "    }",
        "",
        "    enum class Accent(val label: String, val hue: Float, val saturation: Float, val swatchLightness: Float) {",
    ]
    opts = list(acc["options"].items())
    for i, (k, a) in enumerate(opts):
        sep = "," if i < len(opts) - 1 else ";"
        out.append(f'        {k.upper()}("{a["label"]}", {a["h"]}f, {a["s"] / 100}f, {a["swatchL"] / 100}f){sep}')
    out += [
        "",
        "        companion object {",
        f"            val Default = {acc['default'].upper()}",
        "        }",
        "    }",
        "",
    ]
    for theme_name, th in t["themes"].items():
        a = th["accent"]
        out.append(f"    val {pascal(theme_name)} = ThemeTokens(")
        out.append(f"        isDark = {'true' if theme_name == 'dark' else 'false'},")
        for k, v in th["semantic"].items():
            out.append(f"        {k} = {kt_color(v, th)},")
        for k, v in th["palette"].items():
            out.append(f"        {k} = {kt_color(v, th)},")
        out += [
            f"        accentLightness = {a['l'] / 100}f,",
            f"        accentHoverLightness = {a['hoverL'] / 100}f,",
            f"        accentSoftAlpha = {a['softAlpha']}f,",
            f"        accentSofterAlpha = {a['softerAlpha']}f,",
            f"        onAccent = {kt_color(a['on'], th)},",
            "    )",
            "",
        ]
    out[-1:] = ["}", ""]
    th = t["themes"]["light"]
    fields = ["    val isDark: Boolean,"]
    fields += [f"    val {k}: Color," for k in th["semantic"]]
    fields += [f"    val {k}: Color," for k in th["palette"]]
    fields += [
        "    val accentLightness: Float,",
        "    val accentHoverLightness: Float,",
        "    val accentSoftAlpha: Float,",
        "    val accentSofterAlpha: Float,",
        "    val onAccent: Color,",
    ]
    out += ["/** Resolved colors for one theme (light or dark); the accent hue is applied in NutriMeTheme. */", "data class ThemeTokens("]
    out += fields
    out += [")", ""]
    return "\n".join(out)


def hsl_hex(h, s, l):
    import colorsys
    r, g, b = colorsys.hls_to_rgb(h / 360, l / 100, s / 100)
    return "#%02X%02X%02X" % (round(r * 255), round(g * 255), round(b * 255))


def gen_res(t, theme_name):
    """Android color resources for things drawn before Compose (window background, launcher icon)."""
    th = t["themes"][theme_name]
    acc = t["accents"]["options"][t["accents"]["default"]]
    bg = th["semantic"]["bg"]
    bg = th["gray"][bg[5:]] if bg.startswith("gray.") else bg
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {HEADER} -->",
        "<resources>",
        f'    <color name="bg">{bg.upper()}</color>',
        f'    <color name="brand">{hsl_hex(acc["h"], acc["s"], th["accent"]["l"])}</color>',
        f'    <color name="on_brand">{th["accent"]["on"].upper()}</color>',
        "</resources>",
        "",
    ]
    return "\n".join(lines)


def main():
    with open(SRC, encoding="utf-8") as f:
        tokens = json.load(f)
    outputs = {
        OUT_CSS: gen_css(tokens),
        OUT_TS: gen_ts(tokens),
        OUT_KT: gen_kt(tokens),
        OUT_RES: gen_res(tokens, "light"),
        OUT_RES_NIGHT: gen_res(tokens, "dark"),
    }
    check = "--check" in sys.argv
    stale = []
    for path, content in outputs.items():
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if current == content:
            continue
        if check:
            stale.append(os.path.relpath(path, ROOT))
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            print(f"wrote {os.path.relpath(path, ROOT)}")
    if stale:
        print("Design tokens are stale; run `python3 tools/gen_design_tokens.py`:\n  " + "\n  ".join(stale))
        sys.exit(1)


if __name__ == "__main__":
    main()
