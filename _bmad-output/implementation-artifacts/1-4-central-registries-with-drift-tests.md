# Story 1.4: Central Registries with Drift Tests

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a foundation developer,
I want central registries for every enumerable domain value (states, events, errors, failures, artifact types, statuses, allowed actions, ID prefixes, runner schema versions) with automated drift tests,
so that no value can be silently introduced outside the registry and cross-layer consistency is enforceable.

## Acceptance Criteria

1. **Given** the `domain.registry` package, **Then** `DomainRegistry`, `DomainErrorCode`, `AllowedAction`, `WorkflowState`, `WorkflowEventType`, `FailureCategory`, `ArtifactType`, `ArtifactOperationStatus`, `RunnerExecutionStatus`, `DataClassification`, `PublicIdPrefixes`, and `RunnerSchemaVersion` exist as authoritative value sources.
2. **Given** `WorkflowEventType`, **Then** event types follow dot-separated lowerCamel namespaces (for example `workflow.stateChanged`, `approval.requested`, `runner.failed`, `recovery.reconciled`, `integration.linked`, `export.created`).
3. **Given** `DomainErrorCode`, **Then** codes are uppercase snake_case and include at minimum `ILLEGAL_TRANSITION`, `IDEMPOTENCY_KEY_CONFLICT`, `APPROVAL_VERSION_MISMATCH`, `CONCURRENT_TRANSITION_CONFLICT`, `RUNNER_TIMEOUT`, `RUNNER_CONTRACT_VIOLATION`, `ARTIFACT_PAYLOAD_UNAVAILABLE`, plus `UNKNOWN_REGISTRY_VALUE` to satisfy the persisted-value fail-fast contract.
4. **Given** `FR45` actor attribution, **Then** an `ActorType` registry value set includes `human`, `agent`, `system`, `service_account`.
5. **Given** drift tests, **When** any registry value is added or removed, **Then** a test that compares registry values against (a) corresponding domain enums, (b) API request/response schema references, (c) test fixtures for event types, and (d) a placeholder frontend allowed-actions list fails until the registry, consumers, and fixtures are realigned.
6. **Given** persisted data with an unknown registry value (for example a database row with a workflow state not present in the registry), **When** loaded through the domain mapping layer, **Then** the load fails with an explicit error (`UNKNOWN_REGISTRY_VALUE`) or routes the row to a reconciliation queue; it must never silently coerce to a default.
7. **Given** `PublicIdPrefixes`, **When** an ID is generated or parsed, **Then** the producer uses a registered prefix and the validator rejects any ID with an unknown or mismatched prefix. For this repository's live V1 schema, that contract applies to readable `public_id` values while relational joins continue to use numeric `id bigint`.

## Tasks / Subtasks

- [x] **Implementation order for the dev agent**
  - [x] First create registry/value-source types and shared parsers.
  - [x] Then wire uniform fail-fast mapping for every currently implemented registry-backed persistence or external-input boundary.
  - [x] Then add registry drift contract tests against schema and consumer artifacts.
  - [x] Then add the temporary placeholder consumer manifests in their single documented locations.

