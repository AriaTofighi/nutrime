import { useEffect, useState } from "react";
import { ApiError } from "./api";
import type { NutrientMap } from "./nutrients";

export type FoodCollection = "curated" | "cached" | "library";
export interface AdminFood {
  food: {
    id: string;
    name: string;
    brand?: string | null;
    category?: string | null;
    barcode?: string | null;
    nutrients: NutrientMap;
    servings: { label: string; grams: number }[];
  };
  sourceLabel: string;
  reviewStatus: string;
  sourceUrl?: string | null;
  license?: string | null;
  fetchedAt?: number | null;
  basis?: string | null;
  aliases: string[];
}
export interface AdminFoodPage {
  items: AdminFood[];
  total: number;
  page: number;
  pageSize: number;
}

export async function adminGet<T>(path: string, token: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(`/api/admin/${path}`, {
    headers: { Authorization: `Bearer ${token}` }, signal, cache: "no-store",
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiError(response.status, body?.error ?? `Server error (HTTP ${response.status})`);
  }
  return response.json() as Promise<T>;
}

/** Bind the result to this session so a previous account's access is never reused. */
export function useAdminAccess(token?: string) {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ token: string; allowed: boolean; error?: string }>();
  useEffect(() => {
    setResult(undefined);
    if (!token) return;
    const controller = new AbortController();
    void adminGet<{ admin: boolean }>("status", token, controller.signal).then(
      ({ admin }) => { if (!controller.signal.aborted) setResult({ token, allowed: admin }); },
      () => { if (!controller.signal.aborted) setResult({ token, allowed: false, error: "Could not check database access. Please try again." }); },
    );
    return () => controller.abort();
  }, [token, attempt]);
  const current = result?.token === token ? result : undefined;
  return {
    allowed: current?.allowed === true,
    loading: !!token && !current,
    error: current?.error,
    retry: () => setAttempt((n) => n + 1),
  };
}
