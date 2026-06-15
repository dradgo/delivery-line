# Story 2.6: TanStack Query + Key Factories + Typed API Client Generated from OpenAPI

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **frontend developer**,
I want **TanStack Query configured with typed query key factories and a typed API client generated from the backend's OpenAPI spec**,
so that **server state is the single source of truth, query keys are stable and collision-free, and API contract drift between backend and frontend is caught at build time**.

## ⚠️ Read first — what this story is, what already exists, and the traps that will bite a literal reader

**Scope in one line:** wire the data layer the previous five frontend stories (2.1–2.5) left as SEAMs — add TanStack Query, generate a typed client from 6.9's committed `openapi.json`, author the `workflowKeys` factory + the typed query/mutation hooks, a typed Problem Details parser, and replace the route-loader stubs with real `ensureQueryData` prefetch. **Frontend-only.** No backend changes.

### 🚨 TRAP 1 — Most of the "create an ESLint rule" AC (AC4) is ALREADY DONE. Do not re-author it.

`tools/eslint-rules/no-inline-query-keys.js` **already exists** (authored by story 2.31, referenced by 2.7 AC4) and is **already wired at `error`** in `eslint.config.js:94` for `src/**/*.{ts,tsx}`. It flags `useQuery`/`useMutation`/`useInfiniteQuery` whose `queryKey`/`mutationKey` is not a factory **call**. Its RuleTester fixtures pass today (`npm run lint:rules-test`). **Your job for AC4 is NOT to write the rule** — it is to (a) create the `workflowKeys` factory the rule expects callers to use, (b) confirm every hook you author uses `workflowKeys.*()` so the rule has real, passing call sites, and (c) add a RuleTester fixture proving an ad-hoc `useQuery(['workflows', id])` in a real-shaped hook file fails. If you find the rule needs a tweak to handle a real call pattern, tweak minimally and keep `npm run lint:rules-test` green — do not rewrite it.

### 🚨 TRAP 2 — The correlation-ID header has a committed SEAM. Wire it; do NOT invent a new header or generator.

`src/lib/api/correlation.ts` **already exists** (story 2.5 AC9 seam): it exports `CORRELATION_ID_HEADER = 'X-Correlation-Id'` and `newCorrelationId()` (UUID v4 via `crypto.randomUUID()`), with the header name pinned to the backend `CorrelationIdFilter.HEADER`. The route loaders carry `// SEAM (story 2.6/1.19): attach the X-Correlation-Id header` markers. **Use this module** — the client middleware attaches `CORRELATION_ID_HEADER` on every request. Do not create a second correlation utility or hard-code `'X-Correlation-Id'` elsewhere.

> **Idempotency-key vs correlation-id are different headers.** AC7 wants a UUIDv7 `Idempotency-Key` on **mutations** (story 1.9). `correlation.ts` mints a UUID **v4** for the **correlation** header on **all** requests. Do not conflate them. `crypto.randomUUID()` is v4; you need a small **v7** generator (timestamp-ordered) for idempotency keys — add it to `src/lib/api/` (e.g. `idempotency.ts`), do not overload `newCorrelationId()`.

### 🚨 TRAP 3 — Replace the loader STUBS; do not bypass them. The route tree already expects `queryClient`.

