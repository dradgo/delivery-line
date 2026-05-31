# Codex Runner Image (`deliveryline/codex-runner`)

Story 3.3. A runner container that wraps the [Codex CLI](https://www.npmjs.com/package/@openai/codex)
and conforms to the **runner-contracts v1** file-based contract: read an input bundle, invoke Codex,
produce a result file, exit. The backend `DockerRunnerAdapter` (stories 3.1/3.2) dispatches it the
same way it dispatches the deterministic mock runner — no HTTP, no socket, `--network=none`.

> This image is **not** a long-running service. It is launched per execution by the backend with the
> workspace bind-mounts, runs once, and exits.

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
| `CODEX_API_KEY` | one of these for a real run | Agent-provider key (story 3.5 contract). Value is **never** logged. |
| `OPENAI_API_KEY` | fallback for the above | Used if `CODEX_API_KEY` is unset. |
| `CODEX_BASE_URL` | no | Optional API base-URL override (exported to the CLI as `OPENAI_BASE_URL`). |
| `DELIVERYLINE_RUNNER_STAGE` | no | Stage when `--stage` is not passed. |
| `DELIVERYLINE_CORRELATION_ID` | no | Correlation id prepended to log lines (story 3.11 traceability). |
| `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE` | no | Must equal `true` to enable `--simulate-failure` (off in production). |
| `DELIVERYLINE_RUNNER_SKIP_AUTH` | no | `true` lets a non-real run proceed without a key (mock/test only). |
| `CODEX_CLI_VERSION` | baked | The pinned Codex CLI version, reported by `--self-test`. |

Secrets arrive **as container env** (already injected by the backend `RunnerSecretsService` →
`CreateContainerSpec.environment`). The entrypoint reads the variable, exports `OPENAI_API_KEY` for
the CLI, and logs only the **variable name + presence** — never the value. The backend redaction
layer (story 3.6) is a backstop, not a license to leak.

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
| `20` | No agent-provider key present for a real run (`CODEX_API_KEY` / `OPENAI_API_KEY`). |
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

### Image size / layer count (AC9)

Production image (`INSTALL_CODEX_CLI=true`, real CLI): **~652 MB, 10 layers**. The bulk is the Codex
CLI's bundled native binary on top of the ~200 MB `node:22-slim` base. This is **under** the ~1 GB
budget, so no additional justification is required. (The conformance-test image, built with the mock
CLI, is ~327 MB.)

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
* **Real invocation:** the precise real-Codex argument vector is fed via the documented
  `CODEX_EXEC_ARGS` seam (default `exec`, prompt on stdin) and finalized in story 3.8.
