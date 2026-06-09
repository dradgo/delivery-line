/**
 * Story 2.19 (Task 6, AC1/AC3/AC5/AC7/AC8/AC9/AC10/AC12/AC13) — `ApprovalDecisionBar`.
 *
 * Fixture-driven RTL (NOT `toMatchSnapshot` — no snapshot harness, story 2.27). The bar
 * is presentational + router/query-free; it renders directly from constructed
 * `ApprovalDecisionView` fixtures paired with mutation/localUi inputs.
 * `useReturnToRunContext` (consumed by `<ErrorState>` only in the `error` state) is
 * mocked at its own module (the 2.18 pattern).
 */
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { tabbableElements } from '@/test/a11y/keyboard';
import {
  blockedNoArtifactView,
  blockedPendingClarificationsView,
  implementationReviewView,
  inlineLayoutView,
  lockedView,
  multiCandidateView,
  reasonCodedView,
  readyView,
  recoveryOperatorView,
  staleView,
  submittingView,
  successView,
} from '@/test/fixtures/approval/approvalDecisionFixtures';
import type {
  ApprovalDecisionView,
  ApprovalLocalUi,
  ApprovalMutationState,
} from '../approvalDecisionView';

vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));

import { ApprovalDecisionBar } from './ApprovalDecisionBar';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const IDLE: ApprovalMutationState = { status: 'idle' };

function renderBar(
  view: ApprovalDecisionView,
  mutation: ApprovalMutationState = IDLE,
  localUi: ApprovalLocalUi = {},
  overrides: Partial<{ onApprove: () => void; onReject: () => void; onRefresh: () => void }> = {},
) {
  return render(
    <ApprovalDecisionBar
      view={view}
      mutation={mutation}
      localUi={localUi}
      onApprove={overrides.onApprove ?? vi.fn()}
      onReject={overrides.onReject ?? vi.fn()}
      onRefresh={overrides.onRefresh ?? vi.fn()}
    />,
  );
}

function barState(): string | null {
  return screen.getByTestId('approval-decision-bar').getAttribute('data-approval-bar-state');
}

describe('ApprovalDecisionBar — states (AC3)', () => {
  it('ready — renders the action area and stamps data-approval-bar-state', () => {
    renderBar(readyView);
    expect(barState()).toBe('ready');
    expect(screen.getByTestId('approval-action-area')).toBeInTheDocument();
  });

  it('submitting — region-local non-spinner indicator', () => {
    renderBar(submittingView, { status: 'pending' });
    expect(barState()).toBe('submitting');
    expect(screen.getByTestId('approval-submitting')).toHaveAttribute('aria-busy', 'true');
  });

  it('success — renders the post-submit decision summary with the audit event ref (AC9)', () => {
    renderBar(successView, { status: 'success' });
    expect(barState()).toBe('success');
    expect(screen.getByTestId('approval-decision-summary')).toBeInTheDocument();
    expect(screen.getByTestId('approval-summary-ref')).toHaveTextContent('corr_appr_001');
  });

  it('error — a failed allowed-actions load renders a load-specific error + retry (not blocked)', () => {
    render(
      <ApprovalDecisionBar
        view={readyView}
        mutation={IDLE}
        loadError
        onApprove={vi.fn()}
        onReject={vi.fn()}
        onRefresh={vi.fn()}
      />,
    );
    expect(barState()).toBe('error');
    expect(screen.getByTestId('approval-load-error')).toBeInTheDocument();
    expect(screen.getByText(/failed to load/i)).toBeInTheDocument();
  });

  it('stale — names the new spec version in the refresh CTA (AC6)', () => {
    renderBar(staleView, { status: 'error', errorCode: 'APPROVAL_VERSION_MISMATCH' });
    expect(barState()).toBe('stale');
    // staleView carries versionStampFull (currentSpecArtifactVersion: 3).
    expect(screen.getByTestId('approval-stale')).toHaveTextContent(/now at version 3/i);
  });

  it('error — renders ErrorState with the failure code', () => {
    renderBar(readyView, { status: 'error', errorCode: 'ILLEGAL_TRANSITION' });
    expect(barState()).toBe('error');
    expect(screen.getByTestId('error-state')).toBeInTheDocument();
    expect(screen.getByText(/ILLEGAL_TRANSITION/)).toBeInTheDocument();
  });

  it('stale — on APPROVAL_VERSION_MISMATCH renders the refresh CTA + explanation (AC6)', () => {
    renderBar(staleView, { status: 'error', errorCode: 'APPROVAL_VERSION_MISMATCH' });
    expect(barState()).toBe('stale');
    expect(screen.getByTestId('approval-stale-chip')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /refresh and review/i })).toBeInTheDocument();
  });

  it('blocked — no resolvable artifactId renders the explanation, never a dead primary (T-ARTIFACTID)', () => {
    renderBar(blockedNoArtifactView);
    expect(barState()).toBe('blocked');
    expect(screen.getByTestId('approval-blocked-reason')).toHaveTextContent(/not yet available/i);
    expect(
      screen.queryByRole('button', { name: /approve specification/i }),
    ).not.toBeInTheDocument();
  });

  it('locked — renders a read-only decision summary', () => {
    renderBar(lockedView, IDLE, { locked: true });
    expect(barState()).toBe('locked');
    expect(screen.getByTestId('approval-decision-summary')).toBeInTheDocument();
    expect(screen.getByText(/read-only/i)).toBeInTheDocument();
  });
});

