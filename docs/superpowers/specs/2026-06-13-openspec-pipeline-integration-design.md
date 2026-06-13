# OpenSpec Pipeline Integration — Design

- **Date:** 2026-06-13
- **Author:** Alex (via brainstorming)
- **Status:** Design approved — pending spec review → implementation plan
- **Proposed story:** **3a-8** (new), layered on **3a-6** (OpenSpec CLI baked into both runner images)
- **Depends on:** 3a-6 (CLI present in both images); rides seams from 3.10 (`approvedImplementationPlanReference` carry-forward) and 3a-2 (repo context bundle, `/workspace/repo` mount)

---

## 1. Goal & decisions

**Goal:** make autonomous agent runs author durable, methodology-shaped OpenSpec change folders that are **committed into the target repository as part of the delivered PR** — so each run leaves a structured, version-controlled spec (`proposal.md` / `specs/` / `design.md` / `tasks.md`) next to the code.

**Decisions taken during brainstorming:**

| # | Decision |
|---|----------|
| D1 | **Primary value = durable in-repo specs.** Our existing three stages stay; OpenSpec gives their output a structured, reviewable home. |
| D2 | **Persistence = the target repo / PR.** The `openspec/changes/<id>/` folder is committed into the repository being worked on and ships in the delivered PR. |
| D3 | **1:1 stage mapping.** spec-investigation → `proposal.md` + `specs/`; implementation-plan → `design.md` + `tasks.md`; pr-output → implement `tasks.md` + assemble + commit the folder. |
| D4 | **Opt-in, default OFF.** Gated behind `deliveryline.runner.openspec.enabled` (mirrors auto-ingest/auto-dispatch). Flag off ⇒ byte-identical to today. |
| D5 | **Approach A — entrypoint-orchestrated, prompt-driven authoring.** The agent authors OpenSpec-shaped content to stdout (its normal artifact channel); the entrypoint scaffolds structure and assembles the folder. (Rejected: B = backend post-hoc reshaping, loses methodology value; C = full slash-command emulation, unverified/high-risk.) |
| D6 | **No runner-contract / schema change.** Each stage's OpenSpec content is its existing single artifact; the folder is reconstructed at pr-output from the two artifacts the bundle already carries. `context-bundle.v1` and `runner-result.v1` are untouched. |

---

## 2. Architecture & boundaries

A new opt-in OpenSpec authoring layer added to **both runner entrypoints** (mirror rule). No backend dispatch or bundle-composition change.

| Component | Change |
|-----------|--------|
| `runners/codex/entrypoint.sh` + `runners/claude/entrypoint.sh` | **Only behavioral code.** When the flag is on: derive `change-id`, scaffold the OpenSpec convention into the working area, lay down prior-stage OpenSpec files carried in the bundle, **augment the existing per-stage `PROMPT_INSTRUCTION`**, and at pr-output assemble + write `openspec/changes/<id>/` into `/workspace/repo`. Mirror, do not re-derive. |
| `runners/codex/lib/runner.mjs` + claude twin | New `split-fenced` helper (deterministic split of fenced stdout into the change-folder file set). No schema change. |
| Backend | **Config → env injection only:** new `deliveryline.runner.openspec.*` property surfaced as `DELIVERYLINE_RUNNER_OPENSPEC=true` on the container, threaded exactly like `DELIVERYLINE_RUNNER_STAGE`. No orchestration change. |
| Docs | Both runner READMEs + `runners/RUNNER_CONTRACT.md` document the opt-in authoring mode. |

**Invariants:**
- **Flag off ⇒ byte-identical** — no scaffold, no prompt delta, no `openspec/` folder in the PR. Hard gate.
- **Mirror rule** — both runners change in one PR.
- **Read-only posture preserved** — spec/plan stages stay `--sandbox read-only` and emit OpenSpec content to **stdout** (their normal artifact channel); they never write the repo. Only pr-output (`danger-full-access`) mutates `/workspace/repo`.
- **No repo-mutating `openspec init` during read-only stages** — `init` runs only at pr-output (which mutates the repo anyway).

