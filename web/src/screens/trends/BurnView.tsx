import { useState } from "react";
import { TrendChart } from "../../components/charts";
import { Card, Hero, Segmented } from "../../components/ui";
import { missing } from "../../lib/burn";
import { tdee, type BurnMode } from "../../lib/profile";
import { useStore } from "../../lib/store";
import { burnEstimate, conv as convOf, profileNow, unitOf } from "../../lib/trends";
import { isoDate } from "../../lib/util";
import { saveProfile, signed, SubHead } from "./shared";

export function BurnView({ onBack }: { onBack: () => void }) {
  useStore();
  const profile = profileNow();
  const e = burnEstimate(profile);
  const unit = unitOf(profile);
  const conv = convOf(profile);
  const shown = (e?.days ?? []).slice(-180);
  const [manual, setManual] = useState(profile.manualBurnKcal?.toString() ?? "");
  const setMode = (m: BurnMode) => {
    const learned = e && e.confidence !== "LEARNING" ? e.burnKcal : null;
    saveProfile({
      ...profile, burnMode: m, burnModeChosen: true,
      adaptiveBurnKcal: m === "ADAPTIVE" ? profile.adaptiveBurnKcal ?? learned : profile.adaptiveBurnKcal,
      adaptiveBurnDate: m === "ADAPTIVE" && profile.adaptiveBurnKcal == null && learned != null ? isoDate() : profile.adaptiveBurnDate,
    });
  };
  return (
    <div className="page page-trends page-narrow">
      <SubHead title="Daily burn" onBack={onBack} />
      <Card>
        {!e ? (
          <>
            <Hero label="From your body and activity" value={Math.round(tdee({ ...profile, burnMode: "FORMULA" })).toLocaleString()} unit="kcal" />
            <p className="hint">Log your weight a few times a week and log your food. After about two weeks NutriMe learns your real daily burn.</p>
          </>
        ) : (
          <>
            <Hero
              label={e.confidence === "LEARNING" ? "Learning your daily burn" : "Learned daily burn"}
              value={e.burnKcal.toLocaleString()} unit={`± ${e.burnSd} kcal`}
              status={e.confidence === "HIGH" ? "Sure" : e.confidence === "MEDIUM" ? "Fairly sure" : "Learning"}
              tone={e.confidence === "HIGH" ? "good" : "neutral"}
            />
            {e.confidence === "LEARNING" && <p className="compare">Needs {missing(e).join(", ")}.</p>}
            {shown.length >= 2 && (
              <TrendChart
                line={shown.map((d) => ({ date: d.date, v: d.burnKcal }))}
                band={shown.map((d) => ({ date: d.date, lo: d.burnKcal - d.burnSd, hi: d.burnKcal + d.burnSd }))}
                refs={[{ v: e.formulaKcal, label: "Formula" }]}
                label="Learned daily burn over time"
                format={(v) => Math.round(v).toLocaleString()}
              />
            )}
            <div className="sheet-lines">
              <div className="sheet-line"><span>Formula says</span><span className="num">{e.formulaKcal.toLocaleString()} kcal</span></div>
              <div className="sheet-line"><span>Difference</span><span className="num">{e.burnKcal >= e.formulaKcal ? "+" : "−"}{Math.abs(e.burnKcal - e.formulaKcal).toLocaleString()} kcal</span></div>
              <div className="sheet-line"><span>Fully logged days used</span><span className="num">{e.knownDays}</span></div>
              <div className="sheet-line"><span>Weigh-ins used</span><span className="num">{e.weighIns}</span></div>
              <div className="sheet-line"><span>Trend weight</span><span className="num">{conv(e.trendKg).toFixed(1)} {unit}</span></div>
              <div className="sheet-line"><span>Rate now</span><span className="num">{Math.abs(e.ratePerWeekKg) < 0.02 ? "steady" : `${signed(conv(e.ratePerWeekKg), unit)} a week`}</span></div>
            </div>
          </>
        )}
      </Card>
      <Card title="Burn used for your targets">
        <Segmented options={[{ value: "ADAPTIVE" as BurnMode, label: "Learned" }, { value: "FORMULA" as BurnMode, label: "Formula" }, { value: "MANUAL" as BurnMode, label: "My own" }]} value={profile.burnMode} onChange={setMode} />
        {profile.burnMode === "ADAPTIVE" && (
          <>
            <p className="hint">
              {profile.adaptiveBurnKcal != null
                ? `Your targets use ${profile.adaptiveBurnKcal.toLocaleString()} kcal. Each week NutriMe asks before it changes this value, by 150 kcal at most.`
                : "Your targets use the formula until NutriMe is sure of your burn. Then it asks you each week."}
            </p>
            <label className="toggle-row">
              <span>Update without asking</span>
              <input type="checkbox" checked={profile.autoAcceptBurn} onChange={(ev) => saveProfile({ ...profile, autoAcceptBurn: ev.target.checked })} />
            </label>
          </>
        )}
        {profile.burnMode === "FORMULA" && <p className="hint">Resting burn × activity: {Math.round(tdee({ ...profile, burnMode: "FORMULA" })).toLocaleString()} kcal.</p>}
        {profile.burnMode === "MANUAL" && (
          <label className="field">
            <span>Daily burn (kcal)</span>
            <input className="input" inputMode="numeric" value={manual} onChange={(ev) => {
              setManual(ev.target.value);
              const v = Number(ev.target.value);
              saveProfile({ ...profile, manualBurnKcal: Number.isInteger(v) && v >= 1000 && v <= 6000 ? v : null });
            }} />
          </label>
        )}
      </Card>
      <Card title="How it works">
        <p className="hint">Your weight changes by about 1 kg for each 7,700 kcal you eat above or below your burn. NutriMe compares what you log with how your trend weight moves, and from that learns your real burn.</p>
        <p className="hint">Days that look partly logged are not used. Mark a day complete in the diary when you logged everything, including a day you ate little.</p>
        <p className="hint">If you always log a little low, this value is also a little low. Your target is still correct, because it uses the same log.</p>
      </Card>
    </div>
  );
}
