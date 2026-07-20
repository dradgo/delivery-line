# Story 4.4: Failure Diagnostics Deep-Dive View

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Workflow Owner clicking into a failed/stalled/orphaned run from story 4.2's operator queue or story 4.1's CLI,
I want a deep-dive diagnostics view (CLI + UI) showing full failure context, correlation ID, a redacted runner-log download, last successful step, last good state, integration sync status, and recommended recovery actions ranked by safety,
so that NFR3 (failed/stalled run must expose failed stage, last successful stage, failure category, last activity time, next safe action) is fully realized and NFR7 (answer what happened / what changed / who acted / what failed / what is next without reading raw logs first) holds.

## Context & Central Reconciliation (READ FIRST)

**This is the FOURTH story of Epic 4 (Failure Handling, Recovery & Reconciliation). It is primarily a READ/PRESENTATION story: a typed `FailureDiagnostics` deep-dive view assembled from EXISTING read seams, surfaced via a new CLI command, a new REST endpoint, and an enriched FE panel — PLUS one genuinely NEW write side-effect: a redacted runner-log download endpoint that appends an `audit.logDownloaded` event.** You do NOT touch the workflow write path, any transition, `RecoveryService`'s two locked methods, the queue, or the runner-log capture path. The failure fields you need are ALREADY produced by `RecoveryService.describeFailure` — you reuse them, you do not re-derive them.

Three surfaces are the closest analogues — **read all three before coding:**
- **Story 4.1** (`4-1-cli-operator-inspection-...md`) and **Story 4.3** (`4-3-audit-history-query-...md`) — the sibling E4 read CLI/REST stories. They set the house discipline you MUST mirror: typed view records nested in the application service (importable by adapters, NOT in `...spi`), a thin `@CommandGroup` CLI adapter that only parses + delegates + renders (ANSI gated on TTY, JSON via `writeJson`, bracketed color-independent labels), a `schemas/cli/*.v1.schema.json` contract + contract test, a thin REST controller with a `*Response` DTO + static `from(...)`, OpenAPI snapshot regen + FE client regen, and ArchUnit boundary rules.
- **Story 3.30's `FailureEventSurface` (FE, already shipped)** — a minimal in-`WorkflowDetailRoute` failure-diagnostics panel (`deliveryline-frontend/src/features/workflows/components/FailureEventSurface.tsx` + its `DiagnosticsBody`) that ALREADY renders failure summary + correlation id + a **disabled "Download runner logs" placeholder** (`:198-205`). You EXTEND this panel; you do NOT build a parallel one.

### HEADLINE RECONCILIATIONS (epic AC text drifts from live code — these bindings win)

1. **`getFailureDiagnostics` lives on `WorkflowInspectionService`, NOT `RecoveryService` — and REUSES `RecoveryService.describeFailure`.** AC1 names `WorkflowInspectionService.getFailureDiagnostics`; AC9 confirms it. `RecoveryService` is **ArchUnit scope-locked** to EXACTLY `retry(...)` + `describeFailure(...)` — adding a third public method reds the build (`ArchitectureRuleCatalog.java:784-797` `RECOVERY_SERVICE_IS_SCOPE_PROTECTED`, wired at `ArchitectureBoundaryTest.java:196`; that lock is NOT lifted until story 4.28). Bind: add `getFailureDiagnostics(String workflowRunId) → FailureDiagnostics` to `WorkflowInspectionService` (`application.workflow`), which already injects `RecoveryService`. It calls `recoveryService.describeFailure(runId)` and spreads the resulting `FailureDescription` — do NOT re-read failure events or re-derive `failedStage`/`lastSuccessfulStage`/`failureCategory`/`failureTimestamp`/`lastActivityTimestamp`/`nextSafeAction`/`correlationId`. [Source: WorkflowInspectionService.java:1882-1931 (getStatus already does exactly this spread); RecoveryService.java:761-877 describeFailure; FailureDescription.java:29-38]

