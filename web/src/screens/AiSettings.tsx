import { Bot, Globe, KeyRound, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import { Badge } from "@/components/shadcn/badge";
import { Button } from "@/components/shadcn/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/shadcn/card";
import { Input } from "@/components/shadcn/input";
import { Label } from "@/components/shadcn/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/shadcn/select";
import { Separator } from "@/components/shadcn/separator";
import { Switch } from "@/components/shadcn/switch";
import { aiSettingsRequest, type AiSettingsPatch, type AiSettingsView } from "@/lib/aiSettings";

const LEVEL_LABEL: Record<string, string> = {
  default: "Model default",
  minimal: "Minimal (fastest)",
  low: "Low",
  medium: "Medium",
  high: "High",
  xhigh: "Extra high (most careful)",
};

interface Draft {
  model: string;
  thinkingLevel: string;
  dailyMessages: string;
  webOn: boolean;
  webLimit: string;
}

const draftFrom = (v: AiSettingsView): Draft => ({
  model: v.effective.model,
  thinkingLevel: v.effective.thinkingLevel ?? "default",
  dailyMessages: String(v.effective.dailyMessages),
  webOn: v.effective.webLookupDailyLimit > 0,
  webLimit: String(v.effective.webLookupDailyLimit > 0 ? v.effective.webLookupDailyLimit : v.environment.webLookupDailyLimit || 30),
});

/** Admin: the assistant's model, thinking level and limits. Saved on the server; no redeploy needed. */
export default function AiSettings({ token }: { token: string }) {
  const [view, setView] = useState<AiSettingsView>();
  const [draft, setDraft] = useState<Draft>();
  const [error, setError] = useState<string>();
  const [saved, setSaved] = useState<string>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    aiSettingsRequest(token, "GET", undefined, controller.signal).then(
      (v) => { setView(v); setDraft(draftFrom(v)); },
      (e: unknown) => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : "Could not load the settings"); },
    );
    return () => controller.abort();
  }, [token]);

  const run = async (method: "PUT" | "DELETE", body?: AiSettingsPatch) => {
    setBusy(true);
    setError(undefined);
    setSaved(undefined);
    try {
      const v = await aiSettingsRequest(token, method, body);
      setView(v);
      setDraft(draftFrom(v));
      setSaved(method === "DELETE" ? "Server defaults restored" : "Saved. New messages use these settings.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not save");
    } finally {
      setBusy(false);
    }
  };

  if (!view || !draft) {
    return (
      <div className="page page-narrow">
        <h1 className="page-title">Assistant settings</h1>
        <p role="status" className="hint">{error ?? "Loading…"}</p>
      </div>
    );
  }

  const env = view.environment;
  const set = (patch: Partial<Draft>) => { setDraft({ ...draft, ...patch }); setSaved(undefined); };
  const int = (s: string) => (s.trim() === "" ? NaN : Number(s));
  const daily = int(draft.dailyMessages);
  const webLimit = draft.webOn ? int(draft.webLimit) : 0;
  const valid = draft.model.trim() !== "" && Number.isInteger(daily) && daily >= 0 && Number.isInteger(webLimit) && webLimit >= 0;
  // Store only values that differ from the environment, so a later change to the environment still applies.
  const patch: AiSettingsPatch = {
    model: draft.model.trim() !== env.model ? draft.model.trim() : null,
    thinkingLevel: draft.thinkingLevel !== (env.thinkingLevel ?? "default") ? draft.thinkingLevel : null,
    dailyMessages: daily !== env.dailyMessages ? daily : null,
    webLookupDailyLimit: webLimit !== env.webLookupDailyLimit ? webLimit : null,
  };
  const current = draftFrom(view);
  const dirty = JSON.stringify(current) !== JSON.stringify(draft);
  const overridden = Object.values(view.overrides).some((v) => v != null);

  return (
    <div className="page page-narrow">
      <header className="page-head">
        <div>
          <h1 className="page-title">Assistant settings</h1>
          <p className="page-sub">Changes apply to the next message. You do not need to redeploy.</p>
        </div>
      </header>

      <div className="flex flex-col gap-4">
        {!view.apiKeySet && (
          <Card className="border-warning/40 py-4">
            <CardContent className="flex items-start gap-3">
              <KeyRound className="mt-0.5 size-4 text-warning" />
              <p className="text-[0.8125rem] text-muted-foreground">
                The assistant is off: the server has no <code>GEMINI_API_KEY</code>. Add it to the server's environment and redeploy. You can still change these settings.
              </p>
            </CardContent>
          </Card>
        )}

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Bot className="size-4 text-primary" /> Model</CardTitle>
            <CardDescription>The Gemini model for the assistant and for web lookups.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="ai-model">Model ID</Label>
              <Input id="ai-model" value={draft.model} spellCheck={false} autoCapitalize="off" onChange={(e) => set({ model: e.target.value })} placeholder="gemini-4-hydrogen" />
              <p className="text-xs text-subtle-foreground">Server default: <code>{env.model}</code>. An unknown ID makes the assistant fail.</p>
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="ai-thinking">Thinking level</Label>
              <Select value={draft.thinkingLevel} onValueChange={(v) => set({ thinkingLevel: v })}>
                <SelectTrigger id="ai-thinking"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {["default", ...view.thinkingLevels].map((l) => <SelectItem key={l} value={l}>{LEVEL_LABEL[l] ?? l}</SelectItem>)}
                </SelectContent>
              </Select>
              <p className="text-xs text-subtle-foreground">Lower is faster and costs less. Higher is more careful with long lists and photos.</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Globe className="size-4 text-primary" /> Web search</CardTitle>
            <CardDescription>
              When a food is not in the database, the assistant can look up published nutrition with Google Search. Each lookup is one extra Gemini request.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="flex items-center justify-between gap-4">
              <Label htmlFor="ai-web">Allow web lookups</Label>
              <Switch id="ai-web" checked={draft.webOn} onCheckedChange={(on) => set({ webOn: on })} />
            </div>
            {draft.webOn && (
              <div className="flex flex-col gap-2">
                <Label htmlFor="ai-web-limit">Lookups per person per day</Label>
                <Input id="ai-web-limit" className="max-w-32 num" inputMode="numeric" value={draft.webLimit} onChange={(e) => set({ webLimit: e.target.value.replace(/\D/g, "") })} />
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Limits</CardTitle>
            <CardDescription>Messages each person can send in 24 hours.</CardDescription>
          </CardHeader>
          <CardContent>
            <Input aria-label="Messages per person per day" className="max-w-32 num" inputMode="numeric" value={draft.dailyMessages} onChange={(e) => set({ dailyMessages: e.target.value.replace(/\D/g, "") })} />
          </CardContent>
          <Separator />
          <CardFooter className="flex-wrap">
            <Button disabled={!dirty || !valid || busy} onClick={() => void run("PUT", patch)}>{busy ? "Saving…" : "Save"}</Button>
            <Button variant="ghost" disabled={!dirty || busy} onClick={() => { setDraft(current); setSaved(undefined); }}>Cancel</Button>
            <span className="grow" />
            {overridden && (
              <Button variant="ghost" size="sm" disabled={busy} onClick={() => void run("DELETE")}><RotateCcw /> Use server defaults</Button>
            )}
          </CardFooter>
        </Card>

        {(error || saved) && <p role="status" className={error ? "text-[0.8125rem] text-destructive" : "text-[0.8125rem] text-primary"}>{error ?? saved}</p>}
        {overridden && <p className="text-xs text-subtle-foreground"><Badge variant="secondary">Changed</Badge> Some values differ from the server's environment variables. The values on this page win.</p>}
      </div>
    </div>
  );
}
