/**
 * Story 2.19 (Task 2, AC5/AC6) — the LIVE `useApproveSpec` mutation test.
 *
 * MSW-backed: success → `WorkflowStateChangeResponse`; `APPROVAL_VERSION_MISMATCH`
 * (409, non-retryable) → typed `ProblemDetailsError`; the body carries both expected
 * version ints (AC6 / T-VERSIONSTAMP). The factory invariants (idempotency-key reuse,
 * invalidation) are covered through this hook in `useWorkflowMutation.test.tsx`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useApproveSpec } from './useApproveSpec';

const APPROVE_URL = 'http://localhost/api/v1/workflows/:runId/approve-spec';
const RUN_ID = 'run_appr_demo_001';

const VARIABLES = {
  artifactId: 'art_spec_appr_001',
  expectedArtifactVersion: 3,
  expectedContextBundleVersion: 1,
};

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

describe('useApproveSpec (LIVE — relocated from the factory scaffold, OQ-2)', () => {
  it('success → WorkflowStateChangeResponse; body carries both expected version ints (AC6)', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(APPROVE_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );

    const { result } = renderHook(() => useApproveSpec(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    let response;
    await act(async () => {
      response = await result.current.mutateAsync(VARIABLES);
    });

    expect(response).toMatchObject({ currentState: 'Executing' });
    expect(receivedBody).toMatchObject({
      artifactId: 'art_spec_appr_001',
      expectedArtifactVersion: 3,
      expectedContextBundleVersion: 1,
    });
  });

  it('APPROVAL_VERSION_MISMATCH (409) → typed ProblemDetailsError', async () => {
    server.use(
      http.post(APPROVE_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Version mismatch',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/approve-spec`,
            code: 'APPROVAL_VERSION_MISMATCH',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useApproveSpec(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current.mutateAsync(VARIABLES).catch(() => undefined);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('APPROVAL_VERSION_MISMATCH');
    }
  });

  it('AC7 — mints ONE idempotency key and reuses it across the attempt retry', async () => {
    const keys: (string | null)[] = [];
    let call = 0;
    server.use(
      http.post(APPROVE_URL, ({ request }) => {
        call += 1;
        keys.push(request.headers.get('Idempotency-Key'));
        if (call === 1) {
          return HttpResponse.json(
            {
              type: 'about:blank',
              title: 'Transient',
              status: 503,
              detail: 'try again',
              instance: `/api/v1/workflows/${RUN_ID}/approve-spec`,
              code: 'INTERNAL_ERROR',
              retryable: true,
            },
            { status: 503, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
          );
        }
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );

    const { result } = renderHook(() => useApproveSpec(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });

    await act(async () => {
      await result.current.mutateAsync(VARIABLES);
    });

    expect(call).toBeGreaterThanOrEqual(2);
    expect(new Set(keys).size).toBe(1);
    expect(keys[0]).not.toBeNull();
  });
});
