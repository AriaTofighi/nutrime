import { LoaderCircle } from "lucide-react";
import { useEffect, useState } from "react";
import { Logo } from "../components/Logo";
import * as api from "../lib/api";
import { store } from "../lib/store";

export default function Auth() {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [invitationCode, setInvitationCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(store.status.error);
  const [server, setServer] = useState<{ status: string; registration: string } | null | undefined>();

  useEffect(() => {
    void api.health().then(setServer);
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const res = mode === "login" ? await api.login(email, password) : await api.register(email, password, invitationCode.trim());
      setPassword("");
      setInvitationCode("");
      await store.signIn(res);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const registrationOpen = server?.registration === "true";

  return (
    <div className="auth">
      <div className="auth-glow" aria-hidden />
      <div className="auth-card">
        <Logo size={44} />
        <h1 className="auth-title">{mode === "login" ? "Welcome back" : "Create your account"}</h1>
        <p className="auth-sub">Calories, macros and 40+ micronutrients, in sync with the NutriMe Android app.</p>
        <form className="form" onSubmit={submit}>
          <label className="field">
            <span>Email</span>
            <input className="input-lg" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
          </label>
          <label className="field">
            <span>Password</span>
            <input
              className="input-lg"
              type="password"
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={mode === "register" ? 8 : undefined}
              maxLength={72}
              required
            />
          </label>
          {mode === "register" && (
            <label className="field">
              <span>Invitation code</span>
              <input className="input-lg" value={invitationCode} onChange={(e) => setInvitationCode(e.target.value)}
                autoComplete="off" autoCapitalize="none" spellCheck={false} maxLength={100} required />
              <span className="hint">Ask the server owner for a code. Use the email address they invited.</span>
            </label>
          )}
          {error && <p className="hint danger">{error}</p>}
          <button className="btn btn-primary btn-lg btn-block" disabled={busy}>
            {busy && <LoaderCircle size={17} className="spin" />}
            {mode === "login" ? "Sign in" : "Create account"}
          </button>
        </form>
        {mode === "login" && registrationOpen && (
          <p className="auth-switch">
            Have an invitation?{" "}
            <button className="link" onClick={() => { setMode("register"); setError(undefined); }}>
              Create an account
            </button>
          </p>
        )}
        {mode === "register" && (
          <p className="auth-switch">
            Already have an account?{" "}
            <button className="link" onClick={() => { setMode("login"); setInvitationCode(""); setError(undefined); }}>
              Sign in
            </button>
          </p>
        )}
        <p className={`server-status ${server === null ? "danger" : ""}`}>
          <i className={`status-dot ${server ? "ok" : server === null ? "err" : ""}`} />
          {server === undefined ? "Connecting…" : server ? "Server online" : "Server unreachable"}
        </p>
      </div>
    </div>
  );
}
