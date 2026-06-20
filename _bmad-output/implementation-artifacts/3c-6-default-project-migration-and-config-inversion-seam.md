# Story 3c.6: Default-Project Migration + Config-Inversion Seam

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **⚠️ READ FIRST — this is the Epic 3c "de-risking keystone."** It introduces the **first Project persistence/read side** (3c-2 shipped only the `domain.project.Project` record + registries; 3c-3 explicitly said "there is **no `Project` persistence/read side yet** — that is 3c-6/3c-8"), seeds a reserved **`default`** project from today's global config at startup, backfills every existing run to it, and inverts run-time config resolution so the **default project — not the global `@ConfigurationProperties` — becomes the source of truth**, while staying **byte-identical** for a single-project deployment. Everything you build on is already merged:
>
> - **`Project` aggregate** — `org.dradgo.domain.project.Project` (a `record`): `publicId, name, slug, status (ProjectStatus), repositoryUrl (nullable), ticketSourceKind (ConnectorKind), repoHostKind (ConnectorKind), openspecEnabled, createdAt, archivedAt`. Compact ctor validates via `PublicIdPrefixes.require(publicId, PROJECT)` (3c-2).
> - **Registries** — `ProjectStatus{active,disabled}`, `ConnectorKind{linear,github,gitlab}`; prefixes `prj_`/`cred_`; error codes `PROJECT_NOT_FOUND(404)`, `PROJECT_SLUG_CONFLICT(409)`, `UNSUPPORTED_CONNECTOR_KIND(400)` — **all three already three-sites-registered** (3c-2). **Consume only — do NOT re-register any.**
> - **`ProjectConnectorResolver`** + **`ProjectCredentialSource`** seam in `application.project` (3c-3). The credential seam **returns empty today**; adapters fall back to the host-env secret path (`LINEAR_API_TOKEN` / `GITHUB_TOKEN`). The encrypted credential store is **3c-5** (gated behind the 3c-4 security review) — **this story writes ZERO `project_credentials` rows.**
> - **The repo-ref seam** — `RepositoryWorkspaceService.resolveExpectedRepositoryRef(workflowRunId)` (3c-3 AC5) **today delegates to the global `resolveConfiguredRepositoryRef()`** with a `// 3c-3 AC5 seam — global fallback until run<->Project wiring (3c-6/3c-7)` marker. **This story is one of the two stories that swap point names** (3c-6 repoints it to the run's Project; 3c-7 threads `project_id` through every other consumer).
> - **Schema** — V17 (3c-1) created `projects` + `project_credentials` and added a **nullable** `workflow_runs.project_id text` + `fk_workflow_runs_projects` (→ `projects.public_id`) + `idx_workflow_runs_project_id`. Head migration on disk is **`V18`** (3c-3, the `gitlab` CHECK widening). **This story needs NO Flyway migration** — see R1.
>
> **The scope boundary with 3c-7 (read this twice).** This story owns the **default-project plumbing + the resolution seam**: read side, seeder, backfill, bind-new-runs-to-default at create, a `ProjectRuntimeConfigResolver`, and repointing the **one already-isolated** repo-ref seam — then **prove single-project parity**. Threading `project_id` through the **rest** of the hot path (runner bundle, dispatch, queue, Linear completion sync) so **non-default** projects take effect is **3c-7** (`backlog`, deps on this story). Where the epic 3c-6 ACs reach into that wiring, the reconciled ACs below split it explicitly — follow them, not the raw epic wording. Rationale: Dev Notes → "Why these ACs are reconciled."

## Story

As a backend developer,
I want today's global configuration migrated into a seeded `default` Project (read through a real persistence side), every existing run backfilled to it, and run-time config resolved per-project with a default fallback,
so that the global→per-project inversion lands without breaking any existing single-project flow — a single-project deployment behaves byte-identically to pre-3c.

## Acceptance Criteria

> These ACs are **reconciled** against the live codebase (no Project persistence yet; no data-seeding precedent; the run↔Project intake/dispatch wiring is 3c-7). Where the epic wording assumes infrastructure that does not exist yet or work that 3c-7 owns, the reconciled wording below is authoritative; the rationale is in Dev Notes.

1. **Given** today's global config (`deliveryline.workflow.repos.url`, `deliveryline.integration.ticket-source.kind` [default `linear`], `deliveryline.integration.repo-host.kind` [default `github`], `deliveryline.runner.openspec.enabled` [default `false`]), **When** the backend reaches `ApplicationReadyEvent` and **no project with slug `default` exists**, **Then** an application-level seeder inserts exactly one `Project` with `publicId = "prj_default"`, `slug = "default"`, `name = "Default Project"`, `status = ACTIVE`, `repositoryUrl = repos.url` (raw, nullable), `ticketSourceKind = ConnectorKind.fromValue(ticket-source.kind)`, `repoHostKind = ConnectorKind.fromValue(repo-host.kind)`, `openspecEnabled = runner.openspec.enabled`, `createdAt = now()`. **The default project writes NO `project_credentials` row** — it references the existing global env-var secrets via the adapters' existing host-env path (`LINEAR_API_TOKEN` / `GITHUB_TOKEN`); the encrypted credential store is 3c-5 (R4).

2. **Given** V17 added `workflow_runs.project_id` as **nullable**, **When** the seeder has ensured the `default` project exists, **Then** it backfills **all** `workflow_runs` rows where `project_id IS NULL` to `prj_default` (single `@Modifying` UPDATE), and from then on the application **binds every new run to a resolved project at create time** — `WorkflowCommandService.submit` resolves the `default` project and passes its `project_id` into the create path; a create that cannot resolve a project is rejected with the registered `PROJECT_NOT_FOUND` (R6, R8). (Resolving a run's project from an **explicit reference or ticket-source binding** is 3c-7; 3c-6 resolves to the `default` project only.)

3. **Given** run-time config resolution, **Then** a new `ProjectRuntimeConfigResolver` in `application.project` resolves the **effective repository binding, connector kinds, and OpenSpec flag for a run from its Project, falling back to the `default` project** when the run carries no `project_id` (or the project has no binding). `RepositoryWorkspaceService.resolveExpectedRepositoryRef(workflowRunId)` is **repointed** from the global `resolveConfiguredRepositoryRef()` to this resolver (run → Project → `repositoryUrl` ref, default fallback). The global `@ConfigurationProperties` records (`WorkflowProperties.RepoConfig`, `TicketSourceProperties`, `RepositoryHostProperties`, `RunnerProperties.OpenSpec`) **remain bound (normalize-never-throw) only as the seeder's seed source** and are no longer the source of truth for the repo-ref seam. (Repointing the remaining hot-path consumers — runner bundle composition, `DockerRunnerAdapter` dispatch, the queue, Linear completion sync — is **3c-7**; they need the per-run `project_id` threading 3c-7 owns.)

4. **Given** a single-project deployment that sets only the old global keys, **Then** behavior is **byte-identical** to pre-3c: the seeded `default` project carries exactly the global values, so the repointed repo-ref seam yields the same expected ref the broker already passes (expected == requested ⇒ no new `LINEAR_GITHUB_REPO_MISMATCH`), and a parity test asserts no behavioral drift through `prepareWorkspace`.

5. **Given** idempotent seeding, **When** the app restarts (the seeder fires again), **Then** the `default` project is **not** duplicated — the seeder is keyed on the reserved `default` slug (`findBySlug("default")` short-circuits), with the `uq_projects_slug` / `uq_projects_public_id` unique constraints + a caught `DataIntegrityViolationException` as the concurrent-startup backstop (logged `WARN`, treated as already-seeded). The backfill UPDATE is naturally idempotent (`where project_id is null`).

6. **Given** the OpenSpec opt-in, **Then** `openspecEnabled` now lives on the Project (seeded from `deliveryline.runner.openspec.enabled`); the global flag becomes the `default` project's seed value. **3c-6 establishes the flag on the project + exposes it through `ProjectRuntimeConfigResolver`; the `DockerRunnerAdapter` dispatch consumer is repointed in 3c-7.** Flag-off (the default) remains byte-identical — the resolver returns `false` for the default project and no `DELIVERYLINE_RUNNER_OPENSPEC` env is emitted, preserving the 2026-06-13 OpenSpec proposal's default-off guarantee.

7. **Given** the persistence boundary this story introduces, **Then** `ProjectEntity` stores `status`/`ticket_source_kind`/`repo_host_kind` as `text` parsed at the getter via **new** `PersistedRegistryValues.projectStatus(...)` / `projectTicketSourceKind(...)` / `projectRepoHostKind(...)` wrappers, and `RegistryContractTest.everyCurrentPersistenceBoundaryUsesUniformFailFastRegistryParsing` gains the **three** boundaries `projects.status`, `projects.ticket_source_kind`, `projects.repo_host_kind` (the work 3c-2 explicitly deferred here). Unknown DB values fail fast with `UNKNOWN_REGISTRY_VALUE`.

8. **Given** ArchUnit boundaries, **Then** the new application code (`ProjectStore` port, `ProjectRuntimeConfigResolver`, `DefaultProjectSeeder`) lives in `application.project` and depends only on `domain.*` + application ports — never on `adapters..`/`infrastructure..`; the new JPA `ProjectEntity`/`ProjectRepository`/`ProjectEntityMapper`/`ProjectPersistenceAdapter` reside in `org.dradgo.adapters.persistence.{entity,repository,mapper}` and implement the application-owned `ProjectStore` port (the `application-cannot-import-adapters` rule).

9. **Given** tests, **Then** coverage asserts: seed-from-global-config (values mirror the config), no-credential-row-written, backfill of existing null-`project_id` runs, no-duplicate-on-restart (seeder runs twice ⇒ one row), default-fallback resolution (run with null `project_id` → default config), new-run binding to the default project + `PROJECT_NOT_FOUND` when no default resolves, the three new registry persistence boundaries (AC7), and **full single-project behavioral parity** (AC4). New `application.project` + `adapters.persistence` project code meets the standing **≥80% line-coverage** floor.

## Tasks / Subtasks

- [x] **Task 1 — Add the `ProjectStore` application port + the three registry persistence-boundary wrappers** (AC: 1, 2, 7, 8)
  - [x] Create `org.dradgo.application.project.ProjectStore` (port): `Optional<Project> findBySlug(String slug)`, `Optional<Project> findByPublicId(String publicId)`, `Project insert(Project project)`, and `int backfillNullProjectIds(String defaultProjectPublicId)` (returns rows updated). Keep it minimal — CRUD/disable for the REST API is **3c-8**; this port serves the seeder + resolver only.
  - [x] `PersistedRegistryValues.java`: add `public static ProjectStatus projectStatus(String rawValue)` → `ProjectStatus.fromValue(rawValue, "projects.status")`; `projectTicketSourceKind(...)` → `ConnectorKind.fromValue(rawValue, "projects.ticket_source_kind")`; `projectRepoHostKind(...)` → `ConnectorKind.fromValue(rawValue, "projects.repo_host_kind")`. (These are the wrappers 3c-2 R7 deferred to "when rows are first read/written through the app" — that is now.)
  - [x] `RegistryContractTest.everyCurrentPersistenceBoundaryUsesUniformFailFastRegistryParsing`: add the three `registryBoundaries.put("projects.status", PersistedRegistryValues::projectStatus)` etc. entries. The test auto-asserts each throws `UNKNOWN_REGISTRY_VALUE` with `field`/`value`/`registry` details and bumps the expected boundary count.
- [x] **Task 2 — Build the Project read/write persistence side (first occupant of project persistence)** (AC: 1, 2, 7, 8)
  - [x] `adapters/persistence/entity/ProjectEntity.java` — `@Entity @Table(name = "projects")`; `id bigserial` via `@GeneratedValue(IDENTITY)`; `public_id`, `name`, `slug` String columns; `status`/`ticket_source_kind`/`repo_host_kind` stored as raw `String`, parsed at the getter through the new `PersistedRegistryValues` wrappers (mirror `WorkflowRunEntity.getCurrentState()`); `openspec_enabled boolean`; `created_at` (`insertable=false, updatable=false`, DB default); `archived_at` nullable. **No mapping for `project_credentials`** (that table stays unmapped until 3c-5).
  - [x] `adapters/persistence/repository/ProjectRepository.java` — `extends JpaRepository<ProjectEntity, Long>`; `Optional<ProjectEntity> findBySlug(String slug)`; `Optional<ProjectEntity> findByPublicId(String publicId)`; a `@Modifying @Query("update WorkflowRunEntity r set r.projectId = :pid where r.projectId is null")` backfill **OR** a JDBC/`@Modifying` native update against `workflow_runs` (see Task 4 sub-decision on where the WR `project_id` mapping lives).
  - [x] `adapters/persistence/mapper/ProjectEntityMapper.java` — `@Component`, explicit `toDomain(ProjectEntity)` / `toNewEntity(Project)` (no MapStruct; match the `IntegrationLinkEntityMapper`/`WorkflowRunEntityMapper` pattern).
  - [x] `adapters/persistence/ProjectPersistenceAdapter.java` — `@Component`, implements `ProjectStore`, `@Transactional` on writes; `insert` maps the domain `Project` → entity → `saveAndFlush` → back to domain; `backfillNullProjectIds` runs the `@Modifying` update and returns the count.
  - [x] `pom.xml`: add the new persistence package to the JaCoCo per-package **0.80** floor block (alongside `org.dradgo.application.project` from 3c-3) — e.g. `org.dradgo.adapters.persistence` is likely already covered globally; if a dedicated floor is wanted add `org.dradgo.application.project` already exists — confirm the project persistence classes fall under an existing include or add one.
- [x] **Task 3 — `DefaultProjectSeeder` (startup seed + backfill, idempotent)** (AC: 1, 2, 5)
  - [x] `application/project/DefaultProjectSeeder.java` — `@Component`; `@EventListener(ApplicationReadyEvent.class)` `@Transactional` `seedDefaultProjectOnStartup()` (the only `ApplicationReadyEvent` precedent is `RunnerConfiguration.recoverRunnerExecutionsOnStartup`). Inject `WorkflowProperties`, `TicketSourceProperties`, `RepositoryHostProperties`, `RunnerProperties`, and `ProjectStore`.
  - [x] Logic: `if (projectStore.findBySlug("default").isPresent()) { log.info("default project already present, skipping seed"); }` else build the `Project` from config (AC1 values; `ConnectorKind.fromValue(...)` for the two kinds, `runnerProperties.openSpecEnabled()` for the flag), `projectStore.insert(...)`, then `int n = projectStore.backfillNullProjectIds("prj_default"); log.info("backfilled {} runs to default project", n);`.
  - [x] Wrap the insert in a `try/catch (DataIntegrityViolationException e)` → `log.warn("default project already seeded concurrently (slug/public_id unique), continuing")` + re-run/short-circuit the backfill (AC5 race backstop).
  - [x] **Reserved id/slug:** `publicId = "prj_default"` (deterministic — satisfies `^prj_[A-Za-z0-9_-]{4,64}$`, makes idempotency + cross-context races trivial), `slug = "default"`. Add a `public static final String DEFAULT_PROJECT_PUBLIC_ID = "prj_default"` / `DEFAULT_PROJECT_SLUG = "default"` constant (consumed by the resolver + tests).
  - [x] **Test-tier safety:** the seeder fires in **every** `@SpringBootTest`+Testcontainers context. That is intended (runs now need a resolvable project), but verify it does not collide with `FlywaySchemaContractTest` (which inserts its own raw `prj_…` rows with random suffixes — no `default` slug collision) and the profile-wiring slices. If a unit/slice tier needs to opt out, gate the listener on an **optional, unvalidated** boolean (`deliveryline.project.seed-default-on-startup`, default `true`) bound like `RunnerProperties.OpenSpec` — do **not** add a `@Validated` field (memory: `validated-config-needs-test-yaml`).
- [x] **Task 4 — Bind new runs to the default project at create** (AC: 2, 4)
  - [x] Map `project_id` on `WorkflowRunEntity` — add `@Column(name = "project_id") private String projectId;` + getter/setter (the V17 column is currently unmapped/nullable). Hibernate validation passes (existing nullable text column).
  - [x] Thread `projectId` into the create seam: add a `projectId` parameter to `WorkflowRunCreatePort.create(publicId, initialState, projectId)` → `WorkflowRunPersistenceAdapter` → `WorkflowRunEntityMapper.toNewEntity(...)` → `WorkflowRunEntity.create(...)`. **Fan-out warning:** grep every `WorkflowRunCreatePort` impl + every test double/fake of it + every `workflowRunCreatePort.create(...)` call site (memory pattern: `RunnerProperties record component fan-out`). The only production caller is `WorkflowCommandService.submitInternal`.
  - [x] In `WorkflowCommandService.submitInternal`: resolve the default project before create — `String projectId = projectStore.findBySlug("default").map(Project::publicId).orElseThrow(() -> new DomainException(PROJECT_NOT_FOUND, "no default project resolved for run creation"));` (consume-only error; R8). Pass `projectId` into `create(...)`. Keep this inside the existing `@Transactional` submit boundary.
  - [x] **Sub-decision (record in Completion Notes):** if you prefer **not** to widen the create-port signature, the lower-fan-out alternative is a dedicated `WorkflowRunStatePort`-style `bindProject(runPublicId, projectId)` set immediately after create within the same tx. Either satisfies AC2; the param approach keeps the run row never-null at insert (preferred). Pick one and note it.
- [x] **Task 5 — `ProjectRuntimeConfigResolver` + repoint the repo-ref seam** (AC: 3, 4, 6)
  - [x] `application/project/ProjectRuntimeConfigResolver.java` — `@Component`. Method `Project resolveForRun(String workflowRunId)`: load the run's `project_id` (via a focused read — see sub-task) → `projectStore.findByPublicId(pid)`; fall back to `projectStore.findBySlug("default")` when the run has no `project_id` or the lookup misses. Expose convenience accessors used by the seam + 3c-7: `Optional<String> resolveRepositoryRef(String workflowRunId)` (the project's `repositoryUrl` normalized via `RepositoryRef.normalizeRepositoryUrl`), and `boolean resolveOpenSpecEnabled(String workflowRunId)`.
  - [x] **Run → project_id read:** add a focused read rather than widening `WorkflowRunSnapshot` (which fans out to every `new WorkflowRunSnapshot(...)`). Recommended: `Optional<String> ProjectStore.findProjectIdForRun(String runPublicId)` implemented in `ProjectPersistenceAdapter` via a `ProjectRepository`/`WorkflowRunRepository` query (`select r.projectId from WorkflowRunEntity r where r.publicId = :id`). Keep the resolver's lookups read-only.
  - [x] `RepositoryWorkspaceService.resolveExpectedRepositoryRef(workflowRunId)` — **repoint** from `resolveConfiguredRepositoryRef()` to `projectRuntimeConfigResolver.resolveRepositoryRef(workflowRunId)`. Inject `ProjectRuntimeConfigResolver` (application→application is allowed; `REPOSITORY_WORKSPACE_SERVICE_SCOPE` still forbids `adapters..`/`jgit` — the resolver is `application.project`, fine). Update the `// 3c-3 AC5 seam` comment to `// 3c-6 — repointed to run's Project (default-project fallback); remaining consumers wired in 3c-7`.
  - [x] **Parity guarantee:** the `default` project's `repositoryUrl` is seeded from `deliveryline.workflow.repos.url`, so `resolveRepositoryRef` returns the same `owner/repo` the global path returned. Keep `resolveConfiguredRepositoryRef()` (do not delete — 3c-7 / doctor / tests may still reference the global view); the resolver's default fallback is what makes the swap byte-identical.
- [x] **Task 6 — Tests** (AC: 9, all)
  - [x] `DefaultProjectSeederIT` (`*IT`, Failsafe, Testcontainers — memory: `@SpringBootTest+Testcontainers test must be named *IT`): seed-from-config (assert the row's fields equal the configured globals incl. `openspecEnabled`); **no `project_credentials` row** created; restart parity (invoke the listener method twice ⇒ exactly one `default` row); backfill (insert a run with null `project_id` via raw JDBC, run the seeder, assert it now points at `prj_default`).
  - [x] `ProjectRuntimeConfigResolverTest` / `…IT` — run with null `project_id` resolves to the default project's config; run bound to `prj_default` resolves the same; `resolveOpenSpecEnabled` returns the seeded flag.
  - [x] `ProjectPersistenceAdapterIT` — round-trip insert→findBySlug/findByPublicId; the three registry getters parse `text`→enum and fail fast on a hand-inserted bogus `status`/`*_kind` value (`UNKNOWN_REGISTRY_VALUE`).
  - [x] `WorkflowCommandService` create-binding test — a submitted run carries `project_id = prj_default`; submit with no default project present ⇒ `PROJECT_NOT_FOUND` (mock `ProjectStore`).
  - [x] `RepositoryWorkspaceServiceTest` parity extension — requested ref == seeded-default ref ⇒ no mismatch (byte-identical); the existing 3c-3 AC5 parity tests stay green after the repoint.
  - [x] `RegistryContractTest` — the three new persistence boundaries (Task 1) green under the Testcontainers tier + `-Pfoundation-gate`.
  - [x] **Naming/tier discipline:** any `@SpringBootTest`+Testcontainers test is `*IT` (Failsafe); ArchUnit `@ArchTest` runs in Failsafe; use the lifecycle `integration-test` phase, not the `failsafe:`/`surefire:` direct goal (memory: `Maven argLine direct-goal crash`).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] This story **has** an application log surface — instrument it (NOT N/A, unlike 3c-1/3c-2).
  - [x] `DefaultProjectSeeder` → `INFO` "seeding default project from global config repoRef={} ticketKind={} repoKind={} openspec={}" (kinds/flags only — **never** the secrets), `INFO` "default project already present, skipping" on the idempotent path, `INFO` "backfilled {} runs to default project", `WARN` on the caught `DataIntegrityViolationException` race.
  - [x] `ProjectRuntimeConfigResolver` → `DEBUG`/`INFO` on resolve with `workflowRunId` + resolved `project.publicId()` + `source=run_project|default_fallback`; `WARN` when a run's `project_id` references a missing project (then default-fallback).
  - [x] `WorkflowCommandService` create-binding → `INFO` "binding new run {} to project {}"; `WARN`+`PROJECT_NOT_FOUND` when no default resolves.
  - [x] Parameterized logging only (`log.info("...", a, b)`); context keys `correlationId`, `workflowRunId`, `project.publicId()`. **Never log** `LINEAR_API_TOKEN`/`GITHUB_TOKEN`/any credential. Pin at least one log line per new branch with a `ListAppender`/`OutputCaptureExtension` assertion.

## Dev Notes

### Why these ACs are reconciled (epic wording vs. the live codebase)

The dev agent only has this file. These are the traps where the epic 3c-6 ACs collide with the real codebase state; follow the reconciled ACs.

| Epic assumption | Reality | Reconciliation |
|---|---|---|
| "a single `default` Project is seeded … its credentials reference the existing global env-var secrets" (epic AC1) | The encrypted credential store + `ConnectorRole` enum + `project_credentials` JPA mapping are **3c-5** (`backlog`, behind the 3c-4 security-review gate). 3c-3's `ProjectCredentialSource` seam returns empty; adapters use the host-env path. | **Write ZERO `project_credentials` rows.** "References the global env-var secrets" = the default project relies on the unchanged adapter host-env secret path (`LINEAR_API_TOKEN`/`GITHUB_TOKEN`). Leave the `project_credentials` table unmapped. (R4) |
| "repository/connector/OpenSpec settings are read from the run's Project … no longer read on the per-run hot path" (epic AC3) | The hot-path consumers (runner bundle, `DockerRunnerAdapter` dispatch, queue, Linear sync) read globals **and** depend on a per-run `project_id` that **3c-7** threads through intake/dispatch. Only the `resolveExpectedRepositoryRef` seam is already isolated (3c-3). | 3c-6 builds the **resolver** + repoints the **one isolated repo-ref seam** + seeds all values into the default project. **3c-7 repoints bundle/dispatch/queue/sync.** The default project is now source-of-truth; globals remain bound as the seed source. (R6, R7) |
| "the application treats `project_id` as required (run creation without a resolved project is rejected)" (epic AC2) | Resolving a run's project by **explicit reference / ticket-source binding** is **3c-7** (`backlog`). Today there is exactly one project to resolve. | 3c-6 binds every new run to the **`default`** project at create (the only resolution available pre-3c-7) and rejects with `PROJECT_NOT_FOUND` if no default resolves. 3c-7 generalizes the resolution. (R6) |
| "Flyway V14 … the seed backfills all existing rows" (epic + V17 column) | Head migration is **V18**; the `project_id` column already exists (V17). The seed values come from **Spring-bound, env-var-backed config** that a pure-SQL migration **cannot read**. | **No Flyway migration in this story.** Seeding + backfill is an **application-level startup seeder** (`@EventListener(ApplicationReadyEvent)`), the only data-bootstrap pattern with access to the config beans. (R1, R2) |

### R1 — No Flyway migration; the column already exists
V17 (3c-1) added `workflow_runs.project_id text null` + `fk_workflow_runs_projects` + `idx_workflow_runs_project_id`. Head on disk is `V18` (3c-3 `gitlab` widening). This story adds **no** `V19` — it seeds + backfills at the application layer. (If a future reviewer insists on a SQL backfill, it still cannot derive `repository_url`/kinds from host config, so the row must be inserted by the seeder first; the backfill UPDATE could be SQL but is cleaner co-located in the seeder's tx.)

### R2 — The seeder is application-level, not a Flyway Java migration
There is **no data-seeding precedent** in this codebase — every migration is pure SQL. The only `@EventListener(ApplicationReadyEvent)` is `RunnerConfiguration.recoverRunnerExecutionsOnStartup` (the pattern to copy). A Flyway `JavaMigration` runs in Flyway's classloader during `migrate` (before the context is ready) and **cannot inject `WorkflowProperties`/`RunnerProperties`** (which are env-var-bound Spring beans). So the seeder must be a Spring `@Component` firing on `ApplicationReadyEvent`, `@Transactional`, idempotent on the `default` slug.

### R3 — Reserved deterministic id `prj_default` (not `PublicIdPrefixes.PROJECT.next()`)
Use a **fixed** `publicId = "prj_default"` + `slug = "default"`. A random `next()` id would break idempotency (you'd need to query-by-slug anyway) and concurrent-startup races; a deterministic reserved id makes "insert-if-absent" trivial and lets `uq_projects_public_id` **and** `uq_projects_slug` both backstop a race. `prj_default` matches `^prj_[A-Za-z0-9_-]{4,64}$`. Expose `DEFAULT_PROJECT_PUBLIC_ID`/`DEFAULT_PROJECT_SLUG` constants.

### R4 — ZERO credential rows; the host-env secret path is unchanged
The default project's "credentials" are the **existing** global env vars the adapters already read (`LINEAR_API_TOKEN`, `GITHUB_TOKEN`). 3c-3's `ProjectCredentialSource` seam returns empty → adapters fall back to host-env. The encrypted `project_credentials` write path + `RedactionPolicyService` integration is **3c-5** (gated behind 3c-4). **Do not** write `project_credentials`, **do not** map that table, **do not** introduce the `ConnectorRole` enum. Forward obligation: when 3c-5 lands, ciphertext/`key_id`/`algo`/master key must never reach logs/events/artifacts/exports (foundation-gate assertion in 3c-5).

### R5 — Project persistence is the FIRST occupant; mirror the existing stack exactly
3c-2 (R7) and 3c-3 deliberately shipped **no** Project persistence. This story adds it, matching the live convention (confirmed against `IntegrationLink`/`WorkflowRun`):
- Entity/Repository/Mapper/Adapter in `org.dradgo.adapters.persistence.{entity,repository,mapper}` + a `*PersistenceAdapter` implementing an `application` port (the `application-cannot-import-adapters` rule — reach the DB only through `ProjectStore`).
- Enum-like `text` columns are stored raw and parsed **at the entity getter** via `PersistedRegistryValues` (mirror `WorkflowRunEntity.getCurrentState()`), which is exactly why the three new `PersistedRegistryValues.project*` wrappers + the `RegistryContractTest` boundary entries are mandatory (AC7) — 3c-2 R7 explicitly deferred them to "when rows are first read/written through the app," i.e. now.

### R6 — The 3c-6 / 3c-7 split (the crux — get this right)
- **3c-6 owns:** the read side, the seeder, the backfill, bind-new-runs-to-default at create, the `ProjectRuntimeConfigResolver`, repointing the **one** already-isolated repo-ref seam, and **proving single-project parity**.
- **3c-7 owns:** threading `project_id` through CLI/REST intake (explicit ref or ticket-source binding), the runner bundle composition, `DockerRunnerAdapter` dispatch (incl. the OpenSpec env emission), the queue, and Linear completion sync — so **non-default** projects actually take effect end-to-end.
- This is why 3c-7 **depends on** 3c-6: 3c-6 provides the read side + resolver 3c-7 consumes everywhere. Do not pull 3c-7's wiring forward; do not leave 3c-6 without a working end-to-end proof (the repo-ref seam + parity test is that proof).

### R7 — The deferred registry persistence boundaries (do not skip)
`RegistryContractTest.everyCurrentPersistenceBoundaryUsesUniformFailFastRegistryParsing` enumerates every `<table>.<column>` registry boundary and asserts fail-fast parsing. Introducing `ProjectEntity` adds three boundaries — add the `PersistedRegistryValues.project*` wrappers **and** the three `registryBoundaries.put(...)` entries, or the boundary is unprotected (a bad DB value would parse silently). This was a named hand-off from 3c-2.

### R8 — `PROJECT_NOT_FOUND` is consume-only (already registered)
3c-2 registered `PROJECT_NOT_FOUND(404)` at all three sites. Use it for the create-time "no default project resolved" rejection and any "run references a missing project_id" path. **Do not** add a new error code (the three-sites rule would red the foundation gate). If you judge a startup-seed failure to be an internal invariant breach rather than a 404, `INTERNAL_ERROR` is the alternative — but the create-path rejection is a `PROJECT_NOT_FOUND`.

### Global config → default-project seed mapping (copy-ready)

| Project field | Global source | Default |
|---|---|---|
| `repositoryUrl` | `WorkflowProperties.repos().url()` (raw url, nullable) | `null` |
| `ticketSourceKind` | `ConnectorKind.fromValue(TicketSourceProperties.kind())` | `linear` |
| `repoHostKind` | `ConnectorKind.fromValue(RepositoryHostProperties.kind())` | `github` |
| `openspecEnabled` | `RunnerProperties.openSpecEnabled()` (`deliveryline.runner.openspec.enabled`) | `false` |
| `status` | constant | `ACTIVE` |
| `name` / `slug` / `publicId` | constants | `"Default Project"` / `"default"` / `"prj_default"` |

Note: store `repositoryUrl` as the **raw** configured url (the `Project` record's `repositoryUrl` is the unnormalized binding; the resolver normalizes to `owner/repo` at use time via `RepositoryRef.normalizeRepositoryUrl`, exactly as `WorkflowProperties.RepoConfig.repositoryRef()` and `ProjectConnectorResolver.assertRepositoryRefMatchesProject` already do — keep that single normalizer).

### Project Structure Notes

- **New (application):** `application/project/ProjectStore.java`, `application/project/DefaultProjectSeeder.java`, `application/project/ProjectRuntimeConfigResolver.java`.
- **New (adapters):** `adapters/persistence/entity/ProjectEntity.java`, `adapters/persistence/repository/ProjectRepository.java`, `adapters/persistence/mapper/ProjectEntityMapper.java`, `adapters/persistence/ProjectPersistenceAdapter.java`.
- **Modified (main):** `domain/registry/PersistedRegistryValues.java` (+3 wrappers); `adapters/persistence/entity/WorkflowRunEntity.java` (+`project_id` mapping); `application/workflow/spi/WorkflowRunCreatePort.java` (+`projectId` param) + `adapters/persistence/WorkflowRunPersistenceAdapter.java` + `adapters/persistence/mapper/WorkflowRunEntityMapper.java` (+`WorkflowRunEntity.create` arg); `application/workflow/WorkflowCommandService.java` (resolve+bind default at create); `application/runner/workspace/RepositoryWorkspaceService.java` (repoint `resolveExpectedRepositoryRef`); `pom.xml` (JaCoCo floor include if needed).
- **Modified (test):** `contract/RegistryContractTest.java` (+3 persistence boundaries); `application/runner/workspace/RepositoryWorkspaceServiceTest.java` (parity after repoint); any `WorkflowRunCreatePort` test double/fake (create signature fan-out).
- **No** Flyway migration, **no** `project_credentials` mapping, **no** `ConnectorRole` enum, **no** new `DomainErrorCode`/`WorkflowEventType`, **no** REST/OpenAPI surface (CRUD is 3c-8), **no** frontend change (UI is 3c-9). No **validated** `@ConfigurationProperties` added → no `application.yml`/test-yaml change required (the optional seed-toggle in Task 3, if added, is unvalidated like `RunnerProperties.OpenSpec`).

### Logging Requirements (project-wide standard)

This story introduces real application surface (seeder, resolver, create-binding) — instrument it; do not mark N/A.
- **Framework:** SLF4J + Logback. No `System.out`/`printStackTrace()`.
- **Where to log:** seeder seed/skip/backfill/race (INFO/INFO/INFO/WARN); resolver resolution decision (INFO/DEBUG with `source=run_project|default_fallback`, WARN on missing-project fallback); create-binding (INFO bind, WARN+`PROJECT_NOT_FOUND`).
- **Required context keys:** `correlationId`, `workflowRunId`, `project.publicId()` (via MDC/`MdcKeys` or params).
- **Forbidden:** `LINEAR_API_TOKEN`/`GITHUB_TOKEN`/any secret, raw PII. The seeder logs **kinds/flags/refs only**.
- **Test contract:** pin each new log branch with a `ListAppender`/`OutputCaptureExtension` assertion.

### References

- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-6] — authoritative ACs (L124-138); epic context + FR56–FR63.
- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-7] — the run↔Project intake/dispatch wiring that consumes this story's read side + resolver (the R6 split).
- [Source: _bmad-output/planning-artifacts/architecture.md#Multi-Project-Configuration] — L283 data model (`project_id` FK on `workflow_runs`); L378-390 `Project` as configuration, default-project transparent migration.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/project/Project.java] — the aggregate the seeder constructs + the resolver returns.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java] — add the three `project*` wrappers (AC7).
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java#everyCurrentPersistenceBoundaryUsesUniformFailFastRegistryParsing] — add the three boundaries (3c-2 R7 hand-off).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowRunEntity.java] — the entity pattern to mirror (getter-side `PersistedRegistryValues` parse) + the `project_id` mapping to add.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/IntegrationLinkEntity.java; .../mapper/IntegrationLinkEntityMapper.java; .../repository/IntegrationLinkRepository.java] — full entity/mapper/repository stack to mirror for `Project`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunCreatePort.java] — create seam to thread `projectId` through (fan-out).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java#submitInternal] — resolve+bind the default project at create (only production caller of `create`).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java#resolveExpectedRepositoryRef] — the 3c-3 AC5 seam (L420-441) to repoint at the run's Project.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowProperties.java] — `RepoConfig.url()`/`repositoryRef()` seed source + the shared `RepositoryRef.normalizeRepositoryUrl`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceProperties.java; .../repohost/RepositoryHostProperties.java] — `kind` seed sources (default `linear`/`github`).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java#openSpecEnabled] — OpenSpec flag seed source (`deliveryline.runner.openspec.enabled`).
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RunnerConfiguration.java#recoverRunnerExecutionsOnStartup] — the only `@EventListener(ApplicationReadyEvent)` precedent (the seeder pattern to copy).
- [Source: deliveryline-backend/pom.xml] — JaCoCo per-package 0.80 floor block (extend if a dedicated project-persistence include is wanted).
- [Source: _bmad-output/implementation-artifacts/3c-3-per-project-connector-resolution-over-ticketsource-and-repohost.md] — the resolver/credential/repo-ref seams this story repoints; "no Project read side yet" hand-off.
- [Source: _bmad-output/implementation-artifacts/3c-2-project-domain-aggregate-and-registries-and-drift-tests.md] — Project record + registries + the R7 "persistence boundary deferred to 3c-6" note.
- Memory: `@SpringBootTest+Testcontainers test must be named *IT`; `Maven argLine direct-goal crash`; `validated-config-needs-test-yaml`; `application-cannot-import-adapters`; `RunnerProperties record component fan-out` (the create-port signature fan-out shape); `post-commit hook needs REQUIRES_NEW` (startup seeder is NOT in an afterCommit synchronization — plain `@Transactional` is fine).

### Open Decisions surfaced for review (do not block implementation; defaults chosen)

1. **Create-binding mechanism** — default: widen `WorkflowRunCreatePort.create(...)` with a `projectId` param so the run row is never null at insert (Task 4). Alternative: a separate `bindProject` set immediately after create (lower fan-out, two writes). Proceeding with the param.
2. **Reserved default id** — `prj_default` / slug `default` (deterministic, idempotent). If the team wants a generated `prj_…` id, the seeder must instead query-by-slug for idempotency and the constant becomes a slug-only reserve. Proceeding with `prj_default`.
3. **Seed toggle** — a default-on, unvalidated `deliveryline.project.seed-default-on-startup` is offered only if a slice tier needs to opt out; not added unless a test tier breaks. Proceeding without it unless required.
4. **ADR** — 3c-3 deferred ADR 0012; architecture L390 anticipates per-epic ADRs. A short `docs/adr/00xx-config-inversion-default-project.md` recording the global→default-project inversion + the seeder-not-migration decision is in scope if ADRs are authored per story; flag at review whether ADRs are batched per epic.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (bmad-dev-story, 2026-06-20)

### Debug Log References

- `mvn -o test -Dtest=ProjectRuntimeConfigResolverTest,DefaultProjectSeederTest,WorkflowCommandServiceCreateBindingTest,WorkflowCommandServiceReplayRefTest,RepositoryWorkspaceServiceTest` → 31 unit tests green.
- `mvn -o verify -Dit.test=DefaultProjectSeederIT,ProjectPersistenceAdapterIT,RegistryContractTest,WorkflowMutationEndpointsContractTest` → 30 IT/contract tests green (Testcontainers Postgres).
- `mvn -o verify -Dit.test=ArchitectureBoundaryTest,LayeredArchitectureTest` → ArchUnit boundary tests green.
- `mvn -o verify -Dit.test=WorkflowBatchEndpointContractTest,WorkflowReadEndpointsContractTest` → submit/read paths green after the create-port signature change.
- checkstyle + spotless green.

### Completion Notes List

- **AC1/AC5 — `DefaultProjectSeeder`** (`application.project`, `@EventListener(ApplicationReadyEvent)`): seeds a reserved `prj_default`/`default`/`Default Project` ACTIVE project from the bound global config (repo url raw/nullable, `ConnectorKind.fromValue` for both kinds, `runnerProperties.openSpecEnabled()`), idempotent on the `default` slug, with a caught `DataIntegrityViolationException` concurrent-startup backstop. **The listener method is intentionally NOT `@Transactional`** — `ProjectStore.insert`/`backfillNullProjectIds` are each adapter-level `@Transactional`, so the caught DIVE rolls back only the failed insert's tx; a single surrounding tx would be poisoned (rollback-only) by the caught exception and the subsequent backfill would fail. Logs seed/skip/backfill/race; **no secrets logged** (pinned by `DefaultProjectSeederTest` ListAppender assertions). **ZERO `project_credentials` rows** (R4 — asserted by `DefaultProjectSeederIT`).
- **AC2 — create-time binding**: `WorkflowRunCreatePort.create` gained a `projectId` param (fan-out: only `WorkflowRunPersistenceAdapter` + `WorkflowRunEntityMapper` + `WorkflowRunEntity.create`; the 2-arg `WorkflowRunEntity.create` is retained delegating with `null` so the ~14 pre-3c-6 entity fixtures keep compiling). `WorkflowCommandService.submitInternal` resolves the default project before create and rejects with the consume-only `PROJECT_NOT_FOUND` when absent. **Open-decision #1: chose the param approach** (run row never null at insert) over a post-create `bindProject`.
- **AC3/AC4/AC6 — `ProjectRuntimeConfigResolver`** (`application.project`): `resolveForRun` (run `project_id` → `findByPublicId`, default-slug fallback, `PROJECT_NOT_FOUND` only if even the default is missing) + `resolveRepositoryRef` (shared `RepositoryRef.normalizeRepositoryUrl`) + `resolveOpenSpecEnabled`. `RepositoryWorkspaceService.resolveExpectedRepositoryRef` repointed to it. **Lower-fan-out injection**: a no-resolver test-facing ctor was kept (resolver nullable) so the 4 git-focused service-level test constructions stay byte-identical (resolver null → global-config fallback); production always uses the `@Autowired` resolver ctor. New `RepositoryWorkspaceServiceTest` cases prove the repoint + parity + mismatch.
- **AC7 — registry persistence boundaries**: added `PersistedRegistryValues.projectStatus/projectTicketSourceKind/projectRepoHostKind` + the 3 `RegistryContractTest` boundary entries (auto-bumps the count). `ProjectEntity` parses the enum-like text at the getter. The bogus-DB-value getter fail-fast is covered by `RegistryContractTest` directly — the `ck_projects_*` CHECKs forbid inserting an out-of-set value to exercise it via the adapter IT.
- **Trap fixed during impl**: `created_at` was first mapped `insertable=false` (DB default) per the task wording, but Hibernate does not refresh after `saveAndFlush`, so `toDomain` read a null `createdAt` and tripped the `Project` record's non-null guard at startup. Fixed by stamping `created_at` from the domain `createdAt` at insert (`updatable=false`; V17 `default now()` remains a raw-SQL fallback).
- **No Flyway migration** (R1), **no `pom.xml` change** (the `application.project` 0.80 line floor already exists from 3c-3; the new persistence classes fall under the BUNDLE rule), **no `project_credentials` mapping / `ConnectorRole` enum**, **no new error code / event type / OpenAPI / frontend** — as scoped.
- **Memory trap hit**: `checkstyle-suppressions-line-anchored` — the WorkflowCommandService edits shifted the pre-existing `Thread.sleep` exemption; re-anchored `config/checkstyle/suppressions.xml` `ForbiddenThreadSleep` from `lines="810"` to `lines="840"`. Reviewers inserting lines above it must re-anchor again.
- **3c-7 hand-off**: this story repoints only the one isolated repo-ref seam + exposes the resolver. Threading `project_id` through CLI/REST intake, runner bundle, `DockerRunnerAdapter` dispatch (incl. the OpenSpec env emission), the queue, and Linear completion sync — so non-default projects take effect — remains 3c-7.

### File List

**New (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectStore.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/project/DefaultProjectSeeder.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectRuntimeConfigResolver.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/ProjectEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/ProjectRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/ProjectEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ProjectPersistenceAdapter.java`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowRunEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/WorkflowRunEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunCreatePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java`

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectRuntimeConfigResolverTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/project/DefaultProjectSeederTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/project/DefaultProjectSeederIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceCreateBindingTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/ProjectPersistenceAdapterIT.java`

**Modified (test):**
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceReplayRefTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceServiceTest.java`

**Modified (config):**
- `config/checkstyle/suppressions.xml` (re-anchor `ForbiddenThreadSleep` for WorkflowCommandService)

## Change Log

| Date | Version | Description |
|---|---|---|
| 2026-06-20 | 0.1 | Story implemented: default-project seeder + backfill, `ProjectStore`/`ProjectRuntimeConfigResolver`, create-time project binding, repo-ref seam repoint, 3 registry persistence boundaries. Status → review. |

### Review Findings

_Adversarial code review 2026-06-20 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). Acceptance Auditor reported ZERO AC violations — all 9 ACs + R1/R3/R4/R8 reconciliations PASS. Findings below are robustness/design observations._

- [x] [Review][Patch] Harden the seeder's fail-fast message on a typo'd connector kind (decision resolved: keep fail-fast at startup) — FIXED 2026-06-20: added `parseSeedConnectorKind(...)` wrapping `ConnectorKind.fromValue` to abort startup with an `IllegalStateException` naming the property key, bad value, and allowed kinds (+ an `ERROR` log), preserving the `DomainException` cause. — `DefaultProjectSeeder.seedDefaultFromGlobalConfig` calls `ConnectorKind.fromValue(ticketSourceProperties.kind())` / `repoHostProperties.kind()`, which throws `UNKNOWN_REGISTRY_VALUE` and aborts startup via the `ApplicationReadyEvent` listener. Decision (2026-06-20): fail-fast-at-startup is the desired behavior; do NOT normalize-never-throw. Patch = wrap/augment the parse so the startup failure names the offending config key (`deliveryline.integration.ticket-source.kind` / `…repo-host.kind`) and its bad value, instead of surfacing a bare `UNKNOWN_REGISTRY_VALUE` stack. [deliveryline-backend/.../application/project/DefaultProjectSeeder.java]

- [x] [Review][Patch] Backfill hardcodes `prj_default` even when an existing `default`-slug project has a different public_id — FIXED 2026-06-20: `seedDefaultProjectOnStartup` now backfills to the *resolved* default project's `publicId()` (from `findBySlug`/the insert result), and the concurrent-race branch re-reads `findBySlug` to use the winner's id. All 5 `DefaultProjectSeederTest` cases green. — `seedDefaultProjectOnStartup` unconditionally calls `projectStore.backfillNullProjectIds(DEFAULT_PROJECT_PUBLIC_ID)` after the seed short-circuits on `findBySlug("default")`. If a `default`-slug row exists with a public_id ≠ `prj_default` (manual insert / future 3c-8 CRUD), the backfill writes a FK value that doesn't exist → `fk_workflow_runs_projects` violation, or mis-binds runs. Fix: backfill to the resolved/existing default project's `publicId()` rather than the constant. [deliveryline-backend/.../application/project/DefaultProjectSeeder.java]

- [x] [Review][Defer] `LINEAR_GITHUB_REPO_MISMATCH` is a vendor-named error code on a now vendor-neutral seam [deliveryline-backend/.../application/runner/workspace/RepositoryWorkspaceService.java] — deferred, pre-existing (error code predates 3c-6; a GitLab-kind project would still throw a Linear/GitHub-named code; rename is out of scope until GitLab is real).
- [x] [Review][Defer] Seed-failure fragility: a failed/deleted default project surfaces as per-request `PROJECT_NOT_FOUND` on every submit, and a non-DIVE insert failure skips the backfill [deliveryline-backend/.../application/project/DefaultProjectSeeder.java; .../application/workflow/WorkflowCommandService.java] — deferred, pre-existing (seed failure already fails startup loudly; a startup self-check / health probe is a future hardening, candidate for 3c-10 doctor `projects` check).
- [x] [Review][Defer] Startup-window race: the embedded server can accept a submit before `ApplicationReadyEvent` seeds the default, yielding a transient `PROJECT_NOT_FOUND` [deliveryline-backend/.../application/workflow/WorkflowCommandService.java] — deferred, pre-existing (narrow, transient startup window; acceptable until a readiness gate exists).
- [x] [Review][Defer] `backfillNullProjectIds` uses `@Modifying(clearAutomatically = true)`, a latent detach hazard for any future caller inside a wider transaction [deliveryline-backend/.../adapters/persistence/repository/WorkflowRunRepository.java] — deferred, pre-existing (safe today — only the non-@Transactional seeder calls it via a dedicated adapter tx; add a guard/comment when a second caller appears).
- [x] [Review][Defer] `created_at` provenance differs by insert path (app-clock via JPA stamp vs DB `default now()` for raw-SQL inserts) [deliveryline-backend/.../adapters/persistence/entity/ProjectEntity.java] — deferred, pre-existing (documented compromise from the Hibernate no-refresh trap; cross-path clock skew is cosmetic).
