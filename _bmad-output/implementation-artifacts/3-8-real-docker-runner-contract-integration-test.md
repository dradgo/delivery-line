# Story 3.8: Real Docker Runner Contract Integration Test

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- Created 2026-06-05 via bmad-create-story. Story pulled out-of-order from the deferred epic-3b
     slice (same as 3-7), at user request. The runner-image side (entrypoints + runner.mjs) ALREADY
     implements every artifact stage AND every --simulate-failure mode; the build-invalid contract
     test and the per-stage artifact emission exist. This story is therefore an INTEGRATION-TEST
     authoring story: it wires the existing real images to the existing RunnerContractValidator +
     broker + persistence through a new Testcontainers/@SpringBootTest IT. It does NOT add product
     code paths. Read the "Central Reconciliations" block before touching anything — several epic
     ACs reference stale story numbers / not-yet-built CI jobs. -->

## Story

As a runner-infrastructure developer,
I want a Testcontainers-based, broker-driven integration test (`RealRunnerContractIT`) that runs the **real** Codex + Claude runner images end-to-end against the `RunnerContractValidator` (story 1.6), the `DockerRunnerAdapter` + `RunnerBroker` (stories 3.1/3.2), and the runner-execution persistence + log-capture + secret-scan pipeline (stories 3.5/3.6),
so that the foundation contract (story 1.23) is exercised against real runner images — not mocks — and any drift between `deliveryline-runner-contracts`, `DockerRunnerAdapter`, and the actual runner image surfaces before it reaches a pilot.

---

## ⚠️ READ FIRST — Central Reconciliations (the epic ACs describe an end-state; here is what is actually true in this repo today)

> The epic text for story 3.8 was written at planning time and cross-references several story numbers and CI jobs that have since shifted. **The implementing dev MUST honor these reconciliations over the literal epic AC wording.**

1. **The runner-image side is DONE — this story adds a TEST, not product code (the headline).** Stories 3.3/3.4 already shipped: `runners/codex/entrypoint.sh` + `runners/claude/entrypoint.sh` + `runners/{codex,claude}/lib/runner.mjs` already (a) resolve the stage from `--stage=` / `DELIVERYLINE_RUNNER_STAGE` / bundle field, (b) emit the correct `spec` / `implementationPlan` / `prOutput` artifact shape per stage, and (c) implement `--simulate-failure={timeout|crash|contract_violation}` gated behind `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true` (timeout = `sleep 86400`; crash = `exit 50` no result file; contract_violation = `runner.mjs build-invalid` writes `{schemaVersion: 2}` then `exit 0`). **Do NOT modify the runner images, entrypoints, or mock CLIs.** If you find yourself editing `runners/**`, stop — the contract is already there; you are only consuming it. (See `runners/RUNNER_CONTRACT.md` §exit-codes and §simulate-failure.)

2. **The CI-tier story is `3.34`, not `3.28` (Decision D1).** AC1 and AC10 say "the dedicated CI tier (story 3.28)" / "runner-contract-real job ... depends on runner-image-build from story 3.28." In **this** repo's numbering, the CI-tier story is **3.34 — "CI Tier — Real Docker Runner Image Build + Compatibility Checks"** (`epic-03` line 675), whose AC explicitly says it builds Codex+Claude via `runner-image-build` and runs `runner-contract-real` `needs: runner-image-build`, and whose AC6 says the foundation gate widens "per story 3.8 AC9." **3.34 is currently `backlog`** (its CI jobs do NOT exist yet). The `3.28` references in 3.8's ACs are stale planning numbers. → **Treat the GitHub Actions `runner-image-build` + `runner-contract-real` job wiring as OWNED by story 3.34, and DEFER it.** This story delivers the IT itself, runnable via the existing `-Pdocker-runner-it` profile; 3.34 later adds the dedicated CI job that invokes it. (This mirrors how 3-7 created the `docker-runner-it` Maven profile but left the dedicated CI job to a later story.)

3. **The test MUST carry `@Tag("docker-runner-it")` in addition to `@Tag("real-runner-contract")` (Decision D2 — critical, or you break every PR).** AC1 says tag it `@Tag("real-runner-contract")` only. But this repo's `deliveryline-backend/pom.xml` Failsafe default config **excludes only `@Tag("docker-runner-it")`** from the standard `verify` tier (the `backend-contract-tests` CI job runs plain `./mvnw -pl deliveryline-backend -am verify`). A `*IT.java` tagged only `real-runner-contract` would be **included by Failsafe on every PR**, forcing every PR to `docker build` both runner images + boot Postgres — exactly the cost the tiering exists to avoid. → **Annotate the class with BOTH `@Tag("docker-runner-it")` AND `@Tag("real-runner-contract")`, plus `@EnabledIfDockerAvailable`.** `docker-runner-it` keeps it out of the default tier; `real-runner-contract` is the semantic tag the future 3.34 job will select on; `@EnabledIfDockerAvailable` makes it a graceful skip (not a failure) on Docker-less machines. Verify locally with `mvn -Pdocker-runner-it verify -Dit.test=RealRunnerContractIT` (the `docker-runner-it` profile re-includes the tag — see `pom.xml` `docker-runner-it` profile).

4. **File path / package (Decision D3).** AC1 names `backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java`. The module is **`deliveryline-backend`** (not `backend`), and existing runner ITs live under `org/dradgo/adapters/runner/...`. → **Create it at `deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java`** (new `integration.runners` package is fine and matches the epic intent) **OR** co-locate under `org/dradgo/adapters/runner/` to sit beside the conformance ITs. Either is acceptable; prefer the epic's `integration/runners` package for discoverability. Whichever you pick, the filename must end in `IT` so Failsafe (not Surefire) owns it ([[springboot-testcontainers-test-must-be-IT]]).

