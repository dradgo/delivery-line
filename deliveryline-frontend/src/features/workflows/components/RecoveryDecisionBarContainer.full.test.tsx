/**
 * Story 4.22 (Task 8, AC1–AC9, AC13) — `RecoveryDecisionBarContainer` FULL activation integration.
 *
 * Real wiring over MSW: allowed-actions (workflow_owner) + detail + failure-diagnostics feed the
 * bar; the deeper recovery mutations + the non-mutating rerun preview are driven through the live
 * client. Asserts: the full action set renders from allowed-actions + the diagnostics safety
 * ranking; a Paused run surfaces Resume; the resume mutation sends an Idempotency-Key + role body;
 * the rerun preview fetches the superseded/invalidated ids; a typed conflict renders the error CTA;
 * the post-submit summary persists the resulting state + correlationId.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';

vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));

import { RecoveryDecisionBarContainer } from './RecoveryDecisionBarContainer';

const RUN_ID = 'run_recovery_full_001';
const API = 'http://localhost/api/v1/workflows';
const ALLOWED_URL = `${API}/:runId/allowed-actions`;
const DETAIL_URL = `${API}/:runId`;
const DIAGNOSTICS_URL = `${API}/:runId/failure-diagnostics`;
const PREVIEW_URL = `${API}/:runId/preview-rerun-from-step`;
const RESUME_URL = `${API}/:runId/resume`;
const CONFLICTS_URL = 'http://localhost/api/v1/integration-conflicts';
const CONFLICT_DETAIL_URL = 'http://localhost/api/v1/integration-conflicts/:conflictId';

function allowed(actions: string[], workflowState = 'Failed') {
  return HttpResponse.json({ actions, versionStamp: { workflowState, lastEventId: 'evt_1' } });
}

function detail(currentState: string) {
  return HttpResponse.json({ workflowRunId: RUN_ID, currentState, failedStage: 'implementation' });
}

function diagnostics(recommended: Array<{ actionType: string; safetyLevel: string }>) {
  return HttpResponse.json({
    workflowRunId: RUN_ID,
    currentState: 'Failed',
    recommendedRecoveryActions: recommended.map((r) => ({
      actionType: r.actionType,
      safetyLevel: r.safetyLevel,
      reason: 'x',
      precondition: 'y',
    })),
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

describe('RecoveryDecisionBarContainer — full activation (story 4.22)', () => {
  it('renders the full Failed action set with retry primary from the diagnostics ranking (AC1/AC2)', async () => {
    server.use(
      http.get(ALLOWED_URL, () =>
        allowed(['retry', 'rerun_from_step', 'pause_workflow', 'classify_failure']),
      ),
      http.get(DETAIL_URL, () => detail('Failed')),
      http.get(DIAGNOSTICS_URL, () =>
        diagnostics([
          { actionType: 'retry', safetyLevel: 'safe' },
          { actionType: 'pause', safetyLevel: 'caution' },
        ]),
      ),
    );
    renderContainer();
    await screen.findByRole('button', { name: 'Retry failed step' });
    expect(screen.getByRole('button', { name: 'Rerun from step' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pause run' })).toBeInTheDocument();
    // classify has no wired handler here (OQ-2) → disabled + explained, still present.
    const classify = screen.getByRole('button', { name: 'Classify failure' });
    expect(classify).toBeDisabled();
    await waitFor(() =>
      expect(screen.getByTestId('recovery-action-retry')).toHaveAttribute(
        'data-priority',
        'primary',
      ),
    );
  });

  it('surfaces Resume for a Paused run (AC1)', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowed(['resume_workflow'], 'Paused')),
      http.get(DETAIL_URL, () => detail('Paused')),
      http.get(DIAGNOSTICS_URL, () => diagnostics([])),
    );
    renderContainer();
    const resume = await screen.findByRole('button', { name: 'Resume run' });
    expect(resume).toBeInTheDocument();
    // resume_workflow gets the static `safe` fallback (Paused emits no diagnostics) → primary.
    await waitFor(() =>
      expect(screen.getByTestId('recovery-action-resume_workflow')).toHaveAttribute(
        'data-priority',
        'primary',
      ),
    );
  });

  it('resume confirm sends an Idempotency-Key + role body and persists the post-submit summary (AC3/AC8/AC9)', async () => {
    let idempotencyKey: string | null = null;
    let body: { role?: string } = {};
    server.use(
      http.get(ALLOWED_URL, () => allowed(['resume_workflow'], 'Paused')),
      http.get(DETAIL_URL, () => detail('Paused')),
      http.get(DIAGNOSTICS_URL, () => diagnostics([])),
      http.post(RESUME_URL, async ({ request }) => {
        idempotencyKey = request.headers.get('Idempotency-Key');
        body = (await request.json()) as { role?: string };
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Executing',
          recoveryActionId: 'rcv_1',
          replayed: false,
          correlationId: 'corr-xyz',
        });
      }),
    );
    const user = userEvent.setup();
    renderContainer();
    await user.click(await screen.findByRole('button', { name: 'Resume run' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm resume' }));

    await waitFor(() => expect(idempotencyKey).toBeTruthy());
    expect(body.role).toBe('workflow_owner');
    // AC9 — the summary persists the resulting state + correlationId ref.
    const summary = await screen.findByTestId('recovery-decision-summary');
    expect(summary).toHaveTextContent('Executing');
    expect(summary).toHaveTextContent('corr-xyz');
  });

  it('a typed conflict renders the error CTA rather than a bare failure (AC8)', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowed(['resume_workflow'], 'Paused')),
      http.get(DETAIL_URL, () => detail('Paused')),
      http.get(DIAGNOSTICS_URL, () => diagnostics([])),
      http.post(RESUME_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Resume not applicable',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/resume`,
            code: 'RESUME_NOT_APPLICABLE',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderContainer();
    await user.click(await screen.findByRole('button', { name: 'Resume run' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm resume' }));
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'error',
      ),
    );
    expect(screen.getByRole('button', { name: /refresh/i })).toBeInTheDocument();
  });

  it('the rerun dialog fetches the preview and renders the superseded/invalidated ids (AC5)', async () => {
    let previewStep: string | null = null;
    server.use(
      http.get(ALLOWED_URL, () => allowed(['rerun_from_step'])),
      http.get(DETAIL_URL, () => detail('Failed')),
      http.get(DIAGNOSTICS_URL, () => diagnostics([])),
      http.get(PREVIEW_URL, ({ request }) => {
        previewStep = new URL(request.url).searchParams.get('targetStep');
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          targetStep: previewStep ?? 'investigating',
          supersededArtifactIds: ['art_super_9'],
          invalidatedApprovalIds: ['apr_inv_9'],
        });
      }),
    );
    const user = userEvent.setup();
    renderContainer();
    await user.click(await screen.findByRole('button', { name: 'Rerun from step' }));
    // The preview query fires with the default step and renders its ids.
    await waitFor(() => expect(previewStep).toBe('investigating'));
    const preview = await screen.findByTestId('rerun-preview');
    await waitFor(() => expect(preview).toHaveTextContent('art_super_9'));
    expect(preview).toHaveTextContent('apr_inv_9');
  });

  it('wires the reconcile seam: clicking Reconcile resolves a conflictId and opens the dialog (story 4.23, AC8)', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowed(['reconcile_conflict'], 'Paused')),
      http.get(DETAIL_URL, () => detail('Paused')),
      http.get(DIAGNOSTICS_URL, () => diagnostics([])),
      http.get(CONFLICTS_URL, () =>
        HttpResponse.json({
          conflicts: [{ conflictId: 'icf_seam_1', integrationType: 'github_pr' }],
        }),
      ),
      http.get(CONFLICT_DETAIL_URL, () =>
        HttpResponse.json({
          conflictId: 'icf_seam_1',
          workflowRunId: RUN_ID,
          conflictCategory: 'external_state_advanced',
          integrationType: 'github_pr',
          internalStateSnapshot: '{"state":"open"}',
          externalStateSnapshot: '{"state":"merged"}',
          suggestedDecisions: [{ decision: 'accept_external_state', safety: 'safe' }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderContainer();
    // The button starts as the disabled placeholder and becomes ENABLED once the conflicts query
    // resolves a concrete conflictId (handler wired) — re-query each poll (the node is swapped).
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Reconcile conflict' })).toBeEnabled(),
    );
    await user.click(screen.getByRole('button', { name: 'Reconcile conflict' }));
    // The reconciliation dialog opens on the resolved conflict.
    expect(await screen.findByTestId('reconciliation-decisions')).toBeInTheDocument();
  });

  it('keeps the reconcile button DISABLED + explained while no conflict is resolvable, so a click never dead-no-ops (story 4.23 D1)', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowed(['reconcile_conflict'], 'Paused')),
      http.get(DETAIL_URL, () => detail('Paused')),
      http.get(DIAGNOSTICS_URL, () => diagnostics([])),
      // The action is offered but the unresolved-conflict list is empty (another operator resolved
      // it, or it is still loading) — no concrete conflictId to open the dialog on.
      http.get(CONFLICTS_URL, () => HttpResponse.json({ conflicts: [] })),
    );
    renderContainer();
    const reconcile = await screen.findByRole('button', { name: 'Reconcile conflict' });
    // Never enabled (would be an enabled-but-dead click), and explained with resolving copy — NOT the
    // 4.22 seam-missing "upcoming increment" placeholder.
    await waitFor(() =>
      expect(screen.getByTestId('recovery-action-reconcile_conflict')).toHaveTextContent(
        /loading the conflict to reconcile/i,
      ),
    );
    expect(reconcile).toBeDisabled();
    // The resolving copy (not the 4.22 seam-missing "upcoming increment" placeholder) is shown.
    expect(screen.getByTestId('recovery-action-reconcile_conflict')).not.toHaveTextContent(
      /upcoming increment/i,
    );
  });
});
