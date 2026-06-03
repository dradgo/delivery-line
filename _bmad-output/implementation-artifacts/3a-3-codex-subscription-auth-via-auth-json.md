# Story 3a-3: Codex Subscription Auth via `auth.json`

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **runner-infrastructure developer**,
I want **the `runners/codex/` runner image to authenticate the Codex CLI with a ChatGPT/subscription account by materializing a `CODEX_AUTH_JSON` secret env var into `$CODEX_HOME/auth.json` before invoking Codex — the Codex twin of story 3.4's Claude subscription-first dual-mode auth, but file-based because Codex keeps subscription credentials in a file, not an env var**,
so that **operators can run the Codex runner against a Pro/subscription account (the cost-saving path) instead of per-token API-key billing, selectable purely by which credential is present — with no new container mount capability and no backend logic change**.

## Context & Design Source

This story was brainstormed and the design approved on 2026-06-03. The authoritative design spec is
`docs/superpowers/specs/2026-06-03-codex-subscription-auth-json-design.md` — read it first; this story
operationalizes it. Four decisions are **locked** (do not re-open):

1. **Provisioning = env-var → materialize file.** The auth.json *content* travels as a secret env var
   (`CODEX_AUTH_JSON`); the entrypoint writes it to `$CODEX_HOME/auth.json` before invoking Codex.
   Reuses the existing `RunnerSecretsService` env-var pipeline — **no new container mount**.
2. **Precedence = subscription-first** (mirrors Claude story 3.4). `CODEX_AUTH_JSON` is the **first**
   entry in `secret-env-names.codex`; API key is the fallback.
3. **Scope = seam + plumbing** (mirrors how 3.3/3.4/3.5 landed their auth seams). Real subscription
   execution + token refresh deferred to story 3.8.
4. **Materialization = `runner.mjs` helper + raw single-line JSON** (not shell, not base64).

## Acceptance Criteria

1. **Given** `deliveryline.runner.secret-env-names.codex`, **Then** `CODEX_AUTH_JSON` is prepended so
   the list is `[CODEX_AUTH_JSON, CODEX_API_KEY, OPENAI_API_KEY]` — in `application.yml`, the **test**
   `application.yml`, **and** `RunnerProperties.defaultSecretEnvNames()`. NAMES only; no secret value
   ever appears in any yaml (story 3.5 AC2). The list is **ordered = resolution preference**, so
   `RunnerSecretsService` (first-present-wins) injects `CODEX_AUTH_JSON` under its own name when set
   (matched-name injection, story 3.4) — **no change to `RunnerSecretsService` itself**.
2. **Given** `runners/codex/lib/runner.mjs`, **Then** a new `materialize-auth --out <path>` subcommand:
   (a) reads the credential from `process.env.CODEX_AUTH_JSON` (NOT from argv — never on the process
   command line); (b) fails with a documented non-zero exit if it is absent/blank or does not
   `JSON.parse` to a non-empty object; (c) on success writes it **atomically** (tmp + rename, reusing
   the existing `writeAtomically` helper) to `--out` with file mode **`0600`**; (d) **never** prints
   the value to stdout/stderr (only a name+presence diagnostic).
3. **Given** `runners/codex/entrypoint.sh` auth resolution (currently `:247-278`), **When** the
   resolved credential is `CODEX_AUTH_JSON` (checked **before** `CODEX_API_KEY`/`OPENAI_API_KEY`),
   **Then** the entrypoint: (a) sets and `export`s `CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"`;
   (b) invokes `runner.mjs materialize-auth --out "$CODEX_HOME/auth.json"`; (c) does **NOT** export
   `OPENAI_API_KEY`; (d) logs the auth mode by **name + presence + path only** (e.g. `auth resolved
   from variable name=CODEX_AUTH_JSON mode=subscription`), never the value. When only
   `CODEX_API_KEY`/`OPENAI_API_KEY` is present the existing API-key path is **byte-for-byte
   unchanged**.
4. **Given** the `cleanup()` trap (`:37-42`), **Then** it also `rm -f`s the materialized
   `$CODEX_HOME/auth.json` on `EXIT/INT/TERM` (defense-in-depth; the container is ephemeral anyway).
5. **Given** a malformed/empty `CODEX_AUTH_JSON` on a real (non-self-test, non-simulate) run, **Then**
   `materialize-auth` fails and the entrypoint writes a **schema-valid failure result** via
   `runner.mjs build-failure --category runner_non_zero_exit` and exits with a documented code —
   never collapsing to `RUNNER_CRASH`, never echoing the value.
