/**
 * Story 2.18 (AC1, AC2, AC3, AC6, AC10) — the frontend-owned `ClarificationView`
 * contract + pure view-model helpers.
 *
 * THE CENTRAL RECONCILIATION (Dev Notes): the backend exposes NO clarification-read
 * endpoint and NO `ClarificationsView` / `ClarificationView` type — `schema.d.ts`
 * carries ONLY `POST .../clarifications/{id}/answer` (`answerClarification`). The
 * backend inspection methods (`getClarifications`/`getClarificationStatus`/
 * `countPendingByWorkflowRun`, stories 2.11/2.12) exist in the Java service but are
 * NOT REST-exposed, so they are invisible here. Therefore `ClarificationView` is a
 * FRONTEND-OWNED type modeling the epic's intended read model — populated by the
 * future clarification-read story. No live source today; the region is presentational
 * and every render is driven by constructed fixtures (T4 — never fabricated from
 * `WorkflowDetail`, which carries no clarification data).
 *
 * These helpers live in this `.ts` sibling (NOT the `.tsx`) so the region/container
 * import them without tripping `react-refresh/only-export-components`
 * (`frontend-react-refresh-no-fn-exports`).
 *
 * @see ./artifactView.ts — the story-2.17 dormant/live template this mirrors.
 */
import type { StateName } from '@/lib/state-signifiers';

/**
 * The clarification lifecycle (story 2.12 AC3 / UX-DR11). `'unknown'` is the sentinel
 * the backend `ClarificationAnswerResponse.clarificationStatus` may carry on
 * idempotent replays of hard-deleted legacy rows (schema.d.ts:249) — TS clients
 * default-case unknown statuses rather than narrowing exhaustively.
 */
export type ClarificationLifecycleStatus =
  | 'open'
  | 'answered'
  | 'accepted'
  | 'incorporated'
  | 'superseded'
  | 'rejected_invalid'
  | 'unknown';

/**
 * One clarification carrying the FULL epic anatomy. The future clarification-read
 * endpoint will supply these; today they come only from fixtures.
 *
 * Field source map (what the future read endpoint will supply):
 *   • clarificationId           — the clarification's own public id (`cla_…`).
 *   • workflowRunId             — the run it belongs to.
 *   • artifactId/artifactVersion — the artifact version this clarification binds to
 *                                  (the mutation's `artifactId` + `expectedArtifactVersion`).
 *   • questionId                — stable per-question id (NEVER logged free-text).
 *   • questionText              — UNTRUSTED — rendered ONLY via `SafeMarkdownRenderer`.
 *   • status                    — the lifecycle status above.
 *   • answerText                — UNTRUSTED reviewer wording — sanitized render only.
 *   • answeredBy / answeredAt   — who/when the answer was recorded.
 *   • acceptedAt/incorporatedAt — lifecycle advance timestamps.
 *   • incorporatedIntoArtifactId — the artifact version the answer was applied to.
 *   • supersededByArtifactId    — the artifact version that set this aside.
 *   • noEffectReason            — the explicit make-or-break reason for a
 *                                 `superseded`/`rejected_invalid` outcome (AC6).
 */
export interface ClarificationView {
  readonly clarificationId: string;
  readonly workflowRunId: string;
  readonly artifactId: string;
  readonly artifactVersion: number;
  readonly questionId: string;
  /** UNTRUSTED — rendered EXCLUSIVELY through `SafeMarkdownRenderer`. */
  readonly questionText: string;
  readonly status: ClarificationLifecycleStatus;
  /** UNTRUSTED reviewer wording — rendered EXCLUSIVELY through `SafeMarkdownRenderer`. */
  readonly answerText?: string;
  readonly answeredByActor?: string;
  readonly answeredByActorType?: string;
  readonly answeredAt?: string;
  readonly acceptedAt?: string;
  readonly incorporatedAt?: string;
  readonly incorporatedIntoArtifactId?: string;
  readonly supersededByArtifactId?: string;
  /** Explicit no-effect reason for `superseded`/`rejected_invalid` (AC6). */
  readonly noEffectReason?: string;
  readonly createdAt: string;
}

