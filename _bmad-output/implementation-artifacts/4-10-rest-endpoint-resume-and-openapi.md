# Story 4.10: REST Endpoint — `resume` + OpenAPI

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a frontend developer (Decision Bar `recovery_operator` mode in story 4.22) and CLI user,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/resume` wired to `RecoveryService.resume` (story 4.5), plus the sibling `deliveryline operator resume` CLI command,
so that resume initiation flows through the same idempotency + Problem Details + OpenAPI conventions as the other mutation endpoints.

## Context & Central Reconciliation (READ FIRST)

**This is the FIRST story of Epic 4's "REST Endpoints" slice (4.10–4.14) and a PURE ADAPTER-WIRING story. The entire application/domain half already exists and is `done`: `RecoveryService.resume(...)` (story 4.5), the `recovery.resumed` event, the `recovery_actions` row, the `RESUME_NOT_APPLICABLE` error code (already mapped 409), and the `resume_workflow` allowed-action (already wired into `case PAUSED:` for `workflow_owner`). Your job is: ONE controller method, TWO DTO records, ONE CLI command, an OpenAPI snapshot regen + frontend client regen, and the tests. You will write ZERO lines in `application/` or `domain/`.**

**The single most expensive mistake available here is wiring the controller to `WorkflowCommandService.resumeWorkflow` instead of `RecoveryService.resume`. Read Reconciliation 2 before you touch `WorkflowController`.**

### HEADLINE RECONCILIATIONS

1. **Everything the epic tells you to "add" already exists. Verify, do not build.** Story 4.5 shipped `resume` end-to-end minus the adapters, and story 4.28 (commit `d06717f`) DELETED the `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` ArchUnit rule. Concretely, all of these are **DONE — do not re-add**: `RecoveryService.resume(String, String, ActorContext, String) → ResumeRecoveryResult` (`RecoveryService.java:676-972`); `DomainErrorCode.RESUME_NOT_APPLICABLE` (`DomainErrorCode.java:231`) mapped to **409 CONFLICT, non-retryable** (`ProblemDetailsCatalog.java:553-558`); `WorkflowEventType.RECOVERY_RESUMED`; `AllowedAction.RESUME_WORKFLOW("resume_workflow")` (`AllowedAction.java:112`) already returned from `WorkflowInspectionService` `case PAUSED:` for `ROLE_WORKFLOW_OWNER` (`:1859-1873`); `WorkflowCommandService.resumeWorkflow` + `ResumeWorkflowCommand` + the sealed-permits/fingerprint fan-out. **Therefore: NO Flyway migration, NO new `DomainErrorCode` (no [[new-domainerrorcode-three-sites]] fan-out), NO new `WorkflowEventType` (no [[new-workfloweventtype-fixture-sites]] fan-out), NO new `AllowedAction` (no `allowed-actions.placeholder.json` / `AllowedActionRegistryPinTest` edit), NO new `WorkflowCommand` permit (no [[new-workflowcommand-permit-updates-symmetry-contract]] fan-out), NO ArchUnit scope-lock edit** (the rule is gone; `RecoveryServiceScopeLiftMetaTest` guards its absence — re-adding it reds the build). [Source: RecoveryService.java:56-143,676-972; ArchitectureRuleCatalog.java:827-831; AllowedAction.java:105-113; WorkflowInspectionService.java:1856-1873; ProblemDetailsCatalog.java:553-558; git log d06717f]

2. **⚠️ THE STRUCTURAL HEADLINE — wire the controller to `RecoveryService.resume`, NEVER to `WorkflowCommandService.resumeWorkflow`.** There are two "resume" entry points and only one is correct. `WorkflowCommandService.resumeWorkflow(ResumeWorkflowCommand)` performs the **state transition ONLY** — story 4.5's "double-dispatch caution" deliberately kept it re-dispatch-free so that re-dispatch is single-sourced in `RecoveryService.resume`. Wiring `/resume` to the command service would silently: (a) skip the runner re-enqueue, so AC8's `runnerExecutionId` is **always null** and the resumed run never actually runs; (b) skip the `recovery_actions` row, so there is no `rcv_` id to return and no audit anchor; (c) skip the `recovery.resumed` event; (d) skip the `PAUSED` current-state guard, so `RESUME_NOT_APPLICABLE` never fires and a wrong-state call surfaces as a raw `ILLEGAL_TRANSITION`. All four are invisible to a naive happy-path test on a `Paused` run. **Bind: `WorkflowController` injects `RecoveryService` and calls `recoveryService.resume(workflowRunId, idempotencyKey, actor, request.reasonText())`.** This mirrors how the rich `/takeover` endpoint calls `DeveloperTakeoverService`, not `workflowCommandService.takeoverWorkflow`. [Source: 4-5 story Dev Notes "Double-dispatch caution"; RecoveryService.java:676-972,1186-1196; WorkflowCommandService.resumeWorkflow]

3. **The template is the RICH `POST /{workflowRunId}/takeover` (story 3.25), NOT `POST /{workflowRunId}/retry-workflow`.** `WorkflowController` carries a legacy pair (`/retry-workflow`, `/takeover-workflow` — `:1929-1967`) that predates story 2.13: they take `Idempotency-Key` as `required = false`, carry `actorIdentity`/`actorType` **in the request body**, and return the thin `WorkflowStateChangeResponse`. **Do not copy them.** The rich `/takeover` (`:1969-2060`) is the live convention and the exact shape 4.10 needs: required `Idempotency-Key` header, header-derived actor, MDC correlation, a rich response carrying `recoveryActionId` + `replayed`, and full `@ApiResponses` springdoc annotations. Copy its guard prologue verbatim:
   ```java
   rejectMultiValuedIdempotencyKeyHeader(httpRequest);
   requireNonBlankIdempotencyKey(idempotencyKey);
   rejectMultiValuedActorIdentityHeader(httpRequest);
   localActorIdentityResolver.requireSafe(actorIdentityHeader);
   String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
   String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
   ```
   The path `/{workflowRunId}/resume` is **free** (verified against all 34 existing `WorkflowController` mappings). [Source: WorkflowController.java:86,1929-1967,1969-2060,2213-2226,2340-2380]

4. **`RecoveryService.resume` takes a positional `ActorContext`, NOT a `*Command` record — this breaks the standard CLI/REST equivalence test idiom.** Signature is `resume(String workflowRunId, String idempotencyKey, ActorContext actor, String reasonText)`, where `ActorContext` is `record ActorContext(String actorIdentity, ActorType actorType, String correlationId)` in **`org.dradgo.application.artifact`** (not `application.recovery`). The existing `CliRestEquivalenceContractTest` asserts record-equality on a captured `WorkflowCommand` — there is no such record here. **Bind: the equivalence test captures the four positional args of `recoveryService.resume(...)` from both surfaces (via `ArgumentCaptor` on each) and asserts all four equal, with `ActorContext` compared by record equality.** Build the actor as `new ActorContext(actorIdentity, ActorType.HUMAN, correlationId)`. [Source: RecoveryService.java:676; ActorContext.java:5; CliRestEquivalenceContractTest.java:133-203]

5. **`ResumeRecoveryResult` has NO `workflowRunId` field — `TakeoverResponse.from(result)` will not compile if copied.** `TakeoverResult` carries `workflowRunId`; `ResumeRecoveryResult(recoveryActionPublicId, resumedEventPublicId, newRunnerExecutionPublicId, resultingState, correlationId, replayed)` does not. **Bind: `ResumeResponse.from(String workflowRunId, ResumeRecoveryResult result)` — pass the `@PathVariable` explicitly.** [Source: ResumeRecoveryResult.java:28-34; TakeoverResponse.java:31-42]

6. **⚠️ `currentState` is NULLABLE on the replay path — this is story 4.5's deferred review finding landing in its named consumer.** `RecoveryService.resume`'s replay branch returns `resolvePriorExecutingStateForReplay(workflowRunId).orElse(null)` as `resultingState` (**`RecoveryService.java:761`**). 4.5's code review deferred this explicitly: *"best-effort informational field; the resume REST/CLI consumer is deferred to story 4.10."* **You are that consumer.** `TakeoverResponse` marks `currentState` `requiredMode = REQUIRED`; for `ResumeResponse` that would be a lie the generated TS client trusts. **Bind: `ResumeResponse.currentState` is `NOT_REQUIRED` (nullable), with a javadoc sentence stating it is null only on a replay whose `→ Paused` anchor event can no longer be resolved (re-pause / event archival). Mirror `TakeoverResponse`'s null-mapping guard (`result.resultingState() == null ? null : result.resultingState().value()`) — do NOT `.value()` an unguarded null.** Do NOT "fix" `RecoveryService` here; record the choice and let OQ-3 carry the alternative. [Source: RecoveryService.java:757-763; 4-5 story "Review Findings" → third `[Review][Defer]`; TakeoverResponse.java:23,32]

7. **`ACTION_NOT_ALLOWED` (epic AC4) DOES NOT EXIST and must not be created.** This codebase deliberately expresses wrong-state as **state-specific** codes (`RETRY_NOT_APPLICABLE` / `RESUME_NOT_APPLICABLE` / `RECONCILE_NOT_APPLICABLE`); `ACTION_NOT_ALLOWED` appears only in `DomainErrorCode` comments explaining why it was rejected. `ProblemDetailsCatalog` fails at class-init if a code is unmapped, so inventing one is a hard error, not a silent one. **The real error set for this endpoint** (all pre-existing, all already mapped): `MISSING_IDEMPOTENCY_KEY` 400 · `INVALID_IDEMPOTENCY_KEY` 400 · `INVALID_COMMAND_PAYLOAD` 400 · `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` 400 · `RUN_NOT_FOUND` 404 · `RESUME_NOT_APPLICABLE` 409 · `IDEMPOTENCY_KEY_CONFLICT` 409 · `ILLEGAL_TRANSITION` 409. [Source: DomainErrorCode.java:74-76,183,190,224-226,231; ProblemDetailsCatalog.java:35-46,251-256,553-558,590-592]

8. **Adding `RecoveryService` to `WorkflowController`'s constructor (10 → 11 args) breaks 17 `@WebMvcTest` slices.** Each must gain `@MockitoBean private RecoveryService recoveryService;` or the slice fails to construct the controller. This is the [[docker-adapter-ctor-dep-fans-out]] pattern. **The exhaustive list** (all under `src/test/java/org/dradgo/`): `foundation/CommandModelSymmetryFoundationContract` · `foundation/CliRestEquivalenceContractTest` · `contract/ProblemDetailsContractTest` · `adapters/WorkflowAdapterEquivalenceTest` · `adapters/rest/{TakeoverEndpointContractTest, RejectImplementationEndpointContractTest, ManualArtifactEndpointContractTest, ClarificationsEndpointContractTest, ArchiveRunEndpointContractTest, AllowedActionsEndpointContractTest, WorkflowControllerLoggingContractTest, RunDependencyEndpointContractTest, RejectSpecEndpointContractTest, ManualBundleEndpointContractTest, ApproveSpecEndpointContractTest, AnswerClarificationEndpointContractTest, AcceptImplementationEndpointContractTest}`. Follow the existing in-file comment idiom (`// Story 3f-3 — WorkflowController gained the run-dependency service; the bean must exist for this @WebMvcTest slice to construct the controller.`). [Source: 17-file grep on `@WebMvcTest(controllers = WorkflowController.class)`; TakeoverEndpointContractTest.java:80-88]

