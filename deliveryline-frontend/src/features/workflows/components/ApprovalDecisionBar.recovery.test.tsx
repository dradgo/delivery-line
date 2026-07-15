/**
 * Story 4.22 (Task 8, AC1–AC7, AC10, AC11) — `ApprovalDecisionBar` recovery_operator FULL
 * activation. Fixture-driven RTL over the presentational bar: the full action set renders only for
 * allowed tokens with exactly one safety-ranked primary; subordinates carry a non-color-only safety
 * affix; resume/pause open ConfirmationDialogs and rerun a RationaleCaptureDialog (+ step select +
 * preview); reconcile/classify invoke their seam callbacks (or render disabled+explained without a
 * handler per OQ-2).
 */
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { recoveryReadyView } from '@/test/fixtures/approval/approvalDecisionFixtures';
import type {
  ApprovalDecisionView,
  ApprovalMutationState,
  DecisionAction,
  RecoverySafetyLevel,
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

type RerunPreview = {
  data?: {
    workflowRunId: string;
    targetStep: string;
    supersededArtifactIds: string[];
    invalidatedApprovalIds: string[];
  };
  isLoading?: boolean;
  isError?: boolean;
};

function renderRecovery(
  actions: DecisionAction[],
  safetyByToken: Partial<Record<DecisionAction, RecoverySafetyLevel>>,
  overrides: Partial<{
    onResume: () => void;
    onRerunFromStep: (vars: {
      targetStep: 'investigating' | 'executing';
      reasonText: string;
    }) => void;
    onPause: (reasonText: string) => void;
    onReconcile: () => void;
    onClassifyFailure: () => void;
    onRerunPreviewRequest: (args: {
      open: boolean;
      targetStep: 'investigating' | 'executing';
    }) => void;
    rerunPreview: RerunPreview;
    mutation: ApprovalMutationState;
  }> = {},
) {
  const view: ApprovalDecisionView = { ...recoveryReadyView, actions };
  return render(
    <ApprovalDecisionBar
      view={view}
      mutation={overrides.mutation ?? IDLE}
      onApprove={vi.fn()}
      onReject={vi.fn()}
      onRefresh={vi.fn()}
      onRetry={vi.fn()}
      recoverySafetyByToken={safetyByToken}
      onResume={overrides.onResume}
      onRerunFromStep={overrides.onRerunFromStep}
      onPause={overrides.onPause}
      onReconcile={overrides.onReconcile}
      onClassifyFailure={overrides.onClassifyFailure}
      rerunPreview={overrides.rerunPreview}
      onRerunPreviewRequest={overrides.onRerunPreviewRequest}
    />,
  );
}

describe('ApprovalDecisionBar recovery_operator full activation (story 4.22)', () => {
  it('renders the full action set only for allowed tokens with exactly one primary (AC1/AC2)', () => {
    renderRecovery(
      ['retry', 'rerun_from_step', 'pause_workflow', 'classify_failure'],
      {
        retry: 'safe',
        rerun_from_step: 'risky',
        pause_workflow: 'caution',
        classify_failure: 'caution',
      },
      { onClassifyFailure: vi.fn() },
    );
    expect(screen.getByRole('button', { name: 'Retry failed step' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rerun from step' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pause run' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Classify failure' })).toBeInTheDocument();
    // An absent token never renders an action (forward-compat, UX-DR6).
    expect(screen.queryByRole('button', { name: 'Resume run' })).not.toBeInTheDocument();
    // AC2 / UX-DR19 — exactly one primary control, and it is the safe-ranked retry.
    expect(document.querySelectorAll('[data-priority="primary"]')).toHaveLength(1);
    expect(screen.getByTestId('recovery-action-retry')).toHaveAttribute('data-priority', 'primary');
  });

  it('subordinate actions carry a non-color-only safety affix (AC2)', () => {
    renderRecovery(['retry', 'rerun_from_step', 'pause_workflow'], {
      retry: 'safe',
      rerun_from_step: 'risky',
      pause_workflow: 'caution',
    });
    expect(screen.getByTestId('recovery-safety-rerun_from_step')).toHaveTextContent('Higher risk');
    expect(screen.getByTestId('recovery-safety-pause_workflow')).toHaveTextContent('Caution');
  });

  it('reconcile/classify entry-point buttons invoke their seam callbacks (AC4/AC7)', async () => {
    const onReconcile = vi.fn();
    const onClassifyFailure = vi.fn();
    const user = userEvent.setup();
    renderRecovery(
      ['reconcile_conflict', 'classify_failure'],
      { reconcile_conflict: 'caution', classify_failure: 'caution' },
      { onReconcile, onClassifyFailure },
    );
    await user.click(screen.getByRole('button', { name: 'Reconcile conflict' }));
    await user.click(screen.getByRole('button', { name: 'Classify failure' }));
    expect(onReconcile).toHaveBeenCalledTimes(1);
    expect(onClassifyFailure).toHaveBeenCalledTimes(1);
  });

  it('reconcile/classify render DISABLED + explained when no handler is wired (AC11 / OQ-2)', () => {
    renderRecovery(['reconcile_conflict', 'classify_failure'], {
      reconcile_conflict: 'caution',
      classify_failure: 'caution',
    });
    const reconcile = screen.getByRole('button', { name: 'Reconcile conflict' });
    expect(reconcile).toBeDisabled();
    expect(reconcile).toHaveAttribute('aria-describedby');
    expect(screen.getByTestId('recovery-action-reconcile_conflict')).toHaveTextContent(
      /upcoming increment/i,
    );
  });

  it('resume opens the ConfirmationDialog and confirm invokes onResume (AC3)', async () => {
    const onResume = vi.fn();
    const user = userEvent.setup();
    renderRecovery(['resume_workflow'], { resume_workflow: 'safe' }, { onResume });
    await user.click(screen.getByRole('button', { name: 'Resume run' }));
    expect(
      await screen.findByText(/Resume will return the run to its prior executing state/),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirm resume' }));
    expect(onResume).toHaveBeenCalledTimes(1);
  });

  it('pause requires a reason before confirm and invokes onPause with it (AC6)', async () => {
    const onPause = vi.fn();
    const user = userEvent.setup();
    renderRecovery(['pause_workflow'], { pause_workflow: 'caution' }, { onPause });
    await user.click(screen.getByRole('button', { name: 'Pause run' }));
    const confirm = await screen.findByRole('button', { name: 'Confirm pause' });
    // Confirm is disabled until a non-blank reason is captured (MISSING_REASON_TEXT guard).
    expect(confirm).toBeDisabled();
    await user.type(screen.getByTestId('pause-reason'), 'pausing to investigate');
    expect(screen.getByRole('button', { name: 'Confirm pause' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Confirm pause' }));
    expect(onPause).toHaveBeenCalledWith('pausing to investigate');
  });

  it('rerun opens the RationaleCaptureDialog with a step select + preview and confirms step+reason (AC5)', async () => {
    const onRerunFromStep = vi.fn();
    const onRerunPreviewRequest = vi.fn();
    const user = userEvent.setup();
    renderRecovery(
      ['rerun_from_step'],
      { rerun_from_step: 'risky' },
      {
        onRerunFromStep,
        onRerunPreviewRequest,
        rerunPreview: {
          data: {
            workflowRunId: 'run_x',
            targetStep: 'investigating',
            supersededArtifactIds: ['art_super_1'],
            invalidatedApprovalIds: ['apr_inv_1'],
          },
        },
      },
    );
    await user.click(screen.getByRole('button', { name: 'Rerun from step' }));
    // Opening the dialog notifies the container to enable + param the preview query (AC5).
    expect(onRerunPreviewRequest).toHaveBeenCalledWith({ open: true, targetStep: 'investigating' });
    // The preview section renders the fetched superseded + invalidated ids.
    const preview = await screen.findByTestId('rerun-preview');
    expect(preview).toHaveTextContent('art_super_1');
    expect(preview).toHaveTextContent('apr_inv_1');
    // Fill the required reason + confirm → the step + reason flow through.
    await user.type(screen.getByLabelText(/Reason/), 'spec was wrong');
    await user.click(screen.getByRole('button', { name: 'Confirm rerun' }));
    expect(onRerunFromStep).toHaveBeenCalledWith({
      targetStep: 'investigating',
      reasonText: 'spec was wrong',
    });
  });

  it('a11y — the full recovery action set has zero axe violations', async () => {
    const { container } = renderRecovery(
      ['retry', 'rerun_from_step', 'pause_workflow', 'classify_failure'],
      {
        retry: 'safe',
        rerun_from_step: 'risky',
        pause_workflow: 'caution',
        classify_failure: 'caution',
      },
      { onClassifyFailure: vi.fn() },
    );
    await expectNoA11yViolations(container);
  });
});
