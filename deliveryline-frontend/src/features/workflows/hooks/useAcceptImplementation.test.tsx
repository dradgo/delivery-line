/**
 * Story 3.28 (Task 2/Task 6) — the LIVE `useAcceptImplementation` mutation test.
 *
 * MSW-backed: success → `WorkflowStateChangeResponse`; the body carries `artifactId` +
 * the two version ints and OMITS actor + reviewerRole (R4 — header-derived). An
 * `APPROVAL_VERSION_MISMATCH` (409) surfaces a typed `ProblemDetailsError`. Idempotency-key
 * reuse + invalidation are covered through the factory in `useWorkflowMutation.test.tsx`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useAcceptImplementation } from './useAcceptImplementation';

const ACCEPT_URL = 'http://localhost/api/v1/workflows/:runId/accept-implementation';
const RUN_ID = 'run_accept_demo_001';

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

describe('useAcceptImplementation (LIVE — story 3.28)', () => {
  it('success → WorkflowStateChangeResponse; body carries versions, omits actor + reviewerRole', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(ACCEPT_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );

    const { result } = renderHook(() => useAcceptImplementation(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    let response;
    await act(async () => {
      response = await result.current.mutateAsync({
        artifactId: 'art_impl_001',
        expectedArtifactVersion: 2,
        expectedContextBundleVersion: 1,
      });
    });

    expect(response).toMatchObject({ currentState: 'Executing' });
    expect(receivedBody).toMatchObject({
      artifactId: 'art_impl_001',
      expectedArtifactVersion: 2,
      expectedContextBundleVersion: 1,
    });
    expect(receivedBody).not.toHaveProperty('actorIdentity');
    expect(receivedBody).not.toHaveProperty('actorType');
    expect(receivedBody).not.toHaveProperty('reviewerRole');
    expect(receivedBody).not.toHaveProperty('reason');
  });

  it('forwards an optional reason when provided', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(ACCEPT_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );

    const { result } = renderHook(() => useAcceptImplementation(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current.mutateAsync({
        artifactId: 'art_impl_001',
        expectedArtifactVersion: 2,
        expectedContextBundleVersion: 1,
        reason: 'looks good',
      });
    });

    expect(receivedBody).toMatchObject({ reason: 'looks good' });
  });

  it('APPROVAL_VERSION_MISMATCH (409) → typed ProblemDetailsError', async () => {
    server.use(
      http.post(ACCEPT_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Version mismatch',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/accept-implementation`,
            code: 'APPROVAL_VERSION_MISMATCH',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useAcceptImplementation(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current
        .mutateAsync({
          artifactId: 'art_impl_001',
          expectedArtifactVersion: 2,
          expectedContextBundleVersion: 1,
        })
        .catch(() => undefined);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('APPROVAL_VERSION_MISMATCH');
    }
  });
});
