/**
 * Story 2.19 (Task 5, OQ-1/OQ-3) — the thin data CONTAINER for the Approval Bar.
 *
 * Reads the LIVE `useAllowedActions` (the 2.18-deferred wiring 2.19 flips on) +
 * `useWorkflowDetail`, maps both into an {@link ApprovalDecisionView}, derives the two
 * expected-version ints (AC6), and wires `onApprove`/`onReject` to the live
 * `useApproveSpec`/`useRejectSpec` mutations. On `APPROVAL_VERSION_MISMATCH` it surfaces
 * the `stale` state (the bar's refresh CTA) and refetches allowed-actions. Keeping it
 * separate from the presentational bar lets the bar be tested router/query-free with
 * fixtures (the 2.17/2.18 discipline).
 *
 * THE DORMANCY BOUNDARY (T-ARTIFACTID): the approve/reject bodies REQUIRE `artifactId`,
 * which NO live read model exposes (`WorkflowDetail.latestArtifacts` = type/status/
 * version only; `useArtifact` is a dormant stub). {@link resolveSpecArtifactId} is the
 * SINGLE seam where a live id source plugs in — until then it returns undefined and the
 * bar renders `blocked` ("specification not yet available for a decision"); the bar NEVER
 * fires a primary it cannot complete. Never fabricate an id from `latestArtifacts`.
 *
 * AC11 / OQ-5 — the bar/container consume ONLY `useAllowedActions` output for eligibility
 * (no permission-inference module exists to import); see `ApprovalDecisionBar.eligibility.test.ts`
 * for the focused import-graph guard that pins this convention.
 */
import { useCallback, useEffect, useRef, useState } from 'react';

import { isProblemDetailsError } from '@/lib/api/problemDetails';

import {
  buildDecisionContextLabel,
  deriveExpectedVersions,
  normalizeActions,
  resolveSpecArtifactId,
  type ApprovalBarLayout,
  type ApprovalBarMode,
  type ApprovalDecisionView,
  type ApprovalLocalUi,
  type ApprovalMutationState,
  type ApprovalVersionStamp,
  type DecisionSummary,
  type RejectionDraft,
} from '../approvalDecisionView';
import { useAllowedActions } from '../hooks/useAllowedActions';
import { useApproveSpec } from '../hooks/useApproveSpec';
import { useRejectSpec } from '../hooks/useRejectSpec';
import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import { ApprovalDecisionBar } from './ApprovalDecisionBar';

export interface ApprovalDecisionBarContainerProps {
  workflowRunId: string;
  /** Defaults to the fully-implemented `spec_approval` mode (E2). */
  mode?: ApprovalBarMode | undefined;
  /** Defaults to the bottom-of-pane `sticky_footer` layout (AC4). */
  layout?: ApprovalBarLayout | undefined;
}

