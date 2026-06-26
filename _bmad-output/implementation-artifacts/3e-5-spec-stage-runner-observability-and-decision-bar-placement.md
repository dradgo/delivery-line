# Story 3e.5: Spec-Stage (Investigating) Runner Log & Console Visibility + Decision-Bar-Relative Placement

Status: done

<!-- Added to Epic 3e via sprint-change-proposal-2026-06-24-spec-stage-observability.md (correct-course). Depends on 3d-5 + 3d-6 (the log-viewer + read-only-console substrates, both done). Validation optional: run validate-create-story before dev-story. -->

> **⚠️ READ FIRST — this is a COVERAGE + PLACEMENT fix over the 3d-5 / 3d-6 substrates, NOT a new streaming subsystem.** Story 3d-5 built the live + historical step-log viewer (`view_runner_logs`, the `GET /runner-logs/stream` SSE endpoint, `StepExecutionLogViewer`) and story 3d-6 built the read-only diagnostic console (`open_diagnostic_console`, `GET /diagnostic-console/stream`, `ReadOnlyDiagnosticConsole`). Both deliberately scoped their allowed-action affordances to the **execution** states (3d-5: `Executing`/`Failed`/`Paused`/`WaitingForReview`; 3d-6: `Executing`-only, owner-only). **`Investigating` — the spec-generation stage — was left out**, even though a runner container is live and producing output there exactly as in `Executing`. Net effect (the reported symptom): a run viewed in `Investigating` shows **no console output**. This story (a) widens the allowed-action matrix so both affordances are offered in `Investigating`, mirroring `Executing`, and (b) relocates the log viewer + diagnostic console **below the Decision Bar** in the run-detail route.
>
> **Three reconciliations the live codebase forces (read all three before coding):**
>
> 1. **`Investigating` is a live-container state — the streaming endpoints already work there; only the matrix gate is missing.** `WorkflowTransitionTable` dispatches a spec runner on `INBOX → INVESTIGATING` (story 3a-1), so a container is live during `Investigating` just like `Executing`. The `GET /runner-logs/stream` and `GET /diagnostic-console/stream` endpoints resolve the *latest runner execution* / re-check liveness at attach — they are **stage-agnostic** and already handle the spec runner. The ONLY reason nothing renders is that `WorkflowInspectionService.baseActionMatrix`'s `INVESTIGATING` arm (`WorkflowInspectionService.java:908`) returns only `VIEW_ONLY` (+ `ANSWER_CLARIFICATION`), so the FE never sees `view_runner_logs` / `open_diagnostic_console`. Widen that arm; do **not** touch the endpoints, ports, adapters, or schemas.
> 2. **Mirror the EXECUTING role split exactly — `open_diagnostic_console` stays owner-only.** The `EXECUTING` arm (`:933-952`) offers `VIEW_RUNNER_LOGS` role-agnostically and `OPEN_DIAGNOSTIC_CONSOLE` **only** to `workflow_owner` (the single local operator; input-disabled, live-only re-check at attach — 3d-6 DD-1/DD-3). The `Investigating` arm must replicate that split, and must layer onto **both** of its existing branches — the open-clarification branch (`:914-916`, which also returns `ANSWER_CLARIFICATION`) and the no-open branch (`:917`). `archive_run`/`unarchive_run` continue to be appended by the `computeActionMatrix` wrapper (`:881-898`) for `workflow_owner` — unchanged.
> 3. **Provider-usage status is OUT of scope for this story.** `EXECUTING` also offers `VIEW_PROVIDER_USAGE_STATUS` (3d-7). The reported symptom + the requested change are about **console output** (logs + console). Provider-usage during the spec stage is a separate concern (the spike snapshot is captured post-execution) and is **not** added to `Investigating` here — keep the change tightly scoped to the two console affordances. Note this exclusion in Completion Notes.

## Story

