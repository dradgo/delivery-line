/**
 * Story 3d-6 (AC4 / AC6) — the Read-only Diagnostic Console is gated on the backend-reported
 * `open_diagnostic_console` action ONLY (through `useAllowedActions`, never role-inferred). The
 * action is offered ONLY to the run owner, so the route resolves the workflow_owner-scoped action
 * set. Mounts the REAL route tree and overrides the allowed-actions response to prove the console
 * appears with the action and is hidden without it. A mock `EventSource` stands in for the SSE
 * stream (jsdom has none).
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

/** A no-op EventSource so the console can mount without a real SSE transport. */
class NoopEventSource {
  addEventListener() {}
  removeEventListener() {}
  close() {}
}

function allowedActionsResponse(actions: string[]) {
  return HttpResponse.json({
    actions,
    versionStamp: {
      workflowState: 'Executing',
      lastEventId: 'evt_console',
      currentSpecArtifactVersion: 1,
      currentContextBundleVersion: 1,
    },
  });
}

/**
 * The route makes two allowed-actions reads: the default product_reviewer read (NO `actorRole`
 * param) and the owner-scoped read (the only one that passes `actorRole`). `open_diagnostic_console`
 * is owner-only, so the owner-scoped read returns `ownerActions` while the default read returns the
 * passive set — branching on the param's PRESENCE (not a role string) mirrors the backend matrix.
 */
function ownerScopedHandler(ownerActions: string[]) {
  return http.get(ALLOWED_ACTIONS_URL, ({ request }) => {
    const ownerScopedRead = new URL(request.url).searchParams.has('actorRole');
    return ownerScopedRead
      ? allowedActionsResponse(ownerActions)
      : allowedActionsResponse(['view_only', 'await_outcome']);
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

describe('WorkflowDetail route — diagnostic-console gating (story 3d-6 AC4/AC6)', () => {
  it('renders the Read-only Diagnostic Console when open_diagnostic_console is allowed for the owner', async () => {
    server.use(ownerScopedHandler(['view_only', 'view_runner_logs', 'open_diagnostic_console']));

    renderRoute();

    expect(await screen.findByTestId('read-only-diagnostic-console')).toBeInTheDocument();
  });

  it('hides the Read-only Diagnostic Console when open_diagnostic_console is absent', async () => {
    server.use(ownerScopedHandler(['view_only', 'view_runner_logs']));

    renderRoute();

    // The run heading proves the route loaded; the console must be absent.
    await screen.findByRole('heading', { name: /Workflow run/ });
    expect(screen.queryByTestId('read-only-diagnostic-console')).not.toBeInTheDocument();
  });

  // Story 3e-5 (AC3/AC4/AC7) — open_diagnostic_console is now offered to the owner in the
  // Investigating (spec-generation) state, and the console renders BELOW the Decision Bar.
  it('renders the console below the Decision Bar when allowed in the Investigating state', async () => {
    server.use(
      http.get(ALLOWED_ACTIONS_URL, ({ request }) => {
        const ownerScopedRead = new URL(request.url).searchParams.has('actorRole');
        const actions = ownerScopedRead
          ? ['view_only', 'view_runner_logs', 'open_diagnostic_console']
          : ['view_only'];
        return HttpResponse.json({
          actions,
          versionStamp: {
            workflowState: 'Investigating',
            lastEventId: 'evt_console',
            currentSpecArtifactVersion: 1,
            currentContextBundleVersion: 1,
          },
        });
      }),
    );

    renderRoute();

    const console_ = await screen.findByTestId('read-only-diagnostic-console');
    const decisionBar = await screen.findByTestId('approval-decision-bar');
    // AC4: the console must appear AFTER the Decision Bar in document order. compareDocumentPosition
    // returns a bitmask, so assert the FOLLOWING bit is set rather than strict-equal — robust to a
    // future layout that nests the surfaces (which would OR-in DOCUMENT_POSITION_CONTAINED_BY etc.).
    expect(
      decisionBar.compareDocumentPosition(console_) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
});
