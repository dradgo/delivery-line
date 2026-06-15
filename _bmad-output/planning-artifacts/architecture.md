---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\prd.md
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\implementation-spec-2026-04-20-agent-orchestration.md
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\implementation-readiness-report-2026-04-23.md
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\brainstorming\brainstorming-session-2026-04-18-210302.md
workflowType: 'architecture'
project_name: 'ai-hackaton-1'
user_name: 'Alex'
date: '2026-04-23'
lastStep: 8
status: 'complete'
completedAt: '2026-04-23'
documentCounts:
  productBriefs: 0
  prd: 1
  uxDesign: 0
  research: 0
  projectDocs: 0
  implementationSpecs: 1
  readinessReports: 1
  brainstorming: 1
projectContextRules: 0
---

# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
The product requires one governed low-risk ticket workflow that moves work from ticket intake through specification review, implementation output, developer review or takeover, visible run history, recovery, and merge-ready handoff. The architecture must treat workflow instances, explicit states, approval checkpoints, artifacts, audit events, integration links, runner execution records, and context bundles as first-class domain concepts.

**Non-Functional Requirements:**
The NFRs establish the MVP trust floor: durable state, append-only recovery actions, interruption survival, secret redaction, local/shareable data separation, inspectable failures, and modest local read performance.

### First-Principles Architectural Foundation

The core system is not an agent chat interface, a generic workflow builder, or an integration dashboard. It is a durable decision and execution record for one governed delivery workflow.

The irreducible domain model is: ticket, run, state transition, event, artifact, approval decision, runner execution, context bundle, failure, and recovery action. Linear, GitHub, Codex, Claude, and the review UI are adapters around that center.

### Initial Architecture Decision Fronts

- **Authoritative lifecycle:** Internal workflow run state and append-only event history should be the source of truth for governed execution. Linear and GitHub provide linked external references and sync targets, not authoritative run lifecycle ownership.
- **Local persistence:** The MVP needs a simple durable local persistence model for workflow instances, events, approvals, runner executions, integration links, and artifact metadata.
- **Artifact storage:** Artifact metadata should be queryable with the run record, while artifact payloads may be stored as local files or Git-visible files for review and demo transparency.
- **Event semantics:** State changes, approvals, runner results, retries, takeovers, failures, and reconciliations should be represented as append-only events.
- **Runner abstraction:** Agent providers should be integrated through a normalized runner contract so workflow logic does not depend on provider-specific output shapes.
- **Integration conflict policy:** External state disagreements should pause workflow progression and require explicit human reconciliation.
- **Data sharing boundary:** The architecture must distinguish private local execution data from redacted, shareable review artifacts.

### Failure-Driven Architecture Implications

Failure handling is part of the product's primary value proposition. Agent execution, integrations, artifact generation, approvals, local persistence, and human handoff can each fail independently.

Each state-changing component should emit structured failure events, preserve the last safe state, expose the next safe action, and avoid silent repair when identity or state is ambiguous.

### Trust Hardening Implications

Approval decisions should be bound to specific artifact versions, context bundle versions, workflow states, and actor identities. Retry and resume actions should record what input, context, or configuration changed before continuation.

External references need stable identity rules. Linear imports should be idempotent by ticket and repository context. GitHub or PR linkage should preserve branch, commit, artifact, and run relationships.

### Architecture Option Evaluation Biases

The requirements point toward a conservative MVP architecture: explicit state machine, simple local durable persistence, local file-backed artifact payloads, queryable metadata, normalized runner contract, polling-based Linear intake, reference-based GitHub linkage, and a thin review UI.

Architecture options should be evaluated primarily on inspectability, state correctness, recovery clarity, local operability, and implementation simplicity.

### Contract Clarity Needed Before Implementation

The architecture should convert its core principles into machine-checkable contracts before implementation begins. The highest-priority contracts are:

- **MVP scope and non-goals:** The first release supports one low-risk `feature-delivery` workflow only. It excludes arbitrary workflow authoring, multi-ticket orchestration, broad dashboards, enterprise RBAC, policy engines, cross-project dependency management, and fully automated merge/release orchestration.
- **Executable state model:** The architecture must define canonical states, terminal states, allowed transitions, invalid-transition behavior, and retry/resume/takeover/reconciliation entry points.
- **Approval checkpoints:** Each approval must bind to a specific artifact version, context bundle version, workflow state, actor identity, reviewer role, decision, reason, and invalidation rule if later artifacts change.
- **Event model:** Append-only events should have required payload fields, ordering/idempotency rules, and enough data to reconstruct the visible run state through deterministic replay.
- **Artifact lineage:** Artifacts should not be overwritten in place. Each meaningful change creates a new version with parent ancestry, producing actor, input/context reference, reason for change, linked event, and share/redaction classification.
- **Runner contract:** Codex and Claude adapters should satisfy a common contract for execution status, normalized output, artifact references, raw diagnostic metadata, timeout/cancellation behavior, heartbeat/progress, and failure normalization. A deterministic mock runner should exist for tests.
- **Data classification:** Local and exported data should be classified as `local-only`, `shareable-redacted`, `shareable-full`, or `derived-public-safe`, with explicit handling for logs, raw runner output, context bundles, and integration sync.
- **Integration conflict policy:** Linear, GitHub, local state, and human edits need source-of-truth rules per field, idempotency keys, partial-sync behavior, and a clear "manual reconciliation required" outcome when identity or state is ambiguous.
- **Recovery semantics:** Retry, resume, takeover, pause, and reconciliation must be distinct actions with persisted evidence, success criteria, and expected final state.
- **Observability contract:** The inspection view should always expose current owner or actor, current state, current blocking reason, last successful step, latest artifact version per stage, last external sync status, failure reason, and next safe action.

The UX bar for the MVP is: a pilot user should be able to open a local run later, understand what happened, who owns it now, what artifact/version is under decision, and what the next safe action is in under a minute.

### Scale & Complexity

- Primary domain: local-first full-stack developer workflow control plane
- Complexity level: medium-high
- Estimated architectural components: workflow engine, event store, artifact service, approval service, runner adapter layer, context bundle builder, Linear adapter, GitHub/repo integration, review UI, export/redaction layer, recovery/reconciliation service

### Cross-Cutting Concerns Identified

- Explicit workflow state machine and transition validation
- Append-only workflow event history
- Artifact lineage and versioning
- Human approval and rejection boundaries
- Runner abstraction with normalized outputs and preserved diagnostics
- Context bundle generation, versioning, inspection, and redaction
- Credential isolation and secret redaction
- Local-private versus shareable-export data separation
- Integration identity and conflict detection
- Recovery actions: retry, rerun, resume, takeover, pause, reconciliation
- Failure taxonomy governance
- Inspection performance for pilot-sized run histories

## Starter Template Evaluation

### Primary Technology Domain

CLI-first Spring Boot backend, with React added later as a separate frontend surface.

The MVP should start as a Spring Boot application that exposes CLI commands for workflow operation, REST APIs for future React/thin review UI, PostgreSQL-backed durable workflow state, file-backed artifact payloads, and Docker Compose support for PostgreSQL plus agent-runner containers.

React should not drive the MVP starter. It becomes a later Vite React project when the review UI is ready.

### Starter Options Considered

**Option 1: Spring Initializr + Spring Boot CLI**
Best fit for the MVP. It gives a conservative backend foundation with Spring Web, PostgreSQL, JPA, Flyway, validation, Actuator, Docker Compose support, and Testcontainers.

**Option 2: Spring Initializr + Spring Shell**
Good fit for a CLI-first workflow tool because it supports command definitions, help, parsing, validation, interactive and non-interactive operation. Use it as the CLI adapter if compatible with the selected Spring Boot version.

**Option 3: Spring Boot + Picocli**
Viable fallback if the CLI needs stronger Unix-style subcommands, exit-code handling, or packaging behavior than Spring Shell provides.

**Option 4: Vite React**
Right later frontend starter. Current Vite docs use `npm create vite@latest` and require Node.js `20.19+` or `22.12+`. This should wait until review UI implementation.

### Selected Starter: Spring Initializr Spring Boot Backend With Spring Shell CLI Adapter

**Rationale for Selection:**

The MVP's core risk is workflow correctness, recovery, auditability, and integration behavior, not frontend richness. Spring Boot gives a stable backend foundation for explicit state machines, durable persistence, REST APIs, validation, integration adapters, and testable service boundaries.

Spring Shell fits the CLI-first direction while staying in the Spring programming model. It must remain an adapter, not the architectural center. CLI commands should call application services exactly the same way future REST controllers will.

PostgreSQL plus file storage matches the durable-state plus artifact-payload model. Docker Compose support matters because the MVP needs local PostgreSQL and agent-runner containers. Testcontainers matters because workflow engine, repository, and integration boundaries need repeatable tests against real service dependencies.

### CLI Framework Trade-Off

The MVP should use Spring Shell as the CLI adapter if available and compatible with the selected Spring Boot version. Spring Shell fits the CLI-first product direction while staying inside the Spring programming model.

However, Spring Shell must not become the architectural center. CLI commands should be thin adapters over application services. The workflow engine, event store, artifact service, runner broker, approval service, and recovery service should remain independent of the command framework.

This preserves a clean path to the later React review UI: future REST controllers can call the same application services that the CLI uses.

Picocli remains a fallback if Spring Shell proves incompatible with the selected Spring Boot version or if the CLI needs stronger Unix-style subcommands, exit-code handling, or packaging behavior than Spring Shell provides.

Plain `CommandLineRunner` is acceptable only for early bootstrap commands, not as the long-term CLI surface.

### Starter-Level Failure Considerations

PostgreSQL should be treated as the durable transactional record for workflow state, events, approvals, runner executions, artifact metadata, and integration links. File storage should hold artifact payloads and must be referenced by stable relative paths or content-addressed identifiers, not machine-specific absolute paths.

Any state transition that depends on artifact creation must record enough metadata to detect missing, stale, or corrupted files, including artifact version, checksum or content hash, storage location, classification, producing actor, and linked event.

Dockerized agent runners should be modeled as external executors. The runner contract must include lifecycle state, timeout, heartbeat or last activity, normalized result, raw diagnostic reference, and failure category. Runner containers should not be treated as Spring-managed data services even if they are present in the same Docker Compose environment.

The implementation should include a deterministic mock runner for workflow tests and reserve real containerized runner tests for adapter or contract-level validation.

### Boundary and Testability Guardrails

The starter choice requires explicit guardrails so the CLI-first MVP does not become CLI-locked.

**Adapter boundary:**
Spring Shell is a transport adapter only. Shell commands may parse input, format output, and call command handlers or application services. They must not contain workflow orchestration, persistence, runner dispatch, approval, recovery, or artifact-lineage business rules.

The core application services should expose narrow use cases such as `submit`, `advance`, `approve`, `reject`, `recover`, `replay`, and `artifact lookup`. Future REST controllers should call the same use cases as the CLI.

**Package/module rule:**
The codebase should keep adapters, application services, domain model, persistence, runner infrastructure, and file storage in separate packages or modules. Tests should enforce that business rules remain outside the shell adapter.

