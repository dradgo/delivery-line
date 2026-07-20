/**
 * Story 4.4 (AC4) — the LIVE failure-diagnostics deep-dive query.
 *
 * GETs `/api/v1/workflows/{workflowRunId}/failure-diagnostics` (operationId
 * `getFailureDiagnostics`) → the generated `FailureDiagnosticsResponse`: the NFR7
 * five-questions fields, per-integration sync status, an optional runner-log reference
 * (its `runnerExecutionId` drives the download link), and the safety-ranked recommended
 * recovery actions.
 *
 * Read-only + idempotent → NO Idempotency-Key. The key
 * (`workflowKeys.failureDiagnostics(runId)`) is a structural PREFIX child of
 * `detail(runId)`, so a recovery mutation's `detail(id)` invalidation cascade refreshes
 * the operator panel for free (AC4).
 *
 * `enabled` lets the caller skip the request for a non-failure surface event (e.g. a
 * takeover), so those views make no diagnostics call.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type FailureDiagnostics = components['schemas']['FailureDiagnosticsResponse'];

async function fetchFailureDiagnostics(workflowRunId: string): Promise<FailureDiagnostics> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/failure-diagnostics', {
      params: { path: { workflowRunId } },
    }),
  );
}

/** Read a run's failure-diagnostics deep-dive. Pass `enabled: false` to skip the fetch. */
export function useFailureDiagnostics(workflowRunId: string, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: workflowKeys.failureDiagnostics(workflowRunId),
    queryFn: () => fetchFailureDiagnostics(workflowRunId),
    enabled: options?.enabled ?? true,
    staleTime: STALE_TIME.detail,
  });
}
