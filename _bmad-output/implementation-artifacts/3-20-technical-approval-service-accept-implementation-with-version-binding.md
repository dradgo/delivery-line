# Story 3.20: Technical Approval Service — `acceptImplementation` with Version Binding

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Developer reviewing implementation output (plan or PR/output),
I want `TechnicalApprovalService.acceptImplementation(...)` that binds technical approval to a specific artifact version + context bundle version + actor identity + reviewer role (`developer`),
so that **FR16** (developer accepts merge-ready) is wired with the same version-binding rigor as story 2.9, and **FR21** (separate product/technical acceptance states) is preserved at the data layer and surfaced as distinct typed fields.

## Acceptance Criteria

1. **Given** an `application.approval` package extension, **Then** a new `TechnicalApprovalService` exposes `acceptImplementation(AcceptImplementationCommand command) → ApprovalResult`. `TechnicalApprovalService` is the **canonical executor** for the `accept_implementation` action.
2. **Given** the story 1.7 shared command-model pattern (`WorkflowCommand` sealed interface), **Then** `AcceptImplementationCommand` is a new `WorkflowCommand` carrying: `workflowRunId` (`run_…`), `artifactId` (`art_…`, **must resolve to `implementationPlan` or `prOutput`, never `spec`**), `artifactVersion` (= expectedArtifactVersion, `@Positive`), `contextVersion` (= expectedContextBundleVersion, `@Positive`), `actorIdentity`, `actorType` (`= ActorType.HUMAN`), `reviewerRole` (`@NotBlank`, max 128 — `developer`), optional `reason` (max 1024), `idempotencyKey`, `correlationId`. **Keep the short field names `artifactVersion`/`contextVersion`** to mirror the existing `ApproveSpecCommand`/`RejectSpecCommand` records (Trap T1).
3. **Given** the V1 `approvals` table, **Then** a successful approval inserts a row via the **existing** `ApprovalWritePort.insert(NewApproval)`: `public_id` (`apr_…`), `workflow_run_id` (FK), `artifact_id` + `artifact_version` (composite FK pin), `context_bundle_version`, `actor_identity`, `actor_type`, `reviewer_role='developer'`, `decision='approved'`, `reason` (nullable), `rejection_taxonomy=null` (per the `ck_approvals_decision_taxonomy_paired` CHECK), `decided_at=now()`, `idempotency_key`. **No new Flyway migration** — the schema already accommodates technical approvals.
4. **Given** **FR21 separate product/technical acceptance states**, **Then** `WorkflowInspectionService.getRunSummary` returns `productApprovalState` + `technicalApprovalState` as distinct typed fields on `WorkflowRunDetailedSummaryView` — never collapsed. `productApprovalState` derives from the latest approved `spec` lineage; `technicalApprovalState` derives from the latest approved `implementationPlan`/`prOutput` lineage. (REST/CLI/UI surfacing of these two fields is **deferred** to stories 3.23 / 3.28 / 3.31 per the 2.12 `pendingClarifications` precedent — see Trap T8 + OQ-3.)
5. **Given** version-binding enforcement, **When** the artifact's current version differs from `artifactVersion` OR the run's current context-bundle version differs from `contextVersion`, **Then** raise `DomainException(APPROVAL_VERSION_MISMATCH, …)` with `details` carrying `expectedArtifactVersion`, `currentArtifactVersion`, `expectedContextBundleVersion`, `currentContextBundleVersion`, `artifactId`, `workflowRunId` — identical `details` shape to story 2.9 AC4. No DB writes, no event, no transition. Run version-binding **before** eligibility + PR-link checks (Trap T3).
6. **Given** approval-eligibility gating (story 1.12 AC6 `ArtifactService.isApprovalEligible`) **and** the GitHub PR-link gate (story 3.15 AC5 `IntegrationLinkService.assertArtifactPrLinkMatches`), **When** the artifact is not `available` **Then** raise `ARTIFACT_PAYLOAD_UNAVAILABLE`; **When** approving a `prOutput` whose PR ref does not match the active `github_pr` link **Then** `assertArtifactPrLinkMatches` raises `ARTIFACT_PR_LINK_MISMATCH`. The PR-link gate runs **only** for `prOutput` (an `implementationPlan` has no PR yet). See OQ-1 for the prOutput PR-ref source.
7. **Given** successful approval of a `prOutput` artifact, **Then** in **one transaction**: `approvals` row inserted with `reviewer_role=developer`; `WorkflowEventType.APPROVAL_APPROVED` event appended with `details.reviewerRole=developer`; `WorkflowTransitionService.transition(workflowRunId, COMPLETED, actor, "accept implementation", idempotencyKey, eventDetails)` — fulfilling the merge-ready handoff path. The COMPLETED transition's post-commit hook (story 3.16) auto-fires the Linear completion sync; **3.20 wires no Linear call directly** (Trap T6).
8. **Given** successful approval of an `implementationPlan` artifact, **Then** the transition target is `EXECUTING` (**not** `COMPLETED`) and, **after** the transition, `WorkflowOrchestrationService.dispatchImplementation(workflowRunId, correlationId)` (story 3.12) is invoked **inside the same transaction** to enqueue the PR/output runner (story 3.17). `dispatchImplementation` never transitions (it relies on this service having already moved the run to `EXECUTING`).
9. **Given** **FR46** attribution, **Then** the persisted `approvals.reviewer_role` and the `approval.approved` event's `details.reviewerRole` both carry `developer` — distinct from story 2.9's `product_reviewer`. A contract test asserts the event's `details.reviewerRole` equals the persisted row's `reviewer_role`.
10. **Given** idempotency (story 1.9), **Then** a new `WorkflowCommandService.acceptImplementation` entry point delegates to `executeIdempotent(...)` exactly like `approveSpec`; `TechnicalApprovalService.acceptImplementation` runs inside the reserved transaction. Retries with the same `idempotencyKey` + identical fingerprint replay the prior result; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`. The DB-level `uq_approvals_idempotency_key` UNIQUE is the defense-in-depth backstop (mapped in the existing `ApprovalWritePersistenceAdapter`). **Replay must re-read the run's current state** — the resulting state differs by artifact type (Trap T2).
11. **Given** an invalid current state (run not in `WAITING_FOR_REVIEW`), **When** the transition is attempted, **Then** `WorkflowTransitionTable` raises `DomainException(ILLEGAL_TRANSITION, …)` which propagates out of `TechnicalApprovalService`; because the transition runs **after** the row insert + event append in the same transaction, the entire transaction rolls back (no orphan row, no orphan event).
12. **Given** allowed-actions integration (story 2.14), **Then** a new `AllowedAction.ACCEPT_IMPLEMENTATION("accept_implementation")` is registered; `developer` is added to the recognized actor-role set; and `WorkflowInspectionService.computeActionMatrix` returns `accept_implementation` for `state=WAITING_FOR_REVIEW` + `role=developer`. The registry drift test + frontend placeholder fixture are updated in lockstep (Trap T7). `reject_implementation` (story 3.21) and `takeover` (story 3.22) are **out of scope** — the matrix seam stays open for them.
13. **Given** contract tests, **Then** cover: happy-path approval of `implementationPlan` → `Executing` + `dispatchImplementation` invoked + event; happy-path approval of `prOutput` → `Completed` + event + completion-sync hook registered; reject-spec-artifact (`artifactId` resolves to `spec`) → `INVALID_COMMAND_PAYLOAD`; version-mismatch; unavailable-artifact; PR-link-mismatch (prOutput); idempotent replay; idempotency-conflict; illegal-state-transition rolls back; FR21 separate `productApprovalState`/`technicalApprovalState` surface in `getRunSummary`; FR46 attribution end-to-end.

**Scope guardrails:**

- **Out of scope for 3.20:** `rejectImplementation` + the V6 implementation-rejection loop column + developer rejection taxonomy (story 3.21); `DeveloperTakeoverService.takeoverWorkflow` + `cancelled_for_takeover` (story 3.22); REST endpoints `accept-implementation`/`reject-implementation`/`takeover` + OpenAPI regen (stories 3.23/3.24/3.25); CLI `deliveryline accept-implementation`; the UI Decision Bar `implementation_review` mode (story 3.28) and Run Context Strip surfacing of the two FR21 fields (stories 3.28/3.31).
- **No new Flyway migration. No new `DomainErrorCode`** — `APPROVAL_VERSION_MISMATCH`, `ARTIFACT_PAYLOAD_UNAVAILABLE`, `ARTIFACT_PR_LINK_MISMATCH`, `IDEMPOTENCY_KEY_CONFLICT`, `ILLEGAL_TRANSITION`, `INVALID_COMMAND_PAYLOAD` all already exist.
- **One new registry value:** `AllowedAction.ACCEPT_IMPLEMENTATION`. Handle it via the registry-drift / fixture lockstep (Trap T7) — this is NOT a `DomainErrorCode` three-sites change.

## Tasks / Subtasks

- [x] **Task 1: `AcceptImplementationCommand` + sealed-interface wiring** (AC: 2)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/AcceptImplementationCommand.java` as a record implementing `WorkflowCommand`. Mirror `ApproveSpecCommand` field-for-field: `@NotBlank @Size(max=128) workflowRunId`, `@NotBlank @Size(max=128) artifactId`, `@NotNull @Positive Integer artifactVersion`, `@NotNull @Positive Integer contextVersion`, `@NotBlank @Size(max=128) actorIdentity`, `@NotNull ActorType actorType`, `@NotBlank @Size(max=256) idempotencyKey`, `@Size(max=128) correlationId`, `@NotBlank @Size(max=128) reviewerRole`, `@Size(max=1024) reason`.
  - [x] Javadoc: "Fingerprint fields are `workflowRunId`, `artifactId`, `artifactVersion`, `contextVersion`, `reviewerRole`. `reason` is intentionally excluded — wording edits replay idempotently (mirrors `ApproveSpecCommand`)."
  - [x] Add `AcceptImplementationCommand` to the `WorkflowCommand` sealed `permits` list (`commands/WorkflowCommand.java:6-11`).
  - [x] Add a `case AcceptImplementationCommand` to `WorkflowCommandFingerprintFactory.fingerprintFor` (`application/idempotency/WorkflowCommandFingerprintFactory.java:27-50`) — append `workflowRunId`, `artifactId`, `artifactVersion.toString()`, `contextVersion.toString()`, `reviewerRole`; do **not** append `reason`. The switch is exhaustive over the sealed type, so this is a **compile-time requirement**.

