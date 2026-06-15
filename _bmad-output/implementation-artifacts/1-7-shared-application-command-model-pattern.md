# Story 1.7: Shared Application Command Model Pattern

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a foundation developer,
I want CLI and REST workflow mutations to translate into the same application command types and flow through one `WorkflowCommandService`,
so that DeliveryLine enforces identical workflow invariants, idempotency behavior, and stable domain error codes across transports.

## Acceptance Criteria

1. **Given** the `application.workflow.commands` package, **Then** command types exist: `SubmitWorkflowCommand`, `ApproveSpecCommand`, `RejectSpecCommand`, `RetryWorkflowCommand`, `TakeoverWorkflowCommand` (approval/reject/retry/takeover command handlers wired progressively - submit is the only command with end-to-end execution in E1; the rest have service-level wiring but adapter surfacing waits for later stories/epics).
2. **Given** each command type, **Then** it carries `actorIdentity`, `actorType` (from `ActorType` registry), `idempotencyKey`, command-specific payload fields (for example `linearTicketReference` on submit and version-bearing fields such as `artifactVersion` plus `contextVersion` on approval-oriented commands), and optional `correlationId`.
3. **Given** Bean Validation annotations on command fields, **When** validation fails, **Then** the command service throws a `DomainException` with stable code `INVALID_COMMAND_PAYLOAD` and machine-readable field-level details.
4. **Given** `WorkflowCommandService`, **When** it receives a valid command, **Then** it enforces workflow invariants, delegates to `WorkflowTransitionService` / `ArtifactOperationService` / `IdempotencyService` / `RunnerBroker` as appropriate, and returns a typed `DomainResult` (success with typed outcome or failure with stable `DomainErrorCode`).
5. **Given** CLI and REST adapters for the same command + payload, **When** both execute, **Then** they produce identical `DomainResult` outcomes and identical stable error codes for failure cases.
6. **Given** the CLI adapter (`adapters.cli`), **Then** Spring Shell commands parse arguments, construct the application command, call the command service, format the result for terminal output - with no workflow orchestration, persistence, approval, or recovery logic inside the adapter (enforced by ArchUnit in story 1.11).
7. **Given** the REST adapter (`adapters.rest`), **Then** stub command endpoints exist under `WorkflowController` using kebab-case action routes and translate HTTP request bodies plus required headers to application commands (full endpoint activation for reads in story 1.20; mutation endpoints land in E2+).
8. **Given** correlation IDs on commands, **When** commands are processed, **Then** the correlation ID is propagated through logs/events as available now, with full structured logging completion deferred to story 1.19.

## Tasks / Subtasks

