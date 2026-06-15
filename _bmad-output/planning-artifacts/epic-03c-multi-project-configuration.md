## Epic 3c: Multi-Project Configuration & Pluggable Connectors

A single local-first operator can configure and govern **multiple projects** from one DeliveryLine instance — each project carrying its own repository binding, *selectable* ticket-source and repository-host connector types, encrypted per-project credentials, and run options (including OpenSpec mode). A first-class `Project` aggregate is introduced; every governed run is scoped to one project. The global configuration that today drives a single pilot repository/connector is inverted into per-project data, migrated transparently to a seeded `default` project so existing single-project flows continue byte-identically. Builds on the vendor-neutral `TicketSourceAdapter` (story 3.32) and `RepositoryHostAdapter` (story 3.33) seams, lifting their single global `kind` selection to a per-project binding resolved at run time.

This epic deliberately reverses two prior MVP non-goals — **multi-project configuration** and **application-level credential encryption** — while keeping multi-user authentication, RBAC, and tenant isolation out of scope. It is **pilot-blocking** (inserted between Epic 3 and Epic 4) and **sequenced after stories 3.32 + 3.33 merge**. Source: `sprint-change-proposal-2026-06-14-multi-project.md`. FRs covered: FR56–FR63.

**Documentation increment (owned inside Epic 3c):** Epic 3c completion requires a **project-configuration walkthrough** doc — add/edit a project, choose connector types, enter credentials safely, test connectivity, set run options — shipped alongside the feature stories (story 3c-12).

**Prerequisites:** Story 3.32 (TicketSourceAdapter abstraction), Story 3.33 (RepositoryHostAdapter abstraction).

### Story List (12 stories)

```
Foundation & Domain
3c-1   Flyway V14 — projects + project_credentials schema + project_id association
3c-2   Project domain aggregate + central registries + drift tests

Connector Resolution & Credential Security
3c-3   Per-project connector resolution over TicketSourceAdapter/RepositoryHostAdapter
3c-4   Credential encryption primitive + key management (security-gated)
3c-5   Encrypted credential store + redaction integration

Config Inversion & Run Association
3c-6   Default-project migration + config-inversion seam
3c-7   Run ↔ Project association across intake + dispatch

Surfaces
3c-8   Project REST API (CRUD + connection test)
3c-9   Projects management UI
3c-10  Doctor + observability for projects

Cross-cutting
3c-11  Foundation-gate widening + test suite extension
3c-12  Project-configuration documentation increment
```

---

### Story 3c-1: Flyway V14 — `projects` + `project_credentials` Schema + `project_id` Association

As a foundation developer,
I want Flyway-managed V14 schema introducing the `projects` and `project_credentials` tables plus a `project_id` association on runs,
So that per-project configuration and encrypted credentials have a durable home and every run can be scoped to a project.

**Acceptance Criteria:**

