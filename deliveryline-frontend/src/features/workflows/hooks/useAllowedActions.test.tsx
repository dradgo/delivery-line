/**
 * Story 2.19 (Task 2, AC5) — the `useAllowedActions` LIVE read test.
 *
 * MSW-backed: 200 → `{ actions, versionStamp }`; a problem+json error → typed
 * `ProblemDetailsError` state. This is the seam 2.18 T5 deferred to 2.19 — the hook
 * flipped from a disabled, throwing stub to a real `apiClient.GET`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { server } from '@/test/server';
import { useAllowedActions } from './useAllowedActions';

const ALLOWED_URL = 'http://localhost/api/v1/workflows/:runId/allowed-actions';
const RUN_ID = 'run_appr_demo_001';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function queryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe('useAllowedActions (LIVE — story 2.18 T5 → 2.19)', () => {
  it('200 → returns the actions list + version stamp', async () => {
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json({
          actions: ['approve_spec', 'reject_spec'],
          versionStamp: {
            currentSpecArtifactVersion: 3,
            currentContextBundleVersion: 1,
            lastEventId: 'evt_appr_100',
            workflowState: 'WaitingForSpecApproval',
          },
        }),
      ),
    );

    const { result } = renderHook(() => useAllowedActions(RUN_ID), {
      wrapper: createWrapper(queryClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.actions).toEqual(['approve_spec', 'reject_spec']);
    expect(result.current.data?.versionStamp.currentSpecArtifactVersion).toBe(3);
  });

  it('problem+json error → typed ProblemDetailsError state', async () => {
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Run not found',
            status: 404,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/allowed-actions`,
            code: 'RUN_NOT_FOUND',
            retryable: false,
          },
          { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useAllowedActions(RUN_ID), {
      wrapper: createWrapper(queryClient()),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('RUN_NOT_FOUND');
    }
  });
});