**DB/file consistency strategy:**
PostgreSQL remains the source of truth for workflow state and artifact metadata. Artifact files are payload storage. Artifact writes should be idempotent and referenced by stable relative paths or content-addressed identifiers. Recovery should be able to detect and repair DB/file drift, including missing payload files, stale checksums, and interrupted writes.

**Idempotency requirements:**
Command execution, workflow advancement, runner submission, approval decisions, artifact creation, and recovery replay should use idempotency keys or equivalent safeguards so duplicate commands or retries cannot corrupt run history.

**Runner testing strategy:**
A deterministic mock runner is required for workflow and recovery tests. Real Dockerized runner containers should be covered by a separate contract/integration suite that verifies lifecycle, timeout, heartbeat, malformed output, duplicate result, and failure-normalization behavior.

**Observability baseline:**
CLI-first operation must still emit enough structured information to diagnose runs: correlation IDs, workflow/run IDs, event IDs, artifact IDs, runner execution IDs, command outcome status, and failure category where applicable.

### Initialization Command

```bash
spring init deliveryline \
  --type=maven-project \
  --language=java \
  --java-version=21 \
  --boot-version=4.0.6 \
  --packaging=jar \
  --group-id=org.dradgo \
  --artifact-id=deliveryline \
  --name=deliveryline \
  --description="Governed local-first agent delivery workflow" \
  --dependencies=web,data-jpa,postgresql,flyway,validation,actuator,docker-compose,testcontainers
```

**Spring Boot version pin:** Spring Boot **4.0.6** is the locked baseline. Initializr's current default tracks 4.x; specifying `--boot-version=4.0.6` (or its `bootVersion=4.0.6` query parameter on `start.spring.io/starter.zip`) makes the scaffold reproducible. Note that Spring Boot 4.x renames a few starters relative to 3.x — most visibly `spring-boot-starter-web` → `spring-boot-starter-webmvc` — and ships per-starter `-test` variants (`spring-boot-starter-actuator-test`, `spring-boot-starter-flyway-test`, etc.). Maven **3.9.4+** is required (Boot 4 baseline).

After generation, verify whether Spring Shell is available from Initializr:

```bash
spring init --list | findstr /i shell
```

If available, include it during generation. If not, add `org.springframework.shell:spring-shell-starter` manually with the **Spring Shell 4.0.2 BOM** (`spring-shell-dependencies:4.0.2`, the first stable Spring Shell release on the Boot 4 line — released 2026-04-24).

Later React UI:

```bash
npm create vite@latest deliveryline-ui -- --template react-ts
```

### Architectural Decisions Provided by Starter

**Language & Runtime:** Java 21, Spring Boot backend, Maven build, executable jar packaging.

**Application Shape:** CLI-first backend application with REST API support for later React review UI.

**Persistence:** PostgreSQL for durable workflow records, events, approvals, runner executions, integration links, and artifact metadata.

**Artifact Storage:** Local filesystem for artifact payloads. PostgreSQL stores metadata, versioning, lineage, classification, and references.

**Database Migration:** Flyway manages schema evolution.

**Docker Foundation:** Docker Compose is used for PostgreSQL and local agent-runner containers. Spring Boot Docker Compose support can manage PostgreSQL service connections during development. Agent containers remain external runner infrastructure.

**Testing Framework:** Spring Boot test stack plus Testcontainers for PostgreSQL-backed integration tests. A deterministic mock runner is required for workflow and recovery tests.

**API & Validation:** Spring Web provides REST endpoints for future review UI and local automation. Bean Validation supports command/API input validation.

**Operational Visibility:** Actuator provides local health and operational endpoints. Product-level observability comes from workflow events and run inspection APIs.

**Frontend Path:** React should be added later using Vite React TypeScript and consume the Spring Boot REST API.

**Note:** Project initialization using this command should be the first implementation story.

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**

- Data architecture: state table with append-only audit log, transactional artifact operations, explicit relational model, layered validation, Flyway SQL migrations, and no application cache.
- Security posture: local trusted user for MVP, strict local config handling, audit role labels only, capture/export redaction, localhost-only REST, and no app-level encryption.
- API and communication: dual CLI and REST adapters from day one, shared application command models, hybrid REST, Problem Details, mandatory idempotency keys, OpenAPI, and file-based runner contracts.
- Frontend architecture: minimal React review UI in MVP, bundled into Spring Boot, Maven-driven Vite build, TanStack Query, TanStack Router, shadcn/ui, and Tailwind.
- Infrastructure: local-only MVP, executable Spring Boot jar, PostgreSQL and runner images through Docker Compose, GitHub Actions CI, optional observability, and operational diagnostics.

**Important Decisions (Shape Architecture):**

- State transitions must pass through a single transition service.
- Artifact files become approval-eligible only through validated artifact operations.
- CLI, REST, and future UI must remain adapters over the same application use cases.
- Runner containers are external executors controlled by the runner broker.
- React UI controls are backend-reported allowed actions, not frontend-inferred workflow permissions.
- Local observability is profile-gated and optional.

**Deferred Decisions (Post-MVP):**

- Hosted deployment, multi-user authentication, real RBAC, tenant isolation, OS keychain support, queue-based runner orchestration, HTTP runner services, app Docker image, workflow builder, analytics dashboard, policy editor, and production monitoring.
- **Scope amendment (2026-06-14, Epic 3c):** multi-project *configuration* and *application-level credential encryption* are pulled into MVP scope. A single instance can be configured with multiple projects — each with its own repository, pluggable ticket-source / repository-host connectors, encrypted credentials, and run options — and per-project connector secrets are encrypted at rest. This does **not** introduce multi-user authentication, RBAC, or tenant isolation: projects are configuration records operated by a single local operator, not access-control tenants. See the Multi-Project Configuration & Connector Pluggability decision below and `docs/adr/0011`–`0013`.

### Data Architecture

**Decision:** Use PostgreSQL with explicit relational tables, operational state rows, append-only audit events, transactional artifact-operation records, layered validation, Flyway SQL migrations, and no application cache for MVP.

**Specific Choices:**

- **Run state model:** `workflow_runs.current_state` is the operational source for fast reads and command decisions. `workflow_events` is immutable audit history.
- **Artifact atomicity:** Use transactional outbox-style artifact operations. PostgreSQL records artifact intent, event, and operation state before file payload processing. Artifact operations may be processed synchronously in MVP, but the persisted operation record must exist.
- **Data model:** Use explicit relational tables for core concepts: `workflow_runs`, `workflow_events`, `artifacts`, `artifact_operations`, `approvals`, `runner_executions`, `integration_links`, and `recovery_actions`. Epic 3c adds `projects` and `project_credentials` (Flyway V14) and a `project_id` foreign key on `workflow_runs` (and `integration_links`) so every run is scoped to one configured project. JSON is allowed only for bounded metadata, not as the primary domain model.
- **Validation:** Use layered validation: Bean Validation at CLI/API boundaries, application/domain services for workflow invariants, and PostgreSQL constraints for identity, uniqueness, foreign keys, and lifecycle fields.
- **Migrations:** Use Flyway versioned SQL migrations only. Hibernate auto-DDL is not a schema source of truth.
- **Caching:** No application cache for MVP. Use indexed PostgreSQL queries and deliberate summary columns where needed.

**Rationale:**
The MVP must optimize for inspectability, recovery, and implementation clarity. A relational schema gives first-class identities and constraints for runs, events, artifacts, approvals, runner executions, integration links, and recovery actions. A state table gives simple operational reads and command decisions. An append-only audit log preserves history without requiring full event sourcing in the first release.

Artifact payloads live outside the database, so artifact writes require persisted operation records to avoid silent DB/file drift. Flyway SQL migrations keep the schema explicit and reviewable. Avoiding application caching reduces stale-state risk while the product is still proving correctness.

**Alternatives Considered:**

- Full event sourcing: stronger theoretical audit model, but too much implementation complexity for the first MVP.
- JSON-heavy model: faster schema evolution, but weaker validation, harder querying, and poorer story clarity.
- Hibernate auto-DDL: convenient early, but inappropriate as schema source of truth for audit-heavy data.
- Application cache: unnecessary for pilot-size read targets and risky for workflow correctness.

**Data Decision Consistency Rules:**

- **State/event atomicity:** Any workflow state transition must update the operational run state and append the corresponding audit event in the same PostgreSQL transaction. A run state without a matching event, or an event without the intended state update, is data drift requiring reconciliation.
- **Artifact availability gating:** Workflow transitions that depend on generated artifacts must gate on artifact operation status. Artifact metadata alone is not sufficient; the artifact payload must be `available` with a verified storage reference and checksum before approval or downstream runner execution can proceed.

**Data Architecture Guardrails:**

- All workflow state changes must go through a single transition service. Direct state mutation outside that service is prohibited.
- The transition service owns the transaction that updates `workflow_runs.current_state` and appends the matching `workflow_events` row.
- Artifact operations must have both an execution path and a reconciliation path. Recording an operation without a way to complete, retry, fail, or reconcile it is not sufficient. When partial failure, replay, or stale callback makes artifact lineage ambiguous, the system must fail closed and require explicit reconciliation; it must not silently create a new lineage or attach output to a guessed parent.
- Workflow invariants should be documented and enforced primarily in application/domain services. Database constraints backstop identity, uniqueness, foreign keys, and lifecycle fields.
- Inspection read paths should rely on indexed queries and deliberate summary columns where needed, not application caching.

**Data Failure Handling Expectations:**

- State update and event append must commit or roll back together.
- Artifact metadata without an available verified payload is not eligible for approval or downstream runner execution.
- Orphan artifact payloads should be detected by reconciliation and either attached to a valid artifact record or quarantined.
- Duplicate commands, runner submissions, artifact operations, approvals, and recovery actions must be protected by idempotency keys or unique constraints.
- Late runner results after timeout must be recorded as late or stale and must not silently advance workflow state.
- Pending or interrupted artifact operations must be recoverable through explicit retry, fail, or reconcile actions.
- Failed Flyway migrations block startup rather than allowing uncertain schema state.

**Data Architecture Quality Gates:**

- `workflow_runs.current_state` is the operational source for command decisions. `workflow_events` is immutable audit history.
- Contract tests must prove illegal transitions, duplicate transitions, replayed requests, out-of-order events, and conflicting concurrent updates cannot corrupt run state.
- Artifact operations should have unique idempotency protection, such as `idempotency_key + operation_type + artifact_id`, and a defined retry outcome matrix.
- Late runner results must be persisted and correlated to the originating runner execution, artifact expectation, and context version. They must not advance state unless explicit recovery accepts them.
- If summary columns are used for inspection read paths, the transition service owns their mutation. Tests must prove summary fields stay consistent across every transition and recovery path.
- Database constraints enforce integrity; domain validation enforces invariants; application-service validation enforces workflow rules and command preconditions.
- Migration verification should cover forward migration and compatibility with existing pilot data. Failed migrations block startup.
- Tests must cover interrupted artifact operations, missing payloads, duplicate commands, duplicate approvals, stale runner callbacks, retry with the same idempotency key, and reconciliation that repairs missed work without double-applying successful work.

### Authentication & Security

**Decision:** Use a local-first, single-operator MVP security model.

**Specific Choices:**