1. **Given** the V13 head migration, **When** the backend starts, **Then** Flyway applies `V14__create_projects_and_credentials.sql` creating `projects` and `project_credentials` — V14 is the next free version.
2. **Given** the prefix registry (`run_`, `evt_`, … `idm_`), **Then** `projects.id` uses prefix `prj_` and `project_credentials.id` uses prefix `cred_`, both `text` primary keys.
3. **Given** the `projects` table, **Then** it has `name text NOT NULL`, `slug text NOT NULL`, `status text NOT NULL` (CHECK in the `project_status` value set), `repository_url text NULL`, `ticket_source_kind text NOT NULL` (CHECK in `connector_kind`), `repo_host_kind text NOT NULL` (CHECK in `connector_kind`), `openspec_enabled boolean NOT NULL DEFAULT false`, plus `created_at timestamptz NOT NULL DEFAULT now()` and `archived_at timestamptz NULL` per the retention-readiness rule.
4. **Given** uniqueness + naming conventions, **Then** `uq_projects_slug` enforces a unique slug; foreign keys use `fk_`, unique constraints `uq_`, indexes `idx_`, checks `ck_`; all timestamps are `timestamptz`; all enum-likes are `text` + CHECK (never ordinals or Postgres enums).
5. **Given** `project_credentials`, **Then** it stores `project_id text NOT NULL` (FK `fk_project_credentials_projects`), `connector_role text NOT NULL` (CHECK: `ticket_source` | `repo_host`), `ciphertext bytea NOT NULL`, `key_id text NOT NULL`, `algo text NOT NULL` — **no plaintext column ever exists**; `uq_project_credentials_project_role` allows one active secret per role per project.
6. **Given** `workflow_runs`, **Then** a `project_id text NULL` column + `fk_workflow_runs_projects` + `idx_workflow_runs_project_id` are added (nullable now; backfilled to the default project by story 3c-6, after which the application treats it as required).
7. **Given** `integration_links`, **Then** a `project_id text NULL` + FK + index are added so Epic 4's conflict-detection job can resolve the adapter per project.
8. **Given** existing constraints (including the `workflow_runs.current_state` CHECK), **Then** they remain intact — the migration is additive only.
9. **Given** migration replay, **When** Flyway runs twice against the same DB, **Then** the second run is a no-op (no checksum mismatch, no errors).
10. **Given** a deliberately broken V14 in a throwaway branch, **When** the app starts, **Then** startup fails fast with a Flyway validation error (no partial schema).

### Story 3c-2: Project Domain Aggregate + Central Registries + Drift Tests

As a foundation developer,
I want a `Project` domain aggregate plus `ProjectStatus` / `ConnectorKind` registries and prefix registrations, drift-tested against the schema and APIs,
So that project configuration has an authoritative, drift-protected domain model consistent with the rest of the system.

**Acceptance Criteria:**

1. **Given** `domain.project`, **Then** a `Project` aggregate models id, name, slug, status, repository binding, ticket-source kind, repo-host kind, OpenSpec/run options, and timestamps.
2. **Given** the central registries, **Then** `ProjectStatus` (e.g. `active`, `disabled`) and `ConnectorKind` (ticket-source kinds + repo-host kinds) are added as authoritative registries; `prj_` and `cred_` are added to the prefix registry.
3. **Given** the drift-test pattern from story 1.4, **Then** registry drift tests assert the enum values match the V14 DB CHECK constraints, the API schema, and any frontend allowed-value lists — a mismatch fails the test.
4. **Given** new domain error codes (e.g. `PROJECT_NOT_FOUND`, `PROJECT_SLUG_CONFLICT`, `UNSUPPORTED_CONNECTOR_KIND`), **Then** they are registered in the ProblemDetailsCatalog and the registry-api-schema-placeholders manifest (the DomainErrorCode three-sites rule), verified under `-Pfoundation-gate`.
5. **Given** ArchUnit boundaries, **Then** the `Project` aggregate lives in `domain.project`; project application logic lives in `application.project`; no adapter imports leak into the domain.
6. **Given** the foundation gate (story 1.23), **Then** the new registries + prefixes are added to the gate's drift assertions.
7. **Given** tests, **Then** coverage asserts registry authority, prefix correctness, drift against schema/API, and error-code registration completeness.

### Story 3c-3: Per-Project Connector Resolution over TicketSourceAdapter / RepositoryHostAdapter

As a backend developer,
I want a resolver that returns the correctly-typed and correctly-credentialed connectors for a given project,
So that each project can use its own selectable ticket-source and repository-host connector instead of one global connector.

**Acceptance Criteria:**

