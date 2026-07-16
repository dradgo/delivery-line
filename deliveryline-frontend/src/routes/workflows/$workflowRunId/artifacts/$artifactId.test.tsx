/**
 * Artifact viewer route — the subtitle reflects the LIVE artifact type (not the
 * story-2.6 hardcoded `spec` loader stub), and the in-flow decision bar is the
 * state-driven `WorkflowDecisionBar` (so a `WaitingForReview` run viewing an
 * implementation-plan gets the `implementation_review` bar, not a perpetually-blocked
 * `spec_approval` bar reading "No decision available").
 *
 * Mounts the REAL generated `routeTree` (the routeCoverage.integration pattern); the
 * detail / allowed-actions / artifact reads are overridden per-test via `server.use`.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installMatchMedia, uninstallMatchMedia } from '@/test/matchMedia';
import { routeTree } from '@/routeTree.gen';
import { NavigationBreadcrumbProvider } from '@/lib/navigation/NavigationBreadcrumbProvider';
import { server } from '@/test/server';

const RUN_ID = 'run_523cc820c8274768a48320aa3ddd46c7';
const ARTIFACT_ID = 'art_4a35b6ae45f64cc7925e4f38c8658912';
const API = 'http://localhost/api/v1/workflows';

function detailWith(currentState: string) {
  return HttpResponse.json({
    workflowRunId: RUN_ID,
    currentState,
    lastEventAt: '2026-06-19T00:00:00Z',
    lastEventType: 'runner.completed',
    specRejectionLoopCount: 0,
    escalationMarker: false,
  });
}

function allowedActionsWith(currentState: string, actions: string[]) {
  return HttpResponse.json({
    actions,
    versionStamp: {
      workflowState: currentState,
      lastEventId: 'evt_0001',
      currentSpecArtifactVersion: 1,
      currentContextBundleVersion: 1,
    },
  });
}

function artifactOf(artifactType: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json({
    artifactType,
    artifactId: ARTIFACT_ID,
    version: 1,
    classification: 'shareable-redacted',
    body: '',
    createdAt: '2026-06-19T00:00:00Z',
    ...extra,
  });
}

function renderArtifactRoute() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({
      initialEntries: [`/workflows/${RUN_ID}/artifacts/${ARTIFACT_ID}`],
    }),
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

describe('Artifact viewer route — live type + state-driven decision bar', () => {
  it('shows the live artifact type in the subtitle (not the hardcoded `spec` stub)', async () => {
    server.use(
      http.get(`${API}/:workflowRunId`, () => detailWith('WaitingForReview')),
      http.get(`${API}/:workflowRunId/allowed-actions`, () =>
        allowedActionsWith('WaitingForReview', []),
      ),
      http.get(`${API}/:workflowRunId/artifacts/:artifactId`, () =>
        artifactOf('implementationPlan', { steps: ['Investigate', 'Produce'] }),
      ),
    );

    renderArtifactRoute();

    // The technical subtitle echoes the REAL artifact type, not `spec`.
    expect(await screen.findByText('implementationPlan')).toBeInTheDocument();
    expect(screen.queryByText('spec')).not.toBeInTheDocument();
  });

  it('mounts the implementation_review decision bar for a WaitingForReview run', async () => {
    server.use(
      http.get(`${API}/:workflowRunId`, () => detailWith('WaitingForReview')),
      http.get(`${API}/:workflowRunId/allowed-actions`, () =>
        allowedActionsWith('WaitingForReview', [
          'accept_implementation',
          'reject_implementation',
          'takeover_workflow',
        ]),
      ),
      http.get(`${API}/:workflowRunId/artifacts/:artifactId`, () =>
        artifactOf('implementationPlan', { steps: ['Investigate', 'Produce'] }),
      ),
    );

    renderArtifactRoute();

    // The bar starts in the `spec_approval` fallback and flips once the run detail
    // resolves to `WaitingForReview` — wait for the state-driven selection.
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-mode',
        'implementation_review',
      ),
    );
    expect(screen.queryByText('No decision available')).not.toBeInTheDocument();
  });

  it('4.20 OQ-2 — opening Compare targets the immediately-prior version via parentArtifactId', async () => {
    const PARENT_ID = 'art_parent00000000000000000000000000000';
    let comparePair: string | null = null;
    server.use(
      http.get(`${API}/:workflowRunId`, () => detailWith('WaitingForSpecApproval')),
      http.get(`${API}/:workflowRunId/allowed-actions`, () =>
        allowedActionsWith('WaitingForSpecApproval', [
          'approve_spec',
          'reject_spec',
          'enter_compare_mode',
        ]),
      ),
      // A v2 spec artifact whose lineage parent (immediately-prior version) is resolvable — the
      // OQ-2 Compare-Mode baseline id now surfaced on the artifact read.
      http.get(`${API}/:workflowRunId/artifacts/:artifactId`, () =>
        artifactOf('spec', { version: 2, parentArtifactId: PARENT_ID }),
      ),
      // The story-4.19 compare endpoint (NOT under /workflows) — capture the ordered id pair.
      http.get(
        'http://localhost/api/v1/artifacts/:artifactIdA/compare/:artifactIdB',
        ({ params }) => {
          comparePair = `${String(params.artifactIdA)}|${String(params.artifactIdB)}`;
          return HttpResponse.json({ noMeaningfulDiff: true });
        },
      ),
    );

    renderArtifactRoute();

    const compareBtn = await screen.findByTestId('artifact-compare-entry');
    // The control activates only once allowed-actions resolve (enter_compare_mode) AND the
    // artifact is past v1 — then the onClick opens the in-context overlay.
    await waitFor(() => expect(compareBtn).toBeEnabled());
    fireEvent.click(compareBtn);

    // artifactIdA = the lineage parent (prior), artifactIdB = the artifact under review (current).
    await waitFor(() => expect(comparePair).toBe(`${PARENT_ID}|${ARTIFACT_ID}`));
  });

  it('keeps the spec_approval bar for a spec artifact awaiting spec approval', async () => {
    server.use(
      http.get(`${API}/:workflowRunId`, () => detailWith('WaitingForSpecApproval')),
      http.get(`${API}/:workflowRunId/allowed-actions`, () =>
        allowedActionsWith('WaitingForSpecApproval', ['approve_spec', 'reject_spec']),
      ),
      http.get(`${API}/:workflowRunId/artifacts/:artifactId`, () => artifactOf('spec')),
    );

    renderArtifactRoute();

    const bar = await screen.findByTestId('approval-decision-bar');
    expect(bar).toHaveAttribute('data-approval-bar-mode', 'spec_approval');
  });
});
