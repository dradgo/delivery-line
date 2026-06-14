# Story 3.21: Technical Approval Service — `rejectImplementation` with Structured Technical Feedback

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Developer rejecting implementation output with structured feedback,
I want `TechnicalApprovalService.rejectImplementation(...)` accepting tagged feedback (developer-specific taxonomy: `incorrect_approach` / `incomplete_implementation` / `quality_issue` / `breaks_existing_functionality` / `out_of_scope`),
so that FR17 is wired and rejection feedback flows back into the rebuild loop with measurable rework categorization for AR34a metrics.

## Acceptance Criteria

> Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md` § Story 3.21 (lines 424–441). The epic AC text is **idealized**; several items drift from the live code and are reconciled below — **the reconciliations in Dev Notes win where they conflict with the literal AC text.** Read the "Reconciliations & Decisions" section before implementing.

1. **Given** `RejectImplementationCommand`, **Then** it carries: `workflowRunId`, `artifactId` (must be `implementationPlan` or `prOutput`), `expectedArtifactVersion`, `expectedContextBundleVersion`, `actorIdentity`, `actorType`, `reviewerRole=developer`, `reasonText` (required), `taggedFeedback` from the **developer-rejection taxonomy** (`incorrect_approach` / `incomplete_implementation` / `quality_issue` / `breaks_existing_functionality` / `out_of_scope`), `idempotencyKey`, optional `correlationId`.
2. **Given** the `approvals` table, **Then** the rejection row uses `decision=rejected, reviewer_role=developer`, stores `reason` (= `reasonText`), and stamps `rejection_taxonomy` with the developer-taxonomy value. The `rejection_taxonomy` column already exists (V1); the DB CHECK constraints `ck_approvals_rejection_taxonomy` + `ck_workflow_events_rejection_taxonomy` must be **widened** to admit the 5 developer values (see D4).
3. **Given** version-binding, **When** `expectedArtifactVersion` / `expectedContextBundleVersion` don't match the live versions, **Then** `APPROVAL_VERSION_MISMATCH` is raised with the same `details` shape as story 2.9 AC4 — **no rejection row written, no counter increment, no transition** (version check runs first; mirror `ApprovalService.rejectSpec` lines 341–350).
4. **Given** successful rejection of `implementationPlan`, **Then** in one transaction: `approvals` row inserted, `approval.rejected` event with `details.reviewerRole=developer` + `details.taggedFeedback`, `workflow_runs.implementation_rejection_loop_count` incremented, transition to re-enter **plan generation**, and the plan runner re-dispatched. **RECONCILED (D3): transition target is `Executing` (re-dispatch plan generation via `retryPlanGeneration`), NOT `Investigating`** — `WaitingForReview → Investigating` is illegal in the live transition table and `Investigating` is the spec stage, not the plan stage.
5. **Given** successful rejection of `prOutput`, **Then** transition target is `Executing` (re-dispatch PR/output runner via `retryImplementation`) — re-execution carries the rejected-artifact reference + reason + `taggedFeedback` in the new context bundle (story 3.10 `priorFeedbackReferences`, kind `implementationPlan.rejection`). See D8 for the plan-stage feedback-threading gap.
6. **Given** a configurable escalation threshold (default `3`, via `deliveryline.workflow.implementation-rejection-escalation-threshold`), **When** `implementation_rejection_loop_count` reaches the threshold, **Then** an `escalation.required` event is appended with `details.reason='implementation_rejection_loop_threshold_exceeded'` and the run's `escalation_marker_set` is flipped to `true` (reusing the existing V7 column) — fulfilling FR13 at the implementation stage. Escalation event is emitted **at most once per run** (idempotent marker, mirror `rejectSpec` lines 395–440).
7. **Given** AR34a measurement, **Then** `rejection_taxonomy` is populated on every rejection — a contract test asserts no rejection is written without a non-null `taggedFeedback`. The service raises `MISSING_REJECTION_TAXONOMY` if `taggedFeedback` is null (defense-in-depth; the typed `@NotNull` field already prevents it at the command layer).
8. **Given** a new Flyway migration, **Then** it adds `implementation_rejection_loop_count integer NOT NULL DEFAULT 0` to `workflow_runs` (mirroring V7's `spec_rejection_loop_count`) plus a `>= 0` CHECK; replay-safety is asserted by the existing `FlywaySchemaContractTest.flywayMigrateIsReplaySafeAndChecksumStable()`. **RECONCILED (D1): the migration is `V13`, NOT `V6`** — V6 is taken (`V6__integration_link_active_uniqueness.sql`), latest on disk is V11, and V12 is claimed by story 3-17a.
9. **Given** allowed-actions integration, **Then** when `state=WaitingForReview` + `role=developer`, `reject_implementation` appears in the allowed-actions list; the new `AllowedAction.REJECT_IMPLEMENTATION` registry value is added and picked up by the registry drift test + frontend placeholder fixture.
10. **Given** the test suite, **Then** it covers: happy-path rejection of `implementationPlan` → `Executing` + `approval.rejected` event + plan re-dispatch; happy-path rejection of `prOutput` → `Executing` for re-dispatch with feedback in the new bundle; taxonomy-missing rejection (`MISSING_REJECTION_TAXONOMY`); version-mismatch (`APPROVAL_VERSION_MISMATCH`, no row written); threshold-not-exceeded (counter increments, no escalation); threshold-exceeded (escalation event once); idempotent replay (same key + fingerprint) vs `IDEMPOTENCY_KEY_CONFLICT` (same key + different fingerprint); artifact-type guard (`spec` artifactId rejected); V13 migration replay safety; registry drift tests pick up the new `reject_implementation` + developer-taxonomy values.

## Tasks / Subtasks

- [x] **Task 1 — Flyway V13 migration + entity field** (AC: 2, 6, 8)
  - [x] Create `deliveryline-backend/src/main/resources/db/migration/V13__add_implementation_loop_columns.sql`. Mirror `V7__add_spec_rejection_loop_columns.sql` exactly: `alter table workflow_runs add column implementation_rejection_loop_count integer not null default 0;` + `add constraint ck_workflow_runs_implementation_rejection_loop_count_nonneg check (implementation_rejection_loop_count >= 0);`. **Reuse** the existing `escalation_marker_set` column (V7) — do NOT add a second marker.
  - [x] In the SAME migration, **widen the two rejection-taxonomy CHECK constraints** to admit the 5 developer values. The constraints `ck_approvals_rejection_taxonomy` (V1 lines ~195–198) and `ck_workflow_events_rejection_taxonomy` (V1 lines ~97–100) currently allow only `('missing_scope','unclear_specification','misunderstood_implementation')`. Drop + re-add each with the union of product + developer values: `('missing_scope','unclear_specification','misunderstood_implementation','incorrect_approach','incomplete_implementation','quality_issue','breaks_existing_functionality','out_of_scope')`. (Postgres: `alter table … drop constraint …; alter table … add constraint … check (…);`)
  - [x] Add `implementationRejectionLoopCount` field to `adapters/persistence/entity/WorkflowRunEntity.java` mirroring `specRejectionLoopCount` (lines 46–54): `@Column(name = "implementation_rejection_loop_count", nullable = false) private int implementationRejectionLoopCount = 0;` + public getter + package-private setter + a Javadoc comment.
  - [x] Confirm `FlywaySchemaContractTest.flywayMigrateIsReplaySafeAndChecksumStable()` + `FlywayMigrationsFoundationContract` go green (replay-safe + clean fresh apply on the Testcontainers Postgres).

- [x] **Task 2 — Developer rejection taxonomy registry** (AC: 1, 2, 7)
  - [x] Extend `domain/registry/RejectionTaxonomy.java` (single enum, role-scoped — see D4) with the 5 developer constants: `INCORRECT_APPROACH("incorrect_approach")`, `INCOMPLETE_IMPLEMENTATION("incomplete_implementation")`, `QUALITY_ISSUE("quality_issue")`, `BREAKS_EXISTING_FUNCTIONALITY("breaks_existing_functionality")`, `OUT_OF_SCOPE("out_of_scope")`. Wire values must match the widened DB CHECK substrings exactly.
  - [x] Add a helper to partition product vs developer subsets (e.g. `Set<RejectionTaxonomy> developerValues()` / `isDeveloperValue()`) so the service can enforce role-scoping. The `RejectImplementationCommand.taggedFeedback` is typed `RejectionTaxonomy`; the service guards that the value is in the **developer** subset when `reviewerRole=developer`.
  - [x] Update `RegistryContractTest` expectations + any `DomainRegistry` exported value set. The drift test cross-checks enum ↔ SQL CHECK ↔ manifest — keep all three in lockstep.

- [x] **Task 3 — `RejectImplementationCommand` + loop-count port** (AC: 1, 6)
  - [x] Create `application/workflow/commands/RejectImplementationCommand.java` implementing `WorkflowCommand`, mirroring `RejectSpecCommand` field-for-field with Bean Validation: `@NotBlank @Size(max=128) workflowRunId`, `@NotBlank @Size(max=128) artifactId`, `@NotNull @Positive Integer artifactVersion` (= expectedArtifactVersion), `@NotNull @Positive Integer contextVersion` (= expectedContextBundleVersion), `@NotBlank @Size(max=128) actorIdentity`, `@NotNull ActorType actorType`, `@NotBlank @Size(max=256) idempotencyKey`, `@Size(max=128) correlationId`, `@NotBlank @Size(max=128) reviewerRole`, `@NotNull RejectionTaxonomy taggedFeedback`, `@NotBlank @Size(max=1024) reasonText`. **Keep the short names `artifactVersion`/`contextVersion`** (they ARE the expected versions) to mirror the sibling commands + the fingerprint factory (3.20 Trap T1). **Fingerprint fields**: `workflowRunId`, `artifactId`, `artifactVersion`, `contextVersion`, `reviewerRole`, `taggedFeedback` (exclude `reasonText` — mirror `RejectSpecCommand`).
  - [x] **`WorkflowCommand` is a SEALED interface (compile-time requirements — do not skip):** (a) add `RejectImplementationCommand` to the `permits` list in `application/workflow/commands/WorkflowCommand.java`; (b) add a `case RejectImplementationCommand` to the exhaustive switch in `application/idempotency/WorkflowCommandFingerprintFactory.java` — append `workflowRunId`, `artifactId`, `artifactVersion.toString()`, `contextVersion.toString()`, `reviewerRole`, `taggedFeedback.value()`; do NOT append `reasonText`. The switch is exhaustive over the sealed type, so the build will not compile without this case. (Coordinate with 3.20's `AcceptImplementationCommand` addition if both land — both touch the same two files.)
  - [x] Add implementation-loop methods to the loop-count port. Extend `application/workflow/spi/WorkflowRunRejectionLoopPort.java` with `int incrementAndReadImplementationLoopCount(String workflowRunPublicId)` (atomic `UPDATE … SET implementation_rejection_loop_count = implementation_rejection_loop_count + 1 … RETURNING …`, throws `RUN_NOT_FOUND` on no row). **Reuse** the existing `markEscalationOnce` / `isEscalationMarkerSet` (shared marker — D7). Implement the new SQL in `adapters/persistence/WorkflowRunPersistenceAdapter.java` mirroring `incrementAndReadLoopCount` (lines 109–128).
  - [x] Create `application/workflow/ImplementationRejectionEscalationThresholdProvider.java` mirroring `SpecRejectionEscalationThresholdProvider`: `@Value("${deliveryline.workflow.implementation-rejection-escalation-threshold:3}") int threshold`, `< 1` falls back to `3` with a WARN. Add the key to `src/main/resources/application.yml` (and `src/test/resources/application.yml` set to a deterministic value if any `@SpringBootTest` asserts on it — see [[validated-config-needs-test-yaml]]; the `@Value` default makes test-yaml optional but set it for parity).

- [x] **Task 4 — `TechnicalApprovalService.rejectImplementation`** (AC: 2, 3, 4, 5, 6, 7) — the heart of the story
  - [x] Create `application/approval/TechnicalApprovalService.java` (net-new — 3.20's `acceptImplementation` is NOT built yet; this story creates the class with only `rejectImplementation`; 3.20 adds `acceptImplementation` later). Inject the same collaborators `ApprovalService` uses: `ArtifactService`/`ArtifactRecordPort`, `ApprovalWritePort`, `WorkflowEventWritePort`, `WorkflowTransitionService`, `WorkflowOrchestrationService`, `WorkflowRunRejectionLoopPort`, `ImplementationRejectionEscalationThresholdProvider`, `Clock`, `PublicIdPrefixes`.
  - [x] `public ApprovalResult rejectImplementation(RejectImplementationCommand command)` annotated `@Transactional(propagation = Propagation.MANDATORY)` (NOT REQUIRES_NEW — atomicity; mirror `ApprovalService.rejectSpec`). Method flow, in order:
    1. Resolve the artifact; **guard** `artifact.artifactType() ∈ {IMPLEMENTATION_PLAN, PR_OUTPUT}` else raise `ARTIFACT_TYPE_MISMATCH` (mirror the `rejectSpec` spec-type guard at lines 333–335).
    2. **Version-binding check** (artifact version + context-bundle version) → `APPROVAL_VERSION_MISMATCH` on mismatch, before any write (mirror lines 341–350 + `resolveCurrentContextBundleVersion` lines 509–529). Do NOT check approval-eligibility (rejection of a stale/unavailable artifact is a valid decision — mirror `rejectSpec` OQ-3).
    3. Guard `taggedFeedback != null` (defense-in-depth) → `MISSING_REJECTION_TAXONOMY`; guard developer-subset membership when `reviewerRole=developer`.
    4. Insert `approvals` row via `ApprovalWritePort.insert(new NewApproval(... DECISION_REJECTED, reasonText, taggedFeedback.value(), decidedAt, idempotencyKey))`. DB UNIQUE `uq_approvals_idempotency_key` → `IDEMPOTENCY_KEY_CONFLICT`.
    5. Append `WorkflowEventType.APPROVAL_REJECTED` event with details `{approvalId, artifactId, artifactVersion, contextBundleVersion, reviewerRole, taggedFeedback, implementationRejectionLoopCount, idempotencyKey, correlationId?}`.
    6. `int newLoopCount = loopPort.incrementAndReadImplementationLoopCount(runId)`.
    7. Escalation block: if `newLoopCount >= threshold`, idempotent `markEscalationOnce` → append `ESCALATION_REQUIRED` event once with `details.reason='implementation_rejection_loop_threshold_exceeded'` (mirror lines 395–440 verbatim, including the concurrent-flip race handling).
    8. **Transition** to `WorkflowState.EXECUTING` (BOTH artifact kinds — D3) via `workflowTransitionService.transition(runId, EXECUTING, actor, "reject implementation", idempotencyKey, transitionDetails)`.
    9. **Re-dispatch** inside the same tx: `IMPLEMENTATION_PLAN → workflowOrchestrationService.retryPlanGeneration(runId, correlationId)`; `PR_OUTPUT → workflowOrchestrationService.retryImplementation(runId, correlationId)`. (Both are pure re-dispatch, no transition — Trap T1 from 3.11/3.12.)
    10. Return `ApprovalResult` with `resultingState = EXECUTING`.
  - [x] Add a `TechnicalApprovalServiceTransactionalityTest` analog asserting `MANDATORY` propagation (direct call without an outer tx fails fast).
  - [x] ArchUnit: if 3.21 creates `TechnicalApprovalService`, extend `ArchitectureBoundaryTest`/`ArchitectureRuleCatalog` so the class is pinned to `application.approval` depending only on `application.*` ports + `domain.*` types (no `adapters.*`, no JPA entities), mirroring the `ApprovalService` rule (verify in Failsafe, [[archunit-runs-in-failsafe-not-surefire]]). If 3.20 already added it, no change.

- [x] **Task 5 — `WorkflowCommandService.rejectImplementation` wrapper** (AC: 3, 4, 5) — makes the service invocable/testable; REST surface deferred to 3.24
  - [x] Add `@Transactional public WorkflowStateChangeResult rejectImplementation(RejectImplementationCommand command)` to `WorkflowCommandService` delegating via `executeIdempotent(command, this::rejectImplementationInternal, this::replayStateChange)`, mirroring `rejectSpec` (lines 134–137, 252–270). `rejectImplementationInternal` calls `technicalApprovalService.rejectImplementation(command)` inside the `@Transactional` boundary and maps `ApprovalResult → WorkflowStateChangeResult`.
  - [x] **`TechnicalApprovalService` ctor dep:** if story 3.20 already threaded `TechnicalApprovalService` into the `WorkflowCommandService` constructor, reuse it. If 3.21 lands first, add it to the ctor — this fans out to every `new WorkflowCommandService(...)` test site (grep `src/test`, thread the new arg; Trap T10 in 3.20, see [[two-public-constructors-need-autowired]]).
  - [x] Add the replay resulting-state case in the `switch` (lines 537–543): `case RejectImplementationCommand ignored -> WorkflowState.EXECUTING;` (both plan + prOutput rejection resolve to `Executing` per D3 — a hard-coded replay is correct here, unlike 3.20's `acceptImplementation` whose target is artifact-type-dependent and needs a current-state re-read).
  - [x] MDC scope `WORKFLOW_RUN_ID` around the call (mirror `rejectSpecInternal` lines 260–266).

- [x] **Task 6 — `MISSING_REJECTION_TAXONOMY` DomainErrorCode (three-sites)** (AC: 7) — see [[new-domainerrorcode-three-sites]]
  - [x] Site 1: add `MISSING_REJECTION_TAXONOMY("MISSING_REJECTION_TAXONOMY")` to `domain/registry/DomainErrorCode.java`.
  - [x] Site 2: `register(...)` it in `adapters/rest/ProblemDetailsCatalog.java#createMetadata` with `HttpStatus.BAD_REQUEST`, a title, `retryable=false`.
  - [x] Site 3: add the problem-type URI to `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json#problemTypeUris` (`https://deliveryline.local/problems/missing-rejection-taxonomy`).
  - [x] `APPROVAL_VERSION_MISMATCH`, `IDEMPOTENCY_KEY_CONFLICT`, `ARTIFACT_TYPE_MISMATCH`, `RUN_NOT_FOUND` already exist — reuse, do not re-add. **Do NOT add `INVALID_REJECTION_TAXONOMY`** here — that is the REST-layer concern of story 3.24 (the service takes a typed enum).

