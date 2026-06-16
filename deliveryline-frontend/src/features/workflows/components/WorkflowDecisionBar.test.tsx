/**
 * Story 3.30 (P3, code review 2026-06-13) — the recovery bar must SURVIVE the post-retry
 * `Failed → Executing` flip so the success acknowledgement (panel + AC7 `retryRecorded`
 * announcement) is actually seen. This drives a real retry through MSW: the detail read
 * returns `Failed` until the retry POST, then `Executing`; the selector must keep showing
 * the recovery `success` state rather than swapping to the spec-approval bar.
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

const RUN_ID = 'run_recovery_survive_001';
const ALLOWED_URL = 'http://localhost/api/v1/workflows/:runId/allowed-actions';
const DETAIL_URL = 'http://localhost/api/v1/workflows/:runId';
const RETRY_URL = 'http://localhost/api/v1/workflows/:runId/retry-workflow';
const TAKEOVER_URL = 'http://localhost/api/v1/workflows/:runId/takeover';

function client() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('WorkflowDecisionBar — recovery survives the post-retry state flip (P3)', () => {
  it('keeps the recovery success panel after a retry flips currentState Failed→Executing', async () => {
    let retried = false;
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json({ actions: ['retry'], versionStamp: { workflowState: 'Failed' } }),
      ),
      http.get(DETAIL_URL, () =>
        HttpResponse.json({
          workflowRunId: RUN_ID,
          // The retry transitions the run out of Failed — the detail read reflects that
          // the moment the POST lands (RecoveryService: Failed → Executing).
          currentState: retried ? 'Executing' : 'Failed',
          failedStage: 'implementation',
          failureCategory: 'runner_crash',
        }),
      ),
      http.post(RETRY_URL, () => {
        retried = true;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={client()}>
        <WorkflowDecisionBar workflowRunId={RUN_ID} />
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: 'Retry failed step' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm retry' }));

    // Even though the detail read now reports Executing, the recovery bar stays mounted
    // and shows the success acknowledgement — it did NOT swap to the spec-approval bar.
    await waitFor(() => expect(screen.getByTestId('recovery-retry-success')).toBeInTheDocument());
    expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'recovery_operator',
    );
  });
});

describe('WorkflowDecisionBar — implementation_review selection + survival (story 3.28 R10)', () => {
  it('selects the implementation_review bar for a WaitingForReview run', async () => {
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json({
          actions: ['accept_implementation', 'reject_implementation', 'takeover_workflow'],
          versionStamp: { workflowState: 'WaitingForReview', currentContextBundleVersion: 1 },
        }),
      ),
      http.get(DETAIL_URL, () =>
        HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'WaitingForReview',
          latestArtifacts: [
            {
              artifactType: 'prOutput',
              artifactId: 'art_impl_001',
              version: 2,
              status: 'available',
            },
          ],
        }),
      ),
    );
    render(
      <QueryClientProvider client={client()}>
        <WorkflowDecisionBar workflowRunId={RUN_ID} />
      </QueryClientProvider>,
    );
    await screen.findByRole('button', { name: 'Accept implementation' });
    expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'implementation_review',
    );
  });

  it('keeps the bar mounted + shows the takeover summary after the run flips to TakenOver', async () => {
    let takenOver = false;
    server.use(
      http.get(ALLOWED_URL, () =>
        HttpResponse.json({
          actions: takenOver
            ? []
            : ['accept_implementation', 'reject_implementation', 'takeover_workflow'],
          versionStamp: {
            workflowState: takenOver ? 'TakenOver' : 'WaitingForReview',
            currentContextBundleVersion: 1,
          },
        }),
      ),
      http.get(DETAIL_URL, () =>
        HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: takenOver ? 'TakenOver' : 'WaitingForReview',
          latestArtifacts: [
            {
              artifactType: 'prOutput',
              artifactId: 'art_impl_001',
              version: 2,
              status: 'available',
            },
          ],
        }),
      ),
      http.post(TAKEOVER_URL, () => {
        takenOver = true;
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'TakenOver',
          recoveryActionId: 'rec_001',
          replayed: false,
          preservedPrReference: 'octo/repo#42',
        });
      }),
    );
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={client()}>
        <WorkflowDecisionBar workflowRunId={RUN_ID} />
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: 'Take over' }));
    await user.type(await screen.findByTestId('takeover-reason'), 'manual continuation');
    await user.click(screen.getByRole('button', { name: 'Confirm takeover' }));

    // Even though the run is now TakenOver (out of WaitingForReview), the impl bar stays
    // mounted and shows the takeover summary + PR affordance.
    await waitFor(() => expect(screen.getByTestId('takeover-continue-pr')).toBeInTheDocument());
    expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'implementation_review',
    );
  });
});
