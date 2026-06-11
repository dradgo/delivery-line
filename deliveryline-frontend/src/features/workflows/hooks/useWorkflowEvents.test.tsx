/**
 * Story 2.27 (Task 4, AC6) — `useWorkflowEvents` coverage (the one live read hook
 * that shipped without a test). Mirrors the `useWorkflowDetail` / `useAllowedActions`
 * query pattern: success returns the typed `WorkflowEventsResponse` with no runtime
 * cast (seeded from the story-1.23 happy-path fixture stream — AC3 realistic data),
 * and a `RUN_NOT_FOUND` problem+json surfaces as a typed `ProblemDetailsError` with a
 * stable `error.code`. Read-only + idempotent → invalidation/idempotency (AC6 c/d)
 * do not apply.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { server } from '@/test/server';
import { happyPathStream } from '@/test/fixtures/event-streams';
import { useWorkflowEvents } from './useWorkflowEvents';

const EVENTS_URL = 'http://localhost/api/v1/workflows/:id/events';
const RUN_ID = 'run_fix_happy_001';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe('useWorkflowEvents', () => {
  it('success → typed WorkflowEventsResponse (1.23 happy-path stream, no cast)', async () => {
    server.use(http.get(EVENTS_URL, () => HttpResponse.json(happyPathStream)));

    const { result } = renderHook(() => useWorkflowEvents(RUN_ID), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // Typed access — `workflowRun`/`events` are non-optional on WorkflowEventsResponse.
    expect(result.current.data?.workflowRun.publicId).toBe(RUN_ID);
    expect(result.current.data?.events[0]?.eventType).toBe('workflow.stateChanged');
    expect(result.current.data?.events.at(-1)?.resultingState).toBe('Completed');
  });

  it('RUN_NOT_FOUND (404) → typed ProblemDetailsError with stable code', async () => {
    server.use(
      http.get(EVENTS_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Workflow run not found',
            status: 404,
            detail: 'Workflow run not found: run_missing0001',
            instance: '/api/v1/workflows/run_missing0001/events',
            code: 'RUN_NOT_FOUND',
            retryable: false,
          },
          { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useWorkflowEvents('run_missing0001'), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('RUN_NOT_FOUND');
      expect(error.retryable).toBe(false);
    }
  });
});
