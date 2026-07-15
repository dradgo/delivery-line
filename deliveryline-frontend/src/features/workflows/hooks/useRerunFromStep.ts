/**
 * Story 4.22 (Task 3, AC5/AC8) — the LIVE `useRerunFromStep` mutation (NEW).
 *
 * Drives the Decision Bar `recovery_operator` "Rerun from step" action against the story-4.12
 * endpoint `POST /api/v1/workflows/{workflowRunId}/rerun-from-step` (operationId `rerunFromStep`).
 * Built on `useWorkflowMutation` (UUIDv7 `Idempotency-Key` + `detail(id)`/`lists()` invalidation).
 *
 * ROLE/ACTOR DIVERGENCE (Dev Notes): header-derived actor + a `role: "workflow_owner"` BODY field
 * (NOT retry's `actorIdentity`/`actorType`). Body is `{ role, targetStep, reasonText }`. Both
 * `targetStep` (a safe-step enum) and `reasonText` (REQUIRED for rerun — the service raises
 * `MISSING_REASON_TEXT` if blank) are caller-supplied; `reasonText` is reviewer-authored → NEVER
 * logged (T-LOG-PII).
 *
 * Typed failures via `ProblemDetailsError`: `INVALID_RERUN_TARGET_STEP` (400), `MISSING_REASON_TEXT`
 * (400), `ILLEGAL_TRANSITION` (409, wrong source state), `RUN_NOT_FOUND` (404), idempotency 409s.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { RECOVERY_OPERATOR_ROLE } from '../approvalDecisionView';
import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type RerunFromStepRequest = components['schemas']['RerunFromStepRequest'];
type RerunFromStepResponse = components['schemas']['RerunFromStepResponse'];

/** The safe step boundary a rerun may target (the `RerunFromStepRequest.targetStep` enum). */
export type RerunTargetStep = RerunFromStepRequest['targetStep'];

/** The variables a caller passes to rerun a run from a safe step. `role` is constant. */
export interface RerunFromStepVariables {
  /** The safe step to rerun from (`investigating` / `executing`). */
  targetStep: RerunTargetStep;
  /** REQUIRED operator rationale — reviewer-authored, NEVER logged (T-LOG-PII). */
  reasonText: string;
}

export type RerunFromStepResult = WorkflowMutationResult<
  RerunFromStepResponse,
  RerunFromStepVariables
>;

/** Build the live rerun-from-step mutation for a run. */
export function useRerunFromStep(workflowRunId: string): RerunFromStepResult {
  return useWorkflowMutation<RerunFromStepVariables, RerunFromStepResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: RerunFromStepRequest = {
        role: RECOVERY_OPERATOR_ROLE,
        targetStep: variables.targetStep,
        reasonText: variables.reasonText,
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/rerun-from-step', {
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