5. **This test is BROKER-DRIVEN (`@SpringBootTest` + Postgres), not raw-DockerClient (Decision D4 — the architectural choice).** AC5/AC6/AC7/AC8/AC12 demand assertions on the `runner_executions` DB row (status + `failure_category`), the emitted `workflow_events`, the redacted log file at `{DELIVERYLINE_HOME}/runner-logs/{rexId}/`, and mock-vs-real parity. Those artifacts only exist when the **backend** (`RunnerBroker` → `DockerRunnerAdapter` → `RunnerLogCaptureService` / `RunnerSecretScanService` / `RunnerExecutionService`) drives the container — not when a test pokes `DockerClient` directly. → **Model `RealRunnerContractIT` on `BrokerDrivenDockerLifecycleITSupport` (story 3.2a)**, which already gives you `@SpringBootTest` + `@Import(TestcontainersConfiguration)` (per-context Postgres) + `@ActiveProfiles({"test","linear-mock","runners.docker"})` + real `RunnerBroker`/`DockerRunnerAdapter`/`LocalRunnerWorkspaceStore`. The ONLY substantive difference is: launch containers from the **real `deliveryline/codex-runner` / `deliveryline/claude-runner` images** (built once in `@BeforeAll` via the `ProcessBuilder docker build … --build-arg INSTALL_*_CLI=false` pattern from the conformance ITs) instead of `alpine:3.20`. **Do not reinvent the Postgres/broker/workspace scaffolding — extend the existing support class or copy its proven shape.**

6. **AC8 lifecycle is already alpine-proven — do NOT duplicate the full JVM-restart recovery here (Decision D5).** Timeout / heartbeat-staleness / broker-restart-recovery / dangling-cleanup are exhaustively covered by `DockerRunnerLifecycle{Timeout,Heartbeat,Recovery}IT` + `DockerRunnerDanglingContainerCleanupIT` (story 3.2a) against `alpine:3.20`, and that behavior is **image-agnostic** (the broker kills any container the same way). → For 3.8, cover **timeout** against the *real* image via `--simulate-failure=timeout` (this proves the real entrypoint's `sleep` + the broker's kill path interoperate), and reference the 3.2a suite for the rest. Re-running full JVM-restart recovery on the heavy real images adds cost without new signal. Note this scoping in the story's File List / Completion Notes.

7. **Result filename is `runner-result.v1.json`, not `runner-result.json` (Decision D6).** AC4 says "reads the produced `runner-result.json`." The entrypoints actually write `$OUTPUT_DIR/runner-result.v1.json` (`runners/{codex,claude}/entrypoint.sh`). The backend's `DockerRunnerAdapter.tryReadResult(...)` already reads from the workspace and mirrors to scratch — **prefer asserting via `adapter.tryReadResult(rexId)` + `RunnerContractValidator.validate(RUNNER_RESULT, bytes, ctx)`** rather than hard-coding a filename, so you stay decoupled from the on-disk name.

8. **`--simulate-failure` requires `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true` in the container env (Decision D7).** Production images refuse the flag (`exit 2`). The IT must inject that env var into the runner container for the failure scenarios. Determine how the broker/adapter passes through extra runner env (it injects resolved secrets + stage env today — see `DockerRunnerAdapter.dispatch` env assembly); you may need a test-only execution-constraint or env hook. If the adapter has no seam to add an arbitrary container env var, that is the one small **product seam** this story may legitimately add (a test-scoped passthrough) — but check first; do not assume.

---

## Acceptance Criteria

> Carried from epic-03 story 3.8, **reconciled** per the block above. Where an AC's literal wording conflicts with a reconciliation, the reconciliation wins and is called out inline.

