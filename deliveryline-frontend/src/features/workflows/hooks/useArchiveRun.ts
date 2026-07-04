/**
 * LIVE `useArchiveRun` mutation (story 3d-8 FE gap). Soft-hides a run via
 * `POST /api/v1/workflows/{id}/archive`. Built on `useWorkflowMutation` so it inherits
 * idempotency-key reuse + the `detail(id)` / `lists()` invalidation cascade (so the
 * allowed-actions refetch flips the control to Unarchive on success).
 *
 * `reason` is user-authored — pass through, NEVER log (T-LOG-PII).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type ArchiveRunRequest = components['schemas']['ArchiveRunRequest'];
export type ArchiveRunResponse = components['schemas']['ArchiveRun'];

/** Variables to archive (hide) a run. `reason` is REQUIRED (backend `@NotBlank`). */
export interface ArchiveRunVariables {
  reason: string;
}

export type ArchiveRunResult = WorkflowMutationResult<ArchiveRunResponse, ArchiveRunVariables>;

export function useArchiveRun(workflowRunId: string): ArchiveRunResult {
  return useWorkflowMutation<ArchiveRunVariables, ArchiveRunResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: ArchiveRunRequest = { reason: variables.reason.trim() };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/archive', {
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
