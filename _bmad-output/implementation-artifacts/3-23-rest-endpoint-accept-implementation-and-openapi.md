# Story 3.23: REST Endpoint — `accept-implementation` + OpenAPI

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a frontend developer (Decision Bar `implementation_review` mode in story 3.28) and CLI user,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/accept-implementation` wired to `WorkflowCommandService.acceptImplementation` (the story 3.20 `TechnicalApprovalService.acceptImplementation` service),
so that UI mutation hooks (story 2.6 AC6) and the CLI `deliveryline accept-implementation` command have a stable contract, developer acceptances flow through the same idempotency + Problem Details + OpenAPI conventions as `approve-spec` (story 2.13), and CLI/REST equivalence (story 1.7 AC5) is maintained.

## Acceptance Criteria

> Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md` § Story 3.23 (lines 464–482). The epic AC text is **idealized**; several items drift from the live service contract established by story 3.20 (which is `done`) and the story 2.13 `approve-spec` REST precedent. **The reconciliations in Dev Notes (R1–R8) WIN where they conflict with the literal AC text — read them before implementing.** This is the **accept** twin of story 3.24 (`reject-implementation` REST, `ready-for-dev`); keep the two controller handlers symmetric.

1. **Given** `WorkflowController` (the existing `adapters.rest` controller, extended by story 2.13), **Then** a new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/accept-implementation` — kebab-case action, mirroring the `approve-spec` handler shape exactly (`@PostMapping`, JSON consume/produce, `@Operation`/`@ApiResponses`).
2. **Given** the request body, **Then** a new typed DTO `AcceptImplementationRequest { artifactId, expectedArtifactVersion, expectedContextBundleVersion, reviewerRole, reason? }` (camelCase JSON, `@JsonIgnoreProperties(ignoreUnknown = false)`), mirroring `ApproveSpecRequest` field-for-field with Bean Validation. `reason` is optional (`@Size(max = 1024)`); there is **no** `taggedFeedback`/`reasonText` (those belong to the reject twin, 3.24).
3. **Given** the mandatory `Idempotency-Key` header + optional `X-Actor-Identity` header, **Then** the same conventions as `approve-spec` / story 3.24 apply: missing/blank `Idempotency-Key` → 400 `MISSING_IDEMPOTENCY_KEY`; malformed → 400 `INVALID_IDEMPOTENCY_KEY`; multi-valued / comma-folded `Idempotency-Key` or `X-Actor-Identity` → 400 `INVALID_COMMAND_PAYLOAD`; unsafe `X-Actor-Identity` → fail-closed via `LocalActorIdentityResolver.requireSafe`; correlation id from MDC.
4. **Given** `X-Actor-Identity` (story 2.13 AC4 deferred-auth model), **Then** it identifies the actor; missing/blank falls back to the configured local-user (`LocalActorIdentityResolver`, property `deliveryline.security.local-actor-identity`, default `local-operator`). **RECONCILED (R4): the body `reviewerRole` must equal `developer`** — anything else (incl. blank) → 400 `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`. Do **NOT** route `reviewerRole` through `approvalReviewerRoleResolver.resolveFor(...)` (its blank-default → `product_reviewer` would mask the mismatch).
5. **Given** Problem Details mapping (story 1.8), **Then** coverage matches the live `approve-spec` set plus the one new REST-layer code (R4): `MISSING_IDEMPOTENCY_KEY` (400), `INVALID_IDEMPOTENCY_KEY` (400), `INVALID_COMMAND_PAYLOAD` (400 — bad payload, header duplication, **and the artifact-type guard when `artifactId` points to a `spec`** — R7), `INVALID_ID_PREFIX` (400), `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400 — NEW; when `reviewerRole != developer`), `RUN_NOT_FOUND` (404), `ARTIFACT_RECORD_NOT_FOUND` (404), `APPROVAL_VERSION_MISMATCH` (409), `IDEMPOTENCY_KEY_CONFLICT` (409), `ARTIFACT_PR_LINK_MISMATCH` (409 — prOutput PR-link gate, R8), `ILLEGAL_TRANSITION` (409 — covers the "state forbids the action" case, R3), `WORKFLOW_RUN_TERMINAL` (409), `ARTIFACT_PAYLOAD_UNAVAILABLE` (**503** — R6, accept reads the payload/eligibility). Contract tests check `code` + `status` + `details`, never human text.
6. **Given** OpenAPI via `springdoc-openapi`, **Then** the endpoint (`operationId=acceptImplementation`) + the `AcceptImplementationRequest` schema + the new `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` error code appear in the regenerated committed snapshot `src/main/resources/openapi/openapi.json`; the `OpenApiSnapshotContractTest` drift gate (story 1.21 AC6) passes; the generated frontend client `deliveryline-frontend/src/lib/api/schema.d.ts` is regenerated from the snapshot and committed.
7. **Given** CLI/REST equivalence (story 1.7 AC5), **Then** a Spring Shell command `deliveryline accept-implementation --run {runId} --artifact-id {artifactId} --expected-artifact-version N --expected-context-bundle-version M --reviewer-role developer [--reason "..."] [--idempotency-key K] [--actor-identity ...] [--correlation-id ...]` is added under `adapters.cli.WorkflowCommands` mirroring `approve-spec`; a contract test asserts CLI and REST produce identical command records + identical typed errors. (CLI flag names mirror the existing `approve-spec` long names — see R-CLI.)
8. **Given** ArchUnit (story 1.11), **Then** the controller method stays thin — request parsing, controller-boundary request validation (header guards + the `reviewerRole == developer` check), command construction, service invocation, response mapping. No domain decisions, no persistence, no transition logic (those live in `TechnicalApprovalService`).
9. **Given** a successful acceptance, **Then** the endpoint returns **200 OK** with the existing `WorkflowStateChangeResponse { workflowRunId, currentState, correlationId }`. **`currentState` is artifact-type-dependent: `Executing` for `implementationPlan` acceptance (plan approved → dispatch PR/output runner) and `Completed` for `prOutput` acceptance (merge-ready handoff).** The `correlationId` is stamped from the request MDC and `WorkflowStateChangeResponse` carries it; `X-Correlation-Id` response behavior matches `approve-spec` (no new header work). **RECONCILED (R2): the response does NOT carry the `apr_` approval id** — the live service maps `ApprovalResult → WorkflowStateChangeResult`, discarding it, exactly as `approve-spec` does (see OQ-1).
10. **Given** the test suite, **Then** it covers: happy-path acceptance of an `implementationPlan` → 200 + `currentState=Executing`; happy-path acceptance of a `prOutput` → 200 + `currentState=Completed`; missing `Idempotency-Key`; missing/blank `reviewerRole` and non-`developer` role → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`; spec `artifactId` → `INVALID_COMMAND_PAYLOAD` (R7); `APPROVAL_VERSION_MISMATCH`; `ARTIFACT_PAYLOAD_UNAVAILABLE` (503); `ARTIFACT_PR_LINK_MISMATCH` (prOutput); `IDEMPOTENCY_KEY_CONFLICT`; idempotent replay (same key + fingerprint → same 200 body); `ILLEGAL_TRANSITION` when state forbids; request schema validation failures; CLI/REST parity.

## Tasks / Subtasks

- [x] **Task 1 — `AcceptImplementationRequest` REST DTO** (AC: 2)
  - [x] Create `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/AcceptImplementationRequest.java` mirroring `ApproveSpecRequest` (`adapters/rest/ApproveSpecRequest.java:38–44`) exactly: `@JsonIgnoreProperties(ignoreUnknown = false)` record with `@NotBlank @Size(max=128) String artifactId`, `@NotNull @Positive Integer expectedArtifactVersion`, `@NotNull @Positive Integer expectedContextBundleVersion`, `@Size(max=128) String reviewerRole`, `@Size(max=1024) String reason`.
  - [x] Javadoc: note the verbose wire names `expectedArtifactVersion`/`expectedContextBundleVersion` map to the short `artifactVersion`/`contextVersion` command fields (Trap T1, already documented on `AcceptImplementationCommand`); that `reviewerRole` must be `developer` (controller enforces — Task 3); and that `reason` is free-form and **excluded from the idempotency fingerprint** (wording edits replay idempotently — mirrors `ApproveSpecCommand.reason`).

- [x] **Task 2 — New REST-layer `DomainErrorCode` `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (three-sites)** (AC: 5) — see [[new-domainerrorcode-three-sites]]
  - [x] **COORDINATION (R6):** this code is **SHARED with story 3.24** (`reject-implementation` REST, `ready-for-dev`). Grepped: 3.24 had NOT landed it (3.24 still `ready-for-dev`), so **3.23 lands first and CREATES** the code + the `reviewerRole == "developer"` controller validation idiom; 3.24 will REUSE both.
  - [x] Site 1: added `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` to `domain/registry/DomainErrorCode.java`. Site 2: `register(...)` in `adapters/rest/ProblemDetailsCatalog.java` with `HttpStatus.BAD_REQUEST`, title "Invalid reviewer role for endpoint", `retryable=false`. Site 3: added `"INVALID_REVIEWER_ROLE_FOR_ENDPOINT": "https://deliveryline.local/problems/invalid-reviewer-role-for-endpoint"` to `registry-api-schema-placeholders.json#problemTypeUris`.
  - [x] **No `ARTIFACT_TYPE_MISMATCH` code (R7).** Spec artifact → service raises `INVALID_COMMAND_PAYLOAD`; no extra code added.
  - [x] Verified the three sites with `RegistryContractTest` (green) + `-Pfoundation-gate` (PowerShell).

