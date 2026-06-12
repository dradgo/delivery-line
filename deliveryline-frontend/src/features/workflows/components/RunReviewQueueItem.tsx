/**
 * Story 2.15 (AC1–AC11) — `RunReviewQueueItem`: the populated review-queue row.
 *
 * A PURE-PRESENTATIONAL row (`architecture.md:516`): it takes a `RunQueueRow` prop
 * and holds NO query/server state. `QueueShell` (story 2.20) owns the
 * `useWorkflowsList` query and passes each `WorkflowSummary` through its `renderItem`
 * seam; the wiring site maps via `toRunQueueRow`. The row returns row CONTENT only —
 * `QueueShell` provides the `<ul role="list">`/`<li>` semantics (Trap T5).
 *
 * COMPOSES, never rebuilds:
 *  • `WorkflowStateBadge` / `StateSignifierChip` — the shared badge born in story
 *    2.16 FOR this story (Trap T1); every signal pairs color with icon + label so it
 *    is never color-alone (AC11 / story 2.3 AC5).
 *  • `QUEUE_ROW_MIN_HEIGHT` (story 2.20) so real rows match the skeleton (no shift).
 *  • `formatRelativeTime` / `formatUtcTimestamp` (story 2.16) for the age + tooltip.
 *  • `densityGap` (story 2.4) for compact/standard inner spacing.
 *
 * One primary attention signal (AC5): `resolvePrimaryAttentionIndicator` picks the
 * single highest-priority active signal; the rest demote to the secondary cluster.
 *
 * Open-run intent (AC6/AC8): a typed `<Link to="/workflows/$workflowRunId">` (Enter
 * native) plus a Space `onKeyDown` so Space also opens (Trap T8). When `disabled`
 * (or the run id is absent/malformed) the row is inert — no `<Link>`, not focusable.
 *
 * Plain text only (Trap T6): `ticketRef` / `summary` render as React-escaped text;
 * NO `SafeMarkdownRenderer`, NO `dangerouslySetInnerHTML`.
 */
import { type KeyboardEvent, type ReactNode } from 'react';
import { Link } from '@tanstack/react-router';

import { Inline } from '@/components/layout';
import { densityGap, type Density } from '@/lib/density';
import { isValidRunId } from '@/lib/routing/publicId';
import type { StateName } from '@/lib/state-signifiers';
import { cn } from '@/lib/utils';

import { formatRelativeTime, formatUtcTimestamp } from '../runContextFormat';
import { humanizeFailureCategory } from '../failureCategoryView';
import {
  resolvePrimaryAttentionIndicator,
  resolveQueueItemState,
  type AttentionIndicator,
  type QueueItemState,
  type RunQueueRow,
} from '../runQueueRow';
import { QUEUE_ROW_MIN_HEIGHT } from '../queueState';
import { StateSignifierChip, WorkflowStateBadge } from './WorkflowStateBadge';

export type RunReviewQueueItemVariant = 'reviewer' | 'operator';

export interface RunReviewQueueItemProps {
  /** The mapped view-model row (`toRunQueueRow(summary)` at the wiring site). */
  run: RunQueueRow;
  /** AC3 — accent border + background when this row is the active selection. */
  selected?: boolean | undefined;
  /** AC3 / OQ-1 — subtle unread dot; a presentation concern the parent drives. */
  unread?: boolean | undefined;
  /** AC3 — inert (reduced opacity, no hover, NOT focusable/navigable). */
  disabled?: boolean | undefined;
  /** AC4 — `reviewer` (default) or `operator` (E4 placeholder affordance). */
  variant?: RunReviewQueueItemVariant | undefined;
  /** AC4 — inner spacing density (`standard` default); `densityGap` maps it. */
  density?: Density | undefined;
}