- [x] **Task 1: Introduce the shared application command model under the real backend package layout** (AC: 1, 2)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/` and add `SubmitWorkflowCommand`, `ApproveSpecCommand`, `RejectSpecCommand`, `RetryWorkflowCommand`, and `TakeoverWorkflowCommand`.
  - [x] Model commands as transport-neutral Java types with the shared envelope fields required by the epic: `actorIdentity`, `actorType`, `idempotencyKey`, optional `correlationId`, plus command-specific payload such as `linearTicketReference` for submit and explicit version-bearing fields on approval/retry/takeover flows where the epic calls for them.
  - [x] Reuse the existing registry wire vocabulary from `ActorType`; do not invent transport-local actor enums or stringly typed constants inside CLI/REST adapters.
  - [x] Keep submit as the only command whose full workflow mutation path is expected to execute end-to-end in Epic 1. Other commands may have application-service wiring and typed results without complete downstream behavior yet.

- [x] **Task 2: Add application-layer validation and stable domain error translation** (AC: 2, 3, 4)
  - [x] Add Bean Validation annotations to command fields and centralize validation inside the application layer rather than duplicating checks in each adapter.
  - [x] Introduce `DomainErrorCode.INVALID_COMMAND_PAYLOAD` in the backend registry and raise `DomainException` with that stable code when command validation fails.
  - [x] Include machine-readable field-level details in the `DomainException.details()` payload so later REST Problem Details mapping in story 1.8 can expose them without inventing a second error shape.
  - [x] Follow the existing `DomainException` style already used by `WorkflowTransitionService`: stable `DomainErrorCode` plus structured details map, not transport-specific exceptions.
  - [x] Keep transport-to-command extraction explicit: REST must source idempotency from the `Idempotency-Key` header, CLI must source it from `--idempotency-key`, and both transports must populate the shared command model instead of burying those values in transport-specific DTO-only logic.

- [x] **Task 3: Add \****`WorkflowCommandService`**\*\* as the single mutation orchestration entry point** (AC: 4, 8)
  - [x] Create `WorkflowCommandService` under `deliveryline-backend/src/main/java/org/dradgo/application/workflow/`.
  - [x] Route command handling through shared application methods that delegate to `WorkflowTransitionService` for state changes rather than letting adapters call repositories or domain registries directly.
  - [x] Design dependencies so missing later-story collaborators (`ArtifactOperationService`, `IdempotencyService`, `RunnerBroker`) can be represented cleanly without forcing premature full implementations in this story.
  - [x] Return a typed `DomainResult` hierarchy or equivalent typed application result model that adapters can render consistently for both success and failure.
  - [x] Ensure `correlationId` is accepted by the command model and carried forward in available service/event/log hooks without trying to finish story 1.19 early.

- [x] **Task 4: Create thin CLI and REST adapter shells with no business logic leakage** (AC: 5, 6, 7)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/` and add a thin Spring Shell command class that parses command input, reads `--idempotency-key`, builds a command object, calls `WorkflowCommandService`, and formats the returned `DomainResult`.
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` plus request DTO types for the workflow mutation endpoints owned by this shared command pattern.
  - [x] Use explicit kebab-case REST action routes under `WorkflowController` for command endpoints, matching the architecture convention (for example `/workflows/{workflowRunId}/approve-spec`) rather than camelCase or ad hoc controller names.
  - [x] REST request DTOs must stay body-focused while transport metadata such as `Idempotency-Key` and any request correlation header handling are mapped into the shared command model at the controller boundary.
  - [x] Keep CLI and REST request translation explicit and thin. No repository injection, no workflow transition logic, no approval/recovery logic, and no file/runner adapter calls belong in the adapters.
  - [x] Scope the adapter surface to what this story needs: command-model translation and equivalence scaffolding. Full REST read activation stays in story 1.20; fuller mutation coverage lands in later stories/epics.

- [x] **Task 5: Add tests that lock adapter equivalence and registry drift behavior** (AC: 3, 4, 5, 6, 7, 8)
  - [x] Add application-layer tests for `WorkflowCommandService` covering valid command handling, invalid command payload mapping to `INVALID_COMMAND_PAYLOAD`, and delegation into `WorkflowTransitionService`.
  - [x] Add transport-equivalence tests proving the same logical submit command produces the same `DomainResult` and stable error code whether invoked through CLI parsing or REST request mapping.
  - [x] Update registry drift coverage so `INVALID_COMMAND_PAYLOAD` is treated as an authoritative new wire value rather than an undocumented local enum change, including the required `problemTypeUris` entry in `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`.
  - [x] Reuse existing backend test infrastructure where appropriate; do not add heavyweight end-to-end stacks just to prove DTO mapping and command-service equivalence.

## Dev Notes

This story is the first application-service slice that turns the foundation work from stories 1.4 through 1.6 into a transport-neutral mutation pattern. The important design goal is not "add a few command classes." It is to make CLI and REST share one command model and one orchestration layer so future workflow mutations do not fork behavior by transport.

**Current repo state**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/` currently contains `WorkflowTransitionService` and `WorkflowTransitionTable`, but no `application.workflow.commands` package and no `WorkflowCommandService`.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/` currently contains only `persistence/`; `adapters.cli` and `adapters.rest` do not exist yet even though the architecture already reserves them.
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` does not yet contain `INVALID_COMMAND_PAYLOAD`, so this story must extend the registry and keep the drift tests honest.
- The backend already has `spring-boot-starter-validation`, `spring-boot-starter-webmvc`, and `spring-shell-starter` in `deliveryline-backend/pom.xml`. Reuse those. Do not add a second validation stack or alternate transport framework.
- Placeholder contract resources that registry-related tests currently inspect live under `deliveryline-backend/src/test/resources/contracts/`, not in main resources. Adding `INVALID_COMMAND_PAYLOAD` requires updating the real placeholder file's `problemTypeUris` map rather than creating shadow copies, because `RegistryContractTest` asserts exact key parity.

**Scope discipline**
- Do not bypass `WorkflowTransitionService` for workflow state changes. This story is about shared orchestration, not a second mutation path.
- Do not implement full `ArtifactOperationService`, `IdempotencyService`, `RunnerBroker`, or Problem Details HTTP mapping here. Those are separate stories. This story should leave clean seams for them rather than inventing throwaway logic inside adapters.
- Do not put business rules into Spring Shell command methods or REST controllers. The architecture explicitly requires both transports to translate into the same application commands and receive the same stable outcomes.
- Do not over-build approval/reject/retry/takeover flows in Epic 1. Service-level wiring is enough now; submit is the only command expected to run end-to-end in this epic slice.

**Validation and error-handling guardrails**
- Validation failures must emerge as `DomainException(INVALID_COMMAND_PAYLOAD, ...)`, not `MethodArgumentNotValidException`, `ConstraintViolationException`, or transport-specific 400 handling leaked into the application layer.
- Field-level error details should be stable and machine-readable. Favor explicit keys such as `field`, `code`, `rejectedValue`, and `messageTemplate` or a similarly structured detail shape that later stories can map consistently.
- Reuse the existing `DomainException` pattern and details-map style already established by `WorkflowTransitionService`. This codebase already prefers stable error codes plus structured details over exception taxonomy sprawl.
- Keep `actorIdentity` and `idempotencyKey` validation coherent with the existing `WorkflowTransitionService.TransitionActor` and transition method preconditions. Do not duplicate slightly-different blank/length rules in every adapter.
- Keep command metadata ownership unambiguous: REST reads `Idempotency-Key` from the header and maps it into the command object; CLI reads `--idempotency-key`; both transports should preserve `correlationId` under that exact field name when handing off to the application layer.

