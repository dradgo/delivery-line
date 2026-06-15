# Story 1.5: Workflow State-Transition Table + WorkflowTransitionService

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a foundation developer,
I want a canonical workflow state-transition table (including runner-failure states) and a `WorkflowTransitionService` that atomically updates run state and appends the matching event,
so that every workflow state change passes through one validated path and Epic 3 does not have to reopen the transition table for real runner failures.

## Acceptance Criteria

1. **Given** the state registry, **Then** canonical states include `Inbox`, `Planned`, `Investigating`, `WaitingForSpecApproval`, `Executing`, `WaitingForReview`, `Completed`, `Failed`, `Paused`, `TakenOver`, `Reconciled` (terminal: `Completed`).
2. **Given** the transition table, **Then** allowed transitions include `Inbox->Planned`, `Planned->Investigating`, `Investigating->WaitingForSpecApproval`, `WaitingForSpecApproval->Executing`, `WaitingForSpecApproval->Investigating`, `Executing->WaitingForReview`, `Executing->Failed`, `Executing->Paused`, `Failed->Executing`, `Failed->Investigating`, `Paused->Executing`, `WaitingForReview->Completed`, `WaitingForReview->Executing`, `*->TakenOver`, `*->Reconciled` (where `*` covers any non-terminal state).
3. **Given** the transition table, **Then** runner-failure transitions explicitly include `Executing->Failed` with `failure_category` values `runner_timeout`, `runner_crash`, `runner_contract_violation`, `runner_non_zero_exit`.
4. **Given** `WorkflowTransitionService.transition(runId, targetState, actor, reason, idempotencyKey)`, **When** called with a valid transition, **Then** within a single PostgreSQL transaction the service updates `workflow_runs.current_state` and appends a `workflow_events` row with matching `prior_state`, `resulting_state`, `actor_identity`, `actor_type`, `created_at`, optional `reason`, and optional `intervention_marker`.
5. **Given** an invalid transition (source state not in the table's allowed transitions for the target), **When** attempted, **Then** the service throws a `DomainException` carrying stable code `ILLEGAL_TRANSITION`; no state or event row is written.
6. **Given** two concurrent transitions on the same run, **When** both commit, **Then** exactly one succeeds and the other fails with `CONCURRENT_TRANSITION_CONFLICT` (optimistic locking on a `version` column or equivalent).
7. **Given** contract tests, **Then** illegal transitions, duplicate transitions, replayed requests, out-of-order events, and conflicting concurrent updates are each proven to leave the run and event history consistent.
8. **Given** the architecture rule that no code path outside `WorkflowTransitionService` can mutate `workflow_runs.current_state`, **Then** story 1.11's ArchUnit test will enforce this (placeholder only here; actual ArchUnit enforcement lands in 1.11).

## Tasks / Subtasks

- [x] **Task 1: Publish the canonical transition table as code, not prose** (AC: 1, 2, 3, 5, 7)
  - [x] Create a single authoritative transition-rule type under `deliveryline-backend/src/main/java/org/dradgo/application/workflow/` (for example `WorkflowTransitionTable`, `WorkflowTransitionRules`, or equivalent) instead of scattering `if/else` rules across services, entities, or adapters.
  - [x] Encode the exact AC transition matrix, including wildcard `non-terminal -> TakenOver` and `non-terminal -> Reconciled` handling, while making `Completed` the only terminal state in this story.
  - [x] Resolve the story-1.4 deferred decision on missing states and non-runner failure categories explicitly in this story. Either keep the current `WorkflowState` and `FailureCategory` sets authoritative for Epic 1 and record that decision in completion notes, or extend them now with matching SQL/drift-test changes in the same diff.
  - [x] Keep the current `WorkflowState` registry aligned with the live V1 SQL CHECK values unless you intentionally expand the state model in the same change. Do not add speculative states such as `Queued`, `Retrying`, or `Superseded` without also updating SQL constraints, drift tests, and story notes.
  - [x] Model the `Executing -> Failed` path so runner failure categories are explicit and validated against the existing `FailureCategory` registry values needed now: `runner_timeout`, `runner_crash`, `runner_contract_violation`, `runner_non_zero_exit`.
  - [x] Define the invalid-transition contract once: source state, target state, run identifier, and failure category context (if any) should be surfaced in `DomainException.details()` for later REST/CLI mapping.

- [x] **Task 2: Introduce the persistence and concurrency seam the service needs** (AC: 4, 6, 7, 8)
  - [x] Create the first real workflow persistence types under the backend's actual package layout, not the architecture doc's shorthand `backend/` tree. Expected homes are `org.dradgo.adapters.persistence.entity` and `org.dradgo.adapters.persistence.repository` (create them if absent).
  - [x] Implement `WorkflowRunEntity` and `WorkflowEventEntity` against the live schema from `V1__create_workflow_core_tables.sql`; use the actual column names `actor_identity` and `created_at` rather than the epic text's stale `actor_id` / `timestamp` phrasing.
  - [x] Add the persistence mapping for registry-backed fields through the existing story-1.4 parser path (`WorkflowState`, `ActorType`, `FailureCategory`, `WorkflowEventType`) so unknown DB values still fail fast with stable domain codes.
  - [x] Make `workflow_events.event_type` persist through the `WorkflowEventType` registry path rather than as free-form text, and keep serialization/parsing aligned with story 1.4's fail-fast registry contract.
  - [x] Generate `workflow_events.public_id` through the existing public-ID discipline (`evt_` prefix, uniqueness, validation) instead of ad hoc string assembly in controllers, tests, or repositories.
  - [x] Resolve AC6's concurrency requirement explicitly. Preferred path: add a Flyway migration introducing `workflow_runs.version bigint not null default 0` and map it with JPA optimistic locking (`@Version` or equivalent compare-and-swap semantics). If you choose a different equivalent strategy, document the reason and prove the same failure contract in tests.
  - [x] If a new Flyway migration is required here, **do not** claim `V2__...`: Epic 2 story 2.11 already reserves `V2__add_spec_loop_and_clarifications.sql`. Use an earlier sortable version such as `V1_1__...` (Flyway dotted/underscore version notation is valid) so the later story keeps its reserved filename.

- [x] **Task 3: Implement \****`WorkflowTransitionService`**\*\* as the only state mutation path** (AC: 4, 5, 6, 8)
  - [x] Add `WorkflowTransitionService` under `deliveryline-backend/src/main/java/org/dradgo/application/workflow/` and make it the sole class responsible for mutating `workflow_runs.current_state`.
  - [x] Wrap the transition in one transaction (`@Transactional` or equivalent) that loads the run, validates the transition table, updates `current_state`, and appends exactly one `workflow_events` row before commit.
  - [x] Use the already-registered `WorkflowEventType.WORKFLOW_STATE_CHANGED` as the generic event type for this story unless a narrower type is required by the acceptance criteria. Do not invent new event-type strings outside the registry.
  - [x] Ensure `Executing -> Failed` requires an allowed runner failure category and persists it on the event row. Non-failure transitions should not write irrelevant failure metadata.
  - [x] Treat `TakenOver` and `Reconciled` as explicit intervention transitions: require `intervention_marker=true` and a non-empty `reason` when moving into either state.
  - [x] Map invalid transitions to `DomainErrorCode.ILLEGAL_TRANSITION` and concurrent-write failures to `DomainErrorCode.CONCURRENT_TRANSITION_CONFLICT` with machine-readable details. A failed transition must leave both `workflow_runs` and `workflow_events` unchanged.

- [x] **Task 4: Keep idempotency compatible with story 1.9 without re-implementing it here** (AC: 4, 7)
  - [x] Keep `idempotencyKey` in the service API and request/command shape because later command stories depend on it.
  - [x] Do **not** build the full `IdempotencyService` here; story 1.9 owns `idempotency_records` reservation, replay, and conflict semantics across CLI and REST.
  - [x] For this story's replay/duplicate coverage, use the narrower contract only: repeated or stale transition attempts against the same logical request must not produce a second state mutation or a second `workflow_events` row. The implementation may reject duplicates or no-op them, but it must not claim or persist general-purpose `idempotency_records` behavior yet.
  - [x] If a minimal local seam is needed for tests, keep it transition-scoped and easy to replace with story 1.9's authoritative service rather than spreading ad hoc idempotency logic through repositories or controllers.

- [x] **Task 5: Add contract and concurrency tests that make the state machine hard to bypass** (AC: 5, 6, 7, 8)
  - [x] Add focused unit tests for the transition table itself: every allowed transition passes, every disallowed transition fails, `Completed` has no outbound transitions, and wildcard transitions only apply to non-terminal states.
  - [x] Add PostgreSQL-backed integration/contract tests that prove atomic state+event writes, invalid-transition rollback, concurrency conflict behavior, and `Executing -> Failed` runner-failure-category handling.
  - [x] Reuse the existing `TestcontainersConfiguration` / contract-test style rather than inventing a second container harness. Prefer focused JPA/JDBC tests over broad `@SpringBootTest` where possible, and if a Spring context test is unavoidable, configure it so Docker Compose startup is not a hidden prerequisite of the test.
  - [x] Make the concurrency test real: two threads or transactions race the same run, and the test proves one success plus one `CONCURRENT_TRANSITION_CONFLICT`, not "last writer wins".
  - [x] Leave explicit room for story 1.11 to add ArchUnit enforcement later; do not try to satisfy AC8 with manual code review alone.

## Dev Notes

This story is the first real workflow-engine slice. It is not "just an enum matrix." It establishes the only legal path for lifecycle mutation, the first transactional application service, and the first concurrency contract other stories will depend on. If it is vague, later approval, runner, recovery, and inspection stories will encode incompatible assumptions.

**Current repo state**
- `deliveryline-backend` is still mostly foundation code. There is no existing `application.workflow` package, no persistence entity layer, and no workflow service yet.
- Story 1.4 already shipped the authoritative registries and parser helpers in `org.dradgo.domain.registry`, including `WorkflowState`, `FailureCategory`, `WorkflowEventType`, `DomainErrorCode`, and `PersistedRegistryValues`.
- The live schema is still only `V1__create_workflow_core_tables.sql`; `workflow_runs` currently has no explicit optimistic-lock column.
- The existing backend test harness already uses PostgreSQL Testcontainers through `deliveryline-backend/src/test/java/org/dradgo/TestcontainersConfiguration.java` and contract tests under `org.dradgo.contract`.

**Do**
- Implement against the live schema names `actor_identity` and `created_at` unless you are deliberately shipping a migration.
- Prove deterministic concurrency behavior; if optimistic locking is used, add the required column and tests in the same story.
- Keep `idempotencyKey` in the service contract so story 1.9 can take over cleanly later.
- Reuse the story-1.4 registry and public-ID discipline for `WorkflowState`, `FailureCategory`, `WorkflowEventType`, and `evt_` public IDs.
- Record an explicit decision if Epic 1 keeps the current state and failure-category vocabulary unchanged.

**Do not**
- Build a general-purpose idempotency subsystem in this story.
- Add states or failure categories in Java without matching SQL/drift-test updates.
- Invent transient lifecycle states such as `Reconciling` or `TakingOver` ad hoc; if they are needed, record the decision in `_bmad-output/implementation-artifacts/deferred-work.md`.

**Exact file targets**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java` (or equivalent authoritative rule type)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowRunEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowEventEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowEventRepository.java`
- `deliveryline-backend/src/main/resources/db/migration/` for any schema addition needed to satisfy AC6
- `deliveryline-backend/src/test/java/org/dradgo/contract/` for PostgreSQL-backed workflow-transition contract tests
- `deliveryline-backend/src/test/java/org/dradgo/...` for fast unit tests over the pure transition table

**Testing expectations**
- Prefer one fast transition-matrix test suite plus one focused PostgreSQL-backed service/persistence suite. Do not hide all validation behind a single heavyweight context boot.
- Reuse the current registry contract surface from story 1.4. If the state list changes, both `RegistryContractTest` and `FlywaySchemaContractTest` must change in the same diff.
- Add a true rollback assertion for invalid transitions: row count in `workflow_events` must remain unchanged and `workflow_runs.current_state` must remain at the prior value.
- Add at least one out-of-order or duplicate-attempt test proving the append-only event history stays sane when a stale caller retries after another transition already committed.
- Be deliberate about test bootstrapping. The repo's local profile uses Docker Compose `start-only`, and deferred work from story 1.2 already flagged that broad `@SpringBootTest` can drag Compose behavior into tests before test-specific datasource settings exist.

**Previous story intelligence**
- Story 1.4 deliberately deferred missing extra states and non-runner failure categories to this story. Decide explicitly whether they remain deferred or become real now; do not leave the question implicit.
- Story 1.4 also established the fail-fast parsing pattern. Use that same pattern for workflow run/event entity mapping so persistence failures remain consistent.
- Story 1.3's schema work already proved the project values PostgreSQL-backed contract tests and explicit Flyway ownership over "magic" JPA defaults. Follow that pattern here.

**Git intelligence summary**
- Recent commits are still the three foundation-slice commits: `DL-2 initial database schema version`, `DL-1 initial docker compose version`, and `DL-0 fixes after initial review`.
- That pattern matters: keep this story narrow, contract-first, and reviewable. Avoid speculative scaffolding for later approval, runner, or recovery work.

**Current official-docs specifics to follow**
- Stay on Spring Boot `4.0.6`; do not introduce dependency-version churn inside this story.
- Use standard Spring transactional behavior as the baseline unless a failing test proves stronger settings are required.
- If you choose repository-level locking instead of a version column, use Spring Data JPA / PostgreSQL locking primitives intentionally and prove the conflict behavior in tests.
- If you add a migration before Epic 2, use a sortable pre-`V2` version such as `V1.1__...` / `V1_1__...` rather than taking story 2.11's reserved `V2__...` filename.

### Project Structure Notes

- The architecture document still uses shorthand `backend/` examples, but the live module name is `deliveryline-backend`.
- `org.dradgo.domain.registry` already exists and should remain the authority for enumerable workflow values.
- `org.dradgo.application` and `org.dradgo.adapters` exist only as placeholders today; this story is expected to create the first concrete `workflow` and persistence classes beneath them.
- There is still no frontend or runner implementation code that depends on this service yet, so keep the public contract minimal and foundation-focused.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.5 acceptance criteria and Epic 1 foundation-gate rules]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - service-boundary rule that `WorkflowTransitionService` is the only state-transition path]
- [Source: `_bmad-output/planning-artifacts/implementation-spec-2026-04-20-agent-orchestration.md` - explicit state-transition table and state/event persistence expectations]
- [Source: `_bmad-output/implementation-artifacts/1-4-central-registries-with-drift-tests.md` - registry ownership, deferred state-machine decisions, and parser guardrails]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` - deferred state/failure-category questions and test-bootstrapping cautions]
- [Source: `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql` - live workflow run/event schema, state CHECK constraints, and actual column names]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java` - current authoritative workflow state registry]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java` - current authoritative failure-category registry]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java` - current authoritative workflow event-type registry]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/DomainException.java` - stable domain-exception surface for `ILLEGAL_TRANSITION` and concurrency errors]
- [Source: `deliveryline-backend/src/test/java/org/dradgo/TestcontainersConfiguration.java` - existing PostgreSQL Testcontainers harness]
- [Source: `https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html` - current `@Transactional` defaults]
- [Source: `https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html` - current Spring Data JPA locking guidance]
- [Source: `https://www.postgresql.org/docs/current/explicit-locking.html` - current PostgreSQL row-lock semantics]
- [Source: `https://documentation.red-gate.com/fd/versioned-migrations-273973333.html` - Flyway versioned migration naming and ordering]

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Auto-selected next backlog story from `_bmad-output/implementation-artifacts/sprint-status.yaml`: `1-5-workflow-state-transition-table-and-workflow-transition-service`
- Loaded project config from `_bmad/bmm/config.yaml` and planning artifacts from `_bmad-output/planning-artifacts/`
- Reviewed Epic 1 story 1.5, the architecture service-boundary section, the implementation spec, the prior story `1-4-central-registries-with-drift-tests.md`, and the deferred-work notes
- Inspected the live backend module, V1 Flyway schema, current registries, `DomainException`, and the existing PostgreSQL Testcontainers contract-test harness
- Checked current official references for Spring transactions, Spring Data JPA locking, PostgreSQL explicit locking, and Flyway versioned migration naming
- Added red-phase tests first: `WorkflowTransitionTableTest` and `WorkflowTransitionServiceContractTest`, then implemented the minimum production slice to satisfy them
- Verified local compile/test progression with `mvn -pl deliveryline-backend -DskipTests test-compile`, targeted red/green test runs, and final full-module regression `mvn -pl deliveryline-backend test`

