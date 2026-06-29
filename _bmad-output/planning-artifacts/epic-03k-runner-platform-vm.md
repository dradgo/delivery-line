## Epic 3k: Runner Platform / VM Execution

The governed workflow moves runner execution **off the orchestrator host**. Today every runner runs as a **local Docker container on the orchestrator host** (`DockerRunnerAdapter`), and the only agent kind that exists is **read-only sandboxed** (`bwrap`/`seccomp` HostConfig). Pilot work needs agents with **full filesystem/network access**, which is unsafe to grant on the orchestrator host — so execution must move into an **isolated, operator-provisioned remote VM**. Rather than invent a new RPC, the remote runner service runs a **worker that pulls from the existing `runner_executions` queue over an authenticated, transport-secured network boundary**, reusing the dequeue-lease + reserve-at-worker + heartbeat substrate (3.17a/b) so backpressure and retry come for free. The backend stays the system of record and git owner; the operator stands up the VM and the runner service, and the backend **connects, registers, and dispatches**. A new **full-access codex runner kind** (which deliberately omits the sandbox HostConfig) is **remote-only**: attempting to dispatch it locally is **refused** with a governed error, and the elevated-access disposition is recorded in the audit history. Separately, the feasibility of a **Kimi** agent runner is investigated as a **spike** — an ADR + go/no-go + a deferred story sketch — with no production Kimi runner shipped.

**Why this epic exists (net-new capability):** Epics 1–3j assume a single execution locality — `DockerRunnerAdapter` spins every runner as a local container, and the codex runner is locked to a read-only `bwrap`/`seccomp` sandbox. There is **no way to run a runner off-host**, and therefore **no safe way to grant an agent full access** (granting it on the orchestrator host would expose the system of record). This epic adds those two capabilities as **new product scope** (FR86/FR87): remote operator-provisioned runner execution over the existing queue, and a full-access runner kind that is permitted **only** on an isolated remote VM. It is the **deepest architectural shift** of the 3g–3l family and is **inserted between Epic 3j and Epic 4** purely for sequencing (avoids renumbering E4–E6). Source: this sprint-change-proposal.

**Highest-risk epic — ADR-0031 + a security review are authored up front.** It is mostly independent of 3g–3j (it extends the 3.17 queue) and can run on a separate track.

**Prerequisites & reused substrates (all done):**
- **Run-execution queue** (story 3.17a substrate + 3.17b activation, V12/V14) — the dequeue-lease + **reserve-at-worker** + `workerId` + `queueAttemptCount` + `heartbeatStaleEmittedAt` stale-lease detection + correlation/idempotency carriage. The remote worker pulls this queue over the network; the lease/heartbeat semantics give no-double-execution under lease expiry **for free** (no new RPC).
- **Local runner execution** (`DockerRunnerAdapter`) — runs runners as local Docker containers on the orchestrator host; packaged into the standalone runner service. Threading a routing dependency through it hits the **ctor-dep fan-out trap** (every `new DockerRunnerAdapter(...)` site + slice tests).
- **Codex sandbox config** — the read-only `bwrap`/`seccomp` HostConfig (security-opts → HostConfig) that the new full-access kind **deliberately omits**; the security boundary moves to the VM.
- **Runner contract + entrypoint** (`runner.mjs` + `runner-contracts`) — transported **unchanged** to the remote worker (heed the `runner-contracts` stale-in-`.m2` trap: install / `-am` before a backend-only test).
- **Raw-output capture + harvest** (story 3.6) — the remote worker reports results through the same contract; backend-side ingest + harvest are unchanged.
- **Runner-kind resolution** (`ProjectRuntimeConfigResolver`, the 3-layer runner-kind precedent) — local-vs-remote routing is resolved here; default = local (parity).
- **Connector substrate for endpoint registration** — 3c-5 encrypted credential store + redaction, 3c-8 `verifyConnectivity` connectivity probe, 3c-10 doctor probe (heed the `checksRun` fan-out trap).

**ADR (proposed):** `docs/adr/0031-remote-runner-architecture.md` — records (a) the **remote queue-pull** protocol (the runner service worker dequeues `runner_executions` over an authenticated, transport-secured network boundary, reusing reserve-at-worker + lease/heartbeat rather than a new RPC) and how no-double-execution is preserved; (b) the **externally-provisioned VM** model (operator runs the VM + runner service; backend connects + dispatches; VM lifecycle automation deferred); (c) the **security boundary** — the VM is the isolation boundary for full-access (unsandboxed) execution, why full-access is refused on the orchestrator host, and the governed audit of elevated access. Author alongside story 3k-1. A **security review** is required for the full-access path (story 3k-3).

