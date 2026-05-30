# Story 3.5: Runner Secrets Handling — Secure Mount of Agent-Provider API Keys

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer + pilot installer,
I want secrets (agent-provider API keys — Codex/OpenAI key, Claude/Anthropic key) mounted into runner containers via runtime env-var injection with strict no-leak guarantees, plus host-side resolution of the GitHub/Linear tokens that NEVER enter a runner container,
so that runner containers can authenticate to their agent provider without any secret being committed to the repo, baked into an image, persisted in a workspace, written to logs, shipped to ELK (story 3.7), or serialized into a context bundle — fulfilling NFR8, NFR9, NFR14.

## Context

This is the **secrets-handling slice** of Epic 3a's runner-execution track. Stories [[3-1-docker-runner-adapter-core-container-lifecycle-and-file-based-contract-invocation]] and [[3-2-docker-runner-adapter-lifecycle-timeout-heartbeat-lease-expiry-cleanup-idempotent-restart]] (closed out by [[3-2a-docker-runner-lifecycle-test-surface-and-review-hardening]]) built the full Docker runner lifecycle — but every container launched so far receives **zero environment variables** (`CreateContainerSpec` has no `environment` field). The Codex/Claude runner images (stories 3.3/3.4, which come *after* this story in the active slice) will need a provider API key to call their agent. **This story is the source of truth for which env-var names those images consume** — 3.3/3.4 read what 3.5 injects.

Next-active order (backend track): **3-5** → 3-3 → 3-4 → 3-6 → 3-13 → 3-14 → 3-9. Epic-3a is already `in-progress`.

**The no-leak contract this story enforces** (NFR8/NFR9/NFR14):
- Secrets are NEVER committed, baked into images, read from `application.yml` defaults, written into the workspace, serialized into the context bundle, logged at any level, or shipped to ELK.
- The only place a provider key legitimately exists at rest in the runner pipeline is the container's `Config.Env` (visible to a local operator via `docker inspect`) — this is the accepted MVP posture, documented in a new ADR (AC6).

**Reuse, do NOT reinvent** — story 1.10's redaction stack is the detection engine for the leak scans:
- `application.security.RedactionPolicyService.redact(String, classification)` → `RedactionResult { sanitizedText, detectedCategories (Set<RedactionCategory>), redacted }`.
- `application.security.SensitivePayloadAnalyzer` already ships patterns for `GITHUB_TOKEN` (`ghp_…`, `github_pat_…`), `LINEAR_API_KEY` (`lin_api_…`), `AUTHORIZATION_HEADER`, `ENV_SECRET_VALUE`, PEM/SSH keys, etc. (`RedactionCategory` enum).
- ArchUnit rule `CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY` **forbids** reimplementing credential regex anywhere else — `RunnerSecretsService` only **resolves** secret *values* from config and **delegates** all *detection* to `RedactionPolicyService`.

## Acceptance Criteria

1. **`RunnerSecretsService` (`application.runner`)** exposes `resolveSecretsForRunner(RunnerKind runnerKind, RunnerStage stage, String workflowRunId) → Map<String,String>` returning the env-var name→value map a **runner container** needs for this dispatch (provider key only — see AC-scope note below). Values are read at call time from the Spring `Environment` (system env + `.env`), never from `application.yml` defaults, never persisted/cached in a field or static beyond the call. If a required env var for the kind is absent or blank, the method throws `DomainException(DOCTOR_RUNNER_SECRET_MISSING, …)` with `details.runnerKind` + `details.missingEnvVar` (the *name*, never a value) — so dispatch fails fast at dispatch time, not at server startup (runners may be optional in early pilot phases).

2. **Secret loading source** — secrets resolve from `.env` (wired via `spring.config.import: "optional:file:./.env[.properties]"` added to `application.yml`) or from process environment variables at runtime. They are never committed, never present as `application.yml` defaults, and never logged at any level. A redaction-rule test asserts that no value matching the provider-key / `LINEAR_API_KEY` / API-key regex appears in captured logs across the dispatch + scan paths.

3. **Env injection through the gateway (Trap-T8 boundary preserved)** — extend `CreateContainerSpec` with a `Map<String,String> environment` field; `DefaultDockerEngineGateway.createContainer` applies it via docker-java `CreateContainerCmd.withEnv(...)`. Because we use the **docker-java API, not the `docker` CLI**, secrets are set in the container's `Config.Env` and never appear in any process argv / `docker ps` command column. Secrets are never written to a file inside the workspace and never serialized into the input context bundle (NFR14).

4. **Post-execution workspace secret scan + quarantine** — after a runner result is harvested (`RunnerBroker.onResult`, before the row is marked `completed`), every regular file under `input/`, `output/`, and `logs/` is read and passed through **two** detectors: (i) `RedactionPolicyService` for the *known* secret shapes (GitHub/Linear/auth-header/env-block/PEM/SSH), and (ii) a **mandatory** exact-substring containment check against the literal provider-key value(s) this dispatch injected (the broker knows them — string compare, not a credential pattern, so the `CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY` rule still holds; see Trap T7). The substring check is **required, not optional**: the injected provider keys (`CODEX_API_KEY`/`ANTHROPIC_API_KEY` values) are arbitrary strings that match **no** `RedactionCategory` regex, so pattern scanning alone would not catch this story's own secrets. If either detector fires, the execution is recorded **failed** with `FailureCategory.RUNNER_SECRET_LEAK` (new) — NOT completed — a `RUNNER_FAILED` event carries `details.failureCategory = "runner_secret_leak"` + `details.leakedFile` (relative path) + `details.detectedCategories` (category *names* only, never values; for a substring-only hit use a synthetic `"injected_provider_key"` label, never the value), and the workspace is **quarantined** (a `.quarantine` marker is written so `RunnerWorkspaceCleanupJob` from story 3.2 preserves it past normal retention for diagnostics rather than deleting it).

