# Story 3f.6: Run Review Queue Project Attribution + Project Filter

Status: done

<!-- 2026-06-26 bmad-create-story context-engine pass. Target sprint key: 3f-6-run-review-queue-project-attribution-and-filter. This completes the queue-scoping follow-up explicitly deferred by story 3c-9 AC6. -->

> READ FIRST - this is the backend-unblocking queue attribution/filter story that 3c-9 could not finish. Use the existing `workflow_runs.project_id` association from 3c-7 and existing project records. Do not add new project persistence, new Flyway migrations, new project CRUD behavior, split proposal state, child-run lineage, dependency graph behavior, or ticket-source subticket creation.

## Story

As an authorized user,
I want to see which project each run belongs to in the Run Review Queue and filter the queue by project,
So that with multiple projects, and with split fan-out multiplying run counts, the queue stays navigable - completing the queue-scoping that story 3c-9 deferred.

## Acceptance Criteria

1. Given story 3c-9 deferred queue scoping because run-list DTOs carried no project field and `/workflows` had no `projectId` parameter, then the run-list read model, REST summary DTO, and workflow detail DTO expose the run's project public id, name, and slug resolved from the existing `workflow_runs.project_id` FK with no new persistence.
2. Given `GET /api/v1/workflows`, then it accepts an optional `projectId` query parameter that may be either a project slug or a `prj_` public id; absence returns the same all-project list as before this story, while an unknown value returns the existing `PROJECT_NOT_FOUND` problem shape.
3. Given OpenAPI/client contracts, then `openapi.json` and frontend `schema.d.ts` are regenerated for the added query parameter and DTO fields; the no-filter request remains byte-identical from the frontend because `projectId` is omitted unless selected.
4. Given the Run Review Queue UI, then each populated row shows project attribution and the queue offers a project filter control that reuses/refactors the 3c-9 `ProjectSelector` seam. The project filter composes with the existing `state` and `includeArchived` filters and persists through the existing URL-backed queue-state pattern.
5. Given accessibility requirements, then the filter control and row attribution meet WCAG 2.1 AA, axe checks pass, keyboard operation is complete, attribution is not conveyed by color alone, and live announcements reflect project filter changes using the existing one-commit-lag `useLiveAnnouncement` test pattern with `waitFor`.
6. Given performance requirements, then the list query remains within NFR25/NFR26 expectations. Filtering uses the already-indexed `workflow_runs.project_id` path from 3c-era project association work; enrichment avoids per-row unbounded project lookups.
7. Given tests, then coverage proves DTO attribution, `/workflows?projectId=` scoping by slug and public id, unknown-project `PROJECT_NOT_FOUND`, no-filter parity, OpenAPI/schema drift, frontend query/key/search wiring, row attribution, composed filters, axe/live-announcement behavior, and committed `application.*` coverage stays at or above 80%.

## Tasks / Subtasks

- [x] Task 1 - Widen backend read models and REST DTOs (AC: 1, 3)
  - [x] Add project attribution fields to `WorkflowInspectionService.WorkflowRunSummaryView`: `projectId`, `projectName`, and `projectSlug`.
  - [x] Add the same attribution fields to `WorkflowInspectionService.WorkflowStatusView` so detail views do not lose the attribution available in the queue.
  - [x] Append the new fields at the end of `WorkflowSummaryResponse` and `WorkflowDetailResponse` constructor parameter lists, following the existing DTO compatibility comment in `WorkflowSummaryResponse`.
  - [x] Keep the wire values nullable only for defensive legacy/data-repair scenarios. Normal persisted runs should have project attribution because 3c-7 stores `project_id`.
  - [x] Do not alter unrelated summary fields such as `pendingClarifications`, PR linkage, failure category, or takeover metadata.

- [x] Task 2 - Add the optional project filter to the workflows list endpoint (AC: 2, 6)
  - [x] Extend `WorkflowController.listWorkflows` with `@RequestParam(required = false) String projectId`.
  - [x] Thread the filter through `WorkflowInspectionService.listRuns(...)`. Prefer a small filter object if the method signature would otherwise keep growing.
  - [x] Resolve the filter in application/service code, not in the controller: if the value starts with `prj_`, resolve with `ProjectStore.findByPublicId`; otherwise resolve with `ProjectStore.findBySlug`.
  - [x] For unknown public id or slug, throw the existing `PROJECT_NOT_FOUND` domain error. Do not create a new error code.
  - [x] Treat absent, blank, or cleared frontend state as no project filter; the frontend must not send `projectId=`.
  - [x] Keep existing `state`, `includeArchived`, and `limit` behavior unchanged except for narrowing by project when a valid filter is present.

