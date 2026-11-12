import { ArrowLeft, Bookmark, Database, Globe, History, LoaderCircle, Plus, RefreshCw, ScanBarcode, Search, Star, Trash2 } from "lucide-react";
import { RecipeIcon } from "./icons";
import { useEffect, useMemo, useRef, useState } from "react";
import {
  allServings,
  byBarcode,
  defaultQuantity,
  defaultServing,
  isLabelOnly,
  recordToFood,
  searchBundledDetailed,
  searchMine,
  type BundledResults,
  searchOnline,
  type Food,
} from "../lib/foods";
import { N, fmtNum, forGrams, parseNutrients } from "../lib/nutrients";
import { store, useStore } from "../lib/store";
import type { DiaryEntry, Serving } from "../lib/types";
import { fmtQty, isoDate, toNum } from "../lib/util";
import { encodeProfile, parseProfile } from "../lib/profile";
import { amountLabel, foodUsage, parseQueryAmount, rank, servingFor, textScore, usualFor, words } from "../lib/searchRank";
import { savedMeals, type SavedMeal } from "../lib/days";
import { COLORS } from "../theme/theme";
import { Empty, NutrientGrid, Ring } from "./ui";

const SOURCE_TAG: Record<string, string> = {
  USDA: "USDA",
  NutriMe: "NutriMe",
  "USDA Branded": "Branded",
  "Open Food Facts": "OFF",
  Custom: "Custom",
  Recipe: "Recipe",
};

export function FoodRow({ food, onPick, usual, favorite }: { food: Food; onPick: (f: Food) => void; usual?: number; favorite?: boolean }) {
  const s = defaultServing(food);
  const q = defaultQuantity(food);
  const kcal = ((food.nutrients[N.ENERGY] ?? 0) * s.grams * q) / 100;
  return (
    <button className="food-row" onClick={() => onPick(food)}>
      <span className="food-main">
        <span className="food-name">{food.name}</span>
        <span className="food-meta">
          <span className={`tag tag-${SOURCE_TAG[food.source]?.toLowerCase()}`}>{SOURCE_TAG[food.source] ?? food.source}</span>
          {food.brand && <span>{food.brand}</span>}
          <span>
            {fmtQty(q)} {s.label}
          </span>
          {favorite && <span title="Favourite">★</span>}
          {usual != null && usual > 0 && <span className="dim">logged {usual}×</span>}
          {isLabelOnly(food) && (
            <span className="dim" title="Only the values on the package label: vitamins and minerals are missing">
              label only
            </span>
          )}
        </span>
      </span>
      <span className="food-kcal num">
        {Math.round(kcal)}
        <small>kcal</small>
      </span>
    </button>
  );
}

function Section({ icon, title, children }: { icon: React.ReactNode; title: string; children: React.ReactNode }) {
  return (
    <div className="list-section">
      <div className="list-head">
        {icon}
        {title}
      </div>
      {children}
    </div>
  );
}

export interface PickedAmount {
  serving: Serving;
  quantity: number;
}

type Filter = "all" | "mine" | "favorites" | "complete";
const FILTERS: [Filter, string][] = [["all", "All"], ["mine", "My foods"], ["favorites", "Favourites"], ["complete", "Complete data"]];
const MEAL_NAMES = ["breakfast", "lunch", "dinner", "snacks"];

/** A food from its newest diary entry (when it isn't in the catalog any more). */
function entryFood(e: DiaryEntry): Food {
  const rec = store.get("foods", e.foodId);
  return rec
    ? recordToFood(rec)
    : { id: e.foodId, source: e.foodId.startsWith("off:") ? "Open Food Facts" : "USDA", name: e.foodName, brand: e.brand, nutrients: parseNutrients(e.nutrientsJson), servings: [{ label: e.servingLabel, grams: e.servingGrams }] };
}

/** The usual amount: the newest entry of this food. */
export function usualAmount(foodId: string): PickedAmount | undefined {
  let last: DiaryEntry | undefined;
  for (const e of store.all("diary")) if (e.foodId === foodId && (!last || e.createdAt > last.createdAt)) last = e;
  return last ? { serving: { label: last.servingLabel, grams: last.servingGrams }, quantity: last.quantity } : undefined;
}

