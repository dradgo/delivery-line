# Story 3d.7: Post-Execution Provider Usage/Limit Status — 5h/Weekly (Spike-Gated)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator,
I want to see the agent provider's usage/limit status after a step runs,
So that I can decide between automated and manual execution before exhausting a 5-hour or weekly window.

## Context

This is the **provider-limit-awareness slice** of Epic 3d (per-step execution control), implementing **PRD FR69** and decision **D5** of `sprint-change-proposal-2026-06-21.md`. It is **SPIKE-GATED**: the story opens with a spike confirming whether the Claude CLI / Anthropic API **and** Codex expose 5-hour / weekly window status programmatically in headless mode. **If the signal is unavailable, the feature still ships** — as a documented "not exposed by provider" state with the UI/CLI degrading to that state, never a fabricated value. There is **no dedicated ADR** for this story; the capture path rides the existing runner-contracts output metadata, and the same-moment redaction / localhost posture follows **ADR 0025**.

It builds entirely on **done** substrate:

- **Story 3.4/3.5/3.8 (done)** — `RunnerSecretsService` resolves the agent-provider auth a runner container needs per dispatch. **Critical:** the provider auth is sourced from the Spring `Environment` (system env + `.env`) and, for Codex, the host `~/.codex/auth.json` file — Claude reads `CLAUDE_CODE_OAUTH_TOKEN` (subscription) **or** `ANTHROPIC_API_KEY` (per-token), two DISTINCT credential modes. **The provider account is NOT modeled as an Epic-3c `project_credentials` row** (those roles are `ticket_source`/`repo_host`/`reviewer`). See Trap T1 — "per-credential attribution" must key on a non-secret account label, never a `project_credentials` FK.
- **Story 1.13 + runner-contracts (done)** — `runners/{claude,codex}/lib/runner.mjs` `commandBuild()` assembles the output object and writes `/workspace/output/runner-result.v1.json`; the schema `deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json` validates it (`normalizedOutput` is `additionalProperties:false`). `RunnerBroker.onResult`/`handleSuccess` validates + reads the parsed result on the backend.
- **Story 1.10 (done)** — `RedactionPolicyService` + the two-gate adversarial fixture corpus (`redaction-fixtures/fixtures-manifest.json` manifest **and** the hardcoded set in `RedactionPolicyServiceContractTest`) — AC4's no-secret assertion plugs in here.
- **Story 2.14 + 3d-5 (done)** — `AllowedAction` registry + `WorkflowInspectionService.computeActionMatrix` (the SOLE state×role source) + the `RegistryContractTest` drift gate vs `contracts/frontend/allowed-actions.placeholder.json`. 3d-5 added `view_runner_logs` the exact same way this story adds `view_provider_usage_status`.
- **Story 3c-10 / observability (done)** — `RunnerQueueMetricsBinder` + `ProjectHealthMetricsBinder` establish the **weak-ref-safe** `MeterBinder` pattern (hold snapshot state in a strongly-referenced field, recompute on a short cache window) — AC6's optional per-provider gauge mirrors it.

**What this story IS:** a spike → (if confirmed) runner.mjs captures the provider 5h/weekly status and emits it in the **existing** runner-result output metadata → backend persists a **per-credential, non-secret** usage snapshot keyed to the run → UI **Provider Limit Status indicator** + CLI surface it (color-independent, labelled provider-reported + as-of) → optional weak-ref-safe observability gauge. Plus one new allowed-action `view_provider_usage_status`.

**What this story is NOT:** NOT a new runner mount or runner-contract version bump (the field is an **optional, additive** `normalizedOutput` property — no `schemaVersion` change). NOT a secret store (no token/key/account-secret persisted — only window/quota numbers + timestamps + a non-secret account label). NOT gating/throttling (we surface status; we do NOT block, queue-defer, or auto-switch to manual based on it — that is a later decision). NOT a fabricated value when the provider does not expose the signal (the negative spike branch ships the documented "not exposed" state). NOT a new `WorkflowEventType` (avoids the two-fixture-site trap).

**Dependency note:** Depends on **3d-5 (done)** (the same allowed-action recipe + run-detail surface slot) — sprint-status lists "Deps: 3d-5 (or independent)". It is otherwise independent of unbuilt 3d siblings (3d-2/3d-4/3d-6).

## Acceptance Criteria

1. **Spike gate (D5).** The story begins with a spike confirming whether the Claude CLI / Anthropic API **and** Codex expose 5-hour/weekly window status programmatically in headless mode; the spike outcome is recorded (in Completion Notes + the per-step-execution-control doc seam). **If the signal is unavailable**, the feature ships as a documented **"not exposed by provider"** state rather than a fabricated value, and the UI/CLI degrade to that state.
2. **Confirmed-signal capture rides the existing contract.** Given a confirmed signal, when a runner execution finishes, the runner (`runner.mjs`) captures the provider usage/limit status and emits it in the runner-contracts **output metadata** — an **optional, additive** field on `normalizedOutput` (no new mount, no `schemaVersion` bump). Both `claude` + `codex` runners (Dockerfiles + entrypoints + offline mocks) are updated in the **same PR** per the runner-contract change rule.
3. **Per-credential, non-secret persistence.** Given the captured status, the backend persists a usage/limit snapshot keyed to the workflow run **and** the account that produced it (a **non-secret account reference** — see Trap T1), so the value is attributable to the producing account; **no secret material is ever persisted**.
4. **No secret in the payload (adversarial fixture).** The usage/limit payload is asserted to contain no secret material via a new `redaction-fixtures/` entry wired through **both** gates (manifest + the hardcoded corpus set in `RedactionPolicyServiceContractTest`); only window/quota numbers + timestamps (+ the non-secret account label) are stored/surfaced.
5. **UI + CLI surface, color-independent, labelled.** After a run, the UI **Provider Limit Status indicator** and the CLI surface the 5-hour and weekly status (or the "not exposed" state) with a **color-independent** signifier (icon + label), and values are clearly labelled as **provider-reported** and **as-of a timestamp**. Access is gated by a new backend-reported allowed-action `view_provider_usage_status` (mirrored into the frontend placeholder; enforced server-side).
6. **Observability (optional, profile-gated, weak-ref-safe).** When the observability profile is active, per-provider limit status may be surfaced consistently with existing local observability via a `MeterBinder`; any gauge holds its snapshot state in a strongly-referenced field (weak-ref-NaN guard).
7. **Tests.** Coverage asserts: the spike-confirmed path captures + persists + surfaces the snapshot; the "signal unavailable" path degrades to the documented "not exposed" state; no secret in the payload (fixture, both gates); per-credential attribution (the non-secret account reference, not a project_credentials FK); CLI/UI parity; allowed-action gating server-side; and a default/legacy run that emits no usage field is byte-identical to pre-3d.

## Tasks / Subtasks

