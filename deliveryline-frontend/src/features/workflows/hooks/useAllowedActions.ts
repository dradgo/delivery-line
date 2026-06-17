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
import type { components, operations } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type AllowedActions = components['schemas']['AllowedActions'];

/**
 * The generated `actorRole` query-param union (`product_reviewer | workflow_owner |
 * developer`, story 3b-4). Sourced from the OpenAPI client so a future enum change flows
 * through the type, not a hand-maintained literal.
 */
export type AllowedActionsActorRole = NonNullable<
  operations['getAllowedActions']['parameters']['query']
>['actorRole'];

/**
 * GET the backend-derived allowed actions + version stamp for a run (story 2.14).
 *
 * Story 3b-4: an optional `actorRole` is threaded into the GET as the `actorRole` query
 * param ONLY when provided; when omitted the request is param-free and byte-identical to
 * today (backend defaults to `product_reviewer`).
 */
async function fetchAllowedActions(
  workflowRunId: string,
  actorRole?: AllowedActionsActorRole,
): Promise<AllowedActions> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/allowed-actions', {
      params: {
        path: { workflowRunId },
        ...(actorRole ? { query: { actorRole } } : {}),
      },
    }),
  );
}

/**
 * Read a run's backend-derived allowed actions.
 *
 * Story 3b-4: pass an optional `actorRole` to request the role-scoped action set (e.g.
 * `developer` at `WaitingForReview`). When omitted, the backend defaults to
 * `product_reviewer` and the request URL + query key are byte-identical to today, so the
 * spec/recovery consumers are unaffected (AC5). The role, when given, is appended to the
 * TanStack query key so the developer-role entry never serves a stale `product_reviewer`
 * payload (or vice-versa) — the key stays a PREFIX child of `detail(id)` either way.
 */
export function useAllowedActions(workflowRunId: string, actorRole?: AllowedActionsActorRole) {
  return useQuery({
    queryKey: workflowKeys.allowedActions(workflowRunId, actorRole),
    queryFn: () => fetchAllowedActions(workflowRunId, actorRole),
    // Short freshness: the allowed-action set turns over as the run advances (mirrors
    // the detail policy — the bar must reflect live state, AC9).
    staleTime: STALE_TIME.detail,
  });
}
