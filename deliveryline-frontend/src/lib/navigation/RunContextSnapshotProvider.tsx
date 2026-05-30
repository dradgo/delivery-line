/**
 * Story 2.22 (AC10) — run-context snapshot provider. Mounted in `__root.tsx` so
 * any `<RunContextBoundary>` rendered within a route can publish its snapshot and
 * any consumer can read it via `useRunContextSnapshot`.
 */
import { useCallback, useMemo, useState, type ReactNode } from 'react';

import { RunContextSnapshotContext } from './RunContextSnapshotContext';
import type { RunContextSnapshot } from './types';

export function RunContextSnapshotProvider({ children }: { children: ReactNode }) {
  const [snapshot, setSnapshotState] = useState<RunContextSnapshot | null>(null);
  const setSnapshot = useCallback((next: RunContextSnapshot | null) => {
    setSnapshotState(next);
  }, []);
  const value = useMemo(() => ({ snapshot, setSnapshot }), [snapshot, setSnapshot]);
  return (
    <RunContextSnapshotContext.Provider value={value}>
      {children}
    </RunContextSnapshotContext.Provider>
  );
}
