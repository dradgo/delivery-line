# Story 3c.1: Flyway V17 — `projects` + `project_credentials` Schema + `project_id` Run Association

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **⚠️ VERSION RECONCILIATION (read first).** The epic and the sprint-change proposal both say "Flyway **V14**". That number is stale: the head migration on disk is **`V16__add_cancelled_for_takeover_status.sql`** (story 3.22), so the **next free version is `V17`**. This story ships **`V17__create_projects_and_credentials.sql`**. The story *key/filename* keeps the `…v14…` slug (it is an identifier synced to `sprint-status.yaml` — do not rename it), but every artifact you create says **V17**. This is the same drift the codebase already absorbed twice (epic "V5" → real V15 in 3.18; story draft "V15" → real V16 in 3.22 — see the version-note headers in those two SQL files). Add the same kind of version-note header comment to V17.

## Story

As a foundation developer,
I want a Flyway-managed **V17** schema introducing the `projects` and `project_credentials` tables plus a `project_id` association on runs (and integration links),
so that per-project configuration and encrypted connector credentials have a durable, drift-protected home and every governed run can be scoped to a project.

## Acceptance Criteria

> These ACs are **reconciled** against the live schema and its test contracts (see Dev Notes → "Why these ACs differ from the epic"). Where the epic wording conflicts with an enforced codebase invariant, the reconciled wording below is authoritative.

1. **Given** the V16 head migration, **When** the backend starts, **Then** Flyway applies `V17__create_projects_and_credentials.sql` creating `projects` and `project_credentials` — V17 is the next free version (V16 is the current head, NOT V13/V14).
2. **Given** the universal core-table shape (every existing table uses `id bigserial primary key` + a separate `public_id text` column with a format CHECK), **Then** `projects` and `project_credentials` follow the **same** shape: each gets `id bigserial primary key` and `public_id text not null`. The `prj_` prefix lives on `projects.public_id` (CHECK `ck_projects_public_id_format` = `^prj_[A-Za-z0-9_-]{4,64}$`); the `cred_` prefix lives on `project_credentials.public_id` (CHECK `ck_project_credentials_public_id_format` = `^cred_[A-Za-z0-9_-]{4,64}$`). **No table uses a `text` primary key.**
3. **Given** the `projects` table, **Then** it has: `name text NOT NULL`, `slug text NOT NULL`, `status text NOT NULL` (CHECK `ck_projects_status` in the `project_status` value set `('active','disabled')`), `repository_url text NULL`, `ticket_source_kind text NOT NULL` (CHECK `ck_projects_ticket_source_kind` in the `connector_kind` value set `('linear','github')`), `repo_host_kind text NOT NULL` (CHECK `ck_projects_repo_host_kind` in the same `connector_kind` value set), `openspec_enabled boolean NOT NULL DEFAULT false`, plus `created_at timestamptz NOT NULL DEFAULT now()` and `archived_at timestamptz NULL` (retention-readiness rule).
4. **Given** uniqueness + naming conventions, **Then** `uq_projects_slug` enforces a unique slug; `uq_projects_public_id` enforces a unique public id; foreign keys use `fk_`, unique constraints `uq_`, indexes `idx_`, checks `ck_`; all timestamps are `timestamptz`; all enum-likes are `text` + CHECK (never ordinals or Postgres enums); every constraint/index name is ≤ 63 bytes.
5. **Given** `project_credentials`, **Then** it stores `id bigserial primary key`, `public_id text NOT NULL` (`cred_`), `project_id text NOT NULL` (FK `fk_project_credentials_projects` → `projects.public_id`), `connector_role text NOT NULL` (CHECK `ck_project_credentials_connector_role` in `('ticket_source','repo_host')`), `ciphertext bytea NOT NULL`, `key_id text NOT NULL`, `algo text NOT NULL`, plus `created_at timestamptz NOT NULL DEFAULT now()` and `archived_at timestamptz NULL`. **No plaintext column ever exists.** `uq_project_credentials_project_role` (`project_id`, `connector_role`) allows exactly one active secret per role per project.
6. **Given** `workflow_runs`, **Then** a `project_id text NULL` column + `fk_workflow_runs_projects` (→ `projects.public_id`) + `idx_workflow_runs_project_id` are added (nullable now; backfilled to the default project by story 3c-6, after which the application treats it as required).
7. **Given** `integration_links`, **Then** a `project_id text NULL` column + `fk_integration_links_projects` (→ `projects.public_id`) + `idx_integration_links_project_id` are added so Epic 4's conflict-detection job can resolve the adapter per project.
8. **Given** existing constraints (including the `ck_workflow_runs_current_state` CHECK and all V1–V16 constraints), **Then** they remain intact — the migration is **additive only** (new tables + new nullable columns; it never drops or alters an existing column/constraint).
9. **Given** migration replay, **When** Flyway runs twice against the same DB, **Then** the second run is a no-op (no checksum mismatch, no errors) — proven by extending the existing `flywayMigrateIsReplaySafeAndChecksumStable` contract or relying on it unchanged.
10. **Given** the fail-fast-on-broken-migration guarantee, **Then** it is **already covered** by the existing `FlywaySchemaContractTest.malformedMigrationFailsFastWithSyntaxError` (it runs an intentionally broken `V999` against an isolated schema and asserts a `FlywayException`). This story does **not** add a second broken-migration fixture; Flyway's default `validateOnMigrate=true` plus `FlywayMigrationsFoundationContract` (fresh-container migrate must fully succeed) cover the partial-schema/fail-fast intent for V17.
11. **(Schema-contract test extension — REQUIRED.)** `FlywaySchemaContractTest` is extended so the new tables join the enforced core-table invariant: add `"projects"` and `"project_credentials"` to `CORE_TABLES`, add `Map.entry("projects","prj_")` and `Map.entry("project_credentials","cred_")` to `EXPECTED_PUBLIC_ID_PREFIX`, and add focused assertions for the new columns, CHECKs, unique constraints, and the two `project_id` FKs (→ `projects.public_id`). The `foreignKeysReferenceExpectedTablesAndColumns` `workflow_run_id`-FK count **stays at 8** (the new FKs are on `project_id`, not `workflow_run_id`) — do not bump it.