/** The list shape the region renders (AC1). */
export interface ClarificationsView {
  readonly clarifications: ClarificationView[];
}

/**
 * The single render state for ONE clarification item (AC3), exposed as
 * `data-clarification-item-state` for state-detection tests. Backend statuses plus
 * the local UI states (`in_progress`, `blocked_invalid`, `error` — OQ-3) that layer
 * over the backend status via {@link resolveClarificationItemState}.
 */
export type ClarificationItemState =
  | 'unanswered' // backend `open`, no draft
  | 'in_progress' // backend `open` + a local unsubmitted draft
  | 'answered' // submitted, awaiting acceptance (pending incorporation)
  | 'accepted' // queued for incorporation in the next spec version
  | 'incorporated' // visibly applied — the happy outcome
  | 'superseded' // set aside, with an explicit reason
  | 'rejected_invalid' // rejected, with a reason
  | 'blocked_invalid' // local client-side validation error
  | 'error' // local submit/network error
  | 'unknown'; // backend sentinel (hard-deleted legacy row)

/** Local, per-question UI overlay (OQ-3) — never a backend status. */
export interface ClarificationDraft {
  /** The in-progress answer text the reviewer is typing (not yet submitted). */
  readonly text?: string | undefined;
  /** A client-side validation error (e.g. empty submit) — drives `blocked_invalid`. */
  readonly validationError?: string | undefined;
  /** A submit/network error code — drives `error`. */
  readonly errorCode?: string | undefined;
  /** Whether a submit is currently in flight. */
  readonly submitting?: boolean | undefined;
}

/**
 * Map a clarification (+ optional local draft) to its AC3 render state. Local UI
 * states take precedence over the backend status (OQ-3): a client-side error or
 * validation failure is shown over the underlying `open`/`answered` status; a
 * non-empty unsubmitted draft on an `open` question reads as `in_progress`.
 */
export function resolveClarificationItemState(
  view: ClarificationView,
  draft?: ClarificationDraft,
): ClarificationItemState {
  if (draft?.errorCode !== undefined) {
    return 'error';
  }
  if (draft?.validationError !== undefined) {
    return 'blocked_invalid';
  }
  switch (view.status) {
    case 'open':
      return draft?.text !== undefined && draft.text.trim() !== '' ? 'in_progress' : 'unanswered';
    case 'answered':
      return 'answered';
    case 'accepted':
      return 'accepted';
    case 'incorporated':
      return 'incorporated';
    case 'superseded':
      return 'superseded';
    case 'rejected_invalid':
      return 'rejected_invalid';
    case 'unknown':
      return 'unknown';
    default:
      return assertNeverStatus(view.status);
  }
}

/** Exhaustiveness guard — a new lifecycle status without a branch fails `tsc`. */
function assertNeverStatus(status: never): never {
  throw new Error(`Unhandled ClarificationLifecycleStatus: ${String(status)}`);
}

/** The non-color signifier (story 2.3 AC5) backing each item's `StateSignifierChip`. */
export interface ClarificationSignifier {
  /** Semantic state driving the chip's color token + icon. */
  readonly stateName: StateName;
  /** The visible text label paired with the icon (never color-alone). */
  readonly label: string;
}

const ITEM_STATE_SIGNIFIERS: Record<ClarificationItemState, ClarificationSignifier> = {
  unanswered: { stateName: 'warning', label: 'Open' },
  in_progress: { stateName: 'draft', label: 'In progress' },
  answered: { stateName: 'informational', label: 'Answered · pending incorporation' },
  accepted: { stateName: 'selected', label: 'Accepted' },
  incorporated: { stateName: 'success', label: 'Incorporated' },
  superseded: { stateName: 'stale', label: 'Superseded' },
  rejected_invalid: { stateName: 'error', label: 'Rejected' },
  blocked_invalid: { stateName: 'blocker', label: 'Invalid answer' },
  error: { stateName: 'error', label: 'Error' },
  unknown: { stateName: 'informational', label: 'Unknown' },
};