**Architecture guardrails from planning**
- The architecture explicitly calls for one application command model per state-changing operation and requires CLI and REST to share command models and application-service tests.
- `adapters.cli` is reserved for Spring Shell commands; `adapters.rest` is reserved for REST controllers and DTO mapping.
- REST mutation routes must use explicit kebab-case action endpoints under `WorkflowController`; do not invent camelCase paths or spread command endpoints across ad hoc controller names.
- Package-boundary enforcement arrives in story 1.11, but this story should already behave as if those rules exist: no direct repository calls from adapters and no transport logic in the application service.
- Story 1.19 owns full structured logging/correlation propagation. This story only needs to preserve the correlation ID through the command/service boundary and any event details or logging hooks that already exist.

**Exact file targets**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/SubmitWorkflowCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/ApproveSpecCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/RejectSpecCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/RetryWorkflowCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/TakeoverWorkflowCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/` (new package)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/` request DTO types for command endpoints
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/` for command-service tests
- `deliveryline-backend/src/test/java/org/dradgo/contract/` or equivalent adapter-equivalence tests
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` with the required `problemTypeUris.INVALID_COMMAND_PAYLOAD` entry

**Testing expectations**
- Keep tests focused on shared application behavior first, not on full HTTP or shell bootstrapping unless needed to prove translation/equivalence.
- Add tests that prove identical stable error codes for equivalent invalid CLI and REST payloads. Human-readable text can differ later; the stable code and typed result behavior cannot.
- Add at least one submit-path test that proves the adapter layer calls the same application command path and yields the same typed result object across transports.
- Add at least one validation test that shows malformed command input becomes `INVALID_COMMAND_PAYLOAD` with field-level detail entries rather than raw framework exceptions.
- Add at least one transport-mapping test that proves REST sources idempotency from `Idempotency-Key`, CLI sources it from `--idempotency-key`, and neither transport hides that value inside a body-only DTO contract.
- If `DomainErrorCode` grows, update the registry contract tests and placeholder manifests so the new wire value is explicitly governed.

**Previous story intelligence**
- Story 1.5 already established `WorkflowTransitionService` as the canonical state transition path, with structured `DomainException` details and an inner `TransitionActor` value type. Reuse that direction instead of inventing a parallel transition mechanism.
- Story 1.6 created a separate contracts module for runner schemas and deliberately kept backend orchestration concerns out of that module. Maintain that separation here; this story lives in `deliveryline-backend`, not `deliveryline-runner-contracts`.
- Problem Details mapping is intentionally deferred to story 1.8, so this story should stop at stable application errors and leave HTTP serialization concerns to the next slice.
- The sprint is still in foundation mode. Favor narrow, explicit, testable seams over broad infrastructure scaffolding that later stories will replace.

**Git intelligence summary**
- Recent work landed foundational registries, persistence, workflow transition rules, and runner contracts. The next useful increment is to centralize application command handling, not to jump ahead into richer approval/recovery/product flows.
- The most relevant recent commit line for this story is the registry + transition foundation commit (`5368739`) plus the runner-contracts addition (`929627a`). Build directly on those two seams.

### Project Structure Notes

- The architecture names `adapters.cli`, `adapters.rest`, and `application.workflow.commands` explicitly, and the live repo still has room for those packages. Use those exact package names now so story 1.11 ArchUnit rules and later controller/CLI stories align with what gets created here.
- `deliveryline-backend` already owns application orchestration and transport adapters. Do not move command types into `domain` or into a transport package just because the adapters are the first consumers.
- REST mutation endpoints are intentionally only stubbed in this story. Full endpoint activation and broader workflow reads come later, especially story 1.20 for read endpoints and Epic 2/3 stories for richer mutations.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - AR8 shared application command model requirement]
- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.7 acceptance criteria]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - reserved package layout for `adapters.cli`, `adapters.rest`, and `WorkflowCommandService`]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - CLI/REST must share application command models and stable domain errors]
- [Source: `_bmad-output/implementation-artifacts/1-5-workflow-state-transition-table-and-workflow-transition-service.md` - prior foundation slice and transition-service ownership]
- [Source: `_bmad-output/implementation-artifacts/1-6-runner-context-result-schema-v1-with-artifact-variant-discriminators.md` - recent foundation-story scope discipline]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java` - existing transition path and structured domain-error pattern]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/DomainException.java` - canonical stable error + details shape]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` - current governed error-code registry]
- [Source: `deliveryline-backend/pom.xml` - existing Spring Validation, WebMVC, and Spring Shell dependencies]
- [Source: `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` - current registry placeholder contract location]

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Auto-selected next backlog story from `_bmad-output/implementation-artifacts/sprint-status.yaml`: `1-7-shared-application-command-model-pattern`
- Reviewed Epic 1 story 1.7 requirements plus architecture sections covering package layout, shared application command models, and CLI/REST adapter boundaries
- Inspected live backend structure and confirmed `application.workflow.commands`, `WorkflowCommandService`, `adapters.cli`, and `adapters.rest` do not exist yet
- Checked current domain error registry and verified `INVALID_COMMAND_PAYLOAD` is not yet present
- Checked backend module dependencies and confirmed Spring Validation, WebMVC, and Spring Shell are already available for this story
- Added contract-style tests first for `WorkflowCommandService`, adapter equivalence, and registry drift, then ran `mvn -pl deliveryline-backend "-Dtest=WorkflowCommandServiceContractTest,WorkflowAdapterEquivalenceTest,RegistryContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- Implemented the shared command model, typed domain results, `WorkflowCommandService`, thin CLI/REST adapters, and the `INVALID_COMMAND_PAYLOAD` registry extension needed to satisfy the red test failures
- Fixed a persistence bug in submit handling by reusing the managed entity returned from `workflowRunRepository.saveAndFlush(...)` before creating the initial `workflow.stateChanged` event
- Re-ran the targeted verification suite successfully, then ran the full backend regression suite with `mvn -pl deliveryline-backend test`
- Strengthened `WorkflowAdapterEquivalenceTest` into a MockMvc-backed MVC slice so REST coverage now exercises real header binding, JSON parsing, and cross-transport parity through the web layer
- Extended `WorkflowCommandServiceContractTest` with explicit `RUN_NOT_FOUND` and `ILLEGAL_TRANSITION` failure-path coverage for transition commands, then re-ran targeted tests plus the full backend suite

