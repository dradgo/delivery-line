/**
 * Story 2.22 (AC2.b–d) — `useAssertRunContextLoaded`.
 *
 * Composes `useWorkflowDetail` (2.6) + `useAllowedActions` (2.14 key; currently a
 * disabled stub) into ONE discriminated-union guard so composites stop
 * re-implementing the `{ isPending, isError, data }` shape four different ways.
 *
 * Classification order: a `RUN_NOT_FOUND` problem-details on the detail query →
 * `runNotFound`; any other query error → `error` (with a combined `refetch`);
 * either query still pending → `loading`; otherwise `loaded`.
 *
 * Trap T7: the guard returns the loaded actions to the consumer; it NEVER
 * branches on whether a specific action is enabled — backend-reported actions are
 * the source of truth, the composite renders the buttons. (Until 2.14 rewires the
 * `useAllowedActions` hook from its disabled stub, the actions query stays pending
 * in production and this guard reports `loading`; the wiring is forward-correct.)
 */
import { useCallback } from 'react';

import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { useWorkflowDetail } from '@/features/workflows/hooks/useWorkflowDetail';
import { useAllowedActions } from '@/features/workflows/hooks/useAllowedActions';
import type { WorkflowDetail } from '@/lib/api/queryOptions';

export type RunContextLoadedState =
  | { kind: 'loading' }
  | { kind: 'loaded'; detail: WorkflowDetail; actions: unknown }
  | { kind: 'runNotFound' }
  | { kind: 'error'; error: Error; refetch: () => Promise<unknown> };

export function useAssertRunContextLoaded(runId: string): RunContextLoadedState {
  const detail = useWorkflowDetail(runId);
  const actions = useAllowedActions(runId);

  const refetch = useCallback(
    () => Promise.all([detail.refetch(), actions.refetch()]),
    [detail, actions],
  );

  if (isProblemDetailsError(detail.error) && detail.error.code === 'RUN_NOT_FOUND') {
    return { kind: 'runNotFound' };
  }
  if (detail.isError) {
    return { kind: 'error', error: detail.error, refetch };
  }
  if (actions.isError) {
    return { kind: 'error', error: actions.error, refetch };
  }
  if (detail.isPending || actions.isPending) {
    return { kind: 'loading' };
  }
  return { kind: 'loaded', detail: detail.data, actions: actions.data };
}
