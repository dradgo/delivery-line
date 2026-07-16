/**
 * Story 4.23 (Task 2, AC5/AC6) — the LIVE `useReconcileWorkflow` mutation test.
 *
 * MSW-backed: success → `ReconcileResponse`; the body carries `conflictId` + `resolutionDecision` +
 * `reasonText` + `role: "workflow_owner"` and an `Idempotency-Key` header, and OMITS actor fields +
 * any version stamp (trap #5/#6). A `CONFLICT_ALREADY_RESOLVED` (409) surfaces a typed
 * `ProblemDetailsError`. On success the `conflictId`-keyed detail query is invalidated (trap #7).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { server } from '@/test/server';
import { useReconcileWorkflow } from './useReconcileWorkflow';

const RECONCILE_URL = 'http://localhost/api/v1/workflows/:runId/reconcile';
const RUN_ID = 'run_reconcile_demo_001';
const CONFLICT_ID = 'icf_demo_001';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function mutationClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

describe('useReconcileWorkflow (LIVE — story 4.23)', () => {
  it('success → ReconcileResponse; body carries role + decision + reason, Idempotency-Key header, no version stamps', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    let idempotencyKey: string | null = null;
    server.use(
      http.post(RECONCILE_URL, async ({ request }) => {
        idempotencyKey = request.headers.get('Idempotency-Key');
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Executing',
          recoveryActionId: 'rcv_1',
          replayed: false,
          resolvedConflictId: CONFLICT_ID,
        });
      }),
    );

    const { result } = renderHook(() => useReconcileWorkflow(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    let response;
    await act(async () => {
      response = await result.current.mutateAsync({
        conflictId: CONFLICT_ID,
        resolutionDecision: 'accept_external_state',
        reasonText: 'external merge is authoritative',
      });
    });

    expect(response).toMatchObject({ resolvedConflictId: CONFLICT_ID });
    expect(idempotencyKey).toBeTruthy();
    expect(receivedBody).toMatchObject({
      conflictId: CONFLICT_ID,
      resolutionDecision: 'accept_external_state',
      reasonText: 'external merge is authoritative',
      role: 'workflow_owner',
    });
    expect(receivedBody).not.toHaveProperty('actorIdentity');
    expect(receivedBody).not.toHaveProperty('actorType');
    expect(receivedBody).not.toHaveProperty('expectedArtifactVersion');
    expect(receivedBody).not.toHaveProperty('expectedContextBundleVersion');
  });

  it('invalidates the conflictId-keyed detail query on success (trap #7)', async () => {
    server.use(
      http.post(RECONCILE_URL, () =>
        HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Executing',
          recoveryActionId: 'rcv_1',
          replayed: false,
          resolvedConflictId: CONFLICT_ID,
        }),
      ),
    );
    const client = mutationClient();
    const spy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useReconcileWorkflow(RUN_ID), {
      wrapper: createWrapper(client),
    });
    await act(async () => {
      await result.current.mutateAsync({
        conflictId: CONFLICT_ID,
        resolutionDecision: 'accept_external_state',
        reasonText: 'x',
      });
    });

    const invalidatedKeys = spy.mock.calls.map((call) => JSON.stringify(call[0]?.queryKey));
    expect(invalidatedKeys).toContain(
      JSON.stringify(workflowKeys.integrationConflict(CONFLICT_ID)),
    );
    expect(invalidatedKeys).toContain(JSON.stringify(workflowKeys.detail(RUN_ID)));
  });

  it('CONFLICT_ALREADY_RESOLVED (409) → typed ProblemDetailsError', async () => {
    server.use(
      http.post(RECONCILE_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Already resolved',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/reconcile`,
            code: 'CONFLICT_ALREADY_RESOLVED',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useReconcileWorkflow(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current
        .mutateAsync({
          conflictId: CONFLICT_ID,
          resolutionDecision: 'accept_internal_state',
          reasonText: 'x',
        })
        .catch(() => undefined);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('CONFLICT_ALREADY_RESOLVED');
    }
  });
});