## Tasks / Subtasks

- [x] **Task 1 — Author `V17__create_projects_and_credentials.sql`** (AC: 1,2,3,4,5,6,7,8)
  - [x] Add a version-note header comment in the style of V15/V16: "This migration is **V17**, NOT the epic/proposal's 'V14'. The head on disk is `V16__add_cancelled_for_takeover_status.sql`, so V17 is the next free version."
  - [x] `create table projects` with `id bigserial primary key`, `public_id text not null`, `name`, `slug`, `status`, `repository_url`, `ticket_source_kind`, `repo_host_kind`, `openspec_enabled boolean not null default false`, `created_at`, `archived_at` — with `uq_projects_public_id`, `uq_projects_slug`, `ck_projects_public_id_format`, `ck_projects_status`, `ck_projects_ticket_source_kind`, `ck_projects_repo_host_kind` (exact SQL in Dev Notes).
  - [x] `create table project_credentials` with `id bigserial primary key`, `public_id text not null`, `project_id text not null`, `connector_role`, `ciphertext bytea not null`, `key_id`, `algo`, `created_at`, `archived_at` — with `uq_project_credentials_public_id`, `uq_project_credentials_project_role`, `ck_project_credentials_public_id_format`, `ck_project_credentials_connector_role`, `fk_project_credentials_projects` (→ `projects.public_id`, `on delete restrict on update cascade`).
  - [x] `alter table workflow_runs add column project_id text null` + `fk_workflow_runs_projects` (→ `projects.public_id`, `on delete restrict on update cascade`) + `idx_workflow_runs_project_id`.
  - [x] `alter table integration_links add column project_id text null` + `fk_integration_links_projects` (→ `projects.public_id`, `on delete restrict on update cascade`) + `idx_integration_links_project_id`.
  - [x] Verify every new constraint/index name is ≤ 63 bytes (the longest, `ck_project_credentials_connector_role` = 37 chars, `ck_project_credentials_public_id_format` = 39 — all safe; the existing `everyConstraintAndIndexNameFitsPostgresIdentifierLimit` test also guards this).
