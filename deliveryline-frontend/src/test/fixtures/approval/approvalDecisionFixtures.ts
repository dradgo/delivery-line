/**
 * Story 2.19 (Task 1) — `ApprovalDecisionView` fixtures.
 *
 * One constructed view per AC3 state (the bar is presentational + prop-driven; every
 * render is driven from these fixtures, never fabricated from live data — the
 * 2.17/2.18 discipline). The mutation-driven states (`submitting`/`success`/`error`/
 * `stale`) pair a view fixture with an {@link ApprovalMutationState}/`ApprovalLocalUi`
 * in the test; the view fixtures here carry the scenario shape.
 */
import type {
  ApprovalDecisionView,
  ApprovalVersionStamp,
  DecisionSummary,
} from '@/features/workflows/approvalDecisionView';

const RUN_ID = 'run_appr_demo_001';
const ARTIFACT_ID = 'art_spec_appr_001';
const IMPL_ARTIFACT_ID = 'art_impl_appr_001';

/** A live version stamp with BOTH ints present → `deriveExpectedVersions` succeeds. */
export const versionStampFull: ApprovalVersionStamp = {
  currentSpecArtifactVersion: 3,
  currentContextBundleVersion: 1,
  lastEventId: 'evt_appr_100',
  workflowState: 'WaitingForSpecApproval',
};

/** A null-version stamp → `deriveExpectedVersions` returns null → contributes to blocked. */
export const versionStampNullVersions: ApprovalVersionStamp = {
  currentSpecArtifactVersion: null,
  currentContextBundleVersion: null,
  lastEventId: 'evt_appr_100',
  workflowState: 'WaitingForSpecApproval',
};

/** A later stamp than {@link versionStampFull} → `isStaleAgainst` returns true. */
export const versionStampAdvanced: ApprovalVersionStamp = {
  currentSpecArtifactVersion: 4,
  currentContextBundleVersion: 1,
  lastEventId: 'evt_appr_217',
  workflowState: 'WaitingForSpecApproval',
};

export const decisionSummaryApproved: DecisionSummary = {
  decision: 'approved',
  resultingState: 'Executing',
  decidedAt: '2026-06-04T10:30:00.000Z',
  actor: 'Alex (product_reviewer)',
  correlationId: 'corr_appr_001',
};

function baseView(overrides: Partial<ApprovalDecisionView> = {}): ApprovalDecisionView {
  return {
    workflowRunId: RUN_ID,
    mode: 'spec_approval',
    layout: 'sticky_footer',
    actions: ['approve_spec', 'reject_spec'],
    versionStamp: versionStampFull,
    currentState: 'WaitingForSpecApproval',
    decisionContextLabel: 'Approve specification v3 by Alex (product_reviewer)',
    artifactId: ARTIFACT_ID,
    ...overrides,
  };
}

/** `ready` — actions enabled, artifact resolvable, versions present. */
export const readyView = baseView();

/** `blocked` — no affirmative primary action (AC5c / AC7). */
export const blockedNoPrimaryView = baseView({ actions: ['answer_clarification'] });

/** `blocked` — clarifications pending incorporation (AC13, DORMANT/fixture-driven). */
export const blockedPendingClarificationsView = baseView({
  actions: [],
  pendingClarifications: 2,
});

/** `blocked` — THE dormancy boundary: no resolvable `artifactId` (T-ARTIFACTID). */
export const blockedNoArtifactView = baseView({ artifactId: undefined });

/** `blocked` — null version stamp → cannot build a firing request (T-VERSIONSTAMP). */
export const blockedNullVersionsView = baseView({ versionStamp: versionStampNullVersions });

/** `stale` source — paired with `localUi.stale` or a 409 mutation error in the test. */
export const staleView = baseView();

// ---- Story 3.28 — implementation_review fixtures -------------------------------

/** A takeover summary WITH a preserved PR reference (AC7 — renders the "Continue in PR" link). */
export const decisionSummaryTakenOverWithPr: DecisionSummary = {
  decision: 'takenover',
  resultingState: 'TakenOver',
  decidedAt: '2026-06-16T09:15:00.000Z',
  actor: 'Alex (developer)',
  correlationId: 'corr_takeover_001',
  preservedPrReference: 'octo/repo#42',
  cancelledInFlightCount: 1,
  cancelledQueuedCount: 2,
};

/** A takeover summary with NO preserved PR reference (AC7 — label-only, no link). */
export const decisionSummaryTakenOverNoPr: DecisionSummary = {
  decision: 'takenover',
  resultingState: 'TakenOver',
  decidedAt: '2026-06-16T09:15:00.000Z',
  actor: 'Alex (developer)',
  correlationId: 'corr_takeover_002',
};

