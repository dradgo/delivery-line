# Story 6.9: Localhost REST Binding + Workflow Read Endpoints

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **pilot installer or the Epic 2 React review UI**,
I want **the Spring Boot REST server bound strictly to localhost with read endpoints `GET /api/v1/workflows`, `GET /api/v1/workflows/{workflowRunId}`, and `GET /api/v1/workflows/{workflowRunId}/events`, plus a committed OpenAPI snapshot**,
so that **Epic 2's UI can consume the same application services the CLI uses, the API contract is type-generatable and drift-checked, and REST is proven safe-by-default (fail-closed on non-loopback binding) without local auth tokens in Phase 1**.

## ⚠️ Read first — what this story is, why it moved, and the two traps that will bite a literal reader

**Why this story is here now (out of Epic 6):** Story 6.9 was *originally* parked in Epic 6 so the localhost-REST/OpenAPI baseline wouldn't block the CLI-first Epic 1 sequence (see epics.md:178). Epic 1 is now **closed**, and the **next frontend story 2.6** (TanStack Query + a typed API client *generated from the backend's OpenAPI spec*) is hard-blocked without a real `openapi.json` and live read endpoints. **Resolved up front by Alex (2026-05-21): pull 6.9 forward as 2.6's prerequisite.** Story IDs stay stable (epics.md:843 — no renumbering); 6.9 is simply sequenced and built now, ahead of 2.6, in the Epic 2 timeframe. After 6.9 merges, 2.6 generates its typed client from the snapshot this story commits.

**Scope:** three **read-only** REST endpoints + localhost-only binding (fail-closed) + springdoc OpenAPI + committed snapshot + Problem Details + correlation-ID. The application/service layer it reads from **already exists** (`WorkflowInspectionService`, story 1.15). This is mostly a **REST adapter + config + OpenAPI wiring** story — not new domain logic.

### 🚨 TRAP 1 — AC9 says "mutation endpoints are absent." They are NOT, and you must NOT delete them.

AC9 was authored in the CLI-first world where `WorkflowController` had no endpoints. **Reality today:** `deliveryline-backend/.../adapters/rest/WorkflowController.java` already exposes five **POST command endpoints** (`submit-workflow`, `approve-spec`, `reject-spec`, `retry-workflow`, `takeover-workflow`) added by epic-1 stories (shared command model 1.7 / CLI 1.15). **AC9 is overtaken by events.** Reinterpret it as: **"6.9 adds only READ endpoints and adds no new mutations; the pre-existing command endpoints remain untouched."** **Deleting or moving the existing POST endpoints is a regression disaster** (breaks 1.7 CLI/REST-equivalence + their tests). Do not touch them except to coexist in the same controller (or a sibling read controller — see Task 1).

### 🚨 TRAP 2 — `GET /events` MUST serialize to the committed fixture schema, which is NOT the shape `WorkflowInspectionService` returns today.

The authoritative wire contract is **already committed**: `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` (story 1.23). Its header literally states: *"This schema IS the contract for the future GET /api/v1/workflows/{workflowRunId}/events response serializer (story 6.9 WorkflowEventsController). … Field names mirror `org.dradgo.application.workflow.spi.WorkflowEventRecord`."* Story 2.6's query hooks bind to this schema. **The existing `WorkflowInspectionService.WorkflowHistoryView` / `WorkflowEventView` records DIVERGE from it** (no `workflowRunPublicId` on the event; top-level is `workflowRunId` not the `workflowRun{publicId,ticketRef,createdAt,terminalState}` object). You must produce REST DTOs that match the schema **exactly** and pin them with a **contract test that validates the live response against the committed schema file**. Do not casually reuse `WorkflowHistoryView` for the wire — map to the schema shape (the `WorkflowEventRecord` SPI already carries `workflowRunPublicId`; see Dev Notes).

**Hard boundaries:**

- **Read-only.** No new mutations, no domain/state-machine changes, no writes. Endpoints call existing application services only (hexagonal: `adapters.rest` → `application.workflow.*`; never reach into `adapters.persistence`).
- **`details.idempotencyKey` must never appear in any response** (the schema encodes `not: { required: ["idempotencyKey"] }`). Route event `details` through the existing redaction path (story 1.10 `RedactionPolicyService`) or explicitly strip server-only keys. This is a redaction-disaster guard, not optional.
- **Fail-closed binding is build-blocking behavior**, not a warning: non-loopback bind without the explicit unsafe override must **fail app startup** with `DOCTOR_REST_BIND_UNAVAILABLE` (the code already exists in `DomainErrorCode`).
- **No frontend code.** This is backend-only. The frontend consumption is 2.6.

## Acceptance Criteria

1. **Given** the `adapters.rest` package, **Then** the REST surface exposes three read endpoints: `GET /api/v1/workflows` (list with optional filters), `GET /api/v1/workflows/{workflowRunId}` (single run detail), `GET /api/v1/workflows/{workflowRunId}/events` (event history for a run) — all delegating to `WorkflowInspectionService` (story 1.15, which already wires the service methods used by CLI `status`/`history`).
2. **Given** REST response bodies, **Then** JSON uses `camelCase`, timestamps serialize as ISO-8601 UTC (`2026-04-24T14:32:15Z`), IDs carry their registered public prefixes (`run_`, `evt_`, etc.), and response DTOs are direct resource shapes (not a generic `{data: ...}` envelope) per architecture format patterns.
3. **Given** localhost-only binding, **Then** `application.yml` configures `server.address=127.0.0.1` by default; an explicit unsafe development override (`deliveryline.rest.bind-address=0.0.0.0` + `deliveryline.rest.unsafe-network-bind=true`) is required to bind non-loopback and emits a WARN-level log on startup.
4. **Given** a non-loopback bind attempt without the unsafe override, **When** the app starts, **Then** startup fails fast with `DOCTOR_REST_BIND_UNAVAILABLE` error code and clear remediation in the message.
5. **Given** the CI matrix and smoke tests, **Then** a bundled-jar smoke test launches the backend, asserts REST binds to `127.0.0.1:8080` (or configured port), issues a `GET /api/v1/workflows` and validates response schema, then shuts down cleanly.
6. **Given** OpenAPI via `springdoc-openapi`, **Then** the spec is served at `/v3/api-docs` (JSON) and Swagger UI at `/swagger-ui.html` — both localhost-bound; the OpenAPI doc is committed in `backend/src/main/resources/openapi/openapi.json` as a reference snapshot and regenerated in CI to detect drift (activating the existing graceful-no-op gate at `.github/workflows/ci.yml`).
7. **Given** Problem Details error responses (story 1.8), **Then** the REST endpoints emit `application/problem+json` for 4xx/5xx — tests assert stable error codes for missing runs (`RUN_NOT_FOUND`), malformed IDs (`INVALID_ID_PREFIX`), and inspection timeouts.
8. **Given** performance targets (NFR25/26/27), **Then** REST `GET /workflows/{id}` returns within 2 seconds and `GET /workflows/{id}/events` returns within 5 seconds for pilot-size runs — a contract test enforces this against seeded fixtures.
9. **Given** read-only scope in this story, **Then** this story adds **no new mutation endpoints**; the read endpoints are additive and the pre-existing command endpoints (`submit`/`approve-spec`/`reject-spec`/`retry`/`takeover` from stories 1.7/1.15) remain unchanged. *(Reinterpreted from the original "mutations are absent" wording — see TRAP 1; deleting the existing POST endpoints is forbidden.)*
10. **Given** `correlationId` from story 1.19, **Then** every REST request carries the ID through the handler and stamps it on response headers (`X-Correlation-Id`) and the Problem Details `instance`/`correlationId` field. *(The `CorrelationIdFilter` already echoes the header on all responses; this story extends Problem Details to also surface it — see Task 5.)*

