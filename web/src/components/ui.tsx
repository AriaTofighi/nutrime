import { ChevronLeft, ChevronRight, X } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { GROUPS, N, NUTRIENTS, NUTRIENT_BY_ID, fmtNum, type NutrientMap } from "../lib/nutrients";
import { parseProfile, targets as profileTargets, type Target } from "../lib/profile";
import { useStore } from "../lib/store";
import { addDays, isoDate, parseIso } from "../lib/util";

export function Modal({
  title,
  subtitle,
  onClose,
  children,
  wide,
}: {
  title: string;
  subtitle?: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    document.body.classList.add("modal-open");
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.classList.remove("modal-open");
    };
  }, [onClose]);
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? "modal-wide" : ""}`} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-head">
          <div>
            <h2>{title}</h2>
            {subtitle && <p className="modal-sub">{subtitle}</p>}
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            <X size={18} />
          </button>
        </header>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  );
}

export function Card({
  title,
  eyebrow,
  action,
  children,
  className,
  flush,
}: {
  title?: ReactNode;
  eyebrow?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
  flush?: boolean;
}) {
  return (
    <section className={`card ${flush ? "card-flush" : ""} ${className ?? ""}`}>
      {(title || action || eyebrow) && (
        <header className="card-head">
          <div>
            {eyebrow && <div className="eyebrow">{eyebrow}</div>}
            {title && <h3 className="card-title">{title}</h3>}
          </div>
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

/** Thin progress track. Over an upper limit it turns red; limit-only targets use a neutral fill. */
export function Track({ value, target, color, thick }: { value: number; target?: Target; color?: string; thick?: boolean }) {
  const goal = target?.min ?? target?.max;
  const pct = goal ? Math.min(100, (value / goal) * 100) : 0;
  const over = target?.max != null && value > target.max;
  const limitOnly = target?.min == null && target?.max != null;
  const fill = over ? "var(--danger)" : color ?? (limitOnly ? "var(--text-tertiary)" : "var(--accent)");
  return (
    <div className={`track ${thick ? "track-thick" : ""}`} aria-hidden>
      <div className="track-fill" style={{ width: `${pct}%`, background: fill }} />
    </div>
  );
}

/** Labeled meter, e.g. "Protein 87 / 100 g ··· 87%". */
export function Meter({
  label,
  value,
  target,
  unit,
  color,
}: {
  label: string;
  value: number;
  target?: Target;
  unit: string;
  color?: string;
}) {
  const goal = target?.min ?? target?.max;
  const pct = goal ? Math.round((value / goal) * 100) : null;
  return (
    <div className="meter">
      <div className="meter-row">
        <span className="meter-label">
          {color && <i className="dot" style={{ background: color }} />}
          {label}
        </span>
        <span className="meter-value num">
          {fmtNum(value)}
          {goal != null && <span className="dim"> / {fmtNum(goal)}</span>} <span className="dim">{unit}</span>
        </span>
        <span className={`meter-pct num ${target?.max != null && value > target.max ? "danger" : ""}`}>{pct != null ? `${pct}%` : ""}</span>
      </div>
      <Track value={value} target={target} color={color} thick />
    </div>
  );
}

export function NutrientRow({ id, value, target }: { id: number; value: number; target?: Target }) {
  const def = NUTRIENT_BY_ID[id];
  const goal = target?.min ?? target?.max;
  const pct = goal ? Math.round((value / goal) * 100) : null;
  const over = target?.max != null && value > target.max;
  return (
    <div className="nrow">
      <span className="nrow-name">{def.name}</span>
      <span className="nrow-val num">
        {fmtNum(value)}
        {goal != null ? <span className="dim"> / {fmtNum(goal)}</span> : null} <span className="dim">{def.unit}</span>
      </span>
      <span className={`nrow-pct num ${over ? "danger" : ""}`}>{pct != null ? `${pct}%` : "—"}</span>
      <Track value={value} target={target} />
    </div>
  );
}

/** All nutrients, in one card per group, laid out as a responsive grid. Bars compare with the user's daily targets unless others are given. */
export function NutrientGrid({ totals, targets, showEmpty = true }: { totals: NutrientMap; targets?: Record<number, Target>; showEmpty?: boolean }) {
  const store = useStore();
  const json = store.get("profile", "profile")?.json;
  const daily = useMemo(() => targets ?? profileTargets(parseProfile(json)), [targets, json]);
  targets = daily;
  return (
    <div className="ngrid">
      {GROUPS.map((g) => {
        const defs = NUTRIENTS.filter((n) => n.group === g && (showEmpty || totals[n.id] != null));
        if (!defs.length) return null;
        return (
          <div key={g} className="ngroup">
            <div className="eyebrow">{g}</div>
            {defs.map((n) => (
              <NutrientRow key={n.id} id={n.id} value={totals[n.id] ?? 0} target={targets?.[n.id]} />
            ))}
          </div>
        );
      })}
    </div>
  );
}

export interface RingSegment {
  value: number;
  color: string;
}

/** Donut chart. Segments fill proportionally to `total` (or their sum). */
export function Ring({
  segments,
  total,
  size = 120,
  stroke = 10,
  children,
}: {
  segments: RingSegment[];
  total?: number;
  size?: number;
  stroke?: number;
  children?: ReactNode;
}) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const sum = segments.reduce((s, x) => s + Math.max(0, x.value), 0);
  const denom = Math.max(total ?? sum, sum, 1e-9);
  const gap = segments.filter((s) => s.value > 0).length > 1 ? 2.5 : 0;
  let offset = 0;
  return (
    <div className="ring" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--track)" strokeWidth={stroke} />
        {segments.map((s, i) => {
          const len = (Math.max(0, s.value) / denom) * c;
          if (len <= 0) return null;
          const el = (
            <circle
              key={i}
              cx={size / 2}
              cy={size / 2}
              r={r}
              fill="none"
              stroke={s.color}
              strokeWidth={stroke}
              strokeLinecap="round"
              strokeDasharray={`${Math.max(0.01, len - gap)} ${c}`}
              strokeDashoffset={-offset}
              transform={`rotate(-90 ${size / 2} ${size / 2})`}
              className="ring-seg"
            />
          );
          offset += len;
          return el;
        })}
      </svg>
      <div className="ring-center">{children}</div>
    </div>
  );
}

export function Segmented<T extends string | number>({
  options,
  value,
  onChange,
  size,
}: {
  options: { value: T; label: ReactNode }[];
  value: T;
  onChange: (v: T) => void;
  size?: "sm";
}) {
  return (
    <div className={`segmented ${size === "sm" ? "segmented-sm" : ""}`} role="radiogroup">
      {options.map((o) => (
        <button
          key={String(o.value)}
          role="radio"
          aria-checked={o.value === value}
          className={o.value === value ? "on" : ""}
          onClick={() => onChange(o.value)}
          type="button"
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Empty({ icon, title, children }: { icon?: ReactNode; title?: string; children?: ReactNode }) {
  return (
    <div className="empty">
      {icon && <div className="empty-icon">{icon}</div>}
      {title && <div className="empty-title">{title}</div>}
      {children && <div className="empty-text">{children}</div>}
    </div>
  );
}

/** Month calendar; days with entries get a dot. */
export function Calendar({ value, onChange, marked }: { value: string; onChange: (iso: string) => void; marked: Set<string> }) {
  const [month, setMonth] = useState(() => value.slice(0, 7));
  useEffect(() => setMonth(value.slice(0, 7)), [value]);
  const today = isoDate();
  const days = useMemo(() => {
    const first = parseIso(`${month}-01`);
    const start = addDays(`${month}-01`, -first.getDay());
    return Array.from({ length: 42 }, (_, i) => addDays(start, i));
  }, [month]);
  const shift = (n: number) => {
    const d = parseIso(`${month}-01`);
    d.setMonth(d.getMonth() + n);
    setMonth(isoDate(d).slice(0, 7));
  };
  const label = parseIso(`${month}-01`).toLocaleDateString(undefined, { month: "long", year: "numeric" });
  const weekdays = Array.from({ length: 7 }, (_, i) => parseIso(addDays("2023-01-01", i)).toLocaleDateString(undefined, { weekday: "narrow" }));
  return (
    <div className="cal">
      <div className="cal-head">
        <span className="cal-title">{label}</span>
        <div className="cal-nav">
          <button className="icon-btn sm" onClick={() => shift(-1)} aria-label="Previous month">
            <ChevronLeft size={16} />
          </button>
          <button className="icon-btn sm" onClick={() => shift(1)} aria-label="Next month">
            <ChevronRight size={16} />
          </button>
        </div>
      </div>
      <div className="cal-grid">
        {weekdays.map((w, i) => (
          <span key={i} className="cal-wd">
            {w}
          </span>
        ))}
        {days.map((d) => (
          <button
            key={d}
            className={[
              "cal-day",
              d.slice(0, 7) !== month ? "out" : "",
              d === value ? "sel" : "",
              d === today ? "today" : "",
              marked.has(d) ? "marked" : "",
            ].join(" ")}
            onClick={() => onChange(d)}
            aria-label={parseIso(d).toDateString()}
            aria-pressed={d === value}
          >
            {Number(d.slice(8))}
          </button>
        ))}
      </div>
    </div>
  );
}

const MACRO_IDS = { protein: N.PROTEIN, carbs: N.CARBS, fat: N.FAT };

export type Tone = "neutral" | "good" | "warn" | "bad";

/** A short plain-language status ("526 under target") in a soft pill. */
export function StatusPill({ children, tone = "neutral" }: { children: ReactNode; tone?: Tone }) {
  return <span className={`status-pill tone-${tone}`}>{children}</span>;
}

/** The one answer a card gives: a label, a large number with its unit, and an optional status. */
export function Hero({ label, value, unit, status, tone }: { label: ReactNode; value: ReactNode; unit?: string; status?: ReactNode; tone?: Tone }) {
  return (
    <div className="hero">
      <span className="hero-label">{label}</span>
      <div className="hero-value num">
        {value}
        {unit && <small>{unit}</small>}
      </div>
      {status != null && <StatusPill tone={tone}>{status}</StatusPill>}
    </div>
  );
}

/** Protein, carbs and fat side by side: grams (against a target when given) and a thin bar. */
export function MacroTiles({ values, targets }: { values: NutrientMap; targets?: Record<number, Target> }) {
  const rows: [number, string, string][] = [
    [MACRO_IDS.protein, "Protein", "var(--protein)"],
    [MACRO_IDS.carbs, "Carbs", "var(--carbs)"],
    [MACRO_IDS.fat, "Fat", "var(--fat)"],
  ];
  return (
    <div className="macro-tiles">
      {rows.map(([id, label, color]) => {
        const v = values[id] ?? 0;
        const t = targets?.[id]?.min;
        return (
          <div key={id} className="macro-tile">
            <span className="macro-tile-label">
              <i className="dot" style={{ background: color }} />
              {label}
            </span>
            <span className="macro-tile-value num">
              {Math.round(v)}
              <small>{t ? ` / ${Math.round(t)} g` : " g"}</small>
            </span>
            {targets && (
              <div className="track">
                <div className="track-fill" style={{ width: `${Math.min(100, t ? (v / t) * 100 : 0)}%`, background: color }} />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}


/** How many targets are met, as a row of small segments. */
export function ScoreBar({ met, total }: { met: number; total: number }) {
  if (total <= 0) return null;
  return (
    <div className="score-bar" role="img" aria-label={`${met} of ${total} targets met`}>
      {Array.from({ length: total }, (_, i) => (
        <i key={i} className={i < met ? "on" : undefined} />
      ))}
    </div>
  );
}

/** Calm daily bars: one neutral color, the selected day in the accent, a dashed target line, no axis. */
export function MiniBars({
  values,
  labels,
  target,
  selected,
  onSelect,
  targets,
  limit,
  unit = "kcal",
}: {
  values: (number | null)[];
  labels: string[];
  target?: number;
  selected: number | null;
  onSelect: (i: number) => void;
  /** A target per bar (weekday calorie schedule), drawn instead of the single line. */
  targets?: (number | null)[];
  limit?: number;
  unit?: string;
}) {
  const top = Math.max(1, ...values.map((v) => v ?? 0), target ?? 0, ...(targets ?? []).map((t) => t ?? 0), limit ?? 0) * 1.06;
  const n = values.length;
  return (
    <div className="mini-bars">
      <div className="mini-bars-plot" style={{ gap: n > 40 ? 1 : n > 14 ? 3 : 8 }}>
        {values.map((v, i) => (
          <button
            key={i}
            className={`mini-bar ${i === selected ? "on" : ""} ${v == null || v <= 0 ? "empty" : ""}`}
            style={{ height: v == null || v <= 0 ? undefined : `${(v / top) * 100}%` }}
            onClick={() => onSelect(i)}
            aria-label={`${labels[i]}: ${v == null ? "not logged" : `${fmtNum(v)} ${unit}`}`}
          />
        ))}
        {targets && targets.length === n
          ? targets.map((t, i) =>
              t == null ? null : <div key={`t${i}`} className="mini-bars-target" style={{ bottom: `${(t / top) * 100}%`, left: `${(i / n) * 100}%`, right: `${100 - ((i + 1) / n) * 100}%` }} />,
            )
          : target != null && <div className="mini-bars-target" style={{ bottom: `${(target / top) * 100}%` }} />}
        {limit != null && <div className="mini-bars-target mini-bars-limit" style={{ bottom: `${(limit / top) * 100}%` }} />}
      </div>
      <div className="mini-bars-labels">
        {labels.map((l, i) => (
          <span key={i} className={i === selected ? "on" : undefined}>
            {n <= 7 || i === selected || ((i === 0 || i === n - 1) && (selected == null || Math.abs(i - selected) > n / 6)) ? l : ""}
          </span>
        ))}
      </div>
    </div>
  );
}
