/**
 * Story 3.28 (Task 2, AC3/AC5) — the LIVE `useAcceptImplementation` mutation (NEW).
 *
 * Drives the Decision Bar's `implementation_review` primary action against the `done`
 * story-3.23 `POST .../accept-implementation` endpoint (op `acceptImplementation`). Built
 * on the `useWorkflowMutation` factory so it inherits the two cross-cutting invariants for
 * free, exactly like `useApproveSpec` / `useRejectSpec`:
 *   • (AC7, story 1.9) a UUIDv7 `Idempotency-Key` minted ONCE per attempt + reused across
 *     the attempt's internal retries;
 *   • (AC6) on success, `detail(runId)` invalidation — a PREFIX of `events` /
 *     `allowedActions` — plus the run-queue `lists()`.
 *
 * RECONCILIATION (R3 / OQ-1): `expectedArtifactVersion` is the IMPLEMENTATION artifact's
 * version (the backend `ApprovalVersionBinder` compares against the artifact identified by
 * `artifactId`), NOT the spec version — the container derives it via
 * `deriveImplementationExpectedVersions`. `expectedContextBundleVersion` comes from the
 * allowed-actions version stamp.
 *
 * ACTOR (R4): OMIT `actorIdentity`/`actorType` — the endpoint derives the actor from the
 * optional `X-Actor-Identity` header and the backend defaults `local-operator` / HUMAN
 * (identical to `useApproveSpec` / `useRejectSpec`, which send no actor — do NOT copy
 * `useRetryWorkflow`'s body-actor pattern). `reviewerRole` is optional on the wire; the
 * impl-review container now sends `"developer"` (story 3b-4) — when sent it must equal
 * `"developer"` → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`.
 *
 * Typed failures surface via `ProblemDetailsError` (the factory's `unwrap`):
 * `APPROVAL_VERSION_MISMATCH` (stale, AC5), `ARTIFACT_PR_LINK_MISMATCH` (409, accept-only),
 * `ARTIFACT_PAYLOAD_UNAVAILABLE` (503), `ILLEGAL_TRANSITION` / `WORKFLOW_RUN_TERMINAL` /
 * `IDEMPOTENCY_KEY_CONFLICT` (409), `RUN_NOT_FOUND` / `ARTIFACT_RECORD_NOT_FOUND` (404),
 * `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type AcceptImplementationRequest = components['schemas']['AcceptImplementationRequest'];
type WorkflowStateChangeResponse = components['schemas']['WorkflowStateChangeResponse'];

/** The variables a caller passes to accept an implementation. Shape mirrors the request. */
export interface AcceptImplementationVariables {
  /** The implementation artifact id the decision fires against (R1). */
  artifactId: string;
  /** Optimistic-concurrency guard (R3) — the IMPLEMENTATION artifact's version. */
  expectedArtifactVersion: number;
  /** Optimistic-concurrency guard (R3) — from `versionStamp.currentContextBundleVersion`. */
  expectedContextBundleVersion: number;
  /** Optional reviewer note — reviewer-authored, NEVER logged (T-LOG-PII). */
  reason?: string | undefined;
  /**
   * R4 — the reviewer role. Optional on the wire, but the impl-review container now sends
   * `"developer"` (story 3b-4); when sent the REST endpoint requires it to equal
   * `"developer"` (else `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`).
   */
  reviewerRole?: string | undefined;
}

export type AcceptImplementationResult = WorkflowMutationResult<
  WorkflowStateChangeResponse,
  AcceptImplementationVariables
>;

/** Build the live accept-implementation mutation for a run. */
export function useAcceptImplementation(workflowRunId: string): AcceptImplementationResult {
  return useWorkflowMutation<AcceptImplementationVariables, WorkflowStateChangeResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: AcceptImplementationRequest = {
        artifactId: variables.artifactId,
        expectedArtifactVersion: variables.expectedArtifactVersion,
        expectedContextBundleVersion: variables.expectedContextBundleVersion,
        ...(variables.reason !== undefined ? { reason: variables.reason } : {}),
        ...(variables.reviewerRole !== undefined ? { reviewerRole: variables.reviewerRole } : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/accept-implementation', {
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
