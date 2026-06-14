# Claude Runner Image (`deliveryline/claude-runner`)

Story 3.4. A runner container that wraps the
[Claude Code CLI](https://www.npmjs.com/package/@anthropic-ai/claude-code) and conforms to the
**runner-contracts v1** file-based contract: read an input bundle, invoke Claude, produce a result
file, exit. The backend `DockerRunnerAdapter` (stories 3.1/3.2) dispatches it the same way it
dispatches the Codex runner and the deterministic mock runner — no HTTP, no socket, `--network=none`.

This image is the **Claude twin of the Codex runner** (story 3.3): it obeys the same shared
conventions. **The shared env-var, mount, context-file, result-file, exit-code, and diagnostics
contract is documented once in [`../RUNNER_CONTRACT.md`](../RUNNER_CONTRACT.md)** — read that for the
mounts, filenames, stage→`artifactType` table, exit codes, and diagnostic flags. This README covers
only what is **Claude-specific**.

> This image is **not** a long-running service. It is launched per execution by the backend with the
> workspace bind-mounts, runs once, and exits.

## Authentication — two modes (subscription-first)

The Claude credential arrives **as a container env var**, already injected by the backend
`RunnerSecretsService` at dispatch (story 3.5). The entrypoint resolves auth from the **first present**
of:

| Variable | Mode | Notes |
|----------|------|-------|
| `CLAUDE_CODE_OAUTH_TOKEN` | **Subscription (preferred)** | Billed against a Claude **Pro/Max subscription** instead of per-token API credits — the cost-saving path. Generate with `claude setup-token` (see below). |
| `ANTHROPIC_API_KEY` | API (fallback) | Per-token API billing. Read directly from env by the CLI. Get a key at <https://console.anthropic.com/settings/keys>. |

Both names are configured (and ordered, preference-first) in
`deliveryline.runner.secret-env-names.claude` — the broker injects whichever is set **under its own
name**, and `doctor` reports the Claude kind as `present` when **either** is set. The entrypoint logs
only the resolved variable **name + presence**, never the value. Unlike Codex there is **no**
`OPENAI_*` fallback.

### Generating a subscription token (`claude setup-token`)

```bash
# On a machine with the Claude Code CLI installed and logged into your Pro/Max account:
claude setup-token        # prints an OAuth token bound to your subscription
# Put it in .env (NEVER commit) as:
#   CLAUDE_CODE_OAUTH_TOKEN=<token>
```

`DELIVERYLINE_RUNNER_SKIP_AUTH=true` lets a non-real run (mock/test) proceed keyless. On a real run
with **neither** credential present, the entrypoint writes a schema-valid failure result and exits
`20`, naming only the **variable names** — never a value.

## Claude configuration (read from env — never baked into the image, AC4)

These knobs are read from env at run time and passed through to the CLI (logged by **name +
presence** only). They are **not** image constants — the model in particular is an operator/runtime
choice, defaulting to the latest capable Claude model at dispatch time.

| Variable | Purpose | Passed through as |
|----------|---------|-------------------|
| `CLAUDE_MODEL` | Model name | `ANTHROPIC_MODEL` |
| `CLAUDE_MAX_TOKENS` | Max output tokens | `CLAUDE_CODE_MAX_OUTPUT_TOKENS` |
| `CLAUDE_PROMPT_TEMPLATE` | Path to an optional prompt template (under a mounted dir) | read by the entrypoint and applied to the generated prompt; use `{{prompt}}` as the insertion marker |
| `CLAUDE_EXEC_ARGS` | CLI command-token seam (default `-p`, the CLI's non-interactive "print" mode) | shell-safe-token guarded; the precise real-Claude argument vector is finalized in story 3.8 |

### Rate limiting

Claude subscription tiers and the API both rate-limit. The runner does **not** implement retry/backoff
itself — a rate-limited run surfaces as a non-zero CLI exit (`30`) with a schema-valid failure result,
and retry/queueing is the backend's responsibility (queue + worker pool is story 3.17). For
subscription mode, concurrency is bounded by your Pro/Max plan; for API mode, by your org's API rate
limits. Keep `default-kind`/worker concurrency aligned with the active plan's limits.

## Base-image choice & layout

* **Base:** `node:22-slim` (Debian-slim). The Claude Code CLI is distributed as the npm package
  `@anthropic-ai/claude-code`, which needs Node at run time, so a Node base is the natural fit (same
  rationale as Codex).
* **Single stage (documented deviation from the "multi-stage" task hint):** the CLI is an interpreted
  npm-global package that requires the same Node runtime at run time, so a builder/runtime split
  yields no size win and risks clobbering the base image's `/usr/local/bin` `node`/`npm` symlinks.
  We keep the install lean instead (`npm cache clean --force`, `apt` lists removed).
* **Helper:** the slim base ships `node` but not `jq`, so JSON read/build is delegated to the
  dependency-free `lib/runner.mjs` (Node ESM). Its schema shape is byte-identical to the Codex
  helper — that IS the shared contract.
* **Agent-side tooling — OpenSpec (story 3a-6):** the [`openspec`](https://www.npmjs.com/package/@fission-ai/openspec)
  CLI is installed alongside Claude Code as a second agent-usable tool (mirroring the Codex runner —
  same PR). It is a **pure-JS npm-global** (`@fission-ai/openspec`, binary `openspec`) requiring
  **Node ≥ 20.19** — already satisfied by the `node:22-slim` base, so there is **no base-image
  change**. It installs in the **same npm-global layer** as the Claude Code CLI (real in production;
  a deterministic mock in the offline/`INSTALL_CLAUDE_CLI=false` build). Because OpenSpec manages
  spec files **on disk**, it needs no network/login once installed.
* **Agent-side tooling — superpowers skills (story 3a-7):** the [obra/superpowers](https://github.com/obra/superpowers)
  skills collection (14 skills) is **vendored** into the repo at a pinned commit
  (`runners/vendor/superpowers`, see `runners/vendor/VENDOR.md`) and `COPY`'d into the image — **not**
  `git clone`d — mirroring the Codex runner (same PR). Vendoring keeps it offline-safe (no `git` apt
  layer, no build-time network) in **both** the production and the `INSTALL_CLAUDE_CLI=false` build, so
  the self-test skills assertion is green without a mock. The tree lands at
  `/home/claude/.claude/superpowers`; its `skills/` dir is exposed on **Claude Code's personal-skills
  discovery dir** via the symlink `~/.claude/skills/superpowers → …/superpowers/skills` (owned
  `claude:1001`). **Per-runner placement differs from Codex only in the discovery path** (`~/.claude/skills/`
  here vs `~/.agents/skills/` there); see "superpowers skills pin" + "activation" below.
* **Non-root (AC2):** runs as `claude:1001`. uid/gid **1001** is used because `node:22-slim` already
  occupies uid/gid `1000` with its own `node` user.

### Claude Code CLI version pinning + upgrade procedure

The CLI is pinned via `ARG CLAUDE_CLI_VERSION` (currently **`2.1.149`**, the `stable` dist-tag at
authoring time — **no** floating `latest`). To upgrade:

```bash
npm view @anthropic-ai/claude-code version       # confirm the current published version + package name
npm view @anthropic-ai/claude-code dist-tags     # `stable` vs `latest`
# bump ARG CLAUDE_CLI_VERSION in runners/claude/Dockerfile (and CI --build-arg if pinned there)
docker compose build claude-runner
docker run --rm deliveryline/claude-runner:latest --self-test   # confirm the new version reports
```

> The Claude Code CLI distribution name/version can change — confirm with `npm view` at build time
> rather than trusting this doc.

### OpenSpec version pinning + upgrade procedure (story 3a-6)

The agent-side [OpenSpec CLI](https://www.npmjs.com/package/@fission-ai/openspec) is pinned via
`ARG OPENSPEC_VERSION` (currently **`1.4.1`** — **no** floating `latest`/`main`), surfaced as an
`ENV` so `--self-test` can assert it. To upgrade:

```bash
npm view @fission-ai/openspec version          # confirm the current published version + package name
# bump ARG OPENSPEC_VERSION in runners/claude/Dockerfile (mirror runners/codex/Dockerfile — same PR!)
docker compose build claude-runner
docker run --rm deliveryline/claude-runner:latest --self-test   # confirm the new openspec version reports
```

> Per the RUNNER_CONTRACT mirror rule, an OpenSpec bump (like a CLI bump) edits **both** runner
> Dockerfiles in the same PR. The scoped package name `@fission-ai/openspec` is easy to typo — confirm
> with `npm view` rather than trusting this doc.

**Offline/mock build:** the conformance-test build (`--build-arg INSTALL_CLAUDE_CLI=false`, used by
`ClaudeRunnerImageConformanceIT` and the CI `runner-image-compat` line) bakes a deterministic
`test/mock-openspec.sh` to `/usr/local/bin/openspec` (mirroring `mock-claude.sh`). It answers
`openspec --version` with the pin and reads no env other than `OPENSPEC_VERSION`, so the self-test's
openspec assertion is green offline with **no** network `npm install`.

> **Rebuild `:latest` after merge (done-criteria caveat).** The hand-built `:latest` runner images do
> **not** auto-rebuild on merge. A run on a stale `:latest` won't have `openspec` and would surface as
> missing tooling — rebuild (`docker compose build claude-runner`) and re-run `--self-test` after this
> change lands.

### OpenSpec activation during runs — finding (story 3a-6 AC4)

OpenSpec is **present + CLI-invokable**: the agent inside the container can shell out to `openspec …`
during a stage. **Slash-command auto-activation (`/opsx:*`) is NOT wired** in the single-prompt
headless invocations (`claude -p`). OpenSpec's interactive UX is driven by slash commands plus an
`AGENTS.md` written by `openspec init`; a single hardcoded prompt types no slash command, and the
entrypoint deliberately does **not** run `openspec init` at runtime (it is interactive and would
mutate the read-only spec/plan working tree, polluting pr-output diffs). A live headless-activation
spike needs egress + a credential (out of scope for the offline tier); reasoning from the OpenSpec
docs, auto-firing cannot be confirmed in single-prompt headless mode, so this image ships the
**minimum** (present + invokable) and the entrypoint prompt is left unchanged. Wiring a minimal,
non-mutating prompt nudge is deferred to a future story once headless activation is confirmed.

> **Superseded under the 3a-8 opt-in flag.** When `DELIVERYLINE_RUNNER_OPENSPEC=true` (default OFF),
> the entrypoint *does* augment the prompt with a fence-emit delta and assembles a change folder at
> pr-output — see the next section. The "prompt left unchanged" stance above remains the **flag-off
> default**.

### OpenSpec spec-driven authoring during runs (story 3a-8)

Gated entirely on `DELIVERYLINE_RUNNER_OPENSPEC=true` (the backend injects it only when
`deliveryline.runner.openspec.enabled=true`, default OFF). The shared behavior, stage→artifact mapping,
the `=== FILE: <relpath> ===` fence convention, additive-never-blocks discipline, and the
no-schema-change guarantee are documented once in
[`../RUNNER_CONTRACT.md`](../RUNNER_CONTRACT.md#openspec-spec-driven-authoring-story-3a-8--opt-in-default-off).
Claude-specific notes:

- Claude reads its prompt from the generated prompt file (the `CLAUDE_PROMPT_TEMPLATE` seam still
  applies); the OpenSpec delta is **appended to that prompt file** (`>>`) rather than to a
  `PROMPT_INSTRUCTION` var (Claude has no Codex-style sandbox/instruction flag — read-only is enforced
  by the prompt). The read-only stages emit their change files **to stdout only** and never touch
  `/workspace/repo`.
- At pr-output the entrypoint lays down `openspec/AGENTS.md` + `openspec/changes/<id>/` itself (no
  interactive `openspec init`, per the 3a-6 finding), splits the carried fenced files via
  `runner.mjs split-fenced`, runs best-effort `openspec validate`, and appends the tasks.md authoring
  instruction to the prompt; the Claude pr-output commit picks the folder up.
- The change-id is derived from the ticket ref + a short workspace-run-id slug; `runner.mjs prepare`
  surfaces `DL_TICKET_REF` and the per-stage reference paths for this.
- **Flag OFF ⇒ byte-identical**: no prompt delta, no files, no `openspec` call. The conformance IT
  pins both the flag-on assembly and the flag-off byte-identity.

### superpowers skills pin + update procedure (story 3a-7)

The agent-side [obra/superpowers](https://github.com/obra/superpowers) skills are **vendored** (the pin
is a **commit SHA**, not a CLI version) into `runners/vendor/superpowers` and surfaced as
`ARG`/`ENV SUPERPOWERS_PIN` (currently **`f2cbfbef…`** = tag `v5.1.0`, MIT — **no** floating `main`) so
`--self-test` reports it. To re-vendor / bump (full steps + a script in
[`runners/vendor/VENDOR.md`](../vendor/VENDOR.md)):

```bash
git ls-remote https://github.com/obra/superpowers.git refs/tags/<tag>   # resolve tag -> commit
# re-vendor runners/vendor/superpowers @ the new COMMIT sha (see VENDOR.md), then:
# bump ARG SUPERPOWERS_PIN in runners/claude/Dockerfile (mirror runners/codex/Dockerfile — same PR!)
docker compose build claude-runner
docker run --rm deliveryline/claude-runner:latest --self-test    # confirm the new pin + skill count report
```

> Per the RUNNER_CONTRACT mirror rule, a superpowers re-vendor edits **both** runner Dockerfiles in the
> same PR. Keep the pin a **commit SHA**, never a branch.

**Claude discovery path (confirmed in dev — story 3a-7 Task 0):** the marketplace/plugin install route
(`/plugin install`) is interactive + online — unusable in the offline image. The offline filesystem
target is Claude Code's **personal-skills dir** `~/.claude/skills/<skill>/SKILL.md` (confirmed against
CLI `2.1.149`), **not** the plugin dir. The image symlinks `~/.claude/skills/superpowers → the vendored
skills/ dir` (mirroring the Codex collection symlink); full plugin machinery (hooks / SessionStart
injection) is **out of scope**.

**Offline/mock build:** superpowers needs **no** mock — the skills are static files vendored via `COPY`,
present in **both** the production and the `INSTALL_CLAUDE_CLI=false` build. The self-test's skills
assertion (`~/.claude/skills/superpowers` resolves + ≥1 `SKILL.md`) is green offline with no network,
exactly as the conformance IT + CI `runner-image-compat` line exercise it.

> **Rebuild `:latest` after merge.** The hand-built `:latest` images do **not** auto-rebuild on merge
> (`[[runner-image-stale-causes-exit-20]]`); a stale `:latest` won't carry the vendored skills — rebuild
> (`docker compose build claude-runner`) and re-run `--self-test` after this change lands.

### superpowers activation during runs — finding (story 3a-7 AC5)

The skills are **present + discoverable** on the personal-skills dir above. Whether a skill **fires** in
a **single-prompt headless** run (`claude -p`, no human typing, no slash command) is the same open
question as 3a-6's OpenSpec: superpowers skills activate by **name-mention / task-description match**,
and confirming auto-firing (and that Claude's scanner descends into the symlinked collection) needs a
live run (egress + a credential) the offline tier does not have. So this image ships the **minimum**
(present + discoverable) and leaves the entrypoint `PROMPT_INSTRUCTION` **unchanged** — **no runtime
mutation of `/workspace/repo`, no interactive install** (Trap T-NO-RUNTIME-MUTATION). A minimal,
non-mutating prompt nudge is deferred to a future story once headless activation is confirmed under
real execution (story 3.8).

### Image size / layer count (AC9)

Production image (`INSTALL_CLAUDE_CLI=true`, real CLIs): **≈ 167 MB, 12 layers** (≈ 166 MB before
story 3a-7, + ~1.3 MB for the vendored superpowers tree — markdown + scripts — + one `COPY` layer,
11 → 12; baseline measured on WSL2 Ubuntu, `docker` 28.5.1, `node:22-slim`). This is **well under** the
~1 GB budget and still *smaller* than the Codex runner (≈ 671 MB) — the `@anthropic-ai/claude-code` npm
package is lighter than the Codex CLI's bundled native binary, and superpowers adds the same negligible
markdown delta to both. No size justification required. The conformance-test image (both mocks + the
same vendored skills, `INSTALL_CLAUDE_CLI=false`) is ~80 MB.

```bash
docker build -f runners/claude/Dockerfile -t deliveryline/claude-runner:latest .
docker image inspect deliveryline/claude-runner:latest --format 'size={{.Size}} layers={{len .RootFS.Layers}}'
```

> Docker builds differ Windows vs the Linux CI runner — measure on WSL2 Ubuntu (the baseline figures
> were measured there). The ~19 MB OpenSpec delta was measured via `npm install -g
> @fission-ai/openspec@1.4.1` on `node:22-slim`. See "CI parity" in the story Dev Notes.

## Testing the image locally

```bash
# Build (production CLI):
docker compose build claude-runner
# or:  docker build -f runners/claude/Dockerfile -t deliveryline/claude-runner:latest .

# Smoke test (doctor / CI — story 1.16): exits 0, prints a health summary, no network:
docker run --rm deliveryline/claude-runner:latest --self-test

# Build the conformance-test image with the deterministic MOCK CLI (no network, no API):
docker build -f runners/claude/Dockerfile --build-arg INSTALL_CLAUDE_CLI=false \
  -t deliveryline/claude-runner:it-test .

# Run a stage against the valid fixture (mock CLI), mirroring the backend dispatch:
work=$(mktemp -d); mkdir -p "$work"/{input,output,logs}; chmod 0777 "$work"/{output,logs}
cp deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json \
   "$work/input/context-bundle.v1.json"
docker run --rm --network=none \
  -v "$work/input:/workspace/input:ro" \
  -v "$work/output:/workspace/output" \
  -v "$work/logs:/workspace/logs" \
  -e ANTHROPIC_API_KEY=sk-local-test \
  deliveryline/claude-runner:it-test --stage=spec-investigation
cat "$work/output/runner-result.v1.json"
```

The automated conformance test is `ClaudeRunnerImageConformanceIT`
(`deliveryline-backend`, `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`). Run it via the
Docker-tier:

```bash
mvn -pl deliveryline-backend test -Dtest=ClaudeRunnerImageConformanceIT \
    -DexcludedGroups= -Dgroups=docker-runner-it
```

It is excluded from the no-Docker PR tier. Real Claude-API execution and the single-test
Codex-vs-Claude **parity** assertion are story 3.8.

## Backend integration notes (story 3.8)

* **Stage injection:** the backend encodes the stage as the `deliveryline.stage` label today, which is
  not readable inside the container. To drive `artifactType` in production, the backend must pass the
  stage into the container interior via `DELIVERYLINE_RUNNER_STAGE` (this entrypoint already honors
  it). Wiring that env is a backend change owned by story 3.8 — out of scope here (the adapter is
  DO-NOT-EDIT in this story).
* **Mount writability:** the image runs as the non-root user `claude:1001`. The backend-created
  workspace dirs (`LocalRunnerWorkspaceStore` makes them `0700`, owned by the JVM user) must be
  writable by that uid in production (e.g. a matching `--user`, or group/world-writable output/logs).
  The conformance test creates its rw mounts world-writable to satisfy this on native Linux.
* **Real invocation (story 3.8):** the entrypoint invokes `claude <subcommand>
  --dangerously-skip-permissions`, prompt on stdin, with `/workspace/repo` as the cwd when the repo
  mount is present (Claude Code has no `-C` flag — the cwd is the project root). `-p` (print) is the
  default headless subcommand (`CLAUDE_EXEC_ARGS` overrides it); `--dangerously-skip-permissions`
  clears the tool-permission / folder-trust prompts a headless run cannot answer — sound here because
  the *container* is the sandbox (non-root `claude` user, backend network policy, read-only input),
  and Claude Code allows the flag for non-root users. The model comes from `ANTHROPIC_MODEL` (set
  from `CLAUDE_MODEL`). Real runs require egress — see `deliveryline.runner.docker.network-mode`
  (default `none`; set `bridge` for real Claude).
* **CI obligation (AC10):** the "build both runner images + `--self-test` on every PR touching the
  runner-contracts schemas" CI check is owned by stories **3.28 / 3.34** (deferred). This story ships
  the documented obligation (`../RUNNER_CONTRACT.md`) + the conformance IT the CI tier will invoke.
