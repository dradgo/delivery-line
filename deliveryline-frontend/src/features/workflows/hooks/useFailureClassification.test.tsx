/**
 * Story 4.24 (AC2/AC5/AC9, Task 8) — the LIVE `useFailureClassification` query test.
 *
 * MSW-backed: a never-classified run → 200 with `currentTaxonomyValue` absent + `priorClassifications:
 * []`; a classified run → the current triple + ordered priors. An error warns
 * `recovery.classificationLoadError` (code + transport, no PII).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { server } from '@/test/server';
import { useFailureClassification } from './useFailureClassification';

const RUN_ID = 'run_classification_hook_001';
const URL = `http://localhost/api/v1/workflows/${RUN_ID}/failure-classification`;

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

describe('useFailureClassification (LIVE — story 4.24)', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('never-classified run → 200 with null current + empty priors', async () => {
    server.use(
      http.get(URL, () =>
        HttpResponse.json({ workflowRunId: RUN_ID, deprecated: false, priorClassifications: [] }),
      ),
    );

    const { result } = renderHook(() => useFailureClassification(RUN_ID), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.currentTaxonomyValue).toBeUndefined();
    expect(result.current.data?.priorClassifications).toEqual([]);
  });

  it('classified run → current triple + ordered priors', async () => {
    server.use(
      http.get(URL, () =>
        HttpResponse.json({
          workflowRunId: RUN_ID,
          currentTaxonomyValue: 'agent_execution_failure',
          currentDisplayLabel: 'agent_execution_failure',
          deprecated: false,
          classifiedBy: 'alex',
          priorClassifications: [
            {
              taxonomyValue: 'context_gap',
              displayLabel: 'context_gap',
              classifiedAt: '2026-07-16T09:00:00Z',
              classifiedBy: 'alex',
            },
          ],
        }),
      ),
    );

    const { result } = renderHook(() => useFailureClassification(RUN_ID), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.currentTaxonomyValue).toBe('agent_execution_failure');
    expect(result.current.data?.priorClassifications).toHaveLength(1);
    expect(result.current.data?.priorClassifications[0]?.taxonomyValue).toBe('context_gap');
  });

  it('error → recovery.classificationLoadError warn (code + transport)', async () => {
    const warnSpy = vi.spyOn(console, 'warn');
    server.use(
      http.get(URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Not found',
            status: 404,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/failure-classification`,
            code: 'RUN_NOT_FOUND',
            retryable: false,
          },
          { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useFailureClassification(RUN_ID), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const call = warnSpy.mock.calls.find((c) => c[0] === 'recovery.classificationLoadError');
    expect(call?.[1]).toEqual({ code: 'RUN_NOT_FOUND', transport: false });
  });
});