6. **Given** the "no credential present" guard (`:258`), **Then** it is satisfied when
   `CODEX_AUTH_JSON` is present (subscription) **or** an API key is present; only the absence of
   **all** configured credentials (with `DELIVERYLINE_RUNNER_SKIP_AUTH != true`) drives the existing
   exit `20` no-key failure, which names the **preferred** variable only.
7. **Given** `DoctorService` `CHECK_RUNNER_SECRETS`, **Then** its remediation text names
   `CODEX_AUTH_JSON` as a valid Codex credential. `DoctorProbeAdapter.probeRunnerSecrets` (`:596`)
   already iterates `secretEnvNamesFor(kind)` and PASSes if **any** name resolves, so Codex reports
   `present` on subscription auth with **no probe code change** — a focused doctor test asserts PASS
   when only `CODEX_AUTH_JSON` is set.
8. **Given** `RunnerSecretScanService`, **Then** because the credential travels as **raw** JSON (AC: not
   base64), its mandatory literal-substring detector still catches the auth.json content if it ever
   leaks into `input/output/logs` (synthetic category `injected_provider_key`). A focused test proves
   a workspace file containing the injected auth.json content is flagged. **No scan code change.**
9. **Given** `runners/codex/README.md`, **Then** it documents subscription mode: how to generate
   `auth.json` (`codex login` on a trusted host), the **single-line minified JSON** requirement for
   `CODEX_AUTH_JSON` in `.env`, the subscription-first precedence rule, and the `$CODEX_HOME/auth.json`
   materialization. `.env.example` documents `CODEX_AUTH_JSON`.
10. **Given** `CodexRunnerImageConformanceIT`, **Then** a new subscription scenario runs the
    mock-CLI image with `-e CODEX_AUTH_JSON=<fixture json containing a sentinel>` and asserts: the run
    completes (exit 0, schema-valid result), the entrypoint's `materialize` log line is present in
    `runner.stderr`, and the **negative-leak** assertion holds — the auth.json sentinel never appears
    in `runner.stdout`/`.stderr`/the result. File existence + `0600` perms + cleanup are covered by the
    `runner.mjs`/entrypoint unit tier (no live container fs inspection needed).
11. **Given** the shared runner contract, **Then** `runners/RUNNER_CONTRACT.md` records the
    `CODEX_AUTH_JSON` env var + the `$CODEX_HOME/auth.json` materialization convention in the shared
    env-var section; this is a **Codex-only** credential (Claude's subscription path is the env-var
    `CLAUDE_CODE_OAUTH_TOKEN`, story 3.4) so it is documented as runner-specific, not a shared
    obligation on the Claude runner.

## Tasks / Subtasks

- [x] **Task 1: Backend config — add `CODEX_AUTH_JSON` to `secret-env-names.codex`** (AC: 1, 7)
  - [x] `deliveryline-backend/src/main/resources/application.yml`: change `secret-env-names.codex` to
        `[CODEX_AUTH_JSON, CODEX_API_KEY, OPENAI_API_KEY]` (`:144-147`); update the explanatory comment
        above it to note the subscription-first Codex mode (mirroring the Claude comment).
  - [x] `deliveryline-backend/src/test/resources/application.yml`: make the **same** change — the test
        yaml *shadows* (does not merge) the main one, so an omission mis-binds the `@SpringBootTest`
        tier ([[validated-config-needs-test-yaml]]).
  - [x] `RunnerProperties.defaultSecretEnvNames()` (`:144`): `CODEX → List.of("CODEX_AUTH_JSON",
        "CODEX_API_KEY", "OPENAI_API_KEY")`. Update the javadoc on `defaultSecretEnvNames()` (`:132-141`)
        to describe Codex's subscription-first file-based mode.
  - [x] Update `RunnerPropertiesTest` expectations for the Codex list. (Also updated
        `RunnerSecretsServiceTest.blankValueIsTreatedAsMissing` — the preferred missing-var hint is now
        `CODEX_AUTH_JSON`.)
  - [x] **Do NOT touch `RunnerSecretsService`.** Matched-name injection (story 3.4, `:91-105`) already
        injects the first-present value under its found name — `CODEX_AUTH_JSON` rides for free.
- [x] **Task 2: `runner.mjs materialize-auth` subcommand** (AC: 2, 5)
  - [x] Add `commandMaterializeAuth(args)` to `runners/codex/lib/runner.mjs` and wire it into the
        bottom `switch` (`:296-311`). Read `process.env.CODEX_AUTH_JSON` (NOT argv). Fail
        (`fail(<code>, …)`) when absent/blank or when `JSON.parse` throws or yields a non-object /
        empty object. On success call the existing `writeAtomically(out, contents)` (`:100-109`) then
        `chmodSync(out, 0o600)` (import `chmodSync` from `node:fs`).
  - [x] **Never** write the value to stdout/stderr. The only diagnostic is a name+presence line (or
        silence) — the entrypoint owns the human-readable log.
  - [x] Pick a documented exit code consistent with the entrypoint's table (chose **21**, new
        auth-family code; see Dev Notes "Exit codes"). Recorded in the `runner.mjs` header comment +
        entrypoint header + README + RUNNER_CONTRACT.
