# Story 3.24: REST Endpoint — `reject-implementation` + OpenAPI

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a frontend developer (Decision Bar `implementation_review` mode in story 3.28) and CLI user submitting a developer rejection,
I want a REST endpoint `POST /api/v1/workflows/{workflowRunId}/reject-implementation` wired to `WorkflowCommandService.rejectImplementation` (the story 3.21 `TechnicalApprovalService.rejectImplementation` service),
so that developer rejections flow through the same idempotency + Problem Details + OpenAPI conventions as `reject-spec` (story 2.13), and CLI/REST equivalence (story 1.7 AC5) is maintained.

## Acceptance Criteria

> Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md` § Story 3.24 (lines 483–500). The epic AC text is **idealized**; several items drift from the live service contract established by story 3.21 (which is `done`) and the story 2.13 `reject-spec` REST precedent. **The reconciliations in Dev Notes (R1–R8) WIN where they conflict with the literal AC text — read them before implementing.**

1. **Given** `WorkflowController` (the existing `adapters.rest` controller, extended by story 2.13), **Then** a new endpoint exists: `POST /api/v1/workflows/{workflowRunId}/reject-implementation` — kebab-case action, mirroring the `reject-spec` handler shape exactly (`@PostMapping`, JSON consume/produce, `@Operation`/`@ApiResponses`).
2. **Given** the request body, **Then** a new typed DTO `RejectImplementationRequest { artifactId, expectedArtifactVersion, expectedContextBundleVersion, reviewerRole, taggedFeedback, reasonText }` (camelCase JSON, `@JsonIgnoreProperties(ignoreUnknown = false)`), mirroring `RejectSpecRequest` field-for-field with Bean Validation. `taggedFeedback` is typed `RejectionTaxonomy` and (per R4) must be a **developer**-subset value.
3. **Given** the mandatory `Idempotency-Key` header + optional `X-Actor-Identity` header, **Then** the same conventions as `reject-spec` / story 3.23 apply: missing/blank `Idempotency-Key` → 400 `MISSING_IDEMPOTENCY_KEY`; malformed → 400 `INVALID_IDEMPOTENCY_KEY`; multi-valued / comma-folded `Idempotency-Key` or `X-Actor-Identity` → 400 `INVALID_COMMAND_PAYLOAD`; unsafe `X-Actor-Identity` → fail-closed via `LocalActorIdentityResolver.requireSafe`; correlation id from MDC.
4. **Given** Problem Details errors, **Then** coverage matches the live `reject-spec` set plus the two new REST-layer codes (R4): `MISSING_IDEMPOTENCY_KEY` (400), `INVALID_IDEMPOTENCY_KEY` (400), `INVALID_COMMAND_PAYLOAD` (400 — bad payload, unknown-enum `taggedFeedback`, header duplication, **and the artifact-type guard when `artifactId` points to a `spec`** — R7), `INVALID_ID_PREFIX` (400), `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400 — NEW; when `reviewerRole != developer`), `INVALID_REJECTION_TAXONOMY` (400 — NEW; when `taggedFeedback` is a valid `RejectionTaxonomy` but NOT in the developer subset), `MISSING_REJECTION_TAXONOMY` (400 — already exists, defense-in-depth), `RUN_NOT_FOUND` (404), `ARTIFACT_RECORD_NOT_FOUND` (404), `APPROVAL_VERSION_MISMATCH` (409), `IDEMPOTENCY_KEY_CONFLICT` (409), `ILLEGAL_TRANSITION` (409 — covers the "state forbids rejection" case, R3), `WORKFLOW_RUN_TERMINAL` (409). Contract tests check `code` + `status` + `details`, never human text.
5. **Given** OpenAPI via `springdoc-openapi`, **Then** the endpoint (`operationId=rejectImplementation`) + the `RejectImplementationRequest` schema + the two new error codes appear in the regenerated committed snapshot `src/main/resources/openapi/openapi.json`; the `OpenApiSnapshotContractTest` drift gate passes; the generated frontend client `deliveryline-frontend/src/lib/api/schema.d.ts` is regenerated from the snapshot and committed.
6. **Given** CLI/REST equivalence (story 1.7 AC5), **Then** a Spring Shell command `deliveryline reject-implementation --run {runId} --artifact-id {artifactId} --expected-artifact-version N --expected-context-bundle-version M --reviewer-role developer --reason-text "..." --tagged-feedback {value} [--idempotency-key K] [--actor-identity ...] [--correlation-id ...]` is added under `adapters.cli.WorkflowCommands` mirroring `reject-spec`; a contract test asserts CLI and REST produce identical outcomes + identical typed errors.
7. **Given** ArchUnit (story 1.11), **Then** the controller method stays thin — request parsing, controller-boundary request validation (header guards + role/taxonomy subset checks), command construction, service invocation, response mapping. No domain decisions, no persistence, no transition logic (those live in `TechnicalApprovalService`).
8. **Given** a successful rejection, **Then** the endpoint returns **200 OK** with the existing `WorkflowStateChangeResponse { workflowRunId, currentState, correlationId }`. **RECONCILED (R1): `currentState` is `Executing` for BOTH `implementationPlan` AND `prOutput` rejection** (epic AC8's "Investigating for plan" is wrong — `WaitingForReview → Investigating` is illegal in the live transition table; story 3.21 D3 lands both kinds in `Executing`). **RECONCILED (R2): the response does NOT carry the `apr_` approval id or an escalation marker** — the live service maps `ApprovalResult → WorkflowStateChangeResult`, discarding both, exactly as `reject-spec` does (see OQ-1). `X-Correlation-Id` response behavior matches `reject-spec` (no new header work).
9. **Given** the "state forbids the action" case (e.g. rejecting from `Completed` / a terminal run), **Then** the failure surfaces as **`ILLEGAL_TRANSITION` (409)** (or `WORKFLOW_RUN_TERMINAL` (409) for terminal runs) from the state machine. **RECONCILED (R3): no new `ACTION_NOT_ALLOWED` code and no controller-side allowed-actions pre-check** — that would be business logic (ArchUnit violation) and diverges from the `reject-spec` precedent, which relies on the transition table.
10. **Given** the test suite, **Then** it covers: happy-path rejection of an `implementationPlan` → 200 + `currentState=Executing`; happy-path rejection of a `prOutput` → 200 + `currentState=Executing`; missing `Idempotency-Key`; missing/blank `reviewerRole` and non-`developer` role → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`; product-taxonomy value (e.g. `MISSING_SCOPE`) → `INVALID_REJECTION_TAXONOMY`; unknown-enum `taggedFeedback` → `INVALID_COMMAND_PAYLOAD`; spec `artifactId` → `INVALID_COMMAND_PAYLOAD` (R7); `APPROVAL_VERSION_MISMATCH`; `IDEMPOTENCY_KEY_CONFLICT`; idempotent replay (same key + fingerprint → same 200 body); `ILLEGAL_TRANSITION` when state forbids; request schema validation failures; CLI/REST parity.

## Tasks / Subtasks

- [ ] **Task 1 — `RejectImplementationRequest` REST DTO** (AC: 2)
  - [ ] Create `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RejectImplementationRequest.java` mirroring `RejectSpecRequest` (`adapters/rest/RejectSpecRequest.java`) exactly: `@JsonIgnoreProperties(ignoreUnknown = false)` record with `@NotBlank @Size(max=128) String artifactId`, `@NotNull @Positive Integer expectedArtifactVersion`, `@NotNull @Positive Integer expectedContextBundleVersion`, `@Size(max=128) String reviewerRole`, `@NotNull RejectionTaxonomy taggedFeedback`, `@NotBlank @Size(max=1024) String reasonText`.
  - [ ] Javadoc: note the verbose wire names `expectedArtifactVersion`/`expectedContextBundleVersion` map to the short `artifactVersion`/`contextVersion` command fields (Trap T1, already documented on `RejectImplementationCommand`), and that `taggedFeedback` must be a **developer**-subset `RejectionTaxonomy` value (controller enforces — Task 3).
  - [ ] **Wire-value gotcha (R5):** Jackson deserializes the `RejectionTaxonomy` enum by **constant NAME** (UPPERCASE, e.g. `"INCORRECT_APPROACH"`), NOT by `value()` (`incorrect_approach`) — verify against the working `reject-spec` contract (`RejectSpecEndpointContractTest` sends `"taggedFeedback": "MISSING_SCOPE"`). Use the UPPERCASE names in all REST test fixtures.

- [ ] **Task 2 — Two new REST-layer `DomainErrorCode`s (three-sites each)** (AC: 4) — see [[new-domainerrorcode-three-sites]]
  - [ ] `INVALID_REJECTION_TAXONOMY` — Site 1: add to `domain/registry/DomainErrorCode.java` (a `// INVALID_REJECTION_TAXONOMY deserialization concern belongs to story 3.24` placeholder comment is already parked there at line ~107 — replace it with the real constant). Site 2: `register(...)` in `adapters/rest/ProblemDetailsCatalog.java` with `HttpStatus.BAD_REQUEST`, title "Invalid rejection taxonomy", `retryable=false` (mirror the `MISSING_REJECTION_TAXONOMY` registration at lines ~399–404). Site 3: add `"INVALID_REJECTION_TAXONOMY": "https://deliveryline.local/problems/invalid-rejection-taxonomy"` to `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json#problemTypeUris`.
  - [ ] `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` — Site 1: add to `DomainErrorCode.java`. Site 2: `register(...)` in `ProblemDetailsCatalog.java` with `HttpStatus.BAD_REQUEST`, title "Invalid reviewer role for endpoint", `retryable=false`. Site 3: add `"INVALID_REVIEWER_ROLE_FOR_ENDPOINT": "https://deliveryline.local/problems/invalid-reviewer-role-for-endpoint"` to the placeholders manifest. (Shared with story 3.23 accept REST — 3.24 lands first and creates it; R6.)
  - [ ] Verify the three sites with `-Pfoundation-gate` and `RegistryContractTest` (PowerShell — [[rtk-hook-only-matches-bash]]).

- [ ] **Task 3 — `rejectImplementation` controller handler** (AC: 1, 3, 4, 7, 8)
  - [ ] Add a `rejectImplementation(...)` `@PostMapping("/{workflowRunId}/reject-implementation")` method to `WorkflowController`, copied from `rejectSpec` (lines 447–526) with these deltas:
    - Body type `RejectImplementationRequest`; service call `workflowCommandService.rejectImplementation(...)`.
    - Reuse the header guards verbatim: `rejectMultiValuedIdempotencyKeyHeader`, `requireNonBlankIdempotencyKey`, `rejectMultiValuedActorIdentityHeader`, `localActorIdentityResolver.requireSafe(...)` + `resolve(...)`, correlation from `MDC.get(MdcKeys.CORRELATION_ID)`.
    - **Role validation (R4):** the body `reviewerRole` must equal `"developer"`. Do **NOT** route it through `approvalReviewerRoleResolver.resolveFor(...)` (that defaults blank → `product_reviewer` and would mask the error). Resolve the raw value (trim), and if it is null/blank/`!= "developer"` throw `new DomainException(DomainErrorCode.INVALID_REVIEWER_ROLE_FOR_ENDPOINT, ...)` with `details{ field:"reviewerRole", expected:"developer", actual:<value> }` (LinkedHashMap, deterministic order — matches the controller's existing detail-payload pattern).
    - **Taxonomy subset validation (R4):** if `!request.taggedFeedback().isDeveloperValue()` throw `INVALID_REJECTION_TAXONOMY` with `details{ field:"taggedFeedback", value:request.taggedFeedback().value() }`. (An entirely-unknown enum string never reaches the body — Jackson fails first → `INVALID_COMMAND_PAYLOAD` via the existing `HttpMessageNotReadableException` advice; no extra code.)
    - Build `RejectImplementationCommand` positionally — **field order is**: `workflowRunId, artifactId, expectedArtifactVersion, expectedContextBundleVersion, actorIdentity, ActorType.HUMAN, idempotencyKey, correlationId, reviewerRole(="developer"), taggedFeedback, reasonText` (matches `RejectImplementationCommand` ctor — identical order to the `reject-spec` call site, confirmed).
    - Map the result with `WorkflowStateChangeResponse.from(...)` (R2 — same response DTO as `reject-spec`).
    - INFO entry/exit logs mirroring `reject-spec` (sanitize all user-supplied values via `MdcKeys.sanitizeForLog`).
  - [ ] `@Operation(operationId = "rejectImplementation", summary = ...)` + `@ApiResponses` documenting the AC4 code set on 200/400/404/409 (copy the `reject-spec` `@ApiResponses` and append the two new 400 codes; drop the 503 `ARTIFACT_PAYLOAD_UNAVAILABLE` line — rejection does NOT read the artifact payload, unlike approve-spec).

- [ ] **Task 4 — `deliveryline reject-implementation` CLI command** (AC: 6)
  - [ ] Add a `rejectImplementation(...)` `@Command(name = "reject-implementation", ...)` to `adapters/cli/WorkflowCommands.java`, copied from `rejectSpec` (lines 544–634). Options: `--artifact-id` (req), `--expected-artifact-version` (req Integer), `--expected-context-bundle-version` (req Integer), `--tagged-feedback` (req `RejectionTaxonomy`), `--reason-text` (req), `--reviewer-role` (req — developer; mirror REST: validate `== "developer"` → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`), `--idempotency-key`/`--actor-identity`/`--correlation-id`/`--verbose` (opt). Validate `taggedFeedback.isDeveloperValue()` → `INVALID_REJECTION_TAXONOMY` for CLI/REST parity.
  - [ ] Resolve actor via `resolveActorIdentity(...)`, idempotency via `idempotencyKeyValidator.requireValid(resolveIdempotencyKey(...))`, correlation via `pushCorrelation(...)`; call `workflowCommandService.rejectImplementation(new RejectImplementationCommand(...))` with `ActorType.HUMAN`. Output line: `"<runId> reject-implementation accepted (state: " + result.currentState().value() + ")"` (always `Executing` — R1). Emit success/failure telemetry (`emitSuccess`/`emitFailure`, `codeFor`) and use `WorkflowCliExitStatusExceptionMapper.BEAN_NAME`.
  - [ ] No new constructor dependency — `WorkflowCommands` already injects `workflowCommandService`, `approvalReviewerRoleResolver`, `localActorIdentityResolver`, `idempotencyKeyValidator` (do NOT add a ctor arg — avoids the 3-arg/legacy-ctor fan-out, [[two-public-constructors-need-autowired]]).

- [ ] **Task 5 — OpenAPI snapshot + generated TS client regen** (AC: 5)
  - [ ] Run the snapshot regen: backend snapshot via the `test`/`integration-test` lifecycle phase + `-Dopenapi.snapshot.write=true` (NOT the direct surefire/failsafe goal — [[maven-arglineation-goal-crash]]), then `npm run generate-api`. Cross-shell coordination required (WSL2 for step 1 ELK/backend, the node-bin-owning shell for step 2 — [[openapi-regen-platform-shim]]); `scripts/regen-openapi.sh` is the canonical driver.
  - [ ] Commit both regenerated files: `src/main/resources/openapi/openapi.json` + `deliveryline-frontend/src/lib/api/schema.d.ts`. The snapshot must now contain `rejectImplementation`, the `RejectImplementationRequest` schema, and the two new problem codes.
  - [ ] Optional but recommended: extend `OpenApiSnapshotContractTest`'s additive command-surface assertion (lines ~87–93) with `.contains("rejectImplementation")` so a future accidental deletion of the operation fails loudly (the byte-equality check already covers it, but the explicit assertion documents intent).

- [ ] **Task 6 — Contract / endpoint tests** (AC: 10)
  - [ ] Create `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RejectImplementationEndpointContractTest.java` (`@WebMvcTest(controllers = WorkflowController.class)` + `@Import(ApprovalReviewerRoleResolver.class)`, `@MockitoBean WorkflowCommandService` / `WorkflowInspectionService` / `LocalActorIdentityResolver`), mirroring `RejectSpecEndpointContractTest`. Cover every AC10 case. Happy paths stub `workflowCommandService.rejectImplementation(any())` → `new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, null)` and assert `currentState=Executing` for both plan + prOutput fixtures, capture the `RejectImplementationCommand` arg and assert its fields (incl. `actorType=HUMAN`, `reviewerRole="developer"`, version mapping). Error cases assert `status` + `code` + `details` JSON (never human text).
  - [ ] CLI/REST parity test: extend the existing CLI test surface (mirror the `reject-spec` parity test) asserting the CLI `reject-implementation` and the REST endpoint construct equal commands and surface identical typed error codes.
  - [ ] Foundation-gate / boot tier: ensure `OpenApiSnapshotContractTest` (Failsafe, Testcontainers) is green after regen — it boots the app, so any springdoc annotation error surfaces here, not in `mvnw test`.

- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [ ] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [ ] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [ ] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `artifactId`). Use MDC where the framework supports it; otherwise pass as parameters.
  - [ ] Never log secrets, payload bytes, raw tokens, or full PII. Reference the redaction policy when in doubt.
  - [ ] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use a list-appender or `OutputCaptureExtension`). For this story the new branches are at the controller boundary: assert the INFO `REST reject-implementation received/success` lines and the WARN/typed-rejection path for `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` / `INVALID_REJECTION_TAXONOMY`.

## Dev Notes

### Reconciliations & Decisions (READ FIRST — these override the literal epic AC text)

- **R1 — Response `currentState` is `Executing` for BOTH plan and prOutput rejection (epic AC8 stale).** The live `WorkflowCommandService.rejectImplementation` → `WorkflowStateChangeResult` with `resultingState = EXECUTING` for both artifact kinds. Story 3.21 Decision D3 (confirmed with Alex, story is `done`): `WaitingForReview → Investigating` is **illegal** in `WorkflowTransitionTable` and `Investigating` is the spec stage, so both `implementationPlan` and `prOutput` rejection transition `WaitingForReview → Executing` and branch the re-dispatch by artifact type internally. Epic 3.24 AC8's "Investigating for plan-rejection / Executing for prOutput-rejection" is therefore wrong for this REST layer. [Source: `WorkflowCommandService.java:159–168, 318–330, 605–626`; `3-21-...reject-implementation....md` D3]
- **R2 — Response DTO is the existing `WorkflowStateChangeResponse` (workflowRunId, currentState, correlationId); NO `apr_` id, NO escalation marker (epic AC8 over-specified).** `rejectImplementationInternal` maps `ApprovalResult → WorkflowStateChangeResult`, discarding `ApprovalResult.approvalId()` and never surfacing `escalation_marker_set`. This mirrors `reject-spec` exactly (same response record). Surfacing the `apr_` id + escalation marker would require widening `WorkflowStateChangeResult` + `WorkflowStateChangeResponse`, which fans out to the approve-spec / reject-spec / retry / takeover responses AND the generated TS client — disproportionate for this thin-endpoint story. **Decision: mirror `reject-spec`, return state only. See OQ-1** (enrich is a deliberate, separable enhancement).
- **R3 — "State forbids the action" surfaces as `ILLEGAL_TRANSITION` (409) / `WORKFLOW_RUN_TERMINAL` (409), NOT a new `ACTION_NOT_ALLOWED` code (epic AC9 reconciled).** The service relies on the state machine: rejecting from a state that has no legal `→ Executing` edge (e.g. `Completed`/terminal) raises `ILLEGAL_TRANSITION` (or `WORKFLOW_RUN_TERMINAL`). Adding a controller-side allowed-actions pre-check would be **business logic in the controller** (violates AC7 + the story 1.11 ArchUnit thin-controller rule) and diverges from the `reject-spec` precedent. **Decision: no `ACTION_NOT_ALLOWED` code; document `ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL` in the 409 set.**
- **R4 — Two NEW REST-layer `DomainErrorCode`s: `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` + `INVALID_REJECTION_TAXONOMY`.** Both are controller-boundary **request validation** (not domain decisions — ArchUnit-safe):
  - `reviewerRole` must equal `developer`; anything else (incl. blank → would otherwise default to `product_reviewer`) → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` (400). Do NOT use `ApprovalReviewerRoleResolver.resolveFor` here (its blank-fallback masks the mismatch).
  - `taggedFeedback` is a valid `RejectionTaxonomy` but a **product** value (e.g. `MISSING_SCOPE`) → `INVALID_REJECTION_TAXONOMY` (400), using the existing `RejectionTaxonomy.isDeveloperValue()` helper (added by 3.21). An entirely-unknown enum string fails Jackson deserialization first → `INVALID_COMMAND_PAYLOAD` (400) via the pre-existing `HttpMessageNotReadableException` advice (no extra code).
  - `INVALID_REJECTION_TAXONOMY` is **explicitly pre-reserved for this story** — `DomainErrorCode.java:~107` already carries the placeholder comment "INVALID_REJECTION_TAXONOMY deserialization concern belongs to story 3.24". The service layer deliberately does NOT have this code (3.21 D6). [Source: `DomainErrorCode.java:107–109`; `RejectionTaxonomy.java:73–81`]
  - The service keeps its own defense-in-depth guards (`reviewerRole`==developer AND developer-subset, both → `INVALID_COMMAND_PAYLOAD` from 3.21's unconditional patch); the controller's earlier, more specific typed errors are additive, not a replacement. Both layers stay.
- **R5 — `taggedFeedback` JSON wire value is the UPPERCASE enum NAME** (`INCORRECT_APPROACH`), not the lowercase `value()` (`incorrect_approach`). The DTO types it as the `RejectionTaxonomy` enum and Jackson's default enum binding uses the constant name — confirmed by `RejectSpecEndpointContractTest` sending `"taggedFeedback": "MISSING_SCOPE"`. Use UPPERCASE in REST fixtures. (CLI uses Spring Shell's `RejectionTaxonomy` option converter — mirror `reject-spec`'s working CLI.)
- **R6 — `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` is SHARED with story 3.23 (accept REST, still `backlog` + uncreated).** 3.24 lands first, so it CREATES the code + the controller `reviewerRole == developer` validation idiom; 3.23 will REUSE both. (Analogous to how 3.20/3.21 shared `TechnicalApprovalService`.) If 3.23 lands first, reuse whatever it created. Keep the two endpoint handlers symmetric. The pre-existing `/takeover-workflow` + `/retry-workflow` endpoints in the controller are the **story 1.15 command endpoints, NOT story 3.25's `/takeover`** — do not touch them.
- **R7 — Artifact-type guard surfaces as `INVALID_COMMAND_PAYLOAD` (400), NOT `ARTIFACT_TYPE_MISMATCH`.** `ARTIFACT_TYPE_MISMATCH` does NOT exist as a `DomainErrorCode` (only `RUNNER_ARTIFACT_TYPE_MISMATCH`, unrelated). When `artifactId` points to a `spec` artifact, `TechnicalApprovalService.rejectImplementation` raises `INVALID_COMMAND_PAYLOAD` (3.21 reconciliation, mirroring the sibling `acceptImplementation`). So epic 3.24 AC4's reference to story 3.23 AC5's `ARTIFACT_TYPE_MISMATCH (400)` reconciles to `INVALID_COMMAND_PAYLOAD (400)`. [Source: `3-21-...reject-implementation....md` "Reconciliation note (Task 4 / Task 6)"]
- **R8 — No 503 `ARTIFACT_PAYLOAD_UNAVAILABLE` on this endpoint.** Unlike `approve-spec` (which reads the artifact payload to checksum-verify), rejection does NOT read the payload — version-binding compares versions only. Drop the 503 line from the `@ApiResponses` (do not copy it from approve-spec).

### Architecture patterns & constraints

- **Thin controller (Trap T-arch, AC7).** The handler does: header guards → identity resolution → role/taxonomy boundary validation → command construction → `workflowCommandService.rejectImplementation(...)` → `WorkflowStateChangeResponse.from(...)`. NO transition logic, NO persistence, NO taxonomy/role *business* rules (the service owns those; the controller only does request-shape validation with typed codes). `WorkflowController` is pinned to `adapters.rest` + `@RestController` by the story 1.11 ArchUnit rule ([[spa-fallback-lives-in-problemdetailsmapper]] documents the same pin). Verify ArchUnit in Failsafe ([[archunit-runs-in-failsafe-not-surefire]]).
- **Idempotency + Problem Details are inherited, not re-implemented.** `WorkflowCommandService.executeIdempotent` handles replay vs `IDEMPOTENCY_KEY_CONFLICT`; `ProblemDetailsMapper` + `ProblemDetailsCatalog` map every `DomainException` to RFC-7807. The controller only adds the two new codes to the catalog (Task 2) and constructs commands.
- **No service / persistence / migration / registry changes.** The entire service + persistence + taxonomy + allowed-action substrate already exists (story 3.21, `done`). This story is **REST adapter + CLI adapter + 2 problem codes + OpenAPI regen only.** Do NOT add a Flyway migration, do NOT touch `TechnicalApprovalService`, `RejectImplementationCommand`, `WorkflowCommandService.rejectImplementation`, `RejectionTaxonomy`, or `AllowedAction`.
- **Command field order (Trap T1).** `RejectImplementationCommand` ctor order is `workflowRunId, artifactId, artifactVersion, contextVersion, actorIdentity, actorType, idempotencyKey, correlationId, reviewerRole, taggedFeedback, reasonText` — the short `artifactVersion`/`contextVersion` names ARE the expected versions. This order is identical to the `reject-spec` call site, so the positional command construction copies cleanly. [Source: `RejectImplementationCommand.java:33–45`]
- **Deferred-auth model.** `X-Actor-Identity` is optional with property fallback (`LocalActorIdentityResolver`); RBAC stays audit-only (no 401/403). `ActorType.HUMAN` is hard-coded for this reviewer-decision mutation (mirror `reject-spec`).

### Exact patterns to mirror (file:line)

- **REST handler:** `WorkflowController.rejectSpec` — `adapters/rest/WorkflowController.java:447–526` (header guards 493–499, command build 506–520, response map 506–507). The new handler is a near-verbatim copy with the R4 boundary checks inserted before the command build and the 503 line removed (R8).
- **REST DTO:** `RejectSpecRequest` — `adapters/rest/RejectSpecRequest.java:30–37`.
- **Response DTO:** `WorkflowStateChangeResponse` — `adapters/rest/WorkflowStateChangeResponse.java` (reuse as-is; R2).
- **Problem-code registration:** `ProblemDetailsCatalog` `register(metadata, DomainErrorCode.MISSING_REJECTION_TAXONOMY, HttpStatus.BAD_REQUEST, ..., false)` — `adapters/rest/ProblemDetailsCatalog.java:397–404`.
- **CLI command:** `WorkflowCommands.rejectSpec` — `adapters/cli/WorkflowCommands.java:544–634` (option set + actor/idempotency/correlation resolution + telemetry).
- **Endpoint contract test:** `RejectSpecEndpointContractTest` — `src/test/java/org/dradgo/adapters/rest/RejectSpecEndpointContractTest.java` (WebMvcTest wiring, actor-resolver stub, body fixtures, command-arg capture).
- **OpenAPI drift gate + regen:** `OpenApiSnapshotContractTest` — `src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java` (snapshot path, `-Dopenapi.snapshot.write=true` bootstrap, additive command-surface assertion).
- **Service consumed (do not modify):** `WorkflowCommandService.rejectImplementation` — `application/workflow/WorkflowCommandService.java:159–168, 318–330`; `TechnicalApprovalService.rejectImplementation` — `application/approval/TechnicalApprovalService.java`.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - Public application-service methods → `INFO` on entry + `INFO` on success / `WARN` on typed-domain rejection / `ERROR` on unexpected failure.
  - REST controller boundary → `INFO` "REST reject-implementation received/success" with sanitized run/artifact/actor ids (mirror `reject-spec`); typed boundary rejections (`INVALID_REVIEWER_ROLE_FOR_ENDPOINT` / `INVALID_REJECTION_TAXONOMY`) → at least a `WARN`/`INFO` audit line before the `DomainException` is thrown.
  - State-machine transitions / persistence → already logged by the service (do not duplicate at the controller).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus any entity public ids touched.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields. Pass through the existing redaction/classification path before logging; sanitize all user-supplied strings via `MdcKeys.sanitizeForLog`.
- **Test contract:** new logging surfaces must be pinned by at least one focused test (list-appender or Spring Boot `OutputCaptureExtension`).

### Project Structure Notes

- **New files:**
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RejectImplementationRequest.java`
  - `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RejectImplementationEndpointContractTest.java`
  - (CLI/REST parity test — extend an existing parity test file if one exists for `reject-spec`, else add a focused test)
- **Modified files:**
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (+`rejectImplementation` handler)
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` (+`reject-implementation` command)
  - `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (+`INVALID_REJECTION_TAXONOMY`, +`INVALID_REVIEWER_ROLE_FOR_ENDPOINT`)
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (+2 `register(...)`)
  - `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (+2 problem-type URIs)
  - `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated — new operation + request schema + error codes)
  - `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated from the snapshot)
  - `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java` (optional `.contains("rejectImplementation")` assertion)
- **No conflicts** with the unified hexagonal structure: adapters depend on application ports/services; no `adapters → adapters` cross-import beyond the existing controller↔catalog↔mapper triad.

### Gate / verification notes (Windows + this repo)

- Use **PowerShell** for all build/test gates — the RTK hook corrupts only the Bash tool ([[rtk-hook-only-matches-bash]]).
- New `DomainErrorCode` → verify the three sites with `-Pfoundation-gate` ([[new-domainerrorcode-three-sites]]); `RegistryContractTest` cross-checks enum ↔ catalog ↔ manifest.
- OpenAPI regen is the failure-prone step: run via the `test`/`integration-test` **lifecycle phase**, never the direct `surefire:test`/`failsafe:integration-test` goal ([[maven-arglineation-goal-crash]]); coordinate the backend-snapshot shell vs the npm-generate shell ([[openapi-regen-platform-shim]]). `OpenApiSnapshotContractTest` is a Testcontainers boot test (Failsafe) — verify in a clean/Linux env, not just locally ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).
- One un-formatted frontend file (the regenerated `schema.d.ts`) cascades through `format-static-checks` → `doctor-smoke` → every downstream CI job — run `prettier --write` before pushing ([[prettier-gate-cascades-ci]]); regenerate the frontend lockfile on Linux if `npm install` touches it ([[frontend-lockfile-cross-platform]]).
- Commit without the Claude co-author trailer ([[commit-no-claude-coauthor]]).

### References

- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.24` (lines 483–500) — the endpoint AC text (idealized; reconciled R1–R8)]
- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.23` (lines 464–482) — symmetric accept REST sibling; `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` originates in its AC4; shared per R6]
- [Source: `_bmad-output/implementation-artifacts/3-21-technical-approval-service-reject-implementation-with-structured-technical-feedback.md` — the `done` service this endpoint wires to; D3 (Executing), D6 (no INVALID_REJECTION_TAXONOMY at service), artifact-type guard → INVALID_COMMAND_PAYLOAD]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java:447–526` — `reject-spec` master template]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:159–168, 318–330, 605–626` — `rejectImplementation` → `WorkflowStateChangeResult(Executing)`]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RejectionTaxonomy.java:69–81` — `developerValues()` / `isDeveloperValue()`]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java:107–109` — pre-reserved `INVALID_REJECTION_TAXONOMY` placeholder comment]

