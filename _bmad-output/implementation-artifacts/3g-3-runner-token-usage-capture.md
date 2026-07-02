# Story 3g.3: Runner Token-Usage Capture

Status: done

<!-- 2026-07-02 bmad-create-story context-engine pass (Opus 4.8 [1m]). Target sprint key: 3g-3-runner-token-usage-capture. Epic 3g already in-progress (3g-1 created it). Source: epic-03g-provenance-token-accounting.md + sprint-change-proposal-2026-06-29-epics-3g-3l.md. Delivers FR74 BACKEND (per-step token accounting — capture + persistence). 3g-4 (FE) consumes the persisted per-step token fields plus the 3g-4 run-level rollup. This is the TOKEN track — independent of and shares no schema with the provenance track (3g-1/3g-2). -->

> **READ FIRST — this is a pure additive CAPTURE + PERSISTENCE story.** No new `WorkflowState`, `AllowedAction`, `WorkflowEventType`, or `DomainErrorCode`. No REST DTO change, **no OpenAPI/`schema.d.ts` regen, no FE work** — the per-step display and the run-level rollup are **3g-4**. Your job is to add an additive-optional `usage{inputTokens,outputTokens,totalTokens}` block to the runner-result contract, emit it best-effort from both `runner.mjs` entrypoints (deterministic in the offline mocks), add three nullable columns to `runner_executions` (a Flyway migration), append the three fields to `RunnerExecutionSnapshot` behind a ctor shim, and populate them on result ingest.
>
> **CRITICAL PREMISE (verify before coding):** token accounting is **genuinely net-new data**. `RunnerExecutionSnapshot` carries **no** token fields today; the runner-result contract carries no `usage` block; and the agent CLIs are invoked in plain-text (`-p`) mode — their stdout is captured raw and only the first non-empty line becomes `summary`; **no token/usage parsing happens anywhere today** (grep `usage`/`tokens`/`inputTokens` across `runners/` returns only the *rate-limit* `providerUsage`/`usageWindow` code from 3d-7 and the `CLAUDE_MAX_TOKENS` export). This is **not** the same thing as 3d-7's `providerUsage`: that is rolling *subscription quota* window status persisted to a **separate** `provider_usage_snapshots` table; **this** is **per-execution token counts** persisted as **columns on `runner_executions`**. Both are best-effort/nullable, and you will **model the emit + ingest plumbing on `providerUsage`** — but the persistence target and the DTO shape are different. Do not conflate them.

## Story

As the system,
I want each runner execution to record the agent's input/output/total token counts when the agent reports them,
so that per-step token consumption is governed data — best-effort and nullable where the agent does not report it.

## Acceptance Criteria

