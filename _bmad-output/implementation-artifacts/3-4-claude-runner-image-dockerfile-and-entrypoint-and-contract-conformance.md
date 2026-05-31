# Story 3.4: Claude Runner Image — Dockerfile + Entrypoint + Contract Conformance

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **runner-infrastructure developer**,
I want **a `runners/claude/` Dockerfile + entrypoint script that wraps the Claude Code CLI as a runner container conforming to the runner-contracts v1 schema, mirroring story 3.3's Codex structure exactly, plus a shared `runners/RUNNER_CONTRACT.md` documenting the conventions both runners obey**,
so that **the runner broker has two interchangeable real runner kinds selectable by config, and the v1 contract is validated against two independent implementations (catching contract gaps a single runner could hide)**.

## Acceptance Criteria

1. **Given** `runners/claude/Dockerfile`, **Then** it produces an image tagged `deliveryline/claude-runner:latest` (with a build-arg version tag for CI) based on a documented base image (likely `node:22-slim` or whatever the Claude Code CLI requires) and installs the Claude Code CLI via a documented version pin.
2. **Given** `runners/claude/entrypoint.sh`, **Then** the entrypoint mirrors story 3.3's contract: read input bundle, validate `schemaVersion`, invoke the Claude Code CLI with extracted inputs + secrets, emit `runner-result.v1.json`, write raw stdout/stderr to logs, exit with documented codes — **same** workspace conventions, **same** artifact-variant emission, **same** least-privilege posture.
3. **Given** the **shared contract** between Codex and Claude runners (architecture project-structure ownership boundary: "runners/codex and runners/claude share the same environment variable, mount, context file, result file, and diagnostics contract"), **Then** a `runners/RUNNER_CONTRACT.md` lists every shared convention (env vars, mounts, file paths, exit codes, diagnostic flags); **both** runner READMEs reference it; deviations require updating the shared contract first.
4. **Given** runner-specific behavior, **Then** Claude-specific configuration (model name, max tokens, prompt template) is read from **environment variables documented in `runners/claude/README.md`** and consumed by the entrypoint — **never baked into the image as constants**.
5. **Given** the unified `docker-compose.yml`, **Then** the Claude image is declared as a `build` target alongside Codex — `docker compose build` rebuilds both.
6. **Given** image smoke testing, **Then** `docker run --rm deliveryline/claude-runner:latest --self-test` exits 0 with a documented self-test summary — same convention as Codex (story 3.3 AC8).
7. **Given** failure injection per story 3.3 AC11, **Then** the Claude entrypoint also supports `--simulate-failure={timeout|crash|contract_violation}` with the same flag set, gated behind the same test-only env var — enabling cross-runner contract tests.
8. **Given** runner-kind selection via configuration (`deliveryline.runner.docker.default-kind=codex|claude`), **Then** swapping kinds requires **no code changes** — only a config value change + the corresponding image being available; a contract test asserts the Claude image produces, for each stage, a `runner-result.v1`-conformant document with the same `artifactType` mapping as Codex (content differs, schema + `artifactType` match). The full single-test Codex-vs-Claude **parity** assertion is story 3.8 (AC11).
9. **Given** `runners/claude/README.md`, **Then** it documents the same items as story 3.3 AC10 (base-image rationale, CLI version pin + upgrade, env vars consumed, mount paths, exit-code table, image size/layers, local test recipe) **plus** Claude-specific configuration (model selection, prompt-template location, rate-limiting considerations).
10. **Given** the cross-runner consistency principle, **Then** any change to the input-bundle schema, output schema, mount paths, or exit codes must update **both** runner Dockerfiles and entrypoints in the same PR; `runners/RUNNER_CONTRACT.md` records this obligation. A CI check that builds **both** images and runs `--self-test` on every PR touching `runner-contracts/.../schemas/` extends story **3.28/3.34** and is out of scope here — this story delivers the conformance test + the documented obligation (see Dev Notes "Deferred: AC10 CI wiring").
11. **Given** parity testing, **Then** an integration test (story **3.8**) runs the same fixture scenario against both runners and asserts both produce schema-conformant output even if content differs — **this story does not author that test** (it ships the Claude conformance IT that 3.8 will pair with the Codex one).

## Tasks / Subtasks

