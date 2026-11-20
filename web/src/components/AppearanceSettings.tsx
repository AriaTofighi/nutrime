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
      aria-label={label}
      title={label}
      className={cn(
        "grid size-8 cursor-pointer place-items-center rounded-full border-0 p-0 text-white shadow-[inset_0_0_0_1px_rgb(0_0_0/0.1)] transition-transform outline-none hover:scale-110 focus-visible:ring-[3px] focus-visible:ring-ring/35",
        on && "ring-2 ring-foreground ring-offset-2 ring-offset-card hover:scale-100",
      )}
      style={{ background }}
    >
      {on && <Check size={14} strokeWidth={3} />}
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
        <Field label="Dark style">
          <Choice<DarkStyle> label="Dark theme" value={t.darkStyle} onChange={(darkStyle) => setTheme({ darkStyle })} options={[{ value: "dim", label: "Dim" }, { value: "black", label: "Black" }]} />
        </Field>
      )}
      <Field label="Color">
        <div className="flex justify-between gap-1 py-1">
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

      <h3 className="m-0 text-[0.9375rem] font-semibold text-foreground">Diary</h3>
      <Field label="Calories">
        <Choice<RingValue> label="Calories" value={t.ring} onChange={(ring) => setTheme({ ring })} options={[{ value: "remaining", label: "Left" }, { value: "consumed", label: "Eaten" }]} />
      </Field>
      <Field label="Macros">
        <Choice<MacroValue> label="Macros" value={t.macros} onChange={(macros) => setTheme({ macros })} options={[{ value: "remaining", label: "Left" }, { value: "consumed", label: "Eaten" }]} />
      </Field>
      {t.ring === "consumed" && (
        <label className="flex cursor-pointer items-center justify-between gap-4">
          <span className="text-sm text-foreground">Macro colors in the ring</span>
          <Switch checked={t.ringSplit} onCheckedChange={(ringSplit) => setTheme({ ringSplit })} />
        </label>
      )}
    </div>
  );
}
