import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useUnarchiveRun } from './useUnarchiveRun';

const UNARCHIVE_URL = 'http://localhost/api/v1/workflows/:runId/unarchive';
const RUN_ID = 'run_unarchive_demo_001';

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

describe('useUnarchiveRun', () => {
  it('sends { reason } when provided', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post(UNARCHIVE_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Failed',
          archivedAt: null,
        });
      }),
    );
    const { result } = renderHook(() => useUnarchiveRun(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({ reason: 'still needed' });
    });
    expect(body).toEqual({ reason: 'still needed' });
  });

  it('omits reason from the body when blank/undefined', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post(UNARCHIVE_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Failed',
          archivedAt: null,
        });
      }),
    );
    const { result } = renderHook(() => useUnarchiveRun(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({ reason: '   ' });
    });
    expect(body).toEqual({});
  });

  it('trims surrounding whitespace from a provided reason before sending', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post(UNARCHIVE_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Failed',
          archivedAt: null,
        });
      }),
    );
    const { result } = renderHook(() => useUnarchiveRun(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({ reason: '  keep  ' });
    });
    expect(body).toEqual({ reason: 'keep' });
  });
});
