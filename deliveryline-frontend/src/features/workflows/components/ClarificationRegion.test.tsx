/**
 * Story 2.18 (Task 6, AC2/AC3/AC5/AC6/AC7/AC8/AC10/AC11) — `ClarificationRegion`.
 *
 * Fixture-driven RTL (NOT `toMatchSnapshot` — no snapshot harness, story 2.27). The
 * region is presentational + router/query-free, so it renders directly from
 * constructed `ClarificationsView` fixtures. `useReturnToRunContext` (consumed by
 * `<ErrorState>` only when `loadState='error'`) is mocked at its own module (T7).
 */
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import {
  acceptedClarification,
  answeredClarification,
  answeredView,
  emptyView,
  incorporatedView,
  multiQuestionView,
  openView,
  rejectedInvalidView,
  stuckAnsweredView,
  supersededView,
  xssView,
} from '@/test/fixtures/clarification/clarificationViewFixtures';
import type { ClarificationsView } from '../clarificationView';

vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));

import { ClarificationRegion } from './ClarificationRegion';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function singleRowState(): string | null {
  return screen.getByRole('option').getAttribute('data-clarification-item-state');
}

describe('ClarificationRegion — states (AC3)', () => {
  it('renders the calm empty state when there are no clarifications (the only live state)', () => {
    render(<ClarificationRegion view={emptyView} />);
    expect(screen.getByTestId('clarification-region')).toHaveAttribute(
      'data-clarification-region-state',
      'empty',
    );
    expect(screen.getByTestId('empty-state')).toHaveAttribute('data-variant', 'noOpenQuestions');
  });

  it.each([
    ['open', openView, 'unanswered', 'Open'],
    ['answered', answeredView, 'answered', 'Answered · pending incorporation'],
    ['incorporated', incorporatedView, 'incorporated', 'Incorporated'],
    ['superseded', supersededView, 'superseded', 'Superseded'],
    ['rejected_invalid', rejectedInvalidView, 'rejected_invalid', 'Rejected'],
  ])(
    'renders %s with its non-color signifier (label) + data-clarification-item-state',
    (_name, view, expectedState, expectedLabel) => {
      render(<ClarificationRegion view={view as ClarificationsView} />);
      // Terminal states collapse — reveal them so the row is present.
      const toggle = screen.queryByTestId('clarification-terminal-toggle');
      if (toggle !== null) {
        fireEvent.click(toggle);
      }
      expect(singleRowState()).toBe(expectedState);
      expect(screen.getAllByText(expectedLabel as string).length).toBeGreaterThan(0);
    },
  );
});

describe('ClarificationRegion — grouping + collapse (AC2)', () => {
  it('collapses terminal states by default behind a disclosure toggle', () => {
    render(<ClarificationRegion view={multiQuestionView} />);
    // open + pending (answered, accepted) are visible; terminal AND the unknown sentinel
    // are collapsed behind the disclosure (Decision-①).
    expect(screen.getByText(answeredClarification.questionText)).toBeInTheDocument();
    expect(screen.queryByText(supersededView.clarifications[0]!.questionText)).toBeNull();

    fireEvent.click(screen.getByTestId('clarification-terminal-toggle'));
    expect(screen.getByText(supersededView.clarifications[0]!.questionText)).toBeInTheDocument();
  });
});

describe('ClarificationRegion — selected-question dominance (AC8)', () => {
  it('renders the detail panel for the selected question with separated raw/interpreted answer', () => {
    render(<ClarificationRegion view={answeredView} />);
    fireEvent.click(screen.getByRole('option'));

    const detail = screen.getByTestId('clarification-detail');
    expect(detail).toBeInTheDocument();
    expect(screen.getByTestId('clarification-question-body')).toBeInTheDocument();
    // Reviewer wording and system interpretation are visually SEPARATED (no commingling).
    expect(screen.getByTestId('clarification-answer-raw')).toBeInTheDocument();
    expect(screen.getByTestId('clarification-answer-interpreted')).toBeInTheDocument();
  });
});