- [x] **Task 0 — SPIKE: confirm the headless provider-usage signal (AC1)** — GATE; record the outcome before building capture
  - [x] Investigate, in **headless** mode (as the runner runs the CLI — `claude -p --dangerously-skip-permissions`, Codex non-interactive), whether the **5-hour rolling** and **weekly** window status is obtainable WITHOUT interactive auth:
    - Claude subscription (`CLAUDE_CODE_OAUTH_TOKEN`): is there a CLI subcommand / `/usage` output / JSON channel exposing the 5h+weekly windows? Anthropic API (`ANTHROPIC_API_KEY`): `anthropic-ratelimit-*` response headers + any usage/limit endpoint.
    - Codex/OpenAI (`CODEX_AUTH_JSON` / `OPENAI_API_KEY`): equivalent usage/limit surface in headless mode.
  - [x] Record per-provider: **signal present?**, **how captured** (header/CLI-output/endpoint), **what fields** (used, limit, fraction, reset-at), and whether it needs an extra network call vs riding the run's existing response.
  - [x] **Decision:** for each provider, set the capture strategy OR mark "not exposed". The story is deliverable on **either** branch — confirmed providers build Tasks 2–4 capture; unconfirmed providers wire the **"not exposed"** state (Task 5/6 still ship; runner emits an explicit `signalState: "not_exposed"` marker, never a fabricated number). → **Decision: both providers ship the `not_exposed` branch; mock exercises the `available` shape.** (See Completion Notes.)
  - [x] Write the spike result into the Completion Notes + leave a pointer for 3d-10's `per-step-execution-control-walkthrough.md` (the provider-limit section, AC5 of 3d-10).
- [x] **Task 1 — runner-contracts output schema: optional `providerUsage` metadata (AC2, AC7)** — additive v1 patch, NO `schemaVersion` bump
  - [x] In `deliveryline-runner-contracts/.../schemas/runner-result.v1.schema.json`, add an **optional** `providerUsage` object to `normalizedOutput.properties` (keep `normalizedOutput.required` = `["summary","outcome"]`; the field is NOT required). Because `normalizedOutput` is `additionalProperties:false`, the property MUST be declared explicitly — define its own `additionalProperties:false` shape: `signalState` (`enum:["available","not_exposed"]`), `accountLabel` (non-secret string, e.g. `"claude:oauth"`/`"claude:api"`/`"codex:subscription"`), `fiveHour` + `weekly` window objects (each: optional `usedFraction` number 0..1, optional `used`/`limit` integers, optional `resetsAt` date-time), and `asOf` date-time. NO secret/token field permitted by the schema. → Added `$defs/providerUsage` + `$defs/usageWindow`; `providerUsage` referenced from `normalizedOutput.properties`. Added valid fixture `runner-result.v1.spec.provider-usage.valid.json`.
  - [x] Mirror the change per the runner-contract change rule (`RUNNER_CONTRACT.md` AC10): if either runner Dockerfile/entrypoint references the schema/mount, update **both** `claude` + `codex` in this PR. Validate the conformance ITs (`ClaudeRunnerImageConformanceIT`, `CodexRunnerImageConformanceIT`) stay green.
  - [x] If the runner-contracts jar is consumed from `.m2`, rebuild/install it (or use `-am`) so backend tests validate against the NEW schema (memory `[[runner-contracts-schema-stale-in-m2]]`). → flagged for the backend build step (`-am` / install runner-contracts).
- [x] **Task 2 — runner.mjs capture + emit (AC2)** — confirmed-signal branch; mirror in BOTH runners + mocks
  - [x] In `runners/claude/lib/runner.mjs` (+ `runners/codex/lib/runner.mjs`) `commandBuild()`, after the agent run, capture the provider usage per the Task-0 strategy and merge a `providerUsage` object into `normalizedOutput`. Source the **non-secret** `accountLabel` from the auth-mode the runner used (which env var was set — already non-secret; never the token value). On any capture failure or unconfirmed provider → emit `providerUsage: { signalState: "not_exposed", accountLabel, asOf }` (graceful; never throw, never fabricate). → `buildProviderUsage(authVar)` + `sanitizeUsageWindow`; entrypoints pass `--auth-var "$AUTH_KEY_VAR"`.
  - [x] In the entrypoints (`runners/{claude,codex}/entrypoint.sh`), wire whatever the capture strategy needs (e.g. an extra usage probe after CLI exit, or header capture) WITHOUT changing exit-code semantics; a usage-capture failure must NOT fail the run. → `--auth-var` added to the step-6 build invocation in both entrypoints; capture is best-effort in runner.mjs (try/catch → not_exposed).
  - [x] **Offline mocks** (`runners/claude/test/mock-claude.sh`, codex equivalent; `INSTALL_*_CLI=false` Dockerfile branch): emit a **deterministic** mock `providerUsage` (e.g. via a temp file / env the runner reads) so conformance ITs are env-blind (memory `[[runner-tool-self-test-needs-offline-mock]]`). The mock SHOULD exercise the `available` shape so the happy path is covered offline. → mock signal baked into the `INSTALL_*_CLI=false` Dockerfile branch at `/opt/deliveryline/test/provider-usage.mock.json` (env `DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE`); mock-CLI scripts left env-free (preserves their negative-log property). Node unit tests added (`runner-provider-usage.test.mjs`, both runners, 4 each, green).
- [x] **Task 3 — backend capture + per-credential snapshot persistence (AC3, AC7)**
  - [x] After successful result validation in `RunnerBroker.handleSuccess` (after the parse at ~line 1292, before/around artifact ingestion ~line 1318), read `parsed.path("normalizedOutput").path("providerUsage")`; absent → no-op (legacy/default byte-identical, AC7). Keep this read tolerant (optional field).
  - [x] **Persistence (recommended: dedicated table).** New `V21__add_provider_usage_snapshots.sql`: `provider_usage_snapshots(id bigserial PK, public_id text UNIQUE CHECK ^pul_[A-Za-z0-9_-]{4,64}$, workflow_run_id bigint FK, runner_execution_id text NULL, account_reference text NOT NULL, signal_state text NOT NULL CHECK in ('available','not_exposed'), five_hour_used_fraction numeric NULL, five_hour_used int NULL, five_hour_limit int NULL, five_hour_resets_at timestamptz NULL, weekly_used_fraction numeric NULL, weekly_used int NULL, weekly_limit int NULL, weekly_resets_at timestamptz NULL, as_of timestamptz NULL, created_at timestamptz NOT NULL DEFAULT now(), archived_at timestamptz NULL)`. **NO secret/token column.** `account_reference` = the non-secret `accountLabel` (Trap T1). Mirror the entity/mapper/SPI-port/adapter pattern of `BatchSubmissionPersistenceAdapter` (entity `@PrePersist` createdAt MICROS-truncated; adapter maps `DataIntegrityViolationException` → typed error).
  - [x] Register the new public-id prefix `pul_` in `PublicIdPrefixes` (+ matching CHECK constraint name) and add `provider_usage_snapshots` + its prefix to `FlywaySchemaContractTest` (CORE_TABLES + EXPECTED_PUBLIC_ID_PREFIX) — the schema drift test fails until both are added.
  - [x] **Decision/OQ-1:** if a dedicated table is judged too heavy, the lighter alternative is metadata-only columns on `runner_executions` (mirrors 3.6 `recordRawOutput`) — but that loses the clean "per-credential snapshot" shape and the `pul_` lineage. Default to the dedicated table; flag the choice in the PR.
