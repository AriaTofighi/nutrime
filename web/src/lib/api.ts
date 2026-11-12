/** Client for the NutriMe sync server. The web container proxies /api to the hosted backend. */

export interface AuthResponse {
  token: string;
  userId: string;
  email: string;
}

export interface SyncChange {
  collection: string;
  id: string;
  updatedAt: number;
  deleted: boolean;
  data?: unknown;
}

export interface SyncResponse {
  cursor: number;
  changes: SyncChange[];
  hasMore: boolean;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function post<T>(path: string, body: unknown, token?: string): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api/${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Can't reach the server. Check your connection.");
  }
  const text = await res.text();
  if (!res.ok) {
    let msg = `Server error (HTTP ${res.status})`;
    try {
      msg = JSON.parse(text).error ?? msg;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, msg);
  }
  return JSON.parse(text) as T;
}

export const login = (email: string, password: string) => post<AuthResponse>("auth/login", { email, password });
export const register = (email: string, password: string) => post<AuthResponse>("auth/register", { email, password });
export const sync = (token: string, since: number, changes: SyncChange[]) =>
  post<SyncResponse>("sync", { since, changes }, token);

export async function health(): Promise<{ status: string; registration: string } | null> {
  try {
    const r = await fetch("/api/health");
    return r.ok ? await r.json() : null;
  } catch {
    return null;
  }
}
