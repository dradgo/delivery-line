/**
 * Story 2.19 (Task 2, AC5/AC6/AC11) — the LIVE `useApproveSpec` mutation.
 *
 * RELOCATED (OQ-2): this hook lived as a compile-proof SCAFFOLD inside
 * `useWorkflowMutation.ts` (its comment tagged it for story 2.13/2.19). 2.19 promotes
 * it to its own file — the live hook the Approval Decision Bar calls — so the factory
 * file holds ONLY the generic factory and the concrete command hooks sit beside their
 * siblings (`useSubmitClarification`, `useRejectSpec`). The mutation shape mirrors
 * `useSubmitClarification` exactly (typed variables → explicit body → `unwrap`).
 *
 * Rides the `useWorkflowMutation` factory so it inherits the two invariants for free:
 *   • (AC7) a UUIDv7 `Idempotency-Key` minted ONCE per attempt + reused across the
 *     attempt's internal retries (story 1.9);
 *   • (AC6) on success, `detail(runId)` invalidation — a PREFIX of `allowedActions`/
 *     `events`/`clarifications` — plus the run-queue `lists()`.
 *
 * Typed failures surface via `ProblemDetailsError` (the factory's `unwrap`):
 * `APPROVAL_VERSION_MISMATCH` (the stale-decision trigger, AC6), `ILLEGAL_TRANSITION`,
 * `WORKFLOW_RUN_TERMINAL`, `IDEMPOTENCY_KEY_CONFLICT` (409); `RUN_NOT_FOUND` /
 * `ARTIFACT_RECORD_NOT_FOUND` (404); `ARTIFACT_PAYLOAD_UNAVAILABLE` (503); the
 * idempotency/payload `400`s (schema.d.ts:648–712).
 *
 * OQ-6 — `reviewerRole` is OPTIONAL on the request and there is NO live actor-role
 * context in the frontend yet; the container omits it. It is accepted here for
 * forward-compat (sent only when defined); the bar never invents a role.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type ApproveSpecRequest = components['schemas']['ApproveSpecRequest'];
type WorkflowStateChangeResponse = components['schemas']['WorkflowStateChangeResponse'];

/** The variables a caller passes to approve a spec. Shape mirrors `ApproveSpecRequest`. */
export interface ApproveSpecVariables {
  /** THE dormancy boundary (T-ARTIFACTID) — the spec artifact id the decision fires against. */
  artifactId: string;
  /** Optimistic-concurrency guard (AC6) — derived from `versionStamp.currentSpecArtifactVersion`. */
  expectedArtifactVersion: number;
  /** Optimistic-concurrency guard (AC6) — derived from `versionStamp.currentContextBundleVersion`. */
  expectedContextBundleVersion: number;
  /** Optional reviewer note — reviewer-authored, NEVER logged (T-LOG-PII). */
  reason?: string | undefined;
  /** OQ-6 — omitted today (no live role context); accepted for forward-compat. */
  reviewerRole?: string | undefined;
}

export type ApproveSpecResult = WorkflowMutationResult<
  WorkflowStateChangeResponse,
  ApproveSpecVariables
>;

/** Build the live approve-spec mutation for a run. */
export function useApproveSpec(workflowRunId: string): ApproveSpecResult {
  return useWorkflowMutation<ApproveSpecVariables, WorkflowStateChangeResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: ApproveSpecRequest = {
        artifactId: variables.artifactId,
        expectedArtifactVersion: variables.expectedArtifactVersion,
        expectedContextBundleVersion: variables.expectedContextBundleVersion,
        ...(variables.reason !== undefined ? { reason: variables.reason } : {}),
        ...(variables.reviewerRole !== undefined ? { reviewerRole: variables.reviewerRole } : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/approve-spec', {
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
