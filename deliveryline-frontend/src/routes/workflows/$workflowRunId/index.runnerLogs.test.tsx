/**
 * Story 3d-5 (AC6 / AC8) — the Step Execution Log Viewer is gated on the backend-reported
 * `view_runner_logs` action ONLY (through `useAllowedActions`, never role-inferred). Mounts the REAL
 * route tree (the `index.test.tsx` pattern) and overrides the allowed-actions response to prove the
 * viewer appears with the action and is hidden without it. A mock `EventSource` stands in for the
 * SSE stream (jsdom has none).
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { render, screen, cleanup } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installMatchMedia, uninstallMatchMedia } from '@/test/matchMedia';
import { routeTree } from '@/routeTree.gen';
import { NavigationBreadcrumbProvider } from '@/lib/navigation/NavigationBreadcrumbProvider';
import { server } from '@/test/server';

const RUN_ID = 'run_fix_happy_001';
const ALLOWED_ACTIONS_URL = 'http://localhost/api/v1/workflows/:workflowRunId/allowed-actions';

/** A no-op EventSource so the viewer can mount without a real SSE transport. */
class NoopEventSource {
  addEventListener() {}
  removeEventListener() {}
  close() {}
}

function allowedActionsResponse(actions: string[]) {
  return HttpResponse.json({
    actions,
    versionStamp: {
      workflowState: 'WaitingForReview',
      lastEventId: 'evt_runnerlogs',
      currentSpecArtifactVersion: 1,
      currentContextBundleVersion: 1,
    },
  });
}

function renderRoute() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/workflows/${RUN_ID}`] }),
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
  (globalThis as { EventSource?: unknown }).EventSource =
    NoopEventSource as unknown as typeof EventSource;
});
afterEach(() => {
  cleanup();
  uninstallMatchMedia();
  vi.restoreAllMocks();
  delete (globalThis as { EventSource?: unknown }).EventSource;
});

describe('WorkflowDetail route — runner-log viewer gating (story 3d-5 AC6/AC8)', () => {
  it('renders the Step Execution Log Viewer when view_runner_logs is allowed', async () => {
    server.use(
      http.get(ALLOWED_ACTIONS_URL, () =>
        allowedActionsResponse(['view_only', 'view_runner_logs']),
      ),
    );

    renderRoute();

    expect(await screen.findByTestId('step-execution-log-viewer')).toBeInTheDocument();
  });

  it('hides the Step Execution Log Viewer when view_runner_logs is absent', async () => {
    server.use(http.get(ALLOWED_ACTIONS_URL, () => allowedActionsResponse(['view_only'])));

    renderRoute();

    // The run heading proves the route loaded; the viewer must be absent.
    await screen.findByRole('heading', { name: /Workflow run/ });
    expect(screen.queryByTestId('step-execution-log-viewer')).not.toBeInTheDocument();
  });

  // Story 3e-5 (AC3/AC4/AC7) — the spec-generation (Investigating) state is now a live runner state
  // that offers view_runner_logs, and the viewer renders BELOW the Decision Bar.
  it('renders the log viewer below the Decision Bar in the Investigating state', async () => {
    server.use(
      http.get(ALLOWED_ACTIONS_URL, () =>
        HttpResponse.json({
          actions: ['view_only', 'view_runner_logs'],
          versionStamp: {
            workflowState: 'Investigating',
            lastEventId: 'evt_runnerlogs',
            currentSpecArtifactVersion: 1,
            currentContextBundleVersion: 1,
          },
        }),
      ),
    );

    renderRoute();

    const viewer = await screen.findByTestId('step-execution-log-viewer');
    const decisionBar = await screen.findByTestId('approval-decision-bar');
    // AC4: the viewer must appear AFTER the Decision Bar in document order. compareDocumentPosition
    // returns a bitmask, so assert the FOLLOWING bit is set rather than strict-equal — robust to a
    // future layout that nests the surfaces (which would OR-in DOCUMENT_POSITION_CONTAINED_BY etc.).
    expect(
      decisionBar.compareDocumentPosition(viewer) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
});