- [x] **Task 2 — Extend `FlywaySchemaContractTest`** (AC: 2,3,4,5,6,7,9,11)
  - [x] Add `"projects"`, `"project_credentials"` to `CORE_TABLES`; add `prj_`/`cred_` to `EXPECTED_PUBLIC_ID_PREFIX`. (This alone makes `startupCreatesExactlyTheExpectedCoreTables`, `coreTablesUseBigserialIdsAndTextPublicIdsAndRetentionColumns`, `publicIdPrefixCheckConstraintsExistForEveryCoreTable`, and `uniqueConstraintsCoverPublicIdsAndIdempotencyKeys` cover the new tables.)
  - [x] Add `projectsSchemaCarriesExpectedColumnsConstraintsAndIndexes()` — column types/nullability for all `projects` columns; `uq_projects_slug` exists; CHECK acceptance/rejection probes for `status` (`active` accepted, `archived`/`bogus` rejected) and `ticket_source_kind`/`repo_host_kind` (`linear`/`github` accepted, `bogus` rejected); `openspec_enabled` boolean default false.
  - [x] Add `projectCredentialsSchemaCarriesExpectedColumnsConstraintsAndIndexes()` — `ciphertext` is `bytea`, `key_id`/`algo` `text not null`, `connector_role` CHECK (`ticket_source`/`repo_host` accepted, `bogus` rejected), `uq_project_credentials_project_role` exists and enforces one-per-(project,role), no `plaintext`/`secret` column exists.
  - [x] Add `runsAndLinksCarryNullableProjectIdForeignKeys()` — `workflow_runs.project_id` + `integration_links.project_id` are `text` & nullable; `fk_workflow_runs_projects` and `fk_integration_links_projects` reference `projects(public_id)` and are `RESTRICT` on delete; `idx_workflow_runs_project_id`/`idx_integration_links_project_id` exist.
  - [x] Confirm `foreignKeysReferenceExpectedTablesAndColumns` still asserts exactly **8** `workflow_run_id` FKs (unchanged) — leave that number alone.
