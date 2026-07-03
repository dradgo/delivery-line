/**
 * Story 3g-4 (FR74, AC1) — typed one-shot read of a run's per-step token usage.
 *
 * A standard React-Query `useQuery` over the generated `openapi-fetch` client (NOT SSE — this is a
 * normal JSON read, unlike the 3d-5 runner-log stream). `GET /api/v1/workflows/{id}/steps` returns a
 * direct `StepExecution[]` array (each execution's stage/status/createdAt + the nullable
 * input/output/total token counts, oldest-first). NON-SECRET by construction — only token counts,
 * timestamps, and stage/status labels.
 *
 * The key is a structural PREFIX child of `workflowKeys.detail(runId)`, so a detail invalidation
 * cascade refreshes the per-step token panel for free as the run advances.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type StepExecution = components['schemas']['StepExecution'];

async function fetchStepExecutions(workflowRunId: string): Promise<StepExecution[]> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/steps', {
      params: { path: { workflowRunId } },
    }),
  );
}

/** Read a run's per-step token usage (story 3g-4). */
export function useStepExecutions(workflowRunId: string) {
  return useQuery({
    queryKey: workflowKeys.stepExecutions(workflowRunId),
    queryFn: () => fetchStepExecutions(workflowRunId),
    // Short freshness: the per-step counts turn over as the run produces more runner executions.
    staleTime: STALE_TIME.detail,
  });
}
