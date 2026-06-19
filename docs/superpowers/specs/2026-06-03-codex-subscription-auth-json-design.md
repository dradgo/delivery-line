# Codex Subscription Auth via `auth.json` — Design Spec

- **Date:** 2026-06-03
- **Proposed story id:** `3a-3` (Epic 3a — Agent Execution; runner-image hardening, sibling to 3-3/3-4/3-5)
- **Status:** design approved, pending spec → story creation
- **Author:** Alex (with Claude)

## Problem

The Codex runner today authenticates only with an API key: the entrypoint resolves
`CODEX_API_KEY` / `OPENAI_API_KEY` from the environment and exports `OPENAI_API_KEY`
for the Codex CLI (`runners/codex/entrypoint.sh:247-278`). Operators who want to use a
**ChatGPT/subscription** account cannot, because Codex stores subscription credentials in a
**file** — `$CODEX_HOME/auth.json` (default `~/.codex/auth.json`), holding the OAuth
access+refresh tokens and account id — not in a single env var.

Supporting subscription accounts therefore means *getting an `auth.json` file to the path the
Codex CLI reads*, which is a new mechanism relative to the env-var-only secret pipeline the
runners use today.

## Decisions (locked during brainstorming)

1. **Provisioning = env-var → materialize file.** The auth.json *content* travels as a secret env
   var (`CODEX_AUTH_JSON`); the runner entrypoint writes it to `$CODEX_HOME/auth.json` before
   invoking Codex. This reuses the existing `RunnerSecretsService` env-var pipeline — no new
   container mount capability.
