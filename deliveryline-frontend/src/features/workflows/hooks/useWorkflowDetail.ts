/**
 * Story 2.6 (AC8) — typed workflow-detail query hook.
 *
 * Wraps `getWorkflow` (6.9). Data is typed by the generated `WorkflowDetail`
 * response shape with NO runtime cast. The query key + staleTime come from the
 * shared `detailQueryOptions` (which uses `workflowKeys.detail` — AC3/AC4), so a
 * route loader's `ensureQueryData(detailQueryOptions(id))` and this hook share ONE
 * cache entry (dedup / structural sharing, AC10).
 */
import { useQuery } from '@tanstack/react-query';

import { detailQueryOptions } from '@/lib/api/queryOptions';

export function useWorkflowDetail(workflowRunId: string) {
  return useQuery(detailQueryOptions(workflowRunId));
}
