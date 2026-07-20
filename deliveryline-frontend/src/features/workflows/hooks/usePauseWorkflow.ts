/**
 * Story 4.22 (Task 3, AC6/AC8) — the LIVE `usePauseWorkflow` mutation (NEW).
 *
 * Drives the Decision Bar `recovery_operator` "Pause run" action against the story-4.13 endpoint
 * `POST /api/v1/workflows/{workflowRunId}/pause` (operationId `pauseWorkflow`). Built on
 * `useWorkflowMutation` (UUIDv7 `Idempotency-Key` + `detail(id)`/`lists()` invalidation).
 *
 * ROLE/ACTOR DIVERGENCE (Dev Notes): header-derived actor + a `role: "workflow_owner"` BODY field
 * (NOT retry's `actorIdentity`/`actorType`). Body is `{ role, reasonText }`. `reasonText` is
 * REQUIRED for pause (the service raises `MISSING_REASON_TEXT` if blank) and reviewer-authored →
 * NEVER logged (T-LOG-PII).
 *
 * Typed failures via `ProblemDetailsError`: `PAUSE_NOT_APPLICABLE` (409, wrong/terminal source),
 * `MISSING_REASON_TEXT` (400), `RUN_NOT_FOUND` (404), and the idempotency-conflict 409s.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { RECOVERY_OPERATOR_ROLE } from '../approvalDecisionView';
import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type PauseWorkflowRequest = components['schemas']['PauseWorkflowRequest'];
type PauseResponse = components['schemas']['PauseResponse'];

/** The variables a caller passes to pause a run. `role` is constant (workflow_owner). */
export interface PauseWorkflowVariables {
  /** REQUIRED operator note explaining the pause — reviewer-authored, NEVER logged (T-LOG-PII). */
  reasonText: string;
}

export type PauseWorkflowResult = WorkflowMutationResult<PauseResponse, PauseWorkflowVariables>;

/** Build the live pause-workflow mutation for a run. */
export function usePauseWorkflow(workflowRunId: string): PauseWorkflowResult {
  return useWorkflowMutation<PauseWorkflowVariables, PauseResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: PauseWorkflowRequest = {
        role: RECOVERY_OPERATOR_ROLE,
        reasonText: variables.reasonText,
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/pause', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
          body,
        }),
      );
    },
  });
}
