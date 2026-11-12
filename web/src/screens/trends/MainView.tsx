import { ChevronRight, Plus, Scale } from "lucide-react";
import { useMemo, useState } from "react";
import { TrendChart, useWidth } from "../../components/charts";
import { WeightDialog } from "../../components/WeightDialog";
import { Card, Empty, Hero, MacroTiles, MiniBars, Modal, ScoreBar, type Tone } from "../../components/ui";
import { missing, type BurnEstimate } from "../../lib/burn";
import { projectedDate, reached } from "../../lib/goal";
import { WEEKDAYS, patterns } from "../../lib/insights";
import { N, type NutrientMap } from "../../lib/nutrients";
import { LB_PER_KG, calorieTargetOn, macroGrams, tdee, type Profile } from "../../lib/profile";
import { bucketAverage, bucketLabel, buckets, customRange, eachDay, rangeName, type TrendRange } from "../../lib/range";
import { store } from "../../lib/store";
import { burnEstimate, burnSource, conv as convOf, loggedItems, unitOf } from "../../lib/trends";
import { isoDate, parseIso, shortDate } from "../../lib/util";
import { COLORS } from "../../theme/theme";
import { compareText, hasPrev, niceMax, openDay, type Period, RangeControl, rangeLabelFor, short, signed, watch, type View } from "./shared";

const MACROS: [number, number, string, string][] = [
  [N.PROTEIN, 4, COLORS.protein, "Protein"],
  [N.CARBS, 4, COLORS.carbs, "Carbs"],
  [N.FAT, 9, COLORS.fat, "Fat"],
  [N.ALCOHOL, 7, COLORS.alcohol, "Alcohol"],
];
/** Stacked bars by macro, one per day (ranges up to a month). */
function CalorieChart({ days, target }: { days: { date: string; n: NutrientMap }[]; target: number }) {
  const [hover, setHover] = useState<number | null>(null);
  const [ref, W] = useWidth();
  const H = 240, padL = 40, padB = 26, padT = 12;
  const totals = days.map((d) => MACROS.reduce((s, [id, k]) => s + (d.n[id] ?? 0) * k, 0));
  const max = niceMax(Math.max(target * 1.1, ...totals, 500));
  const bw = (W - padL) / days.length;
  const barW = Math.max(3, Math.min(28, bw * 0.62));
  const y = (v: number) => padT + (H - padT - padB) * (1 - v / max);
  const step = Math.max(1, Math.ceil(days.length / Math.max(2, Math.floor(W / 56))));
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(max * f));
  const h = hover != null ? days[hover] : null;
  return (
    <div className="chart" ref={ref}>
      <div className="chart-readout">
        {h ? (
          <>
            <b>{parseIso(h.date).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" })}</b>
            <span className="num">{Math.round(h.n[N.ENERGY] ?? 0).toLocaleString()} kcal</span>
            {MACROS.slice(0, 3).map(([id, , color, label]) => (
              <span key={id} className="legend-item"><i className="dot" style={{ background: color }} />{label} <span className="num">{Math.round(h.n[id] ?? 0)} g</span></span>
            ))}
          </>
        ) : (
          <>
            {MACROS.map(([id, , color, label]) => (
              <span key={id} className="legend-item"><i className="dot" style={{ background: color }} />{label}</span>
            ))}
            <span className="legend-item"><i className="legend-dash" />Target</span>
          </>
        )}
      </div>
      <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Daily calories by macronutrient" onMouseLeave={() => setHover(null)}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={padL} x2={W} y1={y(t)} y2={y(t)} className="grid" />
            <text x={padL - 8} y={y(t) + 4} className="axis" textAnchor="end">{t >= 1000 ? `${(t / 1000).toFixed(t % 1000 ? 1 : 0)}k` : t}</text>
          </g>
        ))}
        {days.map((d, i) => {
          let acc = 0;
          const cx = padL + i * bw + bw / 2;
          const segs = MACROS.map(([id, k, color]) => ({ v: (d.n[id] ?? 0) * k, color, id })).filter((s) => s.v > 0);
          return (
            <g key={d.date} onMouseEnter={() => setHover(i)} onClick={() => setHover(i)} opacity={hover == null || hover === i ? 1 : 0.45} className="bar-group">
              <rect x={padL + i * bw} y={padT} width={bw} height={H - padT - padB} fill="transparent" />
              {segs.map((s, j) => {
                const top = y(acc + s.v);
                const height = y(acc) - top;
                acc += s.v;
                const isTop = j === segs.length - 1;
                return <rect key={s.id} x={cx - barW / 2} width={barW} y={top} height={Math.max(0, height - (isTop ? 0 : 1.5))} rx={isTop ? Math.min(5, barW / 2) : 1.5} fill={s.color} />;
              })}
              {i % step === 0 && (
                <text x={cx} y={H - 8} className="axis" textAnchor="middle">
                  {days.length <= 7 ? parseIso(d.date).toLocaleDateString(undefined, { weekday: "short" }) : shortDate(d.date)}
                </text>
              )}
            </g>
          );
        })}
        <line x1={padL} x2={W} y1={y(target)} y2={y(target)} className="target-line" />
      </svg>
    </div>
  );
}