### Completion Notes List

- Added a shared `application.workflow.commands` model plus typed `DomainResult` outcomes so CLI and REST mutations enter the same application-service path
- Implemented `WorkflowCommandService` with centralized Bean Validation, stable `INVALID_COMMAND_PAYLOAD` translation, machine-readable field error details, and submit plus transition-backed workflow command handling
- Added thin Spring Shell and REST adapters that map transport-specific inputs such as `--idempotency-key` and `Idempotency-Key` into the shared command model without leaking business logic into adapters
- Extended workflow event emission to preserve command metadata such as `commandType`, `idempotencyKey`, `correlationId`, and command payload details where relevant
- Verified targeted command/adaptor/registry coverage and the full backend suite: `mvn -pl deliveryline-backend "-Dtest=WorkflowCommandServiceContractTest,WorkflowAdapterEquivalenceTest,RegistryContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` and `mvn -pl deliveryline-backend test`
- Resolved the two remaining review follow-ups by adding MockMvc-driven adapter parity tests and explicit `RUN_NOT_FOUND` / `ILLEGAL_TRANSITION` contract coverage
- Re-verified the follow-up changes with `mvn -pl deliveryline-backend "-Dtest=org.dradgo.adapters.WorkflowAdapterEquivalenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`, `mvn -pl deliveryline-backend "-Dtest=org.dradgo.application.workflow.WorkflowCommandServiceContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`, and `mvn -pl deliveryline-backend test`

### File List

- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/WorkflowCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/SubmitWorkflowCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/ApproveSpecCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/RejectSpecCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/RetryWorkflowCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/TakeoverWorkflowCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/DomainResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/SubmitWorkflowResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowStateChangeResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/SubmitWorkflowRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/SubmitWorkflowResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ApproveSpecRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RejectSpecRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RetryWorkflowRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/TakeoverWorkflowRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowStateChangeResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/WorkflowAdapterEquivalenceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/commands/WorkflowCommandTypeTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/domain/id/PublicIdPrefixesTest.java`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`

### Change Log

- 2026-04-29: Created story 1.7 with implementation guidance for shared application commands, `WorkflowCommandService`, and thin CLI/REST adapters
- 2026-04-29: Implemented story 1.7, verified targeted command/adapter/registry coverage, and passed the full `deliveryline-backend` regression suite before moving the story to review
- 2026-04-29: Completed post-review test follow-ups for story 1.7, reran targeted plus full backend verification, and moved the story to review

## Review Findings

Code review run on 2026-04-29 across three parallel layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor). Unified, deduped, and triaged below. 9 findings dismissed as noise/false-positives (notably: Spring Shell 4.0.2 imports verified valid).

### Decision-needed (resolved 2026-04-29)

- [x] [Review][Decision] **Submit bypasses \****`WorkflowTransitionService`**\*\* for initial creation** — Resolved as **option (a) accept as documented exception**. Inline rationale comment added in `WorkflowCommandService.submit()` explaining initial creation has no prior state to transition from.
- [x] [Review][Decision] **REST route naming inconsistent** — Resolved as **option (a) rename to verb-noun across the board**. Routes renamed: `/submit` → `/submit-workflow`, `/retry` → `/retry-workflow`, `/takeover` → `/takeover-workflow`. Existing `/approve-spec` and `/reject-spec` already match.
- [x] [Review][Decision] **`reasonText`**** required on Retry/Takeover or auto-fallback?** — Resolved as **option (3) keep as-is**. Comment added near `fallbackReason()` in `WorkflowCommandService` documenting that requiring `reasonText` on retry/takeover would be a UX regression for the operator-recovery happy path; reject-spec keeps `@NotBlank` because rejection is an explicit operator decision.
- [x] [Review][Decision] **Sealed \****`DomainResult`***\* interface design\*\* — Resolved as \*\*option (1) hoist \****`currentState()`**\*\* to the interface**. `DomainResult` now declares `currentState()`; both records auto-implement.

### Patch (action items)

