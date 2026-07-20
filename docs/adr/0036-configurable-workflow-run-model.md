# ADR 0036 — Configurable-Workflow Run-Model

**Status:** Proposed (2026-07-19) — to be confirmed on merge of Epic 3m story 3m-2/3m-3
**Driver:** Epic 3m (Configurable Workflow Definitions + BMAD-Method Preset), story 3m-1 (spike). Epic 3m turns the hardcoded spec→implement→review pipeline into a **data-driven definition** — an ordered list of steps, each with a configurable executor — and ships the full BMAD method (analyst → pm → ux → architect → epics → story → dev → review → retro) as the first built-in preset. Before any schema (3m-2) or engine (3m-3) is written, one decision gates the whole epic: **does a definition-driven run reuse the existing `workflow_runs` state machine, or is it a new run type?** This ADR settles it.

## Context

The live pipeline is a hand-enumerated state machine. `org.dradgo.domain.registry.WorkflowState` declares the phases (`INBOX → PLANNED → INVESTIGATING → WAITING_FOR_SPEC_APPROVAL → EXECUTING → WAITING_FOR_REVIEW → COMPLETED`) plus gates (`WAITING_FOR_MANUAL_EXECUTION`, `WAITING_FOR_LINT_APPROVAL`, `WAITING_FOR_DELIVERY`, `WAITING_FOR_DEPENDENCIES`, `SPLIT`) and terminals (`FAILED`, `PAUSED`, `TAKEN_OVER`, `RECONCILED`). Every edge is enumerated in `WorkflowTransitionTable` (per ADR [0034](0034-rerun-safe-boundaries.md) — "there is no transition-table wildcard"). The whole recovery, audit, allowed-actions, and Epic 4 reconciliation apparatus keys off `workflow_runs` + `WorkflowState`.

Two facts shape the decision:

1. **Most existing states are pipeline-*semantic*, not generic.** `WAITING_FOR_SPEC_APPROVAL`, `EXECUTING`, `WAITING_FOR_REVIEW`, `WAITING_FOR_LINT_APPROVAL`, `WAITING_FOR_DELIVERY` all encode *what the spec→implement→review pipeline is doing*. A BMAD definition has 9 *different* phases (analyst, pm, ux, architect, epics, story, dev, review, retro). Naively mapping each BMAD phase to its own `WorkflowState` value would explode the enum with method-specific states and force per-phase transition-table edges — the exact expansion ADR 0034 rejected for rerun.

2. **The *generic* machinery is genuinely reusable.** The **gate** states (`WAITING_FOR_REVIEW` for human approval, `WAITING_FOR_MANUAL_EXECUTION` for a `manual`-kind step) and the **terminals** (`COMPLETED`/`FAILED`/`PAUSED`/`TAKEN_OVER`/`RECONCILED`) are method-agnostic. So is `EXECUTING` read as "a step is dispatching/running". So is every Epic 4 recovery path, the audit event log, and the context-bundle (FR54) mechanism.

The parallel-run-type alternative (a second `definition_runs` table + its own lifecycle) would duplicate all of that generic machinery — recovery, audit, allowed-actions, inspection, the operator queue — to avoid extending the one enum. That is a large, high-risk fork of the most load-bearing aggregate in the system.

## Decision

**A definition-driven run reuses the existing `workflow_runs` aggregate and `WorkflowState`, with a generic *current-step cursor* over the definition. No new run type. No per-BMAD-phase `WorkflowState` values.**

Concretely:

