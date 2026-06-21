---
stepsCompleted:
  - step-01-validate-prerequisites
  - step-02-design-epics
inputDocuments:
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\prd.md
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\architecture.md
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\ux-design-specification.md
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\implementation-spec-2026-04-20-agent-orchestration.md
project_name: DeliveryLine
user_name: Alex
---
# DeliveryLine - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for DeliveryLine, decomposing the requirements from the PRD, UX Design Specification, and Architecture decisions into implementable stories.

## Requirements Inventory

### Functional Requirements

**Workflow Initiation & Ticket Governance**

- FR1: Product Managers can initiate a governed workflow from a low-risk ticket reference.
- FR2: Product Managers can associate a governed workflow run with a source ticket reference.
- FR3: The system supports one governed low-risk ticket workflow in Phase 1.
- FR4: Product Managers can see the current workflow stage for each governed ticket.
- FR5: Authorized users can view the linkage between a ticket, its workflow run, and its related artifacts.
- FR6: Authorized users can see the current pending action required to move a governed ticket forward.

**Specification Capture & Product Approval**

- FR7: Product Managers can capture or review a specification for a governed ticket.
- FR8: Product Managers can approve a specification for progression to implementation.
- FR9: Product Managers can reject a specification and provide structured feedback.
- FR10: Authorized users can see the currently approved specification state for a governed ticket.
- FR11: Authorized users can review prior specification states and changes before approving a revision.
- FR12: The workflow can prevent implementation progression until a specification has been accepted from a product perspective.
- FR13: The workflow can expose unresolved specification loops for human escalation.

**Implementation Output & Developer Review**

- FR14: Developers can access the approved specification and related workflow context before reviewing implementation output.
- FR15: Developers can review implementation output associated with a governed ticket.
- FR16: Developers can accept implementation output as technically ready for merge-ready handoff.
- FR17: Developers can reject implementation output and provide structured technical feedback.
- FR18: Developers can take over a governed ticket after agent-produced work without losing prior workflow context.
- FR19: The workflow can preserve artifact lineage and run history across developer takeover.
- FR20: Authorized users can see the relationship between implementation output, PR linkage, and review outcome.
- FR21: Authorized users can see separate product acceptance and technical acceptance states for a governed ticket.

**Run History, Visibility & Inspectability**

- FR22: Authorized users can inspect the stage-by-stage history of a governed run.
- FR23: Authorized users can see who or what acted at each workflow step.
- FR24: Authorized users can see what artifacts were produced or changed during a run.
- FR25: Authorized users can see prior state, resulting state, and intervention markers for workflow actions.
- FR26: Workflow Owners can inspect active, failed, stalled, and manually overridden runs.
- FR27: Authorized users can determine what changed after each feedback cycle.
- FR28: Authorized users can see why a workflow step changed state after feedback, intervention, or recovery action.
- FR29: Workflow Owners can query audit history by ticket and by run.

**Failure Handling, Recovery & Reconciliation**

- FR30: Authorized users can see when a run has failed or stalled and where it stopped.
- FR31: Workflow Owners can rerun a failed or rejected workflow step without erasing prior history.
- FR32: Workflow Owners can record retry or rerun actions as recovery actions linked to the failed step.
- FR33: Developers can continue a workflow manually after takeover while preserving prior run context.
- FR34: Workflow Owners can record recovery actions in the same governed history as normal execution.
- FR35: Workflow Owners can reconcile workflow state when an integration conflict is detected.
- FR36: Authorized users can see the current state, last known good state, and next safe action during recovery.
- FR37: Workflow Owners can apply a failure category to each failed pilot-scope run.
- FR38: Workflow Owners can apply and review a governed failure taxonomy for failed runs.

**Integration & State Integrity**

- FR39: The workflow can link governed tickets to Linear ticket references.
- FR40: The workflow can link governed implementation output to GitHub / PR references.
- FR41: Workflow Owners can detect disagreement between internal workflow state and external integration state.
- FR42: Workflow Owners can review integration conflicts without silent overwrite of conflicting state.
- FR43: Workflow Owners can distinguish sync failures, link failures, and state conflicts in the operational record.
- FR44: Workflow Owners can manually preserve ticket linkage, artifact linkage, and recovery history when automated integration behavior fails.

**Governance, Accountability & Approval Boundaries**

- FR45: The system can record whether an action was system-generated, agent-executed, human-approved, or human-overridden.
- FR46: Authorized users can see which role approved a specification, which role approved implementation output, and which role performed recovery actions.
- FR47: Authorized users can inspect an append-only history of human, agent, and system actions for each run.

**Local-First Pilot Use, Runner Abstraction & Context Handoff**

- FR48: Pilot users can operate the governed workflow from a local-first environment in Phase 1.
- FR49: The workflow can preserve run state and history across local interruptions.
- FR50: Team members can access shared run history and artifacts generated from local-first workflow execution in a form suitable for review.
- FR51: Pilot users can use familiar coding agents within the governed workflow rather than replacing them with a new agent interface.
- FR52: Workflow Owners can inspect local-first run records and exported history without requiring centralized operations tooling.
- FR53: The workflow can dispatch agent work through a common runner abstraction that records normalized output, artifacts, and failure state.
- FR54: The workflow can create context bundles from ticket data, approved specifications, prior feedback, artifact references, and workflow state for use by later workflow steps.
- FR55: Authorized users can inspect the context bundle used for an agent step when reviewing output, diagnosing failure, or taking over work.

### NonFunctional Requirements

**Reliability, Recovery & Inspectability**

- NFR1: A run must use explicit states: `running`, `paused`, `failed`, `taken_over`, `reconciled`, and `completed`.
- NFR2: A workflow run must preserve current state, last safe checkpoint, last durable event, produced artifacts, and audit history after interruption, restart, or agent failure.
- NFR3: A failed or stalled run must expose failed stage, last successful stage, failure category, last activity time, and next safe action.
- NFR4: Retry, rerun, manual takeover, and reconciliation actions must append history and must never erase or mutate prior history.
- NFR5: When workflow or integration state is uncertain, the system must pause progression and require explicit human recovery.
- NFR6: Durable workflow events must be written so interruption does not leave the run unreadable or partially corrupted.
- NFR7: A reviewer must be able to answer what happened, what changed, who acted, what failed, and what is next from the inspection view without reading raw agent logs first.

**Security, Redaction & Share Boundaries**

- NFR8: Linear, GitHub, and agent-provider credentials must not be committed to the repository or stored in generated artifacts.
- NFR9: Local configuration and credentials must be scoped by user and repository.
- NFR10: Shared/exported run artifacts must redact secrets, private tokens, and unnecessary local machine paths by default.
- NFR11: The local store must distinguish private working data from shareable/exported review data.
- NFR12: Human, agent, and system actions must be attributable to an actor identity or service identity in the audit trail.
- NFR13: The MVP must define which data is safe to share with teammates and which data remains local-only.
- NFR14: Context bundles prepared for agent execution must avoid including credentials, private tokens, or unrelated local-only data.

**Integration & Identity Integrity**

- NFR15: The system must define the system of record for run state, ticket identity, repository identity, branch/commit lineage, artifact linkage, and PR linkage.
- NFR16: One governed run must map to one ticket, one repository context, and one implementation lineage unless a human explicitly reconciles the record.
- NFR17: Linear ticket linkage and GitHub/PR linkage must be durable enough that a reviewer can reconstruct which ticket, repo, branch/commit lineage, artifacts, and PR belong to a run.
- NFR18: Integration writes and sync operations must be idempotent where practical and must detect conflicts before changing workflow state.
- NFR19: The system must not silently overwrite conflicting internal and external state.
- NFR20: The system must prevent or clearly flag attempts to attach implementation output, artifacts, or PR references to the wrong ticket or wrong run.
- NFR21: When ticket, run, repository, artifact, or PR identity is ambiguous, the system must pause and require human confirmation.
- NFR22: Integration failures must be classified as sync failure, link failure, state conflict, or network/API failure.
- NFR23: Integration freshness expectations must be explicit, including whether status depends on polling, manual refresh, or direct API reads.
- NFR24: Runner execution records must link normalized runner output, raw output reference when retained, produced artifacts, and the context bundle used for the step.

**Performance & Freshness**

- NFR25: Local inspection of a single run's current status should return within 2 seconds for normal pilot-size run histories.
- NFR26: Local inspection of a single run's stage history should return within 5 seconds for normal pilot-size run histories.
- NFR27: For MVP measurement, a normal pilot-size run history means up to 100 durable workflow events and up to 25 linked artifacts for one ticket run.
- NFR28: Performance targets apply to inspection/read paths, not to agent implementation execution time.
- NFR29: Workflow status must be available without waiting for agent execution to complete.
- NFR30: Long-running agent work must expose current stage, last activity time, latest durable event, and freshness/staleness indicator.

**Data Retention & Auditability**

- NFR31: MVP run history and artifacts must be retained for at least 60 days by default unless manually archived or deleted.
- NFR32: Audit history must be append-only from the product perspective; corrections must be represented as new events rather than mutation of prior events.
- NFR33: Failure taxonomy values used on historical runs must remain interpretable if the taxonomy changes later.
- NFR34: Run records must be inspectable by ticket reference and run identifier.
- NFR35: The system must define what happens to run history when a ticket is closed, archived, or removed from the source system, including whether tombstone records are preserved.

**Local-First Operability**

- NFR36: The MVP must run from a supported local development environment without requiring a hosted control plane.
- NFR37: The MVP must define supported pilot environment assumptions, including operating system, shell, Git repository access, Linear access, GitHub access, and agent tool availability.
- NFR38: Local persisted state must survive normal interruption and allow the user to inspect, resume, or take over a run.
- NFR39: The system must define where local state, logs, artifacts, private working data, and exported review history are stored.
- NFR40: The first-release setup path must avoid platform-engineering support and should be completable by a pilot developer or workflow owner.
- NFR41: Exported or shared run history must include enough context for a teammate to inspect status, artifacts, decisions, failures, and next action without access to the originating local machine.

**Usability & Adoption**

- NFR42: A pilot user must be able to run one low-risk ticket through the guided workflow using documented setup and tutorial material.
- NFR43: The product should minimize new workflow concepts beyond ticket, spec, run, artifact, review, failure, and recovery action.
- NFR44: The MVP should optimize for understandable recovery and inspection over maximum automation.
- NFR45: First-run documentation must include a happy-path tutorial and at least one failed-run recovery walkthrough.

### Additional Requirements

**Starter Template (from Architecture — impacts Epic 1 Story 1)**

- AR1: Initialize backend via Spring Initializr with **Spring Boot 4.0.6** (Java 21, Maven 3.9.4+, jar packaging), dependencies: Spring Web (`spring-boot-starter-webmvc` on the Boot 4 line), Data JPA, PostgreSQL driver, Flyway, Validation, Actuator, Docker Compose support, Testcontainers; add **Spring Shell 4.0.2** manually if not available from Initializr (use `spring-shell-dependencies:4.0.2` BOM); Picocli fallback if Spring Shell incompatible.
- AR2: Initialize frontend later as a Vite React TypeScript project (`npm create vite@latest deliveryline-ui -- --template react-ts`), Node.js 20.19+ or 22.12+.
- AR3: Adopt Maven multi-module root with `deliveryline-backend`, `deliveryline-frontend`, `deliveryline-runner-contracts`; root-level `runners/`, `infra/`, `scripts/`, `docs/`, `.github/workflows/`.

**Foundation Contract Slice (required before feature stories per Architecture readiness caveat)**

- AR4: Publish canonical workflow state-transition table with explicit states, terminal states, allowed transitions, invalid-transition behavior, and retry/resume/takeover/reconciliation entry points — with illegal-transition tests.
- AR5: Create Flyway `V1__create_workflow_core_tables.sql` establishing `workflow_runs`, `workflow_events`, `artifacts`, `artifact_operations`, `approvals`, `runner_executions`, `integration_links`, `recovery_actions`, `idempotency_records` with enum-as-string columns, `timestamptz` timestamps, and proper FK/unique constraints; migration replay verification.
- AR6: Establish central registries (`DomainRegistry`, `DomainErrorCode`, `AllowedAction`, workflow states, event types, failure categories, artifact types, artifact operation statuses, runner execution statuses, data classifications, ID prefixes, runner schema versions) with registry drift tests comparing registry values against enums, API schemas, frontend allowed actions, and fixtures.
- AR7: Define runner context/result JSON schema v1 in `deliveryline-runner-contracts` with `context-bundle.v1.schema.json` and `runner-result.v1.schema.json`, a `RunnerContractValidator`, and valid/invalid fixtures; contract tests must reject missing fields, unknown schema versions, bad checksums, duplicate execution IDs, stale metadata, malformed classification, partial writes, oversized files, and path traversal.
- AR8: Implement shared application command model pattern so CLI (Spring Shell) and REST (`/api/v1`) adapters translate into the same commands (`submit`, `advance`, `approve`, `reject`, `recover`, `replay`, artifact lookup) and produce equivalent stable domain error codes.
- AR9: Implement Problem Details (`application/problem+json`) mapper with required fields (`type`, `title`, `status`, `detail`, `instance`, `code`, `retryable`) and optional machine-readable `details`.
- AR10: Implement initial redaction/data classification policy (`RedactionPolicyService`, `DataClassificationService`) covering local-only / shareable-redacted / shareable-full / derived-public-safe classifications, plus an **adversarial redaction fixture set** exhausting known credential formats (Linear API keys + token prefixes, GitHub personal-access-token shapes, SSH public/private key blocks, `.env` variable leaks, YAML/JSON-embedded secrets, Authorization header leakage, secrets embedded in URL query parameters, absolute-path leaks that reveal user directories, process-environment leakage). Happy-path redaction fixtures alone are insufficient — the fixture set is maintained as a living list and any exported artifact containing an un-fixtured secret format is a product defect. Fixtures cover logs, exports, artifacts, context bundles, runner output, and failure-path traces.
- AR11: Enforce Java package boundaries with ArchUnit tests covering `domain`, `application`, `adapters.cli`, `adapters.rest`, `adapters.persistence`, `adapters.runner`, `adapters.files`, `adapters.integration.linear/github`, `infrastructure.*`; reject CLI/REST controllers calling repositories directly or containing orchestration/approval/recovery logic.
- AR12: Implement idempotency service with `Idempotency-Key` header (REST) and `--idempotency-key` (CLI); records persist key, command type, actor identity, command fingerprint, status, result reference; same key + same fingerprint replays, same key + different fingerprint fails with `IDEMPOTENCY_KEY_CONFLICT`; tests cover uniqueness, replay, conflict, and retry semantics.

**Core Workflow Services & Data (Architecture)**

- AR13: Implement `WorkflowTransitionService` as the single path for state transitions; owns the transaction that updates `workflow_runs.current_state` and appends the matching `workflow_events` row atomically; enforce via architecture/contract tests.
- AR14: Implement artifact operations as transactional outbox-style records (`ArtifactOperationService`, `ArtifactReconciliationService`); artifact metadata without an `available` verified payload (checksum + storage reference) is not approval-eligible; detect orphan payloads and missing files via reconciliation.
- AR15: Implement `ApprovalService` so each approval binds to a specific artifact version, context bundle version, workflow state, actor identity, reviewer role, decision, reason, and invalidation rule when later artifacts change.
- AR16: Implement `RunnerBroker` + `ContextBundleService` + runner adapters (Docker-based `DockerRunnerAdapter`, deterministic `MockRunnerAdapter` for tests, `FileRunnerContractReader`); broker owns runner execution identity, lifecycle, timeout, heartbeat/last activity, lease expiry, stale execution cleanup, idempotent restart.
- AR17: Implement `RecoveryService` supporting distinct actions: retry, resume, takeover, pause, reconcile — each persisted with evidence, success criteria, expected final state, and linked to failed step/event.
- AR18: Implement `IntegrationLinkService` with Linear adapter (polling intake, idempotent by ticket + repository) and GitHub adapter (PR/branch/link references preserving branch, commit, artifact, and run relationships); source-of-truth rules per field, conflict detection without silent overwrite.
- AR19: Implement `WorkflowInspectionService` exposing `current owner/actor, current state, current blocking reason, last successful step, latest artifact version per stage, last external sync status, failure reason, next safe action` — target pilot user can reconstruct run state in under a minute.
- AR20: Implement `RunExportService` producing redacted shareable run reports with deterministic structure; redaction verified by tests for secrets, tokens, hostnames, user identifiers, and local paths.

**API, CLI & OpenAPI (Architecture)**

- AR21: Implement REST endpoints under `/api/v1`: resource-oriented reads (plural nouns), explicit command/action mutation endpoints in kebab-case (e.g., `POST /workflows/{workflowRunId}/approve-spec`); JSON bodies in `camelCase`; domain error codes in uppercase snake_case.
- AR22: Publish OpenAPI via `springdoc-openapi` with contract tests covering request/response shape, status codes, Problem Details payloads, idempotency requirements, and command endpoint semantics.
- AR23: Implement Spring Shell CLI commands as thin adapters over shared application command models; `DoctorCommand`, workflow commands, runner commands.

**Infrastructure, CI & Observability (Architecture)**

- AR24: Provide `docker-compose.yml` running PostgreSQL plus agent runner image definitions (Codex, Claude) as build/profile anchors — not always-on workers; runner lifecycle controlled by the broker.
- AR25: Provide optional `docker-compose.observability.yml` with Prometheus/Grafana/Loki under a `demo-observability` profile; must NOT be required for normal workflow execution, tests, or recovery.
- AR26: Implement `DoctorService` + `DoctorCommand` checking Java version, Spring profile, PostgreSQL connectivity, Flyway state, required directories, config file permissions, Docker availability, runner image availability, local REST bind address, frontend asset presence — stable human-readable + machine-readable JSON output with stable exit codes and stable infrastructure error codes.
- AR27: Configure Spring profiles `local`, `test`, `demo` with documented property precedence and allowed overrides; `demo` may seed data but must NOT bypass workflow rules, approvals, validation, redaction, or recovery.
- AR28: Configure GitHub Actions CI with tiered gates: formatting/static checks → runner contracts/fixtures → frontend build + tests → backend unit/application tests → API/architecture/persistence/redaction/export contract tests → build runner images + compatibility checks → package executable jar with bundled frontend → Docker-backed bundled-jar smoke tests → verify exported report redaction.
- AR29: Implement structured logging with correlation fields: `correlationId`, `workflowRunId`, `runnerExecutionId`, `artifactId`, `artifactOperationId`; never log secrets, raw credentials, unredacted context bundles, or unredacted runner output.
- AR30: Implement REST localhost-only binding with fail-closed behavior for non-loopback binding unless explicit unsafe development override is provided; local config/secret file permission checks where OS supports.
- AR31: Provide `scripts/` with `doctor.{ps1,sh}`, `reset-local.{ps1,sh}`, `start-all.{ps1,sh}` (convenience wrapper for `docker compose --profile observability up -d` against the unified compose file from AR24), and `export-run.{ps1,sh}` (E5 placeholder in E1) as root-level developer/CI entry points; reusable logic stays in application modules, not scripts. The prior `build-runner-images.{ps1,sh}` script is removed — runner images are build targets in the unified `docker-compose.yml` (AR24), so `docker compose build` rebuilds them without a separate script.

**Frontend Packaging (Architecture)**

- AR32: Frontend Maven module (`deliveryline-frontend`) runs Vite build producing a single canonical `dist/` directory; backend packaging copies only that output into static assets; CI fails if frontend assets cannot be produced.
- AR33: Spring Boot serves bundled React SPA with `SpaFallbackController` supporting direct refresh of React routes without masking missing API endpoints; SPA fallback tests cover route-not-found, API path collisions, REST 404s.

**Pilot Measurement Instrumentation (derived from PRD success criteria)**

