## Epic 3d: Per-Step Execution Control, Observability & Manual Execution

A single local-first operator gains finer control over **how each workflow step is executed, reviewed, observed, and retired**. Each project can configure a **reviewer model** so a second LLM reviews a step's output (advisory now, gating-capable per project later); steps can be run through a first-class **manual execution mode** when an agent's unattended/headless auth is unavailable; operators can watch a step's container logs live and after the fact, open a **read-only diagnostic console** into a running runner, see the agent provider's usage/limit status (5-hour/weekly windows) after a run, and soft **hide/archive** obsolete executions when their source ticket is removed. The epic builds on the Epic 3c `Project` aggregate + per-project encrypted credentials + `ProjectConnectorResolver` and the existing runner-contracts / runner-broker seams.

This epic deliberately **narrows (does not remove)** the runner sandbox / governed-access posture for exactly one capability — a read-only diagnostic console attached to a live runner, with every session recorded in governed history. It does **not** introduce write-capable shells, host shells, multi-user authentication, RBAC, or tenant isolation. Obsolete-execution removal is **soft hide/archive only** — append-only audit history (FR47) is preserved; any true purge remains an Epic 5 retention concern. It is **inserted between Epic 3c and Epic 4** and **sequenced after Epic 3c**. Source: `sprint-change-proposal-2026-06-21.md`. FRs covered: FR64–FR69.

**Documentation increment (owned inside Epic 3d):** Epic 3d completion requires a **per-step execution-control walkthrough** doc — configure a reviewer model, run a step manually, view live/finished logs, open the read-only console, read provider limit status, and hide an obsolete execution — shipped alongside the feature stories (story 3d-10).

**Prerequisites:** Epic 3c complete (Project aggregate + per-project credentials + connector resolver). Stories 3.4 (Claude runner auth), 3.6 (runner log reference + secret scan), 3.17 (runner queue), and the Epic 3b `WaitingForReview` loop are the seams this epic extends.

**ADRs:** `docs/adr/0024-manual-execution-mode.md`, `docs/adr/0025-live-observability-and-readonly-console.md` (security-review-gated), `docs/adr/0026-per-step-advisory-reviewer-model.md`, `docs/adr/0027-obsolete-execution-soft-hide.md`.

### Story List (10 stories)

```
Per-Step LLM Review (advisory, gating-capable later)
3d-1   Reviewer-model project config + advisory-verdict schema (Flyway)
3d-2   Reviewer execution + advisory verdict in the WaitingForReview Decision Bar

Manual Execution as a Runner Kind
3d-3   WaitingForManualExecution state + `manual` runner-kind dispatch
3d-4   Manual-artifact submission (UI + CLI) into the same validation/review pipeline

Execution Observability
3d-5   Live + historical step log viewing (REST stream + UI)
3d-6   Read-only diagnostic console into a running runner (security-gated)
3d-7   Post-execution provider usage/limit status — 5h/weekly (spike-gated)

Execution Lifecycle
3d-8   Soft hide/archive obsolete executions

Cross-cutting
3d-9   Foundation-gate widening + test suite extension
3d-10  Per-step execution-control documentation increment
```

---

### Story 3d-1: Reviewer-Model Project Config + Advisory-Verdict Schema (Flyway)

As a backend developer,
I want a per-project (optionally per-stage) reviewer-model binding plus a persisted advisory-verdict record,
So that each project can nominate a second LLM to review step output and the verdict has a durable, gating-capable home.

**Acceptance Criteria:**

