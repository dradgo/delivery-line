/**
 * Story 4.23 (Task 1, AC1/AC6) — the LIVE integration-conflict detail query (NEW).
 *
 * GETs `/api/v1/integration-conflicts/{conflictId}` (operationId `getIntegrationConflict`, story
 * 4.18) → the generated `IntegrationConflictDetail`: the conflict category + integration type, the
 * raw-JSON-string internal/external state snapshots (FE parses them — see `reconciliationDialogView`),
 * and the safe-first `suggestedDecisions[]`. Read-only + idempotent → NO Idempotency-Key.
 *
 * The key (`workflowKeys.integrationConflict(conflictId)`) is rooted at `all`, NOT under
 * `detail(runId)` — the endpoint is keyed by `conflictId`, not a run id (a conflict spans an
 * integration link). `useReconcileWorkflow` invalidates this key EXPLICITLY on success.
 *
 * `enabled` gates the request to WHILE the dialog is open AND a `conflictId` is set (the dialog
 * passes `enabled: open && !!conflictId`): a closed dialog / absent id makes no request.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type IntegrationConflictDetail = components['schemas']['IntegrationConflictDetail'];

async function fetchIntegrationConflict(conflictId: string): Promise<IntegrationConflictDetail> {
  return unwrap(
    await apiClient.GET('/api/v1/integration-conflicts/{conflictId}', {
      params: { path: { conflictId } },
    }),
  );
}

/**
 * Read a single integration conflict's detail. Pass `enabled: false` (dialog closed) or an empty
 * `conflictId` to skip the fetch — the queryFn never runs with a blank id.
 */
export function useIntegrationConflict(conflictId: string, options?: { enabled?: boolean }) {
  const enabled = (options?.enabled ?? true) && conflictId !== '';
  return useQuery({
    queryKey: workflowKeys.integrationConflict(conflictId),
    queryFn: () => fetchIntegrationConflict(conflictId),
    enabled,
    staleTime: STALE_TIME.detail,
  });
}
