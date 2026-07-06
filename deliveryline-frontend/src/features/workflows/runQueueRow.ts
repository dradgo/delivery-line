/**
 * Story 2.15 (AC1, AC3, AC5) — the `RunReviewQueueItem` view-model + pure helpers.
 *
 * Mirrors story 2.16's `runContextView` pattern EXACTLY: a rich view-model
 * (`RunQueueRow`) built to the full epic anatomy + a single pure mapper
 * (`toRunQueueRow`) that is the one `WorkflowSummary → RunQueueRow` seam. Epic 3 /
 * story 6.9 enrich the mapper later; today it populates ONLY the 7 fields the live
 * list contract exposes and leaves the rest `undefined`.
 *
 * THE CENTRAL RECONCILIATION (same rule as 2.16): the epic's 12-field
 * `RunQueueRow` predates the real backend read model. The live
 * `GET /api/v1/workflows → WorkflowSummary[]` carries only 7 fields. The live
 * contract is authoritative — `summary`, `blockerCount`, `openQuestionCount`,
 * `currentArtifactType`, `assigneeHint`, and `staleIndicator` are DORMANT (built +
 * tested via constructed fixtures, never fabricated from live data).
 *
 * `lastTransitionAt` maps from `lastEventAt`, NOT `lastActivityTimestamp` (Trap T2;
 * the list summary carries no `lastActivityTimestamp` at all — Trap T3).
 *
 * Kept in a NON-component module (per `frontend-react-refresh-no-fn-exports`) so the
 * component file exports only components and these pure helpers stay unit-testable.
 */
import type { WorkflowSummary } from '@/lib/api/queryOptions';

import type { PrLinkageView } from './prLinkageView';

/**
 * The full Queue-Item anatomy (epic AC1). Fields absent from the live
 * `WorkflowSummary` are optional and stay `undefined` until the backend projection
 * grows (story 6.9) — the component renders + tests them via constructed fixtures.
 */
