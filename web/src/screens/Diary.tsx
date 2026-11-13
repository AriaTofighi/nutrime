import { Apple, Bookmark, CalendarArrowDown, ChartColumn, ChevronLeft, ChevronRight, CircleCheck, Copy, CopyPlus, EllipsisVertical, GripVertical, HeartPulse, NotebookPen, Pencil, Plus, Scale, Trash2, Utensils } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { AmountForm, FoodSearch, type PickedAmount } from "../components/FoodPicker";
import { WeightDialog, deleteWeight, restoreWeight } from "../components/WeightDialog";
import { Calendar, Card, Empty, MacroTiles, Modal, NutrientGrid, Ring, ScoreBar } from "../components/ui";
import { copyDay, copyEntry, entryNutrients, getFood, logFood, reorderEntries, updateEntry, type Food } from "../lib/foods";
import { N, NUTRIENTS, fmtNum, parseNutrients, sum } from "../lib/nutrients";
import { LB_PER_KG, calorieTargetOn, computedCalories, parseProfile, targets, tdee, type Profile } from "../lib/profile";
import { checkInDue, nextAccepted } from "../lib/burn";
import { reached } from "../lib/goal";
import { burnEstimate } from "../lib/trends";
import { dayRecord, logMeal, saveMeal, savedMeals, setComplete, setNote, type SavedMeal } from "../lib/days";
import { saveProfile } from "./Trends";
import { store, useStore } from "../lib/store";
import type { DiaryEntry, WeightRecord } from "../lib/types";
import { addDays, amountLabel, defaultMealForNow, fmtWeight, friendlyDate, isoDate, parseIso } from "../lib/util";
import { COLORS } from "../theme/theme";

type Dialog =
  | { kind: "add"; food?: Food; amount?: PickedAmount }
  | { kind: "edit"; entry: DiaryEntry; food: Food }
  | { kind: "weight"; existing?: WeightRecord }
  | { kind: "nutrition"; entry: DiaryEntry }
  | { kind: "copy"; entry: DiaryEntry }
  | { kind: "note" }
  | { kind: "saveMeal" }
  | { kind: "meals" }
  | null;

const WEEKLY_REVIEW =
  "Review my last 7 days: calories against my target, my trend weight and daily burn, my three weakest nutrients and where they come from, and one suggestion for next week.";

/** Weekly check-in: the learned burn moved; update the target or keep it. */
function CheckIn({ profile, burn, learned, sd, onDone }: { profile: Profile; burn: number; learned: number; sd: number; onDone: (msg: string, undo?: () => void) => void }) {
  const { value, limited } = nextAccepted(burn, learned);
  const next = { ...profile, burnMode: "ADAPTIVE" as const, adaptiveBurnKcal: value };
  const first = !profile.burnModeChosen;
  return (
    <Card>
      <div className="checkin">
        <strong>{first ? "NutriMe learned your daily burn" : "Weekly check-in"}</strong>
        <p className="hint">
          {first ? `From your log and weigh-ins, you burn about ${learned.toLocaleString()} ± ${sd} kcal a day. The formula says ${Math.round(burn).toLocaleString()}. ` : `Your daily burn is ${learned.toLocaleString()} kcal (was ${Math.round(burn).toLocaleString()}). `}
          {profile.calorieOverride != null ? "You use a custom calorie target, so it does not change." : `New target: ${computedCalories(next).toLocaleString()} kcal.`}
          {limited ? ` The change is limited to ${Math.abs(value - Math.round(burn))} kcal this week.` : ""}
        </p>
        <div className="actions">
          <button className="btn btn-primary btn-sm" onClick={() => {
            saveProfile({ ...next, burnModeChosen: true, adaptiveBurnDate: isoDate() });
            onDone("Target updated", () => saveProfile({ ...profile, adaptiveBurnDate: isoDate() }));
          }}>{first ? "Use it" : "Update target"}</button>
          <button className="btn btn-ghost btn-sm" onClick={() => saveProfile({ ...profile, adaptiveBurnDate: isoDate(), burnModeChosen: true, burnMode: profile.burnModeChosen ? profile.burnMode : "FORMULA" })}>Keep</button>
          <span className="spacer" />
          <a className="btn btn-ghost btn-sm" href="#/assistant" onClick={() => { try { sessionStorage.setItem("nutrime.assistantPrompt", WEEKLY_REVIEW); } catch { /* ignore */ } }}>Review my week</a>
        </div>
      </div>
    </Card>
  );
}