1. **Given** the `TicketSourceAdapter` (story 3.32) and `RepositoryHostAdapter` (story 3.33) ports, **Then** a `ProjectConnectorResolver` in `application.project` returns, for a `Project`, the adapter matching `project.ticketSourceKind()` and the adapter matching `project.repoHostKind()`.
2. **Given** registered connector kinds (story 3c-2), **Then** resolution is driven by the `ConnectorKind` registry, not the global `deliveryline.integration.ticket-source.kind` / `repo-host.kind` keys — those globals remain only as the default project's seed values (story 3c-6).
3. **Given** an unknown or unregistered kind on a project, **Then** the resolver raises `UNSUPPORTED_CONNECTOR_KIND` at the application layer (not relying solely on the DB CHECK), surfaced as Problem Details.
4. **Given** capability detection (story 3.32 AC3 / 3.33 AC3), **Then** the resolved adapter's `getCapabilities()` is honored exactly as today — e.g. completion sync still checks `supportsCommentOnTicket` before posting; no change to the 3.32/3.33 degradation contract.
5. **Given** the existing global `LINEAR_GITHUB_REPO_MISMATCH` guard (NFR20, story 3.14 AC4), **Then** it becomes project-scoped: `prepareWorkspace` validates the requested `repositoryRef` against the run's Project repo binding before any clone.
6. **Given** a resolved adapter needs credentials, **Then** the resolver obtains the per-project decrypted secret via the credential store (story 3c-5) at use time only — adapters never hold long-lived plaintext.
7. **Given** ArchUnit boundaries, **Then** `ProjectConnectorResolver` depends only on the abstract ports, never on concrete vendor adapters (preserving the 3.32/3.33 leak-detection rules).
8. **Given** the scope cap, **Then** beyond `linear`/`github` exactly one additional kind (a real implementation or a documented stub) is registered to prove the seam; full additional vendor implementations remain post-pilot.
9. **Given** tests, **Then** coverage asserts kind→adapter resolution for each registered kind, `UNSUPPORTED_CONNECTOR_KIND` on a bad kind, capability-driven degradation parity, and project-scoped mismatch rejection.

### Story 3c-4: Credential Encryption Primitive + Key Management (Security-Gated)

As a security-conscious backend developer,
I want an application-level encryption primitive for connector credentials with host-environment key management,
So that per-project secrets can be stored encrypted at rest without reintroducing hosted-grade key infrastructure.

**Acceptance Criteria:**

1. **Given** `infrastructure.crypto`, **Then** a `CredentialCipher` performs envelope encryption: a data key encrypts the secret; the data key is wrapped by a master key resolved from a host env var (e.g. `DELIVERYLINE_MASTER_KEY`) that is **never** persisted to the DB or any file.
2. **Given** the cipher API, **Then** `encrypt(plaintext) → (ciphertext, keyId, algo)` and `decrypt(ciphertext, keyId, algo) → plaintext`; `algo` records the cipher suite (e.g. `AES-256-GCM`) so future rotation is non-breaking.
3. **Given** a missing/blank master key at startup with at least one project credential present, **Then** the app fails fast with `CREDENTIAL_MASTER_KEY_UNCONFIGURED` (it does not silently start with unreadable secrets); with no credentials present it boots (greenfield/test parity).
4. **Given** key rotation, **Then** a `keyId` indirection + a documented re-wrap path exists (rotate master key → re-wrap data keys); rotation mechanics may be a stub command, but the schema/`keyId` must support rotation without migration.
5. **Given** `docs/adr/0013-credential-encryption.md`, **Then** it records the deliberate reversal of the "no app-level encryption" non-goal, the threat model (defends at-rest DB compromise, not host-environment compromise), and the single-operator/no-RBAC posture.
6. **Given** the security-review gate, **Then** this story does not close (and story 3c-5 does not start) until a security review signs off the primitive + threat model.
7. **Given** tests, **Then** coverage asserts round-trip encrypt/decrypt, tamper detection (GCM auth-tag failure → error, never silent plaintext), wrong-`keyId` rejection, and fail-fast on missing master key.

### Story 3c-5: Encrypted Credential Store + Redaction Integration

As a backend developer,
I want a write-only credential store wired to the cipher and integrated with redaction,
So that per-project connector secrets persist encrypted and can never leak through logs, events, artifacts, or exports.

**Acceptance Criteria:**

