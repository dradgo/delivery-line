/**
 * Story 3c-9 (Task 3/4) — read + mutation hook coverage.
 *
 * Exercises the thin read hooks (`useProjectsList`, `useProjectDetail`) and the
 * disable/enable mutation hooks (the create/update/credential/test paths are covered
 * through their components) so the `src/features/projects/**` floor is met.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';

import { server } from '@/test/server';
import { defaultProjectFixture } from '@/test/handlers';

import { useProjectsList } from '../hooks/useProjectsList';
import { useProjectDetail } from '../hooks/useProjectDetail';
import { useDisableProject } from '../hooks/useDisableProject';
import { useEnableProject } from '../hooks/useEnableProject';

const PROJECTS_URL = 'http://localhost/api/v1/projects';

function wrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function QueryWrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return QueryWrapper;
}

afterEach(() => {
  cleanup();
});

describe('project read hooks', () => {
  it('useProjectsList returns the typed list', async () => {
    const { result } = renderHook(() => useProjectsList(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0]?.slug).toBe('default');
  });

  it('useProjectDetail fetches a single project when enabled', async () => {
    const { result } = renderHook(() => useProjectDetail('prj_default'), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.id).toBe('prj_default');
  });

  it('useProjectDetail does not fetch for an empty id', () => {
    const { result } = renderHook(() => useProjectDetail(undefined), { wrapper: wrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('project disable / enable hooks', () => {
  it('useDisableProject posts to the disable endpoint', async () => {
    let hit = false;
    server.use(
      http.post(`${PROJECTS_URL}/prj_default/disable`, () => {
        hit = true;
        return HttpResponse.json({ ...defaultProjectFixture, status: 'disabled' });
      }),
    );
    const { result } = renderHook(() => useDisableProject('prj_default'), { wrapper: wrapper() });
    result.current.mutate({});
    await waitFor(() => expect(hit).toBe(true));
  });

  it('useEnableProject posts to the enable endpoint', async () => {
    let hit = false;
    server.use(
      http.post(`${PROJECTS_URL}/prj_default/enable`, () => {
        hit = true;
        return HttpResponse.json({ ...defaultProjectFixture, status: 'active' });
      }),
    );
    const { result } = renderHook(() => useEnableProject('prj_default'), { wrapper: wrapper() });
    result.current.mutate({});
    await waitFor(() => expect(hit).toBe(true));
  });
});