- [x] **Task 2: Reuse `ApprovalResult` + the version-binding logic** (AC: 1, 5)
  - [x] Reuse the existing `application/approval/ApprovalResult` record verbatim (it already carries `approvalId`, `workflowRunId`, `artifactId`, `artifactVersion`, `contextBundleVersion`, `reviewerRole`, `decidedAt`, `resultingState`, `correlationId`). For `acceptImplementation`, `resultingState` is `COMPLETED` (prOutput) or `EXECUTING` (implementationPlan).
  - [x] **Decision (recommended):** Extract the version-binding + context-bundle-version resolution from `ApprovalService` into a small package-private collaborator (e.g. `@Component ApprovalVersionBinder` in `application/approval`) exposing `int resolveCurrentContextBundleVersion(ArtifactRecordSnapshot)` and `void assertVersionsMatch(expectedArtifactVersion, currentArtifactVersion, expectedContextBundleVersion, currentContextBundleVersion, artifactId, workflowRunId)` (throws `APPROVAL_VERSION_MISMATCH` with the 2.9 `details` shape). Refactor `ApprovalService.approveSpec`/`rejectSpec` to call it (behavior must stay byte-identical — pinned by the existing `ApprovalServiceApproveSpecTest`/`…RejectSpecTest`). Adding a ctor dep to `ApprovalService` fans out to its Mockito tests — add the mock there (Trap T9). **Fallback** if extraction ripples too far: duplicate the ~25 lines into `TechnicalApprovalService` and add a `// DUP(ApprovalService.resolveCurrentContextBundleVersion): keep in sync` comment. Prefer extraction (anti-reinvention).
  - [x] Context-bundle version source mirrors `ApprovalService.resolveCurrentContextBundleVersion` (`application/approval/ApprovalService.java:~509-529`): if the artifact has no linked `runner_execution_id` → version `1`; else read `RunnerExecutionRecordPort.findByPublicId(execId).contextBundleVersion()`.