- [x] **Task 1: Establish the authoritative registry package in the live backend module** (AC: 1, 2, 3, 4, 7)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/domain/registry/` as the canonical home for registry types; do not create new top-level modules or stale `backend/` aliases.
  - [x] Implement thin authoritative registry types for `DomainRegistry`, `DomainErrorCode`, `AllowedAction`, `WorkflowState`, `WorkflowEventType`, `FailureCategory`, `ArtifactType`, `ArtifactOperationStatus`, `RunnerExecutionStatus`, `DataClassification`, `ActorType`, and `RunnerSchemaVersion`.
  - [x] Implement `PublicIdPrefixes` under a coherent ID-focused package (`domain/id` or equivalent) and keep it aligned to the live V1 `public_id` regex families: `run_`, `evt_`, `art_`, `op_`, `apr_`, `rex_`, `ilk_`, `rcv_`, `idm_`.
  - [x] Provide explicit `fromValue(...)` / parse methods that fail fast on unknown values rather than returning `null`, defaulting, or using lenient `valueOf` wrappers.
  - [x] Seed canonical values needed by the already-merged schema and immediately following foundation stories: workflow states from story 1.5, actor types from FR45, runner failure categories needed by story 1.5, V1 artifact statuses, runner execution statuses, data classifications, and runner schema version `1`.
  - [x] Seed `DomainErrorCode` with the Story 1.4 minimum set plus the immediately assumed Epic 1/1.20 codes: `UNKNOWN_REGISTRY_VALUE`, `INVALID_ID_PREFIX`, `RUN_NOT_FOUND`, `DOCTOR_POSTGRES_UNREACHABLE`, `DOCTOR_FLYWAY_FAILED`, `DOCTOR_REST_BIND_UNAVAILABLE`, `DOCTOR_DOCKER_MISSING`, `DOCTOR_CONFIG_PERMISSIONS_UNSAFE`, `DOCTOR_UNSUPPORTED_ENVIRONMENT`, `DOCTOR_ARTIFACT_DIR_UNWRITABLE`.
  - [x] Keep `DomainRegistry` simple and code-owned; do **not** build a dynamic registry framework, config-driven registry loader, or database lookup-table system in this story.

- [x] **Task 2: Add fail-fast domain mapping for persisted registry values** (AC: 3, 4, 6, 7)
  - [x] Introduce `UNKNOWN_REGISTRY_VALUE` as a stable domain error code and wire it into registry parsing helpers.
  - [x] Add shared mapping/parsing helpers so **every currently implemented registry-backed load path** uses the same fail-fast behavior, not just a single sample field.
  - [x] Cover at minimum the registry-backed V1 fields that already exist in the schema and are likely to be loaded first: `workflow_runs.current_state`, `workflow_events.{prior_state,resulting_state,actor_type}`, `artifacts.{artifact_type,classification,status}`, `artifact_operations.status`, `approvals.actor_type`, `runner_executions.status`, `integration_links.sync_status`, and `recovery_actions.actor_type`.
  - [x] Prove that an unknown persisted value raises an explicit domain error with the offending registry name and raw value in machine-readable `details`, so later REST/CLI surfaces can map it consistently.
  - [x] Choose the explicit-error path now; do **not** build the reconciliation queue yet. If a later recovery story wants queue routing, leave a seam for it instead of silently swallowing invalid values today.
  - [x] Ensure prefix parsing/validation is applied to `public_id` strings and any externally supplied workflow/artifact/event identifiers, and does not regress the story 1.3 `bigserial id + public_id text` pivot.

- [x] **Task 3: Build registry drift contract tests across code, schema, and placeholder consumers** (AC: 1, 5, 6, 7)
  - [x] Add focused contract tests under `deliveryline-backend/src/test/java/org/dradgo/contract/` for registry drift and unknown-value handling.
  - [x] Reuse the existing PostgreSQL-backed Flyway contract infrastructure where schema interaction matters; do not create duplicate heavyweight container setup without a concrete need.
  - [x] Assert the workflow-state registry stays aligned with the V1 schema's current-state/prior-state/resulting-state checks and with the actor-type checks already enforced in SQL.
  - [x] Assert `PublicIdPrefixes` stay aligned with the V1 `public_id` regex checks and with any generator/parser helpers introduced in the story.
  - [x] Assert drift against the single documented consumer artifacts for this phase: API schema reference manifest, frontend allowed-actions placeholder manifest, and workflow event-type fixture manifest.
  - [x] Assert `DomainErrorCode` drift against the future REST Problem Details mapping contract so new codes cannot be added without updating the mapper/type-URL ownership expected by story 1.8.
  - [x] Make the failure mode explicit and developer-facing: a single registry addition/removal should break tests until every declared consumer artifact is updated in the same change.

- [x] **Task 4: Create minimal placeholder contract artifacts for future API/frontend layers without stealing later stories** (AC: 2, 5)
  - [x] Create the temporary API-schema reference manifest at `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`. This file is backend-test-owned only until the real committed OpenAPI snapshot exists at `deliveryline-backend/src/main/resources/openapi/`; once that snapshot exists, drift tests must read the real snapshot instead of maintaining both.
  - [x] Create the temporary frontend allowed-actions placeholder manifest at `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json`. This file is the **only** placeholder source for story 1.4; story 2.14 or the real frontend module must replace it in-place or redirect the test to a single canonical frontend-owned file, not create a second list elsewhere.
  - [x] Create the workflow event-type fixture manifest at `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json` covering the namespace examples and initial event families expected by Epic 1 through Epic 5.
  - [x] Keep runner context/result schema files and their valid/invalid fixtures **out of backend test resources**; those belong exclusively to `deliveryline-runner-contracts` once story 1.6 populates that module.
  - [x] Document in code comments and completion notes exactly when each temporary manifest must hand off to its long-term owner: OpenAPI snapshot in story 1.20/1.21, runner schemas and fixtures in story 1.6, frontend allowed-actions consumer in story 2.14, foundation-gate fixture stream in story 1.23.
  - [x] Do **not** pre-implement the real frontend module, OpenAPI generation, or runner-contract schemas here; placeholders are enough only if they are singular, authoritative for this phase, and drift-tested.

- [x] **Task 5: Preserve architecture boundaries and the foundation-gate intent** (AC: 1-7)
  - [x] Keep story 1.4 focused on central registries and their tests; do **not** implement the transition service (1.5), runner schemas (1.6), shared command model (1.7), Problem Details mapper (1.8), idempotency service behavior (1.9), or ArchUnit package-boundary suite (1.11).
  - [x] Do **not** migrate the live V1 schema to lookup tables, PostgreSQL enums, or a new persistence model. Story 1.4 should align registries to the schema already merged in story 1.3 and prove that alignment through tests.
  - [x] Do **not** create phantom `deliveryline-cli` or `deliveryline-shared` modules because they are not present in the repo. All story 1.4 implementation work belongs in `deliveryline-backend`, with only placeholder artifacts touching `deliveryline-frontend` / `deliveryline-runner-contracts` if strictly necessary.
  - [x] If any unavoidable pattern change is discovered, record it in implementation notes and targeted docs/comments rather than silently drifting away from the architecture.

### Review Findings

*Code review on 2026-04-27 — 3-layer adversarial. Acceptance Auditor: 5/7 ACs ✅, ACs 1 and 5 ⚠️; all 11 spec-listed parser fields covered. Blind Hunter: 32 findings (1 BLOCKER on ****\*******`DomainException`****\*). Edge Case Hunter: 38 findings. After dedup/triage: 5 decisions, 18 patches, 11 deferred, 9 dismissed.*

**Decisions needed:**

- [x] [Review][Decision] **`DomainErrorCode`**** wire stability** — `value()` returns `name()`, so renaming an enum constant silently breaks problem+json type URIs and any wire consumer that pinned to the literal. Other registries (`ActorType`, `WorkflowState`, etc.) have an explicit `value` field decoupled from the enum constant. Options: (a) add an explicit `wireValue` field per error code (one-time work; future-proofs against renames), (b) document that error-code names ARE the wire contract and treat renames as breaking changes, (c) defer.
- [x] [Review][Decision] **`fromValue(rawValue)`**** no-field overload** — Every registry exposes both `fromValue(rawValue)` and `fromValue(rawValue, field)`. Callers using the former produce exceptions whose `details` lack the `field` key, weakening the persistence-boundary contract that `RegistryContractTest` enforces (every persistence boundary must surface `field` in details). Options: (a) deprecate the no-field overload + warn at compile time, (b) remove it (only field-aware variant supported), (c) make it package-private so it can only be used within `org.dradgo.domain`, (d) keep both as convenience.
- [x] [Review][Decision] **`PersistedRegistryValues`**** coverage scope** — All 11 spec-listed AC fields are covered. Edge Case Hunter identifies additional CHECK-constrained columns the spec does NOT explicitly list: `artifact_operations.operation_type`, `integration_links.integration_type`, `approvals.decision`, `recovery_actions.{action_type, result_status}`, `idempotency_records.status`, `workflow_events.rejection_taxonomy`, `approvals.rejection_taxonomy`. SQL has CHECK lists for these but no Java-side registry. Options: (a) add registries + persistence helpers + drift assertions for all of them now, (b) document them as intentionally inline-CHECK-only, (c) defer to follow-up (story 1.5 / 1.18 territory).
- [x] [Review][Decision] **V1 schema asymmetries discovered** — `artifacts.artifact_type` has NO CHECK constraint in V1 (only the registry list in Java); `artifacts.classification` similarly has no CHECK; `workflow_events.failure_category` only has `length(failure_category) > 0` (no enum). Drift test cannot bind these to SQL since the SQL has no enumeration. Options: (a) raise a V2 migration to add the missing CHECKs (extends 1-3 work; bidirectional drift), (b) document the asymmetry — registry is authoritative for these, SQL is permissive — and accept that DB rows can carry values not in the registry, (c) defer to a follow-up schema-tightening story.
- [x] [Review][Decision] **Missing workflow states / failure categories** — Blind Hunter flags `WorkflowState` is missing `Cancelled`, `Aborted`, `Queued`, `Superseded`, `RetryScheduled`, `Retrying`; `FailureCategory` has only RUNNER_* (no `INFRASTRUCTURE_FAILURE`, `DEPENDENCY_UNAVAILABLE`, `CONFIGURATION_INVALID`, `PERMISSION_DENIED`, `RATE_LIMITED`, `INPUT_VALIDATION`). Spec says "align to V1, story 1.5 owns transitions." Options: (a) accept as-is (1.5 territory; matches V1 CHECK), (b) extend now (requires V2 migration + amend story 1.5 plan), (c) defer to 1.5 with a tracking note in `deferred-work.md`.

**Patches applied (2026-04-27):** all items below `[x]`. Verified by `mvnw test -pl deliveryline-backend` → 31/31 pass on Testcontainers Postgres 17.9 (15 RegistryContractTest + 10 FlywaySchemaContractTest + 1 DeliveryLineApplicationTests + 5 LocalDevelopmentContractTest).

**Implementation summary of patches:**

- **DomainException.java rewrite** — null-safe details (defaults to `Map.of()`); cause-bearing constructor `(errorCode, message, cause)`; convenience constructor `(errorCode, message)`; full constructor `(errorCode, message, details, cause)` calling `super(message, cause)`.
- **DomainErrorCode.java rewrite** — added explicit `wireValue` field per code (matches enum constant for V1, decoupled going forward); `wireValue()` accessor; package-private no-field `fromValue` overload.
- **RegistryParsers.java** — `index()` detects duplicate `value()` strings at static init (throws `IllegalStateException` with both colliding constants); `parse()` distinguishes null input via `reason: "null_value"` detail key (no longer concatenates literal `"null"` into the message); returns `Collections.unmodifiableMap` (not `Map.copyOf`) so iteration order stays predictable.
- **DomainRegistry.java rewrite** — added accessors for `WorkflowEventType`, `FailureCategory`, `ArtifactType`, `ArtifactStatus`, `IntegrationSyncStatus`, `RunnerSchemaVersion`; every accessor wraps its set in `Collections.unmodifiableSet` so callers cannot mutate the static cache; `valuesOf` private helper reused.
- **PublicIdPrefixes.java rewrite** — `fromPublicId` now distinguishes null (`reason="null_value"`), empty (`reason="empty_value"`), unknown prefix (`reason="unknown_or_mismatched_prefix"`), and malformed suffix (`reason="malformed_suffix"`); the suffix is validated against the V1 SQL pattern `[A-Za-z0-9_-]{4,64}`; `format(suffix)` validates the supplied suffix; static-init invariant rejects prefix-of-prefix relationships (future `run_v2_` would fail to load); `prefixMap()` returns `Collections.unmodifiableMap`.
- **All 13 registry enum types** — `static <T> fromValue(String rawValue)` (no-field overload) made package-private per Decision 2; `fromValue(rawValue, field)` remains public.
- **RegistryContractTest.java rewrite** —
  - `extractConstraintValues` now matches both `IN (...)` and PG-normalized `= ANY (ARRAY[...])` forms via `VALUES_CLAUSE` regex; only literals inside the values clause are extracted (no incidental quoted tokens).
  - `queryForObject` replaced with `queryForList` + `assertEquals(1, defs.size(), "Constraint not found: ...")` — clean diagnostic on missing constraint.
  - New `publicIdPrefixSqlRegexExactlyMatchesJavaSuffixPattern` test asserts the SQL CHECK regex matches the literal V1 form `^<prefix>_[A-Za-z0-9_-]{4,64}$` per prefix.
  - Drift assertions added for `ArtifactType`, `DataClassification`, `RunnerSchemaVersion` (new `registriesWithoutSqlChecksStayAlignedWithApiPlaceholder` test) and for `WorkflowEventType` / `IntegrationSyncStatus` / `FailureCategory` via `DomainRegistry`.
  - `readArrayNonEmpty` / `readObjectNonEmpty` helpers fail empty/duplicate fixtures so empty fixture + empty registry no longer passes vacuously.
  - `nullableStateParsingPreservesNullButRejectsUnknownAndEmpty` adds `assertThrows` on unknown nullable input AND on empty-string nullable input.
  - `persistenceBoundariesRejectEmptyStringAndCaseMismatchedInputs` exercises empty-string, lowercase, padded, uppercase variants.
  - `nonNullablePersistenceBoundariesRejectNullWithExplicitReason` asserts `reason="null_value"` for non-nullable null.
  - `domainExceptionConstructorsHandleNullDetailsAndCauses` covers the BLOCKER fix (no NPE on null details) and cause preservation.
  - `domainErrorCodesStayAlignedWithProblemTypeOwnershipManifest` now asserts URI uniqueness, non-empty path suffix, and pins `wireValue == name()` for V1.
  - `failureCategoryRegistryHasNoExternalConsumerYetButIsCatalogued` documents the deliberate asymmetry.
  - `registryCatalogExposesTheAuthoritativeFoundationValueSets` asserts every AC-1-named registry is exposed via `DomainRegistry`.

*Verification: **`mvnw test -pl deliveryline-backend`** → 31/31 pass on Testcontainers Postgres 17.9.*

**Original patch checklist (kept for traceability — all ****`[x]`****):**

- [x] [Review][Patch] **BLOCKER:** `new LinkedHashMap<>(details)` in `DomainException` ctor NPEs when `details == null`; null-safe to `Map.of()`. [`DomainException.java:14`]
- [x] [Review][Patch] `DomainException` has no constructor accepting a `Throwable cause` — wrapping a SQL/JSON/parser error drops the original stack. Add `(errorCode, message, cause)` and `(errorCode, message, details, cause)` overloads. [`DomainException.java`]
- [x] [Review][Patch] `DomainRegistry` exposes only ~half of the AC-1-named registry types as accessors. Add accessors (and corresponding test assertions) for `WorkflowEventType`, `FailureCategory`, `ArtifactType`, `ArtifactStatus`, `IntegrationSyncStatus`, `RunnerSchemaVersion`. [`DomainRegistry.java`]
- [x] [Review][Patch] `DomainRegistry` accessor methods return mutable `LinkedHashSet`s — caller can mutate the static cached set and corrupt subsequent lookups. Wrap in `Collections.unmodifiableSet` at the boundary. [`DomainRegistry.java`]
- [x] [Review][Patch] `PublicIdPrefixes.fromPublicId` only checks `startsWith` — `run_` (just prefix), `run_  ` (whitespace), or `run_'; DROP TABLE` all match. Validate the entire suffix against the V1 regex `^<prefix>_[A-Za-z0-9_-]{4,64}$` after the prefix match. [`PublicIdPrefixes.java`]
- [x] [Review][Patch] `PublicIdPrefixes.fromPublicId(null)` returns "unknown prefix" — distinguish null/empty/blank from unknown-prefix in the error path (both code AND message). [`PublicIdPrefixes.java`]
- [x] [Review][Patch] `PublicIdPrefixes` lacks a static-init duplicate-prefix check — two prefixes where one is a prefix of another (future risk: adding `run_v2_`) would silently misidentify. Assert at static init that no prefix is a prefix of another. [`PublicIdPrefixes.java`]
- [x] [Review][Patch] `RegistryContractTest.QUOTED_LITERAL = '([^']+)'` extracts ALL single-quoted tokens from `pg_get_constraintdef` output — picks up incidental quoted literals (e.g., `format('...')`, default sentinels). Restrict to within `IN (...)` clause OR query `pg_constraint` AST directly. [`RegistryContractTest.java`]
- [x] [Review][Patch] `RegistryContractTest.PREFIX_PATTERN = '\\^([a-z]+_)'` only validates prefix segment of the SQL CHECK; suffix grammar is unverified — Java emitting `run_abcDEF` passes even if SQL says `^run_[0-9]{4}$`. Compare full regex strings or insert/probe with concrete public_id values. [`RegistryContractTest.java`]
- [x] [Review][Patch] `queryForObject` for constraint definitions throws `EmptyResultDataAccessException` on missing constraint — masks the descriptive `"Constraint not found"` IllegalStateException. Use `queryForList(...)` + `assertEquals(1, size, "Constraint not found: ...")`. [`RegistryContractTest.java:172-175`]
- [x] [Review][Patch] Drift assertions missing for `ArtifactType`, `RunnerSchemaVersion`, `DataClassification` against `registry-api-schema-placeholders.json` (the JSON has `artifactTypes`, `runnerSchemaVersions`, `dataClassifications` arrays but no test reads them). Add `assertEquals` per registry. [`RegistryContractTest.java`]
- [x] [Review][Patch] `FailureCategory` has no drift test against any consumer (no SQL CHECK; no API manifest entry). Either add manifest entry + assertion, or document explicitly that `FailureCategory` is registry-only and not yet part of any external contract. [`RegistryContractTest.java`, `registry-api-schema-placeholders.json`]
- [x] [Review][Patch] `readArray` empty-fixture vacuous-pass: if a fixture array is `[]` AND the registry is also empty, `assertEquals` passes silently. Add `assertFalse(values.isEmpty())` before equality for every drift assertion. [`RegistryContractTest.java:248-258`]
- [x] [Review][Patch] `nullableStateParsingPreservesNullWhileRejectingUnknownValues` claims to test rejection but only asserts the null and one-valid path — no `assertThrows` on `"__bogus__"`. Add the rejection assertion. [`RegistryContractTest.java:148`]
- [x] [Review][Patch] `RegistryParsers.index` silently overwrites on duplicate `value()` strings — silent registry duplicates produce ambiguous resolution. Detect: `if (lookup.put(...) != null) throw new IllegalStateException("Duplicate registry value: ...")`. [`RegistryParsers.java`]
- [x] [Review][Patch] `RegistryParsers.parse` non-nullable null-handling: passing `null` produces `"Unknown value 'null'..."` — conflates "null/missing" with "unknown value". Distinguish: throw with a clearer message and a `reason: "null_value"` detail key, OR introduce a separate `MISSING_REGISTRY_VALUE` error code. [`RegistryParsers.java`]
- [x] [Review][Patch] Persistence-boundary tests only feed `"__unknown__"` — null, empty, whitespace, and case-mismatched inputs are not exercised. Add parametrized cases for `null`, `""`, `" Inbox"`, `"INBOX"` (lowercase variant for actor types) per representative parser to nail down the documented contract. [`RegistryContractTest.java`]
- [x] [Review][Patch] V1 SQL CHECK constraints with no Java-side registry counterpart (per Decision 3): if the decision is "add registries", patches will follow per-column. If decision is "document only", add explicit comments in `DomainRegistry` listing intentionally-inline-only CHECKs.