2. **Precedence = subscription-first** (mirrors the Claude runner's dual-mode). `CODEX_AUTH_JSON`
   is added as the **first** entry in `deliveryline.runner.secret-env-names.codex`, so the existing
   first-present-wins resolution picks it up; API key remains the fallback.
3. **Scope = seam + plumbing** (mirrors how 3-3/3-4/3-5 landed their auth seams). Full
   materialization path, config, doctor/redaction awareness, and CI-green tests with fixtures. Real
   subscription execution is validated manually / deferred to story 3.8.
4. **Materialization = `runner.mjs` helper + raw single-line JSON.** A new `runner.mjs`
   subcommand validates and writes the file (the slim image has no `jq`, and `runner.mjs` already
   owns all JSON work). Raw (not base64) keeps the literal-substring leak scan meaningful.

## Why the backend needs almost no change

The backend secret path is name-driven and already generic:

- `RunnerSecretsService.resolveSecretsForRunner` — first-present-wins over the configured names →
  returns `CODEX_AUTH_JSON` when set. **No change.**
- `DoctorProbeAdapter.probeRunnerSecrets` (`:596`) iterates the *same* `secretEnvNamesFor(kind)`
  list and PASSes if any name resolves → reports Codex "present" on subscription auth
  automatically. **No code change** (remediation text only).
- `RunnerSecretScanService` substring-scans the injected value across `input/output/logs` → catches
  the auth.json content if it ever leaks. **No change** (relies on the raw-JSON choice).
- `DockerRunnerAdapter` injects whatever map it is handed. **No change.**

Adding `CODEX_AUTH_JSON` as the first configured name lights up the entire backend path. The only
genuinely new logic lives in the runner image.

## Components & changes

### Backend (config + docs only)

- `application.yml` **and** the test `application.yml` — prepend `CODEX_AUTH_JSON` to
  `deliveryline.runner.secret-env-names.codex`:
  `[CODEX_AUTH_JSON, CODEX_API_KEY, OPENAI_API_KEY]`. The test yaml *shadows* (does not merge), so
  both must change or the `@SpringBootTest` tier mis-binds.
- `DoctorService` `CHECK_RUNNER_SECRETS` remediation text — name `CODEX_AUTH_JSON` as a valid Codex
  credential. Probe logic unchanged.
- `.env.example` — document `CODEX_AUTH_JSON` (minified single-line JSON from `~/.codex/auth.json`).

### Runner image (the real work)

- `runners/codex/lib/runner.mjs` — new `materialize-auth --out <path>` subcommand: read
  `process.env.CODEX_AUTH_JSON`, `JSON.parse`-validate it is a non-empty object, write atomically
  with mode `0600`. Never print the value. Documented non-zero exit on malformed/empty input.
- `runners/codex/entrypoint.sh` — in the auth-resolution block: when the resolved credential name is
  `CODEX_AUTH_JSON`, set `CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"`, call
  `runner.mjs materialize-auth --out "$CODEX_HOME/auth.json"`, `export CODEX_HOME`, and **skip** the
  `OPENAI_API_KEY` export; otherwise the existing API-key path. Extend the `cleanup()` trap to
  `rm -f` the materialized file on exit. Update `print_help`.
- `runners/codex/Dockerfile` — no structural change (home dir already created via
  `useradd --create-home`); optionally declare a default `ENV CODEX_HOME`.
- `runners/codex/README.md` — document subscription mode: how to generate `auth.json`
  (`codex login` on a trusted host), the single-line-JSON `.env` requirement, and the precedence
  rule.

## Data flow

```
operator runs `codex login` on a trusted host → ~/.codex/auth.json
  → minify into .env as CODEX_AUTH_JSON=<single-line json>
RunnerSecretsService.resolveSecretsForRunner(CODEX, …)   [first-present → CODEX_AUTH_JSON]
  → DockerRunnerAdapter injects {CODEX_AUTH_JSON: <json>} as container env
  → entrypoint detects resolved name == CODEX_AUTH_JSON
      → runner.mjs materialize-auth → $CODEX_HOME/auth.json (0600, atomic)
      → export CODEX_HOME ; skip OPENAI_API_KEY export
  → Codex CLI reads auth.json (subscription mode)
```

Materialization happens on **every** Codex dispatch when subscription auth is configured; it is not
stage-specific.

## Error handling

- Malformed / empty `CODEX_AUTH_JSON` → `runner.mjs` exits non-zero → entrypoint writes a
  `runner_non_zero_exit` failure result via the existing `build-failure` path and exits with a
  documented code. The value is never echoed.
- Neither subscription nor API credential present → unchanged "no agent-provider key" failure
  (entrypoint exit 20).

## Security / leak-safety

- File written `0600`, in the non-root `codex` user's home, **outside** the workspace mounts; removed
  by the `cleanup()` trap on exit; the container is ephemeral regardless.
- `RunnerSecretScanService` substring-scans the injected JSON across `input/output/logs` and would
  flag any accidental leak as `injected_provider_key` — works because we chose raw JSON, not base64.
- The value is logged by name + presence only (entrypoint), and never by `runner.mjs`.
- **`--network=none` note:** the contract/self-test tier has no network. Subscription token *refresh*
  requires egress and only matters under real execution (story 3.8). Out of scope here — we
  materialize exactly what the operator supplies; an expired token surfaces as a normal run failure.

## Testing (must stay green in CI)

- `runner.mjs` unit tests: valid object → file written with `0600`; invalid JSON / empty → correct
  non-zero exit; value never appears on stdout/stderr.
- `entrypoint` test: `CODEX_AUTH_JSON` set → materialize branch taken, `OPENAI_API_KEY` not
  exported, cleanup removes the file; API-key path unchanged when only `CODEX_API_KEY` is set.
- `CodexRunnerImageConformanceIT`: a subscription scenario (`-e CODEX_AUTH_JSON=…`, mock-codex build)
  asserting the run completes and the file lands at `$CODEX_HOME/auth.json`; may extend
  `mock-codex.sh` to assert presence.
- `RunnerSecretScanServiceTest`: a leaked-auth-json fixture is detected.
- Backend: focused doctor + properties slices green; `secret-env-names` binding verified in both
  yaml tiers (memory: `validated-config-needs-test-yaml`).

## Out of scope

Real Codex subscription execution and token refresh (story 3.8); base64 transport; Claude (already
env-var dual-mode); any new container mount capability; Linear↔repo mapping.

## Memory / convention references

- `validated-config-needs-test-yaml` — change both yamls for `secret-env-names`.
- `rtk-hook-only-matches-bash` — run gates via PowerShell, route around the Bash hook.
- `runner-image-ci-root-context` — build with `-f runners/codex/Dockerfile … .` + mock CLI.
- `springboot-testcontainers-test-must-be-IT` — Docker-backed conformance test stays `*IT`.
