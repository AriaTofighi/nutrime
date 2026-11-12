import { useEffect, useRef, useState } from "react";
import { addDays, parseIso, shortDate } from "../lib/util";
import { dayDiff } from "../lib/range";

/** Measure the container so charts draw at real pixel size. */
export function useWidth(fallback = 640) {
  const ref = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(fallback);
  useEffect(() => {
    if (!ref.current) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(240, Math.round(e.contentRect.width))));
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, []);
  return [ref, w] as const;
}

export interface Pt {
  date: string;
  v: number;
}

function niceStep(range: number, ticks = 3) {
  const raw = range / ticks;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const n = raw / mag;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
}

/**
 * A trend line with an optional uncertainty band, raw points as dots, horizontal reference lines
 * and a dashed projection. Dates on x.
 */
export function TrendChart({
  line,
  band,
  dots,
  refs,
  projection,
  label,
  format = (v) => v.toFixed(v >= 100 ? 0 : 1),
  height = 200,
}: {
  line: Pt[];
  band?: { date: string; lo: number; hi: number }[];
  dots?: Pt[];
  refs?: { v: number; label: string }[];
  projection?: Pt[];
  label: string;
  format?: (v: number) => string;
  height?: number;
}) {
  const [ref, W] = useWidth();
  if (!line.length) return null;
  const H = height, padL = 44, padB = 24, padT = 12, padR = 10;
  const all = [...line.map((p) => p.v), ...(dots ?? []).map((p) => p.v), ...(refs ?? []).map((r) => r.v), ...(projection ?? []).map((p) => p.v), ...(band ?? []).flatMap((b) => [b.lo, b.hi])];
  const minV = Math.min(...all), maxV = Math.max(...all);
  const step = niceStep(Math.max(0.5, maxV - minV));
  const lo = Math.floor(minV / step) * step;
  const hi = Math.max(lo + step, Math.ceil(maxV / step) * step);
  const first = line[0].date;
  const last = [...line, ...(projection ?? [])].map((p) => p.date).sort().pop()!;
  const span = Math.max(1, dayDiff(first, last));
  const x = (d: string) => padL + (dayDiff(first, d) / span) * (W - padL - padR);
  const y = (v: number) => padT + (H - padT - padB) * (1 - (v - lo) / (hi - lo));
  const path = (pts: Pt[]) => pts.map((p, i) => `${i ? "L" : "M"}${x(p.date).toFixed(1)},${y(p.v).toFixed(1)}`).join(" ");
  const ticks: number[] = [];
  for (let t = lo; t <= hi + step / 1000; t += step) ticks.push(t);
  const end = line[line.length - 1];
  return (
    <div className="chart trend-chart" ref={ref}>
      <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} role="img" aria-label={label}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={padL} x2={W - padR} y1={y(t)} y2={y(t)} className="grid" />
            <text x={padL - 8} y={y(t) + 4} className="axis" textAnchor="end">{format(t)}</text>
          </g>
        ))}
        {band && band.length > 1 && (
          <path className="band" d={`${band.map((b, i) => `${i ? "L" : "M"}${x(b.date)},${y(b.hi)}`).join(" ")} ${[...band].reverse().map((b) => `L${x(b.date)},${y(b.lo)}`).join(" ")} Z`} />
        )}
        {(refs ?? []).map((r) => (
          <g key={r.label}>
            <line x1={padL} x2={W - padR} y1={y(r.v)} y2={y(r.v)} className="ref" />
            <text x={W - padR} y={y(r.v) - 4} className="axis" textAnchor="end">{r.label}</text>
          </g>
        ))}
        {(dots ?? []).map((p) => (
          <circle key={p.date} cx={x(p.date)} cy={y(p.v)} r={2.4} className="raw">
            <title>{shortDate(p.date)}: {format(p.v)}</title>
          </circle>
        ))}
        {line.length > 1 && <path d={path(line)} className="trend" />}
        {projection && projection.length > 1 && <path d={path(projection)} className="proj" />}
        <circle cx={x(end.date)} cy={y(end.v)} r={4} className="end" />
        <text x={padL} y={H - 6} className="axis">{shortDate(first)}</text>
        <text x={W - padR} y={H - 6} className="axis" textAnchor="end">{shortDate(last)}</text>
      </svg>
    </div>
  );
}

export const dayName = (iso: string, style: "narrow" | "short" = "short") => parseIso(iso).toLocaleDateString(undefined, { weekday: style });
export { addDays };
