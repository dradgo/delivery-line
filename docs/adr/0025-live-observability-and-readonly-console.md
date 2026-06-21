# ADR 0025 — Live Execution Observability & Read-Only Diagnostic Console

**Status:** Proposed (2026-06-21) — **security-review-gated** before any console wiring (mirrors the ADR 0013 sign-off pattern)
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
