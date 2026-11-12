import { ArrowUp, Camera, Check, ChevronDown, ChevronUp, ImagePlus, LoaderCircle, MessagesSquare, Plus, Square, SquarePen, Trash2, X } from "lucide-react";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Markdown } from "../components/Markdown";
import { Empty, Modal, Segmented } from "../components/ui";
import * as ai from "../lib/assistant";
import { MEALS } from "../lib/assistant";
import type { ChatBlock, ChatImage, ChatMessage, ConversationSummary, Proposal, ProposalItem, ProposalItemEdit } from "../lib/assistant";
import { store } from "../lib/store";

const MAX_PHOTOS = 4;

interface State {
  conversationId?: string;
  title?: string;
  messages: ChatMessage[];
  streaming: boolean;
  loading: boolean;
  error?: string;
  status?: ai.AiStatus;
  conversations: ConversationSummary[];
  busy: Set<string>;
}

/**
 * Chat state lives outside React so a reply keeps streaming while you look at other tabs.
 * The server runs the agent; this keeps the conversation and talks to it.
 */
class AssistantSession {
  state: State = { messages: [], streaming: false, loading: false, conversations: [], busy: new Set() };
  private listeners = new Set<() => void>();
  private abort?: AbortController;

  subscribe = (fn: () => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };
  private set(patch: Partial<State>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l());
  }
  private get token() {
    return store.account?.token;
  }
  private message(e: unknown) {
    return e instanceof Error ? e.message : "Something went wrong";
  }

  async refresh() {
    const t = this.token;
    if (!t) return;
    try {
      this.set({ status: await ai.status(t) });
      this.set({ conversations: await ai.conversations(t) });
    } catch {
      /* offline: keep what we have */
    }
  }

  newChat() {
    this.abort?.abort();
    this.set({ conversationId: undefined, title: undefined, messages: [], error: undefined, loading: false });
  }

  async open(id: string) {
    const t = this.token;
    if (!t) return;
    this.abort?.abort();
    this.set({ conversationId: id, title: undefined, messages: [], loading: true, error: undefined });
    try {
      const c = await ai.conversation(t, id);
      if (this.state.conversationId === id) this.set({ messages: c.messages, title: c.title, loading: false });
    } catch (e) {
      this.set({ loading: false, error: this.message(e) });
    }
  }

  async remove(id: string) {
    const t = this.token;
    if (!t) return;
    await ai.deleteConversation(t, id).catch(() => undefined);
    if (this.state.conversationId === id) this.newChat();
    this.set({ conversations: this.state.conversations.filter((c) => c.id !== id) });
  }

  stop() {
    this.abort?.abort();
  }

  clearError() {
    this.set({ error: undefined });
  }

  private updateReply(fn: (blocks: ChatBlock[]) => ChatBlock[]) {
    const msgs = this.state.messages;
    const last = msgs[msgs.length - 1];
    if (!last || last.role !== "assistant") return;
    this.set({ messages: [...msgs.slice(0, -1), { ...last, blocks: fn(last.blocks) }] });
  }

  async send(text: string, images: ChatImage[]) {
    const t = this.token;
    if (!t || this.state.streaming || (!text.trim() && !images.length)) return;
    const userBlocks: ChatBlock[] = [
      ...images.map((i) => ({ type: "image" as const, mimeType: i.mimeType, data: i.data })),
      ...(text.trim() ? [{ type: "text" as const, text: text.trim() }] : []),
    ];
    this.set({
      messages: [...this.state.messages, { role: "user", blocks: userBlocks }, { role: "assistant", blocks: [] }],
      streaming: true,
      error: undefined,
    });
    const abort = new AbortController();
    this.abort = abort;
    try {
      // Push unsynced changes first, so the assistant sees what was just logged.
      await store.sync();
      for await (const e of ai.chat(t, { conversationId: this.state.conversationId, text: text.trim(), images }, abort.signal)) {
        if (e.type === "conversation") this.set({ conversationId: e.id, title: e.title });
        else if (e.type === "text")
          this.updateReply((b) => {
            const last = b[b.length - 1];
            return last?.type === "text" ? [...b.slice(0, -1), { type: "text", text: last.text + e.delta }] : [...b, { type: "text", text: e.delta }];
          });
        else if (e.type === "tool") this.updateReply((b) => [...b, { type: "tool", name: e.name, label: e.label }]);
        else if (e.type === "proposal")
          // One card per message: replace it if it's already shown.
          this.updateReply((b) =>
            b.some((x) => x.type === "proposal" && x.proposal.id === e.proposal.id)
              ? b.map((x) => (x.type === "proposal" && x.proposal.id === e.proposal.id ? { ...x, proposal: e.proposal } : x))
              : [...b, { type: "proposal", proposal: e.proposal }],
          );
        else if (e.type === "error") this.set({ error: e.message });
      }
    } catch (e) {
      this.set({ error: this.message(e) });
    } finally {
      const msgs = this.state.messages;
      const last = msgs[msgs.length - 1];
      this.set({ streaming: false, messages: last?.role === "assistant" && !last.blocks.length ? msgs.slice(0, -1) : msgs });
      void this.refresh();
    }
  }

  private replaceProposal(p: Proposal) {
    this.set({
      messages: this.state.messages.map((m) => ({
        ...m,
        blocks: m.blocks.map((b) => (b.type === "proposal" && b.proposal.id === p.id ? { ...b, proposal: p } : b)),
      })),
    });
  }

  /** Change one row of a pending card. Returns false if it failed (the error is shown). */
  async editItem(id: string, index: number, edit: ProposalItemEdit): Promise<boolean> {
    const t = this.token;
    if (!t || this.state.busy.has(id)) return false;
    this.set({ busy: new Set([...this.state.busy, id]) });
    try {
      this.replaceProposal(await ai.editProposalItem(t, id, index, edit));
      return true;
    } catch (e) {
      this.set({ error: this.message(e) });
      return false;
    } finally {
      const busy = new Set(this.state.busy);
      busy.delete(id);
      this.set({ busy });
    }
  }

  async resolve(id: string, apply: boolean, undo = false) {
    const t = this.token;
    if (!t || this.state.busy.has(id)) return;
    this.set({ busy: new Set([...this.state.busy, id]) });
    try {
      this.replaceProposal(undo ? await ai.undoProposal(t, id) : apply ? await ai.applyProposal(t, id) : await ai.dismissProposal(t, id));
      // Pull the changes into the diary right away.
      if (apply || undo) void store.sync();
    } catch (e) {
      this.set({ error: this.message(e) });
    } finally {
      const busy = new Set(this.state.busy);
      busy.delete(id);
      this.set({ busy });
    }
  }
}

