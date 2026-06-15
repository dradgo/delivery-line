# Story 1.10: Redaction Classification Policy and Adversarial Secret Fixture Set

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a foundation developer,
I want `RedactionPolicyService` and `DataClassificationService` with an adversarial fixture set exhausting known credential formats,
so that secrets, tokens, and unnecessary local-only data cannot leak into logs, exports, artifacts, context bundles, or runner outputs and redaction is enforced both on capture and on export.

## Acceptance Criteria

1. **Given** the `application.security` package, **Then** `RedactionPolicyService` (applies redaction rules) and `DataClassificationService` (assigns/queries classification labels) exist with interfaces matching the architecture decision.
2. **Given** `DataClassification` registry values (`local-only`, `shareable-redacted`, `shareable-full`, `derived-public-safe`), **Then** each can be assigned to an artifact, context bundle, log entry, export, or runner output and is persisted alongside the data (via `classification` column on relevant tables or metadata sidecar).
3. **Given** a string or structured payload containing known credential patterns, **When** `RedactionPolicyService.redact(...)` runs, **Then** secrets are replaced with classification-safe placeholders (for example `[REDACTED_LINEAR_API_KEY]`, `[REDACTED_GITHUB_TOKEN]`, `[REDACTED_SSH_PRIVATE_KEY]`) and the output classification is downgraded to `shareable-redacted` if any redaction occurred.
4. **Given** the adversarial fixture set at `deliveryline-backend/src/test/resources/redaction-fixtures/`, **Then** fixtures exist covering: Linear API keys with known prefix, GitHub personal-access tokens (`ghp_*`, `github_pat_*`), SSH public key blocks (`ssh-rsa`, `ssh-ed25519`), SSH private key blocks (`-----BEGIN OPENSSH PRIVATE KEY-----` / `-----BEGIN RSA PRIVATE KEY-----`), `.env`-style `KEY=VALUE` with secret patterns, YAML/JSON documents with embedded secret fields, HTTP `Authorization: Bearer` + `Authorization: Basic` headers, secrets in URL query params (`token=`, `apikey=`, `access_token=`), absolute local paths revealing `C:\Users\{name}` or `/Users/{name}` or `/home/{name}`, and process-environment leakage (full env block in a stack trace).
5. **Given** each adversarial fixture, **When** passed through `RedactionPolicyService`, **Then** a test asserts no raw secret token appears in the output and the placeholder indicates the matched category.
6. **Given** a property-based generator producing unknown-shape `KEY=VALUE` pairs with high-entropy values, **When** classified, **Then** suspicious entropy plus key-name heuristics (keys containing `secret`, `token`, `key`, `password`, `credential`) result in conservative redaction.
7. **Given** double-gate redaction, **Then** redaction runs at two points: (a) on capture into durable storage (logs, artifacts, context bundles) and (b) on export/sharing; a test proves that even if a raw value slipped past capture-time redaction, export-time redaction catches it.
8. **Given** an export attempt for data classified as `local-only`, **When** invoked, **Then** the export is rejected with `EXPORT_CLASSIFICATION_VIOLATION` unless the data is first downgraded via a `RedactionPolicyService`-approved path.
9. **Given** the fixture library is documented as a living artifact, **Then** a `README.md` next to the fixtures explains how to add new credential formats as they are discovered, and CI fails loudly on fixture additions that are not wired into the redaction contract test.
10. **Given** a metadata-spoofing fixture (payload claims `classification: "shareable-full"` but contains secret patterns), **When** processed, **Then** the classification service re-validates against actual payload content and downgrades; claimed classification cannot override detected secrets.

## Tasks / Subtasks

- [x] **Task 1: Establish application-owned redaction and classification seams** (AC: 1, 2, 3, 10)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionPolicyService.java` as the single application-owned redaction entry point. Do not put regexes, masking rules, or secret-pattern strings in adapters or any package outside `application.security`.
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/application/security/DataClassificationService.java` as the single owner of effective classification decisions.
  - [x] Add small immutable result types in the same package (for example `RedactionResult`, `ClassificationAssessment`, or equivalent) so callers receive sanitized payload, detected categories, effective classification, and whether redaction occurred through one typed result instead of parallel booleans and strings.
  - [x] Support the payload shapes the current backend can realistically produce now: raw `String` plus structured payloads already common in the codebase such as `Map<String, Object>` or `JsonNode`. Do not require callers to hand-flatten rich payloads before invoking the service.
  - [x] Reuse the existing `DataClassification` registry and `DomainRegistry.dataClassifications()` rather than introducing local string constants or a duplicate enum. Treat the `DataClassification` enum itself as fenced — adapters may pass classification *strings* through DTOs, but the enum reference belongs to `application.security` and `domain.registry` only (this is what the Story 1.11 ArchUnit test will assert).
  - [x] Treat claimed classification as advisory only. Effective classification may stay the same or downgrade after inspection; it must never upgrade because the payload says it is safe.
  - [x] When a payload claims a classification value that is not in the `DataClassification` registry (unknown / non-registry / null after explicit-claim), fail explicitly via the existing registry-fail-fast convention — do not silently default to `local-only` or `shareable-redacted`. Match the architecture rule that unknown registry values fail explicitly or route to reconciliation; do not silently map to defaults.

