import { Activity as ActivityIcon, ChartPie, Check, ChevronRight, CircleUserRound, Database, Download, Info, ListChecks, LogOut, Monitor, Moon, Palette, RefreshCw, Ruler, Sun, Target as TargetIcon, Upload } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { Hero, Modal, Segmented } from "../components/ui";
import { GROUPS, N, NUTRIENTS, fmtNum } from "../lib/nutrients";
import {
  ACTIVITIES,
  CM_PER_IN,
  LB_PER_KG,
  bmr,
  computedCalories,
  encodeProfile,
  formulaTdee,
  goalKcalPerDay,
  macroGrams,
  parseProfile,
  targets,
  tdee,
  type Activity,
  type Profile as P,
  type Sex,
} from "../lib/profile";
import { store, useStore } from "../lib/store";
import { MAX_GAIN, MAX_LOSS, WARNING_TEXT, editSchedule, higherWeekends, projectedDate, reached, warnings, weeksToGoal } from "../lib/goal";
import { burnSource } from "../lib/trends";
import { ExportImport } from "./DataTools";
import { isoDate, timeAgo, toNum } from "../lib/util";
import { ACCENTS, COLORS, setTheme, useTheme, type ThemeMode } from "../theme/theme";

const PRESETS: [number, number, number, string][] = [
  [25, 45, 30, "Balanced"],
  [35, 35, 30, "High protein"],
  [30, 20, 50, "Low carb"],
  [20, 5, 75, "Keto"],
];

/** A text field bound to a number, so partial input like "1." isn't clobbered while typing. */
function NumField({ label, value, onChange, suffix, digits = 1 }: { label: string; value: number | undefined; onChange: (v: number | undefined) => void; suffix?: string; digits?: number }) {
  const scale = 10 ** digits;
  const show = (v: number | undefined) => (v == null ? "" : String(Math.round(v * scale) / scale));
  const [text, setText] = useState(show(value));
  useEffect(() => {
    const t = toNum(text);
    if (!(value != null && t != null && Math.abs(t - value) < 0.5 / scale) && !(value == null && t == null)) setText(show(value));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);
  return (
    <label className="field">
      <span>{label}</span>
      <div className={suffix ? "input-affix" : undefined}>
        <input
          inputMode="decimal"
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            onChange(toNum(e.target.value));
          }}
        />
        {suffix && <em>{suffix}</em>}
      </div>
    </label>
  );
}

type Section = "goal" | "macros" | "targets" | "body" | "appearance" | "account" | "data";
const WEEKDAY_SHORT = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

/** One row of a settings list: icon, name, the current value, and a chevron. Opens the section's editor. */
function SettingsRow({ icon, label, value, onClick, href }: { icon: ReactNode; label: string; value?: ReactNode; onClick?: () => void; href?: string }) {
  const inner = (
    <>
      <span className="settings-icon">{icon}</span>
      <span className="settings-label">{label}</span>
      <span className="settings-value">{value}</span>
      <ChevronRight size={16} className="settings-chevron" />
    </>
  );
  return href ? (
    <a className="settings-row" href={href}>
      {inner}
    </a>
  ) : (
    <button className="settings-row" onClick={onClick}>
      {inner}
    </button>
  );
}

const calorieTargetNow = (p: P) => p.calorieOverride ?? computedCalories(p);

function goalLabel(kg: number, imperial: boolean) {
  if (Math.abs(kg) < 0.001) return "Maintain weight";
  const amount = imperial ? `${+(Math.abs(kg) * LB_PER_KG).toFixed(1)} lb` : `${+Math.abs(kg).toFixed(2)} kg`;
  return `${kg < 0 ? "Lose" : "Gain"} ${amount} / week`;
}