9. **`deliveryline operator resume` is the FIRST MUTATING command in `OperatorCommands` — expect a real constructor fan-out. Mirror `WorkflowCommands.takeover`, NOT `WorkflowCommands.retry`.** This project runs **Spring Shell 4.x**: the annotations are `@CommandGroup(prefix = "deliveryline operator")` + `@Command(name = "resume")` (NOT `@ShellComponent`/`@ShellMethod`), and the registered name is `groupPrefix + " " + @Command.name`. `OperatorCommands` today holds only read-only `status` + `diagnose` and injects exactly four deps (`WorkflowInspectionService`, `WorkflowCommandOutputs`, `CliInteractivityDetector`, `UuidV7Generator`) — it has **no** `RecoveryService`, **no** `IdempotencyKeyValidator`, **no** `LocalActorIdentityResolver`, and **no** generated-idempotency-key supplier. Add them to **both** constructors (the `@Autowired` one and the package-private test one), then update the manual `new OperatorCommands(...)` sites. Reuse the existing private `pushCorrelation`/`CorrelationScope` (`:255-265`) and `exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME`. **`runId` is a positional `@Argument(index = 0)`** — mirroring BOTH the sibling `operator diagnose` (`:166-167`) and `WorkflowCommands.takeover` (`:1414`) — **NOT the epic's `--run {runId}` flag** (see OQ-2).
   **⚠️ The mutation skeleton to copy is `WorkflowCommands.takeover` (`:1404-1470`), not `retry` (`:1326-1402`).** `retry` is the *older* posture: it declares `--actor-identity` **`required = true`** and takes an explicit `--actor-type` (`:1330-1336`), which would contradict AC9's "omitted `X-Actor-Identity` → `local-operator`" symmetry. `takeover` is the live 2.13-era posture and exactly resume's shape: positional runId, **optional** `--actor-identity` resolved through `resolveActorIdentity(...)` (`:1444`), `ActorType.HUMAN` hard-coded, **no** `--actor-type`, **no** `--reviewer-role` (the service hard-codes it), `idempotencyKeyValidator.requireValid(resolveIdempotencyKey(...))` (`:1443`), and the `[generated-idempotency-key: …]` verbose footer. The one divergence: takeover's `--reason` is `required = true` (its service mandates non-blank); resume's `--reason` is **optional**. `OperatorCliCommandRegistrationIT` pins the registered command names — add `deliveryline operator resume` there. [Source: OperatorCommands.java:35-48,66-85,157-178,255-265; WorkflowCommands.java:1326-1336 (retry, the anti-pattern), 1404-1453 (takeover, the template), 2499-2550 (resolveActorIdentity/resolveIdempotencyKey); OperatorCliCommandRegistrationIT.java]