1. **Given** the V18+ migration head, **When** the backend starts, **Then** Flyway applies the next free version adding reviewer-model configuration to the project model (a per-project reviewer connector binding, optionally scoped per stage) and a `step_reviews` (advisory-verdict) table — version is the next free number; replay is a no-op.
2. **Given** the reviewer binding, **Then** it reuses the Epic 3c connector/credential model: a reviewer credential is stored via the existing `project_credentials` mechanism (a `reviewer` connector role added to the role CHECK), encrypted at rest (ADR 0013); no new credential subsystem.
3. **Given** `step_reviews`, **Then** it stores `id` (`rev_` public-id prefix registered), `workflow_run_id` FK, `runner_execution_id` FK (the review run), `reviewed_artifact_id` + reviewed artifact version, `outcome text NOT NULL` (CHECK in a `review_outcome` value set, e.g. `pass`/`concern`/`fail`), `rationale text`, `reviewer_model_identity text`, `producer_model_identity text`, `created_at timestamptz NOT NULL DEFAULT now()`, `archived_at timestamptz NULL` (retention-readiness rule).
4. **Given** the gating-capable decision (ADR 0026), **Then** a per-project `reviewer_gating_enabled boolean NOT NULL DEFAULT false` exists so a failing verdict can block progression later **without** schema rework; the flag is **not** read by any gating logic in this epic.
5. **Given** the central registries + drift-test pattern (story 1.4 / 3c-2), **Then** `review_outcome`, the `reviewer` connector role, and the `rev_` prefix are added to the authoritative registries and drift-tested against the DB CHECK + API schema + any frontend allowed-value lists.
6. **Given** new domain error codes as needed (e.g. `REVIEWER_MODEL_NOT_CONFIGURED`), **Then** they follow the DomainErrorCode three-sites rule (ProblemDetailsCatalog + registry-api-schema-placeholders manifest) verified under `-Pfoundation-gate`.
7. **Given** ArchUnit boundaries, **Then** reviewer config + verdict logic lives in `application.project` / domain; no adapter imports leak into the domain.
8. **Given** tests, **Then** coverage asserts migration replay safety, registry/prefix drift, the encrypted reviewer-credential round-trip via the existing store, and that `reviewer_gating_enabled` defaults false and is never consulted by progression logic in this epic.

### Story 3d-2: Reviewer Execution + Advisory Verdict in the WaitingForReview Decision Bar

As a reviewer,
I want a project-configured second LLM to review a step's output and have its verdict shown alongside my decision,
So that I get a governed second opinion without surrendering human approval authority.

**Acceptance Criteria:**

1. **Given** a project with a reviewer-model binding, **When** a step produces an output artifact, **Then** a reviewer runner invocation runs over that artifact, resolved through `ProjectConnectorResolver` using the per-project reviewer credential (decrypted in memory only, never logged) — exactly as automated runners resolve their adapters.
2. **Given** the reviewer run, **Then** a `runner_executions` row is recorded for it (so the second opinion is itself inspectable, per FR53) and a `step_reviews` row persists the structured `outcome` + `rationale` + reviewer/producer model identities.
3. **Given** the advisory contract (ADR 0026, D2), **Then** the verdict is **surfaced** to the human reviewer in the `WaitingForReview` Decision Bar via the Reviewer Verdict Panel; it does **not** auto-approve or auto-reject, and the human decision remains the governing approval — `reviewer_gating_enabled` is not consulted.
4. **Given** provenance, **Then** the verdict explicitly records which model produced the step output and which produced the review, so "reviewed by a different LLM" is verifiable and a same-model self-review is detectable (and surfaced as a warning in the panel).
5. **Given** no reviewer binding on a project, **Then** behavior is byte-identical to pre-3d (no reviewer run, no panel) — the feature is strictly opt-in per project.
6. **Given** a reviewer-run failure (provider error, timeout), **Then** it degrades gracefully: the step is **not** blocked, the panel shows a "review unavailable" state with the reason, and the failure is recorded — a failed second opinion never strands a run.
7. **Given** redaction (story 1.10) + the live-stream posture (ADR 0025), **Then** the reviewer's input/output passes the same redaction guarantees as any runner artifact before persistence/egress.
8. **Given** allowed-actions (story 2.14), **Then** the human approve/reject actions are unchanged; the panel is presentational and does not add a governed action in this epic.
9. **Given** tests, **Then** coverage asserts: reviewer run resolves via the per-project reviewer credential, verdict persisted + surfaced advisory-only, human decision unaffected, no-binding parity, graceful degradation on reviewer failure, same-model self-review flagged, redaction on reviewer egress.

### Story 3d-3: `WaitingForManualExecution` State + `manual` Runner-Kind Dispatch

