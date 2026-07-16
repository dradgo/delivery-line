/**
 * Story 4.23 (Task 1, AC1/AC6) — the LIVE `useIntegrationConflict` detail-query test.
 *
 * MSW-backed: fetches `IntegrationConflictDetail`; is disabled (no request) while closed / with a
 * blank id; a `CONFLICT_NOT_FOUND` (404) surfaces a typed `ProblemDetailsError`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { server } from '@/test/server';
import { useIntegrationConflict } from './useIntegrationConflict';

const CONFLICT_URL = 'http://localhost/api/v1/integration-conflicts/:conflictId';
const CONFLICT_ID = 'icf_read_001';

function createWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('useIntegrationConflict (LIVE — story 4.23)', () => {
  it('fetches the conflict detail when open + id set', async () => {
    server.use(
      http.get(CONFLICT_URL, () =>
        HttpResponse.json({
          conflictId: CONFLICT_ID,
          conflictCategory: 'external_state_advanced',
          integrationType: 'github_pr',
          suggestedDecisions: [{ decision: 'accept_external_state', safety: 'safe' }],
        }),
      ),
    );
    const { result } = renderHook(() => useIntegrationConflict(CONFLICT_ID, { enabled: true }), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.conflictId).toBe(CONFLICT_ID);
  });

  it('is disabled (no fetch) while closed or with a blank id', () => {
    // No handler registered — onUnhandledRequest:'error' would throw if a request fired.
    const closed = renderHook(() => useIntegrationConflict(CONFLICT_ID, { enabled: false }), {
      wrapper: createWrapper(),
    });
    expect(closed.result.current.fetchStatus).toBe('idle');
    const blank = renderHook(() => useIntegrationConflict('', { enabled: true }), {
      wrapper: createWrapper(),
    });
    expect(blank.result.current.fetchStatus).toBe('idle');
  });

  it('CONFLICT_NOT_FOUND (404) → typed ProblemDetailsError', async () => {
    server.use(
      http.get(CONFLICT_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Not found',
            status: 404,
            detail: 'x',
            instance: `/api/v1/integration-conflicts/${CONFLICT_ID}`,
            code: 'CONFLICT_NOT_FOUND',
            retryable: false,
          },
          { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );
    const { result } = renderHook(() => useIntegrationConflict(CONFLICT_ID, { enabled: true }), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(isProblemDetailsError(result.current.error)).toBe(true);
  });
});