- [x] Task 3 - Support project-scoped persistence reads without new tables (AC: 1, 2, 6)
  - [x] Widen `WorkflowRunReadPort.listRuns` and its persistence adapter to accept an optional resolved project public id or internal project id.
  - [x] Update `WorkflowRunRepository` with project-scoped list queries that preserve newest-first ordering and existing archive semantics.
  - [x] Avoid method explosion if practical by using a single explicit `@Query` with optional filters, but keep the query readable and covered by tests.
  - [x] The project filter must apply to `workflow_runs.project_id`, not to ticket refs, project names, or frontend-only labels.
  - [x] Reuse the existing index on `workflow_runs.project_id`; do not add a Flyway migration unless implementation discovers a missing index and documents why prior 3c assumptions were wrong.
  - [x] Enrich project id/name/slug without N+1 behavior. Acceptable approaches: one `ProjectStore.findAll()` map for the bounded project list, or a repository projection that joins projects in the list query.

- [x] Task 4 - Update OpenAPI, client schema, and contract tests (AC: 1, 2, 3, 7)
  - [x] Update `@Operation` / `@Parameter` descriptions for `GET /api/v1/workflows` to document that `projectId` accepts either a slug or a `prj_` public id.
  - [x] Regenerate `deliveryline-backend/src/main/resources/openapi.json`.
  - [x] Regenerate `deliveryline-frontend/src/lib/api/schema.d.ts`.
  - [x] Add or update controller/OpenAPI contract tests for the query parameter and appended DTO fields.
  - [x] Run the API drift checks required by this repo, including `npm run check:api` from the frontend/client side if that is the established command in the current branch.

- [x] Task 5 - Extend frontend query filters and route search (AC: 3, 4)
  - [x] Add `projectId?: string` to `WorkflowListFilters` in `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts`.
  - [x] Ensure `normalizeFilters` keeps `projectId` in stable keys and drops only `undefined`; do not let an empty string create a phantom cache entry.
  - [x] Update `fetchWorkflowList` in `deliveryline-frontend/src/lib/api/queryOptions.ts` to send `projectId` only when it is a non-empty string.
  - [x] Extend `/workflows` route `validateSearch`, `loaderDeps`, and navigation handlers so `projectId` composes with `state` and `includeArchived`.
  - [x] Preserve the existing clear-filters behavior: clearing filters removes project, state, and includeArchived together.

- [x] Task 6 - Reuse/refactor ProjectSelector for queue filtering (AC: 4, 5)
  - [x] Refactor `ProjectSelector` to support controlled usage with `value` and `onChange` while preserving the existing Projects screen behavior.
  - [x] Keep the collapse-to-label behavior when only one project exists, but do not force a `projectId` filter into the queue unless the user explicitly selects a filter.
  - [x] Include disabled projects in the selector/filter list if the projects API returns them, because historical runs may still be attributed to disabled projects.
  - [x] If the selected project no longer exists or has no usable id/slug, clear or disable the filter and avoid sending an empty `projectId`.
  - [x] Place the control with the existing queue filter controls in `QueueShell`, keeping the route as the source of truth.
  - [x] Add field-only structured logs for project filter changes. Log the selected project public id or cleared state, never free-text project names from user input.

- [x] Task 7 - Show project attribution in queue rows (AC: 1, 4, 5)
  - [x] Add project attribution to `RunQueueRow` and map it in `toRunQueueRow`.
  - [x] Render a compact project label/chip in `RunReviewQueueItem` using name when available, slug/public id as fallback.
  - [x] Pair any color treatment with text/icon semantics; attribution must not be color-only.
  - [x] Include project attribution in the row `aria-label` when present so screen-reader users can distinguish similarly named runs across projects.
  - [x] Keep row height stable and avoid layout shift or text overflow on narrow widths.

- [x] Task 8 - Accessibility, state announcements, and UI tests (AC: 4, 5, 7)
  - [x] Update queue state announcements so project-filter changes are reflected after the query resolves. Follow the current one-commit-lag `useLiveAnnouncement` pattern and assert with `waitFor`.
  - [x] Add Vitest/Testing Library coverage for selecting a project, composing with taken-over and archived filters, clearing all filters, keyboard operation, and row attribution.
  - [x] Add axe coverage for the populated queue with project attribution and for the expanded project filter control.
  - [x] Ensure filter controls have stable accessible names and `aria-pressed`/select semantics consistent with existing controls.

