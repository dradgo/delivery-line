/**
 * Story 3.30 (Task 4/Task 8, AC1/AC6) — `FailureEventSurface` tests.
 *
 * Renders against the vendored story-1.23 `execution-failure-with-retry` stream (served
 * by the default MSW handlers): a `runner.failed` (failureCategory `runner_crash`) +
 * a `recovery.retried` marker. Asserts: failure events render prominently with the
 * `state-error` signifier; clicking a failure opens the diagnostics panel with the
 * humanized category + reason + selectable `correlationId` + a PLACEHOLDER logs
 * affordance; scope discipline — a run with no failure events renders nothing.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { server } from '@/test/server';

import { FailureEventSurface } from './FailureEventSurface';

const FAILED_RUN = 'run_fix_fail_001';
const EVENTS_URL = 'http://localhost/api/v1/workflows/:runId/events';

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderSurface(workflowRunId: string) {
  return render(
    <QueryClientProvider client={freshClient()}>
      <FailureEventSurface workflowRunId={workflowRunId} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('FailureEventSurface', () => {
  it('renders the minimal failure list (failure event + recovery marker) prominently', async () => {
    renderSurface(FAILED_RUN);
    await screen.findByTestId('failure-event-surface');
    const rows = screen.getAllByTestId('failure-event-row');
    // execution-failure-with-retry: runner.failed (evt_010) + recovery.retried (evt_012).
    expect(rows).toHaveLength(2);
    expect(rows.some((row) => row.getAttribute('data-event-type') === 'runner.failed')).toBe(true);
    // The failure row carries the state-error "Failed" signifier (non-color label).
    const failure = rows.find((row) => row.getAttribute('data-event-type') === 'runner.failed');
    expect(failure).toBeDefined();
    expect(within(failure as HTMLElement).getByText('Failed')).toBeInTheDocument();
    expect(within(failure as HTMLElement).getByText('Runner Crash')).toBeInTheDocument();
  });

  it('clicking a failure event opens the diagnostics panel with category + reason + correlationId', async () => {
    // Story 4.4 — a Failed run offers view_runner_logs (the fixture terminalState is Completed, so
    // the default allowed-actions handler would omit it); override to the operator failed set.
    server.use(
      http.get('http://localhost/api/v1/workflows/:runId/allowed-actions', () =>
        HttpResponse.json({
          actions: ['retry', 'view_diagnostics', 'view_runner_logs'],
          versionStamp: {
            workflowState: 'Failed',
            lastEventId: 'evt_fix_fail_010',
            currentSpecArtifactVersion: 1,
            currentContextBundleVersion: 1,
          },
        }),
      ),
    );
    const user = userEvent.setup();
    renderSurface(FAILED_RUN);
    await screen.findByTestId('failure-event-surface');
    const failure = screen
      .getAllByTestId('failure-event-row')
      .find((row) => row.getAttribute('data-event-type') === 'runner.failed');
    await user.click(failure as HTMLElement);

    expect(await screen.findByTestId('failure-diagnostics-category')).toHaveTextContent(
      'Runner Crash',
    );
    expect(screen.getByTestId('failure-diagnostics-reason')).toHaveTextContent(
      'container exited with SIGSEGV',
    );
    expect(screen.getByTestId('failure-diagnostics-correlation')).toHaveTextContent(
      'corr_fix_fail_001',
    );
    // Story 4.4 AC8 — the correlation id has a real copy-to-clipboard button (not select-all CSS).
    expect(screen.getByTestId('failure-diagnostics-copy-correlation')).toBeInTheDocument();
    // Story 4.4 AC5 — expand the "Runner log" section; the download is a REAL enabled link (endpoint
    // now wired), gated on view_runner_logs (present for a Failed run in the default handler).
    await user.click(screen.getByTestId('failure-diagnostics-runner-log-trigger'));
    const download = await screen.findByTestId('failure-diagnostics-download-log');
    expect(download).toBeEnabled();
  });

  it('renders nothing when the run has no failure events (scope discipline, AC5)', async () => {
    server.use(
      http.get(EVENTS_URL, () =>
        HttpResponse.json({
          workflowRun: {
            publicId: 'run_calm_001',
            ticketRef: 'DEL-1',
            createdAt: '2026-01-01T00:00:00Z',
            terminalState: 'Completed',
          },
          events: [
            {
              publicId: 'evt_calm_001',
              workflowRunPublicId: 'run_calm_001',
              eventType: 'workflow.stateChanged',
              priorState: null,
              resultingState: 'Completed',
              actorIdentity: 'system',
              actorType: 'system',
              reason: 'done',
              failureCategory: null,
              interventionMarker: false,
              createdAt: '2026-01-01T00:01:00Z',
              details: {},
            },
          ],
        }),
      ),
    );
    const { container } = renderSurface('run_calm_001');
    // Allow the query to settle, then assert the surface never appears.
    await waitFor(() => expect(screen.queryByTestId('failure-event-surface')).toBeNull());
    expect(container).toBeEmptyDOMElement();
  });

  it('a11y — the failure surface has zero axe violations', async () => {
    const { container } = renderSurface(FAILED_RUN);
    await screen.findByTestId('failure-event-surface');
    await expectNoA11yViolations(container);
  });
});

// ---------------------------------------------------------------------------
// Story 4.4 — the enriched failure-diagnostics deep-dive panel.
// ---------------------------------------------------------------------------
describe('FailureEventSurface — failure-diagnostics deep-dive (story 4.4)', () => {
  const DIAGNOSTICS_URL = 'http://localhost/api/v1/workflows/:runId/failure-diagnostics';
  const ALLOWED_URL = 'http://localhost/api/v1/workflows/:runId/allowed-actions';

  function serveAllowedActions(actions: string[]) {
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json({
          actions,
          versionStamp: {
            workflowState: 'Failed',
            lastEventId: 'evt_fix_fail_010',
            currentSpecArtifactVersion: 1,
            currentContextBundleVersion: 1,
          },
        }),
      ),
    );
  }

  async function openFailureSheet(
    allowedActions: string[] = ['retry', 'view_diagnostics', 'view_runner_logs'],
  ) {
    serveAllowedActions(allowedActions);
    const user = userEvent.setup();
    renderSurface(FAILED_RUN);
    await screen.findByTestId('failure-event-surface');
    const failure = screen
      .getAllByTestId('failure-event-row')
      .find((row) => row.getAttribute('data-event-type') === 'runner.failed');
    await user.click(failure as HTMLElement);
    await screen.findByTestId('failure-diagnostics-summary');
    return user;
  }

  it('answers the NFR7 five questions above the fold (AC7)', async () => {
    await openFailureSheet();
    const summary = screen.getByTestId('failure-diagnostics-summary');
    // what happened / what failed
    expect(within(summary).getByTestId('failure-diagnostics-category')).toHaveTextContent(
      'Runner Crash',
    );
    // reason (what happened, detail)
    expect(within(summary).getByTestId('failure-diagnostics-reason')).toHaveTextContent(
      'container exited with SIGSEGV',
    );
    // what changed / who acted / what is next
    expect(summary).toHaveTextContent('What changed');
    expect(summary).toHaveTextContent('Who acted');
    expect(summary).toHaveTextContent('What is next');
  });

  it('renders the ranked recommended actions with an active Retry button gated on allowed-actions (AC4)', async () => {
    await openFailureSheet();
    const rec = await screen.findByTestId('failure-diagnostics-recommendation');
    expect(rec).toHaveAttribute('data-action-type', 'retry');
    expect(rec).toHaveAttribute('data-safety', 'safe');
    // `retry` is in the Failed run's allowed-actions → an active invoke button appears.
    expect(within(rec).getByTestId('failure-diagnostics-invoke')).toBeEnabled();
  });

  it('renders recommended actions as guidance (no invoke button) when retry is not allowed', async () => {
    await openFailureSheet(['view_diagnostics']);
    const rec = await screen.findByTestId('failure-diagnostics-recommendation');
    expect(within(rec).queryByTestId('failure-diagnostics-invoke')).toBeNull();
  });

  it('copies the correlation id to the clipboard (AC8)', async () => {
    // userEvent.setup() installs its own navigator.clipboard stub; the component writes to it and
    // we read it back — proving the copy button (not select-all CSS) actually copies the value.
    const user = await openFailureSheet();
    await user.click(screen.getByTestId('failure-diagnostics-copy-correlation'));
    expect(await screen.findByText('Copied')).toBeInTheDocument();
    expect(await navigator.clipboard.readText()).toBe('corr_fix_fail_001');
  });

  it('flags a drifted integration sync status (AC6)', async () => {
    server.use(
      http.get(DIAGNOSTICS_URL, () =>
        HttpResponse.json({
          currentState: 'Failed',
          failedStage: 'execution',
          lastSuccessfulStage: 'Executing',
          failureCategory: 'runner_crash',
          failureReason: 'container exited with SIGSEGV',
          failureTimestamp: '2026-06-17T10:30:00Z',
          lastActivityTimestamp: '2026-06-17T10:30:00Z',
          correlationId: 'corr_fix_fail_001',
          lastGoodState: 'Executing',
          currentBlockingReason: null,
          nextSafeAction: 'retry',
          lastActorIdentity: 'codex-runner',
          runnerLogReference: null,
          integrationSyncStatus: {
            linear: {
              integrationType: 'linear',
              externalRef: 'LIN-9',
              syncStatus: 'stale',
              lastSyncAt: '2026-06-17T09:00:00Z',
            },
            github: null,
          },
          recommendedRecoveryActions: [],
        }),
      ),
    );
    const user = await openFailureSheet();
    await user.click(screen.getByTestId('failure-diagnostics-sync-trigger'));
    const status = await screen.findByTestId('failure-diagnostics-sync-status');
    expect(status).toHaveAttribute('data-drift', 'true');
    expect(status).toHaveTextContent('stale');
  });

  it('a11y — the open diagnostics deep-dive has zero axe violations', async () => {
    const { container } = renderSurface(FAILED_RUN);
    const user = userEvent.setup();
    await screen.findByTestId('failure-event-surface');
    const failure = screen
      .getAllByTestId('failure-event-row')
      .find((row) => row.getAttribute('data-event-type') === 'runner.failed');
    await user.click(failure as HTMLElement);
    await screen.findByTestId('failure-diagnostics-summary');
    await expectNoA11yViolations(container);
  });
});

// ---------------------------------------------------------------------------
// Story 4.23 (Task 10, AC8b) — the drift-indicator reconcile launch context.
// ---------------------------------------------------------------------------
describe('FailureEventSurface — reconcile drift launch (story 4.23)', () => {
  const DIAGNOSTICS_URL = 'http://localhost/api/v1/workflows/:runId/failure-diagnostics';
  const ALLOWED_URL = 'http://localhost/api/v1/workflows/:runId/allowed-actions';
  const CONFLICTS_URL = 'http://localhost/api/v1/integration-conflicts';
  const CONFLICT_DETAIL_URL = 'http://localhost/api/v1/integration-conflicts/:conflictId';

  /** Diagnostics with a drifted Linear sync (so the Linear row can offer Reconcile). */
  function diagnosticsWithLinearDrift() {
    return {
      currentState: 'Failed',
      failedStage: 'execution',
      lastSuccessfulStage: 'Executing',
      failureCategory: 'runner_crash',
      failureReason: 'container exited with SIGSEGV',
      correlationId: 'corr_fix_fail_001',
      lastGoodState: 'Executing',
      currentBlockingReason: null,
      nextSafeAction: 'retry',
      lastActorIdentity: 'codex-runner',
      runnerLogReference: null,
      integrationSyncStatus: {
        linear: {
          integrationType: 'linear',
          externalRef: 'LIN-9',
          syncStatus: 'stale',
          lastSyncAt: '2026-06-17T09:00:00Z',
        },
        github: null,
      },
      recommendedRecoveryActions: [],
    };
  }

  function serveAllowedActions() {
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json({
          actions: ['retry', 'view_diagnostics', 'view_runner_logs'],
          versionStamp: { workflowState: 'Failed', lastEventId: 'evt_fix_fail_010' },
        }),
      ),
    );
  }

  async function openSyncSection() {
    serveAllowedActions();
    const user = userEvent.setup();
    renderSurface(FAILED_RUN);
    await screen.findByTestId('failure-event-surface');
    const failure = screen
      .getAllByTestId('failure-event-row')
      .find((row) => row.getAttribute('data-event-type') === 'runner.failed');
    await user.click(failure as HTMLElement);
    await screen.findByTestId('failure-diagnostics-summary');
    await user.click(screen.getByTestId('failure-diagnostics-sync-trigger'));
    return user;
  }

  it('a drifted Linear row opens the reconciliation dialog on the matching unresolved conflict (AC8b)', async () => {
    server.use(
      http.get(DIAGNOSTICS_URL, () => HttpResponse.json(diagnosticsWithLinearDrift())),
      http.get(CONFLICTS_URL, () =>
        HttpResponse.json({
          conflicts: [{ conflictId: 'icf_linear_1', integrationType: 'linear' }],
        }),
      ),
      http.get(CONFLICT_DETAIL_URL, () =>
        HttpResponse.json({
          conflictId: 'icf_linear_1',
          workflowRunId: FAILED_RUN,
          conflictCategory: 'metadata_drift',
          integrationType: 'linear',
          internalStateSnapshot: '{"state":"open"}',
          externalStateSnapshot: '{"state":"done"}',
          suggestedDecisions: [{ decision: 'accept_external_state', safety: 'safe' }],
        }),
      ),
    );
    const user = await openSyncSection();
    // The Linear (drifted) row exposes a Reconcile control (replacing the static tooltip).
    const reconcile = await screen.findByTestId('failure-diagnostics-reconcile');
    await user.click(reconcile);
    // The dialog opens on the resolved conflict.
    expect(await screen.findByTestId('reconciliation-decisions')).toBeInTheDocument();
  });

  it('does NOT offer Reconcile on a drifted row whose integration has no matching unresolved conflict (patch #1 — no cross-integration launch)', async () => {
    server.use(
      http.get(DIAGNOSTICS_URL, () => HttpResponse.json(diagnosticsWithLinearDrift())),
      // The run's only unresolved conflict is on GitHub — NOT the drifted Linear integration.
      http.get(CONFLICTS_URL, () =>
        HttpResponse.json({
          conflicts: [{ conflictId: 'icf_github_1', integrationType: 'github_pr' }],
        }),
      ),
    );
    await openSyncSection();
    // The Linear drift row is shown but must not launch onto the unrelated GitHub conflict.
    await screen.findByTestId('failure-diagnostics-sync-status');
    expect(screen.queryByTestId('failure-diagnostics-reconcile')).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Story 3.29 (Task 5, AC5/R6) — the takeover row (a live workflow.stateChanged
// → TakenOver event; NOT a recovery.takeover event type).
// ---------------------------------------------------------------------------
describe('FailureEventSurface — takeover row (story 3.29)', () => {
  const TAKEN_OVER_RUN = 'run_taken_over_001';

  function takeoverEvent(overrides: Record<string, unknown> = {}) {
    return {
      publicId: 'evt_takeover_001',
      workflowRunPublicId: TAKEN_OVER_RUN,
      eventType: 'workflow.stateChanged',
      priorState: 'WaitingForReview',
      resultingState: 'TakenOver',
      actorIdentity: 'dev@acme.example',
      actorType: 'human',
      reason: 'Agent stalled on an ambiguous merge conflict',
      failureCategory: null,
      interventionMarker: true,
      createdAt: '2026-06-17T11:00:00Z',
      details: { reviewerRole: 'developer', correlationId: 'corr_takeover_001' },
      ...overrides,
    };
  }

  function serve(events: Record<string, unknown>[]) {
    server.use(
      http.get(EVENTS_URL, () =>
        HttpResponse.json({
          workflowRun: {
            publicId: TAKEN_OVER_RUN,
            ticketRef: 'DEL-9002',
            createdAt: '2026-06-17T10:00:00Z',
            terminalState: 'TakenOver',
          },
          events,
        }),
      ),
    );
  }

  it('renders the takeover transition prominently with actor + reason + permalink anchor', async () => {
    serve([takeoverEvent()]);
    renderSurface(TAKEN_OVER_RUN);
    await screen.findByTestId('failure-event-surface');

    const row = screen
      .getAllByTestId('failure-event-row')
      .find((r) => r.getAttribute('data-event-type') === 'workflow.stateChanged');
    expect(row).toBeDefined();
    const takeoverRow = row as HTMLElement;
    // Recovery-styled "Taken over" chip (non-color signifier) + actor + reason.
    const chip = within(takeoverRow).getByText('Taken over');
    expect(chip.closest('[data-state-name]')).toHaveAttribute('data-state-name', 'recovery');
    expect(takeoverRow).toHaveTextContent('dev@acme.example');
    expect(takeoverRow).toHaveTextContent('Agent stalled on an ambiguous merge conflict');
    // AC5 permalink anchor — the event publicId is the element id + data hook.
    expect(takeoverRow).toHaveAttribute('id', 'evt_takeover_001');
    expect(takeoverRow).toHaveAttribute('data-event-id', 'evt_takeover_001');
  });

  it('opens the diagnostics panel with actor + reason + correlationId (no runner-logs affordance)', async () => {
    const user = userEvent.setup();
    serve([takeoverEvent()]);
    renderSurface(TAKEN_OVER_RUN);
    await screen.findByTestId('failure-event-surface');

    await user.click(screen.getByTestId('failure-event-row'));
    expect(await screen.findByTestId('failure-diagnostics-actor')).toHaveTextContent(
      'dev@acme.example',
    );
    expect(screen.getByTestId('failure-diagnostics-reason')).toHaveTextContent(
      'Agent stalled on an ambiguous merge conflict',
    );
    expect(screen.getByTestId('failure-diagnostics-correlation')).toHaveTextContent(
      'corr_takeover_001',
    );
    // A takeover is not a runner failure → no category block, no runner-logs placeholder.
    expect(screen.queryByTestId('failure-diagnostics-category')).toBeNull();
    expect(screen.queryByTestId('failure-diagnostics-logs')).toBeNull();
  });

  it('renders the takeover reason as escaped text, never raw HTML', async () => {
    serve([takeoverEvent({ reason: '<img src=x onerror="alert(1)">stuck' })]);
    renderSurface(TAKEN_OVER_RUN);
    const surface = await screen.findByTestId('failure-event-surface');
    expect(surface).toHaveTextContent('<img src=x onerror="alert(1)">stuck');
    expect(surface.querySelector('img')).toBeNull();
  });

  it('coexists with a failure event without breaking the failure rendering', async () => {
    serve([
      {
        publicId: 'evt_fail_xx',
        workflowRunPublicId: TAKEN_OVER_RUN,
        eventType: 'runner.failed',
        priorState: 'Executing',
        resultingState: 'Failed',
        actorIdentity: 'codex-runner',
        actorType: 'agent',
        reason: 'container exited 1',
        failureCategory: 'runner_crash',
        interventionMarker: false,
        createdAt: '2026-06-17T10:30:00Z',
        details: {},
      },
      takeoverEvent(),
    ]);
    renderSurface(TAKEN_OVER_RUN);
    await screen.findByTestId('failure-event-surface');

    const rows = screen.getAllByTestId('failure-event-row');
    expect(rows).toHaveLength(2);
    expect(rows.some((r) => r.getAttribute('data-event-type') === 'runner.failed')).toBe(true);
    expect(rows.some((r) => r.getAttribute('data-event-type') === 'workflow.stateChanged')).toBe(
      true,
    );
  });

  it('a11y — the takeover surface has zero axe violations', async () => {
    serve([takeoverEvent()]);
    const { container } = renderSurface(TAKEN_OVER_RUN);
    await screen.findByTestId('failure-event-surface');
    await expectNoA11yViolations(container);
  });
});

// ---------------------------------------------------------------------------
// Story 4.24 review (D1/D2) — the classify launch context + the humanized
// classification provenance rendered next to it (diagnostics launch surface).
// ---------------------------------------------------------------------------
describe('FailureEventSurface — classify launch + classification (story 4.24)', () => {
  const ALLOWED_URL = 'http://localhost/api/v1/workflows/:runId/allowed-actions';
  const TAXONOMY_URL = 'http://localhost/api/v1/registries/failure-taxonomy';
  const CLASSIFICATION_URL = 'http://localhost/api/v1/workflows/:runId/failure-classification';

  function serveAllowed(actions: string[]) {
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json({
          actions,
          versionStamp: { workflowState: 'Failed', lastEventId: 'evt_fix_fail_010' },
        }),
      ),
    );
  }
  function serveTaxonomy() {
    server.use(
      http.get(TAXONOMY_URL, () =>
        HttpResponse.json({
          values: [
            {
              value: 'agent_execution_failure',
              humanReadableName: 'Agent Execution Failure',
              description: 'The agent failed to produce a valid result.',
              examples: ['malformed output'],
              deprecated: false,
            },
          ],
        }),
      ),
    );
  }
  function serveClassification(body: Record<string, unknown>) {
    server.use(http.get(CLASSIFICATION_URL, () => HttpResponse.json(body)));
  }

  it('offers the Classify trigger only when classify_failure is an allowed action (D2)', async () => {
    serveAllowed(['retry', 'classify_failure']);
    serveTaxonomy();
    serveClassification({ workflowRunId: FAILED_RUN, deprecated: false, priorClassifications: [] });
    renderSurface(FAILED_RUN);
    await screen.findByTestId('failure-event-surface');
    expect(await screen.findByTestId('failure-diagnostics-classify-trigger')).toBeInTheDocument();
  });

  it('hides the Classify trigger for an ineligible run (classify_failure not allowed) (D2)', async () => {
    serveAllowed(['retry', 'view_diagnostics']);
    serveTaxonomy();
    // A rendered classification chip proves the surface committed past its loading state; the gate is
    // independent of it, so if the trigger were going to appear it would have by then.
    serveClassification({
      workflowRunId: FAILED_RUN,
      currentTaxonomyValue: 'agent_execution_failure',
      currentDisplayLabel: 'agent_execution_failure',
      deprecated: false,
      priorClassifications: [],
    });
    renderSurface(FAILED_RUN);
    await screen.findByTestId('failure-event-surface');
    await screen.findByTestId('failure-diagnostics-classification');
    expect(screen.queryByTestId('failure-diagnostics-classify-trigger')).toBeNull();
  });

  it('renders the current classification humanized via the registry, not the raw wire value (D1)', async () => {
    serveAllowed(['retry', 'classify_failure']);
    serveTaxonomy();
    serveClassification({
      workflowRunId: FAILED_RUN,
      currentTaxonomyValue: 'agent_execution_failure',
      currentDisplayLabel: 'agent_execution_failure',
      deprecated: false,
      classifiedBy: 'alex',
      priorClassifications: [],
    });
    renderSurface(FAILED_RUN);
    await screen.findByTestId('failure-event-surface');
    const chip = await screen.findByTestId('failure-diagnostics-classification');
    expect(chip).toHaveTextContent('Agent Execution Failure');
    expect(chip).not.toHaveTextContent('agent_execution_failure');
    expect(chip).toHaveTextContent(/by alex/i);
  });
});