export const assistant = new AssistantSession();

function useAssistant(): State {
  return useSyncExternalStore(assistant.subscribe, () => assistant.state);
}

export default function Assistant() {
  const s = useAssistant();
  const [history, setHistory] = useState(false);

  useEffect(() => {
    void assistant.refresh();
    // Opened with a prepared question (the weekly review from the Diary): ask it once, in a new chat.
    let prompt: string | null = null;
    try {
      prompt = sessionStorage.getItem("nutrime.assistantPrompt");
      sessionStorage.removeItem("nutrime.assistantPrompt");
    } catch {
      /* ignore */
    }
    if (prompt) {
      assistant.newChat();
      void assistant.send(prompt, []);
    }
  }, []);

  return (
    <div className="page page-narrow chat-page">
      <div className="chat-actions">
        <button className="icon-btn" aria-label="Past chats" title="Past chats" onClick={() => setHistory(true)}>
          <MessagesSquare size={18} />
        </button>
        <button className="icon-btn" aria-label="New chat" title="New chat" onClick={() => assistant.newChat()} disabled={!s.messages.length && !s.conversationId}>
          <SquarePen size={18} />
        </button>
      </div>

      {s.status?.enabled === false ? (
        <Empty title="The assistant isn't set up yet">
          Add a <code>GEMINI_API_KEY</code> to your NutriMe server's environment and redeploy it.
        </Empty>
      ) : (
        <Chat s={s} />
      )}

      {history && (
        <Modal title="Past chats" onClose={() => setHistory(false)}>
          {!s.conversations.length && <p className="muted">No chats yet</p>}
          <div className="chat-history">
            {s.conversations.map((c) => (
              <div key={c.id} className={`chat-history-row ${c.id === s.conversationId ? "on" : ""}`}>
                <button
                  className="chat-history-open"
                  onClick={() => {
                    setHistory(false);
                    void assistant.open(c.id);
                  }}
                >
                  <span className="chat-history-title">{c.title}</span>
                  <span className="chat-history-time">{new Date(c.updatedAt).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" })}</span>
                </button>
                <button className="icon-btn" aria-label="Delete chat" onClick={() => void assistant.remove(c.id)}>
                  <Trash2 size={16} />
                </button>
              </div>
            ))}
          </div>
        </Modal>
      )}
    </div>
  );
}

function greeting() {
  const h = new Date().getHours();
  return h < 5 ? "Late snack?" : h < 11 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
}

function Chat({ s }: { s: State }) {
  const [text, setText] = useState("");
  const [images, setImages] = useState<(ChatImage & { preview: string })[]>([]);
  const [attach, setAttach] = useState(false);
  const end = useRef<HTMLDivElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const cameraInput = useRef<HTMLInputElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const last = s.messages[s.messages.length - 1];
  const lastLen = last ? last.blocks.length + (last.blocks.at(-1)?.type === "text" ? (last.blocks.at(-1) as { text: string }).text.length : 0) : 0;
  const empty = !s.loading && !s.messages.length;

  useEffect(() => {
    end.current?.scrollIntoView({ block: "end", behavior: "smooth" });
  }, [s.messages.length, lastLen, s.error]);

  const addFiles = async (files: FileList | null) => {
    if (!files) return;
    const room = MAX_PHOTOS - images.length;
    const encoded = await Promise.all([...files].filter((f) => f.type.startsWith("image/")).slice(0, room).map((f) => ai.encodeImage(f).catch(() => null)));
    setImages((cur) => [...cur, ...encoded.filter((x): x is ChatImage & { preview: string } => !!x)].slice(0, MAX_PHOTOS));
  };

  const send = () => {
    if (s.streaming || (!text.trim() && !images.length)) return;
    void assistant.send(text, images.map(({ mimeType, data }) => ({ mimeType, data })));
    setText("");
    setImages([]);
    input.current?.focus();
  };

  const composer = (
    <div className="composer">
      <div className="composer-box">
        {!!images.length && (
          <div className="composer-images">
            {images.map((img, i) => (
              <div key={i} className="composer-image">
                <img src={img.preview} alt="Attached photo" />
                <button aria-label="Remove photo" onClick={() => setImages((cur) => cur.filter((_, j) => j !== i))}>
                  <X size={12} />
                </button>
              </div>
            ))}
          </div>
        )}
        <textarea
          ref={input}
          className="composer-input"
          rows={1}
          autoFocus={empty}
          placeholder={empty ? "Describe a meal, share a photo, or ask anything" : "Reply…"}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onPaste={(e) => {
            if (e.clipboardData.files.length) {
              e.preventDefault();
              void addFiles(e.clipboardData.files);
            }
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              send();
            }
          }}
        />
        <div className="composer-tools">
          <div className="composer-attach">
            <button className="icon-btn" aria-label="Add photos" aria-expanded={attach} disabled={images.length >= MAX_PHOTOS} onClick={() => setAttach(!attach)}>
              <Plus size={18} />
            </button>
            {attach && (
              <div className="composer-menu" role="menu" onMouseLeave={() => setAttach(false)}>
                <button role="menuitem" onClick={() => { setAttach(false); fileInput.current?.click(); }}>
                  <ImagePlus size={16} /> Upload photos
                </button>
                <button role="menuitem" className="show-touch" onClick={() => { setAttach(false); cameraInput.current?.click(); }}>
                  <Camera size={16} /> Take a photo
                </button>
              </div>
            )}
          </div>
          {s.streaming ? (
            <button className="composer-send" aria-label="Stop" onClick={() => assistant.stop()}>
              <Square size={12} fill="currentColor" />
            </button>
          ) : (
            <button className="composer-send" aria-label="Send" disabled={!text.trim() && !images.length} onClick={send}>
              <ArrowUp size={17} />
            </button>
          )}
        </div>
      </div>
      <input ref={fileInput} type="file" accept="image/*" multiple hidden onChange={(e) => void addFiles(e.target.files).then(() => (e.target.value = ""))} />
      <input ref={cameraInput} type="file" accept="image/*" capture="environment" hidden onChange={(e) => void addFiles(e.target.files).then(() => (e.target.value = ""))} />
    </div>
  );

  return (
    <div
      className={`chat ${empty ? "chat-empty" : ""}`}
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault();
        void addFiles(e.dataTransfer.files);
      }}
    >
      {empty ? (
        <div className="chat-start">
          <h1 className="chat-greeting">{greeting()}</h1>
          {composer}
        </div>
      ) : (
        <>
          <div className="chat-log">
            {s.loading && (
              <div className="chat-center">
                <LoaderCircle size={20} className="spin" />
              </div>
            )}
            {s.messages.map((m, i) =>
              m.role === "user" ? (
                <UserMessage key={i} m={m} />
              ) : (
                <AssistantMessage key={i} m={m} live={s.streaming && i === s.messages.length - 1} busy={s.busy} />
              ),
            )}
            {s.error && (
              <button className="chat-error" onClick={() => assistant.clearError()}>
                <span>{s.error}</span>
                <X size={14} />
              </button>
            )}
            <div ref={end} />
          </div>
          {composer}
        </>
      )}
    </div>
  );
}

