## Epic 3: Agent Execution, Implementation Output & Developer Review

After spec approval, the workflow dispatches agent work through real Docker runners (Codex + Claude), produces implementation artifacts linked to a GitHub PR reference, and a Developer can inspect the approved spec, implementation plan, and PR artifact — then accept, reject with technical feedback, or take over the run without losing prior context. Activates the implementation-plan + PR/output variants of the generalized E2 composites (UX-DR10 + UX-DR12 mode prop). Ships a deterministic mock GitHub adapter alongside the real adapter so the full execution loop can be demonstrated and contract-tested without external API flakiness.

> **Sequencing Update (2026-05-26):** Per `sprint-change-proposal-2026-05-26.md`, Epic 3 is split into **Epic 3a — Real Agent + Repo Stack** (active slice, pulled forward to enable end-to-end ticket→real-spec→view scenario) and **Epic 3b — Implementation Output + Dev Review** (deferred).
>
> - **Epic 3a active slice:** Stories 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.9, 3.13, 3.14 + new stories **3a-1 (Spec-Stage Orchestration)**, **3a-2 (Spec-Stage Repo-Context Bundle Extension)**, and **3a-3 (Codex Subscription Auth via `auth.json`)** — see end of this file.
> - **Epic 3b deferred:** Stories 3.7, 3.8, 3.10, 3.11, 3.12, 3.15 … 3.36.
>
> Story 3.9's scope is extended to also serve the `spec-investigation` stage (in addition to the originally-planned implementation-plan / pr-output stages); see §2.2 of the change proposal.

### Story 3.1: DockerRunnerAdapter Core — Container Lifecycle + File-Based Contract Invocation

As a backend developer,
I want `DockerRunnerAdapter` implementing the `RunnerAdapter` port from story 1.13 — driving real Docker container lifecycle (run, monitor, capture result file, exit handling) over the versioned file-based runner contract from story 1.6,
So that the runner broker can dispatch real Codex + Claude runs through the same port the deterministic mock used, with no broker-side reshape.

**Acceptance Criteria:**

1. **Given** the `adapters.runner` package, **Then** `DockerRunnerAdapter` exists implementing the `RunnerAdapter` interface from story 1.13 — same method signatures as `MockRunnerAdapter`, dispatched by Spring profile (`runners-docker` activates real adapter; `test` defaults to mock).
2. **Given** a runner dispatch from `RunnerBroker`, **When** invoked, **Then** the adapter: (a) creates a per-execution work directory `{DELIVERYLINE_HOME}/runner-work/{runnerExecutionId}/` with subdirs `input/`, `output/`, `logs/`; (b) writes the `context-bundle.v1.json` document (story 1.6) to `input/context-bundle.json` after `RunnerContractValidator` validation; (c) launches the runner image via Docker CLI (or Docker Java client) with read-only mount of `input/`, read-write mount of `output/` and `logs/`, no other host mounts; (d) returns an execution handle.
3. **Given** the container exits, **When** the adapter detects the exit, **Then** it: (a) reads `output/runner-result.json` if present and validates against `runner-result.v1.schema.json` (story 1.6) before passing to the broker; (b) classifies the exit per `FailureCategory` registry (story 1.4) — `runner_non_zero_exit` if exit code non-zero with valid result, `runner_contract_violation` if result file invalid, `runner_crash` if no result file present after non-zero exit, `runner_timeout` if killed by lifecycle (story 3.2).
4. **Given** the runner-contracts module schema versions (story 1.6), **Then** the adapter writes `schemaVersion: 1` in the input bundle and rejects results carrying any version not in the `RunnerSchemaVersion` registry.
5. **Given** runner image identification, **Then** each `RunnerAdapter.dispatch(...)` call selects the image by runner kind (`codex` → `deliveryline/codex-runner:latest`, `claude` → `deliveryline/claude-runner:latest`); image tags are configured via `application.yml` properties so test/demo profiles can override.
6. **Given** the architecture rule that runner outputs may become workflow artifacts only through `ArtifactOperationService` (story 1.12), **Then** `DockerRunnerAdapter` never calls `ArtifactOperationService` directly — it returns the validated result to `RunnerBroker`, which orchestrates artifact ingest via the application services.
7. **Given** workspace cleanup, **When** an execution completes (success or failure), **Then** the work directory remains on disk for diagnostic inspection until the configurable retention threshold (`deliveryline.runner.workspace-retention-hours`, default 24) — cleanup is performed by a scheduled job in story 3.2; immediate deletion is forbidden so post-mortems remain possible.
8. **Given** localhost binding from story 6.9 + the fail-closed rule on non-loopback REST, **Then** runner containers receive **no** network access to the backend's REST port — runner ↔ backend communication is strictly file-based (no HTTP); a contract test asserts the container starts with `--network=none` (or equivalent isolated bridge) and cannot reach `127.0.0.1:8080`.
9. **Given** ArchUnit boundary rules (story 1.11), **Then** `DockerRunnerAdapter` lives in `adapters.runner` and may not be referenced from `domain` or other adapters; it depends on application-layer interfaces only.
10. **Given** the test suite, **Then** integration tests cover: happy-path success → valid result file consumed, non-zero exit with valid result, non-zero exit without result file, invalid result file rejected with `runner_contract_violation`, schema-version mismatch rejected, image-tag override via property, workspace directory creation + retention preservation, no network access from runner container.
11. **Given** cross-platform support per story 1.17, **Then** the adapter uses Docker CLI commands compatible with Docker Desktop on Windows/macOS and Docker Engine on Linux/WSL2; mount paths are normalized to forward slashes inside container references regardless of host OS.

### Story 3.2: DockerRunnerAdapter Lifecycle — Timeout, Heartbeat, Lease Expiry, Cleanup, Idempotent Restart

As a backend developer,
I want `DockerRunnerAdapter` to enforce per-execution timeouts, track heartbeat / last-activity, expire leases on stale executions, clean up workspaces past retention, and recover idempotently after broker crashes,
So that runner failures (hangs, timeouts, broker restarts mid-execution) don't leak containers, lose result tracking, or silently advance workflow state — fulfilling AR16 lifecycle requirements.

**Acceptance Criteria:**

1. **Given** a per-execution timeout configured per stage (default 30 minutes, overridable via `application.yml` `deliveryline.runner.stage-timeout.{stageName}`), **When** a runner container exceeds the deadline, **Then** the adapter: (a) sends `docker stop` (graceful — 10s) followed by `docker kill` if the container does not exit, (b) updates `runner_executions.status = timed_out` and appends a `runner.timeout` event with the failure category `runner_timeout`, (c) returns control to the broker with a typed timeout outcome — the broker decides whether to retry per workflow rules.
2. **Given** heartbeat tracking, **Then** the adapter polls Docker for container activity at a documented interval (default 15s — configurable) and updates `runner_executions.last_activity_at` based on: (a) the container's main-process state, (b) presence + size of `logs/runner.stdout` (growing log = activity), (c) optional heartbeat marker file at `output/heartbeat.touch` that runner images may touch periodically.
3. **Given** stale-execution detection at broker level, **Then** a scheduled job (`RunnerStaleDetectionJob`, runs every minute) queries `runner_executions` for rows with `status = running` and `last_activity_at` older than `2 × stage_timeout` (lease expiry) — flags them as `orphaned` and emits a `runner.orphaned` event so recovery (Epic 4) can decide next action.
4. **Given** broker crash mid-execution, **When** the backend restarts, **Then** a startup recovery routine queries `runner_executions` for `status IN (pending, running)` rows and: (a) inspects each by `runnerExecutionId` to determine if the container still exists, (b) if container running → resumes monitoring from current state, (c) if container exited but no result was processed → reads result file and processes as normal exit, (d) if container missing entirely → marks `status = orphaned` for Epic-4 reconciliation. This is the **idempotent restart** behavior from AR16.
5. **Given** workspace cleanup per retention threshold from story 3.1 AC7, **Then** a scheduled job (`RunnerWorkspaceCleanupJob`, runs hourly) deletes work directories whose corresponding `runner_executions.completed_at` is older than `workspace-retention-hours`; each deletion is logged with the `runnerExecutionId` for audit; deletion is skipped (and warned) if `runner_executions` row is missing — never delete a workspace whose row cannot be looked up.
6. **Given** dangling containers (broker thinks execution completed but container is still running — e.g., post-restart edge case), **When** the cleanup job runs, **Then** it issues `docker stop` on containers tagged with a known DeliveryLine label whose `runnerExecutionId` no longer corresponds to a `status IN (running, pending)` row — preventing leaked containers from accumulating.
7. **Given** container labeling, **Then** every dispatched container is launched with labels `deliveryline.runnerExecutionId={id}`, `deliveryline.workflowRunId={id}`, `deliveryline.stage={name}`, `deliveryline.dispatchedAt={timestamp}` — enabling cleanup and operator inspection (`docker ps --filter label=deliveryline.workflowRunId=run_abc` shows live runners for a workflow).
8. **Given** lifecycle events appended on every transition, **Then** `runner_executions` lifecycle changes append matching `runner.*` workflow events: `runner.dispatched`, `runner.heartbeatStale`, `runner.timeout`, `runner.orphaned`, `runner.completed`, `runner.failed` — preserving the audit trail for FR47.
9. **Given** `RecoveryService` baseline from story 1.18 (CLI `retry`), **Then** the lifecycle layer integrates: a `retry` action against a timed-out / orphaned execution dispatches a fresh execution with a new `runnerExecutionId` while preserving the original execution's audit row — never reuses a stale `runnerExecutionId`.
10. **Given** the test suite, **Then** integration tests cover: timeout enforcement (a runner that intentionally sleeps past timeout is killed and marked `timed_out`), heartbeat staleness detection (a runner that produces no log output for >2× timeout is flagged orphaned), broker-restart recovery (kill the JVM mid-execution, restart, confirm execution either resumes or is properly orphaned), workspace cleanup (a workspace older than retention is deleted; one within retention is preserved), dangling container cleanup (manually-launched container with stale runnerExecutionId is stopped), label propagation visible in `docker inspect`.

### Story 3.3: Codex Runner Image — Dockerfile + Entrypoint + Contract Conformance

As a runner-infrastructure developer,
I want a `runners/codex/` Dockerfile + entrypoint script that wraps the Codex CLI as a runner container conforming to the runner-contracts v1 schema (input bundle → produce result file → exit),
So that `DockerRunnerAdapter` (story 3.1) can dispatch Codex executions via the same file-based contract used by the deterministic mock, and the unified compose file (AR24) can build the image as part of `docker compose build`.

**Acceptance Criteria:**

1. **Given** `runners/codex/Dockerfile`, **Then** it produces an image tagged `deliveryline/codex-runner:latest` (and a build-arg-controlled version tag for CI) based on a documented base image (e.g., `node:22-slim` or whatever the Codex CLI requires), installs the Codex CLI via documented version pin, and copies the entrypoint script.
2. **Given** `runners/codex/entrypoint.sh`, **Then** the entrypoint: (a) reads `/workspace/input/context-bundle.json`, (b) validates the bundle's `schemaVersion = 1`, (c) extracts the stage's required inputs (ticket summary, approved spec ref, prior feedback, execution constraints), (d) invokes the Codex CLI with the extracted inputs and any required env-var auth (per story 3.5 secrets handling), (e) collects Codex output into a structured `runner-result.v1` JSON document at `/workspace/output/runner-result.json`, (f) writes raw Codex stdout + stderr to `/workspace/logs/runner.stdout` and `/workspace/logs/runner.stderr`, (g) exits 0 on success, non-zero with a documented exit code on contract-detectable failure.
3. **Given** the runner-contracts v1 schema (story 1.6), **Then** the entrypoint emits `artifactType` correctly per stage: `spec` for spec-investigation runs, `implementationPlan` for plan-generation runs, `prOutput` for implementation runs — driven by a `stage` field passed in the input bundle.
4. **Given** `runner-contracts/src/test/resources/fixtures/valid/`, **Then** at least one fixture per artifact variant exercises the Codex image end-to-end against a documented test scenario (the test scenario uses a mocked Codex CLI binary — actual Codex API calls happen in story 3.7's integration test which is profile-gated and skippable in PR CI per story 3.28's flake control).
5. **Given** the unified `docker-compose.yml` (story 1.2), **Then** the Codex image is declared as a `build` target with `dockerfile: runners/codex/Dockerfile` — `docker compose build codex-runner` rebuilds the image, no separate `build-runner-images.sh` script needed (per AR31 update).
6. **Given** workspace conventions per story 3.1 AC2, **Then** the entrypoint expects mounts at `/workspace/input` (read-only), `/workspace/output` (read-write), `/workspace/logs` (read-write) — and never writes outside these paths.
7. **Given** least-privilege per AR16 + architecture security model, **Then** the container runs as a non-root user (e.g., `codex:1000`), receives no network access by default (story 3.1 AC8), and accesses Codex API only when secrets are mounted per story 3.5 (with documented egress policy when network access is required for actual Codex API calls).
8. **Given** image smoke testing, **Then** `docker run --rm deliveryline/codex-runner:latest --self-test` exits 0 and prints a documented self-test summary (entrypoint reachable, Codex CLI installed, expected version) — used by the doctor command (story 1.16) for `runner image availability` check and by CI (story 3.28).
9. **Given** image-size budget, **Then** the resulting image is documented in size + layer count; an additive layer that exceeds a reasonable threshold (e.g., 1 GB total) requires justification in the runner README.
10. **Given** `runners/codex/README.md`, **Then** it documents: base image choice rationale, Codex CLI version pinning + upgrade procedure, environment variables consumed, expected mount paths, exit code meanings, and how to test the image locally (`docker compose build codex-runner && docker run ...`).
11. **Given** failure injection capability, **Then** the entrypoint supports a `--simulate-failure={timeout|crash|contract_violation}` flag for integration tests (story 3.5/3.6) — this flag is gated behind a documented test-only env var so production images can disable it.

### Story 3.4: Claude Runner Image — Dockerfile + Entrypoint + Contract Conformance

As a runner-infrastructure developer,
I want a `runners/claude/` Dockerfile + entrypoint script that wraps the Claude Code CLI as a runner container conforming to the runner-contracts v1 schema, mirroring story 3.3's structure for Codex,
So that the runner broker has two real runner options + the `RunnerAdapter` port supports interchangeable runner kinds via configuration, and the runner-contract is validated against two independent implementations (catching contract gaps story 3.3 alone might miss).

**Acceptance Criteria:**

1. **Given** `runners/claude/Dockerfile`, **Then** it produces an image tagged `deliveryline/claude-runner:latest` (with build-arg version tag for CI) based on a documented base image (likely `node:22-slim` or whatever the Claude Code CLI requires) and installs Claude Code CLI via documented version pin.
2. **Given** `runners/claude/entrypoint.sh`, **Then** the entrypoint mirrors story 3.3's contract: read input bundle, validate schemaVersion, invoke Claude Code CLI with extracted inputs + secrets, emit `runner-result.v1.json`, write raw stdout/stderr to logs, exit with documented codes — same workspace conventions, same artifact-variant emission, same least-privilege posture.
3. **Given** the **shared contract** between Codex and Claude runners per architecture project-structure ownership boundary ("runners/codex and runners/claude share the same environment variable, mount, context file, result file, and diagnostics contract"), **Then** a documented `runners/RUNNER_CONTRACT.md` lists every shared convention (env vars, mounts, file paths, exit codes, diagnostic flags) — both runner READMEs reference it; deviations require updating the shared contract first.
4. **Given** runner-specific behavior, **Then** Claude-specific configuration (model name, max tokens, prompt template) is read from environment variables documented in `runners/claude/README.md` and consumed by the entrypoint — never baked into the image as constants.
5. **Given** the unified `docker-compose.yml`, **Then** the Claude image is declared as a `build` target alongside Codex — `docker compose build` rebuilds both.
6. **Given** image smoke testing, **Then** `docker run --rm deliveryline/claude-runner:latest --self-test` exits 0 with a documented self-test summary — same convention as Codex (story 3.3 AC8).
7. **Given** failure injection per story 3.3 AC11, **Then** the Claude entrypoint also supports `--simulate-failure={...}` with the same flag set — enabling cross-runner contract tests.
8. **Given** runner-kind selection via configuration (`application.yml` `deliveryline.runner.kind=codex|claude`), **Then** swapping kinds requires no code changes — only a config value change + the corresponding image being available; a contract test asserts that running the same workflow stage against Codex vs Claude produces results that differ in content but conform to the same `runner-result.v1` schema with matching `artifactType`.
9. **Given** `runners/claude/README.md`, **Then** it documents the same items as story 3.3 AC10 plus Claude-specific configuration (model selection, prompt template location, rate limiting considerations).
10. **Given** the cross-runner consistency principle from architecture, **Then** any change to the input bundle schema, output schema, mount paths, or exit codes triggers updates to BOTH runner Dockerfiles and entrypoints in the same PR — a CI check (extending story 3.28) verifies both runner images build successfully and pass `--self-test` on every PR that touches `runner-contracts/src/main/resources/schemas/`.
11. **Given** parity testing, **Then** an integration test (story 3.8) runs the same fixture scenario against both runners and asserts both produce schema-conformant output — even if content differs.

### Story 3.5: Runner Secrets Handling — Secure Mount of Agent-Provider API Keys

As a backend developer + pilot installer,
I want secrets (Codex API key, Claude API key, GitHub token, Linear API key when needed by runners) to be mounted into runner containers via Docker secrets / env-var injection with strict no-leak guarantees,
So that runner containers can authenticate to external services without secrets being committed to the repo, baked into images, persisted in workspaces, written to logs, or shipped to ELK (story 3.7) — fulfilling NFR8, NFR9, NFR14.

**Acceptance Criteria:**