- [x] [Review][Patch] **Replace \****`jakarta.transaction.Transactional`***\* with Spring's \****`@Transactional`**\*\*, applied to all five command methods** — applied; all five (`submit`, `approveSpec`, `rejectSpec`, `retryWorkflow`, `takeoverWorkflow`) now wrapped in `org.springframework.transaction.annotation.Transactional`.
- [x] [Review][Patch] **`commandDetails`****: prevent \****`extraDetails`**\*\* from silently overwriting canonical envelope keys** — applied; insertion order swapped in both `WorkflowCommandService.commandDetails(...)` and `WorkflowTransitionService.doTransition(...)` so canonical keys (`idempotencyKey`, `commandType`, `correlationId`) win over caller-supplied details.
- [x] [Review][Patch] **CLI \****`--correlation-id`***\* \****`defaultValue=""`**\*\* removed** — applied; `WorkflowCommands.java` now passes `null` when the option is omitted, matching REST omit semantics.
- [x] [Review][Patch] **`violation.getMessage()`**** instead of \****`violation.getMessageTemplate()`** — applied; key renamed `messageTemplate` → `message` in field error details (resolved message, not raw template tokens).
- [x] [Review][Patch] **Tests use \****`WorkflowState.X.value()`***\* / \****`WorkflowEventType.X.value()`***\* / \****`ActorType.X.value()`**\*\* instead of string literals** — applied across `WorkflowCommandServiceContractTest`.
- [x] [Review][Patch] **`assertEquals(null, ...)`**** → \****`assertNull(...)`** — applied.
- [x] [Review][Patch] **Tautological \****`assertSame(failure, ...)`**\*\* removed** — applied; replaced with `assertEquals(cliError.getMessage(), restError.getMessage())` for actual message-parity assertion.
- [x] [Review][Patch] **Strengthen \****`WorkflowAdapterEquivalenceTest`**\*\* with MockMvc-driven cases** — applied; upgraded the adapter equivalence test into a `@WebMvcTest` MVC slice that now exercises real `Idempotency-Key` header binding, JSON enum/body parsing, validation/error parity, and command equivalence against the CLI path.
- [x] [Review][Patch] **Add tests for \****`RUN_NOT_FOUND`***\* and \****`ILLEGAL_TRANSITION`***\* paths through \****`WorkflowCommandService`** — applied; `WorkflowCommandServiceContractTest` now asserts both stable error codes and structured details for unknown-run approval and illegal-transition approval scenarios.
- [x] [Review][Patch] **Extract UUID-to-hex public-id helper** — applied; `PublicIdPrefixes.next()` added (UUID-derived suffix + format). Both `WorkflowCommandService` and `WorkflowTransitionService` now call `PublicIdPrefixes.WORKFLOW_RUN.next()` / `WORKFLOW_EVENT.next()` instead of duplicating the UUID-strip-format logic.
- [x] [Review][Patch] **Unit test pinning \****`commandType()`**\*\* literals** — applied; new `WorkflowCommandTypeTest` asserts each command's `commandType()` returns its expected literal so future record renames break the test suite loudly.
- [x] [Review][Patch] **`WorkflowController`**** add \****`@Validated`***\*, \****`consumes`***\*/****`produces`**\*\* JSON** — applied; class-level `@Validated` and `@RequestMapping(... consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)`.

**Verification:** `mvn -pl deliveryline-backend test` — 57/57 tests pass after all patches, including the review follow-up coverage.

### Deferred (pre-existing or scope of later stories)

- [x] [Review][Defer] **Idempotency replay: submit creates a fresh run on every call; state-change commands return \****`ILLEGAL_TRANSITION`**\*\* on duplicate-key replay rather than the original outcome** — story 1.9 (`IdempotencyService`) explicitly owns the dedup seam.
- [x] [Review][Defer] **Missing/blank \****`Idempotency-Key`***\* header → Spring \****`MissingRequestHeaderException`**\*\* → generic 400** — story 1.8 (Problem Details mapper) territory.
- [x] [Review][Defer] **`DomainException`**** becomes HTTP 500 (no \****`@RestControllerAdvice`**\*\*)** — story 1.8 explicitly defers HTTP serialization of stable error codes.
- [x] [Review][Defer] **Jackson parse failures (malformed JSON, unknown enum, null \****`actorType`***\*) bypass \****`INVALID_COMMAND_PAYLOAD`** — story 1.8 mapping territory.
- [x] [Review][Defer] **Details JSON >64KB → \****`DataIntegrityViolationException`**\*\*, no domain code mapping** — story 1.8 mapping territory.
- [x] [Review][Defer] **CLI invalid \****`--actor-type`***\* raises Spring Shell parse error, not \****`INVALID_COMMAND_PAYLOAD`** — CLI hardening + 1.19 territory.
- [x] [Review][Defer] **No newline/control-char guard on \****`event.setReason(...)`**\*\* (log-injection vector)** — story 1.19 (structured logging + redaction) explicitly.
- [x] [Review][Defer] **No injectable \****`Clock`***\*; \****`OffsetDateTime.now(...)`**\*\* called directly** — pre-existing pattern in `WorkflowTransitionService`.
- [x] [Review][Defer] **In-place \****`LinkedHashMap`***\* mutation on \****`@JdbcTypeCode(SqlTypes.JSON)`**\*\* columns** — pre-existing pattern; refactor would touch entity layer.
- [x] [Review][Defer] **`correlationId`**** not propagated through structured logs/MDC** — story 1.19 explicitly.
- [x] [Review][Defer] **CLI exposes only \****`submit`**\*\*; approve/reject/retry/takeover have no Spring Shell surface** — story scope explicitly defers full CLI mutation surface.
- [x] [Review][Defer] **`linearTicketReference`**** has no \****`@Pattern`**\*\* format constraint** — beyond story 1.7 scope.
- [x] [Review][Defer] **`commandType()`**** derived from \****`getClass().getSimpleName()`**\*\* is rename-fragile** — registry-backed enum is a larger refactor; the patch above adds a literal-pinning test as the minimum guard.
- [x] [Review][Defer] **`WorkflowTransitionService.transition(...)`**** has 4 telescoping overloads** — refactor opportunity (request object / builder); not blocking.
- [x] [Review][Defer] **No seams declared for \****`ArtifactOperationService`***\* / \****`IdempotencyService`***\* / \****`RunnerBroker`***\* in \****`WorkflowCommandService`** — Task 3 wanted clean seams; collaborators arrive in their own stories (1.9, 1.12, 1.13). Defer until those stories land.
- [x] [Review][Defer] **`WorkflowStateChangeResponse.currentState`**** is the static target literal, not re-read from the entity** — `transition(...)` returns void; tightening would require widening the transition signature. Acceptable for now since `WorkflowTransitionTable` rejects illegal targets before commit.
- [x] [Review][Defer] **`event.setReason("workflow submitted")`**** is hardcoded English** — i18n out of scope.
- [x] [Review][Defer] **`details::text`**** Postgres-specific cast in tests** — Testcontainers uses Postgres; ties test to one dialect but not load-bearing.
- [x] [Review][Defer] **`@AfterEach cleanDatabase`**** deletes only \****`workflow_events`***\* and \****`workflow_runs`** — sufficient given the current test scope; expand when other FK tables get touched.

### Code Review — 2026-04-30 (second pass)

Three parallel layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor) re-ran against the full story 1.7 diff (`git diff 929627a` — 28 files, +1143/-15 LOC). The vast majority of raw layer findings duplicated items already addressed in the 2026-04-29 pass and were dismissed. Below are only the **new** or previously-unsurfaced items.

#### Decision-needed (resolved 2026-04-30)

- [x] [Review][Decision] **`RunnerContractValidator`**** path-traversal scope reduction is hidden inside a refactor** — `walk(document, "$", errors)` was replaced with `inspectArtifactReferencePaths(document.path("approvedSpecificationReference"), ...)` + `inspectArtifactReferencePaths(document.path("artifactReferences"), ...)` [`deliveryline-runner-contracts/.../RunnerContractValidator.java:117-145`]. Companion test loosened from `assertEquals(4, ...)` to `assertTrue(... >= 4)` [`RunnerContractValidatorTest.java:60`]. Acceptance Auditor also flagged as scope creep — out-of-scope for story 1.7's `application.workflow.commands` scope. **Resolved as option (3) defer**: split into separate commit/PR for runner-contracts owner review — likely a story 1.6 follow-up, not a story 1.7 scope item. Tracked in `deferred-work.md`.
- [x] [Review][Decision] **No \****`@Valid`***\* on \****`@RequestBody`***\* parameters in \****`WorkflowController`** — \****`WorkflowController.java:198,212,229,247,262`***\*. \*\*Resolved as option (2) add \****`@Valid`***\* to \****`@RequestBody`** for belt-and-braces — promoted to patch P13 below.
- [x] [Review][Decision] **`SubmitWorkflowResponse`**** and \****`WorkflowStateChangeResponse`**\*\* are structurally identical** — both records carry `(workflowRunId, currentState, correlationId)`. **Resolved as option (2) keep distinct** for semantic clarity / future-divergence safety (e.g., submit may later return a Linear sync handle). No code change.

#### Patch (action items)

- [x] [Review][Patch] **Redundant \****`saveAndFlush`***\* calls in \****`submit()`** — `WorkflowCommandService.java:394-412`. Two `saveAndFlush` invocations inside the same `@Transactional`. Mid-transaction flush forces premature IO and is usually cargo-culted from "I needed the generated id" patterns. Keep the run flush (id needed for the event FK), drop the event flush (will flush at commit), or document why both are needed.
- [ ] [Review][Patch] **`PublicIdPrefixes.next()`**** uses inline-qualified \****`java.util.UUID`** — `PublicIdPrefixes.java:113`. Replace `java.util.UUID.randomUUID()` with `import java.util.UUID;` + `UUID.randomUUID()`. Also: no test pins the format/length/charset of `next()`'s output — add one.
- [ ] [Review][Patch] **`Comparator.comparing(violation.getPropertyPath().toString())`**** is non-deterministic when one field has multiple constraint violations** — `WorkflowCommandService.java:510`. A field with both `@NotBlank` and `@Size` failing produces two violations on the same path → undefined `fieldErrors` ordering, flaky test risk. Add secondary sort by `code` (or constraint annotation simple name).
- [ ] [Review][Patch] **Move \****`consumes`***\*/****`produces`***\* from class-level to method-level on \****`WorkflowController`** — `WorkflowController.java:182-185`. Class-level `consumes = APPLICATION_JSON_VALUE` will block any future GET / no-body endpoint added to this controller (e.g., the read endpoints planned in story 1.20) with a 415. Cheap preventive fix.
- [ ] [Review][Patch] **`@Validated`**** on controller is dead without parameter constraints** — `WorkflowController.java:182`. Class-level `@Validated` enables MVC method-validation, but no `@NotBlank @PathVariable String workflowRunId` and no `@NotBlank @Size(max=256) @RequestHeader("Idempotency-Key") String idempotencyKey` exist. Either add the param-level constraints (so `@Validated` does work and a blank header surfaces as a transport-level 400 instead of an `INVALID_COMMAND_PAYLOAD` from the command validator) or remove `@Validated`.
- [ ] [Review][Patch] **`@PathVariable workflowRunId`**** not shape-validated against \****`PublicIdPrefixes.WORKFLOW_RUN`** — `WorkflowController.java:209,227,245,260`. `POST /api/v1/workflows/foo%20bar/approve-spec` reaches the service with `workflowRunId = "foo bar"`, fails the repository lookup, and returns `RUN_NOT_FOUND` — masking that the actual problem is a malformed prefix. Validate prefix shape at the boundary (e.g., `PublicIdPrefixes.require(workflowRunId, WORKFLOW_RUN)`) or via a regex constraint.
- [ ] [Review][Patch] **Magic-string keys for \****`details`***\* map (****`"linearTicketReference"`***\*, \****`"artifactId"`***\*, \****`"artifactVersion"`***\*, \****`"contextVersion"`***\*, \****`"idempotencyKey"`***\*, \****`"correlationId"`***\*, \****`"commandType"`***\*, \****`"fieldErrors"`***\*, \****`"field"`***\*, \****`"code"`***\*, \****`"rejectedValue"`***\*, \****`"message"`**\*\*)** — `WorkflowCommandService.java` throughout. A typo in any of these keys is unfindable at compile time and silently corrupts audit-event JSON. Extract into a constants holder (`WorkflowEventDetailKeys`) or per-command nested constants. The persisted JSON schema is a public contract.
- [ ] [Review][Patch] **`validate()`**** echoes raw \****`rejectedValue`**\*\* into the public error envelope** — `WorkflowCommandService.java:515` (`fieldError.put("rejectedValue", violation.getInvalidValue())`). If validation ever fires on `actorIdentity`, an `idempotencyKey`, or any token-like field, the rejected value lands in API responses and logs. Sanitize sensitive fields, or whitelist which fields' rejected values may be echoed. (Could also defer to story 1.19's redaction pipeline — see deferred list below.)
- [ ] [Review][Patch] **`assertInstanceOf(DomainException.class, restError.getCause())`**** only unwraps one layer** — `WorkflowAdapterEquivalenceTest.java:974`. Spring may wrap exceptions in multiple layers (`NestedServletException` → `ServletException` → cause); a Spring upgrade can break this assertion. Walk the cause chain to root, or use a helper.
- [ ] [Review][Patch] **`correlationId`**** whitespace normalization happens only in service \****`baseDetails`**\*\*/response, not in the command record** — `WorkflowCommandService.java:533`. The command record retains the raw value (`"   abc   "`) while audit `details` and the response carry the normalized form (`"abc"`). Future code reading `command.correlationId()` directly (logging, headers) will see the un-normalized version. Normalize once in each command record's compact constructor.
- [ ] [Review][Patch] **CLI \****`--idempotency-key`***\* and \****`--actor-identity`**\*\* accept whitespace-only values** — `WorkflowCommands.java:30-34`. Spring Shell `required = true` enforces presence, not non-blankness. A blank value reaches the command validator and surfaces as a raw `DomainException` stack trace because no Spring Shell `ExceptionResolver` is wired. Either pre-validate in the CLI command (cheap), or install a `CommandExceptionResolver` mapping `DomainException` → terminal-friendly message. (CLI hardening also relates to deferred error mapping below — pick one venue.)
- [ ] [Review][Patch] **Story File List does not include \****`WorkflowCommandTypeTest.java`** — `_bmad-output/implementation-artifacts/1-7-shared-application-command-model-pattern.md` File List section. Documentation drift; the test was added during the 2026-04-29 patch pass.

