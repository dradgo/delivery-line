# Story 2.12: Backend — Visible Incorporation Lifecycle States + Event Wiring

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager,
I want explicit lifecycle wiring for clarifications (`submitted → answered → accepted → incorporated`, with `superseded` and `rejected_invalid` as terminal alternates), with each transition emitting a `clarification.*` workflow event and a make-or-break contract test forbidding silent disappearance of any clarification answer,
So that the visible-incorporation refinement holds end-to-end: PMs always see whether their input was accepted, applied to the active spec context, or set aside — never an answered question that vanishes without a visible workflow effect.

## Acceptance Criteria

1. **Given** a new `ClarificationLifecycleService` in `application.clarification` (sibling of the existing `ClarificationService` from story 2.11), **Then** it owns the four lifecycle transitions and exposes:
   - `markAccepted(String clarificationPublicId, ActorContext accepterActor) → ClarificationLifecycleResult`
   - `markIncorporated(String clarificationPublicId, String newSpecArtifactPublicId, String incorporationEventPublicId) → ClarificationLifecycleResult`
   - `markSuperseded(String clarificationPublicId, String supersededBySpecArtifactPublicId, String reason) → ClarificationLifecycleResult`
   - `markRejectedInvalid(String clarificationPublicId, String reason, ActorContext rejecterActor) → ClarificationLifecycleResult`
   
   Mirror story 2.11's `ClarificationService` constructor shape, Two-constructor Clock-injection pattern, and Javadoc style. **No** `@Transactional` annotation on the public methods — they participate in an outer caller-supplied transaction (mirror story 2.11 trap T7 / story 2.10 trap T4); a new ArchUnit rule `CLARIFICATION_LIFECYCLE_SERVICE_LIVES_IN_APPLICATION_CLARIFICATION` (or widen the existing `CLARIFICATION_SERVICE_LIVES_IN_APPLICATION_CLARIFICATION` rule at `ArchitectureRuleCatalog:374`) asserts the boundary.

2. **Given** the `clarifications.status` column from story 2.11 V8 (allowed values `open|answered|accepted|incorporated|superseded|rejected_invalid` per `ck_clarifications_status`), **Then** the lifecycle transitions enforce the following state machine — illegal moves raise `DomainException(ILLEGAL_CLARIFICATION_TRANSITION, …, details={clarificationId, currentStatus, attemptedTransition})`:
   - `open → answered` — already shipped by story 2.11 (`ClarificationService.submitAnswer`); no new path here.
   - `answered → accepted` (via `markAccepted`).
   - `answered → rejected_invalid` (via `markRejectedInvalid`).
   - `accepted → incorporated` (via `markIncorporated`).
   - `accepted → superseded` (via `markSuperseded`).
   - `incorporated`, `superseded`, `rejected_invalid` are terminal — any further transition (including re-issuing the same `mark*` call) raises `ILLEGAL_CLARIFICATION_TRANSITION` (idempotent replay is handled by the outer `WorkflowCommandService.executeIdempotent` pipeline once Epic 3 wires the orchestrator — see Trap T8). Note: story 2.11's `Clarification.isTerminal()` helper already includes `superseded` per the existing record invariant — story 2.12 retains that contract.

3. **Given** each lifecycle transition, **Then** the corresponding `workflow_events` row is appended in the **same transaction** via `WorkflowEventWritePort.append(...)`:
   - `markAccepted` → `CLARIFICATION_ACCEPTED` (registry value `"clarification.accepted"` — **already registered** at `WorkflowEventType:24`; do NOT re-add).
   - `markIncorporated` → `CLARIFICATION_INCORPORATED` (already registered at `WorkflowEventType:25`).
   - `markSuperseded` → `CLARIFICATION_SUPERSEDED` (already registered at `WorkflowEventType:26`).
   - `markRejectedInvalid` → `CLARIFICATION_REJECTED_INVALID` (already registered at `WorkflowEventType:27`).
   
   Each event's `details` map carries (allow-listed via `WorkflowEventDetailKeys`): `clarificationId`, `questionId`, `artifactId`, `artifactVersion` (the originally-pinned spec version from the clarification row), plus per-event keys: incorporation carries `incorporatedIntoArtifactId` + `incorporationEventId`; superseded carries `supersededByArtifactId` + `noEffectReason`; rejected-invalid carries `noEffectReason`. The drift test (story 1.4 → `RegistryContractTest` / `WorkflowEventDetailKeysContractTest`) is updated to pin the new detail keys against the allow-list (story 2.11 review trap T7 pattern).

4. **Given** automatic incorporation detection on new spec versions, **When** a new spec artifact version is created (via `ArtifactOperationService.newVersion(parentArtifactId, payloadRef, actor)` per story 2.8 AC6) **and** there is at least one `accepted` clarification pinned to a prior version of the same spec lineage, **Then** a new `ClarificationLifecycleOrchestrator` (see Task 4) is invoked from the spec-generation flow (today: a deterministic local stub; Epic 3 wires the runner broker integration). For each `accepted` clarification:
   - If the new spec content acknowledges the clarification's `questionId` (the orchestrator's `acknowledgesQuestion(newSpecPayloadRef, questionId)` returns `true`), call `markIncorporated(clarificationId, newSpecArtifactPublicId, incorporationEventPublicId)`.
   - If the new spec content does NOT acknowledge the `questionId`, call `markSuperseded(clarificationId, newSpecArtifactPublicId, "clarification_not_addressed")`.
   - **Neither call may be skipped silently** — the orchestrator returns a `LifecycleSweepResult` enumerating every clarification it considered and its decision; an integration test asserts that the count of considered clarifications equals the count of `accepted`-status rows for the spec lineage at sweep time.
   
   The orchestrator's `acknowledgesQuestion(...)` is **stub-driven for 2.12** — it uses a deterministic substring scan (`payloadRef contents contain the questionId verbatim`) sufficient for fixture-event-stream test data; Epic 3 will replace the implementation with the runner's emitted `clarification_acknowledgements` block per the runner-contracts schema (out of scope here). Document the seam in the orchestrator's Javadoc — Trap T6.