1. **Given** the runner result contract (`runner-contracts` → `runner-result.v1.schema.json`), **Then** an **additive optional** `usage{inputTokens,outputTokens,totalTokens}` object is added under `normalizedOutput` (sibling of the existing `providerUsage`), each value a **non-negative integer**; it is **NOT** in `required` and there is **NO `schemaVersion` bump** (the 3d-7 `providerUsage` / 3e-1 `questions` additive-optional precedent). Because `normalizedOutput` is `additionalProperties:false`, the property **must** be declared to be accepted. Both `runner.mjs` entrypoints emit `usage` **only when token counts are available** and **omit the key entirely** (never `usage:{}`, never `null`) when not — so a no-usage result stays **byte-identical** to pre-3g output. Emission is **best-effort: it must never throw and never fail the run**. Both offline mocks emit **deterministic** token counts. (Heed the `runner-contracts` stale-in-`.m2` trap — install the contracts module or build with `-am` before backend-only tests, or `mvnw test` uses the OLD jar and your schema change is invisible.)
2. **Given** the next-free Flyway head (**V31** — see the contention note below), **Then** a migration adds **nullable** `input_tokens` / `output_tokens` / `total_tokens` columns to `runner_executions` (`integer`, no `NOT NULL`, no default, no CHECK). Replay-safe and checksum-stable (`flywayMigrateIsReplaySafeAndChecksumStable`); pinned by a new per-column assertion in `FlywaySchemaContractTest`. Existing rows stay `NULL` (parity — no token data pre-3g).
3. **Given** `RunnerExecutionSnapshot`, **Then** three nullable `Integer inputTokens, Integer outputTokens, Integer totalTokens` fields are **appended at the END** of the canonical constructor, and a **new ctor shim carrying today's 24-arg signature** is added (delegating with three `null`s) so **every** existing `new RunnerExecutionSnapshot(...)` call site (≈36 across ≈24 test files + the one main mapper) stays green — the snapshot ctor-shim fan-out pattern. The read-path mapper (`RunnerExecutionEntityMapper`) passes the three new entity columns through; the entity gains three nullable `Integer` `@Column` fields (mirroring `redactionCount`).
4. **Given** a result with **no** `usage` (agent did not report, a command-only/no-LLM execution, or a missing/malformed `usage` block), **Then** the three columns and snapshot fields persist as `null` — capture is **best-effort: logged and skipped, never a 5xx or a strand**. A malformed `usage` (non-object, negative, non-integer, partial) is sanitized field-by-field (each bad field dropped to `null`), never rejected wholesale, and never unwinds a successfully-executed run. Each of the three counts is persisted **independently as reported** — do **not** synthesize `totalTokens` from `input+output` (persist only what the agent reported; if only `total` is reported, `input`/`output` stay `null`). (Forward note: command-only BUILD/LINT executions in 3h emit no token usage and rely on this null posture.)
5. **Given** redaction (story 1.10), **Then** token counts are numeric governed data carrying nothing secret; the `usage` block adds **no new sensitive surface** and raw-output capture (3.6) retains its existing redaction posture. Never log a raw agent payload while capturing usage; log **counts and ids only** (a token *count* is not secret, but never log the agent's raw JSON output). The sanitizer copies **only** the three allowed integer keys so a hostile/garbage mock or agent payload can never smuggle a secret-shaped string into the emitted/persisted `usage`.
6. **Given** tests, **Then** coverage asserts: contract **round-trip present / absent / malformed-non-fatal** across **both** runner entrypoints; the three columns persist and `RunnerExecutionSnapshot` **carries** the three fields end-to-end on ingest; **null parity** for no-usage results; **mock determinism** (both offline mocks emit fixed counts); `FlywaySchemaContractTest` drift (three new nullable integer columns) + replay-safety; `application.*` JaCoCo ≥80%.

## Tasks / Subtasks

- [x] **Task 1 — Add the additive-optional `usage` block to the runner-result contract** (AC: 1)
  - [x] In `deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json`, add a `usage` property under `normalizedOutput.properties` (sibling of `providerUsage`, ~schema line 65-67) `{ "$ref": "#/$defs/usage" }`. Add a `$defs/usage` block (near the existing `$defs/providerUsage`/`usageWindow`, ~line 141-165): `type:object`, `additionalProperties:false`, properties `inputTokens`/`outputTokens`/`totalTokens` each `{ "type":"integer", "minimum":0 }`, **NO `required`** (each field independently optional). Add a `$comment` documenting it as OPTIONAL additive per-execution token accounting (FR74), NOT in `required`, NO schemaVersion bump.
  - [x] Do **NOT** touch the top-level `required` list (still 8 keys) and do **NOT** bump `schemaVersion` (`const: 1` stays).
  - [x] Add a passing fixture under `deliveryline-runner-contracts/src/test/resources/fixtures/valid/` mirroring `runner-result.v1.spec.provider-usage.valid.json` — e.g. a `...usage.valid.json` with `normalizedOutput.usage`. Confirm the pre-existing no-`usage` fixtures still validate (parity). If the contracts module has a schema-validation test that enumerates fixtures, ensure the new fixture is picked up.
  - [x] **Install the contracts module before backend tests** (`mvnw -pl deliveryline-runner-contracts install` or build the backend with `-am`) — the `runner-contracts` jar is resolved from `.m2`; a backend-only `mvnw test` would validate against the STALE old schema and your change would be silently invisible.

- [x] **Task 2 — Emit `usage` best-effort from both `runner.mjs` entrypoints** (AC: 1, 5)
  - [x] In **both** `runners/claude/lib/runner.mjs` and `runners/codex/lib/runner.mjs` (byte-identical result-building shape — keep them identical): add a `sanitizeUsage(raw)` helper modeled EXACTLY on `sanitizeUsageWindow` (claude `:267-277`) — copy **only** `inputTokens`/`outputTokens`/`totalTokens` where `Number.isInteger(v) && v >= 0`; return `undefined` when nothing usable remains. Add a `buildUsage()` helper modeled on `buildProviderUsage` (claude `:285-305`): best-effort, never throws (try/catch → `undefined`), reads a deterministic source (see below), returns a sanitized `{inputTokens?,outputTokens?,totalTokens?}` or `undefined`.
  - [x] Wire it into the `commandBuild` result assembly (claude `:700-709` / codex `:707-716`): compute `const usage = buildUsage();` alongside `const providerUsage = buildProviderUsage(...)`, then include it **only when present**: build `normalizedOutput = { summary, outcome: 'success', providerUsage }` and `if (usage) normalizedOutput.usage = usage;`. This conditional-include keeps a no-usage result byte-identical (mirrors how `fiveHour`/`weekly` are conditionally attached, claude `:296-297`).
  - [x] **Source of the counts (locked scope — mirror the 3d-7 `providerUsage` spike posture):** the current text-mode (`-p`) headless invocation does **not** surface token usage on agent stdout (verified — no parsing exists). So the **real path** reads from an **env-injected mock file** `DELIVERYLINE_USAGE_MOCK_FILE` (the exact `DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE` seam, claude `:288`); when the env var is unset/unreadable/malformed, `buildUsage()` returns `undefined` → `usage` omitted (the honest "not reported" state 3g-4 renders). **Do NOT switch the CLI to JSON output mode / re-plumb the stdout→summary→checksum pipeline** — real-CLI token extraction is a documented forward option (see Dev Notes / Open Question), out of scope here.
  - [x] Logging: `DEBUG`/`INFO` "built usage present={}" — never the raw agent payload. (Counts themselves are non-secret, but do not dump raw stdout.)

- [x] **Task 3 — Deterministic token counts in both offline mocks** (AC: 1, 6)
  - [x] The offline CLI mocks (`runners/claude/test/mock-claude.sh`, `runners/codex/test/mock-codex.sh`) emit agent **stdout**, not the result JSON — with the `DELIVERYLINE_USAGE_MOCK_FILE` seam they do **not** need to change. Instead, the deterministic counts come from the **mock file**: wire the offline/CI runner path (or the runner unit tests) to point `DELIVERYLINE_USAGE_MOCK_FILE` at a fixed JSON with deterministic counts (e.g. `{"inputTokens":1200,"outputTokens":800,"totalTokens":2000}`), exactly as `runner-provider-usage.test.mjs` injects `DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE`. **No randomness, no wall-clock.**
  - [x] Verify the two mock scripts still emit only their existing deterministic fences (they read only non-secret env vars — do not add secret-shaped env reads). If a maintainer expects the counts to originate from the mock script rather than an env file, that is the alternative wiring — but prefer the env-mock-file seam for symmetry with 3d-7.

- [x] **Task 4 — Flyway migration: three nullable token columns on `runner_executions`** (AC: 2)
  - [x] **FIRST clear the stale artifact:** a `V31__narrow_pending_clarification_index_to_exclude_superseded.sql` exists ONLY in `deliveryline-backend/target/classes/db/migration/` with **no source counterpart** (source stops at `V30`). Delete/clean `target/classes/db/migration` (or `mvnw clean`) before adding your migration, or the classpath carries two different `V31` scripts and `flywayMigrateIsReplaySafeAndChecksumStable` reds with a checksum-drift. (3g-1 explicitly warned "do not claim V31" — it needed no migration; **you do**, and V31 is the correct next-free head once the stale artifact is gone.)
  - [x] Add `deliveryline-backend/src/main/resources/db/migration/V31__add_runner_execution_token_columns.sql`: `ALTER TABLE runner_executions ADD COLUMN input_tokens integer, ADD COLUMN output_tokens integer, ADD COLUMN total_tokens integer;` — all **nullable**, no default, **no CHECK constraint** (a CHECK would trip the swallowed-`DataIntegrityViolation` failure mode the broker's best-effort capture must avoid; keep validation in the JS sanitizer + Java capture). No index (three plain scalar columns).
  - [x] Confirm no identifier exceeds the Postgres 63-char limit (guarded by `everyConstraintAndIndexNameFitsPostgresIdentifierLimit` — column names are short, fine).

- [x] **Task 5 — Entity + snapshot: append three nullable Integer fields (ctor-shim fan-out)** (AC: 3)
  - [x] `adapters/persistence/entity/RunnerExecutionEntity.java`: add `@Column(name="input_tokens") Integer inputTokens` + `output_tokens` + `total_tokens` (nullable — no `nullable=false`), mirroring the `redactionCount` `Integer` field (`:110-111` / getters `:311-317`). Add getters/setters.
  - [x] `application/runner/spi/RunnerExecutionSnapshot.java`: append `Integer inputTokens, Integer outputTokens, Integer totalTokens` to the **END** of the canonical 24-arg ctor (after `actorType`, `:44`), making it 27-arg. Extend the pre-3.17a shim's `this(...)` tail (`:118-160`) with three trailing `null`s. **Add a NEW secondary ctor** whose signature is today's exact 24-arg list, delegating `this(<24 args>, null, null, null)` — so all existing `new RunnerExecutionSnapshot(...)` call sites (the ≈36 test sites + the mapper) compile unchanged. This is the snapshot ctor-shim fan-out pattern (same as the pre-3.2 / pre-3.6 / pre-3.17a shims already present).
  - [x] `adapters/persistence/mapper/RunnerExecutionEntityMapper.java:18` — the **single main-code** construction site: pass `entity.getInputTokens(), entity.getOutputTokens(), entity.getTotalTokens()` at the end of the `new RunnerExecutionSnapshot(...)` call (use the 27-arg canonical ctor here, NOT the shim).
  - [x] SpotBugs: `Integer` is immutable — no `EI_EXPOSE` concern. Run `spotless:apply`.

- [x] **Task 6 — Ingest: read `usage`, persist via a metadata-only write-port method** (AC: 3, 4, 5)
  - [x] Add a metadata-only write to `application/runner/spi/RunnerExecutionRecordPort.java` modeled on `recordRawOutput` (`:245-250`): `RunnerExecutionSnapshot recordTokenUsage(String publicId, Integer inputTokens, Integer outputTokens, Integer totalTokens)` — **no state-machine guard** (the row is terminal by result-ingest time), tolerates an already-terminal row, throws `DomainException` only when the row is missing. Consider a `default` no-op (returning the row unchanged / not implementable by lean fakes) if adding a mandatory method would fan out to non-persistence fakes — mirror the `pinReviewedArtifact` default pattern if so; otherwise implement across the persistence adapter + any test fakes.
  - [x] Implement in `adapters/persistence/RunnerExecutionPersistenceAdapter.java` mirroring `recordRawOutput` (`:826-859`): `@Transactional(REQUIRES_NEW)` `findByPublicIdForUpdate` → set the three columns → `saveAndFlush` → return the remapped snapshot. (`REQUIRES_NEW` matches the raw-output metadata-write precedent.)
  - [x] In `RunnerBroker.java`, add `captureTokenUsage(String workflowRunId, String runnerExecutionId, JsonNode parsed)` modeled EXACTLY on `captureProviderUsage` (`:1611-1704`): read `JsonNode usage = parsed.path("normalizedOutput").path("usage")`; tolerate missing/non-object (`usage.isMissingNode() || !usage.isObject()` → return, AC4); extract each of the three via a null-tolerant int reader (drop absent/non-int/negative to `null`, like `providerUsageInt`); if all three are `null`, skip the write (nothing to persist); else call the new write port. Wrap in `try/catch (RuntimeException) { log.warn(... best-effort ...) }` so a capture failure NEVER unwinds a successfully-executed run.
  - [x] Invoke `captureTokenUsage(...)` from `handleSuccess` **right next to** the existing `captureProviderUsage(...)` call (`:2079`) — same best-effort positioning. Do **not** thread tokens through `markCompleted`/`recordCompleted` (keep the metadata write separate, as raw-output and provider-usage both are).
  - [x] Logging: `INFO` "onResult token-usage persisted runnerExecutionId={} workflowRunId={} input={} output={} total={}" on success; `WARN` best-effort on skip/failure. Counts are non-secret — safe to log the numbers; never log the raw payload.

- [x] **Task 7 — Tests** (AC: 1-6)
  - [x] **Contract round-trip (both entrypoints):** in `runners/claude/test/` + `runners/codex/test/` (mirror `runner-provider-usage.test.mjs`): (a) with `DELIVERYLINE_USAGE_MOCK_FILE` set → emitted result carries `normalizedOutput.usage` with the deterministic counts and **validates** against the schema; (b) unset → `usage` **absent** (byte-identical parity, no `usage` key); (c) malformed mock file (garbage / negative / non-integer / non-object) → **non-fatal**, `usage` omitted or partially sanitized, run still succeeds.
  - [x] **Contracts module:** the new `...usage.valid.json` fixture validates; a fixture with a **negative**/non-integer token value is rejected by the schema (`minimum:0`/`type:integer`).
  - [x] **Snapshot end-to-end (backend):** a `*IT` (Testcontainers Postgres — name it `*IT`, not `*Test`, or it leaks into Windows Surefire) that ingests a result WITH `usage` → the `runner_executions` row carries the three columns and the round-tripped `RunnerExecutionSnapshot` carries the three fields; a result WITHOUT `usage` → all three `null` (parity); a **malformed** `usage` → non-fatal, run completes, fields `null`. Assert the capture does not change `status` (metadata-only).
  - [x] **Unit:** `captureTokenUsage` best-effort — missing node, non-object, all-null, partial (only `totalTokens`), and a thrown write-port error (swallowed, WARN) — none unwind the success path. `RunnerExecutionEntityMapper` maps the three columns.
  - [x] **Schema drift:** add `runnerExecutionsCarriesTheV31TokenColumns()` to `FlywaySchemaContractTest` (mirror `runnerExecutionsCarriesTheV22ReviewedArtifactPinColumns` `:239-250`): assert each of `input_tokens`/`output_tokens`/`total_tokens` is `integer` and `assertColumnNullable(..., true)`. Confirm `flywayMigrateIsReplaySafeAndChecksumStable` (`:538-568`) still passes (0 executed on replay, checksum stable) after clearing the stale `target/classes` V31.
  - [x] `application.*` JaCoCo ≥80% for the new capture/mapper code (ArchUnit runs in **Failsafe** — verify new `@ArchTest`s, if any, via the failsafe/verify tier, not `mvnw test`).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (result ingest, token-usage persisted), `WARN` for recoverable anomalies (missing/malformed `usage`, swallowed capture failure), `ERROR` only for unhandled failures. `DEBUG` for the JS `buildUsage present={}` line.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `runnerExecutionId`, `idempotencyKey`, `actorIdentity`. Use MDC where the framework supports it; otherwise pass as parameters.
  - [x] Never log secrets, payload bytes, raw tokens, or full PII — here specifically **never the raw agent stdout/payload** while capturing usage. Token *counts* are non-secret numeric data and may be logged.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level (list-appender or `OutputCaptureExtension`) — the "token-usage persisted" INFO on the happy path and the best-effort WARN on the malformed/skip path.

## Dev Notes

### The real shape of this story (read before coding)

There are two independent legs joined by the contract:

1. **Emit (JS, `runner.mjs` ×2 + offline mocks):** a best-effort `usage` block on `normalizedOutput`, present only when counts are available, deterministic in the offline path via an env-injected mock file. **Model it 1:1 on 3d-7's `providerUsage`** (`buildProviderUsage`/`sanitizeUsageWindow`/`DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE` — the same never-throw, copy-only-allowed-keys, conditional-include discipline). The only difference: `usage` is **omitted entirely** when unavailable (there is no `not_exposed` marker — tokens are simply absent, and 3g-4 renders that as "not reported").

2. **Ingest + persist (Java):** read `normalizedOutput.usage` in the broker (mirror `captureProviderUsage`), write via a new metadata-only `recordTokenUsage` port method (mirror `recordRawOutput`), onto **three new nullable columns of `runner_executions`** (V31), carried on `RunnerExecutionSnapshot` (three fields appended behind a ctor shim). **Note the persistence target differs from `providerUsage`:** provider-usage is a *separate table* (`provider_usage_snapshots`) written via `ProviderUsageSnapshotWritePort.insert(NewProviderUsageSnapshot)`; **tokens are columns on the execution row itself** written via a `recordRawOutput`-style update. Use the emit pattern from provider-usage but the persistence pattern from raw-output.

The FE consumption (per-step display + run-level `WorkflowInspectionService` rollup on `WorkflowDetailResponse.totalTokens`) is **entirely 3g-4** — do not build any read model, DTO, OpenAPI regen, or rollup here.

### Source-tree components to touch (with line anchors — verify before editing)

- **Contract (Task 1):**
  - `deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json` — `normalizedOutput.properties` `:53-68` (add `usage` sibling of `providerUsage` `:65-67`); `$defs` `:106-165` (add `usage` block near `providerUsage`/`usageWindow`). Top-level `required` `:7-16` (leave 8 keys), `schemaVersion` `const:1` `:18-20` (do not bump).
  - Precedent fixture: `deliveryline-runner-contracts/src/test/resources/fixtures/valid/runner-result.v1.spec.provider-usage.valid.json` (providerUsage under normalizedOutput).
- **Emit (Task 2-3):**
  - `runners/claude/lib/runner.mjs` — `sanitizeUsageWindow` `:267-277`, `buildProviderUsage` `:285-305`, `commandBuild` result assembly `:700-709` (the `normalizedOutput` literal `:705`), `DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE` read `:288`.
  - `runners/codex/lib/runner.mjs` — byte-identical twin: result assembly `:707-716`, `buildProviderUsage` `:307-327`, `sanitizeUsageWindow` `:289-299`. **Keep both files identical.**
  - Offline mocks: `runners/claude/test/mock-claude.sh`, `runners/codex/test/mock-codex.sh` (emit stdout only; unchanged if using the env-mock-file seam). Existing usage-mock precedent test: `runners/claude/test/runner-provider-usage.test.mjs`, `runners/codex/test/runner-provider-usage.test.mjs`.
- **Migration + schema gate (Task 4, 7):**
  - `deliveryline-backend/src/main/resources/db/migration/` — source head **`V30`**; add **`V31__add_runner_execution_token_columns.sql`**. **STALE ARTIFACT:** `deliveryline-backend/target/classes/db/migration/V31__narrow_pending_clarification_index_to_exclude_superseded.sql` (no source counterpart) — clean it first.
  - `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` — `assertColumnType`/`assertColumnNullable` helpers; per-column precedent `runnerExecutionsCarriesTheV22ReviewedArtifactPinColumns` `:239-250`; replay/checksum gate `flywayMigrateIsReplaySafeAndChecksumStable` `:538-568`; `runner_executions` already in `CORE_TABLES` `:37`.
- **Entity + snapshot (Task 5):**
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/RunnerExecutionEntity.java` — `@Table(name="runner_executions")` `:21-23`; `redactionCount` Integer field precedent `:110-111` (getters `:311-317`); raw-output block `:101-111`.
  - `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionSnapshot.java` — canonical 24-arg ctor `:9-45` (tail `actorType` `:44`); pre-3.2 shim `:48-73`, pre-3.6 shim `:80-110`, pre-3.17a shim `:118-160`.
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/RunnerExecutionEntityMapper.java:18` — the ONLY main-code `new RunnerExecutionSnapshot(...)` site.
- **Ingest (Task 6):**
  - `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` — `onResult` `:1467-1600` (parses `JsonNode parsed` `:1565`, calls `handleSuccess` `:1591`); `captureProviderUsage` `:1611-1704` (reads `parsed.path("normalizedOutput").path("providerUsage")` `:1618`; best-effort try/catch `:1697-1704`); `captureProviderUsage` invoked in `handleSuccess` `:2079`; `recordCompleted` at `:2162`.
  - `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionRecordPort.java` — `recordRawOutput` `:245-250` (metadata-only, no state guard); `pinReviewedArtifact` default-method precedent `:265-269` (use for a no-op default if avoiding fake fan-out).
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerExecutionPersistenceAdapter.java` — `recordRawOutput` impl `:826-859` (`@Transactional(REQUIRES_NEW)` `findByPublicIdForUpdate` → setters → `saveAndFlush`); `markCompleted` `:588-599`.

### Anti-patterns to avoid (disaster prevention)

- **Do NOT** conflate this with `providerUsage`. Same emit pattern, DIFFERENT persistence: tokens are **columns on `runner_executions`** (write via `recordRawOutput`-style update), NOT rows in `provider_usage_snapshots`. Do not create a token snapshot table.
- **Do NOT** bump `schemaVersion` or add `usage` to any `required` list — it is additive-optional (3d-7/3e-1 discipline). Emitting `usage:{}` or `usage:null` when unavailable is WRONG; **omit the key** so no-usage output stays byte-identical.
- **Do NOT** let capture throw. A missing/malformed `usage`, a negative count, a write-port failure — all best-effort, logged, swallowed; a successfully-executed run is NEVER unwound by a token-capture error (mirror `captureProviderUsage`'s try/catch).
- **Do NOT** switch the agent CLI to JSON output mode or re-plumb the stdout→summary→SHA-256-over-raw pipeline to scrape real tokens. Real-CLI extraction is a forward option (Open Question); this story wires the contract + persistence + deterministic-mock coverage, with real counts flowing through the same seam once/if wired.
- **Do NOT** synthesize `totalTokens = input + output`. Persist each count exactly as reported; a missing sub-field stays `null` (avoid fabricating governed data).
- **Do NOT** add a CHECK constraint or `NOT NULL` on the new columns — pre-3g rows and no-usage results must stay `NULL`, and a CHECK would surface as a swallowed `DataIntegrityViolation` in the best-effort capture.
- **Do NOT** "claim V31" without first clearing the stale `target/classes/db/migration/V31...sql` — otherwise the classpath carries two V31 scripts and the replay/checksum gate reds.
- **Do NOT** edit the ≈36 test call sites of `new RunnerExecutionSnapshot(...)` — the whole point of the new 24-arg ctor shim is that they stay untouched. If you find yourself editing them, your shim signature is wrong.
- **Do NOT** name the Testcontainers persistence test `*Test` — it must be `*IT` or it leaks into Windows Surefire and reds CI.

### `runner-contracts` install trap (applies here — unlike 3g-1)

3g-1 changed no contract, so this did not apply there. **It applies to you.** A backend-only `mvnw test` resolves the `runner-contracts` jar from `.m2` and would validate against the OLD schema — your `usage` change would be invisible and tests could pass/fail spuriously. Run `mvnw -pl deliveryline-runner-contracts install` (or build the reactor with `-am`) so the fresh schema is on the classpath before backend tests.

### Testing standards summary

- Backend: JUnit 5 + Mockito + AssertJ (unit); Testcontainers `*IT` for real-Postgres persistence + the schema-drift gate. `application.*` JaCoCo ≥80%. Run `spotless:apply` before pushing Java; ArchUnit runs in **Failsafe** (verify via verify tier, not `mvnw test`).
- Runner JS: node `--test` mjs files under `runners/{claude,codex}/test/` — mirror `runner-provider-usage.test.mjs` for the mock-file-injected happy path + the unset/malformed paths.
- Verify CI-affecting changes in a clean env / WSL2 Linux where Docker-backed ITs matter (local green ≠ CI green). The Flyway replay/checksum gate is Postgres-backed — run it against Testcontainers, not H2.
- No OpenAPI/`schema.d.ts` regen here (no REST DTO change) — the `check:api` / snapshot gates are untouched by this story.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. (JS side uses the runner's existing `log()`/stderr convention — see the entrypoint logging note.)
- **Where to log (minimum surface):**
  - `RunnerBroker.captureTokenUsage` → `INFO` "token-usage persisted … input/output/total counts + ids" on success; `WARN` on missing/malformed/skip and on a swallowed write failure (best-effort).
  - `RunnerExecutionPersistenceAdapter.recordTokenUsage` → `INFO` "persisting token usage runnerExecutionId={}" (metadata write), `ERROR` only on an unmapped `DataIntegrityViolationException`.
  - `runner.mjs buildUsage` → `DEBUG`/`INFO` "built usage present={}" — never the raw payload.
- **Required context keys:** `correlationId`, `workflowRunId`, `runnerExecutionId`, `idempotencyKey`, `actorIdentity`.
- **Forbidden in log output:** raw agent stdout/payload, secrets/tokens, PII. Token **counts** are non-secret numeric governed data and are safe to log.
- **Test contract:** the "token-usage persisted" INFO and the best-effort WARN pinned by a focused list-appender / `OutputCaptureExtension` test.

### Project Structure Notes

- Contract schema + fixtures live in `deliveryline-runner-contracts`. Runner emit logic in `runners/{claude,codex}/lib/runner.mjs` (keep byte-identical). Backend port in `application.runner.spi`; adapters in `adapters.persistence`; broker in `application.runner`. No new module, no new Maven dependency, no new package.
- No new `WorkflowState`/`AllowedAction`/`WorkflowEventType`/`DomainErrorCode` (the lightest foundation-gate footprint of the 3g–3l family — the only drift-tested additions are the three `runner_executions` columns and the `usage` contract field).

### References

- [Source: _bmad-output/planning-artifacts/epic-03g-provenance-token-accounting.md#Story 3g-3: Runner Token-Usage Capture]
- [Source: _bmad-output/planning-artifacts/epic-03g-provenance-token-accounting.md#Cross-Cutting Notes] (token posture locked: per-step + run-level rollup, tokens-only, best-effort nullable, `null`→"not reported" never `0`; `runner-contracts` install trap; snapshot ctor-shim fan-out; light foundation-gate footprint)
- [Source: _bmad-output/planning-artifacts/prd.md#FR74] (per-step agent token accounting + run-level rollup)
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-29-epics-3g-3l.md] (Epic 3g section)
- [Source: 3g-1-ticket-origin-snapshot-and-read-model.md] (sibling additive-convention story; the V31 stale-`target/classes` warning; additive-DTO discipline)
- Precedent stories: 3d-7 (`providerUsage` additive-optional runner-contract field + `buildProviderUsage`/mock-file seam + `captureProviderUsage` best-effort ingest); 3.6 (`recordRawOutput` metadata-only write port + nullable columns); 3.17a/3.17b (snapshot ctor-shim carriage fan-out).
- Seams: `runner-result.v1.schema.json:53-68,106-165`; `runners/claude/lib/runner.mjs:267-305,700-709`; `runners/codex/lib/runner.mjs:289-327,707-716`; `RunnerExecutionSnapshot.java:9-45,118-160`; `RunnerExecutionEntity.java:101-111`; `RunnerExecutionEntityMapper.java:18`; `RunnerBroker.java:1591,1611-1704,2079`; `RunnerExecutionRecordPort.java:245-250`; `RunnerExecutionPersistenceAdapter.java:826-859`; `FlywaySchemaContractTest.java:239-250,538-568`.

## Dev Agent Record

### Agent Model Used

Opus 4.8 [1m] (claude-opus-4-8) via bmad-dev-story, 2026-07-02.

### Debug Log References

- Contracts module built + installed FIRST (`mvnw -pl deliveryline-runner-contracts install`) so the backend resolves the FRESH schema (the runner-contracts stale-in-`.m2` trap) — `RunnerContractValidatorTest` 7/7 green (new valid `usage` fixture auto-enumerated; new invalid negative-token fixture added to the expectations manifest so the count-parity assertion held).
- Cleared the stale `target/classes/db/migration/V31__narrow_pending_clarification_index...sql` (no source counterpart) BEFORE adding the source V31 — otherwise the classpath carries two V31 scripts and the replay/checksum gate reds. Confirmed `target/classes` now holds only the correct `V31__add_runner_execution_token_columns.sql`.
- `FlywaySchemaContractTest` 30/30 (new `runnerExecutionsCarriesTheV31TokenColumns` + `flywayMigrateIsReplaySafeAndChecksumStable` both green on real Postgres).
- Backend full Surefire suite **1464/0** (no regression from the 27-arg canonical ctor — the ≈36 existing `new RunnerExecutionSnapshot(...)` sites compile unchanged via the pre-3.17a shim + the new 24-arg shim).
- New IT `RunnerExecutionTokenUsagePersistenceIT` 3/3 (real PG). Trap hit + fixed: seeding a `status='completed'` row violated `ck_runner_executions_completed_correlation` (a completed row needs completion/correlation fields) — seeded `status='running'` instead (the metadata write is status-agnostic; the test asserts status is unchanged). Ran via the `verify` lifecycle + `-Djacoco.skip=true` (direct `failsafe:` goals crash on the unresolved `@{argLine}`).
- Runner JS node `--test` 10/10 across both entrypoints; both `runner.mjs` files `node --check` clean.

### Completion Notes List

- **AC1 (contract):** added `normalizedOutput.usage` → `#/$defs/usage` ({inputTokens,outputTokens,totalTokens} each `integer`/`minimum:0`, `additionalProperties:false`, NO `required`). NOT added to top-level `required` (still 8 keys); `schemaVersion const:1` unchanged. New valid fixture `runner-result.v1.spec.usage.valid.json` + invalid `runner-result.v1.invalid-usage-negative-tokens.json` (+ manifest entry).
- **AC1/AC5 (emit):** BYTE-IDENTICAL `sanitizeUsage` + `buildUsage` helpers in both `runner.mjs` files, modeled on `sanitizeUsageWindow`/`buildProviderUsage` — copy only the three allowed non-negative-integer keys, never throw, read the `DELIVERYLINE_USAGE_MOCK_FILE` seam (mirrors `DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE`). Wired into the `commandBuild` result via `if (usage) normalizedOutput.usage = usage;` so a no-usage result stays byte-identical (never `{}`/`null`). Emits a `built usage present={}` stderr line (never the raw payload).
- **AC1/AC6 (deterministic mock):** offline/mock Docker build branch bakes `usage.mock.json` `{"inputTokens":1200,"outputTokens":800,"totalTokens":2000}` + `ENV DELIVERYLINE_USAGE_MOCK_FILE` in BOTH Dockerfiles (mirrors the 3d-7 provider-usage baking); production builds never create the file → real path omits `usage`. Mock CLI scripts unchanged (emit stdout only).
- **AC2 (migration):** `V31__add_runner_execution_token_columns.sql` — three nullable `integer` columns, no default, no CHECK. Stale `target/classes` V31 cleared first.
- **AC3 (entity/snapshot/mapper):** entity gains three nullable `Integer @Column` fields + getters/setters (mirror `redactionCount`); `RunnerExecutionSnapshot` canonical ctor extended to 27 args (three appended at END) with a NEW 24-arg shim delegating three `null`s + the pre-3.17a shim tail extended by three `null`s; mapper passes the three columns via the 27-arg ctor.
- **AC3/AC4/AC5 (ingest):** new metadata-only `RunnerExecutionRecordPort.recordTokenUsage` (mandatory — the persistence adapter is the sole impl; all test usages are Mockito mocks) implemented in the adapter with `@Transactional(REQUIRES_NEW)` `findByPublicIdForUpdate → set columns → saveAndFlush` (mirrors `recordRawOutput`). Broker `captureTokenUsage` reads `normalizedOutput.usage`, tolerates missing/non-object, reads each count via a new non-negative `tokenCount` helper (drops negatives — defense-in-depth; the contract validator already rejects schema-invalid `usage` before `handleSuccess`), skips the write when all three are null, and swallows all `RuntimeException` (best-effort). Invoked right next to `captureProviderUsage` in `handleSuccess`. Each count persisted independently — `totalTokens` never synthesized.
- **AC4 note:** a negative/non-integer/non-object `usage` is schema-INVALID → rejected by `RunnerContractValidator` before `handleSuccess` (same posture as `providerUsage`), so at the broker tier only an empty/partial (schema-valid) `usage` is reachable; the "malformed → sanitized, never fatal" guarantee is exercised at the JS EMIT tier (where a garbage mock source is dropped to absent before it is ever emitted). Both tiers tested.
- **AC5 (redaction/logging):** sanitizer copies only the three allowed keys (hostile-mock test asserts no secret-shaped string survives); broker logs counts + ids at INFO, best-effort WARN on skip/failure (CRLF-neutralized via `MdcKeys.sanitizeForLog`), never the raw payload — pinned by list-appender tests.
- **AC6 (tests):** JS round-trip present/absent/partial/malformed/hostile (both entrypoints); contracts fixtures (valid + negative-rejected); `FlywaySchemaContractTest` drift + replay; broker unit (present/absent/partial/empty/write-failure/logging); adapter unit (set-columns + missing-row-throws); mapper unit (carry + null parity); real-PG IT (round-trip + null parity + partial + status-unchanged).
- **Out of scope (per story):** no OpenAPI/`schema.d.ts` regen, no FE, no read model/rollup (all 3g-4); no new state/action/event/error-code; real-CLI token extraction remains a documented forward option (kept the text-mode `-p` stdout→summary→checksum pipeline unchanged).

### Change Log

| Date | Change |
| --- | --- |
| 2026-07-02 | FR74 backend delivered (ready-for-dev → review). Additive-optional `usage` runner-contract field; best-effort emit in both `runner.mjs` + offline-mock baking; V31 three nullable token columns on `runner_executions`; `RunnerExecutionSnapshot` 27-arg canonical + new 24-arg shim; `recordTokenUsage` metadata-only write port + adapter; broker `captureTokenUsage`. Verified: contracts 17/0, backend Surefire 1464/0, FlywaySchemaContractTest 30/0, token-usage IT 3/0, runner JS 10/0; spotless applied. |

### File List

**Contract (deliveryline-runner-contracts)**
- `src/main/resources/schemas/runner-result.v1.schema.json` (modified — `usage` property + `$defs/usage`)
- `src/test/resources/fixtures/valid/runner-result.v1.spec.usage.valid.json` (new)
- `src/test/resources/fixtures/invalid/runner-result.v1.invalid-usage-negative-tokens.json` (new)
- `src/test/resources/fixtures/fixture-expectations.json` (modified — manifest entry for the negative fixture)

**Runner JS (runners)**
- `claude/lib/runner.mjs` (modified — `sanitizeUsage`/`buildUsage` + `commandBuild` wiring)
- `codex/lib/runner.mjs` (modified — byte-identical twin)
- `claude/Dockerfile` (modified — `DELIVERYLINE_USAGE_MOCK_FILE` env + offline `usage.mock.json` bake)
- `codex/Dockerfile` (modified — same)
- `claude/test/runner-token-usage.test.mjs` (new)
- `codex/test/runner-token-usage.test.mjs` (new)

**Backend (deliveryline-backend)**
- `src/main/resources/db/migration/V31__add_runner_execution_token_columns.sql` (new)
- `src/main/java/org/dradgo/adapters/persistence/entity/RunnerExecutionEntity.java` (modified — three `Integer` columns + accessors)
- `src/main/java/org/dradgo/application/runner/spi/RunnerExecutionSnapshot.java` (modified — 27-arg canonical + new 24-arg shim + extended pre-3.17a shim tail)
- `src/main/java/org/dradgo/adapters/persistence/mapper/RunnerExecutionEntityMapper.java` (modified — pass three columns)
- `src/main/java/org/dradgo/application/runner/spi/RunnerExecutionRecordPort.java` (modified — `recordTokenUsage`)
- `src/main/java/org/dradgo/adapters/persistence/RunnerExecutionPersistenceAdapter.java` (modified — `recordTokenUsage` impl)
- `src/main/java/org/dradgo/application/runner/RunnerBroker.java` (modified — `captureTokenUsage` + `tokenCount` helper + `handleSuccess` invocation)
- `src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` (modified — `runnerExecutionsCarriesTheV31TokenColumns`)
- `src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java` (modified — token-usage capture tests + payload helper)
- `src/test/java/org/dradgo/adapters/persistence/RunnerExecutionPersistenceAdapterManualTest.java` (modified — `recordTokenUsage` adapter tests)
- `src/test/java/org/dradgo/adapters/persistence/mapper/RunnerExecutionEntityMapperTest.java` (new)
- `src/test/java/org/dradgo/adapters/persistence/RunnerExecutionTokenUsagePersistenceIT.java` (new)

**Tracking**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — 3g-3 → review)

## Review Findings

<!-- 2026-07-02 bmad-code-review (Opus 4.8 [1m]) — 3 adversarial layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor). Acceptance Auditor: NO confirmed AC violations — all 6 ACs + every anti-pattern satisfied; runner.mjs emit blocks verified byte-identical. Triage: 1 decision-needed, 4 patch, 0 defer, 9 dismissed. -->

