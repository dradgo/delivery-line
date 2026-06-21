# ADR 0024 — Manual Execution Mode (`manual` runner kind + `WaitingForManualExecution`)

**Status:** Proposed (2026-06-21) — to be confirmed during Epic 3d story creation (3d-3/3d-4)
**Driver:** Epic 3d (PRD FR66). Claude no longer supports an unattended prompt mode without an API key, so an operator running on a subscription account cannot dispatch an automated Claude step headlessly. Rather than force per-token API billing as the only path, DeliveryLine needs a first-class way to run a step **manually** — the operator runs the agent themselves and feeds the result back into the governed pipeline.

## Context

Today a workflow step is executed by the runner broker launching a runner container (`claude` / `codex` kinds, ADR 0003 / story 3.3–3.4) over a file-based **runner-contracts v1** input bundle, producing an output artifact that flows through validation → the Epic 3b `WaitingForReview` human loop. The execution *kind* is selected per project via the Epic 3c connector/runner configuration.

The headless-auth breakage removes the automated path for one provider but not the *contract*: the input bundle and the output-artifact schema are unchanged regardless of who produces the artifact. The gap is purely "who runs the agent" — a container, or a human.

A manual path must not become a side-channel that bypasses validation, idempotency, or audit. It has to reuse the same output contract, the same artifact-operation atomicity, and the same review loop, so a manually-produced artifact is indistinguishable downstream from an automated one except in its provenance.

## Decision

**1. `manual` is a new runner *kind*, not a new contract.** It is registered alongside `claude`/`codex` in the runner-kind registry and selectable per project (and per stage). The runner-contracts input bundle and output-artifact schema are unchanged — `manual` changes the *producer*, not the contract.

**2. New workflow state `WaitingForManualExecution`.** Added to the state registry + the `workflow_runs.current_state` CHECK (additive migration). When a step is dispatched under the `manual` kind, the dispatch path **emits the context bundle and transitions the run to `WaitingForManualExecution`** instead of launching a container — the run parks awaiting an operator-produced artifact.

**3. New `WorkflowEventType`s for the manual lifecycle** (e.g. `manual.executionRequested`, `manual.artifactSubmitted`), each mirrored into the registry + fixture sites per the house "new WorkflowEventType → two fixture sites" rule. Audit history records the manual hand-off and the operator identity.

**4. Submission re-enters the existing pipeline.** The operator downloads/copies the emitted bundle (UI + CLI), runs the agent, and submits the resulting artifact through a governed endpoint that runs the **same runner-contracts output validation** as an automated runner; on success the run transitions out of `WaitingForManualExecution` into the normal post-step state (e.g. `WaitingForReview`). Idempotency-Key + X-Actor-Identity conventions apply.

**5. A `runner_executions` row is still recorded** for the manual step (kind `manual`, no container) so the execution is first-class in inspection, observability, and lineage — preserving FR53 (common runner abstraction records normalized output/artifacts/failure state) for the manual path.

## Alternatives Considered

### Alt 1 — Require an API key (no manual path)
**Rejected.** Forces per-token billing as the only way to run Claude steps and strands subscription-only operators; contradicts FR51 (use familiar agents within the governed workflow).

### Alt 2 — Let operators paste artifacts directly via an ad-hoc upload, bypassing the runner abstraction
**Rejected.** A side-channel that skips output validation, the `runner_executions` record, and the state machine would make manual artifacts second-class and unauditable. Reusing the kind + contract keeps one governed pipeline.

### Alt 3 — Drive the agent inside the container via the read-only console (ADR 0025)
**Rejected as the primary mechanism.** The console is deliberately read-only and diagnostic; making it write-capable to host manual execution would breach the sandbox posture. Manual execution is a dispatch-mode concern, not a terminal concern. (The two compose: an operator *may* inspect a live runner read-only, but manual execution is its own kind.)

## Consequences

### Positive
- Subscription-only operators can run Claude (or any agent) steps without API-key billing, via a governed path.
- Manual artifacts are indistinguishable downstream from automated ones (same validation, review, audit) except in recorded provenance.
- The state machine + event log make "this step is awaiting a human to run the agent" an explicit, inspectable condition rather than a stalled run.

### Negative
- A parked `WaitingForManualExecution` run depends on a human to proceed — it has no timeout/auto-progress; operator queues must surface it as actionable (Epic 3d UI + Epic 4 operator queue).
- One more workflow state + event types to maintain across registry, fixtures, and the foundation gate (3d-9).

### Neutral
- `manual` runner-kind selection is per project/stage; default projects keep automated kinds, so existing flows are unaffected unless manual is chosen.
- Pulling 3d-3/3d-4 forward within the epic is the mitigation if the automated headless path is unavailable for the pilot.

## References
- [Source: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-06-21.md`] — Epic 3d proposal (D1); FR66.
- `docs/adr/0003-runner-secrets-mvp-posture.md`, `docs/adr/0004-spec-stage-orchestration.md` — runner posture + ADR format.
- `docs/adr/0025-live-observability-and-readonly-console.md` — sibling console decision (composes, does not host manual execution).
- `docs/glossary.md` — `manual execution`, `WaitingForManualExecution` entries to be introduced (3d-10).