- **Authentication:** Local trusted user only. No login/session system in MVP.
- **Credential storage:** Local config file with strict permissions plus environment variable override for the single-project / instance-level secrets. **Per-project connector credentials (Epic 3c) are stored in `project_credentials`, encrypted at rest** via envelope encryption with a master key supplied through the host environment (never persisted to the database). Ciphertext only at rest; plaintext held in memory only at egress; never logged, recorded, or exported.
- **Authorization:** Role labels are recorded in audit only. MVP does not enforce RBAC.
- **Secret redaction:** Redact on capture for known secret patterns, plus redact again on export.
- **Local REST security:** REST binds to localhost only, with no auth until the UI/security phase revisits local API tokens.
- **Encryption:** Application-level encryption is scoped to per-project connector credentials only (Epic 3c envelope encryption); no other application-managed encryption in MVP. The master key comes from the host environment, so the threat model defends against at-rest database compromise — not against host-environment compromise.

**First-Principles Security Model:**
The MVP security model protects against accidental exposure and misleading trust signals, not against hostile multi-user access. The most sensitive assets are credentials, local repository contents, context bundles, raw runner output, unredacted artifacts, local machine paths, and audit integrity.

The first security obligation is to prevent secrets and unnecessary local-only data from becoming durable workflow or shareable artifact content. The second obligation is to make trust boundaries visible: local-only data, redacted shareable data, and non-enforced audit role labels must be distinguishable.

**Security Guardrails for Local-First MVP:**

- Role labels are audit metadata only and must not be presented as enforced access control.
- REST endpoints must bind to localhost by default. Public interface binding should be blocked or require an explicit unsafe/development override.
- Local config and secret files must be checked for unsafe permissions where the operating system supports it.
- Credentials must not be persisted in workflow records, artifacts, context bundles, runner metadata, or exported review files.
- Raw runner output and raw logs are `local-only` unless sanitized.
- Redaction runs both when data is captured and when data is exported.
- Context bundle generation must exclude `.env` files, credential stores, shell history, unrelated local-only files, and unnecessary absolute machine paths.
- Dockerized runner containers should receive least-privilege filesystem mounts and environment variables.
- Exported artifacts should include data classification metadata so reviewers can distinguish redacted shareable data from local-only material.

**Security Upgrade Triggers and Residual Risks:**

The architecture must revisit authentication, authorization, and secret storage when any of these become true:

- React UI exposes write actions beyond localhost-only development.
- Multiple humans use the same running instance.
- Runs are shared through a team-visible service rather than exported artifacts.
- The application binds to non-loopback interfaces, remote development environments, tunnels, or hosted infrastructure.
- Credentials beyond per-project connector secrets need persistent storage, or key management must grow past a single host-environment master key (Epic 3c persists per-project connector credentials encrypted at rest; broader persistent secret storage, key rotation infrastructure, or OS keychain integration remains a future trigger).
- Runner containers require broad filesystem access or access to multiple repositories.
- Raw logs, context bundles, or runner outputs are retained beyond the MVP retention window.

Residual MVP risks remain: redaction can miss secrets, local filesystem permissions vary by OS, environment variables can leak through process/debug tooling, and role labels are not enforcement. These risks must be visible in documentation and should not be hidden behind product language that implies hosted-grade security.

### Multi-Project Configuration & Connector Pluggability

**Decision:** Introduce a first-class `Project` aggregate (Epic 3c) so a single local-first instance can govern multiple projects, each resolving its own repository, connectors, credentials, and run options at run time. This reverses the prior single-project assumption while preserving the single-operator, no-RBAC posture.

**Specific Choices:**

- **Project as configuration, not tenant:** `projects` is a configuration table (Flyway V14, prefix `prj_`). It carries the repository binding, ticket-source kind, repository-host kind, OpenSpec/run options, and status. It is not an access-control boundary — there is no per-project authorization or user scoping in MVP.
- **Run scoping:** every `workflow_run` carries a `project_id`; repository, connectors, and run options are resolved from the run's project. Existing single-project config seeds a reserved `default` project so prior flows are byte-identical (the config-inversion backward-compatibility seam).
- **Per-project connector resolution over the vendor-neutral ports:** builds on `TicketSourceAdapter` (story 3.32) and `RepositoryHostAdapter` (story 3.33). The global `deliveryline.integration.*.kind` keys are lifted to a per-project binding resolved by a `ProjectConnectorResolver`; an unsupported/unregistered kind is rejected at the application layer (not only by the DB CHECK). First-release reference kinds are `linear` (ticket source) and `github` (repository host); the seam is proven with one additional registered kind.
- **Credential encryption:** per-project connector secrets live in `project_credentials` (prefix `cred_`), encrypted at rest (see Authentication & Security). Credentials are write-only across all surfaces — set/rotate, never read back — and covered by redaction on capture and on export.
- **Boundary preserved:** no multi-user authentication, no RBAC enforcement, no tenant isolation; localhost-only REST binding and audit-only role labels are unchanged.

**Rationale:** Multi-project pilot use requires per-project repository + connector + credential configuration without reintroducing the cost of hosted multi-tenancy. Reusing the 3.32/3.33 ports keeps the pluggability seam vendor-neutral and ArchUnit-enforced, while the `default`-project migration de-risks inverting global configuration into per-project data on live run paths. Decisions are recorded in `docs/adr/0011-multi-project-configuration.md` (project aggregate + non-goal reversal), `docs/adr/0012-per-project-connector-resolution.md`, and `docs/adr/0013-credential-encryption.md`, authored in Epic 3c stories.

### API & Communication Patterns

**Decision:** Implement both CLI and localhost-only REST adapters from day one. Use hybrid REST design with resource-oriented read endpoints and explicit command/action endpoints for workflow mutations. Use Problem Details for REST errors, stable domain error codes, mandatory idempotency keys for all state-changing commands, OpenAPI documentation, and a process/container invocation model with versioned file-based runner contracts.

**Specific Choices:**

- **API style:** Dual CLI and REST from day one. Both call shared application use cases.
- **REST shape:** Resource-oriented reads and explicit command/action mutation endpoints.
- **Error model:** Problem Details (`application/problem+json`) with stable domain error codes.
- **Idempotency:** Idempotency key required for all state-changing REST and CLI commands.
- **Documentation:** OpenAPI from day one using Spring Boot-compatible `springdoc-openapi`.
- **Runner communication:** Process/container invocation with a versioned file-based contract.

**Rationale:**
The MVP is CLI-first, but the product will need a React review UI. Implementing REST from day one establishes API contracts early and avoids redesigning application use cases later. Keeping CLI and REST as adapters over shared application command models prevents the CLI from becoming the true domain interface.

Workflow mutations are command-like operations with audit, idempotency, and state-transition semantics, so action endpoints are clearer than pretending they are ordinary CRUD updates. Problem Details gives REST clients a standard error shape while stable domain error codes preserve transport-independent semantics. File-based runner contracts fit local-first Dockerized agents without requiring each runner to expose an HTTP service.

**Alternatives Considered:**

- CLI-only MVP: simpler, but delays REST contract design and increases later React rework.
- REST-first CLI: cleaner dogfooding, but adds server dependency and local API security pressure to every CLI operation.
- Pure resource REST: less suitable for workflow transitions and recovery actions.
- HTTP runner services: cleaner long-running executor protocol, but heavier infrastructure for MVP.
- Queue/job runner model: strong async pattern, but beyond first-release scope.

**API Consistency Rules:**

- Each state-changing operation should have one application command model. CLI arguments and REST request bodies translate into that model rather than defining separate behavior.
- Domain errors must be transport-independent. REST maps them to Problem Details; CLI maps them to concise terminal output.
- Idempotency behavior must be identical across CLI and REST for the same command.
- OpenAPI documents REST behavior, but the authoritative semantics live in application use cases and domain contracts.
- Runner input and output files must include schema/version identifiers, workflow/run identifiers, runner execution identifiers, context bundle references, checksums where applicable, and redaction/classification metadata.
- File-based runner outputs must enter the system through artifact operations and redaction/classification rules before they become approval-eligible.

**API Risk Controls:**

- REST endpoints must be covered by contract tests for request validation, idempotency behavior, Problem Details responses, and domain error codes.
- CLI commands and REST endpoints for the same use case must share command models and application service tests.
- Idempotency records must persist command key, command fingerprint, result reference, status, and conflict behavior.
- Runner context and result files must use versioned schemas rather than informal payloads.
- Runner file-contract tests should verify missing fields, wrong schema version, malformed output, duplicate result, stale result, checksum mismatch, and unsafe classification.
- Supported local network environments must be documented, especially Docker, WSL, remote dev, and tunnels where localhost assumptions can change.

**Runner Communication Trade-Off:**

The MVP uses process/container invocation with a versioned file-based contract because it fits local-first execution and Dockerized agent runners with the least infrastructure.

- The Spring app creates a versioned context bundle file.
- The runner receives only the mounted files and environment values needed for execution.
- The runner writes a versioned result file and optional diagnostic output.
- The Spring app validates schema version, run identity, runner execution identity, checksum, classification, and expected artifact references before accepting the result.
- Long-running runner state is tracked in `runner_executions` through status, timeout, last activity, and failure category.
- Heartbeat may be represented by periodic file/status updates or process observation in MVP.
- HTTP runner services and queue/job workers are deferred until runner orchestration requires stronger async lifecycle management.

**API Contract Quality Gates:**

- CLI and REST must translate into the same application command models. Tests should prove that the same command payload produces the same use-case behavior and the same stable domain error code across both transports.
- REST reads use resource endpoints. REST mutations use explicit command/action endpoints. OpenAPI must clearly distinguish retrieval operations from executable workflow commands.
- REST errors use Problem Details with a stable domain `code`, `type`, `title`, `detail`, `instance`, retryability metadata where relevant, and field-level validation details for invalid input. Tests assert stable codes, not human message text.
- Idempotency records must include key, command fingerprint, command type, actor identity, status, result reference, created time, and conflict behavior. Same key plus same fingerprint replays or returns the prior result. Same key plus different fingerprint fails with a stable conflict error. Concurrent duplicate submissions must resolve deterministically.
- The implementation must define behavior for crash between command acceptance and idempotency persistence, crash after side effect but before result persistence, replay after domain error, and replay after technical failure.
- Runner context and result files require schema version, workflow/run ID, runner execution ID, context bundle reference, expected artifact references, checksum algorithm, classification metadata, and compatibility rules for unknown fields and unknown schema versions.
- Tests must reject missing fields, unknown schema versions, bad checksums, duplicate execution IDs, stale metadata, malformed classification metadata, partial writes, truncation, oversized files, path traversal, and metadata spoofing.
- Runner outputs may become workflow artifacts only through artifact operations. Direct runner writes are not approval-eligible until validated, classified, checksummed, and recorded.
- OpenAPI is a quality artifact. Contract tests should verify request/response shape, status codes, Problem Details payloads, idempotency requirements, and command endpoint semantics.

### Frontend Architecture

**Decision:** Include a minimal React review UI in the MVP, bundled and served by the Spring Boot application. Build the UI with Vite React TypeScript, driven by Maven for packaged builds. Use TanStack Query for server state, local component state for UI-only state, shadcn/ui plus Tailwind for UI primitives and styling, and TanStack Router for typed routes.

**Specific Choices:**

