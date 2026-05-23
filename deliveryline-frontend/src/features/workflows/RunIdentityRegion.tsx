/**
 * Story 2.7 (Task 3, AC3 / AC11) — the "current run identity" region.
 *
 * TRAP 5 — backend is the single source of truth: this region reads run state
 * from `useWorkflowDetail` (TanStack Query), NEVER from `useState`
 * (architecture.md:761-766, 800-805). Because React hooks cannot be conditional,
 * the AppShell renders this as a CHILD component, mounted ONLY when the active
 * route carries a `$workflowRunId` param — the child then calls the hook
 * unconditionally.
 *
 * AC11 — UI is a faithful view of backend state: the fields shown here are the
 * SAME identifiers the CLI `deliveryline status --format=json` (story 1.15) emits.
 * Mapping verified against `WorkflowCommandOutputs#renderStatusJson`:
 *   runId            → `workflowRunId`
 *   current state    → `currentState`
 *   current actor    → `currentActorIdentity` (+ `currentActorType`)  [CLI `currentActor`]
 *   last transition  → `lastEventAt` (+ `lastEventType`)              [CLI `lastEvent.createdAt`]
 * NOTE: the "last transition" timestamp is `lastEventAt` — the CLI's
 * `lastEvent.createdAt` — NOT `lastActivityTimestamp` (the CLI emits that as a
 * separate field; it is not the state-transition time).
 *
 * Two variants: `full` is the nav-rail block; `compact` is a single line for the
 * mobile top bar — where run identity + current state must stay visible at all
 * times (UX "Structural Collapse Rules": they "never disappear during collapse").
 */
import type { ReactNode } from 'react';

import type { WorkflowDetail } from '@/lib/api/queryOptions';
import { useWorkflowDetail } from './hooks/useWorkflowDetail';

export interface RunIdentityRegionProps {
  /** The active route's `$workflowRunId` param — guaranteed present by the caller. */
  workflowRunId: string;
  /** `full` — the nav-rail block (default). `compact` — one line for the mobile top bar. */
  variant?: 'full' | 'compact';
}

/** Render an ISO instant as a deterministic, timezone-independent UTC string. */
function formatTransitionTimestamp(iso: string | undefined): string | null {
  if (iso === undefined || iso === '') {
    return null;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  // `toISOString()` is always UTC — stable across machines/timezones (audit parity).
  return `${date.toISOString().slice(0, 16).replace('T', ' ')} UTC`;
}

/** Coalesce an optional backend string to a trimmed value, or `null` when absent. */
function presentOrNull(value: string | undefined): string | null {
  return value !== undefined && value !== '' ? value : null;
}

/** A single label/value pair in the identity list. */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-annotation uppercase tracking-wide text-text-tertiary">{label}</dt>
      <dd className="text-sm text-text-primary">{children}</dd>
    </div>
  );
}

/** Placeholder for a backend field that was not reported (every detail field is optional). */
function NotReported() {
  return <span className="text-text-tertiary">Not reported</span>;
}

/**
 * The state/actor/last-transition rows of the full variant. Receives a resolved
 * (non-null) `WorkflowDetail` — every field is still individually optional.
 */
function FullRunIdentityFields({ detail }: { detail: WorkflowDetail }) {
  const actorIdentity = presentOrNull(detail.currentActorIdentity);
  const actorType = presentOrNull(detail.currentActorType);
  const actorText =
    actorIdentity !== null
      ? actorType !== null
        ? `${actorIdentity} (${actorType})`
        : actorIdentity
      : null;

  const lastEventType = presentOrNull(detail.lastEventType);
  const transitionTime = formatTransitionTimestamp(detail.lastEventAt);

  return (
    <>
      <Field label="State">{presentOrNull(detail.currentState) ?? <NotReported />}</Field>
      <Field label="Current actor">{actorText ?? <NotReported />}</Field>
      <Field label="Last transition">
        {transitionTime !== null ? (
          <span className="flex flex-col gap-0.5">
            {lastEventType !== null ? (
              <span className="text-text-secondary">{lastEventType}</span>
            ) : null}
            <time dateTime={detail.lastEventAt} className="text-text-secondary">
              {transitionTime}
            </time>
          </span>
        ) : (
          <NotReported />
        )}
      </Field>
    </>
  );
}

/**
 * Run-identity block. Reads run id, current state, current actor, and the last
 * transition — gracefully handling the loading, error, and missing-field cases
 * (all `WorkflowDetail` fields are optional).
 */
export function RunIdentityRegion({ workflowRunId, variant = 'full' }: RunIdentityRegionProps) {
  const query = useWorkflowDetail(workflowRunId);

  if (variant === 'compact') {
    const stateLabel = query.isError
      ? 'Unavailable'
      : query.isPending
        ? 'Loading…'
        : (presentOrNull(query.data.currentState) ?? 'Not reported');
    return (
      <div className="flex min-w-0 flex-col" data-testid="run-identity-region">
        <code className="truncate text-sm font-medium text-text-primary">{workflowRunId}</code>
        <span className="truncate text-xs text-text-secondary">{stateLabel}</span>
      </div>
    );
  }

  return (
    <div className="border-t border-border pt-4" data-testid="run-identity-region">
      <p className="text-annotation uppercase tracking-wide text-text-tertiary">Current run</p>
      <dl className="mt-2 flex flex-col gap-2">
        <Field label="Run ID">
          <code className="break-all">{workflowRunId}</code>
        </Field>

        {query.isError ? (
          <p className="text-sm text-text-tertiary">Run details unavailable.</p>
        ) : query.isPending ? (
          <p className="text-sm text-text-tertiary">Loading run details…</p>
        ) : (
          <FullRunIdentityFields detail={query.data} />
        )}
      </dl>
    </div>
  );
}
