/**
 * Story 4.20 (AC1, AC8) — the thin Compare-Mode data container.
 *
 * Mirrors `ArtifactReviewPanelContainer`: reads the `useRevisionDelta` hook, resolves the single
 * `CompareState` via `resolveCompareState`, normalizes the loose wire shape via
 * `normalizeRevisionDelta`, and maps to the presentational `CompareMode` props. Owns the
 * field-only structured logging (mirror `ArtifactReviewPanelContainer` / `QueueShell`): NEVER logs
 * the raw error message, delta text, or artifact body — only the stable ProblemDetails `code`
 * + a transport flag.
 *
 * A/B is FIXED (4.19): `artifactIdA` = baseline/prior, `artifactIdB` = target/current. When no
 * prior-version id is resolvable (OQ-2), `artifactIdA` is empty → `hasBaseline` is false → the
 * surface renders "no baseline available" without ever firing a request.
 */
import { useEffect, useRef } from 'react';

import { isProblemDetailsError } from '@/lib/api/problemDetails';

import { normalizeRevisionDelta, resolveCompareState, type CompareView } from '../compareView';
import { useRevisionDelta } from '../hooks/useRevisionDelta';
import { CompareMode } from './CompareMode';

export interface CompareModeContainerProps {
  /** The run whose artifact review opened the compare (used for the prOutput lazy-diff read). */
  workflowRunId: string;
  /** Baseline/prior artifact id (empty when unresolved — OQ-2). */
  artifactIdA: string;
  /** Target/current artifact id (the artifact under review). */
  artifactIdB: string;
  /** Exit back to the originating review context. */
  onExit: () => void;
}

export function CompareModeContainer({
  workflowRunId,
  artifactIdA,
  artifactIdB,
  onExit,
}: CompareModeContainerProps) {
  const query = useRevisionDelta(artifactIdA, artifactIdB);
  const hasBaseline = artifactIdA.length > 0 && artifactIdB.length > 0;

  const delta = query.data;
  const state = resolveCompareState({
    hasBaseline,
    isError: query.isError,
    // Initial fetch only — preserve a rendered delta across a background refetch.
    isLoading: delta === undefined && query.isFetching,
    delta,
    error: query.error,
  });
  const view: CompareView | undefined =
    delta !== undefined ? normalizeRevisionDelta(delta) : undefined;

  // AC1 logging — one info on open (ids only, never text).
  const openedRef = useRef(false);
  useEffect(() => {
    if (openedRef.current) {
      return;
    }
    openedRef.current = true;
    console.info({ event: 'compare.opened', artifactIdA, artifactIdB });
  }, [artifactIdA, artifactIdB]);

  // Field-only error log — the stable ProblemDetails code + a transport flag; NEVER the message.
  useEffect(() => {
    if (state !== 'no-baseline' && state !== 'partial' && state !== 'unavailable') {
      return;
    }
    if (!query.isError) {
      // `no-baseline` from an unresolved id (not an actual error) — nothing to log.
      return;
    }
    const error: unknown = query.error;
    console.warn({
      event: 'compare.loadError',
      code: isProblemDetailsError(error) ? error.code : 'transport',
      transport: !isProblemDetailsError(error),
    });
  }, [state, query.isError, query.error]);

  const handleExit = () => {
    console.info({ event: 'compare.exit' });
    onExit();
  };

  const handleRetry = () => {
    void query.refetch();
  };

  return (
    <CompareMode
      state={state}
      view={view}
      onExit={handleExit}
      onRetry={handleRetry}
      workflowRunId={workflowRunId}
      artifactIdB={artifactIdB}
    />
  );
}
