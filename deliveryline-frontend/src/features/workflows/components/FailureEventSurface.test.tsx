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
    // AC6 — the runner-logs download is a disabled placeholder (no fabricated URL).
    expect(screen.getByTestId('failure-diagnostics-logs-link')).toBeDisabled();
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
