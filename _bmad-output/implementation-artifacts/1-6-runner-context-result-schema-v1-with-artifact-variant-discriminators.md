# Story 1.6: Runner Context/Result Schema v1 with Artifact-Variant Discriminators

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a foundation developer,
I want the `deliveryline-runner-contracts` module to publish `context-bundle.v1.schema.json` and `runner-result.v1.schema.json` with artifact-variant discriminators for spec, implementation-plan, and PR/output artifacts, plus valid and invalid fixtures,
so that Epic 3 can add real Docker runners without reopening the schema to retrofit artifact variants.

## Acceptance Criteria

1. **Given** the `deliveryline-runner-contracts` module, **Then** `src/main/resources/schemas/context-bundle.v1.schema.json` and `src/main/resources/schemas/runner-result.v1.schema.json` exist as JSON Schema Draft 2020-12 documents.
2. **Given** the context-bundle schema, **Then** it requires: `schemaVersion` (const `1`), `workflowRunId`, `runnerExecutionId`, `ticketSummary`, `approvedSpecificationReference` (nullable at spec-stage), `priorFeedbackReferences` (array), `artifactReferences` (array with typed entries), `executionConstraints` (object), and `classification` (enum from `DataClassification` registry).
3. **Given** the runner-result schema, **Then** it requires: `schemaVersion` (const `1`), `workflowRunId`, `runnerExecutionId`, `artifactReferences` (array with `artifactType` discriminator), `normalizedOutput`, `rawOutputReference` (optional, used only when raw retention is enabled), `checksum` (algorithm + hex digest), `classification`, and `failureCategory` (nullable; values from `FailureCategory` registry).
4. **Given** the `artifactType` discriminator in runner-result, **Then** its enum values are exactly `spec`, `implementationPlan`, `prOutput` - and each variant has a matching sub-schema describing its payload shape (spec: markdown content reference; implementationPlan: structured steps array + context refs; prOutput: branch, commitSha, prReference, diffReference).
5. **Given** `RunnerContractValidator` in `deliveryline-runner-contracts/src/main/java`, **When** validating a fixture, **Then** it returns a structured validation result (valid or list of typed errors).
6. **Given** `deliveryline-runner-contracts/src/test/resources/fixtures/valid/`, **Then** at least one valid context-bundle fixture exists and at least one valid runner-result fixture exists per artifact variant (spec, implementationPlan, prOutput) - three variant fixtures minimum.
7. **Given** `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/`, **Then** invalid fixtures exist for each rejection case: missing required field, unknown schema version, bad checksum, duplicate `runnerExecutionId`, stale metadata (for example `runnerExecutionId` referencing a non-existent run), malformed `classification`, partial write (truncated JSON), oversized file (exceeds documented size limit), path-traversal attempt in `artifactReferences`, metadata spoofing (claiming `classification: "shareable-full"` for a payload that contains secret patterns).
8. **Given** the contract test suite, **When** run, **Then** every fixture in `valid/` validates successfully and every fixture in `invalid/` is rejected with the expected error type.
9. **Given** the runner schema version registry (`RunnerSchemaVersion` from story 1.4), **Then** version `1` is registered and marked as the current default for E1 and E2; future versions follow semantic compatibility rules documented in the module README.

## Tasks / Subtasks

- [x] **Task 1: Turn ****`deliveryline-runner-contracts`**** into a real reusable library module** (AC: 1, 5, 8, 9)
  - [x] Change `deliveryline-runner-contracts/pom.xml` from `pom` packaging to a plain `jar` module; this module is the source of truth for runner contracts and must export Java validation code plus classpath schemas/fixtures.
  - [x] Add only the minimal dependencies needed for plain-Java schema validation and fixture tests. Do **not** add Spring Boot starters to this module. Keep it framework-light and backend-independent.
  - [x] If a JSON Schema validator library is needed, use a Draft 2020-12-capable Java library compatible with Java 21 and Jackson 3.x. Recommended default: `com.networknt:json-schema-validator:3.0.2`.
  - [x] Create the source/test roots expected by the architecture: `src/main/java/org/dradgo/runnercontracts/`, `src/main/resources/schemas/`, `src/test/java/org/dradgo/runnercontracts/`, and `src/test/resources/fixtures/{valid,invalid}/`.
  - [x] Keep `deliveryline-runner-contracts` independent of `deliveryline-backend`. Do **not** add a dependency from runner-contracts to backend just to reuse registry enums; that creates the wrong ownership direction for later backend consumption of the contracts module.