- [x] **Task 7 — `reject_implementation` allowed-action** (AC: 9)
  - [x] Add `REJECT_IMPLEMENTATION("reject_implementation")` to `domain/registry/AllowedAction.java`.
  - [x] In `application/workflow/WorkflowInspectionService.java#computeActionMatrix`, the `WAITING_FOR_REVIEW + developer` branch (the seam ~line 428; established by 3.20) must be **additive**: add `REJECT_IMPLEMENTATION`. If 3.20 landed first, the branch returns `[ACCEPT_IMPLEMENTATION, REJECT_IMPLEMENTATION, VIEW_ONLY]`; if 3.21 lands first, `[REJECT_IMPLEMENTATION, VIEW_ONLY]` and 3.20 adds `ACCEPT_IMPLEMENTATION` later. Do NOT invent `takeover_workflow` (3.22). Reuse `ROLE_DEVELOPER` / `RECOGNIZED_ACTOR_ROLES` (add `developer` if 3.20 hasn't).
  - [x] Add `"reject_implementation"` to `src/test/resources/contracts/frontend/allowed-actions.placeholder.json` so `RegistryContractTest.allowedActionsStayAlignedWithFrontendPlaceholder()` stays green; add a pin to `architecture/AllowedActionRegistryPinTest` asserting `AllowedAction.REJECT_IMPLEMENTATION.value().equals("reject_implementation")` (mirror 3.20's `ACCEPT_IMPLEMENTATION` pin).
  - [x] Update `WorkflowInspectionServiceAllowedActionsTest`: assert the `WAITING_FOR_REVIEW + developer` row includes `REJECT_IMPLEMENTATION` (additive with any 3.20 `ACCEPT_IMPLEMENTATION`); other roles stay `[VIEW_ONLY]`.

- [x] **Task 8 — Test suite** (AC: 10)
  - [x] Unit: `TechnicalApprovalServiceTest` (mock collaborators) mirroring the `ApprovalService` reject-spec tests — happy plan-reject → `Executing` + `retryPlanGeneration` invoked; happy prOutput-reject → `Executing` + `retryImplementation` invoked; version-mismatch (no insert/increment/transition); missing-taxonomy; artifact-type guard (`spec`); idempotency-conflict; threshold-not-exceeded (no escalation event) vs threshold-exceeded (one escalation event); escalation idempotent on repeat.
  - [x] Contract/Testcontainers IT (name it `*IT`, see [[springboot-testcontainers-test-must-be-IT]]): end-to-end reject of a seeded `prOutput` artifact verifying the `approvals` row (`decision=rejected`, `rejection_taxonomy=` developer value), `approval.rejected` + `escalation.required` events, counter increment, transition to `Executing`, and that the regenerated context bundle carries the `implementationPlan.rejection` feedback reference. Seed an approved plan + a prOutput artifact directly (no live trigger — 3.23/3.24 own that).
  - [x] AR34a contract test (AC7): assert no rejection row persists with a null `rejection_taxonomy` (DB CHECK `ck_approvals_decision_taxonomy_paired` + service guard both enforce this).
  - [x] Registry drift: `RegistryContractTest` green for the new `AllowedAction`, taxonomy values, and `DomainErrorCode`.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `artifactId`, `approvalId`). Use MDC where the framework supports it; otherwise pass as parameters.
  - [x] Never log secrets, payload bytes, raw tokens, or full PII. Reference the redaction policy when in doubt.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use a list-appender or `OutputCaptureExtension`). Mirror `rejectSpec`'s escalation WARN: `log.warn("rejectImplementation escalation marker raised workflowRunId={} loopCount={} threshold={}", ...)`.

