# Story 4.2: UI Operator Workflow-Owner Queue + Filters

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Workflow Owner using the React UI to triage failed/stalled/orphaned/takenover/overridden runs,
I want a workflow-owner queue view extending story 2.15's `RunReviewQueueItem` with operator-mode filters (state-based, failure-category-based, time-based, runner-kind-based) and a bulk-actions placeholder,
so that I can see the same fleet view as `deliveryline operator status` (story 4.1) in the UI — without forcing operators to switch to CLI for routine triage.

## Context & Central Reconciliation (READ FIRST)

**This is a TWO-LAYER story with a large HIDDEN BACKEND dependency.** Story 4.1 shipped the operator read model (`WorkflowInspectionService.getOperatorRunSummary(OperatorRunFilter) → OperatorRunSummary`) but was **CLI-ONLY** and explicitly deferred the REST surface and the UI queue to *this* story (4.1 Scope table: "REST endpoint / operator UI queue → DEFER → stories 4.2 / 4.4"). The epic text frames 4.2 as a pure UI story, but **the UI cannot exist without a new REST endpoint** — `useOperatorRunsList(filters)` (AC3) has nothing to call today. So 4.2 = **Part A (backend: new REST endpoint + DTO + cursor pagination + runner-kind read-model extension + OpenAPI regen)** and **Part B (frontend: new route, operator-variant row, filter sidebar, virtualization, pagination, a11y)**.

Read story **4.1** (`4-1-cli-operator-inspection-deliveryline-operator-status.md`) first — it defines the operator read model, the 5-token operator-state vocabulary, and the two forward hooks this story must honor (OQ-1 `overridden` semantics; the server-derived `operatorSignifier`). Read story **2.20** (`QueueShell`) and **2.15** (`RunReviewQueueItem` — its `variant: 'reviewer' | 'operator'` prop is **already declared with a live `operator` placeholder** you flesh out).

### HEADLINE RECONCILIATIONS (epic AC text drifts from live code — these bindings win)

1. **THE BIG ONE — no REST endpoint exists; 4.2 BUILDS it.** There is **zero** operator REST surface today (`adapters/rest` has no "operator" route; `openapi.json` mentions "operator" only in prose). AC3's `useOperatorRunsList(filters)` requires a new `GET /api/v1/operator/runs` (operationId `listOperatorRuns`) that delegates to the existing `WorkflowInspectionService.getOperatorRunSummary`. This is a NET-NEW backend deliverable owned by 4.2 — see OQ-SPLIT for whether Part A should be its own story. [Source: 4.1 Scope Boundary "REST endpoint … DEFER … stories 4.2"; grep of `adapters/rest` = 0 operator routes]

2. **Response is an OBJECT, not the repo's usual direct-array.** The existing queue endpoint `GET /api/v1/workflows` returns a bare `List<WorkflowSummaryResponse>` (no envelope). But the operator summary carries **aggregate + pagination** (`total`, `byState`, `byFailureCategory`, `oldestEntryAt`, `runs[]`, `nextCursor`), so the operator endpoint MUST return an object DTO `OperatorRunSummaryResponse`. This is a justified divergence from the direct-array convention — mirror `AllowedActionsResponse.from(...)` (an object list-carrier) for the mapping style, not `WorkflowSummaryResponse` (a row). Map `WorkflowState`/`FailureCategory` enums and the two `EnumMap` histograms to **wire strings** in the DTO. [Source: WorkflowController.listWorkflows:145-185; AllowedActionsResponse.java; WorkflowInspectionService OperatorRunSummary record :3446]

3. **Cursor pagination does NOT exist — BUILD it (AC5), the sort is already keyset-ready.** The 4.1 read model is `--limit`-capped only. `OperatorRunPersistenceAdapter.LIST_ROWS_SQL` already ends `order by last_transition_at desc nulls last, run_id desc limit :limit` — a deterministic composite ordering that IS a valid keyset. Add a cursor: thread an opaque `after` token (base64 of `lastTransitionAt|runId`) through `OperatorRunFilter`→`OperatorRunQuery`→`OperatorRunReadPort.listOperatorRuns`, add a keyset `WHERE (last_transition_at, run_id) < (:cursorTs, :cursorId)` predicate (mind the `nulls last` rows — runs with null `last_transition_at` sort last, so the predicate needs a null-aware branch), fetch `limit+1` to compute `hasMore`/`nextCursor`. The aggregate (`total`, histograms) is already over the FULL match set independent of `limit`, so it stays correct across pages — compute it once (page 1) and let the FE keep it. [Source: OperatorRunPersistenceAdapter.LIST_ROWS_SQL:147-167; OperatorRunQuery.java; OperatorRunReadPort.java]

4. **`runner kind` is NOT on the read model — EXTEND it (AC2 display + AC3 filter).** `OperatorRunRow` (4.1) has `runId, currentState, failureCategory, lastTransitionAt, actorIdentity, linkedTicketRef, linkedPrRef, escalationMarker, oldestEventAt, operatorSignifier` — **no `runnerKind`**. AC2 requires displaying runner kind in the operator row and AC3 a runner-kind filter (codex/claude/mock). Add `runnerKind` (nullable) to `OperatorRunRowSnapshot` + `OperatorRunRow` sourced from the run's **latest `runner_executions.runner_kind`** (LEFT JOIN the latest rex per run in `LIST_ROWS_SQL`), and a runner-kind predicate on `OperatorRunQuery`. See OQ-RUNNERKIND: if Part A scope is too heavy, the runner-kind *filter predicate* may ship as a disabled "coming soon" control while the *display* still lands (display needs the read-model field regardless). [Source: OperatorRunRow:3426; runner_executions.runner_kind column]

5. **`operatorSignifier` is server-derived — the UI row renders FROM it, never re-derives from `currentState`.** 4.1's code review found + fixed exactly this bug on the CLI: the renderer must NOT compute the bracketed label from `currentState` (it mislabeled active `overridden` runs as `[STALLED]` and never emitted `[OVERRIDDEN]`). The fix carries a server-derived `operatorSignifier` (precedence `ORPHANED>FAILED>TAKENOVER>STALLED>OVERRIDDEN>state`) on the row. The React `OperatorRow` MUST render the state badge/label from `run.operatorSignifier` (surfaced through the DTO), NOT re-implement precedence in TS. [Source: 4.1 Review Findings patch #1; `token-usage`… n/a; memory note "renderer must NOT derive the bracketed label from currentState"]

6. **The queue is FLEET-level; 2.14's allowed-actions is PER-RUN — the gating cannot use it, and 4.2 does NOT add a governed `AllowedAction`.** `getAllowedActions(workflowRunId, actorRole)` is keyed on a single run; there is **no run id** for a fleet queue, so `view_operator_queue` has no endpoint to be served from. 4.1 deliberately did NOT add a governed operator `AllowedAction` (E4 = deferred-RBAC, any local user; adding one triggers the 4-site lockstep — enum + `DomainRegistry.allowedActions()` + `allowed-actions.placeholder.json` + `RegistryContractTest`/`AllowedActionRegistryPinTest` — for zero E4 benefit). **PROVISIONAL BINDING (OQ-GATE):** build the gating *seam* (`useCanViewOperatorQueue()`) returning `true` for any local user in E4; wire the not-allowed empty-state (reuse the existing `ErrorState variant="permissionRestricted"`) as a **forward-looking, currently-unreachable** stub for E5 RBAC; do NOT add `view_operator_queue` to the governed registry, do NOT invent a fleet allowed-actions endpoint. Document the deferral. Confirm with Alex. [Source: AllowedAction.java (24 values, no operator-queue); WorkflowInspectionService.getAllowedActions:1122; RegistryContractTest:397; ErrorState variant `permissionRestricted`]

