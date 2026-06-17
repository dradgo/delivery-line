/**
 * Story 3.28 (Task 2, AC3/AC5) — the LIVE `useRejectImplementation` mutation (NEW).
 *
 * Drives the Decision Bar's `implementation_review` `Reject with feedback` action against
 * the `done` story-3.24 `POST .../reject-implementation` endpoint (op
 * `rejectImplementation`). Built on the `useWorkflowMutation` factory so it inherits
 * idempotency-key reuse (AC7) + the `detail(runId)` / `lists()` invalidation cascade (AC6),
 * exactly like `useApproveSpec` / `useRejectSpec`.
 *
 * `taggedFeedback` is the DEVELOPER rejection taxonomy (R5) —
 * `INCORRECT_APPROACH | INCOMPLETE_IMPLEMENTATION | QUALITY_ISSUE |
 * BREAKS_EXISTING_FUNCTIONALITY | OUT_OF_SCOPE` (UPPERCASE wire; Jackson binds by enum
 * NAME). It is DISTINCT from the spec rework taxonomy (`TaggedFeedback`). Both `reasonText`
 * + `taggedFeedback` are REQUIRED; the backend enforces the role subset
 * (`INVALID_REJECTION_TAXONOMY` 400) and a non-null taxonomy (`MISSING_REJECTION_TAXONOMY`
 * 400). Version semantics + actor/reviewer-role omission mirror `useAcceptImplementation`
 * (R3 / R4). `reasonText` is reviewer-authored — pass through, NEVER log (T-LOG-PII).
 *
 * Typed failures via `ProblemDetailsError`: same families as accept MINUS
 * `ARTIFACT_PR_LINK_MISMATCH` (reject does not read the PR link), PLUS
 * `MISSING_REJECTION_TAXONOMY` / `INVALID_REJECTION_TAXONOMY` (400).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import type { DeveloperTaggedFeedback } from '../approvalDecisionView';
import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type RejectImplementationRequest = components['schemas']['RejectImplementationRequest'];
type WorkflowStateChangeResponse = components['schemas']['WorkflowStateChangeResponse'];

/** The variables a caller passes to reject an implementation (captured by the dialog). */
export interface RejectImplementationVariables {
  /** The implementation artifact id the decision fires against (R1). */
  artifactId: string;
  /** Optimistic-concurrency guard (R3) — the IMPLEMENTATION artifact's version. */
  expectedArtifactVersion: number;
  /** Optimistic-concurrency guard (R3) — from `versionStamp.currentContextBundleVersion`. */
  expectedContextBundleVersion: number;
  /** Reviewer-authored free text — NEVER logged (T-LOG-PII). */
  reasonText: string;
  /** The DEVELOPER rejection taxonomy (R5) — distinct from the spec set. */
  taggedFeedback: DeveloperTaggedFeedback;
  /**
   * R4 — the reviewer role. Optional on the wire, but the impl-review container now sends
   * `"developer"` (story 3b-4); when sent the REST endpoint requires it to equal
   * `"developer"` (else `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`).
   */
  reviewerRole?: string | undefined;
}

export type RejectImplementationResult = WorkflowMutationResult<
  WorkflowStateChangeResponse,
  RejectImplementationVariables
>;

/** Build the live reject-implementation mutation for a run. */
export function useRejectImplementation(workflowRunId: string): RejectImplementationResult {
  return useWorkflowMutation<RejectImplementationVariables, WorkflowStateChangeResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: RejectImplementationRequest = {
        artifactId: variables.artifactId,
        expectedArtifactVersion: variables.expectedArtifactVersion,
        expectedContextBundleVersion: variables.expectedContextBundleVersion,
        reasonText: variables.reasonText,
        taggedFeedback: variables.taggedFeedback,
        ...(variables.reviewerRole !== undefined ? { reviewerRole: variables.reviewerRole } : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/reject-implementation', {
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
