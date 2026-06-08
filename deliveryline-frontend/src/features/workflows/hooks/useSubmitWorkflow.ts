/**
 * Story 2a.1 (Task 1, AC4/AC7) — the LIVE `useSubmitWorkflow` mutation.
 *
 * Submit creates a governed run from a Linear ticket reference over the EXISTING
 * `POST /api/v1/workflows/submit-workflow` (backend `WorkflowController.submit`,
 * already in the committed OpenAPI client). This is a CREATE mutation: there is NO
 * `workflowRunId` yet, so it does NOT ride the `useWorkflowMutation` factory (whose
 * `detail(id)` mutation-key + invalidation presume an existing run). It reuses only
 * the factory's two cross-cutting idioms:
 *   • (AC7) a UUIDv7 `Idempotency-Key` minted ONCE per attempt and threaded through
 *     so internal retries of that attempt reuse it (story 1.9). A user `retry()` from
 *     the error surface re-runs the SAME attempt (same key); a fresh `submit()` mints
 *     a new key — exactly the AC7 key lifecycle.
 *   • (AC4) on success, invalidate `workflowKeys.lists()` so the new run appears in
 *     the queue.
 *
 * Typed failures surface via `ProblemDetailsError` (the shared `unwrap`): e.g.
 * `LINEAR_TICKET_NOT_FOUND`, `IDEMPOTENCY_KEY_CONFLICT`, `MISSING_IDEMPOTENCY_KEY`,
 * `VALIDATION_ERROR`, `INTERNAL_ERROR` — all open-union `DomainErrorCode` strings the
 * consumer branches on by `code`, never `message`.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER, newIdempotencyKey } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

type SubmitWorkflowRequest = components['schemas']['SubmitWorkflowRequest'];
type SubmitWorkflowResponse = components['schemas']['SubmitWorkflowResponse'];

/** The `ActorType` enum, derived from the generated request type (single source). */
export type ActorType = SubmitWorkflowRequest['actorType'];

/** The four valid actor types (default `HUMAN` for a human PM). */
export const ACTOR_TYPES: readonly ActorType[] = ['HUMAN', 'AGENT', 'SYSTEM', 'SERVICE_ACCOUNT'];

/** The variables a caller passes to submit a run. Shape mirrors `SubmitWorkflowRequest`. */
export interface SubmitRunVariables {
  /** Required, ≤128 — the Linear ticket the run is governed against. NEVER logged (PII). */
  linearTicketReference: string;
  /** Required, ≤128 — the submitting actor's identity. NEVER logged (PII). */
  actorIdentity: string;
  /** Required — the actor classification. Defaults to `HUMAN` in the form. */
  actorType: ActorType;
  /** Optional, ≤128 — caller-supplied correlation id; omitted from the body when blank. */
  correlationId?: string | undefined;
}

/** One submit attempt — the caller's variables plus the stable key for THIS attempt. */
interface SubmitAttempt {
  idempotencyKey: string;
  variables: SubmitRunVariables;
}

export interface UseSubmitWorkflowResult {
  /** TanStack mutation status — drives the button-state resolver (AC5). */
  status: 'idle' | 'pending' | 'success' | 'error';
  /** True while a submit is in flight (disable + progress, prevent double-submit). */
  isPending: boolean;
  /** The success payload (all fields optional — guard before rendering). */
  data: SubmitWorkflowResponse | undefined;
  /** The last failure (a `ProblemDetailsError` for problem+json, else a transport error). */
  error: unknown;
  /** Mint a FRESH idempotency key and submit — a NEW attempt (AC7). */
  submit: (variables: SubmitRunVariables) => void;
  /** Re-run the LAST attempt reusing its idempotency key (AC7 retry). No-op before any submit. */
  retry: () => void;
  /** Clear the mutation back to idle (used when editing fields after a failure). */
  reset: () => void;
}

/**
 * Build the live submit-run mutation. No run id argument — submit CREATES the run.
 */
export function useSubmitWorkflow(): UseSubmitWorkflowResult {
  const queryClient = useQueryClient();

  const mutation = useMutation<SubmitWorkflowResponse, unknown, SubmitAttempt>({
    // Factory-backed key (no-inline-query-keys / AC4): a submit affects the run queue.
    mutationKey: workflowKeys.lists(),
    mutationFn: async ({ variables, idempotencyKey }): Promise<SubmitWorkflowResponse> => {
      const body: SubmitWorkflowRequest = {
        linearTicketReference: variables.linearTicketReference,
        actorIdentity: variables.actorIdentity,
        actorType: variables.actorType,
        // Optional-field spread (mirror `useApproveSpec`): omit when absent/blank.
        ...(variables.correlationId !== undefined && variables.correlationId !== ''
          ? { correlationId: variables.correlationId }
          : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/submit-workflow', {
          params: { header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } },
          body,
        }),
      );
    },
    onSuccess: () => {
      // The new run enters the queue — refresh every list query (AC4).
      void queryClient.invalidateQueries({ queryKey: workflowKeys.lists() });
    },
  });

  const submit = (variables: SubmitRunVariables): void => {
    mutation.mutate({ variables, idempotencyKey: newIdempotencyKey() });
  };

  const retry = (): void => {
    // Reuse the LAST attempt verbatim — SAME idempotency key + payload (AC7).
    const lastAttempt = mutation.variables;
    if (lastAttempt !== undefined) {
      mutation.mutate(lastAttempt);
    }
  };

  return {
    status: mutation.status,
    isPending: mutation.isPending,
    data: mutation.data,
    error: mutation.error,
    submit,
    retry,
    reset: mutation.reset,
  };
}