export interface RunQueueRow {
  /** Run public id (`workflowRunId`) — validated with `isValidRunId` before routing. */
  runId?: string | undefined;
  /** Linear ticket reference (`ticketRef`, e.g. `DEL-1234`); absent ⇒ no ticket chip. */
  linearTicketReference?: string | undefined;
  /**
   * Story 3g-2 (FR73) — LIVE originating ticket title from the summary read model
   * (`WorkflowSummary.ticketTitle`). The queue's human label PREFERS this over the
   * machine `linearTicketReference`; absent (pre-3g / unlinked) ⇒ the label falls back
   * to the ref (never a blank cell). Untrusted plain text (React-escaped at render).
   */
  ticketTitle?: string | undefined;
  /** Short scannable summary line — DORMANT (no live source); render only when present. */
  summary?: string | undefined;
  /** Backend `currentState` enum → `WorkflowStateBadge`. */
  currentState?: string | undefined;
  /** Latest artifact type — DORMANT (list summary has no artifact projection). */
  currentArtifactType?: string | undefined;
  /** Last transition instant (ISO-8601 UTC) — from `lastEventAt` (Trap T2). */
  lastTransitionAt?: string | undefined;
  /** Assignee hint — DORMANT (no live source). */
  assigneeHint?: string | undefined;
  /** Active blocker count — DORMANT (drives the `blocked` state via fixtures only). */
  blockerCount?: number | undefined;
  /** Open-question count — DORMANT (no live source). */
  openQuestionCount?: number | undefined;
  /** Escalation marker — a real live trust signal. */
  escalationMarker?: boolean | undefined;
  /** Accumulated spec rejections — a real live trust signal ("N rejections"). */
  specRejectionLoopCount?: number | undefined;
  /** Stale flag — DORMANT (not derivable from the live list; Trap T3). */
  staleIndicator?: boolean | undefined;
  /**
   * Story 3.30 (AC8) — failure category for a `Failed` run, compact form. DORMANT: the
   * live `WorkflowSummary` carries no `failureCategory` (only `currentState`), so this
   * is built/tested via constructed fixtures only, never fabricated from live data.
   */
  failureCategory?: string | undefined;
  /**
   * Story 3.31 — backend-truth GitHub PR linkage for the compact queue element.
   * DORMANT: the live `WorkflowSummary` carries no PR projection (same reconciliation
   * as `summary`/`staleIndicator`), so this is built/tested via constructed fixtures
   * only and never fabricated. PR linkage is metadata, NOT an attention/dominant-state
   * signal — it does not feed `resolvePrimaryAttentionIndicator`/`resolveQueueItemState`.
   */
  prLinkage?: PrLinkageView | undefined;
  /**
   * Story 3.29 (AC3/R5) — the developer who took over, for a `TakenOver` row's
   * secondary metadata. DORMANT: the lean `WorkflowSummary` list payload carries no
   * attribution and per-row event fetches are out of scope (same reconciliation as
   * `summary`/`prLinkage`), so this is built/tested via constructed fixtures only and
   * never fabricated from live data. Takeover is metadata, NOT an attention/dominant
   * signal — it does not feed `resolvePrimaryAttentionIndicator`/`resolveQueueItemState`.
   */
  takenOverBy?: string | undefined;
  /** Story 3.29 (AC3/R5) — when the takeover happened; DORMANT (see {@link takenOverBy}). */
  takenOverAt?: string | undefined;
  /**
   * Story 3d-8 (AC5) — LIVE: true when the run is soft-hidden (archived). Derived from the
   * `WorkflowSummary.archivedAt` marker (non-null ⇒ archived). Renders a color-independent
   * "Hidden" chip in the secondary cluster; metadata, NOT an attention/dominant-state signal.
   */
  archived?: boolean | undefined;
  /** Story 3f-6 - LIVE project attribution from the backend summary. */
  projectId?: string | undefined;
  projectName?: string | undefined;
  projectSlug?: string | undefined;
  /**
   * Story 4.2 (AC2) — operator queue: the run's project-level runner kind
   * (`codex`/`claude`/`manual`), sourced from `OperatorRunRow.runnerKind`. Absent on the reviewer
   * queue (dormant there); rendered as a chip on the operator variant. Never fabricated.
   */
  runnerKind?: string | undefined;
  /**
   * Story 4.2 (AC2/Reconciliation 5) — operator queue: the server-derived UPPERCASE state signifier
   * (`ORPHANED`/`FAILED`/`TAKENOVER`/`STALLED`/`OVERRIDDEN`/else the uppercased state). The operator
   * row renders its badge FROM this, NEVER re-deriving from `currentState`. Absent on the reviewer
   * queue.
   */
  operatorSignifier?: string | undefined;
  /**
   * Story 4.2 (AC2/OQ-LASTACTION) — operator queue: last operator-action timestamp. E4 has no
   * distinct operator-action column, so this proxies `lastTransitionAt` (documented). Absent on the
   * reviewer queue.
   */
  lastOperatorActionAt?: string | undefined;
}

/**
 * The single typed union of primary-attention signals (AC5). Ordered by the
 * documented priority `failed > blocker > escalation > openQuestion > stale` — story
 * 3.30 (AC8) elevates `failed` to rank alongside/above `blocker` for visibility.
 */
export type AttentionIndicator = 'failed' | 'blocker' | 'escalation' | 'openQuestion' | 'stale';

/** The mutually-exclusive dominant row state (AC3), surfaced as `data-queue-item-state`. */
export type QueueItemState =
  | 'default'
  | 'selected'
  | 'unread'
  | 'failed'
  | 'blocked'
  | 'stale'
  | 'disabled';

/**
 * The ONE `WorkflowSummary → RunQueueRow` seam (AC1). Maps the 7 live fields per
 * the reconciliation table; every field the live list does not expose is left
 * `undefined` (built + tested via fixtures, never fabricated). `lastTransitionAt`
 * comes from `lastEventAt` (Trap T2).
 */