function UserMessage({ m }: { m: ChatMessage }) {
  return (
    <div className="msg-user">
      {m.blocks.some((b) => b.type === "image") && (
        <div className="msg-images">
          {m.blocks.map((b, i) => b.type === "image" && <img key={i} src={`data:${b.mimeType};base64,${b.data}`} alt="Photo" />)}
        </div>
      )}
      {m.blocks.map((b, i) => b.type === "text" && <div key={i} className="msg-bubble">{b.text}</div>)}
    </div>
  );
}

type ToolBlock = Extract<ChatBlock, { type: "tool" }>;

/** A short summary of what the steps did, e.g. "Looked up foods, checked your diary · 4 steps". */
function stepsSummary(steps: ToolBlock[]): string {
  if (steps.length === 1) return steps[0].label;
  const names = new Set(steps.map((s) => s.name));
  const has = (...n: string[]) => n.some((x) => names.has(x));
  const did = [
    has("search_foods", "get_food", "get_frequent_foods") && "looked up foods",
    has("get_day", "get_nutrient_report", "get_weight_history") && "checked your diary",
    [...names].some((n) => n.startsWith("propose_")) && "prepared changes",
  ].filter(Boolean) as string[];
  const text = (did.length ? did : ["worked"]).join(", ");
  return `${text[0].toUpperCase()}${text.slice(1)} · ${steps.length} steps`;
}

