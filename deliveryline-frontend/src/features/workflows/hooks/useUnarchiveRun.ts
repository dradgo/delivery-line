/**
 * LIVE `useUnarchiveRun` mutation (story 3d-8 FE gap). Reverses a soft-hide via
 * `POST /api/v1/workflows/{id}/unarchive`. Symmetric twin of `useArchiveRun`; the
 * `reason` is OPTIONAL (backend `UnarchiveRunRequest.reason` is nullable) — omit it from
 * the body when blank. `reason` is user-authored — pass through, NEVER log (T-LOG-PII).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';
import type { ArchiveRunResponse } from './useArchiveRun';

type UnarchiveRunRequest = components['schemas']['UnarchiveRunRequest'];

/** Variables to un-archive (un-hide) a run. `reason` is OPTIONAL. */
export interface UnarchiveRunVariables {
  reason?: string | undefined;
}

export type UnarchiveRunResult = WorkflowMutationResult<ArchiveRunResponse, UnarchiveRunVariables>;

export function useUnarchiveRun(workflowRunId: string): UnarchiveRunResult {
  return useWorkflowMutation<UnarchiveRunVariables, ArchiveRunResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const trimmed = variables.reason?.trim();
      const body: UnarchiveRunRequest =
        trimmed !== undefined && trimmed !== '' ? { reason: trimmed } : {};
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/unarchive', {
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
