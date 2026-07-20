# Story 3m.1b: Headless BMAD-Agent Live-Run Proof

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- Split from 3m-1 by bmad-code-review 2026-07-19 (Alex, epic-owner sign-off, option (c)). 3m-1 delivered the ADR/design (mechanism decided); this story is the LIVE, credentialed proof deferred from 3m-1 AC2. HUMAN-GATED: needs Docker + egress + a real Claude/Codex credential — the operator (Alex) runs it; the agent cannot. NEVER fabricate a transcript/run-id. -->

## Story

As a backend developer (with a live runner environment),
I want a captured, real headless run of a runner acting as a vendored BMAD agent that returns a typed artifact,
so that Epic 3m's executor-invocation mechanism is proven in reality — not just designed — and the per-phase automatable-vs-`manual` defaults that seed 3m-5 are finalized on evidence rather than assumption.

## Context

This is the **live-proof half of the Epic 3m foundation spike**, split out of story **3m-1** by code-review (Alex, 2026-07-19). Story 3m-1 delivered the two ADRs and decided the invocation mechanism on paper; its AC2 asked for a *recorded* headless-run proof, which could not be executed in the authoring session (no Docker, no egress, no live credential). Rather than defer it inside 3m-1 (which over-claimed `review`), the epic owner carved the live proof into this dedicated ticket.

**Why this is its own story:** the proof requires infrastructure the design work does not — a built runner image, `network-mode=bridge` egress, and a real Claude (or Codex) subscription/API credential. It is **human-gated**: an operator with that environment runs it and pastes back the captured evidence. It does **not** structurally block 3m-2/3m-3 (they proceed on ADR 0036/0037); it **finalizes** the *provisional* per-phase automatable map in `docs/spikes/3m-1-findings.md` §3 that 3m-5 uses to seed each BMAD step's default `runner_kind`.

**The one thing this proves that nothing else can:** the mechanism is "vendor a `.claude/skills/bmad-*` skill into the runner image + a prompt nudge naming it" (ADR 0037 §2). Whether a skill **reliably fires** in a *single-prompt headless* `claude -p` run (no human, no slash command) is the **same open question** stories 3a-6 (OpenSpec) and 3a-7 (superpowers) explicitly deferred to real execution (story 3.8), because "skills activate by name-mention / task-description match" and confirming auto-firing needs a live credentialed run. This story closes that question for the BMAD skills.

**Dependencies:** none structurally. The proof can use a **throwaway `-v` mount** of the skill into `~/.claude/skills/` — it does **not** need 3m-5's full vendoring. A built `deliveryline/claude-runner:latest` image is required (`docker compose build claude-runner`); a real credential (`CLAUDE_CODE_OAUTH_TOKEN` preferred, or `ANTHROPIC_API_KEY`).

## Acceptance Criteria

