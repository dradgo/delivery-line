/**
 * Story 3.30 + 4.22 — the thin data CONTAINER for the Decision Bar's `recovery_operator` mode.
 *
 * A sibling of `ApprovalDecisionBarContainer` (OQ-3): the spec-approval container is heavily
 * spec-specific, so the recovery path lives in its own small container. Both feed the SAME
 * presentational `ApprovalDecisionBar`, which renders the real `recovery_operator` branch.
 *
 * Story 4.22 FULLY ACTIVATES the mode: beyond the 3.30 retry baseline it wires resume / rerun-from-
 * step (+ the non-mutating preview) / pause mutations, joins the safety ranking from
 * `useFailureDiagnostics` (AC2), and provides the reconcile / classify entry-point SEAMS. Per the
 * epic's explicit delegation (OQ-2 seam-only default), reconcile + classify handlers are NOT wired
 * here — the bar renders those gated buttons disabled + explained until stories 4.23 / 4.24 supply
 * their dialogs (they will pass `onReconcile` / `onClassifyFailure` when they land).
 *
 * Eligibility (AC1 / AC11): every action is gated on the live `useAllowedActions` (workflow_owner) —
 * the frontend NEVER infers eligibility locally.
 *
 * Logging (T-LOG-PII): field-only structured console — `recovery.<action>Submit` (response state
 * only) / `recovery.<action>Error` (stable code + transport flag) / `recovery.previewLoadError` /
 * `recovery.allowedActionsLoadError`. NEVER `reasonText` / run / actor ids / PII.
 */
import { useCallback, useEffect, useState } from 'react';

import { isProblemDetailsError } from '@/lib/api/problemDetails';

import {
  RECOVERY_OPERATOR_ROLE,
  buildRecoveryContextLabel,
  buildRecoverySafetyByToken,
  canRetry,
  normalizeActions,
  type ApprovalBarLayout,
  type ApprovalDecisionView,
  type ApprovalMutationState,
  type ApprovalVersionStamp,
  type DecisionSummary,
} from '../approvalDecisionView';
import { useAllowedActions } from '../hooks/useAllowedActions';
import { useFailureDiagnostics } from '../hooks/useFailureDiagnostics';
import { usePauseWorkflow, type PauseWorkflowResult } from '../hooks/usePauseWorkflow';
import { usePreviewRerunFromStep } from '../hooks/usePreviewRerunFromStep';
import { useReconcileWorkflow, type ReconcileWorkflowResult } from '../hooks/useReconcileWorkflow';
import {
  useRerunFromStep,
  type RerunFromStepResult,
  type RerunTargetStep,
} from '../hooks/useRerunFromStep';
import { useResumeWorkflow, type ResumeWorkflowResult } from '../hooks/useResumeWorkflow';
import { useRetryWorkflow, type RetryWorkflowResult } from '../hooks/useRetryWorkflow';
import { useRunIntegrationConflicts, resolveConflictId } from '../hooks/useRunIntegrationConflicts';
import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import { ApprovalDecisionBar } from './ApprovalDecisionBar';
import { ReconciliationDialog } from './ReconciliationDialog';

export interface RecoveryDecisionBarContainerProps {
  workflowRunId: string;
  /** Defaults to the bottom-of-pane `sticky_footer` layout (mirrors the approval bar). */
  layout?: ApprovalBarLayout | undefined;
  /**
   * The recovery mutations, optionally LIFTED to the route-level `WorkflowDecisionBar` so the bar
   * survives the post-action state flip (kept-alive summary + AC7 announcement). Each defaults to an
   * internal instance for standalone mounts (tests / non-route use) — mirroring the 3.30 `retry`
   * prop.
   */
  retry?: RetryWorkflowResult | undefined;
  resume?: ResumeWorkflowResult | undefined;
  rerun?: RerunFromStepResult | undefined;
  pause?: PauseWorkflowResult | undefined;
  /**
   * Story 4.23 — the reconcile mutation, optionally LIFTED to `WorkflowDecisionBar` so a post-
   * reconcile state flip (Paused→…) does not unmount this bar and tear down the dialog's success
   * announcement before it is spoken. Defaults to an internal instance for standalone/test mounts.
   */
  reconcile?: ReconcileWorkflowResult | undefined;
}

