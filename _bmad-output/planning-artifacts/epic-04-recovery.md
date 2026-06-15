## Epic 4: Failure Handling, Recovery & Reconciliation (Operator Console + Compare Mode)

A Workflow Owner opens the run queue, selects a failed or stalled run, inspects container logs, current failed stage, artifact status, and integration conflict state — then retries, reruns, resumes, reconciles, pauses, or classifies the failure, with every recovery action appended to the same governed history. Reviewers gain Compare Mode to verify what changed between revisions before approving. Delivers the governed failure taxonomy, full artifact reconciliation for DB/file drift, and integration conflict detection for Linear/GitHub. Activates the `recovery_operator` Decision Bar mode with the deeper action set beyond Epic 3's retry baseline.

### Story List (28 stories)

```
Operator Console & Inspection
4.1   CLI operator inspection — `deliveryline operator status`
4.2   UI operator workflow-owner queue + filters
4.3   Audit history query by ticket and by run (CLI + REST)
4.4   Failure diagnostics deep-dive view

Recovery Service Extensions
4.5   `RecoveryService.resume`
4.6   `RecoveryService.reconcile`
4.7   `RecoveryService.rerunFromStep` (restricted to safe step boundaries)
4.8   `RecoveryService.pause`
4.9   `RecoveryService.classifyFailure` + taxonomy registry management

REST Endpoints (split per user preference)
4.10  REST `resume` endpoint
4.11  REST `reconcile` endpoint
4.12  REST `rerun-from-step` endpoint
4.13  REST `pause` endpoint
4.14  REST `classify-failure` endpoint

Reconciliation (split into detection + repair per user preference)
4.15  Artifact reconciliation — DB/file drift detection
4.16  Artifact reconciliation — operator-driven repair actions
4.16a Artifact lineage reconciliation and fork governance
4.17  Integration conflict detection (Linear + GitHub vs internal state)
4.18  Integration conflict — operator-driven manual reconcile actions

Compare Mode
4.19  Compare Mode backend — revision delta service
4.20  Compare Mode UI component (UX-DR13)
4.21  Compare Mode mobile bounded state (UX-DR23)

UI Operator Mode
4.22  Decision Bar `recovery_operator` mode full activation
4.23  Operator reconciliation dialog UI
4.24  Failure-taxonomy classification UI

Cross-cutting
4.25  CI tier extension — recovery integration tests
4.26  Test suite extension
4.27  Failed-run recovery walkthrough documentation increment
4.28  Architecture lift — remove `RecoveryService` scope-protected lock (ADR)
```

### Story 4.1: CLI Operator Inspection — `deliveryline operator status`

As a Workflow Owner monitoring system health across many runs,
I want `deliveryline operator status [--state failed|stalled|orphaned|takenover|overridden] [--since duration] [--format text|json]` showing all runs in non-happy states across all workflows with diagnostic summaries,
So that I can spot patterns (e.g., 3 runs stuck on the same runner kind in the last hour) without opening each run individually — extending story 1.15's per-run `status` command into a fleet view.

**Acceptance Criteria:**

1. **Given** the `adapters.cli` package, **Then** `OperatorCommands` class registers a Spring Shell command `operator status` — a thin adapter over `WorkflowInspectionService.getOperatorRunSummary(filter)` (new method added in this story to the existing inspection service).
2. **Given** `WorkflowInspectionService.getOperatorRunSummary(OperatorRunFilter) → OperatorRunSummary`, **Then** the typed view returns: `total: int`, `byState: Map<WorkflowState, Integer>` (counts by state matching the filter), `byFailureCategory: Map<FailureCategory, Integer>` (when state filter includes `failed`/`orphaned`), `oldestEntryAt: Instant?` (oldest run matching filter), `runs: List<OperatorRunRow>` — each row carries `runId`, `currentState`, `failureCategory?`, `lastTransitionAt`, `actorIdentity?` (last actor), `linkedTicketRef?`, `linkedPrRef?`, `escalationMarker`, `oldestEventAt`.
3. **Given** filter flags, **Then** `--state` accepts comma-separated values from `WorkflowState` registry (default: `failed,stalled,orphaned`), `--since` accepts duration strings (`1h`, `24h`, `7d` — parsed via Java Duration); `--format=text|json` (default: text); `--limit N` (default 100, max 500 — backpressure against operator command pulling massive result sets).
4. **Given** the text-format output, **Then** it renders a tabular summary header (counts by state, counts by failure category, oldest entry age) followed by per-row tabular details with color coding (red for failures, yellow for stalled, dim for takenover) — non-color signifier (icons + state labels) per story 2.3 AC5 so output remains readable in non-color terminals.
5. **Given** JSON output, **Then** the schema is stable + documented in the CLI reference; field additions are backward-compatible, removals require schema version bump.
6. **Given** performance target, **Then** the query returns within 5 seconds for up to 1000 active runs (NFR26 extended for fleet view) — backed by the indexes added in stories 1.3 + 3.6 + 3.17.
7. **Given** correlation propagation per story 1.19, **Then** the operator command emits a structured log line with `correlationId`, `commandName='operator status'`, filter values, result count, durationMs.
8. **Given** WorkflowInspectionService extension, **Then** the new method is registered in the service's `@Tag` set so allowed-actions endpoint (story 2.14) can later expose `view_operator_status` action when E5+ adds role-based access; in E4 with deferred-RBAC posture, command is invocable by any local user.
9. **Given** ArchUnit (story 1.11), **Then** `OperatorCommands` is a thin adapter calling the inspection service; no business logic.
10. **Given** the test suite, **Then** tests cover: state filter selects only matching runs, since filter applies time window, format=text vs json output structure, limit caps result set, sort order is by `lastTransitionAt DESC`, color coding present in text mode + non-color signifier preserved, performance under 5s for 1000-run fixture, JSON schema stability assertion.

### Story 4.2: UI Operator Workflow-Owner Queue + Filters

As a Workflow Owner using the React UI to triage failed/stalled/orphaned/takenover/overridden runs,
I want a workflow-owner queue view extending story 2.15's `RunReviewQueueItem` with operator-mode filters (state-based, failure-category-based, time-based) and bulk actions placeholder,
So that I can see the same fleet view as `deliveryline operator status` (story 4.1) in the UI — without forcing operators to switch to CLI for routine triage.

**Acceptance Criteria:**

1. **Given** `src/features/workflows/OperatorQueueRoute.tsx`, **Then** a new TanStack Router route `/operator/queue` (typed per story 2.5) renders the operator workflow-owner queue view; backend-reported allowed-actions (story 2.14) gate access via the `view_operator_queue` action (added to registry per drift test) — when not allowed, route renders an empty-state explaining permission posture (audit-only roles per story 2.25 AC8 + UX-DR21).
2. **Given** the queue uses `RunReviewQueueItem` from story 2.15 with `variant='operator'` (already declared in story 2.15 AC4), **Then** the operator variant displays additional metadata in the row: failure category (when applicable), runner kind, escalation marker prominence, last operator action timestamp — these augment without overwhelming the standard row anatomy.
3. **Given** filter UI components, **Then** a filter sidebar exposes: state checkboxes (failed / stalled / orphaned / takenover / overridden — multi-select), failure-category checkboxes (from `FailureCategory` registry — multi-select), time-window selector (last 1h / 24h / 7d / 30d / all), runner-kind filter (codex / claude / mock — multi-select). Selecting filters re-runs the underlying `useOperatorRunsList(filters)` query; results update live.
4. **Given** the empty-state per story 2.20 (queue shell states), **Then** the operator queue surfaces `loading` / `empty` (no runs match filters) / `filtered-empty` (filters narrowed to nothing) / `error` states using the same primitives.
5. **Given** queue depth + pagination, **Then** the list is virtualized (windowed rendering) for >100 items; results are paginated server-side with cursor-based pagination — UI loads more as the operator scrolls.
6. **Given** bulk-actions placeholder, **Then** the UI includes a "Select multiple" checkbox column + a disabled "Bulk action" dropdown with text "Bulk operator actions arrive in a future release" — sets expectation without committing scope; a future story will activate it.
7. **Given** keyboard accessibility per story 2.25, **Then** the filter sidebar + queue list + pagination controls are fully keyboard-operable; focus order matches visual order.
8. **Given** ARIA per story 2.25 + announcement vocabulary, **Then** filter changes trigger an ARIA live region announcement ("Filtered to {N} runs in state failed").
9. **Given** correlation per story 1.19, **Then** the operator queue's API calls carry `correlationId` so backend log searches can find the originating UI session.
10. **Given** component test coverage, **Then** tests cover: route renders when allowed, redirects to empty-state when not allowed, filters apply correctly, virtualization works at >100 items, pagination loads more on scroll, bulk-actions placeholder visible but disabled, ARIA live region announces filter changes, axe-core a11y zero violations.

### Story 4.3: Audit History Query by Ticket and by Run — CLI + REST

As a Workflow Owner investigating an audit question ("show me everything that happened to LIN-123 across all its workflow runs"),
I want `deliveryline audit query --ticket LIN-123` (CLI) + `GET /api/v1/audit/by-ticket/{ticketRef}` + `GET /api/v1/audit/by-run/{workflowRunId}` (REST) returning a flat queryable event stream,
So that FR29 (workflow owners can query audit history by ticket and by run) is wired with a CLI + REST surface — supporting compliance reviews, post-incident investigations, and pattern analysis.

**Acceptance Criteria:**

1. **Given** `AuditQueryService.queryByTicket(ticketRef, filters) → AuditQueryResult` and `queryByRun(workflowRunId, filters) → AuditQueryResult`, **Then** the typed result returns: `events: List<AuditEventRow>` (each row: `eventId`, `eventType`, `workflowRunId`, `actorIdentity`, `actorType`, `timestamp`, `priorState?`, `resultingState?`, `failureCategory?`, `reason?`, `correlationId`, `linkedArtifactId?`), `totalCount`, `nextCursor?` (for pagination).
2. **Given** filter flags on both methods, **Then** they accept: `--event-type` (one or more registry values, multi-select), `--actor` (specific actor identity), `--since` + `--until` (time window), `--limit` + `--cursor` (pagination); Invalid filter values return `INVALID_AUDIT_FILTER` with details.
3. **Given** ticket-scope query, **Then** `queryByTicket` joins all workflow runs linked to the ticket (via `integration_links` of type `linear_ticket`) and returns events from all of them in `timestamp DESC` order — supporting "show me all governed runs for this ticket including any retried runs" use case.
4. **Given** CLI `deliveryline audit query --ticket LIN-123 [--event-type ...] [--since 7d] [--format text|json]`, **Then** the command invokes `queryByTicket`, prints tabular text or stable-schema JSON; non-zero exit if filters reject query.
5. **Given** CLI `deliveryline audit query --run run_abc [--event-type ...] [--format text|json]`, **Then** invokes `queryByRun`, same output formats — `--run` and `--ticket` are mutually exclusive (enforced by Spring Shell parameter validation).
6. **Given** REST `GET /api/v1/audit/by-ticket/{ticketRef}` and `GET /api/v1/audit/by-run/{workflowRunId}`, **Then** they accept the same filter query params (`?eventType=`, `?since=`, `?until=`, `?limit=`, `?cursor=`), return 200 with `AuditQueryResult` JSON, idempotent reads (no Idempotency-Key); OpenAPI documented.
7. **Given** classification + redaction per story 1.10, **Then** any `reason` / `details` text in an audit event passes through `RedactionPolicyService` before output — event content classified `shareable-redacted` is included; `local-only` events (rare — e.g., raw runner logs referenced from event metadata) trigger a documented opacity ("[CONTENT REDACTED — see correlationId in local logs]") in the output.
8. **Given** performance, **Then** queries return within 5 seconds for runs/tickets with up to 200 events (NFR26 + NFR27 extended); cursor-paginated for larger result sets — backed by `idx_workflow_events_workflow_run_id_created_at` from story 1.3 + a new `idx_workflow_events_correlation` (added by Flyway V7 in this story).
9. **Given** Flyway V7 migration `V7__add_audit_query_indexes.sql`, **Then** it adds: `idx_workflow_events_correlation_id` (for correlation-based lookup if a future filter needs it), `idx_workflow_events_event_type_created_at` (for event-type-filtered queries) — replay safety asserted; index creation is `CONCURRENTLY` to avoid lock contention on production-sized tables.
10. **Given** correlation propagation, **Then** every CLI/REST audit query carries the originating `correlationId` in its own log line — operators can audit who queried what, when.
11. **Given** the test suite, **Then** tests cover: query-by-ticket joins multiple runs, query-by-run scopes correctly, filters narrow result set, time-window respected, cursor-pagination preserves stable ordering, mutual exclusion of `--ticket` + `--run`, redaction of sensitive event content, performance under 5s for 200-event fixture, JSON schema stability, V7 migration replay safety, ArchUnit boundary (audit query is a read-only path).

### Story 4.4: Failure Diagnostics Deep-Dive View

As a Workflow Owner clicking into a failed/stalled/orphaned run from story 4.2's operator queue or story 4.1's CLI,
I want a deep-dive diagnostics view (CLI + UI) showing: full failure context, correlation ID, runner logs link, last successful step, last good state, integration sync status, and recommended recovery actions ranked by safety,
So that NFR3 (failed/stalled run must expose failed stage, last successful stage, failure category, last activity time, next safe action) is fully realized — and operators don't need to read raw logs first per NFR7.

**Acceptance Criteria:**