1. **Given** a built `claude` runner image with the BMAD architect skill made discoverable on `~/.claude/skills/` (throwaway `-v` mount is sufficient — no 3m-5 vendoring required) and a real Claude credential, **When** the runner is driven **headlessly** (`claude -p`, per `runners/claude/README.md` §"Real invocation") with a prompt nudge naming `bmad-create-architecture` over a `context-bundle.v1.json`, **Then** the run completes and both `runner.stdout` (the transcript) and `runner-result.v1.json` are **captured as real evidence** (pasted into Completion Notes and/or committed under `docs/spikes/3m-1b-*`).
2. **Given** the captured output, **Then** it is asserted (by inspection) to be shape-able into the candidate `architecture` typed-artifact (`artifactId` + `title` + body-markdown, satisfying `isArtifactView` — 3m-6's contract). A produced architecture-shaped document = pass; unrelated/empty output = the skill did not fire.
3. **Given** the **"reliably fires" question**, **Then** the run records whether the BMAD skill **actually activated** in single-prompt headless mode (evidence: the output reflects the architect agent's persona/structure, not a generic response). If it does **not** reliably fire, **Then** that is recorded as the finding (AC5) — a documented "did not fire" is a valid, useful outcome; a fabricated success is not.
4. **Given** the second runner kind, **Then** the same proof is attempted for `codex` **if time/credentials permit** (Codex discovery path `~/.agents/skills/`); if not attempted, that is stated explicitly (no silent omission). Codex SKILL.md parse-compat vs the Claude-authored skills is an open question from 3m-1 review (findings §5.13) this run can settle.
5. **Given** the run outcome(s), **Then** the **provisional per-phase automatable-vs-`manual` map** in `docs/spikes/3m-1-findings.md` §3 is **finalized**: each of the 9 BMAD phases is marked automated (skill fires reliably headlessly) or `manual` (default to `WaitingForManualExecution`) **based on the evidence**, and the finding is written back so 3m-5 seeds defaults on fact. Phases not directly exercised inherit the architect result with an explicit "inferred, not individually proven" note.
6. **Given** the secret-and-honesty discipline, **Then** no credential value appears in any captured artifact (only the variable **name + presence**, per `RUNNER_CONTRACT.md` §"Secrets & logging discipline"); and **no run-id, transcript, or token count is fabricated** — if the run cannot be executed, the story stays open rather than inventing a result (the 3g-5 real-run-gate discipline).

## Tasks / Subtasks

- [ ] **Task 1 — Build the runner image + make the BMAD architect skill discoverable (AC: #1)**
  - [ ] `docker compose build claude-runner` (the hand-built `:latest` does **not** auto-rebuild on merge — [[runner-image-stale-causes-exit-20]]); `docker run --rm deliveryline/claude-runner:latest --self-test` to confirm health + that the skills dir resolves.
  - [ ] Make `.claude/skills/bmad-create-architecture/` discoverable on the image's personal-skills path — either the 3m-5 vendoring (if it has landed) or a **throwaway** `-v .../bmad-create-architecture:/home/claude/.claude/skills/bmad-create-architecture:ro` mount for this spike only (do not commit throwaway mounts).

- [ ] **Task 2 — Drive one real headless run as the architect agent + capture (AC: #1, #2, #3, #6)**
  - [ ] Prepare a workspace: `work=$(mktemp -d); mkdir -p "$work"/{input,output,logs}; chmod 0777 "$work"/{output,logs}`; copy the valid fixture `deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json` → `"$work/input/context-bundle.v1.json"` (per `runners/claude/README.md` §"Testing the image locally").
  - [ ] Supply the **BMAD role nudge**: a prompt (via `CLAUDE_PROMPT_TEMPLATE` seam, or by driving the entrypoint with a prompt naming the skill) that instructs the run to "act as the `bmad-create-architecture` agent and produce an architecture document." (This is the manual analogue of the `DELIVERYLINE_RUNNER_BMAD_ROLE` nudge ADR 0037 §2 specifies for production.)
  - [ ] Run **with egress + credential**: `docker run --network=bridge -e CLAUDE_CODE_OAUTH_TOKEN=… -v "$work/input:/workspace/input:ro" -v "$work/output:/workspace/output" -v "$work/logs:/workspace/logs" … deliveryline/claude-runner:latest --stage=spec-investigation` (network-mode `bridge` is required for a real Claude call; default is `none`). Model via `CLAUDE_MODEL`→`ANTHROPIC_MODEL`.
  - [ ] **Capture** `"$work/logs/runner.stdout"` + `"$work/output/runner-result.v1.json"` as the proof. Confirm no credential **value** leaked into either (only name+presence).
  - [ ] Assert (by inspection) the output is architecture-document-shaped and maps to `artifactId`+`title`+body-markdown (`isArtifactView`).
  - [ ] Record the **fired / did-not-fire** verdict with evidence (persona/structure present vs generic output).

- [ ] **Task 3 — (optional, if credentials/time permit) Codex parity attempt (AC: #4)**
  - [ ] Repeat Task 2 for the `codex` runner (`~/.agents/skills/` discovery path, `CODEX_AUTH_JSON`/API per `runners/codex/README.md`). If not attempted, state so explicitly in Completion Notes — do not silently omit.

- [ ] **Task 4 — Finalize the per-phase automatable map + write it back (AC: #5)**
  - [ ] Update `docs/spikes/3m-1-findings.md` §3: convert the **provisional** default-executor column to an evidence-backed one — architect (and any other exercised phase) marked automated or `manual` per the run; the other document phases inherited from the architect result with an explicit "inferred, not individually proven" note; `dev` decided per its own note (route-to-implementation-path vs runner).
  - [ ] Add a short outcome line to `docs/spikes/3m-1-findings.md` §4 (the live-run section): what was run, the verdict, and any per-phase adjustment 3m-5 must apply.

- [ ] **Task 5 — Feed the result to 3m-5 (AC: #5)**
  - [ ] Ensure 3m-5's seed-defaults are unblocked: the finalized map is the authoritative input for each BMAD step's default `runner_kind` and `human_gated` flag. If any phase is `manual`-by-default, note it in the 3m-1b Completion Notes so 3m-5's story picks it up.

- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] **N/A — this is a live-proof/docs spike, no production runtime code.** The only "logging" concern is the **secret-discipline assertion** (AC6): the captured `runner.stdout`/`runner-result.v1.json` must contain no credential value (name+presence only), which the runner's own conformance posture already enforces (`RUNNER_CONTRACT.md` §"Secrets & logging discipline"). No new SLF4J surface is added by this story.

## Dev Notes

### What this story is (and is not)

- **Is:** the **real, credentialed proof** that a headless runner can act as a vendored BMAD agent and return a typed artifact — with captured evidence — plus the **finalized per-phase automatable map** that seeds 3m-5. Human-gated (an operator with Docker + egress + a credential runs it).
- **Is not:** the *design* (that is 3m-1's ADR 0036/0037 — already `done`), the runner-image vendoring (that is 3m-5; this story can use a throwaway mount), or any production code. No Flyway, no engine, no REST/CLI/FE.

### The mechanism being proven (decided in 3m-1)

Per **ADR 0037 §2**: a runner "becomes" a BMAD agent by (a) the `.claude/skills/bmad-*` skill being discoverable on the image's personal-skills path (`~/.claude/skills/`, confirmed against Claude CLI `2.1.149`; the superpowers collection is already vendored this way, story 3a-7), and (b) a prompt nudge naming the skill (`DELIVERYLINE_RUNNER_BMAD_ROLE` in production; a manual prompt here). This is the **exact same headless-activation question** 3a-6/3a-7 deferred to story 3.8 — this story settles it for BMAD.

### Honesty discipline (non-negotiable)

- **Never fabricate.** If the run cannot be executed (no environment/credential this session), the story **stays open** — do not invent a transcript, run-id, or token count. A documented "not run this session" or "did not fire" is the correct outcome; an invented success is a hard failure. (The 3g-5 real-run-gate lesson; [[token-usage-full-capture-codex-json]].)
- **Secret discipline:** only the credential **variable name + presence** may appear in any captured artifact — never the value. Assert this on the captured stdout/result (`RUNNER_CONTRACT.md`).

### Key seams & exact locations (verified in 3m-1)

- **Runner + invocation:** `runners/claude/lib/runner.mjs`; `runners/claude/README.md` §"Real invocation (story 3.8)" (`claude -p --dangerously-skip-permissions`, prompt on stdin, cwd `/workspace/repo`, model from `ANTHROPIC_MODEL`, egress `network-mode=bridge`); `CLAUDE_PROMPT_TEMPLATE` / `CLAUDE_EXEC_ARGS` seams; contract `runners/RUNNER_CONTRACT.md` (mounts, exit codes, secret discipline).
- **Auth:** `CLAUDE_CODE_OAUTH_TOKEN` (subscription, preferred) or `ANTHROPIC_API_KEY`; `DELIVERYLINE_RUNNER_SKIP_AUTH=true` is mock-only (not for a real run).
- **Skill source:** `.claude/skills/bmad-create-architecture/`. Discovery path `~/.claude/skills/<skill>/SKILL.md` (Claude), `~/.agents/skills/` (Codex).
- **Fixture:** `deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json`.
- **The map to finalize + runbook:** `docs/spikes/3m-1-findings.md` §3 (provisional per-phase map) + §4 (runbook this story executes).
- **Candidate typed-artifact contract:** `isArtifactView` ([[artifact-read-dto-must-satisfy-isartifactview]]) — `architecture` kind = artifactId + title + body-markdown.

### Traps

- **`:latest` is not auto-rebuilt** — a stale local `:latest` won't have the JDK/Maven toolchain or (if you vendored) the BMAD skills; rebuild + `--self-test` first ([[runner-image-stale-causes-exit-20]]).
- **`network-mode` defaults to `none`** — a real Claude call needs `--network=bridge`; a `none` run can't reach the provider and will fail auth/egress, not prove anything.
- **Throwaway mounts are not commits** — the `-v` skill mount for this spike must not be committed; the real vendoring is 3m-5's job.
- **Codex ≠ Claude discovery path** — `~/.agents/skills/` vs `~/.claude/skills/`; and Codex SKILL.md parse-compat is unproven (findings §5.13).
- **Mojibake on Windows** — if writing the outcome back with em-dashes, verify by codepoints not PowerShell display ([[mojibake-emdash-openapi-drift]], [[stash-recovery-reinjects-mojibake-invisibly]]).

### Project Structure Notes

- No production Java/SQL/TS. Net changes are docs only: the finalized map in `docs/spikes/3m-1-findings.md` (§3/§4) + optionally captured evidence under `docs/spikes/3m-1b-*`. No foundation-gate / OpenAPI / registry impact.

### References

- [Source: _bmad-output/implementation-artifacts/3m-1-spike-and-adr-executor-invokes-bmad-agent-and-run-model-decision.md] — parent spike; Review Findings (the split decision) + Task 1 (mechanism decided).
- [Source: docs/adr/0037-per-step-executor-binding.md §2] — the vendored-skill + prompt-nudge invocation mechanism this run proves.
- [Source: docs/spikes/3m-1-findings.md §3 (provisional map) + §4 (runbook)] — what to finalize + the exact steps.
- [Source: runners/claude/README.md] — real-invocation notes, auth modes, "Testing the image locally", superpowers activation finding (the precedent).
- [Source: runners/RUNNER_CONTRACT.md] — mounts, exit codes, secret discipline, stage→artifactType.
- [Source: _bmad-output/planning-artifacts/epic-03m-configurable-workflow-bmad-method.md] — Follow-up note (3m-1b).

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date       | Version | Description                                                                 | Author |
|------------|---------|-----------------------------------------------------------------------------|--------|
| 2026-07-19 | 0.1     | Created 3m-1b (split from 3m-1 by code-review, epic-owner sign-off): the live, credentialed headless-run proof of the BMAD-agent invocation mechanism + finalize the per-phase automatable map for 3m-5. Human-gated (Docker + egress + real credential). `backlog -> ready-for-dev`. | Bob (create-story) |