export function toRunQueueRow(summary: WorkflowSummary): RunQueueRow {
  return {
    runId: summary.workflowRunId,
    linearTicketReference: summary.ticketRef,
    // Story 3g-2 (FR73) — LIVE: prefer the human ticket title; collapse a wire `null` OR a
    // blank/whitespace-only title to `undefined` so the queue label falls back to the ref
    // (AC1 "never a blank label"). A bare `?? undefined` would let `""` survive and render an
    // empty prominent label — mirror the sibling `runOriginView.ts` present/trim posture.
    ticketTitle:
      summary.ticketTitle != null && summary.ticketTitle.trim() !== ''
        ? summary.ticketTitle
        : undefined,
    currentState: summary.currentState,
    lastTransitionAt: summary.lastEventAt,
    escalationMarker: summary.escalationMarker,
    specRejectionLoopCount: summary.specRejectionLoopCount,
    // Story 3d-8 — LIVE: the archived/hidden marker (non-null archivedAt ⇒ archived).
    archived: summary.archivedAt != null,
    projectId: summary.projectId ?? undefined,
    projectName: summary.projectName ?? undefined,
    projectSlug: summary.projectSlug ?? undefined,
    // DORMANT — no live source (reconciliation): summary, currentArtifactType,
    // assigneeHint, blockerCount, openQuestionCount, staleIndicator, failureCategory.
    // The `Failed` BADGE + attention indicator ARE live (from `currentState`); only the
    // compact failure-category chip is dormant (the list summary has no category).
    summary: undefined,
    currentArtifactType: undefined,
    assigneeHint: undefined,
    blockerCount: undefined,
    openQuestionCount: undefined,
    staleIndicator: undefined,
    failureCategory: undefined,
    // Story 3.31 — DORMANT: the live list summary has no PR projection.
    prLinkage: undefined,
    // Story 3.29 — DORMANT: the lean list summary carries no takeover attribution.
    takenOverBy: undefined,
    takenOverAt: undefined,
  };
}

/** A count is "active" when it is a positive number (defensive against undefined/0/NaN). */
function isPositive(count: number | undefined): boolean {
  return typeof count === 'number' && count > 0;
}

/**
 * AC5 — return the SINGLE highest-priority active attention signal, or `null` when
 * none is active. Priority `blocker > escalation > openQuestion > stale`; the
 * non-winning signals demote to the secondary trust cluster (never the primary
 * slot). Today live data can only yield `escalation`; fixtures exercise the ladder.
 */
export function resolvePrimaryAttentionIndicator(row: RunQueueRow): AttentionIndicator | null {
  // Story 3.30 (AC8) — a failed run's `failed` indicator outranks every other signal
  // (failure ranks at/above `blocker` for visibility). LIVE from `currentState`.
  if (row.currentState === 'Failed') {
    return 'failed';
  }
  if (isPositive(row.blockerCount)) {
    return 'blocker';
  }
  if (row.escalationMarker === true) {
    return 'escalation';
  }
  if (isPositive(row.openQuestionCount)) {
    return 'openQuestion';
  }
  if (row.staleIndicator === true) {
    return 'stale';
  }
  return null;
}

export interface ResolveQueueItemStateInput {
  readonly row: RunQueueRow;
  readonly selected?: boolean | undefined;
  readonly unread?: boolean | undefined;
  readonly disabled?: boolean | undefined;
}

/**
 * AC3 — collapse the row inputs to EXACTLY ONE dominant state. Documented
 * precedence (highest first): `disabled > blocked > stale > selected > unread >
 * default`. Centralizing it here makes the states mutually exclusive by
 * construction (mirrors `resolveQueueState`, story 2.20). `blocked`/`stale` are
 * dormant from live data but fully supported via constructed fixtures (Traps T3/T4).
 */
export function resolveQueueItemState({
  row,
  selected,
  unread,
  disabled,
}: ResolveQueueItemStateInput): QueueItemState {
  if (disabled === true) {
    return 'disabled';
  }
  // Story 3.30 (AC8) — `failed` is the dominant row state for a failed run (LIVE from
  // `currentState`), ranking just below `disabled` and above `blocked`.
  if (row.currentState === 'Failed') {
    return 'failed';
  }
  if (isPositive(row.blockerCount)) {
    return 'blocked';
  }
  if (row.staleIndicator === true) {
    return 'stale';
  }
  if (selected === true) {
    return 'selected';
  }
  if (unread === true) {
    return 'unread';
  }
  return 'default';
}