- [x] **Task 2: Implement a governed redaction catalog with deterministic placeholders** (AC: 3, 4, 5, 10)
  - [x] Centralize the pattern catalog inside `application.security` only. Cover the exact categories named in the epic: Linear keys, GitHub tokens, SSH public/private keys, `.env`-style secrets, JSON/YAML secret fields, HTTP Authorization headers, query-string secrets, Windows/macOS/Linux local-user paths, and process-environment dumps. Keep `java.util.regex.Pattern` imports inside this package — adapters and other application packages must call the service rather than carry their own regexes.
  - [x] Use stable placeholders per category such as `[REDACTED_LINEAR_API_KEY]`, `[REDACTED_GITHUB_TOKEN]`, `[REDACTED_SSH_PRIVATE_KEY]`, `[REDACTED_AUTHORIZATION_HEADER]`, and `[REDACTED_LOCAL_PATH]`. Do not use generic `***` masking that loses the matched-category signal.
  - [x] Preserve as much non-sensitive context as possible around the placeholder so logs and artifacts stay inspectable. Redact values, not entire documents, unless the entire block is inherently unsafe (for example a private-key block or full process env dump).
  - [x] Keep behavior deterministic. The same input must produce byte-identical sanitized output and the same effective classification on every run; no probabilistic masking, no timestamp-dependent output, no host-dependent ordering of categories in the result.
  - [x] Metadata spoofing must lose to content inspection. If a payload claims `shareable-full` but matches secret patterns, the result must downgrade to `shareable-redacted` or `local-only` according to the policy.

- [x] **Task 3: Implement conservative classification policy and double-gate export protection** (AC: 2, 6, 7, 8, 10)
  - [x] Keep the existing `artifacts.classification` column and `PersistedRegistryValues.artifactClassification(...)` path authoritative for artifact rows. Do not add a Flyway migration just to create hypothetical classification columns for tables that do not exist yet.
  - [x] For surfaces that do not yet have first-class persistence tables in this repo (`logs`, future `context bundles`, future `exports`, and future runner-output metadata), carry classification through the new application-level result objects and fixture-driven tests instead of inventing placeholder tables or schema side quests.
  - [x] Add `EXPORT_CLASSIFICATION_VIOLATION` to `DomainErrorCode` now and wire it everywhere the foundation drift gates demand:
    - `ProblemDetailsCatalog`: map to HTTP `409 Conflict`, retryable `false`.
    - `WorkflowCliExitStatusExceptionMapper`: map to the `2xx` conflict band (`201`) so later CLI export commands do not silently fall into the generic `101` default.
    - `docs/cli/README.md` and `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`: update the documented and drift-tested manifests at the same time.
  - [x] Provide an explicit export-time redaction/classification path in the service boundary, even though `RunExportService` does not exist yet. Story 1.10 owns the policy; story 5.x will own the export flow that calls it.
  - [x] Prove the double gate with a test that simulates a capture-time miss and then re-runs the sanitized payload through export-time redaction. The second pass must catch the leaked secret instead of trusting earlier classification.
  - [x] Satisfy the "property-based generator" requirement inside the current test stack first. Prefer a deterministic randomized generator helper plus repeated/dynamic JUnit tests over adding a new property-testing dependency unless the current stack proves insufficient.

