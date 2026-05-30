/**
 * Story 2.22 (AC3) — the per-session breadcrumb-stack context object.
 *
 * Split from the provider component (NavigationBreadcrumbProvider.tsx) and the
 * accessor hook (useNavigationBreadcrumb.ts) so no module mixes a React
 * component with non-component exports — the same three-file split the
 * story-2.7 `AppShellContext` uses to stay React-Fast-Refresh-clean.
 *
 * The provider lives in `App.tsx` OUTSIDE the router (Trap T3); the auto-tracker
 * (`useBreadcrumbAutoTrack` via `<NavigationBreadcrumbTracker />`) lives INSIDE
 * the router in `__root.tsx` and is the only writer in production.
 */
import { createContext } from 'react';

import type { BreadcrumbEntry } from './types';

export interface NavigationBreadcrumbContextValue {
  /** The current stack, oldest-first (readonly to consumers). */
  readonly stack: readonly BreadcrumbEntry[];
  /** Push a new meaningful context (deduped against the top — AC3.c). */
  readonly push: (entry: BreadcrumbEntry) => void;
  /** Replace the top entry in place. */
  readonly replaceLast: (entry: BreadcrumbEntry) => void;
  /** Reset the stack to empty. */
  readonly clear: () => void;
}

export const NavigationBreadcrumbContext = createContext<NavigationBreadcrumbContextValue | null>(
  null,
);