#### Deferred (pre-existing or scope of later stories)

- [x] [Review][Defer] **No authentication/authorization on \****`WorkflowController`**\*\* POST endpoints** — `WorkflowController.java`. No `@PreAuthorize`, no security config visible. `actorIdentity` is taken from the request body, so any client can claim any identity for `submit-workflow`, `approve-spec`, `reject-spec`, `retry-workflow`, `takeover-workflow` and the audit log. Foundation phase has no auth story; this belongs to a dedicated security epic. Track explicitly.
- [x] [Review][Defer] **`Map.of(...)`**** rejects null values — defensive only** — `WorkflowCommandService.java:428-431,446-449`. Today every value passed in is `@NotNull`-validated upstream, so this is safe. A future relaxation of validation OR a refactor reordering `validate()` after the map construction would NPE. Defensive `LinkedHashMap`-with-null-skip is preferred but not currently a bug.
- [x] [Review][Defer] **CLI \****`submit`**\*\* return-string format is coupled with the equivalence test** — `WorkflowCommands.java:42`. The test asserts `"<id> submitted (state: <STATE>)"` against the literal string. Any UX tweak breaks the test, and the test arguably checks the wrong thing (string vs structured result). Refactor the equivalence test to compare command-objects, not formatted output.
- [x] [Review][Defer] **`validate()`**** does not cascade \****`@Valid`**\*\* for nested types** — `WorkflowCommandService.java:503`. All commands today are flat records; cascade is moot. If a future command adds a nested object (e.g., `RetryPolicy` sub-record on `RetryWorkflowCommand`), violations on its fields will be silently skipped. Add `@Valid` cascade convention when the first nested type lands.
- [x] [Review][Defer] **`actorIdentity`****, \****`actorType`**\*\*, and other ActorType enum values bound directly at REST/CLI boundary** — adding/removing/renaming an enum value is a breaking API change. Foundation phase ships internal enums at the boundary; a stable wire-format DTO mapping is a dedicated concern beyond story 1.7.
- [x] [Review][Defer] **`RunnerContractValidator`**** path-traversal scope reduction (D1)** — `walk(...)` → `inspectArtifactReferencePaths(...)` narrowed scanning to two subtrees; companion test loosened to `>=4`. **Reason:** out-of-scope for story 1.7's `application.workflow.commands` work — split into a separate commit/PR for the runner-contracts owner to review (likely a story 1.6 follow-up).