### Story List (4 stories)

```
Remote runner platform
3k-1   Remote runner-service extraction — network dequeue protocol + standalone runner-service boundary   [item 14]
3k-2   Runner-service registration + dispatch routing (local vs remote, per project/step)                  [item 14]

Full-access execution
3k-3   Full-access codex VM runner kind — unsandboxed, remote-only, refused on host                        [item 15]

Feasibility
3k-4   Kimi agent feasibility spike (ADR + recommendation)                                                 [item 8]
```

> Story 3k-1 (the network dequeue boundary + standalone runner service) is the foundation. 3k-2 (registration + routing) depends on 3k-1 — it lets an operator register a remote runner-service endpoint and route executions local-vs-remote. 3k-3 (the full-access codex kind) depends on 3k-2's routing + capability advertisement — it is the one kind that is **remote-only** and refused on the host. 3k-4 (Kimi) is an **independent spike** — a time-boxed feasibility investigation with an ADR + go/no-go output, shipping **no production runner kind**. Detailed, reconciled implementation stories live at `{implementation_artifacts}/3k-1..3k-4-...md`.

---

### Story 3k-1: Remote Runner-Service Extraction

As an operator running governed delivery at scale,
I want a runner service that can run in a separate VM and pull governed runner work from the existing queue over an authenticated, transport-secured network boundary,
So that runner execution can move off the orchestrator host without inventing a new dispatch protocol — reusing the queue's lease/heartbeat semantics so work is never double-executed.

**Acceptance Criteria:**

1. **Given** the `runner_executions` dequeue-lease path (3.17a/b), **Then** it is exposed over a **network transport** (authenticated; transport-secured) so a remote worker can `dequeue → run → report result`, preserving **reserve-at-worker**, the lease + heartbeat (`heartbeatStaleEmittedAt`), `workerId`, `queueAttemptCount`, and correlation/idempotency carriage — **no new RPC**, the queue contract is the protocol.
2. **Given** lease semantics under a network boundary, **Then** a remote worker that crashes or stalls (lease expiry / stale heartbeat) has its execution re-dispatched **without double-execution** (the reserve-at-worker + `queueAttemptCount` guard holds across the boundary); a result reported after lease expiry is rejected idempotently.
3. **Given** packaging, **Then** `runner.mjs` + the Docker execution shim (`DockerRunnerAdapter` shape) are packaged into a **standalone runner service** deployable in a VM, carrying `runner-contracts` **unchanged** (heed the install / `-am` stale-in-`.m2` trap).
4. **Given** result ingest, **Then** the 3.6 raw-output capture + harvest are **unchanged** on the backend side — the remote worker reports through the same contract, byte-identical to a local execution's result (parity at the ingest seam).
5. **Given** transport auth, **Then** a worker presenting no/invalid credential is refused at the boundary (credential via 3c-5 posture); nothing secret is logged (ids/lengths only; redaction posture honored).
6. **Given** parity, **Then** with no remote runner registered the system runs **exactly as pre-3k** — every execution stays local on `DockerRunnerAdapter` (byte-identical hot path); the network boundary is dormant until an endpoint is registered (3k-2).
7. **Given** tests, **Then** coverage asserts: remote `dequeue → run → report` round-trip (IT); lease-expiry re-dispatch with **no double-run**; post-expiry result rejected idempotently; transport auth rejected without credential; raw-output/harvest parity vs local; local-only parity; `application.*` ≥80% coverage.

### Story 3k-2: Runner-Service Registration + Dispatch Routing

As an operator,
I want to register a remote runner-service endpoint and choose per project (or per step) whether an execution runs locally or on a remote runner,
So that I can move selected work off-host while everything else keeps running locally — with the default unchanged from today.

**Acceptance Criteria:**