- [x] **Task 4 — adversarial no-secret fixture (AC4, AC7)** — TWO gates required (memory `[[redaction-fixture-two-gates]]`)
  - [x] Add `deliveryline-backend/src/test/resources/redaction-fixtures/provider-usage-snapshot.json` — a representative `providerUsage` payload (window numbers + timestamps + a non-secret `accountLabel`), deliberately seeded with secret-shaped strings in adjacent positions to prove the redactor/structure rejects them.
  - [x] Gate 1: add the manifest entry to `redaction-fixtures/fixtures-manifest.json` (`file`, `placeholder`, `minimumClassification`, `forbiddenSnippets`). Gate 2: add the filename to the **hardcoded** corpus set in `RedactionPolicyServiceContractTest#fixtureManifestMustCoverEveryCorpusFile` — missing this reds the default `verify` (memory `[[redaction-fixture-two-gates]]`).
  - [x] Add a focused test asserting the persisted snapshot row carries NO secret-shaped material (only numbers/timestamps/account label).
- [x] **Task 5 — `view_provider_usage_status` allowed-action + read REST endpoint (AC5)** — registry recipe, exactly mirroring 3d-5's `view_runner_logs`
  - [x] Add `VIEW_PROVIDER_USAGE_STATUS("view_provider_usage_status")` to `org.dradgo.domain.registry.AllowedAction`. Wire into `WorkflowInspectionService.computeActionMatrix` for the post-execution states where a runner execution exists (`EXECUTING`, `WAITING_FOR_REVIEW`, `FAILED`, `PAUSED`) — role-agnostic, exactly as `view_runner_logs`. Do NOT add to pre-execution states.
  - [x] Mirror `"view_provider_usage_status"` into `contracts/frontend/allowed-actions.placeholder.json` so `RegistryContractTest#allowedActionsStayAlignedWithFrontendPlaceholder` passes.
  - [x] NEW read endpoint (e.g. `GET /api/v1/workflows/{workflowRunId}/provider-usage`) returning the latest snapshot for the run (`signalState` + windows + `accountLabel` + `asOf`), or an empty/`not_exposed` body. **Server-side gating** (the real guard, not just the UI): compute allowed-actions via `WorkflowInspectionService.getAllowedActions(runId, actorRole)` and reject when `view_provider_usage_status` is absent (mirror `RunnerLogStreamController` ~lines 145-166). Localhost-only is inherited from `server.address=127.0.0.1` + `RestBindingGuard` (add NO new binding).
  - [x] Annotate for OpenAPI; run `scripts/regen-openapi.sh` then `npm run generate-api`, commit `schema.d.ts` — or the `check:api` → foundation-gate cascade reds (memory `[[openapi-regen-frontend-client-drift-cascade]]`).
- [x] **Task 6 — CLI parity (AC5, AC7)**
  - [x] Surface the snapshot in the Spring Shell CLI (`adapters/cli/WorkflowCommands.java`) — either a new `provider-usage <runId>` command or a `--include-provider-usage` flag on `status`. Render the 5h + weekly status (or "not exposed") with **color-independent** markers (e.g. `[5h: 95/100, resets 14:20]`, `[weekly: not exposed]`); label values provider-reported + as-of. Resolve the actor + gate on the same allowed-action server-side path.
- [x] **Task 7 — Frontend Provider Limit Status indicator (AC5)**
  - [x] NEW data hook `src/features/workflows/hooks/useProviderUsageStatus.ts` (a standard React-Query `useQuery` over the new REST endpoint via the generated `openapi-fetch` client — NOT SSE; this is a one-shot post-run read, not a stream).
  - [x] NEW `src/features/workflows/components/ProviderLimitStatus.tsx`: renders the 5h + weekly status (or the documented "not exposed" state) using a **color-independent** `StateSignifierChip` (icon + label, UX-DR2; e.g. `success`/`warning`/`stale`), labelled provider-reported + as-of timestamp; announces load/critical via `useLiveAnnouncement` + new strings in `src/lib/a11y/announcements.ts`.
  - [x] Slot into the run-detail route `src/routes/workflows/$workflowRunId/index.tsx` (near the 3d-5 `StepExecutionLogViewer`). **Gate** on `useAllowedActions(workflowRunId).data?.actions.includes('view_provider_usage_status')` ONLY (eslint `local-rules/no-role-based-action-gating` — never infer from role).
- [x] **Task 8 — Observability gauge (optional, AC6)** — weak-ref-safe (memory `[[micrometer-gauge-weak-ref-nan-flake]]`)
  - [x] IF surfacing metrics: NEW `MeterBinder` (e.g. `ProviderLimitMetricsBinder`) mirroring `ProjectHealthMetricsBinder` — hold the snapshot in a `volatile`-field, recompute on a ~1s cache window, serve-stale-on-error (never crash the scrape). Gauge tags per provider/account-label. Prometheus is disabled in `@SpringBootTest` (memory `[[prometheus-actuator-disabled-in-springboottest]]`) — re-enable per-IT with `@TestPropertySource`.
- [x] **Task 9 — Tests (AC7)**
  - [x] Backend: `RunnerBroker` reads `providerUsage` and persists a snapshot (confirmed path); absent field → no row + legacy byte-identical; "not exposed" emit persists `signal_state='not_exposed'`; per-credential `account_reference` asserted (NOT a project_credentials FK); endpoint gating denies when action absent; `FlywaySchemaContractTest` + `RegistryContractTest` green; redaction fixture (both gates) + no-secret persisted-row sweep.
  - [x] Frontend: Vitest — indicator renders 5h/weekly; renders "not exposed" degradation; live-region announcement via `waitFor` (memory `[[livesnnouncement-defers-one-commit-test-flake]]`); gate hides when action absent; axe zero `wcag2aa`. Playwright e2e: run with snapshot present → indicator visible + values render (JSON fixtures `with { type: 'json' }`, memory `[[playwright-e2e-harness-wiring]]`). Run `prettier --write` before push.
  - [x] Verify CI-shape on Linux (lockfile/native bindings, memory `[[frontend-lockfile-cross-platform]]`, `[[verify-ci-fixes-in-clean-env]]`).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at: `RunnerBroker` usage-snapshot capture decision (`INFO` available/not_exposed + `accountLabel` + counts; NEVER the token), snapshot persistence (`INFO` with `pul_` public id, `WARN` on `DataIntegrityViolationException`); the read endpoint open + gating decision (`INFO`/`WARN` on denial); the CLI read; the runner.mjs capture (best-effort, `WARN` on capture failure — never a token).
  - [x] Parameterized logging (`log.info("...", arg1, arg2)`) — never concatenation.
  - [x] Levels: `INFO` normal lifecycle + decision, `WARN` for capture-unavailable / fallback / gating denial, `ERROR` only for unhandled failure.
  - [x] Carry `correlationId`, `workflowRunId`, `runnerExecutionId`, plus the snapshot `pul_` id via MDC/params. **NEVER** log tokens, raw provider auth, account secrets, or full payload bytes — window numbers + `accountLabel` only.
  - [x] ≥1 focused test per new branch asserting the expected log line/level + an adversarial no-secret-in-logs sweep.

## Dev Notes

### Architecture & insertion points (verified against live code)