**Deferred (recorded in \****`deferred-work.md`**\*\*):**

- [x] [Review][Defer] Self-rubber-stamp problem on placeholder fixtures — `allowed-actions.placeholder.json`, `registry-api-schema-placeholders.json`, `workflow-event-types.fixture.json` are authored alongside the enums in this same diff, so drift is structurally impossible until external owners take over (story 1.20/1.21 for OpenAPI, 1.23 for foundation-gate fixtures, 2.14 for frontend). The drift-test-as-theatre concern is acknowledged by the spec.
- [x] [Review][Defer] AC-5(a) "drift vs domain enums" — no parallel domain enums exist; the spec scope says story 1.4 should not yet build the domain model. The registry-vs-itself check is the practical interpretation.
- [x] [Review][Defer] `RunnerSchemaVersion` int-vs-string design — current `value() = "1"` will require redesign for `1.1` / `2-beta`. Revisit when story 1.6 introduces real schema versioning.
- [x] [Review][Defer] `DomainRegistry` returns `Set<String>` rather than `Set<RegistryValue>` (or sealed types). Stringly-typed external API. Revisit when consumer call sites materialize.
- [x] [Review][Defer] Registry value collisions across enums (`ArtifactStatus.PENDING("pending")` and `ArtifactOperationStatus.PENDING("pending")`). Field-aware overload mitigates blast radius; structural duplicate-detection is a nice-to-have.
- [x] [Review][Defer] AC-6 reconciliation queue seam — current explicit-error path satisfies "fail fast", but no extension point exists for routing to a recovery queue. Revisit when story 1.18 (CLI MVR baseline) or recovery work needs it.
- [x] [Review][Defer] `RegistryContractTest` is `@SpringBootTest` — heavyweight for what's mostly enum/string/regex assertions. Split fast unit tests from DB-touching ones in a follow-up performance pass.
- [x] [Review][Defer] `Map.copyOf` does not preserve insertion order on JDK 17+ — if any consumer relies on ordered iteration of LOOKUP for error messages listing acceptable values, ordering becomes nondeterministic. Use `Collections.unmodifiableMap(new LinkedHashMap<>(lookup))` if order matters.
- [x] [Review][Defer] Tabs vs spaces indentation — every new `.java` file uses tabs; project lacks `.editorconfig`. Address via project-wide formatter setup, not in 1-4.
- [x] [Review][Defer] Hard-coded literal `"https://deliveryline.local/problems/"` in test — lift into a single source of truth (constant) when the Problem Details mapper lands in story 1.8.
- [x] [Review][Defer] `details()` returns map of `Object` values that may be mutable collections — defensive copying of values is a polish item.