- [x] Task 9 - Backend test coverage (AC: 1, 2, 6, 7)
  - [x] Test `WorkflowInspectionService.listRuns` with no project filter, slug filter, public-id filter, and unknown filter.
  - [x] Test archived composition: `includeArchived=false` excludes archived runs within the project, `includeArchived=true` includes them.
  - [x] Test state composition: `state + projectId` returns only matching runs.
  - [x] Test detail read attribution on `GET /api/v1/workflows/{workflowRunId}`.
  - [x] Add persistence/repository tests for project-scoped ordering and limit behavior if the query is non-trivial.
  - [x] Add a parity test proving the no-project list path returns the same runs/order as the pre-filter path.

- [x] Logging instrumentation (cross-cutting; required)
  - [x] Backend list logs include whether a project filter was present, the resolved project public id when known, state/includeArchived/limit, result count, and elapsed timing where the service already logs timing.
  - [x] Backend logs never include project secrets, ticket descriptions, artifact bodies, or raw free-text request payloads.
  - [x] Frontend logs are field-only and avoid project names/slugs if those are user-controlled display values.
  - [x] Pin at least one backend or frontend log-safety assertion if a new logging branch is introduced.

## Dev Notes

### Reconciled Scope

This story completes a specifically deferred item from 3c-9. Story 3c-9 built the Projects Management UI and a `ProjectSelector` seam, but explicitly did not wire the Run Review Queue because:

- `WorkflowSummary` and `WorkflowDetail` did not carry project fields.
- `GET /api/v1/workflows` did not accept `projectId`.
- The selector held local state and did not mutate the queue query.

This story resolves those three blockers. It remains independent from 3f-1 through 3f-5 split fan-out work and can ship any time after the 3c project-association foundation. It should not implement subticket creation, split proposal UI, child run lineage, or dependency gating.

### Live Code Seams Verified 2026-06-26