1. **Given** `application.project` credential operations, **Then** `setCredential(projectId, role, plaintext)` encrypts via `CredentialCipher` and persists to `project_credentials`; `getDecrypted(projectId, role)` returns plaintext in memory only, never logged.
2. **Given** the write-only contract, **Then** no API, service, or serialization path ever returns a stored secret (plaintext or ciphertext) to a client — credentials are set/rotated, never read back.
3. **Given** `RedactionPolicyService`, **Then** it is extended so any project credential value is redacted from logs, events, artifacts, and exports; the AR10 adversarial-secret fixture set gains project-credential fixtures.
4. **Given** the export-redaction gate (epic-05 / story 5.3 AC7), **Then** `project_credentials` is on the export deny-list; a regression fixture proves an exported run carrying a credentialed project emits no secret material.
5. **Given** the one-active-secret-per-role constraint (story 3c-1 `uq_project_credentials_project_role`), **Then** `setCredential` replaces (re-encrypts) the existing row idempotently; rotation appends a new `keyId` without exposing the old plaintext.
6. **Given** the foundation gate (story 1.23), **Then** the credential-redaction assertion is added so a leak fails the gate.
7. **Given** tests, **Then** coverage asserts set→decrypt round-trip via the store, redaction across all egress channels (log/event/artifact/export), write-only enforcement (no read-back path), and replace-on-reset.

### Story 3c-6: Default-Project Migration + Config-Inversion Seam

As a backend developer,
I want today's global configuration migrated into a seeded `default` project and run-time config resolved per project,
So that the global→per-project inversion does not break any existing single-project flow.

**Acceptance Criteria:**

1. **Given** today's global config (`deliveryline.workflow.repos.*`, `deliveryline.linear.*`, `deliveryline.github.*`, `deliveryline.runner.openspec.enabled`), **When** the backend starts and no project exists, **Then** a single `default` Project is seeded from those values (repo URL, ticket-source kind `linear`, repo-host kind `github`, OpenSpec flag); its credentials reference the existing global env-var secrets.
2. **Given** V14 added `workflow_runs.project_id` as nullable, **Then** the seed backfills all existing rows to the default project, after which the application treats `project_id` as required (run creation without a resolved project is rejected).
3. **Given** run-time config resolution, **Then** repository/connector/OpenSpec settings are read from the run's Project with fallback to the default project; the global `@ConfigurationProperties` records remain bound (normalize-never-throw) only as the default project's seed source and are no longer read on the per-run hot path.
4. **Given** a single-project deployment setting only the old global keys, **Then** behavior is byte-identical to pre-3c (one default project, transparently); parity tests assert no behavioral drift.
5. **Given** idempotent seeding, **When** the app restarts, **Then** the default project is not duplicated (seed keyed on the reserved `default` slug).
6. **Given** the OpenSpec opt-in, **Then** `openspec_enabled` now lives on the Project; the global `deliveryline.runner.openspec.enabled` becomes the default project's seed value — flag-off remains byte-identical (preserving the 2026-06-13 OpenSpec proposal's default-off guarantee).
7. **Given** tests, **Then** coverage asserts seed-from-global-config, backfill of existing runs, default fallback, no-duplicate-on-restart, and full single-project behavioral parity.

### Story 3c-7: Run ↔ Project Association across Intake + Dispatch

As a backend developer,
I want every run-creation and dispatch path to resolve and carry a project,
So that a run's repository, connectors, and run options always derive from its project rather than global config.

**Acceptance Criteria:**

1. **Given** CLI `submit` and the REST intake path, **Then** both resolve and bind a `project_id` for the new run (explicit project reference, or resolution by ticket-source binding); creation without a resolvable project is rejected with a clear domain error.
2. **Given** the runner bundle composition, **Then** repository content, connector context, and OpenSpec mode are derived from the run's Project (not global config).
3. **Given** workspace preparation, **Then** the clone target + repo-host operations use the project's resolved repository binding + connector (story 3c-3), with the project-scoped `LINEAR_GITHUB_REPO_MISMATCH` guard applied.
4. **Given** Linear completion sync (story 3.16), **Then** it resolves the ticket-source adapter via the run's Project and honors that adapter's capabilities.
5. **Given** the queue, **Then** queued runs carry their project so workers dispatch with the correct per-project configuration.
6. **Given** audit history, **Then** the run's project is recorded so FR63 (which project a run/ticket/artifact belongs to) is satisfiable.
7. **Given** tests, **Then** integration coverage asserts a run created against a non-default project dispatches with that project's repo/connector/OpenSpec settings, and a default-project run remains byte-identical to pre-3c behavior.

