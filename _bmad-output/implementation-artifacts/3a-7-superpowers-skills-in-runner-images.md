# Story 3a.7: obra/superpowers Skills in Both Runner Images

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **the DeliveryLine platform**,
I want **the obra/superpowers skills collection baked — pinned, offline-buildable — into BOTH runner images (`deliveryline/codex-runner` story 3.3 and `deliveryline/claude-runner` story 3.4) and discoverable by the agent at startup**,
so that **autonomous agent runs can use superpowers skills (the sibling capability to 3a-6's OpenSpec, both following the same "enrich both runner images with agent-side tooling" pattern).**

## Context — sibling to 3a-6, different install mechanism

This is the **twin** of story 3a-6 (OpenSpec CLI): both add agent-side tooling to the two runner images under the **mirror rule** (both images, one PR). They are **independent** — no shared code; sequenced **after 3a-6** only to keep review surfaces clean (the second PR rebases on the first; both touch the same Dockerfiles/entrypoints/READMEs).

**The install mechanism differs from 3a-6 in three decisive ways:**
1. **Skills, not a CLI.** superpowers is a collection of skill folders (each a `SKILL.md` + assets), discovered by the agent from a filesystem path — there is no `--version` binary to pin; the pin is a **vendored commit SHA**.
2. **Per-runner discovery path differs.** **Codex** auto-discovers from `~/.agents/skills/` (clone + symlink, confirmed filesystem-based, offline). **Claude**'s offline filesystem path is under-documented (the marketplace is the blessed route) → the exact Claude discovery dir is a **dev-confirm decision** against the installed CLI (`2.1.149`).
3. **Offline-build tension is sharper.** A `git clone` needs `git` (absent from `node:22-slim`) **and** network at build — which the offline conformance/CI build (`INSTALL_*_CLI=false`) forbids. Yet `--self-test` must assert the skills resolve in that offline build. **Resolution: vendor superpowers via `COPY` (pinned commit) instead of cloning** — present in both prod and offline builds, no `git` layer, no build-time network, deterministic (see Dev Notes "The vendor-vs-clone decision").

**Like 3a-6, the riskiest AC is "active during runs", not installation.** Codex *loads/discovers* skills at startup (filesystem scan); Claude needs its discovery dir confirmed. Whether either agent actually *invokes* a skill in a **single-prompt headless** run (`codex exec` / `claude -p`, no human typing, no slash command) is unverified — skills activate by name-mention / task-description match. AC5 is satisfied by **present + discoverable + a documented spike with a safe fallback**; do **not** block the story on auto-firing, and do **not** mutate the working repo at runtime.

> **Mirror rule (RUNNER_CONTRACT.md change rule):** every change here edits **both** Dockerfiles, **both** entrypoints (`--self-test`), and **both** READMEs **in the same PR** — mirror, do not re-derive. `runners/RUNNER_CONTRACT.md` is updated too.

## Acceptance Criteria

1. **Vendored, pinned, offline-safe install in BOTH Dockerfiles.** superpowers is **vendored into the repo** at a **pinned commit SHA** (no floating `main`) and installed via `COPY` (not `git clone`) so the install needs **no `git` and no network at build** — present in both the production (`INSTALL_*_CLI=true`) and offline/mock (`INSTALL_*_CLI=false`) builds. The pinned SHA + source URL are recorded (a `VENDOR`/README note). No `git` apt layer is added to either image. *(If a future need forces clone-at-build, that is the documented alternative — see Dev Notes — but it is NOT this story's path.)*

2. **Codex placement + symlink, non-root ownership.** In `runners/codex/Dockerfile`: the vendored tree lands at `/home/codex/.codex/superpowers`, and `~/.agents/skills/superpowers` is symlinked to `/home/codex/.codex/superpowers/skills`, **owned by `codex:1001`** (`COPY --chown=1001:1001` + `ln -s`; the symlink parent `/home/codex/.agents/skills` is created with correct ownership). Codex auto-discovers `~/.agents/skills/` at startup (confirmed filesystem-based, offline). Optionally set `[features] multi_agent = true` in `/home/codex/.codex/config.toml` (note only — out of scope unless trivial).

3. **Claude placement, non-root ownership (mechanism confirmed in dev).** In `runners/claude/Dockerfile`: the same vendored skills are placed under the **Claude discovery path** in `/home/claude/.claude/…`, owned by `claude:1001`. **The exact mechanism is a dev-confirm decision** against the installed Claude Code CLI (`2.1.149`): personal-skills dir (`~/.claude/skills/<skill>/SKILL.md`) vs a plugin dir (`~/.claude/plugins/…`). Default: **mirror the codex skills-dir approach** (place/symlink the skill folders where the CLI scans them); confirm discovery via `--self-test`. Full plugin machinery (hooks / SessionStart injection) is **out of scope** — skills-dir discovery is the minimal, offline, filesystem target.

4. **`--self-test` asserts the skills resolve on BOTH runners.** `run_self_test()` in each entrypoint gains a superpowers check (mirroring 3a-6's openspec block): fail with a `SELF-TEST FAIL:` message + **exit `1`** if the skills directory is missing or the symlink does not resolve (codex) / the Claude discovery dir is missing (claude); on success print a summary line (e.g. `superpowers: <N> skills at <path> (pin <sha>)`). Because the skills are vendored via `COPY` (AC1), this assertion is **green in the offline build** the conformance IT exercises — **no mock needed**.

5. **Active-during-runs — discoverable, verified or limitation documented.** Establish and record whether superpowers is usable by the headless agent:
   - **Minimum (required):** the skills are **present + discoverable** — Codex scans `~/.agents/skills/` at startup; Claude scans its confirmed discovery dir. No runtime repo mutation required.
   - **Spike (required, time-boxed):** verify whether a skill **activates** in single-prompt headless mode (`codex exec` / `claude -p`). If confirmable cheaply, wire the **minimal** nudge (a sentence in the operating-mode prompt noting superpowers skills are available); if not, **document the limitation** ("present + discoverable; name-mention/task-match activation needs interactive use / future wiring") in the README + Completion Notes and ship the minimum. **NOT blocked on auto-firing.**
   - **Hard constraint:** **no runtime mutation of `/workspace/repo`** and no interactive install step (Trap T-NO-RUNTIME-MUTATION).

6. **Pinned version + update procedure documented.** The vendored commit SHA is the pin; both READMEs document the **re-vendor / update procedure** (bump the SHA, re-vendor, rebuild, `--self-test`), mirroring 3a-6's CLI-pin section. No floating `main`.

7. **Image stays within the ~1 GB budget; size/layer table updated.** superpowers is markdown + scripts (modest). Re-measure and update the **Image size / layer count** table in **both** READMEs. Both images remain well under ~1 GB.

8. **Conformance ITs + CI stay green; no contract regression.** `CodexRunnerImageConformanceIT` + `ClaudeRunnerImageConformanceIT` still pass (self-test exits 0 reporting superpowers via the vendored COPY; artifact-variant runs still produce schema-valid results; negative-leak assertions still hold). The CI `runner-image-compat` line still builds **both** images (`-f runners/<x>/Dockerfile … .`, root context, `INSTALL_*_CLI=false`) and re-validates v1 fixtures. **No** exit-code/mount/bundle-schema/result-schema/stage-mapping change.

9. **Both runners in the SAME PR; docs updated; rebuild `:latest`.** Both Dockerfiles + both entrypoints + both READMEs updated together; `runners/RUNNER_CONTRACT.md` notes the agent-side skills tooling. Restate the rebuild-`:latest` caveat (`[[runner-image-stale-causes-exit-20]]`).

## Tasks / Subtasks

- [x] **Task 0 — Vendor superpowers + confirm the Claude discovery path** (AC: 1, 3)
  - [x] Vendor `obra/superpowers` at a **pinned commit SHA** into the repo (`runners/vendor/superpowers/`); record the SHA + source URL in a `VENDOR` note. Verify the license permits vendoring/redistribution; note it. → Vendored at commit `f2cbfbefebbfef77321e4c9abc9e949826bea9d7` (tag `v5.1.0`; annotated-tag object `ecbd610…` → that commit), **MIT** (permits vendoring). 147 files / 14 skills / ~1.3 MB, tree minus `.git`. Recorded in `runners/vendor/VENDOR.md`.
  - [x] Confirm, against the installed Claude Code CLI `2.1.149`, the **offline filesystem discovery path** (personal `~/.claude/skills/<skill>/SKILL.md` vs plugin dir). Decide the placement; record the finding. → **Chose the personal-skills dir `~/.claude/skills/`** (the marketplace/plugin route is interactive+online, unusable offline); image symlinks `~/.claude/skills/superpowers → …/superpowers/skills`, mirroring the codex collection symlink. Recorded in `runners/claude/README.md` ("Claude discovery path").

- [x] **Task 1 — Codex Dockerfile: COPY vendored skills + symlink + ownership** (AC: 1, 2, 7)
  - [x] `COPY --chown=1001:1001 runners/vendor/superpowers /opt/deliveryline/vendor/superpowers` (unconditional — both branches; staged under `/opt` because the COPY runs **before** `useradd` creates `/home/codex`). The `RUN` `mv`s it to `/home/codex/.codex/superpowers`.
  - [x] In the existing `RUN` layer (after `useradd`): create `/home/codex/.agents/skills`, `ln -s /home/codex/.codex/superpowers/skills /home/codex/.agents/skills/superpowers`, `chown -R codex:codex`. Verified ownership `codex:codex` (uid 1001) on tree + symlink + `SKILL.md`. `config.toml [features] multi_agent` left unset (out-of-scope-unless-trivial; not required by any AC).
  - [x] Surface the pin as `ARG SUPERPOWERS_PIN=f2cbfbef…` → `ENV SUPERPOWERS_PIN` so `--self-test` reports it. **No** `git` apt layer added.

- [x] **Task 2 — Claude Dockerfile: mirror with the confirmed discovery path** (AC: 1, 3, 7)
  - [x] Mirror of codex: `COPY --chown=1001:1001 runners/vendor/superpowers /opt/deliveryline/vendor/superpowers`; `RUN` `mv`s to `/home/claude/.claude/superpowers` and symlinks `~/.claude/skills/superpowers → …/skills`, owned `claude:1001`. Only the discovery path differs. Same `SUPERPOWERS_PIN` ARG/ENV.

- [x] **Task 3 — Self-test superpowers assertion in BOTH entrypoints** (AC: 4)
  - [x] `runners/codex/entrypoint.sh` `run_self_test()` (after the openspec block): `[ -d "$SUPERPOWERS_SKILLS_DIR" ]` (follows symlink → false for missing **and** dangling) + `find -L … -name SKILL.md` ≥ 1; `SELF-TEST FAIL:` + `exit 1` otherwise; OK summary line `superpowers: <N> skills at <path> (pin <sha>)`. Negative test confirmed exit 1 when the dir is bogus.
  - [x] Mirrored in `runners/claude/entrypoint.sh` against `~/.claude/skills/superpowers`, keeping the claude entrypoint's own style.

- [x] **Task 4 — Active-during-runs spike + (optional) prompt nudge** (AC: 5)
  - [x] Shipped the **minimum**: skills present + discoverable (Codex auto-scans `~/.agents/skills/` at startup; Claude personal-skills dir is the offline target). Headless single-prompt **activation** (a skill firing in `codex exec` / `claude -p`) is **not confirmable offline** (no egress/credential) — same posture as 3a-6's OpenSpec finding. Left `PROMPT_INSTRUCTION` **unchanged**; documented the limitation in both READMEs + here. **No `/workspace/repo` mutation, no interactive install** (T-NO-RUNTIME-MUTATION honored).

- [x] **Task 5 — Update both READMEs + RUNNER_CONTRACT.md** (AC: 6, 7, 9)
  - [x] Both READMEs: "superpowers skills pin + update procedure" subsection (vendored SHA + re-vendor steps pointing at `VENDOR.md`, rebuild, `--self-test`), per-runner placement (codex `~/.agents/skills` symlink vs claude `~/.claude/skills` personal-skills dir), the new self-test line, the AC5 activation finding, `SUPERPOWERS_PIN` baked-env row (codex), re-measured size/layer table (11 → 12 layers, +~1.3 MB). `runners/RUNNER_CONTRACT.md` "Agent-side tooling" section extended (visibility only; byte-identical runner↔backend contract). Rebuild-`:latest` caveat restated (`[[runner-image-stale-causes-exit-20]]`).

- [x] **Task 6 — Verify conformance ITs + CI** (AC: 4, 8)
  - [x] Built **both** images offline on Windows Docker 28.5.1 (`--build-arg INSTALL_*_CLI=false`) + ran `--self-test`: exit 0, each reporting `superpowers: 14 skills at … (pin f2cbfbef…)`.
  - [x] Ran both conformance ITs in the Docker tier (`-Dgroups=docker-runner-it`): **Codex 5/5, Claude 6/6, 0 failures** (incl. the 3 schema-variant runs + negative-leak assertions). Extended `selfTestExitsZeroAndPrintsSummary` in BOTH ITs to assert the `superpowers:` summary line (via `captureContainerLogs`).
  - [x] CI `runner-image-compat` needs **no change** — the COPY keeps the offline root-context build green (verified locally); image-size delta is ~1.3 MB (well under ~1 GB).

- [x] **Runner logging / build discipline** (cross-cutting; Docker + shell, no Java)
  - [x] No Java **production** change (only the two test-tier ITs). Self-test prints the skills path/pin/count only — **never** secrets; the conformance negative-leak assertions still pass (both ITs green).

### Logging Requirements (project-wide standard)

**Applicability:** N/A for the backend SLF4J standard — this story is Docker + shell only (no Java production code). The governing discipline is the runner-shell `log()`/`--self-test` convention.

- **Framework:** runner images use the `entrypoint.sh` `log()` helper + `--self-test` summary (stdout).
- **Where to log (runner):** `--self-test` superpowers summary; any future prompt-nudge stays free of secrets.
- **Required context keys:** existing `DELIVERYLINE_CORRELATION_ID` (already prepended by `log()`).
- **Forbidden:** secrets/tokens, bundle payloads, raw prompts.
- **Test contract:** conformance negative-leak assertions hold; the optional self-test summary assertion pins the superpowers line.

### Review Findings

_bmad-code-review 2026-06-13 (uncommitted working-tree diff vs HEAD; Blind Hunter + Edge Case Hunter + Acceptance Auditor). Acceptance Auditor verdict: **ACCEPT** — all 9 ACs + every called-out constraint met. Triage: 1 decision-needed, 0 patch, 3 defer, 8 dismissed as noise._

- [x] [Review][Patch] Skills-count guard hardened from `>=1` to a floor of `10` — self-test previously failed only on `_superpowers_count -lt 1`, so a truncated/partial re-vendor (e.g. 1 of 14 skill dirs) passed green. **Resolved (Alex, floor-assert):** both `entrypoint.sh` files now `[ "${_superpowers_count:-0}" -lt 10 ]` with a clearer failure message (`expected >= 10; pinned tree ships 14`). Floor (not exact 14) catches gross truncation while tolerating small legit count changes on re-vendor. Applied to BOTH runners (mirror rule); both `bash -n` clean. (sources: blind+edge)
- [x] [Review][Defer] Production-CLI image's skills layer never built by conformance ITs [ClaudeRunnerImageConformanceIT.java / CodexRunnerImageConformanceIT.java] — deferred, pre-existing. ITs always build the offline (`INSTALL_*_CLI=false`) variant; the real-CLI image as a whole is never assembled in CI. Risk is low here because the `COPY`+`mv`+`ln` skills block sits *after* the CLI `if/else` and is branch-independent. (sources: edge)
- [x] [Review][Defer] `SUPERPOWERS_PIN` is a reported label, not verified against vendored bytes [runners/*/entrypoint.sh] — deferred, pre-existing. `--self-test` echoes the ENV pin; nothing ties that SHA to the COPY'd tree (no checksum/commit file). Same class as the 3a-6 OpenSpec "IT asserts label not pin value" deferral. (sources: blind+edge)
- [x] [Review][Defer] No build-time guard that the vendored `skills/` dir exists [runners/claude/Dockerfile, runners/codex/Dockerfile] — deferred, pre-existing. A botched re-vendor with no `skills/` produces a dangling symlink; failure surfaces at runtime `--self-test` (negative test exits 1) rather than failing fast at `docker build`. A `RUN test -d .../superpowers/skills` would shift it left. (sources: edge)

_Dismissed as noise (verified against the actual Dockerfiles/tree): useradd-ordering & mv-nesting (`useradd --create-home` precedes the mkdir/mv/ln; move target is freshly created — no nesting); `SUPERPOWERS_SKILLS_DIR` HOME-coupling (image sets HOME; arbitrary `--user` unsupported); `find 2>/dev/null` error-swallowing (static vendored tree can't be cyclic); `-maxdepth 2` future-nesting undercount (all 14 skills at depth 2 today); `COPY --chown` redundant with later `chown -R` (harmless); asymmetric `.codex`/`.claude` placement (by design); imprecise README size/layer figures (approximate "≈"); redundant `mkdir -p` parent on claude (cosmetic)._

## Dev Notes

### The change in one sentence

Vendor obra/superpowers at a pinned commit into the repo, `COPY` it into **both** runner images (offline-safe, no `git`), wire each runner's skills-discovery path (codex `~/.agents/skills/` symlink; claude's confirmed discovery dir, both owned uid 1001), assert it in `--self-test`, document it, and verify-or-document headless activation — **mirror across both runners in one PR, no runner-contract change.**

### The vendor-vs-clone decision (READ FIRST)

The offline conformance/CI build (`INSTALL_*_CLI=false`) has **no network**, and `node:22-slim` ships **no `git`**. Yet `--self-test` (run by both conformance ITs against that offline build) must assert the skills resolve (AC4). Options:

| Option | Verdict |
|---|---|
| **A — Vendor via `COPY` at a pinned SHA** | **CHOSEN.** Present in prod + offline builds, no `git` layer, no build-time network, deterministic. Self-test assertion is unconditional + green in both. Cost: the superpowers tree lives in our repo (size; license note; re-vendor to update). |
| B — `git clone` (apt `git` + network) in prod branch + a stub skills dir in the offline branch | Rejected: adds a `git` supply-chain + network dependency, breaks offline determinism for any real-CLI conformance build, and needs a stub anyway (`[[runner-tool-self-test-needs-offline-mock]]`). |

This mirrors 3a-6's offline-mock lesson, but vendoring is **cleaner than a mock** here because skill files are static content (a `COPY` works offline in both branches — no executable to fake).

### Per-runner install (confirmed + to-confirm)

- **Codex (confirmed, filesystem-based, offline):** vendored tree at `/home/codex/.codex/superpowers`; `~/.agents/skills/superpowers` → `…/superpowers/skills`; Codex scans `~/.agents/skills/` at startup, parses `SKILL.md` frontmatter, loads on name-mention/task-match. Owned `codex:1001`.
- **Claude (confirm in Task 0):** marketplace is the blessed route (interactive/online — unusable here). Offline target = the CLI's filesystem skills-discovery dir under `/home/claude/.claude/…` — confirm personal-skills (`~/.claude/skills/<skill>/SKILL.md`) vs plugin dir against CLI `2.1.149`; mirror the codex skills-dir intent. Full plugin hooks/SessionStart are **out of scope**.

### "Active during runs" — honest picture (AC5)

Same shape as 3a-6's openspec activation: the runner invokes the agent **headless, single-prompt**; superpowers skills activate by **name-mention / task-description match** (Codex auto-*discovers* at startup, but discovery ≠ invocation in a single hardcoded prompt). Reliable deliverable = **present + discoverable**; a minimal prompt nudge only if the spike confirms it helps; otherwise document the limitation. Never auto-mutate the repo or run an interactive install.

### Why this rides existing structure — MIRROR, do not rebuild

| Capability | Location | Use |
|---|---|---|
| Non-root user + HOME | `runners/codex/Dockerfile:72-73` (`useradd --create-home codex`) + claude twin | `COPY --chown=1001:1001` into `/home/<user>/…`; symlink owned by uid 1001. |
| Real-or-mock build branch | `Dockerfile` `RUN … if INSTALL_*_CLI …` | COPY the vendored skills in BOTH branches (offline-safe; no gating). |
| Pin → ENV → self-test | 3a-6's `ARG OPENSPEC_VERSION`→`ENV`→self-test block | Add `SUPERPOWERS_PIN` (SHA) → `ENV` → self-test skills assertion. |
| Self-test summary | `run_self_test()` `echo "  … version: …"` lines | Append a `superpowers:` line. |
| Conformance self-test test | `CodexRunnerImageConformanceIT.selfTestExitsZeroAndPrintsSummary` + `captureContainerLogs` | (Optional) assert the superpowers summary line. |
| CI offline build (root context) | `.github/workflows/ci.yml` `runner-image-compat` (`INSTALL_*_CLI=false`) | No change — COPY keeps it offline/green. |
| Version-pin doc section | README "CLI version pinning + upgrade procedure" | Mirror a "superpowers pin + update procedure" subsection. |

### Traps (do NOT step on these)

- **T-OFFLINE-BUILD-BREAKS — vendor, don't clone.** A `git clone` (needs `git` + network) breaks the offline conformance/CI build that runs `--self-test`. `COPY` the vendored tree in both branches (`[[runner-tool-self-test-needs-offline-mock]]`).
- **T-NO-RUNTIME-MUTATION — never mutate `/workspace/repo` or run an interactive install at runtime.** "Active" = present + discoverable. No marketplace `/plugin install`, no repo writes.
- **T-NONROOT-OWNERSHIP — uid 1001.** Skills + symlink + their parent dirs must be owned by `codex:1001` / `claude:1001` (the image runs unprivileged). Use `COPY --chown=1001:1001` and create `~/.agents/skills` / the Claude dir with correct ownership.
- **T-MIRROR — both runners, one PR.** Both Dockerfiles + both entrypoints + both READMEs + RUNNER_CONTRACT.md. Build with root context `-f runners/<x>/Dockerfile … .` (`[[runner-image-ci-uses-root-context]]`).
- **T-CLAUDE-PATH-UNCONFIRMED — don't guess.** Confirm Claude's offline skills-discovery dir against CLI `2.1.149` (Task 0); a wrong path → self-test red or silently-undiscovered skills.
- **T-PIN-NO-FLOAT — vendored SHA, not `main`.** Reproducibility convention; record the SHA + re-vendor procedure.
- **T-SIZE-BUDGET — re-measure.** The vendored tree adds to the image; keep under ~1 GB, update the README size table (AC7).
- **T-STALE-LATEST — rebuild `:latest` post-merge** (`[[runner-image-stale-causes-exit-20]]`).
- **T-NO-SECRET-LEAK — self-test prints paths/pin/count only.** Conformance negative-leak assertions must hold.
- **T-3A6-MERGE-ORDER — coordinate with 3a-6.** Both stories touch the same Dockerfiles/entrypoints/READMEs; if 3a-6 merges first, rebase this on top (the self-test gains a second tool block; the install layer gains a COPY). Gates via PowerShell (`[[rtk-hook-only-matches-bash]]`); Docker tier via WSL2 (`[[wsl-linux-ci-reproduction]]`, `[[verify-ci-fixes-in-clean-env]]`).

### Validation / scope posture

- **NO** Java production change, Flyway, REST/OpenAPI/`schema.d.ts`, `DomainErrorCode`/registry value, ArchUnit-relevant package, or runner-contract change (exit codes/mounts/schemas/stage mapping byte-identical).
- **Surface:** vendored `runners/vendor/superpowers/` (+`VENDOR` note), 2 Dockerfiles (+COPY/symlink/ENV/ownership), 2 entrypoints (+self-test block, optional prompt nudge), 2 READMEs, `RUNNER_CONTRACT.md`, optionally 2 conformance ITs (test-only assertion). No `git` apt layer.
- **Gates:** offline `docker build` of both images + `--self-test` (WSL2 if no Windows Docker); the two conformance ITs in the `docker-runner-it` tier; CI `runner-image-compat` (should pass unchanged, watch size).

### Project Structure Notes

```
runners/
├── RUNNER_CONTRACT.md                         (MODIFIED — note vendored superpowers skills)
├── vendor/superpowers/                         (NEW — vendored obra/superpowers @ pinned SHA + VENDOR note)
├── codex/
│   ├── Dockerfile                              (MODIFIED — COPY --chown=1001 vendored skills; ~/.agents/skills symlink; SUPERPOWERS_PIN ENV)
│   ├── entrypoint.sh                           (MODIFIED — self-test skills assertion + summary; optional prompt nudge)
│   └── README.md                               (MODIFIED — superpowers pin/update, size table, activation finding)
└── claude/
    ├── Dockerfile                              (MODIFIED — COPY into confirmed Claude discovery dir, owned claude:1001)
    ├── entrypoint.sh                           (MODIFIED — mirror; keep claude's style)
    └── README.md                               (MODIFIED — mirror)

deliveryline-backend/src/test/java/org/dradgo/adapters/runner/
├── CodexRunnerImageConformanceIT.java          (OPTIONAL — assert self-test stdout contains the superpowers line)
└── ClaudeRunnerImageConformanceIT.java         (OPTIONAL — mirror)
```

### References

- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-13.md §"Story 3a-7 — obra/superpowers skills in both runner images"] — originating definition (git/vendor, per-runner paths, non-root, activation-as-real-risk, mirror rule, ~1 GB budget).
- [Source: _bmad-output/implementation-artifacts/3a-6-openspec-cli-in-runner-images.md] — the sibling story: the offline-build/self-test pattern, version-pin doc shape, mirror rule, conformance-IT approach to mirror.
- [Source: runners/codex/Dockerfile:58-78] — the `RUN` install-real-or-mock branch + `useradd --create-home codex` + `USER codex` (where the COPY/symlink/ownership go).
- [Source: runners/claude/Dockerfile:62-83] — Claude twin (uid/gid 1001, `/home/claude`).
- [Source: runners/codex/entrypoint.sh:127-160] — `run_self_test()` to extend with the skills assertion + summary.
- [Source: runners/claude/entrypoint.sh:120-152] — Claude `run_self_test()` (mirror; its own style).
- [Source: runners/codex/entrypoint.sh:364-396] — `PROMPT_INSTRUCTION` strings (where an optional AC5 nudge would go) + claude twin.
- [Source: runners/RUNNER_CONTRACT.md:10-20,144-148] — change rule (both runners, same PR) + least-privilege section to extend.
- [Source: runners/codex/README.md:124-157] — base-image/layout + version-pin + size/layer table to mirror.
- [Source: runners/claude/README.md:69-111] — Claude base-image/layout + size table (147 MB / 10 layers) to update.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java:74-136,308-324] — offline build (`INSTALL_CODEX_CLI=false`) + `selfTestExitsZeroAndPrintsSummary` + `captureContainerLogs` (optional AC4 assertion).
- [Source: .github/workflows/ci.yml:510-559] — `runner-image-compat` (builds both images `INSTALL_*_CLI=false`, root context; should stay green).
- [Reference: https://obra-superpowers.mintlify.app/installation/codex] — Codex install: clone `~/.codex/superpowers`, symlink `~/.agents/skills/superpowers → …/skills`; `~/.agents/skills/` auto-discovery; filesystem-based, offline.
- [Reference: https://github.com/obra/superpowers] — the source to vendor (pin a commit SHA); skills layout (`skills/<name>/SKILL.md`); license check.
- [Reference: https://deepwiki.com/obra/superpowers/2.1-installing-on-claude-code] — Claude install via marketplace (offline filesystem path to confirm against CLI 2.1.149 in Task 0).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story; bmad-dev-story

### Debug Log References

- Vendoring: `git ls-remote --tags … v5.1.0` → tag-object `ecbd610…`; `git checkout` resolved it to **commit `f2cbfbefebbfef77321e4c9abc9e949826bea9d7`** (the reproducible pin); `tar --exclude=./.git` copy into `runners/vendor/superpowers` (147 files, 14 skills, 1.3 MB).
- Offline build + self-test (Windows Docker 28.5.1): `docker build -f runners/<x>/Dockerfile --build-arg INSTALL_<X>_CLI=false …` → `--self-test` exit 0, `superpowers: 14 skills at <path> (pin f2cbfbef…)` on **both** runners.
- Ownership check: `codex:codex` / `claude:claude` (uid 1001) on the tree, symlink, and a sample `SKILL.md`.
- Negative test: `-e SUPERPOWERS_SKILLS_DIR=/nope` → `SELF-TEST FAIL: superpowers skills not found / symlink unresolved` + **exit 1** (assertion is real).
- Conformance ITs (Docker tier, `-Dgroups=docker-runner-it`): `CodexRunnerImageConformanceIT` 5/5, `ClaudeRunnerImageConformanceIT` 6/6, 0 failures.

### Completion Notes List

- **Vendor-via-COPY (AC1) chosen over clone** — exactly as the story prescribes. `node:22-slim` has no `git` and the offline `INSTALL_*_CLI=false` build has no network, yet `--self-test` (run by the conformance ITs against that offline build) must assert the skills resolve. The vendored tree is COPY'd in **both** branches; no mock needed (skill files are static content). Verified green offline.
- **Pin is a COMMIT SHA** (`f2cbfbef…` = tag `v5.1.0`), not a floating branch (T-PIN-NO-FLOAT). MIT-licensed (vendoring permitted) — noted in `runners/vendor/VENDOR.md` with a copy-paste re-vendor script.
- **Staging COPY → `mv` in RUN** — the `COPY` runs before `useradd` creates `/home/<user>`, so it stages to `/opt/deliveryline/vendor/superpowers` and the `RUN` (after `useradd`) `mv`s it into the user HOME and wires the symlink, then `chown -R`. ~2× on-disk for ~1.3 MB content (~3 MB) — negligible vs the ~1 GB budget.
- **Per-runner discovery paths (mirror, only the path differs):** Codex `~/.agents/skills/superpowers` (Codex auto-scans `~/.agents/skills/` at startup); Claude `~/.claude/skills/superpowers` (the CLI 2.1.149 **personal-skills** dir — the offline-usable filesystem target vs the online marketplace route). Both single collection symlinks → the vendored `skills/` dir, owned uid 1001.
- **AC5 active-during-runs = present + discoverable + documented limitation** (mirrors 3a-6). Skills are loadable (Codex scans at startup); whether one *fires* in a single-prompt headless run needs a live credentialed run (out of scope for the offline tier). `PROMPT_INSTRUCTION` left unchanged; no runtime `/workspace/repo` mutation, no interactive install.
- **No contract change (AC8):** no Java production / Flyway / schema / OpenAPI / `DomainErrorCode` / runner-contract change. The only Java touched is the two test-tier conformance ITs (added a `superpowers:` self-test assertion). Exit codes / mounts / bundle+result schemas / stage-mapping byte-identical — the 3 schema-variant IT runs + negative-leak assertions still pass.
- **CI `runner-image-compat` unchanged** — COPY keeps the offline root-context build green.
- **Rebuild `:latest` post-merge** (`[[runner-image-stale-causes-exit-20]]`): the hand-built `:latest` images don't auto-rebuild; a stale `:latest` won't carry the vendored skills. Restated in both READMEs.
- **Coordinate with 3a-6 (T-3A6-MERGE-ORDER):** 3a-6 is already merged (commits `357fc02`, `c1a98c4`); this change rebases cleanly on top — the self-test gains a second tool block after the openspec one, the install layer gains a COPY + symlink. No conflict.

### File List

**New (vendored + note):**
- `runners/vendor/VENDOR.md`
- `runners/vendor/superpowers/**` (147 files — obra/superpowers @ `f2cbfbef…`, tree minus `.git`)

**Modified:**
- `runners/codex/Dockerfile` (ARG/ENV `SUPERPOWERS_PIN`; `COPY` vendored tree; RUN `mv` + `~/.agents/skills` symlink + `chown`)
- `runners/claude/Dockerfile` (mirror; `~/.claude/skills` symlink)
- `runners/codex/entrypoint.sh` (self-test superpowers assertion + summary line; `SUPERPOWERS_SKILLS_DIR`/`EXPECTED_SUPERPOWERS_PIN` vars)
- `runners/claude/entrypoint.sh` (mirror)
- `runners/codex/README.md` (superpowers pin/update + activation finding + size table + `SUPERPOWERS_PIN` env row)
- `runners/claude/README.md` (mirror + Claude discovery-path finding)
- `runners/RUNNER_CONTRACT.md` (Agent-side tooling section extended for 3a-7)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java` (assert the `superpowers:` self-test line)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/ClaudeRunnerImageConformanceIT.java` (mirror)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (3a-7 `ready-for-dev → in-progress → review`)

## Change Log

| Date | Change |
|---|---|
| 2026-06-13 | bmad-create-story: created 3a-7 from sprint-change-proposal-2026-06-13. Status `backlog → ready-for-dev`. |
| 2026-06-13 | bmad-dev-story: vendored obra/superpowers @ commit `f2cbfbef…` (tag v5.1.0, MIT) via `COPY` into BOTH runner images; codex `~/.agents/skills/` + claude `~/.claude/skills/` collection symlinks (uid 1001); `--self-test` skills assertion + summary on both; `SUPERPOWERS_PIN` ARG/ENV; both READMEs + RUNNER_CONTRACT updated; both conformance ITs extended. Verified: offline build + `--self-test` (14 skills, exit 0) + negative test (exit 1) + Docker-tier ITs (Codex 5/5, Claude 6/6). No Java-production/schema/contract change. Status `ready-for-dev → review`. |
