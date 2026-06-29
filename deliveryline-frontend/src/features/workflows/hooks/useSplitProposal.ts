/**
 * Story 3f-4 (advisory split-proposal channel) — read the current split proposal for a run.
 *
 * Backs the presentational {@link SplitProposalPanel} beside the Decision Bar at the
 * WaitingForSpecApproval / WaitingForReview gates. The proposal is produced asynchronously
 * (the split call fires non-blocking when an operator requests one), so while the server
 * reports `state === 'pending'` this hook polls so the proposal appears live without a manual
 * reload; once `available` / `unavailable` / `none` it stops polling.
 *
 * Read-only + idempotent → NO Idempotency-Key. The key is a structural PREFIX child of
 * `workflowKeys.detail(runId)`, so a split request/repropose/decline mutation's detail
 * invalidation cascade refreshes it for free.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type SplitProposalResponse = components['schemas']['SplitProposalResponse'];

/** Poll cadence while the proposal is still being produced (the split call runs async). */
const PENDING_POLL_MS = 3_000;

async function fetchSplitProposal(workflowRunId: string): Promise<SplitProposalResponse> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/split-proposal', {
      params: { path: { workflowRunId } },
    }),
  );
}

export function useSplitProposal(workflowRunId: string) {
  return useQuery({
    queryKey: workflowKeys.splitProposal(workflowRunId),
    queryFn: () => fetchSplitProposal(workflowRunId),
    staleTime: STALE_TIME.detail,
    // Poll only while the proposal is in flight; stop once it resolves (available/unavailable/none).
    refetchInterval: (query) => (query.state.data?.state === 'pending' ? PENDING_POLL_MS : false),
  });
}