5. **Runner output redaction seam for logs** — `RedactionPolicyService` is the mandatory redaction pass for any host-side capture of runner stdout/stderr. Story 3.6 owns the host-side capture path; this story (a) covers the file layer now via the AC4 post-execution scan of `logs/`, and (b) exposes/uses a single redaction entry point that story 3.6's capture path MUST call before writing `logs/` files, so a runner CLI that logs its own auth header cannot leak through. A test deliberately leaks a secret into `logs/runner.stdout` and asserts the post-execution scan flags it (AC4 path).

6. **ADR** — create `docs/adr/0003-runner-secrets-mvp-posture.md` (Status: Accepted) documenting the MVP posture: env-var injection only, no OS keychain, no Docker secrets daemon; the explicit acceptance that provider keys are visible to a local operator via `docker inspect Config.Env`; and the exact upgrade triggers that force re-evaluation (multi-user, hosted, or cross-repo runners; raw logs/bundles/outputs retained beyond MVP retention). **Note:** the epic text names this file `0002-…` but `0002` is already taken (`0002-idempotency-stale-reservation-policy.md`); `0003` is the next free number — use `0003`.

7. **`.env.example` documentation** — every runner-required secret has a documented placeholder in `.env.example` with a comment naming the service it authenticates and how to obtain the credential (matching the existing `LINEAR_API_KEY`/`GITHUB_TOKEN` comment style). At minimum add the per-kind provider-key placeholders this story injects (see Trap T2 for names).

8. **Doctor per-runner-kind secret check** — extend the doctor command (story 1.16): a new `CHECK_RUNNER_SECRETS` probe iterates `RunnerKind.values()` and reports, per kind, whether the required secret env var(s) are present (`PASS`) or missing (`FAIL` with `DOCTOR_RUNNER_SECRET_MISSING` and a remediation pointer to `.env.example`). Doctor never prints secret values — only presence + the env-var name. Register the check in `DoctorService` (constant + `STATIC_ORDER` + remediation map + `runSingleProbe` switch) and implement `probeRunnerSecrets()` in `DoctorProbeAdapter`. Add `DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING`.

9. **Rotation without caching** — rotating a value in `.env` and restarting the backend immediately uses the new value for subsequent dispatches. `RunnerSecretsService` must read the Spring `Environment` per call and hold no `@Value`-snapshotted field / static cache; in-flight containers keep their dispatch-time values until completion (env is fixed at container create).

10. **ArchUnit boundary** — `RunnerSecretsService` may be depended on ONLY from `org.dradgo.application.runner..` and `org.dradgo.adapters.runner.docker..`; no controller, REST adapter, or other application service may resolve secrets directly. Add the rule to `ArchitectureRuleCatalog` + an `@ArchTest` field in `ArchitectureBoundaryTest`. The service must NOT reimplement credential detection (the existing `CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY` rule continues to hold — delegate to `RedactionPolicyService`).

11. **Test suite** covers: (a) secret resolution from env vars; (b) absent secret raises `DOCTOR_RUNNER_SECRET_MISSING` at dispatch time; (c) post-execution scan catches a deliberately-leaked secret in `output/` (→ `RUNNER_SECRET_LEAK` + quarantine) — **two cases: one using a known-pattern shape (e.g. `ghp_…`) to exercise the `RedactionPolicyService` detector, one using the literal injected provider-key value to exercise the mandatory substring detector (AC4)**; (d) the same in `logs/runner.stdout` (at least the provider-key-value case, since that is the realistic leak this story guards); (e) secrets never appear in container **labels** / `docker inspect` label output (the label set from story 3.1/3.2 carries no secret values — env visibility is the accepted posture, labels are not); (f) secrets absent from the persisted context bundle (adversarial fixture: source data seeded with a fake key — **use a known-pattern shape such as `ghp_…`/`lin_api_…` so `RedactionPolicyService` produces a placeholder; a bare provider-key value would NOT be redacted in the bundle and is not the assertion here** → bundle contains the redaction placeholder, not the value); (g) logging-contract test asserts no secret value at any level on the dispatch + scan paths; (h) ArchUnit rule passes.

> **AC-scope note (Trap T1):** Only the **agent-provider key** is injected into a runner *container* (`CODEX_API_KEY`/`OPENAI_API_KEY` for Codex, `ANTHROPIC_API_KEY` for Claude — see Trap T2). `GITHUB_TOKEN` and `LINEAR_API_KEY` are **host-side only** (git clone/push is story 3.9/3.14, ticket intake is Epic 1/2) and MUST NOT be injected into any runner container env (story 3.14 AC10: the runner sees only the cloned working tree, never the credential). `RunnerSecretsService` may expose a separate host-side resolver method for those, but `resolveSecretsForRunner` returns container env only.

## Tasks / Subtasks

- [x] **Task 1 — `RunnerSecretsService` + secret-to-kind config (AC1, AC2, AC9)**
  - [x] Create `org.dradgo.application.runner.RunnerSecretsService` (`@Service`). Inject Spring `org.springframework.core.env.Environment`. No `@Value` snapshots, no static/field cache (AC9, Trap T6).
  - [x] `Map<String,String> resolveSecretsForRunner(RunnerKind, RunnerStage, String workflowRunId)` → reads required env-var name(s) for the kind from config (Task 2), pulls each value from `Environment.getProperty(name)`; blank/absent → `throw new DomainException(DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING, "runner secret missing", Map.of("runnerKind", kind.value(), "missingEnvVar", name))` (NEVER put the value in `details`).
  - [x] Add an optional host-side resolver (e.g. `Optional<String> resolveHostSecret(String envName)`) for git/Linear callers (3.9/3.14) — but `resolveSecretsForRunner` returns container env only (Trap T1).
  - [x] Structured logs: INFO on resolve entry/exit with `runnerKind` + resolved-var **count** only — never names of *missing-vs-present* in a way that reveals a value, never values. WARN on missing.
