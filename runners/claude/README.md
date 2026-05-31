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

### Image size / layer count (AC9)

Production image (`INSTALL_CLAUDE_CLI=true`, real CLI): **~147 MB, 10 layers** (measured on WSL2
Ubuntu, `docker` 28.5.1, `node:22-slim`). This is **well under** the ~1 GB budget and actually
*smaller* than the Codex runner (~652 MB) — the `@anthropic-ai/claude-code` npm package is lighter
than the Codex CLI's bundled native binary. No size justification required. The conformance-test image
(mock CLI, `INSTALL_CLAUDE_CLI=false`) is smaller still.

```bash
docker build -f runners/claude/Dockerfile -t deliveryline/claude-runner:latest .
docker image inspect deliveryline/claude-runner:latest --format 'size={{.Size}} layers={{len .RootFS.Layers}}'
```

> Docker builds differ Windows vs the Linux CI runner — measure on WSL2 Ubuntu (the figures above
> were measured there). See "CI parity" in the story Dev Notes.

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
* **Real invocation:** the precise real-Claude argument vector is fed via the documented
  `CLAUDE_EXEC_ARGS` seam (default `-p`, prompt on stdin) and finalized in story 3.8.
* **CI obligation (AC10):** the "build both runner images + `--self-test` on every PR touching the
  runner-contracts schemas" CI check is owned by stories **3.28 / 3.34** (deferred). This story ships
  the documented obligation (`../RUNNER_CONTRACT.md`) + the conformance IT the CI tier will invoke.