- [x] **Task 3 — `acceptImplementation` controller handler** (AC: 1, 3, 4, 5, 8, 9)
  - [x] Added `acceptImplementation(...)` `@PostMapping("/{workflowRunId}/accept-implementation")` to `WorkflowController`, copied from `approveSpec` with the deltas:
    - Body type `AcceptImplementationRequest`; service call `workflowCommandService.acceptImplementation(...)`.
    - Header guards reused verbatim in the same call order as `approveSpec`; correlation from `MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID))`.
    - **Role validation (R4):** new private static `requireDeveloperReviewerRole(...)` — trims the raw `reviewerRole`; if null/blank/`!= "developer"` it WARN-logs then throws `DomainException(INVALID_REVIEWER_ROLE_FOR_ENDPOINT, …)` with `details{field, expected, actual}` (LinkedHashMap). Does NOT use `approvalReviewerRoleResolver.resolveFor`.
    - `AcceptImplementationCommand` built positionally in the confirmed Trap-T1 order; result mapped via `WorkflowStateChangeResponse.from(...)` (R2).
    - INFO entry/exit logs `"REST accept-implementation received/success …"` (sanitized).
  - [x] `@Operation(operationId = "acceptImplementation", …)` + `@ApiResponses` for 200/400(+`INVALID_REVIEWER_ROLE_FOR_ENDPOINT`)/404/409(+`ARTIFACT_PR_LINK_MISMATCH`)/503(`ARTIFACT_PAYLOAD_UNAVAILABLE`, kept — R6/R8).

