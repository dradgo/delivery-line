/**
 * Story 2.22 (AC3.c) — auto-tracks the breadcrumb stack from router state.
 *
 * Subscribes to the router location + matches, classifies the deepest match into
 * a meaningful {@link BreadcrumbKind}, and pushes (the reducer dedups identical
 * consecutive kinds + ids — Trap T5). Runs from `<NavigationBreadcrumbTracker />`
 * mounted INSIDE the router in `__root.tsx`, while the provider lives OUTSIDE the
 * router in `App.tsx` (Trap T3).
 */
import { useEffect } from 'react';
import { useMatches, useRouterState } from '@tanstack/react-router';

import { useNavigationBreadcrumb } from './useNavigationBreadcrumb';
import type { BreadcrumbEntry } from './types';

interface RouterMatchLike {
  routeId: string;
  params: Record<string, string | undefined>;
}

function classify(
  matches: readonly RouterMatchLike[],
  search: Record<string, unknown>,
): BreadcrumbEntry | null {
  const deepest = matches.at(-1);
  if (deepest === undefined) {
    return null;
  }
  const id = deepest.routeId;
  const params = deepest.params;
  const runId = params.workflowRunId;
  const artifactId = params.artifactId;
  const clarificationId =
    typeof search.clarificationId === 'string' ? search.clarificationId : undefined;
  const base = { scrollY: window.scrollY, createdAt: Date.now() };

  if (id.includes('artifacts/$artifactId')) {
    return runId !== undefined && artifactId !== undefined
      ? { kind: 'artifact', runId, artifactId, ...base }
      : null;
  }
  if (id.includes('$workflowRunId')) {
    if (runId === undefined) {
      return null;
    }
    return clarificationId !== undefined
      ? { kind: 'clarification', runId, clarificationId, ...base }
      : { kind: 'runDetail', runId, ...base };
  }
  if (id === '/workflows/' || id === '/workflows' || id === '/') {
    return { kind: 'queue', ...base };
  }
  return null;
}

export function useBreadcrumbAutoTrack(): void {
  const { push } = useNavigationBreadcrumb();
  const matches = useMatches() as unknown as RouterMatchLike[];
  const location = useRouterState({ select: (s) => s.location });

  // `useMatches()` / `useRouterState` hand back fresh references on every router
  // render, so depending on them directly would re-run this effect (and re-push)
  // on unrelated re-renders. Gate on a stable route-identity key derived from the
  // deepest match + search, so the effect fires only on an actual navigation
  // (AC3.c "on route stable"); `matches`/`location` are read fresh inside.
  const deepest = matches.at(-1);
  const routeKey = `${deepest?.routeId ?? ''}|${JSON.stringify(deepest?.params ?? {})}|${JSON.stringify(location.search)}`;

  useEffect(() => {
    const entry = classify(matches, location.search);
    if (entry !== null) {
      push(entry);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fire only on route-identity change (routeKey); matches/location are read fresh inside.
  }, [routeKey, push]);
}
