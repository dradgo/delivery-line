/**
 * Story 2.22 (AC10.a) — captures + restores run-context scroll/selection across
 * sub-state navigation (entering Compare Mode, a clarification deep-link).
 *
 * On MOUNT it snapshots `{ runId, artifactId?, clarificationId?, scrollY,
 * mainPaneScrollTop }` and publishes it to `RunContextSnapshotContext`. On
 * UNMOUNT it restores `scrollY` + the main pane's `scrollTop` via a
 * `useLayoutEffect` CLEANUP (Trap T12 — `useEffect` would paint at scroll=0
 * before restoring, causing a visible flash).
 */
import { useLayoutEffect, useRef, type ReactNode } from 'react';

import { useRunContextSnapshotContext } from './useRunContextSnapshot';
import type { RunContextSnapshot } from './types';

/** Mirrors `AppShell`'s private `MAIN_CONTENT_ID = 'main-content'` (story 2.7). */
const MAIN_CONTENT_ID = 'main-content';

export interface RunContextBoundaryProps {
  runId: string;
  artifactId?: string | undefined;
  clarificationId?: string | undefined;
  children: ReactNode;
}

export function RunContextBoundary({
  runId,
  artifactId,
  clarificationId,
  children,
}: RunContextBoundaryProps) {
  const { setSnapshot } = useRunContextSnapshotContext();
  // Hold the latest identity props so the mount/unmount-only layout effect can
  // read them without re-subscribing (keeps exhaustive-deps satisfied).
  const identityRef = useRef({ runId, artifactId, clarificationId });
  identityRef.current = { runId, artifactId, clarificationId };

  useLayoutEffect(() => {
    const main = document.getElementById(MAIN_CONTENT_ID);
    const snapshot: RunContextSnapshot = {
      ...identityRef.current,
      scrollY: window.scrollY,
      mainPaneScrollTop: main?.scrollTop ?? 0,
    };
    setSnapshot(snapshot);
    return () => {
      window.scrollTo(0, snapshot.scrollY);
      const mainOnExit = document.getElementById(MAIN_CONTENT_ID);
      if (mainOnExit !== null) {
        mainOnExit.scrollTop = snapshot.mainPaneScrollTop;
      }
      setSnapshot(null);
    };
  }, [setSnapshot]);

  return <>{children}</>;
}