## Tasks / Subtasks

- [x] **Task 1: Read endpoints (`GET` list / detail / events)** (AC: 1, 2, 9)
  - [x] Decide controller placement: **either** add the three `@GetMapping` methods to the existing `WorkflowController` (AC1 names "WorkflowController") **or** add a sibling `WorkflowReadController` in `adapters.rest` and keep `WorkflowController` command-only. **Recommended:** add the GETs to `WorkflowController` to satisfy AC1 literally; if the class grows unwieldy, a `@RequestMapping("/api/v1/workflows")` `WorkflowReadController` is acceptable (document the choice). **Do NOT remove or relocate the existing POST endpoints** (TRAP 1 / AC9).
  - [x] `GET /api/v1/workflows/{workflowRunId}` → delegate to `WorkflowInspectionService.getStatus(workflowRunId)` (returns `WorkflowStatusView`). Map to a camelCase direct-shape DTO. Validate the path param via `PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN)` (throws `INVALID_ID_PREFIX`) **before** service call — mirror `WorkflowInspectionService.java:83`. A missing run surfaces `RUN_NOT_FOUND` (the inspection service / read port already throws this; verify and let it propagate to the `@RestControllerAdvice`).
  - [x] `GET /api/v1/workflows/{workflowRunId}/events` → delegate to the history read path. **The response MUST match `workflow-events-response.schema.json` (TRAP 2)** — see Task 2 for the DTO + contract test. Same path-param validation.
  - [x] `GET /api/v1/workflows` (list) → **new capability**: there is no list/`findAll` query today (only `findByPublicId`). Add a paginated read method to the application layer (extend `WorkflowInspectionService` with e.g. `listRuns(filters, pageable)`) backed by a new `WorkflowRunReadPort` method + a `WorkflowRunRepository` query (e.g. `findAllByOrderByCreatedAtDesc(Pageable)` and optional state/ticket filters). Return a **list-item summary DTO** (lean shape: `workflowRunId`, `currentState`, `ticketRef`, `lastEventAt`, `lastEventType` — enough for the queue/list UI, story 2.15). Cap page size (reuse the `HISTORY_CEILING`/page-size discipline from the events read port). Document supported optional filters (at minimum: by state; keep filter surface small for MVP).
  - [x] Set explicit `produces = MediaType.APPLICATION_JSON_VALUE` on all three; no `consumes` (GET).
  - [x] **Hexagonal guard:** the controller imports only from `application.*` (services/views/SPI records) — never `adapters.persistence.*` or entities. ArchUnit (story 1.11) enforces this; keep it green.

- [x] **Task 2: `GET /events` response DTO conforming to the committed fixture schema + schema contract test** (AC: 1, 2, 7)
  - [x] Build the events response DTO to match `workflow-events-response.schema.json` **field-for-field**:
    - top-level `{ workflowRun: { publicId, ticketRef, createdAt, terminalState }, events: [ … ] }`
    - each event: `{ publicId, workflowRunPublicId, eventType, priorState, resultingState, actorIdentity, actorType, reason, failureCategory, interventionMarker, createdAt, details }`
    - `terminalState` = the run's current state (the schema's "state at end of stream"); enum values match `WorkflowState`.
  - [x] **Source the data from the `WorkflowEventRecord` SPI** (`org.dradgo.application.workflow.spi.WorkflowEventRecord`) which the schema mirrors and which carries `workflowRunPublicId` — NOT from `WorkflowInspectionService.WorkflowEventView` (which drops it). If `listHistory` only returns `WorkflowEventView`, add an inspection method that returns the schema-shaped records (or expose `WorkflowEventRecord` directly through a thin REST mapping). Document the chosen seam.
  - [x] **Redaction (mandatory):** ensure `details` never contains `idempotencyKey` or any server-only/secret key (schema: `not: { required: ["idempotencyKey"] }`). Route `details` through `RedactionPolicyService` (story 1.10) or explicitly drop the server-only keys before serialization. Pin this with a test asserting `idempotencyKey` is absent even when an internal event detail map contains it.
  - [x] **Contract test:** add a test that loads `fixture-event-streams/schema/workflow-events-response.schema.json` and validates a **live** `GET /events` response (via MockMvc or `@SpringBootTest`) against it using a JSON-Schema validator (e.g. `networknt/json-schema-validator` if already on the classpath, else `com.networknt:json-schema-validator` test-scope, or reuse whatever 1.23 used to validate fixtures — check `fixture-event-streams` tests first to avoid adding a dep). Seed a run + events fixture, hit the endpoint, assert schema-valid. This is the lock that prevents 2.6's generated client from drifting from the backend.
  - [x] Also validate the **single-detail** and **list** DTOs in tests for camelCase + ISO-8601-UTC + prefixed IDs + no `{data:}` envelope (AC2).

