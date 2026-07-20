/**
 * Story 4.23 (Task 11, AC11) — `ReconciliationDialog` component tests.
 *
 * MSW-backed conflict GET + reconcile POST inside a per-test QueryClientProvider. Covers: side-by-
 * side snapshot diff + raw/unparseable fallback, recommended pre-selection, confirm gating, the risky
 * inline warning, submission (role + Idempotency-Key), the CONFLICT_ALREADY_RESOLVED stale UI, the
 * Esc/Cancel-with-dirty-reason discard prompt, the mobile stacked layout, and axe (desktop + mobile).
 */
import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { installMatchMedia, uninstallMatchMedia } from '@/test/matchMedia';
import { expectNoA11yViolations } from '@/test/a11y/axe';

import { ReconciliationDialog } from './ReconciliationDialog';

const RUN_ID = 'run_reconcile_ui_001';
const CONFLICT_ID = 'icf_ui_001';
const CONFLICT_URL = 'http://localhost/api/v1/integration-conflicts/:conflictId';
const RECONCILE_URL = 'http://localhost/api/v1/workflows/:runId/reconcile';

interface ConflictOverrides {
  internalStateSnapshot?: string | null;
  externalStateSnapshot?: string | null;
  suggestedDecisions?: Array<{ decision: string; safety: 'safe' | 'risky' }>;
}

function conflictDetail(overrides: ConflictOverrides = {}) {
  return {
    conflictId: CONFLICT_ID,
    workflowRunId: RUN_ID,
    conflictCategory: 'external_state_advanced',
    integrationType: 'github_pr',
    externalRef: 'octo/repo#7',
    internalStateSnapshot:
      overrides.internalStateSnapshot !== undefined
        ? overrides.internalStateSnapshot
        : '{"state":"open","branch":"feat/x","secretMeta":"hidden"}',
    externalStateSnapshot:
      overrides.externalStateSnapshot !== undefined
        ? overrides.externalStateSnapshot
        : '{"state":"merged","commitSha":"abc123"}',
    suggestedDecisions: overrides.suggestedDecisions ?? [
      { decision: 'accept_external_state', safety: 'safe' },
      { decision: 'accept_internal_state', safety: 'risky' },
    ],
  };
}

function client() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

function Harness({ onClose = vi.fn() }: { onClose?: () => void }) {
  const [open, setOpen] = useState(true);
  return (
    <QueryClientProvider client={client()}>
      <ReconciliationDialog
        workflowRunId={RUN_ID}
        conflictId={CONFLICT_ID}
        open={open}
        onClose={() => {
          setOpen(false);
          onClose();
        }}
      />
    </QueryClientProvider>
  );
}

/** A harness with a real trigger button so focus-restore-on-close (AC7) can be asserted. */
function TriggerHarness({ onClose = vi.fn() }: { onClose?: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <QueryClientProvider client={client()}>
      <button type="button" data-testid="reconcile-trigger" onClick={() => setOpen(true)}>
        Open reconcile
      </button>
      <ReconciliationDialog
        workflowRunId={RUN_ID}
        conflictId={CONFLICT_ID}
        open={open}
        onClose={() => {
          setOpen(false);
          onClose();
        }}
      />
    </QueryClientProvider>
  );
}

afterEach(() => {
  cleanup();
  uninstallMatchMedia();
  vi.restoreAllMocks();
});