- [x] **Task 4 — `deliveryline accept-implementation` CLI command** (AC: 7)
  - [x] Added `acceptImplementation(...)` `@Command(name = "accept-implementation", …)` to `adapters/cli/WorkflowCommands.java`, copied from `approveSpec`. Options mirror `approve-spec`'s long names; `--reviewer-role` is **required** and validated with the same `requireDeveloperReviewerRole` idiom (typed `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`, not `resolveFor`). Output reads `result.currentState().value()` (not hardcoded). Telemetry via `emitSuccess`/`emitFailure`/`codeFor` + `WorkflowCliExitStatusExceptionMapper.BEAN_NAME`.
  - [x] No new constructor dependency — reused the already-injected services.

- [x] **Task 5 — OpenAPI snapshot + generated TS client regen** (AC: 6)
  - [x] Ran `scripts/regen-openapi.sh` in WSL2 (backend snapshot via `failsafe:integration-test` + `-Dopenapi.snapshot.write=true` + `-Djacoco.skip=true`), then `npm run generate-api` from Windows PowerShell (node-bin shim owner). Ran `prettier --write` on the regenerated `schema.d.ts`.
  - [x] Both files regenerated + committed-to-tree: `src/main/resources/openapi/openapi.json` (+`acceptImplementation` operation, `AcceptImplementationRequest` schema, `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` problem code) + `deliveryline-frontend/src/lib/api/schema.d.ts`.
  - [x] Extended `OpenApiSnapshotContractTest`'s command-surface assertion with `.contains("acceptImplementation")`.

- [x] **Task 6 — `CommandModelSymmetryFoundationContract` REST round-trip (foundation-gate lockstep)** (AC: 6, 8)
  - [x] Added `captureAcceptImplementation(path, idempotencyKey, body)` helper + an expected-`AcceptImplementationCommand` block (body carries `"reviewerRole": "developer"`) inside `everyWorkflowCommandPermitRoundTripsThroughRestAsTheCanonicalRecord`; updated the stale "deferred" comment to record 3.23 wired the accept round-trip (reject stays deferred to 3.24).
  - [x] Verified with `-Pfoundation-gate` (green).

- [x] **Task 7 — Contract / endpoint tests** (AC: 10)
  - [x] Created `AcceptImplementationEndpointContractTest.java` (`@WebMvcTest`) — 16 tests covering both happy paths (plan→`Executing`, prOutput→`Completed`), command-arg capture (HUMAN, `developer`, verbose→short version mapping, reason passthrough), `MISSING_IDEMPOTENCY_KEY`, blank + non-developer `reviewerRole`→`INVALID_REVIEWER_ROLE_FOR_ENDPOINT`, spec→`INVALID_COMMAND_PAYLOAD` (R7), schema-validation `INVALID_COMMAND_PAYLOAD`, `APPROVAL_VERSION_MISMATCH`, `ARTIFACT_PAYLOAD_UNAVAILABLE` (503), `ARTIFACT_PR_LINK_MISMATCH` (409), `IDEMPOTENCY_KEY_CONFLICT`, idempotent replay, `ILLEGAL_TRANSITION`, `RUN_NOT_FOUND`, actor fallback, correlation propagation. All green.
  - [x] CLI/REST parity test added to `CliRestEquivalenceContractTest` — identical `reviewerRole=developer` payload to REST + CLI, `AcceptImplementationCommand` records asserted `isEqualTo`. Green under `-Pfoundation-gate`.
  - [x] `OpenApiSnapshotContractTest` (Failsafe/Testcontainers) green after regen.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at the new controller boundary only (service-layer logging from 3.20 not duplicated).
  - [x] Parameterized logging throughout (`log.info("...", arg1, arg2)`).
  - [x] `INFO` `"REST accept-implementation received/success"` + `WARN` audit line before the `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` throw.
  - [x] Required context keys carried (correlationId/workflowRunId/idempotencyKey/actorIdentity/actorType/artifactId).
  - [x] `reason` never logged; all user-supplied strings sanitized via `MdcKeys.sanitizeForLog`.
  - [x] Contract test asserts the INFO received/success lines, the WARN reviewer-role line, AND that the `reason` value never appears in any INFO line.

## Dev Notes

### Reconciliations & Decisions (READ FIRST — these override the literal epic AC text)