10. **OpenAPI regen is MANDATORY and cascades into the frontend.** A net-new path changes `src/main/resources/openapi/openapi.json`; `OpenApiSnapshotContractTest` boots the app, canonicalizes `/v3/api-docs`, and asserts byte-equality — it reds until you regenerate with `-Dopenapi.snapshot.write=true`, review, and commit. Then the frontend client must be regenerated (`cd deliveryline-frontend && npm run generate-api` → `src/lib/api/schema.d.ts`) or `check:api` reds in CI ([[openapi-regen-frontend-client-drift-cascade]]). Note the asymmetry with 4.5: adding an `AllowedAction` needed **no** regen (open `string[]`), but adding an **endpoint** does. [Source: OpenApiSnapshotContractTest.java:56-58,135-152; deliveryline-frontend/package.json:19; .github/workflows/ci.yml:474-489,830-844]

11. **The endpoint ships DARK — nothing can reach `Paused` in production until `pause` (story 4.8) lands.** `WorkflowTransitionTable` defines `Executing → Paused` as the only into-`Paused` edge, and no code performs that transition (4.8 is `ready-for-dev`, not built). This is a benign **runtime** gap, not a code dependency: 4.5 shipped on the same posture. Tests must seed `Paused` by transitioning `Executing → Paused` through the transition service. Do not add a producer of `Paused` in this story. [Source: 4-5 story Reconciliation 1 + OQ-3; WorkflowTransitionTable.java:91,142-147; sprint-status.yaml 4-8 = ready-for-dev]

12. **`reasonText` is OPTIONAL for resume — copying `/takeover`'s logging line NPEs.** `TakeoverRequest.reasonText` is `@NotBlank`, so `/takeover` safely logs `request.reasonText().length()`. Epic AC2 makes resume's `reasonText` optional (`ResumeWorkflowRequest { reasonText? }`), and `RecoveryService.resume` accepts a null `reasonText`. **Bind: log `reasonLength = request.reasonText() == null ? 0 : request.reasonText().length()` — never the prose itself.** Keep `@Size(max = 512)` and `@JsonIgnoreProperties(ignoreUnknown = false)`. [Source: TakeoverRequest.java:31-33; WorkflowController.java:2038-2040; epic AC2]

## Scope Boundary — what 4.10 BUILDS vs REUSES vs DEFERS

