/**
 * Story 2.22 AC11.q — the catastrophic overlay mounts on `signalCatastrophic`,
 * traps focus (Radix FocusScope), and restores focus to the previously-focused
 * element on dismiss. Trap T11 — it portals to document.body.
 */
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CatastrophicErrorProvider } from '../CatastrophicErrorProvider';
import { CatastrophicErrorOverlay } from '../CatastrophicErrorOverlay';
import { useCatastrophicError } from '../useCatastrophicError';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function Trigger() {
  const overlay = useCatastrophicError();
  return (
    <button type="button" onClick={() => overlay.signalCatastrophic(new Error('boom'))}>
      trigger
    </button>
  );
}

describe('CatastrophicErrorOverlay', () => {
  it('AC11.q — opens on signal, traps focus, and restores focus on dismiss', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <CatastrophicErrorProvider>
        <Trigger />
        <CatastrophicErrorOverlay />
      </CatastrophicErrorProvider>,
    );

    const trigger = screen.getByRole('button', { name: 'trigger' });
    trigger.focus();
    expect(document.activeElement).toBe(trigger);

    fireEvent.click(trigger);

    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toBeInTheDocument();
    // Trap T11 — the overlay portals to document.body, not into the trigger's tree.
    expect(document.body.contains(dialog)).toBe(true);
    // Focus moved into the dialog (Radix FocusScope).
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    // Focus restored to the element focused before the overlay opened.
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });
});