- [x] **Task 3: Localhost-only binding + fail-closed startup guard** (AC: 3, 4)
  - [x] In `application.yml`, set `server.address: 127.0.0.1` as the committed default (currently absent — Spring defaults to all interfaces). Keep `server.port` default 8080 (configurable).
  - [x] Add a `@ConfigurationProperties("deliveryline.rest")` record/class exposing `bindAddress` (optional override) and `unsafeNetworkBind` (boolean, default `false`). Register via `@ConfigurationPropertiesScan` or `@EnableConfigurationProperties`.
  - [x] Add a **startup fail-fast** validator: an `ApplicationListener<ApplicationContextInitializedEvent>` / `ApplicationListener<ApplicationEnvironmentPreparedEvent>` (early enough to abort before the connector binds) **or** an `EnvironmentPostProcessor`, resolving the effective bind address (`deliveryline.rest.bind-address` overriding `server.address`). If it resolves to a **non-loopback** address (`!InetAddress.getByName(addr).isLoopbackAddress()`) **and** `unsafeNetworkBind=false`, **fail startup** by throwing with `DOCTOR_REST_BIND_UNAVAILABLE` + remediation text ("set deliveryline.rest.unsafe-network-bind=true to bind a non-loopback address; default is loopback-only").
  - [x] When the unsafe override **is** set and the address is non-loopback, **start anyway** but emit a `WARN`-level log on startup naming the bound address and the security implication (AC3).
  - [x] **Reuse, don't duplicate, the loopback logic:** `DoctorProbeAdapter.probeRestBindAddress()` (`adapters/diagnostics/DoctorProbeAdapter.java:472-495`) already does the `InetAddress.getByName(...).isLoopbackAddress()` check returning `DOCTOR_REST_BIND_UNAVAILABLE`. Extract the loopback-resolution into a small shared helper (e.g. in `infrastructure` or a domain util) that both the doctor probe and this startup guard call, so the check has one source of truth. (Do not have the startup guard depend on the full `DoctorService`.)
  - [x] Verify `/v3/api-docs`, `/swagger-ui.html`, and `/actuator/**` inherit the same loopback binding (they bind to the same connector — no extra work, but assert it in the smoke test, AC6/AC5).

- [x] **Task 4: springdoc-openapi + committed snapshot + CI drift activation** (AC: 6)
  - [x] Add `org.springdoc:springdoc-openapi-starter-webmvc-ui` to `deliveryline-backend/pom.xml` (Spring Boot 4 / Spring 6.x-compatible springdoc 2.x line — **verify the version supports Spring Boot 4.0.6**; if the GA springdoc line lags Boot 4, pick the lowest version whose Spring peer range includes Boot 4 or escalate to Alex — same risk profile as the 2.5 Vite-8 plugin check). This serves `/v3/api-docs` (JSON) + `/swagger-ui.html`.
  - [x] Add a minimal `@OpenAPIDefinition` / `OpenAPI` bean (title "DeliveryLine API", version, localhost server URL) so the generated doc is stable and self-describing. Group/annotate the three read endpoints with `@Operation`/`@ApiResponse` enough that the generated client (2.6) gets meaningful operation IDs and typed responses. Ensure Problem Details responses appear (`application/problem+json` with the error schema) so 2.6's `problemDetails.ts` can be typed from the spec.
  - [x] **Generate + commit the snapshot** to `deliveryline-backend/src/main/resources/openapi/openapi.json`. The existing CI gate (`.github/workflows/ci.yml:385-396`) runs `./mvnw -pl deliveryline-backend springdoc:generate -DoutputDir=target/openapi` then `diff`s against the committed file. **`springdoc:generate` needs the app running** — wire `springdoc-openapi-maven-plugin`'s `generate` goal in the `integration-test` phase **bracketed by `spring-boot-maven-plugin` `start`/`stop`** (the canonical springdoc-maven-plugin setup), OR replace the gate's generation step with a boot-test that writes `/v3/api-docs` to the snapshot — **pick the approach that makes the committed CI `diff` reproduce deterministically** and update the gate comment/commands accordingly if you change them. Document the choice and confirm the gate flips from "skipping" to a real diff.
  - [x] Ensure snapshot determinism: pin springdoc to stable output (sorted keys / no timestamps) so the CI diff isn't flaky. If springdoc emits non-deterministic ordering, configure `springdoc.writer-with-order-by-keys=true` (or equivalent) and document it.
  - [x] Cross-ref note for 2.28 (SPA fallback, backlog): record in the story/dev notes that `/v3/api-docs`, `/swagger-ui*`, and `/actuator/**` must be excluded from the future SPA fallback (already enumerated in epics.md:1477 / story 2.28 AC2) — no action here beyond the note.

- [x] **Task 5: Problem Details + correlation-ID propagation** (AC: 7, 10)
  - [x] Confirm path-param validation throws `INVALID_ID_PREFIX` and missing-run throws `RUN_NOT_FOUND`; both are already registered in `DomainErrorCode` + `ProblemDetailsCatalog` and handled by the `@RestControllerAdvice` `ProblemDetailsMapper`. **Add no new error codes** unless an inspection-timeout path needs one — AC7 lists "inspection timeouts"; if `WorkflowInspectionService` can time out, map it to an existing code (prefer `INTERNAL_ERROR` → 500, or a dedicated code only if one already exists). Verify the catalog covers the path; do not invent codes speculatively.
  - [x] Tests assert `application/problem+json` + stable `code` for: `GET /{bad-prefix}` → `INVALID_ID_PREFIX` (400), `GET /run_doesnotexist…` → `RUN_NOT_FOUND` (404). Reuse the existing Problem Details test conventions (`ProblemDetailsMapperTest`, `ProblemDetailsCorrelationIdContractTest`).
  - [x] **AC10:** `CorrelationIdFilter` (`infrastructure/observability/CorrelationIdFilter.java:33,63`) already reads/echoes `X-Correlation-Id` on **all** responses (success + error) and seeds MDC. Confirm it is registered for the new GET paths (it's a servlet `Filter` over the whole app — verify, don't re-add). Extend `ProblemDetailsMapper.problemResponse()` so the Problem Details body carries the correlation id on the **`instance`** field too (AC10 wording: "Problem Details `instance` field") — today it sets a `correlationId` *property* (mapper:299-302); add the `instance` URI (e.g. derived from correlation id) without removing the existing property. Pin with a contract test.

- [x] **Task 6: REST binding smoke test + performance contract test** (AC: 5, 8)
  - [x] **Binding smoke (AC5):** add an integration test (`@SpringBootTest(webEnvironment = DEFINED_PORT)` or `RANDOM_PORT`, `@Tag("integration")`, Testcontainers Postgres per the existing pattern) that boots the app, asserts the connector is bound to a **loopback** address, issues `GET /api/v1/workflows`, and validates the response is schema-shaped, then context shuts down cleanly. A literal **executable-jar** launch smoke is heavier and overlaps story 2.28's packaging smoke — implement the `@SpringBootTest` binding smoke here (satisfies AC5's intent for this story) and add a `// SEAM (story 2.28)` note that the full bundled-jar launch smoke lands with packaging. If a jar-launch smoke is cheap to add via `spring-boot:start`, prefer it; otherwise document the deferral.
  - [x] Add a **fail-closed test:** with `deliveryline.rest.bind-address=0.0.0.0` and `unsafe-network-bind=false`, the context **fails to start** with `DOCTOR_REST_BIND_UNAVAILABLE` (assert via `assertThatThrownBy(() -> context.refresh())` or `ApplicationContextRunner`). And the inverse: with `unsafe-network-bind=true`, it starts and logs the WARN.
  - [x] **Performance contract (AC8):** seed a pilot-size run (a fixture run with a realistic event count — reuse/adapt the `fixture-event-streams` runs) and assert `GET /{id}` < 2s and `GET /{id}/events` < 5s. Use a generous-but-real threshold; mark `@Tag` so it runs in the right CI tier (story 1.21). Keep it deterministic (no network).

