/**
 * Story 4.24 (AC6, Task 8) — the LIVE `useClassifyFailure` mutation test.
 *
 * MSW-backed: success → `ClassifyFailureResponse`; the body carries `role: "workflow_owner"` +
 * `taxonomyValue` (+ `reasonText` only when non-blank) and an `Idempotency-Key` header, and OMITS
 * actor fields (trap 5). A `DEPRECATED_TAXONOMY_VALUE` (400) surfaces a typed `ProblemDetailsError`
 * carrying `details.replacementValue`. Field-only logs (`recovery.classifySubmit` /
 * `recovery.classifyError`) NEVER carry `reasonText`. On success `detail(id)` is invalidated.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { server } from '@/test/server';
import { useClassifyFailure } from './useClassifyFailure';

const CLASSIFY_URL = 'http://localhost/api/v1/workflows/:runId/classify-failure';
const RUN_ID = 'run_classify_hook_001';

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
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

function successResponse() {
  return {
    workflowRunId: RUN_ID,
    taxonomyValue: 'agent_execution_failure',
    recoveryActionId: 'rcv_1',
    replayed: false,
  };
}

describe('useClassifyFailure (LIVE — story 4.24)', () => {
  beforeEach(() => {
    vi.spyOn(console, 'info').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('success → body carries role + taxonomyValue + reasonText + Idempotency-Key, no actor fields', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    let idempotencyKey: string | null = null;
    server.use(
      http.post(CLASSIFY_URL, async ({ request }) => {
        idempotencyKey = request.headers.get('Idempotency-Key');
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(successResponse());
      }),
    );

    const { result } = renderHook(() => useClassifyFailure(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({
        taxonomyValue: 'agent_execution_failure',
        reasonText: '  the runner produced malformed output  ',
      });
    });

    expect(idempotencyKey).toBeTruthy();
    expect(receivedBody).toMatchObject({
      role: 'workflow_owner',
      taxonomyValue: 'agent_execution_failure',
      // trimmed on the wire.
      reasonText: 'the runner produced malformed output',
    });
    expect(receivedBody).not.toHaveProperty('actorIdentity');
    expect(receivedBody).not.toHaveProperty('actorType');
  });

  it('omits reasonText from the body when blank', async () => {
    let receivedBody: Record<string, unknown> | undefined;
    server.use(
      http.post(CLASSIFY_URL, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(successResponse());
      }),
    );

    const { result } = renderHook(() => useClassifyFailure(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({ taxonomyValue: 'context_gap', reasonText: '   ' });
    });

    expect(receivedBody).not.toHaveProperty('reasonText');
    expect(receivedBody).toMatchObject({ role: 'workflow_owner', taxonomyValue: 'context_gap' });
  });

  it('logs recovery.classifySubmit with the wire value ONLY — never reasonText (T-LOG-PII)', async () => {
    const infoSpy = vi.spyOn(console, 'info');
    server.use(http.post(CLASSIFY_URL, () => HttpResponse.json(successResponse())));

    const { result } = renderHook(() => useClassifyFailure(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({
        taxonomyValue: 'agent_execution_failure',
        reasonText: 'super secret operator note',
      });
    });

    const submitCall = infoSpy.mock.calls.find((call) => call[0] === 'recovery.classifySubmit');
    expect(submitCall).toBeDefined();
    expect(submitCall?.[1]).toEqual({ taxonomyValue: 'agent_execution_failure' });
    // The reason text must never appear in any log argument.
    const serialized = JSON.stringify(infoSpy.mock.calls);
    expect(serialized).not.toContain('super secret operator note');
  });

  it('DEPRECATED_TAXONOMY_VALUE (400) → typed error + recovery.classifyError log (no PII)', async () => {
    const errorSpy = vi.spyOn(console, 'error');
    server.use(
      http.post(CLASSIFY_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Deprecated',
            status: 400,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/classify-failure`,
            code: 'DEPRECATED_TAXONOMY_VALUE',
            retryable: false,
            details: { replacementValue: 'agent_execution_failure' },
          },
          { status: 400, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useClassifyFailure(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current
        .mutateAsync({ taxonomyValue: 'legacy_value', reasonText: 'secret' })
        .catch(() => undefined);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('DEPRECATED_TAXONOMY_VALUE');
    }
    const errorCall = errorSpy.mock.calls.find((call) => call[0] === 'recovery.classifyError');
    expect(errorCall?.[1]).toEqual({ code: 'DEPRECATED_TAXONOMY_VALUE', transport: false });
    expect(JSON.stringify(errorSpy.mock.calls)).not.toContain('secret');
  });

  it('invalidates detail(id) on success', async () => {
    server.use(http.post(CLASSIFY_URL, () => HttpResponse.json(successResponse())));
    const queryClient = mutationClient();
    const spy = vi.spyOn(queryClient, 'invalidateQueries');

    const { result } = renderHook(() => useClassifyFailure(RUN_ID), {
      wrapper: createWrapper(queryClient),
    });
    await act(async () => {
      await result.current.mutateAsync({ taxonomyValue: 'context_gap' });
    });

    const invalidatedKeys = spy.mock.calls.map((call) => JSON.stringify(call[0]?.queryKey));
    expect(invalidatedKeys).toContain(JSON.stringify(workflowKeys.detail(RUN_ID)));
  });
});
