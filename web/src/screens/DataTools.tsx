import { Download, Upload } from "lucide-react";
import { useRef, useState } from "react";
import { daysCsv, diaryCsv, download, weightsCsv, zip } from "../lib/export";
import { applyImport, readImport, type ImportPlan } from "../lib/import";
import { entryNutrients } from "../lib/foods";
import { sum } from "../lib/nutrients";
import { calorieTargetOn, encodeProfile } from "../lib/profile";
import { store } from "../lib/store";
import { burnEstimate, profileNow } from "../lib/trends";
import { COLLECTION_NAMES } from "../lib/types";
import { isoDate, shortDate } from "../lib/util";

/** "Export your data" (CSV files and a full copy) and "Import data" (Cronometer, MyFitnessPal, NutriMe). */
export function ExportImport() {
  const [plan, setPlan] = useState<ImportPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [skip, setSkip] = useState(true);
  const [lb, setLb] = useState(profileNow().imperial);
  const file = useRef<HTMLInputElement>(null);

  const exportAll = () => {
    const profile = profileNow();
    const diary = store.all("diary");
    const days = new Map(store.all("days").map((d) => [d.id, d]));
    const byDate = new Map<string, ReturnType<typeof entryNutrients>[]>();
    for (const e of diary) (byDate.get(e.date) ?? byDate.set(e.date, []).get(e.date)!).push(entryNutrients(e));
    const trend = new Map((burnEstimate(profile)?.days ?? []).map((d) => [d.date, d.trendKg]));
    const dates = [...new Set([...byDate.keys(), ...days.keys()])].sort();
    const records = Object.fromEntries(COLLECTION_NAMES.map((c) => [c, store.all(c)]));
    const blob = zip({
      "diary.csv": diaryCsv(diary.map((e) => ({ date: e.date, meal: e.meal, food: e.foodName, brand: e.brand, amount: e.quantity, serving: e.servingLabel, grams: e.servingGrams * e.quantity, nutrients: entryNutrients(e) }))),
      "days.csv": daysCsv(dates.map((d) => ({ date: d, complete: days.get(d)?.complete ?? false, note: days.get(d)?.note, steps: days.get(d)?.steps, activeKcal: days.get(d)?.activeKcal, target: calorieTargetOn(profile, d), totals: sum(byDate.get(d) ?? []) }))),
      "weights.csv": weightsCsv(store.all("weights").map((w) => ({ date: w.date, kg: w.weightKg, bodyFatPct: w.bodyFatPct, trendKg: trend.get(w.date), note: w.note }))),
      "profile.json": encodeProfile(profile),
      "nutrime.json": JSON.stringify(records),
      "README.txt": `NutriMe export, ${isoDate()}.\r\ndiary.csv: every food you logged, with nutrients for the amount eaten.\r\ndays.csv: daily totals, targets, notes, steps and active energy.\r\nweights.csv: weigh-ins and the trend weight.\r\nnutrime.json: every record, to restore with Import data.\r\n`,
    });
    download(`nutrime-${isoDate()}.zip`, blob);
  };

  const pick = async (f: File | undefined) => {
    setError(null);
    setDone(null);
    setPlan(null);
    if (!f) return;
    try {
      setPlan(await readImport(f));
    } catch (e) {
      setError(e instanceof Error ? e.message : "This file can't be read.");
    }
    if (file.current) file.current.value = "";
  };

  const apply = () => {
    if (!plan) return;
    const r = applyImport(plan, { skipExistingDays: skip, weightsInLb: plan.needsWeightUnit ? lb : undefined });
    setDone(`Imported ${r.entries.toLocaleString()} entries and ${r.weights.toLocaleString()} weigh-ins. They sync to your phone.`);
    setPlan(null);
  };

  return (
    <div className="form">
      <div className="field">
        <span>Export your data</span>
        <p className="hint">A ZIP file with your diary, daily totals and weigh-ins as CSV files for a spreadsheet, and a full copy you can import again.</p>
        <div><button className="btn btn-primary" onClick={exportAll}><Download size={15} /> Export</button></div>
      </div>
      <div className="field">
        <span>Import data</span>
        <p className="hint">From Cronometer (servings.csv, biometrics.csv), MyFitnessPal (nutrition summary and measurement CSV, or the ZIP), or a NutriMe export. You see a summary before anything is saved.</p>
        <div>
          <input ref={file} type="file" accept=".csv,.zip,text/csv,application/zip" hidden onChange={(e) => void pick(e.target.files?.[0])} />
          <button className="btn btn-secondary" onClick={() => file.current?.click()}><Upload size={15} /> Choose file</button>
        </div>
      </div>
      {error && <p className="hint danger">{error}</p>}
      {done && <p className="hint">{done}</p>}
      {plan && (
        <div className="card">
          <h4 style={{ margin: 0 }}>{plan.label}</h4>
          <div className="sheet-lines">
            {plan.from && <div className="sheet-line"><span>Dates</span><span className="num">{shortDate(plan.from)} – {shortDate(plan.to!)}</span></div>}
            <div className="sheet-line"><span>Diary entries</span><span className="num">{plan.entries.length.toLocaleString()} on {new Set(plan.entries.map((e) => e.date)).size} days</span></div>
            <div className="sheet-line"><span>Weigh-ins</span><span className="num">{plan.weights.length.toLocaleString()}</span></div>
          </div>
          {plan.existingDays.length > 0 && (
            <label className="toggle-row">
              <span>Skip the {plan.existingDays.length} days that already have entries in NutriMe</span>
              <input type="checkbox" checked={skip} onChange={(e) => setSkip(e.target.checked)} />
            </label>
          )}
          {plan.needsWeightUnit && (
            <label className="toggle-row">
              <span>Weights in the file are in pounds</span>
              <input type="checkbox" checked={lb} onChange={(e) => setLb(e.target.checked)} />
            </label>
          )}
          {plan.ignored.length > 0 && <p className="hint">Columns not imported: {plan.ignored.slice(0, 12).join(", ")}{plan.ignored.length > 12 ? ` and ${plan.ignored.length - 12} more` : ""}.</p>}
          <p className="hint">Imported foods show in your diary with their totals. Importing the same file again changes nothing.</p>
          <div className="actions">
            <span className="grow" />
            <button className="btn btn-ghost" onClick={() => setPlan(null)}>Cancel</button>
            <button className="btn btn-primary" onClick={apply} disabled={!plan.entries.length && !plan.weights.length}>Import</button>
          </div>
        </div>
      )}
    </div>
  );
}
