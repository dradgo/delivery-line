/**
 * Story 3.30 (Task 1, AC4/AC10) — the LIVE `useRetryWorkflow` mutation (NEW).
 *
 * The retry baseline shipped backend-side in story 1.18 (`RecoveryService.retry`,
 * the `deliveryline retry` CLI, and the REST endpoint). This hook drives the UI
 * "Retry failed step" action against it. Built on the `useWorkflowMutation` factory
 * so it inherits the two cross-cutting invariants for free, exactly like
 * `useApproveSpec` / `useRejectSpec`:
 *   • (AC7, story 1.9) a UUIDv7 `Idempotency-Key` minted ONCE per attempt + reused
 *     across the attempt's internal retries;
 *   • (AC6) on success, `detail(runId)` invalidation — a PREFIX of `events` /
 *     `allowedActions` — plus the run-queue `lists()`, so the timeline / Run Context
 *     Strip / Decision Bar all refresh once the run leaves `Failed`.
 *
 * RECONCILIATION (Dev Notes #1): the endpoint is
 * `POST /api/v1/workflows/{workflowRunId}/retry-workflow` (operationId `retryWorkflow`),
 * NOT `/retry` — the `-workflow` suffix is REQUIRED and already in the generated client.
 *
 * RECONCILIATION (Dev Notes #2, OQ-1): `RetryWorkflowRequest` requires a non-blank
 * `actorIdentity` + `actorType`. The frontend has no live actor context, so both come
 * from the single `LOCAL_ACTOR_IDENTITY` / `LOCAL_ACTOR_TYPE` seam (`lib/api/actor.ts`)
 * — the same `local-operator` / `HUMAN` value the backend stamps for every other UI
 * governance action. Retry is one-click (no actor form field). `reasonText` /
 * `correlationId` are optional (omitted when blank — the optional-field spread mirrors
 * `useApproveSpec`); `reasonText` is reviewer-authored and NEVER logged (T-LOG-PII).
 *
 * Typed failures surface via `ProblemDetailsError` (the factory's `unwrap`):
 * `RETRY_NOT_APPLICABLE` (409), `ILLEGAL_TRANSITION` (409), `RUN_NOT_FOUND` (404),
 * and the idempotency-conflict 409s.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { LOCAL_ACTOR_IDENTITY, LOCAL_ACTOR_TYPE } from '@/lib/api/actor';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type RetryWorkflowRequest = components['schemas']['RetryWorkflowRequest'];
type WorkflowStateChangeResponse = components['schemas']['WorkflowStateChangeResponse'];

/** The variables a caller passes to retry a failed run. Actor identity is constant (OQ-1). */
export interface RetryWorkflowVariables {
  /** Optional reviewer rationale — reviewer-authored, NEVER logged (T-LOG-PII). */
  reasonText?: string | undefined;
  /** Optional caller-supplied correlation id; the client middleware also stamps one. */
  correlationId?: string | undefined;
}

export type RetryWorkflowResult = WorkflowMutationResult<
  WorkflowStateChangeResponse,
  RetryWorkflowVariables
>;

/** Build the live retry-workflow mutation for a run. */
export function useRetryWorkflow(workflowRunId: string): RetryWorkflowResult {
  return useWorkflowMutation<RetryWorkflowVariables, WorkflowStateChangeResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: RetryWorkflowRequest = {
        actorIdentity: LOCAL_ACTOR_IDENTITY,
        actorType: LOCAL_ACTOR_TYPE,
        ...(variables.reasonText !== undefined ? { reasonText: variables.reasonText } : {}),
        ...(variables.correlationId !== undefined
          ? { correlationId: variables.correlationId }
          : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/retry-workflow', {
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
