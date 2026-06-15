# Story 3a.6: OpenSpec CLI in Both Runner Images

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **the DeliveryLine platform**,
I want **the `openspec` CLI (`@fission-ai/openspec`) baked — pinned, offline-buildable — into BOTH runner images (`deliveryline/codex-runner` story 3.3 and `deliveryline/claude-runner` story 3.4) and verifiably present/invokable during a stage run**,
so that **autonomous agent runs can use OpenSpec's spec-driven workflow, established as the repeatable "enrich both runner images with agent-side tooling" pattern that story 3a-7 (superpowers) then follows**.

## Context & what this story is (and is NOT)

The two production runner images wrap an agent CLI (Codex / Claude Code) and **nothing else** — they ship the one CLI, a `runner.mjs` JSON helper, an `entrypoint.sh`, and (in offline/test builds) a deterministic mock of the CLI. This story adds a **second** tool, `openspec`, to both images using the **same mechanism** as the existing CLI (`npm install -g`, pinned, gated for offline determinism), so the agent inside the container can invoke OpenSpec.

**This is a runner-image (Docker + shell + docs) story. There is NO Java production change** — no backend, no `application/`, no `adapters/`, no schema, no Flyway, no REST/OpenAPI, no new `DomainErrorCode`/registry value. The only Java touched is the two conformance ITs (optional assertion extension; see Task 6).

**Decisive tension to resolve up front (read Dev Notes "The offline/mock-build decision" before coding):** AC2 wants `--self-test` to assert `openspec --version` on **both** runners; AC5 requires the existing conformance ITs (`CodexRunnerImageConformanceIT` + Claude twin), which run the image's `--self-test` against the **mock/offline build** (`INSTALL_CODEX_CLI=false` / `INSTALL_CLAUDE_CLI=false`), to **stay green**. The offline build deliberately does **no real `npm install`**. The resolution that mirrors the existing `mock-codex.sh` / `mock-claude.sh` pattern is to **bake a deterministic `mock-openspec.sh`** in the `else` (mock) branch of each Dockerfile, so the self-test's openspec assertion is unconditional and passes in both production (real npm) and offline (mock) builds.

**The riskiest AC is "active during runs" (AC3), not installation.** The entrypoints invoke `codex exec` / `claude -p` with a **hardcoded operating-mode prompt** (no human, no TTY). OpenSpec's primary UX is **slash commands** (`/opsx:new`, `/opsx:apply`, …) plus an `AGENTS.md` instructions file written by `openspec init`. Whether either auto-fires in a single-prompt headless run is **unverified**. AC3 is satisfied by a **bounded verification spike + a documented outcome with a safe fallback to "present + CLI-invokable"** — do **not** block the story on slash-command auto-firing, and do **not** mutate the working repo at runtime (see Trap T-NO-RUNTIME-INIT).

> **Mirror rule (RUNNER_CONTRACT.md change rule, AC10 of 3.3/3.4):** the two runners are a mirrored pair. Every change here edits **both** Dockerfiles, **both** entrypoints, **both** mock CLIs, and **both** READMEs **in the same PR** — mirror, do not re-derive. `runners/RUNNER_CONTRACT.md` is updated too.

## Acceptance Criteria

1. **Pinned, offline-safe install in BOTH Dockerfiles.** Both `runners/codex/Dockerfile` and `runners/claude/Dockerfile` install OpenSpec **pinned** via a new `ARG OPENSPEC_VERSION` (default the current published version — confirm with `npm view @fission-ai/openspec version`; **no** floating `latest`/`main`), in the **same `RUN`/npm-global layer** as the agent CLI. The real `npm install -g "@fission-ai/openspec@${OPENSPEC_VERSION}"` runs **only** in the production branch (the existing `INSTALL_CODEX_CLI=true` / `INSTALL_CLAUDE_CLI=true` gate). The package name + version are confirmed via `npm view` at authoring time and recorded in the README.

2. **Offline/mock build stays buildable AND self-test-assertable.** In the `else` (mock) branch — built by the conformance ITs and the CI `runner-image-compat` line with `INSTALL_*_CLI=false` — a deterministic **`mock-openspec.sh`** is `install`-ed to `/usr/local/bin/openspec` (mirroring how `mock-codex.sh`/`mock-claude.sh` become `/usr/local/bin/codex|claude`). The mock answers `openspec --version` with `$OPENSPEC_VERSION` and **never reads/echoes env** (so an injected secret value can never leak through it — preserves the negative-log assertion). `OPENSPEC_VERSION` is surfaced as an `ENV` so the entrypoint self-test can read the expected pin in **both** build modes.

3. **`--self-test` asserts `openspec` on BOTH runners.** `run_self_test()` in each entrypoint gains an `openspec` check mirroring the existing CLI-version block: fail with a `SELF-TEST FAIL:` message + **exit `1`** if `openspec` is not on PATH, or if `openspec --version` does not contain/equal `$OPENSPEC_VERSION`; on success add a summary line (e.g. `openspec version:  <v> (expected pin: <v>)`). Because mock-openspec (AC2) reports the pin, this assertion is green in the offline build the conformance IT exercises.

