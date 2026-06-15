# Story 1.3: Flyway V1 Core Schema with Retention and Measurement Columns

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **foundation developer**,
I want **Flyway-managed V1 schema creating all nine core tables with retention and measurement-capture columns from day one**,
so that **subsequent stories can persist domain data without reopening the schema mid-flight**.

## Acceptance Criteria

1. **Given** an empty PostgreSQL database and the `local` profile, **When** the backend starts, **Then** Flyway applies `V1__create_workflow_core_tables.sql` creating tables `workflow_runs`, `workflow_events`, `artifacts`, `artifact_operations`, `approvals`, `runner_executions`, `integration_links`, `recovery_actions`, and `idempotency_records`.
2. **Given** each table, **Then** primary keys are `bigserial` and a separate `public_id text NOT NULL` column carries the readable prefixed identifier per the prefix registry (`run_`, `evt_`, `art_`, `op_`, `apr_`, `rex_`, `ilk_`, `rcv_`, `idm_`) with a UNIQUE constraint and a format CHECK (`~ '^<prefix>_[A-Za-z0-9_-]{4,64}$'`). _**[Amended 2026-04-27 by code-review decision: bigserial PK + text public\_id, replacing the prior text-PK shape. Architecture, story 1.4 prefix registry, and downstream stories 1.9/1.12/1.13/1.14 must be updated to reference \****`id bigint`***\* for joins and \****`public_id`**\*\* for human-readable lookups.]**_
3. **Given** every row-producing table, **Then** `created_at timestamptz NOT NULL DEFAULT now()` and `archived_at timestamptz NULL` columns exist to support later retention enforcement (Epic 5) without schema migration.
4. **Given** the `workflow_events` table, **Then** a `stage_duration_ms bigint NULL` column exists for stage-transition events (AR34a cycle-time capture) and a `rejection_taxonomy text NULL` column exists with a CHECK constraint limiting values to `missing_scope`, `unclear_specification`, `misunderstood_implementation`, or `NULL` (AR34a rework-rate capture).
5. **Given** the workflow state registry, **Then** `workflow_runs.current_state text NOT NULL` has a CHECK constraint enforcing values from the state registry (story 1.4 populates the registry; the CHECK is added here using the canonical state string list).
6. **Given** architecture naming conventions, **Then** foreign keys use `fk_{table}_{referenced_table}`, unique constraints use `uq_{table}_{columns}`, indexes use `idx_{table}_{columns}`, and check constraints use `ck_{table}_{meaning}`. Where a derived name exceeds the PostgreSQL 63-byte identifier limit, the truncation is documented inline in the SQL via `--` comments at the constraint site (per code-review patch 2026-04-27). Every FK declares an explicit `ON DELETE` action: `RESTRICT` for audit-critical references (workflow_runs, event chain), `SET NULL` for soft references (`artifacts.parent_artifact_id`, `recovery_actions.{triggering,resulting}_event_id`).
7. **Given** all timestamp columns, **Then** they use `timestamptz` - never `timestamp without time zone`.
8. **Given** all enum-like columns, **Then** they are persisted as `text` with CHECK constraints - never as ordinals or Postgres enums.
9. **Given** migration replay, **When** Flyway runs twice against the same DB, **Then** the second run is a no-op (no checksum mismatch, no errors).
10. **Given** a deliberately broken `V1` migration in a throwaway branch, **When** the app starts, **Then** startup fails fast with a Flyway validation error - the app does not partially start with uncertain schema state.

## Tasks / Subtasks

- [x] **Task 1: Create the V1 Flyway SQL migration in the canonical location** (AC: 1, 6, 7, 8, 9)
  - [x] Add `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql`.
  - [x] Keep all schema creation in Flyway SQL; do **not** add `schema.sql`, `data.sql`, or Hibernate auto-DDL shortcuts.
  - [x] Use explicit, reviewable DDL with named PK, FK, UNIQUE, CHECK, and INDEX objects that follow the architecture naming rules.
  - [x] Verify the migration is idempotent in the Flyway sense: first run applies V1, second run is a no-op with identical checksum.