7. **The filter vocabulary is a NEW, MULTI-valued filter model — the existing `WorkflowListFilters` (single `state` string) cannot carry it.** AC3 needs multi-select state (5 tokens), multi-select failure-category, a time-window enum (1h/24h/7d/30d/all), and multi-select runner-kind. Build a dedicated `OperatorQueueFilters` type + `operatorKeys` query-key factory + a new `useOperatorRunsList` hook — do NOT bend `WorkflowListFilters`/`useWorkflowsList`/`workflowKeys` (those back the reviewer queue). Filters are **URL-owned** (single source of truth) via the route's `validateSearch`; TanStack strips any search key `validateSearch` doesn't parse, and every `navigate` must spread ALL active filters or they silently drop — this is the [[tanstack-validatesearch-strips-unparsed-param]] trap, and multi-valued params (arrays) make it sharper (parse `state=failed,stalled` → `string[]`; re-serialize on every nav). [Source: workflowKeys.ts WorkflowListFilters:20-31; routes/workflows/index.tsx validateSearch:24-45]

8. **`overridden` is still PROVISIONAL (4.1 OQ-1) and this story is the confirmation gate.** 4.1 bound `overridden` = latest `workflow_events.intervention_marker = true` AND non-terminal, and explicitly said "Confirm before 4.2 consumes the same vocabulary in the UI queue." The filter sidebar's `overridden` checkbox inherits that provisional semantics verbatim (reuse `OperatorRunState`). Surface the same 5 tokens; do not redefine them. Raise OQ-OVERRIDDEN to Alex. [Source: 4.1 OQ-1]

