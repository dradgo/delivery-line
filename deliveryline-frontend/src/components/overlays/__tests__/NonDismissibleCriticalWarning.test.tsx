/**
 * Story 2.23 (AC5) — `<NonDismissibleCriticalWarning>` (T-NON-DISMISSIBLE).
 */
import { render, screen, cleanup, fireEvent, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
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

describe('NonDismissibleCriticalWarning a11y (story 2.25)', () => {
  it('AC2 — default danger alertdialog open state has no axe violations', async () => {
    renderOverlay();
    await screen.findByRole('alertdialog');
    await expectNoA11yViolations(document.body);
  });

  it('AC2 — warning intent open state has no axe violations', async () => {
    render(
      <NonDismissibleCriticalWarning
        open
        intent="warning"
        title="Action required"
        body="You must acknowledge before continuing."
        acknowledgmentLabel="Acknowledge"
        onAcknowledge={() => undefined}
      />,
    );
    await screen.findByRole('alertdialog');
    await expectNoA11yViolations(document.body);
  });

  it('AC1 — focus moves into the alertdialog on open (lands on the acknowledgment control)', async () => {
    renderOverlay();
    const dialog = await screen.findByRole('alertdialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
  });

  it('AC1 — Escape is INERT: focus is CONTAINED and Tab cycles within the dialog (not dismissed)', async () => {
    const user = userEvent.setup();
    renderOverlay();
    const dialog = await screen.findByRole('alertdialog');

    // Per T-NON-DISMISSIBLE: Escape must NOT close the overlay.
    fireEvent.keyDown(dialog, { key: 'Escape', code: 'Escape' });
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();

    // Focus containment: the sole acknowledgment control is the only focusable
    // target, so Tab keeps focus inside the dialog (Radix focus trap).
    const ack = screen.getByRole('button', { name: 'I understand' });
    ack.focus();
    expect(ack).toHaveFocus();
    await user.tab();
    expect(dialog.contains(document.activeElement)).toBe(true);
    await user.tab({ shift: true });
    expect(dialog.contains(document.activeElement)).toBe(true);
  });

  it('AC1 — the acknowledgment control activates via keyboard (Enter)', async () => {
    const user = userEvent.setup();
    const onAcknowledge = vi.fn();
    renderOverlay(onAcknowledge);
    await screen.findByRole('alertdialog');
    const ack = screen.getByRole('button', { name: 'I understand' });
    ack.focus();
    await user.keyboard('{Enter}');
    expect(onAcknowledge).toHaveBeenCalledTimes(1);
  });
});
