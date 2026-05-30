/**
 * Story 2.22 (AC1, AC3.e) — return to the prior meaningful run context.
 *
 * Walks the breadcrumb stack BACKWARDS for the most recent run-centered entry
 * (`runDetail | artifact | clarification`), navigating to it via the typed
 * router. When none exists (e.g. a deep-link landing), falls back to the queue
 * `/workflows` (Trap T4 — NEVER `window.history.back()`, which may hold pre-app
 * pages from a fresh tab) and emits a debug signal.
 *
 * Returns a `useCallback`-stabilized handler (Trap T1) so memoized children
 * holding it as an `onClick` keep referential equality across re-renders.
 */
import { useCallback } from 'react';
import { useNavigate } from '@tanstack/react-router';

import { useNavigationBreadcrumb } from './useNavigationBreadcrumb';
import type { BreadcrumbKind } from './types';

/** The "run-centered" kinds `useReturnToRunContext` returns to (AC3.e). */
const RUN_CENTERED_KINDS: ReadonlySet<BreadcrumbKind> = new Set<BreadcrumbKind>([
  'runDetail',
  'artifact',
  'clarification',
]);

export function useReturnToRunContext(): () => void {
  const navigate = useNavigate();
  const { stack } = useNavigationBreadcrumb();

  return useCallback(() => {
    // Skip the top entry — it is the CURRENT view; "return to the PRIOR run
    // context" (AC1) walks back from one below the top (AC11.c).
    for (let i = stack.length - 2; i >= 0; i -= 1) {
      const entry = stack[i];
      if (entry === undefined || !RUN_CENTERED_KINDS.has(entry.kind) || entry.runId === undefined) {
        continue;
      }
      if (entry.kind === 'artifact' && entry.artifactId !== undefined) {
        void navigate({
          to: '/workflows/$workflowRunId/artifacts/$artifactId',
          params: { workflowRunId: entry.runId, artifactId: entry.artifactId },
        });
        return;
      }
      if (entry.kind === 'clarification') {
        void navigate({
          to: '/workflows/$workflowRunId',
          params: { workflowRunId: entry.runId },
          search:
            entry.clarificationId !== undefined ? { clarificationId: entry.clarificationId } : {},
        });
        return;
      }
      void navigate({ to: '/workflows/$workflowRunId', params: { workflowRunId: entry.runId } });
      return;
    }
    // Logging instrumentation — diagnoses "the back button went to the queue".
    console.debug({ event: 'navigation.fallback', reason: 'empty_stack' });
    void navigate({ to: '/workflows' });
  }, [navigate, stack]);
}
