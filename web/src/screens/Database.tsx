import { ChevronLeft, ChevronRight, Database as DatabaseIcon, ExternalLink, RefreshCw, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { Card, Empty, Modal, NutrientGrid, Segmented } from "../components/ui";
import { adminGet, type AdminFood, type AdminFoodPage, type FoodCollection } from "../lib/adminFoods";
import { N, fmtNum } from "../lib/nutrients";

const COLLECTIONS: { value: FoodCollection; label: string }[] = [
  { value: "curated", label: "NutriMe curated" },
  { value: "cached", label: "Saved from providers" },
  { value: "library", label: "USDA library" },
];
const DESCRIPTIONS: Record<FoodCollection, string> = {
  curated: "Foods selected for NutriMe, with source notes and search aliases.",
  cached: "Foods saved from USDA and Open Food Facts searches. These have not been reviewed by NutriMe.",
  library: "The USDA reference foods bundled with NutriMe.",
};

function providerLink(value?: string | null): string | undefined {
  if (!value) return;
  try {
    const url = new URL(value);
    if (url.protocol === "https:" && (url.hostname === "fdc.nal.usda.gov" || url.hostname === "world.openfoodfacts.org")) return url.href;
  } catch { /* Stored provider links are optional. */ }
}

export default function Database({ token }: { token: string }) {
  const [collection, setCollection] = useState<FoodCollection>("curated");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ key: string; data?: AdminFoodPage; error?: string }>();
  const [selected, setSelected] = useState<AdminFood | null>(null);
  const requestKey = JSON.stringify([collection, query.trim(), page, attempt]);
  const current = result?.key === requestKey ? result : undefined;
  const data = current?.data;
  const loading = !current;

  useEffect(() => {
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      const params = new URLSearchParams({ collection, q: query.trim(), page: String(page) });
      void adminGet<AdminFoodPage>(`foods?${params}`, token, controller.signal).then(
        (data) => { if (!controller.signal.aborted) setResult({ key: requestKey, data }); },
        (error: unknown) => {
          if (!controller.signal.aborted) setResult({ key: requestKey, error: error instanceof Error ? error.message : "Could not load foods. Please try again." });
        },
      );
    }, 250);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [collection, query, page, attempt, token, requestKey]);

  const sourceUrl = providerLink(selected?.sourceUrl);
  return (
    <div className="page catalog-page">
      <header className="page-head">
        <div>
          <h1 className="page-title">Food database</h1>
          <p className="page-sub">Read-only access to NutriMe's food collections</p>
        </div>
        <button className="btn btn-secondary" onClick={() => setAttempt((n) => n + 1)} disabled={loading}>
          <RefreshCw size={15} /> Refresh
        </button>
      </header>
      <div className="toolbar catalog-toolbar">
        <Segmented options={COLLECTIONS} value={collection} onChange={(next) => { setCollection(next); setPage(1); }} />
        <label className="search-bar catalog-search">
          <Search size={16} aria-hidden />
          <input aria-label="Search food database" placeholder="Search names, brands or barcodes" value={query} maxLength={200}
            onChange={(event) => { setQuery(event.target.value); setPage(1); }} />
        </label>
      </div>
      <p className="page-sub catalog-description">{DESCRIPTIONS[collection]} Searching here uses stored data only.</p>
      <Card flush>
        {loading ? <div className="catalog-message" role="status">Loading foods…</div> : current?.error ? (
          <div className="catalog-message" role="alert">
            <p>{current.error}</p>
            <button className="btn btn-secondary" onClick={() => setAttempt((n) => n + 1)}>Try again</button>
          </div>
        ) : data?.items.length ? (
          <div className="catalog-scroll">
            <table className="catalog-table">
              <caption className="sr-only">Stored foods. Nutrients are per 100 grams. Select a food to view details.</caption>
              <thead><tr><th scope="col">Food</th><th scope="col" className="catalog-source">Source</th><th scope="col" className="num">kcal / 100 g</th></tr></thead>
              <tbody>{data.items.map((item) => (
                <tr key={item.food.id}>
                  <td><button className="catalog-food" onClick={() => setSelected(item)}>
                    <span>{item.food.name}</span>
                    <span className="catalog-secondary">{[item.food.brand, item.food.category, item.food.barcode].filter(Boolean).join(" · ") || item.food.id}</span>
                  </button></td>
                  <td className="catalog-source">{item.sourceLabel}</td>
                  <td className="num">{item.food.nutrients[N.ENERGY] == null ? "—" : fmtNum(item.food.nutrients[N.ENERGY])}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        ) : (
          <Empty icon={<DatabaseIcon size={22} />} title={query.trim() ? "No matching foods" : "No foods in this collection"}>
            {query.trim() ? "Try a different name, brand or barcode." : collection === "cached" ? "Provider results appear here after a food search saves them." : "This collection has no stored entries."}
          </Empty>
        )}
      </Card>
      {data && <footer className="catalog-pagination">
        <span role="status">{data.total.toLocaleString()} foods{data.total > 0 && ` · Page ${page} of ${Math.max(1, Math.ceil(data.total / data.pageSize)).toLocaleString()}`}</span>
        <div className="catalog-page-buttons">
          <button className="btn btn-secondary" disabled={page <= 1} onClick={() => setPage((n) => n - 1)}><ChevronLeft size={15} /> Previous</button>
          <button className="btn btn-secondary" disabled={page * data.pageSize >= data.total} onClick={() => setPage((n) => n + 1)}>Next <ChevronRight size={15} /></button>
        </div>
      </footer>}
      {selected && <Modal title={selected.food.name} subtitle={`${selected.sourceLabel} · ${selected.reviewStatus}`} onClose={() => setSelected(null)} wide>
        <dl className="catalog-details">
          <dt>Food ID</dt><dd>{selected.food.id}</dd>
          {selected.food.brand && <><dt>Brand</dt><dd>{selected.food.brand}</dd></>}
          {selected.food.barcode && <><dt>Barcode</dt><dd>{selected.food.barcode}</dd></>}
          {selected.food.category && <><dt>Category</dt><dd>{selected.food.category}</dd></>}
          {selected.license && <><dt>License</dt><dd>{selected.license}</dd></>}
          {selected.fetchedAt != null && <><dt>Last fetched</dt><dd>{new Date(selected.fetchedAt).toLocaleString()}</dd></>}
          {sourceUrl && <><dt>Source</dt><dd><a href={sourceUrl} target="_blank" rel="noopener noreferrer">View provider record <ExternalLink size={12} /></a></dd></>}
        </dl>
        {selected.basis && <section className="catalog-section"><h3>Source notes</h3><p>{selected.basis}</p></section>}
        {!!selected.aliases.length && <section className="catalog-section"><h3>Search aliases</h3><p>{selected.aliases.join(" · ")}</p></section>}
        <section className="catalog-section"><h3>Serving sizes</h3>
          {selected.food.servings.length ? <ul className="catalog-servings">{selected.food.servings.map((serving, index) => <li key={index}><span>{serving.label}</span><span className="num">{fmtNum(serving.grams)} g</span></li>)}</ul> : <p>No serving sizes stored. Use grams.</p>}
        </section>
        <section className="catalog-section"><h3>Nutrients per 100 g</h3>
          <p className="catalog-secondary">Only stored nutrients are shown. Missing values are unknown, not zero.</p>
          <NutrientGrid totals={selected.food.nutrients} showEmpty={false} />
        </section>
      </Modal>}
    </div>
  );
}
