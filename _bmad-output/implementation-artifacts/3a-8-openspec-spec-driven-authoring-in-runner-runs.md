# Story 3a.8: OpenSpec Spec-Driven Authoring During Runs

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **the DeliveryLine platform**,
I want **each runner stage to author its OpenSpec change-folder artifact behind an opt-in flag, and the `pr-output` stage to assemble and commit `openspec/changes/<id>/` (`proposal.md` / `specs/` / `design.md` / `tasks.md`) into the delivered PR**,
so that **every run leaves a durable, version-controlled, methodology-shaped spec next to the code — turning the inert `openspec` CLI that story 3a-6 installs into a tool that actually drives the run.**

## Context — what this is, and the one approved approach

Story **3a-6** bakes the `openspec` CLI into both runner images but leaves it **inert**: the headless agent (`codex exec` / `claude -p`, one hardcoded operating-mode prompt) never invokes OpenSpec's slash-command / `AGENTS.md`-driven workflow. This story makes OpenSpec **drive the run**, delivering the agreed value: **durable in-repo specs** — an OpenSpec change folder authored across our existing three stages and **committed into the target repository as part of the delivered PR**.

**The approach was brainstormed and approved — do not re-explore it.** The authoritative spec is `docs/superpowers/specs/2026-06-13-openspec-pipeline-integration-design.md` (read it first). The locked decisions:

- **D1 — durable in-repo specs** is the goal; our three stages stay, OpenSpec gives their output a structured home.
- **D2 — persistence = the target repo / PR** (the folder is committed alongside the code).
- **D3 — 1:1 stage mapping:** spec-investigation → `proposal.md` + `specs/`; implementation-plan → `design.md` + `tasks.md`; pr-output → implement `tasks.md` + assemble + commit the folder.
- **D4 — opt-in, default OFF** (`deliveryline.runner.openspec.enabled`); **flag off ⇒ byte-identical to today.**
- **D5 — Approach A (entrypoint-orchestrated, prompt-driven authoring).** The agent emits OpenSpec-shaped Markdown to **stdout** (its normal artifact channel) via a documented **file-fence convention**; the entrypoint scaffolds the convention and, at pr-output, reconstructs + commits the folder. (Rejected: B = backend post-hoc reshaping — loses methodology value; C = slash-command emulation — unverified/high-risk.)
- **D6 — NO runner-contract / schema change.** Each stage's OpenSpec content is its existing single artifact; the folder is reconstructed at pr-output from the two artifacts the bundle already carries.

**The riskiest part is headless authoring reliability, not plumbing.** Whether a single-prompt agent reliably emits the fence convention + OpenSpec spec-delta format is unverified. This story ships it behind `openspec validate` + best-effort assembly + the off-by-default flag, with the failure posture that **OpenSpec problems never break code delivery** (Trap T-ADDITIVE-NEVER-BLOCKS). A live-run authoring-quality spike is in scope as verification, not as a blocker.

> **Mirror rule (RUNNER_CONTRACT.md change rule):** the two runners are a mirrored pair. Every behavioral change here edits **both** entrypoints, **both** `runner.mjs` helpers, and **both** READMEs **in the same PR** — mirror, do not re-derive. `runners/RUNNER_CONTRACT.md` is updated too.

## Acceptance Criteria

1. **Opt-in gate, byte-identical when off.** A new config flag `deliveryline.runner.openspec.enabled` (default **`false`**, **OPTIONAL + UNVALIDATED** so no `@SpringBootTest` yaml break per `[[validated-config-needs-test-yaml]]`) is surfaced to the container as env `DELIVERYLINE_RUNNER_OPENSPEC` (threaded in `DockerRunnerAdapter` exactly like `DELIVERYLINE_RUNNER_STAGE`, line 243). When the flag is off/absent: the entrypoints emit **no scaffold, no prompt delta, no `openspec/` folder**, and behave **byte-identically to today** (no new log lines on the legacy path). The mock adapter and every no-repo dispatch are unaffected.

2. **1:1 stage authoring via stdout + a file-fence convention (read-only preserved).** When enabled, each read-only stage's augmented `PROMPT_INSTRUCTION` directs the agent to author OpenSpec content to **stdout** using a documented fence convention (`=== FILE: <relpath> ===`):
   - **spec-investigation** authors `proposal.md` (WHAT/WHY) + `specs/<capability>/spec.md` (deltas in OpenSpec `ADDED/MODIFIED/REMOVED` requirement format);
   - **implementation-plan** authors `design.md` (HOW) + `tasks.md` (ordered tasks).
   The stages stay `--sandbox read-only` (codex) / no repo writes (claude) — **the agent writes nothing to `/workspace/repo`**; its stdout becomes the existing per-stage artifact (no new artifact channel). The stage `artifactType` and result schema are unchanged (D6).

