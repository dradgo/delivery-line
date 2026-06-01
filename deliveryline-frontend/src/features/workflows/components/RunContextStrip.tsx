/**
 * Story 2.16 (AC1–AC10) — the persistent `RunContextStrip`.
 *
 * A lightweight orientation strip above the primary review surface (UX-DR9): run
 * identity, current-state badge, current actor, latest revision pointer, and the
 * last meaningful transition — just enough to confirm "right artifact, right
 * point", without competing with the artifact body.
 *
 * Data: reads the SAME warmed cache entry the route loader populated via
 * `useWorkflowDetail` (story 2.6) — NO new fetch (T5) — and maps it through the
 * pure {@link toRunContextView}. State selection is delegated to the pure
 * {@link resolveRunContextState} so EXACTLY ONE branch renders (mirrors
 * `QueueShell`/`resolveQueueState`); the resolved value is exposed as
 * `data-run-context-state` for tests.
 *
 * Boundaries:
 *  • loading → `<Skeleton>` rows matching the inline layout, NEVER a spinner (T3).
 *  • error → composes story-2.22 `<ErrorState variant="failedRetrieval">` with a
 *    Retry action; `<ErrorState>` consumes `useReturnToRunContext` internally, so
 *    unit tests `vi.mock` that module (T6).
 *  • partial-context → per-field "Not reported" placeholders within an otherwise
 *    populated strip, NOT an `<EmptyState>` (T4).
 *  • single labeled region `role="region" aria-label="Run context"` (T8); height
 *    is capped by {@link RUN_CONTEXT_STRIP_MAX_HEIGHT} across states (AC4); NO
 *    lineage/provenance control is wired in E2 (AC5 / T9).
 */
import { useEffect, useState, type ReactNode } from 'react';

import { Inline } from '@/components/layout';
import { Skeleton } from '@/components/ui/skeleton';
import { ErrorState } from '@/components/feedback';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { cn } from '@/lib/utils';

import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import { formatRelativeTime, formatUtcTimestamp } from '../runContextFormat';
import {
  RUN_STALE_THRESHOLD_MS,
  resolveRunContextState,
  toRunContextView,
  type RunContextView,
} from '../runContextView';
import { StateSignifierChip, WorkflowStateBadge } from './WorkflowStateBadge';

/**
 * AC4 — documented vertical-real-estate cap applied across all states. The strip
 * is a single inline row (wrap allowed), never a multi-row metadata panel.
 * NOTE (AC4 jsdom caveat): tests assert this constraining style is PRESENT, not a
 * measured pixel height — jsdom does not compute real layout.
 */
export const RUN_CONTEXT_STRIP_MAX_HEIGHT = '3.5rem';

export interface RunContextStripProps {
  /** The active route's `$workflowRunId` param. */
  workflowRunId: string;
  /**
   * AC5 — reserved for a future Phase-3 lineage drilldown. Accepted but NO UI
   * control wires it in Epic 2 (a test asserts no such control renders).
   */
  onNavigateToFullLineage?: () => void;
}

/** Per-field placeholder for an unreported slot (T4 — reuses the `RunIdentityRegion` idiom). */
function NotReported() {
  return <span className="text-text-tertiary">Not reported</span>;
}

/** A labeled inline metadata slot (mini uppercase label + value). */
function Item({
  label,
  children,
  testId,
  title,
}: {
  label: string;
  children: ReactNode;
  testId?: string;
  title?: string | undefined;
}) {
  return (
    <span
      className="inline-flex min-w-0 max-w-80 items-center gap-1"
      data-testid={testId}
      title={title}
    >
      <span className="shrink-0 text-annotation uppercase tracking-wide text-text-tertiary">
        {label}
      </span>
      <span className="min-w-0 truncate text-sm text-text-primary">{children}</span>
    </span>
  );
}

/** Coalesce actor identity + type into `"Identity (type)"` — parity with `RunIdentityRegion`. */
function coalesceActor(identity: string | undefined, type: string | undefined): string | undefined {
  if (identity === undefined) {
    return undefined;
  }
  return type !== undefined ? `${identity} (${type})` : identity;
}

/** The "Stale" marker chip with a reason tooltip derived from `lastActivityAt` (AC9). */
function StaleChip({
  lastActivityAt,
  nowMs,
}: {
  lastActivityAt: string | undefined;
  nowMs: number;
}) {
  const relative = formatRelativeTime(lastActivityAt, nowMs);
  const title =
    relative !== null
      ? `Last activity ${relative} — runner may be unresponsive`
      : 'Run may be stale — runner may be unresponsive';
  return (
    <StateSignifierChip
      stateName="stale"
      label="Stale"
      title={title}
      testId="run-context-stale-badge"
    />
  );
}

/** The populated anatomy (shared by default / stale / partial-context states). */
function artifactDisplayLabel(type: string): string {
  switch (type) {
    case 'implementationPlan':
      return 'implementation-plan';
    case 'prOutput':
      return 'pr-output';
    default:
      return type;
  }
}

