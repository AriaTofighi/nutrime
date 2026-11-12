import { useEffect, useState } from "react";
import { decodeRange, encodeRange, type TrendRange } from "../lib/range";
import { useStore } from "../lib/store";
import { BurnView } from "./trends/BurnView";
import { MainView } from "./trends/MainView";
import { NutrientsView, NutrientView } from "./trends/NutrientViews";
import { WeightView } from "./trends/WeightView";
import { usePeriod, type View } from "./trends/shared";

export { saveProfile } from "./trends/shared";

const RANGE_KEY = "nutrime.trendsRange";

export default function Trends() {
  useStore();
  const [range, setRangeState] = useState<TrendRange>(() => {
    try {
      return decodeRange(localStorage.getItem(RANGE_KEY)) ?? { kind: "last", days: 7 };
    } catch {
      return { kind: "last", days: 7 };
    }
  });
  const setRange = (r: TrendRange) => {
    setRangeState(r);
    try {
      localStorage.setItem(RANGE_KEY, encodeRange(r));
    } catch {
      /* ignore */
    }
  };
  const [view, setView] = useState<View>(() => {
    try {
      const v = sessionStorage.getItem("nutrime.trendsView");
      sessionStorage.removeItem("nutrime.trendsView");
      if (v === "burn") return { kind: "burn" };
      if (v === "weight") return { kind: "weight" };
    } catch {
      /* ignore */
    }
    return { kind: "main" };
  });
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [view.kind]);
  const p = usePeriod(range);
  const back = () => setView({ kind: "main" });
  if (view.kind === "nutrients") return <NutrientsView p={p} range={range} setRange={setRange} onBack={back} onOpen={(id) => setView({ kind: "nutrient", id })} />;
  if (view.kind === "nutrient") return <NutrientView p={p} id={view.id} range={range} setRange={setRange} onBack={() => setView({ kind: "nutrients" })} />;
  if (view.kind === "weight") return <WeightView onBack={back} />;
  if (view.kind === "burn") return <BurnView onBack={back} />;
  return <MainView p={p} range={range} setRange={setRange} setView={setView} />;
}
