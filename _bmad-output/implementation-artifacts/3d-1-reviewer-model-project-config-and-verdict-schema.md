# Story 3d.1: Reviewer-Model Project Config + Advisory-Verdict Schema (Flyway)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer,
I want a per-project (optionally per-stage) reviewer-model binding plus a persisted advisory-verdict record,
so that each project can nominate a second LLM to review step output and the verdict has a durable, gating-capable home.

## Context

This is the **foundation story of Epic 3d** (Per-Step Execution Control, Observability & Manual Execution), inserted between Epic 3c and Epic 4. It is the schema/registry/domain-record half of the **per-step advisory reviewer model** capability (PRD FR64, ADR 0026). It is a structural twin of the Epic 3c foundation stories: it mirrors the shape of **3c-1** (pure Flyway schema), **3c-2** (domain record + registries + prefixes + error codes), and **3c-5** (the `project_credentials` connector-role widening pattern).

**3d-1 is intentionally a pure foundation story:** it creates the migration, the registries, the domain `Project` fields, the `step_reviews` advisory-verdict table, and one new error code — and **nothing executes the reviewer yet**. Story **3d-2** runs the reviewer through `ProjectConnectorResolver`, persists verdicts, and surfaces them in the `WaitingForReview` Decision Bar. Keep that boundary: do **not** wire any reviewer execution, REST surface, or frontend in 3d-1.

