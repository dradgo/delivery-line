# Story 1.8: Problem Details Mapper with Stable Domain Error Codes

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a foundation developer,
I want a `ProblemDetailsMapper` that converts application-level `DomainException`s into RFC 7807 `application/problem+json` responses for REST and concise exit-coded output for CLI,
so that transport-specific error representations preserve stable semantics and clients can rely on machine-readable error metadata (`code`, `status`, `retryable`, `details`) rather than human-readable message text.

## Acceptance Criteria

1. **Given** a `DomainException` thrown from an application service during REST request handling, **When** the mapper runs, **Then** it emits an `application/problem+json` response with required fields: `type` (URL shaped as `https://deliveryline.local/problems/{code}`), `title`, `status`, `detail`, `instance` (request path), `code` (from `DomainErrorCode` registry), and `retryable` (boolean).
2. **Given** Bean Validation failures, **When** the mapper handles them, **Then** the response includes a `details` array with `field`, `rejectedValue` (redacted if sensitive), and `constraint` per invalid field.
3. **Given** a `DomainException` carrying `APPROVAL_VERSION_MISMATCH`, **When** mapped, **Then** the response includes `details.expectedArtifactVersion` and `details.currentArtifactVersion` so the UI (Epic 2) can refresh intelligently.
4. **Given** contract tests, **Then** REST error responses conform to RFC 7807 schema and assertions check `code`, `status`, and `details` - never the human-readable `detail` or `title` text (which may change).
5. **Given** the CLI adapter, **When** a `DomainException` propagates out of a Shell command, **Then** the CLI prints a one-line error: `[{code}] {detail}` and sets a non-zero exit code mapped from the error category (`1xx` for client-like errors, `2xx` for concurrency/idempotency conflicts, `3xx` for runner/integration failures, `4xx` for infrastructure failures - exact mapping documented in the CLI README).
6. **Given** the central registry drift test (story 1.4), **When** a new `DomainErrorCode` is added without a corresponding mapper case or type-URL, **Then** the drift test fails.
7. **Given** unknown exceptions (not `DomainException`), **When** caught by the REST error handler, **Then** a generic `500` Problem Details response with code `INTERNAL_ERROR` is returned - no stack trace or internal path leaks into the response body (verified by a redaction-aware contract test).

## Tasks / Subtasks