export default function Profile({ admin = false }: { admin?: boolean }) {
  useStore();
  const theme = useTheme();
  const rec = store.get("profile", "profile");
  const saved = parseProfile(rec?.json);
  const [draft, setDraft] = useState<P>(saved);
  const [section, setSection] = useState<Section | null>(null);
  const [about, setAbout] = useState(false);
  const [confirmOut, setConfirmOut] = useState(false);
  const [touched, setTouched] = useState<Set<number>>(new Set());

  // While no editor is open, follow the saved profile (it may change on another device).
  useEffect(() => {
    if (!section) setDraft(parseProfile(store.get("profile", "profile")?.json));
  }, [rec?.updatedAt, section]);

  const p = draft;
  const update = (patch: Partial<P>) => setDraft({ ...p, ...patch });
  const open = (s: Section) => {
    setDraft(saved);
    setSection(s);
  };
  const close = () => setSection(null);
  const save = () => {
    store.put("profile", { id: "profile", json: encodeProfile({ ...p, configured: true }), updatedAt: Date.now(), deleted: false });
    setSection(null);
  };

  const imp = p.imperial;
  const unit = imp ? "lb" : "kg";
  const cv = (kg: number) => (imp ? kg * LB_PER_KG : kg);
  const dir = p.goalKgPerWeek < -0.001 ? "lose" : p.goalKgPerWeek > 0.001 ? "gain" : "maintain";
  const rate = Math.abs(p.goalKgPerWeek);
  const rateStep = imp ? 0.1 / LB_PER_KG : 0.05;
  const macroSum = p.proteinMode === "PER_KG" ? 100 : p.proteinPct + p.carbsPct + p.fatPct;
  const mg = macroGrams(p);
  const mgKcal = calorieTargetNow(p);
  const status = store.status;
  const overrideCount = Object.keys(saved.targetOverrides).length;
  const kcal = saved.calorieOverride ?? computedCalories(saved);
  const t = targets(saved);
  const footer = (valid = true) => (
    <div className="actions">
      <span className="grow" />
      <button className="btn btn-ghost" onClick={close}>
        Cancel
      </button>
      <button className="btn btn-primary" onClick={save} disabled={!valid}>
        Save
      </button>
    </div>
  );

  return (
    <div className="page page-narrow">
      <header className="page-head">
        <div>
          <h1 className="page-title">Profile</h1>
          <p className="page-sub">Your plan and settings</p>
        </div>
      </header>

      {/* Who you are: the account and its sync state. */}
      <section className="card card-flush settings-list">
        <SettingsRow
          icon={<CircleUserRound size={20} />}
          label={store.account?.email ?? "Sign in to sync"}
          value={
            status.error ? (
              <span className="danger">Sync problem</span>
            ) : store.account ? (
              `Synced ${timeAgo(status.lastSync)}`
            ) : undefined
          }
          onClick={() => setSection("account")}
        />
      </section>

      {/* The plan at a glance: the daily target, the goal, and the macro split. Each shows once. */}
      <button className="card plan-card card-button" onClick={() => open("goal")}>
        <Hero label="Daily target" value={kcal.toLocaleString()} unit="kcal" status={goalLabel(saved.goalKgPerWeek, saved.imperial)} tone="good" />
        <div className="macro-bar" aria-hidden>
          <i style={{ flex: (t[N.PROTEIN]?.min ?? 0) * 4, background: COLORS.protein }} />
          <i style={{ flex: (t[N.CARBS]?.min ?? 0) * 4, background: COLORS.carbs }} />
          <i style={{ flex: (t[N.FAT]?.min ?? 0) * 9, background: COLORS.fat }} />
        </div>
        <div className="plan-legend num">
          <span>
            <b>{Math.round(t[N.PROTEIN]?.min ?? 0)} g</b> protein
          </span>
          <span>
            <b>{Math.round(t[N.CARBS]?.min ?? 0)} g</b> carbs
          </span>
          <span>
            <b>{Math.round(t[N.FAT]?.min ?? 0)} g</b> fat
          </span>
        </div>
      </button>

      <div>
        <div className="settings-head">Plan</div>
        <section className="card card-flush settings-list">
          <SettingsRow icon={<TargetIcon size={17} />} label="Goal & calories" onClick={() => open("goal")} />
          <SettingsRow
            icon={<ChartPie size={17} />}
            label="Macros"
            value={saved.proteinMode !== "PER_KG" && saved.proteinPct + saved.carbsPct + saved.fatPct !== 100 ? <span className="danger">Adds up to {saved.proteinPct + saved.carbsPct + saved.fatPct}%</span> : undefined}
            onClick={() => open("macros")}
          />
          <SettingsRow icon={<ListChecks size={17} />} label="Nutrient targets" value={overrideCount ? `${overrideCount} custom` : undefined} onClick={() => open("targets")} />
          <SettingsRow icon={<Ruler size={17} />} label="Body & activity" onClick={() => open("body")} />
        </section>
      </div>

      <div>
        <div className="settings-head">App</div>
        <section className="card card-flush settings-list">
          <SettingsRow icon={<Palette size={17} />} label="Appearance" onClick={() => setSection("appearance")} />
          <SettingsRow icon={<Download size={17} />} label="Export and import" value={<><Upload size={13} /> Cronometer, MyFitnessPal</>} onClick={() => setSection("data")} />
          <SettingsRow icon={<Info size={17} />} label="About & data sources" onClick={() => setAbout(true)} />
          {admin && <SettingsRow icon={<Database size={17} />} label="Food database" value="Admin" href="#/database" />}
          {admin && <SettingsRow icon={<ActivityIcon size={17} />} label="Assistant traces" value="Admin" href="#/traces" />}
          {admin && <SettingsRow icon={<CircleUserRound size={17} />} label="Invitations" value="Admin" href="#/invitations" />}
        </section>
      </div>

      {about && (
        <Modal title="About NutriMe" onClose={() => setAbout(false)}>
          <div className="form">
            <p className="hint">
              <b>Targets.</b> Calorie targets use the Mifflin-St Jeor equation and your activity level. Vitamin and mineral targets use the Dietary Reference Intakes for your age and sex. They are general guidance, not
              medical advice.
            </p>
            <p className="hint">
              <b>Food data.</b> USDA FoodData Central (SR Legacy, Foundation and FNDDS), Open Food Facts (Open Database License), and foods that NutriMe checked from package labels.
            </p>
          </div>
        </Modal>
      )}

      {section === "goal" && (
        <Modal title="Goal & calories" subtitle={`Daily target: ${(p.calorieOverride ?? computedCalories(p)).toLocaleString()} kcal`} onClose={close}>
          <div className="form">
            <div className="sheet-lines">
              {p.burnMode === "FORMULA" || tdee(p) === formulaTdee(p) ? (
                <>
                  <div className="sheet-line"><span>Resting burn</span><span className="num">{Math.round(bmr(p)).toLocaleString()} kcal</span></div>
                  <div className="sheet-line"><span>Activity</span><span className="num">+ {Math.round(formulaTdee(p) - bmr(p)).toLocaleString()} kcal</span></div>
                </>
              ) : null}
              <a className="sheet-line strong card-button" href="#/trends" onClick={() => { try { sessionStorage.setItem("nutrime.trendsView", "burn"); } catch { /* ignore */ } close(); }}>
                <span>Daily burn <span className="dim">· {burnSource(p)}</span></span><span className="num">{Math.round(tdee(p)).toLocaleString()} kcal</span>
              </a>
              <div className="sheet-line"><span>{goalLabel(p.goalKgPerWeek, imp)}</span><span className="num">{Math.abs(goalKcalPerDay(p)) < 1 ? "± 0" : `${goalKcalPerDay(p) < 0 ? "−" : "+"} ${Math.round(Math.abs(goalKcalPerDay(p))).toLocaleString()}`} kcal</span></div>
            </div>
            {warnings(p).map((w) => <p key={w} className={w === "AT_FLOOR" ? "hint" : "warn-note"}>{WARNING_TEXT[w]}</p>)}
            <div className="field">
              <span>Goal</span>
              <Segmented
                options={[{ value: "lose", label: "Lose" }, { value: "maintain", label: "Maintain" }, { value: "gain", label: "Gain" }]}
                value={dir}
                onChange={(d) => {
                  const r = rate > 0.001 ? rate : 0.25;
                  update({ goalKgPerWeek: d === "lose" ? -Math.min(r, MAX_LOSS) : d === "gain" ? Math.min(r, MAX_GAIN) : 0 });
                }}
              />
            </div>
            <NumField
              label="Goal weight (optional)" suffix={unit}
              value={p.goalWeightKg != null ? cv(p.goalWeightKg) : undefined}
              onChange={(v) => update({ goalWeightKg: v != null && v >= 20 && v <= 700 ? (imp ? v / LB_PER_KG : v) : null })}
            />
            {dir !== "maintain" && (
              <label className="field">
                <span>Rate: {+cv(rate).toFixed(2)} {unit} a week</span>
                <input
                  type="range" min={rateStep} max={dir === "lose" ? MAX_LOSS : MAX_GAIN} step={rateStep} value={rate}
                  onChange={(e) => {
                    const v = Math.round(Number(e.target.value) / rateStep) * rateStep;
                    update({ goalKgPerWeek: dir === "lose" ? -v : v });
                  }}
                />
                <span className="hint">{((rate / p.weightKg) * 100).toFixed(1)}% of your body weight a week · {Math.round((rate * 7700) / 7).toLocaleString()} kcal a day</span>
              </label>
            )}
            {p.goalWeightKg != null && (
              <p className="hint">
                {reached(p.weightKg, p.goalWeightKg)
                  ? "You are at your goal weight."
                  : (() => {
                      const w = weeksToGoal(p.weightKg, p.goalWeightKg!, p.goalKgPerWeek);
                      const d = projectedDate(isoDate(), p.weightKg, p.goalWeightKg!, p.goalKgPerWeek);
                      return w != null && d ? `About ${Math.ceil(w)} weeks · around ${new Date(d + "T12:00:00").toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" })}` : dir === "maintain" ? "Choose Lose or Gain to see a date." : null;
                    })()}
              </p>
            )}
            <NumField
              label="Custom calorie target (leave blank to use the suggestion)"
              suffix="kcal"
              value={p.calorieOverride ?? undefined}
              onChange={(v) => update({ calorieOverride: v && v >= 800 && v <= 10000 ? Math.round(v) : undefined })}
            />
            <div className="field">
              <span>Calorie schedule</span>
              <Segmented
                options={[{ value: "same", label: "Same every day" }, { value: "weekend", label: "Higher weekends" }]}
                value={p.calorieSchedule?.length === 7 ? "weekend" : "same"}
                onChange={(v) => { setTouched(new Set()); update({ calorieSchedule: v === "weekend" ? higherWeekends(250) : null }); }}
              />
              <span className="hint">Eat more on some days and less on others. The week keeps the same total.</span>
            </div>
            {p.calorieSchedule?.length === 7 && (
              <div className="schedule-grid">
                {WEEKDAY_SHORT.map((d, i) => (
                  <NumField
                    key={d} label={`${d} · ${((p.calorieOverride ?? computedCalories(p)) + p.calorieSchedule![i]).toLocaleString()}`} suffix="kcal"
                    value={p.calorieSchedule![i]}
                    onChange={(v) => {
                      if (v == null || Math.abs(v) > 1500 || Math.round(v) === p.calorieSchedule![i]) return;
                      const t2 = new Set(touched).add(i);
                      setTouched(t2);
                      update({ calorieSchedule: editSchedule(p.calorieSchedule, i, Math.round(v), t2) });
                    }}
                  />
                ))}
              </div>
            )}
          </div>
          {footer()}
        </Modal>
      )}

      {section === "macros" && (
        <Modal title="Macros" subtitle="How your daily calories are split" onClose={close}>
          <div className="form">
            <Segmented options={[{ value: "PERCENT", label: "Percent" }, { value: "PER_KG", label: "Protein per kg" }]} value={p.proteinMode} onChange={(m) => update({ proteinMode: m as P["proteinMode"] })} />
            {p.proteinMode === "PERCENT" ? (
              <>
                <div className="chips">
                  {PRESETS.map(([pr, c, f, label]) => (
                    <button key={label} className={`chip ${p.proteinPct === pr && p.carbsPct === c && p.fatPct === f ? "on" : ""}`} onClick={() => update({ proteinPct: pr, carbsPct: c, fatPct: f })}>
                      {label}
                    </button>
                  ))}
                </div>
                <div className="row3">
                  <NumField label="Protein" suffix="%" value={p.proteinPct} onChange={(v) => v != null && update({ proteinPct: Math.round(v) })} />
                  <NumField label="Carbs" suffix="%" value={p.carbsPct} onChange={(v) => v != null && update({ carbsPct: Math.round(v) })} />
                  <NumField label="Fat" suffix="%" value={p.fatPct} onChange={(v) => v != null && update({ fatPct: Math.round(v) })} />
                </div>
                {macroSum !== 100 ? (
                  <p className="hint danger">These add up to {macroSum}%. They should total 100%.</p>
                ) : (
                  <p className="hint">{Math.round(mg.protein)} g protein · {Math.round(mg.carbs)} g carbs · {Math.round(mg.fat)} g fat per day</p>
                )}
              </>
            ) : (
              <>
                <div className="row3">
                  <NumField
                    label="Protein" suffix={imp ? "g/lb" : "g/kg"} value={imp ? p.proteinGPerKg / LB_PER_KG : p.proteinGPerKg}
                    onChange={(v) => { const g = v != null ? (imp ? v * LB_PER_KG : v) : null; if (g != null && g >= 0.5 && g <= 3.5) update({ proteinGPerKg: g }); }}
                  />
                  <NumField label="Fat" suffix="%" value={p.fatPct} onChange={(v) => v != null && v >= 5 && v <= 90 && update({ fatPct: Math.round(v) })} />
                </div>
                {mg.protein * 4 + mg.fat * 9 > mgKcal ? (
                  <p className="hint danger">Protein and fat use more than your calories. Lower one of them.</p>
                ) : (
                  <p className="hint">
                    {Math.round(mg.protein)} g protein ({Math.round((mg.protein * 400) / mgKcal)}%) · {Math.round(mg.fat)} g fat ({Math.round((mg.fat * 900) / mgKcal)}%) · {Math.round(mg.carbs)} g carbs, the rest ({Math.round((mg.carbs * 400) / mgKcal)}%)
                  </p>
                )}
                <p className="hint">Common ranges: 1.2–1.6 g/kg for health, 1.6–2.2 g/kg to keep or build muscle while you lose weight.</p>
              </>
            )}
          </div>
          {footer(macroSum === 100)}
        </Modal>
      )}

      {section === "data" && (
        <Modal title="Export and import" onClose={close} wide>
          <ExportImport />
        </Modal>
      )}

      {section === "body" && (
        <Modal title="Body & activity" subtitle="Your calorie and nutrient targets are calculated from these" onClose={close}>
          <div className="form">
            <Segmented<Sex> options={[{ value: "MALE", label: "Male" }, { value: "FEMALE", label: "Female" }]} value={p.sex} onChange={(sex) => update({ sex })} />
            <div className="row3">
              <NumField label="Birth year" value={p.birthYear} onChange={(v) => v && v > 1900 && v <= new Date().getFullYear() && update({ birthYear: Math.round(v) })} />
              {imp ? (
                <NumField label="Height" suffix="in" value={p.heightCm / CM_PER_IN} onChange={(v) => v && v > 0 && update({ heightCm: v * CM_PER_IN })} />
              ) : (
                <NumField label="Height" suffix="cm" value={p.heightCm} onChange={(v) => v && v > 0 && update({ heightCm: v })} />
              )}
              <NumField
                label="Weight"
                suffix={imp ? "lb" : "kg"}
                digits={2}
                value={imp ? p.weightKg * LB_PER_KG : p.weightKg}
                onChange={(v) => v && v > 0 && update({ weightKg: imp ? v / LB_PER_KG : v })}
              />
            </div>
            <label className="field">
              <span>Activity level</span>
              <select value={p.activity} onChange={(e) => update({ activity: e.target.value as Activity })}>
                {ACTIVITIES.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.label} — {a.description}
                  </option>
                ))}
              </select>
            </label>
            <div className="field">
              <span>Units</span>
              <Segmented options={[{ value: "metric", label: "Metric (kg, cm)" }, { value: "imperial", label: "Imperial (lb, in)" }]} value={imp ? "imperial" : "metric"} onChange={(v) => update({ imperial: v === "imperial" })} />
            </div>
          </div>
          {footer()}
        </Modal>
      )}

      {section === "targets" && (
        <Modal title="Nutrient targets" subtitle="Based on Dietary Reference Intakes for your age and sex. Leave a field blank to use the recommended amount." onClose={close} wide>
          <div className="form">
            {GROUPS.map((g) => (
              <div key={g} className="form">
                <div className="eyebrow">{g}</div>
                <div className="nutrient-inputs">
                  {NUTRIENTS.filter((n) => n.group === g).map((n) => {
                    const base = targets({ ...p, targetOverrides: {} })[n.id];
                    return (
                      <NumField
                        key={n.id}
                        label={`${n.name}${base?.min != null ? ` · rec. ${fmtNum(base.min)}` : ""}`}
                        suffix={n.unit}
                        value={p.targetOverrides[String(n.id)]}
                        onChange={(v) => {
                          const o = { ...p.targetOverrides };
                          if (v != null && v > 0) o[String(n.id)] = v;
                          else delete o[String(n.id)];
                          update({ targetOverrides: o });
                        }}
                      />
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
          {footer()}
        </Modal>
      )}

      {section === "appearance" && (
        <Modal title="Appearance" onClose={close}>
          <div className="form">
            <div className="field">
              <span>Theme</span>
              <Segmented<ThemeMode>
                options={[
                  { value: "light", label: <><Sun size={14} /> Light</> },
                  { value: "dark", label: <><Moon size={14} /> Dark</> },
                  { value: "system", label: <><Monitor size={14} /> System</> },
                ]}
                value={theme.mode}
                onChange={(mode) => setTheme({ mode })}
              />
            </div>
            <div className="field">
              <span>Accent</span>
              <div className="swatches">
                {ACCENTS.map((a) => (
                  <button key={a.id} className={`swatch ${theme.accent === a.id ? "on" : ""}`} style={{ background: a.swatch }} onClick={() => setTheme({ accent: a.id })} aria-label={a.label} title={a.label}>
                    {theme.accent === a.id && <Check size={15} />}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </Modal>
      )}

      {section === "account" && (
        <Modal title="Account & sync" onClose={close}>
          <div className="account-row">
            <CircleUserRound size={34} strokeWidth={1.4} className="account-icon" aria-hidden />
            <div className="grow">
              <div className="strong">{store.account?.email}</div>
              <div className="hint">
                {status.running ? "Syncing…" : `Last synced ${timeAgo(status.lastSync)}`}
                {status.pending ? ` · ${status.pending} change${status.pending === 1 ? "" : "s"} waiting to upload` : ""}
              </div>
            </div>
          </div>
          {status.error && <p className="hint danger">{status.error}</p>}
          <p className="hint">Your diary, foods, weights and profile sync with the NutriMe Android app when you sign in there with the same account.</p>
          <div className="actions start">
            <button className="btn btn-secondary" onClick={() => void store.sync()} disabled={status.running}>
              <RefreshCw size={15} className={status.running ? "spin" : ""} /> Sync now
            </button>
            <button className="btn btn-ghost" onClick={() => setConfirmOut(true)}>
              <LogOut size={15} /> Sign out
            </button>
          </div>
        </Modal>
      )}

      {confirmOut && (
        <Modal title="Sign out?" onClose={() => setConfirmOut(false)}>
          <p className="hint">
            {status.pending ? `${status.pending} change(s) haven't been uploaded yet and will be lost. ` : ""}Your data stays safe on the server.
          </p>
          <div className="actions">
            <span className="grow" />
            <button className="btn btn-ghost" onClick={() => setConfirmOut(false)}>
              Cancel
            </button>
            <button className="btn btn-danger" onClick={() => void store.signOut()}>
              Sign out
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

