/**
 * Story 3.28 (Task 5, AC1/AC5/AC6/AC7) — the thin data CONTAINER for the Decision Bar's
 * `implementation_review` mode.
 *
 * A sibling of `ApprovalDecisionBarContainer` (spec) / `RecoveryDecisionBarContainer`
 * (recovery): the developer technical-review path lives in its own small container rather
 * than overloading the spec one. All three feed the SAME presentational
 * `ApprovalDecisionBar`, which renders the real `implementation_review` branch.
 *
 * Eligibility (AC1 / UX-DR12): every action comes ONLY from `useAllowedActions` — the
 * frontend NEVER infers eligibility locally. accept/reject fire against the resolved
 * IMPLEMENTATION artifact (R1) with the impl artifact version + the stamp's context-bundle
 * version (R3); takeover needs neither and stays available even when accept/reject are
 * blocked (R1/R8).
 *
 * Post-decision (AC6/AC7): the captured `lastDecision` summary (decision + state + actor +
 * correlation id) persists via the bar's `success` state — which is checked BEFORE
 * `blocked` so the refetched terminal allowed-actions don't tear it down. The takeover
 * `TakeoverResponse.preservedPrReference` is captured to drive the "Continue work in PR
 * {ref}" affordance.
 *
 * Logging (T-LOG-PII): field-only structured console — `impl.acceptSubmit` /
 * `impl.rejectSubmit` / `impl.takeoverSubmit` (the response `currentState` only; reject
 * additionally logs the non-PII `taggedFeedback` enum), `impl.submitError` /
 * `impl.versionMismatch`. NEVER `reasonText` / `preservedPrReference` / ids / PII.
 */
import { useCallback, useEffect, useState } from 'react';

import { isProblemDetailsError } from '@/lib/api/problemDetails';

import {
  buildImplementationContextLabel,
  deriveImplementationExpectedVersions,
  normalizeActions,
  resolveImplementationArtifact,
  type ApprovalBarLayout,
  type ApprovalDecisionView,
  type ApprovalMutationState,
  type ApprovalVersionStamp,
  type DecisionSummary,
  type ImplementationRejectionDraft,
} from '../approvalDecisionView';
import {
  useAcceptImplementation,
  type AcceptImplementationResult,
} from '../hooks/useAcceptImplementation';
import { useAllowedActions } from '../hooks/useAllowedActions';
import {
  useRejectImplementation,
  type RejectImplementationResult,
} from '../hooks/useRejectImplementation';
import { useTakeoverWorkflow, type TakeoverWorkflowResult } from '../hooks/useTakeoverWorkflow';
import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import { ApprovalDecisionBar } from './ApprovalDecisionBar';

export interface ImplementationReviewDecisionBarContainerProps {
  workflowRunId: string;
  /** Defaults to the bottom-of-pane `sticky_footer` layout (mirrors the other bars). */
  layout?: ApprovalBarLayout | undefined;
  /**
   * The three mutations, optionally LIFTED to the route-level `WorkflowDecisionBar` (3.30
   * P3 pattern) so the bar survives the post-decision state flip out of `WaitingForReview`
   * (kept-alive success summary + AC7 PR affordance + AC8 announcement). Default to internal
   * instances for standalone mounts (tests / non-route use).
   */
  accept?: AcceptImplementationResult | undefined;
  reject?: RejectImplementationResult | undefined;
  takeover?: TakeoverWorkflowResult | undefined;
}