- [x] **Task 1: Add REST Problem Details mapping as the single transport error adapter** (AC: 1, 3, 7)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java` as the single REST error-mapping entry point. Prefer `@RestControllerAdvice` plus `ResponseEntityExceptionHandler` / `@ExceptionHandler` over per-controller try/catch blocks.
  - [x] Use Spring's built-in `org.springframework.http.ProblemDetail` to emit `application/problem+json`; do not introduce a second problem-details library.
  - [x] Map `DomainException` into the required top-level fields: `type`, `title`, `status`, `detail`, `instance`, `code`, `retryable`, plus optional machine-readable `details`.
  - [x] Add `DomainErrorCode.INTERNAL_ERROR` and its problem-type URI ownership entry so unknown exceptions can be surfaced safely.
  - [x] Keep error-code to `status` / `title` / `retryable` / `type` mapping centralized in one helper or catalog so drift tests can assert full coverage.

- [x] **Task 2: Normalize framework validation and request-binding failures into stable 400 responses** (AC: 2, 4, 7)
  - [x] Handle the Spring MVC validation and request-binding exceptions that occur before `WorkflowCommandService.validate(...)` runs: body validation, missing required headers, enum/binding failures, malformed JSON, and the current Boot 4 MVC validation exception type.
  - [x] For this foundation slice, surface those request-shape failures as `INVALID_COMMAND_PAYLOAD` unless the registry already owns a more specific code. Do not preempt story 1.9 by inventing idempotency-specific behavior outside the governed registry.
  - [x] Emit public `details` as a flat array of objects with `field`, `rejectedValue`, and `constraint`; if the source is the existing `DomainException.details().fieldErrors` shape from story 1.7, translate that internal shape into the public Problem Details contract rather than leaking the internal key names directly.
  - [x] Redact `rejectedValue` when the field or header name indicates a secret-bearing value. Keep the rule narrow and deterministic in this story; full adversarial redaction remains story 1.10.

- [x] **Task 3: Add CLI exception-to-exit-code mapping without pushing try/catch into commands** (AC: 5)
  - [x] Add a global Spring Shell exception resolver / exit-status mapper under `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/` so `WorkflowCommands` and later commands stay thin.
  - [x] When a `DomainException` escapes a command, print exactly `[{code}] {detail}` and return a non-zero exit code using the story's category bands.
  - [x] Keep successful command output unchanged; do not rewrite happy-path command formatting.
  - [x] Document the exit-code bands in one authoritative CLI doc and link to it from the repo's visible entry-point documentation.

- [x] **Task 4: Add HTTP-level contract coverage and registry drift protection** (AC: 4, 6, 7)
  - [x] Extend `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` with every new `problemTypeUris` entry introduced by this story, including `INTERNAL_ERROR`.
  - [x] Strengthen `RegistryContractTest` so every `DomainErrorCode` is covered not only by a type URI but also by mapper-owned HTTP metadata. Adding a new error code without a mapper case should fail the build.
  - [x] Add HTTP contract tests that exercise real serialization and `Content-Type: application/problem+json`, not only unit tests that assert exceptions are thrown.
  - [x] This story should absorb the deferred 1.7 review action to strengthen adapter coverage through MockMvc / real MVC execution for error paths.
  - [x] Cover at minimum: mapped `DomainException`, `APPROVAL_VERSION_MISMATCH` details passthrough, invalid JSON, enum binding failure, missing required `Idempotency-Key`, invalid body validation, and unknown exception fallback to `INTERNAL_ERROR`.

- [x] **Task 5: Hold the boundary with adjacent foundation stories** (AC: 1-7)
  - [x] Do not implement idempotency replay storage/locking from story 1.9, correlation-ID generation/MDC propagation from story 1.19, or full springdoc OpenAPI publication from stories 1.20/1.21 in this slice.
  - [x] Do not move transport-specific error construction into `application` or `domain`; the mapper adapts stable domain semantics, it does not redefine them.
  - [x] Preserve the machine-readable `DomainException.details()` contract created in earlier stories unless a public transport projection is required.

### Review Findings

Code review run on 2026-05-01 against commit `8714232` (range `HEAD~1..HEAD`). Three layers: Blind Hunter (adversarial, diff-only), Edge Case Hunter (branch coverage, project read), Acceptance Auditor (spec compliance). Initial counts: 2 decision-needed, 6 patch, 5 defer, 9 dismissed. **Resolution (2026-05-01):** both decisions resolved as revert, all 6 patches applied (P5 narrowed to `APPROVAL_VERSION_MISMATCH` only — `ILLEGAL_TRANSITION` left at 101 to match the author's intentional placement in `docs/cli/README.md`). Backend test suite green: `73/73` passed.

- [x] [Review][Decision] Application-layer `correlationId` normalization preempts story 1.19 — **resolved: revert.** Removed `WorkflowCommand.normalizeOptional` and the five compact-constructor calls. Service-layer `WorkflowCommandService.normalizeOptional(...)` (preexisting from story 1.7 era) remains untouched.
- [x] [Review][Decision] Silent `saveAndFlush(event)` → `save(event)` regression in submit — **resolved: revert.** Restored `workflowEventRepository.saveAndFlush(event)` at `WorkflowCommandService.java:77` to preserve same-transaction read-after-write semantics.

- [x] [Review][Patch] Catch-all `@ExceptionHandler(Exception.class)` reclassifies 4xx framework exceptions as 500 and silently swallows the cause [`deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java:152`] — **applied.** Added explicit `@ExceptionHandler` methods for `HttpRequestMethodNotSupportedException` (405), `NoResourceFoundException` (404), `HttpMediaTypeNotSupportedException` (415), `HttpMediaTypeNotAcceptableException` (406), each routing through a new `requestShapeProblem(HttpStatus, ...)` helper that emits `INVALID_COMMAND_PAYLOAD` with the framework-correct status. Added SLF4J logger and `LOG.error(..., exception)` inside `handleUnexpectedException`.
- [x] [Review][Patch] CLI mapper discards original exception on non-DomainException [`WorkflowCliExitStatusExceptionMapper.java:21`] — **applied.** Added SLF4J logger and `LOG.error("Unexpected exception escaped CLI command; surfacing INTERNAL_ERROR", exception)` before returning the generic `ExitStatus`.
- [x] [Review][Patch] CLI exit-code bands miss conflict-class codes [`WorkflowCliExitStatusExceptionMapper.java:25`] — **applied (narrowed).** `APPROVAL_VERSION_MISMATCH` moved into the 2xx band alongside `IDEMPOTENCY_KEY_CONFLICT` and `CONCURRENT_TRANSITION_CONFLICT`. `ILLEGAL_TRANSITION` left at 101 because `docs/cli/README.md` explicitly places it there as a "stable client-side failure". `docs/cli/README.md` updated to add `APPROVAL_VERSION_MISMATCH` to the 201 list. New test `approvalVersionMismatchMapsToThe200SeriesBand` added to `WorkflowCliExitStatusExceptionMapperTest`.
- [x] [Review][Patch] `translateFieldErrors` serializes literal `"null"` strings on missing map keys [`ProblemDetailsMapper.java:212`] — **applied.** Replaced `String.valueOf(map.get(...))` with explicit null checks defaulting to `UNKNOWN_FIELD_NAME` ("unknown") and `DEFAULT_CONSTRAINT` ("invalid").
- [x] [Review][Patch] `fieldErrorsFromBindingResult` ignores global/class-level validation errors [`ProblemDetailsMapper.java:222`] — **applied.** Added a second loop over `errors.getGlobalErrors()` projecting `ObjectError` entries with the object name and a new `resolveObjectConstraint(...)` helper.
- [x] [Review][Patch] AC4 violation in contract test — equality assertion on human-readable `detail` text [`ProblemDetailsContractTest.java:202`] — **applied.** Removed `.andExpect(jsonPath("$.detail").value("An unexpected internal error occurred."))`; added `.andExpect(jsonPath("$.retryable").value(false))` to keep machine-readable contract coverage. Redaction-style `not.containsString(...)` assertions retained.

- [x] [Review][Defer] Field-name-only redaction allowlist gaps and false positives [`deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java:258`] — `Idempotency-Key` is not redacted; `credential`/`bearer`/`private` patterns are missing; harmless field names containing `token` (e.g., a future `tokenizerVersion`) get redacted unnecessarily. Deferred — story 1.10 owns adversarial redaction per spec (Task 2: "Keep the rule narrow and deterministic in this story").
- [x] [Review][Defer] `extractJacksonFieldName` returns innermost reference for nested errors [`deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java:138`] — the `while (matcher.find()) { field = matcher.group(1); }` loop overwrites with each match; nested DTOs would surface the leaf name, not the top-level field. Deferred — current command DTOs are flat and tests cover only shallow cases. Revisit when a nested request body is introduced.
- [x] [Review][Defer] Out-of-scope drive-by additions inside this commit — new `PublicIdPrefixesTest.java` (50 lines), two new `WorkflowCommandServiceContractTest` cases (`approveSpecRaisesRunNotFoundForUnknownWorkflowRunId`, `approveSpecRejectsIllegalTransitionWhenRunIsNotWaitingForSpecApproval`), `PublicIdPrefixes.java` import reorder, secondary `.thenComparing` in `WorkflowCommandService.validate(...)`. Useful additions but outside transport-adaptation scope. Deferred — accept as bonus coverage; track for separation in future stories if scope-creep recurs.
- [x] [Review][Defer] `WorkflowAdapterEquivalenceTest` lost cli↔rest field-by-field response comparison [`deliveryline-backend/src/test/java/org/dradgo/adapters/WorkflowAdapterEquivalenceTest.java`] — replaced by jsonPath assertions and a captured-command equivalence check. Net coverage went up (real MVC), but the prior assertion that both adapters produce the same logical response payload was dropped. Deferred — restore in a follow-up if cross-adapter response divergence is observed.
- [x] [Review][Defer] Future CLI commands must wire `exitStatusExceptionMapper` explicitly [`deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:22`] — only `submit` exists today. New commands added without `exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME` will silently revert to Spring Shell defaults and break the unified bands. Deferred — add a `// TODO`/`// REVIEW` comment near the `@Command` annotation when story 1.18+ adds more commands; consider an ArchUnit rule when we have more than one.

