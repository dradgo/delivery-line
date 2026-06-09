/**
 * Story 2.25 (Task 5 — AC10, D3) — touch-target floor static enforcement.
 *
 * Asserts that interactive primitives carry the 44px `min-h-touch` floor class
 * (icon-only controls also carry `min-w-touch`). This is a STATIC class-presence
 * check — jsdom has no layout engine, so live mobile-viewport px verification is
 * deferred to story 2.26 + the manual checklist (a11y-screen-reader-checklist.md).
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { GovernedButton } from '@/components/actions';
import { InlineFeedback } from '@/components/feedback/primitives';

afterEach(cleanup);

describe('touch-target floor (story 2.25 AC10)', () => {
  it('GovernedButton carries the 44px min-height floor', () => {
    render(<GovernedButton priority="primary">Approve</GovernedButton>);
    expect(screen.getByRole('button', { name: 'Approve' })).toHaveClass('min-h-touch');
  });

  it('InlineFeedback icon-only dismiss control carries the 44px min height + width floor', () => {
    const { container } = render(
      <InlineFeedback variant="info" persistsUntil="dismiss" onDismiss={() => undefined}>
        Saved.
      </InlineFeedback>,
    );
    const dismiss = container.querySelector('[data-inline-feedback-dismiss]');
    expect(dismiss).not.toBeNull();
    expect(dismiss).toHaveClass('min-h-touch');
    expect(dismiss).toHaveClass('min-w-touch');
  });
});