**Runner (Node)**
- `runners/claude/lib/runner.mjs` `commandBuild()` (~lines 197-273) assembles `result.normalizedOutput = { summary, outcome }` and writes `/workspace/output/runner-result.v1.json`. Add `providerUsage` here. Mirror in `runners/codex/lib/runner.mjs`. Auth-mode (for the non-secret `accountLabel`) is known from which env var the entrypoint received (`CLAUDE_CODE_OAUTH_TOKEN` vs `ANTHROPIC_API_KEY`; Codex `CODEX_AUTH_JSON`).
- Offline mocks: `runners/claude/test/mock-claude.sh` (and codex), installed when `INSTALL_*_CLI=false` (Dockerfile mock branch ~lines 86-106). The mock must emit a deterministic `providerUsage` so conformance ITs pass env-blind.
- **Runner-contract change rule** (`RUNNER_CONTRACT.md` AC10): any schema/mount/exit-code change updates BOTH runner Dockerfiles + entrypoints in the same PR.

**runner-contracts**
- `deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json` — `normalizedOutput` (~lines 46-65) is `additionalProperties:false`, `required:["summary","outcome"]`. Add `providerUsage` to `properties` only (NOT to `required`) — backward-compatible additive v1 patch, NO `schemaVersion` bump. Existing additive precedent: optional context-bundle fields were added the same way.

**Backend**
- `application/runner/RunnerBroker.java` `onResult` (~1276) validates `ValidationTarget.RUNNER_RESULT`; `handleSuccess` (~1329+) holds the parsed `JsonNode` (~1292). Read `providerUsage` post-validation, pre-artifact-ingest. The broker does NOT currently consume `normalizedOutput` — this is the first reader; keep it tolerant of absence.
- **No credential FK on runner_executions** — `runner_executions` (V1 ~206-234, entity `RunnerExecutionEntity`) keys to `workflow_run_id` only; there is NO credential/connector column. `RunnerSecretsService` (`application/runner/RunnerSecretsService.java`) sources provider auth from `Environment`/host file, NOT `project_credentials`. So attribution = the runner-emitted non-secret `accountLabel`, persisted as `account_reference` (Trap T1).
- Persistence pattern to mirror: `adapters/persistence/BatchSubmissionPersistenceAdapter.java` (+ `BatchSubmissionEntity` `@PrePersist`, mapper, `BatchSubmissionWritePort`/`ReadPort`, repository) and the V15 migration shape (bigserial id + text public_id + CHECK + created_at + nullable archived_at). `PublicIdPrefixes.java` (~12-28) for the new `pul_` prefix. `FlywaySchemaContractTest` (CORE_TABLES + EXPECTED_PUBLIC_ID_PREFIX) MUST learn the new table+prefix.
- **Next free Flyway version = V21** (current head: `V20__add_manual_execution_kind_and_state.sql`). Replay-safe, additive.
- Redaction two-gate: `RedactionPolicyService` + `redaction-fixtures/fixtures-manifest.json` + the hardcoded set in `RedactionPolicyServiceContractTest#fixtureManifestMustCoverEveryCorpusFile`.
- AllowedAction matrix: `domain/registry/AllowedAction.java` + `application/workflow/WorkflowInspectionService.java#computeActionMatrix` (~620-719) + `contracts/frontend/allowed-actions.placeholder.json` + `RegistryContractTest#allowedActionsStayAlignedWithFrontendPlaceholder`. 3d-5's `VIEW_RUNNER_LOGS` is the exact template (added to EXECUTING/WAITING_FOR_REVIEW/FAILED/PAUSED, role-agnostic).
- Server-side gating template: `adapters/rest/RunnerLogStreamController.java` (~145-166). Localhost-only via `server.address=127.0.0.1` + `RestBindingGuard` (story 6.9) — no new binding.

**Frontend**
- Run-detail route `src/routes/workflows/$workflowRunId/index.tsx` — 3d-5's `StepExecutionLogViewer` is slotted + gated on `useAllowedActions(...).actions.includes('view_runner_logs')` (~131-155). Mirror for `ProviderLimitStatus` + `view_provider_usage_status`.
- Standard typed read: generated `openapi-fetch` client via `src/lib/api/schema.d.ts` (regen with `npm run generate-api`). Use React-Query `useQuery` (one-shot read), NOT EventSource (that was SSE-specific to 3d-5).
- Color-independent signifier: `src/lib/state-signifiers.ts` (`STATE_SIGNIFIERS` icon+label) + `StateSignifierChip`. Live-region: `src/lib/a11y/useLiveAnnouncement.ts` (defers one commit — `waitFor`) + `announcements.ts`. eslint `local-rules/no-role-based-action-gating` forbids role inference.

**Observability**
- `infrastructure/observability/ProjectHealthMetricsBinder.java` (~33-55) / `RunnerQueueMetricsBinder.java` — weak-ref-safe pattern: strongly-referenced `volatile` snapshot field, ~1s cache window, serve-stale-on-error. `@SpringBootTest` disables Prometheus (re-enable per-IT with `@TestPropertySource`).

### Key design decisions

- **Spike-first, deliverable either way.** Task 0 decides per-provider capture-vs-not-exposed. The negative branch is a real deliverable (the documented "not exposed" state + indicator + CLI + tests), NOT a no-op — the epic explicitly does not block on the signal.
- **Additive optional field, no contract version bump.** `providerUsage` rides `normalizedOutput` as an optional property; legacy runs (and any runner not emitting it) stay byte-identical (AC7). No new mount, no `schemaVersion` change.
- **Per-credential attribution without a project_credentials FK (Trap T1).** Provider auth is env/host-file sourced and is two distinct Claude modes (OAuth vs API key); model attribution as a runner-emitted **non-secret** `accountLabel` → `account_reference` column. Never an FK to `project_credentials`, never a token-derived secret.
- **Status, not control.** We surface the windows; we do NOT throttle, defer, or auto-route to manual based on them. That cross-feature decision is out of scope.
- **Numbers + timestamps only.** The persisted/surfaced payload is window/quota numbers + reset/as-of timestamps + the non-secret label. The adversarial fixture (AC4) proves no secret leaks.

### What NOT to add (scope guard)