- [x] **Task 3: Entrypoint subscription branch** (AC: 3, 4, 5, 6)
  - [x] In the auth-resolution block (`runners/codex/entrypoint.sh:247-278`), **before** the existing
        `CODEX_API_KEY`/`OPENAI_API_KEY` checks, add: `if [ -n "${CODEX_AUTH_JSON:-}" ]; then` →
        `CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"; export CODEX_HOME;` run
        `"$NODE_BIN" "$RUNNER_LIB" materialize-auth --out "$CODEX_HOME/auth.json"`; set
        `AUTH_KEY_VAR=CODEX_AUTH_JSON` + an `mode=subscription` marker; **skip** the
        `export OPENAI_API_KEY` path. Guard every expansion with `${VAR:-}` (the script runs `set -eu`).
  - [x] On `materialize-auth` non-zero: log ERROR (name only), write a schema-valid failure result via
        `runner.mjs build-failure --category runner_non_zero_exit`, exit the documented code 21 (AC5).
  - [x] Update the "no agent-provider key present" guard (`:258`) so `CODEX_AUTH_JSON` presence counts
        as a resolved credential (AC6); message names the preferred variable.
  - [x] Extend `cleanup()` (`:37-42`) to also `rm -f` the materialized auth.json (AC4).
  - [x] Update `print_help` (`:56-78`) auth section to list `CODEX_AUTH_JSON` (subscription) ahead of
        `CODEX_API_KEY`/`OPENAI_API_KEY`.
  - [x] Log auth mode by **name + presence + path only** — never the value (AC3d).
- [x] **Task 4: Doctor remediation text** (AC: 7)
  - [x] `DoctorService` `CHECK_RUNNER_SECRETS` remediation (`:85-87`): add `CODEX_AUTH_JSON` to the
        Codex options string. **No probe-logic change** — `probeRunnerSecrets` (`:596`) is already
        name-driven.
  - [x] Add/extend a focused doctor test asserting PASS for Codex when **only** `CODEX_AUTH_JSON` is
        set (mirrors the existing dual-mode Claude assertion).
- [x] **Task 5: Leak-scan coverage** (AC: 8)
  - [x] Add a `RunnerSecretScanServiceTest` case: with `CODEX_AUTH_JSON` set in the test environment, a
        workspace file whose text contains the injected auth.json content is reported as a leak with
        category `injected_provider_key`. **No production scan change** — this pins that the raw-JSON
        choice keeps the substring detector effective.
