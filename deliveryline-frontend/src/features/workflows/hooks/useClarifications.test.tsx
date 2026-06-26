/**
 * `useClarifications` LIVE read test — the clarification-read endpoint shipped, so the
 * hook flipped from a disabled, throwing stub (story 2.18/2.27 SEAM) to a real
 * `apiClient.GET`. MSW-backed: 200 → `{ clarifications: [...] }`; a problem+json error →
 * typed `ProblemDetailsError` state. Also pins the reserved key as a prefix-child of
 * `detail(id)` so a spec mutation's invalidation cascade still refreshes it for free.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { server } from '@/test/server';
import { useClarifications } from './useClarifications';

const CLARIFICATIONS_URL = 'http://localhost/api/v1/workflows/:runId/clarifications';
const RUN_ID = 'run_abcd0001';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function queryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe('useClarifications (LIVE — clarification-read endpoint)', () => {
  it('200 → returns the clarifications envelope', async () => {
    server.use(
      http.get(CLARIFICATIONS_URL, () =>
        HttpResponse.json({
          clarifications: [
            {
              clarificationId: 'clr_open1',
              workflowRunId: RUN_ID,
              artifactId: 'art_spec1',
              artifactVersion: 1,
              questionId: 'Q-001',
              questionText: 'Should this only add REST endpoints?',
              status: 'open',
              createdAt: '2026-06-25T20:09:00Z',
            },
          ],
        }),
      ),
    );

    const { result } = renderHook(() => useClarifications(RUN_ID), {
      wrapper: createWrapper(queryClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.clarifications).toHaveLength(1);
    expect(result.current.data?.clarifications[0]?.status).toBe('open');
    expect(result.current.data?.clarifications[0]?.questionId).toBe('Q-001');
  });

  it('problem+json error → typed ProblemDetailsError state', async () => {
    server.use(
      http.get(CLARIFICATIONS_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Run not found',
            status: 404,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/clarifications`,
            code: 'RUN_NOT_FOUND',
            retryable: false,
          },
          { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useClarifications(RUN_ID), {
      wrapper: createWrapper(queryClient()),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('RUN_NOT_FOUND');
    }
  });

  it('reserves a key that is a prefix child of detail(id) (invalidation cascade)', () => {
    expect(workflowKeys.clarifications(RUN_ID)).toEqual([
      'workflows',
      'detail',
      RUN_ID,
      'clarifications',
    ]);
    expect(workflowKeys.clarifications(RUN_ID).slice(0, 3)).toEqual(workflowKeys.detail(RUN_ID));
  });
});