- NO runner-contract `schemaVersion` bump / new mount (additive optional field only).
- NO `WorkflowEventType` (this is post-run data capture, not a governed lifecycle event) — avoids the two-fixture-site trap (memory `[[new-workfloweventtype-fixture-sites]]`).
- NO new `DomainErrorCode` unless genuinely required — the "not exposed"/"unavailable" states are **data**, not errors; gating reuses the existing allowed-action denial. If unavoidable, follow the three-sites recipe.
- NO `project_credentials` row / FK for the provider account; NO token, key, or account-secret column anywhere.
- NO gating/throttling/auto-manual-switch behavior built on the status.

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out`/`printStackTrace()`.
- **Where:** `RunnerBroker` capture decision + snapshot persistence (`INFO`/`WARN`); read endpoint open + gating (`INFO`/`WARN`); CLI read; runner.mjs capture best-effort (`WARN` on failure).
- **Context keys:** `correlationId`, `workflowRunId`, `runnerExecutionId`, snapshot `pul_` id (MDC/params).
- **Forbidden in output:** provider tokens, raw auth, account secrets, payload bytes — log window numbers + non-secret `accountLabel` only. Adversarial no-secret-in-logs sweep required.

### Project Structure Notes

- New snapshot table/entity/mapper/port/adapter follow the existing persistence split (entity in `adapters.persistence.entity`, mapper in `adapters.persistence.mapper`, write/read ports in `application.runner.spi` or `application.workflow.spi`, adapter in `adapters.persistence`). Read endpoint controller in `adapters.rest`. CLI in `adapters.cli`.
- Runner changes are symmetric across `runners/claude` + `runners/codex` (+ mocks) — never one without the other.

### References

- [Source: _bmad-output/planning-artifacts/epic-03d-per-step-execution-control.md#Story 3d-7] — AC1-7, FR69.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-21.md] — D5 (spike-gated), Section 2 #7 (signal unproven), Risk "Provider-limit signal may not exist".
- [Source: _bmad-output/planning-artifacts/prd.md#FR69].
- [Source: _bmad-output/implementation-artifacts/3d-5-live-and-historical-step-log-viewing.md] — `view_runner_logs` allowed-action recipe + run-detail surface slot + color-independent signifier (twin pattern).
- [Source: docs/adr/0025-live-observability-and-readonly-console.md] — same-moment redaction / localhost posture.
- Memory: `[[runner-contracts-schema-stale-in-m2]]`, `[[runner-tool-self-test-needs-offline-mock]]`, `[[redaction-fixture-two-gates]]`, `[[new-workfloweventtype-fixture-sites]]`, `[[openapi-regen-frontend-client-drift-cascade]]`, `[[micrometer-gauge-weak-ref-nan-flake]]`, `[[prometheus-actuator-disabled-in-springboottest]]`, `[[livesnnouncement-defers-one-commit-test-flake]]`, `[[playwright-e2e-harness-wiring]]`, `[[frontend-lockfile-cross-platform]]`, `[[verify-ci-fixes-in-clean-env]]`, `[[validated-config-needs-test-yaml]]`, `[[one-active-per-key-needs-partial-unique-index]]`.

## Declared Traps

- **T1 — per-credential attribution is a NON-SECRET label, not a project_credentials FK.** Provider auth is sourced by `RunnerSecretsService` from env/host file (Claude OAuth vs API key = two modes; Codex auth.json), NOT modeled as a `project_credentials` row. Attribute via a runner-emitted non-secret `accountLabel` → `account_reference`. Never an FK, never a token-derived value.
- **T2 — additive optional field; `normalizedOutput` is `additionalProperties:false`.** The new `providerUsage` MUST be declared in `properties` (not just appended) and MUST NOT be added to `required`; do NOT bump `schemaVersion`. Legacy/absent → byte-identical (AC7).
- **T3 — both runners + both mocks + same PR.** Claude AND Codex runner.mjs/entrypoint/Dockerfile/mock change together (RUNNER_CONTRACT.md AC10); the offline mock must emit a deterministic `providerUsage` or conformance ITs go red.
- **T4 — redaction fixture needs BOTH gates.** Manifest entry AND the hardcoded corpus set in `RedactionPolicyServiceContractTest` — missing the latter reds default `verify` (memory `[[redaction-fixture-two-gates]]`).
- **T5 — server-side gating is the real guard.** Enforce `view_provider_usage_status` on the read endpoint (mirror `RunnerLogStreamController`), not only the UI gate.
- **T6 — Flyway + schema drift.** New table → `FlywaySchemaContractTest` CORE_TABLES + EXPECTED_PUBLIC_ID_PREFIX; new `pul_` prefix → `PublicIdPrefixes` + CHECK constraint name. Use the partial-index pattern only if a "one active per (run, account)" rule is wanted (memory `[[one-active-per-key-needs-partial-unique-index]]`).
- **T7 — OpenAPI/client drift.** Regen `openapi.json` → `npm run generate-api` → commit `schema.d.ts`, or the `check:api` → foundation-gate cascade reds (memory `[[openapi-regen-frontend-client-drift-cascade]]`).
- **T8 — runner-contracts jar staleness.** Editing the schema then running backend-only `mvnw test` validates against the OLD `.m2` jar — install runner-contracts or use `-am` (memory `[[runner-contracts-schema-stale-in-m2]]`).
- **T9 — weak-ref gauge NaN.** Any observability gauge MUST hold its snapshot in a strongly-referenced field (memory `[[micrometer-gauge-weak-ref-nan-flake]]`); Prometheus is off in `@SpringBootTest` (memory `[[prometheus-actuator-disabled-in-springboottest]]`).

## Open Questions

- **OQ-1 (persistence shape):** Dedicated `provider_usage_snapshots` table (recommended, clean per-credential lineage + `pul_` id) vs metadata-only columns on `runner_executions` (lighter, mirrors 3.6 `recordRawOutput`). Default = dedicated table; confirm in PR.
- **OQ-2 (spike outcome per provider):** Does the headless Claude subscription path expose 5h+weekly windows at all (vs only the Anthropic API `anthropic-ratelimit-*` headers, which describe API-key rate limits, not the 5h/weekly subscription windows)? If only one provider/path is confirmed, ship that provider's `available` state and the other's `not_exposed` — both branches are valid deliverables.
- **OQ-3 (window-kind modeling):** Explicit `five_hour_*` + `weekly_*` columns (recommended — avoids a new central registry/value-set + its drift surface) vs a `window_kind` discriminator with a new registry. Default = explicit columns.
- **OQ-4 (capture cost):** If capture needs an extra network call after the run, confirm it cannot fail or slow the run (best-effort, time-boxed, never blocks completion or changes exit codes).
- **OQ-5 (new DomainErrorCode):** Confirm none is needed (states are data; gating reuses existing denial). Add only if a genuine typed error surfaces, via the three-sites recipe.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (bmad-dev-story workflow)

### Debug Log References

### Completion Notes List

#### Task 0 — SPIKE outcome (AC1): provider 5h/weekly signal is NOT exposed headless → ship documented "not_exposed" state

Investigated whether the 5-hour rolling and weekly **subscription** window status is obtainable in the
**headless** invocation the runner uses, WITHOUT interactive auth, for each provider/auth-mode:

- **Claude subscription (`CLAUDE_CODE_OAUTH_TOKEN`)** — In headless `claude -p --dangerously-skip-permissions`
  mode there is no documented, stable channel that emits the 5h/weekly subscription window status. The
  interactive `/usage` view is a session slash-command, not a headless subcommand; `-p --output-format json`
  returns per-request token `usage`/cost for *that* call, **not** the rolling 5h/weekly window quota.
  → **not_exposed** for the 5h/weekly semantics this story targets.
- **Anthropic API (`ANTHROPIC_API_KEY`)** — responses carry `anthropic-ratelimit-requests-*` /
  `anthropic-ratelimit-tokens-*` headers, but these describe **short-window API-key rate limits**, not the
  5h/weekly subscription windows (OQ-2), and the CLI abstracts the HTTP response away from the runner.
  → **not_exposed** for the targeted semantics.
- **Codex / OpenAI (`CODEX_AUTH_JSON` / `OPENAI_API_KEY`)** — Codex headless mode exposes no documented stable
  5h/weekly window surface; the OpenAI API `x-ratelimit-*` headers are again short-window rate limits, not the
  5h/weekly subscription windows, and are not surfaced through the CLI invocation path.
  → **not_exposed** for the targeted semantics.

**Decision (per-provider capture strategy):** Both providers ship the **"not_exposed"** branch — the explicitly
supported deliverable per AC1 / sprint-change-proposal-2026-06-21 D5. The runner emits an explicit
`providerUsage: { signalState: "not_exposed", accountLabel, asOf }` marker (NEVER a fabricated number). The
capture path in `runner.mjs` is written defensively so a future confirmed signal can populate the `available`
shape without a contract change (it is an additive optional field, no `schemaVersion` bump). Per Task-2 guidance
the **offline mock emits the `available` shape**, so the full capture → persist → REST → CLI/UI surface chain is
exercised on the happy path offline + in CI, even though real providers report `not_exposed`.

Pointer left for 3d-10's `per-step-execution-control-walkthrough.md` provider-limit section: document the
"provider-reported, as-of timestamp, or 'not exposed by provider'" semantics; the value is never fabricated.

#### Implementation summary (Tasks 1–9 + logging)

- **Contract (Task 1):** `providerUsage` is an OPTIONAL additive `$ref` on `normalizedOutput.properties` (no
  `schemaVersion` bump, `normalizedOutput.required` unchanged) backed by new `$defs/providerUsage` +
  `$defs/usageWindow` (both `additionalProperties:false`, no token field). Valid fixture added; runner-contracts
  jar reinstalled so backend validates against the new schema.
- **Runner (Task 2):** both `runner.mjs` `commandBuild()` emit `providerUsage` (real path → `not_exposed`,
  offline mock file → `available`); `accountLabel` from the non-secret `--auth-var` name (Trap T1); entrypoints
  pass `--auth-var "$AUTH_KEY_VAR"`; mock signal baked ONLY into the `INSTALL_*_CLI=false` Dockerfile branch via
  `DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE`. 8 Node tests (4 per runner) green.
- **Backend persistence (Task 3):** `V24__add_provider_usage_snapshots.sql` (no secret column; `pul_` prefix;
  partial latest-read index); entity/repo/adapter/write+read ports; `PublicIdPrefixes.PROVIDER_USAGE_SNAPSHOT`;
  `FlywaySchemaContractTest` widened. `RunnerBroker.captureProviderUsage` reads `normalizedOutput.providerUsage`
  (absent → no-op byte-identical AC7; best-effort, never unwinds a run) via optional setter injection (zero
  ctor fan-out). Persistence IT (5 tests, Testcontainers) green.
- **Redaction (Task 4):** `provider-usage-snapshot.json` fixture wired through BOTH gates (manifest +
  `RedactionPolicyServiceContractTest` corpus set); a planted bearer is scrubbed to
  `[REDACTED_AUTHORIZATION_HEADER]`. Per-credential `account_reference` no-secret sweep in the IT.
- **Allowed-action + REST (Task 5):** `VIEW_PROVIDER_USAGE_STATUS` added to the enum + matrix (EXECUTING /
  WAITING_FOR_REVIEW / FAILED / PAUSED, role-agnostic) + frontend placeholder. New `GET …/provider-usage` with
  server-side gating (403 backstop, Trap T5) — controller test (4) + matrix test (50) green. OpenAPI snapshot +
  `schema.d.ts` regenerated and in sync (`check:api`).
- **CLI (Task 6):** `workflow provider-usage <runId>` (text + json, color-independent markers) gated on the same
  matrix path, optional-setter injected.
- **Frontend (Task 7):** `useProviderUsageStatus` (one-shot React-Query, NOT SSE), `ProviderLimitStatus`
  (color-independent `StateSignifierChip`, provider-reported + as-of, `not_exposed` degradation,
  `useLiveAnnouncement`), slotted + gated on `view_provider_usage_status` in the run-detail route. Vitest (5,
  incl. axe) green; tsc + eslint(`--max-warnings=0`) + prettier + a11y-vocabulary clean.
- **Observability (Task 8):** `ProviderLimitMetricsBinder` (weak-ref-safe volatile snapshot, 1s cache,
  serve-stale-on-error) gauges available/not_exposed counts via `countActiveBySignalState`.
- **Logging:** SLF4J parameterized logs at broker capture/persist (INFO/WARN), endpoint open+gating (INFO/WARN),
  CLI read, runner.mjs best-effort capture — only the non-secret account label + counts, NEVER a token.

**Spike decision recorded:** both providers ship `not_exposed`; mock exercises `available`. No new
`WorkflowEventType` / `DomainErrorCode` (states are data; gating reuses allowed-action denial / 403).

#### Review follow-up close-out (2026-06-24, dev-story resume)

The last actionable review patch — the missing capture-path logging coverage (previously PARTIAL) — is
resolved with two `RunnerBrokerUnitTest` cases: a named-non-secret-field INFO assertion plus an adversarial
no-secret-in-logs sweep (a secret-shaped token planted in the adjacent free `summary` field never reaches any
broker log line), and a CRLF-injection-safe capture-failure WARN that still completes the run. Backend
`RunnerBrokerUnitTest` (63) green; spotless + checkstyle clean. The three remaining Review Findings (#326
OpenAPI/TS nullable degradation, #331 403 denial body/convention, #332 `asOf`-null on the available path) stay
**deferred** by decision (their recorded rationale stands) and are tracked above for a follow-up; story moved to
**review**.

#### Verification (commands run, all green)

- Node: `node --test runners/{claude,codex}/test/runner-provider-usage.test.mjs` → 4+4 pass.
- runner-contracts: `RunnerContractValidatorTest` (schema + new fixture validate).
- Backend unit/contract: `RedactionPolicyServiceContractTest`, `RegistryContractTest`,
  `FlywaySchemaContractTest` (23), `WorkflowInspectionServiceAllowedActionsTest` (50),
  `RunnerBrokerUnitTest` (55), `RunnerLogStreamControllerTest` (3), `RunnerDiagnosticConsoleControllerTest` (3),
  `ProviderUsageStatusControllerTest` (4).
- Backend IT (Testcontainers): `ProviderUsageSnapshotPersistenceAdapterIT` (5).
- Frontend: `ProviderLimitStatus.test.tsx` (5), `tsc -b`, `eslint --max-warnings=0`, `check:api`,
  `prettier --check`, `announcement-vocabulary` (4).
- OpenAPI regen via `scripts/regen-openapi.sh` (openapi.json + schema.d.ts committed-in-sync).

### File List

**Runner / contracts**
- `deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json` (M)
- `deliveryline-runner-contracts/src/test/resources/fixtures/valid/runner-result.v1.spec.provider-usage.valid.json` (A)
- `runners/claude/lib/runner.mjs`, `runners/codex/lib/runner.mjs` (M)
- `runners/claude/entrypoint.sh`, `runners/codex/entrypoint.sh` (M)
- `runners/claude/Dockerfile`, `runners/codex/Dockerfile` (M)
- `runners/claude/test/runner-provider-usage.test.mjs`, `runners/codex/test/runner-provider-usage.test.mjs` (A)

**Backend (main)**
- `deliveryline-backend/src/main/resources/db/migration/V24__add_provider_usage_snapshots.sql` (A)
- `deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java` (M)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` (M)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (M)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` (M)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/ProviderUsageSnapshotView.java` (A)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/ProviderUsageStatusService.java` (A)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/ProviderUsageSnapshotWritePort.java` (A)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/ProviderUsageSnapshotReadPort.java` (A)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/ProviderUsageSnapshotEntity.java` (A)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/ProviderUsageSnapshotRepository.java` (A)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ProviderUsageSnapshotPersistenceAdapter.java` (A)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProviderUsageStatusController.java` (A)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProviderUsageStatusResponse.java` (A)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` (M)
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/ProviderLimitMetricsBinder.java` (A)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (M, regenerated)