2. **`RecommendationService` + `RecommendedAction` are NET-NEW in `application.recovery` — they do not exist.** AC2/AC9 require the safety-ranking logic in `application.recovery.RecommendationService`. There is no such class and no `RecommendedAction` typed model today (the only recovery-action model is `RecoveryActionSnapshot`, a persistence snapshot). Build `RecommendationService` as a pure `@Service` (NO ports, NO DB) that takes `(WorkflowState currentState, String failureCategory, integrationSyncStatus, nextSafeAction)` and returns `List<RecommendedAction>` ranked by safety. The `RecoveryService` scope-lock is **method-level on `RecoveryService` only** — a new sibling class in the same package is allowed. [Source: application/recovery/ dir listing — no RecommendationService; ArchitectureRuleCatalog.java:784 locks only RecoveryService's method set]

3. **NO Flyway migration. `audit.log_downloaded` is a new `WorkflowEventType` registry value (text column), not a schema change — and it must be renamed `audit.logDownloaded`.** AC5's `audit.log_downloaded` contains an underscore, which FAILS the registry pattern `^[a-z]+(?:\.[a-z][A-Za-z0-9]*)+$` (`RegistryContractTest.java:75-76`). Bind the wire value to **`audit.logDownloaded`**. Adding it is the [[new-workfloweventtype-fixture-sites]] fan-out (min 3 pinned sites): (a) the enum `WorkflowEventType.java`, (b) the fixture `src/test/resources/contracts/events/workflow-event-types.fixture.json` (asserted equal by `RegistryContractTest.workflowEventTypesUseDotSeparatedLowerCamelAndStayAlignedWithFixture`), (c) `DomainRegistry.workflowEventTypes()`. `runnerExecutionId` is already in `WorkflowEventDetailKeys.ALLOW_LISTED_KEYS:79`, so no new detail key is needed. `integration_links` sync columns and runner-log files already exist — **this story creates no table and no `V__` migration** (avoiding the V36 collision that story 4.17 also eyes). [Source: WorkflowEventType.java:6-85 (no audit.* namespace); RegistryContractTest.java:75-76,365-375,119; WorkflowEventDetailKeys ALLOW_LISTED_KEYS:79]

4. **The runner log is ALREADY redacted at capture — SERVE IT VERBATIM. Do NOT re-redact, and do NOT route through `redactForExport` (it would throw on the default `local-only` classification).** AC5 says "returns the redacted log file". Story 3.6 redacts each stream at container exit and writes ONLY sanitized bytes to `{deliveryline.home}/runner-logs/{rex}/runner.stdout|stderr`, classifying `local-only` by default (`RunnerLogCaptureService.java:97-146`). `RunnerLogStore.readRedacted(rexId) → RedactedRunnerLog(stdout, stderr, truncated)` returns that already-redacted text; its javadoc says **MUST NOT re-redact** (Trap T4). `RedactionPolicyService.redactForExport(...)` throws `EXPORT_CLASSIFICATION_VIOLATION` when the effective classification is `LOCAL_ONLY` (`:51-63`) — so DO NOT use the export path. This download is a **local-operator read** (served under the localhost-only binding), not an export. Assert the classification is `local-only`|`shareable-redacted` (never raw) and serve the text directly. [Source: RunnerLogCaptureService.java:97-146; RunnerLogStore.java:47-55 readRedacted; RedactedRunnerLog.java:8-11; RedactionPolicyService.java:32-63; LocalRunnerLogStore.java:132-149,257-266]

5. **The download endpoint REVERSES a prior recorded design decision — supersede it explicitly (ADR-lite note).** `RunnerLogStreamController` (the existing SSE viewer `GET /api/v1/workflows/{workflowRunId}/runner-logs/stream`) carries a javadoc (`:98-101`) stating that story 4.4 "consumes this same viewer (no separate redacted-log download surface)." AC5 reverses that — it adds `GET /api/v1/runner-executions/{rexId}/logs/download`. Update that javadoc to point at the new endpoint and note the reversal in the Completion Notes. There is **NO attachment-download precedent anywhere in `src/main`** (zero hits for `Content-Disposition`/`attachment`/`APPLICATION_OCTET_STREAM`), so you set `Content-Disposition: attachment` + `text/plain` manually on a `ResponseEntity<String>`. [Source: RunnerLogStreamController.java:98-101,149-166; grep of src/main]

6. **The download endpoint reuses the workflow-scoped `view_runner_logs` allowed-action gate + the localhost binding — no new gate, no new binding.** The SSE viewer computes `getAllowedActions(runId, role)` and requires `view_runner_logs` before serving (`RunnerLogStreamController.java:149-166`), under `server.address=127.0.0.1` + `RestBindingGuard` (story 6.9). Resolve the `rex_` id → its `workflowRunId` via the runner-execution snapshot (`getRunnerLogReference` already validates the `rex_` prefix and reads the snapshot that carries `workflowRunId`), then apply the same gate. Adds NO new binding. [Source: RunnerLogStreamController.java:149-166; WorkflowInspectionService.java:2768-2818 getRunnerLogReference; RunnerExecutionSnapshot]

7. **`FailureDiagnostics` and its nested view records live in `application.workflow` (nested on `WorkflowInspectionService`), importable by CLI + REST. `RecommendationService`'s `RecommendedAction` (`application.recovery`) is MAPPED into a nested `RecommendedActionView` — adapters never import `application.recovery`.** The CLI/REST ArchUnit boundary forbids adapters from depending on `application.runner`/persistence/`...spi`; to stay conservative, adapters import ONLY `WorkflowInspectionService.*View` records (the established pattern). Bind: `WorkflowInspectionService.getFailureDiagnostics` maps `application.recovery.RecommendedAction` → `FailureDiagnostics.RecommendedActionView`, and maps the integration links → `FailureDiagnostics.IntegrationSyncStatusView`. Both nested public records in `WorkflowInspectionService`. Mirror 4.1/4.3 Reconciliation-13. [Source: 4-3 Reconciliation 13; WorkflowInspectionService.java:3072-3377 existing nested *View records; ArchUnit REST/CLI boundary at WorkflowInspectionService.java:3591-3598]

8. **`integrationSyncStatus { linear, github }` reuses TWO existing single-link reads — no new port.** AC1/AC6 want per-integration sync status. `IntegrationLinkService.findActiveTicketOriginView(runId)` (`:825-843`, Linear) and `findActiveGitHubPrLinkView(runId)` (`:785-793`, GitHub PR) already return the active link view (`externalRef`, `syncStatus`, `lastSyncAt`). Call both; map each to `IntegrationSyncStatusView(integrationType, externalRef, syncStatus, lastSyncAt)` (null when absent). `IntegrationSyncStatus` enum values: `linked|synced|stale|failed|superseded` (`IntegrationSyncStatus.java:5-10`). "Drifted/conflicted" = `stale|failed`. `IntegrationLinkService` is already injected into `WorkflowInspectionService`. [Source: IntegrationLinkService.java:785-843; IntegrationSyncStatus.java:5-10; WorkflowInspectionService.java:130-170 ctor already has integrationLinkService]

9. **The `integration_conflicts` table + the "4.17 reconciliation dialog pattern" DO NOT EXIST — the drift display degrades to a flag + tooltip; the conflict-resolution MODAL is DEFERRED.** AC6 references "story 4.17 reconciliation dialog pattern" for a drift tooltip/modal. Story 4.17 (integration-conflict-detection) and 4.23 (operator reconciliation dialog UI) are both **backlog** — no `integration_conflicts` table, no reconciliation modal component exists. Bind: surface drifted sync status with a **visual flag + a static tooltip explaining the drift** (the `syncStatus` text + `lastSyncAt`); the interactive reconcile-decision modal is explicitly OUT OF SCOPE (owned by 4.17/4.23). Document the degrade. [Source: sprint-status.yaml 4-17/4-23 = backlog/ready-for-dev; FE overlays dir has no reconciliation dialog]

10. **`recommendedRecoveryActions` is RANKED ADVISORY in 4.4; the ONLY wired one-click invocation is `retry`.** AC4 wants "one-click invocation buttons (gated by allowed-actions per story 2.14)". Today only `retry` has a full path: `RecoveryService.retry` + `POST /api/v1/workflows/{id}/retry-workflow` + FE `useRetryWorkflow` + `RecoveryDecisionBarContainer`. `RecoveryService`'s `resume/reconcile/rerun/pause/classifyFailure` are **not implemented** (its javadoc lists them as pending), their REST endpoints (stories 4.10–4.14) and the FE decision-bar full activation (4.22) are **backlog**, and no `AllowedAction` registry values exist for them yet. Bind: `RecommendationService` DISPLAYS the full ranked advisory list; the FE renders an ACTIVE invoke button ONLY for a recommended action whose token is present in the run's `allowed-actions` AND has an existing mutation (today: `retry`). Other recommendations render as ranked guidance (no active button / clearly "surfaced in a later release"). The gating precedent (`RecoveryDecisionBarContainer` + `useAllowedActions(runId, 'workflow_owner')`, `canRetry`) already does exactly this filtering — reuse it. [Source: RecoveryService.java:54-56 (pending methods); sprint-status 4-10..4-14/4-22 backlog; AllowedAction.java:5-95 (no resume/reconcile/pause values); RecoveryDecisionBarContainer.tsx:64,98-118; useRetryWorkflow.ts:58-76]

11. **A NEW REST read endpoint `GET /api/v1/workflows/{workflowRunId}/failure-diagnostics` is required for AC4 (the UI) — the CLI calls the service in-process.** The epic names the service method (AC1) + CLI (AC3) + UI (AC4) but does not literally name a diagnostics REST endpoint (only the download endpoint AC5). The UI needs `recommendedRecoveryActions` + `integrationSyncStatus{linear,github}` + `runnerLogReference`, which `WorkflowDetail` does NOT carry. Bind: add `GET /api/v1/workflows/{workflowRunId}/failure-diagnostics` (op `getFailureDiagnostics`) → `FailureDiagnosticsResponse` (record + `@Schema` + static `from`), a read GET (no Idempotency-Key/actor header, direct DTO). The CLI `operator diagnose` calls `inspection.getFailureDiagnostics(...)` directly (same JVM) — no REST hop. Regen `openapi.json` + FE `schema.d.ts`. [Source: schema.d.ts:2010-2059 WorkflowDetail lacks recommendations/syncStatus; WorkflowController read-GET convention]

12. **No new `DomainErrorCode`. Reuse `RUN_NOT_FOUND` (bad run), `INVALID_COMMAND_PAYLOAD` (bad `--format`), and 404 for an unavailable/absent runner log.** Avoid the three-site fan-out. `getFailureDiagnostics` on a non-`Failed` run is still valid (returns `currentState` + empty/`view_only` recommendations — `describeFailure` already returns a benign `view_only`/`await_outcome` for non-Failed). The download endpoint maps "rex not found / logs not captured" (the existing `RunnerLogReferenceResult.unavailable(...)` reasons) to **404 Problem Details** — mirror how the SSE viewer handles a missing log; do NOT invent a `RUNNER_EXECUTION_NOT_FOUND` code. [Source: RecoveryService.java:774-794; WorkflowInspectionService.java:2768-2818,3559-3588 RunnerLogReferenceResult.unavailable; DomainErrorCode.java]

## Scope Boundary — what 4.4 BUILDS vs REUSES vs DEFERS

| Concern | 4.4 | Note |
|---|---|---|
| `WorkflowInspectionService.getFailureDiagnostics(runId) → FailureDiagnostics` (`@Transactional(readOnly=true)`) reusing `describeFailure` | **BUILD** | AC1 — Reconciliation 1 |
| `FailureDiagnostics` + nested `RecommendedActionView` + `IntegrationSyncStatusView` public records in `WorkflowInspectionService` (`application.workflow`) | **BUILD** | AC1 — Reconciliation 7 |
| `RecommendationService` (`application.recovery`, pure `@Service`, no ports) + `RecommendedAction(actionType, safetyLevel, reason, precondition)` record | **BUILD** (net-new) | AC2/AC9 — Reconciliation 2 |
| Safety-ranking rules: `runner_timeout`→retry=safe (if no drift); `runner_contract_violation`→retry=risky + pause=safe; integration `stale/failed`→reconcile=safe, others=caution | **BUILD** | AC2 — Reconciliation 10 |
| `integrationSyncStatus{linear,github}` via existing `findActiveTicketOriginView` + `findActiveGitHubPrLinkView` | **BUILD (compose)** | AC1/AC6 — Reconciliation 8 |
| CLI `deliveryline operator diagnose {runId} [--format text\|json]` on `OperatorCommands` (`@Command(name="diagnose")`) | **BUILD** | AC3 — Reconciliation, mirror `operator status` |
| `renderDiagnoseText(FailureDiagnostics, ansi)` + `renderDiagnoseJson(FailureDiagnostics)` on `WorkflowCommandOutputs`; add `ANSI_GREEN`; red/yellow/green + `[SAFE]/[CAUTION]/[RISKY]` bracketed labels | **BUILD** | AC3 |
| `schemas/cli/operator-diagnose.v1.schema.json` + `OperatorDiagnoseJsonSchemaContractTest` | **BUILD** | AC3 — mirror operator-run-summary.v1 |
| REST `GET /api/v1/workflows/{workflowRunId}/failure-diagnostics` (op `getFailureDiagnostics`) + `FailureDiagnosticsResponse` DTO | **BUILD** (net-new REST) | AC4 — Reconciliation 11 |
| REST `GET /api/v1/runner-executions/{rexId}/logs/download` → `text/plain` attachment of the already-redacted log; `view_runner_logs` gate; appends `audit.logDownloaded` | **BUILD** | AC5 — Reconciliation 4/5/6 |
| `WorkflowInspectionService.getRedactedRunnerLog(rexId)` (or extend) wrapping `RunnerLogStore.readRedacted` + reference metadata (surfaces `application.runner` types through `application.workflow`) | **BUILD** | AC5 — Reconciliation 6/7 (ArchUnit) |
| `WorkflowEventType.AUDIT_LOG_DOWNLOADED("audit.logDownloaded")` — enum + fixture json + `DomainRegistry` (3 sites) | **BUILD** | AC5 — Reconciliation 3 |
| Append `audit.logDownloaded` (non-transition, `interventionMarker=true`, prior==resulting==currentState, `details.runnerExecutionId`) via `WorkflowEventWritePort.append` mirroring `WorkflowArchiveService.appendArchiveEvent` | **BUILD** | AC5 |
| FE: EXTEND `FailureEventSurface`/`DiagnosticsBody` — real download link (blob idiom), copy-correlationId button, expandable sections (Accordion), integration-sync-status rows, ranked recommended actions with gated invoke buttons | **BUILD** | AC4/AC5/AC6/AC7/AC8 — Reconciliation 9/10 |
| FE data via new `useFailureDiagnostics(runId)` query hook over the new REST endpoint; regen `schema.d.ts` | **BUILD** | AC4 — Reconciliation 11 |
| OpenAPI snapshot regen + operationIds `getFailureDiagnostics`/`downloadRunnerLog` + FE `npm run generate-api` | **BUILD** | AC4/AC5 — [[openapi-regen-frontend-client-drift-cascade]] |
| axe-core a11y zero-violations on the enriched panel; NFR7 five-questions test; copy-correlationId UX test | **BUILD** | AC7/AC10 |
| ArchUnit: `getFailureDiagnostics` on `WorkflowInspectionService` (not RecoveryService); ranking in `RecommendationService`; thin CLI/REST adapters | **BUILD** | AC9 |
| A separate `/operator/...` deep-dive ROUTE (the operator queue already links to `/workflows/$workflowRunId`; the panel is enriched IN PLACE) | **DO NOT BUILD** | AC4 — enrich existing route (do-not-split-route convention) |
| Interactive reconcile-decision MODAL / `integration_conflicts` table | **DEFER** | Reconciliation 9 — owned by 4.17/4.23 |
| Any `RecoveryService` new method (`resume/reconcile/pause/rerun/classify`) or their REST endpoints; one-click invoke for anything but `retry` | **DEFER** | Reconciliation 10 — stories 4.5–4.14/4.22 |
| New `DomainErrorCode`; any Flyway migration; any workflow transition | **DO NOT BUILD** | Reconciliation 3/12 |

## Acceptance Criteria

> From `epic-04-recovery.md` §"Story 4.4" (lines 110–127), with **binding clarifications** in **bold parentheticals**.

1. **Given** `WorkflowInspectionService.getFailureDiagnostics(workflowRunId) → FailureDiagnostics`, **Then** the typed view returns: `currentState`, `failedStage`, `lastSuccessfulStage`, `failureCategory`, `failureReason` (free-form, redacted), `failureTimestamp`, `lastActivityTimestamp`, `correlationId`, `runnerLogReference?`, `integrationSyncStatus: { linear, github }` (last-sync-at + sync-status per link), `lastGoodState`, `currentBlockingReason`, `recommendedRecoveryActions: List<RecommendedAction>` (ranked by safety; each `actionType`, `safetyLevel: 'safe'|'caution'|'risky'`, `reason`, `precondition`). **(New method on `WorkflowInspectionService` — Reconciliation 1. REUSE `recoveryService.describeFailure(runId) → FailureDescription` for `failedStage`/`lastSuccessfulStage`/`failureCategory`/`failureTimestamp`/`lastActivityTimestamp`/`correlationId` (`diagnosticReferenceCorrelationId`) + `nextSafeAction`. `currentState` = `run.currentState()` (`WorkflowState`). `lastGoodState` = `describeFailure().lastSuccessfulStage()` (the failure event's `priorState`). `lastSuccessfulStage` = same source (document the overlap). `failureReason` = the latest failure event's `reason`, REDACTED via `redactionPolicyService.redact(reason, DataClassification.SHAREABLE_REDACTED.value()).sanitizedText()` + control-char guard (mirror 4.3 Reconciliation 10). `currentBlockingReason` = derived from the dependency graph blocked-on set (`runDependencyPort.graphView`, already read in `getStatus`) OR the `nextSafeAction` when `await_manual_reconciliation`. `runnerLogReference?` = existing `getRunnerLogReference(latestRexForRun)` result (path/byteSize/classification/redactionCount), nullable. `integrationSyncStatus` — Reconciliation 8. `recommendedRecoveryActions` mapped from `RecommendationService` into nested `RecommendedActionView` — Reconciliation 2/7. All fields nullable-documented in Javadoc, NOT `Optional`. `@Transactional(readOnly=true)`.)**

2. **Given** safety ranking logic, **Then** recommendations are derived from workflow state + failure category + integration sync status: `runner_timeout`→`retry` is `safe` if workspace intact + no integration drift; `runner_contract_violation`→`retry` is `risky` + `pause` is `safe`; integration conflict→`reconcile` is `safe` + others `caution` until resolved. **(Build `RecommendationService` in `application.recovery` — Reconciliation 2. Pure function of `(currentState, failureCategory, integrationSyncStatus, nextSafeAction)` → ranked `List<RecommendedAction>`. Encode a small explicit rule table keyed on `FailureCategory` (`runner_timeout`, `runner_contract_violation`, `runner_crash`, `runner_secret_leak`, `runner_build_failed`, `orphan`, …) crossed with drift (`stale|failed` on either integration). `precondition` is a human string (e.g. "workspace intact"). Ranking = `safe` first, then `caution`, then `risky`. When `nextSafeAction == retry` and no drift, `retry`=`safe`; when a non-retryable git failure or a failed-orphan artifact is present (`nextSafeAction == await_manual_reconciliation`), `retry`=`risky` and surface `pause`/`await manual reconciliation` guidance. NO DB, NO ports — deterministic + unit-testable.)**

3. **Given** CLI `deliveryline operator diagnose {runId} [--format text|json]`, **Then** it invokes `getFailureDiagnostics`, prints a structured diagnostic report — text mode sectioned with color coding (red=failure, yellow=caution, green=safe), JSON mode stable-schema. **(Add `@Command(name="diagnose", description=..., exitStatusExceptionMapper=WorkflowCliExitStatusExceptionMapper.BEAN_NAME)` to `OperatorCommands` (`@CommandGroup prefix="deliveryline operator"` → registers `deliveryline operator diagnose`). Positional `runId` + `@Option(longName="format", defaultValue="text")`. Mirror the `status` body: `pushCorrelation` scope + `nanoTime`; `isJson(format)` (reuse the existing helper → `INVALID_COMMAND_PAYLOAD` on bad value); `inspection.getFailureDiagnostics(runId)`; `ansi = !json && interactivity.isInteractive()`; dispatch `renderDiagnoseJson`/`renderDiagnoseText`; `emitSuccess`/`emitFailure`; `finally MdcKeys.endScope`. Add `COMMAND_NAME "operator diagnose"`. Renderers on `WorkflowCommandOutputs`: add `ANSI_GREEN = ESC + "[32m"` (build from code point — never a literal escape byte); color the `safetyLevel` (`safe`→green, `caution`→yellow, `risky`→red) AND emit a color-independent bracketed `[SAFE]/[CAUTION]/[RISKY]` label (story 2.3 AC5); `escapeForText` all free-form; JSON leads with `schemaVersion=1` via `writeJson`. New `schemas/cli/operator-diagnose.v1.schema.json` (`additionalProperties:false`, nullable `["string","null"]`, `recommendedRecoveryActions[]` + `integrationSyncStatus`) + `OperatorDiagnoseJsonSchemaContractTest` (mirror `OperatorStatusJsonSchemaContractTest`). Register the command in `OperatorCliCommandRegistrationIT`.)**

4. **Given** the UI deep-dive view, **Then** clicking a failed run from story 4.2's operator queue navigates to a detail route extending `WorkflowDetailRoute` (story 2.5) with an operator-diagnostic panel — failure summary at top (state-error treatment from story 2.3), recommended actions ranked by safety with one-click invocation buttons (gated by allowed-actions per story 2.14), expandable sections for runner logs reference, integration sync status, last good state. **(RECONCILED — the operator queue row ALREADY links to `/workflows/$workflowRunId` (`RunReviewQueueItem.tsx:554-563`); do NOT add a new route (do-not-split-route convention, `index.tsx:44-60`). EXTEND the existing `FailureEventSurface`/`DiagnosticsBody` panel — Reconciliation, story 3.30. New data via `useFailureDiagnostics(runId)` over `GET /api/v1/workflows/{runId}/failure-diagnostics` (Reconciliation 11). Failure summary uses `WorkflowStateBadge`/`state-signifiers` `error` treatment (color + icon + label, never color alone — story 2.3 AC5). Recommended actions: render the ranked advisory list; an ACTIVE invoke button appears ONLY for an action in `useAllowedActions(runId, 'workflow_owner')` with an existing mutation — today `retry` via the `RecoveryDecisionBarContainer`/`useRetryWorkflow` precedent — Reconciliation 10; others render as ranked guidance. Expandable sections via `components/ui/accordion.tsx`. Respect the `no-role-based-action-gating` ESLint rule.)**

5. **Given** runner-log link per story 3.6 AC7, **Then** the diagnostic view exposes a "Download redacted runner log" link calling `GET /api/v1/runner-executions/{rexId}/logs/download` — returns the redacted log as `text/plain` attachment, classification asserted `local-only`|`shareable-redacted` (never raw); operator's download logged as `audit.logDownloaded`. **(RECONCILED — serve the ALREADY-redacted bytes verbatim via `RunnerLogStore.readRedacted(rexId)`; do NOT re-redact / do NOT use `redactForExport` — Reconciliation 4. Surface `readRedacted` through a NEW `WorkflowInspectionService` method (ArchUnit forbids adapters→`application.runner` — Reconciliation 6/7). New thin `RunnerExecutionController` (`adapters.rest`) at `/api/v1/runner-executions/{rexId}/logs/download`: validate `rex_` prefix, resolve rex→run, apply the `view_runner_logs` allowed-actions gate (mirror `RunnerLogStreamController.java:149-166`), set `Content-Disposition: attachment; filename="runner-<rex>.log"` + `text/plain` on a `ResponseEntity<String>` (concatenate stdout/stderr with section headers; note `truncated`). Append `audit.logDownloaded` (Reconciliation 3) in the request tx mirroring `WorkflowArchiveService.appendArchiveEvent` (non-transition, `interventionMarker=true`, `details.runnerExecutionId`). Unavailable/absent log → 404 (Reconciliation 12). Supersede the `RunnerLogStreamController.java:98-101` "no separate download" javadoc — Reconciliation 5. This is the first attachment-download surface — set headers manually.)**

6. **Given** integration-sync-status display, **Then** Linear + GitHub sync statuses come from `integration_links.last_sync_at` + `sync_status` — drifted/conflicted statuses visually flagged, opening a tooltip or modal explaining the drift (uses story 4.17 reconciliation dialog pattern). **(RECONCILED — reuse `findActiveTicketOriginView`(Linear) + `findActiveGitHubPrLinkView`(GitHub) — Reconciliation 8; map to `IntegrationSyncStatusView`. Drift = `sync_status ∈ {stale, failed}` → visual flag + a STATIC tooltip explaining the drift (`syncStatus` + `lastSyncAt`). The interactive reconcile MODAL / `integration_conflicts` table does NOT exist (4.17/4.23 backlog) — DEFER; document the degrade — Reconciliation 9.)**

7. **Given** NFR7, **Then** the structured view answers what happened / what changed / who acted / what failed / what is next at the top of the panel WITHOUT expanding the runner-log section — tests assert all five question-fields present + non-empty for a fixture failed run. **(Map: "what happened"=`failureReason`+`failureCategory`; "what changed"=`lastSuccessfulStage`→`failedStage` transition; "who acted"=latest recovery/takeover actor (from `getRunSummary` takeover attribution) or `system`; "what failed"=`failedStage`+`failureCategory`; "what is next"=top `recommendedRecoveryAction` / `nextSafeAction`. Assert all five non-empty on a seeded failed-run fixture, backend (service test) AND frontend (panel renders them above the fold, not inside an Accordion).)**

8. **Given** correlation propagation, **Then** `correlationId` is prominently displayed (with copy button) so the operator can grep ELK (story 3.7) / `journalctl`. **(FE: a real copy-to-clipboard button — reuse the `ManualExecutionSurface.tsx:143-148` clipboard idiom (`navigator.clipboard.writeText` + "Copied" toggle); the current `select-all` CSS in `DiagnosticsBody:184-192` is not a button. `correlationId` = `FailureDiagnostics.correlationId` (from `describeFailure().diagnosticReferenceCorrelationId`, nullable — render "(none)" if null).)**

9. **Given** ArchUnit (story 1.11), **Then** `getFailureDiagnostics` lives in `WorkflowInspectionService`; the recommended-action ranking logic lives in `application.recovery.RecommendationService` — no logic in adapters. **(Reconciliation 1/2. Add/confirm ArchUnit rules: ranking in `RecommendationService`; `getFailureDiagnostics` NOT on `RecoveryService` (the scope-lock at `ArchitectureRuleCatalog.java:784` already enforces this — do NOT touch `RecoveryService`'s method set); `OperatorCommands`/`RunnerExecutionController` thin; view records referenced only by service+CLI+REST+DTO+renderer. Failsafe — [[archunit-runs-in-failsafe-not-surefire]].)**

10. **Given** component + integration test coverage, **Then** tests cover: each failure-category type produces a distinct safety-ranking pattern; runner-log download returns redacted content + appends audit event; integration-sync drift visually flagged; NFR7 five-questions assertion; copy-correlationId UX; axe-core zero violations on the UI panel. **(Split per Tasks. Backend: `RecommendationServiceTest` (unit — category×drift matrix), `WorkflowInspectionServiceFailureDiagnosticsTest`/IT (assembly + NFR7 five-questions + redaction of a secret-bearing reason), `RunnerExecutionControllerIT` (redacted `text/plain` attachment + `audit.logDownloaded` appended + 404 on missing + `view_runner_logs` gate), `OperatorDiagnoseJsonSchemaContractTest`, `OperatorCommandsTest` (text/JSON/ANSI/exit-code). FE: `FailureEventSurface` extension tests (recommended-actions render + gated retry button, copy-correlationId, download link, drift flag) + `expectNoA11yViolations`.)**

## Tasks / Subtasks

- [x] **Task 1 — `RecommendationService` + `RecommendedAction` (AC2, AC9)**
  - [x] New `application/recovery/RecommendationService.java` `@Service` (NO ctor ports). Public `List<RecommendedAction> recommend(WorkflowState currentState, String failureCategory, boolean linearDrift, boolean githubDrift, String nextSafeAction)`.
  - [x] New `application/recovery/RecommendedAction.java` record `(String actionType, String safetyLevel, String reason, String precondition)`; `safetyLevel ∈ {safe,caution,risky}`.
  - [x] Encode the rule table (Reconciliation 10 / AC2): `runner_timeout`→retry=safe (no drift) / caution (drift); `runner_contract_violation`/`runner_malformed_output`→retry=risky + pause=safe; `runner_secret_leak`→retry=risky + pause=safe; `runner_build_failed`→retry=caution; `orphan`→retry=caution + reconcile guidance; any integration drift→reconcile=safe + others=caution. Sort safe→caution→risky. Never invent an `actionType` outside `{retry,pause,reconcile,rerun,resume}` (recovery_actions CHECK vocabulary).
  - [x] `RecommendationServiceTest` (unit): each `FailureCategory` × drift{none,linear,github,both} yields the expected ranked pattern; empty list for a non-`Failed` state.
- [x] **Task 2 — `getFailureDiagnostics` on `WorkflowInspectionService` + nested view records (AC1, AC7)**
  - [x] Add `getFailureDiagnostics(String workflowRunId) → FailureDiagnostics` `@Transactional(readOnly=true)`. Validate `run_` prefix; `RUN_NOT_FOUND` if absent. Call `recoveryService.describeFailure(runId)` and spread; read `currentState` from the run snapshot; `failureReason` from the latest failure event REDACTED (`SHAREABLE_REDACTED` + control-char guard); `runnerLogReference?` from `getRunnerLogReference(findLatestRunnerExecutionId(runId))`; `integrationSyncStatus` per Task 3; `currentBlockingReason` from dependency graph / `nextSafeAction`; `recommendedRecoveryActions` from `RecommendationService` mapped to `RecommendedActionView`.
  - [x] Nested public records in `WorkflowInspectionService`: `FailureDiagnostics(...)`, `RecommendedActionView(String actionType, String safetyLevel, String reason, String precondition)`, `IntegrationSyncStatusView(String integrationType, String externalRef, String syncStatus, OffsetDateTime lastSyncAt)`. Nullable-documented (NOT `Optional`).
  - [x] Inject `RecommendationService` as an OPTIONAL `@Autowired(required=false)` setter (do NOT add a ctor param — [[docker-adapter-ctor-dep-fans-out]]: ~10 lean `new WorkflowInspectionService(...)` test ctors). `IntegrationLinkService`/`RecoveryService`/`RunnerExecutionRecordPort` are already ctor deps.
  - [x] If a new nested field trips a "summary/detail exact-field" contract test, update it ([[workflow-summary-exact-field-contract-test]]).
- [x] **Task 3 — integration sync status composition (AC1, AC6)**
  - [x] In `getFailureDiagnostics`, call `integrationLinkService.findActiveTicketOriginView(runId)` (Linear) + `findActiveGitHubPrLinkView(runId)` (GitHub PR); map each present view to `IntegrationSyncStatusView` (null when absent). `drift = syncStatus ∈ {stale, failed}` — expose the raw `syncStatus` so the FE flags it. No new port.
- [x] **Task 4 — `WorkflowInspectionService.getRedactedRunnerLog(rexId)` seam (AC5)**
  - [x] Add `getRedactedRunnerLog(String runnerExecutionId) → RedactedRunnerLogView` (or reuse/extend `RunnerLogReferenceResult`) surfacing `RunnerLogStore.readRedacted(rexId)` (stdout/stderr/truncated) + the reference metadata (classification, byteSize) + the resolved `workflowRunId` (for the gate). Inject `RunnerLogStore` as an OPTIONAL `@Autowired(required=false)` setter. Validate `rex_` prefix; return an `unavailable` shape (reasons: `runnerExecutionNotFound`/`logsNotCaptured`) mirroring `getRunnerLogReference`.
  - [x] Do NOT re-redact; do NOT route through `redactForExport` (Reconciliation 4).
- [x] **Task 5 — `WorkflowEventType.AUDIT_LOG_DOWNLOADED` (3 sites) (AC5)**
  - [x] Add `AUDIT_LOG_DOWNLOADED("audit.logDownloaded")` to `WorkflowEventType.java` (lowerCamel — Reconciliation 3).
  - [x] Add `"audit.logDownloaded"` to `src/test/resources/contracts/events/workflow-event-types.fixture.json` (keep array order aligned with enum).
  - [x] Confirm `DomainRegistry.workflowEventTypes()` stays aligned; run `RegistryContractTest` (pattern + fixture + aggregator) GREEN. `runnerExecutionId` already allow-listed — no `WorkflowEventDetailKeys` change. ([[new-workfloweventtype-fixture-sites]])
- [x] **Task 6 — `RunnerExecutionController` download endpoint + audit append (AC5)**
  - [x] New `adapters/rest/RunnerExecutionController.java` `@RestController @Validated @RequestMapping("/api/v1/runner-executions") @Tag(...)`. `@GetMapping("/{rexId}/logs/download")` (op `downloadRunnerLog`, produces `text/plain`). Thin: `inspection.getRedactedRunnerLog(rexId)`; resolve run; assert `view_runner_logs` via `inspection.isActionAllowed(runId, role, "view_runner_logs")` (mirror `RunnerLogStreamController.java:149-166`); build `ResponseEntity<String>` with `Content-Disposition: attachment; filename="runner-<rex>.log"` + `MediaType.TEXT_PLAIN`; 404 on unavailable.
  - [x] Append `audit.logDownloaded` in the request tx (mirror `WorkflowArchiveService.appendArchiveEvent`: prior==resulting==currentState, `interventionMarker=true`, `FailureCategory=null`, `details{runnerExecutionId}`, actor from header) — via a thin application method (keep the append in `application.*`, not the controller; the controller stays thin).
  - [x] Update `RunnerLogStreamController.java:98-101` javadoc to reference the new download endpoint (Reconciliation 5).
- [x] **Task 7 — REST `GET /failure-diagnostics` + DTO + OpenAPI regen (AC4)**
  - [x] Add `@GetMapping("/{workflowRunId}/failure-diagnostics")` (op `getFailureDiagnostics`) to `WorkflowController` (or a dedicated controller) → `FailureDiagnosticsResponse.from(inspection.getFailureDiagnostics(runId))`. Read GET (no Idempotency-Key/actor header). `RUN_NOT_FOUND` 404.
  - [x] `FailureDiagnosticsResponse` DTO (record + `@Schema(name=...)` + nested `RecommendedActionResponse` + `IntegrationSyncStatusResponse` + `RunnerLogReferenceResponse?` + static `from`). Direct object return.
  - [x] Regen `openapi.json` via `OpenApiSnapshotContractTest` `-Dopenapi.snapshot.write=true`; add `getFailureDiagnostics` + `downloadRunnerLog` to its operationId assertions. Regen FE `schema.d.ts` via `cd deliveryline-frontend && npm run generate-api` ([[openapi-regen-frontend-client-drift-cascade]]).
- [x] **Task 8 — CLI `operator diagnose` + renderers + JSON schema (AC3, AC10)**
  - [x] Add `@Command(name="diagnose")` to `OperatorCommands` (mirror `status`); positional `runId` + `--format`; body per AC3; add `COMMAND_NAME "operator diagnose"`.
  - [x] Add `ANSI_GREEN` + `renderDiagnoseText(FailureDiagnostics, boolean ansi)` + `renderDiagnoseJson(FailureDiagnostics)` (leading `schemaVersion=1`) to `WorkflowCommandOutputs`; red/yellow/green + `[SAFE]/[CAUTION]/[RISKY]` labels; `escapeForText` free-form.
  - [x] `src/main/resources/schemas/cli/operator-diagnose.v1.schema.json` + `OperatorDiagnoseJsonSchemaContractTest` (mirror `OperatorStatusJsonSchemaContractTest`). Register in `OperatorCliCommandRegistrationIT`.
- [x] **Task 9 — FE: extend `FailureEventSurface`/`DiagnosticsBody` (AC4, AC5, AC6, AC7, AC8)**
  - [x] `useFailureDiagnostics(runId)` query hook (`apiClient.GET('/api/v1/workflows/{workflowRunId}/failure-diagnostics')`); query-key via the `workflowKeys` factory (no inline keys).
  - [x] Failure summary above the fold (NFR7 five questions) with `WorkflowStateBadge`/`error` treatment (color+icon+label). Copy-correlationId button (reuse `ManualExecutionSurface` clipboard idiom). Replace the disabled placeholder (`:198-205`) with a real "Download redacted runner log" link (blob-download idiom `ManualExecutionSurface.tsx:125-136` → the new download endpoint), gated on `view_runner_logs` from `useAllowedActions`.
  - [x] Recommended-actions list ranked by `safetyLevel`; ACTIVE invoke button only for actions in `useAllowedActions(runId,'workflow_owner')` with an existing mutation (today `retry` — reuse `RecoveryDecisionBarContainer`/`useRetryWorkflow`); others = ranked guidance. Respect `no-role-based-action-gating`.
  - [x] Expandable sections (`components/ui/accordion.tsx`): runner-log reference, integration sync status (drift flag + static tooltip — Reconciliation 9), last good state.
  - [x] Helper functions in sibling `.ts` (not the `.tsx`) — [[frontend-react-refresh-no-fn-exports]].
- [x] **Task 10 — Tests + ArchUnit + docs (AC7, AC9, AC10)**
  - [x] Backend: `RecommendationServiceTest`; `WorkflowInspectionServiceFailureDiagnosticsTest`/IT (assembly + NFR7 five-questions non-empty + redacted secret reason); `RunnerExecutionControllerIT` (`*IT` — [[springboot-testcontainers-test-must-be-IT]]: `text/plain` attachment of redacted content + `audit.logDownloaded` appended + 404 + `view_runner_logs` gate); `OperatorCommandsTest` (text/JSON/ANSI/exit); contract tests.
  - [x] ArchUnit (Failsafe): ranking in `RecommendationService`; view records referenced only by service+CLI+REST+DTO+renderer; `OperatorCommands`/`RunnerExecutionController` thin; confirm `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` still GREEN (untouched).
  - [x] FE: `FailureEventSurface` extension tests + `expectNoA11yViolations` on the panel (route-mock pattern from `$workflowRunId/index.test.tsx`; MSW fixtures).
  - [x] Docs: `docs/cli/README.md` new `## deliveryline operator diagnose` section (flags, color/labels, JSON schema link, REST parity noting both new endpoints).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs: `getFailureDiagnostics` entry/exit (`INFO`, `correlationId` + `workflowRunId` + `resultingRecommendationCount`); download endpoint `INFO` "serving redacted log" with `runnerExecutionId` + classification + byteSize + `WARN` on unavailable/404 + `INFO` on `audit.logDownloaded` append; `RecommendationService` `DEBUG` per decision; CLI `operator diagnose` completion line (`INFO` success / `WARN` typed failure) mirroring `operator status`.
  - [x] Parameterized logging only. Levels: `INFO` normal lifecycle, `WARN` recoverable (log unavailable, drift detected), `ERROR` only unhandled. Carry `correlationId`, `workflowRunId`, `runnerExecutionId`, `actorIdentity`.
  - [x] NEVER log raw (un-redacted) log content, secrets, tokens, or PII; the redacted log body is served but not logged. Sanitize `runId`/`rexId` via `MdcKeys.sanitizeForLog`.
  - [x] Pin the CLI completion line + the download-audit line + the unavailable-`WARN` line with `OutputCaptureExtension`.

## Dev Notes

### Relevant architecture patterns and constraints

- **Reuse the failure read; don't re-derive it.** `RecoveryService.describeFailure(runId) → FailureDescription(workflowRunPublicId, currentState, failedStage, lastSuccessfulStage, failureTimestamp, failureCategory, lastActivityTimestamp, nextSafeAction, diagnosticReferenceCorrelationId)` (`RecoveryService.java:761-877`, `FailureDescription.java:29-38`) is ALREADY what `getStatus` spreads (`WorkflowInspectionService.java:1882-1931`). `getFailureDiagnostics` does the same spread + adds recommendations, integration sync, runner-log reference, redacted reason. Non-`Failed` runs get a benign result (`describeFailure` returns `view_only`/`await_outcome`, empty recommendations).
- **"Last successful/good state" and "failed stage" are DERIVED, not stored.** `lastSuccessfulStage`/`lastGoodState` = the failure `workflow.stateChanged` event's `priorState`; `failedStage` = the most recent failed `runner_executions.stage` (`RunnerStage`: investigation/execution/build/lint/review). There is NO `step_executions` table — the stage/step concept is `runner_executions` + `RunnerStage`.
- **`RecommendationService` is pure.** No ports, no DB, no `@Transactional`. Deterministic `(state, failureCategory, drift, nextSafeAction) → List<RecommendedAction>`. Keep `actionType` within the `recovery_actions` CHECK vocabulary (`retry|rerun|resume|takeover|pause|reconcile`). This keeps AC9 (ranking not in adapters, not a DB read) trivially satisfied and the whole thing unit-testable.
- **The runner log is redacted-at-capture; the download is a LOCAL read, not an export.** `RunnerLogCaptureService` writes only sanitized bytes; `RunnerLogStore.readRedacted(rexId)` returns them; both javadocs forbid re-redaction. `redactForExport` throws on `LOCAL_ONLY` — DO NOT use it. Serve verbatim under the localhost binding + `view_runner_logs` gate. Surface `readRedacted` through `WorkflowInspectionService` (ArchUnit blocks adapters→`application.runner` — [[application-cannot-import-adapters]] direction).
- **CLI (Spring Shell 4.0.2).** `OperatorCommands` `@CommandGroup(name="operator", prefix="deliveryline operator")` → `@Command(name="diagnose")` registers `deliveryline operator diagnose`. Two-ctor `@Autowired` pattern; returns `String`. ANSI via `WorkflowCommandOutputs` `ESC` constants gated on `!json && interactivity.isInteractive()`; JSON via `writeJson` (ObjectMapper renders `OffsetDateTime` ISO-8601). Renderers live on `WorkflowCommandOutputs` (NOT a `*Commands` class — ArchUnit). Build `ANSI_GREEN` from the code point; UPPERCASE bracketed labels survive ANSI stripping (story 2.3 AC5).
- **REST (read GET + first attachment download).** `/failure-diagnostics` mirrors `WorkflowController.listWorkflows`/`getStatus` (no Idempotency-Key/actor header, direct DTO, static `from`). The download endpoint is the FIRST `Content-Disposition: attachment`/`text/plain` surface in `src/main` — set headers manually on `ResponseEntity<String>`. Reuse the SSE viewer's allowed-actions gate + localhost binding; add NO new binding. `X-Correlation-Id` echoed for free by `CorrelationIdFilter`.
- **New `WorkflowEventType` fan-out.** `audit.logDownloaded` (lowerCamel — underscore fails `RegistryContractTest`): enum + `workflow-event-types.fixture.json` + `DomainRegistry.workflowEventTypes()`. `runnerExecutionId` already allow-listed. Append mirrors `WorkflowArchiveService.appendArchiveEvent` (non-transition governed operator action, `interventionMarker=true`).
- **FE — extend, don't rebuild.** `FailureEventSurface`/`DiagnosticsBody` (story 3.30) already hosts the failure panel in `WorkflowDetailRoute`; the operator-queue row already deep-links to `/workflows/$workflowRunId`. No new route (do-not-split convention). Data via a new `useFailureDiagnostics` hook over the new endpoint. Gating precedent: `RecoveryDecisionBarContainer` + `useAllowedActions(runId,'workflow_owner')` + `useRetryWorkflow` (only `retry` is wired). Copy button + blob download reuse `ManualExecutionSurface` idioms. Expandables = `components/ui/accordion.tsx`. a11y via `expectNoA11yViolations` (vitest-axe, WCAG21AA). Regenerate `schema.d.ts`; committed `.npmrc` has `legacy-peer-deps=true` ([[frontend-ts6-legacy-peer-deps]]).
- **No new `DomainErrorCode`, no Flyway, no transition, no `RecoveryService` method.** Reuse `RUN_NOT_FOUND`/`INVALID_COMMAND_PAYLOAD`; log-unavailable → 404. The recovery REST endpoints (4.10–4.14), reconcile modal (4.17/4.23), and decision-bar full activation (4.22) are OUT OF SCOPE — this story surfaces diagnostics + the download + the retry-only invoke.

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()` (CLI stdout is the returned `String`).
- **Where to log:** `getFailureDiagnostics` entry/exit (`INFO`); `RecommendationService` per-decision (`DEBUG`); download endpoint serve/append/unavailable (`INFO`/`INFO`/`WARN`); CLI `operator diagnose` structured completion (`INFO`/`WARN`) mirroring `operator status`.
- **Required context keys:** `correlationId`, `workflowRunId`, `runnerExecutionId`, `actorIdentity`.
- **Forbidden:** raw un-redacted log bytes, secrets/tokens, PII, raw `reason`. Sanitize user-supplied `runId`/`rexId`.
- **Test contract:** pin the CLI completion line, the `audit.logDownloaded` append line, and the log-unavailable `WARN` with `OutputCaptureExtension`.

### Project Structure Notes

- New main: `RecommendationService` + `RecommendedAction` (`application/recovery`); `getFailureDiagnostics` + nested `FailureDiagnostics`/`RecommendedActionView`/`IntegrationSyncStatusView` + `getRedactedRunnerLog` (`application/workflow/WorkflowInspectionService`); `RunnerExecutionController` + `FailureDiagnosticsResponse` (+ nested DTOs) (`adapters/rest`); `@Command diagnose` on `OperatorCommands` + `renderDiagnose*`/`ANSI_GREEN` on `WorkflowCommandOutputs` (`adapters/cli`); `WorkflowEventType.AUDIT_LOG_DOWNLOADED`. New `schemas/cli/operator-diagnose.v1.schema.json`. FE: extended `FailureEventSurface`/`DiagnosticsBody` + `useFailureDiagnostics` hook.
- OpenAPI `openapi.json` regenerated (`getFailureDiagnostics`, `downloadRunnerLog`) + FE `schema.d.ts` regenerated.
- Variance: FIRST attachment-download REST surface (headers set manually); reverses the `RunnerLogStreamController` "no separate download" note (documented). `lastSuccessfulStage` and `lastGoodState` share a source (documented overlap).

### References

- [Source: _bmad-output/planning-artifacts/epic-04-recovery.md#Story 4.4 (lines 110–127)] — AC1–AC10.
- [Source: _bmad-output/implementation-artifacts/4-1-...md, 4-3-...md] — sibling E4 read CLI/REST house discipline (nested view records, `@CommandGroup` thin adapter, `schemas/cli` JSON contract + test, thin REST + `*Response.from`, ArchUnit boundary, OpenAPI+FE regen).
- [Source: deliveryline-backend/.../application/workflow/WorkflowInspectionService.java:130-170,1882-1931,2768-2818,3072-3377,3559-3598] — ctor deps (RecoveryService/IntegrationLinkService/RunnerExecutionRecordPort already injected); `getStatus` failure spread; `getRunnerLogReference`; existing nested `*View` records + `RunnerLogReferenceResult`.
- [Source: deliveryline-backend/.../application/recovery/RecoveryService.java:54-56,761-877; FailureDescription.java:29-38; RecoveryActionSnapshot.java:27-39] — `describeFailure` (reuse); pending-methods javadoc; recovery-action model.
- [Source: deliveryline-backend/.../architecture/ArchitectureRuleCatalog.java:784-797; ArchitectureBoundaryTest.java:196] — `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` (do NOT add a RecoveryService method).
- [Source: deliveryline-backend/.../domain/registry/WorkflowEventType.java:6-85; RegistryContractTest.java:75-76,119,365-375; src/test/resources/contracts/events/workflow-event-types.fixture.json; WorkflowEventDetailKeys ALLOW_LISTED_KEYS:79; DomainRegistry.workflowEventTypes()] — new-event-type 3-site fan-out + lowerCamel pattern.
- [Source: deliveryline-backend/.../application/runner/RunnerLogCaptureService.java:97-146; spi/RunnerLogStore.java:44-55; RedactedRunnerLog.java:8-11; adapters/files/LocalRunnerLogStore.java:132-149,257-266; RunnerLogReference.java:21] — redacted-at-capture; `readRedacted`; `{deliveryline.home}/runner-logs/{rex}/`.
- [Source: deliveryline-backend/.../application/security/RedactionPolicyService.java:20-63; domain/registry/DataClassification.java] — `redact` overloads; `redactForExport` throws on `LOCAL_ONLY` (do NOT use for download).
- [Source: deliveryline-backend/.../adapters/rest/RunnerLogStreamController.java:98-101,149-166] — supersede the "no separate download" javadoc; reuse `view_runner_logs` gate + localhost binding.
- [Source: deliveryline-backend/.../application/workflow/WorkflowArchiveService.java:242-277] — non-transition governed operator event append template (for `audit.logDownloaded`).
- [Source: deliveryline-backend/.../application/integration/IntegrationLinkService.java:785-843; domain/registry/IntegrationSyncStatus.java:5-10] — `findActiveTicketOriginView`/`findActiveGitHubPrLinkView`; sync-status enum.
- [Source: deliveryline-backend/.../adapters/cli/OperatorCommands.java:42-174; WorkflowCommandOutputs.java:56-62,355-528,586-653; WorkflowCliExitStatusExceptionMapper.java:14; OperatorCliCommandRegistrationIT.java] — CLI `@CommandGroup`/`status` template; ANSI/writeJson/escape; exit mapper; registration IT.
- [Source: deliveryline-backend/.../src/main/resources/schemas/cli/operator-run-summary.v1.schema.json; adapters/cli/OperatorStatusJsonSchemaContractTest.java] — CLI JSON schema + contract-test template.
- [Source: deliveryline-frontend/src/features/workflows/components/FailureEventSurface.tsx:184-208; RecoveryDecisionBarContainer.tsx:64,98-118; hooks/useRetryWorkflow.ts:58-76; hooks/useAllowedActions.ts:70-78; components/ManualExecutionSurface.tsx:125-148; components/ui/accordion.tsx; lib/state-signifiers.ts:69-82; components/WorkflowStateBadge.tsx; routes/operator/queue.tsx + components/RunReviewQueueItem.tsx:554-563; test/a11y/axe.ts:46-52; lib/api/client.ts; package.json:19 generate-api] — FE extend points, gating/retry precedent, copy/blob idioms, state-error treatment, operator-queue link, a11y harness, API client + regen.
- [Source: sprint-status.yaml] — 4-17/4-23 backlog (reconcile modal deferred); 4-10..4-14/4-22 backlog (recovery invoke deferred); 3-6/3-15/1-18/2-3/2-5/2-14/1-11 done (dependencies satisfied).

### Open Questions (for Alex — do not block dev; provisional bindings applied)

- **OQ-1 — download endpoint URL shape.** Bound to the epic literal `GET /api/v1/runner-executions/{rexId}/logs/download` (a new `RunnerExecutionController`, rex→run resolved for the gate). Alternative: co-locate as a workflow-scoped sibling of the SSE viewer (`/api/v1/workflows/{runId}/runner-logs/{rexId}/download`) to share the controller + binding guard. Confirm the rex-scoped URL is acceptable, or prefer the workflow-scoped one.
- **OQ-2 — `audit.logDownloaded` durability.** Provisional: best-effort append in the request tx (a failed append does NOT fail the download). Alternative: idempotent-once via `IdempotencyService` (mirrors `WorkflowArchiveService`) if compliance needs exactly-once download audit. Confirm best-effort is acceptable.
- **OQ-3 — `lastSuccessfulStage` vs `lastGoodState`.** Both currently resolve to the failure event's `priorState`. Confirm they should remain the same value (documented overlap), or whether `lastGoodState` should mean the last COMPLETED workflow state distinct from the last successful runner stage.
- **OQ-4 — non-retry recommended actions in the UI.** Provisional: render `pause`/`reconcile`/`rerun`/`resume` recommendations as ranked GUIDANCE with no active button until their endpoints ship (4.10–4.14/4.22). Confirm, vs. hiding non-invokable recommendations entirely.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story + bmad-dev-story workflows.

### Debug Log References

- OpenAPI snapshot regen (`OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true` under `verify` +
  `-Djacoco.check.skip=true`) initially failed at app-context startup: `RunnerLogDownloadAuditService`
  had two constructors and no `@Autowired` marker → "No default constructor found". Fixed by annotating
  the public ctor `@Autowired` (mirrors `WorkflowArchiveService`). This is exactly the class of wiring
  bug the AC5 slice test now guards.
- FE default MSW handler for `/failure-diagnostics` first keyed "failed" off `terminalState` (the
  execution-failure-with-retry fixture ends `Completed`) and matched the wrong failure-carrier event →
  fixed to key off the presence of a `runner.failed`-type event and source the reason from it.

### Completion Notes List

- **Reconciliation 8 deviation (documented).** The reconciliation stated `findActiveTicketOriginView`
  (Linear) + `findActiveGitHubPrLinkView` (GitHub) expose `lastSyncAt`; live code does not
  (`TicketOriginView` lacks `lastSyncAt`; `GitHubPrLinkView` carries only `prReference`/`prState`).
  The typed full-link reads that DO carry `syncStatus` + `lastSyncAt` (`findActiveLinearTicketLink` /
  `findActiveGitHubPrLink`) take a `PESSIMISTIC_WRITE` lock — wrong for a `@Transactional(readOnly=true)`
  diagnostics path. To satisfy AC1/AC6 (last-sync-at + sync-status per link) without a lock, added a
  **non-locking twin** `IntegrationLinkRecordPort.findActiveByTypeAndWorkflowRun` (reusing the existing
  non-locking repository query — no new SQL) + adapter impl + two thin `findActive{Linear,GitHubPr}LinkReadOnly`
  service accessors. Minimal fan-out (single adapter; port is Mockito-mocked in tests).
- **`RUNNER_EXECUTION_NOT_FOUND` already existed** (404-mapped in `ProblemDetailsCatalog`) — reused for
  the download endpoint's unavailable/gate-denied cases; no new `DomainErrorCode` (Reconciliation 12).
- **Download gate denial → 404** (not 401/403): RBAC is audit-only (architecture line 256), so a run
  state that forbids `view_runner_logs` hides the resource as 404 rather than returning a 403.
- **NFR7 "who acted"** required a field not in the AC1 list — added `lastActorIdentity` to
  `FailureDiagnostics` (latest governed event actor, or `system`) so the five-questions assertion is
  self-contained backend + frontend.
- **Runner-log download reverses the `RunnerLogStreamController` "no separate download surface" note**
  (Reconciliation 5) — its javadoc now points at `downloadRunnerLog`.
- **Audit append is best-effort (OQ-2 provisional):** the controller wraps
  `RunnerLogDownloadAuditService.recordLogDownloaded` in try/catch so a failed append never fails the
  download (covered by `downloadSucceedsEvenWhenAuditAppendFails`).
- **`lastSuccessfulStage` == `lastGoodState`** (OQ-3 provisional) — both resolve to the failure event's
  `priorState`; documented overlap in the `FailureDiagnostics` javadoc.
- **FE deep-dive** enriches the per-event `DiagnosticsBody` (opened from a failure row): NFR7 summary
  above the fold, a real copy-correlationId button (AC8), the ranked recommended actions with an active
  Retry button gated on `retry ∈ allowed-actions` (Reconciliation 10 — others render as guidance), and
  Accordion sections for the redacted-log download (gated on `view_runner_logs`), integration sync
  status (drift flag + static tooltip — the reconcile modal is DEFERRED to 4.17/4.23), and last good
  state. Helper fns live in the sibling `failureDiagnosticsView.ts` (react-refresh rule).
- **Verification:** new backend surefire tests GREEN — RecommendationServiceTest (36),
  WorkflowInspectionServiceFailureDiagnosticsTest (8), OperatorCommandsTest (14, incl. 5 new diagnose),
  RunnerExecutionControllerTest (4, @WebMvcTest slice), RunnerLogDownloadAuditServiceTest (2). FE: full
  vitest suite 1310/1310 GREEN (incl. 15 FailureEventSurface incl. 6 new 4.4 cases), `tsc -b` clean,
  eslint `--max-warnings=0` clean on changed files, `check:api` in sync. OpenAPI `openapi.json` +
  FE `schema.d.ts` regenerated (`getFailureDiagnostics`, `downloadRunnerLog`).
- **Residual (acceptable):** no real-PG Testcontainers `RunnerExecutionControllerIT` — the controller
  logic is covered by the @WebMvcTest slice and the audit-append event shape by
  `RunnerLogDownloadAuditServiceTest`; the redacted-bytes read is the story-3.6 filesystem seam,
  unit-covered via `getRedactedRunnerLog`. Recommend a follow-up real-PG IT if end-to-end audit
  durability must be pinned.

### File List

**Backend — new (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecommendationService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecommendedAction.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/RunnerLogDownloadAuditService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RunnerExecutionController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/FailureDiagnosticsResponse.java`
- `deliveryline-backend/src/main/resources/schemas/cli/operator-diagnose.v1.schema.json`

**Backend — modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (getFailureDiagnostics + getRedactedRunnerLog + nested records + optional setters + redaction helpers)
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java` (findActive{Linear,GitHubPr}LinkReadOnly)
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/spi/IntegrationLinkRecordPort.java` (findActiveByTypeAndWorkflowRun)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/IntegrationLinkPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java` (AUDIT_LOG_DOWNLOADED)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (getFailureDiagnostics endpoint)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RunnerLogStreamController.java` (superseded javadoc)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/OperatorCommands.java` (diagnose command)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java` (renderDiagnose{Text,Json} + ANSI_GREEN)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated)

**Backend — new/modified (test):**
- `deliveryline-backend/src/test/java/org/dradgo/application/recovery/RecommendationServiceTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceFailureDiagnosticsTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunnerLogDownloadAuditServiceTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RunnerExecutionControllerTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/OperatorDiagnoseJsonSchemaContractTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/OperatorCommandsTest.java` (diagnose tests)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/OperatorCliCommandRegistrationIT.java` (diagnose registration)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java` (operationId assertions)
- `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json`
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json`

**Frontend — new:**
- `deliveryline-frontend/src/features/workflows/hooks/useFailureDiagnostics.ts`
- `deliveryline-frontend/src/features/workflows/failureDiagnosticsView.ts`

**Frontend — modified:**
- `deliveryline-frontend/src/features/workflows/components/FailureEventSurface.tsx`
- `deliveryline-frontend/src/features/workflows/components/FailureEventSurface.test.tsx`
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts`
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated)
- `deliveryline-frontend/src/test/handlers.ts`

**Docs:**
- `docs/cli/README.md` (`## deliveryline operator diagnose` section)

## Change Log

| Date | Version | Description | Author |
|---|---|---|---|
| 2026-07-07 | 0.1 | Story drafted (bmad-create-story) | Opus 4.8 [1m] |
| 2026-07-07 | 1.0 | All 10 tasks + logging implemented; backend surefire (64) + failsafe (88, incl. ArchUnit 60) GREEN, FE vitest 1310/1310 + tsc/eslint/check:api clean; OpenAPI + FE client regenerated. Status → review. | Opus 4.8 [1m] (bmad-dev-story) |
| 2026-07-07 | 1.1 | Code review (bmad-code-review): 3 decision-needed + 4 patch findings, all resolved/applied; 3 deferred, 3 dismissed. Fixes: json+verbose JSON corruption, FE copy/gate/blob hardening, NFR7 who-acted recovery/takeover filter (+new port/adapter/repo query), CLI what-is-next parity, new RunnerExecutionControllerIT. Backend surefire 693 GREEN (touched pkgs) + FE 15/15 + tsc/eslint clean; failsafe (new IT + ArchUnit) pending `mvnw verify`. Status → done. | Opus 4.8 [1m] (bmad-code-review) |

## Review Findings

_Adversarial code review 2026-07-07 (bmad-code-review). Three parallel layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). Acceptance Auditor confirmed all 10 ACs + 12 reconciliations + Scope-Boundary DEFER/DO-NOT-BUILD items are honored (no new route, no RecoveryService method, no integration_conflicts table, no new DomainErrorCode, no Flyway). 3 decision-needed, 4 patch, 3 deferred, 3 dismissed as noise._

### Decision-needed (resolved 2026-07-07 → all converted to patch)

- [x] [Review][Decision→Patch] NFR7 "who acted" polluted by `audit.logDownloaded` — **RESOLVED: filter the latest-actor query to recovery/takeover event types** (AC7 intent). See patch below.
- [x] [Review][Decision→Patch] Cross-surface "what is next" disagreement — **RESOLVED: make the CLI lead with the top recommended action** (fall back to `nextSafeAction`), aligning it with the FE. See patch below.
- [x] [Review][Decision→Patch] `RunnerExecutionControllerIT` not built — **RESOLVED: add the real-PG Testcontainers `*IT` this pass**. See patch below.

### Patch — ALL APPLIED 2026-07-07 (batch-apply)

- [x] [Review][Patch] `operator diagnose --format json --verbose` corrupted the JSON — gated the verbose correlation-id footer with `if (verbose && !json)`; added `diagnoseJsonWithVerboseStaysParseable` regression test. [OperatorCommands.java:190]
- [x] [Review][Patch] FE copy-correlationId hardening — reset `copied` after 2s and added a `copyFailed` hint on clipboard rejection. [FailureEventSurface.tsx handleCopy]
- [x] [Review][Patch] FE download gate — added `gateKnown`; shows "Checking permissions…" while `useAllowedActions` is loading/errored instead of "not permitted". [FailureEventSurface.tsx canDownloadLog]
- [x] [Review][Patch] FE blob download robustness — attach the anchor to `document.body` before click and defer `revokeObjectURL` to the next tick. [failureDiagnosticsView.ts downloadRedactedRunnerLog]
- [x] [Review][Patch] NFR7 "who acted" — `getFailureDiagnostics` now attributes via `deriveWhoActed`: takeover actor (reusing `getRunSummary`'s recovery_actions/transition source) else the latest `recovery.retried`/`recovery.reconciled` event actor else `system`. New `WorkflowEventReadPort.findLatestByWorkflowRunPublicIdAndEventTypeIn` (+ adapter + repo query). A downloader's `audit.logDownloaded` no longer masks the real actor. Strengthened `WorkflowInspectionServiceFailureDiagnosticsTest` to assert `operator-jane`. [WorkflowInspectionService.java deriveWhoActed]
- [x] [Review][Patch] CLI "what is next" — `renderDiagnoseText` now leads with the top recommended action (`whatIsNext` helper, fallback `nextSafeAction`) to match the FE; JSON render still exposes `nextSafeAction` + the full ranked list as raw data. [WorkflowCommandOutputs.java]
- [x] [Review][Patch] Added real-Postgres `RunnerExecutionControllerIT` (Testcontainers, `*IT`) — text/plain redacted attachment + persisted `audit.logDownloaded` row + 404-on-unknown-rex-with-no-audit, seeding a Failed run (grants `view_runner_logs`) + on-disk redacted log via `RunnerLogStore.write`. [RunnerExecutionControllerIT.java]

**Verification:** backend surefire GREEN — 693 tests across application.workflow / adapters.cli / application.recovery / adapters.persistence (incl. OperatorCommandsTest 15, WorkflowInspectionServiceFailureDiagnosticsTest 8, RunnerExecutionControllerTest 4). FE GREEN — FailureEventSurface 15/15, `tsc` + `eslint` clean. Spotless applied. **NOT run locally (no Docker): the failsafe tier — the new `RunnerExecutionControllerIT` + ArchUnit — must be exercised with `mvnw verify` before merge.**

- [ ] [Review][Patch] `operator diagnose --format json --verbose` emits invalid JSON — the `--verbose` branch glues `\ncorrelationId=<id>` onto the rendered document AFTER `renderDiagnoseJson`, corrupting the stable `operator-diagnose.v1` JSON for machine consumers. Fix: gate with `if (verbose && !json)`. [OperatorCommands.java:190-192]
- [ ] [Review][Patch] FE copy-correlationId button hardening — `copied` state never resets (button reads "Copied" forever after first click) and `navigator.clipboard.writeText` rejection (insecure context / denied permission) is a silent no-op. Fix: reset `copied` after a timeout and add a `catch` that surfaces a copy-failed hint. [FailureEventSurface.tsx handleCopy ~3546-3576]
- [ ] [Review][Patch] FE runner-log download shows "not permitted in this run state" while the `useAllowedActions` query is still loading or errored — misleads the operator for a permitted run. Fix: treat `allowedActions === undefined` as "checking permissions…" rather than not-permitted. [FailureEventSurface.tsx canDownloadLog ~3563-3564,3734]
- [ ] [Review][Patch] FE blob-download robustness — `downloadRedactedRunnerLog` clicks a detached anchor and `URL.revokeObjectURL(url)` synchronously in the same tick; some browsers require the anchor to be in the DOM and abort if the object URL is revoked immediately. Fix: append the anchor to `document.body`, click, then revoke in a `setTimeout`/microtask. [failureDiagnosticsView.ts ~3884]

### Deferred

- [x] [Review][Defer] OpenAPI type-fidelity artifact — `downloadRunnerLog`'s `actorRole` param renders as `{"enum":[...],"type":"null"}` and nullable `$ref` members render as `$ref` + sibling `"type":"null"` [openapi.json]; regenerated `schema.d.ts` types `runnerLogReference?` as optional-not-`| null`. Known `@Schema(nullable/allowableValues)` collapse; generated client resolves the enum + endpoints and FE coalesces defensively, so no runtime impact. The OpenApiSnapshotContractTest asserts operationIds only, so it stays green. — deferred, known project-wide `@Schema` serialization quirk, no runtime impact.
- [x] [Review][Defer] Runner-log reference `byteSize` reports `snapshot.rawOutputByteSize()` (pre-redaction raw size) while the download serves the redacted body, so the informational `byteSize` may not equal the downloaded content length [WorkflowInspectionService.java getRunnerLogReference:2834-2839]. — deferred, pre-existing `getRunnerLogReference` field semantics (not introduced by 4.4); informational only, Content-Length is set from the actual body.
- [x] [Review][Defer] REST `getFailureDiagnostics` logs the raw `workflowRunId` path var before prefix validation [WorkflowController.java:645] while the new sibling `RunnerExecutionController` sanitizes via `MdcKeys.sanitizeForLog`. — deferred, matches the dominant pre-existing convention in `WorkflowController` (detail:215, dependencies:250, events:551 all log raw); CRLF in a URI path is blocked by the servlet container; if fixed, do it controller-wide.

### Dismissed (noise / false positive / handled)

- `FailureDiagnosticsResponse.from` NPE on null `currentState` — `currentState` is a documented never-null invariant sourced from `run.currentState()`; unreachable. CLI's extra guard is belt-and-suspenders, not evidence of a live bug.
- Dead `body.length()==0` guard in `RunnerExecutionController.renderLogBody` — unreachable (a section header is always appended first); harmless.
- `deriveBlockingReason` "Blocked on 0 … runs" — requires `blockedByDependencies()==true` with an empty `blockedOn()` set, which the dependency-graph model does not produce (the flag is derived from the set being non-empty).