As a workflow owner watching a run during spec generation,
I want the live step-log viewer and the read-only diagnostic console to be available while the run is in `Investigating`, rendered below the action buttons,
So that I can see the spec runner's console output as it works (today the page shows nothing) and find the output in a consistent place beneath the decision controls.

## Acceptance Criteria

> Reconciled against the live codebase: 3d-5/3d-6 built the substrates but offered the affordances only in execution states. The ACs below extend matrix coverage to `Investigating` and relocate the FE surfaces; rationale in Dev Notes (R1–R5).

1. **Given** the allowed-action matrix, **When** a run is in `Investigating`, **Then** `WorkflowInspectionService.baseActionMatrix` offers `VIEW_RUNNER_LOGS` for **every** actor role and `OPEN_DIAGNOSTIC_CONSOLE` for `workflow_owner` only — mirroring the `EXECUTING` arm — on **both** the open-clarification branch (alongside the existing `VIEW_ONLY` + `ANSWER_CLARIFICATION`) and the no-open-clarification branch (alongside `VIEW_ONLY`).

2. **Given** the matrix change, **Then** the resulting `Investigating` action sets are exactly: `product_reviewer`, no open clarification → `{view_only, view_runner_logs}`; `product_reviewer`, open clarification → `{view_only, answer_clarification, view_runner_logs}`; `workflow_owner`, no open clarification → `{view_only, view_runner_logs, open_diagnostic_console, archive_run}` (archive appended by the wrapper); `workflow_owner`, open clarification → `{view_only, answer_clarification, view_runner_logs, open_diagnostic_console, archive_run}`. (`unarchive_run` replaces `archive_run` when the run is already archived — orthogonal, unchanged.)

3. **Given** a live spec-generation run, **When** the workflow owner opens its detail page, **Then** the `StepExecutionLogViewer` streams the spec runner's logs (live-follow via the stage-agnostic `GET /runner-logs/stream`) and the `ReadOnlyDiagnosticConsole` attaches to the live spec container (`GET /diagnostic-console/stream`, input disabled, liveness re-checked at attach) — with **no** change to the endpoints, ports, adapters, or `runner-result`/OpenAPI schemas.

4. **Given** the run-detail route (`/workflows/$workflowRunId`), **Then** the `StepExecutionLogViewer` and `ReadOnlyDiagnosticConsole` render **below** the `WorkflowDecisionBar` (the action buttons) rather than near the top of the page; their gating (`canViewRunnerLogs` from role-agnostic `useAllowedActions`; `canOpenDiagnosticConsole` from the `workflow_owner`-scoped action set) is unchanged — only their DOM position moves. The Provider Limit Status indicator and Failure surface keep their current positions.

5. **Given** the registry/contract surfaces, **Then** there is **no** new `AllowedAction`, event type, error code, Flyway migration, or `runner-result` field; `view_runner_logs` and `open_diagnostic_console` already exist in `AllowedAction` and in `contracts/frontend/allowed-actions.placeholder.json`, and the `getAllowedActions` `@Schema(allowableValues)` already lists them. Review follow-up updated the diagnostic-console OpenAPI description and regenerated `schema.d.ts` so public docs reflect the actual `Investigating` coverage; this is documentation-only API churn, not schema shape/action churn.

