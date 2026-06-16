# Story 3.25: REST Endpoint — `takeover` + OpenAPI

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **frontend developer (Developer Takeover UI flow in story 3.29) and CLI user initiating a takeover**,
I want **a REST endpoint `POST /api/v1/workflows/{workflowRunId}/takeover` (plus a `deliveryline takeover` CLI command) wired to the RICH `DeveloperTakeoverService.takeoverWorkflow` (story 3.22) — returning cancelled-runner counts + the preserved GitHub PR reference**,
so that **takeover initiation flows through the same idempotency + Problem Details + OpenAPI conventions as the other developer mutation endpoints (accept-/reject-implementation), and the UI can immediately offer "Continue work in PR {ref}" navigation with the non-reversibility consequence text surfaced from OpenAPI**.

## Acceptance Criteria

> Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md` §"Story 3.25" (lines 502–519). The epic AC text is **idealized**; where it drifts from the live contract, the **Reconciliations (R1–R9)** in Dev Notes win — each AC below is annotated with its governing reconciliation.

1. **Given** `WorkflowController`, **Then** a **new** endpoint exists: `POST /api/v1/workflows/{workflowRunId}/takeover` — kebab-case action — wired to the **rich** `DeveloperTakeoverService.takeoverWorkflow` (NOT the transition-only `workflowCommandService.takeoverWorkflow`). **[R1 — this is additive; the pre-existing transition-only `POST /takeover-workflow` endpoint is left untouched.]**
2. **Given** request body, **Then** a **new** typed DTO carries `{ reasonText, reviewerRole }`; `reasonText` is required (free-form, why takeover needed, `@NotBlank @Size(max=512)`); `reviewerRole` must equal `developer`. **[R2 — the name `TakeoverWorkflowRequest` is already taken by the transition-only endpoint's body-carried-actor DTO; this story's DTO must use a distinct name, e.g. `TakeoverRequest`.]**
3. **Given** mandatory `Idempotency-Key` header + optional `X-Actor-Identity` header, **Then** the same conventions as story 3.23/3.24 apply: missing/blank `Idempotency-Key` → `MISSING_IDEMPOTENCY_KEY` (400); malformed → `INVALID_IDEMPOTENCY_KEY` (400); multi-valued/comma-folded `Idempotency-Key` or `X-Actor-Identity` → `INVALID_COMMAND_PAYLOAD` (400); actor resolved via `localActorIdentityResolver` with local-operator fallback; `actorType` is always `ActorType.HUMAN` at REST transport; `correlationId` read from MDC (`CorrelationIdFilter` ingress).
4. **Given** Problem Details errors, **Then** typed errors cover: `IDEMPOTENCY_KEY_CONFLICT` (409), `ILLEGAL_TRANSITION` (409 — taking over a `Completed`/terminal run), `WORKFLOW_RUN_TERMINAL` (409), `RUN_NOT_FOUND` (404), `INVALID_COMMAND_PAYLOAD` (400 — covers blank `reasonText` via `@NotBlank` + the service's `takeover_requires_non_blank_reason` guard), `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400), `MISSING_IDEMPOTENCY_KEY`/`INVALID_IDEMPOTENCY_KEY` (400). Contract tests check `code` + `status` + `retryable`, never human text. **[R3 — epic's `MISSING_REASON_TEXT` reconciled to `INVALID_COMMAND_PAYLOAD`; epic's `ACTION_NOT_ALLOWED` dropped in favour of `ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL` with NO controller-side allowed-actions pre-check — exactly story 3.24 R3. NET-NEW DomainErrorCodes = ZERO.]**
5. **Given** OpenAPI via `springdoc-openapi`, **Then** the new endpoint + request/response DTOs appear in the regenerated `openapi.json` snapshot; the CI drift check passes; the frontend `schema.d.ts` is regenerated in lockstep. **[R7]**
6. **Given** CLI/REST equivalence (story 1.7 AC5), **Then** a Spring Shell command `deliveryline takeover {runId} --reason "..." [--actor-identity A] [--idempotency-key K] [--correlation-id C] [--verbose]` is added under `adapters.cli`, calling the **same** `DeveloperTakeoverService.takeoverWorkflow`; `--reason` is **required** (the service mandates a non-blank `reasonText`); a contract test asserts CLI/REST identical outcomes. **[R4 — actor posture mirrors `accept-implementation` (HUMAN-only, optional `--actor-identity`), NOT `retry`'s required `--actor-identity`/`--actor-type`; the CLI carries NO `--reviewer-role` (developer is the takeover invariant, hard-coded in the service).]**
7. **Given** ArchUnit (story 1.11 thin-controller rule), **Then** the controller method only parses headers/path/body, validates request shape, constructs the `TakeoverWorkflowCommand`, invokes the service, and maps the result into the response DTO — no business logic, no SPI/persistence/runner-adapter access.
8. **Given** the response, **Then** success returns 200 OK with a **new rich** result DTO carrying: new state (`TakenOver`), recorded `recovery_actions.id` (`rcv_` prefix), counts of `cancelled_for_takeover` runner executions (in-flight + queued, per story 3.22 AC5 — `null` on idempotent replay), the preserved `integration_links` GitHub PR reference (per story 3.22 AC6, `null` when no GitHub link), `correlationId`, and a `replayed` flag. `X-Correlation-Id` response header echoes the request correlation id. **[R5 — `WorkflowStateChangeResponse` is too thin; map from `TakeoverResult` into a new `TakeoverResponse`.]**
9. **Given** the high consequence of takeover (stops orchestrator dispatch, preserved-but-no-further-automation terminal state), **Then** the endpoint's OpenAPI documentation (`@Operation`/`@ApiResponse` description) explicitly states: **"This action is non-reversible in E3 — Epic 4 will add takeover-revert; until then, a taken-over run can only be closed by an operator action."** — so the UI confirmation dialog (story 3.28) can read this consequence text from OpenAPI. **[R6 — must land in the regenerated `openapi.json` so 3.28 can consume it.]**
10. **Given** the contract test suite, **Then** it covers: happy-path takeover from `WaitingForReview` → 200 + state `TakenOver` + cancelled-runner counts + preserved PR ref; takeover from each non-terminal state succeeds; takeover from `Completed`/terminal → 409 `ILLEGAL_TRANSITION` (or `WORKFLOW_RUN_TERMINAL`); blank/missing `reasonText` rejected (400 `INVALID_COMMAND_PAYLOAD`); role-mismatch rejected (400 `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`); missing/invalid idempotency key; idempotent replay (`replayed=true`, counts `null`); GitHub PR reference preserved in response.

## Tasks / Subtasks

- [x] **Task 1 — Inject `DeveloperTakeoverService` into `WorkflowController`** (AC: #1, #7)
  - [x] Add `private final DeveloperTakeoverService developerTakeoverService;` field + constructor parameter to `WorkflowController` (currently injects `WorkflowCommandService`, `WorkflowInspectionService`, `ApprovalReviewerRoleResolver`, `LocalActorIdentityResolver` — see `WorkflowController.java:76-90`).
  - [x] Update **every** `new WorkflowController(...)` test site (the `@WebMvcTest` slice tests `@MockBean`/`@Mock` the controller deps; add a `DeveloperTakeoverService` mock). Grep `new WorkflowController(` and `@MockBean.*WorkflowCommandService` across `src/test` to find them — including `CommandModelSymmetryFoundationContract` and every `*EndpointContractTest` that boots the `WorkflowController.class` slice.
  - [x] Verify no ArchUnit boundary forbids `adapters.rest → application.recovery.DeveloperTakeoverService`. The thin-controller rule (`REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER`, `ArchitectureRuleCatalog.java:228-243`) forbids `application.*.spi..`, `application.runner..`, persistence + runner adapters — `application.recovery` is **not** in that forbidden set (the CLI already imports `RecoveryService`). Confirm green with the ArchUnit Failsafe run.

- [x] **Task 2 — New request + response DTOs** (AC: #2, #5, #8)
  - [x] Create `TakeoverRequest.java` in `adapters/rest` (separate file, mirroring `AcceptImplementationRequest.java`): `record TakeoverRequest(@NotBlank @Size(max = 512) String reasonText, @Size(max = 128) String reviewerRole) {}` with `@JsonIgnoreProperties(ignoreUnknown = false)`. **Do NOT reuse the existing `TakeoverWorkflowRequest`** — that DTO belongs to the transition-only `/takeover-workflow` endpoint and carries body actor/actorType (the pre-2.13 shape).
  - [x] Create `TakeoverResponse.java` in `adapters/rest`: `record TakeoverResponse(...)` with fields mapped from `TakeoverResult` — `workflowRunId`, `currentState` (String, `result.resultingState().value()`), `recoveryActionId` (the `rcv_` public id), `cancelledInFlightCount` (Integer, nullable), `cancelledQueuedCount` (Integer, nullable), `preservedPrReference` (String, nullable), `correlationId` (nullable), `replayed` (boolean). Add a static `from(TakeoverResult)` factory (mirror `WorkflowStateChangeResponse.from`). Annotate required/not-required fields with `@Schema(requiredMode = ...)` matching the nullability table in Dev Notes.

- [x] **Task 3 — New `takeover` REST handler** (AC: #1, #3, #4, #7, #8, #9)
  - [x] Add `public TakeoverResponse takeover(...)` to `WorkflowController`, **copying the `acceptImplementation` handler shape verbatim** (`WorkflowController.java:529-613`) for header handling: `rejectMultiValuedIdempotencyKeyHeader` → `requireNonBlankIdempotencyKey` → `rejectMultiValuedActorIdentityHeader` → `localActorIdentityResolver.requireSafe`/`.resolve` → `correlationId` from MDC.
  - [x] Validate `reviewerRole` via the **existing** `requireDeveloperReviewerRole(request.reviewerRole())` helper (`WorkflowController.java:768-786`, created by story 3.23 — DO NOT re-create). Discard the returned value (the rich service hard-codes `developer`).
  - [x] Build `new TakeoverWorkflowCommand(workflowRunId, actorIdentity, ActorType.HUMAN, idempotencyKey, correlationId, request.reasonText())` and call `developerTakeoverService.takeoverWorkflow(command)`; map the `TakeoverResult` via `TakeoverResponse.from(...)`.
  - [x] `@PostMapping(value = "/{workflowRunId}/takeover", consumes/produces JSON)`, `@Operation(operationId = "takeover", summary = "...")`. The `@Operation` description (or a `@ApiResponse(responseCode="200", description=...)`) MUST carry the AC9 non-reversibility consequence sentence so it lands in `openapi.json`.
  - [x] `@ApiResponses`: 200; 400 (`MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD, INVALID_REVIEWER_ROLE_FOR_ENDPOINT`); 404 (`RUN_NOT_FOUND`); 409 (`IDEMPOTENCY_KEY_CONFLICT, ILLEGAL_TRANSITION, WORKFLOW_RUN_TERMINAL`). NO 503 (takeover doesn't read artifact payloads — unlike accept-implementation).
  - [x] INFO log on receipt + on success (mirror accept-implementation's two `log.info` lines, NEVER logging `reasonText` content — log its length only if at all). See Logging task.

- [x] **Task 4 — New `takeover` CLI command** (AC: #6)
  - [x] Inject `DeveloperTakeoverService` into `WorkflowCommands` (`adapters/cli/WorkflowCommands.java:61-169`) — add field + `@Autowired` ctor param + thread it through the delegation-constructor chain. ⚠️ `WorkflowCommands` has the two-public-constructor pattern; the `@Autowired` annotation must stay on the wiring ctor ([[two-public-constructors-need-autowired]]). Update every `new WorkflowCommands(...)` test site.
  - [x] Add a `requireTakeoverWired()` guard mirroring `requireRecoveryWired()` (`WorkflowCommands.java` ~1414-1423) if the service can be absent in any profile; otherwise a non-null assert.
  - [x] Add `@Command(name = "takeover", ...)` mirroring the `retry` command shape (`WorkflowCommands.java:519-595`) but with the `accept-implementation` HUMAN-only actor posture (`:732-823`): positional `runId` `@Argument(index=0)`; `--reason` **required**; `--actor-identity` optional (resolved via `resolveActorIdentity`, fallback local-operator); `ActorType.HUMAN` hard-coded; `--idempotency-key`/`--correlation-id`/`--verbose` optional. NO `--actor-type`, NO `--reviewer-role`.
  - [x] Build `TakeoverWorkflowCommand` + call `developerTakeoverService.takeoverWorkflow(...)`. Render output mirroring `retry`: `"{recoveryActionId} takeover submitted (state: TakenOver)"` + conditional brackets for `[cancelled-in-flight: N]`, `[cancelled-queued: M]`, `[pr: {ref}]`, `[replayed]` (when `result.replayed()`), `[generated-idempotency-key: …]` (when none supplied), `[correlation-id: …]` (verbose). Wrap in the `pushCorrelation`/`emitSuccess`/`emitFailure`/`MdcKeys.endScope` scaffolding identical to `retry`/`accept-implementation`.

- [x] **Task 5 — Contract tests** (AC: #4, #6, #10)
  - [x] `TakeoverEndpointContractTest` (`@WebMvcTest(controllers = WorkflowController.class)`, **`*Test`/Surefire**, mirroring `AcceptImplementationEndpointContractTest.java`): mock `developerTakeoverService`. Cover AC10 — happy path from `WaitingForReview` (200 + `currentState=TakenOver` + `cancelledInFlightCount`/`cancelledQueuedCount` + `preservedPrReference`); `Completed`/terminal → 409 `ILLEGAL_TRANSITION`; blank `reasonText` → 400 `INVALID_COMMAND_PAYLOAD`; `reviewerRole != developer` → 400 `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`; missing/invalid idempotency key; idempotent replay (`replayed=true`, counts `null`); PR ref preserved. Assert `code`/`status`/`retryable`, never human text.
  - [x] CLI contract coverage in the CLI test (mirror `WorkflowCommandsTest`'s `accept-implementation` cases): assert CLI/REST identical outcomes — same `DeveloperTakeoverService.takeoverWorkflow` invocation + command field equality; required `--reason`; HUMAN actor; generated idempotency key path.

- [x] **Task 6 — OpenAPI snapshot + frontend schema regen** (AC: #5, #9)
  - [x] Regenerate `deliveryline-backend/src/main/resources/openapi/openapi.json`: run the **`test` lifecycle phase** (not the direct `surefire:test` goal) with `-Dopenapi.snapshot.write=true` — see [[maven-arglineation-goal-crash]] — then re-run `OpenApiSnapshotContractTest` to confirm byte-stable. Review the diff: it must add the `takeover` operation + `TakeoverRequest`/`TakeoverResponse` schemas + the AC9 consequence description, and change nothing else.
  - [x] Regenerate the frontend `deliveryline-frontend/src/lib/api/schema.d.ts` via `npm run generate-api` (cross-shell: backend snapshot in one shell, `generate-api` in the shell that owns the `node_modules/.bin` shim — [[openapi-regen-platform-shim]]). Run `npm run check:api` + `tsc` + `prettier --write` so the frontend gate stays green ([[prettier-gate-cascades-ci]]).
  - [x] Commit `openapi.json` + `schema.d.ts` together.

- [x] **Task 7 — Verification** (AC: all)
  - [x] Focused units + the two contract tests green; `OpenApiSnapshotContractTest` 1/0 (byte-exact after regen); `ArchitectureBoundaryTest` green (thin-controller rule intact); `CommandModelSymmetryFoundationContract` **unchanged + still green** (the new endpoint does NOT touch the existing `/takeover-workflow` round-trip).
  - [x] Run `spotless:apply` before any gate (Google-Java-Format reflows Javadoc — [[story-3-23-accept-implementation-rest-reconciliations]]); then checkstyle + `-Pfoundation-gate verify`. Recommend WSL2/Linux clean-env Docker confirm before merge ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `recoveryActionId`). Use MDC where the framework supports it; otherwise pass as parameters.
  - [x] Never log secrets, payload bytes, raw tokens, or full PII. **`reasonText` is free-form developer prose — never log its content (log its length at most), matching `retry`'s reason-handling.** Reference the redaction policy when in doubt.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (the boundary `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` WARN is already exercised by the 3.23 idiom; pin the new REST receive/success INFO + the rejection WARN).

## Dev Notes

### What this story IS (and IS NOT)

A **thin REST + CLI adapter** over the already-`done` story-3.22 `DeveloperTakeoverService.takeoverWorkflow`. **NO** service-logic change, **NO** migration, **NO** registry/state-machine/transition-table change, **NO** new `DomainErrorCode`, **NO** new `WorkflowEventType`, **NO** `CommandModelSymmetryFoundationContract` change. This is the takeover analogue of story 3.23 (`accept-implementation`) — but with **two key deltas** from that twin: (a) it wires the **rich recovery service**, not `WorkflowCommandService`, so it needs a **richer response DTO**; (b) the endpoint path `/takeover` is **net-new and additive** alongside the pre-existing transition-only `/takeover-workflow`.

**Direct template to copy:** the `acceptImplementation` handler (`WorkflowController.java:529-613`) + `AcceptImplementationRequest.java` + `AcceptImplementationEndpointContractTest.java` + the `accept-implementation` CLI command (`WorkflowCommands.java:732-823`). Story 3.23 is `done`; story 3.24 (`reject-implementation`) is still `ready-for-dev` and **not yet dev'd** — so do NOT expect any `reject-implementation` endpoint/DTO/CLI/test to exist; 3.25 is independent of 3.24 (they only share the `requireDeveloperReviewerRole` idiom + `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`, both created by 3.23).

### Reconciliations — epic AC drift vs. live code (these WIN over the epic text)

- **R1 — ADD a new `/takeover` endpoint; do NOT re-point `/takeover-workflow`.** The epic AC1 path is literally `/takeover` (kebab action). A transition-only `POST /{workflowRunId}/takeover-workflow` already exists (`WorkflowController.java:715-733`) calling `workflowCommandService.takeoverWorkflow` → `WorkflowStateChangeResponse`, with a body-carried-actor `TakeoverWorkflowRequest`. The `DeveloperTakeoverService` javadoc (lines 72-75) speculates 3.25 will "re-point it" — **diverge from that comment**: re-pointing would (a) break the green `CommandModelSymmetryFoundationContract.captureTakeover` round-trip that verifies `workflowCommandService.takeoverWorkflow` on `/takeover-workflow` (`CommandModelSymmetryFoundationContract.java:305-335, 492-508`), and (b) be a breaking OpenAPI response-shape change to a documented operation. Adding a new `/takeover` is purely additive (matches the "additive contract" philosophy the OpenAPI snapshot test enforces) and keeps both contracts green. **Both endpoints coexist** — mirroring the existing retry asymmetry (transition-only `/retry-workflow` REST + rich `recoveryService.retry` CLI), except here the rich path becomes REST-available.
- **R2 — request DTO name clash.** `TakeoverWorkflowRequest` is already defined (`adapters/rest/TakeoverWorkflowRequest.java`: `{actorIdentity, actorType, correlationId, reasonText}`) for the transition-only endpoint. This story's body is `{reasonText, reviewerRole}` (header-carried actor, the 2.13 pattern). Use a **distinct name** — recommend `TakeoverRequest`.
- **R3 — NET-NEW DomainErrorCodes = ZERO.** Epic AC4 names `MISSING_REASON_TEXT` and `ACTION_NOT_ALLOWED`; **neither exists** and **neither should be added**:
  - `MISSING_REASON_TEXT` → reconcile to **`INVALID_COMMAND_PAYLOAD`**. `@NotBlank` on `TakeoverRequest.reasonText` surfaces as `INVALID_COMMAND_PAYLOAD` (the existing bean-validation → ProblemDetails mapping; same path accept-implementation's `@NotBlank artifactId` uses). Defence-in-depth: the rich service ALSO guards blank reason and throws `INVALID_COMMAND_PAYLOAD` (`DeveloperTakeoverService.requireTakeoverInvariants`, `:558-566`, `details.reason=takeover_requires_non_blank_reason`). One code, three layers consistent.
  - `ACTION_NOT_ALLOWED` → dropped. Taking over a terminal run fails inside the transition as `ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL` (409). **No controller-side allowed-actions pre-check** (would violate the thin-controller ArchUnit rule). This is exactly story 3.24's R3. `* → TakenOver` is legal from every NON-terminal state, so "state forbids" reduces to "terminal → ILLEGAL_TRANSITION."
  - `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` already exists (story 3.23, `DomainErrorCode.java:126`) — **reuse, do NOT re-add** (re-adding reds `RegistryContractTest`/`ProblemDetailsCatalog` boot-assert on the duplicate, per [[new-domainerrorcode-three-sites]] / [[story-3-23-accept-implementation-rest-reconciliations]]).
- **R4 — CLI actor posture + required `--reason`.** Takeover is a developer/HUMAN action, so the CLI mirrors `accept-implementation` (optional `--actor-identity` resolved with local-operator fallback, `ActorType.HUMAN` hard-coded) — NOT `retry` (which requires `--actor-identity`/`--actor-type`). No `--reviewer-role` flag (the service hard-codes `developer`; `reviewer_role` is the takeover invariant, NOT on `TakeoverWorkflowCommand` — story 3.22 Trap T2). `--reason` is **required** because `DeveloperTakeoverService` mandates a non-blank `reasonText` (a missing reason on the CLI would otherwise fail deep in the service). The stale `WorkflowCommands.java:685` comment calling submit/retry/**takeover** "pre-existing" is inaccurate — there is **no** existing takeover CLI command.
- **R5 — richer response DTO required.** `WorkflowStateChangeResponse {workflowRunId, currentState, correlationId}` cannot carry AC8's cancelled-runner counts + PR ref. Map `TakeoverResult` → a new `TakeoverResponse`. This is the structural delta from 3.23/3.24 (which reuse `WorkflowStateChangeResponse`).
- **R6 — AC9 consequence text must land in `openapi.json`.** Put the non-reversibility sentence in `@Operation`/`@ApiResponse` so it serializes into the snapshot; story 3.28's confirmation dialog reads it from there.
- **R7 — OpenAPI is additive.** `OpenApiSnapshotContractTest` (`@SpringBootTest`+Testcontainers, **`*Test` but Failsafe-routed**) asserts pre-existing operations survive (`submitWorkflow`, `approveSpec`, `rejectSpec`, `acceptImplementation`, `retryWorkflow`, `takeoverWorkflow`). Adding `takeover` must not drop any. Snapshot path: `deliveryline-backend/src/main/resources/openapi/openapi.json`; frontend mirror: `deliveryline-frontend/src/lib/api/schema.d.ts`.
- **R8 — `reviewerRole` is REST-boundary-only.** The body carries it (for symmetry with accept/reject-implementation + so the UI can fail fast), the controller validates `== developer` via `requireDeveloperReviewerRole`, then **discards** it. The rich service does NOT accept `reviewerRole` on the command and hard-codes `developer` on the `recovery_actions` insert (story 3.22). CLI carries no reviewerRole — so CLI/REST equivalence is on **outcomes** (resulting state + counts), not on the boundary-validation surface.
- **R9 — `CommandModelSymmetryFoundationContract` is UNCHANGED.** `TakeoverWorkflowCommand` is already in `EXPECTED_PERMITS` and already round-trips via the transition-only `/takeover-workflow` (`captureTakeover`). The new `/takeover` endpoint constructs a `TakeoverWorkflowCommand` but hands it to `DeveloperTakeoverService` (which internally reuses `workflowCommandService.takeoverWorkflow` for the transition) — the existing mock-and-capture on `/takeover-workflow` is untouched. **No round-trip to add** (unlike 3.23's `captureAcceptImplementation`).

### Exact live-code signatures (verified)

- **Rich service** — `org.dradgo.application.recovery.DeveloperTakeoverService.takeoverWorkflow(TakeoverWorkflowCommand) → TakeoverResult` (`DeveloperTakeoverService.java:182`). Validates `actorType==HUMAN` + non-blank `reasonText` FIRST (`:558-566`), then idempotency. Atomic `REQUIRES_NEW` prep (transition + cancel-flips + `recovery_actions` insert), best-effort post-commit container `cancel`, `markSucceeded`. Hard-codes `reviewer_role='developer'`. On replay → counts `null`, `replayed=true`.
- **Command** — `org.dradgo.application.workflow.commands.TakeoverWorkflowCommand(@NotBlank @Size(128) String workflowRunId, @NotBlank @Size(128) String actorIdentity, @NotNull ActorType actorType, @NotBlank @Size(256) String idempotencyKey, @Size(128) String correlationId, @Size(512) String reasonText) implements WorkflowCommand`. **No `reviewerRole` field.**
- **Result** — `org.dradgo.application.recovery.TakeoverResult(String workflowRunId, String recoveryActionPublicId, WorkflowState resultingState, String resultingEventPublicId, Integer cancelledInFlightCount, Integer cancelledQueuedCount, String preservedPrReference, String correlationId, boolean replayed)`. Nullability: `resultingEventPublicId`/`cancelledInFlightCount`/`cancelledQueuedCount`/`preservedPrReference`/`correlationId` nullable; `cancelled*` + `preservedPrReference` are `null` on replay.
- **Reused helper (story 3.23, both layers, DO NOT re-create):** `requireDeveloperReviewerRole(String)` private static in `WorkflowController.java:768-786` and `WorkflowCommands.java:825-853` — trims, throws `DomainException(INVALID_REVIEWER_ROLE_FOR_ENDPOINT, details{field,expected,actual})` after a WARN. ⚠️ Its WARN message hard-codes `"accept-implementation rejected"`; reusing verbatim for takeover logs a slightly misleading line — consider generalizing the message to "developer-only endpoint rejected" (low priority; keep symmetric across REST + CLI if changed).
- **Header/validation helpers (reuse verbatim):** `rejectMultiValuedIdempotencyKeyHeader`, `requireNonBlankIdempotencyKey`, `rejectMultiValuedActorIdentityHeader` (`WorkflowController.java:742-851`); `localActorIdentityResolver.requireSafe`/`.resolve`; `correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID))`.
- **Three-sites pattern (only needed IF a new code is added — it is NOT here):** `DomainErrorCode` enum + `ProblemDetailsCatalog.register(...)` (`:418-423` example) + `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (`problemTypeUris` map). Listed for reference only ([[new-domainerrorcode-three-sites]]).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - REST `takeover` handler → `INFO` on receipt (run id + actor, NOT reasonText content) + `INFO` on success (run id + currentState + recoveryActionId + cancelled counts); `WARN` on the `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` boundary rejection (already in the reused idiom).
  - CLI `takeover` command → mirror `retry`'s `emitSuccess`/`emitFailure` + reason-length-only audit line.
  - The rich service itself already carries the state-transition / cancel-flip / replay / conflict logging (story 3.22) — do NOT duplicate; the adapter logs are transport-boundary lifecycle only.
- **Required context keys** (MDC or structured params): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus `recoveryActionId` from the result.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, and **`reasonText` content** (developer prose — length only).
- **Test contract:** new logging surfaces pinned by at least one focused test (list-appender or `OutputCaptureExtension`).

### Project Structure Notes

- REST: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/` — `WorkflowController.java` (+ new `TakeoverRequest.java`, `TakeoverResponse.java`).
- CLI: `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java`.
- Service (unchanged, consumed only): `deliveryline-backend/src/main/java/org/dradgo/application/recovery/{DeveloperTakeoverService,TakeoverResult}.java`; `application/workflow/commands/TakeoverWorkflowCommand.java`.
- Tests: `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/TakeoverEndpointContractTest.java` (new); CLI cases in the existing `WorkflowCommandsTest`; `OpenApiSnapshotContractTest` (regen, no code edit).
- Snapshots: `deliveryline-backend/src/main/resources/openapi/openapi.json` + `deliveryline-frontend/src/lib/api/schema.d.ts`.
- **Ctor fan-out (both adapters):** adding `DeveloperTakeoverService` to `WorkflowController` AND `WorkflowCommands` constructors fans out to every manual `new …(...)` test site + `@WebMvcTest` `@MockBean` set; `WorkflowCommands` two-constructor `@Autowired` caveat applies ([[two-public-constructors-need-autowired]]).
- **Parallel-dev coordination:** story 3.24 (`reject-implementation`, `ready-for-dev`) touches the SAME two files (`WorkflowController`, `WorkflowCommands`) and ALSO regenerates `openapi.json`/`schema.d.ts`. No logical conflict (different endpoints), but expect merge friction on those four files if dev'd concurrently — rebase + re-run the snapshot regen if 3.24 lands first.

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.25] (lines 502–519) — AC source (idealized; R1–R9 reconcile).
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.22] (lines 443–462) — the rich service this wires.
- [Source: deliveryline-backend/.../adapters/rest/WorkflowController.java:529-613] — `acceptImplementation` template; `:715-733` transition-only takeover; `:768-786` reviewer-role helper; `:742-851` header guards.
- [Source: deliveryline-backend/.../adapters/cli/WorkflowCommands.java:519-595] — `retry` template; `:732-823` `accept-implementation`; `:825-853` CLI reviewer-role helper.
- [Source: deliveryline-backend/.../application/recovery/DeveloperTakeoverService.java:182,558-566] — service entry + reason guard.
- [Source: deliveryline-backend/.../application/recovery/TakeoverResult.java] / [.../workflow/commands/TakeoverWorkflowCommand.java] — DTO mapping sources.
- [Source: deliveryline-backend/.../foundation/CommandModelSymmetryFoundationContract.java:305-335,492-508] — existing `/takeover-workflow` round-trip (leave green).
- [Source: deliveryline-backend/.../architecture/ArchitectureRuleCatalog.java:228-243] — thin-controller rule.
- [Source: deliveryline-backend/.../adapters/rest/AcceptImplementationEndpointContractTest.java] — contract-test shape.
- Memory: [[story-3-22-developer-takeover-reconciliations]], [[story-3-23-accept-implementation-rest-reconciliations]], [[story-3-24-reject-implementation-rest-reconciliations]], [[epic3b-command-and-approval-wiring-fanout]], [[new-domainerrorcode-three-sites]], [[two-public-constructors-need-autowired]], [[maven-arglineation-goal-crash]], [[openapi-regen-platform-shim]], [[prettier-gate-cascades-ci]], [[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]], [[commit-no-claude-coauthor]], [[rtk-hook-only-matches-bash]].

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (bmad-dev-story)

### Debug Log References

- Backend unit tier (PowerShell `test` phase, `-Djacoco.skip=true`): **1019 / 0 / 12 skipped**.
- New + regression slices: `TakeoverEndpointContractTest` 13/0, `WorkflowCommandsTest` 14/0, plus all 11 `@WebMvcTest(WorkflowController)` slices green (Accept/Reject/Approve/RejectSpec/AllowedActions/AnswerClarification/ProblemDetails/Logging/AdapterEquivalence) and the 6 ctor-fanout CLI test files (Sync/StatusHistory/RunnerLogs/ContextBundle/JsonSchema/Batch) + `CorrelationIdMdcLeakageTest`.
- `OpenApiSnapshotContractTest` (Failsafe / Testcontainers): regenerated with `-Dopenapi.snapshot.write=true`, re-ran byte-exact **1/0** (+143 lines: `takeover` op + `TakeoverRequest`/`TakeoverResponse` schemas + AC9 consequence text; ZERO deletions).
- `ArchitectureBoundaryTest` **52/0** (AC7 thin-controller rule intact). `CommandModelSymmetryFoundationContract` **3/0** (R9 — `captureTakeover` `/takeover-workflow` round-trip unchanged) + `CliRestEquivalenceContractTest` **5/0** under `-Pfoundation-gate`. `WorkflowCliCommandRegistrationIT` **2/0** (new `takeover` command registers).
- Frontend: `npm run generate-api` → `schema.d.ts` (+92, additive); `check:api` in sync; `prettier --write` + `tsc --noEmit` clean.
- Spotless `apply` + `spotless:check`/`checkstyle:check` clean.

### Completion Notes List

- **Drift vs story assumptions:** Story 3.24 (`reject-implementation`) had **already landed** by dev time (the story file assumed it was un-dev'd). Consequence: the shared `requireDeveloperReviewerRole` helper was already **generalized** to `(action, reviewerRole)` (created by 3.23, parameterized by 3.24) — reused verbatim with `requireDeveloperReviewerRole("takeover", request.reviewerRole())` on both REST + CLI. No conflict; the four shared files (`WorkflowController`, `WorkflowCommands`, `openapi.json`, `schema.d.ts`) were rebased on top of 3.24's landed state.
- **R1/R9 honored:** added a NEW additive `POST /takeover` (rich `DeveloperTakeoverService`) alongside the untouched transition-only `POST /takeover-workflow` (`workflowCommandService.takeoverWorkflow`). The two Java handler methods are both named `takeover` (legal overload — distinct signatures + operationIds `takeover` vs `takeoverWorkflow`). `CommandModelSymmetryFoundationContract` contract is unchanged (only a `@MockitoBean DeveloperTakeoverService` added so the slice constructs).
- **R3 honored:** ZERO net-new `DomainErrorCode`. Blank `reasonText` → `INVALID_COMMAND_PAYLOAD` via `@NotBlank` bean validation (service keeps its own non-blank guard); terminal-run → `ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL` with NO controller-side allowed-actions pre-check; `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` reused from 3.23 (no re-add).
- **R5 honored:** new rich `TakeoverResponse` (`workflowRunId`, `currentState`, `recoveryActionId`, nullable `cancelledInFlightCount`/`cancelledQueuedCount`/`preservedPrReference`/`correlationId`, `replayed`) mapped from `TakeoverResult`. `null` counts/PR ref on idempotent replay verified.
- **R6 honored:** AC9 non-reversibility consequence sentence lands in both `@Operation.description` and the 200 `@ApiResponse.description` → serialized into `openapi.json` for story 3.28 to consume.
- **R8 honored:** `reviewerRole` is REST-boundary-only — validated `== developer` then discarded; the CLI carries NO `--reviewer-role` (developer is the service-hard-coded takeover invariant); CLI/REST equivalence is on outcomes (HUMAN actor + reasonText pass-through + resulting state/counts), pinned by the `WorkflowCommandsTest` takeover cases capturing the `TakeoverWorkflowCommand`.
- **Ctor fan-out:** `DeveloperTakeoverService` added to `WorkflowController` (1 ctor) and `WorkflowCommands` (the `@Autowired` wiring ctor + delegating + full-assignment ctors; the legacy 3-arg ctor passes `null` and `requireTakeoverWired()` guards the takeover command). 11 `@WebMvcTest` slices got a `@MockitoBean`; 8 manual `new WorkflowCommands(...)` full-ctor test sites got the trailing arg.
- **Logging:** REST `INFO` on receipt (run/actor/reasonLength — never reasonText content) + `INFO` on success (state/recoveryActionId/counts/replayed); reused-idiom `WARN` on reviewer-role rejection; CLI `emitSuccess`/`emitFailure` + reason-length-only audit line. Pinned by `TakeoverEndpointContractTest` (INFO assertions + `noneMatch` on reason content).
- **Encoding note:** story-file checkbox bulk-update via PowerShell double-encoded UTF-8 under the CP1251 system locale; reversed via CP1251 re-encode (see [[literal-nul-byte-binarizes-source]] family). Restored em-dash/arrow/section glyphs verified.

### File List

**New (production):**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/TakeoverRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/TakeoverResponse.java`

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/TakeoverEndpointContractTest.java`

**Modified (production):**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (field + ctor param + rich `takeover` handler)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` (field + 4 ctors + `takeover` command + `requireTakeoverWired`)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated; +143 additive)
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated; +92 additive)

**Modified (test — ctor fan-out / mock-bean):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/AcceptImplementationEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RejectImplementationEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ApproveSpecEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RejectSpecEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/AnswerClarificationEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/AllowedActionsEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/WorkflowControllerLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/WorkflowAdapterEquivalenceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/ProblemDetailsContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/CliRestEquivalenceContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsTest.java` (CLI takeover cases)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsSyncCompletionTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsStatusHistoryTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsRunnerLogsFlagTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsContextBundleFlagTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliJsonSchemaContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowBatchCommandsTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliCommandRegistrationIT.java`

## Review Findings

**bmad-code-review 2026-06-16** — 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the working-tree diff scoped to the File List (26 files, ~1716 diff lines incl. regenerated openapi.json/schema.d.ts).

**Outcome: CLEAN — 0 decision-needed, 0 patch, 0 net-new defer, ~9 dismissed.** Acceptance Auditor FULL PASS — all AC1–AC10 and R1–R9 faithfully implemented, ZERO scope creep, no contradictions. No High/Medium real defect survived verification.

Triage of the Low findings:

- [x] [Review][Dismiss] CLI does not null-guard `result.resultingState().value()` while REST `TakeoverResponse.from` does (Blind, Medium→dismissed) — VERIFIED UNREACHABLE: the service stamps `TAKEN_OVER` on the fresh path (`DeveloperTakeoverService.java:319`) and re-reads the run's non-null `currentState` on replay (`:534-538`, `orElseThrow` on missing run). The REST guard is defensive dead code; the CLI omission is harmless. No production path yields a null `resultingState`.
- [x] [Review][Dismiss] CLI logs the reason-length audit line only on the success path / after `emitSuccess` (Blind, Low) — intentional mirror of the `retry`/`accept-implementation` pattern; `emitFailure` covers the failure path. Not a defect.
- [x] [Review][Dismiss] REST reviewer-role rejection short-circuits before the "received" INFO log (Blind, Low) — intentional symmetry with sibling endpoints; the `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` WARN still fires (pinned by test).
- [x] [Review][Dismiss] `correlationId` response nullability coupled to `CorrelationIdFilter` registration (Blind, Low) — by-design; the filter is always registered in production; `NOT_REQUIRED` schema is correct.
- [x] [Review][Dismiss] CLI whitespace-only `--reason "   "` relies on the service blank-guard rather than a boundary check (Edge, Low) — handled: `DeveloperTakeoverService.requireTakeoverInvariants` (`:562`) rejects blank → `INVALID_COMMAND_PAYLOAD`, symmetric with REST `@NotBlank`. No divergence.
- [x] [Review][Dismiss] `TakeoverResponse.from` copies `recoveryActionId`/`workflowRunId` through without a null guard despite `REQUIRED` schema (Edge, Low) — not triggerable; the service always populates both on fresh and replay paths.
- [x] [Review][Already-deferred] CLI accepts oversized `reasonText` only after a DB round-trip (no `@Valid` on CLI args); the eventual code matches REST (`INVALID_COMMAND_PAYLOAD`) (Edge, Low) — same family as the existing `deferred-work.md` "CLI required-option framework-error vs REST typed code" item (3-24). Behavior-equivalent, efficiency-only.
- [x] [Review][Already-deferred] OpenAPI emits `minLength: 0` for `@NotBlank` `TakeoverRequest.reasonText` (Blind, Low) — another instance of the springdoc limitation already logged in `deferred-work.md:230`.
- [x] [Review][Already-deferred] `TakeoverRequest.reviewerRole` modeled optional/`minLength:0` while the handler strictly requires `== developer`; `Idempotency-Key` still `required:false` on the takeover route (Blind, Low) — already covered by the 3-24 deferred "reviewerRole optional-vs-required" item + `deferred-work.md:229` (idempotency-key required-true scoped to approve/reject/answer only).

## Change Log

| Date       | Version | Description                                                                                 | Author |
| ---------- | ------- | ------------------------------------------------------------------------------------------- | ------ |
| 2026-06-16 | 0.1     | Implemented thin REST `POST /takeover` + `deliveryline takeover` CLI over the rich 3.22 `DeveloperTakeoverService`; new `TakeoverRequest`/`TakeoverResponse` DTOs; OpenAPI + `schema.d.ts` regen (additive); all gates green. Status → review. | Amelia (dev-story) |
