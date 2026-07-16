/**
 * Story 4.23 (Task 10, AC8) — the LIVE run-scoped unresolved-conflicts query (NEW).
 *
 * The reconciliation dialog is keyed on a concrete `conflictId` (AC1), but its launch surfaces carry
 * only a run id: the Decision Bar `onReconcile` seam fires with NO argument (`onReconcile?.()`,
 * `ApprovalDecisionBar`), and the failure-diagnostics drift indicator (`FailureEventSurface`) knows
 * only the drifted integration. So both must RESOLVE a `conflictId` from the run before opening the
 * dialog. This GETs `/api/v1/integration-conflicts?workflowRunId=…&resolved=false` (operationId
 * `listIntegrationConflicts`, story 4.18) → the unresolved `IntegrationConflictSummary[]` for the run
 * (detection-time DESC). Read-only + idempotent → NO Idempotency-Key.
 *
 * The key (`workflowKeys.integrationConflicts(runId)`) is a structural PREFIX child of
 * `detail(runId)`, so a reconcile mutation's `detail(id)` invalidation cascade refreshes it — a
 * reconciled conflict drops off the list automatically.
 *
 * `enabled` gates the request so a surface that never offers reconcile makes no call.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type IntegrationConflictSummary = components['schemas']['IntegrationConflictSummary'];
export type IntegrationConflictListResponse =
  components['schemas']['IntegrationConflictListResponse'];

async function fetchRunIntegrationConflicts(
  workflowRunId: string,
): Promise<IntegrationConflictListResponse> {
  return unwrap(
    await apiClient.GET('/api/v1/integration-conflicts', {
      params: { query: { workflowRunId, resolved: false } },
    }),
  );
}

/**
 * Read a run's UNRESOLVED integration conflicts (newest-first). Pass `enabled: false` to skip the
 * fetch. Consumers pick the first entry (most-recently detected) to open the reconciliation dialog.
 */
export function useRunIntegrationConflicts(workflowRunId: string, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: workflowKeys.integrationConflicts(workflowRunId),
    queryFn: () => fetchRunIntegrationConflicts(workflowRunId),
    enabled: options?.enabled ?? true,
    staleTime: STALE_TIME.detail,
  });
}

/**
 * Resolve the `conflictId` to reconcile from a run's unresolved-conflict list.
 *
 * - No hint (the Decision Bar seam) → the most-recently detected unresolved conflict (list is
 *   detection-time DESC).
 * - A non-empty hint (a specific drifted integration row) → the conflict ON that integration ONLY.
 *   The hint is a HARD filter, not a preference: if nothing on the hinted integration has an
 *   unresolved conflict, return `undefined` rather than falling back to an unrelated conflict — a
 *   drifted Linear row must never open the dialog onto a GitHub conflict (it would show the wrong
 *   snapshots/decisions). The caller then hides the affordance.
 *
 * Returns `undefined` when nothing is resolvable — the caller does not open the dialog.
 */
export function resolveConflictId(
  conflicts: readonly IntegrationConflictSummary[] | undefined,
  preferredIntegrationType?: string | null,
): string | undefined {
  const unresolved = (conflicts ?? []).filter((c) => typeof c.conflictId === 'string');
  if (unresolved.length === 0) {
    return undefined;
  }
  if (preferredIntegrationType != null && preferredIntegrationType !== '') {
    const hint = preferredIntegrationType.toLowerCase();
    // Loose match so a "GitHub" drift row (`github`) resolves a `github_pr` conflict, and vice versa.
    const match = unresolved.find((c) => {
      const type = c.integrationType?.toLowerCase();
      return (
        type !== undefined && (type === hint || type.startsWith(hint) || hint.startsWith(type))
      );
    });
    // Hint is a hard filter: no match ⇒ undefined (do NOT fall back to an unrelated integration).
    return match?.conflictId;
  }
  return unresolved[0]?.conflictId;
}
