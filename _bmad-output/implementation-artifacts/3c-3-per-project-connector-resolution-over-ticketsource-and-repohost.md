# Story 3c.3: Per-Project Connector Resolution over TicketSourceAdapter / RepositoryHostAdapter

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **⚠️ READ FIRST — this is the FIRST occupant of `application.project` and the keystone that turns the 3.32/3.33 ports + the 3c-2 `Project`/`ConnectorKind` registry into a working per-project resolver.** Everything you depend on is already on disk and merged:
>
> - **`Project` aggregate** — `org.dradgo.domain.project.Project` (a `record`) already carries `ticketSourceKind()` and `repoHostKind()` as `ConnectorKind` (3c-2). The resolver **receives** a `Project`; it does **not** load one — there is **no `Project` persistence/read side yet** (that is 3c-6/3c-8). Tests construct `Project` records directly.
> - **`ConnectorKind` registry** — `org.dradgo.domain.registry.ConnectorKind` = `LINEAR("linear")`, `GITHUB("github")` (3c-2). Resolution is **registry-driven**, NOT off the global `deliveryline.integration.*.kind` config keys.
> - **`UNSUPPORTED_CONNECTOR_KIND`** — already registered at all **three sites** (`DomainErrorCode` enum L146 + `ProblemDetailsCatalog` → `400`/`retryable=false` + `registry-api-schema-placeholders.json` `problemTypeUris`) by 3c-2, **ahead of its throw site, which is THIS story.** **Do NOT re-register it** — that reds the gate. You only *throw* it from the new resolver.
> - **The two ports** — `org.dradgo.application.integration.ticketsource.TicketSourceAdapter` (3.32) and `org.dradgo.application.integration.repohost.RepositoryHostAdapter` (3.33) are application-owned, vendor-neutral, and already carry `getCapabilities()`.
>
> **The hard truth this story must reconcile (see Dev Notes → "Why these ACs are reconciled"):** adapters today are selected by **mutually-exclusive Spring `@Profile`** (`linear-mock` XOR `linear-real`, `github-mock` XOR `github-real`) — exactly **one** bean exists per port, and there is **no `Map<ConnectorKind, adapter>` anywhere**. Lifting the global single-kind selection to a per-project kind→adapter map is the net-new work. Two downstream stories are **NOT done yet** and you must NOT hard-depend on them: the **credential store is 3c-5** (`backlog`; cipher 3c-4 is `ready-for-dev`, not merged) and **run↔project association is 3c-6/3c-7** (`backlog`). AC6 and AC5 are therefore built as **parity-preserving seams**, not live wiring.
>
> The epic still says "Flyway V14" — that number is stale. Head is **`V17`** (3c-1). Your migration in this story is **`V18`**. The story key/filename keeps its slug (synced to `sprint-status.yaml` — do not rename).

## Story

As a backend developer,
I want a `ProjectConnectorResolver` that returns the correctly-typed `TicketSourceAdapter` and `RepositoryHostAdapter` for a given `Project` — driven by the `ConnectorKind` registry, rejecting unsupported kinds at the application layer, and honoring the existing capability-degradation contract,
so that each project can use its own selectable ticket-source and repository-host connector instead of one global profile-pinned connector, and the `LINEAR_GITHUB_REPO_MISMATCH` guard becomes project-scoped.

## Acceptance Criteria

> These ACs are **reconciled** against the live profile-based wiring and the actual dependency-graph state (3c-4/3c-5/3c-6/3c-7 not merged). Where the epic wording assumes infrastructure that does not exist yet, the reconciled wording below is authoritative; the rationale is in Dev Notes.

1. **Given** the `TicketSourceAdapter` (3.32) and `RepositoryHostAdapter` (3.33) ports, **When** a `Project` is passed to a new `ProjectConnectorResolver` in `org.dradgo.application.project`, **Then** `resolveTicketSource(project)` returns the adapter whose declared kind equals `project.ticketSourceKind()` and `resolveRepositoryHost(project)` returns the adapter whose declared kind equals `project.repoHostKind()`.

2. **Given** registered connector kinds (3c-2 `ConnectorKind`), **Then** resolution is driven by each adapter's **own declared `ConnectorKind`** (a new `connectorKind()` accessor on both ports), assembled into a `Map<ConnectorKind, adapter>` at resolver construction — **not** by the global `deliveryline.integration.ticket-source.kind` / `deliveryline.integration.repo-host.kind` keys. Those globals remain bound (normalize-never-throw) only as the future default project's seed values (3c-6) and are **not read** by the resolver.

3. **Given** a `Project` whose `ticketSourceKind()` or `repoHostKind()` has no adapter registered for it in the active context, **Then** the resolver raises `new DomainException(DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND, …)` at the application layer (not relying solely on the V18 DB CHECK), surfaced via the existing Problem Details mapping as **HTTP 400**, `retryable=false`. The error detail names the offending kind + role but **never** echoes credentials.

4. **Given** capability detection (3.32 AC3 / 3.33 AC3), **Then** the resolver returns the adapter **as-is** and does **not** alter the degradation contract: consuming services still call `getCapabilities()` and gate optional operations (e.g. `IntegrationLinkService` checks `supportsCommentOnTicket()` before `postGovernedRunComment`; PR comment posting checks `supportsPullRequestComments()`). A test proves a resolved adapter that reports a capability as `false` is degraded gracefully exactly as today — **no new `WorkflowEventType`, no behavioral change to 3.32/3.33.**