### Review Findings

_Code review 2026-06-14 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 3 patch, 1 defer, 6 dismissed as noise._

- [x] [Review][Patch] Developer-taxonomy subset guard only fired when `reviewerRole=="developer"` — a non-`developer` role bypassed the subset check and accepted product taxonomy values (`missing_scope` etc.). **FIXED 2026-06-14:** guard is now unconditional — `reviewerRole` must be `developer` AND `taggedFeedback` must be a developer-subset value. [deliveryline-backend/src/main/java/org/dradgo/application/approval/TechnicalApprovalService.java:384]
- [x] [Review][Patch] V13 widened the **shared** rejection-taxonomy CHECK (`ck_approvals_rejection_taxonomy` / `ck_workflow_events_rejection_taxonomy`) to the 8-value union, removing the DB backstop that previously rejected developer values on a SPEC/product rejection; `ApprovalService.rejectSpec` had no taxonomy subset guard, so a product spec-rejection could persist a developer taxonomy value (pollutes AR34a / rework metrics). **FIXED 2026-06-14:** added `RejectionTaxonomy.isProductValue()` + a product-subset guard in `rejectSpec` (`INVALID_COMMAND_PAYLOAD`, reason `spec_rejection_requires_product_taxonomy`), symmetric to the developer-subset guard. [deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java]
- [x] [Review][Patch] Story File List + Dev Notes claimed "No frontend / openapi.json / schema.d.ts change", but both are modified (5 developer enum values propagate into the existing `RejectSpecRequest.taggedFeedback` enum). **FIXED 2026-06-14:** Dev Notes corrected to "No NEW REST surface" + generated-propagation note; File List updated with `openapi.json` + `schema.d.ts`.
- [x] [Review][Defer] Artifact-type guard excludes only `SPEC`; the re-dispatch `if IMPLEMENTATION_PLAN … else` defaults the else to PR_OUTPUT with no allowlist. Currently safe (`ArtifactType` has exactly 3 values) and mirrors sibling `acceptImplementation`, but a future `ArtifactType` would be silently treated as `prOutput` and re-dispatch the implementation runner. Harden with an explicit `!= IMPLEMENTATION_PLAN && != PR_OUTPUT → throw` allowlist + `else throw illegalState` on re-dispatch. [deliveryline-backend/src/main/java/org/dradgo/application/approval/TechnicalApprovalService.java:354] — deferred, latent (no current trigger)