- [x] **Task 2: Model the nine core tables with forward-compatible columns** (AC: 1, 2, 3, 4, 5, 7, 8)
  - [x] Create all nine required tables: `workflow_runs`, `workflow_events`, `artifacts`, `artifact_operations`, `approvals`, `runner_executions`, `integration_links`, `recovery_actions`, `idempotency_records`.
  - [x] Give every table a `text` primary key using the story-defined prefix family (`run_`, `evt_`, `art_`, `op_`, `apr_`, `rex_`, `ilk_`, `rcv_`, `idm_`).
  - [x] Add `created_at timestamptz not null default now()` and `archived_at timestamptz null` everywhere rows are retained for audit or lifecycle management.
  - [x] Persist enum-like values as `text` plus CHECK constraints; never use numeric ordinals or PostgreSQL enum types.
  - [x] Add `workflow_runs.current_state text not null` with a CHECK using the canonical state list from story 1.5: `Inbox`, `Planned`, `Investigating`, `WaitingForSpecApproval`, `Executing`, `WaitingForReview`, `Completed`, `Failed`, `Paused`, `TakenOver`, `Reconciled`.
  - [x] Add `workflow_events.stage_duration_ms bigint null` and `workflow_events.rejection_taxonomy text null` with the PM taxonomy CHECK (`missing_scope`, `unclear_specification`, `misunderstood_implementation`).

- [x] **Task 3: Include the table columns that later stories already assume exist in V1** (AC: 1, 3, 4, 5, 8)
  - [x] `workflow_events`: include at minimum fields for `workflow_run_id`, `event_type`, `prior_state`, `resulting_state`, `actor_identity`, `actor_type`, optional `reviewer_role`, optional `reason`, optional `failure_category`, optional `intervention_marker`, optional bounded `details` JSON payload, plus the AR34a measurement columns.
  - [x] `artifacts`: include the exact baseline expected by story 1.12: `workflow_run_id`, `artifact_type`, `version`, nullable `parent_artifact_id`, `classification`, `storage_ref`, `checksum_algorithm`, `checksum_value`, `status`, `linked_event_id`, timestamps.
  - [x] `artifact_operations`: include the baseline expected by story 1.12: `workflow_run_id`, `artifact_id`, `linked_event_id`, `operation_type`, `status`, `idempotency_key`, plus failure/reason fields if needed for orphan or late/stale handling.
  - [x] `approvals`: include the baseline expected by stories 2.9 and 2.10: `workflow_run_id`, `artifact_id`, `artifact_version`, `context_bundle_version`, `actor_identity`, `actor_type`, `reviewer_role`, `decision`, `reason`, `rejection_taxonomy`, `decided_at`, `idempotency_key`.
  - [x] `runner_executions`: include the baseline expected by story 1.13 and later Epic 3 work: `workflow_run_id`, `stage`, `status`, `context_bundle_version` or stable context reference, `last_activity_at`, timeout/deadline field, optional `failure_category`, and completion timestamps; leave raw-output and queue-specific columns for their later migrations.
  - [x] `integration_links`: include the baseline expected by story 1.14 and story 3.15: `workflow_run_id`, `integration_type`, `external_ref`, bounded `external_metadata` JSONB, `last_sync_at`, `sync_status`, and any idempotency/classification fields needed to keep later migrations small.
  - [x] `recovery_actions`: include the baseline expected by story 1.18 and story 3.22: `workflow_run_id`, `action_type`, `triggering_event_id`, `resulting_event_id`, `actor_identity`, optional `actor_type`, optional `reviewer_role`, `idempotency_key`, `result_status`, timestamps.
  - [x] `idempotency_records`: include the baseline expected by story 1.9: `key` (unique), `command_type`, `actor_identity`, `command_fingerprint`, `status`, `result_ref`, `completed_at`, alongside the required `idm_` primary key.

