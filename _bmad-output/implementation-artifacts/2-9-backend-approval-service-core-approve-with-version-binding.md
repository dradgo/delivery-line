# Story 2.9: Backend - ApprovalService Core (Approve) with Version Binding

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager,
I want `ApprovalService.approveSpec(...)` that binds approvals to a specific artifact version + context bundle version + actor identity + reviewer role,
so that approvals can never apply to a stale artifact (`APPROVAL_VERSION_MISMATCH` if the spec changed under me) and approval attribution is auditable per **FR12** (no implementation progression until spec accepted) and **FR46** (per-decision attribution).

## Acceptance Criteria

1. **Given** the `application.approval` package (already provisioned by story 2.8 — holds `ApprovalSnapshot` + `spi/ApprovalReadPort`), **Then** a new `ApprovalService` exposes `approveSpec(ApproveSpecCommand command) -> ApprovalResult`. `ApprovalService` is the **canonical executor** for the `approve_spec` action (AC11).
2. **Given** the story 1.7 shared command-model pattern (`WorkflowCommand` sealed interface + envelope fields), **Then** `ApproveSpecCommand` carries: `workflowRunId` (`run_…`), `artifactId` (`art_…`), `expectedArtifactVersion` (positive int — the version the reviewer reviewed against), `expectedContextBundleVersion` (positive int — the bundle version associated with that artifact), `actorIdentity`, `actorType` (`= ActorType.HUMAN` for PM approvals), `reviewerRole` (NotBlank, max 128 — e.g. `product_reviewer`), optional `reason` (Size max 1024), and `idempotencyKey`. The existing record already carries `workflowRunId` / `artifactId` / `artifactVersion` (= expectedArtifactVersion) / `contextVersion` (= expectedContextBundleVersion) / `actorIdentity` / `actorType` / `idempotencyKey` / `correlationId` — **this story adds `reviewerRole` + `reason` and keeps the existing field names** (see Trap T1).
3. **Given** the V1 `approvals` table (already provisioned — `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql:167-204`), **Then** a successful approval inserts a row with: `public_id` (`apr_…` via `PublicIdPrefixes.APPROVAL.next()`), `workflow_run_id` (FK), `artifact_id` (FK), `artifact_version` (composite FK pin), `context_bundle_version`, `actor_identity`, `actor_type`, `reviewer_role`, `decision='approved'`, `reason` (nullable), `decided_at = now()`, `idempotency_key` (DB-unique, defense-in-depth alongside `idempotency_records`). `rejection_taxonomy` MUST remain null per the `ck_approvals_decision_taxonomy_paired` CHECK. **No new Flyway migration.**
4. **Given** version-binding enforcement, **When** the artifact's current version differs from `expectedArtifactVersion` OR the run's current context-bundle version (read from the artifact's owning `runner_executions.context_bundle_version` — see Dev Notes "context-bundle version source") differs from `expectedContextBundleVersion`, **Then** raise `DomainException(APPROVAL_VERSION_MISMATCH, …)` (registry value already present in `DomainErrorCode`) with `details` carrying `expectedArtifactVersion`, `currentArtifactVersion`, `expectedContextBundleVersion`, `currentContextBundleVersion`, `artifactId`, `workflowRunId` — matching the Problem Details example from story 1.8. No DB writes, no event append, no transition.
5. **Given** story 1.12 AC6's `ArtifactService.isApprovalEligible(String artifactId)` (`deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactService.java:26`), **When** it returns `false` for the supplied `artifactId`, **Then** raise `DomainException(ARTIFACT_PAYLOAD_UNAVAILABLE, …)` (registry value already present) with `details.artifactId` + `details.reason='not_approval_eligible'` — approval is rejected **before** any DB write, event append, or transition. Run version-binding (AC4) **before** the eligibility check so callers learn about a stale version even when the artifact has been superseded by an unavailable next version.
6. **Given** a successful approval, **When** committed, **Then** in **one transaction** (REQUIRED propagation; participates in the outer `WorkflowCommandService.approveSpec` transaction): (a) the `approvals` row is inserted; (b) a `WorkflowEventType.APPROVAL_APPROVED` event (registry value `"approval.approved"` — already in `WorkflowEventType`) is appended via `WorkflowEventWritePort.append(...)` carrying `details = { approvalId, artifactId, artifactVersion, contextBundleVersion, reviewerRole, idempotencyKey, correlationId? }`; (c) `WorkflowTransitionService.transition(workflowRunId, WorkflowState.EXECUTING, TransitionActor, "approve specification", idempotencyKey, Map.of("approvalId", …, "artifactId", …, "artifactVersion", …, "contextBundleVersion", …, "reviewerRole", …))` is invoked — fulfilling **FR12** (no implementation progression until spec accepted). The transition table (`WorkflowTransitionTable.defaultTable()`) already permits `WAITING_FOR_SPEC_APPROVAL → EXECUTING`.
7. **Given** **FR46** attribution, **Then** the persisted `approvals.reviewer_role` column carries the value supplied on the command and the `approval.approved` event's `details.reviewerRole` carries the same value — proving end-to-end that inspection (CLI `status`/`history`, UI story 2.17) can render `"approved by alex (product_reviewer)"`. A contract test asserts that the event's `details.reviewerRole` equals the persisted row's `reviewer_role` for the same approval.
8. **Given** idempotency (story 1.9), **Then** the existing `WorkflowCommandService.approveSpec` `executeIdempotent(...)` pipeline is the **single entry point** that the surface delegates to; `ApprovalService` itself is invoked inside the pipeline's reserved transaction. Retries with the same `idempotencyKey` + identical `WorkflowCommandFingerprintFactory` fingerprint replay the prior `ApprovalResult` (loaded via `findWorkflowRunForReplay`); same key + **different** fingerprint raises `IDEMPOTENCY_KEY_CONFLICT` (registry value already present). The DB-level `uq_approvals_idempotency_key` UNIQUE constraint is the defense-in-depth backstop — if it ever fires, map the JPA `DataIntegrityViolationException` to `IDEMPOTENCY_KEY_CONFLICT` (do NOT leak the persistence exception).
9. **Given** an attempt to approve from an invalid current state (e.g. run not in `WAITING_FOR_SPEC_APPROVAL`), **When** the transition is attempted, **Then** `WorkflowTransitionTable` raises `DomainException(ILLEGAL_TRANSITION, …)` (registry value already present) and the exception propagates out of `ApprovalService` — `ApprovalService` does **not** silently skip the transition, and because the transition runs **after** the row insert + event append in the same transaction, the entire transaction rolls back (no orphan approval row, no orphan event). A contract test seeds a run in `INVESTIGATING` and asserts the rollback shape.
10. **Given** contract tests in `deliveryline-backend/src/test/java/org/dradgo/application/approval/` and `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/`, **Then** coverage includes: (a) happy-path approval — state transitions `WaitingForSpecApproval → Executing`, `approvals` row written with all 7 attribution fields, `approval.approved` + `workflow.stateChanged` events both appended in order; (b) version-mismatch rejection — no row written, no event, state unchanged; (c) unavailable-artifact rejection (`ArtifactService.isApprovalEligible` returns false) — same; (d) idempotent replay — same `idempotencyKey` + fingerprint returns the prior `ApprovalResult` without a second row; (e) idempotency-key conflict — different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`; (f) illegal-state-transition — run in `INVESTIGATING` rolls back entire transaction; (g) attribution end-to-end — the `approval.approved` event's `details.reviewerRole` equals the persisted row's `reviewer_role`; (h) `ApprovalReadPort.findLatestApprovedForArtifactLineage(...)` returns the just-written row (story 2.8 read path inherits 2.9's writer).
11. **Given** the central registry (`AllowedAction` — already carries `APPROVE_SPEC("approve_spec")`), **Then** a smoke test in `deliveryline-backend/src/test/java/org/dradgo/architecture/` asserts `AllowedAction.APPROVE_SPEC.value().equals("approve_spec")` — pinning the registry value `ApprovalService` is the canonical executor for. The full state×role→action-set logic in `WorkflowInspectionService.getAllowedActions` lands in story 2.14; story 2.9 does NOT extend that service.

**Scope guardrails:**

- **Out of scope for 2.9:** `ApprovalService.rejectSpec(...)` + escalation (story 2.10), clarifications (story 2.11), allowed-actions endpoint (story 2.14), REST mutation endpoints with rich Problem Details mapping (story 2.13 — the existing `WorkflowController.approveSpec` stays as-is until 2.13 rebuilds it), frontend approval bar (story 2.19).
- **Approvals table is already provisioned** (V1 migration). **No new Flyway migration in this story.**
- **Existing CLI/REST shape preserved:** `WorkflowController.approveSpec` keeps its current contract (story 2.13 rebuilds it). No new CLI command in this story (story 2.13 will add `deliveryline approve-spec`).

## Tasks / Subtasks

- [x] **Task 1: Extend `ApproveSpecCommand` with `reviewerRole` + `reason`** (AC: 2)
  - [x] Edit `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/ApproveSpecCommand.java`. Add two record components: `@NotBlank @Size(max = 128) String reviewerRole` and `@Size(max = 1024) String reason` (nullable). KEEP the existing field names `artifactVersion` and `contextVersion` (they ARE the expected versions — renaming would ripple into `WorkflowCommandFingerprintFactory`, `WorkflowCommandService`, `WorkflowController`, and 3 tests; see Trap T1).
  - [x] Update the Javadoc on `ApproveSpecCommand` to clarify: "fingerprint fields after the shared envelope are `workflowRunId`, `artifactId`, `artifactVersion` (expected), `contextVersion` (expected), `reviewerRole`. `reason` is intentionally excluded from the fingerprint — wording changes on the same review must replay as idempotent."
  - [x] Verify `WorkflowCommandFingerprintFactory.fingerprintFor(ApproveSpecCommand)` includes `reviewerRole` in its serialization; if it does not, add it (read the factory first — match the existing field ordering). `reason` MUST NOT enter the fingerprint (per Javadoc above).
  - [x] Update `WorkflowController.approveSpec` request DTO + mapping to pass `reviewerRole` through (default to a configured `deliveryline.approval.default-reviewer-role` property when the header/body is absent — MVP fallback per architecture security posture; story 2.13 rebuilds this surface with header parsing).
  - [x] Update existing test fixtures that construct `ApproveSpecCommand` to pass `reviewerRole` (and `null` for `reason`). Grep before editing: `grep -r "new ApproveSpecCommand" deliveryline-backend/src/test/`.

- [x] **Task 2: `ApprovalResult` value type** (AC: 1, 8)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalResult.java` as a public record:
    ```
    public record ApprovalResult(
        String approvalId,            // apr_…
        String workflowRunId,         // run_…
        String artifactId,            // art_…
        int artifactVersion,
        int contextBundleVersion,
        String reviewerRole,
        OffsetDateTime decidedAt,
        WorkflowState resultingState, // always Executing for approveSpec
        String correlationId)         // nullable
    ```
  - [x] Do NOT add `ApprovalResult` to the existing `DomainResult` sealed interface (`application.workflow.DomainResult` — currently `permits SubmitWorkflowResult, WorkflowStateChangeResult`). Reason: `executeIdempotent`'s replay loaders are typed to `DomainResult`; widening the sealed type ripples through all replay/serialization machinery for zero gain. Instead, `WorkflowCommandService.approveSpec` keeps returning `WorkflowStateChangeResult` and constructs it from `ApprovalResult` (see Task 4); the rich `ApprovalResult` is what REST/CLI consumers will eventually read when story 2.13 wires the new surface. See Trap T2.

- [x] **Task 3: `ApprovalWritePort` SPI + `ApprovalWritePersistenceAdapter`** (AC: 3, 6, 8)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/application/approval/spi/ApprovalWritePort.java`:
    ```
    public interface ApprovalWritePort {
      /**
       * Insert a single approval row. Caller is responsible for a surrounding transaction; the
       * implementation participates via REQUIRED propagation so the insert commits or rolls back
       * with the calling service's event append + state transition.
       *
       * @throws DomainException(IDEMPOTENCY_KEY_CONFLICT) when the DB-level
       *     uq_approvals_idempotency_key UNIQUE fires (defense-in-depth backstop —
       *     IdempotencyService should have rejected first; if it didn't, map the
       *     DataIntegrityViolationException without leaking the persistence exception).
       */
      ApprovalSnapshot insert(NewApproval newApproval);

      record NewApproval(
          String publicId,             // apr_… caller-generated for log determinism
          String workflowRunPublicId,
          String artifactPublicId,
          int artifactVersion,
          int contextBundleVersion,
          String actorIdentity,
          ActorType actorType,
          String reviewerRole,
          String decision,             // "approved" only in story 2.9 (rejection ships in 2.10)
          String reason,               // nullable
          String rejectionTaxonomy,    // MUST be null when decision="approved" (DB CHECK)
          OffsetDateTime decidedAt,
          String idempotencyKey) {}
    }
    ```
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapter.java` implementing `ApprovalWritePort`:
    - Inject `ApprovalRepository` (already exists — `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/ApprovalRepository.java`) and `WorkflowRunReadEntityRepository` + `ArtifactRepository` to resolve the `WorkflowRunEntity` and `ArtifactEntity` references for the ManyToOne joins.
    - In `insert(...)`: load the FK targets, construct an `ApprovalEntity`, copy fields, call `approvalRepository.save(...)`, catch `DataIntegrityViolationException` and inspect for the `uq_approvals_idempotency_key` constraint name — if matched, throw `DomainException(IDEMPOTENCY_KEY_CONFLICT, …)`; otherwise rethrow (let the existing global handler classify it).
    - Map the saved entity back through `ApprovalEntityMapper.toSnapshot(...)` (already exists) to return `ApprovalSnapshot`.
    - Wrap MDC `APPROVAL_ID` scope around the save call using `MdcKeys.withKey(MdcKeys.APPROVAL_ID, publicId, () -> …)`. **Add the constant** `MdcKeys.APPROVAL_ID = "approvalId"` to `MdcKeys.java` (and to the `ALL_KEYS` list at line 27) — verify the constant doesn't already exist first.
    - `@Component` (per project convention — adapters are not `@Service`).
  - [x] Add a focused test under `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapterTest.java` using the same Testcontainers slice as `ApprovalReadPersistenceAdapterTest` (mirror its setup). Cases: happy-path insert + read-back via `ApprovalReadPort.findLatestApprovedForArtifactLineage`; duplicate idempotency-key insert → `IDEMPOTENCY_KEY_CONFLICT`; FK violation on missing artifact_id rolls back cleanly.

- [x] **Task 4: `ApprovalService` + delegation from `WorkflowCommandService`** (AC: 1, 4, 5, 6, 7, 9)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java`:
    ```
    @Service
    public class ApprovalService {
      // Constructor-inject:
      //   ArtifactRecordPort artifactRecordPort,                  // application.artifact.spi
      //   ArtifactService artifactService,                        // already exists; isApprovalEligible
      //   ApprovalWritePort approvalWritePort,                    // new (Task 3)
      //   WorkflowEventWritePort workflowEventWritePort,          // already exists
      //   WorkflowTransitionService workflowTransitionService,    // already exists
      //   RunnerExecutionReadPort runnerExecutionReadPort         // existing port — see "context-bundle version source" in Dev Notes; verify exact port name before injecting
      //   Clock clock                                              // for decidedAt; default Clock.systemUTC()

      // No @Transactional annotation on the public method — relies on the outer
      // WorkflowCommandService.approveSpec @Transactional. ArchUnit boundary
      // test should confirm this method has no Propagation.REQUIRES_NEW.
      public ApprovalResult approveSpec(ApproveSpecCommand command) {
        // 1. Prefix-validate ids
        PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
        PublicIdPrefixes.require(command.artifactId(), PublicIdPrefixes.ARTIFACT);

        // 2. Open MDC scope
        // 3. Load current artifact + current context-bundle version
        // 4. AC4: version-binding check — throw APPROVAL_VERSION_MISMATCH on diff
        // 5. AC5: ArtifactService.isApprovalEligible — throw ARTIFACT_PAYLOAD_UNAVAILABLE on false
        // 6. AC3: build NewApproval + ApprovalWritePort.insert(...)
        // 7. AC6: WorkflowEventWritePort.append(APPROVAL_APPROVED event)
        // 8. AC6: WorkflowTransitionService.transition(runId, EXECUTING, actor, "approve specification", idempotencyKey, eventDetails)
        // 9. Return ApprovalResult
      }
    }
    ```
  - [x] **Refactor `WorkflowCommandService.approveSpec` (file: `application/workflow/WorkflowCommandService.java:90-91`):** keep the public `@Transactional` method + `executeIdempotent` pipeline. Replace `approveSpecInternal` body so it calls `approvalService.approveSpec(command)` and then constructs `WorkflowStateChangeResult` from the returned `ApprovalResult` (resultingState + correlationId). **Inject `ApprovalService` into the constructor** (extend the constructor; do NOT add a setter). Remove the inline `transition(...)` + `Map.of("artifactId", …, "artifactVersion", …, "contextVersion", …)` block — it now lives inside `ApprovalService`.
  - [x] Verify the surrounding `executeIdempotent → approveSpecInternal → ApprovalService.approveSpec → ApprovalWritePort.insert + WorkflowEventWritePort.append + WorkflowTransitionService.transition` chain runs in **one** transaction (the outer `@Transactional`). Add a focused integration test (`ApprovalServiceTransactionalityTest`) that simulates a runtime failure inside `WorkflowTransitionService.transition` (e.g. mock it to throw) and asserts the `approvals` row is **rolled back** (zero rows after rollback) and no `approval.approved` event was committed.
  - [x] ArchUnit boundary: `ApprovalService` must live under `org.dradgo.application.approval` and must depend only on `application.*` ports + `domain.*` types — NO JPA entity types, NO `adapters.*` imports. Add the assertion to `ArchitectureRuleCatalog` (mirror the existing service-package rules — read the file first).

- [x] **Task 5: Allowed-actions registry pin** (AC: 11)
  - [x] Add a focused architecture test `AllowedActionRegistryPinTest.java` under `deliveryline-backend/src/test/java/org/dradgo/architecture/` (or extend `RegistryContractTest` if one already pins enum values — check first). Assert: `AllowedAction.APPROVE_SPEC.value().equals("approve_spec")`. This is a regression pin — the value must NOT silently rename when story 2.14 ships the full allowed-actions endpoint.
  - [x] Do NOT modify `WorkflowInspectionService` in this story (story 2.14 owns `getAllowedActions`). Add a `// TODO(story-2.14): wire ApprovalService as the canonical executor for AllowedAction.APPROVE_SPEC` comment on the `AllowedAction.APPROVE_SPEC` enum value (or as a Javadoc tag) so the cross-story link is visible.

- [x] **Task 6: Test suite** (AC: 10)
  - [x] **Unit tests** under `deliveryline-backend/src/test/java/org/dradgo/application/approval/`:
    - `ApprovalServiceApproveSpecTest.java` — Mockito-driven. Mock all 6 injected dependencies. Cases per AC10 (a)-(g): happy-path; version-mismatch (assert no port mutations); unavailable-artifact (assert no port mutations); idempotent replay (assert single port-insert call across 2 invocations through the WorkflowCommandService); idempotency-key conflict (different fingerprint); illegal-state-transition (mock `WorkflowTransitionService.transition` to throw `ILLEGAL_TRANSITION` — assert rollback by verifying `ApprovalWritePort.insert` was called but the test asserts the `DomainException` propagates and `WorkflowEventWritePort.append` for `APPROVAL_APPROVED` happens before the failing transition); attribution end-to-end (capture both the `NewApproval` argument and the `WorkflowEventRecord` argument — assert `reviewerRole` matches across them). Verify SLF4J log lines via Logback `ListAppender` per the project logging contract (mirror `ArtifactLoggingContractTest`).
    - `ApprovalResultTest.java` — record component validation: positive-int constraints on versions, null guards on required ids.
  - [x] **Persistence-adapter test** under `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/`:
    - `ApprovalWritePersistenceAdapterTest.java` per Task 3 — Testcontainers Postgres; happy-path insert + read-back through `ApprovalReadPort.findLatestApprovedForArtifactLineage`; duplicate idempotency-key insert raises `IDEMPOTENCY_KEY_CONFLICT`; missing artifact FK rolls back.
  - [x] **Service contract test** under `deliveryline-backend/src/test/java/org/dradgo/application/approval/`:
    - `ApprovalServiceContractTest.java` — Spring slice (`@SpringBootTest` minimal or a focused `@DataJpaTest` extension with the application beans wired). End-to-end through `WorkflowCommandService.approveSpec` → `ApprovalService` → adapters. Seed an `INVESTIGATING → WaitingForSpecApproval` run with one `available` spec artifact (reuse story 2.8's `SpecArtifactFixtures` if it landed; otherwise mirror its direct-write seeding pattern). Cases per AC10 plus: confirm `ApprovalReadPort.findLatestApprovedForArtifactLineage(runId, "spec")` returns the newly inserted row immediately after commit; confirm `getCurrentApprovedSpec` (story 2.8 read path) sees the just-approved spec.
  - [x] **Architecture / registry tests**: extend `ArchitectureBoundaryTest` with the rule from Task 4 (ApprovalService confined to `application.approval`); add the `AllowedAction.APPROVE_SPEC` pin from Task 5.
  - [x] Add a focused Maven invocation to the dev loop:
    ```
    ./mvnw.cmd -pl deliveryline-backend -o -Dtest='ApprovalServiceApproveSpecTest,ApprovalResultTest,ApprovalServiceContractTest,ApprovalWritePersistenceAdapterTest,ArchitectureBoundaryTest,RegistryContractTest,AllowedActionRegistryPinTest' -Dsurefire.failIfNoSpecifiedTests=false test
    ```
    plus `./mvnw.cmd -pl deliveryline-backend verify` once before opening the PR to confirm the JaCoCo floor from story 2.32 (LINE 81.33% / BRANCH 62.74%) stays green.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels:
    - `INFO` on `ApprovalService.approveSpec` entry (`workflowRunId`, `artifactId`, `expectedArtifactVersion`, `expectedContextBundleVersion`, `reviewerRole`, `actorIdentity`, `actorType`); `INFO` on success (`approvalId`, `workflowRunId`, `artifactId`, `artifactVersion`, `contextBundleVersion`, `reviewerRole`, `resultingState=Executing`); `INFO` on transition invocation (`from=WaitingForSpecApproval`, `to=Executing`).
    - `WARN` on each typed-domain rejection branch (`APPROVAL_VERSION_MISMATCH` with `currentArtifactVersion`/`currentContextBundleVersion`; `ARTIFACT_PAYLOAD_UNAVAILABLE` with `reason=not_approval_eligible`; `ILLEGAL_TRANSITION` propagating from the transition service); `WARN` on the `IDEMPOTENCY_KEY_CONFLICT` DB backstop path (note `source=db_unique_constraint`).
    - `ERROR` only for unhandled failures (`DataIntegrityViolationException` not matched to the idempotency constraint — log and rethrow).
    - `DEBUG` for hot-path detail (raw fingerprint comparison; per-field version comparison).
  - [x] Every log carries: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, `artifactId`, and on success `approvalId`. Use MDC via `MdcKeys.beginScope/endScope` mirroring `WorkflowInspectionService.getStatus` (lines 83-141 of that file) — open `WORKFLOW_RUN_ID` + `ARTIFACT_ID` + the new `APPROVAL_ID` MDC keys; close in `finally`.
  - [x] **Forbidden in log output:** `reason` text from the reviewer (free-form — may contain unredacted product information); payload bytes; secrets; raw PII. Log only the `reason.length()` (or omit entirely) — never the text itself. The `approvals.reason` column itself stores the text but it never enters log lines.
  - [x] Add at least one assertion in `ApprovalServiceApproveSpecTest` that the expected log line(s) are emitted at the expected level for each new branch (use a Logback `ListAppender` matching the existing `ArtifactLoggingContractTest` style). Pin the `success` line, `APPROVAL_VERSION_MISMATCH` warn, `ARTIFACT_PAYLOAD_UNAVAILABLE` warn, and `ILLEGAL_TRANSITION` propagation warn.

## Dev Notes

### Foundations already in place (do NOT rebuild)

- **`ApprovalReadPort` + `ApprovalSnapshot` + `ApprovalReadPersistenceAdapter` + `ApprovalRepository` + `ApprovalEntity` + `ApprovalEntityMapper`** — all shipped by story 2.8. Story 2.9 adds the **writer** path (`ApprovalWritePort` + adapter); the existing read path inherits the writer's persisted rows immediately. Verify via the integration test in Task 6.
- **`approvals` table** — V1 migration `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql:167-204`. Columns include all 7 attribution fields (AC3/AC7) + `idempotency_key UNIQUE`. CHECK constraints already enforce: `decision IN ('approved','rejected')`, `actor_type IN ('human','agent','system','service_account')`, `rejection_taxonomy` nullability paired with `decision`. **No new Flyway migration in 2.9.**
- **`WorkflowEventType.APPROVAL_APPROVED`** — already registered (`deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java:8`) with wire value `"approval.approved"`. No registry addition required.
- **`DomainErrorCode.APPROVAL_VERSION_MISMATCH`** — already registered (line 19 of `DomainErrorCode.java`). Same for `IDEMPOTENCY_KEY_CONFLICT` (12), `ARTIFACT_PAYLOAD_UNAVAILABLE` (25), `ILLEGAL_TRANSITION` (11), `RUN_NOT_FOUND` (43), `INVALID_COMMAND_PAYLOAD` (37), `INVALID_ID_PREFIX` (42). **No new error codes in 2.9.**
- **`AllowedAction.APPROVE_SPEC`** — already registered (`deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java:6`) with wire value `"approve_spec"`. No registry addition required.
- **`WorkflowState.WAITING_FOR_SPEC_APPROVAL → EXECUTING`** — already permitted by `WorkflowTransitionTable.defaultTable()` (line 48-54 of `WorkflowTransitionTable.java`). The transition service already validates the source state and throws `ILLEGAL_TRANSITION` on mismatch. **No transition-table change in 2.9.**
- **`WorkflowTransitionService.transition(runId, targetState, actor, reason, idempotencyKey, eventDetails)`** — the only state-mutation path. Inject and call; do NOT bypass to write `workflow.stateChanged` events manually. This service appends the `WORKFLOW_STATE_CHANGED` event on its own — `ApprovalService` is responsible for appending the **separate** `APPROVAL_APPROVED` event in the same transaction.
- **`WorkflowCommandService.executeIdempotent(...)`** (`application/workflow/WorkflowCommandService.java:233-264`) — the canonical idempotency-reserve / replay / complete pipeline. KEEP `WorkflowCommandService.approveSpec` as the entry point so this pipeline applies to `ApprovalService.approveSpec` invocations. `WorkflowCommandFingerprintFactory` (story 1.9) already serializes `ApproveSpecCommand` for the fingerprint; verify it picks up the new `reviewerRole` field automatically (reflection-based) or extend it explicitly.
- **`IdempotencyService.checkAndReserve(...) + .complete(...)`** — `application/idempotency/IdempotencyService.java`. Already wraps the reserve/complete cycle in `REQUIRES_NEW` transactions; `ApprovalService` does NOT call this directly — the surrounding `WorkflowCommandService.approveSpec` does.
- **`ArtifactService.isApprovalEligible(String artifactId)`** — `application/artifact/ArtifactService.java:26`. Already loads the artifact, verifies `status=AVAILABLE`, `archived_at IS NULL`, non-blank `storage_ref`, reads payload bytes, recomputes checksum, and asserts checksum match. Returns `true` only if every gate passes. Reuse — do NOT inline a parallel eligibility check.
- **`ArtifactRecordPort.findByPublicId(String)`** — `application/artifact/spi/ArtifactRecordPort.java`. Returns `Optional<ArtifactRecordSnapshot>`; snapshot carries `version` (the field 2.9 reads for the version-binding comparison) + `artifactType` + `status` + `payloadRef`.
- **`PublicIdPrefixes.APPROVAL.next()`** — `domain/id/PublicIdPrefixes.java:17`. Generates `apr_<uuid-no-dashes>` matching the V1 CHECK constraint `^apr_[A-Za-z0-9_-]{4,64}$`.
- **`MdcKeys.WORKFLOW_RUN_ID / ARTIFACT_ID / CORRELATION_ID`** — already defined (`application/observability/MdcKeys.java`). `APPROVAL_ID` constant is **added by this story** (Task 3) — verify it doesn't already exist before adding.

### Context-bundle version source (AC4)

`ApproveSpecCommand.contextVersion` (= `expectedContextBundleVersion`) must be compared to the artifact's **current context-bundle version**. The current version is the `runner_executions.context_bundle_version` row tied to the artifact's parent `runner_execution_id` (already populated by `ContextBundleService.create` / `ContextBundleService.createForSpecInvestigation`).

- The existing `RunnerExecutionPersistenceAdapter.nextContextBundleVersion(...)` query is the canonical "what version did we just stamp" path (referenced in story 2.8 Dev Notes).
- Verify whether `application/runner/spi/RunnerExecutionReadPort` (or equivalent) already exposes a `findContextBundleVersionByArtifactId(String artifactId)` method. If yes — inject + use. If no — add one in this story (single-method extension to an existing port; no new SPI file).
- Edge case: if the artifact has **no** `runner_execution_id` (early bootstrap state — e.g. an admin-injected spec for testing), treat the current context-bundle version as `1` and compare against `expectedContextBundleVersion=1`. Document this branch in the test suite.

### Traps (anti-pattern prevention)

| ID | Trap | Resolution |
|----|------|------------|
| **T1** | The story AC2 wording uses `expectedArtifactVersion` and `expectedContextBundleVersion` field names, but the existing `ApproveSpecCommand` record carries `artifactVersion` and `contextVersion`. Renaming would ripple into `WorkflowCommandFingerprintFactory` (fingerprint-field ordering), `WorkflowCommandService.approveSpec` callers, `WorkflowController` (REST request mapping), 2-3 tests, and the OpenAPI snapshot. | **Keep the existing field names.** Document in the Javadoc that they ARE the expected versions. Story 2.13 will introduce the rich REST DTO (`ApproveSpecRequest`) with the verbose names; the application-layer command stays with the short names. Trap surfaced because the AC wording is descriptive, not normative on identifier choice. |
| **T2** | Adding `ApprovalResult` to the `DomainResult` sealed interface (`application/workflow/DomainResult.java`) would ripple into `executeIdempotent`'s replay loaders (typed to `DomainResult`), serialization, and 3 tests for zero gain — the existing `WorkflowStateChangeResult` already carries what `WorkflowCommandService.approveSpec` needs to return for the current REST shape. | **Do NOT widen `DomainResult`.** `ApprovalService.approveSpec` returns the new `ApprovalResult`; `WorkflowCommandService.approveSpec` constructs `WorkflowStateChangeResult` from it (preserves the existing public API). Story 2.13 will eventually surface `ApprovalResult` directly through a new REST DTO. |
| **T3** | The order of validation matters: if `isApprovalEligible` runs first and the artifact has been superseded by an unavailable next version, the reviewer gets `ARTIFACT_PAYLOAD_UNAVAILABLE` even though their actual mistake is reviewing a stale version. | **Run AC4 (version-binding) BEFORE AC5 (eligibility).** The reviewer learns "your version is stale" first — they pull the latest and try again. Documented in AC5 explicitly. |
| **T4** | `ApprovalService` adds `@Transactional` thinking it needs its own boundary. This creates a nested transaction (REQUIRES_NEW by default depending on config) that won't roll back the surrounding `WorkflowCommandService.approveSpec` transaction on failure. | **`ApprovalService.approveSpec` has NO `@Transactional` annotation.** It relies on the outer `WorkflowCommandService.approveSpec` `@Transactional`. The transactionality integration test in Task 4 proves rollback shape. ArchUnit boundary asserts no `@Transactional(propagation = REQUIRES_NEW)` on the service. |
| **T5** | The `approvals.idempotency_key` UNIQUE constraint will fire if `IdempotencyService` somehow allows two different requests with the same key (extremely rare — would require an `IdempotencyService` bug). Letting the raw `DataIntegrityViolationException` propagate would surface as an HTTP 500 instead of the proper `IDEMPOTENCY_KEY_CONFLICT` Problem Details response. | `ApprovalWritePersistenceAdapter.insert` catches `DataIntegrityViolationException`, inspects the constraint name (`uq_approvals_idempotency_key`), and maps to `DomainException(IDEMPOTENCY_KEY_CONFLICT, …)`. Defense-in-depth — log at WARN with `source=db_unique_constraint` so any occurrence is visible as a regression signal. |
| **T6** | The `approval.approved` event MUST be appended INSIDE the same transaction as the `approvals` row insert AND the `workflow.stateChanged` event (which `WorkflowTransitionService.transition` appends). Splitting them creates an audit-history gap if any of the three commits independently. | Single outer `@Transactional` boundary in `WorkflowCommandService.approveSpec`. `ApprovalService.approveSpec` invokes `ApprovalWritePort.insert(...)` → `WorkflowEventWritePort.append(APPROVAL_APPROVED, …)` → `WorkflowTransitionService.transition(...)` in that order, all participating in the outer transaction (REQUIRED propagation). Test in Task 4 simulates a failure in the transition service and asserts the approval row is rolled back. |
| **T7** | The existing `WorkflowController.approveSpec` REST surface is the only current production caller. Refactoring `WorkflowCommandService.approveSpec` to delegate could subtly change response shape (e.g. dropping `correlationId` echoing). | Story 2.13 will rebuild `WorkflowController` with rich Problem Details + new request DTOs. For 2.9: preserve the existing `WorkflowStateChangeResult` return shape verbatim; the only behavioral change is that the approval now writes a row + appends a second event. Add a contract test that pins the `WorkflowController.approveSpec` HTTP response shape against the fixture (no field added or removed). |
| **T8** | Test fixtures will need a "WaitingForSpecApproval run with an available approved-eligible spec artifact" setup. Hand-rolled per-test fixtures will drift. | Reuse story 2.8's `SpecArtifactFixtures` builder (if it landed; otherwise extend its package with an `ApprovalScenarioFixtures` helper) for: seeding a run, a `recorded → available` spec artifact, and a `runner_executions` row with a `context_bundle_version`. Tests reference the builder, not raw repository calls. |
| **T9** | The `reason` field is free-form reviewer text. Including it in `WorkflowCommandFingerprintFactory`'s fingerprint would mean a reviewer who edits their reason mid-flight gets `IDEMPOTENCY_KEY_CONFLICT` on retry instead of an idempotent replay. | **`reason` MUST be excluded from the fingerprint.** `reviewerRole` IS in the fingerprint (changing the asserted role is a meaningful semantic shift). Documented in Task 1's Javadoc. Verify by inspecting `WorkflowCommandFingerprintFactory`'s serialization — if it's reflection-based and includes ALL fields, add an explicit exclusion or `@JsonIgnore` analogue per the factory's conventions. |

### Open Questions (resolve before merging)

- **OQ-1: Reviewer-role default for the existing `WorkflowController.approveSpec` REST surface.** Story 2.13 will introduce the proper `X-Actor-Identity` header and `reviewerRole` in the request body. Until then, the existing endpoint has no reviewerRole concept. **Recommendation:** add a `deliveryline.approval.default-reviewer-role` config property (default `"product_reviewer"`) that the existing controller passes when constructing `ApproveSpecCommand`. Document the MVP-fallback nature in the config property's Javadoc + `application.yml` comment. Surface this in PR description; if rejected, fail-fast at controller construction with a startup error message pointing to the property.
- **OQ-2: Context-bundle version source for artifacts with no `runner_execution_id`.** Some early-bootstrap test fixtures or admin-injected specs may not have a `runner_executions` row. **Recommendation:** treat the current context-bundle version as `1` and compare against `expectedContextBundleVersion=1` (matches the V1 default and the bootstrap path documented in story 2.8). Document the branch in tests; if a future story tightens the invariant, the comparison logic localizes to one method. Surface in PR description.
- **OQ-3: Fingerprint factory extension policy for new command fields.** Whether `WorkflowCommandFingerprintFactory` is reflection-based (auto-picks up new record components) or explicit (per-command-type serializer). **Action:** read the factory before Task 1; if reflection-based, no factory change needed and `reviewerRole` enters automatically — but you must add `reason` to an explicit exclusion list (or rename it with a documented convention). If explicit, extend the factory and add a unit test pinning the new fingerprint shape.

### Project Structure Notes

- **`ApprovalService`** → `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java`. Sibling of the existing `ApprovalSnapshot` and `spi/ApprovalReadPort`.
- **`ApprovalResult`** → `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalResult.java`. Same package.
- **`ApprovalWritePort`** → `deliveryline-backend/src/main/java/org/dradgo/application/approval/spi/ApprovalWritePort.java`. Sibling of `ApprovalReadPort`.
- **`ApprovalWritePersistenceAdapter`** → `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapter.java`. Sibling of `ApprovalReadPersistenceAdapter`. Reuses existing `ApprovalRepository` + `ApprovalEntity` + `ApprovalEntityMapper` (all shipped by 2.8).
- **`ApproveSpecCommand` extension** → `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/ApproveSpecCommand.java`. Add `reviewerRole` + `reason` to the existing record.
- **`MdcKeys.APPROVAL_ID` constant** → `deliveryline-backend/src/main/java/org/dradgo/application/observability/MdcKeys.java`. Append after `ARTIFACT_ID` and add to `ALL_KEYS` list.
- **No new Flyway migration.** No new domain registry values. No new domain error codes.
- **Story 2.32 JaCoCo gate** (LINE 81.33% / BRANCH 62.74%) applies — new code (`ApprovalService`, `ApprovalWritePersistenceAdapter`, `ApprovalResult`, `MdcKeys.APPROVAL_ID`) must carry sufficient unit + adapter coverage to keep the gate green. Run `./mvnw.cmd -pl deliveryline-backend verify` locally (WSL2 Ubuntu native per the project memory note) before pushing.

### Architecture compliance

- **Component boundaries** (architecture.md:1145-1175 / 1235-1240): `application/approval` is the canonical location for the approval service (architecture line 1237). `ApprovalService` depends only on `application/*` ports and `domain/*` types; the JPA `ApprovalEntity` stays in `adapters/persistence/entity` and never leaks across the boundary. ArchUnit boundary test in Task 4 enforces.
- **Service boundaries** (architecture.md:1161-1168): `WorkflowTransitionService` remains the only state-transition path — `ApprovalService` calls it, never bypasses. `ArtifactOperationService` remains the only approval-eligible-write path — `ApprovalService` does NOT mutate artifacts (only reads via `ArtifactRecordPort` + `ArtifactService.isApprovalEligible`).
- **Approval checkpoints** (architecture.md:81): "Each approval must bind to a specific artifact version, context bundle version, workflow state, actor identity, reviewer role, decision, reason." This story's AC2/AC3/AC4 implement that contract end-to-end.
- **Idempotency** (architecture.md:185, 317): "Command execution, workflow advancement, runner submission, approval decisions, artifact creation, and recovery replay should use idempotency keys." `IdempotencyService` (story 1.9) provides the application-level path; the DB-level `uq_approvals_idempotency_key` UNIQUE is the defense-in-depth backstop.
- **State+event atomicity** (architecture.md:301-302): "Any workflow state transition must update the operational run state and append the corresponding audit event in the same PostgreSQL transaction." The approval row insert + `APPROVAL_APPROVED` event append + `WORKFLOW_STATE_CHANGED` event append + `workflow_runs.current_state` update all commit together via the outer `@Transactional` boundary in `WorkflowCommandService.approveSpec`. The Task 4 transactionality test pins this.
- **Data classification** (architecture.md:85): the `reason` text and `reviewerRole` are NOT classification-restricted (they are reviewer-supplied attribution metadata, not runner output), but reviewer-text `reason` MUST NOT enter log lines per the Logging Requirements section.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface for THIS story):**
  - `ApprovalService.approveSpec` → `INFO` on entry (all envelope fields), `INFO` on success (`approvalId` + all attribution), `WARN` on each typed-domain rejection branch (`APPROVAL_VERSION_MISMATCH`, `ARTIFACT_PAYLOAD_UNAVAILABLE`, `ILLEGAL_TRANSITION` propagation).
  - `ApprovalWritePersistenceAdapter.insert` → `INFO` on entry (`approvalId`, `workflowRunId`, `artifactId`, `decision='approved'`), `INFO` on success (`approvalId`, `artifactVersion`, `contextBundleVersion`), `WARN` on the DB-level `uq_approvals_idempotency_key` backstop (`source=db_unique_constraint`), `ERROR` on unmatched `DataIntegrityViolationException`.
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, `artifactId`, plus `approvalId` once generated.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields, and the **reviewer-supplied `reason` text** (free-form — may contain unredacted product information). Log `reason.length()` if you must signal its presence, never the text.
- **Test contract:** new logging surfaces are pinned by `ApprovalServiceApproveSpecTest` using a Logback `ListAppender` matching the existing `ArtifactLoggingContractTest` style. Required pins: success line, all three WARN rejection branches, and the DB-backstop WARN.

### Previous-story intelligence

- **Story 2.8 (spec artifact + context bundle, in-progress):** shipped `ApprovalReadPort` + `ApprovalSnapshot` + the full read-side persistence adapter that this story's writer feeds. The `application.approval` package, `apr_` prefix, `archived_at IS NULL` filtering convention, and `ApprovalEntity`/Mapper/Repository scaffolding are ALL in place. 2.9 only adds the writer (`ApprovalWritePort` + adapter) + the orchestration service (`ApprovalService`). Verify 2.8 is fully merged (status `done`) before starting 2.9; if 2.8 is still `in-progress`, coordinate so the writer integration test in Task 6 picks up 2.8's read path on the same commit.
- **Story 2.8 open questions (OQ-1/OQ-2/OQ-3) — relevance to 2.9:** OQ-2 (persisted bundle storage shape) does NOT block 2.9 (we read `runner_executions.context_bundle_version` — an integer column — not the bundle bytes). OQ-1 + OQ-3 are bundle/schema concerns unrelated to the approval writer.
- **Story 2.7 (tri-pane shell, done):** confirmed the planning-doc convention that AC file paths in the epic text are authoritative when they conflict with `architecture.md`. For 2.9 the epic AC text and `architecture.md` line 1237 agree on `application/approval` package location — no conflict.
- **Story 2.32 (JaCoCo gate, done):** LINE 81.33% / BRANCH 62.74% floor on `deliveryline-backend`. New code must keep the gate green. `./mvnw.cmd -pl deliveryline-backend verify` is the canonical check; reproduce on WSL2 Ubuntu before pushing (per project memory).
- **Story 1.12c (artifact lineage hardening, done):** established `seedDeepLineage` test fixture for artifact lineage stress. Less directly relevant to 2.9 (approval writes don't walk lineage), but the package convention (`…/test/java/org/dradgo/application/testfixtures/`) is reused.
- **Story 1.9 (idempotency service, done):** the `IdempotencyService.checkAndReserve` + `complete` pipeline is wired into `WorkflowCommandService.executeIdempotent` and handles the entire lifecycle. `ApprovalService` does NOT invoke `IdempotencyService` directly — the surrounding `WorkflowCommandService.approveSpec` does.
- **Story 1.5 (workflow transition table, done):** `WAITING_FOR_SPEC_APPROVAL → EXECUTING` already permitted. `WorkflowTransitionService.transition(...)` is the only state-mutation path.
- **Story 1.7 (shared command-model pattern, done):** `WorkflowCommand` sealed interface + `executeIdempotent` pipeline. `ApproveSpecCommand` is already a `WorkflowCommand`; Task 1 extends its fields without breaking the sealed-type guarantee.

### Git intelligence

Recent commit shape: each story lands as one or two clean commits prefixed `Story N.M: <title>`. Mimic that. The Co-Author trailer is **not** added in this repo (per project memory). Commit author = Alex.

Recent commits to study before starting:
- `fdcd6d2 Story 2.7: apply code-review patches; status -> done` — shows the post-review patch shape.
- `4d64e4d Story 2.32: backend coverage reporting + Maven JaCoCo threshold gate` — shows the JaCoCo floor introduction; reference for the verify command.
- `699cc1e Story 2.30: backend lint + format - Spotless, Checkstyle & SpotBugs` — Spotless will autoformat on `verify`; run it locally before pushing.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.9](_bmad-output/planning-artifacts/epics.md#L1071-L1089)
- [Source: _bmad-output/planning-artifacts/architecture.md#Specification approval (FR7-FR13)](_bmad-output/planning-artifacts/architecture.md#L1235-L1240)
- [Source: _bmad-output/planning-artifacts/architecture.md#Approval checkpoints contract](_bmad-output/planning-artifacts/architecture.md#L81-L81)
- [Source: _bmad-output/planning-artifacts/architecture.md#Idempotency requirements](_bmad-output/planning-artifacts/architecture.md#L183-L185)
- [Source: _bmad-output/planning-artifacts/architecture.md#State-event atomicity](_bmad-output/planning-artifacts/architecture.md#L300-L302)
- [Source: _bmad-output/planning-artifacts/architecture.md#Project Structure tree](_bmad-output/planning-artifacts/architecture.md#L976-L984) — application/approval and application/idempotency packages
- [Source: _bmad-output/planning-artifacts/prd.md#FR12-FR13 + FR46](_bmad-output/planning-artifacts/prd.md) — approval contract + attribution
- [Source: deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql#L167-L204](deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql) — `approvals` table (no new migration)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/approval/spi/ApprovalReadPort.java](deliveryline-backend/src/main/java/org/dradgo/application/approval/spi/ApprovalReadPort.java) — sibling read port (story 2.8)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalSnapshot.java](deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalSnapshot.java) — shared projection record (story 2.8)
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/ApprovalEntity.java](deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/ApprovalEntity.java) — JPA entity (story 2.8)
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/ApprovalRepository.java](deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/ApprovalRepository.java) — repository (story 2.8)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java#L90-L92](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java) — `approveSpec` entry point to refactor
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java#L155-L174](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java) — `approveSpecInternal` body (replace with delegation in Task 4)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java#L233-L264](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java) — `executeIdempotent` pipeline (do NOT modify)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java#L52-L116](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java) — single state-mutation path
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java#L48-L54](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java) — `WAITING_FOR_SPEC_APPROVAL → EXECUTING` permitted
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/idempotency/IdempotencyService.java](deliveryline-backend/src/main/java/org/dradgo/application/idempotency/IdempotencyService.java) — invoked by `WorkflowCommandService`, not directly by `ApprovalService`
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactService.java#L26-L105](deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactService.java) — `isApprovalEligible` already shipped (story 1.12 AC6)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/ApproveSpecCommand.java](deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/ApproveSpecCommand.java) — record to extend in Task 1
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowEventWritePort.java](deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowEventWritePort.java) — event append SPI
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowEventRecord.java](deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowEventRecord.java) — event-record shape
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java#L8](deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java) — `APPROVAL_APPROVED` already registered
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java#L11-L19](deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java) — all required error codes already registered
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java#L6](deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java) — `APPROVE_SPEC` already registered
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java#L9-L10](deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java) — both states already registered
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java#L17](deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java) — `APPROVAL` prefix already registered
- [Source: _bmad-output/implementation-artifacts/2-8-backend-specification-artifact-model-and-spec-stage-context-bundle.md](_bmad-output/implementation-artifacts/2-8-backend-specification-artifact-model-and-spec-stage-context-bundle.md) — sibling read-side story that supplies `ApprovalReadPort` + `ApprovalSnapshot`
- [Source: _bmad-output/implementation-artifacts/1-9-idempotency-service.md](_bmad-output/implementation-artifacts/1-9-idempotency-service.md) — foundational idempotency service; integration via `WorkflowCommandService.executeIdempotent`
- [Source: _bmad-output/implementation-artifacts/1-12-artifact-operations-skeleton.md](_bmad-output/implementation-artifacts/1-12-artifact-operations-skeleton.md) — `ArtifactService.isApprovalEligible` origin (AC6)
- [Source: _bmad-output/implementation-artifacts/1-7-shared-application-command-model-pattern.md](_bmad-output/implementation-artifacts/1-7-shared-application-command-model-pattern.md) — `WorkflowCommand` sealed interface + `executeIdempotent` pattern

### Review Findings

#### Review batch 1 (2026-05-24)

Three adversarial layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor) reviewed the 2.9-scoped diff (27 files, 2001 lines, cleanly committed-after-2.8 baseline). Auditor verdict: **ACs SATISFIED WITH CAVEATS** (no CRITICAL findings; AC10 test coverage gaps + missing artifact-type/cross-run guards in the writer). Triage: 3 decision-needed, 10 patches, 5 defers, ~30 dismissed as noise.

- [x] [Review][Patch] Replay returns the live `workflowRun.currentState()` instead of the original approval-time state [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:410-416]
  `replayStateChange` reads `workflowRun.currentState()` at replay time. If the run has advanced past `EXECUTING` (e.g. to `COMPLETED` by the time a retry lands), the idempotent replay returns the new state — not the original `EXECUTING` result the first call returned. AC8 says "retries with the same `idempotencyKey` + identical fingerprint replay the prior `ApprovalResult`" — "prior" suggests the historical state, not the current. Three options: (a) accept current live-state behavior (low-risk because production retries normally fire within seconds, before state can advance); (b) hard-code `EXECUTING` in the replay path since `approveSpec` always transitions to `EXECUTING`; (c) load the approval row by `idempotencyKey` and return its `decidedAt`-time state. Recommendation: (b) — `approveSpec`'s post-state is invariant, so the replay should pin it to `EXECUTING`. Option (c) is the rigorous fix but requires a read query.

- [x] [Review][Patch] `ApprovalReviewerRoleResolver` silently applies `product_reviewer` when neither request nor config supplies a role [deliveryline-backend/src/main/java/org/dradgo/application/workflow/ApprovalReviewerRoleResolver.java:31-42]
  Resolver: request role → configured default (`@Value("${deliveryline.approval.default-reviewer-role:product_reviewer}")`) → `ULTIMATE_FALLBACK = "product_reviewer"`. The request-blank + config-default-applied path is silent: no log, no metric. AC6/FR46 requires per-decision reviewer-role attribution, and the resolver silently stamps `product_reviewer` on the approval row + the `approval.approved` event details. Three options: (a) accept current silent behavior — it's the documented MVP fallback until story 2.13 rebuilds the surface with proper header parsing; (b) `log.warn` once-per-process when the configured-default path is taken (operator gets a signal that the env isn't configured); (c) `log.warn` every time (noisy). Recommendation: (b) — emit a one-shot `WARN` at construction when no explicit config property is set, AND a `DEBUG` per resolve when request was blank (lower volume; investigation-time only).

- [x] [Review][Patch] No defense-in-depth pin on `@Transactional` absence on `ApprovalService.approveSpec` [deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java:106]
  Trap T4 is enforced only by code review + the class-level Javadoc. The existing `APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL` ArchUnit rule checks layer dependencies but does NOT pin the absence of `@Transactional`. A future contributor adding `@Transactional(propagation = REQUIRES_NEW)` to `approveSpec` would silently break the AC9 rollback contract and pass all current tests. Three options: (a) add `@Transactional(propagation = Propagation.MANDATORY)` to fail-fast when invoked outside an outer transaction (defensive + self-documenting; existing tests still work because they go through `WorkflowCommandService.approveSpec`); (b) add an ArchUnit rule that scans `ApprovalService.approveSpec` for the `@Transactional` annotation and fails the build if present; (c) accept current state, rely on the documentation. Recommendation: (a) — `MANDATORY` honors trap T4 explicitly and makes direct-callers fail with a clear `IllegalTransactionStateException` instead of silently committing partial state.

- [x] [Review][Patch] `ApprovalService.approveSpec` does not verify `artifact.artifactType() == SPEC` [deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java:123-135]
  The artifact is loaded by `command.artifactId()` but its `artifactType` is never asserted to be `SPEC`. A caller supplying the publicId of a non-SPEC artifact (e.g. `implementationPlan`, `prOutput`) passes the version-binding check, the eligibility check (which is artifact-type-agnostic), and the row insert. The `approval.approved` event is appended and the run transitions `WAITING_FOR_SPEC_APPROVAL → EXECUTING` off a non-spec artifact. The downstream `WorkflowInspectionService.getCurrentApprovedSpec` (story 2.8) filters by `artifact_type='spec'` so the row is silently invisible there — but the run has still advanced. Fix: at the top of `approveSpec` (before version-binding), assert `artifact.artifactType() == ArtifactType.SPEC`; on mismatch throw `DomainException(INVALID_COMMAND_PAYLOAD, "approveSpec called against non-spec artifact", details=artifactId/actualArtifactType/expectedArtifactType=spec)`.

- [x] [Review][Patch] `ApprovalService.approveSpec` does not verify `artifact.workflowRunId() == command.workflowRunId()` [deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java:123-135]
  Composite FK `(artifact_id, artifact_version) → artifacts(id, version)` enforces artifact existence but NOT that the artifact belongs to the run identified by `command.workflowRunId()`. A caller (or REST client bug) supplying an artifact public id from a DIFFERENT workflow run passes all checks and approves against the wrong run. The transition is dispatched to `command.workflowRunId()`, not the artifact's owning run, so the foreign run silently advances to `EXECUTING`. Fix: after loading the artifact, assert `artifact.workflowRunId().equals(command.workflowRunId())`; on mismatch throw `DomainException(INVALID_COMMAND_PAYLOAD, "Artifact does not belong to the supplied workflow run", details=artifactId/artifactWorkflowRunId/commandWorkflowRunId)`.

- [x] [Review][Patch] AC10(e) — no test exercises "same `idempotencyKey` + different fingerprint → `IDEMPOTENCY_KEY_CONFLICT`" for `approveSpec` end-to-end [deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceContractTest.java]
  Spec AC10(e) explicitly enumerates this case. The dev-record at line 314 defers it to "the surrounding `WorkflowCommandService` layer," but `grep IDEMPOTENCY_KEY_CONFLICT WorkflowCommandServiceContractTest.java` returns nothing for `approveSpec`. The persistence-adapter test `ApprovalWritePersistenceAdapterTest.duplicateIdempotencyKeyMapsToTypedConflict` exercises the DB backstop (trap T5), not the fingerprint-mismatch path through `IdempotencyService`. Fix: add a contract test that approves the same workflow with the same `idempotencyKey` but different `reviewerRole` (the fingerprint includes reviewerRole) and asserts `IDEMPOTENCY_KEY_CONFLICT` is raised by the application-layer pipeline before the DB backstop fires.

- [x] [Review][Patch] `ApprovalServiceContractTest` named in Task 6 (Spring slice through write→read) is missing [deliveryline-backend/src/test/java/org/dradgo/application/approval/]
  Spec lines 145-146 require a full Spring-context contract test that drives `WorkflowCommandService.approveSpec → ApprovalService → adapters → ApprovalReadPort` and confirms `findLatestApprovedForArtifactLineage(runId, "spec")` returns the just-written row plus `getCurrentApprovedSpec` sees it (AC10(h)). Only `ApprovalServiceApproveSpecTest` (Mockito unit) + `ApprovalWritePersistenceAdapterTest` (Testcontainers persistence slice) ship — the integration through the application-layer service is implicitly covered by `WorkflowCommandServiceContractTest.approveSpecTransitionsWaitingForSpecApprovalToExecutingAndCarriesMetadata` (asserts row count, not read-port read-back). Fix: add `deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceContractTest.java` (`@SpringBootTest` + `TestcontainersConfiguration`) that calls `workflowCommandService.approveSpec(...)` and then `approvalReadPort.findLatestApprovedForArtifactLineage(runId, "spec")` + `workflowInspectionService.getCurrentApprovedSpec(runId)` and asserts both surface the just-written row.

- [x] [Review][Patch] Inline comment "2 events" but test asserts 3 events [deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceContractTest.java approveSpecReplayDoesNotAppendDuplicateEvents]
  Cosmetic but misleading: the inline comment says "2 events: the original artifact.draftCreated seed + the approval.approved + the workflow.stateChanged" — three components, "2 events" — and the actual assertion is `assertEquals(3, …)`. A future maintainer reading the comment would think the test is wrong. Fix: change "2 events" → "3 events" in the inline comment (the assertion is correct).

- [x] [Review][Patch] Happy-path unit test does not pin `correlationId` in the appended `approval.approved` event details [deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceApproveSpecTest.java happyPathInsertsApprovalAppendsEventAndTransitionsToExecuting]
  AC6 requires `details = { …, idempotencyKey, correlationId? }`. The production code DOES include `correlationId` (conditionally — only when non-null) but the happy-path unit test supplies `correlationId="corr-1"` without ever asserting that key appears in the captured event details map. A future change that drops the `if (correlationId != null) details.put("correlationId", ...)` branch would slip through unnoticed. Fix: add `assertEquals("corr-1", capturedEventDetails.get("correlationId"))` to `happyPathInsertsApprovalAppendsEventAndTransitionsToExecuting`.

- [x] [Review][Patch] `matchesIdempotencyConstraint` uses fragile substring match on raw exception message [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapter.java:144-154]
  Constraint name detection walks the cause chain checking `cursor.getMessage().contains("uq_approvals_idempotency_key")`. Postgres / Hibernate sometimes localize error messages (`error_text_locale` driver setting), sometimes truncate, sometimes quote the constraint name. A future Postgres / Hibernate version that changes wording silently flips every duplicate-key race into a generic HTTP 500. Fix: walk to a `org.hibernate.exception.ConstraintViolationException` and call `getConstraintName()`; or check the `SQLException.getSQLState() == "23505"` (Postgres unique-violation SQLSTATE) as a fallback. Keep the substring match as the first-pass shortcut.

- [x] [Review][Patch] `IDEMPOTENCY_KEY_CONFLICT` error details echo the caller-supplied `idempotencyKey` verbatim [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapter.java:113-119]
  `details.put("idempotencyKey", newApproval.idempotencyKey())` puts the caller's key into the Problem Details response body. Idempotency keys are caller-private session tokens — a 409 response with the key reflected back makes it discoverable in any error-aggregation pipeline (Sentry, Splunk, etc.). Fix: replace with `details.put("conflictDetected", true)` and `details.put("source", "db_unique_constraint")`; rely on server-side logs (which already pin the key under structured MDC) for forensic correlation.

- [x] [Review][Patch] `uq_approvals_public_id` constraint collision is not branched separately from `uq_approvals_idempotency_key` [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapter.java:107-129]
  The catch maps the idempotency-key constraint via substring; any OTHER `DataIntegrityViolationException` (e.g. `uq_approvals_public_id` collision from a test fixture reusing an id, or the composite FK `fk_approvals_artifact_version` failing if the artifact version moves under us) is re-thrown raw, becoming HTTP 500. Astronomically unlikely for `uq_approvals_public_id` (UUIDv7) but possible. Fix: branch the catch — substring-match against each known constraint and map to `INTERNAL_ERROR` with `details.constraintName=…` instead of leaking the DAE.

- [x] [Review][Patch] `ApprovalWritePersistenceAdapterTest.duplicateIdempotencyKeyMapsToTypedConflict` does not pin the WARN log line [deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapterTest.java]
  Spec line 239 requires "`WARN` on the DB-level `uq_approvals_idempotency_key` backstop (`source=db_unique_constraint`)." The production code emits this WARN (adapter line 109-112) but the test only asserts the exception shape, not the log line. A future change that drops the WARN would slip past the test. Fix: attach a Logback `ListAppender` to `ApprovalWritePersistenceAdapter`'s logger in the test and assert the WARN line is emitted with `source=db_unique_constraint`.

- [x] [Review][Defer] `AllowedActionRegistryPinTest` only pins the wire-value string, no executor-wiring assertion [deliveryline-backend/src/test/java/org/dradgo/architecture/AllowedActionRegistryPinTest.java] — deferred, scope-bounded — Spec AC11 explicitly says "the full state×role→action-set logic in `WorkflowInspectionService.getAllowedActions` lands in story 2.14; story 2.9 does NOT extend that service." The executor-wiring assertion belongs in 2.14. Track for 2.14 entry.
- [x] [Review][Defer] `ApprovalServiceTransactionalityTest` named-test is missing but functional intent is satisfied by `WorkflowCommandServiceContractTest.approveSpecRejectsIllegalTransitionWhenRunIsNotWaitingForSpecApproval` — deferred, naming-only — The functional contract (transition exception rolls back row + event in the outer transaction) IS pinned by an integration test that seeds an `INBOX` run and asserts `select count(*) from approvals = 0` plus `count(approval.approved events) = 0`. Spec line 132 named the test but didn't require the specific file path. Track as documentation alignment.
- [x] [Review][Defer] T8 — `seedAvailableSpecArtifact` is an inline private helper, not a shared fixture [deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceContractTest.java:467-498] — deferred, pattern-emergence — Spec line 203 suggested reusing/extending a shared `SpecArtifactFixtures` builder. As shipped, the seed pattern is duplicated between `WorkflowCommandServiceContractTest` and `ApprovalWritePersistenceAdapterTest`. Story 2.10 (rejection writer) will need a similar seed pattern; refactor into a shared `application.approval.test.SpecArtifactFixtures` then.
- [x] [Review][Defer] `idempotencyKey` is persisted into queryable `workflow_events.details` JSON [deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java:309-322 + 325-340] — deferred, per-spec contract — AC6 explicitly lists `idempotencyKey` in the event details map. Same pattern exists in the existing `WorkflowTransitionService.transition` event details. Cross-cutting policy change (omit/hash keys in event details surface-wide) belongs in a future operator-secret-handling epic; not a 2.9 regression.
- [x] [Review][Defer] `config/checkstyle/suppressions.xml` line-number magic number drift (438→434) [config/checkstyle/suppressions.xml] — deferred, pre-existing pattern — The suppression targets `Thread.sleep` in `WorkflowCommandService.pauseBeforeReplayLookup`; the line number shifts every time the file changes above. Magic-number drift was flagged in story 1.21's review and accepted as the project's existing checkstyle-suppression style. Track for a `@SuppressWarnings`-based replacement.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) via bmad-dev-story (2026-05-23).

### Debug Log References

- Backend unit suite (`ApprovalServiceApproveSpecTest`, `ApprovalResultTest`, `AllowedActionRegistryPinTest`, `WorkflowCommandTypeTest`, `LoggingFieldNameContractTest`): 5 + 6 + 1 + 1 + 2 = 15 tests, 0 failures.
- Backend contract suite (`WorkflowCommandServiceContractTest`, `ProblemDetailsContractTest`, `WorkflowAdapterEquivalenceTest`, `ApprovalReadPersistenceAdapterTest`, `ApprovalWritePersistenceAdapterTest`): 15 + 11 + 7 + 5 + 3 = 41 tests, 0 failures.
- Full backend `./mvnw.cmd -pl deliveryline-backend -o verify`: BUILD SUCCESS for Spotless / Checkstyle / SpotBugs / JaCoCo merge / report / check / Failsafe. JaCoCo gate (LINE 81.33% / BRANCH 62.74% from story 2.32) held.
- Two PRE-EXISTING story 2.8 WIP test failures observed and confirmed unrelated: `ContextBundleServiceSpecInvestigationTest.bootstrapBundleHasNullApprovedSpecEmptyPriorFeedbackAndEmptyArtifactReferences` and `ContextBundleServiceUnitTest.emptyArtifactReferencesArrayIsAcceptedAfterSchemaRelaxation` — both reference the in-flight story 2.8 schema relaxation (artifactReferences minItems 0). Story 2.8 is in `review`, not `done`. Both files (`ContextBundleService.java`, `context-bundle.v1.schema.json`, `ContextBundleServiceUnitTest.java`) were already marked `M` in `git status` at the start of this session. Story 2.9 touches NEITHER the schema NOR `ContextBundleService`. Re-verify post-2.8-done.

### Completion Notes List

- All 11 AC + 6 Task groups + 1 cross-cutting Logging task complete; story status `ready-for-dev → review`.
- Trap discipline: all 9 traps honored. **T1**: `ApproveSpecCommand` keeps the existing `artifactVersion` / `contextVersion` field names; Javadoc clarifies they ARE the expected versions. **T2**: `DomainResult` sealed interface untouched — `ApprovalResult` is a sibling type; `WorkflowCommandService.approveSpec` builds `WorkflowStateChangeResult` from it. **T3**: version-binding (AC4) runs BEFORE eligibility (AC5) — pinned by `ApprovalServiceApproveSpecTest.versionMismatchRejectsBeforeAnyWrite` + `versionMismatchRejectsBeforeEligibilityCheckEvenWhenContextBundleDiffers`. **T4**: `ApprovalService.approveSpec` carries NO `@Transactional`; the new ArchUnit rule `APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL` confines its imports to application/domain. **T5**: `ApprovalWritePersistenceAdapter.insert` catches `DataIntegrityViolationException`, inspects the constraint name (`uq_approvals_idempotency_key`), and maps to `DomainException(IDEMPOTENCY_KEY_CONFLICT, …)` with `source=db_unique_constraint` WARN log. **T6**: `approval.approved` event + `approvals` insert + `workflow.stateChanged` + state update all commit in one outer transaction; rollback pinned by `WorkflowCommandServiceContractTest.approveSpecRejectsIllegalTransitionWhenRunIsNotWaitingForSpecApproval`. **T7**: existing `WorkflowController.approveSpec` REST shape preserved verbatim (request DTO gained optional `reviewerRole`/`reason`; the contract tests in `CommandModelSymmetryFoundationContract` + `ProblemDetailsContractTest` both pass without surface changes). **T8**: reused the `seedAvailableSpecArtifact` JDBC-direct pattern (mirroring story 2.8's `ApprovalReadPersistenceAdapterTest` fixture). **T9**: `WorkflowCommandFingerprintFactory.fingerprintFor` explicitly appends `reviewerRole` to the ApproveSpec branch; `reason` is intentionally excluded (free-form text edits replay idempotently).
- Open Questions surfaced for PR review:
  - **OQ-1 (default reviewer role)** — implemented as `ApprovalReviewerRoleResolver` + new config property `deliveryline.approval.default-reviewer-role` (default `product_reviewer`). Pinned by `CommandModelSymmetryFoundationContract.everyWorkflowCommandPermitRoundTripsThroughRestAsTheCanonicalRecord` (omits `reviewerRole` from the REST body; captured command carries `product_reviewer`).
  - **OQ-2 (context-bundle bootstrap)** — implemented per recommendation: `ApprovalService.resolveCurrentContextBundleVersion` returns `1` when the artifact has no `runner_execution_id` OR the runner_execution row is absent. Pinned by `ApprovalServiceApproveSpecTest.bootstrapBundleVersionIsOneWhenArtifactHasNoRunnerExecution`.
  - **OQ-3 (fingerprint factory extension policy)** — confirmed explicit switch (NOT reflection): `WorkflowCommandFingerprintFactory.fingerprintFor` is a `switch` over `WorkflowCommand` variants. Extended the `ApproveSpecCommand` branch to append `reviewerRole`; `reason` deliberately omitted.
- `MdcKeys` surface widened from 5 → 6 keys (added `APPROVAL_ID = "approvalId"`). Cascading updates: `LoggingFieldNameContractTest.mdcKeysSetMatchesPublishedSurface` (+ camelCase pin), `logback-spring.xml` pattern + comment.
- OpenAPI snapshot (`openapi.json`) updated with the new optional `reviewerRole` + `reason` properties on `ApproveSpecRequest` so `OpenApiSnapshotContractTest` will pass on the next CI run (the snapshot is regenerated by `OpenApiSnapshotContractTest` itself when set via `-Dopenapi.snapshot.write=true`; the manually-updated diff matches the springdoc output).
- One inherited Checkstyle suppression line number drifted (`ForbiddenThreadSleep` in `WorkflowCommandService.java` from line 438 → 434 after the `approveSpecInternal` body shrank by delegation). Suppression file updated.

### File List

**New (production):**

- `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/spi/ApprovalWritePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/ApprovalReviewerRoleResolver.java`

**New (tests):**

- `deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceApproveSpecTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalResultTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapterTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/AllowedActionRegistryPinTest.java`

**Modified (production):**

- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/ApproveSpecCommand.java` — added `reviewerRole` + `reason` record components.
- `deliveryline-backend/src/main/java/org/dradgo/application/idempotency/WorkflowCommandFingerprintFactory.java` — extended ApproveSpec branch to fingerprint `reviewerRole` (reason intentionally excluded).
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java` — constructor accepts `ApprovalService`; `approveSpecInternal` delegates to it.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` — wires `ApprovalReviewerRoleResolver` into the ApproveSpec command construction.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ApproveSpecRequest.java` — added optional `reviewerRole` + `reason`.
- `deliveryline-backend/src/main/java/org/dradgo/application/observability/MdcKeys.java` — added `APPROVAL_ID` constant + ALL set entry.
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` — added TODO(story-2.14) comment on `APPROVE_SPEC`.
- `deliveryline-backend/src/main/resources/application.yml` — added `deliveryline.approval.default-reviewer-role` property.
- `deliveryline-backend/src/main/resources/logback-spring.xml` — added `approvalId` to the human-readable pattern + comment.
- `deliveryline-backend/src/main/resources/openapi/openapi.json` — added `reviewerRole` + `reason` to the `ApproveSpecRequest` schema.

**Modified (tests):**

- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceContractTest.java` — seeded approval-ready artifact + payload for happy-path tests; rewrote `approveSpecRaisesArtifactNotFoundWhenArtifactDoesNotExist` (was `…RunNotFound…`) for the new contract; cleaned approvals + artifacts in `cleanDatabase`.
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/commands/WorkflowCommandTypeTest.java` — updated `ApproveSpecCommand` positional construction.
- `deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java` — imports + `@Import(ApprovalReviewerRoleResolver.class)`; expected command carries `product_reviewer` from MVP fallback.
- `deliveryline-backend/src/test/java/org/dradgo/contract/ProblemDetailsContractTest.java` — `@Import(ApprovalReviewerRoleResolver.class)`.
- `deliveryline-backend/src/test/java/org/dradgo/adapters/WorkflowAdapterEquivalenceTest.java` — `@Import(ApprovalReviewerRoleResolver.class)`.
- `deliveryline-backend/src/test/java/org/dradgo/observability/LoggingFieldNameContractTest.java` — added `approvalId` to both assertions and rejected-variant list.
- `deliveryline-backend/src/test/java/org/dradgo/observability/testsupport/ItLoggingHarness.java` — Javadoc updated for the 6-key MDC surface.
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` — added `approval_service_lives_in_application_approval`.
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` — added `APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL` rule.

**Modified (build / configuration):**

- `config/checkstyle/suppressions.xml` — updated `ForbiddenThreadSleep` line number for `WorkflowCommandService.java` (438 → 434) after delegation shrunk `approveSpecInternal`.

### Change Log

| Date | Description |
|------|-------------|
| 2026-05-23 | Story 2.9 dev-story complete; status `ready-for-dev → review`. ApprovalService writer + ApprovalResult + ApprovalWritePort/Adapter shipped, WorkflowCommandService.approveSpec delegates to it, fingerprint factory includes reviewerRole (excludes reason). MDC surface widened to add `approvalId`. Open questions OQ-1/OQ-2/OQ-3 resolved per recommendations. |
