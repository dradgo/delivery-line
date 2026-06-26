# Story 3f.6: Run Review Queue Project Attribution + Project Filter

Status: ready-for-dev

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

- [ ] Task 1 - Widen backend read models and REST DTOs (AC: 1, 3)
  - [ ] Add project attribution fields to `WorkflowInspectionService.WorkflowRunSummaryView`: `projectId`, `projectName`, and `projectSlug`.
  - [ ] Add the same attribution fields to `WorkflowInspectionService.WorkflowStatusView` so detail views do not lose the attribution available in the queue.
  - [ ] Append the new fields at the end of `WorkflowSummaryResponse` and `WorkflowDetailResponse` constructor parameter lists, following the existing DTO compatibility comment in `WorkflowSummaryResponse`.
  - [ ] Keep the wire values nullable only for defensive legacy/data-repair scenarios. Normal persisted runs should have project attribution because 3c-7 stores `project_id`.
  - [ ] Do not alter unrelated summary fields such as `pendingClarifications`, PR linkage, failure category, or takeover metadata.

- [ ] Task 2 - Add the optional project filter to the workflows list endpoint (AC: 2, 6)
  - [ ] Extend `WorkflowController.listWorkflows` with `@RequestParam(required = false) String projectId`.
  - [ ] Thread the filter through `WorkflowInspectionService.listRuns(...)`. Prefer a small filter object if the method signature would otherwise keep growing.
  - [ ] Resolve the filter in application/service code, not in the controller: if the value starts with `prj_`, resolve with `ProjectStore.findByPublicId`; otherwise resolve with `ProjectStore.findBySlug`.
  - [ ] For unknown public id or slug, throw the existing `PROJECT_NOT_FOUND` domain error. Do not create a new error code.
  - [ ] Treat absent, blank, or cleared frontend state as no project filter; the frontend must not send `projectId=`.
  - [ ] Keep existing `state`, `includeArchived`, and `limit` behavior unchanged except for narrowing by project when a valid filter is present.

- [ ] Task 3 - Support project-scoped persistence reads without new tables (AC: 1, 2, 6)
  - [ ] Widen `WorkflowRunReadPort.listRuns` and its persistence adapter to accept an optional resolved project public id or internal project id.
  - [ ] Update `WorkflowRunRepository` with project-scoped list queries that preserve newest-first ordering and existing archive semantics.
  - [ ] Avoid method explosion if practical by using a single explicit `@Query` with optional filters, but keep the query readable and covered by tests.
  - [ ] The project filter must apply to `workflow_runs.project_id`, not to ticket refs, project names, or frontend-only labels.
  - [ ] Reuse the existing index on `workflow_runs.project_id`; do not add a Flyway migration unless implementation discovers a missing index and documents why prior 3c assumptions were wrong.
  - [ ] Enrich project id/name/slug without N+1 behavior. Acceptable approaches: one `ProjectStore.findAll()` map for the bounded project list, or a repository projection that joins projects in the list query.

- [ ] Task 4 - Update OpenAPI, client schema, and contract tests (AC: 1, 2, 3, 7)
  - [ ] Update `@Operation` / `@Parameter` descriptions for `GET /api/v1/workflows` to document that `projectId` accepts either a slug or a `prj_` public id.
  - [ ] Regenerate `deliveryline-backend/src/main/resources/openapi.json`.
  - [ ] Regenerate `deliveryline-frontend/src/lib/api/schema.d.ts`.
  - [ ] Add or update controller/OpenAPI contract tests for the query parameter and appended DTO fields.
  - [ ] Run the API drift checks required by this repo, including `npm run check:api` from the frontend/client side if that is the established command in the current branch.