9. **New route path is literally `/operator/queue` (file-based routing).** Create `src/routes/operator/queue.tsx` (→ path `/operator/queue`); the Vite plugin regenerates `routeTree.gen.ts` (do NOT hand-edit it). This is a NEW top-level `operator` route segment (mirrors the CLI's new `operator` command group), NOT nested under `/workflows/`. [Source: routes/ file-based convention; routeTree.gen.ts auto-gen; App.tsx createRouter]

10. **Virtualization + pagination are net-new (no library, no precedent).** No windowing dep exists; the queue renders a plain `<ul>`. Add `@tanstack/react-virtual` (TanStack-ecosystem consistency; fixed `QUEUE_ROW_MIN_HEIGHT` makes fixed-size virtualization trivial). No FE cursor/infinite-query precedent exists either — use TanStack Query `useInfiniteQuery` with `getNextPageParam` reading `nextCursor`. Adding a dep means regenerating the lockfile cross-platform ([[frontend-lockfile-cross-platform]]) under committed `.npmrc legacy-peer-deps=true` ([[frontend-ts6-legacy-peer-deps]]). [Source: package.json (no react-virtual/react-window); QueueShell.tsx `<ul>` render; grep useInfiniteQuery = 0]

## Scope Boundary — what 4.2 BUILDS vs REUSES vs DEFERS

### Part A — Backend (the hidden dependency)

| Concern | 4.2 | Note |
|---|---|---|
| `GET /api/v1/operator/runs` (operationId `listOperatorRuns`) in a new `OperatorController` (`adapters.rest`) | **BUILD** | Reconciliation 1 — thin: parse query params → `OperatorRunFilter` → `getOperatorRunSummary` → `OperatorRunSummaryResponse.from` |
| `OperatorRunSummaryResponse` + `OperatorRunRowResponse` DTOs (object carrier; enums→wire strings; histograms `Map<String,Integer>`; `nextCursor`) | **BUILD** | Reconciliation 2 |
| Cursor pagination: `after` token on `OperatorRunFilter`→`OperatorRunQuery`→`OperatorRunReadPort.listOperatorRuns`; keyset `WHERE` in `LIST_ROWS_SQL`; `limit+1` → `nextCursor`/`hasMore` | **BUILD** | Reconciliation 3 — verify on real PG |
| `runnerKind` on `OperatorRunRowSnapshot` + `OperatorRunRow` (LEFT JOIN latest `runner_executions`) + runner-kind predicate on `OperatorRunQuery` | **BUILD** | Reconciliation 4 (filter predicate may defer per OQ-RUNNERKIND; display field does not) |
| Query params on the endpoint: `state` (csv), `failureCategory` (csv), `since` (relative token), `runnerKind` (csv), `limit`, `cursor` | **BUILD** | reuse 4.1's relative-`since` parser + token resolution in the service; endpoint stays thin |
| OpenAPI `@Schema allowableValues` for state-token + failure-category + runner-kind on the DTO so the FE gets typed enums via `generate-api` | **BUILD** | Reconciliation 2/7 — also fixes the stale `FailureCategory` enum in `openapi.json` (missing `runner_build_failed`) |
| OpenAPI snapshot regen (`-Dopenapi.snapshot.write=true`) + extend `OpenApiSnapshotContractTest` `.contains("listOperatorRuns")` | **BUILD** | [[openapi-regen-frontend-client-drift-cascade]] |
| ArchUnit: `OperatorController` thin-adapter (no business logic; no `OperatorRunState`/predicate resolution) | **BUILD** | mirror `WorkflowController` thinness rule |
| `WorkflowInspectionService.getOperatorRunSummary` + `OperatorRun*` records + `OperatorRunState` enum + `OperatorRunPersistenceAdapter` | **REUSE/EXTEND** | extend only for cursor + runnerKind; do NOT re-derive predicates |
| `view_operator_queue` governed `AllowedAction` / fleet allowed-actions endpoint | **DEFER (document only)** | Reconciliation 6 — E5 RBAC |
| Flyway migration / new `WorkflowState`/`WorkflowEventType`/`DomainErrorCode` | **NONE** | read-only; rides V1 indexes |

### Part B — Frontend

| Concern | 4.2 | Note |
|---|---|---|
| New route `src/routes/operator/queue.tsx` (`/operator/queue`) with `validateSearch` parsing multi-valued filters + `loaderDeps`/`loader` warm | **BUILD** | Reconciliation 7/9 |
| `useOperatorRunsList(filters)` (`useInfiniteQuery`) + `operatorKeys` query-key factory + `OperatorQueueFilters` type + `fetchOperatorRuns` (openapi-fetch `apiClient.GET('/api/v1/operator/runs')`) | **BUILD** | Reconciliation 3/7/10 |
| Flesh out `RunReviewQueueItem` `variant='operator'` `OperatorRow` — navigable; renders `operatorSignifier` label, failure category, runner kind, escalation-marker prominence, last-operator-action timestamp | **BUILD** | Reconciliation 5; 2.15 AC4 placeholder exists |
| Extend `RunQueueRow` view model with optional operator-only fields (`failureCategory?`, `runnerKind?`, `operatorSignifier?`, `lastOperatorActionAt?`) + `toOperatorQueueRow(response)` mapper | **BUILD** | keeps the one-component/one-row-type 2.15 contract; [[artifactview-variant-field-fanout]] discipline (guard `isRunQueueRow`/mapper) |
| Filter sidebar: state checkboxes (multi), failure-category checkboxes (multi), time-window selector (single), runner-kind checkboxes (multi) — URL-owned, re-runs the query | **BUILD** (net-new) | no checkbox/multi-select/time-window UI exists today; add a shadcn `checkbox` primitive |
| Queue shell states (`loading`/`empty`/`filtered-empty`/`error`) | **REUSE** | `QueueShell` / `resolveQueueState` / `EmptyState` / `ErrorState` — compose, don't rebuild |
| Virtualization (windowed list >100 items) via `@tanstack/react-virtual` | **BUILD** (net-new dep) | Reconciliation 10 |
| Cursor pagination (load-more-on-scroll) via `useInfiniteQuery` + `getNextPageParam` | **BUILD** (net-new) | Reconciliation 10 |
| Bulk-actions placeholder: "Select multiple" checkbox column + disabled "Bulk action" dropdown with "Bulk operator actions arrive in a future release" | **BUILD** | AC6 — expectation-setting only |
| Access gating seam `useCanViewOperatorQueue()` (returns true in E4) + not-allowed `ErrorState variant="permissionRestricted"` (forward stub) | **BUILD** | Reconciliation 6 |
| ARIA live announcement on filter change ("Filtered to {N} runs in state {state}") | **REUSE + EXTEND** | `useLiveAnnouncement` + add vocab to `announcements.ts`; [[livesnnouncement-defers-one-commit-test-flake]] |
| correlationId on the operator-runs GET | **REUSE** | automatic via `headerMiddleware`; add one asserting test |
| OpenAPI FE client regen (`npm run generate-api`) | **BUILD** | after Part A snapshot commit |
| Vitest + vitest-axe + cross-file router `Link` mock + MSW handlers + Radix Select jsdom shims | **REUSE** | mirror `QueueShell.test.tsx` / `RunReviewQueueItem.test.tsx` |

## Acceptance Criteria

> From `epic-04-recovery.md` §"Story 4.2" (lines 71–88), with **binding clarifications** in **bold parentheticals**.

1. **Given** `src/features/workflows/OperatorQueueRoute.tsx`, **Then** a new TanStack Router route `/operator/queue` (typed per story 2.5) renders the operator workflow-owner queue view; backend-reported allowed-actions (story 2.14) gate access via the `view_operator_queue` action (added to registry per drift test) — when not allowed, route renders an empty-state explaining permission posture (audit-only roles per story 2.25 AC8 + UX-DR21). **(RECONCILED — Reconciliation 6: the queue is FLEET-level and 2.14 allowed-actions is PER-RUN, so `view_operator_queue` has no endpoint and 4.2 does NOT add it to the governed registry. Build a gating seam `useCanViewOperatorQueue()` that returns `true` for any local user in E4's deferred-RBAC posture; render the not-allowed branch as a currently-unreachable forward stub using `ErrorState variant="permissionRestricted"`. File location is `src/routes/operator/queue.tsx` (file-based routing → `/operator/queue`); the epic's `OperatorQueueRoute.tsx` name maps to that route module. Route `validateSearch` parses the multi-valued filter model — Reconciliation 7.)**

2. **Given** the queue uses `RunReviewQueueItem` from story 2.15 with `variant='operator'` (already declared in story 2.15 AC4), **Then** the operator variant displays additional metadata in the row: failure category (when applicable), runner kind, escalation marker prominence, last operator action timestamp — these augment without overwhelming the standard row anatomy. **(The 2.15 `OperatorRow` placeholder exists and renders "Operator view — available in Epic 4" non-navigably — flesh it out. The bracketed state label comes from the server-derived `run.operatorSignifier` (Reconciliation 5), NOT re-derived from `currentState`. `runnerKind` is a NEW read-model field (Reconciliation 4). "Last operator action timestamp" = `lastTransitionAt` for E4 (there is no distinct operator-action column; document this). Extend `RunQueueRow` with optional operator fields + a `toOperatorQueueRow` mapper — do NOT overload the reviewer mapper.)**

3. **Given** filter UI components, **Then** a filter sidebar exposes: state checkboxes (failed / stalled / orphaned / takenover / overridden — multi-select), failure-category checkboxes (from `FailureCategory` registry — multi-select), time-window selector (last 1h / 24h / 7d / 30d / all), runner-kind filter (codex / claude / mock — multi-select). Selecting filters re-runs the underlying `useOperatorRunsList(filters)` query; results update live. **(NET-NEW: no checkbox/multi-select/time-window UI exists — add a shadcn `checkbox` primitive under `src/components/ui/`. `useOperatorRunsList` is a NEW `useInfiniteQuery` hook hitting the NEW `GET /api/v1/operator/runs`. State tokens = the 4.1 `OperatorRunState` vocabulary incl. provisional `overridden` (Reconciliation 8). Failure-category + runner-kind options come from the endpoint DTO's OpenAPI `allowableValues` enums (typed via `generate-api`), not hardcoded drift. Time-window maps to the endpoint's relative `since` token (`1h`/`24h`/`7d`/`30d`; "all" omits `since`). Filters are URL-owned; each toggle `navigate`s spreading ALL active filters — Reconciliation 7.)**

4. **Given** the empty-state per story 2.20 (queue shell states), **Then** the operator queue surfaces `loading` / `empty` (no runs match filters) / `filtered-empty` (filters narrowed to nothing) / `error` states using the same primitives. **(REUSE `QueueShell` + `resolveQueueState` + `EmptyState`/`ErrorState`/`LoadingState`(skeletons). "empty" = no non-happy runs at all; "filtered-empty" = active filters matched nothing (`filtersActive` true). Compose the existing shell with the operator `renderItem` + virtualization; do NOT fork a second shell.)**

5. **Given** queue depth + pagination, **Then** the list is virtualized (windowed rendering) for >100 items; results are paginated server-side with cursor-based pagination — UI loads more as the operator scrolls. **(BUILD both — Reconciliation 3 (backend cursor) + Reconciliation 10 (FE `@tanstack/react-virtual` + `useInfiniteQuery`). Endpoint returns `nextCursor?`; `getNextPageParam` reads it; virtualizer triggers `fetchNextPage` near the end. The aggregate/histograms come from page 1 and persist across pages — do not recompute per page.)**

6. **Given** bulk-actions placeholder, **Then** the UI includes a "Select multiple" checkbox column + a disabled "Bulk action" dropdown with text "Bulk operator actions arrive in a future release" — sets expectation without committing scope; a future story will activate it. **(Placeholder ONLY — the checkbox column may track selection state locally but wires to nothing; the dropdown is permanently `disabled` in E4. No bulk mutation endpoint, no selection persistence.)**

7. **Given** keyboard accessibility per story 2.25, **Then** the filter sidebar + queue list + pagination controls are fully keyboard-operable; focus order matches visual order. **(Checkboxes/selects keyboard-operable; virtualized rows remain reachable — virtualization must not break Tab order into off-screen rows (windowing caveat: document that only rendered rows are focusable; provide a "load more"/scroll affordance that's keyboard-triggerable). The operator `OperatorRow` is navigable (Enter/Space → run detail) unlike the placeholder.)**

8. **Given** ARIA per story 2.25 + announcement vocabulary, **Then** filter changes trigger an ARIA live region announcement ("Filtered to {N} runs in state failed"). **(REUSE `useLiveAnnouncement` (defers one commit — tests MUST `waitFor`, [[livesnnouncement-defers-one-commit-test-flake]]) + add a parameterized entry to `src/lib/a11y/announcements.ts` (e.g. `operatorFilteredToRuns(count, stateSummary)`) — the vocabulary node test `check:a11y` enforces the const/function convention. Announce off the resolved result count after the query settles.)**

9. **Given** correlation per story 1.19, **Then** the operator queue's API calls carry `correlationId` so backend log searches can find the originating UI session. **(SATISFIED by existing `headerMiddleware` — every `apiClient.GET` gets `X-Correlation-Id`. Add one test asserting the operator-runs GET carries it, mirroring `client.test.ts`. No new FE plumbing.)**

10. **Given** component test coverage, **Then** tests cover: route renders when allowed, redirects to empty-state when not allowed, filters apply correctly, virtualization works at >100 items, pagination loads more on scroll, bulk-actions placeholder visible but disabled, ARIA live region announces filter changes, axe-core a11y zero violations. **(Vitest + Testing Library + vitest-axe (WCAG 2.1 AA tags, zero `wcag2aa`); cross-file router `Link` mock + MSW handlers for `/api/v1/operator/runs` (multi-page) + Radix Select jsdom shims. Plus BACKEND tests: real-PG `OperatorRunCursorPaginationIT` (keyset stability across pages incl. null-`last_transition_at` rows + `runnerKind` join), `OperatorControllerTest`/contract (`@WebMvcTest` or slice — query-param → filter mapping, wire-string enum mapping, `nextCursor` echo, thin-adapter), `OpenApiSnapshotContractTest` `.contains("listOperatorRuns")`, ArchUnit thin-controller.)**

## Tasks / Subtasks

### Part A — Backend: operator-runs REST endpoint + cursor + runner-kind

- [x] **Task A1 — Read-model extension: cursor + runnerKind (AC5, AC2)**
  - [x] Add nullable `runnerKind` to `OperatorRunRowSnapshot` (`application.workflow.spi`) and `OperatorRunRow` (nested in `WorkflowInspectionService`) — appended at the END of each record (4.1 convention). Source = latest `runner_executions.runner_kind` for the run via a LEFT JOIN in `OperatorRunPersistenceAdapter.LIST_ROWS_SQL` (latest-rex correlated subquery/lateral, mirror the latest-event join).
  - [x] Add cursor support: extend `OperatorRunFilter` with `String cursor` (raw opaque token, nullable) and `OperatorRunQuery` with a decoded keyset `(OffsetDateTime cursorLastTransitionAt, String cursorRunId)` (nullable). Add a runner-kind predicate set to `OperatorRunQuery`.
  - [x] `OperatorRunReadPort.listOperatorRuns(query, limit)` → fetch `limit+1`; add the keyset `WHERE (last_transition_at, run_id) < (:ts,:id)` to `LIST_ROWS_SQL` with a null-aware branch for `last_transition_at IS NULL` rows (they sort last under `nulls last`, so the cursor must page into and through them correctly). Verify on real PG.
  - [x] Aggregate query (`loadOperatorRunAggregate`) unchanged — it already covers the FULL match set independent of `limit`; the cursor never touches it.
- [x] **Task A2 — Service: cursor encode/decode + runner-kind resolution (AC5, AC3)**
  - [x] In `getOperatorRunSummary`: decode the opaque `cursor` (base64 `lastTransitionAt|runId`; malformed → `INVALID_COMMAND_PAYLOAD` with `details{cursor}`), resolve runner-kind tokens (validate against known kinds), pass through to `OperatorRunQuery`; after fetching `limit+1`, drop the extra row and compute `nextCursor` (encode the last returned row's keyset) — expose it on `OperatorRunSummary` (append a nullable `String nextCursor` field). Reuse the 4.1 relative-`since` parser + `OperatorRunState.fromToken` unchanged.
  - [x] MDC/log entry+exit; `WARN` at any `INVALID_COMMAND_PAYLOAD` raise site (sanitized values).
- [x] **Task A3 — REST: `OperatorController` + DTOs + OpenAPI (AC1 backend seam, AC3)**
  - [x] New `OperatorController` (`adapters.rest`) `@GetMapping("/api/v1/operator/runs")` operationId `listOperatorRuns`: `@RequestParam` `state`, `failureCategory`, `since`, `runnerKind` (csv strings), `limit` (default 100), `cursor` → build `OperatorRunFilter` → `getOperatorRunSummary` → `OperatorRunSummaryResponse.from`. Thin: NO token/predicate resolution in the controller (that lives in the service — ArchUnit).
  - [x] `OperatorRunSummaryResponse { int total, Map<String,Integer> byState, Map<String,Integer> byFailureCategory, OffsetDateTime oldestEntryAt, List<OperatorRunRowResponse> runs, String nextCursor }` + `OperatorRunRowResponse { runId, currentState (wire), failureCategory, runnerKind, lastTransitionAt, actorIdentity, linkedTicketRef, linkedPrRef, boolean escalationMarker, oldestEventAt, operatorSignifier }`. `.from(...)` maps `WorkflowState`/`FailureCategory` enums + `EnumMap` histograms to wire strings. Add `@Schema(allowableValues=...)` for state-token / failure-category / runner-kind so the FE gets typed enums.
  - [x] Read GET: no `Idempotency-Key`, no `X-Actor-Identity`; `X-Correlation-Id` free via `CorrelationIdFilter`. Read-only.
- [x] **Task A4 — OpenAPI regen + backend tests + ArchUnit (AC1, AC5, AC10)**
  - [x] Regenerate the snapshot (`mvnw -pl deliveryline-backend -Dopenapi.snapshot.write=true test -Dtest=OpenApiSnapshotContractTest`), review + commit `openapi.json`; extend `OpenApiSnapshotContractTest` operationId assertions with `listOperatorRuns` (confirm the `FailureCategory` enum now includes `runner_build_failed`).
  - [x] `OperatorControllerTest` (slice — query-param→filter mapping, wire-string enums, `nextCursor` echo, empty/populated, thin-adapter). `OperatorRunCursorPaginationIT` (`@SpringBootTest`, `@Tag("integration")`, real PG, name `*IT` [[springboot-testcontainers-test-must-be-IT]]): seed >page-size mixed non-happy runs incl. null-`last_transition_at`, page through with cursor, assert no dup/skip across page boundary + `runnerKind` populated from latest rex.
  - [x] ArchUnit: `OperatorController` thin (mirror `WorkflowController` — no `OperatorRunState`/predicate logic); confirm the 4.1 `OPERATOR_RUN_VIEWS_REFERENCED_ONLY_BY_INSPECTION_AND_CLI` rule is WIDENED to also allow `OperatorController`/`OperatorRunSummaryResponse` (the view records now cross into `adapters.rest`) — Failsafe [[archunit-runs-in-failsafe-not-surefire]]. `spotless:apply` before commit [[spotless-apply-before-pushing-java-edits]].

### Part B — Frontend: operator queue route + filters + virtualization

- [x] **Task B1 — API client + query hook + filter model (AC3, AC5, AC9)**
  - [x] `npm run generate-api` to regen `src/lib/api/schema.d.ts` from the committed `openapi.json` (after Part A) — verify `check:api` in-sync ([[openapi-regen-frontend-client-drift-cascade]]).
  - [x] `OperatorQueueFilters` type (`states: string[]`, `failureCategories: string[]`, `runnerKinds: string[]`, `timeWindow: '1h'|'24h'|'7d'|'30d'|'all'`) + `operatorKeys` query-key factory (normalize/sort arrays for cache stability, mirror `workflowKeys.normalizeFilters`).
  - [x] `useOperatorRunsList(filters)` = `useInfiniteQuery` calling `fetchOperatorRuns` (`apiClient.GET('/api/v1/operator/runs', { params:{ query:{ state, failureCategory, since, runnerKind, limit, cursor } }})`, csv-join arrays, `timeWindow`→`since` token, "all"→omit); `getNextPageParam: (last) => last.nextCursor ?? undefined`. Flatten pages for rows; take aggregate/histograms from `pages[0]`.
- [x] **Task B2 — Route + gating seam (AC1)**
  - [x] `src/routes/operator/queue.tsx` (`createFileRoute('/operator/queue')`): `validateSearch` parses multi-valued filters (arrays from csv, time-window enum with a safe default `all`), `loaderDeps: ({search}) => search`, `loader` warms the infinite query's first page. Component reads `Route.useSearch()`/`useNavigate()`; every toggle spreads ALL active filters ([[tanstack-validatesearch-strips-unparsed-param]]).
  - [x] `useCanViewOperatorQueue()` seam (returns `true` in E4); when false, render `ErrorState variant="permissionRestricted"` (forward stub). Let the Vite plugin regen `routeTree.gen.ts` (do NOT hand-edit).
- [x] **Task B3 — Operator row + view model (AC2)**
  - [x] Extend `RunQueueRow` (`runQueueRow.ts`) with optional `failureCategory?`, `runnerKind?`, `operatorSignifier?`, `lastOperatorActionAt?`; add `toOperatorQueueRow(response)` mapper (guard nullable fields `!= null && trim` like the 3g-2 fix; [[workflowdetail-wire-sends-null-not-undefined]]). Keep `isRunQueueRow`/existing mapper intact ([[artifactview-variant-field-fanout]] discipline).
  - [x] Flesh out `OperatorRow` in `RunReviewQueueItem.tsx`: navigable (`onOpen` → `/workflows/$workflowRunId` for E4; 4.4 deep-dive is backlog), render badge/label from `run.operatorSignifier`, failure-category chip (when present), runner-kind chip, prominent escalation marker, `lastOperatorActionAt` relative time. Non-color signifier preserved (icon + label, story 2.3).
- [x] **Task B4 — Filter sidebar + shell + virtualization + bulk placeholder (AC3, AC4, AC5, AC6, AC7)**
  - [x] Add a shadcn `checkbox` primitive (`src/components/ui/checkbox.tsx`, Radix `@radix-ui/react-checkbox`); build `OperatorFilterSidebar` (state / failure-category / runner-kind multi-checkbox groups + time-window `Select`), URL-owned, each change `navigate`s. Options sourced from generated enums.
  - [x] Compose `QueueShell` (reuse `resolveQueueState`) with the operator `renderItem={(row)=><RunReviewQueueItem run={toOperatorQueueRow(row)} variant="operator" />}`; wrap the populated list in a `@tanstack/react-virtual` virtualizer (fixed `QUEUE_ROW_MIN_HEIGHT`) that calls `fetchNextPage` near the end; keyboard "load more" affordance for AC7.
  - [x] Bulk-actions placeholder: "Select multiple" checkbox column (local selection state, wires to nothing) + permanently-disabled "Bulk action" dropdown with the exact copy "Bulk operator actions arrive in a future release".
- [x] **Task B5 — a11y announcement + tests (AC8, AC9, AC10)**
  - [x] Add `operatorFilteredToRuns(count, stateSummary)` to `announcements.ts` (function per convention; `check:a11y` node test enforces it); announce via `useLiveAnnouncement` after the query settles.
  - [x] Tests (mirror `QueueShell.test.tsx` + `RunReviewQueueItem.test.tsx`): route renders when allowed; permission-restricted stub path; filters apply (MSW asserts query params); virtualization at >100 rows; `fetchNextPage` on scroll (multi-page MSW); bulk placeholder visible+disabled; filter-change announcement (`waitFor` the announcer); `expectNoA11yViolations` on sidebar+list (Radix Select jsdom shims); operator-runs GET carries `X-Correlation-Id`. Run `format:check`/`eslint` — one unformatted file reds the chain ([[prettier-gate-cascades-ci]]); no fn exports from `.tsx` ([[frontend-react-refresh-no-fn-exports]]).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Backend: `INFO` on `getOperatorRunSummary` entry (resolved filter incl. cursor presence, limit) + success (`total`, `returnedRows`, `nextCursor` present?, `durationMs`); `WARN` at `INVALID_COMMAND_PAYLOAD` (bad cursor/since/token, sanitized); `DEBUG` for the two port calls. `OperatorController` rides `CorrelationIdFilter` MDC.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` normal lifecycle, `WARN` recoverable anomalies (bad filter, malformed cursor), `ERROR` only unhandled failures. `DEBUG` for hot-path (operator may poll/scroll).
  - [x] Carry `correlationId` + the filter summary via MDC; per-row ids only at `DEBUG`. FE: no secret/PII logging.
  - [x] Never log secrets, payload bytes, raw tokens, or PII; sanitize user-supplied filter values before logging.
  - [x] Pin the completion-log + `WARN`-on-invalid-filter/cursor lines with `OutputCaptureExtension` (backend). FE relies on component tests, not logs (CLI stdout n/a here).

### Review Findings

_Adversarial code review 2026-07-06 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 2 decision-needed (RESOLVED by Alex → 1 fixed, 1 accepted as-is); 6 patch (5 FIXED + verified via `tsc`/eslint/71 vitest green, 1 dismissed as a false positive on verification); 4 deferred; 4 dismissed as noise._

**Decision-needed** (RESOLVED by Alex 2026-07-06):

- [x] [Review][Decision→Patch] Runner-kind filter hollow for global-default runs — **RESOLVED: ship the runner-kind filter as a disabled "coming soon" control (per OQ-RUNNERKIND) while keeping the display.** `runner_kind` (project-level override, sourced from `projects.runner_kind` per prior Alex sign-off) is `null` for global-default projects, so `f.runner_kind in (:runnerKinds)` silently excludes every default-runner run. Rather than mislead operators with empty filtered results, disable the sidebar runner-kind filter control; the AC2 display chip stays. Converted to patch below.
- [x] [Review][Decision] Failure-category AND-with-null drops non-failed states — **RESOLVED: accepted as intended cross-dimension AND semantics** (filtering by failure category inherently scopes to failed/orphaned runs). No change; not a defect.

**Patch** (fixes APPLIED 2026-07-06):

- [x] [Review][Patch] Runner-kind filter control now ships DISABLED ("coming soon") while the AC2 display chip stays — `CheckboxGroup` gained `disabled`/`note`; the runner-kind group renders disabled with a "future release" hint (resolved Decision 1) [OperatorFilterSidebar.tsx]
- [x] [Review][Patch] AC8 announcement now uses the cursor-independent `aggregate.total` (`matchCount`), not `rows.length` — count no longer understates and no longer re-announces on each page fetch [OperatorQueue.tsx]
- [x] [Review][Patch] Unknown `state`/`failureCategory`/`runnerKind` URL tokens are now dropped (retained against the shared vocab), so a stale/hand-edited URL no longer 400-loops the whole queue — `retainKnownTokens` + `parseKnownCsv`; vocab centralized in `operatorKeys.ts` (single source of truth, also consumed by the sidebar) [routes/operator/queue.tsx, operatorKeys.ts]
- [x] [Review][Patch] Virtualizer now measures each row (`ref={rowVirtualizer.measureElement}` + `data-index`, fixed `height` dropped) so wrapping operator rows no longer overlap/clip and `getTotalSize()` is correct; the 80px `estimateSize` stays as the pre-measure estimate [OperatorQueue.tsx]
- [x] [Review][Patch] Auto-load `useEffect` deps narrowed to `hasNextPage`/`isFetchingNextPage`/`fetchNextPage` (destructured), no longer the whole `query` object → runs on tail-scroll, not every render [OperatorQueue.tsx]
- [x] [Review][Patch → Dismissed] Present-but-unmapped `failureCategory` "renders no chip / dropped from aria-label" — **FALSE POSITIVE on verification: `humanizeFailureCategory` already degrades gracefully via `titleCaseToken` (an unmapped-but-present token is title-cased, e.g. `runner_build_failed` → "Runner Build Failed"); it returns `undefined` only for null/blank, which correctly renders no chip.** No change applied. [failureCategoryView.ts:53-58]

**Deferred** (real, not actionable now):

- [x] [Review][Defer] Time-window pagination is not point-in-time consistent — `now() - make_interval(...)` re-evaluated per page vs the absolute-timestamp keyset cursor; a run near the window edge can shift across pages [`OperatorRunPersistenceAdapter.java` RUN_FACTS_CTE] — deferred, inherent to relative-window pagination
- [x] [Review][Defer] Bulk-selection `Set` is not reset on filter/refetch — retains ids for rows no longer present [deliveryline-frontend/src/features/workflows/OperatorQueue.tsx:71] — deferred, harmless while the bulk control is a disabled placeholder; define reset semantics when bulk actions activate
- [x] [Review][Defer] The 5 operator-state filter tokens are a hardcoded FE const (drift risk vs the backend vocabulary) — the `@Schema(allowableValues)` workaround collapses multi-select, so no typed enum exists to bind to [deliveryline-frontend/src/features/workflows/components/OperatorFilterSidebar.tsx STATE_OPTIONS] — deferred, accepted constraint
- [x] [Review][Defer] Equal-`last_transition_at` keyset tiebreak branch (`run_id` comparator) is logically correct but not exercised — `OperatorRunCursorPaginationIT` seeds only distinct timestamps [OperatorRunCursorPaginationIT.java] — deferred, test-coverage gap

**Dismissed as noise:** FE `7d`/`30d` time-window "unsupported by parser" (false positive — `parseRelativeDuration` supports `d`/`w`); empty-string `nextCursor` infinite-refetch (unreachable — `encodeCursor` never emits empty); runner-kind vocabulary `manual` vs epic's `mock` (correct — real `RunnerKind` registry); bulk-actions `Button` vs `dropdown` (intent met — exact required copy present, permanently disabled).

## Dev Notes

### Relevant architecture patterns and constraints

- **The read seam (Part A).** `WorkflowInspectionService` (`org.dradgo.application.workflow`, `@Service`, every public method `@Transactional(readOnly=true)`) composes SPI ports (interfaces in `…workflow.spi`, impls in `adapters.persistence`) returning lossy snapshots — NO JPA/native query in the service. 4.1 built `OperatorRunReadPort` + `OperatorRunPersistenceAdapter` (native `NamedParameterJdbcTemplate` join: `workflow_runs` LEFT JOIN latest/latest-Failed/`MIN(created_at)` `workflow_events` + typed `integration_links`). 4.2 extends that adapter's `LIST_ROWS_SQL` with (a) a latest-`runner_executions` join for `runner_kind`, (b) a keyset `WHERE` for the cursor. Time math stays server-side (`now()`), clock-free (4.1 Reconciliation 4). The aggregate query is untouched.
- **The REST layer (Part A).** Controllers are thin (ArchUnit `REST_CONTROLLERS_STAY_THIN…`): parse → build command/filter → call service → map response. `OperatorController` imports `OperatorRunSummary`/`OperatorRunRow` (nested view records) — 4.1's ArchUnit rule restricting those to `WorkflowInspectionService`+`OperatorCommands`+`WorkflowCommandOutputs` MUST be widened to admit `OperatorController` + `OperatorRunSummaryResponse`. The SPI snapshots (`OperatorRunRowSnapshot`) stay OUT of `adapters.rest`. List DTO is an OBJECT (aggregate+cursor), diverging from the direct-array `listWorkflows` — justified; mirror `AllowedActionsResponse` object-carrier mapping.
- **The queue shell + row (Part B).** `QueueShell.tsx` owns state determination (`resolveQueueState({isError,isPending,count,filtersActive})` → `loading|empty|filtered-empty|error|populated`), composes `EmptyState`/`ErrorState`/`Skeleton`, and delegates rows via a `renderItem` render-prop. `RunReviewQueueItem` already branches `variant==='operator'` to a placeholder `OperatorRow` — flesh it out. `RunQueueRow` (`runQueueRow.ts`) is the view model; extend with optional operator fields + a dedicated `toOperatorQueueRow` mapper (do NOT overload `toRunQueueRow`, which maps `WorkflowSummary`).
- **Filters are URL-owned (Part B).** No local filter state — the route's `validateSearch` is the source of truth; `QueueShell` reads `filters` and emits toggle callbacks. Multi-valued params: serialize arrays as csv in the URL, parse back in `validateSearch`; EVERY `navigate` must spread all active filters or TanStack strips the unparsed ones ([[tanstack-validatesearch-strips-unparsed-param]]). New filter model = `OperatorQueueFilters` (NOT `WorkflowListFilters`).
- **Virtualization + infinite query (Part B).** Add `@tanstack/react-virtual`; fixed `QUEUE_ROW_MIN_HEIGHT` (from `queueState.ts`) → fixed-size windowing. Pagination = TanStack Query `useInfiniteQuery` + `getNextPageParam` reading `nextCursor`; the virtualizer triggers `fetchNextPage` near list end. Keyboard caveat (AC7): only rendered rows are focusable — provide a keyboard-triggerable load-more affordance so Tab order isn't trapped above the fold.
- **Allowed-actions posture (Part B).** FE never infers eligibility — normally it reads `useAllowedActions(runId)`. But that endpoint is per-run; a fleet queue has no run. So the gating is a seam `useCanViewOperatorQueue()` (true in E4), with a forward-stub not-allowed branch. Do NOT add a governed `AllowedAction` or a fleet allowed-actions endpoint (Reconciliation 6 — E5 RBAC owns that).
- **a11y.** `useLiveAnnouncement` defers one commit (tests `waitFor`); announcement wording lives in `announcements.ts` (const/function convention, `check:a11y` node test). axe via `expectNoA11yViolations` (WCAG 2.1 AA tags, zero `wcag2aa`); Radix `Select` needs `installRadixSelectJsdomShims` when axe-scanned. Cross-file router `Link` mock per-file ([[vitest-cross-file-router-mock]]).
- **correlationId.** Automatic on every `apiClient` request via `headerMiddleware` (`X-Correlation-Id`); AC9 needs no new plumbing, just a test.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying (enforced via the "Logging instrumentation" task).

- **Framework:** backend SLF4J + Logback (no `System.out`/`printStackTrace`); FE has no logger surface here (component tests, not logs).
- **Where to log (minimum surface):** `getOperatorRunSummary` → `INFO` entry (filter incl. cursor/limit) + success (`total`/`returnedRows`/`nextCursor?`/`durationMs`); `WARN` on invalid filter/cursor at the raise site; `DEBUG` for the two port calls (hot path). `OperatorController` rides `CorrelationIdFilter` MDC.
- **Required context keys:** `correlationId` + the sanitized filter summary; per-row ids only at `DEBUG`.
- **Forbidden:** payload bytes, secrets/tokens, PII; sanitize user-supplied `state`/`since`/`cursor`/`runnerKind` before logging.
- **Test contract:** pin the completion-log + `WARN`-on-invalid line with `OutputCaptureExtension`.

### Project Structure Notes

- **New backend main:** `OperatorController`, `OperatorRunSummaryResponse`, `OperatorRunRowResponse` (`adapters.rest`). **Modified backend main:** `WorkflowInspectionService` (`getOperatorRunSummary` cursor encode/decode + `nextCursor` on `OperatorRunSummary` + `runnerKind` on `OperatorRunRow`), `OperatorRunPersistenceAdapter` (runner-kind join + keyset WHERE), `OperatorRunReadPort`/`OperatorRunQuery`/`OperatorRunRowSnapshot`/`OperatorRunFilter` (cursor + runnerKind fields), `openapi.json` (regen). **Modified test:** `ArchitectureRuleCatalog`/`ArchitectureBoundaryTest` (widen operator-view rule to admit `OperatorController`), `OpenApiSnapshotContractTest`.
- **New FE:** `src/routes/operator/queue.tsx`, `src/features/workflows/hooks/useOperatorRunsList.ts`, `src/lib/queryKeys/operatorKeys.ts` (+ `OperatorQueueFilters`), `src/features/workflows/operatorQueueRow.ts` (`toOperatorQueueRow`), `src/features/workflows/components/OperatorFilterSidebar.tsx`, `src/features/workflows/hooks/useCanViewOperatorQueue.ts`, `src/components/ui/checkbox.tsx`, tests. **Modified FE:** `RunReviewQueueItem.tsx` (`OperatorRow` fleshed out), `runQueueRow.ts` (optional operator fields), `announcements.ts` (+ operator filter vocab), `schema.d.ts` (regen), `package.json`/lockfile (`@tanstack/react-virtual`, `@radix-ui/react-checkbox`). `routeTree.gen.ts` regenerates automatically.
- **No Flyway, no new `WorkflowState`/`WorkflowEventType`/`DomainErrorCode`/`AllowedAction`, no domain-registry value.** Read-only. Adding a dep → regen lockfile cross-platform ([[frontend-lockfile-cross-platform]]) under `.npmrc legacy-peer-deps=true` ([[frontend-ts6-legacy-peer-deps]]).
- **Verify in a clean env** — local green ≠ CI green ([[verify-ci-fixes-in-clean-env]]); FE lockfile must resolve on Linux; backend contract tests run in Failsafe.

### References

- [Source: _bmad-output/planning-artifacts/epic-04-recovery.md#Story 4.2 (lines 71–88)] — AC1–AC10.
- [Source: _bmad-output/implementation-artifacts/4-1-cli-operator-inspection-deliveryline-operator-status.md] — the operator read model, the 5-token vocabulary, `operatorSignifier` (Review patch #1), OQ-1 `overridden`, the deferred `view_operator_status`. **Read first.**
- [Source: _bmad-output/planning-artifacts/epics.md Story 2.15 (lines 1282–1303)] — `RunReviewQueueItem` `variant:'reviewer'|'operator'` (AC4) + row anatomy + one-primary-attention rule.
- [Source: _bmad-output/planning-artifacts/epics.md Story 2.20 (lines 1392–1409)] — `QueueShell` four states.
- [Source: _bmad-output/planning-artifacts/epics.md Story 2.14 (lines 1262–1281)] — allowed-actions is PER-RUN (`getAllowedActions(workflowRunId, actorRole)`); registry-additive contract; drift test.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:918,3358,3413,3426,3446] — `getOperatorRunSummary` + `OperatorRunState`/`OperatorRunFilter`/`OperatorRunRow`/`OperatorRunSummary`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/OperatorRunReadPort.java; OperatorRunQuery.java; OperatorRunRowSnapshot.java] — the cursor/runnerKind extension points.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/OperatorRunPersistenceAdapter.java:147-167] — `LIST_ROWS_SQL` `order by last_transition_at desc nulls last, run_id desc limit :limit` (keyset-ready).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java:145-185; WorkflowSummaryResponse.java; AllowedActionsResponse.java] — list-endpoint + DTO-mapping precedents (direct-array vs object-carrier).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java (24 values, no operator-queue); FailureCategory.java (incl. `runner_build_failed`, `orphan`)] — Reconciliation 6/2.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java; deliveryline-backend/src/main/resources/openapi/openapi.json] — snapshot drift gate; regen with `-Dopenapi.snapshot.write=true`.
- [Source: deliveryline-frontend/src/features/workflows/QueueShell.tsx; queueState.ts; components/RunReviewQueueItem.tsx (variant/`OperatorRow`); runQueueRow.ts] — shell + row seams.
- [Source: deliveryline-frontend/src/routes/workflows/index.tsx (validateSearch); src/lib/queryKeys/workflowKeys.ts; src/features/workflows/hooks/useWorkflowsList.ts; src/lib/api/queryOptions.ts] — filter/route/query precedents (do NOT bend — build operator siblings).
- [Source: deliveryline-frontend/src/lib/api/client.ts (headerMiddleware), correlation.ts, client.test.ts] — correlationId (AC9).
- [Source: deliveryline-frontend/src/lib/a11y/useLiveAnnouncement.ts; announcements.ts; src/test/a11y/axe.ts; src/features/workflows/__tests__/QueueShell.test.tsx] — a11y announcement + axe + router-mock test precedents.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.ts; components/ApprovalDecisionBar.tsx] — allowed-actions gating pattern (per-run; not directly reusable for the fleet queue — Reconciliation 6).

### Open Questions (for Alex — do not block dev; provisional bindings applied)

- **OQ-SPLIT — should Part A (backend operator-runs REST endpoint + cursor + runner-kind) be its own story?** The epic frames 4.2 as UI-only, but it carries a non-trivial backend deliverable (new endpoint, cursor pagination, read-model extension, OpenAPI regen) that the epic left unowned (4.1 deferred it here). This mirrors how the epic split detection/repair (4.15/4.16) and per-endpoint REST (4.10–4.14). **Provisional:** keep as ONE story with a clear Part A / Part B split (both land together so the UI is demoable). If Alex prefers, spin Part A as `4-2a` (backend) and keep `4-2` as pure FE — recommended if the combined diff is too large to review in one pass.
- **OQ-GATE — `view_operator_queue` gating.** The fleet queue has no run id, so 2.14's per-run allowed-actions can't gate it, and adding a governed `AllowedAction` triggers the 4-site lockstep for zero E4 benefit (no RBAC until E5). **Provisional:** gating seam returns `true` in E4; not-allowed branch is a forward stub. Confirm before wiring any registry value.
- **OQ-OVERRIDDEN — `overridden` semantics carry-over (4.1 OQ-1).** The filter's `overridden` checkbox inherits 4.1's provisional binding (latest `intervention_marker=true` + non-terminal). Confirm the vocabulary is stable before the UI surfaces it to operators.
- **OQ-RUNNERKIND — runner-kind FILTER predicate scope.** Displaying runner kind (AC2) needs the read-model field regardless. The runner-kind *filter predicate* (AC3) adds query complexity (latest-rex join in the WHERE). **Provisional:** build both. If Part A scope must shrink, ship the runner-kind display + a disabled "coming soon" runner-kind filter control (parallel to the bulk-actions placeholder) and defer the predicate.
- **OQ-LASTACTION — "last operator action timestamp" (AC2).** No distinct operator-action column exists on the run; E4 uses `lastTransitionAt` as the proxy. A true last-operator-action timestamp would need a `recovery_actions`-derived join — deferred until recovery actions (4.5–4.14) populate that table.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story workflow.

### Debug Log References

- Blocker resolved (Alex decision): the story's assumed `runner_executions.runner_kind` column does
  NOT exist and Flyway is forbidden → runner kind is sourced migration-free from `projects.runner_kind`
  via the existing `workflow_runs.project_id → projects` FK (LEFT JOIN in the read model). Semantics:
  the run's project-level runner override (null when the project uses the global default / no project).
- AC drift: the epic says runner kinds `codex/claude/mock`; the real `RunnerKind` registry is
  `codex/claude/manual` — used the registry values.
- Added a failure-category filter predicate (AC3) symmetric to runner-kind; the 4.1 read model only
  histogrammed by category, it had no category predicate.
- `@Schema(allowableValues)` on the `state`/`runnerKind` query params collapsed them to scalar enums
  in OpenAPI (breaking multi-select) → removed; params type as `string[]`. Typed filter OPTIONS still
  flow from the row DTO's nullable-enum `failureCategory`/`runnerKind` fields; state uses the stable
  5-token local const.
- Verification: backend — OperatorControllerTest 6/6 (surefire), OperatorCommandsTest 9/9,
  OperatorStatusJsonSchemaContractTest 3/3, WorkflowInspectionOperatorStatusIT 10/10 (4.1 regression),
  OperatorRunCursorPaginationIT 8/8 (real-PG keyset walk incl. null-tail + runner-kind join + filters),
  OpenApiSnapshotContractTest pass (drift gate), ArchitectureBoundaryTest 58/58 (widened operator-view
  rule). Frontend — full vitest 124 files / 1304 tests pass (incl. new OperatorQueue.test.tsx 9/9),
  `tsc -b` clean, `eslint --max-warnings=0` clean, `check:api` in sync, `check:a11y` pass, prettier
  written. spotless:apply on the backend.

### Completion Notes List

**Part A — backend (NEW `GET /api/v1/operator/runs`).** New `OperatorController` (thin adapter,
operationId `listOperatorRuns`) + object-carrier DTOs `OperatorRunSummaryResponse` /
`OperatorRunRowResponse` (enums → wire strings; histograms `Map<String,Integer>`; `nextCursor`).
Extended the 4.1 read model: `runnerKind` (nullable) added to `OperatorRunRowSnapshot` +
`WorkflowInspectionService.OperatorRunRow` sourced from a `left join projects` in `LIST_ROWS_SQL`;
NET-NEW keyset cursor (opaque base64url `<lastTransitionAt>|<runId>`, null-aware for the `nulls last`
tail) threaded through `OperatorRunFilter`→`OperatorRunQuery`→`OperatorRunReadPort`; NET-NEW
runner-kind + failure-category filter predicates in the shared `matched` CTE (so histograms reflect
the filtered fleet, cursor-independent). Cursor decode/encode + runner-kind/failure-category token
resolution live in the service (INVALID_COMMAND_PAYLOAD + WARN on malformed input); the endpoint
stays thin. Back-compat secondary constructors on `OperatorRunFilter`/`OperatorRunQuery` keep the 4.1
CLI + unit callers untouched. OpenAPI snapshot regenerated + `OpenApiSnapshotContractTest` asserts
`listOperatorRuns`; the 4.1 `OPERATOR_RUN_VIEWS_REFERENCED_ONLY_BY_INSPECTION_AND_CLI` ArchUnit rule
widened to admit the REST classes. NO Flyway / new WorkflowState / WorkflowEventType / DomainErrorCode
/ AllowedAction.

**Part B — frontend (`/operator/queue`).** NEW file-based route with URL-owned multi-valued filters
(`validateSearch` parses CSV → arrays; every nav spreads all active filters). NEW `OperatorQueueFilters`
+ `operatorKeys` factory; `useOperatorRunsList` = `useInfiniteQuery` over
`operatorRunsInfiniteQueryOptions` (`getNextPageParam` reads `nextCursor`, flattens pages, keeps page-1
aggregate). Fleshed out the 2.15 `RunReviewQueueItem variant='operator'` `OperatorRow`: now navigable
(stretched-link → run detail), renders the badge FROM `run.operatorSignifier` (never re-derives from
`currentState` — the 4.1 review lesson), plus failure-category / runner-kind / escalation /
last-operator-action metadata; `RunQueueRow` extended with optional operator fields + a dedicated
`toOperatorQueueRow` mapper (guarded present/trim). NEW `OperatorQueue` composes the 2.20 state
machinery (`resolveQueueState` + `EmptyState`/`ErrorState`/`Skeleton`) with a `@tanstack/react-virtual`
virtualizer (windowed >100 rows) + a keyboard load-more affordance (AC7), a NEW shadcn `checkbox`
primitive + `OperatorFilterSidebar` (multi-select state/failure-category/runner-kind + time-window
Select; options typed against the generated enums via a completeness-checked Record), the bulk-actions
placeholder (disabled dropdown + "Select multiple"), and the ARIA `operatorFilteredToRuns` announcement.
Access gating is the `useCanViewOperatorQueue` seam (true in E4; not-allowed = `ErrorState
variant="permissionRestricted"` forward stub — NO governed AllowedAction, Reconciliation 6). Added
`@tanstack/react-virtual` + `@radix-ui/react-checkbox` deps; regenerated the FE API client.

**Provisional bindings applied (OQs) — for reviewer/Alex confirmation:** OQ-GATE (gating seam true in
E4, no governed action); OQ-OVERRIDDEN (`overridden` vocabulary inherited from 4.1); OQ-RUNNERKIND
(runner kind sourced from `projects.runner_kind`, display + working filter both shipped);
OQ-LASTACTION (`lastTransitionAt` proxy for last-operator-action); OQ-SPLIT (kept as one story per
Alex).

### File List

**Backend — new:**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/OperatorController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/OperatorRunSummaryResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/OperatorRunRowResponse.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OperatorControllerTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/OperatorRunCursorPaginationIT.java`

**Backend — modified:**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (cursor encode/decode + runnerKind/failureCategory resolution; `OperatorRunFilter`/`OperatorRunRow`/`OperatorRunSummary` record fields + back-compat ctor)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/OperatorRunQuery.java` (runnerKinds/failureCategories/cursor fields + back-compat ctor + predicates)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/OperatorRunRowSnapshot.java` (runnerKind)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/OperatorRunPersistenceAdapter.java` (projects join, runner_kind, keyset cursor + failure-category/runner-kind predicates, params)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regen)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java` (`listOperatorRuns`)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` (widened operator-view rule)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/OperatorCommandsTest.java` (record-arg fixups)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/OperatorStatusJsonSchemaContractTest.java` (record-arg fixups)

**Frontend — new:**
- `deliveryline-frontend/src/routes/operator/queue.tsx`
- `deliveryline-frontend/src/features/workflows/OperatorQueue.tsx`
- `deliveryline-frontend/src/features/workflows/operatorQueueRow.ts`
- `deliveryline-frontend/src/features/workflows/components/OperatorFilterSidebar.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useOperatorRunsList.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useCanViewOperatorQueue.ts`
- `deliveryline-frontend/src/lib/queryKeys/operatorKeys.ts`
- `deliveryline-frontend/src/components/ui/checkbox.tsx`
- `deliveryline-frontend/src/features/workflows/__tests__/OperatorQueue.test.tsx`

**Frontend — modified:**
- `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx` (`OperatorRow` fleshed out)
- `deliveryline-frontend/src/features/workflows/runQueueRow.ts` (optional operator fields)
- `deliveryline-frontend/src/lib/api/queryOptions.ts` (`fetchOperatorRuns` + `operatorRunsInfiniteQueryOptions` + `OperatorRunSummary` type)
- `deliveryline-frontend/src/lib/a11y/announcements.ts` (`operatorFilteredToRuns`)
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regen)
- `deliveryline-frontend/src/routeTree.gen.ts` (auto-regen)
- `deliveryline-frontend/src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx` (operator-variant test updated)
- `deliveryline-frontend/package.json` + `package-lock.json` (`@tanstack/react-virtual`, `@radix-ui/react-checkbox`)

## Change Log

| Date | Change |
|---|---|
| 2026-07-06 | Story 4.2 implemented (Opus 4.8 [1m], bmad-dev-story). Part A backend: NEW `GET /api/v1/operator/runs` `OperatorController` + object-carrier DTOs over the 4.1 `getOperatorRunSummary`; keyset cursor pagination (null-aware tail); `runnerKind` read-model extension sourced from `projects.runner_kind` (Alex decision — no Flyway, column absent); NEW runner-kind + failure-category filter predicates; OpenAPI regen + snapshot assertion; widened operator-view ArchUnit rule. Part B frontend: NEW `/operator/queue` route with URL-owned multi-valued filters, `useOperatorRunsList` infinite query, fleshed-out `variant='operator'` `OperatorRow` (renders server `operatorSignifier`), `OperatorFilterSidebar` (+ shadcn `checkbox`), `@tanstack/react-virtual` virtualization + load-more, bulk-actions placeholder, `useCanViewOperatorQueue` gating seam, `operatorFilteredToRuns` announcement. All backend + frontend gates green (see Debug Log). Status → review. |
| 2026-07-05 | Story 4.2 created via bmad-create-story (Opus 4.8 [1m]) from 3 parallel Explore code-maps (FE queue/routing, FE filters/a11y/testing, backend REST/allowed-actions). TWO-LAYER story: Part A backend (NEW `GET /api/v1/operator/runs` `OperatorController` + `OperatorRunSummaryResponse` object DTO over the 4.1 `getOperatorRunSummary` read model + NET-NEW cursor pagination (keyset-ready sort) + `runnerKind` read-model extension + OpenAPI regen) + Part B frontend (NEW `/operator/queue` route, flesh out the 2.15 `variant='operator'` `OperatorRow` rendering server-derived `operatorSignifier`, NET-NEW filter sidebar (multi-checkbox state/failure-category/runner-kind + time-window), `@tanstack/react-virtual` virtualization + `useInfiniteQuery` cursor pagination, bulk-actions placeholder, a11y). HEADLINE RECONCILIATIONS: (1) no REST endpoint exists — 4.1 was CLI-only and deferred it here; (2) response is an OBJECT not the repo's direct-array (carries aggregate+cursor); (3) cursor pagination NET-NEW (sort already keyset); (4) `runnerKind` not on the read model — EXTEND; (5) render from server `operatorSignifier`, never re-derive from currentState (4.1 review lesson); (6) fleet queue vs per-run allowed-actions mismatch → gating seam true in E4, do NOT add governed `view_operator_queue` (E5 RBAC); (7) NEW multi-valued `OperatorQueueFilters` (URL-owned, validateSearch); (8) `overridden` still provisional (4.1 OQ-1); (9) route `/operator/queue`; (10) virtualization+pagination net-new deps. NO Flyway/WorkflowState/WorkflowEventType/DomainErrorCode/AllowedAction. 5 OQs (split; gate; overridden; runner-kind filter; last-action timestamp). Status → ready-for-dev. |
