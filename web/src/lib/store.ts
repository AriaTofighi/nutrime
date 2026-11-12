import { del, get, set } from "idb-keyval";
import { useSyncExternalStore } from "react";
import * as api from "./api";
import { COLLECTION_NAMES, type CollectionName, type Collections } from "./types";

/**
 * Local-first store with the same sync semantics as the Android app: local edits are marked dirty,
 * a sync pushes dirty records and pulls every server change newer than our cursor, and conflicts
 * resolve last-writer-wins by updatedAt. Data is cached in IndexedDB per account.
 */

export interface Account {
  token: string;
  userId: string;
  email: string;
}

type Tables = { [K in CollectionName]: Map<string, Collections[K]> };
type Dirty = { [K in CollectionName]: Set<string> };

interface Persisted {
  cursor: number;
  records: { [K in CollectionName]: Collections[K][] };
  dirty: { [K in CollectionName]: string[] };
  lastSync?: number;
}

export interface SyncStatus {
  running: boolean;
  lastSync?: number;
  error?: string;
  pending: number;
}

const ACCOUNT_KEY = "nutrime.account";

const emptyTables = (): Tables => ({ diary: new Map(), foods: new Map(), weights: new Map(), profile: new Map(), days: new Map(), meals: new Map() });
const emptyDirty = (): Dirty => ({ diary: new Set(), foods: new Set(), weights: new Set(), profile: new Set(), days: new Set(), meals: new Set() });

function loadAccount(): Account | null {
  try {
    const raw = localStorage.getItem(ACCOUNT_KEY);
    return raw ? (JSON.parse(raw) as Account) : null;
  } catch {
    return null;
  }
}

/** Drop nulls so Kotlin's decoder on the phone never sees an explicit null for a non-null field. */
function clean<T extends object>(r: T): T {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(r)) if (v !== null && v !== undefined && k !== "dirty") out[k] = v;
  return out as T;
}

class Store {
  account: Account | null = loadAccount();
  tables: Tables = emptyTables();
  dirty: Dirty = emptyDirty();
  cursor = 0;
  status: SyncStatus = { running: false, pending: 0 };
  loaded = false;
  version = 0;

  private listeners = new Set<() => void>();
  private saveTimer?: number;
  private syncTimer?: number;
  private syncing?: Promise<void>;
  private resync = false;

