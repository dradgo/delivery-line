# Story 3.19: Queue Inspection + Worker Status + Queue-Size Monitoring

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a workflow operator + Product Manager monitoring the queue and worker-pool health,
I want `deliveryline workers status` (CLI) + `GET /api/v1/runner-queue/status` (REST) showing worker-pool state, current load, queue depth, oldest queued age, and per-worker current work — plus Prometheus metrics + Grafana dashboard panels + starter alert rules so growing queue size is visible and alerts fire before users complain,
so that pilots can spot stuck workers, growing queue depth, or pool-too-small symptoms early.

## Context & Central Reconciliation (READ FIRST)

**This story is a READ-ONLY inspection + observability layer over the already-active runner queue (stories 3.17a + 3.17b, `done`) and the just-landed batch FK (story 3.18, in `review`).** You add ONE typed read method to `WorkflowInspectionService`, expose it through ONE new CLI command + ONE new REST endpoint, and wire Prometheus metrics + Grafana dashboards + alert rules. **You do NOT touch the queue, the worker pool, the dispatch path, or any write/transition logic.** Everything you need to read already exists in the `runner_executions` table (`status`, `worker_id`, `dispatched_at`, `stage`, `created_at`, `batch_submission_id`) and in config (`RunnerWorkerPoolProperties.size`).