## Dev Notes

### Reconciliations & Decisions (READ FIRST — these override the literal epic AC text)

- **D1 — Flyway version is V13, NOT V6 (AC8 stale).** V6 is taken (`V6__integration_link_active_uniqueness.sql`); latest on disk is V11; V12 is claimed-but-not-yet-on-disk by story **3-17a** (`V12__add_queue_state_columns.sql`). Use `V13__add_implementation_loop_columns.sql`. Mirror V7 verbatim (which itself documents the same "epic said V2, V2 was taken" renumbering precedent). [Source: `db/migration/V7__add_spec_rejection_loop_columns.sql`; `_bmad-output/implementation-artifacts/3-17a-*.md`]
- **D2 — `TechnicalApprovalService` + the developer-review scaffolding are SHARED with story 3.20 (`acceptImplementation`), which is ALSO `ready-for-dev` — coordinate, don't collide.** This is an out-of-slice pull (epic-3b stays `deferred`). **Whichever of 3.20/3.21 is dev'd first CREATES the shared scaffolding; the other ADDS to it incrementally.** Shared scaffolding (introduced by 3.20, additively extended by 3.21):
  - `TechnicalApprovalService` class (`application/approval`, `@Service`, `MANDATORY` propagation) — 3.20 creates it with `acceptImplementation`; 3.21 adds `rejectImplementation`. If 3.21 lands first, create the class with `rejectImplementation` only.
  - `developer` actor role: `WorkflowInspectionService.ROLE_DEVELOPER` + entry in `RECOGNIZED_ACTOR_ROLES` (so `getAllowedActions(runId,"developer")` doesn't throw `UNKNOWN_ACTOR_ROLE`) — 3.20 adds it; 3.21 reuses if present, else adds.
  - The `WAITING_FOR_REVIEW + developer` branch in `computeActionMatrix` — 3.20 returns `[ACCEPT_IMPLEMENTATION, VIEW_ONLY]`; **3.21 makes it additive → `[ACCEPT_IMPLEMENTATION?, REJECT_IMPLEMENTATION, VIEW_ONLY]`** (include `ACCEPT_IMPLEMENTATION` only if 3.20 already landed; don't invent it). The seam comment notes `takeover` (3.22) still pending.
  - `TechnicalApprovalService` into the `WorkflowCommandService` constructor — 3.20 threads it in (fans out to every `new WorkflowCommandService(...)` test site, Trap T10); 3.21 reuses the same dep if 3.20 landed first.
  - Optional shared `ApprovalVersionBinder` (`application/approval`) extracting `resolveCurrentContextBundleVersion` + `assertVersionsMatch` from `ApprovalService` — 3.20 may introduce it; **3.21 should reuse it** for its own version-binding rather than re-duplicating. If neither created it yet, mirror `ApprovalService.resolveCurrentContextBundleVersion` (lines ~509–529) directly.
- **D3 — BLOCKING: plan-rejection transition target is `Executing`, NOT `Investigating` (AC4 conflicts with the live state machine).** The live `WorkflowTransitionTable` permits from `WAITING_FOR_REVIEW` only `→ {COMPLETED, EXECUTING, TAKEN_OVER, RECONCILED}` — **`Investigating` is not legal** (`WorkflowTransitionTable.java:76–82`). Moreover, per the 3.11 architecture, **plan generation is an `Executing`-stage sub-stage** (`dispatchPlanGeneration` fires on `WaitingForSpecApproval → Executing`); `Investigating` is the **spec** stage. So epic AC4's "transition to `Investigating` to re-enter plan generation" is doubly wrong (illegal edge + wrong stage). **Decision: both `implementationPlan` and `prOutput` rejection transition `WaitingForReview → Executing`, then branch the re-dispatch by artifact type** (`IMPLEMENTATION_PLAN → retryPlanGeneration`, `PR_OUTPUT → retryImplementation`). `ContextBundleService.deriveExecutionSubStage` independently picks the correct sub-stage (PR_OUTPUT only when an approved plan exists). This reuses a legal edge and avoids a transition-table change (which would fan out to `WorkflowTransitionTableTest` + `TransitionTableCrossProductFoundationContract`, see [[transition-table-change-fans-to-contracts]]). **OQ-1 for Alex** — confirm before coding; the only alternative (epic-literal `Investigating`) requires a transition-table amendment AND is semantically incorrect.
- **D4 — Developer taxonomy extends the single `RejectionTaxonomy` enum, role-scoped at validation (not a second enum).** `RejectionTaxonomy` currently holds 3 product values; `RejectSpecCommand.taggedFeedback` is typed `RejectionTaxonomy`. Mirror that: add the 5 developer values to the same enum and enforce the role-appropriate subset in the service (developer role → developer subset). This matches the epic's own wording "constrained to wider value set when role=developer" (AC2) and keeps the command's typed field. Widen the two DB CHECK constraints accordingly. **OQ-3** — confirm single-enum vs separate `DeveloperRejectionTaxonomy`; single-enum is recommended for minimal fan-out.
- **D5 — `escalation_marker_set` is shared (reuse V7's column).** One escalation marker per run; spec-loop and implementation-loop escalations both flip the same boolean. The new migration adds only `implementation_rejection_loop_count`. Reuse `markEscalationOnce` / `isEscalationMarkerSet` unchanged.
- **D6 — `MISSING_REJECTION_TAXONOMY` only (no `INVALID_REJECTION_TAXONOMY`) at the service layer.** The command's `@NotNull RejectionTaxonomy taggedFeedback` makes "missing" structurally impossible at the boundary; add the code + guard for the AR34a contract (AC7) and DB-CHECK defense-in-depth. `INVALID_REJECTION_TAXONOMY` is the REST deserialization concern of story 3.24 — do not add it here.
- **D7 — New port methods for the implementation loop counter; reuse the spec port's escalation methods.** Add `incrementAndReadImplementationLoopCount` to `WorkflowRunRejectionLoopPort` (new `UPDATE … RETURNING` on the new column); the escalation marker methods are stage-agnostic and reused as-is.
- **D8 — Context-bundle feedback threading gap on the PLAN re-dispatch path (enhancement / OQ-2).** `ContextBundleService.collectExecutionFeedbackReferences` threads `implementationPlan.rejection` feedback **only for the `PR_OUTPUT` sub-stage** (lines 817–824). So the `prOutput`-rejection path (AC5) already carries the rejection into the regenerated bundle ✅, but the **`implementationPlan`-rejection path does NOT** — a regenerated plan won't see why the prior plan was rejected, weakening the "feedback flows back into the rebuild loop" goal for plan rejections. AC5 only mandates the prOutput path, so this is **not a blocker**, but recommend extending `collectExecutionFeedbackReferences` to also include `implementationPlan.rejection` for the plan sub-stage. **OQ-2 for Alex** — in-scope enhancement or defer to a follow-up (ContextBundleService is story 3.10's domain).

### Architecture patterns & constraints

- **Hexagonal boundaries.** Service lives in `application.approval`; it must NOT import `org.dradgo.adapters..` ([[application-cannot-import-adapters]]). Reach persistence via SPI ports (`ApprovalWritePort`, `WorkflowRunRejectionLoopPort`, `WorkflowEventWritePort`). Project-owned records cross the boundary.
- **Transaction model (Trap T-tx).** `rejectImplementation` is `@Transactional(propagation = Propagation.MANDATORY)` — it MUST run inside the `WorkflowCommandService.rejectImplementation` `@Transactional` boundary so the approvals row + events + counter increment + transition + re-dispatch are all-or-nothing. A `REQUIRES_NEW` here would persist the approval even if the transition rolls back. Pin with a transactionality test (mirror `ApprovalServiceTransactionalityTest`). [Source: `ApprovalService.java:139,307`]
- **Idempotency.** The command carries `idempotencyKey`; `WorkflowCommandService.executeIdempotent` handles replay (same key + fingerprint → replay same state) vs `IDEMPOTENCY_KEY_CONFLICT` (same key + different fingerprint). DB `uq_approvals_idempotency_key` is the defense-in-depth backstop in `ApprovalWritePersistenceAdapter`. Fingerprint excludes `reasonText`. [Source: `ApprovalService.java`, `ApprovalWritePersistenceAdapter.java:121–162`]
- **Version binding before writes.** Resolve current artifact version + context-bundle version (bootstrap = 1 when no linked runner execution) and compare to expected; raise `APPROVAL_VERSION_MISMATCH` first. [Source: `ApprovalService.java:341–350, 509–529]
- **Re-dispatch entry points already exist** (built by 3.11/3.12): `retryPlanGeneration(runId, correlationId)` (`WorkflowOrchestrationService.java:479–489`) and `retryImplementation(runId, correlationId)` (lines 556–566). Both are pure re-dispatch (no transition). They regenerate the context bundle at the next execution bundle version. [Source: `WorkflowOrchestrationService.java`]
- **Artifact types**: `ArtifactType.{SPEC, IMPLEMENTATION_PLAN("implementationPlan"), PR_OUTPUT("prOutput")}`. Guard `IMPLEMENTATION_PLAN | PR_OUTPUT`, never `SPEC`. [Source: `domain/registry/ArtifactType.java`]
- **Event types + detail keys already exist** — reuse, do NOT add: `WorkflowEventType.{APPROVAL_REJECTED("approval.rejected"), ESCALATION_REQUIRED("escalation.required")}`; `WorkflowEventDetailKeys.{REVIEWER_ROLE, TAGGED_FEEDBACK, REASON}` are already allow-listed. **No `workflow-event-types.fixture.json` / fixture-stream change needed** (no new event type). [Source: `WorkflowEventType.java`, `WorkflowEventDetailKeys.java`]

### Exact patterns to mirror (file:line)

- `ApprovalService.rejectSpec` — the master template: `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java:307–507` (version check 341–350, row insert 352–372, event 380–393, counter 374–377, escalation 395–440, transition 447–453, re-dispatch 462–463).
- `RejectSpecCommand` — command shape to mirror: `application/workflow/commands/RejectSpecCommand.java:26–38`.
- `ApprovalResult` (return) — `application/approval/ApprovalResult.java:29–38`.
- `ApprovalWritePort.NewApproval` — `application/approval/spi/ApprovalWritePort.java:51–64`.
- `SpecRejectionEscalationThresholdProvider` — provider to mirror: `application/workflow/SpecRejectionEscalationThresholdProvider.java`.
- `WorkflowRunRejectionLoopPort` + adapter SQL — `application/workflow/spi/WorkflowRunRejectionLoopPort.java:14–48`; `adapters/persistence/WorkflowRunPersistenceAdapter.java:109–157`.
- `WorkflowRunEntity.specRejectionLoopCount` — entity field to mirror: `adapters/persistence/entity/WorkflowRunEntity.java:46–54`.
- `WorkflowCommandService.rejectSpec/rejectSpecInternal` + replay switch — `application/workflow/WorkflowCommandService.java:134–137, 252–270, 537–543`.
- `V7__add_spec_rejection_loop_columns.sql` — migration to mirror.
- `FlywaySchemaContractTest.flywayMigrateIsReplaySafeAndChecksumStable()` — replay-safety test (AC8) — `src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java:468–497`.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - Public application-service methods → `INFO` on entry + `INFO` on success / `WARN` on typed-domain rejection / `ERROR` on unexpected failure.
  - Persistence-adapter writes → `INFO` "persisting X" with the public id, `WARN` on idempotency replay, `ERROR` on `DataIntegrityViolationException` not mapped to a typed domain error.
  - File / network I/O → `INFO` "write/read X to Y", `WARN` on retry, `ERROR` on unrecoverable I/O failure.
  - State-machine transitions → `INFO` "transitioned X from {from} to {to}".
  - Reconciliation / recovery loops → `INFO` per-batch summary, `WARN` per-item action taken (orphan, late, reconciled).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus any entity public ids touched.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields. Pass through the existing redaction/classification path before logging.
- **Test contract:** new logging surfaces must be pinned by at least one focused test (list-appender or Spring Boot `OutputCaptureExtension`) so downstream refactors can't silently delete them.

### Project Structure Notes

- New files: `application/approval/TechnicalApprovalService.java` (if 3.21 lands before 3.20), `application/workflow/commands/RejectImplementationCommand.java`, `application/workflow/ImplementationRejectionEscalationThresholdProvider.java`, `db/migration/V13__add_implementation_loop_columns.sql`, plus tests (`TechnicalApprovalServiceTest`/`…RejectImplementationTest`, `TechnicalApprovalServiceTransactionalityTest`, a Testcontainers `*IT`).
- Modified: `application/workflow/commands/WorkflowCommand.java` (+`permits` entry), `application/idempotency/WorkflowCommandFingerprintFactory.java` (+exhaustive `case`), `WorkflowRunEntity` (+field), `WorkflowRunRejectionLoopPort` (+method), `WorkflowRunPersistenceAdapter` (+SQL), `RejectionTaxonomy` (+5 values), `AllowedAction` (+1 value), `WorkflowInspectionService` (developer matrix branch — additive with 3.20), `DomainErrorCode` (+`MISSING_REJECTION_TAXONOMY`), `ProblemDetailsCatalog` (+register), `WorkflowCommandService` (+`rejectImplementation` + replay case; ctor dep coordinated with 3.20), `application.yml` (+ test yaml, threshold key), and registry/fixture files: `registry-api-schema-placeholders.json`, `allowed-actions.placeholder.json` (+ V1 CHECK widening lives in V13).
- **No NEW REST surface** — REST endpoints are story 3.24; this story is service + persistence + registry only. **Correction (code-review 2026-06-14):** `openapi.json` and `deliveryline-frontend/src/lib/api/schema.d.ts` *are* touched, but only as **generated propagation** — the 5 new `RejectionTaxonomy` developer values flow into the pre-existing `RejectSpecRequest.taggedFeedback` enum the OpenAPI generator already emits. No new endpoint/request/response schema is added. Regenerate the OpenAPI snapshot cross-shell (see [[openapi-regen-platform-shim]]) and commit both generated files alongside the enum change.

### Gate / verification notes (Windows + this repo)

- Use **PowerShell** for all build/test gates — the RTK hook corrupts only the Bash tool ([[rtk-hook-only-matches-bash]]).
- New `DomainErrorCode` → verify the three sites with `-Pfoundation-gate` ([[new-domainerrorcode-three-sites]]).
- Testcontainers tests must be named `*IT` or they leak into the no-Docker Surefire tier and red CI ([[springboot-testcontainers-test-must-be-IT]]). If the IT is heavy, also `@Tag("docker-runner-it")` is NOT needed here (that tag is for the runner-image tier) — a plain `*IT` routes to Failsafe.
- Local green ≠ CI green; confirm the migration + foundation-gate in a clean env / WSL2 Linux before claiming done ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).
- Commit without the Claude co-author trailer ([[commit-no-claude-coauthor]]).

### References

- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.21` (lines 424–441)]
- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.20` (lines 402–422) — sibling `acceptImplementation`, the version-binding rigor reference]
- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.24` (lines 483–500) — the REST endpoint that consumes this service; `INVALID_REJECTION_TAXONOMY` belongs there]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java` — `rejectSpec` master template]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java:76–82` — legal edges from `WaitingForReview`]
- [Source: `deliveryline-backend/src/main/resources/db/migration/V7__add_spec_rejection_loop_columns.sql` — migration mirror]

