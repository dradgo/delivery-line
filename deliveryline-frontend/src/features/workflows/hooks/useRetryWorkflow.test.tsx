/**
 * Story 3.30 (Task 1, AC4/AC10) — the LIVE `useRetryWorkflow` mutation test.
 *
 * MSW-backed: success → `WorkflowStateChangeResponse`; the body carries the constant
 * `local-operator` / `HUMAN` actor (OQ-1) and omits blank optionals; a
 * `RETRY_NOT_APPLICABLE` (409, non-retryable) → typed `ProblemDetailsError`; AC10 is
 * verified by the hook compiling against the generated `retryWorkflow` operation
 * (the `-workflow` suffix). The idempotency-key reuse + invalidation invariants are
 * covered through the factory in `useWorkflowMutation.test.tsx`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useRetryWorkflow } from './useRetryWorkflow';

const RETRY_URL = 'http://localhost/api/v1/workflows/:runId/retry-workflow';
const RUN_ID = 'run_retry_demo_001';

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

describe('useRetryWorkflow (LIVE — story 3.30)', () => {
  it('success → WorkflowStateChangeResponse; body carries the constant local-operator/HUMAN actor (OQ-1)', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(RETRY_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );

    const { result } = renderHook(() => useRetryWorkflow(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    let response;
    await act(async () => {
      response = await result.current.mutateAsync({});
    });

    expect(response).toMatchObject({ currentState: 'Executing' });
    expect(receivedBody).toMatchObject({ actorIdentity: 'local-operator', actorType: 'HUMAN' });
    // Blank optionals are omitted (the optional-field spread).
    expect(receivedBody).not.toHaveProperty('reasonText');
    expect(receivedBody).not.toHaveProperty('correlationId');
  });

  it('forwards an optional reasonText when provided', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(RETRY_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );

    const { result } = renderHook(() => useRetryWorkflow(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current.mutateAsync({ reasonText: 'transient crash' });
    });

    expect(receivedBody).toMatchObject({ reasonText: 'transient crash' });
  });

  it('RETRY_NOT_APPLICABLE (409) → typed ProblemDetailsError', async () => {
    server.use(
      http.post(RETRY_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Retry not applicable',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/retry-workflow`,
            code: 'RETRY_NOT_APPLICABLE',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useRetryWorkflow(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current.mutateAsync({}).catch(() => undefined);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('RETRY_NOT_APPLICABLE');
    }
  });
});