**Backend (test)**
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` (M)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java` (M)
- `deliveryline-backend/src/test/java/org/dradgo/application/security/RedactionPolicyServiceContractTest.java` (M)
- `deliveryline-backend/src/test/resources/redaction-fixtures/fixtures-manifest.json` (M)
- `deliveryline-backend/src/test/resources/redaction-fixtures/provider-usage-snapshot.json` (A)
- `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json` (M)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/ProviderUsageSnapshotPersistenceAdapterIT.java` (A)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProviderUsageStatusControllerTest.java` (A)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java` (M — provider-usage capture + logging/no-secret-sweep tests)

**Frontend**
- `deliveryline-frontend/src/lib/api/schema.d.ts` (M, regenerated)
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts` (M)
- `deliveryline-frontend/src/lib/a11y/announcements.ts` (M)
- `deliveryline-frontend/src/features/workflows/hooks/useProviderUsageStatus.ts` (A)
- `deliveryline-frontend/src/features/workflows/components/ProviderLimitStatus.tsx` (A)
- `deliveryline-frontend/src/features/workflows/components/ProviderLimitStatus.test.tsx` (A)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (M)

**Story / sprint**
- `_bmad-output/implementation-artifacts/3d-7-post-execution-provider-usage-limit-status.md` (M)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (M)