## Open Questions (for Alex — surfaced, non-blocking unless marked)

1. **OQ-1 (BLOCKING — D3): Plan-rejection transition target.** Recommended: `WaitingForReview → Executing` + `retryPlanGeneration` (legal edge, correct stage). Epic AC4 literally says `Investigating`, which is illegal in the live transition table and is the spec stage. Confirm we take the recommended `Executing` path (no transition-table change) rather than amending the table to add `WaitingForReview → Investigating`.
2. **OQ-2 (D8): Plan-stage feedback threading.** Should `ContextBundleService.collectExecutionFeedbackReferences` be extended now to thread `implementationPlan.rejection` into the **plan** sub-stage bundle (so a regenerated plan sees prior plan rejections), or defer to a follow-up? AC5 only mandates the prOutput path, which already works.
3. **OQ-3 (D4): Taxonomy shape.** Single `RejectionTaxonomy` enum (role-scoped subsets) — recommended — vs a separate `DeveloperRejectionTaxonomy` enum. Single-enum minimizes fan-out and keeps `RejectImplementationCommand.taggedFeedback` typed like `RejectSpecCommand`.
4. **OQ-4: Escalation marker sharing (D5).** Confirm reusing the single `escalation_marker_set` column for both spec-loop and implementation-loop escalations (one marker per run) rather than a stage-specific marker.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Compile + targeted unit tier (`mvnw -pl deliveryline-backend test -Dtest=...`): 84 tests green
  (reject unit test, accept unit test, transactionality pin, fingerprint, command-type,
  allowed-actions matrix, context-bundle execution-stage).
