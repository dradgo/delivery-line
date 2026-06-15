# Sprint Change Proposal — Runner-Image Agent Tooling (OpenSpec + Superpowers)

- **Date:** 2026-06-13
- **Author:** Alex (via Correct Course workflow)
- **Trigger type:** New capability request (additive enhancement)
- **Affected epic:** Epic 3a — Real Agent + Repo Stack (active slice)
- **Proposed stories:** `3a-6` (OpenSpec), `3a-7` (obra/superpowers)
- **Change scope classification:** Moderate (backlog reorganization — two new stories — then direct implementation)

---

## Section 1 — Issue Summary

The two production runner images — `deliveryline/codex-runner` (story 3.3) and
`deliveryline/claude-runner` (story 3.4) — wrap the Codex CLI and Claude Code CLI and invoke
them headlessly per stage. Today they ship the agent CLI and nothing else. We want the agents
running **inside** those containers to have two additional capabilities available during a run:

1. **OpenSpec** — a (largely tool-agnostic) spec-driven workflow tool + `openspec` CLI.
2. **obra/superpowers** — a skills collection that loads natively in **both** Claude Code
   (`~/.claude` plugin/skills) **and** Codex (`~/.codex/superpowers` cloned + symlinked into
   `~/.agents/skills/`; Codex scans `~/.agents/skills/` at startup).