- **MVP frontend scope:** Minimal React review UI in MVP.
- **Serving model:** Spring Boot serves bundled React assets.
- **Build:** Maven-driven Vite React TypeScript build.
- **State management:** TanStack Query plus local component state.
- **UI approach:** shadcn/ui plus Tailwind.
- **Routing:** TanStack Router.

**Rationale:**
The product depends on human review, approval, recovery, and artifact inspection. A CLI-only MVP would prove backend mechanics, but a minimal review UI better validates whether users can understand a run, inspect artifacts, and take the next safe action. Bundling the UI into Spring Boot keeps the local-first MVP easy to run as one app and avoids CORS/origin complexity.

TanStack Query keeps workflow state server-owned and prevents the frontend from becoming a second source of truth. TanStack Router gives typed deep links for workflow and artifact views. shadcn/ui and Tailwind provide practical, accessible UI primitives without committing to a heavy enterprise component library.

**Frontend Scope Guardrails:**

Included screens:

- pending review / workflow list
- workflow detail timeline
- artifact viewer
- approval/rejection actions
- retry/takeover/reconciliation actions when backend reports them as allowed

Excluded from MVP:

- analytics dashboard
- workflow builder
- team administration
- policy editor
- broad notification center
- complex collaboration features
- role-management UI

Frontend implementation should follow these guardrails:

- Backend-reported allowed actions determine available controls.
- No frontend-only workflow state transitions.
- No frontend interpretation of approval eligibility beyond displaying backend state.
- shadcn/ui components should be used consistently with minimal local customization.
- UI text must not imply role enforcement, hosted security, or multi-user authorization that MVP does not provide.
- Any new screen must map directly to review, artifact inspection, approval, or recovery.

**Frontend Persona Requirements:**

- **Product reviewer:** can inspect the current specification artifact, see what changed since prior review, confirm the artifact/context version under decision, and approve or reject with structured feedback.
- **Developer reviewer:** can inspect the approved spec, implementation artifact, runner diagnostics, lineage to branch/PR/diff references, and choose approve, reject, retry, or takeover when allowed.
- **Workflow owner:** can inspect failed or stalled runs, last safe state, failure reason, external sync/conflict status, and backend-reported next safe actions.

Across all personas, the UI should answer four questions without requiring raw log inspection first: what happened, what is current, who owns it now, and what is the next safe action.

**Frontend Failure Handling Expectations:**

- Approval and recovery actions must send the workflow state, artifact version, context version, and idempotency key expected by the user. Backend version mismatch returns a stable conflict error.
- TanStack Query mutations must invalidate or refetch workflow detail, timeline, artifact, approval, and pending-review queries affected by the action.
- UI controls for approve, reject, retry, takeover, and reconcile should be derived from backend-reported allowed actions, not hardcoded frontend assumptions.
- React routes must support direct deep links through Spring Boot static serving fallback.
- The frontend build must be reproducible through Maven packaging, with CI failing if bundled assets cannot be produced.
- Artifact rendering must treat runner output as untrusted content. Markdown/diff rendering should sanitize or render safely.
- The UI must label audit roles as recorded roles, not enforced permissions, until real authorization exists.

**Frontend Quality Gates:**

- Spring Boot serves bundled React assets. SPA fallback, static asset cache headers, direct-refresh routes, and route-not-found behavior must be explicitly tested.
- Approval and recovery actions must send expected workflow state, artifact version, context version, and idempotency key. Backend version mismatch returns `409` with a stable conflict code and enough detail for the UI to refresh and explain what changed.
- Available UI controls come from backend-reported allowed actions. The frontend must handle empty, disabled, unknown, or stale action sets without inferring workflow permissions locally.
- Version mismatch or stale decision attempts need a clear UI state, not only a toast. The UI should show what became stale, what changed if available, and which action is now valid.
- TanStack Query owns server state; local React state is limited to ephemeral UI concerns. Mutation success must invalidate/refetch affected workflow, timeline, artifact, approval, and pending-review queries.
- TanStack Router route params for workflow and artifact IDs must be typed and validated. Deep links, missing workflows, missing artifacts, unsupported routes, and unsupported workspace states need explicit UI handling.
- Runner output is untrusted. Markdown/diff/artifact rendering must escape or sanitize unsafe content, block scriptable payloads, and visually separate trusted metadata from generated content.
- UI labels must make clear that MVP roles are recorded audit labels, not enforced authorization. Frontend code must not gate actions based on audit role labels.
- The review UI must include loading, empty review queue, conflict, stale data, no-actions-available, missing artifact, failed artifact load, untrusted artifact, and route-not-found states.

### Infrastructure & Deployment

**Decision:** The MVP runs locally as a Spring Boot executable jar with bundled React assets. PostgreSQL and runner image definitions are provided through Docker Compose. Runner containers are external executors controlled by the runner broker. Use Spring profiles `local`, `test`, and `demo`. CI runs in GitHub Actions. A local observability stack is available for demo/diagnostic use but should be optional/profile-gated. MVP deployment is local-only.

**Specific Choices:**

- **Runtime topology:** Single Spring Boot app plus local PostgreSQL plus external runner containers.
- **Docker Compose scope:** PostgreSQL plus named runner images/services. Runner services are build/profile anchors, not always-on workflow workers.
- **Packaging:** Executable Spring Boot jar with bundled React UI.
- **Environment configuration:** Spring profiles `local`, `test`, and `demo`.
- **CI:** GitHub Actions.
- **Logging and monitoring:** Local observability stack plus structured logs and workflow event log. Observability is optional/profile-gated.
- **Deployment:** Local-only MVP.

**Rationale:**
The product is intentionally local-first. Running the Spring Boot app as a local executable jar keeps CLI, REST, and bundled UI easy to develop and operate. Docker Compose provides repeatable PostgreSQL and runner image setup without forcing the app itself into a container during MVP development. Keeping runner lifecycle under the app's broker preserves the workflow audit and recovery model.

GitHub Actions provides a practical quality gate for backend tests, frontend build, migrations, OpenAPI contracts, runner file contracts, and packaging. Local observability helps diagnose demo and runner/container behavior, but requiring a full observability stack for normal MVP use would increase setup friction.

**Infrastructure Consistency Rules:**

- The app should provide a `doctor` or equivalent diagnostic command that checks Java version, Spring profile, PostgreSQL connectivity, Flyway state, required directories, config file permissions, Docker availability, runner image availability, and local REST bind address.
- Docker Compose runner services are build/profile anchors, not always-on workflow workers. Runner lifecycle remains controlled by the runner broker.
- The local observability stack should be optional and profile-gated, such as `demo-observability`, so normal MVP operation does not require Prometheus/Grafana/Loki.
- CI should separate fast unit/application tests from Docker-backed integration and contract tests to reduce flakiness while preserving quality gates.
- Local-only MVP should support sharing through exported/redacted run artifacts rather than hosted access.

**Infrastructure Risk Controls:**

- Local setup must have one documented happy path and one `doctor` command that distinguishes missing prerequisites from product failures.
- Runner images may be defined in Compose, but runner execution must always be initiated and tracked by the runner broker.
- Observability stack is optional and must not be required for normal workflow execution, tests, or recovery.
- The `demo` profile may seed data or stabilize external inputs, but must not bypass workflow state transitions, artifact operations, approval gates, runner contracts, redaction, or recovery rules.
- Exported/redacted run reports are the primary sharing mechanism for local-only MVP.
- CI should split fast tests from Docker-backed integration/contract tests and make flakiness visible rather than masking it with broad retries.
- Failure to bind REST to loopback, connect to PostgreSQL, build runner images, or package React assets should fail fast with actionable diagnostics.

**Infrastructure Failure Handling Expectations:**

- `doctor` checks should run before or independently of workflow execution and report missing Docker, PostgreSQL connectivity, Flyway state, runner image availability, artifact directory writability, config permissions, REST bind address, and required tool versions.
- Mutation commands should fail before state changes when PostgreSQL, schema, artifact storage, or runner prerequisites are unavailable.
- Runner image missing, container startup failure, timeout, or early exit should produce structured runner infrastructure failure records with diagnostics.
- Observability stack failure must not block normal workflow execution.
- React build or bundled asset failure blocks packaging.
- SPA deep-link fallback must be covered by packaging/integration tests.
- REST non-loopback binding should fail closed unless an explicit unsafe development override is provided.

**Infrastructure Operational Quality Gates:**

- The executable jar must contain the compiled React SPA assets and Spring Boot backend. Packaging fails if frontend assets cannot be built, copied, or served.
- The app must distinguish process started from workflow usable. Readiness checks should verify PostgreSQL connectivity, Flyway migration state, artifact directory access, local REST loopback binding, bundled SPA availability, and runner image availability where runner execution is required.
- `doctor` should provide stable human-readable output plus machine-readable JSON, stable exit codes, and stable infrastructure error codes for missing Docker, PostgreSQL unavailable, Flyway failure, runner image missing, artifact directory unwritable, REST bind failure, config permission failure, and frontend asset missing.
- Compose runner definitions are not runner identities. The runner broker owns runner execution identity, start/stop/retry, heartbeat or last activity, lease expiry, stale execution cleanup, and idempotent restart behavior after broker or container failure.
- `local`, `test`, and `demo` profiles must document property precedence, allowed overrides, and settings that cannot bypass workflow rules. Demo may seed data or stabilize external inputs, but must not bypass state transitions, approvals, validation, artifact operations, runner contracts, redaction, or recovery.
- Tests must cover direct refresh of React routes, route-not-found behavior, API path collisions, REST 404s, and SPA fallback not masking missing API endpoints.
- Exported redacted run reports must have deterministic structure and tests proving secrets, tokens, hostnames, user identifiers, and local paths are removed or classified according to policy.
- CI should separate fast unit/application tests, Docker-backed integration tests, contract tests, bundled-jar smoke tests, frontend build checks, and export/redaction verification.
- Local development should include a documented cleanup/reset command for volumes, runner state, artifact temp files, and demo data so failed runs do not poison later runs.

### Decision Impact Analysis

**Implementation Sequence:**

1. Initialize Spring Boot project with Maven, PostgreSQL, Flyway, Spring Web, validation, Actuator, Docker Compose support, Testcontainers, and Spring Shell.
2. Establish package boundaries for adapters, application services, domain model, persistence, runner infrastructure, artifact storage, and configuration.
3. Implement Flyway schema for workflow runs, events, artifacts, artifact operations, approvals, runner executions, integrations, recovery actions, idempotency records, and summary fields.
4. Implement transition service, state/event atomicity, idempotency, artifact operations, and reconciliation primitives.
5. Implement CLI and REST adapters over shared application command models.
6. Implement OpenAPI, Problem Details mapping, API contract tests, and CLI/REST adapter equivalence tests.
7. Implement runner broker with versioned file-based runner contract and deterministic mock runner.
8. Implement local config, redaction, data classification, and export/report generation.
9. Implement minimal React review UI, bundled Vite build, query/routing contracts, safe artifact rendering, and frontend quality gates.
10. Implement Docker Compose, doctor command, profiles, CI tiers, optional observability, and local packaging smoke tests.

**Cross-Component Dependencies:**