- AR34a (belongs in Epic 1 — measurement capture): Flyway V1 and domain events must capture the measurement data required by the PRD success criteria from run #1 onward: per-event timestamps (capable of deriving per-stage wall-clock and active time), a structured review-feedback taxonomy column (missing scope, unclear specification, misunderstood implementation intent) on rejection events, and enough event-type granularity to compute cycle time, rework rate, and adoption counts (runs per sprint per team). Capture must precede enforcement — the schema cannot be retrofitted onto historical runs, so these columns/events must land in E1's Flyway V1, not E5.
- AR34b (belongs in Epic 5 — measurement surfacing): Surface the captured measurement data via CLI reports (cycle time, rework rate, adoption counters) and include it in exported run reports, so pilot teams can answer *"did median cycle time improve vs baseline?"*, *"did rework rate decrease?"*, and *"what fraction of eligible tickets went through the workflow?"* without requiring direct database access. Baseline capture (pre-pilot measurement on the same team's current delivery process) is out of epic scope but should be documented as a prerequisite in E6.

**Integration Mocks (derived from advanced-elicitation risk review)**

- AR35: Provide a deterministic mock Linear adapter and mock GitHub adapter — activated via Spring profile (`test` required; `local`/`demo` optional) — that implement the same internal port as the real adapters, satisfy runner contract behavior, and support idempotent intake/linkage semantics without external API calls. Mocks must be usable for foundation-slice demos, contract tests, and failed-pilot diagnosis when external APIs are unavailable.

**Supported-Environment Matrix (derived from pre-mortem)**

- AR36: Define and implement an explicit **supported-environment matrix** for pilot use — at minimum: Windows 11 + PowerShell + Docker Desktop, macOS 14+ + Docker Desktop, Ubuntu 22.04+ + Docker Engine (WSL2 supported as a Linux environment). For each supported combination, ship install/doctor/reset scripts (`.ps1` for PowerShell, `.sh` for bash) and ensure `application-local.yml` path handling is cross-platform. The `doctor` command must report which supported combination is detected and fail-closed with actionable diagnostics on unsupported or untested combinations. NFR37 is satisfied by this matrix; vague support claims ("runs locally") are not sufficient.

### UX Design Requirements

**Foundation & Design System**

- UX-DR1: Scaffold `shadcn/ui + Tailwind` design system with documented primitive inventory (buttons, inputs, textareas, labels, dialogs, sheets, popovers, dropdowns, selects, tabs, badges, alerts, tables, cards, tooltips, scroll areas, accordions, separators, toasts); primitives remain generic and reusable.
- UX-DR2: Implement a neutral/calm color token system with semantic tokens distinguishing informational, success/approved, warning, blocker, draft/inactive, selected/focused, loading, error, permission-restricted, empty, stale, and recovery states; blocker/warning strong and obvious; no semantic state conveyed by color alone.
- UX-DR3: Implement a typography hierarchy token set for page/panel titles, workflow state/section headings, artifact/body content, metadata/captions/secondary labels, and inline status/annotation text — supporting long-form reading and rapid scanning with a common modern sans-serif stack.
- UX-DR4: Implement a hybrid spacing system using a 4px rhythm (tight internal, compact metadata, dense review) and an 8px rhythm (panel spacing, section separation, larger layout).

**Application Shell**

- UX-DR5: Build the tri-pane application shell (Direction 1 + Direction 2 influence) with left navigation rail/sidebar for queue/state navigation, central main review pane, and a right-side supporting context panel; stable placement of workflow state; artifact primacy enforced (center pane is the visual anchor, side panels collapse before artifact shrinks).
- UX-DR6: Implement routing with TanStack Router and typed route params for workflow and artifact IDs; explicit UI handling for deep links, missing workflows, missing artifacts, unsupported routes, and unsupported workspace states; routes: `/` (WorkflowsRoute), `/workflows/{workflowRunId}` (WorkflowDetailRoute), artifact viewer route.
- UX-DR7: Implement server-state management with TanStack Query, using typed query key factories (`workflowKeys.list`, `.detail`, `.events`, `.artifacts`); mutations invalidate affected workflow detail, timeline, artifact, approval, and pending-review queries.

**Core MVP Workflow Composites (Phase 1)**

- UX-DR8: Build `Run / Review Queue Item` component with ticket/run identifier, concise title/summary, current stage/status, primary attention indicator, artifact type, age/updated timestamp, optional assignee hint, trust signals (blocker count, open-question count, stale indicator); states: default, hover, selected, unread, blocked, stale, disabled; variants: reviewer queue item, operator queue item, compact + standard density; fully keyboard focusable with ARIA label including ticket identity, status, and attention state.
- UX-DR9: Build `Run Context Strip` persistent lightweight component displaying run identifier, current state, current actor/source, latest revision/artifact pointer, last meaningful transition timestamp, optional trigger/branch/commit reference; states: default, stale, partial context, loading, error; grouped as labeled context region with keyboard-readable metadata order.
- UX-DR10: Build `Artifact Review Panel` with artifact title/type, current revision indicator, artifact body/structured content, inline metadata region, optional change summary, optional section anchors, compare mode entry points, anchors into clarification/decision regions; states: default, loading, empty/not-yet-generated, stale, conflicting/superseded, incomplete, error/failed retrieval; variants: specification view, implementation-plan view, PR/output view, read-only, compare-entry-enabled; runner output rendered as untrusted (sanitize markdown/diff, block scriptable payloads, visually separate trusted metadata from generated content).
- UX-DR11: Build `Clarification Region / Open-Questions Block` with question list, status per question, selected question detail, response input area, optional structured choices, submit/resolve action, visible relationship to current artifact state; states: no open questions, unanswered, in progress, answered/pending incorporation, incorporated, blocked/invalid, error; variants: inline, sidebar subregion, compact summary, full response; ARIA live feedback for submitted/incorporated states; distinguish answered vs incorporated explicitly.
- UX-DR12: Build `Approval / Decision Bar` concentrating the current decision point with current decision context, primary action(s), required reason input where relevant, stale/conflict warning, immediate consequence hint, disabled-state explanation, post-submit decision summary; states: ready, blocked, stale, disabled, submitting, success, error, locked; variants: spec approval mode, implementation review mode, recovery/operator decision mode, sticky footer bar, inline section bar; one visually primary governed action per decision area; controls derived from backend-reported allowed actions (no frontend permission inference); sends expected workflow state, artifact version, context version, idempotency key; `409` with `APPROVAL_VERSION_MISMATCH` triggers stale-state UI refresh.

**Core MVP Workflow Composites (Phase 2 — Trust & Verification)**

- UX-DR13: Build `Compare Mode / Revision Delta Summary` with revision A/B identifiers, changed-section summary, side-by-side or before/after comparison surface, changed-region indicators, filter/jump controls, exit-back-to-review control; states: default comparison, loading, no meaningful diff, no baseline available, partial, diff unavailable, error; variants: side-by-side, stacked, summary-first, spec revision compare, plan revision compare; exit returns to same review context with continuity preserved.

**Shell States**

- UX-DR14: Implement Queue Shell States: loading, empty (no runs), filtered-with-no-matches, and error — distinguishable from one another, each with next safe action.

**UX Consistency Patterns**

- UX-DR15: Implement feedback pattern infrastructure that distinguishes submitted / accepted / incorporated / blocked / failed outcomes — workflow-significant state changes must persist in component context, never only in toast; toast reserved for lightweight confirmation.
- UX-DR16: Implement navigation pattern infrastructure preserving run identity, artifact identity, and workflow state across queue → run → compare → clarification → back-navigation; back returns to prior meaningful review context, not a generic top-level page.
- UX-DR17: Implement empty/loading/error state infrastructure attached to affected region (not only global); distinguish absence vs delay vs failure vs restriction; every error state provides next safe action where possible.
- UX-DR18: Implement modal/overlay/confirmation pattern infrastructure: confirmation dialogs for reject-with-reason, approve-when-stale/conflict risk, stop-orchestrator, consequential retry/recover; focus moves into overlay and returns to triggering element on close; escape/keyboard dismissal predictable except where unsafe.
- UX-DR19: Implement button hierarchy infrastructure: one visually primary governed action per decision area; secondary/tertiary actions visually subordinate; destructive actions clearly differentiated; buttons reflect workflow truth (ready, blocked, stale, submitting, completed); if no safe primary action exists, show blocked state rather than promoting an unavailable action; disabled buttons carry adjacent explanation.

**Accessibility (WCAG 2.1 AA)**

- UX-DR20: Meet WCAG 2.1 AA baseline: strong text-to-background contrast; keyboard-accessible operation across the full review workflow; visible and consistent focus states; semantic HTML with labeled regions; semantic heading and landmark structure across queue, run, artifact, context, and decision regions; labeled controls and explicit action text for all governed actions; ARIA live regions for asynchronous workflow updates; consistent announcement of stale/blocked/error/completed states; minimum touch target sizing appropriate for mobile.
- UX-DR21: UI labels must explicitly communicate that MVP roles are recorded audit labels, not enforced authorization; frontend must not gate actions based on audit role labels.

**Responsive Design**

- UX-DR22: Implement desktop-first responsive strategy with standard breakpoints (Mobile 320–767px, Tablet 768–1023px, Desktop 1024px+); desktop = full tri-pane, tablet = reduced tri-pane/two-region with some supporting context collapsed, mobile = single-column artifact-first with progressive disclosure; priority order on narrow screens: preserve artifact reading > preserve decision controls > disclose navigation/supporting context; compare becomes a dedicated bounded mobile state rather than compressed side-by-side.
- UX-DR23: Structural collapse rules: artifact content remains primary before context panels remain visible; decision controls reachable before secondary metadata remains expanded; run identity and current state never disappear during collapse; supporting context moves into drawers/tabs/sheets/accordions before artifact becomes unreadable.

**Testing Strategy**

- UX-DR24: Responsive + accessibility testing coverage: automated a11y checks in dev/CI; keyboard-only navigation testing for all critical journeys (queue → run entry, artifact review, clarification submission, approval/rejection, compare enter/exit, mobile review and decision flow); screen-reader spot checks on critical flows; manual verification of focus order, dialog behavior, and status messaging; contrast validation and color-independent state recognition; real-device mobile validation on a Galaxy S23+ class phone or equivalent; primary flows validated across major modern browsers.

### FR Coverage Map

| FR | Epic | Description |
| --- | --- | --- |
| FR1 | Epic 1 | Governed workflow initiation from low-risk ticket reference (CLI) |
| FR2 | Epic 1 | Associate workflow run with source ticket reference |
| FR3 | Epic 1 | Single governed low-risk ticket workflow in Phase 1 |
| FR4 | Epic 1 / Epic 2 | Current workflow stage visible (CLI baseline in E1, UI in E2) |
| FR5 | Epic 2 | Ticket ↔ run ↔ artifact linkage visible |
| FR6 | Epic 2 | Current pending action surfaced |
| FR7 | Epic 2 | Capture or review specification |
| FR8 | Epic 2 | Approve specification for progression |
| FR9 | Epic 2 | Reject specification with structured feedback |
| FR10 | Epic 2 | Currently approved specification state visible |
| FR11 | Epic 2 | Prior specification states and changes reviewable before approving revision |
| FR12 | Epic 2 | Prevent implementation progression until spec accepted |
| FR13 | Epic 2 | Expose unresolved specification loops for escalation |
| FR14 | Epic 3 | Developer access to approved spec + workflow context before reviewing output |
| FR15 | Epic 3 | Developer reviews implementation output |
| FR16 | Epic 3 | Developer accepts implementation output as merge-ready |
| FR17 | Epic 3 | Developer rejects output with structured technical feedback |
| FR18 | Epic 3 | Developer takeover preserves prior workflow context |
| FR19 | Epic 3 | Artifact lineage and run history preserved across takeover |
| FR20 | Epic 3 | Relationship between implementation output, PR linkage, and review outcome visible |
| FR21 | Epic 3 | Separate product and technical acceptance states |
| FR22 | Epic 1 / Epic 2 | Stage-by-stage history of a governed run (CLI/JSON in E1, UI timeline in E2) |
| FR23 | Epic 1 / Epic 2 | Who or what acted at each workflow step |
| FR24 | Epic 2 | Artifacts produced or changed during a run |
| FR25 | Epic 2 | Prior state, resulting state, intervention markers for workflow actions |
| FR26 | Epic 2 / Epic 4 | Inspect active runs (E2) + failed/stalled/manually overridden runs (E4) |
| FR27 | Epic 2 | Determine what changed after each feedback cycle |
| FR28 | Epic 2 | Why workflow step changed state after feedback/intervention/recovery |
| FR29 | Epic 4 | Query audit history by ticket and by run |
| FR30 | Epic 4 | Failed/stalled runs show where they stopped |
| FR31 | Epic 4 | Rerun failed/rejected step without erasing prior history |
| FR32 | Epic 4 | Record retry/rerun as recovery actions linked to failed step |
| FR33 | Epic 4 | Continue workflow manually after takeover preserving context |
| FR34 | Epic 4 | Recovery actions recorded in same governed history as normal execution |
| FR35 | Epic 4 | Reconcile workflow state on integration conflict |
| FR36 | Epic 4 | Current state, last known good state, next safe action during recovery |
| FR37 | Epic 4 | Apply failure category to each failed pilot-scope run |
| FR38 | Epic 4 | Apply and review governed failure taxonomy |
| FR39 | Epic 1 | Link governed tickets to Linear ticket references |
| FR40 | Epic 3 | Link governed implementation output to GitHub / PR references |
| FR41 | Epic 4 | Detect disagreement between internal and external integration state |
| FR42 | Epic 4 | Review integration conflicts without silent overwrite |
| FR43 | Epic 4 | Distinguish sync failures, link failures, state conflicts |
| FR44 | Epic 4 | Manually preserve ticket/artifact/recovery history when integration fails |
| FR45 | Epic 1 | Record whether action was system/agent/human-approved/human-overridden |
| FR46 | Epic 2 / Epic 4 | Visible role-by-role approval attribution (spec in E2, recovery in E4) |
| FR47 | Epic 1 | Append-only history of human/agent/system actions per run |
| FR48 | Epic 1 | Pilot users operate governed workflow from local-first environment |
| FR49 | Epic 1 | Preserve run state and history across local interruptions |
| FR50 | Epic 5 | Team access to shared run history and artifacts from local-first execution |
| FR51 | Epic 1 | Use familiar coding agents within governed workflow (runner abstraction) |
| FR52 | Epic 1 / Epic 5 | Inspect local-first run records (E1 CLI + JSON, E5 exported reports) |
| FR53 | Epic 1 / Epic 3 | Common runner abstraction (mock in E1, Docker Codex/Claude in E3) |
| FR54 | Epic 1 / Epic 3 | Context bundles (baseline in E1, full generation in E3) |
| FR55 | Epic 2 / Epic 3 | Inspect context bundle used for agent step (spec-stage in E2, impl-stage in E3) |

**All 55 FRs mapped. No gaps.**

## Epic List

**Thesis vs Amplification:** The MVP product thesis (per PRD § MVP Strategy — *"a low-risk ticket can move through a governed workflow from intake to merge-ready handoff with visible history, human feedback, developer takeover, and minimum recovery"*) is proved when **Epics 1–3** complete. **Epics 4–6 amplify and harden** the thesis for pilot-team adoption. **Epic 3c** (inserted between E3 and E4 per sprint-change-proposal-2026-06-14) is pilot-blocking enabling infrastructure — the multi-project configuration the pilot requires before E4–E6 build on it; it is not a thesis-proof epic, but it is not a legitimate cut target either (cutting it strands multi-project pilot teams).

**Cut order under schedule pressure (strictly enforced):** E6 depth → E5 depth → E4 depth, in that order. **Never cut E4 before E5/E6** — E4 is what converts a working demo into a pilot-survivable workflow. The first agent-execution failure during pilot use will test whether E4 shipped; if it hasn't, pilot trust collapses regardless of how polished E5/E6 are. E1/E2/E3 breadth is never a legitimate cut target — it would invalidate the thesis itself.

**Minimum-viable-recovery baseline (elevated from E4):** Because the first real failure in pilot use can occur before E4 ships, a recovery *baseline* — failed stage, last successful stage, failure timestamp, and a `retry` action — must land inside E1 (CLI) and E3 (UI) rather than waiting for E4. Deep reconciliation, governed failure taxonomy, and Compare Mode remain in E4.

### Epic 1: Foundation & First Governed Run (CLI)

A **pilot installer or workflow-owner developer** can install DeliveryLine locally, pass `doctor` checks, configure Linear/GitHub credentials, and submit one low-risk Linear ticket reference through the CLI — producing a real governed run with visible current state, append-only audit event, and idempotent submission. This proves the governance foundation end-to-end on a CLI-only slice before any UI exists; the PM persona (Alex) does not yet have a usable surface — their first usable experience arrives in Epic 2. Establishes all foundation contracts required by later epics (state-transition table, Flyway V1 schema, central registries, runner schema v1, shared command model, Problem Details, redaction/classification, ArchUnit boundaries, idempotency). Ships a deterministic **mock Linear adapter** alongside the real adapter so foundation demos and contract tests do not depend on external API availability.

**Retention-schema readiness:** Flyway V1 (AR5) must accommodate future retention enforcement (Epic 5) without schema migration: include `created_at`, `archived_at`, tombstone flags, ticket-closure event types in the central event-type registry, and indexes that make retention-window queries efficient. Retention *enforcement* (scheduled cleanup, archive policy) lives in Epic 5, but the *schema to support it* must land in E1 or retrofit becomes impossibly expensive.

**Foundation gate:** Stories implementing AR4–AR12 (state-transition table, Flyway V1, central registries, runner schema v1, shared command model, Problem Details, redaction/classification, package-boundary tests, idempotency) must merge before any feature story in E2–E4 begins. Story ordering within E1 should prevent parallel feature work on unfinished foundation contracts.

**Foundation-gate verification story (final story in Epic 1):** A single verification story closes Epic 1 by asserting that every foundation contract is live and wired end-to-end: ArchUnit package-boundary tests pass, Flyway V1 applies cleanly with the full schema, central registries are authoritative and drift-tested against domain enums + API schemas + frontend allowed-action names, runner context/result schema v1 validator accepts the fixture set and rejects every invalid fixture, shared command model produces identical behavior across CLI and REST for the same command payload, Problem Details mapper returns stable domain error codes, redaction/classification policy redacts the adversarial-secret fixture set (AR10), and idempotency records persist + replay correctly. This verification runs as a dedicated CI gate; any Epic 2/3/4 story opened before this verification story merges is structurally blocked by CI, enforcing the foundation-gate discipline at the tooling level rather than relying on story-ordering convention alone.

**CLI minimum-viable-recovery baseline:** Epic 1 must surface failed-run diagnostics via CLI: failed stage, last successful stage, failure timestamp, and a `retry` command that reruns the last failed step without erasing prior history. This baseline prevents the first pilot-run failure from stranding the pilot team before E4 ships. Captures FR30, FR31, NFR3 at the CLI surface; UI surfacing ships in E3; deep reconciliation and taxonomy wait for E4.

**Documentation increment (owned inside Epic 1):** Epic 1 completion requires a **setup + CLI first-run quickstart** doc — install path, credential configuration, `doctor` walkthrough, and "submit your first governed ticket" walkthrough — shipped alongside the feature stories. Epic 6 consolidates this increment; it does not author it from scratch.

**FRs covered:** FR1, FR2, FR3, FR4 (CLI form), FR22 (CLI/JSON), FR23 (CLI/JSON), FR39, FR45, FR47, FR48, FR49, FR51, FR52 (CLI side), FR53 (mock runner), FR54 (baseline)

### Epic 2: Specification Review & Product Approval (Review UI + PM Loop)

A Product Manager opens the review queue in the bundled React UI, opens a governed run, reads the current specification, answers open clarification questions in context, and approves or rejects with structured feedback — seeing their input visibly change workflow state. Spec revisions are visible in run history. The Artifact Review Panel includes a **lightweight change summary** (per UX-DR10's "optional change summary" field) so PMs can see what changed between spec revisions before approving, without requiring full Compare Mode (deferred to Epic 4). Delivers the full design system foundation (shadcn/ui + Tailwind + tokens), tri-pane application shell, TanStack Router + TanStack Query infrastructure, and the five Phase-1 workflow composites (Queue Item, Context Strip, Artifact Review Panel for spec view, Clarification Region, Approval/Decision Bar). Meets WCAG 2.1 AA.

**Visible incorporation lifecycle (make-or-break):** The PRD and UX spec both identify *clarification input that appears accepted but doesn't visibly change workflow state* as the single make-or-break UX failure for this product. Epic 2 is not complete until the visible incorporation lifecycle — **submitted → accepted → incorporated** — is demonstrated for every state-changing interaction (clarification answers, spec approvals, spec rejections with feedback). "Answer received" and "answer incorporated into active workflow context" must be distinguishable states in the UI; a toast alone is not sufficient acknowledgement.

**Documentation increment (owned inside Epic 2):** Epic 2 completion requires a **PM-loop walkthrough** doc — queue entry, spec reading, clarification answering, approving/rejecting with feedback, understanding the incorporation lifecycle — shipped alongside the feature stories.

**FRs covered:** FR4 (UI), FR5, FR6, FR7, FR8, FR9, FR10, FR11, FR12, FR13, FR22 (UI timeline), FR23 (UI), FR24, FR25, FR26 (active runs), FR27, FR28, FR46 (spec approval attribution), FR55 (spec-stage context bundle)

### Epic 3: Agent Execution, Implementation Output & Developer Review

After a spec is approved, the workflow dispatches agent work through real Docker runners (Codex + Claude), produces implementation artifacts linked to a GitHub PR reference, and a Developer can inspect the approved spec, implementation plan, and PR artifact — then accept, reject with technical feedback, or take over the run without losing prior context. Extends the Artifact Review Panel with implementation-plan and PR/output variants. Ships a deterministic **mock GitHub adapter** alongside the real adapter so the full execution loop can be demonstrated and contract-tested without external API flakiness.

**UI minimum-viable-recovery baseline:** Epic 3 must surface the E1 recovery baseline in the UI: run timeline shows failed stage, last successful stage, failure timestamp, and freshness/staleness indicator (NFR30); Approval/Decision Bar's "recovery/operator decision mode" variant (UX-DR12) exposes a basic `retry` action. This prevents the first pilot-run failure from stranding reviewers before E4 ships. Deep reconciliation, governed failure taxonomy, and Compare Mode wait for E4.

**Documentation increment (owned inside Epic 3):** Epic 3 completion requires an **execution walkthrough** doc — what happens when the workflow dispatches agent work, how to read the implementation plan and PR artifact, how developer review + takeover preserve context — shipped alongside the feature stories.

**FRs covered:** FR14, FR15, FR16, FR17, FR18, FR19, FR20, FR21, FR40, FR53 (Docker runners), FR54 (full bundle generation), FR55 (impl-stage context bundle)

### Epic 3c: Multi-Project Configuration & Pluggable Connectors

A single local-first operator can configure and govern **multiple projects** from one DeliveryLine instance — each project carrying its own repository binding, *selectable* ticket-source and repository-host connector types, encrypted per-project credentials, and run options (including OpenSpec mode). A first-class `Project` aggregate is introduced; every governed run is scoped to one project. The global configuration that today drives a single pilot repository/connector is inverted into per-project data, migrated transparently to a seeded `default` project so existing flows continue byte-identically. Builds on the vendor-neutral `TicketSourceAdapter` (story 3.32) and `RepositoryHostAdapter` (story 3.33) seams, lifting their single global `kind` selection to a per-project binding resolved at run time.

**Deliberate scope-boundary reversal:** this epic pulls **multi-project configuration** and **application-level credential encryption** into MVP scope (see PRD §MVP + FR56–FR63 and the architecture *Multi-Project Configuration & Connector Pluggability* decision). It does **not** introduce multi-user authentication, RBAC, or tenant isolation — projects are configuration records under a single operator, not access-control tenants.

**Positioning (pilot-blocking):** inserted between Epic 3 and Epic 4 because pilot teams need per-project configuration before the recovery/export/pilot-docs epics build on it. Epics 4–6 inherit the project dimension (per-project conflict detection, per-project redaction/retention, project onboarding). Sequenced after stories 3.32 + 3.33 merge.

**Security gate:** the credential-encryption primitive (envelope encryption, host-env master key) reopens the prior *no app-level encryption* posture for connector secrets only and ships behind a dedicated security review before any credential CRUD wiring.

**FRs covered:** FR56, FR57, FR58, FR59, FR60, FR61, FR62, FR63

### Epic 3d: Per-Step Execution Control, Observability & Manual Execution

A single local-first operator gains finer control over how each workflow step is executed, reviewed, observed, and retired. Each project can configure a **reviewer model** so a second LLM reviews a step's output (advisory now, gating-capable per project later); steps can be run through a first-class **manual execution mode** when an agent's unattended/headless auth is unavailable; operators can watch a step's container logs live and after the fact, open a **read-only diagnostic console** into a running runner, see the agent provider's usage/limit status (5-hour/weekly windows) after a run, and soft **hide/archive** obsolete executions when their source ticket is removed. Builds on the Epic 3c `Project` aggregate + per-project credentials and the existing runner-contracts / runner-broker seams.

**Deliberate scope-boundary reversal:** this epic **narrows** (does not remove) the runner sandbox / governed-access posture for one capability — a read-only diagnostic console attached to a live runner, with every session recorded in governed history (see PRD FR64–FR69 and the architecture *Per-Step Execution Control* scope amendment + ADRs 0024–0027). It does **not** introduce write-capable shells, host shells, multi-user authentication, RBAC, or tenant isolation. Obsolete-execution removal is **soft hide/archive only** — append-only audit history is preserved (FR47); any true purge remains an Epic 5 retention concern.

**Positioning:** inserted between Epic 3c and Epic 4, sequenced after Epic 3c (it depends on per-project configuration + credentials). Manual execution (FR66) may be pulled forward within the epic if the automated Claude/Codex headless path is unavailable for the pilot. Epic 4 inherits two of these surfaces — its failure-diagnostics view (story 4.4) consumes the live-log viewer rather than re-deriving a separate log download, and its operator queue honors the archived/hidden state.

**Spike gate:** post-execution provider limit status (FR69) ships only if the Claude CLI / Anthropic API and Codex expose the 5-hour/weekly windows programmatically in headless mode; the epic confirms the signal via a spike before committing the UI.

**FRs covered:** FR64, FR65, FR66, FR67, FR68, FR69

### Epic 4: Failure Handling, Recovery & Reconciliation (Workflow Owner + Compare Mode)

A Workflow Owner opens the run queue, selects a failed or stalled run, inspects container logs, current failed stage, artifact status, and integration conflict state — then retries, reruns, reconciles, or classifies the failure, with every recovery action appended to the same governed history. Reviewers gain Compare Mode to verify what changed between revisions before approving. Delivers the governed failure taxonomy, artifact reconciliation for DB/file drift, and integration conflict detection for Linear/GitHub.

**Documentation increment (owned inside Epic 4):** Epic 4 completion requires a **failed-run recovery walkthrough** doc — the governed failure taxonomy explained, how to diagnose container logs + artifact state + integration conflicts, which recovery action to choose (retry/rerun/reconcile/takeover), and how Compare Mode supports approval decisions on spec/plan revisions — shipped alongside the feature stories.

**FRs covered:** FR26 (failed/stalled/overridden view), FR29, FR30, FR31, FR32, FR33, FR34, FR35, FR36, FR37, FR38, FR41, FR42, FR43, FR44, FR46 (recovery attribution)

### Epic 5: Shareable Run Export & Team-Visible Review

A pilot user exports a redacted, shareable run report a teammate can inspect without access to the originating machine — secrets, tokens, user identifiers, and local paths removed. Retention policies govern how long run history and artifacts persist and what happens when tickets are closed, archived, or removed from the source system. **Surfaces pilot-measurement data** (AR34b) via CLI reports and exported runs so pilot teams can answer the PRD's success-criteria questions: did median cycle time improve vs baseline, did rework rate decrease using the tagged review-feedback taxonomy, and what fraction of eligible low-risk tickets ran through the governed workflow. (Measurement *capture* — per-event timing columns and rework-taxonomy columns — is established in Epic 1's Flyway V1 as AR34a, because capture must begin from run #1 or there is nothing to report on in E5.)

**Documentation increment (owned inside Epic 5):** Epic 5 completion requires an **exported-report walkthrough** doc — how to export a run for team review, what the redaction guarantees cover, how retention rules apply, how to read the pilot-measurement reports — shipped alongside the feature stories.

**FRs covered:** FR50, FR52 (exported-report side)

### Epic 6: Adoption Polish & Pilot Documentation

A pilot user can follow documented setup, a low-risk ticket happy-path tutorial, and a failed-run recovery walkthrough to run their first governed ticket end-to-end without live assistance — proving the PRD's pilot-success criterion. Hardens concept vocabulary, minimizes friction in first-run onboarding, and closes out the NFRs that define pilot-readiness.

**Documentation-as-incremental-milestone model:** Epic 6 is not a strictly trailing blocker — each feature epic (E1–E5) owns its own tutorial/walkthrough increment as stories inside that epic (e.g., "setup + CLI first-run quickstart" ships inside E1; "failed-run recovery walkthrough" ships inside E4; "exported run report walkthrough" ships inside E5). Epic 6 is the final **consolidation and pilot-readiness audit** pass: verify end-to-end tutorial flow across all prior increments, tighten concept vocabulary (NFR43), run pilot-onboarding dry-runs, and close any NFR42/NFR44/NFR45 gaps. This prevents E6 from becoming a cliff that blocks pilot launch and keeps documentation co-located with the features it describes.

**FRs covered:** (no new FRs — this epic hardens existing capabilities into pilot-ready form via consolidation of incremental tutorials and onboarding polish)

---

## Epic 1: Foundation & First Governed Run (CLI)

A pilot installer or workflow-owner developer can install DeliveryLine locally, pass `doctor` checks, configure Linear credentials, and submit one low-risk Linear ticket reference through the CLI — producing a real governed run with visible current state, append-only audit event, and idempotent submission. Establishes all foundation contracts required by later epics, gated by a CI verification story that structurally blocks E2–E4 feature work until foundation invariants hold.

### Story 1.1: Initialize Maven Multi-Module Project Scaffold

As a foundation developer,
I want the DeliveryLine Maven multi-module project initialized with backend, frontend, and runner-contracts modules plus root-level directories,
So that every subsequent story has a coherent build graph and stable package/module boundaries to work within.

**Acceptance Criteria:**

1. **Given** a clean working directory, **When** the scaffold is generated per AR1 via Spring Initializr (Java 21, Maven, jar packaging, group `org.dradgo`, artifact `deliveryline`), **Then** the resulting project includes Spring Web, Data JPA, PostgreSQL driver, Flyway, Validation, Actuator, Docker Compose support, and Testcontainers dependencies.
2. **Given** the generated parent POM, **When** Spring Shell is available from Initializr, **Then** it is included as a dependency; **Otherwise** `org.springframework.shell:spring-shell-starter` is added manually with a compatible Spring Shell BOM.
3. **Given** the root POM, **When** `mvn clean install` runs, **Then** three Maven modules build successfully with artifact IDs `deliveryline-backend`, `deliveryline-frontend` (Vite stub — real React wiring ships later in Epic 2), and `deliveryline-runner-contracts`.
4. **Given** the root directory, **Then** `runners/` (with `codex/` and `claude/` subfolders containing placeholder `Dockerfile` + `entrypoint.sh` + `README.md`), `infra/observability/`, `scripts/`, `docs/`, and `.github/workflows/` directories exist per the architecture project structure.
5. **Given** the backend module, **Then** the base package is `org.dradgo` with skeleton subpackages `domain`, `application`, `adapters`, `infrastructure` (empty subdirectories at minimum, populated by later stories).
6. **Given** `.gitignore` at the root, **Then** it excludes Maven target dirs, Node `node_modules/` and `dist/`, IDE files (`.idea/`, `.vscode/`), OS artifacts (`.DS_Store`, `Thumbs.db`), `.env` files, and local runtime state directories.
7. **Given** `.env.example` at the root, **Then** it documents placeholder names for `LINEAR_API_KEY`, `GITHUB_TOKEN`, `DELIVERYLINE_HOME`, and Docker Compose overrides — with no real secrets.

### Story 1.2: Unified Docker Compose with `.env`-Configurable Ports

As a foundation developer,
I want a single `docker-compose.yml` at the root that brings up every service the project needs (PostgreSQL in E1; runners + ELK + Prometheus + Grafana arrive in E3 stories that extend this same file) with all host ports configurable via `.env`,
So that one `docker compose up -d` starts everything a pilot installer needs and there is no separate observability/runner compose file to forget.

**Acceptance Criteria:**

1. **Given** a single `docker-compose.yml` at the root (no separate `docker-compose.observability.yml` — the prior architecture decision in AR25 is consolidated here), **When** `docker compose up -d` runs, **Then** every declared service starts; in E1 the file declares only PostgreSQL (story 3.7 extends it with ELK + Prometheus + Grafana, and stories 3.3/3.4 extend it with the Codex + Claude runner image declarations).
2. **Given** the PostgreSQL service, **Then** it uses PostgreSQL 15+, database `deliveryline`, user `deliveryline`, password read from `.env`, and persists data to a named Docker volume (`deliveryline-postgres-data`).
3. **Given** Spring Boot's Docker Compose support, **When** the backend app starts with the `local` profile, **Then** it auto-discovers the PostgreSQL service connection details (host, port, credentials) without manual `application.yml` JDBC URL duplication.
4. **Given** every host port in the compose file, **Then** each is driven by a documented `.env` variable so port collisions are resolved by editing `.env` rather than by editing `docker-compose.yml`. E1 ships with at minimum `POSTGRES_HOST_PORT` (default 5432); story 3.7 adds `ELASTIC_HOST_PORT`, `LOGSTASH_HOST_PORT`, `KIBANA_HOST_PORT`, `PROMETHEUS_HOST_PORT`, `GRAFANA_HOST_PORT` to the same `.env` + compose file.
5. **Given** `.env.example` at the root (story 1.1 AC7), **Then** every port variable referenced by `docker-compose.yml` has a default value documented in `.env.example` with a one-line comment explaining the service it belongs to — pilot installers can copy `.env.example` → `.env` and edit only what conflicts.
6. **Given** profile gating for selective startup, **Then** services that are heavy or not required for normal foundation work (E3's ELK stack and Prometheus/Grafana per story 3.7) are tagged with the `observability` compose profile so `docker compose up -d` (no profile) starts only the essentials, while `docker compose --profile observability up -d` (or `--profile '*'`) starts everything. E1's PostgreSQL is in the default profile (always starts). The compose file remains a single file regardless of profile membership.
7. **Given** `docker compose down -v`, **When** executed, **Then** all named volumes are removed and a subsequent `up` recreates a clean state — supporting the later `reset-local` script in story 1.17.
8. **Given** runner image declarations from stories 3.3/3.4, **Then** they are added to this same `docker-compose.yml` (no separate runner compose file) and tagged with the `runners` compose profile if they are not always-on services (per AR16 — runner lifecycle is broker-controlled, so runner *images* are declared as `build` targets here but actual runner *containers* are spawned by the broker, not by `docker compose up`).
9. **Given** consolidated compose maintenance, **Then** an ADR `docs/adr/0001-unified-compose.md` documents the decision to consolidate (replacing AR25's earlier two-file proposal) — rationale: pilot installers only run one command, port collisions resolve in one `.env`, observability is opt-in via profile not separate file.
10. **Given** the doctor command (story 1.16), **Then** its `docker availability` check additionally lists which compose-declared services are running and their actual host ports (resolved from the `.env`) — so pilot installers see the live binding without parsing the compose file by hand.

### Story 1.3: Flyway V1 Core Schema with Retention and Measurement Columns

As a foundation developer,
I want Flyway-managed V1 schema creating all nine core tables with retention and measurement-capture columns from day one,
So that subsequent stories can persist domain data without reopening the schema mid-flight (per architecture readiness caveat + party-mode finding that E1 retention-schema readiness + AR34a measurement capture must land in V1).

**Acceptance Criteria:**

1. **Given** an empty PostgreSQL database and the `local` profile, **When** the backend starts, **Then** Flyway applies `V1__create_workflow_core_tables.sql` creating tables `workflow_runs`, `workflow_events`, `artifacts`, `artifact_operations`, `approvals`, `runner_executions`, `integration_links`, `recovery_actions`, and `idempotency_records`.
2. **Given** each table, **Then** primary keys are `text` columns storing readable prefixed IDs per the prefix registry (`run_`, `evt_`, `art_`, `op_`, `apr_`, `rex_`, `ilk_`, `rcv_`, `idm_`).
3. **Given** every row-producing table, **Then** `created_at timestamptz NOT NULL DEFAULT now()` and `archived_at timestamptz NULL` columns exist to support later retention enforcement (Epic 5) without schema migration.
4. **Given** the `workflow_events` table, **Then** a `stage_duration_ms bigint NULL` column exists for stage-transition events (AR34a cycle-time capture) and a `rejection_taxonomy text NULL` column exists with a CHECK constraint limiting values to `missing_scope`, `unclear_specification`, `misunderstood_implementation`, or `NULL` (AR34a rework-rate capture).
5. **Given** the workflow state registry, **Then** `workflow_runs.current_state text NOT NULL` has a CHECK constraint enforcing values from the state registry (story 1.4 populates the registry; the CHECK is added here using the canonical state string list).
6. **Given** architecture naming conventions, **Then** foreign keys use `fk_{table}_{referenced_table}`, unique constraints use `uq_{table}_{columns}`, indexes use `idx_{table}_{columns}`, and check constraints use `ck_{table}_{meaning}`.
7. **Given** all timestamp columns, **Then** they use `timestamptz` — never `timestamp without time zone`.
8. **Given** all enum-like columns, **Then** they are persisted as `text` with CHECK constraints — never as ordinals or Postgres enums.
9. **Given** migration replay, **When** Flyway runs twice against the same DB, **Then** the second run is a no-op (no checksum mismatch, no errors).
10. **Given** a deliberately broken `V1` migration in a throwaway branch, **When** the app starts, **Then** startup fails fast with a Flyway validation error — the app does not partially start with uncertain schema state.

### Story 1.4: Central Registries with Drift Tests

As a foundation developer,
I want central registries for every enumerable domain value (states, events, errors, failures, artifact types, statuses, allowed actions, ID prefixes, runner schema versions) with automated drift tests,
So that no value can be silently introduced outside the registry and cross-layer consistency is enforceable.

**Acceptance Criteria:**

1. **Given** the `domain.registry` package, **Then** `DomainRegistry`, `DomainErrorCode`, `AllowedAction`, `WorkflowState`, `WorkflowEventType`, `FailureCategory`, `ArtifactType`, `ArtifactOperationStatus`, `RunnerExecutionStatus`, `DataClassification`, `PublicIdPrefixes`, and `RunnerSchemaVersion` exist as authoritative value sources.
2. **Given** `WorkflowEventType`, **Then** event types follow dot-separated lowerCamel namespaces (e.g., `workflow.stateChanged`, `approval.requested`, `runner.failed`, `recovery.reconciled`, `integration.linked`, `export.created`).
3. **Given** `DomainErrorCode`, **Then** codes are uppercase snake_case and include at minimum `ILLEGAL_TRANSITION`, `IDEMPOTENCY_KEY_CONFLICT`, `APPROVAL_VERSION_MISMATCH`, `CONCURRENT_TRANSITION_CONFLICT`, `RUNNER_TIMEOUT`, `RUNNER_CONTRACT_VIOLATION`, `ARTIFACT_PAYLOAD_UNAVAILABLE`.
4. **Given** `FR45` actor attribution, **Then** an `ActorType` registry value set includes `human`, `agent`, `system`, `service_account`.
5. **Given** drift tests, **When** any registry value is added or removed, **Then** a test that compares registry values against (a) corresponding domain enums, (b) API request/response schema references, (c) test fixtures for event types, and (d) a placeholder frontend allowed-actions list fails until the registry, consumers, and fixtures are realigned.
6. **Given** persisted data with an unknown registry value (e.g., a database row with a workflow state not present in the registry), **When** loaded through the domain mapping layer, **Then** the load fails with an explicit error (`UNKNOWN_REGISTRY_VALUE`) or routes the row to a reconciliation queue — it must never silently coerce to a default.
7. **Given** `PublicIdPrefixes`, **When** an ID is generated or parsed, **Then** the producer uses a registered prefix and the validator rejects any ID with an unknown or mismatched prefix.

### Story 1.5: Workflow State-Transition Table + WorkflowTransitionService

As a foundation developer,
I want a canonical workflow state-transition table (including runner-failure states) and a `WorkflowTransitionService` that atomically updates run state and appends the matching event,
So that every workflow state change passes through one validated path and E3 does not have to reopen the transition table for real runner failures (per party-mode finding #1).

**Acceptance Criteria:**

1. **Given** the state registry, **Then** canonical states include `Inbox`, `Planned`, `Investigating`, `WaitingForSpecApproval`, `Executing`, `WaitingForReview`, `Completed`, `Failed`, `Paused`, `TakenOver`, `Reconciled` (terminal: `Completed`).
2. **Given** the transition table, **Then** allowed transitions include `Inbox→Planned`, `Planned→Investigating`, `Investigating→WaitingForSpecApproval`, `WaitingForSpecApproval→Executing`, `WaitingForSpecApproval→Investigating`, `Executing→WaitingForReview`, `Executing→Failed`, `Executing→Paused`, `Failed→Executing`, `Failed→Investigating`, `Paused→Executing`, `WaitingForReview→Completed`, `WaitingForReview→Executing`, `*→TakenOver`, `*→Reconciled` (where `*` covers any non-terminal state).
3. **Given** the transition table, **Then** runner-failure transitions explicitly include `Executing→Failed` with `failure_category` values `runner_timeout`, `runner_crash`, `runner_contract_violation`, `runner_non_zero_exit` (so E3 real-runner integration does not reopen this story).
4. **Given** `WorkflowTransitionService.transition(runId, targetState, actor, reason, idempotencyKey)`, **When** called with a valid transition, **Then** within a single PostgreSQL transaction the service updates `workflow_runs.current_state` and appends a `workflow_events` row with matching `prior_state`, `resulting_state`, `actor_id`, `actor_type`, `timestamp`, optional `reason`, and optional `intervention_marker`.
5. **Given** an invalid transition (source state not in the table's allowed transitions for the target), **When** attempted, **Then** the service throws a `DomainException` carrying stable code `ILLEGAL_TRANSITION` — no state or event row is written.
6. **Given** two concurrent transitions on the same run, **When** both commit, **Then** exactly one succeeds and the other fails with `CONCURRENT_TRANSITION_CONFLICT` (optimistic locking on a `version` column or equivalent).
7. **Given** contract tests, **Then** illegal transitions, duplicate transitions, replayed requests, out-of-order events, and conflicting concurrent updates are each proven to leave the run and event history consistent.
8. **Given** the architecture rule that no code path outside `WorkflowTransitionService` can mutate `workflow_runs.current_state`, **Then** story 1.11's ArchUnit test will enforce this (placeholder acknowledged here; enforcement lands in 1.11).

### Story 1.6: Runner Context/Result Schema v1 with Artifact-Variant Discriminators

As a foundation developer,
I want the `deliveryline-runner-contracts` module to publish `context-bundle.v1.schema.json` and `runner-result.v1.schema.json` with artifact-variant discriminators for spec, implementation-plan, and PR/output artifacts, plus valid and invalid fixtures,
So that E3 can add real Docker runners without reopening the schema to retrofit artifact variants (per party-mode finding #2).

**Acceptance Criteria:**

1. **Given** the `deliveryline-runner-contracts` module, **Then** `src/main/resources/schemas/context-bundle.v1.schema.json` and `src/main/resources/schemas/runner-result.v1.schema.json` exist as JSON Schema Draft 2020-12 documents.
2. **Given** the context-bundle schema, **Then** it requires: `schemaVersion` (const `1`), `workflowRunId`, `runnerExecutionId`, `ticketSummary`, `approvedSpecificationReference` (nullable at spec-stage), `priorFeedbackReferences` (array), `artifactReferences` (array with typed entries), `executionConstraints` (object), and `classification` (enum from `DataClassification` registry).
3. **Given** the runner-result schema, **Then** it requires: `schemaVersion` (const `1`), `workflowRunId`, `runnerExecutionId`, `artifactReferences` (array with `artifactType` discriminator), `normalizedOutput`, `rawOutputReference` (optional, used only when raw retention is enabled), `checksum` (algorithm + hex digest), `classification`, and `failureCategory` (nullable; values from `FailureCategory` registry).
4. **Given** the `artifactType` discriminator in runner-result, **Then** its enum values are exactly `spec`, `implementationPlan`, `prOutput` — and each variant has a matching sub-schema describing its payload shape (spec: markdown content reference; implementationPlan: structured steps array + context refs; prOutput: branch, commitSha, prReference, diffReference).
5. **Given** `RunnerContractValidator` in `runner-contracts/src/main/java`, **When** validating a fixture, **Then** it returns a structured validation result (valid or list of typed errors).
6. **Given** `runner-contracts/src/test/resources/fixtures/valid/`, **Then** at least one valid context-bundle fixture exists and at least one valid runner-result fixture exists per artifact variant (spec, implementationPlan, prOutput) — three variant fixtures minimum.
7. **Given** `runner-contracts/src/test/resources/fixtures/invalid/`, **Then** invalid fixtures exist for each rejection case: missing required field, unknown schema version, bad checksum, duplicate `runnerExecutionId`, stale metadata (e.g., `runnerExecutionId` referencing a non-existent run), malformed `classification`, partial write (truncated JSON), oversized file (exceeds documented size limit), path-traversal attempt in `artifactReferences`, metadata spoofing (claiming `classification: "shareable-full"` for a payload that contains secret patterns).
8. **Given** the contract test suite, **When** run, **Then** every fixture in `valid/` validates successfully and every fixture in `invalid/` is rejected with the expected error type.
9. **Given** the runner schema version registry (`RunnerSchemaVersion` from story 1.4), **Then** version `1` is registered and marked as the current default for E1 and E2; future versions follow semantic compatibility rules documented in the module README.

### Story 1.7: Shared Application Command Model Pattern

As a foundation developer,
I want application-level command types and command services that both CLI and REST adapters translate into,
So that the same command payload produces identical behavior and stable domain error codes across transports — preventing transport-specific business logic drift.

**Acceptance Criteria:**

1. **Given** the `application.workflow.commands` package, **Then** command types exist: `SubmitWorkflowCommand`, `ApproveSpecCommand`, `RejectSpecCommand`, `RetryWorkflowCommand`, `TakeoverWorkflowCommand` (approval/reject/retry/takeover command handlers wired progressively — submit is the only command with end-to-end execution in E1; the rest have service-level wiring but adapter surfacing waits for later stories/epics).
2. **Given** each command type, **Then** it carries: `actorIdentity`, `actorType` (from `ActorType` registry in story 1.4), `idempotencyKey`, command-specific payload (e.g., `linearTicketReference` on submit, `artifactVersion` + `contextVersion` on approve), and optional `correlationId`.
3. **Given** Bean Validation annotations on command fields, **When** validation fails, **Then** the command service throws a `DomainException` with stable code `INVALID_COMMAND_PAYLOAD` and machine-readable field-level details.
4. **Given** `WorkflowCommandService`, **When** it receives a valid command, **Then** it enforces workflow invariants, delegates to `WorkflowTransitionService` / `ArtifactOperationService` / `IdempotencyService` / `RunnerBroker` as appropriate, and returns a typed `DomainResult` (success with typed outcome or failure with stable `DomainErrorCode`).
5. **Given** CLI and REST adapter tests for the same command type with the same payload, **When** both adapters translate their inputs into the application command and call the service, **Then** both produce identical `DomainResult` outcomes — including identical stable error codes for failure paths (CLI/REST equivalence contract tests).
6. **Given** the CLI adapter (`adapters.cli`), **Then** Spring Shell commands parse arguments, construct the application command, call the command service, format the result for terminal output — with no workflow orchestration, persistence, approval, or recovery logic inside the adapter (enforced by ArchUnit in story 1.11).
7. **Given** the REST adapter (`adapters.rest`), **Then** stub controllers for each command endpoint exist and translate HTTP request bodies to application commands (full endpoint activation for reads in story 6.9; mutation endpoints land in E2+).
8. **Given** a command with `correlationId`, **Then** the ID is propagated through command processing and stamped on every log entry and event produced during that command's execution (wired fully by story 1.19).

### Story 1.8: Problem Details Mapper with Stable Domain Error Codes

As a foundation developer,
I want a `ProblemDetailsMapper` that converts application-level `DomainException`s into RFC 7807 `application/problem+json` responses for REST and concise exit-coded output for CLI,
So that transport-specific error representations preserve stable semantics and clients can rely on machine-readable error metadata (`code`, `status`, `retryable`, `details`) rather than human-readable message text.

**Acceptance Criteria:**

1. **Given** a `DomainException` thrown from an application service during REST request handling, **When** the mapper runs, **Then** it emits an `application/problem+json` response with required fields: `type` (URL shaped as `https://deliveryline.local/problems/{code}`), `title`, `status`, `detail`, `instance` (request path), `code` (from `DomainErrorCode` registry), and `retryable` (boolean).
2. **Given** Bean Validation failures, **When** the mapper handles them, **Then** the response includes a `details` array with `field`, `rejectedValue` (redacted if sensitive), and `constraint` per invalid field.
3. **Given** a `DomainException` carrying `APPROVAL_VERSION_MISMATCH`, **When** mapped, **Then** the response includes `details.expectedArtifactVersion` and `details.currentArtifactVersion` so the UI (Epic 2) can refresh intelligently.
4. **Given** contract tests, **Then** REST error responses conform to RFC 7807 schema and assertions check `code`, `status`, and `details` — never the human-readable `detail` or `title` text (which may change).
5. **Given** the CLI adapter, **When** a `DomainException` propagates out of a Shell command, **Then** the CLI prints a one-line error: `[{code}] {detail}` and sets a non-zero exit code mapped from the error category (`1xx` for client-like errors, `2xx` for concurrency/idempotency conflicts, `3xx` for runner/integration failures, `4xx` for infrastructure failures — exact mapping documented in the CLI README).
6. **Given** the central registry drift test (story 1.4), **When** a new `DomainErrorCode` is added without a corresponding mapper case or type-URL, **Then** the drift test fails.
7. **Given** unknown exceptions (not `DomainException`), **When** caught by the REST error handler, **Then** a generic `500` Problem Details response with code `INTERNAL_ERROR` is returned — no stack trace or internal path leaks into the response body (verified by a redaction-aware contract test).

### Story 1.9: Idempotency Service

As a foundation developer,
I want an `IdempotencyService` that persists command idempotency records and enforces replay/conflict semantics identically across CLI and REST,
So that duplicate command submissions (retries, network hiccups, CLI re-runs) cannot double-apply state changes and retry behavior is safe and predictable.

**Acceptance Criteria:**

1. **Given** the `application.idempotency` package, **Then** `IdempotencyService` exposes `checkAndReserve(key, commandType, actor, fingerprint)` and `complete(key, resultRef, status)` methods; the `idempotency_records` table (from story 1.3) persists `key`, `command_type`, `actor_identity`, `command_fingerprint` (SHA-256 of normalized payload), `status` (reserved|completed|failed), `result_ref`, `created_at`, `completed_at`.
2. **Given** a command carrying idempotency key K submitted for the first time, **When** processed, **Then** a record with status `reserved` is written under a unique constraint on `key`, the command executes, and on success `complete(K, resultRef, completed)` transitions the record to `completed`.
3. **Given** the same key K + same fingerprint F resubmitted after success, **When** processed, **Then** `checkAndReserve` returns the prior `resultRef` and the command is NOT re-executed (replay semantics).
4. **Given** the same key K + a different fingerprint F', **When** resubmitted, **Then** `IDEMPOTENCY_KEY_CONFLICT` is raised (stable code from registry in story 1.4) with `details.existingFingerprint` and `details.submittedFingerprint` (both redacted/hashed) for diagnosis.
5. **Given** two concurrent submissions with same key K and same fingerprint F, **When** both race the unique-constraint lock, **Then** one wins and executes while the other blocks then returns the winner's `resultRef` — neither double-executes.
6. **Given** a crash between `checkAndReserve` and `complete` (record status remains `reserved`), **When** the same key K + fingerprint F is resubmitted, **Then** the service detects the stale reservation (via `created_at` age threshold documented in an ADR) and either re-executes or returns a `STALE_IDEMPOTENCY_RESERVATION` error depending on configured policy — never silently re-executes without evidence.
7. **Given** REST commands, **Then** `Idempotency-Key` header is accepted and required for all state-changing endpoints; missing key returns `MISSING_IDEMPOTENCY_KEY` with status 400.
8. **Given** CLI commands, **Then** `--idempotency-key` flag is accepted; when omitted for interactive CLI, the Shell auto-generates a UUIDv7 and prints it in verbose mode; non-interactive/scripted CLI requires an explicit key.
9. **Given** key validation, **Then** keys must match UUIDv4, UUIDv7, or opaque-string rules (alphanumeric + hyphens, length 16–128) — invalid keys return `INVALID_IDEMPOTENCY_KEY`.
10. **Given** the contract test suite, **Then** it covers: first-time execution, same-key-same-fingerprint replay, same-key-different-fingerprint conflict, concurrent-submission race, stale-reservation recovery, missing-key rejection, and invalid-key-format rejection.

### Story 1.10: Redaction/Classification Policy + Adversarial Secret Fixture Set

As a foundation developer,
I want `RedactionPolicyService` + `DataClassificationService` with an adversarial fixture set exhausting known credential formats,
So that secrets, tokens, and unnecessary local-only data cannot leak into logs, exports, artifacts, context bundles, or runner outputs — capture-time redaction and export-time redaction both applied as a double gate.

**Acceptance Criteria:**

1. **Given** the `application.security` package, **Then** `RedactionPolicyService` (applies redaction rules) and `DataClassificationService` (assigns/queries classification labels) exist with interfaces matching the architecture decision.
2. **Given** `DataClassification` registry values (`local-only`, `shareable-redacted`, `shareable-full`, `derived-public-safe`), **Then** each can be assigned to an artifact, context bundle, log entry, export, or runner output and is persisted alongside the data (via `classification` column on relevant tables or metadata sidecar).
3. **Given** a string or structured payload containing known credential patterns, **When** `RedactionPolicyService.redact(...)` runs, **Then** secrets are replaced with classification-safe placeholders (e.g., `[REDACTED_LINEAR_API_KEY]`, `[REDACTED_GITHUB_TOKEN]`, `[REDACTED_SSH_PRIVATE_KEY]`) and the output classification is downgraded to `shareable-redacted` if any redaction occurred.
4. **Given** the adversarial fixture set at `backend/src/test/resources/redaction-fixtures/`, **Then** fixtures exist covering: Linear API keys with known prefix, GitHub personal-access-tokens (`ghp_*`, `github_pat_*`), SSH public key blocks (`ssh-rsa`, `ssh-ed25519`), SSH private key blocks (`-----BEGIN OPENSSH PRIVATE KEY-----` / `-----BEGIN RSA PRIVATE KEY-----`), `.env`-style `KEY=VALUE` with secret patterns, YAML/JSON documents with embedded secret fields, HTTP `Authorization: Bearer` + `Authorization: Basic` headers, secrets in URL query params (`token=`, `apikey=`, `access_token=`), absolute local paths revealing `C:\Users\{name}` or `/Users/{name}` or `/home/{name}`, process-environment leakage (full env block in a stack trace).
5. **Given** each adversarial fixture, **When** passed through `RedactionPolicyService`, **Then** a test asserts no raw secret token appears in the output and the placeholder indicates the matched category.
6. **Given** a property-based generator producing unknown-shape `KEY=VALUE` pairs with high-entropy values, **When** classified, **Then** suspicious entropy + key-name heuristics (keys containing `secret`, `token`, `key`, `password`, `credential`) result in conservative redaction *(party-mode finding — "unknown-shape secret" negative case)*.
7. **Given** double-gate redaction, **Then** redaction runs at two points: (a) on capture into durable storage (logs, artifacts, context bundles) and (b) on export/sharing; a test proves that even if a raw value slipped past capture-time redaction, export-time redaction catches it.
8. **Given** an export attempt for data classified as `local-only`, **When** invoked, **Then** the export is rejected with `EXPORT_CLASSIFICATION_VIOLATION` unless the data is first downgraded via a `RedactionPolicyService`-approved path.
9. **Given** the fixture library is documented as a living artifact, **Then** a `README.md` next to the fixtures explains how to add new credential formats as they are discovered, and CI fails loudly on fixture additions that are not wired into the redaction contract test.
10. **Given** a metadata-spoofing fixture (payload claims `classification: "shareable-full"` but contains secret patterns), **When** processed, **Then** the classification service re-validates against actual payload content and downgrades — claimed classification cannot override detected secrets.

### Story 1.11: ArchUnit Package-Boundary Tests

As a foundation developer,
I want an ArchUnit test suite enforcing adapter/application/domain/infrastructure boundaries and the hard-invariant rules from the architecture document,
So that no adapter can call persistence directly, no business rule can drift into an adapter, and no violation reaches CI undetected.

**Acceptance Criteria:**

1. **Given** `backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java`, **Then** ArchUnit rules enforce layered dependency direction: `domain` may not depend on Spring/JPA/Jackson types or any adapter package; `application` depends only on `domain`; `adapters.*` may depend on `application` + `domain` + Spring infrastructure but not on other `adapters.*` packages.
2. **Given** `adapters.cli` and `adapters.rest` classes, **Then** ArchUnit asserts they never directly inject or reference `adapters.persistence` repository classes, `adapters.runner.*` adapters, or `adapters.files.*` storage implementations (must go through application services).
3. **Given** `WorkflowTransitionService` from story 1.5, **Then** ArchUnit asserts `workflow_runs.current_state` is mutated only by that service (enforced via a classes-that-access-field rule targeting the JPA entity's `currentState` setter).
4. **Given** `ArtifactOperationService` from story 1.12, **Then** ArchUnit asserts approval-eligible artifact file writes go only through that service — `adapters.rest`, `adapters.cli`, and other application services may not bypass it.
5. **Given** `RedactionPolicyService` from story 1.10, **Then** ArchUnit asserts no adapter class implements its own redaction logic independent of the service (detected via presence of credential regex patterns or classification enum references outside the approved package).
6. **Given** forbidden dependencies in `domain`, **Then** rules reject: Jackson annotations/types, Spring annotations (`@Autowired`, `@Component`, `@Service`), JPA annotations, `HttpServletRequest`/`Response`, Shell command annotations.
7. **Given** naming conventions, **Then** ArchUnit asserts: REST controller classes end in `Controller`, Spring Shell command classes end in `Commands` (or the canonical Spring Shell suffix), persistence entities end in `Entity`, application services end in `Service`, command types end in `Command`, application use-case results end in `Result` or `Outcome`.
8. **Given** the CI pipeline, **Then** ArchUnit tests run in the architecture test tier and fail with clear, actionable diagnostic output (offending class + rule name + suggestion) — violations fail the build loudly, never quietly warn.
9. **Given** the `adapters.persistence` module, **Then** ArchUnit asserts explicit mapper classes exist (ending in `Mapper` or `Mappers`) and JPA entity types do not leak into `application` or `domain` signatures.
10. **Given** each central registry (from story 1.4), **Then** ArchUnit asserts domain enums, REST DTO enum references, and central registry values stay in sync (delegates to the registry drift test — ArchUnit invokes it as part of the architecture tier).

### Story 1.12: Artifact Operations Skeleton

As a foundation developer,
I want `ArtifactService` + `ArtifactOperationService` + `LocalArtifactStore` implementing transactional outbox-style artifact operations with explicit availability gating,
So that artifact payloads can be written outside the database while metadata is transactionally recorded, lineage is preserved across revisions, and only `available` artifacts with verified checksums become approval-eligible.

**Acceptance Criteria:**

1. **Given** the `application.artifact` package, **Then** `ArtifactService`, `ArtifactOperationService`, and `ArtifactReconciliationService` exist with methods: `createDraft(workflowRunId, artifactType, payloadRef)`, `recordOperation(operation)`, `markAvailable(artifactId, checksum, storageRef)`, `markFailed(artifactId, failureCategory, reason)`, `newVersion(parentArtifactId, payloadRef)`, `isApprovalEligible(artifactId)`.
2. **Given** the `artifacts` table (from story 1.3), **Then** each row carries: `id` (`art_` prefix), `workflow_run_id`, `artifact_type` (from registry), `version` (monotonic per artifact lineage), `parent_artifact_id` (nullable — lineage root has null), `created_at`, `archived_at`, `classification`, `storage_ref`, `checksum_algorithm`, `checksum_value`, `status` (`pending|available|failed|late_or_stale`), `linked_event_id`.
3. **Given** an artifact-creation command, **When** processed through `ArtifactOperationService.recordOperation(...)`, **Then** inside one PostgreSQL transaction: (a) an `artifact_operations` row is inserted with `operation_type` (`create|update|replace`), `status=pending`, `idempotency_key`, `artifact_id`, `workflow_run_id`, `linked_event_id`; (b) the matching `workflow_events` row is appended; (c) the commit returns control to the caller which performs the file write outside the DB transaction.
4. **Given** a successful file write, **When** `markAvailable(artifactId, checksum, storageRef)` is called, **Then** the artifact status transitions to `available` and the `artifact_operations` row is marked `complete` — both in one transaction — and a follow-up `artifact.available` event is appended.
5. **Given** a file-write failure or crash, **When** detected (via `ArtifactReconciliationService` reconciliation scan — full implementation in E4, but the skeleton queries `artifact_operations` for `pending` rows older than a configurable threshold), **Then** orphan operations are flagged with `status=failed_orphan` and surfaced via CLI diagnostic in story 1.18; the deep reconciliation loop lives in E4.
6. **Given** approval-eligibility gating, **When** `isApprovalEligible(artifactId)` is called, **Then** it returns true only if `status=available` AND `checksum_value` is present AND `storage_ref` resolves to a readable payload AND `archived_at IS NULL` — enforcing the architecture rule that artifact metadata alone is insufficient for approval.
7. **Given** artifact versioning, **When** `newVersion(lineageMemberArtifactId, payloadRef)` is called, **Then** the service continues only the active lineage for that `(workflow_run_id, artifact_type)` pair and creates the next version on that lineage — the parent's payload is never overwritten in place. If the requested operation would require creating a second lineage, healing an ambiguous lineage, or choosing between multiple possible parents after partial failure or replay, the service fails closed with `ARTIFACT_OPERATION_INTENT_CONFLICT`; explicit repair or fork decisions belong to Epic 4 recovery work.
8. **Given** `LocalArtifactStore` in `adapters.files`, **When** writing a payload, **Then** the path resolves to `{DELIVERYLINE_HOME}/artifacts/{workflowRunId}/{artifactId}/v{version}/{filename}` — relative to the configured home directory, never using absolute machine-specific paths inside `storage_ref`.
9. **Given** idempotency on artifact operations, **Then** duplicate submissions replay only when the prior outcome can be reconstructed unambiguously for the same workflow run, artifact type, and operation intent. When replay would otherwise create a new artifact, attach to the wrong lineage, or convert a prior failed outcome into an apparent success, the service returns `ARTIFACT_OPERATION_INTENT_CONFLICT` and requires later explicit recovery handling instead of silently guessing.
10. **Given** a late runner result arriving after its associated `runner_executions` row timed out, **When** `recordOperation` is called, **Then** the artifact is created with `status=late_or_stale` — workflow state is NOT auto-advanced; the recovery decision lives in E4.
11. **Given** the test suite, **Then** it covers: interrupted artifact operations (DB commit without file write), missing payloads (file deleted after markAvailable), duplicate commands with same idempotency key, concurrent version creation (two newVersion calls against same parent — resolves deterministically via version uniqueness constraint), stale runner callbacks, reconciliation-repair of orphan operations.

**Scope guardrail:** Story `1.12` owns artifact intent persistence, availability gating, late/stale/orphan detection, and deterministic replay where the outcome is unambiguous. It does **not** own operator-driven artifact lineage repair, explicit fork governance, or deep reconciliation of ambiguous artifact history. Those behaviors are planned follow-up recovery work.

### Story 1.13: RunnerBroker + Deterministic MockRunnerAdapter

As a foundation developer,
I want `RunnerBroker` (runner lifecycle controller) + `RunnerExecutionService` + `ContextBundleService` + deterministic `MockRunnerAdapter` that exercises the full runner file contract including failure paths,
So that workflow, idempotency, and recovery tests can run deterministically without Docker, and the real Docker runners in Epic 3 plug into the same port without reshaping foundation contracts.

**Acceptance Criteria:**

1. **Given** the `application.runner` package, **Then** `RunnerBroker`, `RunnerExecutionService`, and `ContextBundleService` exist with a `RunnerAdapter` port (interface) that real + mock adapters implement.
2. **Given** `RunnerBroker.dispatch(workflowRunId, stage, contextBundle, idempotencyKey)`, **When** called, **Then** it: (a) creates a `runner_executions` row with `rex_` prefix ID, status `pending`, timeout deadline, last-activity timestamp, and context-bundle reference; (b) delegates execution to the configured `RunnerAdapter`; (c) returns an execution handle.
3. **Given** `ContextBundleService.create(workflowRunId, stage)`, **When** called, **Then** it builds a `context-bundle.v1` document (conforming to the schema from story 1.6) assembled from: ticket summary, approved specification reference (if any), prior feedback references, artifact references, execution constraints, classification metadata — the content is redacted via `RedactionPolicyService` (story 1.10) before persistence and must exclude credentials, `.env` files, shell history, unrelated local paths.
4. **Given** `MockRunnerAdapter` in `adapters.runner`, **Then** it implements `RunnerAdapter` and accepts a scripted behavior config (per test) producing: happy-path success with a spec artifact, happy-path success with an implementation-plan artifact, happy-path success with a PR-output artifact, timeout (no result before deadline), crash (process exits non-zero with no result file), contract violation (result file present but invalid per runner-contracts validator), non-zero exit (result file valid but `failureCategory` set), late result (delivered after timeout), duplicate result (two results for same `runnerExecutionId`), malformed output (truncated JSON).
5. **Given** each mock failure mode, **When** the broker processes the result, **Then** the corresponding `workflow_events.failure_category` aligns with a registered value (`runner_timeout`, `runner_crash`, `runner_contract_violation`, `runner_non_zero_exit`, `runner_late_result`, `runner_duplicate_result`, `runner_malformed_output`) and the state-transition table from story 1.5 honors the failure state.
6. **Given** runner result handling, **When** a result file arrives, **Then** the broker validates it with `RunnerContractValidator` (story 1.6) BEFORE calling `ArtifactOperationService.recordOperation(...)` — failed validation never produces approval-eligible artifacts.
7. **Given** heartbeat / last-activity tracking, **Then** `runner_executions.last_activity_at` is updated on status polls or heartbeat file touches; stale detection threshold is configurable and defaults to 2× the stage-declared timeout.
8. **Given** idempotent runner restart after broker crash, **When** the broker restarts and sees an in-flight `runner_executions` row, **Then** it either resumes monitoring (if the underlying process/file state is recoverable) or marks the execution `orphaned` and raises a reconciliation signal — never silently abandons the row.
9. **Given** the `runners.mock` profile, **Then** the mock adapter is wired as the default; real Docker runner adapter (E3) activates only under explicit profile switch.
10. **Given** the contract test suite, **Then** every mock failure mode produces the documented `DomainErrorCode` and `workflow_events.failure_category`, and a deterministic test fixture library exists under `backend/src/test/resources/runner-scenarios/` for reuse across application + recovery tests.

### Story 1.14: Mock Linear Adapter + Real Linear Adapter Sharing Port

As a foundation developer,
I want an `IntegrationLinkService` + `LinearAdapter` port with both a mock and a real implementation sharing one interface,
So that foundation-slice demos and contract tests run without Linear API access and the real adapter lands alongside — preventing integration flakiness from blocking E1 completion and preserving clean port boundaries the architecture requires.

**Acceptance Criteria:**

1. **Given** the `application.integration` package, **Then** `IntegrationLinkService` and a `LinearAdapter` port (interface) exist in `adapters.integration.linear`; the port carries only domain-shaped methods (e.g., `fetchTicketByReference(ref)`, `pollNewTickets(since)`, `postGovernedRunComment(ref, summary)`) — Linear-specific types (GraphQL DTOs, Linear auth tokens) must not leak through the port (verified by ArchUnit in story 1.11).
2. **Given** `LinearMockAdapter` activated by Spring profile `linear-mock` (default in `test`, optional in `local` and `demo`), **Then** it implements `LinearAdapter` backed by an in-memory or file-seeded fixture ticket set.
3. **Given** `LinearRealAdapter` activated by profile `linear-real`, **Then** it implements `LinearAdapter` via Linear GraphQL polling intake, uses credentials from environment/config (never hardcoded, never logged), and applies idempotency by (ticket identity + repository context) per architecture requirement.
4. **Given** `IntegrationLinkService.linkTicket(workflowRunId, linearTicketRef)`, **When** called, **Then** it creates an `integration_links` row with `ilk_` prefix, `integration_type=linear`, `external_ref`, `workflow_run_id`, `created_at`, `last_sync_at`, `sync_status` — uniqueness constraint prevents double-linking the same ticket to the same run.
5. **Given** conflicting link attempts (same ticket already linked to a different run), **When** attempted, **Then** the service raises `INTEGRATION_LINK_CONFLICT` with details pointing to the existing run — no silent overwrite (architecture requirement).
6. **Given** fetch failures (network, auth, rate-limit for real; simulated for mock), **When** encountered, **Then** the service classifies the failure per `IntegrationFailureCategory` registry values (`sync_failure`, `link_failure`, `state_conflict`, `network_api_failure`) — never a generic unclassified error.
7. **Given** a ticket reference that resolves to no ticket, **When** `fetchTicketByReference` returns empty, **Then** the command layer raises `LINEAR_TICKET_NOT_FOUND` — CLI surfaces this in story 1.15's `submit` command.
8. **Given** `LinearMockAdapter` fixtures, **Then** at least three fixture tickets exist representing: a bounded low-risk feature, a bug fix, a documentation ticket — each with a stable reference ID and deterministic metadata for test reuse.
9. **Given** freshness expectations, **Then** real-adapter polling interval is configurable (default 60s) and `integration_links.last_sync_at` is updated on each successful poll; stale detection uses configurable thresholds.
10. **Given** CLI commands in story 1.15, **When** a ticket is submitted with profile `linear-mock`, **Then** the flow completes without any network call — proven by tests that run with network access blocked.

### Story 1.15: Spring Shell CLI Commands — submit, status, history

As a pilot installer or workflow-owner developer,
I want `deliveryline submit`, `deliveryline status`, and `deliveryline history` Spring Shell commands,
So that I can submit a Linear ticket reference, inspect the current state of a governed run, and view its append-only event history end-to-end from the command line — proving the foundation without requiring any UI.

**Acceptance Criteria:**

1. **Given** the `adapters.cli` package, **Then** `WorkflowCommands` class registers Spring Shell commands `submit`, `status`, `history` — each a thin adapter over `WorkflowCommandService` (story 1.7) with no business logic in the command class (enforced by ArchUnit in story 1.11).
2. **Given** `deliveryline submit --ticket LIN-123 [--idempotency-key K]`, **When** run, **Then** the CLI resolves the ticket via `LinearAdapter`, constructs a `SubmitWorkflowCommand`, invokes the service, and prints: `{runId} submitted (state: Inbox)` with `runId` in the `run_` prefix format on success; on failure it prints `[{code}] {detail}` with a mapped exit code (from story 1.8).
3. **Given** `deliveryline submit` without `--idempotency-key` in interactive mode, **Then** a UUIDv7 is auto-generated and printed in verbose output; in non-interactive/scripted mode (e.g., redirected stdout) the key is required and its absence returns `MISSING_IDEMPOTENCY_KEY`.
4. **Given** `deliveryline status {runId} [--format=text|json]`, **When** run, **Then** the CLI prints: current state, current actor, last event type, last event timestamp, latest artifact type+version (if any), linked Linear ticket reference, and next safe action (from story 1.18 inspection logic). JSON output is stable-schema and documented.
5. **Given** `deliveryline history {runId} [--format=text|json] [--since=...]`, **When** run, **Then** the CLI prints the append-only event list with: timestamp, event type, actor, actor type, prior state → resulting state, reason (if present), intervention marker (if present); output supports tail-style filtering by `--since` timestamp.
6. **Given** a non-existent `runId`, **Then** all three commands return `RUN_NOT_FOUND` with stable CLI exit code.
7. **Given** `status` and `history` with `--format=json`, **Then** the JSON output conforms to a versioned schema documented in the CLI reference (so scripts can rely on it); adding fields is backward-compatible, removals are breaking and require schema version bump.
8. **Given** inspection performance, **Then** `status` returns in under 2 seconds for pilot-size runs (NFR25) and `history` returns in under 5 seconds for up to 100 events (NFR26+NFR27) — measured against a seeded fixture run in tests.
9. **Given** structured logging (wired in story 1.19), **Then** each CLI command emits a structured log line with `correlationId`, `commandName`, `workflowRunId` (when applicable), `outcome`, and `durationMs`.
10. **Given** FR coverage, **Then** these three commands satisfy FR1 (initiate workflow), FR2 (associate with ticket ref), FR3 (one workflow), FR4 (see current stage — CLI), FR22 (stage-by-stage history — CLI), FR23 (who/what acted — CLI).

### Story 1.16: DoctorService + DoctorCommand

As a pilot installer,
I want `deliveryline doctor` that checks every runtime prerequisite (Java version, Spring profile, PostgreSQL connectivity, Flyway state, required directories, config file permissions, Docker availability, runner image availability, REST bind address) and reports stable human-readable + JSON output,
So that I can distinguish missing prerequisites from product bugs before attempting real work — a broken install cannot masquerade as a product failure.

**Acceptance Criteria:**

1. **Given** the `application.diagnostics` package, **Then** `DoctorService` exists with a `runDiagnostics()` method returning a typed `DiagnosticsReport` (list of named checks, each with status `PASS|WARN|FAIL|SKIP`, optional remediation hint, optional stable infrastructure error code).
2. **Given** `deliveryline doctor [--format=text|json]`, **When** run, **Then** the CLI executes diagnostics and prints either (a) a human-readable report with color-coded statuses, or (b) a JSON report conforming to a documented schema — JSON output is the machine-readable contract for CI/scripts.
3. **Given** the check set, **Then** diagnostics cover: **Java version** (21+), **Spring profile** (one of `local`/`test`/`demo` active — error if `prod`-like profile detected), **PostgreSQL connectivity** (TCP reachable, credentials valid, database exists), **Flyway migration state** (current schema version matches expected baseline, no pending failures), **required directories** (`{DELIVERYLINE_HOME}/artifacts` writable, config dir readable), **config file permissions** (where OS supports — rejects world-readable `.env` on Linux/macOS), **Docker availability** (daemon reachable — warns if not, since runner images are only needed in E3), **runner image availability** (checks required tags when `runners` compose profile is active), **REST bind address** (port available, bound to loopback — not `0.0.0.0`), **frontend asset presence** (skipped in E1 — placeholder that resolves PASS; E2 activates real check), **supported-environment match** (story 1.17 — asserts current OS+shell+Docker combo is in the supported matrix).
4. **Given** stable infrastructure error codes in `DomainErrorCode` (story 1.4), **Then** each FAIL carries a code like `DOCTOR_POSTGRES_UNREACHABLE`, `DOCTOR_FLYWAY_FAILED`, `DOCTOR_REST_BIND_UNAVAILABLE`, `DOCTOR_DOCKER_MISSING`, `DOCTOR_CONFIG_PERMISSIONS_UNSAFE`, `DOCTOR_UNSUPPORTED_ENVIRONMENT`, `DOCTOR_ARTIFACT_DIR_UNWRITABLE`.
5. **Given** JSON output, **Then** exit code reflects overall status: `0` if all PASS/WARN/SKIP, `1` if any FAIL — CI can gate on exit code alone.
6. **Given** individual check granularity, **Then** `--only={check-name}` runs a single named check and `--exclude={check-name}` skips one — enabling CI pipelines to gate on essential checks while deferring optional ones.
7. **Given** remediation hints, **Then** each FAIL includes an actionable suggestion (e.g., "Run `docker compose up -d postgres` to start PostgreSQL" or "Set `spring.profiles.active=local` in `application-local.yml`") with a pointer to the relevant docs section.
8. **Given** the `doctor` output, **Then** no secrets, full credential values, or unredacted env blocks appear — redaction via `RedactionPolicyService` (story 1.10) runs on any diagnostic string before output.
9. **Given** diagnostics must not mutate state, **Then** `DoctorService` is read-only — it probes but never writes, migrates, or modifies configuration; a test asserts no durable writes occur during a `doctor` run.
10. **Given** readiness gating, **Then** the CLI's `submit`/`status`/`history` commands may optionally call `DoctorService.runEssentialChecks()` on startup (configurable — default off for `local`, on for `demo`), surfacing infrastructure failures before the command reaches the service layer.

### Story 1.17: Supported-Environment Matrix + Cross-Platform Scripts

As a pilot installer,
I want an explicit supported-environment matrix (Windows 11 + PowerShell + Docker Desktop; macOS 14+ + Docker Desktop; Ubuntu 22.04+ + Docker Engine; WSL2 as a Linux variant) enforced by `doctor` + per-shell install/reset/build/export scripts,
So that pilot adoption doesn't break on Windows vs Unix path differences, and the NFR37 "supported environment assumptions" requirement is concrete, not hand-wavy.

**Acceptance Criteria:**

1. **Given** `docs/supported-environments.md`, **Then** it documents the exact supported combinations: **Windows 11 Pro/Enterprise** + PowerShell 5.1 or 7+ + Docker Desktop 4.x; **macOS 14+ (Sonoma)** + zsh or bash + Docker Desktop 4.x; **Ubuntu 22.04+ LTS** + bash + Docker Engine 24+; **WSL2 Ubuntu 22.04+** (treated as Linux) + bash + Docker Desktop WSL2 integration. Each row includes required Java version (21 Temurin/Adoptium), required Node version (20.19+ or 22.12+ — for E2's frontend), and documented known-issue footnotes.
2. **Given** `DoctorService.checkSupportedEnvironment()`, **When** run, **Then** it detects the current OS, shell, and Docker runtime and returns PASS if the combination is in the matrix, WARN for untested-but-likely-compatible combinations (e.g., Windows 10, macOS 13), or FAIL with code `DOCTOR_UNSUPPORTED_ENVIRONMENT` for combinations outside the matrix.
3. **Given** `scripts/` directory at the repo root, **Then** each entry script exists in both shells: `doctor.ps1` + `doctor.sh` (invokes the CLI `doctor` command with sensible defaults), `reset-local.ps1` + `reset-local.sh` (stops compose, removes volumes, clears `{DELIVERYLINE_HOME}` artifacts, resets Flyway schema), `build-runner-images.ps1` + `build-runner-images.sh` (builds Codex + Claude runner Docker images — placeholder logic in E1; E3 completes), `export-run.ps1` + `export-run.sh` (placeholder for E5 export command — prints "not available until Epic 5" with exit 2 in E1).
4. **Given** path handling across shells, **Then** all scripts use cross-platform path construction (PowerShell uses `Join-Path`; bash uses `"${VAR}/path"` quoting) — never hardcoded separators that break on the other platform.
5. **Given** `application-local.yml` path handling in the backend, **Then** file paths resolve via `Paths.get(...)` / `Path.of(...)` and never assume forward slashes; a test asserts round-trip path resolution on Windows-style and Unix-style fixture paths.
6. **Given** `PowerShell 5.1` vs `PowerShell 7+` compatibility, **Then** `.ps1` scripts declare `#Requires -Version 5.1` at the top, avoid PowerShell 7-only operators (`??`, `?.`, `?:`), and set encoding to UTF-8 without BOM for any file writes the backend will consume.
7. **Given** WSL2 specifics, **Then** `doctor` detects WSL2 via `/proc/version` and warns if Docker Desktop's WSL2 integration is not enabled; `docker-compose.yml` PostgreSQL binding uses a WSL2-compatible address (documented in supported-environments.md).
8. **Given** the scripts and `doctor`, **Then** a CI matrix job runs `doctor.sh` on Ubuntu-latest and `doctor.ps1` on windows-latest GitHub runners, asserting PASS on a default-seeded environment — catching platform-specific regressions before merge.
9. **Given** NFR40 (setup completable without platform-engineering support), **Then** the supported-environment docs include a "Known-good quickstart in 10 minutes" section with copy-paste commands for each OS — validated by a documentation-increment acceptance check in story 1.22.
10. **Given** an unsupported environment, **Then** `doctor` fails fast with explicit remediation ("To run DeliveryLine on {detected OS+shell}, see docs/supported-environments.md for currently supported combinations — this combination is not tested and may require contributions to the scripts under `scripts/`.").

### Story 1.18: CLI Minimum-Viable-Recovery Baseline

As a pilot installer or workflow-owner developer,
I want `deliveryline retry {runId}` and detailed failure diagnostics via `status`/`history` that surface failed stage, last successful stage, failure timestamp, failure category, and next safe action,
So that when the first pilot run fails — which will happen before Epic 4 ships — there is a visible CLI recovery path rather than a stranded run (pre-mortem finding + thesis-marker promise).

**Acceptance Criteria:**

1. **Given** the `application.recovery` package stub, **Then** `RecoveryService` exists with baseline methods: `retry(runId, idempotencyKey, actor)`, `describeFailure(runId)`; deep reconciliation, reruns-from-arbitrary-steps, failure-taxonomy classification, and operator reconciliation live in Epic 4 (explicitly scoped out here — party-mode finding from John on E3/E4 recovery scope line).
2. **Given** `deliveryline retry {runId} [--idempotency-key K]`, **When** invoked on a run in state `Failed`, **Then** the command constructs a `RetryWorkflowCommand` (story 1.7) and the service: (a) re-runs the last failed step via `RunnerBroker` with a fresh runner-execution ID + fresh context bundle version, (b) appends a `recovery_actions` row (`rcv_` prefix) linking the retry to the failed `workflow_events` row, (c) appends a `recovery.retried` event, (d) transitions the run back to the appropriate executing state per the transition table (story 1.5).
3. **Given** `retry` on a run not in `Failed` state, **Then** `RETRY_NOT_APPLICABLE` is returned with `details.currentState` — retry is only valid from a failed state in this baseline (Epic 4 adds retry-from-other-states + resume + reconcile).
4. **Given** `retry` without an idempotency key in interactive CLI, **Then** a UUIDv7 is auto-generated (consistent with story 1.15); repeated retries with the same key replay (no double-execution).
5. **Given** `deliveryline status {runId}` (story 1.15) on a `Failed` run, **Then** the output includes: **failed stage** (the state prior to `Failed`), **last successful stage**, **failure timestamp**, **failure category** (from registry — `runner_timeout`, `runner_crash`, `runner_contract_violation`, etc.), **last activity timestamp**, **next safe action** (either `retry` with the CLI invocation shown, or `await_operator_action` if no baseline recovery path applies).
6. **Given** `deliveryline history {runId}` on a `Failed` run, **Then** the event list clearly shows the failure event with its category and the chain of events leading to it — no raw stack traces in the CLI output; diagnostic details live in structured logs (story 1.19).
7. **Given** `RecoveryService.describeFailure(runId)`, **When** called, **Then** it returns a typed `FailureDescription` (current state, failed stage, last successful stage, failure timestamp, failure category, last activity timestamp, next safe action, optional diagnostic reference pointing to the correlation ID for log lookup).
8. **Given** artifact-operation conflicts that are not safe to auto-retry, **When** `describeFailure(runId)` or `status {runId}` encounters them, **Then** the baseline recovery surface reports `nextSafeAction=await_manual_reconciliation` (or equivalent stable wording) rather than pretending a normal retry is safe; the richer reconciliation workflow still belongs to Epic 4.
9. **Given** `recovery_actions` table (from story 1.3), **Then** each row carries `rcv_` prefix ID, `workflow_run_id`, `action_type` (E1 scope: `retry`; E4 adds `rerun`, `resume`, `takeover`, `pause`, `reconcile`), `triggering_event_id`, `resulting_event_id`, `actor_identity`, `created_at`, `idempotency_key`, `result_status`.
10. **Given** append-only guarantee (NFR4), **Then** retry never erases or mutates prior events or state — tests prove that a retried run's history retains the original failure event and adds new events for the retry attempt and its outcome.
11. **Given** scope boundary with Epic 4, **Then** a comment in `RecoveryService` explicitly lists the methods Epic 4 will add (not stubs — Epic 4 adds them) and the CLI help text for `retry` notes that deeper recovery (reconcile, take over, rerun-from-arbitrary-step, failure-taxonomy classification, operator console) arrives in a later epic.
12. **Given** party-mode finding (John): "UI recovery baseline" scope in E3 is limited to surfacing these same concepts in the UI — not net-new recovery behaviors. A test asserts that `RecoveryService` exposes exactly and only the baseline methods in E1; any attempt to add a deeper-recovery method without an Epic-4 story fails an ArchUnit rule flagging `RecoveryService` as a scope-protected class.

### Story 1.19: Structured Logging + Correlation IDs

As a foundation developer,
I want structured logging with stable correlation field names (`correlationId`, `workflowRunId`, `runnerExecutionId`, `artifactId`, `artifactOperationId`) threaded through every command, runner dispatch, artifact operation, and failure path,
So that pilot-use diagnostics can be traced across CLI commands, workflow transitions, and runner executions — and no raw secrets, unredacted context bundles, or runner output reach the logs.

**Acceptance Criteria:**

1. **Given** `infrastructure.observability` package, **Then** a logging configuration (Logback or equivalent) emits structured key-value log entries to stdout with a documented JSON schema for the `demo` profile and human-readable pattern for `local`.
2. **Given** every CLI/REST command entry point, **Then** a `correlationId` (UUIDv7) is generated if not provided, stored in MDC/ThreadLocal context, and stamped on every subsequent log line produced during that command's execution.
3. **Given** workflow-scoped operations, **Then** log lines emitted during a workflow run include `workflowRunId`; runner-scoped log lines include `runnerExecutionId`; artifact-scoped log lines include `artifactId` and `artifactOperationId` where relevant.
4. **Given** the correlation field names, **Then** they match the architecture hard invariants exactly: `correlationId`, `workflowRunId`, `runnerExecutionId`, `artifactId`, `artifactOperationId` — not camelCase variants, not snake_case variants (enforced by a logging contract test that greps fixtures).
5. **Given** `RedactionPolicyService` (story 1.10), **Then** all log append paths route through a redacting appender/layout that invokes the service — a contract test feeds an adversarial fixture into the logger and asserts no raw secret appears in the rendered output.
6. **Given** raw runner output, context bundles, and credentials, **Then** they must not appear in logs at any level (INFO, DEBUG, TRACE) — a test fixture deliberately tries to log each and asserts the output is redacted or the append is rejected.
7. **Given** application events, **Then** workflow events emitted via `WorkflowTransitionService` are the product audit record (persisted in `workflow_events`); application logs are *technical* diagnostics only — never a substitute for workflow events. A review checklist in the logging ADR reinforces this.
8. **Given** REST requests, **Then** a request-scoped filter generates or extracts a `correlationId` from a documented request header (`X-Correlation-Id` — accepted if present and valid UUID, auto-generated otherwise) and stamps it on both log MDC and the Problem Details `instance` field (story 1.8) when an error occurs.
9. **Given** CLI commands, **Then** `--verbose` flag surfaces the current correlation ID to stdout (for operators to grep in logs later) without surfacing sensitive payload content.
10. **Given** the logging tests, **Then** they cover: correlation propagation across service calls, MDC clearing between commands (no leakage between CLI invocations), redaction of adversarial fixtures, and schema stability of the JSON log format under the `demo` profile.

### Story 1.21: GitHub Actions CI Tiered Pipeline

As a foundation developer,
I want a GitHub Actions CI pipeline with explicit tiered gates (formatting → contracts → frontend build → backend unit/application → architecture/persistence/redaction/export contract → runner image compat → jar packaging → bundled-jar smoke → export redaction verification),
So that fast checks fail fast, Docker-backed tests don't pollute flake metrics, and CI gate order matches the architecture's documented sequence (AR28).

**Acceptance Criteria:**

1. **Given** `.github/workflows/ci.yml`, **Then** the pipeline is structured as explicit named jobs (or stages within jobs) matching AR28's order: `format-static-checks` → `runner-contract-fixtures` → `frontend-build-tests` → `backend-unit-tests` → `backend-contract-tests` (API, architecture/ArchUnit, persistence-Testcontainers, redaction, export, runner-contracts integration) → `runner-image-compat` → `jar-packaging` → `bundled-jar-smoke` → `export-redaction-verify`.
2. **Given** gate dependencies, **Then** each subsequent job lists the prior job(s) in `needs:` so fast-failing early tiers short-circuit the slower ones — minimizing wasted CI minutes.
3. **Given** the OS matrix per story 1.17, **Then** `format-static-checks`, `backend-unit-tests`, and `doctor`-invoking smoke jobs run on both `windows-latest` and `ubuntu-latest`; Docker-backed jobs (Testcontainers, runner-image-compat, bundled-jar-smoke) run on `ubuntu-latest` only.
4. **Given** runner images, **Then** `runner-image-compat` builds Codex + Claude runner Dockerfiles (E1 placeholders; E3 populates) and validates they respond to the `RunnerContractValidator` with schema v1 fixtures — failing fast if a runner's stub contract drifted.
5. **Given** flake visibility, **Then** CI does NOT apply blanket retries to Docker-backed tests — flake metrics are surfaced, not masked (party-mode finding from Murat on flakiness as tech debt). Legitimate retry policies (e.g., container-start flakes on cold-boot) are applied narrowly with documented justification in the workflow file.
6. **Given** the OpenAPI contract (story 6.9), **Then** a CI step regenerates the OpenAPI doc and `git diff` fails the job if committed snapshot drifts from generated — catching accidental contract changes.
7. **Given** the ArchUnit test tier (story 1.11), **Then** it runs as a named job, fails loudly with the offending class + rule + remediation hint, and does NOT run silently warn-only.
8. **Given** PR vs main branch, **Then** per-PR CI runs the full tiered pipeline; a post-merge job on `main` additionally runs the `bundled-jar-smoke` with full runner-image build (more expensive) for release readiness.
9. **Given** the `foundation-gate` verification job (story 1.23), **Then** it is wired as a required status check on branch protection — structurally blocking any Epic 2/3/4 PR that is opened before foundation-gate passes.
10. **Given** observability of CI runs, **Then** each job emits structured summary artifacts (test report, JUnit XML, coverage report) uploaded as GitHub Actions artifacts for triage on failure.

### Story 1.22: Setup + CLI First-Run Quickstart Documentation

As a pilot installer,
I want `docs/quickstart.md` plus `docs/setup-local.md` that walk me through installation, credential configuration, `doctor` verification, and submitting my first governed ticket end-to-end via CLI against the `linear-mock` profile,
So that I can complete a first-run experience cold without live assistance — satisfying NFR42 (a pilot user runs one low-risk ticket through the guided workflow using documented setup and tutorial material) for the CLI slice (UI tutorial lives in Epic 2+).

**Acceptance Criteria:**

1. **Given** `docs/quickstart.md`, **Then** it contains a target completion time ("~15 minutes from zero to first governed run") and a linear sequence of steps: prerequisites check → clone repo → choose environment (Win/macOS/Linux per story 1.17) → `docker compose up -d postgres` → run `doctor` → configure `.env` from `.env.example` → run `submit` with a mock Linear ticket reference → run `status` → run `history` → interpret the output.
2. **Given** `docs/setup-local.md`, **Then** it explains in depth: supported-environment matrix (link to story 1.17's `docs/supported-environments.md`), Java 21 install, Docker Desktop install per OS, `.env` structure (no secrets committed), Spring profile choice (`local` vs `demo`), database schema migration (Flyway V1 auto-applies on first start), and how to reset local state via `scripts/reset-local.{ps1,sh}`.
3. **Given** `docs/failure-recovery-walkthrough.md`, **Then** it explains the minimum-viable-recovery baseline (story 1.18): what a failed run looks like in `status`/`history`, how to interpret failure categories, when to run `retry` vs when to wait (E4 expands this walkthrough — E1 ships the CLI subset with a forward reference to the full operator walkthrough in E4).
4. **Given** the three docs above, **Then** every command shown uses a copy-paste-ready block with no placeholder `{}` values that require substitution — wherever substitution is needed (e.g., `{DELIVERYLINE_HOME}`), a preceding sentence shows how to resolve the value.
5. **Given** NFR45 (first-run documentation must include a happy-path tutorial and at least one failed-run recovery walkthrough), **Then** both exist at Epic 1 close: happy path in `quickstart.md`, failed-run in `failure-recovery-walkthrough.md` (CLI subset).
6. **Given** per-OS paths, **Then** examples show PowerShell + bash variants side-by-side (or in toggled tabs if the docs site renders them) — never a Linux-only or Windows-only command without the counterpart.
7. **Given** a pilot-installer validation run (party-mode finding from John: "name the human validator per epic"), **Then** the docs section lists a placeholder line for "Pilot-installer validator: ***\_***__ (to be named before Epic 1 close)" — reminding Alex to identify and coordinate with the real human whose cold-install walkthrough gates the epic.
8. **Given** a link-check CI step (story 1.21 gate), **Then** all internal doc links in `quickstart.md`, `setup-local.md`, and `failure-recovery-walkthrough.md` resolve to real files and all external links (Linear API docs, Docker install pages) pass a smoke check.
9. **Given** documentation-increment acceptance (per the epic's explicit doc-increment rule from pre-mortem refinement R7), **Then** Epic 1 cannot close without these docs merged — enforced by the foundation-gate verification (story 1.23) checking their presence.
10. **Given** NFR43 (minimize new concepts), **Then** the quickstart uses only the concept set declared in the PRD: ticket, spec, run, artifact, review, failure, recovery action — new terms introduced in docs require a glossary entry in `docs/glossary.md`.

### Story 1.23: Foundation-Gate CI Verification + Deterministic Fixture Event Stream for Epic 2

As a foundation developer and future Epic 2 implementer,
I want a single foundation-gate CI verification story that asserts every foundation contract is live end-to-end AND publishes a deterministic fixture event stream consumable by Epic 2 UI tests,
So that any Epic 2/3/4 PR opened before foundation contracts hold is structurally blocked by CI, and Epic 2's UI composites can be tested against real workflow events without requiring a live runner (party-mode findings #1, #2, #4 all converge here).

**Acceptance Criteria:**

1. **Given** `backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java`, **Then** it runs as a standalone test class tagged with a JUnit `@Tag("foundation-gate")` and is wired as a dedicated CI job `foundation-gate` in `.github/workflows/ci.yml`.
2. **Given** the verification test, **Then** it asserts each foundation contract is live: ArchUnit package-boundary tests all pass (story 1.11), Flyway V1 applies cleanly against a fresh Testcontainers PostgreSQL (story 1.3), central registries pass drift tests (story 1.4), `WorkflowTransitionService` rejects every canonical illegal transition + runner-failure transitions are present (story 1.5), `RunnerContractValidator` accepts all `valid/` fixtures and rejects every `invalid/` fixture with the expected error (story 1.6), shared command model produces identical `DomainResult` across CLI and REST for the same payload (story 1.7), `ProblemDetailsMapper` returns stable `DomainErrorCode`s for each registered code (story 1.8), `IdempotencyService` passes the full replay/conflict/race/stale-reservation test matrix (story 1.9), `RedactionPolicyService` redacts every adversarial fixture (story 1.10), `ArtifactOperationService` enforces availability gating before approval eligibility (story 1.12).
3. **Given** branch protection on `main`, **Then** the `foundation-gate` CI job is a **required status check** — Epic 2, 3, or 4 PRs cannot merge to `main` unless `foundation-gate` is green on their branch. Party-mode finding (Murat): the foundation-gate job re-runs on every PR, including E3 PRs, so contract regressions introduced downstream are caught — not just on E1 PRs.
4. **Given** `backend/src/test/resources/fixture-event-streams/`, **Then** a deterministic fixture event stream is published containing at least three pre-canned governed runs, each with full event history (submission → spec stage events → approval events → execution events → outcomes) and matching artifact fixtures — covering happy-path success, spec-rejection-and-resubmit, and execution-failure-with-retry scenarios.
5. **Given** the fixture event stream, **Then** its format matches the same JSON schema `GET /api/v1/workflows/{id}/events` returns (story 6.9), so Epic 2's UI can be developed and tested against it without requiring a live backend or runner — and regression-tested against the schema when Epic 2 integrates with real endpoints.
6. **Given** fixture event stream integrity, **Then** a contract test asserts each fixture run's event sequence is a valid sequence under the state-transition table (story 1.5) — no fixture can encode an impossible transition that would mislead Epic 2 UI development.
7. **Given** party-mode finding #2 (runner schema v1 artifact variants enumerate spec/implementationPlan/prOutput), **Then** the fixture event stream includes at least one run producing each artifact variant — so Epic 2's Artifact Review Panel composite can be generalized from day one (party-mode finding #3: ARP must not be spec-hardcoded) against real fixture diversity.
8. **Given** fixture documentation, **Then** `backend/src/test/resources/fixture-event-streams/README.md` explains each fixture run's scenario, the states it exercises, and which E2 composites should rely on it — enabling E2 story writers to reference specific fixtures by name.
9. **Given** the `foundation-gate` job's green signal, **Then** a CI comment is posted on E2/E3/E4 PRs confirming "Foundation gate passing on this branch — safe to merge after normal review" — making the structural gate visible to reviewers rather than implicit.
10. **Given** Epic 1 close, **When** `foundation-gate` turns green on `main`, **Then** the epic's Definition of Done is satisfied: all remaining Epic 1 stories merged, foundation-gate CI job is a required check on `main`, fixture event stream published, documentation increments (story 1.22) merged, pilot-installer cold-run walkthrough validated (human validator named per story 1.22 AC7) — Epic 1 is declared complete and Epic 2/3/4 may begin.
11. **Given** regression protection, **When** any future PR introduces a change that breaks a foundation contract, **Then** the `foundation-gate` job fails on that PR regardless of which epic it belongs to — preventing downstream epics from silently eroding foundation invariants (party-mode finding from Murat + architecture readiness caveat).

---

## Epic 2: Specification Review & Product Approval (UI + PM Loop)

A Product Manager opens the review queue in the bundled React UI, opens a governed run, reads the current specification, answers open clarification questions in context, and approves or rejects with structured feedback — seeing their input visibly change workflow state. Delivers the full design system foundation, tri-pane shell, TanStack Router + TanStack Query infrastructure, and the five Phase-1 workflow composites. Meets WCAG 2.1 AA. The Artifact Review Panel and Approval/Decision Bar are built as generalized composites from day one so Epic 3 adds variants without reshaping infrastructure.

**Epic 2 critical-path dependency edges (per sprint-change proposal 2026-05-19):**

Story IDs remain stable. The following execution-order edges are enforced at sprint planning + CI branch-protection time, NOT by renumbering:

- **2.24 (Artifact Content Sanitization + Redaction-Gap Closure)** must merge before any of: **2.15** (Run / Review Queue Item — renders queue-item artifact metadata), **2.17** (Artifact Review Panel — Spec Variant — renders artifact body content), **2.18** (Clarification Region — renders clarification content from agent runners). Rationale: 2.24 closes F19/F20 redaction-policy gaps deferred from Epic 1; without it, 2.15/2.17/2.18 would render UI content that the backend redactor has not scrubbed for PEM blocks, bundle JSON shapes, Idempotency-Key headers, or credential/bearer/private patterns. See `sprint-change-proposal-2026-05-19.md` and Epic 1 retro (`epic-1-retro-2026-05-19.md`, section 6 "Significant Discovery").
- **Frontend-on-Windows tooling spike** (action A4 from Epic 1 retro) runs **in parallel with** Story 2.1 — not as a prerequisite. Downgraded from hard prerequisite per `sprint-change-proposal-2026-05-19-followup.md` (2026-05-19): spike Q1 (`mvn -pl deliveryline-frontend clean install` on Windows) requires the frontend module that Story 2.1 creates — chicken-and-egg. Q2/Q3/Q4/Q5 run against the in-flight 2.1 branch and findings fold into AC9 mid-flight (or a follow-up story if blockers surface). See `docs/spikes/2026-05-frontend-on-windows.md`.
- All other story-ordering follows the natural dependency reading of the AC text (e.g., 2.2 depends on 2.1's scaffold; 2.6's typed client depends on backend stories 2.13/2.14 publishing OpenAPI).

Enforcement: branch-protection (per story 1.21 AC7) extends to gate merges of 2.15/2.17/2.18 PRs on the presence of merged 2.24 commits on `main`. Mechanism: a required CI check (`dependency-edges`) verifies the dependency graph against the declared edges; the helper script lives in `scripts/ci/check-story-dependencies.{sh,ps1}` (extension of the branch-protection helpers from story 1.21).

### Story 2.1: Frontend Module Scaffolding (Vite React TypeScript + Maven Wiring)

As a frontend developer,
I want `deliveryline-frontend` initialized as a Vite React TypeScript project with Maven build integration,
So that the frontend module has a reproducible build graph inside the root Maven multi-module structure and CI can bundle React assets into the Spring Boot executable jar.

**Acceptance Criteria:**

1. **Given** the `deliveryline-frontend` Maven module (scaffolded as a stub in story 1.1), **When** the Vite React TypeScript app is initialized via `npm create vite@latest . -- --template react-ts` in the module directory, **Then** `package.json`, `vite.config.ts`, `tsconfig.json`, `src/main.tsx`, and `src/App.tsx` exist with React 18+ and TypeScript strict mode.
2. **Given** the Maven module's `pom.xml`, **Then** the `frontend-maven-plugin` (or equivalent) is configured to install Node 20.19+ or 22.12+ locally, run `npm ci` and `npm run build` during the `generate-resources` or `compile` phase, and place output in a canonical `target/dist/` directory.
3. **Given** the backend's `pom.xml`, **Then** its packaging step depends on `deliveryline-frontend` and copies the canonical `dist/` output into `backend/src/main/resources/static/` before jar assembly — failing the backend build if the frontend dist is missing (AR32 quality gate).
4. **Given** `mvn clean install` at the root, **When** run on a machine with Node 20.19+/22.12+ available locally (or via frontend-maven-plugin download), **Then** both modules build successfully and the backend jar contains the compiled React SPA under `BOOT-INF/classes/static/`.
5. **Given** the TypeScript config, **Then** `strict: true`, `noUncheckedIndexedAccess: true`, and `exactOptionalPropertyTypes: true` are enabled.
6. **Given** `.gitignore` in the frontend module, **Then** `node_modules/`, `dist/`, `.vite/`, and coverage dirs are excluded; package lockfile (`package-lock.json`) is committed.
7. **Given** per-OS support (story 1.17 matrix), **Then** frontend build works on Windows 11 PowerShell + Ubuntu 22.04 + macOS 14+ — verified by an **in-story** CI matrix extension (NOT inherited from 1.21's collapsed-to-Ubuntu doctor-smoke job). Story 2.1 ships a `frontend-build` CI job with `strategy.matrix.os` = `[ubuntu-latest, windows-latest]` (macOS deferred per cross-platform support tier from 1.17), both running `mvn -pl deliveryline-frontend clean package` end-to-end; both must be green before merge. A failing Windows job is build-blocking — never a warning, never skippable. Rationale documented inline referencing the Epic 1 retro finding (2026-05-19, sprint-change proposal).
8. **Given** a development workflow, **Then** `npm run dev` inside the frontend module starts Vite's dev server on a documented port (default 5173, configurable via PORT env per AC9c), proxying `/api/*` requests to the Spring Boot backend on `localhost:8080` — configured in `vite.config.ts`. The proxy config works identically on Windows PowerShell, Windows Git Bash, Ubuntu, and macOS — verified manually during Story 2.1 implementation (informed by spike Q4 findings from `docs/spikes/2026-05-frontend-on-windows.md` if available; spike now runs in parallel per `sprint-change-proposal-2026-05-19-followup.md`, not pre-story).
9. **Given** Windows + Linux line-ending and path-length differences discovered in the pre-2.1 frontend-on-Windows tooling spike (per sprint-change proposal 2026-05-19, action A4 — see `docs/spikes/2026-05-frontend-on-windows.md`), **Then**: (a) `deliveryline-frontend/.gitattributes` declares `* text=auto eol=lf` for source files and `*.bat text eol=crlf` for any Windows-only scripts, preventing CRLF contamination of snapshot tests and build artifacts; (b) Path lengths inside `node_modules/` are documented as a known Windows risk; if any transitive dep exceeds `MAX_PATH=260` chars under default Windows config, the spike report identifies a mitigation (long-paths enabled in project README, or transitive dep replaced) before story 2.1 ships; (c) Vite dev-server port (default 5173 per AC8) is documented as a possible conflict point on Windows; the dev-server config exposes a `PORT` env override documented in `frontend/README.md`.
10. **Given** the foundation-gate CI verification from story 1.23 (Epic 1 close gate), **Then** the gate's scope widens to include "frontend-build matrix green on the branch" — meaning the Windows + Ubuntu frontend-build jobs from AC7 are added to the foundation-gate `needs:` chain. A frontend-build failure on either OS blocks every subsequent Epic 2 / 3 / 4 PR from merging. The foundation-gate workflow file (`.github/workflows/ci.yml`) is updated in this story, NOT in a later story — preventing the regression class where "we'll wire it later" becomes "we shipped 8 stories on Linux-only".

**Spike parallelism (revised per `sprint-change-proposal-2026-05-19-followup.md`):** The frontend-on-Windows tooling spike (`docs/spikes/2026-05-frontend-on-windows.md`, retro action A4) runs **in parallel** with Story 2.1 — no longer a hard prerequisite. Rationale: spike Q1 (`mvn -pl deliveryline-frontend clean install`) requires the very frontend module Story 2.1 creates, making the original prerequisite chicken-and-egg. Spike Q2 (`.gitattributes` / line endings), Q3 (path length), Q4 (Vite dev-server / proxy), and Q5 (HMR file-locking) run against the in-flight 2.1 branch; findings fold into AC9 mid-flight. If a spike blocker surfaces that cannot be absorbed into 2.1's scope, it spawns a follow-up story rather than blocking 2.1's start.

### Story 2.2: Tailwind + shadcn/ui Setup + Primitive Inventory

As a frontend developer,
I want Tailwind CSS and shadcn/ui wired into the frontend with a primitive component inventory,
So that subsequent stories have the full primitive layer available and no story needs to author foundation UI components from scratch.

**Acceptance Criteria:**

1. **Given** the frontend module, **Then** `tailwind.config.ts`, `postcss.config.js`, and `src/styles/globals.css` are configured per the shadcn/ui + Tailwind initialization guide, with Tailwind's content globbing covering `src/**/*.{ts,tsx}`.
2. **Given** shadcn/ui CLI initialization, **Then** `components.json` exists with documented configuration (base color, style, CSS variables, component path under `src/components/ui/`).
3. **Given** the primitive inventory, **Then** the following shadcn/ui components are added via CLI and available under `src/components/ui/`: `button`, `input`, `textarea`, `label`, `dialog`, `sheet`, `popover`, `dropdown-menu`, `select`, `tabs`, `badge`, `alert`, `table`, `card`, `tooltip`, `scroll-area`, `accordion`, `collapsible`, `separator`, `toast` / `sonner`.
4. **Given** each primitive, **Then** it is used exactly as shadcn/ui provides it with minimal local customization — workflow-specific visual treatment comes from tokens (stories 2.3/2.4) and composites (stories 2.15–2.19), not from overriding primitives.
5. **Given** a shared `cn()` utility, **Then** `src/lib/utils.ts` exports the standard `cn()` helper (clsx + tailwind-merge) that composites use for conditional className composition.
6. **Given** the primitive layer, **Then** a minimal demo page (e.g., `src/routes/_dev/PrimitivesPlayground.tsx`, gated by dev-only route) renders every primitive in each documented state — serving as living documentation and smoke test, not a production route.
7. **Given** architecture requirement that "shadcn/ui primitives remain generic and reusable", **Then** no primitive file is edited to encode DeliveryLine-specific workflow concepts — an ESLint custom rule or grep-based CI check fails if `src/components/ui/*` files import from `src/features/workflows/` or reference workflow-domain types.
8. **Given** dark mode support, **Then** shadcn/ui's standard dark mode wiring is configured but not activated in E2 — dark mode is out of scope until explicitly prioritized post-MVP; docs note this choice.
9. **Given** the frontend build, **When** Tailwind processes `globals.css`, **Then** the production bundle strips unused utilities (content-purge enabled) and the dev bundle hot-reloads token changes without full-page refresh.

### Story 2.3: Design Tokens — Color Palette + Semantic State Variables

As a frontend developer,
I want a neutral/calm color token system with semantic state variables driven by CSS custom properties,
So that every workflow composite draws from one semantic palette, blocker/warning states are visually dominant, and no state relies on color alone.

**Acceptance Criteria:**

1. **Given** `src/styles/globals.css`, **Then** CSS custom properties define the base neutral palette (background, surface, elevated-surface, text-primary, text-secondary, text-tertiary, border) and a blue-green/teal accent family (`--accent-50` through `--accent-900`) per UX-DR2.
2. **Given** the semantic state token layer, **Then** tokens are defined for each documented state: `--state-informational`, `--state-success`, `--state-warning`, `--state-blocker`, `--state-draft`, `--state-selected`, `--state-loading`, `--state-error`, `--state-permission-restricted`, `--state-empty`, `--state-stale`, `--state-recovery` — each with foreground + background + border triplets and a dedicated high-contrast variant for accessibility edge cases.
3. **Given** Tailwind's theme extension, **Then** `tailwind.config.ts` exposes the tokens as utility classes (e.g., `bg-state-blocker`, `text-state-success`, `border-state-warning`) — composites consume tokens via utilities, not raw hex.
4. **Given** contrast requirements (WCAG 2.1 AA; story 2.25), **Then** every foreground/background pair in the semantic palette passes 4.5:1 for body text and 3:1 for large text — verified by an automated contrast test using the defined tokens.
5. **Given** the "no state by color alone" rule (UX-DR2), **Then** every state token has a documented non-color signifier (icon, label text, pattern) that composites must apply alongside the color — enforced in component-test fixtures.
6. **Given** blocker/warning dominance (UX-DR2), **Then** visual regression fixtures prove that a `state-blocker` badge is visually more prominent than a `state-informational` badge at the same size.
7. **Given** the accent palette, **Then** documentation (`src/styles/README.md`) explains: neutral surfaces for reading, accent for primary interactive actions only (no ambient decoration), blocker/warning for critical states, draft/stale for superseded content.
8. **Given** future dark-mode support (deferred per story 2.2 AC8), **Then** the token system is structured so dark-mode values can be added by overriding CSS custom properties under a `.dark` scope without restructuring composites.

### Story 2.4: Design Tokens — Typography + Spacing + Layout Primitives

As a frontend developer,
I want a typography hierarchy + hybrid 4px/8px spacing system + layout primitives implemented as design tokens,
So that composites have consistent reading rhythm, scanning density, and layout structure — supporting long-form spec reading and rapid scanning without dramatic stylistic contrast.

**Acceptance Criteria:**

1. **Given** typography tokens in `globals.css`, **Then** font stack (`--font-sans` = system modern sans-serif), font-size scale (`--text-xs` through `--text-2xl`), line-height scale (`--leading-tight` / `--leading-normal` / `--leading-relaxed`), and font-weight scale (`--weight-regular` / `--weight-medium` / `--weight-semibold` / `--weight-bold`) are defined.
2. **Given** typography hierarchy per UX-DR3, **Then** semantic classes exist for: page/panel title (h1 equivalent), workflow state / section heading (h2/h3), artifact body content (prose reading size), metadata / captions / secondary labels (smaller, muted), inline status / annotation (smallest, often bold or colored per state).
3. **Given** the hybrid spacing system per UX-DR4, **Then** Tailwind's spacing scale is configured to expose both a `4px` step (`space-0.5`, `space-1`, `space-1.5`, `space-2.5`, etc. — used for control internals, compact metadata, dense review rows) and an `8px` step (`space-2`, `space-4`, `space-6`, `space-8` — used for panel spacing, section separation, larger layout structure).
4. **Given** reading surfaces for long-form spec content (Artifact Review Panel in story 2.17), **Then** a documented `prose` utility applies readable line length (45–75 characters), comfortable line-height (≥ 1.5), and appropriate paragraph spacing.
5. **Given** layout primitive components (`src/components/layout/`), **Then** baseline primitives exist: `Stack` (vertical flex with gap), `Inline` (horizontal flex with gap), `Grid` (simple CSS grid), `Container` (max-width with horizontal padding), `Divider` (semantic section break) — each accepts spacing tokens as props.
6. **Given** focus-visible styling, **Then** a global focus ring token (`--ring-focus`) is defined and applied consistently via Tailwind's `focus-visible:ring-*` utilities — not default browser outlines, and high-contrast enough to satisfy WCAG 2.4.7.
7. **Given** adaptive density (UX-DR — medium-density default, compact for quick scanning, expanded for sustained reading), **Then** a `density` prop pattern is documented for composites (e.g., Queue Item in story 2.15 supports `compact | standard` density) using the 4px scale for compact and 8px for standard.
8. **Given** the tokens are ready, **Then** the primitives-playground route (story 2.2 AC6) renders typographic hierarchy + spacing scale + layout primitives so visual regressions are spottable before composite stories begin.

### Story 2.5: TanStack Router Setup + Typed Routes + Deep-Link Handling

As a frontend developer,
I want TanStack Router configured with typed route definitions for workflow list, workflow detail, artifact viewer, and root / not-found routes,
So that every navigation path is type-safe, deep links into run details work via SPA fallback (story 2.28), and missing workflows / unsupported routes have explicit UI states rather than blank pages.

**Acceptance Criteria:**

1. **Given** `src/routes/`, **Then** TanStack Router's route tree is defined with typed routes: `/` (root), `/workflows` (WorkflowsRoute — list), `/workflows/$workflowRunId` (WorkflowDetailRoute), `/workflows/$workflowRunId/artifacts/$artifactId` (ArtifactViewerRoute), `*` (NotFoundRoute).
2. **Given** route params, **Then** `$workflowRunId` and `$artifactId` are typed with validation that rejects malformed IDs (prefix must match `run_` or `art_` per story 1.4 prefix registry) — invalid params route to a dedicated "Invalid link" state rather than crashing.
3. **Given** a route loader pattern, **Then** each route uses TanStack Router's `loader` to prefetch the primary TanStack Query (story 2.6) data before rendering — so a deep-linked `/workflows/run_123` renders detail directly without a loading flash on navigation.
4. **Given** missing-resource handling, **Then** when a route loader returns a 404 from the backend, the route renders a dedicated "Run not found" / "Artifact not found" state (leveraging empty-state patterns from story 2.22) — not a generic error page.
5. **Given** deep links from external sources (email, CLI output from story 1.15, Linear comment), **Then** pasting a URL like `http://localhost:8080/workflows/run_123` loads the run detail directly — handled via SpaFallbackController from story 2.28.
6. **Given** browser back/forward, **Then** navigation preserves run identity and scroll position where meaningful; navigating from `WorkflowsRoute` → `WorkflowDetailRoute` → `ArtifactViewerRoute` → back returns to detail with the prior scroll state.
7. **Given** a generated route tree file, **Then** TanStack Router's code generation is wired into the Vite build (`tsr generate` or equivalent) and the generated file is gitignored — developers regenerate on route changes; CI regenerates + git-diffs to catch drift.
8. **Given** the "unsupported workspace states" case (UX-DR6), **Then** explicit UI handles: a run in a state the current build doesn't recognize (future E3+ states visible from older E2 builds), an artifact type the current ARP can't render, a permission-restricted navigation attempt.
9. **Given** correlation-ID propagation from story 1.19, **Then** route loaders include a correlation ID header on their API calls so server-side logs can be traced back to the UI navigation that triggered them.
10. **Given** party-mode finding #3 (generalized composites), **Then** the route tree is shaped so the same `WorkflowDetailRoute` serves spec-stage, implementation-plan-stage, and PR-output-stage runs in E3 without route structural changes — achieved by letting the backend-reported `currentStage` drive ARP variant selection inside the route, not by splitting routes per stage.

### Story 2.6: TanStack Query + Key Factories + Typed API Client Generated from OpenAPI

As a frontend developer,
I want TanStack Query configured with typed query key factories and a typed API client generated from the backend's OpenAPI spec,
So that server state is the single source of truth, query keys are stable and collision-free, and API contract drift between backend and frontend is caught at build time.

**Acceptance Criteria:**

1. **Given** `src/lib/api/client.ts`, **Then** a typed API client is generated from the backend's OpenAPI spec (from story 6.9) using `openapi-typescript-codegen` or `orval` or equivalent — generated client types are committed so frontend developers don't need live backend access to type-check.
2. **Given** the generation pipeline, **Then** `npm run generate-api` fetches the committed OpenAPI snapshot (`backend/src/main/resources/openapi/openapi.json`) and regenerates the client; CI diffs the committed generated output against a fresh regeneration to catch drift (matching story 1.21's OpenAPI drift check on the backend side).
3. **Given** `src/lib/queryKeys/workflowKeys.ts`, **Then** a typed query key factory exports stable keys: `workflowKeys.all`, `workflowKeys.list(filters)`, `workflowKeys.detail(workflowRunId)`, `workflowKeys.events(workflowRunId)`, `workflowKeys.artifacts(workflowRunId)`, `workflowKeys.artifact(artifactId)`, `workflowKeys.allowedActions(workflowRunId)` (story 2.14).
4. **Given** an ESLint custom rule, **Then** any inline `useQuery(['workflows', ...])` ad-hoc key in a component file fails lint — keys must come from `queryKeys/*` factories (party-mode finding from Winston on consistency).
5. **Given** `src/lib/api/problemDetails.ts`, **Then** a typed Problem Details error handler parses `application/problem+json` responses (story 1.8), exposes stable `code` / `status` / `retryable` / `details`, and TanStack Query's `onError` callbacks consume typed domain error codes — not raw HTTP status or string matching.
6. **Given** mutation patterns for E2's spec approve/reject/clarify (stories 2.13, 2.19), **Then** mutation hooks live under `src/features/workflows/hooks/` and each mutation invalidates the affected queries (`workflowKeys.detail`, `.events`, `.allowedActions`, and pending-review list) on success — architecture requirement.
7. **Given** the `Idempotency-Key` header pattern (story 1.9), **Then** every mutation hook generates a UUIDv7 idempotency key at mutation-start and includes it in the request — retries of the same mutation attempt reuse the same key.
8. **Given** typed TanStack Query hooks, **Then** `useWorkflowDetail`, `useWorkflowEvents`, `useArtifact`, `useAllowedActions` exist under `features/workflows/hooks/` — consumers are typed by generated API response shapes without runtime casts.
9. **Given** stale time / cache time defaults, **Then** workflow detail queries have short staleTime (e.g., 5s) to reflect workflow state freshness; event history queries can have longer staleTime since events are append-only; documented defaults live in a shared query-options utility.
10. **Given** NFR25/26/27 performance targets, **Then** TanStack Query's request deduplication + structural sharing prevents redundant backend calls when multiple composites in the same view consume overlapping data (e.g., ARP and Context Strip both reading run detail).

### Story 2.7: Tri-Pane Application Shell with Artifact-Primacy Layout Rules

As a PM, developer, or workflow owner,
I want a consistent tri-pane application shell — left navigation rail + central main review pane + right supporting context panel — with artifact-primacy layout rules that keep the main pane as the visual anchor,
So that every run view presents workflow truth in a stable, predictable layout where the artifact dominates and side panels support without competing.

**Acceptance Criteria:**

1. **Given** `src/features/workflows/AppShell.tsx` (or equivalent), **Then** the shell renders a three-region layout: left navigation rail (fixed width, queue/state navigation), central main review pane (flex-grow, houses Artifact Review Panel in stories 2.17+), right supporting context panel (fixed width, houses blockers/open questions/artifact metadata/history).
2. **Given** artifact-primacy rules (UX-DR5), **Then** the central main pane's minimum width is configured so that if viewport shrinks, the **right context panel collapses before the main pane narrows** — a layout test asserts this by shrinking viewport and checking main-pane width remains at or above the minimum.
3. **Given** the left navigation rail, **Then** it includes stable placement for: primary nav (queue home), current run identity (when inside a run), global app status indicator — and is keyboard-focusable with semantic `<nav>` landmark.
4. **Given** the right context panel, **Then** it can host (via slot/composition): Run Context Strip (story 2.16), Clarification Region (story 2.18) in sidebar variant, blocker list, artifact metadata, run history timeline — composites plug into the slot; the shell does not hard-code content.
5. **Given** responsive behavior (story 2.26 details), **Then** at tablet breakpoints the right panel becomes togglable (slide-out or drawer), and at mobile the tri-pane collapses to single-column with progressive disclosure — preserving artifact-primacy priority.
6. **Given** the visual language per UX-DR5, **Then** panel borders are restrained (1px with subtle token colors), background surfaces use neutral palette from story 2.3, and the main pane has slightly more vertical whitespace headroom than side panels to signal visual primacy.
7. **Given** landmarks and semantics for accessibility (story 2.25), **Then** the shell uses `<nav>`, `<main>`, and `<aside>` landmarks with labeled regions (`aria-label="Workflow navigation"`, `aria-label="Supporting context"`) — keyboard users can skip between regions with standard landmark navigation.
8. **Given** the "hybrid coherence rules" (UX spec § Hybrid Coherence Rules), **Then** the tri-pane shell remains structurally stable across normal review and Compare Mode (Epic 4) and clarification states — it is not a "mode switch"; the same shell hosts different panels per state.
9. **Given** scroll isolation, **Then** each region scrolls independently — the main pane's long artifact reading doesn't scroll the nav rail or context panel — supporting long-form spec inspection without losing context.
10. **Given** the "artifact primacy is a hard rule" directive (UX-DR5), **Then** a documented layout ADR under `src/features/workflows/LAYOUT.md` states: "When layout pressure increases, context panels collapse before the main pane; compare mode (Epic 4) is the only state where main pane may share primacy with a second artifact view." This ADR is referenced by future story ACs to prevent drift.
11. **Given** correlation with CLI `deliveryline status` output (story 1.15), **Then** the shell's "current run identity" region displays the same identifiers (runId, current state, current actor, last transition timestamp) that CLI `status --format=json` returns — proving UI is a faithful view of backend state, not an independent source of truth.

### Story 2.30: Backend Lint + Format (Spotless + Checkstyle + SpotBugs)

As a backend developer,
I want Spotless (Google Java Format), Checkstyle, and SpotBugs wired into Maven and CI so every backend PR enforces formatting + style + bug-pattern detection,
So that backend consistency is mechanical from the start rather than relying on reviewer discipline — and the stories that produce the most backend code (2.8–2.16, plus Epic 3's runner and integration work) land against a configured linter, not a future-to-be-added one.

**Execution-order note:** Although numbered 2.30 to preserve AC cross-reference stability in stories 2.1–2.7, this story **must merge before story 2.8** (the first backend-heavy story in Epic 2). Epic 2's Definition of Done includes verifying this ordering held.

**Acceptance Criteria:**

1. **Given** the root Maven POM and each submodule POM (`deliveryline-backend`, `deliveryline-runner-contracts`), **Then** the Spotless Maven Plugin is configured with Google Java Format (`AOSP` or standard variant documented in the POM), runs in the `verify` phase, and fails the build on unformatted source.
2. **Given** Checkstyle Maven Plugin configured with a committed ruleset at `config/checkstyle/checkstyle.xml` (derived from Google Checks or Sun Checks with project-specific deltas documented), **Then** it runs in `verify` phase and fails on violations — treating the configuration as source of truth committed to the repo.
3. **Given** SpotBugs Maven Plugin (or alternatively Error Prone via Google's plugin), **Then** it runs in a dedicated CI tier (under `format-static-checks` per story 1.21) and fails the build on any bug at severity `HIGH` — warnings at `MEDIUM` are reported but non-failing initially; a deferred story can escalate them.
4. **Given** the `format-static-checks` CI job from story 1.21, **Then** Spotless-check, Checkstyle, and SpotBugs each run here as separate named steps so failures are attributable without requiring developers to parse a combined log.
5. **Given** a repo-root `.editorconfig` file, **Then** it defines UTF-8 encoding, LF line endings (with documented exception for `.cmd`/`.ps1` which may need CRLF), indent width per file type (4-space Java, 2-space JSON/YAML/TSX), and `insert_final_newline = true` — consistent with Spotless-enforced formatting.
6. **Given** architecture naming conventions (PascalCase classes, camelCase methods, UPPER_SNAKE_CASE constants, event-type dot-separated lowerCamel per story 1.4), **Then** Checkstyle rules enforce the Java-side ones — class, method, field, constant, and parameter name patterns — and reject wildcard imports, unused imports, and bare TODO/FIXME without issue reference.
7. **Given** the relationship to ArchUnit (story 1.11), **Then** a comment in the Checkstyle config documents: "Checkstyle handles formatting/style/naming; ArchUnit handles architectural boundaries; SpotBugs handles bug patterns. No overlap." — each tool has a non-redundant scope.
8. **Given** forbidden calls in production code, **Then** Checkstyle (or SpotBugs custom rule) flags `System.out.println`, `System.err.println`, `e.printStackTrace()`, `Thread.sleep` outside test directories — production code must use the structured logger (story 1.19).
9. **Given** optional pre-commit integration, **Then** a `scripts/install-git-hooks.sh` + `.ps1` (or documented pre-commit framework config) lets developers opt into local Spotless + Checkstyle pre-commit runs — documented in `docs/setup-local.md` as recommended but not required.
10. **Given** the foundation-gate verification story (1.23), **Then** its scope is widened to include "Spotless, Checkstyle, and SpotBugs are green on the branch" — so backend lint cleanliness is part of the epic-close gate, not a separately manageable concern.

### Story 2.31: Frontend Lint + Prettier + Custom Rules

As a frontend developer,
I want ESLint + Prettier + TypeScript strict rules + jsx-a11y + custom project rules (no-workflow-domain-in-ui-primitives, no-inline-query-keys) wired into the frontend module and CI,
So that the ESLint rules referenced in story 2.2 AC7 and story 2.7 AC4 have a concrete implementing story - and formatting, accessibility-lint, and architectural-boundary enforcement are mechanical on every PR.

**Execution-order note:** Although numbered 2.31 to preserve AC cross-reference stability in stories 2.1–2.7, this story **must merge before story 2.2** (Tailwind + shadcn setup, which references the custom ESLint rule in its AC7) — or at latest before story 2.4 color tokens begin using Tailwind utilities. Epic 2's Definition of Done includes verifying this ordering held.

**Acceptance Criteria:**

1. **Given** `deliveryline-frontend`, **Then** ESLint is configured via `eslint.config.js` (flat config) with plugins: `@typescript-eslint/eslint-plugin`, `eslint-plugin-react`, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh`, `eslint-plugin-jsx-a11y`, `eslint-plugin-import`.
2. **Given** Prettier configured via `.prettierrc.json`, **Then** project conventions are codified (single quotes, trailing commas `all`, semicolons, 2-space indent, 100-char print width) — documented and not duplicated in ESLint to avoid rule conflicts; `eslint-config-prettier` disables stylistic ESLint rules that Prettier handles.
3. **Given** TypeScript strict rules extending story 2.1's tsconfig, **Then** `@typescript-eslint` rules are set: `no-explicit-any` = error, `no-floating-promises` = error, `strict-boolean-expressions` = warn, `no-unnecessary-condition` = warn, `consistent-type-imports` = error, `no-misused-promises` = error.
4. **Given** the custom ESLint rule **`no-workflow-domain-in-ui-primitives`** referenced in story 2.2 AC7, **Then** it is implemented under `tools/eslint-rules/no-workflow-domain-in-ui-primitives.js` (or via a local ESLint plugin) — the rule flags any import from `src/features/workflows/` or any workflow-domain type reference inside files under `src/components/ui/*`.
5. **Given** the custom ESLint rule **`no-inline-query-keys`** referenced in story 2.7 AC4, **Then** it is implemented similarly — the rule flags `useQuery`/`useMutation`/`useInfiniteQuery` calls where the `queryKey` argument is an inline array literal rather than a call to a `workflowKeys.*` factory function; valid usages import from `src/lib/queryKeys/*`.
6. **Given** jsx-a11y rules, **Then** at minimum these are enabled at `error`: `anchor-is-valid`, `click-events-have-key-events`, `no-autofocus`, `role-has-required-aria-props`, `aria-props`, `aria-proptypes`, `aria-unsupported-elements`, `interactive-supports-focus`, `no-noninteractive-element-interactions`, `label-has-associated-control` — supporting WCAG 2.1 AA compliance enforced in story 2.25.
7. **Given** the `frontend-build-tests` CI tier from story 1.21, **Then** `npm run lint` (ESLint with `--max-warnings=0`) and `npm run format:check` (Prettier --check) run here and fail the build on violations.
8. **Given** the shared `.editorconfig` from story 2.30, **Then** frontend file encoding, line endings, and indentation match — no frontend-specific overrides unless documented (JSON/YAML/TSX already handled by top-level `.editorconfig` entries).
9. **Given** optional developer ergonomics, **Then** Husky + lint-staged configuration is documented as optional (not required) in `deliveryline-frontend/README.md` — teams can enable local pre-commit lint/format runs for their own workflow without the config being mandatory.
10. **Given** the foundation-gate verification story (1.23), **Then** its scope is widened (alongside story 2.30's widening) to include "ESLint + Prettier + custom rules green on the branch" for the frontend module - so frontend lint cleanliness is part of the epic-close gate.
11. **Given** a stub or failing-case test fixture for each custom rule, **Then** `tools/eslint-rules/__tests__/` contains cases proving `no-workflow-domain-in-ui-primitives` catches violations (e.g., a `src/components/ui/Button.test-fixture.tsx` importing `WorkflowRun` type fails lint) and `no-inline-query-keys` catches ad-hoc array literals - preventing rule drift as the codebase grows.

### Story 2.32: Backend Coverage Reporting + Maven JaCoCo Threshold Gate

As a backend developer,
I want Maven-wired JaCoCo coverage reporting plus enforceable backend coverage thresholds in `verify`,
So that backend test coverage is measured mechanically, published in a machine-readable form for CI, and able to fail the build when regression-prone code lands without sufficient automated coverage.

**Execution-order note:** Although numbered 2.32 to preserve existing story numbering stability, this story should merge alongside or immediately after story 2.30 and before the bulk of backend-heavy Epic 2 work (stories 2.8-2.14), so coverage reporting exists before the specification/approval backend slices expand.

**Acceptance Criteria:**

1. **Given** `deliveryline-backend/pom.xml`, **Then** `org.jacoco:jacoco-maven-plugin` is configured with `prepare-agent`, `report`, and `check` executions so a standard Maven run of `mvn -pl deliveryline-backend verify` both generates a coverage report and enforces thresholds.
2. **Given** the Maven lifecycle, **Then** coverage verification is attached to the `verify` phase (not a custom ad-hoc script only), and the documented backend coverage command is `mvn -pl deliveryline-backend verify`.
3. **Given** the generated reports, **Then** JaCoCo writes HTML, XML, and CSV outputs under `deliveryline-backend/target/site/jacoco/` (or the documented default output path), so local developers can inspect the HTML report and CI can ingest XML without custom path guessing.
4. **Given** backend quality gates, **Then** JaCoCo `check` enforces documented minimum thresholds at the backend module level - at minimum line and branch coverage - with the threshold values committed in the POM and justified in `deliveryline-backend/README.md` (or equivalent module documentation).
5. **Given** threshold scope, **Then** the story documents exactly what is and is not counted toward the gate: generated sources, configuration-only bootstrapping classes, and clearly documented framework glue may be excluded only through committed plugin configuration with rationale; business/application code may not be blanket-excluded.
6. **Given** the existing test stack (`surefire`, ArchUnit, Spring MVC tests, Testcontainers-backed contract tests), **Then** JaCoCo coverage instrumentation works with the current backend test suite and does not require developers to run a separate test command from `verify`.
7. **Given** a threshold breach, **Then** Maven fails with a clear JaCoCo `check` error during `verify`; this failure is deterministic and does not require manual inspection of the HTML report to detect.
8. **Given** CI integration through story 1.21, **Then** the backend quality job includes a named step that runs `mvn -pl deliveryline-backend verify` (or the final agreed module-scoped equivalent) and archives/publishes the JaCoCo XML/HTML artifacts for inspection when the job fails.
9. **Given** the relationship to story 2.30, **Then** documentation makes the tool split explicit: Spotless/Checkstyle/SpotBugs enforce formatting, style, and bug patterns; JaCoCo measures test coverage; none of them is treated as a substitute for the others.
10. **Given** future backend growth in stories 2.8-2.14 and Epic 3, **Then** the coverage gate is scoped so new backend packages automatically participate unless explicitly excluded by committed configuration; developers do not need to touch the plugin just because a new application package was added.
11. **Given** developer ergonomics, **Then** `deliveryline-backend/README.md` (or equivalent) documents:
    - the standard coverage command
    - where the HTML report appears locally
    - what to do when the JaCoCo threshold fails
    - the current threshold values and why they were chosen for this phase
12. **Given** the foundation-gate verification story (1.23), **Then** its scope is widened to include "backend JaCoCo coverage report generated and threshold gate green on the branch" so backend coverage regressions are caught at the same epic-close gate as backend tests and lint.

### Story 2.8: Backend - Specification Artifact Model + Spec-Stage Context Bundle

As a backend developer,
I want a `SpecificationArtifact` domain shape (built atop the artifact operations skeleton from story 1.12) plus spec-stage context-bundle composition logic,
So that specs are first-class versioned artifacts with redacted, inspectable context bundles - supporting FR7 (capture/review specification), FR10 (current approved state visible), FR11 (prior states visible), FR55 (inspect context bundle).

**Acceptance Criteria:**

1. **Given** the existing `artifacts` table from story 1.3, **Then** a new `SpecificationArtifact` domain projection exposes a typed view over rows where `artifact_type = 'spec'` (registry value from story 1.4): id (`art_` prefix), `workflowRunId`, `version`, `parentArtifactId` (nullable for v1), `payloadRef`, `checksum`, `status`, `classification`, `createdAt`.
2. **Given** the spec payload, **Then** `LocalArtifactStore` (story 1.12 AC8) stores spec content as markdown at `{DELIVERYLINE_HOME}/artifacts/{workflowRunId}/{artifactId}/v{version}/spec.md` — same path scheme as other artifact variants for consistency.
3. **Given** `ContextBundleService.create(workflowRunId, stage='spec-investigation')` from story 1.13, **Then** the spec-stage context bundle includes: `ticketSummary` (from Linear adapter), `priorFeedbackReferences` (rejection feedback rows from prior `approvals` rejections — story 2.10), `executionConstraints` (any constraints from prior runs of this ticket), `classification` (`shareable-redacted` after redaction), and **excludes** approved-spec reference (no spec exists yet at investigation step).
4. **Given** `RedactionPolicyService` (story 1.10), **Then** the bundle is redacted before persistence — adversarial fixture tests prove no Linear API key, GitHub token, or absolute machine path appears in the persisted bundle.
5. **Given** the bundle is persisted as a runner-contracts v1 document (story 1.6), **Then** `RunnerContractValidator` is called before write — invalid bundles fail loudly rather than reaching a runner.
6. **Given** spec versioning, **When** a new spec is generated after rejection (story 2.10), **Then** `ArtifactService.newVersion(parentArtifactId, payloadRef)` is invoked — `parent_artifact_id` points to the rejected version, `version` increments, and an `artifact.versionCreated` event is appended.
7. **Given** FR55 inspection, **Then** `WorkflowInspectionService.getContextBundleForArtifact(artifactId)` returns the typed bundle used to produce that artifact — accessible via CLI `deliveryline status {runId} --include-context-bundle` (CLI flag added here) and via the REST detail endpoint (story 2.13 wires the read side; this story adds the service method).
8. **Given** FR10, **Then** `WorkflowInspectionService.getCurrentApprovedSpec(workflowRunId)` returns the latest spec artifact whose `approvals.decision = approved` — null if no approved spec exists yet.
9. **Given** FR11, **Then** `WorkflowInspectionService.getSpecHistory(workflowRunId)` returns all spec versions in chronological order with their decision history (approved/rejected/pending) joined from the `approvals` table.
10. **Given** the test suite, **Then** it covers: spec creation via `ArtifactOperationService.recordOperation`, version increment after rejection, context-bundle composition with prior feedback, redaction in bundle, RunnerContractValidator-rejection-on-bad-bundle, getCurrentApprovedSpec returning null vs latest approved, getSpecHistory ordering.

### Story 2.9: Backend — ApprovalService Core (Approve) with Version Binding

As a Product Manager,
I want `ApprovalService.approveSpec(...)` that binds approvals to a specific artifact version + context bundle version + actor identity + reviewer role,
So that approvals can never apply to a stale artifact (`APPROVAL_VERSION_MISMATCH` if the spec changed under me) and approval attribution is auditable per FR46.

**Acceptance Criteria:**

1. **Given** the `application.approval` package, **Then** `ApprovalService` exposes `approveSpec(command: ApproveSpecCommand)` returning a typed `ApprovalResult`.
2. **Given** `ApproveSpecCommand` from story 1.7 command-model pattern, **Then** it carries: `workflowRunId`, `artifactId`, `expectedArtifactVersion`, `expectedContextBundleVersion`, `actorIdentity`, `actorType` (= `human` for PM approvals), `reviewerRole` (e.g., `product_reviewer`), optional `reason`, and `idempotencyKey`.
3. **Given** the `approvals` table from story 1.3, **Then** an approval row carries `apr_` prefix ID, `workflow_run_id`, `artifact_id`, `artifact_version`, `context_bundle_version`, `actor_identity`, `reviewer_role`, `decision` (`approved`), `reason`, `decided_at`, `idempotency_key`.
4. **Given** version-binding enforcement, **When** the current artifact version differs from `expectedArtifactVersion` OR the current context bundle version differs from `expectedContextBundleVersion`, **Then** `APPROVAL_VERSION_MISMATCH` is raised with `details.expectedArtifactVersion`, `details.currentArtifactVersion`, `details.expectedContextBundleVersion`, `details.currentContextBundleVersion` — matching the Problem Details example from story 1.8.
5. **Given** the architecture rule that approvals require `available` artifacts (`ArtifactService.isApprovalEligible(artifactId)` from story 1.12 AC6), **When** the artifact is not available, **Then** `ARTIFACT_PAYLOAD_UNAVAILABLE` is raised — approval is rejected before any state change.
6. **Given** a successful approval, **When** committed, **Then** in one transaction: an `approvals` row is inserted, an `approval.approved` workflow event is appended, and `WorkflowTransitionService.transition(workflowRunId, targetState='Executing', ...)` is invoked — fulfilling FR12 (no implementation progression until spec accepted).
7. **Given** FR46 attribution, **Then** the approval record + event preserve `reviewer_role` so inspection (CLI `status`/`history`, UI in story 2.17) can display "approved by Alex (product_reviewer)" — distinct from technical approval roles that arrive in Epic 3.
8. **Given** idempotency (story 1.9), **Then** retries with the same `idempotencyKey` + same fingerprint replay the prior result; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
9. **Given** an attempt to approve from an invalid current state (e.g., run not in `WaitingForSpecApproval`), **When** processed, **Then** `ILLEGAL_TRANSITION` from `WorkflowTransitionService` (story 1.5) propagates — `ApprovalService` does NOT silently skip the transition.
10. **Given** contract tests, **Then** they cover: happy-path approval transitioning state and emitting event, version-mismatch rejection without state change, unavailable-artifact rejection, idempotent replay, idempotency-conflict, illegal-state-transition, attribution fields on the persisted row.
11. **Given** allowed-actions integration (story 2.14), **Then** when run state is `WaitingForSpecApproval` and current actor has reviewer role, `approve_spec` appears in the allowed-actions list — `ApprovalService` is the canonical executor for that action.

### Story 2.10: Backend — Spec Rejection with Structured Feedback + Escalation

As a Product Manager,
I want `ApprovalService.rejectSpec(...)` accepting structured feedback (tagged with the rework taxonomy from story 1.3 — `missing_scope` / `unclear_specification` / `misunderstood_implementation`), and a workflow-level escalation marker when rejection loops repeat beyond a configurable threshold,
So that rejection feedback flows back into the spec-rebuild loop with measurable rework categorization (FR9, AR34a) and unresolved loops surface for human escalation rather than spinning forever (FR13).

**Acceptance Criteria:**

1. **Given** `RejectSpecCommand`, **Then** it carries: `workflowRunId`, `artifactId`, `expectedArtifactVersion`, `expectedContextBundleVersion`, `actorIdentity`, `actorType`, `reviewerRole`, `reasonText` (free-form, required), `taggedFeedback` (one of `missing_scope`, `unclear_specification`, `misunderstood_implementation` from the `rejection_taxonomy` registry), `idempotencyKey`.
2. **Given** the `approvals` table, **Then** a rejection row uses `decision = rejected`, stores `reason` (free-form text from `reasonText`), and stamps `rejection_taxonomy` (the AR34a measurement-capture column from story 1.3 AC4) — enabling later cycle-time + rework-rate analytics in story 5.X (AR34b surfacing).
3. **Given** version-binding (same rule as story 2.9), **When** versions don't match, **Then** `APPROVAL_VERSION_MISMATCH` is raised with the same `details` shape — no rejection row is written.
4. **Given** a successful rejection, **When** committed, **Then** in one transaction: an `approvals` row with `decision=rejected` is inserted, an `approval.rejected` workflow event is appended, the `workflow_runs.spec_rejection_loop_count` counter is incremented (this counter column is added by Flyway V2 in story 2.11 — this story's tests run after that migration), and `WorkflowTransitionService.transition(workflowRunId, targetState='Investigating', ...)` is invoked to re-enter spec generation.
5. **Given** a configurable escalation threshold (default `3`, configurable via `application.yml` property `deliveryline.workflow.spec-rejection-escalation-threshold`), **When** `spec_rejection_loop_count` reaches the threshold, **Then** an `escalation.required` workflow event is appended with `details.reason='spec_rejection_loop_threshold_exceeded'` and `workflow_runs.escalation_marker_set=true` (column added by V2 alongside the counter) — fulfilling FR13.
6. **Given** the escalation marker, **Then** `WorkflowInspectionService.getRunSummary(workflowRunId)` includes `escalationMarker: true` so CLI `status` (story 1.15) and UI (story 2.17 Queue Item, story 2.18 Context Strip) can surface it visibly.
7. **Given** escalation does NOT block the workflow (per FR13 — it exposes the loop, doesn't terminate it), **Then** the workflow remains in `Investigating` state and continues normally; the marker is purely informational + visible until manually cleared by an operator (operator clear-escalation action lands in Epic 4).
8. **Given** AR34a measurement capture, **Then** the `rejection_taxonomy` column is populated on every rejection row — a contract test asserts that no rejection can be recorded without a non-null `taggedFeedback` (`MISSING_REJECTION_TAXONOMY` error if absent).
9. **Given** the test suite, **Then** it covers: happy-path rejection transitioning state to `Investigating` and emitting event, taxonomy-missing rejection, version-mismatch rejection, threshold-not-exceeded (counter increments, no escalation event), threshold-exceeded (counter increments, `escalation.required` event emitted, marker set on run), escalation marker visible via inspection.
10. **Given** the new V2 columns (loop count, escalation marker, plus clarification table from story 2.11), **Then** Flyway V2 migration `V2__add_spec_loop_and_clarifications.sql` lands in story 2.11's scope (clarifications add tables + adds these two columns to `workflow_runs` in the same migration to avoid V3 churn) — this story's implementation depends on V2 having merged.

### Story 2.11: Backend — Clarification Domain Model + Submission

As a Product Manager,
I want a `Clarification` domain entity persisted in a new `clarifications` table (Flyway V2) plus `ClarificationService.submitAnswer(...)` to record reviewer answers tied to specific artifact versions,
So that PMs can answer open questions in context without losing the link to the spec version they were answering against — and the data model can express the visible incorporation lifecycle (story 2.12).

**Acceptance Criteria:**

1. **Given** Flyway migration `V2__add_spec_loop_and_clarifications.sql`, **Then** it creates the `clarifications` table with columns: `id` (`clr_` prefix text), `workflow_run_id` (FK), `artifact_id` (FK to `artifacts` — the spec version the clarification is about), `artifact_version` (snapshot, denormalized for query stability), `question_id` (text — identifier for the open question, comes from spec content), `question_text` (text — copied for stability if the spec is later superseded), `status` (text, CHECK constrained to `open|answered|accepted|incorporated|superseded|rejected_invalid`), `answer_text` (text, nullable until answered), `answered_by_actor` (text, nullable), `answered_at` (timestamptz, nullable), `incorporation_event_id` (FK to `workflow_events`, nullable until incorporated), `idempotency_key` (text, unique), `created_at` (timestamptz NOT NULL DEFAULT now()), `archived_at` (timestamptz NULL — for retention parity with story 1.3).
2. **Given** the same V2 migration, **Then** it ALSO adds two columns to `workflow_runs`: `spec_rejection_loop_count integer NOT NULL DEFAULT 0` and `escalation_marker_set boolean NOT NULL DEFAULT false` (consumed by story 2.10 — bundling these saves a V3 migration).
3. **Given** the `PublicIdPrefixes` registry (story 1.4), **Then** the `clr_` prefix is added with appropriate ID-generator wiring; central-registry drift test confirms the addition.
4. **Given** `ClarificationService.submitAnswer(command: SubmitClarificationCommand)`, **Then** `SubmitClarificationCommand` carries: `workflowRunId`, `artifactId`, `expectedArtifactVersion`, `questionId`, `answerText`, `actorIdentity`, `actorType`, `idempotencyKey`.
5. **Given** version-binding (consistent with story 2.9), **When** `expectedArtifactVersion` doesn't match the current artifact version, **Then** `CLARIFICATION_ARTIFACT_VERSION_MISMATCH` is raised with `details` showing expected vs current — preventing answers from accidentally applying to a superseded spec.
6. **Given** a clarification row in state `open` (created during spec generation — out of scope here; created when the spec runner emits a question marker, wired in Epic 3) or in `answered` state from a prior answer, **When** `submitAnswer` is called, **Then** the row transitions to `answered`, `answer_text` + `answered_by_actor` + `answered_at` are populated, and a `clarification.answered` workflow event is appended in the same transaction.
7. **Given** an attempt to answer a clarification that doesn't exist, **Then** `CLARIFICATION_NOT_FOUND` is raised; an attempt to answer a clarification in `incorporated` or `rejected_invalid` state raises `CLARIFICATION_TERMINAL_STATE`.
8. **Given** an attempt to re-answer an already-`answered` clarification (revising before incorporation), **When** processed, **Then** the row's `answer_text` is updated and a new `clarification.answered` event is appended with `details.priorAnswerText` for audit — the prior answer is preserved in the event log even though the row holds the latest text.
9. **Given** `WorkflowInspectionService.getClarifications(workflowRunId)` and `getClarificationsForArtifact(artifactId)`, **Then** they return clarifications grouped by status, ordered by `created_at` — used by the UI Clarification Region (story 2.18).
10. **Given** idempotency, **Then** retries with the same key + fingerprint replay the prior result; the unique constraint on `idempotency_key` prevents accidental double-submission.
11. **Given** the test suite, **Then** it covers: happy-path submission + event, version-mismatch rejection, not-found, terminal-state rejection, re-answer with prior preserved in event log, idempotent replay, V2 migration replay safety, V2 column additions to `workflow_runs`, prefix-registry inclusion of `clr_`.

### Story 2.12: Backend — Visible Incorporation Lifecycle States + Event Wiring

As a Product Manager,
I want explicit lifecycle wiring (`submitted → answered → accepted → incorporated`, with `superseded` and `rejected_invalid` as terminal alternates) for clarifications, with each transition emitting a `clarification.*` workflow event,
So that the make-or-break refinement holds: PMs can see whether their input was accepted, applied to the active spec context, or set aside — and a contract test enforces that no clarification answer can vanish without a visible workflow effect.

**Acceptance Criteria:**

1. **Given** `ClarificationLifecycleService` in `application.clarification`, **Then** it owns lifecycle transitions: `markAccepted(clarificationId, accepterActor)`, `markIncorporated(clarificationId, newSpecArtifactId, incorporationEventId)`, `markSuperseded(clarificationId, supersededBySpecArtifactId, reason)`, `markRejectedInvalid(clarificationId, reason)`.
2. **Given** the `clarifications.status` column from story 2.11, **Then** lifecycle transitions enforce a state machine: `open → answered` (story 2.11), `answered → accepted | rejected_invalid`, `accepted → incorporated | superseded`, `incorporated` and `rejected_invalid` are terminal — `ILLEGAL_CLARIFICATION_TRANSITION` for invalid moves.
3. **Given** each transition, **Then** the corresponding `workflow_events` row is appended in the same transaction: `clarification.accepted`, `clarification.incorporated`, `clarification.superseded`, `clarification.rejectedInvalid` — types added to the central event registry per story 1.4 drift test.
4. **Given** automatic incorporation detection, **When** a new spec artifact version is created after a clarification was `accepted`, **Then** the spec-generation orchestrator (which lives in the runner broker / E3 — but this story stubs the contract): a) calls `markIncorporated(clarificationId, newSpecArtifactId, eventId)` if the new spec content acknowledges the clarification's `questionId`, b) calls `markSuperseded(...)` with reason `clarification_not_addressed` if it does not — neither call may be skipped silently.
5. **Given** the make-or-break contract test, **Then** it asserts: for every `clarification.answered` event in a workflow run's history, there must subsequently appear either a `clarification.accepted` + (`clarification.incorporated` | `clarification.superseded` | `clarification.rejectedInvalid`) event chain, OR a visible `clarification.noEffectReason` event explaining why no workflow change occurred — silent disappearance is a contract violation.
6. **Given** UX-DR11 visible-incorporation lifecycle, **Then** `WorkflowInspectionService.getClarificationStatus(clarificationId)` returns a typed `ClarificationStatusView` carrying current state, `acceptedAt` (nullable), `incorporatedAt` (nullable), `incorporatedIntoArtifactId` (nullable), `supersededByArtifactId` (nullable), `noEffectReason` (nullable) — ready for UI consumption in story 2.18.
7. **Given** the inspection methods, **Then** the UI (story 2.18) can distinguish `answered` (received, pending acceptance) from `accepted` (received, queued for spec rebuild) from `incorporated` (visibly applied to active workflow context) — addressing the PRD's "answered ≠ incorporated" make-or-break distinction.
8. **Given** fixture event stream extension (referencing story 1.23), **Then** at least one fixture run in `backend/src/test/resources/fixture-event-streams/` is updated or added to include the full clarification lifecycle (submitted → accepted → incorporated) so the UI in story 2.18 has realistic event sequences to develop against.
9. **Given** terminal-state events trigger downstream actions (e.g., `incorporated` may unblock approval-readiness), **Then** the inspection service's `getRunSummary(...)` reports `pendingClarifications: int` (count of clarifications NOT in `incorporated` or `rejected_invalid`) — used by Approval/Decision Bar (story 2.19) to decide whether `approve_spec` is in the allowed-actions list.
10. **Given** the test suite, **Then** it covers: each lifecycle transition + corresponding event, illegal-transition rejection, automatic incorporation detection on new spec version, automatic superseded marking when spec doesn't address the clarification, the make-or-break contract test running against a synthetic event stream and failing if a clarification answer has no follow-up event.

### Story 2.13: Backend — REST Mutation Endpoints + OpenAPI

As a frontend developer,
I want REST mutation endpoints `POST /api/v1/workflows/{workflowRunId}/approve-spec`, `POST /api/v1/workflows/{workflowRunId}/reject-spec`, and `POST /api/v1/workflows/{workflowRunId}/clarifications/{clarificationId}/answer` with Idempotency-Key headers, Problem Details errors, and OpenAPI documentation,
So that the UI's mutation hooks (story 2.6 AC6) have a stable contract and CLI/REST equivalence (story 1.7 AC5) is maintained for the new commands.

**Acceptance Criteria:**

1. **Given** `WorkflowController` in `adapters.rest` (extended from story 6.9), **Then** three new mutation endpoints exist: `POST /api/v1/workflows/{workflowRunId}/approve-spec`, `POST /api/v1/workflows/{workflowRunId}/reject-spec`, `POST /api/v1/workflows/{workflowRunId}/clarifications/{clarificationId}/answer` — paths use kebab-case action names per architecture rule.
2. **Given** request bodies, **Then** they are typed DTOs in camelCase JSON: `ApproveSpecRequest { artifactId, expectedArtifactVersion, expectedContextBundleVersion, reviewerRole, reason? }`, `RejectSpecRequest { artifactId, expectedArtifactVersion, expectedContextBundleVersion, reviewerRole, reasonText, taggedFeedback }`, `AnswerClarificationRequest { artifactId, expectedArtifactVersion, answerText }`.
3. **Given** every state-changing endpoint, **Then** the `Idempotency-Key` header is required; missing header returns 400 with `MISSING_IDEMPOTENCY_KEY`; invalid key format returns 400 with `INVALID_IDEMPOTENCY_KEY`.
4. **Given** the `X-Actor-Identity` header (E2 deferred-auth model — local trusted user per architecture security posture), **Then** it identifies the actor for the application command; missing header falls back to a configured local-user identity (`deliveryline.security.local-actor-identity` property) with `actorType = 'human'`; the reviewer role comes from the request body (not from a roles claim — MVP does not enforce RBAC, audit-only per architecture).
5. **Given** Problem Details mapping (story 1.8), **Then** typed error responses cover: `APPROVAL_VERSION_MISMATCH` (409, retryable), `IDEMPOTENCY_KEY_CONFLICT` (409, not retryable), `ARTIFACT_PAYLOAD_UNAVAILABLE` (409), `ILLEGAL_TRANSITION` (409), `CLARIFICATION_ARTIFACT_VERSION_MISMATCH` (409), `CLARIFICATION_NOT_FOUND` (404), `CLARIFICATION_TERMINAL_STATE` (409), `RUN_NOT_FOUND` (404), `INVALID_COMMAND_PAYLOAD` (400) — assertions on contract tests check `code` + `status` + `details`, never human text.
6. **Given** OpenAPI generation via `springdoc-openapi`, **Then** the three endpoints appear with full request/response schemas and documented error responses; the committed OpenAPI snapshot at `backend/src/main/resources/openapi/openapi.json` is regenerated and CI's drift check (story 1.21 AC6) passes.
7. **Given** CLI/REST equivalence (story 1.7 AC5), **Then** placeholder Spring Shell commands (`deliveryline approve-spec ...`, `deliveryline reject-spec ...`, `deliveryline answer-clarification ...`) are added under `adapters.cli` in this story, calling the same `ApprovalService` / `ClarificationService` methods — and a contract test asserts identical `DomainResult` outcomes between CLI and REST for matched payloads.
8. **Given** the architecture forbids workflow orchestration in adapters, **Then** ArchUnit (story 1.11) confirms `WorkflowController`'s new methods only do request parsing, command construction, service invocation, and response mapping — no business logic.
9. **Given** the responses, **Then** success returns `200 OK` with a typed result DTO carrying the new state, new artifact version (if applicable), and stamped `correlationId`; the `X-Correlation-Id` response header echoes the request correlation ID.
10. **Given** the contract test suite, **Then** for each endpoint it covers: happy path 200, every documented error code 4xx/409, idempotent replay, idempotency-conflict, version mismatch with stale `expected*Version`, request schema validation failures with field-level `details` array.

### Story 2.14: Backend — Allowed-Actions Inspection Endpoint

As a frontend developer building the generalized Approval/Decision Bar (story 2.19) and other action-aware composites,
I want a `GET /api/v1/workflows/{workflowRunId}/allowed-actions` endpoint returning a typed list of backend-derived allowed actions for the current state + actor role + run context, with a version stamp the UI can use to detect staleness,
So that frontend composites read backend truth — never inferring permissions, applicability, or workflow rules locally (party-mode finding #3 + UX-DR12 hard rule).

**Acceptance Criteria:**

1. **Given** the `application.workflow` package, **Then** `WorkflowInspectionService.getAllowedActions(workflowRunId, actorRole)` returns a typed `AllowedActionsView` carrying `actions: List<AllowedAction>` and `versionStamp: AllowedActionsVersionStamp { workflowState, currentSpecArtifactVersion, currentContextBundleVersion, lastEventId }`.
2. **Given** the `AllowedAction` central registry (story 1.4), **Then** the following values are added (with drift tests updated): `approve_spec`, `reject_spec`, `answer_clarification`, `view_only`, `await_outcome`, `retry`, `view_diagnostics`, `clear_escalation_marker` (operator action — full implementation lands in E4; here it just registers).
3. **Given** state-+-role-derived rules (sole source of truth — backend, not frontend), **Then** `getAllowedActions` returns sets such as: state `WaitingForSpecApproval` + role `product_reviewer` → `[approve_spec, reject_spec, answer_clarification]`; state `Investigating` + any role → `[view_only]` (with `answer_clarification` if there are open clarifications on the latest in-flight spec); state `Executing` + any role → `[view_only, await_outcome]`; state `Failed` + role `product_reviewer` → `[view_only, view_diagnostics]`; state `Failed` + role `workflow_owner` → `[retry, view_diagnostics]`.
4. **Given** clarification gating (party-mode finding from John on E2 PM-loop completeness), **Then** if `pendingClarifications > 0` (per story 2.12 AC9), `approve_spec` is REMOVED from the action list even when state is `WaitingForSpecApproval` — backend enforces "answered ≠ incorporated, can't approve until incorporated" rather than the frontend.
5. **Given** the version stamp, **Then** the UI (Approval/Decision Bar in story 2.19) sends `expectedAllowedActionsVersionStamp` on its mutation requests; if the stamp doesn't match current backend state when the mutation lands, the appropriate version-mismatch error returns (e.g., `APPROVAL_VERSION_MISMATCH`) — no silent overwrites.
6. **Given** REST endpoint `GET /api/v1/workflows/{workflowRunId}/allowed-actions`, **Then** it returns `200` with the typed `AllowedActionsView`, supports a `?actorRole=` query param (defaults to `product_reviewer` when absent — documented as MVP convenience), and is idempotent (no Idempotency-Key required).
7. **Given** a non-existent run, **Then** `RUN_NOT_FOUND` returns 404; an unrecognized actorRole returns 400 with `UNKNOWN_ACTOR_ROLE`.
8. **Given** state coverage, **Then** for every `WorkflowState` registered (story 1.4), there is at least one (state × role) → action-set test case — preventing future state additions from leaving a stale or empty action set undetected.
9. **Given** ArchUnit boundary rules, **Then** the action-derivation logic lives in `WorkflowInspectionService` (application layer); no controller, no frontend, no adapter contains action-derivation rules — verified by an ArchUnit test that fails if a `*Controller` or `*Adapter` class references the `AllowedAction` enum directly without going through the service.
10. **Given** OpenAPI doc + CI drift check (story 1.21 AC6), **Then** the endpoint and `AllowedActionsView` schema appear in the regenerated OpenAPI snapshot; UI in story 2.6 generates a typed client method for it.
11. **Given** future-stage action additions (Epic 3 adds developer-review actions, Epic 4 adds operator recovery actions), **Then** the design allows extending without breaking — new `AllowedAction` registry values are additive, the `versionStamp` model accommodates future fields, and a documented compatibility contract states "the UI must gracefully handle unknown action values by hiding them" (UX-DR12 + UX-DR6 unsupported-state handling already covers this).

### Story 2.15: Run / Review Queue Item Component

As a Product Manager (and later, developer + workflow owner — variants in Epics 3 + 4),
I want a `RunReviewQueueItem` component that represents one actionable run in a review queue with enough context to decide whether to open it now,
So that the queue surface is the entry point into a run-centered workflow per UX-DR8 — and the row is scannable, keyboard-accessible, and prioritizes one primary attention signal without dense overload.

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/RunReviewQueueItem.tsx`, **Then** the component accepts a typed `RunQueueRow` prop (sourced from `useWorkflowsList()` hook backed by story 6.9's `GET /api/v1/workflows`) carrying: `runId`, `linearTicketReference`, `summary`, `currentState`, `primaryAttentionIndicator`, `currentArtifactType`, `lastTransitionAt`, `assigneeHint?`, `blockerCount`, `openQuestionCount`, `staleIndicator`, `escalationMarker`.
2. **Given** anatomy per UX-DR8, **Then** the rendered item displays: ticket/run identifier (e.g., `LIN-123 · run_abc`), concise summary (truncated with ellipsis past line clamp), current stage badge (using `state-*` token colors from story 2.3 with non-color signifier — icon + state label per AC of story 2.3), primary attention indicator (one slot only — never multiple competing indicators), artifact type badge, age/updated relative-time text, optional assignee hint, trust signals (blocker count + open-question count + stale icon when applicable + escalation marker icon when set per story 2.10).
3. **Given** states per UX-DR8, **Then** the component supports: `default`, `hover` (background tint shift), `selected` (accent border + background), `unread` (subtle dot indicator), `blocked` (state-blocker color treatment), `stale` (state-stale color treatment), `disabled` (reduced opacity, no hover affordance).
4. **Given** variants per UX-DR8, **Then** props support: `variant: 'reviewer' | 'operator'` (operator variant arrives in E4; reviewer is the E2 default — both must compile, operator can render a placeholder), and `density: 'compact' | 'standard'` (using the 4px scale from story 2.4 for compact, 8px for standard).
5. **Given** primary attention indicator rule, **Then** the component encodes the rule "show only one primary attention signal" — if a row has both `blockerCount > 0` and `openQuestionCount > 0`, the indicator follows a documented priority order (blocker > escalation > open question > stale) and the others demote to secondary trust signals; a unit test asserts this.
6. **Given** keyboard accessibility per UX-DR8, **Then** the item is fully keyboard focusable, supports `Enter` and `Space` to open the run, exposes `aria-label` including ticket identity + state + attention state (e.g., `"LIN-123, Waiting for spec approval, 1 open question, last updated 3 minutes ago"`), and integrates as a semantic list item or table row depending on container.
7. **Given** content guidelines per UX-DR8, **Then** the summary is truncated to a documented character limit (line-clamp via Tailwind utility), hover does NOT reveal a tooltip-of-everything (avoids metadata overload — a secondary metadata reveal is allowed only for the `lastTransitionAt` precise timestamp on hover), and the row never expands inline.
8. **Given** responsibility boundary per UX-DR8, **Then** the component owns: run identity rendering, stage badge, primary indicator, last activity time, trust signals — and emits a `onOpen(runId)` navigation intent via TanStack Router (story 2.5); it does NOT own queue-level filtering, sorting, pagination (those live on the parent queue page).
9. **Given** ArchUnit-equivalent ESLint rule (story 2.31 AC4), **Then** this component must not import workflow-domain types into `src/components/ui/*` files — the composite lives under `src/features/workflows/components/` and consumes shadcn primitives (Badge, Card, etc.) from `src/components/ui/`.
10. **Given** component test coverage, **Then** Vitest + Testing Library tests cover: each state renders correctly, primary-attention-indicator priority order, keyboard navigation (Tab, Enter, Space), `aria-label` content correctness, density variant rendering, and escalation-marker rendering when `escalationMarker = true`.
11. **Given** WCAG 2.1 AA (story 2.25), **Then** focus-visible ring from story 2.4 AC6 applies; contrast on every state is verified by the contrast test from story 2.3 AC4; non-color signifiers accompany all state colors.
12. **Given** the foundation fixture event stream (story 1.23) including the spec-rejection-and-resubmit and execution-failure-with-retry scenarios, **Then** Storybook-equivalent test fixtures render the queue item against each scenario's terminal state — proving the component handles diverse trust-signal combinations from real fixture data.

**Dependency:** Cannot merge before story 2.24 ships (Artifact Content Sanitization + Redaction-Gap Closure). Reason: this story renders untrusted backend artifact content in the UI; 2.24 closes the F19/F20 redaction gaps that make safe rendering possible. Enforced by `dependency-edges` CI check (per `sprint-change-proposal-2026-05-19.md`).

### Story 2.16: Run Context Strip Component

As a Product Manager opening a governed run,
I want a `RunContextStrip` persistent lightweight component above or adjacent to the primary review surface,
So that I have just enough orientation (run identity, current state, current actor, latest revision pointer, last meaningful transition) to confirm I'm looking at the right artifact at the right workflow point — without the strip competing with the artifact body for attention (UX-DR9).

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/RunContextStrip.tsx`, **Then** the component accepts a typed `RunContextView` prop sourced from `useWorkflowDetail(workflowRunId)` (story 2.6) — carrying: `runId`, `currentState`, `currentActor`, `currentActorType`, `latestArtifactId`, `latestArtifactType`, `latestArtifactVersion`, `lastTransitionAt`, `lastTransitionEventType`, `triggerReference?` (Linear ticket ref), `branchOrCommitReference?` (populated in E3 when GitHub linkage exists), `escalationMarker`, `staleIndicator`.
2. **Given** anatomy per UX-DR9, **Then** the strip displays: run identifier (`run_abc`), current state badge (consistent with Queue Item's badge styling for visual consistency), current actor + actor-type tag (e.g., `Alex (human)`, `Codex Runner (agent)`), latest revision pointer (`spec v3` / `implementation-plan v1` / `pr-output v2` — depends on artifact type), last meaningful transition timestamp (relative time + precise tooltip), optional trigger or branch/commit reference (when applicable).
3. **Given** states per UX-DR9, **Then** the component renders: `default`, `stale` (state-stale token treatment + "stale" badge), `partial context` (when some fields are nullable — e.g., `branchOrCommitReference` is null in E2; render with empty-state placeholder for those slots), `loading` (skeleton placeholders matching the layout), `error` (loading failed — error treatment with retry).
4. **Given** the strip is "lightweight" per UX-DR9, **Then** vertical real estate is constrained (max-height enforced) and horizontal layout uses inline layout (`Inline` primitive from story 2.4); the strip does NOT expand into a multi-row metadata panel — a layout test asserts the rendered height is below a documented threshold across all states.
5. **Given** the strip "must not expand into a full lineage or provenance panel in MVP" (UX-DR9 responsibility boundary), **Then** the component exposes NO drilldown into full lineage or provenance — those views are deferred (UX spec lists "full run state header / lineage summary" as a deferred trust surface for Phase 3); the component may emit an `onNavigateToFullLineage()` event prop for future use, but no UI control wires it in E2.
6. **Given** accessibility per UX-DR9, **Then** the strip is grouped as a labeled context region (`<div role="region" aria-label="Run context">`), keyboard-readable in a sensible order, and status text is not color-dependent (matches story 2.3 AC5 rule).
7. **Given** correlation with CLI `deliveryline status` output (story 1.15) and the AppShell's current-run-identity region (story 2.7 AC11), **Then** the same identifiers display in both surfaces — the strip is the in-shell render of the same backend truth that CLI shows.
8. **Given** placement, **Then** the component is rendered by the `WorkflowDetailRoute` (story 2.5) in a dedicated slot above the main review pane (Artifact Review Panel from story 2.17) — sitting between the AppShell's left nav rail and the artifact body.
9. **Given** stale detection, **When** `staleIndicator = true` is reported by the backend (e.g., agent has not heartbeat'd within the threshold), **Then** the strip renders the stale state with a documented stale-reason tooltip (e.g., "Last activity 12 minutes ago — runner may be unresponsive"); the actual stale-detection logic lives in the backend (story 1.13 AC7 heartbeat tracking).
10. **Given** component test coverage, **Then** tests cover: each state renders correctly, height threshold respected across states, escalation-marker rendering, partial-context rendering with placeholders for null fields, accessibility region labeling, no expand-into-lineage-panel control rendered, content matches CLI `status --format=json` for the same run (snapshot test using fixture event stream).
11. **Given** the foundation fixture event stream (story 1.23), **Then** the strip renders correctly against each fixture run's terminal state including the spec-rejection-and-resubmit scenario where `latestArtifactVersion` advanced past the prior version.

### Story 2.17: Artifact Review Panel — Generalized Composite (Spec Variant)

As a Product Manager reviewing a specification (and later, developer reviewing implementation-plan + PR/output artifacts in Epic 3),
I want an `ArtifactReviewPanel` composite designed with **artifact-type polymorphism from day one**, currently rendering the spec variant — but with the variant-selection contract, allowed-actions integration, and section-anchor infrastructure already generalized,
So that Epic 3 adds `implementationPlan` + `prOutput` variants without reshaping infrastructure (party-mode finding #3) and the panel preserves artifact primacy as the visual anchor of the review desk (UX-DR10).

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/ArtifactReviewPanel.tsx`, **Then** the component is generalized — it accepts a `ArtifactView` discriminated union prop (sourced from `useArtifact(artifactId)` in story 2.6) where the discriminator is `artifactType` (values from runner-contracts schema v1: `spec`, `implementationPlan`, `prOutput` per story 1.6 AC4) and dispatches to a per-variant renderer.
2. **Given** the spec variant (E2 scope), **Then** `SpecArtifactRenderer` is implemented and renders: artifact title (e.g., "Specification — LIN-123 v3"), artifact type badge, current revision indicator (`v3` with link/anchor to revision history), markdown content (rendered via a sanitization-aware markdown renderer per story 2.24 — runner output is untrusted), inline metadata region (created-at, classification badge, checksum hash short-form), optional change summary slot (lighter weight than full Compare Mode per epic-list addition — change summary is rendered if `changeSummary` is non-null, otherwise hidden), section anchors derived from markdown headings, anchor entry-point into the Clarification Region (story 2.18), anchor entry-point into the Approval/Decision Bar (story 2.19), entry point into Compare Mode (Epic 4 — slot reserved with disabled control + "Available in next release" tooltip).
3. **Given** the `implementationPlan` and `prOutput` variants (Epic 3 scope), **Then** their renderers are scaffolded as stub components (`ImplementationPlanArtifactRenderer.tsx`, `PrOutputArtifactRenderer.tsx`) that render a placeholder "Renderer coming in Epic 3" — the discriminated-union dispatch is fully wired in E2 so E3 only needs to fill the renderers in.
4. **Given** states per UX-DR10, **Then** the panel renders: `default`, `loading` (skeleton matching layout), `empty / not yet generated` (e.g., spec-stage run before first spec drafted), `stale` (current artifact superseded by a newer version — render with stale treatment + clear "View latest" action), `conflicting / superseded` (similar but stronger — backend explicitly marked the artifact as conflicting), `incomplete artifact` (partial content — `truncated` flag from backend), `error / failed retrieval`.
5. **Given** content guidelines per UX-DR10, **Then** artifact body uses the `prose` typography utility from story 2.4 AC4 (readable line length, comfortable line-height); metadata is visually secondary; revision and staleness are clearly surfaced near the top of the panel (not buried).
6. **Given** artifact primacy per UX-DR5 (story 2.7) + UX-DR10 hard rule, **Then** the panel occupies the central main pane of the AppShell and never auto-collapses; section navigation does not displace the main reading flow (anchors scroll within the panel, not by replacing it).
7. **Given** runner output as untrusted (story 2.24), **Then** the markdown renderer sanitizes scriptable payloads (`<script>`, event handler attributes, `javascript:` URLs in links) and renders code blocks as plain text — never executing embedded HTML; the rendering library and config are documented in the story 2.24 ACs.
8. **Given** keyboard accessibility per UX-DR10, **Then** semantic heading hierarchy is preserved (markdown `#`/`##`/`###` map to `<h1>`/`<h2>`/`<h3>`), section anchors are keyboard-navigable (each anchor focusable + activatable with Enter), labeled regions for metadata + content, focus order respects reading order.
9. **Given** allowed-actions integration (story 2.14), **Then** the panel reads `useAllowedActions(workflowRunId)` and uses the result to enable/disable variant-specific controls (e.g., compare-entry control is hidden when `view_only` is the only allowed action and there is no comparable revision yet); the panel does NOT compute action eligibility locally.
10. **Given** responsibility boundary per UX-DR10, **Then** the panel owns: artifact rendering, inline context, comparison entry points (currently disabled), section anchors, anchors into clarification + decision regions; it does NOT absorb the decision workflow itself (Approval/Decision Bar in story 2.19 owns that), supporting history (Run Context Strip owns minimal context per story 2.16; deeper history is deferred), or full lineage (deferred Phase 3).
11. **Given** component test coverage, **Then** tests cover: spec variant renders all anatomy slots correctly, each state renders with appropriate visual treatment, discriminated-union dispatch routes to the correct renderer (assertions for spec → SpecArtifactRenderer, implementationPlan → stub, prOutput → stub), markdown sanitization rejects scriptable payloads (XSS attempt fixtures), section anchors are keyboard-navigable, allowed-actions integration disables/enables compare-entry control correctly, change-summary slot renders when `changeSummary` is non-null and hides when null, primacy: a layout test asserts the panel never auto-collapses below a documented minimum width.
12. **Given** the foundation fixture event stream (story 1.23) including all three artifact variants per AC7 of story 1.23, **Then** the panel renders the spec variant against the spec fixture and the stub renderers gracefully render against the implementationPlan + prOutput fixtures — proving the discriminator dispatch works against real fixture data.

**Dependency:** Cannot merge before story 2.24 ships (Artifact Content Sanitization + Redaction-Gap Closure). Reason: this story renders untrusted backend artifact content in the UI; 2.24 closes the F19/F20 redaction gaps that make safe rendering possible. Enforced by `dependency-edges` CI check (per `sprint-change-proposal-2026-05-19.md`).

### Story 2.18: Clarification Region with Visible Incorporation Lifecycle Wiring

As a Product Manager,
I want a `ClarificationRegion` component that surfaces unresolved questions, accepts my answers in context, and visibly distinguishes the lifecycle states (`open` → `answered` → `accepted` → `incorporated` / `superseded` / `rejected_invalid`),
So that the make-or-break refinement holds end-to-end in the UI: I never see "answer submitted" without seeing whether it was incorporated, superseded, or set aside (UX-DR11 + visible-incorporation refinement).

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/ClarificationRegion.tsx`, **Then** the component accepts a typed `ClarificationsView` prop sourced from `useClarifications(workflowRunId)` (TanStack Query hook backed by story 2.12's inspection methods) — carrying a list of clarifications grouped by status.
2. **Given** anatomy per UX-DR11, **Then** the rendered region displays: question list (grouped/sorted by status — `open` first, then `answered`/`accepted` pending, then terminal states collapsed by default), per-question status indicator with non-color signifier (icon + label, per story 2.3 AC5), selected question detail panel, response input area (textarea or structured-choice selector when applicable), optional structured-choice options, submit/resolve action button, visible relationship to current artifact state (`spec v3` callout), and a per-question lifecycle indicator showing the chain `submitted → accepted → incorporated` with current position highlighted.
3. **Given** states per UX-DR11, **Then** each question renders in one of: `no open questions` (region collapses or shows celebratory empty state), `unanswered` (open), `in progress` (answer drafted but not submitted), `answered / pending incorporation` (answer submitted, awaiting acceptance), `accepted` (queued for incorporation in next spec version), `incorporated` (visibly applied to active workflow context — happy outcome), `superseded` (set aside, with explicit reason), `rejected_invalid` (rejected with reason), `blocked / invalid` (validation error), `error` (network/backend error).
4. **Given** variants per UX-DR11, **Then** the component supports: `inline review region` (default — embedded in or anchored from the Artifact Review Panel), `sidebar subregion` (right-context-panel slot via AppShell story 2.7 AC4), `compact summary mode` (just counts and CTA — for queue-level reads), `full response mode` (when a question is selected for answering).
5. **Given** the visible incorporation lifecycle per UX-DR11 + make-or-break refinement, **Then** when a user submits an answer via the response input: (a) the UI shows immediate "answer submitted" inline feedback (NOT a toast — UX-DR15 rule), (b) the question's status visibly transitions to `answered` upon backend confirmation, (c) the lifecycle indicator updates as backend events arrive, (d) when the backend marks the clarification as `incorporated` or `superseded`, the UI updates to reflect — proving the backend's `clarification.*` event chain (story 2.12 AC3) is faithfully surfaced.
6. **Given** the make-or-break contract (story 2.12 AC5), **Then** the UI exposes the no-effect-reason explicitly: when a clarification is `superseded`, the UI renders the reason ("Spec rebuilt without addressing this question — superseded by spec v4"); when `rejected_invalid`, the UI renders why ("Answer text was not parseable for question type") — never a silent disappearance.
7. **Given** accessibility per UX-DR11, **Then** each question is labeled and keyboard-navigable (focus moves between questions with arrow keys or Tab), response controls are programmatically associated with the selected question (`aria-labelledby`), and ARIA live regions announce status transitions (e.g., "Clarification answer accepted" when the backend confirms) — story 2.25 enforces the broader WCAG AA rules; this story ensures live-region wiring exists.
8. **Given** content guidelines per UX-DR11, **Then** when a question is selected, it visually dominates the detail area (other questions remain navigable but secondary); reviewer wording (`answerText`) and system interpretation are visually separated (no commingling — if the system parses an answer into structured form, both raw and parsed renders are shown side-by-side with clear labels).
9. **Given** responsibility boundary per UX-DR11, **Then** the region owns: question status display, response capture, visible incorporation state — and emits `onSubmitAnswer(clarificationId, answerText)` calling `useSubmitClarification` mutation hook (story 2.6). The region does NOT own approval gating (Approval/Decision Bar in story 2.19 owns that, reading clarification status via allowed-actions endpoint per story 2.14 AC4).
10. **Given** unresolved questions block approval per story 2.14 AC4, **Then** the region displays a clear "{N} clarifications must be incorporated before approval" affordance when `pendingClarifications > 0` — making the gating reason visible to the PM rather than letting the Approval/Decision Bar appear arbitrarily disabled.
11. **Given** component test coverage, **Then** tests cover: each state renders correctly with non-color signifier, full lifecycle path (submitted → answered → accepted → incorporated) updates UI as backend events arrive (using a mocked TanStack Query backend with sequential events), superseded path with no-effect-reason rendered, rejected_invalid path with reason, ARIA live region announces status transitions, focus management when a question is selected, "no answer received" anti-pattern test (a fixture where the backend acknowledges a submission but no follow-up event arrives — the UI must visibly surface the stuck state, not show "answer received" forever).

**Dependency:** Cannot merge before story 2.24 ships (Artifact Content Sanitization + Redaction-Gap Closure). Reason: this story renders untrusted backend clarification content in the UI; 2.24 closes the F19/F20 redaction gaps that make safe rendering possible. Enforced by `dependency-edges` CI check (per `sprint-change-proposal-2026-05-19.md`).

### Story 2.19: Approval / Decision Bar — Generalized Composite (Spec Approval Mode)

As a Product Manager making a decision on a spec (and later, developer making a decision on implementation output, or operator making a recovery decision in Epic 4),
I want an `ApprovalDecisionBar` composite that **reads backend-reported allowed actions** (no frontend permission inference per UX-DR12 hard rule), is **generalized for variant modes from day one**, and concentrates the current decision into one explicit control area with clear consequences,
So that Epic 3 adds `implementation review mode` and Epic 4 adds `recovery / operator decision mode` without reshaping infrastructure (party-mode finding #3) — and the bar always sends the expected workflow + artifact + context versions to prevent stale-decision races.

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/ApprovalDecisionBar.tsx`, **Then** the component is generalized — it accepts a `mode` prop (typed: `'spec_approval' | 'implementation_review' | 'recovery_operator'`) and renders the variant per mode; the spec_approval variant is fully implemented in E2, implementation_review and recovery_operator are stub-only-renderers with documented placeholder ("available in Epic 3" / "available in Epic 4") so the mode contract holds.
2. **Given** anatomy per UX-DR12, **Then** the bar renders: current decision context (e.g., "Approve specification v3 by Alex (product_reviewer)"), primary actions (one visually primary action — `Approve` for spec_approval mode), secondary actions (`Reject with feedback` opens a rationale modal — story 2.25 patterns), required reason input where relevant (rejection requires `reasonText` + `taggedFeedback` from story 2.10), stale/conflict warning slot, immediate-consequence hint (e.g., "Approval will transition to Executing"), disabled-state explanation when an action is unavailable (e.g., "Approval blocked: 2 clarifications pending incorporation"), post-submit decision summary (after a decision lands, the bar replaces the action area with a summary timestamp + actor + decision).
3. **Given** states per UX-DR12, **Then** the bar renders: `ready` (actions enabled), `blocked` (no safe primary action available — clearly explained, NEVER shows a disabled primary action without explanation), `stale` (workflow state changed since the bar loaded — refresh CTA), `disabled` (mode-specific control restrictions), `submitting` (loading state during mutation), `success` (post-decision summary), `error` (mutation failed, error displayed), `locked` (decision already made, read-only view).
4. **Given** variants per UX-DR12, **Then** prop-based variants include: `spec_approval` mode (E2), `implementation_review` mode (stub for E3), `recovery / operator decision` mode (stub for E4), `sticky footer bar` layout (default — fixed to bottom of main pane), `inline section bar` layout (rendered inline within ARP — alternative for shorter artifacts).
5. **Given** **backend-reported allowed actions** per UX-DR12 hard rule + party-mode finding #3, **Then** the bar reads `useAllowedActions(workflowRunId)` (story 2.14) and: (a) only renders actions returned by the backend, (b) the `Approve` button is hidden if `approve_spec` is not in the allowed-actions list (e.g., due to pending clarifications per story 2.14 AC4), (c) when the bar disables an action, the disabled-state explanation comes from a backend-derived reason field (not a frontend-computed string) — UX `useAllowedActions` returns `disabledActions: { [action]: reasonCode }` so the bar maps the reason code to localized text via a documented mapping table.
6. **Given** version-stamped mutations per story 2.14 AC5, **Then** every mutation (approve / reject / answer-clarification when surfaced inline) sends `expectedArtifactVersion`, `expectedContextBundleVersion`, `expectedAllowedActionsVersionStamp` (composite from story 2.14 AC1) — when the backend returns `APPROVAL_VERSION_MISMATCH` or any version conflict, the bar renders a "stale decision" state with a refresh-and-retry CTA explaining what changed (e.g., "Spec was updated to v4 by the agent — review the new version before approving").
7. **Given** "one visually primary action per decision area" per UX-DR19 button hierarchy + UX-DR12, **Then** the bar enforces this rule: only one button uses primary styling at a time; secondary actions are visually subordinate; if no safe primary action exists, the bar renders the blocked state instead of promoting an unavailable action.
8. **Given** confirmation patterns per UX-DR18 (story 2.25 implements the modal infrastructure), **Then** rejection with feedback opens a confirmation dialog that captures `reasonText` (free-form) + `taggedFeedback` (radio selection from rework taxonomy: `missing_scope`, `unclear_specification`, `misunderstood_implementation` per story 2.10 AC1) — the dialog cannot be dismissed without an explicit cancel; submit-with-confirmation invokes the `useRejectSpec` mutation.
9. **Given** the post-submit decision summary, **Then** after a decision lands, the bar persists the outcome visibly (timestamp + actor + decision + linked event ID for audit trail) until the next workflow state change — the user sees their action's consequence rather than an empty bar.
10. **Given** accessibility per UX-DR12, **Then** all actions are keyboard reachable (Tab order matches visual order), button labels use explicit verbs ("Approve specification", "Reject with feedback" — not "OK"/"Cancel"), disabled rationale is readable by screen reader (`aria-describedby` linking the disabled button to its reason text), warning states announced via ARIA live regions, focus moves predictably into and out of confirmation dialogs (focus restoration on close per UX-DR18).
11. **Given** responsibility boundary per UX-DR12, **Then** the bar owns: decision actions, rationale capture, blocked-state messaging, visible decision outcome — it does NOT compute approval eligibility (backend does via story 2.14), hide action consequences, or omit stale-state warnings; ArchUnit-equivalent ESLint rule asserts the bar does not import allowed-actions inference logic.
12. **Given** component test coverage, **Then** tests cover: each state renders correctly, allowed-actions integration (button hidden when action absent, disabled with backend-reported reason when blocked), version-stamped mutation sends all expected versions, stale-decision UI on `APPROVAL_VERSION_MISMATCH`, rejection confirmation dialog flow, post-submit summary persists, locked state when decision already made, mode-prop dispatch (spec_approval renders fully, implementation_review + recovery_operator render their placeholders), one-primary-action rule enforced (test fixture with multiple candidate actions confirms only one renders as primary), keyboard navigation through full action set including dialog.
13. **Given** the "answered ≠ incorporated, can't approve until incorporated" rule from party-mode + story 2.14 AC4, **Then** when a PM has answered a clarification but it has not yet been incorporated by the backend, the bar visibly shows "{N} clarifications pending incorporation — approval blocked" — never silently disabled. The Clarification Region (story 2.18) provides the reciprocal affordance per its AC10.

### Story 2.20: Queue Shell States — Loading, Empty, Filtered-Empty, Error

As a Product Manager opening the review queue,
I want the queue surface to clearly distinguish "loading", "no runs to review", "no matches under current filters", and "failed to load" so I always know what's going on,
So that queue-first entry doesn't fail silently when something is missing or wrong (UX-DR14 + UX-DR17 — empty/loading/error states must explain workflow meaning, not just technical absence).

**Acceptance Criteria:**

1. **Given** `src/features/workflows/QueueShell.tsx` (or the parent of `RunReviewQueueItem` from story 2.15), **Then** the shell renders one of four explicit non-row states based on `useWorkflowsList()` query state + filter state + result count: `loading`, `empty` (no runs at all), `filtered-empty` (filters active but matched no runs), `error` (load failed).
2. **Given** the `loading` state, **Then** the queue renders skeleton row placeholders (matching `RunReviewQueueItem` height + density) — never a spinner-on-blank-page; loading communicates "queue is materializing", not "something is wrong".
3. **Given** the `empty` state, **Then** the queue renders a calm empty-state component explaining the situation contextually for the role: for the PM persona, "No specifications awaiting review. New runs from Linear will appear here once submitted via the CLI." — accompanied by a documented CTA pointing to story 1.22's quickstart docs.
4. **Given** the `filtered-empty` state, **Then** the queue renders a distinct message ("No runs match the current filters") with a clear "Clear filters" action that resets the filter state and re-issues the query — this state is visually distinct from `empty` so users can tell whether to clear filters or wait for new runs.
5. **Given** the `error` state, **Then** the queue renders an error-state component with: a stable error message derived from the Problem Details `code` (story 1.8) — never raw stack trace or HTTP status text — a "Retry" action that re-issues the query, and a "Report this if it persists" pointer.
6. **Given** UX-DR17 rule "every error state should provide the next safe action where possible", **Then** the error state always renders at least one actionable control (Retry, Clear filters, or pointer to docs); silent failure with no action is forbidden.
7. **Given** UX-DR14 explicit MVP-critical state distinction, **Then** the four states are visually + semantically distinguishable — a snapshot test renders all four states side-by-side and asserts each carries a unique label and at least one unique visual marker (icon, color, layout).
8. **Given** accessibility per story 2.25, **Then** state transitions are announced via ARIA live region (e.g., "Queue loaded: 4 runs available", "Queue is empty", "Failed to load queue — retry available"); each state component uses semantic landmarks and is keyboard-navigable.
9. **Given** responsibility boundary, **Then** the queue shell owns: state determination from query + filter + result count, rendering the four states, emitting refetch on Retry — it does NOT own filter UI (that's a separate component composed alongside) or row rendering (delegates to `RunReviewQueueItem` from story 2.15).
10. **Given** component test coverage, **Then** tests cover: each of four states renders correctly, transitions between states (e.g., loading → empty after fetch resolves with zero runs, loading → error on Problem Details failure, filtered-empty after applying filters that match nothing), Retry action invokes refetch, Clear filters action resets state, ARIA live region announces transitions.

### Story 2.21: Feedback Patterns Infrastructure (Inline / Persistent / Toast Boundaries)

As a frontend developer building any composite that triggers a mutation or surfaces a workflow-significant outcome,
I want shared feedback infrastructure that distinguishes inline / persistent-in-component / toast feedback per documented rules (UX-DR15) and connects feedback to workflow effect rather than generic UI success,
So that "answer received" is never confused with "answer incorporated" and no workflow-significant state change is communicated only by toast.

**Acceptance Criteria:**

1. **Given** `src/components/feedback/`, **Then** typed feedback primitives exist: `<InlineFeedback variant="info|success|warning|blocker|error" persistsUntil="dismiss|workflowChange|infinite">`, `<PersistentStateBadge state={SemanticState}>`, `<Toast variant="info|success|warning|error">` (using shadcn `sonner`), `<ActionLifecycleIndicator stages={['submitted', 'accepted', 'incorporated']} currentStage={...}>`.
2. **Given** UX-DR15 hard rule "no workflow-significant state change may be communicated only by toast" (also enforceable per UX-DR's pattern enforcement rules), **Then** an ESLint custom rule (extending story 2.31's rule set) flags `toast.success(...)` calls in components that handle workflow mutations (approve, reject, submit-clarification, retry) — those mutations must use `<InlineFeedback>` or `<PersistentStateBadge>` for the workflow outcome, with toast reserved for lightweight ancillary confirmations only.
3. **Given** the documented action-lifecycle vocabulary per story 2.12 + UX-DR15, **Then** mutation hooks (story 2.6 mutation pattern) emit feedback in the canonical sequence: `submitted` (immediate inline ack on call) → `accepted` (backend 200 response) → `incorporated` or `failed` (subsequent backend event) — the `<ActionLifecycleIndicator>` renders this chain in the originating component's region.
4. **Given** the rule "if an action changes the workflow, the new state should be visible in the same screen region where the user acted", **Then** all mutation results from Approval/Decision Bar (story 2.19) and Clarification Region (story 2.18) render their outcome in their own region — feedback infrastructure provides the primitives but composites place them inline.
5. **Given** the rule "if an action fails because the state is stale, the UI should explain what changed and what the user must do next", **Then** `<InlineFeedback variant="warning">` supports a `staleStateExplanation` prop that renders structured "what changed → what to do next" text — consumed by the Approval/Decision Bar's stale-decision state (story 2.19 AC6).
6. **Given** accessibility per story 2.25, **Then** `<InlineFeedback>` and `<PersistentStateBadge>` use ARIA live regions (`aria-live="polite"` for info/success, `aria-live="assertive"` for warning/blocker/error) with documented announcement text; toast uses sonner's built-in live region; non-color signifiers accompany every variant per story 2.3 AC5.
7. **Given** UX-DR15 variant inventory (inline informational / inline success-incorporated / warning-stale / blocker / error-failed-action / persistent decision outcome), **Then** every variant has a Storybook-equivalent fixture + visual regression snapshot in the primitives playground (story 2.2 AC6 extended).
8. **Given** the rule "do not collapse 'answer received' and 'answer incorporated' into one message", **Then** feedback primitives explicitly forbid concatenating these two events into a single message — a unit test of `<ActionLifecycleIndicator>` asserts that the `submitted` and `incorporated` stages always render as distinguishable visual elements.
9. **Given** mobile considerations per UX-DR15 (stories 2.21 + 2.26), **Then** inline feedback remains attached to the relevant content region in stacked layouts; toast positioning avoids covering primary action controls on mobile (sonner config tested at mobile breakpoints).
10. **Given** component test coverage, **Then** tests cover: each variant renders correctly with non-color signifier, ARIA live region announces appropriately, ActionLifecycleIndicator transitions through stages, ESLint rule catches `toast.success` in mutation handlers, stale-state explanation renders structured "what changed → next action" text.

### Story 2.22: Navigation + Empty / Loading / Error States Infrastructure

As a frontend developer building any view that navigates between queue / run detail / artifact / clarification states,
I want shared navigation patterns + standardized empty/loading/error state primitives across features,
So that navigation preserves run identity and artifact continuity (UX-DR16) and content-absent states distinguish absence vs delay vs failure vs restriction with explicit next safe actions (UX-DR17).

**Acceptance Criteria:**

1. **Given** `src/lib/navigation/`, **Then** typed navigation helpers exist: `useReturnToRunContext()` (returns the user to the prior meaningful run-centered context — not a generic top-level page), `useNavigateToArtifact(runId, artifactId)`, `useNavigateToClarification(runId, clarificationId)` (anchors to the clarification within the run detail view).
2. **Given** UX-DR16 rule "navigation should always preserve current run identity, current artifact identity, current workflow state", **Then** every navigation helper passes these three identifiers through TanStack Router state + URL — a route guard / loader pattern asserts they are present before rendering composites that depend on them.
3. **Given** "back navigation should return users to the prior meaningful review context, not to a generic top-level page", **Then** the navigation library tracks a per-session breadcrumb stack of meaningful contexts (queue page, specific run, specific artifact) — the AppShell's back affordance (when present) consults this stack rather than relying solely on browser history.
4. **Given** `src/components/feedback/states/` (or extending the feedback module from story 2.21), **Then** typed state components exist: `<EmptyState variant="queue|filtered|artifactNotGenerated|noOpenQuestions|noMeaningfulDiff" message={...} action={...}>`, `<LoadingState variant="fetchingData|generatingArtifact|rebuildingAfterRejection|retryingRecovery">`, `<ErrorState variant="failedRetrieval|unavailableDiffBaseline|permissionRestricted|blockedByStaleState" message={...} nextAction={...}>`.
5. **Given** UX-DR17 rule "Empty states should distinguish: no runs / no results after filtering / no artifact generated yet / no open questions / no meaningful diff", **Then** the variant prop on `<EmptyState>` enforces this distinction — a TypeScript exhaustiveness check ensures new empty contexts are added to the variant union before they can render.
6. **Given** UX-DR17 rule "Every error state should provide the next safe action where possible", **Then** `<ErrorState>` requires a `nextAction` prop (typed as a `NextAction` union: `Retry | Refresh | NavigateBack | ContactSupport | DocsLink` with action-specific payloads) — TypeScript prevents rendering an error state without an action.
7. **Given** UX-DR17 rule "Loading states should indicate whether the system is fetching / generating / rebuilding / retrying", **Then** `<LoadingState>` requires a `variant` prop matching one of those four meanings — a generic spinner with no semantic context is forbidden by an ESLint rule (extension of story 2.31).
8. **Given** UX-DR17 rule "Empty, loading, and error states should appear inside the affected region, not only globally", **Then** state components are designed for in-region rendering (sized to fit their parent container) — a global app-level error overlay is reserved for catastrophic auth/network failures only and lives in the AppShell from story 2.7.
9. **Given** accessibility per story 2.25, **Then** state messages remain readable without color/icon dependence; loading state announces appropriately when it materially affects interaction; errors use semantic alert treatment (`role="alert"` for active errors, lower-urgency landmarks for passive empty states).
10. **Given** UX-DR16 rule "compare and clarification interactions should preserve the current artifact context and not disorient the user", **Then** the navigation library exposes a `withRunContext()` HOC / hook pattern that wraps any sub-state navigation (e.g., entering Compare Mode in Epic 4, entering a Clarification deep-link) so the run + artifact context is preserved on return — verified by a test that asserts entering and exiting compare/clarification returns to the exact prior scroll position + artifact + selection.
11. **Given** component test coverage, **Then** tests cover: each empty/loading/error variant renders with appropriate content, exhaustiveness check fails when a new variant is missing from the type union, `nextAction` prop required by error state, breadcrumb stack returns to the correct prior context, run+artifact+state preservation across compare/clarification entry/exit (compare mode is stubbed in E2 — the test fixtures use the stub).

### Story 2.23: Modal / Overlay / Confirmation Patterns + Button Hierarchy Infrastructure

As a frontend developer needing to confirm a high-consequence action (reject with reason, approve when stale/conflict, stop orchestrator, retry) or surface bounded secondary detail without losing run context,
I want shared modal/overlay/confirmation pattern primitives plus button-hierarchy infrastructure enforcing one visually primary action per decision area,
So that overlays are reserved for genuinely consequential interactions (UX-DR18) and action hierarchy reflects governed-workflow seriousness (UX-DR19).

**Acceptance Criteria:**

1. **Given** `src/components/overlays/`, **Then** typed overlay primitives exist: `<ConfirmationDialog title intent="danger|warning|info" consequence required>`, `<RationaleCaptureDialog title fields={[...]}>`, `<BoundedDetailSheet title>`, `<NonDismissibleCriticalWarning title body acknowledgmentLabel>`.
2. **Given** UX-DR18 rule "use confirmation dialogs for actions with meaningful workflow consequence", **Then** `<ConfirmationDialog>` requires a `consequence` prop (string, mandatory) describing what will happen if confirmed — a TypeScript-required prop, not optional; rationale capture (e.g., for rejection feedback) uses `<RationaleCaptureDialog>` which composes `<ConfirmationDialog>` with structured field inputs.
3. **Given** UX-DR18 rule "do not require modal confirmation for low-risk navigation or simple compare entry", **Then** an ESLint custom rule (extension of story 2.31) flags `<ConfirmationDialog>` usage in navigation handlers + compare-entry handlers + view-only state changes — those must use inline action without a modal.
4. **Given** UX-DR18 documented confirm-before list, **Then** the following actions ALWAYS use confirmation: reject with reason (Approval/Decision Bar story 2.19), approve when stale/conflict risk exists, stop orchestrator processing (Epic 3), retry/recover when consequential (Epic 4); a shared catalog `src/lib/overlays/confirmationCatalog.ts` documents which actions require confirmation across the app.
5. **Given** UX-DR18 accessibility rules, **Then** `<ConfirmationDialog>` enforces: focus moves into the overlay on open, focus returns to the triggering element on close (focus-restoration tested), dialog title + consequence are explicit for screen readers (`aria-labelledby` + `aria-describedby`), Escape dismissal is predictable except where unsafe (e.g., `<NonDismissibleCriticalWarning>` blocks Escape).
6. **Given** UX-DR18 mobile considerations, **Then** at narrow breakpoints (per story 2.26) `<BoundedDetailSheet>` uses full-height sheet pattern (slide-up from bottom) rather than centered dialog; destructive actions use `intent="danger"` styling and remain clearly separated from confirm action in touch layouts.
7. **Given** UX-DR19 rule "one primary action per decision area", **Then** `<Button>` (extending shadcn primitive) supports a `priority="primary|secondary|tertiary|destructive|blocked"` prop — and an ESLint custom rule asserts no React component renders more than one `<Button priority="primary">` within the same `<ButtonGroup>` or `<DecisionArea>` container.
8. **Given** UX-DR19 rule "if no safe primary action exists, the interface should show a blocked state rather than visually promoting an unavailable action", **Then** `<Button priority="blocked">` is a documented variant rendering as a non-interactive blocked-state visual (using state-blocker token from story 2.3) with adjacent explanation text — composites use this instead of a disabled primary button when no safe action exists.
9. **Given** UX-DR19 rule "buttons should reflect workflow truth (ready, blocked, stale, submitting, completed)", **Then** `<Button>` accepts a `workflowState="ready|blocked|stale|submitting|completed"` prop that maps to documented visual treatments and ARIA semantics (e.g., `submitting` shows spinner + `aria-busy`, `completed` shows post-action checkmark + `aria-live` confirmation).
10. **Given** UX-DR19 rule "post-decision state should remain visible after the button action completes", **Then** `<Button>` in `workflowState="completed"` persists its outcome visual until parent component explicitly resets — never auto-clears; consumers like Approval/Decision Bar (story 2.19 AC9) control reset timing.
11. **Given** UX-DR19 mobile rule "primary decision actions should remain reachable without hunting", **Then** mobile breakpoint tests (story 2.26) verify primary actions don't collapse into menus; secondary/tertiary may collapse into overflow menus, but never the primary.
12. **Given** component test coverage, **Then** tests cover: ConfirmationDialog focus management + Escape behavior + consequence prop required, RationaleCaptureDialog field validation, BoundedDetailSheet mobile full-height variant, NonDismissibleCriticalWarning blocks Escape, single-primary-action ESLint rule catches violations, blocked-state Button variant renders correctly with explanation, workflowState="completed" persists outcome until reset.

### Story 2.24: Artifact Content Sanitization + Redaction-Gap Closure (Untrusted Runner Output)

As a frontend developer rendering markdown content produced by an LLM-driven runner — combined with a backend redaction-policy maintainer,
I want a hardened markdown sanitization library + diff sanitization + safe artifact rendering pipeline AND closure of the F19/F20 redaction-policy gaps deferred from Epic 1 (PEM blocks, bundle JSON shapes, Idempotency-Key header, missing credential/bearer/private patterns),
So that untrusted runner output cannot inject scripts, exfiltrate via crafted links, mislead reviewers via metadata spoofing, OR leak secrets that the backend redactor missed — closing the threat-model shift identified in the Epic 1 retrospective (2026-05-19): the CLI pilot didn't render untrusted content in a UI, but Epic 2 does, so backend-side redaction gaps that were "acceptable for CLI" now become UI-rendering vulnerabilities (see sprint-change-proposal-2026-05-19.md).

**Acceptance Criteria:**

1. **Given** `src/lib/sanitization/`, **Then** a typed `SafeMarkdownRenderer` component exists wrapping a known-good markdown library (e.g., `react-markdown` + `rehype-sanitize` + `remark-gfm`) with a strict allowlist policy: only documented HTML tags, no `<script>`, no `<iframe>`, no `<style>`, no event handler attributes, no `javascript:` URLs.
2. **Given** the rendering pipeline, **Then** the allowlist covers: headings (`h1`–`h6`), text (`p`, `strong`, `em`, `code`, `pre`, `blockquote`), lists (`ul`, `ol`, `li`), links (`a` — but with documented `rel="noopener noreferrer"` + URL-scheme validation), tables (`table`, `thead`, `tbody`, `tr`, `th`, `td`), images (`img` — but only from same-origin sources or documented allowlist; agent-generated image URLs treated as untrusted by default and rendered as link-only).
3. **Given** code blocks, **Then** they render as plain text within a `<code>`/`<pre>` container with syntax highlighting from a documented allowlisted library (e.g., `shiki` or `prism`) — never executing embedded HTML, never interpreting JavaScript even in language="javascript" blocks.
4. **Given** link handling, **Then** the renderer validates the URL scheme (only `http`, `https`, `mailto` allowed; `javascript:`, `data:`, `file:` rejected and rendered as plain text), adds `rel="noopener noreferrer"` to all external links, and visually distinguishes external links from internal ones (small external-link icon).
5. **Given** the diff renderer (used in change-summary slot from story 2.17 AC2 and full Compare Mode in Epic 4), **Then** diff content is rendered as plain text within structured before/after panels — never as raw HTML; line-level additions/deletions use semantic `<ins>`/`<del>` with stable token classes.
6. **Given** metadata spoofing prevention per story 1.10 AC10, **Then** the artifact panel visually separates **trusted system metadata** (artifact title, version, classification badge — rendered from backend-typed fields with no markdown interpretation) from **generated content** (artifact body — rendered through `SafeMarkdownRenderer`); a documented visual treatment makes the distinction explicit.
7. **Given** XSS test fixtures, **Then** `src/lib/sanitization/__tests__/xss-fixtures/` contains an adversarial fixture set covering: `<script>` tag injection, `<img onerror>` event handler injection, `<a href="javascript:...">` URL scheme attack, `<iframe src="...">` injection, CSS-based exfiltration attempts (`<style>` injection), markdown link with `javascript:` URL, HTML entity-encoded script attempts, mixed-case `<ScRiPt>` evasion attempts, polyglot payloads; tests assert each fixture renders as inert text or is rejected entirely.
8. **Given** Murat's "risk-weight artifact sanitization tests highest" call, **Then** sanitization tests run as a dedicated CI gate (under the redaction-and-sanitization tier in story 1.21 CI pipeline) and a sanitization regression treats a single passing-XSS-fixture as a build-blocking failure — never a warning.
9. **Given** the renderer's bundle size impact, **Then** the markdown + sanitization libraries are documented in the frontend dependency review; alternatives that exceed a documented bundle threshold are rejected without justification.
10. **Given** future Compare Mode (Epic 4) + implementation-plan + PR-output variants, **Then** the same sanitization pipeline serves all artifact variants — variant-specific renderers (story 2.17 AC3 stubs) inherit the safe rendering primitives rather than rolling their own.
11. **Given** ArchUnit-equivalent ESLint rule, **Then** any direct use of `dangerouslySetInnerHTML` or unsanitized HTML rendering anywhere in the frontend codebase fails lint — the only sanctioned path for runner-produced HTML is `SafeMarkdownRenderer` or the documented diff pipeline.
12. **Given** component test coverage, **Then** tests cover: every adversarial fixture renders as inert, sanitization removes disallowed tags but preserves allowed ones, link URL-scheme validation, `rel="noopener noreferrer"` always present on external links, code blocks never execute, metadata-spoofing visual separation persists across artifact variants.
13. **Given** the F19/F20 redaction-policy gaps from `_bmad-output/implementation-artifacts/deferred-work.md`, **Then** story 2.24 includes a backend-side redaction-policy hardening pass that extends the `RedactionPolicy` service from story 1.10 to cover: (a) PEM-formatted blocks (`-----BEGIN [A-Z ]+-----` ... `-----END [A-Z ]+-----`), (b) bundle JSON shapes (any object key matching `(?i)(secret|token|password|api[_-]?key|credential|bearer|private[_-]?key|access[_-]?token|refresh[_-]?token)`), (c) the `Idempotency-Key` HTTP header in any logged/exported request shape, (d) the field-name allowlist extended from `{password,token,secret}` to `{password,token,secret,credential,bearer,private,access_token,refresh_token,api_key,client_secret}`.
14. **Given** the extended RedactionPolicy, **Then** the adversarial fixture set from story 1.10 (`backend/src/test/resources/redaction-fixtures/`) is extended with: a PEM RSA private key, a PEM EC private key, a PEM certificate-with-private-key bundle, a bundle JSON containing nested `credential`/`bearer`/`private_key` values, a request log shape containing `Idempotency-Key: <uuid>`; each fixture has an `.expected-redacted` sidecar asserting the post-redaction shape. The `RedactionPolicyContractTest` extends to include these fixtures and is build-blocking.
15. **Given** the frontend SafeMarkdownRenderer from AC1, **Then** a **second-pass frontend redaction filter** runs on rendered text content (after sanitization, before display) that re-asserts the redaction policy as defense-in-depth — using a frontend port of the same patterns from AC13, sourced from a shared spec file (`runner-contracts/redaction-policy.json` — new artifact) to prevent frontend/backend pattern drift. A passing redaction fixture in the frontend test suite is build-blocking.
16. **Given** the shared `runner-contracts/redaction-policy.json` spec, **Then** a contract test (in `runner-contracts/` module per the existing E1 contract-test pattern) asserts the backend RedactionPolicy and the frontend filter consume identical pattern sets. Pattern additions in either side must update the JSON spec; CI fails on drift.
17. **Given** the visible-distinction-from-redaction-failure UX, **Then** when redaction detects a pattern hit in untrusted content, the rendered output shows a documented `[REDACTED: <classification>]` placeholder (matching the backend's redaction sentinel convention from story 1.19) — never silently dropping characters. The placeholder is visually distinguishable from author-written `[REDACTED]` literals (e.g., wrapped in `<mark class="redaction-applied">` with a tooltip "Redaction applied — see audit log").
18. **Given** the F19/F20 closure, **Then** `deferred-work.md` F19 and F20 entries are marked `closed by 2-24` with a link to the story. Any redaction-pattern additions discovered during story execution are added to the JSON spec and tracked in the same file, not as new deferred entries.

### Story 2.25: WCAG 2.1 AA Compliance (Keyboard + Focus + ARIA + Contrast + Audit-Label Semantics)

As a Product Manager (or any user with a disability — keyboard-only, screen-reader, low-vision, motor-impaired),
I want the entire E2 review experience to meet WCAG 2.1 AA — keyboard navigability, visible focus, semantic landmarks + ARIA, contrast verified — and audit-role labels in the UI to be honest "recorded label" semantics rather than implying enforced authorization,
So that the governed-review workflow is genuinely usable with assistive technology (UX-DR20) and the UI does not mislead users about MVP's deferred-RBAC security posture (UX-DR21).

**Acceptance Criteria:**

1. **Given** every interactive composite from stories 2.15–2.19 + state primitives from 2.20–2.23, **Then** all are fully keyboard-operable: Tab order matches visual order, every action reachable without mouse, focus visible at all times via the focus ring token from story 2.4 AC6.
2. **Given** axe-core automated a11y testing wired into the frontend test suite (story 2.27), **Then** every component test runs an axe scan and fails on any `wcag2aa` violation — no warnings ignored without documented justification.
3. **Given** keyboard-only end-to-end flows, **Then** Playwright (or equivalent) keyboard-only journey tests cover: queue entry → run open → spec read → clarification answer → spec approval; queue entry → spec read → spec rejection with feedback; navigation back to queue with prior context preserved.
4. **Given** semantic landmarks per UX-DR20, **Then** the AppShell uses `<nav>`, `<main>`, `<aside>` (story 2.7 AC7); each composite uses appropriate semantic regions (`<section>` with `aria-labelledby`); list-like surfaces use `<ul>`/`<ol>`/`<li>` or `<table>`/`<tr>`/`<td>` per content shape.
5. **Given** ARIA live regions per UX-DR20, **Then** asynchronous workflow updates (clarification status transitions per story 2.18, decision outcomes per story 2.19, queue state transitions per story 2.20, feedback lifecycle per story 2.21) announce via `aria-live` regions with documented announcement text — not via toast-only signals.
6. **Given** contrast requirements, **Then** the contrast test from story 2.3 AC4 runs against every documented foreground/background pair in semantic-state tokens; story 2.20 + 2.21 + 2.23 components additionally pass contrast audit against their actual rendered states (not just token-pair check).
7. **Given** UX-DR20 "consistent announcement of stale, blocked, error, and completed states", **Then** a documented announcement vocabulary lives in `src/lib/a11y/announcements.ts` mapping each semantic state + lifecycle event to an announcement string — composites use the vocabulary rather than rolling their own announcement text; an audit test verifies all live-region usages reference the vocabulary.
8. **Given** UX-DR21 audit-label semantics, **Then** any UI element displaying an actor role (e.g., the Approval/Decision Bar's "approved by Alex (product_reviewer)") includes a `<small>` or tooltip clarifying the role is "recorded for audit only — not an enforced permission" — visible on first hover/focus and present in the accessible name; an ESLint custom rule (extension of story 2.31) flags rendering of role text without the audit-label clarification wrapper.
9. **Given** UX-DR21 frontend rule "frontend code must not gate actions based on audit role labels", **Then** a code-review checklist + ArchUnit-equivalent rule asserts no `if (actorRole === ...)` permission gate exists in frontend code — all action gating goes through `useAllowedActions` (story 2.14 / 2.6).
10. **Given** touch-target sizing per UX-DR20, **Then** interactive elements meet a documented minimum touch target size (24×24 CSS pixels for desktop, 44×44 for mobile per WCAG 2.5.5 AAA-aspirational target — AA requires only that touch targets not be too small to use, but the project commits to the higher bar) — verified by a layout test on each composite at mobile breakpoints.
11. **Given** screen-reader spot-check coverage per UX-DR24, **Then** a documented manual-testing checklist exists in `docs/testing/a11y-screen-reader-checklist.md` covering NVDA + VoiceOver runs of the critical journeys; the checklist must be executed at least once before E2 epic close (referenced from the foundation-gate-equivalent E2 close gate in story 2.28).
12. **Given** test coverage, **Then** axe-core scan failures, contrast failures, missing live-region announcements, missing audit-label wrappers, and Tab-order regressions all fail CI in the frontend-build-tests tier (story 1.21).

### Story 2.26: Responsive Design (Mobile/Tablet/Desktop Breakpoints + Structural Collapse Rules)

As a Product Manager occasionally needing to triage on a phone (Galaxy S23+ class) or tablet,
I want the full E2 PM-loop usable across desktop / tablet / mobile breakpoints with structural collapse rules that preserve artifact reading, decision controls, and run identity even as side panels collapse,
So that mobile-and-tablet usage genuinely supports governed decisions per UX-DR22 + UX-DR23 — not a desktop layout squeezed into a phone.

**Acceptance Criteria:**

1. **Given** Tailwind config from story 2.2, **Then** breakpoints are explicitly: `mobile: 320–767px`, `tablet: 768–1023px`, `desktop: 1024px+` (per UX-DR22 specification) — Tailwind's default `sm`/`md`/`lg` aliases are mapped accordingly with documented `tailwind.config.ts` overrides.
2. **Given** the AppShell from story 2.7 + structural collapse rules per UX-DR23, **Then** at desktop the full tri-pane renders; at tablet the right context panel becomes togglable (slide-out drawer with persistent toggle button) while left nav rail + main pane remain; at mobile the layout collapses to single-column artifact-first with a top-nav menu for queue navigation and a bottom-anchored decision bar.
3. **Given** UX-DR23 priority order on narrow screens "preserve artifact reading > preserve decision controls > disclose navigation/supporting context", **Then** mobile layout always reserves the central viewport for artifact content; the decision bar from story 2.19 uses sticky-footer placement on mobile (always reachable without scroll); supporting context (run context strip, clarification region) accessible via tabs/drawers/accordions.
4. **Given** UX-DR23 rule "compare becomes a dedicated bounded mobile state rather than compressed side-by-side", **Then** Compare Mode (Epic 4) is documented in the responsive ADR as a mobile-specific full-screen state with explicit before/after toggle — story 2.17's Compare Mode entry control already disabled in E2; the responsive plan reserves the mobile UX pattern.
5. **Given** UX-DR23 rule "run identity and current state should never disappear during collapse", **Then** the mobile top-nav always renders run identity + current state badge when inside a run — verified by responsive layout tests that resize viewport across breakpoints and assert these elements remain visible.
6. **Given** UX-DR22 mobile must support all critical actions, **Then** the mobile review flow supports: browsing queue → opening a run → reading current artifact → answering clarifications → approving or rejecting with reason → entering Compare Mode (Epic 4 stub) — a mobile-flow E2E test (story 2.27) covers each.
7. **Given** mobile decision controls per story 2.23 AC11, **Then** primary actions remain visible without hunting; secondary actions may collapse into overflow menu but the primary `Approve` / `Reject with feedback` remain in the sticky footer at all times.
8. **Given** UX-DR23 rule "supporting context should move into drawers, tabs, sheets, or accordions before the artifact becomes unreadable", **Then** breakpoint-conditional rendering lives in a documented `useResponsiveLayout()` hook returning `'mobile' | 'tablet' | 'desktop'` based on `matchMedia` — composites use the hook + Tailwind responsive utilities consistently rather than ad-hoc media queries.
9. **Given** Galaxy S23+ class real-device testing per UX-DR24, **Then** a documented manual-testing checklist exists in `docs/testing/responsive-real-device-checklist.md` covering critical-flow validation on a real Galaxy S23+ (or equivalent — documented reference device in `docs/supported-environments.md`); checklist execution required before E2 epic close.
10. **Given** UX-DR23 "structural collapse rules", **Then** an ADR `src/features/workflows/RESPONSIVE.md` documents: which panels collapse first, which UI surfaces become drawers/tabs/sheets/accordions, which elements are non-collapsible (run identity, state, primary decision action), and the collapse order under viewport pressure — referenced by future story ACs.
11. **Given** browser coverage per UX-DR24, **Then** primary flows are validated across modern Chrome, Firefox, Safari, and Edge (current + n-1 versions) in CI via a Playwright cross-browser job (extension of story 2.27); coverage is documented and a documented browser-support policy excludes IE/legacy.
12. **Given** component + layout test coverage, **Then** tests cover: each breakpoint renders correct layout, structural collapse order respected (right panel collapses before main pane narrows per story 2.7 AC2), run identity + state badge always visible across breakpoints, primary decision action always reachable on mobile sticky footer, drawer/tabs/sheets render correctly when invoked at narrow breakpoints, `useResponsiveLayout` hook returns correct breakpoint per viewport.

### Story 2.27: Frontend Test Suite (Component + Route + Query Hook + A11y + Sanitization + Cross-Browser)

As a frontend developer,
I want a comprehensive frontend test suite — Vitest + React Testing Library + jest-dom + MSW for API mocking + axe-core for a11y + Playwright for cross-browser end-to-end + adversarial sanitization fixtures — wired into the CI frontend-build-tests tier,
So that every composite, route, and query hook from stories 2.5–2.26 has automated coverage and the WCAG / responsive / sanitization commitments hold under CI rather than relying on manual review.

**Acceptance Criteria:**

1. **Given** `package.json`, **Then** test infrastructure is configured: Vitest as the test runner, `@testing-library/react` + `@testing-library/jest-dom` for component testing, `msw` (Mock Service Worker) for backend API mocking against the generated client (story 2.6), `@axe-core/react` (or `vitest-axe`) for accessibility scans, Playwright for end-to-end + cross-browser tests.
2. **Given** the MSW setup, **Then** request handlers are derived from the OpenAPI snapshot (story 2.6 AC1) so mocked responses conform to the same schema the real backend returns — preventing mock/prod divergence.
3. **Given** the foundation fixture event stream from story 1.23, **Then** frontend tests reuse those fixtures (loaded via MSW handlers or imported directly) so component tests run against realistic event sequences (happy path, spec-rejection-and-resubmit, execution-failure-with-retry, full clarification lifecycle per story 2.12 AC8) — not synthetic happy-path-only data.
4. **Given** component test coverage requirements, **Then** every composite from stories 2.15–2.19 + every state primitive from stories 2.20–2.23 has Vitest + Testing Library tests asserting: each documented state renders correctly, all ACs marked with "component test coverage" in their respective stories are covered, axe-core scan passes with zero `wcag2aa` violations.
5. **Given** route test coverage, **Then** each TanStack Router route from story 2.5 has tests asserting: typed param validation rejects malformed IDs, route loader prefetches the right query, missing-resource handling routes to the correct empty state, deep-link entry renders correctly, scroll-position preservation across navigation works.
6. **Given** query hook test coverage, **Then** each TanStack Query hook from story 2.6 (`useWorkflowDetail`, `useWorkflowEvents`, `useArtifact`, `useAllowedActions`, `useClarifications`) has tests covering: success path with typed response, Problem Details error path with stable error code, mutation success invalidates correct downstream queries (per story 2.6 AC6), Idempotency-Key header sent on every mutation (per story 2.6 AC7), stale time + cache time defaults respected.
7. **Given** sanitization regression coverage per story 2.24, **Then** the adversarial XSS fixture set is wired into the test suite as a dedicated test file; a single passing-XSS-fixture is build-blocking (story 2.24 AC8).
8. **Given** Playwright cross-browser end-to-end coverage per story 2.26 AC11, **Then** Playwright tests exist for the critical journeys (queue → run → spec read → clarification answer → approve; queue → run → spec read → reject with feedback) and run across Chrome, Firefox, Safari, and Edge in CI matrix; mobile viewport variants run for the same journeys at the Galaxy S23+ class viewport size from story 2.26 AC9.
9. **Given** keyboard-only journey coverage per story 2.25 AC3, **Then** Playwright tests use keyboard-only navigation (Tab/Shift+Tab/Enter/Space/Escape only — no `.click()` calls) for the critical journeys; tests fail if any action becomes unreachable without a mouse.
10. **Given** coverage thresholds, **Then** documented minimum coverage thresholds are enforced (e.g., 80% line coverage on `src/features/workflows/`, 90% on `src/lib/sanitization/` and `src/lib/queryKeys/`); thresholds documented in `frontend/README.md` with rationale; CI fails when thresholds are not met.
11. **Given** test parallelization + flake control, **Then** tests run in parallel where safe (per Vitest defaults); known-flaky tests are explicitly quarantined with documented justification (per story 1.21 AC5 — flake metrics surfaced, not masked); a no-blanket-retry rule applies.
12. **Given** CI integration with the `frontend-build-tests` tier from story 1.21, **Then** Vitest unit/component tests run first, axe scans run alongside, MSW-backed integration tests next, Playwright cross-browser job runs as a separate matrix job (so Vitest failures fail fast before the slower Playwright job consumes CI time).
13. **Given** the foundation-gate verification from story 1.23, **Then** its scope widens to include "frontend test suite green on the branch" — so frontend regression catches at the same gate as backend regressions.

### Story 2.28: SPA Fallback Controller + Maven Bundled-Jar Smoke + Packaging Integration

As a pilot installer running the bundled `deliveryline.jar`,
I want Spring Boot to serve the bundled React SPA with `SpaFallbackController` so deep links work, plus a CI bundled-jar smoke test asserting the packaged jar contains the SPA + the SPA + REST round-trip works end-to-end,
So that AR33 (SPA fallback supporting direct refresh of React routes without masking missing API endpoints) holds under packaging — and Epic 2's UI is genuinely deployable as one executable jar (AR32).

**Acceptance Criteria:**

1. **Given** `infrastructure.web` package per architecture project structure (story 6.9 already established `adapters.rest`), **Then** `SpaFallbackController` exists and forwards GET requests for non-API, non-static-asset paths to `/index.html` so React Router (story 2.5) handles client-side routing.
2. **Given** SPA fallback rules per AR33, **Then** the controller does NOT fall through for: requests under `/api/**` (REST endpoints — must return their actual response or REST 404), requests for static asset paths (`/assets/**`, `/static/**`, `*.js`, `*.css`, `*.svg`, `*.ico`, `*.png`, etc. — must return the real asset or 404), requests under `/v3/api-docs` and `/swagger-ui.html` (OpenAPI surfaces — must serve their real responses), `/actuator/**` (Spring Boot Actuator).
3. **Given** test coverage of SPA fallback rules, **Then** integration tests assert: GET `/workflows/run_abc` returns `index.html` with React app, GET `/api/v1/workflows/nonexistent` returns 404 Problem Details (NOT `index.html`), GET `/assets/missing.js` returns 404 (NOT `index.html`), GET `/v3/api-docs` returns the OpenAPI doc (NOT `index.html`), GET `/actuator/health` returns the actuator response — these tests run as the **API path collision tests** referenced in story 2.5 AC5 + story 6.9 AC9.
4. **Given** static asset cache headers per architecture quality gates, **Then** built React assets (under `/assets/`) carry long-cache `Cache-Control: max-age=31536000, immutable` (Vite's content-hashed filenames make this safe); `index.html` carries no-cache headers so users get the latest SPA shell on every page load.
5. **Given** Maven packaging per story 2.1 AC3, **Then** the backend's `pom.xml` declares an explicit dependency on `deliveryline-frontend`'s build output via `frontend-maven-plugin` (or equivalent) and copies `target/dist/` into `backend/src/main/resources/static/` before jar assembly — failing the backend build with a clear error if the frontend dist is missing or empty.
6. **Given** the Maven build, **When** `mvn clean package` runs, **Then** the resulting `deliveryline-{version}.jar` contains the compiled React SPA under `BOOT-INF/classes/static/` — verified by a packaging contract test that unzips the jar and asserts `index.html`, `assets/`, and the entrypoint JS file are present.
7. **Given** the bundled-jar smoke test as a CI tier per story 1.21 AC1, **Then** the smoke test: launches the packaged jar with the `local` profile against a Testcontainers PostgreSQL, runs Flyway migrations including V2 from story 2.11, hits `GET /` and asserts `index.html` is served, hits `GET /api/v1/workflows` and asserts a 200 response with the documented schema, hits a non-existent route `/some/spa/route` and asserts `index.html` is served (SPA fallback works), hits `GET /api/v1/workflows/nonexistent` and asserts 404 Problem Details (NOT SPA fallback), shuts down cleanly.
8. **Given** cross-platform packaging per story 1.17 supported-environment matrix, **Then** the bundled-jar smoke test runs on `ubuntu-latest` in CI; a documented note specifies that Windows + macOS jar smoke tests can be enabled in a follow-up CI extension if pilot demand emerges (deferred to keep CI minutes manageable per story 1.21 AC3).
9. **Given** REST endpoint preservation, **Then** an explicit test asserts that every documented REST mutation endpoint from story 2.13 + read endpoint from story 6.9 resolves correctly through the bundled jar (not masked by SPA fallback) — protecting against accidental fallback ordering bugs as endpoints expand in Epic 3+.
10. **Given** failure modes, **When** the frontend dist is missing during packaging, **Then** Maven build fails with a clear message ("frontend dist not found at expected path; ensure `mvn clean install` completed successfully in `deliveryline-frontend`"); when SPA assets fail to load at runtime, the bundled-jar smoke test fails with a documented diagnostic.
11. **Given** the foundation-gate verification from story 1.23, **Then** its scope widens to include "bundled-jar smoke green on the branch" — protecting against packaging regressions across all future epics.

### Story 2.29: PM-Loop Walkthrough Documentation Increment

As a Product Manager joining the pilot,
I want a `docs/pm-loop-walkthrough.md` that walks me through opening a run from the queue, reading a specification, answering clarification questions, understanding the visible incorporation lifecycle, and approving or rejecting with structured feedback — end-to-end with annotated screenshots or diagrams,
So that I can complete the PM review loop on my first pilot run unaided (NFR42 satisfied for the PM persona) — and the make-or-break refinement (answered ≠ incorporated) is set as an explicit expectation rather than a surprise.

**Acceptance Criteria:**

1. **Given** `docs/pm-loop-walkthrough.md`, **Then** it follows a linear sequence: prerequisites (DeliveryLine running locally per quickstart from story 1.22; a governed run exists in `WaitingForSpecApproval` state) → open the review queue → identify a run needing review → open the run → read the specification → understand the run context strip → answer any open clarification questions → understand the visible incorporation lifecycle → approve OR reject with structured feedback → see the workflow advance (or rebuild) → return to the queue.
2. **Given** target completion time, **Then** the doc states "~10 minutes from opening the queue to completing your first review decision".
3. **Given** the make-or-break refinement, **Then** a dedicated section "What you'll see when you answer a clarification" explicitly explains the lifecycle states (submitted → answered → accepted → incorporated / superseded / rejected_invalid), with the canonical anti-pattern called out: "If you submit an answer and the lifecycle indicator never advances past 'answered', the spec is not yet rebuilt with your input — wait for 'incorporated' before approving" — directly preempting the PRD's documented make-or-break failure mode.
4. **Given** screenshots or annotated diagrams (Mermaid OK), **Then** at minimum the following are illustrated: the queue page showing a run needing review, the tri-pane shell with an open run, the Clarification Region showing the lifecycle indicator, the Approval/Decision Bar in `ready` and `blocked` states (the latter when clarifications are pending), the rejection-with-feedback dialog with the rework taxonomy options.
5. **Given** the rework taxonomy from story 2.10 + AR34a, **Then** a section explains each tag (`missing_scope` / `unclear_specification` / `misunderstood_implementation`) with concrete examples of when to use each — supporting consistent measurement (AR34b in Epic 5 surfaces the data).
6. **Given** the role-label semantics from story 2.25 / UX-DR21, **Then** a callout box clarifies "Your role appears as 'product_reviewer' for audit purposes — the MVP does not enforce role-based access; anyone with local DeliveryLine access can perform any action. The role is recorded for traceability, not authorization."
7. **Given** version-mismatch handling from story 2.19 AC6, **Then** a "What if I see 'Spec was updated'?" section explains the `APPROVAL_VERSION_MISMATCH` UI state and what the user should do (refresh, re-read the new version, decide again — never approve a spec you didn't review).
8. **Given** cross-platform usability, **Then** the walkthrough is browser-based and contains no OS-specific instructions — works identically on Windows / macOS / Linux per story 1.17 supported-environment matrix.
9. **Given** the link-check CI step from story 1.22 AC8, **Then** all internal doc links in `pm-loop-walkthrough.md` resolve to real files; cross-references to stories 1.22, 2.10, 2.19 are anchored correctly.
10. **Given** documentation-increment acceptance per Epic 2's doc-increment rule (pre-mortem refinement R7), **Then** Epic 2 cannot close without `pm-loop-walkthrough.md` merged + visible from `docs/index.md` (or equivalent docs index); the foundation-gate-equivalent E2 close gate verifies its presence.
11. **Given** a PM-validator placeholder (parallel to story 1.22 AC7 — John's party-mode finding "name the human validator per epic"), **Then** the doc includes a placeholder line for "PM-loop validator: ***\_***_ (to be named before Epic 2 close)" — reminding Alex to identify and coordinate with the real human PM whose cold walkthrough validates the epic.
12. **Given** NFR43 (minimize new concepts), **Then** the walkthrough uses only the concept set declared in the PRD (ticket, spec, run, artifact, review, failure, recovery action) plus the lifecycle vocabulary (open / answered / accepted / incorporated / superseded / rejected_invalid) — new concepts require updating `docs/glossary.md` from story 1.22 AC10.

### Story 2a-1: UI Submit — New Governed Run from the Web App

> Net-new per `sprint-change-proposal-2026-06-07.md` (Epic 2a active slice). Closes the non-CLI intake gap on the UI side: realizes PRD FR1's PM-initiation, which the FR-coverage table had scoped to Epic 1 (CLI) only.

As a Product Manager / Workflow Owner,
I want a "Submit a Run" form in the web app that creates a governed run from a Linear ticket reference — calling the existing `POST /api/v1/workflows/submit-workflow` endpoint,
So that I can initiate work without dropping to the CLI.

**Backend status:** DONE — `WorkflowController.submit()` → `POST /api/v1/workflows/submit-workflow` with body `SubmitWorkflowRequest { linearTicketReference, actorIdentity, actorType, correlationId }` already exists and is in the generated OpenAPI client. **No backend change.**

**Dependencies:** 2.5 (TanStack Router), 2.6 (TanStack Query + generated API client), 2.7 (app shell), 2.13 (REST mutation endpoints — provides submit-workflow in OpenAPI), 2.19 (mutation-hook + button-precedence + idempotency conventions), 1.8 (ProblemDetails error rendering). Coupling note: persistent feedback infra is story 2.21 (Epic 2b, backlog) — the submit-result surface reuses it.

**AC-shape reference** (model on the story 2.19 mutation-composite patterns):
- **Route + entry:** a typed route (e.g. `/submit`) reachable from the queue shell; the **story 2.20 empty-state CTA** (currently hardcodes "New runs from Linear will appear here once submitted via the CLI") is updated to point at this form.
- **Form fields:** `linearTicketReference` (required, ≤128), `actorIdentity` (required, ≤128), `actorType` (select from the `ActorType` enum), optional `correlationId`; client-side validation mirrors the backend `@NotBlank/@Size` constraints.
- **Mutation hook:** `useSubmitWorkflow` on the generated client; sends an `Idempotency-Key` (UUIDv7, regenerated per distinct submit, reused on retry per the 2.19 idempotency convention); the submit button follows the locked>error>stale>submitting>blocked>disabled>success>ready precedence.
- **Success:** a persistent (non-toast) confirmation showing the new `runId` + state (`Inbox`) with a link to the run detail.
- **Failure:** render ProblemDetails `code`/`detail` (`LINEAR_TICKET_NOT_FOUND`, `IDEMPOTENCY_KEY_CONFLICT`, `MISSING_IDEMPOTENCY_KEY`) via the shared error surface; retry preserves the idempotency key.
- **A11y:** form labels, focus management, keyboard submit — built to the 2.22 infra standards (full audit is the deferred 2.25).
- **Test coverage (vitest/MSW):** happy submit, validation gating, error-code rendering, idempotency-key reuse on retry, empty-state CTA navigates to the form.
- **Logging:** field-only client logs (submitAttempt / submitSuccess / submitError) with no PII (story 2.19 log-PII negative-test convention).

**Note:** Closes the FR1 "UI form" gap left by the FR-coverage table (FR1 → Epic 1 CLI only). Initiation semantics are unchanged — same command, same idempotency, same domain error codes (AR8 shared-command-model equivalence).

---

## Epic 3: Agent Execution, Implementation Output & Developer Review

> **Detailed stories live in **[epic-03-agent-execution.md](./epic-03-agent-execution.md) — file split for size/maintainability.

After spec approval, the workflow dispatches agent work through real Docker runners (Codex + Claude), produces implementation artifacts linked to a GitHub PR reference, and a Developer can inspect the approved spec, implementation plan, and PR artifact — then accept, reject with technical feedback, or take over the run without losing prior context.

---

## Epic 4: Failure Handling, Recovery & Reconciliation (Operator Console + Compare Mode)

> **Detailed stories live in **[epic-04-recovery.md](./epic-04-recovery.md) — file split for size/maintainability.

A Workflow Owner opens the run queue, selects a failed or stalled run, inspects container logs, current failed stage, artifact status, and integration conflict state — then retries, reruns, resumes, reconciles, pauses, or classifies the failure, with every recovery action appended to the same governed history. Reviewers gain Compare Mode for revision-delta inspection. Activates the `recovery_operator` Decision Bar mode with the deeper action set beyond Epic 3's retry baseline.

---

## Epic 5: Shareable Run Export & Team-Visible Review

> **Detailed stories live in **[epic-05-export.md](./epic-05-export.md) — file split for size/maintainability.

A pilot user exports a redacted, shareable run report a teammate can inspect without access to the originating machine. Retention policies govern run history + artifact persistence + ticket-closure behavior. Surfaces pilot-measurement data so pilot teams can answer the PRD's success-criteria questions: cycle time vs baseline, rework rate, adoption fraction.

---

## Epic 6: Adoption Polish & Pilot Documentation

> **Detailed stories live in **[epic-06-pilot-docs.md](./epic-06-pilot-docs.md) — file split for size/maintainability.

A pilot user follows documented setup + happy-path tutorial + failed-run recovery walkthrough + exported-report walkthrough end-to-end without live assistance. Per the documentation-as-incremental-milestone model from refinement R2, each prior epic owns its doc-increment; Epic 6 is the final consolidation + pilot-readiness audit pass — verifies end-to-end stitched flows, tightens concept vocabulary, runs pilot-onboarding dry-runs by 5 named validators, closes NFR42/NFR44/NFR45 gaps, produces a pilot launch readiness checklist + ADR.

---