/**
 * One ranked list of your foods, the bundled database and online foods. Online search starts by
 * itself when the offline results are weak. Before typing: usual foods for this meal, saved meals,
 * favourites and recent foods.
 */
export function FoodSearch({
  onPick,
  meal,
  onQuickAdd,
  onAddMeal,
}: {
  onPick: (f: Food, amount?: PickedAmount) => void;
  meal?: number;
  onQuickAdd?: (f: Food, serving: Serving, quantity: number) => void;
  onAddMeal?: (m: SavedMeal) => void;
}) {
  const { version } = useStore();
  const [query, setQuery] = useState("");
  const [local, setLocal] = useState<BundledResults>({ foods: [], corrected: new Set() });
  const [online, setOnline] = useState<{ foods: Food[]; errors: string[]; q: string } | null>(null);
  const [loading, setLoading] = useState(false);
  const [onlineLoading, setOnlineLoading] = useState(false);
  const [dbError, setDbError] = useState<string>();
  const [barcodeMode, setBarcodeMode] = useState(false);
  const [barcodeMsg, setBarcodeMsg] = useState<string>();
  const [filter, setFilter] = useState<Filter>("all");
  const [added, setAdded] = useState(0);
  const seq = useRef(0);
  const amount = barcodeMode ? null : parseQueryAmount(query);
  const q = (amount?.query ?? query).trim();
  const profile = parseProfile(store.get("profile", "profile")?.json);
  const favorites = useMemo(() => new Set(profile.favoriteFoodIds), [version]);
  const usage = useMemo(() => foodUsage(store.all("diary"), isoDate()), [version]);

  useEffect(() => {
    setOnline(null);
    if (!q || barcodeMode) return setLocal({ foods: [], corrected: new Set() });
    const n = ++seq.current;
    setLoading(true);
    const t = window.setTimeout(async () => {
      try {
        const bundled = await searchBundledDetailed(q);
        if (n !== seq.current) return;
        const mine = searchMine(q);
        const mineFixed = bundled.correctedQuery ? searchMine(bundled.correctedQuery).filter((f) => !mine.some((m) => m.id === f.id)) : [];
        mineFixed.forEach((f) => bundled.corrected.add(f.id));
        const all = { ...bundled, foods: [...mine, ...bundled.foods, ...mineFixed].filter((f, i, a) => a.findIndex((x) => x.id === f.id) === i) };
        setLocal(all);
        setDbError(undefined);
        // Weak offline results: search online after a short pause, without a button press.
        const qw = words(q);
        const strong = all.foods.filter((f) => textScore(qw, f, q) >= 1).length;
        if (q.length >= 3 && strong < 5) {
          window.setTimeout(() => {
            if (n === seq.current) void goOnline(q, n);
          }, 500);
        }
      } catch (e) {
        setLocal({ foods: searchMine(q), corrected: new Set() });
        setDbError((e as Error).message);
      } finally {
        if (n === seq.current) setLoading(false);
      }
    }, 200);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, barcodeMode]);

  async function goOnline(text = q, n = seq.current) {
    setOnlineLoading(true);
    try {
      const r = await searchOnline(text);
      if (n === seq.current) setOnline({ ...r, q: text });
    } finally {
      if (n === seq.current) setOnlineLoading(false);
    }
  }

  async function lookupBarcode() {
    setBarcodeMsg("Looking up…");
    try {
      const f = await byBarcode(query.trim());
      if (f) {
        setBarcodeMsg(undefined);
        onPick(f);
      } else setBarcodeMsg("No product found for that barcode. You can add it as a custom food.");
    } catch (e) {
      setBarcodeMsg(e instanceof Error ? e.message : "Barcode lookup is unavailable. Try again later.");
    }
  }

  const pick = (f: Food) => onPick(f, amount ? servingFor(amount, f) : undefined);
  const quick = onQuickAdd
    ? (f: Food) => {
        const a = amount ? servingFor(amount, f) : usualAmount(f.id) ?? { serving: defaultServing(f), quantity: defaultQuantity(f) };
        onQuickAdd(f, a.serving, a.quantity);
        setAdded((n) => n + 1);
      }
    : undefined;

  const applyFilter = (list: Food[]) =>
    filter === "all" ? list
      : filter === "mine" ? list.filter((f) => f.source === "Custom" || f.source === "Recipe" || usage.has(f.id))
        : filter === "favorites" ? list.filter((f) => favorites.has(f.id))
          : list.filter((f) => !isLabelOnly(f) && Object.keys(f.nutrients).length >= 20);

  const results = useMemo(
    () => applyFilter(rank([...local.foods, ...(online?.q === q ? online.foods : [])], q, { usage, meal, favorites, corrected: local.corrected })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [local, online, usage, favorites, filter, meal, q],
  );

  // Before typing.
  const recentEntries = useMemo(() => {
    const seen = new Set<string>();
    const out: DiaryEntry[] = [];
    for (const e of store.all("diary").sort((a, b) => b.createdAt - a.createdAt)) {
      if (seen.has(e.foodId) || e.foodId.startsWith("quick:") || e.foodId.startsWith("import:")) continue;
      seen.add(e.foodId);
      out.push(e);
      if (out.length >= 30) break;
    }
    return out;
  }, [version]);
  const usual = meal != null && (filter === "all" || filter === "mine") ? usualFor(meal, usage.values()).map((u) => u.last) : [];
  const recent = filter === "all" || filter === "mine" ? recentEntries.filter((e) => !usual.some((u) => u.foodId === e.foodId)) : [];
  const favFoods = useMemo(() => profile.favoriteFoodIds.map((id) => store.get("foods", id) ? recordToFood(store.get("foods", id)!) : recentEntries.find((e) => e.foodId === id) ? entryFood(recentEntries.find((e) => e.foodId === id)!) : null).filter((f): f is Food => !!f), [version]);
  const meals = onAddMeal && (filter === "all" || filter === "mine") ? savedMeals().sort((a, b) => Number(b.defaultMeal === meal) - Number(a.defaultMeal === meal)) : [];
  const mine = filter === "all" || filter === "mine" ? store.all("foods").filter((f) => f.kind === "custom" || f.kind === "recipe") : [];

  const row = (f: Food, key?: string) => (
    <div key={key ?? f.id} className="food-row-wrap" style={{ display: "flex", alignItems: "center" }}>
      <div style={{ flex: 1, minWidth: 0 }}><FoodRow food={f} onPick={pick} usual={usage.get(f.id)?.count} favorite={favorites.has(f.id)} /></div>
      {quick && (
        <button className="icon-btn sm food-row-add" aria-label={`Add ${f.name}`} title="Add with your usual amount" onClick={() => quick(f)}>
          <Plus size={16} />
        </button>
      )}
    </div>
  );

  return (
    <div className="food-search">
      <form
        className="search-bar"
        onSubmit={(e) => {
          e.preventDefault();
          if (barcodeMode && query.trim()) void lookupBarcode();
          else if (q) void goOnline();
        }}
      >
        {barcodeMode ? <ScanBarcode size={18} /> : <Search size={18} />}
        <input
          type="search"
          inputMode={barcodeMode ? "numeric" : undefined}
          placeholder={barcodeMode ? "Enter a UPC or EAN barcode" : "Search 13,000+ foods, or “200 g rice”"}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setBarcodeMsg(undefined);
          }}
          autoFocus
          aria-label={barcodeMode ? "Barcode" : "Search foods"}
        />
        {barcodeMode && query.trim() && <button className="btn btn-primary btn-sm">Look up</button>}
        <button
          type="button"
          className={`icon-btn sm ${barcodeMode ? "on" : ""}`}
          title={barcodeMode ? "Search by name" : "Look up a barcode"}
          aria-pressed={barcodeMode}
          onClick={() => {
            setBarcodeMode(!barcodeMode);
            setQuery("");
          }}
        >
          <ScanBarcode size={17} />
        </button>
      </form>
      {barcodeMsg && <p className="hint">{barcodeMsg}</p>}
      {!barcodeMode && (
        <div className="search-tools">
          {amount && <span className="pill">{amountLabel(amount)}</span>}
          {FILTERS.map(([id, label]) => (
            <button key={id} className={`chip ${filter === id ? "on" : ""}`} onClick={() => setFilter(id)}>{label}</button>
          ))}
          {added > 0 && <span className="hint">{added} added</span>}
        </div>
      )}

      <div className="search-results">
        {!q && !barcodeMode && (
          <>
            {usual.length > 0 && (
              <Section icon={<History size={13} />} title={`Usual for ${MEAL_NAMES[meal ?? 0]}`}>{usual.map((e) => row(entryFood(e), "u" + e.foodId))}</Section>
            )}
            {meals.length > 0 && (
              <Section icon={<Bookmark size={13} />} title="Saved meals">
                {meals.map((m) => (
                  <button key={m.id} className="food-row" onClick={() => onAddMeal!(m)}>
                    <span className="food-main">
                      <span className="food-name">{m.name}</span>
                      <span className="food-meta"><span>{m.items.map((i) => i.name).join(", ").slice(0, 80)}</span></span>
                    </span>
                    <span className="food-kcal num">{Math.round(m.kcal)}<small>kcal</small></span>
                  </button>
                ))}
              </Section>
            )}
            {favFoods.length > 0 && filter !== "complete" && (
              <Section icon={<Star size={13} />} title="Favourites">{favFoods.map((f) => row(f, "f" + f.id))}</Section>
            )}
            {recent.length > 0 && (
              <Section icon={<History size={13} />} title="Recent">{recent.map((e) => row(entryFood(e), "r" + e.foodId))}</Section>
            )}
            {mine.length > 0 && (
              <Section icon={<RecipeIcon size={13} />} title="My foods & recipes">{mine.map((r) => row(recordToFood(r), "m" + r.id))}</Section>
            )}
            {!recent.length && !mine.length && !usual.length && !favFoods.length && !meals.length && (
              <Empty icon={<Search size={22} />} title="Find a food">Type a food, or an amount and a food (“2 eggs”, “150 g chicken”). Online and branded foods show when local results are few.</Empty>
            )}
          </>
        )}
        {q && !barcodeMode && (
          <>
            {dbError && <p className="hint danger">{dbError}</p>}
            {local.correctedQuery && <p className="corrected">Showing results for “{local.correctedQuery}”</p>}
            {results.length > 0 && <Section icon={<Database size={13} />} title="Results">{results.map((f) => row(f))}</Section>}
            {loading && !results.length && (
              <div className="hint center"><LoaderCircle size={16} className="spin" /></div>
            )}
            {online && online.q === q && online.errors.length > 0 && (
              <>
                {!online.foods.length && <p className="hint">Couldn't search online.</p>}
                {online.errors.map((e) => <p key={e} className={online.foods.length ? "hint" : "hint danger"}>{e}</p>)}
              </>
            )}
            {!loading && !results.length && !onlineLoading && online?.q === q && !online.errors.length && <p className="hint">No foods found.</p>}
            {onlineLoading ? (
              <p className="hint center"><LoaderCircle size={16} className="spin" /> Searching branded and online foods…</p>
            ) : (
              (!online || online.q !== q || online.errors.length > 0) && (
                <button className="btn btn-secondary btn-block" onClick={() => void goOnline()}>
                  {online?.errors.length ? <RefreshCw size={16} /> : <Globe size={16} />}
                  {online?.errors.length ? "Try again" : "Search branded foods online"}
                </button>
              )
            )}
          </>
        )}
      </div>
    </div>
  );
}

