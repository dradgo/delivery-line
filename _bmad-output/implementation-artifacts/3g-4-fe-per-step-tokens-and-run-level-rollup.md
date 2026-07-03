# Story 3g.4: Per-Step Tokens + Run-Level Rollup

Status: done

<!-- 2026-07-02 bmad-create-story context-engine pass (Opus 4.8 [1m]). Target sprint key: 3g-4-fe-per-step-tokens-and-run-level-rollup. FINAL Epic 3g story — FR74 display half, consuming 3g-3's persisted per-execution token columns. Source: epic-03g-provenance-token-accounting.md (Story 3g-4) + the delivered 3g-3 backend (3g-3-runner-token-usage-capture.md, status done). -->

> **READ FIRST — SCOPE DISCOVERY (this is NOT an FE-only story).** The epic's story-list framed 3g-4 as "FE — per-step token display + run-level rollup" and AC1 assumes a *3d-5 per-step log/step view* to hang tokens on. **That per-step view does not exist as a per-step list.** Verified: the only step surface is `StepExecutionLogViewer.tsx`, which streams *log lines* from the **single latest** runner execution over raw SSE (`useRunnerLogStream`) — it enumerates no steps and carries no typed metadata. There is **no `RunnerExecutionView`/step-list DTO, no `GET .../steps` endpoint, and no per-step schema type** anywhere (backend or FE). `RunnerExecutionSnapshot` (with 3g-3's `inputTokens/outputTokens/totalTokens`) is projected to **no** REST DTO today.
>
> **Therefore this story has THREE legs, not one:**
> 1. **Backend — net-new per-step read surface:** a `StepExecutionView` projection + a read-only `GET /api/v1/workflows/{workflowRunId}/steps` endpoint (mirrors the `reviewer-verdict` read-only endpoint pattern — **no new `AllowedAction`**, honoring the epic's locked light foundation-gate footprint), returning each runner execution's `stage/status/createdAt` + the three token counts. OpenAPI + `schema.d.ts` regenerate.
> 2. **Backend — run-level rollup:** `WorkflowInspectionService.getStatus` sums the **non-null** per-step `totalTokens` into a **nullable** `totalTokens` on `WorkflowDetailResponse` (`null` when no step reported any). Same OpenAPI regen.
> 3. **FE:** a per-step token panel (consuming leg 1) + the run-level total on the detail page (consuming leg 2), both with the **"not reported"** null posture (mirror 3d-7 `ProviderLimitStatus`).
>
> **You OWN the `schema.d.ts` regen here** (unlike 3g-2, which consumed 3g-1's regen). Regen the OpenAPI snapshot + FE client **before** the FE legs (the `openapi-regen → FE-client-drift` cascade).
>
> **SPLIT OPTION (flagged, not taken):** because the per-step read surface is net-new backend, the user was offered a split (3g-4 = rollup only; a new 3g-5 = per-step surface). No response was given, so this story delivers the **full FR74** (per-step + rollup) to avoid under-delivering a committed FR. If the story proves too large in dev, carve leg 1 (per-step) into a follow-up via `correct-course` — the rollup (legs 2+3-total) is independently shippable and already satisfies FR74's "run-level total."

## Story

As an authorized user,
I want each step to show its token usage and the run to show a total,
so that I can see where tokens were spent and the run's overall consumption.

## Acceptance Criteria

1. **Given** the per-step token surface, **Then** each runner execution (step) of the run renders its **input / output / total** tokens; a step whose counts are `null` shows an explicit **"not reported"** indicator (mirroring 3d-7's not-exposed posture) rather than `0` or a blank — distinguishing "agent reported zero" from "agent reported nothing." Because no per-step list surface exists, this requires a **net-new backend read leg**: a read-only `GET /api/v1/workflows/{workflowRunId}/steps` endpoint projecting each `RunnerExecutionSnapshot` to `{runnerExecutionId, stage, status, createdAt, inputTokens, outputTokens, totalTokens}` (tokens nullable, from 3g-3's columns), ordered oldest-first; **no new `AllowedAction`/state/event/error-code** (read-only, prefix-validated only — mirror the `reviewer-verdict` endpoint).
2. **Given** the run-level rollup, **Then** `WorkflowInspectionService.getStatus` **sums the non-null** step `totalTokens` into a **nullable** `Integer totalTokens` on `WorkflowStatusView` → `WorkflowDetailResponse` — `null` when **no** step reported any tokens (never `0`); OpenAPI + `schema.d.ts` regenerate (NOT byte-identical). The sum is over ALL executions of the run (every status; token columns are only ever set on terminal rows so non-terminal rows contribute `null` → skipped). Do **not** synthesize from input+output — sum the persisted per-step `totalTokens` only.
3. **Given** the detail page, **Then** the run-level `totalTokens` renders as the run's overall consumption, with the same **"not reported"** treatment when `totalTokens` is `null`; **tokens-only** is displayed (no estimated $ cost — the locked tokens-only decision; cost is a documented forward option).
4. **Given** the regenerated `schema.d.ts` (regenerated **first**, this story owns it) + the FE traps, **Then** any new pure helper/mapper lives in a **sibling `.ts`** (`frontend-react-refresh-no-fn-exports`), wire reads guard **`!= null`** (the `workflowdetail-wire-sends-null-not-undefined` trap; coalesce `?? undefined` at the view-model seam for `exactOptionalPropertyTypes`), and any announcer reflecting loaded tokens is asserted via `waitFor` (the `useLiveAnnouncement` one-commit-lag trap; an announcer is optional here).
5. **Given** accessibility, **Then** the per-step token cells, the "not reported" indicators, and the run-total surface meet WCAG 2.1 AA and are **axe-clean** (`expectNoA11yViolations`); numeric state is never conveyed by color alone.
6. **Given** tests, **Then** coverage asserts: per-step token render; the **not-reported** state when a step's counts are `null`; the run-level **rollup** total; the rollup's `null` (no step reported) state; components are **axe-clean**; backend coverage of `getStatus` rollup + the new `getStepExecutions` read (sum of non-null steps; `null` when none reported; oldest-first ordering) with `application.*` JaCoCo **≥80%**; the new `/steps` endpoint contract test; and the `OpenApiSnapshotContractTest` snapshot is regenerated (not byte-identical) with the FE client rebuilt from it.

## Tasks / Subtasks

- [x] **Task 1 — Backend: per-step read projection + service method** (AC: 1, 2, 6)
  - [x] In `application/workflow/WorkflowInspectionService.java`, add a public read-only method `List<StepExecutionView> getStepExecutions(String workflowRunPublicId)`: prefix-validate (`PublicIdPrefixes.require(..., WORKFLOW_RUN)`) and throw `runNotFound` if the run is absent (same guard shape as `getReviewerVerdict` `:245-249` / `getStatus` `:1238-1245`); fetch executions via `runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(runId, ALL_STATUSES)` — the exact "all executions for a run" pattern used by `findLatestRunnerExecutionId` (`:2135-2146`) — where `ALL_STATUSES = List.of(RunnerExecutionStatus.values())`; **sort oldest-first by `createdAt()`** (the port does not order — `findLatestRunnerExecutionId` maxes manually, confirming unordered); map each to a new `StepExecutionView`. MDC-scope + INFO entry/exit with `count=`.
  - [x] Add the projection record next to the other inner views (near `LatestArtifactView` `:2530`): `public record StepExecutionView(String runnerExecutionId, String stage, String status, OffsetDateTime createdAt, Integer inputTokens, Integer outputTokens, Integer totalTokens)`. Source fields from `RunnerExecutionSnapshot`: `publicId()`, `stage()==null?null:stage().value()`, `status()==null?null:status().value()`, `createdAt()`, and the three token accessors (`inputTokens()/outputTokens()/totalTokens()` — added by 3g-3, `RunnerExecutionSnapshot.java:50-52`). All three tokens are `Integer` nullable — pass through verbatim (no coalesce to 0).

- [x] **Task 2 — Backend: run-level `totalTokens` rollup on the detail view** (AC: 2, 6)
  - [x] In `getStatus` (`:1232-1323`), before building `WorkflowStatusView`, compute the rollup: fetch the run's executions once via `runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(run.publicId(), ALL_STATUSES)` and sum each row's **non-null** `totalTokens()` into an `Integer runTotalTokens` that is **`null` when no row reported any** (accumulate into a nullable: start `null`, on the first non-null add promote to a running int; return `null` if none seen — do NOT default to `0`). Extract this to a private helper `Integer rollupTotalTokens(List<RunnerExecutionSnapshot> rows)` for unit-testability. Guard the int32 sum against overflow defensively (the three token columns are int4 and 3g-3 caps each at `Integer.MAX_VALUE`; a run with many maxed steps could overflow — clamp the accumulator at `Integer.MAX_VALUE` rather than wrap, and `log.warn` once if clamped).
  - [x] Append a nullable `Integer totalTokens` to the **END** of the canonical `WorkflowStatusView` record (`:2453-2484`, after `decompositionStatus`). Extend the 16-arg back-compat ctor (`:2486-2527`) tail with one trailing `null` (it already passes defaults for the 3f-3/3f-7 fields — add one more `null`). Pass `runTotalTokens` at the `new WorkflowStatusView(...)` call (`:1290-1314`, after `decompositionStatus`).
  - [x] Append a nullable `Integer totalTokens` to `WorkflowDetailResponse` (`adapters/rest/WorkflowDetailResponse.java:20-78`, after `decompositionStatus` `:78`) with `@Schema(description="Run-level token consumption: sum of per-step totalTokens where reported (story 3g-4, FR74). Null when no step reported tokens.", nullable=true, example="12345")`; pass `view.totalTokens()` at the END of the `from(...)` call (`:81-106`).
  - [x] **No `containsExactlyInAnyOrder` guard on the detail** — the exact-field trap is summary-only (already handled by 3g-1's `ticketTitle`); `WorkflowReadEndpointsContractTest.detailIsCamelCaseDirectShapeWithinTwoSeconds` (`:218-232`) checks only a few fields, so adding `totalTokens` is safe there. The gate that WILL fire is `OpenApiSnapshotContractTest` — regenerate the snapshot (Task 4).

- [x] **Task 3 — Backend: the `/steps` REST endpoint** (AC: 1, 6)
  - [x] Add a read-only endpoint to `adapters/rest/WorkflowController.java` modeled EXACTLY on `getReviewerVerdict` (`:626-667`): `@GetMapping(value="/{workflowRunId}/steps", produces=APPLICATION_JSON_VALUE)` + `@Operation(operationId="listStepExecutions", ...)` + the standard 200/400(INVALID_ID_PREFIX)/404(RUN_NOT_FOUND) `@ApiResponses` block. Return `List<StepExecutionResponse>` (a **direct JSON array**, matching the `GET /workflows` list precedent — no envelope). Delegate to `workflowInspectionService.getStepExecutions(workflowRunId)`, mapping each `StepExecutionView` via `StepExecutionResponse.from(...)`. INFO entry/exit with `MdcKeys.sanitizeForLog(workflowRunId)` + result `count=` (mirror the reviewer-verdict logging).
  - [x] Add `adapters/rest/StepExecutionResponse.java` — a record mirroring `ReviewerVerdictResponse`/`LinkedTicket` shape: `@Schema(name="StepExecution") record StepExecutionResponse(String runnerExecutionId, String stage, String status, OffsetDateTime createdAt, @Schema(nullable=true) Integer inputTokens, @Schema(nullable=true) Integer outputTokens, @Schema(nullable=true) Integer totalTokens)` + a `static StepExecutionResponse from(StepExecutionView view)` that normalizes the timestamp to UTC (reuse the `WorkflowDetailResponse.toUtc` idiom — `createdAt.withOffsetSameInstant(ZoneOffset.UTC)`, null-safe).
  - [x] **No new `AllowedAction`** — this is a read-only diagnostic surface (like reviewer-verdict), prefix-validated only. Do NOT gate it behind `view_runner_logs` or add a matrix row (the epic's locked light foundation-gate footprint: NO new state/action/event/error-code).

- [x] **Task 4 — Regenerate OpenAPI snapshot + FE client (do this BEFORE the FE legs)** (AC: 2, 4)
  - [x] Backend: regenerate the committed OpenAPI snapshot — run `OpenApiSnapshotContractTest` with `-Dopenapi.snapshot.write=true` (it writes `deliveryline-backend/src/main/resources/openapi/openapi.json` then fails asking you to review+commit; re-run without the flag to confirm green). Verify the new `StepExecution` schema, the `/steps` path, and `WorkflowDetail.totalTokens` all appear in the diff.
  - [x] FE: regenerate the typed client from the committed snapshot — `npm run generate-api` (the `openapi-fetch` `schema.d.ts` is built from `openapi.json`; the `openapi-regen-platform-shim` + `frontend-lockfile-cross-platform` notes apply — regen on the canonical platform). Confirm `WorkflowDetail.totalTokens?: number | null` and a `StepExecution` (or the `/steps` path's response) type land in `deliveryline-frontend/src/lib/api/schema.d.ts`. Then `npm run check:api` green. **This story OWNS this regen** — it is not pre-committed.

- [x] **Task 5 — FE: per-step token panel** (AC: 1, 4, 5, 6)
  - [x] Add a query hook for the new endpoint alongside `lib/api/queryOptions.ts` (mirror the existing `openapi-fetch` read hooks; the `/steps` GET returns the `StepExecution[]` array). If the codebase wraps reads in `queryOptions` factories, add a `stepExecutionsQueryOptions(workflowRunId)`; otherwise a `useStepExecutions(workflowRunId)` hook using the generated client + TanStack Query (do NOT hand-roll `fetch` — this endpoint is a normal JSON read, unlike the SSE log stream).
  - [x] Add `features/workflows/stepTokensView.ts` (sibling `.ts` pure mapper — the `runContextView.ts`/`prLinkageView.ts` convention): map each wire `StepExecution` to a `StepTokenRow { runnerExecutionId, stage, status, createdAt, inputTokens?: number, outputTokens?: number, totalTokens?: number }` coalescing each nullable count via `!= null ? v : undefined` (reuse the `presentOrUndefined` idiom, `runContextView.ts:113-115`). Export a small formatter `formatTokenCount(n: number | undefined): string` returning the number or the literal **"Not reported"** for `undefined` — the exact 3d-7 posture (`ProviderLimitStatus.tsx` renders `'Not exposed'`/`'n/a'` for absent, never `0`).
  - [x] Add `features/workflows/components/RunStepTokensPanel.tsx` — a presentational panel taking `{ workflowRunId }`, calling the query, and rendering a `<section aria-label="Step token usage">` with one row per step: the `stage` (+ `status` chip — reuse `StateSignifierChip` if apt), and three labeled cells **Input / Output / Total** each via `formatTokenCount` (null → "Not reported"). Self-hide (`return null`) when the list is empty (parity with `RunDependencyPanel`). Loading/error states minimal (mirror `ProviderLimitStatus.tsx`). Numbers are plain React-escaped text; "Not reported" carries a visible textual label, not color-only (AC5).
  - [x] Mount `<RunStepTokensPanel workflowRunId={workflowRunId} />` in `routes/workflows/$workflowRunId/index.tsx` near the other self-hiding panels (after `ProviderLimitStatus` at `:213`, or grouped with `StepExecutionLogViewer` at `:319` since both are step-scoped). Reuse the route's `workflowRunId`; the panel owns its own query (a small extra read is fine — it is a distinct concern from `useWorkflowDetail`, like `ProviderLimitStatus`/`reviewer-verdict` panels which each fetch their own).

- [x] **Task 6 — FE: run-level total on the detail page** (AC: 2, 3, 4, 5, 6)
  - [x] Consume `data.totalTokens` (from `useWorkflowDetail`, the route's warmed `data`) and render the run's overall token total with the **"not reported"** posture when `null`. Prefer a compact surface: either a labeled line inside `RunStepTokensPanel`'s header ("Run total: N / Not reported") so per-step + total live together, OR a tiny standalone `RunTokenTotal` element near the top of the detail `<Stack>`. Reuse `formatTokenCount` from `stepTokensView.ts` (a `.ts`, so no react-refresh violation). Guard `data?.totalTokens != null` (wire sends `null`, not `undefined`).
  - [x] Do NOT display an estimated $ cost — tokens-only (AC3, locked). Do NOT synthesize a total on the FE from per-step rows — render the backend-computed `totalTokens` (the backend is the single source of the rollup, AC2).

- [x] **Task 7 — FE structured logging (field-only)** (AC: 4)
  - [x] Presentational read-only panels; the FE logging standard is the field-only structured `console` discipline (`console.info({ event, ...primitives })`) used across the workflows feature. Only emit if you add an interaction (none required here). **Never** log token counts tied to identifiers as PII — counts are non-secret, but keep logs to `{ event, stepCount }` at most. No backend log surface beyond the two new INFO lines (Task 1 + Task 3).

- [x] **Task 8 — Tests** (AC: 1-6)
  - [x] **Backend unit (`WorkflowInspectionService`):** `rollupTotalTokens` sums non-null steps; returns **`null`** when every step's `totalTokens` is null (parity — NOT `0`); mixed null/non-null sums only the non-null; overflow clamps at `Integer.MAX_VALUE` + WARN. `getStepExecutions` returns oldest-first, maps stage/status/tokens, throws `RUN_NOT_FOUND` for a missing run and `INVALID_ID_PREFIX` for a bad prefix. Use the lean `new WorkflowInspectionService(...)` ctor test sites (the ~10 existing ones stay green — you are appending a field to a returned record + a new method, not a ctor dep).
  - [x] **Backend IT / contract:** extend `WorkflowReadEndpointsContractTest` — detail carries `totalTokens` (a run with token-bearing executions → the summed value; a run with none → JSON `null`, not `0`/absent); a new `stepsReturnsPerExecutionTokensOldestFirst` asserting the `/steps` array shape (`runnerExecutionId/stage/status/createdAt/inputTokens/outputTokens/totalTokens`), token nullability, and ordering. Seed executions with 3g-3's token columns (note the 3g-3 IT trap: a `completed` row needs completion/correlation fields per `ck_runner_executions_completed_correlation` — seed `status='completed'` with the required fields, or reuse the story-3g-3 IT seeding helper). Name any new Testcontainers test `*IT` (the `springboot-testcontainers-test-must-be-IT` trap).
  - [x] **OpenAPI snapshot:** `OpenApiSnapshotContractTest` green after regen (Task 4) — the snapshot now includes `/steps`, `StepExecution`, and `WorkflowDetail.totalTokens`.
  - [x] **FE Vitest:** `stepTokensView.test.ts` (render-free): maps rows, coalesces null tokens to `undefined`, `formatTokenCount` returns the number for a value and "Not reported" for `undefined`. `RunStepTokensPanel.test.tsx`: per-step tokens render; a step with null counts shows "Not reported" (not `0`/blank); panel self-hides on empty; axe-clean (mirror `ProviderLimitStatus.test.tsx` — MSW `server.use(http.get(...))` stubbing the `/steps` endpoint, `QueryClientProvider` wrapper, `expectNoA11yViolations`). Run-total render + null "Not reported" case; axe-clean.
  - [x] **Green gates:** backend `mvnw` (Surefire + the Testcontainers `verify` tier with `-Djacoco.skip=true` for direct failsafe, per the `maven-argline-direct-goal-crash` note); FE `npm run test` / `npm run build` (tsc+vite) / `npm run lint` (max-warnings=0) / `npm run check:api`.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Backend: `getStepExecutions` and the `/steps` controller each log `INFO` entry/exit with `workflowRunId` (MDC/sanitized) + result `count=`; `getStatus` already logs — extend its success line with `totalTokensPresent={}` (boolean, non-secret). Token counts are non-secret numeric data; safe to log counts, but prefer count-of-steps over per-step dumps.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never concatenation.
  - [x] Levels: `INFO` for the two new read lifecycles + the rollup presence; `WARN` for the overflow-clamp anomaly (Task 2); no new `ERROR`/`DEBUG` surface required.
  - [x] Context keys: `workflowRunId` via `MdcKeys` (already scoped in `getStatus`/`getReviewerVerdict`); no new MDC key is needed (do not add `runnerExecutionId` to MDC — the permitted-key set is closed; pass as a parameter if ever needed).
  - [x] Never log secrets/PII; token counts and ids only. FE: field-only `console` per Task 7.
  - [x] Pin the new INFO lines with a focused `OutputCaptureExtension`/list-appender assertion (the `/steps` success `count=` and the `getStatus` `totalTokensPresent=` line).

### Review Findings

_2026-07-02 bmad-code-review (Opus 4.8 [1m]) — 3 adversarial layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor) over the story-scoped diff (`891f04e~1` → working tree, File-List paths only; note the OpenAPI regen + all FE files are still uncommitted). Acceptance Auditor: **all 6 ACs PASS** + every named anti-pattern honored (no new AllowedAction/state/event/error-code; no FE re-summing; sibling `.ts`; `!= null` wire guards; oldest-first; no input+output synthesis; OpenAPI + `schema.d.ts` both regenerated). Triage: 3 patches (all Low), 1 deferred, 4 dismissed. No decision-needed. No confirmed HIGH/MEDIUM._

**Patch (all applied + verified 2026-07-02):**

- [x] [Review][Patch] Run-level total is hidden whenever the `/steps` read errors or is pending, though `totalTokens` comes from the independent detail query [deliveryline-frontend/src/features/workflows/components/RunStepTokensPanel.tsx:33-53] — blind+edge+auditor converged. `isError` → error box (no total); `isPending`/empty → `return null`. When `useWorkflowDetail` already reports a non-null `totalTokens` but the separate `/steps` GET fails, the run's overall consumption (AC3) disappears. **FIXED:** the `isError` branch now renders the `run-step-tokens-total` line when `runTotal !== undefined`; the empty-run case stays consistent (rollup is `null` when no step reported).
- [x] [Review][Patch] Equal-`createdAt` steps sort nondeterministically — no stable tiebreaker on an unordered port [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:407-412] — edge, verified against source. Sort was `Comparator.comparing(createdAt, nullsFirst(naturalOrder()))` with no `.thenComparing(...)`; `Stream.sorted` is stable but `findByWorkflowRunPublicIdAndStatusIn` has no `ORDER BY`, so two same-millisecond executions could flip order across requests. **FIXED:** appended `.thenComparing(RunnerExecutionSnapshot::publicId)` for a deterministic oldest-first order.
- [x] [Review][Patch] Absent `runnerExecutionId` collapses to duplicate React keys [deliveryline-frontend/src/features/workflows/components/RunStepTokensPanel.tsx:72-74] — blind+edge. Generated schema types `runnerExecutionId?: string`; the mapper coalesces `?? ''` and the panel keyed `key={row.runnerExecutionId}`, so two absent ids both become `key=''`. Defensive only (backend `publicId` is `NOT NULL` today). **FIXED:** key now falls back to the row index (`key={row.runnerExecutionId || \`idx-${index}\`}`).

_Verification: FE tsc clean, eslint 0-warn, prettier `format:check` clean (also normalized a pre-existing prettier miss on `RunStepTokensPanel.test.tsx`), `stepTokensView.test.ts` 7/7 + panel suite green; backend `compile` exit 0 + `spotless:apply` clean._

**Deferred:**

- [x] [Review][Defer] Verify tertiary-on-surface small-text contrast on the "Step token usage" heading + "Run total" label meets WCAG 2.1 AA [deliveryline-frontend/src/features/workflows/components/RunStepTokensPanel.tsx:55-57] — deferred, advisory. jest-axe does not compute contrast ratios, so the axe-clean gate does not prove AC5's 4.5:1 body-text threshold for `text-meta text-text-tertiary` on `bg-surface`. Needs a one-time manual/token-level contrast check; design-system-wide, not specific to this change.

**Dismissed (4):**

- Shared-`HAPPY_RUN` fixture test flake (Blind, HIGH) — **FALSE POSITIVE**: `truncate table workflow_runs restart identity cascade` runs in **both** `@BeforeEach` (:94) and `@AfterEach` (:97), cascading to `runner_executions`, so the two token tests cannot cross-pollute. Verified.
- No `AllowedAction` gate on the stage/status/token exposure (Blind, speculative) — **by-design**: AC1 + the epic's locked light foundation-gate footprint mandate a read-only, prefix-validated surface mirroring `reviewer-verdict`; `AllowedActionRegistryPinTest` green.
- Rollup skips a step with `input`/`output` but `null` `totalTokens` (Blind) — **by-design**: AC2 explicitly forbids synthesizing the run total from input+output; sum the persisted per-step `totalTokens` only.
- `createdAt` carried in `StepTokenRow` but never rendered (Blind) — **noise**: not an AC requirement (backend already orders oldest-first); harmless unused view-model field.

## Dev Notes

### The real shape of this story (read before coding)

The epic under-specified this: it assumed a per-step list view existed (from 3d-5) to attach tokens to. **It does not** — 3d-5 shipped a *log stream* of the latest execution, not a step enumeration. So the honest work is three legs:

1. **Per-step read surface (net-new backend):** `StepExecutionView` projection + `getStepExecutions` service read + `GET /{id}/steps` endpoint returning `StepExecution[]`. Read-only, prefix-validated, **no new `AllowedAction`** — modeled on the `reviewer-verdict` read endpoint. This is the leg the epic's "FE-only" framing missed.
2. **Run-level rollup (backend):** one new nullable `Integer totalTokens` summed over non-null per-step `totalTokens`, carried `WorkflowStatusView → WorkflowDetailResponse`. `null` when none reported (never `0`).
3. **FE (two surfaces):** a per-step token panel (leg 1) + the run-total (leg 2), both with the 3d-7 "not reported" null posture.

Both backend legs read the SAME rows (`findByWorkflowRunPublicIdAndStatusIn(runId, ALL_STATUSES)`) but from two entry points (`getStepExecutions` for the list; `getStatus` for the sum) — that is acceptable (they are distinct endpoints; do not try to fold the step list into `WorkflowDetail`, which would bloat the detail DTO and its snapshot).

### Why "not reported" (null), never 0 (the locked token posture)

3g-3 persists each count **independently as reported** and leaves `null` where the agent reported nothing (command-only/no-LLM steps, unreported usage). A `0` means "agent reported zero tokens"; `null`/"not reported" means "no data." Collapsing null→0 fabricates governed data. Mirror 3d-7 `ProviderLimitStatus` exactly: absent → a textual "Not reported"/"Not exposed" state, never `0`, never blank, never color-only.

### Source-tree components to touch (with line anchors — verify before editing)

- **Backend — service (Tasks 1-2):**
  - `application/workflow/WorkflowInspectionService.java` — `getStatus` `:1232-1323` (rollup + `new WorkflowStatusView(...)` `:1290-1314`); `WorkflowStatusView` record `:2453-2484` + 16-arg back-compat ctor `:2486-2527`; inner-view neighborhood `LatestArtifactView` `:2530-2543` (place `StepExecutionView` here); the "all executions for a run" read pattern in `findLatestRunnerExecutionId` `:2135-2146`; guard/MDC idiom in `getReviewerVerdict` `:245-249` and `getStatus` `:1238-1245`.
  - `application/runner/spi/RunnerExecutionRecordPort.java` — `findByWorkflowRunPublicIdAndStatusIn(String, List<RunnerExecutionStatus>)` `:23-24` (the list read; no unfiltered/ordered variant exists — sort in the service).
  - `application/runner/spi/RunnerExecutionSnapshot.java` — token accessors `inputTokens()/outputTokens()/totalTokens()` `:50-52`; `publicId()/stage()/status()/createdAt()` are existing.
- **Backend — REST (Tasks 2-3):**
  - `adapters/rest/WorkflowDetailResponse.java` — record `:20-78` (append `totalTokens` after `decompositionStatus` `:78`); `from(...)` `:81-106`; `toUtc` helper `:109-111`; `LinkedTicket` shape precedent `:135-158`.
  - `adapters/rest/WorkflowController.java` — `getReviewerVerdict` endpoint `:626-667` (the read-only GET template to copy); the list-endpoint direct-array precedent (`GET /api/v1/workflows`).
  - New `adapters/rest/StepExecutionResponse.java` (mirror `ReviewerVerdictResponse` / the `LinkedTicket` inner record).
- **Backend — snapshot/contract gates (Task 4, 8):**
  - `adapters/rest/OpenApiSnapshotContractTest.java` `:30-58` — regen via `-Dopenapi.snapshot.write=true` writing `src/main/resources/openapi/openapi.json`.
  - `adapters/rest/WorkflowReadEndpointsContractTest.java` — summary exact-field guard `:185-200` (detail has NO such guard `:218-232`; safe to add `totalTokens`).
- **FE (Tasks 5-6):**
  - `deliveryline-frontend/src/lib/api/schema.d.ts` — `WorkflowDetail` `:1572-1633` (gains `totalTokens?: number | null` after regen); no step type exists yet (gains `StepExecution`).
  - `deliveryline-frontend/src/lib/api/queryOptions.ts` — `WorkflowDetail`/`WorkflowSummary` type surfacing `:33-34`; add the `/steps` read hook/queryOptions here.
  - `deliveryline-frontend/src/features/workflows/runContextView.ts` — `presentOrUndefined` `:113-115` (the null→undefined coalesce idiom); pure-`.ts` mapper convention.
  - `deliveryline-frontend/src/features/workflows/components/ProviderLimitStatus.tsx` — the **"not reported"** precedent: `windowDisplay` `:68-84`, `WindowRow` hasData/else `:143-151`, `usagePercent` `'n/a'` `:86-94`, `signalSignifier` chip `:33-50`, `!= null` guards throughout. **This is the component to mirror** for the panel + null posture.
  - `deliveryline-frontend/src/features/workflows/components/StepExecutionLogViewer.tsx` — the existing (log-only) step surface `:30-170`; do NOT extend it with tokens (it is SSE/latest-only). The new panel is separate.
  - Mount point `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` — `<Stack>` `:194-327`, self-hiding data-fed panels `:205/:209`, gated `ProviderLimitStatus` `:213`, `StepExecutionLogViewer` `:319`; `useWorkflowDetail` `data` `:139`.
  - Tests: `ProviderLimitStatus.test.tsx` (MSW + `QueryClientProvider` + `expectNoA11yViolations` `:10-36,159-164`); axe helper `@/test/a11y/axe` `:46-52`.

### Anti-patterns to avoid (disaster prevention)

- **Do NOT** render `0` or a blank for an unreported count — always the textual "Not reported" (AC1/AC3, the 3d-7 posture). `null` ≠ `0`.
- **Do NOT** add a new `AllowedAction`/`WorkflowState`/`WorkflowEventType`/`DomainErrorCode` — the epic's locked light footprint. The `/steps` endpoint is read-only + prefix-validated only (mirror reviewer-verdict), NOT matrix-gated.
- **Do NOT** synthesize the run total from input+output, and do NOT synthesize a FE total from the per-step rows — sum the persisted per-step `totalTokens` in the BACKEND only (AC2); the FE renders the backend value.
- **Do NOT** fold the per-step list into `WorkflowDetail` — keep it a separate `/steps` endpoint (a detail-embedded steps array bloats the detail DTO + its committed snapshot and couples two concerns). Only `totalTokens` (a scalar) goes on `WorkflowDetail`.
- **Do NOT** extend `StepExecutionLogViewer` — it streams the latest execution's logs over SSE and enumerates no steps. The token panel is a new REST-backed component.
- **Do NOT** guard wire fields with `=== undefined` — they arrive as JSON `null` (`number | null`). Use `!= null`, coalesce `?? undefined` at the view-model seam (the `workflowdetail-wire-sends-null-not-undefined` trap).
- **Do NOT** export a non-component function from a `.tsx` — mappers/formatters (`formatTokenCount`, `toStepTokenRows`) go in `stepTokensView.ts` (the `frontend-react-refresh-no-fn-exports` rule, max-warnings=0).
- **Do NOT** forget the regen ordering — regenerate `openapi.json` (backend snapshot write) AND `schema.d.ts` (`generate-api`) BEFORE the FE legs, or `check:api`/tsc red on the missing fields (the `openapi-regen → FE-client-drift` cascade). This story OWNS the regen (unlike 3g-2).
- **Do NOT** name the Testcontainers steps/rollup test `*Test` — `*IT` only (the `springboot-testcontainers-test-must-be-IT` trap). Seed `completed` runner-execution rows with the completion/correlation columns `ck_runner_executions_completed_correlation` demands (the 3g-3 IT trap).

### Testing standards summary

- Backend: JUnit 5 + Mockito + AssertJ (unit for the rollup helper + `getStepExecutions`); Testcontainers `*IT`/contract for the endpoint + detail `totalTokens`. `application.*` JaCoCo ≥80%. `spotless:apply` before pushing Java; ArchUnit runs in Failsafe (verify tier, not `mvnw test`). Direct failsafe goals crash on `@{argLine}` — use the `verify` lifecycle + `-Djacoco.skip=true` (the `maven-argline-direct-goal-crash` note).
- FE: Vitest + RTL; MSW to stub `/steps` (a normal REST read — NOT SSE, so MSW works, unlike `StepExecutionLogViewer`); a11y via `expectNoA11yViolations`. Regen on the canonical platform (`openapi-regen-platform-shim`, `frontend-lockfile-cross-platform`).
- Verify CI-affecting changes in a clean env / WSL2 Linux where the Docker-backed ITs + the OpenAPI snapshot matter (local green ≠ CI green).

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback (backend). No `System.out`/`printStackTrace`. FE: field-only structured `console`.
- **Where to log:** `WorkflowInspectionService.getStepExecutions` → `INFO` entry + `INFO` success with `count=`; `getStatus` → extend the existing success line with `totalTokensPresent={}`; the `/steps` controller → `INFO` received + success with `count=` (`MdcKeys.sanitizeForLog(workflowRunId)`); `WARN` only on the rollup overflow-clamp anomaly.
- **Context keys:** `workflowRunId` (already MDC-scoped in both service methods). No new MDC key.
- **Forbidden:** secrets/PII/raw payloads. Token counts are non-secret numeric data — safe to log counts and step counts.
- **Test contract:** pin the `/steps` `count=` INFO and the `getStatus` `totalTokensPresent=` line via `OutputCaptureExtension`/list-appender.

### Project Structure Notes

- Backend: service read + projection in `application.workflow` (`WorkflowInspectionService` + `StepExecutionView`); REST DTO + endpoint in `adapters.rest` (`StepExecutionResponse`, `WorkflowController`, `WorkflowDetailResponse`). No new module/package/dependency. No new migration (3g-3's V31 columns supply the data). No new `AllowedAction`/state/event/error-code — the drift-tested additions are the OpenAPI snapshot (new path + schema + `totalTokens`) only.
- FE: sibling `.ts` mapper `stepTokensView.ts` joins `runContextView.ts`/`prLinkageView.ts`; component `RunStepTokensPanel.tsx` joins `ProviderLimitStatus.tsx`; query hook in `lib/api`. Tests co-located in `__tests__/`.
- This closes Epic 3g (FR73 via 3g-1/3g-2, FR74 via 3g-3/3g-4).

### References

- [Source: _bmad-output/planning-artifacts/epic-03g-provenance-token-accounting.md#Story 3g-4: FE — Per-Step Tokens + Run-Level Rollup]
- [Source: _bmad-output/planning-artifacts/epic-03g-provenance-token-accounting.md#Cross-Cutting Notes] (token posture locked: per-step + run-level rollup, tokens-only, `null`→"not reported" never `0`; light foundation-gate footprint — no new state/action/event/error-code; the second OpenAPI regen point is `WorkflowDetail.totalTokens`)
- [Source: _bmad-output/planning-artifacts/prd.md#FR74] (agent token consumption of *each* workflow step execution, with a run-level total)
- [Source: 3g-3-runner-token-usage-capture.md] (backend dependency, status `done`; delivered the V31 `input_tokens`/`output_tokens`/`total_tokens` columns + `RunnerExecutionSnapshot.inputTokens()/outputTokens()/totalTokens()`)
- Precedent stories: 3d-7 (`ProviderLimitStatus` "not exposed"/"not reported" null posture + read-only per-run projection endpoint); 3d-2 (`reviewer-verdict` read-only GET endpoint template + `application.workflow` projection so REST stays thin); 3g-1 (additive nullable read-model widening + OpenAPI regen point; the summary exact-field trap is summary-only); 3g-2 (FE sibling-`.ts` mapper + self-hiding panel + `!= null` wire discipline — but that story consumed a pre-committed regen; THIS story owns its regen).
- Seams: `WorkflowInspectionService.java:1232-1323,2135-2146,2453-2527,2530`; `RunnerExecutionRecordPort.java:23-24`; `RunnerExecutionSnapshot.java:50-52`; `WorkflowDetailResponse.java:20-111,135-158`; `WorkflowController.java:626-667`; `OpenApiSnapshotContractTest.java:30-58`; `WorkflowReadEndpointsContractTest.java:185-232`; `schema.d.ts:1572-1633`; `ProviderLimitStatus.tsx:33-94,143-151`; `runContextView.ts:113-115`; `routes/workflows/$workflowRunId/index.tsx:139,194-327`.
- Traps: `openapi-regen-frontend-client-drift-cascade`; `openapi-regen-platform-shim`; `frontend-lockfile-cross-platform`; `workflowdetail-wire-sends-null-not-undefined`; `frontend-react-refresh-no-fn-exports`; `livesnnouncement-defers-one-commit-test-flake`; `springboot-testcontainers-test-must-be-IT`; `maven-argline-direct-goal-crash`; `workflow-summary-exact-field-contract-test` (summary-only — detail is safe).

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context) — claude-opus-4-8[1m]

### Debug Log References

- Backend compile (`mvnw -pl deliveryline-backend -am compile`) — green.
- Backend Surefire unit tier (`mvnw -pl deliveryline-backend test -Djacoco.skip=true`) — **1477 tests, 0 failures, 0 errors, 12 skipped** (includes the new `WorkflowInspectionServiceStepTokensTest` + the CLI `WorkflowStatusView`-building tests, unaffected by the appended field).
- OpenAPI snapshot regenerated via `OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true` (write-then-fail-by-design), then re-run without the flag → **green** (snapshot now carries `/steps`, `StepExecution`, `WorkflowDetail.totalTokens`, `StepExecution.totalTokens`; +121 lines).
- Backend contract tier (`verify -Dit.test=OpenApiSnapshotContractTest,WorkflowReadEndpointsContractTest`) — **BUILD SUCCESS**.
- Backend ArchUnit architecture tier — **74 tests, 0 failures** (`AllowedActionRegistryPinTest` green → confirms NO new AllowedAction leaked).
- FE `generate-api` → `schema.d.ts` regenerated; `check:api` in-sync; `lint` 0 warnings; `build` (routes+tsc+vite) exit 0; `test` **118 files / 1264 tests, 0 fail** (+11 new).
- ENV NOTE: the FE `node_modules` was in a partial-extraction state (missing `@tanstack/router-generator` dist, then `vitest`). Repaired with `npm ci` (clean reinstall from lockfile); lockfile unchanged. All FE gates re-verified green afterward.

### Completion Notes List

Delivered the full FR74 display half in three legs (per the story's SCOPE DISCOVERY — the per-step list surface did not exist):

1. **Backend per-step read leg** — `WorkflowInspectionService.getStepExecutions(runId)` + `StepExecutionView` projection (oldest-first sort in-service; the port is unordered); read-only, prefix-validated, MDC-scoped, INFO entry/exit with `count=`. New read-only `GET /api/v1/workflows/{id}/steps` on `WorkflowController` (modeled on `getReviewerVerdict`; direct JSON array, no envelope) + `StepExecutionResponse` DTO (UTC-normalized `createdAt`, nullable tokens verbatim). **No new AllowedAction/state/event/error-code.**
2. **Backend rollup leg** — package-private `rollupTotalTokens(rows)` sums the NON-NULL per-step `totalTokens`, returns `null` when none reported (never `0`), and clamps int32 overflow at `Integer.MAX_VALUE` (WARN once). Appended nullable `Integer totalTokens` at the END of `WorkflowStatusView` (+ trailing `null` in the 16-arg back-compat ctor) → `WorkflowDetailResponse`. `getStatus` success log extended with `totalTokensPresent={}`.
3. **FE leg** — new `useStepExecutions` hook (normal JSON read, not SSE) + `stepExecutions` query key; sibling `.ts` mapper `stepTokensView.ts` (`toStepTokenRows` coalescing null→undefined but PRESERVING a reported `0`; `formatTokenCount` → number or literal "Not reported"); self-hiding `RunStepTokensPanel.tsx` (per-step Input/Output/Total cells + a header "Run total" line consuming the backend-computed `WorkflowDetail.totalTokens`, guarded `!= null`, NEVER re-summed on the FE). Mounted un-gated (self-hides on empty) after `ProviderLimitStatus` in the detail route.

Token posture (3d-7 parity) honored everywhere: `null`/absent → textual "Not reported", never `0`, never blank, never color-only. Logging contract pinned: the `/steps` `count=3` INFO and the `getStatus` `totalTokensPresent=true` INFO are asserted in `WorkflowReadEndpointsContractTest` via `OutputCaptureExtension`.

No announcer added (AC4 marks it optional). No estimated $ cost (AC3 tokens-only, locked).

### File List

**Backend — modified**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — `ALL_RUNNER_EXECUTION_STATUSES` const; `getStepExecutions` + `toStepExecutionView` + `StepExecutionView` record; `rollupTotalTokens` helper; `getStatus` rollup + `totalTokens` on `WorkflowStatusView` (+16-arg ctor tail) + extended success log.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` — `listStepExecutions` GET `/{id}/steps` endpoint.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowDetailResponse.java` — nullable `totalTokens` field + `from(...)` wiring.
- `deliveryline-backend/src/main/resources/openapi/openapi.json` — regenerated snapshot (new path + schema + field).

**Backend — new**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/StepExecutionResponse.java` — `StepExecution` REST DTO.
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceStepTokensTest.java` — unit coverage (getStepExecutions mapping/ordering/guards + rollup sum/null/skip/overflow).

**Backend — modified (test)**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/WorkflowReadEndpointsContractTest.java` — detail `totalTokens` (summed + null), `/steps` shape/ordering/nullability + RUN_NOT_FOUND, INFO log pins; runner-execution seed helpers.

**Frontend — modified**
- `deliveryline-frontend/src/lib/api/schema.d.ts` — regenerated from the snapshot.
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts` — `stepExecutions` key.
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` — mounted `RunStepTokensPanel`.

**Frontend — new**
- `deliveryline-frontend/src/features/workflows/hooks/useStepExecutions.ts` — `/steps` read hook.
- `deliveryline-frontend/src/features/workflows/stepTokensView.ts` — sibling `.ts` mapper + `formatTokenCount`.
- `deliveryline-frontend/src/features/workflows/components/RunStepTokensPanel.tsx` — per-step token panel + run total.
- `deliveryline-frontend/src/features/workflows/stepTokensView.test.ts` — mapper/formatter Vitest.
- `deliveryline-frontend/src/features/workflows/components/RunStepTokensPanel.test.tsx` — panel Vitest (render/not-reported/self-hide/axe).

## Change Log

| Date | Version | Description | Author |
| ---- | ------- | ----------- | ------ |
| 2026-07-02 | 0.2 | Implemented full FR74 display half (3 legs). Backend: net-new read-only `GET /{id}/steps` (`getStepExecutions` + `StepExecutionView` + `StepExecutionResponse`) exposing 3g-3's per-execution token columns oldest-first; run-level nullable `totalTokens` rollup (`rollupTotalTokens`, non-null sum, null-when-none, int32-clamp) on `WorkflowStatusView`→`WorkflowDetailResponse`; NO new AllowedAction/state/event/error-code. OpenAPI snapshot + FE `schema.d.ts` regenerated (this story owns the regen). FE: `useStepExecutions` hook + sibling `stepTokensView.ts` mapper/`formatTokenCount` + self-hiding `RunStepTokensPanel` (per-step cells + run total), 3d-7 "Not reported" null posture, mounted in the detail route. Verified: backend Surefire 1477/0, contract (OpenApiSnapshot+WorkflowReadEndpoints) green, ArchUnit 74/0, spotless; FE check:api in-sync, lint 0-warn, build exit 0, Vitest 1264/0. (Repaired a partial FE node_modules via `npm ci` — lockfile unchanged.) Status → review. | Amelia (Opus 4.8 [1m]) |
| 2026-07-02 | 0.1 | Drafted FR74 display story. SCOPE DISCOVERY: the per-step view AC1 assumed does not exist — 3d-5 shipped a latest-execution log *stream*, not a step list. Story delivers full FR74 in three legs: (1) net-new read-only `GET /{id}/steps` endpoint + `StepExecutionView`/`StepExecutionResponse` projecting 3g-3's token columns; (2) run-level nullable `totalTokens` rollup (sum of non-null per-step totals) on `WorkflowDetail`; (3) FE per-step token panel + run-total, both with the 3d-7 "not reported" null posture. This story OWNS the OpenAPI + `schema.d.ts` regen. No new state/action/event/error-code (light foundation-gate footprint). Split option (rollup-now / per-step-later) flagged but not taken (no user response; full FR74 delivered to avoid under-delivery). Status → ready-for-dev. | Bob (Opus 4.8) |