/** The approve/reject callbacks are unused in recovery mode — a single shared no-op. */
const NOOP = () => {};

export function RecoveryDecisionBarContainer({
  workflowRunId,
  layout = 'sticky_footer',
  retry: retryProp,
  resume: resumeProp,
  rerun: rerunProp,
  pause: pauseProp,
  reconcile: reconcileProp,
}: RecoveryDecisionBarContainerProps) {
  // Request the workflow_owner action set — the backend matrix gates the recovery actions on this
  // role (a default product_reviewer request returns only view_only/view_diagnostics → "View only").
  const allowedActionsQuery = useAllowedActions(workflowRunId, RECOVERY_OPERATOR_ROLE);
  const detailQuery = useWorkflowDetail(workflowRunId);
  // The safety ranking (AC2) — sourced from the diagnostics recommendations, NOT allowed-actions.
  const diagnosticsQuery = useFailureDiagnostics(workflowRunId);

  // Use the lifted instance when the route owns it; otherwise an internal one. The internal hooks
  // are always called (hooks rule) and stay idle when a prop is supplied.
  const internalRetry = useRetryWorkflow(workflowRunId);
  const internalResume = useResumeWorkflow(workflowRunId);
  const internalRerun = useRerunFromStep(workflowRunId);
  const internalPause = usePauseWorkflow(workflowRunId);
  const internalReconcile = useReconcileWorkflow(workflowRunId);
  const retry = retryProp ?? internalRetry;
  const resume = resumeProp ?? internalResume;
  const rerun = rerunProp ?? internalRerun;
  const pause = pauseProp ?? internalPause;
  const reconcile = reconcileProp ?? internalReconcile;

  // The rerun preview is bar-driven: the bar reports the dialog's open state + selected step so we
  // enable/param the non-mutating preview query (AC5).
  const [rerunPreviewReq, setRerunPreviewReq] = useState<{
    open: boolean;
    targetStep: RerunTargetStep;
  }>({ open: false, targetStep: 'investigating' });
  const rerunPreviewQuery = usePreviewRerunFromStep(workflowRunId, rerunPreviewReq.targetStep, {
    enabled: rerunPreviewReq.open,
  });

  // The persisted post-submit summary (AC9) — set when an OWNED recovery action lands so it survives
  // the subsequent currentState flip until a refresh resets it. Retry keeps its own bespoke chip.
  const [lastDecision, setLastDecision] = useState<DecisionSummary | undefined>(undefined);

  const allowed = allowedActionsQuery.data;
  const detail = detailQuery.data;
  const versionStamp: ApprovalVersionStamp | undefined = allowed?.versionStamp;
  const actions = normalizeActions(allowed?.actions);
  const recoverySafetyByToken = buildRecoverySafetyByToken(actions, diagnosticsQuery.data);

  // Story 4.23 — the reconcile seam carries no conflictId (`onReconcile?.()`), so resolve one from the
  // run's unresolved-conflict list. Only fetched when the backend actually offers `reconcile_conflict`.
  const canReconcile = actions.includes('reconcile_conflict');
  const conflictsQuery = useRunIntegrationConflicts(workflowRunId, { enabled: canReconcile });
  // Resolve the concrete conflictId the dialog needs BEFORE offering the button. The conflicts query
  // only starts once `reconcile_conflict` is offered, so there is a real window where the action is
  // allowed but no conflict is resolvable yet (still loading, or the list came back empty because
  // another operator just resolved it). Gate `onReconcile` on this so the bar renders the button
  // disabled-and-explained instead of enabled-but-dead (a click that silently no-ops).
  const resolvableConflictId = canReconcile
    ? resolveConflictId(conflictsQuery.data?.conflicts)
    : undefined;
  const [reconcileState, setReconcileState] = useState<{ open: boolean; conflictId: string }>({
    open: false,
    conflictId: '',
  });
  const openReconcileDialog = useCallback(() => {
    if (resolvableConflictId === undefined) {
      // Guarded by the gated `onReconcile` below; defensive only.
      console.warn({ event: 'recovery.reconcileNoConflict' });
      return;
    }
    setReconcileState({ open: true, conflictId: resolvableConflictId });
  }, [resolvableConflictId]);
  const closeReconcileDialog = useCallback(() => {
    setReconcileState((prev) => ({ ...prev, open: false }));
  }, []);

  const view: ApprovalDecisionView = {
    workflowRunId,
    mode: 'recovery_operator',
    layout,
    actions,
    versionStamp,
    currentState: detail?.currentState ?? versionStamp?.workflowState ?? '',
    decisionContextLabel: buildRecoveryContextLabel(detail),
    lastDecision,
  };

  // Aggregate the mutation status across all owned recovery mutations (whichever is in flight /
  // errored / landed most recently). Actions are sequential, so at most one is non-idle.
  const settleables = [retry, resume, rerun, pause];
  const erroredMutation = settleables.find((mutationResult) => mutationResult.isError);
  const status: ApprovalMutationState['status'] = settleables.some(
    (mutationResult) => mutationResult.isPending,
  )
    ? 'pending'
    : erroredMutation !== undefined
      ? 'error'
      : settleables.some((mutationResult) => mutationResult.isSuccess)
        ? 'success'
        : 'idle';
  const mutation: ApprovalMutationState = {
    status,
    errorCode:
      erroredMutation !== undefined && isProblemDetailsError(erroredMutation.error)
        ? erroredMutation.error.code
        : undefined,
  };

  const handleRetry = () => {
    if (!canRetry(view) || retry.isPending) {
      return; // Never fire a retry the run is not eligible for (AC5 — read allowed-actions).
    }
    retry.mutate(
      {},
      {
        onSuccess: (data) => {
          // Field-only (T-LOG-PII): the response state, never reason / ids / actor.
          console.info({ event: 'recovery.retrySubmit', currentState: data.currentState });
        },
        onError: (error) => {
          console.warn({
            event: 'recovery.retryError',
            code: isProblemDetailsError(error) ? error.code : 'transport',
            transport: !isProblemDetailsError(error),
          });
        },
      },
    );
  };

  const handleResume = () => {
    if (resume.isPending) {
      return;
    }
    resume.mutate(
      {},
      {
        onSuccess: (data) => {
          console.info({ event: 'recovery.resumeSubmit', currentState: data.currentState });
          setLastDecision({
            decision: 'resumed',
            resultingState: data.currentState ?? '',
            correlationId: data.correlationId,
            decidedAt: new Date().toISOString(),
          });
        },
        onError: (error) => {
          console.warn({
            event: 'recovery.resumeError',
            code: isProblemDetailsError(error) ? error.code : 'transport',
            transport: !isProblemDetailsError(error),
          });
        },
      },
    );
  };

  const handleRerun = (vars: { targetStep: RerunTargetStep; reasonText: string }) => {
    if (rerun.isPending) {
      return;
    }
    rerun.mutate(vars, {
      onSuccess: (data) => {
        console.info({ event: 'recovery.rerunSubmit', currentState: data.currentState });
        setLastDecision({
          decision: 'reranFromStep',
          resultingState: data.currentState,
          correlationId: data.correlationId,
          decidedAt: new Date().toISOString(),
        });
      },
      onError: (error) => {
        console.warn({
          event: 'recovery.rerunError',
          code: isProblemDetailsError(error) ? error.code : 'transport',
          transport: !isProblemDetailsError(error),
        });
      },
    });
  };

  const handlePause = (reasonText: string) => {
    if (pause.isPending) {
      return;
    }
    pause.mutate(
      { reasonText },
      {
        onSuccess: (data) => {
          console.info({ event: 'recovery.pauseSubmit', currentState: data.currentState });
          setLastDecision({
            decision: 'paused',
            // PauseResponse.currentState is REQUIRED (always Paused) — no null-guard.
            resultingState: data.currentState,
            correlationId: data.correlationId,
            decidedAt: new Date().toISOString(),
          });
        },
        onError: (error) => {
          console.warn({
            event: 'recovery.pauseError',
            code: isProblemDetailsError(error) ? error.code : 'transport',
            transport: !isProblemDetailsError(error),
          });
        },
      },
    );
  };

  const handleRefresh = useCallback(() => {
    void allowedActionsQuery.refetch();
    void detailQuery.refetch();
    void diagnosticsQuery.refetch();
    if (canReconcile) {
      void conflictsQuery.refetch();
    }
    retry.reset();
    resume.reset();
    rerun.reset();
    pause.reset();
    reconcile.reset();
    setLastDecision(undefined);
  }, [
    allowedActionsQuery,
    detailQuery,
    diagnosticsQuery,
    conflictsQuery,
    canReconcile,
    retry,
    resume,
    rerun,
    pause,
    reconcile,
  ]);

  // Allowed-actions / detail load-error logging (live — either GET can genuinely fail). Field-only
  // (T-LOG-PII): the stable code + transport flag, never ids / reason.
  const loadError = allowedActionsQuery.isError || detailQuery.isError;
  useEffect(() => {
    if (!loadError) {
      return;
    }
    const error = allowedActionsQuery.error ?? detailQuery.error;
    console.warn({
      event: 'recovery.allowedActionsLoadError',
      code: isProblemDetailsError(error) ? error.code : 'transport',
      transport: !isProblemDetailsError(error),
    });
  }, [loadError, allowedActionsQuery.error, detailQuery.error]);

  // Rerun-preview load-error logging (AC5) — a transient preview read failure must be
  // distinguishable from "nothing would be superseded".
  useEffect(() => {
    if (!rerunPreviewQuery.isError) {
      return;
    }
    console.warn({
      event: 'recovery.previewLoadError',
      code: isProblemDetailsError(rerunPreviewQuery.error)
        ? rerunPreviewQuery.error.code
        : 'transport',
      transport: !isProblemDetailsError(rerunPreviewQuery.error),
    });
  }, [rerunPreviewQuery.isError, rerunPreviewQuery.error]);

  // Failure-diagnostics load-error logging (project-wide Logging Requirements) — diagnostics failure
  // does NOT force the bar into its error state (safety ranking falls back to the static table), so
  // a warn is the only signal distinguishing a transient read failure from "no recommendation".
  useEffect(() => {
    if (!diagnosticsQuery.isError) {
      return;
    }
    console.warn({
      event: 'recovery.diagnosticsLoadError',
      code: isProblemDetailsError(diagnosticsQuery.error)
        ? diagnosticsQuery.error.code
        : 'transport',
      transport: !isProblemDetailsError(diagnosticsQuery.error),
    });
  }, [diagnosticsQuery.isError, diagnosticsQuery.error]);

  return (
    <>
      <ApprovalDecisionBar
        view={view}
        mutation={mutation}
        loadError={loadError}
        onApprove={NOOP}
        onReject={NOOP}
        onRefresh={handleRefresh}
        onRetry={handleRetry}
        onResume={handleResume}
        onRerunFromStep={handleRerun}
        onPause={handlePause}
        recoverySafetyByToken={recoverySafetyByToken}
        rerunPreview={{
          data: rerunPreviewQuery.data,
          isLoading: rerunPreviewQuery.isLoading,
          isError: rerunPreviewQuery.isError,
        }}
        onRerunPreviewRequest={setRerunPreviewReq}
        // Story 4.23 — the reconcile seam is now wired (the dialog resolves + reconciles a conflict).
        // Only offered once a concrete conflict is resolvable; while it is still resolving (or none is
        // available) the bar renders the button disabled + explained rather than enabled-but-dead.
        // onClassifyFailure stays a seam until story 4.24 (bar renders it disabled + explained).
        onReconcile={resolvableConflictId !== undefined ? openReconcileDialog : undefined}
        reconcileResolving={canReconcile && resolvableConflictId === undefined}
      />
      <ReconciliationDialog
        workflowRunId={workflowRunId}
        conflictId={reconcileState.conflictId}
        open={reconcileState.open}
        onClose={closeReconcileDialog}
        reconcile={reconcile}
      />
    </>
  );
}