- Full Failsafe integration tier (`verify`, Testcontainers Postgres): all ITs + `*ContractTest`
  green incl. new `TechnicalApprovalServiceRejectImplementationContractTest` (5), `RegistryContractTest`,
  `FlywaySchemaContractTest`, `ProblemDetailsContractTest`.
- Foundation-gate (`-Pfoundation-gate`): `CommandModelSymmetryFoundationContract` (3) +
  `TransitionTableCrossProductFoundationContract` (4) green.
- Spotless / Checkstyle / ArchUnit / SpotBugs: green (ran `spotless:apply`; re-anchored the
  line-anchored Checkstyle `ForbiddenThreadSleep` suppression for `WorkflowCommandService` 769→802
  after the new methods shifted the `Thread.sleep` line — [[checkstyle-suppressions-line-anchored]]).

### Completion Notes List

Story 3.20 had already landed, so the shared scaffolding (`TechnicalApprovalService`,
`AcceptImplementationCommand`, `ApprovalVersionBinder`, `developer` role + `WAITING_FOR_REVIEW`
matrix branch, `TechnicalApprovalService` ctor dep in `WorkflowCommandService`) existed; 3.21 added
to it additively.

Decisions confirmed with Alex before coding: **OQ-1 → Executing** (both artifact kinds transition
`WaitingForReview → Executing`, branching re-dispatch by type — no transition-table change);
**OQ-2 → Extend now** (threaded `implementationPlan.rejection` into the regenerated
IMPLEMENTATION_PLAN sub-stage bundle too, not just PR_OUTPUT). Defaults taken: **OQ-3 → single
role-scoped `RejectionTaxonomy` enum**; **OQ-4 → shared `escalation_marker_set` column**.