Acceptance Auditor confirmed: AC1, AC2, AC3, AC5, AC6, AC7 satisfied; Tasks 1, 3, 4, 5 fully implemented; Task 2 satisfied with the redaction caveats above. AC4 partially satisfied (one equality-on-`detail` violation in `ProblemDetailsContractTest` listed as a Patch above).

## Dev Notes

This story closes the transport-error gap left intentionally open in story 1.7. The backend already throws stable `DomainException`s for command validation and workflow failures, but REST still falls back to framework defaults or raw 500 behavior and CLI still lacks a centralized non-zero error path. The implementation should stay focused on transport adaptation and contract coverage, not new workflow behavior.

**Current repo state**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` already owns the mutation endpoints introduced in story 1.7, but there is no `@RestControllerAdvice`, `ProblemDetail`, or REST exception-mapping class anywhere in the backend yet.
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java` already throws `DomainException(INVALID_COMMAND_PAYLOAD, ...)` with `details.commandType` plus a `fieldErrors` list (`field`, `code`, `rejectedValue`, `message`). Story 1.8 should adapt that application shape into the public Problem Details contract rather than reworking command validation itself.
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` does not yet define `INTERNAL_ERROR`, so unknown-exception fallback will require a registry extension and matching placeholder URI entry.
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` currently owns `problemTypeUris` drift coverage; it includes `INVALID_COMMAND_PAYLOAD` but not `INTERNAL_ERROR`.
- `deliveryline-backend/src/test/java/org/dradgo/adapters/WorkflowAdapterEquivalenceTest.java` is still unit-level and does not yet prove actual HTTP error serialization. Story 1.8 is the right place to add the first MVC-layer error contract tests.
- There is no existing backend or CLI README dedicated to exit-code semantics. If you create a dedicated CLI doc, prefer `docs/cli/README.md` and link to it from [README.md](./c:/Users/pc/Documents/Personal/ai-hackaton-1/README.md).

