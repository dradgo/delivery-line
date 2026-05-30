/**
 * Story 2.22 (AC10) — accessors for the run-context snapshot.
 *
 * `useRunContextSnapshot` (public) returns the most recent snapshot for consumers
 * that want to READ scroll/selection state (e.g. the Clarification Region reading
 * the parent artifact id). `useRunContextSnapshotContext` (internal) exposes the
 * setter the boundary uses to capture/clear.
 */
import { useContext } from 'react';

import {
  RunContextSnapshotContext,
  type RunContextSnapshotContextValue,
} from './RunContextSnapshotContext';
import type { RunContextSnapshot } from './types';

export function useRunContextSnapshotContext(): RunContextSnapshotContextValue {
  const value = useContext(RunContextSnapshotContext);
  if (value === null) {
    throw new Error('RunContextSnapshot hooks must be used within <RunContextSnapshotProvider>.');
  }
  return value;
}

export function useRunContextSnapshot(): RunContextSnapshot | null {
  return useRunContextSnapshotContext().snapshot;
}
