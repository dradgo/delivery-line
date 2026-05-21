# Routing (story 2.5)

TanStack Router file-based routing. The `@tanstack/router-plugin/vite` plugin
(wired in [`vite.config.ts`](../../vite.config.ts), **before** `@vitejs/plugin-react`)
generates `src/routeTree.gen.ts` from the files in this directory on every
`vite dev` / `vite build`. That generated tree is **gitignored** (AC7) and is also
ignored by ESLint and Prettier — never edit or commit it.

This story is the **navigation skeleton** only: the typed route tree, validated
params, the loader **seam**, and every explicit "this link goes nowhere good" UI
state. It is intentionally **data-free** — there is no API/query layer yet (that is
story 2.6). Route components are minimal placeholders; the real composites land
later (see the deferral table).

## Route tree

| File                                                 | Path                                              | Route                                                                              |
| ---------------------------------------------------- | ------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `__root.tsx`                                         | (root)                                            | Root layout + `*` not-found + error boundary                                       |
| `index.tsx`                                          | `/`                                               | Redirects → `/workflows` (the queue is the canonical entry surface)                |
| `workflows/index.tsx`                                | `/workflows`                                      | WorkflowsRoute (queue/list placeholder)                                            |
| `workflows/$workflowRunId/index.tsx`                 | `/workflows/$workflowRunId`                       | WorkflowDetailRoute                                                                |
| `workflows/$workflowRunId/artifacts/$artifactId.tsx` | `/workflows/$workflowRunId/artifacts/$artifactId` | ArtifactViewerRoute                                                                |
| `-states/DeadEndState.tsx`                           | (not a route)                                     | Shared explicit-UI-state components — the `-` prefix is the router's ignore prefix |

The `*` NotFoundRoute from AC1 is realized idiomatically as the root's
`notFoundComponent` (+ `throw notFound()` in loaders), not a literal catch-all file.

## Param validation (AC2)

`$workflowRunId` and `$artifactId` are validated against the **V1 public-id shape**
`^<prefix>_[A-Za-z0-9_-]{4,64}$` (`run_…` / `art_…`). The validators are pure
functions in [`../lib/routing/publicId.js`](../lib/routing/publicId.js); a malformed
id routes to the dedicated **"Invalid link"** state (not a crash, not the
resource-not-found state).

**Source of truth + accepted duplication:** the canonical shape is the backend
`PublicIdPrefixes` enum + the Flyway V1 CHECK constraints (story 1.4). There is no
shared JVM↔TS codegen across the module boundary yet, so the regex is **re-encoded**
here as accepted cross-language duplication — keep it in lockstep with
`deliveryline-backend/.../domain/id/PublicIdPrefixes.java`.

The validators ship as framework-free `.js` + a hand-written `.d.ts` precisely so the
`node --test` gate (`npm run check:routes`) can import them **directly with no build
step** (Node 20 cannot strip TS types) and exercise the exact predicates the routes
use — no parallel copy to drift.

## Loader seam (AC3) — what story 2.6 fills in

Each data route has a `loader` that returns **typed stub data** today. A
`// SEAM (story 2.6):` comment marks where
`queryClient.ensureQueryData(workflowKeys.detail(...))` (real prefetch, AC3) and the
`X-Correlation-Id` header (AC9, see [`../lib/api/correlation.ts`](../lib/api/correlation.ts))
attach once the typed API client and key factories land. The typed `RouterContext`
in `__root.tsx` is the seam for injecting the real `QueryClient` into the router.

## Explicit UI states (AC4, AC8)

All in `-states/DeadEndState.tsx`, composed from the story-2.4 layout primitives +
typography classes + 2.3 state tokens:

- **Invalid link** (AC2) — malformed param.
- **Run not found / Artifact not found** (AC4) — wired as each data route's
  `notFoundComponent`; the loader `throw notFound()` that drives them lands with 2.6.
- **Page not found** (AC1) — the root `notFoundComponent` for any unmatched URL.
- **Something went wrong** — the generic error boundary, distinct from not-found.
- **Unsupported workspace states** (AC8 / UX-DR6): a run in an **unrecognized state**
  (a future Epic-3+ state seen from an older Epic-2 build), an **unrenderable artifact
  type**, and a **permission-restricted** navigation. The permission state is a
  **recorded-role-aware display only** — the UI never enforces permissions or approval
  eligibility (architecture.md:519); the backend is the authority.

> SEAM (story 2.22): when the shared empty-state / feedback-pattern infrastructure
> lands, these dead-end states adopt that component instead of the local shell here.

## One detail route across stages (AC10)

`WorkflowDetailRoute` is **not** split per stage. The backend-reported `currentStage`
(spec / implementation-plan / pr-output) selects the Artifact Review Panel variant
**inside** the route; Epic 3 adds stages by widening the recognized set, never by
forking the tree. Compare/clarification are bounded states inside the run (ux:1836),
not separate routes.

## Deep links + back/forward (AC5, AC6)

- **Back/forward + scroll (AC6):** `scrollRestoration: true` on the router
  (`src/App.tsx`) restores scroll position; run/artifact identity rides in the URL
  params, so it survives refresh and back/forward.
- **Deep links (AC5):** in **dev**, Vite's SPA history fallback serves any path to
  `index.html`, so `http://localhost:5173/workflows/run_…` resolves to the detail
  route directly. **Production** deep links depend on the SpaFallbackController
  (story 2.28, not built yet).

## Deferred to later stories

| Item                                           | Owning story |
| ---------------------------------------------- | ------------ |
| Real query prefetch in loaders (AC3)           | 2.6          |
| Backend-404 → `throw notFound()` (AC4)         | 2.6          |
| Correlation-ID header on loader requests (AC9) | 2.6 (+ 1.19) |
| Production SPA-fallback deep links (AC5)       | 2.28         |
| Route/component/a11y test suite                | 2.27         |

See [`../../../../_bmad-output/implementation-artifacts/deferred-work.md`](../../../../_bmad-output/implementation-artifacts/deferred-work.md).