**Discovery context:** raised by Alex as a deliberate enhancement; Alex has already installed
superpowers manually into a local Codex and confirmed the native Codex install path
(<https://obra-superpowers.mintlify.app/installation/codex>). Neither tool is referenced
anywhere in the repository today — this is net-new.

**Decisions already taken (this session):**
- Structure the work as **two tickets, split by tool** (each spanning both images).
- Intended runtime: the tools must be **active during the autonomous headless runs**, not merely
  present on disk.

---

## Section 2 — Impact Analysis

### Epic Impact
- **Epic 3a stays valid and unchanged in intent.** This is purely additive — two new stories
  appended to the active 3a slice. No epic is redefined, reordered, or removed.
- Highest existing 3a story is **3a-5**; next free IDs are **3a-6** and **3a-7**.

### Story Impact
- **No existing story is modified.** Stories 3.3 / 3.4 (the image definitions) are the conceptual
  parents; the new work extends them rather than editing their acceptance criteria.
- Two new stories are added (see Section 4).

### Artifact Conflicts
| Artifact | Impact |
|----------|--------|
| **PRD** | None. Runner-image tooling is below PRD altitude. **N/A.** |
| **Architecture** | None structural — containers, the file-based runner-contracts v1 boundary, `--network=none` default, and least-privilege posture are unchanged. Optional one-line note that the images now carry agent-side tooling. **Mostly N/A.** |
| **UX Design** | None. **N/A.** |
| **CI / build** | `runner-image-compat` line (root build context, mock CLI) must still build both images. Hand-built `:latest` images must be **rebuilt** after merge or runs use the stale image (see Risks). |
| **Docs** | `runners/codex/README.md`, `runners/claude/README.md`, and the shared `runners/RUNNER_CONTRACT.md` need version-pin + self-test updates. |

### Technical Impact & Constraints (the decisive ones)
1. **`--network=none` on the conformance tier ⇒ bake at build time.** No runtime `npm install`,
   `git clone`, or plugin-marketplace fetch is available inside the container. Real runs may use
   `bridge`, but the offline conformance/self-test tier cannot — so both tools install during
   `docker build`.
2. **Two distinct install mechanisms.** OpenSpec = `npm install -g` (mirrors the existing CLI
   layer). Superpowers = `git clone` + symlink into the runtime user's HOME skills dir →
   **requires `git`**, which `node:22-slim` does **not** ship (add an apt layer or vendor/COPY).
3. **Non-root HOME placement.** Both images run as an unprivileged user (`codex:1001`,
   `claude:1001`). Superpowers must land under that user's HOME (`/home/codex`, `/home/claude`)
   with correct ownership.
4. **Headless activation is the real risk, not installation.** The entrypoints invoke
   `codex exec` / `claude -p` with a **hardcoded operating-mode prompt**. Per the superpowers
   docs, skills activate by name-mention / task-description match; OpenSpec's value leans on
   slash-commands / project instructions. Whether either **auto-fires** in a non-interactive
   single-prompt run is **unverified** and must be proven (or the limitation documented) — this is
   what makes "active during runs" bigger than "installed."
5. **Reproducibility convention.** The repo pins CLI versions strictly (no floating `latest`).
   Both new tools must be pinned (OpenSpec npm version; superpowers commit SHA/tag — no floating
   `main`).

---

## Section 3 — Recommended Approach

**Direct Adjustment (Option 1), split by tool.** Add two new stories to the active 3a slice:

- **3a-6 — OpenSpec CLI in both runner images**
- **3a-7 — obra/superpowers skills in both runner images**

**Rationale**
- The two tools are **independent capabilities with different install paths, prerequisites,
  self-test assertions, and risk profiles** (OpenSpec = trivial npm add; superpowers = git layer +
  HOME placement + activation spike). Splitting by tool keeps each independently testable and
  revertable, and lets OpenSpec ship even if superpowers headless-activation proves flaky.
- **Splitting by tool (not by image)** preserves the repo's standing convention that the two
  runners are a mirrored pair changed in the **same PR** (RUNNER_CONTRACT change rule; "mirror, do
  not re-derive"). Each tool's PR edits both Dockerfiles once.
- Rollback (Option 2) and MVP review (Option 3) are **N/A** — the change is additive and below MVP
  altitude.

**Effort:** Low–Med per story. **Risk:** Low–Med, concentrated in the headless-activation
verification (mitigated by an explicit verification AC + a documented fallback to
"installed + invokable").

**Sequencing:** 3a-6 (OpenSpec) first — lower risk, establishes the "enrich both images" pattern —
then 3a-7 (superpowers). They share no code dependency and could run in parallel, but serial keeps
review surfaces clean.

---

## Section 4 — Detailed Change Proposals

> These are **story definitions** (to be turned into full story files via `bmad-create-story`),
> plus the `sprint-status.yaml` additions. ACs are a sketch to scope the work, not the final
> contract.

### Story 3a-6 — OpenSpec CLI in both runner images

**As** the platform, **I want** the `openspec` CLI baked into both runner images and available to
the agent during a stage run, **so that** runs can use OpenSpec's spec-driven workflow.

Sketch ACs:
1. Both Dockerfiles install OpenSpec **pinned** (`ARG OPENSPEC_VERSION`, no floating `latest`;
   confirm package name + version via `npm view`), in the agent-CLI install layer, gated so the
   **mock/offline conformance image stays buildable offline** (reuse the existing
   `INSTALL_*_CLI` gate or add an `INSTALL_OPENSPEC` arg).
2. `--self-test` asserts `openspec --version` resolves to the pinned version (both runners).
3. **Active-during-runs:** the entrypoint makes OpenSpec usable by the headless agent (e.g.
   initialize/surface it in the working repo, or reference it from the operating-mode prompt).
   **Verify** it activates under `codex exec` / `claude -p`; if it cannot in single-prompt headless
   mode, document the limitation and fall back to "present + CLI-invokable."
4. Image stays within the ~1 GB budget; README size/layer table updated.
5. `CodexRunnerImageConformanceIT` + Claude twin still pass; `runner-image-compat` CI line still
   builds (root context, mock CLI).
6. Both runners changed in the **same PR**; READMEs + RUNNER_CONTRACT note updated; version-pin
   upgrade procedure documented.

### Story 3a-7 — obra/superpowers skills in both runner images

**As** the platform, **I want** obra/superpowers skills baked into both images and discoverable by
the agent at startup, **so that** runs can use superpowers skills.

Sketch ACs:
1. Provide `git` in both images (apt layer) **or** vendor the repo via `COPY` to avoid a build-time
   network dependency; pin to a **specific commit/tag** (no floating `main`).
2. **Codex:** clone to `/home/codex/.codex/superpowers` and symlink
   `/home/codex/.agents/skills/superpowers` → `…/superpowers/skills`, owned by `codex:1001`;
   optionally set `[features] multi_agent = true` in `~/.codex/config.toml`.
3. **Claude:** install superpowers under the Claude discovery path in `/home/claude/.claude/…`
   (confirm plugin vs skills-dir mechanism; mirror the codex approach), owned by `claude:1001`.
4. `--self-test` asserts the skills dir + symlink resolve (both runners).
5. **Active-during-runs:** verify skills are **discovered and can activate** in headless
   `codex exec` / `claude -p` (docs say activation is by name-mention / task-match → the
   operating-mode prompt may need a nudge). If headless activation can't be confirmed, document the
   limitation.
6. Pin a superpowers version (commit SHA/tag); document the update procedure (git pull / re-bump).
7. Image stays within the ~1 GB budget; READMEs updated; conformance ITs + `runner-image-compat`
   still pass; both runners in the **same PR**.

### `sprint-status.yaml` additions (under `# ---- Epic 3a … (active slice) ----`)

```yaml
  3a-6-openspec-cli-in-runner-images: backlog       # 2026-06-13 sprint-change-proposal — OpenSpec in codex+claude images
  3a-7-superpowers-skills-in-runner-images: backlog # 2026-06-13 sprint-change-proposal — obra/superpowers in codex+claude images
```

---

## Section 5 — Implementation Handoff

- **Scope classification: Moderate.** No PRD/architecture/UX conflict, but it adds two stories to
  the sprint plan (backlog reorganization) before implementation.
- **Handoff:**
  1. **PO/Dev (`bmad-create-story`)** — create the `3a-6` and `3a-7` story files from the
     definitions above; add the two `sprint-status.yaml` entries.
  2. **Dev (`bmad-dev-story`)** — implement 3a-6, then 3a-7. Each PR edits **both** Dockerfiles
     (mirror rule), updates `--self-test` + READMEs, keeps the offline/mock conformance build
     green, and verifies (or documents the limitation on) headless activation.
- **Success criteria:** both images build (production + mock/offline); `--self-test` reports the
  new tooling on both runners; conformance ITs + `runner-image-compat` pass; the `:latest` images
  are **rebuilt** post-merge; headless activation is verified or its limitation documented.

---

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| **Headless activation may not auto-fire** (biggest unknown) | Explicit verification AC + documented fallback to "installed + invokable"; a short spike up front in 3a-6. |
| **Stale hand-built `:latest`** — runs use the old image (exit 20 / missing tooling) | Add a "rebuild `:latest`" step to each story's done criteria; note in README. |
| **Offline conformance build breaks** (network-needing install) | Gate tool installs behind the `INSTALL_*` arg, or vendor via `COPY`. |
| **`git` apt layer** adds size + supply-chain surface (3a-7) | Pin commit/tag; consider vendoring instead of cloning; keep within the ~1 GB budget. |
| **Reproducibility drift** (floating versions) | Pin OpenSpec npm version + superpowers commit/tag; document upgrade procedure like the CLI pins. |
| **Non-root HOME ownership** | Clone/symlink under the runtime user's HOME with `chown` to uid 1001. |