### Review Findings

_Adversarial code review 2026-06-24 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 1 decision-needed, 11 patch, 1 deferred, 4 dismissed as noise._

#### Patch

- [x] [Review][Patch] (APPLIED 2026-06-24) (resolved from decision) Move provider-usage capture AFTER the post-execution secret-scan/quarantine — `captureProviderUsage` currently runs in `RunnerBroker.handleSuccess` ahead of the `runnerSecretScanService.scanWorkspace` block in its own committed tx, so a run later flagged `RUNNER_SECRET_LEAK` still leaves a queryable `provider_usage_snapshots` row. Reorder so a quarantined execution never persists a snapshot. **[Medium]** [deliveryline-backend/.../application/runner/RunnerBroker.java]
- [x] [Review][Patch] (APPLIED 2026-06-24) No RunnerBroker test for `captureProviderUsage` — the core AC7 backend seam (reads `normalizedOutput.providerUsage`; writes snapshot; no-op + byte-identical on absence; persists `not_exposed`; swallows write failure) has zero direct assertions; Completion-Notes "RunnerBrokerUnitTest green" only proves the pre-existing suite. **[High]** [deliveryline-backend/.../application/runner/RunnerBroker.java:1530]
- [ ] [Review][Patch] (DEFERRED 2026-06-24 — needs springdoc `@Schema` change + full `generate-api`/openapi-snapshot regen cascade; runtime-safe today) OpenAPI/TS nullable degradation — `fiveHour`/`weekly` (`$ref` siblings) and the `actorRole` enum query param emit a bare `"type":"null"` instead of the file's correct `["string","null"]` / nullable-object convention; generated `schema.d.ts` drops `| null`, so a strict consumer dereferencing `status.fiveHour.used` type-checks yet NPEs. **[Medium]** [openapi.json:797,817,2727 · schema.d.ts]
- [x] [Review][Patch] (APPLIED 2026-06-24) `providerUsageInt` mis-coerces integers — `node.isInt() || node.isLong() ? node.intValue() : null` drops a double-encoded integer (`62.0` → null) and truncates a `long`-range value to a wrong int32; compare the correct `providerUsageDouble` using `isNumber()`. **[Medium]** [RunnerBroker.java:1574]
- [x] [Review][Patch] (APPLIED 2026-06-24) `usedFraction` not range/finite-validated on the backend persist path — broker reads any `isNumber()` value (no `[0,1]` clamp, asymmetric with the runner's `sanitizeUsageWindow`); `>1`/negative render as e.g. `500%`, and `NaN`/`Infinity` make `BigDecimal.valueOf` throw → snapshot silently dropped with only a WARN. **[Medium]** [RunnerBroker.java:1569 · ProviderUsageSnapshotPersistenceAdapter.java:1522]
- [x] [Review][Patch] (APPLIED 2026-06-24) No server-side validation of `signalState` — broker accepts any string; an invalid value reaches the DB CHECK and the `DataIntegrityViolationException` is swallowed to a WARN (snapshot lost, no typed handling); `not_exposed` + populated windows persists a contradictory row; the free-string view lets FE silently mislabel unknown future states as "Not exposed". **[Medium]** [RunnerBroker.java:1530 · ProviderUsageSnapshotPersistenceAdapter.java:1530]
- [x] [Review][Patch] (APPLIED 2026-06-24 — WARN uses `MdcKeys.sanitizeForLog`; the missing tests are now added) Logging tests claimed but absent — no "expected log line/level" test and no adversarial no-secret-in-logs sweep (Task 9 subtask checked `[x]`); the capture-failure WARN logs raw `error.toString()` (a DataAccess exception can embed SQL/params) instead of `MdcKeys.sanitizeForLog`. **[Medium]** [RunnerBroker.java:1307 region] → Added `RunnerBrokerUnitTest#onResultProviderUsagePersistLogsNamedNonSecretFieldsOnly` (asserts the INFO persist line carries the non-secret named fields — `snapshotId=pul_`/`signalState`/`accountReference` — and an adversarial sweep that a secret-shaped token planted in the adjacent free `summary` field never appears in ANY broker log line) and `#onResultProviderUsageWriteFailureLogsSanitizedWarnAndCompletesRun` (best-effort capture-failure WARN neutralizes a CRLF-injecting cause via `sanitizeForLog` and the run still completes). Both green; spotless + checkstyle clean.
- [ ] [Review][Patch] (DEFERRED 2026-06-24 — denial-shape change needs a convention decision vs sibling gated controllers) 403 denial returns an empty body, but OpenAPI documents the `403` content as `application/json` → `ProviderUsageStatus`, and it diverges from the `application/problem+json` denial convention used elsewhere. **[Medium]** [ProviderUsageStatusController.java · openapi.json]
- [ ] [Review][Patch] (DEFERRED 2026-06-24 — persist-without-timestamp vs reject is a product call; only reachable from a contract-violating runner) `asOf` is `required` in the runner schema but the broker persists it as null on the `available` path (read via tolerant `providerUsageInstant`, no validation) — a provider-reported value with no provenance timestamp, which the story forbids; only reachable from a contract-violating runner. **[Low]** [RunnerBroker.java:1530]
- [x] [Review][Patch] (APPLIED 2026-06-24) Partial-window rendering discards present values — when `used` is set but `limit`/`usedFraction` are null (or only `resetsAt` is set), CLI and FE print `"partial"` and drop the actual `used` count. **[Low]** [WorkflowCommands.java:166 · ProviderLimitStatus.tsx]
- [x] [Review][Patch] (APPLIED 2026-06-24) `available` signal with both windows empty/dropped renders a contradictory surface — chip says "Provider-reported, as of…" while both window rows show "Not exposed"; reachable when `sanitizeUsageWindow` drops both windows. **[Low]** [ProviderLimitStatus.tsx · WorkflowCommands.java]
- [x] [Review][Patch] (APPLIED 2026-06-24) `ProviderUsageSnapshotView.capturedAt` javadoc says "always present" but it is typed nullable across REST/OpenAPI, and the CLI text branch omits it entirely. **[Low]** [ProviderUsageSnapshotView.java]

#### Deferred

- [x] [Review][Defer] Snapshots accumulate per run with no dedupe on duplicate `onResult` re-entry (recovery scratch-replay / concurrent harvest); the observability gauge `countByArchivedAtIsNullAndSignalState` counts all non-archived rows globally → over-counts and grows unbounded. Read side uses `findFirst…OrderByCreatedAtDesc` so the surfaced value is correct. — deferred, row growth/purge is owned by Epic 5; gauge semantics non-blocking. [ProviderUsageSnapshotRepository.java · ProviderLimitMetricsBinder.java]

### Review Findings — Re-review 2026-06-24 (3-layer adversarial: Blind Hunter + Edge Case Hunter + Acceptance Auditor)

_Independent re-review on the working-tree diff (scoped to the story File List, 43 files). AC coverage MET for AC1–AC4, AC6, AC7 and all nine declared traps + every scope guard. No NEW High/Medium actionable patch surfaced. The 3 carried-forward contract findings (#326/#331/#332) were independently reconfirmed as still present (2–3 layers each); they are re-opened below for an explicit keep-deferred/act decision. 2 decision-needed, 1 patch, 4 deferred, 5 dismissed as noise._

#### Patch (RESOLVED 2026-06-24)

- [x] [Review][Patch] (APPLIED 2026-06-24, carried-forward #331; decision → document no-content 403) 403 denial returned an **empty** body while OpenAPI documented the `403` as `application/json → ProviderUsageStatus`. Resolution: the codebase has **no 403 `DomainErrorCode` / `problem+json` denial convention** (the SSE siblings signal in-stream) and the story scope-guards a new error code, so rather than introduce the first 403 code we made the contract honest — added an explicit empty `@Content` to the 403 `@ApiResponse` so OpenAPI documents a **no-content 403** (controller stays status-only). Regenerated `openapi.json` + `schema.d.ts`; `OpenApiSnapshotContractTest` + `check:api` + `tsc` green. [ProviderUsageStatusController.java · openapi.json · schema.d.ts]
- [x] [Review][Patch] (APPLIED 2026-06-24, carried-forward #332; decision → reject/skip-persist) `asOf` is `required` in the runner schema, but JSON-schema `format: date-time` is annotation-only (not asserted), so a **present-but-unparseable** `asOf` slips past `RunnerContractValidator` and yields a null instant in `captureProviderUsage`. Resolution: skip-persist an `available` snapshot whose resolved `asOf` is null (a provider-reported reading with no provenance) with a WARN; `not_exposed` still tolerates a null `asOf` (documented absence). Added 2 `RunnerBrokerUnitTest` cases (available+unparseable-asOf → no insert + run completes; not_exposed+unparseable-asOf → still persists, asOf null). 65 broker tests green; spotless + checkstyle clean. [RunnerBroker.java#captureProviderUsage]

#### Deferred (re-review)

- [x] [Review][Defer] (carried-forward #326) OpenAPI/TS nullable degradation — `fiveHour`/`weekly` (`$ref` siblings) emit a bare `"type":"null"` instead of the `anyOf:[{$ref},{type:null}]` convention, so generated `schema.d.ts` drops `| null` (a strict future consumer dereferencing `status.fiveHour.used` type-checks yet NPEs on the `not_exposed` path). **Investigated 2026-06-24:** root cause is a **swagger-core serializer quirk** — `@Schema(nullable=true)` on a `$ref`-typed property renders the contradictory sibling form, and there is **no annotation attribute** to inject a `null` branch into a `$ref`; a regen-proof fix needs a swagger-core version bump or a springdoc `PropertyCustomizer`/`OpenApiCustomizer` bean (new global infra affecting all nullable refs across the API). The `actorRole` `"type":"null"` half is a **shared pre-existing** quirk across all 4 gated endpoints (RunnerLogStream/DiagnosticConsole/provider-usage/+1) and openapi-typescript still generates a correct union for it (no consumer impact) — out of scope. Runtime-safe today (live `ProviderLimitStatus.tsx` guards `window != null`). — re-deferred; revisit as a project-wide springdoc nullable-`$ref` customizer, not a per-field patch. [openapi.json:795-797,815-817 · schema.d.ts · ProviderUsageStatusResponse.java]

#### Deferred

- [x] [Review][Defer] Duplicate `onResult` (recovery scratch-replay / concurrent harvest) inserts a **second** snapshot row — `captureProviderUsage` runs unconditionally before the `recordCompleted` ILLEGAL_TRANSITION transition-guard and mints a fresh `pul_` id each entry, so the unique-public-id guard does not dedupe. Read surface stays correct (`findFirst…OrderByCreatedAtDesc`); gauge over-counts. — deferred, same root as the prior duplicate-row defer; row growth/purge owned by Epic 5. [RunnerBroker.java#handleSuccess]
- [x] [Review][Defer] `providerUsageInstant` silently drops a schema-valid-but-offsetless ISO timestamp (`OffsetDateTime.parse` → `DateTimeParseException` swallowed to null, no WARN), losing `resetsAt`/`asOf` provenance. In-house runners always emit `…Z` so practical exposure is nil; broker claims tolerance of "any runner." — deferred, low; in-house runners are offset-bearing. [RunnerBroker.java#providerUsageInstant]
- [x] [Review][Defer] `signalState: available` with both windows absent/empty (schema-valid — only `signalState`/`accountLabel`/`asOf` required) persists a contentless `available` row, indistinguishable in content from `not_exposed`; the contradiction guard covers only the `not_exposed`+numbers inverse. — deferred, low; UI renders the empty state safely. [RunnerBroker.java#captureProviderUsage · ProviderLimitStatus.tsx]
- [x] [Review][Defer] `account_reference` column is `text not null` with no length CHECK at the DB boundary; the 128-char cap lives only in the runner-contract schema, so a non-`onResult` writer is unbounded (defense-in-depth gap on a "non-secret by construction" table). — deferred, low; the only production writer is gated upstream. [V24__add_provider_usage_snapshots.sql]

#### Dismissed (noise / by-design / out-of-scope, not written as action items)

- Offline mock file with a non-`available` `signalState` falls through to `not_exposed` without a warning — by-design test infra (mock only activates the happy path).
- `signalSignifier` treats `status === undefined` as "loading" while the body uses `isPending`; can diverge on background refetch — cosmetic, no functional impact.
- `Math.round(usedFraction*100)` shows `0%`/`100%` for tiny/near-full fractions — standard rounding, cosmetic.
- `resetsAt`/`asOf` rendered as raw ISO-8601 with no relative/locale formatting — meets AC5 "as-of a timestamp"; humanization is out-of-scope polish.
- Broker numeric-hardening helpers (`providerUsageInt`/`providerUsageDouble`) are unreachable via the schema-validated `onResult` path — harmless defense-in-depth; AC7 coverage note only.
