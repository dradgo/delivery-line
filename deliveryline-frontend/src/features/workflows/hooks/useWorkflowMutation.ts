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
 *
 * Story 2.19 (OQ-2) RELOCATED the `useApproveSpec` scaffold out of this file into its
 * own `hooks/useApproveSpec.ts` (the live hook the decision bar calls), alongside the
 * new `hooks/useRejectSpec.ts`. This file now holds ONLY the generic factory — concrete
 * command hooks live beside their siblings (`useSubmitClarification`).
 */
import {
  useMutation,
  useQueryClient,
  type MutateOptions,
  type QueryClient,
  type UseMutateFunction,
  type UseMutationResult,
} from '@tanstack/react-query';

import { newIdempotencyKey } from '@/lib/api/idempotency';
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
  /**
   * Story 4.23 — OPTIONAL extra success hook, invoked AFTER the built-in `detail(id)` + `lists()`
   * invalidation with the QueryClient, the response, and the attempt variables. Lets a command
   * invalidate keys the factory does not know about — e.g. reconcile must refresh the
   * `conflictId`-keyed detail (rooted at `all`, outside the `detail(runId)` cascade). Backward
   * compatible: omit it and only the two cross-cutting invalidations run (the pre-4.23 behaviour).
   */
  onSuccess?:
    | ((args: { queryClient: QueryClient; data: TData; variables: TVariables }) => void)
    | undefined;
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
    onSuccess: (data, attempt) => {
      const { workflowRunId } = config;
      // Prefix invalidation: detail(id) is a structural prefix of events(id) /
      // allowedActions(id), so one call refreshes all three for this run (AC6).
      void queryClient.invalidateQueries({ queryKey: workflowKeys.detail(workflowRunId) });
      // A spec action also moves the run within (or out of) the review queue.
      void queryClient.invalidateQueries({ queryKey: workflowKeys.lists() });
      // Story 4.23 — the optional command-specific extra invalidation (e.g. the conflict key).
      config.onSuccess?.({ queryClient, data, variables: attempt.variables });
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