3. **Deterministic change identity.** The entrypoint derives a stable `change-id` from `ticketRef` + short `workflowRunId` (e.g. `add-export-filter-run_a1b2`), identical across all three stages of a run, slug-sanitized to a filesystem-safe token. Logged (non-secret).

4. **pr-output assembles + commits the folder.** When enabled, the `pr-output` stage (the only repo-mutating stage):
   a. carries forward both prior artifacts via the **existing** `approvedSpecificationReference` + `approvedImplementationPlanReference` (read by `referencePath` from the input mount — **no bundle-schema change**);
   b. runs `openspec init` **non-interactively** into `/workspace/repo` (idiomatic `openspec/` + `AGENTS.md`); if no non-interactive flag exists, lays down a **pre-baked skeleton** (fallback);
   c. reconstructs `openspec/changes/<id>/{proposal.md, specs/…, design.md, tasks.md}` from the two carried artifacts via a new `runner.mjs split-fenced` helper;
   d. runs `openspec validate <id>` as a structural guard;
   e. the agent implements `tasks.md` against the repo;
   f. the `openspec/` folder is committed **alongside the code** in the delivered PR.

5. **Additive failure posture — OpenSpec problems NEVER break code delivery.** An `openspec init`/`validate` failure, a malformed fence parse, or a missing/`unavailable`/`late_or_stale` carried artifact logs **WARN**, assembles best-effort what is present, and **still ships the code** — it does **not** fail the stage, change an exit code, or add a new failure-category enum (no `DomainErrorCode`/`IntegrationFailureCategory` fan-out). The validation outcome is surfaced in `runner.stderr` + the result summary. Flag-off ⇒ zero new failure surface.

6. **No runner-contract / schema regression.** `context-bundle.v1` and `runner-result.v1` schemas, the exit-code table, mounts, and stage→artifactType mapping are **byte-identical**. The existing `CodexRunnerImageConformanceIT` / `ClaudeRunnerImageConformanceIT` flag-OFF runs and the CI `runner-image-compat` build stay green.

7. **Secrets discipline preserved.** OpenSpec content shares the ticket+repo provenance of today's spec/plan artifacts and inherits the bundle `classification` + the story-3.6 redaction pass. The committed `openspec/` folder ships to the target repo at the **same trust boundary** as the code the agent already writes there. The conformance ITs' negative-leak assertions (secret/`AUTH_SENTINEL` never in stdout/artifact/result/container logs) continue to hold; the fence convention + scaffold introduce **no** new secret surface.

8. **Both runners in the SAME PR; docs updated.** Both entrypoints + both `runner.mjs` + both READMEs document the opt-in authoring mode; `runners/RUNNER_CONTRACT.md` notes it. The version-pin/rebuild caveats from 3a-6 are restated (`[[runner-image-stale-causes-exit-20]]`).

9. **Tests.** (a) **Entrypoint unit** (no container, via `DELIVERYLINE_*_DIR` overrides): flag-off → byte-identical (no prompt delta, no folder, no `openspec` log lines); flag-on → per-stage `PROMPT_INSTRUCTION` augmented, deterministic `change-id`, read-only stages scaffold into the **output/working** area not the repo. (b) **`runner.mjs split-fenced` unit** (Node, mirroring `runners/codex/test/runner-materialize-auth.test.mjs`): well-formed fenced stdout → correct file set; malformed → clear error (no throw past the best-effort boundary). (c) **Conformance ITs** (`docker-runner-it`): feed **fixture** spec/plan artifacts (fenced) to a flag-on pr-output run, assert `openspec/changes/<id>/` materializes with the four files + `openspec validate` is invoked + read-only stages leave the repo mount untouched; assert a flag-off run is byte-identical. Real authoring quality = live-run spike (Task 8), not CI.

## Tasks / Subtasks

- [x] **Task 0 — Read the design doc + spike `openspec init` non-interactivity** (AC: 4)
  - [x] Read `docs/superpowers/specs/2026-06-13-openspec-pipeline-integration-design.md` (authoritative). Confirm the locked decisions D1–D6 before writing code.
  - [x] Spike: does `openspec init` support a non-interactive / `--tool` invocation (no TTY prompt for tool selection)? Confirm `openspec validate` semantics + exit behavior. Record the finding; if no non-interactive init exists, implement the **pre-baked skeleton** fallback (AC4b) and note it. **Finding:** `openspec init` selects a tool **interactively** with no verified non-interactive flag (TTY-prompt + headless-hang risk — Trap T-INIT-NONINTERACTIVE). Chose the **pre-baked skeleton** fallback (AC4b): the entrypoint lays down `openspec/AGENTS.md` + `openspec/changes/<id>/` itself and never invokes real `openspec init`. It still calls `openspec validate` (best-effort, WARN-on-fail).