**Dependencies:** Epic 3c complete — specifically **3c-5** (the encrypted credential store: `ProjectCredentialService` / `ProjectCredentialSource`, which is what AC8's "encrypted reviewer-credential round-trip via the existing store" exercises) and **3c-2** (the `Project` record + registry + prefix conventions). Both are `done`/merged on `main`.

## Acceptance Criteria

> Copied verbatim from the epic (`_bmad-output/planning-artifacts/epic-03d-per-step-execution-control.md`, Story 3d-1). Already BDD-formatted.

1. **Given** the V18+ migration head, **When** the backend starts, **Then** Flyway applies the next free version adding reviewer-model configuration to the project model (a per-project reviewer connector binding, optionally scoped per stage) and a `step_reviews` (advisory-verdict) table — version is the next free number; replay is a no-op.
2. **Given** the reviewer binding, **Then** it reuses the Epic 3c connector/credential model: a reviewer credential is stored via the existing `project_credentials` mechanism (a `reviewer` connector role added to the role CHECK), encrypted at rest (ADR 0013); no new credential subsystem.
3. **Given** `step_reviews`, **Then** it stores `id` (`rev_` public-id prefix registered), `workflow_run_id` FK, `runner_execution_id` FK (the review run), `reviewed_artifact_id` + reviewed artifact version, `outcome text NOT NULL` (CHECK in a `review_outcome` value set, e.g. `pass`/`concern`/`fail`), `rationale text`, `reviewer_model_identity text`, `producer_model_identity text`, `created_at timestamptz NOT NULL DEFAULT now()`, `archived_at timestamptz NULL` (retention-readiness rule).
4. **Given** the gating-capable decision (ADR 0026), **Then** a per-project `reviewer_gating_enabled boolean NOT NULL DEFAULT false` exists so a failing verdict can block progression later **without** schema rework; the flag is **not** read by any gating logic in this epic.
5. **Given** the central registries + drift-test pattern (story 1.4 / 3c-2), **Then** `review_outcome`, the `reviewer` connector role, and the `rev_` prefix are added to the authoritative registries and drift-tested against the DB CHECK + API schema + any frontend allowed-value lists.
6. **Given** new domain error codes as needed (e.g. `REVIEWER_MODEL_NOT_CONFIGURED`), **Then** they follow the DomainErrorCode three-sites rule (ProblemDetailsCatalog + registry-api-schema-placeholders manifest) verified under `-Pfoundation-gate`.
7. **Given** ArchUnit boundaries, **Then** reviewer config + verdict logic lives in `application.project` / domain; no adapter imports leak into the domain.
8. **Given** tests, **Then** coverage asserts migration replay safety, registry/prefix drift, the encrypted reviewer-credential round-trip via the existing store, and that `reviewer_gating_enabled` defaults false and is never consulted by progression logic in this epic.

## Tasks / Subtasks

- [x] **Task 1 — Flyway migration `V19__add_reviewer_model_and_step_reviews.sql` (AC: #1, #2, #3, #4)**
  - [x] Create `deliveryline-backend/src/main/resources/db/migration/V19__add_reviewer_model_and_step_reviews.sql`. The current head on disk is **V18** (`V18__widen_connector_kind_to_gitlab.sql`); the next free version is **V19**. (The epic says "V18+"; this is that next free number — same stale-version drift the repo has absorbed before. Keep the story-key slug as-is; do not rename.)
  - [x] **Widen the project model:** `alter table projects add column reviewer_model_kind text null;` and `alter table projects add column reviewer_gating_enabled boolean not null default false;`. `reviewer_model_kind` is nullable — a NULL binding means "no reviewer," preserving pre-3d behavior (ADR 0026 Decision 1). **Do not** add a DB CHECK on `reviewer_model_kind` (see Design Decision DD-1).
  - [x] **Widen the connector-role CHECK** to add `reviewer`, using the drop-then-readd idiom (precedent: V12/V16/V18):
    ```sql
    alter table project_credentials drop constraint ck_project_credentials_connector_role;
    alter table project_credentials add constraint ck_project_credentials_connector_role
        check (connector_role in ('ticket_source', 'repo_host', 'reviewer'));
    ```
    The existing partial unique index `uq_project_credentials_project_role on (project_id, connector_role) where archived_at is null` already scopes "one active reviewer credential per project" automatically — **no index change needed**.
  - [x] **Create `step_reviews`** following the universal core-table shape (`id bigserial` PK + `public_id text` + format CHECK + `created_at` + `archived_at`):
    ```sql
    create table step_reviews (
        id bigserial primary key,
        public_id text not null,
        workflow_run_id bigint not null,
        runner_execution_id bigint not null,
        reviewed_artifact_id bigint not null,
        reviewed_artifact_version integer not null,
        outcome text not null,
        rationale text null,
        reviewer_model_identity text null,
        producer_model_identity text null,
        created_at timestamptz not null default now(),
        archived_at timestamptz null,
        constraint uq_step_reviews_public_id unique (public_id),
        constraint ck_step_reviews_public_id_format check (public_id ~ '^rev_[A-Za-z0-9_-]{4,64}$'),
        constraint ck_step_reviews_outcome check (outcome in ('pass', 'concern', 'fail')),
        constraint fk_step_reviews_workflow_runs foreign key (workflow_run_id)
            references workflow_runs (id) on delete restrict on update cascade,
        constraint fk_step_reviews_runner_executions foreign key (runner_execution_id)
            references runner_executions (id) on delete restrict on update cascade,
        constraint fk_step_reviews_artifacts foreign key (reviewed_artifact_id, reviewed_artifact_version)
            references artifacts (id, version) on delete restrict on update cascade
    );
    ```
    The composite FK into `artifacts (id, version)` matches the **approvals** precedent (`uq_artifacts_id_version`) — it pins the verdict to the exact reviewed artifact version (AC3). Use `on delete restrict` like every sibling FK.
  - [x] Migration is **additive + replay-safe** by construction (Flyway versioning; no data backfill, no destructive change). No need to author a broken migration — `malformedMigrationFailsFastWithSyntaxError` already covers fail-fast.

- [x] **Task 2 — Registries: `review_outcome`, `reviewer` role, `rev_` prefix (AC: #2, #3, #5)**
  - [x] **New `ReviewOutcome` enum** in `org.dradgo.domain.registry` on the `ArtifactStatus`/`ProjectStatus` template: `PASS("pass"), CONCERN("concern"), FAIL("fail")` implementing `RegistryValue`, with the standard `LOOKUP`/`fromValue(raw)`/`fromValue(raw, field)` members.
  - [x] **`DomainRegistry`:** add `private static final Set<String> REVIEW_OUTCOMES = valuesOf(ReviewOutcome.values());` + public `reviewOutcomes()` accessor (mirrors `artifactStatuses()`).
  - [x] **`ConnectorRole` enum** (`org.dradgo.domain.registry.ConnectorRole`): add `REVIEWER("reviewer")`. Wire value is **underscored/lowercase** `reviewer` to match the DB CHECK — see the ConnectorRole wire-value doctrine ([[story-3c-5-credential-store-redaction-reconciliations]] R1). No `DomainRegistry` accessor for connector roles exists (deliberate — they're compared directly in the drift test); don't add one.
  - [x] **`PublicIdPrefixes` enum** (`org.dradgo.domain.id.PublicIdPrefixes`): add `REVIEW("review", "rev_", "ck_step_reviews_public_id_format")`. The `constraintName()` must exactly equal the migration's format-CHECK name so `extractPublicIdPrefixesFromSql()` finds it.

- [x] **Task 3 — Domain `Project` record fields (AC: #4, #7)**
  - [x] Add to `org.dradgo.domain.project.Project` (record): `String reviewerModelKind` (nullable) and `boolean reviewerGatingEnabled`, positioned **before** `archivedAt` to keep the column-mirror ordering. Validate in the compact constructor: if `reviewerModelKind != null && reviewerModelKind.isBlank()` → `throw new IllegalArgumentException(...)` (the record's existing precedent for value-object invariants; `publicId` stays the only typed-`DomainException` field).
  - [x] Fan-out: the `Project` persistence mapper / row-mapper and any full-arg `new Project(...)` construction sites (e.g. the 3c-6 default-project seeder, `ProjectStore`/`ProjectEntity` mapping) must pass the two new fields. Default-project seed value: `reviewerModelKind = null`, `reviewerGatingEnabled = false` (no reviewer for the seeded default). Grep for `new Project(` and the projects row-mapper to find every site.
  - [x] Keep all reviewer config/verdict types in `domain` / `application.project` — **no adapter imports in domain** (AC7, ArchUnit `ArchitectureBoundaryTest`).

- [x] **Task 4 — New `DomainErrorCode` (three sites) (AC: #6)**
  - [x] **Site 1** — `org.dradgo.domain.registry.DomainErrorCode`: add `REVIEWER_MODEL_NOT_CONFIGURED("REVIEWER_MODEL_NOT_CONFIGURED")`.
  - [x] **Site 2** — `org.dradgo.adapters.rest.ProblemDetailsCatalog`: add a `register(metadata, DomainErrorCode.REVIEWER_MODEL_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE, "Reviewer model not configured", false)` call. (`SERVICE_UNAVAILABLE` + non-retryable mirrors the other "config-absent advisory" codes; the type URI auto-derives via `toUriSlug`.)
  - [x] **Site 3** — `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`: add `"REVIEWER_MODEL_NOT_CONFIGURED": "https://deliveryline.local/problems/reviewer-model-not-configured"` to `problemTypeUris`.
  - [x] This code is **registered ahead of its throw site** (the throw lands in 3d-2's resolver path). `ProblemDetailsCoverageFoundationContract` round-trips every registered code, so registration alone passes the gate — no production throw site is required in 3d-1. (Same ahead-of-use pattern as 3c-2's `PROJECT_NOT_FOUND` etc.) See [[new-domainerrorcode-three-sites]].

- [x] **Task 5 — Drift tests + schema-contract tests (AC: #5, #8)**
  - [x] **`RegistryContractTest`** (`org.dradgo.contract`):
    - In the registry-vs-itself test (alongside `assertEquals(registryValues(ProjectStatus.values()), DomainRegistry.projectStatuses())`): add `assertEquals(registryValues(ReviewOutcome.values()), DomainRegistry.reviewOutcomes())`.
    - Add a new drift test `reviewOutcomeStaysAlignedWithSqlCheckAndApiManifest()` asserting `DomainRegistry.reviewOutcomes()` equals `extractConstraintValues("ck_step_reviews_outcome")` **and** equals `readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "reviewOutcomes")` (mirror `artifactStatuses` — see DD-2 for why `review_outcome` gets an API placeholder leg but `reviewer` role does not).
    - In `connectorRoleStaysAlignedWithProjectCredentialsCheck()`: no code change needed — it already compares `ConnectorRole.values()` directly to `ck_project_credentials_connector_role`; adding `REVIEWER` to the enum + the widened CHECK keeps it green. (Just confirm it stays green.)
    - In `everyCurrentPersistenceBoundaryUsesUniformFailFastRegistryParsing`: add a `PersistedRegistryValues.stepReviewOutcome(...)` accessor (`ReviewOutcome.fromValue(raw, "step_reviews.outcome")`) and register `registryBoundaries.put("step_reviews.outcome", PersistedRegistryValues::stepReviewOutcome)`. (The reviewer connector-role boundary is **already** covered by the existing `project_credentials.connector_role` entry — do **not** add a `step_reviews.connector_role`; there is no such column.)
    - `extractPublicIdPrefixesFromSql()` / `publicIdPrefixSqlRegexExactlyMatchesJavaSuffixPattern` are enum-driven — adding the `REVIEW` enum entry makes `rev_` covered automatically once the format CHECK exists.
  - [x] **`registry-api-schema-placeholders.json`:** add a `"reviewOutcomes": ["pass", "concern", "fail"]` array (the API-schema leg of AC5; DD-2).
  - [x] **`FlywaySchemaContractTest`** (`org.dradgo.contract`):
    - Add `"step_reviews"` to `CORE_TABLES`.
    - Add `Map.entry("step_reviews", "rev_")` to `EXPECTED_PUBLIC_ID_PREFIX`.
    - Update the FK-shape expectations: `step_reviews` adds a **new `workflow_run_id` FK** (the `foreignKeysReferenceExpectedTablesAndColumns` workflow_run_id-FK count goes up by one), plus a `runner_execution_id` FK and the composite `(reviewed_artifact_id, reviewed_artifact_version) → artifacts(id, version)` FK. Adjust whatever expected counts / FK-target assertions the test enforces so all three new FKs are accounted for. **Run this Testcontainers test** to discover the exact assertions to update — don't guess the counts.
    - Add focused `@Test`s: `stepReviewsSchemaMatchesContract...` (columns/CHECK/FKs present), and a replay assertion (fresh container migrates through V19 cleanly).
  - [x] **Encrypted reviewer-credential round-trip (AC8):** a focused test that stores a credential with `connectorRole = reviewer` through the existing **3c-5** `ProjectCredentialService` and asserts it round-trips (encrypted at rest, write-only store, in-memory decrypt) — proving the `reviewer` role is a first-class credential role with no new subsystem. (Reuses the 3c-5 store + 3c-4 cipher unchanged.)
  - [x] **Gating-flag inertness (AC4/AC8):** a focused test asserting `reviewer_gating_enabled` defaults `false` and that **no** progression/transition logic reads it in this epic (e.g. assert the column default + a `Project` with the flag unset, and document that no production code references `reviewerGatingEnabled` yet — a grep-style guard or an explicit "not consulted" assertion).

- [x] **Task 6 — Foundation-gate + OpenAPI verification (AC: #5, #6)**
  - [x] Run `-Pfoundation-gate verify` — RegistryContractTest (Contract #3), ProblemDetailsCoverageFoundationContract (Contract #7), FlywaySchemaContractTest, and FoundationGateVerificationTest must all be green. (Do **not** add a new `@Nested ContractNN` here — extend the existing delegate tests, per 3c-2 R5. The Epic 3d gate-widening consolidation is **3d-9**.)
  - [x] Adding a `DomainErrorCode` does **not** by itself require an OpenAPI regen (the three sites are enum + catalog + placeholder manifest). 3d-1 ships **no REST endpoint**, so the OpenAPI snapshot should be byte-identical. If `check:api` reports drift, regenerate per the OpenAPI flow and commit `schema.d.ts` — see [[openapi-regen-frontend-client-drift-cascade]]. Otherwise leave the snapshot untouched.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **N/A for 3d-1 — and that is the correct, honest answer.** This story adds only a Flyway migration, registry enums, two `Project` record fields, and one ahead-of-use error code. There is **no new application-service entry/exit, no new SPI/DB-write call site, and no new retry/replay/recovery branch** introduced by 3d-1 (the `Project` record validation is a domain value-object invariant, which by convention is not logged). The reviewer-execution logging surface — reviewer runner invocation entry/exit, credential resolution, verdict persistence, graceful-degradation `WARN` — lands in **3d-2**, where the runtime behavior exists to instrument. Do not invent log lines for a schema/registry change.
  - [x] If implementation reveals an unforeseen new service branch, apply the full logging standard below (SLF4J parameterized, correct levels, correlation keys, no secrets, pinned by a focused log assertion).

### Review Findings

> Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor), 2026-06-21. Scope: the 3d-1 File List only (working tree also carried unrelated uncommitted 3d-5 work, which was excluded). Acceptance Auditor: **all 8 ACs satisfied; DD-1/DD-2/DD-3 and all documented traps honored.**

- [x] [Review][Patch][FIXED] Read-path is unguarded against a blank `reviewer_model_kind` while DD-1 leaves no DB CHECK — `ProjectEntityMapper.toDomain` fed `entity.getReviewerModelKind()` straight into the `Project` compact constructor, which throws `IllegalArgumentException` on a non-null blank (`Project.java:53-54`). DD-1 deliberately adds no DB CHECK, so an empty/whitespace `reviewer_model_kind` row is persistable but was **un-readable**; because `ProjectManagementService.getAll` → `ProjectStore.findAll()` streams every row, a single bad row would have rendered the whole project list (plus the doctor `projects` check and the health metrics binder) inaccessible for ALL projects. **Fix:** `toDomain` now coerces blank→`null` ("no reviewer"), making the read path symmetric with the canonical NULL semantic and robust to a future 3d-2 bug / direct SQL / legacy row (honors DD-1 — no DB CHECK added). Pinned by new `ProjectEntityMapperTest` (blank/whitespace→null, null stays null, non-blank round-trips) — 6/6 green. [`ProjectEntityMapper.java:31`, `ProjectEntityMapperTest.java`]

- [x] [Review][Defer] `step_reviews` has no uniqueness beyond `public_id` — no partial-unique on `(workflow_run_id, runner_execution_id, reviewed_artifact_id, reviewed_artifact_version) where archived_at is null`, so unlimited duplicate verdicts for the same reviewed artifact version are storable. AC3 specifies the exact column/constraint set and uniqueness is not among them; the "one active per key" partial-index decision belongs to the 3d-2 consumer that defines verdict semantics. [`V19__add_reviewer_model_and_step_reviews.sql`] — deferred, design choice owned by 3d-2 (table is dormant in 3d-1).
- [x] [Review][Defer] `step_reviews` FKs are `on delete restrict` against `workflow_runs`/`runner_executions`/`artifacts` with no soft-reference escape — once a real purge of a run/execution is attempted, referencing verdict rows will block the delete. Matches the sibling-FK precedent and the table is unwritten in 3d-1. [`V19__add_reviewer_model_and_step_reviews.sql`] — deferred, true purge is owned by Epic 5.

**Dismissed as noise (5):** `ProjectTest` arg-order (self-retracted by the hunter — ordering is correct); `ReviewOutcome.fromValue(String)` "dead code" (intentional registry-template parity with `ArtifactStatus`/`ProjectStatus`, tolerated by checkstyle on the siblings); `on update cascade` on the FKs (matches the story's verbatim Task-1 SQL + approvals precedent; `bigserial` PKs never update so it's inert); V19 header "no data backfill" comment nit (metadata-only `NOT NULL DEFAULT` add, no rewrite); mapper/`ProjectManagementService` update-path coupling note (consistent today, no defect).

## Dev Notes

### What this story is (and is not)

- **Is:** the durable, gating-capable *home* for the reviewer feature — a migration (V19), the `review_outcome`/`reviewer`/`rev_` registries, the `Project` reviewer fields, the `step_reviews` table, and one ahead-of-use error code.
- **Is not:** any reviewer execution, REST surface, CLI, frontend, or gating logic. Those are 3d-2 (execution + Decision Bar panel) and later. `reviewer_gating_enabled` is created **off** and is read by nothing in Epic 3d (ADR 0026 Decision 3).

### Design decisions (record in PR + Completion Notes)

- **DD-1 — `reviewer_model_kind` is nullable `text` with no DB CHECK.** The epic phrase "reviewer connector binding" is satisfied by a per-project nullable column; NULL = no reviewer (opt-in parity, AC2/ADR 0026 D1). I deliberately do **not** add a CHECK constraining it to the `RunnerKind` value-set, because the authoritative validation of "which model reviews" is the `ProjectConnectorResolver` at execution time (3d-2), and a DB CHECK would couple `projects` to the runner-kind set (which 3d-3 mutates by adding `manual`) and force an extra drift assertion for a value the resolver already guards. The `Project` record stores it as a nullable opaque `String` (validated non-blank-if-set). **OQ-1:** bind `reviewer_model_kind` to the `RunnerKind` registry (enum field + CHECK + drift) now vs. defer to 3d-2 — default taken: **defer** (opaque text now).
- **DD-2 — `review_outcome` gets an API-placeholder drift leg; the `reviewer` connector role does not.** `review_outcome` will be surfaced through the API (the 3d-2 Decision Bar verdict panel reads it), so it is treated like `artifactStatuses`/`connectorKinds` — added to `registry-api-schema-placeholders.json` and drift-tested against both the DB CHECK and the API placeholder (AC5 "API schema" leg), registered ahead-of-use. The `reviewer` **connector role** is a credential-persistence value only (like `ticket_source`/`repo_host`), so it follows the `ConnectorRole` precedent: drift-tested against the DB CHECK **only**, no API placeholder. **Frontend allowed-value list = N/A in 3d-1** (no `review_outcome` frontend consumer until the 3d-2 panel — same deferral as 3c-2 R3).
- **DD-3 — per-project, not per-stage.** ADR 0026 explicitly leaves "per-project-only vs per-project-and-per-stage" as a 3d-1 detail and "allows either." Chosen: **per-project columns on `projects`** (simplest valid binding). Per-stage scoping, if ever needed, is a future additive table — not built now. **OQ-2:** confirm per-project-only is acceptable for the pilot (default: yes).

### Key seams & exact locations (verified against the tree)

- **Migrations dir:** `deliveryline-backend/src/main/resources/db/migration/` — head is `V18__widen_connector_kind_to_gitlab.sql`; author `V19__...`.
- **`project_credentials` CHECK to widen:** `ck_project_credentials_connector_role` (created in `V17__create_projects_and_credentials.sql`, currently `in ('ticket_source','repo_host')`). Drop-then-readd idiom precedent: `V12`/`V16` (`ck_runner_executions_status`), `V18` (`ck_projects_*_kind`).
- **`projects` table:** created in V17; add the two reviewer columns by `alter table`.
- **`artifacts` composite key for the verdict FK:** `artifacts` already has `constraint uq_artifacts_id_version unique (id, version)` (V1) so `step_reviews.(reviewed_artifact_id, reviewed_artifact_version) → artifacts(id, version)` is valid — same pattern the `approvals` table uses.
- **`PublicIdPrefixes`:** `org.dradgo.domain.id.PublicIdPrefixes` — declares `PROJECT("project","prj_","ck_projects_public_id_format")`, `PROJECT_CREDENTIAL("projectCredential","cred_","ck_project_credentials_public_id_format")`. Add `REVIEW`.
- **Registry enums:** `org.dradgo.domain.registry.{ConnectorRole, ConnectorKind, ProjectStatus, ArtifactStatus}` + `DomainRegistry`.
- **Project record:** `org.dradgo.domain.project.Project` (record mirroring the `projects` columns; compact-constructor invariants).
- **Error-code three sites:** `domain.registry.DomainErrorCode`, `adapters.rest.ProblemDetailsCatalog`, `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`.
- **Drift/contract tests:** `org.dradgo.contract.RegistryContractTest` (the `assertEquals(registryValues(...), DomainRegistry...)` block ~L118; `projectStatusAndConnectorKind...` ~L185; `connectorRoleStaysAlignedWithProjectCredentialsCheck` ~L207; `everyCurrentPersistenceBoundary...` boundary map ~L449) and `org.dradgo.contract.FlywaySchemaContractTest` (`CORE_TABLES` ~L30, `EXPECTED_PUBLIC_ID_PREFIX` ~L46, FK-shape test).
- **Credential round-trip (AC8):** reuse 3c-5 `ProjectCredentialService` / `ProjectCredentialSource` + 3c-4 `CredentialCipher` (no changes) with `connectorRole = reviewer`.

### Traps (from sibling-story reconciliations — read before coding)

- **The `reviewer` wire value is `reviewer` (lowercase, no hyphen).** It joins `ticket_source`/`repo_host` in the DB CHECK and `ConnectorRole` enum. The resolver's hyphenated `TICKET_SOURCE_ROLE`/`REPOSITORY_HOST_ROLE` constants are **log labels only**, never persisted — there is no hyphenated reviewer constant to add ([[story-3c-5-credential-store-redaction-reconciliations]] R1).
- **Do not add a `step_reviews.connector_role` boundary.** There is no `connector_role` column on `step_reviews`. The reviewer role lives on `project_credentials.connector_role` (already a registered persistence boundary — just widened). The *new* persistence boundary for 3d-1 is `step_reviews.outcome → ReviewOutcome`.
- **`ConnectorRole.REVIEWER` may hit an exhaustive consumer.** Adding a value to `ConnectorRole` can break any exhaustive `switch`/`Map`/stream over `ConnectorRole.values()` that assumes only `{ticket_source, repo_host}` — most likely the **3c-3 `ProjectConnectorResolver`** (role→adapter indexing). 3d-1 does **not** resolve reviewer adapters (that's 3d-2), so the resolver should simply **ignore/skip** the `reviewer` role rather than throw `UNSUPPORTED_CONNECTOR_KIND`/NPE. Grep for `ConnectorRole.` consumers and confirm none assumes a closed two-value set; if a `switch` needs a `default`/skip branch, add it so a `reviewer` credential row is inert in 3d-1.
- **FK count in `FlywaySchemaContractTest`.** 3c-1 deliberately kept the `workflow_run_id`-FK count at 8 because its new FKs were on `project_id`. `step_reviews` **does** add a `workflow_run_id` FK, so that count (and the FK-target list) must be updated. Run the test to read the exact failing assertion rather than guessing ([[story-3c-1-projects-schema-reconciliations]]).
- **Stale-version drift.** The epic text says "V18+"; the next free number is **V19**. This is the same epic-vs-disk version drift the repo absorbed for V5→V15 and V14→V17. Use V19; keep the story-key slug unchanged ([[story-3c-1-projects-schema-reconciliations]]).
- **Gate-widening is delegate-extension, not a new `@Nested Contract`.** Extend `RegistryContractTest`/`ProblemDetailsCoverageFoundationContract`/`FlywaySchemaContractTest`; the Epic 3d `FoundationGateVerificationTest` `@Nested` contracts are story **3d-9** ([[story-3c-2-project-domain-and-registries-reconciliations]] R5, [[story-3c-11-foundation-gate-widening-reconciliations]]).
- **`Project` record fan-out.** Adding fields to the `Project` record breaks every full-arg `new Project(...)` site (mapper, seeder, tests). This is the same fan-out class as [[runnerproperties-record-component-fanout]] — grep `new Project(` and fix all sites; the default-project seeder passes `null`/`false`.
- **Adding a validated/CHECK'd column → test yaml is N/A here** (no validated `@ConfigurationProperties` added), but if a `@SpringBootTest` tier touches the new columns ensure Hibernate `validate` still passes — nullable unmapped columns are fine; the mapped `Project` fields must match the SQL types ([[validated-config-needs-test-yaml]] is the adjacent hazard, not triggered here).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above. **For 3d-1 the task is N/A (see that task for the rationale)** — there is no new runtime service surface; the reviewer-execution logging lands in 3d-2.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - Public application-service methods → `INFO` on entry + `INFO` on success / `WARN` on typed-domain rejection / `ERROR` on unexpected failure.
  - Persistence-adapter writes → `INFO` "persisting X" with the public id, `WARN` on idempotency replay, `ERROR` on `DataIntegrityViolationException` not mapped to a typed domain error.
  - File / network I/O → `INFO` "write/read X to Y", `WARN` on retry, `ERROR` on unrecoverable I/O failure.
  - State-machine transitions → `INFO` "transitioned X from {from} to {to}".
  - Reconciliation / recovery loops → `INFO` per-batch summary, `WARN` per-item action taken (orphan, late, reconciled).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus any entity public ids touched.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields. Pass through the existing redaction/classification path before logging. (The reviewer credential ciphertext must never be logged — relevant in 3d-2, not here.)
- **Test contract:** new logging surfaces must be pinned by at least one focused test (list-appender or Spring Boot `OutputCaptureExtension`).

### Project Structure Notes

- **No frontend, no REST, no CLI** in 3d-1. Frontend allowed-value drift for `review_outcome` is N/A until the 3d-2 Decision Bar panel.
- All net-new Java stays in `domain.registry`, `domain.id`, `domain.project`, and `adapters.rest` (the `ProblemDetailsCatalog` register call only). ArchUnit `ArchitectureBoundaryTest` must stay green — domain imports no adapter.
- New test files in `org.dradgo.contract` (extend existing `RegistryContractTest`/`FlywaySchemaContractTest`) + a focused credential-round-trip test in the credential-store test package (3c-5 vicinity).

### References

- [Source: _bmad-output/planning-artifacts/epic-03d-per-step-execution-control.md#Story 3d-1] — ACs (verbatim above) + epic framing.
- [Source: docs/adr/0026-per-step-advisory-reviewer-model.md] — advisory-now / gating-capable-later decision; binding rides the 3c per-project credential model; provenance; null binding = current behavior.
- [Source: docs/adr/0013-credential-encryption.md] — encryption-at-rest the reviewer credential reuses (no new subsystem).
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-21.md] — Epic 3d proposal (D2), FR64.
- [Source: deliveryline-backend/src/main/resources/db/migration/V17__create_projects_and_credentials.sql] — `projects` / `project_credentials` shape + `ck_project_credentials_connector_role`.
- [Source: deliveryline-backend/src/main/resources/db/migration/V18__widen_connector_kind_to_gitlab.sql] — head migration; drop-then-readd CHECK idiom.
- [Source: deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql] — `workflow_runs`/`runner_executions`/`artifacts` (incl. `uq_artifacts_id_version`) for the `step_reviews` FKs.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/{ConnectorRole,ProjectStatus,ArtifactStatus,DomainRegistry,DomainErrorCode}.java] — registry templates.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java] — prefix template.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/project/Project.java] — record to extend.
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/{RegistryContractTest,FlywaySchemaContractTest}.java] — drift/contract tests to extend.
- [Source: deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json] — error-code + API-placeholder manifest.
- Patterns: `docs/patterns/registry-recipe.md` (registry-value + DomainErrorCode three-sites recipes).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story

### Debug Log References

- Verification run with the contract/architecture tier (Failsafe) + targeted Surefire:
  - `RegistryContractTest` 21/21, `FlywaySchemaContractTest` 21/21, `ProjectTest` 12/12,
    `ProjectCredentialServiceTest` 10/10, `ProblemDetailsCoverageFoundationContract` 2/2.
  - `ArchitectureBoundaryTest`: the AC7 domain-no-adapter-import rule passes (the single failing
    rule, `rest_controllers_stay_thin_and_avoid_spi_or_persistence_or_runner`, is violated only by
    Story **3d-5**'s `RunnerLogStreamController`/`SseLogStreamSink`, not by 3d-1).
- One mid-implementation drift caught + fixed: the `publicIdPrefixes` object in
  `registry-api-schema-placeholders.json` had to gain `"review": "rev_"` (the existing
  `publicIdPrefixesStayAlignedWithSqlChecksAndParsingHelpers` drift gate compares the SQL + this
  manifest to `PublicIdPrefixes.prefixMap()`).

### Completion Notes List

- **DD-1 — `reviewer_model_kind` is nullable `text`, no DB CHECK.** Stored as an opaque
  non-blank-if-set `String` on the `Project` record; its value-set validation is the
  `ProjectConnectorResolver`'s job at execution time (3d-2). Avoids coupling `projects` to the
  `RunnerKind` set (which 3d-3 mutates by adding `manual`). **OQ-1 default taken: defer** binding to
  `RunnerKind` to 3d-2.
- **DD-2 — `review_outcome` gets an API-placeholder drift leg; the `reviewer` connector role does
  not.** `ReviewOutcome` is drift-tested against BOTH `ck_step_reviews_outcome` and the
  `reviewOutcomes` API placeholder; `ConnectorRole.REVIEWER` is drift-tested against the DB CHECK
  only (credential-persistence value, like `ticket_source`/`repo_host`). Frontend allowed-value list
  N/A until the 3d-2 panel.
- **DD-3 — per-project, not per-stage** (ADR 0026 allows either). Two columns on `projects`;
  per-stage scoping deferred to a future additive table. **OQ-2 default taken: per-project-only is
  acceptable for the pilot.**
- **`reviewer_gating_enabled` is inert (AC4/AC8).** DB default `false`; grep confirms it is
  referenced ONLY by the migration, the `Project` record, `ProjectEntity`, `ProjectEntityMapper`,
  and a preserve-on-update passthrough in `ProjectManagementService` — NO progression/transition
  logic consults it in Epic 3d. Pinned by `ProjectTest.reviewerBindingDefaultsToNoReviewerAndGatingOff`
  + `FlywaySchemaContractTest.projectsCarryReviewerBindingColumnsWithGatingDefaultOff`.
- **`REVIEWER_MODEL_NOT_CONFIGURED` is registered ahead of its throw site** (lands in 3d-2's
  resolver path); the foundation gate round-trips every registered code, so registration alone passes.
- **AC8 reviewer-credential round-trip** is proven through the existing 3c-5
  `ProjectCredentialService` + 3c-4 AES-256-GCM cipher unchanged
  (`ProjectCredentialServiceTest.reviewerRoleCredentialRoundTripsThroughTheExistingStoreEncryptedAtRest`)
  — `reviewer` is a first-class credential role, no new subsystem.
- **No `step_reviews.connector_role` boundary** (no such column); the new persistence boundary is
  `step_reviews.outcome → ReviewOutcome`. The `reviewer` role lives on
  `project_credentials.connector_role` (already a registered boundary, just widened).
- **OpenAPI:** no REST endpoint/DTO added → the snapshot is byte-identical (no regen needed).
- **`ConnectorRole.REVIEWER` exhaustive-consumer check:** grep confirms no production consumer
  iterates a closed two-value `ConnectorRole.values()` set; `ProjectController` references
  `TICKET_SOURCE`/`REPO_HOST` explicitly, so a `reviewer` credential row is inert in 3d-1.
- **⚠️ Out-of-scope working-tree note (NOT 3d-1):** this session's working tree already contained
  Story **3d-5**'s uncommitted/incomplete work (`RunnerLogStream*`, `SseLogStreamSink`,
  `StepLogStreamService`, `RedactedRunnerLog`, modified `AllowedAction` (`VIEW_RUNNER_LOGS`),
  `WorkflowInspectionService`, `RunnerLogStore`, docker gateways, etc.). That work currently breaks
  the full gate independently of 3d-1: 12 `WorkflowInspectionServiceAllowedActionsTest` failures
  (matrix not updated for `VIEW_RUNNER_LOGS`), ~17 Spotless violations, one Checkstyle unused-import
  (`RunnerLogStreamController`), and the `rest_controllers_stay_thin` ArchUnit rule. These are 3d-5's
  to finish — **left untouched.** The ONE exception: `LocalRunnerLogStore.java` (a 3d-5 file) was
  missing `import java.nio.charset.StandardCharsets;`, which broke ALL compilation; I added that one
  import so the module (and 3d-1's tests) could compile — flagging it here for the 3d-5 author.

### File List

**New (production):**
- `deliveryline-backend/src/main/resources/db/migration/V19__add_reviewer_model_and_step_reviews.sql`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ReviewOutcome.java`

**Modified (production):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ConnectorRole.java` (+`REVIEWER`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainRegistry.java` (+`reviewOutcomes()`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java` (+`stepReviewOutcome`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (+`REVIEWER_MODEL_NOT_CONFIGURED`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java` (+`REVIEW`/`rev_`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/project/Project.java` (+`reviewerModelKind`, `reviewerGatingEnabled`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/ProjectEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/ProjectEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/project/DefaultProjectSeeder.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectManagementService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerLogStore.java` (out-of-scope one-line import fix — see Completion Notes ⚠️)

**Modified (tests / contracts):**
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/domain/project/ProjectTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectCredentialServiceTest.java`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- Project-constructor fan-out (added `null, false` reviewer args): `ProjectHealthMetricsBinderTest`,
  `ProjectConnectorResolverWiringIT`, `DefaultProjectSeederTest`, `ProjectRuntimeConfigResolverTest`,
  `ProjectManagementServiceTest`, `ProjectConnectorResolverTest`, `ProjectConnectivityServiceTest`,
  `WorkflowCommandServiceCreateBindingTest`, `RunProjectAssociationIT`, `DoctorProbeAdapterTest`,
  `ProjectControllerContractTest`, `ProjectPersistenceAdapterIT`, `RunnerBrokerUnitTest`,
  `RepositoryWorkspaceServiceTest`, `WorkflowOrchestrationServiceTest`,
  `TicketSourceAbstractionFoundationContract`

## Change Log

| Date       | Version | Description                                                                 | Author |
|------------|---------|-----------------------------------------------------------------------------|--------|
| 2026-06-21 | 0.1     | Implemented 3d-1 foundation: V19 migration (reviewer cols + `step_reviews` + widened connector-role CHECK), `ReviewOutcome`/`ConnectorRole.REVIEWER`/`rev_` registries, `Project` reviewer fields + fan-out, `REVIEWER_MODEL_NOT_CONFIGURED` (3 sites), drift + schema + credential-round-trip + gating-inertness tests. All 3d-1 contract tests green. | Amelia (dev-story) |
