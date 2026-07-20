# Story 3m.1: Spike + ADR — Executor-Invokes-BMAD-Agent Proof & Run-Model Decision

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- dev-story 2026-07-19: ADRs 0036/0037 + findings note authored. code-review 2026-07-19 (3 Sonnet layers): 8 patches applied to the ADRs/findings (incl. a real ADR-0037 credential-path factual fix + transition-edge/stage-vocabulary/rerun-extension caveats); 1 decision resolved by Alex = SPLIT the live headless-run proof into follow-up spike 3m-1b. 3m-1 re-scoped to the ADR/design deliverables (mechanism decided, live capture is 3m-1b's AC) -> done. See Review Findings + 3m-1b. -->



## Story

As a backend developer,
I want a time-boxed spike that proves an existing runner can headlessly act as a named BMAD agent and return a typed artifact, plus an ADR fixing the run-model,
so that every dependent story (3m-2..3m-11) is built on a verified invocation path and a settled state-machine decision.

## Context

This is the **foundation spike of Epic 3m** (Configurable Workflow Definitions + BMAD-Method Preset), sequenced after Epic 3d and available alongside Epic 4. Epic 3m turns the hardcoded spec→implement→review pipeline into a **data-driven configurable definition**: an ordered list of steps, each binding an **executor** (an existing runner kind `claude`/`codex`/`manual` + a per-project credential + a **BMAD role prompt** naming which BMAD agent the runner acts as). It ships the full BMAD method (analyst → pm → ux → architect → epics → story → dev → review → retro) as the first `builtin` preset, plus a basic custom-definition editor.

**3m-1 is a pure de-risking spike. It writes NO production runtime code.** Its two hard questions gate the entire epic, so they are answered *first*, in the cheapest possible way:

1. **Can a runner headlessly "become" a named BMAD agent and return a typed artifact?** The BMAD agents in this repo are **Claude Code skills** (`.claude/skills/bmad-*/`), not headless-CLI programs. The runners (`runners/claude`, `runners/codex`) are Docker containers driving the `claude` / `codex` CLIs unattended. The spike must prove — with a captured transcript — that a runner can be handed a BMAD agent's instructions as prompt/context and produce output conforming to a candidate typed-artifact schema. If it can't for a given runner kind, the documented fallback is the **`manual` runner kind** for that phase (reuse 3d-3/3d-4). See [[runner-image-has-no-jdk-or-maven]] — runner images are `node:22-slim`; a BMAD agent that shells out to `java`/`mvn` is not agent-executable, which directly informs which phases can be automated vs. must be `manual`.
2. **Do definition-driven runs reuse the existing `workflow_runs` state machine (with a generic current-step cursor over the definition) or a parallel run type?** This is the single biggest architectural risk in the epic. It is decided by **ADR 0036** before any schema (3m-2) or engine (3m-3) work starts.

**The deliverables of this story are documents, not features:** two ADRs, a captured proof, and a findings note. Do **not** author Flyway migrations, registries, the engine, REST/CLI surfaces, or frontend here — those are 3m-2 onward.

**Dependencies:** Epic 3c (`ProjectConnectorResolver` + per-project encrypted credentials), Epic 3d (runner-kind registry incl. `manual`, `WaitingForManualExecution`), and Epic 4 (rerun-from-step FR31/FR32) are all merged and are the seams this epic extends. No code depends on 3m-1; 3m-1's *findings* feed 3m-2..3m-11.

## Acceptance Criteria

> Copied verbatim from the epic (`_bmad-output/planning-artifacts/epic-03m-configurable-workflow-bmad-method.md`, Story 3m-1). Already BDD-formatted.

1. **Given** the spike gate, **Then** the story is a **spike** — its deliverable is a recorded proof + ADRs, not production wiring; any throwaway code is clearly marked and not merged into the runtime path.
2. **Given** the `claude`/`codex` runner kinds + `runner.mjs`, **When** the spike drives a runner with a **BMAD role prompt** (e.g. "act as `bmad-create-architecture`"), **Then** it demonstrates the runner invoking that BMAD agent **headlessly** and returning output that conforms to a candidate **typed-artifact** schema — the proof is recorded (transcript/output captured).
3. **Given** the "signal unavailable" contingency (mirrors 3d-7 D5), **If** headless BMAD-agent invocation is *not* reliably achievable for a runner kind, **Then** the spike records that finding and the epic falls back to the **`manual` runner kind** for that phase as the documented Phase-1 path, rather than fabricating an invocation mechanism.
4. **Given** the central run-model question, **Then** **ADR `0036-configurable-workflow-run-model`** decides whether definition-driven runs (a) reuse the existing `workflow_runs` state machine with a generic *current-step cursor* over the definition, or (b) introduce a parallel run type — with reuse option (a) preferred unless the spike surfaces a blocker; the decision records how `WaitingForReview`/`WaitingForManualExecution` gates and Epic 4 recovery attach.
5. **Given** the executor model, **Then** **ADR `0037-per-step-executor-binding`** records the binding shape (runner kind + per-project credential + BMAD role prompt) and its resolution through `ProjectConnectorResolver`, confirming **no new credential subsystem** (reuses 3c `project_credentials`).
6. **Given** BMAD's feedback loops, **Then** the ADR records the Phase-1 decision to express loops via Epic 4 **rerun-from-step** (FR31) rather than native branching, and notes what a future native-loop epic would add.
7. **Given** the spike outcome, **Then** a short findings note lists confirmed assumptions, the chosen run-model, and any story-scope adjustments the proof forces (feeding back into 3m-2..3m-11 before they start).

## Tasks / Subtasks

- [x] **Task 1 — BMAD-agent invocation MECHANISM decision (AC: #1, #3)** — re-scoped by code-review (Alex 2026-07-19): the DESIGN/mechanism half stays here; the **LIVE run capture is split to follow-up spike `3m-1b`** (was AC2). Mechanism decided, candidate schema + provisional per-phase map recorded, `manual` fallback documented (AC3). NOT fabricated.
  - [x] Pick **one** representative generative phase for the proof — chose `architect` (`bmad-create-architecture`): self-contained document artifact, clean proof target. (Findings §3.)
  - [x] **Decide the invocation mechanism** (ADR 0037 §2): vendor `.claude/skills/bmad-*` into the runner images like `obra/superpowers` (3a-7) + a `DELIVERYLINE_RUNNER_BMAD_ROLE` prompt-nudge (OpenSpec-3a-8 twin). No new runner kind, no bundle-schema change. → The **live** proof that this reliably fires headlessly is **`3m-1b`** (needs Docker + egress + a real credential; same activation question 3a-6/3a-7 deferred to story 3.8; runbook `docs/spikes/3m-1-findings.md` §4). **Do NOT fabricate a transcript.**
  - [x] **Document the `manual` fallback (AC3)** — any phase whose agent can't be driven reliably headlessly defaults to `manual` (parks in `WaitingForManualExecution`); the per-phase automatable-vs-manual map (findings §3) is provisional pending 3m-1b's live run.
  - [x] Sketch a **candidate typed-artifact schema** — `architecture` kind = `artifactId` + `title` + body-markdown, satisfies `isArtifactView` ([[artifact-read-dto-must-satisfy-isartifactview]]). Recorded in findings §3/§4 (informs 3m-6).
  - [x] Record the **per-phase automatable-vs-manual map** — findings §3. CORRECTION: runner images DO carry JDK 21 + Maven 3.9 (RUNNER_CONTRACT §"JDK + Maven"), so `dev` is **not** forced `manual` for lack of a toolchain — the story's DD-4 / `[[runner-image-has-no-jdk-or-maven]]` reasoning is stale (findings §1, §5.3). Per-phase automated defaults remain **provisional** on the live-run (§4).

- [x] **Task 2 — ADR 0036: configurable-workflow run-model (AC: #4)** — COMPLETE.
  - [x] Authored `docs/adr/0036-configurable-workflow-run-model.md` (next-free after head 0035; format matches 0034); registered in `docs/adr/README.md`.
  - [x] **Decided (a) reuse.** Reuse `workflow_runs` + `WorkflowState` with a `current_step_index` cursor; NO parallel run type; NO per-BMAD-phase states (step identity = `definition.steps[cursor]`, data). Rationale + rejected Alt 1 (per-phase states) / Alt 2 (parallel run type) / Alt 3 (repurpose pipeline states) recorded.
  - [x] Gates attach via existing `WAITING_FOR_REVIEW` (human) / `WAITING_FOR_MANUAL_EXECUTION` (manual); **target zero new `WorkflowState` values** stated explicitly.
  - [x] Recovery attaches unchanged; `rerunFromStep` (ADR 0034) targets a step by re-seating the cursor. Grounded in the real `WorkflowState.java` (pipeline-semantic vs generic states) + `WorkflowTransitionTable` no-wildcard fact.
  - [x] Downstream impact enumerated in ADR §Consequences + findings §5.1: 3m-2 adds nullable `workflow_definition_id` FK + nullable `current_step_index`; target zero new `WorkflowState` values.

- [x] **Task 3 — ADR 0037: per-step executor binding (AC: #5, #6)** — COMPLETE.
  - [x] Authored `docs/adr/0037-per-step-executor-binding.md`; registered in `docs/adr/README.md`.
  - [x] Binding shape recorded: `{RunnerKind (incl. manual) + per-project project_credentials + bmad_role}`, resolved via `ProjectConnectorResolver` at dispatch, **no new credential subsystem** (mirrors ADR 0026). Invocation mechanism GROUNDED: vendor `.claude/skills/bmad-*` like `obra/superpowers` (3a-7) + `DELIVERYLINE_RUNNER_BMAD_ROLE` prompt-nudge (OpenSpec-3a-8 twin) — no bundle-schema change, no new runner kind.
  - [x] Preset-immutability + per-project-override recorded (Alt 3 clone-per-project rejected); precedence override→step-default.
  - [x] Phase-1 loop decision recorded: loops via `rerunFromStep` (ADR 0034 — approval-invalidation + lineage-graft supersession) NOT native branching; Alt 2 (native `on_reject_goto`) rejected-for-Phase-1 with deferral rationale. Cross-links ADR 0034.

- [x] **Task 4 — Findings note + story-scope feedback (AC: #7)** — COMPLETE.
  - [x] Authored `docs/spikes/3m-1-findings.md`: confirmed assumptions (incl. the JDK/Maven stale-memory correction + the vendored-skills mechanism), chosen run-model (reuse), per-phase automatable map (§3), candidate typed-artifact sketch (§4).
  - [x] Story-scope adjustments listed (findings §5) — CORRECTION baked in: `dev` is NOT forced `manual` (JDK/Maven present), superseding the story's DD-4; 3m-2 cursor+FK; per-project step-override table; loops via rerunFromStep; code-artifact reuses impl-output lineage.
  - [x] Provisional-number flags recorded (findings §5.7): epic **3m** + ADR **0036/0037** — confirm still-free at merge.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **N/A for 3m-1 — confirmed.** This is a documentation/spike story: two ADRs + a findings note. **No** production runtime code merged (AC1) — no new application-service entry/exit, no SPI/DB-write call site, no retry/replay/recovery branch. The definition-driven engine's logging surface lands in **3m-3**. No log lines invented for a spike.
  - [x] No proof scaffolding merged (the live proof is a documented user-driven runbook, not committed code), so the logging standard has no surface to apply here.

### Review Findings

> bmad-code-review 2026-07-19 (Sonnet reviewers — different model than the Opus author). 3 adversarial layers (Blind Hunter [diff-only], Edge Case Hunter [diff + verified against real code], Acceptance Auditor [7 ACs]). ECH findings verified by the reviewer AND re-verified by the review lead against `DockerRunnerAdapter.java`, `WorkflowTransitionTable.java`, `RUNNER_CONTRACT.md`, `WorkflowState.java`, ADR 0034. 1 decision-needed, 8 patch, 5 dismissed.

**Decision-needed — RESOLVED (Alex 2026-07-19: option (c), split live-run into follow-up `3m-1b`):**
- [x] [Review][Decision][RESOLVED] **AC2 recorded proof undelivered → split into `3m-1b`.** Acceptance Auditor's argument accepted: AC1 makes the recorded proof a co-equal primary deliverable, so `review` overclaimed with the proof deferred, and AC3 ("*if* not reliably achievable") didn't fit a "not attempted" outcome. Alex (epic owner) chose to carve the **live headless-run proof** into a separate spike `3m-1b-headless-bmad-agent-live-run-proof` (backlog) — this choice IS the epic-owner sign-off. **3m-1 is re-scoped to the ADR/design deliverables only** (Task 1 = mechanism decision + candidate schema + provisional per-phase map; the live capture is now 3m-1b's AC). With the re-scope + all patches applied, 3m-1's remaining ACs are met → `done`.

**Patches (ADR/findings doc fixes — verified real) — ALL APPLIED (batch, Alex 2026-07-19):**
- [x] [Review][Patch][FIXED] **ADR 0037 credential-resolution path is factually wrong** — §3 claims resolution "through `ProjectConnectorResolver`… identical to how automated runners resolve their adapters today." VERIFIED FALSE: `DockerRunnerAdapter:784` — every non-REVIEW dispatch uses `RunnerSecretsService.resolveSecretsForRunner`; `ProjectConnectorResolver` is the REVIEW-only path and it *silently falls back to the host secret* (contradicting the ADR's "never a silent default"). Fix the resolution-path claim + the silent-default guarantee. [ECH#1; docs/adr/0037…:§3]
- [x] [Review][Patch][FIXED] **ADR 0036 "zero new WorkflowState values" hides that new transition EDGES are required** — VERIFIED: `WorkflowTransitionTable` has no `EXECUTING→EXECUTING` edge (cursor advance between two adjacent non-gated steps is unwired) and `WAITING_FOR_MANUAL_EXECUTION` exits only to gate states (a manual non-gated step can't advance the cursor or reach `COMPLETED`). Add a Consequences item: zero new *states* but new *edges* (or a generic step-advance edge) are required for 3m-3. [ECH#5/#6; docs/adr/0036…:Consequences]
- [x] [Review][Patch][FIXED] **Findings §5 omits the runner stage/artifactType vocabulary limit** — `RUNNER_CONTRACT.md` accepts exactly 3 stage tokens → 3 `artifactType`s; an unknown token exits **13**. The §3 table asserts 9 BMAD artifact kinds. Add a §5 scope-adjustment: 3m-2/3m-3/3m-5 must extend the stage/artifactType vocabulary (or map BMAD phases onto it) — a real blocker, not just `code` (§5.5). [ECH#2; docs/spikes/3m-1-findings.md §5]
- [x] [Review][Patch][FIXED] **ADR 0036/0037 over-assert that ADR 0034 rerun "carries over"** — `SafeRerunStep` is a 2-value enum {investigating, executing} with no step-index, and its approval-invalidation has exactly 2 kinds (spec/implementationPlan). "Re-seat the cursor at that step's index" needs `rerunFromStep`'s signature + transition edges + the approval-kind model extended. Qualify the claim (it's an extension, not free reuse). [ECH#3/#4; docs/adr/0036…§Decision, 0037…§5]
- [x] [Review][Patch][FIXED] **Cursor edge cases undefined** — add to ADR 0036 / findings: a zero-step definition (no last index to reach `COMPLETED`), and cursor integrity when a mutable custom definition is edited (steps added/removed/reordered) under an in-flight run. [ECH#7]
- [x] [Review][Patch][FIXED] **Clone-to-edit override fate unspecified (3m-9)** — ADR 0037 §4 stores per-project overrides on preset step rows; Alt 3's clone-to-edit doesn't say whether existing overrides are copied/dropped/orphaned. Add a one-line note deferring the rule to 3m-9. [ECH#8-override]
- [x] [Review][Patch][FIXED] **Soften evidentiary framing** — JDK/Maven is "per the committed contract doc," not build-verified this session (label was `[CONFIRMED]`); "byte-identical" → "intended; verified by the 3m-10 parity/flag-off test"; `DELIVERYLINE_RUNNER_BMAD_ROLE` marked "working name" consistently (currently settled in Consequences/Decisions); scope "no parallel surfaces"/"no blocker" to the backend (FE/audit label-rendering unverified). [BH#1/#3/#4/#5/#8]
- [x] [Review][Patch][FIXED] **Minor open-items to record** — STEP_EXECUTOR_NOT_CONFIGURED may need to split "no binding" vs "no credential for kind" (precedent: UNSUPPORTED_CONNECTOR_KIND / DOCTOR_RUNNER_SECRET_MISSING); bind-time vs dispatch-time validation of an unvendored `bmad_role`; `manual` + non-null `bmad_role` combo; Codex `~/.agents/skills/` SKILL.md parse-compat unverified vs the superpowers precedent; drop the loose ADR-0026 "self-review mirror" analogy. [ECH#9/#10/#11/#12, BH#10]

**Dismissed as noise (5):** BH#2 "dev phase not decided" (deliberate 3m-5 scoping — the spike *did* flag dev as open; its resolution is coupled to the stage-vocabulary patch above); BH#6 "confirmed against CLI 2.1.149" (a direct citation from the shipped 3a-7 README, not an invented claim); BH#7 "two Proposed ADRs cite each other" (a matched pair from one spike — normal); BH#9 "'Phase 1' undefined" (= this epic's scope, clear from the epic doc); BH#11 "`isArtifactView` undefined" (a real existing codebase contract the runbook may reference). Auditor's "DD-4 override justified" is a confirmation, not a finding.

## Dev Notes

### What this story is (and is not)

- **Is:** a de-risking spike that produces **two ADRs** (0036 run-model, 0037 executor-binding), a **captured proof** that a headless runner can act as a BMAD agent (or a documented `manual` fallback), and a **findings note** that adjusts 3m-2..3m-11 before they start.
- **Is not:** any production runtime code. No Flyway migration, no `WorkflowDefinition`/`StepDefinition` schema (that's 3m-2), no engine (3m-3), no executor-binding endpoints (3m-4), no preset seed (3m-5), no REST/CLI/frontend. Any proof code is throwaway, clearly marked, and **not merged** into the runtime path (AC1).

### The crux the spike must resolve

The BMAD agents (`analyst`, `pm`, `architect`, …) exist in this repo as **Claude Code skills** — markdown persona + `workflow.md` under `.claude/skills/bmad-*/`. They are designed to run *inside a Claude Code session* (like the one authoring this story), driven by the `Skill` tool. The Epic 3m runners are **different**: Docker containers (`runners/claude`, `runners/codex`) that drive the `claude` / `codex` CLIs **headlessly/unattended**. So "the runner becomes the architect agent" is **not** the Claude Code skill mechanism — it is: *feed the BMAD agent's instructions to a headless CLI as prompt/context and have it follow them to produce a typed document*. Proving that round-trip (and capturing a real transcript) is the whole point of Task 1. The mechanism the spike settles (inline prompt vs. mounted context file; how `.claude/skills/bmad-*/workflow.md` content reaches the container) becomes an input to 3m-4's `bmad_role` catalog (3m-5).

### Design decisions / open questions (record in the ADRs + Completion Notes)

- **DD-1 — run-model = reuse the existing state machine (preferred).** Default: definition-driven runs reuse `workflow_runs` + `WorkflowState` with a current-step cursor; no parallel run type. **OQ:** does the cursor live as a new `workflow_runs.current_step_index` column + a `workflow_definition_id` FK (3m-2's job), and does `WorkflowState` need *any* new value (target: **no** — gates reuse `WaitingForReview`/`WaitingForManualExecution`)? Decide in ADR 0036; if a blocker appears, document the parallel-run-type alternative and switch.
- **DD-2 — executor binding rides the 3c credential model, no new subsystem** (mirrors ADR 0026). Binding = `RunnerKind` + `project_credentials` + `bmad_role`; resolved via `ProjectConnectorResolver`. Preset is immutable `builtin`; per-project **step overrides** carry executor choices (feeds 3m-4 §6 / 3m-9). Record in ADR 0037.
- **DD-3 — loops via rerun-from-step, not branching** (AC6). Phase-1 loops = operator reject → Epic 4 `rerunFromStep` (ADR 0034) with feedback folded into the re-run bundle; upstream rerun marks downstream artifacts superseded (3m-7 §4). Native branching deferred. Record in ADR 0037 (+ cross-link 0034).
- **DD-4 — proof phase = `architect`, not `dev`.** The proof uses a document-producing phase runnable on `node:22-slim`. The `dev` phase produces code and needs a build toolchain the runner image lacks ([[runner-image-has-no-jdk-or-maven]]) — the findings note should recommend seeding `dev` as `manual` by default in 3m-5 (or routing it to the existing build-capable implementation path) rather than a `claude`/`codex` document runner.

### Key seams & exact locations (verified against the tree)

- **Runners:** `runners/claude/lib/runner.mjs`, `runners/codex/lib/runner.mjs`; contract at `runners/RUNNER_CONTRACT.md`; per-runner docs `runners/claude/README.md`, `runners/codex/README.md`. Runner images are `node:22-slim` (no JDK/Maven).
- **BMAD agent sources:** `.claude/skills/bmad-*/` — for the proof phase, `.claude/skills/bmad-create-architecture/` (persona + `workflow.md`). Full catalog for 3m-5: `bmad-agent-analyst`, `bmad-create-prd`, `bmad-create-ux-design`, `bmad-create-architecture`, `bmad-create-epics-and-stories`, `bmad-create-story`, `bmad-dev-story`, `bmad-code-review`, `bmad-retrospective`.
- **Run-model target:** `org.dradgo.domain.registry.WorkflowState` (`.../domain/registry/WorkflowState.java`) — the state machine ADR 0036 decides whether to extend. `WorkflowState.isTerminal()` (3 hardcodes→1, per 4-30) is the terminal-state helper.
- **Executor binding:** `org.dradgo.domain.registry.RunnerKind` (`.../domain/registry/RunnerKind.java`, incl. `manual` from 3d-3); resolution in `org.dradgo.application.project.ProjectConnectorResolver` (`.../application/project/ProjectConnectorResolver.java`); dispatch via `org.dradgo.application.runner.RunnerBroker` (`.../application/runner/RunnerBroker.java`).
- **Recovery / rerun seam:** `org.dradgo.application.recovery.RecoveryService` (`rerunFromStep`, ADR 0034 `docs/adr/0034-rerun-safe-boundaries.md`).
- **ADR directory:** `docs/adr/` — head is `0035-failure-taxonomy-governance.md`; author `0036-...` and `0037-...`; register both in `docs/adr/README.md`. Format precedent: `0034`, `0035`, and the closest conceptual sibling `0026-per-step-advisory-reviewer-model.md` (advisory-now/gating-later, rides 3c credentials) and `0024-manual-execution-mode.md`.
- **Epic + proposal:** `_bmad-output/planning-artifacts/epic-03m-configurable-workflow-bmad-method.md`, `_bmad-output/planning-artifacts/sprint-change-proposal-2026-07-19.md`.

### Traps (read before starting)

- **The runners are NOT the Claude Code skill system.** Do not assume the `Skill` tool or an in-session agent handoff proves anything — the runners are headless Docker CLIs. The proof must exercise the *headless* path (`runner.mjs` driving `claude`/`codex` unattended), which is what production Epic 3m dispatch would use.
- **Runner image has no JDK/Maven** ([[runner-image-has-no-jdk-or-maven]]). `node:22-slim` only; a BMAD phase that shells to `java`/`mvn` exits 127. This is precisely why the proof uses a document phase and why `dev` is flagged `manual`-by-default for 3m-5.
- **Real evidence or an honest "not achieved" — never fabricate** (the 3g-5 real-run-gate lesson). If a headless run can't be driven this session, record that as the finding and the `manual` fallback; do not paste an invented transcript or counts.
- **This is a spike — do not start building.** The strong pull will be to author the `WorkflowDefinition` schema or the engine "while you're here." Resist it: 3m-2/3m-3 own that, and they must inherit ADR 0036's decision first. Anything runtime that merges from 3m-1 is scope leakage.
- **ADR-number drift.** Head is 0035; use **0036/0037**. If a sibling branch has already claimed them, take the next free pair and update the findings note + the epic doc's provisional numbers (same class as the Flyway cross-branch collision hazard [[flyway-v31-cross-branch-collision]], but for ADRs).
- **Mojibake on Windows** ([[mojibake-emdash-openapi-drift]], [[stash-recovery-reinjects-mojibake-invisibly]]). If ADRs/notes use em-dashes, verify by codepoints, not PowerShell display, before committing.

### Logging Requirements (project-wide standard)

Every story is expected to leave touched services observable enough to debug a production incident without re-deploying. **For 3m-1 the task is N/A (see that task for the rationale)** — there is no new runtime service surface; the definition-driven engine's logging lands in 3m-3 (step dispatch, cursor advance, gate parking, failure→recovery). Standard for when it applies:

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):** public application-service entry/exit (`INFO`/`WARN`/`ERROR`); persistence writes (`INFO` + public id, `WARN` on replay); state-machine transitions (`INFO` "transitioned X from {from} to {to}"); recovery/reconciliation loops (`INFO` per-batch, `WARN` per-item).
- **Required context keys:** `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus entity public ids.
- **Forbidden:** payload bytes, secrets/tokens, raw PII — pass through redaction first (the reviewer/agent credential ciphertext must never be logged — relevant in 3m-4, not here).
- **Test contract:** new logging surfaces pinned by a focused list-appender / `OutputCaptureExtension` test.

### Project Structure Notes

- **No production Java/SQL/TS should merge from 3m-1.** Net-new files are docs only: `docs/adr/0036-*.md`, `docs/adr/0037-*.md`, `docs/adr/README.md` (edit), and optionally `docs/spikes/3m-1-*` for the captured proof + findings.
- Any proof scaffolding (a throwaway script driving `runner.mjs`) stays out of the runtime path and is either not committed or committed under a clearly-marked `spikes/` location and never imported by production.
- No foundation-gate / OpenAPI / registry impact in 3m-1 (no schema, no error codes, no endpoints). The gate work is 3m-10.

### References

- [Source: _bmad-output/planning-artifacts/epic-03m-configurable-workflow-bmad-method.md#Story 3m-1] — ACs (verbatim above) + epic framing + the "central architectural decision" section.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-07-19.md] — Epic 3m proposal; locked decisions (generic engine + BMAD preset; executor = runner kind + BMAD role; full 9-phase; editor included; spike-first; loops via rerun-from-step); provisional FR-Nx1..5.
- [Source: docs/adr/0026-per-step-advisory-reviewer-model.md] — closest sibling: advisory-now/gating-later, binding rides the 3c per-project credential model; provenance. Template for ADR 0037's "no new credential subsystem" stance.
- [Source: docs/adr/0024-manual-execution-mode.md] — `manual` runner kind + `WaitingForManualExecution`; the fallback path AC3 routes to.
- [Source: docs/adr/0034-rerun-safe-boundaries.md] — rerun-from-step safety; the mechanism ADR 0037 (AC6) uses for BMAD loops instead of native branching.
- [Source: runners/RUNNER_CONTRACT.md, runners/claude/lib/runner.mjs, runners/codex/lib/runner.mjs] — the headless runner contract + entrypoints the proof drives; `node:22-slim` image.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java] — the state machine ADR 0036 decides whether to extend (target: no new value).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/RunnerKind.java] — runner kinds incl. `manual`; the executor's kind axis.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectConnectorResolver.java] — the resolution seam ADR 0037 confirms the executor binding rides.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java] — dispatch seam the engine (3m-3) will call per step.
- [Source: .claude/skills/bmad-create-architecture/] — the BMAD agent used for the Task-1 proof; full 9-agent catalog listed under Key Seams for 3m-5.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story

### Debug Log References

- Grounding reads (live tree, this session): `runners/RUNNER_CONTRACT.md`, `runners/claude/README.md`, `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java`, `docs/adr/0034-rerun-safe-boundaries.md`, `docs/adr/README.md`. These grounded the ADRs against the *shipped* runner/skill mechanism and the real state machine (not assumptions).
- No test run / build: docs-only spike, no code changed. No foundation-gate / OpenAPI / registry impact.

### Completion Notes List

- **Deliverables COMPLETE:** ADR 0036 (run-model), ADR 0037 (executor-binding), findings note `docs/spikes/3m-1-findings.md`; both ADRs registered in `docs/adr/README.md` (Proposed 2026-07-19). No production runtime code merged (AC1 honored).
- **Run-model decision (ADR 0036):** reuse `workflow_runs` + `WorkflowState` with a `current_step_index` cursor; **no** parallel run type; **no** per-BMAD-phase states (step identity = definition data). Grounded in the real `WorkflowState.java` — its states are pipeline-*semantic*, but the gates/terminals + `EXECUTING`-as-running are generic and reused; the cursor supplies "which phase". Target: zero new `WorkflowState` values.
- **Executor-binding decision (ADR 0037):** `{RunnerKind + 3c project_credentials + bmad_role}` via `ProjectConnectorResolver`; **no** new credential subsystem, **no** new runner kind, **no** bundle-schema change. Invocation mechanism is now concrete + low-risk: the runner images ALREADY vendor Claude Code skills (`obra/superpowers` → `~/.claude/skills/`, story 3a-7) and ALREADY have a flag-gated prompt-augmentation seam (OpenSpec 3a-8 `DELIVERYLINE_RUNNER_OPENSPEC`) — so a `bmad_role` = vendor `.claude/skills/bmad-*` + a `DELIVERYLINE_RUNNER_BMAD_ROLE` prompt nudge. Preset immutable + per-project override. Loops via `rerunFromStep` (ADR 0034), not native branching.
- **⚠️ STALE-MEMORY CORRECTION (the spike's most valuable byproduct):** the runner images **DO** carry JDK 21 + Maven 3.9 (`RUNNER_CONTRACT.md` §"JDK + Maven toolchain"; `runners/claude/README.md`). The `[[runner-image-has-no-jdk-or-maven]]` memory and this story's own **DD-4** ("`dev` must be `manual` — no build toolchain") are **stale/wrong**. Findings §5.3 supersedes DD-4: 3m-5 must NOT hardcode `dev=manual`.
- **⚠️ USER-DEFERRED GATE (blocks review → done):** Task-1's **live** headless-run proof (a captured transcript of `claude -p` acting as a vendored BMAD skill) was **NOT executed this session** — it needs Docker + egress (`network-mode=bridge`) + a real Claude/Codex credential. This is the SAME headless-skill-activation question stories 3a-6 (OpenSpec) and 3a-7 (superpowers) explicitly deferred to real execution (story 3.8), and the same real-run-gate posture as 3g-5. **No transcript/run-id/counts were fabricated.** Runbook for Alex: `docs/spikes/3m-1-findings.md` §4. Outcome that closes the gate: either a captured proof, or a documented per-phase "defaults to `manual`" finding.
- **Provisional:** epic number `3m`; ADR numbers `0036`/`0037` (head was 0035). Confirm still-free at merge (ADR-number cross-branch hazard).
- **Scope discipline:** no Flyway, no registry, no REST/CLI/FE, no engine — those are 3m-2 onward. Design deliverables only, exactly as a spike should be.

### File List

**New (docs — the spike deliverables):**
- `docs/adr/0036-configurable-workflow-run-model.md`
- `docs/adr/0037-per-step-executor-binding.md`
- `docs/spikes/3m-1-findings.md`

**Modified (docs):**
- `docs/adr/README.md` (registered 0036 + 0037 in the index)

**No production code / tests / schema changed** (docs-only spike, AC1).

## Change Log

| Date       | Version | Description                                                                 | Author |
|------------|---------|-----------------------------------------------------------------------------|--------|
| 2026-07-19 | 0.1     | Created 3m-1 spike story: headless BMAD-agent invocation proof + ADR 0036 (run-model) + ADR 0037 (executor-binding) + findings note. Docs-only; no runtime code. `backlog -> ready-for-dev`. | Bob (create-story) |
| 2026-07-19 | 0.2     | dev-story: authored ADR 0036 (reuse workflow_runs + step cursor), ADR 0037 (executor = RunnerKind + 3c credential + bmad_role via vendored-skill + prompt-nudge; loops via rerunFromStep), findings note; registered both ADRs. Grounded against shipped runner contract + `WorkflowState`. Corrected stale JDK/Maven assumption (dev NOT forced manual). Task-1 LIVE headless-run proof USER-DEFERRED (Docker+egress+credential; runbook §4; NOT fabricated) — gates review->done. `ready-for-dev -> review`. | Amelia (dev-story) |
| 2026-07-19 | 0.3     | code-review (3 Sonnet adversarial layers; ECH verified vs real code): 8 patches applied — ADR 0037 credential-path factual fix (RunnerSecretsService not ProjectConnectorResolver; silent host-fallback), ADR 0036 transition-EDGES caveat, findings §5 stage/artifactType-vocabulary blocker + rerun-extension + cursor edge cases + credential-seam + minor open items, framing softeners. 1 decision resolved by Alex: SPLIT live headless-run into follow-up spike 3m-1b; 3m-1 re-scoped to ADR/design deliverables. 5 dismissed. `review -> done`. | Amelia (code-review) |