describe('ClarificationRegion — lifecycle indicator (AC2/AC5)', () => {
  it('advances submitted → accepted → incorporated as sequential states arrive', () => {
    const base = answeredClarification;
    const mk = (status: 'answered' | 'accepted' | 'incorporated'): ClarificationsView => ({
      clarifications: [{ ...base, status }],
    });

    const { rerender } = render(
      <ClarificationRegion view={mk('answered')} selectedClarificationId={base.clarificationId} />,
    );
    expect(screen.getByTestId('clarification-lifecycle')).toHaveAttribute(
      'data-lifecycle-current',
      '0',
    );

    rerender(
      <ClarificationRegion view={mk('accepted')} selectedClarificationId={base.clarificationId} />,
    );
    expect(screen.getByTestId('clarification-lifecycle')).toHaveAttribute(
      'data-lifecycle-current',
      '1',
    );

    rerender(
      <ClarificationRegion
        view={mk('incorporated')}
        selectedClarificationId={base.clarificationId}
      />,
    );
    expect(screen.getByTestId('clarification-lifecycle')).toHaveAttribute(
      'data-lifecycle-current',
      '2',
    );
  });
});

describe('ClarificationRegion — no-effect reason (AC6)', () => {
  it('renders the superseded reason explicitly (never a silent disappearance)', () => {
    render(<ClarificationRegion view={supersededView} />);
    fireEvent.click(screen.getByTestId('clarification-terminal-toggle'));
    fireEvent.click(screen.getByRole('option'));
    expect(screen.getByTestId('clarification-no-effect-reason')).toHaveTextContent(
      /superseded by spec v4/i,
    );
  });

  it('renders the rejected_invalid reason explicitly', () => {
    render(<ClarificationRegion view={rejectedInvalidView} />);
    fireEvent.click(screen.getByTestId('clarification-terminal-toggle'));
    fireEvent.click(screen.getByRole('option'));
    expect(screen.getByTestId('clarification-no-effect-reason')).toHaveTextContent(
      /not parseable/i,
    );
  });
});

describe('ClarificationRegion — sanitization (AC8/AC11)', () => {
  it('routes untrusted question/answer text through SafeMarkdownRenderer — no active script or onerror', () => {
    const probe = window as unknown as { __xss_executed?: boolean };
    delete probe.__xss_executed;

    const { container } = render(<ClarificationRegion view={xssView} />);
    fireEvent.click(screen.getByRole('option'));

    // The body went through the sanitizer (its marker is present)…
    expect(container.querySelector('[data-component="safe-markdown"]')).not.toBeNull();
    // …and no executable <script>/<iframe> survived.
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('iframe')).toBeNull();
    // …nor the <img onerror> event-handler vector (the more dangerous live probe) — and
    // the payload never executed (P4).
    expect(container.querySelector('[onerror]')).toBeNull();
    expect(probe.__xss_executed).not.toBe(true);
  });
});

describe('ClarificationRegion — approval-gating affordance (AC10)', () => {
  it('shows the {N}-pending gate message when pending > 0', () => {
    render(<ClarificationRegion view={multiQuestionView} />);
    // multiQuestion pending = 4 (all except incorporated + rejected_invalid + the
    // unknown sentinel — Decision-①).
    expect(screen.getByTestId('clarification-approval-gate')).toHaveTextContent(
      /4 clarifications must be incorporated before approval/i,
    );
  });

  it('hides the gate when nothing is pending', () => {
    render(<ClarificationRegion view={incorporatedView} />);
    expect(screen.queryByTestId('clarification-approval-gate')).toBeNull();
  });
});

describe('ClarificationRegion — anti-pattern (AC11)', () => {
  it('a stuck answered clarification keeps showing answered/pending, never "incorporated"', () => {
    render(
      <ClarificationRegion view={stuckAnsweredView} selectedClarificationId="cla_stuck00001" />,
    );
    // Row state stays answered.
    expect(singleRowState()).toBe('answered');
    // Lifecycle sits at submitted (0), NOT incorporated (2).
    expect(screen.getByTestId('clarification-lifecycle')).toHaveAttribute(
      'data-lifecycle-current',
      '0',
    );
    // No "Incorporated" label anywhere.
    expect(screen.queryByText('Incorporated')).toBeNull();
  });
});

