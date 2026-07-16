/**
 * Story 4.24 (AC2/AC5/AC9, R6) — the LIVE current + prior failure-classification query.
 *
 * GETs `/api/v1/workflows/{workflowRunId}/failure-classification` (operationId
 * `getFailureClassification`) → the generated `FailureClassificationResponse`: the run's CURRENT
 * operator-applied taxonomy (nullable — a never-classified run returns 200 with `currentTaxonomyValue`
 * absent + `priorClassifications: []`, NOT 404) plus the ordered, most-recent-first prior chain.
 *
 * Read-only + idempotent → NO Idempotency-Key. The key (`workflowKeys.failureClassification(runId)`)
 * is a structural PREFIX child of `detail(runId)`, so the classify mutation's `detail(id)`
 * invalidation cascade refreshes the dialog's prior-classification section + the Run Context Strip
 * badge for free (AC9). `enabled` lets a launch context skip the fetch until the dialog opens.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export type FailureClassification = components['schemas']['FailureClassificationResponse'];

async function fetchFailureClassification(
  workflowRunId: string,
): Promise<FailureClassification> {
  try {
    return unwrap(
      await apiClient.GET('/api/v1/workflows/{workflowRunId}/failure-classification', {
        params: { path: { workflowRunId } },
      }),
    );
  } catch (error) {
    // Field-only structured log (no PII) — a stable code + transport flag only.
    console.warn('recovery.classificationLoadError', {
      code: isProblemDetailsError(error) ? error.code : 'UNKNOWN',
      transport: !isProblemDetailsError(error),
    });
    throw error;
  }
}

/** Read a run's current + prior failure classification. Pass `enabled: false` to skip the fetch. */
export function useFailureClassification(
  workflowRunId: string,
  options?: { enabled?: boolean },
) {
  return useQuery({
    queryKey: workflowKeys.failureClassification(workflowRunId),
    queryFn: () => fetchFailureClassification(workflowRunId),
    enabled: options?.enabled ?? true,
    staleTime: STALE_TIME.detail,
  });
}
