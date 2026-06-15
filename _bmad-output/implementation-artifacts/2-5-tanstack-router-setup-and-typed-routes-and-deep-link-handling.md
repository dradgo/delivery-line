# Story 2.5: TanStack Router Setup + Typed Routes + Deep-Link Handling

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **frontend developer**,
I want **TanStack Router configured with typed route definitions for workflow list, workflow detail, artifact viewer, and root / not-found routes**,
so that **every navigation path is type-safe, deep links into run details work via SPA fallback (story 2.28), and missing workflows / unsupported routes have explicit UI states rather than blank pages**.

## ⚠️ Read first — what this story is and is NOT

This story delivers the **navigation skeleton** of the frontend: the typed route tree, param validation, loader *pattern*, and every explicit "this link goes nowhere good" UI state. It is the structural seam that the data layer (2.6) and composites (2.7, 2.15–2.19) plug into. It deliberately stops at the seam.

**Resolved up front by Alex (2026-05-21) — bake these in, no open clarifications:**

- **Q1 — File-based routing.** Use `@tanstack/react-router` + the `@tanstack/router-plugin/vite` plugin. Route files live under `src/routes/`; the plugin generates `src/routeTree.gen.ts` on every build. The generated tree is **gitignored** (AC7). The explicit code-based filenames in architecture.md:1065-1069 (`root.tsx`, `WorkflowsRoute.tsx`, …) were illustrative — they map cleanly to file-based equivalents (`__root.tsx`, `workflows/index.tsx`, …). This is the TanStack-recommended path and satisfies AC7 literally.
- **Q2 — Route structure + UI states NOW; data wiring deferred to 2.6/2.28.** There is **no API to call yet**: story 2.6 (TanStack Query + typed API client + key factories) is the *next* backlog story, the backend spec/approval endpoints are 2.8+/2.13, the OpenAPI source is 6.9, and the SpaFallbackController is 2.28 — **none are built**. So this story builds the full route tree, **typed + validated params**, and **all** explicit UI states (invalid-link, run/artifact-not-found, unsupported-route, unsupported-workspace-state, generic error). **Loaders are a documented seam returning typed stub data — no network calls.** Real `queryClient.ensureQueryData(workflowKeys…)` prefetch (AC3), backend-404 → not-found (AC4), correlation-ID header (AC9), and SPA-fallback deep links (AC5) are wired by 2.6/2.28. AC3/AC4/AC5/AC9 ship as **structure + seam + documented deferral** here (the deferred items go to `deferred-work.md`).
- **Q3 — `node --test` for param validation + the route-tree drift gate; route/loader/component tests defer to 2.27.** Extract param validation into pure functions covered by `node --test` (mirrors `check:contrast` / `check:tokens` / `lint:rules-test`). **Do NOT add Vitest or Playwright** — story 2.27 owns the component/route/a11y test suite (its AC5 explicitly tests these routes). No new test runner.

**Hard boundaries:**

- **No composites, no shell, no real data.** The tri-pane AppShell is 2.7; Queue Item / Run Context Strip / ARP are 2.15–2.19; TanStack Query + API client + key factories are 2.6. Route components here are **minimal placeholders** that prove the route resolves and renders its state — not product UI.
- **No primitive edits** (`src/components/ui/*` stay byte-stock — 2.2 AC4/AC7; architecture.md:488). Consume existing layout primitives (`src/components/layout/`, story 2.4) and design tokens (2.3/2.4) for the placeholder/error states.
- **No inline query keys.** The `local-rules/no-inline-query-keys` ESLint rule is already `error` on all `src/**` (eslint.config.js:85). Since loaders are stub seams (no `useQuery`), routes won't trip it — **keep it that way**; do NOT introduce queries (that's 2.6).
- **This story ADDS dependencies** (`@tanstack/react-router` runtime, `@tanstack/router-plugin` dev) — unlike 2.3/2.4. That triggers the **load-bearing lockfile cross-platform discipline** (Task 9, memory `frontend-lockfile-cross-platform`): full `npm install` with module-local npm 10.8.2, then Linux verification. This is the highest-risk part of the story.

## Acceptance Criteria

1. **Given** `src/routes/`, **Then** TanStack Router's route tree is defined with typed routes: `/` (root), `/workflows` (WorkflowsRoute — list), `/workflows/$workflowRunId` (WorkflowDetailRoute), `/workflows/$workflowRunId/artifacts/$artifactId` (ArtifactViewerRoute), `*` (NotFoundRoute).
2. **Given** route params, **Then** `$workflowRunId` and `$artifactId` are typed with validation that rejects malformed IDs (prefix must match `run_` or `art_` per story 1.4 prefix registry) — invalid params route to a dedicated "Invalid link" state rather than crashing.
3. **Given** a route loader pattern, **Then** each route uses TanStack Router's `loader` to prefetch the primary TanStack Query (story 2.6) data before rendering — so a deep-linked `/workflows/run_123` renders detail directly without a loading flash on navigation.
4. **Given** missing-resource handling, **Then** when a route loader returns a 404 from the backend, the route renders a dedicated "Run not found" / "Artifact not found" state (leveraging empty-state patterns from story 2.22) — not a generic error page.
5. **Given** deep links from external sources (email, CLI output from story 1.15, Linear comment), **Then** pasting a URL like `http://localhost:8080/workflows/run_123` loads the run detail directly — handled via SpaFallbackController from story 2.28.
6. **Given** browser back/forward, **Then** navigation preserves run identity and scroll position where meaningful; navigating from `WorkflowsRoute` → `WorkflowDetailRoute` → `ArtifactViewerRoute` → back returns to detail with the prior scroll state.
7. **Given** a generated route tree file, **Then** TanStack Router's code generation is wired into the Vite build (`tsr generate` or equivalent) and the generated file is gitignored — developers regenerate on route changes; CI regenerates + git-diffs to catch drift.
8. **Given** the "unsupported workspace states" case (UX-DR6), **Then** explicit UI handles: a run in a state the current build doesn't recognize (future E3+ states visible from older E2 builds), an artifact type the current ARP can't render, a permission-restricted navigation attempt.
9. **Given** correlation-ID propagation from story 1.19, **Then** route loaders include a correlation ID header on their API calls so server-side logs can be traced back to the UI navigation that triggered them.
10. **Given** party-mode finding #3 (generalized composites), **Then** the route tree is shaped so the same `WorkflowDetailRoute` serves spec-stage, implementation-plan-stage, and PR-output-stage runs in E3 without route structural changes — achieved by letting the backend-reported `currentStage` drive ARP variant selection inside the route, not by splitting routes per stage.

