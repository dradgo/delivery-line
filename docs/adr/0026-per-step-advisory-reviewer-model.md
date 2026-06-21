# ADR 0026 — Per-Step Advisory Reviewer Model

**Status:** Proposed (2026-06-21) — to be confirmed during Epic 3d story creation (3d-1/3d-2)
**Driver:** Epic 3d (PRD FR64). Operators want each workflow step's output reviewed by a **different LLM** than produced it, configurable per project — a second opinion before (or alongside) the human review. The open design question is how strong that verdict is; this ADR records the **advisory-now, gating-capable-later** decision and the data model that keeps gating a config switch rather than a rebuild.

## Context

Epic 3b established the human review loop: a step's output artifact lands in `WaitingForReview`, a human reviewer approves/rejects via the Decision Bar (backend-reported allowed-actions). Epic 3c added per-project connector configuration + encrypted per-project credentials + the `ProjectConnectorResolver` (ADR 0007/0008, story 3c-3).

A reviewer LLM is a natural extension: bind a *reviewer model* per project, run it over the produced artifact, and surface its assessment. The risk is over-automation — letting a model auto-reject a step before a human sees it changes the governed approval semantics and the meaning of an approval. The decision must let teams adopt the second opinion without surrendering human authority, while leaving room to opt into automation deliberately.

## Decision

**1. Reviewer-model binding rides the Epic 3c per-project model.** A project (optionally per stage) configures a reviewer model + its own credential, resolved through the same `ProjectConnectorResolver` / per-project credential machinery (ADR 0013) — no new credential subsystem. A null binding means "no reviewer," preserving current behavior.

**2. The verdict is ADVISORY by default.** The reviewer's assessment is **surfaced to the human reviewer** in the Decision Bar (Reviewer Verdict Panel, UX) as a second opinion. It does **not** auto-approve or auto-reject; the human decision remains the governing approval. This keeps the Epic 3b authority model intact.

**3. The data model is GATING-CAPABLE from day one.** The advisory-verdict record persists a structured outcome (e.g. pass/concern/fail + rationale + the reviewer model identity + the reviewed artifact version) sufficient to *act on* later. A per-project `gating` flag (default off) is the only switch needed to let a failing verdict block progression — no schema rework. Gating stays **off** until a deliberate per-project decision turns it on; turning it on is a future story, not this epic.

**4. Reviewer execution reuses the runner abstraction.** The reviewer runs as a runner invocation over the stage's output artifact (it may itself be a `claude`/`codex`/other kind), recording a `runner_executions` row and producing a verdict artifact/record — so the second opinion is itself inspectable and auditable, not an opaque side call.

**5. Provenance is explicit.** The verdict records which model produced the step output and which produced the review, so "reviewed by a different LLM" is verifiable and a same-model self-review is detectable.

## Alternatives Considered

### Alt 1 — Gating reviewer (auto-reject on fail) now
**Rejected for now.** Changes governed approval semantics and needs new states/error codes before the team has calibrated the reviewer's reliability. Deferred behind the gating-capable data model + a per-project opt-in.

### Alt 2 — Advisory-only with no path to gating
**Rejected.** Operators may later want automation for high-volume low-risk projects; designing the record to be act-on-able now avoids a migration then.

### Alt 3 — Reviewer as a direct API call outside the runner abstraction
**Rejected.** Would make the second opinion un-inspectable and inconsistent with FR53 (common runner abstraction records normalized output). Running it as a runner invocation keeps it auditable.

## Consequences

### Positive
- Teams get a second-LLM opinion immediately without surrendering human approval authority.
- Enabling gating later is a per-project flag flip, not a rebuild.
- The reviewer step is itself inspectable/auditable (runner_executions + verdict record) with explicit model provenance.

### Negative
- A reviewer run consumes provider quota/time per step — relevant to the provider-limit status (FR69) and to cost on API-billed accounts.
- Per-stage reviewer configuration adds surface to the project config model + drift tests.

### Neutral
- No reviewer binding = current behavior unchanged; the feature is opt-in per project.
- Whether the binding is per-project-only or per-project-and-per-stage is a 3d-1 detail; the ADR allows either.

## References
- [Source: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-06-21.md`] — Epic 3d proposal (D2); FR64.
- `docs/adr/0013-credential-encryption.md`, `docs/adr/0007-ticket-source-abstraction.md`, `docs/adr/0008-repository-host-abstraction.md` — per-project credential + resolver machinery reused.
- `docs/glossary.md` — `reviewer model`, `advisory verdict` entries to be introduced (3d-10).