- [x] **Task 4: Build the adversarial fixture library as a governed contract surface** (AC: 4, 5, 9, 10)
  - [x] Create `deliveryline-backend/src/test/resources/redaction-fixtures/README.md` describing fixture categories, naming rules, placeholder expectations, and the process for adding new secret formats discovered in the field.
  - [x] Organize fixtures by category and add a manifest file (JSON or YAML) mapping each fixture to its expected redaction category, required placeholder, and minimum resulting classification.
  - [x] Include dedicated fixtures for Windows and Unix local paths, URL query-parameter leaks, HTTP header leaks, `.env` blocks, YAML/JSON embedded secrets, SSH key blocks, process-environment dumps, and metadata spoofing.
  - [x] Make the contract test enumerate fixture files from disk and cross-check them against the manifest. A new fixture without a manifest entry must fail CI immediately rather than being silently ignored.
  - [x] Keep the fixture library in `deliveryline-backend/src/test/resources/redaction-fixtures/`, not in `deliveryline-runner-contracts`, because this story protects backend/local surfaces beyond runner-schema JSON alone.

- [x] **Task 5: Lock the behavior down with application and contract tests** (AC: 5, 6, 7, 8, 9, 10)
  - [x] Add `deliveryline-backend/src/test/java/org/dradgo/application/security/RedactionPolicyServiceContractTest.java` to drive the adversarial fixture corpus and assert placeholder/category correctness.
  - [x] Add `deliveryline-backend/src/test/java/org/dradgo/application/security/DataClassificationServiceContractTest.java` (or a single combined contract test if one driver keeps the suite clearer) to assert claimed-vs-detected classification, local-only export rejection, and metadata-spoofing downgrades.
  - [x] Extend `deliveryline-backend/src/test/java/org/dradgo/contract/ProblemDetailsContractTest.java` for `EXPORT_CLASSIFICATION_VIOLATION` REST metadata and `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java` for registry/placeholder drift.
  - [x] Extend `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliExitStatusExceptionMapperTest.java` and `docs/cli/README.md` if the new error code is introduced now, because the mapper currently defaults unmapped codes into the wrong band.
  - [x] Add tests proving: raw tokens never survive redaction, the expected placeholder does appear, suspicious unknown-shape `KEY=VALUE` pairs downgrade conservatively, export of `local-only` content fails closed, and double-gate redaction catches a capture-time miss on the second pass.

## Dev Notes

This story is the foundation slice for security redaction and classification policy, not the slice that fully wires every downstream consumer. Its job is to make later stories consume one trusted policy instead of inventing five subtly different ones.

**Current repo state**

- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DataClassification.java` already defines the four governed values: `local-only`, `shareable-redacted`, `shareable-full`, and `derived-public-safe`.
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java` currently exposes only one classification parsing boundary: `artifacts.classification`.
- `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql` already persists `artifacts.classification text not null`; there are no context-bundle or export tables yet.
- There is no `deliveryline-backend/src/main/java/org/dradgo/application/security/` package today.
- There is no `deliveryline-backend/src/test/resources/redaction-fixtures/` directory today.
- `ProblemDetailsCatalog` self-asserts that every `DomainErrorCode` is mapped. Adding a new error code without updating the catalog will fail Spring context startup in tests.
- `WorkflowCliExitStatusExceptionMapper` currently defaults unknown domain errors to `101`, so introducing `EXPORT_CLASSIFICATION_VIOLATION` without an explicit mapping will silently misclassify it.

**Scope discipline**

- Do not build `RunExportService` in this story. Story 1.10 owns the policy and the error semantics; the export workflow belongs to Epic 5.
- Do not build the context-bundle flow in this story. Story 1.13 and later stories will call the policy once it exists.
- Do not build the structured logging appender in this story. Story 1.19 owns the logging integration and should call `RedactionPolicyService` rather than re-implement it.
- Do not add a Flyway migration just to persist classification on surfaces that do not have real persistence tables yet. Use typed metadata/result objects now and let the owning stories decide their storage shape later.
- Do not introduce a general-purpose secret-scanning library for this foundation slice unless the story implementation proves the current stack cannot satisfy the acceptance criteria. The architecture assigns ownership to `application.security`, not to a third-party platform dependency.
- The `demo` Spring profile must NOT bypass redaction. Foundation profile semantics (AR27) explicitly forbid demo-mode escape hatches around workflow rules, approvals, validation, redaction, or recovery — design the service so it is unconditionally on the call path.

**Implementation guardrails**

