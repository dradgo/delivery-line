/**
 * Story 2.7 (Task 4, AC4) — the context backing the tri-pane shell's right-panel
 * composition SLOT.
 *
 * The shell does NOT hard-code right-panel content. A composite rendered inside
 * the central main pane — the Run Context Strip (story 2.16), the Clarification
 * Region sidebar variant (story 2.18), a blocker list, artifact metadata, a
 * run-history timeline — projects its content into the right `<aside>` via
 * `<ContextPanelSlot>` (inline on desktop, in a drawer on tablet/mobile).
 *
 * MECHANISM (story 2.7 Q2 — decided: React context, NOT a TanStack layout route;
 * realized as a DOM PORTAL, the option Task 4 sanctions). The flat file-based
 * route tree makes nested pathless layout routes awkward and would force edits to
 * the custom route generator. A portal keeps the slot a pure React concern AND —
 * unlike lifting a child's JSX into parent state — lets the panel content stay in
 * the composite's own render tree: it updates live and cannot cause a render loop.
 *
 * The slot's public surface is the `<ContextPanelSlot>` component; this raw
 * context is exported only so `AppShell` can provide it. (Context + hook + the
 * slot component are split across modules so each stays React-Fast-Refresh-clean.)
 *
 * CONTRACT for future composites (2.16 / 2.18 …) — see `LAYOUT.md`:
 *  - Wrap your panel content in `<ContextPanelSlot>` from a component mounted in
 *    the main pane. It renders into the shell's `<aside>` and clears the slot when
 *    your component unmounts.
 *  - The content renders (via portal) inside the AppShell DOM subtree but remains
 *    in YOUR React tree — it keeps your component's props, state, and context.
 *  - Multiple occupants are reference-counted; Epic-2's per-run composites are
 *    mutually exclusive by design, so in practice one panel shows at a time.
 */
import { createContext } from 'react';

export interface AppShellContextValue {
  /** The live DOM node the right `<aside>` renders into, or `null` while unmounted. */
  contextPanelMount: HTMLElement | null;
  /** True while at least one `<ContextPanelSlot>` is mounted — drives the empty state. */
  isContextPanelOccupied: boolean;
  /**
   * Register a context-panel occupant. Call on mount; invoke the returned
   * unregister function on unmount. Reference-counted so concurrent occupants do
   * not prematurely flip the panel back to its empty state.
   */
  registerContextPanelOccupant: () => () => void;
}

export const AppShellContext = createContext<AppShellContextValue | null>(null);
