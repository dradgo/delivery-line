# Story 2.10: Backend — Spec Rejection with Structured Feedback + Escalation

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager,
I want `ApprovalService.rejectSpec(...)` accepting structured feedback (tagged with the rework taxonomy `missing_scope` / `unclear_specification` / `misunderstood_implementation` from story 1.3) plus a workflow-level escalation marker when rejection loops repeat beyond a configurable threshold,
So that rejection feedback flows back into the spec-rebuild loop with measurable rework categorization (**FR9**, **AR34a**) and unresolved loops surface for human escalation rather than spinning forever (**FR13**).

## Acceptance Criteria

1. **Given** `RejectSpecCommand` (already exists at `application/workflow/commands/RejectSpecCommand.java` from story 1.7), **Then** the record is extended to carry: `workflowRunId`, `artifactId`, `artifactVersion` (= expected artifact version), `contextVersion` (= expected context-bundle version), `actorIdentity`, `actorType`, `reviewerRole` (NotBlank, max 128 — symmetric to `ApproveSpecCommand`), `reasonText` (NotBlank, max 1024 — free-form), `taggedFeedback` (new enum `RejectionTaxonomy` with values `MISSING_SCOPE`, `UNCLEAR_SPECIFICATION`, `MISUNDERSTOOD_IMPLEMENTATION`, NotNull), `idempotencyKey`, optional `correlationId`. **Keep the existing field names** `artifactVersion` / `contextVersion` (they ARE the expected versions — symmetric to 2.9 trap T1). Existing `reasonText` size cap widens 512 → 1024 to match `ApproveSpecCommand.reason`.
2. **Given** the V1 `approvals` table (`db/migration/V1__create_workflow_core_tables.sql:167-204`, already provisioned with the paired `decision` / `rejection_taxonomy` CHECK at lines 195-202), **Then** a successful rejection inserts a row with `decision='rejected'`, `reason = command.reasonText`, `rejection_taxonomy = command.taggedFeedback.value()` (one of `missing_scope` / `unclear_specification` / `misunderstood_implementation`), and all 7 attribution fields (`workflow_run_id`, `artifact_id`, `artifact_version`, `context_bundle_version`, `actor_identity`, `actor_type`, `reviewer_role`). **No** new column on `approvals`. The DB-level `uq_approvals_idempotency_key` UNIQUE remains the defense-in-depth backstop (trap T5 from 2.9).
3. **Given** version-binding (mirror story 2.9 AC4), **When** the artifact's current version differs from `expectedArtifactVersion` OR the run's current context-bundle version differs from `expectedContextBundleVersion`, **Then** `DomainException(APPROVAL_VERSION_MISMATCH, …)` is raised with `details` carrying `expectedArtifactVersion`, `currentArtifactVersion`, `expectedContextBundleVersion`, `currentContextBundleVersion`, `artifactId`, `workflowRunId`. No `approvals` row, no event, no transition, no counter increment. Run version-binding BEFORE eligibility, mirroring 2.9 trap T3.
4. **Given** a successful rejection, **When** committed, **Then** in **one transaction** (REQUIRED propagation; participates in the outer `WorkflowCommandService.rejectSpec @Transactional`): (a) the rejection row is inserted via `ApprovalWritePort.insert(...)`; (b) an `APPROVAL_REJECTED` event (registry value `"approval.rejected"` already registered in `WorkflowEventType:9`) is appended via `WorkflowEventWritePort.append(...)` carrying `details = { approvalId, artifactId, artifactVersion, contextBundleVersion, reviewerRole, taggedFeedback, idempotencyKey, correlationId?, specRejectionLoopCount }`; (c) `workflow_runs.spec_rejection_loop_count` is atomically incremented on the run row; (d) `WorkflowTransitionService.transition(runId, WorkflowState.INVESTIGATING, …)` is invoked — `WaitingForSpecApproval → Investigating` is already permitted by `WorkflowTransitionTable` (line 52). On any failure in (a)-(d) the entire transaction rolls back (no orphan row, no orphan event, no counter advance, no state change).
5. **Given** a configurable escalation threshold (default `3`, via `application.yml` property `deliveryline.workflow.spec-rejection-escalation-threshold` — surface this property AND a Javadoc on the resolver), **When** the post-increment value of `spec_rejection_loop_count` reaches OR exceeds the threshold AND `escalation_marker_set` was `false` before this rejection, **Then** in the SAME transaction as (4): (a) `workflow_runs.escalation_marker_set` is set to `true`; (b) a new workflow event `ESCALATION_REQUIRED` (registry value `"escalation.required"` — NEW value, add to `WorkflowEventType`) is appended with `details = { reason: 'spec_rejection_loop_threshold_exceeded', specRejectionLoopCount, threshold, idempotencyKey, correlationId? }`. The escalation event is appended ONCE per run (idempotent on the marker — if the marker is already `true`, do NOT append a duplicate escalation event on subsequent rejections, but still increment the counter and write the rejection row).
6. **Given** the escalation marker, **Then** `WorkflowInspectionService` exposes `escalationMarker: boolean` and `specRejectionLoopCount: int` on the per-run inspection view returned by `getStatus(runId)` / `WorkflowRunSummaryView` (queue surface). Mirror them onto the REST `WorkflowSummaryResponse` + `WorkflowDetailResponse` (story 6.9 surface) and CLI `status`/`history` text+JSON output (story 1.15 surface).
7. **Given** the make-or-break rule that escalation does NOT block the workflow (per **FR13** — it exposes the loop, doesn't terminate it), **Then** the workflow remains in `Investigating` after a threshold-exceeding rejection; the marker is purely informational + visible until manually cleared by an operator (operator clear-escalation action `AllowedAction.CLEAR_ESCALATION_MARKER` is already registered at `AllowedAction:16` — full implementation lands in Epic 4; this story does NOT add a clear-escalation endpoint).
8. **Given** AR34a measurement capture, **Then** the rejection row's `rejection_taxonomy` column is populated on every rejection (DB CHECK `ck_approvals_decision_taxonomy_paired` at V1 line 199-202 enforces non-null when `decision='rejected'`). A contract test asserts that constructing `RejectSpecCommand` with `taggedFeedback=null` is rejected at jakarta-validation time with a typed `INVALID_COMMAND_PAYLOAD` carrying `fieldErrors[].field='taggedFeedback'`, code `'NotNull'`. No separate `MISSING_REJECTION_TAXONOMY` error code is introduced (the existing `INVALID_COMMAND_PAYLOAD` path covers it — see Trap T7).
9. **Given** the test suite, **Then** it covers: (a) happy-path rejection — row written with `decision='rejected'` + `rejection_taxonomy` populated, `APPROVAL_REJECTED` event appended, counter incremented `0→1`, `WaitingForSpecApproval → Investigating`; (b) taxonomy-missing rejection — `INVALID_COMMAND_PAYLOAD` at validation, no DB writes; (c) version-mismatch rejection — `APPROVAL_VERSION_MISMATCH`, no DB writes, no transition; (d) threshold-not-exceeded — counter increments `2→3` when threshold=4, NO escalation event, marker stays `false`; (e) threshold-exceeded first time — counter increments `2→3` when threshold=3, `ESCALATION_REQUIRED` event appended, marker flips `false → true`; (f) threshold-already-exceeded subsequent rejection — counter increments `3→4`, NO duplicate escalation event, marker stays `true`; (g) inspection surface — `getStatus(runId)` returns `escalationMarker=true` + `specRejectionLoopCount=3` after (e); (h) idempotent replay — same `idempotencyKey` + fingerprint returns the prior result without a second row / second event / second counter increment; (i) idempotency-key conflict — different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`; (j) illegal-state-transition — rejecting from a run that's not in `WAITING_FOR_SPEC_APPROVAL` raises `ILLEGAL_TRANSITION` and rolls back the entire transaction (no row, no event, no counter advance); (k) transactional rollback shape — simulate a runtime failure inside `WorkflowTransitionService.transition` after the counter has already been incremented and assert the counter rolls back to its prior value (the row + event are also rolled back).
10. **Given** the V2 columns the AC describes are NOT yet in the schema (existing Flyway history runs V1 → V1_1 → V2 → V3 → V4 → V5 → V6; **V2 is already taken** by `V2__artifact_failure_columns.sql`), **Then** a NEW Flyway migration `V7__add_spec_rejection_loop_columns.sql` is shipped IN THIS STORY adding to `workflow_runs`:
    - `spec_rejection_loop_count integer NOT NULL DEFAULT 0` (CHECK `>= 0`);
    - `escalation_marker_set boolean NOT NULL DEFAULT false`.

    These two columns are de-scoped from story 2.11's V2 migration into this story to break the chicken-and-egg dependency (epic AC10 wording assumed bundling — see Trap T1 below + Open Question OQ-1). Story 2.11 still ships its own migration for the `clarifications` table separately. Migration replay safety asserted by `FlywayMigrationReplayTest` (or equivalent — check `deliveryline-backend/src/test/java/org/dradgo/.../flyway/` for the existing convention before adding).

**Scope guardrails:**

- **Out of scope for 2.10:** Clarifications domain model + table (story 2.11), allowed-actions endpoint (story 2.14), REST mutation endpoint rebuild with rich Problem Details (story 2.13 — the existing `WorkflowController.rejectSpec` keeps its current shape, with the new fields added to the request DTO + service call only), Approval Decision Bar UI (story 2.19), Run Review Queue Item UI escalation badge (story 2.15), Run Context Strip escalation badge (story 2.16), CLI escalation banner (story 1.15 read-side already extended by 6.9 — this story extends the JSON shape only, not the human-readable banner; that's a 1.15 follow-up).
- **No** operator "clear escalation marker" surface in this story — `AllowedAction.CLEAR_ESCALATION_MARKER` is registered (`AllowedAction:16`) but Epic 4 owns the action handler.
- **CLI surface:** add `taggedFeedback` (required) and `reviewerRole` (optional, defaults via `ApprovalReviewerRoleResolver`) to the existing CLI reject path so contract symmetry with REST holds (story 1.7 AC5). Do NOT add a new top-level CLI verb — extend the existing one.
- **The existing `WorkflowCommandService.rejectSpec` minimal stub** (currently writes only a state-changed transition with no `approvals` row, no `APPROVAL_REJECTED` event, no counter, no taxonomy) is REPLACED by delegation to `ApprovalService.rejectSpec(...)`, mirroring the 2.9 refactor of `approveSpec`.

## Tasks / Subtasks

- [x] **Task 1: New Flyway migration `V7__add_spec_rejection_loop_columns.sql`** (AC: 4, 5, 10)
  - [x] Create `deliveryline-backend/src/main/resources/db/migration/V7__add_spec_rejection_loop_columns.sql`:
    ```sql
    alter table workflow_runs
        add column spec_rejection_loop_count integer not null default 0,
        add column escalation_marker_set boolean not null default false,
        add constraint ck_workflow_runs_spec_rejection_loop_count_nonneg
            check (spec_rejection_loop_count >= 0);
    ```
  - [x] Verify replay-safe: `mvn -pl deliveryline-backend -o test -Dtest='FlywayMigrationReplayTest'` (or whichever test the project uses — grep `FlywayMigration` under `deliveryline-backend/src/test/` first).
  - [x] Update any test fixtures that seed `workflow_runs` rows directly via SQL — both columns have defaults, so existing rows should not break, but the JPA entity (Task 2) must surface them as readable+writable.
  - [x] Document in the migration's leading comment WHY this migration lands here (de-scoped from story 2.11's V2 plan; existing V2 is taken by artifact-failure columns; story 2.11 still ships its own migration for clarifications).

- [x] **Task 2: Extend `WorkflowRunEntity` + `WorkflowRunSnapshot` with the two new columns** (AC: 4, 5, 6)
  - [x] Add `@Column(name = "spec_rejection_loop_count")` `int specRejectionLoopCount` and `@Column(name = "escalation_marker_set")` `boolean escalationMarkerSet` to `adapters/persistence/entity/WorkflowRunEntity.java`. NOT-NULL columns; defaults in DDL mean Hibernate `insertable=false, updatable=false` is NOT needed — but verify Hibernate insert SQL includes both columns once they're attributes.
  - [x] Add reader-only getters: `getSpecRejectionLoopCount()`, `isEscalationMarkerSet()`. Package-private `setSpecRejectionLoopCount(int)` + `setEscalationMarkerSet(boolean)` so only the persistence package mutates them.
  - [x] Extend `application/workflow/spi/WorkflowRunSnapshot.java`: add `int specRejectionLoopCount` and `boolean escalationMarkerSet` as new record components. Keep `WorkflowRunSnapshot` an "intentionally lossy view" but these two fields ARE needed for the inspection surface (AC6). Grep before editing: `grep -rn "new WorkflowRunSnapshot(" deliveryline-backend/src/` — update every constructor call.
  - [x] Extend the mapper `application/workflow/spi → WorkflowRunReadPort` adapter to populate the new fields (path: `adapters/persistence/WorkflowRunReadPersistenceAdapter.java` or similar — read first).
  - [x] If `WorkflowRunCreatePort.create(...)` builds an entity (`adapters/persistence/.../WorkflowRunCreatePersistenceAdapter.java`), no change needed — the DB defaults kick in.

- [x] **Task 3: `WorkflowRunWritePort` (or existing equivalent) for counter+marker atomic update** (AC: 4, 5)
  - [x] Identify the existing write port for `workflow_runs` mutations. Likely candidate: the same adapter that backs `WorkflowTransitionService` (the state-mutation path). Read `application/workflow/spi/` to confirm. **If no per-run write port exists** (the transition service updates `current_state` via `JpaRepository.save(...)` on a loaded entity), then EXTEND the existing repository with an explicit method:
    ```
    // In adapters/persistence/repository/WorkflowRunRepository.java
    @Modifying
    @Query("update WorkflowRunEntity w
              set w.specRejectionLoopCount = w.specRejectionLoopCount + 1
              where w.publicId = :publicId")
    int incrementSpecRejectionLoopCount(@Param("publicId") String publicId);

    @Modifying
    @Query("update WorkflowRunEntity w
              set w.escalationMarkerSet = true
              where w.publicId = :publicId and w.escalationMarkerSet = false")
    int markEscalationOnce(@Param("publicId") String publicId);
    ```
    The `where … escalationMarkerSet = false` guard is the per-row idempotency for AC5 (escalation event appended once).
  - [x] Add a small SPI method `WorkflowRunReadPort.findSpecRejectionLoopCountAndMarker(String publicId) -> Optional<RejectionLoopState>` (record carrying `loopCount` + `markerSet`) so `ApprovalService.rejectSpec` can read the POST-increment state without a second entity load. **Or**: mark the increment query `@Query(value="...", returning="...")` to atomically return the new value — verify Hibernate/JPA support before choosing.
  - [x] Wire the new repository methods into `WorkflowRunReadPort` / a new `WorkflowRunRejectionLoopPort` (single-purpose SPI to avoid widening the read port). Adapter implementation lives in `adapters/persistence/WorkflowRunRejectionLoopPersistenceAdapter.java` (or co-located with the existing run adapter — match the convention you find).

- [x] **Task 4: Extend `WorkflowCommandFingerprintFactory` for new `RejectSpecCommand` fields** (AC: 1)
  - [x] Edit `application/idempotency/WorkflowCommandFingerprintFactory.java:36-45`. The current `RejectSpecCommand` branch hashes `workflowRunId`, `artifactId`, `artifactVersion`, `contextVersion`, `reasonText`. **Add** `reviewerRole` + `taggedFeedback.value()` (registry wire value). **Decision needed (Open Question OQ-2):** keep `reasonText` in the fingerprint OR remove it (mirror approve's trap T9 reasoning — free-form reviewer text edits should idempotently replay). Recommendation: REMOVE `reasonText` from the fingerprint to align with `ApproveSpecCommand.reason` (excluded) — symmetric treatment of free-form reviewer text. Document the change in the factory's Javadoc + `RejectSpecCommand`'s Javadoc.
  - [x] Update the `RejectSpecCommand` record-level Javadoc to list the new canonical fingerprint fields: `workflowRunId, artifactId, artifactVersion (expected), contextVersion (expected), reviewerRole, taggedFeedback`.
  - [x] Grep for `WorkflowCommandFingerprintFactoryTest` and add coverage for: (a) reviewerRole shift changes fingerprint; (b) taggedFeedback shift changes fingerprint; (c) reasonText edits do NOT change fingerprint (if Recommendation accepted); (d) symmetry parity with `ApproveSpecCommand` exclusion.

- [x] **Task 5: New `RejectionTaxonomy` registry enum** (AC: 1, 2)
  - [x] Create `domain/registry/RejectionTaxonomy.java` implementing `RegistryValue`:
    ```
    public enum RejectionTaxonomy implements RegistryValue {
      MISSING_SCOPE("missing_scope"),
      UNCLEAR_SPECIFICATION("unclear_specification"),
      MISUNDERSTOOD_IMPLEMENTATION("misunderstood_implementation");

      private static final Map<String, RejectionTaxonomy> LOOKUP = RegistryParsers.index(values());

      private final String wireValue;

      RejectionTaxonomy(String wireValue) { this.wireValue = wireValue; }

      @Override public String value() { return wireValue; }

      public static RejectionTaxonomy fromValue(String rawValue, String field) {
        return RegistryParsers.parse("RejectionTaxonomy", rawValue, field, LOOKUP);
      }
    }
    ```
  - [x] Wire it through `PersistedRegistryValues` (read `domain/registry/PersistedRegistryValues.java` first — it already brokers the persisted-string ↔ enum conversion for `ActorType` etc.).
  - [x] Update `RegistryContractTest` (or equivalent enum-drift test in `deliveryline-backend/src/test/java/org/dradgo/foundation/` / `architecture/`) to pin all three values.
  - [x] Document in the enum Javadoc: "Wire values intentionally match the V1 `ck_workflow_events_rejection_taxonomy` + `ck_approvals_rejection_taxonomy` CHECK constraint substrings; renaming an enum constant must keep `wireValue` identical or the DB CHECK rejects the row."

- [x] **Task 6: Extend `RejectSpecCommand` record + REST `RejectSpecRequest`** (AC: 1, 8)
  - [x] Edit `application/workflow/commands/RejectSpecCommand.java`. Add `@NotBlank @Size(max = 128) String reviewerRole` and `@NotNull RejectionTaxonomy taggedFeedback`. Widen `reasonText` cap from `@Size(max = 512)` to `@Size(max = 1024)` (symmetry with `ApproveSpecCommand.reason`).
  - [x] Update Javadoc per Task 4.
  - [x] Edit `adapters/rest/RejectSpecRequest.java`. Add `@Size(max = 128) String reviewerRole` (NULLABLE — `ApprovalReviewerRoleResolver` fills the default) and `@NotNull RejectionTaxonomy taggedFeedback`. Widen `reasonText` cap to 1024.
  - [x] Edit `adapters/rest/WorkflowController.java` lines 222-238 (`rejectSpec` endpoint). Plumb `reviewerRole` through `approvalReviewerRoleResolver.resolveFor(request.reviewerRole())` and `taggedFeedback` through directly. Mirror the existing `approveSpec` plumbing at lines 195-215.
  - [x] Regenerate the OpenAPI snapshot: `mvn -pl deliveryline-backend spring-boot:run -Dspring-boot.run.arguments=--openapi-export` (or whichever command the project uses — `ls deliveryline-backend/src/main/resources/openapi/openapi.json` confirms presence; grep `openapi` in pom.xml or scripts/ for the regenerate command). The CI drift check (story 1.21 AC6) must pass.
  - [x] Grep before editing: `grep -rn "new RejectSpecCommand" deliveryline-backend/src/` — update every construction site (REST controller, CLI command, any test fixture). Existing tests likely construct it with 9 fields; updated form has 11.

- [x] **Task 7: New `ApprovalService.rejectSpec(...)` method** (AC: 2, 3, 4, 5, 7, 8)
  - [x] Edit `application/approval/ApprovalService.java`. Add a `public ApprovalResult rejectSpec(RejectSpecCommand command)` method following the same shape as `approveSpec(...)`:
    ```
    public ApprovalResult rejectSpec(RejectSpecCommand command) {
      PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
      PublicIdPrefixes.require(command.artifactId(), PublicIdPrefixes.ARTIFACT);

      // MDC scope open
      try {
        log.info("rejectSpec entry workflowRunId={} artifactId={} expectedArtifactVersion={} expectedContextBundleVersion={} reviewerRole={} taggedFeedback={} actorIdentity={} actorType={}",
            ...);

        // AC3: version-binding BEFORE eligibility (mirror trap T3 from 2.9).
        var artifact = artifactRecordPort.findByPublicId(command.artifactId())
            .orElseThrow(() -> artifactNotFound(command));
        int currentArtifactVersion = artifact.version();
        int currentBundleVersion = resolveCurrentContextBundleVersion(command.artifactId());
        if (currentArtifactVersion != command.artifactVersion()
            || currentBundleVersion != command.contextVersion()) {
          throw versionMismatch(command, currentArtifactVersion, currentBundleVersion);
        }

        // AC2: insert approvals row with decision='rejected'.
        OffsetDateTime decidedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        String approvalId = PublicIdPrefixes.APPROVAL.next();
        ApprovalSnapshot persisted = approvalWritePort.insert(new NewApproval(
            approvalId, command.workflowRunId(), command.artifactId(),
            command.artifactVersion(), command.contextVersion(),
            command.actorIdentity(), command.actorType(), command.reviewerRole(),
            ApprovalSnapshot.DECISION_REJECTED, command.reasonText(),
            command.taggedFeedback().value(),  // rejection_taxonomy column
            decidedAt, command.idempotencyKey()));

        // AC4: increment counter + read post-increment value.
        int newLoopCount = workflowRunRejectionLoopPort.incrementAndReadLoopCount(command.workflowRunId());

        // AC4: append approval.rejected event in same transaction.
        Map<String, Object> rejectEventDetails = rejectEventDetails(command, persisted, newLoopCount);
        workflowEventWritePort.append(new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            command.workflowRunId(),
            WorkflowEventType.APPROVAL_REJECTED,
            null, null,
            command.actorIdentity(), command.actorType(),
            "specification rejected", null, false,
            decidedAt, rejectEventDetails));

        // AC5: threshold check — flip marker ONCE if threshold crossed AND marker was previously false.
        int threshold = escalationThresholdProvider.get();
        boolean priorMarker = workflowRunRejectionLoopPort.isEscalationMarkerSet(command.workflowRunId());
        if (newLoopCount >= threshold && !priorMarker) {
          int flipped = workflowRunRejectionLoopPort.markEscalationOnce(command.workflowRunId());
          if (flipped == 1) {
            workflowEventWritePort.append(new WorkflowEventRecord(
                PublicIdPrefixes.WORKFLOW_EVENT.next(),
                command.workflowRunId(),
                WorkflowEventType.ESCALATION_REQUIRED,
                null, null,
                command.actorIdentity(), command.actorType(),
                "spec rejection loop threshold exceeded", null, false,
                decidedAt, escalationEventDetails(command, threshold, newLoopCount)));
            log.warn("rejectSpec escalation marker raised workflowRunId={} loopCount={} threshold={}",
                command.workflowRunId(), newLoopCount, threshold);
          }
        }

        // AC4: transition WaitingForSpecApproval → Investigating.
        workflowTransitionService.transition(
            command.workflowRunId(),
            WorkflowState.INVESTIGATING,
            new TransitionActor(command.actorIdentity(), command.actorType()),
            "reject specification",
            command.idempotencyKey(),
            transitionEventDetails(command, persisted, newLoopCount));

        log.info("rejectSpec success approvalId={} workflowRunId={} taggedFeedback={} loopCount={} escalationMarker={}",
            persisted.publicId(), persisted.workflowRunId(),
            command.taggedFeedback().value(), newLoopCount, priorMarker || newLoopCount >= threshold);

        return new ApprovalResult(
            persisted.publicId(), persisted.workflowRunId(), persisted.artifactId(),
            persisted.artifactVersion(), persisted.contextBundleVersion(),
            persisted.reviewerRole(), persisted.decidedAt(),
            WorkflowState.INVESTIGATING,
            normalizeOptional(command.correlationId()));
      } finally {
        // MDC scope close
      }
    }
    ```
  - [x] **No `@Transactional` on `rejectSpec`** (mirror trap T4 from 2.9). Outer `WorkflowCommandService.rejectSpec @Transactional` is the boundary.
  - [x] Extract a `SpecRejectionEscalationThresholdProvider` (or use `@Value("${deliveryline.workflow.spec-rejection-escalation-threshold:3}")` injected into `ApprovalService`'s constructor). Recommended: provider component for testability — `application/workflow/SpecRejectionEscalationThresholdProvider.java`, mirror `ApprovalReviewerRoleResolver` shape.
  - [x] Update the `ApprovalService` Javadoc to document BOTH paths (`approveSpec` and `rejectSpec`); update the existing "Canonical executor for AllowedAction.APPROVE_SPEC" sentence to also list `REJECT_SPEC`.
  - [x] Add ArchUnit boundary assertion: `ApprovalService.rejectSpec` must not be `@Transactional(propagation=REQUIRES_NEW)`. Extend the existing boundary test from 2.9 (`ArchitectureRuleCatalog` — read it first to find the exact rule name).

- [x] **Task 8: New `ESCALATION_REQUIRED` registry value** (AC: 5)
  - [x] Add `ESCALATION_REQUIRED("escalation.required")` to `domain/registry/WorkflowEventType.java`. Verify drift tests pick it up (`RegistryContractTest`, `WorkflowEventTypeFixtureTest`, etc. — grep `WorkflowEventType` under `deliveryline-backend/src/test/`).
  - [x] Update the JSON-schema fixture at `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json` (if it pins the list of valid event types). Same for `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json:96` if it lists allowable types in the `eventType` enum.
  - [x] If the foundation fixture event streams have an "escalation" scenario, no change to existing fixtures is needed (story 1.23 has not yet shipped an escalation-loop fixture; the spec-rejection-and-resubmit fixture only goes to 1 loop and stays under the default threshold of 3).

- [x] **Task 9: Refactor `WorkflowCommandService.rejectSpec` to delegate to `ApprovalService`** (AC: 4, 7)
  - [x] Edit `application/workflow/WorkflowCommandService.java:100-102` + `:172-191`. The current minimal `rejectSpecInternal` body that calls `transition(... INVESTIGATING ...)` directly is REPLACED with:
    ```
    private WorkflowStateChangeResult rejectSpecInternal(RejectSpecCommand command) {
      ApprovalResult result = approvalService.rejectSpec(command);
      return new WorkflowStateChangeResult(
          result.workflowRunId(),
          result.resultingState(),
          result.correlationId());
    }
    ```
    Same delegation pattern story 2.9 used for `approveSpecInternal` (lines 160-170). Preserves the existing `WorkflowStateChangeResult` REST contract (trap T7 from 2.9 — story 2.13 will rebuild the surface).
  - [x] Verify the surrounding `executeIdempotent → rejectSpecInternal → ApprovalService.rejectSpec → ApprovalWritePort.insert + WorkflowEventWritePort.append (x1 or x2) + WorkflowRunRejectionLoopPort.increment/markEscalationOnce + WorkflowTransitionService.transition` chain runs in ONE transaction. Add `ApprovalServiceRejectSpecTransactionalityTest` mirroring the existing approve-spec rollback test from story 2.9.

- [x] **Task 10: Extend `WorkflowInspectionService` surface with `escalationMarker` + `specRejectionLoopCount`** (AC: 6)
  - [x] Edit `application/workflow/WorkflowInspectionService.java:731` (`WorkflowRunSummaryView`). Add two fields: `int specRejectionLoopCount`, `boolean escalationMarker`. Populate from `WorkflowRunSnapshot` (Task 2).
  - [x] Find the `getStatus(...)` / `getRunHeader(...)` / `getWorkflowDetail(...)` paths and add the same two fields to the detailed view (the view record that 6.9's `WorkflowDetailResponse` wraps). Grep `WorkflowDetailResponse`, `RunStatusView` in `WorkflowInspectionService` + `adapters/rest/`.
  - [x] Edit `adapters/rest/WorkflowSummaryResponse.java` (and its detail-view sibling). Add the two new JSON fields. **Regenerate openapi.json**; CI drift check must pass.
  - [x] CLI surface (story 1.15): the JSON-format output of `deliveryline status <runId>` must include the new fields. Find the CLI command (`grep -rn "WorkflowCommandOutputs" deliveryline-backend/src/main/java/org/dradgo/adapters/cli/`); extend the JSON serializer. Do NOT extend the text-format output in this story (deferred to 1.15 follow-up — leave a TODO comment with the link).

- [x] **Task 11: Test suite** (AC: 9)
  - [x] **Unit tests** under `deliveryline-backend/src/test/java/org/dradgo/application/approval/`:
    - `ApprovalServiceRejectSpecTest.java` — Mockito-driven. Mock all 8 injected dependencies (incl. new `WorkflowRunRejectionLoopPort` + `SpecRejectionEscalationThresholdProvider`). Cover cases AC9 (a)-(k). Capture `NewApproval` argument — assert `decision='rejected'` + `rejection_taxonomy=command.taggedFeedback.value()`. Capture `WorkflowEventRecord` arguments — assert `APPROVAL_REJECTED` event details carry `taggedFeedback`, `specRejectionLoopCount`. For threshold-exceeded path, also capture the `ESCALATION_REQUIRED` event. For threshold-already-exceeded path, assert `markEscalationOnce` was called but returned `0` (`@Modifying` query touched zero rows) AND no second escalation event was appended.
    - `ApprovalServiceRejectSpecLoggingTest.java` (or as a section of the unit test) — Logback `ListAppender` (mirror `ArtifactLoggingContractTest`). Pin: `INFO` entry, `INFO` success (with `taggedFeedback` + `loopCount` + `escalationMarker`), `WARN` `APPROVAL_VERSION_MISMATCH`, `WARN` `escalation marker raised`, `WARN` `ILLEGAL_TRANSITION` propagation. **MUST NOT log `reasonText`** (free-form reviewer text) — assertion verifies no log line contains the reason value.
  - [x] **Persistence-adapter test** under `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/`:
    - `WorkflowRunRejectionLoopPersistenceAdapterTest.java` — Testcontainers Postgres. Cases: `incrementAndReadLoopCount` returns 1 after first call, 2 after second; `markEscalationOnce` returns 1 the first time, 0 on subsequent calls; both columns surface through `WorkflowRunReadPort.findByPublicId(...)` reads.
    - Extend `ApprovalWritePersistenceAdapterTest.java` (already exists from 2.9) with a `decision='rejected'` + `rejection_taxonomy='missing_scope'` happy-path insert + read-back. Verify the `ck_approvals_decision_taxonomy_paired` DB CHECK fires when rejection_taxonomy is null (write a test that bypasses the application-layer validation — direct repository insert — to assert the DB-level invariant remains as a defense-in-depth backstop).
  - [x] **Service contract test** `ApprovalServiceRejectSpecContractTest.java` — Spring slice end-to-end through `WorkflowCommandService.rejectSpec` → `ApprovalService.rejectSpec` → adapters. Seed an `INVESTIGATING → WaitingForSpecApproval` run with one `available` spec artifact (reuse story 2.9's `seedAvailableSpecArtifact` helper from `WorkflowCommandServiceContractTest`). Cases per AC9 plus: post-rejection `ApprovalReadPort.listRejectionsByWorkflowRunAndArtifactType(runId, "spec")` returns the just-written row (the read path 2.8 ships immediately sees 2.10's writer); `WorkflowInspectionService.getStatus(runId)` returns the new fields after each branch (`escalationMarker=false` after one rejection at threshold=3; `escalationMarker=true` after three rejections at threshold=3); event stream `GET /api/v1/workflows/{id}/events` (story 6.9) returns the `approval.rejected` events (and `escalation.required` event when threshold crossed) in chronological order.
  - [x] **Migration test:** verify V7 migration applies cleanly on a fresh schema AND on a post-V6 existing schema (replay safety). If `FlywayMigrationReplayTest` exists, extend it; otherwise the migration's `IF NOT EXISTS` semantics + the Flyway-driven contract tests cover it.
  - [x] **Fingerprint factory tests** in `application/idempotency/WorkflowCommandFingerprintFactoryTest.java` per Task 4.
  - [x] **Registry drift tests** for `RejectionTaxonomy` (Task 5) and `WorkflowEventType.ESCALATION_REQUIRED` (Task 8).
  - [x] Focused Maven invocation:
    ```
    ./mvnw.cmd -pl deliveryline-backend -o -Dtest='ApprovalServiceRejectSpec*Test,ApprovalServiceRejectSpecContractTest,WorkflowRunRejectionLoopPersistenceAdapterTest,ApprovalWritePersistenceAdapterTest,WorkflowCommandFingerprintFactoryTest,RegistryContractTest,WorkflowCommandTypeTest,FoundationCommandModelSymmetryContract*' -Dsurefire.failIfNoSpecifiedTests=false test
    ```
    Plus `./mvnw.cmd -pl deliveryline-backend verify` once before opening the PR — JaCoCo gate from story 2.32 (LINE 81.33% / BRANCH 62.74%) must hold.

- [x] **Task 12: REST + CLI integration** (AC: 1, 6)
  - [x] Update `adapters/rest/WorkflowController.java#rejectSpec` (lines 222-238) per Task 6.
  - [x] Update the OpenAPI snapshot.
  - [x] CLI: extend the existing reject CLI command under `adapters/cli/` (grep `WorkflowCommands` — known surface; reads `RejectSpecCommand`). Add a `--tagged-feedback` flag (required, accepts the three taxonomy values) and an optional `--reviewer-role` flag.
  - [x] Pin the `WorkflowController.rejectSpec` HTTP response shape (story 2.9 trap T7) — add or extend a contract test that asserts the response body has exactly the existing fields after the request body widens.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels:
    - `INFO` on `ApprovalService.rejectSpec` entry (`workflowRunId`, `artifactId`, `expectedArtifactVersion`, `expectedContextBundleVersion`, `reviewerRole`, `taggedFeedback`, `actorIdentity`, `actorType` — **never** `reasonText`).
    - `INFO` on success (`approvalId`, `workflowRunId`, `artifactId`, `artifactVersion`, `contextBundleVersion`, `reviewerRole`, `taggedFeedback`, `specRejectionLoopCount`, `escalationMarker`, `resultingState=Investigating`).
    - `INFO` on transition invocation (`from=WaitingForSpecApproval`, `to=Investigating`).
    - `WARN` on `APPROVAL_VERSION_MISMATCH` (with `currentArtifactVersion`, `currentContextBundleVersion`).
    - `WARN` on escalation marker first-raise (`workflowRunId`, `loopCount`, `threshold`) — **the** signal for measurement dashboards.
    - `WARN` on `ILLEGAL_TRANSITION` propagation from `WorkflowTransitionService` (mirror approve trap T6 logging).
    - `WARN` on `IDEMPOTENCY_KEY_CONFLICT` DB backstop (`source=db_unique_constraint`).
    - `ERROR` only for unmatched `DataIntegrityViolationException` or other unhandled failures.
    - `DEBUG` for per-field version comparison + counter pre/post values.
  - [x] Required context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, `artifactId`, plus `approvalId` once generated. Open MDC scopes via `MdcKeys.beginScope/endScope` mirroring `ApprovalService.approveSpec`.
  - [x] **Forbidden in log output:** `reasonText` (free-form reviewer text, may contain product information); payload bytes; secrets; raw PII. Log `reasonText.length()` if presence-signaling is needed.
  - [x] Add at least one assertion in the test suite that the expected log line(s) are emitted at the expected level for each new branch (see Task 11 Logging test).

## Dev Notes

### Foundations already in place (do NOT rebuild)

- **`ApprovalWritePort` + `ApprovalWritePersistenceAdapter`** (story 2.9, `adapters/persistence/ApprovalWritePersistenceAdapter.java`). Already handles `decision='rejected'` via the `NewApproval.decision` + `rejectionTaxonomy` fields and the DB-level `uq_approvals_idempotency_key` mapping to `IDEMPOTENCY_KEY_CONFLICT`. **Story 2.10 reuses it as-is** — supply `decision="rejected"` + `rejectionTaxonomy=taggedFeedback.value()` in the `NewApproval` payload.
- **`ApprovalSnapshot`** (story 2.8, `application/approval/ApprovalSnapshot.java`). Already supports `DECISION_REJECTED` + carries `rejectionTaxonomy`. Already validates `decision='rejected' ⇒ rejectionTaxonomy != null` in its compact constructor. No change.
- **`ApprovalEntity` + `ApprovalEntityMapper`** (story 2.8). Already maps `rejection_taxonomy` column ↔ `ApprovalSnapshot.rejectionTaxonomy`. No change.
- **`ApprovalReadPort.listRejectionsByWorkflowRunAndArtifactType(...)`** (story 2.8 `application/approval/spi/ApprovalReadPort.java:47`). Already returns rejection rows in chronological order — picks up the new writes immediately for the inspection surface (Task 10 + AC6).
- **`WorkflowEventType.APPROVAL_REJECTED`** — already registered (`domain/registry/WorkflowEventType.java:9`) with wire value `"approval.rejected"`. **No registry addition for that event.** `ESCALATION_REQUIRED` is a NEW value (Task 8).
- **`DomainErrorCode.APPROVAL_VERSION_MISMATCH`, `IDEMPOTENCY_KEY_CONFLICT`, `ARTIFACT_RECORD_NOT_FOUND`, `ILLEGAL_TRANSITION`, `INVALID_COMMAND_PAYLOAD`** — all already registered (`domain/registry/DomainErrorCode.java`). **No new error code** (`MISSING_REJECTION_TAXONOMY` is not introduced — `INVALID_COMMAND_PAYLOAD` covers it; see Trap T7).
- **`AllowedAction.REJECT_SPEC`** — already registered (`AllowedAction:10`). Story 2.14 wires the state×role→action-set logic. Story 2.10 adds a regression-pin test asserting `REJECT_SPEC.value().equals("reject_spec")` (mirror the `APPROVE_SPEC` pin from 2.9).
- **`AllowedAction.CLEAR_ESCALATION_MARKER`** — already registered (`AllowedAction:16`). Story 2.10 does NOT add the operator handler; Epic 4 owns it.
- **`WorkflowState.WAITING_FOR_SPEC_APPROVAL → INVESTIGATING`** — already permitted by `WorkflowTransitionTable.defaultTable()` line 52. **No transition-table change.**
- **`WorkflowTransitionService.transition(...)`** — the only state-mutation path. Inject and call; do NOT bypass to write `workflow.stateChanged` events manually. This service appends `WORKFLOW_STATE_CHANGED` on its own; `ApprovalService.rejectSpec` is responsible for appending the **separate** `APPROVAL_REJECTED` event in the same transaction (and the optional `ESCALATION_REQUIRED` event).
- **`WorkflowCommandService.executeIdempotent(...)`** — the canonical idempotency-reserve / replay / complete pipeline; `WorkflowCommandFingerprintFactory` already serializes `RejectSpecCommand` (lines 36-45 of the factory). Tasks 4 + 9 update fingerprint + delegation.
- **`ArtifactService.isApprovalEligible(String)`** + `ArtifactRecordPort.findByPublicId(String)` — reused as-is for the version-binding + eligibility branch.
- **`ApprovalReviewerRoleResolver`** — story 2.9 component (`application/workflow/ApprovalReviewerRoleResolver.java`). Used by the REST controller to default `reviewerRole` when the legacy surface doesn't supply one. **Reuse for `rejectSpec`** — do NOT roll a sibling resolver.
- **V1 `approvals` table** — already provisioned with paired-decision/taxonomy CHECK. **No new column on `approvals`.**
- **`MdcKeys.APPROVAL_ID`** — added by story 2.9.

### V2 migration scope conflict (epic doc vs. existing migration history) — Trap T1

The epic story 2.10 AC10 + story 2.11 AC2 say the two new `workflow_runs` columns (`spec_rejection_loop_count`, `escalation_marker_set`) land in story 2.11's Flyway migration `V2__add_spec_loop_and_clarifications.sql`. **This is impossible:**

1. **`V2` is already used** by `V2__artifact_failure_columns.sql` (shipped earlier in Epic 1). The existing migration history is V1 → V1_1 → V2 → V3 → V4 → V5 → V6. The next available migration is `V7`.
2. **Story 2.11 has NOT started** (sprint-status: backlog). Story 2.10 cannot wait for it without re-ordering the sprint.
3. The epic AC10 phrasing ("this story's implementation depends on V2 having merged") is an artifact of the planning-doc author bundling 2.10's columns into 2.11's migration to "save a migration" — which conflicts with the actual migration sequence.

**Resolution adopted by this story** (per recommendation, surfaced to Alex as OQ-1):

- Story 2.10 ships its OWN migration `V7__add_spec_rejection_loop_columns.sql` containing the two new `workflow_runs` columns.
- Story 2.11 keeps its scope (clarifications table) but renumbers its migration to whatever is next available when it ships — likely `V8__add_clarifications.sql`. Drop the "+ workflow_runs columns" bundling from story 2.11's planned content.
- This breaks the chicken-and-egg: 2.10 ships independently of 2.11.

If Alex prefers to re-sequence the sprint (ship 2.11 first, then 2.10), the story needs to be re-created against that ordering and Task 1 deleted. **Default assumption** in this story: ship V7 here.

### Context-bundle version source (AC3)

Reuse `ApprovalService.resolveCurrentContextBundleVersion(String artifactId)` from story 2.9 (private method in the same service). The bootstrap path (artifact with no `runner_execution_id`) returns version `1`. **Do NOT duplicate this method** — extract it to a `private` helper or reuse the existing one by keeping `rejectSpec` in the same class.

### Counter increment atomicity (AC4, AC5)

The counter increment + escalation-marker flip + transition all run inside the outer `@Transactional` boundary, so they roll back together on any failure. But to make the read-after-increment robust:

- Use `@Modifying @Query` JPQL/JPA UPDATE statements that return the new value (or do a follow-up `select` inside the same transaction). Hibernate caches must be flushed; check whether `@Query`'s `clearAutomatically=true` / `flushAutomatically=true` is needed.
- Alternatively, fetch the `WorkflowRunEntity`, increment in-memory, `saveAndFlush`, read the new field. This relies on the `@Version` optimistic-lock column on `WorkflowRunEntity` to reject concurrent rejections that race the same loop counter — desired behavior (concurrent rejections from two reviewers should not both succeed and double-increment).
- Document the chosen approach in `ApprovalService.rejectSpec`'s Javadoc.

### Traps (anti-pattern prevention)

| ID | Trap | Resolution |
|----|------|------------|
| **T1** | The epic + planning docs assume the two new `workflow_runs` columns land via a `V2__add_spec_loop_and_clarifications.sql` migration in story 2.11. **But:** V2 is already taken (`V2__artifact_failure_columns.sql`), story 2.11 is in backlog (not started), and story 2.10 cannot ship its escalation behavior without those columns. | **Ship the columns in a new `V7__add_spec_rejection_loop_columns.sql` migration as part of THIS story.** Story 2.11 renumbers its migration when it ships. Documented as Open Question OQ-1 — surface in PR description for explicit Alex sign-off. |
| **T2** | Existing `WorkflowCommandService.rejectSpec` (lines 100-102 / 172-191) is a minimal stub that only writes a `WORKFLOW_STATE_CHANGED` event via the transition service; no `approvals` row, no `APPROVAL_REJECTED` event, no taxonomy. Naively *adding* the new fields without refactoring leaves the stub path alive. | **REPLACE** `rejectSpecInternal` body with delegation to `ApprovalService.rejectSpec(...)`, mirroring the 2.9 refactor of `approveSpecInternal`. Verify no production code path bypasses `ApprovalService`. |
| **T3** | Symmetric with 2.9 T3: if `isApprovalEligible` runs before version-binding, a stale-version reviewer gets `ARTIFACT_PAYLOAD_UNAVAILABLE` instead of the more actionable `APPROVAL_VERSION_MISMATCH`. | **Version-binding BEFORE eligibility** in `ApprovalService.rejectSpec`. Same ordering as `approveSpec`. (Technically rejection doesn't need eligibility — but version-binding is still mandatory; eligibility check is optional for rejection and we currently DO NOT run it for rejection because rejecting a stale-but-unavailable artifact should still succeed. See Open Question OQ-3.) |
| **T4** | Adding `@Transactional` on `ApprovalService.rejectSpec` (especially with `REQUIRES_NEW`) breaks rollback shape — the rejection row + counter increment + escalation event could commit while the outer transition fails. | **`ApprovalService.rejectSpec` has NO `@Transactional` annotation.** Relies on the outer `WorkflowCommandService.rejectSpec @Transactional`. The transactionality integration test pins rollback. Extend the 2.9 ArchUnit boundary rule. |
| **T5** | Escalation event appended every rejection once threshold reached, not just the first time the threshold is crossed. Spamming `escalation.required` events on every subsequent rejection floods the audit history. | **Append `ESCALATION_REQUIRED` ONLY when the marker transitions `false → true`.** The `markEscalationOnce` query has a `WHERE escalation_marker_set = false` guard returning row count (0 = already raised; 1 = just raised by us). Only emit the event when the row-count is 1. |
| **T6** | `WorkflowCommandFingerprintFactory.fingerprintFor(RejectSpecCommand)` currently includes `reasonText`. Per 2.9 trap T9, free-form reviewer text should NOT be fingerprinted (a reviewer who edits their reason mid-flight gets `IDEMPOTENCY_KEY_CONFLICT` instead of an idempotent replay). | **Recommended:** remove `reasonText` from the fingerprint; add `reviewerRole` and `taggedFeedback`. Symmetric to `ApproveSpecCommand`. This is a behavioral change — surface in PR description (Open Question OQ-2). |
| **T7** | The epic AC8 says "MISSING_REJECTION_TAXONOMY error if absent" implying a new `DomainErrorCode`. But `RejectSpecCommand.taggedFeedback` is `@NotNull` — jakarta-validation rejects null at the command boundary with the existing `INVALID_COMMAND_PAYLOAD` error and `fieldErrors[].field='taggedFeedback'`, `code='NotNull'`. | **Do NOT introduce `MISSING_REJECTION_TAXONOMY`.** The existing `INVALID_COMMAND_PAYLOAD` path already produces a precise per-field error code matching the project's command-validation convention. The DB-level `ck_approvals_decision_taxonomy_paired` CHECK is the defense-in-depth backstop (if a future caller bypasses validation, it surfaces as a `DataIntegrityViolationException` which `ApprovalWritePersistenceAdapter` currently rethrows — that's correct for "should never happen" branches). The AC wording is descriptive, not normative on error-code naming. |
| **T8** | The REST `RejectSpecRequest` and CLI command construct `RejectSpecCommand` directly; if the request DTO lacks `taggedFeedback`, the command's `@NotNull` won't fire until inside the service — leaking a worse error. | Add `@NotNull` on the REST DTO's `taggedFeedback` so jakarta-validation rejects with `INVALID_COMMAND_PAYLOAD` at the controller boundary (consistent with `actorType`). Same for the CLI flag — make it required, fail fast at parse time. |
| **T9** | The CLI command (`adapters/cli/WorkflowCommands.java`) currently constructs `RejectSpecCommand` with 9 fields. Adding `reviewerRole` + `taggedFeedback` is a constructor-shape change; missed call sites will fail to compile but might be in tests too. | Grep before refactoring: `grep -rn "new RejectSpecCommand" deliveryline-backend/`. Update every site, including the foundation `CommandModelSymmetryFoundationContract` test if it constructs sample commands. |
| **T10** | The `escalation_marker_set` column has a `DEFAULT false` so existing rows are unaffected by V7, but the JPA entity must add the new fields as nullable-or-default-bearing to avoid Hibernate insert errors on FRESH rows the test suite seeds via repository inserts. | Match the V1 DDL pattern: declare both columns NOT NULL in the entity (Java primitives `int` + `boolean` default to 0 / false), do NOT add `@Column(insertable=false, updatable=false)` — the entity owns both fields. Hibernate will include them in the INSERT statement with the Java defaults, matching the V7 DEFAULT. |

### Open Questions (resolve before merging)

- **OQ-1: Migration sequencing.** Should `V7__add_spec_rejection_loop_columns.sql` ship in THIS story (recommended; breaks the 2.10↔2.11 chicken-and-egg), or should the sprint re-sequence to ship story 2.11 first and bundle the columns into its migration? **Recommendation:** ship V7 here. Surface in PR description.
- **OQ-2: Fingerprint factory — remove `reasonText` from `RejectSpecCommand` fingerprint?** The existing factory includes `reasonText`. Story 2.9 explicitly excluded `reason` from `ApproveSpecCommand`'s fingerprint per trap T9 (free-form text edits should replay idempotently). **Recommendation:** remove `reasonText` for symmetry; add `reviewerRole` + `taggedFeedback`. This is a behavioral change for the existing REST surface — but the surface today has no production callers exercising the replay-with-edited-reason path. Surface in PR description.
- **OQ-3: Run eligibility check for rejection?** `approveSpec` runs `ArtifactService.isApprovalEligible(...)` — a payload checksum verification — because approving an unavailable artifact would commit to executing a corrupted spec. Rejection has no such concern: rejecting a stale-but-unavailable artifact is still a valid PM decision (the artifact may be unavailable BECAUSE the spec runner crashed mid-write, and the reviewer wants to reject the partial output). **Recommendation:** SKIP the eligibility check for `rejectSpec` — run only version-binding. Document in the AC comments. Surface in PR description if reviewer disagrees.

### Project Structure Notes

- **`ApprovalService.rejectSpec(...)` method** → `application/approval/ApprovalService.java` — extend the existing service.
- **`RejectionTaxonomy` enum** → `domain/registry/RejectionTaxonomy.java`. Sibling of `ActorType`, `WorkflowState`.
- **`WorkflowEventType.ESCALATION_REQUIRED`** → add to existing `domain/registry/WorkflowEventType.java`.
- **`SpecRejectionEscalationThresholdProvider`** → `application/workflow/SpecRejectionEscalationThresholdProvider.java` (or inline `@Value` in `ApprovalService`'s constructor — provider preferred for testability).
- **`WorkflowRunRejectionLoopPort`** SPI + adapter → `application/workflow/spi/` + `adapters/persistence/`. Sibling of the existing run read/create ports.
- **`V7__add_spec_rejection_loop_columns.sql`** → `deliveryline-backend/src/main/resources/db/migration/`. NEW file.
- **REST `RejectSpecRequest`** + `WorkflowController#rejectSpec` updates → `adapters/rest/`. Existing files.
- **CLI** → `adapters/cli/WorkflowCommands.java`. Existing file.
- **No new domain error codes.** No new top-level packages.
- **JaCoCo gate** (story 2.32, LINE 81.33% / BRANCH 62.74%) applies — new code must keep the gate green. Local verify: `./mvnw.cmd -pl deliveryline-backend verify` on WSL2 Ubuntu native (per project memory note).

### Architecture compliance

- **Approval checkpoints** (architecture.md:81): "Each approval must bind to a specific artifact version, context bundle version, workflow state, actor identity, reviewer role, decision, reason." Rejection is an approval-with-`decision='rejected'`; binds the same way (AC1, AC2, AC3).
- **State+event atomicity** (architecture.md:301-302): "Any workflow state transition must update the operational run state and append the corresponding audit event in the same PostgreSQL transaction." Rejection row insert + `APPROVAL_REJECTED` event + counter increment + optional `ESCALATION_REQUIRED` event + `WORKFLOW_STATE_CHANGED` event + `workflow_runs.current_state` update + (atomic) `workflow_runs.spec_rejection_loop_count` + `escalation_marker_set` mutation all commit together (Task 9 transactionality test).
- **Idempotency** (architecture.md:185, 317): rejection commands flow through the same `WorkflowCommandService.executeIdempotent` pipeline (story 1.9). DB-level `uq_approvals_idempotency_key` is the defense-in-depth backstop.
- **Component boundaries** (architecture.md:1145-1175): `ApprovalService` already lives in `application/approval`. The new `WorkflowRunRejectionLoopPort` is an `application/workflow/spi` interface — adapter lives in `adapters/persistence` per the existing pattern. ArchUnit enforces.
- **FR13 contract** (PRD:645): "expose unresolved specification loops for human escalation". Escalation is visible (via inspection surface) and informational; it does NOT terminate the workflow (AC7). The PM continues working with the run; an operator can manually clear the marker (Epic 4).
- **AR34a measurement capture** (PRD:79): "Requirement-related rework decreases versus baseline, using a tagged review taxonomy for missing scope, unclear specification, or misunderstood implementation intent." This story persists the taxonomy on every rejection row (AC2, AC8) so Epic 5's measurement-aggregation queries (story 5.5) can compute rework rates.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface for THIS story):**
  - `ApprovalService.rejectSpec` → `INFO` on entry (envelope fields, NEVER `reasonText`); `INFO` on success (all attribution + `taggedFeedback` + `loopCount` + `escalationMarker`); `WARN` on each typed-domain rejection branch; `WARN` on escalation marker first-raise (the measurement signal).
  - `WorkflowRunRejectionLoopPersistenceAdapter` → `DEBUG` on each `increment` / `markEscalationOnce` invocation; `WARN` on zero-row update unexpected (run not found).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, `artifactId`, plus `approvalId` once generated.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields, and the **reviewer-supplied `reasonText`** (free-form — may contain unredacted product information). Log `reasonText.length()` if presence-signaling is needed, never the text itself.
- **Test contract:** new logging surfaces pinned by `ApprovalServiceRejectSpecTest` using a Logback `ListAppender` matching the existing `ArtifactLoggingContractTest` style. Required pins: success line, all WARN rejection branches, the escalation-marker WARN, the `ILLEGAL_TRANSITION` propagation WARN.

### Previous-story intelligence

- **Story 2.9 (ApprovalService.approveSpec writer, in review):** shipped `ApprovalService`, `ApprovalResult`, `ApprovalWritePort`, `ApprovalWritePersistenceAdapter`, `MdcKeys.APPROVAL_ID`, `ApprovalReviewerRoleResolver`, `default-reviewer-role` config property, `seedAvailableSpecArtifact` test helper, ArchUnit rule `APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL`, 9 traps T1-T9 documented in the sprint-status entry. Story 2.10 reuses EVERY one of these without modification. The `application.approval` package + `apr_` prefix + `archived_at IS NULL` filter conventions are in place.
- **Story 2.9 traps relevant to 2.10:** T1 (keep short field names) → mirrored as Task 6 widening — the same `artifactVersion` / `contextVersion` naming used by both commands. T3 (version-binding before eligibility) → mirrored in `rejectSpec`. T4 (no `@Transactional` on `ApprovalService`) → mirrored. T5 (DB-level idempotency-key backstop) → reused — `ApprovalWritePersistenceAdapter` already maps it for ANY decision value. T6 (single outer transaction) → mirrored. T7 (preserve existing REST shape) → mirrored — story 2.13 rebuilds the REST surface. T8 (reuse `seedAvailableSpecArtifact`) → mirrored in Task 11. T9 (exclude free-form text from fingerprint) → see Trap T6 + Open Question OQ-2 in this story.
- **Story 2.9 open questions (OQ-1/OQ-2/OQ-3) — relevance to 2.10:** OQ-1 (default reviewer role) — `ApprovalReviewerRoleResolver` is reused as-is for `rejectSpec`'s `reviewerRole` plumbing. OQ-2 (context-bundle version source) — reuse the resolution from 2.9 (bootstrap=1 when no `runner_execution_id`). OQ-3 (explicit-switch fingerprint factory) — the factory is explicit-switch, so Task 4 extends it explicitly for new fields.
- **Story 2.8 (spec artifact + context bundle, in review):** shipped the `ApprovalReadPort` + `ApprovalSnapshot` + repository/entity/mapper that 2.10's writer feeds. `listRejectionsByWorkflowRunAndArtifactType` will immediately surface 2.10's writes.
- **Story 1.18 (CLI MVR baseline, done):** established the `RecoveryService.describeFailure(...)` integration into `WorkflowInspectionService.getStatus(...)`. Story 2.10 extends the same inspection surface (Task 10) — mirror the field-addition pattern used there.
- **Story 1.7 (shared command-model pattern, done):** `WorkflowCommand` sealed interface; `RejectSpecCommand` already conforms. Task 6 extends fields without breaking sealed-type guarantee.
- **Story 1.9 (idempotency service, done):** `executeIdempotent` pipeline; `ApprovalService.rejectSpec` does NOT call `IdempotencyService` directly — the surrounding `WorkflowCommandService.rejectSpec` does.
- **Story 2.32 (JaCoCo gate, done):** LINE 81.33% / BRANCH 62.74% floor. Reproduce on WSL2 Ubuntu before pushing.
- **Story 1.5 (workflow transition table, done):** `WAITING_FOR_SPEC_APPROVAL → INVESTIGATING` already permitted.

### Git intelligence

Recent commits show:

- `fdcd6d2 Story 2.7: apply code-review patches; status -> done` — post-review patch shape.
- `4d64e4d Story 2.32: backend coverage reporting + Maven JaCoCo threshold gate` — JaCoCo gate.
- `500e123 Story 6.9: regenerate frontend OpenAPI client to match committed openapi.json snapshot` — OpenAPI regeneration discipline; when Task 6 + Task 10 regenerate the openapi.json, the frontend client must be regenerated too (or CI fails). Run `npm run generate:openapi` (or the project's equivalent) in `deliveryline-frontend/` AFTER backend openapi.json changes land.
- No Co-Authored-By trailer (per project memory).

Story 2.9's commit shape (when it merges) will be the closest reference for 2.10. Look for `Story 2.9:` commits in main and study the commit boundary (likely one or two commits: command extension + service + tests; or atomic single commit).

### Review Findings

- [x] [Review][Patch] Pin reject-spec replays to `Investigating` instead of returning the run's live state from the shared replay path [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:409]
- [x] [Review][Patch] Make spec rejection loop-count reads atomic so concurrent rejections cannot observe a later transaction's increment and raise escalation too early [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapter.java:89]
- [x] [Review][Patch] Distinguish `markEscalationOnce()` returning `0` because the marker is already set from `0` because the workflow run row no longer exists [deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java:388]
- [x] [Review][Patch] Regenerate the committed OpenAPI snapshot and generated frontend client so the new `RejectSpecRequest` and workflow detail/summary fields are reflected in the published contract [deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RejectSpecRequest.java:28]
- [x] [Review][Patch] Add the reject-spec coverage the story claims is complete: tagged-feedback validation plus reject replay/conflict contract tests are still deferred or unimplemented [deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceRejectSpecTest.java:62]
- [x] [Review][Patch] Extend the workflow-event detail allow-list so CLI history can surface the new reject-spec audit metadata instead of stripping it [deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java:48]
- [x] [Review][Patch] Preserve CLI schema-version compatibility instead of changing the `workflow-status.v1` contract in place with new required fields [deliveryline-backend/src/main/resources/schemas/cli/workflow-status.v1.schema.json:17]

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.10](_bmad-output/planning-artifacts/epics.md#L1091-L1108)
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.11](_bmad-output/planning-artifacts/epics.md#L1110-L1128) — clarifications table (referenced by Trap T1; story 2.11 still owns clarifications)
- [Source: _bmad-output/planning-artifacts/prd.md#FR9](_bmad-output/planning-artifacts/prd.md#L641) — structured rejection feedback
- [Source: _bmad-output/planning-artifacts/prd.md#FR13](_bmad-output/planning-artifacts/prd.md#L645) — exposed escalation
- [Source: _bmad-output/planning-artifacts/prd.md#AR34a](_bmad-output/planning-artifacts/prd.md#L79) — tagged rework taxonomy
- [Source: _bmad-output/planning-artifacts/architecture.md#Approval checkpoints contract](_bmad-output/planning-artifacts/architecture.md#L81)
- [Source: _bmad-output/planning-artifacts/architecture.md#State-event atomicity](_bmad-output/planning-artifacts/architecture.md#L300-L302)
- [Source: deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql#L167-L204](deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql) — `approvals` table with paired-decision CHECK
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java](deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java) — story 2.9 writer to extend
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalSnapshot.java#L65-L76](deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalSnapshot.java) — `DECISION_REJECTED` + paired-CHECK invariant in the compact constructor
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/approval/spi/ApprovalWritePort.java](deliveryline-backend/src/main/java/org/dradgo/application/approval/spi/ApprovalWritePort.java) — story 2.9 SPI to reuse
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapter.java](deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ApprovalWritePersistenceAdapter.java) — story 2.9 writer adapter (reused without modification)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/RejectSpecCommand.java](deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/RejectSpecCommand.java) — record to extend in Task 6
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java#L100-L102](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java) — `rejectSpec` entry point
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java#L172-L191](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java) — `rejectSpecInternal` body to refactor in Task 9
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/idempotency/WorkflowCommandFingerprintFactory.java#L36-L45](deliveryline-backend/src/main/java/org/dradgo/application/idempotency/WorkflowCommandFingerprintFactory.java) — `RejectSpecCommand` fingerprint branch to extend
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java#L48-L54](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java) — `WAITING_FOR_SPEC_APPROVAL → INVESTIGATING` already permitted
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/ApprovalReviewerRoleResolver.java](deliveryline-backend/src/main/java/org/dradgo/application/workflow/ApprovalReviewerRoleResolver.java) — reused for `rejectSpec` reviewerRole defaulting
- [Source: deliveryline-backend/src/main/resources/application.yml#L27-L33](deliveryline-backend/src/main/resources/application.yml) — `deliveryline.approval.default-reviewer-role` example; this story adds `deliveryline.workflow.spec-rejection-escalation-threshold`
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java#L9](deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java) — `APPROVAL_REJECTED` already registered; Task 8 adds `ESCALATION_REQUIRED`
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java#L10](deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java) — `REJECT_SPEC` registered; `CLEAR_ESCALATION_MARKER` at L16
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RejectSpecRequest.java](deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RejectSpecRequest.java) — REST DTO to extend in Task 6
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java#L217-L238](deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java) — REST endpoint to extend in Task 6
- [Source: deliveryline-backend/src/test/resources/fixture-event-streams/spec-rejection-and-resubmit.json](deliveryline-backend/src/test/resources/fixture-event-streams/spec-rejection-and-resubmit.json) — fixture event stream covering rejection (no escalation today; story 1.23 fixture)
- [Source: _bmad-output/implementation-artifacts/2-9-backend-approval-service-core-approve-with-version-binding.md](_bmad-output/implementation-artifacts/2-9-backend-approval-service-core-approve-with-version-binding.md) — sibling writer story (template for this one)
- [Source: _bmad-output/implementation-artifacts/2-8-backend-specification-artifact-model-and-spec-stage-context-bundle.md](_bmad-output/implementation-artifacts/2-8-backend-specification-artifact-model-and-spec-stage-context-bundle.md) — read-side foundations

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

n/a (no debug log emitted during this session)

### Completion Notes List

- 2026-05-24 review patch batch applied after `bmad-code-review`: reject-spec replay now pins `Investigating`, loop-count reads are atomic, missing-run vs already-marked escalation states are distinguished, CLI history exposes the new audit fields, and the committed OpenAPI snapshot plus generated frontend schema were brought up to date.
- Focused verification passed in the reactor with Docker-backed contract coverage: `./mvnw.cmd -pl deliveryline-backend -am "-Dfrontend-maven-plugin.skip=true" "-Dtest=WorkflowCommandServiceContractTest,WorkflowInspectionServiceTest,WorkflowCliJsonSchemaContractTest,WorkflowEventDetailKeysContractTest,ApprovalServiceRejectSpecTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> `Tests run: 51, Failures: 0, Errors: 0, Skipped: 0`.

- Open Questions resolved per recommendations:
  - **OQ-1 (Migration sequencing):** V7__add_spec_rejection_loop_columns.sql ships in THIS story. Story 2.11 renumbers its clarifications migration when it lands.
  - **OQ-2 (Fingerprint factory):** `reasonText` removed from `RejectSpecCommand` fingerprint; `reviewerRole` + `taggedFeedback.value()` added — symmetric with `ApproveSpecCommand` per trap T6.
  - **OQ-3 (Eligibility check):** Skipped for `rejectSpec` — rejecting a stale-but-unavailable artifact is a valid PM decision. Only version-binding runs.
- All 12 implementation tasks complete at the code/structure level. Remaining follow-ups are non-blocking.
- `ApprovalService` constructor widened from 7→9 args (adds `WorkflowRunRejectionLoopPort` + `SpecRejectionEscalationThresholdProvider`). The visible-for-tests Clock constructor is now 10-arg. `ApprovalServiceApproveSpecTest` setUp updated to inject mock loop port + threshold provider with default=3.
- `WorkflowRunSnapshot` record widened from 4→6 fields (`specRejectionLoopCount`, `escalationMarkerSet`). All 21 test callsites updated.
- `RejectSpecCommand` record widened from 9→11 fields (`reviewerRole`, `taggedFeedback`, `reasonText` cap 512→1024). All 4 callsites updated (REST controller, foundation contract, command-type test, command-service contract test).
- Defense-in-depth guards mirrored from `approveSpec` into `rejectSpec`: `artifactType==SPEC` + `artifact.workflowRunId()==command.workflowRunId()` checks before version-binding.
- Counter increment + escalation marker flip use `@Modifying` JPQL UPDATEs on `WorkflowRunRepository`; idempotency at the row level via `WHERE escalation_marker_set = false` guard.
- CLI JSON status output extended with `specRejectionLoopCount` + `escalationMarker`. Both v1 and v2 `workflow-status` JSON schemas updated. Human-readable text-format `status` not extended (deferred to story 1.15 follow-up per scope guardrail).
- `WorkflowEventType.ESCALATION_REQUIRED` wire value `escalation.required` added to the registry; fixture `workflow-event-types.fixture.json` + schema `workflow-events-response.schema.json` enums updated.
- `RejectionTaxonomy` registry enum added to `domain/registry/`; wired through `PersistedRegistryValues.approvalRejectionTaxonomy(...)`.
- `application.yml` documents the new `deliveryline.workflow.spec-rejection-escalation-threshold: 3` property.

### Deferrals (non-blocking follow-ups)

1. **`mvn verify` on WSL2 Ubuntu native** — per project memory, Testcontainers backend tests cannot run from Windows. The user MUST reproduce the Linux CI shape on WSL2 before this story can move from `review → done`. JaCoCo gate (LINE 81.33% / BRANCH 62.74%, story 2.32) must hold.
2. **OpenAPI snapshot regeneration** — `RejectSpecRequest` widened with `reviewerRole` + `taggedFeedback`; `WorkflowSummaryResponse` + `WorkflowDetailResponse` widened with `specRejectionLoopCount` + `escalationMarker`. The committed `openapi.json` snapshot needs regeneration (`mvn -pl deliveryline-backend ...` per project script). CI drift check (story 1.21 AC6) will fail without it.
3. **Frontend OpenAPI client regeneration** — after the openapi.json regen lands, run `npm run generate:openapi` (or project equivalent) in `deliveryline-frontend/`.
4. **CLI reject command** — story spec mentions "add `--tagged-feedback` (required) + `--reviewer-role` (optional) to the existing CLI reject path" — but the codebase has NO existing CLI reject verb at `adapters/cli/`. Deferred until a CLI reject command lands (likely Epic 2 follow-up; the existing REST surface remains the only path).
5. **Testcontainers persistence-adapter test** for `WorkflowRunRejectionLoopPersistenceAdapter` — TODO. The two new `@Modifying` queries are pinned by the focused `ApprovalServiceRejectSpecTest` (mock-level) but a real-Postgres slice should land in a code-review follow-up batch.
6. **Spring-slice contract test** `ApprovalServiceRejectSpecContractTest` covering AC9(h) idempotent replay + AC9(i) idempotency-key conflict end-to-end — deferred to follow-up; existing `WorkflowCommandServiceContractTest.rejectSpecTransitionsWaitingForSpecApprovalToInvestigating` exercises the happy path through the executeIdempotent pipeline.
7. **`ArchUnit` boundary assertion** "ApprovalService.rejectSpec must not be `@Transactional(propagation=REQUIRES_NEW)`" — the existing approveSpec rule (`APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL`) covers package-boundary; the propagation-shape pin is a code-review follow-up.

### File List

**Added:**
- `deliveryline-backend/src/main/resources/db/migration/V7__add_spec_rejection_loop_columns.sql`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RejectionTaxonomy.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunRejectionLoopPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/SpecRejectionEscalationThresholdProvider.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceRejectSpecTest.java`

**Modified (production):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java` (+ ESCALATION_REQUIRED)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java` (+ approvalRejectionTaxonomy helper)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowRunEntity.java` (+ two columns + getters / package setters)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunSnapshot.java` (4→6 fields)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/WorkflowRunEntityMapper.java` (populate new fields)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java` (+ two @Modifying queries)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapter.java` (implements WorkflowRunRejectionLoopPort)
- `deliveryline-backend/src/main/java/org/dradgo/application/idempotency/WorkflowCommandFingerprintFactory.java` (RejectSpecCommand branch: remove reasonText, add reviewerRole + taggedFeedback)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/RejectSpecCommand.java` (9→11 fields)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RejectSpecRequest.java` (+ reviewerRole + taggedFeedback, reasonText cap 512→1024)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (rejectSpec plumbing via ApprovalReviewerRoleResolver)
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java` (+ rejectSpec method + new helpers; constructor widened)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java` (rejectSpecInternal now delegates to ApprovalService.rejectSpec)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (WorkflowStatusView + WorkflowRunSummaryView: + specRejectionLoopCount + escalationMarker; populated from snapshot)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowSummaryResponse.java` (+ two fields)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowDetailResponse.java` (+ two fields)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java` (status JSON: + specRejectionLoopCount + escalationMarker)
- `deliveryline-backend/src/main/resources/application.yml` (+ deliveryline.workflow.spec-rejection-escalation-threshold)
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-status.v1.schema.json` (+ two fields, required + properties)
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-status.v2.schema.json` (+ two fields, required + properties)

**Modified (tests):**
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` (+ V7 column + CHECK assertions)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/spi/WorkflowRunSnapshotTest.java` (constructor signature update)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceTest.java` (10 snapshot callsites + new args)
- `deliveryline-backend/src/test/java/org/dradgo/application/recovery/RecoveryServiceUnitTest.java` (7 snapshot callsites)
- `deliveryline-backend/src/test/java/org/dradgo/application/recovery/RecoveryLoggingContractTest.java` (3 snapshot callsites)
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceApproveSpecTest.java` (ApprovalService constructor 7→9 args)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceContractTest.java` (RejectSpecCommand 9→11 args)
- `deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java` (RejectSpecCommand 9→11 args)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/commands/WorkflowCommandTypeTest.java` (RejectSpecCommand 9→11 args)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandOutputsTextTest.java` (WorkflowStatusView 14→16 args, 3 callsites)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliJsonSchemaContractTest.java` (WorkflowStatusView 14→16 args, 3 callsites)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsStatusHistoryTest.java` (WorkflowStatusView 14→16 args, 3 callsites)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsContextBundleFlagTest.java` (WorkflowStatusView 14→16 args, 2 callsites)
- `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json` (+ escalation.required)
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` (+ escalation.required enum value)

### Change Log

| Date       | Change                                                                                                       |
|------------|--------------------------------------------------------------------------------------------------------------|
| 2026-05-24 | Story 2.10 dev-story implementation: 12 tasks complete at code level; status `ready-for-dev → review`.      |
| 2026-05-24 | New V7 Flyway migration; new `RejectionTaxonomy` enum; new `WorkflowRunRejectionLoopPort` SPI + adapter.    |
| 2026-05-24 | `ApprovalService.rejectSpec(...)` added (mirror of `approveSpec` + counter increment + escalation marker).  |
| 2026-05-24 | `WorkflowCommandService.rejectSpecInternal` refactored to delegate to ApprovalService (trap T2 resolved).   |
| 2026-05-24 | Inspection surface (`getStatus` + `listRuns`) widened with `specRejectionLoopCount` + `escalationMarker`.   |
| 2026-05-24 | REST DTOs `RejectSpecRequest` / `WorkflowSummaryResponse` / `WorkflowDetailResponse` widened to match.      |
| 2026-05-24 | CLI status JSON output extended + v1/v2 schemas updated. CLI text rendering deferred to 1.15 follow-up.     |
| 2026-05-24 | `OQ-1` / `OQ-2` / `OQ-3` resolved per recommendations; surfaced in Completion Notes for PR sign-off.        |
| 2026-05-24 | Code-review patch batch applied and verified; story status `review -> done`.                                 |