- **R1 — Response `currentState` is artifact-type-dependent: `Executing` for `implementationPlan`, `Completed` for `prOutput`.** This is the key asymmetry vs the reject twin (3.24, which lands both kinds in `Executing`). The live `WorkflowCommandService.acceptImplementation` → `WorkflowStateChangeResult` with `resultingState` re-read from the run: prOutput acceptance transitions `WaitingForReview → Completed` (merge-ready handoff; the 3.16 Linear-sync post-commit hook auto-fires); implementationPlan acceptance transitions `WaitingForReview → Executing` then `dispatchImplementation` enqueues the PR/output runner. The replay path also re-reads the current state (Trap T2 from 3.20). Epic AC9's `Executing`-for-plan / `Completed`-for-prOutput is **correct** here. [Source: `3-20-...accept-implementation...md` AC7/AC8; `WorkflowCommandService.acceptImplementation` + `replayAcceptImplementation`; `TechnicalApprovalService.acceptImplementation`]
- **R2 — Response DTO is the existing `WorkflowStateChangeResponse` (workflowRunId, currentState, correlationId); NO `apr_` id.** `acceptImplementationInternal` maps `ApprovalResult → WorkflowStateChangeResult`, discarding `ApprovalResult.approvalId()`. This mirrors `approve-spec` exactly (same response record). Surfacing the `apr_` id would require widening `WorkflowStateChangeResult` + `WorkflowStateChangeResponse`, which fans out to approve/reject-spec/retry/takeover responses AND the generated TS client — disproportionate for this thin-endpoint story. **Decision: mirror `approve-spec`, return state only. See OQ-1.**
- **R3 — "State forbids the action" surfaces as `ILLEGAL_TRANSITION` (409) / `WORKFLOW_RUN_TERMINAL` (409), NOT a new `ACTION_NOT_ALLOWED` code.** The service relies on the state machine: accepting from a state with no legal `→ Completed`/`→ Executing` edge (e.g. an already-`Completed`/terminal run) raises `ILLEGAL_TRANSITION` (or `WORKFLOW_RUN_TERMINAL`). A controller-side allowed-actions pre-check would be **business logic in the controller** (violates AC8 + the story 1.11 ArchUnit thin-controller rule) and diverges from the `approve-spec` precedent. **Decision: no allowed-actions pre-check; document `ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL` in the 409 set.**
- **R4 — One NEW REST-layer `DomainErrorCode`: `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`.** This is controller-boundary **request validation** (not a domain decision — ArchUnit-safe): `reviewerRole` must equal `developer`; anything else (incl. blank → would otherwise default to `product_reviewer`) → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400). Do NOT use `ApprovalReviewerRoleResolver.resolveFor` here (its blank-fallback masks the mismatch). The service keeps its own behavior (it accepts the `reviewerRole` from the command verbatim and persists `developer`); the controller's earlier, more specific typed error is additive request-shape validation, not a replacement.
- **R5 — There is no taxonomy on accept.** Unlike the reject twin (3.24), `accept-implementation` has no `taggedFeedback`/`RejectionTaxonomy` field, so **none** of 3.24's `INVALID_REJECTION_TAXONOMY` / `MISSING_REJECTION_TAXONOMY` / Jackson-enum-name (`R5` of 3.24) concerns apply here. The only free-text field is the optional `reason`.
- **R6 — `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` is SHARED with story 3.24 (`reject-implementation` REST, `ready-for-dev`).** Whichever story is dev'd first CREATES the code + the controller `reviewerRole == developer` validation idiom; the other REUSES both (analogous to how 3.20/3.21 shared `TechnicalApprovalService`). **Before adding the code, grep for it** — if 3.24 already landed it, skip Task 2's three-sites work and reuse. Keep the two endpoint handlers symmetric. The pre-existing `/takeover-workflow` + `/retry-workflow` endpoints in the controller are the **story 1.15 command endpoints, NOT story 3.25's `/takeover`** — do not touch them.
- **R7 — Artifact-type guard surfaces as `INVALID_COMMAND_PAYLOAD` (400), NOT `ARTIFACT_TYPE_MISMATCH`.** `ARTIFACT_TYPE_MISMATCH` does NOT exist as a `DomainErrorCode` (only `RUNNER_ARTIFACT_TYPE_MISMATCH`, unrelated). When `artifactId` points to a `spec` artifact, `TechnicalApprovalService.acceptImplementation` raises `INVALID_COMMAND_PAYLOAD` (3.20 AC2/Task 3 step 3, `details.reason='technical_approval_requires_implementation_artifact'`). So epic 3.23 AC5's `ARTIFACT_TYPE_MISMATCH (400)` reconciles to `INVALID_COMMAND_PAYLOAD (400)`. No new code, no controller-side type check (the service owns it). [Source: `3-20-...md` AC2; `TechnicalApprovalService.acceptImplementation`]
- **R8 — `ARTIFACT_PAYLOAD_UNAVAILABLE` is **503**, and `ARTIFACT_PR_LINK_MISMATCH` (409) applies — accept DOES read the payload (key delta from the reject twin).** Epic AC5 lists `ARTIFACT_PAYLOAD_UNAVAILABLE (409)`, but the live `ProblemDetailsCatalog` registers it as **503** (retryable) and the `approve-spec` `@ApiResponses` documents it under 503 — use **503**. Unlike `reject-implementation` (3.24, which drops the 503 line because rejection never reads the payload), `accept-implementation` runs the story 1.12 eligibility gate (`ArtifactService.isApprovalEligible`) → `ARTIFACT_PAYLOAD_UNAVAILABLE` and, for `prOutput` only, the story 3.15 PR-link gate (`IntegrationLinkService.assertArtifactPrLinkMatches`) → `ARTIFACT_PR_LINK_MISMATCH` (409). Both gates live in the service (already implemented by 3.20); the controller only documents them in `@ApiResponses`. The OQ-1 fail-closed self-match caveat from 3.20 is already baked into the service — no REST-layer concern. [Source: `ProblemDetailsCatalog` ARTIFACT_PAYLOAD_UNAVAILABLE→503; `approveSpec` `@ApiResponses` 503 line; `3-20-...md` AC6 + Review Findings (PR-link self-match defer)]

### Architecture patterns & constraints

