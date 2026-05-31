/**
 * Story 2.20 (AC1/AC9) — thin run-queue list hook.
 *
 * Wraps the shared `listQueryOptions` (story 2.6) so the key always comes from the
 * `workflowKeys.list` factory (satisfies `no-inline-query-keys`) and the result is
 * typed `WorkflowSummary[]` with no runtime cast. The `/workflows` route loader
 * warms the SAME options, so the queue renders flash-free off one shared cache
 * entry (dedup / structural sharing).
 *
 * `filters` come from the route search params (AC9) — this hook holds no state of
 * its own; the URL is the single source of truth for the active filter.
 */
import { useQuery } from '@tanstack/react-query';

import { listQueryOptions } from '@/lib/api/queryOptions';
import type { WorkflowListFilters } from '@/lib/queryKeys/workflowKeys';

export function useWorkflowsList(filters: WorkflowListFilters = {}) {
  return useQuery(listQueryOptions(filters));
}