## Open Questions (for Alex — surfaced, non-blocking unless marked)

1. **OQ-1 (R2): Response richness.** The endpoint returns `WorkflowStateChangeResponse { workflowRunId, currentState, correlationId }` (mirroring `reject-spec`). Epic AC8 additionally asks for the recorded rejection's `apr_` id + an escalation marker. Surfacing those means widening `WorkflowStateChangeResult` + `WorkflowStateChangeResponse` (fans out to approve/reject-spec/retry/takeover + the generated TS client). **Recommended: keep the response symmetric with `reject-spec` (state only) for this story; treat the enriched response as a separate, deliberate contract change** if the Decision Bar (story 3.28) actually needs the `apr_` id / escalation signal at mutation time. Confirm.
2. **OQ-2 (R3/AC9): `ACTION_NOT_ALLOWED`.** Recommended: rely on `ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL` (no new code, no controller pre-check, matches `reject-spec`). The only alternative — a controller-side allowed-actions pre-check returning a distinct `ACTION_NOT_ALLOWED (409)` — adds business logic to the controller (ArchUnit tension). Confirm we take the recommended path.
3. **OQ-3 (R4): Two new codes vs reuse `INVALID_COMMAND_PAYLOAD`.** Recommended: add the two distinct typed codes (`INVALID_REVIEWER_ROLE_FOR_ENDPOINT`, `INVALID_REJECTION_TAXONOMY`) since the epic (3.23 AC4 / 3.24 AC4) and the parked `DomainErrorCode` comment call for them, giving the frontend precise, actionable Problem Details. The minimal alternative is to let the service's existing `INVALID_COMMAND_PAYLOAD` guards cover both. Confirm the distinct-codes path.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

### Completion Notes List

### File List