- ✅ AC1–AC2: `RejectImplementationCommand` + `approvals(decision=rejected, reviewer_role=developer,
  rejection_taxonomy=<developer value>)`; V13 widened both rejection-taxonomy CHECK constraints to the
  8-value union.
- ✅ AC3: version-binding (artifact + context-bundle) runs first via the shared `ApprovalVersionBinder`
  → `APPROVAL_VERSION_MISMATCH`, no row/counter/transition on mismatch.
- ✅ AC4/AC5: `implementation_rejection_loop_count` increment + `approval.rejected` event + transition
  to `Executing` + type-branched re-dispatch (`retryPlanGeneration` / `retryImplementation`), all in one
  MANDATORY-propagation transaction.
- ✅ AC6: shared escalation marker, `escalation.required` emitted at most once per run; configurable
  `deliveryline.workflow.implementation-rejection-escalation-threshold` (default 3).
- ✅ AC7: `MISSING_REJECTION_TAXONOMY` three-sites code + AR34a IT asserts non-null persisted taxonomy.
- ✅ AC8: `V13__add_implementation_loop_columns.sql` (replay-safe, asserted by FlywaySchemaContractTest).
- ✅ AC9: `AllowedAction.REJECT_IMPLEMENTATION`; additive `WAITING_FOR_REVIEW + developer` matrix branch
  → `[ACCEPT_IMPLEMENTATION, REJECT_IMPLEMENTATION, VIEW_ONLY]`; placeholder + registry pins updated.