describe('ClarificationRegion — submit flow (AC5/AC9)', () => {
  it('emits onSubmitAnswer(clarificationId, answerText) with the typed answer', () => {
    const onSubmitAnswer = vi.fn();
    render(<ClarificationRegion view={openView} onSubmitAnswer={onSubmitAnswer} />);
    fireEvent.click(screen.getByRole('option'));

    fireEvent.change(screen.getByTestId('clarification-answer-input'), {
      target: { value: 'Return the full set in one response.' },
    });
    fireEvent.click(screen.getByTestId('clarification-submit'));

    expect(onSubmitAnswer).toHaveBeenCalledWith(
      'cla_open0001',
      'Return the full set in one response.',
    );
  });

  it('blocks an empty submit with a client-side validation error (no callback)', () => {
    const onSubmitAnswer = vi.fn();
    render(<ClarificationRegion view={openView} onSubmitAnswer={onSubmitAnswer} />);
    fireEvent.click(screen.getByRole('option'));
    fireEvent.click(screen.getByTestId('clarification-submit'));

    expect(screen.getByTestId('clarification-validation-error')).toBeInTheDocument();
    expect(onSubmitAnswer).not.toHaveBeenCalled();
  });

  it('renders inline success feedback (NOT a toast — UX-DR15) on a successful submit', () => {
    render(
      <ClarificationRegion
        view={openView}
        selectedClarificationId="cla_open0001"
        submission={{ status: 'success', clarificationId: 'cla_open0001' }}
      />,
    );
    expect(screen.getByTestId('clarification-submit-feedback')).toHaveTextContent(
      /pending incorporation/i,
    );
  });

  it('renders an inline submit error with the ProblemDetails code on failure', () => {
    render(
      <ClarificationRegion
        view={openView}
        selectedClarificationId="cla_open0001"
        submission={{
          status: 'error',
          clarificationId: 'cla_open0001',
          errorCode: 'CLARIFICATION_ARTIFACT_VERSION_MISMATCH',
        }}
      />,
    );
    expect(screen.getByTestId('clarification-submit-error')).toHaveTextContent(
      'CLARIFICATION_ARTIFACT_VERSION_MISMATCH',
    );
  });
});