- [x] **Task 6: Conformance IT subscription scenario** (AC: 10)
  - [x] Extend `CodexRunnerImageConformanceIT` with a test that runs the mock-CLI image with
        `-e CODEX_AUTH_JSON=<single-line fixture json containing AUTH_SENTINEL>` (+ a stage), asserts
        exit 0 + schema-valid `runner-result.v1.json`, asserts the **container stderr** contains the
        `name=CODEX_AUTH_JSON`/`mode=subscription` materialize log line (the entrypoint's diagnostics
        go to the container's stderr stream, not the `runner.stderr` mount), and asserts `AUTH_SENTINEL`
        appears in **none** of the container logs / `runner.stdout` / `runner.stderr` / result. Reuses
        the existing valid `context-bundle.v1` fixture + mount helpers; keeps `@Tag("docker-runner-it")`
        + `@EnabledIfDockerAvailable` so it stays in the Docker tier
        ([[springboot-testcontainers-test-must-be-IT]]).
  - [x] `mock-codex.sh` needs **no change** (it already ignores env + auth files); do not make the mock
        read auth.json.
- [x] **Task 7: Docs — README + RUNNER_CONTRACT + .env.example** (AC: 9, 11)
  - [x] `runners/codex/README.md`: new "Subscription authentication" section — `codex login` on a
        trusted host → minify `~/.codex/auth.json` into `CODEX_AUTH_JSON` (single line) → subscription
        beats API key when both present → materialized to `$CODEX_HOME/auth.json` (0600) → real
        subscription execution + token refresh are story 3.8. (Env-var + exit-code tables updated too.)
  - [x] `.env.example`: document `CODEX_AUTH_JSON` (commented; single-line minified JSON; never
        committed).
  - [x] `runners/RUNNER_CONTRACT.md`: record `CODEX_AUTH_JSON` + the `$CODEX_HOME/auth.json`
        materialization as a **Codex-specific** credential convention (not a shared obligation).
- [x] **Logging instrumentation** (cross-cutting; adapted for a shell entrypoint + Node helper)
  - [x] Entrypoint emits a structured `[codex-runner] [INFO]` line on auth-mode resolution carrying
        `name=CODEX_AUTH_JSON mode=subscription` (+ path), and `[ERROR]` on materialize failure
        (name only). Prepends `DELIVERYLINE_CORRELATION_ID` when present (existing `log()` helper).
  - [x] **Never** print the `CODEX_AUTH_JSON` value, the auth.json contents, or any token — name +
        presence + mode + path only. The conformance IT negative-leak assertion (Task 6) is the
        enforcing test; the runner.mjs unit tier + entrypoint smoke matrix add the same negative
        assertion.

## Dev Notes

### ⚠️ Critical context before you start

- **This is a runner-infrastructure story (POSIX shell entrypoint + Node helper + image docs +
  conformance test) plus a config-only backend touch. There is essentially NO new backend Java
  logic** — the secret pipeline is name-driven and already generic. The Java you change is: two yamls,
  `RunnerProperties.defaultSecretEnvNames()` + its test, the doctor remediation string + a doctor
  test, and one scan test. The conformance IT is extended, not authored.
- **Story 3.4 (Claude subscription dual-mode) is your closest analog and template** — read
  `_bmad-output/implementation-artifacts/3-4-claude-runner-image-dockerfile-and-entrypoint-and-contract-conformance.md`
  (esp. Task 5, Completion Notes, Review Findings) before starting. The ONLY structural difference:
  Claude's subscription credential is an **env var** the CLI reads directly (`CLAUDE_CODE_OAUTH_TOKEN`);
  Codex's is a **file** (`$CODEX_HOME/auth.json`) you must materialize. Everything else (subscription-
  first ordering, matched-name injection, doctor PASS-on-either, name+presence logging, negative-leak
  test) is the same pattern.
- **Story 3.3 (Codex runner image) is the file you're editing** — `runners/codex/entrypoint.sh`,
  `lib/runner.mjs`, `README.md`, `test/mock-codex.sh` are all live and DONE. Mirror their conventions;
  do not re-derive the contract.

### What is genuinely NEW in 3a-3

1. **File-based subscription credential.** Unlike every prior runner credential (all env vars consumed
   directly by the CLI), Codex subscription auth is a **file** Codex reads from `$CODEX_HOME/auth.json`.
   The new mechanism = materialize an injected env var into that file. This is the whole story.
2. **`runner.mjs materialize-auth` subcommand.** First `runner.mjs` command that consumes a secret
   (from `process.env`, never argv) and writes a credential file (0600, atomic). It must be as
   leak-disciplined as the rest of the helper (never prints the value).
3. **`CODEX_HOME` handling in the entrypoint.** New: set + export `CODEX_HOME`, default `$HOME/.codex`,
   write `auth.json` there, clean it on exit.

### Architecture patterns & constraints (verified against live code)

- **Name-driven secret pipeline — DO NOT add logic, conform to it:**
  - `RunnerSecretsService.resolveSecretsForRunner` (`application/runner/RunnerSecretsService.java:73`):
    iterates `secretEnvNamesFor(kind)`, returns the **first present** non-blank value injected **under
    the name it was found** (matched-name injection, story 3.4 `:91-105`). With `CODEX_AUTH_JSON` first,
    a subscription operator's container gets `CODEX_AUTH_JSON=<json>` env; an API operator's gets
    `CODEX_API_KEY=…`. No change.
  - `DoctorProbeAdapter.probeRunnerSecrets` (`adapters/diagnostics/DoctorProbeAdapter.java:596`):
    PASSes if any configured name resolves. No change (remediation text only).
  - `RunnerSecretScanService` (`application/runner/RunnerSecretScanService.java`): re-resolves the
    injected value(s) and does a **literal substring** containment check across every `input/output/logs`
    file (`:115-121`), category `injected_provider_key`. Works on the raw JSON; **base64 would have
    blinded it** — that's why decision #4 is raw JSON. No change.
  - `DockerRunnerAdapter` injects whatever map it's handed into `CreateContainerSpec.environment`.
    **DO NOT EDIT** (behavioral contract: ro/rw mounts, `--network=none`, result-file-absent ⇒
    `RUNNER_CRASH`).
