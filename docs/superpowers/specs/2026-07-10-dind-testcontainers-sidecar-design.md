# Per-run Docker-in-Docker (DinD) sidecar for Testcontainers

**Date:** 2026-07-10
**Status:** design approved, pending spec review
**Depends on:** the runner JDK 21 + Maven 3.9 toolchain work (`docs/superpowers/specs/2026-07-10-runner-jdk-maven-design.md`) — Testcontainers is exercised via `mvn verify`, which needs that toolchain.

## Problem

DeliveryLine runner containers execute agent-authored build commands but have no access to a Docker daemon, so agent-authored integration tests that use **Testcontainers** (`mvn verify` spinning up a MySQL/Kafka/etc. container) cannot run. Granting the host Docker socket would hand agent-authored code root-equivalent on the host and access to every sibling container (including the backend and its Postgres) — unacceptable.

## Goal

Give an opted-in project's **execution-stage** runs a private, throwaway Docker daemon (a per-run privileged `dockerd` sidecar on a per-run network) that Testcontainers can drive, without ever exposing the host socket. Default OFF; zero change for every run that does not opt in.

## Chosen posture (settled before design)

- **Per-run privileged `dockerd` sidecar**, never the host socket.
- **Gating:** a per-project opt-in flag `testcontainers-enabled`, default **false**.
- **Stages:** execution-stage runs only (where the agent runs `mvn verify`); spec/investigation/plan stages are read-only and never build.
- **Failure:** if the sidecar can't be provisioned for an opted-in run, **fail the run fast** with a distinct, retryable failure category — do not run the agent and let `mvn` fail cryptically.
- **Network:** one per-run **user-defined bridge** carrying both runner↔sidecar traffic (embedded DNS: the runner resolves `dind`) and the runner's egress (NAT). The runner is single-homed. A two-network / internal-net shape was rejected: an `internal: true` net would stop the sidecar pulling test images, breaking Testcontainers.

## Non-goals (noted for later)

- No `docker` CLI added to the runner image — Testcontainers (a Java library) talks to `DOCKER_HOST` over TCP; no CLI needed.
- No per-project image cache / warming.
- The CI/build stage (story 3h-5, backlog) reuses this seam once it lands; not wired here.

## Architecture

For an execution-stage run whose project has `testcontainers-enabled = true`, the adapter provisions a per-run network + privileged `dockerd` sidecar **before** launching the runner, wires the runner to it, and tears both down on every exit path. Flag off ⇒ **byte-identical** to current behavior (no network, no sidecar; runner stays on the configured `bridge`).

### Components (each one responsibility)

1. **`CreateContainerSpec`** (`adapters.runner.docker`) — gains `boolean privileged`, `List<String> networkAliases`, an optional memory-limit, and an optional healthcheck (command + interval/retries) used only by the sidecar. Back-compat constructors preserve every existing call site (defaults: `false` / empty / none). Only value-record change.
2. **`DockerEngineGateway` + `DefaultDockerEngineGateway`** — gain `createNetwork(String name, Map<String,String> labels) → String id` and `removeNetwork(String name)` (docker-java `createNetworkCmd`/`removeNetworkCmd`), and map the two new `CreateContainerSpec` fields to `HostConfig.withPrivileged(...)` + the network-attach alias. `ContainerState` (returned by `inspectContainer`) gains a `healthStatus` field (`"healthy"`/`"starting"`/`"unhealthy"`/`null`) to back the readiness poll. The `com.github.dockerjava.*` surface stays confined to this class (existing ArchUnit boundary, trap T8).
3. **`DindSidecarService`** (new, `application.runner`) — sole owner of sidecar lifecycle:
   - `provision(String rexId) → DindHandle`: create network `deliveryline-net-<rex>`; start the privileged `docker:<pin>-dind` sidecar aliased `dind` on it; poll readiness; return `DindHandle(networkName, Map<String,String> envToInject)`.
   - `teardown(String rexId)`: stop+remove the sidecar (which kills its nested test containers), then remove the network. Idempotent, best-effort.
   - Sidecar labeled `deliveryline.dind=<rex>` and network named `deliveryline-net-<rex>` so the sweep can find orphans.
4. **`DockerRunnerAdapter`** — the integrator. Gated `provision` before creating the runner container; attaches the runner to the per-run network; injects the env; calls `teardown` on every exit path. Fail-fast on `provision` failure (see below).
5. **`RunnerWorkspaceCleanupJob` / dangling sweep** — extended to reap orphan `deliveryline.dind=<rex>` sidecars and `deliveryline-net-<rex>` networks whose run row is terminal/absent.

The runner image needs **no change**.

## Lifecycle & data flow

### Provision (before the runner starts)
1. `createNetwork("deliveryline-net-<rex>", {deliveryline.dind: <rex>})` — user-defined bridge (DNS + NAT egress).
2. Start sidecar: image `docker:<pin>-dind`, **privileged**, with `networkMode = deliveryline-net-<rex>` and `networkAliases = ["dind"]`, env `DOCKER_TLS_CERTDIR=""` (plaintext daemon on `2375` — TLS cert-sharing buys nothing on an ephemeral per-run isolated net), anonymous volume at `/var/lib/docker`, labeled `deliveryline.dind=<rex>`, a Docker **healthcheck** (`CMD-SHELL docker -H tcp://localhost:2375 version`), and a configurable memory cap (default 2 GiB).
3. **Readiness gate:** poll `inspectContainer(sidecar).healthStatus` until `"healthy"` or `readiness-timeout` (default 60 s). The healthcheck runs inside the sidecar, so the daemon is never exposed on the host to probe it. This catches privileged-daemon boot flakiness.
4. Return `DindHandle(networkName, env)`.