As a backend developer,
I want a `manual` runner kind that emits the step's context bundle and parks the run in a new `WaitingForManualExecution` state instead of launching a container,
So that an operator can run an agent step by hand when unattended/headless auth is unavailable, through a governed path.

**Acceptance Criteria:**

1. **Given** the runner-kind registry, **Then** `manual` is registered alongside `claude`/`codex` and is selectable per project (and per stage) via the Epic 3c configuration; selecting it changes the producer, **not** the runner-contracts input/output schema (ADR 0024 D1).
2. **Given** the workflow state machine (story 1.5), **Then** a new state `WaitingForManualExecution` is added to the state registry + the `workflow_runs.current_state` CHECK (additive migration, next free Flyway version, replay-safe); valid transitions: a dispatching state → `WaitingForManualExecution`, and `WaitingForManualExecution` → the normal post-step state on submission (story 3d-4).
3. **Given** a step dispatched under the `manual` kind, **When** dispatch runs, **Then** the dispatch path **emits the runner-contracts input bundle** (persisted + retrievable) and transitions the run to `WaitingForManualExecution` **instead of** launching a container or enqueuing container work.
4. **Given** new `WorkflowEventType`s for the manual lifecycle (e.g. `manual.executionRequested`), **Then** each is mirrored into the registry + both fixture sites per the "new WorkflowEventType → two fixture sites" rule, and the OpenAPI enum is regenerated byte-identically.
5. **Given** FR53 (common runner abstraction), **Then** a `runner_executions` row is recorded for the manual step (kind `manual`, no container, status reflecting "awaiting manual") so the manual step is first-class in inspection/observability/lineage.
6. **Given** the queue (story 3.17), **Then** a manual step does not occupy a worker slot — it parks rather than enqueues container work.
7. **Given** allowed-actions (story 2.14), **Then** a `WaitingForManualExecution` run exposes the actions that let an operator obtain the bundle + submit a result (consumed by story 3d-4); a new registry value is added per drift test.
8. **Given** tests, **Then** coverage asserts: `manual` kind parks the run + emits the bundle + records the runner_executions row + appends the event, no container/queue work is started, state-machine transitions in/out are valid, registry/CHECK/fixture/OpenAPI drift all pass, and a default (non-manual) project is byte-identical to pre-3d.

### Story 3d-4: Manual-Artifact Submission (UI + CLI) into the Same Validation/Review Pipeline

As an operator,
I want to download the emitted context bundle, run the agent myself, and submit the resulting artifact,
So that a manually-produced artifact re-enters the same validation and review pipeline as an automated runner's output.

**Acceptance Criteria:**

1. **Given** a run in `WaitingForManualExecution`, **Then** a REST endpoint + CLI command let the operator retrieve the emitted context bundle (redacted per story 3.10 / ADR 0025) for the parked step.
2. **Given** a governed submission endpoint `POST /api/v1/workflows/{workflowRunId}/manual-artifact` (+ CLI equivalent, story 1.7 parity), **Then** it accepts the operator-produced artifact and runs the **same runner-contracts output validation** an automated runner's output would (no validation bypass).
3. **Given** mandatory `Idempotency-Key` + `X-Actor-Identity` headers (stories 1.9 / 2.13), **Then** standard conventions apply; replay with same key+fingerprint replays, different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
4. **Given** a valid submission, **Then** the run transitions out of `WaitingForManualExecution` into the normal post-step state (e.g. `WaitingForReview`), a `manual.artifactSubmitted` event is appended with the operator identity, and the `runner_executions` row is finalized — in one transaction.
5. **Given** an invalid artifact (fails output-contract validation), **Then** a typed Problem Details error is returned and the run stays in `WaitingForManualExecution` (resubmittable); no partial state change.
6. **Given** Problem Details (story 1.8), **Then** typed errors cover at least: `MANUAL_EXECUTION_NOT_APPLICABLE` (run not in `WaitingForManualExecution`), validation failure, `IDEMPOTENCY_KEY_CONFLICT`, `RUN_NOT_FOUND`, `ACTION_NOT_ALLOWED` — contract tests assert `code`+`status`, never human text.
7. **Given** the UI Manual Execution Surface, **Then** for a parked run it offers download/copy of the bundle + a submit affordance (file or paste), shows validation errors inline, and is keyboard-operable with explicit labels (WCAG 2.1 AA; axe zero `wcag2aa` violations).
8. **Given** OpenAPI + drift check (story 1.21), **Then** the new endpoint + DTOs appear in the regenerated snapshot.
9. **Given** tests, **Then** coverage asserts: happy-path submission transitions + appends event + finalizes runner_executions, invalid artifact rejected with run unchanged, idempotent replay + conflict, CLI/REST equivalence, downstream review loop sees the manual artifact identically to an automated one, axe a11y on the UI surface.

