/**
 * Story 3d-4 (AC1/AC7) — the parked manual-execution input bundle read hook.
 *
 * Fetches `GET /api/v1/workflows/{workflowRunId}/manual-bundle` — the run-scoped, already-redacted
 * runner-contracts input bundle for a run parked in `WaitingForManualExecution`. The Manual
 * Execution Surface offers download + copy of these bytes so the operator can run the agent by hand.
 *
 * Read-only + idempotent → NO Idempotency-Key. The query key is a structural PREFIX child of
 * `detail(id)`, so a manual-artifact submission's `detail(id)` invalidation cascade refreshes it for
 * free. The query is `enabled`-gated by the caller (the surface only renders at
 * `WaitingForManualExecution` with the backend-advertised `obtain_manual_bundle` action), so the
 * 409 `MANUAL_EXECUTION_NOT_APPLICABLE` wrong-state response is never requested on the happy path.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type ManualBundleResponse = components['schemas']['ManualBundleResponse'];

async function fetchManualBundle(workflowRunId: string): Promise<ManualBundleResponse> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/manual-bundle', {
      params: { path: { workflowRunId } },
    }),
  );
}

/**
 * Read a parked run's manual input bundle. `enabled` defaults to true; callers gate it on the
 * backend-advertised `obtain_manual_bundle` action (never a client role) so the request only fires
 * for a parked run.
 */
export function useManualBundle(workflowRunId: string, enabled = true) {
  return useQuery({
    queryKey: workflowKeys.manualBundle(workflowRunId),
    queryFn: () => fetchManualBundle(workflowRunId),
    enabled: enabled && workflowRunId.length > 0,
    staleTime: STALE_TIME.detail,
  });
}