function StripContent({ view, nowMs }: { view: RunContextView; nowMs: number }) {
  const actorText = coalesceActor(view.currentActorIdentity, view.currentActorType);
  const revision =
    view.latestArtifactType !== undefined && view.latestArtifactVersion !== undefined
      ? `${artifactDisplayLabel(view.latestArtifactType)} v${view.latestArtifactVersion}`
      : undefined;
  const relativeTransition = formatRelativeTime(view.lastTransitionAt, nowMs);
  const utcTransition = formatUtcTimestamp(view.lastTransitionAt);

  return (
    <Inline gap="4" wrap align="center">
      <Item label="Run">
        {view.runId !== undefined ? (
          <code className="text-sm text-text-primary">{view.runId}</code>
        ) : (
          <NotReported />
        )}
      </Item>
      <WorkflowStateBadge currentState={view.currentState} />
      {view.staleIndicator ? (
        <StaleChip lastActivityAt={view.lastActivityAt} nowMs={nowMs} />
      ) : null}
      {view.escalationMarker ? (
        <StateSignifierChip stateName="warning" label="Escalated" testId="run-context-escalation" />
      ) : null}
      <Item label="Actor" testId="run-context-actor" title={actorText}>
        {actorText ?? <NotReported />}
      </Item>
      <Item label="Revision" testId="run-context-revision" title={revision}>
        {revision ?? <NotReported />}
      </Item>
      <Item label="Last transition">
        {relativeTransition !== null ? (
          <time dateTime={view.lastTransitionAt} title={utcTransition ?? undefined}>
            {relativeTransition}
          </time>
        ) : (
          <NotReported />
        )}
      </Item>
      <Item label="Trigger" testId="run-context-trigger" title={view.triggerReference}>
        {view.triggerReference ?? <NotReported />}
      </Item>
      <Item label="Branch" testId="run-context-branch" title={view.branchOrCommitReference}>
        {view.branchOrCommitReference ?? <NotReported />}
      </Item>
    </Inline>
  );
}

export function RunContextStrip({ workflowRunId }: RunContextStripProps) {
  const query = useWorkflowDetail(workflowRunId);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const view = query.data !== undefined ? toRunContextView(query.data, nowMs) : undefined;
  const state = resolveRunContextState({
    isError: query.isError,
    isPending: query.isPending,
    view,
  });

  // Stale duration for the structured log (the tooltip derives its own relative
  // text from `lastActivityAt`); `undefined` when there is no activity timestamp.
  const staleForMs =
    view?.lastActivityAt !== undefined ? nowMs - Date.parse(view.lastActivityAt) : undefined;

  useEffect(() => {
    if (view?.lastActivityAt === undefined || view.staleIndicator) {
      return;
    }
    const activityMs = Date.parse(view.lastActivityAt);
    if (Number.isNaN(activityMs)) {
      return;
    }
    const delayMs = Math.max(activityMs + RUN_STALE_THRESHOLD_MS + 1 - nowMs, 1);
    const timeout = window.setTimeout(() => setNowMs(Date.now()), delayMs);
    return () => window.clearTimeout(timeout);
  }, [nowMs, view?.lastActivityAt, view?.staleIndicator]);

  // Task 6 — field-only structured logs (mirror QueueShell). NEVER the raw error
  // message / payload (T7): only the stable ProblemDetails `code` + transport flag.
  useEffect(() => {
    if (state !== 'error') {
      return;
    }
    const error = query.error;
    console.warn({
      event: 'runContext.loadError',
      code: isProblemDetailsError(error) ? error.code : 'transport',
      transport: !isProblemDetailsError(error),
    });
  }, [state, query.error]);

  useEffect(() => {
    if (state !== 'stale') {
      return;
    }
    console.warn({ event: 'runContext.stale', staleForMs });
  }, [state, staleForMs]);

  const handleRetry = () => {
    console.info({ event: 'runContext.retry' });
    void query.refetch();
  };

  return (
    <section
      // AC6 / T8 — a single labeled landmark: a `<section>` WITH an accessible name
      // is exposed as an implicit `region` role (so `getByRole('region')` resolves),
      // and an explicit `role="region"` would be redundant (jsx-a11y/no-redundant-roles).
      aria-label="Run context"
      aria-busy={state === 'loading' ? true : undefined}
      data-run-context-state={state}
      style={{
        maxHeight: RUN_CONTEXT_STRIP_MAX_HEIGHT,
        overflow: state === 'error' ? 'auto' : undefined,
      }}
      // Cap height + clip overflow so the strip stays a single lightweight row
      // (AC4). The error state composes a taller `<ErrorState>` card, so it does
      // not clip — the cap still applies to the box, per the AC4 "across states" rule.
      className={cn('w-full', state !== 'error' && 'overflow-hidden')}
    >
      {state === 'loading' ? (
        <Inline gap="4" align="center" data-testid="run-context-loading">
          <Skeleton className="h-4 w-28" />
          <Skeleton className="h-4 w-20" />
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-4 w-32" />
        </Inline>
      ) : null}

      {state === 'error' ? (
        <ErrorState
          variant="failedRetrieval"
          urgency="passive"
          message="We couldn’t load this run’s context."
          nextAction={{ kind: 'Retry', onRetry: handleRetry }}
        />
      ) : null}

      {state !== 'loading' && state !== 'error' && view !== undefined ? (
        <StripContent view={view} nowMs={nowMs} />
      ) : null}
    </section>
  );
}