/** Choose serving and quantity for a food, with a live macro preview. */
export function AmountForm({
  food,
  initialServing,
  initialQuantity,
  submitLabel,
  onSubmit,
  onBack,
  onDelete,
}: {
  food: Food;
  initialServing?: Serving;
  initialQuantity?: number;
  submitLabel: string;
  onSubmit: (serving: Serving, quantity: number) => void;
  onBack?: () => void;
  onDelete?: () => void;
}) {
  const servings = useMemo(() => {
    const list = allServings(food);
    if (initialServing && !list.some((s) => s.label === initialServing.label)) list.unshift(initialServing);
    return list;
  }, [food, initialServing]);
  // A new entry starts at the usual amount (the last one logged), unless an amount was typed.
  const usual = useMemo(() => (initialServing ? undefined : usualAmount(food.id)), [food.id, initialServing]);
  const startServing = initialServing ?? usual?.serving;
  const [servingIdx, setServingIdx] = useState(() => {
    if (!startServing) return 0;
    const i = servings.findIndex((s) => s.label === startServing.label);
    if (i >= 0) return i;
    servings.unshift(startServing);
    return 0;
  });
  const [qty, setQty] = useState(fmtQty(initialQuantity ?? usual?.quantity ?? defaultQuantity(food)));
  useStore();
  const prof = parseProfile(store.get("profile", "profile")?.json);
  const fav = prof.favoriteFoodIds.includes(food.id);
  const toggleFav = () =>
    store.put("profile", {
      id: "profile",
      json: encodeProfile({ ...prof, favoriteFoodIds: fav ? prof.favoriteFoodIds.filter((x) => x !== food.id) : [...prof.favoriteFoodIds, food.id] }),
      updatedAt: Date.now(),
      deleted: false,
    });
  const serving = servings[servingIdx] ?? servings[0];
  const q = toNum(qty) ?? 0;
  const grams = serving.grams * q;
  const t = forGrams(food.nutrients, grams);
  const p = t[N.PROTEIN] ?? 0, c = t[N.CARBS] ?? 0, f = t[N.FAT] ?? 0;

  return (
    <form
      className="amount"
      onSubmit={(e) => {
        e.preventDefault();
        if (q > 0) onSubmit(serving, q);
      }}
    >
      <div className="amount-meta">
        <span className={`tag tag-${SOURCE_TAG[food.source]?.toLowerCase()}`}>{SOURCE_TAG[food.source] ?? food.source}</span>
        {food.brand && <span>{food.brand}</span>}
        {food.category && <span>{food.category}</span>}
        {usual && <span className="dim">Last time's amount</span>}
        <span className="grow" />
        <button type="button" className={`icon-btn sm ${fav ? "on" : ""}`} onClick={toggleFav} aria-pressed={fav} aria-label={fav ? "Remove from favourites" : "Add to favourites"} title="Favourite">
          <Star size={16} fill={fav ? "currentColor" : "none"} />
        </button>
      </div>

      <div className="amount-grid">
        <div className="amount-fields">
          <label className="field">
            <span>Amount</span>
            <input className="input-lg num" inputMode="decimal" value={qty} onChange={(e) => setQty(e.target.value)} autoFocus aria-label="Quantity" />
          </label>
          <label className="field grow">
            <span>Serving</span>
            <select className="input-lg" value={servingIdx} onChange={(e) => setServingIdx(Number(e.target.value))}>
              {servings.map((s, i) => (
                <option key={s.label + i} value={i}>
                  {s.label}
                  {s.label === "g" || s.label === "100 g" ? "" : ` · ${fmtNum(s.grams)} g`}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="amount-preview">
          <Ring
            size={76}
            stroke={7}
            segments={[
              { value: p * 4, color: COLORS.protein },
              { value: c * 4, color: COLORS.carbs },
              { value: f * 9, color: COLORS.fat },
            ]}
          >
            <b className="num">{Math.round(t[N.ENERGY] ?? 0)}</b>
            <span>kcal</span>
          </Ring>
          <div className="macro-list">
            <div>
              <i className="dot" style={{ background: COLORS.protein }} />
              Protein <b className="num">{fmtNum(p)} g</b>
            </div>
            <div>
              <i className="dot" style={{ background: COLORS.carbs }} />
              Carbs <b className="num">{fmtNum(c)} g</b>
            </div>
            <div>
              <i className="dot" style={{ background: COLORS.fat }} />
              Fat <b className="num">{fmtNum(f)} g</b>
            </div>
            <div className="dim">{fmtNum(grams)} g total</div>
          </div>
        </div>
      </div>

      <details className="disclosure">
        <summary>All nutrients for this amount</summary>
        <NutrientGrid totals={t} showEmpty={false} />
      </details>

      <div className="actions">
        {onBack && (
          <button type="button" className="btn btn-ghost" onClick={onBack}>
            <ArrowLeft size={16} /> Back
          </button>
        )}
        {onDelete && (
          <button type="button" className="btn btn-danger-ghost" onClick={onDelete}>
            <Trash2 size={15} /> Delete
          </button>
        )}
        <span className="grow" />
        <button className="btn btn-primary" disabled={!(q > 0)}>
          {submitLabel}
        </button>
      </div>
    </form>
  );
}