| Concern | 4.10 | Note |
|---|---|---|
| `POST /api/v1/workflows/{workflowRunId}/resume` on `WorkflowController` → `RecoveryService.resume` | **BUILD** | AC1 — Reconciliation 2 + 3 |
| `WorkflowController` ctor gains `RecoveryService` (10→11 args) + 17 `@WebMvcTest` `@MockitoBean` sites | **BUILD** | Reconciliation 8 |
| `ResumeWorkflowRequest` record `{ role, reasonText? }` (`@JsonIgnoreProperties(ignoreUnknown=false)`) | **BUILD** | AC2 — Reconciliation 12 + OQ-1 |
| `ResumeResponse` record + `from(workflowRunId, ResumeRecoveryResult)` | **BUILD** | AC8 — Reconciliation 5 + 6 |
| `requireWorkflowOwnerRole("resume", request.role())` boundary check (validate + discard) | **BUILD** | OQ-1 — mirror `approve-lint` |
| `@Operation` + `@ApiResponses` springdoc annotations (400/404/409 + `ProblemDetailsResponse`) | **BUILD** | AC5 — mirror `/takeover` |
| OpenAPI snapshot regen (`-Dopenapi.snapshot.write=true`) + FE `npm run generate-api` | **BUILD** | AC5 — Reconciliation 10 |
| CLI `deliveryline operator resume {runId} [--reason] [--idempotency-key] [--actor-identity] [--correlation-id] [--format] [--verbose]` | **BUILD** | AC6 — Reconciliation 9 |
| `OperatorCommands` ctor fan-out (both ctors) + `OperatorCliCommandRegistrationIT` pin | **BUILD** | Reconciliation 9 |
| `ResumeEndpointContractTest` + CLI/REST equivalence test + logging pins | **BUILD** | AC9 — Reconciliation 4 |
| `RecoveryService.resume`, `ResumeRecoveryResult`, `recovery.resumed`, `recovery_actions` row, re-dispatch | **REUSE (done, 4.5)** | Reconciliation 1 |
| `RESUME_NOT_APPLICABLE` (409) + `ProblemDetailsCatalog` mapping | **REUSE (done, 4.5)** | Reconciliation 1 + 7 |
| `AllowedAction.RESUME_WORKFLOW` + `case PAUSED:` matrix wiring + placeholder json + pin test | **REUSE (done, 4.5)** | Reconciliation 1 |
| `X-Correlation-Id` response header | **REUSE (done)** | `CorrelationIdFilter` sets it globally — assert only, write no code |
| `rcv_` public id minting | **REUSE (done)** | `RecoveryActionPersistenceAdapter.java:89` |
| Any Flyway migration; any new `DomainErrorCode` / `WorkflowEventType` / `AllowedAction` / `WorkflowCommand` permit | **DO NOT BUILD** | Reconciliation 1 + 7 |
| Re-adding `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` | **DO NOT BUILD** | Reconciliation 1 — 4.28 deleted it; meta-test guards absence |
| Calling `WorkflowCommandService.resumeWorkflow` from the controller | **DO NOT BUILD** | Reconciliation 2 |
| `ACTION_NOT_ALLOWED` error code | **DO NOT BUILD** | Reconciliation 7 |
| A producer of `Paused` (i.e. `pause`) | **DEFER** | Story 4.8 — Reconciliation 11 |
| FE Decision-Bar resume button / `recovery_operator` mode | **DEFER** | Story 4.22 |

## Acceptance Criteria

> From `epic-04-recovery.md` §"Story 4.10" (lines 228–244), with **binding clarifications** in **bold parentheticals**.