/** An accepted summary (AC6 — persisted post-submit outcome). */
export const decisionSummaryAccepted: DecisionSummary = {
  decision: 'accepted',
  resultingState: 'Executing',
  decidedAt: '2026-06-16T09:15:00.000Z',
  actor: 'Alex (developer)',
  correlationId: 'corr_accept_001',
};

/** `ready` — all three developer actions present, implementation artifact + versions resolvable. */
export const implementationReviewReadyView = baseView({
  mode: 'implementation_review',
  actions: ['accept_implementation', 'reject_implementation', 'takeover_workflow'],
  currentState: 'WaitingForReview',
  decisionContextLabel: 'Review implementation v2 by Alex (developer)',
  artifactId: IMPL_ARTIFACT_ID,
});

/** `blocked` accept/reject (no implementation artifact) BUT takeover stays available (R1). */
export const implementationReviewTakeoverOnlyView = baseView({
  mode: 'implementation_review',
  actions: ['accept_implementation', 'reject_implementation', 'takeover_workflow'],
  currentState: 'WaitingForReview',
  decisionContextLabel: 'Review implementation by Alex (developer)',
  artifactId: undefined,
});

/** `blocked` — no actions at all (degenerate; the prior E3-stub fixture, now a real blocked render). */
export const implementationReviewView = baseView({
  mode: 'implementation_review',
  actions: [],
  currentState: 'WaitingForReview',
  artifactId: undefined,
});

/** `success` (takeover) — paired with `{ status: 'success' }`; carries the takeover summary + PR ref. */
export const implementationReviewSuccessTakenOverView = baseView({
  mode: 'implementation_review',
  actions: ['accept_implementation', 'reject_implementation', 'takeover_workflow'],
  currentState: 'TakenOver',
  decisionContextLabel: 'Review implementation v2 by Alex (developer)',
  artifactId: IMPL_ARTIFACT_ID,
  lastDecision: decisionSummaryTakenOverWithPr,
});

/** `success` (accept) — paired with `{ status: 'success' }`; carries the accepted summary. */
export const implementationReviewSuccessAcceptedView = baseView({
  mode: 'implementation_review',
  actions: ['accept_implementation', 'reject_implementation', 'takeover_workflow'],
  currentState: 'Executing',
  decisionContextLabel: 'Review implementation v2 by Alex (developer)',
  artifactId: IMPL_ARTIFACT_ID,
  lastDecision: decisionSummaryAccepted,
});

/**
 * Story 3.30 — `recovery_operator` "View only": a Failed run whose allowed-actions do
 * NOT include `retry` → `disabled` (no safe recovery action). Replaces the old E4 stub.
 */
export const recoveryOperatorView = baseView({
  mode: 'recovery_operator',
  actions: [],
  currentState: 'Failed',
  artifactId: undefined,
});

/**
 * Story 3.30 — `recovery_operator` `ready`: a Failed run whose live allowed-actions
 * include `retry` → the `Retry failed step` primary + confirm-before dialog.
 */
export const recoveryReadyView = baseView({
  mode: 'recovery_operator',
  actions: ['retry'],
  currentState: 'Failed',
  decisionContextLabel: 'Recover the failed run at the implementation stage',
  artifactId: undefined,
});

/** `submitting`/`error` source — paired with the matching mutation status in the test. */
export const submittingView = baseView();

/** `success` — carries the persisted post-submit summary (AC9). */
export const successView = baseView({ lastDecision: decisionSummaryApproved });

/** `locked` — a decision already made; read-only (paired with `localUi.locked`). */
export const lockedView = baseView({
  actions: [],
  lastDecision: decisionSummaryApproved,
});

/** AC7 — multiple candidate actions present; exactly one must render primary. */
export const multiCandidateView = baseView({ actions: ['approve_spec', 'reject_spec'] });

/**
 * AC5c (DORMANT) + AC10 — the secondary `reject_spec` action is present but withheld
 * with a reason code, so it renders DISABLED with an `aria-describedby` link to its
 * mapped explanation (the canonical disabled-control-with-rationale for the a11y test).
 * `approve_spec` stays the enabled primary (a primary is never disabled — T-ONE-PRIMARY).
 */
export const reasonCodedView = baseView({
  actions: ['approve_spec', 'reject_spec'],
  disabledReasons: { reject_spec: 'ROLE_RESTRICTED' },
});

/** An `inline_section` layout variant (AC4). */
export const inlineLayoutView = baseView({ layout: 'inline_section' });