- **Thin controller (AC8).** The handler does: header guards → identity resolution → `reviewerRole == developer` boundary validation → command construction → `workflowCommandService.acceptImplementation(...)` → `WorkflowStateChangeResponse.from(...)`. NO transition logic, NO persistence, NO eligibility/PR-link/version *business* rules (the service owns those; the controller only does request-shape validation with a typed code). `WorkflowController` is pinned to `adapters.rest` + `@RestController` by the story 1.11 ArchUnit rule `REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER` (`architecture/ArchitectureRuleCatalog.java:223–238`); controllers may only call `WorkflowCommandService`/`WorkflowInspectionService`/`ApprovalService`/`ClarificationService`/`ArtifactOperationService`. Verify ArchUnit in Failsafe ([[archunit-runs-in-failsafe-not-surefire]]).
- **Idempotency + Problem Details are inherited, not re-implemented.** `WorkflowCommandService.executeIdempotent` handles replay vs `IDEMPOTENCY_KEY_CONFLICT` (and `replayAcceptImplementation` re-reads the artifact-type-dependent state — 3.20 Trap T2); `ProblemDetailsMapper` + `ProblemDetailsCatalog` map every `DomainException` to RFC-7807. The controller only adds the one new code to the catalog (Task 2) and constructs commands. The `reason` field is **excluded from the idempotency fingerprint** (`WorkflowCommandFingerprintFactory` case `AcceptImplementationCommand` appends workflowRunId/artifactId/artifactVersion/contextVersion/reviewerRole, NOT reason) — wording edits replay idempotently.
- **No service / persistence / migration / registry / transition-table changes.** The entire service + persistence + version-binding + eligibility + PR-link + allowed-action + `AllowedAction.ACCEPT_IMPLEMENTATION` substrate already exists (story 3.20, `done`). This story is **REST adapter + CLI adapter + 1 problem code (if not already created by 3.24) + foundation round-trip + OpenAPI regen only.** Do NOT add a Flyway migration; do NOT touch `TechnicalApprovalService`, `AcceptImplementationCommand`, `WorkflowCommandService.acceptImplementation`, `WorkflowCommandFingerprintFactory`, or `AllowedAction`.
- **Command field order (Trap T1).** `AcceptImplementationCommand` ctor order is `workflowRunId, artifactId, artifactVersion, contextVersion, actorIdentity, actorType, idempotencyKey, correlationId, reviewerRole, reason` — the short `artifactVersion`/`contextVersion` names ARE the expected versions (the verbose `expected…` names belong to the REST DTO). This order is identical to the `approve-spec` call site, so the positional command construction copies cleanly. [Source: `commands/AcceptImplementationCommand.java:29–40`]
- **Deferred-auth model.** `X-Actor-Identity` is optional with property fallback (`LocalActorIdentityResolver`, default `local-operator`); RBAC stays audit-only (no 401/403). `ActorType.HUMAN` is hard-coded for this reviewer-decision mutation (mirror `approve-spec`). The `reviewerRole` (`developer`) is body-supplied and validated, NOT header-derived.

### Exact patterns to mirror (file:line)

- **REST handler:** `WorkflowController.approveSpec` — `adapters/rest/WorkflowController.java:404–445` (header guards ~410–419, command build ~428–439, response map). The new handler is a near-verbatim copy with the R4 `reviewerRole == developer` check **replacing** the `approvalReviewerRoleResolver.resolveFor(...)` call, `ARTIFACT_PR_LINK_MISMATCH` added to the 409 `@ApiResponses` line, and the operationId/path/DTO/service-method swapped.
- **REST DTO:** `ApproveSpecRequest` — `adapters/rest/ApproveSpecRequest.java:38–44` (same field set; `reason` optional).
- **Response DTO:** `WorkflowStateChangeResponse` — `adapters/rest/WorkflowStateChangeResponse.java:13–23` (reuse as-is, `from(WorkflowStateChangeResult)`; R2).
- **Header validation helpers (reuse verbatim):** `requireNonBlankIdempotencyKey` (`WorkflowController.java:655–668`), `rejectMultiValuedIdempotencyKeyHeader` / `rejectMultiValuedActorIdentityHeader` (~693–733), `LocalActorIdentityResolver.requireSafe`/`resolve`.
- **Problem-code registration:** `ProblemDetailsCatalog` `register(metadata, DomainErrorCode.<X>, HttpStatus.BAD_REQUEST, "...", false)` — mirror the `MISSING_REJECTION_TAXONOMY` registration. The catalog asserts it maps **every** `DomainErrorCode` (so a new enum value WITHOUT a `register(...)` fails fast at boot).
- **CLI command:** `WorkflowCommands.approveSpec` — `adapters/cli/WorkflowCommands.java` (`@Command(name="approve-spec")`, option set + `resolveActorIdentity`/`resolveIdempotencyKey`/`pushCorrelation` + telemetry).
- **Endpoint contract test:** `ApproveSpecEndpointContractTest` — `src/test/java/org/dradgo/adapters/rest/ApproveSpecEndpointContractTest.java` (WebMvcTest wiring, actor-resolver stub, body fixtures, command-arg capture, Problem-Details assertions).
- **CLI/REST parity test:** `CliRestEquivalenceContractTest.approveSpecCommandRecordIsEqualAcrossRestAndCliForTheSamePayload` — `src/test/java/org/dradgo/foundation/CliRestEquivalenceContractTest.java` (REST capture → CLI capture → `isEqualTo`).
- **Foundation round-trip:** `CommandModelSymmetryFoundationContract` — `src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java` (`captureApprove` 380–395 + the `expectedApprove` block 154–190; `EXPECTED_PERMITS` 69–78 already has `AcceptImplementationCommand`).
- **OpenAPI drift gate + regen:** `OpenApiSnapshotContractTest` — `src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java` (snapshot path `src/main/resources/openapi/openapi.json`, `-Dopenapi.snapshot.write=true` bootstrap, additive command-surface assertion).
- **Service consumed (do not modify):** `WorkflowCommandService.acceptImplementation` — `application/workflow/WorkflowCommandService.java:145–156` (+ `acceptImplementationInternal` ~304–316, `replayAcceptImplementation`); `TechnicalApprovalService.acceptImplementation` — `application/approval/TechnicalApprovalService.java`.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched surfaces observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - REST controller boundary → `INFO` "REST accept-implementation received/success" with sanitized run/artifact/actor ids + resulting `currentState` (mirror `approve-spec`); the typed boundary rejection (`INVALID_REVIEWER_ROLE_FOR_ENDPOINT`) → at least a `WARN`/`INFO` audit line before the `DomainException` is thrown.
  - State-machine transitions / eligibility / PR-link / persistence → already logged by the service (do not duplicate at the controller).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus `artifactId`.
- **Forbidden in log output:** the reviewer-supplied `reason` text, payload bytes, secrets/tokens, raw PII. Sanitize all user-supplied strings via `MdcKeys.sanitizeForLog`.
- **Test contract:** new logging surfaces must be pinned by at least one focused test (list-appender or Spring Boot `OutputCaptureExtension`).

