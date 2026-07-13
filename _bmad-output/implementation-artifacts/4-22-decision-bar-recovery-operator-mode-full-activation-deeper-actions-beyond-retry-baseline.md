# Story 4.22: Decision Bar `recovery_operator` Mode — Full Activation (Deeper Actions Beyond Retry Baseline)

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Workflow Owner using the Decision Bar's `recovery_operator` mode in the UI to drive recovery actions,
I want the mode (baseline-activated in story 3.30 with only `Retry failed step`) **fully activated** with the deeper action set — resume / reconcile / rerun-from-step (with a safe-boundary dropdown + supersession preview) / pause / classify-failure — each gated by backend allowed-actions, safety-ranked, and guarded by confirmation patterns appropriate to consequence,
so that operators have a single unified UI surface for every Epic-4 recovery action per UX-DR12, without leaving the Decision Bar to invoke any individual recovery action.

## Acceptance Criteria

1. **Full action set rendered from allowed-actions.** The `recovery_operator` bar (baseline from story 3.30) is extended to render the full action set **only when the live backend allowed-actions include the corresponding wire token**: `Retry failed step` (`retry`), `Resume run` (`resume_workflow`), `Reconcile conflict` (`reconcile_conflict`), `Rerun from step` (`rerun_from_step`), `Pause run` (`pause_workflow`), `Classify failure` (`classify_failure`). Unknown/absent tokens never render an action (forward-compat, UX-DR6).
2. **Single visually-primary action, safety-ranked.** Exactly one primary-styled control per UX-DR19: the action resolved `safe` for the current state is primary; every other action is visually subordinate with its resolved `safetyLevel` indicated non-color-only (`caution` → warning affix/icon, `risky`/`danger` → stronger warning). The bar NEVER renders two primary controls.
3. **Resume → ConfirmationDialog.** `Resume run`, when invoked, opens the shared `ConfirmationDialog` with consequence "Resume will return the run to its prior executing state and re-enqueue runner work." + Cancel + "Confirm resume"; confirm calls `useResumeWorkflow`, sending `role=workflow_owner` + `Idempotency-Key`.
4. **Reconcile → entry point delegating to story 4.23.** `Reconcile conflict` renders (and is enabled) only when `reconcile_conflict` is in allowed-actions (backend adds it when unresolved conflicts exist). Invoking it fires the `onReconcile` seam — the actual conflict-review + reconcile mutation is owned by the story-4.23 `ReconciliationDialog`. This story provides the entry-point button + stable seam only (see Dev Notes "Reconcile / Classify delegation").
5. **Rerun from step → RationaleCaptureDialog + supersession preview.** `Rerun from step` opens a `RationaleCaptureDialog` with: a `targetStep` **select** (options `investigating`/`executing`, sourced from the `RerunFromStepRequest.targetStep` schema enum), a **required** `reasonText` textarea, and a "Show what will be superseded" section that displays the `supersededArtifactIds` + `invalidatedApprovalIds` fetched from a **new non-mutating** `GET /api/v1/workflows/{workflowRunId}/preview-rerun-from-step?targetStep=X` endpoint (added in this story), Cancel + "Confirm rerun" with `intent="danger"`; confirm calls `useRerunFromStep`.
6. **Pause → ConfirmationDialog.** `Pause run` opens `ConfirmationDialog` with consequence "Pause will halt orchestrator dispatch and cancel in-flight + queued runner work for this run. The run can be resumed later." + Cancel + "Confirm pause", `intent="warning"` (pause is reversible — lower severity than takeover's `danger`); confirm calls `usePauseWorkflow`.
7. **Classify failure → entry point delegating to story 4.24.** `Classify failure` renders when `classify_failure` is in allowed-actions; invoking it fires the `onClassifyFailure` seam — the richer taxonomy UI + classify mutation is owned by the story-4.24 `FailureClassificationDialog`. This story provides the entry-point button + stable seam only.
8. **Version-stamped mutations + typed-conflict staleness.** Every owned recovery mutation (resume/rerun/pause) sends `Idempotency-Key` + `role=workflow_owner`. On a typed conflict (`RESUME_NOT_APPLICABLE`, `PAUSE_NOT_APPLICABLE`, `ILLEGAL_TRANSITION`, `IDEMPOTENCY_KEY_CONFLICT`, etc.) the bar renders a stale/error state with a refresh-and-retry CTA rather than a bare failure.
9. **Post-submit decision summary.** After an owned recovery action lands, the bar persists the outcome visibly (chip + resulting state + correlationId ref + timestamp) with the safety-level-appropriate visual treatment, surviving the post-action `currentState` flip until the parent resets.
10. **ARIA + accessibility (story 2.25).** All actions keyboard-reachable; button labels use explicit verbs ("Resume run", "Reconcile conflict", "Rerun from step", "Pause run", "Classify failure"); a disabled action's rationale is `aria-describedby`-linked to backend-reported reason text; focus moves into and out of every dialog predictably (story 2.23 focus discipline).
11. **Disabled actions explained, never silently gone.** When an action's wire token is absent from allowed-actions for the current state, the bar does not render it; when a subordinate action is present-but-withheld with a reason, it renders disabled with an `aria-describedby` explanation. No bare/unexplained disabled control.
12. **Depends on story 4.28 (scope-lift).** The `RecoveryService` scope-protected ArchUnit lock (removed in story 4.28, already merged) must be lifted for the new `RecoveryService.previewRerunFromStep(...)` method to compile without tripping `RECOVERY_SERVICE_IS_SCOPE_PROTECTED`. Confirm 4.28 is merged before starting the backend task.
13. **Test coverage.** Tests cover: `recovery_operator` renders the full action set only when allowed; safety-ranking-driven single-primary selection per state; each owned action's confirmation/rationale dialog renders the correct consequence text + severity styling; rerun preview fetch renders superseded/invalidated ids; version-stamped mutations send `Idempotency-Key` + `role`; typed-conflict → stale CTA; post-submit summary persists; keyboard navigation through all actions + dialogs; single-primary-action rule enforced; reconcile/classify entry-point seams invoke their callbacks; axe-core zero `wcag2aa` violations; backend preview endpoint contract + OpenAPI snapshot.

## Tasks / Subtasks

- [ ] **Task 1 — Backend: `preview-rerun-from-step` read endpoint (AC5, AC12)**
  - [ ] Add `RecoveryService.previewRerunFromStep(String workflowRunId, String targetStep)` returning a lightweight result `record RerunFromStepPreviewResult(WorkflowState resultingState, List<String> supersededArtifactIds, List<String> invalidatedApprovalIds)`. It MUST be **non-mutating**: reuse the existing pure `resolveTargetState(...)` (→ `INVALID_RERUN_TARGET_STEP` on null/blank/unknown), the `RERUN_SOURCE_STATES` gate (→ `ILLEGAL_TRANSITION` when the run is not in `FAILED`/`WAITING_FOR_REVIEW`), and the already-pure `resolveSupersededArtifactIds(workflowRunId, targetState)`. Do NOT call `performRerunPrep`/`approvalService.invalidateCurrentApproval` (those write + require an active tx).
  - [ ] For `invalidatedApprovalIds`, add a **read-only** current-approval lookup rather than the mutating `invalidateCurrentApproval`: `approvalReadPort.findLatestApprovedForArtifactLineage(workflowRunId, invalidatedApprovalArtifactType(targetState).value()).map(publicId)`. Expose it as a small read-only method on `ApprovalService` (mirrors the write-boundary discipline) if a direct port call would violate a boundary; reuse the existing `invalidatedApprovalArtifactType(targetState)` helper (`INVESTIGATING → SPEC`, else `IMPLEMENTATION_PLAN`).
  - [ ] Add `WorkflowController.previewRerunFromStep(...)` as a **read endpoint** (copy the `getFailureDiagnostics`/`getAllowedActions` shape — NO Idempotency-Key, NO actor header, NO `role` gate): `@GetMapping("/{workflowRunId}/preview-rerun-from-step")`, operationId `previewRerunFromStep`, `@RequestParam(name="targetStep", required=false) String targetStep` **as a plain String with NO `@NotBlank`/`@Pattern`** (the class is `@Validated` — a constraint would throw `ConstraintViolationException` and mask the typed `INVALID_RERUN_TARGET_STEP`; `required=false` avoids `MissingServletRequestParameterException`). Normalize with `.strip()` at the boundary like `getAllowedActions`; document the two safe values via `@Parameter(schema=@Schema(allowableValues={"investigating","executing"}))` (doc-only).
  - [ ] Add response DTO `PreviewRerunFromStepResponse(String workflowRunId, String targetStep, List<String> supersededArtifactIds, List<String> invalidatedApprovalIds)` — both lists REQUIRED + never-null (mirror `RerunFromStepResponse`'s REQUIRED never-null pair so the generated TS client can rely on them). Add a `from(...)` factory.
  - [ ] Reuse existing error codes only — NO new `DomainErrorCode`: `INVALID_RERUN_TARGET_STEP` (400), `ILLEGAL_TRANSITION` (409) for wrong source state (or return an empty preview for non-rerunnable states — see Open Question OQ-1), `RUN_NOT_FOUND` (404), `INVALID_ID_PREFIX` (400) via `PublicIdPrefixes.require` in the service.
  - [ ] Regenerate the OpenAPI snapshot: run `OpenApiSnapshotContractTest` with `-Dopenapi.snapshot.write=true` (writes then `fail()`s — expected), review + commit the `openapi.json` diff, re-run to confirm byte-identical; add an operationId assertion for `previewRerunFromStep`. Then `cd deliveryline-frontend; npm run generate-api`.
  - [ ] Tests: `@WebMvcTest`/contract test for the endpoint (happy path returns both id lists; `targetStep=bogus` → 400 `INVALID_RERUN_TARGET_STEP`; wrong source state → per OQ-1; unknown run → 404); a `RecoveryService` unit/integration test proving `previewRerunFromStep` performs **zero writes** (no `recovery_actions`/`workflow_events` rows, no approval invalidation).
- [ ] **Task 2 — FE: extend the action vocabulary + safety model (AC1, AC2)**
  - [ ] In `deliveryline-frontend/src/features/workflows/approvalDecisionView.ts`: add `resume_workflow`, `reconcile_conflict`, `rerun_from_step`, `pause_workflow`, `classify_failure` to BOTH the `DecisionAction` union AND the `KNOWN_ACTIONS` set — otherwise `normalizeActions` coerces them to `unknown` and drops them.
  - [ ] Add a `RecoverySafetyLevel = 'safe' | 'caution' | 'risky'` type + `resolveRecoverySafety(token, diagnostics)` helper that joins each allowed-action token to a `FailureDiagnosticsResponse.recommendedRecoveryActions[].safetyLevel` via a **token→actionType map** — the diagnostics vocabulary is SHORT-FORM and differs from the wire tokens: `{ retry→'retry', pause_workflow→'pause', reconcile_conflict→'reconcile', classify_failure→'classify_failure' }`. `rerun_from_step` and `resume_workflow` are ABSENT from diagnostics (rerun isn't recommended; resume only applies to `Paused` runs, which produce no diagnostics), so give them a **static fallback**: `rerun_from_step → 'risky'` (it supersedes artifacts + invalidates approvals — matches AC5's `danger` styling), `resume_workflow → 'safe'` (the expected forward action from `Paused`). Any token with no diagnostics match and no fallback → `'caution'`.
  - [ ] Add `resolveRecoveryPrimaryAction(actions, safetyByToken)`: returns the single `safe`-rated action to style primary, with a deterministic tie-break priority order (e.g. `retry` > `resume_workflow` > `reconcile_conflict` > others) so multiple `safe` actions never both go primary. Returns `null` → no primary (bar shows subordinate-only / view-only).
  - [ ] Extend `CONSEQUENCE_HINTS.recovery_operator` with short inline hints for `resume_workflow`, `rerun_from_step`, `pause_workflow` (reconcile/classify hints live in their dialogs, 4.23/4.24). Add unit tests in `approvalDecisionView.test.ts` for the new coercion, safety mapping (including the short-form join + fallbacks), and primary resolution.
- [ ] **Task 3 — FE: new mutation + query hooks (AC3, AC5, AC6, AC8)**
  - [ ] `useResumeWorkflow(workflowRunId)` → `POST /api/v1/workflows/{workflowRunId}/resume`, body `ResumeWorkflowRequest { role: 'workflow_owner', reasonText? }`. Build on the `useWorkflowMutation` factory (inherits UUIDv7 `Idempotency-Key` + `detail(id)`/`lists()` invalidation). Use the `RECOVERY_OPERATOR_ROLE` constant for `role` — do NOT copy `useRetryWorkflow`'s `actorIdentity`/`actorType` body pattern (resume/rerun/pause use a header-derived actor + a `role` body field).
  - [ ] `useRerunFromStep(workflowRunId)` → `POST /api/v1/workflows/{workflowRunId}/rerun-from-step`, body `RerunFromStepRequest { role, targetStep, reasonText }`. Vars `{ targetStep: 'investigating'|'executing', reasonText: string }`. Response `RerunFromStepResponse`.
  - [ ] `usePauseWorkflow(workflowRunId)` → `POST /api/v1/workflows/{workflowRunId}/pause`, body `PauseWorkflowRequest { role, reasonText }`.
  - [ ] `usePreviewRerunFromStep(workflowRunId, targetStep, { enabled })` → TanStack **query** hook for `GET .../preview-rerun-from-step?targetStep=X`, keyed under `workflowKeys.detail(runId)` prefix, `enabled` only while the rerun dialog is open and a `targetStep` is chosen. Returns `PreviewRerunFromStepResponse`. Read-only → NO Idempotency-Key.
  - [ ] Do NOT build `useReconcileWorkflow`/`useClassifyFailure` here — those belong to stories 4.23/4.24 (which own the reconcile/classify mutations). See "Reconcile / Classify delegation" in Dev Notes.
  - [ ] Field-only structured logging on submit/error for each mutation (mirror `RecoveryDecisionBarContainer`'s `recovery.retrySubmit`/`recovery.retryError`): log the response `currentState` on success + the stable ProblemDetails `code` + transport flag on error. NEVER `reasonText`/ids/actor (T-LOG-PII).
- [ ] **Task 4 — FE: extend `ApprovalDecisionBar` `recovery_operator` render (AC1, AC2, AC3, AC5, AC6, AC9, AC10, AC11)**
  - [ ] Replace the retry-only `renderRecoveryOperator()` with a multi-action renderer driven by `view.actions` ∩ safety model: one primary (`resolveRecoveryPrimaryAction`) + subordinate secondaries, each labeled with its explicit verb and safety affix. Preserve the existing `success`/`submitting`/`error`/`disabled(view-only)` state branches and the kept-alive success summary.
  - [ ] Add new `on*` callback props to `ApprovalDecisionBarProps`: `onResume?`, `onRerunFromStep?(vars: { targetStep; reasonText })`, `onPause?`, `onReconcile?`, `onClassifyFailure?`. Keep the props flat/optional like the existing `onRetry`/`onTakeover`.
  - [ ] Wire the resume + pause `ConfirmationDialog`s and the rerun `RationaleCaptureDialog` (fields: `targetStep` select + required `reasonText` textarea; render the preview section from `usePreviewRerunFromStep` inside the dialog `children`). Confirm buttons call the respective `on*` callbacks. Use `intent` per AC: resume `warning`/`info`, pause `warning`, rerun `danger`.
  - [ ] Render reconcile + classify as entry-point buttons that invoke `onReconcile`/`onClassifyFailure` (no inline dialog — delegated to 4.23/4.24).
  - [ ] Post-submit summary (AC9): reuse the `recovery` chip + a `DecisionSummary`-style block showing resulting state + correlationId (extend `DecisionSummary.decision` union or add a recovery-specific summary path). Single polite live-region announcement per action lifecycle (extend the `recovery_operator` branch of `announcementText`).
- [ ] **Task 5 — FE: extend `RecoveryDecisionBarContainer` (AC1, AC2, AC8, AC10, AC11)**
  - [ ] Keep `useAllowedActions(workflowRunId, RECOVERY_OPERATOR_ROLE)` + `useWorkflowDetail`. Add `useFailureDiagnostics(workflowRunId)` for the safety ranking; compute `safetyByToken` via `resolveRecoverySafety`. Build the `ApprovalDecisionView` with the full `actions` list + pass the safety model + all `on*` handlers.
  - [ ] Instantiate/accept the new mutations (retry stays; add resume/rerun/pause — accept them as optional props for the route to lift, defaulting to internal instances, exactly like the existing `retry?` prop). Map the aggregate mutation status (pending/success/error) to `ApprovalMutationState`, keyed to whichever action is in flight.
  - [ ] Wire `onReconcile`/`onClassifyFailure` to the seam (Open Question OQ-2 decides the interim behavior). Refresh handler refetches allowed-actions + detail + diagnostics and resets in-flight mutations.
- [ ] **Task 6 — FE: widen the `WorkflowDecisionBar` selector (AC1, AC9)**
  - [ ] Currently `showRecovery = data?.currentState === 'Failed' || retry.isPending || retry.isSuccess`. Widen to also select the recovery bar for `Paused` (so `resume_workflow`/`reconcile_conflict` render) and keep it mounted through resume/rerun/pause settling (`|| resume.isPending || resume.isSuccess || rerun.* || pause.*`). Hoist the new mutation instances here (like `retry`) so a post-action `currentState` flip (e.g. `Paused → Executing` on resume, `Failed → Investigating` on rerun) does not unmount the bar and tear down its success summary + announcement.
  - [ ] Confirm the three selector predicates stay disjoint (`WaitingForReview` vs recovery-states vs everything-else) — `Paused` and `Failed` are both recovery; no collision with `implementation_review`/`spec_approval`.
- [ ] **Task 7 — FE: confirmation catalog entries (AC3, AC5, AC6)**
  - [ ] Add `CONFIRMATION_CATALOG` entries `resumeRun` (`intent: 'warning'`/`'info'`), `pauseRun` (`intent: 'warning'`), `rerunFromStep` (`intent: 'danger'`) with the exact consequence copy from AC3/AC5/AC6. Extend the `ConfirmationActionId` union + `CONFIRMATION_ACTION_IDS` list + the catalog test (which iterates all ids).
- [ ] **Task 8 — FE: tests (AC13)**
  - [ ] `RecoveryDecisionBarContainer.test.tsx`: full action set renders only when allowed (MSW allowed-actions fixtures for `Failed` = retry/rerun/pause/classify and `Paused` = resume; `reconcile_conflict` present variant); single primary per safety fixture; each dialog's consequence + severity; rerun preview fetch renders superseded/invalidated ids; mutation sends `Idempotency-Key` + `role`; typed-conflict → stale CTA; post-submit summary persists across the state flip; reconcile/classify buttons invoke their callback spies; keyboard nav; `expectNoA11yViolations`.
  - [ ] `approvalDecisionView.test.ts`: coercion of the 5 new tokens; safety join + short-form mapping + fallbacks; primary resolution tie-breaks.
  - [ ] `WorkflowDecisionBar.test.tsx`: `Paused` selects the recovery bar; kept-alive through resume/rerun/pause settling.
  - [ ] Run the REAL frontend build (`npm run build` — `tsc -b` typechecks test files that `tsc --noEmit` misses) + eslint + vitest before claiming green.
- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] Backend: `INFO` on `previewRerunFromStep` entry/exit with `workflowRunId` (sanitized) + `targetStep` + result counts; `WARN`/typed on `INVALID_RERUN_TARGET_STEP`/`ILLEGAL_TRANSITION`; NEVER log payloads or ids beyond public ids. Follow the dominant controller convention (`MdcKeys.sanitizeForLog`).
  - [ ] Frontend: field-only structured console per Task 3 (`recovery.resumeSubmit`/`recovery.resumeError`, `recovery.rerunSubmit`/`recovery.rerunError`, `recovery.pauseSubmit`/`recovery.pauseError`, `recovery.previewLoadError`, `recovery.allowedActionsLoadError`). Assert the exact object keys in a focused test to prove no PII leaks (mirror the baseline container's console-key assertions).
  - [ ] Use parameterized logging; levels per the standard below; carry `correlationId`/`workflowRunId` where available; never log `reasonText`, `taxonomyValue` free text, tokens, or PII.

## Dev Notes

### The exact seam this story extends (do NOT rebuild)

The `recovery_operator` mode already exists end-to-end from story 3.30 — this story WIDENS it, it does not create it:

- **Presentational bar:** `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx` — `renderRecoveryOperator()` (currently retry-only, ~lines 586–680). Prop-driven; all types live in the sibling `.ts`.
- **Container:** `deliveryline-frontend/src/features/workflows/components/RecoveryDecisionBarContainer.tsx` — the thin data container wiring `useAllowedActions(runId, RECOVERY_OPERATOR_ROLE)` + `useWorkflowDetail` + `useRetryWorkflow` → the bar.
- **View-model:** `deliveryline-frontend/src/features/workflows/approvalDecisionView.ts` — `DecisionAction` union + `KNOWN_ACTIONS` + `normalizeActions` + `canRetry` + `RECOVERY_OPERATOR_ROLE` (`'workflow_owner'`) + `CONSEQUENCE_HINTS`.
- **Route selector:** `deliveryline-frontend/src/features/workflows/components/WorkflowDecisionBar.tsx` — hoists mutation instances so the bar survives the post-action state flip; mounted from `src/routes/workflows/$workflowRunId/index.tsx` (`<WorkflowDecisionBar>` at ~line 330).
- **Dialog primitives (story 2.23, already built):** `src/components/overlays/ConfirmationDialog.tsx` (`{ open, onOpenChange, title, intent, consequence(REQUIRED), confirmLabel, cancelLabel, onConfirm, onCancel, isConfirming, confirmDisabled, children }`) and `src/components/overlays/RationaleCaptureDialog.tsx` (composes `ConfirmationDialog`, adds typed `fields: RationaleField[]` with `type: 'text'|'textarea'|'select'`, required-field gating, inline `aria-describedby` errors, `onConfirm(values: Record<string,string>)`). The precedent for a select + textarea rationale dialog already exists — use it for rerun.
- **Confirmation catalog:** `src/lib/overlays/confirmationCatalog.ts` — `retryOrRecoverConsequential` (3.30) + `takeoverWorkflow` (3.28) already exist; add `resumeRun`/`pauseRun`/`rerunFromStep`.

### Backend allowed-actions matrix is ALREADY complete — no matrix work

`WorkflowInspectionService.computeActionMatrix` (verified) already emits every recovery token for `role=workflow_owner`:
- **`FAILED`** → `retry`, `rerun_from_step`, `pause_workflow`, `classify_failure` (+ diagnostics/log/usage views).
- **`PAUSED`** → `resume_workflow` (+ views).
- **`reconcile_conflict`** → added orthogonally when the run has unresolved integration conflicts (conflict block runs before the per-state matrix), for `workflow_owner`.
- `pause_workflow` is also offered in many mid-flight states (Investigating, Executing, WaitingForReview, WaitingForLintApproval, WaitingForDelivery, WaitingForManualExecution).

The allowed-actions REST field is an **open `string[]`** (`AllowedActionsResponse.actions`) — no `safetyLevel`/`disabledActions`/enum on it. So the ONLY backend change in this story is the new `preview-rerun-from-step` read endpoint (Task 1).

### The safety-ranking join is a vocabulary mismatch — the #1 trap

AC2's safety ranking is sourced from `FailureDiagnosticsResponse.recommendedRecoveryActions[]` (existing `useFailureDiagnostics` hook), NOT from allowed-actions. `RecommendedAction` = `{ actionType, safetyLevel, reason, precondition }`. **Critical:** `RecommendationService` emits `actionType` in a SHORT-FORM vocabulary that differs from the allowed-actions wire tokens, and only for `Failed` runs:
- diagnostics `retry` ↔ token `retry`
- diagnostics `pause` ↔ token `pause_workflow`
- diagnostics `reconcile` ↔ token `reconcile_conflict`
- diagnostics `classify_failure` ↔ token `classify_failure`
- **`rerun_from_step` and `resume_workflow` are NOT emitted by `RecommendationService` at all** — `rerun` is never recommended, and `resume` only applies to `Paused` (which returns an empty recommendation list). They MUST get a static fallback safety in the FE (`rerun_from_step → risky`, `resume_workflow → safe`).

`safetyLevel` values are the free strings `safe`/`caution`/`risky` (enforced FE-side; there is a `safetyTone()` in `failureDiagnosticsView.ts` — reuse it for the visual affix). When diagnostics are unavailable/loading, fall back to the static table so the primary selection is still deterministic.

### Reconcile / Classify delegation (AC4, AC7) — scope boundary

The epic explicitly says reconcile "delegates to the operator reconciliation dialog from story 4.23" and classify "delegates to the failure-taxonomy classification UI from story 4.24" — "the Decision Bar action is the entry point + the dialog handles the actual mutation." Those dialogs (`ReconciliationDialog`, `FailureClassificationDialog`) and their hooks (`useReconcileWorkflow`, `useClassifyFailure`) **do not exist yet** (confirmed) and are owned by 4.23/4.24. Therefore this story:
- **Fully owns** retry (baseline), resume, rerun-from-step (+ preview), pause — including their mutations + dialogs.
- **Provides entry-point seams only** for reconcile + classify: render the gated button, invoke `onReconcile`/`onClassifyFailure`. The dialog mount + mutation is 4.23/4.24. See OQ-2 for interim behavior of the button before those land.

### Role/actor divergence — do not copy the retry hook's body shape

`useRetryWorkflow` is the ODD one: it puts `actorIdentity`+`actorType` in the `RetryWorkflowRequest` body. The resume/reconcile/rerun/pause/classify endpoints instead take a header-derived actor + a `role: "workflow_owner"` **body field** (the controller calls `requireWorkflowOwnerRole(...)`, then discards `role`; `X-Actor-Identity` header → `ActorContext`). New hooks send `{ role: RECOVERY_OPERATOR_ROLE, ... }`, NOT `actorIdentity`/`actorType`.

### Backend preview endpoint — reuse the pure reads (AC5, AC12)

`RecoveryService.rerunFromStep` already contains the exact read logic:
- `resolveSupersededArtifactIds(workflowRunId, targetState)` is **already pure/non-mutating** (`INVESTIGATING` → latest SPEC/IMPLEMENTATION_PLAN/PR_OUTPUT leaf ids; else IMPLEMENTATION_PLAN/PR_OUTPUT) — reuse verbatim.
- `invalidatedApprovalIds`: the write path is `approvalService.invalidateCurrentApproval(...)`; its read half is `approvalReadPort.findLatestApprovedForArtifactLineage(workflowRunId, artifactType).map(publicId)`. For the preview, call the read half only. `invalidatedApprovalArtifactType(targetState)` (`INVESTIGATING → SPEC`, else `IMPLEMENTATION_PLAN`) + `resolveTargetState` + `RERUN_SOURCE_STATES` are pure/reusable.
- Because the method lives on `RecoveryService`, it needs the story-4.28 scope-lift (`RECOVERY_SERVICE_IS_SCOPE_PROTECTED` removed) — already merged; confirm before compiling (AC12).
- `@Validated @RequestParam` trap: keep `targetStep` a plain `String` with `required=false` and NO bean-validation constraint, or the typed `INVALID_RERUN_TARGET_STEP` gets masked by `ConstraintViolationException`/`MissingServletRequestParameterException`. Let the service's `resolveTargetState` throw the typed code. (See memory: [[validated-requestparam-becomes-500-not-400]].)

### OpenAPI regen incantation (from prior Epic-4 REST stories)

Contract tests run in **Failsafe**, not Surefire. Regen = run `OpenApiSnapshotContractTest` with `-Dopenapi.snapshot.write=true` (it writes the snapshot then `fail()`s on purpose), review + commit the `openapi.json` diff, re-run to confirm byte-identical, then `cd deliveryline-frontend; npm run generate-api`. PowerShell: quote each `-D...=...` arg. Watch for mojibake/em-dash drift in any new `@Schema` description (Windows CP1251 double-encodes `—`). A nullable object field → generated TS OPTIONAL (omit the key in fixtures, don't `:null`).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. FE uses the existing field-only structured `console.info`/`console.warn` idiom (never string-concatenated, never PII).
- **Where to log (minimum surface):**
  - `previewRerunFromStep` read → `INFO` on entry + `INFO` on success with result counts (not ids), `WARN`/typed on invalid target / illegal transition.
  - Each FE recovery mutation → `recovery.<action>Submit` (response `currentState` only) on success, `recovery.<action>Error` (stable `code` + transport flag) on error.
  - Query load failures (allowed-actions, failure-diagnostics, rerun preview) → a `recovery.*LoadError` warn so a transient read failure is distinguishable from "no action available".
- **Required context keys** (via MDC / structured params): `correlationId`, `workflowRunId`; never `reasonText`, `taxonomyValue` free text, tokens, or PII.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, `reasonText`, actor free-text. Field-only console objects only.
- **Test contract:** each new logging surface pinned by at least one focused test (backend `OutputCaptureExtension`; FE `console` spy asserting exact keys), so refactors can't silently delete them.

### Project Structure Notes

- FE recovery UI stays inside `src/features/workflows/` (components/hooks/view-model). New hooks under `src/features/workflows/hooks/`. No new route — the bar mounts via the existing `WorkflowDecisionBar` on `/workflows/$workflowRunId`.
- Backend: new endpoint on the existing `WorkflowController`; new read method on `RecoveryService`; new read-only accessor on `ApprovalService` if needed to avoid an adapter→application boundary hop. `application` cannot import `adapters` (ArchUnit) — the DTO maps the service result, not vice-versa. `REST_CONTROLLERS_STAY_THIN` forbids reaching through nested application types — map into a flat `PreviewRerunFromStepResponse`.
- No Flyway migration (metadata read only). No new `DomainErrorCode`. No new `WorkflowEventType` (preview is a pure read, appends nothing).

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-04-recovery.md#Story 4.22]
- Baseline mode: `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx` (`renderRecoveryOperator`), `RecoveryDecisionBarContainer.tsx`, `approvalDecisionView.ts` (story 3.30).
- Endpoints consumed: resume (4.10), reconcile (4.11), rerun-from-step (4.12), pause (4.13), classify-failure (4.14) — all present in `openapi.json` + `schema.d.ts`.
- Safety ranking: `FailureDiagnosticsResponse.recommendedRecoveryActions[]` (story 4.4) via `useFailureDiagnostics`; `RecommendationService` (short-form actionTypes).
- Backend reuse: `RecoveryService.resolveSupersededArtifactIds` / `invalidatedApprovalArtifactType` / `resolveTargetState` / `RERUN_SOURCE_STATES`; `ApprovalService`/`approvalReadPort.findLatestApprovedForArtifactLineage`.
- Scope-lift dependency: story 4.28 (ArchUnit `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` removed — already merged).
- Traps: [[validated-requestparam-becomes-500-not-400]], [[new-domainerrorcode-three-sites]], [[frontend-tsc-noemit-misses-test-files]], [[openapi-regen-frontend-client-drift-cascade]], [[mojibake-emdash-openapi-drift]], [[workflowdetail-wire-sends-null-not-undefined]], [[frontend-react-refresh-no-fn-exports]].

### Open Questions (resolve during dev; defaults chosen)

- **OQ-1 (preview wrong-state behavior):** For a run not in `FAILED`/`WAITING_FOR_REVIEW`, should the preview endpoint 409 `ILLEGAL_TRANSITION` or return an empty preview (`[]`/`[]`)? **Default:** 409 `ILLEGAL_TRANSITION` (mirror `rerunFromStep`), so the FE only calls preview when `rerun_from_step` is in allowed-actions anyway.
- **OQ-2 (reconcile/classify interim button):** Before stories 4.23/4.24 land, the reconcile/classify entry-point buttons have no dialog to open. **Default:** render them (gated by allowed-actions) but wire `onReconcile`/`onClassifyFailure` to a no-op-with-visible-hint ("Opens in the reconciliation/classification dialog") and DISABLE the button with an `aria-describedby` "available in an upcoming increment" until 4.23/4.24 supply the handler — OR alternatively make the bar only render them when a dialog-open handler prop is provided. Confirm with the epic owner whether 4.22 should ship an interim inline `RationaleCaptureDialog` (resolutionDecision/taxonomyValue select + reasonText) instead, using the already-shipped 4.11/4.14 endpoints. Recommend the seam-only default to honor the epic's explicit delegation.
- **OQ-3 (safety source for Paused):** `Paused` runs produce no `recommendedRecoveryActions`, so `resume_workflow` relies on the static fallback (`safe`). Confirm this is acceptable vs. adding a backend recommendation for the paused/resume path (out of scope here).

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