- Data transition rules underpin CLI, REST, React, runner broker, recovery, and integration behavior.
- Security classification and redaction apply to context bundles, runner outputs, artifacts, exports, logs, and frontend rendering.
- API idempotency depends on explicit relational data model and transition service discipline.
- Frontend approval safety depends on API version/conflict semantics and artifact operation availability.
- Infrastructure diagnostics depend on configuration, database, runner, artifact storage, and frontend packaging contracts.
- Runner communication depends on artifact operations, context bundle generation, redaction, and infrastructure Docker support.

## Implementation Patterns & Consistency Rules

### Pattern Categories Defined

Critical conflict points identified:

- database, migration, ID, and timestamp naming
- Java package boundaries and dependency direction
- REST endpoint and action naming
- JSON, Problem Details, and domain error formats
- workflow state, event type, and registry naming
- idempotency and correlation ID formats
- runner file schema and artifact ingress formats
- React component, route, and query-key naming
- test placement and contract-test naming
- structured logging and redaction practices

### Naming Patterns

**Database Naming Conventions:**

- Tables use plural `snake_case`: `workflow_runs`, `workflow_events`, `runner_executions`.
- Columns use `snake_case`: `workflow_run_id`, `created_at`, `idempotency_key`.
- Foreign keys use `{referenced_entity_singular}_id`: `workflow_run_id`, `artifact_id`.
- Indexes use `idx_{table}_{columns}`: `idx_workflow_events_workflow_run_id_created_at`.
- Unique constraints use `uq_{table}_{columns}`.
- Foreign key constraints use `fk_{table}_{referenced_table}`.
- Check constraints use `ck_{table}_{meaning}`.
- Flyway migration files use `V{number}__{description}.sql`, for example `V1__create_workflow_core_tables.sql`.
- Database timestamps use `timestamptz`.

**API Naming Conventions:**

- Base path is `/api/v1`.
- REST resources use plural nouns: `/workflows`, `/workflows/{workflowRunId}/events`.
- REST action endpoints use kebab-case: `/workflows/{workflowRunId}/approve-spec`.
- Path parameters use descriptive names when ambiguity matters: `{workflowRunId}`, `{artifactId}`.
- REST request/response JSON uses `camelCase`.
- Domain error codes use uppercase snake_case: `APPROVAL_VERSION_MISMATCH`.
- REST errors use Problem Details plus project extension fields.

**Code Naming Conventions:**

- Java classes and enums use normal Java conventions.
- Java fields and DTO fields use `camelCase`.
- Workflow states are uppercase enum values: `WAITING_FOR_SPEC_APPROVAL`.
- Event types use dot-separated lowerCamel segments: `workflow.stateChanged`, `approval.requested`, `runner.failed`, `recovery.reconciled`.
- Public/domain IDs use readable prefixed strings stored as text, such as `run_`, `evt_`, `art_`, `op_`, `apr_`, and `rex_`.
- External IDs from Linear, GitHub, and runner providers remain namespaced separately and must not be confused with internal IDs.

**React Naming Conventions:**

- Component files use PascalCase: `WorkflowTimeline.tsx`, `ArtifactViewer.tsx`, `ApprovalActions.tsx`.
- Component names match filenames.
- Route components use descriptive PascalCase: `WorkflowDetailRoute.tsx`, `ArtifactViewerRoute.tsx`.
- Hooks use `useX.ts`, for example `useWorkflowDetail.ts`.
- App-owned frontend utility modules should use camelCase TypeScript module names unless tooling requires otherwise.

### Structure Patterns

**Java Package Organization:**

- `domain`: core model, value objects, domain invariants. No Spring framework types.
- `application`: use cases, command models, transition service, orchestration services.
- `adapters.cli`: Spring Shell commands.
- `adapters.rest`: REST controllers, DTO mapping, Problem Details mapping.
- `adapters.persistence`: JPA entities, repositories, persistence mappers.
- `adapters.runner`: Docker/process runner adapter.
- `adapters.files`: artifact filesystem implementation.
- `adapters.integration.linear`: Linear adapter.
- `adapters.integration.github`: GitHub adapter.
- `infrastructure.config`: Spring configuration, profiles, properties.
- `infrastructure.observability`: logging, metrics, health integrations.

Boundary rules:

- CLI and REST adapters call application use cases.
- CLI and REST adapters do not directly call repositories, runner adapters, or file storage.
- `application` may depend on `domain`, not on `adapters`.
- `domain` depends on no Spring or infrastructure types.
- `adapters.persistence` maps between JPA entities and domain/application models.

**Frontend Organization:**

- `features/workflows`: workflow-specific views, components, hooks, and route-facing feature code.
- `components`: shared UI components and shadcn/ui components.
- `lib/api`: typed/generated API client and API helpers.
- `lib/queryKeys`: TanStack Query key factories.
- `routes`: TanStack Router route definitions.

**Test Organization:**

- Java unit tests: `src/test/java/.../*Test.java`.
- Java integration tests: `src/test/java/.../*IT.java`.
- Contract tests: `src/test/java/.../*ContractTest.java`.
- Frontend tests: near components where practical as `*.test.tsx`.
- Runner file-contract fixtures: `src/test/resources/runner-contracts` or equivalent.
- Test names describe behavior, not implementation details.

### Format Patterns

**API Response Formats:**

- Successful REST responses return direct resource/command-result DTOs, not a generic `{data: ...}` wrapper.
- REST errors use `application/problem+json`.
- Required Problem Details fields: `type`, `title`, `status`, `detail`, `instance`, `code`, `retryable`.
- Optional Problem Details field: `details` for machine-readable validation, conflict, version, or recovery metadata.
- Tests assert `code`, `status`, and relevant `details`, not exact human message wording.

Example:

```json
{
  "type": "https://deliveryline.local/problems/approval-version-mismatch",
  "title": "Approval version mismatch",
  "status": 409,
  "detail": "The artifact version changed before approval was submitted.",
  "instance": "/api/v1/workflows/run_123/approve-spec",
  "code": "APPROVAL_VERSION_MISMATCH",
  "retryable": true,
  "details": {
    "expectedArtifactVersion": 3,
    "currentArtifactVersion": 4
  }
}
```

**Data Exchange Formats:**

- REST JSON uses `camelCase`.
- Runner file schemas use `camelCase` unless an external provider requires otherwise.
- API timestamps are ISO-8601 UTC strings.
- Workflow states, event types, failure categories, and domain error codes are stored as strings, never ordinals.
- Boolean values use JSON booleans.
- Null values are used only when absence is meaningful and documented.

**Idempotency Key Format:**

- REST uses `Idempotency-Key` header.
- CLI accepts `--idempotency-key`.
- Interactive CLI may generate a UUID key and print it in verbose/debug output.
- Keys are opaque strings, recommended UUID v4 or UUID v7.
- Keys must pass length and character validation.
- Idempotency records store key, command type, actor identity, command fingerprint, status, result reference, and conflict behavior.
- Same key plus same fingerprint returns prior result or no-op status.
- Same key plus different fingerprint returns `IDEMPOTENCY_KEY_CONFLICT`.

### Communication Patterns

**Event System Patterns:**

- Event types use dot-separated lowerCamel namespaces.
- Stable event namespaces include `workflow`, `approval`, `artifact`, `runner`, `recovery`, `integration`, and `export`.
- Central registries define workflow states, event types, domain error codes, failure categories, artifact types, artifact operation statuses, runner execution statuses, data classification values, and backend-reported allowed action names.
- New states, events, errors, failures, artifact types, statuses, or allowed actions must be added to the relevant central registry and tests.

**State Management Patterns:**

- Backend owns workflow state.
- Frontend server state is managed by TanStack Query.
- Frontend local state is limited to ephemeral UI concerns.
- TanStack Query keys are created through query key factory functions, not ad hoc inline arrays.

Example:

```ts
export const workflowKeys = {
  all: ['workflows'] as const,
  list: (filters: WorkflowListFilters) => ['workflows', 'list', filters] as const,
  detail: (workflowRunId: string) => ['workflows', 'detail', workflowRunId] as const,
  events: (workflowRunId: string) => ['workflows', 'detail', workflowRunId, 'events'] as const,
  artifacts: (workflowRunId: string) => ['workflows', 'detail', workflowRunId, 'artifacts'] as const,
};
```

**Logging Patterns:**

- Every command/request has a `correlationId`.
- Workflow-scoped logs include `workflowRunId`.
- Runner logs include `runnerExecutionId`.
- Artifact logs include `artifactId` and `artifactOperationId` where relevant.
- Logs use structured fields where the logging stack supports them.
- Workflow events are the product audit record; application logs are technical diagnostics.
- Do not log secrets, raw credentials, unredacted context bundles, or unredacted runner output.

### Process Patterns

**Error Handling Patterns:**

- Application/domain errors are transport-independent.
- REST maps domain errors to Problem Details.
- CLI maps domain errors to concise terminal output with the same stable `code`.
- Unknown enum/registry values from persisted data or external inputs fail explicitly or route to reconciliation; they do not silently map to defaults.
- User-facing messages may change; stable codes must not change without migration or compatibility notes.

**Loading and Mutation Patterns:**

- Frontend loading states are local to views/components.
- Mutations invalidate or refetch affected workflow detail, timeline, artifact, approval, and pending-review queries.
- Available UI controls come from backend-reported allowed actions.
- Frontend does not infer workflow permissions locally.

### Pattern Enforcement Rules

Implementation patterns must be enforceable, not only descriptive:

- Java package boundaries should be tested with ArchUnit or equivalent.
- Flyway migration naming is mandatory and should be rejected in review if violated.
- Workflow states, event types, failure categories, and domain error codes are stored as strings, never ordinals.
- Database timestamps use `timestamptz`; API timestamps use ISO-8601 UTC strings.
- REST action endpoint names use kebab-case.
- Domain error codes use uppercase snake_case.
- IDs follow the central prefix registry.
- Frontend structure separates `features/workflows`, shared `components`, `lib/api`, `lib/queryKeys`, and `routes`.
- Runner file schema fixtures should be versioned and tested as contract artifacts.

### Consistency Drift Prevention

Agents must avoid these known drift patterns:

- Do not create REST action paths in camelCase; use kebab-case action names.
- Do not return custom error envelopes from REST; use Problem Details with the project extension fields.
- Do not store enum ordinals in JPA; persist string values.
- Do not put workflow orchestration, persistence, runner dispatch, artifact lineage, approval, or recovery logic in CLI/REST adapters.
- Do not access repositories directly from CLI commands or REST controllers.
- Do not create ad hoc TanStack Query keys; use query key factories.
- Do not render runner output as trusted HTML.
- Do not use local timezone timestamps in persisted records or API payloads; use UTC instants and ISO-8601 API strings.
- Do not log raw context bundles, credentials, environment variables, or unredacted runner output.
- Do not write approval-eligible artifact files outside artifact operations.
- Do not introduce domain error codes, event types, or failure categories without adding them to the central registry.
- Do not store server workflow state as independent frontend state.

### Hard Invariants and Rejection Criteria

These rules are mandatory. Violations should be treated as architecture defects, not style preferences:

- `snake_case` is for database identifiers only. Java, JSON, TypeScript, and runner schemas use `camelCase` unless an external provider requires otherwise.
- Central registries are the source of truth for workflow states, event types, domain error codes, failure categories, artifact types, artifact operation statuses, runner execution statuses, data classification values, allowed action names, ID prefixes, and runner schema versions.
- States, event types, error codes, failure categories, artifact types, and statuses must not be defined ad hoc outside central registries.
- Event types use dot-separated lowerCamel namespaces and stable namespaces.
- Problem Details `details` must be machine-readable JSON, not free-form text.
- Public/domain ID prefixes must come from the prefix registry. Producers and validators must reject unknown or mismatched prefixes.
- Correlation fields use stable names: `correlationId`, `workflowRunId`, `runnerExecutionId`, `artifactId`, and `artifactOperationId`.
- Unknown enum/registry values from persisted data or external inputs must fail explicitly or route to reconciliation.
- New REST mutation endpoints must be added to the action endpoint allowlist and must use kebab-case.
- New query keys must be added through query key factories, not inline arrays inside components.

### Pattern Enforcement Quality Gates

The build should enforce consistency through tests and static checks:

- ArchUnit or equivalent tests enforce package boundaries, adapter direction, and forbidden dependencies.
- API/schema tests verify camelCase JSON, Problem Details shape, stable error codes, public ID prefixes, and route naming conventions.
- Serialization/persistence tests verify enums are stored as strings, timestamps use `timestamptz`, and API timestamps serialize as UTC ISO-8601.
- Registry drift tests compare central registry values against domain enums, API schemas, frontend allowed actions, and test fixtures.
- REST routing convention tests reject singular resource paths and camelCase action endpoints.
- Public ID validation tests cover all registered prefixes, producer paths, and consumer validation paths.
- Idempotency tests cover uniqueness, replay, conflict behavior, and retry semantics.
- Query key factory tests verify stable keys and prevent collisions.
- Logging tests or assertions verify correlation/run identifiers appear on request, command, runner, artifact, and failure paths.

### Enforcement Guidelines

All AI agents must:

- Use the central registries before introducing states, event types, error codes, failure categories, artifact types, statuses, allowed actions, ID prefixes, or runner schema versions.
- Keep adapters thin and business rules in application/domain services.
- Use explicit mappers at DB/API/schema boundaries.
- Add tests for any new convention-sensitive path.
- Treat pattern violations as architecture defects.

Pattern updates must be reflected in this architecture document, central registries, contract tests, and relevant examples.

### Pattern Examples

**Good Examples:**

- `workflow_runs.current_state`
- `V1__create_workflow_core_tables.sql`
- `GET /api/v1/workflows/{workflowRunId}/events`
- `POST /api/v1/workflows/{workflowRunId}/approve-spec`
- `APPROVAL_VERSION_MISMATCH`
- `workflow.stateChanged`
- `WorkflowTimeline.tsx`
- `workflowKeys.detail(workflowRunId)`
- `run_123`, `evt_123`, `art_123`

**Anti-Patterns:**

- `/api/v1/approveSpec`
- `{ "error": { "message": "failed" } }`
- storing enum ordinals
- Spring Shell command calling a repository directly
- REST controller writing artifact files directly
- inline TanStack Query keys in components
- logging raw runner output with secrets
- storing workflow state only in frontend state

## Project Structure & Boundaries

### Complete Project Directory Structure

```text
deliveryline/
├── README.md
├── AGENTS.md
├── pom.xml
├── .gitignore
├── .env.example
├── docker-compose.yml
├── docker-compose.observability.yml
├── .github/
│   └── workflows/
│       └── ci.yml
├── docs/
│   ├── architecture.md
│   ├── setup-local.md
│   ├── runner-contract.md
│   └── operations/
│       ├── doctor.md
│       ├── recovery.md
│       └── export-redaction.md
├── scripts/
│   ├── doctor.ps1
│   ├── reset-local.ps1
│   ├── build-runner-images.ps1
│   └── export-run.ps1
├── backend/
│   ├── pom.xml
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── org/dradgo/
│   │   │   │       ├── DeliveryLineApplication.java
│   │   │   │       ├── domain/
│   │   │   │       │   ├── model/
│   │   │   │       │   │   ├── WorkflowRun.java
│   │   │   │       │   │   ├── WorkflowState.java
│   │   │   │       │   │   ├── WorkflowEventType.java
│   │   │   │       │   │   ├── ArtifactType.java
│   │   │   │       │   │   ├── FailureCategory.java
│   │   │   │       │   │   └── DataClassification.java
│   │   │   │       │   ├── ids/
│   │   │   │       │   │   ├── WorkflowRunId.java
│   │   │   │       │   │   ├── ArtifactId.java
│   │   │   │       │   │   ├── RunnerExecutionId.java
│   │   │   │       │   │   └── PublicIdPrefixes.java
│   │   │   │       │   └── registry/
│   │   │   │       │       ├── DomainRegistry.java
│   │   │   │       │       ├── DomainErrorCode.java
│   │   │   │       │       └── AllowedAction.java
│   │   │   │       ├── application/
│   │   │   │       │   ├── workflow/
│   │   │   │       │   │   ├── WorkflowCommandService.java
│   │   │   │       │   │   ├── WorkflowTransitionService.java
│   │   │   │       │   │   ├── WorkflowInspectionService.java
│   │   │   │       │   │   └── commands/
│   │   │   │       │   │       ├── SubmitWorkflowCommand.java
│   │   │   │       │   │       ├── ApproveSpecCommand.java
│   │   │   │       │   │       ├── RejectSpecCommand.java
│   │   │   │       │   │       ├── RetryWorkflowCommand.java
│   │   │   │       │   │       └── TakeoverWorkflowCommand.java
│   │   │   │       │   ├── artifact/
│   │   │   │       │   │   ├── ArtifactService.java
│   │   │   │       │   │   ├── ArtifactOperationService.java
│   │   │   │       │   │   └── ArtifactReconciliationService.java
│   │   │   │       │   ├── runner/
│   │   │   │       │   │   ├── RunnerBroker.java
│   │   │   │       │   │   ├── RunnerExecutionService.java
│   │   │   │       │   │   └── ContextBundleService.java
│   │   │   │       │   ├── approval/
│   │   │   │       │   │   └── ApprovalService.java
│   │   │   │       │   ├── integration/
│   │   │   │       │   │   └── IntegrationLinkService.java
│   │   │   │       │   ├── recovery/
│   │   │   │       │   │   └── RecoveryService.java
│   │   │   │       │   ├── idempotency/
│   │   │   │       │   │   └── IdempotencyService.java
│   │   │   │       │   ├── export/
│   │   │   │       │   │   └── RunExportService.java
│   │   │   │       │   ├── security/
│   │   │   │       │   │   ├── RedactionPolicyService.java
│   │   │   │       │   │   └── DataClassificationService.java
│   │   │   │       │   └── diagnostics/
│   │   │   │       │       └── DoctorService.java
│   │   │   │       ├── adapters/
│   │   │   │       │   ├── cli/
│   │   │   │       │   │   ├── WorkflowCommands.java
│   │   │   │       │   │   ├── RunnerCommands.java
│   │   │   │       │   │   └── DoctorCommand.java
│   │   │   │       │   ├── rest/
│   │   │   │       │   │   ├── WorkflowController.java
│   │   │   │       │   │   ├── ArtifactController.java
│   │   │   │       │   │   ├── ApprovalController.java
│   │   │   │       │   │   ├── ProblemDetailsMapper.java
│   │   │   │       │   │   └── dto/
│   │   │   │       │   ├── persistence/
│   │   │   │       │   │   ├── entity/
│   │   │   │       │   │   │   ├── WorkflowRunEntity.java
│   │   │   │       │   │   │   ├── WorkflowEventEntity.java
│   │   │   │       │   │   │   ├── ArtifactEntity.java
│   │   │   │       │   │   │   ├── ArtifactOperationEntity.java
│   │   │   │       │   │   │   ├── RunnerExecutionEntity.java
│   │   │   │       │   │   │   ├── ApprovalEntity.java
│   │   │   │       │   │   │   ├── IntegrationLinkEntity.java
│   │   │   │       │   │   │   ├── RecoveryActionEntity.java
│   │   │   │       │   │   │   └── IdempotencyRecordEntity.java
│   │   │   │       │   │   ├── repository/
│   │   │   │       │   │   └── mapper/
│   │   │   │       │   ├── runner/
│   │   │   │       │   │   ├── DockerRunnerAdapter.java
│   │   │   │       │   │   ├── FileRunnerContractReader.java
│   │   │   │       │   │   └── MockRunnerAdapter.java
│   │   │   │       │   ├── files/
│   │   │   │       │   │   └── LocalArtifactStore.java
│   │   │   │       │   └── integration/
│   │   │   │       │       ├── linear/
│   │   │   │       │       └── github/
│   │   │   │       └── infrastructure/
│   │   │   │           ├── config/
│   │   │   │           ├── observability/
│   │   │   │           └── web/
│   │   │   │               └── SpaFallbackController.java
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-local.yml
│   │   │       ├── application-test.yml
│   │   │       ├── application-demo.yml
│   │   │       ├── db/migration/
│   │   │       │   └── V1__create_workflow_core_tables.sql
│   │   │       ├── static/
│   │   │       └── openapi/
│   │   └── test/
│   │       ├── java/
│   │       │   └── org/dradgo/
│   │       │       ├── architecture/ArchitectureBoundaryTest.java
│   │       │       ├── application/
│   │       │       ├── adapters/
│   │       │       ├── contract/
│   │       │       │   ├── api/
│   │       │       │   ├── runner/
│   │       │       │   ├── redaction/
│   │       │       │   └── export/
│   │       │       └── integration/
│   │       └── resources/
│   │           ├── runner-contracts/
│   │           ├── api-contracts/
│   │           ├── redaction-fixtures/
│   │           └── fixtures/
├── frontend/
│   ├── pom.xml
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── components.json
│   ├── tailwind.config.ts
│   ├── src/
│   │   ├── main.tsx
│   │   ├── App.tsx
│   │   ├── routes/
│   │   │   ├── root.tsx
│   │   │   ├── WorkflowsRoute.tsx
│   │   │   ├── WorkflowDetailRoute.tsx
│   │   │   └── ArtifactViewerRoute.tsx
│   │   ├── features/
│   │   │   └── workflows/
│   │   │       ├── hooks/
│   │   │       ├── WorkflowList.tsx
│   │   │       ├── WorkflowTimeline.tsx
│   │   │       ├── ArtifactViewer.tsx
│   │   │       ├── ApprovalActions.tsx
│   │   │       └── RecoveryPanel.tsx
│   │   ├── components/
│   │   │   ├── ui/
│   │   │   ├── layout/
│   │   │   └── feedback/
│   │   ├── lib/
│   │   │   ├── api/
│   │   │   │   ├── client.ts
│   │   │   │   └── problemDetails.ts
│   │   │   ├── queryKeys/
│   │   │   │   └── workflowKeys.ts
│   │   │   └── utils.ts
│   │   └── styles/
│   │       └── globals.css
│   └── src/test/
├── runner-contracts/
│   ├── pom.xml
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/org/dradgo/runnercontracts/
│   │   │   │   └── RunnerContractValidator.java
│   │   │   └── resources/schemas/
│   │   │       ├── context-bundle.v1.schema.json
│   │   │       └── runner-result.v1.schema.json
│   │   └── test/
│   │       ├── java/org/dradgo/runnercontracts/
│   │       └── resources/fixtures/
│   │           ├── valid/
│   │           └── invalid/
├── runners/
│   ├── codex/
│   │   ├── Dockerfile
│   │   ├── entrypoint.sh
│   │   └── README.md
│   └── claude/
│       ├── Dockerfile
│       ├── entrypoint.sh
│       └── README.md
└── infra/
    └── observability/
        ├── prometheus.yml
        ├── grafana/
        └── loki/
```