- [x] **Task 2: Author the JSON Schema Draft 2020-12 contracts with future-proof structure** (AC: 1, 2, 3, 4, 9)
  - [x] Add `$schema` with the Draft 2020-12 metaschema URI and stable `$id` values for both schema documents. Do not invent a custom draft or omit the metaschema.
  - [x] Model `schemaVersion` as a literal wire value `1` that stays aligned with `RunnerSchemaVersion.V1.value()`, while documenting in the README that future versions may need a more flexible representation than the current backend enum's simple integer string.
  - [x] Use `$defs` plus `oneOf`/`const` for typed artifact variants. JSON Schema does not have OpenAPI's `discriminator` keyword semantics; do not fake this with undocumented custom properties.
  - [x] Keep `approvedSpecificationReference` nullable so later spec-stage bundles from story 2.8 can legally omit an approved spec without forcing a schema version bump.
  - [x] Keep `artifactReferences` reference-oriented rather than embedding full artifact payloads. The architecture says filesystem payloads remain runtime data and contracts should carry stable references plus metadata, not giant inline documents.
  - [x] For runner-result artifacts, ensure the three variants line up exactly with the current central artifact-type vocabulary: `spec`, `implementationPlan`, `prOutput`.
  - [x] Define the checksum object once in `$defs` and reuse it. Require algorithm + hex digest, not a free-form checksum string.

- [x] **Task 3: Implement ****`RunnerContractValidator`**** as schema validation plus semantic guardrails** (AC: 3, 5, 7, 8, 9)
  - [x] Implement `RunnerContractValidator` under `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/` as a plain Java utility/service that loads schema resources from the classpath and validates JSON payloads against them.
  - [x] Return a structured validation result object, not bare booleans and not raw library exceptions. The result should distinguish schema failures from semantic-contract failures with typed error codes the tests can assert against.
  - [x] Treat these cases as **semantic validation** layered on top of JSON Schema, not as pure schema keywords: duplicate `runnerExecutionId`, stale metadata, oversized file, partial/truncated JSON, path traversal, and metadata spoofing. JSON Schema alone cannot express all of them cleanly.
  - [x] Keep the validator pure and reusable: no Spring context, no database access, no filesystem writes, no backend repository lookups. If semantic checks need external facts (for example "known runner execution IDs"), pass them through a small `ValidationContext`/options object instead of hard-coding integration dependencies.
  - [x] For metadata spoofing, implement only the narrow contract this story actually needs: obvious secret-pattern detection sufficient for the invalid fixture set. Do **not** reimplement the full redaction engine from story 1.10 inside runner-contracts.

- [x] **Task 4: Build the valid/invalid fixture corpus with explicit expected outcomes** (AC: 6, 7, 8)
  - [x] Add at least one valid context-bundle fixture and three valid runner-result fixtures, one for each artifact variant: `spec`, `implementationPlan`, and `prOutput`.
  - [x] Add one invalid fixture per rejection case named in AC7. The invalid corpus should be readable and one-case-per-file; do not combine multiple independent failures into one ambiguous fixture unless the story explicitly needs precedence rules.
  - [x] Add a small fixture expectation manifest or equivalent test-owned mapping that says which validator error each invalid fixture is expected to produce. This prevents vague "it failed somehow" tests.
  - [x] For the duplicate `runnerExecutionId` case, use at least two fixtures that intentionally collide so the test harness proves cross-fixture duplicate detection rather than only same-document duplication.
  - [x] For the stale-metadata case, keep it self-contained inside the module tests. Use a fake validation context or stubbed registry of known runner execution IDs rather than reaching into backend persistence.
  - [x] For the oversized-file case, document one explicit byte limit in module code + README + tests. Do not leave "oversized" as an undefined human interpretation.

