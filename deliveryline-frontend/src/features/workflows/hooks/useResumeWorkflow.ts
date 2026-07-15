/**
 * Story 4.22 (Task 3, AC3/AC8) — the LIVE `useResumeWorkflow` mutation (NEW).
 *
 * Drives the Decision Bar `recovery_operator` "Resume run" action against the story-4.10 endpoint
 * `POST /api/v1/workflows/{workflowRunId}/resume` (operationId `resumeWorkflow`). Built on the
 * `useWorkflowMutation` factory so it inherits the two cross-cutting invariants for free:
 *   • (story 1.9) a UUIDv7 `Idempotency-Key` minted ONCE per attempt + reused across retries;
 *   • on success, `detail(runId)` invalidation — a PREFIX of `events` / `allowedActions` /
 *     `failureDiagnostics` — plus the run-queue `lists()`, so the timeline + Decision Bar refresh
 *     once the run leaves `Paused`.
 *
 * ROLE/ACTOR DIVERGENCE (Dev Notes): unlike `useRetryWorkflow` (the ODD one, which puts
 * `actorIdentity`+`actorType` in its body), resume/rerun/pause take a HEADER-derived actor
 * (`X-Actor-Identity`, defaulted server-side to `local-operator`) + a `role: "workflow_owner"` BODY
 * field the controller validates then discards. So this hook sends `{ role: RECOVERY_OPERATOR_ROLE,
 * reasonText? }`, NOT the retry body shape. `reasonText` is optional (omitted when blank — resume's
 * genuinely-optional posture) and reviewer-authored → NEVER logged (T-LOG-PII).
 *
 * Typed failures surface via `ProblemDetailsError` (the factory's `unwrap`): `RESUME_NOT_APPLICABLE`
 * (409), `ILLEGAL_TRANSITION` (409), `RUN_NOT_FOUND` (404), and the idempotency-conflict 409s.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { RECOVERY_OPERATOR_ROLE } from '../approvalDecisionView';
import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type ResumeWorkflowRequest = components['schemas']['ResumeWorkflowRequest'];
type ResumeResponse = components['schemas']['ResumeResponse'];

/** The variables a caller passes to resume a paused run. `role` is constant (workflow_owner). */
export interface ResumeWorkflowVariables {
  /** Optional operator note — reviewer-authored, NEVER logged (T-LOG-PII). */
  reasonText?: string | undefined;
}

export type ResumeWorkflowResult = WorkflowMutationResult<ResumeResponse, ResumeWorkflowVariables>;

/** Build the live resume-workflow mutation for a run. */
export function useResumeWorkflow(workflowRunId: string): ResumeWorkflowResult {
  return useWorkflowMutation<ResumeWorkflowVariables, ResumeResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: ResumeWorkflowRequest = {
        role: RECOVERY_OPERATOR_ROLE,
        ...(variables.reasonText !== undefined ? { reasonText: variables.reasonText } : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/resume', {
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