export function ImplementationReviewDecisionBarContainer({
  workflowRunId,
  layout = 'sticky_footer',
  accept: acceptProp,
  reject: rejectProp,
  takeover: takeoverProp,
}: ImplementationReviewDecisionBarContainerProps) {
  const allowedActionsQuery = useAllowedActions(workflowRunId);
  const detailQuery = useWorkflowDetail(workflowRunId);
  // Internal instances are always created (hooks rule); they stay idle when a prop is given.
  const internalAccept = useAcceptImplementation(workflowRunId);
  const internalReject = useRejectImplementation(workflowRunId);
  const internalTakeover = useTakeoverWorkflow(workflowRunId);
  const accept = acceptProp ?? internalAccept;
  const reject = rejectProp ?? internalReject;
  const takeover = takeoverProp ?? internalTakeover;

  const [lastDecision, setLastDecision] = useState<DecisionSummary | undefined>(undefined);

  const allowed = allowedActionsQuery.data;
  const detail = detailQuery.data;
  const versionStamp: ApprovalVersionStamp | undefined = allowed?.versionStamp;
  const actions = normalizeActions(allowed?.actions);
  const implArtifact = resolveImplementationArtifact(detail);
  const expectedVersions = deriveImplementationExpectedVersions(implArtifact, versionStamp);
  // Expose the artifactId to the bar ONLY when the full accept/reject request is buildable
  // (impl artifact + both version ints) — so the bar's `canFire` gate never shows a `ready`
  // accept that `handleAccept` would no-op. Takeover stays available regardless (no artifact).
  const artifactId = expectedVersions !== null ? implArtifact?.artifactId : undefined;

  const view: ApprovalDecisionView = {
    workflowRunId,
    mode: 'implementation_review',
    layout,
    actions,
    versionStamp,
    currentState: detail?.currentState ?? versionStamp?.workflowState ?? '',
    decisionContextLabel: buildImplementationContextLabel(detail),
    artifactId,
    // disabledReasons + pendingClarifications are DORMANT here (no live source — R11).
    lastDecision,
  };

  // Collapse the three mutations into the single state the bar renders. Only one fires at
  // a time; the error code drives the stale-vs-error split (takeover never returns a
  // version mismatch — R2).
  const mutationError = accept.error ?? reject.error ?? takeover.error;
  const status: ApprovalMutationState['status'] =
    accept.isPending || reject.isPending || takeover.isPending
      ? 'pending'
      : accept.isError || reject.isError || takeover.isError
        ? 'error'
        : accept.isSuccess || reject.isSuccess || takeover.isSuccess
          ? 'success'
          : 'idle';
  const mutation: ApprovalMutationState = {
    status,
    errorCode: isProblemDetailsError(mutationError) ? mutationError.code : undefined,
  };

  const handleAccept = () => {
    if (artifactId === undefined || expectedVersions === null || accept.isPending) {
      return; // Never fire a request we cannot complete (R1) — accept renders blocked.
    }
    accept.mutate(
      { artifactId, ...expectedVersions },
      {
        onSuccess: (data) => {
          // Field-only (T-LOG-PII): the response state, never reason/artifactId/run id.
          console.info({ event: 'impl.acceptSubmit', currentState: data.currentState });
          setLastDecision({
            decision: 'accepted',
            resultingState: data.currentState,
            decidedAt: new Date().toISOString(),
            actor: detail?.currentActorIdentity,
            correlationId: data.correlationId,
          });
        },
        onError: (error) => logMutationError(error, () => void allowedActionsQuery.refetch()),
      },
    );
  };

  const handleReject = (draft: ImplementationRejectionDraft) => {
    if (artifactId === undefined || expectedVersions === null || reject.isPending) {
      return;
    }
    reject.mutate(
      {
        artifactId,
        ...expectedVersions,
        reasonText: draft.reasonText,
        taggedFeedback: draft.taggedFeedback,
      },
      {
        onSuccess: (data) => {
          // Field-only: the non-PII enum + response state; NEVER `reasonText` (T-LOG-PII).
          console.info({
            event: 'impl.rejectSubmit',
            taggedFeedback: draft.taggedFeedback,
            currentState: data.currentState,
          });
          setLastDecision({
            decision: 'rejected',
            resultingState: data.currentState,
            decidedAt: new Date().toISOString(),
            actor: detail?.currentActorIdentity,
            correlationId: data.correlationId,
          });
        },
        onError: (error) => logMutationError(error, () => void allowedActionsQuery.refetch()),
      },
    );
  };

  const handleTakeover = (reasonText: string) => {
    if (!view.actions.includes('takeover_workflow') || takeover.isPending) {
      return;
    }
    takeover.mutate(
      { reasonText },
      {
        onSuccess: (data) => {
          // Field-only (T-LOG-PII): the response state ONLY — NEVER preservedPrReference /
          // reason / ids. The PR ref is captured into local state for the AC7 affordance,
          // but it is never logged.
          console.info({ event: 'impl.takeoverSubmit', currentState: data.currentState });
          setLastDecision({
            decision: 'takenover',
            resultingState: data.currentState,
            decidedAt: new Date().toISOString(),
            actor: detail?.currentActorIdentity,
            correlationId: data.correlationId,
            preservedPrReference: data.preservedPrReference,
            cancelledInFlightCount: data.cancelledInFlightCount,
            cancelledQueuedCount: data.cancelledQueuedCount,
          });
        },
        onError: (error) => logMutationError(error, () => void allowedActionsQuery.refetch()),
      },
    );
  };

  const handleRefresh = useCallback(() => {
    void allowedActionsQuery.refetch();
    void detailQuery.refetch();
    accept.reset();
    reject.reset();
    takeover.reset();
  }, [allowedActionsQuery, detailQuery, accept, reject, takeover]);

  // Allowed-actions / detail load-error logging (live — either GET can genuinely fail).
  // Field-only (T-LOG-PII): the stable code + transport flag, mirroring the sibling
  // containers — never ids / reason.
  const loadError = allowedActionsQuery.isError || detailQuery.isError;
  useEffect(() => {
    if (!loadError) {
      return;
    }
    const error = allowedActionsQuery.error ?? detailQuery.error;
    console.warn({
      event: 'impl.allowedActionsLoadError',
      code: isProblemDetailsError(error) ? error.code : 'transport',
      transport: !isProblemDetailsError(error),
    });
  }, [loadError, allowedActionsQuery.error, detailQuery.error]);

  return (
    <ApprovalDecisionBar
      view={view}
      mutation={mutation}
      loadError={loadError}
      onApprove={NOOP}
      onReject={NOOP_REJECT}
      onRefresh={handleRefresh}
      onAccept={handleAccept}
      onRejectImplementation={handleReject}
      onTakeover={handleTakeover}
    />
  );
}

/** The spec approve/reject callbacks are unused in implementation_review mode. */
const NOOP = () => {};
const NOOP_REJECT = () => {};

/**
 * Field-only mutation-error logging (T-LOG-PII). `APPROVAL_VERSION_MISMATCH` (accept/reject
 * only — R2) is the stale trigger (AC5): logged at WARN with `{ event, code }` + a refetch;
 * every other failure logs `{ event, code, transport }`. NEVER the raw message / reasonText
 * / ids.
 */
function logMutationError(error: unknown, refetchAllowedActions: () => void) {
  if (isProblemDetailsError(error) && error.code === 'APPROVAL_VERSION_MISMATCH') {
    console.warn({ event: 'impl.versionMismatch', code: error.code });
    refetchAllowedActions();
    return;
  }
  console.warn({
    event: 'impl.submitError',
    code: isProblemDetailsError(error) ? error.code : 'transport',
    transport: !isProblemDetailsError(error),
  });
}
