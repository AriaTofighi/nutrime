import { useEffect, useState } from "react";
import { Modal } from "../components/ui";
import { invitationRequest, type CreatedInvitation, type Invitation } from "../lib/invitations";
import { health } from "../lib/api";

const date = (value: number) => new Date(value).toLocaleString();
const status = (invite: Invitation, now: number) => invite.usedAt ? "Used" : invite.revokedAt ? "Revoked" : invite.expiresAt <= now ? "Expired" : "Available";

export default function Invitations({ token }: { token: string }) {
  const [items, setItems] = useState<Invitation[]>([]);
  const [email, setEmail] = useState("");
  const [days, setDays] = useState(7);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [attempt, setAttempt] = useState(0);
  const [created, setCreated] = useState<CreatedInvitation>();
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState<string>();
  const [now, setNow] = useState(Date.now());
  const [registrationPaused, setRegistrationPaused] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void health().then((server) => { if (!controller.signal.aborted) setRegistrationPaused(server?.registration === "false"); });
    void invitationRequest<Invitation[]>(token, "", undefined, controller.signal).then(
      (result) => { if (!controller.signal.aborted) { setItems(result); setLoading(false); } },
      (err: unknown) => { if (!controller.signal.aborted) { setError(err instanceof Error ? err.message : "Could not load invitations"); setLoading(false); } },
    );
    return () => controller.abort();
  }, [token, attempt]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const result = await invitationRequest<CreatedInvitation>(token, "", { email: email.trim(), days });
      setCreated(result);
      setCopied(false);
      setCopyError(undefined);
      setEmail("");
      setAttempt((value) => value + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create invitation");
    } finally { setBusy(false); }
  }

  async function revoke(invite: Invitation) {
    setBusy(true);
    setError(undefined);
    try {
      await invitationRequest(token, `/${encodeURIComponent(invite.id)}/revoke`, {});
      setAttempt((value) => value + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not revoke invitation");
    } finally { setBusy(false); }
  }

  async function copyCode() {
    if (!created) return;
    try {
      await navigator.clipboard.writeText(created.code);
      setCopied(true);
      setCopyError(undefined);
    } catch { setCopyError("Could not copy. Select the code and copy it manually."); }
  }

  return (
    <div className="page page-narrow">
      <header className="page-head">
        <h1 className="page-title">Invitations</h1>
        <a className="btn btn-secondary" href="#/profile">Back to profile</a>
      </header>
      <p className="hint">Each code lets one person create an account with the email address you choose. Share codes privately.</p>
      {registrationPaused && <p className="warn-note" role="status">New accounts are paused on this server. Codes can be used after the owner enables invitation registration.</p>}
      <form className="form" onSubmit={(event) => void create(event)}>
        <label className="field">
          <span>Friend’s email</span>
          <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} maxLength={320} autoComplete="off" required disabled={busy} />
        </label>
        <label className="field">
          <span>Expires in</span>
          <select value={days} onChange={(event) => setDays(Number(event.target.value))} disabled={busy}>
            <option value={1}>1 day</option><option value={7}>7 days</option><option value={30}>30 days</option>
          </select>
        </label>
        <div className="actions start"><button className="btn btn-primary" disabled={busy || !email.trim()}>Create invitation</button></div>
      </form>
      {error && <p className="hint danger" role="alert">{error}</p>}
      <div className="page-head invitation-history">
        <h2>Recent invitations</h2>
        <button className="btn btn-secondary" disabled={busy || loading} onClick={() => { setError(undefined); setAttempt((value) => value + 1); }}>Refresh</button>
      </div>
      {loading ? <p role="status">Loading invitations…</p> : items.length ? (
        <ul className="invitation-list">{items.map((invite) => (
          <li key={invite.id}>
            <div>
              <div className="strong invitation-email">{invite.email}</div>
              <div className="hint">{status(invite, now)} · {invite.usedAt ? date(invite.usedAt) : invite.revokedAt ? date(invite.revokedAt) : `Expires ${date(invite.expiresAt)}`}</div>
            </div>
            {status(invite, now) === "Available" && <button className="btn btn-ghost" disabled={busy} onClick={() => void revoke(invite)}>Revoke</button>}
          </li>
        ))}</ul>
      ) : !error && <p className="hint">No invitations yet.</p>}
      {created && <Modal title="Invitation created" onClose={() => setCreated(undefined)}>
        <div className="form">
          <p className="hint">For {created.invitation.email}. Expires {date(created.invitation.expiresAt)}. Copy this code now; it cannot be shown again.</p>
          <label className="field"><span>Invitation code</span><input readOnly value={created.code} autoComplete="off" spellCheck={false} onFocus={(event) => event.target.select()} /></label>
          {copyError && <p className="hint danger" role="alert">{copyError}</p>}
          <div className="actions">
            <button className="btn btn-secondary" onClick={() => setCreated(undefined)}>Done</button>
            <button className="btn btn-primary" onClick={() => void copyCode()}>{copied ? "Copied" : "Copy code"}</button>
          </div>
        </div>
      </Modal>}
    </div>
  );
}
