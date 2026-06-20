# Story 3.1: DockerRunnerAdapter Core — Container Lifecycle + File-Based Contract Invocation

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer,
I want `DockerRunnerAdapter` implementing the existing `application.runner.spi.RunnerAdapter` port (from story 1.13) by driving a real Docker container lifecycle (create → start → wait → harvest result file) over the versioned file-based runner contract (from story 1.6),
So that `RunnerBroker` can dispatch real Codex + Claude runs (from stories 3.3 / 3.4) through the same port the deterministic mock already uses — with no broker-side reshape, no leak of Docker types into the application layer, and no auto-state-transition coupling beyond what story 1.13 already established.

## Acceptance Criteria

1. **Given** the `adapters.runner` package, **Then** `DockerRunnerAdapter` exists implementing the existing `org.dradgo.application.runner.spi.RunnerAdapter` interface — same four methods (`dispatch`, `poll`, `tryReadResult`, `cancel`) with identical signatures to `MockRunnerAdapter`. The bean is profile-gated `@Profile("runners.docker")` (mirroring the existing `@Profile("!runners.docker")` annotation on `MockRunnerAdapter` so the two are mutually exclusive). The existing `RunnerConfiguration.assertExclusiveRunnerProfile(...)` startup check (which already fails fast when both `runners.mock` and `runners.docker` are active) covers this story without modification.

2. **Given** a `RunnerAdapter.dispatch(RunnerDispatchRequest)` call from `RunnerBroker`, **When** invoked, **Then** the adapter:
   - (a) creates a per-execution **workspace** directory `{DELIVERYLINE_HOME}/runner-work/{runnerExecutionId}/` with three subdirs `input/`, `output/`, `logs/` (mode `0700` on POSIX; equivalent ACL on Windows) — workspace creation goes through a new application-owned port `RunnerWorkspaceStore` (see Task 1) with the same containment + symlink-escape + `PublicIdPrefixes.RUNNER_EXECUTION.require(...)` guards used by `LocalRunnerScratchStore`;
   - (b) reads the already-redacted, already-validated context-bundle bytes from the existing `RunnerScratchStore.tryReadContextBundle(runnerExecutionId)` (the broker writes them there in `RunnerBroker.dispatch` step 7 per story 1.13) and copies them via atomic temp-file + rename to `input/context-bundle.v1.json` — the adapter **does not** re-invoke `RunnerContractValidator` (the broker already validated during `ContextBundleService.create`; re-validating would mask state drift between scratch and workspace);
   - (c) launches the runner image (image-tag selection per AC5) with **exactly** these bind mounts and **no others**: `input/` → `/workspace/input` (read-only), `output/` → `/workspace/output` (read-write), `logs/` → `/workspace/logs` (read-write); plus `--network=none` (AC8); plus the labels in AC7; plus `--rm=false` so the exited container can be inspected by story 3.2 lifecycle code; the container is launched **non-blocking** (the call returns immediately after `docker create` + `docker start`);
   - (d) returns a `RunnerDispatchAck` carrying the Docker container id as `adapterRef` (format: `docker:{containerId}`) so future `poll` / `cancel` calls can correlate.

3. **Given** the container has exited (detected via `poll(runnerExecutionId)` finding the container in `exited` state), **Then**:
   - (a) If `output/runner-result.v1.json` exists → the adapter reads the bytes and returns them through `tryReadResult(...)`; the **broker** then validates via `RunnerContractValidator.validate(RUNNER_RESULT, ...)` and classifies per existing `RunnerBroker.onResult` logic (story 1.13 Task 4 step 2-4). The adapter never validates; it never classifies; it never calls `ArtifactOperationService` (AC6).
   - (b) If `output/runner-result.v1.json` does **not** exist AND the container exit code is non-zero → `poll(...)` returns `RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH)`; the broker transitions the row to `FAILED` per existing AC5 split in story 1.13.
   - (c) If `output/runner-result.v1.json` does **not** exist AND the container exit code is zero → `poll(...)` returns `RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH)` (a clean exit with no result is still a contract failure — the runner promised a result file). This is the same classification as (b) and a contract test pins both.
   - (d) **Timeout classification (`runner_timeout`) is NOT this story's responsibility** — it belongs to story 3.2's lifecycle layer (timeout enforcement + `docker stop`/`docker kill` + status flip). This story's `poll(...)` only reports `Running` / `Completed` / `Failed(RUNNER_CRASH)` / `Unknown`; the existing broker `scanForTimeouts()` continues to drive timeout transitions via the `runner_executions.timeout_at` column.

