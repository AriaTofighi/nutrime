#!/usr/bin/env python3
"""Generate Compose ImageVectors for the Lucide icons the Android app uses.

The web app uses lucide-react; this converts the same icon geometry (from web/node_modules) into
app/src/main/java/com/nutrime/app/ui/theme/LucideIcons.kt so both apps show identical icons.

Usage (after `npm ci` in web/):
  python3 tools/gen_icons.py
Add an icon by appending its Lucide name (kebab-case, as in lucide.dev) to ICONS below.
"""
import os
import re

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "web", "node_modules", "lucide-react", "dist", "esm", "icons")
OUT = os.path.join(ROOT, "app", "src", "main", "java", "com", "nutrime", "app", "ui", "theme", "LucideIcons.kt")

ICONS = [
    "apple", "arrow-left", "arrow-up", "camera", "image-plus", "messages-square", "square", "square-pen", "book-open", "chart-line", "check", "chevron-down", "chevron-left",
    "chevron-right", "chevron-up", "cloud", "cloud-off", "copy", "database", "ellipsis-vertical", "eye", "eye-off", "flame", "globe",
    "heart-pulse", "history", "loader-circle", "log-out", "minus", "monitor", "moon", "pencil", "plus",
    "refresh-cw", "salad", "scale", "scan-barcode", "search", "sun", "target", "trash-2", "user-round", "x",
    "copy-plus", "calendar-arrow-down", "grip-vertical", "arrow-right-left", "chart-column", "clipboard-paste",
    "chart-pie", "ruler", "list-checks", "palette", "info",
    "star", "circle-check", "notebook-pen", "bookmark", "bell", "download", "upload", "activity", "flag", "utensils",
]

NUM = r"-?[\d.]+"


def attrs(body):
    return {k: (s if s is not None else n) for k, s, n in re.findall(r'(\w+):\s*(?:"([^"]*)"|(' + NUM + "))", body)}


def f(v):
    return float(v)


def fmt(v):
    v = round(v, 4)
    return str(int(v)) if v == int(v) else str(v)


ARGS = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "a": 7, "z": 0}


def normalize(d):
    """Re-serialize SVG path data with explicit separators.

    Minified paths pack arc flags against the next number ("a2 2 0 0022 17"), which Compose's
    parser misreads; here arc flags are read as single characters, as the SVG grammar allows.
    """
    out, i, cmd = [], 0, None
    num = re.compile(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?")
    while i < len(d):
        ch = d[i]
        if ch in " ,\t\n":
            i += 1
            continue
        if ch.isalpha():
            cmd = ch
            out.append(ch)
            i += 1
            if ch.lower() == "z":
                continue
        n = ARGS[cmd.lower()]
        args = []
        while len(args) < n:
            while i < len(d) and d[i] in " ,\t\n":
                i += 1
            if cmd.lower() == "a" and len(args) in (3, 4):
                args.append(d[i])
                i += 1
                continue
            m = num.match(d, i)
            if not m:
                raise ValueError(f"bad path near {d[i:i + 12]!r}")
            args.append(m.group(0))
            i = m.end()
        out.append(" ".join(args))
        if cmd == "m":
            cmd = "l"
        elif cmd == "M":
            cmd = "L"
    return " ".join(out)


def to_path(tag, a):
    if tag == "path":
        return normalize(a["d"])
    if tag in ("circle", "ellipse"):
        cx, cy = f(a["cx"]), f(a["cy"])
        rx = f(a.get("r", a.get("rx")))
        ry = f(a.get("r", a.get("ry")))
        return (f"M{fmt(cx - rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2 * rx)} 0"
                f"a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2 * rx)} 0z")
    if tag == "rect":
        x, y, w, h = f(a.get("x", 0)), f(a.get("y", 0)), f(a["width"]), f(a["height"])
        rx = f(a.get("rx", a.get("ry", 0)))
        if rx == 0:
            return f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
        return (f"M{fmt(x + rx)} {fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)} {fmt(rx)} 0 0 1 {fmt(rx)} {fmt(rx)}"
                f"v{fmt(h - 2 * rx)}a{fmt(rx)} {fmt(rx)} 0 0 1 {fmt(-rx)} {fmt(rx)}h{fmt(-(w - 2 * rx))}"
                f"a{fmt(rx)} {fmt(rx)} 0 0 1 {fmt(-rx)} {fmt(-rx)}v{fmt(-(h - 2 * rx))}"
                f"a{fmt(rx)} {fmt(rx)} 0 0 1 {fmt(rx)} {fmt(-rx)}z")
    if tag == "line":
        return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if tag in ("polyline", "polygon"):
        pts = re.findall(NUM, a["points"])
        pairs = [f"{pts[i]} {pts[i + 1]}" for i in range(0, len(pts), 2)]
        return "M" + "L".join(pairs) + ("z" if tag == "polygon" else "")
    raise ValueError(f"unsupported element {tag}")


def load(name):
    src = open(os.path.join(SRC, f"{name}.mjs"), encoding="utf-8").read()
    alias = re.search(r"export \{ default \} from './([\w-]+)\.mjs'", src)
    if alias:  # renamed icons re-export their new name
        return load(alias.group(1))
    node = src[src.index("node: ["):src.index("__iconData.node")]
    return [to_path(tag, attrs(body)) for tag, body in re.findall(r'\[\s*"(\w+)",\s*\{([^}]*)\}\s*\]', node)]


def pascal(name):
    return "".join(p.capitalize() for p in name.split("-"))


def main():
    out = [
        "// GENERATED by tools/gen_icons.py from lucide-react (ISC license, https://lucide.dev). Do not edit by hand.",
        "package com.nutrime.app.ui.theme",
        "",
        "import androidx.compose.ui.graphics.SolidColor",
        "import androidx.compose.ui.graphics.StrokeCap",
        "import androidx.compose.ui.graphics.StrokeJoin",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "import androidx.compose.ui.graphics.vector.addPathNodes",
        "import androidx.compose.ui.graphics.vector.path",
        "import androidx.compose.ui.unit.dp",
        "",
        "/** Lucide icons (24x24, stroked), identical to the web app's. Tint them with Icon(tint = ...). */",
        "object Lucide {",
    ]
    for name in ICONS:
        paths = ", ".join(f'"{p}"' for p in load(name))
        out.append(f"    val {pascal(name)}: ImageVector by lazy {{ icon(\"{name}\", {paths}) }}")
    out += [
        "",
        "    private fun icon(name: String, vararg paths: String): ImageVector =",
        "        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {",
        "            paths.forEach { d ->",
        "                addPath(",
        "                    pathData = addPathNodes(d),",
        "                    stroke = SolidColor(androidx.compose.ui.graphics.Color.Black),",
        "                    strokeLineWidth = 2f,",
        "                    strokeLineCap = StrokeCap.Round,",
        "                    strokeLineJoin = StrokeJoin.Round,",
        "                )",
        "            }",
        "        }.build()",
        "}",
        "",
    ]
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out))
    print(f"wrote {os.path.relpath(OUT, ROOT)} ({len(ICONS)} icons)")


if __name__ == "__main__":
    main()
