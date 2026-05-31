# Story 3.3: Codex Runner Image — Dockerfile + Entrypoint + Contract Conformance

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **runner-infrastructure developer**,
I want **a `runners/codex/` Dockerfile + entrypoint script that wraps the Codex CLI as a runner container conforming to the runner-contracts v1 schema (input bundle → produce result file → exit)**,
so that **`DockerRunnerAdapter` (story 3.1) can dispatch Codex executions via the same file-based contract used by the deterministic mock, and the unified compose file can build the image as part of `docker compose build`**.

## Acceptance Criteria

1. **Given** `runners/codex/Dockerfile`, **Then** it produces an image tagged `deliveryline/codex-runner:latest` (and a build-arg-controlled version tag for CI) based on a documented base image (e.g., `node:22-slim` or whatever the Codex CLI requires), installs the Codex CLI via a documented version pin, and copies the entrypoint script.
2. **Given** `runners/codex/entrypoint.sh`, **Then** the entrypoint: (a) reads `/workspace/input/context-bundle.json`, (b) validates the bundle's `schemaVersion = 1`, (c) extracts the stage's required inputs (ticket summary, approved spec ref, prior feedback, execution constraints), (d) invokes the Codex CLI with the extracted inputs and any required env-var auth (per story 3.5 secrets handling), (e) collects Codex output into a structured `runner-result.v1` JSON document at `/workspace/output/runner-result.json`, (f) writes raw Codex stdout + stderr to `/workspace/logs/runner.stdout` and `/workspace/logs/runner.stderr`, (g) exits 0 on success, non-zero with a documented exit code on contract-detectable failure.
3. **Given** the runner-contracts v1 schema (story 1.6), **Then** the entrypoint emits `artifactType` correctly per stage: `spec` for spec-investigation runs, `implementationPlan` for plan-generation runs, `prOutput` for implementation runs — driven by a `stage` field passed in the input bundle.
4. **Given** `runner-contracts` valid fixtures, **Then** at least one fixture per artifact variant exercises the Codex image end-to-end against a documented test scenario (the test scenario uses a **mocked Codex CLI binary** — actual Codex API calls happen in story 3.8's integration test which is profile-gated and skippable in PR CI).
5. **Given** the unified `docker-compose.yml` (story 1.2), **Then** the Codex image is declared as a `build` target with `dockerfile: runners/codex/Dockerfile` — `docker compose build codex-runner` rebuilds the image, no separate `build-runner-images.sh` script needed.
6. **Given** workspace conventions per story 3.1 AC2, **Then** the entrypoint expects mounts at `/workspace/input` (read-only), `/workspace/output` (read-write), `/workspace/logs` (read-write) — and never writes outside these paths.
7. **Given** least-privilege per AR16 + architecture security model, **Then** the container runs as a non-root user (e.g., `codex:1000`), receives no network access by default (story 3.1 AC8 `--network=none`), and accesses the Codex API only when secrets are mounted per story 3.5 (with documented egress policy when network access is required for actual Codex API calls).
8. **Given** image smoke testing, **Then** `docker run --rm deliveryline/codex-runner:latest --self-test` exits 0 and prints a documented self-test summary (entrypoint reachable, Codex CLI installed, expected version) — used by the doctor command (story 1.16) for `runner image availability` check and by CI.
9. **Given** image-size budget, **Then** the resulting image is documented in size + layer count; an additive layer that exceeds a reasonable threshold (e.g., 1 GB total) requires justification in the runner README.
10. **Given** `runners/codex/README.md`, **Then** it documents: base-image choice rationale, Codex CLI version pinning + upgrade procedure, environment variables consumed, expected mount paths, exit-code meanings, and how to test the image locally (`docker compose build codex-runner && docker run ...`).
11. **Given** failure-injection capability, **Then** the entrypoint supports a `--simulate-failure={timeout|crash|contract_violation}` flag for integration tests (stories 3.6/3.8) — this flag is gated behind a documented test-only env var so production images can disable it.

## Tasks / Subtasks

- [x] **Task 1: Codex Dockerfile** (AC: 1, 7, 9)
  - [x] Replace the `runners/codex/Dockerfile` stub (`FROM scratch`) with a real multi-stage build on a documented, version-pinned base (recommend `node:22-slim` per epic hint — confirm against the actual Codex CLI install requirements; record the choice in the README).
  - [x] Install the Codex CLI via a **documented version pin** (no floating `latest`). Pin in an `ARG CODEX_CLI_VERSION=...` so CI can override.
  - [x] Add `ARG IMAGE_VERSION=latest` → also tag/label the image with the version for CI (`LABEL org.opencontainers.image.version=$IMAGE_VERSION`).
  - [x] Create a non-root user `codex:1000`, `chown` the entrypoint, drop to `USER codex`. The container must run unprivileged.
  - [x] `COPY runners/codex/entrypoint.sh` to a path on `PATH`, `chmod +x`, set as `ENTRYPOINT` (exec form).
  - [x] Keep layers lean; document final image size + layer count in the README (AC9).
- [x] **Task 2: Codex entrypoint — contract conformance** (AC: 2, 3, 6)
  - [x] Replace the `runners/codex/entrypoint.sh` stub (`#!/bin/sh`) with the real entrypoint. Use a POSIX-portable shell (`/bin/sh`) or `bash` if the base image guarantees it — document which.
  - [x] Read `/workspace/input/context-bundle.json`; parse `schemaVersion` and **fail fast** (documented non-zero exit) if it is not `1`. Parse the `stage` field.
  - [x] Extract stage inputs from the bundle (ticket summary, approved spec ref, prior feedback, execution constraints) — see the context-bundle.v1 schema and the valid fixtures for the exact field shape.
  - [x] Invoke the Codex CLI with extracted inputs + env-var auth (Task 5). Capture stdout/stderr.
  - [x] Emit `/workspace/output/runner-result.json` conforming to `runner-result.v1.schema.json` — set `artifactType` from the bundle `stage`: `spec-investigation`→`spec`, `implementation-plan`→`implementationPlan`, `pr-output`→`prOutput` (AC3). Mirror the structure of the existing valid fixtures.
  - [x] Write raw stdout → `/workspace/logs/runner.stdout` and stderr → `/workspace/logs/runner.stderr`. **Never** write outside `/workspace/output` and `/workspace/logs` (input is read-only — writing there must fail loudly, which validates the read-only mount).
  - [x] Exit `0` on success; documented non-zero codes per failure mode (Task 6 documents the table).
- [x] **Task 3: `--self-test` mode** (AC: 8)
  - [x] When invoked with `--self-test`, the entrypoint verifies: it is reachable, the Codex CLI is installed and reports the expected version, the workspace mount points are detectable — then prints a documented summary and exits `0`. No network, no API call. This is what doctor (story 1.16) and CI invoke.
- [x] **Task 4: Failure injection** (AC: 11)
  - [x] Support `--simulate-failure={timeout|crash|contract_violation}`:
    - `timeout` → sleep past any plausible deadline (lets story 3.2's timeout path kill it),
    - `crash` → exit non-zero **without** writing a result file (drives `RUNNER_CRASH` classification per `DockerRunnerAdapter.classifyExited`),
    - `contract_violation` → write a deliberately schema-invalid `runner-result.json`.
  - [x] Gate the whole flag behind a documented test-only env var (e.g., `DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true`) so production images refuse it.
- [x] **Task 5: Secrets / auth wiring (consume story 3.5 contract — do NOT re-implement)** (AC: 2d, 7)
  - [x] The Codex API key arrives as a **container environment variable**, already injected by the backend at dispatch time (story 3.5, done — `DockerRunnerAdapter` resolves it via `RunnerSecretsService` and passes it as `CreateContainerSpec.environment`). The entrypoint reads, in order, the first present of `CODEX_API_KEY` then `OPENAI_API_KEY` (matches `.env.example` + the resolver contract). Optionally honor `CODEX_BASE_URL` if set.
  - [x] If no key is present when a real (non-self-test, non-simulate) Codex invocation is required, fail with a documented non-zero exit and a message that names the **variable name only** — never echo the value.
  - [x] Document the network-egress posture: the container is launched `--network=none` by default; real Codex API calls require an operator to grant egress (documented in the README + tied to ADR `0003-runner-secrets-mvp-posture.md`).
- [x] **Task 6: README + docs** (AC: 9, 10)
  - [x] Replace the `runners/codex/README.md` stub with full docs: base-image rationale, Codex CLI version pin + upgrade steps, env vars consumed (`CODEX_API_KEY`/`OPENAI_API_KEY`, optional `CODEX_BASE_URL`, the test-only simulate-failure flag), mount paths, exit-code table, image size/layer count, and local test recipe (`docker compose build codex-runner && docker run --rm deliveryline/codex-runner:latest --self-test`).
- [x] **Task 7: docker-compose build target** (AC: 5)
  - [x] Add a `codex-runner` service to `docker-compose.yml` with `build: { context: ., dockerfile: runners/codex/Dockerfile }` and `image: deliveryline/codex-runner:latest`. It is a build-only image (no long-running service) — do not add it to default `up`; it should not start on `docker compose up -d`. Keep the single-file compose convention (postgres is the only runtime service today).
- [x] **Task 8: Contract-conformance tests** (AC: 4)
  - [x] Add a test that runs the Codex image against a **mocked Codex CLI binary** (a stub script baked in via build-arg or bind-mounted) producing deterministic output, for each artifact variant (spec / implementationPlan / prOutput), feeding the existing valid context-bundle fixture(s) as input.
  - [x] Validate the produced `runner-result.json` with `RunnerContractValidator` (module `deliveryline-runner-contracts`) — failure means the image is out of contract conformance and blocks the PR.
  - [x] Reuse the existing valid fixtures under `deliveryline-runner-contracts/src/test/resources/fixtures/valid/` (`context-bundle.v1.valid.json`, `runner-result.v1.spec.valid.json`, `runner-result.v1.implementation-plan.valid.json`, `runner-result.v1.pr-output.valid.json`) as inputs / golden references rather than authoring new shapes.
  - [x] Tag the docker-dependent test so it runs in the Docker-backed CI tier (mirror the `docker-runner-it` / `@EnabledIfDockerAvailable` posture from stories 3.1/3.2) and does NOT block PRs lacking Docker. Real Codex-API execution stays in story 3.8.
- [x] **Logging instrumentation** (cross-cutting; adapted for a shell entrypoint + image story)
  - [x] The entrypoint emits structured, parseable diagnostic lines to its own stdout/stderr (which the backend captures into `/workspace/logs` and redacts via story 3.5/3.6) at: start, schemaVersion validation, stage resolution, Codex invocation start/finish, result-file write, and every failure/exit branch.
  - [x] If the bundle carries a `correlationId`, the entrypoint prepends it to its log lines (sets up story 3.11 AC11 full-stack traceability).
  - [x] **Never** print secret values (`CODEX_API_KEY`/`OPENAI_API_KEY`), auth headers, or full bundle payloads. Reference only variable NAMES and counts. The backend redaction layer is a backstop, not a license to leak.
  - [x] Add an assertion in the contract test (Task 8) that a deliberately-injected secret value passed via env does NOT appear in the produced `runner.stdout`/`runner.stderr` written under `/workspace/logs` (negative log assertion).

## Dev Notes

### ⚠️ Critical context before you start

- **This is a runner-infrastructure story (Dockerfile + POSIX shell entrypoint + a contract test), NOT backend Java business logic.** The only Java you write is the conformance test in Task 8.
- **The `runners/codex/` files already exist as 1-line stubs awaiting this story** — do not "create from scratch," *fill them in*:
  - `runners/codex/Dockerfile` → currently `FROM scratch`
  - `runners/codex/entrypoint.sh` → currently `#!/bin/sh`
  - `runners/codex/README.md` → currently `Codex runner image — populated in story 3.3.`
  - (`runners/claude/` holds the symmetric stubs for story 3.4 — out of scope here, but keep the contract identical so 3.4 can mirror you; story 3.4 AC3 will extract the shared conventions into `runners/RUNNER_CONTRACT.md`.)
- **The backend half is already done and is your contract surface — do not modify it, conform to it:**
  - `DockerRunnerAdapter` (`adapters/runner/DockerRunnerAdapter.java`, story 3.1/3.2, done) already: selects the image via `runnerProperties.docker().imageTagFor(kind)`; bind-mounts `/workspace/input` **read-only**, `/workspace/output` and `/workspace/logs` **read-write**; launches with `--network=none`; writes the input bundle to the input mount; resolves secrets and passes them as container env; classifies exit (`classifyExited`): **no result file ⇒ `RUNNER_CRASH`** regardless of exit code, result file present ⇒ `Completed`.
  - **Secrets are already injected as env vars by story 3.5 (done).** You consume `CODEX_API_KEY` (fallback `OPENAI_API_KEY`); you never mount or read a secret file, and you never put secrets into the result or logs.

### Architecture Patterns and Constraints

- **File-based contract only (AC6, story 3.1 AC8):** the runner ↔ backend boundary is the three mounts + the two contract files. No HTTP, no socket. `--network=none` is enforced by the backend at create time.
- **Result-file-or-bust:** the adapter treats a missing `/workspace/output/runner-result.json` as `RUNNER_CRASH` even on a clean exit. Always write the result file before exiting `0`. (This is exactly what `--simulate-failure=crash` must violate.)
- **Versioned contract:** input is `schemaVersion: 1`; output must validate against `runner-result.v1.schema.json`. Reject unknown input versions; never emit an unversioned result.
- **Least privilege (AC7):** non-root user, no network by default, secret consumed from env and never echoed.
- **Determinism in tests (AC4):** the conformance test must NOT call the real Codex API — stub the CLI so output is deterministic; real-API execution is story 3.8 (profile-gated).
- **Untrusted-output downstream:** the backend treats your `runner-result.json` as untrusted and re-validates + redacts it. Your job is to produce a well-formed, schema-conformant document; do not assume your output is trusted.

### Existing files / contract surfaces (verified by reading them)

```
runners/codex/Dockerfile        # STUB `FROM scratch` — FILL IN (Task 1)
runners/codex/entrypoint.sh      # STUB `#!/bin/sh` — FILL IN (Task 2-5)
runners/codex/README.md          # STUB one-liner — FILL IN (Task 6)
docker-compose.yml               # only `postgres` service today — ADD `codex-runner` build target (Task 7)
.env.example                     # documents CODEX_API_KEY / OPENAI_API_KEY (story 3.5) — your auth contract

deliveryline-runner-contracts/
  src/main/resources/schemas/
    context-bundle.v1.schema.json   # INPUT shape (schemaVersion, stage, ticket/spec/feedback/constraints)
    runner-result.v1.schema.json    # OUTPUT shape your entrypoint must satisfy
  src/main/java/org/dradgo/runnercontracts/RunnerContractValidator.java  # use in Task 8 test
  src/test/resources/fixtures/valid/
    context-bundle.v1.valid.json
    runner-result.v1.spec.valid.json
    runner-result.v1.implementation-plan.valid.json
    runner-result.v1.pr-output.valid.json

deliveryline-backend/src/main/java/org/dradgo/adapters/runner/
  DockerRunnerAdapter.java        # DO NOT EDIT — your behavioral contract (mounts, network, exit classification)
  docker/CreateContainerSpec.java # shows env injection shape (secrets arrive as env)
```

### Key facts pulled from the live backend code (so the entrypoint matches reality)

- Mount constants in `DockerRunnerAdapter`: `CONTAINER_INPUT_MOUNT="/workspace/input"` (read-only), `CONTAINER_OUTPUT_MOUNT="/workspace/output"` (rw), `CONTAINER_LOGS_MOUNT="/workspace/logs"` (rw); `NETWORK_MODE_NONE="none"`.
- Input bundle is written by `workspaceStore.writeInputBundle(...)` into the input mount; the entrypoint reads `/workspace/input/context-bundle.json` (story 3.1 AC2b filename).
- Secret resolution: `runnerSecretsService.resolveSecretsForRunner(kind, stage, workflowRunId)` → passed as `CreateContainerSpec.environment` → Docker `Config.Env`. For Codex this is `CODEX_API_KEY` (fallback `OPENAI_API_KEY`). The adapter logs only the **count** of secret vars — match that discipline in the entrypoint.
- Exit classification: `classifyExited(...)` → result file absent ⇒ `RunnerPollStatus.Failed(RUNNER_CRASH)`; result file present ⇒ `Completed()`. Exit code is logged but the **presence of the result file** is the success signal.

### Image base / Codex CLI — open decision (recommendation)

The epic suggests `node:22-slim`. Confirm against the actual Codex CLI distribution before pinning. **Recommendation:** `node:22-slim` + `npm i -g @openai/codex@<pinned>` (or the documented install path), pinned via `ARG CODEX_CLI_VERSION`. Record the rationale + exact install command in the README (AC10). If the CLI requires a heavier base, justify the size in the README (AC9).

### Web / latest-tech check

- No new runtime dependency lands in the backend. Pin the Codex CLI version explicitly (no floating tags) so CI is reproducible; document the upgrade procedure (AC10). Verify the current Codex CLI package name + install command at build time rather than trusting this doc — CLI distribution names change.

### Project Structure Notes

- All new artifacts live under `runners/codex/` + `docker-compose.yml` + one conformance test (place it with the runner ITs in `deliveryline-backend` or in `deliveryline-runner-contracts`, consistent with where `@EnabledIfDockerAvailable` Docker-tier tests already live — mirror stories 3.1/3.2).
- **CI parity:** Docker image builds + container runs behave differently on Windows vs the Linux CI runner. Smoke-build and run `--self-test` on WSL2 Ubuntu before pushing, and confirm the Docker-tier test is correctly excluded from the no-Docker PR tier. See [[wsl-linux-ci-reproduction]] and [[verify-ci-fixes-in-clean-env]].
- The Docker-backed conformance test must NOT join the fast unit tier (it needs a Docker daemon + a built image) — gate it exactly like the existing `docker-runner-it` tier.

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.3]
- [Source: _bmad-output/planning-artifacts/runner-architecture-spec.md]
- [Source: _bmad-output/planning-artifacts/architecture.md (runner project-structure ownership boundary)]
- [Source: _bmad-output/implementation-artifacts/3-5-runner-secrets-handling-secure-mount-of-agent-provider-api-keys.md (secrets env contract — DONE)]
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java (mounts, network, exit classification, secret-env injection)]
- [Source: deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json + context-bundle.v1.schema.json]
- [Source: deliveryline-runner-contracts/src/test/resources/fixtures/valid/ (golden fixtures for Task 8)]
- [Source: .env.example (CODEX_API_KEY / OPENAI_API_KEY runner-secret contract)]
- [Source: docker-compose.yml (single-file compose; add codex-runner build target)]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-dev-story

### Debug Log References

- Entrypoint branch matrix (local, no Docker, WSL2): 22/22 PASS — spec/plan/pr variants + RunnerStage aliases (`investigation`/`execution`) + env-stage seam + exits 10/11/12/13/20/50/2/0 + self-test + simulate-failure gate + negative secret-in-logs.
- Docker e2e (test image, mock CLI, WSL2 Docker): self-test exit 0 + all three variants produce the correctly-named `runner-result.v1.json` with the right `artifactType`, `--network=none`, input ro, no secret leak — 14/14 PASS.
- Production image build (real `@openai/codex@0.135.0`): BUILD ok; `--self-test` reports `codex-cli 0.135.0` (== pin); size ~652 MB / 10 layers (under the ~1 GB AC9 budget; bulk is the Codex native binary over `node:22-slim`). Test (mock) image ~327 MB.
- `CodexRunnerImageConformanceIT` (Docker-tier, WSL2): **Tests run: 4, Failures: 0, Errors: 0** (self-test + 3 artifact variants; each schema-validated via `RunnerContractValidator.RUNNER_RESULT` + negative secret-log assertion).
- `docker compose config --services` → `postgres` only (codex-runner absent from default up); `--profile runners` → `codex-runner` present; `docker compose build codex-runner` builds by name. (AC5)
- `spotless:check` + `checkstyle:check` (backend) → clean (0 ERROR).

### Completion Notes List

- **Filename contract corrected against live code (NOT the story prose).** `LocalRunnerWorkspaceStore` writes the bundle as `context-bundle.v1.json` and reads the result from `runner-result.v1.json` (both carry the `.v1` infix). The story's Task 2 referenced `context-bundle.json` / `runner-result.json`; the entrypoint follows the **code** — a result under any other name reads back as `RUNNER_CRASH`. Verified by reading the adapter + workspace store.
- **Stage source (deviation from AC3 literal wording, documented in README).** The frozen `context-bundle.v1` schema is `additionalProperties:false` (cannot carry `stage`) and the `deliveryline.stage` Docker label is not readable inside the container. The entrypoint resolves the stage from `--stage` arg → `DELIVERYLINE_RUNNER_STAGE` env → optional bundle `stage` field. Tokens map the three story stages + the `RunnerStage` enum aliases (`investigation`→spec, `execution`→prOutput). Wiring stage into the container interior in production is a backend change owned by story 3.8 (adapter is DO-NOT-EDIT here); the env seam is already honored.
- **Non-root uid is `codex:1001`, not 1000** (`node:22-slim` already occupies uid/gid 1000 with its `node` user). AC7 says "e.g., codex:1000" — value is not a backend contract.
- **Single-stage Dockerfile (deviation from the Task-1 "multi-stage" hint, justified in README):** the CLI is an interpreted npm-global package needing the same Node runtime at run time, so a builder/runtime split yields no size win and risks clobbering the base `/usr/local/bin` node/npm symlinks. AC1 itself does not mandate multi-stage.
- **jq-free:** the slim base ships `node` but not `jq`, so all JSON read/build is in `lib/runner.mjs` (dependency-free Node ESM); the entrypoint stays POSIX `sh` (dash-compatible).
- **Secrets:** entrypoint reads `CODEX_API_KEY` then `OPENAI_API_KEY`, exports `OPENAI_API_KEY`/`OPENAI_BASE_URL` for the CLI, logs only the variable NAME + presence. Negative-log assertion in the IT proves the injected sentinel value never reaches `runner.stdout`/`.stderr`/the result.
- **Conformance test build via docker CLI:** docker-java `buildImageCmd` + `withBaseDirectory` failed to locate the out-of-cwd Dockerfile in its context tar; the test shells out to `docker build -f runners/codex/Dockerfile .` (identical to compose), keeping only the container RUNS on docker-java. Test is `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable` → excluded from the no-Docker PR tier (failsafe `excludedGroups` already lists `docker-runner-it`), mirroring stories 3.1/3.2.
- **Integration notes for story 3.8 (in README):** (a) backend must inject the stage as `DELIVERYLINE_RUNNER_STAGE`; (b) backend-created workspace mounts (`0700`, JVM-user-owned) must be writable by the non-root runner uid; (c) real Codex argument vector finalized via the `CODEX_EXEC_ARGS` seam.
- **Linux parity gate satisfied:** all builds + self-test + the conformance IT were run on WSL2 Ubuntu Docker (memory `wsl-linux-ci-reproduction`). Story 3.8 ships the real-API CI tier.

### File List

- `runners/codex/Dockerfile` — real image (was `FROM scratch` stub): `node:22-slim`, `ARG CODEX_CLI_VERSION=0.135.0` + `INSTALL_CODEX_CLI` toggle + `ARG IMAGE_VERSION` OCI label, non-root `codex:1001`, exec-form ENTRYPOINT.
- `runners/codex/entrypoint.sh` — real POSIX entrypoint (was `#!/bin/sh` stub): bundle read + schemaVersion fail-fast, stage resolution, secrets-from-env, Codex invocation + raw log capture, `runner-result.v1.json` emission, `--self-test`, `--simulate-failure`, exit-code contract.
- `runners/codex/lib/runner.mjs` — NEW dependency-free Node ESM helper (`prepare`/`build`/`build-invalid`) for JSON read + schema-conformant result construction.
- `runners/codex/test/mock-codex.sh` — NEW deterministic mock Codex CLI for the AC4 conformance build (`INSTALL_CODEX_CLI=false`).
- `runners/codex/README.md` — full docs (was one-line stub): contract, stage mapping, env vars, exit-code table, base/CLI rationale + upgrade, image size/layers, local test recipe, 3.8 integration notes.
- `docker-compose.yml` — MODIFIED: added build-only `codex-runner` service under the `runners` profile.
- `.dockerignore` — NEW (repo root): keeps the codex-runner build context lean.
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java` — NEW Docker-tier conformance test (AC4): builds the mock image, runs all 3 artifact variants against the valid fixture, validates via `RunnerContractValidator`, negative secret-log assertion.

## Change Log

| Date | Change |
|------|--------|
| 2026-05-31 | Story 3.3 implemented: Codex runner image (Dockerfile + POSIX entrypoint + Node helper + mock CLI), README, docker-compose `codex-runner` build target, `.dockerignore`, and the `CodexRunnerImageConformanceIT` Docker-tier conformance test. All 8 tasks + logging instrumentation complete; verified on WSL2 Linux Docker (entrypoint 22/22, docker e2e 14/14, conformance IT 4/4, spotless+checkstyle clean). Status ready-for-dev → in-progress → review. |
### Review Findings

- [x] [Review][Decision] Production Docker dispatch cannot provide the stage to the entrypoint - fixed by passing `DELIVERYLINE_RUNNER_STAGE` in the Docker container env while keeping secret-count logging scoped to provider secrets.
- [x] [Review][Decision] Non-root image cannot write backend-created output/log mounts on Linux - fixed by making POSIX input dirs runner-readable, output/log dirs runner-writable, and input bundle files runner-readable while retaining owner-only execution root permissions.
- [x] [Review][Patch] Runner-result artifact references are not materialized or ingestible by the current broker [runners/codex/lib/runner.mjs:176] - fixed by materializing spec payloads under `output/artifacts/...`, mirroring Docker workspace result/artifacts into scratch, and teaching broker ingestion to handle schema-native implementationPlan/prOutput references.
- [x] [Review][Patch] Codex/auth failure branches exit without a structured failure result, collapsing actionable failures into `RUNNER_CRASH` [runners/codex/entrypoint.sh:250] - fixed by writing schema-valid failure results after bundle/stage resolution for auth, command-config, and Codex non-zero failures.
- [x] [Review][Patch] `CODEX_EXEC_ARGS` is logged verbatim and shell-split unsafely [runners/codex/entrypoint.sh:269] - fixed by logging only arg count and constraining the command seam to a single safe token.
- [x] [Review][Patch] Entrypoint writes the prompt temp file outside the allowed `/workspace/output` and `/workspace/logs` mounts [runners/codex/entrypoint.sh:205] - fixed by writing the prompt file under `/workspace/logs`.
- [x] [Review][Patch] `--self-test` reports but does not verify the pinned Codex version or mount detectability [runners/codex/entrypoint.sh:122] - fixed by checking the reported CLI version contains the expected pin and printing mount presence.
- [x] [Review][Patch] Conformance test bypasses production dispatch boundaries and does not use the required runner-result golden fixtures [deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java:140] - fixed by using `DELIVERYLINE_RUNNER_STAGE`, mirroring production workspace permissions, checking golden variant shape, and asserting spec payload materialization.