### Story 3d-5: Live + Historical Step Log Viewing (REST Stream + UI)

As an operator,
I want to watch a step's container logs while it runs and read them after it finishes,
So that I can follow progress and diagnose behavior without waiting for a post-hoc download.

**Acceptance Criteria:**

1. **Given** a running runner execution, **Then** a backend streaming endpoint (SSE or websocket) follows the container's logs live; **Given** a finished execution, **Then** the endpoint serves the already-persisted post-hoc-redacted log (story 3.6) — the viewer covers both states.
2. **Given** the localhost-only REST posture + ADR 0025, **Then** the live stream is served only over the existing localhost binding to the single local operator; best-effort streaming redaction is applied, but the **authoritative** redaction guarantee remains the persisted post-hoc scan (story 3.6) — documented in the endpoint contract.
3. **Given** no new raw-log store (ADR 0025 D4), **Then** the viewer streams from Docker for the live case and reads the existing persisted redacted log for the finished case; no second raw-log table is introduced.
4. **Given** the UI Step Execution Log Viewer, **Then** it renders within the run-detail view per step, shows live-follow with auto-scroll + a finished/static mode, and announces stream start/end via a live region (color-independent state signifier).
5. **Given** Epic 4 de-dup (proposal Section 4), **Then** this viewer is the surface story 4.4's failure-diagnostics view consumes; no separate redacted-log download surface is built.
6. **Given** allowed-actions (story 2.14), **Then** viewing logs is gated by a backend-reported action; a new registry value is added per drift test.
7. **Given** correlation propagation (story 1.19), **Then** the streaming endpoint emits a structured log line with `correlationId`, the runner-execution id, and stream lifecycle.
8. **Given** tests, **Then** coverage asserts: live stream follows a running execution, finished mode serves the redacted persisted log, the persisted/export path is unchanged by the live view, localhost-only enforcement, allowed-action gating, UI live-region announcement, axe a11y on the viewer.

### Story 3d-6: Read-Only Diagnostic Console into a Running Runner (Security-Gated)

As an operator,
I want a read-only console attached to a running runner container,
So that I can diagnose a stuck or misbehaving step in the moment, without the ability to mutate the run or the workspace.

**Acceptance Criteria:**

1. **Given** ADR 0025 (security-review-gated), **Then** this story does not close until a security review signs off the threat model; sign-off is recorded in the story Completion Notes + PR description (mirrors the ADR 0013 gate).
2. **Given** a **running** runner execution only, **Then** a backend endpoint opens a **read-only**, non-mutating console attached to that container; there is no write to the workspace, no host shell, and no attach to a finished/absent container.
3. **Given** governed history (ADR 0025 D2), **Then** opening a console appends a governed event (operator identity, runner-execution id, open/close timestamps); the session is first-class audit. Console I/O is not durably stored — only session metadata.
4. **Given** allowed-actions (story 2.14), **Then** console access is gated by a backend-reported action available only while a runner execution is live; a new registry value is added per drift test.
5. **Given** the localhost-only posture, **Then** the console is served only over the existing localhost binding to the single local operator; remote/multi-user access remains out of scope.
6. **Given** the UI Read-only Diagnostic Console, **Then** it renders a terminal clearly badged **read-only**, refuses to transmit input that would mutate state (or is purely a streaming pty with input disabled per the read-only design), and is keyboard-operable (WCAG 2.1 AA; axe zero `wcag2aa` violations).
7. **Given** redaction, **Then** the same in-the-moment posture as the live log stream applies (ADR 0025 threat model): export/shareable guarantees are unaffected because nothing the console shows changes persisted/exported content.
8. **Given** tests, **Then** coverage asserts: console attaches only to a live execution (rejected for finished/absent), read-only enforcement (no mutation path), session open/close appends governed events, allowed-action gating, localhost-only enforcement, axe a11y on the UI, and the security-review sign-off is recorded.

