/**
 * Story 2.23 (AC2, AC5, AC6) — `<ConfirmationDialog>`.
 *
 * Router-free, query-free, no MSW (presentational). Mirrors the 2.21 primitive
 * tests. Radix portals into <body>, so overlay nodes are queried via `screen`.
 */
import { useState } from 'react';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ConfirmationDialog, type ConfirmationDialogProps } from '../ConfirmationDialog';
import type { OverlayIntent } from '../overlayPresentation';

afterEach(cleanup);

function Harness({
  intent = 'danger',
  consequence = 'This cannot be undone.',
  onConfirm = () => undefined,
}: {
  intent?: OverlayIntent;
  consequence?: string;
  onConfirm?: () => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button type="button" data-testid="trigger" onClick={() => setOpen(true)}>
        Open
      </button>
      <ConfirmationDialog
        open={open}
        onOpenChange={setOpen}
        title="Reject this specification?"
        intent={intent}
        consequence={consequence}
        confirmLabel="Reject"
        onConfirm={onConfirm}
      />
    </div>
  );
}

describe('ConfirmationDialog', () => {
  it('AC5 — opens on trigger and wires aria-labelledby (title) + aria-describedby (consequence)', async () => {
    render(<Harness consequence="The current version will no longer be the candidate." />);
    fireEvent.click(screen.getByTestId('trigger'));

    const dialog = await screen.findByRole('dialog');
    const labelledby = dialog.getAttribute('aria-labelledby');
    const describedby = dialog.getAttribute('aria-describedby');
    expect(labelledby).toBeTruthy();
    expect(describedby).toBeTruthy();

    const titleNode = document.getElementById(labelledby ?? '');
    const consequenceNode = document.getElementById(describedby ?? '');
    expect(titleNode).toHaveTextContent('Reject this specification?');
    expect(consequenceNode).toHaveTextContent(
      'The current version will no longer be the candidate.',
    );
    expect(consequenceNode).toHaveAttribute('data-consequence');
  });

  it('AC5 — Escape closes and focus restores to the triggering element', async () => {
    render(<Harness />);
    const trigger = screen.getByTestId('trigger');
    trigger.focus();
    expect(trigger).toHaveFocus();

    fireEvent.click(trigger);
    const dialog = await screen.findByRole('dialog');

    fireEvent.keyDown(dialog, { key: 'Escape', code: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('AC5 — cancel closes and restores focus to the trigger', async () => {
    render(<Harness />);
    const trigger = screen.getByTestId('trigger');
    trigger.focus();
    fireEvent.click(trigger);
    await screen.findByRole('dialog');

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('AC6 — danger intent renders a destructive confirm, a distinct element separated from cancel', async () => {
    render(<Harness intent="danger" />);
    fireEvent.click(screen.getByTestId('trigger'));
    await screen.findByRole('dialog');

    const confirm = screen.getByRole('button', { name: 'Reject' });
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(confirm).toHaveAttribute('data-priority', 'destructive');
    expect(cancel).toHaveAttribute('data-priority', 'secondary');
    expect(confirm).not.toBe(cancel);
  });

  it('non-danger intent renders a primary confirm', async () => {
    render(<Harness intent="info" />);
    fireEvent.click(screen.getByTestId('trigger'));
    await screen.findByRole('dialog');
    expect(screen.getByRole('button', { name: 'Reject' })).toHaveAttribute(
      'data-priority',
      'primary',
    );
  });

  it('AC1 — confirm invokes onConfirm', async () => {
    const onConfirm = vi.fn();
    render(<Harness onConfirm={onConfirm} />);
    fireEvent.click(screen.getByTestId('trigger'));
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('AC2 — consequence is a TypeScript-required prop', () => {
    // @ts-expect-error consequence is mandatory (AC2) — omitting it must not compile.
    const props: ConfirmationDialogProps = {
      open: true,
      onOpenChange: () => undefined,
      title: 'Reject?',
      intent: 'info',
      onConfirm: () => undefined,
    };
    expect(props).toBeDefined();
  });
});
