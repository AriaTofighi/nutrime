import { Activity, BookOpen, ChartLine, CircleUserRound, Cloud, CloudOff, Database as DatabaseIcon, LoaderCircle, Monitor, Moon, Salad, Sun, UserRound } from "lucide-react";
import { useEffect, useState } from "react";
import { AssistantIcon } from "./components/icons";
import { Logo } from "./components/Logo";
import { store, useStore } from "./lib/store";
import { timeAgo } from "./lib/util";
import Assistant from "./screens/Assistant";
import Auth from "./screens/Auth";
import Diary from "./screens/Diary";
import Foods from "./screens/Foods";
import Profile from "./screens/Profile";
import Trends from "./screens/Trends";
import Database from "./screens/Database";
import Traces from "./screens/Traces";
import Invitations from "./screens/Invitations";
import { useAdminAccess } from "./lib/adminFoods";
import { setTheme, useTheme, type ThemeMode } from "./theme/theme";

const TABS = [
  { id: "diary", label: "Diary", Icon: BookOpen },
  { id: "trends", label: "Trends", Icon: ChartLine },
  { id: "assistant", label: "Assistant", Icon: AssistantIcon },
  { id: "foods", label: "Foods", Icon: Salad },
  { id: "profile", label: "Profile", Icon: UserRound },
  { id: "database", label: "Database", Icon: DatabaseIcon },
  { id: "traces", label: "Traces", Icon: Activity },
  { id: "invitations", label: "Invitations", Icon: UserRound },
] as const;
type Tab = (typeof TABS)[number]["id"];

const tabFromHash = (): Tab => {
  const h = window.location.hash.replace(/^#\/?/, "");
  return (TABS.find((t) => t.id === h)?.id ?? "diary") as Tab;
};

function SyncStatus() {
  const s = store.status;
  const Icon = s.running ? LoaderCircle : s.error ? CloudOff : Cloud;
  const label = s.running ? "Syncing" : s.error ? "Offline" : s.pending ? `${s.pending} to sync` : "Synced";
  return (
    <button
      className={`sync ${s.error ? "sync-err" : s.running || s.pending ? "sync-busy" : ""}`}
      onClick={() => void store.sync()}
      title={s.error ?? `Last synced ${timeAgo(s.lastSync)}. Click to sync now.`}
    >
      <Icon size={15} className={s.running ? "spin" : ""} />
      <span>{label}</span>
    </button>
  );
}

function ThemeToggle() {
  const t = useTheme();
  const modes: { id: ThemeMode; Icon: typeof Sun; label: string }[] = [
    { id: "light", Icon: Sun, label: "Light" },
    { id: "dark", Icon: Moon, label: "Dark" },
    { id: "system", Icon: Monitor, label: "System" },
  ];
  return (
    <div className="theme-toggle" role="radiogroup" aria-label="Theme">
      {modes.map(({ id, Icon, label }) => (
        <button key={id} role="radio" aria-checked={t.mode === id} className={t.mode === id ? "on" : ""} onClick={() => setTheme({ mode: id })} title={label}>
          <Icon size={14} />
        </button>
      ))}
    </div>
  );
}

export default function App() {
  const s = useStore();
  const [tab, setTab] = useState<Tab>(tabFromHash);
  const admin = useAdminAccess(s.account?.token);

  useEffect(() => {
    const onHash = () => {
      setTab(tabFromHash());
      window.scrollTo(0, 0);
    };
    window.addEventListener("hashchange", onHash);
    // Pull changes from other devices when the tab regains focus, and every minute while open.
    const onFocus = () => document.visibilityState === "visible" && store.account && void store.sync();
    document.addEventListener("visibilitychange", onFocus);
    const iv = window.setInterval(() => store.account && document.visibilityState === "visible" && void store.sync(), 60_000);
    return () => {
      window.removeEventListener("hashchange", onHash);
      document.removeEventListener("visibilitychange", onFocus);
      window.clearInterval(iv);
    };
  }, []);

  if (!s.loaded) return <div className="splash"><Logo size={36} /></div>;
  if (!s.account) return <Auth />;

  const email = s.account.email;
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <Logo size={28} />
          <span>NutriMe</span>
        </div>
        <nav className="nav" aria-label="Main">
          {/* Admin pages open from Profile, not the main navigation. */}
          {TABS.filter((t) => t.id !== "database" && t.id !== "traces" && t.id !== "invitations").map(({ id, label, Icon }) => (
            <a key={id} href={`#/${id}`} className={tab === id || (id === "profile" && (tab === "database" || tab === "traces" || tab === "invitations")) ? "on" : ""} aria-current={tab === id ? "page" : undefined}>
              <Icon size={19} strokeWidth={tab === id ? 2.2 : 1.8} />
              <span>{label}</span>
            </a>
          ))}
        </nav>
        <div className="sidebar-foot">
          <SyncStatus />
          <ThemeToggle />
          <a href="#/profile" className="account-chip" title={email}>
            <CircleUserRound size={18} strokeWidth={1.75} aria-hidden />
            <span className="account-email">{email}</span>
          </a>
        </div>
      </aside>

      <header className="mobile-bar">
        <div className="sidebar-brand">
          <Logo size={24} />
          <span>NutriMe</span>
        </div>
        <SyncStatus />
      </header>

      <main className="main">
        {tab === "diary" && <Diary />}
        {tab === "trends" && <Trends />}
        {tab === "assistant" && <Assistant />}
        {tab === "foods" && <Foods />}
        {tab === "profile" && <Profile admin={admin.allowed} />}
        {tab === "traces" && admin.allowed && <Traces key={s.account.token} token={s.account.token} />}
        {tab === "invitations" && (admin.allowed ? <Invitations key={s.account.token} token={s.account.token} /> : (
          <div className="page page-narrow">
            <h1 className="page-title">Invitations</h1>
            <p role="status">{admin.loading ? "Checking access…" : admin.error ?? "Administrator access is required."}</p>
            {admin.error && <button className="btn btn-secondary" onClick={admin.retry}>Try again</button>}
          </div>
        ))}
        {tab === "database" && (admin.allowed ? <Database key={s.account.token} token={s.account.token} /> : (
          <div className="page page-narrow">
            <h1 className="page-title">Food database</h1>
            <p role="status">{admin.loading ? "Checking access…" : admin.error ?? "This page is only available to the NutriMe administrator."}</p>
            {admin.error && <button className="btn btn-secondary" onClick={admin.retry}>Try again</button>}
          </div>
        ))}
      </main>
    </div>
  );
}
