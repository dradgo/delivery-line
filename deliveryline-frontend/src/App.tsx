import { lazy, Suspense } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createRouter } from '@tanstack/react-router';

import { createQueryClient } from './lib/api/queryOptions';
import { routeTree } from './routeTree.gen';
import { GenericErrorState, PageNotFoundState } from './routes/-states/DeadEndState';
import { NavigationBreadcrumbProvider } from './lib/navigation/NavigationBreadcrumbProvider';
import { CatastrophicErrorProvider } from './lib/navigation/CatastrophicErrorProvider';
import { CatastrophicErrorBoundary } from './lib/navigation/CatastrophicErrorBoundary';
import { CatastrophicErrorOverlay } from './lib/navigation/CatastrophicErrorOverlay';

// Story 2.6 — the single app-wide QueryClient (server-state source of truth,
// architecture.md:454). Created once at module scope so it is stable across
// renders and shared by BOTH the React tree (QueryClientProvider) and the route
// loaders (via the router context below) — that shared instance is what makes a
// loader prefetch and a component hook hit the same cache (AC10 dedup).
const queryClient = createQueryClient();

// Story 2.5 — build the router from the GENERATED route tree (the TanStack Router
// Vite plugin regenerates ./routeTree.gen.ts on every dev/build; it is gitignored).
//   - context.queryClient (story 2.6) — fills the RouterContext seam so loaders
//     call `context.queryClient.ensureQueryData(...)` (AC3).
//   - scrollRestoration: true (AC6) — restores scroll position on back/forward so
//     queue → detail → artifact → back returns to the prior scroll state. Run /
//     artifact identity is preserved by the URL params themselves.
//   - default*Component — tree-wide fallbacks mirroring the per-route ones on
//     __root.tsx, so a `throw notFound()` from any route without its own
//     notFoundComponent still lands on an explicit state, never a blank page.
const router = createRouter({
  routeTree,
  context: { queryClient },
  scrollRestoration: true,
  defaultNotFoundComponent: PageNotFoundState,
  defaultErrorComponent: GenericErrorState,
});

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}

// Dev-only PrimitivesPlayground (story 2.2; relocated to src/dev in story 2.5). The
// `lazy(() => import(...))` binding is created ONLY when import.meta.env.DEV is true;
// in a production build that flag is statically false, the binding is null, and
// Rollup tree-shakes the playground (and its example-only primitive states) out.
const PrimitivesPlayground = import.meta.env.DEV
  ? lazy(() => import('./dev/PrimitivesPlayground'))
  : null;

function App() {
  // Dev-only escape hatch (story 2.2): `?playground` short-circuits BEFORE the
  // router so the playground stays a dev-only, non-routed view.
  if (
    import.meta.env.DEV &&
    PrimitivesPlayground &&
    new URLSearchParams(window.location.search).has('playground')
  ) {
    return (
      <Suspense fallback={<div className="p-8 text-muted-foreground">Loading playground…</div>}>
        <PrimitivesPlayground />
      </Suspense>
    );
  }

  // Story 2.22 — navigation + catastrophic-error infrastructure wraps the router:
  //   • CatastrophicErrorProvider + NavigationBreadcrumbProvider live OUTSIDE the
  //     router (Traps T3/T11) so a router/shell crash still surfaces the overlay
  //     and the breadcrumb stack survives router re-mounts;
  //   • the overlay is a sibling of the boundary (portals to document.body), so a
  //     crash inside the boundary cannot also take the overlay down.
  return (
    <CatastrophicErrorProvider>
      <NavigationBreadcrumbProvider>
        <QueryClientProvider client={queryClient}>
          <CatastrophicErrorOverlay />
          <CatastrophicErrorBoundary>
            <RouterProvider router={router} />
          </CatastrophicErrorBoundary>
        </QueryClientProvider>
      </NavigationBreadcrumbProvider>
    </CatastrophicErrorProvider>
  );
}

export default App;
