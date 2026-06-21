# Story 3c.10: Doctor + Observability for Projects

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **⚠️ READ FIRST — this story adds a per-project health *read-out* (a new doctor check + a local-observability gauge). It is a thin, read-only consumer of the Project read side; it builds NO new persistence and writes NOTHING.** Most of what you depend on is already merged; one piece is a **hard prerequisite that must be merged first** (R0):
>
> - **`Project` aggregate** — `org.dradgo.domain.project.Project` (a `record`): `publicId, name, slug, status (ProjectStatus), repositoryUrl (nullable), ticketSourceKind (ConnectorKind), repoHostKind (ConnectorKind), openspecEnabled, createdAt, archivedAt`. (3c-2.)
> - **Registries** — `ProjectStatus{ACTIVE("active"), DISABLED("disabled")}`, `ConnectorKind{LINEAR("linear"), GITHUB("github"), GITLAB("gitlab")}`. (3c-2/3c-3.)
> - **`ProjectConnectorResolver`** (`application.project`, 3c-3) — `resolveTicketSource(Project)` / `resolveRepositoryHost(Project)` (throw `UNSUPPORTED_CONNECTOR_KIND` for an unregistered kind), `assertRepositoryRefMatchesProject(Project, ref)`, `resolveConnectorSecret(Project, role)` (the `ProjectCredentialSource` seam — **returns empty today**; the encrypted store is 3c-5).
> - **Doctor subsystem** (1.16 + later) — `DoctorService` (`application.diagnostics`) with `CHECK_*` constants + `STATIC_ORDER` (currently **17** checks) + a `runSingleProbe` switch; `DoctorProbePort` SPI (15 `probeX()` methods); `DoctorProbeAdapter` (`adapters.diagnostics`, a **5-overload constructor chain**); `DiagnosticsStatus{PASS,WARN,FAIL,SKIP}`; `ProbeResult.{pass,warn,fail,skip}`; CLI-only invocation via `DoctorCommands` (`--format`, `--only`, `--exclude`). Every check's `summary`/`remediation`/`details` is **already run through `RedactionPolicyService`** (SHAREABLE_REDACTED) by `DoctorService` — your probe inherits that, but **still must never put a secret in a detail value** (redaction is defense-in-depth, not a license to leak).
> - **Local observability** — `org.dradgo.infrastructure.observability.RunnerQueueMetricsBinder` (`@Component implements MeterBinder`, Micrometer gauges, dots→underscores in Prometheus). This is the **only** metrics-binder precedent — mirror it. The profile name is **`observability`**.
>
> **R0 — HARD PREREQUISITE: `application.project.ProjectStore` must be merged (story 3c-6) before this story is dev'd.** 3c-6 introduces the *first Project persistence/read side* (entity/repository/mapper/`ProjectPersistenceAdapter` + the `ProjectStore` port). **As of this story's creation, `ProjectStore`, `ProjectEntity`, `ProjectRepository`, `ProjectPersistenceAdapter`, and `ProjectRuntimeConfigResolver` DO NOT EXIST on disk** (verified). A doctor check cannot *list* projects without a read side. The epic's stated dependency ("3c-3, 1-16") is **incomplete** — the real prerequisite is **3c-6**. **Do not start this story until 3c-6 is `done`/merged.** This story then *adds one read method* (`List<Project> findAll()`) to the existing `ProjectStore` (Task 1) and consumes it.
>
> **What this story deliberately does NOT do (owned elsewhere):**
> - **No live network connection test.** Persisted "last connection-test outcome" is **story 3c-8**'s `testConnection` endpoint (`backlog`). Doctor is a *fast prerequisite read-out* — it reports **configuration health** (status, kinds, credential presence, repo binding present, kinds resolvable) and explicitly surfaces `connectionTest=not-run` deferring live connectivity to 3c-8. See R3.
> - **No `project_credentials` read/JPA mapping.** The encrypted store + `ConnectorRole` enum are **3c-5** (`backlog`, behind the 3c-4 security gate). Credential *presence* is reported **presence-only** via the existing `ProjectCredentialSource` seam (empty today) + the unchanged host-env secret path (`LINEAR_API_TOKEN`/`GITHUB_TOKEN`) — never a value, never the ciphertext. See R4.

## Story

As an operator,
I want the doctor command and local observability to report per-project health — each configured project's status, connector kinds, credential presence (without printing any secret), repository binding, and connector-kind resolvability — surfaced as a single new doctor check and a profile-gated metric,
so that I can confirm my projects are correctly configured before running governed work, and a misconfigured project shows up as a WARN with the specific failing check and a safe next action rather than failing silently mid-run.

## Acceptance Criteria

> These ACs are **reconciled** against the live doctor subsystem and the actual dependency-graph state (3c-5/3c-8 not merged; 3c-6 is the hard read-side prerequisite). Where the epic wording assumes infrastructure that does not exist yet (a persisted connection-test result; a `project_credentials` read side), the reconciled wording below is authoritative; the rationale is in Dev Notes → "Why these ACs are reconciled."