/** Per-row container classes per the single dominant state (AC3). Literal, purge-safe. */
const STATE_CONTAINER_CLASSES: Record<QueueItemState, string> = {
  default: 'border-border bg-surface',
  selected: 'border-state-selected-border bg-state-selected',
  unread: 'border-border bg-surface',
  // Story 3.30 (AC8) — the `state-error` border for a failed run.
  failed: 'border-state-error-border bg-surface',
  blocked: 'border-state-blocker-border bg-surface',
  stale: 'border-state-stale-border bg-surface',
  disabled: 'border-border bg-surface opacity-60',
};

/** Active-signal presentation (color token + non-color label) — never color-alone (AC11). */
interface SignalPresentation {
  readonly indicator: AttentionIndicator;
  readonly stateName: StateName;
  readonly label: string;
}

/** The aria term for each primary attention signal (AC6; OQ-2 — raw state stays raw). */
const ATTENTION_ARIA_TERM: Record<AttentionIndicator, string> = {
  failed: 'failed',
  blocker: 'blocked',
  escalation: 'escalated',
  openQuestion: 'open question',
  stale: 'stale',
};

/** Friendly display label for a (dormant) artifact type — mirrors `RunContextStrip`. */
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

/**
 * All ACTIVE attention signals in priority order (blocker > escalation >
 * openQuestion > stale). The head is the primary (it matches
 * `resolvePrimaryAttentionIndicator`); the tail demotes to the secondary cluster.
 */
function activeSignals(row: RunQueueRow): SignalPresentation[] {
  const out: SignalPresentation[] = [];
  // Story 3.30 (AC8) — the `failed` signal leads (priority parity with the resolver).
  // Its label carries the compact failure category when present (DORMANT — fixtures
  // only), else just "Failed" (LIVE from `currentState`).
  if (row.currentState === 'Failed') {
    const category = humanizeFailureCategory(row.failureCategory);
    out.push({
      indicator: 'failed',
      stateName: 'error',
      label: category !== undefined ? `Failed · ${category}` : 'Failed',
    });
  }
  if (typeof row.blockerCount === 'number' && row.blockerCount > 0) {
    out.push({
      indicator: 'blocker',
      stateName: 'blocker',
      label: row.blockerCount === 1 ? '1 blocker' : `${row.blockerCount} blockers`,
    });
  }
  if (row.escalationMarker === true) {
    out.push({ indicator: 'escalation', stateName: 'warning', label: 'Escalated' });
  }
  if (typeof row.openQuestionCount === 'number' && row.openQuestionCount > 0) {
    out.push({
      indicator: 'openQuestion',
      stateName: 'informational',
      label:
        row.openQuestionCount === 1 ? '1 open question' : `${row.openQuestionCount} open questions`,
    });
  }
  if (row.staleIndicator === true) {
    out.push({ indicator: 'stale', stateName: 'stale', label: 'Stale' });
  }
  return out;
}

/** Compose the accessible label from PRESENT fields only — never the literal "undefined" (AC6). */
function composeAriaLabel(
  row: RunQueueRow,
  primary: AttentionIndicator | null,
  relativeTime: string | null,
): string {
  const parts: string[] = [];
  const identity = [row.linearTicketReference, row.runId].filter(Boolean).join(' ');
  if (identity !== '') {
    parts.push(identity);
  }
  // OQ-2 — reuse the RAW backend state for parity with the visible badge.
  if (row.currentState !== undefined && row.currentState.trim() !== '') {
    parts.push(row.currentState);
  }
  if (primary !== null) {
    parts.push(ATTENTION_ARIA_TERM[primary]);
  }
  if (relativeTime !== null) {
    parts.push(`last updated ${relativeTime}`);
  }
  return parts.join(', ');
}

/** Identity cluster: `DEL-1234 · run_abc…` (run id as `<code>`); run id alone when no ticket. */
function Identity({ row }: { row: RunQueueRow }) {
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5">
      {row.linearTicketReference !== undefined ? (
        <span className="font-medium text-text-primary">{row.linearTicketReference}</span>
      ) : null}
      {row.linearTicketReference !== undefined && row.runId !== undefined ? (
        <span aria-hidden className="text-text-tertiary">
          ·
        </span>
      ) : null}
      {row.runId !== undefined ? (
        <code className="truncate text-meta text-text-secondary">{row.runId}</code>
      ) : null}
    </span>
  );
}

