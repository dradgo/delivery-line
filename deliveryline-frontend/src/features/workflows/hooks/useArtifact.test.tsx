/**
 * Story 2.27 (Task 4, AC6) — `useArtifact` stays an inert DISABLED STUB until the
 * artifact-read endpoint ships (story 2.6 SEAM). AC6 names this hook, so its
 * contract is pinned: it binds the reserved `workflowKeys.artifact(id)` key, never
 * fires the throwing placeholder `queryFn` (so a forgotten consumer can't silently
 * hit a non-existent endpoint), and exposes no data. When the endpoint lands, this
 * test flips to the success/error matrix (mirror `useAllowedActions.test.tsx`).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { useArtifact } from './useArtifact';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('useArtifact (disabled stub — artifact-read SEAM)', () => {
  it('binds the reserved key and never fetches (queryFn never runs)', () => {
    // `onUnhandledRequest: 'error'` (setup.ts) would fail this test if the disabled
    // query ever fired the placeholder queryFn against the network.
    const { result } = renderHook(() => useArtifact('art_sample0001'), {
      wrapper: createWrapper(new QueryClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.isFetching).toBe(false);
    expect(result.current.data).toBeUndefined();
  });

  it('reserves the stable workflowKeys.artifact contract', () => {
    expect(workflowKeys.artifact('art_sample0001')).toEqual([
      'workflows',
      'artifact',
      'art_sample0001',
    ]);
  });
});
