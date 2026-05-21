import { Outlet, createRootRouteWithContext } from '@tanstack/react-router';

import { GenericErrorState, PageNotFoundState } from './-states/DeadEndState';

/**
 * Story 2.5 — typed router context SEAM.
 *
 * SEAM (story 2.6): TanStack Query injects the real `QueryClient` here so route
 * loaders can call `queryClient.ensureQueryData(workflowKeys.detail(...))` (AC3).
 * `QueryClient` does not exist yet (2.6 owns it), so the field is typed `unknown`
 * and optional today; 2.6 widens it to the concrete type and passes the instance
 * into `createRouter({ context })`. Using `createRootRouteWithContext` now means
 * 2.6 does NOT have to reshape the root route.
 */
export interface RouterContext {
  /** SEAM (story 2.6): real `QueryClient` instance is injected here. */
  queryClient?: unknown;
}

/**
 * Root layout route. Renders the matched child via `<Outlet />` and defines the
 * tree-wide fallbacks:
 *   • notFoundComponent — AC1's `*` NotFoundRoute: the idiomatic TanStack Router
 *     realization of a catch-all (any unmatched URL renders "Page not found").
 *   • errorComponent — a generic error boundary, distinct from not-found.
 */
export const Route = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
  notFoundComponent: PageNotFoundState,
  errorComponent: GenericErrorState,
});

function RootLayout() {
  return <Outlet />;
}
