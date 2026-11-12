import { ApiError } from "./api";

export interface Invitation {
  id: string;
  email: string;
  createdAt: number;
  expiresAt: number;
  usedAt?: number | null;
  revokedAt?: number | null;
}

export interface CreatedInvitation {
  invitation: Invitation;
  code: string;
}

export async function invitationRequest<T>(token: string, path = "", body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/admin/invitations${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: { Authorization: `Bearer ${token}`, ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store", signal,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new ApiError(response.status, error?.error ?? `Server error (HTTP ${response.status})`);
  }
  return response.json() as Promise<T>;
}