1. **Given** endpoint registration, **Then** an operator registers a remote runner-service endpoint (URL + credential via the 3c-5 encrypted store) which is **connectivity-probed** via `verifyConnectivity` (3c-8); credentials are never exposed (redaction posture honored).
2. **Given** a `runner-service` doctor probe (3c-10), **Then** it reports each registered endpoint's reachability + advertised capabilities; the hardcoded `checksRun` count is incremented at **every** asserting site (heed the doctor-probe fan-out trap).
3. **Given** routing resolution, **Then** per-project / per-step routing decides whether an execution runs **local** (current `DockerRunnerAdapter`) or is left on the queue for a **remote** worker — resolved via `ProjectRuntimeConfigResolver` (the runner-kind 3-layer precedent); **default = local** (parity, byte-identical to pre-3k).
4. **Given** capability advertisement, **Then** a registered remote runner reports **which runner kinds it supports** (including whether it supports the full-access kind); routing **refuses** a kind that no registered runner advertises (a clear error, not a silent local fallback).
5. **Given** the `DockerRunnerAdapter` ctor-dep fan-out trap, **Then** threading the routing dependency through the adapter updates **every** `new DockerRunnerAdapter(...)` site + the slice tests without breaking the local hot path.
6. **Given** read model / observability, **Then** an execution's resolved locality (local vs the named remote endpoint) is recorded + surfaced on the run/step view (no secret in the endpoint label).
7. **Given** tests, **Then** coverage asserts: endpoint registration + `verifyConnectivity` probe; the `runner-service` doctor probe + `checksRun` fan-out; local-default parity; remote routing dispatches to the registered worker; a kind no registered runner advertises is refused; ctor fan-out compiles + slice tests pass; `application.*` ≥80% coverage.

### Story 3k-3: Full-Access Codex VM Runner Kind

As an operator with work that needs full filesystem/network access,
I want a full-access codex runner kind that runs **only** on an isolated remote VM and is refused on the orchestrator host,
So that agents can be granted elevated access where it is safe — never on the system-of-record host — with the elevation recorded in the governed history.

**Depends on:** 3k-1 (remote boundary), 3k-2 (routing + capability advertisement). Sequenced **after 3k-2**. **Security review required.**

**Acceptance Criteria:**

1. **Given** a new runner kind (e.g. `codex-full`), **Then** it runs the codex CLI with **full filesystem/network access** — the read-only `bwrap`/`seccomp` sandbox HostConfig that the standard codex kind applies is **deliberately omitted**; the VM is the isolation boundary (ADR-0031).
2. **Given** the **remote-only gate**, **Then** dispatching `codex-full` to the **local host** is **refused** with a new `FULL_ACCESS_REQUIRES_REMOTE_RUNNER` error code (registry + ProblemDetails catalog + placeholder/pin drift — **three sites**); it is dispatchable **only** to a registered remote VM runner that **advertises** the full-access capability (3k-2 AC4).
3. **Given** capability gating, **Then** a remote runner that does **not** advertise full-access is also refused the kind (the advertisement is the authorization, not the locality alone); a registered full-access-capable remote runner accepts and runs it.
4. **Given** governed audit, **Then** the elevated-access disposition — that this execution ran unsandboxed with full access, on which remote runner — is **recorded in the governed history** (auditable; ids/lengths only, redaction posture honored), so elevated runs are never silent.
5. **Given** parity, **Then** the standard sandboxed codex kind is **byte-identical to pre-3k** (still `bwrap`/`seccomp`, still local-or-remote per routing); the full-access kind is purely additive and dormant until both the kind is selected **and** a full-access-capable remote runner is registered.
6. **Given** redaction + security posture, **Then** the full-access execution's outputs pass the same redaction/secret-fixture gate as any runner output; the security review (ADR-0031) sign-off is recorded.
7. **Given** tests, **Then** coverage asserts: full-access kind runs on a full-access-capable remote runner; **local dispatch refused** with `FULL_ACCESS_REQUIRES_REMOTE_RUNNER` (three-sites drift); a non-advertising remote runner refused; sandboxed-codex parity; the elevated-access audit record is written + surfaced; `application.*` ≥80% coverage.

### Story 3k-4: Kimi Agent Feasibility Spike

As an architect,
I want a time-boxed investigation of running **Kimi** agents as a runner kind,
So that we have a feasibility ADR + a go/no-go recommendation + a sketched story breakdown before committing to build a real Kimi runner — with no production runner shipped by this spike.

**Acceptance Criteria:**

