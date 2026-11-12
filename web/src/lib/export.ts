/** CSV and ZIP writers for "Export your data". A copy of shared/.../model/Export.kt. */
import { NUTRIENTS, type NutrientMap } from "./nutrients";

const MEALS = ["Breakfast", "Lunch", "Dinner", "Snacks"];

export function cell(v: unknown): string {
  let s: string;
  if (v == null || (typeof v === "number" && Number.isNaN(v))) s = "";
  else if (typeof v === "number") s = Number.isInteger(v) ? String(v) : String(+v.toFixed(4));
  else s = String(v);
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

const line = (...cells: unknown[]) => cells.map(cell).join(",") + "\r\n";
const headers = () => NUTRIENTS.map((n) => `${n.name} (${n.unit})`);

export interface DiaryRow { date: string; meal: number; food: string; brand?: string; amount: number; serving: string; grams: number; nutrients: NutrientMap }
export interface DayRow { date: string; complete: boolean; note?: string | null; steps?: number | null; activeKcal?: number | null; target?: number | null; totals: NutrientMap }
export interface WeightRow { date: string; kg: number; bodyFatPct?: number | null; trendKg?: number | null; note?: string | null }

export function diaryCsv(rows: DiaryRow[]): string {
  let out = line("Date", "Meal", "Food", "Brand", "Amount", "Serving", "Grams", ...headers());
  for (const r of [...rows].sort((a, b) => a.date.localeCompare(b.date) || a.meal - b.meal))
    out += line(r.date, MEALS[r.meal] ?? "", r.food, r.brand, r.amount, r.serving, r.grams, ...NUTRIENTS.map((n) => r.nutrients[n.id]));
  return out;
}

export function daysCsv(rows: DayRow[]): string {
  let out = line("Date", "Complete", "Note", "Steps", "Active energy (kcal)", "Calorie target (kcal)", ...headers());
  for (const r of [...rows].sort((a, b) => a.date.localeCompare(b.date)))
    out += line(r.date, r.complete ? "yes" : "no", r.note, r.steps, r.activeKcal, r.target, ...NUTRIENTS.map((n) => r.totals[n.id]));
  return out;
}

export function weightsCsv(rows: WeightRow[]): string {
  let out = line("Date", "Weight (kg)", "Body fat (%)", "Trend weight (kg)", "Note");
  for (const r of [...rows].sort((a, b) => a.date.localeCompare(b.date)))
    out += line(r.date, r.kg, r.bodyFatPct, r.trendKg == null ? null : Math.round(r.trendKg * 100) / 100, r.note);
  return out;
}

// ---- A small ZIP writer (stored, no compression) ----

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(data: Uint8Array): number {
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

export function zip(files: Record<string, string>): Blob {
  const enc = new TextEncoder();
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const [name, text] of Object.entries(files)) {
    const nameBytes = enc.encode(name);
    const data = enc.encode(text);
    const crc = crc32(data);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true);
    local.setUint16(6, 0x0800, true); // UTF-8 names
    local.setUint32(14, crc, true);
    local.setUint32(18, data.length, true);
    local.setUint32(22, data.length, true);
    local.setUint16(26, nameBytes.length, true);
    parts.push(new Uint8Array(local.buffer), nameBytes, data);
    const c = new DataView(new ArrayBuffer(46));
    c.setUint32(0, 0x02014b50, true);
    c.setUint16(4, 20, true);
    c.setUint16(6, 20, true);
    c.setUint16(8, 0x0800, true);
    c.setUint32(16, crc, true);
    c.setUint32(20, data.length, true);
    c.setUint32(24, data.length, true);
    c.setUint16(28, nameBytes.length, true);
    c.setUint32(42, offset, true);
    central.push(new Uint8Array(c.buffer), nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const size = central.reduce((s, b) => s + b.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, Object.keys(files).length, true);
  end.setUint16(10, Object.keys(files).length, true);
  end.setUint32(12, size, true);
  end.setUint32(16, offset, true);
  return new Blob([...parts, ...central, new Uint8Array(end.buffer)] as BlobPart[], { type: "application/zip" });
}

/** Read the stored (uncompressed) and deflated files of a ZIP. */
export async function unzip(buf: ArrayBuffer): Promise<Record<string, string>> {
  const v = new DataView(buf);
  const out: Record<string, string> = {};
  let i = 0;
  const dec = new TextDecoder();
  while (i + 30 <= buf.byteLength && v.getUint32(i, true) === 0x04034b50) {
    const method = v.getUint16(i + 8, true);
    const flags = v.getUint16(i + 6, true);
    let size = v.getUint32(i + 18, true);
    const nameLen = v.getUint16(i + 26, true);
    const extraLen = v.getUint16(i + 28, true);
    const name = dec.decode(new Uint8Array(buf, i + 30, nameLen));
    const start = i + 30 + nameLen + extraLen;
    if (flags & 0x08 && size === 0) throw new Error("This ZIP file uses a format NutriMe can't read. Unzip it and import the CSV files.");
    const raw = new Uint8Array(buf, start, size);
    if (method === 0) out[name] = dec.decode(raw);
    else if (method === 8) {
      const stream = new Blob([raw as BlobPart]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
      out[name] = await new Response(stream).text();
    }
    i = start + size;
    size = 0;
  }
  return out;
}

export function download(name: string, blob: Blob) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}
