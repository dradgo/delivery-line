/**
 * Story 3.30 (Task 2, AC3/AC4/AC5/OQ-3) — the thin data CONTAINER for the Decision
 * Bar's `recovery_operator` mode.
 *
 * A sibling of `ApprovalDecisionBarContainer` (OQ-3): the spec-approval container is
 * heavily spec-specific (approve/reject mutations, `artifactId` seam, version stamp,
 * persisted decision summary), so the retry path lives in its own small container
 * rather than overloading it. Both feed the SAME presentational `ApprovalDecisionBar`,
 * which renders the real `recovery_operator` branch.
 *
 * Eligibility (AC5 / AC11): the bar's primary `Retry failed step` is gated on
 * {@link canRetry} — `currentState === 'Failed'` AND the live `useAllowedActions`
 * include `retry`. The frontend NEVER infers retry-eligibility locally (no permission-
 * inference module); it reads the backend-reported allowed actions, exactly like the
 * spec-approval bar.
 *
 * Logging (T-LOG-PII): field-only structured console, mirroring
 * `ApprovalDecisionBarContainer` — `recovery.retrySubmit` (the response state on
 * success) and `recovery.retryError` (the stable code + transport flag). NEVER
 * `reasonText` / run / actor ids / PII.
 */
import { useCallback, useEffect } from 'react';

import { isProblemDetailsError } from '@/lib/api/problemDetails';

import {
  buildRecoveryContextLabel,
  canRetry,
  normalizeActions,
  type ApprovalBarLayout,
  type ApprovalDecisionView,
  type ApprovalMutationState,
  type ApprovalVersionStamp,
} from '../approvalDecisionView';
import { useAllowedActions } from '../hooks/useAllowedActions';
import { useRetryWorkflow, type RetryWorkflowResult } from '../hooks/useRetryWorkflow';
import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import { ApprovalDecisionBar } from './ApprovalDecisionBar';

export interface RecoveryDecisionBarContainerProps {
  workflowRunId: string;
  /** Defaults to the bottom-of-pane `sticky_footer` layout (mirrors the approval bar). */
  layout?: ApprovalBarLayout | undefined;
  /**
   * Story 3.30 (P3) — the retry mutation, optionally LIFTED to the route-level
   * `WorkflowDecisionBar` so the recovery bar survives the post-retry Failed→Executing
   * flip (kept-alive success panel + AC7 announcement). Defaults to an internal instance
   * for standalone mounts (tests / non-route use).
   */
  retry?: RetryWorkflowResult | undefined;
}

/** The approve/reject callbacks are unused in recovery mode — a single shared no-op. */
const NOOP = () => {};

export function RecoveryDecisionBarContainer({
  workflowRunId,
  layout = 'sticky_footer',
  retry: retryProp,
}: RecoveryDecisionBarContainerProps) {
  const allowedActionsQuery = useAllowedActions(workflowRunId);
  const detailQuery = useWorkflowDetail(workflowRunId);
  // Use the lifted instance when the route owns it (P3); otherwise an internal one. The
  // internal hook is always called (hooks rule) and stays idle when a prop is supplied.
  const internalRetry = useRetryWorkflow(workflowRunId);
  const retry = retryProp ?? internalRetry;

  const allowed = allowedActionsQuery.data;
  const detail = detailQuery.data;
  const versionStamp: ApprovalVersionStamp | undefined = allowed?.versionStamp;
  const actions = normalizeActions(allowed?.actions);

  const view: ApprovalDecisionView = {
    workflowRunId,
    mode: 'recovery_operator',
    layout,
    actions,
    versionStamp,
    currentState: detail?.currentState ?? versionStamp?.workflowState ?? '',
    decisionContextLabel: buildRecoveryContextLabel(detail),
  };

  const status: ApprovalMutationState['status'] = retry.isPending
    ? 'pending'
    : retry.isError
      ? 'error'
      : retry.isSuccess
        ? 'success'
        : 'idle';
  const mutation: ApprovalMutationState = {
    status,
    errorCode: isProblemDetailsError(retry.error) ? retry.error.code : undefined,
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

  const handleRefresh = useCallback(() => {
    void allowedActionsQuery.refetch();
    void detailQuery.refetch();
    retry.reset();
  }, [allowedActionsQuery, detailQuery, retry]);

  // Allowed-actions / detail load-error logging (live — either GET can genuinely fail).
  // Without this the recovery bar would silently render "View only" on a transient read
  // failure, indistinguishable from a non-retryable run. Field-only (T-LOG-PII): the
  // stable code + transport flag, never ids / reason — mirrors the spec-approval container.
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

  return (
    <ApprovalDecisionBar
      view={view}
      mutation={mutation}
      loadError={loadError}
      onApprove={NOOP}
      onReject={NOOP}
      onRefresh={handleRefresh}
      onRetry={handleRetry}
    />
  );
}