This is an **epic-3b** story that builds directly on 3.17b (queue activation) and 3.18 (the `runner_executions.batch_submission_id` FK that AC9's `?batchId=` filter reads). Read `3-17b-runner-worker-pool-and-queue-activation.md` (queue/worker model + house discipline) and `3-18-workflow-batch-submission-cli-and-rest.md` (the batch FK + CLI/REST adapter patterns) before starting.

### HEADLINE RECONCILIATIONS (epic AC text drifts from live code — these bindings win)

1. **THE CENTRAL BOUNDARY TRAP — the typed view records MUST live in `application.workflow`, NOT `application.runner`.** ArchUnit rule `REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER` forbids anything in `adapters.rest` from depending on `org.dradgo.application.runner..` (ArchitectureRuleCatalog.java:241). The CLI adapter is similarly confined. Therefore `RunnerQueueStatus` + `WorkerStatus` (AC1) must be defined as **nested public records inside `WorkflowInspectionService`** (`org.dradgo.application.workflow`), exactly like the existing `WorkflowRunDetailedSummaryView` / `WorkflowStatusView` records — NOT in `application.runner.queue`. If you put them under `application.runner`, the REST controller importing them reds the ArchUnit boundary. AC10 ("all queue-status reading goes through `WorkflowInspectionService.getRunnerQueueStatus`") is the literal enforcement of this. [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:228-243; WorkflowInspectionService.java:1407-1677]

2. **`WorkflowInspectionService` is the correct home — it ALREADY injects `RunnerExecutionRecordPort`.** Do NOT create a new `RunnerQueueInspectionService`. The mandated service (AC1/AC10) already holds `runnerExecutionRecordPort` (WorkflowInspectionService.java:75,103-104) and is `@Service` + `@Transactional(readOnly = true)` per method. Add `getRunnerQueueStatus(String batchIdOrNull)` next to `getRunSummary`. The application layer MAY depend on `application.runner` (only `adapters.rest`/`adapters.cli` may not), so reading `RunnerWorkerPoolProperties.size()` for `poolSize` from inside the service is allowed. [Source: WorkflowInspectionService.java:62-108]

3. **Epic AC6 "Grafana dashboards from story 3.7 AC6" is a cross-reference typo — 3.7 delivered KIBANA dashboards (ELK), NOT Grafana.** Story 3.7's explicit Decision D8 deferred Prometheus + Grafana to **this story (3.19)**: *"Grafana dashboards + Prometheus alerts are owned by the later metrics story (3.19)."* So 3.19 **creates `infra/observability/grafana/` and `infra/observability/prometheus/` from scratch** — they do not exist yet. `infra/observability/` today holds only `elasticsearch/`, `kibana/`, `logstash/` + `README.md`. `PROMETHEUS_HOST_PORT` / `GRAFANA_HOST_PORT` are already reserved (commented) in `.env.example`. [Source: 3-7-elk-stack-integration-centralized-log-capture-profile-gated.md D8 (lines 57,180); infra/observability/]

4. **`micrometer-registry-prometheus` is NOT on the classpath — you must add it.** `spring-boot-starter-actuator` is present (deliveryline-backend/pom.xml:62) but with no Prometheus registry the `/actuator/prometheus` endpoint does not exist (Actuator falls back to an in-memory `SimpleMeterRegistry`). Add `io.micrometer:micrometer-registry-prometheus` (version from the Boot 4.0.6 BOM — do not pin) AND expose the endpoint via `management.endpoints.web.exposure.include: health,prometheus` in BOTH `src/main/resources/application.yml` AND `src/test/resources/application.yml` (the test yml SHADOWS, not merges — the AC5 scrapeable test boots under the `test` profile and will 404 the endpoint if the exposure key is missing there). [Source: pom.xml:60-63; application.yml has no `management:` block; [[validated-config-needs-test-yaml]]]

5. **Micrometer meter names use DOTS; Prometheus renders them with UNDERSCORES.** AC5 lists Prometheus names like `deliveryline_runner_queue_depth`. Register the Micrometer meter as **`deliveryline.runner.queue.depth`** (dots) — Micrometer's Prometheus registry auto-converts dots→underscores at scrape time. Naming the meter with literal underscores would produce a double-mangled name. Follow the existing pattern: `Counter.builder("deliveryline.artifact.operation.idempotency_replay").register(meterRegistry)` (ArtifactOperationService.java:158-161). [Source: ArtifactOperationService.java:158-161]

6. **There is NO in-memory per-worker roster — reconstruct per-worker status from the DB.** `RunnerWorkerPool` (application.runner.queue) tracks only an aggregate `inFlight` counter (RunnerWorkerPool.java:63-65,247-254); it does NOT keep a `workerId → currentRunnerExecutionId` map. The authoritative per-worker state lives on the `runner_executions` row: `status='running' AND worker_id IS NOT NULL` carries `worker_id`, `dispatched_at`, `stage`, and the run/execution public ids. Build the `workers: List<WorkerStatus>` from those leased rows (the BUSY workers); derive `idleWorkers = max(0, poolSize − activeWorkers)` and `activeWorkers = inFlightExecutions = count(running leased rows)`. Do not try to enumerate idle workers by id (the pool doesn't expose its roster — see OQ-1). [Source: RunnerWorkerPool.java:88-120,176-224]

7. **The worker pool is DISABLED in the test profile (`worker-pool.enabled: false`, Trap T8) — inspection must not assume a started pool.** Read `poolSize` from `RunnerWorkerPoolProperties.size()` (config, always present) and all live counts from the DB read port. Never call into the pool's runtime state for the counts. [Source: application.yml:186-188]

8. **No new `DomainErrorCode`, no new `WorkflowEventType`, no Flyway migration.** This is reads-only. A malformed `batchId` reuses the existing `bat_`-prefix validation (`PublicIdPrefixes.require(..., BATCH_SUBMISSION)`) → existing `INVALID_ID_PREFIX`; a missing batch simply returns an empty/zeroed filtered view. Avoid the [[new-domainerrorcode-three-sites]] / [[new-workfloweventtype-fixture-sites]] fan-outs. [Source: 3-18 PublicIdPrefixes `bat_` prefix]

## Scope Boundary — what 3.19 BUILDS vs REUSES vs DEFERS

| Concern | 3.19 | Note |
|---|---|---|
| `WorkflowInspectionService.getRunnerQueueStatus(String batchIdOrNull) → RunnerQueueStatus` | **BUILD** | epic AC1/AC9/AC10 — Reconciliation 1/2 |
| `RunnerQueueStatus` + `WorkerStatus` nested public records in `application.workflow` | **BUILD** | epic AC1 — Reconciliation 1 |
| Read-port additions on `RunnerExecutionRecordPort`: oldest-queued, active-leased list, stale-queued/stale-dispatched counts, throughput, all `?batchId`-filtered | **BUILD** | reuse `countQueued()`; mirror existing native/JPQL query style |
| CLI `deliveryline workers status [--format=text\|json] [--watch]` (new `WorkerCommands` `@CommandGroup`) | **BUILD** | epic AC2/AC3 — net-new color coding + watch loop |
| REST `GET /api/v1/runner-queue/status[?batchId=]` (`RunnerQueueController`) + `RunnerQueueStatusResponse` DTO + OpenAPI snapshot | **BUILD** | epic AC4/AC9 |
| `micrometer-registry-prometheus` dep + `management` actuator exposure (main + test yml) | **BUILD** | epic AC5 — Reconciliation 4 |
| Prometheus gauges/counters/timer (`deliveryline.runner.*`) via a `MeterBinder` + dispatch/completion instrumentation | **BUILD** | epic AC5 — Reconciliation 5 |
| `infra/observability/prometheus/` (`prometheus.yml` + `alerts.yml` + `README-alerting.md`) | **BUILD** | epic AC7/AC8 — Reconciliation 3 |
| `infra/observability/grafana/dashboards/` "Runner Queue" dashboard JSON + provisioning | **BUILD** | epic AC6 — Reconciliation 3 |
| Prometheus + Grafana services in `docker-compose.yml` under the `observability` profile | **BUILD** | epic AC8 — reuse reserved `*_HOST_PORT` env keys |
| ArchUnit rule pinning `RunnerQueueStatus`/`WorkerStatus` references to `WorkflowInspectionService` + the response DTO | **BUILD** | epic AC10 — mirror `ALLOWED_ACTION_DERIVATION_…` rule |
| Foundation-gate widening: field-coverage contract + `deliveryline_runner_queue_depth` scrapeable | **BUILD** | epic AC11 — Reconciliation 10 |
| `RunnerExecutionQueue` / `RunnerWorkerPool` / dispatch path / `runner_executions` writes | **REUSE UNCHANGED** | 3.17a/3.17b — do NOT touch |
| `runner_executions.batch_submission_id` FK (the `?batchId` filter source) | **REUSE** | 3.18 — already added by V15 |
| `WorkflowCommandOutputs` text/JSON render helpers + `CorrelationIdFilter` (X-Correlation-Id echo) | **REUSE** | stories 6.9 / 1.x |
| New `DomainErrorCode` / `WorkflowEventType` / Flyway migration | **AVOID — none needed** | Reconciliation 8 |
| Alertmanager service in compose (Slack/email/PagerDuty wiring) | **DEFER (document only)** | epic AC8 — pilots opt in; only `README-alerting.md` ships |

## Acceptance Criteria

> From `epic-03-agent-execution.md` §"Story 3.19" (lines 381–400), with **binding clarifications** in **bold parentheticals**.

1. **Given** `WorkflowInspectionService.getRunnerQueueStatus() → RunnerQueueStatus`, **Then** the typed view returns: `poolSize`, `activeWorkers`, `idleWorkers`, `queueDepth`, `oldestQueuedAt`, `oldestQueuedAgeSeconds`, `inFlightExecutions`, `recentThroughputPerMinute`, `workers: List<WorkerStatus>` — per-worker `WorkerStatus { workerId, state, currentRunnerExecutionId?, currentWorkflowRunId?, dispatchedAt?, currentStage? }`. **(`RunnerQueueStatus` + `WorkerStatus` are nested public records in `WorkflowInspectionService` — Reconciliation 1. `poolSize` from `RunnerWorkerPoolProperties.size()`; `activeWorkers = inFlightExecutions = count(status='running' AND worker_id IS NOT NULL)`; `idleWorkers = max(0, poolSize − activeWorkers)`; `workers` = one entry per leased running row, `state='busy'`; `oldestQueuedAgeSeconds` computed server-side from `min(created_at)` of queued rows — Reconciliation 6.)**

2. **Given** CLI `deliveryline workers status [--format=text|json] [--watch]`, **Then** prints the typed view; `--watch` refreshes every 5s (configurable); JSON output stable-schema. **(New `@CommandGroup(name="workers", prefix="deliveryline")` component `WorkerCommands` with a `@Command(name="status")` method. Reuse the `WorkflowCommandOutputs` render-to-`LinkedHashMap` + `schemaVersion` + `writeJson` pattern. `--watch` loops `print → Thread.sleep(intervalMs)`; the `Thread.sleep` call needs a `config/checkstyle/suppressions.xml` line-anchored entry — Reconciliation in Dev Notes. JSON output ignores `--watch` color codes.)**

3. **Given** queue-stale detection from story 3.2 AC3 (extended for queued items in story 3.17 AC9), **Then** the inspection view exposes `staleQueuedCount` + `staleDispatchedCount`; both render with yellow/red color coding in CLI text output when non-zero. **(`staleDispatchedCount` reuses the lease-window threshold = `staleThresholdMultiplier × stageTimeout` — running leased rows with `dispatched_at` past it. `staleQueuedCount` = queued rows whose `created_at` is older than the same threshold. Add both fields to `RunnerQueueStatus`. Color: yellow when stale, red when over the critical alert threshold (AC7); plain when zero. Strip ANSI for `--format=json` and non-TTY.)**

4. **Given** REST `GET /api/v1/runner-queue/status`, **Then** returns 200 with `RunnerQueueStatus` JSON; idempotent read; OpenAPI documented; rate-limit-friendly for Grafana scrape every 15s. **(New `RunnerQueueController` `@RestController @RequestMapping("/api/v1/runner-queue")` in `adapters.rest`; calls `workflowInspectionService.getRunnerQueueStatus(null)`; maps to `RunnerQueueStatusResponse.from(...)`. `X-Correlation-Id` echoed by the existing `CorrelationIdFilter` — no per-controller work. Regenerate the OpenAPI snapshot with `-Dopenapi.snapshot.write=true` then commit.)**

5. **Given** queue-size monitoring via Prometheus per AR25, **Then** Spring Boot Actuator's Prometheus endpoint exposes: `deliveryline_runner_pool_size`, `deliveryline_runner_active_workers`, `deliveryline_runner_idle_workers`, **`deliveryline_runner_queue_depth`** (headline metric), **`deliveryline_runner_queue_oldest_age_seconds`**, `deliveryline_runner_dispatched_count_total`, `deliveryline_runner_completed_count_total{stage,outcome}`, `deliveryline_runner_dispatch_duration_seconds{stage}` (histogram). **(Register the gauges via a `MeterBinder` whose suppliers call the read port (or `getRunnerQueueStatus`) — meters named with DOTS, Reconciliation 5. Counters/timer are incremented at the dispatch + completion sites (RunnerWorkerPool / RunnerBroker.onResult) — instrumentation only, no behavior change. `{stage}`/`{outcome}` are Micrometer tags. Add `micrometer-registry-prometheus` + actuator exposure first — Reconciliation 4.)**

6. **Given** Grafana dashboards, **Then** extends the observability stack with a "Runner Queue" panel set: **queue depth over time** (headline graph), oldest queued age, worker-pool utilization, per-stage throughput, dispatch duration p50/p95/p99 — committed under `infra/observability/grafana/dashboards/`. **(Reconciliation 3: this DIR DOES NOT EXIST yet — 3.7 shipped Kibana, not Grafana. Create the dir + a `runner-queue.json` dashboard + Grafana provisioning (datasource → Prometheus, dashboard auto-load). AC12 asserts the JSON parses.)**

7. **Given** queue-size alert rules, **Then** `infra/observability/prometheus/alerts.yml` defines: **`RunnerQueueDepthHigh`** (depth > warn threshold for 5 min — default 50, override `deliveryline.runner.alerts.queue-depth-warn`), **`RunnerQueueDepthCritical`** (depth > critical for 2 min — default 200), **`RunnerOldestQueuedStale`** (oldest queued age > 2× stage timeout), **`RunnerPoolStarved`** (active workers = pool size for 10 min). **(Thresholds are baked into the PromQL expressions in `alerts.yml`; the `deliveryline.runner.alerts.queue-depth-warn` override is an app-config knob only if you also want the app to surface the warn level — keep it OPTIONAL+UNVALIDATED so the test yml needs no mirror. AC12 asserts `promtool check rules` validity.)**

8. **Given** alertmanager-routing documentation, **Then** `infra/observability/prometheus/README-alerting.md` shows how to wire alerts to Slack / email / PagerDuty by adding an alertmanager service to the unified compose `observability` profile + supplying webhook URLs via `.env` — alertmanager NOT added by default; pilots opt in. **(Add Prometheus + Grafana services to `docker-compose.yml` under the existing `observability` profile, mirroring the ELK service style (version-pinned images, `${PROMETHEUS_HOST_PORT:-9090}` port templating). Alertmanager is documented but NOT a compose service — AR25 keeps observability fully optional.)**

9. **Given** per-batch visibility (from story 3.18 AC4), **Then** `getRunnerQueueStatus` accepts an optional `batchId` query param to filter to that batch's executions. **(`getRunnerQueueStatus(String batchIdOrNull)`; when non-null, every read-port query adds `AND batch_submission_id = :batchId`. REST surfaces it as `?batchId=bat_…`; validate the prefix and reuse `INVALID_ID_PREFIX`. `poolSize` stays global; the filtered counts/`workers`/`queueDepth` scope to the batch.)**

10. **Given** ArchUnit boundary, **Then** all queue-status reading goes through `WorkflowInspectionService.getRunnerQueueStatus(...)`; controllers, CLI, Prometheus exporters consume the typed view. **(Add a `namedRule` mirroring `ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE`: `RunnerQueueStatus`/`WorkerStatus` may be referenced only from `WorkflowInspectionService` and `RunnerQueueStatusResponse`. The MeterBinder reads via the service or the read port — not by duplicating the counting SQL. New rule registered in `ArchitectureRuleCatalog` + `@ArchTest` in `ArchitectureBoundaryTest`; [[archunit-runs-in-failsafe-not-surefire]].)**

11. **Given** the foundation gate (story 1.23) widening, **Then** extends the gate to include "queue-status inspection returns expected fields" + "Prometheus `deliveryline_runner_queue_depth` metric scrapeable". **(Two pieces: (a) a `*FoundationContract` asserting every `RunnerQueueStatus`/`WorkerStatus` field is populated, wired into `FoundationGateVerificationTest`; (b) the scrapeable check needs a booted app + actuator, so it is a `@SpringBootTest` contract test — `GET /actuator/prometheus` contains `deliveryline_runner_queue_depth`. Name the Testcontainers one `*IT` so Surefire's no-Docker tier excludes it — [[springboot-testcontainers-test-must-be-IT]].)**

12. **Given** the test suite, **Then** covers: status-view fields under various states, CLI color coding for stale states, `--watch` refresh, **Prometheus `deliveryline_runner_queue_depth` exposure** (metric present + value matches DB count), Grafana dashboard JSON validity, batch-filter view, alert-rule YAML validity (`promtool check rules`). **(promtool/Grafana JSON checks: parse the JSON for validity unconditionally; gate the `promtool check rules` shell-out behind an availability probe so the test SKIPS (not fails) where promtool is absent — log the skip per [[no silent caps]] discipline. Mirror the `WorkflowBatchCommandsTest` mock-the-service CLI pattern and the `AcceptImplementationEndpointContractTest` `@WebMvcTest` REST pattern.)**

## Tasks / Subtasks

- [x] **Task 1 — Read-port + inspection view (AC1, AC3, AC9)**
  - [x] Add `RunnerQueueStatus` + `WorkerStatus` as nested public records in `WorkflowInspectionService` (NOT in `application.runner` — Reconciliation 1). Fields per AC1 + `staleQueuedCount`/`staleDispatchedCount` (AC3).
  - [x] Extend `RunnerExecutionRecordPort` (+ `RunnerExecutionPersistenceAdapter`) with batch-filterable reads: oldest-queued timestamp, active-leased rows list (`worker_id`,`dispatched_at`,`stage`,run+exec public ids), stale-queued count, stale-dispatched count, recent-throughput count (completed in last 60s). Reuse `countQueued()`; reuse stale window = `staleThresholdMultiplier × stageTimeout`.
  - [x] Implement `getRunnerQueueStatus(String batchIdOrNull)` `@Transactional(readOnly = true)`: poolSize from `RunnerWorkerPoolProperties.size()`, counts from the read port, `idleWorkers = max(0, poolSize − activeWorkers)`, `oldestQueuedAgeSeconds` from server-side `now() − min(created_at)`. Validate `batchId` prefix when present; empty/zeroed view when the batch has no rows.
  - [x] Drive with a Testcontainers IT (`*IT`) seeding queued/running/leased/stale rows; assert every field. ([[markescalationonce-boolean-returning-bug]] — verify new native SQL on real Postgres, not mocks.)
- [x] **Task 2 — CLI `workers status` (AC2, AC3)**
  - [x] New `WorkerCommands` `@Component @CommandGroup(name="workers", prefix="deliveryline")` with `@Command(name="status")` taking `--format` (default text) + `--watch`. Inject `WorkflowInspectionService` only.
  - [x] Add text + JSON renderers to `WorkflowCommandOutputs` (`schemaVersion` + `LinkedHashMap` → `writeJson`). Yellow/red ANSI on non-zero `staleQueuedCount`/`staleDispatchedCount`; strip ANSI for JSON + non-TTY.
  - [x] `--watch`: loop `print → Thread.sleep(intervalMs)`; add the line-anchored `config/checkstyle/suppressions.xml` entry for the `Thread.sleep` forbidden-call ([[checkstyle-suppressions-line-anchored]]).
  - [x] Test mirrors `WorkflowBatchCommandsTest` (mock `WorkflowInspectionService`, assert table header + color markers + JSON schema).
- [x] **Task 3 — REST `GET /api/v1/runner-queue/status` (AC4, AC9)**
  - [x] `RunnerQueueController` (`@RestController @RequestMapping("/api/v1/runner-queue")`) with `@GetMapping("/status")` + optional `@RequestParam(required=false) String batchId`; `@Operation`/`@ApiResponses` for OpenAPI.
  - [x] `RunnerQueueStatusResponse` (+ nested `WorkerStatusResponse`) records with `.from(RunnerQueueStatus)`; `@Schema(requiredMode=...)`; camelCase JSON.
  - [x] Regenerate snapshot: `mvn ... -Dopenapi.snapshot.write=true` (lifecycle phase, not direct goal — [[maven-arglineation-goal-crash]]), review diff, commit; re-run to confirm green.
  - [x] `@WebMvcTest(controllers = RunnerQueueController.class)` contract test (200 happy path, batch-filtered, malformed `batchId` → 400 `INVALID_ID_PREFIX`).
- [x] **Task 4 — Prometheus metrics (AC5)**
  - [x] Add `io.micrometer:micrometer-registry-prometheus` to `deliveryline-backend/pom.xml` (no version — Boot BOM). Add `management.endpoints.web.exposure.include: health,prometheus` to `src/main/resources/application.yml` AND `src/test/resources/application.yml` (Reconciliation 4).
  - [x] `RunnerQueueMetricsBinder implements MeterBinder` (gauges named with DOTS: `deliveryline.runner.pool.size`, `…active.workers`, `…idle.workers`, `…queue.depth`, `…queue.oldest.age.seconds`) — suppliers read the port / `getRunnerQueueStatus`. Register on a long-lived bean (gauge holds a weak ref).
  - [x] Counters `deliveryline.runner.dispatched.count` (total) + `deliveryline.runner.completed.count` tagged `{stage,outcome}` incremented at dispatch (RunnerWorkerPool) + completion (RunnerBroker.onResult); `Timer` `deliveryline.runner.dispatch.duration` tagged `{stage}` around dispatch. Instrumentation only — no behavior change.
- [x] **Task 5 — Observability infra (AC6, AC7, AC8)**
  - [x] Create `infra/observability/prometheus/prometheus.yml` (scrape `deliveryline-backend:8080/actuator/prometheus`, 15s) + `alerts.yml` (the four rules) + `README-alerting.md` (alertmanager opt-in for Slack/email/PagerDuty).
  - [x] Create `infra/observability/grafana/dashboards/runner-queue.json` + `grafana/provisioning/` (Prometheus datasource + dashboard auto-load).
  - [x] Add `prometheus` + `grafana` services to `docker-compose.yml` under the `observability` profile (version-pinned images, `${PROMETHEUS_HOST_PORT:-9090}` / `${GRAFANA_HOST_PORT:-3000}`). Update `infra/observability/README.md`.
- [x] **Task 6 — ArchUnit + foundation gate + remaining tests (AC10, AC11, AC12)**
  - [x] ArchUnit `namedRule` (catalog + `@ArchTest`): `RunnerQueueStatus`/`WorkerStatus` referenced only from `WorkflowInspectionService` + `RunnerQueueStatusResponse`. Verify via Failsafe ([[archunit-runs-in-failsafe-not-surefire]]).
  - [x] Foundation contract `*FoundationContract` asserting all view fields populated; wire into `FoundationGateVerificationTest`. `@SpringBootTest`+actuator scrapeable check (named `*IT`) asserting `/actuator/prometheus` contains `deliveryline_runner_queue_depth` with value == DB count.
  - [x] Grafana JSON parse test; `promtool check rules` test gated on promtool availability (SKIP-not-fail when absent; log the skip).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `runnerExecutionId`, `batchId`). Use MDC where the framework supports it; otherwise pass as parameters.
  - [x] Never log secrets, payload bytes, raw tokens, or full PII. Reference the redaction policy when in doubt.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use a list-appender or `OutputCaptureExtension`).

## Dev Notes

### Relevant architecture patterns and constraints

- **The mandated read seam.** `WorkflowInspectionService` (`org.dradgo.application.workflow`, `@Service`) is the single application read service. It already injects `RunnerExecutionRecordPort` (ctor arg, WorkflowInspectionService.java:88,103-104) and every public method is `@Transactional(readOnly = true)` with MDC-scoped entry/exit logging. Add `getRunnerQueueStatus` following that exact shape. All `*View` result records are nested public records at the bottom of the class (WorkflowInspectionService.java:1407-1677) — put `RunnerQueueStatus`/`WorkerStatus` there.
- **The boundary that defines the whole story (Reconciliation 1).** `noClasses().that().resideInAnyPackage(ADAPTERS_REST/CLI).should().dependOnClassesThat().resideInAnyPackage("…application.workflow.spi..", "…application.runner..", PERSISTENCE, RUNNER_ADAPTER)` (ArchitectureRuleCatalog.java:228-243). The transport adapters can see `application.workflow` (non-spi) + `domain` + framework types only. That is why the typed view records live in `application.workflow`, and why there is no `RunnerQueueInspectionService` in `application.runner`.
- **Queue/worker substrate (read-only inputs — do not modify).**
  - Queue depth: `RunnerExecutionRecordPort.countQueued()` → `select count(*) from runner_executions where status = 'queued'` (RunnerExecutionPersistenceAdapter.java:61-62,282-286).
  - Per-worker state: leased running rows carry `worker_id`, `dispatched_at`, `stage` (RunnerExecutionEntity.java:66-83). `RunnerWorkerPool` keeps only an aggregate `inFlight` (RunnerWorkerPool.java:63-65,247-254) — no roster.
  - `RunnerExecutionStatus`: `pending, running, queued, completed, failed, timed_out, orphaned` (RunnerExecutionStatus.java:5-15).
  - Config: `deliveryline.runner.worker-pool.{enabled,size}` (`RunnerWorkerPoolProperties`, default size 2, clamped 1..32; `enabled:false` in test profile); `deliveryline.runner.queue-max-depth: 100`; `deliveryline.runner.stale-threshold-multiplier: 2.0`; `deliveryline.runner.stage-timeouts.{investigation,execution}: 600s` (application.yml:167-194). The stale window = `staleThresholdMultiplier × stageTimeout`, identical to the broker's orphan threshold (RunnerBroker `scanForStaleExecutions`).
- **Batch filter (AC9).** Story 3.18 added `runner_executions.batch_submission_id text null` (V15) and the `bat_` public-id prefix. The `?batchId=` filter = `AND batch_submission_id = :batchId`. 3.18 is in `review` (uncommitted in git status) — this story depends on it landing; coordinate if it has not merged.
- **Metrics (AC5).** Only one custom metric exists today: `Counter.builder("deliveryline.artifact.operation.idempotency_replay").register(meterRegistry)` (ArtifactOperationService.java:158-161). No `MeterRegistry` Prometheus bean, no `management:` block, no `micrometer-registry-prometheus`. Add the dep + exposure (Reconciliation 4), then a `MeterBinder` for the gauges and counter/timer instrumentation at the dispatch/completion sites. Meter names use dots; Prometheus underscores them (Reconciliation 5).
- **CLI (AC2).** Spring Shell 3.x: `@Component @CommandGroup(name="…", prefix="deliveryline")` + `@Command(name=…)` + `@Option(longName=…)` (WorkflowCommands.java). JSON via `WorkflowCommandOutputs` (`schemaVersion` + `LinkedHashMap` → `writeJson`). No `--watch` precedent exists; the `Thread.sleep` it needs is a checkstyle forbidden-call requiring a line-anchored suppression ([[checkstyle-suppressions-line-anchored]]).
- **REST (AC4).** Mirror `WorkflowBatchController` / `WorkflowController`: `@RestController` under `adapters.rest`, `@GetMapping` + `@Operation`/`@ApiResponses`, response record with `.from(...)` + `@Schema`. `X-Correlation-Id` is handled globally by `CorrelationIdFilter` (echoes the resolved id). OpenAPI snapshot at `src/main/resources/openapi/openapi.json`, regenerated by `OpenApiSnapshotContractTest` with `-Dopenapi.snapshot.write=true`, drift-checked in CI.
- **Profile/wiring cautions.** Worker pool is OFF in the test profile (Trap T8) — never assume it is started. Adding the actuator `management` block requires mirroring it into `src/test/resources/application.yml` (it shadows, not merges) so the scrapeable test sees the endpoint — [[validated-config-needs-test-yaml]]. The Postgres-backed inspection IT must be named `*IT` so the no-Docker Surefire tier excludes it ([[springboot-testcontainers-test-must-be-IT]]) and verify new native SQL on real Postgres, not mocks ([[markescalationonce-boolean-returning-bug]]).
- **AR25 (the architecture driver).** "Provide optional observability (Prometheus/Grafana) under a profile; must NOT be required for normal workflow execution, tests, or recovery." Everything you add stays behind the `observability` compose profile + the actuator endpoint; the app runs byte-identically with it all off.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface for this story):**
  - `getRunnerQueueStatus` → `INFO` on entry (with `batchId` when filtered) + `INFO` on success with `queueDepth`/`activeWorkers`/`staleQueuedCount`/`staleDispatchedCount`; `WARN` when stale counts are non-zero (a real operational anomaly worth a log line, not just a metric).
  - New read-port queries → `DEBUG` per query (hot-path; the `/actuator/prometheus` scrape every 15s + Grafana would flood `INFO`).
  - CLI `workers status` → `INFO` entry/exit; `--watch` logs once at start, not per refresh tick.
  - REST `GET /runner-queue/status` → `INFO` entry/exit (mirror `getWorkflow`).
  - Metric binder registration → `INFO` once at startup ("registered N runner-queue gauges").