5. **Given** the existing global `LINEAR_GITHUB_REPO_MISMATCH` guard (NFR20, story 3.14 AC4) in `RepositoryWorkspaceService.prepareWorkspace`, **Then** the resolver exposes a project-scoped check `assertRepositoryRefMatchesProject(project, repositoryRef)` that raises `LINEAR_GITHUB_REPO_MISMATCH` when the requested `repositoryRef` does not match the `RepositoryRef` derived from `project.repositoryUrl()`; **and** `prepareWorkspace` is refactored to resolve the *expected* repo binding through a seam (`resolveExpectedRepositoryRef`) that **today falls back to the existing global `resolveConfiguredRepositoryRef()`** (byte-identical behavior — the broker still passes the configured ref), with the per-run `Project` injection deliberately deferred to 3c-6/3c-7. No clone occurs before this check (it runs in the existing pre-clone guard block).

6. **Given** a resolved adapter will eventually need per-project credentials, **Then** 3c-3 establishes the **at-use-time credential seam only**: a minimal `ProjectCredentialSource` port in `application.project` with a single `Optional<…> resolveSecret(project, role)`-shaped method, injected into the resolver via `ObjectProvider` and returning empty today (no store exists). The resolver **never** stores plaintext in a field and never logs it. **Live per-project decryption is 3c-5's deliverable** (after the 3c-4 security-review gate); adapters continue to use their existing host-env secret path (`runnerSecretsService` / `GITHUB_TOKEN_ENV`) until then. A test asserts the resolver retains no credential state.

7. **Given** ArchUnit boundaries, **Then** `ProjectConnectorResolver` and `ProjectCredentialSource` depend **only** on the abstract ports (`TicketSourceAdapter`, `RepositoryHostAdapter`), the `domain.project`/`domain.registry`/`domain.integration.*` types, and never on a concrete vendor adapter (`Linear*Adapter` / `GitHub*Adapter`) nor any `org.dradgo.adapters..` / `org.dradgo.infrastructure..` package — preserving `LAYERED_BOUNDARIES` and the 3.32/3.33 leak-detection rules.

8. **Given** the scope cap, **Then** beyond `linear`/`github` **exactly one** additional `ConnectorKind` — `GITLAB("gitlab")` — is registered as a **documented stub** that genuinely proves the kind→adapter seam: stub `TicketSourceAdapter` + `RepositoryHostAdapter` beans declare `connectorKind() == GITLAB`, return safe no-op/empty results, and report a **deliberately degraded** capability set (e.g. `supportsCommentOnTicket=false`) so AC4's degradation path is exercised by a real registered kind. Widening `ConnectorKind` fans out to **V18** (drop-then-re-add both `ck_projects_ticket_source_kind` and `ck_projects_repo_host_kind` CHECKs), the `connectorKinds` API-placeholder array, and is picked up automatically by the 3c-2 `RegistryContractTest` drift gate. Full additional vendor *implementations* remain post-pilot.

9. **Given** tests, **Then** coverage asserts: kind→adapter resolution for **each** registered kind (`linear`, `github`, `gitlab`); `UNSUPPORTED_CONNECTOR_KIND` raised for a kind with no registered adapter; duplicate-kind fail-fast at resolver construction; capability-driven degradation parity through the resolved adapter (AC4); project-scoped `LINEAR_GITHUB_REPO_MISMATCH` rejection (AC5) **and** a parity test that the refactored `prepareWorkspace` is byte-identical when the requested ref equals the configured ref; and that the resolver holds no credential state (AC6). New `application.project` code meets the standing **≥80% line-coverage** threshold (credential-adjacent code **≥90%**).

## Tasks / Subtasks

- [x] **Task 1 — Add `connectorKind()` to both ports + implement on every adapter** (AC: 1, 2, 7)
  - [x] Add `org.dradgo.domain.registry.ConnectorKind connectorKind();` to `application/integration/ticketsource/TicketSourceAdapter.java` (Javadoc: "Declare which `ConnectorKind` this adapter serves — the key `ProjectConnectorResolver` selects on (3c-3)."). `ConnectorKind` is `domain.registry` → allowed through the port (no vendor leak; satisfies `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT`).
  - [x] Add the same accessor to `application/integration/repohost/RepositoryHostAdapter.java`.
  - [x] Implement on the **four** existing vendor adapters: `LinearMockAdapter` + `LinearRealAdapter` → `return ConnectorKind.LINEAR;`; `GitHubMockAdapter` + `GitHubRealAdapter` → `return ConnectorKind.GITHUB;` (packages `adapters.integration.ticketsource.linear` / `adapters.integration.repohost.github`).
  - [x] **Fan-out check:** grep every `implements TicketSourceAdapter` / `implements RepositoryHostAdapter` (incl. test fakes/anonymous impls in `TicketSourceAbstractionFoundationContract` / `RepositoryHostAbstractionFoundationContract` and any unit-test doubles) and add `connectorKind()` so they still compile.
