import { Fragment, type ReactNode } from "react";

/** The small subset of Markdown the assistant writes: headings, bullet and numbered lists, bold, italic, code. */
type Block = { kind: "h"; text: string } | { kind: "p"; text: string } | { kind: "li"; marker: string; text: string; level: number };

export function parseMarkdown(src: string): Block[] {
  const out: Block[] = [];
  let para: string[] = [];
  const flush = () => {
    if (para.length) out.push({ kind: "p", text: para.join(" ") });
    para = [];
  };
  for (const raw of src.split("\n")) {
    const indent = raw.length - raw.trimStart().length;
    const line = raw.trim();
    const bullet = /^[-*•+]\s+(.*)/.exec(line);
    const numbered = /^(\d+)[.)]\s+(.*)/.exec(line);
    if (!line) flush();
    else if (line.startsWith("#")) {
      flush();
      out.push({ kind: "h", text: line.replace(/^#+/, "").trim() });
    } else if (bullet) {
      flush();
      out.push({ kind: "li", marker: "•", text: bullet[1], level: Math.floor(indent / 2) });
    } else if (numbered) {
      flush();
      out.push({ kind: "li", marker: `${numbered[1]}.`, text: numbered[2], level: Math.floor(indent / 2) });
    } else if (/^-{3,}$/.test(line)) flush();
    else para.push(line);
  }
  flush();
  return out;
}

const INLINE = /\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)/g;

function inline(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  let i = 0;
  for (const m of text.matchAll(INLINE)) {
    out.push(text.slice(i, m.index));
    const k = m.index;
    if (m[1] || m[2]) out.push(<strong key={k}>{m[1] || m[2]}</strong>);
    else if (m[3]) out.push(<code key={k}>{m[3]}</code>);
    else out.push(<em key={k}>{m[4] || m[5]}</em>);
    i = m.index + m[0].length;
  }
  out.push(text.slice(i));
  return out;
}

export function Markdown({ text }: { text: string }) {
  return (
    <div className="md">
      {parseMarkdown(text).map((b, i) => (
        <Fragment key={i}>
          {b.kind === "h" && <p className="md-h">{inline(b.text)}</p>}
          {b.kind === "p" && <p>{inline(b.text)}</p>}
          {b.kind === "li" && (
            <div className="md-li" style={{ paddingLeft: b.level * 14 }}>
              <span className="md-marker">{b.marker}</span>
              <span>{inline(b.text)}</span>
            </div>
          )}
        </Fragment>
      ))}
    </div>
  );
}