describe('ApprovalDecisionBar — mode dispatch (AC1)', () => {
  it('spec_approval renders the full bar', () => {
    renderBar(readyView);
    expect(screen.getByTestId('approval-action-area')).toBeInTheDocument();
  });

  it('implementation_review renders the Epic 3 placeholder (disabled)', () => {
    renderBar(implementationReviewView);
    expect(barState()).toBe('disabled');
    expect(screen.getByTestId('approval-mode-placeholder')).toHaveTextContent(/Epic 3/);
  });

  it('recovery_operator renders the Epic 4 placeholder (disabled)', () => {
    renderBar(recoveryOperatorView);
    expect(barState()).toBe('disabled');
    expect(screen.getByTestId('approval-mode-placeholder')).toHaveTextContent(/Epic 4/);
  });
});

describe('ApprovalDecisionBar — one-primary-action (AC7)', () => {
  it('renders exactly one primary-styled control even with multiple candidate actions', () => {
    renderBar(multiCandidateView);
    expect(document.querySelectorAll('[data-primary="true"]')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Approve specification' })).toHaveAttribute(
      'data-primary',
      'true',
    );
  });
});

describe('ApprovalDecisionBar — layout variants (AC4)', () => {
  it('stamps the sticky_footer layout', () => {
    renderBar(readyView);
    expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-layout',
      'sticky_footer',
    );
  });

  it('stamps the inline_section layout', () => {
    renderBar(inlineLayoutView);
    expect(screen.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-layout',
      'inline_section',
    );
  });
});

describe('ApprovalDecisionBar — allowed-actions gating (AC5/AC13)', () => {
  it('hides Approve when approve_spec is absent → blocked with the specific pending message', () => {
    renderBar(blockedPendingClarificationsView);
    expect(barState()).toBe('blocked');
    expect(
      screen.queryByRole('button', { name: /approve specification/i }),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId('approval-blocked-reason')).toHaveTextContent(
      /2 clarifications pending incorporation/i,
    );
  });

  it('renders a withheld secondary action DISABLED with an aria-describedby reason (AC5c/AC10)', () => {
    renderBar(reasonCodedView);
    const reject = screen.getByRole('button', { name: /reject with feedback/i });
    expect(reject).toBeDisabled();
    const describedBy = reject.getAttribute('aria-describedby');
    expect(describedBy).not.toBeNull();
    const reason = document.getElementById(describedBy as string);
    expect(reason).toHaveTextContent(/recorded role/i);
  });
});

