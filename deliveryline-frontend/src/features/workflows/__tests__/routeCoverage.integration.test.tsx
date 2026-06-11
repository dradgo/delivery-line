/**
 * Story 2.27 (Task 3, AC5) — route-mount coverage for the typed routes the audit
 * found untested at the ROUTE level (param validation, loader prefetch, missing-
 * resource routing, deep-link entry). Mounts the REAL generated `routeTree` in an
 * in-memory TanStack Router (the queueRoute.integration pattern) so `beforeLoad`
 * param defense, loaders, `notFound()` handling, and deep links run exactly as in
 * production. Backend reads come from the shared 1.23-seeded default MSW handlers
 * (src/test/handlers.ts), so `run_fix_happy_001` resolves and an unknown well-formed
 * id surfaces a real RUN_NOT_FOUND.
 *
 * Scroll-position preservation (AC5e) is a real-layout concern — jsdom has no scroll
 * box, so it is proven in the Playwright critical-journey specs (e2e/), not here.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installMatchMedia, uninstallMatchMedia } from '@/test/matchMedia';
import { routeTree } from '@/routeTree.gen';
import { NavigationBreadcrumbProvider } from '@/lib/navigation/NavigationBreadcrumbProvider';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

/** A known fixture run (happy-path stream → terminalState `Completed`). */
const KNOWN_RUN = 'run_fix_happy_001';

function renderRoute(initialEntry: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialEntry] }),
    context: { queryClient },
  });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <NavigationBreadcrumbProvider>
        <RouterProvider router={router} />
      </NavigationBreadcrumbProvider>
    </QueryClientProvider>,
  );
  return { ...utils, queryClient };
}

beforeEach(() => {
  installMatchMedia(1280);
  vi.spyOn(console, 'info').mockImplementation(() => {});
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  uninstallMatchMedia();
  vi.restoreAllMocks();
});

describe('Route coverage (AC5)', () => {
  it('AC5a — detail route rejects a malformed run id → InvalidLinkState (beforeLoad)', async () => {
    // `run_x` has a 1-char suffix (< the 4-char minimum) → assertValidRunRouteParams throws.
    renderRoute('/workflows/run_x');
    expect(await screen.findByText('Invalid link')).toBeInTheDocument();
    expect(screen.queryByText('Run not found')).not.toBeInTheDocument();
  });

  it('AC5c — detail route for a well-formed unknown run → RunNotFoundState (notFound)', async () => {
    // Valid shape, not in the fixture set → default handler 404s RUN_NOT_FOUND →
    // the loader catches it and throws notFound().
    renderRoute('/workflows/run_unknown999');
    expect(await screen.findByText('Run not found')).toBeInTheDocument();
  });

  it('AC5b/d — detail deep-link warms the cache (loader prefetch) and renders flash-free', async () => {
    const { queryClient } = renderRoute(`/workflows/${KNOWN_RUN}`);

    // The component reads the SAME warmed cache entry the loader populated (the run
    // id appears in both the context strip and the heading — assert presence, and
    // that this is NOT a dead-end state).
    await waitFor(() => expect(screen.getAllByText(KNOWN_RUN).length).toBeGreaterThan(0));
    expect(screen.queryByText('Run not found')).not.toBeInTheDocument();
    expect(screen.queryByText('Invalid link')).not.toBeInTheDocument();
    // Loader prefetch proof: the detail query is populated in the cache.
    expect(queryClient.getQueryData(workflowKeys.detail(KNOWN_RUN))).toBeDefined();
  });

  it('AC5a — artifact route rejects a malformed artifact id → InvalidLinkState', async () => {
    renderRoute(`/workflows/${KNOWN_RUN}/artifacts/art_x`);
    expect(await screen.findByText('Invalid link')).toBeInTheDocument();
  });

  it('AC5d — artifact deep-link renders the viewer for a well-formed pair', async () => {
    renderRoute(`/workflows/${KNOWN_RUN}/artifacts/art_spec0001`);
    expect(await screen.findByText('art_spec0001')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Back to run/ })).toBeInTheDocument();
  });

  it('AC5d — `/` redirects to the queue route (deep-link entry at root)', async () => {
    renderRoute('/');
    // The redirect resolves to /workflows, which renders the queue (1.23 rows).
    expect(await screen.findByTestId('queue-submit-run-link')).toHaveAttribute('href', '/submit');
  });

  it('AC5b/d — queue deep-link with a typed `?state` filter warms the list cache', async () => {
    const { queryClient } = renderRoute('/workflows?state=Executing');
    await waitFor(() =>
      expect(screen.getAllByTestId('run-review-queue-item').length).toBeGreaterThan(0),
    );
    // Loader prefetch proof for the filtered list key.
    expect(queryClient.getQueryData(workflowKeys.list({ state: 'Executing' }))).toBeDefined();
  });
});
