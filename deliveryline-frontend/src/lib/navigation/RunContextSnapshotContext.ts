/**
 * Story 2.22 (AC10) — the run-context snapshot context object.
 *
 * Holds the most recent scroll/selection snapshot captured by a
 * `<RunContextBoundary>`. Orthogonal to the breadcrumb stack (Trap T14): this
 * tracks scroll + artifact selection, the breadcrumb tracks pages. Split into a
 * `.ts` (object), `.tsx` (provider), and hook file per the repo's Fast-Refresh
 * three-file convention.
 */
import { createContext } from 'react';

import type { RunContextSnapshot } from './types';

export interface RunContextSnapshotContextValue {
  /** The most recently captured snapshot, or null when no boundary is active. */
  readonly snapshot: RunContextSnapshot | null;
  /** Publish (or clear) the active snapshot. Called only by `<RunContextBoundary>`. */
  readonly setSnapshot: (snapshot: RunContextSnapshot | null) => void;
}

export const RunContextSnapshotContext = createContext<RunContextSnapshotContextValue | null>(null);