- [ ] Task 5 - Extend frontend query filters and route search (AC: 3, 4)
  - [ ] Add `projectId?: string` to `WorkflowListFilters` in `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts`.
  - [ ] Ensure `normalizeFilters` keeps `projectId` in stable keys and drops only `undefined`; do not let an empty string create a phantom cache entry.
  - [ ] Update `fetchWorkflowList` in `deliveryline-frontend/src/lib/api/queryOptions.ts` to send `projectId` only when it is a non-empty string.
  - [ ] Extend `/workflows` route `validateSearch`, `loaderDeps`, and navigation handlers so `projectId` composes with `state` and `includeArchived`.
  - [ ] Preserve the existing clear-filters behavior: clearing filters removes project, state, and includeArchived together.

- [ ] Task 6 - Reuse/refactor ProjectSelector for queue filtering (AC: 4, 5)
  - [ ] Refactor `ProjectSelector` to support controlled usage with `value` and `onChange` while preserving the existing Projects screen behavior.
  - [ ] Keep the collapse-to-label behavior when only one project exists, but do not force a `projectId` filter into the queue unless the user explicitly selects a filter.
  - [ ] Include disabled projects in the selector/filter list if the projects API returns them, because historical runs may still be attributed to disabled projects.
  - [ ] If the selected project no longer exists or has no usable id/slug, clear or disable the filter and avoid sending an empty `projectId`.
  - [ ] Place the control with the existing queue filter controls in `QueueShell`, keeping the route as the source of truth.
  - [ ] Add field-only structured logs for project filter changes. Log the selected project public id or cleared state, never free-text project names from user input.

- [ ] Task 7 - Show project attribution in queue rows (AC: 1, 4, 5)
  - [ ] Add project attribution to `RunQueueRow` and map it in `toRunQueueRow`.
  - [ ] Render a compact project label/chip in `RunReviewQueueItem` using name when available, slug/public id as fallback.
  - [ ] Pair any color treatment with text/icon semantics; attribution must not be color-only.
  - [ ] Include project attribution in the row `aria-label` when present so screen-reader users can distinguish similarly named runs across projects.
  - [ ] Keep row height stable and avoid layout shift or text overflow on narrow widths.

- [ ] Task 8 - Accessibility, state announcements, and UI tests (AC: 4, 5, 7)
  - [ ] Update queue state announcements so project-filter changes are reflected after the query resolves. Follow the current one-commit-lag `useLiveAnnouncement` pattern and assert with `waitFor`.
  - [ ] Add Vitest/Testing Library coverage for selecting a project, composing with taken-over and archived filters, clearing all filters, keyboard operation, and row attribution.
  - [ ] Add axe coverage for the populated queue with project attribution and for the expanded project filter control.
  - [ ] Ensure filter controls have stable accessible names and `aria-pressed`/select semantics consistent with existing controls.

- [ ] Task 9 - Backend test coverage (AC: 1, 2, 6, 7)
  - [ ] Test `WorkflowInspectionService.listRuns` with no project filter, slug filter, public-id filter, and unknown filter.
  - [ ] Test archived composition: `includeArchived=false` excludes archived runs within the project, `includeArchived=true` includes them.
  - [ ] Test state composition: `state + projectId` returns only matching runs.
  - [ ] Test detail read attribution on `GET /api/v1/workflows/{workflowRunId}`.
  - [ ] Add persistence/repository tests for project-scoped ordering and limit behavior if the query is non-trivial.
  - [ ] Add a parity test proving the no-project list path returns the same runs/order as the pre-filter path.

- [ ] Logging instrumentation (cross-cutting; required)
  - [ ] Backend list logs include whether a project filter was present, the resolved project public id when known, state/includeArchived/limit, result count, and elapsed timing where the service already logs timing.
  - [ ] Backend logs never include project secrets, ticket descriptions, artifact bodies, or raw free-text request payloads.
  - [ ] Frontend logs are field-only and avoid project names/slugs if those are user-controlled display values.
  - [ ] Pin at least one backend or frontend log-safety assertion if a new logging branch is introduced.

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

TBD by dev-story agent.

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Version | Change |
|------|---------|--------|
| 2026-06-26 | 0.1 | Created ready-for-dev story for 3f-6 with backend project attribution/filter scope, frontend queue wiring, accessibility, OpenAPI/schema, and test guardrails. |