**Scope discipline**
- Use Spring's built-in `ProblemDetail`; current Spring documentation describes it as the framework representation for RFC 9457 problem details, while this repo's architecture and epics still require the familiar `application/problem+json` wire shape from the RFC 7807 family. The practical guidance for this story is: use Spring's native type, but keep the repo's required top-level fields and URI conventions.
- Keep REST error mapping in `adapters.rest` and CLI exception handling in `adapters.cli`. Do not push transport logic into `application.workflow` or `domain`.
- The `instance` field baseline for story 1.8 is the request path from AC1. Do not preempt story 1.19's later correlation-ID refinement by inventing a different `instance` contract now.
- For framework-side request failures with no governed specific code yet, prefer `INVALID_COMMAND_PAYLOAD` over ad hoc new domain enums. Story 1.9 will own `MISSING_IDEMPOTENCY_KEY` and related idempotency-specific semantics.
- `details` must remain machine-readable JSON. Do not stringify arrays or stuff stack traces, SQL messages, or exception class names into `detail` or `details`.
- Rejected-value redaction in this story should be narrow and deterministic, for example field-name based. Do not try to solve the adversarial secret-fixture problem from story 1.10 here.

**Architecture guardrails from planning**
- The architecture's API consistency rules require domain errors to stay transport-independent while REST maps them to Problem Details and CLI maps them to concise terminal output. Follow that split literally.
- The architecture's consistency-drift rules explicitly forbid custom REST error envelopes and require machine-readable `details`; story 1.8 is where that contract becomes live in code.
- REST mutation endpoints already use kebab-case action paths and shared command models from story 1.7. Error mapping must preserve that transport-neutral command-service design instead of bypassing it.
- The architecture calls OpenAPI a quality artifact, but this story should not block on full springdoc publishing. Keep the placeholder manifest authoritative for problem-type URI drift until story 1.20/1.21 introduce committed OpenAPI outputs.