- [x] **Task 2 — secret-name configuration (AC1, AC7, Trap T2)**
  - [x] Add a config-driven `RunnerKind → required env-var names` mapping. Recommended: extend `RunnerProperties` with a `Map<RunnerKind, List<String>> secretEnvNames` (defaults: `CODEX → [CODEX_API_KEY]`, `CLAUDE → [ANTHROPIC_API_KEY]`) bound under `deliveryline.runner.secret-env-names`. Update `src/test/resources/application.yml` if the new validated property is required (memory `[[validated-config-needs-test-yaml]]`).
  - [x] Wire `application.yml` (real) with the defaults; do NOT put secret *values* there — only env-var *names*.
- [x] **Task 3 — env injection through the gateway (AC3, Trap T8)**
  - [x] Add `Map<String,String> environment` field to `CreateContainerSpec` (default empty map; never null). Update all constructor call sites.
  - [x] `DefaultDockerEngineGateway.createContainer`: after `withLabels`, add `cmd.withEnv(spec.environment().entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList())` (docker-java `withEnv(List<String>)`); if empty, omit. **Gateway create-container log must log only image/networkMode/bind-count/label-keys and the env-var COUNT — never env names or values** (Trap T5).
  - [x] `DockerRunnerAdapter.dispatch`: immediately before building `CreateContainerSpec`, call `runnerSecretsService.resolveSecretsForRunner(kind, request.stage(), request.workflowRunId())` and pass the map into the new field. Mock adapter path untouched (Trap T4 — `MockRunnerAdapter` never calls the secrets service).
- [x] **Task 4 — post-execution workspace secret scan + quarantine (AC4, AC5)**
  - [x] Add `FailureCategory.RUNNER_SECRET_LEAK("runner_secret_leak")` to the registry enum. **`RegistryContractTest` asserts only that the `failureCategory` registry is non-empty and has no SQL `CHECK` constraint — the enum addition itself likely needs NO test change there** (confirm, don't hunt for a missing assertion). The real schema obligation: **`failureCategory` IS an enum in `src/main/resources/openapi/openapi.json` (verified — all 8 current values present), so adding `runner_secret_leak` REQUIRES regenerating `openapi.json`** per memory `[[openapi-regen-platform-shim]]` (cross-shell regen) — add `openapi.json` to the File List.
  - [x] Add a `RunnerWorkspaceStore` capability to enumerate + read workspace files for scanning (e.g. `List<WorkspaceScanFile> readFilesForSecretScan(String rex)` returning relative path + UTF-8 text; skip/flag binary files per OQ-6). Detection stays OUT of the store (Trap T7) — the store only reads bytes.
  - [x] In `RunnerBroker.onResult`, **after artifact harvest (`recordOperation` at `:468`) and immediately before `recordCompleted` at `RunnerBroker.java:504`** (NOT after the already-terminal skip-guard `~:500`/`:510`): loop files and run **both** detectors — (i) `redactionPolicyService.redact(text, DataClassification.LOCAL_ONLY.value())` → check `result.detectedCategories()` non-empty (known shapes), AND (ii) **mandatory** exact-substring check of `text` against the literal provider-key value(s) injected for this dispatch (catches the provider keys that match no regex — see Dev Notes). On any hit, record `RUNNER_SECRET_LEAK` via the existing `recordFailed(runnerExecutionId, FailureCategory.RUNNER_SECRET_LEAK)` path (mirroring `:484`/`:621`/`:638`/`:664`) — **never** the `recordCompleted` branch — emit `RUNNER_FAILED` with `details.failureCategory="runner_secret_leak"`, `details.leakedFile`, `details.detectedCategories` (category names only; for a substring-only hit use a synthetic `"injected_provider_key"` label, never the value), and write the `.quarantine` marker.
  - [x] Teach `RunnerWorkspaceCleanupJob` (story 3.2) to skip deletion of any workspace carrying a `.quarantine` marker (preserve for diagnostics; WARN once).
- [x] **Task 5 — ADR (AC6)**
  - [x] Create `docs/adr/0003-runner-secrets-mvp-posture.md` (Status/Context/Decision/Consequences format matching `0001-unified-compose.md`). Document env-var-only posture, `docker inspect Config.Env` visibility acceptance, and upgrade triggers.
- [x] **Task 6 — `.env.example` (AC7)**
  - [x] Add `CODEX_API_KEY=` (+ optional `OPENAI_API_KEY=`) and `ANTHROPIC_API_KEY=` placeholders with service + obtain-it comments, matching existing style. Keep `LINEAR_API_KEY`/`GITHUB_TOKEN` (host-side) as-is.
- [x] **Task 7 — doctor per-runner-kind secret check (AC8)**
  - [x] `DoctorService`: add `CHECK_RUNNER_SECRETS` constant, insert into `STATIC_ORDER`, add remediation map entry (pointer to `.env.example`), add `case CHECK_RUNNER_SECRETS -> probes.probeRunnerSecrets()` in `runSingleProbe`.
  - [x] `DoctorProbeAdapter.probeRunnerSecrets()`: iterate `RunnerKind.values()`, check each required env-var name present in `Environment`; build `details` with `<kind>=present|missing` (presence only); `PASS` if all present, else `ProbeResult.fail(summary, DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING.value(), details)`. Never put values in `details`.
  - [x] Add `DOCTOR_RUNNER_SECRET_MISSING("DOCTOR_RUNNER_SECRET_MISSING")` to `DomainErrorCode`.
- [x] **Task 8 — ArchUnit boundary (AC10)**
  - [x] Add `RUNNER_SECRETS_SERVICE_SCOPE` rule to `ArchitectureRuleCatalog`: `noClasses().that().resideOutsideOfPackage("org.dradgo.application.runner..").and().resideOutsideOfPackage("org.dradgo.adapters.runner.docker..").should().dependOnClassesThat().haveFullyQualifiedName("org.dradgo.application.runner.RunnerSecretsService")`. Add the `@ArchTest` field in `ArchitectureBoundaryTest`. Confirm the existing credential-detection rule still passes (no new regex in the runner package).
- [x] **Task 9 — tests (AC11)** — see Testing Requirements for the full matrix.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `runnerExecutionId`, `runnerKind`). Use MDC where the framework supports it; otherwise pass as parameters.
  - [x] **Never log secrets, env-var values, payload bytes, raw tokens, or full PII.** This story's WHOLE POINT is no-leak — log env-var **counts** and **names** of *required* vars, never their values, and never the names in a way that distinguishes a present value from its content. Reference the redaction policy when in doubt.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use a list-appender or `OutputCaptureExtension`), PLUS an adversarial sweep asserting no captured line matches a secret regex (AC2/AC11g).

