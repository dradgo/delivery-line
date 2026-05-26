/**
 * Story 2.6 (AC6 invalidation, AC7 idempotency-key reuse across retries, Task 7d).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { server } from '@/test/server';
import { useApproveSpec } from './useWorkflowMutation';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function mutationClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      // Real retry policy, but no backoff delay so the test is fast.
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

const APPROVE_BODY = {
  artifactId: 'art_abcd',
  expectedArtifactVersion: 1,
  expectedContextBundleVersion: 1,
};

describe('useApproveSpec / useWorkflowMutation', () => {
  it('AC7 — reuses ONE idempotency key across retries of the same attempt', async () => {
    const keys: (string | null)[] = [];
    let call = 0;
    server.use(
      http.post('http://localhost/api/v1/workflows/:id/approve-spec', ({ request }) => {
        call += 1;
        keys.push(request.headers.get('Idempotency-Key'));
        if (call === 1) {
          // Retryable domain error → the mutation retry policy re-attempts it.
          return HttpResponse.json(
            {
              type: 'about:blank',
              title: 'Transient',
              status: 503,
              detail: 'try again',
              instance: '/api/v1/workflows/run_abcd/approve-spec',
              code: 'INTERNAL_ERROR',
              retryable: true,
            },
            { status: 503, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
          );
        }
        return HttpResponse.json({ workflowRunId: 'run_abcd', currentState: 'Executing' });
      }),
    );

    const { result } = renderHook(() => useApproveSpec('run_abcd'), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current.mutateAsync(APPROVE_BODY);
    });

    expect(call).toBeGreaterThanOrEqual(2);
    expect(keys.length).toBeGreaterThanOrEqual(2);
    // The whole point: every retry of one attempt carried the SAME key.
    expect(new Set(keys).size).toBe(1);
    expect(keys[0]).not.toBeNull();
  });

  it('AC6 — invalidates the run detail (prefix) and the run-queue list on success', async () => {
    server.use(
      http.post('http://localhost/api/v1/workflows/:id/approve-spec', () =>
        HttpResponse.json({ workflowRunId: 'run_abcd', currentState: 'Executing' }),
      ),
    );

    const client = mutationClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useApproveSpec('run_abcd'), {
      wrapper: createWrapper(client),
    });

    await act(async () => {
      await result.current.mutateAsync(APPROVE_BODY);
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: workflowKeys.detail('run_abcd') });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: workflowKeys.lists() });
  });

  it('isolates overlapping mutation attempts so each keeps its own retry key', async () => {
    // Story 2.13: actor identity moved from request body to the X-Actor-Identity header.
    // Distinguish overlapping attempts by their distinct artifactId payloads instead.
    const keysByArtifact = new Map<string, string[]>();
    const attemptsByArtifact = new Map<string, number>();

    server.use(
      http.post('http://localhost/api/v1/workflows/:id/approve-spec', async ({ request }) => {
        const body = (await request.json()) as typeof APPROVE_BODY;
        const artifactId = body.artifactId;
        const attempts = (attemptsByArtifact.get(artifactId) ?? 0) + 1;
        attemptsByArtifact.set(artifactId, attempts);

        const idempotencyKey = request.headers.get('Idempotency-Key');
        keysByArtifact.set(artifactId, [
          ...(keysByArtifact.get(artifactId) ?? []),
          idempotencyKey ?? '',
        ]);

        if (artifactId === 'art_first' && attempts === 1) {
          return HttpResponse.json(
            {
              type: 'about:blank',
              title: 'Transient',
              status: 503,
              detail: 'try again',
              instance: '/api/v1/workflows/run_abcd/approve-spec',
              code: 'INTERNAL_ERROR',
              retryable: true,
            },
            { status: 503, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
          );
        }

        return HttpResponse.json({ workflowRunId: 'run_abcd', currentState: 'Executing' });
      }),
    );

    const { result } = renderHook(() => useApproveSpec('run_abcd'), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await Promise.all([
        result.current.mutateAsync({ ...APPROVE_BODY, artifactId: 'art_first' }),
        result.current.mutateAsync({ ...APPROVE_BODY, artifactId: 'art_second' }),
      ]);
    });

    const firstKeys = keysByArtifact.get('art_first') ?? [];
    const secondKeys = keysByArtifact.get('art_second') ?? [];

    expect(firstKeys).toHaveLength(2);
    expect(Array.from(new Set(firstKeys))).toHaveLength(1);
    expect(secondKeys).toHaveLength(1);
    expect(Array.from(new Set(secondKeys))).toHaveLength(1);
    expect(firstKeys[0]).not.toBe(secondKeys[0]);
  });
});
