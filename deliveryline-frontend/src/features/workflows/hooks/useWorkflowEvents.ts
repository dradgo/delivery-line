/**
 * Story 2.6 (AC8) — typed workflow-events query hook.
 *
 * Wraps `getWorkflowEvents` (6.9). Data is typed by the generated
 * `WorkflowEventsResponse` (which 6.9 pinned against the committed
 * `workflow-events-response.schema.json`) with NO runtime cast. Longer staleTime
 * than detail because the event stream is append-only (AC9, via `eventsQueryOptions`).
 */
import { useQuery } from '@tanstack/react-query';

import { eventsQueryOptions } from '@/lib/api/queryOptions';

export function useWorkflowEvents(workflowRunId: string) {
  return useQuery(eventsQueryOptions(workflowRunId));
}