describe('ClarificationRegion — accessibility (AC7)', () => {
  it('moves focus between questions with arrow keys', () => {
    render(<ClarificationRegion view={multiQuestionView} />);
    const listbox = screen.getByRole('listbox', { name: 'Clarification questions' });
    const buttons = within(listbox).getAllByRole('option');
    buttons[0]!.focus();
    expect(document.activeElement).toBe(buttons[0]);

    fireEvent.keyDown(listbox, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(buttons[1]);

    fireEvent.keyDown(listbox, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(buttons[0]);
  });

  it('associates the response textarea with the selected question (aria-labelledby)', () => {
    render(<ClarificationRegion view={openView} />);
    fireEvent.click(screen.getByRole('option'));
    const textarea = screen.getByTestId('clarification-answer-input');
    const labelledBy = textarea.getAttribute('aria-labelledby');
    expect(labelledBy).not.toBeNull();
    expect(document.getElementById(labelledBy!)).toHaveTextContent('Question');
  });

  it('announces a status transition through a polite ARIA live region', () => {
    const onLifecycleAdvance = vi.fn();
    const base = answeredClarification;
    const { rerender } = render(
      <ClarificationRegion
        view={{ clarifications: [{ ...base, status: 'answered' }] }}
        onLifecycleAdvance={onLifecycleAdvance}
      />,
    );
    const live = screen.getByTestId('clarification-live-region');
    expect(live).toHaveAttribute('aria-live', 'polite');

    rerender(
      <ClarificationRegion
        view={{ clarifications: [{ ...base, status: 'accepted' }] }}
        onLifecycleAdvance={onLifecycleAdvance}
      />,
    );
    expect(live).toHaveTextContent(/Accepted/i);
    expect(onLifecycleAdvance).toHaveBeenCalledWith(base.clarificationId, 'accepted');
  });

  it('AC5/AC7 — announces the COUNT when multiple clarifications advance at once (story 2.25)', () => {
    const onLifecycleAdvance = vi.fn();
    const a = { ...answeredClarification, clarificationId: 'c-a', status: 'answered' as const };
    const b = { ...answeredClarification, clarificationId: 'c-b', status: 'answered' as const };
    const { rerender } = render(
      <ClarificationRegion
        view={{ clarifications: [a, b] }}
        onLifecycleAdvance={onLifecycleAdvance}
      />,
    );
    const live = screen.getByTestId('clarification-live-region');

    // Both advance in the SAME update — the old code announced only the last-in-array.
    rerender(
      <ClarificationRegion
        view={{
          clarifications: [
            { ...a, status: 'accepted' },
            { ...b, status: 'accepted' },
          ],
        }}
        onLifecycleAdvance={onLifecycleAdvance}
      />,
    );
    expect(live).toHaveTextContent('2 clarifications updated');
    // Each advance is reported (field-only logging), not just the last one.
    expect(onLifecycleAdvance).toHaveBeenCalledWith('c-a', 'accepted');
    expect(onLifecycleAdvance).toHaveBeenCalledWith('c-b', 'accepted');
  });
});

describe('ClarificationRegion — compact variant (AC4)', () => {
  it('renders counts + a CTA only', () => {
    render(<ClarificationRegion view={multiQuestionView} variant="compact" />);
    expect(screen.getByTestId('clarification-compact-summary')).toHaveTextContent(/pending/i);
    expect(screen.getByTestId('clarification-compact-cta')).toBeInTheDocument();
    // No full question list in compact mode.
    expect(screen.queryByRole('listbox')).toBeNull();
  });
});

describe('ClarificationRegion — accepted state chip', () => {
  it('renders accepted with its label', () => {
    render(<ClarificationRegion view={{ clarifications: [acceptedClarification] }} />);
    expect(singleRowState()).toBe('accepted');
    expect(screen.getByText('Accepted')).toBeInTheDocument();
  });
});

/**
 * Story 2.25 (Task 2 — AC1/AC2). The region is a labeled `<section>` with a `listbox`
 * of question `option`s (roving focus via arrow keys) and a detail panel with an answer
 * control. Every documented state is axe-scanned; the question list + answer flow are
 * exercised for keyboard operability.
 */
describe('ClarificationRegion a11y (story 2.25)', () => {
  // AC2 — every documented region state. Terminal-only views are scanned with the
  // disclosure expanded so the collapsed rows are also in the scanned subtree.
  it('AC2 — the empty state has no axe violations', async () => {
    const { container } = render(<ClarificationRegion view={emptyView} />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — the loading state has no axe violations', async () => {
    const { container } = render(<ClarificationRegion view={emptyView} loadState="loading" />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — the error state has no axe violations', async () => {
    const { container } = render(
      <ClarificationRegion view={emptyView} loadState="error" onRetry={vi.fn()} />,
    );
    await expectNoA11yViolations(container);
  });

  it.each([
    ['open', openView] as const,
    ['answered (detail + separated answers)', answeredView] as const,
    ['incorporated', incorporatedView] as const,
    ['multi-question (grouped + disclosure + gate)', multiQuestionView] as const,
  ])('AC2 — populated state "%s" has no axe violations', async (_name, view) => {
    const { container } = render(<ClarificationRegion view={view as ClarificationsView} />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — a terminal (superseded) state with the disclosure expanded + detail open has no axe violations', async () => {
    const user = userEvent.setup();
    const { container } = render(<ClarificationRegion view={supersededView} />);
    await user.click(screen.getByTestId('clarification-terminal-toggle'));
    await user.click(screen.getByRole('option'));
    await expectNoA11yViolations(container);
  });

  it('AC2 — the selected-question detail with the answer input open has no axe violations', async () => {
    const user = userEvent.setup();
    const { container } = render(<ClarificationRegion view={openView} />);
    await user.click(screen.getByRole('option'));
    expect(screen.getByTestId('clarification-answer-input')).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('AC2 — the compact variant has no axe violations', async () => {
    const { container } = render(
      <ClarificationRegion view={multiQuestionView} variant="compact" />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC1 — a question option is reachable by Tab/arrow keys and selects on Enter, revealing the answer input', async () => {
    const user = userEvent.setup();
    render(<ClarificationRegion view={openView} />);
    const option = screen.getByRole('option');
    // The single open question row is in the natural Tab order.
    await user.tab();
    expect(document.activeElement).toBe(option);
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('clarification-answer-input')).toBeInTheDocument();
  });

  it('AC1 — the answer flow is fully keyboard-operable (type + Tab to submit + Enter)', async () => {
    const user = userEvent.setup();
    const onSubmitAnswer = vi.fn();
    render(<ClarificationRegion view={openView} onSubmitAnswer={onSubmitAnswer} />);
    await user.click(screen.getByRole('option'));

    const input = screen.getByTestId('clarification-answer-input');
    input.focus();
    await user.keyboard('Paginate the export.');
    await user.tab();
    expect(document.activeElement).toBe(screen.getByTestId('clarification-submit'));
    await user.keyboard('{Enter}');
    expect(onSubmitAnswer).toHaveBeenCalledWith('cla_open0001', 'Paginate the export.');
  });

  it('AC1 — the terminal disclosure toggle is keyboard-operable (Enter expands)', async () => {
    const user = userEvent.setup();
    render(<ClarificationRegion view={multiQuestionView} />);
    const toggle = screen.getByTestId('clarification-terminal-toggle');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    toggle.focus();
    await user.keyboard('{Enter}');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
  });
});

describe('ClarificationRegion — accept + regenerate controls (story 3e-2 AC1/AC2)', () => {
  const ANSWERED_ID = answeredClarification.clarificationId;

  it('shows the Accept button on an answered clarification only when accept_clarification is allowed', () => {
    const onAccept = vi.fn();
    render(
      <ClarificationRegion
        view={answeredView}
        selectedClarificationId={ANSWERED_ID}
        allowedActions={['accept_clarification']}
        onAcceptClarification={onAccept}
      />,
    );
    const acceptButton = screen.getByTestId('clarification-accept');
    fireEvent.click(acceptButton);
    expect(onAccept).toHaveBeenCalledWith(ANSWERED_ID);
  });

  it('hides the Accept button when accept_clarification is NOT in the allowed actions (governed)', () => {
    render(
      <ClarificationRegion
        view={answeredView}
        selectedClarificationId={ANSWERED_ID}
        allowedActions={[]}
        onAcceptClarification={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('clarification-accept')).toBeNull();
  });

  it('shows the regenerate footer + fires onRegenerateSpec only when regenerate is allowed', () => {
    const onRegenerate = vi.fn();
    render(
      <ClarificationRegion
        view={answeredView}
        selectedClarificationId={ANSWERED_ID}
        allowedActions={['regenerate_spec_with_clarifications']}
        onRegenerateSpec={onRegenerate}
      />,
    );
    const regenerateButton = screen.getByTestId('clarification-regenerate');
    fireEvent.click(regenerateButton);
    expect(onRegenerate).toHaveBeenCalledTimes(1);
  });

  it('hides the regenerate footer when regenerate is NOT allowed (governed)', () => {
    render(
      <ClarificationRegion
        view={answeredView}
        selectedClarificationId={ANSWERED_ID}
        allowedActions={['answer_clarification']}
        onRegenerateSpec={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('clarification-regenerate-footer')).toBeNull();
  });

  it('surfaces inline accept + regenerate feedback and stays a11y-clean', async () => {
    const { container } = render(
      <ClarificationRegion
        view={answeredView}
        selectedClarificationId={ANSWERED_ID}
        allowedActions={['accept_clarification', 'regenerate_spec_with_clarifications']}
        onAcceptClarification={vi.fn()}
        acceptSubmission={{ status: 'success', clarificationId: ANSWERED_ID }}
        onRegenerateSpec={vi.fn()}
        regenerateSubmission={{ status: 'success' }}
      />,
    );
    expect(screen.getByTestId('clarification-accept-feedback')).toBeInTheDocument();
    expect(screen.getByTestId('clarification-regenerate-feedback')).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });
});