**Dismissed as noise (9 items):** ordinal-usage warning on `DomainErrorCode` (nobody is using `@Enumerated(ORDINAL)` here yet); `assertEquals(null, ...)` style nit; class-loader-init cycle speculation (no actual cycle exists); regex catastrophic backtracking on bounded enum inputs; `prefixMap()` rebuild cost (cached); whitespace/case-mismatch in PublicId paths (subsumed by suffix-regex patch); `equalsIgnoreCase` Turkish-i bug (code uses `equals`, not `equalsIgnoreCase`); namespace empty-validation in `EVENT_TYPE_PATTERN` (regex already requires at least one segment); `assertTrue` URI starts-with vacuous pass when empty (subsumed by `assertFalse(empty)` patch).

## Dev Notes

This story is the authoritative vocabulary slice for the whole platform. Its value is not "create a few enums." It is preventing later stories from inventing incompatible strings in SQL, REST, CLI, frontend, runner contracts, or fixtures. If story 1.4 is vague, later epics will drift in different directions and story 1.23's foundation gate becomes cleanup instead of protection.

**Current repo state**
- `deliveryline-backend` is the only populated module today. `deliveryline-frontend` and `deliveryline-runner-contracts` currently contain only `pom.xml` stubs.
- The backend package roots already exist as empty ownership anchors: `org.dradgo.domain`, `org.dradgo.application`, `org.dradgo.adapters`, and `org.dradgo.infrastructure`.
- Story 1.3 already merged the V1 Flyway schema and its contract tests. The schema now uses `bigserial id` as the relational PK and `public_id text` as the readable governed identifier on every core table.
- The existing backend test suite already has a contract-test home under `deliveryline-backend/src/test/java/org/dradgo/contract/`.
- No `project-context.md` file exists anywhere in the repository, so the authoritative context sources are the planning artifacts, prior story files, live repo structure, and recent git history.