- [x] **Task 3 — Verify the cross-cutting drift gates stay green** (AC: 8,11)
  - [x] Run `RegistryContractTest` — it is **enum-driven** (`extractPublicIdPrefixesFromSql` iterates `PublicIdPrefixes.values()` and looks up each enum's `constraintName()`), so the new DB CHECKs are invisible to it and it stays green. **Do NOT add `prj_`/`cred_` to `PublicIdPrefixes.java` in this story** — that (plus `DomainRegistry`, the API placeholder manifest, and the drift test) is story **3c-2**'s job. Naming the two new format CHECKs exactly `ck_projects_public_id_format`/`ck_project_credentials_public_id_format` with the canonical `^<prefix>_[A-Za-z0-9_-]{4,64}$` regex is what lets 3c-2 wire the enum entries with zero rework.
  - [x] Confirm `FlywayMigrationsFoundationContract` (fresh Testcontainers PG, `@Tag("foundation-gate")`) still migrates all the way through V17 with every `MigrationInfo` = `SUCCESS`.
  - [x] Confirm **no JPA entity change is needed**: adding *nullable, unmapped* columns to `workflow_runs`/`integration_links` does not break Hibernate schema validation (16 prior migrations added columns the same way). The `Project`/`ProjectCredential` JPA entities + mappers are story **3c-2/3c-5**, not here.
- [x] **Task 4 — Run the gated suites and capture evidence** (AC: all)
  - [x] Backend integration tier (Testcontainers): `FlywaySchemaContractTest`, `RegistryContractTest`, `FlywayMigrationsFoundationContract` all green. Use the lifecycle `integration-test` phase (not the `failsafe:` direct goal) per the Maven argLine note.
  - [x] `spotless:check`/`checkstyle` clean on the touched SQL/test files.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **N/A with rationale — record this in the Completion Notes.** This story adds **no Java service, SPI, persistence-adapter, or state-machine code** — it is a declarative Flyway SQL migration plus a Testcontainers schema-contract test. There are no new public service entry/exit points, `DomainException` raise sites, external SPI calls, or recovery branches to instrument. Flyway already logs migration application (version applied, checksum) at INFO. The first story to introduce project services/persistence (3c-2 domain, 3c-5 credential store) owns the SLF4J/MDC logging surface (and there, **credential plaintext/ciphertext, `key_id`, and the master key MUST never be logged** — set up the redaction expectation early; story 3c-5 makes it a foundation-gate assertion).

## Dev Notes

### Why these ACs differ from the epic (the reconciliations that matter)

The dev agent will only have this file. These are the traps where the epic/proposal wording collides with an **enforced** codebase invariant; follow the reconciled ACs above, not the epic text.

- **R1 — Version V14 → V17.** Head is `V16__add_cancelled_for_takeover_status.sql`. Epic §AC1 and `architecture.md:283` say "V14"; the proposal §58/§86 say "V14 (V13 is current head)". Both predate V14/V15/V16 landing. Use **V17**. [Source: deliveryline-backend/src/main/resources/db/migration/V16__add_cancelled_for_takeover_status.sql#version-note]
- **R2 — `text` primary key → `bigserial id` + `public_id text` (THE central reconciliation).** Epic §AC2 says "`projects.id` uses prefix `prj_` … both `text` primary keys". This is **forbidden** by the universal core-table invariant: *every* table in V1–V16 uses `id bigserial primary key` + a separate `public_id text` column, and `FlywaySchemaContractTest.coreTablesUseBigserialIdsAndTextPublicIdsAndRetentionColumns` asserts `id`=`bigint` & `public_id`=`text` for every entry in `CORE_TABLES`. A text PK on a core table would fail that test the moment the table is added to `CORE_TABLES` (which `startupCreatesExactlyTheExpectedCoreTables` forces — see R7). Resolution: `prj_`/`cred_` are **public_id prefixes** (exactly what story 3c-2 AC2 means by "added to the prefix registry"), not PKs. The batch-submissions story (V15) called this "every table is a core table with its own public_id" a **contract invariant** in its own header. [Source: deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql; src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java:85-96]
- **R3 — `project_id` FK type: `text` → `projects.public_id`.** The proposal is explicit and repeated: "`project_id text` FK added to `workflow_runs`" (§58, §86); epic §AC5/6/7 say `project_id text`. Honor it: `project_id` columns are **`text`**, with **real** FKs to `projects.public_id` (which is `UNIQUE` via `uq_projects_public_id`, so it is a valid FK target). This carries the readable `prj_` id on the run/link (symmetric with how runs already expose `run_` ids) and matches how the app layer addresses projects (by public id), minimizing lookups in the 3c-6 backfill / 3c-7 association work. *Note the deviation from the intra-schema "child→parent uses bigint→id" micro-convention (e.g. `artifact_operations.artifact_id`): we deliberately reference projects **uniformly by `public_id` (text)** across `workflow_runs`, `integration_links`, **and** `project_credentials`, to honor the proposal's explicit `text` and avoid exposing the surrogate bigint.* The alternative (`bigint project_id → projects.id`) is recorded under Open Questions; if Alex prefers it, only the column types + FK targets change. [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-14-multi-project.md:58,86]
- **R4 — `project_credentials` also gets `public_id` + `created_at`/`archived_at`.** Epic §AC5 lists only the crypto columns, but the core-table invariant (and `coreTablesUseBigserialIds…` once it is in `CORE_TABLES`) requires `id bigserial` + `public_id text` (`cred_`) + the retention pair. Include them.
- **R5 — Inlined CHECK value sets.** `project_status` and `connector_kind` are central registries created in **3c-2** (domain), but the V17 DB CHECK needs concrete values **now**: `status in ('active','disabled')`; `ticket_source_kind`/`repo_host_kind` both `in ('linear','github')` (the `connector_kind` union — a single value set per the proposal). 3c-2's drift test will pin these DB values against the `ProjectStatus`/`ConnectorKind` enums; **3c-3** widens `connector_kind` for the one additional proof kind via the drop-then-re-add idiom (see V12/V16). The CHECK intentionally allows `github` as a ticket-source value at the DB level — role correctness is enforced at the application layer by `ProjectConnectorResolver` (3c-3), not the DB. [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-2; #Story-3c-3]
- **R6 — Fail-fast (epic §AC10) is already covered.** Do not author a second broken migration. `FlywaySchemaContractTest.malformedMigrationFailsFastWithSyntaxError` already runs `classpath:db/broken-migration/V999__broken_for_test.sql` against an isolated `broken_test` schema and asserts a syntax-error `FlywayException`; `FlywayMigrationsFoundationContract` proves a fresh container applies all migrations fully. Together they satisfy the "no partial schema / fail-fast on a broken V17" intent. [Source: src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java:511-536; src/test/java/org/dradgo/foundation/FlywayMigrationsFoundationContract.java]
- **R7 — `FlywaySchemaContractTest` MUST change or it goes red.** `startupCreatesExactlyTheExpectedCoreTables` asserts the public schema contains **exactly** `CORE_TABLES` — any new table (even non-core) reds it until added. So `projects`/`project_credentials` must join `CORE_TABLES` (which then enforces the bigserial+public_id+retention shape on them — see R2/R4) and `EXPECTED_PUBLIC_ID_PREFIX` must gain `prj_`/`cred_`. [Source: src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java:29-55,64-82]
- **R8 — Stay out of `PublicIdPrefixes.java` / `RegistryContractTest` / the API manifest.** Those belong to 3c-2. `RegistryContractTest.publicIdPrefixesStayAlignedWithSqlChecksAndParsingHelpers` is **enum-driven** — `extractPublicIdPrefixesFromSql()` iterates `PublicIdPrefixes.values()`, so DB CHECKs without a matching enum entry are invisible and the test stays green. (Contrast 3.18/V15, which *did* add `BATCH_SUBMISSION` to the enum in-story — because that story actually persisted `bat_` rows through the app. 3c-1 persists nothing through the app; the schema-contract test inserts literal `prj_…`/`cred_…` strings via raw JDBC.) [Source: src/test/java/org/dradgo/contract/RegistryContractTest.java:215-217,553-567; src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java:23]

### Exact migration SQL (author as `V17__create_projects_and_credentials.sql`)

```sql
-- Story 3c-1 — projects + project_credentials schema + project_id run association.
--
-- This migration is V17, NOT the epic/proposal's "V14". The head migration on disk is
-- V16__add_cancelled_for_takeover_status.sql (story 3.22), so V17 is the next free version.
-- (Same drift the codebase already absorbed: epic "V5" -> real V15; story "V15" -> real V16.)
--
-- Shape note: projects/project_credentials follow the universal core-table invariant
-- (bigserial id PK + public_id text + format CHECK + created_at/archived_at). The prj_/cred_
-- prefixes live on public_id, never on the PK. Runs/links/credentials reference a project by its
-- prj_ public_id (text FK), honoring the proposal's explicit `project_id text`.
-- Enum-likes (project_status, connector_kind) are text + CHECK with inlined value sets; the
-- ProjectStatus/ConnectorKind registries + drift tests land in story 3c-2.

create table projects (
    id bigserial primary key,
    public_id text not null,
    name text not null,
    slug text not null,
    status text not null,
    repository_url text null,
    ticket_source_kind text not null,
    repo_host_kind text not null,
    openspec_enabled boolean not null default false,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_projects_public_id unique (public_id),
    constraint uq_projects_slug unique (slug),
    constraint ck_projects_public_id_format check (public_id ~ '^prj_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_projects_status check (status in ('active', 'disabled')),
    constraint ck_projects_ticket_source_kind check (ticket_source_kind in ('linear', 'github')),
    constraint ck_projects_repo_host_kind check (repo_host_kind in ('linear', 'github'))
);

create table project_credentials (
    id bigserial primary key,
    public_id text not null,
    project_id text not null,
    connector_role text not null,
    ciphertext bytea not null,
    key_id text not null,
    algo text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_project_credentials_public_id unique (public_id),
    -- One active secret per (project, role).
    constraint uq_project_credentials_project_role unique (project_id, connector_role),
    constraint ck_project_credentials_public_id_format check (public_id ~ '^cred_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_project_credentials_connector_role check (connector_role in ('ticket_source', 'repo_host')),
    constraint fk_project_credentials_projects foreign key (project_id)
        references projects (public_id) on delete restrict on update cascade
);

-- Run -> Project association (nullable now; story 3c-6 backfills to the seeded default project,
-- after which the application treats it as required).
alter table workflow_runs
    add column project_id text null;
alter table workflow_runs
    add constraint fk_workflow_runs_projects foreign key (project_id)
        references projects (public_id) on delete restrict on update cascade;
create index idx_workflow_runs_project_id on workflow_runs (project_id);

-- Integration link -> Project association (Epic 4 conflict-detection resolves the adapter per project).
alter table integration_links
    add column project_id text null;
alter table integration_links
    add constraint fk_integration_links_projects foreign key (project_id)
        references projects (public_id) on delete restrict on update cascade;
create index idx_integration_links_project_id on integration_links (project_id);
```

> Mirror the existing files' lowercase-SQL + explicit-`constraint`-name style. Postgres renders `pg_get_constraintdef` with uppercase keywords and may reorder; the schema-contract test probes structurally, so exact source casing is not asserted.

### `FlywaySchemaContractTest` deltas (exact)

- `CORE_TABLES`: append `"projects"`, `"project_credentials"`.
- `EXPECTED_PUBLIC_ID_PREFIX`: add `Map.entry("projects", "prj_")`, `Map.entry("project_credentials", "cred_")`.
- New `@Test` methods (use the existing private helpers `assertColumnType`, `assertColumnNullable`, `assertConstraintDefinitionContains`, `assertIndexDefinitionContains`, and the `assertStateAccepted`/`assertStateRejected` *pattern* — write small local insert/expect-CHECK-violation probes for `projects.status`/`*_kind` and `project_credentials.connector_role`):
  - `projectsSchemaCarriesExpectedColumnsConstraintsAndIndexes()`
  - `projectCredentialsSchemaCarriesExpectedColumnsConstraintsAndIndexes()` (assert `ciphertext` `data_type` = `bytea`; assert there is **no** column named `plaintext`/`secret`/`value`).
  - `runsAndLinksCarryNullableProjectIdForeignKeys()` (FK parent table = `projects`, parent column = `public_id`, `delete_rule` = `RESTRICT`).
- Leave `foreignKeysReferenceExpectedTablesAndColumns`'s `assertEquals(8, workflowRunFks…)` **unchanged**.

### Project Structure Notes

- Migration: `deliveryline-backend/src/main/resources/db/migration/V17__create_projects_and_credentials.sql` (new). Flyway location is `classpath:db/migration` (`FlywayMigrationsFoundationContract:52`).
- Test: `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` (extend; `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`, profiles `{"test","linear-mock"}`).
- **No** changes to `src/main/java/**` in this story (no entities, no domain, no services). No `application.yml`/test-yaml change (no new validated `@ConfigurationProperties`). No OpenAPI/contract snapshot change (no REST surface). No frontend change.
- Canonical Postgres image is `postgres:17.2` (pinned in `org.dradgo.TestcontainersConfiguration`); do not introduce a divergent tag.

### Logging Requirements (project-wide standard)

Schema/SQL-only story: **no application log surface is introduced**, so the standard logging task is N/A here (recorded in the Logging task above with rationale). The redaction obligation it foreshadows — **credentials, ciphertext, `key_id`, `algo`, and the master key must never reach logs/events/artifacts/exports** — is implemented and gated when the credential code lands (stories 3c-4/3c-5; 3c-5 AC6 makes it a foundation-gate assertion). Do not add logging just to satisfy the template.

### References

- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-1] — the source ACs (reconciled here).
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-14-multi-project.md:58,86] — authoritative `project_id text` + `prj_`/`cred_` + text+CHECK enum-likes.
- [Source: _bmad-output/planning-artifacts/architecture.md:283] — data model: Epic 3c adds `projects`/`project_credentials` + `project_id` FK on `workflow_runs`/`integration_links`.
- [Source: _bmad-output/planning-artifacts/architecture.md:341,345,378-390] — credential storage/encryption posture; `Project` as configuration (prefix `prj_`), credentials (`cred_`), encrypted at rest, write-only.
- [Source: deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql] — core-table shape, FK/CHECK/index naming, `workflow_runs`/`integration_links` definitions.
- [Source: deliveryline-backend/src/main/resources/db/migration/V15__add_batch_submissions.sql; V16__add_cancelled_for_takeover_status.sql] — version-note header precedent; "every table is a core table with its own public_id" invariant.
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java] — the contract this story extends.
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java:215-288,553-567] — enum-driven prefix drift gate (stays green; 3c-2 owns the enum).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java] — the prefix registry 3c-2 (not 3c-1) extends with `prj_`/`cred_`.