describe('ReconciliationDialog (story 4.23)', () => {
  it('renders side-by-side snapshots with field-level diff highlighting (AC2/AC3)', async () => {
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    render(<Harness />);

    const internal = await screen.findByTestId('reconciliation-snapshot-internal');
    const external = await screen.findByTestId('reconciliation-snapshot-external');
    // internal: State open (Changed), Branch feat/x (Removed — external dropped it)
    expect(internal).toHaveTextContent('open');
    expect(within(internal).getByText('Branch').closest('[data-diff-status]')).toHaveAttribute(
      'data-diff-status',
      'removed',
    );
    // external: State merged (Changed), Commit abc123 (Added)
    expect(external).toHaveTextContent('merged');
    expect(within(external).getByText('Commit').closest('[data-diff-status]')).toHaveAttribute(
      'data-diff-status',
      'added',
    );
    // unknown field routed to the Raw metadata section, not a bare row.
    expect(within(internal).getByTestId('reconciliation-snapshot-internal-raw')).toHaveTextContent(
      'secretMeta',
    );
  });

  it('degrades to a plain-text fallback for a null / unparseable snapshot (AC3)', async () => {
    server.use(
      http.get(CONFLICT_URL, () =>
        HttpResponse.json(conflictDetail({ internalStateSnapshot: null })),
      ),
    );
    render(<Harness />);
    const internal = await screen.findByTestId('reconciliation-snapshot-internal');
    expect(internal).toHaveAttribute('data-snapshot-ok', 'false');
    expect(
      within(internal).getByTestId('reconciliation-snapshot-internal-fallback'),
    ).toBeInTheDocument();
  });

  it('pre-selects the recommended (first) decision and tags it (AC4)', async () => {
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    render(<Harness />);
    const recommended = await screen.findByTestId('reconciliation-decision-accept_external_state');
    expect(within(recommended).getByRole('radio')).toBeChecked();
    expect(screen.getByTestId('reconciliation-recommended')).toBeInTheDocument();
    // the safe option carries the SAFE chip, the risky one the RISKY chip.
    expect(screen.getByTestId('reconciliation-safety-accept_external_state')).toHaveTextContent(
      'SAFE',
    );
    expect(screen.getByTestId('reconciliation-safety-accept_internal_state')).toHaveTextContent(
      'RISKY',
    );
  });

  it('surfaces an inline warning when a risky option is selected (AC4)', async () => {
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    const user = userEvent.setup();
    render(<Harness />);
    const risky = await screen.findByTestId('reconciliation-decision-accept_internal_state');
    await user.click(within(risky).getByRole('radio'));
    const consequence = screen.getByTestId('reconciliation-consequence');
    expect(consequence).toHaveAttribute('data-risky', 'true');
    expect(consequence).toHaveTextContent(/warning/i);
  });

  it('keeps Confirm disabled until a decision AND a reason are set (AC5/NFR19)', async () => {
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    const user = userEvent.setup();
    render(<Harness />);
    await screen.findByTestId('reconciliation-decisions');
    // A decision is pre-selected but the reason is empty → confirm disabled.
    const confirm = screen.getByRole('button', { name: 'Confirm reconcile' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByTestId('reconciliation-reason'), 'external is authoritative');
    expect(confirm).toBeEnabled();
  });

  it('submits via useReconcileWorkflow with role + Idempotency-Key (AC5)', async () => {
    let body: Record<string, unknown> | undefined;
    let idempotencyKey: string | null = null;
    server.use(
      http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())),
      http.post(RECONCILE_URL, async ({ request }) => {
        idempotencyKey = request.headers.get('Idempotency-Key');
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Executing',
          recoveryActionId: 'rcv_1',
          replayed: false,
          resolvedConflictId: CONFLICT_ID,
        });
      }),
    );
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<Harness onClose={onClose} />);
    await screen.findByTestId('reconciliation-decisions');
    await user.type(screen.getByTestId('reconciliation-reason'), 'external merge wins');
    await user.click(screen.getByRole('button', { name: 'Confirm reconcile' }));

    await waitFor(() => expect(idempotencyKey).toBeTruthy());
    expect(body).toMatchObject({
      conflictId: CONFLICT_ID,
      resolutionDecision: 'accept_external_state',
      reasonText: 'external merge wins',
      role: 'workflow_owner',
    });
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    // the recorded announcement is spoken from the persistent live region.
    await waitFor(() =>
      expect(screen.getByTestId('reconcile-announcer')).toHaveTextContent(/recorded/i),
    );
  });

  it('renders the CONFLICT_ALREADY_RESOLVED stale state + refresh CTA (AC6)', async () => {
    let conflictHits = 0;
    server.use(
      http.get(CONFLICT_URL, () => {
        conflictHits += 1;
        return HttpResponse.json(conflictDetail());
      }),
      http.post(RECONCILE_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Already resolved',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/reconcile`,
            code: 'CONFLICT_ALREADY_RESOLVED',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );
    const user = userEvent.setup();
    render(<Harness />);
    await screen.findByTestId('reconciliation-decisions');
    await user.type(screen.getByTestId('reconciliation-reason'), 'note');
    await user.click(screen.getByRole('button', { name: 'Confirm reconcile' }));

    const errorBox = await screen.findByTestId('reconciliation-error');
    expect(errorBox).toHaveAttribute('data-error-code', 'CONFLICT_ALREADY_RESOLVED');
    const hitsBefore = conflictHits;
    await user.click(screen.getByTestId('reconcile-refresh'));
    await waitFor(() => expect(conflictHits).toBeGreaterThan(hitsBefore));
  });

  it('prompts to discard when Cancel is pressed with an edited reason (AC7)', async () => {
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<Harness onClose={onClose} />);
    await screen.findByTestId('reconciliation-decisions');
    await user.type(screen.getByTestId('reconciliation-reason'), 'important note');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    // The discard prompt intercepts — the dialog is NOT closed yet.
    expect(screen.getByTestId('reconcile-discard-prompt')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Discard note' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('intercepts the Esc key with an edited reason and shows the discard prompt (AC7 — custom, not the base immediate-Esc)', async () => {
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<Harness onClose={onClose} />);
    await screen.findByTestId('reconciliation-decisions');
    await user.type(screen.getByTestId('reconciliation-reason'), 'important note');
    // Esc (not the Cancel button) must hit the same dirty-reason interception.
    await user.keyboard('{Escape}');
    expect(screen.getByTestId('reconcile-discard-prompt')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Discard note' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('moves focus into the dialog on open and returns it to the trigger on close (AC7/UX-DR18)', async () => {
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    const user = userEvent.setup();
    render(<TriggerHarness />);
    const trigger = screen.getByTestId('reconcile-trigger');
    await user.click(trigger);
    await screen.findByTestId('reconciliation-decisions');
    // On open, focus is moved INTO the dialog (radix focus-scope).
    const dialog = screen.getByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
    // Clean close (no dirty reason) → focus RETURNS to the triggering element.
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('closes immediately (no discard prompt) when Cancel is pressed with a clean reason (AC7)', async () => {
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<Harness onClose={onClose} />);
    await screen.findByTestId('reconciliation-decisions');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByTestId('reconcile-discard-prompt')).not.toBeInTheDocument();
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('renders a stacked (not side-by-side) layout on mobile (AC9)', async () => {
    installMatchMedia(400);
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    render(<Harness />);
    const snapshots = await screen.findByTestId('reconciliation-snapshots');
    expect(snapshots.className).toContain('grid-cols-1');
    expect(snapshots.className).not.toContain('grid-cols-2');
  });

  it('has zero axe violations in desktop and mobile layouts (AC10/AC11)', async () => {
    server.use(http.get(CONFLICT_URL, () => HttpResponse.json(conflictDetail())));
    const desktop = render(<Harness />);
    await screen.findByTestId('reconciliation-decisions');
    await expectNoA11yViolations(document.body);
    desktop.unmount();
    cleanup();

    installMatchMedia(400);
    render(<Harness />);
    await screen.findByTestId('reconciliation-decisions');
    await expectNoA11yViolations(document.body);
  });
});
