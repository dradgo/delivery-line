/**
 * Story 4.24 (AC1, Task 8) — the LIVE `useFailureTaxonomy` registry query test.
 *
 * MSW-backed: success → `TaxonomyValue[]`; error → `recovery.taxonomyLoadError` field-only warn
 * (stable code + transport flag, no PII) + `isError`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { server } from '@/test/server';
import { useFailureTaxonomy } from './useFailureTaxonomy';

const TAXONOMY_URL = 'http://localhost/api/v1/registries/failure-taxonomy';

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

describe('useFailureTaxonomy (LIVE — story 4.24)', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('success → returns the taxonomy values array', async () => {
    server.use(
      http.get(TAXONOMY_URL, () =>
        HttpResponse.json({
          values: [
            {
              value: 'agent_execution_failure',
              humanReadableName: 'Agent Execution Failure',
              description: 'The agent failed.',
              examples: ['malformed output'],
              deprecated: false,
            },
          ],
        }),
      ),
    );

    const { result } = renderHook(() => useFailureTaxonomy(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0]?.value).toBe('agent_execution_failure');
  });

  it('error → recovery.taxonomyLoadError warn (code + transport, no PII)', async () => {
    const warnSpy = vi.spyOn(console, 'warn');
    server.use(
      http.get(TAXONOMY_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Boom',
            status: 500,
            detail: 'x',
            instance: '/api/v1/registries/failure-taxonomy',
            code: 'INTERNAL_ERROR',
            retryable: false,
          },
          { status: 500, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useFailureTaxonomy(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const call = warnSpy.mock.calls.find((c) => c[0] === 'recovery.taxonomyLoadError');
    expect(call?.[1]).toEqual({ code: 'INTERNAL_ERROR', transport: false });
  });

  it('does not fetch when disabled', async () => {
    let called = false;
    server.use(
      http.get(TAXONOMY_URL, () => {
        called = true;
        return HttpResponse.json({ values: [] });
      }),
    );

    renderHook(() => useFailureTaxonomy({ enabled: false }), { wrapper: createWrapper() });
    // Give any accidental fetch a tick to fire.
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(called).toBe(false);
  });
});
