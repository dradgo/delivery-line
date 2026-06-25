# ADR 0025 — Live Execution Observability & Read-Only Diagnostic Console

**Status:** Accepted (signed off 2026-06-22, Alex — workflow owner / security reviewer; story 3d-6) — was Proposed (2026-06-21), **security-review-gated** before any console wiring (mirrors the ADR 0013 sign-off pattern)
**Driver:** Epic 3d (PRD FR65, FR68). Operators need to watch a step's container logs while it runs and after it finishes, and to open a console into a running runner to diagnose a stuck step. Both reach **inside** the runner sandbox, which is deliberately closed today — so this ADR records exactly how far the posture is narrowed and the threat model that bounds it.

## Context

The runner sandbox is `--sandbox read-only`, no-leak, and every action is appended to governed history (architecture security posture; ADR 0003). Secret redaction (`RunnerSecretScanService`, story 3.6) is **post-hoc** — it scans a *completed* runner log before the bundle/log is persisted. Two Epic 3d capabilities reach inside that boundary while a step is live:

- **Live logs (FR65):** following `docker logs -f` streams output *before* the post-hoc scan can run, so a live stream can momentarily surface a secret the post-hoc scan would have caught.
- **Read-only console (FR68):** attaching a shell to a running runner is, by nature, interactive access into the sandbox.

Epic 4's failure-diagnostics view (story 4.4) already planned a *post-hoc redacted log download*. Epic 3d's live viewer supersedes the need for a second download surface — 4.4 should **consume** the 3d viewer rather than re-derive one.

## Decision

**1. The console is READ-ONLY and LIVE-ONLY.** It attaches only to a *running* runner container and exposes a non-mutating, inspection-only shell — no write to the workspace, no host shell, no access to a finished/absent container. Write-capable shells, host shells, and ungoverned access are explicitly out of scope. This **narrows but does not remove** the sandbox posture.

**2. Every console session is recorded in governed history.** Opening a console appends a governed event (operator identity, runner-execution id, timestamps); the session is first-class audit, not an ungoverned back door. Access is gated by backend-reported allowed-actions like every other operator action.

**3. Live-stream redaction posture: localhost + single-operator, with documented residual risk.** Live log streaming and the console run **only** over the existing localhost-only REST binding to the single local operator — the same trust boundary that already governs every other surface. Best-effort streaming redaction is applied where feasible, but the authoritative guarantee remains: the **persisted** log/bundle is post-hoc scanned (story 3.6) and is what enters shareable/export channels. The live view is an operator-only, in-the-moment surface; the residual "a secret may flash in a live stream to the local operator" risk is accepted and recorded here, consistent with the ADR 0013 stance that host/local-operator access is already trusted.

**4. No new persisted log store.** The viewer streams from the runner (Docker) and reads the already-persisted post-hoc-redacted log for the finished case; it does not introduce a second raw-log table. Console session *metadata* (the governed events) is persisted; console *I/O* is not durably stored.

**5. Epic 4 consumes, does not duplicate.** Story 4.4's diagnostics view links to this live/finished viewer instead of building a separate redacted-log download; this is enumerated as a downstream amendment in the Epic 3d proposal.

### Threat model

- **Defends:** **export / shareable-channel leakage.** Nothing the live view shows changes what is persisted or exported — the post-hoc scan still governs durable + shareable log content. Console access is audited, allowed-action-gated, and read-only, so it cannot mutate a run or the workspace.
- **Does NOT defend:** **the local operator seeing a secret in-the-moment.** A secret may transiently appear in a live stream or a read-only console to the single local operator before post-hoc redaction would have masked it. This is the deliberate, recorded limit — it is the same trust boundary (host/local operator is trusted) that ADR 0013 and the localhost-only REST posture already accept. It does **not** extend that trust to remote/multi-user access, which remains out of scope.

### Security-review sign-off (story 3d-6, AC1)

Signed off **2026-06-22** by **Alex (workflow owner / security reviewer)**. No CI job enforces this; the sign-off is this recorded artifact (mirrors the ADR 0013 gate), also recorded in the story 3d-6 Completion Notes + PR description.

The 3d-6 console implementation ships the **input-disabled** read-only design (DD-1 / OQ-1), which is **stronger** than the ADR's baseline "read-only inspection shell": the docker attach is opened **without stdin** (`attachContainerConsole` never calls `withStdIn`), and the frontend transport is a receive-only `EventSource` with **no input control** — so no input path exists end-to-end. Non-mutation is therefore *provable* (no write channel exists) rather than merely *policy-enforced*. The reviewed posture, as implemented and asserted by tests:

- **Read-only + input-disabled (Decision 1):** no stdin at the docker layer, no input widget on the UI; asserted by `DefaultDockerEngineGatewayTest` (`withStdIn` never invoked) + `ReadOnlyDiagnosticConsole.test.tsx` (no `input`/`textarea`).
- **LIVE-ONLY (Decision 1):** a terminal/absent runner execution is rejected with `console-not-live`; no attach engaged, no `console.opened` appended (DD-3) — asserted by `DiagnosticConsoleServiceTest`.
- **Governed history (Decision 2):** `console.opened`/`console.closed` appended on open/close, carrying only allow-listed `runnerExecutionId`/`workflowRunId` keys; console I/O is **not** durably stored.
- **Allowed-action gated (Decision 2):** `open_diagnostic_console`, offered only in `EXECUTING` to `workflow_owner`, enforced **server-side** on the endpoint (not just the UI) — asserted by `RunnerDiagnosticConsoleControllerTest`.
- **Localhost-only (Decision 3):** served only over the existing `127.0.0.1` binding + `RestBindingGuard`; the endpoint adds no binding of its own — asserted by the no-own-binding test.
- **Best-effort live redaction, export unchanged (Decision 3/4):** per-chunk best-effort redaction before chunks leave the server; nothing the console shows changes persisted/exported content (it never writes `runner-logs/` nor mutates `runner_executions`). The **accepted residual risk** — a secret may transiently flash to the single local operator before post-hoc redaction — is unchanged and remains bounded to the already-trusted localhost/host-operator boundary; remote/multi-user access stays out of scope.

The interactive (write-forwarded) console of Alt 2 remains **deferred** to a future story; any move toward input forwarding requires a fresh review.

### Amendment — story 3e-5 (Investigating/spec-generation coverage)

Story 3e-5 extends **both** affordances — the live log viewer (`view_runner_logs`) and the read-only diagnostic console (`open_diagnostic_console`) — to cover the **`Investigating` (spec-generation) state** in addition to `Executing`. `Investigating` is a live-container state (the spec runner is dispatched on `INBOX → INVESTIGATING`, story 3a-1), so a container is live and producing output there exactly as in `Executing`; the streaming endpoints are stage-agnostic and already served it — only the allowed-action matrix arm withheld the affordances. The change is a single matrix widening (`WorkflowInspectionService.baseActionMatrix` `INVESTIGATING` arm) plus a frontend relocation of the two surfaces below the Decision Bar.

The **security posture is identical** to `Executing`: the console stays read-only/input-disabled (no stdin at the docker layer, receive-only `EventSource` on the UI), owner-only (`open_diagnostic_console` for `workflow_owner` only; `view_runner_logs` role-agnostic), LIVE-ONLY (liveness re-checked at attach → `console-not-live` if absent), governed-history-audited, localhost-only, and best-effort-live-redacted with the same accepted residual risk. Because nothing about the threat model, the trust boundary, or the input/persistence design changes — only the *set of states* the unchanged affordances are offered in — **no new security sign-off gate is introduced** (contrast the 3d-6 AC1 sign-off that originally ratified the input-disabled console design, which still governs). ADR status is unchanged (remains Accepted).

## Alternatives Considered

### Alt 1 — Post-hoc redacted log download only (the Epic 4 / story 4.4 baseline), no live view
**Rejected.** Operators cannot watch a step progress or diagnose a stuck live run; FR65/FR68 explicitly require the live capability.

### Alt 2 — Full interactive (write-capable) shell into the runner
**Rejected.** Breaks the read-only sandbox posture and the "every action governed" principle, and would make the console a way to mutate a run outside the state machine. Read-only diagnostic attach is the proportionate scope.

### Alt 3 — Real-time streaming redaction as the authoritative guarantee
**Rejected as the guarantee (kept as best-effort).** A streaming redactor cannot match the completeness of the post-hoc scan over a full log; promising it as the guarantee would overstate protection. The persisted post-hoc scan remains authoritative; streaming redaction is best-effort over a localhost-only, single-operator surface.

## Consequences

### Positive
- Operators can follow a live step and diagnose a stuck runner read-only, with full audit of console access.
- Export/shareable guarantees are unchanged — the durable, post-hoc-scanned log is still what leaves the box.
- Epic 4 reuses one viewer instead of maintaining two log surfaces.

### Negative
- A transient in-the-moment secret exposure to the local operator is possible on the live stream — accepted + documented, not eliminated.
- Console + live streaming add a websocket/SSE surface that must respect the localhost-only binding and allowed-action gating.

### Neutral
- This ADR does not close (and console wiring, 3d-6, does not start) until a security review signs off the threat model — sign-off recorded in the story Completion Notes + PR description, as with ADR 0013 (no CI job exists).
- Console I/O is not durably stored; only governed session metadata is.

## References
- [Source: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-06-21.md`] — Epic 3d proposal (D3, Risk #1); FR65, FR68.
- `docs/adr/0003-runner-secrets-mvp-posture.md` — runner sandbox + secrets posture this narrows.
- `docs/adr/0013-credential-encryption.md` — the security-review-gated + "host/local operator is trusted" precedent followed here.
- `_bmad-output/planning-artifacts/epic-04-recovery.md#Story-4.4` — the diagnostics view that consumes this viewer.
- `docs/glossary.md` — `diagnostic console`, `live log` entries to be introduced (3d-10).
