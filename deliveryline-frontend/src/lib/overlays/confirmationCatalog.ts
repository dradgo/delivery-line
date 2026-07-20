/**
 * Story 2.23 (AC4) — the confirm-before catalog.
 *
 * UX-DR18 names the actions that ALWAYS require a confirmation overlay:
 *   · reject with reason            (Approval/Decision Bar, story 2.19)
 *   · approve when stale / conflict (Approval/Decision Bar, story 2.19)
 *   · stop orchestrator processing  (Epic 3 — future consumer)
 *   · retry / recover when consequential (Epic 4 — future consumer)
 *
 * This module is **documentation + a typed lookup** that consumers import — it is
 * the AUTHORITATIVE source of "which actions confirm" (the `no-confirmation-for-
 * navigation` ESLint rule is only a best-effort guard-rail, T-AC3-PRAGMATIC).
 *
 * RECONCILIATION / OQ-1 — this story does NOT wire the catalog into any `done`
 * composite. 2.19's `ApprovalDecisionBar` already confirms reject-with-reason
 * region-locally; the catalog records the contract so future consumers (Epic 3
 * stop-orchestrator, Epic 4 retry/recover) adopt the shared overlay primitives
 * when their stories land. The retrofit of 2.19 onto the shared primitives is a
 * deferred follow-up.
 *
 * T-REFRESH: pure `.ts` (typed data + a literal record; no component).
 */
import type { OverlayIntent } from '@/components/overlays/overlayPresentation';

/** The catalogued confirm-before action ids (UX-DR18). */
export type ConfirmationActionId =
  | 'rejectWithReason'
  | 'approveWhenStaleOrConflict'
  | 'stopOrchestrator'
  | 'retryOrRecoverConsequential'
  | 'takeoverWorkflow'
  // Story 4.22 — the deeper recovery_operator confirmations (resume/pause via ConfirmationDialog,
  // rerun via RationaleCaptureDialog). Reconcile + classify have NO catalog entry — their dialogs
  // (owned by 4.23 / 4.24) carry their own consequence copy.
  | 'resumeRun'
  | 'pauseRun'
  | 'rerunFromStep';

/** Ordered list form of {@link ConfirmationActionId} for iteration (tests). */
export const CONFIRMATION_ACTION_IDS: readonly ConfirmationActionId[] = [
  'rejectWithReason',
  'approveWhenStaleOrConflict',
  'stopOrchestrator',
  'retryOrRecoverConsequential',
  'takeoverWorkflow',
  'resumeRun',
  'pauseRun',
  'rerunFromStep',
];

/** A single catalogued confirm-before action. */
export interface ConfirmationCatalogEntry {
  /** Stable action id (the record key). */
  readonly id: ConfirmationActionId;
  /** Always `true` — every catalogued action requires confirmation by definition. */
  readonly requiresConfirmation: true;
  /** The overlay intent the confirmation should carry. */
  readonly intent: OverlayIntent;
  /**
   * A non-empty template describing the consequence of confirming. A `{…}`
   * placeholder is interpolated by the consuming composite at call time; the
   * catalog stores the trusted template, never runner-supplied text.
   */
  readonly consequenceTemplate: string;
  /** The story that owns (or will own) wiring this action to the primitives. */
  readonly owningStory: string;
}

/**
 * The typed confirm-before catalog. Keyed by {@link ConfirmationActionId} so
 * TypeScript fails the build if an action is missing.
 */
export const CONFIRMATION_CATALOG: Record<ConfirmationActionId, ConfirmationCatalogEntry> = {
  rejectWithReason: {
    id: 'rejectWithReason',
    requiresConfirmation: true,
    intent: 'danger',
    consequenceTemplate:
      'Rejecting sends this specification back for rework with your reason. The current version will no longer be the candidate for approval.',
    owningStory: '2.19',
  },
  approveWhenStaleOrConflict: {
    id: 'approveWhenStaleOrConflict',
    requiresConfirmation: true,
    intent: 'warning',
    consequenceTemplate:
      'This view may be out of date or in conflict with a newer version. Approving anyway advances the run on the version you are viewing.',
    owningStory: '2.19',
  },
  stopOrchestrator: {
    id: 'stopOrchestrator',
    requiresConfirmation: true,
    intent: 'danger',
    consequenceTemplate:
      'Stopping halts orchestrator processing for this run. In-flight work is abandoned and the run will require manual recovery to resume.',
    owningStory: 'Epic 3',
  },
  retryOrRecoverConsequential: {
    id: 'retryOrRecoverConsequential',
    requiresConfirmation: true,
    intent: 'warning',
    // Story 3.30 (AC3) re-owns this entry: the exact consequence text the retry
    // confirmation dialog renders, and the owning story flips from the Epic-4
    // placeholder to 3.30 (the UI minimum-viable-recovery baseline).
    consequenceTemplate:
      'Retry will re-execute the last failed step with a fresh runner. The previous failure will be preserved in the timeline.',
    owningStory: '3.30',
  },
  // Story 3.28 (AC4 / R6) — the takeover confirmation consequence text. `consequenceTemplate`
  // is the VERBATIM copy of `openapi.json` `paths./api/v1/workflows/{workflowRunId}/
  // takeover.post.description` (story 3.25 AC9). `openapi-typescript` strips endpoint
  // descriptions from `schema.d.ts`, so there is NO runtime field to read — this catalog
  // constant IS the canonical UI copy (the same pattern story 3.30 used for the retry
  // consequence). Keep byte-for-byte in sync with the OpenAPI source; pinned by a test.
  takeoverWorkflow: {
    id: 'takeoverWorkflow',
    requiresConfirmation: true,
    intent: 'danger',
    consequenceTemplate:
      'Stops orchestrator dispatch, cancels all in-flight + queued runner executions, records a developer takeover, and transitions the run to the TakenOver terminal state while preserving all prior context (artifacts, audit trail, and the active GitHub PR link). This action is non-reversible in E3 — Epic 4 will add takeover-revert; until then, a taken-over run can only be closed by an operator action.',
    owningStory: '3.28',
  },
  // Story 4.22 (AC3) — resume is the low-severity forward action from Paused (safe), so `info`
  // (NOT takeover's `danger`): it returns the run to its prior executing state + re-enqueues work.
  resumeRun: {
    id: 'resumeRun',
    requiresConfirmation: true,
    intent: 'info',
    consequenceTemplate:
      'Resume will return the run to its prior executing state and re-enqueue runner work.',
    owningStory: '4.22',
  },
  // Story 4.22 (AC6) — pause is reversible (resumable later), so `warning` — a lower severity than
  // takeover's `danger`.
  pauseRun: {
    id: 'pauseRun',
    requiresConfirmation: true,
    intent: 'warning',
    consequenceTemplate:
      'Pause will halt orchestrator dispatch and cancel in-flight + queued runner work for this run. The run can be resumed later.',
    owningStory: '4.22',
  },
  // Story 4.22 (AC5) — rerun-from-step supersedes artifacts + invalidates the prior approval, so
  // `danger`. Rendered via the RationaleCaptureDialog (targetStep select + required reasonText).
  rerunFromStep: {
    id: 'rerunFromStep',
    requiresConfirmation: true,
    intent: 'danger',
    consequenceTemplate:
      'Rerun will re-execute the run from the selected safe step. Artifacts produced at or after that step are superseded and the corresponding approval is invalidated.',
    owningStory: '4.22',
  },
};
