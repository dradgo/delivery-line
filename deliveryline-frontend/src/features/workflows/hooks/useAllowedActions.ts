/**
 * Story 2.6 (AC8) key reservation → story 2.14 endpoint → story 2.19 LIVE wiring.
 *
 * THE 2.18-DEFERRED WIRING (2.18 T5): "Re-wiring `useAllowedActions` live is the
 * Approval-Bar / data-layer story's job, not this one." THAT STORY IS 2.19. This hook
 * was a disabled stub (`enabled:false`, throwing `queryFn`) holding the
 * `workflowKeys.allowedActions(runId)` contract (reserved by 2.6 AC3) until the
 * `GET .../allowed-actions` endpoint shipped (story 2.14, done). It is now a real
 * `apiClient.GET` returning the generated `AllowedActions` (`{ actions: string[];
 * versionStamp }`) — the live action list the Approval Decision Bar gates on + the
 * version stamp it derives expected versions from and detects staleness against.
 *
 * Read-only + idempotent → NO Idempotency-Key (schema.d.ts:64–75). The key stays a
 * structural PREFIX child of `workflowKeys.detail(runId)`, so a spec mutation's
 * `detail(id)` invalidation cascade refreshes the allowed actions for free (AC6).
 *
 * Forward-compat (UX-DR6): the raw `actions[]` may carry wire values this build does
 * not recognize; consumers coerce/drop unknowns via `normalizeActions`
 * (`approvalDecisionView.ts`) — this hook returns the raw generated shape unchanged.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type AllowedActions = components['schemas']['AllowedActions'];

/** GET the backend-derived allowed actions + version stamp for a run (story 2.14). */
async function fetchAllowedActions(workflowRunId: string): Promise<AllowedActions> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/allowed-actions', {
      params: { path: { workflowRunId } },
    }),
  );
}

export function useAllowedActions(workflowRunId: string) {
  return useQuery({
    queryKey: workflowKeys.allowedActions(workflowRunId),
    queryFn: () => fetchAllowedActions(workflowRunId),
    // Short freshness: the allowed-action set turns over as the run advances (mirrors
    // the detail policy — the bar must reflect live state, AC9).
    staleTime: STALE_TIME.detail,
  });
}
