/**
 * Story 3c-9 (Task 4, R5/AC3/AC4/AC7) — the project mutation-hook factory.
 *
 * The workflow `useWorkflowMutation` factory is RUN-scoped (it takes a
 * `workflowRunId` and invalidates `workflowKeys`), so it does not model project
 * mutations. This is its project-scoped sibling, reusing only the two cross-cutting
 * idioms every governed mutation needs:
 *   • (AC7) a UUIDv7 `Idempotency-Key` minted ONCE per attempt and reused across the
 *     attempt's internal retries (story 1.9) — the hook's `mutationFn` attaches it to
 *     whichever header the op accepts (`setProjectCredential` REQUIRES it;
 *     `createProject` accepts it optionally; the client middleware also adds a
 *     fallback for any mutation that arrives without one).
 *   • (AC3/AC4) on success, invalidate `projectKeys.lists()` (every list view) plus
 *     the affected `projectKeys.detail(projectId)` when the op targets an existing
 *     project. Create omits `projectId` (the new id is unknown until the response);
 *     the list invalidation surfaces it.
 *
 * The non-idempotent connectivity probe (`useTestProjectConnection`) does NOT ride
 * this factory — it mints no key and invalidates nothing (it returns transient data
 * the component holds in local state).
 */
import {
  useMutation,
  useQueryClient,
  type MutateOptions,
  type UseMutateFunction,
  type UseMutationResult,
} from '@tanstack/react-query';

import { newIdempotencyKey } from '@/lib/api/idempotency';
import { projectKeys } from '@/lib/queryKeys/projectKeys';

/** Context returned by `onMutate` — carries the attempt's idempotency key. */
export interface ProjectMutationContext {
  idempotencyKey: string;
}

export interface ProjectMutationConfig<TVariables, TData> {
  /**
   * The project this mutation acts on — drives `detail(id)` invalidation. Omitted
   * for create (the new id is not known until the response lands).
   */
  projectId?: string | undefined;
  /**
   * Performs the request. Receives the caller's `variables` plus the stable
   * `idempotencyKey` for THIS attempt (attach it as the `Idempotency-Key` header
   * where the op accepts it).
   */
  mutationFn: (args: { variables: TVariables; idempotencyKey: string }) => Promise<TData>;
}

interface ProjectMutationAttempt<TVariables> {
  idempotencyKey: string;
  variables: TVariables;
}

export type ProjectMutationResult<TData, TVariables> = Omit<
  UseMutationResult<TData, unknown, ProjectMutationAttempt<TVariables>, ProjectMutationContext>,
  'mutate' | 'mutateAsync' | 'variables'
> & {
  mutate: UseMutateFunction<TData, unknown, TVariables, ProjectMutationContext>;
  mutateAsync: (
    variables: TVariables,
    options?: MutateOptions<TData, unknown, TVariables, ProjectMutationContext>,
  ) => Promise<TData>;
  variables: TVariables | undefined;
};

/**
 * Build a project mutation with idempotency-key reuse (AC7) + success invalidation
 * (AC3/AC4) wired in. Generic over the request/response shapes so each op
 * (create/update/disable/enable/set-credential) instantiates it with its own
 * generated types.
 */
export function useProjectMutation<TVariables, TData>(
  config: ProjectMutationConfig<TVariables, TData>,
): ProjectMutationResult<TData, TVariables> {
  const queryClient = useQueryClient();
  const mutation = useMutation<
    TData,
    unknown,
    ProjectMutationAttempt<TVariables>,
    ProjectMutationContext
  >({
    // Factory-backed key (no-inline-query-keys requires a direct factory call).
    // Every project mutation namespaces under the list root; per-project detail
    // invalidation is handled explicitly in `onSuccess`.
    mutationKey: projectKeys.lists(),
    onMutate: ({ idempotencyKey }): ProjectMutationContext => ({ idempotencyKey }),
    mutationFn: ({ variables, idempotencyKey }): Promise<TData> =>
      config.mutationFn({ variables, idempotencyKey }),
    onSuccess: () => {
      // Every project mutation can move the list (status/name/credential presence).
      void queryClient.invalidateQueries({ queryKey: projectKeys.lists() });
      // An existing-project mutation also restales its detail (edit-form prefill).
      if (config.projectId !== undefined && config.projectId !== '') {
        void queryClient.invalidateQueries({ queryKey: projectKeys.detail(config.projectId) });
      }
    },
  });

  const mutate: ProjectMutationResult<TData, TVariables>['mutate'] = (variables, options) => {
    mutation.mutate(
      { variables, idempotencyKey: newIdempotencyKey() },
      options as
        | MutateOptions<TData, unknown, ProjectMutationAttempt<TVariables>, ProjectMutationContext>
        | undefined,
    );
  };

  const mutateAsync: ProjectMutationResult<TData, TVariables>['mutateAsync'] = (
    variables,
    options,
  ) =>
    mutation.mutateAsync(
      { variables, idempotencyKey: newIdempotencyKey() },
      options as
        | MutateOptions<TData, unknown, ProjectMutationAttempt<TVariables>, ProjectMutationContext>
        | undefined,
    );

  return {
    ...mutation,
    mutate,
    mutateAsync,
    variables: mutation.variables?.variables,
  };
}
