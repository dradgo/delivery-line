/**
 * Story 2.7 (Task 4, AC4) — the composite-facing half of the right-panel slot.
 *
 * A composite renders `<ContextPanelSlot>{panelContent}</ContextPanelSlot>` from
 * within the central main pane; the content is portaled into the shell's right
 * `<aside>` and the slot is marked occupied for as long as this component is
 * mounted. See `AppShellContext.ts` for the full mechanism and contract.
 *
 * This component is intentionally isolated in its own module: a file that mixes a
 * React component with hooks/context exports breaks React Fast Refresh.
 */
import { useEffect, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

import { useAppShellContext } from './hooks/useAppShellContext';

/**
 * Project `children` into the tri-pane shell's right context panel. Renders
 * nothing in place; the content appears inside the shell's `<aside>`.
 */
export function ContextPanelSlot({ children }: { children: ReactNode }) {
  const { contextPanelMount, registerContextPanelOccupant } = useAppShellContext();

  // Mark the slot occupied while mounted — drives the shell's empty/collapsed
  // state. Stable callback ⇒ runs once on mount, once on unmount (no loop).
  useEffect(() => registerContextPanelOccupant(), [registerContextPanelOccupant]);

  return contextPanelMount !== null ? createPortal(children, contextPanelMount) : null;
}