**Critical scope discipline**
- Keep registries as code-owned authoritative value sets. Do not over-engineer them into runtime-loaded metadata tables, admin-editable configs, or generic registry frameworks.
- Do not reopen story 1.3's schema shape. Story 1.4 must absorb the `public_id` pivot, not fight it.
- Do not build the whole domain model yet. A thin typed registry layer plus the minimum mapping/test surface is enough.
- Do not confuse this story with story 1.11. Boundary enforcement via ArchUnit is a later story; registry drift here should primarily be plain contract tests unless a very small ArchUnit helper clearly reduces noise.
- Do not preempt Epic 2 or story 1.6 by building real frontend or runner-contract implementations. Placeholder consumer artifacts are acceptable only when they are explicit, tested, and clearly handed off.

**Non-goals and ownership boundaries**
- Do not create phantom `deliveryline-cli` or `deliveryline-shared` modules. CLI work remains inside `deliveryline-backend`.
- Do not create duplicate OpenAPI, frontend allowed-actions, or runner-schema sources of truth. Each temporary placeholder in this story must have one documented successor location.
- Do not place runner context/result schema fixtures in backend resources; `deliveryline-runner-contracts` is the long-term authority for those artifacts.

**Registry-specific guardrails**
- `WorkflowState` must align to the live V1 SQL CHECK values and the upcoming transition table in story 1.5: `Inbox`, `Planned`, `Investigating`, `WaitingForSpecApproval`, `Executing`, `WaitingForReview`, `Completed`, `Failed`, `Paused`, `TakenOver`, `Reconciled`.
- `ActorType` must exist even though it is not named in AC1; FR45 and the V1 schema already require `human`, `agent`, `system`, `service_account`.
- `DomainErrorCode` must include the minimum codes from the epic plus `UNKNOWN_REGISTRY_VALUE`, and it should already seed the near-term Epic 1 infrastructure and lookup codes that later stories explicitly assume (`INVALID_ID_PREFIX`, `RUN_NOT_FOUND`, `DOCTOR_*` family).
- `PublicIdPrefixes` is now about readable `public_id` values. Keep validators/generators strict and future-friendly for added prefixes such as `clr_` in story 2.11, but do not register future prefixes prematurely unless they are required by an accepted AC.
- `AllowedAction` should support drift testing now without locking the product into premature frontend behavior. Register only what the current foundation and immediate next stories need, then extend additively in later stories.
- `WorkflowEventType` must use dot-separated lowerCamel namespaces from day one. Avoid free-form strings or upper snake values that later require cleanup across fixtures and history.
- Unknown registry values must fail uniformly anywhere they are parsed from persisted data or external input. Do not satisfy the story with only one demonstration parser while leaving other fields as raw strings.

