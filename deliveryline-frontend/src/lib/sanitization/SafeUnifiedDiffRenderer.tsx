/**
 * Story 3.27 (Task 2 / AC2, AC3, AC5) — `SafeUnifiedDiffRenderer`.
 *
 * The sanctioned unified-diff primitive: renders a set of parsed diff hunks with every
 * line as `<ins className="diff-line-added">` / `<del className="diff-line-removed">` /
 * `<span className="diff-line-context">` — the SAME stable token-class convention as
 * {@link SafeDiffRenderer} (story 2.24 AC5). EVERY line's text, plus the hunk headers,
 * routes through {@link renderTextWithRedactions} (inert React text nodes + redaction
 * `<mark>`, NEVER `dangerouslySetInnerHTML`) — this is what keeps the untrusted-text→DOM
 * concern inside `src/lib/sanitization/**` so the `no-unsanitized-html` ESLint rule +
 * `--max-warnings=0` stay satisfied and story 3.31 gets a reusable diff primitive.
 *
 * The accordion / keyboard / file-grouping CHROME lives in the feature-layer
 * `PrOutputArtifactRenderer`; this primitive only renders one file's (or the whole
 * diff's) hunks. "Syntax highlighting" is scoped to diff-LEVEL treatment via the token
 * classes (add/remove/context/hunk-header coloring); per-language intra-line token
 * highlighting is a deferred E4 enhancement (story 3.27 decision #2).
 */
import { renderTextWithRedactions } from './redactionFilter';
import type { ParsedDiffHunk, ParsedDiffLine } from './unifiedDiff';

export interface SafeUnifiedDiffRendererProps {
  /** Parsed hunks (from {@link parseUnifiedDiff}) to render. */
  hunks: readonly ParsedDiffHunk[];
  className?: string;
  /**
   * AC5 — cap the number of rendered diff lines; a "showing N of M" note is surfaced when
   * the hunks exceed it (no silent truncation). Omit for no cap.
   */
  maxLines?: number;
  /** Accessible label for the diff region (e.g. the file path). */
  ['aria-label']?: string;
}

/** A single diff line as its semantic `<ins>`/`<del>`/`<span>` with redacted text. */
function DiffLine({
  line,
  lineIndex,
  withTrailingNewline,
}: {
  line: ParsedDiffLine;
  lineIndex: number;
  withTrailingNewline: boolean;
}) {
  const className =
    line.kind === 'added'
      ? 'diff-line-added'
      : line.kind === 'removed'
        ? 'diff-line-removed'
        : 'diff-line-context';
  // Untrusted line text → the sanctioned redaction path (inert text + redaction <mark>).
  const content = renderTextWithRedactions(line.text);
  const newline = withTrailingNewline ? '\n' : '';
  if (line.kind === 'added') {
    return (
      <>
        <ins className={className} data-line-index={lineIndex}>
          {content}
        </ins>
        {newline}
      </>
    );
  }
  if (line.kind === 'removed') {
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

export function SafeUnifiedDiffRenderer({
  hunks,
  className,
  maxLines,
  'aria-label': ariaLabel,
}: SafeUnifiedDiffRendererProps) {
  const cap = maxLines ?? Number.POSITIVE_INFINITY;
  const totalLines = hunks.reduce((sum, hunk) => sum + hunk.lines.length, 0);
  let budget = cap;

  return (
    <div
      className={['diff-view', className].filter(Boolean).join(' ')}
      data-component="safe-unified-diff"
      aria-label={ariaLabel}
    >
      {hunks.map((hunk, hunkIndex) => {
        const visible = budget > 0 ? hunk.lines.slice(0, budget) : [];
        budget -= hunk.lines.length;
        return (
          <section
            key={`hunk-${hunkIndex}`}
            data-diff-hunk={hunkIndex}
            aria-label={hunk.header.length > 0 ? hunk.header : `Hunk ${hunkIndex + 1}`}
          >
            {hunk.header.length > 0 ? (
              // Hunk header is UNTRUSTED → also through the redaction path.
              <div className="diff-line-hunk-header" data-diff-hunk-header="true">
                {renderTextWithRedactions(hunk.header)}
              </div>
            ) : null}
            {visible.length > 0 ? (
              <pre className="diff-pre">
                {visible.map((line, lineIndex) => (
                  <DiffLine
                    key={`line-${lineIndex}`}
                    line={line}
                    lineIndex={lineIndex}
                    withTrailingNewline={lineIndex < visible.length - 1}
                  />
                ))}
              </pre>
            ) : null}
          </section>
        );
      })}
      {totalLines > cap ? (
        <p className="text-meta text-text-tertiary" data-testid="diff-line-cap-note">
          Showing the first {cap} of {totalLines} changed lines — the remainder is omitted to keep
          the view responsive.
        </p>
      ) : null}
    </div>
  );
}