- [x] **Task 3: `TechnicalApprovalService.acceptImplementation`** (AC: 1, 5, 6, 7, 8, 9, 11)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/application/approval/TechnicalApprovalService.java`, `@Service`, method annotated `@Transactional(propagation = Propagation.MANDATORY)` (mirror `ApprovalService` — relies on the outer `WorkflowCommandService` transaction; ArchUnit pins no `REQUIRES_NEW` — Trap T4).
  - [x] Constructor-inject (mirror `ApprovalService`): `ArtifactRecordPort`, `ArtifactService`, `ApprovalWritePort`, `WorkflowEventWritePort`, `WorkflowTransitionService`, `RunnerExecutionRecordPort`, `WorkflowOrchestrationService`, `IntegrationLinkService` (for the PR-link gate), the shared `ApprovalVersionBinder` (Task 2), and `Clock`.
  - [x] Flow:
    1. Prefix-validate `workflowRunId` (`WORKFLOW_RUN`) + `artifactId` (`ARTIFACT`). Open MDC scope (`WORKFLOW_RUN_ID`, `ARTIFACT_ID`).
    2. Load the artifact via `ArtifactRecordPort.findByPublicId` → `RUN_NOT_FOUND`/not-found if absent.
    3. **AC2 type guard:** if `artifactType == SPEC` → `DomainException(INVALID_COMMAND_PAYLOAD, …)` with `details.artifactType` + `details.reason='technical_approval_requires_implementation_artifact'`. Accept only `IMPLEMENTATION_PLAN` / `PR_OUTPUT`.
    4. **AC5 version-binding (FIRST):** resolve current artifact version + context-bundle version, `assertVersionsMatch(...)` → `APPROVAL_VERSION_MISMATCH`.
    5. **AC6 eligibility:** `ArtifactService.isApprovalEligible(artifactId)` false → `ARTIFACT_PAYLOAD_UNAVAILABLE` (`details.reason='not_approval_eligible'`).
    6. **AC6 PR-link gate (prOutput only):** resolve the prOutput artifact's PR reference (OQ-1) and call `IntegrationLinkService.assertArtifactPrLinkMatches(workflowRunId, artifactPrReference)` → `ARTIFACT_PR_LINK_MISMATCH` on drift / fail-closed when no active link.
    7. **AC3 insert:** build `NewApproval` (`decision="approved"`, `rejectionTaxonomy=null`, `reviewerRole=command.reviewerRole()`, `decidedAt=now(clock)`) → `ApprovalWritePort.insert`.
    8. **AC7/AC9 event:** append `WorkflowEventType.APPROVAL_APPROVED` with `details={approvalId, artifactId, artifactVersion, contextBundleVersion, reviewerRole, idempotencyKey, correlationId?}`.
    9. **AC7/AC8 transition + dispatch:** branch on artifact type:
       - `PR_OUTPUT` → `WorkflowTransitionService.transition(runId, COMPLETED, actor, "accept implementation", idempotencyKey, eventDetails)`. (Linear sync hook auto-fires post-commit — do not call it.)
       - `IMPLEMENTATION_PLAN` → `transition(runId, EXECUTING, …)` then `workflowOrchestrationService.dispatchImplementation(runId, correlationId)`.
    10. Return `ApprovalResult` with the correct `resultingState`.
  - [x] ArchUnit: `TechnicalApprovalService` lives under `org.dradgo.application.approval` and depends only on `application.*` ports + `domain.*` types (no `adapters.*`, no JPA entities). Extend `ArchitectureRuleCatalog`/`ArchitectureBoundaryTest` mirroring the `ApprovalService` rule.

- [x] **Task 4: `WorkflowCommandService.acceptImplementation` delegation + replay** (AC: 10)
  - [x] Add a public `@Transactional WorkflowStateChangeResult acceptImplementation(AcceptImplementationCommand command)` to `WorkflowCommandService` (mirror `approveSpec` at `application/workflow/WorkflowCommandService.java:130-132`): `return executeIdempotent(command, this::acceptImplementationInternal, this::replayAcceptImplementation);`
  - [x] `acceptImplementationInternal` calls `technicalApprovalService.acceptImplementation(command)` and builds `WorkflowStateChangeResult(approvalResult.workflowRunId(), approvalResult.resultingState(), approvalResult.correlationId())` (mirror `approveSpecInternal:240-250`). Open a `WORKFLOW_RUN_ID` MDC scope as `rejectSpecInternal` does.
  - [x] **Inject `TechnicalApprovalService` into the `WorkflowCommandService` constructor** (extend the existing ctor at `:93-122`; do not add a setter). Adding a ctor dep fans out to every `new WorkflowCommandService(...)` test site — grep and update (Trap T10).
  - [x] **Replay (Trap T2):** the existing `replayStateChange` (`:527-553`) hard-codes `resultingState` per command type (EXECUTING for ApproveSpec, INVESTIGATING for RejectSpec). For `acceptImplementation` the target depends on artifact type, so add `replayAcceptImplementation` that re-reads the run via `findWorkflowRunForReplay` and returns `WorkflowStateChangeResult` with the run's **current** `currentState()` — do NOT hard-code.

- [x] **Task 5: FR21 separate approval states in `getRunSummary`** (AC: 4)
  - [x] Extend `WorkflowInspectionService.WorkflowRunDetailedSummaryView` (`application/workflow/WorkflowInspectionService.java:1287-1295`) with two fields at the END of the record: `String productApprovalState`, `String technicalApprovalState` (typed via a new small enum `RunApprovalState { NONE, APPROVED }` rendered to its name — see OQ-2 for the value set). Append after `pendingClarifications`.
  - [x] In `getRunSummary` (`:220-261`) derive both via `ApprovalReadPort` (inject if not already present — `WorkflowInspectionService` already injects `ApprovalReadPort` for `getCurrentApprovedSpec`):
    - `productApprovalState = findLatestApprovedForArtifactLineage(runId, ArtifactType.SPEC.value()).isPresent() ? APPROVED : NONE`.
    - `technicalApprovalState = APPROVED` iff `findLatestApprovedForArtifactLineage(runId, IMPLEMENTATION_PLAN)` OR `…(runId, PR_OUTPUT)` is present, else `NONE`.
  - [x] Update `WorkflowInspectionServiceClarificationStatusTest.getRunSummaryIncludesPendingClarificationsAndLatestEvent` (and any other constructor of `WorkflowRunDetailedSummaryView`) for the two new fields. Add focused cases asserting separate states (spec-approved-only run → product APPROVED/technical NONE; plan-approved run → both/just technical APPROVED).
  - [x] **Do NOT** widen `WorkflowSummaryResponse` (REST list) or `WorkflowStatusView` (REST/CLI status) — that surfacing is deferred (Trap T8). The detailed view is read internally by `getAllowedActions` and tests today.

- [x] **Task 6: `accept_implementation` allowed-action + developer role** (AC: 12)
  - [x] Add `ACCEPT_IMPLEMENTATION("accept_implementation")` to `domain/registry/AllowedAction.java` (currently 8 values; no implementation actions yet).
  - [x] Add `static final String ROLE_DEVELOPER = "developer";` to `WorkflowInspectionService` and include it in `RECOGNIZED_ACTOR_ROLES` (`:276-284`) so `getAllowedActions(runId, "developer")` does not throw `UNKNOWN_ACTOR_ROLE`.
  - [x] In `computeActionMatrix` `WAITING_FOR_REVIEW` case (`:426-429`): when `ROLE_DEVELOPER.equals(actorRole)` return `List.of(AllowedAction.ACCEPT_IMPLEMENTATION, AllowedAction.VIEW_ONLY)`; all other roles keep `VIEW_ONLY`. Leave the `// SEAM (Epic 3/4)` comment, narrowed to note that `reject_implementation`/`takeover` are still pending (3.21/3.22).
  - [x] **Registry-drift lockstep (Trap T7):** add `"accept_implementation"` to `src/test/resources/contracts/frontend/allowed-actions.placeholder.json` (`allowedActions` array) so `RegistryContractTest.allowedActionsStayAlignedWithFrontendPlaceholder` (`contract/RegistryContractTest.java:322`) stays green. Add a pin to `architecture/AllowedActionRegistryPinTest` asserting `AllowedAction.ACCEPT_IMPLEMENTATION.value().equals("accept_implementation")`.
  - [x] Update `application/workflow/WorkflowInspectionServiceAllowedActionsTest` matrix: add a `WAITING_FOR_REVIEW + developer → [ACCEPT_IMPLEMENTATION, VIEW_ONLY]` row; keep `product_reviewer`/`workflow_owner → [VIEW_ONLY]`.