1. **Given** `WorkflowController` (extended from story 3.23–3.25), **Then** new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/resume` — kebab-case action. **(Path verified free. Place it immediately after the rich `takeover` method (`:2060`) to keep the recovery cluster contiguous. `WorkflowController` gains a `RecoveryService` ctor dep — Reconciliation 8.)**

2. **Given** request body, **Then** typed DTO `ResumeWorkflowRequest { reasonText? }` in camelCase JSON. **(RECONCILED — bind `ResumeWorkflowRequest(@NotBlank @Size(max=128) String role, @Size(max=512) String reasonText)` with `@JsonIgnoreProperties(ignoreUnknown = false)`, mirroring `ApproveLintRequest` (the live `workflow_owner`-gate precedent). `role` must equal `workflow_owner`, validated at the boundary via the existing `requireWorkflowOwnerRole(...)` helper then DISCARDED — `RecoveryService.resume` hard-codes `reviewer_role='workflow_owner'` on the `recovery_actions` insert, exactly as `/takeover` validates-and-discards `reviewerRole`. `reasonText` stays optional — Reconciliation 12. See OQ-1 if you disagree.)**

3. **Given** mandatory `Idempotency-Key` header (story 1.9) + `X-Actor-Identity` header (story 2.13 AC4), **Then** standard conventions apply. **(Declare `@RequestHeader(name = "Idempotency-Key") String idempotencyKey` (no `required=false`) + `@RequestHeader(name = "X-Actor-Identity", required = false)` + `HttpServletRequest httpRequest`, then run the six-line guard prologue from Reconciliation 3. Format validation of the key happens downstream inside `RecoveryService.resume` via `IdempotencyKeyValidator.requireValid` → `INVALID_IDEMPOTENCY_KEY`. Missing/blank header → `MISSING_IDEMPOTENCY_KEY` (400) from `requireNonBlankIdempotencyKey`.)**

4. **Given** Problem Details mapping (story 1.8), **Then** typed errors cover: `RESUME_NOT_APPLICABLE` (409), `IDEMPOTENCY_KEY_CONFLICT` (409), `ILLEGAL_TRANSITION` (409), `ACTION_NOT_ALLOWED` (409), `RUN_NOT_FOUND` (404) — contract tests check `code` + `status` + `details`, never human text. **(RECONCILED — Reconciliation 7. `ACTION_NOT_ALLOWED` DOES NOT EXIST; drop it. Ship the eight real codes listed in Reconciliation 7. All are already registered in `ProblemDetailsCatalog` — assert, do not add. `RESUME_NOT_APPLICABLE` carries `details.currentState` on the not-paused path and `details.reason ∈ {not_paused, no_paused_event_to_link, paused_event_missing_prior_state}`; assert on `code`/`status`/`details` keys only.)**

5. **Given** OpenAPI via `springdoc-openapi`, **Then** endpoint appears in regenerated OpenAPI snapshot; CI drift check (story 1.21 AC6) passes. **(Reconciliation 10. Annotate with `@Operation(operationId = "resume", …)` + `@ApiResponses` for 400/404/409 referencing `ProblemDetailsResponse`, mirroring `/takeover` (`:1973-2011`). Regenerate `openapi.json` via `-Dopenapi.snapshot.write=true`, then `npm run generate-api` in `deliveryline-frontend` and commit `schema.d.ts` — else `check:api` reds.)**

6. **Given** CLI/REST equivalence (story 1.7 AC5), **Then** Spring Shell command `deliveryline operator resume --run {runId} [--reason "..."] [--idempotency-key K]` added under `adapters.cli` with the operator subcommand grouping; contract test asserts CLI/REST identical outcomes. **(RECONCILED — Reconciliation 9. Spring Shell 4.x `@Command(name = "resume")` on `OperatorCommands` (`@CommandGroup(prefix = "deliveryline operator")`) registers `deliveryline operator resume`. `runId` is POSITIONAL `@Argument(index = 0)` mirroring the sibling `operator diagnose`, not `--run` (OQ-2). Options: `--reason`, `--idempotency-key`, `--actor-identity`, `--correlation-id`, `--format text|json`, `--verbose`. Equivalence test compares the four positional args captured from `recoveryService.resume(...)` on both surfaces — Reconciliation 4 — NOT a `WorkflowCommand` record.)**

7. **Given** ArchUnit (story 1.11), **Then** controller method does only request parsing, command construction, service invocation, response mapping — no business logic. **(The governing rule is `REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER` (`ArchitectureRuleCatalog.java:238`). Calling `application.recovery.RecoveryService` is permitted — `DeveloperTakeoverService` is already called from this controller. `requireWorkflowOwnerRole` is request-shape validation, not a domain decision, and stays within the rule (the `/takeover` + `/approve-lint` precedent). ArchUnit runs in **Failsafe**, not Surefire — [[archunit-runs-in-failsafe-not-surefire]].)**

8. **Given** the response, **Then** success returns 200 OK with typed result DTO carrying new state (the prior executing state from story 4.5 AC5), recorded `recovery_actions.id` (`rcv_` prefix), runner re-enqueue confirmation (`runnerExecutionId` if re-dispatched), stamped `correlationId`; `X-Correlation-Id` response header echoes request correlation ID. **(Bind `ResumeResponse(workflowRunId REQUIRED, currentState NOT_REQUIRED, recoveryActionId REQUIRED, resumedEventId NOT_REQUIRED, runnerExecutionId NOT_REQUIRED, correlationId NOT_REQUIRED, replayed REQUIRED)` with a static `from(String workflowRunId, ResumeRecoveryResult result)` — Reconciliation 5. `currentState` is NULLABLE per Reconciliation 6. `runnerExecutionId` is null on replay AND when auto-dispatch is off (the shared test profile) — assert both. `X-Correlation-Id` is set globally by `CorrelationIdFilter` (`:33,44-69`): assert it, write no code. Note `correlationId` is null in `@WebMvcTest` slices that don't register the filter — mirror `TakeoverResponse`'s `NOT_REQUIRED`.)**

9. **Given** the test suite, **Then** covers: happy-path resume returns 200 + state at prior executing state + recovery_actions row + re-enqueued runner, resume from non-`Paused` state returns 409 with `RESUME_NOT_APPLICABLE`, missing X-Actor-Identity falls back to local-user, idempotent replay, action-not-allowed when state forbids. **(New `ResumeEndpointContractTest` (`@WebMvcTest(controllers = WorkflowController.class)` + `@MockitoBean RecoveryService`, mirroring `TakeoverEndpointContractTest`) covering: 200 happy path (`currentState=Executing`, `recoveryActionId` matches `^rcv_`, `runnerExecutionId` present, `replayed=false`); replay (`replayed=true`, `runnerExecutionId` null, `currentState` null-tolerant); `RESUME_NOT_APPLICABLE` → 409 + `details.currentState`; `IDEMPOTENCY_KEY_CONFLICT` → 409; `RUN_NOT_FOUND` → 404; missing/blank `Idempotency-Key` → 400 `MISSING_IDEMPOTENCY_KEY`; multi-valued header → 400 `INVALID_COMMAND_PAYLOAD`; `role != workflow_owner` (incl. null/blank) → 400 `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`; unknown body field → 400; omitted `X-Actor-Identity` → captured `ActorContext.actorIdentity()=="local-operator"`. The final epic clause "action-not-allowed when state forbids" IS the `RESUME_NOT_APPLICABLE` case — Reconciliation 7. Plus a CLI/REST equivalence test, an `OperatorCliCommandRegistrationIT` pin, the 17 `@MockitoBean` slice repairs, `OpenApiSnapshotContractTest` green after regen, and an OPTIONAL real-PG `ResumeEndpointIT` seeding `Paused` via `Executing → Paused` ([[springboot-testcontainers-test-must-be-IT]]).)**

## Tasks / Subtasks

- [ ] **Task 0 — Verify the "already done" surface before writing anything (Reconciliation 1)**
  - [ ] Confirm `RecoveryService.resume` exists (`:676`), `RESUME_NOT_APPLICABLE` is mapped 409 (`ProblemDetailsCatalog.java:553-558`), `AllowedAction.RESUME_WORKFLOW` exists (`:113`), and `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` is **absent** from `ArchitectureRuleCatalog`. If any is missing, STOP — the branch is not on top of 4.5 + 4.28.
  - [ ] Confirm no Flyway migration is needed (`recovery_actions` `action_type='resume'` is a V1 CHECK slot). Create none.

- [ ] **Task 1 — REST DTOs (AC2, AC8)**
  - [ ] `adapters/rest/ResumeWorkflowRequest.java` — `record ResumeWorkflowRequest(@NotBlank @Size(max=128) String role, @Size(max=512) String reasonText)`; `@JsonIgnoreProperties(ignoreUnknown = false)`; `@Schema` on both (mirror `ApproveLintRequest.java`).
  - [ ] `adapters/rest/ResumeResponse.java` — 7-component record per AC8 with `@Schema(requiredMode=…)` on each; `static ResumeResponse from(String workflowRunId, ResumeRecoveryResult result)`. Guard the null state: `result.resultingState() == null ? null : result.resultingState().value()`. Javadoc the replay nullability (Reconciliation 6).

- [ ] **Task 2 — Controller endpoint (AC1, AC3, AC4, AC5, AC7)**
  - [ ] Add `RecoveryService` to the `WorkflowController` ctor (`:121-142`) + field, with a `// Story 4.10 —` comment.
  - [ ] Add `public ResumeResponse resume(...)` after the rich `takeover` (`:2060`): `@PostMapping(value="/{workflowRunId}/resume", consumes=…, produces=…)`, `@Operation(operationId="resume", …)`, `@ApiResponses` (400/404/409 → `ProblemDetailsResponse`).
  - [ ] Guard prologue (Reconciliation 3) → `requireWorkflowOwnerRole("resume", request.role())` → `new ActorContext(actorIdentity, ActorType.HUMAN, correlationId)` → `recoveryService.resume(workflowRunId, idempotencyKey, actor, request.reasonText())` → `ResumeResponse.from(workflowRunId, result)`.
  - [ ] **Do NOT call `workflowCommandService.resumeWorkflow`** (Reconciliation 2).

