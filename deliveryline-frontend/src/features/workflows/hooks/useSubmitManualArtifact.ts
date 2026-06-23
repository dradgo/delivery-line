/**
 * Story 3d-4 (AC2/AC7) — the LIVE manual-artifact submission mutation.
 *
 * Drives the Manual Execution Surface's submit affordance against the governed
 * `POST /api/v1/workflows/{workflowRunId}/manual-artifact` endpoint (op `submitManualArtifact`).
 * Built on `useWorkflowMutation` so it inherits the two cross-cutting invariants exactly like
 * `useAcceptImplementation`:
 *   • (AC7, story 1.9) a UUIDv7 `Idempotency-Key` minted ONCE per attempt + reused across retries;
 *   • (AC6) on success, `detail(runId)` invalidation — a PREFIX of `events`/`allowedActions`/
 *     `manualBundle` — plus the run-queue `lists()` (the run leaves WaitingForManualExecution).
 *
 * ACTOR: OMIT `actorIdentity`/`actorType` — the endpoint derives the actor from the optional
 * `X-Actor-Identity` header and defaults `local-operator` / HUMAN (identical to
 * `useAcceptImplementation`).
 *
 * Typed failures surface via `ProblemDetailsError` (the factory's `unwrap`):
 * `MANUAL_EXECUTION_NOT_APPLICABLE` (409, wrong state), `RUNNER_OUTPUT_VALIDATION_FAILED` (502),
 * `RUNNER_ARTIFACT_TYPE_MISMATCH` (502), `IDEMPOTENCY_KEY_CONFLICT` (409), `RUN_NOT_FOUND` (404).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type ManualArtifactSubmissionRequest = components['schemas']['ManualArtifactSubmissionRequest'];
type WorkflowStateChangeResponse = components['schemas']['WorkflowStateChangeResponse'];

/** The variables a caller passes to submit a manual artifact. */
export interface SubmitManualArtifactVariables {
  /** The runner-result-shaped JSON the operator produced (parsed from paste / file upload). */
  result: ManualArtifactSubmissionRequest['result'];
  /** Optional map of contentReference -> base64 artifact bytes (e.g. a spec's markdown). */
  artifactContents?: Record<string, string> | undefined;
}

export type SubmitManualArtifactResult = WorkflowMutationResult<
  WorkflowStateChangeResponse,
  SubmitManualArtifactVariables
>;

/** Build the live manual-artifact submission mutation for a run. */
export function useSubmitManualArtifact(workflowRunId: string): SubmitManualArtifactResult {
  return useWorkflowMutation<SubmitManualArtifactVariables, WorkflowStateChangeResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: ManualArtifactSubmissionRequest = {
        result: variables.result,
        ...(variables.artifactContents !== undefined
          ? { artifactContents: variables.artifactContents }
          : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/manual-artifact', {
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