function ToolSteps({ steps, running }: { steps: ToolBlock[]; running: boolean }) {
  const [open, setOpen] = useState(false);
  const many = steps.length > 1;
  const label = running ? `${steps[steps.length - 1].label}${many ? ` · step ${steps.length}` : ""}` : stepsSummary(steps);
  return (
    <div className="msg-steps">
      <button type="button" className="msg-tool" onClick={() => many && setOpen(!open)} disabled={!many} aria-expanded={many ? open : undefined}>
        {running ? <LoaderCircle size={12} className="spin" /> : <Check size={12} />}
        <span>{label}</span>
        {many && (open ? <ChevronUp size={12} /> : <ChevronDown size={12} />)}
      </button>
      {open && steps.map((s, i) => (
        <div key={i} className="msg-tool msg-step">
          <span>{s.label}</span>
        </div>
      ))}
    </div>
  );
}

/** One steps row for the whole reply, then the text, then the card. */
function AssistantMessage({ m, live, busy }: { m: ChatMessage; live: boolean; busy: Set<string> }) {
  const steps = m.blocks.filter((b): b is ToolBlock => b.type === "tool");
  const texts = m.blocks.filter((b) => b.type === "text");
  const cards = m.blocks.filter((b) => b.type === "proposal");
  const last = m.blocks[m.blocks.length - 1];
  const running = live && last?.type === "tool";
  return (
    <div className="msg-assistant">
      {!!steps.length && <ToolSteps steps={steps} running={running} />}
      {texts.map((b, i) => b.type === "text" && <Markdown key={i} text={b.text} />)}
      {cards.map((b) => b.type === "proposal" && <ProposalCard key={b.proposal.id} p={b.proposal} busy={busy.has(b.proposal.id)} />)}
      {live && !m.blocks.length && (
        <div className="msg-tool">
          <LoaderCircle size={12} className="spin" />
          <span>Thinking…</span>
        </div>
      )}
    </div>
  );
}