- [ ] **Task 3 — `@WebMvcTest` slice repair (Reconciliation 8)**
  - [ ] Add `@MockitoBean private RecoveryService recoveryService;` to all **17** listed slices, with the house comment idiom. Run `mvnw -o test` and confirm no `UnsatisfiedDependencyException`.

- [ ] **Task 4 — CLI `deliveryline operator resume` (AC6)**
  - [ ] Extend BOTH `OperatorCommands` constructors with `RecoveryService`, `IdempotencyKeyValidator`, `LocalActorIdentityResolver`, and a generated-idempotency-key `Supplier<String>`; update every manual `new OperatorCommands(...)` test site.
  - [ ] Add `@Command(name = "resume", exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)` with positional `@Argument(index=0) String runId` + options `--reason` (optional), `--idempotency-key`, `--actor-identity` (optional), `--correlation-id`, `--format`, `--verbose`. **Mirror `WorkflowCommands.takeover` (`:1404-1453`) — NOT `retry`** (Reconciliation 9): optional `--actor-identity` → `resolveActorIdentity(...)`, `ActorType.HUMAN` hard-coded, no `--actor-type`. Reuse `pushCorrelation`/`CorrelationScope` and the `finally { MdcKeys.endScope(...) }`.
  - [ ] Render `rcv_… resume submitted (state: <resultingState>)` + `[runner-execution: rex_…]` when non-null + `[replayed]` when replayed; `--verbose` appends `[correlation-id: …]` and `[generated-idempotency-key: …]` when the flag was omitted. Text output only appends the verbose footer (never JSON — the `diagnose` precedent, `:190-195`).
  - [ ] Pin `deliveryline operator resume` in `OperatorCliCommandRegistrationIT`.

- [ ] **Task 5 — OpenAPI + frontend client (AC5, Reconciliation 10)**
  - [ ] Regenerate: `mvnw -o test -Dtest=OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true`; review the `openapi.json` diff (expect exactly one new path + two new schemas).
  - [ ] `cd deliveryline-frontend && npm run generate-api`; commit `src/lib/api/schema.d.ts`. Verify `npm run check:api` green ([[openapi-regen-frontend-client-drift-cascade]]).

- [ ] **Task 6 — Tests (AC9)**
  - [ ] New `adapters/rest/ResumeEndpointContractTest` — the ten cases enumerated in AC9; mirror `TakeoverEndpointContractTest` (`@WebMvcTest` + `ListAppender` + `LocalActorIdentityResolver` call-through stub). Assert Problem Details `code`/`status`/`details` only, never human text.
  - [ ] CLI/REST equivalence test (Reconciliation 4): capture the four positional `recoveryService.resume(...)` args from REST (MockMvc) and CLI (`new OperatorCommands(...)`), assert equal — including `ActorContext` record equality and the `local-operator` fallback on both surfaces.
  - [ ] `OpenApiSnapshotContractTest` + `ProblemDetailsContractTest` + `WorkflowAdapterEquivalenceTest` green.
  - [ ] Optional real-PG `ResumeEndpointIT` (`*IT`) seeding `Paused` via `Executing → Paused`, asserting the `recovery_actions` row (`action_type='resume'`, `reviewer_role='workflow_owner'`) and the `recovery.resumed` event.
  - [ ] ⚠️ New `@WebMvcTest` classes null the redaction holder and poison `CapturedOutput` in a reused fork — add the identity-holder `@BeforeAll`/`@AfterAll` ([[webmvctest-redaction-holder-poisons-capturedoutput]]).

- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] REST: `INFO "REST resume received workflowRunId={} actorIdentity={} reasonLength={}"` on entry (reasonLength null-guarded — Reconciliation 12) and `INFO "REST resume success workflowRunId={} currentState={} recoveryActionId={} runnerExecutionId={} replayed={}"` on success. `WARN` on the role rejection is already emitted inside `requireWorkflowOwnerRole`. No `ERROR` — `DomainException`s are mapped by `ProblemDetailsMapper`.
  - [ ] CLI: reuse the `emit*` completion-log idiom — `INFO` with `correlationId`, `commandName='operator resume'`, `workflowRunId`, `durationMs`, `outcome=success|failure:<code>`.
  - [ ] Parameterized logging only; sanitize `workflowRunId`/`actorIdentity` via `MdcKeys.sanitizeForLog`. Never log `reasonText` prose, the idempotency key value, secrets, tokens, or PII.
  - [ ] Pin the REST entry/success lines and the role-rejection `WARN` in `ResumeEndpointContractTest` via `ListAppender` on the `WorkflowController` logger (the `TakeoverEndpointContractTest` pattern, `:106-110`).

## Dev Notes

### Relevant architecture patterns and constraints