- [x] **Task 1: Claude Dockerfile — mirror `runners/codex/Dockerfile`** (AC: 1, 2, 9)
  - [x] Replace the `runners/claude/Dockerfile` stub (`FROM scratch`) with a real image. **Copy `runners/codex/Dockerfile` verbatim and change only the runner-specific parts** — the structure (single-stage, ARG-pinned CLI, `INSTALL_*_CLI` mock toggle, OCI labels, non-root user, exec-form ENTRYPOINT, one-layer install) is the proven template.
  - [x] Base: `node:22-slim` (the Claude Code CLI is the npm package `@anthropic-ai/claude-code`, needs Node at run time — same rationale as Codex; **confirm package name + current version with `npm view @anthropic-ai/claude-code version` at build time** — distribution names change, don't trust this doc).
  - [x] Pin via `ARG CLAUDE_CLI_VERSION=<pinned>` (no floating `latest`); `ARG IMAGE_VERSION=latest` for the CI OCI label; `ARG INSTALL_CLAUDE_CLI=true` (false → bake the mock for the conformance build).
  - [x] Non-root user **`claude:1001`** — uid/gid **1000 is already taken** by `node:22-slim`'s own `node` user (this exact gotcha cost 3.3 a debug cycle; AC2 says "e.g. claude:1000" — the value is not a backend contract, 1001 is correct).
  - [x] `COPY` the entrypoint, `lib/runner.mjs`, and `test/mock-claude.sh`; `chmod +x`; set `ENV CLAUDE_CLI_VERSION` / `CLAUDE_CLI_BIN=claude` / `DELIVERYLINE_RUNNER_LIB`; OCI labels `title=deliveryline-claude-runner`.
  - [x] Document final image size + layer count in the README (AC9 — must stay under the ~1 GB budget; if heavier than Codex, justify).
- [x] **Task 2: Claude entrypoint — contract conformance** (AC: 2, 3, 6, 7)
  - [x] Replace the `runners/claude/entrypoint.sh` stub (`#!/bin/sh`) by **copying `runners/codex/entrypoint.sh`** and changing only: log prefix `[claude-runner]`, the CLI bin (`claude`), the auth block (Task 5), and the invocation seam (Task 4). Keep POSIX `sh` (dash-compatible), the same arg parsing, the same `map_stage`, the same exit-code table.
  - [x] **Filenames are the LIVE backend contract — use the `.v1` infix:** read `/workspace/input/context-bundle.v1.json`, write `/workspace/output/runner-result.v1.json`. (The epic prose says `context-bundle.json`/`runner-result.json` without `.v1`; the **code** — `LocalRunnerWorkspaceStore.CONTEXT_BUNDLE_FILENAME`/`RUNNER_RESULT_FILENAME` — is authoritative. A result under any other name reads back as `RUNNER_CRASH`.)
  - [x] **Stage resolution is `--stage` arg → `DELIVERYLINE_RUNNER_STAGE` env → optional `bundle.stage`** (the frozen `context-bundle.v1` schema is `additionalProperties:false` so it carries no `stage`; the `deliveryline.stage` Docker label is not readable inside the container). Map the three story stages + `RunnerStage` enum aliases identically to Codex: `spec-investigation|investigation|spec → spec`, `implementation-plan|plan|implementationPlan → implementationPlan`, `pr-output|execution|prOutput → prOutput` (AC2 same-emission requirement).
  - [x] **Result-file-or-bust:** always write the schema-valid result file before `exit 0`; failure branches (auth, command-config, CLI non-zero) write a **schema-valid failure result** via `runner.mjs build-failure` (so an actionable failure isn't collapsed into `RUNNER_CRASH`). Never write outside `/workspace/output` + `/workspace/logs`.
- [x] **Task 3: `lib/runner.mjs` — copy + de-Codex the content-only fields** (AC: 2, 3)
  - [x] Copy `runners/codex/lib/runner.mjs` to `runners/claude/lib/runner.mjs`. **The schema shape stays byte-for-byte identical** (this IS the shared contract). Change only content-not-contract fields: the `prOutput` branch prefix `codex/${runnerExecutionId}` → `claude/${runnerExecutionId}`, default summary text `Codex …` → `Claude …`, header comments. Keep `prepare`/`build`/`build-failure`/`build-invalid`, atomic write, SHA-256 checksum, `artifactId` derivation, and the spec `contentReference` materialization (`output/artifacts/{workflowRunId}/spec.md`) unchanged.
- [x] **Task 4: `--self-test` + failure injection + Claude invocation seam** (AC: 4, 6, 7)
  - [x] `--self-test`: verify node + helper present, the **`claude` CLI is on PATH and its `--version` contains the expected pin**, mounts are detectable; print the documented summary; exit 0; no network, no API call. (3.3's self-test verifies the version + mount presence — match it.)
  - [x] `--simulate-failure={timeout|crash|contract_violation}` gated behind `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true` — same three behaviors (timeout=sleep, crash=exit 50 with NO result file, contract_violation=write schema-invalid result then exit 0).
  - [x] **Claude invocation seam (AC4 — config from env, never baked):** the real CLI argument vector is finalized in story 3.8, so expose a documented seam `CLAUDE_EXEC_ARGS` (default a single safe token — apply the **same `*[!A-Za-z0-9_./:=,-]*` safe-token guard 3.3 added** so the seam can't be shell-injected; log arg **count** only). Read Claude-specific knobs from env and pass them through (never as image constants): `CLAUDE_MODEL` (e.g. maps to `--model`/`ANTHROPIC_MODEL`), `CLAUDE_MAX_TOKENS`, `CLAUDE_PROMPT_TEMPLATE` (path under a read-only/allowed mount). Document each in the README (AC9). Prompt temp file goes under `/workspace/logs` (3.3 review fix — never outside the rw mounts).
- [x] **Task 5: Secrets / auth wiring — TWO auth modes: API key OR subscription token (consume story 3.5 contract — do NOT re-implement)** (AC: 2, 4)
  - [x] The Claude credential arrives as a **container env var** already injected by the backend at dispatch (story 3.5 done — `RunnerSecretsService` injects the configured `secret-env-names.claude` into `CreateContainerSpec.environment`). The entrypoint resolves auth from the **first present of: (1) `CLAUDE_CODE_OAUTH_TOKEN` (subscription mode), then (2) `ANTHROPIC_API_KEY` (API-billing mode)**.
  - [x] **Subscription mode (`CLAUDE_CODE_OAUTH_TOKEN`) — the cost-saving path the team wants:** the Claude Code CLI honors an OAuth token generated by `claude setup-token` and billed against a Claude **Pro/Max subscription** instead of per-token API credits. Prefer it when present. The entrypoint exports it for the CLI and **never logs the value** (name + presence only). Document the `claude setup-token` generation procedure in the README (AC9) + `.env.example`.
  - [x] **API mode (`ANTHROPIC_API_KEY`)** — the fallback when no subscription token is set (no `OPENAI_*` fallback, unlike Codex). The CLI reads it directly from env.
  - [x] **Backend config update required (allowed — config, not the DO-NOT-EDIT adapter):** add `CLAUDE_CODE_OAUTH_TOKEN` to `deliveryline.runner.secret-env-names.claude` in `application.yml` **and** `RunnerProperties.defaultSecretEnvNames()` so the broker actually injects it — list it **before** `ANTHROPIC_API_KEY` to match the entrypoint preference: `claude: [CLAUDE_CODE_OAUTH_TOKEN, ANTHROPIC_API_KEY]`. Story 3.5 made this list config-driven precisely for this; doctor's per-kind presence probe (story 3.5 AC8) then reports PASS when **either** is set. Update the story-3.5 `RunnerProperties`/`application.yml` test expectations (and `.env.example`) accordingly — confirm the validated-config test yaml is updated too ([[validated-config-needs-test-yaml]]).
  - [x] On a real (non-self-test, non-simulate) run with **neither** credential present, write a schema-valid failure result and exit `20`, naming the **variable names only** — never echo a value. `DELIVERYLINE_RUNNER_SKIP_AUTH=true` lets mock/test runs proceed keyless.
  - [x] Log only the variable **name + presence** of whichever credential resolved (match the backend's count-only discipline); the negative-log assertion (Task 8) must inject a sentinel via **both** `CLAUDE_CODE_OAUTH_TOKEN` and `ANTHROPIC_API_KEY` and prove neither value reaches the logs/result.
- [x] **Task 6: `runners/RUNNER_CONTRACT.md` (shared) + Claude README + Codex README cross-link** (AC: 3, 9)
  - [x] **NEW `runners/RUNNER_CONTRACT.md`** — the single source of truth for every shared convention both runners obey: the three mounts + modes, `context-bundle.v1.json`/`runner-result.v1.json` filenames, the `schemaVersion=1` rule, the stage→`artifactType` table + the `--stage`/`DELIVERYLINE_RUNNER_STAGE`/`bundle.stage` resolution order, the exit-code table (10/11/12/13/20/30/40/50 + 0/1/2), the diagnostic flags (`--self-test`, `--simulate-failure`, `--help`), the shared env-var names (`DELIVERYLINE_RUNNER_STAGE`/`_CORRELATION_ID`/`_ALLOW_SIMULATE_FAILURE`/`_SKIP_AUTH`), and the **"change the schema → update BOTH runners in the same PR"** rule (AC10).
  - [x] **`runners/claude/README.md`** (replace stub): all of AC9 — base/CLI rationale + pin/upgrade, env vars (incl. `ANTHROPIC_API_KEY` + the `CLAUDE_MODEL`/`CLAUDE_MAX_TOKENS`/`CLAUDE_PROMPT_TEMPLATE` config from AC4 + rate-limiting note), mount paths, exit-code table, image size/layers, local test recipe — and **reference `../RUNNER_CONTRACT.md`** for the shared conventions rather than duplicating them.
  - [x] **`runners/codex/README.md`** (small edit — AC3 requires BOTH READMEs reference the shared doc): add a one-line link to `../RUNNER_CONTRACT.md`. This is the only intentional touch of a 3.3 deliverable.
- [x] **Task 7: docker-compose build target** (AC: 5)
  - [x] Add a `claude-runner` service to `docker-compose.yml` alongside `codex-runner`: `build: { context: ., dockerfile: runners/claude/Dockerfile }`, `image: deliveryline/claude-runner:latest`. Build-only image target; `docker compose build` rebuilds **both** runner images (AC5). Keep the single-file compose convention.
- [x] **Task 8: Contract-conformance test** (AC: 6, 8)
  - [x] Add `ClaudeRunnerImageConformanceIT` (mirror `deliveryline-backend/.../runner/CodexRunnerImageConformanceIT.java`): build the image once with `--build-arg INSTALL_CLAUDE_CLI=false` (deterministic mock CLI), run the self-test + all three artifact variants against the existing valid `context-bundle.v1.valid.json`, validate each `runner-result.v1.json` via `RunnerContractValidator.validate(RUNNER_RESULT, …)`, assert the golden variant shape, assert the spec `contentReference` payload is materialized, and the **negative secret-log assertion** (inject an `ANTHROPIC_API_KEY` sentinel; assert it never appears in `runner.stdout`/`.stderr`/the result).
  - [x] Reuse the existing valid fixtures under `deliveryline-runner-contracts/src/test/resources/fixtures/valid/` — do NOT author new shapes. Pass the stage via `DELIVERYLINE_RUNNER_STAGE` (production dispatch seam), mount input `:ro`, output/logs `:rw` world-writable for the non-root uid, `--network=none`.
  - [x] Tag `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable` so it runs only in the Docker-tier (excluded from the no-Docker PR tier via the existing `excludedGroups`). Real Claude-API execution + the single-test Codex-vs-Claude parity are story 3.8.
- [x] **Logging instrumentation** (cross-cutting; adapted for a shell entrypoint + image story)
  - [x] The entrypoint emits structured, parseable diagnostic lines (prefix `[claude-runner]`) at: start, schemaVersion validation, stage resolution, auth resolution (name+presence only), Claude invocation start/finish (rc + stdout byte count), result-file write, and every failure/exit branch.
  - [x] If the bundle carries a `correlationId` (surfaced by `runner.mjs prepare` / `DELIVERYLINE_CORRELATION_ID`), prepend it to log lines.
  - [x] **Never** print secret values (`ANTHROPIC_API_KEY`), auth headers, full bundle payloads, or the raw `CLAUDE_EXEC_ARGS`/prompt — names + counts only.
  - [x] The conformance test's negative-log assertion (Task 8) pins the no-leak contract.

### Review Findings

- [x] [Review][Patch] AC5 compose command semantics conflict with runner profiles [docker-compose.yml:28, docker-compose.yml:38] — decision resolved 2026-05-31: removed runner profiles so plain `docker compose build` includes both runner images.
- [x] [Review][Patch] `CLAUDE_PROMPT_TEMPLATE` is documented but not consumed [runners/claude/entrypoint.sh:303]
- [x] [Review][Patch] Docker conformance never exercises `ANTHROPIC_API_KEY`-only fallback mode [deliveryline-backend/src/test/java/org/dradgo/adapters/runner/ClaudeRunnerImageConformanceIT.java:167]
- [x] [Review][Patch] `--self-test` version check accepts substring matches such as `2.1.1490` for pin `2.1.149` [runners/claude/entrypoint.sh:135]
- [x] [Review][Patch] Doctor remediation still omits `CLAUDE_CODE_OAUTH_TOKEN` after Claude became subscription-first [deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java:80]
- [x] [Review][Defer] Real Claude execution still conflicts with the inherited `--network=none` runner contract [runners/RUNNER_CONTRACT.md:26] — deferred, pre-existing

## Dev Notes

### ⚠️ Critical context before you start

- **This is a runner-infrastructure story (Dockerfile + POSIX shell entrypoint + Node helper + one conformance test), NOT backend Java business logic.** The only Java you write is `ClaudeRunnerImageConformanceIT`.
- **Story 3.4 is the Claude twin of story 3.3 (Codex), which is DONE and is your template.** The fastest correct path is: copy each `runners/codex/*` file, change only the runner-specific bits, and keep the **shared contract byte-identical**. Do not re-derive the contract — 3.3 already paid that discovery cost. Read `_bmad-output/implementation-artifacts/3-3-codex-runner-image-dockerfile-and-entrypoint-and-contract-conformance.md` (esp. its **Completion Notes** + **Review Findings**) before writing anything — every correction it lists, you inherit.
- **The `runners/claude/` files exist as 1-line stubs awaiting this story** — *fill them in*, don't "create from scratch":
  - `runners/claude/Dockerfile` → currently `FROM scratch`
  - `runners/claude/entrypoint.sh` → currently `#!/bin/sh`
  - `runners/claude/README.md` → currently `Claude runner image — populated in story 3.4.`
- **The backend half is already done and is your contract surface — DO NOT modify it, conform to it:**
  - `DockerRunnerAdapter` (story 3.1/3.2) selects the image via `runnerProperties.docker().imageTagFor(CLAUDE)` → `deliveryline/claude-runner:latest`; bind-mounts `/workspace/input` **ro**, `/workspace/output` + `/workspace/logs` **rw**; launches `--network=none`; classifies **no result file ⇒ `RUNNER_CRASH`** regardless of exit code.
  - **Secrets are already injected as env by story 3.5 (done).** For Claude you consume `ANTHROPIC_API_KEY`; you never mount/read a secret file, never put a secret into the result or logs.

### What is genuinely NEW in 3.4 vs a straight copy of 3.3

1. **`runners/RUNNER_CONTRACT.md` (AC3)** — does not exist yet. 3.3 shipped only `runners/codex/README.md`. This story extracts the shared conventions into one doc and links both READMEs to it.
2. **Claude-specific config from env (AC4)** — Codex had only `CODEX_BASE_URL`/`CODEX_EXEC_ARGS`. Claude adds `CLAUDE_MODEL` / `CLAUDE_MAX_TOKENS` / `CLAUDE_PROMPT_TEMPLATE`, read from env and documented, **never baked as constants**. A test/lint check that these are env-driven (not hardcoded) satisfies AC4.
3. **Dual-mode auth, subscription-first (Task 5)** — Claude supports **two** credentials: `CLAUDE_CODE_OAUTH_TOKEN` (subscription / Pro-Max billing, preferred — the cost-saving path) then `ANTHROPIC_API_KEY` (API-credit billing, fallback). Codex by contrast chained `CODEX_API_KEY`→`OPENAI_API_KEY`. This **adds `CLAUDE_CODE_OAUTH_TOKEN` to the story-3.5 `secret-env-names.claude` config** so the broker injects it.
4. **Cross-runner consistency (AC10)** — the "schema change → update both runners" rule is documented in `RUNNER_CONTRACT.md`; the **CI workflow** that enforces it on schema-touching PRs extends story 3.28/3.34 and is **deferred** (see below).

### Architecture Patterns and Constraints

- **File-based contract only (story 3.1 AC8):** three mounts + two contract files. No HTTP, no socket; `--network=none` enforced by the backend at create time.
- **Versioned contract:** input `schemaVersion: 1`; output validates against `runner-result.v1.schema.json`. Reject unknown input versions (exit 12); never emit an unversioned/mis-named result.
- **Least privilege (AC2/3.3 AC7):** non-root `claude:1001`, no network by default, secret from env never echoed.
- **Determinism in tests (AC8):** the conformance test must NOT call the real Claude API — bake a deterministic mock CLI (`INSTALL_CLAUDE_CLI=false`); real-API execution is story 3.8 (profile-gated).
- **Untrusted-output downstream:** the backend re-validates + redacts your `runner-result.v1.json`. Produce a well-formed, schema-conformant document; don't assume your output is trusted.

### Inherited corrections from story 3.3 (apply all — do not rediscover)

- Filenames carry the **`.v1` infix** (`context-bundle.v1.json` / `runner-result.v1.json`) — the code, not the prose, is authoritative.
- **Stage cannot come from the bundle or the Docker label** → `--stage` arg → `DELIVERYLINE_RUNNER_STAGE` env → optional `bundle.stage`. Backend wiring of `DELIVERYLINE_RUNNER_STAGE` into the container is **story 3.8** (adapter is DO-NOT-EDIT here); honor the env seam.
- **uid/gid 1000 is occupied** by `node:22-slim` → use `claude:1001`.
- **Single-stage Dockerfile** (the CLI is an interpreted npm-global package; a builder/runtime split yields no win and risks clobbering `/usr/local/bin` node/npm symlinks).
- **jq-free** (`node` present, `jq` absent) → all JSON read/build in `lib/runner.mjs`; entrypoint stays POSIX `sh`.
- Failure branches write a **schema-valid failure result** (`build-failure`) so actionable failures don't collapse to `RUNNER_CRASH`.
- The CLI-args seam is **shell-safe-token-guarded** and **logged by count only**; the prompt temp file lives under `/workspace/logs`.
- The conformance test builds via the **docker CLI** (`docker build -f runners/claude/Dockerfile .`) — docker-java's `buildImageCmd` mishandles an out-of-cwd Dockerfile context tar; keep only the container RUNS on docker-java.

### Deferred: AC10 CI wiring

AC10's "a CI check verifies both runner images build + pass `--self-test` on every PR touching `runner-contracts/.../schemas/`" extends **story 3.28** (CI flake/tier control) and overlaps **story 3.34** (CI tier — real docker runner image build + compatibility checks) — both `backlog`, both NOT done. This story delivers: (a) the documented obligation in `RUNNER_CONTRACT.md`, (b) the `ClaudeRunnerImageConformanceIT` that the CI tier will invoke. The actual GitHub-Actions/CI workflow wiring is out of scope and owned by 3.28/3.34 — note this in the README so the obligation isn't silently dropped.

### Claude Code CLI — open decision (recommendation)

The Claude Code CLI is published on npm as **`@anthropic-ai/claude-code`** with binary **`claude`**; non-interactive ("print") mode is `claude -p`. **Recommendation:** `node:22-slim` + `npm i -g @anthropic-ai/claude-code@<pinned>` via `ARG CLAUDE_CLI_VERSION`. **Confirm the package name + current version with `npm view @anthropic-ai/claude-code version` at build time** rather than trusting this doc (CLI distribution names/versions change). Record the exact install command + rationale in the README (AC9). If the CLI needs a heavier base, justify the size (AC9).

### Web / latest-tech check

- No new runtime dependency lands in the backend. Pin the Claude CLI version explicitly (no floating tags) for reproducible CI; document the upgrade procedure (AC9). The model name is a **runtime env var** (`CLAUDE_MODEL`), not a build constant (AC4) — default to the latest capable Claude model at dispatch time, set by the backend/operator, not baked here.

### Existing files / contract surfaces (verified by reading them)

```
runners/claude/Dockerfile          # STUB `FROM scratch` — FILL IN (Task 1)
runners/claude/entrypoint.sh       # STUB `#!/bin/sh` — FILL IN (Task 2,4,5)
runners/claude/README.md           # STUB one-liner — FILL IN (Task 6)
runners/claude/lib/runner.mjs      # NEW — copy from codex (Task 3)
runners/claude/test/mock-claude.sh # NEW — copy from codex mock (Task 1/8)
runners/RUNNER_CONTRACT.md         # NEW — shared contract (Task 6, AC3)
runners/codex/README.md            # small edit: link to ../RUNNER_CONTRACT.md (Task 6, AC3)

runners/codex/Dockerfile / entrypoint.sh / lib/runner.mjs / test/mock-codex.sh / README.md
                                   # DONE (story 3.3) — your verbatim template; mirror, don't re-derive

docker-compose.yml                 # has postgres + codex-runner — ADD claude-runner (Task 7)
.env.example                       # ANTHROPIC_API_KEY documented for Claude (story 3.5) — your auth contract

deliveryline-runner-contracts/
  src/main/resources/schemas/
    context-bundle.v1.schema.json   # INPUT shape (additionalProperties:false — no `stage` field)
    runner-result.v1.schema.json    # OUTPUT shape your entrypoint must satisfy
  src/main/java/org/dradgo/runnercontracts/RunnerContractValidator.java  # use in Task 8 test
  src/test/resources/fixtures/valid/
    context-bundle.v1.valid.json
    runner-result.v1.spec.valid.json
    runner-result.v1.implementation-plan.valid.json
    runner-result.v1.pr-output.valid.json

deliveryline-backend/src/main/java/org/dradgo/
  adapters/runner/DockerRunnerAdapter.java        # DO NOT EDIT — behavioral contract (mounts/network/exit class)
  adapters/runner/docker/CreateContainerSpec.java # env-injection shape (secrets arrive as env)
  application/runner/RunnerProperties.java         # secretEnvNamesFor(CLAUDE)=[ANTHROPIC_API_KEY]; imageTagFor(CLAUDE)
  domain/registry/RunnerKind.java                  # CLAUDE("claude")
deliveryline-backend/src/test/java/org/dradgo/adapters/runner/
  CodexRunnerImageConformanceIT.java               # mirror this exactly for Claude (Task 8)
```

### Key facts pulled from the live backend code (so the entrypoint matches reality)

- `application.yml`: `deliveryline.runner.secret-env-names.claude: [ANTHROPIC_API_KEY]` **today** → this story extends it to `[CLAUDE_CODE_OAUTH_TOKEN, ANTHROPIC_API_KEY]` (subscription-first); `deliveryline.runner.docker.image-tags.claude: deliveryline/claude-runner:latest`.
- `RunnerProperties.defaultSecretEnvNames()` → `CLAUDE → [ANTHROPIC_API_KEY]` today (update to `[CLAUDE_CODE_OAUTH_TOKEN, ANTHROPIC_API_KEY]`); `CODEX → [CODEX_API_KEY, OPENAI_API_KEY]` (unchanged). The list is **ordered** = entrypoint resolution preference.
- `RunnerKind.CLAUDE` registry value is the string `"claude"`.
- Mount constants (`DockerRunnerAdapter`): `/workspace/input` (ro), `/workspace/output` (rw), `/workspace/logs` (rw); `--network=none`.
- Exit classification (`classifyExited`): result file **absent ⇒ `RUNNER_CRASH`**; present ⇒ `Completed`. The **presence of the result file** is the success signal, not the exit code.

### Project Structure Notes

- New artifacts live under `runners/claude/` + `runners/RUNNER_CONTRACT.md` + `docker-compose.yml` (1 service) + one conformance IT (place it beside `CodexRunnerImageConformanceIT` in `deliveryline-backend`, same Docker-tier gating).
- **CI parity:** Docker builds/runs differ on Windows vs the Linux CI runner. Smoke-build + run `--self-test` + the conformance IT on **WSL2 Ubuntu** before pushing, and confirm the Docker-tier test is excluded from the no-Docker PR tier. See [[wsl-linux-ci-reproduction]] and [[verify-ci-fixes-in-clean-env]].
- The Docker-backed conformance test must NOT join the fast unit tier — gate it exactly like `docker-runner-it`.

### Logging Requirements (project-wide standard)

Shell-entrypoint adaptation of the standard (there is no SLF4J here): structured single-line diagnostics to the container's own stderr, redacted by the backend (story 3.6) on capture. Levels via the `[INFO]/[WARN]/[ERROR]` tag in the `log()` helper. **Forbidden in output:** secret values (`ANTHROPIC_API_KEY`), auth headers, full bundle payloads, raw exec-args/prompt. The conformance IT's negative-log assertion is the enforcing test (mirrors 3.3).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.4]
- [Source: _bmad-output/implementation-artifacts/3-3-codex-runner-image-dockerfile-and-entrypoint-and-contract-conformance.md (DONE — verbatim template + inherited corrections + Review Findings)]
- [Source: runners/codex/Dockerfile + entrypoint.sh + lib/runner.mjs + test/mock-codex.sh + README.md (the live 3.3 implementation to mirror)]
- [Source: _bmad-output/implementation-artifacts/3-5-runner-secrets-handling-secure-mount-of-agent-provider-api-keys.md (ANTHROPIC_API_KEY env contract — DONE)]
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java (secretEnvNamesFor / imageTagFor)]
- [Source: deliveryline-backend/src/main/resources/application.yml (secret-env-names.claude, image-tags.claude)]
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java (mounts, network, exit classification — DO NOT EDIT)]
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java (mirror for Claude)]
- [Source: deliveryline-runner-contracts/src/main/resources/schemas/ + src/test/resources/fixtures/valid/]
- [Source: .env.example (ANTHROPIC_API_KEY runner-secret contract) + docker-compose.yml (codex-runner build target to mirror)]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context)

### Debug Log References

- WSL2 Ubuntu non-Docker entrypoint branch matrix (dash): 21/21 — POSIX syntax, `--self-test`, 3
  stages (artifactType + no-leak of both sentinels + OAuth-preferred), API-only resolves
  `ANTHROPIC_API_KEY`, no-credential → exit 20 + schema-valid failure result, unknown stage → exit
  13, simulate gate (2 ungated / 50 gated), `CLAUDE_EXEC_ARGS` injection guard → exit 2.
- `ClaudeRunnerImageConformanceIT` under WSL2 Docker 28.5.1 (mock CLI image): `Tests run: 4,
  Failures: 0, Errors: 0` (self-test + spec/implementationPlan/prOutput each schema-valid via
  `RunnerContractValidator` + golden shape + spec `contentReference` materialized + dual-sentinel
  negative-log).
- Production image (real CLI) on WSL2: `claude --version` = `2.1.149 (Claude Code)` == pin;
  `--self-test` OK; **~147 MB / 10 layers** (under the ~1 GB AC9 budget; smaller than Codex's
  ~652 MB).
- Backend fast tier: `RunnerSecretsServiceTest` 11, `RunnerPropertiesTest` 7, `DoctorProbeAdapterTest`
  26, `DockerRunnerAdapterUnitTest` 24 → 68/0/0. spotless + checkstyle clean.

### Completion Notes List

- **Mirror, not re-derive.** Each `runners/claude/*` file was copied from the DONE `runners/codex/*`
  (story 3.3) and only runner-specific bits changed; the shared contract (mounts, `.v1` filenames,
  `schemaVersion=1`, stage→artifactType table, exit-code table 10/11/12/13/20/30/40/50, diagnostic
  flags, `runner.mjs` schema shape) is byte-identical. New vs 3.3: the shared
  `runners/RUNNER_CONTRACT.md`, dual-mode auth, Claude config-from-env (AC4), and the AC8 kind-swap
  conformance IT.
- **CLI pin.** `@anthropic-ai/claude-code@2.1.149` (the `stable` dist-tag; `latest` was 2.1.158),
  confirmed via `npm view` at authoring time. Binary `claude`; non-root `claude:1001` (1000 taken by
  the base `node` user); single-stage; jq-free (`lib/runner.mjs`).
- **Dual-mode auth (subscription-first).** The entrypoint resolves the first present of
  `CLAUDE_CODE_OAUTH_TOKEN` (Pro/Max subscription token — the cost-saving path) then
  `ANTHROPIC_API_KEY` (API billing), logging name+presence only. No `OPENAI_*` fallback (unlike Codex).
- **DEVIATION from the story's "config-only" backend change (user-approved, AskUserQuestion
  2026-05-31).** Adding `CLAUDE_CODE_OAUTH_TOKEN` ahead of `ANTHROPIC_API_KEY` in
  `secret-env-names.claude` exposed that story-3.5 `RunnerSecretsService` *collapsed* a kind's
  credential onto the first/canonical env-name on injection — fine for Codex's interchangeable
  aliases, but it would inject an `ANTHROPIC_API_KEY` value under the `CLAUDE_CODE_OAUTH_TOKEN` name
  when only the API key is set (an API key masquerading as an OAuth token). Per the user's choice, the
  service now injects each value **under the name it was found under** (matched-name injection); the
  first name remains the *preferred* credential + the missing-secret hint. Updated 3.5 tests
  accordingly (renamed `fallsBackToAlternateSourceInjectedUnderItsOwnName`,
  `preferredNameWinsWhenBothSourcesPresent`; added Claude OAuth-only / API-only-under-its-own-name /
  OAuth-preferred cases; missing-secret hint now `CLAUDE_CODE_OAUTH_TOKEN`). Doctor PASSes on either
  (the per-kind probe already checks presence of any configured name) — verified by the unchanged
  `DoctorProbeAdapterTest`.
- **Claude config from env (AC4).** `CLAUDE_MODEL`→`ANTHROPIC_MODEL`, `CLAUDE_MAX_TOKENS`→
  `CLAUDE_CODE_MAX_OUTPUT_TOKENS`, `CLAUDE_PROMPT_TEMPLATE` (applied via `{{prompt}}` insertion) —
  read at run time, never baked. `CLAUDE_EXEC_ARGS` seam (default `-p`) is safe-token-guarded and logged by count; the
  precise real-Claude arg vector is story 3.8.
- **DO-NOT-EDIT honored.** `DockerRunnerAdapter` / `CreateContainerSpec` unchanged; the entrypoint
  conforms to imageTagFor(CLAUDE)=`deliveryline/claude-runner:latest`, ro/rw mounts, `--network=none`,
  result-file-absent⇒RUNNER_CRASH. The only intentional touch of a 3.3 deliverable is the one-line
  `runners/codex/README.md` link to the shared contract (AC3 requires both READMEs reference it).
- **Deferred (documented, not dropped):** AC10's CI workflow that builds both images + runs
  `--self-test` on schema-touching PRs extends stories 3.28/3.34 (this story ships the obligation in
  `RUNNER_CONTRACT.md` + the conformance IT the tier will invoke). AC11 single-test Codex-vs-Claude
  parity + real-API execution are story 3.8.
- **Gate-to-done note for the reviewer:** all Docker verification above ran on WSL2 Docker from the
  Windows checkout at `/mnt/c`; a fresh-clone WSL2 build is the belt-and-suspenders pre-merge check
  (memory [[wsl-linux-ci-reproduction]]). The `docker-runner-it`-tagged conformance IT is excluded
  from the no-Docker PR tier by the existing `excludedGroups`. Recommend `bmad-code-review` with a
  different LLM.

### File List

**New — Claude runner image (Tasks 1–4, 8):**
- `runners/claude/Dockerfile` (was `FROM scratch` stub)
- `runners/claude/entrypoint.sh` (was `#!/bin/sh` stub)
- `runners/claude/lib/runner.mjs`
- `runners/claude/test/mock-claude.sh`
- `runners/claude/README.md` (was one-line stub)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/ClaudeRunnerImageConformanceIT.java`

**New — shared contract (Task 6, AC3/AC10):**
- `runners/RUNNER_CONTRACT.md`

**Modified — cross-link + compose + backend config (Tasks 5, 6, 7):**
- `runners/codex/README.md` (one-line link to `../RUNNER_CONTRACT.md`)
- `docker-compose.yml` (new `claude-runner` default build target)
- `deliveryline-backend/src/main/resources/application.yml` (`secret-env-names.claude` →
  `[CLAUDE_CODE_OAUTH_TOKEN, ANTHROPIC_API_KEY]`)
- `deliveryline-backend/src/test/resources/application.yml` (same)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java`
  (`defaultSecretEnvNames()` Claude list)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerSecretsService.java`
  (matched-name injection — user-approved deviation)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerSecretsServiceTest.java`
  (updated + new dual-mode cases)
- `.env.example` (`CLAUDE_CODE_OAUTH_TOKEN` + `claude setup-token` note)

### Change Log

| Date | Change |
|------|--------|
| 2026-05-31 | Story 3.4 implemented — Claude runner image (Dockerfile + POSIX entrypoint + `runner.mjs` + mock CLI), shared `RUNNER_CONTRACT.md`, Claude/Codex README cross-link, docker-compose `claude-runner` target, dual-mode subscription-first auth, Claude config-from-env (AC4), and `ClaudeRunnerImageConformanceIT`. Backend: extended `secret-env-names.claude` to `[CLAUDE_CODE_OAUTH_TOKEN, ANTHROPIC_API_KEY]` and (user-approved) switched `RunnerSecretsService` to matched-name injection so dual-mode auth works end-to-end. Verified: 68/0/0 backend fast tier + spotless/checkstyle clean; 21/21 dash entrypoint matrix + 4/4 Dockerized conformance IT on WSL2 Docker; production image `claude 2.1.149`, ~147 MB/10 layers. Status `ready-for-dev → review`. |