- [x] **Task 2 — Keep single-bean consumers unambiguous with `@Primary`** (AC: 1, 8)
  - [x] Add `@org.springframework.context.annotation.Primary` to `LinearMockAdapter`, `LinearRealAdapter`, `GitHubMockAdapter`, `GitHubRealAdapter` (only one vendor profile is ever active per port — the profile-conflict fail-fast guards already guarantee that, so at most one `@Primary` candidate exists per port).
  - [x] Rationale: Task 4 introduces always-on stub adapter beans, so `IntegrationLinkService` (injects a single `TicketSourceAdapter`) and `RepositoryWorkspaceService` (injects a single `RepositoryHostAdapter`) would otherwise hit `NoUniqueBeanDefinitionException`. `@Primary` on the active vendor bean resolves single-injection points; the resolver's `List<>` injection still receives **all** beans. (Same fix pattern as the `DockerHostPort dual-bean ambiguity` memory.)
  - [x] Verify with a context-load slice test (or the existing `IntegrationProfileWiringContractTest` / `GitHubProfileWiringContractTest`) that boot still succeeds with stubs present.
- [x] **Task 3 — Register the `GITLAB` connector kind + V18 CHECK widening + API placeholder** (AC: 8)
  - [x] `domain/registry/ConnectorKind.java`: add `GITLAB("gitlab")` (keep `LINEAR`, `GITHUB`; same template). `DomainRegistry.connectorKinds()` auto-derives `{linear, github, gitlab}`.
  - [x] Create `src/main/resources/db/migration/V18__widen_connector_kind_to_gitlab.sql` using the **drop-then-re-add CHECK idiom** (precedent: V12/V16) for **both** constraints:
    ```sql
    alter table projects drop constraint ck_projects_ticket_source_kind;
    alter table projects add constraint ck_projects_ticket_source_kind
      check (ticket_source_kind in ('linear','github','gitlab'));
    alter table projects drop constraint ck_projects_repo_host_kind;
    alter table projects add constraint ck_projects_repo_host_kind
      check (repo_host_kind in ('linear','github','gitlab'));
    ```
    (Confirm the exact existing CHECK literal form in `V17__create_projects_and_credentials.sql` and mirror it; additive/idempotent — second Flyway run must be a no-op.)
  - [x] `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`: update the `connectorKinds` array to `["linear", "github", "gitlab"]`.
  - [x] If `FlywaySchemaContractTest` pins a migration head/version or count, bump it for V18 (it should already cover the `projects` table from 3c-1 — a CHECK change usually needs no table-list edit; verify).
  - [x] **Do NOT touch** `RegistryContractTest` — its `projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest()` reads the enum + both CHECKs + the placeholder array and will pass once the three are consistent (this is your green signal that V18 + placeholder are correct).
- [x] **Task 4 — Add the `GITLAB` documented-stub adapters** (AC: 8, 4, 9)
  - [x] `adapters/integration/ticketsource/gitlab/GitLabTicketSourceStubAdapter.java` — `@Component` (always-on, **no `@Profile`**), `implements TicketSourceAdapter`; `connectorKind()` → `ConnectorKind.GITLAB`; `getCapabilities()` → a degraded `TicketSourceCapabilities(false /*supportsCommentOnTicket*/, false, false)`; the other methods return safe empties (`Optional.empty()`, `List.of()`) / `CommentResult.SKIPPED_*` and log at `INFO` "gitlab stub: <op> is a documented no-op". Class Javadoc: "Documented stub proving the per-project connector seam (3c-3 AC8); a real GitLab impl is post-pilot."
  - [x] `adapters/integration/repohost/gitlab/GitLabRepositoryHostStubAdapter.java` — same shape; `getCapabilities()` → degraded `RepositoryHostCapabilities` (e.g. `supportsPullRequestComments=false`); methods return safe empties / throw the *typed* not-implemented path only where a non-Optional return is required (prefer empties so the stub never crashes a happy-path resolution test).
  - [x] These live under `adapters.integration.{ticketsource,repohost}.gitlab` to satisfy the `*_IMPLS_RESIDE_IN_ADAPTERS_*` ArchUnit residence rules. They must import **no** vendor SDK (nothing to leak).
- [x] **Task 5 — Create `ProjectConnectorResolver` in `application.project`** (AC: 1, 2, 3, 7, 9)
  - [x] Create the package `org.dradgo.application.project` (first occupant — 3c-2 deliberately left it empty).
  - [x] `ProjectConnectorResolver` as a `@org.springframework.stereotype.Service`, constructor-injecting `List<TicketSourceAdapter>` and `List<RepositoryHostAdapter>` (Spring injects all beans; `List` injection is never ambiguous) plus `ObjectProvider<ProjectCredentialSource>` (Task 6).
  - [x] In the constructor, build `Map<ConnectorKind, TicketSourceAdapter>` and `Map<ConnectorKind, RepositoryHostAdapter>` keyed on `adapter.connectorKind()`. **Fail fast** on a duplicate kind (two beans claiming the same `ConnectorKind`) with a clear `IllegalStateException` — a misconfiguration must not boot silently. Log `INFO` the assembled kind→adapter map (kinds only, no secrets).
  - [x] `public TicketSourceAdapter resolveTicketSource(Project project)` → `Objects.requireNonNull(project)`, look up `project.ticketSourceKind()`; missing → `throw unsupported(project.ticketSourceKind(), "ticket-source")`. Same for `resolveRepositoryHost(Project)`.
  - [x] Private `DomainException unsupported(ConnectorKind kind, String role)` → `new DomainException(DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND, "No " + role + " connector registered for kind " + kind.value())` (names kind + role, no credentials). `WARN`-log before throwing with the `projectId`/`kind`/`role` context keys.
  - [x] Imports allowed: `domain.project.Project`, `domain.registry.{ConnectorKind, DomainErrorCode}`, `domain.DomainException`, the two application ports. **No** `adapters..`/`infrastructure..` imports.
