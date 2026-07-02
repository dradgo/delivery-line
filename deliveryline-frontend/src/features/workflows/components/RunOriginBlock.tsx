/**
 * Story 3g-2 (Task 3 / AC2, AC3, AC4, AC5, AC6) — the run Origin block.
 *
 * A pure-presentational, self-hiding provenance surface on the workflow-detail page. It
 * takes the route's already-warmed `useWorkflowDetail` `data` (no second fetch — mirrors
 * `RunDependencyPanel` / `SplitLineagePanel`), maps it via `toRunOriginView`, and renders
 * NOTHING (`return null`) when there is no linked ticket or no title (parity with the
 * `PrLinkageDetails` / `RunDependencyPanel` T-ABSENT discipline — never an empty placeholder).
 *
 * ORIGIN DEPTH IS LOCKED (AC3): title + ref + integrationType + link-out ONLY — no ticket
 * body, no initiating prompt. The title/ref are untrusted plain text, React-escaped (Trap
 * T6 — no `dangerouslySetInnerHTML` / `SafeMarkdownRenderer`). The link-out renders ONLY
 * when a safe `url` is present; it opens in a new tab (`target="_blank" rel="noopener
 * noreferrer"`) with a distinguishing accessible name (AC4), and is omitted entirely — no
 * dead `#` / `javascript:` / disabled anchor — when `url` is absent (AC2).
 */
import { Inline } from '@/components/layout';
import type { WorkflowDetail } from '@/lib/api/queryOptions';

import { toRunOriginView } from '../runOriginView';
import { StateSignifierChip } from './WorkflowStateBadge';

export interface RunOriginBlockProps {
  /** The route's warmed workflow-detail read model; `undefined` while loading. */
  readonly detail: WorkflowDetail | undefined;
}

export function RunOriginBlock({ detail }: RunOriginBlockProps) {
  const view = detail !== undefined ? toRunOriginView(detail) : undefined;
  // AC2 — render nothing for an unlinked run (or a linked run with no title).
  if (view === undefined) {
    return null;
  }
  // AC4 — the link's accessible name distinguishes it as the external source ticket and
  // announces the new tab; built without a trailing double-space when the ref is absent.
  const linkAriaLabel =
    view.ticketRef !== undefined
      ? `Open source ticket ${view.ticketRef} (opens in a new tab)`
      : 'Open source ticket (opens in a new tab)';
  return (
    <section
      aria-label="Origin"
      data-testid="run-origin-block"
      className="w-full rounded-md border border-border bg-surface px-4 py-2"
    >
      <Inline gap="4" wrap align="center">
        <span className="shrink-0 text-annotation uppercase tracking-wide text-text-tertiary">
          Origin
        </span>
        <span
          className="min-w-0 truncate text-sm text-text-primary"
          data-testid="run-origin-title"
          title={view.title}
        >
          {view.title}
        </span>
        {view.ticketRef !== undefined ? (
          <code className="text-meta text-text-secondary" data-testid="run-origin-ref">
            {view.ticketRef}
          </code>
        ) : null}
        {view.integrationType !== undefined ? (
          <StateSignifierChip
            stateName="informational"
            label={view.integrationType}
            testId="run-origin-type"
          />
        ) : null}
        {view.url !== undefined ? (
          <a
            href={view.url}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={linkAriaLabel}
            className="text-sm text-text-secondary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
            data-testid="run-origin-link"
          >
            Open source ticket
          </a>
        ) : null}
      </Inline>
    </section>
  );
}
