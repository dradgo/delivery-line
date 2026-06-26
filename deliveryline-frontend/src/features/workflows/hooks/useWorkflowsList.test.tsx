/**
 * Story 2.20 (AC1) — `useWorkflowsList` returns the typed `WorkflowSummary[]` off
 * the shared `listQueryOptions` cache, and surfaces a typed failure as `isError`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { server } from '@/test/server';
import { useWorkflowsList } from './useWorkflowsList';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe('useWorkflowsList', () => {
  it('returns the typed run summaries on success', async () => {
    server.use(
      http.get('http://localhost/api/v1/workflows', () =>
        HttpResponse.json([{ workflowRunId: 'run_abcd0001', currentState: 'Executing' }]),
      ),
    );

    const { result } = renderHook(() => useWorkflowsList(), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0]?.workflowRunId).toBe('run_abcd0001');
  });

  it('threads a non-empty projectId filter to the workflow list request', async () => {
    let seenProjectId: string | null = null;
    server.use(
      http.get('http://localhost/api/v1/workflows', ({ request }) => {
        seenProjectId = new URL(request.url).searchParams.get('projectId');
        return HttpResponse.json([]);
      }),
    );

    const { result } = renderHook(() => useWorkflowsList({ projectId: 'prj_alpha' }), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(seenProjectId).toBe('prj_alpha');
  });

  it('does not send an empty projectId query parameter', async () => {
    let hasProjectId = true;
    server.use(
      http.get('http://localhost/api/v1/workflows', ({ request }) => {
        hasProjectId = new URL(request.url).searchParams.has('projectId');
        return HttpResponse.json([]);
      }),
    );

    const { result } = renderHook(() => useWorkflowsList({ projectId: ' ' }), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(hasProjectId).toBe(false);
  });
  it('surfaces a server failure as isError', async () => {
    server.use(
      http.get(
        'http://localhost/api/v1/workflows',
        () =>
          new HttpResponse(
            JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', retryable: true }),
            {
              status: 500,
              headers: { 'content-type': 'application/problem+json' },
            },
          ),
      ),
    );

    const { result } = renderHook(() => useWorkflowsList(), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});
