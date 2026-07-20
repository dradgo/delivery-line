# Codex Runner Image (`deliveryline/codex-runner`)

Story 3.3. A runner container that wraps the [Codex CLI](https://www.npmjs.com/package/@openai/codex)
and conforms to the **runner-contracts v1** file-based contract: read an input bundle, invoke Codex,
produce a result file, exit. The backend `DockerRunnerAdapter` (stories 3.1/3.2) dispatches it the
same way it dispatches the deterministic mock runner — no HTTP, no socket, `--network=none`.

> This image is **not** a long-running service. It is launched per execution by the backend with the
> workspace bind-mounts, runs once, and exits.

> The env-var, mount, context-file, result-file, exit-code, and diagnostics contract shared with the
> Claude runner is documented once in [`../RUNNER_CONTRACT.md`](../RUNNER_CONTRACT.md). A schema change
> must update **both** runners in the same PR (see that doc's change rule). The sections below repeat
> the Codex-relevant parts for convenience.

## File-based contract (the only runner ↔ backend boundary)

| Mount | Mode | Contents |
|-------|------|----------|
| `/workspace/input`  | read-only  | `context-bundle.v1.json` (the input bundle the backend writes) |
| `/workspace/output` | read-write | `runner-result.v1.json` (the result the entrypoint MUST write) |
| `/workspace/logs`   | read-write | `runner.stdout`, `runner.stderr` (raw Codex CLI output) |

The entrypoint **never** writes outside `/workspace/output` and `/workspace/logs`; `/workspace/input`
is mounted read-only by the backend.

> **Filenames are the live backend contract**, verified against
> `LocalRunnerWorkspaceStore` (`CONTEXT_BUNDLE_FILENAME = context-bundle.v1.json`,
> `RUNNER_RESULT_FILENAME = runner-result.v1.json`). The adapter classifies a **missing result file
> as `RUNNER_CRASH` regardless of exit code**, so the entrypoint always writes the result file before
> exiting `0`. (The story prose referenced `context-bundle.json` / `runner-result.json` without the
> `.v1` infix — the code is authoritative; this image follows the code.)

## Stage → `artifactType`

The runner emits the correct `artifactType` for the stage it is running (AC3). Because the frozen
`context-bundle.v1` schema is `additionalProperties: false` (it cannot carry a `stage` field) and the
Docker `deliveryline.stage` label is container metadata that is **not readable from inside the
container**, the stage is supplied to the entrypoint via — in priority order:

1. `--stage=<token>` CLI argument, **or**
2. `DELIVERYLINE_RUNNER_STAGE` environment variable (the production injection seam — see "Backend
   integration notes"), **or**
3. an optional `stage` field in the bundle, if a future schema revision adds one (forward-compatible).

Accepted tokens (the three story-named artifact stages plus the `RunnerStage` enum aliases):

| Stage token | `artifactType` |
|-------------|----------------|
| `spec-investigation`, `investigation`, `spec` | `spec` |
| `implementation-plan`, `plan`, `implementationPlan` | `implementationPlan` |
| `pr-output`, `execution`, `prOutput` | `prOutput` |

## Environment variables consumed

| Variable | Required | Purpose |
|----------|----------|---------|
| `CODEX_AUTH_JSON` | one of these for a real run | **Subscription auth** (story 3a-3, PREFERRED) — the raw single-line content of `$CODEX_HOME/auth.json` from a ChatGPT/Codex Pro subscription. Materialized to `$CODEX_HOME/auth.json` (mode `0600`) before Codex runs. Value is **never** logged. See "Subscription authentication" below. |
| `CODEX_API_KEY` | fallback | Agent-provider API key (story 3.5 contract). Used when `CODEX_AUTH_JSON` is absent. Value is **never** logged. |
| `OPENAI_API_KEY` | fallback for the above | Used if both `CODEX_AUTH_JSON` and `CODEX_API_KEY` are unset. |
| `CODEX_HOME` | no | Codex home dir where the subscription `auth.json` is materialized (default `$HOME/.codex`). |
| `CODEX_BASE_URL` | no | Optional API base-URL override (exported to the CLI as `OPENAI_BASE_URL`). API mode only. |
| `DELIVERYLINE_RUNNER_STAGE` | no | Stage when `--stage` is not passed. |
| `DELIVERYLINE_CORRELATION_ID` | no | Correlation id prepended to log lines (story 3.11 traceability). |
| `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE` | no | Must equal `true` to enable `--simulate-failure` (off in production). |
| `DELIVERYLINE_RUNNER_SKIP_AUTH` | no | `true` lets a non-real run proceed without a key (mock/test only). |
| `DELIVERYLINE_RUNNER_OPENSPEC` | no | `true` opts into the OpenSpec spec-driven authoring layer (story 3a-8; see "OpenSpec spec-driven authoring during runs" below). Absent/false ⇒ byte-identical legacy path. Injected by the backend only when `deliveryline.runner.openspec.enabled=true` (default OFF). |
| `CODEX_CLI_VERSION` | baked | The pinned Codex CLI version, reported by `--self-test`. |
| `OPENSPEC_VERSION` | baked | The pinned OpenSpec CLI version (story 3a-6), asserted + reported by `--self-test`. |
| `SUPERPOWERS_PIN` | baked | The vendored obra/superpowers commit SHA (story 3a-7), reported by `--self-test`. |

Secrets arrive **as container env** (already injected by the backend `RunnerSecretsService` →
`CreateContainerSpec.environment`). In **API mode** the entrypoint reads the variable, exports
`OPENAI_API_KEY` for the CLI, and logs only the **variable name + presence** — never the value. In
**subscription mode** it materializes `CODEX_AUTH_JSON` into `$CODEX_HOME/auth.json` and does **not**
export an API key (see below). The backend redaction layer (story 3.6) is a backstop, not a license
to leak.

## Subscription authentication (story 3a-3)

Codex can authenticate against a **ChatGPT/Codex Pro subscription** (the cost-saving path) instead of
per-token API billing. Codex keeps subscription credentials in a **file** — `$CODEX_HOME/auth.json`
(default `~/.codex/auth.json`) — not an env var, so the runner *materializes* the file from an
injected secret:

1. **Generate `auth.json` on a trusted host.** Run `codex login` (Codex CLI) and complete the ChatGPT
   sign-in; it writes `~/.codex/auth.json` (OAuth access + refresh tokens and account id).
2. **Minify it into `.env`.** Put the file's content on a **single line** of JSON as `CODEX_AUTH_JSON`
   in your `.env` (see `.env.example`). It is a secret — never commit it.
3. **Precedence is subscription-first.** `deliveryline.runner.secret-env-names.codex` is
   `[CODEX_AUTH_JSON, CODEX_API_KEY, OPENAI_API_KEY]`; `RunnerSecretsService` resolves the first
   present, so when `CODEX_AUTH_JSON` is set it wins over an API key. The container then receives
   `CODEX_AUTH_JSON` as env (injected under its own name).
4. **Materialization.** The entrypoint detects the resolved credential is `CODEX_AUTH_JSON`, sets and
   exports `CODEX_HOME` (default `$HOME/.codex`), and runs `runner.mjs materialize-auth --out
   "$CODEX_HOME/auth.json"`, which validates the value is a non-empty JSON object and writes it
   **atomically** with mode **`0600`**. The value is **never** printed; a malformed/empty value
   writes a schema-valid failure result and exits **`21`**. The `cleanup()` trap removes the file on
   exit (the container is ephemeral regardless). No `OPENAI_API_KEY` is exported in this mode.

> **`--network=none` note:** the contract/self-test/conformance tier has no network, so subscription
> **token refresh** (which needs egress) is **out of scope here** and deferred to **story 3.8** under
> real execution. This story materializes exactly what the operator supplies; an expired token
> surfaces as a normal Codex non-zero-exit failure.

## Exit codes

| Code | Meaning |
|------|---------|
| `0`  | Success — `runner-result.v1.json` written (or self-test / simulated contract-violation). |
| `1`  | `--self-test` failed (node / helper / Codex CLI missing). |
| `2`  | Usage error, or `--simulate-failure` requested without the test-only env gate. |
| `10` | Input bundle not found at `/workspace/input/context-bundle.v1.json`. |
| `11` | Input bundle is not readable / not valid JSON. |
| `12` | Unsupported `schemaVersion` (this image speaks v1 only). |
| `13` | No stage resolved, or an unknown stage token. |
| `20` | No agent-provider credential present for a real run (`CODEX_AUTH_JSON` / `CODEX_API_KEY` / `OPENAI_API_KEY`). |
| `21` | Subscription `CODEX_AUTH_JSON` is present but malformed/empty (not a non-empty JSON object) — a schema-valid failure result is written (story 3a-3). |
| `30` | The Codex CLI exited non-zero (see `runner.stderr`). No result file written. |
| `40` | Failed to build/write `runner-result.v1.json`. |
| `50` | `--simulate-failure=crash` — intentional non-zero exit with **no** result file. |

> `--simulate-failure=timeout` sleeps indefinitely (the backend timeout path kills it);
> `--simulate-failure=contract_violation` writes a schema-invalid result and exits `0` so the
> backend's `RunnerContractValidator` classifies it as `runner_contract_violation`.

## Base-image choice & layout

* **Base:** `node:22-slim` (Debian-slim). The Codex CLI is distributed as the npm package
  `@openai/codex`, which needs Node at run time, so a Node base is the natural fit.
* **Single stage (documented deviation from the "multi-stage" task hint):** the CLI is an interpreted
  npm-global package that requires the same Node runtime at run time, so a builder/runtime split
  yields no size win and risks clobbering the base image's `/usr/local/bin` `node`/`npm` symlinks.
  We keep the install lean instead (`npm cache clean --force`, `apt` lists removed).
* **Helper:** the slim base ships `node` but not `jq`, so JSON read/build is delegated to the
  dependency-free `lib/runner.mjs` (Node ESM).
* **Agent-side tooling — OpenSpec (story 3a-6):** the [`openspec`](https://www.npmjs.com/package/@fission-ai/openspec)
  CLI is installed alongside Codex as a second agent-usable tool. It is a **pure-JS npm-global**
  (`@fission-ai/openspec`, binary `openspec`) requiring **Node ≥ 20.19** — already satisfied by the
  `node:22-slim` base, so there is **no base-image change**. It installs in the **same npm-global
  layer** as the Codex CLI (real in production; a deterministic mock in the offline/`INSTALL_CODEX_CLI=false`
  build — see below). Because OpenSpec manages spec files **on disk**, it needs no network/login to
  run once installed (unlike Codex's subscription token refresh).
* **Agent-side tooling — superpowers skills (story 3a-7):** the [obra/superpowers](https://github.com/obra/superpowers)
  skills collection (TDD, debugging, brainstorming, … — 14 skills) is **vendored** into the repo at a
  pinned commit (`runners/vendor/superpowers`, see `runners/vendor/VENDOR.md`) and `COPY`'d into the
  image — **not** `git clone`d. Vendoring keeps the install offline-safe (no `git` apt layer, no
  build-time network) in **both** the production and the `INSTALL_CODEX_CLI=false` build, so the
  self-test skills assertion is green without a mock (skill files are static content). The tree lands
  at `/home/codex/.codex/superpowers` and its `skills/` dir is exposed on Codex's auto-discovery path
  via the symlink `~/.agents/skills/superpowers → …/superpowers/skills` (owned `codex:1001`). Codex
  scans `~/.agents/skills/` at startup. See "superpowers skills pin" + "superpowers activation" below.
* **Non-root (AC7):** runs as `codex:1001`. uid/gid **1001** is used because `node:22-slim` already
  occupies uid/gid `1000` with its own `node` user.

### Codex CLI version pinning + upgrade procedure

The CLI is pinned via `ARG CODEX_CLI_VERSION` (currently **`0.135.0`** — **no** floating `latest`).
To upgrade:

```bash
npm view @openai/codex version          # confirm the current published version + package name
# bump ARG CODEX_CLI_VERSION in runners/codex/Dockerfile (and CI --build-arg if pinned there)
docker compose build codex-runner
docker run --rm deliveryline/codex-runner:latest --self-test   # confirm the new version reports
```

> The Codex CLI distribution name/version can change — confirm with `npm view` at build time rather
> than trusting this doc.

### OpenSpec version pinning + upgrade procedure (story 3a-6)

The agent-side [OpenSpec CLI](https://www.npmjs.com/package/@fission-ai/openspec) is pinned via
`ARG OPENSPEC_VERSION` (currently **`1.4.1`** — **no** floating `latest`/`main`), surfaced as an
`ENV` so `--self-test` can assert it. To upgrade:

```bash
npm view @fission-ai/openspec version          # confirm the current published version + package name
# bump ARG OPENSPEC_VERSION in runners/codex/Dockerfile (mirror runners/claude/Dockerfile — same PR!)
docker compose build codex-runner
docker run --rm deliveryline/codex-runner:latest --self-test   # confirm the new openspec version reports
```

> Per the RUNNER_CONTRACT mirror rule, an OpenSpec bump (like a CLI bump) edits **both** runner
> Dockerfiles in the same PR. The scoped package name `@fission-ai/openspec` is easy to typo — confirm
> with `npm view` rather than trusting this doc.

**Offline/mock build:** the conformance-test build (`--build-arg INSTALL_CODEX_CLI=false`, used by
`CodexRunnerImageConformanceIT` and the CI `runner-image-compat` line) bakes a deterministic
`test/mock-openspec.sh` to `/usr/local/bin/openspec` (mirroring `mock-codex.sh`). It answers
`openspec --version` with the pin and reads no env other than `OPENSPEC_VERSION`, so the self-test's
openspec assertion is green offline with **no** network `npm install`.

> **Rebuild `:latest` after merge (done-criteria caveat).** The hand-built `:latest` runner images do
> **not** auto-rebuild on merge. A run on a stale `:latest` won't have `openspec` and would surface as
> missing tooling — rebuild (`docker compose build codex-runner`) and re-run `--self-test` after this
> change lands.

### OpenSpec activation during runs — finding (story 3a-6 AC4)

OpenSpec is **present + CLI-invokable**: the agent inside the container can shell out to `openspec …`
during a stage. **Slash-command auto-activation (`/opsx:*`) is NOT wired** in the single-prompt
headless invocations (`codex exec`). OpenSpec's interactive UX is driven by slash commands plus an
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
Codex-specific notes:

- The prompt delta is appended to `PROMPT_INSTRUCTION` (the same seam that drives the Codex `exec`
  prompt); the read-only stages emit their change files **to stdout only** and never touch
  `/workspace/repo`.
- At pr-output the entrypoint lays down `openspec/AGENTS.md` + `openspec/changes/<id>/` itself (it does
  **not** run interactive `openspec init`, for the reasons in the 3a-6 finding above), splits the
  carried fenced files via `runner.mjs split-fenced`, runs best-effort `openspec validate`, and the
  Codex pr-output commit picks the folder up.
- The change-id is derived from the ticket ref + a short workspace-run-id slug; `runner.mjs prepare`
  surfaces `DL_TICKET_REF` and the per-stage reference paths for this.
- **Flag OFF ⇒ byte-identical**: no prompt delta, no files, no `openspec` call. The conformance IT
  pins both the flag-on assembly and the flag-off byte-identity.

### superpowers skills pin + update procedure (story 3a-7)

The agent-side [obra/superpowers](https://github.com/obra/superpowers) skills are **vendored** (not a
CLI — there is no `--version`; the pin is a **commit SHA**) into `runners/vendor/superpowers` and
surfaced as `ARG`/`ENV SUPERPOWERS_PIN` (currently **`f2cbfbef…`** = tag `v5.1.0`, MIT — **no**
floating `main`) so `--self-test` reports it. To re-vendor / bump (full steps + a copy-paste script in
[`runners/vendor/VENDOR.md`](../vendor/VENDOR.md)):

```bash
git ls-remote https://github.com/obra/superpowers.git refs/tags/<tag>   # resolve tag -> commit
# re-vendor runners/vendor/superpowers @ the new COMMIT sha (see VENDOR.md), then:
# bump ARG SUPERPOWERS_PIN in runners/codex/Dockerfile (mirror runners/claude/Dockerfile — same PR!)
docker compose build codex-runner
docker run --rm deliveryline/codex-runner:latest --self-test    # confirm the new pin + skill count report
```

> Per the RUNNER_CONTRACT mirror rule, a superpowers re-vendor (like a CLI bump) edits **both** runner
> Dockerfiles in the same PR. Keep the pin a **commit SHA**, never a branch.

**Offline/mock build:** unlike OpenSpec, superpowers needs **no** mock — the skills are static files
vendored via `COPY`, present in **both** the production and the `INSTALL_CODEX_CLI=false` build. The
self-test's skills assertion (`~/.agents/skills/superpowers` resolves + ≥1 `SKILL.md`) is therefore
green offline with no network, exactly as the conformance IT + CI `runner-image-compat` line exercise it.

> **Rebuild `:latest` after merge.** The hand-built `:latest` images do **not** auto-rebuild on merge
> (`[[runner-image-stale-causes-exit-20]]`); a stale `:latest` won't carry the vendored skills — rebuild
> (`docker compose build codex-runner`) and re-run `--self-test` after this change lands.

### superpowers activation during runs — finding (story 3a-7 AC5)

The skills are **present + discoverable**: Codex auto-scans `~/.agents/skills/` at startup and parses
each `SKILL.md` (the 14 vendored skills are loadable). Whether a skill **fires** in a **single-prompt
headless** run (`codex exec`, no human typing, no slash command) is the same open question as 3a-6's
OpenSpec: superpowers skills activate by **name-mention / task-description match**, and confirming
auto-firing needs a live run (egress + a credential), which the offline self-test/conformance tier does
not have. So this image ships the **minimum** (present + discoverable) and leaves the entrypoint
`PROMPT_INSTRUCTION` **unchanged** — **no runtime mutation of `/workspace/repo`, no interactive
install** (Trap T-NO-RUNTIME-MUTATION). Wiring a minimal, non-mutating prompt nudge that names the
available skills is deferred to a future story once headless activation is confirmed under real
execution (story 3.8).

### JDK + Maven toolchain

Both the Codex and Claude runner images carry a pinned **JDK 21** (Temurin, `eclipse-temurin:21-jdk`)
and **Maven 3.9** (pinned image `maven:3.9-eclipse-temurin-21`), installed in both the production build
and the conformance-test (`INSTALL_CODEX_CLI=false`) build. These tooling components enable agent
plans to shell out and run real `mvn` builds and compilation tasks within the container.

**Version pinning + upgrade procedure:**

To upgrade the JDK or Maven base images:

```bash
docker pull eclipse-temurin:21-jdk           # check latest patch / refresh
docker pull maven:3.9-eclipse-temurin-21     # check latest patch / refresh
# Verify the pinned versions are current:
docker run --rm eclipse-temurin:21-jdk java -version
docker run --rm maven:3.9-eclipse-temurin-21 mvn -version
# bump ARG JAVA_IMAGE and ARG MAVEN_IMAGE in runners/codex/Dockerfile (mirror runners/claude/Dockerfile — same PR!)
docker compose build codex-runner
docker run --rm deliveryline/codex-runner:latest --self-test   # confirm both tooling versions report
```

**Shared Maven cache (`/workspace/.m2`):**

A persistent Maven local repository is optionally mounted at `/workspace/.m2` (host path configured as
`{deliveryline.home}/maven-cache`, enabled by the backend when `deliveryline.runner.maven-cache-enabled=true`).
When present, the cache survives across runs, avoiding repeated dependency downloads. If the mount is
absent, Maven falls back to a container-local ephemeral repo and re-downloads on each run.

Per the RUNNER_CONTRACT change rule, a JDK or Maven version bump edits **both** runner Dockerfiles + the
READMEs in the same PR.

### Image size / layer count (AC9)

Production image (`INSTALL_CODEX_CLI=true`, real CLIs): **≈ 671 MB, 12 layers** (≈ 671 MB before
story 3a-7, + ~1.3 MB for the vendored superpowers tree — markdown + scripts — + one `COPY` layer,
11 → 12). The bulk is still the Codex CLI's bundled native binary on top of the ~200 MB `node:22-slim`
base; superpowers' markdown footprint is negligible (the vendored tree measures ~1.3 MB; the staging
`COPY`-then-`mv` roughly doubles that on disk to ~3 MB — still negligible). This remains **well under**
the ~1 GB budget, so no additional justification is required. (The conformance-test image, built with
both mocks + the same vendored skills, is far smaller — ~80 MB.)

> The ~19 MB OpenSpec delta was measured via `npm install -g @fission-ai/openspec@1.4.1` on
> `node:22-slim`; the ~652 MB Codex baseline was measured on WSL2 — re-measure the full production
> image on WSL2 Ubuntu (Docker builds differ Windows vs the Linux CI runner).

## Testing the image locally

```bash
# Build (production CLI):
docker compose build codex-runner
# or:  docker build -f runners/codex/Dockerfile -t deliveryline/codex-runner:latest .

# Smoke test (doctor / CI — story 1.16): exits 0, prints a health summary, no network:
docker run --rm deliveryline/codex-runner:latest --self-test

# Build the conformance-test image with the deterministic MOCK CLI (no network, no API):
docker build -f runners/codex/Dockerfile --build-arg INSTALL_CODEX_CLI=false \
  -t deliveryline/codex-runner:it-test .

# Run a stage against the valid fixture (mock CLI), mirroring the backend dispatch:
work=$(mktemp -d); mkdir -p "$work"/{input,output,logs}; chmod 0777 "$work"/{output,logs}
cp deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json \
   "$work/input/context-bundle.v1.json"
docker run --rm --network=none \
  -v "$work/input:/workspace/input:ro" \
  -v "$work/output:/workspace/output" \
  -v "$work/logs:/workspace/logs" \
  -e CODEX_API_KEY=sk-local-test \
  deliveryline/codex-runner:it-test --stage=spec-investigation
cat "$work/output/runner-result.v1.json"
```

The automated conformance test is `CodexRunnerImageConformanceIT`
(`deliveryline-backend`, `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`). Run it via the
Docker-tier:

```bash
mvn -pl deliveryline-backend test -Dtest=CodexRunnerImageConformanceIT \
    -DexcludedGroups= -Dgroups=docker-runner-it
```

It is excluded from the no-Docker PR tier. Real Codex-API execution is story 3.8 (profile-gated).

## Live build smoke (manual, needs egress)

This verification step confirms Maven resolves against the shared cache with real network egress.
**Network access is required** — this is **not** a CI gate, but a manual pre-rollout checklist item.

```bash
# Build the REAL image (installs the agent CLI; needs network).
docker build -f runners/codex/Dockerfile -t deliveryline/codex-runner:latest .

# Prove Maven resolves against the shared cache with real egress.
mkdir -p /tmp/dl-m2
docker run --rm -v /tmp/dl-m2:/workspace/.m2 --entrypoint sh \
  deliveryline/codex-runner:latest -c \
  'mvn -version && mvn -q -Dplugin=help help:describe -Dfull=false || true; ls /workspace/.m2 | head'
```

**Expected output:**
- Maven 3.9.x version banner
- `/workspace/.m2` populated with downloaded plugin artifacts on the first run
- The cache contents reused on subsequent runs (no re-download)

> If egress is unavailable in your environment, record that and defer the smoke to a networked host —
> do **not** block the commit on it.

## Rollout checklist

Before deploying the Maven cache mount to production, verify:

- [ ] **Build both real images.** Run `docker compose --profile runners build` to build both the
  Codex and Claude runner images with the real CLIs.

- [ ] **Run both conformance ITs in the Docker CI tier.** Execute:
  ```bash
  mvn -pl deliveryline-backend test -Ddocker-runner-it
  ```
  Both the Codex and Claude runner conformance tests must pass.

- [ ] **Rebuild the backend.** Rebuild the backend module so the new `maven-cache-enabled` config is
  picked up:
  ```bash
  mvn -pl deliveryline-backend clean install
  ```
  Confirm that both the main and test code compile without errors.

- [ ] **Re-run a build-workflow sample.** Execute a workflow with build-stage tasks (`java -version`,
  `mvn -B test/package/dependency:tree/verify`) and verify they produce exit code 0 with the
  dependency tree resolved via `/workspace/.m2`. Example: re-run the canonical test lineage (e.g.
  `FIN-41`, `run_009f4595…`) to confirm the build-half tasks now succeed with real Maven artifact
  resolution.

> **Future work (out of scope):** MySQL integration, servlet container, and browser automation remain
> blocked pending separate planner-awareness and Docker-in-Docker (DinD) specifications.

## Backend integration notes (story 3.8)

* **Stage injection:** the backend encodes the stage as the `deliveryline.stage` label today, which is
  not readable inside the container. To drive `artifactType` in production, the backend must pass the
  stage into the container interior via `DELIVERYLINE_RUNNER_STAGE` (this entrypoint already honors
  it). Wiring that env is a backend change owned by story 3.8 — out of scope here (the adapter is
  DO-NOT-EDIT in this story).
* **Mount writability:** the image runs as the non-root user `codex:1001`. The backend-created
  workspace dirs (`LocalRunnerWorkspaceStore` makes them `0700`, owned by the JVM user) must be
  writable by that uid in production (e.g. a matching `--user`, or group/world-writable output/logs).
  The conformance test creates its rw mounts world-writable to satisfy this on native Linux.
* **Real invocation (story 3.8):** the entrypoint invokes `codex <subcommand> --skip-git-repo-check
  --dangerously-bypass-approvals-and-sandbox [-C /workspace/repo] [--model <m>]`, prompt on stdin.
  `--skip-git-repo-check` clears Codex's "not inside a trusted directory" gate (a stage may have no
  repo mount); `--dangerously-bypass-approvals-and-sandbox` is correct here because the *container*
  is the sandbox (non-root, network policy set by the backend, read-only input) and a headless run
  has no TTY for Codex's own prompts; `-C` is added only when the repo mount is present. The
  `CODEX_EXEC_ARGS` seam overrides the subcommand token (default `exec`); `CODEX_MODEL` /
  `DELIVERYLINE_CODEX_MODEL` pins the model. Real runs require egress — see
  `deliveryline.runner.docker.network-mode` (default `none`; set `bridge` for real Codex).