### Story 3c-8: Project REST API (CRUD + Connection Test)

As an operator,
I want REST endpoints to manage projects and verify their connectivity,
So that the UI (and scripts) can create, edit, disable, credential, and test projects through a governed contract.

**Acceptance Criteria:**

1. **Given** the REST layer, **Then** endpoints exist to list, create, read, update, and disable projects, returning project configuration but never credential values.
2. **Given** credential management, **Then** a write-only set/rotate endpoint accepts a credential per connector role and never returns it; the response conveys only "configured" status.
3. **Given** a `testConnection` endpoint, **Then** it runs capability-aware checks (repository reachable, ticket-source auth, repository-host auth) and returns per-check results without persisting secrets to logs or history.
4. **Given** error handling, **Then** Problem Details responses use the registered domain error codes (`PROJECT_NOT_FOUND`, `PROJECT_SLUG_CONFLICT`, `UNSUPPORTED_CONNECTOR_KIND`, credential/connection errors).
5. **Given** idempotency (story 1.9), **Then** create + credential-set are idempotent under the shared idempotency contract.
6. **Given** the allowed-actions model, **Then** available project actions are backend-reported (not frontend-inferred), consistent with the rest of the system.
7. **Given** localhost-only binding (architecture security posture), **Then** the project endpoints follow the same local-only REST rules as existing endpoints.
8. **Given** the OpenAPI snapshot + drift tests, **Then** the new endpoints + schemas are reflected (regenerated byte-identically) and pass the contract tests.
9. **Given** tests, **Then** coverage asserts CRUD behavior, write-only credential handling (no read-back), connection-test result shape, error mapping, and idempotency.

### Story 3c-9: Projects Management UI

As an operator,
I want a Projects management screen,
So that I can configure, credential, and test projects without editing config files.

**Acceptance Criteria:**

1. **Given** the React app, **Then** a "Projects" navigation landmark opens a settings/configuration area distinct from the queue and run views (per the UX navigation rule).
2. **Given** the project list, **Then** it shows name, status, ticket-source kind, repository-host kind, repository reference, and last connection-test result + timestamp.
3. **Given** the create/edit form, **Then** it captures display name, slug, repository URL, ticket-source kind picker, repository-host kind picker, run options (OpenSpec toggle and other per-project toggles), with field-level validation.
4. **Given** credential fields, **Then** each connector role renders as "configured / not configured" with a write-only set/replace affordance; the stored value is never displayed, never placed in the DOM, and never exposed to assistive tech.
5. **Given** the connection-test control, **Then** it shows per-check results (repository reachable, ticket-source auth, repository-host auth); progress + results are announced via a live region and pass/fail is not conveyed by color alone.
6. **Given** the project selector, **Then** the queue can be scoped/filtered by project and each run shows its project attribution; when only the default project exists, the selector collapses to a static label (no selection friction).
7. **Given** backend-reported allowed actions, **Then** create/edit/disable/set-credential controls reflect allowed actions rather than frontend inference.
8. **Given** WCAG 2.1 AA, **Then** all forms are keyboard operable with explicit labels + error identification; an axe scan reports zero `wcag2aa` violations.
9. **Given** the design system, **Then** the UI reuses the Epic 2 shadcn/ui + tokens foundation (no new component primitives required).
10. **Given** tests, **Then** Vitest + Testing Library cover the list, form, credential write-only behavior, connection-test states, and the project selector; an axe scan runs on each.

### Story 3c-10: Doctor + Observability for Projects