### Dispatch
The adapter attaches the **runner** to the per-run network by setting the runner container's `networkMode = deliveryline-net-<rex>` (instead of the configured `bridge`) — the runner needs no alias — and injects, only when enabled:
- `DOCKER_HOST=tcp://dind:2375`
- `TESTCONTAINERS_HOST_OVERRIDE=dind` — Testcontainers maps published ports to `dind:<port>` (the sidecar's address on the shared net), not `localhost`.
- `TESTCONTAINERS_RYUK_DISABLED=true` — the sidecar is nuked at teardown, so Ryuk's reaper is redundant and misbehaves against a remote daemon.

The agent runs `mvn verify`; Testcontainers starts its containers inside the sidecar's daemon, reachable over the shared network.

### Teardown (every exit path: success, failure, timeout, crash, dispatch error)
`teardown(rex)`: stop+remove the sidecar (kills nested test containers automatically), then remove the network. Idempotent, best-effort — a teardown error is logged, never masks the run outcome. Ordering: runner stopped/removed first (existing path), then sidecar, then network (a network can't be removed while a container is attached).

### Fail-fast
If `provision` fails (network create, sidecar start, or readiness timeout): clean up any partial resources, record the run **FAILED** with `TESTCONTAINERS_INFRA_FAILED`, emit `RUNNER_FAILED`, and do **not** dispatch the agent. Cheap, diagnosable, retryable.

## Config, flag, failure category, cleanup

### Config & flag (mirroring existing patterns)
- **Project model** gains `testcontainersEnabled` (default false), resolved per-run via `ProjectRuntimeConfigResolver.resolveTestcontainersEnabled(workflowRunId)` — twin of the existing `resolveOpenSpecEnabled`. A Flyway migration adds the column; the resolver falls back to the default project. This mirrors the openspec-enabled flag fan-out (project entity/mapper/DTOs, create/update commands, REST, FE edit surface).
- **Sidecar tunables** (dind image tag e.g. `docker:27-dind`, memory cap, readiness timeout) land as a **separate `@ConfigurationProperties`** (not components on the `RunnerProperties.Docker` record — same 8-site fan-out avoidance as the Maven-cache work). `application.yml` and the test `application.yml` both get the keys; a validated `@ConfigurationProperties` requires the test yaml updated (known trap).

### New `FailureCategory.TESTCONTAINERS_INFRA_FAILED`
The fail-fast transition happens from `EXECUTING`, so the category must be added to `WorkflowTransitionTable.ALLOWED_RUNNER_FAILURE_CATEGORIES` **and** its foundation-contract mirror `TransitionTableCrossProductFoundationContract` — the exact two-site edit done for `RUNNER_SECRET_LEAK`. Classify it in `RecommendationService` as retryable (a runner infra failure). Adding a new `FailureCategory` enum value may also ripple to any exhaustive `switch` over the enum (e.g. `RunnerBroker`'s duplicate-result classifier) — audit those.

### Cleanup / orphan reaping
The dangling sweep already reaps orphan runner containers by label; extend it to also remove `deliveryline.dind=<rex>` sidecars and `deliveryline-net-<rex>` networks whose run is terminal/absent. Sidecar-then-network ordering holds in the sweep too.

## Testing

- **Unit — `DindSidecarService`** (mocked gateway): `provision` creates network+sidecar and polls readiness; a readiness timeout throws and cleans up the partial network/sidecar; `teardown` removes both and is idempotent.
- **Unit — `DockerRunnerAdapter`**: flag on ⇒ per-run network attached + env injected + `provision` called + `teardown` on every exit; **flag off ⇒ byte-identical** (no network, no sidecar, `networkMode` unchanged); `provision` failure ⇒ run FAILED + `TESTCONTAINERS_INFRA_FAILED` + agent NOT dispatched.
- **Unit — transition table**: `EXECUTING → FAILED` with `TESTCONTAINERS_INFRA_FAILED` is allowed (mirror the `RUNNER_SECRET_LEAK` test).
- **Integration (`docker-runner-it` tier)**: provision a real dind sidecar on a real per-run network; a minimal client container runs `docker -H tcp://dind:2375 version` (or a tiny Testcontainers test) proving the seam end-to-end; assert `teardown` removes sidecar + network. A separate sweep IT reaps an orphan sidecar+network.

## Success criteria

- An opted-in execution run: the agent's `mvn verify` with a Testcontainers-based test passes, the test's container ran inside the sidecar's daemon (never on the host), and after the run no `deliveryline-net-<rex>` network or `deliveryline.dind=<rex>` container remains.
- A non-opted-in run is byte-identical to current behavior (no sidecar, no network, `bridge` network mode).
- An opted-in run whose sidecar fails to become ready ends FAILED with `TESTCONTAINERS_INFRA_FAILED`, no agent dispatched, no leaked network/sidecar.
- The host Docker socket is never mounted into any runner or sidecar; Testcontainers containers are never visible on the host daemon.