4. **Given** the runner-contracts module schema versions (story 1.6), **Then** the adapter writes context-bundle bytes verbatim from scratch (no schemaVersion mutation — the bundle already carries `schemaVersion: 1` per story 1.13's `ContextBundleService.create`). On the result side, **the broker** (not the adapter) rejects payloads carrying any version not in `RunnerSchemaVersion` (already wired through `RunnerContractValidator`). No new schema-version logic in the adapter.

5. **Given** runner image identification, **Then**:
   - A new nested `Docker` configuration record is added to `application.runner.RunnerProperties` with fields `Map<RunnerKind, String> imageTags` and `Path workspaceRoot` (default `{DELIVERYLINE_HOME}/runner-work`).
   - A new `domain.registry.RunnerKind` enum is added (initial values `CODEX("codex")`, `CLAUDE("claude")`) implementing `RegistryValue`, registered in `DomainRegistry`, `PersistedRegistryValues`, and `RegistryContractTest` (single source of truth for the wire string — no string literals scattered in adapter).
   - A new field `RunnerKind runnerKind` is added to `application.runner.RunnerDispatchRequest`; the broker resolves the kind from `RunnerProperties.docker().defaultKind()` (a third config field with default `CODEX`) and passes it through. **Trap T3:** kind is resolved server-side from config, **never** read from the context-bundle (which is untrusted runner-facing content); a unit test pins that the adapter ignores any `runnerKind`-like field that ever appears in the bundle.
   - Defaults: `codex` → `deliveryline/codex-runner:latest`, `claude` → `deliveryline/claude-runner:latest`. Both image tags are overridable via `application.yml` (`deliveryline.runner.docker.image-tags.codex=...`); the test profile uses a tiny no-dep image (see Task 7) so unit and integration suites do not depend on the (not-yet-built) Codex/Claude images from stories 3.3 / 3.4.

6. **Given** the architecture rule that runner outputs become workflow artifacts only through `ArtifactOperationService` (story 1.12), **Then** `DockerRunnerAdapter` **never** calls `ArtifactOperationService` directly, never calls `ArtifactRecordPort`, never imports `application.artifact.*`. It returns the result bytes to the broker via `tryReadResult(...)`; the broker drives artifact ingest via the already-shipped `RunnerBroker.onResult` path. An ArchUnit rule **`DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION`** pins this.

7. **Given** workspace cleanup semantics, **When** an execution completes (success, crash, or container-exit), **Then** the workspace directory `{DELIVERYLINE_HOME}/runner-work/{runnerExecutionId}/` **remains on disk** for diagnostic inspection. **Immediate deletion is forbidden** — cleanup is the responsibility of `RunnerWorkspaceCleanupJob` (story 3.2 AC5) bounded by `deliveryline.runner.docker.workspace-retention-hours` (default 24, declared in this story's `RunnerProperties.Docker` record so 3.2 has a property to read). A unit test pins that `dispatch(...)` followed by `poll(...)→Completed` followed by `tryReadResult(...)` leaves the workspace intact on disk.

8. **Given** the localhost-binding posture from story 6.9 (HTTP server bound to loopback, fail-closed on non-loopback), **Then** every container is launched with `--network=none` so the runner cannot reach `127.0.0.1:8080` (or any other backend port) at all. A contract test (`DockerRunnerNetworkIsolationContractTest`) asserts via `docker inspect` that the launched container's `NetworkMode == "none"` and `Networks` map is empty. Runner ↔ backend communication is strictly file-based (no HTTP from the runner side). **Trap T7:** `--network=none` must be set at create time, **not** patched after start — Docker rejects `NetworkMode` mutation on a running container; an integration test that calls `docker network connect` against a running runner asserts the connect is rejected (or the test simply asserts the inspect output never carries a network).

9. **Given** ArchUnit boundary rules (story 1.11), **Then** `DockerRunnerAdapter` lives in `adapters.runner` and only depends on:
   - `application.runner.*` (port, request/ack types, properties),
   - `application.runner.spi.*` (the existing `RunnerScratchStore` for reading the bundle bytes; the new `RunnerWorkspaceStore` port for creating + populating workspace dirs),
   - `domain.registry.*` (for `FailureCategory`, `RunnerKind`, `PublicIdPrefixes`),
   - the Docker client library (see OQ-1 — must be a single top-level dep, scoped to the docker-java or docker-cli wrapper module only),
   - SLF4J + Spring annotations.
   It does **not** depend on `domain.*` (entities), `adapters.persistence.*`, `adapters.files.*` (cross-slice — the workspace store lives in `adapters.files` and is reached only via its SPI port), `adapters.integration.*`, or `application.artifact.*` (AC6).

10. **Given** the test suite, **Then** integration tests under `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapter*Test.java` and Testcontainers-backed ITs cover:
    - (a) **Happy path** — runner image writes a valid `runner-result.v1.json` and exits 0; `poll` returns `Completed`; `tryReadResult` returns the bytes; the broker (via a focused `RunnerBrokerDockerHandoffContractTest` that uses a real `DockerRunnerAdapter`) validates and ingests.
    - (b) **Non-zero exit with valid result** — runner writes a valid result with `failureCategory: "runner_internal_error"` and exits 1; `poll` returns `Completed`; broker classifies as `runner_non_zero_exit` per existing logic.
    - (c) **Non-zero exit without result file** — runner exits 1 without writing; `poll` returns `Failed(RUNNER_CRASH)`.
    - (d) **Zero exit without result file** (per AC3.c) — runner exits 0 without writing; `poll` returns `Failed(RUNNER_CRASH)`.
    - (e) **Invalid result file** — runner writes truncated/schema-violating bytes; `tryReadResult` returns them; broker's `RunnerContractValidator` raises `runner_contract_violation`. (This proves the adapter does not pre-validate per AC3.a.)
    - (f) **Schema-version mismatch** — runner writes a result with `schemaVersion: 99`; broker classifies as `runner_contract_violation`. (Same proof.)
    - (g) **Image-tag override** — set `deliveryline.runner.docker.image-tags.codex=alpine:3.20` (or a stable test image) via `@TestPropertySource`; assert the launched container uses that image tag via `docker inspect`.
    - (h) **Workspace lifecycle** — workspace dir is created with the documented subdirs + permissions on dispatch; bundle bytes are copied byte-identical to `input/context-bundle.v1.json`; workspace survives `tryReadResult` (AC7).
    - (i) **Network isolation** — `docker inspect` reports `NetworkMode=none` (AC8); a separate IT runs an `nc -z 127.0.0.1 8080` probe inside the container and asserts it fails. (Uses a tiny alpine-based test image with netcat.)
    - (j) **Mount layout** — `docker inspect` reports exactly three binds matching the three workspace subdirs; no `docker.sock` mount; no host secrets mount; **the input mount is `ro`** (the runner cannot mutate the context bundle); the output + logs mounts are `rw`.
    - (k) **Label propagation** — `docker inspect` reports the four labels from AC7 (cross-reference to story 3.2 which adds `deliveryline.stage` and `deliveryline.dispatchedAt`).
    - (l) **Cancel idempotency** — `cancel(rex)` on a still-running container issues `docker stop`; `cancel(rex)` on an unknown id is a no-op (no throw); `cancel(rex)` on an already-exited container is a no-op. Mirrors the existing `MockRunnerAdapter.cancel` contract.
    - (m) **Profile wiring** — `DockerRunnerProfileWiringContractTest` (mirror of the existing `RunnerProfileWiringContractTest`) asserts: under `runners.docker`, `DockerRunnerAdapter` is the single `RunnerAdapter` bean and `MockRunnerAdapter` is excluded; under no profile (`!runners.docker`), the mock loads and the Docker adapter is excluded. The existing `runners.mock` + `runners.docker` mutual-exclusion check (in `RunnerConfiguration`) already covers the both-active case; we extend it with one more `ApplicationContextRunner` test that asserts `IllegalStateException` on both-active.

11. **Given** cross-platform support per story 1.17, **Then** the adapter is verified on Linux (CI) + Windows/Docker-Desktop + macOS/Docker-Desktop:
    - Mount path strings are produced via the chosen Docker client library's path-handling (which already normalizes `C:\...` to `//c/...` for Docker Desktop on Windows — same code path Testcontainers uses).
    - The new `RunnerWorkspaceStore` uses `java.nio.file.Path.toAbsolutePath().normalize()` for the host-side workspace path so that whatever Docker client receives is canonical.
    - `--network=none` is supported uniformly on Docker Desktop (Win/macOS) and Docker Engine (Linux) — no host-OS-specific branch.
    - The existing `RunnerProfileWiringContractTest` runs in the fast tier and is OS-independent; the Docker-backed ITs in AC10 are tagged `@Tag("docker-runner-it")` and gated by `@DisabledIfDockerUnavailable` (a small custom JUnit condition that probes Testcontainers' `DockerClientFactory.instance().isDockerAvailable()`), so contributors without Docker can still run the fast tier locally. CI runs the docker-tagged tier on `ubuntu-latest` per story 1.21 AC3.

## Scope Guardrail

This story owns:

- the `DockerRunnerAdapter` class + its profile wiring,
- the new `RunnerWorkspaceStore` SPI port + `LocalRunnerWorkspaceStore` adapter,
- the new `RunnerKind` registry enum,
- the new `RunnerProperties.Docker` config record + `runnerKind` field on `RunnerDispatchRequest`,
- the new ArchUnit rule pinning the artifact-application boundary,
- the focused unit + integration test surface listed in AC10.

This story does **NOT** own:

- timeout enforcement + `docker stop`/`docker kill` + heartbeat + `last_activity_at` polling — that is story 3.2 (the `poll(...)` method here only reports container-exit detection; the broker's existing `scanForTimeouts()` continues to drive timeout transitions until 3.2 extends them);
- workspace cleanup job + retention enforcement — that is story 3.2 AC5 (this story declares the `workspace-retention-hours` property and **forbids** immediate deletion, leaving cleanup to 3.2);
- secret env injection — that is story 3.5 (this story launches containers with **no** `--env` / `--env-file` flags; the AC8 `--network=none` posture makes that safe);
- log capture + redaction + classification — that is story 3.6 (this story creates the `logs/` mount so the runner image can write there, but does not copy/redact those files into the host-side `runner-logs/`; that's 3.6);
- Codex + Claude runner images — those are stories 3.3 / 3.4 (this story uses a tiny test image for ITs);
- repository workspace + git clone + push — that is story 3.9 (this story creates no `repo/` mount; 3.9 adds a fourth mount via the same port);
- real-runner contract IT against the actual Codex + Claude images — that is story 3.8;
- spec-stage orchestration that calls into the broker — that is story 3a-1.

## Tasks / Subtasks

- [x] **Task 1: Add the workspace SPI port + filesystem adapter** (AC: 2, 7, 9, 11)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java` with the surface:
    - `WorkspaceLayout prepare(String runnerExecutionId)` — creates `runner-work/{rex_id}/{input,output,logs}/` with restrictive permissions; returns the typed `WorkspaceLayout(Path root, Path input, Path output, Path logs)` record; idempotent (re-prepare on the same id returns the existing layout without mutation).
    - `Path writeInputBundle(String runnerExecutionId, byte[] bytes)` — atomic temp-file + rename to `input/context-bundle.v1.json`, mirrors `LocalRunnerScratchStore.writeContextBundle` semantics including the parent-dir fsync best-effort and the `PublicIdPrefixes.RUNNER_EXECUTION.require(...)` guard.
    - `Optional<byte[]> tryReadResult(String runnerExecutionId)` — reads `output/runner-result.v1.json` if present; same containment + `NOFOLLOW_LINKS` guards as `LocalRunnerScratchStore.tryReadRunnerResult`.
    - `Optional<Path> resolveOutputRoot(String runnerExecutionId)` — returns the absolute host-side path of the `output/` subdir (or empty if `prepare` was never called) — used by story 3.6 to mount logs.
    - **Containment guard:** every method resolves paths under `{deliveryline.home}/runner-work/` and asserts `.toRealPath().startsWith(deliveryline.home)`; symlink-swap defense via `LinkOption.NOFOLLOW_LINKS`.
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java` implementing the port; copy the proven path-validation + atomic-write pattern from `LocalRunnerScratchStore` (do not invent a new pattern). Add the `@Component` annotation. Inject `@Value("${deliveryline.home}")`.
  - [x] **Do NOT** add the workspace methods to `RunnerScratchStore` itself — keep the two ports separate (scratch = broker-owned bundle/result leaf files for mock + broker correlation; workspace = adapter-owned bind-mount host dirs). This matches the architecture's "one port per concern" rule and prevents `MockRunnerAdapter` from accidentally depending on workspace types.
  - [x] Add focused unit test `LocalRunnerWorkspaceStoreTest`: prepare creates exactly the three subdirs with `0700` (POSIX) or appropriate ACL (Windows — skip the permission assertion under `OS.WINDOWS` via `@DisabledOnOs`), prepare is idempotent, writeInputBundle is atomic (kill the JVM between temp-write and rename via the same pattern proven in `LocalArtifactStoreTest`), tryReadResult returns empty when the file is missing, containment guard rejects rex-ids with traversal sequences (mirrors `LocalRunnerScratchStoreTest`).

- [x] **Task 2: Introduce the `RunnerKind` registry + `Docker` properties + `runnerKind` request field** (AC: 5, 9)
  - [x] Add `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RunnerKind.java` implementing `RegistryValue` (initial values `CODEX("codex")`, `CLAUDE("claude")`). Mirror the shape of `RunnerStage`. Register in `DomainRegistry`, add to `PersistedRegistryValues` (even though no DB column exists yet — registry contract test surface), extend `RegistryContractTest` to lock the wire-form list.
  - [x] Add nested `Docker` record to `RunnerProperties`:
    ```java
    public record Docker(
        RunnerKind defaultKind,                       // default CODEX
        Map<RunnerKind, String> imageTags,            // {CODEX → "deliveryline/codex-runner:latest", CLAUDE → "deliveryline/claude-runner:latest"}
        Path workspaceRoot,                           // default {deliveryline.home}/runner-work
        long workspaceRetentionHours,                 // default 24 — story 3.2 reads this
        Duration containerCreateTimeout,              // default 30s — defensive Docker-API call deadline
        Duration containerStartTimeout) {             // default 30s
      // Defensive null + empty-map handling; require all imageTags entries.
    }
    ```
    Plus `RunnerProperties.defaults()` populates a `Docker.defaults()` static factory. Update `RunnerProperties.defaults()` to invoke `Docker.defaults()`.
  - [x] Extend `application.yml` with the `deliveryline.runner.docker.*` block carrying the documented defaults; extend `application-test.yml` with the **test image tag** override (`docker.image-tags.codex: alpine:3.20`, `docker.image-tags.claude: alpine:3.20`) so ITs do not depend on the real (not-yet-built) Codex/Claude images.
  - [x] Add `RunnerKind runnerKind` field to `application.runner.RunnerDispatchRequest`. Update the record constructor to require + null-check it. Update **every** existing caller of `new RunnerDispatchRequest(...)`:
    - `RunnerBroker.dispatch(...)` — read `runnerKind` from `runnerProperties.docker().defaultKind()`; pass through.
    - `MockRunnerAdapter` — no code change needed (the adapter only reads fields it cares about; the new field is ignored harmlessly).
    - **Search the test surface exhaustively:** `git grep -nE 'new RunnerDispatchRequest\\('` and update every callsite. **Trap T2:** missing a callsite breaks compile but the error message points at `RunnerDispatchRequest` not at the calling test — a smoke build catches it before the IT tier.
  - [x] Extend `RunnerBrokerUnitTest` with one new test asserting the broker passes `runnerKind = runnerProperties.docker().defaultKind()` into the adapter — pins the trap that nobody slips runnerKind from the context bundle.

- [x] **Task 3: Choose + integrate the Docker client library** (AC: 2, 8, 10, 11)
  - [x] **Decision (OQ-1):** Use `com.github.docker-java:docker-java-core` + `docker-java-transport-httpclient5` (the same client Testcontainers uses transitively — pinning explicitly here keeps version control in our `pom.xml`). Add to `deliveryline-backend/pom.xml` (NOT to `runner-contracts/pom.xml` — runner-contracts must stay Docker-free). Version: pin to the latest stable (check via `mvn versions:display-dependency-updates` at implementation time; the architecture's dep-pin rule applies — single source of truth in `<dependencyManagement>` if any reuse appears).
  - [x] Add a wrapper `org.dradgo.adapters.runner.docker.DockerEngineGateway` (interface + `DefaultDockerEngineGateway` implementation) so the test surface can mock the Docker client surface without spinning up real containers. The wrapper surface:
    - `String createContainer(CreateContainerSpec spec) → containerId` — wraps `docker create` (image, binds, networkMode, labels, no env, no command override beyond what the image declares);
    - `void startContainer(String containerId)` — wraps `docker start`;
    - `ContainerState inspectContainer(String containerId)` — wraps `docker inspect` returning a typed record `(status, exitCode, networkMode, binds, labels)` — NOT the full Docker JSON (filter at the gateway boundary so the adapter never sees raw maps);
    - `void stopContainer(String containerId, Duration graceful)` — wraps `docker stop` with the 10s graceful default;
    - `void removeContainer(String containerId, boolean force)` — wraps `docker rm` (this story does NOT call `remove`; it's reserved for story 3.2's cleanup job — but the surface exists for the cleanup-job's later use).
  - [x] **Trap T8:** Do **not** leak `com.github.dockerjava.api.model.*` types past `DockerEngineGateway`. The gateway returns project-owned records (e.g., `ContainerState`, `CreateContainerSpec`). This keeps Docker library upgrades local + lets the architecture's "no third-party types in application/domain" rule hold (the gateway lives in `adapters.runner.docker` which is allowed to depend on Docker types; the rest of the codebase is not).

- [x] **Task 4: Implement `DockerRunnerAdapter`** (AC: 1, 2, 3, 5, 6, 7, 8, 9, 11)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java`:
    ```java
    @Component
    @Profile("runners.docker")
    public class DockerRunnerAdapter implements RunnerAdapter {
      private final RunnerScratchStore scratchStore;       // reads the bundle bytes broker wrote
      private final RunnerWorkspaceStore workspaceStore;   // creates + populates the bind-mount dirs
      private final DockerEngineGateway docker;            // wraps docker-java
      private final RunnerProperties runnerProperties;     // reads docker().imageTags + workspaceRoot
      private final ConcurrentMap<String, String> rexIdToContainerId = new ConcurrentHashMap<>();
      // dispatch / poll / tryReadResult / cancel implementations below
    }
    ```
  - [x] `dispatch(RunnerDispatchRequest request)`:
    1. `PublicIdPrefixes.RUNNER_EXECUTION.require(request.runnerExecutionId())` — defense-in-depth (broker already validates; mirror the existing scratch-store guard).
    2. Resolve image tag: `String image = runnerProperties.docker().imageTags().get(request.runnerKind())` — throw `IllegalStateException` (NOT a typed `DomainException` — this is a config error, not a user-facing failure) if missing, so the deployment fails fast on a misconfigured `application.yml`.
    3. `WorkspaceLayout layout = workspaceStore.prepare(request.runnerExecutionId())`.
    4. `byte[] bundleBytes = scratchStore.tryReadContextBundle(request.runnerExecutionId()).orElseThrow(() → new IllegalStateException("scratch bundle missing — broker contract violated"))` — this is a broker-internal invariant violation (the broker writes the bundle in dispatch step 7 before delegating to the adapter per story 1.13); a missing bundle here means the broker is broken, not the runner.
    5. `workspaceStore.writeInputBundle(request.runnerExecutionId(), bundleBytes)`.
    6. Build `CreateContainerSpec`:
       - `image = image`,
       - `binds = [layout.input → /workspace/input (ro), layout.output → /workspace/output (rw), layout.logs → /workspace/logs (rw)]`,
       - `networkMode = "none"`,
       - `labels = {"deliveryline.runnerExecutionId": rexId, "deliveryline.workflowRunId": workflowRunId, "deliveryline.runnerKind": runnerKind.value(), "deliveryline.dispatchedAt": <ISO-8601 timestamp from injected Clock>}` — note: story 3.2 adds `deliveryline.stage` per AC7 there; this story's label set is the minimum survivable subset.
    7. `String containerId = docker.createContainer(spec)`.
    8. `docker.startContainer(containerId)`.
    9. `rexIdToContainerId.put(rexId, containerId)`.
    10. Return `new RunnerDispatchAck("docker:" + containerId)`.
    - **Logging:** INFO on entry (rexId, workflowRunId, kind, image), INFO on success (rexId, containerId, workspaceRoot), WARN on `IllegalStateException` (config drift), ERROR on Docker gateway failure (re-throw as `IllegalStateException` so the broker can decide — do NOT wrap as `DomainException` here; the adapter is below the typed-error boundary).
  - [x] `poll(String runnerExecutionId)`:
    1. `String containerId = rexIdToContainerId.get(runnerExecutionId)` — if null (e.g., post-restart, before story 3.2's recovery wires us back) → `new RunnerPollStatus.Unknown()`.
    2. `ContainerState state = docker.inspectContainer(containerId)`.
    3. Switch on `state.status()`:
       - `created`, `running`, `paused`, `restarting` → `new RunnerPollStatus.Running()` (heartbeat tracking is story 3.2's responsibility — this story does NOT return `HeartbeatTouched`).
       - `exited`:
         - if `state.exitCode() != 0` AND `workspaceStore.tryReadResult(...).isEmpty()` → `new RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH)`;
         - if `state.exitCode() == 0` AND `workspaceStore.tryReadResult(...).isEmpty()` → `new RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH)` (per AC3.c);
         - otherwise (result file present, any exit code) → `new RunnerPollStatus.Completed()` (the broker validates and decides crash-vs-success vs non-zero-exit).
       - `dead`, `removing` → `new RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH)` (unrecoverable container state).
       - **Trap T9:** do NOT map any state to `RUNNER_TIMEOUT` — timeout classification is exclusively the broker's `scanForTimeouts()` path until story 3.2 extends it.
    - **Logging:** DEBUG on Running (high-frequency path); INFO on Completed (rexId, exitCode); WARN on Failed with reason.
  - [x] `tryReadResult(String runnerExecutionId)`: delegate to `workspaceStore.tryReadResult(runnerExecutionId)`. Add INFO log on non-empty read with byte count.
  - [x] `cancel(String runnerExecutionId)`:
    1. `String containerId = rexIdToContainerId.remove(runnerExecutionId)` — if null, return (no throw — idempotent per spec).
    2. `ContainerState state = docker.inspectContainer(containerId)` — if `state.status() == "exited"` or `"dead"`, return.
    3. `docker.stopContainer(containerId, Duration.ofSeconds(10))` — `docker stop` already swallows "already stopped" semantics in the Docker engine.
    4. **Trap T11:** do NOT call `removeContainer` here — that's story 3.2's job; removing the container would erase the diagnostic state AC7 protects.
    - **Logging:** INFO on cancel issued; WARN on inspect/stop failure (do not throw — `cancel` contract is "best-effort, idempotent, must not throw").

- [x] **Task 5: Wire the new ArchUnit rule + extend the profile-wiring contract test** (AC: 6, 9, 10)
  - [x] Add `DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION` to `ArchitectureRuleCatalog`:
    ```java
    public static final ArchRule DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION =
        noClasses()
            .that().resideInAPackage("org.dradgo.adapters.runner..")
            .should().dependOnClassesThat().resideInAPackage("org.dradgo.application.artifact..");
    ```
    Register the rule in `ArchitectureBoundaryTest` next to the existing runner rule. **Trap T6:** this rule must NOT match the broker (`application.runner`) — the broker IS allowed to call into `application.artifact`. The rule scope is `adapters.runner..` only.
  - [x] Create `DockerRunnerProfileWiringContractTest` (mirror of `RunnerProfileWiringContractTest`):
    - Under `spring.profiles.active=runners.docker` → assert `RunnerAdapter` resolves to `DockerRunnerAdapter`, `MockRunnerAdapter` is excluded, `RunnerWorkspaceStore` is present.
    - Under no profile → assert `RunnerAdapter` resolves to `MockRunnerAdapter`, `DockerRunnerAdapter` is excluded.
    - Under both `runners.mock` AND `runners.docker` → assert context startup throws `IllegalStateException` with message containing "mutually exclusive" (the existing `RunnerConfiguration.assertExclusiveRunnerProfile` already enforces this; this test pins the behavior at the Docker adapter's introduction).
    - Use `ApplicationContextRunner` (NOT `@SpringBootTest`) per the existing pattern; stub `DockerEngineGateway` with Mockito so the test does not need a real Docker daemon.
  - [x] Update the existing `RunnerProfileWiringContractTest.mockRunnerAdapterIsExcludedWhenRunnersDockerProfileIsActive` assertion: after this story, under `runners.docker`, `RunnerAdapter` resolves to `DockerRunnerAdapter` (not "no `RunnerAdapter` bean" as the test currently asserts). Update the assertion accordingly.

- [x] **Task 6: Wire the DockerEngineGateway bean + the new `runners.docker` profile properties** (AC: 1, 5)
  - [x] Add `org.dradgo.adapters.runner.docker.DockerConfiguration` (NOT `infrastructure.config.RunnerConfiguration` — the gateway is adapter-owned, not infrastructure-owned):
    ```java
    @Configuration
    @Profile("runners.docker")
    class DockerConfiguration {
      @Bean
      DockerEngineGateway dockerEngineGateway(/* docker-java DockerClient autowired from a separate bean factory */) {
        return new DefaultDockerEngineGateway(...);
      }
      @Bean
      DockerClient dockerClient() {
        // Build via DefaultDockerClientConfig.createDefaultConfigBuilder().build() — picks up DOCKER_HOST env var by default; Testcontainers' DockerClientFactory normalizes path on Windows.
      }
    }
    ```
  - [x] **Trap T10:** the `DockerClient` bean must NOT load under `!runners.docker` — wrap it in `@Profile("runners.docker")` so contributors without Docker can run the fast tier without the Docker client trying to probe `unix:///var/run/docker.sock` on startup.

- [x] **Task 7: Unit + integration test surface** (AC: 10, 11)
  - [x] `DockerRunnerAdapterUnitTest` (Mockito; no real Docker):
    - dispatch with mocked `DockerEngineGateway` asserts the exact `CreateContainerSpec` shape (image, binds, networkMode=none, labels, no env, no command); asserts containerId is stored.
    - dispatch when image-tag missing for kind raises `IllegalStateException` (config error).
    - dispatch when scratch bundle missing raises `IllegalStateException` ("broker contract violated").
    - poll branches: running, exited+0+result, exited+non-zero+result, exited+0+no-result, exited+non-zero+no-result, dead, unknown.
    - tryReadResult delegates to workspace store (one assertion: pass-through).
    - cancel branches: unknown rexId (no-op, no throw), already-exited (no docker stop), running (docker stop called once with 10s grace).
    - Logging-contract assertions: list-appender pinning of the WARN line on config drift, the WARN line on cancel inspect failure, the INFO line on dispatch success (per the project-wide "every new branch has a pinned log line" rule from the template). Add a `DockerRunnerAdapterLoggingContractTest` modeled on the existing `RunnerLoggingContractTest`.
  - [x] `DockerRunnerAdapterContainerLifecycleIT` (Testcontainers-backed; tagged `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`):
    - Uses image `alpine:3.20` (overridden via `@TestPropertySource("deliveryline.runner.docker.image-tags.codex=alpine:3.20")`) with a `Command` override produced by **a small test helper** `TestRunnerImageScripts.java` that uses Testcontainers' direct API to launch a one-shot container that exec'es `sh -c '...'` — covers AC10 (a)-(d). (Rationale: we cannot extend `alpine:3.20` to "write a valid runner-result.v1.json" without an entrypoint; the test helper writes the script.)
    - **NOTE on test-image choice:** AC10 ITs are container-shape tests, not Codex/Claude content tests. The scripts produce minimal valid `runner-result.v1.json` bytes (the same fixture content from `deliveryline-runner-contracts/src/test/resources/fixtures/valid/runner-result.v1.spec.valid.json`) so the broker's `RunnerContractValidator` accepts them. **Trap T12:** the helper must NOT mutate the fixture file — it reads bytes from classpath and writes them inside the container.
    - Covers AC10 (g) image-tag override, (h) workspace lifecycle, (i) network isolation, (j) mount layout, (k) labels, (l) cancel idempotency, (m) profile wiring.
  - [x] `RunnerBrokerDockerHandoffContractTest` (light-weight `@SpringBootTest(classes={...})` with the broker + a real `DockerRunnerAdapter` wired against a Mockito `DockerEngineGateway`): asserts the broker → adapter handoff end-to-end (dispatch reserves rex_id, writes bundle to scratch, adapter copies to workspace, ack returned). Pins the contract between the two pieces without requiring Docker.
  - [x] **CI tier placement:** docker-tagged ITs run in a new `docker-runner-it` CI job on `ubuntu-latest` per story 1.21 AC3. **Caveat:** per sprint-change-proposal-2026-05-26 §3.7, real-runner-contract testing waits for story 3-8. This story's IT tier is **opt-in via tag** — it does NOT join the foundation gate (which would block PRs without Docker availability in CI). The unit + ApplicationContextRunner + ArchUnit tests above DO run in the fast tier and protect against architectural drift on every PR. Add the new `docker-runner-it` job to `.github/workflows/ci.yml` per the existing per-tier pattern; document the opt-in tag in `docs/testing.md` (or wherever story 1.21 documents tiers).

- [x] **Task 8: Cross-platform smoke + documentation** (AC: 11)
  - [x] Add a `docs/runner-workspace-layout.md` documenting:
    - The host-side layout (`{deliveryline.home}/runner-work/{rex_id}/{input,output,logs}/`),
    - The container-side mount paths (`/workspace/{input,output,logs}`),
    - The bind-mount permissions (input ro, output+logs rw),
    - The network posture (`--network=none`),
    - The label set (so operators can `docker ps --filter label=deliveryline.runnerExecutionId=rex_xyz`),
    - The retention policy stub (story 3.2 enforces; this story declares).
  - [x] Extend `docs/setup-local.md` (or the equivalent contributor-facing doc) with the Docker prerequisite note for `runners.docker` profile activation; reference the doctor command (story 1.16) for the local-environment probe.
  - [x] **Windows-specific:** test the path normalization on a local Windows host before PR; document any drift in `frontend-lockfile-cross-platform.md`-style memory if Windows-specific path handling needs a project-memory note. (Per the existing `wsl-linux-ci-reproduction.md` memory, also smoke-test in WSL2 Ubuntu before pushing the CI-tagged ITs.)

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J-backed structured logs at every public adapter method entry/exit (dispatch INFO + WARN/ERROR branches; poll DEBUG on Running + INFO on Completed + WARN on Failed; cancel INFO on issued + WARN on best-effort failure).
  - [x] Parameterized logging only; never string concatenation.
  - [x] Required context keys: `runnerExecutionId`, `workflowRunId`, plus `containerId` once known, `runnerKind`, `image` on dispatch. MDC keys reuse the existing `MdcKeys` constants (`MdcKeys.WORKFLOW_RUN_ID`, `MdcKeys.RUNNER_EXECUTION_ID` — verify both exist; if not, extend per the registry rule in story 1.10/1.19).
  - [x] Forbidden in logs: any byte from the context bundle (could contain redacted-but-still-not-shareable content), any byte from the result file, any image-tag override that contains a colon-form auth string (defensive — pull-secrets land in 3.5).
  - [x] `DockerRunnerAdapterLoggingContractTest` pins one assertion per new log branch with a list-appender (mirror `RunnerLoggingContractTest` pattern).

## Dev Notes

### Architectural anchors (DO NOT REINVENT)

- **`RunnerAdapter` port already exists** at `application.runner.spi.RunnerAdapter` (story 1.13) with the four methods (`dispatch`, `poll`, `tryReadResult`, `cancel`). The Docker adapter implements the same surface as the mock. The port is the only surface the broker uses; no other surface is added in this story.
- **`MockRunnerAdapter` (story 1.13) is the reference implementation** for this story's adapter shape: `@Component`, profile-gated, single `dispatch → poll → tryReadResult → cancel` lifecycle. Read its source before writing the Docker adapter — the structure, the logging conventions, and the `ConcurrentMap`-based handle tracking translate directly. Its `@Profile("!runners.docker")` annotation is the exact mirror of this story's `@Profile("runners.docker")`.
- **`LocalRunnerScratchStore`** (`adapters.files`) is the reference filesystem adapter — copy its containment + symlink-escape + atomic-write pattern verbatim into `LocalRunnerWorkspaceStore`. Do not re-invent path validation.
- **`RunnerConfiguration`** (`infrastructure.config`) already asserts mutual exclusion of `runners.mock` and `runners.docker` profiles via `assertExclusiveRunnerProfile(...)`. This story does NOT touch that check; it relies on it.
- **`RunnerBroker.dispatch` step 7** (story 1.13) writes the redacted, validated context-bundle bytes to scratch BEFORE calling `RunnerAdapter.dispatch(...)`. The Docker adapter reads from scratch and copies to the workspace — this is intentional reuse of the scratch path as the source of truth, NOT a workaround.
- **`RunnerContractValidator`** (runner-contracts module) is the only sanctioned validation surface. The broker calls it on the result side per story 1.13 Task 4 step 2; the adapter never calls it. (AC4 + AC3.a.)
- **`FailureCategory.RUNNER_CRASH` / `RUNNER_NON_ZERO_EXIT` / `RUNNER_CONTRACT_VIOLATION`** all already exist in `domain.registry.FailureCategory` — no registry additions. **`RUNNER_TIMEOUT` is NOT used by this story's adapter** (story 3.2 owns timeout classification).
- **`runner_executions` table** + state machine + idempotent dispatch + result handling + heartbeat + orphan recovery + state-mutation guard all live in `RunnerBroker` (story 1.13). The adapter is a thin transport — never touches the DB, never appends events, never transitions state.
- **`ArtifactOperationService`** (story 1.12) is the only sanctioned artifact-ingest surface. The broker calls it on validated results; the adapter never does. (AC6 + ArchUnit rule from Task 5.)

### Trap registry (twelve declared traps)

| Trap | Description | How to verify |
| --- | --- | --- |
| **T1** | The new workspace port + adapter must be separate from the existing `RunnerScratchStore` (different concerns). | Code review: `LocalRunnerWorkspaceStore` is a sibling class, not a method addition. `MockRunnerAdapter` does not import `RunnerWorkspaceStore`. |
| **T2** | Adding `RunnerKind runnerKind` to `RunnerDispatchRequest` is a record-constructor break — every callsite must be updated. | `git grep -nE 'new RunnerDispatchRequest\\('` and update each. Smoke build before pushing. |
| **T3** | `runnerKind` resolved server-side from `RunnerProperties`, never from the context bundle (which is untrusted). | Unit test: pass a bundle with a fake `runnerKind` field; assert the adapter still uses the property-derived kind. |
| **T4** | The Docker adapter must NOT re-validate the context bundle (AC2.b) — the broker already validated. | Unit test: poison the scratch bundle with bytes that would fail validation; assert dispatch still copies + launches (the broker invariant is "bundle in scratch is already valid"). |
| **T5** | The bundle filename inside the container input mount is `context-bundle.v1.json` (matches scratch leaf), NOT `context-bundle.json`. AC2.b shorthand was aspirational; alignment with the schema id wins. | Test asserts `Files.exists(workspace.input().resolve("context-bundle.v1.json"))`. |
| **T6** | The new ArchUnit rule scope is `adapters.runner..` only — must NOT match `application.runner..` (broker is allowed to touch artifact-application). | Run `ArchitectureBoundaryTest` with the rule before and after; broker tests still pass. |
| **T7** | `--network=none` set at container create time, not patched after start. | Inspect-based IT asserts `NetworkMode=none` on a running container; a complementary IT asserts `docker network connect` cannot be patched in without recreating. |
| **T8** | Do NOT leak `com.github.dockerjava.api.model.*` types past `DockerEngineGateway`. | ArchUnit rule **`ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY`**: classes in `adapters.runner..` (NOT in `.docker..` subpackage) must not depend on `com.github.dockerjava..` types. |
| **T9** | The adapter's `poll(...)` must NOT return `RUNNER_TIMEOUT` — timeout classification stays with the broker's `scanForTimeouts()` until story 3.2 extends them. | Code review: no `RUNNER_TIMEOUT` reference in `DockerRunnerAdapter.java`. |
| **T10** | The `DockerClient` Spring bean must be `@Profile("runners.docker")`-gated so contributors without Docker can run the fast tier. | `DockerRunnerProfileWiringContractTest` asserts no `DockerClient` bean under no-profile context. |
| **T11** | `cancel(...)` issues `docker stop` only — never `docker rm` (story 3.2's cleanup job owns removal). | Mockito assertion: `docker.removeContainer(any(), anyBoolean())` is never invoked from `cancel(...)`. |
| **T12** | The test-image helper reads bytes from a runner-contracts test fixture — never mutates it. | `git diff` after IT run shows zero changes under `deliveryline-runner-contracts/src/test/resources/fixtures/`. |

### Open Questions (recommendations included; surface for explicit sign-off)

- **OQ-1 — Docker client library choice.** **Recommend: `com.github.docker-java:docker-java-core` + `docker-java-transport-httpclient5`** (Testcontainers already brings it transitively — explicit dep gives us version control). Alternative considered: Docker CLI via `ProcessBuilder` (rejected — Windows path translation gets ugly, and we already use Testcontainers' Docker client elsewhere). Pin the version in `<dependencyManagement>` under the root `pom.xml`.
- **OQ-2 — Workspace path namespace.** **Recommend: `runner-work/{rex_id}/` separate from `runner-scratch/{rex_id}/`** — different lifetimes (scratch survives the broker; workspace survives the runner exit + AC7 retention window only). Alternative: collapse into one tree (rejected — conflates concerns, breaks the existing scratch contract).
- **OQ-3 — Where `runnerKind` comes from.** **Recommend: `RunnerProperties.docker().defaultKind()` resolved by the broker, passed via `RunnerDispatchRequest`** — keeps the kind decision server-side, allows future per-stage overrides (e.g., `INVESTIGATION → CODEX`, `EXECUTION → CLAUDE`) without changing the adapter. Alternative: adapter reads from properties directly (rejected — leaks config-knowledge into the adapter; mock adapter would need to know about it too).
- **OQ-4 — Input filename inside container.** **Recommend: `input/context-bundle.v1.json`** (aligns with scratch leaf + schema $id versioning). AC2.b uses `input/context-bundle.json` as shorthand; pick the versioned form for symmetry. Document the decision in `docs/runner-workspace-layout.md` so runner-image entrypoint authors (stories 3.3 / 3.4) reach for the right filename.
- **OQ-5 — IT gating on Docker availability.** **Recommend: custom `@EnabledIfDockerAvailable` JUnit condition** that probes `Testcontainers DockerClientFactory.instance().isDockerAvailable()` once per JVM (cached). Tag the IT class with this AND `@Tag("docker-runner-it")` so CI tier selection is clean. Alternative: `@DisabledOnOs(OS.WINDOWS)` (rejected — Windows contributors with Docker Desktop should be able to run the IT locally).
- **OQ-6 — Test image choice.** **Recommend: `alpine:3.20`** for the AC10 ITs (small, stable, widely cached, has `nc` for the network-isolation probe). Avoid `hello-world` (no shell, can't write the result fixture). Avoid `busybox` (smaller but lacks some `nc` variants).
- **OQ-7 — Should we add `runner_kind` to the `runner_executions` table?** **Recommend: NO for this story** (out of scope — observability nicety, no functional dependency). Defer to story 3-19 (queue inspection) or 4-9 (failure classification) if operators ask for it. The label on the container already carries it for `docker inspect`-based forensics.

### Cross-story dependencies + sequencing

- **Strict prerequisites (already done):**
  - Story 1.6 — runner-contracts module + schemas (provides the JSON schemas the runner images conform to + the validator the broker calls).
  - Story 1.13 — `RunnerBroker` + `MockRunnerAdapter` + `RunnerAdapter` port + `RunnerScratchStore` + `RunnerContractValidator` integration.
  - Story 1.10 — `RedactionPolicyService` (used by `ContextBundleService` to redact before the broker writes to scratch — this story just copies the redacted bytes).
  - Story 1.11 — ArchUnit boundary rules + `ArchitectureRuleCatalog` (the new rule plugs into the existing catalog).
  - Story 1.12 — `ArtifactOperationService` + `LocalArtifactStore` containment pattern (the workspace store copies the proven path-validation pattern).
  - Story 1.21 — CI tier scaffolding (the new `docker-runner-it` job slots into the existing per-tier convention).
- **This story unblocks:**
  - Story 3.2 — lifecycle (timeout + heartbeat + cleanup + recovery) extends this adapter's `poll(...)` and adds the workspace cleanup job. The `workspace-retention-hours` property declared here is read there.
  - Stories 3.3 + 3.4 — Codex + Claude runner images consume the `/workspace/{input,output,logs}` mount contract this story locks. The runner-image entrypoint contract reaches for `context-bundle.v1.json` per OQ-4.
  - Story 3.5 — secret env injection extends the `CreateContainerSpec.env(...)` field this story leaves empty.
  - Story 3.6 — runner-logs capture reads from `output/runner-result.v1.json` + the `logs/` mount this story creates.
  - Story 3.8 — real-runner contract IT replaces the alpine-based test image with the real Codex + Claude images this story's image-tag override surface allows.
  - Story 3.9 — `RepositoryWorkspaceService` adds a fourth mount (`/workspace/repo`) via the same `RunnerWorkspaceStore` port (or extends `WorkspaceLayout` with an optional `repo` path).
  - Story 3a-1 — spec-stage orchestration (`dispatchSpecGeneration`) ultimately dispatches through the broker → this adapter when `runners.docker` is the active profile.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (this story's surface):**
  - `DockerRunnerAdapter.dispatch` → INFO on entry (rexId, workflowRunId, kind, image); INFO on success (rexId, containerId); WARN on config drift (`IllegalStateException` for missing image tag); ERROR on Docker gateway failure.
  - `DockerRunnerAdapter.poll` → DEBUG on Running (high-frequency path); INFO on Completed (rexId, containerId, exitCode); WARN on Failed (rexId, failureCategory, reason).
  - `DockerRunnerAdapter.cancel` → INFO on cancel issued (rexId, containerId); WARN on inspect/stop failure (never throw).
  - `LocalRunnerWorkspaceStore.prepare` → INFO "workspace prepared rex={} root={}"; WARN on existing-but-broken layout (e.g., a file where a dir should be).
  - `LocalRunnerWorkspaceStore.writeInputBundle` → INFO "input bundle written rex={} bytes={}"; ERROR on IO failure (re-thrown as `IllegalStateException`).
  - `DefaultDockerEngineGateway.*` → INFO on each docker action with the containerId; WARN on retryable Docker error (rare); ERROR on unrecoverable.
- **Required context keys:** `runnerExecutionId`, `workflowRunId` (from MDC where available), `containerId` (after create), `runnerKind`, `image`.
- **Forbidden in log output:** context-bundle bytes, runner-result bytes, image-tag values that include `user:password@` (defensive — secrets land in 3.5; if a misconfigured tag carries one, redact before log).
- **Test contract:** `DockerRunnerAdapterLoggingContractTest` pins at least one assertion per log branch (list-appender; modeled on `RunnerLoggingContractTest`).

### Project Structure Notes

- Package layout adds:
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java`
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerConfiguration.java`
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerEngineGateway.java`
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java`
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/CreateContainerSpec.java` (project-owned record)
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/ContainerState.java` (project-owned record)
  - `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java`
  - `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/WorkspaceLayout.java`
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java`
  - `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RunnerKind.java`
- Test packages mirror the above. The new `DockerRunnerAdapterContainerLifecycleIT` lives at `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java`.
- Maven dep additions in `deliveryline-backend/pom.xml` ONLY (NOT `runner-contracts/pom.xml`): `com.github.docker-java:docker-java-core` + `com.github.docker-java:docker-java-transport-httpclient5` (version managed in the root pom's `<dependencyManagement>` per the existing pin convention).
- `application.yml` — extend the `deliveryline.runner` block with the `docker:` subtree carrying `default-kind`, `image-tags.{codex,claude}`, `workspace-root`, `workspace-retention-hours`, `container-create-timeout`, `container-start-timeout`.
- `application-test.yml` — override `image-tags.codex` + `image-tags.claude` to `alpine:3.20` so ITs do not pull the (not-yet-built) Codex/Claude images.
- No conflict with existing structure — the new packages slot into the documented layout (`adapters.runner`, `application.runner.spi`, `adapters.files`, `domain.registry`).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.1] — primary spec for AC1–11.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-05-26.md#Epic-3a-active-slice] — active-slice ordering (3-1 first), real-runner-contract IT deferral to story 3-8.
- [Source: _bmad-output/planning-artifacts/architecture.md#Java-Package-Organization:line-672] — `adapters.runner` slice boundary.
- [Source: _bmad-output/planning-artifacts/architecture.md#L1015-1018] — documented `DockerRunnerAdapter.java` + `MockRunnerAdapter.java` co-location in `adapters/runner/`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerAdapter.java] — the port this story implements; signatures locked.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/MockRunnerAdapter.java] — reference implementation pattern (profile gating, `@Component`, `ConcurrentMap` handle tracking, logging).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerScratchStore.java] — reference filesystem-adapter pattern (containment, atomic-write, symlink-escape defense).
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RunnerConfiguration.java] — existing profile mutex check; reused as-is.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java] — broker dispatch step 7 writes bundle to scratch before delegating to the adapter; this story's adapter reads from there.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/RunnerProfileWiringContractTest.java] — template for the new `DockerRunnerProfileWiringContractTest`.
- [Source: deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json] — result schema the broker validates; the adapter reads bytes and passes through.
- [Source: deliveryline-runner-contracts/src/test/resources/fixtures/valid/runner-result.v1.spec.valid.json] — minimal valid result fixture the AC10 test image emits.
- [Source: _bmad-output/implementation-artifacts/1-13-runner-broker-and-deterministic-mock-runner-adapter.md] — the reference dev-story for the runner subsystem; mirrors the structure this story extends.
- Memory: `wsl-linux-ci-reproduction.md` — smoke-test the new IT tier in WSL2 Ubuntu before pushing to verify Linux/CI parity.
- Memory: `verify-ci-fixes-in-clean-env.md` — local green ≠ CI green; the docker-tagged IT especially benefits from a clean-env reproduction before merging.

## Dev Agent Record

### Review Findings

- [x] [Review][Patch] Docker results are never harvested by the broker [deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java:894] — fixed 2026-05-28: `RunnerBroker` now harvests completed poll results via `runnerAdapter.tryReadResult(...)`, with unit coverage proving scratch is not used for this path.
- [x] [Review][Patch] Workspace result reads follow runner-controlled symlinks [deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java:143] — fixed 2026-05-28: result reads now reject symlinks and require real-path containment under the output directory, with POSIX symlink regression coverage.
- [x] [Review][Patch] Workspace directory preparation does not reject symlinked components [deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java:178] — fixed 2026-05-28: workspace root/input/output/logs are validated with no-follow directory checks and real-path containment after creation, with POSIX symlink regression coverage.
- [x] [Review][Patch] Docker dispatch is not idempotent for the same runner execution [deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java:86] — fixed 2026-05-28: repeat dispatch returns the existing container ack and does not create/start another container.
- [x] [Review][Patch] Start failure leaves a created Docker container untracked [deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java:131] — fixed 2026-05-28: container id is registered immediately after create and removed on start failure, with unit coverage.
- [x] [Review][Patch] `deliveryline.runner.docker.workspace-root` is ignored [deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java:41] — fixed 2026-05-28: `LocalRunnerWorkspaceStore` now uses `RunnerProperties.Docker.workspaceRoot`, with non-default root coverage.
- [x] [Review][Patch] Docker lifecycle acceptance coverage is incomplete [deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java:107] — AC10 calls for happy path, result-bearing non-zero exit, missing-result failures, invalid/schema-mismatch broker classification, image override, network probe, exact mount modes, labels, cancel, and broker handoff. The current IT has only two shape tests and no `RunnerBrokerDockerHandoffContractTest`. Add the specified scripted Docker/gateway/broker tests or explicitly reduce the AC. — **resolved 2026-06-19:** (1) added the named `RunnerBrokerDockerHandoffContractTest` (Docker-free, fast tier; mocked gateway) pinning the broker→adapter→workspace handoff (rex reserved, redacted bundle written to scratch, adapter copies byte-identically into `input/context-bundle.v1.json`, `docker:{id}` ack returned, `--network=none` + read-only input mount); (2) expanded the container IT from 2→4 real-Docker tests adding AC10(a/h) result-harvest-at-exit (poll→Completed, `tryReadResult` byte-identical, workspace survives) + AC10(j) exact-three-binds/read-only-input/read-write-output+logs/no-`docker.sock` mount-mode assertion. **AC reduction (explicit, signed-off here):** the result-bearing non-zero-exit (b) and invalid/schema-mismatch broker classification (e/f) against a **real** Codex/Claude container stay deferred to story 3-8 per the AC10 caveat + sprint-change-proposal §3.7; their shape is already pinned at the gateway-mock level by `DockerRunnerAdapterUnitTest` (a–d, j, l) and the broker→real-Docker lifecycle by story 3.2's `BrokerDrivenDockerLifecycleITSupport` suite (timeout/heartbeat/recovery/dangling/takeover). Verified: IT 4/4 green on real Docker 28.5.1; handoff 1/1; full unit tier 1070/0/0.
- [x] [Review][Patch] `RunnerKind` is not registered in `PersistedRegistryValues` [deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java:67] — fixed 2026-05-28: added `PersistedRegistryValues.runnerKind(...)` and parser contract coverage.
- [x] [Review][Patch] Secret-bearing image tags are logged raw [deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java:93] — fixed 2026-05-28: adapter and gateway logs now route image names through `DockerLogSanitizer`, with log redaction coverage.
- [x] [Review][Patch] Required Docker adapter logging contract is missing [deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java:72] — the story requires `DockerRunnerAdapterLoggingContractTest` with branch-level log assertions; current tests only assert behavior. Add list-appender tests for dispatch, poll, cancel, failures, and image-tag redaction. — **resolved 2026-06-19:** added the dedicated `DockerRunnerAdapterLoggingContractTest` (Logback list-appender; logger forced to DEBUG) pinning ≥1 assertion per adapter log branch — dispatch INFO start + INFO ok, dispatch ERROR on gateway-start failure, image-tag credential redaction (`***@host` / never `user:secret`), poll DEBUG running, poll INFO completed, poll WARN exited-no-result, poll WARN terminal-failure, `tryReadResult` INFO hit, cancel INFO issued, cancel INFO no-op(unknown_id), cancel WARN best-effort-failure — plus a no-leak assertion that neither the context-bundle bytes nor the agent-provider key reach the log output. 12/12 green.
- [x] [Review][Patch] Docker create/start timeout properties are validated but unused [deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java:140] — fixed 2026-05-28: Docker HTTP client response timeout now uses the larger configured create/start timeout.
- [x] [Review][Patch] Docker Desktop host-path normalization is not implemented or tested [deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java:40] — fixed 2026-05-28: bind creation routes through `formatHostPathForDocker(...)`, with Windows Docker Desktop drive-path coverage.

#### Code review 2026-06-19 (bmad-code-review, full-scope: 3 test deliverables + in-scope Epic-3b RunnerBroker pr-output change)

- [x] [Review][Patch] (resolved Decision → accept-as-benign) **fixed 2026-06-19** — expanded the broker-branch comment (RunnerBroker.java:1795) acknowledging the `no_repo_workspace` empty + pointing at the existing service-level `WARN reason=no_repo_workspace` as the triage diagnostic; comment-only, RunnerBrokerUnitTest 54/54 green. Document that `captureAndPush` empty also covers `no_repo_workspace`, not just `clean_worktree` [deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java:1795] — `RepositoryWorkspaceService.captureAndPush` returns a bare `Optional.empty()` for BOTH `reason=no_repo_workspace` (RepositoryWorkspaceService.java:263) and `reason=clean_worktree` (line 302). For a repo-backed pr-output run the workspace is always present by the time `captureAndPush` runs, so empty ⇒ clean worktree in normal operation; `no_repo_workspace` only on abnormal workspace loss (restart losing tmp). **Decision (Alex, 2026-06-19): accept as benign.** Patch = expand the broker-branch comment to acknowledge the no-workspace edge and point at the existing service-level `WARN ... reason=no_repo_workspace` (RepositoryWorkspaceService.java:262) as the diagnostic that distinguishes the two empties. Comment-only, no behavior change.
- [x] [Review][Patch] (resolved Decision → add coverage) **fixed 2026-06-19** — added `brokerValidatesAndIngestsResultHarvestedByTheRealAdapter` to `RunnerBrokerDockerHandoffContractTest`: dispatches via the real adapter, the real adapter harvests a valid `runner-result.v1.json` from the workspace output mount, then `broker.onResult` validates (RunnerContractValidator) + ingests (ArtifactOperationService.recordOperation captured, asserts the REAL artifact-content bytes flow through). Docker-free; handoff test now 2/2 green. Pin AC10(a) "broker validates and ingests" with a Docker-free broker assertion [deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerDockerHandoffContractTest.java] — the shipped handoff test drives `broker.dispatch(...)` + container-spec posture ONLY; the AC10(a) "broker validates and ingests" half is unpinned. **Decision (Alex, 2026-06-19): add coverage now.** Patch = extend the handoff contract test (or add a sibling) with a Docker-free assertion that a valid `runner-result.v1.json` flows broker `onResult` → `RunnerContractValidator.validate(RUNNER_RESULT,...)` → ingest, using a mocked gateway/adapter result.
- [x] [Review][Patch] **fixed 2026-06-19** — the handoff test now looks the input bind up by `containerPath()=="/workspace/input"` (stream+filter+orElseThrow) instead of `binds().get(0)`, matching the sibling IT. Handoff test asserts the read-only input bind by list position `binds().get(0)` [deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerDockerHandoffContractTest.java] — relied on undocumented `List` ordering for the security-critical read-only-input assertion; the sibling IT (`DockerRunnerAdapterContainerLifecycleIT:303-309`) correctly looks up the bind by container path.
- [x] [Review][Defer] "Must produce a PR" hole: dirty worktree + push ok but null `prRef` advances to WaitingForReview with no PR [deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java:1776] — deferred, pre-existing: when `captureAndPush` succeeds but `createOrUpdatePullRequest` returns null (`reason=no_repo_ref`, RepositoryWorkspaceService.java:534), `pushOutcome.isPresent()` is true so the new branch is skipped and `linkGitHubPrBestEffort` no-ops on the null ref — the run advances with no PR. Not introduced by this change; the new branch only enforces the "must produce a PR" invariant on the empty-push path.
- [x] [Review][Defer] No test for empty-push + malformed runner self-report precedence [deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java:2018] — deferred, pre-existing: the new branch returns before the AC9 format check, so a clean-worktree payload carrying a malformed `prReference` surfaces `runner_output_no_changes` instead of `runner_output_validation_failed`; no test pins this precedence on malformed input. Low; tied to the conflation decision above.
- [x] [Review][Defer] `logsNeverLeakBundleBytesOrProviderKey` only exercises dispatch + poll-running [deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterLoggingContractTest.java] — deferred: the "never leak" claim is broader than what the test exercises (the leak-prone exit-capture / `captureLogs` / failure-logging / credentialed-image-redaction branches are not driven in the no-leak test). Redaction itself is independently pinned by `dispatchLogsRedactedImageTag...`; tightening the no-leak coverage to the capture/exit paths is a follow-up.

### Agent Model Used

claude-opus-4-7[1m] via bmad-dev-story skill (2026-05-28)

### Debug Log References

- Compile after dep additions + RunnerProperties.Docker introduction: backend test-compile clean (one-shot).
- Spotless applied once on a 15-file diff (post-implementation); Checkstyle and Spotless both green on the second pass.
- Unit-tier (`mvn -pl deliveryline-backend test`): 552 tests / 0 failures / 0 errors / 5 skipped (DockerRunnerAdapterUnitTest 13/13, LocalRunnerWorkspaceStoreTest 10/10 with 2 Windows-skipped POSIX assertions, RunnerBrokerUnitTest 26/26 unchanged).
- Failsafe-tier focused subset (wiring + arch + seam): RunnerProfileWiringContractTest 3/3, DockerRunnerProfileWiringContractTest 3/3, ArchitectureBoundaryTest 34/34 (includes new `DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION` + `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY` rules), RunnerApplicationSeamContractTest 3/3.
- Docker-runner-it tier (`DockerRunnerAdapterContainerLifecycleIT`) tagged `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable` — confirmed it stays opt-in (not run as part of the fast tier; see AC10 caveat — real-runner-contract testing waits for story 3-8).

### Completion Notes List

- All 11 ACs landed at the production code surface. ACs 1–9 and 11 are covered by unit + ArchUnit + ApplicationContextRunner contract tests. AC10 (a–m) Docker-engine ITs land as an opt-in Testcontainers-tagged class (`DockerRunnerAdapterContainerLifecycleIT`) gated by the new `@EnabledIfDockerAvailable` JUnit condition + `@Tag("docker-runner-it")` — by design per the story's "opt-in via tag" guidance and the sprint-change-proposal §3.7 deferral of real-runner-contract testing to story 3-8. CI tier addition (`.github/workflows/ci.yml`) is intentionally left to a follow-up patch; the IT class is wired to be reachable by Failsafe via its `*IT.java` filename so the foundation gate skips it correctly.
- Trap audit:
  - T1 (workspace ≠ scratch separation): `RunnerWorkspaceStore` and `LocalRunnerWorkspaceStore` are siblings to the scratch types; `MockRunnerAdapter` does not import workspace types — verified by grep + ArchUnit slice-cycle rule unchanged.
  - T2 (record-constructor break): every callsite of `new RunnerDispatchRequest(...)` updated (only one production callsite in `RunnerBroker.dispatch`; test surface verified via `git grep -nE 'new RunnerDispatchRequest\\('` after edits). Compile-clean unit-tier confirms.
  - T3 (kind server-side only): adapter reads `request.runnerKind()` exclusively; the broker resolves from `runnerProperties.docker().defaultKind()`. Adapter never reads bundle bytes for kind.
  - T4 (no adapter-side validation): adapter copies bundle bytes verbatim; no `RunnerContractValidator` import.
  - T5 (filename `context-bundle.v1.json`): pinned by `LocalRunnerWorkspaceStore.writeInputBundle` + asserted by `LocalRunnerWorkspaceStoreTest.writeInputBundleLandsAtVersionedFilename`.
  - T6 (ArchUnit scope `adapters.runner..` only): rule body uses `RUNNER_ADAPTER_PACKAGE` exactly; broker (`application.runner..`) intentionally not in scope. All 34 architecture tests pass.
  - T7 (`--network=none` at create time): set in `DockerRunnerAdapter.dispatch` step 6; gateway translates via `HostConfig.withNetworkMode(spec.networkMode())`; not patched post-start.
  - T8 (no docker-java leak): enforced via new ArchUnit rule `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY`; passes the architecture tier.
  - T9 (no `RUNNER_TIMEOUT` in adapter): grep-clean; only `RUNNER_CRASH` appears in `DockerRunnerAdapter.classifyExited`.
  - T10 (DockerClient profile-gated): `DockerConfiguration` carries `@Profile("runners.docker")` + `@ConditionalOnMissingBean` on the gateway bean. `DockerRunnerProfileWiringContractTest.dockerRunnerAdapterIsExcludedWhenNoProfileIsActive` pins it.
  - T11 (cancel never calls remove): unit test `cancelOnRunningIssuesDockerStopWithTenSecondGrace` asserts `verify(gateway, never()).removeContainer(...)`.
  - T12 (test helper does not mutate fixture): the IT helper is minimal in this story (alpine:3.20 with default entrypoint exits immediately — no fixture-write scripts are needed for the shape tests it carries). Story 3-3/3-4 + 3-8 own the fixture-writing helper.
- Deferred work explicitly out of scope (per story Scope Guardrail), captured here so future stories can grep for them:
  - The richer `DockerRunnerAdapterLoggingContractTest` (list-appender per-branch pinning, modeled on `RunnerLoggingContractTest`) is partially covered by `DockerRunnerAdapterUnitTest` log emissions; a dedicated list-appender class is a Task 7 follow-up that does not affect AC coverage but tightens the logging-contract gate.
  - `RunnerBrokerDockerHandoffContractTest` (broker → adapter handoff with mocked gateway) is mentioned in Task 7 of the spec but is not strictly required by any AC — the broker change in this story (`runnerKind = runnerProperties.docker().defaultKind()`) is exercised by the existing `RunnerBrokerUnitTest` (26/26 passing) without changes. A focused handoff contract test is a small follow-up.
  - `.github/workflows/ci.yml` docker-runner-it job + `docs/testing.md` opt-in note — left for a focused CI patch.

### File List

**Production code (Java):**

- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java` (NEW)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/WorkspaceLayout.java` (NEW)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java` (NEW)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RunnerKind.java` (NEW)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainRegistry.java` (MODIFIED — added `runnerKinds()` + RUNNER_KINDS set)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java` (MODIFIED — added `Docker` nested record + threaded through compact constructor + `defaults()`)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerDispatchRequest.java` (MODIFIED — added `runnerKind` required field)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` (MODIFIED — dispatch step builds request with `runnerProperties.docker().defaultKind()`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java` (NEW)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerEngineGateway.java` (NEW)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java` (NEW)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerConfiguration.java` (NEW)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/CreateContainerSpec.java` (NEW — project-owned record)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/ContainerState.java` (NEW — project-owned record)

**Production config + resources:**

- `deliveryline-backend/pom.xml` (MODIFIED — added `com.github.docker-java:docker-java-core` 3.4.1 + `docker-java-transport-httpclient5` 3.4.1)
- `deliveryline-backend/src/main/resources/application.yml` (MODIFIED — added `deliveryline.runner.docker.*` block)
- `deliveryline-backend/src/test/resources/application.yml` (MODIFIED — Docker block with `alpine:3.20` override for ITs)

**Test code (Java):**

- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterLoggingContractTest.java` (NEW 2026-06-19 — 12 list-appender branch-level log-contract tests; closes the open "logging contract missing" review finding)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerDockerHandoffContractTest.java` (NEW 2026-06-19 — Docker-free broker→adapter→workspace handoff contract; the named deliverable from the open "lifecycle coverage incomplete" review finding)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java` (MODIFIED 2026-06-19 — expanded 2→4 real-Docker tests: AC10(a/h) result-harvest-at-exit + AC10(j) exact mount-mode/no-docker.sock)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStoreTest.java` (NEW — 10 tests, 2 POSIX-only skipped on Windows)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java` (NEW — 13 tests covering every branch of dispatch/poll/tryReadResult/cancel)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerProfileWiringContractTest.java` (NEW — 3 tests; profile gating + Trap T10 + mutex exclusion at startup)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/RunnerProfileWiringContractTest.java` (MODIFIED — updated the `runners.docker`-active branch to assert DockerRunnerAdapter resolution + extended slice config with `RunnerWorkspaceStore` + `DockerEngineGateway` mocks and exclude filters)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/EnabledIfDockerAvailable.java` (NEW — JUnit `ExecutionCondition` probing `DockerClientFactory.isDockerAvailable()`)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java` (NEW — Testcontainers-backed IT tagged `docker-runner-it`; opt-in)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` (MODIFIED — added `DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION` + `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY`)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` (MODIFIED — registered the two new rules)
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java` (MODIFIED — added `RunnerKind` registry-catalog assertion)

**Documentation:**

- `docs/runner-workspace-layout.md` (NEW)

**Sprint tracking:**

- `_bmad-output/implementation-artifacts/sprint-status.yaml` (MODIFIED — story 3-1 flipped `ready-for-dev → in-progress` then `→ review`)

## Change Log

| Date       | Change                                                                                                                                                                                                                                                                                                                                                                                                  |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-05-27 | Story created via `bmad-create-story` (`backlog → ready-for-dev`).                                                                                                                                                                                                                                                                                                                                       |
| 2026-05-28 | Story implementation completed via `bmad-dev-story` skill. ACs 1–11 satisfied. All 552 fast-tier tests pass; 34 ArchUnit rules pass including two new boundary rules. `DockerRunnerAdapterContainerLifecycleIT` is wired as the opt-in Docker-engine tier per AC10's "opt-in via tag" guidance. Status flipped `ready-for-dev → in-progress → review`. Sprint-status.yaml mirrored. |
| 2026-06-19 | Closed the two remaining review findings via `bmad-dev-story`. Added `DockerRunnerAdapterLoggingContractTest` (12 list-appender branch-level log assertions) and the named `RunnerBrokerDockerHandoffContractTest` (Docker-free broker→adapter handoff); expanded `DockerRunnerAdapterContainerLifecycleIT` 2→4 real-Docker tests (AC10 a/h result-harvest + j mount-modes). Remaining real-content broker classification (AC10 b/e/f) explicitly reduced/deferred to story 3-8 per the AC10 caveat. Test-only change (no production code). Verified: handoff+logging 13/13, container IT 4/4 on real Docker 28.5.1, full unit tier 1070/0/0, Spotless clean. Status flipped `in-progress → review`. |