### Project Structure Notes

- **New files:**
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/AcceptImplementationRequest.java`
  - `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/AcceptImplementationEndpointContractTest.java`
- **Modified files:**
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (+`acceptImplementation` handler)
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` (+`accept-implementation` command)
  - `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (+`INVALID_REVIEWER_ROLE_FOR_ENDPOINT` — **only if 3.24 has not already added it**)
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (+1 `register(...)` — only if not already present)
  - `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (+1 problem-type URI — only if not already present)
  - `deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java` (+`captureAcceptImplementation` round-trip; update the deferred-comment)
  - `deliveryline-backend/src/test/java/org/dradgo/foundation/CliRestEquivalenceContractTest.java` (+accept parity test)
  - `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated — new operation + request schema + error code)
  - `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated from the snapshot)
  - `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java` (optional `.contains("acceptImplementation")` assertion)
- **No conflicts** with the unified hexagonal structure: adapters depend on application ports/services; no `adapters → adapters` cross-import beyond the existing controller↔catalog↔mapper triad.

### Gate / verification notes (Windows + this repo)

- Use **PowerShell** for all build/test gates — the RTK hook corrupts only the Bash tool ([[rtk-hook-only-matches-bash]]).
- New `DomainErrorCode` (if 3.24 didn't land it first) → verify the three sites with `-Pfoundation-gate` ([[new-domainerrorcode-three-sites]]); `RegistryContractTest` cross-checks enum ↔ catalog ↔ manifest; `ProblemDetailsCatalog` self-asserts it maps every enum value at boot.
- OpenAPI regen is the failure-prone step: run via the `test`/`integration-test` **lifecycle phase**, never the direct `surefire:test`/`failsafe:integration-test` goal ([[maven-arglineation-goal-crash]]); coordinate the backend-snapshot shell vs the npm-generate shell ([[openapi-regen-platform-shim]]). `OpenApiSnapshotContractTest` + `CommandModelSymmetryFoundationContract` + `CliRestEquivalenceContractTest` are boot/foundation-gate tests (Failsafe/Testcontainers) — verify in a clean/Linux env, not just locally ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).
- One un-formatted frontend file (the regenerated `schema.d.ts`) cascades through `format-static-checks` → `doctor-smoke` → every downstream CI job — run `prettier --write` before pushing ([[prettier-gate-cascades-ci]]); regenerate the frontend lockfile on Linux if `npm install` touches it ([[frontend-lockfile-cross-platform]]).
- Focused fast tier (PowerShell): `./mvnw.cmd -pl deliveryline-backend -o -Dtest='AcceptImplementationEndpointContractTest,ApproveSpecEndpointContractTest,RegistryContractTest' -Dsurefire.failIfNoSpecifiedTests=false test`, then `./mvnw.cmd -pl deliveryline-backend -Pfoundation-gate verify` once before the PR.
- Commit without the Claude co-author trailer ([[commit-no-claude-coauthor]]).

### Coordination with the reject twin (3.24)

3.23 (accept) and 3.24 (reject) are symmetric thin REST+CLI adapters over the `done` 3.20/3.21 services, both currently `ready-for-dev`. They share exactly one artifact — the `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` code + the `reviewerRole == developer` controller idiom (R6). **First-to-land creates it; the second reuses.** Otherwise the surface is disjoint: 3.23 has the optional `reason` + `Completed`/`Executing` split + the 503 payload gate + `ARTIFACT_PR_LINK_MISMATCH`; 3.24 has `taggedFeedback`/`reasonText` + the two taxonomy codes + always-`Executing` + no 503. Read [[story-3-24-reject-implementation-rest-reconciliations]] and [[epic3b-command-and-approval-wiring-fanout]] before starting.

### Previous-story intelligence