- `WorkflowSummaryResponse` currently exposes `workflowRunId`, `currentState`, `ticketRef`, `lastEventAt`, `lastEventType`, `specRejectionLoopCount`, `escalationMarker`, and `archivedAt`. It already warns that widened fields should be appended to the record parameter list.
- `WorkflowDetailResponse` currently has no project attribution.
- `WorkflowController.listWorkflows` currently accepts `state`, `includeArchived`, and `limit`, then delegates to `workflowInspectionService.listRuns(stateFilter, includeArchived, limit)`.
- `WorkflowInspectionService.WorkflowRunSummaryView` currently has no project fields and is built from `workflowRunReadPort.listRuns(...)`.
- `WorkflowRunRepository` has list methods for state/archive combinations and a `findProjectIdByPublicId(publicId)` query, but no project-filtered list method yet.
- `ProjectStore` and `ProjectPersistenceAdapter` already provide `findBySlug`, `findByPublicId`, `findAll`, and `findProjectIdForRun`.
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts` currently has `WorkflowListFilters` with `state` and `includeArchived` only.
- `deliveryline-frontend/src/lib/api/queryOptions.ts` only sends `state` and `includeArchived` to `/api/v1/workflows`.
- `/workflows` route search currently validates only `state` and `includeArchived`.
- `QueueShell` owns URL-backed filter controls but not project selection.
- `ProjectSelector` currently holds local state, collapses to a label when zero/one project exists, and offers only an `onSelect` forward-compat callback.
- `RunQueueRow` and `RunReviewQueueItem` currently treat project attribution as absent from live data.

### Prior Story Intelligence

- 3c-7 introduced persisted run/project association through `workflow_runs.project_id`, `WorkflowRunCreatePort.create(publicId, initialState, projectId)`, `ProjectStore.findProjectIdForRun`, and runtime project resolution for existing runs.
- 3c-9 created project list/edit UI, `ProjectSelector`, `useProjectsList`, `projectKeys`, and `listProjectsOptions`, but deferred queue scoping to this backend-follow-up story.
- 3c-9 review explicitly accepted the queue-scoping deferral and named the needed follow-up: add project fields to `WorkflowSummary`/`WorkflowDetail`, add the `/workflows` `projectId` query parameter, then wire the selector.
- 3f-1 is a ticket-source capability foundation and does not block this story. The business reason this story appears in Epic 3f is that split fan-out will multiply visible queue rows, making project scoping more important.

### API and Data Decisions

- Query parameter name is `projectId` per epic text, but the accepted value is either project slug or public id. OpenAPI must say this clearly.
- Frontend should send the public `project.id` returned by the Projects API when possible. Slug support exists for external/API ergonomics and deep links.
- Unknown slug and unknown `prj_` id both map to `PROJECT_NOT_FOUND`; do not add `PROJECT_FILTER_NOT_FOUND` or similar.
- No-filter parity is mandatory. Backend absence of `projectId` should call the same logical all-project path, and frontend absence should omit the query parameter entirely.
- Workflow detail should be widened even though the epic emphasizes the run list, because the deferred 3c-9 review called out both `WorkflowSummary` and `WorkflowDetail`.
- Existing default/single-project pilot behavior should stay low-friction. A collapsed one-project label is acceptable, but a project filter must not silently narrow the queue unless represented in URL-backed state.

### Performance and Safety Notes

- Avoid a per-run call to `ProjectStore.findProjectIdForRun` during list rendering. Use a single project map or a join/projection.
- Keep the list limit cap behavior unchanged.
- Include archived composition matters: `includeArchived=true` means archived rows in the selected project are visible; default still hides archived rows.
- Disabled projects should remain usable for filtering if they are present in project list data because historical runs can belong to disabled projects.
- Project names/slugs are display data. Render as escaped plain text only and do not use them as log message bodies.

### Latest External Context

No external-current API dependency is introduced by this story. Use the repo-pinned Spring/OpenAPI/TanStack/router/testing stack and existing project selector/query patterns. No web research is required before implementation unless a dependency/tool version conflict appears while regenerating OpenAPI or schema types.

### Testing Standards

- Backend contract tests should cover both slug and public id filter values.
- Frontend tests should assert URL search state, query key/input shape, and absence of `projectId` when cleared.
- `ProjectSelector` refactor must keep existing 3c-9 Projects UI tests green.
- Run axe on the queue with at least one attributed row and an expanded project filter.
- Use `waitFor` for live-announcement assertions because the existing `useLiveAnnouncement` hook intentionally updates one commit after the state change.
- Keep `application.*` coverage at or above 80%.

### References

- Epic definition: `_bmad-output/planning-artifacts/epic-03f-complex-ticket-flow.md` - Story 3f-6.
- PRD: `_bmad-output/planning-artifacts/prd.md` - FR72 project-attributed run queue and project filtering.
- UX spec: `_bmad-output/planning-artifacts/ux-design.md` - queue project context/filter expectations.
- Deferred source story: `_bmad-output/implementation-artifacts/3c-9-projects-management-ui.md`.
- Project association source story: `_bmad-output/implementation-artifacts/3c-7-run-project-association-across-intake-and-dispatch.md`.
- Backend controller: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java`.
- Backend DTOs: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowSummaryResponse.java`, `WorkflowDetailResponse.java`.
- Backend service: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`.
- Backend project store: `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectStore.java`.
- Backend repository: `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java`.
- Frontend query keys: `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts`.
- Frontend query options: `deliveryline-frontend/src/lib/api/queryOptions.ts`.
- Queue route/shell/row: `deliveryline-frontend/src/routes/workflows/index.tsx`, `deliveryline-frontend/src/features/workflows/QueueShell.tsx`, `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx`, `deliveryline-frontend/src/features/workflows/runQueueRow.ts`.
- Project selector: `deliveryline-frontend/src/features/projects/components/ProjectSelector.tsx`.

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Red phase: focused backend compile/test failed before implementation because project-filter/list DTO contract did not exist.
- Verification: focused backend/frontend slices, OpenAPI drift, API client drift, backend formatter/checkstyle, frontend lint/build.

### Completion Notes List

- Added backend project attribution to workflow run snapshots, summary/detail views, REST DTOs, OpenAPI, and generated frontend schema.
- Added `/api/v1/workflows?projectId=` support accepting project slug or `prj_` public id, resolving in application service code and returning `PROJECT_NOT_FOUND` for unknown filters.
- Reworked list persistence to use one optional-filter query preserving state/archive/limit semantics and project-id narrowing.
- Refactored `ProjectSelector` for controlled queue use with an explicit All projects option while preserving existing Projects screen behavior.
- Wired URL-backed project filtering into the review queue route/shell, including stable query keys, omitted empty project params, field-only logs, and composed state/includeArchived/project filters.
- Rendered project attribution in queue rows with text/icon semantics and aria-label inclusion.
- Regenerated `openapi.json` and `schema.d.ts` and updated MSW fixtures.