**Latest technical guidance**
- Prefer Spring MVC's native exception-resolution path (`@ExceptionHandler` / `@ControllerAdvice`) instead of hand-rolling response writing in controllers. The framework already composes exception handlers into the MVC resolver chain.
- Prefer Spring's native `ProblemDetail` support rather than a separate Problem Details library. It supports spec-defined fields plus top-level extension properties, which fits this repo's `code`, `retryable`, and `details` extension fields.
- For CLI, prefer Spring Shell's centralized exception-resolving / exit-status hooks over wrapping every command method in local try/catch blocks. The command methods in this repo should stay thin and focused on argument translation.

**Testing expectations**
- Default to HTTP-level MockMvc contract tests with a mocked `WorkflowCommandService` for REST mapper coverage. The important behaviors here are serialization, content type, stable code/status/details mapping, and request-path `instance`; DB persistence is not the subject of this story.
- Keep assertions on stable fields (`code`, `status`, `retryable`, `type`, and relevant `details`) rather than exact `detail` or `title` strings.
- Add at least one contract test that proves unknown exceptions do not leak stack traces, internal class names, SQL snippets, or absolute filesystem paths.
- Add at least one CLI-focused test that proves `DomainException` is rendered as `[{code}] {detail}` with the expected non-zero exit band.
- If you add helper catalogs for `status` or `retryable`, make them directly assertable in tests so story 1.4 drift coverage can fail loudly when a new `DomainErrorCode` is unmapped.

