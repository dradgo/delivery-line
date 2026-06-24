/**
 * Story 3d-7 (FR69, AC5) — typed one-shot read of a run's latest provider usage/limit status.
 *
 * A standard React-Query `useQuery` over the generated `openapi-fetch` client (NOT SSE — this is a
 * one-shot post-run read, unlike the 3d-5 runner-log stream). Returns the generated
 * `ProviderUsageStatus` shape: the 5h + weekly window status (or the documented `not_exposed`
 * state), provider-reported + as-of a timestamp. NON-SECRET by construction — only window numbers,
 * timestamps, and the non-secret account label.
 *
 * Gating (AC5 / Trap T5) is the route's job: the indicator is rendered only when the backend reports
 * the `view_provider_usage_status` action (via `useAllowedActions`, never role-inferred). The
 * endpoint enforces the same gate server-side.
 *
 * The key is a structural PREFIX child of `workflowKeys.detail(runId)`, so a detail invalidation
 * cascade refreshes the indicator for free as the run advances.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type ProviderUsageStatus = components['schemas']['ProviderUsageStatus'];

async function fetchProviderUsageStatus(workflowRunId: string): Promise<ProviderUsageStatus> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/provider-usage', {
      params: { path: { workflowRunId } },
    }),
  );
}

/** Read a run's latest provider usage/limit status (story 3d-7). */
export function useProviderUsageStatus(workflowRunId: string) {
  return useQuery({
    queryKey: workflowKeys.providerUsageStatus(workflowRunId),
    queryFn: () => fetchProviderUsageStatus(workflowRunId),
    // Short freshness: the window status turns over as the run produces more runner output.
    staleTime: STALE_TIME.detail,
  });
}