export function ApprovalDecisionBarContainer({
  workflowRunId,
  mode = 'spec_approval',
  layout = 'sticky_footer',
}: ApprovalDecisionBarContainerProps) {
  const allowedActionsQuery = useAllowedActions(workflowRunId);
  const detailQuery = useWorkflowDetail(workflowRunId);
  const approve = useApproveSpec(workflowRunId);
  const reject = useRejectSpec(workflowRunId);

  const [lastDecision, setLastDecision] = useState<DecisionSummary | undefined>(undefined);

  const allowed = allowedActionsQuery.data;
  const detail = detailQuery.data;

  const versionStamp: ApprovalVersionStamp | undefined = allowed?.versionStamp;
  const actions = normalizeActions(allowed?.actions);
  const artifactId = resolveSpecArtifactId(detail);
  const expectedVersions = deriveExpectedVersions(versionStamp);

  const view: ApprovalDecisionView = {
    workflowRunId,
    mode,
    layout,
    actions,
    versionStamp,
    currentState: detail?.currentState ?? versionStamp?.workflowState ?? '',
    decisionContextLabel: buildDecisionContextLabel(detail, versionStamp),
    artifactId,
    // pendingClarifications + disabledReasons are DORMANT (no live source — AC5c/AC13);
    // left undefined so the bar renders the generic blocked explanation live.
    lastDecision,
  };

  // Collapse the two mutations into the single state the bar renders. Only one fires at
  // a time (the bar offers one decision); error code drives the stale-vs-error split.
  const mutationError = approve.error ?? reject.error;
  const status: ApprovalMutationState['status'] =
    approve.isPending || reject.isPending
      ? 'pending'
      : approve.isError || reject.isError
        ? 'error'
        : approve.isSuccess || reject.isSuccess
          ? 'success'
          : 'idle';
  const mutation: ApprovalMutationState = {
    status,
    errorCode: isProblemDetailsError(mutationError) ? mutationError.code : undefined,
  };

  // AC9 — once a decision lands, the summary persists READ-ONLY (`locked`) and never
  // auto-clears (T-SUMMARY-PERSIST). The component's `success` branch is reachable in
  // fixture tests; live the bar settles into `locked` so a post-refetch empty action
  // set never replaces the recorded outcome with `blocked`.
  const localUi: ApprovalLocalUi = {
    locked: lastDecision !== undefined && status !== 'pending',
  };

  const handleApprove = () => {
    if (artifactId === undefined || expectedVersions === null || approve.isPending) {
      return; // Dormant firing path — never fire a request we cannot complete (T-ARTIFACTID).
    }
    approve.mutate(
      { artifactId, ...expectedVersions },
      {
        onSuccess: (data) => {
          // Field-only (T-LOG-PII): the response state, never reason/artifactId/run id.
          console.info({ event: 'approval.approveSubmit', currentState: data.currentState });
          setLastDecision({
            decision: 'approved',
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

  const handleReject = (draft: RejectionDraft) => {
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
            event: 'approval.rejectSubmit',
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

  const handleRefresh = useCallback(() => {
    void allowedActionsQuery.refetch();
    void detailQuery.refetch();
    approve.reset();
    reject.reset();
  }, [allowedActionsQuery, detailQuery, approve, reject]);

  // AC9 (decision-2 resolution) — reconcile the persisted summary against the live
  // read model: clear it once the workflow advances PAST the state the decision produced,
  // so `locked` does not outlive "the next workflow state change". The ref guards the
  // read-model lag (the detail refetch trails the mutation): only clear AFTER we have
  // observed the resulting state, never on the pre-refetch stale value.
  const observedDecisionStateRef = useRef<string | undefined>(undefined);
  useEffect(() => {
    const currentState = detail?.currentState;
    if (lastDecision === undefined || currentState === undefined) {
      observedDecisionStateRef.current = currentState;
      return;
    }
    if (
      currentState !== lastDecision.resultingState &&
      observedDecisionStateRef.current === lastDecision.resultingState
    ) {
      setLastDecision(undefined);
    }
    observedDecisionStateRef.current = currentState;
  }, [detail?.currentState, lastDecision]);

  // Allowed-actions load-error logging (live — the GET can now genuinely fail).
  useEffect(() => {
    if (!allowedActionsQuery.isError) {
      return;
    }
    const error = allowedActionsQuery.error;
    console.warn({
      event: 'approval.allowedActionsLoadError',
      code: isProblemDetailsError(error) ? error.code : 'transport',
      transport: !isProblemDetailsError(error),
    });
  }, [allowedActionsQuery.isError, allowedActionsQuery.error]);

  return (
    <ApprovalDecisionBar
      view={view}
      mutation={mutation}
      localUi={localUi}
      loadError={allowedActionsQuery.isError}
      onApprove={handleApprove}
      onReject={handleReject}
      onRefresh={handleRefresh}
    />
  );
}

/**
 * Field-only mutation-error logging (T-LOG-PII). A version mismatch is the stale
 * trigger (AC6) — logged at WARN with just `{ event, code }` and a refetch; every other
 * failure logs `{ event, code, transport }`. NEVER the raw message / reasonText / ids.
 */
function logMutationError(error: unknown, refetchAllowedActions: () => void) {
  if (isProblemDetailsError(error) && error.code === 'APPROVAL_VERSION_MISMATCH') {
    console.warn({ event: 'approval.versionMismatch', code: error.code });
    refetchAllowedActions();
    return;
  }
  console.warn({
    event: 'approval.submitError',
    code: isProblemDetailsError(error) ? error.code : 'transport',
    transport: !isProblemDetailsError(error),
  });
}