1. **Given** `RunnerSecretsService` in `application.runner`, **Then** it exposes `resolveSecretsForRunner(runnerKind, stageName, workflowRunId) → Map<String, String>` returning the secret env vars the runner needs for this dispatch — read from local config (`.env`, `application-local.yml`) or system env at runtime, never persisted by the service.
2. **Given** secret loading, **Then** secrets are loaded from `.env` (via Spring Boot's `spring.config.import=optional:file:.env`) or from environment variables at runtime — never committed, never read from `application.yml` defaults, never logged at any level (a redaction-rule test asserts no `LINEAR_API_KEY` value or any value matching the API-key regex appears in logs).
3. **Given** the `RunnerAdapter.dispatch(...)` call from story 3.1, **Then** secrets are passed to Docker as `--env` flags with `--env-file` for batch values — never as command-line arguments visible in `docker ps`, never written to a mounted file inside the workspace, never serialized into the input context bundle (per NFR14 — context bundles must avoid credentials).
4. **Given** workspace persistence per story 3.1 AC7 (24h retention for diagnostics), **Then** **no file under \****`/workspace/input`***\*, \****`/workspace/output`***\*, or \****`/workspace/logs`**\*\* may contain a raw secret** — a post-execution scan runs `RedactionPolicyService` (story 1.10) against every file in the workspace and fails the execution as `runner_secret_leak` if any secret pattern is detected, quarantining the workspace.
5. **Given** runner stdout/stderr capture (story 3.6), **Then** raw runner output is also passed through `RedactionPolicyService` before being written to `logs/` files — runner CLIs that incorrectly log their own auth headers cannot leak through the file layer.
6. **Given** the architecture's security upgrade triggers (raw logs / context bundles / runner outputs retained beyond MVP retention window), **Then** an explicit ADR `docs/adr/0002-runner-secrets-mvp-posture.md` documents the MVP secrets handling: env-var injection only, no OS keychain, no Docker secrets daemon, and the exact upgrade triggers that would force re-evaluation (multi-user, hosted, cross-repo runners).
7. **Given** `.env.example` documentation (story 1.1 AC7 + 1.2 AC5), **Then** every runner-required secret has a documented placeholder name in `.env.example` with a comment explaining the service it authenticates to and how to obtain the credential — pilot installers know exactly what to set without secrets ever being committed.
8. **Given** the doctor command (story 1.16) supported-environment + secret presence checks, **Then** doctor reports per-runner-kind whether the required secret env vars are present (PASS) or missing (FAIL with `DOCTOR_RUNNER_SECRET_MISSING` and remediation pointer to `.env.example`) — but doctor never prints secret values, only presence.
9. **Given** secret rotation, **Then** rotating a secret in `.env` and restarting the backend immediately picks up the new value for subsequent dispatches — no in-memory caching of secrets beyond the request scope; existing in-flight runner containers continue with their dispatch-time values until completion.
10. **Given** ArchUnit boundary rule, **Then** `RunnerSecretsService` may only be called from `application.runner` services + `DockerRunnerAdapter`; no controller, no REST adapter, no other application service may resolve secrets directly — preventing accidental leak surfaces.
11. **Given** the test suite, **Then** tests cover: secret resolution from env vars, secret absence raises `DOCTOR_RUNNER_SECRET_MISSING` at dispatch time (not at server startup — runners may be optional in early pilot phases), workspace post-execution scan catches a deliberately-leaked secret in `output/`, log redaction catches a deliberately-leaked secret in `logs/runner.stdout`, secrets never appear in `docker inspect` output (verified by labeling check from story 3.2 AC7 not exposing secret values), secrets absent from context bundles persisted in story 3.8.

### Story 3.6: Runner Logs Capture + Redaction + Classification

As a backend developer + workflow owner,
I want raw runner stdout/stderr captured to local files, redacted via `RedactionPolicyService` (story 1.10), classified per `DataClassification` registry, and linked to `runner_executions` rows for diagnostic inspection,
So that NFR24 (runner execution records link normalized output, raw output reference, produced artifacts, and context bundle) holds — and operators can diagnose runner failures without exposing raw secrets to logs or downstream consumers (ELK in story 3.7, exports in Epic 5).

**Acceptance Criteria:**

1. **Given** `RunnerLogCaptureService` in `application.runner`, **Then** it exposes `captureLogs(runnerExecutionId, stdout, stderr) → CapturedLogs` writing redacted log files to `{DELIVERYLINE_HOME}/runner-logs/{runnerExecutionId}/` and returning typed references.
2. **Given** the log-capture pipeline, **When** `DockerRunnerAdapter` (story 3.1) detects container exit, **Then** it copies `/workspace/logs/runner.stdout` and `/workspace/logs/runner.stderr` from the container's mounted log dir to the local log dir, passing each through `RedactionPolicyService` (story 1.10) before write — raw unredacted content is never persisted outside the container's ephemeral lifetime.
3. **Given** the `runner_executions` table (from story 1.3), **Then** the schema includes `raw_output_reference text NULL` (added in Flyway V3 by this story — file path to redacted log dir), `raw_output_classification text NULL` (CHECK-constrained to `DataClassification` registry values), `raw_output_byte_size bigint NULL`, `redaction_count integer NULL` (count of secret patterns redacted — for monitoring redaction effectiveness).
4. **Given** classification per `DataClassification` registry, **Then** raw runner logs are classified `local-only` by default unless the redaction pass detected zero secrets AND the user explicitly opts to elevate via a documented config (`deliveryline.runner.allow-shareable-logs=true` — default false); a contract test asserts `local-only` classification flows through to ELK shipping policy in story 3.7 (local-only classification = NOT shipped).
5. **Given** the log retention policy, **Then** runner log files follow the same 60-day default retention as workflow events (NFR31), but are subject to early cleanup by story 3.2's workspace cleanup job for the source workspace; the **redacted local log files** persist independently for the full retention window.
6. **Given** structured log fields per AR29, **Then** workflow events emitted by `RunnerLogCaptureService` include `runnerExecutionId`, `workflowRunId`, `redactionCount`, `byteSize`, `classification` — enabling later analytics on redaction effectiveness.
7. **Given** WorkflowInspectionService extension, **Then** `getRunnerLogReference(runnerExecutionId)` returns the typed `RunnerLogReference` (path, byte size, classification, redaction count) — surfaced via CLI `deliveryline status {runId} --include-runner-logs` (CLI flag added here) and the REST endpoint from story 6.9 detail expansion in story 3.27 GitHub PR linkage display.
8. **Given** ArchUnit boundary, **Then** raw (unredacted) runner output is touched only by `DockerRunnerAdapter` momentarily during capture, immediately handed to `RedactionPolicyService`; no other class may hold raw runner output references — verified by an ArchUnit field-access test.
9. **Given** ELK shipping policy from story 3.7, **Then** runner logs classified `local-only` are NOT shipped to ELK — the shipping pipeline filters by classification; redacted-but-still-local-only logs remain inspectable locally only.
10. **Given** the foundation gate from story 1.23, **Then** the adversarial redaction fixture set is extended in this story to include common runner CLI auth-leak patterns (Codex CLI auth header logging, Claude CLI verbose-mode token printing, generic `Authorization: Bearer ...` headers in HTTP debug output) — story 1.23's fixture-completeness assertion fails if any runner CLI's known leak pattern is missing from the fixture set.
11. **Given** the test suite, **Then** tests cover: redaction applied on capture (a deliberately-leaky stdout produces a clean log file), zero-secret logs may be elevated to `shareable-redacted` per config, redaction-count metric matches manual count for adversarial fixtures, log-reference inspection returns expected fields, classification gate prevents `local-only` logs from reaching ELK shipping, CLI `--include-runner-logs` flag returns the typed reference.

### Story 3.7: ELK Stack Integration — Centralized Log Capture (Profile-Gated)

As a workflow owner debugging runner failures or tracing audit trails across many runs,
I want Elasticsearch + Logstash + Kibana added to the unified `docker-compose.yml` under the `observability` profile + a Logstash pipeline ingesting structured Spring Boot logs (story 1.19) and redacted runner logs (story 3.6),
So that AR25's observability decision (now ELK + Prometheus + Grafana, replacing the prior Loki proposal) is concretely realized — and ops/diagnostic workflows have searchable log history without becoming required for normal operation.

**Acceptance Criteria:**

1. **Given** the unified `docker-compose.yml` from story 1.2, **Then** this story adds three service definitions tagged with the `observability` compose profile: `elasticsearch` (single-node, version pinned), `logstash` (with mounted pipeline config), `kibana` (linked to elasticsearch) — the file remains a single `docker-compose.yml`; profile gating prevents these from starting on default `docker compose up -d`.
2. **Given** `.env`-configurable ports per story 1.2 AC4, **Then** `ELASTIC_HOST_PORT` (default 9200), `LOGSTASH_HOST_PORT` (default 5044), `KIBANA_HOST_PORT` (default 5601) are added to `.env.example` with default values — pilot installers can override on collision.
3. **Given** `infra/observability/logstash/pipelines/deliveryline.conf`, **Then** a Logstash pipeline ingests: (a) Spring Boot structured JSON logs via TCP/JSON-over-port-5044 (Spring Boot's JSON appender ships here when `observability` profile is active), (b) redacted runner log files (story 3.6) via filebeat-style file input watching `{DELIVERYLINE_HOME}/runner-logs/`.
4. **Given** classification-driven shipping policy per story 3.6 AC9, **Then** the Logstash pipeline filters out any document with `classification: "local-only"` — only `shareable-redacted` and above reach Elasticsearch; a contract test injects a fixture log with `local-only` classification and asserts it is dropped before indexing.
5. **Given** redaction enforcement, **Then** as a defense-in-depth measure, the Logstash pipeline runs a **second** redaction pass via a `grok`+`mutate` filter chain matching the same patterns as `RedactionPolicyService` adversarial fixtures (story 1.10) — even though source-side redaction (story 3.6) should have caught everything, an extra net catches drift.
6. **Given** Kibana dashboards, **Then** `infra/observability/kibana/dashboards/` contains saved-objects JSON files for: workflow events dashboard (events by type / failure category / time), runner executions dashboard (dispatched / completed / timed-out / orphaned counts + duration heatmap), redaction audit dashboard (redaction counts by source + secret-pattern category), failure-category distribution (FR37 + FR38 taxonomy applied) — Kibana imports them on first startup via a startup hook documented in `infra/observability/README.md`.
7. **Given** AR25 hard rule "must NOT be required for normal workflow execution, tests, or recovery", **Then** the backend's Spring Boot profile detection: when `observability` is NOT active, no Logstash appender is configured (logs go to stdout only); when `observability` IS active, the JSON appender ships to `${LOGSTASH_HOST}:5044` — the backend starts and runs identically with or without ELK, verified by a smoke test that runs the bundled jar with the observability profile disabled and asserts no Logstash connection attempts.
8. **Given** the `start-all.{ps1,sh}` script (story 1.17 AC3 update), **Then** `start-all` invokes `docker compose --profile observability up -d` to bring up everything including ELK; documented as the "give me the full stack" convenience wrapper.
9. **Given** ADR documentation, **Then** `docs/adr/0003-elk-replaces-loki.md` records: ELK chosen over Loki for richer query/full-text-search capabilities, accepting the tradeoff of higher memory footprint; Prometheus + Grafana retained for metrics; lifecycle management of indices (e.g., 30-day retention via Index Lifecycle Management — ILM policy committed alongside Logstash config).
10. **Given** memory budget, **Then** Elasticsearch's heap is configured via `ES_JAVA_OPTS` env var with a documented sane default (e.g., `-Xms512m -Xmx512m` for a dev box) — pilot installers with low-memory machines can opt out by simply not enabling the `observability` profile; doctor (story 1.16) WARNs (does not FAIL) if `observability` is active and host has <8 GB total memory.
11. **Given** the test suite, **Then** integration tests cover: ELK stack starts with `--profile observability up -d`, Logstash pipeline accepts a sample JSON log via TCP and indexes it in Elasticsearch, classification-`local-only` log is dropped by pipeline filter, double-redaction filter catches a fixture that source-side missed, Kibana dashboards load on first startup, `observability`-disabled backend produces zero Logstash connection attempts (verified by mocking the network layer).
12. **Given** cross-platform considerations (story 1.17 supported-environment matrix), **Then** the ELK stack runs on Docker Desktop (Win/macOS) and Docker Engine (Linux/WSL2) without OS-specific config; the Elasticsearch `vm.max_map_count` requirement on Linux hosts is documented in `docs/setup-local.md` with the remediation `sudo sysctl -w vm.max_map_count=262144`.

### Story 3.8: Real Docker Runner Contract Integration Test

As a runner-infrastructure developer,
I want a Testcontainers-based integration test running the **real** Codex + Claude runner images against the `RunnerContractValidator` from story 1.6 — proving end-to-end conformance (input bundle → container execution → result file → schema validation → artifact ingest),
So that the foundation contract from story 1.23 is exercised against real runners (not just mocks), and any contract drift between the runner-contracts module + DockerRunnerAdapter + actual runner image surfaces in CI before reaching pilot.

**Acceptance Criteria:**

1. **Given** `backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java`, **Then** it uses Testcontainers to spin up: a PostgreSQL container (the test schema), a Codex runner container built from `runners/codex/Dockerfile` (story 3.3), a Claude runner container built from `runners/claude/Dockerfile` (story 3.4) — the test is tagged `@Tag("real-runner-contract")` so it runs in the dedicated CI tier (story 3.28) but not in the fast unit-test tier.
2. **Given** the test scenarios, **Then** it covers per runner kind (Codex, Claude): happy-path success producing a valid `spec` artifact result, happy-path success producing a valid `implementationPlan` artifact result, happy-path success producing a valid `prOutput` artifact result, deliberate failure injection via `--simulate-failure=timeout` (story 3.3 AC11 + story 3.4 AC7), `--simulate-failure=crash`, `--simulate-failure=contract_violation`.
3. **Given** the foundation fixture event stream (story 1.23) artifact-variant fixtures, **Then** this test reuses those fixtures as inputs — proving the same fixture data that drove E2 UI development drives real runner execution end-to-end.
4. **Given** schema conformance assertions, **Then** for each happy-path scenario the test reads the produced `runner-result.json` and validates it via `RunnerContractValidator` — failure means the runner image is out of contract conformance, blocking the PR.
5. **Given** mock vs real parity, **Then** the test asserts that the `RunnerAdapter` port behavior is identical between `MockRunnerAdapter` (story 1.13) and `DockerRunnerAdapter` (story 3.1) for matched scenarios — same `runner_executions.status` outcomes, same `failure_category` classifications, same workflow events emitted (event types + actor types match; only `actor_identity` and timestamps differ).
6. **Given** secrets handling per story 3.5, **Then** the test injects mock secret values via env var (e.g., `LINEAR_API_KEY=test-mock-key-not-real`) and asserts the post-execution workspace scan finds zero secret leaks — proving the secrets pipeline holds end-to-end.
7. **Given** logs capture per story 3.6, **Then** the test asserts each execution produces a redacted log file at `{DELIVERYLINE_HOME}/runner-logs/{runnerExecutionId}/` with appropriate classification — and that injecting a deliberate auth-header leak in the runner's stdout is redacted before persistence.
8. **Given** lifecycle behavior per story 3.2, **Then** the test covers: timeout enforcement (a runner deliberately sleeping past the configured timeout is killed and marked `timed_out`), heartbeat staleness, broker-restart recovery (shut down the JVM mid-execution, restart, confirm proper resumption or orphan flagging), workspace cleanup after retention.
9. **Given** the `foundation-gate` CI verification from story 1.23 widening per stories 2.27/2.28/2.30/2.31, **Then** this story extends it again: the foundation gate now includes "real-runner-contract test green on the branch" — protecting against runner-image drift on every PR including E4+.
10. **Given** the dedicated CI tier per story 3.28, **Then** this test runs in the `runner-contract-real` job which depends on `runner-image-build` from story 3.28 (image must exist before the test consumes it); the job uses `ubuntu-latest` per story 1.21 AC3 (Docker-backed jobs Linux-only).
11. **Given** test isolation, **Then** each test scenario uses a fresh per-test `DELIVERYLINE_HOME` directory (created via JUnit `@TempDir`) and a fresh PostgreSQL schema (Testcontainers cleanup) — no cross-test state pollution.
12. **Given** failure diagnostics, **Then** when a scenario fails, the test surfaces: the failed runner's logs (already redacted), the `runner_executions` row with status + failure_category, the workflow_events emitted during the run, and the offending fixture path — making debugging tractable without reproducing the test locally.

### Story 3.9: Repository Workspace Service — Git Clone, Branch Management, Commit/Push

As a backend developer + workflow orchestrator,
I want a `RepositoryWorkspaceService` that clones the linked GitHub repository into a per-execution workspace, checks out a deterministic feature branch (`deliveryline/{ticketRef}/stage-{runId}`), mounts the working tree into the runner container, captures runner-produced changes after exit, commits + direct-pushes to the target repo, and opens or updates the GitHub PR — failing-and-deferring-to-recovery on push rejection per the architecture's "pause when state uncertain" rule,
So that runners (Codex/Claude per stories 3.3/3.4) have an actual code repository to read, edit, and produce a PR against — closing the gap between runner infrastructure (3.1–3.8) and orchestration (3.10+).

**Acceptance Criteria:**

1. **Given** the `application.runner.workspace` package, **Then** `RepositoryWorkspaceService` exists with methods: `prepareWorkspace(workflowRunId, stage, runnerExecutionId, linearTicketRef, repositoryRef)`, `captureAndPush(runnerExecutionId)`, `cleanupWorkspace(runnerExecutionId)`.
2. **Given** `prepareWorkspace(...)` invocation by `DockerRunnerAdapter` (story 3.1) before container launch, **Then** the service: (a) reads the linked GitHub repository via `GitHubAdapter.getRepositoryByRef(repositoryRef)` (story 3.13), (b) clones the repo into `{DELIVERYLINE_HOME}/runner-work/{runnerExecutionId}/repo/` using HTTPS authenticated by the `GITHUB_TOKEN` PAT from story 3.5 (never logs the auth URL — uses `git -c credential.helper=...` or env-var-injected helper), (c) creates and checks out the deterministic branch `deliveryline/{linearTicketRef}/stage-{runIdShort}` where `runIdShort` is the last 8 chars of the run ID, (d) configures `user.email` and `user.name` to the documented `deliveryline-bot` service-account identity (e.g., `deliveryline-bot@dradgo.org`).
3. **Given** branch creation idempotency across retries (story 3.11 retry path), **When** the deterministic branch already exists locally or remotely, **Then** the service: (a) if remote branch exists with prior commits → fetches and resets local to remote tip (preserving prior runner work for retry context), (b) if only local exists from a partially-completed prior attempt → reuses, (c) clean state always — no half-merged conflicting state ever reaches the runner.
4. **Given** the runner container's mount layout from story 3.1, **Then** `prepareWorkspace` returns mount references that `DockerRunnerAdapter` adds to the `docker run` invocation: `/workspace/repo` (read-write — runner needs to edit + commit), alongside the existing `/workspace/input` (read-only), `/workspace/output` (read-write), `/workspace/logs` (read-write).
5. **Given** sparse-checkout / shallow-clone configuration for performance, **Then** per-repository config (`deliveryline.workflow.repos.{repo-key}.clone-depth`, default 1 for shallow; `deliveryline.workflow.repos.{repo-key}.sparse-paths`, optional list) lets pilots tune for large repos — defaults work for typical pilot-size repos without configuration.
6. **Given** `captureAndPush(runnerExecutionId)` invocation after runner exits successfully, **Then** the service: (a) inspects the workspace's git state, (b) if uncommitted changes exist (the runner may have edited but not committed) → stages all + commits with a documented message template referencing the ticket + run + stage, (c) pushes the branch to the target repo's remote, (d) on success returns the pushed commit SHA + branch reference for inclusion in the runner's `prOutput` artifact (story 3.11 AC2).
7. **Given** **fail-and-defer-to-recovery on push rejection** per the architecture's "pause when state uncertain" rule, **When** `git push` is rejected (target branch advanced, force-push policy violation, branch protection rejected push, network failure mid-push), **Then** the service: (a) classifies the failure per `IntegrationFailureCategory` registry (`GIT_PUSH_REJECTED`, `GIT_BRANCH_PROTECTION_VIOLATION`, `GIT_NETWORK_FAILURE`, `GIT_AUTH_FAILED`), (b) appends a `git.pushFailed` event with details, (c) raises the typed failure to `WorkflowOrchestrationService` which transitions the run to `Failed` with the failure category — **never auto-rebases, never force-pushes, never silently retries** (deeper recovery actions live in Epic 4).
8. **Given** PR creation/update via `GitHubAdapter` (story 3.13), **When** push succeeds, **Then** the service: (a) checks if a PR already exists for the deterministic branch (idempotent across retries) via `GitHubAdapter.getPullRequestByRef(...)`, (b) if not → creates a new draft PR with title `[{ticketRef}] {ticketSummary}` and body referencing the governed run, (c) if exists → updates the PR description to reflect the latest run + commit references, (d) returns the canonical PR reference for inclusion in the `prOutput` artifact.
9. **Given** wrong-ticket / wrong-repo prevention per NFR20 + story 3.14 AC4, **When** the workflow's existing Linear↔GitHub repo mapping does not match the requested `repositoryRef`, **Then** `prepareWorkspace` raises `LINEAR_GITHUB_REPO_MISMATCH` before any clone — preventing accidental work in the wrong repo.
10. **Given** secrets handling per story 3.5, **Then** the GITHUB_TOKEN used for clone + push is never logged (stdout, stderr, structured logs all redacted), never written to git config files persisted in the workspace, and never exposed to the runner container — auth is handled at the host process level before mounting; the runner sees only the cloned working tree, not the credential helper config.
11. **Given** `cleanupWorkspace(runnerExecutionId)` invocation by `RunnerWorkspaceCleanupJob` (story 3.2 AC5), **Then** the entire workspace including the cloned repo is deleted after the configurable retention threshold (`deliveryline.runner.workspace-retention-hours`, default 24) — same retention rules as story 3.1 AC7 apply.
12. **Given** the architecture's append-only history requirement (NFR4 + NFR32), **Then** captureAndPush's commit message includes a stable trailer (`Deliveryline-Run: {runId}`, `Deliveryline-Stage: {stage}`, `Deliveryline-RunnerExecution: {rexId}`) — git commit history carries durable governance traceability without DeliveryLine being the only place the linkage exists.
13. **Given** ArchUnit boundary, **Then** `RepositoryWorkspaceService` lives in `application.runner.workspace`; its only collaborators are `GitHubAdapter` (story 3.13), `RunnerSecretsService` (story 3.5), and `LocalFilesystem` operations — no direct dependency on `DockerRunnerAdapter` (which calls into this service, not the other way), no leak of git library types into domain.
14. **Given** the test suite, **Then** integration tests (using a Testcontainers-launched gitea or local bare repo as a stand-in for GitHub) cover: clean clone + branch checkout, idempotent branch reuse on retry, runner-produced changes captured + committed, successful push + PR creation, push-rejected scenarios produce `GIT_PUSH_REJECTED` and transition to `Failed` (no auto-retry), `LINEAR_GITHUB_REPO_MISMATCH` on incompatible request, secret-leak scan asserts no GITHUB_TOKEN in workspace files or logs, cleanup respects retention.
15. **Given** the doctor command (story 1.16), **Then** when GitHub real adapter is active, doctor probes `git --version` (PASS / FAIL with `DOCTOR_GIT_MISSING`) and validates the configured `deliveryline-bot` identity (PASS / WARN with `DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED`) — pilots see git prerequisite issues before attempting a real run.

### Story 3.10: Full Context Bundle Generation for Implementation Stage

As a backend developer,
I want `ContextBundleService` to produce full implementation-stage context bundles (extending the spec-stage baseline from story 1.13 + 2.8) carrying approved spec + prior reviewer decisions + implementation constraints + repository workspace reference,
So that runners dispatched at the implementation stage have everything they need to act without reconstructing context — fulfilling FR54 (full bundle generation) and FR55 (inspectable bundles for an agent step).

**Acceptance Criteria:**

1. **Given** `ContextBundleService.create(workflowRunId, stage)` from story 1.13, **Then** when `stage='implementation-plan'` it assembles: ticket summary (Linear adapter), **approved spec artifact reference** (story 2.8 `getCurrentApprovedSpec`), prior PM decision history (approvals + rejection feedback rows), incorporated clarification answers (status `incorporated` per story 2.12), execution constraints, classification metadata.
2. **Given** `stage='pr-output'` (the implementation-execution stage that produces the PR/output artifact), **Then** the bundle additionally includes: **approved implementation-plan artifact reference**, technical-feedback history if a prior plan version was rejected, repository context from GitHub adapter (story 3.14), expected branch name from the deterministic branch convention (story 3.9 AC2), and a pointer to the `/workspace/repo` mount where the runner finds the working tree.
3. **Given** the runner-contracts v1 schema (story 1.6), **Then** the produced bundle conforms to `context-bundle.v1.schema.json` and is validated by `RunnerContractValidator` before persistence — invalid bundles fail at the boundary.
4. **Given** redaction per story 1.10 + secrets exclusion per story 3.5, **Then** the bundle excludes credentials, raw `.env` content, runner secrets, absolute machine paths revealing usernames — adversarial fixture tests assert no Linear API key, GitHub token, or `C:\Users\{name}` path appears in the persisted bundle even when the source data contains them.
5. **Given** bundle versioning, **Then** each bundle is persisted with a monotonic `contextBundleVersion` per workflow run + stage; a re-dispatch (e.g., after spec rejection rebuild) creates a new version — never overwrites; older versions remain inspectable for FR55.
6. **Given** `WorkflowInspectionService.getContextBundleForArtifact(artifactId)` extension from story 2.8, **Then** it now returns the full implementation-stage bundle when the artifact is `implementationPlan` or `prOutput` — bundle content visible in CLI `deliveryline status {runId} --include-context-bundle` and via REST detail expansion.
7. **Given** the architecture rule that artifact references in bundles are stable identifiers (not raw payloads), **Then** the bundle carries `artifactReferences: [{ artifactId, artifactType, version, payloadRef, checksum }]` — runners read payloads via the file system from the mounted input dir, never embedding payload content inline.
8. **Given** ArchUnit boundary, **Then** `ContextBundleService` lives in `application.runner` and depends only on application services + domain types — no adapter types leak into bundle composition; redaction goes through `RedactionPolicyService` (story 1.10), not bespoke regex.
9. **Given** the test suite, **Then** tests cover: implementation-plan bundle composition (with approved spec + PM decisions + clarifications), pr-output bundle composition (with approved plan + repo context + branch reference), bundle versioning across re-dispatch, redaction adversarial fixtures, schema validation rejection of malformed bundles, FR55 inspection returns the bundle used by a specific artifact's runner execution.
10. **Given** the foundation fixture event stream (story 1.23) extension scope, **Then** at least one fixture run includes a full implementation-stage bundle so E2 UI tests + future E3 dev-review UI (story 3.23+) have realistic fixture data for the `--include-context-bundle` inspection path.

### Story 3.11: Implementation-Plan Artifact Generation Flow (Orchestration)

As a Product Manager (downstream consumer) and Developer (next reviewer),
I want the workflow orchestration: spec-approved → repository workspace prepared (story 3.9) → runner dispatched → implementation-plan artifact produced → workflow paused at `WaitingForReview` for the plan,
So that FR14 (developer access to approved spec + workflow context) and FR15 (developer reviews implementation output — plan side) are wired end-to-end through the runner broker, repository workspace, artifact operations, and state-transition service.

**Acceptance Criteria:**

1. **Given** `WorkflowOrchestrationService.dispatchPlanGeneration(workflowRunId)` in `application.workflow`, **When** invoked from the `WaitingForSpecApproval → Executing` transition triggered by spec approval (story 2.9), **Then** it: (a) calls `RepositoryWorkspaceService.prepareWorkspace(...)` (story 3.9) to clone + checkout the feature branch, (b) calls `ContextBundleService.create(workflowRunId, 'implementation-plan')` (story 3.10), (c) calls `RunnerBroker.dispatch(...)` with the bundle + workspace mount references, (d) the broker uses `DockerRunnerAdapter` (story 3.1) to run the configured runner kind (per `application.yml` `deliveryline.runner.plan-stage.kind`).
2. **Given** the runner produces a valid `runner-result.v1.json` with `artifactType=implementationPlan`, **Then** `RunnerBroker` invokes `ArtifactOperationService.recordOperation(...)` (story 1.12) creating the implementation-plan artifact + appending events; `markAvailable(...)` is called after the plan payload is persisted via `LocalArtifactStore` (story 1.12 AC8).
3. **Given** the implementation-plan artifact becomes `available`, **Then** an automatic state transition fires: `WorkflowTransitionService.transition(workflowRunId, 'WaitingForReview', actor='system', reason='implementation_plan_ready')` — the workflow now waits for developer review via stories 3.17/3.20/3.22.
4. **Given** runner failures from story 3.1/3.2 (timeout, crash, contract violation, non-zero exit) OR repository workspace failures from story 3.9 (clone failed, push rejected for any post-runner pushes), **When** detected, **Then** the orchestration: (a) appends the appropriate failure event, (b) transitions to `Failed` with `failure_category` from the registry, (c) preserves the failed `runner_executions` row + redacted logs (story 3.6) + workspace (story 3.1 retention) for diagnostic inspection.
5. **Given** retry from CLI baseline (story 1.18) or UI (story 3.27), **Then** `WorkflowOrchestrationService.retryPlanGeneration(workflowRunId)` re-prepares the workspace (idempotent branch reuse per story 3.9 AC3) and re-dispatches with a fresh `runnerExecutionId` + fresh context bundle version (capturing any clarifications incorporated since the failure) — preserves prior failure event + runner_executions row for audit.
6. **Given** idempotency, **Then** repeated calls to `dispatchPlanGeneration` for the same `workflowRunId` while a plan-stage execution is already `pending` or `running` are no-ops returning the existing `runnerExecutionId` — never doubly-dispatch.
7. **Given** correlation propagation per story 1.19, **Then** every event in the chain (spec approval → workspace prepare → orchestration dispatch → runner events → artifact events → state transition) carries the same `correlationId` originating from the spec-approval REST/CLI command — enabling end-to-end log tracing.
8. **Given** the artifact-variant discriminator from story 1.6 / party-mode finding #2, **Then** if the runner produces a result with `artifactType` other than `implementationPlan` at the plan stage, the orchestration rejects it with `RUNNER_ARTIFACT_TYPE_MISMATCH` — runners can't accidentally emit a spec or PR artifact when the workflow expected a plan.
9. **Given** ArchUnit boundary, **Then** `WorkflowOrchestrationService` is the only path that auto-triggers state transitions on runner success/failure — no other service may auto-advance state from runner outcomes (the rule from story 1.5 widens here).
10. **Given** the test suite, **Then** integration tests cover: end-to-end happy path (approve spec → workspace prepared → plan generated → state at WaitingForReview), each runner failure mode → state at Failed with correct failure_category, workspace failure modes (clone failed, push rejected) → state at Failed with git failure category, retry after failure preserves prior events + creates new runnerExecutionId, idempotent re-dispatch is a no-op, `RUNNER_ARTIFACT_TYPE_MISMATCH` on type-mismatched output, correlationId propagates through the full event chain.
11. **Given** `correlationId` propagated to the runner via the context bundle (story 3.10 extension), **Then** the runner's logs include the correlationId in their output (story 3.3 + 3.4 entrypoints prepend the value) — proving full-stack traceability from REST request through to runner stdout.

### Story 3.12: PR/Output Artifact Generation Flow (Orchestration)

As a Developer reviewing implementation output,
I want the workflow orchestration: plan-approved → repository workspace prepared (idempotent reuse per story 3.9) → runner dispatched → PR/output artifact produced (carrying branch + commit + PR refs from story 3.9's push) → workflow paused at `WaitingForReview` for the PR/output,
So that FR15 (developer reviews implementation output — PR side), FR20 (relationship between implementation output, PR linkage, and review outcome), and FR40 (link governed implementation output to GitHub / PR refs) are wired end-to-end.

**Acceptance Criteria:**

1. **Given** `WorkflowOrchestrationService.dispatchImplementation(workflowRunId)` triggered by plan approval (developer's `acceptImplementation` of the plan via story 3.17 OR an automatic dual-stage flow if the plan passes acceptance criteria — depends on story 3.17 wiring), **When** invoked, **Then** it: (a) calls `RepositoryWorkspaceService.prepareWorkspace(...)` (story 3.9 — idempotent branch reuse since the plan stage already created the branch), (b) calls `ContextBundleService.create(workflowRunId, 'pr-output')` (story 3.10), (c) calls `RunnerBroker.dispatch(...)` with the bundle, (d) targets the runner kind configured for the implementation stage (`deliveryline.runner.implementation-stage.kind`).
2. **Given** the runner exits successfully having edited files in `/workspace/repo`, **Then** `RepositoryWorkspaceService.captureAndPush(runnerExecutionId)` (story 3.9 AC6) is invoked — staging, committing, pushing, and creating/updating the GitHub PR; the returned commit SHA + branch + PR reference are passed back to `RunnerBroker`.
3. **Given** the runner produces a `runner-result.v1.json` with `artifactType=prOutput`, **Then** the orchestration enriches the artifact's payload with the actual `branch`, `commitSha`, `prReference`, `diffReference` from `captureAndPush` (the runner's reported values are validated against actual git/GitHub state — discrepancies raise `RUNNER_PR_REF_DRIFT`), then `ArtifactOperationService` ingests the artifact + emits `artifact.created` event with `artifactType=prOutput`.
4. **Given** the PR/output artifact's `prReference`, **Then** `IntegrationLinkService.linkGitHubPr(workflowRunId, prReference, ...)` (story 3.15) is invoked transactionally — creating an `integration_links` row of type `github_pr` carrying repository identity, branch, commit SHA, PR number, with NFR17 durability.
5. **Given** the PR/output artifact is `available` AND the GitHub PR linkage row is created, **Then** state transitions to `WaitingForReview` with reason `pr_output_ready` — workflow now awaits developer technical review.
6. **Given** wrong-ticket attach prevention per NFR20, **When** `linkGitHubPr` detects the linked PR's repo conflicts with the workflow's prior repository linkage (impossible if story 3.9 AC9 prevented the workspace from being prepared, but defense-in-depth), **Then** the orchestration raises `PR_REF_CONTEXT_MISMATCH` and refuses to advance state — operator intervention required.
7. **Given** runner failures OR git-push rejections from story 3.9 AC7, **Then** the same handling as story 3.11 AC4 applies — failure event with appropriate category (`runner_*` or `GIT_PUSH_REJECTED` etc.), transition to `Failed`, preserve runner_executions + logs + workspace (including the branch on the local clone for diagnostic git inspection).
8. **Given** retry behavior, **Then** `WorkflowOrchestrationService.retryImplementation(workflowRunId)` re-dispatches with a fresh `runnerExecutionId` + fresh context bundle version — and `linkGitHubPr` uses idempotent linkage so a retry that targets the same PR reference does not create a duplicate `integration_links` row.
9. **Given** runner output as untrusted per story 2.24, **Then** `prReference`, `branch`, `commitSha`, `diffReference` strings are validated against documented patterns (e.g., branch name must match `^[a-zA-Z0-9._/-]+$`, PR reference must match `^PR-\d+$` or canonical GitHub PR URL format) — malformed strings raise `RUNNER_OUTPUT_VALIDATION_FAILED` and the artifact is rejected.
10. **Given** correlation propagation, **Then** the same `correlationId` chain from story 3.11 AC7 extends through plan-approval → workspace prepare → implementation dispatch → runner events → push event → PR linkage event → state transition.
11. **Given** the test suite, **Then** integration tests cover: happy path (approve plan → workspace prepared → runner edits → captureAndPush → PR/output artifact created → GitHub linkage → state at WaitingForReview), runner failure modes, push-rejection failure mode (story 3.9 AC7), retry preserves audit + idempotent linkage, PR_REF_CONTEXT_MISMATCH on conflicting repo, RUNNER_PR_REF_DRIFT when runner reports refs that differ from actual git state, malformed PR ref / branch / commit SHA rejected, correlationId propagation.
12. **Given** the `RecoveryService` scope-protected baseline from story 1.18 AC11, **Then** PR/output retry uses the baseline `retry` action only — deeper recovery (revert the PR linkage, take over with manual PR creation, reconcile diverged branch state, force-push to overwrite drifted state) lives in Epic 4 and any attempt to add such methods here fails the scope-protection ArchUnit rule.

### Story 3.13: Mock GitHub Adapter

As a backend developer + foundation/contract test suite,
I want a deterministic `GitHubMockAdapter` implementing the `GitHubAdapter` port — backed by an in-memory or file-seeded fixture PR/branch/repo set,
So that the full execution loop (story 3.12) can be demonstrated and contract-tested without real GitHub API access (parallel to story 1.14 mock Linear), satisfying AR35.

**Acceptance Criteria:**

1. **Given** the `application.integration` package, **Then** a `GitHubAdapter` port exists in `adapters.integration.github` carrying domain-shaped methods only: `getRepositoryByRef(repoRef)`, `getPullRequestByRef(prRef)`, `getBranchByRef(repoRef, branchName)`, `createPullRequest(repoRef, branch, title, body)`, `updatePullRequest(prRef, body)`, `commentOnPullRequest(prRef, body)` — GitHub-specific types (REST API DTOs, GitHub auth tokens) must not leak through (verified by ArchUnit in story 1.11 + this story extends).
2. **Given** `GitHubMockAdapter` activated by Spring profile `github-mock` (default in `test`, optional in `local`/`demo`), **Then** it implements `GitHubAdapter` backed by an in-memory or file-seeded fixture set under `backend/src/test/resources/github-fixtures/`.
3. **Given** the fixture set, **Then** at minimum it includes: 3 fixture repositories (matching the 3 fixture tickets from story 1.14 mock Linear), 1 fixture PR per repo (open state), 1 fixture branch per repo (matching the PR's source branch) — each with stable IDs and deterministic metadata.
4. **Given** wrong-ticket / wrong-repo prevention per NFR20, **Then** the mock supports a "deliberate-conflict" fixture mode where calling `getPullRequestByRef('PR-conflict')` returns a PR whose repo conflicts with what the workflow has linked — used by story 3.12 AC6 PR_REF_CONTEXT_MISMATCH test.
5. **Given** failure simulation, **Then** the mock supports configurable error injection: `getRepositoryByRef('repo-not-found')` returns 404 simulating `GITHUB_REPO_NOT_FOUND`; `getPullRequestByRef('pr-403')` simulates `GITHUB_PERMISSION_DENIED`; `commentOnPullRequest('pr-rate-limited', ...)` simulates `GITHUB_RATE_LIMITED`; `createPullRequest(...)` with branch `protected-branch` simulates `GITHUB_BRANCH_PROTECTED` — failures classify per `IntegrationFailureCategory` registry per story 1.4.
6. **Given** the unified compose file (story 1.2 + AR24), **When** running with `github-mock` profile, **Then** no GitHub network calls are made — proven by a test that runs the foundation slice with network access blocked, parallel to story 1.14 AC10 for Linear.
7. **Given** ArchUnit boundary, **Then** the mock adapter shares the exact same port interface as the real adapter (story 3.14) — switching profile activates the real implementation with no orchestration code change.
8. **Given** idempotency at the mock layer, **Then** repeated calls to `commentOnPullRequest` / `createPullRequest` with the same content + same target do not stack duplicate fixture records (matches the real adapter's idempotency behavior in story 3.14).
9. **Given** the fixture mock's seed data, **Then** it is documented in `backend/src/test/resources/github-fixtures/README.md` with each fixture's intended use case and which test scenarios consume it.
10. **Given** the foundation gate (story 1.23) widens with each adapter addition, **Then** this story extends the gate to assert: `GitHubAdapter` port exists, mock adapter implements it, mock-vs-real interface parity holds (verified by an interface-method-coverage test once story 3.14 lands).
11. **Given** the test suite, **Then** tests cover: each fixture repo/PR/branch lookup, each error injection produces correct `IntegrationFailureCategory`, idempotent commentOnPullRequest + createPullRequest, no-network execution under `github-mock` profile, ArchUnit assertion no GitHub-specific types leak through the port.

### Story 3.14: Real GitHub Adapter — PR/Branch/Commit Refs + PAT Auth

As a backend developer + pilot installer using real GitHub repositories,
I want a real `GitHubRealAdapter` implementing the `GitHubAdapter` port via the GitHub REST API with personal-access-token authentication, idempotent PR creation/update by repo+branch, and rate-limit awareness,
So that pilots running real implementation work can link governed runs to actual GitHub PRs (FR40, NFR17) — and AR18 (GitHub adapter) is satisfied for production-style usage.

**Acceptance Criteria:**

1. **Given** the `adapters.integration.github` package, **Then** `GitHubRealAdapter` implements `GitHubAdapter` (story 3.13 port) using GitHub's REST API v3 (or GraphQL — choice documented in `docs/adr/0004-github-rest-vs-graphql.md`) — version-pinned client library.
2. **Given** authentication, **Then** the adapter authenticates via personal-access-token (PAT) read from `GITHUB_TOKEN` env var (story 3.5 secrets handling) — never logged, never embedded in URLs, never persisted in DB or artifacts; auth failures classify as `GITHUB_AUTH_FAILED`.
3. **Given** the `GitHubAdapter` port methods (story 3.13 AC1), **Then** each is implemented against the real API: read methods map straightforward; `createPullRequest(...)` calls `POST /repos/{owner}/{repo}/pulls` with `draft: true` (so PRs land as drafts pending governed review approval); `updatePullRequest(prRef, body)` calls `PATCH /repos/{owner}/{repo}/pulls/{number}`; `commentOnPullRequest` calls `POST /repos/{owner}/{repo}/issues/{number}/comments`. All write methods pass `body` through `RedactionPolicyService` (story 1.10) before sending.
4. **Given** idempotent PR creation per AR18 + NFR18, **Then** before creating, the adapter checks if a PR for the same `(repo, sourceBranch, targetBranch)` already exists — if yes, returns the existing PR ref (no duplicate creation); if no, creates and returns the new PR ref. The `RepositoryWorkspaceService` (story 3.9) deterministic branch convention guarantees consistent (repo, sourceBranch) per workflow run, so retry-after-failure naturally finds and reuses the existing PR.
5. **Given** rate-limit awareness, **Then** the adapter inspects GitHub's `X-RateLimit-Remaining` + `X-RateLimit-Reset` response headers and: (a) logs WARN when remaining drops below a configurable threshold (default 100), (b) raises `GITHUB_RATE_LIMITED` when remaining=0 with a `details.resetAtSeconds` field, (c) the orchestration treats `GITHUB_RATE_LIMITED` as a retryable failure category — workflow pauses, doesn't crash.
6. **Given** redaction-on-egress for write methods, **Then** the body passes through `RedactionPolicyService` before sending — a contract test verifies that posting a comment containing a fixture API key results in a redacted comment posted (or the post being refused with `EGRESS_SECRET_DETECTED` if redaction would alter semantics significantly — documented policy in the security ADR).
7. **Given** failure classification, **Then** GitHub failures map to `IntegrationFailureCategory` registry (story 1.4): `GITHUB_AUTH_FAILED`, `GITHUB_REPO_NOT_FOUND`, `GITHUB_PR_NOT_FOUND`, `GITHUB_PERMISSION_DENIED`, `GITHUB_RATE_LIMITED`, `GITHUB_NETWORK_FAILURE`, `GITHUB_API_VERSION_INCOMPATIBLE`, `GITHUB_BRANCH_PROTECTED` — never a generic unclassified error.
8. **Given** mock-vs-real parity (story 3.13 AC10 reciprocal), **Then** every method on the port behaves identically between mock and real for matched fixtures — proven by a parity test running the same scenario sequence against both adapters and asserting domain-result equivalence (DTO content differs, but typed shape + `IntegrationFailureCategory` outcomes match).
9. **Given** doctor (story 1.16) integration, **Then** when `github-real` profile is active, doctor probes `GET /user` (cheap auth check) and reports PASS / `DOCTOR_GITHUB_AUTH_FAILED` / `DOCTOR_GITHUB_TOKEN_MISSING` accordingly.
10. **Given** ADR `docs/adr/0005-github-write-scope.md`, **Then** it documents the minimum PAT scopes required (e.g., `repo` for private repo linkage, `public_repo` if only public; `pull_requests:write` if commenting; ability to push branches required for story 3.9 captureAndPush) — pilot installers know exactly what scopes to grant when creating a PAT, no over-scoping.
11. **Given** the test suite, **Then** integration tests use a documented test repository (separate from production) for happy-path coverage; unit tests with mocked HTTP layer cover failure classification, rate-limit handling, redaction-on-egress, idempotent PR creation, parity assertions vs the mock.

### Story 3.15: IntegrationLinkService Extended for GitHub PR Linkage

As a backend developer + reviewer reconstructing run lineage,
I want `IntegrationLinkService` extended with `linkGitHubPr(workflowRunId, prReference, repositoryRef, branchName, commitSha, idempotencyKey)` writing to `integration_links` with NFR17-durable references,
So that a reviewer can reconstruct ticket↔repository↔branch/commit lineage↔artifacts↔PR↔run for any governed run (NFR17), conflicts cannot silently overwrite (NFR19), and double-linkage raises `INTEGRATION_LINK_CONFLICT` (story 3.14 AC4).

**Acceptance Criteria:**

1. **Given** the `integration_links` table from story 1.3, **Then** rows for GitHub linkage carry: `id` (`ilk_` prefix), `integration_type='github_pr'`, `workflow_run_id`, `external_ref` (canonical PR identifier — e.g., `org/repo#42`), `external_metadata` (JSONB containing `repositoryFullName`, `branch`, `commitSha`, `prNumber`, `prState`, `prUrl`), `created_at`, `last_sync_at`, `sync_status`.
2. **Given** `IntegrationLinkService.linkGitHubPr(...)`, **When** called, **Then** in one transaction it: (a) verifies the (`repository_full_name`, `pr_number`) is not already linked to a different workflow run, raising `INTEGRATION_LINK_CONFLICT` if so per story 3.14 AC4, (b) inserts the row with idempotency key, (c) appends an `integration.linked` workflow event with details including the PR reference + repo + branch + commit.
3. **Given** NFR17 durability, **Then** the row preserves enough metadata that a reviewer can reconstruct the full chain (run ↔ ticket ↔ repo ↔ branch ↔ commit ↔ artifacts ↔ PR) without needing to re-query GitHub — verified by an inspection test that simulates GitHub being unreachable and asserts the inspection still returns the linked metadata.
4. **Given** NFR20 wrong-ticket prevention, **Then** before linking, the service verifies the workflow's existing Linear ticket linkage (`integration_links` of type `linear_ticket`) and the GitHub PR's referenced repository are compatible — using a documented compatibility rule (e.g., the Linear ticket's project must map to the GitHub repo per a `linear-github-mapping.yml` config; mismatches raise `LINEAR_GITHUB_REPO_MISMATCH` and require operator override).
5. **Given** the ApprovalService (story 2.9 + extensions in stories 3.16/3.17), **Then** approving a `prOutput` artifact requires the artifact's PR reference to match the linked `integration_links.external_ref` — preventing approval of an artifact whose PR reference has drifted from the workflow's recorded link; mismatches raise `ARTIFACT_PR_LINK_MISMATCH`.
6. **Given** sync status, **Then** `IntegrationLinkService.syncGitHubPr(workflowRunId)` calls `GitHubAdapter.getPullRequestByRef(...)` to refresh `external_metadata.prState` (open/closed/merged) and `last_sync_at` — used by orchestration when transitioning toward `Completed` to verify the PR is in mergeable state.
7. **Given** classification, **Then** the GitHub-related metadata in `integration_links.external_metadata` is classified `shareable-redacted` (PR URLs, branch names are shareable; commit SHAs are shareable) — never `local-only` since the entire purpose is team-visible review references.
8. **Given** ArchUnit + foundation-gate widening, **Then** this story widens the foundation gate (story 1.23) to assert `IntegrationLinkService.linkGitHubPr` works end-to-end against the mock adapter as part of the gate scenarios.
9. **Given** retry / re-dispatch behavior from story 3.12 AC8, **Then** if a PR/output retry produces a result with the same PR reference, `linkGitHubPr` is a no-op (idempotency key replay) — never duplicate-links; if a retry produces a different PR reference (e.g., runner re-created the PR), the prior link is **superseded** (status updated, new link created) with both visible in audit history.
10. **Given** the test suite, **Then** tests cover: happy-path linkage + `integration.linked` event, INTEGRATION_LINK_CONFLICT on cross-workflow double-link, LINEAR_GITHUB_REPO_MISMATCH on incompatible link, ARTIFACT_PR_LINK_MISMATCH on artifact↔link drift at approval time, NFR17 reconstruction with GitHub unreachable, idempotent re-link, supersede behavior on retry with different PR.

### Story 3.16: Linear Completion Sync — Write Merge-Ready Summary Back to Source Linear Ticket

As a Product Manager in Linear (without opening DeliveryLine UI),
I want the workflow to write a merge-ready completion summary back to the source Linear ticket when a governed run reaches `Completed`,
So that Linear-native users see the outcome in-place rather than needing to context-switch — closing the loop between Linear intake (story 1.14) and DeliveryLine governance.

**Acceptance Criteria:**

1. **Given** a new `LinearAdapter` port method `commentOnTicket(ticketRef, body)` (added to the port shared by mock + real adapters from story 1.14), **Then** both implementations support it: `LinearMockAdapter` records the comment in its in-memory fixture log; `LinearRealAdapter` posts via Linear's GraphQL `commentCreate` mutation.
2. **Given** redaction-on-egress, **Then** the comment body passes through `RedactionPolicyService` (story 1.10) before sending — a contract test asserts no secret patterns reach Linear.
3. **Given** `WorkflowOrchestrationService` extension (`syncCompletionToLinear`), **When** `WorkflowTransitionService` transitions a run to `Completed`, **Then** an automatic post-commit hook calls `syncCompletionToLinear(workflowRunId)` which: (a) loads the linked Linear ticket reference from `integration_links` of type `linear_ticket`, (b) loads the linked GitHub PR reference from `integration_links` of type `github_pr`, (c) composes a documented summary template ("DeliveryLine governed run `{runId}` completed: PR `{prUrl}` ready for merge. Spec: `{specSummary}` (v{specVersion}). Reviewers: PM `{pmReviewer}`, Dev `{devReviewer}`. Cycle time: `{durationFormatted}`."), (d) calls `LinearAdapter.commentOnTicket(ticketRef, body)`.
4. **Given** failure handling, **When** Linear comment posting fails (network, auth, rate limit), **Then** the failure is recorded as a `linear.completionSyncFailed` event with `failureCategory` per registry — but does NOT roll back the `Completed` transition; completion sync is an after-the-fact best-effort notification, never blocking governed flow.
5. **Given** retry on transient failure, **Then** `syncCompletionToLinear` may be re-invoked manually via CLI (`deliveryline sync-completion {runId}`) — idempotent via Linear's comment-deduplication or a documented client-side fingerprint check (don't post the same canonical summary twice).
6. **Given** classification per story 1.10, **Then** the summary body is classified `shareable-full` (no secrets, no local paths, only shareable identifiers) — and a test asserts this classification holds even when the source data contains local paths or secrets that should have been redacted upstream.
7. **Given** opt-out configuration, **Then** `application.yml` `deliveryline.workflow.linear-completion-sync.enabled` (default `true`) lets pilots disable completion-sync without code changes; doctor (story 1.16) reports current setting.
8. **Given** message-template configuration, **Then** the summary template lives in `application.yml` `deliveryline.workflow.linear-completion-sync.template` (with documented placeholder variables: `{runId}`, `{prUrl}`, `{specSummary}`, etc.) — pilots can customize without code; an invalid template (missing required variable) raises `INVALID_COMPLETION_TEMPLATE` at startup.
9. **Given** ArchUnit + scope, **Then** this is the only path that writes to Linear — runner CLIs (Codex/Claude) never post to Linear directly; preserving the "Linear is intake + completion sync" narrow boundary. Verified by an ArchUnit rule that `LinearAdapter.commentOnTicket` may only be invoked from `WorkflowOrchestrationService.syncCompletionToLinear` and the CLI `sync-completion` command.
10. **Given** the test suite, **Then** tests cover: happy-path completion sync after `Completed` transition, redaction of summary body, failure does not roll back completion, manual retry via CLI, opt-out config disables sync, invalid template rejected at startup, idempotent re-sync (no duplicate Linear comments).
11. **Given** documentation, **Then** `docs/integrations/linear-completion-sync.md` documents the feature, the default template, customization, opt-out, and security posture (best-effort, after-the-fact, redaction enforced).

### Story 3.17: RunnerExecutionQueue + Configurable Worker Pool

As a backend developer + workflow operator running multiple governed tickets in parallel,
I want a PostgreSQL-backed `RunnerExecutionQueue` with a configurable Spring TaskExecutor worker pool that pulls queued executions via `SELECT ... FOR UPDATE SKIP LOCKED` semantics — every dispatch goes through the queue (no direct-dispatch path),
So that multiple tickets can flow through the workflow concurrently with bounded resource use, no new infrastructure beyond the existing PostgreSQL container, and the architecture's deferred queue-runner decision is satisfied without adopting Redis/RabbitMQ/Kafka.

**Execution-order note:** Although numbered 3.17, this story **must merge before stories 3.11, 3.12, 3.14, 3.15** (the orchestration flows that will call `enqueue`). Existing 3.11/3.12/3.14/3.15 ACs that reference `RunnerBroker.dispatch(...)` directly are interpreted as `enqueue(...)` after this story lands.

**Acceptance Criteria:**

1. **Given** the `runner_executions` table, **Then** Flyway V4 migration `V4__add_queue_state_columns.sql` adds: `status` CHECK widened to include `queued`, `dispatched_at timestamptz NULL`, `worker_id text NULL`, `queue_priority integer NOT NULL DEFAULT 100`, `queue_attempt_count integer NOT NULL DEFAULT 0` — plus an index on `(status, queue_priority, created_at)` for queue pickup efficiency.
2. **Given** the `application.runner.queue` package, **Then** `RunnerExecutionQueue` exposes `enqueue(workflowRunId, stage, contextBundleRef, idempotencyKey) → QueuedRunnerExecution` (writes a `runner_executions` row with `status='queued'` + appends `runner.queued` event) and `dequeue(workerId) → Optional<RunnerExecution>` (uses `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1 ORDER BY queue_priority ASC, created_at ASC`).
3. **Given** `RunnerWorkerPool` is a Spring-managed `ThreadPoolTaskExecutor` with size from `application.yml` `deliveryline.runner.worker-pool-size` (default 2, min 1, max 32) — each worker thread runs a loop: `dequeue → if work then dispatch via DockerRunnerAdapter → on completion mark runner_executions.status → repeat`; idle workers sleep on documented backoff (default 1s, exponential up to 10s).
4. **Given** PostgreSQL LISTEN/NOTIFY for low-latency wake-up, **Then** `enqueue` issues `NOTIFY runner_queue_updated` after commit; idle workers `LISTEN` so newly-enqueued work is picked up within p95 < 500ms when workers are idle (measured by integration test).
5. **Given** **queue-only execution path**, **Then** there is **no direct-dispatch API** — `RunnerBroker.dispatch(...)` from story 1.13/3.1 is refactored to `RunnerBroker.enqueue(...)`; ArchUnit rule asserts no caller invokes `DockerRunnerAdapter.dispatch` directly outside the worker loop.
6. **Given** **one shared pool**, **Then** all stages compete for the same worker pool — no per-stage pools in E3; documented in `docs/adr/0006-runner-queue-shared-pool.md`.
7. **Given** backpressure, **Then** `application.yml` `deliveryline.runner.queue-max-depth` (default 100) caps queued items; submissions beyond the cap raise `RUNNER_QUEUE_FULL` with `details.currentDepth` + `details.maxDepth`; orchestration treats this as transient (workflow remains in prior state).
8. **Given** correlation propagation per story 1.19, **Then** `enqueue` accepts and persists the originating `correlationId`; the worker carries it into MDC for the duration of the dispatch.
9. **Given** worker crash mid-dispatch, **When** a worker thread dies, **Then** the in-flight `runner_executions` row's lease is reclaimed by story 3.2's stale-detection mechanism (extended to cover queued/dispatched rows whose `dispatched_at` + `2 × stage_timeout` has passed); reclaimed rows transition to `orphaned` for E4 recovery handling.
10. **Given** graceful shutdown, **Then** the worker pool participates in Spring Boot shutdown lifecycle: SIGTERM triggers a documented drain timeout (default 60s); queued items remain queued for next backend restart.
11. **Given** ArchUnit boundary, **Then** `RunnerExecutionQueue` lives in `application.runner.queue`; only `WorkflowOrchestrationService` and the worker pool may invoke `enqueue`/`dequeue`.
12. **Given** the test suite, **Then** integration tests cover: enqueue + dequeue happy path, FOR-UPDATE-SKIP-LOCKED prevents two workers picking the same row, LISTEN/NOTIFY wake-up latency, backpressure raises `RUNNER_QUEUE_FULL`, correlationId preservation, worker-crash orphan reclamation, graceful shutdown drains in-progress, no direct-dispatch path callable from outside the worker loop.

### Story 3.18: Workflow Batch Submission (CLI + REST)

As a Product Manager submitting a batch of related low-risk tickets at once,
I want `deliveryline submit-batch --tickets LIN-1,LIN-2,LIN-3` (or `--from-file tickets.txt`) and `POST /api/v1/workflows/batch` returning a batch ID + per-ticket queue/reject result with **best-effort semantics** (one rejected ticket does not fail the whole batch),
So that bulk submissions are first-class operations with clear per-ticket outcomes rather than an opaque all-or-nothing API.

**Execution-order note:** Numbered 3.18 but logically follows story 3.17; **must merge after the queue lands** so batch submissions actually go through the worker pool.

**Acceptance Criteria:**

1. **Given** `WorkflowBatchSubmissionService.submitBatch(command: SubmitBatchCommand) → BatchSubmissionResult`, **Then** `SubmitBatchCommand` carries: `tickets: List<LinearTicketRef>` (1–100 items, configurable max), `actorIdentity`, `actorType`, `idempotencyKey` (batch-level — same key + same fingerprint replays the prior batch outcome).
2. **Given** **best-effort semantics**, **Then** the service iterates each ticket: for each, attempts `submitWorkflow` (existing story 1.15 single-submission flow refactored to invoke `RunnerBroker.enqueue` from story 3.17) and records per-ticket outcome `{ticketRef, runId?, queueResult: 'queued'|'rejected', rejectionReason?, rejectionCode?}` — a single rejection does not abort processing of remaining tickets.
3. **Given** the result, **Then** `BatchSubmissionResult` includes: `batchId` (`bat_` prefix — added to `PublicIdPrefixes` registry per story 1.4 drift test), `submittedAt`, `actorIdentity`, `total: int`, `queuedCount: int`, `rejectedCount: int`, `tickets: List<TicketBatchResult>`.
4. **Given** persistence, **Then** Flyway V5 migration `V5__add_batch_submissions.sql` adds a `batch_submissions` table with `id` (`bat_` prefix), actor, idempotency key, counts, created_at; `runner_executions` gains a `batch_submission_id text NULL` FK so each queued execution traces back to its batch.
5. **Given** CLI `deliveryline submit-batch --tickets LIN-1,LIN-2,LIN-3 [--from-file tickets.txt] [--idempotency-key K]`, **Then** parses tickets from flag or file (one per line, `#` for comments, blanks ignored), invokes `submitBatch`, prints tabular result `Ticket | Run ID | Outcome | Reason`; non-zero exit code if any ticket rejected (configurable `--exit-on-any-rejection`, default exits 0 if at least one queued).
6. **Given** REST `POST /api/v1/workflows/batch`, **Then** accepts `BatchSubmissionRequest` (camelCase JSON), returns 200 with `BatchSubmissionResult` JSON; OpenAPI documented.
7. **Given** queue capacity awareness from story 3.17 AC7, **When** queue at capacity during batch processing, **Then** stops enqueueing remaining tickets, marks unprocessed as `rejected` with `RUNNER_QUEUE_FULL`, returns partial result.
8. **Given** correlation propagation, **Then** generates one `correlationId` for the batch + per-ticket child correlation IDs (`{batchCorrelationId}/{ticketRef}`).
9. **Given** idempotency at batch level, **Then** same key + same fingerprint (canonical hash of sorted ticket list + actor) returns prior result without re-processing; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
10. **Given** ArchUnit + scope, **Then** `WorkflowBatchSubmissionService` lives in `application.workflow`; composes single-submission logic, never duplicates it.
11. **Given** the test suite, **Then** covers: happy-path batch all queued, mixed batch (some queued, some rejected), best-effort semantics, idempotent replay, queue-full mid-batch produces partial result, correlation IDs propagate, CLI tabular output, REST + OpenAPI conformance.

### Story 3.19: Queue Inspection + Worker Status + Queue-Size Monitoring

As a workflow operator + Product Manager monitoring the queue and worker pool health,
I want `deliveryline workers status` (CLI) + `GET /api/v1/runner-queue/status` (REST) showing worker pool state, current load, queue depth, oldest queued age, and per-worker current work — plus Prometheus metrics + Grafana dashboard panels + starter alert rules so growing queue size is visible and alerts fire before users complain,
So that pilots can spot stuck workers, growing queue depth, or pool-too-small symptoms early.

**Acceptance Criteria:**

1. **Given** `WorkflowInspectionService.getRunnerQueueStatus() → RunnerQueueStatus`, **Then** the typed view returns: `poolSize`, `activeWorkers`, `idleWorkers`, `queueDepth`, `oldestQueuedAt`, `oldestQueuedAgeSeconds`, `inFlightExecutions`, `recentThroughputPerMinute`, `workers: List<WorkerStatus>` — per-worker `WorkerStatus { workerId, state, currentRunnerExecutionId?, currentWorkflowRunId?, dispatchedAt?, currentStage? }`.
2. **Given** CLI `deliveryline workers status [--format=text|json] [--watch]`, **Then** prints the typed view; `--watch` refreshes every 5s (configurable); JSON output stable-schema.
3. **Given** queue-stale detection from story 3.2 AC3 (extended for queued items in story 3.17 AC9), **Then** the inspection view exposes `staleQueuedCount` + `staleDispatchedCount`; both render with yellow/red color coding in CLI text output when non-zero.
4. **Given** REST `GET /api/v1/runner-queue/status`, **Then** returns 200 with `RunnerQueueStatus` JSON; idempotent read; OpenAPI documented; rate-limit-friendly for Grafana scrape every 15s.
5. **Given** **queue-size monitoring via Prometheus** per AR25, **Then** Spring Boot Actuator's Prometheus endpoint exposes: `deliveryline_runner_pool_size`, `deliveryline_runner_active_workers`, `deliveryline_runner_idle_workers`, **`deliveryline_runner_queue_depth`** (headline metric), **`deliveryline_runner_queue_oldest_age_seconds`**, `deliveryline_runner_dispatched_count_total`, `deliveryline_runner_completed_count_total{stage,outcome}`, `deliveryline_runner_dispatch_duration_seconds{stage}` (histogram).
6. **Given** Grafana dashboards from story 3.7 AC6, **Then** extends them with a "Runner Queue" panel: **queue depth over time** (headline graph), oldest queued age, worker pool utilization, per-stage throughput, dispatch duration p50/p95/p99 — committed under `infra/observability/grafana/dashboards/`.
7. **Given** **queue-size alert rules**, **Then** `infra/observability/prometheus/alerts.yml` defines: **`RunnerQueueDepthHigh`** (depth > warn threshold for 5 min — default 50, override `deliveryline.runner.alerts.queue-depth-warn`), **`RunnerQueueDepthCritical`** (depth > critical for 2 min — default 200), **`RunnerOldestQueuedStale`** (oldest queued age > 2× stage timeout), **`RunnerPoolStarved`** (active workers = pool size for 10 min).
8. **Given** alertmanager-routing documentation, **Then** `infra/observability/prometheus/README-alerting.md` shows how to wire alerts to Slack / email / PagerDuty by adding alertmanager service to the unified compose `observability` profile + supplying webhook URLs via `.env` — alertmanager NOT added by default; pilots opt in.
9. **Given** per-batch visibility (from story 3.18 AC4), **Then** `getRunnerQueueStatus` accepts an optional `batchId` query param to filter to that batch's executions.
10. **Given** ArchUnit boundary, **Then** all queue-status reading goes through `WorkflowInspectionService.getRunnerQueueStatus(...)`; controllers, CLI, Prometheus exporters consume the typed view.
11. **Given** the foundation gate (story 1.23) widening, **Then** extends gate to include "queue-status inspection returns expected fields" + "Prometheus `deliveryline_runner_queue_depth` metric scrapeable".
12. **Given** the test suite, **Then** covers: status view fields under various states, CLI color coding for stale states, --watch refresh, **Prometheus ****`deliveryline_runner_queue_depth`**** exposure** (metric present + value matches DB count), Grafana dashboard JSON validity, batch-filter view, alert-rule YAML validity (`promtool check rules`).

### Story 3.20: Technical Approval Service — `acceptImplementation` with Version Binding

As a Developer reviewing implementation output (plan or PR/output),
I want `TechnicalApprovalService.acceptImplementation(...)` that binds technical approval to a specific artifact version + context bundle version + actor identity + reviewer role (`developer`),
So that FR16 (developer accepts merge-ready) is wired with the same version-binding rigor as story 2.9 — and FR21 (separate product/technical acceptance states) is preserved at the data layer.

**Acceptance Criteria:**

1. **Given** `application.approval` package extension, **Then** `TechnicalApprovalService` exposes `acceptImplementation(command: AcceptImplementationCommand) → ApprovalResult`.
2. **Given** `AcceptImplementationCommand`, **Then** carries: `workflowRunId`, `artifactId` (must be `implementationPlan` or `prOutput`, never `spec`), `expectedArtifactVersion`, `expectedContextBundleVersion`, `actorIdentity`, `actorType=human`, `reviewerRole=developer`, optional `reason`, `idempotencyKey`.
3. **Given** the `approvals` table, **Then** existing schema accommodates technical approvals — `reviewer_role` already supports `developer`; row stores `apr_` prefix ID, workflow run, artifact + version, context bundle version, actor, role, decision=approved, reason, decided_at, idempotency_key.
4. **Given** **FR21 separate product/technical acceptance states**, **Then** `WorkflowInspectionService.getRunSummary` returns `productApprovalState` + `technicalApprovalState` as distinct typed fields — never collapsed; CLI `status` and UI Run Context Strip surface both independently.
5. **Given** version-binding, **When** versions don't match, **Then** `APPROVAL_VERSION_MISMATCH` raised with same `details` shape as story 2.9 AC4 — UI Decision Bar `implementation_review` mode (story 3.28) handles identically.
6. **Given** approval-eligibility gating per story 1.12 AC6 + GitHub link gate per story 3.15 AC5, **When** artifact not `available` OR PR ref doesn't match linked PR, **Then** `ARTIFACT_PAYLOAD_UNAVAILABLE` or `ARTIFACT_PR_LINK_MISMATCH` raised.
7. **Given** successful approval of `prOutput`, **Then** in one transaction: `approvals` row inserted with `reviewer_role=developer`, `approval.approved` event with `details.reviewerRole=developer`, `WorkflowTransitionService.transition` to `Completed` — fulfilling merge-ready handoff path.
8. **Given** successful approval of `implementationPlan`, **Then** transition target is `Executing` (not `Completed`) and `WorkflowOrchestrationService.dispatchImplementation` (story 3.12) is invoked to enqueue PR/output runner via story 3.17.
9. **Given** FR46 attribution, **Then** approval record + event preserve `reviewer_role=developer` so inspection displays "approved by Nina (developer)" — distinct from story 2.9's `product_reviewer`.
10. **Given** idempotency (story 1.9), **Then** retries with same key + fingerprint replay; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
11. **Given** invalid current state (e.g., not `WaitingForReview`), **Then** `ILLEGAL_TRANSITION` propagates from story 1.5.
12. **Given** allowed-actions integration (story 2.14), **Then** when state=`WaitingForReview` + role=`developer`, `accept_implementation` appears in allowed-actions list; new registry value added per drift test.
13. **Given** contract tests, **Then** cover: happy-path approval of `implementationPlan` → `Executing` + event, happy-path approval of `prOutput` → `Completed` + Linear completion sync triggered (story 3.16), version-mismatch, unavailable-artifact, PR-link-mismatch, idempotent replay, idempotency-conflict, illegal-state-transition, FR21 separate states surface in inspection.

### Story 3.21: Technical Approval Service — `rejectImplementation` with Structured Technical Feedback

As a Developer rejecting implementation output with structured feedback,
I want `TechnicalApprovalService.rejectImplementation(...)` accepting tagged feedback (developer-specific taxonomy: `incorrect_approach` / `incomplete_implementation` / `quality_issue` / `breaks_existing_functionality` / `out_of_scope`),
So that FR17 is wired and rejection feedback flows back into the rebuild loop with measurable rework categorization for AR34a metrics.

**Acceptance Criteria:**

1. **Given** `RejectImplementationCommand`, **Then** carries: `workflowRunId`, `artifactId` (must be `implementationPlan` or `prOutput`), `expectedArtifactVersion`, `expectedContextBundleVersion`, `actorIdentity`, `actorType`, `reviewerRole=developer`, `reasonText` (required), `taggedFeedback` from `rejection_taxonomy_developer` registry (added in story 1.4 drift test), `idempotencyKey`.
2. **Given** the `approvals` table, **Then** rejection row uses `decision=rejected, reviewer_role=developer`, stores `reason`, stamps `rejection_taxonomy` with developer-taxonomy value (column already exists from story 2.10, just constrained to wider value set when role=developer).
3. **Given** version-binding, **When** versions don't match, **Then** `APPROVAL_VERSION_MISMATCH` raised — no rejection row written.
4. **Given** successful rejection of `implementationPlan`, **Then** in one transaction: `approvals` row inserted, `approval.rejected` event with `details.reviewerRole=developer` and `details.taggedFeedback`, `workflow_runs.implementation_rejection_loop_count` incremented (column added by Flyway V6 in this story), `WorkflowTransitionService.transition` to `Investigating` to re-enter plan generation.
5. **Given** successful rejection of `prOutput`, **Then** transition target is `Executing` (re-dispatch PR/output runner with feedback) — distinct from plan rejection. Runner re-execution carries rejected artifact reference + reason + taggedFeedback in new context bundle (story 3.10).
6. **Given** configurable escalation threshold (default `3`, configurable via `application.yml` `deliveryline.workflow.implementation-rejection-escalation-threshold`), **When** `implementation_rejection_loop_count` reaches threshold, **Then** `escalation.required` event appended with `details.reason='implementation_rejection_loop_threshold_exceeded'` and `escalation_marker_set=true` — fulfilling FR13 at implementation stage.
7. **Given** AR34a measurement, **Then** `rejection_taxonomy` populated on every rejection — contract test asserts no rejection without non-null `taggedFeedback` (`MISSING_REJECTION_TAXONOMY`).
8. **Given** Flyway V6 migration `V6__add_implementation_loop_columns.sql`, **Then** adds `implementation_rejection_loop_count integer NOT NULL DEFAULT 0` to `workflow_runs` (mirroring story 2.11's spec-loop-count); replay safety asserted.
9. **Given** allowed-actions integration, **Then** when state=`WaitingForReview` + role=`developer`, `reject_implementation` appears in list; new registry value added per drift test.
10. **Given** the test suite, **Then** covers: happy-path rejection of `implementationPlan` → `Investigating` + event, happy-path rejection of `prOutput` → `Executing` for re-dispatch with feedback, taxonomy-missing rejection, version-mismatch, threshold-not-exceeded (counter increments, no escalation), threshold-exceeded (escalation event), V6 migration replay safety, registry drift tests pick up new values.

### Story 3.22: Developer Takeover Service — `takeoverWorkflow` with Preserved Context

As a Developer who needs to continue implementation work outside the orchestrator (per UX spec § Developer Flow),
I want `DeveloperTakeoverService.takeoverWorkflow(...)` that transitions to `TakenOver` state, stops orchestrator runner dispatch for that run, preserves all prior artifacts + run history + GitHub PR linkage, and records the takeover with developer attribution — fulfilling FR18, FR19, FR33,
So that the developer can continue work in the linked GitHub branch using normal IDE/tooling without losing the governed run's context, history, or merge-ready handoff trail.

**Acceptance Criteria:**

1. **Given** `application.recovery` package extension (story 1.18 stubbed `RecoveryService` baseline), **Then** `DeveloperTakeoverService` exposes `takeoverWorkflow(command: TakeoverWorkflowCommand) → TakeoverResult`.
2. **Given** `TakeoverWorkflowCommand`, **Then** carries: `workflowRunId`, `actorIdentity`, `actorType=human`, `reviewerRole=developer`, `reasonText` (required), `idempotencyKey`.
3. **Given** the `recovery_actions` table from story 1.3, **Then** takeover row inserted: `id` (`rcv_` prefix), `workflow_run_id`, `action_type='takeover'` (extending registry per story 1.18 AC8), `triggering_event_id`, `actor_identity`, `actor_type`, `reviewer_role`, `created_at`, `idempotency_key`, `result_status`.
4. **Given** the workflow state machine from story 1.5, **Then** `takeoverWorkflow` invokes `WorkflowTransitionService.transition` to `TakenOver` — already valid per story 1.5 AC1 + `* → TakenOver` allowed from any non-terminal state per AC2.
5. **Given** **stop orchestrator runner dispatch** per FR18, **When** `TakenOver` reached, **Then** any in-flight `runner_executions` rows: (a) gracefully cancelled (story 3.2 `docker stop` for live containers), (b) rows transition to `cancelled_for_takeover` status (added to `RunnerExecutionStatus` registry), (c) any queued executions (story 3.17 status='queued') removed from queue + marked `cancelled_for_takeover` — preventing post-takeover dispatch.
6. **Given** **preserved context** per FR19 + FR33, **Then** all prior artifacts (spec, plan, PR/output) remain persisted; `integration_links` GitHub PR row remains so developer can continue work on the same PR; full audit trail preserved.
7. **Given** **continuation outside the orchestrator**, **Then** developer continues editing in linked GitHub branch (`deliveryline/{ticketRef}/stage-{runId}` per story 3.9 AC2) using normal git tooling — DeliveryLine no longer dispatches runners but PR linkage remains visible in `deliveryline status`.
8. **Given** post-takeover visibility, **Then** `getRunSummary` returns `state='TakenOver'`, `takenOverBy={actorIdentity, actorType, reviewerRole}`, `takenOverAt={timestamp}`, `takenOverReason={reasonText}` — surfaced in CLI `status`, UI Run Context Strip (story 2.16), Decision Bar transitions to read-only "Taken over by Nina (developer)" state.
9. **Given** allowed-actions integration, **Then** when state=`WaitingForReview` + role=`developer`, `takeover_workflow` in allowed-actions list. After `TakenOver` reached, only allowed action is `view_only`.
10. **Given** Linear completion sync from story 3.16, **When** developer completes takeover work (manually marks PR ready in GitHub), **Then** DeliveryLine does NOT auto-detect completion in E3 — workflow remains in `TakenOver` until explicit operator action (Epic 4) closes it.
11. **Given** idempotency, **Then** retries with same key + fingerprint replay; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
12. **Given** the test suite, **Then** covers: happy-path takeover transitions to `TakenOver` and cancels in-flight + queued executions, prior artifacts + GitHub PR linkage preserved, audit trail preserved, takeover from each non-terminal state succeeds, takeover from `Completed` raises `ILLEGAL_TRANSITION`, post-takeover allowed actions reduced to `view_only`, idempotent replay, FR19 reconstruction (reviewer can see who took over, when, why, what state preserved).

### Story 3.23: REST Endpoint — `accept-implementation` + OpenAPI

As a frontend developer (Decision Bar `implementation_review` mode in story 3.28) and CLI user,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/accept-implementation` wired to `TechnicalApprovalService.acceptImplementation` (story 3.20),
So that UI mutation hooks (story 2.6 AC6) and CLI `deliveryline accept-implementation` command have a stable contract and CLI/REST equivalence (story 1.7 AC5) is maintained.

**Acceptance Criteria:**

1. **Given** `WorkflowController` (extended from story 2.13), **Then** new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/accept-implementation` — kebab-case action.
2. **Given** request body, **Then** typed DTO `AcceptImplementationRequest { artifactId, expectedArtifactVersion, expectedContextBundleVersion, reviewerRole, reason? }` in camelCase JSON.
3. **Given** mandatory `Idempotency-Key` header (story 1.9), **Then** missing returns 400 with `MISSING_IDEMPOTENCY_KEY`; invalid returns 400 with `INVALID_IDEMPOTENCY_KEY`.
4. **Given** `X-Actor-Identity` header (story 2.13 AC4 deferred-auth model), **Then** identifies actor; missing falls back to configured local-user; `reviewerRole` from body must be `developer` (mismatch returns `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`).
5. **Given** Problem Details mapping (story 1.8), **Then** typed errors cover: `APPROVAL_VERSION_MISMATCH` (409), `IDEMPOTENCY_KEY_CONFLICT` (409), `ARTIFACT_PAYLOAD_UNAVAILABLE` (409), `ARTIFACT_PR_LINK_MISMATCH` (409), `ILLEGAL_TRANSITION` (409), `ARTIFACT_TYPE_MISMATCH` (400 — when artifactId points to `spec` not `implementationPlan`/`prOutput`), `RUN_NOT_FOUND` (404), `INVALID_COMMAND_PAYLOAD` (400) — contract tests check `code` + `status` + `details`, never human text.
6. **Given** OpenAPI via `springdoc-openapi`, **Then** endpoint appears in regenerated OpenAPI snapshot; CI drift check (story 1.21 AC6) passes.
7. **Given** CLI/REST equivalence (story 1.7 AC5), **Then** Spring Shell command `deliveryline accept-implementation --run {runId} --artifact {artifactId} --artifact-version N --context-version M --reviewer-role developer [--reason "..."] [--idempotency-key K]` added under `adapters.cli`; contract test asserts CLI/REST identical outcomes.
8. **Given** ArchUnit (story 1.11), **Then** controller method only does request parsing, command construction, service invocation, response mapping — no business logic.
9. **Given** the response, **Then** success returns 200 OK with typed result DTO carrying new state (`Executing` for plan / `Completed` for prOutput) and stamped `correlationId`; `X-Correlation-Id` response header echoes request correlation ID.
10. **Given** contract test suite, **Then** covers: happy path 200 for plan-stage approval, happy path 200 for prOutput-stage approval, every documented error code, idempotent replay, idempotency-conflict, version mismatch, request schema validation failures, role-mismatch, artifact-type-mismatch.

### Story 3.24: REST Endpoint — `reject-implementation` + OpenAPI

As a frontend developer and CLI user submitting a developer rejection,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/reject-implementation` wired to `TechnicalApprovalService.rejectImplementation` (story 3.21),
So that developer rejections flow through the same idempotency + Problem Details + OpenAPI conventions as `accept-implementation` (story 3.23) and `reject-spec` (story 2.13).

**Acceptance Criteria:**

1. **Given** `WorkflowController`, **Then** new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/reject-implementation` — kebab-case action.
2. **Given** request body, **Then** typed DTO `RejectImplementationRequest { artifactId, expectedArtifactVersion, expectedContextBundleVersion, reviewerRole, reasonText, taggedFeedback }`; `taggedFeedback` must be one of developer-rejection taxonomy values from story 3.21 AC1.
3. **Given** mandatory `Idempotency-Key` header + `X-Actor-Identity` header, **Then** same conventions as story 3.23 apply.
4. **Given** Problem Details errors, **Then** coverage matches story 3.23 AC5 plus `MISSING_REJECTION_TAXONOMY` (400) and `INVALID_REJECTION_TAXONOMY` (400 — when `taggedFeedback` not in developer-rejection taxonomy).
5. **Given** OpenAPI + drift check, **Then** endpoint + new error codes appear in regenerated snapshot.
6. **Given** CLI/REST equivalence, **Then** Spring Shell command `deliveryline reject-implementation --run {runId} --artifact {artifactId} --artifact-version N --context-version M --reviewer-role developer --reason "..." --tagged-feedback {value} [--idempotency-key K]` added; contract test asserts identical outcomes.
7. **Given** ArchUnit (story 1.11), **Then** controller method is thin — no business logic.
8. **Given** the response, **Then** success returns 200 OK with typed result DTO carrying new state (`Investigating` for plan-rejection / `Executing` for prOutput-rejection — re-dispatch with feedback in next context bundle), recorded rejection's `apr_` ID, escalation marker if threshold reached.
9. **Given** allowed-actions integration, **Then** endpoint validates `reject_implementation` is in allowed-actions list — returns `ACTION_NOT_ALLOWED` (409) if not allowed (e.g., from `Completed` state).
10. **Given** contract test suite, **Then** covers: happy-path rejection of `implementationPlan` → 200 + state at `Investigating`, happy-path rejection of `prOutput` → 200 + state at `Executing`, missing taxonomy rejected, invalid taxonomy rejected, role-mismatch rejected, action-not-allowed when state forbids, version mismatch, idempotent replay, escalation-marker set when threshold reached.

### Story 3.25: REST Endpoint — `takeover-workflow` + OpenAPI

As a frontend developer (Developer Takeover UI flow in story 3.29) and CLI user initiating a takeover,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/takeover` wired to `DeveloperTakeoverService.takeoverWorkflow` (story 3.22),
So that takeover initiation flows through the same idempotency + Problem Details + OpenAPI conventions as the other mutation endpoints, with explicit confirmation semantics on the UI side (story 2.23 confirmation pattern) since takeover stops orchestrator dispatch.

**Acceptance Criteria:**

1. **Given** `WorkflowController`, **Then** new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/takeover` — kebab-case action.
2. **Given** request body, **Then** typed DTO `TakeoverWorkflowRequest { reasonText, reviewerRole }`; `reasonText` required (free-form, why takeover needed); `reviewerRole` must be `developer`.
3. **Given** mandatory `Idempotency-Key` header + `X-Actor-Identity` header (same as 3.23/3.24).
4. **Given** Problem Details errors, **Then** typed errors cover: `IDEMPOTENCY_KEY_CONFLICT` (409), `ILLEGAL_TRANSITION` (409 — e.g., taking over `Completed` run), `RUN_NOT_FOUND` (404), `MISSING_REASON_TEXT` (400), `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400), `ACTION_NOT_ALLOWED` (409 — when `takeover_workflow` not in allowed-actions for current state + role).
5. **Given** OpenAPI + drift check, **Then** endpoint appears in regenerated snapshot.
6. **Given** CLI/REST equivalence, **Then** Spring Shell command `deliveryline takeover --run {runId} --reason "..." [--idempotency-key K]` added; contract test asserts identical outcomes.
7. **Given** ArchUnit (story 1.11), **Then** controller method is thin — no business logic.
8. **Given** the response, **Then** success returns 200 OK with typed result DTO carrying: new state (`TakenOver`), recorded `recovery_actions.id` (`rcv_` prefix), counts of `cancelled_for_takeover` runner executions (queued + in-flight per story 3.22 AC5), `integration_links` GitHub PR reference (preserved per story 3.22 AC6) so UI can immediately offer "Continue work in PR {ref}" navigation.
9. **Given** the high consequence of takeover (stops orchestrator, preserved-but-no-further-automation state), **Then** endpoint documentation explicitly states "this action is non-reversible in E3 — Epic 4 will add takeover-revert; until then, a takenover run can only be closed by an operator action" — UI confirmation dialog (story 3.28) reads this consequence text from OpenAPI.
10. **Given** contract test suite, **Then** covers: happy-path takeover from `WaitingForReview` returns 200 + state at `TakenOver` + cancelled-runner counts, takeover from each non-terminal state succeeds with appropriate cancelled-runner counts, takeover from `Completed` returns 409 with `ILLEGAL_TRANSITION`, missing reason text rejected, role-mismatch rejected, action-not-allowed when state forbids, idempotent replay, GitHub PR reference preserved in response.

### Story 3.26: Artifact Review Panel — Implementation-Plan Variant Renderer

As a Developer reviewing a generated implementation plan,
I want the `ImplementationPlanArtifactRenderer` (stub from story 2.17 AC3) fully implemented as a variant of the generalized Artifact Review Panel,
So that the plan's structured steps + context references + linked spec are rendered with the same primacy + sanitization + accessibility commitments as the spec variant — activating the party-mode-finding-#3 generalization paid for in E2.

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/renderers/ImplementationPlanArtifactRenderer.tsx`, **Then** the stub from story 2.17 AC3 is replaced with a real renderer for the `implementationPlan` artifact variant per runner-contracts schema v1 sub-schema (story 1.6 AC4 — `structured steps array + context refs`).
2. **Given** the artifact payload, **Then** the renderer displays: artifact title (e.g., "Implementation Plan — LIN-123 v1"), artifact type badge (distinct visual treatment from spec via tokens from story 2.3), revision indicator with link to plan revision history, structured-steps section (numbered ordered list, each step expandable to show details + estimated complexity if provided), context-references section (links to approved spec artifact + linked GitHub repo + branch reference from story 3.9 AC2 — clickable to navigate within DeliveryLine UI or open in GitHub).
3. **Given** sanitization per story 2.24, **Then** all step text + descriptions go through `SafeMarkdownRenderer` — XSS fixtures from story 2.24 AC7 exercised against this renderer in tests.
4. **Given** discriminated-union dispatch from story 2.17 AC1, **Then** when `useArtifact(artifactId)` returns an artifact with `artifactType=implementationPlan`, the ARP automatically dispatches to this renderer.
5. **Given** states from story 2.17 AC4 (loading / empty-not-yet-generated / stale / superseded / incomplete / error), **Then** all states render correctly for the implementation-plan variant.
6. **Given** allowed-actions integration from story 2.17 AC9, **Then** the renderer reads `useAllowedActions(workflowRunId)` and enables/disables variant-specific controls.
7. **Given** primacy per story 2.17 AC10 + story 2.7 AC10, **Then** the renderer respects the central-pane visual anchor.
8. **Given** keyboard accessibility per story 2.17 AC8, **Then** numbered steps are focusable + navigable with Tab + arrow keys; expanding a step is keyboard-activatable.
9. **Given** the foundation fixture event stream from story 1.23 AC4 + AC7, **Then** at least one `implementationPlan` fixture exists with realistic step content — exercised in component tests.
10. **Given** component test coverage, **Then** tests cover: structured steps render correctly, expanding/collapsing a step works via mouse + keyboard, context-references render with correct anchors, sanitization rejects scriptable payloads in step text, each artifact-state variant renders, allowed-actions integration enables/disables controls, axe-core a11y zero violations.

### Story 3.27: Artifact Review Panel — PR/Output Variant Renderer

As a Developer reviewing the agent's PR/output (the actual code change with branch + commit + PR refs),
I want the `PrOutputArtifactRenderer` (stub from story 2.17 AC3) fully implemented with diff display + branch/commit/PR reference panels,
So that the PR/output variant gives developers everything they need to decide accept / reject / takeover without leaving the DeliveryLine UI for routine cases.

**Acceptance Criteria:**

1. **Given** `src/features/workflows/components/renderers/PrOutputArtifactRenderer.tsx`, **Then** stub from story 2.17 AC3 is replaced with a real renderer for the `prOutput` artifact variant per runner-contracts schema v1 sub-schema (story 1.6 AC4 — `branch, commitSha, prReference, diffReference`).
2. **Given** the artifact payload + linked GitHub PR (from story 3.15), **Then** renderer displays: artifact title, artifact type badge, revision indicator, **branch reference** (per story 3.9 AC2 — clickable opens GitHub branch view), **commit SHA** (short form with copy button + clickable to commit view), **PR reference** (`org/repo#42` with status badge `draft`/`open`/`merged` from `integration_links.external_metadata.prState` per story 3.15 AC1 — clickable opens GitHub PR view), **diff display** (rendered via story 2.24's diff sanitization pipeline — file-by-file accordion, additions/deletions in semantic `<ins>`/`<del>` with stable token classes, syntax highlighting, no execution of embedded scripts).
3. **Given** runner output as untrusted per story 2.24, **Then** diff content + file paths + commit messages all pass through sanitization — XSS fixtures from story 2.24 AC7 exercised; metadata-spoofing protection (story 2.24 AC6) clearly separates trusted system metadata (PR ref from `integration_links` — backend truth) from untrusted runner-emitted content (diff body).
4. **Given** discriminated-union dispatch from story 2.17 AC1, **Then** when `useArtifact(artifactId)` returns artifact with `artifactType=prOutput`, ARP dispatches to this renderer.
5. **Given** large-diff handling, **Then** files are paginated/virtualized when diff exceeds a documented threshold (e.g., 50 files or 5000 lines); each file collapsed by default with summary stats; user can expand individual files.
6. **Given** GitHub link reconciliation per NFR17 (story 3.15 AC3), **When** GitHub unreachable but `integration_links` row has `external_metadata.prState`, **Then** renderer still displays cached state with "(last synced X ago)" affordance.
7. **Given** allowed-actions integration, **Then** the renderer reads `useAllowedActions(workflowRunId)` and enables/disables variant-specific controls.
8. **Given** keyboard accessibility, **Then** the file accordion is fully keyboard-operable (Tab to file header, Enter/Space to expand, Tab into diff body, arrow keys within hunks); jump-to-changed-region keyboard shortcut documented.
9. **Given** the foundation fixture event stream, **Then** at least one `prOutput` fixture with a realistic small diff (3-5 files, ~100 lines change) exists; tests render against it.
10. **Given** component test coverage, **Then** tests cover: branch + commit + PR refs render with correct GitHub URLs, status badge reflects `prState`, diff display renders correctly, sanitization rejects scriptable payloads in diff content + commit messages, file pagination works at threshold, GitHub-unreachable cached-state rendering, large-diff performance, keyboard navigation through file accordion + diff hunks, axe-core a11y zero violations.

### Story 3.28: Approval / Decision Bar — `implementation_review` Mode Activation

As a Developer making a technical accept/reject/takeover decision,
I want the Decision Bar's `implementation_review` mode (stub from story 2.19 AC1) fully activated — wired to `accept-implementation` (story 3.23), `reject-implementation` (story 3.24), `takeover` (story 3.25) endpoints with version-stamped mutations and confirmation patterns appropriate to each action's consequence,
So that the developer surface mirrors the PM surface from E2 with consistent UX patterns + the takeover confirmation reads its consequence text from OpenAPI per story 3.25 AC9.

**Acceptance Criteria:**

1. **Given** `ApprovalDecisionBar` from story 2.19 with `mode='implementation_review'` prop, **Then** stub replaced with real implementation rendering: primary action `Accept implementation` (gated on backend allowed-actions per story 2.14), secondary actions `Reject with feedback` + `Take over`, all driven by backend-reported allowed actions (no frontend permission inference per story 2.19 AC5 + UX-DR12 hard rule).
2. **Given** **one visually primary action per decision area** per UX-DR19, **Then** when state=`WaitingForReview` + role=`developer` + clarifications resolved + PR linkage matches: `Accept implementation` is visually primary; reject + takeover visually subordinate (matches story 2.19 AC7, enforced by ESLint rule from story 2.23 AC7).
3. **Given** `Reject with feedback` action, **When** invoked, **Then** opens `RationaleCaptureDialog` (story 2.23 AC1) with: required `reasonText` textarea, required `taggedFeedback` radio selection from developer-rejection taxonomy (story 3.21 AC1), Cancel + "Submit rejection" buttons; submit calls `useRejectImplementation` mutation hook sending Idempotency-Key + version stamps + reviewer-role per story 3.24 AC2 + AC3.
4. **Given** `Take over` action, **When** invoked, **Then** opens `ConfirmationDialog` (story 2.23 AC1) with: **consequence text read from OpenAPI documentation** per story 3.25 AC9 (e.g., "Taking over stops orchestrator dispatch for this run. The workflow becomes read-only and can only be closed by an operator action. This is non-reversible in the current release."), required `reasonText` textarea, Cancel + "Confirm takeover" buttons styled with `intent="danger"`; submit calls `useTakeoverWorkflow` mutation hook.
5. **Given** version-stamped mutations per story 2.19 AC6, **Then** every mutation sends `expectedArtifactVersion` + `expectedContextBundleVersion` + `expectedAllowedActionsVersionStamp` from story 2.14 AC1; on `APPROVAL_VERSION_MISMATCH`, bar renders stale-decision state from story 2.19 AC6 with refresh-and-retry CTA explaining what changed.
6. **Given** post-submit decision summary per story 2.19 AC9, **Then** after decision lands, bar persists outcome visibly (timestamp + actor + decision + linked event ID + transition outcome — e.g., "Accepted by Nina (developer) at 14:32 — workflow now Completed; Linear summary posted") until parent component resets.
7. **Given** takeover-result response from story 3.25 AC8 includes preserved GitHub PR reference, **Then** post-takeover state of bar shows "Continue work in PR {ref}" navigation control (link to GitHub) + "Run is taken over" read-only label.
8. **Given** ARIA + accessibility per story 2.25, **Then** all actions keyboard reachable, button labels use explicit verbs ("Accept implementation", "Reject with feedback", "Take over" — never "OK"), disabled rationale `aria-describedby` linked to reason text, focus moves into and out of confirmation dialogs predictably (story 2.23 AC5).
9. **Given** allowed-actions integration with backend-reported `disabledActions: { [action]: reasonCode }` per story 2.19 AC5, **Then** when backend reports `accept_implementation` is disabled (e.g., because artifact's PR linkage drifted), bar renders disabled state with backend-reported reason mapped to localized text — never silently disabled.
10. **Given** component test coverage, **Then** tests cover: `implementation_review` mode renders all three actions when allowed, version-stamped mutations send all expected versions, stale-decision UI on version mismatch, rejection dialog enforces reasonText + taggedFeedback, takeover dialog enforces reasonText + consequence text from OpenAPI, post-submit summary persists, post-takeover "Continue in PR" affordance renders, keyboard navigation through all actions + dialogs, single-primary-action rule enforced, axe-core a11y scan zero violations.

### Story 3.29: Developer Takeover UI Flow — Initiation, Confirmation, Persistent Visibility

As a Developer initiating a takeover from the Decision Bar (story 3.28) AND a reviewer/PM later viewing a taken-over run,
I want the takeover initiation flow + the persistent "this run was taken over by X" visibility across the queue, run context strip, and run timeline,
So that takeover is a first-class UX state — not just a backend transition — and any reviewer opening the run later sees clearly who took over, when, why, and where the work continued (FR19 reconstruction surface).

**Acceptance Criteria:**

1. **Given** the takeover initiation flow from story 3.28 AC4, **Then** the confirmation dialog is the only entry point to takeover; the action cannot be triggered accidentally (no keyboard shortcut without confirmation, no double-click escalation).
2. **Given** the takeover mutation succeeds and returns the typed result from story 3.25 AC8, **Then** the UI immediately: (a) updates `useWorkflowDetail(workflowRunId)` query (TanStack Query invalidation per story 2.6 AC6), (b) Run Context Strip (story 2.16) re-renders showing `state='TakenOver'` + `takenOverBy` + `takenOverAt` + `takenOverReason` from story 3.22 AC8, (c) Decision Bar transitions to read-only takenover state per story 3.28 AC7 with "Continue work in PR {ref}" affordance.
3. **Given** the queue (story 2.15 RunReviewQueueItem), **Then** taken-over runs render with a distinct `taken_over` state badge (using semantic state tokens from story 2.3 — `state-draft` color treatment, since the run is no longer orchestrator-actionable but isn't a failure either; non-color signifier per story 2.3 AC5 — icon + "Taken over" label) + the takenOverBy actor + takenOverAt timestamp visible in queue-row metadata.
4. **Given** the queue filters (extending story 2.15), **Then** a "Show taken-over runs" filter toggle exists (default: hidden — taken-over runs don't clutter the active queue); when enabled, taken-over runs appear in the list grouped or visually distinct so reviewers can navigate to them.
5. **Given** the run timeline (workflow events visible in the UI per stories 2.16 + future detail expansion), **Then** the `recovery.takeover` event renders with a prominent visual treatment — actor name + timestamp + reason text + a permalink anchor — preserving story 3.22 AC12 FR19 reconstruction expectation in the UI.
6. **Given** post-takeover navigation from story 3.28 AC7, **When** the user clicks "Continue work in PR {ref}", **Then** they navigate to the linked GitHub PR (opens in new tab); the DeliveryLine UI does not deep-link into GitHub for embedded editing — the integration ends at the PR-ref link in E3.
7. **Given** opening a taken-over run later (e.g., a PM revisits the run a week later to check status), **Then** the entire UI consistently reflects the takeover state: queue badge, run context strip, decision bar, run timeline — no surface incorrectly shows the run as in-flight or actionable.
8. **Given** correlation propagation per story 1.19, **Then** the takeover mutation carries the originating correlationId so backend logs trace back to the UI action that triggered the takeover.
9. **Given** ARIA live region per story 2.25 + announcement vocabulary per story 2.25 AC7, **Then** when takeover succeeds, an ARIA live region announces "Workflow run {runId} has been taken over by {actor}" — assistive-technology users hear the state transition immediately.
10. **Given** component + integration test coverage, **Then** tests cover: takeover initiation only via confirmation dialog (no accidental trigger), successful takeover updates Run Context Strip + Decision Bar + Queue Item simultaneously (TanStack Query invalidation works), queue filter for taken-over runs, run timeline renders takeover event prominently, "Continue work in PR" navigation, taken-over state visible across all surfaces consistently, ARIA live region announcement, axe-core a11y zero violations.

### Story 3.30: UI Minimum-Viable-Recovery Baseline (Failed Stage / Last Successful / Retry Action)

> **Sprint note (2026-06-12):** Pulled forward from the deferred epic-3b slice into the **epic-2b active slice** (see `sprint-change-proposal-2026-06-12.md`) to deliver the operator-requested "rerun a failed agent" button sooner. Story id unchanged. Frontend-only — its backend retry path (`POST /api/v1/workflows/{id}/retry` + `RecoveryService` retry + `deliveryline retry` CLI) already shipped in story 1.18. The deeper "rerun from an earlier step after fixing a runner config" remains Epic 4 (stories 4.7 / 4.12 / 4.22).

As a Product Manager / Developer / Workflow Owner viewing a failed run before Epic 4 ships,
I want the UI minimum-viable-recovery baseline (failed stage + last successful stage + failure timestamp + failure category + retry action) surfaced in the run timeline + Decision Bar `recovery_operator` mode (story 2.19 stub variant) — mirroring the CLI baseline from story 1.18,
So that the first agent-execution failure during pilot use does not strand the team before Epic 4's full operator console + reconciliation lands (per Epic 1 refinement R1 + thesis-marker promise that recovery baseline ships with E1 CLI + E3 UI).

**Acceptance Criteria:**

1. **Given** the run timeline component (rendered in run detail view), **When** a workflow event of type `runner.failed` / `runner.timeout` / `runner.crash` / `runner.contractViolation` / `git.pushFailed` is present, **Then** it renders with a prominent failure visual treatment using `state-error` token (story 2.3) + non-color signifier (icon + "Failed" label) + failure category from `FailureCategory` registry rendered as human-readable badge (e.g., "Runner Timeout").
2. **Given** the Run Context Strip (story 2.16) on a failed run, **Then** it surfaces: **failed stage** (state prior to `Failed`), **last successful stage**, **failure timestamp**, **failure category** (from registry), **last activity timestamp**, **next safe action** (mirroring story 1.18 AC5 CLI behavior — `retry` if state allows or `await_operator_action` if not).
3. **Given** the Decision Bar `recovery_operator` mode (stub from story 2.19 AC1), **Then** stub replaced with real implementation when run state is `Failed` AND backend allowed-actions include `retry`: primary action `Retry failed step` opens confirmation dialog (story 2.23 AC1) with consequence text "Retry will re-execute the last failed step with a fresh runner. The previous failure will be preserved in the timeline." + Cancel + "Confirm retry" buttons.
4. **Given** retry submission, **When** invoked, **Then** UI calls `useRetryWorkflow` mutation hook calling existing CLI-baseline retry path from story 1.18 (via REST endpoint added per AC10).
5. **Given** scope discipline per Epic 1 refinement R1 + story 1.18 AC11 ArchUnit scope-protected `RecoveryService`, **Then** UI does NOT expose any deeper recovery actions in E3 — no reconcile, no resume, no rerun-from-arbitrary-step, no failure-taxonomy classification controls; those wait for Epic 4. Decision Bar in `recovery_operator` mode shows ONLY `Retry failed step` (or `View only` when retry is not safe).
6. **Given** failure diagnostics surface, **Then** clicking failure event in timeline expands a detail panel showing: failure category + reason text + correlationId (so users can grep logs / story 3.7 ELK if available) + link to redacted runner logs from story 3.6 (download link calls backend endpoint — placeholder URL, full implementation in Epic 4).
7. **Given** `state-error` token treatment from story 2.3 + WCAG 2.1 AA from story 2.25, **Then** failure visuals meet contrast requirements + non-color signifier + ARIA live region announces failure-state-entry when user is on the run page.
8. **Given** the Queue Item (story 2.15) on a failed run, **Then** displays `state-error` badge + failure category in compact form + `Failed` attention indicator becomes primary indicator (per story 2.15 AC5 priority order: blocker > escalation > question > stale; failure ranks alongside blocker for visibility).
9. **Given** the foundation fixture event stream from story 1.23 includes the execution-failure-with-retry scenario, **Then** this story's UI tests render against that fixture and assert: failure event renders prominently, recovery baseline information surfaces in Run Context Strip, retry confirmation dialog opens correctly, retry mutation invalidates queries to refresh state.
10. **Given** REST `POST /api/v1/workflows/{workflowRunId}/retry` endpoint, **Then** if not already added, this story adds it: typed `RetryWorkflowRequest { reasonText? }`, mandatory Idempotency-Key, Problem Details errors covering `RETRY_NOT_APPLICABLE` (from story 1.18 AC3), `ILLEGAL_TRANSITION` (409), `RUN_NOT_FOUND` (404); CLI/REST equivalence with existing `deliveryline retry` from story 1.18; OpenAPI snapshot regenerated.
11. **Given** component + integration test coverage, **Then** tests cover: failure event renders with correct visual treatment, Run Context Strip surfaces all baseline fields when run is `Failed`, Decision Bar `recovery_operator` mode shows only `Retry failed step`, retry confirmation dialog enforces consequence text + Cancel preserves state, retry mutation succeeds invalidates queries, scope discipline holds (no deeper recovery actions visible in E3 UI), failure diagnostics expand panel shows correlationId + log link, queue item shows failed state with priority indicator, ARIA live region announces failure entry, axe-core a11y zero violations.

### Story 3.31: GitHub PR Linkage Display in Run Context Strip + Queue Item

As a Product Manager / Developer / Workflow Owner viewing any run with a linked GitHub PR,
I want the Run Context Strip + Queue Item to display the GitHub PR reference (branch + commit SHA short form + PR ref + PR state badge) alongside the existing Linear ticket reference,
So that FR40 (link governed implementation output to GitHub / PR refs) is surfaced in the UI — making the ticket↔repo↔branch↔commit↔PR↔run lineage (NFR17) visible at a glance from the queue without opening the run.

**Acceptance Criteria:**

1. **Given** `RunReviewQueueItem` from story 2.15, **Then** extended to display linked GitHub PR reference (when present in `integration_links`) as a compact secondary metadata element: `PR org/repo#42` with state badge (`draft` / `open` / `merged` / `closed` from `external_metadata.prState` per story 3.15 AC1) + clickable to open GitHub PR in new tab.
2. **Given** `RunContextStrip` from story 2.16, **Then** extended to display: `branchOrCommitReference` (was nullable per story 2.16 AC1 — populated for runs that completed at least PR-output stage), `prReference` with state badge, last-sync timestamp from `integration_links.last_sync_at` per story 3.15 AC1.
3. **Given** "lightweight" rule from story 2.16 AC4, **Then** new PR linkage display does not push strip past max-height threshold; if real estate tight, branch + commit displayed inline with abbreviation (commit SHA short form e.g., `a3f291`) + tooltip for full SHA.
4. **Given** stale GitHub state per story 3.15 AC6, **When** `last_sync_at` is older than configurable freshness threshold (e.g., 5 minutes), **Then** state badge renders with "(stale, last synced X ago)" affordance.
5. **Given** GitHub unreachable (e.g., backend's GitHubAdapter calls failing), **Then** cached `external_metadata.prState` continues to render — story 3.15 AC3 NFR17 reconstruction holds; reviewers not blocked from inspecting run state.
6. **Given** wrong-ticket-link prevention per NFR20, **Then** displayed PR reference always comes from `integration_links` (backend truth) — never from runner-emitted artifact metadata that might have drifted; contract test asserts renderer reads from `useWorkflowDetail` (which reads `integration_links`), not from `useArtifact`.
7. **Given** queue and run-context surfaces are consistent, **Then** same PR reference + state badge visual treatment is used across both — no surface-specific styling drift.
8. **Given** runs without GitHub linkage (e.g., run that hasn't reached PR-output stage yet, or a legacy run), **Then** PR linkage display gracefully renders nothing in affected slots — no empty placeholders, no "—" markers, just absent.
9. **Given** ARIA labels + accessibility per story 2.25, **Then** PR reference link's accessible name includes full canonical reference + state (e.g., `aria-label="Pull request 42 in org/repo, status open"`).
10. **Given** component test coverage, **Then** tests cover: queue item renders PR linkage when present, queue item gracefully omits when absent, run context strip renders branch/commit/PR/state with last-sync timestamp, stale-state freshness affordance renders past threshold, GitHub-unreachable cached-state rendering, max-height threshold preserved with PR linkage added (layout regression test), backend-truth-only sourcing (artifact-emitted PR refs never reach renderer), axe-core a11y zero violations.

### Story 3.32: TicketSourceAdapter Abstraction (Extract from LinearAdapter)

As an architect preparing for future ticket-source extension (JIRA, GitHub Issues, GitLab Issues, Asana),
I want the existing `LinearAdapter` port (story 1.14) renamed/refactored to a vendor-neutral `TicketSourceAdapter` interface — with `LinearMockAdapter` + `LinearRealAdapter` becoming concrete implementations alongside future `JiraAdapter`/etc. — plus a documented extension contract,
So that adding a new ticket source in a future version requires implementing one interface against one documented contract, not refactoring a Linear-shaped port.

**Acceptance Criteria:**

1. **Given** the `application.integration.ticketsource` package, **Then** the generic `TicketSourceAdapter` interface exists with vendor-neutral domain-shaped methods: `fetchTicketByReference(TicketRef ref) → Optional<Ticket>`, `pollNewTickets(Instant since) → List<Ticket>`, `commentOnTicket(TicketRef ref, String body) → CommentResult`, `getCapabilities() → TicketSourceCapabilities` — all parameters and return types use vendor-neutral domain models defined in `domain.integration.ticketsource`.
2. **Given** the existing `LinearAdapter` port from story 1.14, **Then** it is renamed/refactored such that `LinearAdapter` is no longer a separate port; the existing `LinearMockAdapter` + `LinearRealAdapter` (stories 1.14, 3.16) now implement `TicketSourceAdapter` directly. A migration note in `docs/adr/0007-ticket-source-abstraction.md` records the change.
3. **Given** capability detection (since not all ticket sources support all operations — e.g., some may not support comment posting via API), **Then** `TicketSourceCapabilities` exposes typed booleans: `supportsCommentOnTicket`, `supportsPolling`, `supportsTicketStateUpdates`, etc. Consuming services (story 3.16 Linear completion sync) check `capabilities.supportsCommentOnTicket` before invoking — if false, the operation gracefully degrades (e.g., logs a `linear.completionSyncSkipped` event with reason `ticket_source_does_not_support_comments`).
4. **Given** vendor-neutral domain types in `domain.integration.ticketsource`, **Then** `TicketRef`, `Ticket`, `CommentResult`, `TicketSourceCapabilities` are defined with no Linear-specific fields (no GraphQL IDs, no Linear team URLs); vendor-specific data lives only inside the implementing adapter and is mapped to the neutral domain types at the port boundary.
5. **Given** Spring profile-based wiring, **Then** `application.yml` `deliveryline.integration.ticket-source.kind=linear` (default) selects the implementation; future kinds (`jira`, `github-issues`, `gitlab-issues`) plug in by adding a new implementation + a profile entry — no other code changes required.
6. **Given** a documented extension contract `docs/integrations/ticket-source-extension-contract.md`, **Then** it specifies: every method's expected behavior, error classification per `IntegrationFailureCategory`, idempotency guarantees, redaction-on-egress requirements (any text sent to the source must pass through `RedactionPolicyService`), capability declaration, configuration-key conventions (`deliveryline.integration.ticket-source.{kind}.*`), and testing requirements (must implement parity test against the same fixture scenarios used for Linear).
7. **Given** ArchUnit boundary, **Then** `TicketSourceAdapter` lives in `application.integration.ticketsource`; concrete implementations live in `adapters.integration.ticketsource.{kind}` (e.g., `adapters.integration.ticketsource.linear`); domain types live in `domain.integration.ticketsource`; ArchUnit asserts no vendor-specific types (Linear DTOs, JIRA REST types) leak through the port — verified by a per-vendor leak-detection test.
8. **Given** existing consumers (story 3.16 `WorkflowOrchestrationService.syncCompletionToLinear`, CLI `deliveryline submit` from story 1.15, runner intake polling from story 1.14), **Then** they are refactored to depend on `TicketSourceAdapter` (not `LinearAdapter`) — names like `syncCompletionToLinear` may remain for now (renaming is a follow-up cosmetic story) but the type dependency is on the abstraction.
9. **Given** the foundation gate (story 1.23) widening, **Then** the gate now asserts: `TicketSourceAdapter` interface exists, current Linear implementations satisfy it, capability declaration is honored by consumers (a contract test injects a mock adapter declaring `supportsCommentOnTicket=false` and asserts the completion sync skips gracefully).
10. **Given** the test suite, **Then** tests cover: existing Linear scenarios pass against `TicketSourceAdapter` interface (refactor preserves behavior), capability-driven graceful degradation (commentOnTicket skipped when capability=false), config-driven implementation selection (switching `deliveryline.integration.ticket-source.kind` activates a different implementation), no vendor-specific type leakage at port boundary (ArchUnit assertion), extension-contract documentation completeness (all required sections present, link-checked).

### Story 3.33: RepositoryHostAdapter Abstraction (Extract from GitHubAdapter)

As an architect preparing for future repository-host extension (Bitbucket, GitLab, Gitea, Azure DevOps Repos),
I want the existing `GitHubAdapter` port (story 3.13) renamed/refactored to a vendor-neutral `RepositoryHostAdapter` interface — with `GitHubMockAdapter` + `GitHubRealAdapter` becoming concrete implementations alongside future `BitbucketAdapter`/etc. — plus a documented extension contract,
So that adding a new repository host in a future version requires implementing one interface against one documented contract, not refactoring a GitHub-shaped port.

**Acceptance Criteria:**

1. **Given** the `application.integration.repohost` package, **Then** the generic `RepositoryHostAdapter` interface exists with vendor-neutral domain-shaped methods: `getRepositoryByRef(RepositoryRef ref) → Optional<Repository>`, `getPullRequestByRef(PullRequestRef ref) → Optional<PullRequest>`, `getBranchByRef(RepositoryRef repo, String branchName) → Optional<Branch>`, `createPullRequest(RepositoryRef repo, String sourceBranch, String targetBranch, String title, String body) → PullRequest`, `updatePullRequest(PullRequestRef ref, String body) → PullRequest`, `commentOnPullRequest(PullRequestRef ref, String body) → CommentResult`, `getCapabilities() → RepositoryHostCapabilities` — all parameters and return types use vendor-neutral domain models defined in `domain.integration.repohost`.
2. **Given** the existing `GitHubAdapter` port from story 3.13, **Then** it is renamed/refactored such that `GitHubAdapter` is no longer a separate port; `GitHubMockAdapter` + `GitHubRealAdapter` (stories 3.13, 3.14) now implement `RepositoryHostAdapter` directly. Migration note in `docs/adr/0008-repository-host-abstraction.md` records the change.
3. **Given** capability detection, **Then** `RepositoryHostCapabilities` exposes typed booleans: `supportsDraftPullRequests`, `supportsPullRequestComments`, `supportsBranchProtection`, `supportsForkPushes`, `supportsRequiredStatusChecks` — consumers check capabilities before invoking optional features.
4. **Given** vendor-neutral domain types in `domain.integration.repohost`, **Then** `RepositoryRef`, `Repository`, `PullRequestRef`, `PullRequest`, `Branch`, `CommentResult`, `RepositoryHostCapabilities` are defined with no GitHub-specific fields (no `node_id`, no GitHub-specific URL formats); vendor-specific data lives only inside the implementing adapter.
5. **Given** Spring profile-based wiring, **Then** `application.yml` `deliveryline.integration.repo-host.kind=github` (default) selects the implementation; future kinds (`bitbucket`, `gitlab`, `gitea`, `azure-devops`) plug in by adding a new implementation + a profile entry.
6. **Given** a documented extension contract `docs/integrations/repository-host-extension-contract.md`, **Then** it specifies: every method's expected behavior, error classification per `IntegrationFailureCategory`, idempotency guarantees (especially for `createPullRequest` which must be idempotent by `(repo, sourceBranch, targetBranch)` per story 3.14 AC4), redaction-on-egress requirements, capability declaration, auth-model documentation (token vs OAuth vs basic auth — per-vendor), branch-naming compatibility (e.g., Bitbucket forbids `:` in branch names; the deterministic naming convention from story 3.9 AC2 must work across all supported hosts), configuration-key conventions, and testing requirements (parity test against the same fixture scenarios used for GitHub).
7. **Given** ArchUnit boundary, **Then** `RepositoryHostAdapter` lives in `application.integration.repohost`; concrete implementations in `adapters.integration.repohost.{kind}`; domain types in `domain.integration.repohost`; ArchUnit asserts no vendor-specific types (GitHub REST DTOs, Bitbucket types) leak through the port.
8. **Given** existing consumers (story 3.9 `RepositoryWorkspaceService`, story 3.15 `IntegrationLinkService.linkGitHubPr`, runner orchestration in stories 3.11/3.12), **Then** they are refactored to depend on `RepositoryHostAdapter`. Method names like `linkGitHubPr` should be renamed to `linkPullRequest` in this story to keep the naming vendor-neutral; service method renames trigger updates to story 3.15's call sites.
9. **Given** git-protocol independence, **Then** `RepositoryWorkspaceService` (story 3.9) clones via standard git HTTPS — independent of which `RepositoryHostAdapter` is active; the host adapter handles only the API-layer concerns (PR creation, branch metadata via API, comment posting), while git operations (clone, push) use vendor-agnostic git CLI/library.
10. **Given** the foundation gate (story 1.23) widening, **Then** the gate now asserts: `RepositoryHostAdapter` interface exists, current GitHub implementations satisfy it, capability declaration is honored by consumers (e.g., if `supportsDraftPullRequests=false`, `createPullRequest` is invoked without the draft flag).
11. **Given** the test suite, **Then** tests cover: existing GitHub scenarios pass against `RepositoryHostAdapter` interface (refactor preserves behavior), capability-driven graceful degradation, config-driven implementation selection, no vendor-specific type leakage at port boundary, extension-contract documentation completeness, deterministic-branch-naming compatibility (a synthetic test asserts the naming convention from story 3.9 AC2 produces strings valid in GitHub + a documented Bitbucket-compatibility variant).

### Story 3.34: CI Tier — Real Docker Runner Image Build + Compatibility Checks

As a backend developer + CI maintainer,
I want a dedicated CI tier that builds the Codex + Claude runner images on every PR that touches `runners/` or `runner-contracts/`, runs each image's `--self-test` (story 3.3 AC8 + 3.4 AC6), and runs the real-runner contract integration test from story 3.8 in a Linux-only Docker-backed job,
So that runner-image drift, schema-contract drift, or self-test failures surface in CI before reaching the foundation gate — extending story 1.21's CI tier order with runner-specific gates.

**Acceptance Criteria:**

1. **Given** `.github/workflows/ci.yml` from story 1.21, **Then** this story adds three new CI jobs: `runner-image-build` (builds Codex + Claude images via `docker compose build` per stories 3.3 AC5 + 3.4 AC5), `runner-image-self-test` (runs `--self-test` on each built image per stories 3.3 AC8 + 3.4 AC6), `runner-contract-real` (runs `RealRunnerContractIT` from story 3.8 — Testcontainers-backed). All three jobs run on `ubuntu-latest` (Docker-backed jobs Linux-only per story 1.21 AC3).
2. **Given** path-based PR triggers, **Then** these jobs run on every PR that touches `runners/**`, `runner-contracts/**`, or the runner-related backend code under `backend/src/main/java/org/dradgo/adapters/runner/**` — not on every PR (saves CI time on docs-only changes).
3. **Given** PR comment surface, **Then** when these jobs run, a CI summary comment is posted on the PR showing: which runner images built successfully, self-test outcomes, real-runner-contract test outcomes — making runner regressions visible without hunting through the GitHub Actions UI.
4. **Given** image-tag pinning per story 3.3 AC1 + 3.4 AC1, **Then** the build job tags images with `deliveryline/codex-runner:pr-{prNumber}` and `deliveryline/claude-runner:pr-{prNumber}` for PR builds; main-branch builds tag with `:latest` + a semver tag (build args drive this).
5. **Given** flake control per story 1.21 AC5 (no blanket retries), **Then** flaky failures in these jobs are surfaced as such — a real-runner-contract flake is a tracked tech-debt item, not silently retried.
6. **Given** the foundation gate (story 1.23) widening per story 3.8 AC9, **Then** `runner-contract-real` job's success is required for foundation-gate PRs; its failure blocks merge regardless of other green checks.
7. **Given** dependent ordering, **Then** `runner-image-self-test` `needs: runner-image-build`; `runner-contract-real` `needs: runner-image-build` (consumes the built images via Docker daemon shared with Testcontainers).
8. **Given** image-build caching, **Then** the build uses BuildKit cache mounts (or GHA cache) keyed on the runner Dockerfile + entrypoint hash so repeated PRs that don't change the runner image skip the build (cache hit) — cuts CI time materially.
9. **Given** cross-runner consistency check per story 3.4 AC10, **Then** any PR touching `runner-contracts/src/main/resources/schemas/` triggers both `runner-image-build` jobs (Codex + Claude) and asserts both build successfully + pass `--self-test` — preventing schema changes from silently breaking one runner.
10. **Given** the `start-all.{ps1,sh}` convenience script (story 1.17) + the unified compose file (story 1.2 + AR24), **Then** a documented "developer can reproduce CI locally" path exists in `docs/setup-local.md`: `docker compose build && docker compose run --rm codex-runner --self-test && docker compose run --rm claude-runner --self-test` matches what CI runs.

### Story 3.35: Test Suite Extension (Runner Adapter, GitHub Adapter, Takeover, Dev Review)

As a backend + frontend developer,
I want the test suite from story 2.27 (Vitest + Playwright + axe + MSW) extended to cover Epic 3's new surfaces — `DockerRunnerAdapter` integration scenarios, real + mock GitHub adapter parity, takeover flow end-to-end, dev review accept/reject scenarios — under the existing CI tier structure,
So that Epic 3's new code paths have automated coverage and regressions are caught at the same CI gates as Epic 2's surfaces.

**Acceptance Criteria:**

1. **Given** the existing test infrastructure from story 2.27 + story 1.21 CI tiers, **Then** this story extends them with new test classes/files — does not introduce new infrastructure (no new test runners, no new mocking libraries).
2. **Given** `RunnerAdapter` integration coverage, **Then** integration tests in `backend/src/test/java/org/dradgo/integration/runner/` cover the full lifecycle scenarios from story 3.1 AC10 + story 3.2 AC10 against both `MockRunnerAdapter` (story 1.13) and `DockerRunnerAdapter` (story 3.1 — using Testcontainers) — parity asserted per story 3.8 AC5.
3. **Given** GitHub adapter parity per story 3.14 AC8, **Then** a parity test class runs the same fixture scenario sequence against `GitHubMockAdapter` + `GitHubRealAdapter` (real adapter against a documented test repository — gated behind `gh-real-tests` profile, skipped in PR CI by default to avoid GitHub API rate-limit costs; runs nightly on main).
4. **Given** developer takeover flow end-to-end, **Then** an integration test (`DeveloperTakeoverFlowIT`) exercises: spec approved → plan generated → developer takes over → run transitions to `TakenOver` → in-flight runner cancelled → queued executions cancelled → artifacts + GitHub PR linkage preserved → post-takeover allowed actions reduced to `view_only`. Asserts FR18 + FR19 + FR33.
5. **Given** developer review (accept/reject implementation) flow, **Then** integration tests cover: happy-path acceptance of plan → state at `Executing` (PR runner enqueued via story 3.17), happy-path acceptance of `prOutput` → state at `Completed` + Linear sync triggered, rejection of plan with feedback → state at `Investigating` + counter incremented, rejection of `prOutput` with feedback → state at `Executing` for re-dispatch, escalation threshold triggers `escalation.required` event.
6. **Given** frontend coverage extension per story 2.27 AC4, **Then** Vitest + Testing Library tests cover stories 3.26 (impl-plan ARP variant), 3.27 (PR/output ARP variant), 3.28 (Decision Bar impl-review mode), 3.29 (takeover UI flow), 3.30 (UI recovery baseline), 3.31 (PR linkage display) — assertions per each story's "component test coverage" AC.
7. **Given** Playwright cross-browser coverage extension per story 2.27 AC8, **Then** new keyboard-only journey tests cover: developer accept-implementation flow (queue → run → review plan → accept → see state advance), developer reject-implementation flow (with rationale dialog), developer takeover flow (with confirmation dialog + post-takeover navigation), recovery retry flow.
8. **Given** sanitization regression per story 2.27 AC7, **Then** XSS fixtures for diff content (story 3.27) and implementation-plan step text (story 3.26) are added to the adversarial fixture set — story 2.27's sanitization regression block expanded.
9. **Given** axe-core a11y scan per story 2.27 AC4, **Then** every new component test runs an axe scan; zero `wcag2aa` violations is the bar.
10. **Given** the foundation gate (story 1.23) widening, **Then** "Epic 3 frontend test suite + Epic 3 backend integration tests green" is added to the foundation-gate verification — so Epic 3 regressions block PRs the same way Epic 2's do.
11. **Given** coverage thresholds per story 2.27 AC10, **Then** thresholds are extended to cover Epic 3's new packages: `application.runner.queue` (story 3.17), `application.runner.workspace` (story 3.9), `application.integration.ticketsource` (story 3.32), `application.integration.repohost` (story 3.33), `application.recovery` extensions (story 3.22) — minimum 80% line coverage; sanitization-related code 90%.
12. **Given** flake metrics per story 1.21 AC5 + story 3.34 AC5, **Then** Epic 3 tests are surfaced in flake reports; legitimate retry policies (e.g., container cold-start flakes) are narrowly scoped with documented justification.

### Story 3.36: Execution Walkthrough Documentation Increment

As a Developer joining the pilot,
I want a `docs/execution-walkthrough.md` that explains the Epic 3 surface end-to-end — what happens when the workflow dispatches agent work, how to read the implementation plan and PR/output artifacts, how developer review + takeover preserve context, how the queue + worker pool work — with annotated screenshots/diagrams,
So that I can complete a developer-side review on my first pilot run unaided (NFR42 satisfied for the developer persona) — and the takeover non-reversibility expectation (per story 3.25 AC9) is set explicitly rather than discovered surprise.

**Acceptance Criteria:**

1. **Given** `docs/execution-walkthrough.md`, **Then** it follows a linear sequence: prerequisites (DeliveryLine running locally per story 1.22 quickstart; a governed run with an approved spec) → "what happens after spec approval" (queue → worker pickup → repository workspace prepared → runner dispatched → plan generated) → reviewing the implementation plan (in the ARP impl-plan variant — story 3.26) → accepting the plan (via Decision Bar `implementation_review` mode — story 3.28) → "what happens after plan approval" (queue → runner produces PR/output) → reviewing PR/output (diff display, branch + commit + PR refs — story 3.27) → accepting / rejecting / taking over → completion + Linear sync (story 3.16).
2. **Given** target completion time, **Then** the doc states "~15 minutes from approved spec to merge-ready PR review".
3. **Given** the takeover non-reversibility expectation per story 3.25 AC9, **Then** a dedicated section "When and how to take over" explicitly explains: takeover stops orchestrator dispatch + cancels in-flight + queued executions, takeover is non-reversible in the current release, the developer continues work in the linked GitHub PR (`deliveryline/{ticketRef}/stage-{runId}` per story 3.9 AC2) using normal git tooling, the run remains in `TakenOver` state until an operator action closes it (Epic 4).
4. **Given** screenshots or annotated diagrams (Mermaid OK), **Then** the following are illustrated: queue + worker status view (story 3.19), implementation-plan ARP variant (story 3.26), PR/output ARP variant with diff display (story 3.27), Decision Bar `implementation_review` mode with all three actions visible (story 3.28), takeover confirmation dialog with consequence text (story 3.28 AC4), post-takeover Run Context Strip showing preserved state (story 3.29).
5. **Given** the developer-rejection taxonomy per story 3.21, **Then** a section explains each tag (`incorrect_approach` / `incomplete_implementation` / `quality_issue` / `breaks_existing_functionality` / `out_of_scope`) with concrete examples — supporting consistent measurement (AR34b in Epic 5 surfaces the data).
6. **Given** the queue + worker pool concepts per stories 3.17 + 3.19, **Then** a "How parallel execution works" section briefly explains: workers configurable via `deliveryline.runner.worker-pool-size`, queue is FIFO with backpressure at `deliveryline.runner.queue-max-depth`, monitoring via `deliveryline workers status` + Grafana dashboards (when observability profile active), alert rules for queue depth — pilots understand the parallelism model without reading source.
7. **Given** the failure-recovery baseline per story 3.30, **Then** a section "What if the agent fails?" explains: failure visible in Run Context Strip + run timeline, retry available via Decision Bar `recovery_operator` mode, deeper recovery options arrive in Epic 4 — sets expectations cleanly.
8. **Given** cross-platform usability, **Then** the walkthrough is browser-based and contains no OS-specific instructions — works identically on Windows / macOS / Linux per story 1.17 supported-environment matrix.
9. **Given** the link-check CI step from story 1.22 AC8, **Then** all internal doc links resolve to real files; cross-references to stories 1.18, 2.16, 2.19, 3.21, 3.25, 3.28, 3.29 are anchored correctly.
10. **Given** documentation-increment acceptance per Epic 3's doc-increment rule (pre-mortem refinement R7), **Then** Epic 3 cannot close without `execution-walkthrough.md` merged + visible from `docs/index.md`; the foundation-gate-equivalent E3 close gate verifies its presence.
11. **Given** a Developer-validator placeholder (parallel to story 1.22 AC7 + story 2.29 AC11 — John's party-mode finding "name the human validator per epic"), **Then** the doc includes a placeholder line for "Developer walkthrough validator: ***\_***_ (to be named before Epic 3 close)" — reminding Alex to identify and coordinate with the real human developer whose cold walkthrough validates the epic.
12. **Given** NFR43 (minimize new concepts), **Then** the walkthrough uses only the concept set declared in the PRD (ticket, spec, run, artifact, review, failure, recovery action) plus Epic 3 vocabulary (worker pool, queue, takeover, PR linkage, branch reference) — new concepts require updating `docs/glossary.md` from story 1.22 AC10.

---

## Epic 3a Net-New Stories (added 2026-05-26)

These two stories cover gaps not represented in the original Epic 3 plan. Both are part of the Epic 3a active slice per `sprint-change-proposal-2026-05-26.md`. Full acceptance criteria are drafted by `bmad-create-story` when each story enters the cycle — the stubs below capture working title, goal, dependencies, and AC-shape reference.

### Story 3a-1: Spec-Stage Orchestration — `dispatchSpecGeneration`

As a backend developer + workflow orchestrator,
I want `WorkflowOrchestrationService.dispatchSpecGeneration(workflowRunId)` invoked on the `Inbox → Investigating` state transition — analogous to story 3.11's `dispatchPlanGeneration` but for the spec stage,
So that ticket submission via story 1.15 / 6.9 / 2.13 automatically dispatches a real runner against the spec-stage context bundle (story 2.8 baseline + story 3a-2 repo extension), producing a spec artifact and transitioning to `WaitingForSpecApproval` — instead of relying on test code dispatching the mock runner directly.

**Dependencies:** 3.1 (DockerRunnerAdapter), 3a-2 (spec-stage repo-context bundle), 2.8 (spec artifact model + spec-stage bundle baseline), 1.13 (RunnerBroker + ContextBundleService), 1.5 (state-transition table).

**AC-shape reference:** Follow story 3.11 ACs 1–11 with the following differences:
- Trigger transition: `Inbox → Investigating` (not `WaitingForSpecApproval → Executing`)
- Target artifact type: `spec` (not `implementationPlan`)
- Success transition: `WaitingForSpecApproval` (not `WaitingForReview`)
- Runner image selection: configurable per `application.yml` `deliveryline.runner.spec-stage.kind` (codex or claude)
- Retry path: `retrySpecGeneration(workflowRunId)` analog of 3.11 AC5 — re-prepares the workspace (idempotent branch reuse) + re-dispatches with fresh context bundle version
- ArchUnit boundary: same as 3.11 AC9 — `WorkflowOrchestrationService` remains the only path that auto-triggers state transitions on runner outcomes
- Idempotency: same as 3.11 AC6 — duplicate `dispatchSpecGeneration` calls for an in-flight spec-stage execution are no-ops
- Correlation propagation: same as 3.11 AC7 — `correlationId` flows from REST/CLI request through runner stdout
- Test coverage: end-to-end happy path (submit → spec generated → state at WaitingForSpecApproval), each runner failure mode → state at Failed, retry after failure, idempotent re-dispatch, `RUNNER_ARTIFACT_TYPE_MISMATCH` if runner emits non-spec result at spec stage

**Note:** Story 2.10 AC4 already references `WorkflowTransitionService.transition(workflowRunId, targetState='Investigating', ...)` as the re-entry path after spec rejection — this story makes that auto-dispatch behavior end-to-end, replacing whatever in-test scaffolding currently handles spec generation in tests.

### Story 3a-2: Spec-Stage Repo-Context Bundle Extension

As a backend developer,
I want `ContextBundleService.create(workflowRunId, stage='spec-investigation')` extended to include a reference to the cloned repository working tree (from story 3.9 `RepositoryWorkspaceService`) and a curated repo summary (top-level tree, README content, package/config manifest references) — extending the bundle defined in story 2.8,
So that the spec runner has actual codebase context when generating a spec against a real Linear ticket — closing the gap between "ticket text" (current story 2.8 scope) and "actual git project" that the user requires for the active-slice scenario.

**Dependencies:** 2.8 (spec artifact model + spec-stage bundle baseline), 3.9 (RepositoryWorkspaceService — repo clone + mount), 1.10 (redaction policy — applies to bundled repo content), 1.6 (runner-contracts schema — bundle version + validation), 2.24 (redaction-policy hardening on PEM blocks / bundle JSON / credential patterns — bundle composition feeds into the same hardened redactor).

**AC-shape reference:** Follow story 3.10 ACs 1–10 adapted for the spec-investigation stage:
- Bundle composition: extends story 2.8 AC3 to additionally carry `repositoryWorkspaceRef` (path to the runner-mounted working tree from story 3.9), `repositoryTreeSummary` (top-level tree listing with file types, depth-limited), `repositoryReadmeRef` (artifact ref to redacted README content), `packageManifestRefs` (refs to redacted package.json / pom.xml / Cargo.toml / etc.), `ticketRepositoryMappingVersion` (which Linear↔GitHub mapping resolved to this repo)
- Schema validation: bundle continues to conform to `context-bundle.v1.schema.json` per story 1.6 — extending the schema as needed (additive, version bump if breaking)
- Redaction: same rigor as 3.10 AC4 — adversarial fixture tests prove no Linear API key, GitHub token, absolute machine path, or `.env` content appears in the persisted bundle even when source content contains them
- Versioning: same as 3.10 AC5 — each bundle persisted with monotonic `contextBundleVersion`; re-dispatch creates a new version, never overwrites
- Inspection: same as 3.10 AC6 — `WorkflowInspectionService.getContextBundleForArtifact(artifactId)` returns the spec-stage bundle for inspection per FR55, visible via CLI `deliveryline status {runId} --include-context-bundle` and REST detail expansion
- ArchUnit boundary: same as 3.10 AC8 — bundle composition depends only on application services + domain types
- Fixture extension: at least one fixture event stream entry in story 1.23's fixture set includes a full spec-stage bundle with repo context, so E2 frontend tests (story 2.17 spec variant rendering) have realistic fixture data
- Test coverage: spec-stage bundle composition with repo content, redaction adversarial fixtures including PEM blocks and bundle JSON shapes (leveraging 2.24's hardened patterns), schema validation rejection of malformed bundles, inspection returns the bundle used by a specific spec artifact's runner execution

**Note:** Story 2.8's existing AC3 explicitly omits approved-spec reference at spec-investigation stage (correctly — no prior spec exists yet at this stage). This story adds repo content fields *alongside* the existing AC3 fields, not replacing them.

### Story 3a-3: Codex Subscription Auth via `auth.json`

As a runner-infrastructure developer,
I want the `runners/codex/` runner image to authenticate the Codex CLI with a ChatGPT/subscription account by materializing a `CODEX_AUTH_JSON` secret env var into `$CODEX_HOME/auth.json` before invoking Codex — the Codex twin of story 3.4's Claude subscription-first dual-mode auth, but file-based (Codex keeps subscription credentials in a file, not an env var),
So that operators can run the Codex runner against a Pro/subscription account (the cost-saving path) instead of per-token API-key billing, selectable purely by which credential is present — with no new container mount capability.

**Dependencies:** 3.3 (Codex runner image — Dockerfile + entrypoint + `runner.mjs` — the template this extends), 3.5 (RunnerSecretsService + `secret-env-names` config — the env-var pipeline `CODEX_AUTH_JSON` rides), 3.4 (Claude subscription-first dual-mode auth — the precedent pattern), 3.6 (RunnerSecretScanService — leak scan over the injected value).

**AC-shape reference:** Mirror story 3.4's auth-mode tasks (Task 5) adapted for file-based provisioning:
- Precedence: `CODEX_AUTH_JSON` (subscription) added as the **first** entry in `deliveryline.runner.secret-env-names.codex` → `[CODEX_AUTH_JSON, CODEX_API_KEY, OPENAI_API_KEY]`; first-present-wins resolution makes it subscription-first, API key the fallback. NAMES only in config (never values).
- Materialization: a new `runner.mjs materialize-auth` subcommand validates `CODEX_AUTH_JSON` parses as a non-empty JSON object and writes it atomically to `$CODEX_HOME/auth.json` with mode `0600`; the entrypoint takes this branch (instead of exporting `OPENAI_API_KEY`) when the resolved credential is `CODEX_AUTH_JSON`, sets+exports `CODEX_HOME`, and removes the file via the `cleanup()` trap on exit.
- Leak-safety: raw single-line JSON (not base64) keeps `RunnerSecretScanService`'s literal-substring detector effective; the file lives outside the workspace mounts; the value is logged by name+presence only.
- Backend: config + docs only (`application.yml` **and** test yaml, doctor `CHECK_RUNNER_SECRETS` remediation text, `.env.example`) — `RunnerSecretsService` / `DoctorProbeAdapter.probeRunnerSecrets` / `RunnerSecretScanService` / `DockerRunnerAdapter` are name-driven and need **no logic change**.
- Scope: seam + plumbing (mirrors how 3.3/3.4/3.5 landed their auth seams) — full materialization path, config, doctor/redaction awareness, and CI-green tests with fixtures. Real Codex subscription execution + token refresh (needs network egress vs the `--network=none` contract tier) are deferred to story 3.8.
- Design source: `docs/superpowers/specs/2026-06-03-codex-subscription-auth-json-design.md` (brainstormed + approved 2026-06-03).

### Story 3a-5: Scheduled Linear Auto-Ingest — Poll-Driven Run Creation

> Net-new per `sprint-change-proposal-2026-06-07.md` (Epic 3a active slice). Closes the non-CLI intake gap on the scheduled side: turns the Epic-1 polling *watcher* into an opt-in auto-ingest intake.

As a Workflow Owner / operator,
I want the scheduled Linear poll loop (`LinearPollingHost`) to automatically create a governed run for each *qualifying* newly-discovered ticket — calling the same `WorkflowCommandService.submit` the CLI/REST adapters use,
So that low-risk tickets enter the workflow without anyone running the CLI — and the auto-created run flows straight into story 3a-1's spec auto-dispatch for a fully hands-off Linear-ticket → real-spec → review scenario.

**Current behavior to change:** `LinearPollingHost.pollLinearInternal()` only touches `integration_links.last_sync_at` and explicitly "does not create new integration links — ingestion happens via CLI submit (story 1.15)". This story adds the create path behind an opt-in flag.

**Dependencies:** 1.13/1.14 (LinearPollingHost + `LinearAdapter.pollNewTickets` — the loop this extends), 1.7 (`WorkflowCommandService.submit` + `SubmitWorkflowCommand`), 1.12 (IdempotencyService — dedupe), 3a-4 (team/project poll scoping — bounds which tickets are eligible), 3a-1 (spec-stage auto-dispatch — the downstream consumer of the created run), AR18 (IntegrationLinkService idempotent-by-ticket intake).

**AC-shape reference:**
- **Feature gate:** new config flag `deliveryline.linear.auto-ingest.enabled` (default **false**, OPTIONAL+UNVALIDATED appended so no `@SpringBootTest` yaml break per `[[validated-config-needs-test-yaml]]`). When off, poll behavior is byte-identical to today (watcher only).
- **Eligibility filter:** ingest only tickets matching a configured label/state allow-list (plus the existing 3a-4 team/project scope); non-qualifying tickets are touched (existing path) but not submitted. Never auto-ingest the entire workspace.
- **Idempotent submit:** for each eligible ticket with no existing active integration link, build a `SubmitWorkflowCommand` (`actorType=SYSTEM`) with a deterministic idempotency key derived from the ticket identity (re-polls + JVM restarts cannot double-create — replay semantics per 1.12), then call `WorkflowCommandService.submit`. Existing-link tickets keep the touch-only path (AR18 idempotent-by-ticket).
- **Failure isolation:** a per-ticket submit failure logs WARN + a classified failure category and does NOT abort the batch or stall the watermark (mirror the existing per-ticket touch best-effort + cursor-preservation logic).
- **Watermark safety:** cursor advancement rules unchanged; a partial-failure batch preserves the cursor exactly as today.
- **Boundary:** `LinearPollingHost` (infrastructure) drives intake via the application command port only — no repository/orchestration logic (AR11), no `org.dradgo.adapters..` import (`[[application-cannot-import-adapters]]`).
- **Observability:** poll-batch log gains `ingested=N skipped=N ineligible=N` counts; actorType + ticketRef logged, token never logged (3a-4 convention).
- **Test coverage:** unit (MockRestServiceServer / mock `LinearAdapter`) — eligible ticket auto-submits once, re-poll is a no-op (idempotent), ineligible ticket is touch-only, disabled-flag = byte-identical legacy behavior, one submit failure doesn't abort the batch, watermark preserved on partial failure.

**Note:** Upstream of story 3a-1 — the auto-created run lands in `Inbox`, and 3a-1's `dispatchSpecGeneration` (on the `Inbox → Investigating` transition) then auto-dispatches the real spec runner. The two stories together enable hands-off intake with no CLI.

### Story 3a-8: OpenSpec Spec-Driven Authoring During Runs

> Net-new per `sprint-change-proposal-2026-06-13-openspec-pipeline.md` (Epic 3a active slice). Layered on **3a-6** (which bakes the `openspec` CLI into both runner images). Authoritative design: `docs/superpowers/specs/2026-06-13-openspec-pipeline-integration-design.md`.

As the platform,
I want each runner stage to author its OpenSpec change-folder artifact behind an opt-in flag, and the pr-output stage to assemble and commit `openspec/changes/<id>/` (`proposal.md` / `specs/` / `design.md` / `tasks.md`) into the delivered PR,
So that every run leaves a durable, version-controlled, methodology-shaped spec next to the code (3a-6 only *installs* the CLI; this story makes OpenSpec actually drive the run).

**Approach (decided in design):** **Approach A — entrypoint-orchestrated, prompt-driven authoring.** The agent emits OpenSpec-shaped Markdown to **stdout** (its normal artifact channel) via a documented file-fence convention; the entrypoint scaffolds the convention and, at pr-output, reconstructs + commits the folder. No runner-contract / schema change. Opt-in, default OFF.

**Dependencies:** 3a-6 (`openspec` CLI present in both images), 3.10 (`approvedImplementationPlanReference` carry-forward), 3a-2 (spec-stage repo-context bundle + `/workspace/repo` mount), 2.8 (spec artifact model + spec-stage bundle baseline), 3.3/3.4 (the two runner images + entrypoints — mirror rule). Independent of 3a-7 (superpowers).

**AC-shape reference (design doc is authoritative):**
- **Opt-in gate:** `deliveryline.runner.openspec.enabled` (default **false**, OPTIONAL+UNVALIDATED per `[[validated-config-needs-test-yaml]]`) surfaced as container env `DELIVERYLINE_RUNNER_OPENSPEC`, threaded like `DELIVERYLINE_RUNNER_STAGE`. Off ⇒ byte-identical to today (no scaffold, no prompt delta, no `openspec/` folder in the PR).
- **1:1 stage mapping:** spec-investigation authors `proposal.md` + `specs/<cap>/spec.md` deltas (OpenSpec ADDED/MODIFIED/REMOVED format); implementation-plan authors `design.md` + `tasks.md`; pr-output implements `tasks.md` + assembles + commits the folder. Deterministic `change-id` from `ticketRef` + `workflowRunId`.
- **Read-only posture preserved:** spec/plan stages stay `--sandbox read-only`, emit to stdout; only pr-output mutates `/workspace/repo`. Non-interactive `openspec init` runs **only** at pr-output (fallback: pre-baked skeleton if init has no non-interactive flag).
- **Carry-forward = existing seams:** read `approvedSpecificationReference` + `approvedImplementationPlanReference` by `referencePath` from the input mount; **no bundle-schema change**. A `runner.mjs split-fenced` helper reconstructs the folder; `openspec validate` guards structure.
- **Failure posture:** additive — `init`/`validate` failure logs WARN + best-effort folder + still ships the code; no new failure-category enum; flag-off = zero new failure surface.
- **Mirror rule:** both entrypoints + both `runner.mjs` + both READMEs + `RUNNER_CONTRACT.md` in one PR.
- **Tests:** entrypoint unit (flag-off byte-identical; flag-on per-stage prompt + read-only scaffolds to output not repo); `runner.mjs split-fenced` unit; conformance ITs (fixture artifacts → folder materializes + `openspec validate` invoked + read-only stages leave repo untouched; flag-off byte-identical). Real authoring quality = live-run spike.

**Risk:** headless authoring reliability is the biggest unknown (mitigated by validate + best-effort + default-off); `openspec init` non-interactivity is a spike; the offline mock-openspec (from 3a-6) may need `init`/`validate` stubs to keep the conformance build green (`[[runner-tool-self-test-needs-offline-mock]]`).

### Story 3a-9: Spec Artifact Live Review + Approval — Read-Model `artifactId` Exposure + Artifact-Read Endpoint

> Net-new (correct-course 2026-06-14). Surfaced by the Epic-2-retro **"first real full-cycle run"** pilot-readiness gate (`[[epic-2-retro-real-run-gate]]`, C1–C3): the first real Linear→spec runs (e.g. `run_b3fbcafb…`, FIN-18) reached `WaitingForSpecApproval` with a genuine spec artifact, but the reviewer **cannot read the spec and cannot approve it** in the UI. Three dormant seams stack up; the spec stage was never exercised end-to-end before the real-run gate.

As a Workflow Owner / spec reviewer,
I want the run-detail read surface to expose the spec artifact's **public id** and a **read endpoint that returns the artifact's content**, so the Artifact Review Panel (story 2.17) renders the real spec body and the Approval/Decision Bar (story 2.19) can fire `approve_spec`/`reject_spec` against a live artifact,
So that a real spec produced by story 3a-1 is actually reviewable and approvable through the UI — closing the last mile of the hands-off Linear-ticket → real-spec → human review loop instead of stranding every run at `WaitingForSpecApproval`.

**Current behavior to change (three stacked gates, observed on real runs):**
1. **Ingest leaves the spec `pending`.** The runner-ingested spec artifact stays `status=pending`; the human approval gate (`ArtifactService.isApprovalEligible` requires `status=AVAILABLE` + checksum + storageRef) rejects approval with `ARTIFACT_PAYLOAD_UNAVAILABLE`. *(Gate 1 is already wired in a working-tree change — `RunnerBroker` now calls `ArtifactOperationService.markAvailable` for the spec stage on ingest; this story formalizes + tests it and supersedes `[[markavailable-has-no-production-caller]]` for the spec stage.)*
2. **The read model omits the artifact id.** `WorkflowDetail.latestArtifacts[]` carries only `{artifactType, version, status}` — no `artifactId`. The approval bar's `resolveSpecArtifactId` (story 2.19 **T-ARTIFACTID**) therefore resolves `undefined` → `blocked` → *"The specification is not yet available for a decision."*
3. **No artifact-content read endpoint.** `useArtifact(artifactId)` (story 2.17) is a disabled stub ("ships with the artifact-read story"); the detail route can only link a hardcoded `art_sample0001` → *"Artifact not generated yet."*

**Dependencies:** 3a-1 (spec auto-dispatch — produces the artifact), 2.8 (spec artifact model + payload store), 2.13 (`approve-spec`/`reject-spec` mutation endpoints + `ARTIFACT_PAYLOAD_UNAVAILABLE` mapping), 2.14 (allowed-actions version stamp the bar already reads live), 6.9 (`WorkflowController` read endpoints + `WorkflowInspectionService` snapshot — `LatestArtifactView` lives here), 2.17 (Artifact Review Panel + dormant `useArtifact` stub), 2.19 (Approval/Decision Bar + the T-ARTIFACTID seam that auto-lights), 1.8 (Problem Details), 1.10/2.24 (redaction — artifact content served must already be redacted shareable content), 1.21 (OpenAPI drift check).

**AC-shape reference:**
- **Spec artifact `available` on ingest (Gate 1 — formalize):** on a successful spec-stage ingest the broker promotes the artifact to `available` (checksum over the ingested payload bytes + the storage-store-reported `storageRef`), so `isApprovalEligible` passes. Scoped to `spec`; `implementationPlan`/`prOutput` keep their deliberate `pending`-on-ingest posture (no human gate). Idempotent-replay safe. Backend test: real-wiring IT asserts the ingested spec is `available` and `approveSpec` advances `WaitingForSpecApproval → Executing`.
- **Expose `artifactId` (Gate 2):** add the artifact **public id** to `LatestArtifactView` (`WorkflowInspectionService`) → `WorkflowDetail.latestArtifacts[].artifactId`; regenerate the committed OpenAPI snapshot + frontend `schema.ts` (story 1.21 drift check passes; cross-shell regen per `[[openapi-regen-platform-shim]]`). The bar goes live with **zero component changes** — `resolveSpecArtifactId` already reads the field (T-ARTIFACTID closed).
- **Artifact-read endpoint (Gate 3):** `GET /api/v1/workflows/{workflowRunId}/artifacts/{artifactId}` returns a typed artifact DTO — `artifactType`, `version`, `status`, `classification`, `createdAt`, `checksum` (short-form), and the **redacted** payload `body` — sourced from the existing artifact record + payload store. Cross-run guard: an `artifactId` not owned by `{workflowRunId}` returns `RUN_NOT_FOUND`/`ARTIFACT_RECORD_NOT_FOUND` (never another run's artifact). Problem Details: `RUN_NOT_FOUND` (404), `ARTIFACT_RECORD_NOT_FOUND` (404), `INVALID_COMMAND_PAYLOAD` (400). OpenAPI documented + snapshot regenerated.
- **Frontend wiring (minimal):** flip `useArtifact` from disabled stub → live `apiClient.GET` against the new endpoint (reuse the `workflowKeys.artifact(artifactId)` key reserved in 2.6); the detail route replaces the hardcoded `art_sample0001` link with the real spec `artifactId` from `latestArtifacts` (and renders the inline review entry-point rather than scaffold copy). The ARP flips `empty → default` with zero panel changes (story 2.17 dormancy boundary).
- **Classification / redaction:** the served `body` is the already-redacted shareable artifact content (no new redaction logic — reuse the persisted payload); a `local-only` artifact is never served as shareable. Adversarial test: no Linear/GitHub token, absolute path, or `.env` content in the response.
- **ArchUnit boundary:** the new endpoint lives on a `*Controller` in `adapters.rest` (`@RestController`, `[[spa-fallback-lives-in-problemdetailsmapper]]` placement rule); read path depends only on application services + domain types.
- **Test coverage:** backend contract test for the read endpoint (happy 200 with redacted body, each 4xx, cross-run guard) + a `WorkflowInspectionService` test asserting `latestArtifacts[].artifactId` is populated; frontend `useArtifact` live test + approval-bar `ready` (not `blocked`) once `artifactId` is present + ARP renders the real spec body; one end-to-end (real wiring) happy path: real spec run → read spec body → approve → `Executing`.

**Note (scope discipline):** read + approval-enable only — NOT artifact revision history, compare/diff (Epic 4), implementation-plan/pr-output variants (3.26/3.27), or per-viewer authorization. The implementation-plan + pr-output artifacts deliberately remain `pending`-on-ingest and out of this story; their human gate (developer review) arrives with 3.20/3.23/3.26. This is the spec-stage slice of the read surface only.

**Risk:** the OpenAPI/`schema.ts` regen is a known cross-shell chore (`[[openapi-regen-platform-shim]]`, `[[frontend-lockfile-cross-platform]]`); adding `artifactId` to `LatestArtifactView` is a contract change that reds `RegistryContractTest`/snapshot drift until regenerated (`[[new-workfloweventtype-fixture-sites]]` pattern). Verify on Linux/Docker CI before merge (`[[verify-ci-fixes-in-clean-env]]`).

---

## Epic 3b Net-New Stories (added 2026-06-16)

> Net-new per `sprint-change-proposal-2026-06-16-waiting-for-review.md` ("Option X" — execution-stage review loop). Surfaced by the first real full-cycle **execution** run (`run_ae258aa42f524ba29db3c795732a21e6`, FIN-21) reaching `WaitingForReview` unusable end-to-end — the execution-stage twin of the spec-stage gate that story 3a-9 closed (the Epic-2-retro "first real full-cycle run" pilot-readiness gate, `[[epic-2-retro-real-run-gate]]`). Three approved 2026-06-16 design specs decompose it: `2026-06-16-runner-two-phase-execution-contract-design.md` (#1), `2026-06-16-waiting-for-review-availability-design.md` (#2b), `2026-06-16-waiting-for-review-ui-design.md` (#3). Full acceptance criteria are drafted by `bmad-create-story` when each story enters the cycle — the stubs below capture working title, goal, current-behavior, dependencies, AC-shape reference, and design source. **Sequencing:** `(3b-1 → 3b-2)` and `3b-3` parallel → `3b-4` → `3b-5`/`3b-6`.

### Story 3b-1: Thread `ExecutionSubStage` → Runner Stage Token (Dispatch + Adapter)

As a backend developer + workflow orchestrator,
I want the execution dispatch to carry the resolved `ExecutionSubStage` so `DockerRunnerAdapter` sets `DELIVERYLINE_RUNNER_STAGE` to `implementation-plan` / `pr-output` (the tokens `entrypoint.sh map_stage` already accepts) instead of the coarse `execution`,
So that an `IMPLEMENTATION_PLAN` dispatch runs the read-only plan phase (emits `implementationPlan`, no push) and a `PR_OUTPUT` dispatch implements + pushes + emits `prOutput` — fixing the root cause that every execution dispatch ran `prOutput` regardless of sub-stage.

**Current behavior to change:** `DockerRunnerAdapter` (`:243`) sets `DELIVERYLINE_RUNNER_STAGE = request.stage().value()` → `"execution"`; `entrypoint.sh map_stage` (`:246`) maps `execution → prOutput` unconditionally. The broker already derives the sub-stage for logging (`executeQueuedDispatch … subStage=IMPLEMENTATION_PLAN`) — reuse that single derivation.

**Dependencies:** 3.1 (DockerRunnerAdapter), 3.11 (`dispatchPlanGeneration` + execution dispatch path), 3.12 (PR/output orchestration + `validateAndEnrichPrOutput`), 1.13 (RunnerBroker + ContextBundleService — `deriveExecutionSubStage`), 1.6 (runner-contracts schema), 3.3/3.4 (runner images + `entrypoint.sh map_stage` — confirmed stage-aware, no image change expected).

**AC-shape reference:**
- Dispatch carries the resolved `ExecutionSubStage` (preferred: add the sub-stage to the dispatch request built in `executeQueuedDispatch`; alternative: keep `request.stage()` as `RunnerStage` and add a `subStage` field, adapter chooses the env token from the pair — decide in story design). `INVESTIGATION` still maps to `spec-investigation`/`investigation`.
- `DockerRunnerAdapter` sets `DELIVERYLINE_RUNNER_STAGE ∈ {implementation-plan, pr-output}` from the resolved sub-stage. **Caution:** do not add a second Docker record constructor — it breaks Spring binding (`[[runner-image-stale-causes-exit-20]]`).
- Conformance ITs (folds in spec #1 "Story C"): an `implementation-plan` dispatch produces an `implementationPlan` with `read-only` sandbox (no repo writes); a `pr-output` dispatch produces a `prOutput`. Confirm `runner.mjs build --stage` already emits both shapes (it does) — assert, don't add.
- Unit/IT: each sub-stage dispatches the correct runner stage token; investigation/spec path unchanged (regression).
- ArchUnit boundary unchanged; `RUNNER_ARTIFACT_TYPE_MISMATCH` still guards a runner emitting the wrong artifact type for the dispatched sub-stage.

**Design source:** `docs/superpowers/specs/2026-06-16-runner-two-phase-execution-contract-design.md` (sub-project #1, Story A + Story C).

### Story 3b-2: Two-Dispatch Execution Orchestration — Plan-Approval Re-Dispatches the PR Phase

As a workflow orchestrator,
I want approving the implementation plan to re-dispatch a second execution run (the PR phase), so the full walk is approve spec → dispatch #1 (plan, read-only) → plan review → approve plan → dispatch #2 (PR, implement+push) → PR review → accept → `Completed`,
So that the two-phase contract from 3b-1 actually reaches the `PR_OUTPUT` sub-stage and persists the `github_pr` link via `validateAndEnrichPrOutput`.

**Current behavior to change:** today only ONE execution dispatch happens (because the runner always did `prOutput`). **Open question to resolve in story design:** does `TechnicalApprovalService.acceptImplementation` on an `implementationPlan` (→ `Executing`) already re-dispatch the PR phase, or must it be added? Trace `WorkflowOrchestrationService.onPlanStageSucceeded` / the accept-plan transition and the dispatch trigger.

**Dependencies:** 3b-1 (sub-stage threading — required), 3.11 (`dispatchPlanGeneration`), 3.12 (`validateAndEnrichPrOutput` + PR/output orchestration), 3.20 (`acceptImplementation` — the plan-approval entry point), 3.15 (GitHub PR integration-link persistence), 1.5 (state-transition table).

**AC-shape reference:**
- Spec approval triggers `dispatchPlanGeneration` (already observed); with 3b-1 the first execution dispatch genuinely runs the plan phase (read-only, no push).
- Accepting the plan re-dispatches the PR phase (add the trigger if absent); the PR dispatch produces a `prOutput`, pushes, and `validateAndEnrichPrOutput` runs (persisting the `github_pr` link).
- Idempotency: duplicate accept / re-dispatch for an in-flight PR-phase execution is a no-op (mirror 3.11 AC6).
- **End-to-end orchestration IT (the headline AC):** approve spec → plan dispatch emits `implementationPlan` (no push) → `WaitingForReview` → accept plan → PR dispatch emits `prOutput` + pushes + persists `github_pr` link → `WaitingForReview` → accept → `Completed`. Investigation/spec path unchanged (regression).
- `[[post-commit-hook-needs-requires-new]]` applies if the re-dispatch fires from a transaction-synchronization afterCommit hook.

**Design source:** `docs/superpowers/specs/2026-06-16-runner-two-phase-execution-contract-design.md` (sub-project #1, Story B).

### Story 3b-3: WaitingForReview Artifact Availability — Mark `implementationPlan`/`prOutput` `available` on Ingest + Surface the Implementation-Artifact Link

As a Workflow Owner / developer reviewer,
I want execution-produced artifacts (`implementationPlan`, `prOutput`) marked `available` on ingest and a state-aware "Open the implementation output →" link surfaced at `WaitingForReview`,
So that `acceptImplementation` (which requires `isApprovalEligible` = AVAILABLE) can fire and the reviewer can actually reach the implementation artifact — the execution-stage twin of 3a-9's spec-stage Gate 1.

**Current behavior to change:** only `spec` is marked available (`RunnerBroker.markSpecArtifactAvailable`, ingest loop `RunnerBroker.java:1342`); `implementationPlan`/`prOutput` stay `pending`. The detail route (`routes/workflows/$workflowRunId/index.tsx`) links only the spec via `resolveSpecArtifactId`. The embedded SPA bundle is stale (predates the `implementation_review` bar).

**Dependencies:** 3a-9 (spec-stage availability pattern — generalize `markSpecArtifactAvailable` → `markArtifactAvailable`; this story **supersedes** 3a-9's deliberate `pending`-on-ingest posture for `IMPLEMENTATION_PLAN`/`PR_OUTPUT`), 3.12 (3.12 enrich `UPDATE` interaction), 1.12 (artifact operations + `markAvailable`, `[[markavailable-has-no-production-caller]]`), 2.1 (SPA embed at `mvn package`), 3.28 (`implementation_review` bar — re-embed activates it), `resolveImplementationArtifact` (live as of 3a-9).

**AC-shape reference:**
- Backend: in `RunnerBroker.onResult`'s ingest loop (~`:1342`), after the artifact `CREATE` succeeds, mark `SPEC`, `IMPLEMENTATION_PLAN`, and `PR_OUTPUT` `available` (today only `SPEC`). Generalize `markSpecArtifactAvailable` → `markArtifactAvailable` (checksum over the ingested payload bytes + the payload-store-reported `storageRef`; no spec-specific assumptions). Idempotent-replay safe.
- `prOutput` × 3.12 enrich: the enrich `UPDATE` runs only in the `PR_OUTPUT` sub-stage and after the in-loop marking. **Verify the enrich does not revert status to `pending`; if it does, re-mark `available` after `enrichPrOutputArtifact`** so the enriched version is the available one. Auto-advance (`onPlanStageSucceeded`/`onPrOutputStageSucceeded`) unchanged.
- Frontend: render an "Open the implementation output →" link when `resolveImplementationArtifact(data)` resolves an artifact id (prefers `prOutput`/`implementationPlan`, highest version), targeting the existing `/workflows/$workflowRunId/artifacts/$artifactId` route. No decision-bar change here.
- Rebuild + re-embed: `mvn package` rebuilds the SPA into backend `static/` (`[[embedded-frontend-at-package-phase]]`); verify on the live `WaitingForReview` run.
- Tests (failing-first): `RunnerBrokerUnitTest` — ingested `prOutput`/`implementationPlan` end `available`; `PrOutputOrchestrationIT`/`ImplementationPlanOrchestrationIT` — artifact row `status=available` + run `WaitingForReview`; no spec regression (`SpecStageOrchestrationIT`); a route/page test asserting the impl-artifact link renders with a `prOutput` `artifactId` and not when only a spec exists.

**Note:** marking `prOutput` available is a prerequisite for #3's accept flow (`isApprovalEligible`). This story does NOT change runner behavior or fix the sub-stage mismatch (that is 3b-1/3b-2), and does NOT ship the real `prOutput` renderer (that is 3b-5) — the generic viewer may render a raw `prOutput` JSON as `error`, which is acceptable for this story.

**Design source:** `docs/superpowers/specs/2026-06-16-waiting-for-review-availability-design.md` (sub-project #2b).

### Story 3b-4: Developer-Role Wiring at WaitingForReview

As the single operator (one user, multiple roles for now),
I want the UI to request allowed-actions as `developer` when `currentState === 'WaitingForReview'` and send the `developer` reviewer role on accept/reject/takeover calls,
So that the already-built `implementation_review` decision bar's actions appear and fire instead of being `blocked`/inert because `getAllowedActions` defaults to `product_reviewer`.

**Current behavior to change:** `getAllowedActions` defaults to `product_reviewer` (the `ApprovalReviewerRoleResolver` `@Value` fallback); accept/reject/takeover are only returned for the `developer` role → the decision bar is `blocked`.

**Dependencies:** 3.28 (`implementation_review` bar + `ImplementationReviewDecisionBarContainer` + `useAcceptImplementation`/`useRejectImplementation`/`useTakeoverWorkflow` hooks), 3.23/3.24/3.25 (accept/reject/takeover REST endpoints + reviewer-role boundary), 2.14 (allowed-actions version stamp the bar reads live), 2.13 (header-based role attribution — explicitly OUT of scope; keep the wiring isolated so a future 2.13 swap-in is clean).

**AC-shape reference:**
- UI requests allowed-actions as `developer` at `WaitingForReview` (single-user-all-roles) and sends the developer reviewer role on the decision calls. Mechanism decided in story design; keep isolated for a future 2.13 header-attribution swap.
- With the developer role, the bar renders accept/reject/takeover and a decision transitions the run (vitest + a manual run after `mvn package`).
- A thin contract test that allowed-actions for `WaitingForReview` returns the developer action set.
- **Note:** a `prOutput` accept still needs the `github_pr` link (backend `assertNoConflictingRepoLink` / accept PR-link gate) from 3b-1/3b-2 — sequence the full `prOutput` accept after #1, or test against a plan-phase accept (plan-phase accept/reject/takeover do not need the link).

**Design source:** `docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md` (sub-project #3, Story A; takeover folds in).

### Story 3b-5: `prOutput` Review Renderer — PR Link + State Badge + Unified Diff

As a developer reviewer,
I want a dedicated `prOutput` review panel that renders the PR link (via `githubRef` + `PrStateBadge`) and the unified diff (via `SafeUnifiedDiffRenderer` / `parseUnifiedDiff`),
So that a `prOutput` (JSON: branch/commitSha/prReference/diffReference) renders as a reviewable PR + diff instead of `error`, and accept/reject fire end-to-end against the real PR.

**Current behavior to change:** the generic artifact viewer renders the artifact body as markdown; a `prOutput` JSON likely fails the `isArtifactView` guard → renders `error`. **Reconcile against done story 3.27** (PR/output ARP variant renderer): the variant exists but is likely unreachable for a live `prOutput` because the raw JSON body fails `isArtifactView` (`[[artifact-read-dto-must-satisfy-isartifactview]]`, `[[artifactview-variant-field-fanout]]`) — wire the done variant to the live artifact path; do not re-build it.

**Dependencies:** 3b-3 (`prOutput` available + linkable), 3b-4 (developer-role actions), 3.27 (PR/output ARP variant — reconcile), 3.31 (`githubRef.ts`, `PrStateBadge`, `SafeUnifiedDiffRenderer`/`parseUnifiedDiff` — reused unforked; `[[githubref-branchurl-dot-traversal]]` guard retained), 3.23/3.24 (accept/reject endpoints).

**AC-shape reference:**
- A `prOutput` review panel: summary + PR link (`PrStateBadge` + `githubRef` URL hardening) + unified diff (`SafeUnifiedDiffRenderer`/`parseUnifiedDiff`), diff sourced from the artifact payload / `diffReference`. Decide render location (artifact-viewer route vs inline review panel on the detail page) in story design.
- Accept / reject fire and transition the run; the post-decision success summary + kept-alive announcement behavior in `WorkflowDecisionBar` is preserved (bar stays mounted through the state flip).
- Respect `[[frontend-react-refresh-no-fn-exports]]` (helpers in `.ts`, not `.tsx`) and `exactOptionalPropertyTypes` when extending ArtifactView variants.
- Tests: a live `prOutput` renders as PR link + diff (not `error`); accept/reject vitest + a manual run after `mvn package`.

**Design source:** `docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md` (sub-project #3, Story B).

### Story 3b-6: `implementationPlan` Review Rendering + Plan-Phase Accept/Reject/Takeover

As a developer reviewer,
I want the `implementationPlan` artifact's ordered steps rendered for review, with accept / reject (developer taxonomy) / takeover wired end-to-end for the plan phase,
So that the plan phase of the two-dispatch flow is reviewable and actionable — the path that needs no `github_pr` link (unlike a `prOutput` accept).

**Current behavior to change:** the `implementationPlan` review rendering is not surfaced on the live review path. **Reconcile against done story 3.26** (impl-plan ARP variant renderer): wire the existing variant to the live artifact path (same `isArtifactView` reachability reconciliation as 3b-5/3.27); do not re-build it.

**Dependencies:** 3b-4 (developer-role actions), 3b-3 (`implementationPlan` available + linkable), 3.26 (impl-plan ARP variant — reconcile), 3.21 (`rejectImplementation` + developer rejection taxonomy `incorrect_approach`/`incomplete_implementation`/`quality_issue`/`breaks_existing_functionality`/`out_of_scope`), 3.22/3.25 (takeover service + endpoint + preserved-PR affordance).

**AC-shape reference:**
- Render the `implementationPlan` ordered steps for review (reuse the 3.26 variant on the live path).
- Plan-phase accept / reject (developer taxonomy) / takeover wired end-to-end to the existing endpoints, including the post-decision success summary and the takeover preserved-PR affordance. Plan-phase decisions need no `github_pr` link.
- Tests: the `implementationPlan` renders its steps (not `error`); plan-phase accept/reject/takeover vitest + a manual run after `mvn package`.

**Design source:** `docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md` (sub-project #3, Story C; takeover folds in).