/** The shared row anatomy (AC2). Pure presentation — no navigation/keyboard concerns. */
function RowBody({ row, density, nowMs }: { row: RunQueueRow; density: Density; nowMs: number }) {
  const signals = activeSignals(row);
  const primaryIndicator = resolvePrimaryAttentionIndicator(row);
  const primarySignal = signals.find((s) => s.indicator === primaryIndicator);
  const secondarySignals = signals.filter((s) => s !== primarySignal);

  const relative = formatRelativeTime(row.lastTransitionAt, nowMs);
  const utc = formatUtcTimestamp(row.lastTransitionAt);

  const revision =
    row.currentArtifactType !== undefined
      ? artifactDisplayLabel(row.currentArtifactType)
      : undefined;

  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1">
      <Inline
        gap="2"
        wrap
        align="center"
        className={densityGap(density)}
        data-testid="queue-item-anatomy"
      >
        <Identity row={row} />
        <WorkflowStateBadge currentState={row.currentState} />

        {/* AC5 — the ONE primary attention signal (never multiple competing). */}
        {primarySignal !== undefined ? (
          <StateSignifierChip
            stateName={primarySignal.stateName}
            label={primarySignal.label}
            testId="queue-item-primary-attention"
          />
        ) : null}

        {/* AC2 — artifact-type badge (dormant today: no live source). */}
        {revision !== undefined ? (
          <StateSignifierChip
            stateName="informational"
            label={revision}
            testId="queue-item-artifact-type"
          />
        ) : null}

        {/* AC2 — relative "last updated" age + precise UTC tooltip (AC7's only hover reveal). */}
        {relative !== null ? (
          <time
            className="text-meta text-text-tertiary"
            dateTime={row.lastTransitionAt}
            title={utc ?? undefined}
            data-testid="queue-item-age"
          >
            {relative}
          </time>
        ) : null}
      </Inline>

      {/* AC2 — secondary trust-signal cluster (demoted attention signals + spec rejections + assignee). */}
      {secondarySignals.length > 0 ||
      (typeof row.specRejectionLoopCount === 'number' && row.specRejectionLoopCount > 0) ||
      row.assigneeHint !== undefined ? (
        <Inline
          gap="2"
          wrap
          align="center"
          className={densityGap(density)}
          data-testid="queue-item-secondary"
        >
          {secondarySignals.map((s) => (
            <StateSignifierChip key={s.indicator} stateName={s.stateName} label={s.label} />
          ))}
          {/* OQ-3 — `specRejectionLoopCount` is a real live signal, shown secondary (NOT primary). */}
          {typeof row.specRejectionLoopCount === 'number' && row.specRejectionLoopCount > 0 ? (
            <StateSignifierChip
              stateName="warning"
              label={
                row.specRejectionLoopCount === 1
                  ? '1 rejection'
                  : `${row.specRejectionLoopCount} rejections`
              }
              testId="queue-item-rejections"
            />
          ) : null}
          {/* Dormant today (no live source). */}
          {row.assigneeHint !== undefined ? (
            <span className="text-meta text-text-secondary" data-testid="queue-item-assignee">
              {row.assigneeHint}
            </span>
          ) : null}
        </Inline>
      ) : null}

      {/* AC2/AC7 — summary line, plain text, two-line clamp; renders ONLY when present (dormant today). */}
      {row.summary !== undefined ? (
        <p className="line-clamp-2 text-meta text-text-secondary" data-testid="queue-item-summary">
          {row.summary}
        </p>
      ) : null}
    </div>
  );
}

/**
 * AC4 / OQ-4 — the `operator` variant placeholder (E4). Renders the same identity +
 * badge but swaps the open-run affordance for a documented, non-navigable marker so
 * the variant contract compiles + holds without over-building.
 */
