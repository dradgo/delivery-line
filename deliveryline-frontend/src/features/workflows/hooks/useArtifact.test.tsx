/**
 * Story 3a-9 (Gate 3 / AC6, AC8) — `useArtifact` is now LIVE against the artifact-read
 * endpoint. This replaces the story-2.6/2.27 disabled-stub matrix:
 *   • happy path: the raw `ArtifactDetail` DTO is adapted into an `ArtifactView` that
 *     satisfies `isArtifactView` (artifactId injected from the arg, title composed) — D1;
 *   • the query stays bound to the reserved `workflowKeys.artifact(id)` key (AC3);
 *   • a typed `ARTIFACT_RECORD_NOT_FOUND` (404) surfaces as `ProblemDetailsError`;
 *   • the query is disabled (idle) until both ids are present.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { isArtifactView } from '@/features/workflows/artifactView';
import { server } from '@/test/server';
import { useArtifact } from './useArtifact';

const RUN = 'run_specread0001';
const ARTIFACT = 'art_specread0001';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe('useArtifact (live artifact-read)', () => {
  it('adapts the raw ArtifactDetail DTO into an ArtifactView that satisfies isArtifactView', async () => {
    server.use(
      http.get('http://localhost/api/v1/workflows/:runId/artifacts/:artifactId', () =>
        HttpResponse.json({
          artifactId: ARTIFACT,
          artifactType: 'spec',
          version: 3,
          status: 'available',
          classification: 'shareable-redacted',
          createdAt: '2026-06-14T09:00:00Z',
          checksum: 'sha-256:0123456789ab',
          body: '# Specification\n\nThe redacted spec body.\n',
        }),
      ),
    );

    const { result } = renderHook(() => useArtifact(RUN, ARTIFACT), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const artifact = result.current.data;
    expect(isArtifactView(artifact)).toBe(true);
    expect(artifact?.artifactId).toBe(ARTIFACT);
    expect(artifact?.artifactType).toBe('spec');
    expect(artifact?.version).toBe(3);
    // title is composed in the adapter (not on the wire) — D1.
    expect(artifact?.title).toBe('Specification — v3');
    expect(artifact?.body).toContain('The redacted spec body.');
  });

  it('injects artifactId from the arg even when the wire field is null (wire-null guard)', async () => {
    server.use(
      http.get('http://localhost/api/v1/workflows/:runId/artifacts/:artifactId', () =>
        HttpResponse.json({
          artifactId: null,
          artifactType: 'spec',
          version: 1,
          status: 'available',
          classification: 'shareable-redacted',
          createdAt: '2026-06-14T09:00:00Z',
          body: '# Spec\n',
        }),
      ),
    );

    const { result } = renderHook(() => useArtifact(RUN, ARTIFACT), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.artifactId).toBe(ARTIFACT);
    expect(isArtifactView(result.current.data)).toBe(true);
  });

  it('AC4 — ARTIFACT_RECORD_NOT_FOUND (404) surfaces a typed ProblemDetailsError', async () => {
    server.use(
      http.get('http://localhost/api/v1/workflows/:runId/artifacts/:artifactId', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Artifact record not found',
            status: 404,
            detail: 'Artifact record not found: art_specread0001',
            instance: `/api/v1/workflows/${RUN}/artifacts/${ARTIFACT}`,
            code: 'ARTIFACT_RECORD_NOT_FOUND',
            retryable: false,
          },
          { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    const { result } = renderHook(() => useArtifact(RUN, ARTIFACT), {
      wrapper: createWrapper(freshClient()),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('ARTIFACT_RECORD_NOT_FOUND');
    }
  });

  it('is disabled (idle, never fetches) until both ids are present', () => {
    // `onUnhandledRequest: 'error'` (setup.ts) fails the test if a request fires.
    const { result } = renderHook(() => useArtifact(RUN, ''), {
      wrapper: createWrapper(freshClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });

  it('reserves the stable one-arg workflowKeys.artifact contract', () => {
    expect(workflowKeys.artifact(ARTIFACT)).toEqual(['workflows', 'artifact', ARTIFACT]);
  });
});