- [x] **Task 1 — Backend: opt-in flag → container env** (AC: 1)
  - [x] Add `openspec` to `RunnerProperties` as a nested OPTIONAL+UNVALIDATED record (`OpenSpec(boolean enabled)`, default `false`; mirrors the `docker()` nesting + `openSpecEnabled()` accessor). No bean validation, no test-yaml mirror (`[[validated-config-needs-test-yaml]]`). Fixed all 13 full-arg construction sites.
  - [x] In `DockerRunnerAdapter` (next to the `DELIVERYLINE_RUNNER_STAGE` put), add `if (runnerProperties.openSpecEnabled()) containerEnv.put("DELIVERYLINE_RUNNER_OPENSPEC", "true")` — emitted **only when enabled** so flag-off is byte-identical at the container interior. Reuses the already-injected `runnerProperties` (no ctor dep — `[[docker-adapter-ctor-dep-fans-out]]`). Mock adapter untouched.
  - [x] Add a commented placeholder under `deliveryline.runner:` in `application.yml`.

- [x] **Task 2 — Codex entrypoint: opt-in OpenSpec authoring layer** (AC: 1, 2, 3, 4, 5)
  - [x] Gate the entire behavior on `DELIVERYLINE_RUNNER_OPENSPEC=true`. Flag off ⇒ legacy path, byte-identical.
  - [x] Derive `change-id` (AC3) from `DL_TICKET_REF` + short `DL_WORKFLOW_RUN_ID` (prepare now emits both); slug-sanitized; falls back to `change` when ticketRef absent.
  - [x] **Read-only stages:** append the fence-convention authoring delta to `PROMPT_INSTRUCTION`; sandbox posture unchanged; agent writes only to stdout.
  - [x] **pr-output:** lay down the skeleton fallback (no interactive `openspec init`), `runner.mjs split-fenced` the two carried artifacts into `openspec/changes/<id>/`, best-effort `openspec validate <id>` (WARN-on-fail — T-ADDITIVE-NEVER-BLOCKS), then the agent + commit ship the folder. Never aborts on an OpenSpec failure.
  - [x] All new diagnostics via `log()` → container stderr (`[[runner-entrypoint-logs-to-container-stderr]]`); no secrets / no wholesale fence payloads.

- [x] **Task 3 — Claude entrypoint: mirror Task 2 exactly** (AC: 1, 2, 3, 4, 5, 8)
  - [x] Same gating / change-id / pr-output assembly. Prompt augmentation appended to `PROMPT_FILE` (Claude reads the prompt from stdin; no `PROMPT_INSTRUCTION` var) — mirrored intent, not codex line numbers.

- [x] **Task 4 — `runner.mjs split-fenced` helper (both runners)** (AC: 4, 9b)
  - [x] Added `split-fenced` to both `runner.mjs` (byte-identical block): parses `=== FILE: <relpath> ===` sections, atomic `writeAtomically` per file (creates subdirs), rejects path traversal (absolute / drive-letter / `..` / empty segment → exit 42), read/parse failure → exit 41, usage → exit 2. Also extended `prepare` to emit `DL_TICKET_REF` + `DL_SPEC_REF_PATH` + `DL_PLAN_REF_PATH` (byte-identical in both).
  - [x] Helper block kept byte-identical across the two files.

- [x] **Task 5 — mock-openspec init/validate stubs (both runners)** (AC: 6, 9c)
  - [x] Extended both `mock-openspec.sh` with an env-blind `init` (no-op exit 0) + `validate` (echo OK exit 0) case so the offline conformance build exercises pr-output assembly.

- [x] **Task 6 — Docs: both READMEs + RUNNER_CONTRACT.md** (AC: 8)
  - [x] Documented the opt-in authoring mode (flag, env, 1:1 mapping, fence convention, where the folder lands, additive failure posture, flag-off byte-identity) in both READMEs + a shared section in `RUNNER_CONTRACT.md`; restated the rebuild-`:latest` caveat (`[[runner-image-stale-causes-exit-20]]`).