- **`POST /{workflowRunId}/takeover` (`WorkflowController.java:1969-2060`) is the exact structural template.** Copy: annotations → guard prologue → role validation → service call → rich response → two log lines. `resume` differs only in: the service is `RecoveryService` (not `DeveloperTakeoverService`), the call is positional-with-`ActorContext` (not a command record), `reasonText` is optional, the gate role is `workflow_owner` (not `developer`), and `ResumeResponse.from` needs the runId passed in.
- **Two-endpoint trap.** `WorkflowController` has BOTH a legacy `/takeover-workflow` (thin, `WorkflowCommandService`) and a rich `/takeover` (`DeveloperTakeoverService`). The same fork exists for resume at the service layer (`WorkflowCommandService.resumeWorkflow` vs `RecoveryService.resume`) but 4.10 ships only ONE endpoint, wired to the rich path. Reconciliation 2 explains why the thin path is a silent-failure trap.
- **Idempotency has two surfaces and you touch neither.** The `recovery_actions` unique key on `idempotency_key` is the outer guard; `executeIdempotent` inside `WorkflowCommandService.resumeWorkflow` is the inner one. `RecoveryService.resume`'s Step-1 pre-check already carries the `actionType == resume` guard added by 4.5's code review (`:701-717`) — cross-action key reuse is an `IDEMPOTENCY_KEY_CONFLICT`, not a false replay. The controller passes the raw header through and lets the service validate it.
- **`ActorContext` lives in `org.dradgo.application.artifact`**, not `application.recovery`. A wrong import is the fastest way to a confusing compile error. Its canonical constructor **rejects a blank `actorIdentity` and a null `actorType`** — this is safe because `localActorIdentityResolver.resolve(...)` never returns blank (it falls back to `local-operator`) and `ActorType.HUMAN` is hard-coded on both surfaces. Do not pass the raw header through.
- **RBAC is audit-only.** `requireWorkflowOwnerRole` is a request-shape check; the controller never returns 401/403. The resolved actor identity is stamped on the audit trail, nothing more.
- **[[gate-action-needs-explicit-current-state-precondition]]** — resume's `PAUSED` precondition is enforced by `RecoveryService.resume`'s explicit current-state guard (`:766-785`), NOT by the transition table (which would let `Executing` through from several sources). The controller adds no state check of its own.
- **No `WorkflowCommand` permit, no fingerprint arm, no `EXPECTED_PERMITS` edit.** `ResumeWorkflowCommand` was already added to the sealed hierarchy by 4.5. This story adds only REST DTOs, which are not `WorkflowCommand`s.

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out` / `printStackTrace()`.
- **Where to log:** REST entry (`INFO`), REST success (`INFO`), role rejection (`WARN`, inside the shared helper), CLI completion (`INFO`, success + failure with the typed `DomainErrorCode`). The recovery-service log surface (`recovery resume start/success/replay/rejected`) is already pinned by `RecoveryLoggingContractTest` — do not duplicate it in the adapter.
- **Required context keys:** `correlationId`, `workflowRunId`, `actorIdentity`, plus `recoveryActionId` + `runnerExecutionId` on success. MDC `correlationId` is stamped by `CorrelationIdFilter` (REST) and `pushCorrelation` (CLI).
- **Forbidden:** `reasonText` prose (log its length only), idempotency-key values, secrets, tokens, PII.
- **Test contract:** `ListAppender` pins on the `WorkflowController` logger for entry/success/rejection.

### Project Structure Notes

- **New (main):** `adapters/rest/ResumeWorkflowRequest.java`, `adapters/rest/ResumeResponse.java`.
- **Modified (main):** `adapters/rest/WorkflowController.java` (ctor + `resume` method), `adapters/cli/OperatorCommands.java` (both ctors + `resume` command), `src/main/resources/openapi/openapi.json` (regenerated).
- **Modified (frontend):** `src/lib/api/schema.d.ts` (regenerated).
- **New (test):** `adapters/rest/ResumeEndpointContractTest.java`, CLI/REST equivalence test, optional `ResumeEndpointIT`.
- **Modified (test):** the 17 `@WebMvcTest` slices (Reconciliation 8), `OperatorCliCommandRegistrationIT`, any manual `new OperatorCommands(...)` site.
- **Variance:** `ResumeWorkflowRequest` carries a `role` field the epic's AC2 omits (OQ-1); the CLI takes a positional `runId` rather than the epic's `--run` flag (OQ-2); `ResumeResponse.currentState` is nullable where `TakeoverResponse.currentState` is required (Reconciliation 6 / OQ-3).

### References

- [Source: _bmad-output/planning-artifacts/epic-04-recovery.md#Story 4.10 (lines 228–244)] — AC1–AC9.
- [Source: _bmad-output/implementation-artifacts/4-5-recovery-service-resume-recovery-from-paused-state.md] — the `done` producer story; its "Double-dispatch caution" (Dev Notes) and three `[Review][Defer]` findings, one of which names 4.10 as its consumer.
- [Source: deliveryline-backend/.../adapters/rest/WorkflowController.java:86,121-142,1322-1389,1929-1967,1969-2060,2213-2226,2271-2288,2340-2380] — base path; ctor; `approve-lint` role gate; legacy retry/takeover; rich takeover template; idempotency + multi-valued-header guards; `requireWorkflowOwnerRole`.
- [Source: deliveryline-backend/.../adapters/rest/TakeoverRequest.java; TakeoverResponse.java; ApproveLintRequest.java; WorkflowStateChangeResponse.java] — request/response DTO templates.
- [Source: deliveryline-backend/.../application/recovery/RecoveryService.java:56-143,676-972,701-717,757-763,766-785,1186-1196] — class javadoc/scope; `resume`; Step-1 action-type guard; replay nullable `resultingState`; PAUSED guard; `redispatchForResume`.
- [Source: deliveryline-backend/.../application/recovery/ResumeRecoveryResult.java:12-34] — result shape + nullability contract (no `workflowRunId`).
- [Source: deliveryline-backend/.../application/artifact/ActorContext.java:5] — `(actorIdentity, actorType, correlationId)`.
- [Source: deliveryline-backend/.../application/security/LocalActorIdentityResolver.java:45,64-66,108-127,140-150] — `requireSafe` / `resolve` / `local-operator` fallback.
- [Source: deliveryline-backend/.../infrastructure/observability/CorrelationIdFilter.java:33,44-69] — `X-Correlation-Id` request→MDC→response echo.
- [Source: deliveryline-backend/.../adapters/rest/ProblemDetailsCatalog.java:35-46,251-256,553-558,590-592] — 409/404 mappings + total-coverage assertion.
- [Source: deliveryline-backend/.../domain/registry/DomainErrorCode.java:74-76,183,190,224-226,231] — `RESUME_NOT_APPLICABLE`; the comments explaining why `ACTION_NOT_ALLOWED` was rejected.
- [Source: deliveryline-backend/.../domain/registry/AllowedAction.java:105-113; application/workflow/WorkflowInspectionService.java:1856-1873] — `resume_workflow` already registered + `case PAUSED:` already wired.
- [Source: deliveryline-backend/.../adapters/cli/OperatorCommands.java:35-48,66-85,157-178,255-265] — Spring Shell 4.x group prefix; 4-arg ctor; `diagnose` positional-arg precedent; `pushCorrelation`.
- [Source: deliveryline-backend/.../adapters/cli/WorkflowCommands.java:1404-1453] — **the CLI template**: positional runId, optional `--actor-identity`, `ActorType.HUMAN` hard-coded, no `--actor-type`.
- [Source: deliveryline-backend/.../adapters/cli/WorkflowCommands.java:1326-1336] — `retry`, the ANTI-pattern (required `--actor-identity` + `--actor-type`); do not mirror.
- [Source: deliveryline-backend/.../adapters/cli/WorkflowCommands.java:2499-2550] — `resolveActorIdentity` / `resolveIdempotencyKey` fallback helpers.
- [Source: deliveryline-backend/.../adapters/persistence/RecoveryActionPersistenceAdapter.java:89; domain/id/PublicIdPrefixes.java:21] — `rcv_` minting.
- [Source: deliveryline-backend/.../src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:238,827-831] — thin-controller rule; the 4.28 lift note (do not re-add the scope lock).
- [Source: deliveryline-backend/.../src/test/java/org/dradgo/adapters/rest/TakeoverEndpointContractTest.java:63-110] — `@WebMvcTest` + `@MockitoBean` + `ListAppender` template.
- [Source: deliveryline-backend/.../src/test/java/org/dradgo/foundation/CliRestEquivalenceContractTest.java:133-203] — equivalence idiom (and why it does not transfer verbatim — Reconciliation 4).
- [Source: deliveryline-backend/.../src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java:56-58,135-152; deliveryline-frontend/package.json:19] — snapshot path, write flag, FE regen script.
- [Source: sprint-status.yaml] — 4-10 backlog (this story); 4-5/4-6/4-28 done (dependencies satisfied); 4-8 ready-for-dev (the `Paused` producer, not required to ship 4.10 — Reconciliation 11); 4-22 backlog (FE deferred).

### Open Questions (for Alex — do not block dev; provisional bindings applied)

- **OQ-1 — does `ResumeWorkflowRequest` carry a `role` field?** Epic AC2 literally says `{ reasonText? }`. Provisional bind: **include `@NotBlank String role` validated to `workflow_owner` then discarded**, mirroring `ApproveLintRequest` + `/takeover`'s `reviewerRole` — every other operator-governance gate in this codebase validates the role at the boundary, and `resume_workflow` is surfaced ONLY to `ROLE_WORKFLOW_OWNER` in `baseActionMatrix`. Downside: `role` becomes a REQUIRED wire field, so the 4.22 Decision Bar must send it. Confirm, or drop `role` and rely solely on the allowed-actions gate.
- **OQ-2 — CLI `runId`: positional or `--run` flag?** Epic AC6 says `--run {runId}`. Every shipped run-scoped CLI command takes it positionally: `operator diagnose` (`:167`), `workflow retry` (`:1327`), `workflow takeover` (`:1414`). Provisional bind: **positional `@Argument(index = 0)`** — the epic's `--run` appears to be prose shorthand, not a contract. Confirm, or accept the flag and diverge from every sibling.
- **OQ-3 — `ResumeResponse.currentState` nullable, or fix `RecoveryService` first?** 4.5 deferred the replay-path `resultingState` `.orElse(null)` (`RecoveryService.java:761`) to "the resume REST/CLI consumer" — this story. Provisional bind: **expose it as nullable (`NOT_REQUIRED`) and document why**, keeping 4.10 a pure adapter story. Alternative: make the replay path re-read the `recovery_actions` row's resulting event and derive a non-null state, upgrading the field to `REQUIRED` (an `application/` change that widens 4.10's scope).
- **OQ-4 — should the CLI live on `OperatorCommands` or `WorkflowCommands`?** Epic AC6 says `deliveryline operator resume`. Provisional bind: **`OperatorCommands`**, accepting that it becomes the first mutating command there (ctor fan-out, Reconciliation 9). Alternative: `WorkflowCommands` (already has `RecoveryService` + the mutation helpers wired, zero fan-out) at the cost of the command reading `deliveryline resume` and contradicting the epic.
- **OQ-5 — ship `/resume` before `pause` (4.8) exists?** The endpoint is unreachable in production until something transitions a run into `Paused` (Reconciliation 11). Provisional bind: **ship it dark**, mirroring 4.5's accepted posture. Confirm, or sequence 4.8 ahead of 4.10.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story workflow.

### Debug Log References

### Change Log

### Completion Notes List

### File List