- **Story 3.20 (`TechnicalApprovalService.acceptImplementation`, done)** — the direct service this endpoint surfaces. It built `AcceptImplementationCommand` (sealed permit + fingerprint case), `WorkflowCommandService.acceptImplementation` + `replayAcceptImplementation`, `AllowedAction.ACCEPT_IMPLEMENTATION`, the `developer` role + matrix branch, and deferred the REST/CLI/OpenAPI surfacing **explicitly to this story** (3.20 Scope guardrails + Completion Note on Contract #6). No new DomainErrorCode/Flyway/openapi was needed at 3.20; the REST surfacing is where the OpenAPI snapshot finally changes. Its OQ-1 PR-link gate is a fail-closed active-link self-match (Alex-confirmed pilot scope) — already in the service, no REST concern.
- **Story 2.13 (`approve-spec`/`reject-spec` REST, done)** — the master template for the controller handler, DTO, header guards, deferred-auth model, and the `REST_CONTROLLERS_STAY_THIN...` ArchUnit rule. `approve-spec` is the closest analog (single optional `reason`, version-binding, payload-eligibility 503).
- **Story 3.24 (`reject-implementation` REST, ready-for-dev)** — the symmetric twin created 2026-06-14; shares `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (R6). Its R1–R8 reconciliations are the mirror image of these.
- **Story 1.7 (shared command model, done)** — `CommandModelSymmetryFoundationContract` (REST round-trip per surfaced command) + `CliRestEquivalenceContractTest` (CLI≡REST) are the foundation-gate symmetry pins this story extends.

### Git intelligence

Recent commits land per-story prefixed `feat(3-NN): …` or `Story N.M: <title>` in one or two clean commits. **No Co-Authored-By Claude trailer in this repo** ([[commit-no-claude-coauthor]]). Spotless + Checkstyle run on `verify` — run `spotless:apply` before pushing ([[checkstyle-suppressions-line-anchored]], [[pom-xml-comment-no-double-dash]]). Run all gates via PowerShell, not the RTK-hooked Bash tool ([[rtk-hook-only-matches-bash]]); reproduce CI on WSL2 Linux before claiming green ([[wsl-linux-ci-reproduction]], [[verify-ci-fixes-in-clean-env]]).

Memory refs: [[epic3b-command-and-approval-wiring-fanout]], [[story-3-24-reject-implementation-rest-reconciliations]], [[new-domainerrorcode-three-sites]], [[maven-arglineation-goal-crash]], [[openapi-regen-platform-shim]], [[archunit-runs-in-failsafe-not-surefire]], [[spa-fallback-lives-in-problemdetailsmapper]], [[two-public-constructors-need-autowired]], [[prettier-gate-cascades-ci]], [[frontend-lockfile-cross-platform]], [[rtk-hook-only-matches-bash]], [[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]], [[commit-no-claude-coauthor]].

### References

- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.23` (lines 464–482) — the endpoint AC text (idealized; reconciled R1–R8)]
- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.24` (lines 483–500) — symmetric reject REST twin; shared `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` per R6]
- [Source: `_bmad-output/implementation-artifacts/3-20-technical-approval-service-accept-implementation-with-version-binding.md` — the `done` service this endpoint wires to; AC7/AC8 (Completed/Executing), AC2 (spec → INVALID_COMMAND_PAYLOAD), AC6 (eligibility 503 + PR-link 409), Trap T2 (replay re-reads state)]
- [Source: `_bmad-output/implementation-artifacts/3-24-rest-endpoint-reject-implementation-and-openapi.md` — symmetric reject twin; mirror its structure; R6 shared code]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java:404–445` — `approve-spec` master template]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ApproveSpecRequest.java:38–44` — DTO template]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowStateChangeResponse.java:13–23` — reused response DTO]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:145–156` — `acceptImplementation` → `WorkflowStateChangeResult` (Completed/Executing)]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/AcceptImplementationCommand.java:29–40` — command ctor field order (Trap T1)]
- [Source: `deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java:63–78, 380–395` — EXPECTED_PERMITS + deferred-round-trip comment]
- [Source: `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:223–238` — thin-controller ArchUnit rule]

## Open Questions (for Alex — surfaced, non-blocking unless marked)