### Completion Notes List

- Implemented `WorkflowTransitionTable` as the single canonical rule source, including non-terminal wildcard transitions to `TakenOver` and `Reconciled`, `Completed` as the only terminal state, and stable `ILLEGAL_TRANSITION` detail payloads
- Implemented the first workflow persistence seam with `WorkflowRunEntity`, `WorkflowEventEntity`, repositories, registry-backed parsing for persisted values, and `evt_` public ID generation through the existing prefix discipline
- Added `V1_1__add_workflow_run_version.sql` plus JPA `@Version` mapping and service-level conflict translation so concurrent transitions fail with `CONCURRENT_TRANSITION_CONFLICT`
- Implemented `WorkflowTransitionService` as the only state mutation path, performing atomic run-state update plus event append and enforcing intervention and runner-failure metadata contracts
- Kept the current `WorkflowState` and `FailureCategory` registries authoritative for Epic 1; this story narrows `Executing -> Failed` to the four runner failure categories required now without introducing speculative new values
- Preserved story 1.9 ownership of general idempotency by keeping `idempotencyKey` in the service API while handling replay/stale attempts through the narrower "no second mutation/event" contract only
- Added focused unit and PostgreSQL-backed contract coverage for allowed/disallowed transitions, rollback, duplicate attempts, concurrency conflicts, and failure-category persistence; `mvn -pl deliveryline-backend test` passed with `40` tests, `0` failures, `0` errors
- **Idempotency seam deferred to story 1.9 (****`IdempotencyService`****)**: this story keeps `idempotencyKey` in the service API and writes it into `workflow_events.details` for traceability, but does not implement DB-level uniqueness on `(workflow_run_id, idempotency_key)` or a pre-insert dedup lookup. The associated contract test was renamed to `staleSourceStateRejectsReplayWithIllegalTransition` to honestly reflect that it proves state-machine rejection of stale source replays, not idempotency-key dedup. Tracked in `deferred-work.md`.

