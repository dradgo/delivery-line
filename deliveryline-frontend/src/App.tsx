import { lazy, Suspense, useEffect } from 'react';

// Story 2.1 scaffold → re-skinned in story 2.2 onto Tailwind + shadcn/ui.
// The real tri-pane app shell lands in story 2.7; this placeholder only proves
// Tailwind utilities + the design-system CSS variables resolve.
//
// Dev-only PrimitivesPlayground (story 2.2 Task 6): the `lazy(() => import(...))`
// is created ONLY when `import.meta.env.DEV` is true. In a production build that
// flag is statically `false`, so the binding is `null`, the dynamic import is
// unreferenced, and Rollup tree-shakes the playground (and every example-only
// primitive state it renders) out of the bundle entirely — no prod chunk.
const PrimitivesPlayground = import.meta.env.DEV
  ? lazy(() => import('./routes/_dev/PrimitivesPlayground'))
  : null;

function App() {
  useEffect(() => {
    if (!import.meta.env.DEV) {
      return;
    }

    console.warn(
      '[deliveryline-frontend] Scaffolding only — placeholder UI. Real app shell arrives in story 2.7. Append ?playground in dev to view the primitives playground.',
    );
  }, []);

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

  return (
    <main className="flex min-h-svh flex-col items-center justify-center gap-4 bg-background p-8 text-foreground">
      <h1 className="text-3xl font-semibold tracking-tight">DeliveryLine</h1>
      <p className="max-w-prose text-center text-muted-foreground">
        Frontend scaffold. The tri-pane application shell arrives in story 2.7. In dev, append{' '}
        <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-sm">?playground</code> to the
        URL to view the shadcn/ui primitives playground.
      </p>
    </main>
  );
}

export default App;