- **`runner.mjs` owns all JSON** (slim base image has no `jq`) — that's why materialization + JSON
  validation live there, not in the POSIX entrypoint (decision #4 / story 3.3 convention).
- **`set -eu` entrypoint** — every new variable expansion MUST use `${VAR:-}` or the script aborts on
  an unset var. `CODEX_AUTH_JSON` is the injected secret; reference it as `${CODEX_AUTH_JSON:-}`.
- **Least privilege:** non-root `codex:1001` (`Dockerfile:66-67`, uid/gid 1000 taken by base `node`
  user); `useradd --create-home` means `/home/codex` exists and is writable → `$HOME/.codex/auth.json`
  is writable by the runtime user. `0600` perms on the materialized file.
- **`--network=none` reality:** the contract/self-test/conformance tier has no network. Subscription
  **token refresh** needs egress and only matters under real execution → story 3.8. This story
  materializes exactly what the operator supplies; an expired token surfaces as a normal Codex
  non-zero-exit failure.

### Exit codes (documented entrypoint table — pick consistently)

The existing Codex auth-failure family is exit `20` (no key present) and `30` (CLI non-zero). For a
malformed/empty `CODEX_AUTH_JSON` the credential **is** present but unusable, so prefer a **new
documented code in the auth family (e.g. `21`)** OR reuse `20` with a distinct summary — recommend a
distinct code so the failure-result `summary` is unambiguous ("Codex auth.json is malformed"). Record
the chosen code in: the entrypoint header comment, `runner.mjs` header, `README.md` exit-code table,
and `runners/RUNNER_CONTRACT.md`. `materialize-auth`'s own `runner.mjs` exit code can differ (it's an
internal helper); the **entrypoint**'s exit code is the contract surface.

### Inherited corrections (apply — do not rediscover)

- Filenames carry the `.v1` infix; failure branches write a **schema-valid failure result** via
  `build-failure` so they don't collapse to `RUNNER_CRASH`.
- The CLI-exec seam stays shell-safe-token-guarded; secrets logged by **name + presence** only.
- Conformance IT builds via the **docker CLI** (`docker build -f runners/codex/Dockerfile … .`,
  `INSTALL_CODEX_CLI=false`) — keep only container RUNS on docker-java ([[runner-image-ci-root-context]]).
- Run all gates via **PowerShell**, not the Bash tool ([[rtk-hook-only-matches-bash]]).
- Smoke the dash entrypoint matrix + the Docker conformance IT on **WSL2 Ubuntu** before pushing; the
  `docker-runner-it` tier is excluded from the no-Docker PR tier ([[wsl-linux-ci-reproduction]],
  [[verify-ci-fixes-in-clean-env]]).

### Existing files / contract surfaces (verified by reading them)

```
runners/codex/entrypoint.sh        # EDIT: subscription branch (Task 3), cleanup trap, print_help
runners/codex/lib/runner.mjs       # EDIT: new materialize-auth subcommand (Task 2)
runners/codex/README.md            # EDIT: subscription auth section (Task 7)
runners/codex/test/mock-codex.sh   # NO CHANGE (already ignores env + auth files)
runners/RUNNER_CONTRACT.md         # EDIT: record CODEX_AUTH_JSON convention (Task 7)
.env.example                       # EDIT: document CODEX_AUTH_JSON (Task 7)

deliveryline-backend/src/main/resources/application.yml          # EDIT: secret-env-names.codex (Task 1)
deliveryline-backend/src/test/resources/application.yml          # EDIT: same (Task 1)
deliveryline-backend/src/main/java/.../application/runner/RunnerProperties.java        # EDIT: defaultSecretEnvNames() (Task 1)
deliveryline-backend/src/main/java/.../application/diagnostics/DoctorService.java      # EDIT: remediation text (Task 4)
deliveryline-backend/src/main/java/.../adapters/diagnostics/DoctorProbeAdapter.java    # NO CHANGE (name-driven probe)
deliveryline-backend/src/main/java/.../application/runner/RunnerSecretsService.java    # NO CHANGE
deliveryline-backend/src/main/java/.../application/runner/RunnerSecretScanService.java # NO CHANGE
deliveryline-backend/src/main/java/.../adapters/runner/DockerRunnerAdapter.java        # DO NOT EDIT

deliveryline-backend/src/test/java/.../adapters/runner/CodexRunnerImageConformanceIT.java # EXTEND (Task 6)
deliveryline-backend/src/test/java/.../application/runner/RunnerSecretScanServiceTest.java # EXTEND (Task 5)
deliveryline-backend/src/test/java/.../application/runner/RunnerPropertiesTest.java        # EXTEND (Task 1)
+ a focused DoctorService/DoctorProbeAdapter test for CODEX_AUTH_JSON-only PASS (Task 4)
```

### Key live-code facts (so the entrypoint matches reality)

- `application.yml` today: `secret-env-names.codex: [CODEX_API_KEY, OPENAI_API_KEY]` (`:144-147`);
  `RunnerProperties.defaultSecretEnvNames()` → `CODEX → [CODEX_API_KEY, OPENAI_API_KEY]` (`:144`).
