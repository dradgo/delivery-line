/**
 * Story 2.19 (Task 6) — `ApprovalDecisionBarContainer` integration + logging tests.
 *
 * Real wiring (NOT fabrication — the 2.17/2.18 discipline): MSW serves the LIVE
 * allowed-actions + detail reads and the approve/reject mutations. The firing path is
 * exercised by serving a forward-compat `artifactId` on the spec `latestArtifacts`
 * entry (the documented `resolveSpecArtifactId` seam, T-ARTIFACTID) — never invented.
 * Asserts the field-only structured logs incl. an EXACT-key-set negative test (no
 * `reasonText`/`artifactId`/`workflowRunId`/`message` — T-LOG-PII).
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

import { ApprovalDecisionBarContainer } from './ApprovalDecisionBarContainer';

const RUN_ID = 'run_appr_demo_001';
const ALLOWED_URL = `http://localhost/api/v1/workflows/:runId/allowed-actions`;
const DETAIL_URL = `http://localhost/api/v1/workflows/:runId`;
const APPROVE_URL = `http://localhost/api/v1/workflows/:runId/approve-spec`;
const REJECT_URL = `http://localhost/api/v1/workflows/:runId/reject-spec`;

const VERSION_STAMP = {
  currentSpecArtifactVersion: 3,
  currentContextBundleVersion: 1,
  lastEventId: 'evt_appr_100',
  workflowState: 'WaitingForSpecApproval',
};

function allowedActions(actions: string[] = ['approve_spec', 'reject_spec']) {
  return HttpResponse.json({ actions, versionStamp: VERSION_STAMP });
}

/** Detail carrying the forward-compat `artifactId` on the spec artifact (the live seam). */
function detailWithArtifact(artifactId: string | undefined = 'art_spec_appr_001') {
  return HttpResponse.json({
    workflowRunId: RUN_ID,
    currentState: 'WaitingForSpecApproval',
    currentActorIdentity: 'Alex',
    latestArtifacts:
      artifactId !== undefined
        ? [{ artifactType: 'spec', status: 'pending', version: 3, artifactId }]
        : [{ artifactType: 'spec', status: 'pending', version: 3 }],
  });
}

function problem(code: string, status: number, retryable: boolean) {
  return HttpResponse.json(
    {
      type: 'about:blank',
      title: code,
      status,
      detail: 'x',
      instance: `/api/v1/workflows/${RUN_ID}/approve-spec`,
      code,
      retryable,
    },
    { status, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
  );
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
      <ApprovalDecisionBarContainer workflowRunId={RUN_ID} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('ApprovalDecisionBarContainer — live read mapping', () => {
  it('maps live allowed-actions + detail into a ready bar when artifactId resolves', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowedActions()),
      http.get(DETAIL_URL, () => detailWithArtifact()),
    );
    renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'ready',
      ),
    );
    expect(screen.getByRole('button', { name: 'Approve specification' })).toBeInTheDocument();
  });

  it('renders blocked when no artifactId resolves — the dormancy boundary (T-ARTIFACTID)', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowedActions()),
      http.get(DETAIL_URL, () => detailWithArtifact(undefined)),
    );
    renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'blocked',
      ),
    );
    expect(screen.getByTestId('approval-blocked-reason')).toHaveTextContent(/not yet available/i);
  });
});

