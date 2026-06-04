/**
 * Story 2.18 (Task 2, AC5/AC9) — the LIVE `useSubmitClarification` mutation.
 *
 * MSW-backed: success → `ClarificationAnswerResponse`; version-mismatch → typed
 * `ProblemDetailsError` state; one idempotency key minted + reused across the retry
 * of a single attempt (AC7); `detail(runId)` invalidation on success (AC6).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { server } from '@/test/server';
import { useSubmitClarification } from './useSubmitClarification';

const ANSWER_URL =
  'http://localhost/api/v1/workflows/:runId/clarifications/:clarificationId/answer';
const RUN_ID = 'run_clr_demo_001';

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

const VARIABLES = {
  clarificationId: 'cla_open0001',
  answerText: 'Use UTC for all scheduled runs.',
  artifactId: 'art_spec_clr_001',
  expectedArtifactVersion: 3,
};

function problem(code: string, status: number, retryable: boolean) {
  return HttpResponse.json(
    {
      type: 'about:blank',
      title: code,
      status,
      detail: 'x',
      instance: `/api/v1/workflows/${RUN_ID}/clarifications/cla_open0001/answer`,
      code,
      retryable,
    },
    { status, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
  );
}

describe('useSubmitClarification', () => {
  it('success → returns the ClarificationAnswerResponse (run state unchanged — T9)', async () => {
    server.use(
      http.post(ANSWER_URL, () =>
        HttpResponse.json({
          clarificationId: 'cla_open0001',
          clarificationStatus: 'answered',
          currentState: 'WaitingForSpecApproval',
          workflowRunId: RUN_ID,
        }),
      ),
    );

    const { result } = renderHook(() => useSubmitClarification(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    let response;
    await act(async () => {
      response = await result.current.mutateAsync(VARIABLES);
    });

    expect(response).toMatchObject({
      clarificationStatus: 'answered',
      currentState: 'WaitingForSpecApproval',
    });
  });

  it('version-mismatch → typed ProblemDetailsError (retryable code) state', async () => {
    // Always return the retryable mismatch so the (retry-enabled) client exhausts to error.
    server.use(
      http.post(ANSWER_URL, () => problem('CLARIFICATION_ARTIFACT_VERSION_MISMATCH', 409, true)),
    );

    const { result } = renderHook(() => useSubmitClarification(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current.mutateAsync(VARIABLES).catch(() => undefined);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('CLARIFICATION_ARTIFACT_VERSION_MISMATCH');
      expect(error.retryable).toBe(true);
    }
  });

  it('AC7 — mints ONE idempotency key and reuses it across the attempt retry', async () => {
    const keys: (string | null)[] = [];
    let call = 0;
    server.use(
      http.post(ANSWER_URL, ({ request }) => {
        call += 1;
        keys.push(request.headers.get('Idempotency-Key'));
        if (call === 1) {
          return problem('INTERNAL_ERROR', 503, true);
        }
        return HttpResponse.json({
          clarificationId: 'cla_open0001',
          clarificationStatus: 'answered',
          currentState: 'WaitingForSpecApproval',
          workflowRunId: RUN_ID,
        });
      }),
    );

    const { result } = renderHook(() => useSubmitClarification(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current.mutateAsync(VARIABLES);
    });

    expect(call).toBeGreaterThanOrEqual(2);
    expect(new Set(keys).size).toBe(1);
    expect(keys[0]).not.toBeNull();
  });

  it('AC6 — invalidates the run detail (prefix) on success', async () => {
    server.use(
      http.post(ANSWER_URL, () =>
        HttpResponse.json({
          clarificationId: 'cla_open0001',
          clarificationStatus: 'answered',
          currentState: 'WaitingForSpecApproval',
          workflowRunId: RUN_ID,
        }),
      ),
    );

    const client = mutationClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useSubmitClarification(RUN_ID), {
      wrapper: createWrapper(client),
    });

    await act(async () => {
      await result.current.mutateAsync(VARIABLES);
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: workflowKeys.detail(RUN_ID) });
  });
});