- Entrypoint auth block today (`:247-278`): resolves first-present of `CODEX_API_KEY` then
  `OPENAI_API_KEY`, `export OPENAI_API_KEY="$AUTH_KEY"`. No `CODEX_AUTH_JSON`, no `CODEX_HOME`.
- `cleanup()` (`:37-42`) removes only `PROMPT_FILE` today.
- `runner.mjs` (`:296-311`) dispatches `prepare|build|build-invalid|build-failure`; `writeAtomically`
  (`:100-109`); header (`:26-28`) states secrets live in env and are consumed by the CLI directly —
  this story is the first time `runner.mjs` itself handles a secret, so hold the same no-print bar.
- Conformance IT (`CodexRunnerImageConformanceIT.java`): builds mock image (`INSTALL_CODEX_CLI=false`),
  injects `CODEX_API_KEY=<SECRET_SENTINEL>` (`:161`), asserts negative-leak (`:198-209`). Your
  subscription test mirrors this with `CODEX_AUTH_JSON`.

### Logging Requirements (project-wide standard)

Shell-entrypoint adaptation (no SLF4J): structured single-line `[codex-runner] [LEVEL]` diagnostics to
the container's own stderr, redacted by the backend (story 3.6) on capture. **Forbidden in output:**
the `CODEX_AUTH_JSON` value, auth.json contents, any token, full bundle payloads. Auth mode logged by
name + presence + path only. The conformance IT's negative-leak assertion is the enforcing test.

### Project Structure Notes

- No new module, no new dependency, no Flyway migration, no REST/OpenAPI/`schema.d.ts` change.
- The Docker-backed conformance IT must stay out of the fast unit tier (existing `excludedGroups`).

### References

- [Source: docs/superpowers/specs/2026-06-03-codex-subscription-auth-json-design.md (approved design)]
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3a-3]
- [Source: _bmad-output/implementation-artifacts/3-4-claude-runner-image-dockerfile-and-entrypoint-and-contract-conformance.md (DONE — subscription-first dual-mode template + matched-name-injection deviation + Review Findings)]
- [Source: _bmad-output/implementation-artifacts/3-3-codex-runner-image-dockerfile-and-entrypoint-and-contract-conformance.md (DONE — Codex image conventions)]
- [Source: runners/codex/entrypoint.sh (:37-42 cleanup, :247-278 auth block, :56-78 help) + lib/runner.mjs (:100-109, :296-311) + test/mock-codex.sh + README.md]
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java (:132-147 defaultSecretEnvNames, :164 secretEnvNamesFor)]
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerSecretsService.java (:73-116 matched-name injection)]
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerSecretScanService.java (:115-121 literal-substring detector)]
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java (:41,:85-87 CHECK_RUNNER_SECRETS) + adapters/diagnostics/DoctorProbeAdapter.java (:596 probeRunnerSecrets)]
- [Source: deliveryline-backend/src/main/resources/application.yml (:144-147 secret-env-names.codex) + src/test/resources/application.yml]
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java (mirror for the subscription scenario)]
- [Memory: validated-config-needs-test-yaml, rtk-hook-only-matches-bash, wsl-linux-ci-reproduction, runner-image-ci-root-context, springboot-testcontainers-test-must-be-IT, verify-ci-fixes-in-clean-env]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) via bmad-dev-story.

### Debug Log References

- Local entrypoint smoke (git-bash `sh`, mock/probe CLI) — subscription path: exit 0, auth.json
  materialized with sentinel, `OPENAI_API_KEY` NOT exported, no leak across all outputs, auth.json
  cleaned on exit, log line `name=CODEX_AUTH_JSON mode=subscription`.
- Malformed `CODEX_AUTH_JSON` → exit 21 + schema-valid failure result (`runner_non_zero_exit`,
  `outcome=failure`), no leak. API-key-only → exit 0, `OPENAI_API_KEY` exported (byte-for-byte path),
  no auth.json. No credential → exit 20 naming the preferred variable.
- `node --test runners/codex/test/runner-materialize-auth.test.mjs` → 7/7 pass.

### Completion Notes List

- **Backend is config-only — the name-driven secret pipeline lit up for free.** Prepending
  `CODEX_AUTH_JSON` to `secret-env-names.codex` (both yamls + `RunnerProperties.defaultSecretEnvNames()`)
  makes `RunnerSecretsService` (first-present-wins, matched-name injection), `DoctorProbeAdapter`
  (`probeRunnerSecrets`, any-name PASS), and `RunnerSecretScanService` (literal-substring detector,
  raw-JSON) all work with ZERO production logic change. Only the doctor remediation *string* changed.