/** Resolve an item state to its non-color signifier (icon + label). */
export function clarificationItemSignifier(state: ClarificationItemState): ClarificationSignifier {
  return ITEM_STATE_SIGNIFIERS[state];
}

/**
 * The grouped/sorted view (AC2): `open` first, then `answered`/`accepted` pending,
 * then the terminal states (`incorporated`/`superseded`/`rejected_invalid`) which the
 * region collapses by default. `unknown` sentinels sit in their own bucket so a
 * hard-deleted legacy row never masquerades as a real lifecycle state.
 */
export interface GroupedClarifications {
  readonly open: ClarificationView[];
  readonly pending: ClarificationView[];
  readonly terminal: ClarificationView[];
  readonly unknown: ClarificationView[];
}

const TERMINAL_STATUSES: ReadonlySet<ClarificationLifecycleStatus> = new Set([
  'incorporated',
  'superseded',
  'rejected_invalid',
]);

/**
 * Partition a view into the AC2 display groups, preserving input order WITHIN each
 * group (the read model decides intra-group ordering; the region only enforces the
 * cross-group precedence).
 */
export function groupClarificationsByStatus(view: ClarificationsView): GroupedClarifications {
  const open: ClarificationView[] = [];
  const pending: ClarificationView[] = [];
  const terminal: ClarificationView[] = [];
  const unknown: ClarificationView[] = [];
  for (const clarification of view.clarifications) {
    switch (clarification.status) {
      case 'open':
        open.push(clarification);
        break;
      case 'answered':
      case 'accepted':
        pending.push(clarification);
        break;
      case 'incorporated':
      case 'superseded':
      case 'rejected_invalid':
        terminal.push(clarification);
        break;
      case 'unknown':
        unknown.push(clarification);
        break;
      default:
        assertNeverStatus(clarification.status);
    }
  }
  return { open, pending, terminal, unknown };
}

/**
 * Count clarifications that still BLOCK approval (AC10). T8 — `superseded` counts as
 * pending: the workflow stays blocked until a fresh answer is incorporated or the
 * clarification is `rejected_invalid` (story 2.12/2.14 AC4). So this excludes the
 * terminal `incorporated` + `rejected_invalid` AND the `unknown` sentinel (a
 * hard-deleted legacy row is a non-actionable historical artifact — review finding
 * Decision-① — so it must NOT inflate the gate). Everything else (open/answered/
 * accepted/superseded) still counts as pending incorporation.
 */
export function countPendingIncorporation(view: ClarificationsView): number {
  return view.clarifications.filter(
    (clarification) =>
      clarification.status !== 'incorporated' &&
      clarification.status !== 'rejected_invalid' &&
      clarification.status !== 'unknown',
  ).length;
}

/** The fixed happy-path lifecycle chain rendered by the per-question indicator (AC2). */
export const LIFECYCLE_STAGES = ['submitted', 'accepted', 'incorporated'] as const;
export type LifecycleStage = (typeof LIFECYCLE_STAGES)[number];

/**
 * The per-question lifecycle indicator position (AC2/AC5). `currentIndex` is the
 * furthest stage reached (−1 = not yet submitted); `offChain` marks a clarification
 * that left the happy chain (`superseded`/`rejected_invalid`) — the region surfaces
 * the no-effect reason rather than a green "incorporated" (AC6).
 */
export interface LifecyclePosition {
  readonly stages: typeof LIFECYCLE_STAGES;
  /** −1 = not submitted; 0 = submitted; 1 = accepted; 2 = incorporated. */
  readonly currentIndex: number;
  /** True when the clarification was set aside off the happy chain. */
  readonly offChain: boolean;
}

