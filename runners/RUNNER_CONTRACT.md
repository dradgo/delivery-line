# Runner Contract (shared) — `runner-contracts v1`

**Single source of truth for the conventions every DeliveryLine runner image obeys.**
The `codex` (story 3.3) and `claude` (story 3.4) runners are interchangeable real runner kinds
selectable by config (`deliveryline.runner.docker.default-kind=codex|claude`). They share the **same**
environment-variable, mount, context-file, result-file, and diagnostics contract so the backend
`DockerRunnerAdapter` dispatches them identically and the v1 contract is validated against two
independent implementations.

> **Change rule (AC10).** Any change to the input-bundle schema, output schema, mount paths, or exit
> codes **MUST update both runner Dockerfiles + entrypoints in the same PR**, and this document
> first. A schema change that touches only one runner is a contract break. A CI check that builds
> **both** images and runs `--self-test` on every PR touching
> `deliveryline-runner-contracts/.../schemas/` is owned by stories **3.28 / 3.34** (deferred — see
> each runner README); the per-runner conformance ITs (`CodexRunnerImageConformanceIT`,
> `ClaudeRunnerImageConformanceIT`) exist today and the CI tier will invoke them.

Per-runner specifics (base image, CLI pin, provider auth, model config) live in
[`codex/README.md`](codex/README.md) and [`claude/README.md`](claude/README.md). Everything below is
**shared and identical** across runners.

## Mounts (the only runner ↔ backend boundary)

The contract is **file-based only** (story 3.1 AC8): three mounts, two contract files, no HTTP, no
socket between the backend and the runner. The container network mode is configurable via
`deliveryline.runner.docker.network-mode` (story 3.8) and **defaults to `--network=none`** — the
locked-down posture for the mock/contract/self-test tiers, which need no egress. Real agent runs
(the codex/claude CLI calling its provider API) set it to `bridge` (or an egress-allowlisted
network); this never adds a backend↔runner channel — the file mounts remain the only such boundary.

| Mount | Mode | Contents |
|-------|------|----------|
| `/workspace/input`  | read-only  | `context-bundle.v1.json` (the input bundle the backend writes) |
| `/workspace/output` | read-write | `runner-result.v1.json` (the result the entrypoint MUST write) |
| `/workspace/logs`   | read-write | `runner.stdout`, `runner.stderr` (raw CLI output) |

The entrypoint **never** writes outside `/workspace/output` and `/workspace/logs`.

## Contract files & filenames (live backend contract)

The filenames carry the **`.v1` infix** and are verified against `LocalRunnerWorkspaceStore`
(`CONTEXT_BUNDLE_FILENAME = context-bundle.v1.json`, `RUNNER_RESULT_FILENAME = runner-result.v1.json`).
The adapter classifies a **missing result file as `RUNNER_CRASH` regardless of exit code**, so the
entrypoint **always** writes a schema-valid result file before exiting `0` — including actionable
failure branches (auth / command-config / CLI non-zero), which write a schema-valid *failure* result
rather than collapsing to `RUNNER_CRASH`.

- **Input:** `context-bundle.v1.json`, `schemaVersion: 1`. An unknown version is rejected (exit `12`);
  the runner never emits an unversioned or mis-named result.
- **Output:** `runner-result.v1.json`, validated against `runner-result.v1.schema.json`.

## Stage → `artifactType`

The frozen `context-bundle.v1` schema is `additionalProperties: false` (it cannot carry a `stage`
field), and the Docker `deliveryline.stage` label is container metadata **not readable inside the
container**. The stage is therefore supplied to the entrypoint via — **in priority order**:

1. `--stage=<token>` CLI argument, **or**
2. `DELIVERYLINE_RUNNER_STAGE` environment variable (the production injection seam; backend wiring is
   story 3.8), **or**
3. an optional `stage` field in the bundle, if a future schema revision adds one (forward-compatible).

Accepted tokens (the three story-named artifact stages plus the `RunnerStage` enum aliases):

| Stage tokens | `artifactType` |
|--------------|----------------|
| `spec-investigation`, `investigation`, `spec` | `spec` |
| `implementation-plan`, `plan`, `implementationPlan` | `implementationPlan` |
| `pr-output`, `execution`, `prOutput` | `prOutput` |

## Exit codes