<!-- All findings applied + verified 2026-07-02 (bmad-code-review, Opus 4.8 [1m]): contracts install 17/0, runner JS 10/0 (both runner.mjs sanitizeUsage blocks re-verified byte-identical), RunnerBrokerUnitTest 74/0, spotless applied. -->

- [x] [Review][Patch] (resolved from Decision → cap at int32) Over-int32 token counts silently dropped to `null` — contract (`minimum:0`, no `maximum`) and JS `sanitizeUsage` accept any non-negative integer, but the DB columns are int4 and the broker `tokenCount`/`providerUsageInt` reader drops any value `> Integer.MAX_VALUE` to `null`. FIXED: added `"maximum": 2147483647` to the three `$defs/usage` schema properties AND an `inRange` int32 guard in `sanitizeUsage` (both runner.mjs, byte-identical) so contract + JS agree with the int4 storage. [runner-result.v1.schema.json usage $defs; runners/{claude,codex}/lib/runner.mjs sanitizeUsage]
- [x] [Review][Patch] codex token-usage test copied the Claude auth var + Anthropic-shaped secret instead of codex-appropriate values. FIXED: swapped `--auth-var` to `CODEX_AUTH_JSON` and `SECRET` to `sk-openai-…` (matches the codex `runner-provider-usage.test.mjs` precedent). [runners/codex/test/runner-token-usage.test.mjs]
- [x] [Review][Patch] anti-synthesis invariant untested in the risky direction. FIXED: added `onResultInputAndOutputWithoutTotalDoesNotSynthesizeTotal` — `{inputTokens,outputTokens}` (no total) → asserts `recordTokenUsage(REX_ID, 1200, 800, null)`. [RunnerBrokerUnitTest.java]
- [x] [Review][Patch] `recordTokenUsage` Javadoc "always terminal by the time a result is ingested" was inaccurate (capture runs BEFORE `recordCompleted`; row still `running`). FIXED: reworded to "status-agnostic — capture runs during result ingest BEFORE the row transitions to terminal, tolerates a still-running row as well as an already-terminal one". [RunnerExecutionRecordPort.java]
- [x] [Review][Patch] no regression test pinned "token usage NOT captured when a secret-leak quarantine short-circuits the run". FIXED: added `onResultDoesNotCaptureTokenUsageWhenSecretLeakDetected` (twin of the providerUsage case) — leak scan → `verify(recordPort, never()).recordTokenUsage(...)`. [RunnerBrokerUnitTest.java]
