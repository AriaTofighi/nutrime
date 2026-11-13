import { History, Plus, Scale } from "lucide-react";
import { useRef, useState } from "react";
import { TrendChart } from "../../components/charts";
import { WeightDialog, restoreWeight } from "../../components/WeightDialog";
import { Card, Empty, Hero, Segmented } from "../../components/ui";
import { projectedDate, reached } from "../../lib/goal";
import { store, useStore } from "../../lib/store";
import { burnEstimate, conv as convOf, profileNow, unitOf } from "../../lib/trends";
import type { WeightRecord } from "../../lib/types";
import { fmtWeight, fmtWeight1, isoDate, parseIso } from "../../lib/util";
import { addDaysIso, signed, SubHead } from "./shared";

export function WeightView({ onBack }: { onBack: () => void }) {
  useStore();
  const profile = profileNow();
  const est = burnEstimate(profile);
  const unit = unitOf(profile);
  const conv = convOf(profile);
  const [win, setWin] = useState(90);
  const [dialog, setDialog] = useState<{ existing?: WeightRecord } | null>(null);
  // "Removed weight · Undo" after a delete from the edit dialog.
  const [removed, setRemoved] = useState<WeightRecord>();
  const toastTimer = useRef<number>();
  function onDeleted(rec: WeightRecord) {
    setRemoved(rec);
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setRemoved(undefined), 5000);
  }
  const weights = store.all("weights").sort((a, b) => b.date.localeCompare(a.date));
  const today = isoDate();
  const shown = (est?.days ?? []).filter((d) => win === 0 || d.date > addDaysIso(today, -win));
  const trendOn = new Map((est?.days ?? []).map((d) => [d.date, d.trendKg]));
  const goal = profile.goalWeightKg;
  const projDate = est && goal != null ? projectedDate(today, est.trendKg, goal, est.ratePerWeekKg) : null;
  const projection = est && goal != null && projDate && projDate <= addDaysIso(today, 365) ? [{ date: today, v: conv(est.trendKg) }, { date: projDate, v: conv(goal) }] : undefined;
  const monthAgo = est?.days.find((d) => d.date >= addDaysIso(today, -30));
  return (
    <div className="page page-trends">
      <SubHead title="Weight" onBack={onBack}>
        <button className="btn btn-secondary btn-sm" onClick={() => setDialog({})}><Plus size={15} /> Log</button>
      </SubHead>
      {!est ? (
        <Card><Empty icon={<Scale size={22} />} title="No weigh-ins yet">Log your weight to see your trend. Daily weigh-ins in the morning give the best trend.</Empty></Card>
      ) : (
        <>
          <Card>
            <Hero label="Trend weight" value={fmtWeight1(conv(est.trendKg))} unit={unit} status={monthAgo && est.days.length > 7 ? `${signed(conv(est.trendKg - monthAgo.trendKg), unit)} in 30 days` : undefined} />
            <p className="compare">Rate now: {Math.abs(est.ratePerWeekKg) < 0.02 ? "steady" : `${signed(conv(est.ratePerWeekKg), unit)} a week`}</p>
            <Segmented options={[{ value: 30, label: "Month" }, { value: 90, label: "3 months" }, { value: 365, label: "Year" }, { value: 0, label: "All" }]} value={win} onChange={setWin} />
            {shown.length >= 2 && (
              <TrendChart
                line={shown.map((d) => ({ date: d.date, v: conv(d.trendKg) }))}
                dots={shown.filter((d) => d.scaleKg != null).map((d) => ({ date: d.date, v: conv(d.scaleKg!) }))}
                refs={goal != null ? [{ v: conv(goal), label: "Goal" }] : undefined}
                projection={projection}
                label="Trend weight and weigh-ins"
              />
            )}
            <p className="hint">Line: trend weight. Dots: weigh-ins. A single weigh-in can be up or down by about 1 kg from water and food.</p>
          </Card>
          {goal != null && (
            <Card title="Goal" action={<span className="card-meta">{fmtWeight1(conv(goal))} {unit}</span>}>
              {reached(est.trendKg, goal) ? <p className="hint">You are at your goal weight.</p> : (
                <div className="sheet-lines">
                  <div className="sheet-line"><span>At your goal rate</span><span className="num">{(() => { const d = projectedDate(today, est.trendKg, goal, profile.goalKgPerWeek); return d ? parseIso(d).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }) : "—"; })()}</span></div>
                  <div className="sheet-line"><span>At your current rate</span><span className="num">{projDate ? parseIso(projDate).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }) : "Not at this rate"}</span></div>
                  <p className="hint">{fmtWeight1(conv(Math.abs(goal - est.trendKg)))} {unit} to go</p>
                </div>
              )}
            </Card>
          )}
        </>
      )}
      {weights.length > 0 && (
        <Card title={<span><History size={15} style={{ verticalAlign: -2 }} /> Weigh-ins</span>}>
          <ul className="list">
            {weights.map((w) => {
              const t = trendOn.get(w.date);
              const d = t != null ? conv(w.weightKg - t) : null;
              return (
                <li key={w.id}>
                  <button className="list-row" onClick={() => setDialog({ existing: w })}>
                    <span>
                      {parseIso(w.date).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric", year: "numeric" })}
                      {d != null && <span className="dim"> · {Math.abs(d) < 0.05 ? "on trend" : `${d > 0 ? "+" : "−"}${Math.abs(d).toFixed(1)} ${unit} from trend`}</span>}
                    </span>
                    <span className="num">{fmtWeight(conv(w.weightKg))} <span className="dim">{unit}</span>{w.bodyFatPct != null && <span className="dim"> · {w.bodyFatPct.toFixed(1)}%</span>}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        </Card>
      )}
      {dialog && <WeightDialog imperial={profile.imperial} existing={dialog.existing} onClose={() => setDialog(null)} onDeleted={onDeleted} />}
      {removed && (
        <div className="toast">
          Removed weight
          <button className="btn btn-ghost btn-sm" onClick={() => { restoreWeight(removed); setRemoved(undefined); }}>Undo</button>
        </div>
      )}
    </div>
  );
}