export function MainView({ p, range, setRange, setView }: { p: Period; range: TrendRange; setRange: (r: TrendRange) => void; setView: (v: View) => void }) {
  const [selected, setSelected] = useState<number | null>(null);
  const [sheet, setSheet] = useState<"balance" | "macros" | null>(null);
  const [weightDialog, setWeightDialog] = useState(false);
  const { profile, avg, counted, tgt } = p;
  const est = burnEstimate(profile);
  const kcalTarget = p.calorieTarget;
  const avgKcal = avg[N.ENERGY] ?? 0;
  const diff = Math.round(avgKcal - kcalTarget);
  const [status, tone]: [string, Tone] =
    counted.length === 0 ? ["Nothing logged yet", "neutral"]
      : Math.abs(diff) <= kcalTarget * 0.05 ? ["On target", "good"]
        : diff < 0 ? [`${(-diff).toLocaleString()} under target`, "neutral"]
          : [`${diff.toLocaleString()} over target`, diff > kcalTarget * 0.15 ? "warn" : "neutral"];

  const bs = useMemo(() => buckets(p.from, p.to), [p.from, p.to]);
  const size = bs[0]?.size ?? "DAY";
  const countedSet = new Set(counted);
  const kcalByDay = new Map([...p.totals].map(([d, n]) => [d, n[N.ENERGY] ?? 0]));
  const values = size === "DAY" ? bs.map((b) => (p.totals.has(b.from) ? kcalByDay.get(b.from)! : null)) : bucketAverage(bs, kcalByDay, countedSet, (xs) => xs.reduce((a, b) => a + b, 0) / xs.length);
  const barTargets = profile.calorieSchedule?.length === 7 ? bs.map((b) => { const ds = eachDay(b.from, b.to); return ds.reduce((s, d) => s + calorieTargetOn(profile, d), 0) / ds.length; }) : undefined;
  const lastIdx = values.map((v) => v != null).lastIndexOf(true);
  const sel = selected ?? (lastIdx >= 0 ? lastIdx : null);
  const selB = sel != null ? bs[sel] : null;

  const unit = unitOf(profile);
  const conv = convOf(profile);
  const weights = store.all("weights").sort((a, b) => b.date.localeCompare(a.date));
  const inPeriod = (est?.days ?? []).filter((d) => d.date >= p.from && d.date <= p.to);
  const change = inPeriod.filter((d) => d.scaleKg != null).length >= 2 ? conv(inPeriod[inPeriod.length - 1].trendKg - inPeriod[0].trendKg) : null;
  const w = watch(avg, tgt);
  const burn = tdee(profile);
  const balance = avgKcal - burn;
  const perWeek = (balance * 7) / 7700;
  const partial = counted.filter((d) => (p.totals.get(d)?.[N.ENERGY] ?? 0) < calorieTargetOn(profile, d) * 0.5);
  const weightFirst = Math.abs(profile.goalKgPerWeek) > 0.001 && inPeriod.length > 0;
  const pr = avg[N.PROTEIN] ?? 0, cb = avg[N.CARBS] ?? 0, ft = avg[N.FAT] ?? 0;
  const macroKcal = Math.max(1, pr * 4 + cb * 4 + ft * 9);
  const goalPct = macroGrams(profile);
  const goalKcal = Math.max(1, goalPct.protein * 4 + goalPct.carbs * 4 + goalPct.fat * 9);

  const weightCard = (
    <Card
      title={<button className="card-button" onClick={() => setView({ kind: "weight" })}>Weight <ChevronRight size={14} /></button>}
      action={<button className="btn btn-secondary btn-sm" onClick={() => setWeightDialog(true)}><Plus size={15} /> Log</button>}
    >
      {!est || weights.length === 0 ? (
        <Empty icon={<Scale size={22} />} title="No weigh-ins yet">Log your weight to see your trend.</Empty>
      ) : (
        <button className="card-button" onClick={() => setView({ kind: "weight" })}>
          <Hero label="Trend weight" value={conv(est.trendKg).toFixed(1)} unit={unit} status={change == null ? undefined : `${signed(change, unit)} in this period`} />
          {profile.goalWeightKg != null && (
            <p className="compare">
              {reached(est.trendKg, profile.goalWeightKg)
                ? "At your goal weight"
                : (() => {
                    const d = projectedDate(isoDate(), est.trendKg, profile.goalWeightKg!, est.ratePerWeekKg);
                    return `Goal ${conv(profile.goalWeightKg!).toFixed(1)} ${unit}${d ? ` around ${shortDate(d)} at your current rate` : ""}`;
                  })()}
            </p>
          )}
          {inPeriod.length >= 2 && <TrendChart line={inPeriod.map((d) => ({ date: d.date, v: conv(d.trendKg) }))} dots={inPeriod.filter((d) => d.scaleKg != null).map((d) => ({ date: d.date, v: conv(d.scaleKg!) }))} label="Trend weight" height={150} />}
        </button>
      )}
    </Card>
  );

  return (
    <div className="page page-trends">
      <header className="page-head">
        <h1 className="page-title">Trends</h1>
        <RangeControl range={range} onChange={(r) => { setRange(r); setSelected(null); }} />
      </header>
      {!(range.kind === "last" && [7, 30, 90].includes(range.days)) && <p className="range-label">{rangeName(range)}</p>}

      <Card>
        <button className="card-button" onClick={() => counted.length && setSheet("balance")} aria-label="Explain the average">
          <Hero
            label={counted.length ? `Daily average · ${counted.length} of ${p.days.length} days` : "Daily average"}
            value={counted.length ? Math.round(avgKcal).toLocaleString() : "—"}
            unit="kcal" status={status} tone={tone}
          />
          {hasPrev(p) && counted.length > 0 && <p className="compare">{compareText(avgKcal, p.prevAvg![N.ENERGY] ?? 0, "kcal", p.prevLabel)}</p>}
        </button>
        <MiniBars
          values={values}
          labels={bs.map((b) => (size === "DAY" ? (p.days.length <= 7 ? parseIso(b.from).toLocaleDateString(undefined, { weekday: "narrow" }) : shortDate(b.from)) : bucketLabel(b)))}
          target={kcalTarget} targets={barTargets} selected={sel} onSelect={setSelected}
        />
        <div className="card-foot">
          {selB ? (
            <button className="day-link" onClick={() => (selB.size === "DAY" ? openDay(selB.from) : setRange(customRange(selB.from, selB.to)!))}>
              <b>{rangeLabelFor(selB)}</b>
              <span className="num">{values[sel!] == null ? "Not logged" : `${Math.round(values[sel!]!).toLocaleString()} kcal${selB.size === "DAY" ? "" : " a day"}`}</span>
              <ChevronRight size={14} />
            </button>
          ) : <span>No days logged</span>}
          <span className="num">Target {Math.round(kcalTarget).toLocaleString()}</span>
        </div>
      </Card>

      <div className="trends-grid">
        {weightFirst && weightCard}
        <BurnCard profile={profile} est={est} onOpen={() => setView({ kind: "burn" })} />
        <Card title="Macros" action={<span className="card-meta">Daily average</span>}>
          <button className="card-button" onClick={() => counted.length && setSheet("macros")} aria-label="Show calorie split">
            <MacroTiles values={avg} targets={tgt} />
            {hasPrev(p) && counted.length > 0 && (
              <p className="compare">
                {[[N.PROTEIN, "Protein"], [N.CARBS, "carbs"], [N.FAT, "fat"]].map(([id, name]) => {
                  const d = (avg[id as number] ?? 0) - (p.prevAvg![id as number] ?? 0);
                  return `${name} ${Math.abs(d) < 0.5 ? "±0" : `${d > 0 ? "+" : "−"}${Math.abs(d).toFixed(0)}`} g`;
                }).join(", ")} against {p.prevLabel}
              </p>
            )}
          </button>
        </Card>
        <Card title="Nutrients" action={counted.length > 0 && <button className="btn btn-ghost btn-sm" onClick={() => setView({ kind: "nutrients" })}>All <ChevronRight size={14} /></button>}>
          {counted.length === 0 ? (
            <p className="hint">Log a few days to see which vitamins and minerals you are short on.</p>
          ) : (
            <>
              <ScoreBar met={w.met} total={w.withMin.length} />
              <p className="calm-note">
                <strong>{w.met} of {w.withMin.length} targets met.</strong>{" "}
                {w.low.length ? `Lowest: ${w.low.slice(0, 3).map((d) => short(d.name)).join(", ")}.` : w.over.length ? "" : "You meet every target on average."}
              </p>
              {hasPrev(p) && <p className="compare">{(() => { const b = watch(p.prevAvg!, tgt).met; return b === w.met ? `Same as ${p.prevLabel}` : `${b} met in ${p.prevLabel}`; })()}</p>}
              {w.over.length > 0 && <p className="calm-note warn-text">Over the limit: {w.over.slice(0, 2).map((d) => short(d.name)).join(", ")}</p>}
            </>
          )}
        </Card>
        {!weightFirst && weightCard}
        {counted.length >= 14 && <PatternsCard p={p} />}
      </div>

      {sheet === "balance" && (
        <Modal title={rangeName(range)} subtitle={`Daily average over ${counted.length} day${counted.length === 1 ? "" : "s"}`} onClose={() => setSheet(null)}>
          <div className="sheet-lines">
            <div className="sheet-line"><span>Eaten</span><span className="num">{Math.round(avgKcal).toLocaleString()} kcal</span></div>
            <button className="sheet-line card-button" onClick={() => { setSheet(null); setView({ kind: "burn" }); }}>
              <span>Daily burn <span className="dim">· {burnSource(profile, est)}</span></span><span className="num">{Math.round(burn).toLocaleString()} kcal <ChevronRight size={12} /></span>
            </button>
            <div className="sheet-line strong"><span>{balance < 0 ? "Deficit" : "Surplus"}</span><span className="num">{Math.abs(Math.round(balance)).toLocaleString()} kcal</span></div>
            <p className="hint">
              {Math.abs(perWeek) < 0.02 ? "At this rate, your weight stays about the same."
                : `At this rate, you ${perWeek < 0 ? "lose" : "gain"} about ${profile.imperial ? `${(Math.abs(perWeek) * LB_PER_KG).toFixed(1)} lb` : `${Math.abs(perWeek).toFixed(2)} kg`} a week.`}
            </p>
            <div className="sheet-line"><span>Your target</span><span className="num">{Math.round(kcalTarget).toLocaleString()} kcal</span></div>
            {p.totals.has(isoDate()) && !countedSet.has(isoDate()) && <p className="hint">Today is not in the average until the day ends.</p>}
            {partial.length > 0 && (
              <p className="hint">
                {partial.slice(0, 5).map((d) => shortDate(d)).join(", ")}{partial.length > 5 ? ` and ${partial.length - 5} more` : ""} {partial.length === 1 ? "looks" : "look"} partly logged (below half of your target). This pulls the average down.
              </p>
            )}
          </div>
        </Modal>
      )}
      {sheet === "macros" && (
        <Modal title="Calorie split" subtitle="Share of calories from each macro" onClose={() => setSheet(null)} wide>
          <div className="macro-bar" aria-hidden>
            <i style={{ flex: pr * 4, background: COLORS.protein }} />
            <i style={{ flex: cb * 4, background: COLORS.carbs }} />
            <i style={{ flex: ft * 9, background: COLORS.fat }} />
          </div>
          {([[N.PROTEIN, "Protein", COLORS.protein, pr * 4, goalPct.protein * 4], [N.CARBS, "Carbs", COLORS.carbs, cb * 4, goalPct.carbs * 4], [N.FAT, "Fat", COLORS.fat, ft * 9, goalPct.fat * 9]] as const).map(([id, label, color, k, gk]) => (
            <button key={id} className="macro-line card-button" onClick={() => { setSheet(null); setView({ kind: "nutrient", id }); }}>
              <span className="meter-label"><i className="dot" style={{ background: color }} />{label}</span>
              <span className="num">{Math.round(avg[id] ?? 0)}<span className="dim"> / {Math.round(tgt[id]?.min ?? 0)} g</span></span>
              <span className="num dim">{Math.round((k / macroKcal) * 100)}% · goal {Math.round((gk / goalKcal) * 100)}%</span>
            </button>
          ))}
          {p.days.length <= 31 && <CalorieChart days={p.days.map((d) => ({ date: d, n: p.totals.get(d) ?? {} }))} target={kcalTarget} />}
        </Modal>
      )}
      {weightDialog && <WeightDialog imperial={profile.imperial} onClose={() => setWeightDialog(false)} />}
    </div>
  );
}