**Exact file targets**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/`
- `deliveryline-backend/src/main/java/org/dradgo/domain/id/` or equivalent ID-focused package for `PublicIdPrefixes`
- `deliveryline-backend/src/test/java/org/dradgo/contract/` for registry drift and invalid-value tests
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json`
- `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json`
- `deliveryline-backend/src/main/resources/openapi/` as the future authoritative OpenAPI snapshot location; do not create a competing second location
- `_bmad-output/implementation-artifacts/deferred-work.md` only if implementation discovers a justified deferral that should be preserved for later stories

**Testing expectations**
- Prefer contract-style tests with explicit failure messages over clever reflection magic that hides what drifted.
- Use PostgreSQL-backed assertions only where the schema is genuinely part of the contract, for example verifying registry state lists and `public_id` prefix rules stay aligned with V1.
- Keep unknown-value tests deterministic: insert or simulate a bad value, map it through the real parser/mapping boundary, assert `UNKNOWN_REGISTRY_VALUE`.
- Make unknown-value coverage broad enough to prove the shared parser path is reused across the currently implemented registry-backed fields, not just one showcase field.
- Make placeholder-consumer drift tests cheap to run; they should become a daily guardrail, not an occasional slow integration suite.
- Avoid silent success patterns. If a registry value changes but a consumer file was not updated, the test must say exactly which consumer is stale.