1. **Given** `WorkflowInspectionService.getFailureDiagnostics(workflowRunId) → FailureDiagnostics`, **Then** the typed view returns: `currentState`, `failedStage`, `lastSuccessfulStage`, `failureCategory`, `failureReason` (free-form, redacted), `failureTimestamp`, `lastActivityTimestamp`, `correlationId` (last command's correlation), `runnerLogReference?` (from story 3.6 AC7), `integrationSyncStatus: { linear: SyncStatus, github: SyncStatus }` (last-sync-at + sync-status per integration link), `lastGoodState`, `currentBlockingReason`, `recommendedRecoveryActions: List<RecommendedAction>` (ranked by safety: each carries `actionType`, `safetyLevel: 'safe'|'caution'|'risky'`, `reason`, `precondition`).
2. **Given** safety ranking logic, **Then** recommendations are derived from the workflow state + failure category + integration sync status: e.g., `runner_timeout` → `retry` is `safe` if the workspace is intact + no integration drift; `runner_contract_violation` → `retry` is `risky` (likely to fail again) + `pause` is `safe`; integration conflict → `reconcile` is `safe` + other actions are `caution` until conflict resolved.
3. **Given** CLI `deliveryline operator diagnose {runId} [--format text|json]`, **Then** the command invokes `getFailureDiagnostics`, prints a structured diagnostic report — text mode uses sectioned output with color coding (red for failure, yellow for caution, green for safe), JSON mode is stable-schema for scripting.
4. **Given** the UI deep-dive view, **Then** clicking a failed run from story 4.2's operator queue navigates to a detail route extending the `WorkflowDetailRoute` from story 2.5 with an operator-diagnostic panel — shows the same `FailureDiagnostics` typed view in a structured layout: failure summary at top (state-error treatment from story 2.3), recommended actions ranked by safety with one-click invocation buttons (gated by allowed-actions per story 2.14), expandable sections for runner logs reference, integration sync status, last good state.
5. **Given** runner-log link per story 3.6 AC7, **Then** the diagnostic view exposes a "Download redacted runner log" link calling a backend endpoint `GET /api/v1/runner-executions/{rexId}/logs/download` (added in this story) — returns the redacted log file as `text/plain` attachment with classification asserted as `local-only` or `shareable-redacted` (never raw); operator's download is logged in audit history (`audit.log_downloaded` event).
6. **Given** integration-sync-status display, **Then** Linear + GitHub sync statuses come from `integration_links.last_sync_at` + `sync_status` (per stories 1.14 + 3.15) — drifted/conflicted statuses are visually flagged, opening a tooltip or modal explaining the drift (uses story 4.17 reconciliation dialog pattern).
7. **Given** NFR7 "reviewer must be able to answer what happened, what changed, who acted, what failed, and what is next from the inspection view without reading raw agent logs first", **Then** the structured view answers all five questions at the top of the panel without requiring the operator to expand the runner-log section — tests assert all five question-fields are present and non-empty for a fixture failed run.
8. **Given** correlation propagation, **Then** the `correlationId` is prominently displayed (with copy button) so the operator can grep ELK (story 3.7) or `journalctl` for the full log trail.
9. **Given** ArchUnit (story 1.11), **Then** `getFailureDiagnostics` lives in `WorkflowInspectionService`; the recommended-action ranking logic is in `application.recovery.RecommendationService` — no logic in adapters.
10. **Given** component + integration test coverage, **Then** tests cover: each failure-category type produces a distinct safety-ranking pattern, runner-log download endpoint returns redacted content + appends audit event, integration-sync-status drift visually flagged, NFR7 five-questions assertion, copy-correlationId UX works in UI, axe-core a11y zero violations on UI panel.

### Story 4.5: `RecoveryService.resume` — Recovery from Paused State

As a Workflow Owner who paused a run for investigation (story 4.8) and now wants to continue,
I want `RecoveryService.resume(workflowRunId, actor, idempotencyKey)` that transitions the run from `Paused` back to its prior executing state, re-enqueues runner work via story 3.17, and preserves the pause history for audit,
So that NFR5 (pause when uncertain, require explicit human recovery) is fully wired with a typed resume path — operators can pause + resume rather than only retry.

**Acceptance Criteria:**

1. **Given** the `application.recovery` package extension to story 1.18's `RecoveryService` baseline, **Then** `resume(command: ResumeWorkflowCommand) → RecoveryResult` is added; per the architecture lift in story 4.28, this is no longer scope-protected.
2. **Given** `ResumeWorkflowCommand`, **Then** carries: `workflowRunId`, `actorIdentity`, `actorType=human`, `reviewerRole='workflow_owner'` (deferred-RBAC: audit-only label), optional `reasonText`, `idempotencyKey`.
3. **Given** the workflow state machine from story 1.5 + story 3.22 (`TakenOver` valid), **Then** `resume` is valid only when current state is `Paused`; from any other state it raises `RESUME_NOT_APPLICABLE` with `details.currentState`.
4. **Given** the `recovery_actions` table, **Then** a resume row is inserted: `action_type='resume'`, `triggering_event_id` (the prior `workflow.paused` event), `actor_*`, `reasonText`, `idempotency_key`, `result_status`.
5. **Given** state transition, **Then** `WorkflowTransitionService.transition(workflowRunId, targetState=priorExecutingState, reason='resumed_by_operator')` is invoked — the prior executing state (e.g., `Investigating` / `Executing`) is read from the most recent `workflow.paused` event's `details.priorState`.
6. **Given** runner re-enqueue, **When** the prior executing state requires runner work (e.g., resuming into `Investigating` re-dispatches the spec runner), **Then** `WorkflowOrchestrationService.dispatch{Stage}Generation(workflowRunId)` is invoked — work flows through the queue (story 3.17) just like initial dispatch.
7. **Given** the `workflow.resumed` event is appended in the same transaction as the state change, **Then** the audit trail preserves who resumed and when; FR47 (append-only history) holds.
8. **Given** idempotency, **Then** retries with same key + fingerprint replay; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
9. **Given** allowed-actions integration (story 2.14), **Then** when state=`Paused` + role allows operator actions, `resume` appears in allowed-actions list; new `AllowedAction` registry value `resume_workflow` added per drift test.
10. **Given** the test suite, **Then** tests cover: resume from `Paused` transitions to prior executing state + appends `workflow.resumed` event + re-enqueues runner work, resume from non-`Paused` state raises `RESUME_NOT_APPLICABLE`, idempotent replay, idempotency-conflict, allowed-actions integration.

### Story 4.6: `RecoveryService.reconcile` — Reconcile Workflow State on Integration Conflict

As a Workflow Owner facing a detected integration conflict (e.g., GitHub PR merged externally while workflow expected it open),
I want `RecoveryService.reconcile(workflowRunId, conflictId, resolutionDecision, actor, idempotencyKey)` that applies an explicit resolution to a detected conflict — never silent overwrite per NFR19,
So that FR35 (workflow owners can reconcile workflow state when integration conflict is detected) is wired with a typed reconcile path that requires explicit decision input from the operator.

**Acceptance Criteria:**

1. **Given** the `application.recovery` package extension, **Then** `reconcile(command: ReconcileWorkflowCommand) → RecoveryResult` is added.
2. **Given** `ReconcileWorkflowCommand`, **Then** carries: `workflowRunId`, `conflictId` (typed reference to a row in the new `integration_conflicts` table from story 4.17), `resolutionDecision: ReconciliationDecision` (enum: `accept_external_state` / `accept_internal_state` / `mark_completed_externally` / `mark_failed_externally` — registry value), `actorIdentity`, `actorType=human`, `reviewerRole='workflow_owner'`, `reasonText` (required — explains why this resolution chosen), `idempotencyKey`.
3. **Given** NFR19 no silent overwrite, **Then** `reconcile` requires the operator to explicitly choose a `ReconciliationDecision` — there is no default; missing field returns `MISSING_RECONCILIATION_DECISION`; invalid values return `INVALID_RECONCILIATION_DECISION`.
4. **Given** the `recovery_actions` table, **Then** a reconcile row is inserted: `action_type='reconcile'`, `triggering_event_id` (the `integration.conflictDetected` event from story 4.17), `actor_*`, `reasonText`, `idempotency_key`, `result_status`; the row's `details` JSONB column carries the chosen `resolutionDecision` for audit reconstruction.
5. **Given** the resolution decision, **Then** the service applies the corresponding internal/external state change: e.g., `accept_external_state` updates the `integration_links` row's `external_metadata` to match the queried external state and triggers a state transition aligned with the external state (e.g., GitHub PR merged → workflow → `Completed`); `accept_internal_state` re-attempts to push the internal state to the external system (idempotent push).
6. **Given** state transitions are atomic with the recovery action, **Then** the `workflow.reconciled` event + the resulting state transition + the `recovery_actions` row are all in one transaction.
7. **Given** the conflict row in `integration_conflicts` (story 4.17), **Then** after successful reconcile, the conflict row's `resolved_at` + `resolved_by_action_id` are set; future queries for unresolved conflicts no longer surface this row.
8. **Given** idempotency, **Then** retries with same key + fingerprint replay the prior reconcile; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
9. **Given** allowed-actions integration, **Then** when an unresolved conflict exists for the run, `reconcile_conflict` appears in allowed-actions list; new registry value added per drift test.
10. **Given** the test suite, **Then** tests cover: reconcile with `accept_external_state` updates internal state + appends event, reconcile with `accept_internal_state` re-pushes to external + appends event, missing decision rejected, invalid decision rejected, conflict row marked resolved, idempotent replay, allowed-actions integration.

### Story 4.7: `RecoveryService.rerunFromStep` — Restricted Rerun from Safe Step Boundaries

As a Workflow Owner who needs to rerun from an earlier safe step (e.g., re-spec because the original spec missed scope, or re-execute the implementation after fixing a runner config),
I want `RecoveryService.rerunFromStep(workflowRunId, targetStep, actor, idempotencyKey)` **restricted to safe step boundaries** — `Investigating` (re-spec), `Executing` (re-implement), and operator-flagged custom checkpoints only,
So that operators have a deeper-than-retry rerun path without the unbounded "rerun from arbitrary step" risk that could re-execute already-approved decisions or re-create artifacts the team relied on.

**Acceptance Criteria:**

1. **Given** the `application.recovery` package extension, **Then** `rerunFromStep(command: RerunFromStepCommand) → RecoveryResult` is added.
2. **Given** `RerunFromStepCommand`, **Then** carries: `workflowRunId`, `targetStep: SafeRerunStep` (enum constrained to `investigating` / `executing` — registry value), `actorIdentity`, `actorType`, `reviewerRole='workflow_owner'`, `reasonText` (required — explains why this rerun is needed), `idempotencyKey`.
3. **Given** the **safe step boundary restriction per user confirmation**, **Then** `targetStep` is constrained to `SafeRerunStep` enum (`investigating` / `executing`) — no other values accepted; `INVALID_RERUN_TARGET_STEP` raised on out-of-range values; documented in `docs/adr/0009-rerun-safe-boundaries.md` with rationale (prevents rerunning from `Inbox` which would lose the entire run, or from `WaitingForSpecApproval` which would lose the PM's prior approval).
4. **Given** state-transition validity, **Then** `rerunFromStep` is allowed from any non-terminal state (per story 1.5 AC2 — `*→TakenOver` and `*→Reconciled` patterns extend here as `*→{targetStep}` for rerun).
5. **Given** the rerun semantics, **Then** the workflow transitions to `targetStep` state, the prior artifacts at and beyond that step are marked superseded (NOT deleted — preserved for audit), the runner is re-enqueued via story 3.17 with a fresh runner-execution ID + fresh context bundle version, and a `workflow.rerunFromStep` event is appended with `details.targetStep` + `details.supersededArtifactIds`.
6. **Given** the `recovery_actions` table, **Then** a rerun row is inserted: `action_type='rerun_from_step'`, `triggering_event_id`, `actor_*`, `reasonText`, `idempotency_key`, `result_status`, `details` JSONB carrying `targetStep` + `supersededArtifactIds`.
7. **Given** approval supersession per story 2.9 AC5 invalidation rule, **When** rerun-to-`Investigating` is invoked after a spec was approved, **Then** the prior spec approval is marked invalidated (`approvals.invalidated_at + invalidated_reason='superseded_by_rerun_from_step'` — column added by Flyway V8 in this story); future approvals require re-approval of the new spec version. PM is notified via Linear completion sync's reverse path (`linear.runReopenedNotification` event — auto-comment to source ticket if Linear adapter supports it).
8. **Given** idempotency, **Then** retries with same key + fingerprint replay; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
9. **Given** Flyway V8 migration `V8__add_approval_invalidation.sql`, **Then** adds `invalidated_at timestamptz NULL` + `invalidated_reason text NULL` to `approvals` table; replay safety asserted; existing approvals get `NULL` for both columns (default).
10. **Given** allowed-actions integration, **Then** when state allows rerun (per AC4), `rerun_from_step` appears in allowed-actions list with allowed `targetStep` values surfaced as a sub-list (so the UI can offer a dropdown); new registry value added per drift test.
11. **Given** the test suite, **Then** tests cover: rerun-to-`investigating` from `Failed` transitions correctly + supersedes prior artifacts + invalidates prior spec approval + re-enqueues runner, rerun-to-`executing` from `WaitingForReview` transitions correctly + supersedes prior PR-output + invalidates prior plan approval, invalid target step rejected, V8 migration replay safety, prior approval invalidation visible in inspection (`getCurrentApprovedSpec` returns null until re-approval), allowed-actions integration with target-step sub-list, idempotent replay.

### Story 4.8: `RecoveryService.pause` — Manual Pause for Operator Intervention

As a Workflow Owner who needs to halt a run mid-flight to investigate something (e.g., suspected runner config issue, integration sync drift, or external dependency outage),
I want `RecoveryService.pause(workflowRunId, actor, idempotencyKey)` that transitions the run to `Paused` state, cancels in-flight runner work without erasing it, and preserves the prior executing state for later resume (story 4.5),
So that NFR5 (pause when uncertain) is fully wired with a typed manual pause path — operators don't have to take over (irreversible) just to investigate.

**Acceptance Criteria:**

1. **Given** the `application.recovery` package extension, **Then** `pause(command: PauseWorkflowCommand) → RecoveryResult` is added.
2. **Given** `PauseWorkflowCommand`, **Then** carries: `workflowRunId`, `actorIdentity`, `actorType=human`, `reviewerRole='workflow_owner'`, `reasonText` (required), `idempotencyKey`.
3. **Given** the workflow state machine from story 1.5, **Then** `pause` is valid from any non-terminal, non-`Paused`, non-`TakenOver` state; from terminal states (`Completed`) or `TakenOver` it raises `PAUSE_NOT_APPLICABLE` with `details.currentState`.
4. **Given** in-flight runner work, **When** `pause` is invoked, **Then** any in-flight `runner_executions` rows for the workflow run: (a) gracefully cancelled via `docker stop` + status transition to `cancelled_for_pause` (added to `RunnerExecutionStatus` registry alongside story 3.22's `cancelled_for_takeover`), (b) any queued executions for this run (story 3.17 status='queued') removed from queue + marked `cancelled_for_pause` — preventing the orchestrator from picking up new work after pause.
5. **Given** prior executing state preservation, **Then** the `workflow.paused` event's `details.priorState` carries the state the run was in just before pause — used by `resume` (story 4.5) to know where to transition back to.
6. **Given** the `recovery_actions` table, **Then** a pause row is inserted: `action_type='pause'`, `triggering_event_id` (the prior event), `actor_*`, `reasonText`, `idempotency_key`, `result_status`.
7. **Given** state transition, **Then** `WorkflowTransitionService.transition(workflowRunId, targetState='Paused', reason='paused_by_operator', priorState=currentState)` is invoked — Paused is already a valid state per story 1.5 AC1.
8. **Given** integration link freshness, **When** paused, **Then** integration sync polls (Linear / GitHub) continue at their normal cadence — pausing the workflow doesn't pause integration awareness; this lets operators see new external events arrive (e.g., GitHub PR merged) and decide whether to reconcile (story 4.6) before resuming.
9. **Given** idempotency, **Then** retries with same key + fingerprint replay; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
10. **Given** allowed-actions integration, **Then** when state allows pause (per AC3), `pause_workflow` appears in allowed-actions list; new registry value added per drift test.
11. **Given** the test suite, **Then** tests cover: pause from `Executing` transitions to `Paused` + cancels in-flight + queued runner executions + appends event with priorState=Executing, pause from `WaitingForReview` transitions correctly with priorState preserved, pause from terminal states rejected, pause from `TakenOver` rejected, prior state correctly preserved for later resume, integration polls continue while paused, idempotent replay, allowed-actions integration.

### Story 4.9: `RecoveryService.classifyFailure` + Failure Taxonomy Registry Management

As a Workflow Owner classifying a failed run for cross-run pattern analysis (Growth-stage analytics per PRD § Growth Features),
I want `RecoveryService.classifyFailure(workflowRunId, taxonomyValue, actor, idempotencyKey)` that applies a governed failure-taxonomy classification + governance over the taxonomy registry itself (additions, deprecations, semantic-stability rules),
So that FR37 + FR38 (workflow owners can apply + review a governed failure taxonomy) are wired and NFR33 (failure-taxonomy values used on historical runs remain interpretable if the taxonomy changes later) is upheld.

**Acceptance Criteria:**

1. **Given** the `application.recovery` package extension, **Then** `classifyFailure(command: ClassifyFailureCommand) → RecoveryResult` is added.
2. **Given** `ClassifyFailureCommand`, **Then** carries: `workflowRunId`, `taxonomyValue: FailureTaxonomyValue` (registry-constrained — see AC5), `actorIdentity`, `actorType`, `reviewerRole='workflow_owner'`, `reasonText` (optional — explains the classification), `idempotencyKey`.
3. **Given** classifyFailure is valid only on runs in terminal-failure states (`Failed` or runs that completed but were classified post-hoc by operator decision), **Then** invalid states return `CLASSIFY_NOT_APPLICABLE`.
4. **Given** the `workflow_runs` table extension, **Then** Flyway V9 migration `V9__add_failure_classification.sql` adds: `failure_classification text NULL` (CHECK against the taxonomy registry — see AC5), `failure_classified_at timestamptz NULL`, `failure_classified_by text NULL` (actor identity); replay safety asserted.
5. **Given** the `FailureTaxonomyValue` registry (added to central registries per story 1.4 drift test), **Then** the canonical taxonomy values mirror the PRD § Technical Success initial taxonomy: `specification_gap`, `context_gap`, `agent_execution_failure`, `review_rejection`, `integration_or_merge_failure`, `tooling_or_infrastructure_failure`. Additions to the registry require an ADR per story 4.28's lift criteria.
6. **Given** NFR33 taxonomy stability, **Then** historical runs' `failure_classification` values must remain interpretable even when the taxonomy evolves: deprecated values are NEVER removed from the registry (only marked `deprecated=true` with a `deprecatedReplacementValue` field — supports gentle migration); reading code falls back to the deprecated label with a "(deprecated)" affix in human-readable output. A registry-stability test asserts no value is ever hard-removed.
7. **Given** the `recovery_actions` table, **Then** a classify row is inserted: `action_type='classify_failure'`, `triggering_event_id` (the original failure event), `actor_*`, `reasonText`, `idempotency_key`, `result_status`, `details` JSONB carrying `taxonomyValue`.
8. **Given** the `workflow.failureClassified` event is appended in the same transaction as the row + `workflow_runs` column update, **Then** audit trail preserves attribution + timestamp.
9. **Given** classification can be re-applied (operator changes their mind), **When** a new classification is applied, **Then** the prior `failure_classification` is overwritten on `workflow_runs` BUT a new `workflow.failureClassified` event is appended (preserving prior classification in event history per FR47); inspection methods surface "classified as X (previously Y at timestamp Z)" so audit reconstructive history is intact.
10. **Given** classify is a metadata operation (no state transition, no runner re-dispatch, no integration side-effect), **Then** it is the lightest-weight recovery action; safety-ranking from story 4.4 AC2 always rates `classify_failure` as `safe`.
11. **Given** allowed-actions integration, **Then** when run is in a terminal-failure state, `classify_failure` appears in allowed-actions list; the list response includes the available `taxonomyValue` enum so the UI Decision Bar in operator mode (story 4.22) can render a dropdown; new registry value added per drift test.
12. **Given** the test suite, **Then** tests cover: classifyFailure on `Failed` run sets the column + appends event, classifyFailure on non-failure state rejected, classification can be re-applied (column overwritten + new event appended, prior preserved in event log), V9 migration replay safety, deprecated taxonomy values still readable + tagged `(deprecated)` in output, registry-stability test (no values can be hard-removed), allowed-actions integration with taxonomy enum surfaced.

### Story 4.10: REST Endpoint — `resume` + OpenAPI

As a frontend developer (Decision Bar `recovery_operator` mode in story 4.22) and CLI user,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/resume` wired to `RecoveryService.resume` (story 4.5),
So that resume initiation flows through the same idempotency + Problem Details + OpenAPI conventions as the other mutation endpoints.

**Acceptance Criteria:**

1. **Given** `WorkflowController` (extended from story 3.23–3.25), **Then** new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/resume` — kebab-case action.
2. **Given** request body, **Then** typed DTO `ResumeWorkflowRequest { reasonText? }` in camelCase JSON.
3. **Given** mandatory `Idempotency-Key` header (story 1.9) + `X-Actor-Identity` header (story 2.13 AC4), **Then** standard conventions apply.
4. **Given** Problem Details mapping (story 1.8), **Then** typed errors cover: `RESUME_NOT_APPLICABLE` (409 — current state not `Paused`), `IDEMPOTENCY_KEY_CONFLICT` (409), `ILLEGAL_TRANSITION` (409), `ACTION_NOT_ALLOWED` (409), `RUN_NOT_FOUND` (404) — contract tests check `code` + `status` + `details`, never human text.
5. **Given** OpenAPI via `springdoc-openapi`, **Then** endpoint appears in regenerated OpenAPI snapshot; CI drift check (story 1.21 AC6) passes.
6. **Given** CLI/REST equivalence (story 1.7 AC5), **Then** Spring Shell command `deliveryline operator resume --run {runId} [--reason "..."] [--idempotency-key K]` added under `adapters.cli` with the operator subcommand grouping; contract test asserts CLI/REST identical outcomes.
7. **Given** ArchUnit (story 1.11), **Then** controller method does only request parsing, command construction, service invocation, response mapping — no business logic.
8. **Given** the response, **Then** success returns 200 OK with typed result DTO carrying new state (the prior executing state from story 4.5 AC5), recorded `recovery_actions.id` (`rcv_` prefix), runner re-enqueue confirmation (`runnerExecutionId` if re-dispatched), stamped `correlationId`; `X-Correlation-Id` response header echoes request correlation ID.
9. **Given** the test suite, **Then** covers: happy-path resume returns 200 + state at prior executing state + recovery_actions row + re-enqueued runner, resume from non-`Paused` state returns 409 with `RESUME_NOT_APPLICABLE`, missing X-Actor-Identity falls back to local-user, idempotent replay, action-not-allowed when state forbids.

### Story 4.11: REST Endpoint — `reconcile` + OpenAPI

As a frontend developer (operator reconciliation dialog UI in story 4.23) and CLI user,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/reconcile` wired to `RecoveryService.reconcile` (story 4.6),
So that operator reconciliation decisions flow through the standard mutation conventions with explicit `ReconciliationDecision` enforcement (NFR19 no silent overwrite).

**Acceptance Criteria:**

1. **Given** `WorkflowController`, **Then** new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/reconcile` — kebab-case action.
2. **Given** request body, **Then** typed DTO `ReconcileWorkflowRequest { conflictId, resolutionDecision, reasonText }` in camelCase JSON; all three fields required.
3. **Given** mandatory `Idempotency-Key` + `X-Actor-Identity` headers, **Then** standard conventions apply.
4. **Given** Problem Details errors, **Then** typed errors cover: `MISSING_RECONCILIATION_DECISION` (400), `INVALID_RECONCILIATION_DECISION` (400 — value not in enum), `CONFLICT_NOT_FOUND` (404 — `conflictId` does not match an unresolved conflict), `CONFLICT_ALREADY_RESOLVED` (409 — conflict was already reconciled by another action), `IDEMPOTENCY_KEY_CONFLICT` (409), `RUN_NOT_FOUND` (404), `ACTION_NOT_ALLOWED` (409).
5. **Given** OpenAPI + drift check, **Then** endpoint + new error codes + `ReconciliationDecision` enum schema appear in regenerated OpenAPI snapshot.
6. **Given** CLI/REST equivalence, **Then** Spring Shell command `deliveryline operator reconcile --run {runId} --conflict {conflictId} --decision {accept_external_state|accept_internal_state|mark_completed_externally|mark_failed_externally} --reason "..." [--idempotency-key K]` added; contract test asserts identical outcomes.
7. **Given** ArchUnit (story 1.11), **Then** controller method is thin — no business logic.
8. **Given** the response, **Then** success returns 200 OK with typed result DTO carrying: new state (post-reconcile), recorded `recovery_actions.id`, `integration_links.{id, external_metadata}` reflecting the reconciled state, the resolved `integration_conflicts.{id, resolved_at, resolved_by_action_id}`.
9. **Given** the test suite, **Then** covers: happy-path reconcile with each `ReconciliationDecision` value, missing decision rejected, invalid decision rejected, conflict-not-found, conflict-already-resolved, idempotent replay, internal-state-changed-correctly assertion per decision type.

### Story 4.12: REST Endpoint — `rerun-from-step` + OpenAPI

As a frontend developer (Decision Bar `recovery_operator` mode in story 4.22) and CLI user,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/rerun-from-step` wired to `RecoveryService.rerunFromStep` (story 4.7) **restricted to safe step boundaries**,
So that rerun-from-step initiation flows through standard mutation conventions with explicit `targetStep` validation against the safe-boundary enum.

**Acceptance Criteria:**

1. **Given** `WorkflowController`, **Then** new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/rerun-from-step` — kebab-case action.
2. **Given** request body, **Then** typed DTO `RerunFromStepRequest { targetStep, reasonText }`; both required.
3. **Given** mandatory `Idempotency-Key` + `X-Actor-Identity` headers, **Then** standard conventions apply.
4. **Given** Problem Details errors, **Then** typed errors cover: `INVALID_RERUN_TARGET_STEP` (400 — value not in `SafeRerunStep` enum from story 4.7 AC3), `MISSING_REASON_TEXT` (400), `IDEMPOTENCY_KEY_CONFLICT` (409), `ILLEGAL_TRANSITION` (409 — e.g., from a terminal state), `RUN_NOT_FOUND` (404), `ACTION_NOT_ALLOWED` (409).
5. **Given** OpenAPI + drift check, **Then** endpoint + `SafeRerunStep` enum schema appear in regenerated snapshot — UI dropdown (story 4.22) consumes the typed enum.
6. **Given** CLI/REST equivalence, **Then** Spring Shell command `deliveryline operator rerun-from-step --run {runId} --target {investigating|executing} --reason "..." [--idempotency-key K]` added; contract test asserts identical outcomes.
7. **Given** ArchUnit (story 1.11), **Then** controller method is thin.
8. **Given** the response, **Then** success returns 200 OK with typed result DTO carrying: new state (the `targetStep`), recorded `recovery_actions.id`, list of `supersededArtifactIds` (story 4.7 AC6), list of `invalidatedApprovalIds` (story 4.7 AC7), runner re-enqueue confirmation (`runnerExecutionId`).
9. **Given** the test suite, **Then** covers: happy-path rerun-to-`investigating` returns 200 + state at `Investigating` + supersededArtifacts + invalidatedApprovals + re-enqueue, happy-path rerun-to-`executing` returns 200 + state at `Executing` + supersedes from that boundary, invalid target step rejected, missing reason rejected, illegal transition from terminal state, idempotent replay.

### Story 4.13: REST Endpoint — `pause` + OpenAPI

As a frontend developer (Decision Bar `recovery_operator` mode in story 4.22) and CLI user,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/pause` wired to `RecoveryService.pause` (story 4.8),
So that pause initiation flows through standard mutation conventions and operators have a one-step manual pause without taking over (which is non-reversible per story 3.25 AC9).

**Acceptance Criteria:**

1. **Given** `WorkflowController`, **Then** new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/pause` — kebab-case action.
2. **Given** request body, **Then** typed DTO `PauseWorkflowRequest { reasonText }`; required.
3. **Given** mandatory `Idempotency-Key` + `X-Actor-Identity` headers, **Then** standard conventions apply.
4. **Given** Problem Details errors, **Then** typed errors cover: `MISSING_REASON_TEXT` (400), `PAUSE_NOT_APPLICABLE` (409 — current state is terminal or `Paused` or `TakenOver`), `IDEMPOTENCY_KEY_CONFLICT` (409), `RUN_NOT_FOUND` (404), `ACTION_NOT_ALLOWED` (409).
5. **Given** OpenAPI + drift check, **Then** endpoint appears in regenerated snapshot.
6. **Given** CLI/REST equivalence, **Then** Spring Shell command `deliveryline operator pause --run {runId} --reason "..." [--idempotency-key K]` added; contract test asserts identical outcomes.
7. **Given** ArchUnit (story 1.11), **Then** controller method is thin.
8. **Given** the response, **Then** success returns 200 OK with typed result DTO carrying: new state (`Paused`), `priorState` (preserved per story 4.8 AC5 — what the run was doing before pause; resume goes back to this), recorded `recovery_actions.id`, counts of `cancelled_for_pause` runner executions (queued + in-flight per story 4.8 AC4).
9. **Given** the test suite, **Then** covers: happy-path pause from `Executing` returns 200 + state at `Paused` + priorState=Executing + cancelled-runner counts, pause from `WaitingForReview` returns priorState=WaitingForReview, pause from `Completed` returns 409, pause from `TakenOver` returns 409, pause from already-`Paused` returns 409, missing reason rejected, idempotent replay.

### Story 4.14: REST Endpoint — `classify-failure` + OpenAPI

As a frontend developer (failure-taxonomy classification UI in story 4.24) and CLI user,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/classify-failure` wired to `RecoveryService.classifyFailure` (story 4.9),
So that classification flows through standard mutation conventions and the UI dropdown (story 4.24) consumes the typed `FailureTaxonomyValue` enum from OpenAPI.

**Acceptance Criteria:**

1. **Given** `WorkflowController`, **Then** new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/classify-failure` — kebab-case action.
2. **Given** request body, **Then** typed DTO `ClassifyFailureRequest { taxonomyValue, reasonText? }` in camelCase JSON.
3. **Given** mandatory `Idempotency-Key` + `X-Actor-Identity` headers, **Then** standard conventions apply.
4. **Given** Problem Details errors, **Then** typed errors cover: `MISSING_TAXONOMY_VALUE` (400), `INVALID_TAXONOMY_VALUE` (400 — value not in registry), `DEPRECATED_TAXONOMY_VALUE` (400 — value is marked deprecated; payload includes `details.replacementValue` from story 4.9 AC6), `CLASSIFY_NOT_APPLICABLE` (409 — run not in terminal-failure state), `IDEMPOTENCY_KEY_CONFLICT` (409), `RUN_NOT_FOUND` (404), `ACTION_NOT_ALLOWED` (409).
5. **Given** OpenAPI + drift check, **Then** endpoint + `FailureTaxonomyValue` enum schema (with deprecated markers) appear in regenerated snapshot.
6. **Given** CLI/REST equivalence, **Then** Spring Shell command `deliveryline operator classify-failure --run {runId} --taxonomy {value} [--reason "..."] [--idempotency-key K]` added; contract test asserts identical outcomes.
7. **Given** ArchUnit (story 1.11), **Then** controller method is thin.
8. **Given** the response, **Then** success returns 200 OK with typed result DTO carrying: applied `taxonomyValue`, `priorTaxonomyValue?` (when re-classifying — story 4.9 AC9 surfaces both), recorded `recovery_actions.id`.
9. **Given** the test suite, **Then** covers: happy-path classification returns 200 + workflow_runs.failure_classification updated + event appended, re-classification returns priorTaxonomyValue + new value, deprecated value rejected with replacement hint, invalid value rejected, classify on non-failure state rejected, idempotent replay.

### Story 4.15: Artifact Reconciliation — DB/File Drift Detection

As a backend developer + workflow owner needing visibility into DB/file drift,
I want a scheduled `ArtifactDriftDetectionJob` that scans `artifact_operations` for orphan rows (DB record without payload file), missing payloads (file deleted after `markAvailable`), or checksum mismatches, plus a read surface so operators can list current drift,
So that NFR2 (workflow run must preserve produced artifacts after interruption) is enforced operationally + drift is visible before reviewers encounter it during approval.

**Acceptance Criteria:**

1. **Given** the `application.artifact.reconciliation` package extension to story 1.12's `ArtifactReconciliationService` skeleton, **Then** `ArtifactDriftDetectionJob` (scheduled via Spring `@Scheduled`, default every 15 minutes — configurable via `application.yml` `deliveryline.artifact.drift-detection-interval`) scans for three drift categories: (a) **orphan operation** — `artifact_operations.status='pending'` older than `2 × stage_timeout` with no corresponding `markAvailable` event (story 1.12 AC5 already flags these; this story formalizes the scan into a job), (b) **missing payload** — `artifacts.status='available'` but `LocalArtifactStore` cannot resolve the `storage_ref` (file deleted manually, disk corruption), (c) **checksum mismatch** — `artifacts.status='available'` + payload exists but its current checksum does not match `artifacts.checksum_value`.
2. **Given** detected drift, **Then** `artifact_drift_detected` table (added by Flyway V10 in this story) records: `id` (`adr_` prefix — added to `PublicIdPrefixes` registry per story 1.4 drift test), `artifact_id` or `artifact_operation_id` (one of the two FKs populated), `drift_category` (CHECK against `orphan_operation` / `missing_payload` / `checksum_mismatch`), `detected_at`, `last_known_state` (JSONB snapshot of artifact metadata at detection time), `resolved_at` (NULL until repair via story 4.16), `resolved_by_action_id` (NULL until resolved).
3. **Given** Flyway V10 migration `V10__add_artifact_drift_detected.sql`, **Then** creates the table + index on `(drift_category, detected_at)` for operator queue queries; replay safety asserted.
4. **Given** detection job emits `artifact.driftDetected` events per detected drift (one event per drift row), **Then** the central event registry (story 1.4) is extended; events appear in run timelines (FR47 append-only history) and in audit query (story 4.3).
5. **Given** the inspection read surface, **Then** `ArtifactReconciliationService.listUnresolvedDrift(filters) → List<DriftSummary>` returns rows where `resolved_at IS NULL`; filter options: drift category, time-since, workflow run, ticket reference; result includes typed `DriftSummary { driftId, artifactId?, artifactOperationId?, driftCategory, detectedAt, lastKnownState, suggestedRepairAction }`.
6. **Given** suggested-repair-action heuristics, **Then** each drift row carries a typed `RepairActionHint`: `orphan_operation` → `mark_failed_or_complete` (operator decides based on context); `missing_payload` → `restore_from_backup_or_mark_unavailable` (depending on whether a backup is configured); `checksum_mismatch` → `re_verify_or_mark_corrupted` — used by story 4.16's repair UI to pre-select an option.
7. **Given** observability per story 3.7 (ELK) + story 3.19 (Prometheus), **Then** the detection job exposes Prometheus metrics: `deliveryline_artifact_drift_detected_total{category}` (counter), `deliveryline_artifact_drift_unresolved_count{category}` (gauge); Grafana dashboard from story 3.7 AC6 extended with an "Artifact Drift" panel.
8. **Given** the read surface used by story 4.2 operator UI queue, **Then** drift surfaces alongside failed/stalled runs as an alternative attention indicator — operators see "3 drift entries" badges on workflow runs that have unresolved drift even when the run state itself is happy.
9. **Given** ArchUnit (story 1.11), **Then** `ArtifactDriftDetectionJob` lives in `application.artifact.reconciliation`; only that package may write to `artifact_drift_detected`; controllers + adapters consume the typed `DriftSummary` view.
10. **Given** the test suite, **Then** tests cover: orphan-operation detection (a stale pending operation past threshold is flagged + event emitted), missing-payload detection (an artifact whose file is deleted is flagged), checksum-mismatch detection (an artifact whose file is corrupted produces correct hash mismatch), V10 migration replay safety, Prometheus metrics emitted, suggested-repair-action heuristics correct per drift category, listUnresolvedDrift filter combinations work, drift appears in story 4.2 operator queue.

### Story 4.16: Artifact Reconciliation — Operator-Driven Repair Actions

As a Workflow Owner reviewing detected drift from story 4.15,
I want operator-triggered repair methods on `ArtifactReconciliationService` (`markOperationFailed`, `markOperationComplete`, `markPayloadUnavailable`, `restoreFromBackup`, `markCorrupted`, `reVerifyChecksum`) plus a REST endpoint `POST /api/v1/artifact-drift/{driftId}/repair` to invoke them,
So that AR14 (artifact reconciliation deeper than story 1.12 skeleton) is fully realized — operators can resolve drift with explicit decisions (NFR19 no silent overwrite).

**Acceptance Criteria:**

1. **Given** the `ArtifactReconciliationService` extension, **Then** typed repair methods are added: `markOperationFailed(driftId, reason, actor, idempotencyKey)`, `markOperationComplete(driftId, completionEvidence, actor, idempotencyKey)`, `markPayloadUnavailable(artifactId, reason, actor, idempotencyKey)`, `restoreFromBackup(artifactId, backupSource, actor, idempotencyKey)` (E4 stub — backup-source resolution deferred to a future "backup integration" epic, but the method signature is defined here for forward compatibility), `markCorrupted(artifactId, reason, actor, idempotencyKey)`, `reVerifyChecksum(artifactId, actor, idempotencyKey)` (re-runs the checksum and resolves the drift if hash now matches; otherwise leaves the drift row unresolved with an updated `lastKnownState`).
2. **Given** each repair method, **Then** in one transaction: (a) the `artifact_drift_detected` row's `resolved_at` + `resolved_by_action_id` are set, (b) the corresponding `artifacts` or `artifact_operations` row is updated per the chosen repair (e.g., `markOperationFailed` sets `artifact_operations.status='failed'` + appends `artifact.operationFailed` event; `markCorrupted` sets `artifacts.status='corrupted'` + appends `artifact.corrupted` event — `corrupted` added to `ArtifactStatus` registry per drift test).
3. **Given** the `recovery_actions` table, **Then** every repair appends a row with `action_type='artifact_repair'`, `triggering_event_id` (the `artifact.driftDetected` event), `actor_*`, `reasonText`, `idempotency_key`, `result_status`, `details` JSONB carrying the repair-method name + parameters.
4. **Given** approval-eligibility gating per story 1.12 AC6, **When** `markCorrupted` or `markPayloadUnavailable` runs on an artifact that has approvals attached, **Then** those approvals are invalidated (mirrors story 4.7 AC7's invalidation pattern using the V8 `invalidated_*` columns); PM/Developer is notified via run timeline.
5. **Given** REST `POST /api/v1/artifact-drift/{driftId}/repair`, **Then** typed request `ArtifactRepairRequest { repairAction, reasonText, ...action-specific-fields }` (camelCase JSON); mandatory `Idempotency-Key` + `X-Actor-Identity` headers per story 1.9 + story 2.13 conventions.
6. **Given** Problem Details errors, **Then** typed errors cover: `DRIFT_NOT_FOUND` (404), `DRIFT_ALREADY_RESOLVED` (409), `INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY` (400 — e.g., trying to `restoreFromBackup` on an `orphan_operation` drift category), `MISSING_REPAIR_REQUIRED_FIELD` (400 — e.g., `markOperationComplete` requires `completionEvidence`), `IDEMPOTENCY_KEY_CONFLICT` (409), `ACTION_NOT_ALLOWED` (409).
7. **Given** OpenAPI + drift check, **Then** endpoint + repair-action enum schemas appear in regenerated snapshot.
8. **Given** CLI/REST equivalence, **Then** Spring Shell command `deliveryline operator artifact-repair --drift {driftId} --action {repairAction} --reason "..." [--field=value ...] [--idempotency-key K]` added; contract test asserts identical outcomes.
9. **Given** ArchUnit (story 1.11), **Then** `ArtifactReconciliationService` is the ONLY service that may resolve drift rows; controllers + adapters call the service, never write to `artifact_drift_detected.resolved_*` directly.
10. **Given** the test suite, **Then** tests cover: each repair method per drift category (happy path + idempotent replay), invalid-repair-action-for-drift-category rejection, drift-already-resolved rejection, missing-required-field rejection, `markCorrupted` + `markPayloadUnavailable` invalidate prior approvals, REST endpoint conformance, CLI equivalence.

### Story 4.16a: Artifact Lineage Reconciliation and Fork Governance

As a Workflow Owner repairing ambiguous artifact history after partial failure or replay conflict,
I want explicit lineage-recovery actions that let me reattach an orphan payload, mark a dead lineage terminal, or approve creation of a new lineage branch with recorded rationale,
So that ambiguous artifact history is resolved through auditable recovery rather than hidden automatic behavior.

**Acceptance Criteria:**

1. **Given** story 1.12's fail-closed artifact behavior, **Then** `ArtifactReconciliationService` is extended with typed lineage-recovery methods that resolve `ARTIFACT_OPERATION_INTENT_CONFLICT` cases rather than forcing Epic 1 to guess lineage outcomes.
2. **Given** a lineage-recovery command, **Then** the operator must choose an explicit action from a typed set: `reattach_to_existing_lineage`, `terminate_ambiguous_lineage`, or `create_explicit_fork`; there is no default branch-selection behavior.
3. **Given** `reattach_to_existing_lineage`, **Then** the service records the chosen parent artifact/version, re-links the orphaned or ambiguous artifact operation to that lineage, appends an audit event, and preserves the superseded/failed lineage history unchanged.
4. **Given** `terminate_ambiguous_lineage`, **Then** the service marks the ambiguous artifact/operation path terminal, appends a recovery event explaining why it was abandoned, and prevents later replay from silently reviving it.
5. **Given** `create_explicit_fork`, **Then** the service creates a new lineage branch only after operator approval, records a lineage-recovery discriminator in persisted metadata, and appends an audit event containing the rationale and source lineage reference.
6. **Given** a persisted lineage-recovery action, **Then** it writes a `recovery_actions` row with `action_type='artifact_lineage_reconcile'`, stores the selected action and rationale in `details`, and appends a dedicated workflow event so the run timeline explains how ambiguity was resolved.
7. **Given** follow-up artifact operations after lineage recovery, **Then** replay and version creation use the explicit repaired lineage outcome and never fall back to silent auto-fork or guessed-parent behavior.
8. **Given** operator inspection surfaces, **Then** lineage-recovery outcomes appear in the operator queue, recovery detail views, and audit history with enough information to distinguish reattach, terminate, and explicit fork decisions.
9. **Given** Problem Details and REST/CLI parity, **Then** attempts to process an unresolved lineage ambiguity without an explicit operator decision return a stable conflict error; CLI and REST surface the same resolution requirement.
10. **Given** the test suite, **Then** it covers: reattach happy path, terminate happy path, explicit-fork happy path, idempotent replay of lineage-recovery actions, rejected missing-decision requests, and proof that no silent fork occurs before operator approval.

### Story 4.17: Integration Conflict Detection — Linear + GitHub vs Internal State

As a backend developer + workflow owner needing visibility into integration drift,
I want scheduled `IntegrationConflictDetectionJob` that compares internal workflow state against external Linear ticket state + GitHub PR state, persisting detected conflicts to a queryable table — never silent overwrite per NFR19,
So that FR41 (workflow owners can detect disagreement between internal workflow state and external integration state) is wired and FR43 (distinguish sync failures, link failures, state conflicts) is upheld.

**Acceptance Criteria:**

1. **Given** the `application.integration.conflict` package, **Then** `IntegrationConflictDetectionJob` (scheduled via Spring `@Scheduled`, default every 5 minutes — configurable via `application.yml` `deliveryline.integration.conflict-detection-interval`) iterates `integration_links` rows and compares cached `external_metadata` (last-synced state) against fresh queries to the corresponding adapter (`TicketSourceAdapter` from story 3.32 / `RepositoryHostAdapter` from story 3.33).
2. **Given** detected conflict categories, **Then** the job classifies into `IntegrationConflictCategory` registry values (added to story 1.4 drift test): `external_state_advanced` (e.g., GitHub PR merged externally while internal state is `WaitingForReview`), `external_state_reverted` (e.g., GitHub PR reopened after internal state thought it was `Completed`), `external_resource_removed` (e.g., Linear ticket deleted, GitHub branch deleted), `metadata_drift` (non-state metadata drifted — e.g., PR title or description changed externally), `link_broken` (external resource no longer accessible — 404 from external API).
3. **Given** Flyway V11 migration `V11__add_integration_conflicts.sql`, **Then** creates `integration_conflicts` table: `id` (`icf_` prefix — added to `PublicIdPrefixes` registry per drift test), `integration_link_id` (FK), `workflow_run_id` (FK), `conflict_category` (CHECK against registry), `detected_at`, `internal_state_snapshot` (JSONB), `external_state_snapshot` (JSONB), `resolved_at` (NULL until reconcile via story 4.6), `resolved_by_action_id`; index on `(conflict_category, detected_at)` for operator queue queries; replay safety asserted.
4. **Given** the detection job emits `integration.conflictDetected` events per detected conflict (one per row), **Then** the central event registry is extended; events appear in run timelines + audit query (story 4.3).
5. **Given** sync-failure vs link-failure vs state-conflict distinction per FR43, **Then** the job classifies failures hitting the external API into the right `IntegrationFailureCategory` registry value (story 1.14 + 3.13 already added these): `sync_failure` (transient — the job will retry next cycle), `link_failure` (external resource removed → emits `external_resource_removed` conflict), `state_conflict` (state diverges → emits the appropriate `external_state_*` conflict), `network_api_failure` (transient network issue — retried).
6. **Given** the inspection read surface, **Then** `IntegrationConflictService.listUnresolvedConflicts(filters) → List<ConflictSummary>` returns rows where `resolved_at IS NULL`; filter options: conflict category, integration type (linear / github), time-since, workflow run, ticket reference.
7. **Given** observability per story 3.7 (ELK) + story 3.19 (Prometheus), **Then** the detection job exposes Prometheus metrics: `deliveryline_integration_conflict_detected_total{category, integration}` (counter), `deliveryline_integration_conflict_unresolved_count{category, integration}` (gauge); Grafana dashboard from story 3.7 AC6 extended with an "Integration Conflicts" panel; alert rule `IntegrationConflictUnresolvedHigh` (>5 unresolved conflicts for 10 minutes — default thresholds configurable).
8. **Given** rate-limit awareness per story 3.14 AC5 (GitHub rate limits) + Linear's polling cadence (story 1.14 AC9), **Then** the conflict detection job adapts its query frequency: degrades to lower-frequency mode under rate-limit pressure (logs WARN), backs off on `GITHUB_RATE_LIMITED` failures.
9. **Given** ArchUnit (story 1.11), **Then** `IntegrationConflictDetectionJob` lives in `application.integration.conflict`; only that package may write to `integration_conflicts`.
10. **Given** the test suite, **Then** tests cover: each conflict category detection (using mock Linear + mock GitHub fixtures from stories 1.14 + 3.13 with conflict-injection scenarios), V11 migration replay safety, Prometheus metrics emitted, rate-limit-driven backoff behavior, listUnresolvedConflicts filter combinations work, conflict appears in story 4.2 operator queue.

### Story 4.18: Integration Conflict — Operator Action Surfacing + Conflict-Driven Workflow Pause

As a Workflow Owner needing detected conflicts (from story 4.17) to surface in the right places + the workflow to pause when state-conflict severity warrants it,
I want backend orchestration that: (a) surfaces unresolved conflicts in the operator queue (story 4.2) with priority indicators, (b) exposes them via REST inspection endpoint, (c) auto-pauses the workflow on `external_state_advanced` or `external_state_reverted` conflicts (per NFR21 — pause + require human confirmation when identity is ambiguous), (d) prevents new orchestration dispatches against runs with unresolved high-severity conflicts,
So that conflicts don't silently advance state (NFR19) and operators have explicit decisions to make via story 4.6 / 4.11 reconcile path before workflow can continue.

**Acceptance Criteria:**

1. **Given** the operator queue from story 4.2, **Then** runs with unresolved conflicts (from story 4.17's `integration_conflicts.resolved_at IS NULL`) display a distinct "conflict" attention indicator alongside the existing failed/stalled indicators — using non-color signifier (icon + "Conflict" label) per story 2.3 AC5 + state-warning token treatment.
2. **Given** REST `GET /api/v1/integration-conflicts` (operator-facing inspection), **Then** returns `IntegrationConflictListResponse { conflicts: List<ConflictSummary>, totalUnresolved, totalResolved, totalUnresolvedByCategory: Map<...>, totalUnresolvedByIntegration: Map<...> }`; filter query params: `?category=`, `?integration=linear|github`, `?workflowRunId=`, `?since=`, `?resolved=true|false`, `?limit=`, `?cursor=`; idempotent read; OpenAPI documented.
3. **Given** `GET /api/v1/integration-conflicts/{conflictId}` (single-conflict detail), **Then** returns the typed conflict with both `internalStateSnapshot` + `externalStateSnapshot` JSONBs (so the UI dialog from story 4.23 can display the diff side-by-side), suggested `ReconciliationDecision` options ranked by safety (e.g., for `external_state_advanced` where GitHub PR was merged externally → `accept_external_state` is `safe`, `accept_internal_state` would re-open the PR which is `risky`).
4. **Given** auto-pause-on-high-severity per NFR21, **When** the detection job from story 4.17 detects a new conflict in category `external_state_advanced` or `external_state_reverted` for a run that is in a non-terminal, non-`Paused`, non-`TakenOver` state, **Then** the conflict-handling layer invokes `RecoveryService.pause(workflowRunId, actor='system', reasonText='auto_paused_on_state_conflict')` (story 4.8) — auto-pause is recorded with `actor=system` and `reviewerRole=system` so the audit trail distinguishes auto-pause from operator-triggered pause.
5. **Given** auto-pause is configurable per environment, **Then** `application.yml` `deliveryline.integration.conflict.auto-pause-on-categories` (list, default `[external_state_advanced, external_state_reverted]`) lets pilots opt out of auto-pause for specific categories — empty list = no auto-pause; documented in `docs/integrations/conflict-handling.md`.
6. **Given** orchestration gate, **When** `WorkflowOrchestrationService.dispatchPlanGeneration` / `dispatchImplementation` (stories 3.11 / 3.12) is invoked on a run with unresolved high-severity conflicts, **Then** dispatch is refused with `DISPATCH_BLOCKED_BY_UNRESOLVED_CONFLICT` (carried as a transient orchestration failure — workflow remains in its prior state); operator must reconcile (story 4.6 / 4.11) before dispatch can proceed.
7. **Given** allowed-actions integration (story 2.14), **Then** runs with unresolved conflicts surface `reconcile_conflict` in their allowed-actions list (mirrors story 4.6 AC9); the conflict ID + suggested decision are attached so the UI dialog from story 4.23 can pre-fill.
8. **Given** Linear completion-sync awareness from story 3.16, **When** auto-pause fires due to a Linear-side conflict (e.g., the linked Linear ticket was closed externally), **Then** the completion sync's reverse-notification path is suppressed (no premature "Run reopened" comment posted to Linear) until the operator explicitly reconciles.
9. **Given** ArchUnit (story 1.11), **Then** the conflict-driven orchestration gate is implemented in `application.workflow.WorkflowOrchestrationService` (the existing service) — the gate is a precondition check before dispatch; no new service introduced.
10. **Given** the test suite, **Then** tests cover: conflict surfaces in operator queue with attention indicator, GET list returns unresolved + resolved counts + filter combinations, GET detail returns both snapshots + safety-ranked suggestions, auto-pause triggers on `external_state_advanced` (with `actor=system`), auto-pause does not trigger on excluded categories per config, dispatch blocked when unresolved high-severity conflict exists, allowed-actions includes `reconcile_conflict` with conflictId + suggestion attached, Linear premature-notification suppression.

### Story 4.19: Compare Mode Backend — Revision Delta Service for Spec / Plan / PR-Output

As a backend developer providing the data layer for the Compare Mode UI (story 4.20),
I want a `RevisionDeltaService` that computes typed deltas between two artifact versions of the same artifact lineage (per story 1.12 AC7 lineage with `parent_artifact_id` chain) — for spec (markdown text diff), implementation-plan (structured-step diff), and PR-output (file-level diff already captured in artifact payload) — with sanitization-aware output,
So that UX-DR13 (Compare Mode / Revision Delta Summary) has a typed backend contract the frontend consumes via REST.

**Acceptance Criteria:**

1. **Given** the `application.compare` package, **Then** `RevisionDeltaService.computeDelta(artifactIdA, artifactIdB) → RevisionDelta` is added; both artifacts must belong to the same lineage (verified by walking the `parent_artifact_id` chain) — mismatch raises `ARTIFACT_LINEAGE_MISMATCH`.
2. **Given** the typed `RevisionDelta` view, **Then** it returns: `artifactType` (one of `spec` / `implementationPlan` / `prOutput`), `revisionA: ArtifactSummary`, `revisionB: ArtifactSummary` (each with `version`, `createdAt`, `producedByActor`, `checksum`), `summary: DeltaSummary { changedRegionCount, addedCount, removedCount, modifiedCount }`, `changes: List<ChangeBlock>` (variant-specific shape per AC3-AC5), `noMeaningfulDiff: boolean` (true when both artifacts are byte-equal or differ only in non-semantic whitespace).
3. **Given** spec variant, **Then** `ChangeBlock` is `MarkdownChangeBlock { sectionPath, changeKind: 'added'|'removed'|'modified', priorText, currentText }`; the diff algorithm operates on markdown sections (split by heading levels) so changes are presented section-by-section rather than line-by-line — operators see "Section 'Edge Cases' was added" instead of raw line diff.
4. **Given** implementation-plan variant, **Then** `ChangeBlock` is `PlanStepChangeBlock { stepId, changeKind, priorStepText?, currentStepText?, priorStepOrder?, currentStepOrder? }`; diff operates on the structured-steps array — supports detecting added/removed/reordered/modified steps.
5. **Given** PR-output variant, **Then** the runner already produces a `diffReference` artifact field per story 1.6 AC4 / story 3.27 AC2 — `RevisionDeltaService` for PR-output reads the two artifacts' `diffReference` payloads and computes a `FileChangeBlock { filePath, changeKind, addedLines, removedLines }` summary, plus a `linkedDiffReferences` field so the UI can lazy-load the actual diff content (story 4.20).
6. **Given** sanitization per story 2.24 / story 1.10, **Then** all text content in the returned `ChangeBlock`s passes through `RedactionPolicyService` before serialization — even though the artifact content itself was already redacted on capture, defense-in-depth applies on serve.
7. **Given** REST `GET /api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}`, **Then** returns 200 with `RevisionDelta` JSON; idempotent read; OpenAPI documented; performance target: under 5s for spec/plan deltas of typical pilot size, under 10s for PR-output deltas (file-level summary only — full diff content lazy-loaded by story 4.20 UI).
8. **Given** Problem Details errors, **Then** typed errors cover: `ARTIFACT_LINEAGE_MISMATCH` (400), `ARTIFACT_NOT_FOUND` (404 for either ID), `ARTIFACT_PAYLOAD_UNAVAILABLE` (409 — one of the artifacts is not `available` per story 1.12 AC6).
9. **Given** ArchUnit (story 1.11), **Then** `RevisionDeltaService` lives in `application.compare`; its only collaborators are `ArtifactService` (story 1.12) + `RedactionPolicyService` (story 1.10); the diff algorithms (markdown, structured-steps, file-level) live in dedicated implementation classes with clear interfaces so they can be unit-tested independently.
10. **Given** the test suite, **Then** tests cover: spec delta detects added/removed/modified sections, plan delta detects added/removed/reordered/modified steps, PR-output delta produces correct file-level summary, lineage mismatch rejected, no-meaningful-diff (byte-equal artifacts) returns `noMeaningfulDiff=true` + empty changes list, sanitization applied on serve, performance under target for fixture artifacts, REST endpoint conformance.

### Story 4.20: Compare Mode UI Component (UX-DR13)

As a Product Manager (spec revisions) / Developer (plan revisions, PR-output revisions) verifying what changed before approving,
I want the `CompareMode` composite (UX-DR13) fully implemented with side-by-side / summary-first / changed-region indicators / filter & jump controls / exit-back-to-review control, consuming `RevisionDeltaService` from story 4.19,
So that UX-DR13 lands as a first-class trust-and-verification surface — the deferred-from-MVP composite that the architecture's "trust verification" promise depended on.

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/CompareMode.tsx`, **Then** the component accepts a `CompareModeProps { artifactIdA, artifactIdB, onExit }` and consumes `useRevisionDelta(artifactIdA, artifactIdB)` (TanStack Query hook backed by story 4.19 REST endpoint).
2. **Given** anatomy per UX-DR13, **Then** the rendered component displays: revision A + revision B identifiers (with `version`, `producedByActor`, `createdAt` from the typed `ArtifactSummary` per story 4.19 AC2), summary header (changed-region count + added/removed/modified counts), side-by-side or stacked comparison surface (variant-driven per artifact type), changed-region indicators (markers in scroll gutter showing where changes are), filter controls (toggle "Show only changes" / "Show all"), jump controls (next/previous changed region — keyboard-shortcut J/K like GitHub PR review), exit-back-to-review control.
3. **Given** states per UX-DR13, **Then** the component renders: `default comparison` (both artifacts available + diff computed), `loading` (delta fetch in progress — skeleton matching layout), `no meaningful diff` (per story 4.19 AC2 `noMeaningfulDiff=true` — empty-state rendered with "These revisions are identical"), `no baseline available` (story 4.19 AC8 `ARTIFACT_NOT_FOUND` — explanatory error), `partial comparison available` (one artifact is current + the other was archived per retention — operator override needed; uses story 2.22 ErrorState `nextAction`), `diff unavailable` / `error / comparison unavailable` (per story 4.19 errors).
4. **Given** variants per UX-DR13, **Then** the component supports: `side-by-side compare` (default for spec + plan; horizontal split with synced scroll), `stacked compare` (default for PR-output; vertical with file accordions), `summary-first compare` (initial render shows summary header + collapsed change blocks; user expands to see details), `spec revision compare` (uses story 4.19 AC3 `MarkdownChangeBlock` with section-level granularity), `plan revision compare` (uses AC4 `PlanStepChangeBlock` with step ordering visible).
5. **Given** sanitization per story 2.24, **Then** all rendered text from the delta passes through `SafeMarkdownRenderer` (for spec) or plain-text rendering with diff syntax (for plan + PR-output); XSS fixtures from story 2.24 AC7 are exercised against the compare renderer in tests.
6. **Given** keyboard navigation per UX-DR13 + story 2.25, **Then** users can: Tab between summary header / filter controls / changed regions / exit control; J/K (or Down/Up Arrow) jumps to next/previous changed region with smooth scroll; Esc exits compare mode and returns to the originating review context (story 2.22 `useReturnToRunContext` per UX-DR16).
7. **Given** content guidelines per UX-DR13, **Then** the renderer prioritizes changed regions (scrollable focus follows the next changed region by default), keeps compare scoped + task-driven (no extraneous metadata panels), summarizes what changed before showing dense detail (collapsed-by-default in summary-first variant).
8. **Given** mode boundary per UX-DR13 + story 2.7 layout ADR, **Then** Compare Mode is **a deeper inspection state of the same workflow review** — not a separate route or sub-product; entering compare from an Artifact Review Panel (story 2.17 / 3.26 / 3.27) uses an in-context overlay or expanded panel within the AppShell tri-pane (story 2.7), preserving run identity + artifact context per UX-DR16.
9. **Given** allowed-actions integration (story 2.14), **Then** the entry control to Compare Mode (the disabled control from story 2.17 AC2 / story 3.27 AC9) is now activated when backend allowed-actions include `enter_compare_mode` (added to registry per drift test) — typically when at least 2 versions exist in the artifact's lineage.
10. **Given** ARP integration, **Then** Compare Mode is launched from: (a) Artifact Review Panel's "Compare with previous revision" control (default — compares current vs immediately-prior version), (b) explicit "Compare with revision N" dropdown (when artifact has >2 versions), (c) operator deep-dive view (story 4.4) for failure-context comparison.
11. **Given** component test coverage, **Then** tests cover: side-by-side renders correctly with synced scroll, stacked renders for PR-output, summary-first collapsed-then-expanded, no-meaningful-diff empty state, partial-comparison error state with next action, J/K jump shortcuts, Esc-to-exit returns to prior context, sanitization rejects scriptable payloads in delta content, allowed-actions integration enables/disables entry control, ARP launch contexts work, axe-core a11y zero violations.

### Story 4.21: Compare Mode Mobile Bounded State (UX-DR23)

As a Product Manager / Developer occasionally needing Compare Mode on mobile (Galaxy S23+ class per UX-DR24),
I want Compare Mode to render as a **dedicated bounded mobile state** per UX-DR23 — single-column with explicit before/after toggle rather than compressed side-by-side,
So that the mobile review flow remains usable when revision verification is needed away from desktop.

**Acceptance Criteria:**

1. **Given** the responsive design system from story 2.26 + UX-DR23 rule "compare becomes a dedicated bounded mobile state rather than compressed side-by-side", **Then** at mobile breakpoint (320–767px per story 2.26 AC1) Compare Mode (story 4.20) renders in a dedicated mobile layout: full-screen takeover (covering the AppShell's nav rail + context panel), top bar with revision A/B labels + before/after toggle + exit control, single-column body showing one revision at a time (per the toggle).
2. **Given** the before/after toggle, **Then** it is a prominent control at the top of the mobile view (always visible — not behind a menu), keyboard-operable on devices with keyboard support, tap-target sized per story 2.25 AC10 (≥44×44 CSS pixels).
3. **Given** changed-region navigation on mobile, **Then** the J/K shortcuts from story 4.20 AC6 are replaced with on-screen "Previous change" / "Next change" buttons in the top bar (since J/K is impractical without keyboard) — buttons advance to the next changed region in the currently-displayed revision.
4. **Given** the variant-driven content per story 4.20 AC4, **Then** spec mobile compare shows section-by-section per `MarkdownChangeBlock`; plan mobile compare shows step-by-step per `PlanStepChangeBlock`; PR-output mobile compare shows file-by-file per `FileChangeBlock` — all in single-column with clear added/removed/modified visual treatment using state tokens from story 2.3.
5. **Given** UX-DR23 priority order ("preserve artifact reading > preserve decision controls > disclose navigation/supporting context"), **Then** the mobile compare prioritizes the artifact body (single revision shown); the toggle + change navigation are persistent at top; exit control is always visible.
6. **Given** UX-DR23 rule "structural collapse rules: compare should become a dedicated bounded mobile state rather than compressed side-by-side", **Then** the mobile layout NEVER attempts to show both revisions simultaneously (no "shrunk side-by-side" on phones — would lose comprehension); a layout test asserts the mobile breakpoint renders only one revision at a time.
7. **Given** exit behavior, **Then** the mobile exit control (X icon at top-right per common mobile patterns + back-button behavior) returns the user to the originating review context using story 2.22 `useReturnToRunContext` — preserves run + artifact selection on return.
8. **Given** real-device validation per story 2.26 AC9, **Then** Compare Mode on mobile is included in the Galaxy S23+ class real-device manual checklist (`docs/testing/responsive-real-device-checklist.md` from story 2.26).
9. **Given** keyboard-only support on devices with keyboards (e.g., tablets with attached keyboards), **Then** Tab/Enter/Esc + the J/K shortcuts continue to work on tablet breakpoint (768–1023px) where Compare Mode renders a reduced tri-pane (per story 2.26 AC2) — mobile-specific UI only kicks in at <768px.
10. **Given** component + integration test coverage, **Then** tests cover: mobile breakpoint renders single-column with toggle + exit + change navigation, no shrunk-side-by-side at mobile, before/after toggle switches displayed revision, "Previous/Next change" buttons advance correctly, exit returns to originating context, tap targets meet 44×44 minimum, axe-core a11y zero violations on mobile viewport, real-device checklist updated with Compare Mode entry.

### Story 4.22: Decision Bar `recovery_operator` Mode — Full Activation (Deeper Actions Beyond Retry Baseline)

As a Workflow Owner using the Decision Bar's `recovery_operator` mode in the UI to drive recovery actions,
I want the mode (stub from story 2.19 AC1, baseline-activated in story 3.30 with only `Retry failed step`) **fully activated** with the deeper action set: resume / reconcile / rerun-from-step (with safe-boundary dropdown) / pause / classify-failure (with taxonomy dropdown), each gated by backend allowed-actions and confirmation patterns appropriate to consequence,
So that operators have a unified UI surface for all Epic 4 recovery actions per UX-DR12 (mode prop fully realized) — without leaving the Decision Bar to invoke any single recovery action.

**Acceptance Criteria:**

1. **Given** `ApprovalDecisionBar` from story 2.19 with `mode='recovery_operator'`, **Then** the baseline implementation from story 3.30 is extended to render the full action set when backend allowed-actions include them: `Retry failed step` (story 3.30 baseline), `Resume run` (story 4.10), `Reconcile conflict` (story 4.11), `Rerun from step` (story 4.12), `Pause run` (story 4.13), `Classify failure` (story 4.14).
2. **Given** **one visually primary action per decision area** per UX-DR19 + the safety-ranking from story 4.4 AC2, **Then** the action ranked `safe` for the current state appears as the visually primary action; others are visually subordinate with their backend-reported `safetyLevel` indicated visually (e.g., `caution` action labeled with a small warning icon, `risky` with a stronger warning); the bar never renders multiple primary actions.
3. **Given** `Resume run` action, **When** invoked, **Then** opens `ConfirmationDialog` (story 2.23) with consequence text "Resume will return the run to its prior executing state ({priorState}) and re-enqueue runner work." + Cancel + "Confirm resume"; submit calls `useResumeWorkflow` mutation hook calling story 4.10 endpoint with version stamps + Idempotency-Key.
4. **Given** `Reconcile conflict` action, **When** invoked AND there are unresolved integration conflicts on the run (per story 4.18), **Then** delegates to the operator reconciliation dialog from story 4.23 (which has its own richer UI for conflict review + decision selection); the Decision Bar action is the entry point + the dialog handles the actual reconcile mutation.
5. **Given** `Rerun from step` action, **When** invoked, **Then** opens `RationaleCaptureDialog` (story 2.23) with: typed `targetStep` dropdown sourced from backend allowed-actions response (story 4.7 AC10 surfaces the allowed `targetStep` enum values), required `reasonText` textarea explaining why rerun is needed, "Show what will be superseded" expandable section displaying the `supersededArtifactIds` + `invalidatedApprovalIds` that will result (preview before confirm — pulls from a `GET /api/v1/workflows/{runId}/preview-rerun-from-step?targetStep=X` lightweight endpoint added in this story), Cancel + "Confirm rerun" with `intent="danger"` styling per UX-DR18.
6. **Given** `Pause run` action, **When** invoked, **Then** opens `ConfirmationDialog` with consequence text "Pause will halt orchestrator dispatch and cancel in-flight + queued runner work for this run. The run can be resumed later." + Cancel + "Confirm pause" — `intent="warning"` since pause is reversible (lower-severity than takeover from story 3.28 which uses `intent="danger"`).
7. **Given** `Classify failure` action, **When** invoked, **Then** delegates to the failure-taxonomy classification UI from story 4.24 (richer UI for taxonomy selection); the Decision Bar action is the entry point.
8. **Given** version-stamped mutations per story 2.19 AC6, **Then** every recovery mutation sends the appropriate version stamps + Idempotency-Key + reviewerRole=workflow_owner; on conflict (e.g., `IDEMPOTENCY_KEY_CONFLICT`, `RESUME_NOT_APPLICABLE`), the bar renders the appropriate stale-decision state with refresh-and-retry CTA explaining what changed.
9. **Given** post-submit decision summary per story 2.19 AC9, **Then** after a recovery action lands, the bar persists the outcome visibly (timestamp + actor + action + new state + reason) with the appropriate visual treatment per safety level until parent component resets.
10. **Given** ARIA + accessibility per story 2.25, **Then** all actions keyboard-reachable, button labels use explicit verbs ("Resume run", "Reconcile conflict", "Rerun from step", "Pause run", "Classify failure"), disabled rationale `aria-describedby` linked to backend-reported `reasonCode`, focus moves into and out of dialogs predictably (story 2.23 AC5).
11. **Given** allowed-actions integration with backend-reported `disabledActions: { [action]: reasonCode }` per story 2.19 AC5, **Then** when the backend reports an action disabled (e.g., `pause_workflow` disabled because run is `Completed`), the bar renders the disabled state with backend-reported reason mapped to localized text — never silently disabled.
12. **Given** scope discipline per story 1.18 AC11 ArchUnit scope-protected `RecoveryService` lock, **Then** this story works alongside story 4.28 (the architecture lift that removes the scope-protected lock since deeper recovery is now implemented) — story 4.28 must merge before this story's frontend mutations work end-to-end.
13. **Given** component test coverage, **Then** tests cover: `recovery_operator` mode renders full action set when allowed, safety-ranking-driven primary action selection per state, each action's confirmation dialog renders correct consequence text + danger styling per severity, version-stamped mutations send all expected versions, post-submit summary persists with correct visual treatment, keyboard navigation through all actions + dialogs, single-primary-action rule enforced, axe-core a11y scan zero violations.

### Story 4.23: Operator Reconciliation Dialog UI

As a Workflow Owner reviewing detected integration conflicts (from story 4.17 + 4.18) and deciding how to reconcile,
I want a dedicated reconciliation dialog UI that displays the internal-state-snapshot vs external-state-snapshot side-by-side, surfaces the safety-ranked `ReconciliationDecision` options, requires explicit decision + rationale, and submits via story 4.11's REST endpoint,
So that NFR19 (no silent overwrite) is enforced at the UI layer — operators cannot accidentally reconcile without seeing both states + selecting an explicit decision.

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/ReconciliationDialog.tsx`, **Then** the component accepts `ReconciliationDialogProps { workflowRunId, conflictId, onClose }` and consumes `useIntegrationConflict(conflictId)` (TanStack Query hook backed by story 4.18's GET-detail endpoint).
2. **Given** anatomy, **Then** the dialog displays: header with conflict category + integration type (Linear / GitHub) + detected-at timestamp, **side-by-side panels** for `internalStateSnapshot` (left) vs `externalStateSnapshot` (right) with field-level diff highlighting (added/removed/modified field-value comparison rendered visually using state tokens from story 2.3), suggested `ReconciliationDecision` radio group (one option marked as "Recommended" based on the safety-ranked suggestion from story 4.18 AC3), required `reasonText` textarea (operator must explain why this resolution chosen — NFR19 enforcement), Cancel + "Confirm reconcile" buttons (the latter `intent="danger"` since reconcile is non-reversible state assertion).
3. **Given** the snapshot panels, **Then** they use a structured display rather than raw JSON — known fields (state, branch, PR state, commit SHA, ticket status) are rendered with labeled rows + diff highlighting; unknown fields fall back to a "Raw metadata" expandable section showing prettified JSON for transparency.
4. **Given** the safety-ranked decision options per story 4.18 AC3, **Then** each `ReconciliationDecision` option is rendered with its safety level visible (`safe` / `caution` / `risky`) using the same visual vocabulary as story 4.22 AC2; the recommended option is pre-selected (operator can change); selecting `caution` or `risky` options surfaces an additional inline warning explaining what could go wrong (e.g., "Choosing `accept_internal_state` will re-open the externally-merged GitHub PR — this may conflict with downstream work.").
5. **Given** required-decision enforcement per NFR19 + story 4.6 AC3, **Then** the "Confirm reconcile" button is disabled until both `resolutionDecision` is selected AND `reasonText` is non-empty; submission sends the request via `useReconcileWorkflow` mutation hook calling story 4.11 endpoint.
6. **Given** error handling per story 4.11 AC4, **When** the backend returns `CONFLICT_ALREADY_RESOLVED` (another operator reconciled in parallel), **Then** the dialog renders a stale-state notice with "Refresh and try again" CTA — the conflict was resolved by someone else, the dialog shows the resolution outcome before closing.
7. **Given** focus management per UX-DR18 + story 2.23 AC5, **Then** focus moves into the dialog on open, returns to the triggering element on close (Cancel or successful submission); Esc dismisses with a confirmation prompt if reasonText has been edited (prevents accidental loss of work).
8. **Given** the dialog launch context, **Then** it can be invoked from: (a) the operator queue (story 4.2) via "Reconcile" action on a conflict-flagged row, (b) the failure diagnostics deep-dive view (story 4.4) when integration-sync drift is shown, (c) the Decision Bar `recovery_operator` mode (story 4.22 AC4) when "Reconcile conflict" is invoked.
9. **Given** mobile responsiveness per story 2.26, **Then** at mobile breakpoint the side-by-side snapshot panels collapse to a stacked layout (internal state above, external state below); the decision radio group + reasonText + buttons remain prominent; mobile uses full-height sheet pattern per story 2.23 AC6.
10. **Given** ARIA per story 2.25, **Then** the dialog has `role="dialog"` + `aria-labelledby` for the header, `aria-describedby` for the consequence-warning text on the selected decision; keyboard-fully-operable; ARIA live region announces submission state ("Reconciling..." → "Reconciled successfully" or error).
11. **Given** component test coverage, **Then** tests cover: side-by-side snapshot rendering with field-level diff highlighting, recommended option pre-selected, Confirm button disabled until both fields set, `caution`/`risky` warnings render correctly, submission via mutation hook, `CONFLICT_ALREADY_RESOLVED` stale-state UI, focus management on open/close + Esc-with-edited-reason confirmation, mobile stacked layout, all 3 launch contexts work, axe-core a11y zero violations.

### Story 4.24: Failure-Taxonomy Classification UI

As a Workflow Owner classifying a failed run for cross-run pattern analysis (per story 4.9),
I want a dedicated UI for selecting the appropriate `FailureTaxonomyValue` (with taxonomy descriptions + examples + deprecated-marker visibility) and submitting via story 4.14's REST endpoint,
So that FR37 (workflow owners can apply a failure category) is wired in the UI with a richer experience than a flat dropdown — operators understand what each category means and pick consistently.

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/FailureClassificationDialog.tsx`, **Then** the component accepts `FailureClassificationDialogProps { workflowRunId, onClose }` and consumes `useFailureTaxonomy()` (TanStack Query hook fetching the typed `FailureTaxonomyValue` enum with descriptions + deprecated markers from a new `GET /api/v1/registries/failure-taxonomy` endpoint added in this story).
2. **Given** anatomy, **Then** the dialog displays: header with run identifier + current failure context (failure category + reason text), **classification options** rendered as labeled radio cards (each card shows: taxonomy value name in human-readable form, 1-2 sentence description, 1-2 example scenarios, deprecated marker if `deprecated=true` per story 4.9 AC6 with replacement-value pointer), prior classification visible if the run was previously classified (per story 4.9 AC9 — shows "Previously classified as X at timestamp Y by actor Z" + lets operator re-classify), optional `reasonText` textarea, Cancel + "Apply classification" buttons.
3. **Given** the taxonomy registry endpoint, **Then** `GET /api/v1/registries/failure-taxonomy` returns `FailureTaxonomyRegistryResponse { values: List<TaxonomyValue> }` where each entry carries `{value, humanReadableName, description, examples: List<String>, deprecated: boolean, replacementValue?: string}`; idempotent read; OpenAPI documented; consumed by the dialog.
4. **Given** deprecated-value handling per story 4.9 AC6 + story 4.14 AC4, **Then** deprecated taxonomy values are rendered with reduced visual prominence (state-draft token treatment from story 2.3) + "(deprecated, use {replacementValue} instead)" affix; selecting a deprecated value displays a warning that submission will be rejected by the backend (`DEPRECATED_TAXONOMY_VALUE` per story 4.14 AC4) — the dialog actively guides operators away from deprecated values.
5. **Given** prior-classification handling per story 4.9 AC9, **Then** when the run was previously classified, the dialog: (a) pre-selects the prior value (if not deprecated; otherwise pre-selects the replacement value), (b) shows the prior classification provenance, (c) warns "This will re-classify the run. The prior classification remains in audit history." — operator confirms re-classification intentionally.
6. **Given** submission, **Then** "Apply classification" calls `useClassifyFailure` mutation hook calling story 4.14 endpoint with the selected `taxonomyValue` + optional `reasonText` + Idempotency-Key + reviewerRole=workflow_owner; success closes the dialog + invalidates the workflow detail query (TanStack Query) to refresh the run's `failure_classification` display.
7. **Given** error handling per story 4.14 AC4, **When** the backend returns `DEPRECATED_TAXONOMY_VALUE`, **Then** the dialog renders inline error pointing to `details.replacementValue` and re-selects the recommended replacement.
8. **Given** the dialog launch context, **Then** it can be invoked from: (a) the operator queue (story 4.2) via "Classify" action on a failed-run row that has not yet been classified, (b) the failure diagnostics deep-dive view (story 4.4), (c) the Decision Bar `recovery_operator` mode (story 4.22 AC7) when "Classify failure" is invoked.
9. **Given** post-classification surfacing, **Then** after successful classification, the run's failure classification appears throughout the UI: Run Context Strip (story 2.16) shows "Failure classification: {humanReadableName}" badge, operator queue rows display the classification in their attention-indicator slot, failure diagnostics deep-dive shows it prominently with provenance.
10. **Given** ARIA + accessibility per story 2.25, **Then** the radio cards are keyboard-navigable with arrow keys (standard radio-group behavior), each card's labeled by its taxonomy value + describedby its description; deprecated markers are announced by screen readers; ARIA live region announces submission state.
11. **Given** mobile responsiveness per story 2.26, **Then** at mobile breakpoint the radio cards stack vertically (already the default layout), full-height sheet pattern per story 2.23 AC6.
12. **Given** component test coverage, **Then** tests cover: radio cards render with descriptions + examples + deprecated markers, deprecated values rendered with reduced prominence + warning, prior classification pre-selected (if not deprecated) or replacement pre-selected, re-classification warning rendered, submission via mutation hook + query invalidation, `DEPRECATED_TAXONOMY_VALUE` error handling re-selects replacement, all 3 launch contexts work, post-classification visible across UI surfaces, axe-core a11y zero violations.

### Story 4.25: CI Tier Extension — Recovery Integration Tests + Fault Injection

As a backend developer + CI maintainer,
I want a dedicated CI tier that runs recovery integration tests with deliberate fault injection (timeout, crash, contract violation, integration conflict, artifact drift) — extending stories 1.21 + 3.34's tier order with recovery-specific gates,
So that recovery-path regressions surface in CI before reaching the foundation gate; the architecture's "recovery is part of the primary value proposition" claim is mechanically enforced.

**Acceptance Criteria:**

1. **Given** `.github/workflows/ci.yml` from stories 1.21 + 3.34, **Then** this story adds a new CI job `recovery-integration` running on `ubuntu-latest` (Docker-backed jobs Linux-only per story 1.21 AC3), `needs:` the foundation-gate job from story 1.23 + the runner-image-build job from story 3.34.
2. **Given** the test scenarios run by `recovery-integration`, **Then** they exercise each Epic 4 recovery path end-to-end: (a) **resume scenario** — pause a run via story 4.13's REST endpoint, verify state at `Paused` + cancelled runner counts, then resume via story 4.10's endpoint, verify state returns to prior executing state + runner re-enqueued via story 3.17 + workflow continues to completion; (b) **reconcile scenario** — inject an integration conflict via mock GitHub adapter (story 3.13 conflict-injection per AC4), verify `IntegrationConflictDetectionJob` (story 4.17) detects + persists, verify auto-pause fires (story 4.18 AC4), verify story 4.11 reconcile endpoint resolves; (c) **rerun-from-step scenario** — approve a spec, then rerun-from-step to `Investigating` via story 4.12, verify supersededArtifactIds + invalidatedApprovalIds + new spec runner enqueued; (d) **pause/resume idempotency** — invoke pause + resume in rapid sequence with same idempotency key, verify deterministic outcome; (e) **classify-failure** — fail a run, classify via story 4.14, verify failure_classification column updated + event appended.
3. **Given** **artifact drift fault injection** for story 4.15 + 4.16 coverage, **Then** tests deliberately produce drift scenarios: (a) inject orphan operation (insert `artifact_operations.status='pending'` row with stale created_at), verify `ArtifactDriftDetectionJob` flags it within one detection cycle, verify story 4.16 `markOperationFailed` resolves it; (b) inject missing payload (delete a payload file from `LocalArtifactStore`), verify detection + repair via `markPayloadUnavailable`; (c) inject checksum mismatch (corrupt a payload file), verify detection + repair via `reVerifyChecksum`.
4. **Given** **integration conflict fault injection** for story 4.17 + 4.18 coverage, **Then** tests use the conflict-injection fixtures from mock GitHub (story 3.13) + mock Linear (story 1.14) to produce each conflict category from story 4.17 AC2 (`external_state_advanced` / `_reverted` / `_resource_removed` / `metadata_drift` / `link_broken`); each detection + auto-pause behavior asserted.
5. **Given** flake control per story 1.21 AC5 + story 3.34 AC5 (no blanket retries), **Then** flaky failures in `recovery-integration` are surfaced as such — recovery-path flakes are tracked tech-debt items, not silently retried.
6. **Given** the foundation gate (story 1.23) widening per story 3.8 AC9 + story 3.35 AC10, **Then** `recovery-integration` job's success is required for foundation-gate PRs; its failure blocks merge regardless of other green checks.
7. **Given** PR-comment summary per story 3.34 AC3, **Then** when this job runs, a CI summary comment is posted on the PR showing scenario outcomes (which recovery scenarios passed / failed, which fault-injection paths exercised) — making recovery regressions visible without hunting through GitHub Actions UI.
8. **Given** path-based PR triggers per story 3.34 AC2, **Then** `recovery-integration` runs on every PR that touches `application.recovery/**`, `application.artifact.reconciliation/**`, `application.integration.conflict/**`, story 4.X frontend components, or recovery-related REST controllers — not on every PR.
9. **Given** test isolation per story 3.8 AC11, **Then** each scenario uses a fresh per-test `DELIVERYLINE_HOME` directory + fresh PostgreSQL schema (Testcontainers cleanup) — no cross-test state pollution.
10. **Given** "developer can reproduce CI locally" path per story 3.34 AC10, **Then** a documented reproduction path exists in `docs/setup-local.md` for running `recovery-integration` scenarios locally — operators can debug failed CI runs by re-running the exact scenario.

### Story 4.26: Test Suite Extension — Recovery Service, Reconciliation, Compare Mode, Operator UI

As a backend + frontend developer,
I want the test suite from stories 2.27 + 3.35 (Vitest + Playwright + axe + MSW + Testcontainers) extended to cover Epic 4's new surfaces — recovery service methods (resume/reconcile/rerun/pause/classify), reconciliation services (artifact drift + integration conflict), Compare Mode (backend + UI + mobile), operator UI (queue + dialog + classification),
So that Epic 4's new code paths have automated coverage; regressions caught at the same CI gates as Epic 2 + Epic 3.

**Acceptance Criteria:**

1. **Given** the existing test infrastructure from stories 2.27 + 3.35 + story 1.21 CI tiers, **Then** this story extends them with new test classes/files — does not introduce new infrastructure.
2. **Given** `RecoveryService` extension coverage, **Then** unit + integration tests in `backend/src/test/java/org/dradgo/recovery/` cover each method from stories 4.5–4.9 (resume/reconcile/rerun/pause/classify) per the AC assertions in those stories — happy paths + error paths + idempotency + state-machine validity.
3. **Given** reconciliation service coverage per stories 4.15–4.18, **Then** integration tests cover: artifact drift detection job runs against seeded drift scenarios, repair methods resolve drift correctly + invalidate prior approvals when applicable, integration conflict detection + auto-pause + dispatch gate behavior holds (per story 4.18 AC10).
4. **Given** Compare Mode backend coverage per story 4.19, **Then** integration tests cover spec/plan/PR-output delta scenarios + lineage-mismatch + no-meaningful-diff + sanitization-on-serve + REST endpoint conformance.
5. **Given** Compare Mode UI coverage per stories 4.20 + 4.21, **Then** Vitest + Testing Library tests cover all variants (side-by-side, stacked, summary-first), state transitions (loading, no-meaningful-diff, partial, error), J/K keyboard shortcuts, mobile bounded state assertion (no shrunk side-by-side <768px) + 44×44 tap targets.
6. **Given** operator UI coverage per stories 4.2 + 4.4 + 4.22 + 4.23 + 4.24, **Then** Vitest + Testing Library tests cover: operator queue with filters + virtualization + bulk-actions placeholder, failure diagnostics deep-dive view per NFR7 5-questions, Decision Bar `recovery_operator` mode with safety-ranked primary action per state, reconciliation dialog with side-by-side snapshots + recommended decision + required fields, classification dialog with radio cards + deprecated handling + prior-classification pre-selection.
7. **Given** Playwright cross-browser coverage extension per stories 2.27 AC8 + 3.35 AC7, **Then** new keyboard-only journey tests cover: operator finds a failed run via operator queue + diagnoses via deep-dive view + classifies via classification dialog, operator detects integration conflict via auto-pause + reconciles via dialog + run resumes, reviewer enters Compare Mode via ARP + navigates changes + exits back to review.
8. **Given** sanitization regression per story 2.27 AC7 + story 3.35 AC8, **Then** XSS fixtures for delta content (story 4.19 / 4.20), reconciliation-snapshot JSONB content (story 4.18), and classification descriptions (story 4.24) are added to the adversarial fixture set — sanitization regression block expanded.
9. **Given** axe-core a11y scan per story 2.27 AC4, **Then** every new component test runs an axe scan; zero `wcag2aa` violations is the bar.
10. **Given** the foundation gate (story 1.23) widening per story 3.35 AC10, **Then** "Epic 4 frontend test suite + Epic 4 backend integration tests + recovery-integration CI job (story 4.25) green" is added to the foundation-gate verification — Epic 4 regressions block PRs the same way Epic 2 + Epic 3's do.
11. **Given** coverage thresholds per stories 2.27 AC10 + 3.35 AC11, **Then** thresholds are extended to cover Epic 4's new packages: `application.recovery` extensions (stories 4.5–4.9), `application.artifact.reconciliation` (stories 4.15–4.16), `application.integration.conflict` (stories 4.17–4.18), `application.compare` (story 4.19) — minimum 80% line coverage; sanitization-related code 90%; safety-ranking logic 90% (high-leverage decision logic).
12. **Given** flake metrics per stories 1.21 AC5 + 3.34 AC5 + 3.35 AC12, **Then** Epic 4 tests are surfaced in flake reports; legitimate retry policies (e.g., scheduled-job timing flakes when tests race the detection cycle) are narrowly scoped with documented justification.

### Story 4.27: Failed-Run Recovery Walkthrough Documentation Increment

As a Workflow Owner joining the pilot,
I want a `docs/failed-run-recovery-walkthrough.md` that explains the Epic 4 recovery surface end-to-end — what each recovery action does + when to choose it + how to recognize when to pause vs reconcile vs takeover vs classify — with annotated screenshots/diagrams,
So that operators have a confident decision-making guide on their first pilot failure (NFR42 satisfied for the workflow-owner persona) — and the architecture's "recovery is part of the primary value proposition" claim is realized in operator-facing docs.

**Acceptance Criteria:**

1. **Given** `docs/failed-run-recovery-walkthrough.md`, **Then** it follows a problem-driven sequence: identifying that something needs attention (operator queue from story 4.2 + alerts from story 3.19) → diagnosing the failure (deep-dive view from story 4.4 + audit query from story 4.3) → choosing a recovery action (decision matrix mapping failure category → recommended action) → executing the action (Decision Bar `recovery_operator` mode from story 4.22 + dialogs from stories 4.23 + 4.24) → verifying the outcome (post-action state + audit history) → classifying for pattern analysis (story 4.24).
2. **Given** target completion time, **Then** the doc states "~10 minutes to triage your first failed run + apply the right recovery action".
3. **Given** the **decision matrix** mapping failure category → recovery action, **Then** a dedicated section presents a table or flowchart: each `FailureCategory` value (story 1.4 + story 4.9 AC5) → recommended action (retry / resume / reconcile / rerun-from-step / pause / takeover / classify-only) + when to choose alternatives. Examples: `runner_timeout` → typically `retry` (safe); `runner_contract_violation` → typically `pause` to investigate (retry will likely fail again); `external_state_advanced` (Linear/GitHub conflict) → typically `reconcile` with `accept_external_state`; `agent_execution_failure` repeatedly → typically `pause` or `takeover` if pattern persists.
4. **Given** the takeover non-reversibility expectation per story 3.25 AC9 + the takeover-vs-pause distinction, **Then** a section "Pause vs Takeover — when to choose which" explicitly explains: pause is reversible (operator can resume later, story 4.10), takeover is non-reversible in current release (story 3.25), so prefer pause for investigation + only takeover when human will continue work outside the orchestrator long-term.
5. **Given** the conflict-handling expectation per stories 4.17 + 4.18, **Then** a section "When the orchestrator auto-pauses on a conflict" explains: auto-pause fires on `external_state_advanced` / `external_state_reverted` (story 4.18 AC4), this is intentional (NFR21 — pause when state is uncertain), operator's job is to review both snapshots in the reconciliation dialog (story 4.23) and choose the explicit decision; never bypass by manually resuming without reconciling — the dispatch gate (story 4.18 AC6) will block it anyway.
6. **Given** screenshots / annotated diagrams (Mermaid OK), **Then** the following are illustrated: operator queue with a failed run highlighted (story 4.2), failure diagnostics deep-dive view with the safety-ranked actions (story 4.4), Decision Bar `recovery_operator` mode showing all 6 actions (story 4.22), reconciliation dialog with side-by-side snapshots + recommended decision (story 4.23), classification dialog with radio cards (story 4.24), audit query result showing post-recovery history (story 4.3).
7. **Given** the failure-taxonomy registry per story 4.9 AC5, **Then** a section explains each canonical taxonomy value (`specification_gap`, `context_gap`, `agent_execution_failure`, `review_rejection`, `integration_or_merge_failure`, `tooling_or_infrastructure_failure`) with concrete examples — supporting consistent classification and the cross-run pattern analysis the PRD's Growth-stage analytics depend on.
8. **Given** the queue + alerts surfaces per stories 3.19 + 4.18 AC7, **Then** a section "Setting up alerts" walks operators through enabling Prometheus alert routing (story 3.19 AC8 alertmanager docs) for `RunnerQueueDepthHigh`, `RunnerOldestQueuedStale`, `IntegrationConflictUnresolvedHigh` — pilots can opt into proactive notification via Slack/email/PagerDuty.
9. **Given** cross-platform usability, **Then** the walkthrough is browser-based + CLI-supplemented (CLI commands shown alongside UI flows for operators who prefer CLI); works identically on Windows / macOS / Linux per story 1.17 supported-environment matrix.
10. **Given** the link-check CI step from story 1.22 AC8, **Then** all internal doc links resolve to real files; cross-references to stories 1.18, 2.16, 2.19, 3.19, 3.30, 4.2, 4.3, 4.4, 4.22, 4.23, 4.24 are anchored correctly.
11. **Given** documentation-increment acceptance per Epic 4's doc-increment rule (pre-mortem refinement R7), **Then** Epic 4 cannot close without `failed-run-recovery-walkthrough.md` merged + visible from `docs/index.md`.
12. **Given** an Operator-validator placeholder (parallel to story 1.22 AC7 + story 2.29 AC11 + story 3.36 AC11 — John's party-mode finding "name the human validator per epic"), **Then** the doc includes a placeholder line for "Operator walkthrough validator: ***\_***_ (to be named before Epic 4 close)" — reminding Alex to identify and coordinate with the real human operator whose cold walkthrough validates the epic.
13. **Given** NFR43 (minimize new concepts), **Then** the walkthrough uses the concept set declared in PRD + prior epics (ticket, spec, run, artifact, review, failure, recovery action) plus Epic 4 vocabulary (resume, reconcile, rerun-from-step, pause, classify, conflict, drift, taxonomy) — all newly-introduced concepts have entries in `docs/glossary.md` from story 1.22 AC10.

### Story 4.28: Architecture Lift — Remove `RecoveryService` Scope-Protected Lock + ADR

As an architect documenting that Epic 4 has fulfilled the deeper-recovery scope that story 1.18 deferred,
I want story 1.18 AC11's ArchUnit scope-protected lock on `RecoveryService` lifted (the rule that asserted the service exposed exactly + only the baseline retry method) and a documented ADR `docs/adr/0010-recovery-service-scope-lift.md` recording the lift,
So that the recovery service's deeper actions from stories 4.5–4.9 can land without ArchUnit complaining + future contributors understand what scope expanded and why.

**Acceptance Criteria:**

1. **Given** story 1.18 AC11's ArchUnit rule (`RecoveryService` is a scope-protected class — exposes exactly + only `retry` + `describeFailure`), **Then** this story removes that rule from the ArchUnit test class; the class itself is no longer scope-protected; ArchUnit tests passes after removal.
2. **Given** `docs/adr/0010-recovery-service-scope-lift.md`, **Then** the ADR documents: (a) **what was scope-protected** (the original rule + rationale from story 1.18 — preventing accidental scope creep before Epic 4), (b) **what changed** (Epic 4 stories 4.5–4.9 fulfilled the deeper-recovery scope that justified lifting the lock), (c) **what new scope is now allowed** (resume / reconcile / rerunFromStep / pause / classifyFailure methods + their REST endpoints from stories 4.10–4.14), (d) **what scope is still NOT allowed** (no further additions without ADR — the methods listed in (c) are exhaustive for E4; future epics adding more recovery methods need to update this ADR), (e) **how to add a new recovery method in a future version** (process: write ADR explaining need, add method + tests + REST endpoint + UI integration, update this ADR's "what new scope is now allowed" list).
3. **Given** the lift is gated on Epic 4 readiness, **Then** this story explicitly depends on stories 4.5–4.9 being merged (their ACs include "the architecture lift in story 4.28 has occurred" so the deeper methods can be used end-to-end); a merge-gate check asserts these stories are merged before 4.28 itself merges.
4. **Given** that this is the last Epic 4 story by ordering convention (cross-cutting + closure), **Then** Epic 4's foundation-gate-equivalent close gate (mirroring Epic 1 story 1.23 + Epic 2 story 2.29 + Epic 3 story 3.36 closure patterns) requires: stories 4.1–4.27 all merged + story 4.28 lift applied + Operator-validator named (story 4.27 AC12) + documented walkthrough validated.
5. **Given** ADR linkage per stories 1.2 / 3.5 / 3.7 / 3.32 / 3.33 / 4.7 ADRs (numbered 0001 through 0009), **Then** the new ADR is numbered `0010` continuing the sequence; cross-linked from `docs/adr/README.md` index.
6. **Given** the test suite, **Then** tests cover: ArchUnit test class no longer contains the scope-protection rule for `RecoveryService` (verified by a meta-test that asserts the rule's absence), the deeper recovery methods (resume/reconcile/rerun/pause/classify) work end-to-end without triggering ArchUnit failures, the ADR file exists + contains all required sections per AC2.
7. **Given** documentation, **Then** the recovery walkthrough (story 4.27) references this ADR in its "Background" section so operators understand why the deeper recovery actions are now available + the governance trail behind their introduction.
8. **Given** ArchUnit boundary scope, **Then** the lift is narrowly targeted at `RecoveryService` only — other scope-protected classes (e.g., `WorkflowTransitionService` from story 1.5 AC8 + the artifact-operation monopoly rule from story 1.11 AC4) are NOT lifted; their protection remains.
9. **Given** Epic 4 close validation, **Then** this story's merge represents the architectural acknowledgment that Epic 4's scope has landed — cross-epic dependencies that referenced "story 4.28 has occurred" (e.g., story 4.22 AC12) are satisfied.