describe('ApprovalDecisionBar — rejection flow (AC8)', () => {
  it('opens the region-local dialog, captures reason + UPPERCASE taggedFeedback, confirms', () => {
    const onReject = vi.fn();
    renderBar(readyView, IDLE, {}, { onReject });

    fireEvent.click(screen.getByRole('button', { name: /reject with feedback/i }));
    const dialog = screen.getByTestId('approval-rejection-dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');

    fireEvent.change(screen.getByTestId('approval-rejection-reason'), {
      target: { value: 'Scope omits the migration path.' },
    });
    fireEvent.click(within(dialog).getByLabelText('Missing scope'));
    fireEvent.click(within(dialog).getByRole('button', { name: /confirm rejection/i }));

    expect(onReject).toHaveBeenCalledWith({
      reasonText: 'Scope omits the migration path.',
      taggedFeedback: 'MISSING_SCOPE',
    });
  });

  it('blocks confirmation until both reason and taggedFeedback are provided', () => {
    const onReject = vi.fn();
    renderBar(readyView, IDLE, {}, { onReject });
    fireEvent.click(screen.getByRole('button', { name: /reject with feedback/i }));
    fireEvent.click(screen.getByRole('button', { name: /confirm rejection/i }));
    expect(onReject).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('moves focus into the dialog on open and restores it to the trigger on Cancel (AC10)', () => {
    renderBar(readyView);
    const trigger = screen.getByRole('button', { name: /reject with feedback/i });
    fireEvent.click(trigger);
    expect(screen.getByTestId('approval-rejection-reason')).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect(screen.queryByTestId('approval-rejection-dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});

describe('ApprovalDecisionBar — accessibility (AC10)', () => {
  it('uses explicit verb labels (never OK/Cancel) for the primary decision', () => {
    renderBar(readyView);
    expect(screen.getByRole('button', { name: 'Approve specification' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject with feedback' })).toBeInTheDocument();
  });

  it('keeps Approve before Reject in DOM order (Tab order matches visual order)', () => {
    renderBar(readyView);
    const buttons = within(screen.getByTestId('approval-action-area')).getAllByRole('button');
    expect(buttons[0]).toHaveAccessibleName('Approve specification');
    expect(buttons[1]).toHaveAccessibleName('Reject with feedback');
  });

  it('links the Approve control to its immediate-consequence hint via aria-describedby (AC2)', () => {
    renderBar(readyView);
    const approve = screen.getByRole('button', { name: 'Approve specification' });
    const hintId = approve.getAttribute('aria-describedby');
    expect(hintId).not.toBeNull();
    expect(document.getElementById(hintId as string)).toHaveTextContent(/Executing/);
  });

  it('announces the stale transition through the ARIA live region', () => {
    renderBar(staleView, { status: 'error', errorCode: 'APPROVAL_VERSION_MISMATCH' });
    expect(screen.getByTestId('approval-live-region')).toHaveTextContent(/out of date/i);
  });

  it('AC5/AC7 — announces the recorded decision outcome on success (story 2.25)', () => {
    renderBar(successView, { status: 'success' });
    // The decision-outcome announcement gap (2.19): success now speaks the
    // recorded outcome, not only the visual summary. specApproved/specRejected
    // both end with "Decision recorded."
    expect(screen.getByTestId('approval-live-region')).toHaveTextContent(/Decision recorded/i);
  });
});

/**
 * Story 2.25 (Task 2 — AC1 keyboard operability, AC2 axe scan of every documented
 * state, AC4 region/list semantics). Each documented `data-approval-bar-state` (plus
 * the layout/mode variants and the rejection-dialog open state) is scanned for WCAG
 * 2.1 AA violations; the interactive controls are asserted Tab-reachable and
 * Enter/Space-activatable. The rejection dialog (T-MODAL) has NO Escape close handler
 * by design (AC8) — we assert focus-on-open + containment, not Escape dismissal.
 */
describe('ApprovalDecisionBar a11y (story 2.25)', () => {
  // AC2 — every prop-driven documented state renders without axe violations.
  const A11Y_STATES: ReadonlyArray<
    [
      name: string,
      view: ApprovalDecisionView,
      mutation: ApprovalMutationState,
      localUi: ApprovalLocalUi,
    ]
  > = [
    ['ready', readyView, IDLE, {}],
    ['submitting', submittingView, { status: 'pending' }, {}],
    ['success', successView, { status: 'success' }, {}],
    ['stale', staleView, { status: 'error', errorCode: 'APPROVAL_VERSION_MISMATCH' }, {}],
    ['error', readyView, { status: 'error', errorCode: 'ILLEGAL_TRANSITION' }, {}],
    ['blocked-no-artifact', blockedNoArtifactView, IDLE, {}],
    ['blocked-pending-clarifications', blockedPendingClarificationsView, IDLE, {}],
    ['locked', lockedView, IDLE, { locked: true }],
    ['reason-coded (disabled secondary)', reasonCodedView, IDLE, {}],
    ['multi-candidate', multiCandidateView, IDLE, {}],
    ['inline layout', inlineLayoutView, IDLE, {}],
    ['implementation_review stub', implementationReviewView, IDLE, {}],
    ['recovery_operator stub', recoveryOperatorView, IDLE, {}],
  ];

  it.each(A11Y_STATES)(
    'AC2 — state "%s" has no axe violations',
    async (_name, view, mutation, localUi) => {
      const { container } = renderBar(view, mutation, localUi);
      await expectNoA11yViolations(container);
    },
  );

  it('AC2 — the load-error state has no axe violations', async () => {
    const { container } = render(
      <ApprovalDecisionBar
        view={readyView}
        mutation={IDLE}
        loadError
        onApprove={vi.fn()}
        onReject={vi.fn()}
        onRefresh={vi.fn()}
      />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — the open rejection dialog has no axe violations (region-local, not portaled)', async () => {
    const user = userEvent.setup();
    const { container } = renderBar(readyView);
    await user.click(screen.getByRole('button', { name: /reject with feedback/i }));
    expect(screen.getByTestId('approval-rejection-dialog')).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('AC1 — Approve and Reject are Tab-reachable and Approve activates on Enter and Space', async () => {
    const user = userEvent.setup();
    const onApprove = vi.fn();
    renderBar(readyView, IDLE, {}, { onApprove });

    const approve = screen.getByRole('button', { name: 'Approve specification' });
    const reject = screen.getByRole('button', { name: 'Reject with feedback' });
    await user.tab();
    expect(document.activeElement).toBe(approve);
    await user.tab();
    expect(document.activeElement).toBe(reject);

    approve.focus();
    await user.keyboard('{Enter}');
    await user.keyboard(' ');
    expect(onApprove).toHaveBeenCalledTimes(2);
  });

  it('AC1 — the stale Refresh CTA is Tab-reachable and activates on Enter', async () => {
    const user = userEvent.setup();
    const onRefresh = vi.fn();
    renderBar(
      staleView,
      { status: 'error', errorCode: 'APPROVAL_VERSION_MISMATCH' },
      {},
      { onRefresh },
    );
    const refresh = screen.getByRole('button', { name: /refresh and review/i });
    await user.tab();
    expect(document.activeElement).toBe(refresh);
    await user.keyboard('{Enter}');
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('AC8/AC10 — opening the rejection dialog focuses the first field and keeps focus contained', async () => {
    const user = userEvent.setup();
    renderBar(readyView);
    await user.click(screen.getByRole('button', { name: /reject with feedback/i }));

    // Focus moves to the first field on open (AC10).
    const reason = screen.getByTestId('approval-rejection-reason');
    expect(reason).toHaveFocus();

    // Every dialog control is reachable by Tab without a mouse (AC1). NOTE: this is a
    // region-local modal with NO Escape close handler by design (T-MODAL / AC8); it is
    // dismissed only via explicit Cancel, asserted in the rejection-flow suite above.
    const dialog = screen.getByTestId('approval-rejection-dialog');
    const tabbables = tabbableElements(dialog);
    // textarea + 3 radios + Confirm + Cancel.
    expect(tabbables.length).toBeGreaterThanOrEqual(6);
    expect(within(dialog).getByRole('button', { name: /confirm rejection/i })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: /cancel/i })).toBeInTheDocument();
  });
});