**Previous story intelligence**
- Story 1.3 already established a strong pattern for foundation work: explicit contracts first, PostgreSQL-backed verification where warranted, and narrow scope discipline.
- The biggest downstream consequence from story 1.3 is the `id bigint` / `public_id text` split. Story 1.4 must treat `PublicIdPrefixes` and any ID validation helpers as `public_id` concerns, not as relational PK concerns.
- Story 1.3 completion notes and review findings explicitly called out that architecture, story 1.4, and downstream stories need to absorb the public-ID pivot. This story is the first place where that correction becomes implementation guidance instead of just review commentary.
- The merged V1 schema already encodes actor-type checks, state checks, and `public_id` regex families. Drift tests should exploit that rather than duplicating unchecked string lists in multiple places.

**Git intelligence summary**
- Recent commit titles are `DL-2 initial database schema version`, `DL-1 initial docker compose version`, and `DL-0 fixes after initial review`.
- Recent work has favored small foundation slices with explicit contract tests and targeted ADR/documentation rather than broad speculative scaffolding.
- Keep the same pattern here: authoritative registry code plus tests proving alignment, not half-finished consumers.

**Current official-docs specifics to follow**
- Spring Boot `4.0.6` is the repo baseline in the root `pom.xml`, and the official Spring release announcement on **2026-04-23** says `4.0.6` is current and includes bug fixes, documentation improvements, dependency upgrades, and multiple CVE fixes. Do not churn Spring Boot versions inside this story.
- Spring Shell's current reference docs list `4.0.1` as the latest stable reference set, while the `4.0.2` API docs are available and the repo already pins `spring-shell.version` to `4.0.2`. Use the repo's existing `4.0.x` API surface and avoid copying outdated `3.x` command examples into foundation code.
- ArchUnit's official user guide currently documents `archunit-junit5` `1.4.1` as the JUnit 5 artifact. If implementation uses ArchUnit at all, use the JUnit 5 variant and keep that usage tightly scoped because story 1.11 owns the broader boundary suite.

### Project Structure Notes

