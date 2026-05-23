/**
 * Story 2.7 (Task 4, AC4) — typed accessor for the tri-pane shell context.
 *
 * Lives in its own module (apart from the `AppShellContext` object and the
 * `<ContextPanelSlot>` component) so no file mixes a React component with hooks —
 * the condition React Fast Refresh requires.
 */
import { useContext } from 'react';

import { AppShellContext, type AppShellContextValue } from '../AppShellContext';

/**
 * Read the shell context. Throws when used outside `<AppShell>` so a composite
 * mounted off-shell fails loudly instead of silently dropping its panel content.
 */
export function useAppShellContext(): AppShellContextValue {
  const value = useContext(AppShellContext);
  if (value === null) {
    throw new Error('useAppShellContext must be used within <AppShell>.');
  }
  return value;
}