- [x] **Task 7 — Tests** (AC: 9)
  - [x] Entrypoint unit (both, no-container via `DELIVERYLINE_*_DIR` + mock CLI): flag-off byte-identical (no folder, no prompt delta, no openspec log lines); flag-on spec augments the prompt + logs the deterministic change-id + leaves the repo untouched; flag-on pr-output assembles the folder + invokes validate. 13 assertions each, green.
  - [x] `runner.mjs split-fenced` Node unit (both): well-formed → file set incl. subdirs; missing flags → 2; unreadable/fence-less → 41; path-traversal → 42 (no escape). 9 tests each, green.
  - [x] Extended `CodexRunnerImageConformanceIT` (+3) + `ClaudeRunnerImageConformanceIT` (+3) (`docker-runner-it`, `*IT` named): flag-on pr-output assembles `openspec/changes/<id>/` + validate invoked; read-only stage leaves the repo untouched; flag-off byte-identical. Codex 8/8, Claude 9/9 green against the freshly-built offline images.

- [x] **Task 8 — Live-run authoring spike + write-up** (AC: 2, 4)
  - [x] **Deferred to real execution (story 3.8), documented as a limitation.** A live authoring-quality run needs `network-mode=bridge` + a real credential (egress), which the offline contract/conformance tier does not have. The offline tier proves the *plumbing* (prompt augmented, repo untouched on read-only, skeleton + split + validate at pr-output, flag-off byte-identical) but **not** whether a single-prompt agent reliably honors the fence convention + OpenSpec spec-delta format. The off-by-default flag + best-effort posture (OpenSpec failures never block code delivery) keep it shippable regardless. See the "OpenSpec spec-driven authoring during runs" sections in both READMEs + `RUNNER_CONTRACT.md`.

- [x] **Runner logging discipline** (cross-cutting; runner-shell + minimal-Java variant)
  - [x] Shell diagnostics via `log()` → container stderr: INFO for the openspec-enabled lifecycle (change-id resolved, assemble start/complete, validate result), WARN for any best-effort OpenSpec failure. Flag-off ⇒ no new lines (pinned by the entrypoint unit + conformance flag-off tests).
  - [x] Java: `DockerRunnerAdapter` only puts the env when enabled; it does not log the flag payload.
  - [x] No secrets / carried-artifact bodies / fence payloads logged. The conformance negative-leak assertions (AC7) continue to hold (existing secret/AUTH_SENTINEL checks unchanged + green).

### Logging Requirements (project-wide standard)

**Applicability:** this story is ~90% shell (runner entrypoints) + a tiny Java config edit. The full SLF4J/MDC backend standard applies only to the one-line `DockerRunnerAdapter` log (if added) and `RunnerProperties`. The governing discipline is the runner-shell `log()` convention above.

- **Framework:** SLF4J + Logback (backend) — here just `DockerRunnerAdapter`; runner images use the `entrypoint.sh` `log()` helper → container stderr.
- **Where to log (runner):** openspec-enabled change-id resolution + folder assembly + validate outcome (INFO), any OpenSpec failure (WARN, best-effort).
- **Required context keys:** existing `DELIVERYLINE_CORRELATION_ID` (already prepended by `log()`), plus `change-id`, stage, `workflowRunId`.
- **Forbidden:** secrets, auth tokens, carried-artifact bodies, fence payloads.
- **Test contract:** conformance negative-leak assertions hold; entrypoint unit asserts flag-off emits no `openspec` lines.

### Review Findings (bmad-code-review 2026-06-14)