- [x] **Task 7: Test suite** (AC: 13)
  - [x] **Unit** (`src/test/java/org/dradgo/application/approval/TechnicalApprovalServiceAcceptImplementationTest.java`, Mockito): happy-path implementationPlan (assert transition→EXECUTING + `dispatchImplementation` invoked + `APPROVAL_APPROVED` appended before transition); happy-path prOutput (transition→COMPLETED, no dispatch, no direct Linear call); spec-artifact rejected (`INVALID_COMMAND_PAYLOAD`, no port mutations); version-mismatch (no mutations); unavailable-artifact (no mutations); prOutput PR-link-mismatch (no insert/event/transition); illegal-state-transition (mock transition to throw `ILLEGAL_TRANSITION` — assert it propagates and the insert+event happened before the failing transition so the outer tx rolls back). Pin SLF4J log lines via Logback `ListAppender` (mirror `ApprovalServiceApproveSpecTest`): success line, the three WARN rejection branches (`APPROVAL_VERSION_MISMATCH`, `ARTIFACT_PAYLOAD_UNAVAILABLE`/`ARTIFACT_PR_LINK_MISMATCH`, `ILLEGAL_TRANSITION` propagation).
  - [x] **Service contract / integration** (`src/test/java/org/dradgo/application/approval/TechnicalApprovalServiceContractTest.java` or a Testcontainers IT named `*IT` per [[springboot-testcontainers-test-must-be-IT]]): drive end-to-end through `WorkflowCommandService.acceptImplementation`. Seed a `WAITING_FOR_REVIEW` run with an `available` implementationPlan (→ Executing + dispatch) and a separate run with an `available` prOutput + an active `github_pr` link (→ Completed + completion-sync hook registered). Assert `ApprovalReadPort.findLatestApprovedForArtifactLineage` returns the new row; assert idempotent replay (single insert across two invocations) + idempotency-conflict (different fingerprint). FR21: assert `getRunSummary` returns the separated states.
  - [x] **Registry/architecture:** extend `ArchitectureBoundaryTest` (TechnicalApprovalService boundary), `AllowedActionRegistryPinTest`, and `RegistryContractTest` (auto-validates enum↔fixture).
  - [x] Run: `./mvnw.cmd -pl deliveryline-backend -o -Dtest='TechnicalApprovalServiceAcceptImplementationTest,TechnicalApprovalServiceContractTest,WorkflowInspectionServiceAllowedActionsTest,WorkflowInspectionServiceClarificationStatusTest,RegistryContractTest,AllowedActionRegistryPinTest,ArchitectureBoundaryTest,ApprovalServiceApproveSpecTest' -Dsurefire.failIfNoSpecifiedTests=false test` then `./mvnw.cmd -pl deliveryline-backend -Pfoundation-gate verify` once before the PR (keeps the JaCoCo floor + ArchUnit Failsafe + Testcontainers ITs green). Run gates via PowerShell ([[rtk-hook-only-matches-bash]]); reproduce CI shape on WSL2 Linux ([[wsl-linux-ci-reproduction]], [[verify-ci-fixes-in-clean-env]]).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write), and every transition/dispatch branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels:
    - `INFO` on `TechnicalApprovalService.acceptImplementation` entry (`workflowRunId`, `artifactId`, `artifactType`, `expectedArtifactVersion`, `expectedContextBundleVersion`, `reviewerRole`, `actorIdentity`, `actorType`); `INFO` on success (`approvalId`, `artifactType`, versions, `reviewerRole`, `resultingState`); `INFO` on transition (`from=WaitingForReview`, `to=Completed|Executing`) and on `dispatchImplementation` invocation (implementationPlan branch).
    - `WARN` on each typed rejection: `INVALID_COMMAND_PAYLOAD` (spec artifact), `APPROVAL_VERSION_MISMATCH` (with `currentArtifactVersion`/`currentContextBundleVersion`), `ARTIFACT_PAYLOAD_UNAVAILABLE` (`reason=not_approval_eligible`), `ARTIFACT_PR_LINK_MISMATCH`, `ILLEGAL_TRANSITION` propagation.
    - `ERROR` only for unhandled failures.
    - `DEBUG` for per-field version comparison detail.
  - [x] Every log carries `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, `artifactId`, and on success `approvalId` (MDC via `MdcKeys.beginScope/endScope`; `APPROVAL_ID` constant already exists).
  - [x] **Forbidden in log output:** the reviewer-supplied `reason` text (free-form), payload bytes, secrets, raw PII. Log `reason.length()` if presence must be signaled — never the text.
  - [x] Pin the success line + each WARN branch in the unit test (Logback `ListAppender`).

## Dev Notes

### Foundations already in place (do NOT rebuild)

- **Approval writer + reader** — `ApprovalWritePort` (+ `NewApproval` record) `application/approval/spi/ApprovalWritePort.java:51-64`; `ApprovalWritePersistenceAdapter.insert` `adapters/persistence/ApprovalWritePersistenceAdapter.java:71-176` already maps the `uq_approvals_idempotency_key` `DataIntegrityViolationException` → `IDEMPOTENCY_KEY_CONFLICT` and enforces the `rejection_taxonomy` pairing. `ApprovalReadPort.findLatestApprovedForArtifactLineage(runId, artifactType)` is the read used for FR21 derivation. **Reuse the writer verbatim** — `acceptImplementation` is just a new caller of `insert(...)`.
- **`ApprovalResult`** — `application/approval/ApprovalResult.java` already carries everything `acceptImplementation` returns; `resultingState` is `COMPLETED` or `EXECUTING`. Do not add it to `DomainResult` (2.9 Trap T2 still holds).
- **`approvals` table** — V1 `db/migration/V1__create_workflow_core_tables.sql:167-204`. `reviewer_role` is a free-text column (no enum/CHECK) — `developer` is accepted as-is. `ck_approvals_decision_taxonomy_paired` requires `rejection_taxonomy IS NULL` when `decision='approved'`. **Highest migration is V11; no new migration in 3.20.**
- **`DomainErrorCode`** — `APPROVAL_VERSION_MISMATCH`(19), `ARTIFACT_PAYLOAD_UNAVAILABLE`(29), `ARTIFACT_PR_LINK_MISMATCH`(95), `IDEMPOTENCY_KEY_CONFLICT`(12), `ILLEGAL_TRANSITION`(11), `INVALID_COMMAND_PAYLOAD`, `UNKNOWN_ACTOR_ROLE` all registered. No new codes.
- **`WorkflowTransitionTable`** — `WAITING_FOR_REVIEW → {COMPLETED, EXECUTING, TAKEN_OVER, RECONCILED}` all permitted (`application/workflow/WorkflowTransitionTable.java:76-82`); `COMPLETED` is terminal (`:83`). No transition-table change.
- **`WorkflowOrchestrationService.dispatchImplementation(runId)` / `(runId, correlationId)`** — `application/workflow/WorkflowOrchestrationService.java:509-541`. Non-`@Transactional`; designed to run inside the caller's transaction; NEVER transitions; idempotent on an in-flight prOutput execution; gated by `implementation-stage.auto-dispatch` (returns null when disabled). The class comment at `:518-519` explicitly names "story 3.20's `acceptImplementation`" as its live caller. It derives the sub-stage via `ContextBundleService.deriveExecutionSubStage` (`PR_OUTPUT` once a plan approval exists — which 3.20 just created).
- **`IntegrationLinkService.assertArtifactPrLinkMatches(workflowRunPublicId, artifactPrReference)`** — `application/integration/IntegrationLinkService.java:580-614`. Raises `ARTIFACT_PR_LINK_MISMATCH`, fails closed when no active `github_pr` link (`:585-590`), format-exact compare against the canonical `owner/repo#number`. The class comment at `:577` names story 3.20 as its production call-site. `findActiveGitHubPrLink(runId)` (`:648`) returns the active link (its `externalRef()` is the canonical PR ref). **Known review caveat:** the method takes a `PESSIMISTIC_WRITE` read while being non-`@Transactional` — calling it inside `acceptImplementation`'s `MANDATORY` transaction gives the lock a proper boundary (resolves the deferred 3.15 reconciliation note).
- **Linear completion sync (story 3.16)** — `WorkflowTransitionService.registerCompletionSyncHookIfApplicable(runId, targetState)` (`:142-159`) registers a **post-commit** `afterCommit` hook for ANY `→ COMPLETED` transition (gated `linearCompletionSync().enabled()`). 3.20's prOutput approval → `COMPLETED` triggers it automatically; **do NOT call `syncCompletionToLinear` directly** (Trap T6). Class comment at `:139` names `TechnicalApprovalService.acceptImplementation` as the production driver.
- **`ArtifactType`** — `domain/registry/ArtifactType.java`: `SPEC("spec")`, `IMPLEMENTATION_PLAN("implementationPlan")`, `PR_OUTPUT("prOutput")`. `ArtifactRecordPort.findByPublicId` → `Optional<ArtifactRecordSnapshot>` carrying `artifactType`, `version`, `status`, `storageRef`, `parentArtifactId`.
- **`ArtifactService.isApprovalEligible(String artifactId)`** — `application/artifact/ArtifactService.java:26-29`. Reuse; do not inline a parallel eligibility check.
- **`ApprovalReviewerRoleResolver`** — `application/workflow/ApprovalReviewerRoleResolver.java`. No allow-list; accepts any non-blank role (so `developer` passes). Used only by the REST mutation path (3.23 will wire the accept-implementation endpoint through it). 3.20 is service-only — the command carries `reviewerRole` directly.
- **`WorkflowCommandFingerprintFactory`** — `application/idempotency/WorkflowCommandFingerprintFactory.java`. Explicit per-command-type switch over the sealed `WorkflowCommand`; adding `AcceptImplementationCommand` REQUIRES a new `case`.

