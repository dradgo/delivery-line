/**
 * Story 3.28 (Task 2/Task 6) — the LIVE `useTakeoverWorkflow` mutation test.
 *
 * MSW-backed: success → the RICH `TakeoverResponse` (R9); the body carries ONLY `reasonText`
 * — NO version fields (R2), NO actor (R4). An `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400)
 * surfaces a typed `ProblemDetailsError`. The endpoint is `.../takeover` (op `takeover`),
 * NOT the older transition-only `/takeover-workflow`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useTakeoverWorkflow } from './useTakeoverWorkflow';

const TAKEOVER_URL = 'http://localhost/api/v1/workflows/:runId/takeover';
const RUN_ID = 'run_takeover_demo_001';

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

describe('useTakeoverWorkflow (LIVE — story 3.28)', () => {
  it('success → rich TakeoverResponse; body carries reasonText only (no versions, no actor)', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(TAKEOVER_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'TakenOver',
          recoveryActionId: 'rec_001',
          replayed: false,
          cancelledInFlightCount: 1,
          cancelledQueuedCount: 2,
          preservedPrReference: 'octo/repo#42',
          correlationId: 'corr_001',
        });
      }),
    );

    const { result } = renderHook(() => useTakeoverWorkflow(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    let response;
    await act(async () => {
      response = await result.current.mutateAsync({ reasonText: 'manual continuation' });
    });

    expect(response).toMatchObject({
      currentState: 'TakenOver',
      recoveryActionId: 'rec_001',
      preservedPrReference: 'octo/repo#42',
    });
    expect(receivedBody).toEqual({ reasonText: 'manual continuation' });
    expect(receivedBody).not.toHaveProperty('expectedArtifactVersion');
    expect(receivedBody).not.toHaveProperty('expectedContextBundleVersion');
    expect(receivedBody).not.toHaveProperty('actorIdentity');
    expect(receivedBody).not.toHaveProperty('reviewerRole');
  });

  it('INVALID_REVIEWER_ROLE_FOR_ENDPOINT (400) → typed ProblemDetailsError', async () => {
    server.use(
      http.post(TAKEOVER_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Invalid reviewer role',
            status: 400,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/takeover`,
            code: 'INVALID_REVIEWER_ROLE_FOR_ENDPOINT',
            retryable: false,
          },
          { status: 400, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useTakeoverWorkflow(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current.mutateAsync({ reasonText: 'x' }).catch(() => undefined);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('INVALID_REVIEWER_ROLE_FOR_ENDPOINT');
    }
  });
});
