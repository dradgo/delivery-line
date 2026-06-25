/**
 * Story 3e-2 (AC2) — the LIVE `useRegenerateSpec` mutation.
 *
 * Drives `POST .../regenerate-spec` (`regenerateSpecWithClarifications`): transitions the run
 * `WaitingForSpecApproval → Investigating` and re-dispatches the spec runner so the rebuild
 * incorporates the run's accepted clarifications. Built on the `useWorkflowMutation` factory so
 * it inherits the UUIDv7 `Idempotency-Key` (minted once per attempt) + the `detail(runId)`
 * success invalidation that refreshes the whole run subtree.
 *
 * Unlike accept/answer, regenerate DOES advance run state (response `currentState` is
 * `Investigating`). The endpoint carries NO request body.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type WorkflowStateChangeResponse = components['schemas']['WorkflowStateChangeResponse'];

/** Regenerate carries no per-call variables — the run id is bound at hook creation. */
export type RegenerateSpecVariables = Record<string, never>;

export type RegenerateSpecResult = WorkflowMutationResult<
  WorkflowStateChangeResponse,
  RegenerateSpecVariables
>;

/** Build the live regenerate-spec mutation for a run. */
export function useRegenerateSpec(workflowRunId: string): RegenerateSpecResult {
  return useWorkflowMutation<RegenerateSpecVariables, WorkflowStateChangeResponse>({
    workflowRunId,
    mutationFn: async ({ idempotencyKey }) => {
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/regenerate-spec', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
        }),
      );
    },
  });
}