const fmtKcal = (n: number) => Math.round(n).toLocaleString();

export default function Diary() {
  useStore();
  const [date, setDate] = useState(() => {
    // Trends can ask the Diary to open on a given day.
    try {
      const d = sessionStorage.getItem("nutrime.openDate");
      sessionStorage.removeItem("nutrime.openDate");
      if (d) return d;
    } catch {
      /* storage blocked */
    }
    return isoDate();
  });
  const [dialog, setDialog] = useState<Dialog>(null);
  const [toast, setToast] = useState<string>();

  const profile = parseProfile(store.get("profile", "profile")?.json);
  const tgt = targets(profile, date);
  const day = dayRecord(date);
  const [undo, setUndo] = useState<(() => void) | undefined>();
  const [noteText, setNoteText] = useState("");
  const [mealName, setMealName] = useState("");
  const today = isoDate();
  const est = date === today ? burnEstimate(profile) : null;
  const showCheckIn = date === today && !profile.autoAcceptBurn && checkInDue(profile, est, today);
  // "Update without asking": apply the weekly check-in once, with an undo.
  useEffect(() => {
    if (date !== today || !profile.autoAcceptBurn || !est || !checkInDue(profile, est, today)) return;
    const { value } = nextAccepted(tdee(profile), est.burnKcal);
    const before = profile;
    saveProfile({ ...profile, burnMode: "ADAPTIVE", burnModeChosen: true, adaptiveBurnKcal: value, adaptiveBurnDate: today });
    flash("Daily burn updated from your log", () => saveProfile({ ...before, adaptiveBurnDate: today }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [est?.burnKcal, profile.autoAcceptBurn, date]);
  const goalHiddenKey = "nutrime.goalReachedHidden";
  const [goalHidden, setGoalHidden] = useState(() => { try { return localStorage.getItem(goalHiddenKey); } catch { return null; } });
  const showGoal = date === today && est != null && profile.goalWeightKg != null && Math.abs(profile.goalKgPerWeek) > 0.001 && reached(est.trendKg, profile.goalWeightKg) && goalHidden !== String(profile.goalWeightKg);
  const stored = store
    .all("diary")
    .filter((e) => e.date === date)
    .sort((a, b) => a.createdAt - b.createdAt);
  // Drag to reorder: the order shown while dragging, saved on drop.
  const [dragId, setDragId] = useState<string>();
  const [preview, setPreview] = useState<string[]>();
  const [menu, setMenu] = useState<string>();
  const entries = preview ? preview.map((id) => stored.find((e) => e.id === id)).filter((e): e is DiaryEntry => !!e) : stored;
  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(undefined);
    window.addEventListener("click", close);
    return () => window.removeEventListener("click", close);
  }, [menu]);
  const key = entries.map((e) => e.id + e.updatedAt).join();
  const perEntry = useMemo(() => new Map(entries.map((e) => [e.id, entryNutrients(e)])), [key]);
  const totals = sum(perEntry.values());
  const weight = store
    .all("weights")
    .filter((w) => w.date === date)
    .sort((a, b) => b.updatedAt - a.updatedAt)[0];
  const loggedDays = useMemo(() => new Set(store.all("diary").map((e) => e.date)), [store.version]);

  const kcalTarget = calorieTargetOn(profile, date);
  const eaten = totals[N.ENERGY] ?? 0;
  const remaining = kcalTarget - eaten;
  const p = totals[N.PROTEIN] ?? 0, c = totals[N.CARBS] ?? 0, f = totals[N.FAT] ?? 0, a = totals[N.ALCOHOL] ?? 0;
  const macroKcal = p * 4 + c * 4 + f * 9 + a * 7;
  const unit = profile.imperial ? "lb" : "kg";
  const [allNutrients, setAllNutrients] = useState(false);
  // Vitamins and minerals with a minimum target: the score on the Nutrients card.
  const skip: number[] = [N.ENERGY, N.PROTEIN, N.CARBS, N.FAT, N.WATER];
  const scored = NUTRIENTS.filter((d) => (tgt[d.id]?.min ?? 0) > 0 && !skip.includes(d.id));
  const scoreRatio = (id: number) => (totals[id] ?? 0) / tgt[id]!.min!;
  const scoreMet = scored.filter((d) => scoreRatio(d.id) >= 1).length;
  const lowest = scored
    .filter((d) => scoreRatio(d.id) < 0.9)
    .sort((x, y) => scoreRatio(x.id) - scoreRatio(y.id))
    .slice(0, 3)
    .map((d) => d.name.split(" (")[0].replace(/^./, (ch) => ch.toLowerCase()));

  async function openEdit(e: DiaryEntry) {
    const snapshot = parseNutrients(e.nutrientsJson);
    const food = (await getFood(e.foodId)) ?? { id: e.foodId, source: "USDA" as const, name: e.foodName, brand: e.brand, nutrients: snapshot, servings: [] };
    // Edit with the logged nutrient snapshot so numbers match what was recorded.
    setDialog({ kind: "edit", entry: e, food: { ...food, nutrients: snapshot } });
  }

  function flash(msg: string, undoFn?: () => void) {
    setToast(msg);
    setUndo(() => undoFn);
    window.setTimeout(() => { setToast(undefined); setUndo(undefined); }, undoFn ? 5000 : 2400);
  }

  const dateLabel = parseIso(date).toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" });

  return (
    <div className="page">
      <header className="page-head">
        <div className="date-nav">
          <button className="icon-btn" onClick={() => setDate(addDays(date, -1))} aria-label="Previous day">
            <ChevronLeft size={20} />
          </button>
          <label className="date-pick">
            <span className="page-title">{friendlyDate(date)}{day?.complete && <CircleCheck size={18} className="day-done" aria-label="Day complete" />}</span>
            <span className="page-sub">{dateLabel}</span>
            <input type="date" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} aria-label="Pick date" />
          </label>
          <button className="icon-btn" onClick={() => setDate(addDays(date, 1))} aria-label="Next day">
            <ChevronRight size={20} />
          </button>
          {date !== isoDate() && (
            <button className="btn btn-ghost btn-sm" onClick={() => setDate(isoDate())}>
              Today
            </button>
          )}
        </div>
        <div className="head-actions">
          <button className={`btn ${day?.complete ? "btn-secondary" : "btn-ghost"}`} onClick={() => setComplete(date, !day?.complete)} title="Mark the day complete when you logged everything. The learned daily burn uses only complete days.">
            <CircleCheck size={16} />
            {day?.complete ? "Complete" : "Mark complete"}
          </button>
          <button className="btn btn-ghost" onClick={() => { setNoteText(day?.note ?? ""); setDialog({ kind: "note" }); }} aria-label={day?.note ? "Edit note" : "Add note"}>
            <NotebookPen size={16} />
          </button>
          <button className="btn btn-ghost" onClick={() => setDialog({ kind: "meals" })} title="Add saved meal">
            <Bookmark size={16} />
          </button>
          <button className="btn btn-secondary" onClick={() => setDialog({ kind: "weight", existing: weight })}>
            <Scale size={16} />
            Weight
          </button>
          <button className="btn btn-primary" onClick={() => setDialog({ kind: "add" })}>
            <Plus size={17} />
            Add food
          </button>
        </div>
      </header>

      <div className="diary-layout">
        <div className="diary-main">
          {showCheckIn && est && (
            <CheckIn profile={profile} burn={tdee(profile)} learned={est.burnKcal} sd={est.burnSd} onDone={flash} />
          )}
          {showGoal && (
            <Card>
              <div className="checkin">
                <strong>You reached your goal weight</strong>
                <p className="hint">Change your plan to Maintain to keep it?</p>
                <div className="actions">
                  <button className="btn btn-primary btn-sm" onClick={() => saveProfile({ ...profile, goalKgPerWeek: 0 })}>Maintain</button>
                  <button className="btn btn-ghost btn-sm" onClick={() => { try { localStorage.setItem(goalHiddenKey, String(profile.goalWeightKg)); } catch { /* ignore */ } setGoalHidden(String(profile.goalWeightKg)); }}>Not now</button>
                </div>
              </div>
            </Card>
          )}
          {/* The day at a glance: calories left, and the three macros against their targets. */}
          <Card className="today-card">
            <div className="today">
              <Ring size={120} stroke={10} total={Math.max(kcalTarget, 1)} segments={[{ value: Math.min(eaten, kcalTarget), color: remaining < 0 ? "var(--warning)" : COLORS.accent }]}>
                <b className="num">{fmtKcal(Math.abs(remaining))}</b>
                <span className={remaining < 0 ? "warn-text" : undefined}>{remaining < 0 ? "kcal over" : "kcal left"}</span>
              </Ring>
              <div className="today-macros">
                <MacroTiles values={totals} targets={tgt} />
                <p className="hint num">
                  {fmtKcal(eaten)} eaten · target {kcalTarget.toLocaleString()}
                </p>
              </div>
            </div>
          </Card>

          {day?.note && (
            <Card>
              <button className="note-card card-button" onClick={() => { setNoteText(day.note ?? ""); setDialog({ kind: "note" }); }}>
                <NotebookPen size={16} aria-hidden /> <span>{day.note}</span>
              </button>
            </Card>
          )}

          <Card className="log-card" flush>
            <div className="log-head">
              <span>Food</span>
              <span className="hide-sm">Amount</span>
              <span className="num hide-sm">Protein</span>
              <span className="num hide-sm">Carbs</span>
              <span className="num hide-sm">Fat</span>
              <span className="num">kcal</span>
              <span />
            </div>
            {weight && (
              <div className="log-row log-row-bio" onClick={() => setDialog({ kind: "weight", existing: weight })} role="button" tabIndex={0}>
                <span className="log-name">
                  <HeartPulse size={17} className="log-icon bio" aria-hidden />
                  <span>
                    Weight
                    {weight.bodyFatPct != null && <small>{weight.bodyFatPct.toFixed(1)}% body fat</small>}
                  </span>
                </span>
                <span className="hide-sm" />
                <span className="hide-sm" />
                <span className="hide-sm" />
                <span className="hide-sm" />
                <span className="num log-kcal">
                  {fmtWeight(profile.imperial ? weight.weightKg * LB_PER_KG : weight.weightKg)} <span className="dim">{unit}</span>
                </span>
                <button
                  className="icon-btn sm row-action"
                  aria-label="Delete weight"
                  onClick={(e) => {
                    e.stopPropagation();
                    const rec = deleteWeight(weight.id);
                    if (rec) flash("Removed weight", () => restoreWeight(rec));
                  }}
                >
                  <Trash2 size={15} />
                </button>
              </div>
            )}
            {entries.map((e) => {
              const n = perEntry.get(e.id) ?? {};
              return (
                <div
                  key={e.id}
                  className={`log-row ${dragId === e.id ? "dragging" : ""}`}
                  onClick={() => openEdit(e)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(k) => k.key === "Enter" && openEdit(e)}
                  draggable
                  onDragStart={(ev) => {
                    ev.dataTransfer.effectAllowed = "move";
                    ev.dataTransfer.setData("text/plain", e.id);
                    setDragId(e.id);
                    setPreview(stored.map((x) => x.id));
                  }}
                  onDragOver={(ev) => {
                    if (!dragId || !preview) return;
                    ev.preventDefault();
                    if (dragId === e.id) return;
                    const from = preview.indexOf(dragId);
                    const to = preview.indexOf(e.id);
                    const next = [...preview];
                    next.splice(to, 0, next.splice(from, 1)[0]);
                    setPreview(next);
                  }}
                  onDrop={(ev) => ev.preventDefault()}
                  onDragEnd={() => {
                    if (preview) reorderEntries(entries);
                    setDragId(undefined);
                    setPreview(undefined);
                  }}
                >
                  <span className="log-name">
                    <GripVertical size={15} className="log-grip" aria-hidden />
                    <Apple size={17} className="log-icon" aria-hidden />
                    <span>
                      <span className="log-title" title={e.foodName}>
                        {e.foodName}
                      </span>
                      <small>
                        <span className="show-sm">
                          {amountLabel(e.quantity, e.servingLabel)}
                          {e.brand ? " · " : ""}
                        </span>
                        {e.brand}
                      </small>
                    </span>
                  </span>
                  <span className="log-amount hide-sm" title={amountLabel(e.quantity, e.servingLabel)}>
                    {amountLabel(e.quantity, e.servingLabel)}
                  </span>
                  <span className="num hide-sm">{fmtNum(n[N.PROTEIN] ?? 0)}</span>
                  <span className="num hide-sm">{fmtNum(n[N.CARBS] ?? 0)}</span>
                  <span className="num hide-sm">{fmtNum(n[N.FAT] ?? 0)}</span>
                  <span className="num log-kcal">{fmtKcal(n[N.ENERGY] ?? 0)}</span>
                  <span className="row-menu-wrap">
                    <button
                      className="icon-btn sm row-action"
                      aria-label={`More actions for ${e.foodName}`}
                      aria-expanded={menu === e.id}
                      onClick={(ev) => {
                        ev.stopPropagation();
                        setMenu(menu === e.id ? undefined : e.id);
                      }}
                    >
                      <EllipsisVertical size={15} />
                    </button>
                    {menu === e.id && (
                      <div className="row-menu" role="menu" onClick={(ev) => ev.stopPropagation()}>
                        <button role="menuitem" onClick={() => { setMenu(undefined); void openEdit(e); }}>
                          <Pencil size={15} /> Edit amount or meal
                        </button>
                        <button role="menuitem" onClick={() => { setMenu(undefined); setDialog({ kind: "nutrition", entry: e }); }}>
                          <ChartColumn size={15} /> Show nutrition
                        </button>
                        <button role="menuitem" onClick={() => { setMenu(undefined); copyEntry(e, date); flash(`Duplicated ${e.foodName}`); }}>
                          <CopyPlus size={15} /> Duplicate
                        </button>
                        {date !== isoDate() && (
                          <button role="menuitem" onClick={() => { setMenu(undefined); copyEntry(e, isoDate()); flash("Copied to today"); }}>
                            <CalendarArrowDown size={15} /> Copy to today
                          </button>
                        )}
                        <button role="menuitem" onClick={() => { setMenu(undefined); setDialog({ kind: "copy", entry: e }); }}>
                          <Copy size={15} /> Copy to another day…
                        </button>
                        <button role="menuitem" className="danger" onClick={() => { setMenu(undefined); store.remove("diary", e.id); flash(`Removed ${e.foodName}`); }}>
                          <Trash2 size={15} /> Delete
                        </button>
                      </div>
                    )}
                  </span>
                </div>
              );
            })}
            {entries.length === 0 ? (
              <Empty icon={<Apple size={26} strokeWidth={1.5} />} title="Nothing logged yet">
                <div className="empty-actions">
                  <button className="btn btn-primary btn-sm" onClick={() => setDialog({ kind: "add" })}>
                    <Plus size={15} /> Add food
                  </button>
                  <button
                    className="btn btn-secondary btn-sm"
                    onClick={() => {
                      const n = copyDay(addDays(date, -1), date);
                      flash(n ? `Copied ${n} item${n === 1 ? "" : "s"} from the previous day` : "Nothing logged the previous day");
                    }}
                  >
                    <Copy size={14} /> Copy previous day
                  </button>
                </div>
              </Empty>
            ) : (
              <div className="log-foot">
                <span>
                  Total{" "}
                  <button className="btn btn-ghost btn-sm" onClick={() => { setMealName(""); setDialog({ kind: "saveMeal" }); }}>
                    <Utensils size={14} /> Save as meal
                  </button>
                </span>
                <span className="hide-sm" />
                <span className="num hide-sm">{fmtNum(p)}</span>
                <span className="num hide-sm">{fmtNum(c)}</span>
                <span className="num hide-sm">{fmtNum(f)}</span>
                <span className="num">{fmtKcal(eaten)}</span>
                <span />
              </div>
            )}
          </Card>

          <Card
            title="Nutrients"
            action={
              <button className="btn btn-ghost btn-sm" onClick={() => setAllNutrients((v) => !v)}>
                {allNutrients ? "Hide" : "Show all"}
              </button>
            }
          >
            <ScoreBar met={scoreMet} total={scored.length} />
            <p className="calm-note">
              <strong>
                {scoreMet} of {scored.length} vitamin and mineral targets met.
              </strong>{" "}
              {lowest.length > 0 && `Lowest: ${lowest.join(", ")}.`}
            </p>
            {allNutrients && (
              <div style={{ marginTop: "var(--space-4)" }}>
                <NutrientGrid totals={totals} targets={tgt} />
              </div>
            )}
          </Card>
        </div>

        <aside className="diary-rail">
          <Card>
            <Calendar value={date} onChange={setDate} marked={loggedDays} />
          </Card>
          <Card title="Calorie sources">
            <div className="sources">
              {(
                [
                  ["Protein", p * 4, COLORS.protein],
                  ["Carbs", c * 4, COLORS.carbs],
                  ["Fat", f * 9, COLORS.fat],
                  ["Alcohol", a * 7, COLORS.alcohol],
                ] as const
              )
                .filter(([label, v]) => label !== "Alcohol" || v > 0)
                .map(([label, v, color]) => (
                  <div key={label} className="source">
                    <span className="meter-label">
                      <i className="dot" style={{ background: color }} />
                      {label}
                    </span>
                    <span className="num">{macroKcal ? Math.round((v / macroKcal) * 100) : 0}%</span>
                  </div>
                ))}
              <div className="stack-bar">
                {macroKcal > 0 &&
                  [
                    [p * 4, COLORS.protein],
                    [c * 4, COLORS.carbs],
                    [f * 9, COLORS.fat],
                    [a * 7, COLORS.alcohol],
                  ].map(([v, color], i) => <i key={i} style={{ width: `${((v as number) / macroKcal) * 100}%`, background: color as string }} />)}
              </div>
            </div>
          </Card>
        </aside>
      </div>

      <button className="fab" onClick={() => setDialog({ kind: "add" })} aria-label="Add food">
        <Plus size={22} />
        <span>Add food</span>
      </button>

      {dialog?.kind === "add" && (
        <Modal title={dialog.food ? dialog.food.name : "Add food"} subtitle={dialog.food ? undefined : friendlyDate(date)} onClose={() => setDialog(null)} wide>
          {dialog.food ? (
            <AmountForm
              food={dialog.food}
              initialServing={dialog.amount?.serving}
              initialQuantity={dialog.amount?.quantity}
              submitLabel="Add to diary"
              onBack={() => setDialog({ kind: "add" })}
              onSubmit={async (s, q) => {
                // Meals aren't shown on the web; record the time-of-day meal so the Android app groups sensibly.
                await logFood(date, defaultMealForNow(), dialog.food!, s, q);
                setDialog(null);
                flash(`Added ${dialog.food!.name}`);
              }}
            />
          ) : (
            <FoodSearch
              onPick={(f, amount) => setDialog({ kind: "add", food: f, amount })}
              meal={defaultMealForNow()}
              onQuickAdd={async (f, s, q) => {
                await logFood(date, defaultMealForNow(), f, s, q);
                flash(`Added ${f.name}`);
              }}
              onAddMeal={(m) => {
                const ids = logMeal(m, date, m.defaultMeal ?? defaultMealForNow());
                setDialog(null);
                flash(`Added ${m.name}`, () => ids.forEach((id) => store.remove("diary", id)));
              }}
            />
          )}
        </Modal>
      )}

      {dialog?.kind === "edit" && (
        <Modal title={dialog.food.name} subtitle="Edit entry" onClose={() => setDialog(null)} wide>
          <AmountForm
            food={dialog.food}
            initialServing={{ label: dialog.entry.servingLabel, grams: dialog.entry.servingGrams }}
            initialQuantity={dialog.entry.quantity}
            submitLabel="Save"
            onDelete={() => {
              store.remove("diary", dialog.entry.id);
              setDialog(null);
            }}
            onSubmit={(s, q) => {
              const current = store.get("diary", dialog.entry.id);
              if (current) updateEntry(current, current.meal, s, q);
              setDialog(null);
            }}
          />
        </Modal>
      )}

      {dialog?.kind === "nutrition" && (
        <Modal title={dialog.entry.foodName} subtitle={`${amountLabel(dialog.entry.quantity, dialog.entry.servingLabel)} · ${fmtKcal(entryNutrients(dialog.entry)[N.ENERGY] ?? 0)} kcal`} onClose={() => setDialog(null)} wide>
          <NutrientGrid totals={entryNutrients(dialog.entry)} targets={tgt} showEmpty={false} />
        </Modal>
      )}
      {dialog?.kind === "copy" && (
        <Modal title={`Copy ${dialog.entry.foodName}`} subtitle="Pick the day to log it on" onClose={() => setDialog(null)}>
          <Calendar
            value={date}
            marked={loggedDays}
            onChange={(day) => {
              copyEntry(dialog.entry, day);
              setDialog(null);
              flash(`Copied to ${friendlyDate(day)}`);
            }}
          />
        </Modal>
      )}
      {dialog?.kind === "note" && (
        <Modal title="Note for this day" onClose={() => setDialog(null)}>
          <textarea className="input" rows={4} value={noteText} maxLength={500} placeholder="Ate out, felt hungry, training day…" onChange={(e) => setNoteText(e.target.value)} style={{ width: "100%" }} />
          <div className="actions">
            {day?.note && <button className="btn btn-ghost" onClick={() => { setNote(date, null); setDialog(null); }}>Delete</button>}
            <span className="grow" />
            <button className="btn btn-ghost" onClick={() => setDialog(null)}>Cancel</button>
            <button className="btn btn-primary" onClick={() => { setNote(date, noteText); setDialog(null); }}>Save</button>
          </div>
        </Modal>
      )}
      {dialog?.kind === "saveMeal" && (
        <Modal title="Save as meal" subtitle={`${entries.length} foods · ${fmtKcal(eaten)} kcal`} onClose={() => setDialog(null)}>
          <label className="field">
            <span>Name</span>
            <input className="input" value={mealName} maxLength={80} placeholder="Usual breakfast" onChange={(e) => setMealName(e.target.value)} autoFocus />
          </label>
          <p className="hint">{entries.map((e) => e.foodName).join(", ")}</p>
          <div className="actions">
            <span className="grow" />
            <button className="btn btn-ghost" onClick={() => setDialog(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={!mealName.trim()} onClick={() => {
              const meals = new Set(entries.map((e) => e.meal));
              saveMeal(mealName, entries, meals.size === 1 ? [...meals][0] : undefined);
              setDialog(null);
              flash(`Saved “${mealName.trim()}”`);
            }}>Save</button>
          </div>
        </Modal>
      )}
      {dialog?.kind === "meals" && (
        <Modal title="Saved meals" subtitle={friendlyDate(date)} onClose={() => setDialog(null)}>
          <SavedMealList onPick={(m) => {
            const ids = logMeal(m, date, m.defaultMeal ?? defaultMealForNow());
            setDialog(null);
            flash(`Added ${m.name}`, () => ids.forEach((id) => store.remove("diary", id)));
          }} />
        </Modal>
      )}
      {dialog?.kind === "weight" && <WeightDialog imperial={profile.imperial} date={date} existing={dialog.existing} onClose={() => setDialog(null)} onDeleted={(rec) => flash("Removed weight", () => restoreWeight(rec))} />}

      {toast && (
        <div className="toast">
          {toast}
          {undo && <button className="btn btn-ghost btn-sm" onClick={() => { undo(); setToast(undefined); setUndo(undefined); }}>Undo</button>}
        </div>
      )}
    </div>
  );
}

export function SavedMealList({ onPick }: { onPick: (m: SavedMeal) => void }) {
  const meals = savedMeals();
  if (!meals.length) return <p className="hint">No saved meals yet. Below a day's foods, choose “Save as meal” to keep foods you eat together.</p>;
  return (
    <ul className="list">
      {meals.map((m) => (
        <li key={m.id}>
          <button className="list-row" onClick={() => onPick(m)}>
            <span>
              {m.name}
              <span className="dim"> · {m.items.map((i) => i.name).join(", ").slice(0, 70)}</span>
            </span>
            <span className="num">{fmtKcal(m.kcal)} kcal</span>
          </button>
        </li>
      ))}
    </ul>
  );
}