- **Subscription-first ripple:** the preferred Codex missing-credential hint is now `CODEX_AUTH_JSON`
  (was `CODEX_API_KEY`) — updated `RunnerSecretsServiceTest.blankValueIsTreatedAsMissing` accordingly.
- **New runner-image logic = the whole story.** `runner.mjs materialize-auth` (reads
  `process.env.CODEX_AUTH_JSON`, validates non-empty JSON object, atomic `0600` write, never prints
  the value, exit 21 on malformed); entrypoint subscription branch (set/export `CODEX_HOME`,
  materialize, skip `OPENAI_API_KEY`, `build-failure`+exit 21 on failure, name/presence/path log);
  `cleanup()` rm of the auth.json; `print_help`/header/README/RUNNER_CONTRACT updates. API-key path
  preserved byte-for-byte.
- **Chosen exit code = 21** (new auth-family code, distinct from exit 20 "no credential present") so
  the failure-result summary is unambiguous ("Codex auth.json is malformed"). Recorded in the
  entrypoint header, `runner.mjs` header, README exit-code table, and `RUNNER_CONTRACT.md`.
- **Test surface:** `runner.mjs materialize-auth` got a new node:test unit tier
  (`runners/codex/test/runner-materialize-auth.test.mjs`, 7 cases incl. 0600/negative-leak — runnable
  via `node --test`); backend Java tiers extended (RunnerProperties, RunnerSecretScanService, doctor
  PASS-on-CODEX_AUTH_JSON-only); the conformance IT got a subscription scenario. The entrypoint
  branch + 0600 + cleanup were verified locally via git-bash smoke (the conformance IT enforces it in
  the Docker tier on CI/WSL2).
- **Gates (PowerShell, [[rtk-hook-only-matches-bash]]):** full fast Surefire **751/0/0/11skip**,
  spotless:apply (3 files) + checkstyle:check **0 violations**, runner.mjs unit **7/7**.
- **NOT run locally (no Docker-on-Linux here):** `CodexRunnerImageConformanceIT` (`docker-runner-it`
  tier, excluded from the no-Docker PR tier) — it compiled clean in `test-compile`. Recommend a WSL2
  Ubuntu / Docker CI confirm before merge ([[wsl-linux-ci-reproduction]], [[verify-ci-fixes-in-clean-env]]).
- No new Maven/npm dependency, no Flyway migration, no REST/OpenAPI/`schema.d.ts` change, no
  runner-contracts schema change (so no stale-.m2 risk — [[runner-contracts-schema-stale-in-m2]]).

### File List

**Backend (production):**
- `deliveryline-backend/src/main/resources/application.yml` (secret-env-names.codex + comment)
- `deliveryline-backend/src/test/resources/application.yml` (same — shadow yaml)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java` (defaultSecretEnvNames + javadoc)
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java` (CHECK_RUNNER_SECRETS remediation text)