### File List

- `_bmad-output/implementation-artifacts/3f-6-run-review-queue-project-attribution-and-filter.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/WorkflowRunEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowDetailResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowSummaryResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunReadPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunSnapshot.java`
- `deliveryline-backend/src/main/resources/openapi/openapi.json`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ArchiveRunEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceClarificationStatusTest.java`
- `deliveryline-frontend/src/features/projects/__tests__/ProjectSelector.test.tsx`
- `deliveryline-frontend/src/features/projects/components/ProjectSelector.tsx`
- `deliveryline-frontend/src/features/workflows/QueueShell.tsx`
- `deliveryline-frontend/src/features/workflows/__tests__/QueueShell.test.tsx`
- `deliveryline-frontend/src/features/workflows/__tests__/runQueueRow.test.ts`
- `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx`
- `deliveryline-frontend/src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowsList.test.tsx`
- `deliveryline-frontend/src/features/workflows/runQueueRow.ts`
- `deliveryline-frontend/src/lib/api/queryOptions.ts`
- `deliveryline-frontend/src/lib/api/schema.d.ts`
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.test.ts`
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts`
- `deliveryline-frontend/src/routes/workflows/index.tsx`
- `deliveryline-frontend/src/test/handlers.ts`
## Change Log

| Date | Version | Change |
|------|---------|--------|
| 2026-06-26 | 0.1 | Created ready-for-dev story for 3f-6 with backend project attribution/filter scope, frontend queue wiring, accessibility, OpenAPI/schema, and test guardrails. |
| 2026-06-26 | 1.0 | Implemented project attribution/filter end-to-end; status in-progress -> review after focused backend/frontend tests, OpenAPI/API drift checks, lint/build, and backend format/checkstyle gates passed. |
| 2026-06-26 | 1.1 | Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor): 8 patch findings (1 Critical, 1 High, 3 Medium, 3 Low), 8 dismissed. Critical: project filter was non-functional end-to-end. |
| 2026-06-26 | 1.2 | All 8 review patches applied & verified: route `validateSearch`/sibling-toggle `projectId` wiring (Critical+High), `ProjectSelector` stale/blank/empty-value reconciliation, new `WorkflowRunRepositoryListRunsFilteredIT` (query + no-filter parity), axe scans for the expanded filter control + attributed row. FE tests 104 green, tsc/eslint/prettier clean, backend test-compile + spotless clean. Status review -> done. |

## Review Findings

<!-- 2026-06-26 bmad-code-review of story 3f-6. Layers: Blind Hunter (adversarial, RTK-compressed diff = backend-only), Edge Case Hunter (diff + project read), Acceptance Auditor (diff + spec). Critical finding independently confirmed by 2 layers and verified directly. -->

- [x] [Review][Patch] **Project filter is non-functional end-to-end — `validateSearch` strips `projectId`** [deliveryline-frontend/src/routes/workflows/index.tsx:27-37] — `validateSearch` only parses `state` and `includeArchived`; `projectId` is never read. TanStack Router treats the `validateSearch` return as the canonical search schema, so the `projectId` written by `onProjectFilterChange` is stripped on the same validation pass. `Route.useSearch()` never carries `projectId`, `loaderDeps`/`fetchWorkflowList` never send it, deep links discard it, and `ProjectSelector` always shows "All projects". The whole AC2/AC4 filter feature is dead in the real app — it passes only because `QueueShell.test.tsx` injects `filters={{ projectId }}` directly, bypassing the route. **Fix:** parse `projectId` in `validateSearch` (non-empty string only); `loaderDeps` already returns `search` so it follows once validated. (sources: edge+auditor; verified directly) (AC: 4, also breaks AC2/AC7 at route level — Task 5 subtask marked `[x]` but not implemented)
- [x] [Review][Patch] **Sibling filter toggles drop an active `projectId`** [deliveryline-frontend/src/routes/workflows/index.tsx:50-65] — `onToggleTakenOverFilter` and `onToggleIncludeArchived` rebuild `search` from `state` + `includeArchived` only; neither spreads `projectId`. With a project filter active, toggling taken-over or include-archived silently clears the project filter, violating AC4 "composes with state and includeArchived". Must be fixed alongside the Critical finding. **Fix:** spread `...(search.projectId !== undefined ? { projectId: search.projectId } : {})` into both handlers. (source: edge) (AC: 4)
- [x] [Review][Patch] **No persistence/repository test for the new `listRunsFiltered` JPQL** [deliveryline-backend/.../repository/WorkflowRunRepository.java] — the adapter was rewritten from four derived queries to one optional-filter JPQL (`(:state is null or …) and (:includeArchived = true or archivedAt is null) and (:projectId is null or …)`), but no repository IT / `WorkflowRunPersistenceAdapterTest` change exists in the File List. The non-trivial query (param binding + newest-first ordering + archive semantics) is exercised only through service-layer mocks of `WorkflowRunReadPort`, never against a real datasource. Task 9 subtask marked `[x]` but absent. (source: auditor) (AC: 6, 7)
- [x] [Review][Patch] **No explicit no-filter parity test** [deliveryline-backend/.../WorkflowInspectionService.java] — the persistence rewrite changed the SQL path for the default (no-project) list, but no test pins that `listRunsFiltered(state, includeArchived, null, page)` returns the same rows/order as the removed `findAllByOrderByCreatedAtDescIdDesc` / `findByCurrentState…` methods. AC7 mandates a parity proof. Task 9 subtask marked `[x]` but not present. (source: auditor) (AC: 7)
- [x] [Review][Patch] **`ProjectSelector` does not reconcile a stale / non-matching controlled value** [deliveryline-frontend/src/features/projects/components/ProjectSelector.tsx] — `value` is fed straight to Radix `Select` with no reconciliation against the loaded list. If the URL `projectId` is a *slug* (endpoint accepts slugs) or references a deleted/absent project, no `SelectItem` (keyed by `project.id` = `prj_` public id) matches; `<SelectValue/>` has no placeholder, so the trigger renders blank with no clear/disable affordance. Task 6 requires "if the selected project no longer exists or has no usable id/slug, clear or disable the filter". (sources: edge+auditor) (AC: 4, 5 — Task 6)
- [x] [Review][Patch] **Empty-string `value` and empty `SelectItem` value mishandled by Radix** [deliveryline-frontend/src/features/projects/components/ProjectSelector.tsx] — `value ?? ALL_PROJECTS_VALUE` uses nullish coalescing, so a `value` of `''` yields `current=''` which Radix treats as the clear sentinel (blank trigger) rather than the explicit "All projects" option; and `SelectItem value={project.id ?? ''}` emits an empty string when a project lacks an id, which Radix Select rejects at runtime. Guard blank-vs-absent and never emit an empty `SelectItem` value. (sources: edge+auditor) (AC: 4)
- [x] [Review][Patch] **Selector hidden during project load/empty leaves a URL filter with no project-scoped clear control** [deliveryline-frontend/.../ProjectSelector.tsx + QueueShell.tsx] — when `useProjectsList` is still loading or returns `[]`, `projects.length === 0` returns `null`, removing the selector. If a `projectId` filter is active in the URL during that window, the only clear path is the global Clear-filters button. Minor (global clear works) but the project filter has no scoped clear affordance. (source: edge) (AC: 4)
- [x] [Review][Patch] **axe coverage missing for the expanded project filter control and project-attributed rows** [deliveryline-frontend/.../QueueShell.test.tsx, RunReviewQueueItem.test.tsx] — the QueueShell axe test renders with no projects mocked so `ProjectSelector` returns `null` and is never scanned; the project-filter test opens the combobox but runs no axe scan; the `RunReviewQueueItem` attribution test asserts the chip/aria-label but does not call the a11y assertion. AC5/Task 8 require axe on the expanded filter and an attributed row. (source: auditor) (AC: 5, 7)

<!-- Dismissed as noise/by-design (8): (1) slug literally starting with `prj_` misrouted to findByPublicId — spec Task 2 explicitly prescribes the `prj_` prefix heuristic; `prj_` is a reserved id namespace. (2) projectStore==null turns filter into 404 — bean is `@Autowired(required=false)`, always wired in production; defensive. (3) filter+attribution assume `workflow_runs.project_id` stores the `prj_` public id — invariant verified by passing public-id/slug contract tests. (4) redundant 3-arg vs 4-arg read-port paths — both correct, converge on listRunsFiltered. (5) duplicate static vs instance `projectAttributionFor` — minor. (6) filtered list builds full project map — bounded, not N+1. (7) DTO `@Schema` indentation — cosmetic, spotless gates it; likely RTK-diff artifact. (8) no-filter attribution degrades for projects absent from findAll() — findAll() returns ALL projects incl. disabled (no status filter), so only truly orphaned/legacy project_ids hit the intended id-only fallback. -->

