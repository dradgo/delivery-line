/**
 * Story 2.23 (AC1, AC6) — `<BoundedDetailSheet>`.
 */
import { useState } from 'react';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { BoundedDetailSheet } from '../BoundedDetailSheet';

afterEach(cleanup);

describe('BoundedDetailSheet', () => {
  it('AC1 — renders the title + bounded content (right slide-over by default)', () => {
    render(
      <BoundedDetailSheet open onOpenChange={() => undefined} title="Run context">
        <p>Bounded supporting detail.</p>
      </BoundedDetailSheet>,
    );
    const sheet = screen.getByTestId('bounded-detail-sheet');
    expect(sheet).toHaveAttribute('data-bounded-detail-sheet');
    expect(sheet).toHaveAttribute('data-side', 'right');
    expect(sheet).toHaveAttribute('data-full-height', 'false');
    expect(screen.getByText('Run context')).toBeInTheDocument();
    expect(screen.getByText('Bounded supporting detail.')).toBeInTheDocument();
  });

  it('AC6 — side="bottom" / fullHeightOnMobile renders the full-height bottom-sheet variant', () => {
    render(
      <BoundedDetailSheet
        open
        onOpenChange={() => undefined}
        title="Run context"
        side="bottom"
        fullHeightOnMobile
      >
        <p>Full-height body.</p>
      </BoundedDetailSheet>,
    );
    const sheet = screen.getByTestId('bounded-detail-sheet');
    expect(sheet).toHaveAttribute('data-side', 'bottom');
    expect(sheet).toHaveAttribute('data-full-height', 'true');
  });

  it('AC6 — fullHeightOnMobile alone defaults the side to bottom', () => {
    render(
      <BoundedDetailSheet
        open
        onOpenChange={() => undefined}
        title="Run context"
        fullHeightOnMobile
      >
        <p>Body.</p>
      </BoundedDetailSheet>,
    );
    const sheet = screen.getByTestId('bounded-detail-sheet');
    expect(sheet).toHaveAttribute('data-side', 'bottom');
    expect(sheet).toHaveAttribute('data-full-height', 'true');
  });
});

describe('BoundedDetailSheet a11y (story 2.25)', () => {
  function Harness({ side }: { side?: 'right' | 'bottom' }) {
    const [open, setOpen] = useState(false);
    return (
      <div>
        <button type="button" data-testid="trigger" onClick={() => setOpen(true)}>
          Open
        </button>
        <BoundedDetailSheet
          open={open}
          onOpenChange={setOpen}
          title="Run context"
          description="Supporting context for this review."
          side={side}
        >
          <p>Bounded supporting detail.</p>
          <button type="button">Inner action</button>
        </BoundedDetailSheet>
      </div>
    );
  }

  it('AC2 — right slide-over open state has no axe violations', async () => {
    render(<Harness side="right" />);
    fireEvent.click(screen.getByTestId('trigger'));
    await screen.findByRole('dialog');
    await expectNoA11yViolations(document.body);
  });

  it('AC2 — bottom full-height open state has no axe violations', async () => {
    render(<Harness side="bottom" />);
    fireEvent.click(screen.getByTestId('trigger'));
    await screen.findByRole('dialog');
    await expectNoA11yViolations(document.body);
  });

  it('AC1 — focus moves into the sheet on open', async () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('trigger'));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
  });

  it('AC1 — inner controls are Tab-reachable within the sheet', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    fireEvent.click(screen.getByTestId('trigger'));
    const dialog = await screen.findByRole('dialog');
    const inner = screen.getByRole('button', { name: 'Inner action' });
    inner.focus();
    expect(inner).toHaveFocus();
    // Tab keeps focus contained within the sheet (Radix focus trap).
    await user.tab();
    expect(dialog.contains(document.activeElement)).toBe(true);
  });

  it('AC1 — Escape closes the dismissible sheet and restores focus to the opener (WCAG 2.4.3)', async () => {
    render(<Harness />);
    const trigger = screen.getByTestId('trigger');
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = await screen.findByRole('dialog');

    fireEvent.keyDown(dialog, { key: 'Escape', code: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // Story 2.25 — the controlled sheet now restores focus to the element that
    // opened it (BoundedDetailSheet's own restoration effect), mirroring
    // ConfirmationDialog. Without it, Radix would drop focus to <body>.
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});