## Tasks / Subtasks

- [x] **Task 1: Add TanStack Router + the Vite file-based-routing plugin** (AC: 1, 7) — Q1
  - [x] Add `@tanstack/react-router` to `dependencies` and `@tanstack/router-plugin` to `devDependencies` in `package.json`. Pin to versions that resolve cleanly against **React 18.3.1** (peer) and **Vite 8** (`vite ^8.0.12`). At authoring time the current line is `@tanstack/react-router` / `@tanstack/router-plugin` ~`1.166.x` — **verify the resolved version actually supports Vite 8** during install (Vite 8 is recent; if the plugin's Vite peer range excludes 8, pick the lowest plugin version whose peer range includes Vite 8, or escalate). Do NOT add `@tanstack/router-devtools` (extra dep; if a dev wants devtools, gate behind `import.meta.env.DEV` in a later story).
  - [x] **Lockfile (LOAD-BEARING):** because a dependency is added, regenerate `package-lock.json` with a **full `npm install`** using the **module-local pinned npm 10.8.2** (`./.frontend-node/...` per pom.xml `npm.version`) — **NOT** `--package-lock-only`, **NOT** ambient npm. This preserves the platform-native binding set CI's Linux job needs (memory `frontend-lockfile-cross-platform`; cost 4 CI rounds in 2.1). Verify `npm ci` succeeds locally afterward.
  - [x] Wire the plugin into `vite.config.ts`: import `{ tanstackRouter } from '@tanstack/router-plugin/vite'` and add `tanstackRouter({ target: 'react' })` to `plugins` **BEFORE** `react()` (plugin ordering matters — the router plugin must transform before the React plugin). Keep all story-2.1 server/proxy/build config intact (the `resolvePort` guard, `strictPort`, `/api` proxy, `outDir: 'target/dist'`). Defaults are fine: `routesDirectory: './src/routes'`, `generatedRouteTree: './src/routeTree.gen.ts'`, `quoteStyle: 'single'`.
  - [x] Confirm `npm run build` (`tsc -b && vite build`) regenerates `src/routeTree.gen.ts` and the build succeeds. The plugin generates the tree during both `vite dev` and `vite build`.

- [x] **Task 2: Gitignore + lint/format-ignore the generated route tree** (AC: 7)
  - [x] Add `src/routeTree.gen.ts` to `deliveryline-frontend/.gitignore` (it is regenerated on every build — never commit it, per AC7).
  - [x] Add `src/routeTree.gen.ts` to the `ignores` array in `eslint.config.js` (it is machine-generated; linting it is noise and would fail strict rules).
  - [x] Add `src/routeTree.gen.ts` to `.prettierignore` (managed file — Prettier must not reformat it, or `format:check` would fight the generator).
  - [x] **Drift protection (AC7 resolution under gitignore):** since the tree is gitignored, there is no committed file to `git diff` — the protection is that **the Vite plugin regenerates it on every `npm run build`** (the enforced Maven/CI path), so CI always builds against a fresh tree and the build *fails* if any route file is malformed or the tree can't be generated. Add a short comment in `vite.config.ts` documenting this. (Do NOT commit the tree to enable a literal git-diff — AC7 mandates gitignore, and build-time regeneration makes a stale committed tree impossible.) Optionally, the `check:routes` Maven step (Task 8) asserts the tree was generated (file exists + non-empty) so a silently-disabled plugin is caught.

- [x] **Task 3: Root route + RouterProvider wiring + preserve dev playground** (AC: 1, 8)
  - [x] Create `src/routes/__root.tsx`: the root layout route via `createRootRoute({ … })` (or `createRootRouteWithContext<RouterContext>()` to leave a typed seam for 2.6 to inject the `queryClient` — recommended, see Dev Notes). It renders an `<Outlet />` and defines:
    - `notFoundComponent` — the **`*` NotFoundRoute** (AC1): a dedicated "Page not found" state for any unmatched URL (idiomatic TanStack Router replacement for a literal `*` route).
    - `errorComponent` — a generic error boundary state (distinct from not-found).
  - [x] Replace the placeholder in `src/App.tsx`: build the router with `createRouter({ routeTree })` (importing the **generated** `routeTree` from `./routeTree.gen`) and render `<RouterProvider router={router} />`. Add the `declare module '@tanstack/react-router' { interface Register { router: typeof router } }` block so route APIs are globally typed. Configure `scrollRestoration: true` on the router (AC6) and `defaultNotFoundComponent` / `defaultErrorComponent` fallbacks if not set per-route.
  - [x] **Preserve the dev-only PrimitivesPlayground (story 2.2/2.4):** it currently lives at `src/routes/_dev/PrimitivesPlayground.tsx` — but `src/routes/` is now the **generator-owned** directory, and `_dev/PrimitivesPlayground.tsx` would be swept into the production route tree (TanStack treats `_dev` as a pathless layout group, not an ignore). **Move it OUT of the routes dir** → `src/dev/PrimitivesPlayground.tsx`, update the import in `App.tsx`, and keep the existing dev-only mount (`import.meta.env.DEV` + `?playground` escape hatch) **wrapping/short-circuiting before `RouterProvider`** so the playground stays a dev-only, non-routed view and is tree-shaken from prod (the 2.2 discipline). Update any reference in docs/README to the new path.
  - [x] Keep `main.tsx` unchanged in spirit (it renders `<App />`); the router lives inside `App`.

- [x] **Task 4: Typed routes + param validation** (AC: 1, 2, 10)
  - [x] Create the route files under `src/routes/` (file-based naming):
    - `index.tsx` → path `/`: **redirect to `/workflows`** via `beforeLoad`/`loader` `throw redirect({ to: '/workflows' })` (the queue is the canonical entry surface — ux:1833; architecture.md:467). This keeps AC1's distinct `/` (root) and `/workflows` (list) while making `/workflows` the real list URL.
    - `workflows/index.tsx` → path `/workflows`: **WorkflowsRoute** (queue/list placeholder).
    - `workflows/$workflowRunId.tsx` (or `workflows/$workflowRunId/index.tsx` if nesting the artifact route) → **WorkflowDetailRoute**.
    - `workflows/$workflowRunId/artifacts/$artifactId.tsx` → **ArtifactViewerRoute**.
  - [x] **Param validation (AC2):** use the route's `params: { parse, stringify }` option (or `beforeLoad`) to validate `$workflowRunId` against `^run_[A-Za-z0-9_-]{4,64}$` and `$artifactId` against `^art_[A-Za-z0-9_-]{4,64}$`. On a malformed ID, route to a dedicated **"Invalid link"** state (NOT a crash, NOT the generic not-found) — e.g. `throw notFound()` into a route-level `notFoundComponent` that distinguishes "this link is malformed" from "this resource doesn't exist", or render an explicit invalid-param component.
  - [x] **Extract the validators as pure functions** in `src/lib/routing/publicId.ts`: `isValidRunId(s): boolean`, `isValidArtifactId(s): boolean` (and/or a generic `matchesPublicId(prefix, s)`), re-encoding the **V1 regex** `^<prefix>_[A-Za-z0-9_-]{4,64}$`. Add a doc-comment naming the **backend source of truth** (`deliveryline-backend` `PublicIdPrefixes` / Flyway V1 CHECK regex, story 1.4) and flag this as **accepted cross-language duplication** (no shared codegen across the JVM/TS boundary yet — a generated registry is out of scope). These pure functions are what the `node --test` gate (Task 8) covers.
  - [x] **AC10 — single detail route across stages:** `WorkflowDetailRoute` must NOT be split per stage. Shape it so a future `currentStage` field (spec / implementation-plan / PR-output) drives ARP-variant selection *inside* the route (a TODO-marked seam now — ARP is 2.17/3.26/3.27). Add a code comment asserting "do not add per-stage routes; stage drives variant inside this route" so E3 doesn't fork the tree.

- [x] **Task 5: Loader seam + explicit UI states** (AC: 3, 4, 8) — Q2 (structure + seam, no network)
  - [x] **Loader pattern (AC3):** give each data route a `loader` that returns **typed stub data** (a hand-written fixture shaped like the eventual response — e.g. `{ workflowRunId, currentStage, … }`). Add a prominent `// SEAM (story 2.6):` comment showing where `queryClient.ensureQueryData(workflowKeys.detail(workflowRunId))` + the correlation-ID header (AC9) will replace the stub. Do NOT call `fetch`/`useQuery` and do NOT invent query keys (no-inline-query-keys is `error`).
  - [x] **Not-found states (AC4):** loaders may `throw notFound()` to drive a dedicated **"Run not found"** / **"Artifact not found"** state (per-route `notFoundComponent`, distinct from the malformed-link state and the generic root not-found). Story 2.22 (empty-state patterns infra) is **not built** — build **minimal inline states now** using the existing layout primitives + typography/tokens (2.3/2.4), and add a `// SEAM (story 2.22):` note that these adopt the shared empty-state component when it lands.
  - [x] **Unsupported workspace states (AC8 / UX-DR6):** add explicit, distinct UI for the three cases: (a) a run in an **unrecognized state** (an E3+ state seen from an older E2 build) — render a "This run is in a newer state this build can't display" panel, not a crash; (b) an **unrenderable artifact type** — "This artifact type isn't viewable here yet"; (c) a **permission-restricted navigation** — a recorded-role-aware "not available" state (UI must NOT enforce auth — architecture.md:519; label as recorded, not enforced). These can be small placeholder components in the route files plus a shared `src/routes/-states/` helper dir (the `-` prefix is the router's **ignore prefix** so these helpers are NOT treated as routes).
  - [x] All placeholder route bodies stay minimal (a heading + the run/artifact identity + a "real composite arrives in story 2.X" note) — no product UI, no data fetching.

- [x] **Task 6: Deep-link, back/forward, scroll restoration** (AC: 5, 6)
  - [x] **Back/forward + scroll (AC6):** confirm `scrollRestoration: true` on the router restores scroll position on back navigation (queue → detail → artifact → back returns to detail with prior scroll). Run identity is preserved by the URL itself (it carries `$workflowRunId`). Verify manually in `npm run dev` (document the steps in the Dev Agent Record).
  - [x] **Deep links (AC5):** in **dev**, the Vite dev server's SPA history fallback already serves any path to `index.html`, so `http://localhost:5173/workflows/run_123` resolves to the detail route — verify this manually. **Production** deep links depend on the **SpaFallbackController (story 2.28, not built)**; add a `// SEAM (story 2.28):` note in `src/routes/README.md` (Task 9) that prod deep-link fallback lands then. Do NOT add a backend controller here (out of module + out of scope).

- [x] **Task 7: Correlation-ID propagation seam** (AC: 9) — deferred wiring
  - [x] AC9 needs a correlation-ID request header on loader API calls (story 1.19 correlation IDs). There are **no API calls yet** (Q2). Establish the seam only: a typed placeholder util (e.g. `src/lib/api/correlation.ts` exporting `newCorrelationId()` using `crypto.randomUUID()` and the canonical header name, with a doc-comment) **and** a `// SEAM (story 2.6/1.19):` marker in each loader showing where the header attaches once the API client exists. Do NOT build an HTTP client (that's 2.6). Keep this minimal — a util + comments, no runtime wiring.

- [x] **Task 8: `node --test` param-validation gate + route-tree generation gate** (AC: 2, 7) — Q3
  - [x] Add `tools/routing/__tests__/public-id-params.test.js` (`node --test`) covering the pure validators from `src/lib/routing/publicId.ts`:
    - accept valid `run_`/`art_` IDs (e.g. `run_abcd`, `art_AB-_12`); reject wrong prefix (`evt_abcd`, `xrun_abcd`), too-short suffix (`run_abc`, <4 chars), too-long suffix (>64), whitespace (`run_  `, `run_abcd `), injection-y input (`run_'; DROP`), empty/`undefined`, and the bare prefix (`run_`).
    - a **negative self-test** proving the gate fails if a validator is loosened to `startsWith` (mirror the 2.3/2.4/1.4 discipline so the gate can't silently no-op).
    - **Note:** the validator file is `.ts` and tests are `node --test` `.js` — either keep the regex/predicate in a tiny framework-free `.ts` that the test imports after `tsc`, OR (simpler, matches `tools/contrast/parse-globals.js`) put the canonical regex in a plain `.js`/`.ts` helper the test can require directly, and have `publicId.ts` import it. Pick the approach that keeps the test runnable with zero build step (the `tools/` pattern runs raw `node --test` on `.js`). Document the choice.
  - [x] Add `"check:routes": "node --test tools/routing/__tests__/public-id-params.test.js"` to `package.json` scripts.
  - [x] **Wire `check:routes` into `pom.xml`** frontend-maven-plugin as a new `process-resources` execution (`npm-run-check-routes`), mirroring the existing `npm-run-check-tokens` / `npm-run-check-contrast` / `npm-run-lint-rules-test` executions, so it runs on the enforced Maven/CI path (`frontend-build-tests`), not just locally. Verify the execution fires in the reactor build.
  - [x] (Optional, satisfies AC7 generation assertion) the same Maven path already runs `npm run build`, which regenerates `src/routeTree.gen.ts`; if you want an explicit guard, the `check:routes` test (or a tiny extra assertion) can verify `src/routeTree.gen.ts` exists and is non-empty after build — catching a silently-disabled plugin.

- [x] **Task 9: Routing docs + cross-links** (supports AC1, AC2, AC4, AC5, AC8, AC10)
  - [x] Add `src/routes/README.md` documenting: the route tree + which file owns which path; the param-validation contract (`run_`/`art_` regex + backend source of truth + accepted duplication); the loader **seam** and what 2.6 fills in; the **deferred items** (real query prefetch AC3, backend-404 AC4, correlation header AC9, prod SPA-fallback deep links AC5 → 2.28); the AC8 unsupported-state taxonomy; and the AC10 "single detail route, stage drives variant" rule. Keep it concise and link to the relevant story numbers.
  - [x] Extend `deliveryline-frontend/README.md` with a short "Routing" section cross-linking `src/routes/README.md` (mirror how 2.3/2.4 cross-linked the Design System section). Update the playground reference if its path changed (Task 3).
  - [x] Log the deferred AC sub-items (AC3 real prefetch, AC4 backend-404, AC5 prod fallback, AC9 correlation header) in `_bmad-output/implementation-artifacts/deferred-work.md` with their owning stories (2.6 / 2.28), matching the 2.1/2.3 defer-logging convention.

- [x] **Task 10: Logging instrumentation** (cross-cutting; required on every story)
  - [x] **Scope note:** this is a frontend routing/config/docs story. It introduces **no JVM application-service classes, no domain exceptions, no SPI calls, no state transitions** — so the SLF4J/MDC standard (entry/exit INFO, WARN typed-rejection, ERROR unhandled, `correlationId`/`workflowRunId`/`idempotencyKey`/`actorIdentity` keys) has no application code to instrument here. The standard remains binding for any future JVM code.
  - [x] Client-side structured logging is out of scope (later stories). Route components, loaders, and error/not-found boundaries must **not** `console.*` in render or in normal navigation paths (the existing dev-only scaffold `console.warn` is being removed with the placeholder App — do not reintroduce console noise). The `node --test` tooling uses the test reporter only — no `System.out`/`printStackTrace()` (no JVM code exists here).
  - [x] The **correlation-ID seam** (Task 7) is the deliberate hook so that, once the API client lands (2.6), every loader-triggered request is traceable back to the navigation — note this in `src/routes/README.md`.

- [x] **Task 11: Cross-platform lockfile + reactor/CI verification** (cross-cutting; LOAD-BEARING — this story ADDS deps)
  - [x] This story **adds runtime + dev dependencies** (`@tanstack/react-router`, `@tanstack/router-plugin`). Follow the exact 2.1/2.2 lockfile discipline: regenerate `package-lock.json` via a **full `npm install`** with module-local pinned npm 10.8.2 (never `--package-lock-only`, never ambient npm); confirm `npm ci` is green.
  - [x] **Verify on Linux** before done: `docker run --rm -v "<frontend>:/app" -w /app node:20.19.0 bash -c "npm ci --no-audit --no-fund --prefer-offline && npm run build && npm run check:contrast && npm run check:tokens && npm run check:routes && npm run lint && npm run lint:rules-test && npm run format:check"` → all green. (Use an anonymous `node_modules` volume on Windows hosts, as in 2.3/2.4, so the Linux-native bindings install cleanly.)
  - [x] Reactor smoke: `./mvnw -B -ntp -DskipTests clean install` green (all 4 modules); backend jar still embeds the SPA at `BOOT-INF/classes/static/`. Confirm the new `check:routes` execution fires on the frontend-maven-plugin path and that `frontend-build-tests` (ubuntu + windows) + `format-static-checks` + `foundation-gate` stay green. Confirm `src/routeTree.gen.ts` is regenerated by the build and is **not** tracked by git (`git status` clean — the gitignore entry works).

## Dev Notes

### Story scope — the navigation skeleton (Layer between tokens and composites)

The frontend layers so far: **2.1** scaffold → **2.2** primitives → **2.3/2.4** design tokens + layout primitives. This story adds the **navigation skeleton**: a typed route tree with validated params and every explicit dead-end UI state, plus the **loader seam** the data layer (2.6) and composites (2.7, 2.15–2.19) plug into. It is intentionally data-free: per Q2, there is no backend or query layer to call yet, so this story proves *navigation correctness and failure-state coverage*, not data flow. The four user questions the UI must answer (what happened / what is current / who owns it / next safe action — architecture.md:498) are answered by the composites later; this story just guarantees you can always *reach* a meaningful state and never a blank page.

### Resolved decisions (confirmed by Alex 2026-05-21 — already baked into the boundaries above)

- **Q1 — File-based routing** via `@tanstack/router-plugin/vite` → gitignored `src/routeTree.gen.ts`. Architecture's named code-based files were illustrative; file-based equivalents map cleanly and satisfy AC7 literally. (Tasks 1–4.)
- **Q2 — Route structure + all UI states now; loaders are typed stub seams; real query prefetch / backend-404 / correlation header / prod deep-link fallback deferred to 2.6/2.28.** AC3/AC4/AC5/AC9 ship as structure + seam + documented deferral. (Tasks 5–7, 9.)
- **Q3 — `node --test` for pure param validators + route-tree generation gate; no Vitest/Playwright (2.27 owns route/component/a11y tests).** (Task 8.)

### Current frontend state this story builds on (stories 2.1–2.4 — 2.2/2.3 done, 2.4 in review)

- **`src/App.tsx`** is currently a **placeholder** (a centered "DeliveryLine scaffold" card) with a dev-only `?playground` escape hatch that lazy-loads `src/routes/_dev/PrimitivesPlayground.tsx` and a dev-only `console.warn`. **This story replaces the placeholder with `RouterProvider`** and **relocates the playground out of `src/routes/`** (now generator-owned) — see Task 3. Remove the scaffold `console.warn` (do not reintroduce console noise — Task 10).
- **`src/main.tsx`** renders `<StrictMode><App /></StrictMode>` with a guard on `#root`. Leave it; the router lives in `App`.
- **`vite.config.ts`** (story 2.1): `resolvePort` guard + `strictPort` + `host:true` + `/api`→`:8080` proxy (`ws:true`, `secure:false`) + `build.outDir:'target/dist'` + `resolve.alias` `@`→`./src` (2.2). **Add the `tanstackRouter()` plugin before `react()`; change nothing else.**
- **`tsconfig.app.json`**: strict + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes` + `verbatimModuleSyntax` + `moduleResolution:'bundler'` + `paths` `@/*`→`./src/*`. Route files and validators must compile clean under these. `verbatimModuleSyntax` means **type-only imports must use `import type`** (the `@typescript-eslint/consistent-type-imports` rule is `error`). The generated `routeTree.gen.ts` is excluded from tsc lint concerns by being lint/format/git-ignored, but it IS part of the `tsc -b` graph (imported by App) — that's expected and fine.
- **ESLint** (`eslint.config.js`, story 2.31): full strict type-aware ruleset on `src/**`. Relevant teeth for this story:
  - `local-rules/no-inline-query-keys: error` — do NOT introduce inline query keys; loaders are stub seams (no queries) so this stays satisfied.
  - `@typescript-eslint/no-floating-promises: error` — router `navigate(...)` returns a Promise; in handlers either `void navigate(...)` or `await` it (don't leave it floating). Prefer `<Link>` for declarative nav to avoid the issue entirely.
  - `@typescript-eslint/no-misused-promises: error` — don't pass an async fn directly to a non-Promise event prop.
  - `react-refresh/only-export-components: ['warn', { allowConstantExport: true }]` — route files export a `const Route` alongside their component; the `allowConstantExport` flag permits this. Keep route component + `Route` export colocated as TanStack expects.
  - `consistent-type-imports: error` — `import type { … }` for type-only TanStack imports.
  - Remember to add `src/routeTree.gen.ts` to the config's `ignores` (Task 2).
- **Layout primitives** (`src/components/layout/`, story 2.4): `Stack`/`Inline`/`Grid`/`Container`/`Divider` with the closed `GapToken` API — **use these** for the placeholder/error/not-found state layouts. **Typography classes** (`.text-page-title`/`.text-section-heading`/`.text-body`/`.text-meta`, 2.4) and **state tokens** (2.3) style the dead-end states. `cn()` (`src/lib/utils.ts`) composes classNames.
- **`tools/` `node --test` pattern**: `tools/contrast/` (`check:contrast`, 8/8), `tools/tokens/` (`check:tokens`, 4/4), `tools/eslint-rules/__tests__/` (`lint:rules-test`, 2/2). New `tools/routing/` (`check:routes`) mirrors this exactly — pure-function tests, framework-free, wired into `pom.xml`.
- **`pom.xml`** frontend-maven-plugin runs (in order): `install-node-and-npm` + `npm ci` + `npm run build` (generate-resources), then `lint` / `lint:rules-test` / `check:contrast` / `check:tokens` / `format:check` (process-resources). **Add `npm-run-check-routes` to process-resources** (Task 8).
- **CI** (`.github/workflows/ci.yml`): `frontend-build-tests` matrix (ubuntu + windows) runs `./mvnw -pl deliveryline-frontend clean package` — the full pipeline including the new `check:routes`. `foundation-gate` depends on it. The npm cache is workspace-pinned (`npm_config_cache`), cache key hashes `pom.xml` + `package-lock.json` (so the new dep invalidates the cache cleanly).

### Architecture-prescribed structure (architecture.md)

- **Frontend stack** (architecture.md:445-461): Vite + React + TS, TanStack Query (2.6), **TanStack Router (this story)**, shadcn/ui + Tailwind, bundled into Spring Boot. "TanStack Router gives typed deep links for workflow and artifact views."
- **Routing guardrails** (architecture.md:505, 517): "React routes must support direct deep links through Spring Boot static serving fallback" (→ 2.28). "TanStack Router route params for workflow and artifact IDs must be typed and validated. Deep links, missing workflows, missing artifacts, unsupported routes, and unsupported workspace states need explicit UI handling." (→ AC2, AC4, AC8 — this story.)
- **Source layout** (architecture.md:693, 1065-1090, 1321): `routes` = "TanStack Router route definitions"; structure shows `routes/` (root + WorkflowsRoute + WorkflowDetailRoute + ArtifactViewerRoute), `features/workflows/`, `components/{ui,layout,feedback}/`, `lib/{api,queryKeys,utils}`, `styles/`. "Frontend source follows routes/features/components/lib/styles boundaries." File-based equivalents: `src/routes/__root.tsx`, `src/routes/workflows/index.tsx`, `src/routes/workflows/$workflowRunId.tsx`, `src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx`.
- **Frontend behavior rules** (architecture.md:485-490, 514, 519): backend-reported allowed actions drive controls; no frontend-only state transitions; **no frontend interpretation of approval eligibility / permission enforcement** (AC8's permission-restricted state is a *recorded-role-aware display*, never an enforcement gate); UI labels must mark roles as recorded, not enforced.
- **Quality gates** (architecture.md:512, 520): SPA fallback / direct-refresh / route-not-found must be explicitly tested (the *packaging/integration* test is 2.28; route-not-found *UI* is here); the review UI must include "route-not-found" among its required states.
- **`RouterContext` seam (recommended):** use `createRootRouteWithContext<{ queryClient: QueryClient }>()` even though `QueryClient` doesn't exist yet — type the context with a minimal interface now (or `unknown`/a placeholder) so 2.6 injects the real `queryClient` into `createRouter({ context })` without reshaping the root route. Document this as the 2.6 integration point. If typing `QueryClient` before 2.6 adds friction, leave context empty (`{}`) and note 2.6 will widen it.

### UX requirements (authoritative for AC5, AC6, AC8, AC10)

From the UX spec (ux:381-402, 1825-1862):
- **Run-centered navigation** (ux:381-402, 1827): a run-centered review flow, NOT broad dashboard navigation; the **queue is the primary entry surface** (→ `/` redirects to `/workflows`); avoid navigation that makes users hunt for current run state.
- **Navigation must always preserve** (ux:1842-1846): current run identity, current artifact identity, current workflow state — these ride in the URL params (`$workflowRunId`, `$artifactId`) so they survive deep links, refresh, and back/forward (AC6). **Back navigation returns to the prior meaningful review context, not a generic top-level page** (→ scroll restoration + URL-encoded identity, not a reset to `/`).
- **Compare/clarification are bounded states inside a run** (ux:1836, 1840), not separate product areas — reinforces AC10's "one detail route, variants inside" shape (don't fork routes for modes/stages).
- **Accessibility** (ux:1848-1851): navigation landmarks explicit; keyboard users move predictably between regions; selected/active states programmatically exposed. The full landmark/keyboard work is the AppShell (2.7) + a11y audit (2.25); here, ensure route components don't *prevent* it (use semantic placeholders, no `autofocus` — `jsx-a11y/no-autofocus` is `error`).
- **UX-DR6 (epics.md:248):** "routing with TanStack Router and typed route params … explicit UI handling for deep links, missing workflows, missing artifacts, unsupported routes, and unsupported workspace states" — the authoritative source for AC2/AC4/AC8.

### Latest tech specifics (verified May 2026)

- **`@tanstack/react-router`** + **`@tanstack/router-plugin`** current line ~`1.166.x`. File-based setup: add `tanstackRouter({ target: 'react' })` to Vite `plugins` **before** `@vitejs/plugin-react`. Defaults: `routesDirectory './src/routes'`, `generatedRouteTree './src/routeTree.gen.ts'`, `routeFileIgnorePrefix '-'` (so `src/routes/-states/` helpers are NOT routes), `quoteStyle 'single'`. (Source: TanStack Router "Installation with Vite" docs.)
- **`autoCodeSplitting`** is available in the plugin; **leave it off** for this story (extra build complexity; the route bodies are tiny placeholders). A later perf story can enable it.
- **React 18.3.1** is supported by TanStack Router v1 (also supports React 19). No React upgrade needed.
- **Vite 8 compatibility is the install-time risk** (Vite 8 is recent): verify the resolved `@tanstack/router-plugin` version's peer range includes Vite 8 during `npm install`; if it doesn't resolve, pick the lowest plugin version whose peer range includes Vite 8 (or escalate to Alex). Do not downgrade Vite.
- **Generated-file hygiene** (TanStack docs): the generated route tree should be excluded from linters/formatters (it's tool-managed) — Task 2 covers eslint/prettier/git ignores.
- **Not-found idiom:** TanStack Router uses `notFoundComponent` on the root (and per-route) + `throw notFound()` in loaders, rather than a literal `*` catch-all route — this is the correct realization of AC1's `*` NotFoundRoute.

### Anti-patterns to avoid

- **Do NOT** commit `src/routeTree.gen.ts` (AC7 mandates gitignore) — and do NOT lint/format it (add to all three ignore lists, Task 2).
- **Do NOT** place the dev playground inside `src/routes/` after enabling the plugin — it would become a production route. Relocate it to `src/dev/` and keep the dev-only `?playground` mount (Task 3).
- **Do NOT** call `fetch`/`useQuery` or invent query keys in loaders — that's 2.6, and `no-inline-query-keys` is `error`. Loaders return typed stubs (Q2).
- **Do NOT** add Vitest/Playwright/React-testing-library — that's 2.27. Use `node --test` for the pure validators (Task 8).
- **Do NOT** split `WorkflowDetailRoute` per stage — one route, `currentStage` drives the variant inside (AC10).
- **Do NOT** enforce permissions/approval-eligibility in route guards — AC8's permission-restricted state is a *display*, not a gate (architecture.md:519). UI never gates on audit-role labels.
- **Do NOT** leave router `navigate()` promises floating (`no-floating-promises` is `error`) — prefer `<Link>`; `void`/`await` when imperative.
- **Do NOT** build a backend SPA-fallback controller here (story 2.28) or any HTTP client (story 2.6) — establish seams + comments only.
- **Do NOT** regenerate the lockfile with `--package-lock-only`, skip Linux verification, or `git add .` — this story adds deps, so the full-`npm install` + Linux-verify discipline is mandatory (Task 11; memory `frontend-lockfile-cross-platform`).
- **Do NOT** touch `src/components/ui/*` primitives (2.2 AC4), the 2.3 color tokens, or 2.4 typography/spacing tokens — `check:contrast` stays 8/8, `check:tokens` stays green.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.5] — authoritative ACs (lines 924-941)
- [Source: _bmad-output/planning-artifacts/epics.md#UX-DR6] — typed routes + explicit handling for deep links / missing workflows / missing artifacts / unsupported routes / unsupported workspace states (line 248)
- [Source: _bmad-output/planning-artifacts/epics.md] — downstream consumers: WorkflowDetailRoute renders Run Context Strip (2.16, line 1226) + ARP (2.17); Queue Item emits `onOpen(runId)` nav intent (2.15, line 1203); nav helpers preserve run/artifact/state (2.22, line 1345); route tests (2.27, line 1458); SpaFallbackController (2.28, line 1476)
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture] — TanStack Router decision + routing guardrails (lines 445-520)
- [Source: _bmad-output/planning-artifacts/architecture.md#Project Structure] — `routes/` layout + boundaries (lines 693, 1065-1090, 1321)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Navigation Patterns] — run-centered nav, preserve run/artifact/state identity, back-to-prior-context, bounded in-run states (lines 381-402, 1825-1862)
- [Source: _bmad-output/implementation-artifacts/1-4-central-registries-with-drift-tests.md] — `PublicIdPrefixes` V1 regex `^<prefix>_[A-Za-z0-9_-]{4,64}$` for `run_`/`art_` (backend source of truth for AC2 validators; lines 21, 34, 96, 115)
- [Source: _bmad-output/implementation-artifacts/2-4-...-layout-primitives.md] — layout primitives + typography classes + tokens to compose dead-end states; `node --test` gate + frontend-maven-plugin wiring pattern; lockfile/playground discipline
- [Source: deliveryline-frontend/src/App.tsx] — placeholder + dev `?playground` mount to replace with `RouterProvider`
- [Source: deliveryline-frontend/vite.config.ts] — add `tanstackRouter()` before `react()`; preserve story-2.1 server/proxy/build config
- [Source: deliveryline-frontend/eslint.config.js] — add `src/routeTree.gen.ts` to ignores; `no-inline-query-keys`/`no-floating-promises`/`consistent-type-imports` teeth
- [Source: deliveryline-frontend/pom.xml] — mirror `npm-run-check-tokens` execution for `npm-run-check-routes`
- [Source: deliveryline-frontend/tools/contrast/parse-globals.js, tools/tokens/__tests__/typography-tokens.test.js] — the framework-free `node --test` + negative-self-test pattern to mirror in `tools/routing/`
- [Source: TanStack Router docs — Installation with Vite] — `tanstackRouter({ target: 'react' })` before `react()`, generated `routeTree.gen.ts`, ignore-the-generated-file guidance (https://tanstack.com/router/latest/docs/installation/with-vite)
- [Source: project memory `frontend-lockfile-cross-platform`, `verify-ci-fixes-in-clean-env`, `commit-no-claude-coauthor`]

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):** Public application-service methods → `INFO` on entry + `INFO` on success / `WARN` on typed-domain rejection / `ERROR` on unexpected failure; persistence writes → `INFO` + `WARN` on idempotency replay + `ERROR` on unmapped integrity violation; file/network I/O → `INFO` + `WARN` on retry + `ERROR` on unrecoverable failure; state-machine transitions → `INFO`; reconciliation/recovery loops → `INFO` per-batch + `WARN` per-item.
- **Required context keys** (MDC or structured params): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus any entity public ids.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields — route through the existing redaction/classification path.
- **Test contract:** new logging surfaces must be pinned by a focused test (list-appender or `OutputCaptureExtension`).
- **This story's applicability:** frontend-only (routing/config/docs) — **no JVM code is introduced**, so there is no application logging surface to instrument here. The standard remains binding for any future JVM code. The **correlation-ID seam (Task 7/AC9)** is the deliberate hook so that once the API client lands (2.6), loader-triggered requests are traceable back to the navigation — front-end client-side structured logging itself is a later story.

### Project Structure Notes

- **New paths:** `src/routes/__root.tsx`, `src/routes/index.tsx`, `src/routes/workflows/index.tsx`, `src/routes/workflows/$workflowRunId.tsx`, `src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx`, `src/routes/-states/*` (ignore-prefixed shared dead-end state helpers), `src/routes/README.md`, `src/lib/routing/publicId.ts`, `src/lib/api/correlation.ts` (seam), `src/dev/PrimitivesPlayground.tsx` (relocated from `src/routes/_dev/`), `tools/routing/__tests__/public-id-params.test.js`, and the generated-but-gitignored `src/routeTree.gen.ts`.
- **Modified:** `vite.config.ts` (add `tanstackRouter()`), `src/App.tsx` (RouterProvider + playground import path), `package.json` (deps + `check:routes` script), `package-lock.json` (regenerated — full `npm install`), `eslint.config.js` (ignore generated tree), `.gitignore` (ignore generated tree), `.prettierignore` (ignore generated tree), `pom.xml` (`npm-run-check-routes` execution), `deliveryline-frontend/README.md` (Routing section + playground path), `_bmad-output/implementation-artifacts/deferred-work.md` (deferred AC sub-items), `sprint-status.yaml` (status).
- **No conflicts with unified structure:** routes under `src/routes/` (generator-owned), routing utils under `src/lib/routing/`, tooling under `tools/routing/` (consistent with `tools/contrast/`, `tools/tokens/`, `tools/eslint-rules/`). The playground moves OUT of `src/routes/` precisely *because* that dir is now generator-owned.

## Dev Agent Record

### Review Findings

- [x] [Review][Patch] Route param validation happens after loaders, so malformed IDs are not rejected at the route boundary as specified [deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx:39]
- [x] [Review][Patch] Artifact viewer uses `specification` instead of the backend's canonical `spec` artifact type, which will misclassify real spec artifacts as unrenderable once the 2.6 seam is wired [deliveryline-frontend/src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx:29]
- [x] [Review][Patch] `routes:generate` imports `@tanstack/router-generator` without declaring it, leaving clean builds dependent on transitive-package hoisting [deliveryline-frontend/tools/routing/generate-route-tree.js:18]
- [x] [Review][Patch] `check:routes` validates only regex helpers and does not perform the documented route-tree emission/drift check [deliveryline-frontend/package.json:16]

### Agent Model Used

claude-opus-4-7 (Opus 4.7, 1M context)

### Debug Log References

- `npm run build` (clean, no pre-existing tree): `routes:generate` → `tsc -b` → `vite build` all green; bundle `index-*.js` 263.75 kB / gzip 85.34 kB. Verified the clean-CI ordering by deleting `src/routeTree.gen.ts` first.
- Gates (Windows, module-local node v20.19.0 / npm 10.8.2): `check:routes` 8/8, `check:contrast` 8/8, `check:tokens` 4/4, `lint:rules-test` 2/2, `lint` clean (`--max-warnings=0`), `format:check` clean.
- **Linux verification (Docker `node:20.19.0`, anonymous `node_modules` volume):** `npm ci && npm run build && check:contrast && check:tokens && check:routes && lint && lint:rules-test && format:check` — all green. Lockfile is cross-platform clean (the `@tanstack/*` packages are pure JS, no native bindings).
- Reactor smoke `./mvnw -B -ntp -DskipTests clean install`: **BUILD SUCCESS** (all 4 modules). `npm-run-check-routes` execution fires on the frontend-maven-plugin path (8/8). Backend jar embeds the SPA at `BOOT-INF/classes/static/{index.html,assets/}`. `src/routeTree.gen.ts` is regenerated by the build and is gitignored (not tracked).
- AC5 dev fallback verified non-interactively: `vite dev` served the SPA shell (`#root` + `@vite/client` + `main.tsx`) for the deep path `/workflows/run_sample0001` (HTTP 200).
- Two lint/type issues found and fixed during the run: (1) `throw redirect(...)` tripped `@typescript-eslint/only-throw-error` → switched to `redirect({ to, throw: true })` (the documented idiom for a void-returning hook); (2) passing a propped component directly as `notFoundComponent` clashed with `NotFoundRouteProps` → wrapped as `() => <…/>`, which also resolved the loader-data `possibly-undefined` inference (the defensive guards were then flagged as unnecessary conditions and removed).

### Completion Notes List

- **Routing approach:** file-based via `@tanstack/router-plugin/vite` (before `@vitejs/plugin-react`), generating gitignored `src/routeTree.gen.ts`. Resolved versions: `@tanstack/react-router@1.170.6`, `@tanstack/router-plugin@1.168.9` — the plugin's Vite peer range includes `>=8.0.0`, so Vite 8 is supported (no downgrade).
- **Clean-CI tsc ordering:** `npm run build` is `routes:generate && tsc -b && vite build`. `tools/routing/generate-route-tree.js` pre-generates the tree (using the plugin's own `getConfig({ target: 'react' }, root)` — no second source of truth for routing config) so `tsc` (which runs before Vite) always sees the tree on a clean checkout. `vite build` still regenerates afterwards, so the AC7 build-time-regen drift protection is intact.
- **Param validators (AC2)** are framework-free ESM in `src/lib/routing/publicId.js` + a hand-written `publicId.d.ts`, so the `node --test` gate imports them DIRECTLY (zero build step — Node 20 can't strip TS types) and exercises the exact predicates the routes use (no parallel copy to drift). Canonical regex `^<prefix>_[A-Za-z0-9_-]{4,64}$` re-encoded from the backend `PublicIdPrefixes` (story 1.4) as accepted cross-language duplication. Gate: accept/reject suites + a `startsWith`-loosening negative self-test + a source guard asserting the shipped module still encodes the anchored V1 regex.
- **Explicit UI states** (`src/routes/-states/DeadEndState.tsx`, `-`-ignored so not routes): Invalid link (AC2, malformed param — component-level, crash-free), Run/Artifact not found (AC4, wired as each data route's `notFoundComponent`), Page not found (AC1, root `notFoundComponent`), generic error boundary, and the three AC8/UX-DR6 unsupported-workspace states (unrecognized run state, unrenderable artifact type, permission-restricted — a recorded-role DISPLAY only, never an enforcement gate per architecture.md:519). Stub fields are typed loosely (string/boolean) on purpose so the AC8 guards aren't statically dead.
- **AC10:** one `WorkflowDetailRoute`; `currentStage` selects the ARP variant inside the route (recognized-stage set), with an explicit "do not fork per stage" comment.
- **Deferred per Q2 (structure + seam + documented deferral, logged in `deferred-work.md`):** AC3 real query prefetch + AC4 backend-404 `throw notFound()` + AC9 correlation header → story 2.6 (`// SEAM` markers in each loader; `src/lib/api/correlation.ts` holds `X-Correlation-Id` + `newCorrelationId()`); AC5 production SPA fallback → story 2.28; route/component/a11y tests → story 2.27 (Q3: `node --test` only, no Vitest/Playwright).
- **Playground:** relocated `src/routes/_dev/PrimitivesPlayground.tsx` → `src/dev/PrimitivesPlayground.tsx` (out of the generator-owned routes dir), kept the dev-only `?playground` mount short-circuiting before `RouterProvider`; removed the story-2.1 scaffold `console.warn` (Task 10 — no console noise reintroduced).
- **Logging (Task 10):** frontend routing/config/docs story — no JVM application code, so the SLF4J/MDC standard has no surface to instrument here; the AC9 correlation-ID seam is the deliberate hook for when 2.6's API client lands.
- **Manual check still recommended at review:** AC6 browser back/forward scroll restoration (queue → detail → artifact → back returns to detail with prior scroll) — `scrollRestoration: true` is set on the router and run/artifact identity rides in the URL params; the sample links in the placeholders make the chain clickable.

### File List

**New:**
- `deliveryline-frontend/src/routes/__root.tsx`
- `deliveryline-frontend/src/routes/index.tsx`
- `deliveryline-frontend/src/routes/workflows/index.tsx`
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx`
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx`
- `deliveryline-frontend/src/routes/-states/DeadEndState.tsx`
- `deliveryline-frontend/src/routes/README.md`
- `deliveryline-frontend/src/lib/routing/publicId.js`
- `deliveryline-frontend/src/lib/routing/publicId.d.ts`
- `deliveryline-frontend/src/lib/api/correlation.ts`
- `deliveryline-frontend/tools/routing/generate-route-tree.js`
- `deliveryline-frontend/tools/routing/__tests__/public-id-params.test.js`
- `deliveryline-frontend/src/routeTree.gen.ts` *(generated by the build; gitignored — NOT committed)*

**Moved:**
- `deliveryline-frontend/src/dev/PrimitivesPlayground.tsx` *(from `src/routes/_dev/PrimitivesPlayground.tsx`; header comment updated)*

**Modified:**
- `deliveryline-frontend/package.json` *(deps `@tanstack/react-router` + `@tanstack/router-plugin`; scripts `routes:generate`, `check:routes`; `build` runs generate first)*
- `deliveryline-frontend/package-lock.json` *(regenerated via full `npm install` with module-local npm 10.8.2)*
- `deliveryline-frontend/vite.config.ts` *(`tanstackRouter({ target: 'react' })` before `react()` + AC7 drift-protection comment)*
- `deliveryline-frontend/src/App.tsx` *(`RouterProvider` + typed `Register` + scroll restoration + default fallbacks; relocated playground import; scaffold `console.warn` removed)*
- `deliveryline-frontend/eslint.config.js` *(ignore `src/routeTree.gen.ts`)*
- `deliveryline-frontend/.gitignore` *(ignore `src/routeTree.gen.ts`)*
- `deliveryline-frontend/.prettierignore` *(ignore `src/routeTree.gen.ts`)*
- `deliveryline-frontend/pom.xml` *(`npm-run-check-routes` process-resources execution)*
- `deliveryline-frontend/README.md` *(Routing section + scripts + playground path)*
- `_bmad-output/implementation-artifacts/deferred-work.md` *(story 2.5 deferred AC sub-items)*
- `_bmad-output/implementation-artifacts/sprint-status.yaml` *(status → review)*

## Change Log

| Date       | Change                                                                                  |
| ---------- | --------------------------------------------------------------------------------------- |
| 2026-05-21 | Story 2.5 implemented: TanStack Router file-based routing (typed route tree, validated `run_`/`art_` params, loader seam, all explicit dead-end UI states, AC10 single-detail-route). Added `@tanstack/react-router` + `@tanstack/router-plugin`; `check:routes` `node --test` gate wired into pom.xml; lockfile regenerated + Linux-verified; reactor smoke green. Status → review. |
