/**
 * Story 3d-2 (AC3/AC6) — read the advisory reviewer verdict for a run.
 *
 * Backs the presentational {@link ReviewerVerdictPanel} beside the WaitingForReview Decision Bar.
 * The verdict arrives asynchronously (the reviewer fires non-blocking on review entry, DD-4), so
 * while the server reports `state === 'pending'` this hook polls so the verdict appears live
 * without a manual reload; once `available`/`unavailable` it stops polling (a verdict is terminal).
 *
 * Read-only + idempotent → NO Idempotency-Key. The key is a structural PREFIX child of
 * `workflowKeys.detail(runId)`, so a detail invalidation cascade refreshes it for free.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type ReviewerVerdict = components['schemas']['ReviewerVerdict'];

/** Poll cadence while the verdict is still pending (the reviewer runs async). */
const PENDING_POLL_MS = 3_000;

async function fetchReviewerVerdict(workflowRunId: string): Promise<ReviewerVerdict> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/reviewer-verdict', {
      params: { path: { workflowRunId } },
    }),
  );
}

export function useReviewerVerdict(workflowRunId: string) {
  return useQuery({
    queryKey: workflowKeys.reviewerVerdict(workflowRunId),
    queryFn: () => fetchReviewerVerdict(workflowRunId),
    staleTime: STALE_TIME.detail,
    // Poll only while the verdict is in flight; stop once it resolves (available/unavailable).
    refetchInterval: (query) =>
      query.state.data?.state === 'pending' ? PENDING_POLL_MS : false,
  });
}