### Context-bundle version source (AC5)

Identical to story 2.9/2.10: compare `command.contextVersion()` against the artifact's current context-bundle version, resolved from the artifact's parent `runner_executions.context_bundle_version`. Mirror `ApprovalService.resolveCurrentContextBundleVersion` (`application/approval/ApprovalService.java:~509-529`): no linked `runner_execution_id` → treat current version as `1`; else `RunnerExecutionRecordPort.findByPublicId(execId).contextBundleVersion()`. (Note: `RunnerExecutionRecordPort.nextContextBundleVersion(runId, stage)` at `:36-40` is the *write-path* "next version" query — NOT what the approval read path uses; read the existing `ApprovalService` resolver and reuse it.)

### Traps (anti-pattern prevention)

| ID | Trap | Resolution |
|----|------|------------|
| **T1** | AC2 prose says `expectedArtifactVersion`/`expectedContextBundleVersion`, but `ApproveSpecCommand`/`RejectSpecCommand` carry `artifactVersion`/`contextVersion`. Renaming would diverge from the sibling commands + the fingerprint factory. | **Keep the short names `artifactVersion`/`contextVersion`.** Document in the Javadoc that they ARE the expected versions. The rich verbose names belong to the REST DTO in story 3.23. |
| **T2** | The existing `replayStateChange` hard-codes the resulting state per command type. `acceptImplementation`'s target is `COMPLETED` (prOutput) or `EXECUTING` (implementationPlan) — a hard-coded replay would return the wrong state. | Add a dedicated `replayAcceptImplementation` that re-reads the run via `findWorkflowRunForReplay` and returns the run's **current** state. |
| **T3** | Running eligibility/PR-link before version-binding gives a stale reviewer `ARTIFACT_PAYLOAD_UNAVAILABLE`/`ARTIFACT_PR_LINK_MISMATCH` when their real mistake is a stale version. | **Order: AC5 version-binding → AC6 eligibility → AC6 PR-link.** Mirrors 2.9 Trap T3. |
| **T4** | Adding `@Transactional` (default `REQUIRED`/`REQUIRES_NEW`) to `TechnicalApprovalService` creates a boundary that won't roll back with the outer command transaction. | Use `@Transactional(propagation = Propagation.MANDATORY)` (mirror `ApprovalService`); the outer `WorkflowCommandService.acceptImplementation` `@Transactional` owns the boundary. ArchUnit pins no `REQUIRES_NEW`. |
| **T5** | Calling `dispatchImplementation` BEFORE the `→ EXECUTING` transition (implementationPlan branch) — it derives sub-stage/guards against the current state and would mis-fire. | Transition to `EXECUTING` first, then `dispatchImplementation` — mirrors `ApprovalService.approveSpec` calling `dispatchPlanGeneration` *after* its transition (`:252`). |
| **T6** | Calling `syncCompletionToLinear` directly from `acceptImplementation` would double-fire (the COMPLETED transition hook already fires it post-commit) and couple the approval tx to a network call. | **Do NOT call Linear.** Just transition to `COMPLETED`; the `WorkflowTransitionService` afterCommit hook (3.16) fires the sync best-effort post-commit. |
| **T7** | Adding `AllowedAction.ACCEPT_IMPLEMENTATION` without updating the frontend placeholder fixture reds `RegistryContractTest`. | Update `contracts/frontend/allowed-actions.placeholder.json` + `AllowedActionRegistryPinTest` in the same commit. This is a registry-fixture lockstep, NOT a `DomainErrorCode` three-sites change. |
| **T8** | Surfacing `productApprovalState`/`technicalApprovalState` through `WorkflowStatusView`/`WorkflowSummaryResponse` would force an `openapi.json` snapshot regen + `workflow-status.v1.schema.json` + frontend type changes — bleeding into stories 3.23/3.28/3.31. | Add the fields ONLY to the application-layer `WorkflowRunDetailedSummaryView` (AC4's named home). REST/CLI/UI surfacing is deferred — exact precedent: story 2.12 added `pendingClarifications` to the detailed view and deferred REST exposure (documented in `WorkflowSummaryResponse.java:36-42`). |
| **T9** | Extracting `ApprovalVersionBinder` adds a ctor dep to `ApprovalService`, breaking its Mockito tests ("no such constructor"). | Add the new mock to `ApprovalServiceApproveSpecTest`/`…RejectSpecTest` ctor setup; assert behavior unchanged. (If the fan-out is unacceptable, fall back to the documented `// DUP(...)` duplication — Task 2.) |
| **T10** | Adding `TechnicalApprovalService` to the `WorkflowCommandService` constructor breaks every `new WorkflowCommandService(...)` test site. | Grep `new WorkflowCommandService(` under `src/test` and thread the new arg (mirror how `ApprovalService`/`ClarificationService` were added). |
| **T11** | Naming a Testcontainers/`@SpringBootTest` test `*Test` leaks it into the no-Docker Windows Surefire fast tier and reds CI. | Name the DB-backed integration test `*IT` so Failsafe (Docker tier) runs it ([[springboot-testcontainers-test-must-be-IT]]). |

### Open Questions (resolve before merging)

- **OQ-1 (headline): source of the prOutput artifact's PR reference for AC6's `assertArtifactPrLinkMatches`.** The gate compares the artifact's claimed PR ref against the active `github_pr` link's canonical `external_ref`. Story 3.12 enriched the prOutput artifact with branch/commit/prRef, and story 3.15 set the link's `external_ref` from that same prRef. **Action:** inspect how 3.12 persisted the prOutput artifact's PR reference (the V11 runner-raw-output columns / artifact enrichment) and read it back to pass as `artifactPrReference`. **Recommendation/fallback:** if a clean read of the artifact's prRef isn't exposed yet, the minimal safe gate is to pass the active link's `findActiveGitHubPrLink(runId).externalRef()` (so the check degrades to "an active PR link must exist", which `assertArtifactPrLinkMatches` already fails-closed on) and file a follow-up to tighten to true artifact-vs-link comparison. Confirm with Alex which is acceptable for the pilot. Note the dormant-seam caveat from 3.12 ([[proutput-prref-validator-rejects-real-adapter]]): real adapters emit `owner/repo#number`, so the comparison must use the canonical form, not `PR-<n>`.
- **OQ-2: `RunApprovalState` value set (AC4).** Recommendation: ship the minimal `{ NONE, APPROVED }` (APPROVED iff a latest-approved row exists for the relevant artifact type) — satisfies "distinct typed fields, never collapsed". A richer `{ NONE, PENDING, APPROVED, REJECTED }` can be added when the Decision Bar (3.28) needs it; keep the enum localized so widening is one file. Confirm in PR.
- **OQ-3: developer role recognition surface.** `WorkflowInspectionService.RECOGNIZED_ACTOR_ROLES` must gain `developer` (AC12) so `getAllowedActions(runId, "developer")` doesn't throw `UNKNOWN_ACTOR_ROLE`. The mutation-side `ApprovalReviewerRoleResolver` already accepts any role. No other surface needs the role until the REST endpoint (3.23). Confirm no test pins `RECOGNIZED_ACTOR_ROLES` to exactly two values.

### Project Structure Notes

- `TechnicalApprovalService` → `application/approval/TechnicalApprovalService.java` (sibling of `ApprovalService`).
- `AcceptImplementationCommand` → `application/workflow/commands/AcceptImplementationCommand.java` (+ `WorkflowCommand` permits + fingerprint-factory case).
- Optional shared `ApprovalVersionBinder` → `application/approval/ApprovalVersionBinder.java`.
- `WorkflowRunDetailedSummaryView` extension + `RunApprovalState` enum → `application/workflow/WorkflowInspectionService.java`.
- `AllowedAction.ACCEPT_IMPLEMENTATION` → `domain/registry/AllowedAction.java`; fixture `src/test/resources/contracts/frontend/allowed-actions.placeholder.json`.
- **No new Flyway migration, no new `DomainErrorCode`, no schema.d.ts/openapi.json change** (FR21 fields stay application-internal — Trap T8).

### Architecture compliance

- **Component boundaries:** `application/approval` is the canonical approval-service location; `TechnicalApprovalService` depends only on `application/*` ports + `domain/*` types — no JPA entities, no `adapters.*` (ArchUnit-enforced, [[application-cannot-import-adapters]]).
- **Service boundaries:** `WorkflowTransitionService` remains the only state-transition path; `ArtifactService`/`ArtifactRecordPort` the only artifact reads; `IntegrationLinkService` the only PR-link path. `TechnicalApprovalService` orchestrates, never bypasses.
- **Approval checkpoints (architecture.md:81):** "Each approval must bind to a specific artifact version, context bundle version, workflow state, actor identity, reviewer role, decision, reason." AC2/AC3/AC5 implement this for the technical decision.
- **State+event atomicity (architecture.md:301-302):** approval row insert + `APPROVAL_APPROVED` event + `WORKFLOW_STATE_CHANGED` event + state update + `dispatchImplementation` (plan branch) all commit together under the outer `@Transactional`. Linear sync is intentionally **post-commit** (best-effort, can't roll back the approval).
- **FR21 (separate product/technical states):** preserved at the data layer (`reviewer_role` + artifact type already distinguish them) and exposed as two typed fields — never collapsed.

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out`/`printStackTrace()`.
- **Where to log:** `TechnicalApprovalService.acceptImplementation` → INFO entry (all envelope fields + artifactType) / INFO success (approvalId + attribution + resultingState) / INFO transition + dispatch / WARN on every typed rejection branch. The reused `ApprovalWritePersistenceAdapter.insert` already logs its own INFO/WARN/ERROR surface.
- **Required context keys:** `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, `artifactId`, `approvalId` (once generated) — via MDC.
- **Forbidden:** reviewer-supplied `reason` text, payload bytes, secrets, raw PII.
- **Test contract:** pin the success line + each WARN branch in `TechnicalApprovalServiceAcceptImplementationTest` (Logback `ListAppender`, mirroring `ApprovalServiceApproveSpecTest`).

### Previous-story intelligence

- **Story 2.9 (ApprovalService approve, done)** — the direct template. `acceptImplementation` is the technical twin of `approveSpec`: same version-binding-first ordering, same `ApprovalWritePort`/event/transition sequence in one MANDATORY-propagation transaction, same idempotency delegation through `WorkflowCommandService.executeIdempotent`. Read `ApprovalService.approveSpec` and copy its shape.
- **Story 2.10 (rejectSpec + escalation, done)** — added `rejectSpec` to `ApprovalService` and the `RejectionTaxonomy` enum (3 product values; developer taxonomy is 3.21's job). Shows the second-method-on-the-service pattern; 3.20 instead introduces a sibling `TechnicalApprovalService` because 3.21 (rejectImplementation) + 3.22 (takeover) extend the technical/developer surface.
- **Story 2.14 (allowed-actions, done)** — `computeActionMatrix` is the single source of truth for state×role→actions; the `WAITING_FOR_REVIEW` case is an explicit `// SEAM (Epic 3/4)` left for exactly this story. `RECOGNIZED_ACTOR_ROLES` + `RegistryContractTest` + the placeholder fixture are the lockstep points.
- **Story 3.11 (plan-stage orchestration, done)** — built `dispatchPlanGeneration` + the EXECUTION success-delegation; deferred "`PLAN_READY_OR_BEYOND` omits EXECUTING" and the in-flight guard to later stories. Its false-premise lesson: verify every "re-harvest forever" claim against source before treating it as a bug (`RunnerBroker` is non-`@Transactional`, `recordCompleted` commits independently).
- **Story 3.12 (pr-output orchestration, done)** — built `dispatchImplementation`/`retryImplementation` + the prOutput enrichment (branch/commit/prRef) and explicitly deferred the **live `acceptImplementation` trigger to 3.20** (`PrOutputOrchestrationIT.java:23,74`). The prRef-format contract caveat ([[proutput-prref-validator-rejects-real-adapter]]) is dormant until 3.20 wires a live trigger + real adapter — OQ-1 owns the reconciliation.
- **Story 3.15 (GitHub PR linkage, done)** — built `assertArtifactPrLinkMatches` (AC5 guard) with its production call-site explicitly deferred to 3.20; flagged the "non-`@Transactional` method takes a PESSIMISTIC_WRITE read" reconciliation for 3.20's wiring (resolved by calling it inside the MANDATORY tx).
- **Story 3.16 (Linear completion sync, done)** — built the COMPLETED post-commit hook whose production driver it names as `TechnicalApprovalService.acceptImplementation`. 3.20's prOutput→COMPLETED is the first production trigger; tests previously drove it via a direct `WaitingForReview → Completed` transition.

### Git intelligence

Recent commits land per-story prefixed `Story N.M: <title>` (or `feat(3a-N): …` for the active-slice stories) in one or two clean commits. **No Co-Authored-By Claude trailer in this repo** ([[commit-no-claude-coauthor]]). Spotless + Checkstyle run on `verify` — run `spotless:apply` before pushing ([[checkstyle-suppressions-line-anchored]], [[pom-xml-comment-no-double-dash]]). Run all gates via PowerShell, not the RTK-hooked Bash tool ([[rtk-hook-only-matches-bash]]); reproduce CI on WSL2 Linux before claiming green ([[wsl-linux-ci-reproduction]], [[verify-ci-fixes-in-clean-env]]).

Memory refs: [[application-cannot-import-adapters]], [[two-public-constructors-need-autowired]], [[docker-adapter-ctor-dep-fans-out]], [[broker-orchestration-lazy-supplier]], [[springboot-testcontainers-test-must-be-IT]], [[validated-config-needs-test-yaml]], [[proutput-prref-validator-rejects-real-adapter]], [[post-commit-hook-needs-requires-new]], [[new-workfloweventtype-fixture-sites]], [[rtk-hook-only-matches-bash]], [[wsl-linux-ci-reproduction]], [[verify-ci-fixes-in-clean-env]], [[commit-no-claude-coauthor]].

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.20](_bmad-output/planning-artifacts/epic-03-agent-execution.md#L402-L422)
- [Source: _bmad-output/implementation-artifacts/2-9-backend-approval-service-core-approve-with-version-binding.md] — the approve twin (full template)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java#approveSpec] — copy its shape (MANDATORY tx, version-binding-first, insert→event→transition→dispatch)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java#L509-L541] — `dispatchImplementation` (names 3.20 as caller at :518-519)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java#L580-L614] — `assertArtifactPrLinkMatches` (names 3.20 at :577)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java#L139-L173] — COMPLETED Linear-sync post-commit hook (names 3.20 at :139)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java#L76-L83] — WAITING_FOR_REVIEW edges + COMPLETED terminal
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java#L426-L429] — `computeActionMatrix` WAITING_FOR_REVIEW SEAM; #L1287-L1295 detailed-summary record; #L276-L284 recognized roles
- [Source: deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json] — registry-drift lockstep fixture
- [Source: _bmad-output/planning-artifacts/architecture.md#Approval checkpoints contract](_bmad-output/planning-artifacts/architecture.md#L81-L81)
- [Source: _bmad-output/planning-artifacts/architecture.md#State-event atomicity](_bmad-output/planning-artifacts/architecture.md#L300-L302)

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Focused unit + contract tier (Surefire, explicit `-Dtest`): **79 passed** (TechnicalApprovalServiceAcceptImplementationTest 7, ApprovalServiceApproveSpecTest 6, ApprovalServiceRejectSpecTest 7, WorkflowInspectionServiceAllowedActionsTest 41, WorkflowInspectionServiceClarificationStatusTest 7, RegistryContractTest, AllowedActionRegistryPinTest, WorkflowCommandServiceReplayRefTest 3, WorkflowCommandFingerprintFactoryTest 8, OpenApiSnapshotContractTest).
- Full fast Surefire tier: **920 passed, 0 failed, 11 skipped** (no regressions from the `ApprovalVersionBinder` extraction or the `WorkflowCommandService` ctor change).
- Failsafe (Docker): `TechnicalApprovalServiceContractTest` **4 passed**, `ArchitectureBoundaryTest` **44 passed** (incl. new `TECHNICAL_APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL` boundary rule).
- `spotless:check` + `checkstyle:check`: **0 violations** (re-anchored the line-pinned `Thread.sleep` suppression for `WorkflowCommandService` 719→769 per [[checkstyle-suppressions-line-anchored]]).
- `-Pfoundation-gate verify`: **BUILD SUCCESS** (all 12 foundation contracts green after reconciling Contract #6 `CommandModelSymmetryFoundationContract.EXPECTED_PERMITS`).

### Completion Notes List

Implemented the technical-approval twin of story 2.9 (`approveSpec`). Key decisions & reconciliations:

- **OQ-1 (Alex-confirmed): fail-closed active-link PR-ref source.** The prOutput artifact's true PR ref lives only in its payload JSON (no clean read port). The pilot PR-link gate resolves `IntegrationLinkService.findActiveGitHubPrLink(runId).externalRef()` and routes it through the canonical `assertArtifactPrLinkMatches` (self-match), degrading to "an active `github_pr` link must exist" (fail-closed when none). A future story can tighten to a true artifact-vs-link comparison in one place.
- **Task 2 — extraction over duplication, but narrowed.** Extracted only the meaty shared port-chain (`ApprovalVersionBinder.resolveCurrentContextBundleVersion` + a pure `versionsMatch` helper) into a `@Component`; each service keeps building its own `APPROVAL_VERSION_MISMATCH` WARN+details under its own logger so the per-service Logback `ListAppender` assertions stay valid (the binder does no logging/exception-building). `ApprovalService` swapped its `RunnerExecutionRecordPort` ctor dep for the binder; its two Mockito tests construct a REAL binder over the same mocks (Trap T9), keeping behavior byte-identical.
- **OQ-2: shipped minimal `RunApprovalState { NONE, APPROVED }`** localized in `WorkflowInspectionService`; FR21 `productApprovalState`/`technicalApprovalState` appended to `WorkflowRunDetailedSummaryView` ONLY (REST/CLI/UI surfacing deferred to 3.23/3.28/3.31, Trap T8 — `OpenApiSnapshotContractTest` confirms no openapi regen needed).
- **OQ-3: a test pinned `RECOGNIZED_ACTOR_ROLES` to exactly two values** — updated it to include `developer` (now three).
- **Foundation reconciliation (not pre-flagged): Contract #6 `CommandModelSymmetryFoundationContract`** hard-codes the sealed permit set; adding `AcceptImplementationCommand` grew it, so `EXPECTED_PERMITS` was updated in lockstep. The contract does not force a REST round-trip per permit (it manually round-trips only the 6 surfaced commands), so the accept-implementation REST capture legitimately lands in 3.23.
- No new Flyway / DomainErrorCode / openapi / schema change (all confirmed). `dispatchImplementation` is a no-op in the test profile (`implementation-stage.auto-dispatch=false`), so the implementation-plan branch transitions to `Executing` without dispatching a runner in tests.

### File List

**Production (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/AcceptImplementationCommand.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/WorkflowCommand.java` (permits list)
- `deliveryline-backend/src/main/java/org/dradgo/application/idempotency/WorkflowCommandFingerprintFactory.java` (new case)
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalVersionBinder.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java` (refactor to use binder)
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/TechnicalApprovalService.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java` (acceptImplementation + replay + ctor dep)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (FR21 states + RunApprovalState enum + developer role + matrix branch)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` (ACCEPT_IMPLEMENTATION)

**Tests:**
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/TechnicalApprovalServiceAcceptImplementationTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/TechnicalApprovalServiceContractTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceApproveSpecTest.java` (binder ctor)
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceRejectSpecTest.java` (binder ctor)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceReplayRefTest.java` (ctor dep)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java` (developer matrix row + RECOGNIZED_ACTOR_ROLES pin)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceClarificationStatusTest.java` (FR21 cases)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` (boundary rule)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` (@ArchTest binding)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/AllowedActionRegistryPinTest.java` (accept_implementation pin)
- `deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java` (EXPECTED_PERMITS)
- `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json` (registry-drift lockstep)

**Config:**
- `config/checkstyle/suppressions.xml` (re-anchored WorkflowCommandService Thread.sleep suppression 719→769)

### Change Log

| Date | Change |
|------|--------|
| 2026-06-14 | Story 3.20 implemented: `TechnicalApprovalService.acceptImplementation` (technical-approval twin of 2.9) + `AcceptImplementationCommand` + `ApprovalVersionBinder` extraction + FR21 separate product/technical approval states + `AllowedAction.ACCEPT_IMPLEMENTATION` + developer role/matrix branch. Status → review. |

### Review Findings

_Code review 2026-06-14 (bmad-code-review, 3 adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor). All 13 ACs confirmed met; all 11 traps avoided; OQ-1/2/3 resolutions match code; no out-of-scope leak. 0 decision-needed (1 raised, dismissed as spec-sanctioned per Trap T2), 1 patch, 1 deferred, 9 dismissed as noise._

- [x] [Review][Patch] Add negative-state test coverage for the `developer` actor role [deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java] — `ROLE_DEVELOPER` is now in `RECOGNIZED_ACTOR_ROLES`, so `getAllowedActions(runId, "developer")` no longer throws in *any* state, but only the `WAITING_FOR_REVIEW + developer → [ACCEPT_IMPLEMENTATION, VIEW_ONLY]` row was pinned. A non-`WAITING_FOR_REVIEW` state granting `developer` an unintended action would go uncaught. **FIXED 2026-06-14:** added `WAITING_FOR_SPEC_APPROVAL + developer → [VIEW_ONLY, ANSWER_CLARIFICATION]` and `FAILED + developer → [VIEW_ONLY, VIEW_DIAGNOSTICS]` rows to the parameterized matrix (43 tests pass). (edge)
- [x] [Review][Defer] PR-link gate is a self-match tautology — true artifact-vs-link PR-ref comparison not enforced [deliveryline-backend/src/main/java/org/dradgo/application/approval/TechnicalApprovalService.java `assertPrLinkPresentAndMatches`] — deferred, accepted OQ-1 pilot scope. The gate resolves `findActiveGitHubPrLink(runId).externalRef()` and passes it back into `assertArtifactPrLinkMatches(runId, sameRef)`, so the comparison is against itself and can only enforce link *presence* (fail-closed when absent), never artifact-vs-link drift. This is the Alex-confirmed OQ-1 fallback (the prOutput artifact's true PR ref lives only in its payload JSON with no clean read port). Follow-up: tighten to a true artifact-vs-link comparison once an artifact-prRef read port exists. (blind+edge+auditor)