const BADGE_LABEL: Record<string, string> = { substitute: "Substitute", estimate: "Estimate", match: "Matches", differs: "Check" };
const COLLAPSE_AFTER = 6;

function ProposalCard({ p, busy }: { p: Proposal; busy: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const pending = p.status === "pending";
  const rows = p.items.map((item, index) => ({ item, index }));
  const shown = expanded || rows.length <= COLLAPSE_AFTER ? rows : rows.slice(0, COLLAPSE_AFTER - 1);
  // Group by meal when the card logs food for more than one meal.
  const meals = new Set(rows.map((r) => r.item.meal).filter((m) => m != null));
  const groups: { meal: number | null; rows: typeof rows }[] = [];
  for (const r of shown) {
    const meal = meals.size > 1 ? (r.item.meal ?? null) : null;
    const g = groups.find((x) => x.meal === meal);
    if (g) g.rows.push(r);
    else groups.push({ meal, rows: [r] });
  }
  const single = meals.size === 1 ? MEALS[[...meals][0] as number] : null;
  return (
    <div className="proposal">
      <div className="proposal-head">
        <div className="proposal-title">{p.title}</div>
        {(p.summary || single) && <div className="proposal-summary">{[single, p.summary].filter(Boolean).join(" · ")}</div>}
      </div>
      {groups.map((g, gi) => (
        <div key={gi} className="proposal-group">
          {g.meal != null && <div className="proposal-meal">{MEALS[g.meal]}</div>}
          {g.rows.map(({ item, index }) => (
            <button key={index} type="button" className="proposal-item" disabled={!pending || busy} onClick={() => setEditing(index)}>
              <span className="proposal-name">
                {item.name ?? item.title}
                {item.badge && <span className={`proposal-badge ${item.badge}`}>{BADGE_LABEL[item.badge] ?? item.badge}</span>}
              </span>
              <span className="proposal-detail">
                {item.kind === "log_food" || item.kind === "quick_add"
                  ? [item.amount, item.kcal != null ? `${Math.round(item.kcal)} kcal` : null, meals.size > 1 || single ? null : item.meal != null ? MEALS[item.meal] : null].filter(Boolean).join(" · ")
                  : item.detail}
              </span>
              {item.note && <span className={`proposal-note ${item.badge ?? ""}`}>{item.note}</span>}
            </button>
          ))}
        </div>
      ))}
      {rows.length > shown.length && (
        <button type="button" className="proposal-more" onClick={() => setExpanded(true)}>
          Show {rows.length - shown.length} more
        </button>
      )}
      <div className="proposal-foot">
        {p.status === "applied" && (
          <>
            <span className="proposal-done">
              <Check size={15} /> Saved to your diary
            </span>
            <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => void assistant.resolve(p.id, false, true)}>
              {busy && <LoaderCircle size={14} className="spin" />} Undo
            </button>
          </>
        )}
        {p.status === "dismissed" && <span className="muted">Dismissed</span>}
        {pending && (
          <>
            <span className="proposal-hint">Tap an item to change it</span>
            <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => void assistant.resolve(p.id, false)}>
              Dismiss
            </button>
            <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => void assistant.resolve(p.id, true)}>
              {busy ? <LoaderCircle size={14} className="spin" /> : <Check size={14} />} Confirm
            </button>
          </>
        )}
      </div>
      {editing != null && p.items[editing] && (
        <ItemEditor proposalId={p.id} index={editing} item={p.items[editing]} busy={busy} onClose={() => setEditing(null)} />
      )}
    </div>
  );
}

