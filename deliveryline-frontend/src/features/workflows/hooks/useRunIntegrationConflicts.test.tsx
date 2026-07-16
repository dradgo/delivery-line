/**
 * Story 4.23 (Task 10, AC8) — the run-scoped unresolved-conflicts query + `resolveConflictId`.
 *
 * MSW-backed: fetches the run's unresolved conflicts (sends `resolved=false` + the run filter);
 * `resolveConflictId` picks the integration-matched conflict, else the newest.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { server } from '@/test/server';
import { resolveConflictId, useRunIntegrationConflicts } from './useRunIntegrationConflicts';

const LIST_URL = 'http://localhost/api/v1/integration-conflicts';
const RUN_ID = 'run_conflicts_001';

function createWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('useRunIntegrationConflicts (LIVE — story 4.23)', () => {
  it('fetches the run scoped unresolved conflicts (resolved=false + workflowRunId)', async () => {
    let sawResolved: string | null = null;
    let sawRun: string | null = null;
    server.use(
      http.get(LIST_URL, ({ request }) => {
        const url = new URL(request.url);
        sawResolved = url.searchParams.get('resolved');
        sawRun = url.searchParams.get('workflowRunId');
        return HttpResponse.json({
          conflicts: [{ conflictId: 'icf_a', integrationType: 'github_pr' }],
        });
      }),
    );
    const { result } = renderHook(() => useRunIntegrationConflicts(RUN_ID, { enabled: true }), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(sawResolved).toBe('false');
    expect(sawRun).toBe(RUN_ID);
    expect(result.current.data?.conflicts?.[0]?.conflictId).toBe('icf_a');
  });

  it('is disabled (no fetch) when enabled=false', () => {
    const { result } = renderHook(() => useRunIntegrationConflicts(RUN_ID, { enabled: false }), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('resolveConflictId', () => {
  const conflicts = [
    { conflictId: 'icf_newest', integrationType: 'github_pr' },
    { conflictId: 'icf_linear', integrationType: 'linear' },
  ];

  it('prefers the integration-matched conflict (loose match: github ≡ github_pr)', () => {
    expect(resolveConflictId(conflicts, 'github')).toBe('icf_newest');
    expect(resolveConflictId(conflicts, 'linear')).toBe('icf_linear');
  });

  it('falls back to the newest (first) when NO hint is given', () => {
    expect(resolveConflictId(conflicts)).toBe('icf_newest');
  });

  it('returns undefined when a hint is given but no conflict matches it (hard filter — no cross-integration fallback)', () => {
    // A drifted Bitbucket row whose run has only github/linear conflicts must NOT open the dialog on
    // an unrelated conflict — the caller hides the affordance instead.
    expect(resolveConflictId(conflicts, 'bitbucket')).toBeUndefined();
  });

  it('returns undefined when there is no unresolved conflict', () => {
    expect(resolveConflictId([])).toBeUndefined();
    expect(resolveConflictId(undefined)).toBeUndefined();
  });
});