| Code | Meaning |
|------|---------|
| `0`  | Success — `runner-result.v1.json` written (or self-test / simulated contract-violation). |
| `1`  | `--self-test` failed (node / helper / CLI missing or version mismatch). |
| `2`  | Usage error, or `--simulate-failure` requested without the test-only env gate, or an invalid CLI-args seam. |
| `10` | Input bundle not found at `/workspace/input/context-bundle.v1.json`. |
| `11` | Input bundle is not readable / not valid JSON. |
| `12` | Unsupported `schemaVersion` (these images speak v1 only). |
| `13` | No stage resolved, or an unknown stage token. |
| `20` | No agent-provider credential present for a real run. |
| `30` | The agent CLI exited non-zero (see `runner.stderr`). A schema-valid failure result is written. |
| `40` | Failed to build/write `runner-result.v1.json`. |
| `50` | `--simulate-failure=crash` — intentional non-zero exit with **no** result file. |

> `--simulate-failure=timeout` sleeps indefinitely (the backend timeout path kills it);
> `--simulate-failure=contract_violation` writes a schema-invalid result and exits `0` so the
> backend's `RunnerContractValidator` classifies it as `runner_contract_violation`.

## Diagnostic flags

| Flag | Purpose |
|------|---------|
| `--self-test` | Verify image health (node + helper present, CLI on PATH with the expected version pin, mounts detectable). Exits `0` with a documented summary. No network, no API call. |
| `--simulate-failure={timeout\|crash\|contract_violation}` | Test-only failure injection, gated behind `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true` (off in production). |
| `--stage=<token>` | Force the stage (see the stage table above). |
| `--help` / `-h` | Usage summary. |

## Shared environment variables

These are **identical** across runners (per-runner provider keys + model config are in each README):

| Variable | Purpose |
|----------|---------|
| `DELIVERYLINE_RUNNER_STAGE` | Stage when `--stage` is not passed (production injection seam). |
| `DELIVERYLINE_CORRELATION_ID` | Optional correlation id prepended to every log line (story 3.11 traceability). |
| `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE` | Must equal `true` to enable `--simulate-failure` (off in production). |
| `DELIVERYLINE_RUNNER_SKIP_AUTH` | `true` lets a non-real run proceed without a credential (mock/test only). |

Mount-path overrides (`DELIVERYLINE_INPUT_DIR` / `_OUTPUT_DIR` / `_LOGS_DIR`) exist purely so the
entrypoint is unit-testable without a container; production uses the `/workspace/*` constants.

## Secrets & logging discipline

Agent-provider credentials arrive **as container env**, already injected by the backend
`RunnerSecretsService` → `CreateContainerSpec.environment` (each value injected under the env-var name
it was found under). The entrypoint reads the variable and logs only the **variable name + presence**
— never the value. **Forbidden in any output** (`runner.stdout` / `runner.stderr` / the result):
secret values, auth headers, full bundle payloads, raw CLI exec-args / prompt. Each runner's
conformance IT pins this with a negative-log assertion. The backend redaction layer (story 3.6) is a
backstop, not a license to leak.

### Runner-specific subscription credentials (NOT shared obligations)

Both runners support a cost-saving **subscription** credential in addition to the API key, but the
*mechanism differs per runner* — these are documented here for visibility, not as a shared contract
either image must implement:

- **Claude (story 3.4):** subscription auth is the env var `CLAUDE_CODE_OAUTH_TOKEN`, which the Claude
  CLI reads directly. No file materialization.
- **Codex (story 3a-3):** subscription auth is `CODEX_AUTH_JSON` — the raw, single-line content of
  `$CODEX_HOME/auth.json` (Codex reads subscription credentials from a **file**, not an env var). The
  Codex entrypoint materializes it into `$CODEX_HOME/auth.json` (mode `0600`, atomic) via
  `runner.mjs materialize-auth` before invoking Codex, and removes it on exit. A present-but-malformed
  `CODEX_AUTH_JSON` exits **`21`** (Codex-specific, distinct from the shared exit `20` "no credential
  present") with a schema-valid failure result. The raw-JSON transport (not base64) keeps
  `RunnerSecretScanService`'s literal-substring leak detector effective.

Both subscription credentials are **subscription-first**: each runner's
`deliveryline.runner.secret-env-names.<kind>` lists the subscription name first, so the
first-present-wins resolution prefers it over the API key.

## Least privilege

Non-root user (`codex:1001` / `claude:1001` — uid/gid `1000` is taken by the `node:22-slim` base's
own `node` user), `--network=none` by default, secret from env never echoed, single-stage image, the
prompt temp file written under `/workspace/logs` (never outside the rw mounts).
