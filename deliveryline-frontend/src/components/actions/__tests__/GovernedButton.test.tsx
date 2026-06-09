/**
 * Story 2.23 (AC7, AC8, AC9, AC10) — `<GovernedButton>`.
 */
import { render, screen, cleanup, fireEvent, act } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { GovernedButton, type GovernedButtonProps } from '../GovernedButton';
import { PRIORITY_VARIANT } from '../buttonHierarchy';

afterEach(cleanup);

describe('GovernedButton — priority (AC7)', () => {
  it.each(['primary', 'secondary', 'tertiary', 'destructive'] as const)(
    'priority "%s" maps to its variant + stamps data-priority',
    (priority) => {
      render(
        <GovernedButton priority={priority} testId="gb">
          Act
        </GovernedButton>,
      );
      const btn = screen.getByTestId('gb');
      expect(btn).toHaveAttribute('data-priority', priority);
      expect(btn.tagName).toBe('BUTTON');
      // Sanity-check the documented variant mapping reaches the className.
      const variant = PRIORITY_VARIANT[priority];
      const VARIANT_CLASS: Record<string, string> = {
        default: 'bg-primary',
        secondary: 'bg-secondary',
        destructive: 'bg-destructive',
        ghost: 'hover:bg-accent',
      };
      expect(btn.className).toContain(VARIANT_CLASS[variant]);
    },
  );
});

describe('GovernedButton — blocked (AC8)', () => {
  it('priority="blocked" renders a NON-interactive blocked visual + required adjacent explanation', () => {
    const onClick = vi.fn();
    render(
      <GovernedButton
        priority="blocked"
        blockedExplanation="Incorporate open clarifications before approving."
        onClick={onClick}
        testId="gb"
      >
        Approve
      </GovernedButton>,
    );
    // Not a <button> — no role button exists.
    expect(screen.queryByRole('button')).toBeNull();

    const blocked = document.querySelector('[data-blocked="true"]');
    expect(blocked).not.toBeNull();
    expect(blocked).toHaveAttribute('aria-disabled', 'true');
    // Non-color signifier: icon (svg) + the action label.
    expect(blocked?.querySelector('svg')).not.toBeNull();
    expect(blocked).toHaveTextContent('Approve');

    // The adjacent explanation renders and is linked via aria-describedby.
    const describedBy = blocked?.getAttribute('aria-describedby') ?? '';
    expect(document.getElementById(describedBy)).toHaveTextContent(
      'Incorporate open clarifications before approving.',
    );

    // Clicking the blocked visual does nothing (no handler is attached).
    fireEvent.click(blocked as HTMLElement);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('workflowState="blocked" composes the same blocked visual', () => {
    render(
      <GovernedButton
        priority="secondary"
        workflowState="blocked"
        blockedExplanation="Unavailable until upstream review completes."
        testId="gb"
      >
        Continue
      </GovernedButton>,
    );
    expect(screen.queryByRole('button')).toBeNull();
    const blocked = document.querySelector('[data-blocked="true"]');
    expect(blocked).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText('Unavailable until upstream review completes.')).toBeInTheDocument();
  });
});

describe('GovernedButton — workflowState (AC9, AC10)', () => {
  it('submitting → aria-busy + spinner + non-interactive', () => {
    render(
      <GovernedButton priority="primary" workflowState="submitting" testId="gb">
        Approving
      </GovernedButton>,
    );
    const btn = screen.getByTestId('gb');
    expect(btn).toHaveAttribute('aria-busy', 'true');
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('data-workflow-state', 'submitting');
    // The sanctioned LoadingState spinner renders an svg.
    expect(btn.querySelector('svg')).not.toBeNull();
  });

  it('completed → checkmark in an aria-live region; persists across timers; clears on reset', () => {
    vi.useFakeTimers();
    try {
      const { rerender } = render(
        <GovernedButton priority="primary" workflowState="completed" testId="gb">
          Approved
        </GovernedButton>,
      );
      const btn = screen.getByTestId('gb');
      expect(btn).toHaveAttribute('data-workflow-state', 'completed');
      expect(btn.querySelector('[aria-live="polite"]')).not.toBeNull();
      expect(btn.querySelector('svg')).not.toBeNull();

      // AC10 — never auto-clears.
      act(() => {
        vi.advanceTimersByTime(15000);
      });
      expect(screen.getByTestId('gb').querySelector('[aria-live="polite"]')).not.toBeNull();

      // Parent-controlled reset clears the outcome.
      rerender(
        <GovernedButton priority="primary" workflowState="ready" testId="gb">
          Approve
        </GovernedButton>,
      );
      expect(screen.getByTestId('gb').querySelector('[aria-live="polite"]')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('logging — a blocked button missing its explanation warns field-only (no content leak)', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    try {
      // Bypass the TS narrowing to reach the defense-in-depth anomaly branch.
      const props = {
        priority: 'blocked',
        children: 'Approve',
        testId: 'gb',
      } as unknown as GovernedButtonProps;
      render(<GovernedButton {...props} />);

      expect(warnSpy).toHaveBeenCalledTimes(1);
      const arg = warnSpy.mock.calls[0]?.[0] as Record<string, unknown>;
      expect(arg.event).toBe('overlay.blockedButtonMissingExplanation');
      // Exact key set — never the children / explanation / any content.
      expect(Object.keys(arg).sort()).toEqual(['event', 'priority', 'workflowState'].sort());
    } finally {
      warnSpy.mockRestore();
    }
  });

  it('stale → carries the stale signifier and stays interactive', () => {
    render(
      <GovernedButton priority="primary" workflowState="stale" testId="gb">
        Approve
      </GovernedButton>,
    );
    const btn = screen.getByTestId('gb');
    expect(btn).toHaveAttribute('data-workflow-state', 'stale');
    expect(btn).not.toBeDisabled();
    expect(btn.querySelector('svg')).not.toBeNull();
  });
});