## Dev Notes

### Architecture & insertion points (exact, verified)

- **`RunnerAdapter` port** — `application/runner/spi/RunnerAdapter.java:21` `RunnerDispatchAck dispatch(RunnerDispatchRequest)`. `RunnerDispatchRequest` (`application/runner/RunnerDispatchRequest.java`) fields: `runnerExecutionId, workflowRunId, stage (RunnerStage), runnerKind (RunnerKind), contextBundlePath, executionConstraints, classification`. **Do NOT add secrets to this record** (Trap T3) — they must never transit the broker-level request object or its logs.
- **`RunnerKind`** — `domain/registry/RunnerKind.java`: `CODEX("codex")`, `CLAUDE("claude")`. **`RunnerStage`** — `domain/registry/RunnerStage.java`: `INVESTIGATION("investigation")`, `EXECUTION("execution")`.
- **`DockerRunnerAdapter`** — `adapters/runner/DockerRunnerAdapter.java`, `dispatch` ~lines 90–180; builds labels (~122–129) then `CreateContainerSpec` (~131–139). **Insert secret resolution right before the spec build.**
- **`CreateContainerSpec`** — `adapters/runner/docker/CreateContainerSpec.java:16` record `(String image, List<BindMount> binds, String networkMode, Map<String,String> labels)`. **Add `Map<String,String> environment`.**
- **`DefaultDockerEngineGateway.createContainer`** — `adapters/runner/docker/DefaultDockerEngineGateway.java:47-73`; currently `cmd.withHostConfig(hostConfig).withLabels(spec.labels())` then `.exec()`. **Add `.withEnv(...)`.** docker-java types must stay behind this gateway (ArchUnit `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY`).
- **`ContainerState`** (`…/docker/ContainerState.java`) has `labels` but **no env field** — reading back state never re-exposes secrets. Keep it that way.
- **`RunnerBroker`** — `application/runner/RunnerBroker.java`; `dispatch` ~182–294, `onResult` is the harvest seam for the AC4 scan. Broker already injects application services; add `RunnerSecretsService` only if the broker (not the adapter) is chosen to resolve — **recommended: adapter resolves** (keeps secrets entirely out of broker scope; AC10 permits both).
- **`RunnerWorkspaceStore` / `LocalRunnerWorkspaceStore`** — `application/runner/spi/RunnerWorkspaceStore.java`, `adapters/files/LocalRunnerWorkspaceStore.java`. `WorkspaceLayout(root, input, output, logs)`. Use the existing `Files.walkFileTree` pattern (see `walkAndDelete`) for the scan-file enumeration. `PublicIdPrefixes.require(rex, RUNNER_EXECUTION)` guards every store method — keep that.
- **`RunnerProperties`** — `application/runner/RunnerProperties.java`, `@ConfigurationProperties("deliveryline.runner")`, nested `Docker` record + `defaults()`. application.yml block at `application.yml:94-108`.

### Redaction / detection (reuse — do NOT reimplement)

- `application/security/RedactionPolicyService.java` — `RedactionResult redact(String payload, String claimedClassificationValue)`. Returns `detectedCategories (Set<RedactionCategory>)`, `redacted (boolean)`, `sanitizedText`. **This is the AC4/AC5 detection engine.** Pass `DataClassification.LOCAL_ONLY.value()` as the classification for workspace scans.
- `application/security/SensitivePayloadAnalyzer.java` — patterns already include `GITHUB_TOKEN`, `LINEAR_API_KEY`, `AUTHORIZATION_HEADER`, `ENV_SECRET_VALUE`, PEM/SSH. `RedactionCategory` placeholders like `[REDACTED_GITHUB_TOKEN]`.
- **Caveat (deferred-work F-row):** `JSON_SECRET_FIELD_PATTERN` text-mode doesn't match non-string JSON values; for the workspace scan we read each file as **text**, which exercises the text-mode patterns. A JSON file with a secret in a string value is caught; a secret as a numeric value is a known pre-existing gap (out of scope here). Note it; don't fix it in this story.
- **No new provider-key regex** unless you can add it inside `application.security` (the ArchUnit rule forbids it elsewhere). The provider keys (`CODEX_API_KEY`/`ANTHROPIC_API_KEY` *values*) are arbitrary strings that match **no** existing `RedactionCategory` pattern — verified against `SensitivePayloadAnalyzer` (its catalog is `GITHUB_TOKEN`/`LINEAR_API_KEY`/`AUTHORIZATION_HEADER`/`ENV_VALUE`/`ENVIRONMENT_BLOCK`/PEM/SSH/`SECRET_FIELD`/`QUERY_SECRET`; a bare `sk-ant-…`-style value in `output/result.json` would slip through, and a `KEY=VALUE` env-block leak is only caught by the env-block/entropy path). **Therefore the exact-substring containment check is REQUIRED, not optional:** the broker already KNOWS the literal value(s) it injected this dispatch, so it must string-compare each scanned file's text against those value(s) (string compare, not a regex/credential *pattern* — does NOT violate `CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY`, Trap T7). `RedactionPolicyService` remains the detector for the known shapes; the substring check is what actually catches *this story's* secrets.
- **Exact AC4 insertion seam (verified):** `recordCompleted(runnerExecutionId)` is at `RunnerBroker.onResult` **`RunnerBroker.java:504`**, inside the success path, *after* artifact harvest (`recordOperation` at `:468`) and *behind* the terminal/duplicate skip-guard (`~:500`). Run the secret scan **after `:468` and immediately before `:504`**, and on a hit route into the **`recordFailed(runnerExecutionId, FailureCategory.RUNNER_SECRET_LEAK)`** path (mirroring the existing `recordFailed` sites at `:484`/`:621`/`:638`/`:664`) — **never** let a leaked execution reach the `recordCompleted` branch, and do not place the scan after the already-terminal skip (`:510`) or it will silently no-op on re-entry.

