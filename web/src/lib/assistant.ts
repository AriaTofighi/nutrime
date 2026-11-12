/** Client for the server's AI assistant (server/…/ai): conversations, streaming chat, proposals. */
import { ApiError } from "./api";

export interface AiStatus {
  enabled: boolean;
  model: string;
  dailyLimit: number;
  usedToday: number;
}
export interface ConversationSummary {
  id: string;
  title: string;
  updatedAt: number;
}
export interface ProposalItem {
  title: string;
  detail: string;
  kind?: string;
  name?: string | null;
  amount?: string | null;
  meal?: number | null;
  kcal?: number | null;
  protein?: number | null;
  carbs?: number | null;
  fat?: number | null;
  quantity?: number | null;
  servingLabel?: string | null;
  servingGrams?: number | null;
  source?: string | null;
  /** "substitute", "estimate", "match" or "differs". */
  badge?: string | null;
  note?: string | null;
  alternatives?: { foodId: string; name: string; kcal: number }[];
  editable?: boolean;
}
export interface Proposal {
  id: string;
  title: string;
  items: ProposalItem[];
  status: "pending" | "applied" | "dismissed";
  summary?: string | null;
}
/** A change to one row of a pending card. */
export interface ProposalItemEdit {
  remove?: boolean;
  meal?: number;
  quantity?: number;
  grams?: number;
  foodId?: string;
}
export const MEALS = ["Breakfast", "Lunch", "Dinner", "Snacks"];
export interface ChatImage {
  mimeType: string;
  /** base64 */
  data: string;
}
export type ChatBlock =
  | { type: "text"; text: string }
  | { type: "image"; mimeType: string; data: string }
  | { type: "tool"; name: string; label: string }
  | { type: "proposal"; proposal: Proposal };
export interface ChatMessage {
  role: "user" | "assistant";
  blocks: ChatBlock[];
}
export type ChatEvent =
  | { type: "conversation"; id: string; title: string }
  | { type: "text"; delta: string }
  | { type: "tool"; name: string; label: string }
  | { type: "proposal"; proposal: Proposal }
  | { type: "error"; message: string }
  | { type: "done" };

async function call<T>(token: string, path: string, method = "GET", body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api/${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, "Can't reach the server. Check your connection.");
  }
  if (!res.ok) throw await apiError(res);
  return (res.status === 204 ? undefined : await res.json()) as T;
}

async function apiError(res: Response): Promise<ApiError> {
  let msg = `Server error (HTTP ${res.status})`;
  try {
    msg = JSON.parse(await res.text()).error ?? msg;
  } catch {
    /* not JSON */
  }
  return new ApiError(res.status, msg);
}

/** The browser's region (ISO code, e.g. "CA"), so products sold there come first. */
export function browserCountry(): string | undefined {
  try {
    const region = new Intl.Locale(navigator.language).maximize().region;
    return region && /^[A-Z]{2}$/.test(region) ? region : undefined;
  } catch {
    return undefined;
  }
}

export const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

export const status = (token: string) => call<AiStatus>(token, "ai/status");
export const conversations = (token: string) => call<ConversationSummary[]>(token, "ai/conversations");
export const conversation = (token: string, id: string) =>
  call<{ id: string; title: string; messages: ChatMessage[] }>(token, `ai/conversations/${id}?today=${today()}`);
export const deleteConversation = (token: string, id: string) => call<void>(token, `ai/conversations/${id}`, "DELETE");
export const applyProposal = (token: string, id: string) => call<Proposal>(token, `ai/proposals/${id}/apply?today=${today()}`, "POST");
export const editProposalItem = (token: string, id: string, index: number, edit: ProposalItemEdit) =>
  call<Proposal>(token, `ai/proposals/${id}/items/${index}?today=${today()}`, "POST", edit);

/** Admin: the steps of the admin's own conversations. */
export interface TraceStep {
  type: "text" | "call" | "result" | "image" | "other";
  role?: string;
  name?: string;
  text?: string;
  data?: unknown;
}
export const traceConversations = (token: string) => call<ConversationSummary[]>(token, "admin/ai/conversations");
export const trace = (token: string, id: string) =>
  call<{ id: string; title: string; steps: TraceStep[]; proposals: Proposal[] }>(token, `admin/ai/conversations/${id}/trace?today=${today()}`);
export const undoProposal = (token: string, id: string) => call<Proposal>(token, `ai/proposals/${id}/undo?today=${today()}`, "POST");
export const dismissProposal = (token: string, id: string) => call<Proposal>(token, `ai/proposals/${id}/dismiss?today=${today()}`, "POST");

/** POST a message and yield the server-sent events of the reply as they arrive. */
export async function* chat(
  token: string,
  body: { conversationId?: string; text: string; images: ChatImage[] },
  signal: AbortSignal,
): AsyncGenerator<ChatEvent> {
  const now = new Date();
  let res: Response;
  try {
    res = await fetch("/api/ai/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream", Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        ...body,
        today: today(),
        localTime: `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`,
        country: browserCountry(),
      }),
      signal,
    });
  } catch (e) {
    if (signal.aborted) return;
    throw new ApiError(0, "Can't reach the server. Check your connection.");
  }
  if (!res.ok || !res.body) throw await apiError(res);
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buf = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += value;
      let idx: number;
      while ((idx = buf.indexOf("\n\n")) >= 0) {
        const block = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        let event = "message";
        let data = "";
        for (const line of block.split("\n")) {
          if (line.startsWith("event:")) event = line.slice(6).trim();
          else if (line.startsWith("data:")) data += line.slice(5).trim();
        }
        if (!data) continue;
        try {
          yield { type: event, ...JSON.parse(data) } as ChatEvent;
        } catch {
          /* ignore malformed event */
        }
      }
    }
  } catch (e) {
    if (signal.aborted) return;
    throw e;
  }
}

/** Downscale and JPEG-encode a picked image, like the Android app does (max 1280 px). */
export async function encodeImage(file: File, maxSide = 1280): Promise<ChatImage & { preview: string }> {
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const url = canvas.toDataURL("image/jpeg", 0.85);
  return { mimeType: "image/jpeg", data: url.slice(url.indexOf(",") + 1), preview: url };
}
