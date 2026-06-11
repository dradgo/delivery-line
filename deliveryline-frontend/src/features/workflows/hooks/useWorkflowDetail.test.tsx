/**
 * Story 2.6 (AC8 typed data, AC10 dedup/structural-sharing, Task 7c/7e).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { server } from '@/test/server';
import { useWorkflowDetail } from './useWorkflowDetail';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe('useWorkflowDetail', () => {
  it('returns the generated WorkflowDetail shape (no runtime cast)', async () => {
    server.use(
      http.get('http://localhost/api/v1/workflows/:id', () =>
        HttpResponse.json({ workflowRunId: 'run_abcd', currentState: 'WaitingForSpecApproval' }),
      ),
    );

    const { result } = renderHook(() => useWorkflowDetail('run_abcd'), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // Typed access — `currentState` is a typed optional string on WorkflowDetail.
    expect(result.current.data?.currentState).toBe('WaitingForSpecApproval');
    expect(result.current.data?.workflowRunId).toBe('run_abcd');
  });

  it('AC10 — two consumers of the SAME key trigger ONE fetch (request dedup)', async () => {
    let calls = 0;
    server.use(
      http.get('http://localhost/api/v1/workflows/:id', () => {
        calls += 1;
        return HttpResponse.json({ workflowRunId: 'run_abcd', currentState: 'Executing' });
      }),
    );

    // Two hooks for the same run id under one client — the Context Strip + ARP case.
    const { result } = renderHook(
      () => ({
        a: useWorkflowDetail('run_abcd'),
        b: useWorkflowDetail('run_abcd'),
      }),
      { wrapper: createWrapper(freshClient()) },
    );

    await waitFor(() => {
      expect(result.current.a.isSuccess).toBe(true);
      expect(result.current.b.isSuccess).toBe(true);
    });

    expect(calls).toBe(1);
    // Structural sharing — both consumers see the same object reference.
    expect(result.current.a.data).toBe(result.current.b.data);
  });

  it('AC6 — RUN_NOT_FOUND (404) surfaces a typed ProblemDetailsError with stable code', async () => {
    server.use(
      http.get('http://localhost/api/v1/workflows/:id', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Workflow run not found',
            status: 404,
            detail: 'Workflow run not found: run_missing0001',
            instance: '/api/v1/workflows/run_missing0001',
            code: 'RUN_NOT_FOUND',
            retryable: false,
          },
          { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useWorkflowDetail('run_missing0001'), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('RUN_NOT_FOUND');
    }
  });
});