- [x] **Task 5: Add fast module-local contract tests and README guidance** (AC: 5, 8, 9)
  - [x] Add a JUnit test suite under `deliveryline-runner-contracts/src/test/java/org/dradgo/runnercontracts/` that iterates every file in `fixtures/valid/` and `fixtures/invalid/` and asserts pass/fail plus expected typed error.
  - [x] Add tests that directly inspect the schema files for critical invariants that are too important to trust only through fixtures: Draft 2020-12 metaschema, `schemaVersion = 1`, artifact-type enum parity, nullable `approvedSpecificationReference`, and allowed `classification` values.
  - [x] Ensure `mvn -pl deliveryline-runner-contracts test` runs without Docker, without Spring Boot, and without depending on any other module's application context.
  - [x] Add `deliveryline-runner-contracts/README.md` documenting: module purpose, schema locations, validator entry points, current version `1`, semantic compatibility expectations for future versions, and the distinction between schema validation and semantic validation.
  - [x] Record in the README that backend and runner implementations must consume these schema resources and fixtures rather than maintaining divergent local copies.

## Dev Notes

This story is the first implementation of the runner file contract that Epic 3 depends on. Its job is not only "write two JSON schema files." It sets the source-of-truth shape for context bundles and runner results, defines how artifact variants are discriminated, and establishes a reusable validator/test corpus that later backend code and runner images can trust.

**Current repo state**
- `deliveryline-runner-contracts` currently contains only [pom.xml](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-runner-contracts/pom.xml) and is still `pom` packaging. No Java source root, no schema resources, no tests, and no README exist yet.
- The root Maven build already includes `deliveryline-runner-contracts` as a module in [pom.xml](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/pom.xml), so this story should populate the module rather than create a new one.
- Story 1.4 already established the current authoritative backend registry values for `ArtifactType`, `DataClassification`, `FailureCategory`, and `RunnerSchemaVersion`.
- Story 1.5 finished the workflow transition foundation and deliberately left runner-specific work for this story onward. There is still no real runner broker, Docker runner adapter, or artifact operation service in the codebase yet.
- No `project-context.md` file exists anywhere in the repository, so the story context comes from planning artifacts, prior story files, the live repo structure, and recent git history.

**Critical scope discipline**
- Keep this module plain Java. Do **not** add Spring Boot autoconfiguration, REST endpoints, persistence entities, or Docker orchestration here.
- Do **not** create a dependency cycle by making `deliveryline-runner-contracts` depend on `deliveryline-backend`. The contracts module must remain consumable by backend and runner implementations later.
- Do **not** move runner schemas into backend test resources. Story 1.4 explicitly reserved `deliveryline-runner-contracts` as the long-term owner for schema files and fixtures.
- Do **not** overreach into runner execution behavior. Story 1.6 owns file contracts, schema fixtures, and validator semantics only. Real runner lifecycle work belongs to Epic 3 stories such as 3.1 through 3.4 and the broker work in 1.13.
- Do **not** try to encode every semantic failure as JSON Schema alone. Several AC7 cases require validator logic beyond the schema library.

**Schema design guardrails**
- Use JSON Schema Draft 2020-12 with explicit `$schema` and `$id`.
- Prefer `$defs` and shared reusable object fragments for `artifactReference`, `checksum`, and repeated metadata blocks.
- Model artifact variants with `oneOf` plus `artifactType.const` values. The current artifact-type wire values are exactly `spec`, `implementationPlan`, and `prOutput` from [ArtifactType.java](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/domain/registry/ArtifactType.java).
- Keep `classification` aligned to the current wire values in [DataClassification.java](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/domain/registry/DataClassification.java): `local-only`, `shareable-redacted`, `shareable-full`, `derived-public-safe`.
- Keep `failureCategory` nullable and aligned to the current wire values in [FailureCategory.java](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java). Do not shrink the enum down to only the four values used in story 1.5; the registry now includes additional runner result categories such as `runner_late_result`, `runner_duplicate_result`, and `runner_malformed_output`.
- Keep `approvedSpecificationReference` nullable because story 2.8 explicitly needs spec-investigation bundles that exclude an approved spec reference.

**Semantic validation guardrails**
- "Duplicate `runnerExecutionId`" is a corpus-level rule or context-level rule, not a single-document schema rule.
- "Stale metadata" requires a validator context with known valid IDs or an equivalent stubbed lookup in tests; it is not a JSON syntax error.
- "Oversized file" should be validated on the raw payload length before or alongside parsing, with one explicit byte-limit constant that tests can assert.
- "Partial write (truncated JSON)" should surface as a typed parse/format error rather than a generic stack trace.
- "Path traversal" should reject suspicious path-like values in artifact references such as `../`, `..`, absolute drive roots, or absolute Unix roots when the contract expects relative references or logical IDs.
- "Metadata spoofing" should reject obviously contradictory cases in the invalid fixture set without turning this story into a full secret-scanning product. Keep the rule narrow and fixture-driven.

