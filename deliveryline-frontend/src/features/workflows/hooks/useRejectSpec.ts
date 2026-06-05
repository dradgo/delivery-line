/**
 * Story 2.19 (Task 2, AC5/AC6/AC8) — the LIVE `useRejectSpec` mutation (NEW).
 *
 * No reject hook existed before 2.19. `POST .../reject-spec` ships in the generated
 * `schema.d.ts` (story 2.13 done). Built on the `useWorkflowMutation` factory so it
 * inherits idempotency-key reuse (AC7) + the `detail(runId)`/`lists()` invalidation
 * cascade (AC6), exactly like `useApproveSpec` / `useSubmitClarification`. A successful
 * rejection increments `specRejectionLoopCount` backend-side (story 2.10).
 *
 * `taggedFeedback` is the UPPERCASE rework-taxonomy enum
 * (`MISSING_SCOPE | UNCLEAR_SPECIFICATION | MISUNDERSTOOD_IMPLEMENTATION`) per
 * `schema.d.ts:301–311` — NOT the epic's lowercase prose (T-TAGGED-UPPERCASE).
 *
 * Typed failures via `ProblemDetailsError`: same families as approve —
 * `APPROVAL_VERSION_MISMATCH` (stale, AC6), `ILLEGAL_TRANSITION`,
 * `WORKFLOW_RUN_TERMINAL`, `IDEMPOTENCY_KEY_CONFLICT` (409); `RUN_NOT_FOUND` /
 * `ARTIFACT_RECORD_NOT_FOUND` (404); `ARTIFACT_PAYLOAD_UNAVAILABLE` (503); the
 * idempotency/payload `400`s.
 *
 * `reasonText` is reviewer-authored free text — pass through, NEVER log (T-LOG-PII).
 * OQ-6 — `reviewerRole` omitted today (no live role context).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import type { TaggedFeedback } from '../approvalDecisionView';
import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type RejectSpecRequest = components['schemas']['RejectSpecRequest'];
type WorkflowStateChangeResponse = components['schemas']['WorkflowStateChangeResponse'];

/** The variables a caller passes to reject a spec (captured by the rationale dialog). */
export interface RejectSpecVariables {
  artifactId: string;
  expectedArtifactVersion: number;
  expectedContextBundleVersion: number;
  /** Reviewer-authored free text — NEVER logged (T-LOG-PII). */
  reasonText: string;
  /** The UPPERCASE rework-taxonomy enum (T-TAGGED-UPPERCASE). */
  taggedFeedback: TaggedFeedback;
  /** OQ-6 — omitted today; accepted for forward-compat. */
  reviewerRole?: string | undefined;
}

export type RejectSpecResult = WorkflowMutationResult<
  WorkflowStateChangeResponse,
  RejectSpecVariables
>;

/** Build the live reject-spec mutation for a run. */
export function useRejectSpec(workflowRunId: string): RejectSpecResult {
  return useWorkflowMutation<RejectSpecVariables, WorkflowStateChangeResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: RejectSpecRequest = {
        artifactId: variables.artifactId,
        expectedArtifactVersion: variables.expectedArtifactVersion,
        expectedContextBundleVersion: variables.expectedContextBundleVersion,
        reasonText: variables.reasonText,
        taggedFeedback: variables.taggedFeedback,
        ...(variables.reviewerRole !== undefined ? { reviewerRole: variables.reviewerRole } : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/reject-spec', {
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