1. **`RealRunnerContractIT` exists and is correctly tiered.** Create `deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java` (D3). It is a `@SpringBootTest` broker-driven IT (D5) that, in `@BeforeAll`, builds the Codex image from `runners/codex/Dockerfile` and the Claude image from `runners/claude/Dockerfile` (both `--build-arg INSTALL_*_CLI=false`, the deterministic mock-CLI build) and boots a Postgres container via `@Import(TestcontainersConfiguration.class)`. The class is annotated `@Tag("docker-runner-it")` **and** `@Tag("real-runner-contract")` **and** `@EnabledIfDockerAvailable` (D2) so it is excluded from the fast tier + the default Failsafe `verify` tier and runs only under `-Pdocker-runner-it` (and, later, story 3.34's `runner-contract-real` job).

2. **Per-runner-kind scenario coverage (Codex, Claude):** each runner kind covers — happy-path producing a valid `spec` result, happy-path producing a valid `implementationPlan` result, happy-path producing a valid `prOutput` result, plus deliberate-failure scenarios `--simulate-failure=timeout`, `--simulate-failure=crash`, `--simulate-failure=contract_violation`. The failure scenarios inject `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true` (D7). Parameterize over `{runnerKind} × {stage}` and over the failure modes to keep the matrix readable.

3. **Foundation fixtures are the inputs.** Reuse the foundation context-bundle fixtures (`deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1*.valid.json`) as the runner input bundles — proving the same fixture data that drives the contract suite drives real runner execution end-to-end. Do not hand-author new bundle JSON if a valid fixture already fits the stage.

4. **Schema conformance on every happy path.** For each happy-path scenario, read the produced runner result (via `adapter.tryReadResult(rexId)` — the file is `runner-result.v1.json`, D6) and validate it with `RunnerContractValidator.validate(ValidationTarget.RUNNER_RESULT, bytes, context)`; assert `ValidationResult.valid() == true` and the `artifactType` matches the dispatched stage. A schema-invalid result fails the test (and, in the `contract_violation` scenario, is the *expected* outcome → assert `runner_contract_violation`).

5. **Mock-vs-real parity.** For matched scenarios, assert the observable outcome of the `RunnerAdapter` port is identical between `MockRunnerAdapter` (story 1.13, `@Profile("!runners.docker")`) and `DockerRunnerAdapter` (`@Profile("runners.docker")`): same terminal `runner_executions.status` (`completed` / `failed` / `timed_out`), same `failure_category` (`FailureCategory`), and same emitted `workflow_events` (event types + actor types match; `actor_identity` + timestamps may differ). Map each mock `Behaviour` (HAPPY/TIMEOUT/CRASH/CONTRACT_VIOLATION) to its real counterpart. See Dev Notes for the recommended way to obtain both adapters' outcomes without two full contexts.

6. **Secrets pipeline holds end-to-end.** Inject a mock provider secret via env (e.g. `LINEAR_API_KEY=test-mock-key-not-real`, or the runner-kind's configured key per `RunnerSecretsService`) and assert the post-execution `RunnerSecretScanService.scanWorkspace(...)` finds **zero** leaks (`ScanOutcome.leakDetected() == false`) for the happy paths.

7. **Logs captured + redacted.** Assert each execution yields a redacted log file under `{DELIVERYLINE_HOME}/runner-logs/{runnerExecutionId}/` (`runner.stdout` / `runner.stderr` via `LocalRunnerLogStore`) with an appropriate `DataClassification`, and that a deliberate auth-header leak injected into the runner's stdout (e.g. an `Authorization: Bearer …` sentinel) is redacted before persistence (`RedactionPolicyService` second pass; `redaction_count > 0`). NB: entrypoint diagnostic `log()` lines go to the **container's** stderr stream, not the mounted `runner.stderr` file ([[runner-entrypoint-logs-to-container-stderr]]) — if you assert on entrypoint diagnostics, capture container logs via `logContainerCmd`, not the mount file.

8. **Lifecycle — timeout against the real image (D5).** `--simulate-failure=timeout` on the real image is killed by the broker timeout path and the row lands `timed_out` with `failure_category = runner_timeout`. Heartbeat-staleness, broker-restart recovery, and dangling-cleanup are already proven image-agnostically by the 3.2a `DockerRunnerLifecycle*IT` suite against `alpine:3.20`; reference them rather than re-running them on the heavy images. Note the deliberate scope boundary in Completion Notes.

9. **Foundation-gate widening (Decision D8 — reconcile carefully).** AC literally says "the foundation gate now includes 'real-runner-contract test green on the branch.'" But the per-PR foundation gate is the lightweight `FoundationGateVerificationTest` aggregator (`@Tag("foundation-gate")`, run by `-Pfoundation-gate`), which does **not** build Docker images. Folding a runner-image-building, Postgres-booting test into the every-PR aggregator would defeat the tiering. → **Realize the "widening" as the dedicated `runner-contract-real` CI job owned by story 3.34**, which `needs: runner-image-build` and is wired as a required check there. In THIS story, (a) ensure `RealRunnerContractIT` runs green under `-Pdocker-runner-it` on Linux/Docker, and (b) leave a documented breadcrumb (story 3.34 + deferred-work.md) that the gate-wiring is 3.34's deliverable. Do **not** add the real-runner test to `FoundationGateVerificationTest`. (Raise as Open Question OQ-1 if Alex wants it wired into a gate now.)

10. **Dedicated CI tier (DEFER to 3.34, per D1).** AC10 places this in a `runner-contract-real` job that `needs: runner-image-build`, on `ubuntu-latest`. Those jobs are story 3.34's deliverables and do not exist yet. → **Defer the GitHub Actions wiring to 3.34.** This story's CI obligation is satisfied by the test running correctly under `-Pdocker-runner-it` (verified locally on WSL2/Linux + Docker per [[wsl-linux-ci-reproduction]]).

11. **Test isolation.** Each scenario uses a fresh per-test `DELIVERYLINE_HOME` (`@TempDir`) and a fresh Postgres schema (per-context Testcontainers via `TestcontainersConfiguration` — each `@SpringBootTest` context gets its own DB; clean FK-ordered deletes in `@AfterEach` as `BrokerDrivenDockerLifecycleITSupport` does). No cross-test state pollution. Built runner images are shared read-only across scenarios (build once in `@BeforeAll`); per-scenario state lives in the workspace + DB, both reset between tests.

12. **Failure diagnostics.** When a scenario fails, the test surfaces enough to debug without local repro: the failed runner's (already-redacted) logs, the `runner_executions` row (status + `failure_category`), the `workflow_events` emitted during the run, and the offending fixture path. Use AssertJ `as(...)`/`describedAs(...)` descriptions and/or a `@AfterEach`-on-failure dump so a CI log alone is actionable.

---

## Tasks / Subtasks

- [x] **Task 1 — Stand up the broker-driven real-image IT skeleton (AC: 1, 11)**
  - [x] Create `deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java` (D3), `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` + `@ActiveProfiles({"test","linear-mock","runners.docker"})` + `@Tag("docker-runner-it")` + `@Tag("real-runner-contract")` + `@EnabledIfDockerAvailable` (D2/D4).
  - [x] In `@BeforeAll`, build both real images once via `ProcessBuilder docker build -f runners/codex/Dockerfile --build-arg INSTALL_CODEX_CLI=false -t deliveryline/codex-runner:real-contract-it .` (and the Claude mirror, `INSTALL_CLAUDE_CLI=false`). Reuse the `locateRepoRoot()` + build-output-capture + `assertThat(exit).isZero()` pattern from `CodexRunnerImageConformanceIT` (do not reinvent).
  - [x] Reuse `BrokerDrivenDockerLifecycleITSupport` scaffolding (seed/cleanup/await helpers + per-context Postgres). Prefer extending or factoring a shared support base; if extending, parameterize the runner image (alpine vs real) rather than copy-pasting the DB plumbing.
  - [x] `@TempDir` per-test `DELIVERYLINE_HOME`; FK-ordered `@AfterEach` cleanup of `recovery_actions` → `runner_executions` → `workflow_runs` (+ `artifacts`/`workflow_events` as the support class does — mind [[workflow-read-endpoints-test-isolation-flake]] ordering).

- [x] **Task 2 — Happy-path × artifact-variant coverage (AC: 2, 3, 4)**
  - [x] Parameterize over `{Codex, Claude} × {spec, implementationPlan, prOutput}`. Feed the matching `context-bundle.v1*.valid.json` foundation fixture as the input bundle (AC3).
  - [x] Dispatch through the broker so a real `runner_executions` row + `DockerRunnerAdapter` container launch happen; await terminal state.
  - [x] Assert `runner_executions.status = completed`; read the result via `adapter.tryReadResult(rexId)`; validate with `RunnerContractValidator.validate(RUNNER_RESULT, bytes, ctx)` → `valid()==true`; assert `artifactType` == dispatched stage (D6).

- [x] **Task 3 — Failure-injection coverage (AC: 2, 4, 8)**
  - [x] For `crash`: inject `--simulate-failure=crash` + `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true` (D7); assert exit→`failed`, `failure_category = runner_crash`, no result file.
  - [x] For `contract_violation`: assert a result file IS produced but `RunnerContractValidator` rejects it (`schemaVersion: 2`), and the row lands `failed` / `failure_category = runner_contract_violation`.
  - [x] For `timeout`: configure a short runner timeout; assert the broker kills the `sleep 86400` container and the row lands `timed_out` / `failure_category = runner_timeout` (AC8, real-image timeout proof).

- [x] **Task 4 — Mock-vs-real parity (AC: 5)**
  - [x] For each matched scenario, obtain the `MockRunnerAdapter` outcome (HAPPY→completed, CRASH→failed/RUNNER_CRASH, TIMEOUT→timed_out, CONTRACT_VIOLATION→failed/RUNNER_CONTRACT_VIOLATION) and assert it equals the real outcome: same terminal status, same `failure_category`, same `workflow_events` (event types + actor types). See Dev Notes for the single-context approach (drive the mock adapter directly as a bean / via a focused unit comparison rather than booting a second `!runners.docker` context).

- [x] **Task 5 — Secrets + logs + redaction end-to-end (AC: 6, 7)**
  - [x] Inject a mock provider secret env; after a happy run assert `RunnerSecretScanService.scanWorkspace(...).leakDetected()==false`.
  - [x] Assert a redacted log file exists at `{DELIVERYLINE_HOME}/runner-logs/{rexId}/` with a sane `DataClassification`; inject an `Authorization: Bearer <sentinel>` leak into runner stdout and assert it is redacted before persistence (`redaction_count > 0`, sentinel absent from the persisted bytes). Capture container logs via `logContainerCmd` if asserting entrypoint diagnostics ([[runner-entrypoint-logs-to-container-stderr]]).

- [x] **Task 6 — Failure diagnostics + scope notes (AC: 12, 9, 10)**
  - [x] Add rich AssertJ descriptions / an on-failure dump (row + events + log path + fixture path).
  - [x] Add deferred-work.md breadcrumbs: (a) `runner-image-build` + `runner-contract-real` CI jobs + foundation-gate wiring are story 3.34's deliverables (D1/D8/D9/D10); (b) full JVM-restart recovery on real images intentionally not duplicated (D5).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] This is a **test-only** story — no new production service entry/exit points are introduced. The cross-cutting logging surfaces (broker dispatch, adapter classify-exit, log-capture, secret-scan) already exist and are pinned by 3.1/3.2/3.5/3.6 logging-contract tests. **Do not add production log statements.** Instead, the test itself must *assert* that the existing INFO/WARN/ERROR lifecycle logs fire on the real-runner path the same way they do on the mock/alpine path — fold these into the parity assertions (AC5) using a logback `ListAppender` or `OutputCaptureExtension` where a branch (e.g. timeout WARN, contract-violation WARN, leak ERROR/quarantine) is newly exercised by a real container.
  - [x] If Task 6's diagnostic dump emits anything, it must use parameterized SLF4J (never string concatenation) and must never echo the injected secret sentinel or raw payload bytes — route through the existing redaction path.

### Review Findings

> Code review 2026-06-05 (bmad-code-review, 3 adversarial layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor). Diff: new `RealRunnerContractIT.java` (830 lines) + `deferred-work.md`. All 18 scenarios green; no production code changed (D7 "no product seam" verified true). Findings below are test-quality/robustness — none block the green build.
>
> **Resolution (2026-06-05):** the 1 decision-needed (AC5 parity → chose to implement a real guard) + all 5 patches were applied to `RealRunnerContractIT.java`; test sources recompile green (`mvnw -pl deliveryline-backend -am test-compile` → BUILD SUCCESS). The 2 deferred items are logged in `deferred-work.md`. NB: the heavy `-Pdocker-runner-it` suite was NOT re-run here (no Docker in the review env) — re-verify on WSL2/Linux+Docker before relying on it ([[wsl-linux-ci-reproduction]]).

- [x] [Review][Decision] **AC5 mock-vs-real parity test is vacuous** — `mockBehaviourMapsToRealOutcome` (RealRunnerContractIT.java:374-396) asserts only its own `@CsvSource` literals against themselves (`status.isNotBlank()`, `eventType.startsWith("runner.")`, `failureCategory` null/not-blank). It never references `MockRunnerAdapter` or `MockRunnerScenarioRegistry`, so it cannot catch the mock/real drift AC5 demands — its own comment ("fails if anyone edits the expected mapping without updating both adapters") is false. Equivalence DOES hold by inspection (the real scenarios assert these same triples via `assertTerminal`, and the CSV values match the registry mapping), but the "guard" gives false confidence. Flagged independently by all three layers. **Decision:** accept the documented lighter approach (and rename/comment the test honestly so it doesn't read as a real cross-adapter assertion), OR implement the Dev-Notes-suggested direct `MockRunnerAdapter` drive (it is a plain bean, no Docker dep) for a machine-checked parity guard.
- [x] [Review][Patch] recoverHandle()/pollActiveExecutions() return values discarded on 6 scenarios — no `.isPresent()` / `processed==1` guard; an unprimed handle degrades to a misleading status-mismatch instead of a precise diagnostic (timeout + AC7 paths already do this) [deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java:263-264, 275-276, 286-287, 301-302, 315-316, 416-417]
- [x] [Review][Patch] makeWorldWritable/makeRunnerReadable swallow a real IOException on POSIX (Linux CI) hosts — catch should be `UnsupportedOperationException` only; a genuine chmod failure on the target platform is currently hidden, leaving the container unable to read/write its bind mount → opaque failure [deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java:789-803]
- [x] [Review][Patch] `@EnumSource(RunnerKind.class)` + `kind == CODEX ? CODEX_IMAGE : CLAUDE_IMAGE` silently maps any non-CODEX kind to the Claude image with mismatched labels; a future third RunnerKind constant would be mis-covered without failing — make image resolution explicit/guarded [deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java:258, 521]
- [x] [Review][Patch] `@BeforeAll` docker build uses `process.waitFor()` with no timeout — a stalled build hangs the whole tier indefinitely with no diagnostic; add a bounded `waitFor(timeout, unit)` + `destroyForcibly()` [deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java:209-212]
- [x] [Review][Patch] AC7 redaction assertion checks `doesNotContain("Bearer redaction-sentinel-<n>")` — the generic `Bearer ` prefix weakens the check; assert the unique secret tail absent so a partial-redaction false-negative cannot slip through [deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java:435, 490]
- [x] [Review][Defer] `awaitNotRunning(..., 20s)` is hardcoded vs the configured broker graceful-stop timeout [deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java:356] — deferred, flake-hardening; matches the 3.2a pattern, only a risk if graceful-stop ≥ ~20s or SIGKILL delivery is slow (Windows vs WSL2)
- [x] [Review][Defer] Dispatch-side container-env assembly (`DockerRunnerAdapter.dispatch`) is uncovered — the IT launches via raw DockerClient per D7 (no product seam) so the production env-assembly path used in real dispatch is not exercised here — deferred, accepted per D7; note coverage gap for a future story

## Dev Notes

### What already exists (reuse — do NOT reinvent)

**Test infrastructure (all under `deliveryline-backend/src/test/java/`):**
- `org/dradgo/TestcontainersConfiguration.java` — `@TestConfiguration`, `@Bean @ServiceConnection PostgreSQLContainer<>("postgres:17.2")`, **per-context** (not shared-static) Postgres. Import this for the DB.
- `org/dradgo/adapters/runner/EnabledIfDockerAvailable.java` — JUnit5 `ExecutionCondition`; `@EnabledIfDockerAvailable` → graceful skip when Docker absent (cached probe).
- `org/dradgo/adapters/runner/lifecycle/BrokerDrivenDockerLifecycleITSupport.java` — **the blueprint** (story 3.2a). `@SpringBootTest` + `@Import(TestcontainersConfiguration)` + `@ActiveProfiles({"test","linear-mock","runners.docker"})` + `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`. Exposes: `seedWorkflowRun(currentState)`, `seedRunningRunner(runId, stage, lastActivitySecondsAgo, timeoutSecondsFromNow)`, `launchLabeledContainer(rex, runId, stage, cmd)` (5 `deliveryline.*` labels), `prepareWorkspace(rex)`, `logsDir/outputDir(rex)`, `isRunning/awaitNotRunning(containerId,timeout)`, JDBC `runnerStatus/lastActivityAt/timeoutAt(rex)`, `eventCount(runId,type)`, FK-ordered `@AfterEach` cleanup. Uses `alpine:3.20` (`TEST_IMAGE`) — **the only thing 3.8 changes is the image**.
- `org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java` + `ClaudeRunnerImageConformanceIT.java` — the **image-build-in-`@BeforeAll`** pattern: `ProcessBuilder("docker","build","-f","runners/codex/Dockerfile","--build-arg","INSTALL_CODEX_CLI=false","--build-arg","IMAGE_VERSION=…","-t","deliveryline/codex-runner:…",".")` from repo root, capture output, assert exit 0. Also the Windows-drive-letter bind-mount conversion (`C:\…` → `/c/…`) if you ever bind directly. **Existing happy-path artifact-variant coverage lives here** (`producesSchemaConformantResultPerArtifactVariant`), parameterized over `spec-investigation→spec`, `implementation-plan→implementationPlan`, `pr-output→prOutput`; these do NOT cover `--simulate-failure` — that gap is 3.8's net-new contribution at the broker/persistence layer.
- `org/dradgo/adapters/observability/ElkPipelineRoundTripIT.java` — example of Testcontainers `GenericContainer` + `@Tag("docker-runner-it")` + the `docker-runner-it` Maven profile created by 3-7.

**Contract + adapters + persistence (production, under `deliveryline-backend/src/main/java/` unless noted):**
- `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/RunnerContractValidator.java` — `validate(ValidationTarget target, byte[] payload, ValidationContext context) → ValidationResult(boolean valid, List<ValidationError>)`; `validateFixture(target, Path[, ctx])`. `ValidationTarget.RUNNER_RESULT` / `CONTEXT_BUNDLE`. Knows `spec`/`implementationPlan`/`prOutput`. ⚠️ [[runner-contracts-schema-stale-in-m2]] — always build the backend with `-am` so the freshest `deliveryline-runner-contracts` jar is on the classpath.
- `org/dradgo/application/runner/spi/RunnerAdapter.java` — port: `RunnerDispatchAck dispatch(RunnerDispatchRequest)`, `RunnerPollStatus poll(String rexId)`, `Optional<byte[]> tryReadResult(String rexId)`, `void cancel(String rexId)`. `RunnerPollStatus` is sealed: `Running / HeartbeatTouched / Completed / Failed(FailureCategory) / Unknown`.
- `org/dradgo/adapters/runner/DockerRunnerAdapter.java` — `@Profile("runners.docker")`; ctor deps: `RunnerScratchStore, RunnerWorkspaceStore, DockerEngineGateway, RunnerProperties, RunnerSecretsService, RunnerLogCaptureService, RunnerExecutionService, ObjectProvider<RepositoryWorkspaceService>`. `dispatch()` assembles container env (resolved secrets + stage) — **this is where you confirm/extend the seam to pass `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE` + `--simulate-failure` for D7/Task 3.** `classifyExited()` captures logs + checks the result file.
- `org/dradgo/adapters/runner/MockRunnerAdapter.java` — `@Profile("!runners.docker")`; `Behaviour { HAPPY, TIMEOUT, CRASH, CONTRACT_VIOLATION, NON_ZERO_EXIT, LATE_RESULT, DUPLICATE_RESULT, MALFORMED_OUTPUT }`; scenarios from `MockRunnerScenarioRegistry`. Use for parity (AC5).
- `org/dradgo/application/runner/RunnerBroker.java` — drives dispatch + idempotent reservation; the entry point the IT should call (don't poke the adapter raw for the happy/parity paths).
- Persistence: `org/dradgo/adapters/persistence/entity/RunnerExecutionEntity.java` (`@Table("runner_executions")`; `status`, `failure_category`, story-3.6 `raw_output_*`/`redaction_count` columns). `org/dradgo/domain/registry/RunnerExecutionStatus.java` = `PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT, ORPHANED`. `org/dradgo/domain/registry/FailureCategory.java` = `RUNNER_TIMEOUT, RUNNER_CRASH, RUNNER_CONTRACT_VIOLATION, RUNNER_NON_ZERO_EXIT, RUNNER_LATE_RESULT, RUNNER_DUPLICATE_RESULT, RUNNER_MALFORMED_OUTPUT, RUNNER_SECRET_LEAK, ORPHAN`.
- Secrets/logs/redaction: `RunnerSecretsService.resolveSecretsForRunner(kind,stage,workflowRunId)`; `RunnerSecretScanService.scanWorkspace(...) → ScanOutcome(leakDetected, leakedFile, detectedCategories)`; `RunnerLogCaptureService.captureLogs(...) → CapturedLogs(referencePath, byteSize, classification, redactionCount)`; `adapters/files/LocalRunnerLogStore.java` writes `{deliveryline.home}/runner-logs/{rexId}/runner.stdout|stderr` (owner-only perms, temp+atomic-rename); `application/security/RedactionPolicyService.redact(payload, claimedClassification)`; `DataClassification = LOCAL_ONLY, SHAREABLE_REDACTED, SHAREABLE_FULL, DERIVED_PUBLIC_SAFE`.

**Fixtures (inputs for AC3):**
- `deliveryline-runner-contracts/src/test/resources/fixtures/valid/` — `context-bundle.v1.valid.json`, `context-bundle.v1.spec-investigation-bootstrap.valid.json`, `context-bundle.v1.spec-investigation-repo-context.valid.json`, and `runner-result.v1.{spec,implementation-plan,pr-output}.valid.json` (golden result shapes for cross-check). `fixture-expectations.json` enumerates the invalid set.
- `deliveryline-backend/src/test/resources/fixture-event-streams/` — full workflow-event histories (`happy-path-success.json`, etc.) with `.md` sidecars (story 1.23). Useful for the parity event-type expectations.

**Runner images (consume, do NOT edit — D1):**
- `runners/codex/Dockerfile` + `runners/claude/Dockerfile` (build-arg `INSTALL_{CODEX,CLAUDE}_CLI=false` bakes the deterministic mock CLI). `runners/{codex,claude}/entrypoint.sh` (stage resolution `--stage=`>`DELIVERYLINE_RUNNER_STAGE`>bundle; `--simulate-failure` parsing + gating at `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE`; writes `$OUTPUT_DIR/runner-result.v1.json`). `runners/{codex,claude}/lib/runner.mjs` (`commandBuild` per-stage artifact emission; `commandBuildInvalid` writes `{schemaVersion:2}` for `contract_violation`). `runners/RUNNER_CONTRACT.md` (exit codes: 0 ok, 2 usage/gate, 30 CLI non-zero, 40 build-failed, 50 simulate-crash; the simulate-failure + stage→artifactType tables).

### Mock-vs-real parity without two contexts (AC5 — implementation hint)

Booting a second `@SpringBootTest` with `!runners.docker` just to read the mock outcome is heavy and profile-fragile. Prefer one of:
- Instantiate/inject `MockRunnerAdapter` directly (it is a plain bean with no Docker dep) and drive its `dispatch/poll` for the matched scenario in the same test, comparing the resulting `RunnerPollStatus` → terminal-status/`FailureCategory` mapping against the real adapter's persisted row; or
- Assert against the **documented contract table** (the `Behaviour`→status/category mapping is fixed and unit-tested in `MockRunnerAdapter`'s own tests) and pin the equivalence in a focused comparison, citing the mock test as the source of truth.
Either way, the parity assertion is on: terminal `runner_executions.status`, `failure_category`, and the set of emitted `workflow_events` (types + actor types; ignore `actor_identity`/timestamps).

### Project Structure Notes

- Module is **`deliveryline-backend`**; runner-contracts is **`deliveryline-runner-contracts`** (Maven `org.dradgo:deliveryline-runner-contracts:0.0.1-SNAPSHOT`). Root reactor pom + per-module poms.
- Test tiers (from `deliveryline-backend/pom.xml`): **Surefire** (fast, Win+Linux) excludes `*IT.java`/`*ContractTest.java` + tags `architecture,integration,contract,known-failure,foundation-gate`. **Failsafe** (`verify`, Linux) includes `*IT.java` but **excludes tags `known-failure,docker-runner-it`** → that exclusion is why `RealRunnerContractIT` MUST carry `@Tag("docker-runner-it")` (D2). The **`docker-runner-it` profile** (added by 3-7) re-includes the tag; run `mvn -Pdocker-runner-it verify -Dit.test=RealRunnerContractIT`. The **`foundation-gate` profile** runs `@Tag("foundation-gate")` only — do not add this test there (D9).
- CI (`.github/workflows/ci.yml`): `backend-contract-tests` runs plain `verify` (so it would catch a mis-tagged test — see D2); `runner-image-compat` builds both runner images with the mock CLIs + re-validates fixtures; there is **no `runner-contract-real` job yet** (story 3.34, D1/D10). All Docker tiers are `ubuntu-latest`.

### Environment / verification reality (read before claiming green)

- **RTK corrupts only the Bash tool** ([[rtk-hook-only-matches-bash]]) — run Maven via PowerShell or native file tools, not the Bash wrapper, or grep/build output gets mangled.
- **Local green ≠ CI green** ([[verify-ci-fixes-in-clean-env]]). This is a Docker + Testcontainers + real-image-build test → verify on **WSL2 Ubuntu** ([[wsl-linux-ci-reproduction]]) before claiming done: `mvn -Pdocker-runner-it -pl deliveryline-backend -am verify -Dit.test=RealRunnerContractIT`. `-am` keeps the runner-contracts jar fresh ([[runner-contracts-schema-stale-in-m2]]).
- Postgres image is per-context (`@ServiceConnection`); the build also produces two real images (`deliveryline/{codex,claude}-runner:real-contract-it`) — first run is slow (image build). Build once in `@BeforeAll`.
- `@SpringBootTest`+Testcontainers ⇒ name it `*IT` so Failsafe owns it and the Windows fast tier excludes it ([[springboot-testcontainers-test-must-be-IT]]).
- No Claude co-author trailer on commits in this repo ([[commit-no-claude-coauthor]]).

### Previous-story intelligence

- **3-7 (ELK, immediately prior, also pulled from epic-3b):** created the `docker-runner-it` Maven profile this story relies on; demonstrated the "READ FIRST reconciliations" pattern (stale ADR numbers, single-compose, profile names) — the same care applies here to the 3.28→3.34 drift. Also: a fast-tier test that *looks* like it validates a real engine can mask a real defect (the Logstash JRuby vs java.util.regex episode) — analogously, the existing conformance ITs cover happy paths but NOT the failure/parity/persistence surface; don't assume "conformance ITs pass" means "contract is fully exercised."
- **3-2a (lifecycle hardening):** authored `BrokerDrivenDockerLifecycleITSupport` + the `DockerRunnerLifecycle*IT` suite this story extends; established that lifecycle behavior is image-agnostic (alpine) and must be WSL2-verified. Reuse, don't duplicate (D5).
- **3-3 / 3-4 (runner images):** landed the entrypoints + `runner.mjs` + mock CLIs + `--simulate-failure` + per-stage artifacts + conformance ITs. Their reviews explicitly deferred "real runner-API execution" and full failure-mode integration to **story 3.8** — i.e. this story. The `INSTALL_*_CLI=false` deterministic build is the right one for a contract test (no network/API).
- **3-5 / 3-6 (secrets / logs):** the secret-scan + redaction + log-capture services + the `{DELIVERYLINE_HOME}/runner-logs/{rexId}/` convention this story asserts on. 3-6's review hardened atomic/NOFOLLOW log writes; the redaction second pass is also enforced in Logstash (3-7).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.8: Real Docker Runner Contract Integration Test] (AC1–AC12)
- [Source: epic-03-agent-execution.md#Story 3.34 (CI Tier — Real Docker Runner Image Build + Compatibility Checks)] — owns `runner-image-build` + `runner-contract-real` + foundation-gate widening (D1/D9/D10)
- [Source: epic-03 Story 3.3 AC11 / Story 3.4 AC7] — `--simulate-failure={timeout|crash|contract_violation}` definition
- [Source: runners/RUNNER_CONTRACT.md] — exit codes, simulate-failure gating, stage→artifactType
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/BrokerDrivenDockerLifecycleITSupport.java] — broker-driven IT blueprint (D4)
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java] — image-build-in-`@BeforeAll` + happy-path artifact-variant pattern
- [Source: deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/RunnerContractValidator.java] — validation API
- [Source: deliveryline-backend/pom.xml — surefire/failsafe/`docker-runner-it`/`foundation-gate` profiles] — tiering + the `docker-runner-it` tag exclusion that drives D2

### Open Questions (for Alex — do not block implementation; default per reconciliations)

- **OQ-1 (D9):** Should the "foundation-gate widening" be realized now (this story) or deferred to 3.34? Default per D9: **deferred to 3.34** (the heavy real-runner test does not belong in the per-PR `FoundationGateVerificationTest` aggregator). Confirm if you want a gate hook now.
- **OQ-2 (D2/D10):** Add a minimal `runner-contract-real` GitHub Actions job in this story, or leave 100% of CI wiring to 3.34? Default: **leave to 3.34**; this story ships the IT runnable under `-Pdocker-runner-it`.
- **OQ-3 (D7):** Does `DockerRunnerAdapter.dispatch` already expose a seam to inject an arbitrary container env var (`DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE`) + extra args (`--simulate-failure=…`)? If not, is a small test-scoped passthrough acceptable as the one product change in this otherwise test-only story?

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context)

### Debug Log References

- `mvn -Pdocker-runner-it -pl deliveryline-backend -am verify -Dit.test=RealRunnerContractIT` (Docker Desktop 28.5.1, Linux VM backend) → **Tests run: 18, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS. All gates green in the same run (spotless:check, checkstyle, spotbugs, jacoco-check "All coverage checks have been met").
- Two iterations to green: (1) post-hoc `ValidationContext` initially put the rex in BOTH `known` + `observed` sets → validator's `DUPLICATE_RUNNER_EXECUTION_ID` semantic check fired; fixed to `known`-only. (2) AC7 redaction test originally drove the full broker harvest, but the seeded `Authorization: Bearer` leak in the raw workspace correctly fail-closes the run to `runner_secret_leak` (AC6 scan); re-scoped the redaction assertion to the `recoverHandle`→`captureRunnerLogs` capture boundary.

### Completion Notes List

**Outcome:** `RealRunnerContractIT` authored, runs **green against the real Codex + Claude images + real Postgres + the production `RunnerBroker`/`DockerRunnerAdapter`/persistence/log-capture/secret-scan pipeline + `RunnerContractValidator`** (18/18). NO production code changed — this is a pure test-authoring story.

**Decision D7 / OQ-3 resolved → NO product seam.** The runner entrypoint reads `--simulate-failure=`/`--stage=` only from container CLI args, and `CreateContainerSpec` carries no cmd field, so `DockerRunnerAdapter.dispatch` cannot pass them. Rather than widen the production adapter, the IT reuses the proven 3.2a `BrokerDrivenDockerLifecycleITSupport` pattern: seed `workflow_runs` + `runner_executions`, launch the real image as a labelled container (per-rex workspace binds + `--stage`/`--simulate-failure` args + the `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true` gate), prime the adapter handle via the production label-probe `recoverHandle(rex)`, then drive the real broker reconcile entry points (`pollActiveExecutions()` for happy/crash/contract_violation, `scanForTimeouts()` for timeout). The broker's harvest → contract-validate → persist → emit-events path runs exactly as in production; only the container launch (already a 3.2a test concern) is test-owned.

**Coverage:** {Codex, Claude} × {spec(INVESTIGATION), implementationPlan(EXECUTION+`--stage=implementation-plan`), prOutput(EXECUTION)} happy paths (status=completed + schema-valid result via `adapter.tryReadResult` + `RunnerContractValidator` + artifactType matches stage, AC2/3/4); {crash→failed/runner_crash/no-result, contract_violation→failed/runner_contract_violation/invalid-result-rejected, timeout→timed_out/runner_timeout + real-image `sleep` container force-killed} for both kinds (AC2/4/8); mock-vs-real parity pinned via the documented `Behaviour`→(status, failure_category, lifecycle event) table asserted by every scenario + a `mockBehaviourMapsToRealOutcome` guard (AC5); secrets pipeline — injected codex key, post-execution `scanWorkspace().leakDetected()==false` (AC6); logs+redaction — seeded `Authorization: Bearer` sentinel redacted before persistence (`redaction_count > 0`, sentinel absent from persisted `runner-logs/{rex}/runner.stdout`, classification present, AC7); rich `describe(scenario)` AssertJ descriptions on every assertion (AC12).

**Key implementation notes:**
- The foundation `context-bundle.v1.valid.json` is reused as the input bundle (AC3) with only `workflowRunId`/`runnerExecutionId` rebound to the seeded ids — the runner reads those from the bundle (`runner.mjs commandBuild`), so the broker's result-validation + artifact-ingestion (which key off the result document's `runnerExecutionId`) align with the seeded row. The bundle schema is stage-agnostic (`additionalProperties:false`, no `stage` field); stage is driven by the `--stage` arg / `DELIVERYLINE_RUNNER_STAGE` env.
- Mock provider keys are registered via `@DynamicPropertySource` so the broker's post-execution `RunnerSecretScanService` (run inside `onResult` on every happy completion) resolves a key to scan for instead of throwing `DOCTOR_RUNNER_SECRET_MISSING`. Non-secret happy paths use `DELIVERYLINE_RUNNER_SKIP_AUTH=true` for the container; the dedicated AC6 scenario injects the real codex key.
- `RunnerStage` is only `{INVESTIGATION, EXECUTION}`; the broker maps INVESTIGATION→spec, EXECUTION→{implementationPlan, prOutput}. implementationPlan is reached by passing `--stage=implementation-plan` (the entrypoint arg overrides the EXECUTION→prOutput env default), and the broker's `allowedArtifactTypesForStage(EXECUTION)` accepts it.

**Scope boundaries (documented in deferred-work.md):** GitHub Actions `runner-image-build` + `runner-contract-real` jobs + foundation-gate widening DEFERRED to story 3.34 (D1/D8/D9/D10) — this story ships the IT runnable under `-Pdocker-runner-it`; full JVM-restart recovery / heartbeat-staleness / dangling-cleanup NOT re-run on the heavy real images (image-agnostic, 3.2a-proven; only real-image **timeout** re-proven here, D5).

**Verification reality:** verified green on Docker Desktop 28.5.1 (Windows host, Linux-VM container runtime) — the timeout test passing proves the real entrypoint's `sleep` + the broker's stop→kill path interoperate on a real Linux container. Per [[wsl-linux-ci-reproduction]] / [[verify-ci-fixes-in-clean-env]], a native-Linux/WSL2 + Docker re-run is still the canonical CI gate (story 3.34) before relying on it in a pilot. No Claude co-author trailer on any commit ([[commit-no-claude-coauthor]]).

### File List

- **NEW** `deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java` — the broker-driven real-image contract IT (18 scenarios).
- **MODIFIED** `_bmad-output/implementation-artifacts/deferred-work.md` — story-3.8 deferred-work section (CI/foundation-gate → 3.34; D5 lifecycle scope; AC7 capture-boundary + AC5 mapping rationale).
- **MODIFIED** `_bmad-output/implementation-artifacts/sprint-status.yaml` — story status `ready-for-dev` → `in-progress` → `review`.

(No production code changed — D7/OQ-3 resolved as "no product seam". `runners/**` consumed unchanged, D1.)

## Change Log

| Date       | Version | Description                                                                                  | Author |
| ---------- | ------- | -------------------------------------------------------------------------------------------- | ------ |
| 2026-06-05 | 0.1     | Authored `RealRunnerContractIT` (18 scenarios, real Codex/Claude images, broker-driven). All green on Docker Desktop; no production code changed (D7 resolved no-seam). Status → review. | Amelia (dev-story) |
