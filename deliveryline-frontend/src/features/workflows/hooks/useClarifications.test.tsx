/**
 * Story 2.27 (Task 4, AC6) — `useClarifications` stays an inert DISABLED STUB until
 * the clarification-read endpoint ships (story 2.18 SEAM; only the answer mutation
 * exists today). AC6 names this hook, so its contract is pinned: it binds the
 * reserved `workflowKeys.clarifications(runId)` key (a PREFIX child of `detail(id)`,
 * so a spec mutation's invalidation cascades for free), never fires the throwing
 * placeholder `queryFn`, and exposes no data. When the endpoint lands, this flips to
 * the success/error matrix (mirror `useAllowedActions.test.tsx`).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { useClarifications } from './useClarifications';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('useClarifications (disabled stub — clarification-read SEAM)', () => {
  it('binds the reserved key and never fetches (queryFn never runs)', () => {
    const { result } = renderHook(() => useClarifications('run_abcd0001'), {
      wrapper: createWrapper(new QueryClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.isFetching).toBe(false);
    expect(result.current.data).toBeUndefined();
  });

  it('reserves a key that is a prefix child of detail(id) (invalidation cascade)', () => {
    const runId = 'run_abcd0001';
    expect(workflowKeys.clarifications(runId)).toEqual([
      'workflows',
      'detail',
      runId,
      'clarifications',
    ]);
    // The detail(id) prefix structurally contains the clarifications key.
    expect(workflowKeys.clarifications(runId).slice(0, 3)).toEqual(workflowKeys.detail(runId));
  });
});