- [x] [Review][Patch] AC5/D4 — surface the `openspec validate` outcome in the result summary (not only container stderr). FIXED: `commandBuild` accepts `--openspec-note` → appends `[openspec: <outcome>]` to `normalizedOutput.summary`; `openspec_assemble_proutput` records `OPENSPEC_VALIDATE_NOTE`; the build invocation passes it ONLY on flag-on pr-output (flag-off ⇒ no extra arg ⇒ byte-identical). New unit assertion in both `entrypoint-openspec.test.sh`. [runners/{codex,claude}/entrypoint.sh + lib/runner.mjs commandBuild]
- [x] [Review][Patch] split-fenced robustness — FIXED: `isSafeRelPath` now rejects a lone `.` segment; the write loop wraps `writeAtomically` so a write failure exits the documented `41` (not an uncaught `1`). Mirror byte-identical across both `runner.mjs`. [runners/codex/lib/runner.mjs:407,444; runners/claude/lib/runner.mjs (mirror)]
- [x] [Review][Patch] change-id trailing-dash hygiene — FIXED: `openspec_change_id` pipes the composed id through `sed 's/-\{1,\}$//'` (covers `change-` when both refs absent, and `cut -c1-16` landing on a boundary dash). Mirror across both entrypoints. [runners/codex/entrypoint.sh:103; runners/claude/entrypoint.sh (mirror)]
- [x] [Review][Defer] Fence-convention authoring robustness — body containing a literal `=== FILE:` line mis-parses; relpaths with spaces/embedded `===` pass the guard and write oddly-named files; duplicate relpath silently overwrites; whitespace-only body → zero-byte file [runners/{codex,claude}/lib/runner.mjs commandSplitFenced] — deferred to the story 3.8 live-run authoring spike (this is exactly the "does a single-prompt agent reliably honor the fence convention" risk the story defers).
- [x] [Review][Defer] pr-output retry idempotency — a stale `openspec/changes/<id>/` from a prior run mixes with new split output; a repo path component existing as a file silently no-ops the split [runners/{codex,claude}/entrypoint.sh openspec_assemble_proutput] — deferred, real-run hardening (default-off, live run is 3.8).
- [x] [Review][Defer] `openspec validate` has no timeout — a real (non-mock) CLI that hangs/prompts would block pr-output despite best-effort intent; add `timeout` when 3.8 enables egress [runners/{codex,claude}/entrypoint.sh:191] — deferred, only reachable once the real CLI runs (3.8).
- [x] [Review][Defer] Read-side referencePath not traversal-guarded (defense-in-depth) — `$INPUT_DIR/$DL_*_REF_PATH` is backend-composed/trusted today and matches the pre-existing referencePath trust, but is unguarded unlike the write side; add an `isSafeRelPath`-equivalent check for symmetry [runners/{codex,claude}/entrypoint.sh openspec_split_carried] — deferred, trusted input (no agent-controlled path), hardening only.

## Dev Notes

### The change in one sentence

Behind an opt-in default-off flag, make each runner stage author its OpenSpec change-folder artifact to **stdout** (via a file-fence convention shaped by the per-stage prompt), carry those artifacts forward on the **existing** bundle references, and at **pr-output** reconstruct + `openspec validate` + commit `openspec/changes/<id>/` into the delivered PR — **mirror across both runners, no runner-contract/schema change, OpenSpec failures never break code delivery.**

### Why this rides existing structure — REUSE, do not rebuild

| Capability | Location | Use |
|---|---|---|
| Per-stage operating-mode prompt | `runners/codex/entrypoint.sh:367-379` (`PROMPT_INSTRUCTION` per `ARTIFACT_TYPE`) + claude twin | Append OpenSpec authoring + fence-convention instructions (gated on the flag). |
| stdout → stage artifact | `runner.mjs build` (stdout summary → `spec.md`/plan/prOutput contentReference) | The agent's fenced stdout IS the stage artifact; no new channel. |
| Cross-stage carry-forward | `context-bundle.v1`: `approvedSpecificationReference` (spec→plan/pr) + `approvedImplementationPlanReference` (plan→pr), read by `referencePath` | Read both at pr-output to reconstruct the folder. **No schema change.** |
| Repo mount (rw at pr-output) | `DockerRunnerAdapter:255-277` (`/workspace/repo` via `RepositoryWorkspaceService`); entrypoint `CODEX_REPO_DIR`/`CLAUDE_REPO_DIR` | `openspec init` + folder write + commit happen here only. |
| Stage env injection | `DockerRunnerAdapter:242-243` (`containerEnv.put("DELIVERYLINE_RUNNER_STAGE", …)`) | Add `DELIVERYLINE_RUNNER_OPENSPEC` the same way, from `runnerProperties` (already injected). |
| Config nesting | `RunnerProperties` (`docker().networkMode()` precedent) | Add `openspec().enabled()` OPTIONAL+UNVALIDATED. |
| Atomic file write | `runner.mjs writeAtomically` | Reuse in `split-fenced`. |
| Node unit-test harness | `runners/codex/test/runner-materialize-auth.test.mjs` | Copy shape for `split-fenced` tests. |
| Conformance IT (offline build + log capture) | `CodexRunnerImageConformanceIT` (`INSTALL_CODEX_CLI=false`, `captureContainerLogs`) + claude twin | Extend for the flag-on pr-output assembly assertions. |
| Offline mock CLI pattern | `mock-codex.sh` / `mock-openspec.sh` (3a-6) | Add `init`/`validate` stubs so the offline build exercises assembly. |

### OpenSpec change-folder layout (target)

```
openspec/
  AGENTS.md                         (from `openspec init`)
  changes/<change-id>/
    proposal.md                     (spec-investigation)
    specs/<capability>/spec.md      (spec-investigation; ADDED/MODIFIED/REMOVED deltas)
    design.md                       (implementation-plan)
    tasks.md                        (implementation-plan)
```