describe('ApprovalDecisionBarContainer — firing path + logging (field-only, T-LOG-PII)', () => {
  it('approve fires with derived versions + artifactId and logs approval.approveSubmit (exact keys)', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get(ALLOWED_URL, () => allowedActions()),
      http.get(DETAIL_URL, () => detailWithArtifact()),
      http.post(APPROVE_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => undefined);
    renderContainer();
    fireEvent.click(await screen.findByRole('button', { name: 'Approve specification' }));

    await waitFor(() => expect(body).toBeDefined());
    expect(body).toMatchObject({
      artifactId: 'art_spec_appr_001',
      expectedArtifactVersion: 3,
      expectedContextBundleVersion: 1,
    });
    await waitFor(() =>
      expect(infoSpy).toHaveBeenCalledWith({
        event: 'approval.approveSubmit',
        currentState: 'Executing',
      }),
    );
    // EXACT-key-set negative test (T-LOG-PII): no artifactId/reason/run id/message.
    const call = infoSpy.mock.calls.find(
      (c) => (c[0] as { event?: string }).event === 'approval.approveSubmit',
    );
    expect(Object.keys(call?.[0] as object).sort()).toEqual(['currentState', 'event']);
  });

  it('reject fires with UPPERCASE taggedFeedback and logs approval.rejectSubmit WITHOUT reasonText', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get(ALLOWED_URL, () => allowedActions()),
      http.get(DETAIL_URL, () => detailWithArtifact()),
      http.post(REJECT_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'WaitingForSpecApproval' });
      }),
    );
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => undefined);
    renderContainer();

    fireEvent.click(await screen.findByRole('button', { name: 'Reject with feedback' }));
    const dialog = screen.getByTestId('approval-rejection-dialog');
    fireEvent.change(screen.getByTestId('approval-rejection-reason'), {
      target: { value: 'secret rationale text' },
    });
    fireEvent.click(within(dialog).getByLabelText('Unclear specification'));
    fireEvent.click(within(dialog).getByRole('button', { name: /confirm rejection/i }));

    await waitFor(() => expect(body).toBeDefined());
    expect(body).toMatchObject({
      reasonText: 'secret rationale text',
      taggedFeedback: 'UNCLEAR_SPECIFICATION',
    });
    await waitFor(() =>
      expect(infoSpy).toHaveBeenCalledWith({
        event: 'approval.rejectSubmit',
        taggedFeedback: 'UNCLEAR_SPECIFICATION',
        currentState: 'WaitingForSpecApproval',
      }),
    );
    const call = infoSpy.mock.calls.find(
      (c) => (c[0] as { event?: string }).event === 'approval.rejectSubmit',
    );
    const logged = call?.[0] as Record<string, unknown>;
    expect(Object.keys(logged).sort()).toEqual(['currentState', 'event', 'taggedFeedback']);
    expect(JSON.stringify(logged)).not.toContain('secret rationale text');
  });

  it('APPROVAL_VERSION_MISMATCH → stale state + approval.versionMismatch warn (field-only)', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowedActions()),
      http.get(DETAIL_URL, () => detailWithArtifact()),
      http.post(APPROVE_URL, () => problem('APPROVAL_VERSION_MISMATCH', 409, false)),
    );
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    renderContainer();
    fireEvent.click(await screen.findByRole('button', { name: 'Approve specification' }));

    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'stale',
      ),
    );
    expect(warnSpy).toHaveBeenCalledWith({
      event: 'approval.versionMismatch',
      code: 'APPROVAL_VERSION_MISMATCH',
    });
  });

  it('allowed-actions load failure logs approval.allowedActionsLoadError (exact keys)', async () => {
    server.use(
      http.get(ALLOWED_URL, () => problem('RUN_NOT_FOUND', 404, false)),
      http.get(DETAIL_URL, () => detailWithArtifact()),
    );
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    renderContainer();

    await waitFor(() =>
      expect(warnSpy).toHaveBeenCalledWith({
        event: 'approval.allowedActionsLoadError',
        code: 'RUN_NOT_FOUND',
        transport: false,
      }),
    );
  });

  it('allowed-actions load failure surfaces a load-error/retry UI, not the benign "not yet available" block', async () => {
    server.use(
      http.get(ALLOWED_URL, () => problem('RUN_NOT_FOUND', 404, false)),
      http.get(DETAIL_URL, () => detailWithArtifact()),
    );
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    renderContainer();

    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'error',
      ),
    );
    expect(screen.getByTestId('approval-load-error')).toBeInTheDocument();
    expect(screen.queryByTestId('approval-blocked-reason')).not.toBeInTheDocument();
  });
});

/**
 * Story 2.25 (Task 2 — AC1/AC2) — a11y over the LIVE-mapped container states. Drives
 * the same MSW-served reads as above, waits for each documented `data-approval-bar-state`
 * to settle, then runs an axe scan + asserts keyboard operability of the live controls.
 */
describe('ApprovalDecisionBarContainer a11y (story 2.25)', () => {
  it('AC2 — the live "ready" bar has no axe violations', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowedActions()),
      http.get(DETAIL_URL, () => detailWithArtifact()),
    );
    const { container } = renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'ready',
      ),
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — the live "blocked" (dormancy boundary) bar has no axe violations', async () => {
    server.use(
      http.get(ALLOWED_URL, () => allowedActions()),
      http.get(DETAIL_URL, () => detailWithArtifact(undefined)),
    );
    const { container } = renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'blocked',
      ),
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — the live allowed-actions load-error bar has no axe violations', async () => {
    server.use(
      http.get(ALLOWED_URL, () => problem('RUN_NOT_FOUND', 404, false)),
      http.get(DETAIL_URL, () => detailWithArtifact()),
    );
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { container } = renderContainer();
    await waitFor(() =>
      expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
        'data-approval-bar-state',
        'error',
      ),
    );
    await expectNoA11yViolations(container);
  });

  it('AC1 — the live ready bar exposes a Tab-reachable Approve that fires the wired mutation', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get(ALLOWED_URL, () => allowedActions()),
      http.get(DETAIL_URL, () => detailWithArtifact()),
      http.post(APPROVE_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Executing' });
      }),
    );
    vi.spyOn(console, 'info').mockImplementation(() => undefined);
    const user = userEvent.setup();
    renderContainer();
    const approve = await screen.findByRole('button', { name: 'Approve specification' });

    await user.tab();
    expect(document.activeElement).toBe(approve);
    await user.keyboard('{Enter}');
    await waitFor(() => expect(body).toBeDefined());
  });
});
