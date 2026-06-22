/**
 * Story 3d-2 (AC3/AC4/AC5/AC6/AC8, Task 8/10) — `ReviewerVerdictPanel`.
 *
 * Presentational + advisory-only: fixture-driven RTL on the pure component (no router/query). Pins
 * the verdict states (available pass/concern/fail, pending, unavailable, self-review), the AC5
 * panel-absent (no-reviewer) case, advisory-only framing (no decision buttons), and zero axe
 * wcag2aa violations.
 */
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import type { ReviewerVerdict } from '../hooks/useReviewerVerdict';
import { ReviewerVerdictPanel } from './ReviewerVerdictPanel';

afterEach(() => cleanup());

function available(overrides: Partial<ReviewerVerdict> = {}): ReviewerVerdict {
  return {
    state: 'available',
    outcome: 'concern',
    rationale: 'The error path lacks test coverage.',
    reviewerModelIdentity: 'claude:it-3d2',
    producerModelIdentity: 'codex:it-3d2',
    selfReview: false,
    unavailableReason: null,
    createdAt: '2026-06-22T10:00:00Z',
    ...overrides,
  } as ReviewerVerdict;
}

describe('ReviewerVerdictPanel', () => {
  it('renders an available verdict with a color-independent outcome label + identities', () => {
    render(<ReviewerVerdictPanel verdict={available()} />);
    expect(screen.getByTestId('reviewer-verdict-outcome')).toHaveTextContent('Concern');
    expect(screen.getByTestId('reviewer-verdict-rationale')).toHaveTextContent(
      'lacks test coverage',
    );
    expect(screen.getByTestId('reviewer-model-identity')).toHaveTextContent('claude:it-3d2');
    expect(screen.getByTestId('producer-model-identity')).toHaveTextContent('codex:it-3d2');
  });

  it('renders pass / concern / fail outcomes', () => {
    for (const outcome of ['pass', 'concern', 'fail'] as const) {
      cleanup();
      render(<ReviewerVerdictPanel verdict={available({ outcome })} />);
      expect(screen.getByTestId('reviewer-verdict-outcome')).toBeInTheDocument();
    }
  });

  it('flags a same-model self-review without refusing it (AC4)', () => {
    render(<ReviewerVerdictPanel verdict={available({ selfReview: true })} />);
    expect(screen.getByTestId('reviewer-verdict-self-review')).toBeInTheDocument();
    // Still shows the verdict — surfaced, not blocked.
    expect(screen.getByTestId('reviewer-verdict-outcome')).toBeInTheDocument();
  });

  it('renders the pending state while the reviewer runs', () => {
    render(<ReviewerVerdictPanel verdict={available({ state: 'pending', outcome: null })} />);
    expect(screen.getByTestId('reviewer-verdict-pending')).toBeInTheDocument();
  });

  it('renders the unavailable state with a reason (AC6 graceful degradation)', () => {
    render(
      <ReviewerVerdictPanel
        verdict={available({
          state: 'unavailable',
          outcome: null,
          rationale: null,
          unavailableReason: 'runner_crash',
        })}
      />,
    );
    expect(screen.getByTestId('reviewer-verdict-unavailable')).toBeInTheDocument();
    expect(screen.getByTestId('reviewer-verdict-reason')).toHaveTextContent('runner_crash');
  });

  it('renders NOTHING for a no-reviewer-configured project (AC5 panel absent)', () => {
    const { container } = render(
      <ReviewerVerdictPanel
        verdict={available({
          state: 'unavailable',
          outcome: null,
          unavailableReason: 'no_reviewer_configured',
        })}
      />,
    );
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByTestId('reviewer-verdict-panel')).not.toBeInTheDocument();
  });

  it('renders nothing before the first fetch resolves', () => {
    const { container } = render(<ReviewerVerdictPanel verdict={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('is advisory-only — renders no approve/reject controls', () => {
    render(<ReviewerVerdictPanel verdict={available()} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('has zero axe wcag2aa violations across states', async () => {
    for (const verdict of [
      available({ outcome: 'pass' }),
      available({ selfReview: true }),
      available({ state: 'pending', outcome: null }),
      available({ state: 'unavailable', outcome: null, unavailableReason: 'runner_crash' }),
    ]) {
      cleanup();
      const { container } = render(<ReviewerVerdictPanel verdict={verdict} />);
      await expectNoA11yViolations(container);
    }
  });
});