function ItemEditor({ proposalId, index, item, busy, onClose }: { proposalId: string; index: number; item: ProposalItem; busy: boolean; onClose: () => void }) {
  const byGrams = item.servingLabel === "g";
  const [amount, setAmount] = useState(item.quantity != null ? String(Math.round(item.quantity * 100) / 100) : "");
  const [meal, setMeal] = useState(item.meal ?? 0);
  const [foodId, setFoodId] = useState<string | null>(null);
  const editable = !!item.editable;
  const canAmount = editable && item.quantity != null;

  const save = async () => {
    const edit: ProposalItemEdit = {};
    const q = Number(amount.replace(",", "."));
    if (canAmount && amount && Number.isFinite(q) && q > 0 && Math.abs(q - (item.quantity ?? 0)) > 1e-9) {
      if (byGrams) edit.grams = q;
      else edit.quantity = q;
    }
    if (editable && item.meal != null && meal !== item.meal) edit.meal = meal;
    if (foodId) edit.foodId = foodId;
    if (!Object.keys(edit).length) return onClose();
    if (await assistant.editItem(proposalId, index, edit)) onClose();
  };
  const remove = async () => {
    if (await assistant.editItem(proposalId, index, { remove: true })) onClose();
  };

  return (
    <Modal title={item.name ?? item.title} subtitle={[item.source, item.kcal != null ? `${Math.round(item.kcal)} kcal` : null].filter(Boolean).join(" · ") || undefined} onClose={onClose}>
      <div className="item-editor">
        {item.note && <p className={`proposal-note ${item.badge ?? ""}`}>{item.note}</p>}
        {canAmount && (
          <label className="field">
            <span className="field-label">Amount</span>
            <div className="item-amount">
              <input className="input num" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
              <span className="muted">{byGrams ? "g" : `× ${item.servingLabel}`}</span>
            </div>
          </label>
        )}
        {editable && item.meal != null && (
          <div className="field">
            <span className="field-label">Meal</span>
            <Segmented options={MEALS.map((m, i) => ({ value: i, label: m }))} value={meal} onChange={(v) => setMeal(v)} />
          </div>
        )}
        {!!item.alternatives?.length && (
          <div className="field">
            <span className="field-label">Other matches</span>
            <div className="item-alts">
              {item.alternatives.map((a) => (
                <button key={a.foodId} type="button" className={`item-alt ${foodId === a.foodId ? "on" : ""}`} onClick={() => setFoodId(foodId === a.foodId ? null : a.foodId)}>
                  <span>{a.name}</span>
                  <span className="muted num">{Math.round(a.kcal)} kcal</span>
                </button>
              ))}
            </div>
          </div>
        )}
        <div className="actions">
          <button className="btn btn-danger-ghost" disabled={busy} onClick={() => void remove()}>
            <Trash2 size={15} /> Remove
          </button>
          <span className="spacer" />
          <button className="btn btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={busy} onClick={() => void save()}>
            {busy ? <LoaderCircle size={14} className="spin" /> : <Check size={14} />} Save
          </button>
        </div>
      </div>
    </Modal>
  );
}
