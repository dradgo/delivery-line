# Story 2.14: Backend — Allowed-Actions Inspection Endpoint

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a frontend developer building the generalized Approval/Decision Bar (story 2.19) and other action-aware composites,
I want a `GET /api/v1/workflows/{workflowRunId}/allowed-actions` endpoint returning a typed list of backend-derived allowed actions for the current state + actor role + run context, with a version stamp the UI can use to detect staleness,
So that frontend composites read backend truth — never inferring permissions, applicability, or workflow rules locally (UX-DR12 hard rule + party-mode finding #3).

## ⚠️ Read first — preconditions and the three traps that will bite a literal reader

**This story is mostly additive to existing infrastructure.** The hard parts are (1) writing the state×role decision matrix in *one* canonical place so no controller / adapter / frontend duplicates it, (2) composing the version stamp from sources that already exist but live in different services, and (3) avoiding regression of the `approve_spec` registry pin (story 2.9 AC11).

**Already shipped — do NOT rebuild:**

- `AllowedAction` enum at `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` — all 8 values (`approve_spec`, `reject_spec`, `answer_clarification`, `view_only`, `await_outcome`, `retry`, `view_diagnostics`, `clear_escalation_marker`) already registered. The `TODO(story-2.14)` on line 6 is *yours to delete* once the action-derivation logic lands.
- `DomainRegistry.allowedActions()` already exposes the enum values; `RegistryContractTest` (`deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java:88`) already asserts registry parity. Adding a new value would break drift tests — **none needed**; just verify still green.
- `WorkflowInspectionService.getRunSummary(workflowRunPublicId)` (`application/workflow/WorkflowInspectionService.java:217-258`) returns `WorkflowRunDetailedSummaryView` carrying `currentState`, `pendingClarifications`, `escalationMarker`, `specRejectionLoopCount` — **this is your one-stop read for the gating signals** in AC3 + AC4.
- `WorkflowInspectionService.getCurrentApprovedSpec(workflowRunPublicId)` (line 487) projects the latest approved spec → `SpecificationArtifact.version()` gives `currentSpecArtifactVersion`.
- `WorkflowInspectionService.getContextBundleLookupForArtifact(artifactId)` (line 612) resolves the `runner_execution` → `contextBundleVersion` from `RunnerExecutionSnapshot`. **Reuse this** instead of re-querying the runner ports directly.
- `WorkflowEventReadPort.findLatestByWorkflowRunPublicId(...)` already exists and is used twice in the same service — its `.publicId()` is your `lastEventId`.
- `ProblemDetailsResponse`, `@ApiResponses` annotations, `@Validated`, `MediaType.APPLICATION_JSON_VALUE` patterns — copy verbatim from `WorkflowController.getWorkflow` (lines 125-156) and `WorkflowController.getWorkflowEvents` (158-194). Story 6.9 set the template.
- `LocalActorIdentityResolver` + `X-Actor-Identity` header model is already wired (story 2.13). **Read endpoints typically don't need it**, but the controller class already injects it — match siblings for consistency. The endpoint does NOT take `X-Actor-Identity`; `actorRole` comes from the **query param** (AC6 — MVP convenience).

### 🚨 TRAP 1 — `clear_escalation_marker` is registered but its workflow is Epic 4

AC2's enumeration looks like you must wire executor logic for every value. **You must NOT.** `clear_escalation_marker` is operator-only (Epic 4); the action just needs to **appear in the registry** (already does) and the derivation **must not surface it in any E2 state** (none of the AC3 rules emit it). Leave it dormant. Adding any executor / endpoint for it is scope creep that breaks story 2.9's prior "ApprovalService is canonical executor for `approve_spec`" pin and is out-of-scope here.

### 🚨 TRAP 2 — The version stamp's `currentSpecArtifactVersion` is **the in-flight spec the reviewer sees**, NOT the last *approved* spec

Read AC1's `versionStamp` literal: `{workflowState, currentSpecArtifactVersion, currentContextBundleVersion, lastEventId}`. The Approval/Decision Bar (story 2.19) sends the stamp back as `expectedAllowedActionsVersionStamp` on the *next approve-spec mutation*. If you derive `currentSpecArtifactVersion` from `getCurrentApprovedSpec()`, then on a brand-new run that has *generated but not yet approved* a spec v1 — the stamp would carry the *prior* approved version (or null) while the UI is staring at the new v1 in the artifact panel. Stamp/UI drift = `APPROVAL_VERSION_MISMATCH` storm on the first approve.

**Correct source:** the **latest spec artifact** for the run (any status: drafted / approved / rejected) — `artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(workflowRunPublicId, ArtifactType.SPEC.value())` — same accessor `getStatus()` already uses in its `latestArtifacts` loop (line 333-345). When no spec exists yet (e.g., `Inbox`/`Investigating` before first draft) → `currentSpecArtifactVersion = null` (Integer-nullable) and `currentContextBundleVersion = null`. Pin both null cases with a test.

### 🚨 TRAP 3 — The decision matrix must live in the **application** layer, in **one** class, with **zero** ArchUnit-violating shortcuts

AC9 is explicit: action derivation lives in `WorkflowInspectionService`; controllers and adapters MUST NOT import `AllowedAction` directly. The temptation when you discover that `getRunSummary()` already returns `currentState` + `pendingClarifications` will be to "just compose actions in the controller — it's only ~30 lines of switch." **Don't.** That pattern duplicates the rule across controller + (future) CLI consumer + tests, and silently weakens UX-DR12. The new `getAllowedActions(...)` service method is the **only** caller permitted to instantiate `AllowedAction` enum values for response purposes; the controller maps `List<AllowedAction>` → `List<String>` (wire `.value()`) via the response DTO and never references the enum type itself.

**Hard boundaries:**

- **Read-only.** No domain mutations, no state transitions, no idempotency-key requirement (AC6: "idempotent — no Idempotency-Key required"). Hexagonal: `adapters.rest` → `application.workflow`; never `adapters.persistence` directly.
- **No new mutations of `AllowedAction` registry.** Verify all 8 values present; do not rename, do not add, do not drop. Story 2.9's `AllowedActionRegistryPinTest` already pins `approve_spec`; do not delete that test.
- **One source of truth for state×role rules.** Any test or doc that hardcodes a state→action mapping outside `WorkflowInspectionService.getAllowedActions` is a future-bug seed.
- **OpenAPI snapshot regen is part of the AC.** The same `scripts/regen-openapi.sh` flow story 2.13 used applies — see memory `openapi-regen-platform-shim.md` for the WSL2-vs-PowerShell shell split.

## Acceptance Criteria

1. **Given** the `application.workflow` package, **Then** `WorkflowInspectionService.getAllowedActions(workflowRunPublicId, actorRole)` returns a typed `AllowedActionsView` carrying `actions: List<AllowedAction>` and `versionStamp: AllowedActionsVersionStamp { workflowState, currentSpecArtifactVersion, currentContextBundleVersion, lastEventId }`.

2. **Given** the `AllowedAction` central registry (story 1.4), **Then** all 8 wire values are present and drift-tested: `approve_spec`, `reject_spec`, `answer_clarification`, `view_only`, `await_outcome`, `retry`, `view_diagnostics`, `clear_escalation_marker`. *(Pre-existing — verify enum at `AllowedAction.java` unchanged, `DomainRegistry.allowedActions()` parity test in `RegistryContractTest:88` stays green, and `AllowedActionRegistryPinTest.approveSpecWireValueIsPinned` is preserved. Delete the `TODO(story-2.14)` comment on `AllowedAction.java:6` as part of this story.)*

3. **Given** state-+-role-derived rules (sole source of truth — backend, not frontend), **Then** `getAllowedActions` returns the following sets (verify the full matrix in a parameterized test per AC8):

   | `WorkflowState`            | Role                | Returned `actions`                                                                          |
   |----------------------------|---------------------|---------------------------------------------------------------------------------------------|
   | `Inbox`                    | any                 | `[view_only]`                                                                               |
   | `Planned`                  | any                 | `[view_only]`                                                                               |
   | `Investigating`            | any                 | `[view_only]` + `[answer_clarification]` if there are **open** clarifications on the latest in-flight spec |
   | `WaitingForSpecApproval`   | `product_reviewer`  | `[approve_spec, reject_spec, answer_clarification]` (subject to AC4 gating)                 |
   | `WaitingForSpecApproval`   | other roles         | `[view_only, answer_clarification]`                                                         |
   | `Executing`                | any                 | `[view_only, await_outcome]`                                                                |
   | `WaitingForReview`         | `product_reviewer`  | `[view_only]` *(developer-review actions land in Epic 3)*                                   |
   | `WaitingForReview`         | other roles         | `[view_only]`                                                                               |
   | `Completed`                | any                 | `[view_only]`                                                                               |
   | `Failed`                   | `product_reviewer`  | `[view_only, view_diagnostics]`                                                             |
   | `Failed`                   | `workflow_owner`    | `[retry, view_diagnostics]`                                                                 |
   | `Failed`                   | other roles         | `[view_only, view_diagnostics]`                                                             |
   | `Paused`                   | any                 | `[view_only, view_diagnostics]`                                                             |
   | `TakenOver`                | any                 | `[view_only]`                                                                               |
   | `Reconciled`               | any                 | `[view_only]`                                                                               |

   Order within a returned list is documented: primary action first (e.g., `approve_spec` before `reject_spec`), followed by passive views. `view_only` is **omitted** from any set that already contains a richer view+act action (e.g., `[approve_spec, reject_spec, answer_clarification]` does NOT also carry `view_only`). The matrix is encoded as a single switch on `WorkflowState` in `WorkflowInspectionService` — no role-based maps scattered across classes.

4. **Given** clarification gating (party-mode finding from John on E2 PM-loop completeness; codified in story 2.12 AC9), **Then** if `pendingClarifications > 0` (count of clarifications NOT in `incorporated` or `rejected_invalid` — sourced from `WorkflowRunDetailedSummaryView.pendingClarifications`), `approve_spec` is REMOVED from the action list even when state is `WaitingForSpecApproval` + role is `product_reviewer`. The resulting set degrades to `[reject_spec, answer_clarification]` (no `view_only` per AC3 ordering rule). Backend enforces "answered ≠ incorporated, can't approve until incorporated" rather than the frontend.

5. **Given** the version stamp, **Then** `AllowedActionsVersionStamp` is computed as:
   - `workflowState` = current `WorkflowState.value()` from `WorkflowRunSnapshot.currentState()`
   - `currentSpecArtifactVersion` = `version` of the **latest SPEC artifact** for the run regardless of approval status, sourced via `ArtifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(runId, ArtifactType.SPEC.value())`; `null` when no spec exists yet (TRAP 2)
   - `currentContextBundleVersion` = `RunnerExecutionSnapshot.contextBundleVersion` for the runner execution that produced that latest spec artifact, resolved by reusing the lookup path `WorkflowInspectionService.getContextBundleLookupForArtifact(artifactId)` walks (artifact → `RunnerExecutionRecordPort.findByPublicId(...)` via `ArtifactRecordPort.findRunnerExecutionIdForArtifact(...)`); `null` when the artifact has no linked runner execution (e.g., CLI/seed artifacts) or no spec exists
   - `lastEventId` = `publicId` of `WorkflowEventReadPort.findLatestByWorkflowRunPublicId(runId)` (already used twice in this service); `null` only on the unreachable edge of a run with zero events (defensive)
   
   The UI (Approval/Decision Bar in story 2.19) sends `expectedAllowedActionsVersionStamp` on its mutation requests; if the stamp doesn't match current backend state when the mutation lands, the appropriate version-mismatch error returns (e.g., `APPROVAL_VERSION_MISMATCH`) — **no new wiring needed in this story**; the existing approve-spec / reject-spec endpoints (story 2.13) already check `expectedArtifactVersion` + `expectedContextBundleVersion` against current state. This AC documents the contract; the matching error is pre-existing.

6. **Given** REST endpoint `GET /api/v1/workflows/{workflowRunId}/allowed-actions`, **Then** it returns `200` with the typed `AllowedActionsResponse`, supports a `?actorRole=` query param (defaults to `product_reviewer` when absent — documented as MVP convenience), and is idempotent (**no Idempotency-Key required**, no `X-Actor-Identity` header). Response shape: `{ actions: ["approve_spec", ...], versionStamp: { workflowState, currentSpecArtifactVersion, currentContextBundleVersion, lastEventId } }` — direct shape, no `{data: ...}` envelope (architecture API format rule).

7. **Given** error surfaces:
   - Non-existent run → `404` `RUN_NOT_FOUND` (existing code, mapped via `runNotFound(...)` in `WorkflowInspectionService:887` — let it propagate through `ProblemDetailsMapper`).
   - Malformed `workflowRunId` (missing/wrong prefix) → `400` `INVALID_ID_PREFIX` (existing — `PublicIdPrefixes.require(...)` throws on entry; mirror existing reads).
   - Unrecognized `actorRole` → `400` **new** `UNKNOWN_ACTOR_ROLE`. **Add this one new `DomainErrorCode` enum value**, register it in `ProblemDetailsCatalog` with `status=400, retryable=false` (mirror the `INVALID_ID_PREFIX` entry shape), and add a registry-drift test entry if the existing `RegistryContractTest` covers `DomainErrorCode`. Recognized roles for MVP: `product_reviewer`, `workflow_owner`. Any other value (case-sensitive) → 400. Blank/missing → falls back to `product_reviewer` per AC6 default.

8. **Given** state coverage, **Then** for **every** `WorkflowState` enum value (sourced from `WorkflowState.values()` — currently 11: `Inbox`, `Planned`, `Investigating`, `WaitingForSpecApproval`, `Executing`, `WaitingForReview`, `Completed`, `Failed`, `Paused`, `TakenOver`, `Reconciled`), there is at least one (state × role) → action-set test case in a `WorkflowInspectionServiceAllowedActionsTest` parameterized test. Plus dedicated tests for:
   - `WaitingForSpecApproval` + `product_reviewer` + `pendingClarifications=0` → `[approve_spec, reject_spec, answer_clarification]`
   - `WaitingForSpecApproval` + `product_reviewer` + `pendingClarifications=3` → `[reject_spec, answer_clarification]` (AC4)
   - `Investigating` + any role + open-clarification on latest spec → includes `answer_clarification`
   - `Investigating` + any role + zero open clarifications → only `[view_only]`
   - `Failed` + `product_reviewer` vs `Failed` + `workflow_owner` divergence
   - A "future state" guard: a test that iterates `WorkflowState.values()` and fails if any state yields an empty `List<AllowedAction>` — protects future state additions from leaving stale/empty action sets undetected.

9. **Given** ArchUnit boundary rules, **Then** the action-derivation logic lives in `WorkflowInspectionService` (application layer); **no controller, no frontend (n/a here), no adapter, no other application service contains action-derivation rules** — verified by a new ArchUnit rule in `ArchitectureRuleCatalog` (siblings: `REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER` at line 210). The new rule asserts: no class in `org.dradgo.adapters..` and no class in `org.dradgo.application..` *other than* `WorkflowInspectionService` (and its inner records / the new view records) may reference `AllowedAction.APPROVE_SPEC` / `AllowedAction.REJECT_SPEC` / etc. by enum constant. Wire it via `@ArchTest` in `ArchitectureBoundaryTest` under the Java-identifier name matching the convention (e.g., `allowed_action_derivation_lives_only_in_workflow_inspection_service`). Keep `AllowedActionRegistryPinTest` (story 2.9) — that test only references the wire value string and is allowed.

10. **Given** OpenAPI doc + CI drift check (story 1.21 AC6; story 2.13 Task 9 pattern), **Then** the new endpoint and the `AllowedActionsResponse` + `AllowedActionsVersionStampResponse` schemas appear in the regenerated `deliveryline-backend/src/main/resources/openapi/openapi.json` snapshot via `scripts/regen-openapi.sh`; the frontend client at `deliveryline-frontend/src/lib/api/schema.d.ts` is regenerated the same way; CI's `openapi-snapshot-drift` job stays green. Memory `openapi-regen-platform-shim.md` documents the WSL2-vs-PowerShell shell split: backend snapshot regen step works in WSL2, frontend `npm run generate-api` may need PowerShell — switch shells to complete both steps.

11. **Given** future-stage action additions (Epic 3 adds developer-review actions like `approve_implementation` / `reject_implementation` / `takeover`; Epic 4 adds operator recovery actions like `resume` / `reconcile` / `rerun_from_step` / `pause`), **Then** the design supports additive extension: new `AllowedAction` registry values land additively, the `AllowedActionsVersionStamp` model accommodates future fields (record is a value type — adding a field is a binary-incompatible change but OpenAPI consumers can ignore unknown response fields), and a contract paragraph in the controller Javadoc states "the UI must gracefully handle unknown action values by hiding them" (UX-DR12 + UX-DR6 unsupported-state handling already covers this on the frontend; no frontend code in this story). A doc comment on `WorkflowInspectionService.getAllowedActions` lists the deferred Epic 3 / Epic 4 actions explicitly, with a `// SEAM (Epic 3/4)` note next to the state-switch cases that will gain those values, so the next consumer doesn't have to rediscover the seam.

## Tasks / Subtasks

- [x] **Task 1: Add `UNKNOWN_ACTOR_ROLE` domain error code** (AC: 7)
  - [x] Add `UNKNOWN_ACTOR_ROLE` value to `DomainErrorCode` enum (alphabetical / canonical position consistent with siblings). Wire value `"UNKNOWN_ACTOR_ROLE"`.
  - [x] Register in `ProblemDetailsCatalog.createMetadata(...)` with HTTP 400, `retryable=false`, type slug derived via the existing `toUriSlug` helper (`unknown-actor-role`), mirroring `INVALID_ID_PREFIX` entry shape.
  - [x] `RegistryContractTest` parity passed automatically — no edit needed.
  - [x] `ProblemDetailsCatalog.createMetadata` end-of-method invariant (`metadata.keySet().equals(EnumSet.allOf(...))`) now validates the new code.

- [x] **Task 2: Add `AllowedActionsView` + `AllowedActionsVersionStamp` value records to `WorkflowInspectionService`** (AC: 1, 5)
  - [x] Add nested public records to `WorkflowInspectionService`:
    - `AllowedActionsView(List<AllowedAction> actions, AllowedActionsVersionStamp versionStamp)`
    - `AllowedActionsVersionStamp(String workflowState, Integer currentSpecArtifactVersion, Integer currentContextBundleVersion, String lastEventId)`
  - [x] Use `Integer` (boxed) for the two version fields so `null` is meaningful — TRAP 2 case (no spec yet). Use `String` for `workflowState` (the wire form) so the record can be returned directly without a controller-side `.value()` call.
  - [x] Record components are immutable; no setters, no mutation paths.

- [x] **Task 3: Implement `WorkflowInspectionService.getAllowedActions(workflowRunPublicId, actorRole)`** (AC: 1, 3, 4, 5, 7, 9)
  - [x] Method signature: `@Transactional(readOnly = true) public AllowedActionsView getAllowedActions(String workflowRunPublicId, String actorRole)`.
  - [x] **Validate** `workflowRunPublicId` via `PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN)` BEFORE any logging or DB read.
  - [x] **Validate** `actorRole`: if null/blank, default to `"product_reviewer"` (AC6); else strip and check against `RECOGNIZED_ACTOR_ROLES`. On no match, throw `DomainException(UNKNOWN_ACTOR_ROLE)` with `details.actorRole` populated. Did NOT reuse `ApprovalReviewerRoleResolver` — it silently defaults and would violate AC7.
  - [x] **Read in one transaction** the three pieces of context:
    1. `WorkflowRunDetailedSummaryView summary = getRunSummary(workflowRunPublicId);` — gives `currentState`, `pendingClarifications`. (P28 ordering note in the existing implementation already handles the pending-count read-after-write hazard; you inherit it.)
    2. Latest SPEC artifact via `artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(workflowRunPublicId, ArtifactType.SPEC.value())` — gives `currentSpecArtifactVersion` (the snapshot's `version` field) and the artifact `publicId` for the bundle lookup. May be empty.
    3. Latest event via `workflowEventReadPort.findLatestByWorkflowRunPublicId(workflowRunPublicId)` — gives `lastEventId` (`.publicId()`).
  - [x] Compute `currentContextBundleVersion` via `getContextBundleLookupForArtifact(...)` only when spec present; nullable when bundle missing / runner-execution-not-found. Pinned by `versionStampBundleIsNullWhenSpecExistsButRunnerExecutionMissing`.
  - [x] Compute `actions` via the AC3 matrix encoded as a `switch (WorkflowState)` in `computeActionMatrix`:
    - `Investigating` uses `clarificationReadPort.listByArtifactId(latestSpecPublicId)` and filters `status == "open"`; code comment documents the difference vs story 2.12's `pendingClarifications`.
    - `WaitingForSpecApproval` + `product_reviewer` emits the 3-action set; drops `APPROVE_SPEC` when `pendingClarifications > 0` (AC4).
    - `Failed` + `workflow_owner` → `[RETRY, VIEW_DIAGNOSTICS]`; other roles → `[VIEW_ONLY, VIEW_DIAGNOSTICS]`.
    - All other rows per AC3 table.
    - **Default branch** throws `IllegalStateException("Allowed-actions matrix missing case for state " + state.value())` — caught by AC8 future-state guard.
  - [x] Compose and return the typed view; lists wrapped in `List.copyOf(...)` so callers cannot mutate.
  - [x] Wrap in the existing MDC scope idiom (`MdcKeys.beginScope` / `endScope`).

- [x] **Task 4: Wire the `GET /api/v1/workflows/{workflowRunId}/allowed-actions` endpoint** (AC: 6, 7, 10, 11)
  - [x] New `@GetMapping(value = "/{workflowRunId}/allowed-actions")` method placed between `getWorkflowEvents` and the command-endpoints separator comment.
  - [x] Signature matches spec; `@RequestParam(name = "actorRole", required = false)`.
  - [x] Full `@Operation` + `@ApiResponses` block covering 200 / 400 / 404 with `ProblemDetailsResponse` references.
  - [x] Controller does NOT import `AllowedAction` — verified by ArchUnit rule + manual import check. Mapping happens in `AllowedActionsResponse.from(...)` factory only.
  - [x] INFO entry + success logs sanitize controller-side fields through `MdcKeys.sanitizeForLog(...)`.

- [x] **Task 5: Add wire DTOs `AllowedActionsResponse` + `AllowedActionsVersionStampResponse`** (AC: 6, 9)
  - [x] `AllowedActionsResponse(List<String> actions, AllowedActionsVersionStampResponse versionStamp)` with `@Schema(requiredMode = REQUIRED)` on both + static `from(AllowedActionsView)` factory.
  - [x] `AllowedActionsVersionStampResponse(String workflowState, Integer currentSpecArtifactVersion, Integer currentContextBundleVersion, String lastEventId)` with `requiredMode = REQUIRED` on `workflowState` only (other three are `nullable = true` per TRAP 2).
  - [x] Factory maps via `.stream().map(AllowedAction::value).toList()` — sole `AllowedAction` import in `adapters.rest` per the ArchUnit rule exemption.

- [x] **Task 6: ArchUnit boundary tightening** (AC: 9)
  - [x] New rule `ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE` — `noClasses().resideInAnyPackage(application/adapters).and().haveNameNotMatching(WorkflowInspectionService inner-classes regex).and().haveNameNotMatching(AllowedActionsResponse).should().dependOnClassesThat().haveFullyQualifiedName(AllowedAction)`.
  - [x] Wired via `@ArchTest` `allowed_action_derivation_lives_only_in_workflow_inspection_service`.
  - [x] Verified non-regressive: `DomainRegistry.allowedActions()` lives in `domain.registry` (outside the rule's scope); `AllowedActionRegistryPinTest` lives in test sources (rule analyzes main only via `ImportOption.DoNotIncludeTests`). 32 boundary tests pass.

- [x] **Task 7: Per-endpoint contract test** (AC: 6, 7, 10)
  - [x] Created `AllowedActionsEndpointContractTest` under `adapters/rest/` with `@WebMvcTest(WorkflowController.class) + @Import(ApprovalReviewerRoleResolver.class)` and `@MockitoBean` of the three dependencies the controller wires.
  - [x] 8 tests cover:
    - `happyPathReturnsAllowedActionsAndVersionStamp` — full JSON shape assertion (3 actions + 4 stamp fields).
    - `actorRoleQueryParamDefaultsToProductReviewerWhenAbsent` — controller passes raw `null` through; service-level default is asserted via the matching service unit test (`blankActorRoleDefaultsToProductReviewer`).
    - `actorRoleQueryParamHonoredWhenPresent` — `workflow_owner` flows through verbatim.
    - `unknownActorRoleReturns400WithUnknownActorRoleProblemDetails` — `code`, `status`, `details.actorRole` pinned.
    - `malformedRunIdReturns400WithInvalidIdPrefixProblemDetails` / `nonExistentRunReturns404WithRunNotFoundProblemDetails`.
    - `noIdempotencyKeyRequired` (AC6).
    - `nullableVersionStampFieldsSerializeAsJsonNull` — TRAP 2 pin using `Matchers.nullValue()` (Jackson default `Include.ALWAYS` serializes nulls in this project).
  - [x] Assertions on `code` + `status` + machine-readable `details` only — never on human `title` / `detail`.

- [x] **Task 8: `WorkflowInspectionServiceAllowedActionsTest` — state-matrix coverage** (AC: 3, 4, 8)
  - [x] Created `WorkflowInspectionServiceAllowedActionsTest` (Mockito + constructor-wired service).
  - [x] **Parameterized test** `matrixCoversEveryStateAndRow` over `Arguments.of(WorkflowState, role, expectedActions)` — 22 cases (every state × {product_reviewer, workflow_owner}).
  - [x] Dedicated tests:
    - `waitingForSpecApprovalWithPendingClarificationsDropsApproveSpec` (AC4 pin)
    - `waitingForSpecApprovalWithZeroPendingClarificationsKeepsApproveSpec`
    - `investigatingWithOpenClarificationIncludesAnswerClarification`
    - `investigatingWithZeroOpenClarificationsIsViewOnlyOnly`
    - `failedAsProductReviewerYieldsViewOnlyAndDiagnostics`
    - `failedAsWorkflowOwnerYieldsRetryAndDiagnostics`
    - `versionStampReflectsLatestSpecArtifactVersionEvenWhenUnapproved` (TRAP 2 pin — uses `ArtifactStatus.PENDING`, the closest "in-flight, not-yet-approved" status in the current registry; the more-explicit `DRAFTED` constant is reserved for a future story).
    - `versionStampSpecAndBundleAreNullWhenNoSpecExists` (TRAP 2 pin)
    - `versionStampBundleIsNullWhenSpecExistsButRunnerExecutionMissing`
    - `versionStampLastEventIdIsLatestEventPublicId`
    - `unknownActorRoleThrowsUnknownActorRoleDomainException`
    - `blankActorRoleDefaultsToProductReviewer`
    - **`futureStateGuard_everyWorkflowStateHasNonEmptyActionSet`** — iterates `WorkflowState.values()` and asserts non-empty action sets + no `IllegalStateException`.
  - [x] Order-sensitive `containsExactly(...)` throughout. 35 tests pass.

- [x] **Task 9: Regenerate OpenAPI snapshot + frontend client** (AC: 10)
  - [x] Backend snapshot regenerated via direct `mvnw failsafe:integration-test -Dit.test=OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true` (Testcontainers + Postgres reachable on this host); 130 lines added to `openapi.json` including the new path, two new schemas, and the new `UNKNOWN_ACTOR_ROLE` reference. Local PowerShell + Docker Desktop sufficed; WSL2 not required.
  - [x] Frontend client regenerated via `npm run generate-api` in `deliveryline-frontend/`; 109 lines added to `schema.d.ts` including `operations["getAllowedActions"]`, `components["schemas"]["AllowedActions"]`, `components["schemas"]["AllowedActionsVersionStamp"]`.
  - [x] `npm run check:api` reports `generated client is in sync with the committed OpenAPI snapshot.` OpenApiSnapshotContractTest passes without `-Dopenapi.snapshot.write=true`.

- [x] **Task 10: Delete the `TODO(story-2.14)` marker** (AC: 2)
  - [x] Removed the 3-line comment; the 8 enum values are unchanged.

- [x] **Logging instrumentation** (cross-cutting; required on every story — **JVM story, fully applicable**)
  - [x] SLF4J logs shipped at the four required surfaces; parameterized logging throughout.
  - [x] MDC context: `workflowRunId` via the existing `MdcKeys.beginScope` idiom; `actorRole` carried as a per-call log parameter.
  - [x] No secrets / no payload bytes / no PII — endpoint reads no user-supplied payloads beyond the query param.
  - [x] Pinned by two list-appender tests: `WorkflowInspectionServiceAllowedActionsLoggingTest` (service-side INFO entry+success + UNKNOWN_ACTOR_ROLE WARN) and a new test inside `WorkflowControllerLoggingContractTest` (controller-side INFO entry+success).

## Dev Notes

### Story scope — one new method, one new endpoint, one new error code

This story turns the dormant `AllowedAction` registry into a live read surface the UI Approval/Decision Bar (2.19) consumes to drive every per-run action affordance. **No domain logic changes, no state-machine changes, no writes, no idempotency surface, no new central registry values** (the 8 are already registered). Everything else — the version-stamp data shape, the gating semantics (`pendingClarifications == 0` for approve), the run-summary read — is already implemented in `WorkflowInspectionService`. You are composing those pieces behind a stable application-service method, exposing it via a read-only REST endpoint, and locking the boundary with ArchUnit.

### Critical context the implementer needs

- **Hexagonal layout** — `application.workflow` owns the decision matrix; `adapters.rest` owns wire mapping only (and is the ONLY adapter package permitted to reference `AllowedAction`, via one DTO `from(...)` factory).
- **State source of truth** — `WorkflowRunSnapshot.currentState()` is the only state read; never derive from event-stream tail or from the latest `resultingState`. State transitions are produced by `WorkflowCommandService` and persisted; this service reads the row.
- **Pending-clarification gating** — the count `WorkflowRunDetailedSummaryView.pendingClarifications` is computed by `clarificationReadPort.countPendingByWorkflowRun(...)` and excludes `incorporated` + `rejected_invalid` (Story 2.12 semantics). DO NOT recount. DO NOT use a different definition. AC4 inherits 2.12's definition verbatim.
- **Open-clarification check (Investigating only)** — different semantic from the gating count: AC3's *Investigating* row asks "are there any **open** (unanswered) clarifications on the latest in-flight spec?" so `answer_clarification` is offered. Use `clarificationReadPort.listByArtifactId(latestSpecPublicId)` and filter `status.equals("open")`. Add a code comment distinguishing the two semantics — they look similar but answer different UX questions.
- **Version stamp source of truth** — TRAP 2 details. Latest spec artifact (any status), not last approved. `RunnerExecutionSnapshot.contextBundleVersion()` is the field — already a typed Integer in the existing snapshot record (verify by reading the snapshot class; it's used by `getContextBundleLookupForArtifact`).
- **Why `UNKNOWN_ACTOR_ROLE` is new** — every existing 4xx for read endpoints is `INVALID_ID_PREFIX` / `RUN_NOT_FOUND`. There is no existing code for "the supplied query param is well-formed but semantically unrecognized." Adding one is the right ergonomics for the frontend (lets it surface a typed error rather than a 500 / silent default).
- **Why not reuse `ApprovalReviewerRoleResolver`** — that resolver was built for the spec-approval mutation path where a missing/blank role MUST resolve to a working default so the mutation can proceed. The allowed-actions endpoint has the opposite contract: an **invalid** role must be visible to the caller (AC7) so the UI can fail closed. Sharing the resolver would silently mask bugs.
- **OpenAPI regen + frontend client** — the same flow story 2.13 ran. Memory `openapi-regen-platform-shim.md` documents the cross-shell coordination needed on Windows.

### Architecture-prescribed rules (`_bmad-output/planning-artifacts/architecture.md`)

- **API format patterns:** camelCase JSON, ISO-8601 UTC, public-prefixed IDs (`run_`, `evt_`), direct resource shapes (no `{data: …}` envelope). All inherited automatically by following the story 6.9 / 2.13 patterns.
- **Hexagonal boundaries:** `adapters.rest` → `application.workflow` only; never `adapters.persistence` or runner adapters directly. The new ArchUnit rule (Task 6) is additive — verify the existing `LAYERED_BOUNDARIES` / `REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_PERSISTENCE_OR_EXTERNAL_ADAPTERS` rules continue to pass.
- **UX-DR12 frontend rule:** "frontend code must not gate actions based on audit role labels — all action gating goes through `useAllowedActions`" (architecture / UX spec line ~9 in the 2.15 / 2.18 / 2.19 AC sets). This story is the backend half of that contract. Breaking it (e.g., letting a controller compute actions) silently weakens the rule even if the frontend test catches the local violation.
- **Read endpoint conventions:** `produces = MediaType.APPLICATION_JSON_VALUE`, no `consumes`, `@RequestMapping("/api/v1/workflows")` shared base path (already on the controller), `@Operation` + `@ApiResponses` annotations referencing `ProblemDetailsResponse.class` for every 4xx/5xx (story 6.9 + 2.13 set the template).
- **CorrelationIdFilter** at `infrastructure/observability/CorrelationIdFilter.java` already echoes `X-Correlation-Id` on every response (story 1.19); MDC is populated; no new wiring needed for this endpoint.

### Previous story intelligence (from 2.13 and 6.9)

- **From 2.13** — `LocalActorIdentityResolver` is now in `application.security` so both REST and CLI share it (commit `2fbb4a8`). The allowed-actions endpoint does NOT take `X-Actor-Identity` (read-only, no actor stamping needed), but the controller class already injects the resolver and you should leave the constructor untouched. Round-3 hardening lessons that apply: sanitize every controller-side log field through `MdcKeys.sanitizeForLog(...)` to prevent log-injection via URL-encoded CR/LF (the workflowRunId path-param has already been validated by `PublicIdPrefixes.require` in the service, but the controller-side INFO entry log fires before that, so sanitize at the controller).
- **From 2.13** — `@JsonIgnoreProperties(ignoreUnknown = false)` + `spring.jackson.deserialization.fail-on-unknown-properties: true` is now the project-wide default for request DTOs (commit `b079c0f`, round-3 D3 patch). The allowed-actions endpoint has no request body, so this doesn't apply directly, but if you add any future request DTO in this story (you shouldn't), follow the same pattern.
- **From 6.9** — `WorkflowInspectionService.listRuns` + `getEventStream` + `WorkflowRunSummaryView` shipped with the localhost binding. The controller's three GET endpoints (lines 95-194) are your copy-paste template for the new endpoint. The `WorkflowEventsResponse.from(WorkflowEventStreamView)` factory is the wire-mapping template for `AllowedActionsResponse.from(AllowedActionsView)`.
- **From 6.9** — `springdoc-openapi-starter-webmvc-ui 3.0.3` + `springdoc-openapi-maven-plugin 1.5` are wired (memory `springdoc-boot4-version.md`). The committed snapshot at `deliveryline-backend/src/main/resources/openapi/openapi.json` is the canonical contract; the CI drift gate at `.github/workflows/ci.yml` performs a real diff (not skipping).
- **From 2.12** — `getRunSummary` returns `pendingClarifications`. The N+1 trade-off (one count query per row in `listRuns`) is accepted for MVP queue scale per Story 2.12 OQ-4 + Trap T12. Your single-row call has no such concern.

### Git intelligence — recent patterns

Last 5 commits (most recent first):
- `a2ada93 feat(2-24): artifact content sanitization + F19/F20 redaction-gap closure` — the just-closed story.
- `ba1115f style: apply spotless formatting after LocalActorIdentityResolver move` — confirms Spotless formatting is enforced. Run `./mvnw spotless:apply` before commit.
- `2fbb4a8 fix(arch): move LocalActorIdentityResolver to application.security` — current location of the resolver; matches what `WorkflowController` already imports.
- `bdd4d5d fix(test): wait for slot-registration width transition in AppShell test` — frontend test stabilization; not relevant to this backend story.
- `16e5c0d fix(test): rename MDC propagation test to *ContractTest so failsafe picks it up` — important convention: `*ContractTest` is the Failsafe-picked test naming. Follow this naming for `AllowedActionsEndpointContractTest` (already named correctly above).

Pattern signals across the last ~10 commits:
- Heavy Spotless usage — formatting runs are common follow-ups; merge spotless into the same commit when possible.
- Story-suffixed commit subject format (`feat(2-24): ...`, `feat(rest): story 2.13 ...`). Use `feat(2-14): backend allowed-actions inspection endpoint` for the implementation commit.
- Tests live in mirrored packages: `application/workflow/` → `application/workflow/` test mirror, `adapters/rest/` → `adapters/rest/` test mirror. Maintain that for the new test classes.

### Anti-patterns to avoid

- **Do NOT put the state×role switch in the controller.** AC9 / ArchUnit / UX-DR12 / TRAP 3 all converge on one rule: derivation lives in `WorkflowInspectionService` only.
- **Do NOT compute `currentSpecArtifactVersion` from `getCurrentApprovedSpec`.** TRAP 2. Use the latest-artifact accessor; the version field is what changes during reject-and-resubmit loops, which is exactly when stamp/UI drift is most likely.
- **Do NOT add new `AllowedAction` enum values.** Registry already has the 8 needed. Adding more breaks the drift contract (`RegistryContractTest:88`) and Story 2.9's pin. Epic 3 / Epic 4 will add new ones in their respective stories.
- **Do NOT use `ApprovalReviewerRoleResolver` for actorRole validation.** It silently defaults; AC7 requires hard rejection. Use a small `RecognizedActorRoles` set inside `WorkflowInspectionService` and throw on miss.
- **Do NOT make the endpoint idempotency-keyed or actor-stamped.** AC6 explicitly says no Idempotency-Key, no X-Actor-Identity. It's a read; matching the mutation-endpoint header model would be over-engineering.
- **Do NOT silently swallow context-bundle-lookup misses.** The existing `getContextBundleLookupForArtifact` already WARN-logs each miss reason — those propagate; just translate "not available" into `currentContextBundleVersion = null` on the stamp and move on. Do not bubble those misses as endpoint errors (AC5 explicitly says null is valid for missing bundle / no spec yet).
- **Do NOT change `WorkflowState` enum order or add states.** The future-state guard test in AC8 will catch new states *if* you forget to update the switch. If a new state arrives in this story's lifecycle, update the switch — don't disable the guard.
- **Do NOT regress `AllowedActionRegistryPinTest`.** That story-2.9 pin remains valid and necessary. The new ArchUnit rule must whitelist it (or whitelist all test-package references, which is the cleaner approach).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. **This is a JVM application + adapter story, so the standard fully applies.**

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface this story adds):**
  - `WorkflowInspectionService.getAllowedActions` → `INFO` on entry (with `workflowRunId`, `actorRole`); `INFO` on success (with `workflowState`, `actionCount`, `versionStampLastEventId`); `WARN` on `UNKNOWN_ACTOR_ROLE` rejection (mirror `clarificationNotFound` WARN shape).
  - `WorkflowController.getAllowedActions` → `INFO` entry/success mirroring story 6.9 read endpoints; **every controller-side log field through `MdcKeys.sanitizeForLog(...)`** per story 2.13 round-3 P-fix.
- **Required context keys** (MDC or structured params): `correlationId` (already in MDC), `workflowRunId` (established via `MdcKeys.beginScope`), `actorRole` (per-call parameter).
- **Forbidden in log output:** no secrets / no payload bytes / no PII — N/A here since the endpoint reads no user-supplied payloads beyond the query param.
- **Test contract:** pin new logging surfaces with at least one focused test (list-appender or `OutputCaptureExtension`) — successful read + UNKNOWN_ACTOR_ROLE WARN.

### Project Structure Notes

- **New (backend):**
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/AllowedActionsResponse.java` (new DTO record)
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/AllowedActionsVersionStampResponse.java` (new DTO record)
  - `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/AllowedActionsEndpointContractTest.java` (new contract test)
  - `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java` (new unit test)

- **Modified (backend):**
  - `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — new method + two nested records (Task 2-3)
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` — one new `@GetMapping` method (Task 4)
  - `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` — new enum value `UNKNOWN_ACTOR_ROLE` (Task 1)
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` — register new error code (Task 1)
  - `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` — delete `TODO(story-2.14)` comment block (Task 10)
  - `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` — new rule constant (Task 6)
  - `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` — wire new rule via `@ArchTest` (Task 6)
  - `deliveryline-backend/src/main/resources/openapi/openapi.json` — regen (Task 9)
  - `deliveryline-frontend/src/lib/api/schema.d.ts` — regen via `npm run generate-api` (Task 9)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` — flip `2-14-*` from `backlog` to `ready-for-dev`, then later to `review` / `done` by the dev / review cycle

- **No conflicts with hexagonal structure** — service in `application.workflow`, DTOs in `adapters.rest`, error code in `domain.registry`. ArchUnit boundaries hold.

### References

- Story foundation: `_bmad-output/planning-artifacts/epics.md` § Story 2.14 (lines 1168-1186 — full AC text)
- Predecessor story (REST patterns): `_bmad-output/implementation-artifacts/2-13-backend-rest-mutation-endpoints-and-openapi.md` (full file — every annotation, test, regen step, and Spring Shell pattern)
- Predecessor story (read-endpoint patterns): `_bmad-output/implementation-artifacts/6-9-localhost-rest-binding-and-workflow-read-endpoints.md` § Task 1, Task 4, Dev Notes
- Predecessor story (gating signal): `_bmad-output/implementation-artifacts/2-12-backend-visible-incorporation-lifecycle-states-and-event-wiring.md` § AC9 + `pendingClarifications` semantics
- AllowedAction enum (already shipped): `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` — all 8 values registered; pin test at `deliveryline-backend/src/test/java/org/dradgo/architecture/AllowedActionRegistryPinTest.java`
- Registry parity test: `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java:88` (`DomainRegistry.allowedActions()` parity)
- Service base class: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — getRunSummary (line 217), getCurrentApprovedSpec (487), getContextBundleLookupForArtifact (612), runNotFound (887), MDC scope idiom (e.g. line 184-208)
- Controller base class: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` — getWorkflow (125-156), getWorkflowEvents (158-194), ProblemDetailsResponse import pattern, MdcKeys.sanitizeForLog usage (round-3 P-fix references)
- Architecture: `_bmad-output/planning-artifacts/architecture.md` § API format patterns (camelCase, ISO-8601 UTC, direct shapes), § Frontend-facing guardrails (UX-DR12), § OpenAPI publishing (line ~201-204)
- Sprint plan reference: `_bmad-output/implementation-artifacts/sprint-status.yaml:107` (`2-14-backend-allowed-actions-inspection-endpoint`)
- Memory: `openapi-regen-platform-shim.md` (WSL2-vs-PowerShell shell coordination for OpenAPI regen); `springdoc-boot4-version.md` (springdoc version for Boot 4); `verify-ci-fixes-in-clean-env.md` (CI vs local reproduction)

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (1M context)

### Debug Log References

- `mvn -pl deliveryline-backend test -Dtest='WorkflowInspectionServiceAllowedActionsTest,WorkflowInspectionServiceAllowedActionsLoggingTest,AllowedActionsEndpointContractTest,WorkflowControllerLoggingContractTest'` → 49/0/0
- `mvn -pl deliveryline-backend test` (full surefire) → 524/0/0 (3 skipped, pre-existing)
- `mvn -pl deliveryline-backend org.jacoco:jacoco-maven-plugin:prepare-agent failsafe:integration-test -Dit.test='ArchitectureBoundaryTest'` → 32/0/0
- `mvn -pl deliveryline-backend org.jacoco:jacoco-maven-plugin:prepare-agent failsafe:integration-test -Dit.test='OpenApiSnapshotContractTest'` → 1/0/0 (in-sync verify after regen)
- `mvn -pl deliveryline-backend spotless:apply checkstyle:check` → 7 files reformatted, 0 checkstyle violations
- `npm run check:api` (frontend) → ✅ generated client in sync with committed snapshot

### Completion Notes List

- ✅ All 11 tasks (10 numbered + Logging instrumentation) complete end-to-end.
- ✅ **AC1-AC11 all satisfied.** Sole new error code: `UNKNOWN_ACTOR_ROLE` (400, retryable=false). Single state×role matrix encoded in `WorkflowInspectionService.computeActionMatrix` and pinned by a parameterized test (22 rows × 2 roles = 22 cases) plus the AC8 future-state guard.
- ✅ **TRAP 1 honored** — no executor / endpoint wiring for `clear_escalation_marker`; that registry value stays dormant and is not surfaced in any matrix row.
- ✅ **TRAP 2 honored** — version stamp derives `currentSpecArtifactVersion` from `findLatestByWorkflowRunIdAndArtifactType(...)` (any status) NOT from `getCurrentApprovedSpec`. Three dedicated tests pin the trap: latest-unapproved spec surfaces, no-spec returns null, bundle-missing returns null with spec version intact.
- ✅ **TRAP 3 honored** — `AllowedAction` enum constants are imported in exactly two `.java` files inside `application..` + `adapters..`: `WorkflowInspectionService` and `AllowedActionsResponse`. Locked by the new ArchUnit rule `allowed_action_derivation_lives_only_in_workflow_inspection_service`.
- ✅ **No new `AllowedAction` enum values added.** Registry parity test stays green; the legacy `TODO(story-2.14)` comment block was the only line removed from `AllowedAction.java`.
- ✅ **OpenAPI snapshot drift gate passes.** `OpenApiSnapshotContractTest` runs green without `-Dopenapi.snapshot.write=true`; frontend `check:api` confirms `schema.d.ts` is in sync. The new endpoint contributes 130 lines to `openapi.json` and 109 lines to `schema.d.ts`.
- ⚠️ **Carryover deferral noted in tests** — the TRAP-2 latest-unapproved test uses `ArtifactStatus.PENDING` because the registry does not currently expose a `DRAFTED` status. The test's intent (spec surfaced before any approval row exists) is preserved; if a future story introduces a more explicit `DRAFTED` constant, that test would be the obvious place to switch.
- ℹ️ Not re-run locally on WSL2 Ubuntu — `OpenApiSnapshotContractTest` requires Testcontainers + Postgres, which worked from the Windows PowerShell + Docker Desktop combination on this host. Per memory `verify-ci-fixes-in-clean-env.md`, a WSL2 dry-run before pushing is still recommended for the SpringBootTest contract slice.

### File List

**New (backend main):**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/AllowedActionsResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/AllowedActionsVersionStampResponse.java`

**New (backend test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/AllowedActionsEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsLoggingTest.java`

**Modified (backend main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — new `getAllowedActions(...)` + `computeActionMatrix(...)` + `resolveActorRole(...)` + `hasOpenClarificationOnArtifact(...)` helpers + 2 nested public records + 2 nested constants.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` — new `@GetMapping(/{workflowRunId}/allowed-actions)` method with INFO logging + sanitized fields.
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` — new `UNKNOWN_ACTOR_ROLE` enum value.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` — registered `UNKNOWN_ACTOR_ROLE` → 400, retryable=false.
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` — deleted 3-line `TODO(story-2.14)` block.
- `deliveryline-backend/src/main/resources/openapi/openapi.json` — regenerated (+130 lines).

**Modified (backend test):**
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` — new `ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE` rule constant + `AllowedAction` import.
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` — wired new rule via `@ArchTest`.
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/WorkflowControllerLoggingContractTest.java` — added `allowedActionsEntryLogIncludesWorkflowRunIdAndActorRole` test + `get` import + `eq` import + 2 new application-layer imports.

**Modified (frontend):**
- `deliveryline-frontend/src/lib/api/schema.d.ts` — regenerated (+109 lines).

**Modified (planning artifacts):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `2-14-*` flipped `ready-for-dev → in-progress → review`.
- `_bmad-output/implementation-artifacts/2-14-backend-allowed-actions-inspection-endpoint.md` — Status, Tasks/Subtasks checkboxes, Dev Agent Record, File List, Change Log.

### Change Log

- 2026-05-27 — Story 2.14 dev-story completed; flipped from `ready-for-dev → review`. All 11 tasks + AC1-AC11 satisfied end-to-end. 49 net new tests (35 service-level + 8 endpoint contract + 2 logging-pin + +1 controller-logging + +3 ArchUnit additions absorbed by `ArchitectureBoundaryTest`); full backend surefire 524/0/0; OpenApiSnapshotContractTest in-sync after regen; frontend `check:api` green.
- 2026-05-27 — bmad-code-review walkthrough (Blind Hunter / Edge Case Hunter / Acceptance Auditor): 43 raw findings → 16 patches applied, 2 decisions deferred (ArchUnit rule-shape, Investigating-no-spec branch), 7 items deferred to `deferred-work.md`, 13 dismissed (spec-mandated or pattern-consistent). Patches landed: MDC scope reorder + service-side log sanitization; bare role literals replaced with `ROLE_PRODUCT_REVIEWER` / `ROLE_WORKFLOW_OWNER` constants; `@JsonInclude(ALWAYS)` on `AllowedActionsVersionStampResponse`; AC11 forward-compat Javadoc paragraph added to controller; `@Schema(allowableValues=…)` on `actorRole` query param; controller-side `actorRole` strip-normalization so controller + service log surfaces match; ArchUnit `AllowedActionsResponse` exemption regex widened with `(\\$.*)?`; misleading bundle-bytes stub clarified to opaque-non-empty payload; future-state guard hardened (both roles + no-throw assertion); +5 new service-unit tests (case-variant, empty-string, lastEventId-null, recognized-set membership, workflow_owner+open-clarification); +1 new endpoint-contract test (case-variant); OpenAPI snapshot + frontend `schema.d.ts` regenerated for the actorRole enum constraint. 55/55 tests green across the four impacted classes, 32/32 ArchUnit boundary tests green, Spotless + Checkstyle clean. Status flipped from `review → done`.

### Review Findings

_Code review run 2026-05-27 (bmad-code-review, 3 layers: Blind Hunter / Edge Case Hunter / Acceptance Auditor). 43 raw findings → 16 patches, 2 decisions, 7 deferred, 13 dismissed (spec-mandated or pattern-consistent)._

**Decisions (resolved 2026-05-27, both deferred):**

- [x] [Review][Defer] ArchUnit rule shape: force typed-view callers to re-stringify at boundary, or widen `AllowedAction` exemption? — deferred: no real consumer yet — revisit on first friction. (Blind #14, #20)
- [x] [Review][Defer] `INVESTIGATING` + open clarification on a run with no spec yet — should `answer_clarification` surface? — deferred: add to Epic 2b backlog — revisit with clarification UX work (story 2-18). (Edge E3)

**Patches:**

- [x] [Review][Patch] Test stubs unused bundle bytes in `versionStampReflectsLatestSpecArtifactVersionEvenWhenUnapproved` — the helper stubs `scratchStore.tryReadContextBundle` returning `{"contextBundleVersion":1}` but the test asserts `12` (from `RunnerExecutionSnapshot`). The bundle stub is dead and would mask a future regression if the implementation switched data sources. [`WorkflowInspectionServiceAllowedActionsTest.java:~1322`]
- [x] [Review][Patch] `MdcKeys.beginScope(WORKFLOW_RUN_ID, ...)` opens after both validation calls, so `UNKNOWN_ACTOR_ROLE` and `INVALID_ID_PREFIX` rejection paths have no MDC `workflowRunId`. Move the scope open before validation (with the run id from the path variable, sanitized). [`WorkflowInspectionService.java:~256`]
- [x] [Review][Patch] Service-side INFO/WARN logs pass `workflowRunPublicId` and `actorRole` verbatim — `MdcKeys.sanitizeForLog(...)` is applied at the controller but not at the service entry/WARN. Log-injection vector through `?actorRole=foo%0D%0A...`. [`WorkflowInspectionService.java:~261,~319`]
- [x] [Review][Patch] Matrix switch uses bare string literals `"product_reviewer".equals(actorRole)` / `"workflow_owner".equals(actorRole)` while `RECOGNIZED_ACTOR_ROLES` holds the same strings. Single-source-of-truth promise weakens on rename. Extract `static final String` constants (or a typed enum). [`WorkflowInspectionService.java:~225,~352,~374`]
- [x] [Review][Patch] Future-state guard test (`futureStateGuard_everyWorkflowStateHasNonEmptyActionSet`) only asserts `isNotEmpty()` for role=`product_reviewer`. Strengthen: assert no `IllegalStateException` is thrown AND iterate both recognized roles, so a `workflow_owner`-only matrix gap is caught. [`WorkflowInspectionServiceAllowedActionsTest.java:~1422`]
- [x] [Review][Patch] Null version-stamp fields rely on project-wide `Include.ALWAYS` Jackson default. Pin explicitly: add `@JsonInclude(JsonInclude.Include.ALWAYS)` to `AllowedActionsVersionStampResponse` (or assert field-present-as-null in the contract test, not just `nullValue()` which also passes when absent). [`AllowedActionsVersionStampResponse.java`]
- [x] [Review][Patch] AC11 controller-Javadoc paragraph missing — the "UI must gracefully handle unknown action values by hiding them (UX-DR12 + UX-DR6)" contract lives on the DTO `@Schema` instead of the controller Javadoc as AC11 specifies. Add the paragraph to `WorkflowController.getAllowedActions`. [`WorkflowController.java:~138`]
- [x] [Review][Patch] AC7 case-sensitivity is unpinned — `UNKNOWN_ACTOR_ROLE` tests use `"auditor"` only. Add a case-variant test (`"Product_Reviewer"` → 400) at both service-unit and endpoint-contract layers. [`WorkflowInspectionServiceAllowedActionsTest.java:~1382`, `AllowedActionsEndpointContractTest.java:~742`]
- [x] [Review][Patch] OpenAPI `actorRole` query param has no `allowableValues` — generated TS clients accept any string. Add `@Schema(allowableValues = {"product_reviewer", "workflow_owner"})` on the `@RequestParam` or via `@Parameter`. Regen snapshot + schema.d.ts. [`WorkflowController.java:~243`]
- [x] [Review][Patch] `actorRole` normalization split — controller logs raw param, service logs trimmed value. Grep for `actorRole=workflow_owner` then misses whitespace-padded requests. Normalize once at controller boundary so both log surfaces see the same string. [`WorkflowController.java:~178` vs `WorkflowInspectionService.java:~263`]
- [x] [Review][Patch] Dead `@Import(ApprovalReviewerRoleResolver.class)` in `AllowedActionsEndpointContractTest` — the spec explicitly says this endpoint does NOT use that resolver. Copy-paste from approval test; remove. [`AllowedActionsEndpointContractTest.java:~663`]
- [x] [Review][Patch] ArchUnit `AllowedActionsResponse` whitelist regex lacks `(\\$.*)?` — an inner record / nested helper of `AllowedActionsResponse` would trip the rule incorrectly. Match the `WorkflowInspectionService` exemption shape. [`ArchitectureRuleCatalog.java:~1576`]
- [x] [Review][Patch] No test exercises `?actorRole=` (empty value, present key). `isBlank()` already handles it, but Spring binding may deliver `null` vs `""` differently across versions. Add an explicit contract test. [`AllowedActionsEndpointContractTest.java`]
- [x] [Review][Patch] No service-unit test stubs `events.findLatestByWorkflowRunPublicId(...)` returning `Optional.empty()` — the `lastEventId = null` branch (documented as "unreachable defensive") is unpinned. A future `.orElseThrow` would slip in. [`WorkflowInspectionServiceAllowedActionsTest.java`]
- [x] [Review][Patch] No test pins `RECOGNIZED_ACTOR_ROLES` set membership — adding a third role string passes existing `UNKNOWN_ACTOR_ROLE` tests because they only check `"auditor"`. Add a tiny set-membership assertion. [`WorkflowInspectionServiceAllowedActionsTest.java`]
- [x] [Review][Patch] Parameterized matrix `matrixCases()` stubs `stubNoLatestSpec()` for every row, so the `INVESTIGATING` + open-clarification AC3 branch is exercised only by the dedicated `product_reviewer`-only test. Add a `workflow_owner` variant (or extend the parameterized matrix with the clarification-present rows). [`WorkflowInspectionServiceAllowedActionsTest.java:~1157,~1264`]

**Deferred (real but out-of-scope for this story):**

- [x] [Review][Defer] `WorkflowInspectionService` returns 500 if `summary.currentState()` is null in `getRunSummary` — pre-existing defensive gap in the inspection service shared by all read endpoints. [`WorkflowInspectionService.java`]
- [x] [Review][Defer] `currentContextBundleVersion` Java type is `Integer` (int32) — silent truncation risk if any upstream port widens to `long`. Type chain audit, not story-2.14 scope.
- [x] [Review][Defer] `pendingClarifications` race between `getRunSummary` count and subsequent spec/event reads — project-wide read-consistency concern under READ COMMITTED; affects every inspection endpoint.
- [x] [Review][Defer] `contextBundleVersion = 0` may be indistinguishable from "absent" in the snapshot — need upstream port audit to confirm whether `0` is a valid sentinel.
- [x] [Review][Defer] No defense against multiple SPEC artifacts with the same `version` (DB unique-constraint concern; "latest" is adapter-order-dependent).
- [x] [Review][Defer] ArchUnit `ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE` rule scope = `application+adapters` only; `infrastructure..` / `cli..` packages can directly reference `AllowedAction` without tripping the rule. Broader rule-scope policy decision.
- [x] [Review][Defer] Dev-record Task 9 bullet overstates the OpenAPI representation of `UNKNOWN_ACTOR_ROLE` — it appears only in the 400 description prose, not as a discriminator/enum/oneOf schema. Doc-accuracy nit; pattern-consistent with `INVALID_ID_PREFIX` elsewhere.