### Open Questions / Decisions for Alex (non-blocking — defaults are in place)

1. **`project_id` FK type — `text → projects.public_id` (default, chosen) vs `bigint → projects.id`.** The proposal says `project_id text`, so this story uses `text` FKs to `projects.public_id` (runs/links carry the readable `prj_` id; valid FK via `uq_projects_public_id`). The alternative — `bigint project_id → projects.id` — is more consistent with every *other* intra-schema FK (all reference the surrogate `id`) and with the JPA `@ManyToOne(bigint join)` pattern the 3c-2/3c-7 adapters will use, at the cost of a public_id→id lookup when associating a run. If you prefer bigint, only the three `project_id` columns + their FK targets change (no other restructure). **Proceeding with `text → public_id` unless you say otherwise.**
2. **`project_status` value set.** Defaulted to `('active','disabled')` per epic 3c-2's "e.g. `active`, `disabled`". If you also want `archived` as a first-class status (vs using `archived_at`), say so — it widens `ck_projects_status` and the 3c-2 `ProjectStatus` enum.
3. **`connector_kind` third proof kind.** Not added in 3c-1 (scope is `linear`/`github`); 3c-3 AC8 adds "exactly one additional kind". Confirm 3c-3 (not 3c-1) owns that CHECK widening — assumed yes.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-dev-story workflow.