- [x] **Task 4: Wire test-time migration verification against PostgreSQL** (AC: 1, 9, 10)
  - [x] Re-enable `deliveryline-backend/src/test/java/org/dradgo/DeliveryLineApplicationTests.java` once the schema can boot successfully through Flyway.
  - [x] Keep using `TestcontainersConfiguration` and the existing `postgres:17` service-connection pattern for migration tests.
  - [x] Add focused schema/migration tests under a persistence/contract-oriented test package rather than burying Flyway assertions inside unrelated tests.
  - [x] Add one test that boots the app against a fresh PostgreSQL container and asserts the schema is present after startup.
  - [x] Add one replay test that runs Flyway twice against the same database and proves the second invocation is a no-op.
  - [x] Add one failure-path test that runs Flyway against a deliberately malformed migration set in isolated test resources and asserts startup/migration aborts before partial application is treated as usable.

- [x] **Task 5: Keep the story inside the schema boundary** (AC: 1-10)
  - [x] Do **not** implement story 1.4 registries, story 1.5 transition logic, story 1.9 idempotency service behavior, story 1.12 artifact services, or story 1.13 runner broker behavior here.
  - [x] Do **not** add JPA entities/repositories unless they are the minimum needed to support migration verification; V1 is primarily a schema story.
  - [x] Do **not** widen the schema into V2/V3 concerns such as `clarifications`, spec-loop counters, raw runner log references, queue columns, or batch submissions. Keep those for their documented later migrations.
  - [x] Document any unavoidable design choice that could constrain later migrations inside the story's completion notes or ADR-style comments in the SQL.

## Dev Notes

This story is the first real persistence contract story. It is not just "make tables exist." It defines the durable vocabulary that stories 1.4 through Epic 5 build on. A weak or underspecified V1 will force immediate churn across approvals, artifact lineage, runner execution, recovery, and pilot measurement.

**Current repo state**
- `deliveryline-backend/src/main/resources/db/migration/` already exists but is empty. This story owns the first migration file in that directory.
- `DeliveryLineApplicationTests` is currently disabled specifically until story 1.3 lands.
- `TestcontainersConfiguration` already provides a PostgreSQL 17 container via `@ServiceConnection`; reuse that instead of inventing a second test-DB pattern.
- `application-local.yml` already uses Spring Boot Docker Compose discovery for local development. Test-time migration verification should rely on Testcontainers, not on the root compose file.

**Critical scope discipline**
- Use Flyway as the **only** schema mechanism. The official Spring Boot guidance recommends using Flyway alone rather than mixing it with `schema.sql` / `data.sql`.
- Do not use Hibernate auto-DDL to create or evolve tables. The architecture explicitly rejects Hibernate as the schema source of truth.
- Do not delay retention columns or AR34a measurement capture. PRD success metrics depend on data existing from run #1; retrofitting later would make Epic 5 reporting incomplete by definition.
- Do not overreach into service-layer behavior. This story establishes storage contracts and migration verification, not the full domain/application implementation.

**Schema guardrails by table**
- `workflow_runs` should stay lean but durable. At minimum it must anchor workflow identity and current state. Avoid speculative summary columns unless they clearly reduce future churn and are documented.
- `workflow_events` is the append-only audit log. Favor explicit event context (`prior_state`, `resulting_state`, actor/reviewer/failure/reason metadata, bounded `details`) over opaque blobs. This table is the right place for AR34a event timing capture.
- `artifacts` and `artifact_operations` must support the outbox-style pattern that story 1.12 assumes: metadata in Postgres first, payload file write second, availability gated on checksum + readable storage reference.
- `approvals` must already support version binding and rejection measurement. Even though technical approvals come later, avoid forcing a disruptive shape change by omitting `context_bundle_version`, `reviewer_role`, or `rejection_taxonomy`.
- `runner_executions` must support story 1.13's broker baseline without pre-implementing Epic 3 queue/log features. Include the status + timeout + last-activity + context reference core, and leave raw-output / queue / batch columns to their later migrations.
- `integration_links` should use explicit link rows, not denormalized ticket or PR columns scattered across other tables. Bounded `external_metadata` JSONB is acceptable here because the architecture explicitly allows JSON for bounded metadata, not primary modeling.
- `recovery_actions` must preserve append-only recovery history and later takeover attribution. Do not assume retry is the only action forever.
- `idempotency_records` must include both the readable `idm_` primary key and the unique command key/fingerprint fields that story 1.9 relies on for replay semantics.