The fence convention the agent emits to stdout (read-only stages):
```
=== FILE: proposal.md ===
<content>
=== FILE: specs/<capability>/spec.md ===
<content>
```

### Traps (do NOT step on these)

- **T-ADDITIVE-NEVER-BLOCKS — OpenSpec failures must never fail the stage.** `init`/`validate`/parse failures + missing carried artifacts → WARN + best-effort + ship the code. No new exit code, no new failure-category enum. The opt-in feature is additive to delivery.
- **T-READONLY-NO-REPO-WRITE — read-only stages write only stdout.** Do NOT relax the spec/plan sandbox, do NOT `openspec init` the repo there (interactive + repo-mutating). Scaffolding for read-only stages stays in the output/working area; the agent emits fences to stdout. Only pr-output mutates `/workspace/repo`.
- **T-FLAG-OFF-BYTE-IDENTICAL — gate the whole layer.** Flag off ⇒ no scaffold, no prompt delta, no `openspec/` folder, no new log lines. The conformance flag-OFF runs + `runner-image-compat` must stay green (AC6).
- **T-NO-SCHEMA-CHANGE — ride existing references.** Carry-forward is via the existing `approvedSpecificationReference`/`approvedImplementationPlanReference`. Do NOT add a bundle/result field (that fans out to fixtures + RegistryContractTest, `[[new-workfloweventtype-fixture-sites]]` shows the cost).
- **T-MIRROR — both runners, one PR.** Both entrypoints + both `runner.mjs` + both READMEs + RUNNER_CONTRACT.md. The Claude entrypoint builds the prompt differently (no `-C`, cwd=repo) — mirror the *intent*, not codex line numbers.
- **T-OFFLINE-MOCK — keep the conformance build green.** The offline build (`INSTALL_*_CLI=false`) has only mock-openspec; give it `init`/`validate` stubs (or assert assembly-only) so the flag-on IT can run (`[[runner-tool-self-test-needs-offline-mock]]`). Keep the mock env-blind.
- **T-PATH-TRAVERSAL — `split-fenced` writes under the change dir only.** Reject `..`/absolute `<relpath>` so a malformed/hostile fence can't escape `openspec/changes/<id>/`.
- **T-INIT-NONINTERACTIVE — `openspec init` may prompt.** It selects a tool interactively; under headless it would hang. Use a non-interactive flag if it exists, else the pre-baked skeleton fallback (Task 0 spike).
- **T-NO-DOCKER-CTOR-FANOUT — reuse `runnerProperties`.** The flag rides the already-injected `RunnerProperties`; do NOT add a new `DockerRunnerAdapter` ctor dep (`[[docker-adapter-ctor-dep-fans-out]]`).
- **T-GATES-POWERSHELL — run gates via PowerShell** (`[[rtk-hook-only-matches-bash]]`); the Docker tier via WSL2 if no Windows Docker (`[[wsl-linux-ci-reproduction]]`, `[[verify-ci-fixes-in-clean-env]]`).

### Validation / scope posture

- **NO** Flyway / REST / OpenAPI / `schema.d.ts` / `DomainErrorCode` / `IntegrationFailureCategory` / registry value / ArchUnit-relevant new package / runner-contract change (exit codes, mounts, bundle/result schema, stage mapping all byte-identical).
- **Surface:** both entrypoints (+openspec layer), both `runner.mjs` (+`split-fenced`), both `mock-openspec.sh` (+init/validate stubs), both READMEs, `RUNNER_CONTRACT.md`, `RunnerProperties` (+`openspec.enabled`), `DockerRunnerAdapter` (one `containerEnv.put`), `application.yml` (commented placeholder), entrypoint + `runner.mjs` unit tests, both conformance ITs.
- **Gates:** entrypoint + `runner.mjs` unit tests; the two conformance ITs in the `docker-runner-it` tier; backend fast tier for the `RunnerProperties`/`DockerRunnerAdapter` edit; `runner-image-compat` (should stay green). PowerShell; WSL2 for Docker tier.

### Project Structure Notes