### Debug Log References

- `FlywaySchemaContractTest` (Failsafe / Testcontainers PG 17.2): **18 tests, 0 failures, 0 errors** (was 15; +3 new: `projectsSchemaCarriesExpectedColumnsConstraintsAndIndexes`, `projectCredentialsSchemaCarriesExpectedColumnsConstraintsAndIndexes`, `runsAndLinksCarryNullableProjectIdForeignKeys`). Full backend Surefire unit tier also green in the same run (1071 tests, 0 failures).
- `RegistryContractTest` (Failsafe): exit 0 — confirms **R8**: the enum-driven `publicIdPrefixesStayAlignedWithSqlChecksAndParsingHelpers` stays green with the new DB CHECKs because `extractPublicIdPrefixesFromSql()` iterates `PublicIdPrefixes.values()` (no `prj_`/`cred_` enum entry yet → invisible to the gate; that is 3c-2's job).
- `-Pfoundation-gate verify`: **BUILD SUCCESS** — the FoundationGate fresh-container Flyway migration contracts applied **all** migrations through V17 on PostgreSQL 17.2 with no checksum/validation errors.
- `spotless:apply`: formatted the new test file (google-java-format). `spotless:check` clean afterward.
- `checkstyle:check`: the **only** violation is the pre-existing `WorkflowCommandService.java:810` `ForbiddenThreadSleep` — separate uncommitted working-tree work from another story (documented in sprint-status 2026-06-19 + memory), **not** introduced by this story. The V17 SQL and `FlywaySchemaContractTest.java` produce **zero** checkstyle violations.

### Completion Notes List

- **Pure schema story — zero `src/main/java` change.** Delivered `V17__create_projects_and_credentials.sql` + extended `FlywaySchemaContractTest`. No entities, domain, services, REST, config/`application.yml`, OpenAPI snapshot, runner-contracts schema, or frontend touched.
- **All key reconciliations honored:** R1 V14→**V17** (head was V16); R2 `bigserial id` PK + `public_id text` (`prj_`/`cred_` are public_id *prefixes*, never PKs); R3 `project_id` is **text** FK → `projects.public_id` on `workflow_runs`, `integration_links`, and `project_credentials` (`on delete restrict on update cascade`); R4 `project_credentials` carries `public_id`/`created_at`/`archived_at`; R5 inline CHECK value sets `project_status('active','disabled')` + `connector_kind('linear','github')` on both kind columns; R6 no second broken-migration fixture (existing `malformedMigrationFailsFastWithSyntaxError` + foundation contract cover fail-fast); R7 `CORE_TABLES`+`EXPECTED_PUBLIC_ID_PREFIX` extended (forces the bigserial+public_id+retention shape on the 2 new tables); R8 stayed out of `PublicIdPrefixes.java`/`RegistryContractTest`/API manifest.
- **`foreignKeysReferenceExpectedTablesAndColumns` 8-FK count left unchanged** — the new FKs are on `project_id`, not `workflow_run_id`.
- **Open Questions:** proceeded with all in-place defaults — OQ1 `text → projects.public_id` FK (not bigint); OQ2 `project_status` = `('active','disabled')` (no `archived` first-class status); OQ3 `connector_kind` third proof kind deferred to 3c-3. None blocking.
- **Logging instrumentation = N/A (rationale):** this is a declarative Flyway SQL migration + a Testcontainers schema-contract test — no Java service/SPI/persistence/state-machine code, hence no service entry/exit points, `DomainException` raise sites, SPI calls, or recovery branches to instrument. Flyway already logs migration application at INFO. The credential redaction obligation (ciphertext/`key_id`/`algo`/master key must never reach logs/events/artifacts/exports) is foreshadowed here and becomes a foundation-gate assertion when the credential code lands (3c-4/3c-5).
- **Out-of-scope note:** `spotless:apply` also reformatted `IntegrationLinkRepository.java`, a file already modified (and already failing the spotless gate) in the working tree from other uncommitted work — google-java-format whitespace only, no semantic change. Excluded from this story's File List since the logical change belongs elsewhere.

### File List

- `deliveryline-backend/src/main/resources/db/migration/V17__create_projects_and_credentials.sql` (new)
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` (modified)

### Change Log

- 2026-06-20 — Story 3c-1 implemented: added Flyway `V17__create_projects_and_credentials.sql` (`projects` + `project_credentials` core tables with `prj_`/`cred_` public_id prefixes; nullable `project_id text` FK → `projects.public_id` on `workflow_runs` + `integration_links`) and extended `FlywaySchemaContractTest` (2 new `CORE_TABLES`, `prj_`/`cred_` prefixes, 3 focused schema/CHECK/FK tests). All gates green (FlywaySchemaContractTest 18/18, RegistryContractTest, foundation-gate). Status → review.

### Review Findings (code review 2026-06-20)

- [x] [Review][Patch] Credential uniqueness enforces "one ever," not "one active" — `uq_project_credentials_project_role` was a plain `unique (project_id, connector_role)` with no `where archived_at is null` partial predicate, but AC5 and the inline SQL comment both say "one **active** secret per role per project." Codebase precedent is the partial pattern (V6 `uq_integration_links_active_linear_ref ... where archived_at is null`). As written, archiving a credential and re-issuing the same role hit a uniqueness violation → blocked rotation. **FIXED 2026-06-20:** converted to a partial `create unique index ... where archived_at is null`; added a rotation test (archive → re-issue same role succeeds) to `projectCredentialsSchemaCarriesExpectedColumnsConstraintsAndIndexes`. [`V17__create_projects_and_credentials.sql`]
- [x] [Review][Patch] Test helpers seeded `public_id`+`slug` from `System.nanoTime()` and asserted rejections with broad `assertThrows(Exception.class)` — coarse-resolution ticks (Windows) could collide on `uq_projects_slug`/`uq_*_public_id` (spurious failure), and a UNIQUE collision could make a CHECK-rejection test pass for the wrong reason (false green). **FIXED 2026-06-20:** added a per-instance `AtomicLong` salt (`uniqueRowSuffix()`) used by all three insert helpers, and narrowed `assertProjectInsertRejected` to assert the failure cites the expected `ck_*` constraint name. [`FlywaySchemaContractTest.java`]
- [x] [Review][Patch] Weak `openspec_enabled` default assertion used `.contains("false")` — would also pass for any `column_default` string embedding `false`. **FIXED 2026-06-20:** exact-match `assertEquals("false", openspecDefault, ...)`. [`FlywaySchemaContractTest.java`]
- [x] [Review][Defer] No partial `archived_at` retention index on `projects`/`project_credentials` [`V17__create_projects_and_credentials.sql`] — deferred, V1 convention (`idx_*_archived_at ... where archived_at is not null`) but no retention consumer exists yet (Epic 5).
