# Spike 3m-1 — Findings: Executor-Invokes-BMAD-Agent + Run-Model

**Story:** 3m-1 (Epic 3m foundation spike). **Date:** 2026-07-19. **Author:** bmad-dev-story (Opus 4.8 [1m]).
**Deliverables:** ADR [0036](../adr/0036-configurable-workflow-run-model.md) (run-model), ADR [0037](../adr/0037-per-step-executor-binding.md) (executor binding), this findings note.

This note records what the spike **confirmed by reading the live codebase**, what it **decided**, the **per-phase automatable map**, the **live-run proof it could not execute this session (and the honest gate for it)**, and the **story-scope adjustments** it forces on 3m-2..3m-11 before they start.

---

## 1. Confirmed assumptions (from the live tree)

- **[CONFIRMED] The runner already has the skill-discovery mechanism the epic needs.** `runners/claude/README.md` (story 3a-7): the `obra/superpowers` skills collection is **vendored** into the image (`runners/vendor/superpowers`, `COPY`'d, offline-safe) and exposed on Claude Code's **personal-skills dir** `~/.claude/skills/<skill>/SKILL.md` (confirmed against CLI `2.1.149`); Codex uses `~/.agents/skills/`. The BMAD agents are themselves Claude Code skills (`.claude/skills/bmad-*/`). ⇒ "a runner becomes the architect agent" = vendor `.claude/skills/bmad-*` the same way + activate the named skill. No new mechanism to invent.
- **[CONFIRMED] A prompt-augmentation seam already exists.** The OpenSpec 3a-8 layer appends a prompt "delta" to the generated prompt, gated on the backend-injected env flag `DELIVERYLINE_RUNNER_OPENSPEC` (default OFF), with **no** change to the frozen `context-bundle.v1` schema, mounts, or exit codes (`runners/RUNNER_CONTRACT.md`). ⇒ a `bmad_role` rides the identical shape: an env flag naming the skill → a prompt nudge.
- **[CONFIRMED] Headless invocation vector.** `runners/claude/README.md` (story 3.8): the entrypoint invokes `claude -p --dangerously-skip-permissions`, prompt on stdin, cwd `/workspace/repo`, model from `ANTHROPIC_MODEL`; real runs require egress (`deliveryline.runner.docker.network-mode=bridge`, default `none`). `CLAUDE_PROMPT_TEMPLATE` + `CLAUDE_EXEC_ARGS` are existing prompt/arg seams.
- **[DOCUMENTED (per committed contract, not build-run this session) — corrects a stale assumption] The runner images carry JDK 21 + Maven 3.9.** `runners/RUNNER_CONTRACT.md` §"JDK + Maven toolchain" + `runners/claude/README.md`: both images ship a pinned Temurin JDK 21 + Maven 3.9, present in production *and* the offline build, "agent plans can run real `mvn` builds and compile tasks via shell-out" (self-test asserts the pins). **Evidence is the committed contract docs, not a build actually executed this session** — and the `:latest` images do not auto-rebuild on merge, so a stale local `:latest` could still lack them (`--self-test` before relying on it). ⇒ **The `[[runner-image-has-no-jdk-or-maven]]` memory is stale**; the `dev` phase is **not** forced to `manual` for lack of a build toolchain. (The story's DD-4 reasoning was based on the stale memory; superseded — see §5.)
- **[CONFIRMED] Reuse target for the run-model.** `WorkflowState` (`domain/registry/WorkflowState.java`) carries pipeline-*semantic* states (`WAITING_FOR_SPEC_APPROVAL`, `EXECUTING`, `WAITING_FOR_REVIEW`, …) plus generic gates/terminals; `isTerminal()` (4.30) is the single terminality predicate; edges are hand-enumerated in `WorkflowTransitionTable` (no wildcard, ADR 0034). ⇒ ADR 0036 reuses the *generic* states + a cursor, not per-BMAD-phase states.
- **[CONFIRMED] Loop mechanism exists.** `RecoveryService.rerunFromStep` + ADR 0034: re-seat + re-dispatch a step, approval invalidation, lineage-graft artifact supersession, append-only history. ⇒ BMAD loops need no new branching.

## 2. Decisions (recorded in the ADRs)

- **Run-model = reuse `workflow_runs` + `WorkflowState` with a `current_step_index` cursor; NO per-BMAD-phase states, NO parallel run type.** (ADR 0036.) Step identity is `definition.steps[cursor]`, data — not a state. Target: **zero** new `WorkflowState` values.
- **Executor binding = `{runner_kind, project credential, bmad_role}`, resolved via `ProjectConnectorResolver`; no new credential subsystem, no new runner kind.** BMAD role delivered by vendored skill + prompt nudge over a `DELIVERYLINE_RUNNER_BMAD_ROLE` env flag (OpenSpec-3a-8 twin). Preset immutable + per-project override. Loops via `rerunFromStep`, not branching. (ADR 0037.)

## 3. Per-phase automatable-vs-manual map (informs the 3m-5 seed defaults)

| BMAD step | BMAD skill | Produces | Phase-1 default executor | Note |
|---|---|---|---|---|
| analyst | `bmad-agent-analyst` | brief | automated (claude/codex) | document phase |
| pm | `bmad-create-prd` | prd | automated + **human-gated** | product acceptance (FR8) |
| ux | `bmad-create-ux-design` | ux_design | automated | document phase |
| architect | `bmad-create-architecture` | architecture | automated + **human-gated** | technical gate; **proof phase** |
| epics | `bmad-create-epics-and-stories` | epics | automated | document phase |
| story | `bmad-create-story` | story | automated | document phase |
| dev | `bmad-dev-story` | code | automated **or** manual | JDK/Maven present (§1) ⇒ *not* forced manual; may still route to the existing implementation path — 3m-5 decides |
| review | `bmad-code-review` | review | automated + **human-gated** | technical acceptance (FR16) |
| retro | `bmad-retrospective` | retro | automated | document phase |

Default `human_gated=true` for **pm / architect / review** (the product/technical approval boundaries). All defaults are per-project overridable (3m-4/3m-7). **These defaults are provisional until §4's live-run confirms which phases reliably auto-fire headlessly.**

## 4. Live-run proof — status: NOT executed this session (honest gate)

**AC2 wants a captured transcript of a headless runner acting as a BMAD agent. That requires a built runner image with the BMAD skills vendored, egress (`network-mode=bridge`), and a real Claude/Codex credential — none of which is exercised from this authoring session.** Per AC3 and the project's own precedent, this is recorded honestly rather than fabricated:

- **Precedent:** stories 3a-6 (OpenSpec) and 3a-7 (superpowers) hit the identical question — *does a skill fire in single-prompt headless `claude -p`?* — and **explicitly deferred the live-activation proof to real execution (story 3.8)**, shipping the "present + discoverable" minimum. 3m-1 inherits that posture: the *plumbing/binding is designed* (ADR 0037), the *live activation* is a real-run gate.
- **No transcript, no run-id, no counts are fabricated.** ([[token-usage-full-capture-codex-json]] / 3g-5 real-run-gate discipline: a documented "not achieved this session" is acceptable; an invented result is not.)

### Runbook — how to execute the proof (Alex, on a machine with Docker + a Claude credential)

1. Vendor the architect skill for the proof: make `.claude/skills/bmad-create-architecture` available on the image's personal-skills path (the 3m-5 vendoring, or a throwaway `-v` mount into `~/.claude/skills/` for the spike only).
2. Rebuild the claude runner: `docker compose build claude-runner` then `docker run --rm deliveryline/claude-runner:latest --self-test` (confirm skills dir resolves).
3. Prepare a workspace with a `context-bundle.v1.json` (reuse the valid fixture, `runners/claude/README.md` "Testing the image locally") and a prompt that names the architect skill (the `DELIVERYLINE_RUNNER_BMAD_ROLE`-style nudge).
4. Run with egress + credential: `docker run --network=bridge -e CLAUDE_CODE_OAUTH_TOKEN=… -v …` per the README's real-invocation notes.
5. **Capture** `runner.stdout` + the `runner-result.v1.json`; assert the output can be shaped into the candidate `architecture` typed-artifact (artifactId + title + body-markdown, satisfying `isArtifactView`).
6. Record the **per-phase verdict**: reliably auto-fired ⇒ automated default stands; not reliably ⇒ set that phase's 3m-5 default to `manual`.

**Outcome that gates 3m-1 → done:** either a captured proof, or a documented per-phase "defaults to manual" finding. This is the story's remaining human-driven item (mirrors 3g-5's user-deferred real-run gate).

## 5. Story-scope adjustments forced on 3m-2..3m-11 (apply before they start)

1. **3m-2:** `workflow_runs` gets a nullable `workflow_definition_id` FK + nullable `current_step_index` (ADR 0036). Target **zero** new `WorkflowState` values — the schema must not add per-phase states.
2. **3m-2/3m-4:** add the **per-project step-override** table (ADR 0037 §4) — not preset-row mutation.
3. **3m-5:** **`dev` is NOT forced to `manual`** — the JDK/Maven finding (§1) supersedes the story's DD-4. 3m-5 decides `dev` = automated-via-runner vs. route-to-existing-implementation-path; do not encode "dev must be manual". Seed the vendored `.claude/skills/bmad-*` into **both** runner images (mirror rule) + the `DELIVERYLINE_RUNNER_BMAD_ROLE` prompt-nudge (OpenSpec-3a-8 twin); rebuild `:latest` (no auto-rebuild on merge).
4. **3m-5 gating defaults:** `human_gated=true` for pm/architect/review; the automatable defaults for other phases are provisional on §4's live-run.
5. **3m-6:** typed `code` artifact reuses the existing implementation-output/PR lineage (FR20/FR40), not a parallel representation.
6. **3m-7:** loops = `rerunFromStep` (ADR 0034) — inherits approval-invalidation + lineage-graft supersession; no native branching.
7. **ADR numbers 0036/0037 + epic number 3m are provisional** — confirm still-free against the branch at merge (a sibling could claim an ADR number; [[flyway-v31-cross-branch-collision]]-class hazard for ADRs).

### Review-added scope adjustments (bmad-code-review 2026-07-19, Edge Case Hunter verified against real code)

8. **[BLOCKER] Runner stage/`artifactType` vocabulary is a fixed 3-value set — but BMAD has 9 artifact kinds.** `runners/RUNNER_CONTRACT.md` "Stage → artifactType" accepts exactly 3 stage-token groups (`spec-investigation`/`implementation-plan`/`pr-output`) → 3 `artifactType`s (`spec`/`implementationPlan`/`prOutput`); an unknown stage token exits **13**. `RunnerStage` is an exhaustively-switched enum. §3's 9 typed artifacts (brief/prd/ux_design/architecture/epics/story/code/review/retro) have **no** mapping onto this contract — passing a new token to today's entrypoint crashes. **3m-2/3m-3/3m-5 must either extend the stage/artifactType vocabulary (both runner Dockerfiles + entrypoints + `RunnerStage`, per the RUNNER_CONTRACT change rule) or map BMAD phases onto the existing 3 tokens.** Only `code` is currently addressed (§5.5).
9. **[3m-3/3m-7] `rerunFromStep` needs EXTENSION, not free reuse.** `SafeRerunStep` is a 2-value enum {`investigating`,`executing`} with no step-index; approval-invalidation has 2 kinds (`spec`/`implementationPlan`). Cursor-index rerun for an arbitrary BMAD step requires widening the target + new transition edges + generalizing the approval-kind model to the definition's gates (ADR 0037 §5 amended). ADR 0034's *safety guarantees* are the model; its 2-value surface is not sufficient.
10. **[3m-3] New transition EDGES required (not new states).** `WorkflowTransitionTable` has no `EXECUTING→EXECUTING` edge and no advance/complete edge out of `WAITING_FOR_MANUAL_EXECUTION` — so the cursor can't advance between two adjacent non-gated steps, nor can a manual non-gated step reach the next step / `COMPLETED`. 3m-3 adds the edges the cursor-walk needs (ADR 0036 Consequences amended). "Zero new states" ≠ "zero new edges."
11. **[3m-2/3m-3] Cursor edge cases:** zero-step definition (no last index → `COMPLETED`) and cursor integrity when a mutable custom definition is edited under an in-flight run (ADR 0036 Consequences amended).
12. **[3m-4] Credential-resolution seam is not what the original ADR said.** Ordinary dispatch uses `RunnerSecretsService.resolveSecretsForRunner` (not `ProjectConnectorResolver`, which is REVIEW-only and *silently falls back to the host secret*). 3m-4 must pick a seam and, if reusing the reviewer resolver, disable the silent host-fallback so an unconfigured step fails fast (ADR 0037 §3 amended). Consider splitting `STEP_EXECUTOR_NOT_CONFIGURED` into "no binding" vs "no credential for kind."
13. **[3m-4/3m-5] Minor open items:** bind-time vs dispatch-time validation of an unvendored `bmad_role` (a mismatch must not degrade to a silent unactivated-skill no-op); `manual` + non-null `bmad_role` combination (reject / ignore / surface to operator?); Codex `~/.agents/skills/` SKILL.md parse-compat is asserted-not-verified vs the superpowers precedent (BMAD skills are Claude-Code-authored).

---

**Confidence:** the *run-model direction* (reuse + cursor) is high-confidence; the *executor-binding direction* is sound but its wiring has real, now-documented extension work (stage vocabulary #8, rerun #9, transition edges #10, credential seam #12) that 3m-2/3m-3/3m-4 must do — this is not free reuse. The **live headless-activation reliability** per phase is carved into a follow-up spike ticket (see the story's Review decision) with an established deferral precedent (3a-6/3a-7/story 3.8), not a design unknown. The "no blocker to reuse the aggregate" conclusion is a **backend** conclusion — the FE/audit label-rendering layer (#5's queue/audit surfaces) was not inspected and is 3m-8's to verify.
