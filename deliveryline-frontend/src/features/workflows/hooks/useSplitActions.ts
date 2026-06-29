/**
 * Story 3f-4 (advisory split-proposal channel) — the three governed split mutations.
 *
 * Front-half / advisory ONLY: these drive the proposal channel beside the Decision Bar at the
 * WaitingForSpecApproval / WaitingForReview gates. NONE of them commits children, moves the
 * parent, or changes the run state — they request / re-propose / dismiss an advisory proposal
 * (the 3f-5 commit affordance "approve_split" is deferred).
 *
 * Built on the {@link useWorkflowMutation} factory so each inherits the two cross-cutting
 * invariants for free, exactly like `useAcceptImplementation` / `useTakeoverWorkflow`:
 *   • (AC7, story 1.9) a UUIDv7 `Idempotency-Key` minted ONCE per attempt + reused across the
 *     attempt's internal retries;
 *   • on success, `detail(runId)` invalidation — a PREFIX of `events` / `allowedActions` /
 *     `splitProposal` — plus the run-queue `lists()`. The panel + the gate's allowed-actions
 *     (which flip request_split ⇄ repropose_split + continue_as_single) therefore refetch.
 *
 * ACTOR: OMIT actor fields — every endpoint derives the actor from the optional
 * `X-Actor-Identity` header and the backend defaults `local-operator` / HUMAN (identical to
 * `useAcceptImplementation`). `feedbackText` is operator-authored — pass through, NEVER log.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type SplitProposalResponse = components['schemas']['SplitProposalResponse'];
type ReproposeSplitRequest = components['schemas']['ReproposeSplitRequest'];
type SplitCommitResponse = components['schemas']['SplitCommitResponse'];

/** The variables a caller passes to re-propose a split. */
export interface ReproposeSplitVariables {
  /** Free-text feedback steering the re-proposal — operator-authored, NEVER logged. */
  feedbackText: string;
}

export type RequestSplitResult = WorkflowMutationResult<SplitProposalResponse, void>;
export type ReproposeSplitResult = WorkflowMutationResult<
  SplitProposalResponse,
  ReproposeSplitVariables
>;
export type DeclineSplitResult = WorkflowMutationResult<SplitProposalResponse, void>;
export type ApproveSplitResult = WorkflowMutationResult<SplitCommitResponse, void>;

/** Request an advisory split proposal (matrix offers this when no proposal is open). */
export function useRequestSplit(workflowRunId: string): RequestSplitResult {
  return useWorkflowMutation<void, SplitProposalResponse>({
    workflowRunId,
    mutationFn: async ({ idempotencyKey }) => {
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/split/request', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
        }),
      );
    },
  });
}

/** Re-propose a split with operator feedback (offered when a proposal is open). */
export function useReproposeSplit(workflowRunId: string): ReproposeSplitResult {
  return useWorkflowMutation<ReproposeSplitVariables, SplitProposalResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: ReproposeSplitRequest = { feedbackText: variables.feedbackText };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/split/repropose', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
          body,
        }),
      );
    },
  });
}

/** Decline the split — continue as one ticket (offered when a proposal is open). */
export function useDeclineSplit(workflowRunId: string): DeclineSplitResult {
  return useWorkflowMutation<void, SplitProposalResponse>({
    workflowRunId,
    mutationFn: async ({ idempotencyKey }) => {
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/split/decline', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
        }),
      );
    },
  });
}

/**
 * Story 3f-5 — commit the open split proposal (offered when a proposal is open). UNLIKE the three
 * advisory mutations above this one COMMITS the decomposition: it fans the proposal out into child
 * runs and transitions this run to Split. Idempotent under the minted `Idempotency-Key`; on success
 * the detail/events/allowed-actions/splitProposal queries + run-queue lists refetch (the run leaves
 * its gate, so the panel + bar tear down to the new state).
 */
export function useApproveSplit(workflowRunId: string): ApproveSplitResult {
  return useWorkflowMutation<void, SplitCommitResponse>({
    workflowRunId,
    mutationFn: async ({ idempotencyKey }) => {
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/split/approve', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
        }),
      );
    },
  });
}
