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
  decisionSummaryTakenOverNoPr,
  implementationReviewReadyView,
  implementationReviewSuccessAcceptedView,
  implementationReviewSuccessTakenOverView,
  implementationReviewTakeoverOnlyView,
  implementationReviewView,
  inlineLayoutView,
  lockedView,
  multiCandidateView,
  reasonCodedView,
  readyView,
  recoveryOperatorView,
  recoveryReadyView,
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
  overrides: Partial<{
    onApprove: () => void;
    onReject: (draft: { reasonText: string; taggedFeedback: string }) => void;
    onRefresh: () => void;
    onRetry: () => void;
    onAccept: () => void;
    onRejectImplementation: (draft: { reasonText: string; taggedFeedback: string }) => void;
    onTakeover: (reasonText: string) => void;
    // Story 4.22 — the recovery safety ranks (drives the single-primary selection).
    recoverySafetyByToken: Partial<Record<string, 'safe' | 'caution' | 'risky'>>;
  }> = {},
) {
  return render(
    <ApprovalDecisionBar
      view={view}
      mutation={mutation}
      localUi={localUi}
      onApprove={overrides.onApprove ?? vi.fn()}
      onReject={overrides.onReject ?? vi.fn()}
      onRefresh={overrides.onRefresh ?? vi.fn()}
      onRetry={overrides.onRetry ?? vi.fn()}
      onAccept={overrides.onAccept ?? vi.fn()}
      onRejectImplementation={overrides.onRejectImplementation ?? vi.fn()}
      onTakeover={overrides.onTakeover ?? vi.fn()}
      recoverySafetyByToken={overrides.recoverySafetyByToken}
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

  it('implementation_review with no actions/artifact renders blocked (real renderer, not a stub)', () => {
    renderBar(implementationReviewView);
    expect(barState()).toBe('blocked');
    // The E3 stub is gone — a real blocked render explains the unavailable accept/reject.
    expect(screen.queryByTestId('approval-mode-placeholder')).not.toBeInTheDocument();
    expect(screen.getByTestId('impl-review-blocked-reason')).toBeInTheDocument();
  });

  it('recovery_operator (View only) — Failed run with no retry action renders no primary CTA', () => {
    renderBar(recoveryOperatorView);
    expect(barState()).toBe('disabled');
    expect(screen.getByTestId('recovery-view-only')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /retry failed step/i })).not.toBeInTheDocument();
    // Scope discipline (AC5) — no deeper recovery controls.
    expect(
      screen.queryByRole('button', { name: /reconcile|resume|rerun/i }),
    ).not.toBeInTheDocument();
  });
});

