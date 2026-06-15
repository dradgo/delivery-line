# Sprint Change Proposal — OpenSpec Pipeline Integration (Story 3a-8)

- **Date:** 2026-06-13
- **Author:** Alex (via Correct Course workflow)
- **Trigger type:** New capability — formalize an approved design into a sprint story
- **Affected epic:** Epic 3a — Real Agent + Repo Stack (active slice)
- **Proposed story:** `3a-8` (OpenSpec spec-driven authoring during runs)
- **Change scope classification:** Moderate (backlog reorganization — one new story — then `bmad-create-story`)
- **Design source:** `docs/superpowers/specs/2026-06-13-openspec-pipeline-integration-design.md` (brainstormed + approved; commit `8b14351`)

---

## Section 1 — Issue Summary

Story **3a-6** bakes the `openspec` CLI into both runner images but leaves OpenSpec **inert** during runs — the headless agent (`codex exec` / `claude -p`, single hardcoded operating-mode prompt) never invokes OpenSpec's slash-command / `AGENTS.md`-driven workflow. The decision (taken with Alex) is to actually **drive OpenSpec's phases** so each run authors a durable, methodology-shaped **change folder** (`proposal.md` / `specs/` / `design.md` / `tasks.md`) that is **committed into the target repository as part of the delivered PR**.

The full design was developed through a brainstorming session and approved; this proposal adds it to the sprint as story **3a-8**. The design doc is the authoritative spec; this proposal is the backlog-reorganization artifact.

**Decisions already taken (design session):**
- **D1** Primary value = durable in-repo specs (our 3 stages stay; OpenSpec gives their output a structured home).
- **D2** Persistence = the **target repo / PR** (folder committed alongside the code).
- **D3** **1:1 stage mapping** — spec-investigation → `proposal.md`+`specs/`; implementation-plan → `design.md`+`tasks.md`; pr-output → implement `tasks.md` + assemble + commit the folder.
- **D4** **Opt-in, default OFF** (`deliveryline.runner.openspec.enabled`); flag off ⇒ byte-identical to today.
- **D5** **Approach A** — entrypoint-orchestrated, prompt-driven authoring (agent authors OpenSpec-shaped content to stdout; entrypoint scaffolds + assembles). Rejected B (backend post-hoc reshaping — loses methodology value) and C (slash-command emulation — unverified/high-risk).
- **D6** **No runner-contract / schema change** — each stage's OpenSpec content is its existing single artifact; the folder is reconstructed at pr-output from the two artifacts the bundle already carries.

---

## Section 2 — Impact Analysis

### Epic Impact
- **Epic 3a stays valid and unchanged in intent.** Purely additive — one new story appended to the active 3a slice. Highest existing 3a id is **3a-7**; next free id is **3a-8**.

### Story Impact
- **No existing story is modified.** 3a-8 **depends on 3a-6** (the `openspec` CLI must be present in both images) and **rides shipped seams**: 3.10 (`approvedImplementationPlanReference` carry-forward), 3a-2 (repo context bundle + `/workspace/repo` mount), 2.8 (spec artifact model). It is independent of 3a-7 (superpowers).

### Artifact Conflicts
| Artifact | Impact |
|----------|--------|
| **PRD** | None. Below PRD altitude. **N/A.** |
| **Architecture** | None structural — no runner-contract change (exit codes, mounts, bundle/result schema, stage mapping all byte-identical); opt-in, default-off. Optional note that runs can author OpenSpec change folders. **Mostly N/A.** |
| **UX Design** | None. **N/A.** |
| **Epics** | `epic-03-agent-execution.md` gains a **Story 3a-8** stub (this proposal). |
| **Sprint status** | `sprint-status.yaml` gains the `3a-8-...: backlog` entry. |
| **CI / build** | `runner-image-compat` builds both images offline; the conformance ITs gain openspec-enabled stage assertions (mock CLI). The offline mock-openspec (from 3a-6) may need `init`/`validate` stubs. |
| **Docs** | Both runner READMEs + `runners/RUNNER_CONTRACT.md` document the opt-in authoring mode. |

### Technical Impact & Constraints (decisive ones)
1. **Approach A** — the only behavioral code is in both **entrypoints** (mirror rule, one PR): derive `change-id`, scaffold convention, augment the per-stage `PROMPT_INSTRUCTION`, assemble + commit `openspec/changes/<id>/` at pr-output. Plus a `runner.mjs split-fenced` helper.
2. **Read-only posture preserved** — spec/plan stages stay `--sandbox read-only` and emit OpenSpec content to **stdout** (their normal artifact channel); only pr-output mutates `/workspace/repo`. `openspec init` runs **only** at pr-output.
3. **No agent file-writes in read-only stages** — the agent emits OpenSpec-shaped Markdown to stdout via a documented **file-fence convention**; the folder is reconstructed at pr-output.
4. **Backend change is minimal** — config flag `deliveryline.runner.openspec.enabled` (OPTIONAL+UNVALIDATED) surfaced as container env `DELIVERYLINE_RUNNER_OPENSPEC`, threaded like `DELIVERYLINE_RUNNER_STAGE`. No orchestration/bundle-composition change.
5. **Risk is headless authoring reliability** (not installation) — mitigated by `openspec validate` + best-effort assembly + off-by-default; first pilot's spec quality may be rough. `openspec init` non-interactivity is a spike (fallback: pre-baked skeleton).

