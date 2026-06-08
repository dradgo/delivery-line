/**
 * Story 2a.1 (Task 6, AC4/AC7) — the LIVE `useSubmitWorkflow` mutation test.
 *
 * MSW-backed: happy submit asserts the request body + omitted optional field;
 * problem+json → typed `ProblemDetailsError`; and the idempotency-key lifecycle —
 * ONE key reused across an attempt's internal retry, the SAME key reused by a user
 * `retry()`, and a NEW key minted by a fresh `submit()` (AC7).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useSubmitWorkflow, type SubmitRunVariables } from './useSubmitWorkflow';

const SUBMIT_URL = 'http://localhost/api/v1/workflows/submit-workflow';

const VARIABLES: SubmitRunVariables = {
  linearTicketReference: 'LIN-2210',
  actorIdentity: 'alex@example.com',
  actorType: 'HUMAN',
};

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function clientWithRetry() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

function clientNoRetry() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

function problem(code: string, status: number, retryable: boolean) {
  return HttpResponse.json(
    {
      type: 'about:blank',
      title: code,
      status,
      detail: 'x',
      instance: '/api/v1/workflows/submit-workflow',
      code,
      retryable,
    },
    { status, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
  );
}

describe('useSubmitWorkflow', () => {
  it('happy submit → posts the body and OMITS a blank correlationId (AC4)', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(SUBMIT_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: 'run_new_001', currentState: 'Inbox' });
      }),
    );

    const { result } = renderHook(() => useSubmitWorkflow(), {
      wrapper: createWrapper(clientNoRetry()),
    });

    act(() => result.current.submit(VARIABLES));
    await waitFor(() => expect(result.current.status).toBe('success'));

    expect(result.current.data).toMatchObject({
      workflowRunId: 'run_new_001',
      currentState: 'Inbox',
    });
    expect(receivedBody).toEqual({
      linearTicketReference: 'LIN-2210',
      actorIdentity: 'alex@example.com',
      actorType: 'HUMAN',
    });
    expect(receivedBody).not.toHaveProperty('correlationId');
  });

  it('includes correlationId in the body when provided', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(SUBMIT_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: 'run_new_002', currentState: 'Inbox' });
      }),
    );

    const { result } = renderHook(() => useSubmitWorkflow(), {
      wrapper: createWrapper(clientNoRetry()),
    });

    act(() => result.current.submit({ ...VARIABLES, correlationId: 'corr-xyz' }));
    await waitFor(() => expect(result.current.status).toBe('success'));

    expect(receivedBody).toMatchObject({ correlationId: 'corr-xyz' });
  });

  it('problem+json failure → typed ProblemDetailsError with the domain code (AC7)', async () => {
    server.use(http.post(SUBMIT_URL, () => problem('LINEAR_TICKET_NOT_FOUND', 404, false)));

    const { result } = renderHook(() => useSubmitWorkflow(), {
      wrapper: createWrapper(clientNoRetry()),
    });

    act(() => result.current.submit(VARIABLES));
    await waitFor(() => expect(result.current.status).toBe('error'));

    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('LINEAR_TICKET_NOT_FOUND');
    }
  });

  it('AC7 — mints ONE idempotency key and reuses it across the attempt retry', async () => {
    const keys: (string | null)[] = [];
    let call = 0;
    server.use(
      http.post(SUBMIT_URL, ({ request }) => {
        call += 1;
        keys.push(request.headers.get('Idempotency-Key'));
        if (call === 1) {
          return problem('INTERNAL_ERROR', 503, true);
        }
        return HttpResponse.json({ workflowRunId: 'run_new_003', currentState: 'Inbox' });
      }),
    );

    const { result } = renderHook(() => useSubmitWorkflow(), {
      wrapper: createWrapper(clientWithRetry()),
    });

    act(() => result.current.submit(VARIABLES));
    await waitFor(() => expect(result.current.status).toBe('success'));

    expect(call).toBeGreaterThanOrEqual(2);
    expect(new Set(keys).size).toBe(1);
    expect(keys[0]).not.toBeNull();
  });

  it('AC7 — user retry() reuses the failed attempt key; a fresh submit() mints a new one', async () => {
    const keys: (string | null)[] = [];
    server.use(
      http.post(SUBMIT_URL, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'));
        // Always fail (non-retryable) so each call is exactly one attempt.
        return problem('LINEAR_TICKET_NOT_FOUND', 404, false);
      }),
    );

    const { result } = renderHook(() => useSubmitWorkflow(), {
      wrapper: createWrapper(clientNoRetry()),
    });

    // Fresh submit → key K1.
    act(() => result.current.submit(VARIABLES));
    await waitFor(() => expect(keys).toHaveLength(1));
    await waitFor(() => expect(result.current.status).toBe('error'));

    // Retry from the error surface → SAME key K1 reused.
    act(() => result.current.retry());
    await waitFor(() => expect(keys).toHaveLength(2));

    // Fresh submit again → NEW key K2.
    act(() => result.current.submit(VARIABLES));
    await waitFor(() => expect(keys).toHaveLength(3));

    expect(keys[0]).not.toBeNull();
    expect(keys[1]).toBe(keys[0]); // retry reuses
    expect(keys[2]).not.toBe(keys[0]); // fresh submit mints new
  });
});