- **The run's "which phase am I in" is `definition.steps[current_step_index]`, not a `WorkflowState` value.** `WorkflowState` stays the *generic lifecycle* axis (dispatching / gated / terminal); the *step identity* (analyst/architect/…) is data on the definition, read via the cursor. This is the core separation that keeps `WorkflowState` from exploding.
- **`workflow_runs` gains (3m-2's job):** a nullable `workflow_definition_id` FK (null ⇒ the legacy hardcoded pipeline, byte-identical to pre-3m) and a nullable `current_step_index` cursor. A run with a null definition never reads the cursor.
- **`EXECUTING` is the generic "a step is running" state** for a definition-driven run — a step dispatches a runner exactly as the pipeline's implementation stage does. The cursor, not a new state, distinguishes "executing step 3 (architect)" from "executing step 6 (dev)".
- **Gates reuse existing states.** A `human_gated` step parks in `WAITING_FOR_REVIEW` (reusing the 3b/3d Decision Bar); a `manual`-kind step parks in `WAITING_FOR_MANUAL_EXECUTION` (reusing 3d-3/3d-4). **Target: zero new `WorkflowState` values for Epic 3m.** If a genuinely novel generic lifecycle state is unavoidable, it is added deliberately and generically (not per-BMAD-phase) as an amendment to this ADR.
- **Advance is cursor increment.** On a non-gated step's success, the engine (3m-3) increments `current_step_index` and dispatches the next step. On the last step (cursor at the final index) success + any gate clearing, the run transitions to `COMPLETED`.
- **Recovery attaches through the existing machinery.** A mid-definition step failure enters the standard `FAILED` handling and surfaces failed-step / last-successful-step / next-safe-action (NFR3) through the existing Epic 4 machinery. Targeting a definition step for rerun re-seats the cursor at that step's index — but note `RecoveryService.rerunFromStep`'s current surface is a two-value `SafeRerunStep` enum {`investigating`,`executing`} with no step-index, so this is an **extension** of ADR 0034 (widened target + new transition edges + generalized approval-kind model), **not** a drop-in. ADR 0034's safety *guarantees* (bounded target, approval invalidation, lineage-graft) are preserved; its 2-value surface is not sufficient as-is (see ADR [0037](0037-per-step-executor-binding.md) §5).

## Alternatives Considered

### Alt 1 — Per-BMAD-phase `WorkflowState` values (analyst/pm/ux/architect/…)
**Rejected.** Explodes the shared enum with method-specific states, forces new hand-enumerated `WorkflowTransitionTable` edges per phase, and makes every future definition (custom pipelines, a non-BMAD method) require enum + migration + transition-table surgery. The cursor models "which step" as *data*, which is the whole point of a configurable definition.

### Alt 2 — A parallel `definition_runs` run type with its own lifecycle
**Rejected.** Duplicates the entire generic apparatus — recovery, audit event log, allowed-actions, inspection, the operator queue, redaction — to avoid two nullable columns on `workflow_runs`. It also splits every operator surface into "which kind of run is this?" branches. The cost of the fork vastly exceeds the cost of the cursor. Kept on the table only as the fallback if the spike surfaced a hard blocker in reusing the aggregate — it did not.

### Alt 3 — Reuse the aggregate but overload the *existing pipeline states* as BMAD phases (e.g. map `architect` → `WAITING_FOR_SPEC_APPROVAL`)
**Rejected.** Semantically dishonest — an operator reading `WAITING_FOR_SPEC_APPROVAL` on an architecture step would be misled, and the audit trail would lie about what happened. The generic states (gates, terminals, `EXECUTING`-as-running) are reused *as generic states*; the pipeline-specific approval states are not repurposed.

## Consequences

- **3m-2 schema contract:** `workflow_runs` gets a nullable `workflow_definition_id` FK + a nullable `current_step_index`; a null definition is *intended to be* the byte-identical legacy pipeline, **verified by the 3m-10 parity test** (not assumed). No new `WorkflowState` value is expected — if 3m-3 proves one is unavoidable, it amends this ADR.
- **⚠️ Zero new *states* ≠ zero new *edges* (do not conflate).** `WorkflowTransitionTable` hand-enumerates every edge per source (ADR 0034 — no wildcard), and today has **no `EXECUTING→EXECUTING` edge** and no advance/complete edge out of `WAITING_FOR_MANUAL_EXECUTION` (it exits only to pipeline gate states). A definition with two adjacent non-gated steps, or a manual-kind non-gated step, therefore has **no wired way to advance the cursor to the next step or reach `COMPLETED`**. 3m-3 must add the transition edges (or a single generic "advance-step" self-edge on `EXECUTING`) that cursor-walking needs — this is real transition-table work the "zero new states" target does not eliminate.
- **3m-3 engine contract:** the sequencer is a cursor walk over `workflow_definition_steps`, dispatching each via `RunnerBroker`, parking on gates, chaining output→next bundle (FR54). It contains **no** BMAD-specific logic and **no** per-phase state (ArchUnit guard in 3m-10: no `bmad-*` literals, no new per-phase `WorkflowState`).
- **Cursor edge cases 3m-2/3m-3 must define:** a definition with **zero steps** (no last index to reach `COMPLETED` from) and **cursor integrity when a mutable custom definition is edited** (steps added/removed/reordered) under an in-flight run — the builtin preset is immutable (ADR 0037 §4) but custom definitions are not.
- **Recovery/audit/allowed-actions are inherited at the backend, not rebuilt** — definition-driven runs flow through the Epic 4 operator queue, recovery flows, and audit history without a parallel run type. **Caveat (unverified, 3m-8):** the operator-queue / audit / FE surfaces today render *hardcoded pipeline-stage labels*; showing a cursor-derived BMAD step name (analyst/architect/…) instead is a display change this spike did **not** inspect at the FE/audit layer — "no parallel surfaces" is a backend claim, not an FE guarantee.
- **The generic-state reuse is the invariant.** Any future method (a custom definition, a non-BMAD pipeline) is pure data on top of the same aggregate — no lifecycle fork. Adding a new *generic* lifecycle state remains a deliberate, ADR-recorded change; adding a *method phase* never touches `WorkflowState`.