- The architecture document still contains some stale `backend/`, `frontend/`, and `runner-contracts/` shorthand examples. In the live repo, the actual module names are `deliveryline-backend`, `deliveryline-frontend`, and `deliveryline-runner-contracts`.
- `deliveryline-backend/src/main/java/org/dradgo/` is the only real source root today. Build story 1.4 there.
- `deliveryline-frontend` and `deliveryline-runner-contracts` are currently POM-only placeholders. If story 1.4 needs contract placeholder artifacts that conceptually belong to them, keep those artifacts clearly temporary and avoid pretending those modules are implemented.
- Existing contract-test ownership already points to `deliveryline-backend/src/test/java/org/dradgo/contract`, which is a natural home for registry drift tests.
- There is no `deliveryline-cli` module. CLI work stays inside the backend module via Spring Shell per the current project structure.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Epic 1 foundation-gate framing and Story 1.4 acceptance criteria]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.5 canonical workflow states and runner failure categories]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 2.11 `clr_` prefix follow-on]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 2.14 allowed-actions follow-on]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - central registry rules, drift-test requirements, project structure, and AI agent guidelines]
- [Source: `_bmad-output/planning-artifacts/prd.md` - FR45-FR47 governance and actor attribution requirements, NFR32 append-only auditability]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` - backend-truth, trust, and allowed-action UX constraints]
- [Source: `_bmad-output/implementation-artifacts/1-3-flyway-v1-core-schema-with-retention-and-measurement-columns.md` - prior-story pivot to `bigserial id + public_id text` and review findings carried into story 1.4]
- [Source: `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql` - live V1 schema checks and `public_id` patterns]
- [Source: `https://spring.io/blog/2026/04/23/spring-boot-4-0-6-available-now` - Spring Boot 4.0.6 release confirmation]
- [Source: `https://docs.spring.io/spring-shell/reference/index.html` - Spring Shell current stable reference line]
- [Source: `https://docs.spring.io/spring-shell/reference/api/org/springframework/shell/core/command/CommandContext.html` - Spring Shell 4.0.2 API confirmation]
- [Source: `https://www.archunit.org/userguide/html/000_Index.html` - ArchUnit JUnit 5 artifact guidance]

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Auto-selected next backlog story from `_bmad-output/implementation-artifacts/sprint-status.yaml`: `1-4-central-registries-with-drift-tests`
- Loaded project config from `_bmad/bmm/config.yaml` and used planning artifacts from `_bmad-output/planning-artifacts/`
- Reviewed prior implementation story `1-3-flyway-v1-core-schema-with-retention-and-measurement-columns.md` for carry-forward constraints and the public-ID pivot
- Scanned live repo structure and confirmed only `deliveryline-backend` is populated; `deliveryline-frontend` and `deliveryline-runner-contracts` remain POM stubs
- Reviewed recent git history to preserve current foundation-slice implementation patterns
- Checked current official sources for Spring Boot, Spring Shell, and ArchUnit version guidance relevant to this story
- Added `RegistryContractTest` first, captured the red compile failure for missing registry/domain classes, then implemented the registry layer and placeholder manifests to satisfy the contract
- Fixed the registry contract test to use a local `ObjectMapper` after the first Docker-backed run showed the backend module does not expose one as a Spring bean
- Verified targeted contract coverage with `./mvnw -pl deliveryline-backend -Dtest=RegistryContractTest test`
- Verified module safety with `./mvnw -pl deliveryline-backend test`

### Completion Notes List

- Implemented a thin registry layer under `org.dradgo.domain.registry` plus `org.dradgo.domain.id.PublicIdPrefixes`, keeping all authority in code-owned types rather than adding a dynamic registry framework
- Added `DomainException`, shared registry parsing helpers, and `PersistedRegistryValues` so every currently required persistence-boundary field now fails fast with `UNKNOWN_REGISTRY_VALUE` or `INVALID_ID_PREFIX` and machine-readable `details`
- Added PostgreSQL-backed registry drift tests that compare registry values against SQL CHECK constraints, placeholder API/frontend/event manifests, and the future Problem Details ownership map
- Added the temporary API, frontend allowed-actions, and workflow event-type manifests in their documented single locations with explicit handoff notes for stories 1.20/1.21, 2.14, and 1.23
- Preserved the story boundary: no lookup tables, no new modules, no runner schema implementation, no REST Problem Details mapper, and no transition-service work were added here

### File List

- `_bmad-output/implementation-artifacts/1-4-central-registries-with-drift-tests.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `deliveryline-backend/src/main/java/org/dradgo/domain/DomainException.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ActorType.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ArtifactOperationStatus.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ArtifactStatus.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ArtifactType.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DataClassification.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainRegistry.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/IntegrationSyncStatus.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RegistryParsers.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RegistryValue.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RunnerExecutionStatus.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RunnerSchemaVersion.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java`
- `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json`
- `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`

### Change Log

- 2026-04-27: Implemented central registries, fail-fast persistence-boundary parsers, placeholder contract manifests, and PostgreSQL-backed registry drift tests; verified with targeted and full backend Maven test runs.