function BurnCard({ profile, est, onOpen }: { profile: Profile; est: BurnEstimate | null; onOpen: () => void }) {
  return (
    <Card title={<button className="card-button" onClick={onOpen}>Daily burn <ChevronRight size={14} /></button>}>
      <button className="card-button" onClick={onOpen}>
        {!est ? (
          <p className="hint">Log your weight a few times a week. NutriMe then learns your real daily burn from your food log and weight.</p>
        ) : est.confidence === "LEARNING" ? (
          <>
            <Hero label="Learning" value={Math.round(tdee(profile)).toLocaleString()} unit="kcal" />
            <p className="compare">Needs {missing(est).join(", ")}.</p>
          </>
        ) : (
          <>
            <Hero label="Learned from your log" value={est.burnKcal.toLocaleString()} unit={`± ${est.burnSd} kcal`} status={est.confidence === "HIGH" ? "Sure" : "Fairly sure"} tone={est.confidence === "HIGH" ? "good" : "neutral"} />
            <p className="compare">
              {est.knownDays} logged days, {est.weighIns} weigh-ins.{" "}
              {Math.abs(est.burnKcal - est.formulaKcal) < 50 ? "Close to the formula." : `${Math.abs(est.burnKcal - est.formulaKcal).toLocaleString()} kcal ${est.burnKcal > est.formulaKcal ? "more" : "less"} than the formula.`}
            </p>
          </>
        )}
      </button>
    </Card>
  );
}