- ✅ AC10: unit + IT suites cover all listed cases.

**Reconciliation note (Task 4 / Task 6):** the epic/story text said the artifact-type guard raises
`ARTIFACT_TYPE_MISMATCH`, but that `DomainErrorCode` does not exist and the live sibling
`acceptImplementation` uses `INVALID_COMMAND_PAYLOAD` for the same guard. Mirrored the sibling
(`INVALID_COMMAND_PAYLOAD`) for consistency and to avoid an unused new code; the developer-subset
role-scoping guard also surfaces `INVALID_COMMAND_PAYLOAD`.

**Latent-bug fix (in-scope blocker):** `WorkflowRunPersistenceAdapter.markEscalationOnce` read the
boolean `escalation_marker_set` RETURNING column via `getInt`, which throws on real Postgres
("Bad value for type int : t"). Spec-rejection escalation only had mocked unit coverage, so this was
never exercised against a DB; story 3.21's escalation IT is the first. Changed the SQL to
`returning 1` (the row-presence is all the mapper needs) — fixes both the spec and implementation
escalation real-DB paths.

### File List

**Added (main):**
- `deliveryline-backend/src/main/resources/db/migration/V13__add_implementation_loop_columns.sql`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/RejectImplementationCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/ImplementationRejectionEscalationThresholdProvider.java`

**Added (test):**
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/TechnicalApprovalServiceRejectImplementationTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/TechnicalApprovalServiceRejectImplementationContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/TechnicalApprovalServiceTransactionalityTest.java`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/TechnicalApprovalService.java` (+`rejectImplementation` + 2 ctor deps + helpers)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java` (+`rejectImplementation` wrapper + internal + replay case)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/WorkflowCommand.java` (+`permits` entry)
- `deliveryline-backend/src/main/java/org/dradgo/application/idempotency/WorkflowCommandFingerprintFactory.java` (+exhaustive `case`)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunRejectionLoopPort.java` (+`incrementAndReadImplementationLoopCount`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapter.java` (+SQL/impl; `markEscalationOnce` `returning 1` fix)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowRunEntity.java` (+`implementationRejectionLoopCount`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RejectionTaxonomy.java` (+5 developer values + role-scoping)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` (+`REJECT_IMPLEMENTATION`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (+`MISSING_REJECTION_TAXONOMY`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (+register `MISSING_REJECTION_TAXONOMY`)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (additive developer matrix branch)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/ContextBundleService.java` (OQ-2: plan-substage rejection feedback)
- `deliveryline-backend/src/main/resources/application.yml` (+implementation-rejection-escalation-threshold)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (generated — 5 developer values propagate into the existing `RejectSpecRequest.taggedFeedback` enum)
- `deliveryline-frontend/src/lib/api/schema.d.ts` (generated — same enum widening, regenerated from the OpenAPI snapshot)
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java` (code-review patch — product-subset taxonomy guard on `rejectSpec`, symmetric to the new developer-subset guard; restores the validation backstop the V13 CHECK widening removed)

**Modified (test / config):**
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/TechnicalApprovalServiceAcceptImplementationTest.java` (thread 2 new ctor deps)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/ContextBundleServiceExecutionStageTest.java` (+OQ-2 plan-feedback test)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java` (additive matrix row)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/AllowedActionRegistryPinTest.java` (+reject_implementation pin)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/commands/WorkflowCommandTypeTest.java` (+commandType pin)
- `deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java` (+EXPECTED_PERMITS entry)
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` (+developer taxonomy accepted-values)
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (+problem-type URI)
- `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json` (+reject_implementation)
- `config/checkstyle/suppressions.xml` (re-anchor WorkflowCommandService Thread.sleep suppression 769→802)

### Change Log

| Date | Change |
| --- | --- |
| 2026-06-14 | Implemented story 3.21 — `TechnicalApprovalService.rejectImplementation` with developer-rejection taxonomy, V13 migration (impl loop counter + widened taxonomy CHECKs), `reject_implementation` allowed-action, `MISSING_REJECTION_TAXONOMY` code, OQ-2 plan-substage feedback threading, and fix for the latent `markEscalationOnce` boolean-RETURNING bug. Confirmed OQ-1 (Executing) + OQ-2 (extend now) with Alex. All unit + integration + foundation gates green. |