#### Patch application result (2026-04-30)

**Applied (8 of 13 patches):**

- **P1** — Dropped redundant `saveAndFlush(event)` in `WorkflowCommandService.submit()`; the run still flushes for FK id, the event flushes naturally at commit.
- **P2** — `PublicIdPrefixes.java`: replaced `java.util.UUID.randomUUID()` with proper import + `UUID.randomUUID()`. New `PublicIdPrefixesTest` (4 tests) pins `next()` regex/length/uniqueness and round-trips through `PublicIdPrefixes.require(...)`.
- **P3** — `WorkflowCommandService.validate()`: added secondary `.thenComparing(annotation simple name)` so multiple violations on the same field have a deterministic order.
- **P4** — `WorkflowController`: moved `consumes`/`produces` from class-level `@RequestMapping` to each `@PostMapping`, leaving the class-level free for future GET/no-body endpoints (story 1.20).
- **P9** — `WorkflowAdapterEquivalenceTest`: replaced single-layer `assertInstanceOf(... restError.getCause())` with `unwrapRootDomainException(...)` helper that walks the full cause chain.
- **P10** — `correlationId` now normalized in each command record's compact constructor via shared `WorkflowCommand.normalizeOptional(...)`. Service-side `normalizeOptional` becomes a no-op for already-normalized values; behavior unchanged for callers, source-of-truth is the record.
- **P12** — Story File List updated: added `WorkflowCommandTypeTest.java` and the new `PublicIdPrefixesTest.java`.
- **P13** *(promoted from D2)* — `WorkflowController`: added `@Valid` to every `@RequestBody` parameter so future DTO-level constraints fire at the controller boundary.

