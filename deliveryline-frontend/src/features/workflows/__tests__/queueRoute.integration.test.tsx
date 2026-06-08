/**
 * Story 2.15 (Task 5 / AC8) — `/workflows` route-mount integration test.
 *
 * Closes the test story 2.20 deferred (OQ-1): mounts the queue wired EXACTLY as the
 * real `/workflows` route wires it — `<QueueShell renderItem={(s) => <RunReviewQueueItem
 * run={toRunQueueRow(s)} />} />` — inside a REAL in-memory TanStack Router (NOT the
 * mocked `Link` the component test uses) so the open-run intent resolves to a real
 * `/workflows/$workflowRunId` href, with MSW returning live-shaped rows.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { server } from '@/test/server';
import { specRejectAndResubmitSummary } from '@/test/fixtures/runQueue/specRejectAndResubmit';
import { executionFailureSummary } from '@/test/fixtures/runQueue/executionFailure';
import { installMatchMedia, uninstallMatchMedia } from '@/test/matchMedia';
import { routeTree } from '@/routeTree.gen';
import { NavigationBreadcrumbProvider } from '@/lib/navigation/NavigationBreadcrumbProvider';

const LIST_URL = 'http://localhost/api/v1/workflows';

function renderRoute() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/workflows'] }),
    context: { queryClient },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <NavigationBreadcrumbProvider>
        <RouterProvider router={router} />
      </NavigationBreadcrumbProvider>
    </QueryClientProvider>,
  );
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

describe('/workflows route — real RunReviewQueueItem rows (AC8)', () => {
  it('renders real rows that link to the run detail route', async () => {
    server.use(
      http.get(LIST_URL, () =>
        HttpResponse.json([specRejectAndResubmitSummary, executionFailureSummary]),
      ),
    );

    renderRoute();

    await waitFor(() => {
      expect(screen.getAllByTestId('run-review-queue-item')).toHaveLength(2);
    });
    // Real RunReviewQueueItem content (replacing the 2.20 placeholder rows).
    expect(screen.getByText('run_fix_rej_001')).toBeInTheDocument();
    expect(screen.getByText('run_exec_fail_001')).toBeInTheDocument();

    // The open-run intent resolves to a REAL typed href via the real router.
    const links = screen.getAllByRole('link');
    const hrefs = links.map((l) => l.getAttribute('href'));
    expect(hrefs).toContain('/workflows/run_fix_rej_001');
    expect(hrefs).toContain('/workflows/run_exec_fail_001');
  });

  // Story 2a.1 (AC1/AC2) — the persistent header entry point and the empty-state CTA
  // both resolve to the real `/submit` route, and the empty copy drops "via the CLI".
  it('exposes a persistent "Submit a run" entry point linking to /submit', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));

    renderRoute();

    const headerLink = await screen.findByTestId('queue-submit-run-link');
    expect(headerLink).toHaveAttribute('href', '/submit');
  });

  it('updates the empty-state CTA to link to /submit and no longer says "via the CLI"', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));

    renderRoute();

    const emptyCta = await screen.findByTestId('empty-state-submit-link');
    expect(emptyCta).toHaveAttribute('href', '/submit');
    expect(screen.queryByText(/via the CLI/i)).not.toBeInTheDocument();
  });
});
