/**
 * Story 2.22 AC11.j (exhaustiveness) + AC11.k (5 variants render).
 */
import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';

import {
  EmptyState,
  assertNeverEmptyVariant,
  type EmptyVariant,
  type EmptyStateProps,
} from '../EmptyState';

afterEach(cleanup);

const VARIANTS: EmptyVariant[] = [
  'queue',
  'filtered',
  'artifactNotGenerated',
  'noOpenQuestions',
  'noMeaningfulDiff',
];

describe('EmptyState', () => {
  it.each(VARIANTS)(
    'AC11.k — renders variant "%s" with a title, default message, and no live region',
    (variant) => {
      render(<EmptyState variant={variant} />);
      const el = screen.getByTestId('empty-state');
      expect(el).toHaveAttribute('data-variant', variant);
      expect(el.querySelector('h2')?.textContent?.length ?? 0).toBeGreaterThan(0);
      expect(el.textContent?.length ?? 0).toBeGreaterThan(0);
      // AC9.c — empty states are benign: no aria-live region.
      expect(el.querySelector('[aria-live]')).toBeNull();
    },
  );

  it('AC11.k — renders an optional action when provided', () => {
    render(<EmptyState variant="queue" action={<button type="button">Go</button>} />);
    expect(screen.getByRole('button', { name: 'Go' })).toBeInTheDocument();
  });

  it('overrides the default message when message is supplied', () => {
    render(<EmptyState variant="queue" message="Custom copy here" />);
    expect(screen.getByText('Custom copy here')).toBeInTheDocument();
  });

  it('AC11.j — the exhaustiveness guard throws on an off-union value at runtime', () => {
    expect(() => assertNeverEmptyVariant('bogus' as never)).toThrow(/Unhandled EmptyState variant/);
  });

  it('AC11.j — an off-union variant is a COMPILE error (type-level contract)', () => {
    // The `@ts-expect-error` makes the build fail if `variant` ever stops being a
    // closed union (e.g. widened to `string`) — `tsc -b` enforces this at the gate.
    const props: EmptyStateProps = {
      // @ts-expect-error — 'bogus' is not an EmptyVariant.
      variant: 'bogus',
    };
    expect(props.variant).toBe('bogus');
  });

  // Story 2.25 (Task 2, AC2) — axe scan of every documented state, both bare and
  // with an optional action affordance.
  it.each(VARIANTS)('AC2 — variant "%s" has no axe violations', async (variant) => {
    const { container } = render(<EmptyState variant={variant} />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — renders cleanly with an action affordance', async () => {
    const { container } = render(
      <EmptyState variant="queue" action={<button type="button">View all runs</button>} />,
    );
    await expectNoA11yViolations(container);
  });

  // Story 2.25 (Task 2, AC1) — the optional action is keyboard-reachable and
  // activatable without a mouse.
  it('AC1 — the action affordance is reachable by Tab and activates on Enter', async () => {
    const user = userEvent.setup();
    const onAct = vi.fn();
    render(
      <EmptyState
        variant="queue"
        action={
          <button type="button" onClick={onAct}>
            View all runs
          </button>
        }
      />,
    );
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'View all runs' }));
    await user.keyboard('{Enter}');
    expect(onAct).toHaveBeenCalledTimes(1);
  });
});
