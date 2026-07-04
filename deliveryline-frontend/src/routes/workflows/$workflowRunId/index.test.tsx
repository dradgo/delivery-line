/**
 * Story 3b-3 (AC4 / AC6) — the WorkflowDetail route surfaces an "Open the implementation
 * output →" link once the execution stage has produced an implementation artifact.
 *
 * Mounts the REAL generated `routeTree` in an in-memory TanStack Router (the
 * routeCoverage.integration pattern) so the loader prefetch + `resolveImplementationArtifact`
 * gating run exactly as in production. The detail read is overridden per-test (via `server.use`)
 * to control `latestArtifacts`; the events / allowed-actions endpoints fall through to the shared
 * `run_fix_happy_001` defaults, so the subordinate panels mount without noise.
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

/** A known fixture run so events / allowed-actions resolve via the default handlers. */
const RUN_ID = 'run_fix_happy_001';
const DETAIL_URL = `http://localhost/api/v1/workflows/:workflowRunId`;

const SPEC_ARTIFACT = {
  artifactType: 'spec',
  artifactId: 'art_spec0001',
  version: 1,
  status: 'available',
};
const PR_OUTPUT_ARTIFACT = {
  artifactType: 'prOutput',
  artifactId: 'art_prout0001',
  version: 2,
  status: 'available',
};

/** A WaitingForReview detail carrying the given `latestArtifacts` (overrides the default). */
function detailWith(latestArtifacts: unknown[]) {
  return HttpResponse.json({
    workflowRunId: RUN_ID,
    currentState: 'WaitingForReview',
    lastEventAt: '2026-06-16T00:00:00Z',
    lastEventType: 'runner.completed',
    specRejectionLoopCount: 0,
    escalationMarker: false,
    latestArtifacts,
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
});
afterEach(() => {
  cleanup();
  uninstallMatchMedia();
  vi.restoreAllMocks();
});

describe('WorkflowDetail route — implementation-output link (story 3b-3 AC4)', () => {
  it('renders the implementation-output link when a prOutput artifact carries an artifactId', async () => {
    server.use(http.get(DETAIL_URL, () => detailWith([SPEC_ARTIFACT, PR_OUTPUT_ARTIFACT])));

    renderRoute();

    const link = await screen.findByRole('link', { name: /Open the implementation output/ });
    expect(link).toHaveAttribute(
      'href',
      `/workflows/${RUN_ID}/artifacts/${PR_OUTPUT_ARTIFACT.artifactId}`,
    );
  });

  it('does NOT render the implementation-output link when only a spec artifact exists', async () => {
    server.use(http.get(DETAIL_URL, () => detailWith([SPEC_ARTIFACT])));

    renderRoute();

    // The spec link proves the detail loaded; the implementation-output link must be absent.
    await screen.findByRole('link', { name: /Open the specification/ });
    expect(
      screen.queryByRole('link', { name: /Open the implementation output/ }),
    ).not.toBeInTheDocument();
  });
});

/**
 * Story 3e-3 (AC4) — the advisory Reviewer Verdict Panel (built at WaitingForReview by 3d-2) is now
 * ALSO rendered in the WaitingForSpecApproval Decision Bar, fed by the same run-scoped, stage-
 * agnostic GET /reviewer-verdict. Advisory-only: the panel is presentational and never gates the
 * human spec decision.
 */
describe('WorkflowDetail route — spec-phase reviewer verdict panel (story 3e-3 AC4)', () => {
  const VERDICT_URL = `http://localhost/api/v1/workflows/:workflowRunId/reviewer-verdict`;

  /** A detail in the given state carrying only the spec artifact (the spec gate). */
  function specGateDetail(currentState: string) {
    return HttpResponse.json({
      workflowRunId: RUN_ID,
      currentState,
      lastEventAt: '2026-06-25T00:00:00Z',
      lastEventType: 'runner.completed',
      specRejectionLoopCount: 0,
      escalationMarker: false,
      latestArtifacts: [SPEC_ARTIFACT],
    });
  }

  it('renders the verdict panel at WaitingForSpecApproval when the reviewer produced a verdict', async () => {
    server.use(
      http.get(DETAIL_URL, () => specGateDetail('WaitingForSpecApproval')),
      http.get(VERDICT_URL, () =>
        HttpResponse.json({
          state: 'available',
          outcome: 'concern',
          rationale: 'The spec leaves the open auth question unresolved.',
          selfReview: false,
          reviewerModelIdentity: 'claude:it',
          producerModelIdentity: 'codex:it',
          unavailableReason: null,
        }),
      ),
    );

    renderRoute();

    // The spec link proves the detail loaded at the spec gate.
    await screen.findByRole('link', { name: /Open the specification/ });
    const panel = await screen.findByTestId('reviewer-verdict-panel');
    expect(panel).toHaveAttribute('data-verdict-state', 'available');
    expect(screen.getByTestId('reviewer-verdict-outcome')).toBeInTheDocument();
  });

  it('does NOT render the verdict panel before the spec gate (Investigating)', async () => {
    server.use(
      http.get(DETAIL_URL, () => specGateDetail('Investigating')),
      http.get(VERDICT_URL, () =>
        HttpResponse.json({ state: 'available', outcome: 'pass', unavailableReason: null }),
      ),
    );

    renderRoute();

    await screen.findByRole('link', { name: /Open the specification/ });
    expect(screen.queryByTestId('reviewer-verdict-panel')).not.toBeInTheDocument();
  });
});

describe('WorkflowDetail route — archive control wiring (story 3d-8 FE gap)', () => {
  it('renders the Archive run button when the workflow_owner matrix advertises archive_run', async () => {
    server.use(
      http.get(`http://localhost/api/v1/workflows/:workflowRunId/allowed-actions`, () =>
        HttpResponse.json({ actions: ['archive_run'] }),
      ),
      http.get(DETAIL_URL, () => detailWith([SPEC_ARTIFACT])),
    );
    renderRoute();
    expect(await screen.findByRole('button', { name: 'Archive run' })).toBeInTheDocument();
  });
});
