/**
 * Story 2.23 (AC5) — `<NonDismissibleCriticalWarning>` (T-NON-DISMISSIBLE).
 */
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { NonDismissibleCriticalWarning } from '../NonDismissibleCriticalWarning';

afterEach(cleanup);

function renderOverlay(onAcknowledge = () => undefined) {
  return render(
    <NonDismissibleCriticalWarning
      open
      title="Run irrecoverably stopped"
      body="This run cannot be resumed."
      acknowledgmentLabel="I understand"
      onAcknowledge={onAcknowledge}
    />,
  );
}

describe('NonDismissibleCriticalWarning', () => {
  it('AC5 — renders as an alertdialog', () => {
    renderOverlay();
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
  });

  it('AC5 — Escape is blocked (overlay stays open)', () => {
    renderOverlay();
    const dialog = screen.getByRole('alertdialog');
    fireEvent.keyDown(dialog, { key: 'Escape', code: 'Escape' });
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
  });

  it('AC5 — outside-pointer dismissal is blocked (overlay stays open)', () => {
    renderOverlay();
    fireEvent.pointerDown(document.body);
    fireEvent.click(document.body);
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
  });

  it('AC5 — the ONLY exit affordance is the acknowledgment control (no close X)', () => {
    renderOverlay();
    const dialog = screen.getByRole('alertdialog');
    const buttons = within(dialog).getAllByRole('button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveTextContent('I understand');
  });

  it('AC5 — acknowledgment invokes onAcknowledge', () => {
    const onAcknowledge = vi.fn();
    renderOverlay(onAcknowledge);
    fireEvent.click(screen.getByRole('button', { name: 'I understand' }));
    expect(onAcknowledge).toHaveBeenCalledTimes(1);
  });
});
