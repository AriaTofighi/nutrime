import { Check, Monitor, Moon, Sun } from "lucide-react";
import type { ReactNode } from "react";
import { Label } from "@/components/shadcn/label";
import { Separator } from "@/components/shadcn/separator";
import { Switch } from "@/components/shadcn/switch";
import { ToggleGroup, ToggleGroupItem } from "@/components/shadcn/toggle-group";
import { cn } from "@/lib/cn";
import { ACCENTS, CUSTOM_SATURATION, TEXT_SIZES, setTheme, useTheme, type DarkStyle, type MacroValue, type RingValue, type TextSize, type ThemeMode } from "../theme/theme";

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <Label className="text-[0.8125rem] font-medium text-muted-foreground">{label}</Label>
      {children}
      {hint && <p className="m-0 text-xs text-subtle-foreground">{hint}</p>}
    </div>
  );
}

function Choice<T extends string>({ value, options, onChange, label }: { value: T; options: { value: T; label: ReactNode }[]; onChange: (v: T) => void; label: string }) {
  return (
    <ToggleGroup type="single" value={value} onValueChange={(v) => v && onChange(v as T)} aria-label={label} className="w-full">
      {options.map((o) => (
        <ToggleGroupItem key={o.value} value={o.value}>
          {o.label}
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  );
}

const RAINBOW = `conic-gradient(${Array.from({ length: 7 }, (_, i) => `hsl(${(i * 60) % 360} ${CUSTOM_SATURATION}% 55%)`).join(", ")})`;
const HUE_TRACK = `linear-gradient(to right, ${Array.from({ length: 13 }, (_, i) => `hsl(${(i * 30) % 360} ${CUSTOM_SATURATION}% 50%)`).join(", ")})`;

function Swatch({ label, background, on, onClick }: { label: string; background: string; on: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={on}
      className="flex w-14 cursor-pointer flex-col items-center gap-1 rounded-md border-0 bg-transparent p-1 text-[0.6875rem] text-muted-foreground hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/35 focus-visible:outline-none"
    >
      <span
        className={cn("grid size-8 place-items-center rounded-full text-white shadow-[inset_0_0_0_1px_rgb(0_0_0/0.1)] transition-transform", on && "ring-2 ring-foreground ring-offset-2 ring-offset-card")}
        style={{ background }}
      >
        {on && <Check size={14} strokeWidth={3} />}
      </span>
      <span className={cn("truncate", on && "text-foreground")}>{label}</span>
    </button>
  );
}

/** Theme, accent color, text size and the diary summary options. Stored in this browser. */
export function AppearanceSettings() {
  const t = useTheme();
  const hue = t.customHue ?? 190;
  return (
    <div className="flex flex-col gap-5">
      <Field label="Theme">
        <Choice<ThemeMode>
          label="Theme"
          value={t.mode}
          onChange={(mode) => setTheme({ mode })}
          options={[
            { value: "light", label: <><Sun /> Light</> },
            { value: "dark", label: <><Moon /> Dark</> },
            { value: "system", label: <><Monitor /> System</> },
          ]}
        />
      </Field>
      {t.mode !== "light" && (
        <Field label="Dark theme" hint="Black uses a true black background. It can save power on OLED screens.">
          <Choice<DarkStyle> label="Dark theme" value={t.darkStyle} onChange={(darkStyle) => setTheme({ darkStyle })} options={[{ value: "dim", label: "Dim" }, { value: "black", label: "Black" }]} />
        </Field>
      )}
      <Field label="Accent color">
        <div className="flex flex-wrap gap-1">
          {ACCENTS.map((a) => (
            <Swatch key={a.id} label={a.label} background={a.swatch} on={t.customHue == null && t.accent === a.id} onClick={() => setTheme({ accent: a.id, customHue: null })} />
          ))}
          <Swatch
            label="Custom"
            background={t.customHue == null ? RAINBOW : `hsl(${hue} ${CUSTOM_SATURATION}% 50%)`}
            on={t.customHue != null}
            onClick={() => setTheme({ customHue: hue })}
          />
        </div>
        {t.customHue != null && (
          <div className="flex items-center gap-3 pt-1">
            <input
              type="range"
              min={0}
              max={359}
              value={t.customHue}
              onChange={(e) => setTheme({ customHue: Number(e.target.value) })}
              aria-label="Accent hue"
              className="hue-range h-2 grow cursor-pointer appearance-none rounded-full"
              style={{ background: HUE_TRACK }}
            />
            <span className="num w-10 text-right text-xs text-muted-foreground">{t.customHue}°</span>
          </div>
        )}
      </Field>
      <Field label="Text size">
        <Choice<TextSize> label="Text size" value={t.textSize} onChange={(textSize) => setTheme({ textSize })} options={TEXT_SIZES.map((s) => ({ value: s.value, label: s.label }))} />
      </Field>

      <Separator />

      <div className="flex flex-col gap-1">
        <h3 className="m-0 text-[15px] font-semibold text-foreground">Diary summary</h3>
      </div>
      <Field label="Number in the ring" hint="The ring always shows your daily target under the number.">
        <Choice<RingValue> label="Number in the ring" value={t.ring} onChange={(ring) => setTheme({ ring })} options={[{ value: "remaining", label: "Calories left" }, { value: "consumed", label: "Calories eaten" }]} />
      </Field>
      <Field label="Protein, carbs and fat">
        <Choice<MacroValue> label="Protein, carbs and fat" value={t.macros} onChange={(macros) => setTheme({ macros })} options={[{ value: "consumed", label: "Eaten" }, { value: "remaining", label: "Left" }]} />
      </Field>
      <label className="flex cursor-pointer items-center justify-between gap-4">
        <span className="flex flex-col">
          <span className="text-sm text-foreground">Color the ring by macro</span>
          <span className="text-xs text-subtle-foreground">Show protein, carbs and fat as parts of the ring</span>
        </span>
        <Switch checked={t.ringSplit} onCheckedChange={(ringSplit) => setTheme({ ringSplit })} />
      </label>
    </div>
  );
}