4. **Active-during-runs — verified or limitation documented (the spike).** Establish whether OpenSpec is usable by the headless agent and record the finding:
   - **Minimum (required):** `openspec` is on `PATH` and **CLI-invokable** by the agent during a run (i.e. the agent *could* call `openspec ...` as a shell tool). No runtime repo mutation is required for this.
   - **Spike (required, time-boxed):** verify whether OpenSpec **auto-activates** in single-prompt headless mode (`codex exec` / `claude -p`) — e.g. via `AGENTS.md` discovery or a prompt nudge. If it can be confirmed cheaply, wire the **minimal** nudge (see Task 4); if it **cannot** be confirmed in single-prompt headless mode, **document the limitation** in the README ("present + CLI-invokable; slash-command auto-activation requires interactive mode / future wiring") and the Completion Notes, and ship the minimum. **The story is NOT blocked on slash-command auto-firing.**
   - **Hard constraint:** the entrypoint must **NOT run `openspec init` (or any repo-mutating openspec command) at runtime by default** — it is interactive (prompts for tool selection) and would dirty the read-only spec/plan stages and pollute pr-output diffs (Trap T-NO-RUNTIME-INIT).

5. **Existing conformance ITs + CI stay green; no contract regression.** `CodexRunnerImageConformanceIT` and `ClaudeRunnerImageConformanceIT` still pass (self-test exits 0 with openspec reported via mock; all artifact-variant runs still produce schema-valid `runner-result.v1.json`; the negative-leak assertions still hold). The CI `runner-image-compat` line still builds **both** images (`-f runners/<x>/Dockerfile … .`, root context, `INSTALL_*_CLI=false`) and re-validates the v1 fixtures. **No exit-code, mount, bundle-schema, result-schema, or stage-mapping change** — the runner ↔ backend contract is byte-identical.