function PatternsCard({ p }: { p: Period }) {
  const counted = new Set(p.counted);
  const pat = patterns(loggedItems(p.from, p.to), new Map([...p.totals].filter(([d]) => counted.has(d)).map(([d, n]) => [d, n[N.ENERGY] ?? 0])), p.from, p.to);
  const colors = [COLORS.carbs, COLORS.protein, COLORS.fat, COLORS.alcohol];
  const names = ["Breakfast", "Lunch", "Dinner", "Snacks"];
  return (
    <Card title="Patterns">
      <div className="sheet-lines">
        {pat.weekdayKcal != null && pat.weekendKcal != null && (
          <>
            <div className="sheet-line"><span>Weekdays</span><span className="num">{Math.round(pat.weekdayKcal).toLocaleString()} kcal</span></div>
            <div className="sheet-line"><span>Weekends</span><span className="num">{Math.round(pat.weekendKcal).toLocaleString()} kcal</span></div>
            <p className="hint">
              {Math.abs(pat.weekendKcal - pat.weekdayKcal) < 75 ? "You eat about the same on weekends."
                : `You eat ${Math.round(Math.abs(pat.weekendKcal - pat.weekdayKcal)).toLocaleString()} kcal ${pat.weekendKcal > pat.weekdayKcal ? "more" : "less"} on weekend days.`}
            </p>
          </>
        )}
        <div className="sheet-line"><span>Days logged</span><span className="num">{pat.loggedDays} of {pat.totalDays}</span></div>
        {pat.currentRun >= 2 && <div className="sheet-line"><span>Days in a row</span><span className="num">{pat.currentRun}</span></div>}
      </div>
      <p className="hint" style={{ marginTop: 8 }}>Calories by meal</p>
      <div className="meal-share">{pat.mealShares.map((s, i) => s > 0.005 && <i key={i} style={{ flex: s, background: colors[i] }} />)}</div>
      <div className="chips">{pat.mealShares.map((s, i) => <span key={i} className="legend-item"><i className="dot" style={{ background: colors[i] }} />{names[i]} {Math.round(s * 100)}%</span>)}</div>
      {pat.steadiestDay != null && pat.mostVariableDay != null && (
        <p className="hint">Steadiest day: {WEEKDAYS[pat.steadiestDay]}. Most variable: {WEEKDAYS[pat.mostVariableDay]}.</p>
      )}
    </Card>
  );
}
