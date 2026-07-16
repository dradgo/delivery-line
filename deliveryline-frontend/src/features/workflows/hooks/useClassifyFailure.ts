/**
 * Story 4.24 (AC6, R6) — the LIVE `useClassifyFailure` mutation (NEW).
 *
 * Drives the classification dialog's "Apply classification" against story 4.14's endpoint
 * `POST /api/v1/workflows/{workflowRunId}/classify-failure` (operationId `classifyFailure`). Built on
 * `useWorkflowMutation` (UUIDv7 `Idempotency-Key` + `detail(id)`/`lists()` invalidation — the
 * `detail(id)` prefix covers `useFailureClassification`, so the dialog's prior-classification section
 * + the Run Context Strip badge refresh for free on success, AC9).
 *
 * ROLE/ACTOR DIVERGENCE (Trap 5): the header-derived actor + a `role: "workflow_owner"` BODY field
 * (`RECOVERY_OPERATOR_ROLE`, validated then discarded) — NOT retry's `actorIdentity`/`actorType`.
 * `reasonText` is GENUINELY OPTIONAL (blank → stored null, no `MISSING_REASON_TEXT`) → spread-omitted
 * when blank, and reviewer-authored so NEVER logged (T-LOG-PII).
 *
 * Typed failures via `ProblemDetailsError`: `DEPRECATED_TAXONOMY_VALUE` (400, `details.replacementValue`
 * — the dialog re-selects the replacement, AC7), `INVALID_TAXONOMY_VALUE` / `MISSING_TAXONOMY_VALUE`
 * (400), `CLASSIFY_NOT_APPLICABLE` (409), `RUN_NOT_FOUND` (404), and the idempotency-conflict 409s.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import type { components } from '@/lib/api/schema';

import { RECOVERY_OPERATOR_ROLE } from '../approvalDecisionView';
import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type ClassifyFailureRequest = components['schemas']['ClassifyFailureRequest'];
type ClassifyFailureResponse = components['schemas']['ClassifyFailureResponse'];

/** The variables a caller passes to classify a failed run. `role` is constant (workflow_owner). */
export interface ClassifyFailureVariables {
  /**
   * The governed taxonomy wire value the operator selected. A plain `string` because the dialog
   * sources it from the live registry (whose `value` is a `string`); it is validated on the wire
   * against the generated union at the mutation boundary + by the backend service.
   */
  taxonomyValue: string;
  /** Optional operator note — reviewer-authored, NEVER logged (T-LOG-PII); blank → omitted (stored null). */
  reasonText?: string | undefined;
}

export type ClassifyFailureResult = WorkflowMutationResult<
  ClassifyFailureResponse,
  ClassifyFailureVariables
>;

/** Build the live classify-failure mutation for a run. */
export function useClassifyFailure(workflowRunId: string): ClassifyFailureResult {
  return useWorkflowMutation<ClassifyFailureVariables, ClassifyFailureResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const trimmedReason = variables.reasonText?.trim();
      const body: ClassifyFailureRequest = {
        role: RECOVERY_OPERATOR_ROLE,
        // The dialog's selection comes from the live registry (a `string`); narrow to the generated
        // wire union at this single boundary — the backend service is the authoritative validator.
        taxonomyValue: variables.taxonomyValue as ClassifyFailureRequest['taxonomyValue'],
        ...(trimmedReason !== undefined && trimmedReason !== ''
          ? { reasonText: trimmedReason }
          : {}),
      };
      try {
        const result = unwrap(
          await apiClient.POST('/api/v1/workflows/{workflowRunId}/classify-failure', {
            params: {
              path: { workflowRunId },
              header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
            },
            body,
          }),
        );
        // Field-only structured log — the applied WIRE value only, never reasonText/ids (T-LOG-PII).
        console.info('recovery.classifySubmit', { taxonomyValue: result.taxonomyValue });
        return result;
      } catch (error) {
        console.error('recovery.classifyError', {
          code: isProblemDetailsError(error) ? error.code : 'UNKNOWN',
          transport: !isProblemDetailsError(error),
        });
        throw error;
      }
    },
  });
}