### File List

- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowRunEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowEventEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowEventRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java`
- `deliveryline-backend/src/main/resources/db/migration/V1_1__add_workflow_run_version.sql`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowTransitionTableTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/WorkflowTransitionServiceContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java`
- `_bmad-output/implementation-artifacts/1-5-workflow-state-transition-table-and-workflow-transition-service.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Review Findings

*Code review run on 2026-04-28 — Blind Hunter + Edge Case Hunter + Acceptance Auditor; 14 actionable findings, 7 deferred, 7 dismissed as noise.*

**Decision-needed (resolved 2026-04-28)**

- [x] [Review][Decision→Patch] Idempotency key not enforced — Resolved: defer real per-run idempotency to story 1.9 (`IdempotencyService`); rename the misleading test and add an explicit deferral note. New patch rows added below; deferred-work.md updated.
- [x] [Review][Decision→Patch] Archived runs are still transitionable — Resolved: add an `archivedAt`-non-null guard in `WorkflowTransitionService.doTransition`, throw `DomainException` with a stable code, add a contract test. New patch row added below.
- [x] [Review][Decision→Patch] Transition table allows intervention-state outbound + self-loops not in AC2 — Resolved: remove the non-AC edges and update `WorkflowTransitionTableTest` to assert they are rejected. New patch row added below.

<!-- ORIGINAL DECISION DETAIL (kept for traceability)
- Idempotency key not enforced; replay protection absent — The service writes `idempotencyKey` only into `event.details` JSON (`WorkflowTransitionService.java:107`); there is no DB-level uniqueness on `(workflow_run_id, idempotency_key)`, no lookup, no dedupe, no `idempotency_records` reservation. The contract test `staleOrRepeatedTransitionAttemptsDoNotAppendASecondEvent` only passes because the second call's source state has moved on (state-machine rejection), not because the idempotency key was previously seen. AC4+AC7 + Task 4 sub-bullet 3 require that "repeated or stale transition attempts against the same logical request must not produce a second state mutation or a second `workflow_events` row." A replay against a self-loop transition (e.g., `TAKEN_OVER → TAKEN_OVER` with same key) would currently append duplicates. **Decision: implement narrow per-run idempotency check (e.g., select existing event for run+key before insert), or document the deferral explicitly and reshape the test name to match what is actually proven.**
- [ ] [Review][Decision] Archived runs are still transitionable — `WorkflowTransitionService.doTransition` (lines 87–95) loads the entity and never inspects `archivedAt`. Per V1 schema, archived runs are a supported retention concept; nothing prevents the service from mutating `current_state` and appending events against a logically retired run. The story spec does not explicitly state semantics for transitioning archived runs. **Decision: add \****`archivedAt`**\*\*-non-null guard rejecting with a stable domain code, or document that archived runs remain mutable in Epic 1 and defer to retention work.**
- [ ] [Review][Decision] Transition table allows intervention-state outbound + self-loops not in AC2 — `WorkflowTransitionTable.defaultTable():49-50` allows `TAKEN_OVER → {TAKEN_OVER, RECONCILED}` and `RECONCILED → {TAKEN_OVER, RECONCILED}`. AC2 lists wildcard `*->TakenOver`/`*->Reconciled` only "where `*` covers any non-terminal state" and never lists outbound transitions FROM these intervention states. The test `WorkflowTransitionTableTest:733-736` re-asserts these new edges as canonical. Implementer added rules beyond AC; intent unclear (loop tolerance for re-intervention vs. stricter "intervention is terminal-ish"). **Decision: keep these edges and record the deviation in completion notes, or remove them to match AC2 exactly.**

**Patch (results — applied 2026-04-28)**

- [x] [Review][Patch] Defer narrow idempotency to story 1.9; rename misleading test (resolves Decision D1) — Test `staleOrRepeatedTransitionAttemptsDoNotAppendASecondEvent` renamed to `staleSourceStateRejectsReplayWithIllegalTransition`; Completion Notes updated; `deferred-work.md` records the per-run idempotency seam as story 1.9 territory.
- [x] [Review][Patch] Reject transitions on archived runs (resolves Decision D2) — `WorkflowTransitionService.doTransition` now guards against `archivedAt != null`, throwing `DomainException(ILLEGAL_TRANSITION, reason=run_archived)`. New contract test `transitionsAgainstArchivedRunsAreRejectedAndLeaveStateUntouched`.
- [x] [Review][Patch] Remove non-AC outbound edges from intervention states (resolves Decision D3) — `TAKEN_OVER` and `RECONCILED` now have empty allowed-target sets in `WorkflowTransitionTable`; `WorkflowTransitionTableTest` updated; new `interventionStatesHaveNoOutboundTransitions` test asserts the constraint.
- [x] [Review][Patch] `@Version` field has no initializer — `WorkflowRunEntity.java:37` initialized to `0L`.
- [x] [Review][Patch] `failureCategory` silently dropped when `targetState ≠ FAILED` — `WorkflowTransitionTable.assertTransitionAllowed` now rejects non-null `failureCategory` outside `EXECUTING → FAILED` with reason `failure_category_only_valid_for_executing_to_failed`. Service no longer silently nulls.
- [x] [Review][Patch] `actor.identity` accepts blank/whitespace strings — `TransitionActor` compact constructor rejects blank identity with `IllegalArgumentException`.
- [x] [Review][Patch] `idempotencyKey` empty/blank not rejected — `WorkflowTransitionService.transition` rejects blank key with `IllegalArgumentException`.
- [x] [Review][Patch] Oversized `idempotencyKey` blows `ck_workflow_events_details_size` mid-flush — `WorkflowTransitionService` now bounds `idempotencyKey` to 256 chars at the boundary; longer keys raise `IllegalArgumentException` before any DB write.
- [x] [Review][Patch] `WorkflowEventEntity.setDetails(null)` is a footgun — setter wrapped with `Objects.requireNonNull`.
- [ ] [Review][Patch] **(skipped — needs judgment)** `WorkflowTransitionConcurrencyProbe` leaks a test-only seam into the production interface — moving the probe to a package-private/test-config seam (or replacing with an aspect) is a non-trivial API change deferred to a focused follow-up.
- [x] [Review][Patch] Transition-table tests don't iterate disallowed cartesian — added `everyDisallowedTransitionIsRejectedWithIllegalTransition` driving the full `state × state` cartesian minus allowed pairs.
- [x] [Review][Patch] Concurrent test inspects `failures.get(0)` blindly with cast — replaced with `assertInstanceOf(DomainException.class, cause, ...)`.
- [x] [Review][Patch] Concurrent test asserts disjunction without cross-checking event/run consistency — added cross-check that the surviving event's `resulting_state` equals the run's `current_state`.
- [x] [Review][Patch] `WorkflowTransitionTable` rejects unknown source via misleading "target_not_allowed" — added construction-time `assertCoversAllStates` that fails fast if `WorkflowState.values()` aren't all keys in the rules map.

**Verification:** `mvn -pl deliveryline-backend test` → `Tests run: 44, Failures: 0, Errors: 0, Skipped: 0` (was 40 before; +4 new tests).

**Deferred**

- [x] [Review][Defer] Optimistic-lock catch may miss commit-time `TransactionSystemException` [`WorkflowTransitionService.java:74`] — theoretical with current `saveAndFlush` path; tests pass deterministically. Hardening: also catch `TransactionSystemException` whose root cause is `ObjectOptimisticLockingFailureException`, or assert the wrapping is stable.
- [x] [Review][Defer] `WorkflowEventEntity.getDetails()` exposes the live mutable map [`WorkflowEventEntity.java:174`] — service relies on `getDetails().put(...)`. Refactor to immutable view + `addDetail(key, value)` would couple multiple files.
- [x] [Review][Defer] Event public id is non-deterministic on `idempotencyKey` [`WorkflowTransitionService.java:235`] — tied to the idempotency decision; if narrow idempotency lands, deterministic id is the natural follow-up.
- [x] [Review][Defer] `WorkflowRunEntity.createdAt` is `insertable=false` and stale post-insert [`WorkflowRunEntity.java`] — entity is not used to insert in this story; refresh-after-insert needed only when the entity drives writes.
- [x] [Review][Defer] In-memory `workflowRun.setCurrentState` mutation precedes flush [`WorkflowTransitionService.java:94-95`] — on rollback the in-memory entity carries the unpersisted target state. Callers do not currently re-use the entity after exception; defensive refresh would be architectural.
- [x] [Review][Defer] `OffsetDateTime.now(ZoneOffset.UTC)` collisions break event ordering at sub-millisecond resolution [`WorkflowTransitionService.java:229`] — needs DB-level monotonic sequence on `workflow_events` (added to deferred-work; revisit when ordering becomes load-bearing).
- [x] [Review][Defer] `runId` empty string yields `RUN_NOT_FOUND` rather than `INVALID_ARGUMENT` [`WorkflowTransitionService.java:66`] — diagnostic muddiness only.

### Change Log

- 2026-04-28: Implemented workflow transition table, persistence seam, optimistic-lock version migration, `WorkflowTransitionService`, and the accompanying unit/contract regression coverage
- 2026-04-28: Code review run — 14 actionable findings recorded (3 decision-needed, 11 patch, 7 deferred, 7 dismissed)