- `RedactionPolicyService` owns redaction policy orchestration. Adapters may call it but must not (a) define independent redaction rules, (b) carry their own credential regexes, or (c) **log sensitive payloads before redaction** — adapter call ordering must always redact first, log second.
- ArchUnit fence (story 1.11 will enforce): `java.util.regex.Pattern`, secret-pattern string literals, and `DataClassification` enum references all stay inside `application.security` (and `domain.registry` for the enum itself). Adapters that need a classification value pass it through DTOs as the registry string, not the enum.
- Keep the policy result typed. Returning only a redacted string loses the effective classification, matched-category set, and "was redacted" signal that later stories need.
- Preserve inspectability. Keep the `Authorization:` header name or the URL shape while redacting the sensitive value. For local path leaks, hide usernames and machine-specific roots but preserve enough structure to explain what kind of path leaked. For full private-key blocks or full environment dumps it is acceptable to collapse the entire unsafe block to one stable placeholder if field-level preservation would still leak too much.
- Treat `shareable-full` as rare. If the payload contains any known secret class, the safest default is a downgrade to `shareable-redacted`; if a safe redacted form cannot be produced, fail closed as `local-only`.
- The "unknown-shape secret" heuristic should be conservative, deterministic, and explainable. Prefer a small set of explicit signals (high entropy plus suspicious key names) over opaque scoring logic.
- Adversarial fixtures live in `deliveryline-backend/src/test/resources/redaction-fixtures/` (backend-local surfaces); the runner-schema fixture set in `deliveryline-runner-contracts` is a different concern. The CI tier name expected by AR28 for the contract suite is `redaction` — keep test class/package names consistent so future CI configuration stays trivial. AR29 also requires structured-logging integration to call `RedactionPolicyService` rather than re-implement it; story 1.19 will own the SLF4J wiring.

**Consumer integration shapes**

The downstream consumers each call `redact(...)` and `classify(...)` with a different shape. Designing the public surface for *all* of these now (rather than discovering breakage when each consumer story lands) avoids API breaks:

- **Story 1.13 / 3.10 — context bundle generation:** structured payload (`Map<String, Object>` / `JsonNode`) plus file-path metadata; output is a redacted bundle persisted under `{DELIVERYLINE_HOME}`.
- **Story 1.16 — \****`doctor`**\*\* command:** raw `String` diagnostic blocks (system info, env subsets, stack traces); output is the same string with placeholders.
- **Story 1.19 — structured logging appender:** raw `String` log message plus key-value pairs from MDC / structured fields; appender layer calls the service before forwarding to SLF4J output.
- **Story 3.5 / 3.6 — runner workspace + log capture:** raw `String` chunks streamed from stdout/stderr plus filename/path metadata; output is redacted file content with classification recorded alongside (`raw_output_classification` column at story 3.6).
- **Story 4.x — Compare Mode \****`RevisionDeltaService`**\*\*:** free-text `ChangeBlock` strings; ArchUnit pins `RedactionPolicyService` (plus `ArtifactService`) as the only allowed collaborators.
- **Story 5.1 / 5.3 — \****`RunExportService`**\*\* composition:** every text field on a structured run report passes through redaction; story 5.3 cross-checks the fixture catalog with this story's manifest via a CI lint.

**Future-story handoffs**

- Story 1.11 will run an ArchUnit test asserting no adapter implements its own redaction (detected via credential regex patterns or `DataClassification` enum references outside the approved package). Code shape above is what makes that test pass.
- Story 1.13 redacts context bundles before persistence; 1.16 redacts doctor output; 1.19 routes all log appends through a redacting layout/appender that calls this service; 2.8 redacts spec-stage bundles.
- Story 1.23 (foundation-gate verification) ratchets a CI contract that enumerates every adversarial fixture through `RedactionPolicyService` and asserts no raw secret survives — the reusable fixture-loading test-support class from Task 4 is what 1.23 will call.
- Story 3.6 AC10 extends the fixture corpus with runner-CLI auth-leak patterns (Codex CLI, Claude CLI, generic `Authorization: Bearer ...`); the manifest schema's `origin` and `scope` fields are how 3.6 plugs in without breaking 1.10's tests.
- Story 5.3 AC8 runs a CI lint that synchronizes the fixture catalog between 1.10 and 5.3 — keep the manifest machine-readable.

**Latest technical guidance**

- Stay on the live repo stack: Spring Boot `4.0.6`, Java `21`, Spring Shell `4.0.2` (`pom.xml`). This story does not justify a framework upgrade.
- For the AC6 randomized-fixture requirement, use JUnit's existing `@RepeatedTest` + `@MethodSource` (parameterized), `@TestFactory` (dynamic), or a small JUnit `@Extension` plus a seeded `java.util.random.RandomGenerator`. Capture the seed in failure messages and reuse it in a regression test when a property-test fails. Do not add a new property-testing dependency unless the existing primitives prove insufficient.
- Use `java.util.regex.Pattern` with explicit `Pattern.compile(...)` flags; cache compiled patterns at class load (`private static final Pattern`) so hot-path classification stays O(input length) per category and avoids per-call regex compile cost.
- Keep the redaction implementation in plain Java and current Spring application code. There is no evidence in this repo that a separate security-DLP framework is warranted for the foundation slice.