/** Resolve a backend status to its happy-chain position. */
export function resolveLifecyclePosition(status: ClarificationLifecycleStatus): LifecyclePosition {
  switch (status) {
    case 'open':
    case 'unknown':
      return { stages: LIFECYCLE_STAGES, currentIndex: -1, offChain: false };
    case 'answered':
      return { stages: LIFECYCLE_STAGES, currentIndex: 0, offChain: false };
    case 'accepted':
      return { stages: LIFECYCLE_STAGES, currentIndex: 1, offChain: false };
    case 'incorporated':
      return { stages: LIFECYCLE_STAGES, currentIndex: 2, offChain: false };
    case 'superseded':
    case 'rejected_invalid':
      // It WAS submitted, then set aside — show submitted reached + the off-chain flag.
      return { stages: LIFECYCLE_STAGES, currentIndex: 0, offChain: true };
    default:
      return assertNeverStatus(status);
  }
}

/**
 * Runtime guard for FUTURE clarification-read data. The hook is a disabled stub today,
 * but when it becomes live this prevents a partial/foreign shape from being cast into
 * region props. Validates the shared required fields + the lifecycle status.
 */
const VALID_STATUSES: ReadonlySet<string> = new Set<ClarificationLifecycleStatus>([
  'open',
  'answered',
  'accepted',
  'incorporated',
  'superseded',
  'rejected_invalid',
  'unknown',
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * Coerce an arbitrary status string to the known union — review finding Decision-②.
 * An unrecognized status (a server-side status not yet in this frontend-owned union)
 * degrades to the `unknown` sentinel rather than throwing or blanking the view; the
 * region renders it as a non-actionable historical row.
 */
export function coerceStatus(status: string): ClarificationLifecycleStatus {
  return VALID_STATUSES.has(status) ? (status as ClarificationLifecycleStatus) : 'unknown';
}

/**
 * Structural shape check — all required fields present and `status` a string, WITHOUT
 * asserting status membership (that is handled by {@link coerceStatus} so one
 * unrecognized status never invalidates a whole row/view — Decision-②).
 */
function hasClarificationViewShape(
  value: unknown,
): value is Omit<ClarificationView, 'status'> & { status: string } {
  return (
    isRecord(value) &&
    typeof value.clarificationId === 'string' &&
    typeof value.workflowRunId === 'string' &&
    typeof value.artifactId === 'string' &&
    typeof value.artifactVersion === 'number' &&
    Number.isFinite(value.artifactVersion) &&
    typeof value.questionId === 'string' &&
    typeof value.questionText === 'string' &&
    typeof value.status === 'string' &&
    typeof value.createdAt === 'string'
  );
}

function isClarificationView(value: unknown): value is ClarificationView {
  return hasClarificationViewShape(value) && VALID_STATUSES.has(value.status);
}

/**
 * STRICT predicate — whether a value is already a canonical `ClarificationsView`
 * (every row structurally valid AND carrying a known status). Use
 * {@link normalizeClarificationsView} at the live-read boundary instead when you want
 * graceful degradation rather than a whole-view reject.
 */
export function isClarificationsView(value: unknown): value is ClarificationsView {
  return (
    isRecord(value) &&
    Array.isArray(value.clarifications) &&
    value.clarifications.every(isClarificationView)
  );
}

/**
 * Normalize FUTURE clarification-read data into a `ClarificationsView` (Decision-②).
 * Unlike the strict {@link isClarificationsView} guard, this NEVER blanks the whole
 * view on a single bad row: structurally-malformed rows are dropped and any
 * unrecognized status is coerced to `unknown`. This is the boundary the container
 * uses so one out-of-union row degrades gracefully instead of emptying the region.
 */
export function normalizeClarificationsView(value: unknown): ClarificationsView {
  if (!isRecord(value) || !Array.isArray(value.clarifications)) {
    return { clarifications: [] };
  }
  const clarifications: ClarificationView[] = [];
  for (const entry of value.clarifications) {
    if (hasClarificationViewShape(entry)) {
      clarifications.push({ ...entry, status: coerceStatus(entry.status) });
    }
  }
  return { clarifications };
}

export { TERMINAL_STATUSES };