- [x] **Task 7: Logging instrumentation** (cross-cutting; required on every story — **this is a JVM story, fully applicable**)
  - [x] Add SLF4J-backed structured logs at the new public surfaces:
    - Each new controller handler: `INFO` on entry (request received, with the `workflowRunId`/filter context), `INFO` on success (with result size — e.g. event count / run count), `WARN` on typed-domain rejection (`INVALID_ID_PREFIX`/`RUN_NOT_FOUND` — though these mostly surface via the mapper; log at the boundary if the controller catches/branches), `ERROR` only on unexpected failure.
    - The startup bind guard: `INFO` "REST bind address resolved to loopback {addr}" on the safe path; `WARN` on the unsafe-override path (AC3); the fail-fast throw is itself the ERROR signal (ensure the thrown message is clear — AC4).
    - New application-service read method (`listRuns`): `INFO` entry/exit with filter + page context.
  - [x] Use **parameterized logging** (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Carry context keys via MDC where present: `correlationId` (already in MDC via the filter), `workflowRunId` on the per-run endpoints. **Never log** `idempotencyKey`, payload bytes, secrets, raw PII, or classification-restricted fields — and remember `details.idempotencyKey` must also be stripped from the *response* (Task 2).
  - [x] Pin new logging surfaces with at least one focused test (list-appender or Spring Boot `OutputCaptureExtension`) asserting the expected line + level for: a successful list/detail/events read, the unsafe-bind WARN, and the fail-fast path.

- [x] **Task 8: Docs + verification** (supports AC3, AC5, AC6)
  - [x] Document the localhost-binding contract + the `deliveryline.rest.bind-address` / `unsafe-network-bind` override (with the security warning) in the backend README / `docs/setup-local.md` (mirror where 1.16/1.17 documented doctor + env). Note the OpenAPI surfaces (`/v3/api-docs`, `/swagger-ui.html`) and that they are loopback-bound.
  - [x] Update `docs` (or the relevant REST/CLI parity doc) to note CLI `status`/`history` and the new REST read endpoints are two faces of the same `WorkflowInspectionService` (architecture: UI is a faithful view of backend state, not a second source of truth).
  - [x] **Verification gate:** run `./mvnw -pl deliveryline-backend -am verify` (or the focused contract/integration tags) green; confirm the **OpenAPI CI drift gate now performs a real diff** (commit the snapshot, run the generate step locally, diff clean); confirm ArchUnit (1.11) + foundation-gate (1.23) stay green. Per memory `verify-ci-fixes-in-clean-env`, reproduce the springdoc generate+diff in a clean/Linux-equivalent env before claiming AC6 done (the CI gate runs on Linux).

## Dev Notes

### Story scope — the backend REST/OpenAPI baseline the UI epic stands on

This story turns the existing CLI-only inspection capability (`WorkflowInspectionService`, story 1.15 — already serving CLI `status`/`history`) into a **loopback-bound REST read surface with a committed, drift-checked OpenAPI contract**. It is deliberately read-only and additive: it introduces no domain logic, no state transitions, no writes, and no new mutations. The hard parts are (1) **not breaking the existing command endpoints** (TRAP 1), (2) **matching the committed event-stream schema exactly** so story 2.6's generated client and the story-1.23 fixtures agree (TRAP 2), and (3) **fail-closed localhost binding** so REST is safe-by-default with no auth in Phase 1.

### Sequencing: 6.9 → 2.6 → 2.13/2.14 (resolved by Alex 2026-05-21)

- **6.9 (this story)** commits `openapi.json` + ships the three GET read endpoints + localhost binding. Read-only.
- **2.6 (next)** generates a typed TS client from this snapshot, builds query-key factories, `problemDetails.ts`, query hooks (`useWorkflowDetail`/`useWorkflowEvents`/…) typed from the generated client, and the mutation-hook pattern. 2.6's `useWorkflowEvents` binds to `GET /events` — the schema 6.9 enforces.
- **2.13/2.14 (later, backend)** add the *mutation* REST endpoints + allowed-actions endpoint and **regenerate** the same `openapi.json` snapshot (their ACs already reference the snapshot 6.9 creates).
- The story keeps its **6.9** id (epics.md:843 "Story IDs remain stable … NOT by renumbering"); only its sprint sequencing moved. The Epic 6 listing in `sprint-status.yaml` stays; its status flips to `ready-for-dev` and a note records the pull-forward.

### Current backend surfaces this story builds on (all verified, with paths)

- **`WorkflowInspectionService`** — `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`
  - `@Transactional(readOnly=true) WorkflowStatusView getStatus(String workflowRunPublicId)` (lines 76-141) — calls `WorkflowRunReadPort.findByPublicId` + `WorkflowEventReadPort.findLatestByWorkflowRunPublicId`. **Validates prefix at line 83** via `PublicIdPrefixes.require(...)`. → backs `GET /{id}`.
  - `@Transactional(readOnly=true) WorkflowHistoryView listHistory(String workflowRunPublicId, OffsetDateTime sinceInclusive)` (lines 143-186) — calls the event read port (paginated, `HISTORY_CEILING` ~1000). → backs `GET /{id}/events` (but **remap to the schema shape**, see TRAP 2).
  - **No list-all method exists** — you must add `listRuns(filters, pageable)` (Task 1) + a `WorkflowRunReadPort`/`WorkflowRunRepository` query.
  - View records (lines 273-307): `WorkflowStatusView(workflowRunId, currentState, currentActorIdentity, currentActorType, lastEventType, lastEventAt, latestArtifacts, linkedTicket, failedStage, lastSuccessfulStage, failureTimestamp, failureCategory, lastActivityTimestamp, nextSafeAction)`; `WorkflowEventView(publicId, eventType, priorState, resultingState, actorIdentity, actorType, reason, failureCategory, interventionMarker, createdAt, details)` — **note: no `workflowRunPublicId`** (the schema requires it). `WorkflowHistoryView(workflowRunId, events)` — top-level shape ≠ schema's `{workflowRun:{…}, events}`.
- **`WorkflowEventRecord` SPI** — `org.dradgo.application.workflow.spi.WorkflowEventRecord`. The committed schema header says it mirrors **this** record (it carries `workflowRunPublicId`). Prefer mapping the events response from this SPI record, not from `WorkflowEventView`.
- **`WorkflowController`** — `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java`. `@RestController @Validated @RequestMapping("/api/v1/workflows")`. **Already has 5 POST command endpoints** (submit/approve-spec/reject-spec/retry/takeover). Response DTOs `SubmitWorkflowResponse(workflowRunId, currentState, correlationId)` / `WorkflowStateChangeResponse(workflowRunId, currentState, correlationId)` — camelCase, direct shapes (the AC2 pattern to mirror). **Leave the POST endpoints untouched (TRAP 1).**
- **Problem Details (story 1.8)** — `adapters/rest/ProblemDetailsMapper.java` (`@RestControllerAdvice`, line 47), `adapters/rest/ProblemDetailsCatalog.java`, `domain/registry/DomainErrorCode.java`. `INVALID_ID_PREFIX` (400), `RUN_NOT_FOUND` (404), `DOCTOR_REST_BIND_UNAVAILABLE`, `INTERNAL_ERROR` **already exist and are registered**. Mapper injects `correlationId` from MDC into the response (lines 299-302). Type URI: `https://deliveryline.local/problems/{slug}`.
- **Correlation ID (story 1.19)** — `infrastructure/observability/CorrelationIdFilter.java`: `HEADER = "X-Correlation-Id"` (line 33), reads/generates UUIDv7, **echoes on response (line 63)**, MDC scope begin/end in `finally`. `application/observability/MdcKeys.java`: `CORRELATION_ID="correlationId"` (line 19), `sanitizeForLog` strips `[\r\n\t]`. **AC10's header echo is already done globally**; you only add the Problem Details `instance` surfacing.
- **Public IDs (story 1.4)** — `domain/id/PublicIdPrefixes.java`: `WORKFLOW_RUN="run_"`, `WORKFLOW_EVENT="evt_"`, …; suffix regex `[A-Za-z0-9_-]{4,64}` (line 28). `require(id, expected)` / `fromPublicId(id)` throw `INVALID_ID_PREFIX`. Use `require(workflowRunId, WORKFLOW_RUN)` for path-param validation. **Note:** the fixture schema's `publicId` patterns use `[A-Za-z0-9_]{4,}` (no `-`, no upper bound) — slightly looser than the backend `[A-Za-z0-9_-]{4,64}`; real backend IDs satisfy both, so no conflict, but don't "fix" the schema.
- **Doctor REST-bind probe (story 1.16)** — `adapters/diagnostics/DoctorProbeAdapter.java:472-495` already implements the loopback check returning `DOCTOR_REST_BIND_UNAVAILABLE`. **Extract the loopback resolution into a shared helper** so the new startup guard and this probe share one implementation (Task 3).
- **Persistence** — `adapters/persistence/repository/WorkflowRunRepository.java` (`findByPublicId`, `existsByPublicId`); `WorkflowEventRepository.java` (paginated `findByWorkflowRunPublicId…OrderByCreatedAtAscIdAsc`, `HISTORY_CEILING`). **No list query yet** — add one for `GET /api/v1/workflows`.
- **Config** — `src/main/resources/application.yml` (profiles group local/test/demo; `deliveryline.linear`/`runner`). **No `server.address`/`server.port` committed** (defaults all-interfaces/8080). Add `server.address: 127.0.0.1` + the `deliveryline.rest.*` props (Task 3).

### The committed event-stream schema is the contract (story 1.23 — TRAP 2 detail)

`deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` (draft 2020-12, `additionalProperties:false`). Shape:
```
{ "workflowRun": { "publicId": "run_…", "ticketRef": "DEL-1234",
                   "createdAt": "ISO-8601-UTC", "terminalState": <WorkflowState|null> },
  "events": [ { "publicId":"evt_…", "workflowRunPublicId":"run_…",
                "eventType":<enum>, "priorState":<state|null>, "resultingState":<state|null>,
                "actorIdentity":"…", "actorType":<human|agent|system|service_account>,
                "reason":<string|null>, "failureCategory":<enum|null>,
                "interventionMarker":<bool>, "createdAt":"ISO-8601-UTC",
                "details":{ … open map; idempotencyKey FORBIDDEN … } } ] }
```
- `eventType`, `workflowState`, `actorType`, `failureCategory`, `artifactVariant` are **closed enums** in the schema — the serialized values must match the registry/domain enum string forms exactly (event-type dot-separated lowerCamel per story 1.4; states PascalCase). Reuse the existing registry serialization so values agree.
- `details` is an open map but `idempotencyKey` is explicitly forbidden (`not:{required:[idempotencyKey]}`) and other server-only keys should not leak — **redact** (story 1.10) before serialization.
- The contract test must validate the **live endpoint** response against this exact file (Task 2). Check how 1.23 validates its fixtures against the schema and reuse that validator/dependency to avoid adding a new one.

### Architecture-prescribed REST + format + binding rules (architecture.md)

- **API format patterns:** camelCase JSON, ISO-8601 UTC timestamps (`2026-04-24T14:32:15Z`), public-prefixed IDs, **direct resource shapes — no `{data:…}` envelope** (AC2; epics.md:183). Spring Boot's default Jackson serializes `OffsetDateTime` to ISO-8601 — verify the `Z`/UTC offset form; if it emits `+00:00`, configure the writer to use `Z` (matching fixture determinism `yyyy-MM-ddTHH:mm:ssZ`).
- **Frontend-facing guardrails (architecture.md:485-519):** the UI derives controls from backend-reported state; REST is the source of truth. `GET /{id}` should expose enough for the four UI questions (what happened / what's current / who owns it / next safe action) — `WorkflowStatusView` already carries `currentState`, `currentActorIdentity`, `nextSafeAction`, `failedStage`, etc. Keep the detail DTO faithful to that.
- **OpenAPI (architecture.md API/CLI/OpenAPI section, ~line 201-204):** "Publish OpenAPI via `springdoc-openapi` with contract tests covering request/response shape, status codes, Problem Details payloads, idempotency requirements." 6.9 establishes the springdoc baseline; the read endpoints + Problem Details schema must appear in the generated doc so 2.6 can type its client + `problemDetails.ts`.
- **Localhost-only / safe-by-default:** MVP is local-only (architecture.md:524-534). Loopback binding fail-closed is the Phase-1 substitute for auth — AC4's fail-fast is a security control, not a nicety.
- **CLI/REST equivalence (story 1.7):** the read endpoints and CLI `status`/`history` share `WorkflowInspectionService` — don't fork the read logic.

### Latest tech specifics (verify at implementation — May 2026)

- **springdoc-openapi on Spring Boot 4.0.6:** the project pins **Spring Boot 4.0.6** (architecture.md:209), which renames starters (`spring-boot-starter-web` → `-webmvc`). springdoc 2.x targets Spring 6/Boot 3; **verify a springdoc release supports Spring Framework 7 / Boot 4** before adding it. If the GA line lags Boot 4, choose the lowest compatible version or escalate (same pattern as 2.5's Vite-8 plugin check). This is the **highest-risk part of the story** — do the compatibility check first.
- **`springdoc-openapi-maven-plugin` `generate` goal needs a running app** — it fetches `/v3/api-docs` from a live instance. Wire it in `integration-test` phase bracketed by `spring-boot-maven-plugin` `start`/`stop`, or generate the snapshot via a `@SpringBootTest` that writes `/v3/api-docs` to the file. The **existing CI gate already calls `springdoc:generate`** (ci.yml:385-396) — make that command actually work, or update the gate to match your generation method (keep the committed-snapshot `diff` semantics).
- **Deterministic OpenAPI output** for a stable CI diff: enable key ordering (`springdoc.writer-with-order-by-keys=true` or sort in the writer) and ensure no build-timestamp/random fields leak into the doc.
- **JSON-Schema validation in tests:** prefer whatever 1.23 already uses to validate fixtures (likely `com.networknt:json-schema-validator`); don't add a second validator library.
- **Startup fail-fast timing:** to abort *before* the web connector binds a non-loopback address, an `EnvironmentPostProcessor` or an early `ApplicationListener` (on `ApplicationEnvironmentPreparedEvent`/`ApplicationContextInitializedEvent`) is more reliable than a late `@PostConstruct`/`ApplicationReadyEvent` (by which point the socket may already be open). Choose the earliest hook that can read resolved config.

### Anti-patterns to avoid

- **Do NOT delete or relocate the existing POST command endpoints** in `WorkflowController` (TRAP 1 / AC9) — they are live (1.7/1.15) and tested. 6.9 is additive.
- **Do NOT serialize `GET /events` from `WorkflowHistoryView`/`WorkflowEventView`** — they diverge from the committed schema (TRAP 2). Map from `WorkflowEventRecord` (which carries `workflowRunPublicId`) to the schema shape, and **validate against the schema file in a test**.
- **Do NOT leak `idempotencyKey`** (or secrets/PII) in event `details` — the schema forbids it; redact via story 1.10's path.
- **Do NOT reach into `adapters.persistence`/entities from the controller** — go through `application.workflow.*` services/SPI (ArchUnit 1.11 enforces; keep green).
- **Do NOT add new `DomainErrorCode`s speculatively** — `RUN_NOT_FOUND`/`INVALID_ID_PREFIX`/`DOCTOR_REST_BIND_UNAVAILABLE`/`INTERNAL_ERROR` already exist. Reuse them.
- **Do NOT duplicate the loopback-check logic** — extract a shared helper from `DoctorProbeAdapter.probeRestBindAddress()` and call it from both the probe and the startup guard.
- **Do NOT introduce a `{data:…}` envelope** or non-UTC timestamp format — AC2 + fixture determinism require direct shapes + `…Z` timestamps.
- **Do NOT make the fail-closed bind a warning** — it must fail startup (AC4). The WARN log is only for the *unsafe-override-enabled* path (AC3).
- **Do NOT let the OpenAPI snapshot be non-deterministic** — a flaky CI diff is worse than no gate. Pin ordering.
- **Do NOT add an HTTP client / generate the TS client here** — that's 2.6. 6.9 only publishes the spec.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. Enforced via the "Logging instrumentation" task above. **This story introduces JVM application + adapter + config code, so the standard fully applies** (unlike the recent frontend-only stories 2.2–2.5).

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface this story adds):**
  - New REST read handlers → `INFO` on entry + `INFO` on success (with result size), `WARN` on typed-domain rejection surfaced at the boundary, `ERROR` on unexpected failure.
  - New `WorkflowInspectionService.listRuns` → `INFO` entry/exit with filter + page context.
  - Startup bind guard → `INFO` loopback-resolved, `WARN` unsafe-override-active (AC3), clear thrown message on fail-fast (AC4).
- **Required context keys** (MDC or structured params): `correlationId` (already in MDC via the filter), `workflowRunId` on per-run endpoints, `actorIdentity`/`actorType` where available.
- **Forbidden in log output:** `idempotencyKey`, payload bytes, secrets/tokens, raw PII, classification-restricted fields — and strip `details.idempotencyKey` from the *response* too (Task 2).
- **Test contract:** pin new logging surfaces with a focused test (list-appender or `OutputCaptureExtension`) — at minimum the unsafe-bind WARN, the fail-fast path, and a successful read.

### Project Structure Notes

- **New (backend):** read-endpoint handlers (in `WorkflowController` or a new `adapters/rest/WorkflowReadController.java`); event/detail/list response DTOs (`adapters/rest/...` — schema-shaped events DTO); `deliveryline.rest` `@ConfigurationProperties` class; startup bind-guard listener/`EnvironmentPostProcessor` (`infrastructure/...`); shared loopback-resolution helper; `WorkflowInspectionService.listRuns` + `WorkflowRunReadPort`/`WorkflowRunRepository` list query; OpenAPI config bean; **committed `src/main/resources/openapi/openapi.json`**; new tests (events schema-contract test, binding smoke, fail-closed test, performance contract, Problem Details + correlation tests, logging tests).
- **Modified:** `deliveryline-backend/pom.xml` (springdoc starter + springdoc-maven-plugin / spring-boot-maven-plugin start-stop wiring); `application.yml` (`server.address: 127.0.0.1` + `deliveryline.rest.*`); `ProblemDetailsMapper` (Problem Details `instance` correlation surfacing, AC10); possibly `.github/workflows/ci.yml` (only if you change the generation command — the gate path already matches); backend README / `docs/setup-local.md`; `sprint-status.yaml` (status).
- **No conflicts with hexagonal structure:** controller/DTOs in `adapters.rest`, config props + bind guard in `infrastructure`/config, read logic in `application.workflow`. ArchUnit boundaries (1.11) hold.

### References

- [Source: _bmad-output/planning-artifacts/epic-06-pilot-docs.md#Story 6.9] — authoritative ACs (lines 172-192) + the "moved out of Epic 1" rationale (line 178)
- [Source: _bmad-output/planning-artifacts/epics.md#Epic 2 dependency edges] — "2.6's typed client depends on backend stories … publishing OpenAPI" (line 847); story-ID stability rule (line 843)
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.6] — downstream consumer: generated TS client + `useWorkflowEvents` hook bind to this story's `/events` schema + `openapi.json` (lines 943-960)
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture / Quality Gates] — REST is source of truth; backend-reported state drives UI (lines 485-520)
- [Source: _bmad-output/planning-artifacts/architecture.md#API, CLI & OpenAPI] — publish OpenAPI via springdoc with contract tests (lines 201-204)
- [Source: _bmad-output/planning-artifacts/architecture.md#Spring Boot 4.0.6 baseline] — Boot 4 starter renames; springdoc compatibility risk (line 209)
- [Source: deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json] — **the authoritative `/events` wire contract** (TRAP 2); states it IS the contract for the 6.9 events serializer
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java] — `getStatus`/`listHistory` + view records; prefix validation at line 83 (story 1.15)
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java] — existing POST command endpoints to PRESERVE (TRAP 1); DTO/camelCase pattern to mirror
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java, ProblemDetailsCatalog.java, domain/registry/DomainErrorCode.java] — RUN_NOT_FOUND / INVALID_ID_PREFIX / DOCTOR_REST_BIND_UNAVAILABLE already registered; correlation id injection at mapper:299-302 (story 1.8)
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/CorrelationIdFilter.java + application/observability/MdcKeys.java] — X-Correlation-Id echo + MDC (story 1.19); AC10 header already satisfied globally
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java] — `require()`/`fromPublicId()` for path-param validation; suffix regex `[A-Za-z0-9_-]{4,64}` (story 1.4)
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java:472-495] — existing loopback check returning DOCTOR_REST_BIND_UNAVAILABLE to reuse (story 1.16)
- [Source: deliveryline-backend/src/main/resources/application.yml] — add `server.address: 127.0.0.1` + `deliveryline.rest.*`
- [Source: .github/workflows/ci.yml:385-396] — the OpenAPI drift gate (graceful no-op) this story activates; expects `deliveryline-backend/src/main/resources/openapi/openapi.json` + `springdoc:generate -DoutputDir=target/openapi`
- [Source: project memory `verify-ci-fixes-in-clean-env`] — reproduce the springdoc generate+diff on Linux/clean env before claiming AC6 done