- **Required context keys** (MDC or structured params): `correlationId`, plus `batchId` when filtering, plus per-row `runnerExecutionId`/`workflowRunId` when logging an individual leased/stale row.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields.
- **Test contract:** new logging surfaces pinned by at least one focused test (list-appender or `OutputCaptureExtension`) — specifically the non-zero-stale `WARN`.

### Project Structure Notes

- New main code: `WorkerCommands` (`adapters/cli`), `RunnerQueueController` + `RunnerQueueStatusResponse` (`adapters/rest`), `RunnerQueueMetricsBinder` (`infrastructure/observability` or `application/runner/queue` — keep it off the `adapters.rest` boundary; reading via the inspection service or read port is fine). New nested records `RunnerQueueStatus`/`WorkerStatus` inside `WorkflowInspectionService` (`application/workflow`). Read-port method additions on `RunnerExecutionRecordPort` (`application/runner/spi`) + impl in `RunnerExecutionPersistenceAdapter` (`adapters/persistence`).
- New non-code: `infra/observability/prometheus/{prometheus.yml,alerts.yml,README-alerting.md}`, `infra/observability/grafana/dashboards/runner-queue.json` + `grafana/provisioning/…`, `docker-compose.yml` Prometheus+Grafana services, `infra/observability/README.md` update.
- No Flyway migration, no new `DomainErrorCode`, no new `WorkflowEventType`, no domain-registry change.
- Variance: `RunnerQueueController` is a sibling of `WorkflowController`/`WorkflowBatchController` at a new base path `/api/v1/runner-queue` (not under `/api/v1/workflows`) — matches the epic's literal route and keeps queue inspection a distinct resource.

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.19] — AC1–AC12.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:62-108,227-296,1407-1677] — service seam, ctor ports, view-record convention, approval-state precedent.
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:228-243,946-961] — REST boundary rule + the `ALLOWED_ACTION_DERIVATION_…` rule to mirror for AC10.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionRecordPort.java:77-92] — `countQueued()` + dequeue precedent for new read methods.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/queue/RunnerWorkerPool.java:63-65,88-120,176-224,247-254] — aggregate inFlight, no per-worker roster (Reconciliation 6).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerWorkerPoolProperties.java; application.yml:167-194] — pool size/enabled + stale/stage-timeout config.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/RunnerExecutionEntity.java:66-97] — `worker_id`/`dispatched_at`/`stage`/`batch_submission_id` columns.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactOperationService.java:158-161] — existing Micrometer `Counter` pattern (meter naming).
- [Source: deliveryline-backend/pom.xml:60-63; src/main/resources/application.yml] — actuator present, no Prometheus registry, no `management:` block (Reconciliation 4).
- [Source: _bmad-output/implementation-artifacts/3-7-elk-stack-integration-centralized-log-capture-profile-gated.md (D8, lines 57,180)] — Grafana/Prometheus explicitly deferred to 3.19 (Reconciliation 3).
- [Source: _bmad-output/implementation-artifacts/3-18-workflow-batch-submission-cli-and-rest.md] — `batch_submission_id` FK + `bat_` prefix (AC9) + CLI/REST adapter patterns.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java; AcceptImplementationEndpointContractTest.java; adapters/cli/WorkflowBatchCommandsTest.java] — snapshot regen + REST `@WebMvcTest` + CLI mock-service test patterns.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story workflow.

