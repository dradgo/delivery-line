/**
 * Story 4.23 (Task 2, AC5/AC6/AC10) — the LIVE `useReconcileWorkflow` mutation (NEW).
 *
 * Drives the reconciliation dialog's "Confirm reconcile" against the story-4.11 endpoint
 * `POST /api/v1/workflows/{workflowRunId}/reconcile` (operationId `reconcile`). Built on the
 * `useWorkflowMutation` factory so it inherits the UUIDv7 `Idempotency-Key` (minted once per attempt,
 * reused on retry) + the `detail(runId)` / `lists()` success invalidation.
 *
 * ROLE/ACTOR (Dev Notes trap #6): mirrors `useResumeWorkflow` / `usePauseWorkflow` — a
 * `role: "workflow_owner"` (`RECOVERY_OPERATOR_ROLE`) BODY field the controller validates, actor via
 * the `X-Actor-Identity` header (defaulted server-side). NO version stamps (trap #5): reconcile is
 * guarded by `conflictId` + `CONFLICT_ALREADY_RESOLVED`, not optimistic versions. `reasonText` is
 * reviewer-authored → NEVER logged (T-LOG-PII).
 *
 * EXTRA INVALIDATION (trap #7): the factory invalidates `detail(runId)` + `lists()` only — NOT the
 * `conflictId`-keyed detail (rooted at `all`) nor `failureDiagnostics(runId)` (already a `detail`
 * child, invalidated for free but pinned here for clarity). So on success this ALSO invalidates
 * `integrationConflict(conflictId)` so the resolved conflict + drift indicators refresh.
 *
 * Typed failures via `ProblemDetailsError`: `CONFLICT_ALREADY_RESOLVED` / `RECONCILE_NOT_APPLICABLE`
 * / `IDEMPOTENCY_KEY_CONFLICT` (409), `CONFLICT_NOT_FOUND` / `RUN_NOT_FOUND` (404),
 * `MISSING_`/`INVALID_RECONCILIATION_DECISION` / `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400). There is
 * NO `ACTION_NOT_ALLOWED` for reconcile.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

import { RECOVERY_OPERATOR_ROLE } from '../approvalDecisionView';
import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type ReconcileWorkflowRequest = components['schemas']['ReconcileWorkflowRequest'];
export type ReconcileResponse = components['schemas']['ReconcileResponse'];
/** The four wire reconciliation decisions (safe-first ordered on the read side). */
export type ReconciliationDecision = ReconcileWorkflowRequest['resolutionDecision'];

/** The variables a caller passes to reconcile a conflict. `role` is constant (workflow_owner). */
export interface ReconcileWorkflowVariables {
  /** Public id of the unresolved conflict to reconcile. */
  conflictId: string;
  /** How the internal/external divergence is resolved (one of the four wire decisions). */
  resolutionDecision: ReconciliationDecision;
  /** REQUIRED operator note (NFR19) — reviewer-authored, NEVER logged (T-LOG-PII). */
  reasonText: string;
}

export type ReconcileWorkflowResult = WorkflowMutationResult<
  ReconcileResponse,
  ReconcileWorkflowVariables
>;

/** Build the live reconcile-workflow mutation for a run. */
export function useReconcileWorkflow(workflowRunId: string): ReconcileWorkflowResult {
  return useWorkflowMutation<ReconcileWorkflowVariables, ReconcileResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: ReconcileWorkflowRequest = {
        conflictId: variables.conflictId,
        resolutionDecision: variables.resolutionDecision,
        reasonText: variables.reasonText,
        role: RECOVERY_OPERATOR_ROLE,
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/reconcile', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
          body,
        }),
      );
    },
    onSuccess: ({ queryClient, variables }) => {
      // The reconciled conflict's own detail is keyed by conflictId OFF `all` — outside the
      // factory's detail(runId) cascade, so invalidate it explicitly (trap #7). The run's
      // failureDiagnostics + unresolved-conflicts list are detail(runId) children already refreshed.
      void queryClient.invalidateQueries({
        queryKey: workflowKeys.integrationConflict(variables.conflictId),
      });
    },
  });
}