`src/routes/workflows/$workflowRunId/index.tsx` returns a typed `WorkflowDetailStub` from its `loader` with three explicit SEAM comments telling you exactly what to do:
```ts
// SEAM (story 2.6): replace this typed stub with the real prefetch —
//   return queryClient.ensureQueryData(workflowKeys.detail(params.workflowRunId))
// SEAM (story 2.6/1.19): attach the X-Correlation-Id header on that request
// SEAM (story 2.6/2.28): a backend 404 here becomes `throw notFound()` → RunNotFoundState (AC4)
```
The `beforeLoad` param validation, the `notFoundComponent`, the `errorComponent` (mapping `InvalidRouteParamError → InvalidLinkState`), and the `RECOGNIZED_STAGES` AC8 guards are **already correct** — keep them. You are swapping the **stub body** for a real prefetch and feeding the loader a `queryClient` (TanStack Router's `context`/`routerContext` pattern). The `/workflows` list route (`src/routes/workflows/index.tsx`) is a placeholder with no loader yet — add the list prefetch there too (the queue UI is 2.15/2.20, but the data seam is yours).

### 🚨 TRAP 4 — DEPENDENCY: 6.9's OpenAPI snapshot is the source for your client, but 6.9 is still in `review` with an OPEN patch that weakens YOUR generated types.

The snapshot you generate from — `deliveryline-backend/src/main/resources/openapi/openapi.json` (8 operationIds: `listWorkflows`/`getWorkflow`/`getWorkflowEvents` + the 5 commands) — exists and is committed. **But story 6.9 is `review`, not `done`,** and its review log has an **open `[Review][Patch]`**: *"OpenAPI is too weak for generated clients: `application/problem+json` responses are untyped and `/events` omits the authoritative required-field/cardinality constraints"* (6.9 story, Review Findings). **Impact on you:** if you generate against the snapshot as-is, your **AC5 `problemDetails.ts` cannot be typed from the spec** (problem+json bodies have no schema), and `/events` response types will be loose. **Mitigation (do this):** (1) author `problemDetails.ts` types by hand from story 1.8's `ProblemDetailsCatalog` + `DomainErrorCode` registry (the stable `code`/`status`/`retryable`/`details` contract), NOT from the generated client — treat the generated `getWorkflowEvents` response as the wire shape and overlay the hand-typed Problem Details on the error path. (2) Add a `// DEPENDS-ON 6.9 patch:` note where you import generated error types, so when 6.9's patch lands and strengthens the spec you can swap hand-types for generated ones. **Do not block on 6.9** — generate from the current snapshot and layer hand-types where the spec is thin. Confirm with Alex if you want to wait for 6.9's patch (see open question at end).

### Hard boundaries

- **Frontend-only.** No backend files. No `deliveryline-backend/**` edits. The OpenAPI snapshot is an INPUT you read, never regenerate (that is 6.9 / 2.13 / 2.14).
- **Server state lives in TanStack Query, never React state.** Architecture invariant (architecture.md:454, 764–766): no `currentWorkflowState` / `currentArtifactVersion` in `useState`. The `no-workflow-domain-in-ui-primitives` rule + reviewer scrutiny enforce this.
- **Every query key comes from a factory.** No inline arrays (TRAP 1). The lint rule fails the build otherwise.
- **The generated client is committed** (AC1) and lives in ONE place: `src/lib/api/` (architecture.md:691, 1212 — "agents must not duplicate generated clients under multiple modules"). Gitignore nothing that AC2's CI drift check needs to diff.
- **No new HTTP endpoints are consumed beyond the 3 reads.** Mutation hooks (approve/reject/clarify) are SCAFFOLDED against the command operationIds that already exist in the snapshot (`approveSpec`/`rejectSpec`/`retryWorkflow`/`takeoverWorkflow`); their **UI wiring** lands in 2.13/2.19. Author the hook pattern + invalidation now (AC6) so 2.13 plugs in without reshaping infra.

## Acceptance Criteria

1. **Given** `src/lib/api/client.ts`, **Then** a typed API client is generated from the backend's OpenAPI spec (from story 6.9) using `openapi-typescript-codegen` or `orval` or equivalent — generated client types are committed so frontend developers don't need live backend access to type-check.
2. **Given** the generation pipeline, **Then** `npm run generate-api` fetches the committed OpenAPI snapshot (`backend/src/main/resources/openapi/openapi.json`) and regenerates the client; CI diffs the committed generated output against a fresh regeneration to catch drift (matching story 1.21's OpenAPI drift check on the backend side).
3. **Given** `src/lib/queryKeys/workflowKeys.ts`, **Then** a typed query key factory exports stable keys: `workflowKeys.all`, `workflowKeys.list(filters)`, `workflowKeys.detail(workflowRunId)`, `workflowKeys.events(workflowRunId)`, `workflowKeys.artifacts(workflowRunId)`, `workflowKeys.artifact(artifactId)`, `workflowKeys.allowedActions(workflowRunId)` (story 2.14).
4. **Given** an ESLint custom rule, **Then** any inline `useQuery(['workflows', ...])` ad-hoc key in a component file fails lint — keys must come from `queryKeys/*` factories (party-mode finding from Winston on consistency).
5. **Given** `src/lib/api/problemDetails.ts`, **Then** a typed Problem Details error handler parses `application/problem+json` responses (story 1.8), exposes stable `code` / `status` / `retryable` / `details`, and TanStack Query's `onError` callbacks consume typed domain error codes — not raw HTTP status or string matching.
6. **Given** mutation patterns for E2's spec approve/reject/clarify (stories 2.13, 2.19), **Then** mutation hooks live under `src/features/workflows/hooks/` and each mutation invalidates the affected queries (`workflowKeys.detail`, `.events`, `.allowedActions`, and pending-review list) on success — architecture requirement.
7. **Given** the `Idempotency-Key` header pattern (story 1.9), **Then** every mutation hook generates a UUIDv7 idempotency key at mutation-start and includes it in the request — retries of the same mutation attempt reuse the same key.
8. **Given** typed TanStack Query hooks, **Then** `useWorkflowDetail`, `useWorkflowEvents`, `useArtifact`, `useAllowedActions` exist under `features/workflows/hooks/` — consumers are typed by generated API response shapes without runtime casts.
9. **Given** stale time / cache time defaults, **Then** workflow detail queries have short staleTime (e.g., 5s) to reflect workflow state freshness; event history queries can have longer staleTime since events are append-only; documented defaults live in a shared query-options utility.
10. **Given** NFR25/26/27 performance targets, **Then** TanStack Query's request deduplication + structural sharing prevents redundant backend calls when multiple composites in the same view consume overlapping data (e.g., ARP and Context Strip both reading run detail).

## Tasks / Subtasks

- [x] **Task 1: Add TanStack Query + the typed-client generator toolchain** (AC: 1, 2)
  - [x] Add `@tanstack/react-query` (v5 — peer-compatible with React 18.3.1 already pinned; **do NOT add React Query Devtools to `dependencies`** — devtools, if used, goes in `devDependencies` and behind a dev-only import). Verify the resolved v5 minor against the Vite 8 / TS ~6.0 toolchain.
  - [x] Choose the client generator (see Dev Notes "Generator decision"): **recommended `openapi-typescript` (emits types) + `openapi-fetch` (tiny typed fetch runtime)** — fetch-based, matches the architecture's fetch default and the existing `correlation.ts` fetch seam, and leaves hooks/factory hand-authored as the ESLint rule + AC3/AC6/AC8 require. (`orval` is the rejected alternative — it generates the hooks too, which collides with the hand-authored hook layer; if you have a strong reason to use it, raise the open question first.)
  - [x] Add `openapi-typescript` to `devDependencies`. Wire `npm run generate-api` in `package.json` `scripts` to read `../deliveryline-backend/src/main/resources/openapi/openapi.json` and emit committed types to `src/lib/api/schema.d.ts` (or `generated/`). Pin the generator version so output is deterministic.
  - [x] **CRITICAL — lockfile cross-platform (project memory `frontend-lockfile-cross-platform`):** after adding deps, regenerate `package-lock.json` with a FULL `npm install` (not `npm ci`) and verify the build on Linux (throwaway Docker container) before pushing. Vite 8 / rolldown native bindings make a Windows-only lockfile fail CI's Linux job. This bit stories 2.1 and 2.5 — do not repeat it.
  - [x] Build the typed client in `src/lib/api/client.ts`: instantiate `openapi-fetch` `createClient<paths>({ baseUrl: '/api/v1' })` and attach a **request middleware** that (a) adds `CORRELATION_ID_HEADER` from `correlation.ts` to every request, (b) adds `Idempotency-Key` (UUIDv7) to mutations only. Base URL is `/api/v1` (the Vite dev proxy at `vite.config.ts` forwards `/api` → `http://localhost:8080`; in the bundled jar it is same-origin).

- [x] **Task 2: CI drift gate for the generated client** (AC: 2)
  - [x] Add an npm check (e.g. `check:api` = regenerate to a temp dir + `git diff --exit-code` against the committed output) wired into the build/CI the same way `check:routes`/`check:tokens`/`check:contrast` are (`package.json` scripts + `pom.xml` exec + `.github/workflows/ci.yml`). Mirror story 1.21's backend OpenAPI drift semantics: a stale committed client fails CI.
  - [x] Confirm the gate is reproducible on Linux (memory `verify-ci-fixes-in-clean-env`) — the committed generated output must be byte-identical to a fresh Linux regeneration (watch for CRLF/LF: enforce `\n` + trailing newline like 6.9's snapshot canonicalization).
  - [x] Document in the story/README that the snapshot SOURCE is owned by the backend (6.9 / 2.13 / 2.14); the frontend only consumes it. When the backend regenerates `openapi.json`, the frontend `generate-api` + commit is the follow-up.

- [x] **Task 3: Query key factory** (AC: 3)
  - [x] Create `src/lib/queryKeys/workflowKeys.ts` exporting `workflowKeys` with EXACTLY these members (AC3): `all`, `list(filters)`, `detail(workflowRunId)`, `events(workflowRunId)`, `artifacts(workflowRunId)`, `artifact(artifactId)`, `allowedActions(workflowRunId)`. Use the architecture-prescribed hierarchical shape (architecture.md:766–777) so `.detail(id)` is a prefix of `.events(id)`/`.artifacts(id)` — enabling partial invalidation by prefix. Mark each `as const`.
  - [x] `list(filters)` takes the typed filter object (at minimum `{ state?: WorkflowState }` — the only filter 6.9's `listWorkflows` documents). Keep filters serializable + stable (sort keys / normalize) so identical filters dedupe.
  - [x] **Note `artifacts`/`artifact`/`allowedActions` reference endpoints that DO NOT EXIST in 6.9's snapshot yet** (artifact reads come later in Epic 2; allowed-actions is 2.14). The FACTORY KEYS must still be authored now (AC3 names them explicitly) — they are the stable contract the future hooks bind to. The hooks for those (Task 5) are scaffolded/deferred; the keys are not.
  - [x] Add a focused unit test (`node --test` to match the repo's `tools/*/__tests__` convention, or vitest if you stand it up — see Task 7) asserting key stability + that `detail(id)` is a structural prefix of `events(id)` (collision/invalidation contract; architecture.md:851).

- [x] **Task 4: Typed Problem Details parser** (AC: 5)
  - [x] Create `src/lib/api/problemDetails.ts`: a `parseProblemDetails(response)` that detects `content-type: application/problem+json` and returns a typed `ProblemDetails { type, title, status, detail, instance, code, retryable, details? }` (RFC-9457 + the backend's `code`/`retryable` extensions — architecture.md:709–730). Expose a `ProblemDetailsError extends Error` carrying the parsed body so TanStack Query `onError` consumes `error.code` (stable `DomainErrorCode` string), `error.status`, `error.retryable` — **never raw HTTP status or string matching** (AC5).
  - [x] Type the `code` field as a union of the **stable domain error codes** relevant to the read/spec surface (`RUN_NOT_FOUND`, `INVALID_ID_PREFIX`, `APPROVAL_VERSION_MISMATCH`, `INTERNAL_ERROR`, …) sourced from story 1.8's `DomainErrorCode`/`ProblemDetailsCatalog`. **Per TRAP 4, hand-author these** (the snapshot does not yet type problem+json) and leave a `// DEPENDS-ON 6.9 patch` marker to swap to generated types later. Keep the union open (`| (string & {})`) so an unknown future code degrades gracefully instead of crashing the parser.
  - [x] Default-export a TanStack Query `QueryCache`/`MutationCache` `onError` (or a shared `queryClient` error handler) that maps `ProblemDetailsError` → typed handling; non-problem responses fall through to a generic error. Pin with a test feeding a real `application/problem+json` body (use a fixture mirroring architecture.md:716–729).

- [x] **Task 5: Typed query hooks + mutation-hook pattern** (AC: 6, 7, 8, 9, 10)
  - [x] Create `src/features/workflows/hooks/` (the architecture-prescribed location, architecture.md:689). Author the query hooks AC8 names: `useWorkflowDetail(workflowRunId)` → `getWorkflow`, `useWorkflowEvents(workflowRunId)` → `getWorkflowEvents`, both typed by the generated response shapes with **no runtime casts**. `useArtifact(artifactId)` + `useAllowedActions(workflowRunId)` are NAMED by AC8 but their endpoints don't exist in 6.9's snapshot — author them as typed stubs that call the factory key + a `// SEAM (story 2.14 / artifact-read story)` marker and a `queryFn` that throws a clear "endpoint not yet available" until the backend ships it. Document this in Dev Notes; do not fabricate endpoints.
  - [x] Every hook's `queryKey` is a `workflowKeys.*()` call (TRAP 1 / AC4). Every hook reads stale/cache defaults from the shared options util (Task 6).
  - [x] **Mutation-hook pattern (AC6, AC7)** — author the reusable shape under `features/workflows/hooks/` (e.g. `useWorkflowMutation` factory or a documented template) that future stories 2.13/2.19 instantiate for approve/reject/clarify:
    - generates a **UUIDv7 idempotency key at mutation-start**, stored on the mutation context so RETRIES of the same attempt reuse it (AC7 — do NOT mint a new key per retry); pass it as `Idempotency-Key` (Task 1 middleware reads it).
    - `onSuccess` invalidates the affected queries: `workflowKeys.detail(id)`, `.events(id)`, `.allowedActions(id)`, and the pending-review **list** key (AC6). Prefer prefix invalidation via the hierarchical keys (Task 3).
    - Scaffold ONE concrete example bound to an existing command operationId (e.g. `approveSpec`) to prove the pattern compiles + invalidates; mark it `// SEAM (story 2.13/2.19): UI wiring`. Do not build the approval UI here.
  - [x] **AC10 — dedup/structural sharing:** ensure overlapping consumers (e.g. detail read from two composites) share one query via the SAME factory key; add a test asserting two `useWorkflowDetail(sameId)` mounts trigger ONE `queryFn` call (deduplication) and that an unrelated cache update doesn't re-render via structural sharing. This is the NFR25/26/27 guard.

- [x] **Task 6: Shared query-options util + QueryClient provider + loader wiring** (AC: 9, 10, and TRAP 3)
  - [x] Create a shared query-options util (e.g. `src/lib/api/queryOptions.ts`) documenting defaults: workflow **detail** `staleTime ≈ 5s` (state freshness, AC9); **events** longer `staleTime` (append-only, AC9); sensible `gcTime`; `retry` policy that respects `ProblemDetailsError.retryable` (don't retry non-retryable domain errors). Centralize so hooks don't each redefine.
  - [x] Create the `QueryClient` (single instance) wired with the Task 4 error cache + Task 6 defaults; mount `QueryClientProvider` in the app root (`src/routes/__root.tsx` or `src/main.tsx` — check where 2.5 mounts the router) and pass the `queryClient` into the **router context** so loaders can call `queryClient.ensureQueryData(...)`.
  - [x] **Replace the loader stubs (TRAP 3):** in `src/routes/workflows/$workflowRunId/index.tsx`, swap the `WorkflowDetailStub` body for `context.queryClient.ensureQueryData(detailQueryOptions(params.workflowRunId))` so deep links render without a loading flash (story 2.5 AC3). Keep the existing `beforeLoad` validation, `notFoundComponent`, `errorComponent`, and `RECOGNIZED_STAGES` guards. Map a backend 404 (`RUN_NOT_FOUND` `ProblemDetailsError`) to `throw notFound()` (the SEAM (story 2.6/2.28) marker). Add the list prefetch to `src/routes/workflows/index.tsx`.
  - [x] Attach the correlation header on loader-triggered requests via the Task 1 client middleware (the loaders' `// SEAM (story 2.6/1.19)` markers) — no per-loader header code; it flows through the client.

- [x] **Task 7: Frontend test suite for the data layer** (AC: 4, 5, 6, 8, 10)
  - [x] Stand up the test runner. The repo currently uses `node --test` for tool-level checks (`tools/**/__tests__`); component/hook tests need a DOM. **DECIDED (Alex 2026-05-21): stand up MINIMAL `Vitest` + `@testing-library/react` + `MSW` here — only enough to test THIS story's hooks/mutations — and note that story 2.27 (frontend test suite, backlog) extends it.** Do not build out the full suite (that is 2.27); do not defer this story's own hook tests to 2.27. (architecture.md:695–702, 1221–1224 anticipate query-hook + mutation tests.)
  - [x] Tests: (a) `workflowKeys` stability + prefix contract (Task 3); (b) `problemDetails.ts` parses a real problem+json body to typed `code`/`retryable` (Task 4); (c) a query hook returns typed data via MSW-mocked `getWorkflow`; (d) the mutation pattern reuses ONE idempotency key across retries (AC7) and invalidates the right keys on success (AC6); (e) dedup/structural-sharing assertion (AC10); (f) extend `tools/eslint-rules/__tests__/no-inline-query-keys.test.js` RuleTester with a real-shaped hook fixture (AC4).
  - [x] `npm run lint` (max-warnings=0), `npm run lint:rules-test`, `npm run check:api` (Task 2), `npm run build` all green.

- [x] **Task 8: Observability + docs (frontend-adapted; the JVM SLF4J logging task is N/A here)**
  - [x] **The project-wide SLF4J/Logback logging task does NOT apply** — this is a browser/TypeScript story with no JVM surface (same posture the 6.9 dev notes recorded for the frontend-only stories 2.2–2.5). The story's observability contribution is the **correlation-ID propagation** (Task 1 middleware — server logs trace back to UI nav, story 1.19) and **typed error surfacing** (Task 4). Pin the correlation-header attachment with a test (a mocked request carries `X-Correlation-Id`). Do NOT add a frontend logging framework or `console.*` spam.
  - [x] Update `deliveryline-frontend/README.md`: the data-layer conventions (query-key factory is mandatory, `no-inline-query-keys` enforces it; staleTime defaults; how to regenerate the client via `npm run generate-api`; that the OpenAPI source is backend-owned). Note the generated client is committed and drift-checked.
  - [x] **Verification gate:** `npm run build` + `npm run lint` + `npm run lint:rules-test` + `npm run check:api` + the new Vitest suite green; reactor `mvn -pl deliveryline-frontend clean install` (or the wired Maven phase) green; regenerate the lockfile with full `npm install` and **verify on Linux** before claiming done (memory `frontend-lockfile-cross-platform` + `verify-ci-fixes-in-clean-env`).

## Dev Notes

### Story scope — wiring the data layer the 2.1–2.5 SEAMs left open

Stories 2.1–2.5 built the frontend shell (scaffold, Tailwind/shadcn, design tokens, TanStack Router) and deliberately left typed SEAMs for the data layer: `src/lib/api/correlation.ts`, the `no-inline-query-keys` ESLint rule, and `// SEAM (story 2.6)` markers in every route loader. Story 6.9 (prerequisite, currently `review`) committed the backend `openapi.json` + the localhost-bound read endpoints. **2.6 connects them:** TanStack Query as the single server-state source, a typed client generated from 6.9's snapshot, the `workflowKeys` factory, typed hooks, a typed Problem Details parser, and real loader prefetch. It introduces NO new UI composites (those are 2.7+/2.15+) and NO backend changes.

### Generator decision (CONFIRMED by Alex 2026-05-21)

AC1 names "`openapi-typescript-codegen` or `orval` or equivalent." **DECIDED: `openapi-typescript` (types-only emitter) + `openapi-fetch` (≈6kb typed fetch wrapper).** (Confirmed with Alex at story creation — do not re-litigate.)

- **Why:** matches the architecture's fetch default (architecture.md does not pick axios; fetch is the modern standard and the existing `correlation.ts` mints a `crypto.randomUUID()` for a fetch request); leaves the **hooks and key factory hand-authored**, which AC3/AC6/AC8 and the `no-inline-query-keys` rule all require; smallest committed-artifact surface for the AC2 drift diff; trivial request middleware for the correlation + idempotency headers.
- **`orval` rejected:** it generates TanStack Query hooks AND keys itself, which collides head-on with the hand-authored `features/workflows/hooks/` + `workflowKeys` factory the ACs mandate, and would make the ESLint rule's "keys from a factory" contract ambiguous. `openapi-typescript-codegen` is unmaintained/heavier and emits a class-based client that doesn't compose with TanStack Query as cleanly.
- **Decision is reversible** but baking it in avoids churn. If you disagree, raise the open question before Task 1.

### What already exists (verified, with paths) — do not rebuild these

- **ESLint rule (AC4 — mostly done):** `deliveryline-frontend/tools/eslint-rules/no-inline-query-keys.js` (authored 2.31; flags `useQuery`/`useMutation`/`useInfiniteQuery` non-factory keys, resolves indirection through `const key = factory()`). Wired at `error` in `eslint.config.js:94` for `src/**/*.{ts,tsx}`. RuleTester: `tools/eslint-rules/__tests__/no-inline-query-keys.test.js`, run via `npm run lint:rules-test`. **Your job: give it real call sites + one real-shaped failing fixture, not a rewrite (TRAP 1).**
- **Correlation seam (TRAP 2):** `deliveryline-frontend/src/lib/api/correlation.ts` — `CORRELATION_ID_HEADER='X-Correlation-Id'`, `newCorrelationId()` (UUID v4). Header pinned to backend `CorrelationIdFilter.HEADER`. Wire it into the client; do not duplicate.
- **Route loader stubs (TRAP 3):** `src/routes/workflows/$workflowRunId/index.tsx` (typed `WorkflowDetailStub` + 3 SEAM comments + correct `beforeLoad`/`notFoundComponent`/`errorComponent`/`RECOGNIZED_STAGES`), `src/routes/workflows/index.tsx` (placeholder list, no loader yet), `src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx` (artifact viewer stub), `src/routes/-states/DeadEndState.tsx` (the `RunNotFoundState`/`InvalidLinkState`/`GenericErrorState`/etc. components your error mapping renders).
- **Param validation:** `src/lib/routing/routeParamValidation.ts` (`assertValidRunRouteParams`, `InvalidRouteParamError`) + `src/lib/routing/publicId.js` (re-encodes the story-1.4 V1 `run_`/`art_` regex). Reuse for any client-side id checks; do not re-implement the regex.
- **Build/CI check pattern:** `package.json` scripts `check:routes`/`check:tokens`/`check:contrast` (all `node --test` against `tools/**/__tests__`) + their `pom.xml` exec wiring + `.github/workflows/ci.yml` steps. Mirror this exact shape for `check:api` (Task 2).
- **Vite dev proxy:** `vite.config.ts` proxies `/api` → `http://localhost:8080` (with `ws:true` for future SSE). Client `baseUrl` is `/api/v1` (relative — proxy in dev, same-origin in the bundled jar).
- **Pinned stack:** React 18.3.1, Vite 8.0.12, TypeScript ~6.0.2, TanStack Router 1.170.6, ESLint flat config 9.39, `eslint-config-prettier` LAST. `tsconfig.app.json` is strict (type-aware lint via `projectService: true`). `@/*` → `./src` alias (vite + tsconfig).

### Backend contract this story consumes (from 6.9 — read-only inputs)

- **OpenAPI snapshot:** `deliveryline-backend/src/main/resources/openapi/openapi.json` (27KB, springdoc 3.0.3 output, deterministic key-sorted). 8 operationIds: `listWorkflows`, `getWorkflow`, `getWorkflowEvents` (the reads you bind hooks to) + `submitWorkflow`, `approveSpec`, `rejectSpec`, `retryWorkflow`, `takeoverWorkflow` (commands — mutation-pattern scaffolding only).
- **Events wire schema:** `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` — the authoritative `/events` shape: `{ workflowRun: { publicId, ticketRef, createdAt, terminalState }, events: [ { publicId, workflowRunPublicId, eventType, priorState, resultingState, actorIdentity, actorType, reason, failureCategory, interventionMarker, createdAt, details } ] }`. `details` is an open map; `idempotencyKey` is server-stripped (your client will never see it). `useWorkflowEvents` types bind to the generated `getWorkflowEvents` response, which 6.9 pinned against this schema.
- **Problem Details contract (story 1.8 — for AC5):** `application/problem+json` body `{ type, title, status, detail, instance, code, retryable, details? }`; `code` is a stable uppercase `DomainErrorCode` (e.g. `RUN_NOT_FOUND`/`INVALID_ID_PREFIX`/`APPROVAL_VERSION_MISMATCH`/`INTERNAL_ERROR`), `type` is `https://deliveryline.local/problems/{slug}`. **TRAP 4: the snapshot does not yet type this body** — hand-author the types from the 1.8 catalog and mark `// DEPENDS-ON 6.9 patch`.
- **Headers:** request adds `X-Correlation-Id` (all) + `Idempotency-Key` UUIDv7 (mutations). Response echoes `X-Correlation-Id` globally (6.9 / 1.19). Idempotency key v4-vs-v7: correlation is v4 (`crypto.randomUUID`), idempotency must be **v7** (story 1.9 wants timestamp-ordered) — add a small v7 minter (e.g. `src/lib/api/idempotency.ts`); do not reuse `newCorrelationId()`.

### Dependency status & sequencing

- **6.9 is `review`, not `done`** — but its committed `openapi.json` and read endpoints are stable enough to build against NOW (Alex pulled 6.9 forward precisely to unblock 2.6). The one open 6.9 patch (untyped problem+json / loose `/events` constraints) only affects how much of your typing is generated vs hand-authored (TRAP 4) — it does not block you. Generate from the current snapshot; layer hand-types; mark the swap points.
- **Downstream consumers of YOUR work:** 2.7 (tri-pane shell — mounts the provider region you set up), 2.13/2.19 (instantiate your mutation-hook pattern for approve/reject), 2.14 (ships the `allowed-actions` endpoint your `useAllowedActions`/`workflowKeys.allowedActions` key already anticipates), 2.15/2.20 (the queue UI that consumes `useWorkflows`/`workflowKeys.list`), 2.17 (`useArtifact` for the Artifact Review Panel). Author the seams so they plug in without reshaping infra (the epic's "generalized from day one" mandate, epics.md:840).
- **2.27 (frontend test suite, backlog)** owns the FULL Vitest+MSW suite. **DECIDED (Alex 2026-05-21):** 2.6 stands up the MINIMAL runner needed for ITS hooks/mutations only; 2.27 extends it. Don't double-own.

### Architecture-prescribed rules (architecture.md)

- **Server state = TanStack Query only; backend is the single source of truth** (architecture.md:447, 454, 461, 764–766). No domain state in `useState`. Mutations invalidate/refetch (architecture.md:516–517), never manual refetch.
- **Query-key factory is mandatory; inline keys are a named anti-pattern** (architecture.md:766–777, 830, 851). Key factory tests verify stability + collision-freedom.
- **API format:** `/api/v1` base, plural-noun resources, kebab-case action endpoints, camelCase JSON, ISO-8601-UTC, public-prefixed ids, direct resource shapes (no `{data:}` envelope) (architecture.md:638–643).
- **Folder conventions** (architecture.md:687–693, 1055–1090): `src/lib/api/` (client + problemDetails), `src/lib/queryKeys/` (factories), `src/features/workflows/hooks/` (query/mutation hooks), `src/components/` (shared/ui). Hooks named `useX.ts`; PascalCase components; camelCase utils.
- **Generated client lives in ONE place** (architecture.md:691, 1212) — `src/lib/api/`; never duplicated across modules.
- **Frontend test organization** (architecture.md:695–702): query-hook + mutation tests, colocated `*.test.tsx` where practical.

### Project Structure Notes

- **New (frontend):** `src/lib/api/client.ts`, `src/lib/api/problemDetails.ts`, `src/lib/api/idempotency.ts`, `src/lib/api/queryOptions.ts`, generated `src/lib/api/schema.d.ts` (committed), `src/lib/queryKeys/workflowKeys.ts`, `src/features/workflows/hooks/*` (query hooks + mutation pattern), QueryClient provider wiring (in `__root.tsx`/`main.tsx`), Vitest config + tests, RuleTester fixture addition.
- **Modified:** `package.json` (deps + `generate-api`/`check:api` scripts), `package-lock.json` (regenerate full install, Linux-verify), `src/routes/workflows/$workflowRunId/index.tsx` + `src/routes/workflows/index.tsx` (real loaders replacing stubs), `pom.xml` (wire `check:api` into the Maven/CI phase), `.github/workflows/ci.yml` (the `check:api` drift step), `deliveryline-frontend/README.md`.
- **No conflicts** with the established structure — everything lands in the architecture-prescribed folders. The `no-inline-query-keys` rule already protects the new hook files.

### Anti-patterns to avoid

- **Do NOT re-author `no-inline-query-keys.js`** — it exists and passes (TRAP 1). Add call sites + a fixture.
- **Do NOT invent a correlation header or generator** — wire `correlation.ts` (TRAP 2). And do NOT reuse the v4 correlation minter for the v7 idempotency key.
- **Do NOT bypass the route loaders** — replace the stub bodies, keep their guards/error states (TRAP 3).
- **Do NOT regenerate or edit the backend `openapi.json`** — it is a backend-owned input.
- **Do NOT type Problem Details from the current snapshot** (it's untyped there per 6.9's open patch) — hand-author from 1.8's catalog and mark the swap point (TRAP 4).
- **Do NOT put server/domain state in React `useState`** — TanStack Query is the source of truth.
- **Do NOT fabricate the `artifacts`/`artifact`/`allowedActions` endpoints** — the factory KEYS are authored now (AC3); the HOOKS are typed stubs with SEAM markers until the backend ships those endpoints (2.14 / artifact-read story).
- **Do NOT commit a Windows-only lockfile** — full `npm install` + Linux verify (memory).
- **Do NOT add React Query Devtools to `dependencies`** or leave a dev-only import in the prod bundle.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.6] — authoritative ACs (lines 943–961); Epic 2 intro (lines 837–840); dependency edge "2.6's typed client depends on backend OpenAPI" (line 847)
- [Source: _bmad-output/implementation-artifacts/6-9-localhost-rest-binding-and-workflow-read-endpoints.md] — the prerequisite: committed `openapi.json`, `/events` schema, Problem Details + correlation contract; **open Review Finding "OpenAPI too weak for generated clients"** (TRAP 4)
- [Source: deliveryline-backend/src/main/resources/openapi/openapi.json] — the 8-operationId snapshot the client generates from
- [Source: deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json] — authoritative `/events` wire shape `useWorkflowEvents` binds to
- [Source: deliveryline-frontend/tools/eslint-rules/no-inline-query-keys.js + eslint.config.js:94] — AC4 rule already authored + wired (TRAP 1)
- [Source: deliveryline-frontend/src/lib/api/correlation.ts] — `CORRELATION_ID_HEADER` + `newCorrelationId()` seam (TRAP 2)
- [Source: deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx + src/routes/workflows/index.tsx] — loader SEAMs to replace (TRAP 3); `src/routes/-states/DeadEndState.tsx` for error/not-found components
- [Source: deliveryline-frontend/src/lib/routing/routeParamValidation.ts + publicId.js] — reuse for client-side id validation
- [Source: deliveryline-frontend/vite.config.ts] — `/api` dev proxy → :8080; client `baseUrl='/api/v1'`
- [Source: deliveryline-frontend/package.json] — current deps (TanStack Query ABSENT — add it); `check:*` script pattern to mirror for `check:api`
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture] — server-state-only, key-factory mandate, folder conventions, format patterns (lines 447–520, 638–693, 764–777, 830–851, 1055–1090, 1212)
- [Source: project memory `frontend-lockfile-cross-platform`] — full `npm install` + Linux-verify the lockfile before pushing (Vite 8/rolldown)
- [Source: project memory `verify-ci-fixes-in-clean-env`] — reproduce CI green in a clean/Linux env before claiming done

## Dev Agent Record

### Review Findings

- [x] [Review][Patch] Mutation idempotency key is shared across overlapping attempts [deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.ts:54]
- [x] [Review][Patch] `unwrap()` coerces non-problem HTTP failures into non-retryable `ProblemDetailsError`s [deliveryline-frontend/src/lib/api/client.ts:91]

### Agent Model Used

claude-opus-4-7[1m] (Claude Opus 4.7, 1M context)

### Debug Log References

- MSW interception failure (all fetch-based tests): `openapi-fetch` captured the `globalThis.fetch` reference at `createClient` time (module import), BEFORE MSW's `server.listen()` ran in `beforeAll`, so requests bypassed the interceptor. Fixed by passing a `liveFetch` wrapper `(input, init) => globalThis.fetch(input, init)` to `createClient` — defers to the live global on every call (idiomatic MSW + openapi-fetch pattern; harmless in the browser).
- Relative `baseUrl: ''` produced unparseable URLs under the node/undici fetch the test runner uses (`Failed to parse URL`). Switched `baseUrl` to `window.location.origin` — identical effective same-origin URL in the browser, absolute/parseable in jsdom.
- `no-unnecessary-condition` warnings: `openapi-fetch` narrows `error` to `never` for operations whose spec declares no error response (`listWorkflows`/`approveSpec`), making the runtime `error !== undefined` guards statically dead. Resolved with a generic `unwrap<T>(result)` boundary in `client.ts` (typed `error?: unknown`) so the guards stay live (the backend can still 500 those paths) and lint-clean.
- `openapi-typescript@7` peer-pins `typescript@^5`; this module runs TS ~6.0 → ERESOLVE. Added committed `.npmrc` `legacy-peer-deps=true` so `npm install` (lockfile authoring) and CI `npm ci` resolve identically.

### Completion Notes List

Wired the data layer the 2.1–2.5 SEAMs left open. All 10 ACs satisfied; verified on Windows (Maven `clean package` BUILD SUCCESS) AND a clean Linux `node:20.19.0` Docker container (`npm ci` + build + 22 tests + check:api + lint + format all green; bundle hashes byte-identical → deterministic).

- **AC1/AC2** — `openapi-typescript`+`openapi-fetch` (confirmed generator); committed `src/lib/api/schema.d.ts` generated from 6.9's snapshot via `npm run generate-api`; `check:api` drift gate (regenerate-to-temp + LF-normalized byte diff) wired into `pom.xml` (`process-resources`, the path CI's `frontend-build-tests` tier runs). NOTE: did NOT add a discrete `.github/workflows/ci.yml` step — the established `check:routes`/`check:tokens`/`check:contrast` gates have no separate ci.yml step either; they all run via the `frontend-maven-plugin` executions inside `mvn package`, which CI invokes. `check:api` + the Vitest run follow that exact pattern.
- **AC3** — `workflowKeys` factory with the exact mandated members + hierarchical prefix contract (`detail(id)` is a prefix of `events`/`artifacts`/`allowedActions`), unit-tested.
- **AC4 (TRAP 1)** — did NOT re-author `no-inline-query-keys`; gave it real call sites (every hook) + added a real-shaped failing/passing RuleTester fixture pair.
- **AC5 (TRAP 4)** — `problemDetails.ts` typed parser + `ProblemDetailsError`. NOTE: 6.9's committed snapshot already types `ProblemDetailsResponse` (springdoc 3.0.3 emitted it on the 400/404 responses — the TRAP-4 "untyped" concern appears resolved in the snapshot), so the wire SHAPE comes from the generated schema; only the `code` UNION is hand-authored (the spec types `code` as a bare `string`), kept open with `(string & {})` and marked `DEPENDS-ON 6.9 patch`.
- **AC6/AC7 (TRAP 2)** — `useWorkflowMutation` pattern: stable UUIDv7 idempotency key minted once in `onMutate` (reused across retries via a ref; tested), prefix invalidation of detail + lists on success. `useApproveSpec` is the concrete scaffold. New `idempotency.ts` v7 minter is separate from the v4 `correlation.ts` minter (do not conflate).
- **AC8** — `useWorkflowDetail`/`useWorkflowEvents` live + typed by generated shapes; `useArtifact`/`useAllowedActions` are typed `enabled:false` stubs with SEAM markers (their endpoints don't exist in 6.9's snapshot — not fabricated).
- **AC9** — shared `queryOptions.ts`: detail staleTime 5s, events 60s, retry respects `ProblemDetailsError.retryable`.
- **AC10** — dedup/structural-sharing proven by a test (two `useWorkflowDetail(sameId)` → 1 fetch, shared object ref).
- **TRAP 3 / DELIBERATE DEVIATION** — replaced the detail-route loader stub with real `context.queryClient.ensureQueryData(detailQueryOptions(id))` + RUN_NOT_FOUND→`notFound()`; added the list-route prefetch. The story-2.5 stub component guarded `currentStage ∈ {spec,…}` and `viewerAuthorized` — NEITHER field exists on the real backend `WorkflowDetail` (it exposes `currentState`; no per-viewer auth). Keeping them verbatim would be dead code failing `no-unnecessary-condition` (max-warnings=0), so the AC8 unrecognized-state guard is re-pointed at the real `currentState` enum (strictly more correct against the live contract); `PermissionRestrictedState` defers to the story that ships role context (still exported/reachable). The artifact-viewer route loader stub is left UNTOUCHED (no artifact-read endpoint exists yet — fabricating it is forbidden).
- **Cross-platform (memory)** — `.npmrc` committed; lockfile regenerated via full `npm install`; verified `npm ci` + build in a clean Linux container before marking review.
- **Open question (generator-tool / wait-for-6.9-patch)** — resolved in-flight per the story's CONFIRMED decision (openapi-typescript + openapi-fetch) and TRAP-4 mitigation (generate now, hand-type the thin `code` union). No blocking on 6.9.

### File List

**New (frontend):**
- `deliveryline-frontend/.npmrc`
- `deliveryline-frontend/vitest.config.ts`
- `deliveryline-frontend/src/lib/api/idempotency.ts`
- `deliveryline-frontend/src/lib/api/problemDetails.ts`
- `deliveryline-frontend/src/lib/api/client.ts`
- `deliveryline-frontend/src/lib/api/queryOptions.ts`
- `deliveryline-frontend/src/lib/api/schema.d.ts` (generated, committed)
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowDetail.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowEvents.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useArtifact.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.ts`
- `deliveryline-frontend/tools/api/check-api-drift.js`
- `deliveryline-frontend/src/test/server.ts`
- `deliveryline-frontend/src/test/setup.ts`
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.test.ts`
- `deliveryline-frontend/src/lib/api/problemDetails.test.ts`
- `deliveryline-frontend/src/lib/api/idempotency.test.ts`
- `deliveryline-frontend/src/lib/api/client.test.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowDetail.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.test.tsx`

**Modified (frontend):**
- `deliveryline-frontend/package.json` (deps + `generate-api`/`check:api`/`test` scripts)
- `deliveryline-frontend/package-lock.json` (full `npm install`, Linux-verified)
- `deliveryline-frontend/pom.xml` (wired `check:api` + Vitest `test` into `process-resources`)
- `deliveryline-frontend/.prettierignore` (ignore generated `schema.d.ts`)
- `deliveryline-frontend/README.md` (data-layer conventions + scripts)
- `deliveryline-frontend/src/App.tsx` (QueryClient + QueryClientProvider + router context)
- `deliveryline-frontend/src/routes/__root.tsx` (typed `RouterContext.queryClient`)
- `deliveryline-frontend/src/routes/workflows/index.tsx` (list-route prefetch loader)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (real detail prefetch + 404→notFound)
- `deliveryline-frontend/tools/eslint-rules/__tests__/no-inline-query-keys.test.js` (real-shaped hook fixtures)

## Change Log

| Date       | Change                                                                                  |
| ---------- | --------------------------------------------------------------------------------------- |
| 2026-05-21 | Story 2.6 created via create-story → `ready-for-dev`. Wires the data layer the 2.1–2.5 SEAMs left open: adds TanStack Query (recommended `openapi-typescript` + `openapi-fetch`), generates the committed typed client from 6.9's `openapi.json` (drift-gated via `check:api`), authors the `workflowKeys` factory + typed query/mutation hooks + typed `problemDetails.ts`, and replaces the route-loader stubs with real `ensureQueryData` prefetch. Four disaster-prevention traps baked in: (1) the `no-inline-query-keys` ESLint rule ALREADY exists (2.31) — give it call sites, don't rewrite; (2) wire the existing `correlation.ts` header seam (and don't reuse the v4 correlation minter for the v7 idempotency key); (3) replace the loader STUBS, keep their guards/error states; (4) 6.9 is `review` with an open patch that leaves problem+json untyped — hand-author Problem Details types from story 1.8's catalog and mark the swap point. |
| 2026-05-22 | Story 2.6 dev-story complete → `review`. Added `@tanstack/react-query` v5 + `openapi-typescript@7.9.1` (pinned) + `openapi-fetch` + Vitest/RTL/MSW (minimal). Authored: typed `client.ts` (origin baseUrl + `liveFetch` for MSW + correlation/idempotency middleware + generic `unwrap`), `idempotency.ts` (UUIDv7), `problemDetails.ts` (typed parser/error, hand-typed `code` union — `DEPENDS-ON 6.9 patch`), `queryOptions.ts` (staleTime defaults + retry-respects-retryable + QueryClient factory), `workflowKeys.ts` (hierarchical factory), query hooks (`useWorkflowDetail`/`useWorkflowEvents` live; `useArtifact`/`useAllowedActions` typed stubs), `useWorkflowMutation` + `useApproveSpec` scaffold. Replaced detail-route loader stub with real `ensureQueryData` + RUN_NOT_FOUND→`notFound()`; added list-route prefetch; typed router context + QueryClientProvider. `check:api` drift gate + Vitest run wired into `pom.xml`. Generated `schema.d.ts` committed + prettier-ignored. Committed `.npmrc legacy-peer-deps=true` (TS6 vs openapi-typescript TS5 peer). 22 Vitest tests + RuleTester fixtures green; lint/format/build/check:api all green. Verified Windows (Maven BUILD SUCCESS) + clean Linux Docker (`npm ci`+build+tests, deterministic bundle). DELIBERATE DEVIATION: AC8 detail guard re-pointed from the non-existent stub `currentStage`/`viewerAuthorized` fields to the real `currentState` enum (see Completion Notes). No backend changes; no separate ci.yml step (runs via `mvn package` like the other check:* gates). |
