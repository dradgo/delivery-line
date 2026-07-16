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
import { Button } from '@/components/ui/button';
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
import { PrLinkageDetails } from './PrLinkageDetails';

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
  /**
   * Story 4.24 (AC8a) — operator-variant "Classify" launch context. When provided, a failed row
   * renders a Classify action (above the stretched navigation link) that opens the taxonomy dialog
   * for `runId`. Omitted → no action (the reviewer variant + non-failed rows never render it). The
   * `OperatorClassifiedRow` container passes it ONLY for a not-yet-classified Failed row (AC8a).
   */
  onClassify?: ((runId: string) => void) | undefined;
  /**
   * Story 4.24 (AC9) — the run's applied failure-taxonomy human name (registry-resolved). When
   * present, the operator row surfaces it as a chip in its attention slot. Omitted → no chip (the
   * `OperatorClassifiedRow` container supplies it for a classified Failed row).
   */
  classificationLabel?: string | undefined;
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

/**
 * Story 3.29 (AC3/R5) — compose the DORMANT takeover metadata line from PRESENT
 * fields only (never the literal "undefined"): `Taken over by {actor} · {age}`, or
 * just one part when only one is provided. Non-exported (this is a `.tsx`, so only
 * components may be exported — `frontend-react-refresh-no-fn-exports`).
 */