### Doctor (story 1.16) wiring

- `application/diagnostics/DoctorService.java` — check constants + `STATIC_ORDER` + `REMEDIATION` map + `runSingleProbe(name)` switch delegating to `DoctorProbePort`. `ProbeResult.pass(...)`, `ProbeResult.fail(summary, errorCode, details)`. `DiagnosticsStatus { PASS, WARN, FAIL, SKIP }`.
- `adapters/diagnostics/DoctorProbeAdapter.java` — implements `DoctorProbePort`; model `probeRunnerSecrets()` on the existing `probeDockerAvailability()` shape (`DoctorProbeAdapter.java:421`).
- `DiagnosticsCheck`/report redaction already strips values — confirm the new check name + error code flow through unredacted but `details` carries presence-only.

### `.env` loading (AC2 — currently MISSING)

- There is **no** `spring.config.import` for `.env` anywhere in `src/main/resources`. Spring Boot does **not** auto-load `.env`. Add to `application.yml`:
  `spring.config.import: "optional:file:./.env[.properties]"` (the `[.properties]` extension hint makes Spring parse the extensionless `.env` as `KEY=VALUE` properties).
- For Docker Compose runs, secrets also reach the JVM via the process environment (compose `env_file`/host export) — `Environment.getProperty` reads both. Both paths must work.
- **Test isolation:** never put real or fake secret values in `src/test/resources/application.yml`. Tests inject via `@SetEnvironmentVariable` / `ApplicationContextRunner.withPropertyValues` / a `MockEnvironment`.

### ADR numbering (AC6 — epic text is stale)

- Existing ADRs: `0001-unified-compose.md`, `0002-idempotency-stale-reservation-policy.md`, `0004-spec-stage-orchestration.md`, `0019-structured-logging.md`. The epic's literal `0002-runner-secrets-mvp-posture.md` **collides** — use **`0003`** (the next free sequential number).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - `RunnerSecretsService.resolveSecretsForRunner` → `INFO` entry/exit with `runnerKind` + resolved-var **count**; `WARN` + `DOCTOR_RUNNER_SECRET_MISSING` on absent var (name only).
  - `DockerRunnerAdapter.dispatch` → existing dispatch log gains `secretVarCount` (count, never names/values).
  - `RunnerBroker.onResult` secret scan → `INFO` "scanned N files for secrets" per execution; `WARN` "runner secret leak detected file=… categories=…" (relative path + category names) on hit; quarantine marker write logged `WARN`.
  - `DefaultDockerEngineGateway.createContainer` → keep existing log; add env-var **count** only.
- **Required context keys:** `correlationId`, `workflowRunId`, `runnerExecutionId`, `runnerKind` (+ `idempotencyKey`, `actorIdentity` where in scope).
- **Forbidden in log output:** secret values, env-var values, payload bytes, tokens, raw PII, classification-restricted fields. The dispatch + scan paths get an adversarial no-secret-in-logs sweep test (AC2/AC11g).
- **Test contract:** new logging surfaces pinned by ≥1 focused test (list-appender / `OutputCaptureExtension`).

### Project Structure Notes