- [x] **Task 6 — Establish the at-use-time credential seam (no live decryption)** (AC: 6, 7)
  - [x] `application/project/ProjectCredentialSource.java` — a minimal port: one method returning `Optional` of an in-memory secret for `(project, role)`. Keep `role` as a `String` for now (the `ConnectorRole` enum/`ticket_source`|`repo_host` value set is **3c-5's** to introduce — do NOT invent it here). Javadoc: "At-use-time per-project credential lookup. 3c-3 ships the seam returning empty; the encrypted store backing it lands in 3c-5 after the 3c-4 security-review gate. Secrets are returned for immediate use and never retained or logged."
  - [x] Inject it into the resolver via `ObjectProvider<ProjectCredentialSource>` and resolve **lazily at call time** (`getIfAvailable()`), never in the constructor — matches the `Broker↔orchestration lazy Supplier` / `unconditional-service-needs-profile-gate` patterns. With no bean present today, the resolver simply does not inject a per-project secret (adapters fall back to their existing host-env secret path).
  - [x] **Do NOT** add a default `@Component` implementation that reads anything — the seam returns empty until 3c-5. The resolver must keep **no** plaintext field.
- [x] **Task 7 — Make the `LINEAR_GITHUB_REPO_MISMATCH` guard project-scoped (parity-preserving)** (AC: 5, 9)
  - [x] Add `public void assertRepositoryRefMatchesProject(Project project, String repositoryRef)` to `ProjectConnectorResolver`: derive the project's bound `RepositoryRef` by normalizing `project.repositoryUrl()` to `owner/repo` (reuse the same normalization that produces `WorkflowProperties.RepoConfig.repositoryRef()` — extract/share it rather than duplicating); if the project has a binding and it does not equal `repositoryRef`, raise `new DomainException(DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH, …)`. Null/blank project binding → no-op (cannot assert what isn't bound yet).
  - [x] In `application/runner/workspace/RepositoryWorkspaceService.java`, introduce a private `Optional<String> resolveExpectedRepositoryRef(String workflowRunId)` that **today delegates to `resolveConfiguredRepositoryRef()`** (the existing global single-repo source) — this is the single swap point 3c-6/3c-7 will repoint to the run's `Project`. Add a pre-clone check inside `prepareWorkspace` (alongside the existing `resolveRepositoryOrMismatch` / `assertNoConflictingRepoLink` block, **before** any clone): if `resolveExpectedRepositoryRef` yields a ref and it differs from the requested `repositoryRef`, call `repoMismatch(...)`. Mark with a `// 3c-3 AC5 seam — global fallback until run↔Project wiring (3c-6/3c-7)` comment.
  - [x] **Parity guarantee:** today the broker passes the configured repo ref as `repositoryRef`, so expected == requested → no new rejection. A parity test must assert pre-existing happy-path `prepareWorkspace` behavior is unchanged.
  - [x] `RepositoryWorkspaceService` may depend on `application.project.ProjectConnectorResolver` (application→application is allowed) but must still obey `REPOSITORY_WORKSPACE_SERVICE_SCOPE` (no `adapters..`/`jgit`). Keep the project-aware assertion in the resolver; keep only the config-fallback seam in the workspace service for now.