  subscribe = (fn: () => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  private emit() {
    this.version++;
    this.status = { ...this.status, pending: this.pendingCount() };
    this.listeners.forEach((l) => l());
  }

  private pendingCount() {
    return COLLECTION_NAMES.reduce((n, c) => n + this.dirty[c].size, 0);
  }

  private get dbKey() {
    return `nutrime.data.${this.account?.userId}`;
  }

  async init() {
    if (this.account) {
      try {
        const p = (await get(this.dbKey)) as Persisted | undefined;
        if (p) this.hydrate(p);
      } catch {
        /* IndexedDB unavailable (private mode): run in memory */
      }
    }
    this.loaded = true;
    this.emit();
    if (this.account) void this.sync();
  }

  private hydrate(p: Persisted) {
    this.tables = emptyTables();
    this.dirty = emptyDirty();
    for (const c of COLLECTION_NAMES) {
      const table = this.tables[c] as Map<string, Collections[typeof c]>;
      (p.records[c] ?? []).forEach((r) => table.set(r.id, r));
      (p.dirty[c] ?? []).forEach((id) => this.dirty[c].add(id));
    }
    this.cursor = p.cursor ?? 0;
    this.status.lastSync = p.lastSync;
  }

  private scheduleSave() {
    window.clearTimeout(this.saveTimer);
    this.saveTimer = window.setTimeout(() => void this.persist(), 300);
  }

  private async persist() {
    if (!this.account) return;
    const records = {} as Persisted["records"];
    const dirty = {} as Persisted["dirty"];
    for (const c of COLLECTION_NAMES) {
      (records as Record<string, unknown[]>)[c] = [...this.tables[c].values()];
      dirty[c] = [...this.dirty[c]];
    }
    try {
      await set(this.dbKey, { cursor: this.cursor, records, dirty, lastSync: this.status.lastSync } satisfies Persisted);
    } catch {
      /* ignore */
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Records
  // ---------------------------------------------------------------------------------------------

  all<K extends CollectionName>(c: K): Collections[K][] {
    return [...this.tables[c].values()].filter((r) => !r.deleted) as Collections[K][];
  }

  get<K extends CollectionName>(c: K, id: string): Collections[K] | undefined {
    const r = this.tables[c].get(id) as Collections[K] | undefined;
    return r && !r.deleted ? r : undefined;
  }

  /** Save a record (stamping updatedAt) and sync shortly after. */
  put<K extends CollectionName>(c: K, record: Collections[K], updatedAt = Date.now()) {
    const r = clean({ ...record, updatedAt }) as Collections[K];
    (this.tables[c] as Map<string, Collections[K]>).set(r.id, r);
    this.dirty[c].add(r.id);
    this.changed();
  }

  putMany<K extends CollectionName>(c: K, records: Collections[K][]) {
    const t = Date.now();
    records.forEach((r, i) => {
      const rec = clean({ ...r, updatedAt: r.updatedAt ?? t + i }) as Collections[K];
      (this.tables[c] as Map<string, Collections[K]>).set(rec.id, rec);
      this.dirty[c].add(rec.id);
    });
    if (records.length) this.changed();
  }

  remove<K extends CollectionName>(c: K, id: string) {
    const r = this.tables[c].get(id);
    if (r) this.put(c, { ...r, deleted: true } as Collections[K]);
  }

  private changed() {
    this.emit();
    this.scheduleSave();
    window.clearTimeout(this.syncTimer);
    this.syncTimer = window.setTimeout(() => void this.sync(), 1500);
  }

  // ---------------------------------------------------------------------------------------------
  // Account & sync
  // ---------------------------------------------------------------------------------------------

  async signIn(acc: Account) {
    localStorage.setItem(ACCOUNT_KEY, JSON.stringify(acc));
    this.account = acc;
    this.tables = emptyTables();
    this.dirty = emptyDirty();
    this.cursor = 0;
    this.status = { running: false, pending: 0 };
    try {
      const p = (await get(this.dbKey)) as Persisted | undefined;
      if (p) this.hydrate(p);
    } catch {
      /* ignore */
    }
    this.emit();
    await this.sync();
  }

  async signOut(clearLocal = true) {
    if (clearLocal && this.account) await del(this.dbKey).catch(() => undefined);
    localStorage.removeItem(ACCOUNT_KEY);
    this.account = null;
    this.tables = emptyTables();
    this.dirty = emptyDirty();
    this.cursor = 0;
    this.status = { running: false, pending: 0 };
    this.emit();
  }

  sync(): Promise<void> {
    if (this.syncing) {
      this.resync = true;
      return this.syncing;
    }
    this.syncing = this.doSync().finally(() => {
      this.syncing = undefined;
      if (this.resync) {
        this.resync = false;
        void this.sync();
      }
    });
    return this.syncing;
  }

  private async doSync() {
    const acc = this.account;
    if (!acc) return;
    this.status = { ...this.status, running: true };
    this.emit();
    try {
      const pushed: [CollectionName, string, number][] = [];
      const changes: api.SyncChange[] = [];
      for (const c of COLLECTION_NAMES) {
        for (const id of this.dirty[c]) {
          const r = this.tables[c].get(id);
          if (!r) continue;
          changes.push({ collection: c, id, updatedAt: r.updatedAt, deleted: r.deleted, data: r });
          pushed.push([c, id, r.updatedAt]);
        }
      }
      let res = await api.sync(acc.token, this.cursor, changes);
      if (this.account !== acc) return;
      // Only clear dirty flags for records that weren't edited again while the request was in flight.
      for (const [c, id, t] of pushed) if (this.tables[c].get(id)?.updatedAt === t) this.dirty[c].delete(id);
      this.apply(res.changes);
      this.cursor = res.cursor;
      while (res.hasMore) {
        res = await api.sync(acc.token, this.cursor, []);
        if (this.account !== acc) return;
        this.apply(res.changes);
        this.cursor = res.cursor;
      }
      this.status = { ...this.status, running: false, error: undefined, lastSync: Date.now() };
      void this.persist();
    } catch (e) {
      const err = e as api.ApiError;
      if (err.status === 401) {
        await this.signOut(false);
        this.status = { running: false, pending: 0, error: "Session expired — please sign in again" };
      } else {
        this.status = { ...this.status, running: false, error: err.message || "Sync failed" };
      }
    }
    this.emit();
  }

  private apply(changes: api.SyncChange[]) {
    for (const ch of changes) {
      if (!COLLECTION_NAMES.includes(ch.collection as CollectionName) || !ch.data || typeof ch.data !== "object") continue;
      const c = ch.collection as CollectionName;
      const local = this.tables[c].get(ch.id);
      if (local && this.dirty[c].has(ch.id) && local.updatedAt > ch.updatedAt) continue;
      const incoming = { ...(ch.data as object), id: ch.id, updatedAt: ch.updatedAt, deleted: ch.deleted } as Collections[typeof c];
      (this.tables[c] as Map<string, Collections[typeof c]>).set(ch.id, incoming);
      this.dirty[c].delete(ch.id);
    }
  }
}

export const store = new Store();

/** Re-render when anything in the store changes. */
export function useStore(): Store {
  useSyncExternalStore(store.subscribe, () => store.version);
  return store;
}

export const newId = () =>
  typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (ch) => {
        const r = (Math.random() * 16) | 0;
        return (ch === "x" ? r : (r & 0x3) | 0x8).toString(16);
      });