**Exact file targets**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/` helper class for error-code to `status` / `title` / `retryable` / `type` mapping, if needed
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/` global exception resolver / exit-status mapping class
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/` Problem Details contract tests
- `deliveryline-backend/src/test/java/org/dradgo/adapters/` CLI error-rendering test, if kept transport-focused
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `docs/cli/README.md` (recommended new authoritative CLI exit-code doc) plus a pointer from [README.md](./c:/Users/pc/Documents/Personal/ai-hackaton-1/README.md)

**Previous story intelligence**
- Story 1.7's review deliberately deferred several transport-error issues into this story: missing/blank `Idempotency-Key` currently becomes generic framework 400, `DomainException` currently becomes HTTP 500, and malformed JSON / enum-binding failures currently bypass `INVALID_COMMAND_PAYLOAD`. Story 1.8 should make those cases intentional and machine-readable.
- Story 1.7 also left a review action item to strengthen adapter coverage with MockMvc-driven cases. Fold that work into this story rather than creating a second parallel HTTP-error test harness later.
- Story 1.7 resolved route naming and JSON `consumes` / `produces` declarations in `WorkflowController`. Preserve that contract; do not regress to camelCase routes or mixed envelope formats while adding the mapper.
- Story 1.7 already established `INVALID_COMMAND_PAYLOAD` as a governed registry value and a shared application-layer validation seam. Reuse it instead of inventing transport-specific validation codes in this story unless the registry explicitly grows.

**Git intelligence summary**
- Recent foundational work landed in this order: registry/persistence/transition groundwork (`5368739`), runner-contract authority (`929627a`), then shared CLI/REST command handling (`a09b699`). Story 1.8 should build directly on the REST and CLI adapter surfaces introduced in `a09b699`, not bypass them.
- The backend test style already includes contract-focused Spring Boot tests (`RegistryContractTest`, `WorkflowCommandServiceContractTest`) plus lighter transport unit tests (`WorkflowAdapterEquivalenceTest`). Keep story 1.8 in that same contract-first style.

### Project Structure Notes

- `deliveryline-backend` already follows the architecture's ownership split: `application.workflow` for orchestration, `adapters.rest` for HTTP translation, `adapters.cli` for shell translation, and `domain.registry` for stable wire vocabularies. Story 1.8 should reinforce that split instead of blurring it.
- `adapters.rest` is the correct home for the Problem Details mapper because the architecture explicitly lists REST controllers, DTO mapping, and Problem Details mapping in that package family.
- `contract/` is the right home for HTTP error-shape tests that assert repo-level wire contracts. Reserve `application/` tests for use-case behavior and `adapters/` tests for narrower translation/unit checks.
- No `project-context.md` file exists in this repo today, so the story's guidance must rely on the live planning artifacts, the current backend code, and the previous story file instead of a generated brownfield context pack.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.8 acceptance criteria]
- [Source: `_bmad-output/planning-artifacts/epics.md` - AR9 Problem Details requirement]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - API & Communication Patterns]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - Format Patterns / API Response Formats]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - Consistency Drift Prevention]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - Hard Invariants and Rejection Criteria]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - Project Structure & Boundaries]
- [Source: `_bmad-output/implementation-artifacts/1-7-shared-application-command-model-pattern.md` - Review Findings / Deferred items now in scope]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java` - current `INVALID_COMMAND_PAYLOAD` details shape and validation seam]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` - existing REST command endpoints]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/DomainException.java` - transport-independent domain error container]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` - current governed error-code registry]
- [Source: `deliveryline-backend/src/test/java/org/dradgo/adapters/WorkflowAdapterEquivalenceTest.java` - current transport test baseline]
- [Source: `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java` - registry drift test pattern]
- [Source: `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` - current `problemTypeUris` ownership manifest]
- [Source: `deliveryline-backend/pom.xml` - existing Spring WebMVC, Validation, and Spring Shell dependencies]
- [Source: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/exceptionhandlers.html - Spring MVC exception-handling chain]
- [Source: https://docs.spring.io/spring-framework/docs/6.2.10/javadoc-api/org/springframework/http/ProblemDetail.html - Spring `ProblemDetail` extension model]
- [Source: https://docs.spring.io/spring-shell/reference/4.0/commands/exception-handling.html - Spring Shell centralized exception/exit-status handling]
- [Source: https://docs.spring.io/spring-boot/4.0/api/java/org/springframework/boot/ExitCodeGenerator.html - Spring Boot exit-code contract]

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Auto-selected next backlog story from `_bmad-output/implementation-artifacts/sprint-status.yaml`: `1-8-problem-details-mapper-with-stable-domain-error-codes`
- Reviewed Epic 1 story 1.8 requirements, architecture sections covering Problem Details and API consistency, and the live backend transport/error code seams
- Pulled forward deferred 1.7 review items that explicitly belong to Problem Details mapping and MVC-layer error contracts
- Checked current backend dependencies and confirmed Spring WebMVC, Validation, and Spring Shell support the required mapper/resolver approach without adding a new error library
- Added centralized REST `ProblemDetailsCatalog` and `ProblemDetailsMapper`, plus request DTO/header validation so framework-side failures collapse into governed `INVALID_COMMAND_PAYLOAD` responses
- Added `WorkflowCliExitStatusExceptionMapper`, wired it into `WorkflowCommands`, documented CLI exit-code bands, and preserved happy-path command output
- Added MVC contract coverage for Problem Details serialization and extended registry drift coverage to require mapper-owned HTTP metadata for every `DomainErrorCode`
- Verified targeted transport tests, targeted registry drift coverage, and the full backend regression suite with `./mvnw -pl deliveryline-backend test` (`72` tests passed)

### Completion Notes List

- 2026-04-29: Created comprehensive story context for story 1.8 and prepared it for `dev-story`
- 2026-04-30: Implemented REST Problem Details transport mapping, CLI exit-status mapping, registry drift protection, and CLI documentation; verified with targeted transport/registry tests and a full `deliveryline-backend` regression run (`72` tests passed)

### File List

- `README.md`
- `_bmad-output/implementation-artifacts/1-8-problem-details-mapper-with-stable-domain-error-codes.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCliExitStatusExceptionMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ApproveSpecRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RejectSpecRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RetryWorkflowRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/SubmitWorkflowRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/TakeoverWorkflowRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/WorkflowAdapterEquivalenceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliExitStatusExceptionMapperTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/ProblemDetailsContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `docs/cli/README.md`
- `_bmad-output/implementation-artifacts/1-8-problem-details-mapper-with-stable-domain-error-codes.md`

## Change Log

- 2026-04-30: Implemented REST Problem Details mapping, CLI exception-to-exit-code mapping, HTTP/registry drift contract coverage, and CLI documentation. Verified with `./mvnw -pl deliveryline-backend test` (`72` tests passed).