- [x] **Task 8 — Tests** (AC: 9)
  - [x] `application/project/ProjectConnectorResolverTest.java` (plain unit, **Surefire-safe**, no `@SpringBootTest`/Testcontainers — construct the resolver with hand-built `List`s of fake adapters declaring `LINEAR`/`GITHUB`/`GITLAB`): assert each kind resolves to its adapter; a `Project` with a kind absent from the list throws `UNSUPPORTED_CONNECTOR_KIND`; two adapters claiming the same kind fail construction; the resolved adapter's `getCapabilities()` is returned untouched (degradation parity); `assertRepositoryRefMatchesProject` raises `LINEAR_GITHUB_REPO_MISMATCH` on a mismatched ref and passes on a match / null binding; the resolver exposes no credential getter / retains no secret state.
  - [x] A wiring slice/IT (`*IT`, Failsafe) asserting that under the default profiles the resolver's maps contain the active vendor kind **plus** `GITLAB`, and single-injection consumers still boot (`@Primary` works).
  - [x] `RepositoryWorkspaceService` parity test (extend the existing workspace test) — requested ref == configured ref ⇒ no mismatch (byte-identical), requested ref ≠ expected (simulated) ⇒ `LINEAR_GITHUB_REPO_MISMATCH`.
  - [x] Mirror the connector-abstraction foundation contracts (`TicketSourceAbstractionFoundationContract` #14 / `RepositoryHostAbstractionFoundationContract` #15) if they enumerate adapters-by-kind — add the `GITLAB` stub to their fixtures so the parity sweep covers it.
  - [x] **Naming discipline:** any `@SpringBootTest`+Testcontainers test is `*IT` (Failsafe) — a `*Test` name leaks into the no-Docker Windows Surefire tier and reds CI (`@SpringBootTest+Testcontainers test must be named *IT` memory). ArchUnit `@ArchTest`s run in Failsafe.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (resolver constructed with kind→adapter map, resolve start/result, decisions taken), `WARN` for recoverable anomalies (`UNSUPPORTED_CONNECTOR_KIND`, repo mismatch, gitlab-stub no-op invoked), `ERROR` only for unhandled failures or invariant breaks (duplicate-kind boot failure). `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys where available: `correlationId`, `workflowRunId`, plus the project's own public id (`project.publicId()`) and the `connectorKind`/`role`. Use MDC where the framework supports it (`MdcKeys`); otherwise pass as parameters.
  - [x] **Never log secrets, payload bytes, raw tokens, or full PII** — the credential seam returns secrets for immediate use only; they must never reach a log line, event, artifact, or export (this becomes a foundation-gate assertion in 3c-5).
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use a list-appender or `OutputCaptureExtension`).

## Dev Notes

### Why these ACs are reconciled (epic wording vs. the live codebase)

The epic's Story 3c-3 ACs were written assuming three things that are **not yet true on `main`**, so each was tightened to a parity-preserving seam:

| Epic assumption | Reality | Reconciliation |
|---|---|---|
| "resolver returns the **correctly-credentialed** adapter … obtains the per-project decrypted secret via the credential store (3c-5)" (epic AC1/AC6) | **3c-5 is `backlog`; 3c-4 cipher is `ready-for-dev`, not merged.** No store, no `getDecrypted`, no `ConnectorRole` enum, no `project_credentials` JPA entity exist. | AC6 ships the **`ProjectCredentialSource` seam returning empty** + the no-plaintext-retention constraint. Live decryption is explicitly 3c-5's. |
| "project-scoped … validates the requested `repositoryRef` against the **run's Project** repo binding" (epic AC5) | **Run↔Project association is 3c-6/3c-7 (`backlog`).** A run carries no `project_id` the application reads yet (V17 added the column nullable; backfill is 3c-6). | AC5 ships the **resolver-side project check** (unit-testable on a `Project`) + a `resolveExpectedRepositoryRef` **seam that falls back to global config** in `prepareWorkspace`. 3c-6/3c-7 repoint the seam. Parity test guards byte-identical behavior. |
| "resolution is driven by the `ConnectorKind` registry … the resolver returns the adapter matching `project.ticketSourceKind()`" (epic AC1/AC2) | Adapters are **profile-selected, one bean per port**; there is **no kind-keyed map** and the `.kind` config key is a documented selector only, not a bean selector. | The resolver introduces the kind→adapter map by adding **`connectorKind()` to the ports** + `List<>` injection. `@Primary` on vendor adapters keeps single-injection consumers safe once the always-on `GITLAB` stub beans exist. |

Everything else (the `UNSUPPORTED_CONNECTOR_KIND` three-sites registration, the `Project` aggregate, the `ConnectorKind` enum) is **already done by 3c-2** — consume, do not re-create.

### The core design: kind→adapter map over profile-pinned beans

- **Today:** `linear-mock` XOR `linear-real` ⇒ one `TicketSourceAdapter`; `github-mock` XOR `github-real` ⇒ one `RepositoryHostAdapter`. `IntegrationLinkService` injects a single `TicketSourceAdapter` and an `ObjectProvider<RepositoryHostAdapter>`; `RepositoryWorkspaceService` injects a single `RepositoryHostAdapter`. (Confirmed in source.)
- **After 3c-3:** each adapter self-declares its `ConnectorKind` via `connectorKind()`. The resolver injects `List<TicketSourceAdapter>` / `List<RepositoryHostAdapter>` and indexes by kind. The always-on `GITLAB` stubs make the maps genuinely contain **two** entries per port (active vendor + gitlab), so resolution is a real lookup, not a degenerate single-entry pass-through — this is what makes AC8 "prove the seam" honest and AC9's per-kind resolution test meaningful.
- **Why `@Primary` and not a dedicated stub profile:** a profile-gated stub would only appear when its profile is active, but co-activating it with a vendor profile reintroduces the single-injection ambiguity in a full-context IT. Always-on stubs + `@Primary` on the (single) active vendor bean is the lowest-friction, production-safe shape and mirrors the existing `DockerHostPort` `@Primary` fix.

### Capability degradation parity (AC4)

The resolver is a **selector, not a decorator** — it returns the adapter untouched. The 3.32/3.33 contract is preserved verbatim: consumers call `getCapabilities()` and gate optional ops (`supportsCommentOnTicket()` gates `postGovernedRunComment`; `supportsPullRequestComments()` gates `commentOnPullRequest`). Per ADR 0007 Decision #5, an unsupported comment capability degrades to a **log + `SKIPPED_NO_COMMENT_CAPABILITY` outcome — NOT a new `WorkflowEventType`.** The `GITLAB` stub reporting `supportsCommentOnTicket=false` is the cheapest way to exercise this path against a real registered kind.

### `UNSUPPORTED_CONNECTOR_KIND` — consume only (do NOT re-register)

Already at all three sites from 3c-2: `DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND` (L146) → `ProblemDetailsCatalog` `HttpStatus.BAD_REQUEST` + `retryable=false` → `registry-api-schema-placeholders.json` `problemTypeUris["UNSUPPORTED_CONNECTOR_KIND"] = "https://deliveryline.local/problems/unsupported-connector-kind"`. The `DomainErrorCode.java` L140-141 comment explicitly designates "the `ProjectConnectorResolver` in 3c-3" as the throw site. Re-adding any of the three reds `RegistryContractTest` / the `ProblemDetailsCatalog` class-load guard. **The only DomainErrorCode work in this story is the `throw`.** (If you somehow needed a *new* code, follow `docs/patterns/registry-recipe.md` Recipe 3 — but you do not.)

### The `GITLAB` registry-widening fan-out (Task 3)

Adding `ConnectorKind.GITLAB` is **not** free — the 3c-2 drift gate (`RegistryContractTest.projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest`) asserts `connectorKinds()` == `ck_projects_ticket_source_kind` CHECK == `ck_projects_repo_host_kind` CHECK == the `connectorKinds` placeholder array. So the enum value forces **V18** (both CHECKs, drop-then-re-add idiom) + the placeholder array update, together, or the gate reds. `connectorKinds()` and the drift test auto-derive from the enum — no test *code* edit, only data. No frontend allowed-value list exists yet (project UI is 3c-9), so the API placeholder array is the only contract surface.

### ArchUnit guardrails you must not trip (AC7)

- `LAYERED_BOUNDARIES` + `APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE` — `application.project` must not import `adapters..` or `infrastructure..`. Reach adapters only through the application-owned ports.
- `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` / `REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT` — the new `connectorKind()` returns a `domain.registry` type (clean); never widen the ports with a vendor type.
- `*_ADAPTER_PORT_RESIDES_IN_APPLICATION` / `*_IMPLS_RESIDE_IN_ADAPTERS_*` — port edits stay in `application.integration.*`; the `GITLAB` stubs live in `adapters.integration.{ticketsource,repohost}.gitlab`.
- `REPOSITORY_WORKSPACE_SERVICE_SCOPE` — `application.runner.workspace` still may not touch `adapters..`/`jgit`; keep the project-aware assertion in the resolver.

### Sequencing — what this story sets up vs. defers

- **Sets up:** the resolver, the kind→adapter seam, the `GITLAB` proof kind, the credential seam shape, the project-scoped mismatch check + its `prepareWorkspace` swap point.
- **Defers (do not attempt here):** loading a `Project` by id (no read side until 3c-6/3c-8); wiring the run's `project_id` into `prepareWorkspace`/dispatch (3c-7); the encrypted credential store + `ConnectorRole` enum + redaction integration (3c-5, gated behind 3c-4's security review); the foundation-gate "per-project connector resolution works" assertion (3c-11 widens the gate — this story only provides the resolver it asserts against).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface) for this story:**
  - `ProjectConnectorResolver` construction → `INFO` the assembled kind→adapter map (kinds only).
  - `resolveTicketSource` / `resolveRepositoryHost` → `INFO` on entry with `projectId` + requested `connectorKind`; `WARN` + the `UNSUPPORTED_CONNECTOR_KIND` raise on a miss.
  - `assertRepositoryRefMatchesProject` → `WARN` + `LINEAR_GITHUB_REPO_MISMATCH` on a project-binding mismatch (reuse the existing `repoMismatch` log shape in `RepositoryWorkspaceService`).
  - `GITLAB` stub adapters → `INFO` "gitlab stub: <op> no-op" so a misrouted project is obvious in logs.
- **Required context keys** (via MDC / `MdcKeys` or structured params): `correlationId`, `workflowRunId`, `project.publicId()`, `connectorKind`, `role`.
- **Forbidden in log output:** any credential/secret returned by `ProjectCredentialSource`, tokens, raw PII. The seam returns secrets for immediate adapter use only.
- **Test contract:** new logging surfaces pinned by at least one focused test (list-appender or `OutputCaptureExtension`).

### Project Structure Notes

- New package `org.dradgo.application.project` — `ProjectConnectorResolver`, `ProjectCredentialSource` (first occupants; 3c-2 left it intentionally empty).
- New adapter packages `org.dradgo.adapters.integration.ticketsource.gitlab` + `org.dradgo.adapters.integration.repohost.gitlab` — stub adapters only.
- Port edits: `application/integration/ticketsource/TicketSourceAdapter.java`, `application/integration/repohost/RepositoryHostAdapter.java`.
- Vendor-adapter edits: `@Primary` + `connectorKind()` on `Linear{Mock,Real}Adapter`, `GitHub{Mock,Real}Adapter`.
- Registry/schema: `domain/registry/ConnectorKind.java`, `db/migration/V18__widen_connector_kind_to_gitlab.sql`, `registry-api-schema-placeholders.json` (`connectorKinds`).
- Workspace seam: `application/runner/workspace/RepositoryWorkspaceService.java`.
- No new `DomainErrorCode`, no new `WorkflowEventType`, no Flyway table, no frontend change.

### Open decisions surfaced for PO/dev review (do not block implementation)

1. **AC8 stub vendor choice** — `GITLAB` is recommended because it is a genuine future ticket-source **and** repo-host (appears in both architecture vendor lists), so one `ConnectorKind` proves both resolver paths. If the team prefers a vendor-neutral placeholder, swap the literal (`stub`/`example`) — but it still costs the same V18 + placeholder fan-out.
2. **Always-on stubs + `@Primary`** vs. **profile-gated stubs** — recommendation is always-on + `@Primary` (production-safe, lowest test friction). If the team objects to shipping no-op beans in production, the alternative is a `connector-stub` profile and resolver wiring tests that avoid loading single-injection consumers; flag at review.
3. **ADR 0012** — `architecture.md` L390 anticipates `docs/adr/0012-per-project-connector-resolution.md`. It does not exist yet. Recommend authoring it in this story to record the profile→kind lift + the stub-seam decision (small, in scope). Confirm whether ADRs are batched per-epic instead.

### References

- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-3] — authoritative ACs (L74-90); epic context + FR56–FR63 coverage.
- [Source: _bmad-output/planning-artifacts/architecture.md#Multi-Project-Configuration-&-Connector-Pluggability] — L378-390 resolver mandate ("unsupported/unregistered kind rejected at the application layer, not only by the DB CHECK"); L1169-1174 layering convention; L724-726 Problem Details fields.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-14-multi-project.md] — L60 `ProjectConnectorResolver` definition; project-scoping of `LINEAR_GITHUB_REPO_MISMATCH`.
- [Source: _bmad-output/planning-artifacts/prd.md] — FR58 (per-project connector selection, L717), FR60 (run scoped to one project, L719), NFR20 (L755, wrong-repo guard).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/project/Project.java] — the `record` the resolver receives; `ticketSourceKind()` / `repoHostKind()` accessors.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/ConnectorKind.java] — `LINEAR`/`GITHUB`; add `GITLAB` (Task 3).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java#L140-146] — `UNSUPPORTED_CONNECTOR_KIND` (already registered; throw-site = this story) + `LINEAR_GITHUB_REPO_MISMATCH`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapter.java] — port to extend with `connectorKind()`; `getCapabilities()` contract.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/repohost/RepositoryHostAdapter.java] — sibling port.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java#L165-238,L395-515] — `prepareWorkspace` pre-clone guard block; `resolveConfiguredRepositoryRef()` (the AC5 fallback seam); `repoMismatch(...)`.
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java] — `LAYERED_BOUNDARIES`, the 3.32/3.33 leak/residence rules, `REPOSITORY_WORKSPACE_SERVICE_SCOPE`.
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java] — the 1.4 drift gate that auto-verifies the `GITLAB` widening (enum == both CHECKs == placeholder).
- [Source: docs/adr/0007-ticket-source-abstraction.md, docs/adr/0008-repository-host-abstraction.md] — port residence + capability-degradation decisions; both name 3c-3 as the downstream consumer.
- [Source: docs/integrations/ticket-source-extension-contract.md, docs/integrations/repository-host-extension-contract.md] — Contract #14/#15 parity-sweep fixtures to extend with `GITLAB`.
- [Source: docs/patterns/registry-recipe.md] — Recipe 3 (DomainErrorCode three sites — for reference only; not needed here) + foundation-gate tier mechanics.
- [Source: _bmad-output/implementation-artifacts/3c-2-project-domain-aggregate-and-registries-and-drift-tests.md] — previous story; the registry/prefix/error-code foundation this story consumes.
- Memory: `DockerHostPort dual-bean ambiguity` (@Primary fix), `@SpringBootTest+Testcontainers test must be named *IT`, `Maven argLine direct-goal crash` (use the lifecycle phase for `-Pfoundation-gate`), `ArchUnit runs in Failsafe not Surefire`.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.8 (claude-opus-4-8[1m]) — bmad-dev-story workflow.

### Debug Log References

- Surefire (unit): `ProjectConnectorResolverTest` (14), `GitLabStubAdaptersTest` (6), `RepositoryWorkspaceServiceTest` (15) — all green.
- Failsafe (Testcontainers): `RegistryContractTest` 19/19 (the 3c-2 drift gate confirms `ConnectorKind`={linear,github,gitlab} == both V18 CHECKs == `connectorKinds` placeholder), `FlywaySchemaContractTest` 18/18 (V18 applies + replay-safe), `ArchitectureBoundaryTest` 56/56 (AC7 boundary/leak/residence rules), `ProjectConnectorResolverWiringIT` 2/2 (resolver maps = active vendor kind + GITLAB; @Primary keeps single-injection consumers booting), `IntegrationProfileWiringContractTest` 4/4 + `GitHubProfileWiringContractTest` 3/3 (sliced single-bean assertions unaffected by sibling gitlab stubs).
- One ArchUnit fix during dev: `ProjectConnectorResolver` could not be `@Service` (the `APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES` rule reserves that for `*Service`/`*Orchestrator` names) — switched to `@Component` to keep the story-mandated `*Resolver` name; bean registration is identical.

### Completion Notes List

- **AC1/AC2** — `ProjectConnectorResolver` indexes `List<TicketSourceAdapter>`/`List<RepositoryHostAdapter>` into `EnumMap<ConnectorKind, …>` keyed on the new `connectorKind()` port accessor; resolution is registry-driven, never the global `.kind` config keys.
- **AC3** — unregistered kind → `DomainException(UNSUPPORTED_CONNECTOR_KIND)` at the application layer (consume-only; the code was already three-sites-registered by 3c-2). Names kind + role, never credentials.
- **AC4** — resolver is a selector, returns the adapter untouched; degradation parity proven via a registered GITLAB stub reporting `supportsCommentOnTicket=false` / `supportsPullRequestComments=false`.
- **AC5** — `assertRepositoryRefMatchesProject` raises `LINEAR_GITHUB_REPO_MISMATCH` on a project-binding mismatch (null binding = no-op); `RepositoryWorkspaceService.prepareWorkspace` gained a pre-clone seam delegating to `resolveExpectedRepositoryRef` → today the global `resolveConfiguredRepositoryRef()` (byte-identical parity proven).
- **AC6** — `ProjectCredentialSource` port (empty seam, no impl bean today) injected via `ObjectProvider`, resolved lazily; resolver retains no plaintext field (reflective test).
- **AC7** — resolver + port edits depend only on application ports + `domain.*`; GITLAB stubs reside in `adapters.integration.{ticketsource,repohost}.gitlab`. ArchUnit green.
- **AC8** — `ConnectorKind.GITLAB` + V18 (drop-then-re-add both CHECKs) + `connectorKinds` placeholder; always-on `@Component` stub adapters + `@Primary` on the four vendor adapters keep single-injection consumers unambiguous.
- **AC9** — duplicate-kind fail-fast at construction; full coverage incl. the new `application.project` package added to the per-package 0.80 JaCoCo floor; credential-present path also covered (no secret in logs).
- **Logging** — INFO construction/resolution, WARN unsupported-kind/repo-mismatch/stub-no-op, ERROR duplicate-kind; pinned by ListAppender assertions; no secret ever logged.
- Shared `owner/repo` normalization extracted to `RepositoryRef.normalizeRepositoryUrl` (consumed by both `WorkflowProperties.RepoConfig.repositoryRef()` and the resolver) to avoid duplication.

### File List

**Main (new):**
- `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectConnectorResolver.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectCredentialSource.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/gitlab/GitLabTicketSourceStubAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/gitlab/GitLabRepositoryHostStubAdapter.java`
- `deliveryline-backend/src/main/resources/db/migration/V18__widen_connector_kind_to_gitlab.sql`

**Main (modified):**
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapter.java` (+`connectorKind()`)
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/repohost/RepositoryHostAdapter.java` (+`connectorKind()`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/LinearMockAdapter.java` (+`@Primary`, +`connectorKind()`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/LinearRealAdapter.java` (+`@Primary`, +`connectorKind()`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/github/GitHubMockAdapter.java` (+`@Primary`, +`connectorKind()`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/github/GitHubRealAdapter.java` (+`@Primary`, +`connectorKind()`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ConnectorKind.java` (+`GITLAB`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/repohost/RepositoryRef.java` (+`normalizeRepositoryUrl`)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowProperties.java` (`RepoConfig.repositoryRef()` delegates to shared normalizer)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java` (AC5 pre-clone seam + `resolveExpectedRepositoryRef`)
- `deliveryline-backend/pom.xml` (JaCoCo per-package 0.80 floor adds `org.dradgo.application.project`)

**Test (new):**
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectConnectorResolverTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectConnectorResolverWiringIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/gitlab/GitLabStubAdaptersTest.java`

**Test (modified):**
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (`connectorKinds` += `gitlab`)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceServiceTest.java` (AC5 parity tests)

### Change Log

| Date | Change |
|---|---|
| 2026-06-20 | Story 3c-3 implemented: per-project `ProjectConnectorResolver` over the 3.32/3.33 ports via a new `connectorKind()` accessor + kind→adapter map; GITLAB documented-stub kind (V18 + placeholder); app-level `UNSUPPORTED_CONNECTOR_KIND` throw; project-scoped `LINEAR_GITHUB_REPO_MISMATCH` seam; at-use-time `ProjectCredentialSource` seam (empty until 3c-5). Status → review. |

### Review Findings

_Code review 2026-06-20 (bmad-code-review, 3 adversarial layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor). All 9 ACs verified PASS by the Acceptance Auditor; implementation is parity-preserving and ArchUnit-clean (56/56). 1 decision, 0 patch, 2 defer, 6 dismissed-as-noise._

- [x] [Review][Patch] Harden repo-ref matching in the AC5 guard (decision → harden now) — normalize the requested ref through `normalizeRepositoryUrl`, trim trailing slashes in the shared normalizer, and compare case-insensitively (`equalsIgnoreCase`) in both `assertRepositoryRefMatchesProject` and the `prepareWorkspace` seam. Idempotent on already-bare lowercase refs so today's byte-identical parity holds; removes the false-`LINEAR_GITHUB_REPO_MISMATCH` risk for the URL/`.git`/case/trailing-slash forms 3c-6/3c-7 will feed. **FIXED 2026-06-20:** `RepositoryRef.normalizeRepositoryUrl` trims trailing `/`; resolver + workspace seam normalize the request side and compare with `equalsIgnoreCase`; +3 regression tests (resolver request-normalization & case-insensitive; workspace case-variant parity). `ProjectConnectorResolverTest` 15 / `RepositoryWorkspaceServiceTest` 16 / `GitLabStubAdaptersTest` 6 — all green. [`ProjectConnectorResolver.java`, `RepositoryRef.java`, `RepositoryWorkspaceService.java`] _(sources: blind+edge; was Decision, resolved 2026-06-20)_
- [x] [Review][Defer] AC9 "credential-adjacent code ≥90%" not independently enforced — pom adds `org.dradgo.application.project` only to the per-package **0.80** JaCoCo floor, not a dedicated ≥0.90 limit for the credential-adjacent code. Measured coverage is effectively well above 90% (`ProjectCredentialSource` is a 0-line interface; `resolveConnectorSecret` has empty-path + delegating + no-secret-logging tests), so the AC intent is met in practice. [`pom.xml:516-528`] — deferred, AC-wording vs the standing 0.80 floor; coverage effectively satisfied. _(source: auditor)_
- [x] [Review][Defer] ADR 0012 (per-project connector resolution) not authored — `architecture.md` L390 anticipates `docs/adr/0012-per-project-connector-resolution.md`; it does not exist. Spec open-decision #3 explicitly flagged this **non-blocking** and asked to confirm whether ADRs are batched per-epic. — deferred, PO to confirm ADR cadence. _(source: auditor)_