### Story 3d-7: Post-Execution Provider Usage/Limit Status — 5h/Weekly (Spike-Gated)

As an operator,
I want to see the agent provider's usage/limit status after a step runs,
So that I can decide between automated and manual execution before exhausting a 5-hour or weekly window.

**Acceptance Criteria:**

1. **Given** the spike gate (ADR/proposal D5), **Then** the story begins with a spike confirming the Claude CLI / Anthropic API and Codex expose 5-hour/weekly window status programmatically in headless mode; the spike outcome is recorded. **If the signal is unavailable**, the feature ships as a documented "not exposed by provider" state rather than a fabricated value, and the UI degrades to that state.
2. **Given** a confirmed signal, **When** a runner execution finishes, **Then** the runner (`runner.mjs`) captures the provider usage/limit status and emits it in the runner-contracts **output metadata** (rides the existing contract; no new mount).
3. **Given** the captured status, **Then** the backend persists a per-credential usage/limit snapshot keyed to the run + credential (so the value is attributable to the account that produced the run), never persisting any secret.
4. **Given** redaction, **Then** the usage/limit payload is asserted to contain no secret material (adversarial fixture); only window/quota numbers + timestamps are stored/surfaced.
5. **Given** the UI Provider Limit Status indicator + the CLI, **Then** after a run they surface the 5-hour and weekly status (or the "not exposed" state) with a color-independent signifier; values are clearly labeled as provider-reported and as-of a timestamp.
6. **Given** observability conventions (when the profile is active), **Then** per-provider limit status may be surfaced consistently with existing local observability, weak-ref-safe for any gauge.
7. **Given** tests, **Then** coverage asserts: spike-confirmed path captures + persists + surfaces the snapshot, the "signal unavailable" path degrades to the documented state, no secret in the payload (fixture), per-credential attribution, and CLI/UI parity.

### Story 3d-8: Soft Hide/Archive Obsolete Executions

As an operator,
I want to hide or archive runs whose source ticket has been removed, without erasing audit history,
So that my queue stays focused on live work while the full record remains inspectable.

**Acceptance Criteria:**

1. **Given** ADR 0027 (archive-not-delete), **Then** retiring an obsolete execution sets `archived_at`/hide markers on the run (additive migration, next free Flyway version, replay-safe) and **never** deletes rows or touches `workflow_events`; append-only history (FR47) is fully preserved.
2. **Given** the cascade scope (a view, not a row-deletion cascade), **Then** hiding a run scopes its related artifacts/runner_executions/integration_links out of default operator views while leaving them durably intact and audit-queryable.
3. **Given** the trigger, **Then** an operator can hide a run explicitly via a REST endpoint + CLI (allowed-action-gated, story 2.14), and the action appends a governed event (who/when/why); **optionally** detection of source-ticket removal via the ticket-source adapter can flag/auto-archive related runs (also as an appended event).
4. **Given** reversibility (ADR 0027 D5), **Then** un-hiding (clearing `archived_at`) is supported, allowed-action-gated, and audited — hide is not a one-way destructive action.
5. **Given** the Run/Review Queue Item, **Then** it gains an archived/hidden state; the queue defaults to hiding archived runs and offers an "include archived" filter; Epic 4's operator queue (story 4.2) honors the same state (de-dup note).
6. **Given** audit queries + inspection, **Then** archived runs remain reachable (e.g. an "include archived" path) so hidden runs are never lost.
7. **Given** the Epic 5 boundary, **Then** true purge/retention of archived runs is explicitly **out of scope** here and remains owned by Epic 5; a comment/doc records the split.
8. **Given** Problem Details + OpenAPI + drift, **Then** the hide/un-hide endpoints + DTOs appear in the regenerated snapshot with typed errors (`RUN_NOT_FOUND`, `ACTION_NOT_ALLOWED`, idempotency).
9. **Given** tests, **Then** coverage asserts: hide sets markers + appends event + leaves events untouched, archived runs leave the default queue but stay audit-queryable, un-hide reverses + audits, "include archived" filter works, optional auto-on-ticket-removal flagging, and no row deletion occurs.