1. **Given** a time-boxed spike, **Then** the investigation covers Kimi's **API/CLI surface**, **auth** model, and how it would be invoked from a `runner.mjs`-style entrypoint.
2. **Given** the `runner-contracts` schema, **Then** the spike assesses **contract fit** — whether Kimi's request/result shape maps to the existing runner-contract (input bundle, raw-output capture, result harvest) or requires additive fields.
3. **Given** the sandbox/full-access posture, **Then** the spike documents whether a Kimi runner would run **read-only sandboxed** (the standard codex posture) or require the **full-access remote-only** path (3k-3) — i.e. which execution locality it lands in.
4. **Given** cost/limits + offline-mock feasibility, **Then** the spike records Kimi's cost/rate-limit profile and whether a **deterministic offline mock** (the dual offline-mock precedent) is feasible for CI.
5. **Given** the output, **Then** the spike produces an **ADR + go/no-go recommendation** + a **sketched story breakdown** for a real Kimi runner kind (deferred); **no production runner kind, no new registry value, no schema change** is shipped by this story.
6. **Given** scope discipline, **Then** the spike is **investigation-only**: no `runner_executions` change, no new error code, no `ProjectRuntimeConfigResolver` change, no foundation-gate drift — the deliverable is documentation + a go/no-go decision.

---

### Cross-Cutting Notes

- **Foundation-gate widening:** the new `FULL_ACCESS_REQUIRES_REMOTE_RUNNER` error code (3k-3) carries the documented **three-sites** drift (`DomainErrorCode` registry + `ProblemDetailsCatalog` + the placeholder manifest / `-Pfoundation-gate` pin). New **runner kind(s)** + **capability advertisement** (3k-2/3k-3) and the **runner-service endpoint registration** (credential-store entry + redaction-corpus gates — the two-gates trap; `verifyConnectivity` probe; `runner-service` doctor probe with the `checksRun` fan-out) are drift-tested at the existing gates — folded into each story, no separate gate story. **3k-4 introduces no foundation-gate drift** (spike only).
- **No new WorkflowState / AllowedAction / WorkflowEventType:** this epic adds **no** workflow state, action, or event type — it is a runner-platform + dispatch-locality change beneath the workflow spine. The only registry addition is one error code (3k-3). The `runner_executions` queue is reused **unchanged** (network transport over the existing dequeue-lease, not a schema change); if any additive column is needed it lands at the **next-free Flyway head**, replay-safe, in `FlywaySchemaContractTest`.
- **Security:** the full-access path (3k-3) **requires a security review** — the VM is the isolation boundary, full-access is refused on the orchestrator host, and every elevated run is recorded in the governed history. ADR-0031 records the security boundary + the review sign-off. Credential-store + redaction gates apply to the runner-service endpoint registration (3k-2).
- **Traps in play:** the `DockerRunnerAdapter` **ctor-dep fan-out** (every `new DockerRunnerAdapter(...)` site + slice tests when threading routing — 3k-2); the `runner-contracts` **stale-in-`.m2`** trap (install / `-am` before backend-only tests — 3k-1); the codex **sandbox HostConfig** is what the full-access kind omits (do not regress the standard kind's `bwrap`/`seccomp`); the doctor-probe **`checksRun` fan-out** (3k-2); the **two-gates** redaction-corpus requirement for new credentials (3k-2).
- **Documentation:** a `docs/remote-runner-walkthrough.md` (stand up a runner-service VM → register the endpoint → route a project's executions remote → run a full-access codex run on the VM → audit the elevated access); new vocabulary (`remote runner`, `runner service`, `full-access runner`, `runner kind capability`) confirmed in `docs/glossary.md` against NFR43 (minimize new concepts — justify each).
- **FRs covered:** **3k-1..3k-2** deliver **FR86** (governed runner work executes on a remote, operator-provisioned runner service); **3k-3** delivers **FR87** (a full-access agent runner is permitted only on an isolated remote runner). **3k-4** is a **spike** — feasibility ADR + go/no-go, shipping no FR. This epic introduces **new PRD scope** (FR86/FR87) — it is not an activation of deferred work.
- **Forward options (out of scope):** VM lifecycle automation (the backend provisions/tears down VMs); a real **Kimi** runner kind (per the 3k-4 go/no-go); remote-runner autoscaling; per-run **ephemeral** VMs; remote routing for non-codex runner kinds; multi-region remote runner pools.