**Exact file targets**
- `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql`
- `deliveryline-backend/src/test/java/org/dradgo/DeliveryLineApplicationTests.java`
- New migration contract tests under `deliveryline-backend/src/test/java/org/dradgo/...` in a package that reflects persistence or contract ownership
- Optional test config file only if needed for clean test isolation, for example `deliveryline-backend/src/test/resources/application-test.yml`

**Testing expectations**
- Prefer structural schema assertions against `information_schema` / `pg_catalog` or Flyway metadata rather than brittle string-matching against the SQL file.
- One test should prove "fresh DB boots and V1 applies."
- One test should prove "second Flyway run is safe and does nothing."
- One test should prove "malformed migration aborts startup/migration before the schema is considered usable."
- Keep the tests deterministic and PostgreSQL-backed. This is contract verification, not a mocked unit test.

**Current official-docs specifics to follow**
- Spring Boot's database-initialization docs explicitly recommend using Flyway alone when Flyway is present, rather than combining it with basic SQL init scripts.
- Spring Boot's Flyway conventions expect versioned migrations in `classpath:db/migration` with names like `V1__create_workflow_core_tables.sql`.
- Spring Boot's Testcontainers support uses `@ServiceConnection` to provide connection details that override manual connection properties in tests.
- Spring Boot `4.0.6` is the current project baseline and was released on **2026-04-23**. Keep this story aligned with that baseline rather than re-deciding framework versions.
- Redgate's Flyway docs show the current OSS command-line release as `12.4.0` (page updated **2026-04-14**), but this project should continue using the Spring Boot-managed Flyway integration already declared in `deliveryline-backend/pom.xml` unless a deliberate dependency-upgrade story says otherwise.

### Project Structure Notes

- The canonical backend module path is `deliveryline-backend/`, not the stale `backend/` alias that still appears in parts of `architecture.md`.
- Keep schema files under `deliveryline-backend/src/main/resources/db/migration/`.
- Keep migration verification in backend tests; runner-contracts, frontend, and root scripts are not the right ownership boundary for this story.
- No `project-context.md` file was found in the repository scan, so the story should treat the architecture, PRD, UX spec, prior implementation stories, and live repo structure as the authoritative context set.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.3 acceptance criteria]
- [Source: `_bmad-output/planning-artifacts/epics.md` - AR5, AR34a, and Epic 1 foundation-contract requirements]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.9 idempotency table expectations]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.12 artifact and artifact-operation table expectations]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.13 runner-execution baseline expectations]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.14 integration-links baseline expectations]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.18 recovery-actions baseline expectations]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 2.9 and 2.10 approvals-table expectations]
- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md` - Story 3.15 GitHub integration-link metadata expectations]
- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md` - Story 3.22 takeover attribution expectations]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - Data Architecture, Database Naming Conventions, and project structure sections]
- [Source: `_bmad-output/planning-artifacts/prd.md` - Success Criteria, Retention & Governance Requirements, NFR27, NFR31, NFR32]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` - workflow-truth and auditability principles (supporting context only; no direct UI work in this story)]
- [Source: `_bmad-output/implementation-artifacts/1-1-initialize-maven-multi-module-project-scaffold.md` - Testcontainers and disabled `DeliveryLineApplicationTests` handoff]
- [Source: `_bmad-output/implementation-artifacts/1-2-unified-docker-compose-with-env-configurable-ports.md` - compose/local-profile handoff and explicit non-ownership of schema work]
- [Source: `https://docs.spring.io/spring-boot/how-to/data-initialization.html` - Spring Boot Flyway-only initialization guidance and migration location/naming conventions]
- [Source: `https://docs.spring.io/spring-boot/reference/testing/testcontainers.html` - `@ServiceConnection` Testcontainers guidance]
- [Source: `https://spring.io/blog/2026/04/23/spring-boot-4-0-6-available-now` - Spring Boot 4.0.6 release confirmation]
- [Source: `https://documentation.red-gate.com/fd/flyway-open-source-277579296.html` - current Flyway OSS release reference]

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Auto-selected next backlog story from `_bmad-output/implementation-artifacts/sprint-status.yaml`: `1-3-flyway-v1-core-schema-with-retention-and-measurement-columns`
- Previous stories confirm this is the first schema-owning story: 1.1 created the backend/test scaffold; 1.2 explicitly deferred Flyway migrations and persistence work to 1.3
- Implemented `V1__create_workflow_core_tables.sql` with nine core tables, readable text IDs, retention columns, state/taxonomy CHECK constraints, named FKs/unique constraints/indexes, and shortened constraint names where PostgreSQL identifier truncation would otherwise occur
- Added PostgreSQL-backed Flyway contract coverage under `org.dradgo.contract`, including fresh-start schema assertions, replay safety, and broken-migration failure-path verification using isolated test resources
- Re-enabled `DeliveryLineApplicationTests` and aligned `TestcontainersConfiguration` to the Testcontainers API actually present in the project classpath after a compile failure exposed a mismatched `PostgreSQLContainer` import
- Verification commands executed successfully on 2026-04-27: `./mvnw -pl deliveryline-backend "-Dtest=org.dradgo.contract.FlywaySchemaContractTest,DeliveryLineApplicationTests" test` and `./mvnw -pl deliveryline-backend test`

