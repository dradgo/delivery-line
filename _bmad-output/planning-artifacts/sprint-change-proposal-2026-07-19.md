---
date: 2026-07-19
author: Alex
change_type: additive-epic
scope_classification: Major
status: proposed
epic: 3m
epic_doc: epic-03m-configurable-workflow-bmad-method.md
---

# Sprint Change Proposal — Epic 3m: Configurable Workflow Definitions + BMAD-Method Preset

## Section 1 — Issue Summary

**Trigger:** Product request to add support for running projects through the **BMAD method** with **configurable executors per step**.

DeliveryLine today runs a **hardcoded** delivery pipeline (spec → implement → review → integration). The step sequence is fixed in the state machine; only the *executor* of certain stages is configurable (Epic 3c per-project/per-stage connector binding; Epic 3d runner kinds `claude`/`codex`/`manual`). There is no way to:

1. run a *different ordered sequence of steps* (e.g. the BMAD method's analyst → PM → UX → architect → epics/stories → dev → review → retro), or
2. bind a distinct executor to each step of such a sequence.

This was surfaced during sprint execution as a new capability the pilot wants: drive the full BMAD delivery method inside the governed workflow, with each phase run by an operator-chosen agent/model.

## Section 2 — Impact Analysis

**Epic Impact**
- **New epic 3m** inserted in the 3-series (configurable-execution theme), sequenced **after Epic 3d** and available alongside Epic 4. Additive — no existing epic is reopened.
- Builds directly on **3c** (Project aggregate + per-project encrypted credentials + `ProjectConnectorResolver`), **3d** (runner-kind registry incl. `manual`, `WaitingForManualExecution`, advisory reviewer model, step log viewer), and **Epic 4** (rerun-from-step FR31/FR32, recovery/inspection).

**Story Impact**
- 11 net-new stories (3m-1 … 3m-11). No existing story is modified.
- The built-in pipeline path is preserved byte-identical for projects with no definition binding (explicit parity AC in 3m-2 §8, 3m-3 §1, 3m-10 §2).

**Artifact Conflicts / Updates**
- **PRD:** 5 new FRs (FR-Nx1…FR-Nx5, below) to be inserted. No existing FR/NFR is contradicted; the change extends FR51–FR55 (runner abstraction + context bundles) and reuses NFR1–NFR12 (state/recovery/audit) unchanged.
- **Architecture:** two new ADRs authored under 3m-1 — `00NN-configurable-workflow-run-model`, `00NN+1-per-step-executor-binding`. Adds a `WorkflowDefinition`/`StepDefinition` model + a definition-driven run engine as an **additive parallel path** to the existing state machine.
- **UX:** new surfaces — Workflow Definition selector, Per-Step Executor panel, phased BMAD run view, custom-definition editor — all reusing existing patterns (Decision Bar, artifact inspection, step log viewer, allowed-actions).
- **Epics doc:** new `epic-03m-configurable-workflow-bmad-method.md` (companion to this proposal).

**Technical Impact**
- New Flyway migrations (definition/step tables + BMAD preset seed + per-project step-override table), additive and replay-safe.
- New registry value sets (`definition_kind`, `bmad_step_key`, `artifact_kind`), public-id prefixes (`wfd_`, `wfs_`), `WorkflowEventType`s, allowed-actions, and domain error codes — all under the existing drift-test + foundation-gate discipline.
- **Key risk:** the run-model decision (reuse the `workflow_runs` state machine with a step cursor vs. a parallel run type) is settled up front by ADR + spike (3m-1) before dependent stories start.
- **Key dependency risk:** headless BMAD-agent invocation by a runner — de-risked by the 3m-1 spike, with a documented `manual`-runner-kind fallback per phase if a signal is unavailable.

## Section 3 — Recommended Approach

**Direct Adjustment (additive epic).** Add Epic 3m as an additive, opt-in capability. Do **not** refactor the shipped Epic 1–4 state machine; run BMAD as an alternate *definition* alongside the legacy pipeline.

Design decisions locked in the correct-course discussion (2026-07-19):
- **Shape:** generic configurable-steps engine, BMAD as the first built-in preset (not a BMAD-only pipeline, not a refactor of the existing pipeline).
- **Executor model:** reuse existing runner kinds (`claude`/`codex`/`manual`) + per-project credential + a BMAD **role prompt** — no new runner infrastructure.
- **Phase scope:** the **full method** (9 phases: analyst → pm → ux → architect → epics → story → dev → review → retro).
- **Custom editor:** **included** — a basic UI/CLI editor for authoring custom definitions (story 3m-9).
- **Invocation risk:** **spike first** (story 3m-1) before committing dependent stories.
- **Loops:** Phase-1 feedback loops via Epic 4 **rerun-from-step**, not native branching (deferred).

**Effort / risk / timeline:** Comparable in size to Epic 3d (11 stories vs. 3d's 10), slightly larger due to the editor. Front-loaded risk retired by 3m-1. Heavy reuse of 3c/3d/4 seams keeps net-new surface area smaller than the capability implies.

### New Functional Requirements (provisional — to insert in PRD)

- **FR-Nx1:** A project can select a **workflow definition** (built-in BMAD preset for Phase 1) instead of the default hardcoded pipeline.
- **FR-Nx2:** An operator can **bind an executor** (runner kind + credential + BMAD role) to each step of a definition.
- **FR-Nx3:** The engine **executes a definition's steps in order**, chaining each step's output into the next step's context bundle.
- **FR-Nx4:** Each BMAD phase produces a **typed artifact** inspectable in run history.
- **FR-Nx5:** A step can be **human-gated**; the run parks for approval/rerun before advancing.

## Section 4 — Detailed Change Proposals

The full 11-story breakdown with acceptance criteria lives in the companion epic doc:
**`_bmad-output/planning-artifacts/epic-03m-configurable-workflow-bmad-method.md`**

Story summary:

| # | Story | Group |
|---|---|---|
| 3m-1 | Spike + ADR: executor-invokes-BMAD-agent proof + run-model decision | Spike & Foundations |
| 3m-2 | `WorkflowDefinition` + `StepDefinition` schema + registries (Flyway) | Spike & Foundations |
| 3m-3 | Definition-driven run engine | Engine & Binding |
| 3m-4 | Per-step executor binding + resolution (REST + CLI) | Engine & Binding |
| 3m-5 | BMAD role-prompt catalog + 8-phase preset seed | BMAD Preset |
| 3m-6 | Typed BMAD artifacts + phase-to-phase chaining | BMAD Preset |
| 3m-7 | Human-gated steps + rerun-from-step for BMAD loops | BMAD Preset |
| 3m-8 | UI: definition selection + per-step executor config + BMAD run inspection | Surfaces |
| 3m-9 | Basic custom-definition editor (UI + CLI) | Surfaces |
| 3m-10 | Foundation-gate widening + test-suite extension | Cross-cutting |
| 3m-11 | Documentation increment | Cross-cutting |

**Sequencing:** 3m-1 → 3m-2 → (3m-3, 3m-4) → 3m-5 → 3m-6 → 3m-7 → (3m-8, 3m-9) → 3m-10 → 3m-11.

## Section 5 — Implementation Handoff

**Scope classification: Major** (new epic, new architecture path, PRD + ADR + UX artifact updates).

Handoff sequence:
1. **PM** — insert FR-Nx1…FR-Nx5 into the PRD.
2. **Architect** — author the two ADRs as the deliverable of story 3m-1 (run-model + executor-binding), plus the spike proof.
3. **Sprint planning** — run `bmad-sprint-planning` (or `bmad-create-story` per story) to add the `3m-*` keys to `sprint-status.yaml`. **Until this runs, the stories do not appear in sprint status.**
4. **Dev** — execute stories in the sequenced order via `bmad-dev-story`, foundation-gate green per story.

**Success criteria:** BMAD method runs end-to-end for a pilot project with per-step executors configured; a null-binding project remains byte-identical to the pre-3m pipeline; the walkthrough doc (3m-11) is merged with a named human validator.

---

**Open items for Alex before sprint-planning:**
- Confirm the **epic number `3m`** (provisional; adjust if the sequence should differ).
- Confirm the **provisional ADR numbers** `00NN` / `00NN+1` (assign next free ADR numbers at 3m-1 time).
- PM to finalize FR numbering when inserting into the PRD.
