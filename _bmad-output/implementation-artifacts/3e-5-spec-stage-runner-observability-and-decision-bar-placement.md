# Story 3e.5: Spec-Stage (Investigating) Runner Log & Console Visibility + Decision-Bar-Relative Placement

Status: ready-for-dev

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

5. **Given** the registry/contract surfaces, **Then** there is **no** new `AllowedAction`, event type, error code, Flyway migration, or `runner-result` field; `view_runner_logs` and `open_diagnostic_console` already exist in `AllowedAction` and in `contracts/frontend/allowed-actions.placeholder.json`, and the `getAllowedActions` `@Schema(allowableValues)` already lists them — so the **OpenAPI snapshot is byte-identical** and no `schema.d.ts` regen is required.

6. **Given** ADR 0025 (live observability + read-only console), **Then** an amendment note records that both affordances now cover the `Investigating` (spec-generation) state on the **identical** security posture as `Executing` (read-only/input-disabled console, owner-only, live-only re-check at attach) — so no new security sign-off gate is introduced (contrast 3d-6's AC1 sign-off, which already ratified the input-disabled design).

7. **Given** tests, **Then** coverage asserts: the `Investigating` matrix rows for `product_reviewer` and `workflow_owner` (both open + no-open clarification branches) match AC2 and the cross-product matrix coverage test stays green; an `Investigating` run with `view_runner_logs` renders the log viewer and (owner) the console; the two surfaces render **below** the Decision Bar in the route's DOM order; `application.*` ≥80% line coverage holds.

## Tasks / Subtasks

- [ ] **Task 1 — Widen the `INVESTIGATING` allowed-action matrix arm** (AC: 1, 2, 5)
  - [ ] `application/workflow/WorkflowInspectionService.java#baseActionMatrix` `INVESTIGATING` case (`:908-918`): add `VIEW_RUNNER_LOGS` (role-agnostic) to both branches and `OPEN_DIAGNOSTIC_CONSOLE` (guarded `ROLE_WORKFLOW_OWNER.equals(actorRole)`) — mirror the `EXECUTING` arm's role split (`:933-952`) but WITHOUT `AWAIT_OUTCOME` and WITHOUT `VIEW_PROVIDER_USAGE_STATUS` (R3 — scope discipline).
  - [ ] Preserve the existing `ANSWER_CLARIFICATION` on the open-clarification branch (the AC3 "open" = literally `open` clarification gate, `hasOpenClarificationOnArtifact`). Do NOT alter the wrapper that appends `archive_run`/`unarchive_run` (`computeActionMatrix`, `:881-898`).
  - [ ] Update the stale `EXECUTING`-arm comment ("EXECUTING is the only state where a container is live to attach") — it is now also true for `INVESTIGATING`; correct the comment in both the matrix arm and any mirrored javadoc.

- [ ] **Task 2 — Move the log viewer + diagnostic console below the Decision Bar** (AC: 4)
  - [ ] `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx`: relocate the `{canViewRunnerLogs ? <StepExecutionLogViewer .../> : null}` block (`:194-196`) and the `{canOpenDiagnosticConsole ? <ReadOnlyDiagnosticConsole .../> : null}` block (`:201-206`) from near the top of the `<Stack>` to **after** `<WorkflowDecisionBar .../>` (`:294`).
  - [ ] Leave `ProviderLimitStatus` (`:197-200`), `FailureEventSurface` (`:193`), the manual-execution surface, and the clarification region in place. Keep the gating expressions (`canViewRunnerLogs`, `canOpenDiagnosticConsole`, `ownerActions`) untouched — only JSX order changes.
  - [ ] Sanity-check the `Stack`/layout spacing reads correctly with the viewers as the trailing block (the surfaces are self-contained cards; no new wrapper needed unless spacing regresses).

- [ ] **Task 3 — ADR 0025 amendment note** (AC: 6)
  - [ ] `docs/adr/0025-live-observability-and-readonly-console.md`: append a short amendment ("Story 3e-5: extended to the Investigating/spec-generation state on identical posture") — affordances now cover `Investigating`; read-only/input-disabled console, owner-only, live-only attach re-check are unchanged, so no new sign-off gate. No ADR status change.

- [ ] **Task 4 — Tests** (AC: 7)
  - [ ] `WorkflowInspectionServiceAllowedActionsTest`: update the parameterized `INVESTIGATING` rows (`product_reviewer` `:106-107`, `workflow_owner` `:108-111`) to AC2; update `investigatingWithOpenClarificationIncludesAnswerClarification` (`:392`) to expect the added `view_runner_logs` (+ owner: `open_diagnostic_console`); rename/update `investigatingWithZeroOpenClarificationsIsViewOnlyOnly` (`:407`) — it is no longer "view-only only"; update the `workflow_owner` `Investigating` variant (`:682`). Confirm `matrixCoversEveryStateAndRow` stays green.
  - [ ] FE route tests (`index.runnerLogs.test.tsx`, `index.diagnosticConsole.test.tsx`): add/extend a case where `currentState === 'Investigating'` + the action present → the viewer/console render; assert DOM order places both surfaces **after** the Decision Bar (e.g. via `compareDocumentPosition` or testid ordering). Update any existing position assertions that expected the viewers above the bar.
  - [ ] Confirm the OpenAPI snapshot test is byte-identical (no contract change) and `allowed-actions.placeholder.json` needs no edit (the two actions already present) — assert by running the drift + snapshot tiers, no regen.
  - [ ] e2e (`runner-log-viewer.spec.ts`, `diagnostic-console.spec.ts`) + `e2e/support/mockApi.ts`: if a mock pins the `Investigating` action set or surface position, update it to include the affordances + the new ordering.

- [ ] **Logging instrumentation** (cross-cutting)
  - [ ] No new backend log lines required (the matrix-derivation log at `getAllowedActions` already records `actionCount`). Do NOT log spec/clarification text. Confirm the existing `getAllowedActions` success log now reports the larger `Investigating` action count (no assertion needed beyond the matrix tests).

## Dev Notes

### Architecture / source-tree context

- **Allowed-action matrix is the SINGLE source of truth** (ArchUnit pin `ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE`): the state×role→action mapping lives only in `WorkflowInspectionService` (+ `AllowedActionsResponse`). This story's backend change is confined to one `switch` arm there. Do not introduce a parallel mapping in the controller or FE.
- **Streaming endpoints are stage-agnostic and already shipped** (3d-5/3d-6): `RunnerLogStreamController` (`GET /api/v1/workflows/{id}/runner-logs/stream`) resolves the latest runner execution (live follow via `RunnerLogStreamPort` behind the docker gateway; finished replay via the 3.6 redacted store); `RunnerDiagnosticConsoleController` (`GET /api/v1/workflows/{id}/diagnostic-console/stream`) attaches the live container with input disabled and re-checks liveness at attach (LIVE-ONLY → `console-not-live` if absent). Neither is stage-bound, so they work for the spec runner once the matrix offers the actions. **No endpoint/port/adapter change.**
- **FE gating already flows from `useAllowedActions`** (never role-inferred — eslint `local-rules/no-role-based-action-gating`): `canViewRunnerLogs = allowedActions.data?.actions.includes('view_runner_logs')`; `canOpenDiagnosticConsole = ownerActions.data?.actions.includes('open_diagnostic_console')` (owner-scoped set). Once the backend offers the actions in `Investigating`, the surfaces render automatically — Task 2 is purely DOM position.

### Reconciliations (why the reconciled ACs differ from a naive reading)

- **R1 — matrix gate is the whole fix; the plumbing is done.** See readiness note 1. The endpoints already serve the spec runner; the symptom is purely the missing matrix affordance. This keeps the story small and contained.
- **R2 — mirror EXECUTING's role split, both branches.** See readiness note 2. `OPEN_DIAGNOSTIC_CONSOLE` is owner-only; `VIEW_RUNNER_LOGS` is role-agnostic. The `Investigating` arm has two branches (open / no-open clarification) and the affordances must be added to both.
- **R3 — provider-usage is excluded.** See readiness note 3. Tight scope to the two console affordances.
- **R4 — no contract churn.** Both actions are already in the `AllowedAction` enum, the frontend placeholder, and the `getAllowedActions` `@Schema(allowableValues)` (added by 3d-5/3d-6). Widening a matrix arm that returns already-registered actions does not change the OpenAPI snapshot or `schema.d.ts`. Avoids the [[openapi-regen-frontend-client-drift-cascade]] cascade entirely (nothing to regen).
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