As an operator,
I want the doctor command and local observability to report per-project health,
So that I can confirm projects are correctly configured and connectable before running governed work.

**Acceptance Criteria:**

1. **Given** the doctor service (story 1.16), **Then** it lists configured projects with their status, connector kinds, and last connection-test outcome.
2. **Given** credential presence, **Then** doctor reports per-project credential presence (PASS/WARN) without ever printing secret values.
3. **Given** an unconfigured or unreachable project, **Then** doctor reports a WARN with the specific failing check and the safe next action.
4. **Given** the doctor probe fan-out rule, **Then** any new probe is stubbed everywhere the full probe set runs and the hard-coded `checksRun` count is bumped (per the doctor-probe house rule).
5. **Given** observability (when the profile is active), **Then** per-project health is surfaced consistently with existing local observability conventions.
6. **Given** tests, **Then** coverage asserts the project listing, credential-presence reporting (no secret leakage), WARN-on-unreachable behavior, and the updated probe count.

### Story 3c-11: Foundation-Gate Widening + Test Suite Extension

As a backend + frontend developer,
I want the foundation gate and test suites extended to cover Epic 3c,
So that multi-project regressions are caught at the same CI gates as the rest of the system.

**Acceptance Criteria:**

1. **Given** the foundation gate (story 1.23), **Then** it asserts: the `project_status` / `connector_kind` registries are authoritative + drift-tested, `prj_`/`cred_` prefixes are registered, credential redaction holds, the project entity drift test passes, and per-project connector resolution works.
2. **Given** the config-inversion seam (story 3c-6), **Then** an integration test asserts single-project behavioral parity (default-project run byte-identical to pre-3c) and a per-project dispatch test asserts a non-default project uses its own repo/connector/OpenSpec settings.
3. **Given** the credential subsystem, **Then** a test asserts credentials never appear in logs, events, artifacts, or exports (extends the AR10 redaction regression).
4. **Given** the frontend (story 3c-9), **Then** Vitest + Playwright + axe coverage is extended for the Projects area (list, form, credential write-only, connection test, selector) under the existing CI tiers.
5. **Given** coverage thresholds (story 2.27 AC10 pattern), **Then** thresholds are extended to the new packages (`application.project`, `infrastructure.crypto`, project adapters) — minimum 80% line coverage; credential/sanitization-related code 90%.
6. **Given** the security-gated primitive (story 3c-4), **Then** the test suite includes the cipher tamper/round-trip/fail-fast assertions and the security-review sign-off is recorded.
7. **Given** the gate, **Then** "Epic 3c backend + frontend suites green" is required for foundation-gate PRs.

### Story 3c-12: Project-Configuration Documentation Increment

As an operator joining the pilot,
I want a project-configuration walkthrough,
So that I can configure and verify a project unaided on my first use.

**Acceptance Criteria:**

1. **Given** `docs/project-configuration-walkthrough.md`, **Then** it follows a linear sequence: open the Projects area → create/edit a project → set repository + connector kinds + run options → set credentials safely (write-only) → run the connection test → activate → scope a run to the project.
2. **Given** the credential-safety guidance, **Then** the doc explains that credentials are write-only, encrypted at rest, and never displayed/exported, and how the master key is supplied via the host environment.
3. **Given** the connection-test section, **Then** it explains each check (repository reachable, ticket-source auth, repository-host auth) and how to fix a named failing check.
4. **Given** the default project, **Then** the doc explains that existing single-project setups migrate transparently and require no action.
5. **Given** the glossary (story 1.22 AC10) and NFR43, **Then** new concepts (`project`, `connector`, `credential`) are added to `docs/glossary.md`; no concept is introduced without a glossary entry.
6. **Given** the link-check CI step, **Then** all internal links resolve; the doc is visible from `docs/index.md`.
7. **Given** cross-platform usability, **Then** the walkthrough is browser-based with no OS-specific instructions.
8. **Given** the epic doc-increment rule, **Then** Epic 3c cannot close without this walkthrough merged + a named human validator placeholder included.