---

## Section 3 — Recommended Approach

**Direct Adjustment** — add story **3a-8** to the active 3a slice, sequenced **after 3a-6** and independent of 3a-7.

**Rationale:** additive, below MVP altitude, design already approved. Splitting it from 3a-6 keeps the thin "CLI install" enabler independently mergeable from the larger authoring-orchestration work. **Effort:** Med. **Risk:** Med, concentrated in the headless-authoring verification (explicit spike + best-effort + default-off fallback).

---

## Section 4 — Detailed Change Proposals

> Story definition (turned into a full story file via `bmad-create-story`), plus the epic stub + `sprint-status.yaml` addition. ACs are an AC-shape sketch; the design doc is authoritative.

### Story 3a-8 — OpenSpec spec-driven authoring during runs

**As** the platform, **I want** each runner stage to author its OpenSpec change-folder artifact (behind an opt-in flag) and pr-output to assemble + commit `openspec/changes/<id>/` into the delivered PR, **so that** every run leaves a durable, version-controlled, methodology-shaped spec next to the code.

**Dependencies:** 3a-6 (openspec CLI in both images), 3.10 (`approvedImplementationPlanReference` carry-forward), 3a-2 (repo context bundle + repo mount), 2.8 (spec artifact model), 3.3/3.4 (the two runner images + entrypoints — mirror rule).

**AC-shape reference (see design doc for the authoritative spec):**
- **Opt-in gate:** `deliveryline.runner.openspec.enabled` (default **false**, OPTIONAL+UNVALIDATED per `[[validated-config-needs-test-yaml]]`) → container env `DELIVERYLINE_RUNNER_OPENSPEC`. Off ⇒ byte-identical to today (no scaffold, no prompt delta, no `openspec/` folder).
- **1:1 authoring:** spec-investigation authors `proposal.md`+`specs/` deltas; implementation-plan authors `design.md`+`tasks.md`; both to **stdout** (their normal artifact channel) via a documented file-fence convention. Deterministic `change-id` from `ticketRef`+`workflowRunId`.
- **pr-output assembly:** non-interactive `openspec init` into `/workspace/repo`; materialize `openspec/changes/<id>/` from the two carried artifacts (`runner.mjs split-fenced`); `openspec validate`; agent implements `tasks.md`; commit `openspec/` with the code.
- **Carry-forward = existing seams:** read `approvedSpecificationReference` + `approvedImplementationPlanReference` by `referencePath`; **no bundle-schema change**.
- **Failure posture:** additive — `init`/`validate` failure logs WARN + best-effort folder + still ships the code; no new failure-category enum; flag-off = zero new failure surface; degraded carry-forward assembles what is present.
- **Mirror rule:** both entrypoints + both `runner.mjs` + both READMEs + `RUNNER_CONTRACT.md` in one PR.
- **Tests:** entrypoint unit (flag-off byte-identical; flag-on per-stage prompt + read-only scaffolds to output not repo); `runner.mjs split-fenced` unit; conformance ITs (fixture artifacts → folder materializes + `openspec validate` invoked + read-only stages leave repo untouched; flag-off byte-identical). Real authoring quality = live-run spike.

### `sprint-status.yaml` addition (under Epic 3a active slice, after 3a-7)

```yaml
  3a-8-openspec-spec-driven-authoring-in-runner-runs: backlog  # 2026-06-13 sprint-change-proposal-2026-06-13-openspec-pipeline.md: opt-in OpenSpec authoring across the 1:1 stage mapping; change folder committed into the PR. Layered on 3a-6. Design: docs/superpowers/specs/2026-06-13-openspec-pipeline-integration-design.md. epic-3a, NEW. Seq: after 3a-6.
```

---

## Section 5 — Implementation Handoff

- **Scope classification: Moderate.** No PRD/architecture/UX conflict; adds one story to the sprint plan.
- **Handoff:**
  1. **PO/Dev (`bmad-create-story 3a-8`)** — create the full context-engineered story file from the design doc + this definition.
  2. **Dev (`bmad-dev-story`)** — implement after 3a-6 merges; one PR edits both entrypoints + both `runner.mjs` + both READMEs (mirror rule), keeps flag-off byte-identical, keeps the offline conformance build green, and verifies (or documents the limitation on) headless authoring.
- **Success criteria:** flag-off byte-identical; flag-on run authors + commits a valid `openspec/changes/<id>/` folder in the PR; conformance ITs + `runner-image-compat` green; headless authoring verified or its limitation documented; `:latest` images rebuilt post-merge (`[[runner-image-stale-causes-exit-20]]`).

---

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| **Headless authoring may be unreliable** (biggest unknown) | `openspec validate` + best-effort assembly + off-by-default; prompt iteration; first pilot may be rough. |
| **`openspec init` non-interactivity** | Spike up front; fallback = pre-baked folder skeleton. |
| **Offline conformance build breaks** (mock openspec lacks init/validate) | Grow `mock-openspec.sh` with `init`/`validate` stubs, or IT asserts assembly only (`[[runner-tool-self-test-needs-offline-mock]]`). |
| **Stale hand-built `:latest`** | Rebuild post-merge (`[[runner-image-stale-causes-exit-20]]`). |
| **PR diff noise** (spec + code together) | Accepted — it is the goal (durable in-repo specs). |
