import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useArchiveRun } from './useArchiveRun';

const ARCHIVE_URL = 'http://localhost/api/v1/workflows/:runId/archive';
const RUN_ID = 'run_archive_demo_001';

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

describe('useArchiveRun', () => {
  it('POSTs { reason } with an idempotency key; returns the ArchiveRun response', async () => {
    let body: Record<string, unknown> | undefined;
    let idem: string | null = null;
    server.use(
      http.post(ARCHIVE_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        idem = request.headers.get('idempotency-key');
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Failed',
          archivedAt: '2026-07-03T00:00:00Z',
          replayed: false,
        });
      }),
    );
    const { result } = renderHook(() => useArchiveRun(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    let response;
    await act(async () => {
      response = await result.current.mutateAsync({ reason: 'obsolete run' });
    });
    expect(response).toMatchObject({ archivedAt: '2026-07-03T00:00:00Z' });
    expect(body).toEqual({ reason: 'obsolete run' });
    expect(idem).toMatch(/[0-9a-f-]{36}/);
  });

  it('surfaces a typed ProblemDetailsError on ARCHIVE_NOT_APPLICABLE (409)', async () => {
    server.use(
      http.post(ARCHIVE_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Archive not applicable',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/archive`,
            code: 'ARCHIVE_NOT_APPLICABLE',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );
    const { result } = renderHook(() => useArchiveRun(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({ reason: 'x' }).catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('ARCHIVE_NOT_APPLICABLE');
    }
  });
});
