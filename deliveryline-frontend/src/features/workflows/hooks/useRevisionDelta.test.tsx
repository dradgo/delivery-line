/**
 * Story 4.20 (AC1) — `useRevisionDelta` over the live story-4.19 compare endpoint.
 *   • happy path: the generated `RevisionDelta` shape passes through unchanged;
 *   • the query is bound to the reserved `workflowKeys.revisionDelta(a, b)` key (off `all`);
 *   • the query is disabled (idle) until BOTH ids are present (OQ-2 unresolved-baseline path);
 *   • the live 4.19 error codes (404 / 503 / 400) surface as typed `ProblemDetailsError`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { server } from '@/test/server';
import { useRevisionDelta } from './useRevisionDelta';

const A = 'art_prior00000001';
const B = 'art_current0000001';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function problem(code: string, status: number) {
  return HttpResponse.json(
    {
      type: 'about:blank',
      title: code,
      status,
      detail: `${code} for the compare`,
      instance: `/api/v1/artifacts/${A}/compare/${B}`,
      code,
      retryable: status === 503,
    },
    { status, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
  );
}

describe('useRevisionDelta (live compare endpoint)', () => {
  it('returns the generated RevisionDelta shape on success', async () => {
    server.use(
      http.get('http://localhost/api/v1/artifacts/:artifactIdA/compare/:artifactIdB', () =>
        HttpResponse.json({
          artifactType: 'spec',
          revisionA: { version: 1, producedByActor: 'runner', createdAt: '2026-07-01T00:00:00Z' },
          revisionB: { version: 2, producedByActor: 'runner', createdAt: '2026-07-02T00:00:00Z' },
          summary: { changedRegionCount: 1, addedCount: 0, removedCount: 0, modifiedCount: 1 },
          noMeaningfulDiff: false,
          changes: [
            {
              blockType: 'markdown',
              changeKind: 'modified',
              sectionPath: 'Overview',
              priorText: 'old',
              currentText: 'new',
            },
          ],
          linkedDiffReferences: null,
        }),
      ),
    );

    const { result } = renderHook(() => useRevisionDelta(A, B), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.artifactType).toBe('spec');
    expect(result.current.data?.summary?.changedRegionCount).toBe(1);
    expect(result.current.data?.changes?.[0]?.blockType).toBe('markdown');
  });

  it('reserves the stable revisionDelta key off `all`, not detail(runId)', () => {
    expect(workflowKeys.revisionDelta(A, B)).toEqual(['workflows', 'revisionDelta', A, B]);
  });

  it('is disabled (idle, never fetches) until both ids are present', () => {
    const { result } = renderHook(() => useRevisionDelta('', B), {
      wrapper: createWrapper(freshClient()),
    });
    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });

  it.each([
    ['ARTIFACT_RECORD_NOT_FOUND', 404],
    ['ARTIFACT_PAYLOAD_UNAVAILABLE', 503],
    ['ARTIFACT_LINEAGE_MISMATCH', 400],
  ])('surfaces %s (%d) as a typed ProblemDetailsError', async (code, status) => {
    server.use(
      http.get('http://localhost/api/v1/artifacts/:artifactIdA/compare/:artifactIdB', () =>
        problem(code, status as number),
      ),
    );

    const { result } = renderHook(() => useRevisionDelta(A, B), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe(code);
      expect(error.status).toBe(status);
    }
  });
});
