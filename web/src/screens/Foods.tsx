import { RecipeIcon } from "../components/icons";
import {Plus, Salad, Search, Trash2} from "lucide-react";
import { useState } from "react";
import { deleteMeal, saveMeal, savedMeals, type SavedMeal } from "../lib/days";
import { AmountForm, FoodRow, FoodSearch } from "../components/FoodPicker";
import { Card, Empty, Modal, NutrientGrid, Segmented } from "../components/ui";
import { ingredientNutrients, recipeIngredients, recordToFood, saveCustomFood, saveRecipe, type Food } from "../lib/foods";
import { LABEL_NUTRIENTS, N, NUTRIENTS, NUTRIENT_BY_ID, fmtNum, sum } from "../lib/nutrients";
import { store, useStore } from "../lib/store";
import type { FoodRecord, RecipeIngredient } from "../lib/types";
import { fmtQty, toNum } from "../lib/util";

type Editor = { kind: "custom"; record?: FoodRecord } | { kind: "recipe"; record?: FoodRecord } | { kind: "view"; record: FoodRecord } | null;

const KIND_LABEL: Record<string, string> = {
  custom: "Custom food",
  recipe: "Recipe",
  usda: "USDA",
  usda_branded: "USDA Branded",
  off: "Open Food Facts",
};

const FOODS_HELP = {
  foods: "Single foods you created, usually from a package's nutrition label.",
  recipes: "Dishes made of several ingredients. Their nutrition is the sum of the ingredients, split into servings.",
  meals: "Foods you eat together, saved from a day in the diary. Add one in a single step when you add food.",
  saved: "Foods from USDA and Open Food Facts that you logged. They are kept so they're quick to find again. Open one to remove it; your diary keeps its entries.",
};

