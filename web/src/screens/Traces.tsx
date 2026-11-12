import { RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { Card, Empty } from "../components/ui";
import * as ai from "../lib/assistant";
import type { ConversationSummary, TraceStep } from "../lib/assistant";

/** Admin: every step of your own assistant conversations, to see why the assistant did something. */
export default function Traces({ token }: { token: string }) {
  const [list, setList] = useState<ConversationSummary[]>([]);
  const [selected, setSelected] = useState<string>();
  const [trace, setTrace] = useState<Awaited<ReturnType<typeof ai.trace>>>();
  const [error, setError] = useState<string>();
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    ai.traceConversations(token).then(
      (l) => {
        setList(l);
        setSelected((cur) => cur ?? l[0]?.id);
      },
      (e: Error) => setError(e.message),
    );
  }, [token, attempt]);

  useEffect(() => {
    setTrace(undefined);
    if (!selected) return;
    let live = true;
    ai.trace(token, selected).then(
      (t) => live && setTrace(t),
      (e: Error) => live && setError(e.message),
    );
    return () => {
      live = false;
    };
  }, [token, selected, attempt]);

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1 className="page-title">Assistant traces</h1>
          <p className="page-sub">Every step of your own assistant chats: what the model called, with which arguments, and what came back.</p>
        </div>
        <div className="head-actions">
          <button className="btn btn-secondary" onClick={() => setAttempt((n) => n + 1)}>
            <RefreshCw size={16} /> Refresh
          </button>
        </div>
      </header>
      {error && <p className="muted" role="alert">{error}</p>}
      {!list.length && !error ? (
        <Empty title="No chats yet">Chat with the assistant, then come back here.</Empty>
      ) : (
        <div className="trace-layout">
          <Card>
            <div className="trace-list">
              {list.map((c) => (
                <button key={c.id} className={c.id === selected ? "on" : ""} onClick={() => setSelected(c.id)}>
                  {c.title}
                  <div className="muted">{new Date(c.updatedAt).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" })}</div>
                </button>
              ))}
            </div>
          </Card>
          <div className="trace-steps">
            {trace?.steps.map((s, i) => <Step key={i} s={s} />)}
            {!!trace?.proposals.length && (
              <div className="trace-step">
                <div className="trace-step-head">Cards</div>
                <pre>{JSON.stringify(trace.proposals, null, 2)}</pre>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Step({ s }: { s: TraceStep }) {
  const head =
    s.type === "call" ? `Call · ${s.name}` : s.type === "result" ? `Result · ${s.name}` : s.type === "image" ? `${s.role} · photo` : `${s.role === "user" ? "User" : "Model"} · text`;
  return (
    <div className={`trace-step ${s.role === "user" && s.type !== "result" ? "user" : ""}`}>
      <div className="trace-step-head">{head}</div>
      {s.type === "text" && <pre>{s.text}</pre>}
      {(s.type === "call" || s.type === "result") && <pre>{JSON.stringify(s.data, null, 2)}</pre>}
    </div>
  );
}
