/**
 * Story 2.18 (AC1) key reservation → clarification-read endpoint → LIVE wiring.
 *
 * THE CENTRAL RECONCILIATION (now resolved): story 2.18 left this a DISABLED STUB
 * (`enabled:false`, throwing `queryFn`) holding the `workflowKeys.clarifications(runId)`
 * contract because there was NO `GET clarifications` endpoint — the backend inspection
 * method `getClarifications` existed but was never REST-exposed, so the Clarification
 * Region rendered the calm `no open questions` empty state even when open clarifications
 * existed in the backend. The endpoint now ships (`GET .../clarifications`), so this is a
 * real `apiClient.GET` returning the generated `ClarificationsResponse`
 * (`{ clarifications: Clarification[] }`). The container already normalizes this exact
 * shape via `normalizeClarificationsView`, so flipping the hook live needs ZERO region /
 * container changes (exactly as story 2.18 designed for).
 *
 * Read-only + idempotent → NO Idempotency-Key. The key stays a structural PREFIX child of
 * `workflowKeys.detail(runId)`, so a spec mutation's `detail(id)` invalidation cascade
 * (answer / accept / regenerate) refreshes the clarifications for free.
 *
 * Forward-compat (Decision-②): the container's `normalizeClarificationsView` coerces an
 * unrecognized status to `unknown` and drops structurally-malformed rows, so this hook
 * returns the raw generated shape unchanged.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type ClarificationsResponse = components['schemas']['ClarificationsResponse'];

/** GET the clarifications raised against a run's specification (open + answered + lifecycle). */
async function fetchClarifications(workflowRunId: string): Promise<ClarificationsResponse> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/clarifications', {
      params: { path: { workflowRunId } },
    }),
  );
}

export function useClarifications(workflowRunId: string) {
  return useQuery({
    queryKey: workflowKeys.clarifications(workflowRunId),
    queryFn: () => fetchClarifications(workflowRunId),
    // Short freshness: the open-question set turns over as the spec rebuilds / answers land
    // (mirrors the detail + allowed-actions policy — the region must reflect live state).
    staleTime: STALE_TIME.detail,
  });
}