---

## 3. Mechanics

### 3.1 Change identity
The entrypoint derives a deterministic `change-id` (slug from `ticketRef` + short `workflowRunId`, e.g. `add-export-filter-run_a1b2`) so all three stages address the same OpenSpec change.

### 3.2 Authoring without agent file-writes (read-only stages)
The agent emits OpenSpec-shaped Markdown to **stdout** — exactly as today, where stdout already becomes the stage artifact. Shaping is via the **augmented `PROMPT_INSTRUCTION`** plus a documented **file-fence convention**:

```
=== FILE: proposal.md ===
<proposal content>
=== FILE: specs/<capability>/spec.md ===
<spec deltas in OpenSpec ADDED/MODIFIED/REMOVED requirement format>
```

Nothing writes the repo until pr-output.

### 3.3 Per-stage (1:1)
- **spec-investigation** (read-only): prompt directs *"author an OpenSpec proposal — `proposal.md` (WHAT/WHY) + `specs/<cap>/spec.md` deltas in OpenSpec's ADDED/MODIFIED/REMOVED format"*. Captured as the existing **spec artifact** (its stdout).
- **implementation-plan** (read-only): the bundle carries the spec artifact forward (`approvedSpecificationReference`); the entrypoint feeds its `referencePath` into the prompt; the agent authors `design.md` (HOW) + `tasks.md` (ordered tasks). Captured as the existing **implementationPlan artifact**.
- **pr-output** (mutate): the bundle carries **both** prior artifacts (`approvedSpecificationReference` + `approvedImplementationPlanReference`). The entrypoint:
  1. runs `openspec init` **non-interactively into `/workspace/repo`** (the one place repo mutation is allowed → idiomatic `openspec/` + `AGENTS.md`),
  2. materializes `openspec/changes/<id>/{proposal.md, specs/…, design.md, tasks.md}` from the two carried artifacts (`runner.mjs split-fenced`),
  3. runs **`openspec validate <id>`** as a structural guard,
  4. the agent implements `tasks.md` against the repo,
  5. commits the `openspec/` folder **alongside the code** in the delivered PR.

### 3.4 Carry-forward = existing seams
`approvedSpecificationReference` (spec → plan/pr-output) and `approvedImplementationPlanReference` (plan → pr-output) already flow by `referencePath` from the mounted input dir (shipped in 3.10 / 3a-2). We read them; we do not re-plumb them. **No bundle-schema change.**

### 3.5 Config / flag threading
`deliveryline.runner.openspec.enabled` (boolean, default `false`, OPTIONAL + UNVALIDATED so the test `application.yml` needs no mirror — see `validated-config-needs-test-yaml`) → `RunnerProperties`/dispatch surfaces it → injected as container env `DELIVERYLINE_RUNNER_OPENSPEC=true` (threaded like `DELIVERYLINE_RUNNER_STAGE`). The entrypoint gates the entire behavior on this env; absent/`false` ⇒ legacy path.

---

## 4. Error handling & redaction

**Failure posture (additive feature must never break real delivery):**
- The OpenSpec folder is **additive** to the code PR. If `openspec init`/`validate` fails at pr-output, the entrypoint **logs WARN, writes the folder best-effort, and still commits the code** — it does not fail the stage. The validation outcome is surfaced in `runner.stderr` + the result summary.
- **No new failure-category enum** (avoids the `DomainErrorCode`/`IntegrationFailureCategory` three-site fan-out). Genuine runner crashes keep the existing exit-code table.
- Flag-off ⇒ zero new failure surface.
- **Degraded carry-forward:** if a prior artifact is `unavailable`/`late_or_stale`, pr-output assembles what is present and logs the gap.

