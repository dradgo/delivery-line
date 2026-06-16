/**
 * Story 3.28 (Task 2/Task 6) — the LIVE `useRejectImplementation` mutation test.
 *
 * MSW-backed: success → `WorkflowStateChangeResponse`; the body carries `reasonText` + the
 * DEVELOPER `taggedFeedback` enum + the version ints, and OMITS actor + reviewerRole (R4).
 * An `INVALID_REJECTION_TAXONOMY` (400) surfaces a typed `ProblemDetailsError`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useRejectImplementation } from './useRejectImplementation';

const REJECT_URL = 'http://localhost/api/v1/workflows/:runId/reject-implementation';
const RUN_ID = 'run_reject_impl_demo_001';

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

describe('useRejectImplementation (LIVE — story 3.28)', () => {
  it('success; body carries reasonText + developer taggedFeedback + versions, omits actor', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(REJECT_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );

    const { result } = renderHook(() => useRejectImplementation(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    let response;
    await act(async () => {
      response = await result.current.mutateAsync({
        artifactId: 'art_impl_001',
        expectedArtifactVersion: 2,
        expectedContextBundleVersion: 1,
        reasonText: 'breaks the build',
        taggedFeedback: 'BREAKS_EXISTING_FUNCTIONALITY',
      });
    });

    expect(response).toMatchObject({ currentState: 'Executing' });
    expect(receivedBody).toMatchObject({
      artifactId: 'art_impl_001',
      expectedArtifactVersion: 2,
      expectedContextBundleVersion: 1,
      reasonText: 'breaks the build',
      taggedFeedback: 'BREAKS_EXISTING_FUNCTIONALITY',
    });
    expect(receivedBody).not.toHaveProperty('actorIdentity');
    expect(receivedBody).not.toHaveProperty('actorType');
    expect(receivedBody).not.toHaveProperty('reviewerRole');
  });

  it('INVALID_REJECTION_TAXONOMY (400) → typed ProblemDetailsError', async () => {
    server.use(
      http.post(REJECT_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Invalid rejection taxonomy',
            status: 400,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/reject-implementation`,
            code: 'INVALID_REJECTION_TAXONOMY',
            retryable: false,
          },
          { status: 400, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useRejectImplementation(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current
        .mutateAsync({
          artifactId: 'art_impl_001',
          expectedArtifactVersion: 2,
          expectedContextBundleVersion: 1,
          reasonText: 'x',
          taggedFeedback: 'QUALITY_ISSUE',
        })
        .catch(() => undefined);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('INVALID_REJECTION_TAXONOMY');
    }
  });
});
