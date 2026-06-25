/**
 * Story 3e-2 (AC1) — the LIVE `useAcceptClarification` mutation.
 *
 * The accept twin of `useSubmitClarification`: drives `POST .../clarifications/{id}/accept`
 * (`acceptClarification`), moving an answered clarification `answered → accepted` so a spec
 * rebuild can incorporate it. Built on the `useWorkflowMutation` factory so it inherits the
 * two workflow-mutation invariants for free:
 *   • a UUIDv7 `Idempotency-Key` minted ONCE per attempt + reused across internal retries;
 *   • on success, `detail(runId)` invalidation — a PREFIX of `events`/`allowedActions`/
 *     `clarifications`, so one call refreshes the whole run subtree.
 *
 * Accepting does NOT change run state (the run stays `WaitingForSpecApproval`); callers read
 * `clarificationStatus` (expected `"accepted"`), never re-derive workflow state. The endpoint
 * carries NO request body — the clarification's identity is the (run, clarification) pair.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type ClarificationAcceptResponse = components['schemas']['ClarificationAcceptResponse'];

/** The variables a caller passes to accept one clarification. */
export interface AcceptClarificationVariables {
  /** The answered clarification being accepted (`clr_…`). */
  clarificationId: string;
}

export type AcceptClarificationResult = WorkflowMutationResult<
  ClarificationAcceptResponse,
  AcceptClarificationVariables
>;

/** Build the live accept-clarification mutation for a run. */
export function useAcceptClarification(workflowRunId: string): AcceptClarificationResult {
  return useWorkflowMutation<AcceptClarificationVariables, ClarificationAcceptResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      return unwrap(
        await apiClient.POST(
          '/api/v1/workflows/{workflowRunId}/clarifications/{clarificationId}/accept',
          {
            params: {
              path: { workflowRunId, clarificationId: variables.clarificationId },
              header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
            },
          },
        ),
      );
    },
  });
}