1. **OQ-1 (R2): Response richness.** The endpoint returns `WorkflowStateChangeResponse { workflowRunId, currentState, correlationId }` (mirroring `approve-spec`). Epic AC9 additionally asks for the stamped `correlationId` (covered) — it does **not** ask for the `apr_` id (unlike the reject twin's epic AC8), so this is lower-risk than 3.24's OQ-1. Surfacing the `apr_` id would widen `WorkflowStateChangeResult`/`WorkflowStateChangeResponse` (fans out to approve/reject-spec/retry/takeover + the generated TS client). **Recommended: keep the response symmetric with `approve-spec` (state only).** Confirm.
2. **OQ-2 (R8/AC5 status): `ARTIFACT_PAYLOAD_UNAVAILABLE` status code.** Epic AC5 lists it as `409`; the live catalog + `approve-spec` `@ApiResponses` use `503` (retryable). **Recommended: document `503` (match the live contract).** Confirm we keep the live 503.
3. **OQ-3 (R6): shared-code ownership.** `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` is shared with 3.24. **Recommended: whichever lands first creates it; this story greps and reuses if 3.24 already shipped it.** Confirm the dev order doesn't matter (the code + idiom are identical on both sides).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Focused fast tier (PowerShell): `AcceptImplementationEndpointContractTest`, `ApproveSpecEndpointContractTest`, `RegistryContractTest`, `WorkflowControllerLoggingContractTest` → **37 tests, 0 failures**.
- Foundation gate (PowerShell, `-Pfoundation-gate verify`, `-Djacoco.skip=true`): `CommandModelSymmetryFoundationContract` + `CliRestEquivalenceContractTest` (Failsafe) + `RegistryContractTest` (Surefire) → **BUILD SUCCESS**; SpotBugs clean.
- OpenAPI regen (WSL2 Linux + Docker/Testcontainers): `scripts/regen-openapi.sh` → backend snapshot updated (`OpenApiSnapshotContractTest` booted the full app and re-wrote it under `-Dopenapi.snapshot.write=true`); frontend client regenerated from Windows PowerShell (`npm run generate-api`) + `prettier --write`.
- Spotless: initial `verify` red on Google-Java-Format Javadoc reflow → `spotless:apply` (7 files) → green.

### Completion Notes List

- **First-to-land created the shared code.** Grep confirmed `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` was absent and 3.24 was still `ready-for-dev`, so 3.23 created the three-sites code + the `requireDeveloperReviewerRole` controller/CLI idiom (R6). 3.24 must REUSE both, not re-add.
- **R4 idiom (the key delta from `approve-spec`):** developer-only `reviewerRole` is validated at the boundary with a typed code; it is deliberately NOT routed through `ApprovalReviewerRoleResolver.resolveFor(...)` (blank→`product_reviewer` would mask the mismatch). The same idiom is duplicated verbatim in `WorkflowController` and `WorkflowCommands` so REST/CLI reject identical payloads identically (CLI/REST parity pin).
- **Response is `WorkflowStateChangeResponse` (state only); no `apr_` id (R2).** Artifact-type-dependent `currentState`: `Executing` (implementationPlan) / `Completed` (prOutput) — both read from the service result, never hardcoded (R1).
- **No service/persistence/migration/registry/transition-table change** — the entire substrate exists from the `done` 3.20. This story is REST adapter + CLI adapter + 1 problem code + foundation round-trip + OpenAPI regen only. `ARTIFACT_PAYLOAD_UNAVAILABLE` documented as **503** and `ARTIFACT_PR_LINK_MISMATCH` (409) kept (accept reads the payload — R8); spec-artifact guard surfaces as `INVALID_COMMAND_PAYLOAD` (R7); "state forbids action" surfaces as `ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL` (R3).
- **OpenAPI snapshot + TS client regenerated and committed-to-tree together** so the frontend `check:api` drift gate stays green.

### File List

**New:**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/AcceptImplementationRequest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/AcceptImplementationEndpointContractTest.java`

**Modified:**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (+`acceptImplementation` handler + `requireDeveloperReviewerRole` helper)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` (+`accept-implementation` command + `requireDeveloperReviewerRole` helper)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (+`INVALID_REVIEWER_ROLE_FOR_ENDPOINT`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (+1 `register(...)`)
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (+1 problem-type URI)
- `deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java` (+`captureAcceptImplementation` round-trip; updated deferred comment)
- `deliveryline-backend/src/test/java/org/dradgo/foundation/CliRestEquivalenceContractTest.java` (+accept CLI/REST parity test)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java` (+`.contains("acceptImplementation")` assertion)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated — new operation + request schema + error code)
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated from the snapshot + prettier-formatted)

### Change Log

| Date       | Version | Description                                                                                  | Author |
| ---------- | ------- | -------------------------------------------------------------------------------------------- | ------ |
| 2026-06-15 | 0.1     | Implemented story 3.23 — `accept-implementation` REST + CLI adapter over the `done` 3.20 service: new `AcceptImplementationRequest` DTO, `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` three-sites code (3.23 first-to-land), `acceptImplementation` controller + CLI handlers with developer-only role validation, foundation round-trip + CLI/REST parity, OpenAPI snapshot + TS client regen. All gates green. Status → review. | Amelia (dev agent) |

### Review Findings

_Adversarial code review (2026-06-15) — Blind Hunter + Edge Case Hunter + Acceptance Auditor. All 8 reconciliations (R1–R8) verified correctly implemented; 5 findings dismissed as noise/false-positive (incl. a misquoted "stale comment" that is actually correct, the `@Size(128)` reviewerRole constraint which is an intentional AC2 mirror, and an unconfirmed CLI actor-resolution divergence)._

- [x] [Review][Patch] (FIXED — added 3 focused `WorkflowCommandsTest` cases: non-developer reject, blank reject, generated-key surfacing) CLI `accept-implementation` has no direct test coverage — its `requireDeveloperReviewerRole` reject branch, the generated-idempotency-key branch, the verbose/correlation branch, and the `emitFailure` paths are exercised only by the happy-path equivalence test. A CLI-copy regression (e.g. switching to `approvalReviewerRoleResolver.resolveFor`, whose blank→`product_reviewer` default would mask the mismatch) would ship uncaught. Rated High by both Blind + Edge hunters. _(Decision 1 → Patch: add focused `WorkflowCommandsTest` cases.)_ `WorkflowCommands.java` / `WorkflowCommandsTest.java`
- [x] [Review][Patch] (FIXED — added the symmetric `WARN` audit line to the CLI helper, sanitized via `MdcKeys.sanitizeForLog`) CLI reviewer-role rejection is observably asymmetric with REST — the REST `requireDeveloperReviewerRole` emits a `WARN` audit line (sanitized `actualReviewerRole`) before throwing; the CLI copy throws with no log/audit line. _(Decision 2 → Patch: add the matching `WARN` audit line to the CLI helper.)_ `WorkflowCommands.java:119-132`
- [x] [Review][Patch] (FIXED — added a valid `expectedContextBundleVersion` to drop the silent 3rd violation, then assert `$.details[*].field` `hasItems("artifactId","expectedArtifactVersion")`) Weak `requestSchemaValidationFailureSurfacesInvalidCommandPayload` assertion — body has 3 violations (blank `artifactId`, `-1` version, omitted `expectedContextBundleVersion`) but asserts only `$.details[0].field`/`.constraint` exist; bean-validation order is non-deterministic so `$.details[0]` could be any of the three. The test would still pass with `@NotBlank`/`@Positive` removed from artifactId/version. Pin the expected fields (assert `details` contains the artifactId + version violations). [`AcceptImplementationEndpointContractTest.java:294-295`]
- [x] [Review][Defer] CLI vs REST first-error precedence diverges on multiply-invalid payloads — REST `@Valid` surfaces `INVALID_COMMAND_PAYLOAD` (bad artifactId/version) before the in-body `requireDeveloperReviewerRole`; CLI has no `@Argument` bean-validation so reviewer-role is checked first → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`. Equivalence contract only tests the all-valid payload. [`WorkflowCommands.java`] — deferred, low-impact (only multiply-invalid input), untested edge
- [x] [Review][Defer] Whitespace-padded `"  developer  "` acceptance (trim path) is untested on both surfaces; each surface owns its own `.trim()` copy, so one could silently drift. [`AcceptImplementationEndpointContractTest.java`] — deferred, behavior is correct, coverage-only
- [x] [Review][Defer] `reason` fingerprint-exclusion has no behavioral test — no case proves two requests differing only in `reason` replay idempotently (same key). A future `WorkflowCommandFingerprintFactory` regression appending `reason` would break idempotency uncaught. — deferred, coverage hardening
- [x] [Review][Defer] Multi-valued / duplicated `Idempotency-Key` and `X-Actor-Identity` header rejection untested for this route (guards are wired + shared, just unpinned for accept-implementation). — deferred, path handled via shared helpers
- [x] [Review][Defer] Unsafe `X-Actor-Identity` (`requireSafe` reject) untested for this route on both REST and CLI. — deferred, path handled via shared resolver
- [x] [Review][Defer] `WORKFLOW_RUN_TERMINAL` (409) is documented in `@ApiResponses` + OpenAPI snapshot but has no contract test (only `ILLEGAL_TRANSITION` is pinned). — deferred, documented-but-unpinned 409