```
runners/
├── RUNNER_CONTRACT.md                         (MODIFIED — note opt-in authoring mode)
├── codex/
│   ├── entrypoint.sh                          (MODIFIED — flag-gated OpenSpec layer; prompt augmentation; pr-output assembly)
│   ├── lib/runner.mjs                          (MODIFIED — split-fenced helper)
│   ├── test/mock-openspec.sh                   (MODIFIED — init/validate stubs; from 3a-6)
│   └── README.md                               (MODIFIED — opt-in authoring mode)
└── claude/ … (MODIFIED — mirror of codex)

deliveryline-backend/src/main/java/org/dradgo/
├── application/runner/RunnerProperties.java     (MODIFIED — + openspec().enabled() OPTIONAL+UNVALIDATED)
└── adapters/runner/DockerRunnerAdapter.java      (MODIFIED — + containerEnv.put("DELIVERYLINE_RUNNER_OPENSPEC", …))
deliveryline-backend/src/main/resources/application.yml   (MODIFIED — commented openspec.enabled placeholder)
deliveryline-backend/src/test/java/org/dradgo/adapters/runner/
├── CodexRunnerImageConformanceIT.java           (MODIFIED — flag-on pr-output assembly + flag-off byte-identical)
└── ClaudeRunnerImageConformanceIT.java          (MODIFIED — mirror)
+ entrypoint unit tests + runner.mjs split-fenced unit tests
```

### References