export default function Foods() {
  useStore();
  const [editor, setEditor] = useState<Editor>(null);
  const [filter, setFilter] = useState("");
  const [tab, setTab] = useState<"foods" | "recipes" | "meals" | "saved">("foods");
  const [mealEdit, setMealEdit] = useState<SavedMeal | null>(null);
  const meals = savedMeals().filter((m) => !filter.trim() || m.name.toLowerCase().includes(filter.trim().toLowerCase()));
  const all = store
    .all("foods")
    .filter((f) => !filter.trim() || `${f.name} ${f.brand ?? ""}`.toLowerCase().includes(filter.trim().toLowerCase()))
    .sort((a, b) => a.name.localeCompare(b.name));
  const foods = all.filter((f) => f.kind === "custom");
  const recipes = all.filter((f) => f.kind === "recipe");
  const saved = all.filter((f) => f.kind !== "custom" && f.kind !== "recipe");
  const list = tab === "foods" ? foods : tab === "recipes" ? recipes : saved;
  const open = (f: FoodRecord) =>
    setEditor(f.kind === "custom" ? { kind: "custom", record: f } : f.kind === "recipe" ? { kind: "recipe", record: f } : { kind: "view", record: f });

  return (
    <div className="page page-narrow">
      <header className="page-head">
        <div>
          <h1 className="page-title">Foods</h1>
          <p className="page-sub">Foods you made, recipes, and online foods you've logged</p>
        </div>
        <div className="head-actions">
          <button className="btn btn-secondary" onClick={() => setEditor({ kind: "recipe" })}>
            <RecipeIcon size={16} /> New recipe
          </button>
          <button className="btn btn-primary" onClick={() => setEditor({ kind: "custom" })}>
            <Plus size={17} /> Custom food
          </button>
        </div>
      </header>
      <div className="toolbar">
        <Segmented
          options={[
            { value: "foods", label: `My foods · ${foods.length}` },
            { value: "recipes", label: `Recipes · ${recipes.length}` },
            { value: "meals", label: `Meals · ${meals.length}` },
            { value: "saved", label: `Saved · ${saved.length}` },
          ]}
          value={tab}
          onChange={setTab}
        />
        <div className="search-bar search-bar-sm">
          <Search size={16} />
          <input type="search" placeholder="Filter" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter foods" />
        </div>
      </div>
      <p className="foods-help">{FOODS_HELP[tab]}</p>
      {tab === "meals" ? (
        <Card flush>
          {meals.length ? (
            <div className="food-list">
              {meals.map((m) => (
                <button key={m.id} className="food-row" onClick={() => setMealEdit(m)}>
                  <span className="food-main">
                    <span className="food-name">{m.name}</span>
                    <span className="food-meta"><span>{m.items.map((i) => i.name).join(", ").slice(0, 90)}</span></span>
                  </span>
                  <span className="food-kcal num">{Math.round(m.kcal)}<small>kcal</small></span>
                </button>
              ))}
            </div>
          ) : (
            <Empty icon={<Salad size={22} />} title={filter ? "No matches" : "No saved meals yet"}>Below a day's foods in the diary, choose “Save as meal”.</Empty>
          )}
        </Card>
      ) : (
      <Card flush>
        {list.length ? (
          <div className="food-list">
            {list.map((f) => (
              <FoodRow key={f.id} food={recordToFood(f)} onPick={() => open(f)} />
            ))}
          </div>
        ) : tab === "foods" ? (
          <Empty icon={<Salad size={22} />} title={filter ? "No matches" : "No foods yet"}>
            Create a food from its nutrition label.
          </Empty>
        ) : tab === "recipes" ? (
          <Empty icon={<RecipeIcon size={22} />} title={filter ? "No matches" : "No recipes yet"}>
            Build a recipe from its ingredients, like a dish you cook often.
          </Empty>
        ) : (
          <Empty icon={<Salad size={22} />} title={filter ? "No matches" : "Nothing saved yet"}>
            Branded and Open Food Facts foods you log are saved here automatically.
          </Empty>
        )}
      </Card>
      )}
      {mealEdit && <MealEditor meal={mealEdit} onClose={() => setMealEdit(null)} />}

      {editor?.kind === "custom" && <CustomFoodEditor record={editor.record} onClose={() => setEditor(null)} />}
      {editor?.kind === "recipe" && <RecipeEditor record={editor.record} onClose={() => setEditor(null)} />}
      {editor?.kind === "view" && (
        <Modal title={editor.record.name} subtitle={`${KIND_LABEL[editor.record.kind]} · per 100 g`} onClose={() => setEditor(null)} wide>
          <NutrientGrid totals={recordToFood(editor.record).nutrients} showEmpty={false} />
          <div className="actions">
            <button
              className="btn btn-danger-ghost"
              onClick={() => {
                store.remove("foods", editor.record.id);
                setEditor(null);
              }}
            >
              <Trash2 size={15} /> Remove from saved foods
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

function CustomFoodEditor({ record, onClose }: { record?: FoodRecord; onClose: () => void }) {
  const food = record ? recordToFood(record) : undefined;
  const s0 = food?.servings[0] ?? { label: "serving", grams: 100 };
  const [name, setName] = useState(food?.name ?? "");
  const [brand, setBrand] = useState(food?.brand ?? "");
  const [barcode, setBarcode] = useState(food?.barcode ?? "");
  const [servingLabel, setServingLabel] = useState(s0.label);
  const [servingGrams, setServingGrams] = useState(fmtQty(s0.grams));
  const [showAll, setShowAll] = useState(() => !!food && Object.keys(food.nutrients).some((k) => !LABEL_NUTRIENTS.includes(Number(k))));
  const [values, setValues] = useState<Record<number, string>>(() => {
    const out: Record<number, string> = {};
    if (food) for (const [k, v] of Object.entries(food.nutrients)) out[Number(k)] = fmtQty(Math.round(((v * s0.grams) / 100) * 1000) / 1000);
    return out;
  });
  const grams = toNum(servingGrams) ?? 0;
  const valid = name.trim() && grams > 0 && toNum(values[N.ENERGY] ?? "") != null;
  const defs = showAll ? NUTRIENTS : LABEL_NUTRIENTS.map((id) => NUTRIENT_BY_ID[id]);

  function save(e: React.FormEvent) {
    e.preventDefault();
    if (!valid) return;
    const per100: Record<number, number> = {};
    for (const [k, v] of Object.entries(values)) {
      const n = toNum(v);
      if (n != null) per100[Number(k)] = (n * 100) / grams;
    }
    saveCustomFood(record?.id, name.trim(), brand, barcode, per100, [{ label: servingLabel.trim() || "serving", grams }]);
    onClose();
  }

  return (
    <Modal title={record ? "Edit custom food" : "New custom food"} subtitle="Enter it the way it's printed on the label" onClose={onClose} wide>
      <form className="form" onSubmit={save}>
        <label className="field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
        </label>
        <div className="row2">
          <label className="field">
            <span>Brand (optional)</span>
            <input value={brand} onChange={(e) => setBrand(e.target.value)} />
          </label>
          <label className="field">
            <span>Barcode (optional)</span>
            <input inputMode="numeric" value={barcode} onChange={(e) => setBarcode(e.target.value.replace(/\D/g, ""))} />
          </label>
        </div>
        <div className="eyebrow">Serving size</div>
        <div className="row2">
          <label className="field">
            <span>Name, e.g. 1 cup</span>
            <input value={servingLabel} onChange={(e) => setServingLabel(e.target.value)} />
          </label>
          <label className="field">
            <span>Grams</span>
            <input inputMode="decimal" value={servingGrams} onChange={(e) => setServingGrams(e.target.value)} />
          </label>
        </div>
        <div className="eyebrow">Nutrition per serving</div>
        <div className="nutrient-inputs">
          {defs.map((d) => (
            <label key={d.id} className="field">
              <span>{d.name} <em>{d.unit}</em></span>
              <input inputMode="decimal" value={values[d.id] ?? ""} onChange={(e) => setValues({ ...values, [d.id]: e.target.value })} />
            </label>
          ))}
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setShowAll(!showAll)}>
          {showAll ? "Show label nutrients only" : "Show all nutrients"}
        </button>
        <div className="actions">
          {record && (
            <button
              type="button"
              className="btn btn-danger-ghost"
              onClick={() => {
                store.remove("foods", record.id);
                onClose();
              }}
            >
              <Trash2 size={15} /> Delete
            </button>
          )}
          <span className="grow" />
          <button className="btn btn-primary" disabled={!valid}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  );
}

function RecipeEditor({ record, onClose }: { record?: FoodRecord; onClose: () => void }) {
  const [name, setName] = useState(record?.name ?? "");
  const [servings, setServings] = useState(fmtQty(record?.recipeServings ?? 1));
  const [ingredients, setIngredients] = useState<RecipeIngredient[]>(() => (record ? recipeIngredients(record) : []));
  const [adding, setAdding] = useState<Food | "search" | null>(null);
  const n = toNum(servings) ?? 0;
  const totals = sum(ingredients.map(ingredientNutrients));
  const perServing: Record<number, number> = {};
  for (const k in totals) perServing[k] = totals[k] / Math.max(n, 0.0001);
  const valid = name.trim() && n > 0 && ingredients.length > 0;

  if (adding) {
    return (
      <Modal title="Add ingredient" onClose={() => setAdding(null)} wide>
        {adding === "search" ? (
          <FoodSearch onPick={(f) => setAdding(f)} />
        ) : (
          <AmountForm
            food={adding}
            submitLabel="Add ingredient"
            onBack={() => setAdding("search")}
            onSubmit={(s, q) => {
              setIngredients([
                ...ingredients,
                { foodId: adding.id, name: adding.name, servingLabel: s.label, servingGrams: s.grams, quantity: q, nutrients: { ...adding.nutrients } },
              ]);
              setAdding(null);
            }}
          />
        )}
      </Modal>
    );
  }

  return (
    <Modal title={record ? "Edit recipe" : "New recipe"} subtitle="Totals are split across the number of servings" onClose={onClose} wide>
      <form
        className="form"
        onSubmit={(e) => {
          e.preventDefault();
          if (!valid) return;
          saveRecipe(record?.id, name.trim(), n, ingredients);
          onClose();
        }}
      >
        <div className="row2">
          <label className="field">
            <span>Recipe name</span>
            <input value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
          </label>
          <label className="field">
            <span>Number of servings</span>
            <input inputMode="decimal" value={servings} onChange={(e) => setServings(e.target.value)} />
          </label>
        </div>
        <div className="eyebrow">Ingredients</div>
        {ingredients.length === 0 && <p className="hint">No ingredients yet.</p>}
        <div className="ingredients">
          {ingredients.map((i, idx) => (
            <div key={idx} className="ingredient">
              <span className="food-main">
                <span className="food-name">{i.name}</span>
                <span className="food-meta">
                  {fmtQty(i.quantity)} × {i.servingLabel} · {fmtNum(i.servingGrams * i.quantity)} g
                </span>
              </span>
              <span className="food-kcal num">
                {Math.round(ingredientNutrients(i)[N.ENERGY] ?? 0)}
                <small>kcal</small>
              </span>
              <button type="button" className="icon-btn sm" aria-label={`Remove ${i.name}`} onClick={() => setIngredients(ingredients.filter((_, j) => j !== idx))}>
                <Trash2 size={15} />
              </button>
            </div>
          ))}
        </div>
        <button type="button" className="btn btn-secondary" onClick={() => setAdding("search")}>
          <Plus size={16} /> Add ingredient
        </button>
        {ingredients.length > 0 && (
          <div className="summary-strip">
            <span className="dim">Per serving</span>
            <span className="num">
              <b>{Math.round(perServing[N.ENERGY] ?? 0)}</b> kcal
            </span>
            <span className="num">
              <i className="dot" style={{ background: "var(--protein)" }} />
              <b>{fmtNum(perServing[N.PROTEIN] ?? 0)}</b> g protein
            </span>
            <span className="num">
              <i className="dot" style={{ background: "var(--carbs)" }} />
              <b>{fmtNum(perServing[N.CARBS] ?? 0)}</b> g carbs
            </span>
            <span className="num">
              <i className="dot" style={{ background: "var(--fat)" }} />
              <b>{fmtNum(perServing[N.FAT] ?? 0)}</b> g fat
            </span>
          </div>
        )}
        <div className="actions">
          {record && (
            <button
              type="button"
              className="btn btn-danger-ghost"
              onClick={() => {
                store.remove("foods", record.id);
                onClose();
              }}
            >
              <Trash2 size={15} /> Delete
            </button>
          )}
          <span className="grow" />
          <button className="btn btn-primary" disabled={!valid}>
            Save recipe
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** Rename a saved meal, remove foods from it, or delete it. */
function MealEditor({ meal, onClose }: { meal: SavedMeal; onClose: () => void }) {
  const [name, setName] = useState(meal.name);
  const [items, setItems] = useState(meal.items);
  return (
    <Modal title="Edit meal" onClose={onClose}>
      <div className="form">
        <label className="field">
          <span>Name</span>
          <input className="input" value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />
        </label>
        <ul className="list">
          {items.map((i, k) => (
            <li key={k} className="list-row">
              <span>{i.name} <span className="dim">· {fmtQty(i.quantity)} {i.servingLabel}</span></span>
              <button className="icon-btn sm" aria-label={`Remove ${i.name}`} onClick={() => setItems(items.filter((_, j) => j !== k))}>×</button>
            </li>
          ))}
        </ul>
      </div>
      <div className="actions">
        <button className="btn btn-danger-ghost" onClick={() => { deleteMeal(meal.id); onClose(); }}>Delete</button>
        <span className="grow" />
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!name.trim() || !items.length} onClick={() => { saveMeal(name, [], meal.defaultMeal, meal.id, items); onClose(); }}>Save</button>
      </div>
    </Modal>
  );
}