describe('ApprovalDecisionBar — recovery_operator mode (story 3.30, AC3/AC5)', () => {
  it('ready — renders exactly one primary "Retry failed step" (safety-ranked safe → primary)', () => {
    // Story 4.22 — retry's primacy now comes from the safety ranking; a Failed run ranks retry safe.
    renderBar(recoveryReadyView, IDLE, {}, { recoverySafetyByToken: { retry: 'safe' } });
    expect(barState()).toBe('ready');
    expect(screen.getByTestId('recovery-action-retry')).toBeInTheDocument();
    expect(document.querySelectorAll('[data-decision-primary] button')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Retry failed step' })).toBeInTheDocument();
    // recoveryReadyView carries only `retry`, so the deeper actions are absent for this run.
    expect(
      screen.queryByRole('button', { name: /reconcile|resume|rerun/i }),
    ).not.toBeInTheDocument();
  });

  it('opening the confirm dialog shows the exact AC3 consequence text + Confirm/Cancel', async () => {
    const user = userEvent.setup();
    renderBar(recoveryReadyView);
    await user.click(screen.getByRole('button', { name: 'Retry failed step' }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(
        /Retry will re-execute the last failed step with a fresh runner\. The previous failure will be preserved in the timeline\./,
      ),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Confirm retry' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('Cancel preserves state — onRetry is NOT called', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderBar(recoveryReadyView, IDLE, {}, { onRetry });
    await user.click(screen.getByRole('button', { name: 'Retry failed step' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(onRetry).not.toHaveBeenCalled();
    expect(barState()).toBe('ready');
  });

  it('Confirm retry fires onRetry once', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderBar(recoveryReadyView, IDLE, {}, { onRetry });
    await user.click(screen.getByRole('button', { name: 'Retry failed step' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('submitting — the recovery primary shows the busy state', () => {
    renderBar(recoveryReadyView, { status: 'pending' });
    expect(barState()).toBe('submitting');
    expect(screen.getByTestId('recovery-submitting')).toHaveAttribute('aria-busy', 'true');
  });

  it('success — renders the retry-recorded feedback (never a toast)', () => {
    renderBar(recoveryReadyView, { status: 'success' });
    expect(barState()).toBe('success');
    expect(screen.getByTestId('recovery-retry-success')).toHaveTextContent(
      /preserved in the timeline/i,
    );
  });

  it('error — renders the retry failure with code + refresh', () => {
    renderBar(recoveryReadyView, { status: 'error', errorCode: 'RETRY_NOT_APPLICABLE' });
    expect(barState()).toBe('error');
    expect(screen.getByText(/RETRY_NOT_APPLICABLE/)).toBeInTheDocument();
  });

  it('announces failure-state-entry via the live region (AC7)', () => {
    renderBar(recoveryReadyView);
    expect(screen.getByTestId('approval-live-region')).toHaveTextContent(/this run has failed/i);
  });

  it('a11y — recovery ready view has zero axe violations', async () => {
    const { container } = renderBar(recoveryReadyView);
    await expectNoA11yViolations(container);
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
    ['implementation_review blocked', implementationReviewView, IDLE, {}],
    ['implementation_review ready', implementationReviewReadyView, IDLE, {}],
    [
      'implementation_review success (takeover)',
      implementationReviewSuccessTakenOverView,
      { status: 'success' },
      {},
    ],
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

describe('ApprovalDecisionBar — implementation_review mode (story 3.28)', () => {
  const READY: ApprovalMutationState = { status: 'idle' };

  it('AC1/AC2 — renders all three developer actions with exactly one primary', () => {
    renderBar(implementationReviewReadyView, READY);
    expect(barState()).toBe('ready');
    const accept = screen.getByRole('button', { name: 'Accept implementation' });
    expect(accept).toHaveAttribute('data-primary', 'true');
    expect(screen.getByRole('button', { name: 'Reject with feedback' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Take over' })).toBeInTheDocument();
    const area = screen.getByTestId('impl-review-action-area');
    expect(area.querySelectorAll('[data-primary="true"]')).toHaveLength(1);
  });

  it('AC1 — Accept fires onAccept', async () => {
    const onAccept = vi.fn();
    const user = userEvent.setup();
    renderBar(implementationReviewReadyView, READY, {}, { onAccept });
    await user.click(screen.getByRole('button', { name: 'Accept implementation' }));
    expect(onAccept).toHaveBeenCalledTimes(1);
  });

  it('R1 — Take over remains available even when accept/reject are blocked (no artifact)', () => {
    renderBar(implementationReviewTakeoverOnlyView, READY);
    expect(barState()).toBe('blocked');
    expect(screen.queryByRole('button', { name: 'Accept implementation' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject with feedback' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Take over' })).toBeInTheDocument();
    expect(screen.getByTestId('impl-review-blocked-reason')).toBeInTheDocument();
  });

  it('AC3/R5 — the rejection dialog enforces reasonText + a developer taxonomy selection', async () => {
    const onRejectImplementation = vi.fn();
    const user = userEvent.setup();
    renderBar(implementationReviewReadyView, READY, {}, { onRejectImplementation });
    await user.click(screen.getByRole('button', { name: 'Reject with feedback' }));

    const dialog = screen.getByTestId('approval-rejection-dialog');
    // Developer taxonomy options — NOT the spec set.
    expect(within(dialog).getByText('Incorrect approach')).toBeInTheDocument();
    expect(within(dialog).getByText('Out of scope')).toBeInTheDocument();
    expect(within(dialog).queryByText('Missing scope')).not.toBeInTheDocument();

    // Submitting with empty reason/selection shows the validation error and does not fire.
    await user.click(within(dialog).getByRole('button', { name: /confirm rejection/i }));
    expect(within(dialog).getByRole('alert')).toBeInTheDocument();
    expect(onRejectImplementation).not.toHaveBeenCalled();

    await user.type(screen.getByTestId('approval-rejection-reason'), 'wrong layering');
    await user.click(within(dialog).getByLabelText('Quality issue'));
    await user.click(within(dialog).getByRole('button', { name: /confirm rejection/i }));
    expect(onRejectImplementation).toHaveBeenCalledWith({
      reasonText: 'wrong layering',
      taggedFeedback: 'QUALITY_ISSUE',
    });
  });

  it('AC4/R6 — the takeover dialog renders the verbatim OpenAPI consequence text + enforces reasonText', async () => {
    const onTakeover = vi.fn();
    const user = userEvent.setup();
    renderBar(implementationReviewReadyView, READY, {}, { onTakeover });
    await user.click(screen.getByRole('button', { name: 'Take over' }));

    const dialog = await screen.findByTestId('confirmation-dialog');
    expect(dialog).toHaveTextContent(
      /Stops orchestrator dispatch, cancels all in-flight \+ queued runner executions/,
    );
    expect(dialog).toHaveTextContent(/This action is non-reversible in E3/);

    const confirm = within(dialog).getByRole('button', { name: /confirm takeover/i });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByTestId('takeover-reason'), 'manual continuation');
    expect(confirm).toBeEnabled();
    await user.click(confirm);
    expect(onTakeover).toHaveBeenCalledWith('manual continuation');
  });

  it('AC5/R2 — APPROVAL_VERSION_MISMATCH renders the stale state with a refresh CTA', () => {
    renderBar(implementationReviewReadyView, {
      status: 'error',
      errorCode: 'APPROVAL_VERSION_MISMATCH',
    });
    expect(barState()).toBe('stale');
    expect(screen.getByTestId('approval-stale')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /refresh and review/i })).toBeInTheDocument();
  });

  it('AC6 — a settled decision persists the post-submit summary', () => {
    renderBar(implementationReviewSuccessAcceptedView, { status: 'success' });
    expect(barState()).toBe('success');
    const summary = screen.getByTestId('approval-decision-summary');
    expect(within(summary).getByTestId('approval-summary-chip')).toHaveTextContent('Accepted');
    expect(within(summary).getByTestId('approval-summary-ref')).toHaveTextContent(
      'corr_accept_001',
    );
  });

  it('AC7/R9 — post-takeover renders the "Continue in PR" link + read-only label', () => {
    renderBar(implementationReviewSuccessTakenOverView, { status: 'success' });
    expect(barState()).toBe('success');
    expect(screen.getByTestId('takeover-readonly-label')).toHaveTextContent('Run is taken over');
    const link = screen.getByTestId('takeover-continue-pr');
    expect(link).toHaveAttribute('href', 'https://github.com/octo/repo/pull/42');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('AC7/R9 — post-takeover with NO preserved PR ref renders the label without a link', () => {
    const view = {
      ...implementationReviewSuccessTakenOverView,
      lastDecision: decisionSummaryTakenOverNoPr,
    };
    renderBar(view, { status: 'success' });
    expect(screen.getByTestId('takeover-readonly-label')).toBeInTheDocument();
    expect(screen.queryByTestId('takeover-continue-pr')).not.toBeInTheDocument();
  });

  it('AC8 — the success announcement is routed through the single live region', () => {
    renderBar(implementationReviewSuccessTakenOverView, { status: 'success' });
    expect(screen.getByTestId('approval-live-region')).toHaveTextContent(/taken over/i);
  });

  it('AC8 — all three actions are keyboard reachable', () => {
    renderBar(implementationReviewReadyView, READY);
    const area = screen.getByTestId('impl-review-action-area');
    const labels = tabbableElements(area).map((el) => el.textContent);
    expect(labels).toContain('Accept implementation');
    expect(labels).toContain('Reject with feedback');
    expect(labels).toContain('Take over');
  });

  it('AC10 — the ready implementation_review bar has zero axe violations', async () => {
    const { container } = renderBar(implementationReviewReadyView, READY);
    await expectNoA11yViolations(container);
  });
});
