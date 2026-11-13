import { useState } from "react";
import { LB_PER_KG, encodeProfile, parseProfile } from "../lib/profile";
import { newId, store } from "../lib/store";
import type { WeightRecord } from "../lib/types";
import { fmtWeight, isoDate, toNum } from "../lib/util";
import { Modal } from "./ui";

/** Keep the profile's weight (used for calorie targets) equal to the most recent log, like the app. */
export function syncProfileWeight() {
  const latest = store.all("weights").sort((a, b) => b.date.localeCompare(a.date) || b.updatedAt - a.updatedAt)[0];
  if (!latest) return;
  const p = parseProfile(store.get("profile", "profile")?.json);
  if (p.weightKg !== latest.weightKg)
    store.put("profile", { id: "profile", json: encodeProfile({ ...p, weightKg: latest.weightKg }), updatedAt: Date.now(), deleted: false });
}

/** Soft-delete a weigh-in; returns the record as it was, for undo with [restoreWeight]. */
export function deleteWeight(id: string): WeightRecord | undefined {
  const before = store.get("weights", id);
  store.remove("weights", id);
  syncProfileWeight();
  return before;
}

/** Undo a delete: save the record again (new updatedAt, not deleted). */
export function restoreWeight(rec: WeightRecord) {
  store.put("weights", { ...rec, deleted: false, updatedAt: Date.now() });
  syncProfileWeight();
}

export function WeightDialog({
  imperial,
  date: initialDate,
  existing,
  onClose,
  onDeleted,
}: {
  imperial: boolean;
  date?: string;
  existing?: WeightRecord;
  onClose: () => void;
  /** Called after Delete with the removed record, so the parent can offer Undo. */
  onDeleted?: (rec: WeightRecord) => void;
}) {
  const conv = (kg: number) => (imperial ? kg * LB_PER_KG : kg);
  const [date, setDate] = useState(existing?.date ?? initialDate ?? isoDate());
  const [weight, setWeight] = useState(existing ? fmtWeight(conv(existing.weightKg)) : "");
  const [bf, setBf] = useState(existing?.bodyFatPct != null ? String(existing.bodyFatPct) : "");
  const w = toNum(weight);
  const valid = w != null && w > 0;
  return (
    <Modal title={existing ? "Edit weight" : "Log weight"} onClose={onClose}>
      <form
        className="form"
        onSubmit={(e) => {
          e.preventDefault();
          if (!valid) return;
          store.put("weights", {
            id: existing?.id ?? newId(),
            date,
            weightKg: imperial ? w / LB_PER_KG : w,
            bodyFatPct: toNum(bf),
            note: existing?.note,
            updatedAt: Date.now(),
            deleted: false,
          });
          syncProfileWeight();
          onClose();
        }}
      >
        <label className="field">
          <span>Date</span>
          <input type="date" value={date} max={isoDate()} onChange={(e) => setDate(e.target.value)} />
        </label>
        <div className="row2">
          <label className="field">
            <span>Weight</span>
            <div className="input-affix">
              <input inputMode="decimal" value={weight} onChange={(e) => setWeight(e.target.value)} autoFocus placeholder="0.0" />
              <em>{imperial ? "lb" : "kg"}</em>
            </div>
          </label>
          <label className="field">
            <span>Body fat (optional)</span>
            <div className="input-affix">
              <input inputMode="decimal" value={bf} onChange={(e) => setBf(e.target.value)} placeholder="—" />
              <em>%</em>
            </div>
          </label>
        </div>
        <div className="actions">
          {existing && (
            <button
              type="button"
              className="btn btn-danger-ghost"
              onClick={() => {
                const rec = deleteWeight(existing.id);
                onClose();
                if (rec) onDeleted?.(rec);
              }}
            >
              Delete
            </button>
          )}
          <span className="grow" />
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={!valid}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  );
}