### Project Structure ADR Summary

**Decision:** Use a Maven multi-module root with `backend`, `frontend`, and `runner-contracts` modules, plus root-level `runners`, `infra`, `scripts`, `docs`, and GitHub Actions configuration. Keep actual runner implementations outside Maven modules under `runners/`, while keeping runner schemas, fixtures, and validation in the `runner-contracts` module.

**Rationale:** The architecture needs strong boundaries between Spring Boot application code, React UI, runner protocol contracts, runner executable/container assets, and local infrastructure. A Maven multi-module root gives one formal build graph for backend, frontend, and runner-contract verification. Keeping actual runners outside Maven avoids forcing non-Java container assets into Maven module conventions while still making them visible as product execution assets.

This structure supports the executable jar packaging decision, where the frontend module builds React assets and the backend module packages them. It also keeps runner contracts as a first-class artifact so the backend and Dockerized runners can be tested against the same schema/fixture source.

**Alternatives Considered:**

- Single Maven project with frontend under `src/main/frontend`: simpler packaging, but weaker boundaries for agents and CI.
- Plain monorepo with only backend Maven project: easier setup, but less formal build graph for frontend and runner contracts.
- Maven module per runner: stronger build integration, but inappropriate if runners are scripts, Docker images, or mixed-language assets.
- Runner Dockerfiles under `infra/`: keeps Docker files together, but hides runner behavior under deployment plumbing.

**Consequences:**

- Root Maven build must orchestrate frontend and runner-contract modules.
- Backend packaging must have a documented dependency on frontend build output.
- CI must explicitly build/test runner images because they are outside Maven modules.
- Runner schemas and fixtures must remain the source of truth for backend and runner implementations.
- Project structure is heavier than a minimal hackathon repo, but substantially reduces agent ownership ambiguity.

### Architectural Boundaries

**API Boundaries:**

- CLI and REST adapters translate input into application command models.
- REST endpoints expose `/api/v1` resource reads and command/action mutations.
- REST returns Problem Details for errors.
- OpenAPI documents REST behavior, while application use cases own semantics.

**Component Boundaries:**

- `domain` has no Spring or adapter dependencies.
- `application` owns workflow orchestration and depends on `domain`.
- `adapters` implement CLI, REST, persistence, files, runners, and external integrations.
- `infrastructure` owns Spring configuration, profile wiring, observability, and SPA fallback.

**Service Boundaries:**

- `WorkflowTransitionService` is the only state transition path.
- `ArtifactOperationService` is the only approval-eligible artifact ingress path.
- `RunnerBroker` owns runner lifecycle and file-contract validation.
- `DoctorService` owns local environment diagnostics.
- `RedactionPolicyService` owns redaction policy orchestration.
- `IdempotencyService` owns command idempotency and replay behavior.

**Data Boundaries:**

- PostgreSQL stores workflow state, audit events, metadata, idempotency, links, and operation records.
- Filesystem stores artifact payloads and runner input/output payloads.
- Runner outputs enter workflow state only through artifact operations.
- Raw logs and runner outputs are local-only unless sanitized/exported.

### Project Structure Refinements

- First-class backend concerns have explicit application packages: `idempotency`, `export`, `security`, and `diagnostics`.
- Explicit persistence entities/repositories exist for idempotency records, artifact operations, runner executions, integration links, and recovery actions.
- Frontend workflow-specific TanStack Query hooks live under `features/workflows/hooks`.
- Frontend empty, stale, conflict, no-actions, and failed-load states live under `components/feedback`.
- Frontend app shell/navigation components live under `components/layout`.
- Runner JSON schemas live under `runner-contracts/src/main/resources/schemas`.
- Runner fixtures live under `runner-contracts/src/test/resources/fixtures`.
- Runner contract validation code lives under `runner-contracts/src/main/java`.
- Backend contract test packages cover API, runner, redaction, and export contracts.
- Root-level `scripts/` contains developer/CI entry points. `infra/` keeps observability and Compose-adjacent configuration.

### Project Structure Clarifications

- Maven module artifact IDs are `deliveryline-backend`, `deliveryline-frontend`, and `deliveryline-runner-contracts`.
- `_bmad-output/planning-artifacts` remains the planning artifact location during BMad workflows. The root `docs/` directory contains curated implementation-facing documentation derived from finalized planning artifacts. Do not maintain divergent duplicate architecture documents.
- Root `scripts/` contains executable developer and CI workflows such as `doctor`, `reset-local`, `build-runner-images`, and `export-run`. `infra/` contains infrastructure configuration only.
- Runner implementations under `runners/` are product execution assets. Docker Compose may reference them, but `infra/` does not own their behavior.

### Project Structure Failure Controls

- Backend packaging must depend on a single known frontend build output path. CI should verify the executable jar contains the compiled React assets.
- `runner-contracts` is the source of truth for runner context/result schemas. Backend and runner implementations must consume or validate against those schemas rather than duplicating them.
- Runner implementations under `runners/` must include tests or fixtures proving compatibility with the current `runner-contracts` schemas.
- Root `scripts/` contains developer and CI entry points. `infra/` contains configuration for Compose, observability, and local infrastructure. Do not split executable developer workflows across both.
- Redaction policy orchestration belongs in application-level security/redaction code. Adapters may call it but must not define independent redaction rules.
- Frontend workflow API hooks and query invalidation live under `features/workflows/hooks` or equivalent, not inside arbitrary components.
- Shared runner fixtures live in `runner-contracts`; backend contract tests reference those fixtures.
- Documentation for runner contracts should be generated from or explicitly linked to versioned schema files.
- Generated OpenAPI/client artifacts must have a single documented location and should not be hand-edited.

### Project Structure Ownership and Quality Gates

- **Frontend module role:** `deliveryline-frontend` is a Maven build artifact module for the React/Vite UI. Its canonical output is one documented `dist` directory. The backend packaging step consumes only that output path when bundling static assets.
- **Generated artifact ownership:** Generated OpenAPI/client artifacts must have one documented location and generation command. The architecture must state whether generated files are committed or produced during build; agents must not duplicate generated clients under multiple modules.
- **Runner contract authority:** `deliveryline-runner-contracts` is the source of truth for runner context/result schemas, validation code, and shared fixtures. Backend and runner implementations consume or validate against these contracts; they must not define local schema extensions without updating the contracts module.
- **Runner implementation boundary:** `runners/codex` and `runners/claude` share the same environment variable, mount, context file, result file, and diagnostics contract. Runner-specific behavior must remain behind that shared contract.
- **Redaction enforcement boundary:** Redaction policy orchestration lives in application-level security/redaction code. Adapters may call it, but must not define independent redaction rules or log sensitive payloads before redaction.
- **Root scripts boundary:** Root `scripts/` contains executable developer/CI workflows only. Reusable implementation logic belongs in application modules, not scripts.
- **Fixture ownership:** Runner contract fixtures live in `runner-contracts` and are reused by backend and runner tests. Backend and runner modules must not maintain divergent copies.

### Module Test Layout

- `backend`: unit tests, application service tests, persistence integration tests, API contract tests, architecture boundary tests, redaction/export tests, and bundled-jar smoke tests.
- `frontend`: component tests, route tests, workflow UI state tests, API/query hook tests, and artifact rendering safety tests.
- `runner-contracts`: schema validation tests, valid/invalid fixture tests, compatibility tests, and generated validator tests.
- `runners`: runner image smoke tests and contract compatibility checks against `runner-contracts`.

### Requirements to Structure Mapping

**Workflow initiation and stage visibility (FR1-FR6):**

- `backend/application/workflow`
- `backend/adapters/cli/WorkflowCommands.java`
- `backend/adapters/rest/WorkflowController.java`
- `frontend/src/features/workflows/WorkflowList.tsx`

**Specification approval (FR7-FR13):**

- `backend/application/approval`
- `backend/application/artifact`
- `frontend/src/features/workflows/ApprovalActions.tsx`
- `frontend/src/features/workflows/ArtifactViewer.tsx`

**Implementation review and takeover (FR14-FR21):**

- `backend/application/runner`
- `backend/application/recovery`
- `backend/adapters/runner`
- `frontend/src/features/workflows/RecoveryPanel.tsx`

**Run history and inspectability (FR22-FR29):**

- `backend/application/workflow/WorkflowInspectionService.java`
- `backend/adapters/persistence/entity`
- `frontend/src/features/workflows/WorkflowTimeline.tsx`

**Failure handling and recovery (FR30-FR38):**

- `backend/application/recovery`
- `backend/application/artifact/ArtifactReconciliationService.java`
- `backend/application/diagnostics/DoctorService.java`

**Linear/GitHub integration (FR39-FR44):**

- `backend/adapters/integration/linear`
- `backend/adapters/integration/github`
- `backend/application/integration`

**Governance and audit (FR45-FR47):**

- `backend/domain/registry`
- `backend/application/workflow`
- `backend/adapters/persistence/entity/WorkflowEventEntity`

**Local-first, runner abstraction, context handoff (FR48-FR55):**

- `backend/application/runner`
- `backend/adapters/runner`
- `runner-contracts`
- `runners`
- `scripts`

### Integration Points

**Internal Communication:**

- CLI/REST/UI -> application command models -> application services -> domain/persistence/adapters.
- Frontend -> localhost REST -> application services.
- Runner broker -> Docker/process runner -> versioned file contract -> artifact operations.

**External Integrations:**

- Linear adapter owns ticket import/sync.
- GitHub adapter owns PR/branch/link references.
- Docker runner adapter owns container execution.
- PostgreSQL owns durable state.
- Filesystem owns payload storage.

**Data Flow:**

1. Ticket reference enters through CLI/REST.
2. Application creates workflow run and event.
3. Runner broker creates context bundle. For spec-stage runs (per Epic 3a), the bundle carries a reference to a cloned repository workspace so the spec runner has codebase context, not just ticket text (see `docs/adr/0004-spec-stage-orchestration.md`).
4. Runner writes result file.
5. Artifact operation validates and records payload.
6. Review UI inspects run, artifact, and allowed actions.
7. Approval/recovery command mutates workflow through transition service.
8. Export creates redacted shareable report.

### File Organization Patterns

**Configuration Files:**

- Root: Maven parent POM, Compose files, environment examples, CI workflows.
- Backend: Spring profiles and Flyway migrations.
- Frontend: Vite, TypeScript, Tailwind, and shadcn configuration.
- Runner contracts: schema files and fixtures.
- Infra: observability configuration.

**Source Organization:**

- Backend source follows domain/application/adapters/infrastructure boundaries.
- Frontend source follows routes/features/components/lib/styles boundaries.
- Runner contracts own schemas, fixtures, and validation code.
- Runner implementations live under root `runners/`.