1. **Given** the doctor service (1.16), **When** `doctor` runs with `ProjectStore` available (a real DB context), **Then** a **new** `projects` check (`DoctorService.CHECK_PROJECTS = "projects"`, appended last in `STATIC_ORDER`) lists every configured project and, per project, reports its **status** (`active`/`disabled`), **ticket-source kind**, **repo-host kind**, **repository binding present?**, and **OpenSpec flag** in the check `details` (keyed by `project.publicId()`), never echoing a secret. Overall the check is **PASS** when every active project is fully configured, **WARN** when at least one active project is misconfigured (see AC3), **SKIP** when `ProjectStore` is absent (lean / doctor-smoke contexts with no DataSource — symmetric with `postgres-connectivity`).

2. **Given** credential presence, **Then** the `projects` check reports per active project, per connector role (`ticket_source`, `repo_host`), a **presence-only** verdict — `present-via-store` (when `ProjectCredentialSource.resolveSecret(project, role)` returns a value — never reached today, the seam is empty until 3c-5), `present-via-host-env` (when the adapter's host-env secret name is set — e.g. the default project reading `LINEAR_API_TOKEN`/`GITHUB_TOKEN`), or `missing`. **No secret value, ciphertext, key id, or env-var *value* ever appears** in any summary/detail; only the literal tokens `present-via-store`/`present-via-host-env`/`missing`. A `missing` credential for an active project's required role drives the check to **WARN** (AC3), never FAIL (a misconfigured project is advisory, not a boot blocker — symmetric with `git-bot-identity` WARN-only).

3. **Given** an unconfigured or unsupported project, **Then** the `projects` check returns **WARN** with `errorCode = DOCTOR_PROJECT_CONFIG_INCOMPLETE` (one new `DomainErrorCode`, R5) and `details` naming **which** project and **which** check failed — any of: `repositoryUrl` blank/absent, a connector role's credential `missing`, or a `ticketSourceKind`/`repoHostKind` that the `ProjectConnectorResolver` cannot resolve (`UNSUPPORTED_CONNECTOR_KIND` — caught and rendered as a per-project WARN line, **not** propagated). The remediation text gives the safe next action ("set the repository URL / set the missing credential via the project's set-credential endpoint (3c-8) / register the connector kind"). `disabled` projects are reported but **excluded** from the WARN roll-up (a disabled project is intentionally not runnable).

4. **Given** the doctor probe fan-out house rule, **Then** the new probe is wired at **all** the doctor "sites" *and* the test fan-out sites: `DoctorProbePort.probeProjects()` + `DoctorProbeAdapter` impl threaded through **every** constructor overload in the 5-overload chain; `DoctorService.CHECK_PROJECTS` + `STATIC_ORDER` (append) + the `runSingleProbe` switch case; the hard-coded `checksRun` literal bumped **17 → 18** (`DoctorLoggingContractTest`) and `DoctorServiceTest.stubAllProbesPass()` stubs the new probe (else the full-set run NPEs on the unstubbed mock). `DoctorRedactionContractTest` (uses `only=[postgres-connectivity]`) and `DoctorProbeAdapterTest` (per-probe builders) are extended as needed.

5. **Given** local observability (when the `observability` profile is active), **Then** per-project health is surfaced consistently with the existing convention — a new `org.dradgo.infrastructure.observability.ProjectHealthMetricsBinder` (`@Component implements MeterBinder`, mirroring `RunnerQueueMetricsBinder`) registers gauges `deliveryline.projects.total` (count of non-archived projects) and `deliveryline.projects.configured` (count of active projects passing the AC1/AC3 configuration check), reading **one cached snapshot** held in a **strongly-referenced field** (the Micrometer weak-ref-NaN gotcha — see R6). The binder is `@Component` (registered in all contexts, meaningful only when a Prometheus registry is scraping) and depends only on `ProjectStore` (+ the config-check helper); it **never** reads or emits a credential.

6. **Given** tests, **Then** coverage asserts: the project listing (status/kinds/binding/OpenSpec in `details`), credential-presence reporting with a **leak-proof assertion** (a project whose secret is set produces only `present-via-*` — the secret literal never appears in any check field, pinned by a ListAppender/redaction assertion), WARN-on-misconfiguration (blank repo URL / missing credential / unsupported kind each → WARN + `DOCTOR_PROJECT_CONFIG_INCOMPLETE`), `disabled`-project excluded from the roll-up, SKIP when `ProjectStore` absent, the updated `checksRun=18` count, and the two metrics gauges (total/configured) against a small fixture. New code meets the standing **≥80% line-coverage** floor; credential-presence-adjacent code **≥90%** (no secret-bearing branch uncovered).

## Tasks / Subtasks

- [x] **Task 1 — Add a read-all method to the `ProjectStore` port (consumed by both the probe and the metrics binder)** (AC: 1, 5)
  - [x] **R0 GUARD:** confirmed `org.dradgo.application.project.ProjectStore` exists on disk (3c-6 merged). ✅
  - [x] `List<Project> findAll()` **already exists** on `ProjectStore` — it landed in story 3c-8 as the co-edit predicted (the port Javadoc explicitly notes "Co-edit with story 3c-10 (its first listing consumer) — identical signature"). It returns all projects ordered by `created_at` (includes disabled). **No port change needed.** The probe + binder consume it and filter `archivedAt() == null` in-code where "non-archived" matters (AC1/AC5), rather than re-pointing 3c-8's already-merged contract. See Completion Notes / Open Decision #4.
  - [x] Implemented in `ProjectPersistenceAdapter` (3c-8) via `findAllByOrderByCreatedAtAsc()`, `@Transactional(readOnly=true)`, mapping each through `ProjectEntityMapper.toDomain(...)`. No new repository finder added (reused 3c-8's).
  - [x] **Fan-out check:** `grep "implements ProjectStore"` → only `ProjectPersistenceAdapter` (no test fakes). `findAll()` already present; nothing else to add.
- [x] **Task 2 — Add the `projects` doctor check (port + service wiring + the doctor "three sites")** (AC: 1, 3, 4)
  - [x] `DoctorProbePort.probeProjects()` added with the presence-only Javadoc.
  - [x] `DoctorService`: `CHECK_PROJECTS = "projects"` + appended to `STATIC_ORDER` (last) + `case CHECK_PROJECTS -> probes.probeProjects();` + a `REMEDIATION` entry. Redaction path untouched (inherits `redactCheck`).
- [x] **Task 3 — Implement `probeProjects()` in `DoctorProbeAdapter` (thread the new deps through the whole 5-overload chain)** (AC: 1, 2, 3, 4)
  - [x] New deps injected as `ObjectProvider<ProjectStore>` + `ObjectProvider<ProjectConnectorResolver>` on the `@Autowired` ctor → nullable `@Nullable` fields via `getIfAvailable()` (mirrors the `gitHubRestClientProvider` idiom).
  - [x] Threaded through **all** ctor overloads (the `@Autowired` one + the 4 narrow test seams pass `null, null` + the final assigning ctor). Added the new package-private test seam `DoctorProbeAdapter(Environment, ProjectStore, ProjectConnectorResolver)` so the probe is unit-testable without DB/Flyway.
  - [x] `probeProjects()` logic: SKIP when `projectStore == null`; else lists non-archived projects with per-project `details` (status/kinds/repositoryBound/openspec/kindsResolvable) + per active project the two credential verdicts; WARN roll-up on any misconfigured **active** project, else PASS.
  - [x] Kind-resolvability wrapped in a try/catch for `DomainException(UNSUPPORTED_CONNECTOR_KIND)` inside the shared `ProjectConfigChecks.kindsResolvable` helper → per-project WARN, never propagated. Null resolver → sub-check skipped.
- [x] **Task 4 — Credential-presence reporting (presence-only, leak-proof)** (AC: 2, 6)
  - [x] Per active project + role `{ "ticket_source", "repo_host" }`: `resolveConnectorSecret` present → `present-via-store`; else host-env name set (`LINEAR_API_TOKEN`/`GITHUB_TOKEN`, presence boolean only) → `present-via-host-env`; else `missing`. Verdict written under `cred:<publicId>:<role>`.
  - [x] **Hard rule honored:** only the three presence tokens + ids/kinds/booleans reach any field; the resolved secret / `environment.getProperty(name)` value is never interpolated/logged. Pinned by the AC6 leak-proof tests (store + host-env paths) and the log-leak test.
- [x] **Task 5 — Add the `DOCTOR_PROJECT_CONFIG_INCOMPLETE` error code (the three-sites rule)** (AC: 3, 4)
  - [x] `DomainErrorCode.DOCTOR_PROJECT_CONFIG_INCOMPLETE` added (beside the other `DOCTOR_*` codes).
  - [x] `ProblemDetailsCatalog` registers it `SERVICE_UNAVAILABLE` + non-retryable (mirrors `DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED` / `DOCTOR_OBSERVABILITY_LOW_MEMORY`).
  - [x] `registry-api-schema-placeholders.json` `problemTypeUris` gains the auto-derived `doctor-project-config-incomplete` URI.
  - [x] Verified GREEN under `-Pfoundation-gate` (Contract #7 ProblemDetails + Contract #3 registry drift). No `WorkflowEventType`, no Flyway.
- [x] **Task 6 — `ProjectHealthMetricsBinder` (local observability)** (AC: 5)
  - [x] `infrastructure/observability/ProjectHealthMetricsBinder.java` — `@Component implements MeterBinder`; ctor-injects `ObjectProvider<ProjectStore>` + `ObjectProvider<ProjectConnectorResolver>`. `bindTo` registers `deliveryline.projects.total` + `deliveryline.projects.configured`.
  - [x] **Weak-ref gotcha (R6):** the `ProjectHealthState` snapshot lives in a strongly-referenced `volatile` field; cached ≤1s (mirrors `RunnerQueueMetricsBinder`); gauges report `0` (never `NaN`) when the store is absent. Forced-GC regression assertion added.
  - [x] Config-check predicate extracted into the shared `ProjectConfigChecks` (used by both probe and binder). **`configured` definition chosen (Open Decision #3):** active + repo-bound + kinds-resolvable; credential-presence is reported by the *probe only*, NOT gated into the gauge (keeps env-reads off the scrape hot path).
- [x] **Task 7 — Tests** (AC: 6, all)
  - [x] `DoctorProbeAdapterTest` — added 9 probe tests via the new seam: all-configured PASS; blank repo → WARN; missing credential → WARN; unsupported kind → WARN (not thrown); disabled excluded from roll-up; `projectStore == null` → SKIP; present-via-store leak-proof; present-via-host-env leak-proof; log-branch + log-leak pins.
  - [x] `DoctorServiceTest` — `stubAllProbesPass()` + the 4 inline full-set tests stub `probeProjects()`; `hasSize(STATIC_ORDER.size())` auto-tracks 18.
  - [x] `DoctorLoggingContractTest` — `checksRun=17` → `18` + probe stubbed.
  - [x] `ProjectHealthMetricsBinderTest` — `SimpleMeterRegistry` fixture (1 active+configured, 1 disabled) asserts total=2/configured=1 + the not-`NaN`-after-GC weak-ref guard. Named `*Test` (Surefire, no Testcontainers).
  - [x] No `@SpringBootTest`+Testcontainers doctor/projects IT added (optional; the config-only read needs none).
  - [x] **Tier discipline:** ran `-Pfoundation-gate verify` (lifecycle phase, not direct goals) — 1196 Surefire + 36 foundation/contract tests GREEN.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Application log surface instrumented (NOT N/A).
  - [x] `probeProjects()` → `INFO` on resolution (`resolution=pass|warn|skip` + `projectCount`/`activeProjectCount`); `WARN` per misconfigured active project (`activeProjectId` + `reason=blank_repo|missing_credential|unsupported_kind`); `DEBUG` per happy-path project.
  - [x] `ProjectHealthMetricsBinder.bindTo` → single `INFO`; no per-scrape logging.
  - [x] Parameterized logging only; the resolved secret / env value is **never** logged — only the `present-via-*`/`missing` token. (DoctorService also redacts every field SHAREABLE_REDACTED as defense-in-depth.)
  - [x] Each new log branch pinned with a `ListAppender` assertion (`projectsProbeLogsPinBranchesAndNeverLeakASecret` + `projectsProbeSkipBranchIsLogged`), including the leak-proof "no secret in any log line" assertion.

## Dev Notes

### Why these ACs are reconciled (epic wording vs. the live codebase)

The dev agent only has this file. These are the traps where the epic 3c-10 ACs collide with the real codebase/dependency state; follow the reconciled ACs.

| Epic assumption | Reality | Reconciliation |
|---|---|---|
| "doctor lists configured projects … and **last connection-test outcome**" (epic AC1) | There is **no persisted connection-test result anywhere**. `testConnection` is **3c-8** (`backlog`); it does not exist and nothing stores its outcome. | The `projects` check reports **configuration health** (status/kinds/binding/credential-presence/kind-resolvability) and surfaces `connectionTest=not-run` in `details`, deferring live connectivity to 3c-8. Doctor is a *fast prerequisite read-out*, not a network test runner. (R3) |
| "doctor reports per-project **credential presence**" (epic AC2) | The encrypted `project_credentials` store + `ConnectorRole` enum + JPA mapping are **3c-5** (`backlog`, behind 3c-4's security gate). 3c-3's `ProjectCredentialSource` seam returns empty; the default project uses the host-env path. | Presence-only verdict via the **seam** (`present-via-store`, never reached today) + the **host-env** check (`present-via-host-env`, e.g. `LINEAR_API_TOKEN`/`GITHUB_TOKEN`) else `missing`. **No `project_credentials` read, no JPA mapping.** Lights up automatically when 3c-5 lands. (R4) |
| deps = "3c-3, 1-16" (sprint-status) | To **list** projects you need a Project **read side** = `ProjectStore` = **3c-6** (`ready-for-dev`, **not merged**; `findAll()` doesn't exist). | **R0 hard prerequisite:** 3c-6 must be merged first. This story *adds* `ProjectStore.findAll()` + the probe + the metric. Corrected dep recorded in sprint-status note + Open Decisions. |
| "any new probe is **stubbed everywhere** … the hard-coded `checksRun` count is bumped" (epic AC4) | The doctor "three sites" fan out further (memory `new-doctor-probe-fans-out`): the 5-overload `DoctorProbeAdapter` ctor chain, `DoctorServiceTest.stubAllProbesPass()`, and the literal `checksRun=17`. | Thread the new deps through **every** ctor overload; stub `probeProjects()` in `stubAllProbesPass()`; bump `checksRun` **17→18**. (R7) |

### R0 — `ProjectStore` is a hard prerequisite (3c-6), and this story adds `findAll()`
3c-2/3c-3 shipped **no** Project persistence. 3c-6 adds it (`ProjectStore` port + `ProjectEntity`/`ProjectRepository`/`ProjectEntityMapper`/`ProjectPersistenceAdapter`). `ProjectStore` as specced in 3c-6 has `findBySlug`/`findByPublicId`/`insert`/`backfillNullProjectIds` — **no list method.** This story adds the single read-all method it needs (`findAll()` → non-archived projects). If a reviewer asks "why not put `findAll` in 3c-6": 3c-6 only needs by-slug/by-id for the seeder; the listing consumer (doctor + the 3c-8 list endpoint) is what motivates `findAll`, so it lands with its first consumer. **Verify `ProjectStore` exists before starting (Task 1 R0 guard).**

### R3 — Doctor reports *configuration* health, not live connectivity
The existing doctor checks are **fast prerequisite reads** (env-var presence, a single cheap `GET /user`, a `git --version`). A full per-project, per-connector live connection test (clone reachability + ticket-source auth + repo-host auth) is heavy, network-bound, and is the explicit job of **3c-8's `testConnection` endpoint**. So `probeProjects` does **configuration completeness** + **credential presence** + **kind resolvability** — all local, fast, deterministic — and prints `connectionTest=not-run (use the project testConnection endpoint, story 3c-8)`. When 3c-8 persists a last-test outcome, a future increment can surface it here; do not build that persistence in 3c-10.

### R4 — Credential presence is presence-only and must be leak-proof
Two sources, in priority order: (1) `ProjectConnectorResolver.resolveConnectorSecret(project, role)` (the `ProjectCredentialSource` seam — **returns empty today**, lights up in 3c-5) → `present-via-store`; (2) the unchanged **host-env** secret path the adapters already read (`LINEAR_API_TOKEN` for ticket-source, `GITHUB_TOKEN` for repo-host) → `present-via-host-env`; else `missing`. The probe writes **only** the three presence tokens — never the resolved secret, never `environment.getProperty(name)`, never a ciphertext/key-id. `DoctorService` already redacts every field (SHAREABLE_REDACTED) as defense-in-depth, but the probe must be leak-proof on its own (the AC6 leak test pins this). This mirrors `probeRunnerSecrets` (reports `present`/`missing` per kind) and `probeGitHubAuth` ("Reports presence only — never logs or returns the token").

### R5 — One new `DomainErrorCode` (three-sites rule)
`DOCTOR_PROJECT_CONFIG_INCOMPLETE` is the WARN code for a misconfigured project. Adding a `DomainErrorCode` is the **three-sites rule** (memory `new-domainerrorcode-three-sites` / `docs/patterns/registry-recipe.md` Recipe 3): enum + `ProblemDetailsCatalog` + the `registry-api-schema-placeholders.json` `problemTypeUris` manifest, verified under `-Pfoundation-gate`. Model the HTTP status/`retryable` posture on the existing WARN-advisory doctor codes (`DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED`, `DOCTOR_OBSERVABILITY_LOW_MEMORY`). One code is enough — the *which-sub-check-failed* specificity rides in `details`/`reason`, not in distinct codes.

### R6 — The Micrometer weak-ref NaN gotcha (observability binder)
`Gauge.builder(name, stateObj, fn)` holds `stateObj` **weakly**. If the only reference is a local var, a GC turns the gauge to `NaN` (a real Windows-CI flake — memory `micrometer-gauge-weak-ref-nan-flake`, hit by `RunnerQueueMetricsBinderTest`). Keep the `ProjectHealthState`/snapshot supplier in a **strongly-referenced instance field** of `ProjectHealthMetricsBinder`. Mirror `RunnerQueueMetricsBinder` exactly: `@Component`, one cached snapshot per ≤1s window, dots in meter names (Prometheus renders underscores), gauges read the cached counts. Add the forced-GC `not NaN` regression assertion (AC6).

### R7 — The doctor-probe fan-out (do all of it or CI reds)
Memory `new-doctor-probe-fans-out`: adding a probe touches more than the obvious three sites.
1. **`DoctorProbeAdapter`** — the new `ObjectProvider` deps must be threaded through **every** chained `this(...)` overload (5 of them) + the final assigning ctor; the narrow test seams pass `null` for the new deps (like they do for `gitHubRestClient`).
2. **`DoctorServiceTest.stubAllProbesPass()`** — stub `probeProjects()`; any full-set test NPEs otherwise (`toDiagnosticsCheck` derefs the unstubbed mock's `null`).
3. **`DoctorLoggingContractTest`** — bump `checksRun=17` → `18` (L89).
4. `DoctorRedactionContractTest` uses `only=[postgres-connectivity]` → unaffected, but confirm. `DoctorProbeAdapterTest` gets the new per-probe coverage.

### Doctor subsystem cheat-sheet (verified on disk)
- `DoctorService` (`application/diagnostics/DoctorService.java`): `CHECK_*` L33-51, `STATIC_ORDER` L53-71 (**17** checks), `runSingleProbe` switch L219-253, `REMEDIATION` map L73-125, redaction L291-308 (every field → `RedactionPolicyService.redact(.., SHAREABLE_REDACTED)`), `aggregate` L310-332 (FAIL>WARN>PASS, SKIP ignored).
- `DoctorProbePort` (`application/diagnostics/spi/DoctorProbePort.java`): 15 `probeX()` methods.
- `DoctorProbeAdapter` (`adapters/diagnostics/DoctorProbeAdapter.java`): `@Autowired` ctor L109-158 (ObjectProvider pattern for nullable deps), public test seam L163-195, github-auth seam L203-229, git-probes seam L237-263, observability-memory seam L271-294, final assigning ctor L296-344. Presence idiom: `probeRunnerSecrets` L665-690, `probeGitHubAuth` L701-760 (`isProfileActive`, presence-only).
- `DiagnosticsStatus` = `{PASS, WARN, FAIL, SKIP}`; `ProbeResult.{pass(s), pass(s,details), warn(s,code,details), fail(s,code,details), skip(s)}`; `DiagnosticsCheck(name,status,summary,remediation,errorCode,details)`.
- CLI only: `DoctorCommands` (`adapters/cli/DoctorCommands.java`) — `--format text|json`, `--only`, `--exclude`, `--correlation-id`. **No REST endpoint** (don't add one).
- Observability: `infrastructure/observability/RunnerQueueMetricsBinder.java` (`@Component implements MeterBinder`, 5 gauges, cached snapshot). Profile `observability` gates the Logstash appender in `logback-spring.xml`; the metric binders themselves are unconditional `@Component`s (meaningful when a Prometheus registry scrapes).

### Project Structure Notes
- **New (application):** none required beyond the `ProjectStore.findAll()` port method (Task 1) and an optional tiny config-check helper (Task 6) in `application.project`.
- **New (adapters/infra):** `infrastructure/observability/ProjectHealthMetricsBinder.java`.
- **Modified (main):** `application/project/ProjectStore.java` (+`findAll()`); `adapters/persistence/ProjectPersistenceAdapter.java` (+ list impl) + `adapters/persistence/repository/ProjectRepository.java` (+`findByArchivedAtIsNull`); `application/diagnostics/spi/DoctorProbePort.java` (+`probeProjects`); `application/diagnostics/DoctorService.java` (+`CHECK_PROJECTS`, `STATIC_ORDER`, switch case, REMEDIATION); `adapters/diagnostics/DoctorProbeAdapter.java` (+ deps through the ctor chain + `probeProjects` impl); `domain/registry/DomainErrorCode.java` (+`DOCTOR_PROJECT_CONFIG_INCOMPLETE`); `ProblemDetailsCatalog` (+ mapping); `registry-api-schema-placeholders.json` (+ problem-type URI).
- **Modified (test):** `DoctorServiceTest` (stub), `DoctorLoggingContractTest` (17→18), `DoctorProbeAdapterTest` (new probe coverage), plus new `ProjectHealthMetricsBinderTest`.
- **No** Flyway migration, **no** `project_credentials` mapping, **no** `ConnectorRole` enum, **no** `WorkflowEventType`, **no** REST/OpenAPI surface, **no** frontend change (the Projects UI is 3c-9; the connection-test endpoint is 3c-8). **No** new validated `@ConfigurationProperties` → no `application.yml`/test-yaml edit.

### Logging Requirements (project-wide standard)
This story introduces real application surface (the probe + the binder) — instrument it; do not mark N/A.
- **Framework:** SLF4J + Logback. No `System.out`/`printStackTrace()`.
- **Where to log:** `probeProjects` entry/result (INFO, with `projectCount` + verdict), per misconfigured active project (WARN, `project.publicId()` + `reason`), SKIP (INFO); `ProjectHealthMetricsBinder.bindTo` (INFO once).
- **Required context keys:** `correlationId`, `project.publicId()` (+ `connectorKind`/`role` where relevant), via MDC/`MdcKeys` or params.
- **Forbidden:** `LINEAR_API_TOKEN`/`GITHUB_TOKEN`/any resolved secret/ciphertext/key-id, raw PII. Log only the `present-via-*`/`missing` token.
- **Test contract:** pin each new log branch with a `ListAppender`/`OutputCaptureExtension`; include the leak-proof assertion.

### References
- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-10] — authoritative ACs (L193-206); epic context + FR56–FR63.
- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-8] — the `testConnection` endpoint + persisted connection result this story defers to (R3).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java#L33-71,L219-253,L291-308] — `CHECK_*`/`STATIC_ORDER`/`runSingleProbe`/redaction — the three doctor sites to extend.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java] — port to extend with `probeProjects()`; the presence-only Javadoc style to mirror.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java#L109-344,L665-760] — the 5-overload ctor chain to thread the new deps through; `probeRunnerSecrets`/`probeGitHubAuth` presence idiom + `isProfileActive`.
- [Source: deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java#L345-372] — `stubAllProbesPass()` to extend; `hasSize(STATIC_ORDER.size())` auto-tracks 18.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java#L89] — `checksRun=17` → bump to 18.
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/RunnerQueueMetricsBinder.java] — the `MeterBinder` precedent to mirror (cached snapshot, dotted meter names, strong field reference).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java#L49-76,L138-146] — the `DOCTOR_*` codes to sit beside; the 3c-2 "registered ahead of throw site" comment block (do not re-register the PROJECT_* codes).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectConnectorResolver.java] — `resolveTicketSource`/`resolveRepositoryHost` (UNSUPPORTED_CONNECTOR_KIND), `resolveConnectorSecret(project, role)` (the credential seam).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/project/Project.java] — the record the probe reads (`status`, `repositoryUrl`, `ticketSourceKind`, `repoHostKind`, `openspecEnabled`).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionPolicyService.java] — the redaction already applied to every doctor field (defense-in-depth).
- [Source: _bmad-output/implementation-artifacts/3c-6-default-project-migration-and-config-inversion-seam.md] — the `ProjectStore` read side this story consumes + adds `findAll()` to (R0 prerequisite).
- [Source: _bmad-output/implementation-artifacts/3c-3-per-project-connector-resolution-over-ticketsource-and-repohost.md] — the resolver + `ProjectCredentialSource` seam (empty until 3c-5).
- [Source: docs/patterns/registry-recipe.md] — Recipe 3 (DomainErrorCode three sites) + foundation-gate tier mechanics.
- Memory: `new-doctor-probe-fans-out` (the 4-site fan-out + `checksRun` literal); `micrometer-gauge-weak-ref-nan-flake` (keep the gauge state in a field); `new-domainerrorcode-three-sites`; `@SpringBootTest+Testcontainers test must be named *IT`; `Maven argLine direct-goal crash`; `application-cannot-import-adapters` (the probe reaches the DB only via the `ProjectStore` port).

### Open Decisions surfaced for review (do not block implementation; defaults chosen)
1. **Dependency correction** — the epic/sprint-status lists 3c-10 deps as "3c-3, 1-16", but listing projects requires the `ProjectStore` read side from **3c-6**. Default: treat **3c-6 as a hard prerequisite** (R0) and start 3c-10 only after 3c-6 merges. Flag at planning if the intended order differs.
2. **Connection-test scope** — default: 3c-10 reports **configuration health only** and emits `connectionTest=not-run`, deferring live connectivity to 3c-8's `testConnection`. Alternative (rejected as scope-creep + slow-doctor): run a live per-connector reachability check inside the probe. Proceeding with config-health-only.
3. **`configured` metric definition** — default: `deliveryline.projects.configured` counts active projects that are "repo bound + kinds resolvable" (credential-presence reported by the *probe*, not the metric, to keep env-reads out of the scrape hot path). Confirm whether credential-presence should also gate the gauge.
4. **`findAll()` placement** — default: add `findAll()` to `ProjectStore` in this story (its first listing consumer). Alternative: fold it into 3c-6 or 3c-8. Proceeding with adding it here.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-dev-story, 2026-06-21.

### Debug Log References

- `-Pfoundation-gate verify` (backend module): **BUILD SUCCESS** — Surefire 1196 tests (0 fail, 12 skipped) + Failsafe foundation/contract subset 36 tests (0 fail, all 15 Epic-1..3 contracts incl. #7 ProblemDetails stable codes + #3 registry drift + #1 ArchUnit boundaries). `ProjectHealthMetricsBinderTest` 2/2, `DoctorProbeAdapterTest` (incl. 9 new probe tests) green, `DoctorServiceTest`/`DoctorLoggingContractTest` green.
- Two iteration fixes during dev: (1) 4 pre-existing inline-stub `DoctorServiceTest` full-set tests needed the 18th probe stubbed (NPE on unstubbed `probeProjects`); (2) test Project public-ids must satisfy the `[A-Za-z0-9_-]{4,64}` suffix rule (`prj_ok` → `prj_active`). Both fixed; `spotless:apply` run for formatting.

### Completion Notes List

- **Task 1 was a no-op on the port:** `ProjectStore.findAll()` already existed (it shipped with story 3c-8 as the documented co-edit — "identical signature, its first listing consumer"). 3c-8's `findAll()` returns ALL projects ordered by `created_at` (includes disabled). Rather than re-point 3c-8's already-merged REST-list contract to exclude archived, the probe + binder consume the existing `findAll()` and filter `archivedAt() == null` in-code where AC1/AC5 require "non-archived". (Open Decision #4 resolved: `findAll` landed with its first consumer, 3c-8; this story consumes it.) Disabled projects (not archived) are intentionally still listed.
- **`configured` metric definition (Open Decision #3 resolved):** `deliveryline.projects.configured` = active + repo-bound + connector-kinds-resolvable, via the shared `ProjectConfigChecks.isStructurallyConfigured`. Credential-presence is reported by the doctor probe ONLY and deliberately does NOT gate the gauge — this keeps host-env reads off the Prometheus scrape hot path. The probe's WARN roll-up is stricter (adds the `missing`-credential condition).
- **Shared predicate, no drift:** `application/project/ProjectConfigChecks` (new, static helper) is the single source of truth for "structurally configured", consumed by both `probeProjects()` and `ProjectHealthMetricsBinder`. `kindsResolvable` catches `DomainException(UNSUPPORTED_CONNECTOR_KIND)` only; any other fault propagates.
- **Leak-proof, by construction:** the probe writes only the literal tokens `present-via-store` / `present-via-host-env` / `missing` + ids/kinds/booleans. The resolved secret and `environment.getProperty(name)` value are never interpolated into a `summary`/`details`/log. Pinned by store-path + host-env-path leak tests and a `ListAppender` log-leak assertion. `DoctorService` redaction (SHAREABLE_REDACTED on every field) remains as defense-in-depth.
- **R3 honored:** no live connectivity test — `details.connectionTest = "not-run (use the project testConnection endpoint, story 3c-8)"`. Config-health only.
- **No new surface beyond the AC:** no Flyway, no `project_credentials` JPA mapping, no `ConnectorRole` enum, no `WorkflowEventType`, no REST/OpenAPI change, no `@ConfigurationProperties` (so no `application.yml`/test-yaml edit), no frontend change. One new `DomainErrorCode` (three-sites).
- **Doctor fan-out (R7) complete:** `DoctorProbePort` (+probeProjects) · `DoctorService` (CHECK_PROJECTS + STATIC_ORDER + switch + REMEDIATION) · `DoctorProbeAdapter` (deps through all ctor overloads + impl) · `DoctorServiceTest.stubAllProbesPass` + 4 inline full-set tests · `DoctorLoggingContractTest` checksRun 17→18.

### File List

**Added (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectConfigChecks.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/ProjectHealthMetricsBinder.java`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java` (+`probeProjects()`)
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java` (+`CHECK_PROJECTS`, STATIC_ORDER, switch case, REMEDIATION)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java` (+deps through the ctor chain + `probeProjects()` + `credentialPresence`/`hostEnvSecretNameFor`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (+`DOCTOR_PROJECT_CONFIG_INCOMPLETE`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (+mapping)

**Modified (test/resources):**
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (+problem-type URI)

**Added (test):**
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/observability/ProjectHealthMetricsBinderTest.java`

**Modified (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapterTest.java` (+9 probe tests, new-seam helpers, env-seams ctor fix)
- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java` (+`probeProjects` stubs)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java` (checksRun 17→18 + stub)

**Modified (process):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status transitions)

### Change Log

- 2026-06-21 — Implemented story 3c-10: new `projects` doctor check (config-health + presence-only credential reporting, leak-proof) + `ProjectHealthMetricsBinder` (total/configured gauges, weak-ref-safe) + `DOCTOR_PROJECT_CONFIG_INCOMPLETE` error code (three-sites) + shared `ProjectConfigChecks` predicate. `checksRun` 17→18. Full `-Pfoundation-gate verify` GREEN. Status ready-for-dev → in-progress → review.

### Review Findings

_Code review 2026-06-21 (bmad-code-review, 3-layer adversarial: Blind Hunter + Edge Case Hunter + Acceptance Auditor). All 6 ACs verified PASS against live code. No Critical/High/Medium issues confirmed — the hunters' two flagged items (`kindsResolvable` re-throw "broken contract"; unguarded `findAll()` in probe) resolve to intended, documented, and safely-degraded behavior (`DoctorService.runSingleProbe` wraps any probe `RuntimeException` into a FAIL check; `ProjectConfigChecks` Javadoc states non-`UNSUPPORTED_CONNECTOR_KIND` faults must surface). 14 findings dismissed as noise/forward-looking/intended-degradation._

- [x] [Review][Patch] `new java.util.ArrayList<>()` written fully-qualified while `LinkedHashMap` is imported — minor import-style inconsistency [deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java — `probeProjects()`] — FIXED 2026-06-21: added `import java.util.ArrayList;`, use short form. `compile` + `spotless:check` GREEN.