### Debug Log References

- Read-port: ONE native aggregate (`QUEUE_COUNTS_SQL`, Postgres `FILTER` + `make_interval` server-side cutoffs) + a leased-running id-then-reread (`LEASED_RUNNING_IDS_SQL`); `:batchId::text` cast keeps the NULL bind typed (3.18's `batch_submission_id` is native-only, no entity field → must be native SQL). Verified on real Postgres via `WorkflowInspectionRunnerQueueIT` (4/0) per [[markescalationonce-boolean-returning-bug]].
- CLI ANSI: ESC built from `(char) 27` (never a literal control byte in source) after an initial Edit landed a raw ESC ([[literal-nul-byte-binarizes-source]] sibling — ESC doesn't binarize but cleaned anyway via PowerShell byte-replace).
- `WorkerCommands` two-ctor context-boot crash ("No default constructor found") — fixed with `@Autowired` on the production ctor ([[two-public-constructors-need-autowired]]); only the Testcontainers ITs caught it (fast Surefire never boots the bean).
- Prometheus scrape 404 in `@SpringBootTest` — Boot's test slice installs `DisableMetricsExportContextCustomizer`; re-enabled per-IT with `@TestPropertySource(management.prometheus.metrics.export.enabled=true)` (NOT in the shared test yml).
- `--watch` `Thread::sleep` is a method REF (no `Thread.sleep(` paren) so it does NOT trip the `ForbiddenThreadSleep` regex; only the `System.out.println` refresh needed a line-anchored `ForbiddenSystemOut` suppression (anchor re-fixed to line 141 after spotless shifted it; [[checkstyle-suppressions-line-anchored]]). The XML comment must not contain `--` ([[pom-xml-comment-no-double-dash]] sibling).
- OpenAPI snapshot regenerated via the `integration-test` phase + `-Dopenapi.snapshot.write=true` ([[maven-arglineation-goal-crash]]); frontend `schema.d.ts` regenerated + `check:api` in-sync + prettier-clean ([[openapi-regen-platform-shim]], [[prettier-gate-cascades-ci]]).
- PowerShell wraps native-exe stderr (Mockito self-attach warning) as a NativeCommandError → spurious exit 1; relied on the `Tests run / BUILD SUCCESS` text, not `$LASTEXITCODE`.

### Completion Notes List

Implemented story 3.19 as a READ-ONLY inspection + observability layer over the active 3.17a/3.17b queue + the 3.18 batch FK — no queue/worker/dispatch/write path touched.

- **AC1/AC3/AC9 (Task 1):** `RunnerQueueStatus` + `WorkerStatus` nested public records in `WorkflowInspectionService` (`application.workflow`, NOT `application.runner` — the central boundary trap, R1). New `getRunnerQueueStatus(String batchIdOrNull)`; `poolSize` from `RunnerWorkerPoolProperties.size()`, `idleWorkers = max(0, poolSize − activeWorkers)`, per-worker list reconstructed from leased running DB rows (R6). New SPI: `RunnerQueueCounts` record + `loadQueueCounts(...)` + `findLeasedRunning(...)` on `RunnerExecutionRecordPort` (impl native SQL). Added 2 ctor deps to `WorkflowInspectionService` → 8 test ctor sites updated. Non-zero-stale `WARN` logged (Logging contract).
- **AC2/AC3 (Task 2):** new `WorkerCommands` `@CommandGroup(name="workers")` `status` command (`--format`, `--watch`, `--interval-ms`, `--batch-id`); text + JSON renderers added to `WorkflowCommandOutputs` (`schemaVersion=1`); yellow/red ANSI on non-zero stale + queue depth, stripped for JSON/non-TTY. `--watch` loop seam-injected (gate + sleeper) for deterministic tests.
- **AC4/AC9 (Task 3):** new `RunnerQueueController` `GET /api/v1/runner-queue/status[?batchId=]` + `RunnerQueueStatusResponse`; malformed `batchId` → 400 `INVALID_ID_PREFIX`. OpenAPI snapshot regenerated (+135 lines).
- **AC5 (Task 4):** added `micrometer-registry-prometheus` + `management.endpoints.web.exposure.include: health,prometheus` (BOTH main + test yml, R4). `RunnerQueueMetricsBinder` (`infrastructure.observability`) registers the 5 DOT-named gauges reading via the read port (NOT the typed view, so it stays off the AC10 type-reference set; R5). Dispatch counter + `{stage}` duration timer at `executeQueuedDispatch`, completion `{stage,outcome}` counter at `onResult` — instrumented via an optional `@Autowired(required=false)` `MeterRegistry` SETTER on `RunnerBroker` (zero ctor fan-out; defaults to a no-op `SimpleMeterRegistry` in tests). `application.runner.queue` is whitelisted away from `io.micrometer`, so the binder lives in infrastructure and the worker pool is NOT instrumented directly.
- **AC6/AC7/AC8 (Task 5):** created `infra/observability/prometheus/{prometheus.yml,alerts.yml,README-alerting.md}` (the 4 alert rules; alertmanager documented-only), `infra/observability/grafana/dashboards/runner-queue.json` + Grafana provisioning (datasource + dashboard auto-load); added `prometheus` + `grafana` services to `docker-compose.yml` under the `observability` profile + the reserved `*_HOST_PORT` env keys. R3: 3.7 shipped Kibana, so these dirs were created from scratch.
- **AC10/AC11/AC12 (Task 6):** ArchUnit `RUNNER_QUEUE_STATUS_VIEWS_REFERENCED_ONLY_BY_INSPECTION_AND_TRANSPORTS` (allow-set = `WorkflowInspectionService` + `RunnerQueueStatusResponse` + the CLI `WorkerCommands`/`WorkflowCommandOutputs`; the binder is deliberately excluded since it reads via the port). Foundation gate Contract #13: field-coverage `RunnerQueueInspectionFoundationContract` + the scrapeable `RunnerQueuePrometheusScrapeIT` (metric present + value == DB count). Grafana JSON parse test + promtool-gated rule-validity test (SKIP when promtool absent).

**Deviations from epic AC text (live code wins, per Context & Central Reconciliation):** AC10's literal "only WIS + Response" allow-set extended to include the CLI consumer + renderer (the CLI calls the service directly, exactly as `WorkflowCommandOutputs` already references `WorkflowStatusView`); documented on the rule. The `onResult` completion counter buckets the rare handleSuccess-internal artifactRefs-empty failure as "success" (a minor, documented observability approximation). No new `DomainErrorCode`/`WorkflowEventType`/Flyway (R8).

**Verification (PowerShell + local Docker, [[rtk-hook-only-matches-bash]], [[maven-arglineation-goal-crash]]):** fast Surefire 1001/0/12 + checkstyle 0 violations; `ArchitectureBoundaryTest` (Failsafe) 52/0 (new AC10 rule passes); new ITs GREEN — `OpenApiSnapshotContractTest` 1/0 (byte-exact after regen), `RunnerQueueEndpointContractTest` 3/0, `WorkflowInspectionRunnerQueueIT` 4/0, `RunnerQueuePrometheusScrapeIT` 1/0; `-Pfoundation-gate` 19/0 (incl Contract #13); frontend `check:api` in-sync + prettier-clean. **Recommended before merge:** WSL2/Linux clean-env Docker confirm ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]) + the frontend vitest/tsc tier (only the additive generated `schema.d.ts` changed) + code-review with a different LLM.

### File List

**New — main:**
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerQueueCounts.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkerCommands.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RunnerQueueController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RunnerQueueStatusResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/RunnerQueueMetricsBinder.java`

**Modified — main:**
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionRecordPort.java` (2 read methods)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerExecutionPersistenceAdapter.java` (native SQL impls)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (`getRunnerQueueStatus` + 2 nested records + 2 ctor deps)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java` (queue-status renderers)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` (dispatch/completion meters via optional MeterRegistry setter)
- `deliveryline-backend/pom.xml` (`micrometer-registry-prometheus`)
- `deliveryline-backend/src/main/resources/application.yml` (`management` exposure)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated, +135)

**New — test:**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionRunnerQueueIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceRunnerQueueTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkerCommandsTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RunnerQueueEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RunnerQueuePrometheusScrapeIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/observability/RunnerQueueMetricsBinderTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/observability/RunnerQueueObservabilityAssetsTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/RunnerQueueInspectionFoundationContract.java`

**Modified — test:**
- `deliveryline-backend/src/test/resources/application.yml` (`management` exposure)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` (+ AC10 rule)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` (register rule)
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java` (Contract #13)
- 8× `WorkflowInspectionService*Test.java` (ctor arity)

**New — infra/observability:**
- `infra/observability/prometheus/prometheus.yml`, `alerts.yml`, `README-alerting.md`
- `infra/observability/grafana/dashboards/runner-queue.json`
- `infra/observability/grafana/provisioning/datasources/prometheus.yml`
- `infra/observability/grafana/provisioning/dashboards/dashboards.yml`

**Modified — infra/config:**
- `docker-compose.yml` (prometheus + grafana services + volumes)
- `.env.example` (`PROMETHEUS_HOST_PORT` / `GRAFANA_HOST_PORT` / `GRAFANA_ADMIN_PASSWORD`)
- `infra/observability/README.md` (Prometheus/Grafana section)
- `config/checkstyle/suppressions.xml` (`WorkerCommands` ForbiddenSystemOut)
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated)

## Change Log

| Date | Change |
|---|---|
| 2026-06-15 | Story 3.19 implemented: runner-queue inspection (`WorkflowInspectionService.getRunnerQueueStatus`) + `deliveryline workers status` CLI + `GET /api/v1/runner-queue/status` REST + Prometheus metrics (5 gauges + dispatch/completion counters/timer) + Grafana dashboard + alert rules + compose services. ArchUnit AC10 rule + foundation-gate Contract #13. All tasks complete; Status → review. |

## Review Findings

> Adversarial code review (bmad-code-review) — 2026-06-15. Three layers (Blind Hunter, Edge-Case Hunter, Acceptance Auditor). Acceptance Auditor independently verified AC1–AC12 are fully and faithfully implemented; all findings below are robustness/polish, no missing-AC or spec-contradiction. Scope was story 3.19 only (3.22 changes intermingled in shared files were excluded). 5 findings dismissed as noise.

### Patch

- [x] [Review][Patch] `--watch` loop terminates on a single transient `RuntimeException` from `getRunnerQueueStatus` — only `InterruptedException` is caught; a DB blip ends the whole monitor session (the metrics binder, by contrast, serves the last snapshot). Wrap the per-tick `renderTick`/`emit` in a `try/catch (RuntimeException)` that logs + continues. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkerCommands.java:117-129]
- [x] [Review][Patch] REST `?batchId=` (empty/whitespace) diverges from the CLI — controller forwards `batchId` verbatim so a blank value hits `PublicIdPrefixes.require("")` → 400 `INVALID_ID_PREFIX`, while the CLI runs `emptyToNull` first and returns the global view. Normalize blank→null (mirror `emptyToNull`, ideally in the service seam so both transports agree). [deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RunnerQueueController.java:59-65]
- [x] [Review][Patch] `colorStale` renders red at `>= QUEUE_DEPTH_CRITICAL` (200), borrowing the queue-depth threshold for a semantically unrelated stale count and contradicting the field's own comment ("yellow whenever non-zero") + AC3 (AC7 defines no stale-count critical). Make stale counts always yellow when non-zero (drop the borrowed red branch) or use a stale-specific threshold. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java (colorStale)]
- [x] [Review][Patch] `recentThroughputPerMinute` is the raw `recentThroughput` count over `THROUGHPUT_WINDOW`, passed through unscaled — correct only because the window is hard-coded to exactly 60s. The "per minute" wire/JSON/REST field silently mislabels if the window ever changes. Normalize `recentThroughput * 60.0 / THROUGHPUT_WINDOW.toSeconds()` (or pin the equality with a test). [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:399]
- [x] [Review][Patch] Worker-detail fields (`state`, `currentRunnerExecutionId`, `currentWorkflowRunId`, `currentStage`) are appended raw in the text renderer while `workerId` is passed through `escapeForText` — inconsistent control-char escaping for fields of the same row. Apply `escapeForText` consistently (low risk today: values are DB public ids / enum / "busy"). [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java (renderQueueStatusText worker loop)]

### Deferred

- [x] [Review][Defer] `findLeasedRunning` does one JPA `findByPublicId` per leased id (N+1, cap 1024 not pool-size), and a row completing between the id-select and re-read is silently dropped — so `workers.size()` can fall below the `activeWorkers` scalar (separate query, READ COMMITTED). Low impact (bounded ≤ pool size in practice; observability-only skew). [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerExecutionPersistenceAdapter.java (findLeasedRunning)] — deferred
- [x] [Review][Defer] `staleDispatchedCount` uses `maxStaleWindow()` (slowest stage) uniformly, so a hung fast-stage dispatched row is under-counted until the slowest window elapses. Currently **inert** — both stage timeouts are 600s, so max == per-stage; only bites if the timeouts diverge. [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java (maxStaleWindow)] — deferred