**Test Organization:**

- Backend test tiers live under `backend/src/test`.
- Frontend tests live under `frontend/src/test` and near components where appropriate.
- Runner contract tests and fixtures live under `runner-contracts/src/test`.
- Runner image smoke tests live with runner implementation directories or CI scripts.

**Asset Organization:**

- React build output comes from the frontend module's canonical `dist` directory.
- Backend packaged static assets are generated from that output.
- Artifact payload storage is runtime data, not source-controlled assets.
- Runner input/output files are runtime data validated by contract schemas.

### Development Workflow Integration

**Development Server Structure:**

- Backend runs via Maven/Spring Boot.
- Frontend can run through Vite in development, but packaged output is served by Spring Boot.
- PostgreSQL starts through Docker Compose.
- Runner images are built from root `runners/`.

**Build Process Structure:**

- Root Maven build orchestrates backend, frontend, and runner-contract modules.
- Frontend Maven module runs Vite build.
- Backend packaging copies frontend build output into static assets.
- CI validates backend tests, frontend build/tests, runner contract tests, OpenAPI contracts, runner image compatibility, export/redaction verification, and jar packaging.

**CI Gate Order:**

1. Validate formatting/static checks.
2. Validate runner contracts and schema fixtures.
3. Build frontend and run frontend tests.
4. Build backend and run backend unit/application tests.
5. Run API, architecture, persistence, redaction, and export contract tests.
6. Build runner images and run runner contract compatibility checks.
7. Package executable jar with bundled frontend assets.
8. Run bundled-jar smoke checks with Docker-backed PostgreSQL.
9. Verify exported report redaction and deterministic structure.

**Deployment Structure:**

- MVP deployable artifact is the executable Spring Boot jar.
- PostgreSQL and runner images remain local prerequisites.
- Hosted deployment is explicitly deferred.

## Architecture Validation Results

### Coherence Validation

**Decision Compatibility:**
The architecture is coherent. Spring Boot, Java 21, Maven multi-module structure, PostgreSQL, Flyway, Spring Shell, REST/OpenAPI, bundled React, Dockerized runners, and local-only infrastructure work together without major contradictions.

The only deliberate complexity increase is React in MVP plus dual CLI/REST from day one. This is controlled by shared application use cases, localhost-only REST, backend-owned workflow state, OpenAPI contract tests, and Maven-driven packaging.

**Pattern Consistency:**
Implementation patterns support the architecture:

- PostgreSQL `snake_case` aligns with Flyway SQL and explicit relational tables.
- Clean/hexagonal Java packages align with CLI/REST adapter boundaries.
- camelCase JSON aligns with React/TypeScript and Jackson.
- Problem Details, stable domain codes, idempotency keys, and query key factories align with API/frontend consistency.
- Central registries and enforcement tests reduce multi-agent drift.

**Structure Alignment:**
The Maven multi-module structure supports all major decisions:

- `backend` owns Spring Boot, workflow, persistence, API, CLI, redaction, export, and diagnostics.
- `frontend` owns the minimal React review UI.
- `runner-contracts` owns runner schemas, fixtures, and validators.
- `runners` owns executable/container runner implementations.
- `infra` owns local infrastructure configuration.
- `scripts` owns executable developer/CI workflows.

### Requirements Coverage Validation

**Functional Requirements Coverage:**
All 55 functional requirements have an assigned architectural home and supporting design decisions.

- FR1-FR6 workflow initiation and visibility: `workflow` application services, CLI, REST, and workflow list UI.
- FR7-FR13 specification review and approval: approval service, artifact service, frontend approval actions, version/idempotency conflict rules.
- FR14-FR21 implementation review and takeover: runner broker, recovery service, artifact lineage, frontend recovery/takeover panel.
- FR22-FR29 run history and inspectability: workflow events, inspection service, timeline UI, structured logs, audit records.
- FR30-FR38 failure handling and recovery: transition service, recovery service, artifact operations, reconciliation, failure categories.
- FR39-FR44 Linear/GitHub integration: integration adapters and integration link service.
- FR45-FR47 governance and audit: audit events, actor/role metadata, central registries.
- FR48-FR55 local-first, runner abstraction, context handoff: Spring Boot jar, Dockerized runners, runner contracts, context bundles, local exports.

Some requirements still need implementation-level detail, especially Linear/GitHub behavior, exact workflow transition rules, context bundle inspection, failure taxonomy handling, runner schemas, and export/redaction schemas.

**Non-Functional Requirements Coverage:**
The architecture covers the NFRs through:

- Durable state: PostgreSQL, state/event atomicity, Flyway.
- Recovery: artifact operations, reconciliation, idempotency, failure matrix.
- Security/redaction: local trusted user model, config permission checks, redaction on capture/export, data classification.
- Local-first operation: executable jar, Docker Compose PostgreSQL/runners, doctor command, local-only deployment.
- Inspectability: workflow events, timeline UI, structured logs, OpenAPI, doctor JSON output.
- Performance: no cache for MVP, indexed DB reads, summary columns where needed.
- Retention/auditability: explicit artifact metadata, file payloads, event history, export rules.

### Implementation Readiness Validation

**Decision Completeness:**
Critical decisions are documented with rationale, alternatives, consequences, quality gates, and guardrails. The architecture gives implementation agents enough specificity to avoid re-deciding stack, data, API, security, frontend, infrastructure, and structure.

**Structure Completeness:**
The project tree is specific and maps requirements to modules, packages, tests, scripts, docs, and external runner assets. Boundaries are explicit enough for agents to work in parallel.

**Pattern Completeness:**
Naming, formatting, communication, process, ID, event, error, registry, logging, test, and enforcement patterns are defined. Known drift risks have explicit rejection criteria.

### Gap Analysis Results

**Critical Gaps:**
No architecture-level gaps currently block a foundation implementation slice.

**Important Gaps:**

- Exact workflow state transition table still needs to be written during the first implementation slice.
- Concrete database schema details remain to be designed in Flyway migrations.
- Exact runner context/result JSON schemas need to be created in `runner-contracts`.
- Exact OpenAPI endpoint schemas need to be generated or defined.
- Exact redaction pattern list and export report schema need implementation-level specification.

These are not architecture blockers because the architecture defines where they live, who owns them, and how they are validated.

**Nice-to-Have Gaps:**

- Future OS keychain support.
- Future hosted deployment model.
- Future local API token/RBAC.
- Future HTTP or queue-based runner protocol.
- Future app Docker image.
- Future analytics/dashboard expansion.

### Validation ADR Summary

**Decision:** Mark the architecture ready for implementation after completing project context analysis, starter evaluation, core architectural decisions, implementation patterns, and project structure, with the explicit requirement that the first implementation slice establishes foundation contracts before feature work.

**Rationale:**
The architecture now defines the technical stack, runtime topology, data approach, security posture, API style, runner communication model, frontend architecture, infrastructure model, implementation patterns, enforcement gates, and project structure. Remaining gaps are specific implementation artifacts with assigned owners and locations, not unresolved architectural choices.

**Conditions for Readiness:**
Implementation may proceed if the first slice creates the shared contracts that prevent downstream drift: state transition table, initial schema, central registries, runner schema v1, command model pattern, Problem Details mapping, redaction policy, Maven module structure, and package-boundary tests.

**Consequences:**
Feature implementation should wait until foundation contracts are available. Implementation agents should treat this architecture document as authoritative for structure, naming, boundaries, and consistency rules. Any deviation should be handled as an architecture change, not as local implementation discretion.

### Readiness Caveat: Foundation Contracts First

The architecture is ready for implementation with one caveat: feature implementation should not begin until a foundation contract slice exists.

The first implementation slice should establish:

- canonical workflow state transition table and transition tests
- initial Flyway schema for workflow core tables
- central registries for states, event types, domain error codes, failure categories, artifact types, statuses, allowed actions, and ID prefixes
- runner context/result schema v1 in `runner-contracts`
- shared application command model pattern used by CLI and REST
- initial Problem Details/domain error mapping
- initial redaction/data classification policy and test fixtures
- root Maven module structure and package boundary tests

This slice prevents parallel agents from encoding incompatible assumptions in workflow, persistence, API, runner, and frontend code.

### Final Validation Caveat

Architecture validation passes for a controlled foundation contract implementation slice. It should not be read as blanket readiness for broad feature delivery.

The first implementation slice must prove the high-risk contracts before feature expansion:

- workflow state-transition table with illegal-transition tests
- Flyway schema v1 with migration replay verification
- central registries and registry drift tests
- runner context/result schema v1 with contract tests
- shared command model pattern across CLI and REST
- Problem Details conformance across REST and CLI/Shell error surfaces
- redaction fixtures for logs, exports, artifacts, and failure paths
- package-boundary enforcement in CI
- Compose smoke checks covering backend, PostgreSQL, and runner integration
- idempotency tests for duplicate command submission and retry behavior

Once this foundation slice is implemented and passing, the architecture can safely guide feature implementation across the governed workflow.

### Architecture Completeness Checklist

**Requirements Analysis**

- [x] Project context analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped

**Architectural Decisions**

- [x] Data architecture defined
- [x] Security posture defined
- [x] API and communication patterns defined
- [x] Frontend architecture defined
- [x] Infrastructure and deployment defined

**Implementation Patterns**

- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Error/idempotency/logging patterns documented
- [x] Enforcement quality gates documented

**Project Structure**

- [x] Maven modules defined
- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

### Architecture Readiness Assessment

**Overall Status:** READY FOR FOUNDATION IMPLEMENTATION

**Confidence Level:** High for architecture consistency and multi-agent implementation guidance. MVP delivery risk remains medium-high because Dockerized runner behavior, agent output quality, Linear/GitHub integration, local setup friction, and React/backend packaging still carry execution risk.

**Key Strengths:**

- Strong governance model around state, events, artifacts, approvals, and recovery.
- Clear adapter boundaries for CLI, REST, persistence, runners, files, and integrations.
- Explicit handling of local-first security and redaction.
- Strong contract strategy for APIs, runner files, idempotency, and frontend actions.
- Concrete project structure and test/CI gates for multi-agent implementation.

**Areas for Future Enhancement:**

- Hosted deployment and multi-user security.
- Stronger secret storage through OS keychain.
- HTTP/queue runner protocol if runner orchestration grows.
- Analytics and workflow optimization features.
- Broader policy engine and configurable workflows.

### Implementation Handoff

**AI Agent Guidelines:**

- Follow all architectural decisions exactly as documented.
- Do not introduce new states, events, error codes, failure categories, artifact types, statuses, allowed actions, ID prefixes, or runner schema versions outside central registries.
- Keep adapters thin and business rules in application/domain services.
- Use runner contracts and API contracts as enforceable artifacts.
- Treat pattern violations as architecture defects.

**First Implementation Priority:**
Initialize the Maven multi-module project with:

- `deliveryline-backend`
- `deliveryline-frontend`
- `deliveryline-runner-contracts`
- root `runners`, `infra`, `scripts`, and `.github/workflows`

Then implement the foundation contract slice before feature work:

- transition table and tests
- Flyway schema v1
- central registries
- runner schema v1
- shared command model pattern
- Problem Details mapping
- redaction fixtures
- package-boundary tests
