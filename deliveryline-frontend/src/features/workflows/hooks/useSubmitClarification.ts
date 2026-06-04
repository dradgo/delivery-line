/**
 * Story 2.18 (AC5, AC9) — the LIVE `useSubmitClarification` mutation.
 *
 * THE ONE GENUINE LIVE SEAM 2.18 closes (OQ-2): unlike the disabled read stub, the
 * `POST .../clarifications/{clarificationId}/answer` endpoint (`answerClarification`)
 * genuinely exists in `schema.d.ts:101–111`. Built on the `useWorkflowMutation`
 * factory so it inherits the two workflow-mutation invariants for free:
 *   • (AC7) a UUIDv7 `Idempotency-Key` minted ONCE per attempt + reused across the
 *     attempt's internal retries (story 1.9);
 *   • (AC6) on success, `detail(runId)` invalidation — a PREFIX of `events`/
 *     `allowedActions`/`clarifications`, so one call refreshes the whole run subtree.
 *
 * T9 — answering does NOT change run state: the response `currentState` stays e.g.
 * `WaitingForSpecApproval`; the lifecycle indicator advances the CLARIFICATION, not
 * the workflow. Callers read `clarificationStatus` (which may be the `"unknown"`
 * sentinel on idempotent replays — schema.d.ts:249), never re-derive workflow state.
 *
 * Typed failures surface via `ProblemDetailsError` — `CLARIFICATION_NOT_FOUND`,
 * `CLARIFICATION_ARTIFACT_VERSION_MISMATCH` (retryable), `CLARIFICATION_TERMINAL_STATE`,
 * `ILLEGAL_CLARIFICATION_TRANSITION` (schema.d.ts:741–767). The factory's retry
 * policy re-attempts only backend-flagged-retryable failures, reusing the key.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type AnswerClarificationRequest = components['schemas']['AnswerClarificationRequest'];
type ClarificationAnswerResponse = components['schemas']['ClarificationAnswerResponse'];

/** The variables a caller passes to submit one clarification answer. */
export interface SubmitClarificationVariables {
  /** The clarification being answered (`cla_…`). */
  clarificationId: string;
  /** UNTRUSTED reviewer wording — the backend records it; the UI sanitizes on render. */
  answerText: string;
  /** The artifact version this clarification binds to (optimistic-concurrency guard). */
  artifactId: string;
  expectedArtifactVersion: number;
}

export type SubmitClarificationResult = WorkflowMutationResult<
  ClarificationAnswerResponse,
  SubmitClarificationVariables
>;

/**
 * Build the live submit-answer mutation for a run. The region's container wires its
 * `onSubmitAnswer(clarificationId, answerText)` callback to `mutate`, sourcing
 * `artifactId`/`expectedArtifactVersion` from the answered `ClarificationView`.
 */
export function useSubmitClarification(workflowRunId: string): SubmitClarificationResult {
  return useWorkflowMutation<SubmitClarificationVariables, ClarificationAnswerResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: AnswerClarificationRequest = {
        answerText: variables.answerText,
        artifactId: variables.artifactId,
        expectedArtifactVersion: variables.expectedArtifactVersion,
      };
      return unwrap(
        await apiClient.POST(
          '/api/v1/workflows/{workflowRunId}/clarifications/{clarificationId}/answer',
          {
            params: {
              path: { workflowRunId, clarificationId: variables.clarificationId },
              header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
            },
            body,
          },
        ),
      );
    },
  });
}