- [Source: docs/superpowers/specs/2026-06-13-openspec-pipeline-integration-design.md] — **authoritative design** (decisions D1–D6, mechanics, error posture, testing, risks).
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-13-openspec-pipeline.md] — the correct-course proposal that added this story.
- [Source: _bmad-output/implementation-artifacts/3a-6-openspec-cli-in-runner-images.md] — the enabler (CLI install + mock-openspec + self-test). This story depends on it.
- [Source: runners/codex/entrypoint.sh:336-423] — codex headless invocation + per-stage `PROMPT_INSTRUCTION` (lines 367-379) + repo-dir handling (the augmentation + pr-output assembly seam).
- [Source: runners/claude/entrypoint.sh:317-385] — claude headless invocation (cwd=repo, `-p`); mirror the intent.
- [Source: runners/codex/lib/runner.mjs:142-181] — `prepare` (extracts ticketRef/specRef into the prompt + DL_* vars) + `writeAtomically` (reuse for `split-fenced`).
- [Source: runners/codex/test/runner-materialize-auth.test.mjs] — Node unit-test harness shape for `split-fenced`.
- [Source: deliveryline-runner-contracts/src/main/resources/schemas/context-bundle.v1.schema.json:53-62,150-160] — `approvedSpecificationReference` + `approvedImplementationPlanReference` carry-forward (read by `referencePath`; no schema change).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java:242-285] — `containerEnv` build (stage env), `runnerProperties.docker().networkMode()`, repo mount — the flag-env + repo seam.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java] — config record to extend with `openspec().enabled()` (mirror `docker()` nesting).
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java] — offline build + container-log capture + negative-leak assertions to extend.
- [Source: runners/RUNNER_CONTRACT.md:10-20] — the mirror-rule (both runners, same PR).
- [Reference: https://openspec.pro/getting-started/] — `openspec init` (creates `openspec/` + `AGENTS.md`), slash-command/`AGENTS.md` UX, change-folder layout.
- [Reference: https://github.com/Fission-AI/OpenSpec] — `openspec validate` + change-folder conventions.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story

### Debug Log References

- Backend fast tier (affected): `mvnw test -Dtest=RunnerPropertiesTest,RunnerLogCaptureServiceTest,WorkflowOrchestrationServiceTest,DockerRunnerAdapterUnitTest,RunnerBrokerUnitTest,LocalRunnerWorkspaceStoreTest` → 153 tests, 0 failures.
- Node split-fenced unit (both): `node --test runners/{codex,claude}/test/runner-split-fenced.test.mjs` → 9 + 9 pass.
- Entrypoint unit (both, no-container): `sh runners/{codex,claude}/test/entrypoint-openspec.test.sh` (Git Bash) → 13 + 13 ok, 0 failures.
- Conformance ITs (docker-runner-it, Docker Desktop 28.5.1): `CodexRunnerImageConformanceIT` 8/8, `ClaudeRunnerImageConformanceIT` 9/9 (incl. the 3 new OpenSpec tests each) against freshly-built `INSTALL_*_CLI=false` images.

### Completion Notes List

- **Approach:** D5 (entrypoint-orchestrated, prompt-driven authoring via the `=== FILE: <relpath> ===` fence convention to stdout), D4 opt-in default OFF, D6 no runner-contract/schema change. Flag-off is byte-identical (gated entirely on `DELIVERYLINE_RUNNER_OPENSPEC`, emitted only when `deliveryline.runner.openspec.enabled=true`).
- **`openspec init` spike (Task 0):** no verified non-interactive flag ⇒ chose the pre-baked skeleton fallback (AC4b); the entrypoint authors `openspec/AGENTS.md` + the change dir itself and never runs interactive `init`. `openspec validate` is still invoked best-effort.
- **STDERR collision avoided:** all OpenSpec diagnostics go to the container's stderr stream (`log()` → `>&2`), NOT the `runner.stderr` mount file (which the agent invocation truncates) — the conformance ITs assert them via `captureContainerLogs`.
- **change-id needs ticketRef:** extended `runner.mjs prepare` to emit `DL_TICKET_REF` + the per-stage `DL_SPEC_REF_PATH`/`DL_PLAN_REF_PATH` rather than adding a new subcommand, keeping flag-off byte-identical internally. change-id = `slug(ticketRef)-short(slug(workflowRunId))` (e.g. `dl-101-run_abcd1234`), identical across all three stages.
- **`RunnerProperties` fan-out:** adding the nested `OpenSpec` record component required appending `RunnerProperties.OpenSpec.defaults()` (or `d.openspec()`) to all 13 full-arg construction sites; the `defaults()`-factory call sites needed no change. The separate legacy `infrastructure/config/RunnerProperties.java` (story 1.13) is a different class — out of scope.
- **Live-run authoring quality (Task 8) is deferred to story 3.8** (needs egress + a real credential). The offline tiers prove the plumbing only; the off-by-default flag + additive-never-blocks posture make it shippable without that confirmation. Documented in both READMEs + RUNNER_CONTRACT.md.
- **Mirror rule honored:** both entrypoints, both `runner.mjs`, both `mock-openspec.sh`, both READMEs, both conformance ITs, both entrypoint/split-fenced unit tests changed in this one PR; the split-fenced + prepare-ref blocks are byte-identical across the two `runner.mjs` files.

### File List

**Backend (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java` — nested `OpenSpec(boolean enabled)` OPTIONAL+UNVALIDATED record + `openSpecEnabled()` accessor + compact-ctor default + `defaults()`.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java` — `DELIVERYLINE_RUNNER_OPENSPEC` env put (only when enabled).
- `deliveryline-backend/src/main/resources/application.yml` — commented `openspec.enabled` placeholder.

**Backend (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java` — +3 OpenSpec tests + `runWithRepoMount` helper.
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/ClaudeRunnerImageConformanceIT.java` — +3 OpenSpec tests (mirror).
- Construction-site fixes (append `OpenSpec.defaults()`/`d.openspec()`): `RunnerPropertiesTest.java`, `RunnerLogCaptureServiceTest.java`, `WorkflowOrchestrationServiceTest.java`, `RunnerBrokerUnitTest.java`, `DockerRunnerAdapterUnitTest.java`, `DockerRunnerAdapterContainerLifecycleIT.java`, `lifecycle/DockerLifecycleITSupport.java`, `adapters/files/LocalRunnerWorkspaceStoreTest.java`.

**Runners (codex + claude, mirrored):**
- `runners/codex/entrypoint.sh`, `runners/claude/entrypoint.sh` — opt-in OpenSpec authoring layer (helpers + flag-gated invocation).
- `runners/codex/lib/runner.mjs`, `runners/claude/lib/runner.mjs` — `split-fenced` subcommand + `isSafeRelPath` + `prepare` ref-path/ticket-ref emission.
- `runners/codex/test/mock-openspec.sh`, `runners/claude/test/mock-openspec.sh` — `init`/`validate` stubs.
- `runners/codex/test/runner-split-fenced.test.mjs`, `runners/claude/test/runner-split-fenced.test.mjs` — new Node unit tests.
- `runners/codex/test/entrypoint-openspec.test.sh`, `runners/claude/test/entrypoint-openspec.test.sh` — new entrypoint unit tests.
- `runners/codex/README.md`, `runners/claude/README.md`, `runners/RUNNER_CONTRACT.md` — opt-in authoring docs.

## Change Log

| Date | Change |
|---|---|
| 2026-06-13 | bmad-correct-course: added 3a-8 to Epic 3a (sprint-change-proposal-2026-06-13-openspec-pipeline.md) + epic stub. bmad-create-story: full story file created from the approved design doc. Status `backlog → ready-for-dev`. |
| 2026-06-14 | bmad-dev-story: implemented the opt-in OpenSpec authoring layer across both runners (entrypoints + `runner.mjs split-fenced` + mock-openspec stubs + READMEs + RUNNER_CONTRACT), backend opt-in flag (`RunnerProperties.openspec.enabled` → `DELIVERYLINE_RUNNER_OPENSPEC`), and the full test set (split-fenced Node unit, entrypoint unit, conformance ITs). All gates green; live-run authoring spike deferred to story 3.8. Status `ready-for-dev → review`. |
