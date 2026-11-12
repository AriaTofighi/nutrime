import { ArrowLeft } from "lucide-react";
import { useMemo, useState } from "react";
import { Modal, Segmented } from "../../components/ui";
import { N, NUTRIENTS, sum, type NutrientMap } from "../../lib/nutrients";
import { calorieTargetOn, encodeProfile, targets, type Profile } from "../../lib/profile";
import { PRESETS, bounds, customRange, decodeRange, encodeRange, eachDay, previous, rangeName, type TrendRange } from "../../lib/range";
import { store, useStore } from "../../lib/store";
import { dayTotals, firstDate, profileNow } from "../../lib/trends";
import { isoDate, parseIso, shortDate } from "../../lib/util";

export function niceMax(v: number) {
  const step = v > 3000 ? 1000 : 500;
  return Math.ceil(v / step) * step;
}

/** Open a day in the Diary tab. */
export function openDay(date: string) {
  try {
    sessionStorage.setItem("nutrime.openDate", date);
  } catch {
    /* storage blocked: the Diary opens on today */
  }
  window.location.hash = "#/diary";
}

export const signed = (v: number, unit: string) => (Math.abs(v) < 0.05 ? "No change" : `${v > 0 ? "+" : "−"}${Math.abs(v).toFixed(1)} ${unit}`);

export function compareText(now: number, before: number | undefined, unit: string, label: string, digits = 0): string | null {
  if (before == null) return null;
  const d = now - before;
  if (Math.abs(d) < (digits ? 0.05 : 0.5)) return `Same as ${label}`;
  return `${d > 0 ? "↑" : "↓"} ${Math.abs(d).toLocaleString(undefined, { maximumFractionDigits: digits })} ${unit} against ${label}`;
}

export const short = (name: string) => name.split(" (")[0].replace(/^./, (ch) => ch.toLowerCase());

/** Everything the Trends views need for one range. */
/** Which Trends page is open. */
export type View = { kind: "main" } | { kind: "nutrients" } | { kind: "nutrient"; id: number } | { kind: "weight" } | { kind: "burn" };

export interface Period {
  range: TrendRange;
  from: string;
  to: string;
  days: string[];
  totals: Map<string, NutrientMap>;
  counted: string[];
  avg: NutrientMap;
  prevAvg?: NutrientMap;
  prevCounted: number;
  profile: Profile;
  tgt: ReturnType<typeof targets>;
  calorieTarget: number;
  prevLabel: string;
}

export function average(days: string[], totals: Map<string, NutrientMap>): NutrientMap {
  const out: NutrientMap = {};
  if (!days.length) return out;
  const s = sum(days.map((d) => totals.get(d)!).filter(Boolean));
  for (const k in s) out[k] = s[k] / days.length;
  return out;
}

export function usePeriod(range: TrendRange): Period {
  const version = useStore().version;
  return useMemo(() => {
    const profile = profileNow();
    const today = isoDate();
    const all = dayTotals();
    const [from, to] = bounds(range, today, firstDate());
    const [pf, pt] = previous(from, to);
    const totals = new Map([...all].filter(([d]) => d >= from && d <= to));
    const logged = [...totals.keys()].sort();
    const counted = logged.filter((d) => d !== today).length ? logged.filter((d) => d !== today) : logged;
    const prevDays = [...all.keys()].filter((d) => d >= pf && d <= pt);
    const days = eachDay(from, to);
    return {
      range, from, to, days, totals, counted, avg: average(counted, totals),
      prevAvg: prevDays.length ? average(prevDays, all) : undefined, prevCounted: prevDays.length,
      profile, tgt: targets(profile),
      calorieTarget: counted.length ? counted.reduce((s, d) => s + calorieTargetOn(profile, d), 0) / counted.length : calorieTargetOn(profile),
      prevLabel: `the ${days.length} days before`,
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [encodeRange(range), version]);
}

export const hasPrev = (p: Period) => p.prevAvg != null && p.prevCounted >= 3;

export function watch(avg: NutrientMap, tgt: Period["tgt"]) {
  const withMin = NUTRIENTS.filter((d) => tgt[d.id]?.min != null && !([N.ENERGY, N.PROTEIN, N.CARBS, N.FAT, N.WATER] as number[]).includes(d.id));
  const ratio = (id: number) => (avg[id] ?? 0) / tgt[id]!.min!;
  return {
    withMin,
    low: withMin.filter((d) => ratio(d.id) < 0.9).sort((a, b) => ratio(a.id) - ratio(b.id)),
    over: NUTRIENTS.filter((d) => tgt[d.id]?.max != null && (avg[d.id] ?? 0) > tgt[d.id]!.max!),
    met: withMin.filter((d) => ratio(d.id) >= 1).length,
  };
}

export function RangeControl({ range, onChange }: { range: TrendRange; onChange: (r: TrendRange) => void }) {
  const [more, setMore] = useState(false);
  const today = isoDate();
  const [b0, b1] = bounds(range, today, firstDate());
  const [from, setFrom] = useState(b0);
  const [to, setTo] = useState(b1);
  const key = encodeRange(range);
  const quick = ["7d", "30d", "90d"];
  const custom = customRange(from, to);
  return (
    <>
      <Segmented
        options={[{ value: "7d", label: "Week" }, { value: "30d", label: "Month" }, { value: "90d", label: "3 months" }, { value: "more", label: "More" }]}
        value={quick.includes(key) ? key : "more"}
        onChange={(v) => (v === "more" ? setMore(true) : onChange(decodeRange(v)!))}
      />
      {more && (
        <Modal title="Range" onClose={() => setMore(false)}>
          <div className="chips">
            {[...PRESETS.filter((d) => d > 90).map((d) => ({ kind: "last", days: d }) as TrendRange), { kind: "all" } as TrendRange].map((r) => (
              <button key={encodeRange(r)} className={`chip ${encodeRange(r) === key ? "on" : ""}`} onClick={() => { onChange(r); setMore(false); }}>
                {rangeName(r)}
              </button>
            ))}
          </div>
          <h4 style={{ margin: "var(--space-4) 0 var(--space-2)" }}>Custom range</h4>
          <div className="range-custom">
            <label>From<input className="input" type="date" value={from} max={today} onChange={(e) => setFrom(e.target.value)} /></label>
            <label>To<input className="input" type="date" value={to} max={today} onChange={(e) => setTo(e.target.value)} /></label>
            <button className="btn btn-primary" disabled={!custom || to > today} onClick={() => { onChange(custom!); setMore(false); }}>Show</button>
          </div>
          {!custom && <p className="hint">Choose up to 730 days.</p>}
        </Modal>
      )}
    </>
  );
}

export function rangeLabelFor(b: { from: string; to: string; size: string }) {
  if (b.size === "DAY") return parseIso(b.from).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" });
  if (b.size === "WEEK") return `Week of ${shortDate(b.from)}`;
  return parseIso(b.from).toLocaleDateString(undefined, { month: "long", year: "numeric" });
}

export function SubHead({ title, onBack, children }: { title: string; onBack: () => void; children?: React.ReactNode }) {
  return (
    <header className="page-head">
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <button className="icon-btn" onClick={onBack} aria-label="Back"><ArrowLeft size={18} /></button>
        <h1 className="page-title">{title}</h1>
      </div>
      {children}
    </header>
  );
}

export const addDaysIso = (iso: string, n: number) => {
  const d = parseIso(iso);
  d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

export function saveProfile(p: Profile) {
  store.put("profile", { id: "profile", json: encodeProfile({ ...p, configured: true }), updatedAt: Date.now(), deleted: false });
}
