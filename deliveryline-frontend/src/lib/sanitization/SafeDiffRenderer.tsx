/**
 * Story 2.24 — SafeDiffRenderer (AC5).
 *
 * Renders before/after content as plain text within structured panels.
 * Line-level additions/deletions use `<ins>`/`<del>` with stable className
 * tokens. Neither side is ever interpreted as HTML — the component renders
 * text nodes via React children, never `dangerouslySetInnerHTML`.
 */
import { diffLines, type Change } from 'diff';
import { useMemo, type ReactNode } from 'react';

import { renderTextWithRedactions } from './redactionFilter';

export interface SafeDiffRendererProps {
  before: string;
  after: string;
  /**
   * Optional accessible labels for the two panels. Defaults to "Before"
   * and "After".
   */
  beforeLabel?: string;
  afterLabel?: string;
  className?: string;
}

interface DiffLine {
  kind: 'added' | 'removed' | 'context';
  text: string;
}

/**
 * Compute a line-level diff via the Myers algorithm (`diff` package). The
 * earlier `Set`-based marker (code review D5, 2026-05-26) lost positional
 * and count semantics — `before="a\na\na"` / `after="a"` showed all three
 * before-lines as context — which could hide newly-introduced secrets in
 * redaction-sensitive comparisons. `diffLines` walks the LCS, returning a
 * sequence of `Change` records with `added`/`removed`/`count` flags. We
 * split that sequence into two column projections so each side renders
 * its own line stream with stable indices.
 *
 * Epic 4's Compare Mode is the canonical surface; this implementation is
 * already accurate, leaving Epic 4 free to focus on richer chrome (line
 * numbers, hunk navigation, side-by-side token-level highlight).
 */
function changesToLineDiff(changes: Change[]): {
  beforeLines: DiffLine[];
  afterLines: DiffLine[];
} {
  const beforeLines: DiffLine[] = [];
  const afterLines: DiffLine[] = [];
  for (const change of changes) {
    const text = change.value;
    // diff's Change.value already preserves trailing newlines per token.
    // Splitting on '\n' would emit an extra empty line; trim the last
    // empty entry if the chunk ended with a newline.
    const lines = text.split('\n');
    if (lines.length > 0 && lines[lines.length - 1] === '') {
      lines.pop();
    }
    if (change.added === true) {
      for (const line of lines) {
        afterLines.push({ kind: 'added', text: line });
      }
    } else if (change.removed === true) {
      for (const line of lines) {
        beforeLines.push({ kind: 'removed', text: line });
      }
    } else {
      // Context chunk — appears verbatim on both sides.
      for (const line of lines) {
        beforeLines.push({ kind: 'context', text: line });
        afterLines.push({ kind: 'context', text: line });
      }
    }
  }
  return { beforeLines, afterLines };
}

function computeLineDiff(
  before: string,
  after: string,
): {
  beforeLines: DiffLine[];
  afterLines: DiffLine[];
} {
  // `diffLines` returns context-only when both inputs are empty.
  return changesToLineDiff(diffLines(before, after));
}

function DiffLineNode({
  line,
  side,
  lineIndex,
  withTrailingNewline,
}: {
  line: DiffLine;
  side: 'before' | 'after';
  lineIndex: number;
  withTrailingNewline: boolean;
}): ReactNode {
  const className =
    line.kind === 'added'
      ? 'diff-line-added'
      : line.kind === 'removed'
        ? 'diff-line-removed'
        : 'diff-line-context';
  // Trap T5 — apply redaction filter to diff lines as well. Even though the
  // diff component renders inert text by construction, secrets in either
  // side need to be wrapped in the redaction `<mark>`.
  const content = renderTextWithRedactions(line.text);
  const newline = withTrailingNewline ? '\n' : '';
  if (line.kind === 'added' && side === 'after') {
    return (
      <>
        <ins className={className} data-line-index={lineIndex}>
          {content}
        </ins>
        {newline}
      </>
    );
  }
  if (line.kind === 'removed' && side === 'before') {
    return (
      <>
        <del className={className} data-line-index={lineIndex}>
          {content}
        </del>
        {newline}
      </>
    );
  }
  return (
    <>
      <span className={className} data-line-index={lineIndex}>
        {content}
      </span>
      {newline}
    </>
  );
}

export function SafeDiffRenderer({
  before,
  after,
  beforeLabel = 'Before',
  afterLabel = 'After',
  className,
}: SafeDiffRendererProps) {
  const { beforeLines, afterLines } = useMemo(
    () => computeLineDiff(before, after),
    [before, after],
  );
  return (
    <div className={className} data-component="safe-diff">
      <section aria-label={beforeLabel} data-side="before">
        <header>{beforeLabel}</header>
        <pre>
          {/* P11 (2026-05-26) — render each diff line as bare children with
              embedded "\n"; the previous version wrapped each line in a
              `<div>`, but `<div>` is flow content and `<pre>` is phrasing-
              content-only per HTML5. Browsers tolerated it but React/a11y
              tooling flagged it. The data-line-index hooks now move to the
              DiffLineNode wrapper. */}
          {beforeLines.map((line, index) => (
            <DiffLineNode
              key={`b-${index}`}
              line={line}
              side="before"
              lineIndex={index}
              withTrailingNewline={index < beforeLines.length - 1}
            />
          ))}
        </pre>
      </section>
      <section aria-label={afterLabel} data-side="after">
        <header>{afterLabel}</header>
        <pre>
          {afterLines.map((line, index) => (
            <DiffLineNode
              key={`a-${index}`}
              line={line}
              side="after"
              lineIndex={index}
              withTrailingNewline={index < afterLines.length - 1}
            />
          ))}
        </pre>
      </section>
    </div>
  );
}