**Classification / redaction:** OpenSpec content shares the ticket+repo provenance of today's spec/plan artifacts, so it inherits the bundle's `classification` and the story-3.6 redaction pass — no new secret surface. The committed `openspec/` folder ships to the target repo at the **same trust boundary** as the code the agent already writes there. Existing negative-leak assertions (stdout / artifact / result never contain secrets) continue to cover it.

---

## 5. Testing

- **Entrypoint unit** (no container, via `DELIVERYLINE_*_DIR` overrides): flag-off → byte-identical (no prompt delta, no folder); flag-on → prompt augmented per stage, deterministic `change-id`, read-only stages scaffold into the **output** mount not the repo.
- **`runner.mjs split-fenced` unit** (Node, like `runner-materialize-auth.test.mjs`): fenced stdout → correct file set; malformed → clear error.
- **Conformance ITs** (`docker-runner-it`): feed **fixture** spec/plan artifacts (fenced); assert pr-output materializes `openspec/changes/<id>/` with the four files, invokes `openspec validate`, and that read-only stages leave the repo mount untouched; assert a flag-off run is byte-identical. Real agent authoring quality is a **live-run spike**, not CI (the mock CLI ignores prompts).
- **Gates** run via PowerShell (`rtk-hook-only-matches-bash`); the Docker tier via WSL2 if no Windows Docker (`wsl-linux-ci-reproduction`); conformance tests stay `*IT` + `@Tag("docker-runner-it")` (`springboot-testcontainers-test-must-be-IT`, `docker-it-needs-exact-docker-runner-it-tag`).

---

## 6. Open risks / spikes (carried into the story)

1. **`openspec init` non-interactivity** — needs a `--tool`/non-interactive path; **fallback** = a pre-baked folder skeleton committed by the entrypoint. *(spike)*
2. **Headless authoring reliability (biggest unknown)** — will single-prompt agents (`codex exec` / `claude -p`) reliably emit the fence convention + OpenSpec spec-delta format? Mitigated by `openspec validate` + best-effort assembly + prompt iteration; first pilot may be imperfect.
3. **Mock-openspec gains `init`/`validate` stubs** so the offline conformance build stays green (`runner-tool-self-test-needs-offline-mock`) — or the IT asserts assembly only and treats `validate` as a mock no-op.
4. **PR diff noise** — reviewers now see spec + code together (accepted; it is the goal).

---

## 7. Out of scope

- Real-agent authoring quality benchmarking (live-run spike, separate).
- `openspec archive` on merge/accept (downstream of the →COMPLETED trigger, story 3.20).
- Multi-run human-in-the-loop editing of the change folder between runs (the "iterative" value option we did **not** pick).
- Any change to the runner ↔ backend file contract, exit codes, mounts, or schemas.
- 3a-7 (superpowers) — independent, reuses the 3a-6 "enrich both images" pattern.

---

## 8. File surface (anticipated)

```
runners/codex/entrypoint.sh                 (MODIFIED — openspec authoring layer, flag-gated)
runners/claude/entrypoint.sh                (MODIFIED — mirror)
runners/codex/lib/runner.mjs                (MODIFIED — split-fenced helper)
runners/claude/lib/runner.mjs               (MODIFIED — mirror)
runners/codex/test/mock-openspec.sh         (MODIFIED — init/validate stubs; from 3a-6)
runners/claude/test/mock-openspec.sh        (MODIFIED — mirror)
runners/codex/README.md                     (MODIFIED — opt-in authoring mode)
runners/claude/README.md                    (MODIFIED — mirror)
runners/RUNNER_CONTRACT.md                  (MODIFIED — note the opt-in authoring mode)
deliveryline-backend/.../runner config       (MODIFIED — deliveryline.runner.openspec.enabled → env injection)
deliveryline-backend/src/main/resources/application.yml  (MODIFIED — commented placeholder)
deliveryline-backend/.../CodexRunnerImageConformanceIT.java + Claude twin  (MODIFIED — openspec-enabled stage assertions)
+ entrypoint unit tests, runner.mjs split-fenced unit tests
```