**Testing expectations**

- The primary regression gate is the adversarial fixture corpus. Every fixture should assert both "secret removed" and "correct placeholder emitted".
- Add a dedicated metadata-spoofing test where the payload advertises `classification: shareable-full` while containing a secret. Effective classification must downgrade based on content, not trust the metadata.
- Add a double-gate test where capture-time misses a secret-like value but export-time catches it. This is the core proof that export-time redaction is not just a pass-through.
- Keep the tests deterministic. If random input is generated for AC6, use an explicit seed and make failures reproducible.

**Previous story intelligence**

- Story 1.9 tightened the repo's pattern of "new governed behavior must update registry drift tests, Problem Details metadata, and CLI exit-band mapping together." Follow the same discipline here if `EXPORT_CLASSIFICATION_VIOLATION` is introduced now.
- Story 1.9 also reinforced a service-boundary style the repo is already adopting: one application-owned seam per cross-cutting concern (`IdempotencyService`). Story 1.10 should do the same for redaction and classification rather than scattering helpers.
- The recent backend stories have been deliberately contract-heavy. This story should continue that pattern with fixtures and drift gates rather than implementation-only happy paths.

**Git intelligence summary**

- The last five commits show a sequence of tightening foundation contracts rather than widening surface area: runner contracts, shared command models, Problem Details, and idempotency. Story 1.10 should continue that direction by adding a strict policy seam and a hostile fixture corpus, not a broad runtime feature expansion.
- The current repo already contains untracked user-context files and in-progress local artifacts. Do not rely on a clean git state for this story's implementation; scope work to the specific backend files the story names.

### Project Structure Notes

