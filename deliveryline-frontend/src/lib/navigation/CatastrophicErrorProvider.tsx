/**
 * Story 2.22 (AC8.b) — catastrophic-error provider. Mounted ONCE in `App.tsx`
 * OUTSIDE the router subtree (Trap T11) so a shell/router crash still surfaces
 * the overlay.
 */
import { useCallback, useMemo, useState, type ReactNode } from 'react';

import { CatastrophicErrorContext } from './CatastrophicErrorContext';

export function CatastrophicErrorProvider({ children }: { children: ReactNode }) {
  const [activeError, setActiveError] = useState<Error | null>(null);
  const [resetToken, setResetToken] = useState(0);
  const signalCatastrophic = useCallback((error: Error) => setActiveError(error), []);
  // Dismiss clears the error AND bumps the reset token so the boundary re-mounts
  // the crashed subtree (retry render) instead of staying latched on a blank app.
  const dismiss = useCallback(() => {
    setActiveError(null);
    setResetToken((token) => token + 1);
  }, []);
  const value = useMemo(
    () => ({ activeError, signalCatastrophic, dismiss, resetToken }),
    [activeError, signalCatastrophic, dismiss, resetToken],
  );
  return (
    <CatastrophicErrorContext.Provider value={value}>
      {children}
    </CatastrophicErrorContext.Provider>
  );
}
