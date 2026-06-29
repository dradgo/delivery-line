# ADR 0031 — Remote Runner Architecture & Full-Access Execution Boundary

**Status:** Proposed (2026-06-29) — to be confirmed during Epic 3k story creation (3k-1..3k-4); **requires a dedicated security review** before full-access execution wiring
**Driver:** Epic 3k (PRD FR86 remote runner execution, FR87 full-access runner permitted only on an isolated remote runner; plus a Kimi-agent feasibility spike). Pilot work needs agents with full filesystem/network access, which is unsafe on the orchestrator host. Execution must move off-host into an isolated VM, and the full-access runner must be confined to that boundary.

## Context

Today `DockerRunnerAdapter` runs every runner as a **local Docker container on the orchestrator host**, and the codex runner is **read-only sandboxed** (`bwrap`/`seccomp` HostConfig). The `runner_executions` queue (stories 3.17a/3.17b) already provides a dequeue-lease with reserve-at-worker, a worker-id lease, a `queueAttemptCount` retry counter, a `heartbeatStaleEmittedAt` liveness signal, and persisted correlation + idempotency carriage (V12/V14). Result ingest + harvest (story 3.6) consume a normalized runner contract. Connector registration precedents exist: the encrypted credential store (ADR 0013), `verifyConnectivity` probes (story 3c-8), and doctor probes (story 3c-10).

The gap: there is no network boundary by which an off-host worker can claim and execute governed runner work, and no runner kind that runs with elevated (unsandboxed) access — nor a guard ensuring such a kind never runs on the orchestrator host.

## Decision

**1. The runner service consumes work by remote queue-pull, not a new push RPC.** The `runner_executions` dequeue-lease path is exposed over an authenticated, transport-secured network boundary so a remote worker can `dequeue` → run → report, preserving reserve-at-worker, lease/heartbeat (`heartbeatStaleEmittedAt`), `queueAttemptCount`, and correlation/idempotency carriage. This reuses the existing backpressure and at-least-once-with-lease semantics rather than building a parallel synchronous dispatch protocol.

**2. The runner service is a standalone, externally-provisioned deployable.** `runner.mjs` + the Docker execution shim are packaged into a standalone service the operator runs inside a VM they provision; it carries `runner-contracts` unchanged. DeliveryLine connects and dispatches but does **not** manage VM lifecycle (create/start/stop/destroy) — VM lifecycle automation is an explicit forward option, not in Epic 3k.

**3. Routing is per-project/per-step, defaulting to local for parity.** An operator registers a remote runner-service endpoint (URL + credential via ADR 0013) with a `verifyConnectivity` probe and a doctor probe. `ProjectRuntimeConfigResolver` decides whether an execution runs on the local `DockerRunnerAdapter` or is left on the queue for a remote worker (the runner-kind 3-layer resolution precedent). Default = local — a project that registers no remote runner is byte-identical to pre-3k.

**4. The full-access runner kind is remote-only and refused on the host.** A new runner kind (e.g. `codex-full`) runs the agent CLI with full filesystem/network access — the `bwrap`/`seccomp` read-only sandbox is deliberately omitted. Dispatching it to the local host is refused with a new `FULL_ACCESS_REQUIRES_REMOTE_RUNNER` error (registry + ProblemDetails + drift, three sites); it is only dispatchable to a registered remote runner that advertises the capability. The VM is the security boundary; the elevated-access disposition is recorded in the governed history.

**5. Remote runners advertise capabilities; routing refuses unsupported kinds.** A registered runner reports which runner kinds it supports (including whether it offers full-access); routing refuses a kind no registered runner supports rather than silently falling back to a sandboxed local run.

**6. Kimi support is a spike, not an implementation.** Epic 3k investigates Kimi as a runner kind (API/CLI surface, auth, `runner-contracts` fit, sandbox/full-access posture, cost/limits, offline-mock feasibility) and outputs a feasibility ADR + go/no-go + a deferred story sketch. No production Kimi runner kind ships in this epic.

## Alternatives Considered

### Alt 1 — Synchronous push RPC (gRPC/REST) from backend to runner service
**Rejected.** It bypasses the queue and re-implements backpressure, retry, lease, and idempotency that the `runner_executions` substrate already provides. Queue-pull reuses all of it; the runner service only needs network access to the dequeue boundary.

### Alt 2 — DeliveryLine provisions and manages the VM
**Rejected for this epic.** VM lifecycle (cloud-provider APIs, secrets, teardown) is a large, provider-specific build that would dominate the epic. Assuming an operator-provisioned VM keeps the scope to the dispatch boundary + the security gate; lifecycle automation is a forward option.

### Alt 3 — Full-access runner allowed anywhere behind a config flag
**Rejected.** A full-access agent on the orchestrator host is a serious security risk regardless of a flag. Confining elevated access to an isolated remote runner makes the VM the enforceable boundary; the local-dispatch refusal is a hard guard, not a toggle.

### Alt 4 — Keep all execution local and just relax the codex sandbox
**Rejected.** Relaxing `bwrap`/`seccomp` on the host gives full access to the orchestrator's filesystem and network — the exact posture the remote boundary exists to prevent.