- `RunnerSecretsService` lives in `org.dradgo.application.runner` (sibling to `RunnerBroker`) — NOT in `adapters`, NOT in `application.security`. ArchUnit rule (AC10) confines its callers.
- No domain types should carry secret values. `Map<String,String>` env maps stay inside the adapter/broker call boundary; never persisted, never put in a record that gets serialized (DB, bundle, event details).
- Detected-but-not-fixed: keep `GITHUB_TOKEN`/`LINEAR_API_KEY` host-side; do not regress story 3.14 AC10 / 3.9 AC10 by mounting them into a container.

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.5] — AC1–11, NFR8/NFR9/NFR14.
- [Source: epic-03-agent-execution.md#Story 3.3/3.4] — Codex/Claude entrypoints consume the env vars this story injects; `--simulate-failure` test hook.
- [Source: epic-03-agent-execution.md#Story 3.9 AC10 / Story 3.14 AC10] — GITHUB_TOKEN host-side, never in runner container.
- [Source: deliveryline-backend/.../application/security/RedactionPolicyService.java] — `redact(String, classification) → RedactionResult`.
- [Source: deliveryline-backend/.../application/security/SensitivePayloadAnalyzer.java] — pattern catalog + `RedactionCategory`.
- [Source: deliveryline-backend/.../adapters/runner/docker/CreateContainerSpec.java + DefaultDockerEngineGateway.java:47] — env-injection seam.
- [Source: deliveryline-backend/.../application/diagnostics/DoctorService.java + adapters/diagnostics/DoctorProbeAdapter.java:421] — doctor check pattern.
- [Source: deliveryline-backend/.../architecture/ArchitectureRuleCatalog.java + ArchitectureBoundaryTest.java] — ArchUnit rule pattern (`CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY`, `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY`).
- [Source: .env.example] — placeholder + comment style.
- [Source: docs/adr/0001-unified-compose.md] — ADR template; numbering (0003 free).
- Memory: `[[validated-config-needs-test-yaml]]`, `[[openapi-regen-platform-shim]]`, `[[wsl-linux-ci-reproduction]]`, `[[application-cannot-import-adapters]]`.

## Declared Traps

- **T1 — provider key only into containers.** `resolveSecretsForRunner` returns ONLY the agent-provider key env for the kind. `GITHUB_TOKEN`/`LINEAR_API_KEY` are host-side and MUST NOT be injected into any runner container (3.14 AC10 / 3.9 AC10).
- **T2 — env-var names are this story's SoT.** Codex container → `CODEX_API_KEY` (recommend also accepting `OPENAI_API_KEY` as a fallback name), Claude container → `ANTHROPIC_API_KEY`. Config-driven (`deliveryline.runner.secret-env-names`) so 3.3/3.4 read the same names. Confirm against the agent CLIs in 3.3/3.4 — see OQ-1.
- **T3 — never add secrets to `RunnerDispatchRequest`** (or any record persisted/serialized/event-logged). Resolve at the adapter, pass straight into `CreateContainerSpec.environment`.
- **T4 — mock path untouched.** `MockRunnerAdapter` never calls `RunnerSecretsService`; only `DockerRunnerAdapter` injects env.
- **T5 — gateway never logs env.** `createContainer` logs env-var **count** only — no names, no values.
- **T6 — no secret caching.** No `@Value` snapshot, no static/field cache; read `Environment` per call (AC9 rotation).
- **T7 — detection stays in `application.security`.** The workspace store only reads bytes; `RunnerSecretsService` only resolves values; ALL pattern detection routes through `RedactionPolicyService`. The exact-substring containment check in the broker (Dev Notes) is a literal value compare, not a credential pattern — allowed.
- **T8 — docker-java behind the gateway.** Env application uses docker-java `withEnv` only inside `DefaultDockerEngineGateway`; no docker-java types leak into `DockerRunnerAdapter` or above.
- **T9 — `docker inspect` env visibility is accepted, labels are not.** AC11(e) tests that **labels** carry no secret; env-in-`Config.Env` is the documented MVP posture (ADR-0003), NOT a bug to "fix" by hiding env.
- **T10 — quarantine preserves, never deletes.** A leaked-secret workspace is marked `.quarantine`; the cleanup job (story 3.2) must skip it. Do not delete diagnostic state.

## Open Questions (recommendations included — proceed unless told otherwise)

- **OQ-1 (env-var names):** Codex CLI reads `CODEX_API_KEY` vs `OPENAI_API_KEY`? Claude Code CLI reads `ANTHROPIC_API_KEY`. **Recommendation:** make names config-driven; default Codex→`CODEX_API_KEY` (also resolve `OPENAI_API_KEY` if present), Claude→`ANTHROPIC_API_KEY`; finalize against 3.3/3.4 entrypoints. Config-driven means a name change in 3.3/3.4 is a yaml edit, not a code change.
- **OQ-2 (resolve at adapter vs broker):** **Recommendation:** resolve in `DockerRunnerAdapter` (keeps secrets entirely out of broker/request scope). AC10 permits both; ArchUnit covers both packages.
- **OQ-3 (new `FailureCategory.RUNNER_SECRET_LEAK`):** **Resolved — add it** (AC4 names `runner_secret_leak` explicitly). **Confirmed:** `failureCategory` IS an OpenAPI enum in `src/main/resources/openapi/openapi.json`, so OpenAPI regen is **required** (not conditional) — regen per `[[openapi-regen-platform-shim]]` in the same PR and include `openapi.json` in the File List. `RegistryContractTest` asserts only registry-non-empty with no SQL `CHECK`, so it needs no change for the enum add itself.
- **OQ-4 (`.env` import vs process-env only):** **Recommendation:** add `spring.config.import: "optional:file:./.env[.properties]"` AND keep process-env support — covers both `mvn`/IDE dev runs and Compose. Both read through `Environment`.
- **OQ-5 (quarantine mechanism):** marker file vs dir rename vs DB column. **Recommendation:** `.quarantine` marker file in the workspace root (cheap, no schema change, cleanup job greps for it). DB column deferred unless an operator UI needs to query it (out of scope).
- **OQ-6 (binary files in scan):** **Recommendation:** attempt UTF-8 decode; on decode failure, skip the file and WARN `binary file skipped in secret scan` (a binary runner artifact is unlikely to hold a plaintext key; scanning bytes for regex is noisy). Note the limitation in the ADR.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (1M context) — bmad-dev-story workflow, 2026-05-30.

### Debug Log References

- Fast-tier regression: `mvn -o test` → 612 run, 4 errors, 7 skipped. The 4 errors are
  `WorkflowAdapterEquivalenceTest` (a `@WebMvcTest(WorkflowController)` slice whose `DomainException`
  is not mapped by `ProblemDetailsMapper`) — **pre-existing baseline failures, not introduced here**:
  proven by re-running the suite with the new `spring.config.import` line removed (same 4 still fail).
- `ArchitectureBoundaryTest` (failsafe `integration-test`): 35/35 incl. new `RUNNER_SECRETS_SERVICE_SCOPE`.
- Static gates: `spotless:check` clean, `checkstyle:check` 0 violations, `spotbugs:check` BUILD SUCCESS.

### Completion Notes List

- **AC1/AC2/AC9 (Task 1/2):** `RunnerSecretsService` resolves the per-kind provider key from the
  Spring `Environment` per call (no `@Value`/static cache → rotation works). Env-var NAMES are
  config-driven via `RunnerProperties.secretEnvNames` (`deliveryline.runner.secret-env-names`):
  CODEX→[CODEX_API_KEY, OPENAI_API_KEY] (first present wins, injected under the canonical
  CODEX_API_KEY), CLAUDE→[ANTHROPIC_API_KEY]. Absent/blank → `DomainException(DOCTOR_RUNNER_SECRET_MISSING)`
  with `details.runnerKind` + `details.missingEnvVar` (name only). `.env` loads via
  `spring.config.import: optional:file:./.env[.properties]`.
- **AC3 (Task 3):** `CreateContainerSpec` gained `Map<String,String> environment` (default empty,
  back-compat 4-arg ctor retained). `DefaultDockerEngineGateway` applies it via docker-java
  `withEnv(...)` (Trap T8) and logs env-var COUNT only (Trap T5). `DockerRunnerAdapter.dispatch`
  resolves secrets immediately before the spec build and passes them straight in (Trap T1/T3); mock
  path untouched (Trap T4).
- **AC4/AC5 (Task 4):** new `RunnerSecretScanService` (application.runner) runs both detectors over
  every `input/`+`output/`+`logs/` file — `RedactionPolicyService` for known shapes + a mandatory
  literal-substring check of the injected provider key(s) (re-resolved per call). `RunnerBroker`
  `handleSuccess` runs the scan after artifact harvest and **before** `recordCompleted`; a hit
  records `RUNNER_SECRET_LEAK` (new `FailureCategory`), emits `RUNNER_FAILED` (leakedFile + category
  NAMES only; substring-only hit labelled `injected_provider_key`), and quarantines the workspace
  (`.quarantine` marker). `RunnerWorkspaceCleanupJob` preserves quarantined workspaces (marks
  archived → WARN once; Trap T10). `RunnerWorkspaceStore` gained `readFilesForSecretScan` (strict
  UTF-8, binary skipped per OQ-6), `writeQuarantineMarker`, `isQuarantined`.
  - **Decision (AC4 vs WorkflowTransitionTable):** a leak does NOT drive the workflow-run to FAILED.
    The EXECUTING→FAILED allowlist (`ALLOWED_RUNNER_FAILURE_CATEGORIES`) is deliberately narrow
    (story 1.13 — do-not-widen) and AC4 scopes leak handling to the runner_execution + event +
    quarantine only. The run-state path is left to operator-driven recovery, mirroring the
    late-result branch.
- **AC6 (Task 5):** `docs/adr/0003-runner-secrets-mvp-posture.md` (0002 was taken). Env-var-only
  posture, `docker inspect Config.Env` visibility accepted, upgrade triggers documented.
- **AC7 (Task 6):** `.env.example` placeholders for CODEX_API_KEY/OPENAI_API_KEY/ANTHROPIC_API_KEY;
  GITHUB_TOKEN/LINEAR_API_KEY annotated host-side-only.
- **AC8 (Task 7):** `CHECK_RUNNER_SECRETS` doctor probe (`DoctorService` constant/order/remediation/
  switch + `DoctorProbeAdapter.probeRunnerSecrets()`) reports per-kind presence only; new
  `DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING`. `RunnerProperties` injected via `ObjectProvider`
  so lean doctor contexts still wire (defaults() fallback).
- **AC10 (Task 8):** `RUNNER_SECRETS_SERVICE_SCOPE` ArchUnit rule. **Deviation note:** the epic text
  says `adapters.runner.docker..`, but the actual consumer `DockerRunnerAdapter` lives in
  `adapters.runner` — the rule allows `application.runner..` + `adapters.runner..` (docker-java
  confinement is covered separately by `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY`).
- **OpenAPI (OQ-3):** `runner_secret_leak` added to `FailureCategory`, `WorkflowEventsResponse`
  `@Schema`, the committed `openapi.json` snapshot, and the frontend `schema.d.ts` — manually
  inserted in the generator's declaration order (before `orphan`). **Gate-to-done for the reviewer:**
  a real-boot `OpenApiSnapshotContractTest` run (contract tier, Testcontainers) confirms the live
  `/v3/api-docs` matches the hand-edited snapshot — not runnable in the fast tier here (memory
  `[[openapi-regen-platform-shim]]`).
- **AC11 (Task 9):** RunnerSecretsServiceTest (8), RunnerSecretScanServiceTest (8, incl. known-pattern
  + injected-value in output/ & logs/, no-secret-in-logs sweep, bundle-redaction-engine placeholder),
  RunnerBrokerUnitTest leak-path (recordFailed/event/quarantine/no-complete), DockerRunnerAdapterUnitTest
  (env-not-labels, dispatch-time missing-secret, no-secret-in-dispatch-logs), LocalRunnerWorkspaceStoreTest
  (scan enumeration + binary skip + quarantine round-trip), DoctorProbeAdapterTest (present/missing,
  presence-only), DefaultDockerEngineGatewayTest (withEnv applied / omitted), cleanup-job quarantine-skip,
  ArchUnit.

### File List

**Added (main):**
- deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerSecretsService.java
- deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerSecretScanService.java
- deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/WorkspaceScanFile.java
- docs/adr/0003-runner-secrets-mvp-posture.md

**Modified (main):**
- deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java
- deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java
- deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerWorkspaceCleanupJob.java
- deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java
- deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java
- deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/CreateContainerSpec.java
- deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java
- deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java
- deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java
- deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java
- deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java
- deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowEventsResponse.java
- deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java
- deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java
- deliveryline-backend/src/main/resources/application.yml
- deliveryline-backend/src/main/resources/openapi/openapi.json
- deliveryline-frontend/src/lib/api/schema.d.ts
- .env.example

**Added (test):**
- deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerSecretsServiceTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerSecretScanServiceTest.java

**Modified (test):**
- deliveryline-backend/src/test/resources/application.yml
- deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java
- deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerPropertiesTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerStaleDetectionUnitTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLoggingContractTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/runner/DockerRunnerLifecycleLoggingContractTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerWorkspaceCleanupJobDanglingUnitTest.java
- deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java
- deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java
- deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/DockerLifecycleITSupport.java
- deliveryline-backend/src/test/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGatewayTest.java
- deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStoreTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java
- deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapterTest.java

## Change Log

| Date       | Version | Description                                                                 | Author |
| ---------- | ------- | --------------------------------------------------------------------------- | ------ |
| 2026-05-30 | 0.1     | Story 3.5 implemented (all 11 ACs + logging task); status → review.         | Amelia |

## Review Findings

_bmad-code-review (2026-05-30) — 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the working-tree diff (40 files, +946/-30 tracked + 737 new-file lines). Triage: 1 decision-needed, 2 patch, 11 defer, 9 dismissed. Headline convergence (2 layers): the post-execution scan re-resolves the injected key via `defaultKind()` not the dispatched kind — verified LATENT (the broker's own dispatch also hardwires `defaultKind()` at `RunnerBroker.java:288`, so dispatch+scan coincide today → no active leak), surfaced as a decision._

- [x] [Review][Patch] (FIXED) Document the `defaultKind()` dispatch/scan coupling in `handleSuccess` [`RunnerBroker.java:515-518`] — _(resolved from Decision, option 1: accept as latent)._ The post-execution substring detector re-resolves the injected key via `runnerProperties.docker().defaultKind()` rather than the dispatched kind (AC4 wants "the value **this dispatch** injected"). Verified LATENT: dispatch also hardwires `defaultKind()` at `:288`, so they coincide today and no leak slips through; `RunnerExecutionSnapshot` carries no `runnerKind` (the `runner_kind` column is deferred to 3-19/4-9 per 3.1 OQ-7). Fix: add a comment at `:515` noting both dispatch and scan derive the kind from `defaultKind()` and that the robust per-execution-kind resolution is blocked on the deferred `runner_kind` column; the real fix lands when per-run kind selection arrives (3.3/3.4).

- [x] [Review][Patch] (FIXED) Leak branch: `recordFailed` lacks the ILLEGAL_TRANSITION re-entry guard its sibling `recordCompleted` has, and `quarantine` runs last + unguarded [`RunnerBroker.java:519-538`] — a duplicate `onResult` on a leak-FAILED row re-runs the scan and `recordFailed` (`:520`) throws uncaught (contrast the guarded `recordCompleted` at `:545-549`, story 3.2a AC9). And the `.quarantine` marker write (`:526`) runs after the terminal transition + event; if it throws, the workspace is left deletable by the cleanup job and re-entry cannot re-quarantine. Fix: write the quarantine marker first (best-effort) and guard `recordFailed` against `ILLEGAL_TRANSITION` (or early-return when the row is already terminal).
- [x] [Review][Patch] (FIXED) `onResultSecretLeak…` test omits the load-bearing invariant assertion [`RunnerBrokerUnitTest.java`] — the production comment (`RunnerBroker.java:511-514`) emphasizes a leak deliberately does NOT drive the workflow-run state, but the test verifies only `recordFailed`/`quarantine`/event/never-`recordCompleted`. Add `verify(workflowTransitionService, never()).transition(...)` to pin the no-run-state-drive claim.

- [x] [Review][Defer] `onResult` leak-handling is not atomic [`RunnerBroker.java:519-538`] — DB FAILED + event append + `.quarantine` marker are sequential with no surrounding transaction; a mid-branch failure leaves inconsistent state. Pre-existing pattern (`onResult` is non-transactional throughout). — deferred, pre-existing
- [x] [Review][Defer] Quarantined workspace marked `archived`, indistinguishable from a reaped one [`RunnerWorkspaceCleanupJob.sweepWorkspaces`] — same status used for cleaned/deleted workspaces; no query distinguishes a leak-preserved workspace (files on disk) from a reaped one. Deliberate per Completion Notes; operator-UI DB column deferred (OQ-5). — deferred
- [x] [Review][Defer] Substring leak detector has no minimum-length/entropy guard [`RunnerSecretScanService`] — a short/low-entropy provider-key value (dev placeholder) can false-positive every file and force-quarantine a clean workspace; `resolveSecretsForRunner` rejects only blank. Real keys are long. — deferred
- [x] [Review][Defer] Doctor `probeRunnerSecrets` hard-FAILs unless EVERY `RunnerKind` has a key [`DoctorProbeAdapter`] — a single-runner operator gets a permanent FAIL for a kind they never use. Matches AC8 as written; "enabled kinds only" is a future product notion. — deferred
- [x] [Review][Defer] Scan re-invokes `resolveSecretsForRunner` for the value — doubles resolve INFO logs + spurious "secret missing" WARN on mid-run rotation-to-blank [`RunnerSecretScanService.resolveInjectedValues`] — log hygiene only. — deferred
- [x] [Review][Defer] AC11(f) bundle-redaction test is tautological [`RunnerSecretScanServiceTest`] — calls `redact()` on a literal string; never seeds a fake key into source data nor asserts the *persisted* context bundle holds the placeholder. Real coverage gap; a proper test needs the dispatch/persist path. — deferred
- [x] [Review][Defer] `readFilesForSecretScan` loads every workspace file fully into memory with no size cap [`LocalRunnerWorkspaceStore`] — `Files.readAllBytes` + UTF-8 String, all files accumulated at once; logs grow unbounded → OOM/memory-pressure hazard. — deferred
- [x] [Review][Defer] No-leak logging tests assert only the test's own literal value is absent [`DockerRunnerAdapterUnitTest` / `RunnerSecretScanServiceTest`] — `doesNotContain(<constant>)` is weaker than the regex sweep AC2/AC11(g) specify; a different secret value or a prefix/hash of the key would pass. — deferred
- [x] [Review][Defer] Doctor probe vs dispatch can disagree on env-var names in lean contexts [`DoctorProbeAdapter`] — probe falls back to `RunnerProperties.defaults()` while dispatch uses the bean-bound properties; a customized `secret-env-names` diverges. — deferred
- [x] [Review][Defer] Gateway flattens env to `key + "=" + value` with no char validation [`DefaultDockerEngineGateway`] — a configured env-var name containing `=`/newline, or a value with a newline, yields a malformed/ambiguous docker env pair. Names/values are operator-config-sourced. — deferred
- [x] [Review][Defer] Plaintext provider key resides in GC-retained `String` instances with no zeroing [`RunnerSecretsService` / `CreateContainerSpec`] — heap-dump residency unaddressed; beyond the documented MVP env-var posture (ADR-0003). — deferred