- Main-code ownership belongs under `deliveryline-backend/src/main/java/org/dradgo/application/security/`.
- Contract tests should follow the repo's current conventions under `deliveryline-backend/src/test/java/org/dradgo/application/security/` and `deliveryline-backend/src/test/java/org/dradgo/contract/`.
- The fixture corpus belongs under `deliveryline-backend/src/test/resources/redaction-fixtures/` with a `README.md` and a machine-readable manifest checked by tests.
- No `project-context.md` file exists in this repo today, so this story is grounded in live planning artifacts, current code, and previous-story learnings only.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` — Story 1.10 ACs (lines 582–599), AR10 living-list rule (line 186), AR27/AR28/AR29 (lines 212–214), foundation-gate ordering (lines 359–367), downstream consumers (1.11 line 613, 1.13 line 650, 1.16 line 712, 1.19 line 767, 1.23 line 840, 2.8 line 1038, 2.24 line 1359)]
- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md` — runner-CLI fixture extension (3.6 AC10 line 121), runner workspace + log redaction (3.5/3.6/3.10), GitHub/Linear egress redaction (3.14, 3.16)]
- [Source: `_bmad-output/planning-artifacts/epic-04-recovery.md` — Compare Mode `RevisionDeltaService` collaborator fence (line 409)]
- [Source: `_bmad-output/planning-artifacts/epic-05-export.md` — `RunExportService` composition + double-gate (5.1 lines 32–45), CI fixture-catalog lint between 1.10 and 5.3 (5.3 AC8 line 83)]
- [Source: `_bmad-output/planning-artifacts/prd.md` — NFR8 through NFR14: security, redaction, and share-boundary requirements]
- [Source: `_bmad-output/planning-artifacts/architecture.md` — `RedactionPolicyService` ownership + adapter-boundary rule (L1167, L1203, L1215), four-value `DataClassification` taxonomy (L85), capture+export double-gate (L342, L358), export determinism contract (L577), fixture and contract-test paths (L1047, L1053), residual-risk visibility (L375), unknown-registry fail-fast (L797, L849)]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - service boundaries]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - project structure refinements and fixture ownership]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - readiness caveat naming initial redaction/data-classification policy as a foundation contract]
- [Source: `_bmad-output/implementation-artifacts/1-9-idempotency-service.md` - prior-story drift-gate and service-boundary patterns]
- [Source: `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql` - current `artifacts.classification` persistence shape]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DataClassification.java` - governed classification values]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java` - current persistence parsing boundary for classification]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` - current governed error-code set]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` - Problem Details catalog completeness requirement]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCliExitStatusExceptionMapper.java` - explicit CLI exit-band mapping requirement]
- [Source: `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java` - registry drift expectations]
- [Source: `docs/cli/README.md` - documented exit-band expectations]
- [Source: https://docs.spring.io/spring-boot/system-requirements.html - Spring Boot 4.0.6 current official docs]
- [Source: https://docs.junit.org/current/user-guide/ - JUnit current guide for repeated/parameterized/dynamic tests]

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Auto-selected the next backlog story from `_bmad-output/implementation-artifacts/sprint-status.yaml`: `1-10-redaction-classification-policy-and-adversarial-secret-fixture-set`
- Reviewed Story 1.10 and AR10 in `_bmad-output/planning-artifacts/epics.md`
- Reviewed PRD security/redaction NFRs and architecture sections covering service boundaries, secret redaction, fixture ownership, and project structure
- Reviewed prior story `1-9-idempotency-service.md` for the repo's current drift-gate and application-service patterns
- Inspected the live backend seams: `DataClassification`, `PersistedRegistryValues`, `DomainErrorCode`, `ProblemDetailsCatalog`, `WorkflowCliExitStatusExceptionMapper`, `RegistryContractTest`, and the V1 schema
- Confirmed there is no existing `application.security` package and no `redaction-fixtures/` test-resource directory in the current repo
- Checked current official docs for the repo's pinned Spring Boot line and the available JUnit test mechanisms before prescribing implementation guidance

### Completion Notes List

- 2026-05-02: Created comprehensive story context for story 1.10 and marked it `ready-for-dev`
- 2026-05-02: Pinned the story to an application-owned `application.security` seam with deterministic placeholders and classification downgrades
- 2026-05-02: Explicitly constrained scope to shared policy, manifests, and contract tests; later stories own runtime consumers such as exports, context bundles, and structured logging
- 2026-05-02: Pinned `EXPORT_CLASSIFICATION_VIOLATION` to REST `409 Conflict` and CLI conflict-band `201` if introduced in this slice so drift gates remain aligned
- 2026-05-02: Quality validation pass against `epics.md`, all five epic files, and `architecture.md` — added explicit ArchUnit-fence rules for story 1.11 readiness, the adapter "redact before logging" rule (architecture L1215), unknown-registry-value fail-fast (L797/L849), forward-compatible fixture manifest schema (`origin`/`scope`/`addedInStory` for stories 3.6 and 5.3 extension), reusable fixture-loader test-support class for story 1.23 verification, AR10 "un-fixtured secret = product defect" framing in the README requirements, residual-risk documentation expectation (L375), AR27/AR28/AR29 capture (demo-bypass ban, CI tier name, structured-logging integration), consumer-shape callouts for 1.13/1.16/1.19/3.5/3.6/4.x/5.1, determinism + seed-logging test, and concrete JUnit/regex hot-path guidance.

- 2026-05-02: Implemented `application.security` with `RedactionPolicyService`, `DataClassificationService`, typed result objects, and a shared analyzer covering deterministic placeholders, metadata spoofing, local-path redaction, process environment dumps, and export-time classification enforcement.
- 2026-05-02: Added the governed fixture corpus and manifest under `deliveryline-backend/src/test/resources/redaction-fixtures/` plus contract coverage for fixture enumeration, placeholder correctness, conservative high-entropy secret heuristics, and structured-payload redaction.
- 2026-05-02: Wired `EXPORT_CLASSIFICATION_VIOLATION` through `DomainErrorCode`, `ProblemDetailsCatalog`, CLI exit mapping, the OpenAPI placeholder manifest, and CLI docs; verified targeted security contracts and the full `mvn -pl deliveryline-backend test` suite outside the sandbox with 120 passing tests.

### File List

- `_bmad-output/implementation-artifacts/1-10-redaction-classification-policy-and-adversarial-secret-fixture-set.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionPolicyService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/security/DataClassificationService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/security/SensitivePayloadAnalyzer.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/security/ClassificationAssessment.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionCategory.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCliExitStatusExceptionMapper.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/security/RedactionPolicyServiceContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/security/DataClassificationServiceContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/ProblemDetailsContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliExitStatusExceptionMapperTest.java`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `deliveryline-backend/src/test/resources/redaction-fixtures/`
- `docs/cli/README.md`

### Change Log

- 2026-05-02: Initial story context created from sprint backlog, planning artifacts, current backend seams, previous-story learnings, and official framework documentation
- 2026-05-02: Story implemented, verified, and advanced to `done`