### Completion Notes List

- Flyway V1 now creates the full nine-table workflow schema with forward-compatible baseline columns expected by later Epic 1, 2, and 3 stories
- Schema naming had to be tightened for two unique constraints and one index because PostgreSQL truncated the original architecture-derived names beyond its identifier limit
- Migration verification is structural and PostgreSQL-backed: startup creates the schema, a second Flyway run is a no-op, and a malformed migration fails before partial state is considered usable
- `DeliveryLineApplicationTests` is active again and the shared Testcontainers service-connection configuration now imports the same container type used by the contract tests
- Maven test runs still emit non-blocking deprecated-API and Mockito/ByteBuddy dynamic-agent warnings; they did not affect schema verification or test outcomes

### File List

- `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql`
- `deliveryline-backend/src/test/java/org/dradgo/DeliveryLineApplicationTests.java`
- `deliveryline-backend/src/test/java/org/dradgo/TestcontainersConfiguration.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java`
- `deliveryline-backend/src/test/resources/db/broken-migration/V1__create_workflow_core_tables.sql`
- `_bmad-output/implementation-artifacts/1-3-flyway-v1-core-schema-with-retention-and-measurement-columns.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

- 2026-04-27: Added the initial Flyway V1 workflow schema migration and PostgreSQL-backed migration contract tests
- 2026-04-27: Re-enabled backend startup coverage and moved story 1.3 to `review` after module-level verification passed

### Review Findings

*Code review on 2026-04-27 — 3-layer adversarial. Acceptance Auditor: 10/10 ACs structurally satisfied; ACs 6/9/10 minor deviations. Blind Hunter: 29 findings. Edge Case Hunter: 33 findings. After dedup/triage: 5 decisions, 23 patches, 9 deferred, 14 dismissed. Most decisions concern referential integrity / append-only / idempotency contracts that span Epic 1 stories and are awkward to fix later.*

**Decisions resolved (2026-04-27):**

- [x] [Review][Decision] **FK ON DELETE / ON UPDATE actions** → declared per-FK in V1: `RESTRICT` for audit-critical references (every `workflow_run_id` FK + every event-chain FK that is `NOT NULL`), `SET NULL` for soft references (`artifacts.parent_artifact_id`, `recovery_actions.{triggering,resulting}_event_id`), `ON UPDATE CASCADE` everywhere. Verified by `foreignKeysReferenceExpectedTablesAndColumns` test which asserts `delete_rule` per FK.
- [x] [Review][Decision] **Append-only enforcement on \****`workflow_events`***\* and \****`recovery_actions`** → \*\*deferred** to role-provisioning work. No triggers added in V1; recorded in `deferred-work.md`.
- [x] [Review][Decision] **Idempotency key scoping & TTL** → keep current global UNIQUE constraints; document UUID-v4 contract; **add \****`expires_at timestamptz NULL`***\* to \****`idempotency_records`** for retention (NULL = indefinite). No scoping change.
- [x] [Review][Decision] **`integration_links`**** cross-run uniqueness** → **deferred** to story 1.14 / 3.15 when integration semantics are designed. Inline SQL comment marks the deferral.
- [x] [Review][Decision] **Test infrastructure** → bundled fix: added `deliveryline-backend/src/test/resources/application-test.yml` with `spring.docker.compose.enabled=false` + `skip.in-tests=true` (defensive — Spring Boot 3.1+ already defaults `skip.in-tests=true`). AC-10 wording kept as Flyway-direct (Task 4 already permissive). Docker accepted as a CI-wide requirement (`DeliveryLineApplicationTests` re-enabled).

**Architectural pivot (2026-04-27): bigserial PK + \****`public_id text`**\*\* for readable IDs.** Reviewer requested switching every PK from prefixed text to `bigserial`, with the readable identifier moved to a new `public_id text NOT NULL UNIQUE` column carrying the prefix `CHECK`. AC-2 and AC-6 amended in this story (see top); architecture, story 1.4 prefix registry, and downstream stories 1.9/1.12/1.13/1.14 must be updated to reference `id bigint` for joins and `public_id` for human-readable lookups.

**Patches (unchecked = action items):**

- [ ] [Review][Patch] `failure_category` is wide-open free text on `workflow_events`, `artifact_operations`, `runner_executions` while every other categorical column has a CHECK — add a CHECK with the architecture failure-taxonomy values (or document that any text is acceptable). [`V1__create_workflow_core_tables.sql`:75, 161, 207]
- [ ] [Review][Patch] FK supporting indexes missing — add btree indexes on `artifacts.parent_artifact_id`, `artifacts.linked_event_id`, `artifact_operations.linked_event_id`, `recovery_actions.triggering_event_id`, `recovery_actions.resulting_event_id`. PG does not auto-index FKs; deletes/cascades will seq-scan child tables. [`V1__create_workflow_core_tables.sql`:288-313]
- [ ] [Review][Patch] `integration_links.last_sync_at NOT NULL DEFAULT now()` makes "was this ever synced?" unanswerable on insert — make nullable (NULL until first sync). [`V1__create_workflow_core_tables.sql`:228]
- [ ] [Review][Patch] `recovery_actions.actor_type` is nullable while `actor_identity` is NOT NULL — inconsistent with `workflow_events`/`approvals`. Make `actor_type` NOT NULL or add `CHECK ((actor_identity IS NULL) = (actor_type IS NULL))`. [`V1__create_workflow_core_tables.sql`:251-252]
- [ ] [Review][Patch] `approvals.artifact_version` is duplicated data with no FK enforcement — caller can claim version 5 for an artifact whose actual version is 2. Either drop the column (derive from `artifact_id`), or add unique `(id, version)` on `artifacts` and a composite FK. [`V1__create_workflow_core_tables.sql`:175-176]
- [ ] [Review][Patch] `runner_executions.timeout_at` < `last_activity_at` is logically impossible but accepted — add `CHECK (timeout_at >= last_activity_at)`. [`V1__create_workflow_core_tables.sql`:208-209]
- [ ] [Review][Patch] No partial indexes on `archived_at` for retention sweeps — Epic 5 will full-scan every retention query. Add `CREATE INDEX … ON … (archived_at) WHERE archived_at IS NOT NULL` for at minimum `workflow_events`, `recovery_actions`, `artifacts`. [`V1__create_workflow_core_tables.sql`:288-313]
- [ ] [Review][Patch] `workflow_events.details jsonb` and `integration_links.external_metadata jsonb` lack GIN indexes — any `details @> '…'` query will seq-scan. Add `CREATE INDEX … USING gin` if these are intended to be queried. [`V1__create_workflow_core_tables.sql`:84, 227]
- [ ] [Review][Patch] `details jsonb` has no size cap — runner output / logs / secrets could blow row size past TOAST. Add `CHECK (pg_column_size(details) < 65536)` (or chosen cap). [`V1__create_workflow_core_tables.sql`:84, 227]
- [ ] [Review][Patch] Prefix CHECKs use `LIKE 'run_%'` — accepts `run_` (empty), `run_   ` (whitespace), `RUN_…` (case-mismatch is rejected with confusing 23514 message rather than a clean validation error). Tighten to `~ '^run_[A-Za-z0-9]{8,64}$'` aligned with story 1.4 prefix registry expectations. [`V1__create_workflow_core_tables.sql`:53, 88, 142, 163, 188, 214, 232, 258, 283]
- [ ] [Review][Patch] `artifacts.parent_artifact_id` permits self-loop (`id = parent_artifact_id`) and unbounded cycles. Add `CHECK (parent_artifact_id IS NULL OR parent_artifact_id <> id)`. [`V1__create_workflow_core_tables.sql`:144]
- [ ] [Review][Patch] `artifacts.checksum_algorithm` and `checksum_value` are independently nullable — partial-checksum rows accepted. Add `CHECK ((checksum_algorithm IS NULL) = (checksum_value IS NULL))`. [`V1__create_workflow_core_tables.sql`:135-136]
- [ ] [Review][Patch] `approvals` allows `decision='rejected'` with `rejection_taxonomy=NULL` and vice versa. Add `CHECK ((decision='approved' AND rejection_taxonomy IS NULL) OR (decision='rejected' AND rejection_taxonomy IS NOT NULL))`. [`V1__create_workflow_core_tables.sql`:194-199]
- [ ] [Review][Patch] `runner_executions.completed_at` correlation with `status` not enforced — `status='completed'` with `completed_at IS NULL` is silently allowed. Add `CHECK ((status IN ('completed','failed','timed_out','orphaned')) = (completed_at IS NOT NULL))`. [`V1__create_workflow_core_tables.sql`:206, 211]
- [ ] [Review][Patch] AC-9 replay test asserts `migrationsExecuted == 0` but never calls `flyway.validate()` — does not actually prove "no checksum mismatch". Add explicit `flyway.validate()` assertion. [`FlywaySchemaContractTest.java`:467-489]
- [ ] [Review][Patch] `malformedMigrationFailsFast` spins up a *second* `PostgreSQLContainer` per test run (doubles Docker startup). Reuse the autowired `@ServiceConnection` DataSource against an isolated Flyway schema (e.g., `flyway.schemas("broken_test")`). [`FlywaySchemaContractTest.java`:491-503]
- [ ] [Review][Patch] Broken-migration test catches generic `FlywayException` — passes for the wrong reason if path/connection error fires. Assert on cause/message contents (e.g., `assertThat(thrown.getMessage()).contains("syntax error")`). [`FlywaySchemaContractTest.java`:497]
- [ ] [Review][Patch] `startupCreatesExpectedCoreTables` uses `containsAll` — silent on accidental extra tables. Use `assertEquals(expectedTables, actualTablesMinusFlywayHistory)`. [`FlywaySchemaContractTest.java`:370-372]
- [ ] [Review][Patch] No tests assert NOT NULL constraints, FK targets, or UNIQUE columns — only column types are checked via `data_type`. A future migration could drop `NOT NULL` on `actor_identity` and every test still passes. Add structural assertions querying `information_schema.columns.is_nullable`, `pg_constraint.conkey`/`confkey`. [`FlywaySchemaContractTest.java`]
- [ ] [Review][Patch] String-match on `pg_get_constraintdef` output (`workflowRunStateCheck.contains("WaitingForSpecApproval")`) is brittle across PG versions/quoting. Replace with structural probe (e.g., `INSERT … RETURNING` with each candidate value) or parse the constraintdef. [`FlywaySchemaContractTest.java`:432-436]
- [ ] [Review][Patch] `JdbcTemplate.queryForObject` throws `EmptyResultDataAccessException` if a constraint is renamed — masks the real assertion. Use `queryForList` + size-1 + content assertions with helpful messages. [`FlywaySchemaContractTest.java`:418-431]
- [ ] [Review][Patch] Constraint-truncation test only spot-checks 3 names — iterate over all expected names and assert each is ≤ 63 bytes and present. [`FlywaySchemaContractTest.java`:439-465]
- [ ] [Review][Patch] Broken-migration fixture filename `V1__create_workflow_core_tables.sql` collides with main migration name — if a future change leaks the fixture path into default Flyway locations, "Found more than one migration with version 1" fires. Rename to `V999__broken_for_test.sql`. [`deliveryline-backend/src/test/resources/db/broken-migration/V1__create_workflow_core_tables.sql`]
- [ ] [Review][Patch] V1 SQL has zero comments — Task 5 says document unavoidable design choices in story notes OR ADR-style SQL comments; the truncated constraint names are documented only in story notes. Add `--` comments at the truncation sites. [`V1__create_workflow_core_tables.sql`:169, 238, 306]

**Deferred (pre-existing or out-of-scope, recorded in \****`deferred-work.md`**\*\*):**

- [x] [Review][Defer] State enum duplicated across 3 CHECK constraints in `workflow_runs` and `workflow_events` — refactoring to lookup table or `CREATE TYPE` is story 1.4's territory (workflow state registry).
- [x] [Review][Defer] `workflow_events.event_type` has no CHECK / no registry — story 1.4 owns the central event-type registry.
- [x] [Review][Defer] No `COMMENT ON TABLE` / `COMMENT ON COLUMN` for self-documentation — schema is currently undocumented at the DB level. Add in a follow-up doc pass.
- [x] [Review][Defer] `integration_links` lacks `idempotency_key` / `classification` columns — discretionary per spec ("as needed"). Story 3.15 may force a schema add.
- [x] [Review][Defer] `@SpringBootTest` is heavyweight for pure schema tests — `@DataJpaTest` or plain Testcontainers + Flyway harness would be faster. Performance optimization.
- [x] [Review][Defer] `postgres:17` not pinned by digest — reproducibility nice-to-have; CI risk acceptable for now.
- [x] [Review][Defer] Testcontainers `withReuse(true)` not enabled — every test class spins up a fresh container; performance optimization for later.
- [x] [Review][Defer] Timezone (`postgres.withEnv("TZ","UTC")` + `-Duser.timezone=UTC`) not pinned in tests — preventive guard for future flaky time-comparison tests.
- [x] [Review][Defer] Index-name truncation documented only in completion notes (not as in-SQL ADR comments) — see "Add `--` comments at the truncation sites" patch above which addresses this; the broader ADR is a documentation pass.

**Dismissed as noise (14 items):** state-list duplication treated as design (spec mandates inline CHECK in V1; lookup table is story 1.4); `prior_state != resulting_state` cross-field check (overspecification); `decided_at` back-dating bound (speculative); jsonb `'{}'` vs NULL semantics (preference); `idempotency_records.key` length cap (subsumed by composite-key decision); index naming `run_id` shorthand (PG 63-char limit acknowledged in completion notes); 30-line `assertColumnType` copy-paste (NIT, doesn't change behavior); Testcontainers Ryuk on Windows (speculative); `decided_at` default `now()` accepts back-dating (NIT, app concern); `runner_executions.completed_at >= created_at` (subsumed by completed_at correlation patch); `details jsonb` default obscures NULL semantics (preference); raw type `PostgreSQLContainer` lacks `asCompatibleSubstituteFor` (cosmetic); `actor_type` taxonomy choice `service_account` (spec leaves it to implementer); `DeliveryLineApplicationTests` body inspection (separately verifiable).
