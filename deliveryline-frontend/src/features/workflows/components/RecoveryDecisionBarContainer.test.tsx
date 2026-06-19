/**
 * Story 3.30 (Task 2/Task 8) — `RecoveryDecisionBarContainer` integration + logging.
 *
 * Real wiring: MSW serves the LIVE allowed-actions + detail reads and the
 * retry-workflow mutation. Asserts: a `Failed` run with a live `retry` action renders
 * the ready recovery bar; confirm fires the retry POST; the success path logs the
 * field-only `recovery.retrySubmit` event with NO PII keys (T-LOG-PII); the error path
 * logs `recovery.retryError`; a `Failed` run without `retry` renders `View only`.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';

vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));

import { RecoveryDecisionBarContainer } from './RecoveryDecisionBarContainer';

const RUN_ID = 'run_recovery_demo_001';
const ALLOWED_URL = 'http://localhost/api/v1/workflows/:runId/allowed-actions';
const DETAIL_URL = 'http://localhost/api/v1/workflows/:runId';
const RETRY_URL = 'http://localhost/api/v1/workflows/:runId/retry-workflow';

const FAILED_STAMP = {
  workflowState: 'Failed',
  lastEventId: 'evt_fail_100',
  currentSpecArtifactVersion: 1,
  currentContextBundleVersion: 1,
};

function allowed(actions: string[]) {
  return HttpResponse.json({ actions, versionStamp: FAILED_STAMP });
}

function failedDetail() {
  return HttpResponse.json({
    workflowRunId: RUN_ID,
    currentState: 'Failed',
    failedStage: 'implementation',
    failureCategory: 'runner_crash',
  });
}

function client() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

function renderContainer() {
  return render(
    <QueryClientProvider client={client()}>
      <RecoveryDecisionBarContainer workflowRunId={RUN_ID} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('RecoveryDecisionBarContainer', () => {
  it('renders the ready recovery bar for a Failed run with a live retry action', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowed(['retry', 'view_diagnostics'])),
      http.get(DETAIL_URL, () => failedDetail()),
    );
    renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'ready',
      ),
    );
    expect(screen.getByRole('button', { name: 'Retry failed step' })).toBeInTheDocument();
    expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'recovery_operator',
    );
  });

  it('requests allowed-actions as workflow_owner so a Failed run can retry (backend matrix gate)', async () => {
    // The backend FAILED matrix returns RETRY only for actorRole=workflow_owner; every other
    // role gets [view_only, view_diagnostics]. The recovery bar must request workflow_owner or it
    // shows "View only" despite the run being retryable (the production bug this pins).
    server.use(
      http.get(ALLOWED_URL, ({ request }) => {
        const role = new URL(request.url).searchParams.get('actorRole');
        return role === 'workflow_owner'
          ? allowed(['retry', 'view_diagnostics'])
          : allowed(['view_only', 'view_diagnostics']);
      }),
      http.get(DETAIL_URL, () => failedDetail()),
    );
    renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'ready',
      ),
    );
    expect(screen.getByRole('button', { name: 'Retry failed step' })).toBeInTheDocument();
  });

  it('renders View only for a Failed run whose allowed-actions omit retry', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowed(['view_diagnostics'])),
      http.get(DETAIL_URL, () => failedDetail()),
    );
    renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'disabled',
      ),
    );
    expect(screen.getByTestId('recovery-view-only')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /retry failed step/i })).not.toBeInTheDocument();
  });

  it('confirm fires the retry POST and logs field-only recovery.retrySubmit (no PII)', async () => {
    const info = vi.spyOn(console, 'info').mockImplementation(() => {});
    let retried = false;
    server.use(
      http.get(ALLOWED_URL, () => allowed(['retry'])),
      http.get(DETAIL_URL, () => failedDetail()),
      http.post(RETRY_URL, () => {
        retried = true;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );
    const user = userEvent.setup();
    renderContainer();
    await screen.findByRole('button', { name: 'Retry failed step' });

    await user.click(screen.getByRole('button', { name: 'Retry failed step' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm retry' }));

    await waitFor(() => expect(retried).toBe(true));
    await waitFor(() =>
      expect(info).toHaveBeenCalledWith({
        event: 'recovery.retrySubmit',
        currentState: 'Executing',
      }),
    );
    // T-LOG-PII — the logged object carries ONLY event + currentState (no ids/reason).
    const call = info.mock.calls.find(
      (args) => (args[0] as { event?: string }).event === 'recovery.retrySubmit',
    );
    expect(call).toBeDefined();
    expect(Object.keys(call?.[0] as object).sort()).toEqual(['currentState', 'event']);
  });

  it('error path logs field-only recovery.retryError with the stable code', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    server.use(
      http.get(ALLOWED_URL, () => allowed(['retry'])),
      http.get(DETAIL_URL, () => failedDetail()),
      http.post(RETRY_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Retry not applicable',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/retry-workflow`,
            code: 'RETRY_NOT_APPLICABLE',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderContainer();
    await screen.findByRole('button', { name: 'Retry failed step' });
    await user.click(screen.getByRole('button', { name: 'Retry failed step' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm retry' }));

    await waitFor(() =>
      expect(warn).toHaveBeenCalledWith({
        event: 'recovery.retryError',
        code: 'RETRY_NOT_APPLICABLE',
        transport: false,
      }),
    );
  });

  it('surfaces a load error (with Refresh) and logs recovery.allowedActionsLoadError when allowed-actions fail', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    server.use(
      http.get(ALLOWED_URL, () => HttpResponse.json({ message: 'boom' }, { status: 500 })),
      http.get(DETAIL_URL, () => failedDetail()),
    );
    renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'error',
      ),
    );
    // The recovery bar must NOT masquerade a read failure as "View only".
    expect(screen.getByTestId('approval-load-error')).toBeInTheDocument();
    expect(screen.queryByTestId('recovery-view-only')).not.toBeInTheDocument();
    await waitFor(() =>
      expect(warn).toHaveBeenCalledWith(
        expect.objectContaining({ event: 'recovery.allowedActionsLoadError' }),
      ),
    );
  });

  it('a11y — the ready recovery bar has zero axe violations', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowed(['retry'])),
      http.get(DETAIL_URL, () => failedDetail()),
    );
    const { container } = renderContainer();
    await screen.findByRole('button', { name: 'Retry failed step' });
    await expectNoA11yViolations(container);
  });
});
