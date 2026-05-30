/**
 * Story 2.22 (AC3) — the per-session breadcrumb-stack provider.
 *
 * Mounted in `App.tsx` OUTSIDE the router (Trap T3 / OQ-1) so the stack survives
 * router re-mounts; the auto-tracker reads router state from INSIDE the router.
 * State is `useReducer`-backed (OQ-2) so `push` / `replaceLast` / `clear` are the
 * only legal mutations and the dedup + cap rules live in one pure function.
 *
 * The stack is session-scoped — never persisted (AC3.f). A refresh resets it;
 * persisting would re-navigate the user into a stale artifact-version context.
 */
import { useCallback, useMemo, useReducer, type ReactNode } from 'react';

import { NavigationBreadcrumbContext } from './NavigationBreadcrumbContext';
import {
  INITIAL_BREADCRUMB_STACK,
  breadcrumbReducer,
  type BreadcrumbAction,
} from './breadcrumbReducer';
import type { BreadcrumbEntry, BreadcrumbKind } from './types';

/** The six meaningful kinds (AC3.b) — anything else is logged + still tracked defensively. */
const KNOWN_KINDS: ReadonlySet<BreadcrumbKind> = new Set<BreadcrumbKind>([
  'queue',
  'runDetail',
  'artifact',
  'clarification',
  'compareMode',
  'recoveryDeepDive',
]);

export function NavigationBreadcrumbProvider({ children }: { children: ReactNode }) {
  const [stack, dispatch] = useReducer(breadcrumbReducer, INITIAL_BREADCRUMB_STACK);

  const push = useCallback((entry: BreadcrumbEntry) => {
    if (!KNOWN_KINDS.has(entry.kind)) {
      // Logging instrumentation — defensive unknown-kind signal (AC logging task).
      console.warn({ event: 'breadcrumb.unknownKind', kind: entry.kind });
    }
    dispatch({ type: 'push', entry } satisfies BreadcrumbAction);
  }, []);

  const replaceLast = useCallback((entry: BreadcrumbEntry) => {
    dispatch({ type: 'replaceLast', entry } satisfies BreadcrumbAction);
  }, []);

  const clear = useCallback(() => {
    dispatch({ type: 'clear' } satisfies BreadcrumbAction);
  }, []);

  const value = useMemo(
    () => ({ stack, push, replaceLast, clear }),
    [stack, push, replaceLast, clear],
  );

  return (
    <NavigationBreadcrumbContext.Provider value={value}>
      {children}
    </NavigationBreadcrumbContext.Provider>
  );
}
