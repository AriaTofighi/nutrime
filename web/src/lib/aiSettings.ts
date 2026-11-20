import { ApiError } from "./api";

/** Assistant settings that an administrator changes on the server (see server AiSettings.kt). */
export interface AiSettings {
  model: string;
  thinkingLevel?: string | null;
  dailyMessages: number;
  webLookupDailyLimit: number;
}

export interface AiSettingsPatch {
  model?: string | null;
  /** "default" means the model's own default; null uses the server's environment value. */
  thinkingLevel?: string | null;
  dailyMessages?: number | null;
  webLookupDailyLimit?: number | null;
}

export interface AiSettingsView {
  effective: AiSettings;
  environment: AiSettings;
  overrides: AiSettingsPatch;
  apiKeySet: boolean;
  thinkingLevels: string[];
  updatedAt?: number | null;
}

export async function aiSettingsRequest(token: string, method: "GET" | "PUT" | "DELETE" = "GET", body?: AiSettingsPatch, signal?: AbortSignal): Promise<AiSettingsView> {
  let res: Response;
  try {
    res = await fetch("/api/admin/ai-settings", {
      method,
      headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      cache: "no-store",
      signal,
    });
  } catch (e) {
    if (signal?.aborted) throw e;
    throw new ApiError(0, "Can't reach the server. Check your connection.");
  }
  if (!res.ok) {
    const error = await res.json().catch(() => null);
    throw new ApiError(res.status, error?.error ?? `Server error (HTTP ${res.status})`);
  }
  return res.json() as Promise<AiSettingsView>;
}