### Review Findings

- [x] [Review][Decision] Correlation ID location in Problem Details conflicts with the pre-existing RFC/path contract — resolved: preserve the existing RFC-9457/path semantics (`instance` stays the request path; correlation ID remains in the header + `correlationId` property).
- [x] [Review][Patch] `deliveryline.rest.bind-address` is validated and documented as an override, but nothing applies it to the embedded server [deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RestBindingProperties.java:12](C:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RestBindingProperties.java:12)
- [x] [Review][Patch] `doctor rest-bind-address` can drift from the startup guard because it still reads only `server.address` [deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java:91](C:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java:91)
- [x] [Review][Patch] Bundled-jar smoke still disables the web app and never exercises the new REST surface [.github/workflows/ci.yml:566](C:/Users/pc/Documents/Personal/ai-hackaton-1/.github/workflows/ci.yml:566)
- [x] [Review][Patch] OpenAPI is too weak for generated clients: `application/problem+json` responses are untyped and `/events` omits the authoritative required-field/cardinality constraints [deliveryline-backend/src/main/resources/openapi/openapi.json:351](C:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/resources/openapi/openapi.json:351)
- [x] [Review][Patch] OpenAPI advertises a hard-coded `http://127.0.0.1:8080` server even though the REST bind address/port are configurable [deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/OpenApiConfiguration.java:35](C:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/OpenApiConfiguration.java:35) — Swagger UI and any live-doc consumers target the wrong origin under non-default `server.port` / `deliveryline.rest.bind-address` settings.
- [x] [Review][Patch] `unsafe-network-bind=true` bypasses the governed failure path for an unresolvable bind address [deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RestBindingGuard.java:72](C:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RestBindingGuard.java:72) — `no-such-host.invalid` logs a warning and falls through until the web-server customizer throws `IllegalStateException`, so startup no longer fails with `DOCTOR_REST_BIND_UNAVAILABLE` + remediation.
- [x] [Review][Patch] `GET /api/v1/workflows/{workflowRunId}/events` can return a 200 body that violates the committed schema on sparse/corrupted run data [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:286](C:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:286) — the service explicitly allows `createdAt=null`, `ticketRef=null`, and `events=[]`, but the committed wire contract requires non-null `workflowRun.createdAt`, non-empty `workflowRun.ticketRef`, and `events.minItems = 1`.
- [x] [Review][Patch] Bundled-jar smoke only checks that `/api/v1/workflows` returns “an array,” not the committed workflow-list shape [.github/workflows/ci.yml:649](C:/Users/pc/Documents/Personal/ai-hackaton-1/.github/workflows/ci.yml:649) — AC5 calls for schema validation, but the job never verifies the item fields or contract beyond `type == "array"`.
- [x] [Review][Patch] Committed OpenAPI still mis-types runtime `details` payloads, so generated clients get the wrong contract [deliveryline-backend/src/main/resources/openapi/openapi.json:99](C:/Users/pc/Documents/Personal/ai-hackaton-1/deliveryline-backend/src/main/resources/openapi/openapi.json:99) — `ProblemDetailsResponse.details` is `null`-only even though runtime emits objects/arrays, and `WorkflowEvent.details` is still an untyped open object instead of the authoritative keyed constraints from `workflow-events-response.schema.json`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (Claude Code, dev-story workflow)