6. **Image stays within the ~1 GB budget; size/layer table updated.** OpenSpec is a pure-JS npm package (no native binary like Codex's Rust), so the production-image growth is modest (tens of MB). Re-measure and update the **"Image size / layer count"** table in **both** READMEs; if growth is non-trivial, note it. Both images remain well under ~1 GB.

7. **Both runners in the SAME PR; docs + version-pin procedure updated.** Both Dockerfiles, both entrypoints, both mock CLIs, both READMEs updated together. Each README documents OpenSpec's pin (`ARG OPENSPEC_VERSION`) + the `npm view @fission-ai/openspec version` upgrade procedure (mirroring the existing CLI-pin section), the new self-test summary line, the offline-mock note, and the AC4 activation finding. `runners/RUNNER_CONTRACT.md` gains a short note that the images now carry **agent-side tooling** (OpenSpec) beyond the agent CLI. The **rebuild-`:latest`** caveat is restated in each story's done criteria (hand-built `:latest` images are stale until rebuilt — `[[runner-image-stale-causes-exit-20]]`).

## Tasks / Subtasks

- [x] **Task 1 — Codex Dockerfile: pin + install OpenSpec (prod) / mock (offline)** (AC: 1, 2, 6, 7)
  - [x] Add `ARG OPENSPEC_VERSION=<pinned>` (confirm with `npm view @fission-ai/openspec version`; record the value chosen) near `ARG CODEX_CLI_VERSION` in `runners/codex/Dockerfile`.
  - [x] Add `OPENSPEC_VERSION=${OPENSPEC_VERSION}` to the existing `ENV` block (so the entrypoint self-test can read the expected pin in both build modes).
  - [x] `COPY runners/codex/test/mock-openspec.sh /opt/deliveryline/test/mock-openspec.sh` alongside the existing `mock-codex.sh` copy.
  - [x] In the `RUN` block: in the `INSTALL_CODEX_CLI=true` branch, add `npm install -g "@fission-ai/openspec@${OPENSPEC_VERSION}"` immediately after the codex install (BEFORE `npm cache clean --force`). In the `else` branch, add `install -m 0755 /opt/deliveryline/test/mock-openspec.sh /usr/local/bin/openspec` alongside the mock-codex install. Keep everything in the single existing layer (lean-image rationale).
  - [x] Do NOT add a separate `INSTALL_OPENSPEC` arg (see Dev Notes "Why reuse the existing gate"). Node ≥20.19 is required by OpenSpec; `node:22-slim` satisfies it — note in README, no base-image change.

- [x] **Task 2 — Claude Dockerfile: mirror Task 1 exactly** (AC: 1, 2, 6, 7)
  - [x] Same edits in `runners/claude/Dockerfile`: `ARG OPENSPEC_VERSION` + `ENV`, `COPY runners/claude/test/mock-openspec.sh`, real install gated on `INSTALL_CLAUDE_CLI=true` (after the claude-code install, before cache clean), mock install in the `else` branch. **Mirror, do not re-derive** — the only deltas vs the codex Dockerfile are the file paths (`runners/claude/…`) and the surrounding agent-CLI lines.

- [x] **Task 3 — Two `mock-openspec.sh` files (NEW)** (AC: 2)
  - [x] Create `runners/codex/test/mock-openspec.sh` and `runners/claude/test/mock-openspec.sh` (identical content; per-runner copies match the existing `mock-codex.sh`/`mock-claude.sh` convention). Pattern: `set -eu`; if `$1 = --version` echo `${OPENSPEC_VERSION:-0.0.0-mock}` and exit 0; otherwise print one deterministic line and exit 0. **NEVER read or echo any env var other than `OPENSPEC_VERSION`** (no secret-leak surface). Make both executable in git (mode is also re-set by `install -m 0755`).

- [x] **Task 4 — Self-test openspec assertion in BOTH entrypoints + (spike-gated) activation nudge** (AC: 3, 4)
  - [x] In `runners/codex/entrypoint.sh` `run_self_test()`: after the `codex` CLI version block, add an `openspec` block mirroring it: `command -v openspec` → `SELF-TEST FAIL: openspec CLI not found on PATH` + `exit 1`; capture `openspec --version`; compare against `$OPENSPEC_VERSION` (substring match, mirroring the codex version comparison) → `SELF-TEST FAIL: openspec version '…' does not match expected pin …` + `exit 1`. Add an `echo "  openspec version: … (expected pin: …)"` line to the OK summary.
  - [x] Mirror the same block in `runners/claude/entrypoint.sh` `run_self_test()` (note the Claude entrypoint version-compare uses `set -- $version; token=$1` exact-match — keep openspec's compare consistent with each entrypoint's own existing style).
  - [x] **Activation (spike-gated, AC4):** by default do the **minimum** — openspec is on PATH (Tasks 1–3), nothing else. If the spike (Task 7) confirms a cheap headless activation path, wire the **smallest** nudge that does NOT mutate the repo and does NOT change the runner contract — e.g. append a single sentence to the existing `PROMPT_INSTRUCTION` strings noting `openspec` is available as a CLI tool. **Do NOT** run `openspec init`, **do NOT** write `AGENTS.md` at runtime, **do NOT** add new exit codes or env-driven repo writes. If the spike is inconclusive, leave the prompt unchanged and document the limitation.

- [x] **Task 5 — Update both READMEs + RUNNER_CONTRACT.md** (AC: 6, 7)
  - [x] `runners/codex/README.md` and `runners/claude/README.md`: add an **OpenSpec version pinning + upgrade procedure** subsection (mirror the existing CLI-pin section: `npm view @fission-ai/openspec version`, bump `ARG OPENSPEC_VERSION`, `docker compose build`, `--self-test` to confirm); list `openspec` in the tooling/"Base-image choice & layout" area (pure-JS npm-global, Node ≥20.19 satisfied by the base); **re-measure and update the Image size / layer count table**; record the AC4 activation finding (verified vs documented limitation). Note the offline build bakes a mock openspec.
  - [x] `runners/RUNNER_CONTRACT.md`: add a brief note (e.g. under "Least privilege"/a new "Agent-side tooling" line) that both images now also carry the `openspec` CLI (pinned, present in production; mocked in the offline build) — visibility only; it is not a new file/mount/exit-code contract.
  - [x] Restate the **rebuild-`:latest`** done-criteria caveat in the README ([[runner-image-stale-causes-exit-20]] — hand-built `:latest` images don't auto-rebuild; a run on a stale image won't see openspec).

- [x] **Task 6 — Verify conformance ITs + CI; (optional) extend self-test assertion** (AC: 3, 5)
  - [x] Build both images offline locally and run `--self-test` to confirm the openspec assertion passes via mock: `docker build -f runners/codex/Dockerfile --build-arg INSTALL_CODEX_CLI=false -t deliveryline/codex-runner:it .` then `docker run --rm deliveryline/codex-runner:it --self-test` (twin for claude). On Docker-less Windows, run the Docker tier in WSL2 ([[wsl-linux-ci-reproduction]]).
  - [x] Run the conformance ITs in the Docker tier (`@Tag("docker-runner-it")`, `[[docker-it-needs-exact-docker-runner-it-tag]]`): `mvn -pl deliveryline-backend test -Dtest=CodexRunnerImageConformanceIT,ClaudeRunnerImageConformanceIT -Dgroups=docker-runner-it -DexcludedGroups=`. They are already `*IT` (Failsafe/Docker tier) — do not rename ([[springboot-testcontainers-test-must-be-IT]]).
  - [x] **Optional (recommended) test hardening:** extend `selfTestExitsZeroAndPrintsSummary` in **both** ITs to capture container stdout (via `logContainerCmd`, the helper already exists in the Codex IT as `captureContainerLogs`) and assert it contains `openspec version` + the pin — turning AC3 into a CI-pinned check. If you skip it, the entrypoint's hard-fail still enforces AC3 (self-test exit 0 would be impossible if openspec were missing).
  - [x] Confirm the CI `runner-image-compat` job needs **no change** (it builds both images with `INSTALL_*_CLI=false` from root context and re-runs `RunnerContractValidatorTest`; the mock-openspec keeps that build green and offline). Only edit `.github/workflows/ci.yml` if you choose to pin `--build-arg OPENSPEC_VERSION` there (not required — the Dockerfile default applies).

- [x] **Task 7 — Activation spike + write up the finding** (AC: 4)
  - [x] Time-box a spike: does `codex exec` / `claude -p` discover or invoke OpenSpec in a single-prompt headless run? (Real runs need `network-mode=bridge` and a credential — out of scope for the offline tier; if a live spike isn't feasible, reason from the OpenSpec docs: activation is slash-command/`AGENTS.md`-driven, which a single hardcoded prompt does not trigger.) Record the outcome and the resulting decision (nudge wired vs limitation documented) in Completion Notes and the READMEs.

- [x] **Runner logging discipline** (cross-cutting; runner-shell variant of the project logging standard)
  - [x] This story touches **shell entrypoints + Dockerfiles**, not Java services — the SLF4J/MDC standard does not apply. The runner's logging contract is the `entrypoint.sh` `log()` helper writing structured `[<runner>] [LEVEL] [cid=…] msg` lines to the **container's stderr** ([[runner-entrypoint-logs-to-container-stderr]]), and `--self-test` echoing a health summary to stdout.
  - [x] New self-test output (the openspec version line) goes through the existing `echo` summary / `log INFO` style. If a prompt nudge is added (AC4), keep it free of any secret/credential text.
  - [x] **NEVER** echo env values: `mock-openspec.sh` reads only `OPENSPEC_VERSION`; the self-test prints the openspec version + path only. The conformance IT's existing negative-leak assertions (`SECRET_SENTINEL`, `AUTH_SENTINEL` never in logs/result) must continue to hold.

### Logging Requirements (project-wide standard)

**Applicability note:** the SLF4J + Logback + MDC standard below is the **backend Java** contract and is **N/A to this story** (no Java production code changes). It is retained for reference; the runner-shell logging discipline in the "Runner logging discipline" task above is the governing one here. The only Java touched is the conformance ITs (test code), which assert on logs rather than emit them.

- **Framework:** SLF4J + Logback (backend only); runner images use the `entrypoint.sh` `log()` shell helper → container stderr.
- **Where to log (runner):** `--self-test` health summary (stdout); per-phase `log INFO/ERROR` lines (container stderr); failure branches write a schema-valid failure result, not a bare log.
- **Required context keys (runner):** `DELIVERYLINE_CORRELATION_ID` (when set) is already prepended to every `log()` line.
- **Forbidden in any output:** secret values, auth headers/tokens, full bundle payloads, raw exec-args/prompt. The openspec additions introduce **no** secret surface.
- **Test contract:** the conformance ITs pin the negative-leak assertions; the optional self-test stdout assertion (Task 6) pins the openspec version line.

## Dev Notes

### The change in one sentence

Add the pinned `@fission-ai/openspec` CLI to **both** runner images via the existing npm-global install layer (real in production, a baked `mock-openspec.sh` in the offline/`INSTALL_*_CLI=false` build), assert it in `--self-test`, document it, and verify-or-document whether it activates in headless agent runs — **mirror across both runners in one PR, no runner-contract change.**

### The offline/mock-build decision (READ FIRST — this is the crux)

The conformance ITs (`CodexRunnerImageConformanceIT`, `ClaudeRunnerImageConformanceIT`) and the CI `runner-image-compat` job build the images with `INSTALL_CODEX_CLI=false` / `INSTALL_CLAUDE_CLI=false`. That branch deliberately bakes a **mock** of the agent CLI (`mock-codex.sh` → `/usr/local/bin/codex`) and does **no real `npm install`**, so the gate stays *offline + deterministic* (the CI comment says so explicitly). Both ITs run the image's `--self-test`, which today asserts the agent CLI's version.

If AC2/AC3 make `--self-test` assert `openspec --version`, then the **offline image must contain something answering `openspec --version` with the pin** — otherwise the existing `selfTestExitsZeroAndPrintsSummary` IT (and the CI build) break (violating AC5). Three options:

| Option | Verdict |
|---|---|
| **A — Bake `mock-openspec.sh` in the `else` branch** (mirrors `mock-codex.sh`) | **CHOSEN.** Faithful to the existing pattern; self-test asserts openspec **unconditionally** and stays green in prod (real) + offline (mock). |
| B — Add `INSTALL_OPENSPEC=true` and real-install openspec even in the offline build | **Rejected.** Requires network `npm install` at docker-build time in the conformance/CI build — undermines the determinism/offline guarantee that gate exists to protect; risks CI flakiness on npm-registry hiccups. |
| C — Make the self-test openspec check conditional (skip when missing) | **Rejected.** Brittle (entrypoint can't see the build arg without surfacing it), and AC2 wants an unconditional assertion. |

### Why reuse the existing `INSTALL_*_CLI` gate (not a new `INSTALL_OPENSPEC`)

The existing gate is really a **"production vs mock/offline build"** switch, not a per-tool switch. Reusing it means the offline build path automatically gets *both* mocks (codex + openspec) with no new combinatorial build matrix, and the real build gets both real tools. The proposal allows either; this keeps the Dockerfile diff minimal and the offline build fully network-free. (If a future story needs to install the real agent CLI but mock openspec, revisit — not needed now.)

### "Active during runs" — the honest picture (AC4)

- The entrypoints invoke the agent **headlessly, single-prompt**: `codex exec --sandbox … -C /workspace/repo` (stdin = a hardcoded `PROMPT_INSTRUCTION` + the ticket prompt) and `claude -p --dangerously-skip-permissions` (cwd = `/workspace/repo`, stdin = prompt).
- OpenSpec's UX is **slash commands** (`/opsx:new`, `/opsx:apply`, `/opsx:archive`, …) and an **`AGENTS.md`** instructions file produced by `openspec init`. Codex auto-reads a repo-root `AGENTS.md`; Claude Code auto-reads `CLAUDE.md`/`AGENTS.md`. But a single hardcoded prompt does **not** type a slash command, and `openspec init` is **interactive** (tool selection) + **repo-mutating**.
- **Therefore:** the reliable, shippable deliverable is **"openspec present + CLI-invokable"** (the agent *can* shell out to `openspec`). Auto-activation is a documented spike; if confirmed, wire only a minimal non-mutating prompt nudge. **Do not** auto-`init` at runtime (Trap T-NO-RUNTIME-INIT). This matches the sprint-change-proposal's explicit fallback ("present + CLI-invokable") and its "headless activation is the real risk" note.

### Why this rides existing structure — MIRROR, do not rebuild

| Capability | Location | Use |
|---|---|---|
| Real-CLI npm-global install layer | `runners/codex/Dockerfile:58-74` (`RUN … npm install -g @openai/codex@… ; npm cache clean`) + claude twin | Add `npm install -g @fission-ai/openspec@${OPENSPEC_VERSION}` in the same branch/layer. |
| Offline-mock CLI bake | same `RUN` `else` branch (`install -m 0755 …/mock-codex.sh /usr/local/bin/codex`) | Add `install -m 0755 …/mock-openspec.sh /usr/local/bin/openspec`. |
| Version pin → ENV → self-test | `ARG CODEX_CLI_VERSION` → `ENV CODEX_CLI_VERSION` → `run_self_test()` version compare | Add `ARG OPENSPEC_VERSION` → `ENV OPENSPEC_VERSION` → openspec compare block. |
| Mock CLI shape | `runners/codex/test/mock-codex.sh` (answers `--version`, no env read) | Copy shape into `mock-openspec.sh`. |
| Self-test summary | `run_self_test()` `echo "  codex version: …"` lines | Append an `openspec version:` line. |
| Conformance self-test test | `CodexRunnerImageConformanceIT.selfTestExitsZeroAndPrintsSummary` + `captureContainerLogs` helper | (Optional) assert stdout contains the openspec line. |
| CI build (offline, root context) | `.github/workflows/ci.yml` `runner-image-compat` (builds both with `INSTALL_*_CLI=false`) | No change — mock-openspec keeps it offline/green. |
| Version-pin doc section | README "Codex CLI version pinning + upgrade procedure" | Mirror an "OpenSpec version pinning" subsection. |

### Confirmed external facts (web research, June 2026)

- **npm package:** `@fission-ai/openspec` (scoped). **CLI binary:** `openspec`. **Version check:** `openspec --version`. Latest published **1.4.1** (confirm + pin the current value at build time with `npm view @fission-ai/openspec version` — do not trust this doc's number).
- **Node requirement:** ≥ 20.19.0 — `node:22-slim` (the runner base) satisfies it; **no base-image change**.
- **Install:** `npm install -g @fission-ai/openspec@<version>`.
- **Usage model:** `openspec init` scaffolds an `openspec/` dir (`changes/`, `archive/`) + an `AGENTS.md`; agents drive it via **slash commands**. OpenSpec is **local** (manages spec files on disk) — it does **not** require network/login to run once installed, so it works under runtime `--network=none` or `bridge` alike (unlike Codex subscription token refresh, which needs egress).

### Traps (do NOT step on these)

- **T-OFFLINE-BUILD-BREAKS — the #1 way to fail this story.** If openspec install is gated `true`-only **and** the self-test asserts it **and** you don't bake a mock, then the `INSTALL_*_CLI=false` build (conformance IT + CI) has no `openspec` → self-test exits 1 → both ITs + CI red (AC5 violation). **Bake `mock-openspec.sh` in the `else` branch.**
- **T-NO-RUNTIME-INIT — never `openspec init` (or any repo write) in the entrypoint.** `init` is interactive (tool selection) and mutates the repo — it would hang headless, dirty the read-only spec/plan stages, and inject spurious files into pr-output diffs. AC4's "active" = present + invokable, not auto-scaffolded.
- **T-MIRROR — both runners, one PR.** Per RUNNER_CONTRACT.md's change rule, edit both Dockerfiles + both entrypoints + both mocks + both READMEs together. A change to one image only is a contract break ([[runner-image-ci-uses-root-context]] also reminds: build with `-f runners/<x>/Dockerfile … .` root context + mock CLI).
- **T-NO-SECRET-LEAK — mock + self-test must stay env-blind.** The conformance IT pins that injected sentinels (`sk-codex-…`, `oauth-…`) never reach logs/result. `mock-openspec.sh` reads only `OPENSPEC_VERSION`; the self-test prints version+path only. Don't echo env.
- **T-PIN-NO-FLOAT — pin the version.** `ARG OPENSPEC_VERSION=<n.n.n>`, no `latest`. Reproducibility convention (matches the CLI pins). Confirm package name + version via `npm view` (scoped name `@fission-ai/openspec` is easy to typo).
- **T-LAYER-BUDGET — keep it lean + under ~1 GB.** Install in the same `RUN` layer, before `npm cache clean --force`. openspec is pure-JS (small), but re-measure and update the README size table (AC6).
- **T-STALE-LATEST — rebuild `:latest` after merge.** Hand-built `:latest` images don't auto-rebuild; a run on the old image won't see openspec (manifests as missing tooling) ([[runner-image-stale-causes-exit-20]]). Add to done-criteria.
- **T-CONFORMANCE-TIER — run the ITs in the Docker tier only.** They're `@Tag("docker-runner-it")` + `*IT` (Failsafe). On Windows without Docker, use WSL2 ([[wsl-linux-ci-reproduction]], [[docker-it-needs-exact-docker-runner-it-tag]], [[springboot-testcontainers-test-must-be-IT]]). Gates via PowerShell, not Bash ([[rtk-hook-only-matches-bash]]).
- **T-ENTRYPOINT-STYLE-DIVERGES — the two version-compares differ.** Codex self-test does a `case "$version" in *"$EXPECTED"*)` substring match; Claude does `set -- $version; token=$1; [ "$token" != "$EXPECTED" ]` exact-token match. Mirror **each entrypoint's own style** for the openspec block, don't paste one into the other verbatim.

### Validation / scope posture

- **NO** Java production change. **NO** Flyway / REST / OpenAPI / `schema.d.ts` / `DomainErrorCode` / registry value / ArchUnit-relevant package. **NO** runner-contract change (exit codes, mounts, bundle/result schema, stage mapping all byte-identical).
- **Surface:** 2 Dockerfiles (+`ARG`/`ENV`/`COPY`/install lines), 2 new `mock-openspec.sh`, 2 entrypoints (+self-test block, optional prompt nudge), 2 READMEs, `RUNNER_CONTRACT.md`, optionally 2 conformance ITs (test-only assertion) and `ci.yml` (only if pinning the build-arg).
- **Gates:** offline `docker build` of both images + `--self-test` (WSL2 if no Windows Docker); the two conformance ITs in the `docker-runner-it` tier; CI `runner-image-compat` (should pass unchanged). No backend fast-tier change needed unless the conformance ITs are edited (then run them).

### Project Structure Notes

```
runners/
├── RUNNER_CONTRACT.md                        (MODIFIED — note agent-side tooling: openspec)
├── codex/
│   ├── Dockerfile                            (MODIFIED — ARG/ENV OPENSPEC_VERSION; COPY mock-openspec.sh; install real|mock)
│   ├── entrypoint.sh                         (MODIFIED — self-test openspec block + summary; optional prompt nudge)
│   ├── README.md                             (MODIFIED — openspec pin/upgrade, size table, activation finding)
│   └── test/
│       └── mock-openspec.sh                  (NEW — deterministic --version mock, env-blind)
└── claude/
    ├── Dockerfile                            (MODIFIED — mirror of codex)
    ├── entrypoint.sh                         (MODIFIED — mirror; keep claude's exact-token compare style)
    ├── README.md                             (MODIFIED — mirror)
    └── test/
        └── mock-openspec.sh                  (NEW — mirror)

deliveryline-backend/src/test/java/org/dradgo/adapters/runner/
├── CodexRunnerImageConformanceIT.java        (OPTIONAL — assert self-test stdout contains openspec version)
└── ClaudeRunnerImageConformanceIT.java       (OPTIONAL — mirror)

.github/workflows/ci.yml                      (OPTIONAL — only if pinning --build-arg OPENSPEC_VERSION; not required)
```

### References

- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-13.md §"Story 3a-6 — OpenSpec CLI in both runner images"] — originating change proposal (sketch ACs, risks, mirror rule, "active during runs" framing, headless-activation as the real risk).
- [Source: runners/codex/Dockerfile:22-78] — `ARG CODEX_CLI_VERSION` / `ENV` / `COPY mock-codex.sh` / the `RUN` install-real-or-mock branch to mirror for openspec.
- [Source: runners/claude/Dockerfile:24-83] — Claude twin of the above.
- [Source: runners/codex/entrypoint.sh:127-160] — `run_self_test()` codex version-compare block + OK summary to mirror.
- [Source: runners/claude/entrypoint.sh:120-152] — Claude `run_self_test()` (note the exact-token compare style differs from Codex).
- [Source: runners/codex/entrypoint.sh:364-396] — `PROMPT_INSTRUCTION` strings + headless `codex exec` invocation (where an optional AC4 nudge would go).
- [Source: runners/claude/entrypoint.sh:332-358] — Claude `-p` headless invocation + cwd=repo.
- [Source: runners/codex/test/mock-codex.sh] — mock CLI shape to mirror (`--version` echo, env-blind, exit 0).
- [Source: runners/RUNNER_CONTRACT.md:10-20,144-148] — change rule (both runners, same PR) + least-privilege section to extend with the agent-side-tooling note.
- [Source: runners/codex/README.md:137-157] — CLI version-pin/upgrade procedure + Image size/layer table to mirror for openspec.
- [Source: runners/claude/README.md:84-111] — Claude version-pin + size table (147 MB / 10 layers) to update.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java:74-136,308-324] — offline build (`INSTALL_CODEX_CLI=false`) + `selfTestExitsZeroAndPrintsSummary` + `captureContainerLogs` helper (optional AC3 assertion).
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/ClaudeRunnerImageConformanceIT.java:121-132] — Claude self-test test (mirror any assertion).
- [Source: .github/workflows/ci.yml:510-559] — `runner-image-compat` job: builds both images `INSTALL_*_CLI=false` from root context, re-validates v1 fixtures (should stay green unchanged).
- [Reference: https://www.npmjs.com/package/@fission-ai/openspec] — package name + current version (`npm view @fission-ai/openspec version`).
- [Reference: https://github.com/Fission-AI/OpenSpec/blob/main/docs/installation.md] — install command, `openspec` binary, Node ≥20.19.
- [Reference: https://openspec.pro/getting-started/] — `openspec init` (creates `openspec/` + `AGENTS.md`), slash-command UX (`/opsx:*`) — the basis for the AC4 activation analysis.
- [Source: _bmad-output/implementation-artifacts/3a-3-codex-subscription-auth-via-auth-json.md] — prior runner-image story: entrypoint conventions, conformance-IT log-capture pattern, RUNNER_CONTRACT change discipline.
- [Source: _bmad-output/implementation-artifacts/3a-5-scheduled-linear-auto-ingest-poll-driven-run-creation.md] — immediately preceding 3a story (format reference; unrelated backend domain).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story

### Debug Log References

- `npm view @fission-ai/openspec version` → **1.4.1**; `engines` → `node >=20.19.0` (satisfied by `node:22-slim`). Pinned `ARG OPENSPEC_VERSION=1.4.1` in both Dockerfiles.
- Offline build + `--self-test` (Docker Desktop 28.5.1, native): both images built with `INSTALL_*_CLI=false` exit `0` and report `openspec version: 1.4.1 (expected pin: 1.4.1)` via the baked `mock-openspec.sh` — confirms AC2/AC3/AC5 before the IT run.
- OpenSpec footprint measured via `npm install -g @fission-ai/openspec@1.4.1` on `node:22-slim`: `@fission-ai` package dir ≈ **19 MB** (pure-JS, no native binary). The new `COPY mock-openspec.sh` adds one image layer (offline mock images measured at **11 layers**, was 10).
- Conformance ITs (Docker tier, `-Dgroups=docker-runner-it`): **11 tests, 0 failures** (`ClaudeRunnerImageConformanceIT` 6, `CodexRunnerImageConformanceIT` 5) — includes the new self-test stdout assertion (`openspec bin:` / `openspec version:`) and the unchanged negative-leak assertions.

### Completion Notes List

- **Mirror-pair change (one PR):** both Dockerfiles, both entrypoints, both new `mock-openspec.sh`, both READMEs, and `RUNNER_CONTRACT.md` updated together per the RUNNER_CONTRACT change rule. No Java production change; no runner-contract change (exit codes / mounts / bundle+result schema / stage mapping byte-identical).
- **AC1/AC2 install:** `ARG OPENSPEC_VERSION=1.4.1` + `ENV OPENSPEC_VERSION`; real `npm install -g @fission-ai/openspec@${OPENSPEC_VERSION}` only in the `INSTALL_*_CLI=true` branch (same npm layer as the agent CLI, before `npm cache clean`); a deterministic `mock-openspec.sh` (`install -m 0755 … /usr/local/bin/openspec`) in the `else` (offline) branch. The mock answers `--version` with the pin and reads no env other than `OPENSPEC_VERSION` (no secret-leak surface).
- **AC3 self-test:** each `run_self_test()` gained an openspec block — Codex uses the substring compare (`case *"$EXPECTED"*`), Claude uses the exact-token compare (`set -- $version; token=$1`), each mirroring its own existing CLI-version style (T-ENTRYPOINT-STYLE-DIVERGES). Fails with `SELF-TEST FAIL:` + `exit 1` if openspec is missing or off-pin; OK summary adds `openspec bin:` + `openspec version:` lines.
- **AC4 activation finding (the spike, Task 7):** shipped the **minimum — openspec present + CLI-invokable**. A live headless-activation spike needs egress + a credential (out of scope for the offline tier). Reasoning from the OpenSpec docs: activation is slash-command (`/opsx:*`) + `AGENTS.md`-driven; both entrypoints invoke the agent single-prompt headless and never type a slash command, and we must NOT run `openspec init` at runtime (T-NO-RUNTIME-INIT — interactive + repo-mutating). Auto-firing **cannot be confirmed** in single-prompt headless mode, so the `PROMPT_INSTRUCTION` strings are **left unchanged** and the limitation is documented in both READMEs ("present + CLI-invokable; slash-command auto-activation deferred"). The story was explicitly NOT blocked on auto-firing.
- **AC5/AC6:** ITs + offline build stay green; production growth ≈ +19 MB (pure-JS) and **10 → 11 layers** (the new COPY); both images remain well under ~1 GB. Size tables in both READMEs updated with the measurement basis. Production baseline (~652 MB codex / ~147 MB claude) was measured on WSL2 earlier; the README notes to re-measure the full production image on WSL2.
- **AC6 test hardening (optional, done):** extended `selfTestExitsZeroAndPrintsSummary` in BOTH ITs to capture container stdout and assert the openspec summary line — turning AC3 into a CI-pinned check. Added the `captureContainerLogs` helper + `Frame`/`ResultCallback` imports to the Claude IT (the Codex IT already had it).
- **CI:** `runner-image-compat` needs **no change** — it builds both images offline (`INSTALL_*_CLI=false`) from root context and re-runs `RunnerContractValidatorTest`; mock-openspec keeps that build offline/green and schemas are untouched. `ci.yml` not edited (the Dockerfile default `OPENSPEC_VERSION=1.4.1` applies).
- **Done-criteria caveat:** hand-built `:latest` images don't auto-rebuild — a run on a stale `:latest` won't have openspec ([[runner-image-stale-causes-exit-20]]); restated in both READMEs.
- **Mock file mode:** new `mock-openspec.sh` kept at git mode `100644` to mirror the existing `mock-codex.sh`/`mock-claude.sh` (also 644); runtime exec bit is set by `install -m 0755` in the Dockerfile.

### File List

- `runners/codex/Dockerfile` (MODIFIED — `ARG`/`ENV OPENSPEC_VERSION`, `COPY mock-openspec.sh`, real|mock install)
- `runners/claude/Dockerfile` (MODIFIED — mirror)
- `runners/codex/test/mock-openspec.sh` (NEW — deterministic `--version` mock, env-blind)
- `runners/claude/test/mock-openspec.sh` (NEW — mirror)
- `runners/codex/entrypoint.sh` (MODIFIED — self-test openspec block + summary lines; prompt unchanged per AC4)
- `runners/claude/entrypoint.sh` (MODIFIED — mirror, exact-token compare)
- `runners/codex/README.md` (MODIFIED — openspec pin/upgrade, tooling, size table, activation finding, `:latest` caveat)
- `runners/claude/README.md` (MODIFIED — mirror)
- `runners/RUNNER_CONTRACT.md` (MODIFIED — "Agent-side tooling" visibility note)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java` (MODIFIED — self-test stdout openspec assertion)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/ClaudeRunnerImageConformanceIT.java` (MODIFIED — self-test stdout openspec assertion + `captureContainerLogs` helper + imports)

## Change Log

| Date | Change |
|---|---|
| 2026-06-13 | bmad-create-story: created 3a-6 from sprint-change-proposal-2026-06-13. Status `backlog → ready-for-dev`. |
| 2026-06-13 | bmad-dev-story: implemented OpenSpec CLI in both runner images (pinned 1.4.1 real install + offline mock, self-test assertion, docs, RUNNER_CONTRACT note, IT stdout assertion). AC4 activation = present + CLI-invokable (auto-activation documented as deferred). Both conformance ITs green (11 tests). Status `ready-for-dev → review`. |
| 2026-06-13 | bmad-code-review: 3-layer adversarial review (Blind Hunter / Edge Case Hunter / Acceptance Auditor). Acceptance audit = full PASS (AC1–AC7 + all traps). Headline HIGH (inter-runner version-compare divergence) dismissed after verifying real `openspec --version` emits a bare `1.4.1` (matches mock; both compares pass). 0 decision-needed, 0 patch, 6 deferred (pre-existing/cosmetic), 8 dismissed. Status `review → done`. |

## Review Findings

_bmad-code-review 2026-06-13 — Blind Hunter + Edge Case Hunter + Acceptance Auditor. Acceptance audit returned a full PASS on AC1–AC7 and every named trap. The only HIGH-severity finding (the two entrypoints using different version-compare strategies — codex substring vs claude exact-token — diverging on multi-token `--version` output) was **dismissed after verification**: the real `openspec --version` (`@fission-ai/openspec@1.4.1`) prints a bare `1.4.1`, which both compares accept, so the runners do not diverge and the pin verifies green on both. No decision-needed or patch findings. Deferred items below are pre-existing mirrored-pattern or cosmetic notes — none block the story._

- [x] [Review][Defer] Codex self-test uses a loose substring version match [runners/codex/entrypoint.sh:161-162] — deferred, pre-existing. `case "$_openspec_version" in *"$EXPECTED_OPENSPEC_VERSION"*)` would falsely accept a substring-superset (e.g. `1.4.10` matches pin `1.4.1`). Mirrors the codex CLI compare style the spec mandated (T-ENTRYPOINT-STYLE-DIVERGES); practically unreachable since install and expected-pin share the one `OPENSPEC_VERSION` ARG. Claude's exact-token compare is not affected.
- [x] [Review][Defer] `set -- $_openspec_version` lacks a `set -f` glob guard [runners/claude/entrypoint.sh:153] — deferred, pre-existing. A version token containing a glob metachar (`*`/`?`/`[`) would undergo pathname expansion. Mirrors the existing claude CLI version line; semver pins won't trigger it.
- [x] [Review][Defer] `openspec --version` capture has no timeout [runners/codex/entrypoint.sh:160, runners/claude/entrypoint.sh:152] — deferred, pre-existing. A hung CLI would stall `--self-test` (`2>/dev/null || echo ''` swallows exit, not a hang). Mirrors the existing agent-CLI version capture; no doctor timeout in either entrypoint today.
- [x] [Review][Defer] Entrypoint `<unset>` default disagrees with mock `0.0.0-mock` default [runners/{codex,claude}/entrypoint.sh + runners/{codex,claude}/test/mock-openspec.sh] — deferred, pre-existing. If `OPENSPEC_VERSION` were ever unset at runtime the expected pin becomes the literal `<unset>` while the mock emits `0.0.0-mock`; latent because both Dockerfiles set `ENV OPENSPEC_VERSION=1.4.1`, and it fails-safe (self-test fails rather than passing wrongly).
- [x] [Review][Defer] Conformance IT asserts the openspec summary labels, not the pinned version value [CodexRunnerImageConformanceIT.java, ClaudeRunnerImageConformanceIT.java] — deferred, pre-existing. `contains("openspec bin:")`/`contains("openspec version:")` pin the lines but not the `1.4.1` value; the self-test exit-0 gate already enforces the pin, so this is optional test hardening.
- [x] [Review][Defer] Docs cite "AC9"/"AC4 mock" numbering carried over from stories 3.3/3.4 [runners/codex/README.md, runners/claude/README.md, runners/codex/Dockerfile comment] — deferred, cosmetic. Story 3a-6's ACs only run to AC7; the legacy AC numbers are harmless references, no functional impact.