**Exact file targets**
- `deliveryline-runner-contracts/pom.xml`
- `deliveryline-runner-contracts/README.md`
- `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/RunnerContractValidator.java`
- `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/ValidationResult.java` or equivalent result/error types
- `deliveryline-runner-contracts/src/main/resources/schemas/context-bundle.v1.schema.json`
- `deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json`
- `deliveryline-runner-contracts/src/test/java/org/dradgo/runnercontracts/RunnerContractValidatorTest.java`
- `deliveryline-runner-contracts/src/test/resources/fixtures/valid/`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/`
- `deliveryline-runner-contracts/src/test/resources/fixtures/fixture-expectations.json` or an equivalent single authoritative test mapping for invalid-fixture expected errors

**Testing expectations**
- Keep tests module-local and fast. This story should not need Docker, Testcontainers, or Spring Boot.
- Add one direct validator test per semantic rejection family plus one corpus-driven test that sweeps every file in `fixtures/valid/` and `fixtures/invalid/`.
- Assert specific error types for invalid fixtures. "Validation failed" is not enough.
- Assert the schema files themselves are loadable and use the expected Draft 2020-12 metaschema.
- Add at least one regression test proving nullable `approvedSpecificationReference` is accepted for a spec-stage context bundle.
- Add at least one regression test proving each artifact variant sub-schema accepts its intended payload and rejects the wrong payload shape under the wrong `artifactType`.

**Previous story intelligence**
- Story 1.4 explicitly said runner context/result schema files and fixtures do **not** belong in backend test resources and should live in `deliveryline-runner-contracts` once this story populates the module.
- Story 1.4 also deferred a known design caveat: [RunnerSchemaVersion.java](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/domain/registry/RunnerSchemaVersion.java) currently exposes `"1"` from an integer-backed enum. Do not "fix" that backend design in this story; document compatibility rules in the contracts-module README instead.
- Story 1.5 reinforced the pattern for foundation slices: keep the scope narrow, contract-first, and thoroughly tested. Do not smuggle in runner orchestration or backend application logic under the excuse of validation.
- Story 2.8 already points forward to this story by requiring spec-stage context bundles to validate against the runner-contracts v1 documents before write. That means the schema must already accommodate stage-specific nullable references without another version bump.

**Git intelligence summary**
- Recent commits show the repo is still in foundation-contract mode, not feature mode:
  - `DL - 3 DL - 4 DL - 5 Add foundational registry definitions, persistence layers, and placeholder contracts for workflow states, event types, and public ID prefixes`
  - `DL-2 initial database schema version`
  - `DL-1 initial docker compose version`
  - `DL-0 fixes after initial review`
- Follow that same pattern here: authoritative contract assets plus focused tests, not speculative backend or runner implementation.

**Current official-docs specifics to follow**
- JSON Schema's current published specification line is Draft 2020-12. Use that exact draft and metaschema; do not fall back to Draft 7 or an older Java validator with incomplete 2020-12 support.
- The NetworkNT JSON Schema Validator project documents support for Draft 2020-12 and lists `3.0.2` as the Java 17+/Jackson 3.x line. That matches this repository's Java 21 / Spring Boot 4 era better than older validator lines.
- Keep the contracts module plain Java even though the repo root uses Spring Boot 4.0.6. Nothing in this story needs Spring container features.

### Project Structure Notes

- The architecture document uses shorthand names like `runner-contracts/`, but the live module path is `deliveryline-runner-contracts/`. Use the live path in code and docs.
- The architecture's planned source layout for this module is already explicit: schemas under `src/main/resources/schemas`, validation code under `src/main/java/org/dradgo/runnercontracts/`, and fixtures under `src/test/resources/fixtures`.
- Backend contract tests are expected to consume these runner-contract fixtures later. Do not create duplicate copies under `deliveryline-backend`.
- This module is intended to become the contract authority consumed by backend and runner implementations. Keep APIs and resource paths stable and boring.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.6 acceptance criteria and artifact-variant requirements]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 2.8 context-bundle usage and nullable approved-spec reference]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - runner contracts as source of truth, module layout, quality gates, and RunnerBroker validation ownership]
- [Source: `_bmad-output/implementation-artifacts/1-4-central-registries-with-drift-tests.md` - runner-contract ownership handoff, registry values, and RunnerSchemaVersion design caveat]
- [Source: `_bmad-output/implementation-artifacts/1-5-workflow-state-transition-table-and-workflow-transition-service.md` - foundation-slice implementation pattern and no real runner code yet]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` - deferred note that RunnerSchemaVersion design should be revisited when story 1.6 introduces real schema versioning]
- [pom.xml](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/pom.xml) - root module list and Java/Spring baseline
- [pom.xml](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-runner-contracts/pom.xml) - current module is still a POM-only stub
- [ArtifactType.java](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/domain/registry/ArtifactType.java) - canonical artifact type wire values
- [DataClassification.java](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/domain/registry/DataClassification.java) - canonical classification wire values
- [FailureCategory.java](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java) - canonical runner failure-category wire values
- [RunnerSchemaVersion.java](.//c:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/domain/registry/RunnerSchemaVersion.java) - current version `1` registry
- [Source: https://json-schema.org/draft/2020-12 - JSON Schema Draft 2020-12 specification landing page]
- [Source: https://json-schema.org/specification - JSON Schema specification index showing 2020-12 as the current version]
- [Source: https://github.com/networknt/json-schema-validator - official validator README documenting Draft 2020-12 support and the Java 17+/Jackson 3.x `3.0.2` line]

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Auto-selected next backlog story from `_bmad-output/implementation-artifacts/sprint-status.yaml`: `1-6-runner-context-result-schema-v1-with-artifact-variant-discriminators`
- Loaded project config from `_bmad/bmm/config.yaml` and planning artifacts from `_bmad-output/planning-artifacts/`
- Reviewed Epic 1 story 1.6, architecture sections covering runner-contract ownership and module layout, the implementation follow-on in story 2.8, and prior story files `1-4` and `1-5`
- Inspected live repo structure and confirmed `deliveryline-runner-contracts` is currently a POM-only stub
- Checked current registry values for `ArtifactType`, `DataClassification`, `FailureCategory`, and `RunnerSchemaVersion`
- Checked official JSON Schema Draft 2020-12 and Java validator guidance to keep the module on a current contract/validator line

### Completion Notes List

- Implemented `deliveryline-runner-contracts` as a plain Java JAR with Draft 2020-12 schemas, typed validation result objects, and a reusable `RunnerContractValidator`.
- Added full valid/invalid fixture corpus coverage with an expectation manifest, semantic validation contexts, cross-fixture duplicate detection, and an explicit `2048` byte oversized-payload limit.
- Corrected the runner-result artifact variant schema to use `unevaluatedProperties: false` with `allOf`, so `spec`, `implementationPlan`, and `prOutput` payloads validate cleanly under Draft 2020-12 semantics.
- Verified the module locally with `mvn -pl deliveryline-runner-contracts test` on 2026-04-28: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`.

### File List

- `deliveryline-runner-contracts/pom.xml`
- `deliveryline-runner-contracts/README.md`
- `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/RunnerContractValidator.java`
- `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/ValidationContext.java`
- `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/ValidationError.java`
- `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/ValidationErrorCode.java`
- `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/ValidationResult.java`
- `deliveryline-runner-contracts/src/main/resources/schemas/context-bundle.v1.schema.json`
- `deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json`
- `deliveryline-runner-contracts/src/test/java/org/dradgo/runnercontracts/RunnerContractValidatorTest.java`
- `deliveryline-runner-contracts/src/test/resources/fixtures/fixture-expectations.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/valid/runner-result.v1.spec.valid.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/valid/runner-result.v1.implementation-plan.valid.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/valid/runner-result.v1.pr-output.valid.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/context-bundle.v1.invalid-missing-required-field.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-unknown-schema-version.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-bad-checksum.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-duplicate-runner-execution-a.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-duplicate-runner-execution-b.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-stale-metadata.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-malformed-classification.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-partial-write.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-oversized-file.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-path-traversal.json`
- `deliveryline-runner-contracts/src/test/resources/fixtures/invalid/runner-result.v1.invalid-metadata-spoofing.json`

### Change Log

- 2026-04-28: Created story 1.6 with implementation guardrails for `deliveryline-runner-contracts` and promoted sprint status to `ready-for-dev`
- 2026-04-28: Implemented runner-contract schemas, validator/result types, fixture corpus, README guidance, and module-local contract tests; story moved to `review`