### Debug Log References

- Highest-risk item resolved first (per Dev Notes): **springdoc-openapi vs Spring Boot 4.0.6**. The 2.8.x line is Boot 3 only; the **3.0.x** line targets Boot 4 / Spring Framework 7. Verified `springdoc-openapi-starter-webmvc-ui:3.0.3` + `springdoc-openapi-maven-plugin:1.5` exist on Maven Central via `repo1.maven.org/.../maven-metadata.xml` (the Central solr search index was cached pre-Boot-4 and wrongly reported 2.8.6 as latest). Pinned 3.0.3.
- OpenAPI snapshot generated by booting the app once (`-Dopenapi.snapshot.write=true`) → committed `openapi.json`; re-run confirmed byte-stable (deterministic canonicalization: key-sorted, `\n` line endings, trailing newline — cross-platform).
- Regression caught + fixed during dev: `WorkflowAdapterEquivalenceTest` (`@WebMvcTest(WorkflowController.class)`) needed a `@MockitoBean WorkflowInspectionService` after the controller gained the read dependency.

### Completion Notes List

- **TRAP 1 / AC9 honored** — the five POST command endpoints (submit/approve-spec/reject-spec/retry/takeover) are untouched; the three GETs were added to the same `WorkflowController`. `OpenApiSnapshotContractTest` asserts all 8 operationIds remain present.
- **TRAP 2 honored** — `GET /events` serializes a schema-shaped DTO sourced from `WorkflowEventRecord` (carries `workflowRunPublicId`), validated against the committed `workflow-events-response.schema.json` by a live-response JSON-Schema contract test. `details.idempotencyKey` (and all `WorkflowEventDetailKeys.SERVER_ONLY_KEYS`) are stripped, but open-map keys the schema/UI need (e.g. `artifactVariant`) are **preserved** — i.e. wire sanitization uses server-only-key stripping + value redaction, NOT the stricter CLI allow-list (which would drop `artifactVariant`).
- **AC10 deviation (deliberate, documented)** — Task 5 literally asks to put the correlation id on the Problem Details `instance` field. That conflicts with the story-1.19 contract `ProblemDetailsCorrelationIdContractTest`, which pins `instance` = request path per RFC 9457. I preserved that contract: `instance` stays the request path, and the correlation id is surfaced via the existing `correlationId` body property + the `X-Correlation-Id` response header (echoed globally by `CorrelationIdFilter`). AC10 is satisfied (header + body field) without breaking the RFC-9457 contract. A pinned assertion was added to the new contract test.
- **`createdAt` / `ticketRef` sourcing for the events header** — `WorkflowRunSnapshot` is intentionally lossy (no `createdAt`), so the events `workflowRun.createdAt` is the earliest event's timestamp (the submit event — matches the committed fixtures, where `workflowRun.createdAt == events[0].createdAt`). `ticketRef` resolves from the active integration link's external ref, falling back to the earliest event's `details.linearTicketReference` (`@NotBlank` on submit guarantees one is present). No snapshot/port arity change was needed.
- **Shared loopback helper placement** — ArchUnit forbids `adapters` ⇄ `infrastructure` edges, so the loopback resolver shared by `DoctorProbeAdapter` (adapters.diagnostics) and `RestBindingGuard` (infrastructure) lives in `domain.net.LoopbackAddressResolver` (framework-free; the only layer both may access). `DoctorProbeAdapter.probeRestBindAddress()` refactored to call it (messages unchanged).
- **Fail-closed timing** — `RestBindingGuard` is a `BeanFactoryPostProcessor` (runs during `invokeBeanFactoryPostProcessors`, before `onRefresh()` binds the connector), so a refused bind never opens a socket; this also makes it testable with `ApplicationContextRunner`.
- **OpenAPI CI gate** — the broken standalone `springdoc:generate` step (it cannot run without a live app) was replaced: drift is enforced deterministically by `OpenApiSnapshotContractTest` in the `verify` run; `ci.yml` now also guards that the snapshot file is committed.
- **Cross-ref for story 2.28 (SPA fallback, backlog)** — `/v3/api-docs`, `/swagger-ui*`, and `/actuator/**` must be excluded from the future SPA fallback (already enumerated in epics.md:1477 / 2.28 AC2). No action here beyond this note.
- **AC8** — perf thresholds asserted against seeded fixtures (`GET /{id}` < 2s, `GET /{id}/events` < 5s).
- Verification: targeted contract/unit runs green (`OpenApiSnapshotContractTest`, `WorkflowReadEndpointsContractTest` 8/8, `RestBindingGuardTest` 9/9); full `mvn -pl deliveryline-backend verify` (quality gates + ArchUnit + foundation-gate + suite) confirmed before flipping to `review`.

