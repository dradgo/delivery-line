/**
 * Story 4.22 (Task 3, AC5) — the LIVE non-mutating rerun-from-step preview query (NEW).
 *
 * GETs `/api/v1/workflows/{workflowRunId}/preview-rerun-from-step?targetStep=X` (operationId
 * `previewRerunFromStep`) → the generated `PreviewRerunFromStepResponse`: which artifacts a rerun to
 * the chosen safe step WOULD supersede + which approval it WOULD invalidate. Read-only + idempotent
 * → NO Idempotency-Key.
 *
 * The key (`workflowKeys.rerunPreview(runId, targetStep)`) is a structural PREFIX child of
 * `detail(runId)` — keyed per `targetStep` so switching the dialog's step select fetches a distinct
 * preview — so a recovery mutation's `detail(id)` invalidation cascade clears stale previews.
 *
 * `enabled` gates the request to WHILE the rerun dialog is open AND a `targetStep` is chosen (the
 * container passes `enabled: dialogOpen`): a closed dialog / unset step makes no request.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

import type { RerunTargetStep } from './useRerunFromStep';

export type PreviewRerunFromStepResponse = components['schemas']['PreviewRerunFromStepResponse'];

async function fetchPreviewRerunFromStep(
  workflowRunId: string,
  targetStep: RerunTargetStep,
): Promise<PreviewRerunFromStepResponse> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/preview-rerun-from-step', {
      params: { path: { workflowRunId }, query: { targetStep } },
    }),
  );
}

/**
 * Read the non-mutating preview of a rerun to `targetStep`. Pass `enabled: false` (dialog closed)
 * or an `undefined` `targetStep` to skip the fetch.
 */
export function usePreviewRerunFromStep(
  workflowRunId: string,
  targetStep: RerunTargetStep | undefined,
  options?: { enabled?: boolean },
) {
  const enabled = (options?.enabled ?? true) && targetStep !== undefined;
  return useQuery({
    // targetStep is part of the key ONLY when set; the `enabled` gate guarantees the queryFn never
    // runs with an undefined step, so the `?? ''` placeholder key is never actually fetched.
    queryKey: workflowKeys.rerunPreview(workflowRunId, targetStep ?? ''),
    queryFn: () => fetchPreviewRerunFromStep(workflowRunId, targetStep as RerunTargetStep),
    enabled,
    staleTime: STALE_TIME.detail,
  });
}
