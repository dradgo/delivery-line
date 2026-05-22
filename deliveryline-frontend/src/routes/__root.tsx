import type { QueryClient } from '@tanstack/react-query';
import { Outlet, createRootRouteWithContext } from '@tanstack/react-router';

import { GenericErrorState, PageNotFoundState } from './-states/DeadEndState';

/**
 * Typed router context.
 *
 * Story 2.6 filled the story-2.5 SEAM: the real `QueryClient` is injected here
 * (App.tsx passes it into `createRouter({ context })`), so route loaders call
 * `context.queryClient.ensureQueryData(detailQueryOptions(...))` for flash-free
 * deep links (AC3). Authored via `createRootRouteWithContext` in 2.5 so this is a
 * type widen, not a route reshape.
 */
export interface RouterContext {
  queryClient: QueryClient;
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