function OperatorRow({ row, density }: { row: RunQueueRow; density: Density }) {
  return (
    <div
      className={cn(
        'flex items-center justify-between rounded-md border border-border bg-surface px-4 opacity-90',
      )}
      style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}
      data-queue-item-state="disabled"
      data-variant="operator"
      data-testid="run-review-queue-item"
    >
      <Inline gap="2" align="center" className={densityGap(density)}>
        <Identity row={row} />
        <WorkflowStateBadge currentState={row.currentState} />
      </Inline>
      <span className="text-meta text-text-tertiary" data-testid="queue-item-operator-placeholder">
        Operator view — available in Epic 4
      </span>
    </div>
  );
}

export function RunReviewQueueItem({
  run,
  selected,
  unread,
  disabled,
  variant = 'reviewer',
  density = 'standard',
}: RunReviewQueueItemProps) {
  if (variant === 'operator') {
    return <OperatorRow row={run} density={density} />;
  }

  // AC6 — navigable only when enabled AND the run id is well-formed (never build a
  // route param from a malformed id; Trap T9 — disabled is truly inert).
  const navigable = disabled !== true && isValidRunId(run.runId);
  const effectivelyDisabled = disabled === true || !navigable;
  const state = resolveQueueItemState({
    row: run,
    selected,
    unread,
    disabled: effectivelyDisabled,
  });
  const primaryIndicator = resolvePrimaryAttentionIndicator(run);
  const nowMs = Date.now();
  const relative = formatRelativeTime(run.lastTransitionAt, nowMs);
  const ariaLabel = composeAriaLabel(run, primaryIndicator, relative);

  const containerClass = cn(
    // Story 2.25 (AC10) — 44px touch-target floor (the row's 4.5rem inline
    // minHeight already exceeds it; the class documents + guarantees the floor).
    'flex min-h-touch items-center gap-3 rounded-md border px-4',
    STATE_CONTAINER_CLASSES[state],
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
    navigable && 'hover:bg-surface-elevated',
  );

  const body: ReactNode = (
    <>
      {/* AC3 — unread dot (paired with a title so it is not purely color; full a11y audit is 2.25). */}
      {state === 'unread' ? (
        <StateSignifierChip
          stateName="informational"
          label="Unread"
          testId="queue-item-unread-dot"
        />
      ) : null}
      <RowBody row={run} density={density} nowMs={nowMs} />
    </>
  );

  if (navigable) {
    // Task 6 — field-only structured log on the open-run activation (click / Enter /
    // Space). NEVER ticketRef / summary / runId or any free-text content.
    const logOpen = () => {
      console.info({
        event: 'queueItem.open',
        state,
        attention: primaryIndicator,
        hasEscalation: run.escalationMarker === true,
        rejectionLoopCount: run.specRejectionLoopCount ?? 0,
      });
    };
    const handleKeyDown = (event: KeyboardEvent<HTMLAnchorElement>) => {
      // Trap T8 — a bare anchor activates on Enter but NOT Space; add Space here.
      if (event.repeat) {
        return;
      }
      if (event.key === ' ' || event.key === 'Enter') {
        event.preventDefault();
        event.currentTarget.click();
      }
    };
    return (
      <Link
        to="/workflows/$workflowRunId"
        params={{ workflowRunId: run.runId as string }}
        aria-label={ariaLabel}
        className={containerClass}
        style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}
        data-queue-item-state={state}
        data-variant="reviewer"
        data-testid="run-review-queue-item"
        onClick={logOpen}
        onKeyDown={handleKeyDown}
      >
        {body}
      </Link>
    );
  }

  // Inert: disabled OR an absent/malformed run id. No <Link>, not in the tab order.
  return (
    <div
      aria-label={ariaLabel !== '' ? ariaLabel : undefined}
      aria-disabled={effectivelyDisabled ? true : undefined}
      className={containerClass}
      style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}
      data-queue-item-state={state}
      data-variant="reviewer"
      data-testid="run-review-queue-item"
    >
      {body}
    </div>
  );
}
