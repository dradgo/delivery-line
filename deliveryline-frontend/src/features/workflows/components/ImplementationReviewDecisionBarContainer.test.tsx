/**
 * Story 3.28 (Task 5/Task 6) — `ImplementationReviewDecisionBarContainer` integration +
 * logging.
 *
 * Real wiring: MSW serves the LIVE allowed-actions + detail reads and the accept / reject /
 * takeover mutations. Asserts: a `WaitingForReview` run with an implementation artifact
 * renders the ready bar; each mutation fires with the correct body (accept/reject send the
 * IMPLEMENTATION artifact version + the stamp context-bundle version; takeover sends only
 * reasonText, NO versions / actor / reviewerRole); the success paths log field-only events
 * with NO PII; `APPROVAL_VERSION_MISMATCH` → stale + refetch; takeover success captures
 * `preservedPrReference` for the AC7 PR affordance.
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

import { ImplementationReviewDecisionBarContainer } from './ImplementationReviewDecisionBarContainer';

const RUN_ID = 'run_impl_review_demo_001';
const ALLOWED_URL = 'http://localhost/api/v1/workflows/:runId/allowed-actions';
const DETAIL_URL = 'http://localhost/api/v1/workflows/:runId';
const ACCEPT_URL = 'http://localhost/api/v1/workflows/:runId/accept-implementation';
const REJECT_URL = 'http://localhost/api/v1/workflows/:runId/reject-implementation';
const TAKEOVER_URL = 'http://localhost/api/v1/workflows/:runId/takeover';

const REVIEW_STAMP = {
  workflowState: 'WaitingForReview',
  lastEventId: 'evt_review_100',
  currentSpecArtifactVersion: 3,
  currentContextBundleVersion: 1,
};

const ALL_ACTIONS = ['accept_implementation', 'reject_implementation', 'takeover_workflow'];

function allowed(actions: string[] = ALL_ACTIONS) {
  return HttpResponse.json({ actions, versionStamp: REVIEW_STAMP });
}

function reviewDetail() {
  return HttpResponse.json({
    workflowRunId: RUN_ID,
    currentState: 'WaitingForReview',
    currentActorIdentity: 'agent-runner',
    latestArtifacts: [
      { artifactType: 'spec', artifactId: 'art_spec_001', version: 3, status: 'available' },
      { artifactType: 'prOutput', artifactId: 'art_impl_001', version: 2, status: 'available' },
    ],
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
      <ImplementationReviewDecisionBarContainer workflowRunId={RUN_ID} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('ImplementationReviewDecisionBarContainer', () => {
  it('renders the ready bar with all three actions for a WaitingForReview run', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowed()),
      http.get(DETAIL_URL, () => reviewDetail()),
    );
    renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'ready',
      ),
    );
    expect(screen.getByRole('button', { name: 'Accept implementation' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject with feedback' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Take over' })).toBeInTheDocument();
    expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'implementation_review',
    );
  });

  it('accept fires with the impl artifact version + stamp context version and logs field-only impl.acceptSubmit', async () => {
    const info = vi.spyOn(console, 'info').mockImplementation(() => {});
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get(ALLOWED_URL, () => allowed()),
      http.get(DETAIL_URL, () => reviewDetail()),
      http.post(ACCEPT_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );
    const user = userEvent.setup();
    renderContainer();
    await user.click(await screen.findByRole('button', { name: 'Accept implementation' }));

    await waitFor(() => expect(body).toBeDefined());
    expect(body).toMatchObject({
      artifactId: 'art_impl_001',
      expectedArtifactVersion: 2,
      expectedContextBundleVersion: 1,
    });
    await waitFor(() =>
      expect(info).toHaveBeenCalledWith({ event: 'impl.acceptSubmit', currentState: 'Executing' }),
    );
    const call = info.mock.calls.find(
      (args) => (args[0] as { event?: string }).event === 'impl.acceptSubmit',
    );
    expect(Object.keys(call?.[0] as object).sort()).toEqual(['currentState', 'event']);
  });

  it('reject fires with reasonText + developer taggedFeedback and logs the non-PII enum (never reasonText)', async () => {
    const info = vi.spyOn(console, 'info').mockImplementation(() => {});
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get(ALLOWED_URL, () => allowed()),
      http.get(DETAIL_URL, () => reviewDetail()),
      http.post(REJECT_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );
    const user = userEvent.setup();
    renderContainer();
    await user.click(await screen.findByRole('button', { name: 'Reject with feedback' }));
    await user.type(screen.getByTestId('approval-rejection-reason'), 'secret reviewer note');
    await user.click(screen.getByLabelText('Incomplete implementation'));
    await user.click(screen.getByRole('button', { name: /confirm rejection/i }));

    await waitFor(() => expect(body).toBeDefined());
    expect(body).toMatchObject({
      artifactId: 'art_impl_001',
      expectedArtifactVersion: 2,
      expectedContextBundleVersion: 1,
      reasonText: 'secret reviewer note',
      taggedFeedback: 'INCOMPLETE_IMPLEMENTATION',
    });
    await waitFor(() =>
      expect(info).toHaveBeenCalledWith({
        event: 'impl.rejectSubmit',
        taggedFeedback: 'INCOMPLETE_IMPLEMENTATION',
        currentState: 'Executing',
      }),
    );
    // T-LOG-PII — reasonText is NEVER logged.
    const logged = JSON.stringify(info.mock.calls);
    expect(logged).not.toContain('secret reviewer note');
  });

  it('takeover fires reasonText only (no versions/actor), captures preservedPrReference, never logs it', async () => {
    const info = vi.spyOn(console, 'info').mockImplementation(() => {});
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get(ALLOWED_URL, () => allowed()),
      http.get(DETAIL_URL, () => reviewDetail()),
      http.post(TAKEOVER_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'TakenOver',
          recoveryActionId: 'rec_001',
          replayed: false,
          preservedPrReference: 'octo/repo#42',
          correlationId: 'corr_001',
        });
      }),
    );
    const user = userEvent.setup();
    renderContainer();
    await user.click(await screen.findByRole('button', { name: 'Take over' }));
    await user.type(await screen.findByTestId('takeover-reason'), 'manual continuation');
    await user.click(screen.getByRole('button', { name: /confirm takeover/i }));

    await waitFor(() => expect(body).toBeDefined());
    expect(body).toEqual({ reasonText: 'manual continuation' });

    // AC7 — the captured PR ref drives the "Continue in PR" affordance.
    await waitFor(() =>
      expect(screen.getByTestId('takeover-continue-pr')).toHaveAttribute(
        'href',
        'https://github.com/octo/repo/pull/42',
      ),
    );
    // T-LOG-PII — currentState only; preservedPrReference is NEVER logged.
    await waitFor(() =>
      expect(info).toHaveBeenCalledWith({
        event: 'impl.takeoverSubmit',
        currentState: 'TakenOver',
      }),
    );
    expect(JSON.stringify(info.mock.calls)).not.toContain('octo/repo#42');
  });

  it('APPROVAL_VERSION_MISMATCH on accept → stale state + refetch + impl.versionMismatch log', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    let allowedHits = 0;
    server.use(
      http.get(ALLOWED_URL, () => {
        allowedHits += 1;
        return allowed();
      }),
      http.get(DETAIL_URL, () => reviewDetail()),
      http.post(ACCEPT_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Version mismatch',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/accept-implementation`,
            code: 'APPROVAL_VERSION_MISMATCH',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderContainer();
    await user.click(await screen.findByRole('button', { name: 'Accept implementation' }));

    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'stale',
      ),
    );
    await waitFor(() =>
      expect(warn).toHaveBeenCalledWith({
        event: 'impl.versionMismatch',
        code: 'APPROVAL_VERSION_MISMATCH',
      }),
    );
    const hitsAfter = allowedHits;
    await waitFor(() => expect(allowedHits).toBeGreaterThan(1));
    expect(hitsAfter).toBeGreaterThan(1);
  });

  it('surfaces a load error (with Refresh) and logs impl.allowedActionsLoadError', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    server.use(
      http.get(ALLOWED_URL, () => HttpResponse.json({ message: 'boom' }, { status: 500 })),
      http.get(DETAIL_URL, () => reviewDetail()),
    );
    renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'error',
      ),
    );
    expect(screen.getByTestId('approval-load-error')).toBeInTheDocument();
    await waitFor(() =>
      expect(warn).toHaveBeenCalledWith(
        expect.objectContaining({ event: 'impl.allowedActionsLoadError' }),
      ),
    );
  });

  it('a11y — the ready bar has zero axe violations', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowed()),
      http.get(DETAIL_URL, () => reviewDetail()),
    );
    const { container } = renderContainer();
    await screen.findByRole('button', { name: 'Accept implementation' });
    await expectNoA11yViolations(container);
  });
});