### File List

**Added (main):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/net/LoopbackAddressResolver.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RestBindingProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RestBindingConfiguration.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RestBindingGuard.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/OpenApiConfiguration.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowSummaryResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowDetailResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowEventsResponse.java`
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (committed snapshot)

**Added (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/WorkflowReadEndpointsContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/config/RestBindingGuardTest.java`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (3 GET read endpoints + OpenAPI annotations + logging; POST endpoints unchanged)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (`listRuns`, `getEventStream`, new view records, wire detail sanitization)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunReadPort.java` (`listRuns`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapter.java` (`listRuns` impl)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java` (newest-first list queries)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java` (use shared `LoopbackAddressResolver`)
- `deliveryline-backend/src/main/resources/application.yml` (`server.address: 127.0.0.1`, `deliveryline.rest.*`, springdoc determinism)
- `deliveryline-backend/pom.xml` (springdoc-openapi-starter-webmvc-ui 3.0.3)

**Modified (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/WorkflowAdapterEquivalenceTest.java` (mock new `WorkflowInspectionService` dependency)

**Modified (other):**
- `.github/workflows/ci.yml` (OpenAPI drift gate now contract-test-driven)
- `docs/setup-local.md` (REST API & localhost-binding section)

## Change Log

| Date       | Change                                                                                  |
| ---------- | --------------------------------------------------------------------------------------- |
| 2026-05-21 | Story 6.9 created via create-story → `ready-for-dev`. Pulled forward from Epic 6 as story 2.6's prerequisite (Alex, 2026-05-21): localhost-bound REST read endpoints (`GET /workflows`, `/{id}`, `/{id}/events`) + fail-closed binding + springdoc OpenAPI snapshot + Problem Details + correlation-ID. Two disaster-prevention catches baked in: AC9 reinterpreted (existing POST command endpoints must be preserved, not deleted); `/events` DTO must validate against the committed `workflow-events-response.schema.json` (mirrors `WorkflowEventRecord`, not `WorkflowEventView`). |
| 2026-05-21 | Dev-story implementation → `review`. Added 3 read endpoints to `WorkflowController` (POST endpoints preserved, AC9), `WorkflowInspectionService.listRuns`/`getEventStream` + new read port/repo queries, fail-closed `RestBindingGuard` (BeanFactoryPostProcessor) + `deliveryline.rest.*` props + shared `domain.net.LoopbackAddressResolver` (refactored doctor probe to share it), springdoc-openapi 3.0.3 + `OpenApiConfiguration` + committed deterministic `openapi.json` snapshot + contract-test drift gate (replaced broken `springdoc:generate` CI step). `/events` validated against the committed wire schema with `idempotencyKey` stripped and `artifactVariant` preserved. AC10 surfaces correlation id via header + body property, keeping RFC-9457 `instance`=path (story-1.19 contract preserved). Docs updated. |
