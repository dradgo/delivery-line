/**
 * Story 2.6 (AC6, AC7) — the reusable workflow mutation-hook pattern.
 *
 * Stories 2.13 / 2.19 instantiate this for spec approve / reject / clarify; the UI
 * wiring lands there, NOT here. This file provides:
 *   • `useWorkflowMutation` — the factory enforcing the two cross-cutting invariants
 *     every workflow mutation needs:
 *       (AC7) a UUIDv7 idempotency key minted ONCE per mutation attempt and reused
 *             across the internal retries of that attempt — so a transient retry is
 *             idempotent on the backend (story 1.9). The key is embedded into the
 *             mutation variables before TanStack Query runs the mutation, so retries
 *             re-use the exact same attempt payload even if multiple calls overlap.
 *       (AC6) `onSuccess` invalidation of every query a state change can stale —
 *             `detail(id)` (a PREFIX of `events`/`allowedActions`, so one call covers
 *             all three) plus the run-queue `lists()`.
 *   • `useApproveSpec` — ONE concrete scaffold bound to the existing `approveSpec`
 *     operationId, proving the pattern compiles + invalidates. SEAM (story 2.13/2.19):
 *     its UI wiring (decision bar) lands later.
 */
import {
  useMutation,
  useQueryClient,
  type MutateOptions,
  type UseMutateFunction,
  type UseMutationResult,
} from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER, newIdempotencyKey } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

/** Context returned by `onMutate` — carries the attempt's idempotency key. */
export interface WorkflowMutationContext {
  idempotencyKey: string;
}

export interface WorkflowMutationConfig<TVariables, TData> {
  /** The run this mutation acts on — drives invalidation + the (factory-backed) mutation key. */
  workflowRunId: string;
  /**
   * Performs the request. Receives the caller's `variables` plus the stable
   * `idempotencyKey` for THIS attempt (pass it as the `Idempotency-Key` header).
   */
  mutationFn: (args: { variables: TVariables; idempotencyKey: string }) => Promise<TData>;
}

interface WorkflowMutationAttempt<TVariables> {
  idempotencyKey: string;
  variables: TVariables;
}

export type WorkflowMutationResult<TData, TVariables> = Omit<
  UseMutationResult<TData, unknown, WorkflowMutationAttempt<TVariables>, WorkflowMutationContext>,
  'mutate' | 'mutateAsync' | 'variables'
> & {
  mutate: UseMutateFunction<TData, unknown, TVariables, WorkflowMutationContext>;
  mutateAsync: (
    variables: TVariables,
    options?: MutateOptions<TData, unknown, TVariables, WorkflowMutationContext>,
  ) => Promise<TData>;
  variables: TVariables | undefined;
};

/**
 * Build a workflow mutation with idempotency-key reuse (AC7) + success
 * invalidation (AC6) wired in. Generic over the request/response shapes so each
 * command (approve/reject/clarify) instantiates it with its own generated types.
 */
export function useWorkflowMutation<TVariables, TData>(
  config: WorkflowMutationConfig<TVariables, TData>,
): WorkflowMutationResult<TData, TVariables> {
  const queryClient = useQueryClient();
  const mutation = useMutation<
    TData,
    unknown,
    WorkflowMutationAttempt<TVariables>,
    WorkflowMutationContext
  >({
    // Factory-backed key (no-inline-query-keys / AC4). Reusing the run's detail key
    // namespaces this mutation under the run it mutates.
    mutationKey: workflowKeys.detail(config.workflowRunId),
    onMutate: ({ idempotencyKey }): WorkflowMutationContext => {
      return { idempotencyKey };
    },
    mutationFn: ({ variables, idempotencyKey }): Promise<TData> => {
      return config.mutationFn({ variables, idempotencyKey });
    },
    onSuccess: () => {
      const { workflowRunId } = config;
      // Prefix invalidation: detail(id) is a structural prefix of events(id) /
      // allowedActions(id), so one call refreshes all three for this run (AC6).
      void queryClient.invalidateQueries({ queryKey: workflowKeys.detail(workflowRunId) });
      // A spec action also moves the run within (or out of) the review queue.
      void queryClient.invalidateQueries({ queryKey: workflowKeys.lists() });
    },
  });

  const mutate: WorkflowMutationResult<TData, TVariables>['mutate'] = (variables, options) => {
    mutation.mutate(
      { variables, idempotencyKey: newIdempotencyKey() },
      options as
        | MutateOptions<
            TData,
            unknown,
            WorkflowMutationAttempt<TVariables>,
            WorkflowMutationContext
          >
        | undefined,
    );
  };

  const mutateAsync: WorkflowMutationResult<TData, TVariables>['mutateAsync'] = (
    variables,
    options,
  ) => {
    return mutation.mutateAsync(
      { variables, idempotencyKey: newIdempotencyKey() },
      options as
        | MutateOptions<
            TData,
            unknown,
            WorkflowMutationAttempt<TVariables>,
            WorkflowMutationContext
          >
        | undefined,
    );
  };

  return {
    ...mutation,
    mutate,
    mutateAsync,
    variables: mutation.variables?.variables,
  };
}

type ApproveSpecRequest = components['schemas']['ApproveSpecRequest'];
type WorkflowStateChangeResponse = components['schemas']['WorkflowStateChangeResponse'];

/**
 * Concrete scaffold proving the pattern (AC6/AC7). Bound to the existing
 * `approveSpec` operationId so it compiles against 6.9's snapshot today.
 * SEAM (story 2.13/2.19): the approval decision-bar UI calls this; not built here.
 */
export function useApproveSpec(workflowRunId: string) {
  return useWorkflowMutation<ApproveSpecRequest, WorkflowStateChangeResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) =>
      unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/approve-spec', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
          body: variables,
        }),
      ),
  });
}