6. **Given** ADR 0025 (live observability + read-only console), **Then** an amendment note records that both affordances now cover the `Investigating` (spec-generation) state on the **identical** security posture as `Executing` (read-only/input-disabled console, owner-only, live-only re-check at attach) — so no new security sign-off gate is introduced (contrast 3d-6's AC1 sign-off, which already ratified the input-disabled design).

7. **Given** tests, **Then** coverage asserts: the `Investigating` matrix rows for `product_reviewer` and `workflow_owner` (both open + no-open clarification branches) match AC2 and the cross-product matrix coverage test stays green; an `Investigating` run with `view_runner_logs` renders the log viewer and (owner) the console; the two surfaces render **below** the Decision Bar in the route's DOM order; `application.*` ≥80% line coverage holds.

## Tasks / Subtasks

- [x] **Task 1 — Widen the `INVESTIGATING` allowed-action matrix arm** (AC: 1, 2, 5)
  - [x] `application/workflow/WorkflowInspectionService.java#baseActionMatrix` `INVESTIGATING` case (`:908-918`): add `VIEW_RUNNER_LOGS` (role-agnostic) to both branches and `OPEN_DIAGNOSTIC_CONSOLE` (guarded `ROLE_WORKFLOW_OWNER.equals(actorRole)`) — mirror the `EXECUTING` arm's role split (`:933-952`) but WITHOUT `AWAIT_OUTCOME` and WITHOUT `VIEW_PROVIDER_USAGE_STATUS` (R3 — scope discipline).
  - [x] Preserve the existing `ANSWER_CLARIFICATION` on the open-clarification branch (the AC3 "open" = literally `open` clarification gate, `hasOpenClarificationOnArtifact`). Do NOT alter the wrapper that appends `archive_run`/`unarchive_run` (`computeActionMatrix`, `:881-898`).
  - [x] Update the stale `EXECUTING`-arm comment ("EXECUTING is the only state where a container is live to attach") — it is now also true for `INVESTIGATING`; correct the comment in both the matrix arm and any mirrored javadoc.

- [x] **Task 2 — Move the log viewer + diagnostic console below the Decision Bar** (AC: 4)
  - [x] `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx`: relocate the `{canViewRunnerLogs ? <StepExecutionLogViewer .../> : null}` block and the `{canOpenDiagnosticConsole ? <ReadOnlyDiagnosticConsole .../> : null}` block from near the top of the `<Stack>` to **after** `<WorkflowDecisionBar .../>`.
  - [x] Leave `ProviderLimitStatus`, `FailureEventSurface`, the manual-execution surface, and the clarification region in place. Keep the gating expressions (`canViewRunnerLogs`, `canOpenDiagnosticConsole`, `ownerActions`) untouched — only JSX order changes.
  - [x] Sanity-check the `Stack`/layout spacing reads correctly with the viewers as the trailing block (the surfaces are self-contained cards; no new wrapper needed unless spacing regresses).

- [x] **Task 3 — ADR 0025 amendment note** (AC: 6)
  - [x] `docs/adr/0025-live-observability-and-readonly-console.md`: append a short amendment ("Story 3e-5: extended to the Investigating/spec-generation state on identical posture") — affordances now cover `Investigating`; read-only/input-disabled console, owner-only, live-only attach re-check are unchanged, so no new sign-off gate. No ADR status change.

- [x] **Task 4 — Tests** (AC: 7)
  - [x] `WorkflowInspectionServiceAllowedActionsTest`: updated the parameterized `INVESTIGATING` rows (`product_reviewer`, `workflow_owner`) to AC2; updated `investigatingWithOpenClarificationIncludesAnswerClarification` to expect the added `view_runner_logs`; renamed `investigatingWithZeroOpenClarificationsIsViewOnlyOnly` → `...OffersViewAndRunnerLogs`; updated the `workflow_owner` `Investigating` open-clarification variant (now `+view_runner_logs +open_diagnostic_console`). `matrixCoversEveryStateAndRow` stays green.
  - [x] FE route tests (`index.runnerLogs.test.tsx`, `index.diagnosticConsole.test.tsx`): added an `Investigating` case for each that renders the viewer/console and asserts DOM order places the surface **after** the Decision Bar (via `compareDocumentPosition` against `approval-decision-bar`). No existing position assertions existed to flip.
  - [x] Confirmed `allowed-actions.placeholder.json` needs no edit (both actions already present). Review follow-up updated the diagnostic-console OpenAPI description and regenerated `schema.d.ts`; `OpenApiSnapshotContractTest` and `check:api` pass.
  - [x] e2e: no change needed — the e2e mocks (`mockApi.ts`) pin only `Executing`/`Failed` action sets (unchanged), and the specs assert no surface DOM position, so the relocation + the `Investigating` widening don't touch them (condition not met).

- [x] **Logging instrumentation** (cross-cutting)
  - [x] No new backend log lines required (the matrix-derivation log at `getAllowedActions` already records `actionCount`). No spec/clarification text logged. The existing `getAllowedActions` success log now reports the larger `Investigating` action count automatically (covered by the matrix tests).

## Dev Notes

### Architecture / source-tree context

- **Allowed-action matrix is the SINGLE source of truth** (ArchUnit pin `ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE`): the state×role→action mapping lives only in `WorkflowInspectionService` (+ `AllowedActionsResponse`). This story's backend change is confined to one `switch` arm there. Do not introduce a parallel mapping in the controller or FE.
- **Streaming endpoints are stage-agnostic and already shipped** (3d-5/3d-6): `RunnerLogStreamController` (`GET /api/v1/workflows/{id}/runner-logs/stream`) resolves the latest runner execution (live follow via `RunnerLogStreamPort` behind the docker gateway; finished replay via the 3.6 redacted store); `RunnerDiagnosticConsoleController` (`GET /api/v1/workflows/{id}/diagnostic-console/stream`) attaches the live container with input disabled and re-checks liveness at attach (LIVE-ONLY → `console-not-live` if absent). Neither is stage-bound, so they work for the spec runner once the matrix offers the actions. **No endpoint/port/adapter change.**
- **FE gating already flows from `useAllowedActions`** (never role-inferred — eslint `local-rules/no-role-based-action-gating`): `canViewRunnerLogs = allowedActions.data?.actions.includes('view_runner_logs')`; `canOpenDiagnosticConsole = ownerActions.data?.actions.includes('open_diagnostic_console')` (owner-scoped set). Once the backend offers the actions in `Investigating`, the surfaces render automatically — Task 2 is purely DOM position.

### Reconciliations (why the reconciled ACs differ from a naive reading)

- **R1 — matrix gate is the whole fix; the plumbing is done.** See readiness note 1. The endpoints already serve the spec runner; the symptom is purely the missing matrix affordance. This keeps the story small and contained.
- **R2 — mirror EXECUTING's role split, both branches.** See readiness note 2. `OPEN_DIAGNOSTIC_CONSOLE` is owner-only; `VIEW_RUNNER_LOGS` is role-agnostic. The `Investigating` arm has two branches (open / no-open clarification) and the affordances must be added to both.
- **R3 — provider-usage is excluded.** See readiness note 3. Tight scope to the two console affordances.
- **R4 — no schema/action churn.** Both actions are already in the `AllowedAction` enum, the frontend placeholder, and the `getAllowedActions` `@Schema(allowableValues)` (added by 3d-5/3d-6). Widening a matrix arm that returns already-registered actions does not add a schema shape, action, error, event, migration, or runner-result field. Review follow-up intentionally updated the diagnostic-console OpenAPI description and regenerated `schema.d.ts` so public documentation matches the widened gate.
- **R5 — placement is cosmetic but test-bearing.** Moving the JSX below `WorkflowDecisionBar` changes DOM order; any FE test asserting the viewers appear before the bar must flip. No accessibility regression expected (the surfaces remain in the same single `Stack`, in source order, keyboard-reachable).

### Testing standards

- Backend matrix tests are the load-bearing assertions — the parameterized provider + the cross-product coverage test (`matrixCoversEveryStateAndRow`) will fail loudly if the `Investigating` rows are wrong, which is the intended tripwire (mirrors the 3d-5/3d-6/3d-7 matrix-fan-out lessons: a matrix change always touches the exact-list assertions).
- FE: Vitest for render-on-`Investigating` + DOM-order; reuse the existing MSW handlers (branch the `Investigating` action set). axe over the relocated surfaces.
- `@SpringBootTest`+Testcontainers ⇒ `*IT` via the lifecycle phase, not a direct goal ([[maven-arglineation-goal-crash]]). This story likely needs no new IT (the endpoints are unchanged) — the matrix change is covered by the existing unit test class.

### Dependencies

- 3d-5 (log viewer + `view_runner_logs`) — done.
- 3d-6 (read-only console + `open_diagnostic_console`) — done.
- No dependency on 3e-1..3e-4 (independent; can be done in any order within Epic 3e).

### FRs

- Extends **FR65** (live + historical step-log viewing) and **FR68** (read-only diagnostic console) — both Epic 3d definitions — to the spec-generation (`Investigating`) state, and refines the run-detail placement. Introduces no new PRD requirement.

## Dev Agent Record

### Implementation Plan

1. **Backend (Task 1):** widened the single `INVESTIGATING` arm of `WorkflowInspectionService.baseActionMatrix`. Rebuilt it from a two-`List.of` branch into an `ArrayList` accumulation: always `VIEW_ONLY`, then `ANSWER_CLARIFICATION` iff the latest in-flight spec has an open clarification (unchanged AC3 gate), then `VIEW_RUNNER_LOGS` (role-agnostic), then `OPEN_DIAGNOSTIC_CONSOLE` iff `workflow_owner`. This mirrors the `EXECUTING` role split minus `AWAIT_OUTCOME`/`VIEW_PROVIDER_USAGE_STATUS` (R3). The `archive_run`/`unarchive_run` wrapper (`computeActionMatrix`) is untouched, so the owner rows still trail with `archive_run`. Corrected the stale `EXECUTING`-arm comment that claimed it was "the only state where a container is live".
2. **Frontend (Task 2):** moved the `StepExecutionLogViewer` and `ReadOnlyDiagnosticConsole` JSX blocks from near the top of the `<Stack>` to after `<WorkflowDecisionBar>`. `ProviderLimitStatus`, `FailureEventSurface`, the manual-execution surface, and the clarification region stay put. Gating expressions (`canViewRunnerLogs`, `canOpenDiagnosticConsole`) are unchanged — only DOM order moved.
3. **ADR (Task 3):** appended an amendment to ADR 0025 recording the `Investigating` coverage on identical security posture (read-only/input-disabled, owner-only, live-only re-check) → no new sign-off gate. Status unchanged (Accepted).
4. **Tests (Task 4):** updated the backend matrix parameterized rows + the dedicated `Investigating` tests to AC2; added an `Investigating` render + DOM-order case to each FE route test; review follow-up verified the OpenAPI snapshot and frontend generated-client drift gate after the documentation-only description update.

### Completion Notes

- **Provider-usage status is intentionally EXCLUDED (R3).** `VIEW_PROVIDER_USAGE_STATUS` (3d-7) is offered in `EXECUTING` but was deliberately NOT added to `Investigating` — the reported symptom and the requested change are about console output (logs + console). The spike snapshot is captured post-execution, so provider-usage during the spec stage is a separate concern.
- **No schema/action churn (R4).** No new `AllowedAction`, event type, error code, Flyway migration, or `runner-result` field. Both `view_runner_logs` and `open_diagnostic_console` were already in the `AllowedAction` enum, the FE placeholder, and the `getAllowedActions` `@Schema(allowableValues)`. Review follow-up updated only the diagnostic-console operation description in `openapi.json`/`schema.d.ts`.
- **Streaming endpoints unchanged (R1).** The `GET /runner-logs/stream` and `GET /diagnostic-console/stream` endpoints, ports, and adapters were not touched — they are stage-agnostic and already served the spec runner; the only gap was the matrix affordance.
- **e2e: no change required.** The e2e mocks pin only `Executing`/`Failed` action sets (unchanged) and the specs assert no surface DOM position, so neither the matrix widening nor the relocation affects them.

### Validation

- Backend: `WorkflowInspectionService*Test` (122 tests) — **all pass**, including `matrixCoversEveryStateAndRow` (cross-product) and the updated `Investigating` rows. `OpenApiSnapshotContractTest`, `WorkflowInspectionServiceAllowedActionsLoggingTest`, `WorkflowReadEndpointsContractTest` — **all pass**.
- Frontend: `index.runnerLogs.test.tsx` + `index.diagnosticConsole.test.tsx` + full `$workflowRunId` route folder — **13 tests pass** (incl. the 2 new `Investigating` DOM-order cases). ESLint + Prettier clean on the 3 changed FE files.
- Review follow-up verification: `index.runnerLogs.test.tsx` + `index.diagnosticConsole.test.tsx` — **6 tests pass**; `RunnerDiagnosticConsoleControllerTest` — **3 tests pass**; `OpenApiSnapshotContractTest` — **1 test passes**; `npm run check:api` — generated client in sync; backend `spotless:check` passes.

## File List

- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (modified — `INVESTIGATING` matrix arm widened; stale `EXECUTING` comment corrected)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java` (modified — `Investigating` matrix rows + dedicated tests updated to AC2)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (modified — log viewer + diagnostic console relocated below the Decision Bar)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.runnerLogs.test.tsx` (modified — added `Investigating` render + below-bar DOM-order case)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.diagnosticConsole.test.tsx` (modified — added `Investigating` render + below-bar DOM-order case)
- `docs/adr/0025-live-observability-and-readonly-console.md` (modified — story 3e-5 amendment note)

## Review Findings

_Code review 2026-06-25 (bmad-code-review, 3 adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor). All 7 ACs + R3 verified satisfied by the Acceptance Auditor against the live code; matrix ordering & consumer impact verified clean by the Edge Case Hunter. 2 low-severity patch items below; 10 findings dismissed as noise / by-design / pre-existing / false-positive._

- [x] [Review][Patch] Stale `@Operation` description still says the diagnostic console is gated "(EXECUTING + workflow_owner)" — resolved by documenting `EXECUTING or INVESTIGATING + workflow_owner` in the controller, committed OpenAPI snapshot, and generated frontend schema.
- [x] [Review][Patch] FE DOM-order tests assert `compareDocumentPosition(...) === Node.DOCUMENT_POSITION_FOLLOWING` (strict equality on a bitmask) — dismissed as stale in the second pass; the tests already use `expect(pos & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()`.

### Review Findings — 2026-06-25 second pass

- [x] [Review][Decision] Public OpenAPI/client docs still describe the diagnostic console as `EXECUTING + workflow_owner` only — resolved per Alex's decision to update documentation to reflect actual code state. Updated the controller OpenAPI description, committed `openapi.json`, and generated `schema.d.ts` wording to `EXECUTING or INVESTIGATING + workflow_owner`.
- [x] [Review][Patch] Non-contract comments/docs still say the console/log affordances are `EXECUTING`-only [deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx:143]
- [x] [Review][Patch] Investigating console route test does not model the AC2 default action set [deliveryline-frontend/src/routes/workflows/$workflowRunId/index.diagnosticConsole.test.tsx:114]
- [x] [Review][Patch] Prior review finding is now stale and contradicts the current bitmask assertions [_bmad-output/implementation-artifacts/3e-5-spec-stage-runner-observability-and-decision-bar-placement.md:130]

## Change Log

- 2026-06-25 — Story 3e-5 implemented: widened the `INVESTIGATING` allowed-action matrix arm to offer `view_runner_logs` (all roles) and `open_diagnostic_console` (workflow_owner) — mirroring `EXECUTING` minus `await_outcome`/`view_provider_usage_status` — and relocated the log viewer + diagnostic console below the Decision Bar in the run-detail route. ADR 0025 amended (identical posture, no new sign-off). No contract/schema/migration churn; OpenAPI snapshot byte-identical. Status → review.
- 2026-06-25 — Code review follow-up complete: public diagnostic-console docs now say `EXECUTING or INVESTIGATING + workflow_owner`, route comments/test fixture match AC2, stale review note resolved, `openapi.json` + `schema.d.ts` regenerated for documentation-only description drift. Status → done.