**Verification:** `mvn test` — **61/61 tests pass** (was 57; +4 from new `PublicIdPrefixesTest`).

**Skipped — need design judgment, deferred to story 1.8 (Problem Details mapper) or follow-up review:**

- **P5** — Adding `@NotBlank @Size` to `@RequestHeader("Idempotency-Key")` and `@PathVariable workflowRunId`. Reason: would surface `ConstraintViolationException` which currently maps to HTTP 500 without a `@RestControllerAdvice`; adding it is part of story 1.8's stable error envelope. Tracked under existing deferred bullet "Missing/blank `Idempotency-Key` header".
- **P6** — Validating `@PathVariable workflowRunId` shape via `PublicIdPrefixes.require(workflowRunId, WORKFLOW_RUN)`. Reason: would throw `DomainException(INVALID_ID_PREFIX)` which today leaks as 500 (same dependency on 1.8 mapper). Defer to 1.8 along with P5.
- **P7** — Magic-string `details` keys → constants holder. Reason: needs a structure decision (single `WorkflowEventDetailKeys` class vs. per-command nested constants vs. registry-style enum). Worth a short brainstorming pass with the architect; not a one-shot patch.
- **P8** — Sanitizing `rejectedValue` in error envelope. Reason: needs a redaction-policy decision (whitelist of fields whose rejected values are safe to echo). Belongs to story 1.19 (structured logging + redaction). Tracked under existing deferred bullet "No newline/control-char guard on `event.setReason(...)`" — extend that scope explicitly.
- **P11** — CLI `--idempotency-key` / `--actor-identity` whitespace handling. Reason: needs a venue decision (pre-validate in each `@Command` method, or install a Spring Shell `CommandExceptionResolver` mapping `DomainException` → terminal-friendly message). Tracked under existing deferred bullet "CLI invalid `--actor-type` raises Spring Shell parse error".

**Net new files added:**

- `deliveryline-backend/src/test/java/org/dradgo/domain/id/PublicIdPrefixesTest.java`