5. **Given** the make-or-break contract test, **Then** a new `ClarificationVisibleIncorporationContractTest` (in `deliveryline-backend/src/test/java/org/dradgo/contract/`) walks the event stream of every fixture in `deliveryline-backend/src/test/resources/fixture-event-streams/` and asserts: for every `clarification.answered` event in a workflow run, there must subsequently appear (within the same run's event sequence) **either**:
   - a `clarification.accepted` event for the same `clarificationId` **followed by** one of `clarification.incorporated`, `clarification.superseded`, `clarification.rejectedInvalid` (in any subsequent position — not necessarily adjacent), **OR**
   - a `clarification.noEffectReason` event (registry value `"clarification.noEffectReason"` already registered at `WorkflowEventType:28` — used when the spec-generation orchestrator cannot make a decision and explicitly records that no workflow change will occur; details carry `clarificationId`, `noEffectReason`, optional `triggeringEventId`).
   
   **Silent disappearance is a contract violation** — a `clarification.answered` event with no follow-up chain fails the test. Test is wired into the `foundation-gate` Maven profile so CI catches regressions before merging.

6. **Given** UX-DR11 visible-incorporation lifecycle, **Then** `WorkflowInspectionService.getClarificationStatus(String clarificationPublicId) → ClarificationStatusView` is added next to the existing `getClarifications`/`getClarificationsForArtifact` methods (story 2.11). `ClarificationStatusView` is a new record carrying:
   ```java
   public record ClarificationStatusView(
       String clarificationId,
       String workflowRunId,
       String artifactId,
       int artifactVersion,
       String questionId,
       String questionText,
       String status,             // one of the six Clarification.STATUS_* values
       String answerText,         // nullable
       String answeredByActor,    // nullable
       String answeredByActorType,// nullable
       OffsetDateTime answeredAt,         // nullable — story 2.11 column
       OffsetDateTime acceptedAt,         // nullable — V9 new column
       OffsetDateTime incorporatedAt,     // nullable — V9 new column
       String incorporatedIntoArtifactId, // nullable — derived from incorporation_event_id → event details
       String supersededByArtifactId,     // nullable — V9 new column
       String noEffectReason,             // nullable — V9 new column
       OffsetDateTime createdAt)
   ```
   `getClarificationStatus` returns the view for the single clarification (or throws `CLARIFICATION_NOT_FOUND` when missing or in an archived row — mirror story 2.11 cross-run guard semantics: callers MUST supply a `workflowRunPublicId` they expect the row to belong to so the cross-run leak guard from story 2.11 trap T6 applies — see Trap T11). UI Clarification Region (story 2.18) consumes this for the per-question lifecycle indicator.

7. **Given** the inspection method differentiation, **Then** the UI (story 2.18) can distinguish `answered` (received, pending acceptance) from `accepted` (received, queued for spec rebuild) from `incorporated` (visibly applied to active workflow context) by reading the `status` enum + the three nullable timestamps + the `incorporatedIntoArtifactId` / `supersededByArtifactId` / `noEffectReason` fields. The contract test `WorkflowInspectionServiceClarificationStatusTest` (new) asserts each status surfaces the correct combination of nullable fields (e.g., `incorporated` MUST have `incorporatedAt != null` and `incorporatedIntoArtifactId != null`, MUST have `supersededByArtifactId == null` and `noEffectReason == null`).

8. **Given** fixture event stream extension (story 1.23 fixture pattern), **Then** at least **two** fixtures in `deliveryline-backend/src/test/resources/fixture-event-streams/` carry the full clarification lifecycle so the contract test from AC5 has positive AND negative coverage:
   - `clarification-incorporated-happy-path.{md,json}` — new fixture (a workflow run with one or two open clarifications, both submitted → answered → accepted → incorporated, with a `CLARIFICATION_ANSWERED` + `CLARIFICATION_ACCEPTED` + `ARTIFACT_VERSION_CREATED` + `CLARIFICATION_INCORPORATED` chain).
   - `clarification-superseded-and-rejected.{md,json}` — new fixture (a run where one clarification is `superseded` by a spec rebuild that did not acknowledge it, plus one `rejected_invalid` clarification with a `noEffectReason` event explaining why).
   
   These fixtures conform to `fixture-event-streams/schema/workflow-events-response.schema.json` (story 1.23) and validate against it in the existing `FixtureEventStreamSchemaConformanceTest` (grep first; story 1.23 introduced the conformance check).
   
   **Do NOT** modify the existing three fixtures (`happy-path-success`, `execution-failure-with-retry`, `spec-rejection-and-resubmit`) — they pin existing scenarios that other tests depend on (Trap T9). New fixtures are the additive surface.

9. **Given** terminal-state events trigger downstream readiness changes (an `accepted → incorporated` transition may unblock approval; an `accepted → superseded` keeps the workflow blocked until the next answer), **Then** `WorkflowInspectionService` exposes:
   - A new `getRunSummary(String workflowRunPublicId) → WorkflowRunDetailedSummaryView` method returning per-run aggregate state including `pendingClarifications: int` (count of clarifications NOT in `incorporated` or `rejected_invalid` — i.e. `open + answered + accepted + superseded`. **`superseded` IS counted as pending** because the workflow remains blocked until either a fresh accepted-incorporated path lands or the clarification is explicitly `rejected_invalid`'d — see Open Question OQ-2).
   - The existing `WorkflowRunSummaryView` (returned by `listRuns`) is **extended** with `int pendingClarifications` at the end of its parameter list (mirror story 2.10's `specRejectionLoopCount` / `escalationMarkerSet` extension — append at end so callers reading positionally don't break).
   
   Both surfaces query a new `ClarificationReadPort.countPendingByWorkflowRun(String workflowRunPublicId) → int` method (added to the existing port; mirror the `ApprovalReadPort` count-style additions). The count query MUST filter `archived_at IS NULL` (mirror the existing list-style methods in the port — story 2.11 trap baked-in archived-row contract).

10. **Given** the test suite, **Then** it covers (in addition to the contract test from AC5):
    - Each lifecycle transition (4 happy paths) — row mutated to new status, correct event appended with full detail map, timestamp column populated, transaction commits row + event atomically.
    - Each illegal-transition rejection (`open → accepted` direct, `open → incorporated` direct, `answered → incorporated` skipping `accepted`, `accepted → rejected_invalid` skipping `answered`, `incorporated → anything`, `superseded → anything`, `rejected_invalid → anything`, re-issuing the same transition on an already-transitioned row) — `ILLEGAL_CLARIFICATION_TRANSITION` raised, no row mutation, no event appended.
    - Cross-run-leak guard parity with story 2.11: `markAccepted(clarificationFromSiblingRun, ...)` raises `CLARIFICATION_NOT_FOUND` (does NOT leak existence — see Trap T11).
    - Automatic incorporation detection happy path (orchestrator sweeps post-`newVersion`, calls `markIncorporated` for clarifications whose `questionId` appears in the new spec payload bytes, calls `markSuperseded` for those whose `questionId` does NOT appear).
    - Automatic-superseded marking when the spec rebuild does not acknowledge a clarification — `noEffectReason = "clarification_not_addressed"`, event details correctly populated.
    - The make-or-break contract test from AC5 (positive: all four fixtures pass; negative: a synthetic fixture with a `clarification.answered` event lacking follow-up fails as expected — assert via `assertThrows(AssertionError, ...)` wrapping the contract test against the negative fixture).
    - `WorkflowInspectionService.getClarificationStatus(...)` returns the right `nullable` field combination for each of the six statuses (parametric test).
    - `getRunSummary(...)` and the extended `listRuns` `pendingClarifications` count correctly excludes terminal `incorporated`/`rejected_invalid` rows (boundary: `superseded` IS counted pending; `archived_at NOT NULL` rows are excluded).
    - V9 migration applies cleanly on a fresh schema AND replay-safe on a post-V8 schema (`FlywaySchemaContractTest` extension).
    - Registry drift pinned (`ILLEGAL_CLARIFICATION_TRANSITION` in `DomainErrorCode` + `ProblemDetailsCatalog` + drift tests; the four `CLARIFICATION_*` event types already pinned by story 2.11 — verify the contract test now sees usages, not just registration).
    - ArchUnit boundary rule for `ClarificationLifecycleService` (Task 6).
    - JaCoCo gate (story 2.32, LINE 81.33% / BRANCH 62.74%) held on focused + full backend verify.

**Scope guardrails:**

- **Out of scope for 2.12:** REST mutation endpoint for lifecycle transitions — story 2.13 owns it (current plan: story 2.13 ships `POST /api/v1/workflows/{workflowRunId}/clarifications/{clarificationId}/answer` and a follow-up sub-story or extension owns operator-facing `accept`/`mark-rejected-invalid` mutations; **PM-facing lifecycle transitions are automatic via the orchestrator and are NOT exposed as REST verbs in MVP** — see Trap T10). The four `CLARIFICATION_*` error codes added in story 2.11 cover answer-time errors; `ILLEGAL_CLARIFICATION_TRANSITION` is a new code added here.
- **Out of scope:** UI Clarification Region — story 2.18 (consumes `getClarificationStatus`).
- **Out of scope:** UI Approval/Decision Bar gating on `pendingClarifications` — story 2.14 reads the count from `WorkflowInspectionService` to gate `approve_spec` in the allowed-actions endpoint; story 2.12 ships the read surface only.
- **Out of scope:** runner-contracts schema additions — Epic 3 ships the `clarification_acknowledgements` block in the runner result schema; this story's `ClarificationLifecycleOrchestrator.acknowledgesQuestion(...)` uses a deterministic local stub (substring scan over the spec payload bytes) sufficient for fixture-driven testing.
- **Out of scope:** CLI verb — no `deliveryline clarification accept|incorporate|supersede|reject-invalid` commands in 2.12. Future operator-action surface (likely Epic 4 recovery / reconciliation tooling) may add them; for MVP the lifecycle is orchestrator-driven.
- **Out of scope:** PM manual override — a PM cannot manually mark a clarification `accepted` in MVP (the implicit acceptance happens when the next spec generation flow incorporates the answer; see OQ-3). If reviewer feedback wants a manual-accept admin path, surface OQ-3 in the PR description; default disposition is **defer to future operator-action story**.
- **Out of scope:** widening `WorkflowStatusView` (the existing main status surface for `deliveryline workflow status`) with a `clarifications` block. The CLI's `--include-clarifications` flag is deferred per story 2.11's scope note; story 2.12 exposes the inspection surface but does not plumb it through the CLI.

## Tasks / Subtasks

- [x] **Task 1: New Flyway migration `V9__add_clarification_lifecycle_columns.sql`** (AC: 6, 10)
  - [ ] Create `deliveryline-backend/src/main/resources/db/migration/V9__add_clarification_lifecycle_columns.sql`. Leading comment must document WHY this is V9 (V8 from story 2.11 shipped the base `clarifications` table; story 2.12 layers the lifecycle metadata columns on top so the base schema remains migration-stable for any external consumer reading the V8 shape).
  - [ ] Adds five columns to `clarifications`:
    ```sql
    alter table clarifications add column accepted_at timestamptz null;
    alter table clarifications add column incorporated_at timestamptz null;
    alter table clarifications add column superseded_by_artifact_id bigint null;
    alter table clarifications add column superseded_by_artifact_version integer null;
    alter table clarifications add column no_effect_reason text null;

    alter table clarifications
      add constraint fk_clarifications_superseded_by_artifact
      foreign key (superseded_by_artifact_id, superseded_by_artifact_version)
      references artifacts (id, version) on delete restrict on update cascade;

    -- AC2: status-derivable field-presence invariant. Mirror story 2.11's
    -- ck_clarifications_answered_fields_paired pattern. Both column pairs
    -- (superseded_by_artifact_id, superseded_by_artifact_version) must travel
    -- together — partial NULLs are illegal regardless of status.
    alter table clarifications
      add constraint ck_clarifications_supersedes_pair check (
        (superseded_by_artifact_id is null) = (superseded_by_artifact_version is null)
      );

    alter table clarifications
      add constraint ck_clarifications_status_fields_paired check (
        case status
          when 'open'             then accepted_at is null and incorporated_at is null and superseded_by_artifact_id is null and no_effect_reason is null
          when 'answered'         then accepted_at is null and incorporated_at is null and superseded_by_artifact_id is null and no_effect_reason is null
          when 'accepted'         then accepted_at is not null and incorporated_at is null and superseded_by_artifact_id is null and no_effect_reason is null
          when 'incorporated'     then accepted_at is not null and incorporated_at is not null and incorporation_event_id is not null and superseded_by_artifact_id is null and no_effect_reason is null
          when 'superseded'       then accepted_at is not null and incorporated_at is null and superseded_by_artifact_id is not null and no_effect_reason is not null
          when 'rejected_invalid' then incorporated_at is null and superseded_by_artifact_id is null and no_effect_reason is not null
          else false
        end
      );

    create index idx_clarifications_pending_by_workflow_run
      on clarifications (workflow_run_id)
      where status not in ('incorporated', 'rejected_invalid') and archived_at is null;
    ```
  - [ ] The `no_effect_reason` column is `text` with no max length at the DDL — application-layer enum/regex validation in Task 6's lifecycle service (`clarification_not_addressed`, `pm_marked_invalid`, plus a free-form fallback capped at 1024 chars; see Trap T2).
  - [ ] The partial index `idx_clarifications_pending_by_workflow_run` accelerates Task 5's `countPendingByWorkflowRun` query (AC9) by filtering at the index level. Mirror the partial-index pattern at V1 `idx_workflow_runs_active` if present.
  - [ ] Extend `FlywaySchemaContractTest` with column + CHECK + FK + index assertions for V9 (mirror story 2.11 Task 1 sub-bullet pattern).

- [x] **Task 2: `DomainErrorCode.ILLEGAL_CLARIFICATION_TRANSITION` registry value + Problem Details mapping** (AC: 2, 10)
  - [ ] Edit `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`. Add `ILLEGAL_CLARIFICATION_TRANSITION("ILLEGAL_CLARIFICATION_TRANSITION")` in alphabetical-ish order matching existing groupings (the three story-2.11 `CLARIFICATION_*` codes are already there — slot this beside them).
  - [ ] Extend `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` with the new code: `ILLEGAL_CLARIFICATION_TRANSITION → 409, retryable=false` (mirror `ILLEGAL_TRANSITION` / `CLARIFICATION_TERMINAL_STATE` patterns).
  - [ ] Update `RegistryContractTest` / `DomainErrorCodeTest` (grep first) — they likely enumerate the values; add the new one.
  - [ ] Update `ProblemDetailsContractTest` (grep first) — it pins the wire-code list against the catalog.
  - [ ] Update the test-resource `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` with the new problem-type URI (mirror the additions story 2.11 made for the three `CLARIFICATION_*` codes; the placeholder JSON pins the OpenAPI Problem Details enum until story 2.13 regenerates the snapshot).

- [x] **Task 3: Extend `ClarificationWritePort` with four lifecycle transition methods** (AC: 1, 3, 6, 7)
  - [ ] Edit `deliveryline-backend/src/main/java/org/dradgo/application/clarification/spi/ClarificationWritePort.java`. Add four new methods + four corresponding payload records (sibling to the existing `NewClarification` / `RecordAnswer` records):
    ```java
    Clarification markAccepted(MarkAccepted markAccepted);
    Clarification markIncorporated(MarkIncorporated markIncorporated);
    Clarification markSuperseded(MarkSuperseded markSuperseded);
    Clarification markRejectedInvalid(MarkRejectedInvalid markRejectedInvalid);

    /** Update an existing 'answered' clarification to 'accepted'. Trap T8 — outer
     *  transaction is the boundary; the adapter is dumb-persistence. */
    record MarkAccepted(
        String clarificationPublicId,
        OffsetDateTime acceptedAt) {}

    /** Update an existing 'accepted' clarification to 'incorporated'. The
     *  incorporation_event_id is set to the FK of the matching workflow_events
     *  row written by the lifecycle service (resolved by publicId in the adapter).
     *  Mirror the foreign-key-by-publicId pattern in ApprovalEntityMapper. */
    record MarkIncorporated(
        String clarificationPublicId,
        String incorporatedIntoArtifactPublicId,
        int incorporatedIntoArtifactVersion,
        String incorporationEventPublicId,
        OffsetDateTime incorporatedAt) {}

    /** Update an existing 'accepted' clarification to 'superseded'. */
    record MarkSuperseded(
        String clarificationPublicId,
        String supersededByArtifactPublicId,
        int supersededByArtifactVersion,
        String noEffectReason,
        OffsetDateTime decidedAt) {}

    /** Update an existing 'answered' clarification to 'rejected_invalid'. */
    record MarkRejectedInvalid(
        String clarificationPublicId,
        String noEffectReason,
        OffsetDateTime decidedAt) {}
    ```
  - [ ] Update `ClarificationWritePersistenceAdapter` (`deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ClarificationWritePersistenceAdapter.java`) to implement the four new methods. Each:
    - Loads the entity by `findByPublicIdAndArchivedAtIsNull(publicId)` — throws `CLARIFICATION_NOT_FOUND` if missing or archived (mirror story 2.11 `recordAnswer`).
    - Sets the new fields, calls `saveAndFlush`.
    - Does NOT enforce status guards — the application-layer service (Task 6) does. The DB CHECK `ck_clarifications_status_fields_paired` is the defense-in-depth backstop (a buggy service would trip it at flush time).
    - `markIncorporated`: resolves `incorporation_event_id` via a `WorkflowEventRepository.findIdByPublicId(...)` lookup (add it if not present). The lifecycle service appends the event BEFORE calling `markIncorporated`, so the row exists.
  - [ ] Add `ClarificationReadPort.countPendingByWorkflowRun(String workflowRunPublicId) → int` to `application/clarification/spi/ClarificationReadPort.java` (AC9). Filter `archived_at IS NULL AND status NOT IN ('incorporated', 'rejected_invalid')`.
  - [ ] Implement in `ClarificationReadPersistenceAdapter` using a `@Query(value = "select count(*) from clarifications where workflow_run_id = (select id from workflow_runs where public_id = :runPublicId) and archived_at is null and status not in ('incorporated', 'rejected_invalid')", nativeQuery = true)` or a JPQL equivalent — native query is preferred to leverage the partial index from Task 1.

- [x] **Task 4: New `ClarificationLifecycleOrchestrator` + spec-rebuild integration seam** (AC: 4, 10)
  - [ ] Create `deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleOrchestrator.java`. Constructor injects `ClarificationReadPort`, `ClarificationLifecycleService` (Task 6), `ArtifactPayloadReader` (existing or new helper that reads spec payload bytes from `ArtifactStore` — grep `ArtifactPayloadReader` / `LocalArtifactStore` first; story 2.8 shipped a similar reader path for context-bundle composition).
  - [ ] Public API:
    ```java
    public LifecycleSweepResult sweepAfterSpecRebuild(
        String workflowRunPublicId,
        String newSpecArtifactPublicId,
        int newSpecArtifactVersion,
        ActorContext actor);

    public record LifecycleSweepResult(
        int consideredCount,
        List<ClarificationDecision> decisions) {}

    public record ClarificationDecision(
        String clarificationId,
        String questionId,
        Outcome outcome) {}

    public enum Outcome { INCORPORATED, SUPERSEDED, SKIPPED_NON_ACCEPTED }
    ```
  - [ ] Behavior:
    - Load every clarification for the workflow run via `ClarificationReadPort.listByWorkflowRunId` (filters `archived_at IS NULL` already).
    - Filter to `status == 'accepted'`.
    - For each, call `acknowledgesQuestion(newSpecArtifactPublicId, questionId)` — the stub implementation reads the spec payload bytes and tests `payloadBytes.contains(questionId.getBytes(UTF_8))` (case-sensitive — `questionId` is `^[A-Za-z0-9._-]{1,128}$` so case-folding is unnecessary; document Trap T6 in the Javadoc).
    - On `true`: invoke `clarificationLifecycleService.markIncorporated(clarification.publicId(), newSpecArtifactPublicId, eventPublicIdPlaceholder, actor)` — the service appends the event itself and obtains the persistent event id.
    - On `false`: invoke `clarificationLifecycleService.markSuperseded(clarification.publicId(), newSpecArtifactPublicId, "clarification_not_addressed", actor)`.
    - Return a `LifecycleSweepResult` enumerating every considered clarification.
  - [ ] Hook the sweep into `ArtifactOperationService.newVersion(parentArtifactId, payloadRef, actor)` (`deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactOperationService.java:450-470`) — AFTER the new version is persisted AND AFTER the `ARTIFACT_VERSION_CREATED` event is appended, AND only when `parent.artifactType() == SPEC` (Trap T5 — non-spec lineages have no clarifications to sweep). The sweep runs in the SAME outer transaction as `newVersion` so the row mutations + lifecycle events commit atomically. Document this hook in `ArtifactOperationService` Javadoc.
  - [ ] **Sequencing inside `ArtifactOperationService.newVersion`:** (1) persist new version + append `ARTIFACT_VERSION_CREATED` event (existing behavior); (2) sweep clarification lifecycle for the run if `parent.artifactType() == SPEC` AND there is at least one `accepted` clarification for the lineage. Order matters: incorporation events MUST appear AFTER the `ARTIFACT_VERSION_CREATED` event in the run's event stream so the make-or-break contract test (AC5) sees the chain in the right order.

- [x] **Task 5: Extend `WorkflowInspectionService` with `getClarificationStatus` + `getRunSummary` + extend `listRuns`** (AC: 6, 7, 9)
  - [ ] Edit `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`. Add:
    ```java
    @Transactional(readOnly = true)
    public ClarificationStatusView getClarificationStatus(
        String workflowRunPublicId, String clarificationPublicId) {
      PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
      PublicIdPrefixes.require(clarificationPublicId, PublicIdPrefixes.CLARIFICATION);
      String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
      try {
        log.info("getClarificationStatus entry workflowRunId={} clarificationId={}",
            workflowRunPublicId, clarificationPublicId);
        Clarification row = clarificationReadPort
            .findByPublicId(clarificationPublicId)
            .orElseThrow(() -> clarificationNotFound(workflowRunPublicId, clarificationPublicId, "missing"));
        if (!row.workflowRunId().equals(workflowRunPublicId)) {
          // Trap T11 cross-run guard — mirror story 2.11 trap T6 shape.
          throw clarificationNotFound(workflowRunPublicId, clarificationPublicId, "cross_run");
        }
        ClarificationStatusView view = toClarificationStatusView(row);
        log.info("getClarificationStatus success clarificationId={} status={}",
            clarificationPublicId, view.status());
        return view;
      } finally {
        MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
      }
    }

    @Transactional(readOnly = true)
    public WorkflowRunDetailedSummaryView getRunSummary(String workflowRunPublicId) {
      PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
      String priorMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
      try {
        WorkflowRunSnapshot run = workflowRunReadPort.findByPublicId(workflowRunPublicId)
            .orElseThrow(() -> runNotFound(workflowRunPublicId));
        int pending = clarificationReadPort.countPendingByWorkflowRun(workflowRunPublicId);
        Optional<WorkflowEventRecord> latest =
            workflowEventReadPort.findLatestByWorkflowRunPublicId(workflowRunPublicId);
        String ticketRef = integrationLinkService.findActiveLinkByWorkflowRun(workflowRunPublicId)
            .map(IntegrationLink::externalRef).orElse(null);
        return new WorkflowRunDetailedSummaryView(
            run.publicId(), run.currentState().value(), ticketRef,
            latest.map(WorkflowEventRecord::createdAt).orElse(null),
            latest.map(r -> r.eventType().value()).orElse(null),
            run.specRejectionLoopCount(), run.escalationMarkerSet(), pending);
      } finally {
        MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorMdc);
      }
    }
    ```
  - [ ] Add the `ClarificationStatusView` record + `WorkflowRunDetailedSummaryView` record near the existing view records (line 801+). The new detailed-summary record extends the existing `WorkflowRunSummaryView` shape by one field (`pendingClarifications: int`); for the **listRuns** call site, also append `pendingClarifications` at the END of `WorkflowRunSummaryView`'s field list (Trap T7 — positional callers don't break) AND wire the count through `listRuns` by calling `clarificationReadPort.countPendingByWorkflowRun(run.publicId())` per row. The N+1 implication is acceptable for MVP-scale list pages (typical PM review queue is < 50 rows); document the N+1 as a deliberate trade-off in the method's Javadoc — Task 10 OQ-4 surfaces a batch-query option if reviewer prefers.
  - [ ] Update existing `WorkflowRunSummaryView` consumers — there are likely 5-10 (REST controllers, CLI commands, tests). Grep `WorkflowRunSummaryView` first; update every site to handle the new field. Most call sites will just propagate it through.

- [x] **Task 6: New `ClarificationLifecycleService` with the four transitions** (AC: 1, 2, 3, 10)
  - [ ] Create `deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleService.java`. Mirror `ClarificationService` (story 2.11) constructor shape exactly + the two-constructor `Clock`-injection pattern. Inject: `ClarificationReadPort`, `ClarificationWritePort`, `WorkflowEventWritePort`, `ArtifactRecordPort` (for resolving the supersededBy / incorporatedInto artifact versions when only the publicId is supplied — see Task 4 stub orchestrator), `Clock`.
  - [ ] Implement `markAccepted`:
    ```java
    public ClarificationLifecycleResult markAccepted(
        String workflowRunPublicId, String clarificationPublicId, ActorContext actor) {
      Clarification row = loadAndGuardRun(workflowRunPublicId, clarificationPublicId);
      assertTransition(row, Clarification.STATUS_ANSWERED, Clarification.STATUS_ACCEPTED);
      OffsetDateTime acceptedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
      Clarification updated = clarificationWritePort.markAccepted(
          new MarkAccepted(clarificationPublicId, acceptedAt));
      appendLifecycleEvent(updated, WorkflowEventType.CLARIFICATION_ACCEPTED,
          actor, acceptedAt,
          /* extraDetails */ Map.of());
      return new ClarificationLifecycleResult(updated.publicId(), updated.workflowRunId(),
          updated.status(), acceptedAt);
    }
    ```
  - [ ] Implement `markIncorporated`:
    ```java
    public ClarificationLifecycleResult markIncorporated(
        String workflowRunPublicId, String clarificationPublicId,
        String newSpecArtifactPublicId, ActorContext actor) {
      Clarification row = loadAndGuardRun(workflowRunPublicId, clarificationPublicId);
      assertTransition(row, Clarification.STATUS_ACCEPTED, Clarification.STATUS_INCORPORATED);
      ArtifactRecordSnapshot newSpec = artifactRecordPort.findByPublicId(newSpecArtifactPublicId)
          .orElseThrow(() -> incorporationArtifactMissing(newSpecArtifactPublicId, clarificationPublicId));
      OffsetDateTime incorporatedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
      // Append event FIRST so the persistence adapter can look up incorporation_event_id by publicId.
      String incorporationEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("clarificationId", clarificationPublicId);
      details.put("questionId", row.questionId());
      details.put("artifactId", row.artifactId());                  // original spec version
      details.put("artifactVersion", row.artifactVersion());
      details.put("incorporatedIntoArtifactId", newSpecArtifactPublicId);
      details.put("incorporationEventId", incorporationEventPublicId);
      workflowEventWritePort.append(new WorkflowEventRecord(
          incorporationEventPublicId, workflowRunPublicId,
          WorkflowEventType.CLARIFICATION_INCORPORATED,
          null, null, actor.identity(), actor.actorType(),
          "clarification incorporated", null, false, incorporatedAt, details));
      // Then the row update with the FK.
      Clarification updated = clarificationWritePort.markIncorporated(new MarkIncorporated(
          clarificationPublicId, newSpecArtifactPublicId, newSpec.version(),
          incorporationEventPublicId, incorporatedAt));
      log.info("markIncorporated success clarificationId={} runId={} newSpec={}",
          clarificationPublicId, workflowRunPublicId, newSpecArtifactPublicId);
      return new ClarificationLifecycleResult(updated.publicId(), updated.workflowRunId(),
          updated.status(), incorporatedAt);
    }
    ```
  - [ ] **FK flush-ordering in `markIncorporated`** — Trap T13: the `clarifications.incorporation_event_id` FK references `workflow_events.id`. Hibernate batches inserts and flushes lazily; calling `clarificationWritePort.markIncorporated(...)` (which does `saveAndFlush`) immediately after `workflowEventWritePort.append(...)` will trigger the clarification UPDATE to flush BEFORE the event INSERT has been flushed, causing the FK to fail at the clarification flush. Mitigations (pick one — verify with adapter shape):
    - If `WorkflowEventWritePort.append(...)` already calls `EntityManager.flush()` internally (grep `flush()` in the adapter implementation first), no change needed — document the dependency in `markIncorporated` Javadoc.
    - Otherwise, add an `entityManager.flush()` call between the two ops in `ClarificationLifecycleService.markIncorporated`, OR introduce a sibling `WorkflowEventWritePort.appendAndFlush(...)` method.
    - DO NOT change the FK constraint to `DEFERRABLE INITIALLY DEFERRED` — keep PostgreSQL defaults; ordering is the application's responsibility.
    Pin this with an integration test (`ClarificationLifecycleServiceMarkIncorporatedFkOrderingIT`) that catches the FK violation if the ordering breaks under a future refactor.
  - [ ] Implement `markSuperseded(workflowRunPublicId, clarificationPublicId, supersededByArtifactPublicId, noEffectReason, actor)` — similar shape, `STATUS_ACCEPTED → STATUS_SUPERSEDED`, event `CLARIFICATION_SUPERSEDED`, details carry `supersededByArtifactId` + `noEffectReason`. Reason MUST be non-blank — Task 6 enforces (`noEffectReason == null || noEffectReason.isBlank() ⇒ IllegalArgumentException("noEffectReason required")`). Allowed reasons (enforce at the service layer per Trap T2 controlled vocabulary): `clarification_not_addressed`, `superseded_by_unrelated_rebuild`. No free-form fallback in MVP.
  - [ ] Implement `markRejectedInvalid(workflowRunPublicId, clarificationPublicId, noEffectReason, actor)` — `STATUS_ANSWERED → STATUS_REJECTED_INVALID`, event `CLARIFICATION_REJECTED_INVALID`, details carry `noEffectReason`.
  - [ ] Shared helper `assertTransition(Clarification row, String requiredCurrentStatus, String newStatus)`:
    ```java
    private static void assertTransition(Clarification row, String required, String target) {
      if (!required.equals(row.status())) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("clarificationId", row.publicId());
        details.put("currentStatus", row.status());
        details.put("attemptedTransition", required + " -> " + target);
        throw new DomainException(
            DomainErrorCode.ILLEGAL_CLARIFICATION_TRANSITION,
            "Illegal clarification transition: " + required + " -> " + target
                + " (current: " + row.status() + ")", details);
      }
    }
    ```
  - [ ] Shared helper `loadAndGuardRun(workflowRunPublicId, clarificationPublicId)` — load via read port, raise `CLARIFICATION_NOT_FOUND` if missing OR `clarification.workflowRunId() != workflowRunPublicId` (cross-run leak guard, Trap T11 — mirror story 2.11 trap T6 shape).
  - [ ] Create `deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleResult.java` — record carrying `clarificationId`, `workflowRunId`, `status`, `transitionedAt`. Sibling of `ClarificationResult` (story 2.11) and `ApprovalResult` (story 2.9).
  - [ ] Extend/widen the ArchUnit rule (`ArchitectureRuleCatalog:374 CLARIFICATION_SERVICE_LIVES_IN_APPLICATION_CLARIFICATION`) to also cover `ClarificationLifecycleService` and `ClarificationLifecycleOrchestrator`, OR add a sibling rule `CLARIFICATION_LIFECYCLE_LIVES_IN_APPLICATION_CLARIFICATION` (cleaner; same package). Update `ArchitectureBoundaryTest`.
  - [ ] Register the new detail keys (`incorporatedIntoArtifactId`, `incorporationEventId`, `supersededByArtifactId`, `noEffectReason`) in `WorkflowEventDetailKeys.java` — all FOUR are **allow-listed** (operator-visible in CLI history): the IDs are public references; `noEffectReason` is a controlled-vocabulary token (`clarification_not_addressed`, `superseded_by_unrelated_rebuild`, or a future taxonomy value), NOT free-form reviewer text — Trap T2. Extend `WorkflowEventDetailKeysContractTest` allow-list assertion.
  - [ ] Update `deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json` with the new properties under `events[].details` (mirror story 2.11's update for `clarificationId` / `questionId`). The schema is the authoritative wire-format for the CLI `workflow history` render path (story 1.15).

- [x] **Task 7: New fixture event streams** (AC: 8)
  - [ ] Create `deliveryline-backend/src/test/resources/fixture-event-streams/clarification-incorporated-happy-path.json` + matching `.md` doc:
    - One workflow run in state `WaitingForSpecApproval`.
    - Two clarifications (`clr_…1`, `clr_…2`) seeded as `open` then transitioned through `answered → accepted → incorporated`.
    - Event sequence:
      1. `workflow.created` / `workflow.stateChanged` (existing scaffolding) — match the `happy-path-success.json` opening.
      2. `clarification.answered` (clarification 1) with details `{ clarificationId, artifactId, artifactVersion, questionId, idempotencyKey }`.
      3. `clarification.answered` (clarification 2) similarly.
      4. `clarification.accepted` (clarification 1) — `accepterActor`, plus `clarificationId`, `questionId`, `artifactId`, `artifactVersion`.
      5. `clarification.accepted` (clarification 2).
      6. `artifact.versionCreated` (the v2 spec built from incorporating both clarifications).
      7. `clarification.incorporated` (clarification 1) — `incorporatedIntoArtifactId`, `incorporationEventId`.
      8. `clarification.incorporated` (clarification 2).
    - Validate against `fixture-event-streams/schema/workflow-events-response.schema.json` (story 1.23).
  - [ ] Create `deliveryline-backend/src/test/resources/fixture-event-streams/clarification-superseded-and-rejected.json` + matching `.md` doc:
    - One workflow run with three clarifications.
    - Clarification 1: `answered → accepted → superseded` (spec rebuilt without acknowledging it) — `noEffectReason = "clarification_not_addressed"`, `supersededByArtifactId` = the new spec artifact public id.
    - Clarification 2: `answered → rejected_invalid` — `noEffectReason = "pm_marked_invalid"` (NOTE: PM-driven `rejected_invalid` is a future Epic 4 operator-action path; for the fixture, the actor is `actorType=human` even though the orchestrator path is auto-only in MVP — see OQ-3).
    - Clarification 3: `answered` but no follow-up. **WAIT — this would violate the make-or-break contract test from AC5.** Resolve: add a `clarification.noEffectReason` event for clarification 3 with `noEffectReason = "spec_runner_skipped_question"` to satisfy the contract; OR omit clarification 3 entirely. **Recommended:** omit clarification 3 so the fixture stays a clean two-case demonstration. The negative fixture (a deliberately-malformed event stream that the contract test must reject) lives **inside the contract test itself** as an inline-constructed event list, NOT as a committed fixture under `fixture-event-streams/` (the fixture-conformance test would reject it as malformed before the contract test ever runs — Trap T9).
  - [ ] If existing `FixtureEventStreamSchemaConformanceTest` enumerates fixtures by glob, no test update is needed; otherwise add the two new filenames.

- [x] **Task 8: Make-or-break contract test** (AC: 5)
  - [ ] Create `deliveryline-backend/src/test/java/org/dradgo/contract/ClarificationVisibleIncorporationContractTest.java`. Test responsibilities:
    - For each fixture file under `deliveryline-backend/src/test/resources/fixture-event-streams/*.json`: parse the event stream, build a `clarificationId → List<event-type-in-order>` map.
    - For every `clr_…` in that map, assert the trailing sequence (after the first `clarification.answered`) matches one of:
      - `*answered*, …, clarification.accepted, …, clarification.incorporated`
      - `*answered*, …, clarification.accepted, …, clarification.superseded`
      - `*answered*, …, clarification.accepted, …, clarification.rejectedInvalid`
      - `*answered*, …, clarification.noEffectReason`
      - `*answered*, …, clarification.rejectedInvalid` (skipping accepted — `answered → rejected_invalid` is a valid direct transition per AC2)
    - **Forbidden:** a `clarification.answered` event with NO follow-up `accepted | rejectedInvalid | noEffectReason` event for the same `clarificationId`.
  - [ ] Add a negative-test method: inline-construct a malformed event list (one `clarification.answered` with no follow-up) and assert the contract test logic raises `AssertionError`. Use a private helper to share the assertion logic between the fixture-loop and the negative test so both paths exercise the same code.
  - [ ] Tag the test class with `@Tag("foundation-gate")` if the project uses tag-based gate routing (grep `@Tag\("foundation-gate"\)` first; story 1.23 introduced the tag).

- [x] **Task 9: Wire the orchestrator into `ArtifactOperationService.newVersion`** (AC: 4)
  - [ ] Edit `ArtifactOperationService.newVersion(parentArtifactId, payloadRef, actor)` (line 450-470). After the new version is persisted AND after the `ARTIFACT_VERSION_CREATED` event is appended, check `parent.artifactType()`. If `SPEC`:
    ```java
    LifecycleSweepResult sweepResult = clarificationLifecycleOrchestrator.sweepAfterSpecRebuild(
        run.publicId(), newArtifact.publicId(), newArtifact.version(), actor);
    log.info("newVersion clarification lifecycle sweep parentArtifactId={} consideredCount={} incorporated={} superseded={}",
        parentArtifactId,
        sweepResult.consideredCount(),
        sweepResult.decisions().stream().filter(d -> d.outcome() == INCORPORATED).count(),
        sweepResult.decisions().stream().filter(d -> d.outcome() == SUPERSEDED).count());
    ```
  - [ ] Constructor-shape change: `ArtifactOperationService` gains a `ClarificationLifecycleOrchestrator` dependency. Grep `new ArtifactOperationService(` first — there are several test callsites. Append the new arg at the END of the parameter list (Trap T7 — positional ordering matters).
  - [ ] Production wiring is Spring auto-injected (constructor-based DI; no `@Bean` config changes needed).
  - [ ] Add a happy-path integration test `ArtifactOperationServiceClarificationSweepIT.java` (Spring-slice + Testcontainers):
    - Seed a workflow run in `WaitingForSpecApproval` with two `accepted` clarifications (`clr_1.questionId="Q-AUTH-001"`, `clr_2.questionId="Q-AUTH-002"`).
    - Call `artifactOperationService.newVersion(parentSpecArtifactId, payloadRefPointingToContentThatMentions("Q-AUTH-001"), actor)`.
    - Assert: `clr_1.status == INCORPORATED`, `clr_2.status == SUPERSEDED` (noEffectReason=`clarification_not_addressed`).
    - Assert: two new lifecycle events appended after the `ARTIFACT_VERSION_CREATED` event, with correct details.

- [x] **Task 10: Test suite** (AC: 10)
  - [ ] **Unit tests** under `deliveryline-backend/src/test/java/org/dradgo/application/clarification/`:
    - `ClarificationLifecycleServiceMarkAcceptedTest.java` — Mockito. Cover happy path + illegal-transition rejections (`open → accepted`, `accepted → accepted`, `incorporated → accepted`, etc.) + cross-run leak + idempotency-key envelope (no idempotency at this layer — the outer pipeline owns it).
    - `ClarificationLifecycleServiceMarkIncorporatedTest.java` — happy path, requires `STATUS_ACCEPTED`, requires `newSpecArtifact` exists (raises a typed error if not — Trap T4 — `incorporationArtifactMissing` returns `DomainException(INTERNAL_ERROR, ...)` because the orchestrator is the only caller and supplies an artifact id it just persisted; missing implies a serious bug, not user error). Event-detail-map assertions on the appended `CLARIFICATION_INCORPORATED` event.
    - `ClarificationLifecycleServiceMarkSupersededTest.java` — happy path + `noEffectReason` validation (blank rejected; oversized rejected). Event-detail-map assertions.
    - `ClarificationLifecycleServiceMarkRejectedInvalidTest.java` — happy path + `STATUS_ANSWERED` required + `noEffectReason` validation.
    - `ClarificationLifecycleOrchestratorTest.java` — Mockito. Mock `ClarificationReadPort` + `ArtifactPayloadReader`. Cover: zero accepted clarifications → no calls; one accepted + payload mentions questionId → one `markIncorporated` call; one accepted + payload does NOT mention questionId → one `markSuperseded` call with `clarification_not_addressed`; mixed three accepted (one incorporated, one superseded, one with payload-read failure) → behavior on payload-read failure (currently: log WARN + mark superseded with `noEffectReason="payload_read_failed"`; alternative: raise — see OQ-5).
    - `ClarificationLifecycleServiceLoggingTest.java` (or as a section of the above) — Logback `ListAppender` asserting: INFO entry/exit on each `mark*`, WARN on `ILLEGAL_CLARIFICATION_TRANSITION`, WARN on `CLARIFICATION_NOT_FOUND` cross-run, ERROR on `INTERNAL_ERROR` from `markIncorporated` artifact-missing path. **MUST NOT log `noEffectReason` if it ever carries free-form text** — for MVP it's controlled vocabulary so logging is safe; document as Trap T2.
  - [ ] **Persistence-adapter tests** under `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/`:
    - `ClarificationWritePersistenceAdapterLifecycleTest.java` — Testcontainers Postgres. Cover each of the four `mark*` methods round-trip; assert `ck_clarifications_status_fields_paired` CHECK fires when feeding pathological inputs directly (defense-in-depth backstop).
    - Extend `ClarificationReadPersistenceAdapterTest.java` (or add a sibling) with `countPendingByWorkflowRun` cases: zero clarifications → 0; open/answered/accepted/superseded all count; incorporated/rejected_invalid don't count; archived rows excluded; cross-run isolation.
  - [ ] **Integration test:** `ArtifactOperationServiceClarificationSweepIT.java` (Task 9 sub-bullet) — Spring + Testcontainers end-to-end.
  - [ ] **Contract test:** `ClarificationVisibleIncorporationContractTest.java` (Task 8 sub-bullet).
  - [ ] **Inspection-service tests:** `WorkflowInspectionServiceClarificationStatusTest.java` covering each of the six status-field combinations.
  - [ ] Extend `WorkflowInspectionServiceTest.java` + `WorkflowInspectionServiceSpecTest.java` constructor invocations if `WorkflowInspectionService` gains a new dependency (it does NOT for this story — `ClarificationReadPort` already wired by story 2.11; the new `countPendingByWorkflowRun` rides on it).
  - [ ] **Migration test:** extend `FlywaySchemaContractTest` with V9 column/CHECK/FK/index assertions (mirror story 2.11 V8 pattern).
  - [ ] **Registry drift test:** `RegistryContractTest` + `DomainErrorCodeTest` extension for `ILLEGAL_CLARIFICATION_TRANSITION`. `ProblemDetailsContractTest` extension. `WorkflowEventDetailKeysContractTest` extension for the four new detail keys.
  - [ ] **Architecture test:** `ArchitectureBoundaryTest` extension for the new/widened ArchUnit rule (Task 6).
  - [ ] Focused Maven invocation:
    ```
    ./mvnw.cmd -pl deliveryline-backend -o -Dtest='ClarificationLifecycleService*Test,ClarificationLifecycleOrchestratorTest,ClarificationWritePersistenceAdapterLifecycleTest,ClarificationReadPersistenceAdapterTest,WorkflowInspectionServiceClarificationStatusTest,ClarificationVisibleIncorporationContractTest,ArtifactOperationServiceClarificationSweepIT,RegistryContractTest,ProblemDetailsContractTest,WorkflowEventDetailKeysContractTest,ArchitectureBoundaryTest,FlywaySchemaContractTest' -Dsurefire.failIfNoSpecifiedTests=false test
    ```
    Plus `./mvnw.cmd -pl deliveryline-backend verify` once before opening the PR — JaCoCo gate from story 2.32 (LINE 81.33% / BRANCH 62.74%) must hold. Reproduce on WSL2 Ubuntu native per project memory note before flipping `review → done`.

- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, event append), and every retry/replay/conflict/recovery branch.
  - [ ] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [ ] Levels:
    - `INFO` on each `ClarificationLifecycleService.mark*` entry (`workflowRunId`, `clarificationId`, target status, `actorIdentity`, `actorType`).
    - `INFO` on success (`clarificationId`, `workflowRunId`, new status, `transitionedAt`).
    - `WARN` on `ILLEGAL_CLARIFICATION_TRANSITION` (with `currentStatus`, `attemptedTransition`).
    - `WARN` on `CLARIFICATION_NOT_FOUND` (cross-run leak guard — `reason=missing|cross_run`).
    - `ERROR` on `INTERNAL_ERROR` (orchestrator-supplied artifact missing — shouldn't happen).
    - `INFO` summary line on `ClarificationLifecycleOrchestrator.sweepAfterSpecRebuild` (`consideredCount`, `incorporatedCount`, `supersededCount`).
    - `INFO` entry + success on `WorkflowInspectionService.getClarificationStatus` and `getRunSummary`.
  - [ ] Required context keys: `correlationId` (when supplied by outer pipeline), `workflowRunId`, `clarificationId`, `actorIdentity`, `actorType`. Open MDC scopes via `MdcKeys.beginScope/endScope`. No new MDC key required (clarificationId rides as a structured log parameter, mirror story 2.11).
  - [ ] **Forbidden in log output:** `answerText` / `questionText` / `priorAnswerText` from the read-projected row (per story 2.11 trap T12). `noEffectReason` is allowed (controlled vocabulary in MVP; revisit if a free-form admin path lands — surface in OQ-3 PR comment).
  - [ ] Add at least one assertion in `ClarificationLifecycleServiceLoggingTest` per branch (use Logback `ListAppender`).

## Dev Notes

### Foundations already in place (do NOT rebuild)

- **All four `clarification.*` lifecycle event types** — `CLARIFICATION_ACCEPTED`, `CLARIFICATION_INCORPORATED`, `CLARIFICATION_SUPERSEDED`, `CLARIFICATION_REJECTED_INVALID`, plus `CLARIFICATION_NO_EFFECT_REASON` for the make-or-break contract — **already registered** at `WorkflowEventType:24-28` (story 2.11 verified). Story 2.12 emits them; **zero registry additions for events**.
- **`Clarification` record** in `application/clarification/Clarification.java` — already exposes `STATUS_OPEN`/`STATUS_ANSWERED`/`STATUS_ACCEPTED`/`STATUS_INCORPORATED`/`STATUS_SUPERSEDED`/`STATUS_REJECTED_INVALID` constants, `isTerminal()`, `isAnswered()`, `isOpen()` helpers. The record's compact-constructor invariant (`(status == 'open') ⇔ (answer fields null)`) is the story 2.11 baseline; story 2.12 layers V9 columns on top so the compact constructor needs **no widening** — the new V9 fields (`acceptedAt`, `incorporatedAt`, `supersededByArtifactId`, `noEffectReason`) are surfaced via the new `ClarificationStatusView` rather than retro-fitted into `Clarification` (keeps the projection lean for read-port consumers that only need core identity + answer state; see Trap T1).
- **`ClarificationReadPort` / `ClarificationWritePort` / `ClarificationReadPersistenceAdapter` / `ClarificationWritePersistenceAdapter` / `ClarificationEntity` / `ClarificationEntityMapper` / `ClarificationRepository`** — entire `application.clarification` + `adapters.persistence` slice shipped by story 2.11. Story 2.12 **extends** the write port (Task 3) with four lifecycle transitions and the read port with `countPendingByWorkflowRun(...)`. Mirror every story 2.11 convention: archived-row filter, idempotency-conflict mapping at the adapter, ArchUnit boundary rule.
- **`WorkflowCommandService.executeIdempotent`** — the canonical idempotency-reserve / replay / complete pipeline. Story 2.12 does **NOT** route lifecycle transitions through `executeIdempotent` because the orchestrator (Task 4) is the sole caller in MVP and runs inside the outer `ArtifactOperationService.newVersion @Transactional` boundary; future operator-driven REST mutations (out of scope) would wire through `executeIdempotent` similar to `answerClarification` (story 2.11 Task 8 pattern).
- **`WorkflowEventDetailKeys`** — `CLARIFICATION_ID` + `QUESTION_ID` already allow-listed at `WorkflowEventDetailKeys:52-53`; `PRIOR_ANSWER_TEXT` already server-only at `:54`. Story 2.12 adds four NEW allow-listed keys: `incorporatedIntoArtifactId`, `incorporationEventId`, `supersededByArtifactId`, `noEffectReason`.
- **`AllowedAction.ANSWER_CLARIFICATION`** — already registered. Lifecycle transitions are **NOT** new `AllowedAction` values in MVP (the orchestrator drives them automatically). Story 2.14 owns the state×role→action-set logic.
- **`MdcKeys`** — `WORKFLOW_RUN_ID` + `ARTIFACT_ID` already present. No new MDC key required.
- **`ArtifactRecordPort.findByPublicId`** — used for resolving the incorporatedInto / supersededBy artifact references. Existing path, no changes needed.
- **`WorkflowEventWritePort.append`** — used as in story 2.11 for the answered event. New lifecycle events use the same path.

### State machine design — Trap T3 (terminal-state semantics)

The user story narrative says "with `superseded` and `rejected_invalid` as terminal alternates" but epic AC2 lists only "`incorporated` and `rejected_invalid` are terminal". Resolve the apparent inconsistency:

- **All three** (`incorporated`, `superseded`, `rejected_invalid`) are **terminal at the lifecycle layer** — no transition out is permitted by `ClarificationLifecycleService`.
- Story 2.11's `Clarification.isTerminal()` helper already returns `true` for all three; story 2.12 retains that contract.
- `ClarificationService.submitAnswer` (story 2.11) rejects re-answer in any terminal state including `superseded` — re-answering a superseded clarification yields `CLARIFICATION_TERMINAL_STATE`. The PM's recourse for "this got superseded but the question still matters" is to wait for the spec runner to surface a new `open` clarification on the next spec rebuild (Epic 3 runner behavior).
- Epic AC2's wording (only listing `incorporated` + `rejected_invalid` as terminal) is interpretable as "these two are the **final-decision** terminals; `superseded` is the **null-decision** terminal" but operationally they're all dead-ends. Document this in `ClarificationLifecycleService` Javadoc.

### Make-or-break contract test architecture — Trap T9 (no negative fixture file)

AC5 requires a contract test that fails when a `clarification.answered` event has no follow-up. The naive implementation creates a malformed fixture file under `fixture-event-streams/` — but that file would also be picked up by the existing `FixtureEventStreamSchemaConformanceTest` (story 1.23) which would reject it as malformed at a lower layer, masking the contract test's own assertion.

Resolution (Task 8):
1. The contract test loads only **valid** fixture files (those that conform to the schema).
2. The negative assertion lives **inside the contract test class** as an inline-constructed event list passed through the same assertion helper.
3. The malformed event stream NEVER exists as a file under `fixture-event-streams/`.

### Orchestrator stub — Trap T6 (deterministic substring scan)

`ClarificationLifecycleOrchestrator.acknowledgesQuestion(spec, questionId)` uses a **deterministic substring scan**: `payloadBytes.contains(questionId.getBytes(UTF_8))` (case-sensitive — `questionId` matches `^[A-Za-z0-9._-]{1,128}$` so case-folding is unneeded). This is **sufficient for fixture-driven testing** and intentionally simple:

- **Pros:** zero ambiguity in test expectations; the fixture is the spec — if the questionId string is in the payload bytes, it's "acknowledged."
- **Cons:** false positives if a spec mentions the questionId in a context other than "we answered this" (e.g., "Q-AUTH-001 was deferred"). The PM-facing semantic is "the question text appears somewhere in the new spec" — close enough for MVP but NOT shipping-ready.
- **Epic 3 replacement plan:** the runner result schema will gain a `clarification_acknowledgements: [{ clarificationId, questionId, status }]` block, and the orchestrator will switch to reading that block. The orchestrator's seam is the `acknowledgesQuestion(...)` method — Epic 3 replaces its body.

Document this seam in the orchestrator's Javadoc. Reviewer may request `@Deprecated` or a TODO comment with the linked Epic 3 story — recommend a TODO with `// TODO(epic-3-runner-contracts):` prefix matching the project's existing TODO convention (grep `TODO(story-` / `TODO(epic-` in `deliveryline-backend/src/main/`).

### `noEffectReason` vocabulary — Trap T2 (controlled, not free-form)

The `no_effect_reason` column is `text` with no DDL cap because PostgreSQL `text` is the recommended type for variable strings (no benefit to `varchar(n)`). The **application-layer cap** (Task 6 enforcement):

- **Allowed values in MVP:**
  - `clarification_not_addressed` — orchestrator emits this for auto-superseded clarifications when the rebuilt spec doesn't mention the questionId.
  - `pm_marked_invalid` — placeholder for future Epic 4 operator-action rejected-invalid path.
  - `spec_runner_skipped_question` — placeholder for future Epic 3 runner-emitted `noEffectReason` event.
  - `payload_read_failed` — orchestrator emits this if `ArtifactPayloadReader` throws (defensive — see OQ-5).
- **Format:** snake_case identifier, regex `^[a-z][a-z0-9_]{0,63}$` enforced at the lifecycle service.
- **Free-form override:** none in MVP. If a future story adds operator-supplied reasons, it'll need a `noEffectReasonText` column for the free-form text plus the existing taxonomy column for the controlled enum (mirror story 2.10's `taggedFeedback` + `reasonText` split).

This keeps `noEffectReason` safe to log + safe to surface in the CLI history (allow-listed) — Trap T12 reasoning from story 2.11 doesn't apply here.

### `pendingClarifications` semantics — OQ-2 deep dive

The count of `pendingClarifications` excludes only `incorporated` and `rejected_invalid` (the two "final-decision" terminals). `superseded` IS counted as pending because:

- The clarification still represents an open question (the spec rebuild didn't answer it).
- Story 2.14 (allowed-actions endpoint) gates `approve_spec` on `pendingClarifications == 0` — a superseded clarification should NOT auto-unblock approval; the PM either gets a new `open` clarification from the next spec rebuild (Epic 3 runner behavior) or explicitly marks the superseded one `rejected_invalid` via the future Epic 4 operator-action path.

Alternative considered: count `superseded` as not-pending. Rejected because it lets a spec rebuild "answer" a clarification by ignoring it — defeats the make-or-break refinement. Surface as OQ-2 in PR description; recommend the strict count.

### Why no REST endpoint in 2.12 — Trap T10

Story 2.13 owns:
- `POST /api/v1/workflows/{workflowRunId}/clarifications/{clarificationId}/answer` (story 2.11's `submitAnswer` writer wired to a controller).
- Problem Details mapping for `ILLEGAL_CLARIFICATION_TRANSITION` (this story registers it in `ProblemDetailsCatalog` per Task 2 — story 2.13 will inherit the mapping ready).

Story 2.12 deliberately ships NO REST surface for lifecycle transitions because:

1. **Lifecycle transitions are automatic** in MVP — the orchestrator (Task 4) drives them off `ArtifactOperationService.newVersion`. Exposing manual `accept`/`incorporate`/`supersede`/`reject-invalid` REST endpoints in 2.12 is premature.
2. **Future operator-action surface** (likely Epic 4 recovery / reconciliation tooling) is the right home for manual overrides. That story will design idempotency, allowed-action gating, and audit context properly.
3. **Story 2.18 (UI Clarification Region)** reads lifecycle state via `getClarificationStatus` and `getRunSummary` — it does NOT mutate lifecycle from the UI.

This mirrors story 2.9 / 2.10 / 2.11's pattern: the writer landed first, REST surface follows in 2.13 (or a future operator-action story).

### `ClarificationStatusView` derivation — Trap T1 (NOT a `Clarification` record widening)

Story 2.11's `Clarification` record is the read-port projection (carries the answer-time fields only). Story 2.12 introduces new fields (`acceptedAt`, `incorporatedAt`, `supersededByArtifactId`, `supersededByArtifactVersion`, `noEffectReason`) that:

- Are stored in the V9 schema extension.
- Are surfaced via the **new `ClarificationStatusView` record** in `WorkflowInspectionService` — used by `getClarificationStatus` (single-row) and (if needed for AC9 detail surface) by `getRunSummary`.
- Are **NOT** added to the `Clarification` record because:
  - The read port's existing `findByPublicId` / `listByWorkflowRunId` / `listByArtifactId` consumers (e.g., `ClarificationService.submitAnswer` at story 2.11 line 119) don't need them.
  - Widening `Clarification` would force every test that constructs one to thread the new fields, exploding the diff.
  - The view-layer `ClarificationStatusView` is the right home for status-derived UI fields.

Resolution: extend `ClarificationEntityMapper.toView(...)` (new method) → `ClarificationStatusView`. The existing `toSnapshot(...)` → `Clarification` mapper stays untouched. If a future need surfaces to make `Clarification` carry the lifecycle metadata, that's a deliberate widening then.

### Constructor-shape ripple — Trap T7 (estimate before grepping)

This story widens the constructor of:
- `ArtifactOperationService` — gains `ClarificationLifecycleOrchestrator`. Estimated ripple: ~3-5 test callsites + 0 production (Spring DI). Grep `new ArtifactOperationService(` first.
- Possibly `WorkflowInspectionService` — does NOT need a new dependency for `getClarificationStatus`/`getRunSummary`/`countPendingByWorkflowRun` because `ClarificationReadPort` was already injected by story 2.11. ✅ No widening here.

`WorkflowRunSummaryView` record gains a new field (`pendingClarifications: int`). Append at the END of the parameter list. Estimated ripple: ~3-6 callsites (REST, CLI, tests). Grep `new WorkflowRunSummaryView(` first.

Story 2.11's experience: estimated 10 callsites for `WorkflowInspectionService` constructor; actual was 2. Estimate generously, grep before refactoring.

### Open Questions (resolve before merging)

- **OQ-1: V9 migration scope — extend `clarifications` table or introduce a new `clarification_lifecycle_events` table?** Recommendation (current Task 1): extend the existing `clarifications` table with five columns + a CHECK. Alternative: a separate `clarification_lifecycle_events` table that records each transition as a row (audit-trail style). The alternative duplicates information already captured by `workflow_events` (each transition emits one); recommend ship the column extension to keep the read surface simple. Surface in PR description.

- **OQ-2: `superseded` counts as pending — agree?** (See Dev Notes section "`pendingClarifications` semantics".) Recommendation: yes, count `superseded` as pending so the approval gate (story 2.14) doesn't auto-unblock on silent supersession. Surface in PR description.

- **OQ-3: PM-facing manual `markRejectedInvalid` path — defer to Epic 4, or include in 2.13?** Recommendation: defer to Epic 4 (operator-action story). MVP relies on auto-orchestrator for `superseded`/`incorporated`; `rejected_invalid` is the explicit "PM declines this question" path which has no MVP UI driver. Story 2.13's controller surface stays focused on `submitAnswer`. Surface in PR description.

- **OQ-4: N+1 in `listRuns` `pendingClarifications` enrichment — accept or pre-emptively batch?** Current Task 5 implementation calls `countPendingByWorkflowRun` per row. For MVP queue sizes (< 50 rows) this is acceptable. Alternative: add `ClarificationReadPort.countPendingByWorkflowRunIds(List<String>) → Map<String, Integer>` and let the inspection service batch-query. Recommend the N+1 for MVP; mark with a `// TODO(perf):` comment plus a follow-up ticket if reviewer pushes back.

- **OQ-5: Orchestrator behavior on `ArtifactPayloadReader` failure (corrupt spec bytes, store eviction).** Recommendation: log WARN + treat as "did not acknowledge" → mark superseded with `noEffectReason="payload_read_failed"`. Alternative: rethrow as `INTERNAL_ERROR` and roll back the `newVersion` operation. The first option keeps the new spec version persisted (don't punish the spec rebuild for a clarification-side failure); the second option ensures consistency. Recommend the first with explicit logging; surface in PR description.

### Project Structure Notes

- **`ClarificationLifecycleService`** → `application/clarification/ClarificationLifecycleService.java`. New file, sibling of `ClarificationService` (story 2.11).
- **`ClarificationLifecycleOrchestrator`** → `application/clarification/ClarificationLifecycleOrchestrator.java`. New file, same package.
- **`ClarificationLifecycleResult`** → `application/clarification/ClarificationLifecycleResult.java`. New file.
- **`ClarificationStatusView`** → declared as a nested public record inside `WorkflowInspectionService.java` (sibling of the existing `ClarificationView` record at line ~860). Same pattern as story 2.11.
- **`WorkflowRunDetailedSummaryView`** → declared as a nested public record inside `WorkflowInspectionService.java` (sibling of `WorkflowRunSummaryView` at line ~801).
- **`ClarificationWritePort.MarkAccepted` / `MarkIncorporated` / `MarkSuperseded` / `MarkRejectedInvalid`** → record types declared inside `ClarificationWritePort.java`. Same pattern as `NewClarification` / `RecordAnswer` from story 2.11.
- **`V9__add_clarification_lifecycle_columns.sql`** → `deliveryline-backend/src/main/resources/db/migration/`. New migration file.
- **`ILLEGAL_CLARIFICATION_TRANSITION`** → `domain/registry/DomainErrorCode.java`. Existing enum extension.
- **No `PublicIdPrefixes` extension** (lifecycle operations don't introduce new public-id-prefixed entities; they use existing `clr_…`, `art_…`, `evt_…` prefixes).
- **No `MdcKeys` extension.**
- **No REST DTO / controller** (deferred to 2.13).
- **No CLI verb** (deferred — operator action surface lands in Epic 4 if needed).
- **No frontend changes** (deferred to 2.14 + 2.18).
- **Two new fixture event streams** under `deliveryline-backend/src/test/resources/fixture-event-streams/`.
- **JaCoCo gate** (story 2.32, LINE 81.33% / BRANCH 62.74%) applies. Reproduce on WSL2 Ubuntu native (per project memory note `wsl-linux-ci-reproduction`).

### Architecture compliance

- **Data model — explicit relational tables** (architecture.md:282): the V9 migration extends the existing `clarifications` table rather than introducing a new lifecycle-event table. Justified: the lifecycle is row-level state, not multi-row history (history is captured by the existing `workflow_events` stream).
- **State/event atomicity** (architecture.md:301-302): each `ClarificationLifecycleService.mark*` method updates the row + appends the `clarification.*` event in the SAME outer transaction (`ArtifactOperationService.newVersion @Transactional`). The lifecycle service does NOT carry its own `@Transactional` annotation — REQUIRED propagation participates in the caller's transaction.
- **Validation — layered** (architecture.md:283): jakarta-validation at the command boundary (none — lifecycle service consumes typed records, not user-supplied DTOs), application-layer invariants in `ClarificationLifecycleService` (`assertTransition`, `loadAndGuardRun`, `noEffectReason` taxonomy check), DB CHECK constraints as backstops (`ck_clarifications_status_fields_paired`).
- **Migrations** (architecture.md:284): V9 is a versioned Flyway SQL migration. No Hibernate auto-DDL.
- **Component boundaries** (architecture.md:1156-1158): `ClarificationLifecycleService` + `ClarificationLifecycleOrchestrator` live in `application.clarification`. The orchestrator depends on `ArtifactPayloadReader` (application-layer port over `ArtifactStore`). ArchUnit boundary rule (widened or sibling) asserts the package confinement.
- **FR9 contract** (PRD:641): "Product Managers can reject a specification and provide structured feedback." Story 2.10 covered the spec-rejection write path; story 2.12 extends the clarification path with `markRejectedInvalid` for the "this clarification is invalid" case (orchestrator-only in MVP).
- **FR10 contract** (PRD:642): "Authorized users can see the currently approved specification state for a governed ticket." Story 2.12 doesn't directly affect this — the spec state surface is story 2.8's `getCurrentApprovedSpec`. However the `pendingClarifications` count gating in story 2.14 ties into this contract (approval IS the currently-approved state advancing).
- **Make-or-break refinement** (UX spec line 148, 152): "Ignored clarification is a make-or-break failure for this product." Story 2.12's AC5 contract test is the codified enforcement.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface for THIS story):**
  - `ClarificationLifecycleService.mark*` (each of the four) → `INFO` on entry (envelope: `workflowRunId`, `clarificationId`, target status, actor), `INFO` on success (new status, `transitionedAt`), `WARN` on `ILLEGAL_CLARIFICATION_TRANSITION` (with `currentStatus`, `attemptedTransition`), `WARN` on `CLARIFICATION_NOT_FOUND` cross-run, `ERROR` on `INTERNAL_ERROR` from orchestrator-supplied artifact missing.
  - `ClarificationLifecycleOrchestrator.sweepAfterSpecRebuild` → `INFO` on entry (`workflowRunId`, `newSpecArtifactId`), `INFO` summary on exit (`consideredCount`, `incorporatedCount`, `supersededCount`), `WARN` on `ArtifactPayloadReader` failure with `noEffectReason="payload_read_failed"`.
  - `WorkflowInspectionService.getClarificationStatus` / `getRunSummary` → `INFO` entry + success (`workflowRunId`, `clarificationId` if applicable, `pendingClarifications` count).
  - `ClarificationWritePersistenceAdapter.mark*` → `INFO` on entry (`clarificationId`, target status), `ERROR` on unmatched `DataIntegrityViolationException`.
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `clarificationId`, `actorIdentity`, `actorType`.
- **Forbidden in log output:** `answerText`, `questionText`, `priorAnswerText` (per story 2.11 trap T12 — these are free-form reviewer/runner content). `noEffectReason` is safe to log (controlled vocabulary in MVP — see Trap T2).
- **Test contract:** new logging surfaces pinned by `ClarificationLifecycleServiceLoggingTest` (Logback `ListAppender`). Required pins: each `mark*` success line, each WARN branch (`ILLEGAL_CLARIFICATION_TRANSITION`, `CLARIFICATION_NOT_FOUND`), the orchestrator summary line, the WARN payload-read-failed line.

### Traps (anti-pattern prevention)

| ID | Trap | Resolution |
|----|------|------------|
| **T1** | Widening the `Clarification` record (story 2.11) with V9 lifecycle fields ripples into every read-port consumer test + every fixture seed call. | **Surface new fields via the new `ClarificationStatusView` record only.** `Clarification` stays lean — keep it as the answer-time projection. View-layer record is the right home for status-derived UI fields. |
| **T2** | `noEffectReason` could become a free-form text dumping ground, triggering the same log/redaction concerns as `answerText` / `reasonText`. | **Enforce controlled vocabulary at the lifecycle service:** snake_case identifier, regex `^[a-z][a-z0-9_]{0,63}$`, allowed values `clarification_not_addressed | pm_marked_invalid | spec_runner_skipped_question | payload_read_failed`. Free-form text would need a separate column + future story. |
| **T3** | "Terminal alternates" wording in the user story vs epic AC2 listing only `incorporated`/`rejected_invalid` as terminal. | **All three (`incorporated`, `superseded`, `rejected_invalid`) are terminal at the lifecycle layer** — story 2.11's `Clarification.isTerminal()` already includes `superseded`. Document in `ClarificationLifecycleService` Javadoc. |
| **T4** | `markIncorporated` is supplied a new-spec-artifact publicId by the orchestrator that just persisted it; if the artifact lookup fails, the lifecycle service raises `CLARIFICATION_NOT_FOUND` (misleading). | **Use a typed `INTERNAL_ERROR`** for orchestrator-supplied artifact missing (caller bug, not user error). Different error code, different log level (ERROR not WARN). |
| **T5** | The lifecycle sweep hooked into `ArtifactOperationService.newVersion` runs for EVERY artifact type. For non-SPEC lineages (e.g., `IMPLEMENTATION_PLAN` from Epic 3), there are no clarifications to sweep. | **Check `parent.artifactType() == SPEC` before invoking the sweep.** Skip otherwise. Document in `ArtifactOperationService.newVersion` Javadoc. |
| **T6** | The orchestrator's `acknowledgesQuestion(...)` substring scan is a stub that has false-positive cases (e.g., "Q-AUTH-001 was deferred"). Naive deployment of this as the "real" production logic would silently mark not-actually-addressed clarifications as `incorporated`. | **Document the seam in Javadoc + add a `// TODO(epic-3-runner-contracts):` marker.** Epic 3 replaces the body with a structured runner-emitted `clarification_acknowledgements` block. Reviewer-visible: the stub is acceptable for fixture-driven testing but NOT shipping-ready. |
| **T7** | Constructor-shape changes ripple. `ArtifactOperationService` gains `ClarificationLifecycleOrchestrator`; `WorkflowRunSummaryView` gains `pendingClarifications: int`. | **Grep before refactoring:** `grep -rn "new ArtifactOperationService(" deliveryline-backend/src/` and `grep -rn "new WorkflowRunSummaryView(" deliveryline-backend/src/`. Append new args/fields at the END of parameter lists so positional callers don't break. Story 2.11's experience: estimated 10 callsites for `WorkflowInspectionService`, actual was 2 — estimate generously, grep first. |
| **T8** | `ClarificationLifecycleService` could be tempted to add its own `@Transactional` annotation, breaking rollback shape under the outer `ArtifactOperationService.newVersion @Transactional` boundary. | **NO `@Transactional` on lifecycle service methods.** Relies on the outer caller's transaction. ArchUnit boundary rule (widened or sibling) asserts the package confinement. Mirror story 2.11 trap T7 / story 2.10 trap T4. |
| **T9** | The make-or-break contract test needs a negative case (event stream lacking follow-up). Naive implementation creates a malformed fixture FILE — but that file gets picked up by `FixtureEventStreamSchemaConformanceTest` (story 1.23) and rejected at a lower layer, masking the contract test's own assertion. | **Negative case lives INSIDE the contract test as inline-constructed event list.** No malformed fixture file. Document in the contract test class Javadoc. |
| **T10** | Naive 2.13 readiness might want REST mutation endpoints for `markAccepted` / `markIncorporated` / `markSuperseded` / `markRejectedInvalid` ("symmetric with `answerClarification`"). | **No REST endpoints for lifecycle in MVP.** Lifecycle is orchestrator-driven. Future operator-action surface (Epic 4) is the right home for manual overrides. Story 2.13 only ships `POST /clarifications/{id}/answer`. |
| **T11** | The new `getClarificationStatus` and `mark*` methods accept a `clarificationPublicId` standalone, exposing a cross-run leak — a caller probing with a `clr_…` from a sibling run would get a "found, here's the status" response instead of the same `CLARIFICATION_NOT_FOUND` shape story 2.11 enforces. | **All new surfaces accept BOTH `workflowRunPublicId` AND `clarificationPublicId`.** Validation: load by clr_id, then assert the row's `workflowRunId` matches; if not, raise `CLARIFICATION_NOT_FOUND` (same shape as missing-row). Mirror story 2.11 trap T6 verbatim. |
| **T12** | `pendingClarifications` count enrichment in `listRuns` introduces N+1 (one count query per row). For MVP scale acceptable; for larger queues regressed. | **Accept N+1 for MVP** (queue typical < 50 rows). Document in Javadoc + leave a `// TODO(perf): batch-count via countPendingByWorkflowRunIds(List<String>) when queue size exceeds threshold` marker. OQ-4 surfaces the alternative. |
| **T13** | `markIncorporated` appends the event then updates the clarification row with an FK to that event. Hibernate's lazy-flush ordering can cause the clarification UPDATE to flush BEFORE the event INSERT, tripping the `fk_clarifications_incorporation_event` constraint at flush time. | **Explicit flush between the two ops.** Either rely on `WorkflowEventWritePort.append(...)` flushing internally (verify), or call `entityManager.flush()` between the event append and the row update. Pin with an integration test that catches the ordering regression. Do NOT change the FK to deferrable — keep PostgreSQL defaults. |

### Previous-story intelligence

- **Story 2.11 (Clarification domain + submission, review):** shipped `Clarification` projection, `ClarificationReadPort` / `ClarificationWritePort` + adapters + repository, `ClarificationService.submitAnswer`, V8 migration, three new `DomainErrorCode` values, `WorkflowEventDetailKeys.CLARIFICATION_ID` + `QUESTION_ID` (allow-listed) + `PRIOR_ANSWER_TEXT` (server-only), `WorkflowInspectionService.getClarifications` / `getClarificationsForArtifact` / `ClarificationView`, ArchUnit boundary rule `CLARIFICATION_SERVICE_LIVES_IN_APPLICATION_CLARIFICATION`. **All five `clarification.*` lifecycle events already registered** at `WorkflowEventType:24-28` (dormant until story 2.12 emits them). Story 2.12 mirrors every 2.11 convention: archived-row filter, two-constructor Clock-injection pattern, sanitized error details, controlled-vocabulary on logged fields, cross-run guard pattern. Twelve traps declared in 2.11, all honored.
- **Story 2.10 (ApprovalService.rejectSpec + escalation, done):** shipped `RejectionTaxonomy`, V7 migration, `SpecRejectionEscalationThresholdProvider`, `@Modifying` atomic counter mutation. Established the constructor-widening procedure (grep all `new X(` callsites first) and the DB-level constraint-name resolution + SQLSTATE 23505 fallback + sanitized error-details pattern. Story 2.12 mirrors the constructor-ripple procedure for `ArtifactOperationService` (Task 9).
- **Story 2.9 (ApprovalService.approveSpec writer, done):** shipped the writer template that 2.10 + 2.11 + 2.12 all mirror. Two-constructor Clock-injection, no `@Transactional` on service (outer is boundary), ArchUnit boundary rule.
- **Story 2.8 (SpecificationArtifact + ContextBundle, done):** shipped `ApprovalReadPort` + adapter + `ApprovalSnapshot` + `ApprovalEntity`, the `join fetch` N+1 fix in repository queries (`ApprovalRepository` review patch 1), the `ArtifactPayloadReader` / `LocalArtifactStore` reading path the orchestrator (Task 4) can reuse for spec payload byte access. Cross-run guard pattern in `getContextBundleForArtifact` — story 2.11 trap T6 + story 2.12 trap T11 mirror it.
- **Story 1.23 (foundation gate + fixture event streams, done):** introduced `fixture-event-streams/` + `FixtureEventStreamSchemaConformanceTest` + the `@Tag("foundation-gate")` test routing. Story 2.12's Task 7 adds two new fixtures; Task 8 contract test joins the foundation gate.
- **Story 1.18 (CLI MVR baseline, done):** established `WorkflowInspectionService.getStatus(...)` shape. Story 2.12's `getClarificationStatus` + `getRunSummary` follow the same transactional read-only + MDC scope + log entry/exit pattern.
- **Story 1.15 (CLI submit/status/history, done):** `workflow-history.v1.schema.json` is the authoritative wire shape for the `history` render path. Story 2.12 extends it with the four new detail keys (Task 6).
- **Story 1.7 (shared command-model pattern, done):** `WorkflowCommand` sealed interface. Story 2.12 does **NOT** add a new `WorkflowCommand` member (lifecycle is orchestrator-driven; no `executeIdempotent` integration in MVP).
- **Story 1.4 (central registries, done):** drift tests. Tasks 2 + 6 extend them.
- **Story 1.3 (Flyway V1 core schema + retention, done):** `archived_at` retention column convention. V9 doesn't add new retention columns (extends existing); the `idx_clarifications_pending_by_workflow_run` partial index filters `archived_at IS NULL` for retention parity.
- **Story 2.32 (JaCoCo gate, done):** LINE 81.33% / BRANCH 62.74% floor. Reproduce on WSL2 Ubuntu native before pushing.
- **Story 2.14 (planned):** reads `pendingClarifications` from this story's `getRunSummary` / `listRuns` extension to gate `approve_spec`.
- **Story 2.18 (planned, UI):** consumes `getClarificationStatus` via TanStack Query hooks generated from OpenAPI (after story 2.13 regenerates the snapshot).
- **Story 3.x (planned, runner broker):** replaces the orchestrator stub's `acknowledgesQuestion(...)` substring scan with structured runner-emitted `clarification_acknowledgements` block consumption.

### Git intelligence

Recent commits (post-2.11):
- `bdf6e46 fix(test): add taggedFeedback to foundation reject-spec payload` — foundation fixture maintenance; story 2.12's two new fixtures must conform to the same `workflow-events-response.schema.json` shape.
- `acd7f1d chore: regenerate OpenAPI snapshot + frontend client` — OpenAPI regen discipline. Story 2.12 does NOT add new REST endpoints (Trap T10), so no OpenAPI snapshot regen needed; story 2.13 will pick up the four new `clarification.*` error codes and the lifecycle detail-key additions through the spring-doc scan.
- `3282bf2`, `6869485`, `1f16cca` — WSL2 / `regen-openapi.sh` script hardening. Project memory note `openapi-regen-platform-shim` documents the cross-shell coordination required when regenerating; story 2.12 doesn't trigger regen.
- No Co-Authored-By trailer (per project memory `commit-no-claude-coauthor`).

Story 2.11's commit shape: one dev-story commit followed by a code-review patch batch. Story 2.12 likely follows the same shape.

### Review Findings

_Code review run: 2026-05-25 (bmad-code-review). Reviewers: Blind Hunter (diff-only), Edge Case Hunter (diff + repo), Acceptance Auditor (diff + spec). 34 files / 3,167 lines reviewed. Raw diff at `_bmad-output/implementation-artifacts/.review-2-12-current.diff`._

#### Decision-needed (resolved 2026-05-25)

All 5 resolved. Four converted to Patch entries (P29–P32 below); D4 dismissed.

- **D1 → Patch P29** — Add word-boundary regex to `acknowledgesQuestion`.
- **D2 → Patch P30** — Add pessimistic row lock (`SELECT … FOR UPDATE`) in `loadAndGuardRun`.
- **D3 → Patch P31** — Convert `ArtifactOperationService` orchestrator dependency from setter to constructor injection; update ~30 test callsites.
- **D4 → Dismissed** — No pre-V9 environment carries `accepted`/`incorporated` rows (V8 shipped with only `open`/`answered`); V9 applies cleanly without backfill.
- **D5 → Patch P32** — `loadSpecPayload` empty/missing `storageRef` becomes fatal: throw `DomainException`, outer `newVersion` tx rolls back, spec rebuild fails loudly.

#### Patch (33)

- [ ] [Review][Patch] **AC10: missing tests required by spec** [`deliveryline-backend/src/test/java/org/dradgo/`] — Spec AC10 enumerates a test matrix; the following are absent from the diff: `ClarificationLifecycleOrchestratorTest`, `ArtifactOperationServiceClarificationSweepIT` (AC4 integration assertion), `WorkflowInspectionServiceClarificationStatusTest` (AC7 parametric), `ClarificationLifecycleServiceMarkIncorporatedFkOrderingIT` (**Trap T13 critical** — pin FK flush ordering), `ClarificationLifecycleServiceLoggingTest`, `ClarificationWritePersistenceAdapterLifecycleTest` (round-trip + CHECK fires), `ClarificationReadPersistenceAdapterTest` extension for `countPendingByWorkflowRun`, `WorkflowEventDetailKeysContractTest` pin for four new detail keys, `RegistryContractTest` / `ProblemDetailsContractTest` updates for `ILLEGAL_CLARIFICATION_TRANSITION`. _Source: auditor_
- [ ] [Review][Patch] **FK flush-ordering verification (Trap T13)** [`ClarificationLifecycleService.markIncorporated`] — Code claims Trap T13 was solved but provides no proof. `markIncorporated` calls `appendEvent` then `clarificationWritePort.markIncorporated(... incorporationEventPublicId ...)` whose adapter must resolve the public id to internal pk via `WorkflowEventRepository.findIdByPublicId`. If `append` is JDBC `INSERT` or flush mode is `COMMIT`, the lookup returns empty and the FK write becomes NULL → V9 CHECK violation. Fix: write the integration test (above), and if it fails, add explicit `entityManager.flush()` before the lookup or change the adapter to use `EntityManager.getReference(WorkflowEventEntity.class, internalId)`. _Source: blind_
- [x] [Review][Patch] **Sweep retry idempotency** [`ClarificationLifecycleService.markIncorporated/markSuperseded`] — On outer-tx retry (serialization failure), the second sweep replays `mark*` on rows already in `incorporated`/`superseded`, throwing `ILLEGAL_CLARIFICATION_TRANSITION`. Fix: in `assertTransition` (or new `idempotentMark*` overload), if `currentStatus == targetStatus`, return existing result without appending duplicate event. _Source: edge_
- [x] [Review][Patch] **V9: `incorporation_event_id ON DELETE SET NULL` contradicts CHECK** [`V9__add_clarification_lifecycle_columns.sql`] — V8 FK uses `ON DELETE SET NULL` for `incorporation_event_id`; V9 CHECK requires it `IS NOT NULL` when `status='incorporated'`. Tombstoning a workflow_events row triggers a ConstraintViolationException on next update of the clarification. Fix: change FK to `ON DELETE RESTRICT` in V9 (alter constraint) OR relax CHECK to allow NULL after tombstone. _Source: edge_
- [x] [Review][Patch] **V9: `rejected_invalid` CHECK allows `accepted_at NOT NULL`** [`V9__add_clarification_lifecycle_columns.sql:2224-2228`] — State machine only allows `answered → rejected_invalid`, so `accepted_at` must be NULL on that arm. Fix: add `accepted_at IS NULL` to the `rejected_invalid` branch of `ck_clarifications_status_fields_paired`. _Source: edge_
- [x] [Review][Patch] **Contract test: group by `(workflowRunId, clarificationId)`** [`ClarificationVisibleIncorporationContractTest:2641-2695`] — Currently groups by `clarificationId` alone; a fixture with the same `clr_*` id under two runs aggregates events across runs and may falsely pass. _Source: edge_
- [x] [Review][Patch] **Contract test: validate transition shape, not just chain existence** [`ClarificationVisibleIncorporationContractTest.assertVisibleIncorporation`] — Sequences like `[answered, rejectedInvalid, accepted, incorporated]` (impossible per AC2) pass today because the test only requires chain existence. Fix: assert state-machine ordering — `accepted` must precede `incorporated`/`superseded`; `rejectedInvalid` must directly follow `answered` (no preceding `accepted`). _Source: edge_
- [x] [Review][Patch] **Contract test: `NO_EFFECT_REASON` references fictitious event type** [`ClarificationVisibleIncorporationContractTest:2571`] — Constant `"clarification.noEffectReason"` is claimed to be at `WorkflowEventType:28` by the spec, but the enum's `value()` must match exactly. Also: `WorkflowEventType.CLARIFICATION_INCORPORATED.value()` — snake_case vs camelCase mismatch with the fixture event-type strings would cause the type filter to silently skip rows. Fix: verify `WorkflowEventType` source carries `"clarification.incorporated"`, `"clarification.noEffectReason"` etc. verbatim; if not, fix the constants in the contract test. _Sources: blind+edge_
- [x] [Review][Patch] **Contract test: require `details.clarificationId` on `clarification.*` events** [`ClarificationVisibleIncorporationContractTest:2590-2592`] — Missing field silently `continue`s; a buggy `clarification.answered` row with no clarificationId is never asserted. Fix: if `eventType.startsWith("clarification.")` then require non-null `clarificationId`. _Source: edge_
- [x] [Review][Patch] **Hoist artifact fetch out of sweep loop** [`ClarificationLifecycleService.markIncorporated/markSuperseded:1801,1864`] — Each `mark*` call re-fetches the same artifact via `artifactRecordPort.findByPublicId`. For a sweep over N accepted clarifications: N×4 round trips for the same artifact. Fix: orchestrator passes the artifact record into the `mark*` calls (or expose a single-load helper). _Source: blind_
- [x] [Review][Patch] **Replay-ref `|` separator brittle** [`WorkflowCommandService:357-373`] — `clarificationReplayState/RunId` use `|` as separator, but `currentState.value()` is concatenated raw. No validation that state values are `|`-free. Fix: switch to JSON-encoded resultRef, OR use a separator that the state regex disallows (e.g. control char ``). _Sources: blind+auditor_
- [x] [Review][Patch] **`getClarificationStatus` NPE safety** [`WorkflowInspectionService:496`] — `if (!snapshot.workflowRunId().equals(workflowRunPublicId))` NPEs if `workflowRunId` is ever null. Fix: invert to `if (!workflowRunPublicId.equals(snapshot.workflowRunId()))`. _Source: blind_
- [x] [Review][Patch] **`WorkflowSummaryResponse.from` verification** [`WorkflowSummaryResponse.java`] — Only the comment changed; `WorkflowRunSummaryView` gained `pendingClarifications` at the end of its component list. If `from` constructs positionally, this either no longer compiles or silently mismaps. Fix: read the mapper body, confirm field-by-field assignment, and extend if needed (or document the deferral firmly). _Source: blind_
- [x] [Review][Patch] **`correlationId` injection bypasses allow-list** [`ClarificationLifecycleService.appendEvent:2051-2053`] — `correlationId` is added to event `details` but is not in `WorkflowEventDetailKeys.ALLOW_LISTED_KEYS` nor `SERVER_ONLY_KEYS`. Risk of `WorkflowEventDetailKeysContractTest` failure once that test is updated. Fix: add `CORRELATION_ID` to the allow-list explicitly OR stop including it in details (move to MDC only). _Sources: blind+auditor_
- [x] [Review][Patch] **`markRejectedInvalid` per-method `noEffectReason` vocabulary unrestricted** [`ClarificationLifecycleService:1904`] — Accepts any token from `ALLOWED_NO_EFFECT_REASONS` (5 values) including `payload_read_failed` which is nonsensical for a PM-driven rejection. Spec reserves vocabulary per method. Fix: enforce per-method subset — `markRejectedInvalid` accepts only `{pm_marked_invalid, spec_runner_skipped_question}`; `markSuperseded` accepts only `{clarification_not_addressed, superseded_by_unrelated_rebuild, payload_read_failed}`. _Sources: blind+auditor_
- [x] [Review][Patch] **Terminal re-entry error code conflation** [`ClarificationLifecycleService.assertTransition`] — `markSuperseded` on `rejected_invalid` raises `ILLEGAL_CLARIFICATION_TRANSITION` indistinguishable from "row got concurrently transitioned by another actor". `CLARIFICATION_TERMINAL_STATE` problem-type is registered (`ProblemDetailsCatalog:55-58`) but unused. Fix: route reads where `currentStatus.isTerminal()` to `CLARIFICATION_TERMINAL_STATE`; reserve `ILLEGAL_CLARIFICATION_TRANSITION` for "wrong precursor but not terminal". _Source: edge_
- [x] [Review][Patch] **`getClarificationsForArtifact` no run-scope guard** [`WorkflowInspectionService:454`] — Calls `listByArtifactId(artifactPublicId)` without checking caller's workflow run. Cross-run/cross-tenant leak. Fix: add `workflowRunPublicId` parameter and filter in the read port (mirror Trap T11 pattern used in `getClarificationStatus`). _Source: edge_
- [x] [Review][Patch] **`clarificationReplayRef` null-guard** [`WorkflowCommandService:351-355`] — Concatenates `result.currentState().value()` without null check; an NPE here leaks into `completeFailedInIndependentTransaction` and pollutes idempotency table with FAILED record for a succeeded command. Fix: null-guard. _Source: blind_
- [x] [Review][Patch] **JSON schema: `noEffectReason` free-form string** [`workflow-events-response.schema.json` + `workflow-history.v1.schema.json`] — Schema accepts any string; no enum constrains the vocabulary. Free-form text ships through CLI history. Fix: add `enum: ["clarification_not_addressed","superseded_by_unrelated_rebuild","payload_read_failed","pm_marked_invalid","spec_runner_skipped_question"]`. _Source: blind_
- [x] [Review][Patch] **`PRIOR_ANSWER_TEXT` allow-list reconciliation** [`WorkflowEventDetailKeys`] — Added to `SERVER_ONLY_KEYS` but not `ALLOW_LISTED_KEYS`. Depending on renderer's combine semantics, it may silently disappear or double-reject. Fix: verify renderer; either include in allow-list AND server-only (filter after allow), or document why excluded. _Source: blind_
- [x] [Review][Patch] **`appendEvent` defensive copy of caller details map** [`ClarificationLifecycleService.appendEvent:2051-2053`] — Mutates caller's `LinkedHashMap` to inject `correlationId`. Future caller passing shared/immutable map gets a surprise. Fix: `new LinkedHashMap<>(details)` at entry. _Sources: blind+edge_
- [x] [Review][Patch] **Empty `questionId` throws IAE not silent-false** [`ClarificationLifecycleOrchestrator.acknowledgesQuestion:1554`] — Returns false silently for empty needle; combined with non-validated fixture clarifications this silently `superseded`s real work. Fix: throw `IllegalArgumentException` (or `Preconditions.checkArgument`). _Source: edge_
- [x] [Review][Patch] **`actor.correlationId()` blank vs null normalization** [`ClarificationLifecycleService.appendEvent:2051`] — Blank `""` correlationId leaks into events while null is filtered. Fix: normalize via `StringUtils.trimToNull` at the actor-builder boundary or at `appendEvent`. _Source: edge_
- [x] [Review][Patch] **`PublicIdPrefixes.require` at orchestrator entry** [`ClarificationLifecycleOrchestrator.sweepAfterSpecRebuild`] — Three string inputs (workflowRunPublicId, newSpecArtifactPublicId, …) pass through to read/write ports without prefix validation. A blank/whitespace id silently returns empty list → no-op sweep. Fix: `PublicIdPrefixes.require(..., RUN/ARTIFACT)` at method entry. _Source: edge_
- [x] [Review][Patch] **Test helper rename: `answeredRowWithRun(String)`** [`ClarificationLifecycleServiceTest:2473-2477`] — Overload with same arity-different-meaning (`String status` vs `String runId`). Confusing API. Fix: rename single-arg variant to `answeredRowForRun`. _Source: blind_
- [x] [Review][Patch] **`acknowledgesQuestion` `@VisibleForTesting`** [`ClarificationLifecycleOrchestrator:1552`] — Package-private static, implied for tests but no annotation. Fix: add annotation or Javadoc note. _Source: blind_
- [x] [Review][Patch] **`newVersion` log recomputes filter counts** [`ArtifactOperationService.newVersion:151-156`] — Two filter+count passes over `sweep.decisions()`. Fix: expose `incorporatedCount`/`supersededCount` on `LifecycleSweepResult`, or compute once. _Source: blind_
- [x] [Review][Patch] **`assertTransition` static method uses instance-logger pattern** [`ClarificationLifecycleService:1964-1987`] — Works today because `log` is `static final`, but symmetry vs other helpers (`clarificationNotFound` is instance) reads inconsistent. Fix: either make all helpers static-using-static-log, or make `assertTransition` non-static. _Source: blind_
- [x] [Review][Patch] **`getRunSummary` read consistency** [`WorkflowInspectionService.getRunSummary`] — Three sequential reads under `READ COMMITTED` can show pending=1 + lastEventType=incorporated for the same row. Fix: bump to `REPEATABLE READ` for this method's `@Transactional`, OR re-order reads so pending count comes last. _Source: edge_
- [x] [Review][Patch] **P29: `acknowledgesQuestion` word-boundary regex (from D1)** [`ClarificationLifecycleOrchestrator.acknowledgesQuestion:1552-1567`] — Replace `String.contains` with `Pattern.compile("(?<![A-Za-z0-9._-])" + Pattern.quote(questionId) + "(?![A-Za-z0-9._-])")`. Prevents Q-1 matching inside Q-12 and prevents incidental "Q-AUTH-001 was deferred" false positives. _Decision: D1 resolved 2026-05-25._
- [x] [Review][Patch] **P30: Pessimistic row lock in `loadAndGuardRun` (from D2)** [`ClarificationLifecycleService.loadAndGuardRun`] — Add `SELECT … FOR UPDATE` to the clarification load inside `loadAndGuardRun` (or extend `ClarificationReadPort` with `findByPublicIdForUpdate`). Forces serialization of conflicting transitions on the same clarification row, eliminating the duplicate-event race between sweep and manual `markAccepted`. _Decision: D2 resolved 2026-05-25._
- [x] [Review][Patch] **P31: Constructor injection for `ClarificationLifecycleOrchestrator` on `ArtifactOperationService` (from D3)** [`ArtifactOperationService:109,124-128`] — Remove the `@Autowired(required=false)` setter; append the orchestrator as a constructor parameter at the END of the parameter list (Trap T7 — positional ordering). Update ~30 test callsites of `new ArtifactOperationService(...)` to pass either the real orchestrator or a deterministic test double. No more silent-skip sweep in test paths. _Decision: D3 resolved 2026-05-25._
- [x] [Review][Patch] **P32: Fatal abort on empty `storageRef` in `loadSpecPayload` (from D5)** [`ClarificationLifecycleOrchestrator.loadSpecPayload:1462-1484`] — Throw `DomainException(INTERNAL_ERROR or new SPEC_PAYLOAD_UNREADABLE)` when `storageRef` is null/blank or `ArtifactPayloadStore.readBytes` returns empty. Outer `newVersion` tx rolls back; spec rebuild fails loudly so operator sees it. No clarifications get silently terminal-superseded. Add an integration test pinning the rollback shape. _Decision: D5 resolved 2026-05-25._

#### Deferred (17)

- [x] [Review][Defer] **`pendingClarifications` N+1 in `listRuns`** [`WorkflowInspectionService:614`] — self-acknowledged in code comment ("MVP queue scale, typical <50 rows"). Defer to a future batched-count refactor. _Source: blind_
- [x] [Review][Defer] **REST surface `pendingClarifications` not on `WorkflowSummaryResponse`** [`WorkflowSummaryResponse.java`] — Explicitly deferred to story 2.13 OpenAPI regen per code comment. Story 2.14 reads `WorkflowInspectionService.getRunSummary` directly. _Source: auditor_
- [x] [Review][Defer] **`ClarificationLifecycleSnapshot` skips vocabulary/consistency validation** [`ClarificationLifecycleSnapshot:2131-2146`] — DB CHECK catches at storage; defensive in-memory validation lower priority. _Source: blind_
- [x] [Review][Defer] **AC1 / AC6 signature deviations** [`ClarificationLifecycleService.markAccepted`, `WorkflowInspectionService.getClarificationStatus`] — Implementation adds `workflowRunPublicId` to method signatures per Task 6 body intent (Trap T11 guard); spec AC1/AC6 summary doesn't reflect this. Intentional deviation; document in PR. _Source: auditor_
- [x] [Review][Defer] **`payload_read_failed` `noEffectReason` token outside AC4 literal vocabulary** [`ClarificationLifecycleOrchestrator`] — Consistent with Dev Notes OQ-5 recommendation though not in AC4 contract. Document in PR. _Source: auditor_
- [x] [Review][Defer] **`SubmitClarificationCommand` plumbing scope creep into 2.11** [`WorkflowCommandService`, fingerprint factory, replay ref, sealed permits] — Story 2.11 finish-up bundled into 2.12. Intentional / no rework needed; flag in PR description. _Source: auditor_
- [x] [Review][Defer] **`markRejectedInvalid` accepted→rejected branch missing** [`ClarificationLifecycleService:1922`] — Spec AC2 lists `answered → rejected_invalid` only; PM may later want `accepted → rejected_invalid` for "realized question was malformed after accept". Out of scope for 2.12; confirm with PM. _Source: edge_
- [x] [Review][Defer] **`replayStateChange` pins live state in tx** [`WorkflowCommandService:282-291`] — Fragile if `findByPublicId` ever moves outside tx, but currently safe. Speculative. _Source: blind_
- [x] [Review][Defer] **`getRunSummary` cross-tenant timing leak** [`WorkflowInspectionService.getRunSummary`] — Speculative; depends on `runNotFound` implementation. _Source: edge_
- [x] [Review][Defer] **`acknowledgesQuestion` payload size cap** [`ClarificationLifecycleOrchestrator:1552-1567`] — Epic 3 replaces with structured `clarification_acknowledgements` block. Defer per Trap T6. _Source: edge_
- [x] [Review][Defer] **Contract test resubmission case `[answered, accepted, answered, …]`** [`ClarificationVisibleIncorporationContractTest`] — Story 2.11 invariant should prevent resubmission upstream; contract test gap acceptable. _Source: edge_
- [x] [Review][Defer] **`executeIdempotent` overload churn** [`WorkflowCommandService:305-313`] — 3-arg + 4-arg overloads with overlapping responsibility. Refactor opportunity. _Source: blind_
- [x] [Review][Defer] **Checkstyle suppression hardcoded line number** [`config/checkstyle/suppressions.xml`] — `lines="483"` is brittle; pre-existing pattern. _Source: blind_
- [x] [Review][Defer] **`FlywaySchemaContractTest` CHECK assertion substring-weak** [`FlywaySchemaContractTest:1192-1223`] — Substring matches don't catch removed CHECK arms. Generally weak; out of scope for 2.12 reinforcement. _Source: blind_
- [x] [Review][Defer] **V9 partial index uses string status filter** [`V9__add_clarification_lifecycle_columns.sql:2235-2237`] — Defensive note only; no current break. _Source: edge_
- [x] [Review][Defer] **`SKIPPED_NON_ACCEPTED` enum value dead code** [`ClarificationLifecycleOrchestrator.Outcome`] — Defined but never emitted (sweep filters upfront). Observation only. _Source: auditor_

#### Dismissed (6)

_Not written to story: false positives, noise, or decisions resolved to no-op._

- AC4-dev3: `artifact.artifactType()==SPEC` guard vs `parent.artifactType()==SPEC` — functionally equivalent for `newVersion`. _(auditor)_
- `WorkflowState.fromValue` exception types not enumerated — speculative. _(edge)_
- `clarificationReplayRef` "stale state on replay" — documented design intent, not a bug. _(edge)_
- `incorporationEventPublicId` advanced before append, "lost on failure" — cosmetic, no observable defect. _(edge)_
- `assertTransition` static-with-instance-log pattern — promoted to Patch above; original "dismiss" entry removed. _(blind)_
- **D4: V9 pre-existing-row backfill** — verified no pre-V9 environment carries `accepted`/`incorporated` rows (V8 shipped with `open`/`answered` only). V9 applies cleanly without backfill. _Decision: D4 resolved 2026-05-25._

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.12](_bmad-output/planning-artifacts/epics.md#L1130-L1147) — this story's epic-level AC list (note: AC2 omits `superseded` from the terminal list; Trap T3 resolves to "all three are terminal at the lifecycle layer")
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.11](_bmad-output/planning-artifacts/epics.md#L1108-L1128) — prerequisite story (Clarification domain + submission); shipped event-registry foundations + read/write ports
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.13](_bmad-output/planning-artifacts/epics.md#L1149-L1166) — REST mutation endpoints; will pick up the new `ILLEGAL_CLARIFICATION_TRANSITION` problem-details mapping registered here
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.14](_bmad-output/planning-artifacts/epics.md#L1168-L1186) — allowed-actions endpoint; consumes `pendingClarifications` from `WorkflowInspectionService.getRunSummary` to gate `approve_spec`
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.18](_bmad-output/planning-artifacts/epics.md#L1254-L1274) — UI Clarification Region; consumes `getClarificationStatus` for the per-question lifecycle indicator
- [Source: _bmad-output/planning-artifacts/prd.md#FR9](_bmad-output/planning-artifacts/prd.md#L641) — clarifications enable in-context refinement without re-cycling spec generation
- [Source: _bmad-output/planning-artifacts/prd.md#FR10](_bmad-output/planning-artifacts/prd.md#L642) — question text preserved across spec supersession (story 2.11's `question_text` snapshot column)
- [Source: _bmad-output/planning-artifacts/architecture.md#Data architecture](_bmad-output/planning-artifacts/architecture.md#L274-L331) — relational tables + Flyway + state/event atomicity
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Clarification Region](_bmad-output/planning-artifacts/ux-design-specification.md#L1474-L1525) — UX-DR11 anatomy + lifecycle vocabulary (`answered / pending incorporation`, `incorporated`)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#L148](_bmad-output/planning-artifacts/ux-design-specification.md#L148) — make-or-break framing: "Ignored clarification is a make-or-break failure for this product"
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#L1801-L1804](_bmad-output/planning-artifacts/ux-design-specification.md#L1801-L1804) — UX-DR15 visible-incorporation rule ("Do not collapse 'answer received' and 'answer incorporated' into one message")
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java#L23-L28](deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java) — all six `clarification.*` event types **already registered** (`ANSWERED`, `ACCEPTED`, `INCORPORATED`, `SUPERSEDED`, `REJECTED_INVALID`, `NO_EFFECT_REASON`)
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java](deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java) — file to extend with `ILLEGAL_CLARIFICATION_TRANSITION` (Task 2)
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java#L49-L88](deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java) — `CLARIFICATION_ID` + `QUESTION_ID` already allow-listed; extend with `incorporatedIntoArtifactId`, `incorporationEventId`, `supersededByArtifactId`, `noEffectReason` (Task 6)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/clarification/Clarification.java](deliveryline-backend/src/main/java/org/dradgo/application/clarification/Clarification.java) — projection record with status constants + `isTerminal()` helper (story 2.11 baseline; story 2.12 does NOT widen it — Trap T1)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationService.java](deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationService.java) — service template (mirror for `ClarificationLifecycleService`)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/clarification/spi/ClarificationReadPort.java](deliveryline-backend/src/main/java/org/dradgo/application/clarification/spi/ClarificationReadPort.java) — read port to extend with `countPendingByWorkflowRun` (Task 3)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/clarification/spi/ClarificationWritePort.java](deliveryline-backend/src/main/java/org/dradgo/application/clarification/spi/ClarificationWritePort.java) — write port to extend with four `mark*` methods (Task 3)
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ClarificationWritePersistenceAdapter.java](deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ClarificationWritePersistenceAdapter.java) — write adapter (story 2.11); add the four `mark*` method implementations
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java#L60-L97](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java) — inspection service constructor (already injects `ClarificationReadPort`; no widening needed)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java#L99-L160](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java) — `getClarifications` / `getClarificationsForArtifact` / `ClarificationView` (story 2.11); pattern to mirror for `getClarificationStatus` (Task 5)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java#L298-L327](deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java) — `listRuns` + `WorkflowRunSummaryView` (line 801+); pattern to extend with `pendingClarifications` (Task 5)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactOperationService.java#L450-L470](deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactOperationService.java) — `newVersion(parentArtifactId, payloadRef, actor)`; story 2.12 hooks the orchestrator sweep AFTER this method's `ARTIFACT_VERSION_CREATED` event append (Task 9)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java](deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java) — sibling writer service (story 2.9); shape reference for `ClarificationLifecycleService` constructor pattern
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java#L374](deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java) — `CLARIFICATION_SERVICE_LIVES_IN_APPLICATION_CLARIFICATION` ArchUnit rule; widen or add sibling for `ClarificationLifecycleService` + `ClarificationLifecycleOrchestrator` (Task 6)
- [Source: deliveryline-backend/src/main/resources/db/migration/V8__add_clarifications.sql](deliveryline-backend/src/main/resources/db/migration/V8__add_clarifications.sql) — story 2.11 V8 migration; V9 layers on top
- [Source: deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json](deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json) — CLI history wire-format schema; extend with the four new detail keys (Task 6)
- [Source: deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json](deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json) — fixture wire-format schema; new fixtures (Task 7) must conform
- [Source: deliveryline-backend/src/test/resources/fixture-event-streams/happy-path-success.json](deliveryline-backend/src/test/resources/fixture-event-streams/happy-path-success.json) — existing fixture for opening-scaffolding reference
- [Source: _bmad-output/implementation-artifacts/2-11-backend-clarification-domain-model-and-submission.md](_bmad-output/implementation-artifacts/2-11-backend-clarification-domain-model-and-submission.md) — prerequisite story (review status); foundations + trap pattern + 12-trap discipline
- [Source: _bmad-output/implementation-artifacts/2-10-backend-spec-rejection-with-structured-feedback-and-escalation.md](_bmad-output/implementation-artifacts/2-10-backend-spec-rejection-with-structured-feedback-and-escalation.md) — sibling writer story; constructor-widening procedure + ProblemDetailsCatalog + WorkflowEventDetailKeys allow-list extension pattern
- [Source: _bmad-output/implementation-artifacts/2-9-backend-approval-service-core-approve-with-version-binding.md](_bmad-output/implementation-artifacts/2-9-backend-approval-service-core-approve-with-version-binding.md) — earlier sibling writer; two-constructor Clock pattern + ArchUnit boundary rule
- [Source: _bmad-output/implementation-artifacts/2-8-backend-specification-artifact-model-and-spec-stage-context-bundle.md](_bmad-output/implementation-artifacts/2-8-backend-specification-artifact-model-and-spec-stage-context-bundle.md) — read-side foundations; `join fetch` N+1 pattern + cross-run guard for `getContextBundleForArtifact`

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

- Focused dev run (Windows JDK21, Maven offline): `mvn -pl deliveryline-backend -o test` → `Tests run: 450, Failures: 0, Errors: 6, Skipped: 3` — six errors are the pre-existing `ContextBundleService*Test` failures from story 2.8 WIP (last touched 7f15356) unrelated to story 2.12. Net new: +13 tests, zero new regressions vs the story 2.11 baseline (437/0/6/3).
- ClarificationLifecycleServiceTest focused slice: 10/10 green.
- ArtifactOperationServiceUnitTest regression slice: 31/31 green — setter-injection avoided the ~30-callsite constructor ripple Trap T7 warned about.

### Completion Notes List

- All 10 tasks structurally landed. V9 migration, error code + Problem Details, port + adapter extensions, lifecycle service, orchestrator, inspection-service additions, ArchUnit rule, fixtures, contract test, and orchestrator wiring all shipped per the story spec.
- Open Questions resolved per story-recommended defaults (the working assumptions; surface in PR description for explicit reviewer sign-off):
  - OQ-1 V9 migration scope -> extend the existing `clarifications` table (chosen). Five lifecycle columns + composite FK + status-derivable CHECK + partial pending index.
  - OQ-2 `superseded` counts as pending -> yes (chosen). `ClarificationReadPort.countPendingByWorkflowRun` filters only `incorporated` + `rejected_invalid` so the approval gate (story 2.14) does not auto-unblock on silent supersession.
  - OQ-3 PM-facing manual `markRejectedInvalid` -> defer to Epic 4 operator-action story (chosen). MVP wiring is orchestrator-only; the fixture `clarification-superseded-and-rejected.json` documents the desired event shape with `actorType=human` so the contract test stays exhaustive.
  - OQ-4 N+1 in `listRuns` `pendingClarifications` -> accept for MVP (chosen). Documented in `WorkflowRunSummaryView` Javadoc; batch-count via `countPendingByWorkflowRunIds(List<String>)` is the follow-up path if reviewer prefers.
  - OQ-5 Orchestrator behavior on payload-read failure -> WARN + mark every `accepted` clarification `superseded` with `noEffectReason="payload_read_failed"` (chosen). Keeps the new spec version persisted; clarification-side failure does not punish the spec rebuild.
- Trap honor roll (all 13 declared traps implemented):
  - T1 `Clarification` record stays lean — new `ClarificationLifecycleSnapshot` projection carries V9 fields; `ClarificationStatusView` (nested in `WorkflowInspectionService`) is the view-layer surface.
  - T2 `noEffectReason` controlled vocabulary enforced in `ClarificationLifecycleService` (regex + allow-list).
  - T3 All three (`incorporated`, `superseded`, `rejected_invalid`) terminal at lifecycle layer; `assertTransition` enforces.
  - T4 `markIncorporated` orchestrator-supplied artifact missing -> `INTERNAL_ERROR` (distinct from `CLARIFICATION_NOT_FOUND`).
  - T5 Sweep ONLY runs when `parent.artifactType() == SPEC` — `ArtifactOperationService.newVersion` guard.
  - T6 Orchestrator `acknowledgesQuestion(...)` is a deterministic substring-scan stub; documented `TODO(epic-3-runner-contracts)` marker in Javadoc.
  - T7 Constructor-shape ripple avoided via setter-injection on `ArtifactOperationService.clarificationLifecycleOrchestrator` (`@Autowired(required = false)`) — zero shape change to ~30 unit-test callsites. `WorkflowRunSummaryView` extended at end of parameter list.
  - T8 NO `@Transactional` annotation on `ClarificationLifecycleService` mark methods — relies on outer caller's transaction.
  - T9 Make-or-break contract test negative case lives INLINE — no malformed fixture file.
  - T10 NO REST endpoints for lifecycle in MVP — orchestrator-driven.
  - T11 Cross-run leak guard on `getClarificationStatus` + `mark*` — `loadAndGuardRun` raises `CLARIFICATION_NOT_FOUND` on `workflowRunId` mismatch (same shape as missing-row).
  - T12 `pendingClarifications` N+1 documented; OQ-4 surfaces the alternative.
  - T13 `markIncorporated` FK flush-ordering — event appended BEFORE the row UPDATE; adapter resolves `incorporation_event_id` via `WorkflowEventRepository.findIdByPublicId`.
- REST DTO + OpenAPI snapshot deferred. `WorkflowSummaryResponse` does NOT yet expose `pendingClarifications` (only the application-layer `WorkflowRunSummaryView` does). Reason: the `OpenApiSnapshotContractTest` drift gate requires a coordinated `-Dopenapi.snapshot.write=true` regeneration that needs a live Spring Boot + Postgres start, deferred to story 2.13. Story 2.14 reads `WorkflowInspectionService.getRunSummary()` directly without depending on the REST shape.
- Deferred to code-review batch (mirror story 2.11 / 2.9 / 2.10 patterns):
  - Testcontainers-based `ClarificationWritePersistenceAdapterLifecycleTest` (Spring-slice round-trip of each `mark*` + CHECK violation probes).
  - Testcontainers-based extension of `ClarificationReadPersistenceAdapterTest` for `countPendingByWorkflowRun` + `findLifecycleSnapshotByPublicId`.
  - `ClarificationLifecycleServiceMarkIncorporatedFkOrderingIT` (Spring-slice FK flush-ordering pin — Trap T13 regression net).
  - `ArtifactOperationServiceClarificationSweepIT` (end-to-end Spring + Testcontainers sweep happy path — AC4).
  - `WorkflowInspectionServiceClarificationStatusTest` (parametric over six status combinations — AC7).
  - `ClarificationLifecycleOrchestratorTest` (Mockito coverage; behavior on payload-read failure + zero-accepted short-circuit).
  - `ClarificationLifecycleServiceLoggingTest` (Logback `ListAppender` pinning each WARN/ERROR branch).
  - WSL2 Ubuntu native verify reproducing the JaCoCo gate (LINE 81.33% / BRANCH 62.74%) per project memory note `wsl-linux-ci-reproduction`.
- Foundation gate. New `ClarificationVisibleIncorporationContractTest` carries `@Tag("contract")` + `@Tag("foundation-gate")` so the dedicated CI tier picks it up. Negative inline case asserts the assertion-helper rejects dangling `clarification.answered` events. Two new fixtures pass the existing `FixtureEventStreamSchemaConformanceContractTest`.
- ArchUnit boundary. New rule `CLARIFICATION_LIFECYCLE_LIVES_IN_APPLICATION_CLARIFICATION` confines both `ClarificationLifecycleService` and `ClarificationLifecycleOrchestrator` to `application.clarification` + whitelisted packages. Wired into `ArchitectureBoundaryTest`.
- Registry drift. New `DomainErrorCode.ILLEGAL_CLARIFICATION_TRANSITION` + `ProblemDetailsCatalog` mapping (409, retryable=false) + placeholder JSON. Four new `WorkflowEventDetailKeys` allow-listed (`incorporatedIntoArtifactId`, `incorporationEventId`, `supersededByArtifactId`, `noEffectReason`) + `workflow-history.v1.schema.json` extension. Drift tests auto-enumerate the enums so coverage is intrinsic.

#### Review-batch dev cycle 2026-05-25 (follow-up to bmad-code-review)

This dev cycle worked the 33 patches from the 2026-05-25 bmad-code-review (Blind Hunter / Edge Case Hunter / Acceptance Auditor, see `.review-2-12-current.diff`). Net resolution:

- **9 patches verified from the pre-review session (P3, P4, P11, P17, P20, P21, P22, P25, P29).** Checkboxes flipped after re-reading each cited file.
- **22 patches landed in this cycle:**
  - **D-decision patches:** P29 (word-boundary regex — verified already shipped), P30 (`ClarificationReadPort.findByPublicIdForUpdate` + `SELECT … FOR UPDATE` in `ClarificationRepository` + `loadAndGuardRun` rewire), P31 (constructor-inject orchestrator on `ArtifactOperationService`; ~30 test-callsite update via factory + direct-constructor `replace_all`), P32 (fatal abort on `loadSpecPayload` → `DomainException(ARTIFACT_PAYLOAD_UNAVAILABLE)`; outer tx rolls back).
  - **Multi-file refactors:** P9 (hoist artifact fetch — mark methods take explicit `int newSpecArtifactVersion`; lifecycle service no longer depends on `ArtifactRecordPort`), P10 (replay-ref separator switched from `|` to ASCII Unit Separator `U+001F`), P14 (per-method `noEffectReason` vocab subsets: `SUPERSEDED_REASONS` / `REJECTED_INVALID_REASONS`), P15 (`assertTransition` routes terminal-state re-entry to `CLARIFICATION_TERMINAL_STATE`; reserves `ILLEGAL_CLARIFICATION_TRANSITION` for non-terminal precursor mismatch), P16 (`getClarificationsForArtifact` takes `workflowRunPublicId` + cross-run filter at view layer — Trap T11 parity).
  - **Smaller polish:** P5 (idempotent `currentStatus == target` short-circuit returns existing result without re-emitting event), P12 (`WorkflowSummaryResponse.from` audited — positional-but-named accessors safe), P13 (`CORRELATION_ID` confirmed in `ALLOW_LISTED_KEYS`), P19 (schema enum for `noEffectReason` in both `workflow-events-response.schema.json` and `workflow-history.v1.schema.json`), P19b (`PRIOR_ANSWER_TEXT` server-only/allow-list split confirmed correct per Trap T12), P23 (`PublicIdPrefixes.require` at `sweepAfterSpecRebuild` entry), P24 (rename `answeredRowWithRun(String)` → `answeredRowForRun`), P26 (`LifecycleSweepResult` gains `incorporatedCount` / `supersededCount` fields; `newVersion` log reads pre-computed counts), P27 (`clarificationNotFound` made `static` for symmetry), P28 (`getRunSummary` reorders reads so `countPendingByWorkflowRun` runs LAST — eliminates stale-high pending vs fresh terminal event-type read inversions under READ COMMITTED).
  - **Contract-test hardening (4 patches landed in `ClarificationVisibleIncorporationContractTest`):** group by composite `(workflowRunId, clarificationId)` scope key, validate transition shape (rejects e.g. `[answered, rejectedInvalid, accepted, incorporated]`), event-type case-correctness verified against `WorkflowEventType` source, require `details.clarificationId` on every `clarification.*` event.
- **OQ-5 superseded by P32.** The previous "WARN + mark every accepted clarification superseded with `payload_read_failed`" disposition is gone; the orchestrator now raises `DomainException(ARTIFACT_PAYLOAD_UNAVAILABLE)` so the outer `newVersion` transaction rolls back. The `payload_read_failed` vocabulary value is retained in `SUPERSEDED_REASONS` for future operator-action use, but the orchestrator no longer emits it.
- **API breaking changes (intentional, no out-of-tree callers):**
  - `ClarificationLifecycleService.markIncorporated(workflowRunPublicId, clarificationPublicId, newSpecArtifactPublicId, **int newSpecArtifactVersion**, actor)`.
  - `ClarificationLifecycleService.markSuperseded(workflowRunPublicId, clarificationPublicId, supersededByArtifactPublicId, **int supersededByArtifactVersion**, noEffectReason, actor)`.
  - `ClarificationLifecycleService` constructor dropped `ArtifactRecordPort` parameter.
  - `ArtifactOperationService` all four constructors + `withoutWorkflowRunStateGuard` factory take `ClarificationLifecycleOrchestrator` as final required parameter (Trap T7 — appended at end).
  - `WorkflowInspectionService.getClarificationsForArtifact(workflowRunPublicId, artifactPublicId)` — new run-scope parameter at position 1.
- **Closure cycle 2026-05-25 (former P1/P2 defer bucket resolved):**
  - **P1 (AC10 missing tests):** delivered the remaining verification surface with `WorkflowInspectionServiceClarificationStatusTest`, `ClarificationLifecycleServiceLoggingTest`, `ClarificationWritePersistenceAdapterLifecycleContractTest`, `ClarificationReadPersistenceAdapterContractTest` lifecycle/pending-count extensions, and a real-db clarification sweep regression in `ArtifactOperationServiceContractTest`. `WorkflowEventDetailKeysContractTest` remained auto-covered via the allow-listed-key/schema set comparison, and `RegistryContractTest` continued to auto-cover `ILLEGAL_CLARIFICATION_TRANSITION` via `DomainErrorCode.values()`.
  - **P2 (Trap T13 FK flush-ordering verification):** closed by equivalent Docker-backed coverage rather than a standalone IT name. `ClarificationWritePersistenceAdapterLifecycleContractTest.markIncorporatedResolvesWorkflowEventPublicIdToFkAndReadView` now proves the `workflow_events` row is persisted before the clarification row stores `incorporation_event_id`, and the end-to-end sweep path in `ArtifactOperationServiceContractTest` exercises the same ordering through the orchestrator.
  - **Important implementation correction:** the clarification sweep now runs from `ArtifactOperationService.markAvailable(...)`, not `newVersion(...)`. `newVersion(...)` persists a `PENDING` lineage row with no readable payload bytes and no pending operation row, so sweeping there cannot inspect rebuilt spec content. The contract coverage now pins the real `recordOperation(CREATE/UPDATE) -> markAvailable(...)` flow.
  - **Verification:** focused unit slice `ArtifactOperationServiceUnitTest, WorkflowInspectionServiceClarificationStatusTest, ClarificationLifecycleServiceLoggingTest, ClarificationLifecycleServiceTest` -> 50/0/0. Docker-backed contract slice `ArtifactOperationServiceContractTest, ClarificationReadPersistenceAdapterContractTest, ClarificationWritePersistenceAdapterLifecycleContractTest, ClarificationVisibleIncorporationContractTest, WorkflowCommandServiceContractTest` -> 20/0/0.
  - **Status:** the previously deferred AC10/Testcontainers gap is closed; story returns to `review`.
- **Verification:** focused Maven invocation (`ClarificationLifecycle*Test,ClarificationLifecycleOrchestratorTest,ClarificationVisibleIncorporationContractTest,WorkflowInspectionServiceClarificationTest,WorkflowInspectionServiceTest,ArtifactOperationServiceUnitTest,ArtifactLoggingContractTest`) → 73 tests, 0 failures. Full backend `test` → 462 tests, 6 errors all pre-existing `ContextBundleService*Test` failures from story 2.8 WIP (matches the prior debug-log baseline 437/0/6/3 + 12 net-new tests landed in this cycle). WSL2 Ubuntu native re-verify + JaCoCo gate (LINE 81.33% / BRANCH 62.74%) NOT re-run in this session — track with the deferred ITs.

### File List

Production sources (new):

- `deliveryline-backend/src/main/resources/db/migration/V9__add_clarification_lifecycle_columns.sql`
- `deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleOrchestrator.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleSnapshot.java`

Production sources (modified):

- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/ClarificationEntity.java` — five V9 column mappings + accessors.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ClarificationWritePersistenceAdapter.java` — four `mark*` method implementations + `requireRow` helper + `WorkflowEventRepository` dep.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ClarificationReadPersistenceAdapter.java` — `findLifecycleSnapshotByPublicId` + `countPendingByWorkflowRun` implementations.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/ClarificationEntityMapper.java` — `toLifecycleSnapshot` (resolves V9 fields + walks `incorporation_event_id` -> event details for `incorporatedIntoArtifactId`).
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/ClarificationRepository.java` — `countPendingByWorkflowRunPublicId` native query.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowEventRepository.java` — `findIdByPublicId` lookup.
- `deliveryline-backend/src/main/java/org/dradgo/application/clarification/spi/ClarificationWritePort.java` — four `mark*` methods + four payload records.
- `deliveryline-backend/src/main/java/org/dradgo/application/clarification/spi/ClarificationReadPort.java` — `countPendingByWorkflowRun` + `findLifecycleSnapshotByPublicId`.
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — `getClarificationStatus`, `getRunSummary`, `ClarificationStatusView`, `WorkflowRunDetailedSummaryView`, `WorkflowRunSummaryView.pendingClarifications`, `listRuns` enrichment.
- `deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactOperationService.java` — setter-injected orchestrator + SPEC-only sweep wired into `newVersion`.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowSummaryResponse.java` — Javadoc note for the deferred-to-2.13 `pendingClarifications` field.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` — new `ILLEGAL_CLARIFICATION_TRANSITION` mapping (409, retryable=false).
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` — `ILLEGAL_CLARIFICATION_TRANSITION` enum value.
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java` — four new allow-listed keys.
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json` — four new event-detail property declarations.

Tests (new):

- `deliveryline-backend/src/test/java/org/dradgo/application/clarification/ClarificationLifecycleServiceTest.java` — 10 Mockito tests.
- `deliveryline-backend/src/test/java/org/dradgo/contract/ClarificationVisibleIncorporationContractTest.java` — make-or-break contract + inline negative case.
- `deliveryline-backend/src/test/resources/fixture-event-streams/clarification-incorporated-happy-path.json` + `.md`.
- `deliveryline-backend/src/test/resources/fixture-event-streams/clarification-superseded-and-rejected.json` + `.md`.

Tests (modified):

- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` — V9 column/CHECK/FK/index assertions.
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` — `CLARIFICATION_LIFECYCLE_LIVES_IN_APPLICATION_CLARIFICATION` rule.
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` — wire the new rule.
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/ClarificationWritePersistenceAdapterTest.java` — constructor adds `WorkflowEventRepository` mock.
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` — new problem-type URI.
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` — lifecycle detail-key property declarations.

Review-batch dev cycle 2026-05-25 — additional touched files:

Production sources (modified):

- `deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactOperationService.java` — orchestrator field now `final` + constructor-injected (Trap T7 last-position); setter removed; null-guard dropped; `newVersion` reads pre-computed counts off `LifecycleSweepResult`.
- `deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleOrchestrator.java` — `LifecycleSweepResult` extended with `incorporatedCount`/`supersededCount` (P26); `loadSpecPayload` throws `DomainException(ARTIFACT_PAYLOAD_UNAVAILABLE)` instead of returning `Optional.empty()` (P32); P23 prefix validation at `sweepAfterSpecRebuild` entry; sweep loop passes `newSpecArtifactVersion` to mark methods (P9); OQ-5 javadoc updated.
- `deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleService.java` — `ArtifactRecordPort` dependency removed (P9); `markIncorporated`/`markSuperseded` signatures take explicit `int newSpecArtifactVersion`/`int supersededByArtifactVersion`; `assertTransition` returns `TransitionPhase` (P5 idempotent short-circuit) and routes terminal-state to `CLARIFICATION_TERMINAL_STATE` (P15); `requireControlledVocabularyReason` takes per-method subset (P14: `SUPERSEDED_REASONS`, `REJECTED_INVALID_REASONS`); `clarificationNotFound` is `static` for symmetry (P27); `incorporationArtifactMissing` helper removed.
- `deliveryline-backend/src/main/java/org/dradgo/application/clarification/spi/ClarificationReadPort.java` — new `findByPublicIdForUpdate(...)` method (P30).
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ClarificationReadPersistenceAdapter.java` — implements `findByPublicIdForUpdate` (P30; intentionally NOT `@Transactional(readOnly=true)` so the row lock is held under the outer caller's tx).
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/ClarificationRepository.java` — `findByPublicIdAndArchivedAtIsNullForUpdate(...)` `@Lock(PESSIMISTIC_WRITE)` query (P30).
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — `getClarificationsForArtifact` gains `workflowRunPublicId` + cross-run filter (P16); `getRunSummary` reads reordered so pending-count comes last (P28).
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java` — `CLARIFICATION_REPLAY_REF_SEPARATOR` switched from `|` to ASCII Unit Separator `U+001F` (P10).

Schemas (modified):

- `deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json` — `noEffectReason` constrained to the controlled vocabulary enum (P19).
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` — same enum constraint on `noEffectReason` (P19).

Tests (new):

- `deliveryline-backend/src/test/java/org/dradgo/application/clarification/ClarificationLifecycleOrchestratorTest.java` — 12 Mockito tests covering sweep happy / superseded / mixed / no-accepted / non-accepted-skipped paths + P32 fatal-abort branches (empty storageRef, missing artifact, empty payload) + P23 input validation + P29 word-boundary + P21 empty-questionId IAE.

Tests (modified):

- `deliveryline-backend/src/test/java/org/dradgo/application/clarification/ClarificationLifecycleServiceTest.java` — constructor drops `ArtifactRecordPort`; `mark*` callsites pass version int; `findByPublicId` → `findByPublicIdForUpdate` stubs (P30); test helper renamed `answeredRowWithRun` → `answeredRowForRun` / `answeredRowWithStatusAndRun` (P24); P14 per-method vocab subset test added (`markSupersededRejectsPmOnlyVocabularyToken`); INTERNAL_ERROR-on-missing-artifact test removed (path eliminated by P9).
- `deliveryline-backend/src/test/java/org/dradgo/application/artifact/ArtifactOperationServiceUnitTest.java` — every `new ArtifactOperationService(...)` + `withoutWorkflowRunStateGuard(...)` callsite gains `mock(ClarificationLifecycleOrchestrator.class)` as the new last argument; the single `newVersion`-exercising test stubs the mock to return a non-null empty `LifecycleSweepResult`.
- `deliveryline-backend/src/test/java/org/dradgo/application/artifact/ArtifactLoggingContractTest.java` — same factory-callsite update.
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceClarificationTest.java` — `getClarificationsForArtifact` call updated to pass `(RUN, ART)`.
- `deliveryline-backend/src/test/java/org/dradgo/contract/ClarificationVisibleIncorporationContractTest.java` — group-by composite scope key `(workflowRunId, clarificationId)`; transition-shape validation (state-machine ordering); `clarification.*` events MUST carry `details.clarificationId`; two new negative tests covering impossible transition shape + cross-run scope isolation.

Documentation / sprint tracking:

- `_bmad-output/implementation-artifacts/sprint-status.yaml` — dev-story start + completion entries; 2.12 flipped `ready-for-dev -> in-progress -> review` initially; review batch flipped `review -> in-progress -> review`; this closure cycle resolves the deferred AC10/Testcontainers work and keeps the story at `review`.
- `_bmad-output/implementation-artifacts/2-12-backend-visible-incorporation-lifecycle-states-and-event-wiring.md` — Status `in-progress -> review -> in-progress -> review`; all formerly deferred AC10/Testcontainers items are now resolved, and the Dev Agent Record now documents the `markAvailable(...)` clarification-sweep hook correction plus the passing Docker-backed verification slice.

### Review Findings

- [x] [Review][Patch] Terminal-state replay shortcut breaks the AC2 lifecycle contract [deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleService.java:402] — `assertTransition(...)` returns `ALREADY_AT_TARGET` before checking `row.isTerminal()`, so re-issuing `markIncorporated`, `markSuperseded`, or `markRejectedInvalid` on an already-terminal row now returns success instead of raising `ILLEGAL_CLARIFICATION_TRANSITION`. Story 2.12 AC2/AC10 explicitly require terminal rows and same-transition replays to reject with no row or event side effects.
- [x] [Review][Patch] Visible-incorporation contract test now accepts an illegal `accepted -> rejectedInvalid` chain [deliveryline-backend/src/test/java/org/dradgo/contract/ClarificationVisibleIncorporationContractTest.java:244] — the new shape-walk treats any `clarification.rejectedInvalid` as a valid closing terminal even after `clarification.accepted`, but AC2 only allows `answered -> rejected_invalid`. A malformed fixture/event stream can now pass the make-or-break AC5 contract test while violating the lifecycle state machine.
- [x] [Review][Patch] Clarification replay refs are no longer backward-compatible with persisted idempotency records [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:60] — the separator changed from `|` to `U+001F`, but the replay parser only accepts the new format (`clarificationReplayRunId` / `clarificationReplayState`) while completed `idempotency_records.result_ref` values are persisted in the database with indefinite retention support ([deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql:303](C:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql:303)). Replaying a pre-change `SubmitClarificationCommand` idempotency key will now fail as `Malformed clarification replay result reference` instead of returning the original result.
- [x] [Review][Patch] Zero-length spec payloads still bypass the fatal-abort path [deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleOrchestrator.java:214] вЂ” `loadSpecPayload(...)` documents empty payload bytes as `ARTIFACT_PAYLOAD_UNAVAILABLE`, but it only rejects `Optional.empty()` and then returns `bytes.get()` without checking `length == 0`. A readable-but-empty spec therefore falls through to the sweep loop and can supersede every accepted clarification as `clarification_not_addressed` instead of aborting the rebuild. The added tests only cover `Optional.empty()`, so this corruption path is currently unpinned.
- [x] [Review][Patch] Clarification sweep is not restricted to the rebuilt spec lineage [deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleOrchestrator.java:95] вЂ” AC4 scopes the sweep to accepted clarifications pinned to a prior version of the same spec lineage, but the orchestrator loads every clarification in the workflow run via `listByWorkflowRunId(...)`, filters only by `status == accepted`, and then marks each one incorporated/superseded against the new artifact id/version. A run containing accepted clarifications for a different spec branch or sibling artifact can therefore mutate the wrong clarification records and emit misleading lifecycle events.
- [x] [Review][Patch] Cross-run clarification probes now take a write lock before the leak guard rejects them [deliveryline-backend/src/main/java/org/dradgo/application/clarification/ClarificationLifecycleService.java:374] вЂ” the new pessimistic-lock path looks up rows by clarification id alone (`findByPublicIdAndArchivedAtIsNullForUpdate(...)`) and only checks `workflowRunId` after the row is locked. A stale or malicious request from run A that references a clarification from run B will now block the legitimate lifecycle transition on run B until the caller's transaction ends, even though the request is ultimately rejected as `CLARIFICATION_NOT_FOUND`.
