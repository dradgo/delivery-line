/**
 * Story 4.22 (Task 8, AC1/AC9 / Task 6) — the `WorkflowDecisionBar` selector widening.
 *
 * A `Paused` run selects the `recovery_operator` bar (so resume/reconcile render), and the bar
 * stays mounted through a resume settling — the post-resume `Paused → Executing` flip must NOT
 * unmount the recovery bar and tear down its kept-alive success summary before it is seen.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';

vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));

import { WorkflowDecisionBar } from './WorkflowDecisionBar';

const RUN_ID = 'run_recovery_paused_001';
const API = 'http://localhost/api/v1/workflows';
const ALLOWED_URL = `${API}/:runId/allowed-actions`;
const DETAIL_URL = `${API}/:runId`;
const DIAGNOSTICS_URL = `${API}/:runId/failure-diagnostics`;
const RESUME_URL = `${API}/:runId/resume`;

function client() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

function renderBar() {
  return render(
    <QueryClientProvider client={client()}>
      <WorkflowDecisionBar workflowRunId={RUN_ID} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('WorkflowDecisionBar — Paused selects the recovery bar (story 4.22)', () => {
  it('a Paused run renders the recovery_operator bar with Resume', async () => {
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json({ actions: ['resume_workflow'], versionStamp: { workflowState: 'Paused' } }),
      ),
      http.get(DETAIL_URL, () => HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Paused' })),
      http.get(DIAGNOSTICS_URL, () =>
        HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Paused',
          recommendedRecoveryActions: [],
        }),
      ),
    );
    renderBar();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-mode',
        'recovery_operator',
      ),
    );
    expect(await screen.findByRole('button', { name: 'Resume run' })).toBeInTheDocument();
  });

  it('keeps the recovery success summary after resume flips Paused→Executing', async () => {
    let resumed = false;
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json({ actions: ['resume_workflow'], versionStamp: { workflowState: 'Paused' } }),
      ),
      http.get(DETAIL_URL, () =>
        HttpResponse.json({
          workflowRunId: RUN_ID,
          // The resume transitions the run out of Paused the moment the POST lands.
          currentState: resumed ? 'Executing' : 'Paused',
        }),
      ),
      http.get(DIAGNOSTICS_URL, () =>
        HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Paused',
          recommendedRecoveryActions: [],
        }),
      ),
      http.post(RESUME_URL, () => {
        resumed = true;
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Executing',
          recoveryActionId: 'rcv_p1',
          replayed: false,
          correlationId: 'corr-paused-1',
        });
      }),
    );
    const user = userEvent.setup();
    renderBar();
    await user.click(await screen.findByRole('button', { name: 'Resume run' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm resume' }));

    // Even after the currentState flips to Executing (no longer Paused), the recovery bar stays
    // mounted (resume.isSuccess) and shows the kept-alive summary — not the spec_approval bar.
    const summary = await screen.findByTestId('recovery-decision-summary');
    expect(summary).toHaveTextContent('Executing');
    expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'recovery_operator',
    );
  });
});
