import { lazy, Suspense } from 'react';
import { RouterProvider, createRouter } from '@tanstack/react-router';

import { routeTree } from './routeTree.gen';
import { GenericErrorState, PageNotFoundState } from './routes/-states/DeadEndState';

// Story 2.5 — build the router from the GENERATED route tree (the TanStack Router
// Vite plugin regenerates ./routeTree.gen.ts on every dev/build; it is gitignored).
//   - context: {} — the typed RouterContext seam (RouterContext in __root.tsx);
//     story 2.6 passes the real QueryClient here.
//   - scrollRestoration: true (AC6) — restores scroll position on back/forward so
//     queue → detail → artifact → back returns to the prior scroll state. Run /
//     artifact identity is preserved by the URL params themselves.
//   - default*Component — tree-wide fallbacks mirroring the per-route ones on
//     __root.tsx, so a `throw notFound()` from any route without its own
//     notFoundComponent still lands on an explicit state, never a blank page.
const router = createRouter({
  routeTree,
  context: {},
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

  return <RouterProvider router={router} />;
}

export default App;