**Backend (tests):**
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerPropertiesTest.java` (new codex subscription-first assertion)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerSecretsServiceTest.java` (preferred-missing-var hint → CODEX_AUTH_JSON)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerSecretScanServiceTest.java` (new auth.json leak case)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapterTest.java` (new CODEX_AUTH_JSON-only PASS)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java` (new subscription scenario + captureContainerLogs helper)

**Runner image:**
- `runners/codex/lib/runner.mjs` (new `materialize-auth` subcommand, chmodSync import, header)
- `runners/codex/entrypoint.sh` (subscription branch, cleanup trap, print_help, header, exit 21)
- `runners/codex/README.md` (Subscription authentication section, env-var + exit-code tables)
- `runners/codex/test/runner-materialize-auth.test.mjs` (NEW — node:test unit tier)
- `runners/RUNNER_CONTRACT.md` (Codex-specific subscription credential convention)
- `.env.example` (CODEX_AUTH_JSON documented)

### Change Log

| Date | Change |
|------|--------|
| 2026-06-03 | Story 3a-3 created via bmad-create-story (`backlog → ready-for-dev`) from the approved design spec `docs/superpowers/specs/2026-06-03-codex-subscription-auth-json-design.md`. |
| 2026-06-03 | bmad-dev-story (`ready-for-dev → in-progress → review`): implemented Codex subscription auth via `auth.json`. Config-only backend touch (subscription-first `secret-env-names.codex` in both yamls + `RunnerProperties` + doctor remediation), new `runner.mjs materialize-auth` (0600 atomic, exit 21, no-leak), entrypoint subscription branch + cleanup trap, docs (README/RUNNER_CONTRACT/.env.example), and test surface (runner.mjs node unit tier, conformance IT subscription scenario, RunnerProperties/SecretScan/doctor Java tests). 11 ACs + Logging; 7 tasks. Gates green via PowerShell: fast Surefire 751/0/11skip, spotless+checkstyle 0, runner.mjs 7/7. Conformance IT (Docker tier) compiled, not run locally — recommend WSL2/Docker CI confirm. |

## Review Findings

Adversarial code review (bmad-code-review, 2026-06-03) — 3 layers (Blind Hunter, Edge Case Hunter,
Acceptance Auditor). Acceptance Auditor verified **all 11 ACs + Logging FULLY MET in code**. Outcome:
0 decision-needed, 3 patch, 1 defer, 12 dismissed (false-positive / by-design / out-of-scope).

**Patch findings (unchecked):**

- [x] [Review][Patch] **FIXED** — `auth.json` 0600 is non-atomic — created at umask mode (≈0644) then chmod'd, and chmod is best-effort so a chmod failure silently leaves the secret world/group-readable while the run proceeds (contradicts the documented "mode 0600" guarantee) [runners/codex/lib/runner.mjs commandMaterializeAuth + writeAtomically] — FIX APPLIED: `writeAtomically` now takes an optional `mode`; `commandMaterializeAuth` passes `0o600` so the temp file is created+chmod'd restricted and atomically renamed into place (no world-readable window, no silent chmod-failure gap), and a partial temp file is `rmSync`'d on write failure. Verified: node unit tier 7/7 (incl. the 0600-write/no-leak case). (MEDIUM; blind+edge)
- [x] [Review][Patch] **FIXED** — A valid credential written into an unwritable `CODEX_HOME` exits `40` inside `runner.mjs`, but the entrypoint masks every non-zero `materialize-auth` exit to `21` with summary "Codex auth.json is malformed" — a misleading diagnosis for a destination/filesystem problem (the JSON was fine) [runners/codex/entrypoint.sh subscription branch + runner.mjs writeAtomically `fail(40,…)`] — FIX APPLIED: the `build-failure --summary` is now "Codex subscription auth.json could not be materialized (malformed/empty value or unwritable CODEX_HOME)", accurate for both cases. Verified: `bash -n` syntax OK. (LOW; blind+edge)
- [x] [Review][Patch] **FIXED** — Bare `$HOME` under `set -eu` in `CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"` aborts the script with an opaque unbound-variable error if `HOME` is ever unset/empty — the one expansion in the new code not guarded like the rest of the entrypoint [runners/codex/entrypoint.sh:278] — FIX APPLIED: `${CODEX_HOME:-${HOME:-/home/codex}/.codex}`. Verified: `set -eu; unset HOME` no longer aborts the default expansion. (LOW; blind+edge)

**Deferred findings:**

- [x] [Review][Defer] No committed CI test exercises the entrypoint's subscription branch (skip-`OPENAI_API_KEY`-export, `cleanup()` rm of `auth.json`) or the real Codex CLI consuming `$CODEX_HOME/auth.json` — the conformance IT runs the mock CLI (which ignores env + auth files) and pins only the in-scope materialize-log-line + negative-leak guarantees; the branch behaviors were verified only via local git-bash smoke [CodexRunnerImageConformanceIT.java / entrypoint.sh] — deferred: real subscription execution is explicitly story 3.8 scope (locked decision #3) and the dev already recommends a WSL2/Docker CI confirm before merge. (edge+auditor)

All 3 patches were fixed automatically and verified (node unit tier 7/7, `bash -n` syntax OK, `$HOME`-guard smoke). Status `review → done`. The deferred CI-confirm item still stands: run the `docker-runner-it` conformance tier on WSL2/Docker-Linux before merge.

**Notable dismissals (12 — recorded for audit):** SIGKILL leaves `auth.json` on the ephemeral container layer (uncatchable signal; teardown is the mitigation; file is outside quarantined mounts); `auth.json` outside the `input/output/logs` scan surface (by design — it IS the credential, intentionally outside mounts); JSON value adds a `SECRET_FIELD` scan category (leak still detected; test uses `.contains`; no downstream breakage shown); no API-key fallback when subscription is malformed (production injects exactly one credential — unreachable; subscription-first is by design); `--out true` parseArgs sentinel (pre-existing; entrypoint always passes a real path); no symlink/path-traversal guard on `--out` (operator owns the whole container — no trust boundary crossed); `CODEX_HOME` dir created 0755 (single-user ephemeral container; file is 0600); doctor reports "present" without JSON-validity (consistent with the name-only probe, same as API keys); scanner re-derives only the resolved credential (production injects one — complete coverage); single-line-JSON unenforced (fails safely; folded into patch #2's diagnostic note); real-CLI consumption untested (explicitly story 3.8); `fail(40)` write-error path confirmed to NOT leak the value (uses path + `error.message` only).