function projectDisplayLabel(row: RunQueueRow): string | undefined {
  const candidate = row.projectName ?? row.projectSlug ?? row.projectId;
  if (candidate === undefined || candidate.trim() === '') {
    return undefined;
  }
  return candidate;
}
function takeoverMetaText(
  takenOverBy: string | undefined,
  takenOverAt: string | undefined,
  nowMs: number,
): string {
  const parts: string[] = [];
  if (takenOverBy !== undefined) {
    parts.push(`Taken over by ${takenOverBy}`);
  }
  const relative = formatRelativeTime(takenOverAt, nowMs);
  if (relative !== null) {
    parts.push(takenOverBy !== undefined ? relative : `Taken over ${relative}`);
  }
  return parts.join(' · ');
}

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
  // Story 3g-2 (AC1/AC6) — the human title leads the identity segment WHEN present, but the
  // machine `linearTicketReference` stays in the aria identity (it is the stable id reviewers
  // search by). `.filter(Boolean)` drops absent parts so the literal "undefined" never leaks.
  const identity = [row.ticketTitle, row.linearTicketReference, row.runId]
    .filter(Boolean)
    .join(' ');
  if (identity !== '') {
    parts.push(identity);
  }
  const project = projectDisplayLabel(row);
  if (project !== undefined) {
    parts.push(project);
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

/**
 * Identity cluster. Story 3g-2 (AC1): the human ticket title is the prominent label when
 * present (`«title» · DEL-1234 · run_abc…`); when absent it falls back to the ref in the
 * prominent slot (`DEL-1234 · run_abc…` — today's exact parity, never a blank label). The
 * `linearTicketReference` stays visible as the machine identity even alongside the title.
 * Title/ref are untrusted plain text — React-escaped (Trap T6).
 */
function Identity({ row }: { row: RunQueueRow }) {
  // Prefer the title; fall back to the ref (AC1 — title→ref, never blank).
  const prominent = row.ticketTitle ?? row.linearTicketReference;
  // The ref demotes to secondary text ONLY when the title occupies the prominent slot;
  // otherwise the ref IS the prominent slot (parity), so we do not repeat it.
  const showRefAsSecondary =
    row.ticketTitle !== undefined && row.linearTicketReference !== undefined;
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5">
      {prominent !== undefined ? (
        <span
          className="min-w-0 truncate font-medium text-text-primary"
          data-testid="queue-item-identity-label"
        >
          {prominent}
        </span>
      ) : null}
      {showRefAsSecondary ? (
        <>
          <span aria-hidden className="text-text-tertiary">
            ·
          </span>
          <span className="text-meta text-text-secondary" data-testid="queue-item-ticket-ref">
            {row.linearTicketReference}
          </span>
        </>
      ) : null}
      {prominent !== undefined && row.runId !== undefined ? (
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
  const project = projectDisplayLabel(row);

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

      {/* AC2 — secondary trust-signal cluster (demoted attention signals + spec rejections +
          assignee + the story-3.31 PR linkage). */}
      {secondarySignals.length > 0 ||
      (typeof row.specRejectionLoopCount === 'number' && row.specRejectionLoopCount > 0) ||
      row.assigneeHint !== undefined ||
      row.prLinkage != null ||
      row.takenOverBy !== undefined ||
      row.takenOverAt !== undefined ||
      row.archived === true ||
      project !== undefined ? (
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
          {/* Story 3d-8 (AC5) — color-independent "Hidden" chip for a soft-hidden (archived)
              run. Paired icon+label (never color-alone); metadata, not an attention signal. */}
          {row.archived === true ? (
            <StateSignifierChip stateName="stale" label="Hidden" testId="queue-item-archived" />
          ) : null}
          {project !== undefined ? (
            <StateSignifierChip
              stateName="informational"
              label={`Project: ${project}`}
              testId="queue-item-project"
            />
          ) : null}
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
          {/* Story 3.31 (AC1/AC7/AC8) — compact PR linkage, rendered ONLY when present
              (Trap T-ABSENT). The same shared treatment as the strip; the PR `<a>` carries
              the D1 stretched-link escape so it opens GitHub without breaking whole-row open-run. */}
          {row.prLinkage != null ? (
            <PrLinkageDetails prLinkage={row.prLinkage} nowMs={nowMs} variant="queue" />
          ) : null}
          {/* Story 3.29 (AC3/R5) — DORMANT takeover attribution, rendered ONLY when the
              fixture provides it (the lean list summary never carries it). Metadata, not
              an attention signal. Actor identity is untrusted — React-escaped plain text. */}
          {row.takenOverBy !== undefined || row.takenOverAt !== undefined ? (
            <span className="text-meta text-text-secondary" data-testid="queue-item-takeover">
              {takeoverMetaText(row.takenOverBy, row.takenOverAt, nowMs)}
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
 * Story 4.2 (AC2/Reconciliation 5) — map the server-derived `operatorSignifier` to a signal color.
 * The row renders the badge FROM the signifier, NEVER re-deriving from `currentState` (the 4.1
 * review lesson: a state re-derivation mislabeled an active `overridden` run as `[STALLED]`).
 */
const SIGNIFIER_STATE_NAME: Record<string, StateName> = {
  FAILED: 'error',
  ORPHANED: 'error',
  STALLED: 'stale',
  TAKENOVER: 'warning',
  OVERRIDDEN: 'warning',
};
function signifierStateName(signifier: string): StateName {
  return SIGNIFIER_STATE_NAME[signifier] ?? 'informational';
}

/** Compose the operator row's accessible label from PRESENT fields only (never "undefined"). */
function composeOperatorAriaLabel(row: RunQueueRow, relativeTime: string | null): string {
  const parts: string[] = [];
  const identity = [row.ticketTitle, row.linearTicketReference, row.runId]
    .filter(Boolean)
    .join(' ');
  if (identity !== '') {
    parts.push(identity);
  }
  if (row.operatorSignifier !== undefined) {
    parts.push(row.operatorSignifier.toLowerCase());
  } else if (row.currentState !== undefined && row.currentState.trim() !== '') {
    parts.push(row.currentState);
  }
  const failure = humanizeFailureCategory(row.failureCategory);
  if (failure !== undefined) {
    parts.push(failure);
  }
  if (row.runnerKind !== undefined) {
    parts.push(`runner ${row.runnerKind}`);
  }
  if (row.escalationMarker === true) {
    parts.push('escalated');
  }
  if (relativeTime !== null) {
    parts.push(`last operator action ${relativeTime}`);
  }
  return parts.join(', ');
}

/** The operator row's inner content (AC2) — identity + signifier badge + operator metadata. */
function OperatorRowBody({
  row,
  density,
  nowMs,
  onClassify,
  classificationLabel,
}: {
  row: RunQueueRow;
  density: Density;
  nowMs: number;
  onClassify?: ((runId: string) => void) | undefined;
  classificationLabel?: string | undefined;
}) {
  const relative = formatRelativeTime(row.lastOperatorActionAt ?? row.lastTransitionAt, nowMs);
  const utc = formatUtcTimestamp(row.lastOperatorActionAt ?? row.lastTransitionAt);
  const failure = humanizeFailureCategory(row.failureCategory);
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
        {/* AC2/Reconciliation 5 — badge FROM the server signifier (fallback to the state badge
            only when the signifier is absent, e.g. a fixture that omits it). */}
        {row.operatorSignifier !== undefined ? (
          <StateSignifierChip
            stateName={signifierStateName(row.operatorSignifier)}
            label={row.operatorSignifier}
            testId="operator-signifier"
          />
        ) : (
          <WorkflowStateBadge currentState={row.currentState} />
        )}
        {/* AC2 — failure category chip (when present). */}
        {failure !== undefined ? (
          <StateSignifierChip
            stateName="error"
            label={failure}
            testId="operator-failure-category"
          />
        ) : null}
        {/* Story 4.24 (AC9) — the applied failure classification, surfaced in the attention slot for a
            classified Failed row (the `OperatorClassifiedRow` container resolves the human name). */}
        {classificationLabel !== undefined ? (
          <StateSignifierChip
            stateName="informational"
            label={`Classification: ${classificationLabel}`}
            testId="operator-classification-chip"
          />
        ) : null}
        {/* AC2 — runner kind chip (when the project carries a runner-kind override). */}
        {row.runnerKind !== undefined ? (
          <StateSignifierChip
            stateName="informational"
            label={`Runner: ${row.runnerKind}`}
            testId="operator-runner-kind"
          />
        ) : null}
        {/* AC2 — escalation marker prominence. */}
        {row.escalationMarker === true ? (
          <StateSignifierChip stateName="warning" label="Escalated" testId="operator-escalation" />
        ) : null}
        {/* AC2/OQ-LASTACTION — last operator action time (lastTransitionAt proxy in E4). */}
        {relative !== null ? (
          <time
            className="text-meta text-text-tertiary"
            dateTime={row.lastOperatorActionAt ?? row.lastTransitionAt}
            title={utc ?? undefined}
            data-testid="operator-last-action"
          >
            {relative}
          </time>
        ) : null}
        {/* Story 4.24 (AC8a) — Classify launch context for a Failed row. Sits ABOVE the stretched
            navigation link (`relative z-[2]`) so it opens the taxonomy dialog rather than the run. */}
        {onClassify !== undefined &&
        row.currentState === 'Failed' &&
        isValidRunId(row.runId) ? (
          <span className="relative z-[2] shrink-0">
            <Button
              type="button"
              variant="outline"
              size="sm"
              data-testid="operator-classify-action"
              onClick={(event) => {
                event.preventDefault();
                event.stopPropagation();
                onClassify(row.runId as string);
              }}
            >
              Classify
            </Button>
          </span>
        ) : null}
      </Inline>
    </div>
  );
}

/**
 * Story 4.2 (AC2/AC7) — the fleshed-out `operator` variant row. Unlike the 2.15 placeholder it is
 * NAVIGABLE (Enter/Space/click → the run detail; the 4.4 operator deep-dive is backlog), renders
 * the state badge FROM `operatorSignifier`, and surfaces failure-category / runner-kind / escalation
 * / last-operator-action metadata. Reuses the 3.31 stretched-link pattern (an absolutely-positioned
 * overlay `<Link>` so the whole row is one open-run control).
 */
function OperatorRow({
  row,
  selected,
  unread,
  disabled,
  density,
  onClassify,
  classificationLabel,
}: {
  row: RunQueueRow;
  selected?: boolean | undefined;
  unread?: boolean | undefined;
  disabled?: boolean | undefined;
  density: Density;
  onClassify?: ((runId: string) => void) | undefined;
  classificationLabel?: string | undefined;
}) {
  const navigable = disabled !== true && isValidRunId(row.runId);
  const effectivelyDisabled = disabled === true || !navigable;
  const state = resolveQueueItemState({ row, selected, unread, disabled: effectivelyDisabled });
  const nowMs = Date.now();
  const relative = formatRelativeTime(row.lastOperatorActionAt ?? row.lastTransitionAt, nowMs);
  const ariaLabel = composeOperatorAriaLabel(row, relative);
  const containerClass = cn(
    'flex min-h-touch items-center gap-3 rounded-md border px-4',
    STATE_CONTAINER_CLASSES[state],
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
    navigable && 'hover:bg-surface-elevated',
  );

  if (navigable) {
    const handleKeyDown = (event: KeyboardEvent<HTMLAnchorElement>) => {
      if (event.repeat) {
        return;
      }
      if (event.key === ' ' || event.key === 'Enter') {
        event.preventDefault();
        event.currentTarget.click();
      }
    };
    return (
      <div className={cn('relative', containerClass)} style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}>
        <Link
          to="/workflows/$workflowRunId"
          params={{ workflowRunId: row.runId as string }}
          aria-label={ariaLabel}
          className="absolute inset-0 z-[1] rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-queue-item-state={state}
          data-variant="operator"
          data-testid="run-review-queue-item"
          onKeyDown={handleKeyDown}
        />
        <OperatorRowBody
          row={row}
          density={density}
          nowMs={nowMs}
          onClassify={onClassify}
          classificationLabel={classificationLabel}
        />
      </div>
    );
  }

  return (
    <div
      aria-label={ariaLabel !== '' ? ariaLabel : undefined}
      aria-disabled={effectivelyDisabled ? true : undefined}
      className={containerClass}
      style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}
      data-queue-item-state={state}
      data-variant="operator"
      data-testid="run-review-queue-item"
    >
      <OperatorRowBody row={row} density={density} nowMs={nowMs} classificationLabel={classificationLabel} />
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
  onClassify,
  classificationLabel,
}: RunReviewQueueItemProps) {
  if (variant === 'operator') {
    return (
      <OperatorRow
        row={run}
        selected={selected}
        unread={unread}
        disabled={disabled}
        density={density}
        onClassify={onClassify}
        classificationLabel={classificationLabel}
      />
    );
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
    // Story 3.31 (D1) — the STRETCHED-LINK pattern. Story 2.15 wrapped the whole row in a
    // single `<Link>`; a nested PR `<a>` inside an anchor is invalid HTML + an a11y failure.
    // Instead the row is a `relative` wrapper, the open-run `<Link>` is an absolutely-positioned
    // `inset-0` overlay (still the focusable open-run control — Enter/Space/click all open the
    // run), and the PR link is rendered as a sibling at `relative z-[2]` (in PrLinkageDetails)
    // so it escapes the overlay and is independently clickable. The testid + state + keyboard
    // handlers stay on the `<Link>` so it remains the row's open-run control.
    return (
      <div className={cn('relative', containerClass)} style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}>
        <Link
          to="/workflows/$workflowRunId"
          params={{ workflowRunId: run.runId as string }}
          aria-label={ariaLabel}
          className="absolute inset-0 z-[1] rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-queue-item-state={state}
          data-variant="reviewer"
          data-testid="run-review-queue-item"
          onClick={logOpen}
          onKeyDown={handleKeyDown}
        />
        {body}
      </div>
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
