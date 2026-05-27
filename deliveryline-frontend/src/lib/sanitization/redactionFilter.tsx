/**
 * Story 2.24 — frontend redaction filter (AC15, AC17).
 *
 * Defense-in-depth second-pass: runs over rendered text AFTER sanitization
 * and BEFORE display. Matches the canonical pattern set from
 * `redaction-policy.generated.json` (the build-time mirror of
 * `deliveryline-runner-contracts/src/main/resources/runner-contracts/redaction-policy.json`)
 * and wraps each hit in `<mark class="redaction-applied">[REDACTED: <CATEGORY>]</mark>`.
 *
 * Trap T5 — runs on RENDERED text tree, not raw markdown source.
 * Trap T11 — author-written literal `[REDACTED]` strings (no `<mark>` wrapper)
 *            render unchanged; only newly-detected patterns get wrapped.
 */
import {
  Children,
  cloneElement,
  type ReactElement,
  type ReactNode,
  isValidElement,
} from 'react';

import redactionPolicy from './redaction-policy.generated.json';

interface RedactionPolicyFile {
  schemaVersion: number;
  patterns: Array<{
    category: string;
    placeholder: string;
    regex: string;
    flags?: string;
  }>;
  secretFieldNames: string[];
}

interface CompiledPattern {
  category: string;
  placeholder: string;
  regex: RegExp;
}

const policy = redactionPolicy as RedactionPolicyFile;

/**
 * Compile patterns once at module load. Each regex carries the `g` flag
 * (we use `.matchAll`) and any policy-declared flags (`i`/`m`/`s` etc.).
 */
const COMPILED_PATTERNS: CompiledPattern[] = policy.patterns.map((entry) => {
  const flags = entry.flags ?? '';
  const withGlobal = flags.includes('g') ? flags : `${flags}g`;
  return {
    category: entry.category,
    placeholder: entry.placeholder,
    regex: new RegExp(entry.regex, withGlobal),
  };
});

export interface RedactionMatch {
  start: number;
  end: number;
  category: string;
}

export interface RedactionScanResult {
  /** Original input text. */
  source: string;
  /** Matches with byte offsets (string-index offsets — JS code-unit indices). */
  matches: RedactionMatch[];
  /** Unique set of detected categories, deterministically ordered. */
  detectedCategories: string[];
}

/**
 * Scan `text` for redaction patterns. Returns the matches with start/end
 * offsets and the set of detected categories.
 *
 * The function is pure and does NOT log the matched text — production
 * silence is enforced by AC18's "Forbidden in log output" rule and Trap
 * T11's "no signal to attackers" semantics. Logging happens at the
 * {@link renderTextWithRedactions} site (dev-mode only).
 */
export function scanForRedactions(text: string): RedactionScanResult {
  const matches: RedactionMatch[] = [];
  for (const pattern of COMPILED_PATTERNS) {
    pattern.regex.lastIndex = 0;
    for (const match of text.matchAll(pattern.regex)) {
      matches.push({
        start: match.index,
        end: match.index + match[0].length,
        category: pattern.category,
      });
    }
  }
  // Resolve overlap by preferring earlier-start + longer match.
  matches.sort((a, b) => (a.start - b.start) || (b.end - a.end));
  const filtered: RedactionMatch[] = [];
  let lastEnd = -1;
  for (const m of matches) {
    if (m.start >= lastEnd) {
      filtered.push(m);
      lastEnd = m.end;
    }
  }
  const detected = Array.from(new Set(filtered.map((m) => m.category))).sort();
  return { source: text, matches: filtered, detectedCategories: detected };
}

/**
 * Render `text` as a React fragment, wrapping each matched span in
 * `<mark class="redaction-applied">[REDACTED: CATEGORY]</mark>`.
 *
 * Trap T11 — only NEWLY detected patterns get wrapped. Backend-applied
 * placeholders like `[REDACTED_GITHUB_TOKEN]` already render as inert text
 * and never match our patterns (they don't look like the original secret).
 * Author-written literal `[REDACTED]` strings remain plain text.
 */
function renderStringWithRedactions(text: string, baseKey: string): ReactNode {
  const result = scanForRedactions(text);
  if (result.matches.length === 0) {
    if (import.meta.env.DEV && text.length > 0) {
      // Dev-mode telemetry only — no-op in prod (Trap T11).
    }
    return text;
  }
  if (import.meta.env.DEV) {
    // Category names only, never the matched text. Production silent.
    console.warn(
      '[sanitization] redaction applied categories=%o count=%d',
      result.detectedCategories,
      result.matches.length,
    );
  }
  const parts: ReactNode[] = [];
  let cursor = 0;
  result.matches.forEach((match, index) => {
    if (match.start > cursor) {
      parts.push(text.slice(cursor, match.start));
    }
    parts.push(
      <mark
        key={`${baseKey}-${index}`}
        className="redaction-applied"
        title="Redaction applied at render time — audit log records the original location"
      >
        [REDACTED: {match.category}]
      </mark>,
    );
    cursor = match.end;
  });
  if (cursor < text.length) {
    parts.push(text.slice(cursor));
  }
  return <>{parts}</>;
}

/**
 * Walk a React tree and apply redaction wrapping to every text node in the
 * subtree, recursing into nested elements (e.g., `<strong>`, `<em>`,
 * `<code>`, `<a>`'s text content). Code-review P1 (2026-05-26) extended the
 * walker to descend into element children — the previous shallow version
 * left `<strong>ghp_…</strong>` and inline-code secrets unredacted, a
 * substantial F19/F20 gap.
 *
 * The walker preserves element type, key, and non-children props; it only
 * rewrites the `children` prop of each visited element. Components that
 * receive functions as children (rare in markdown output) are passed
 * through unchanged.
 */
export function renderTextWithRedactions(children: ReactNode): ReactNode {
  const flattened = Children.toArray(children);
  if (flattened.length === 0) return children;
  return flattened.map((child, index) => renderChildWithRedactions(child, index));
}

function renderChildWithRedactions(child: ReactNode, index: number): ReactNode {
  if (typeof child === 'string') {
    return (
      <span key={`r-${index}`} data-redact-segment="true">
        {renderStringWithRedactions(child, `r-${index}`)}
      </span>
    );
  }
  if (typeof child === 'number') {
    return child;
  }
  if (isValidElement(child)) {
    const element = child as ReactElement<{ children?: ReactNode }>;
    const innerChildren = element.props.children;
    if (innerChildren === undefined || innerChildren === null) {
      return element;
    }
    // Don't descend into `<mark class="redaction-applied">` — those wrap
    // already-detected matches and recursive scanning would re-process
    // the placeholder text itself.
    if (
      typeof element.type === 'string' &&
      element.type === 'mark' &&
      (element.props as Record<string, unknown>)['className'] === 'redaction-applied'
    ) {
      return element;
    }
    // Don't descend into Shiki-highlighted code spans (their styling +
    // tokenization is brittle to re-rendering, and the `<code>` outer
    // wrapper already passed through this filter at the SafeMarkdownRenderer
    // component-override level via SafeCode's text-prescan).
    if (
      typeof element.type === 'string' &&
      (element.type === 'pre' || element.type === 'code') &&
      typeof innerChildren !== 'string'
    ) {
      return element;
    }
    const newChildren = renderTextWithRedactions(innerChildren);
    return cloneElement(element, { key: element.key ?? `r-${index}` }, newChildren);
  }
  return child;
}