### Story 3d-9: Foundation-Gate Widening + Test Suite Extension

As a backend + frontend developer,
I want the foundation gate and test suites extended to cover Epic 3d,
So that per-step-execution-control regressions are caught at the same CI gates as the rest of the system.

**Acceptance Criteria:**

1. **Given** the foundation gate (story 1.23), **Then** it asserts: the `manual` runner kind + `WaitingForManualExecution` state + new `WorkflowEventType`s + `review_outcome` + `reviewer` connector role + `rev_` prefix are authoritative and drift-tested (registry ↔ DB CHECK ↔ API schema ↔ fixtures), and new domain error codes are registered (three-sites).
2. **Given** the advisory-verdict contract, **Then** a test asserts the reviewer verdict is advisory-only (human decision unaffected; `reviewer_gating_enabled` never consulted in this epic) and that a no-binding project is byte-identical to pre-3d.
3. **Given** the live-stream/console posture (ADR 0025), **Then** tests assert that persisted/exported log content is unchanged by the live view, that the console is read-only + live-only + governed-history-recorded, and that both surfaces are localhost-only.
4. **Given** the soft-hide invariant (ADR 0027), **Then** a test asserts hiding/un-hiding never mutates or deletes `workflow_events` (FR47) and archived runs remain audit-queryable.
5. **Given** the frontend (stories 3d-2/3d-4/3d-5/3d-6/3d-7/3d-8), **Then** Vitest + Playwright + axe coverage is extended for the Reviewer Verdict Panel, Manual Execution Surface, Step Execution Log Viewer, Read-only Console, Provider Limit Status indicator, and queue archived state, under the existing CI tiers.
6. **Given** coverage thresholds (story 2.27 pattern), **Then** thresholds are extended to the new packages (reviewer/manual-execution/console/observability application code) — minimum 80% line coverage; any redaction/secret-adjacent code 90%.
7. **Given** the security gate, **Then** the ADR 0025 security-review sign-off (story 3d-6) is recorded as a gate artifact.
8. **Given** the gate, **Then** "Epic 3d backend + frontend suites green" is required for foundation-gate PRs.

### Story 3d-10: Per-Step Execution-Control Documentation Increment

As an operator joining the pilot,
I want a per-step execution-control walkthrough,
So that I can configure reviewers, run steps manually, observe executions, and retire obsolete runs unaided.

**Acceptance Criteria:**

1. **Given** `docs/per-step-execution-control-walkthrough.md`, **Then** it follows a linear sequence: configure a project reviewer model → run a step manually (download bundle → run agent → submit artifact) → view live + finished step logs → open the read-only diagnostic console → read provider limit status → hide an obsolete execution.
2. **Given** the manual-execution section, **Then** it explains *why* manual mode exists (an agent's unattended/headless auth may be unavailable) and that a manual artifact re-enters the same validation/review pipeline.
3. **Given** the console-safety section, **Then** it states the console is read-only, live-only, governed-history-recorded, and that nothing it shows changes persisted/exported content (ADR 0025 posture), in operator-readable terms.
4. **Given** the soft-hide section, **Then** it explains that hiding never erases audit history (FR47), is reversible, and that true purge is a separate retention concern.
5. **Given** the provider-limit section, **Then** it explains the 5-hour/weekly status, that it is provider-reported and may be unavailable, and how it informs the automated-vs-manual choice.
6. **Given** the glossary (story 1.22) + NFR43, **Then** new concepts (`reviewer model`, `advisory verdict`, `manual execution`, `WaitingForManualExecution`, `diagnostic console`, `archived execution`) are added to `docs/glossary.md`; no concept is introduced without a glossary entry.
7. **Given** the link-check CI step, **Then** all internal links resolve and the doc is visible from `docs/index.md`; the walkthrough is browser-based with no OS-specific instructions.
8. **Given** the epic doc-increment rule, **Then** Epic 3d cannot close without this walkthrough merged + a named human-validator placeholder included.
