import { useMemo, useState } from "react";
import { Card, Hero, MiniBars, NutrientRow, ScoreBar, Track } from "../../components/ui";
import { topSources } from "../../lib/insights";
import { GROUPS, NUTRIENTS, NUTRIENT_BY_ID, fmtNum } from "../../lib/nutrients";
import { bucketAverage, bucketLabel, buckets, rangeName, type TrendRange } from "../../lib/range";
import { loggedItems } from "../../lib/trends";
import { shortDate } from "../../lib/util";
import { compareText, hasPrev, type Period, RangeControl, rangeLabelFor, short, SubHead, watch } from "./shared";

export function NutrientsView({ p, range, setRange, onBack, onOpen }: { p: Period; range: TrendRange; setRange: (r: TrendRange) => void; onBack: () => void; onOpen: (id: number) => void }) {
  const w = watch(p.avg, p.tgt);
  return (
    <div className="page page-trends">
      <SubHead title="Nutrients" onBack={onBack}><RangeControl range={range} onChange={setRange} /></SubHead>
      {p.counted.length === 0 ? (
        <Card><p className="hint">No days logged yet. Daily averages show here once you log food.</p></Card>
      ) : (
        <>
          <Card>
            <Hero label={`Vitamins and minerals · ${p.counted.length} days · ${rangeName(range)}`} value={`${w.met}`} unit={`of ${w.withMin.length} targets met`} />
            <ScoreBar met={w.met} total={w.withMin.length} />
            {w.low.length > 0 && <p className="calm-note">Lowest: {w.low.slice(0, 3).map((d) => short(d.name)).join(", ")}</p>}
            <p className="hint">Select a nutrient to see where it comes from.</p>
          </Card>
          {GROUPS.map((g) => (
            <Card key={g} title={g}>
              {NUTRIENTS.filter((d) => d.group === g).map((d) => (
                <button key={d.id} className="card-button" onClick={() => onOpen(d.id)} aria-label={`Open ${d.name}`}>
                  <NutrientRow id={d.id} value={p.avg[d.id] ?? 0} target={p.tgt[d.id]} />
                </button>
              ))}
            </Card>
          ))}
        </>
      )}
    </div>
  );
}

export function NutrientView({ p, id, range, setRange, onBack }: { p: Period; id: number; range: TrendRange; setRange: (r: TrendRange) => void; onBack: () => void }) {
  const def = NUTRIENT_BY_ID[id];
  const target = p.tgt[id];
  const [selected, setSelected] = useState<number | null>(null);
  const avg = p.avg[id] ?? 0;
  const bs = useMemo(() => buckets(p.from, p.to), [p.from, p.to]);
  const values = bucketAverage(bs, new Map([...p.totals].map(([d, n]) => [d, n[id] ?? 0])), new Set(p.counted), (xs) => xs.reduce((a, b) => a + b, 0) / xs.length);
  const items = loggedItems(p.from, p.to);
  const sources = topSources(items, id);
  const min = target?.min;
  const latest = new Map<string, (typeof items)[number]>();
  for (const it of items) if (!it.foodId.startsWith("quick:") && !it.foodId.startsWith("import:") && (!latest.has(it.foodId) || latest.get(it.foodId)!.date < it.date)) latest.set(it.foodId, it);
  const rich = [...latest.values()].map((it) => ({ it, amount: it.nutrients[id] ?? 0 })).filter((x) => x.amount > 0).sort((a, b) => b.amount - a.amount).slice(0, 5);
  const over = target?.max != null && avg > target.max;
  const pct = min ? (avg / min) * 100 : null;
  return (
    <div className="page page-trends">
      <SubHead title={def.name.split(" (")[0]} onBack={onBack}><RangeControl range={range} onChange={setRange} /></SubHead>
      {p.counted.length === 0 ? <Card><p className="hint">No days logged in this range.</p></Card> : (
        <>
          <Card>
            <Hero
              label={`Daily average · ${rangeName(range)}`}
              value={fmtNum(avg)} unit={def.unit}
              status={over ? `Over the limit of ${fmtNum(target!.max!)} ${def.unit}` : pct != null ? `${Math.round(pct)}% of target` : undefined}
              tone={over ? "warn" : pct != null && pct >= 100 ? "good" : "neutral"}
            />
            {hasPrev(p) && <p className="compare">{compareText(avg, p.prevAvg![id] ?? 0, def.unit, p.prevLabel, avg < 10 ? 1 : 0)}</p>}
            <MiniBars values={values} labels={bs.map((b) => (b.size === "DAY" ? shortDate(b.from) : bucketLabel(b)))} target={min} limit={target?.max} selected={selected} onSelect={setSelected} unit={def.unit} />
            {selected != null && <p className="hint">{rangeLabelFor(bs[selected])}: {values[selected] == null ? "Not logged" : `${fmtNum(values[selected]!)} ${def.unit}`}</p>}
          </Card>
          {sources.length > 0 && (
            <Card title="Where it comes from">
              {sources.map((s) => (
                <div key={s.key} className="source-row">
                  <div className="row-main"><span>{s.name}</span><span className="num">{Math.round(s.share * 100)}%</span></div>
                  <span className="hint">{fmtNum(s.amount / Math.max(1, p.counted.length))} {def.unit} a day · logged {s.times}×</span>
                  <Track value={s.share} target={{ min: 1 }} />
                </div>
              ))}
            </Card>
          )}
          {min != null && avg < min && rich.length > 0 && (
            <Card title="Your foods with the most">
              {rich.map(({ it, amount }) => (
                <div key={it.foodId} className="sheet-line"><span>{it.name}</span><span className="num">{fmtNum(amount)} {def.unit} · {Math.round((amount / min) * 100)}%</span></div>
              ))}
            </Card>
          )}
        </>
      )}
    </div>
  );
}
