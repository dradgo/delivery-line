/**
 * Story 3.28 (Task 2, AC4) — the LIVE `useTakeoverWorkflow` mutation (NEW).
 *
 * Drives the Decision Bar's `implementation_review` `Take over` action against the `done`
 * story-3.25 `POST .../takeover` endpoint (op `takeover`) — the RICH endpoint, NOT the
 * older transition-only `/takeover-workflow`. Built on the `useWorkflowMutation` factory so
 * it inherits idempotency-key reuse (AC7) + the `detail(runId)` / `lists()` invalidation
 * cascade (AC6).
 *
 * RECONCILIATIONS:
 *   • R2 — `TakeoverRequest` carries ONLY `reasonText` (REQUIRED) + an optional
 *     `reviewerRole`; there are NO version fields. Takeover CANNOT return
 *     `APPROVAL_VERSION_MISMATCH`.
 *   • R4 — OMIT `actorIdentity` / `actorType` (header-derived, backend defaults
 *     `local-operator` / HUMAN) and `reviewerRole` (optional; if sent must equal
 *     `"developer"` → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`).
 *   • R9 — the response is the RICH `TakeoverResponse` (`currentState`, `recoveryActionId`,
 *     `replayed`, `cancelledInFlightCount?`, `cancelledQueuedCount?`, `preservedPrReference?`,
 *     `correlationId?`), NOT `WorkflowStateChangeResponse`. The container captures
 *     `preservedPrReference` for the AC7 "Continue work in PR {ref}" affordance.
 *
 * `reasonText` is reviewer-authored — pass through, NEVER log (T-LOG-PII).
 *
 * Typed failures via `ProblemDetailsError`: `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400),
 * `ILLEGAL_TRANSITION` / `WORKFLOW_RUN_TERMINAL` / `IDEMPOTENCY_KEY_CONFLICT` (409),
 * `RUN_NOT_FOUND` (404) — NO version-mismatch.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type TakeoverRequest = components['schemas']['TakeoverRequest'];
type TakeoverResponse = components['schemas']['TakeoverResponse'];

/** The variables a caller passes to take over a run. NO version fields (R2). */
export interface TakeoverWorkflowVariables {
  /** Reviewer-authored rationale (REQUIRED) — NEVER logged (T-LOG-PII). */
  reasonText: string;
  /** R4 — omitted today (no live role context); accepted for forward-compat. */
  reviewerRole?: string | undefined;
}

export type TakeoverWorkflowResult = WorkflowMutationResult<
  TakeoverResponse,
  TakeoverWorkflowVariables
>;

/** Build the live takeover mutation for a run (rich `TakeoverResponse`). */
export function useTakeoverWorkflow(workflowRunId: string): TakeoverWorkflowResult {
  return useWorkflowMutation<TakeoverWorkflowVariables, TakeoverResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: TakeoverRequest = {
        reasonText: variables.reasonText,
        ...(variables.reviewerRole !== undefined ? { reviewerRole: variables.reviewerRole } : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/takeover', {
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
