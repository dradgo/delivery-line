# Story 3i.2: Filtered Ticket-Intake Browse (queryTickets by assignee + components)

Status: done

<!-- 2026-07-08 bmad-create-story context-engine pass (Opus 4.8 [1m]). Target sprint key: 3i-2-filtered-ticket-intake-browse. Epic 3i already in-progress (3i-1 done). Source: epic-03i-connector-expansion.md#Story 3i-2 + the 3i-1 done story. Delivers FR81 (filtered ticket intake by assignee + components). This is the FIRST REST + CLI + FE surface in Epic 3i (3i-1 was backend-only) — mind the OpenAPI/schema.d.ts regen cascade and the FE traps. -->

> **READ FIRST — what this story is and is NOT.**
> - It **adds a new read capability** to the ticket-source port: `TicketQueryResult queryTickets(TicketQuery)` (post-review shape; the story shipped `List<TicketSummary>` and code review added the `total`/`truncated` page) + a `supportsTicketQuery` capability flag (default `false`; **only JIRA flips it true** this story). Plus a REST endpoint, CLI command, and FE intake/browse view that lists candidate JIRA tickets and lets the operator start a governed run per selected ticket.
> - **NO new `ConnectorKind`. NO Flyway migration.** `supportsTicketQuery` is an in-code boolean on the `TicketSourceCapabilities` record — it never touches a DB CHECK. (3i-1 already added `ConnectorKind.JIRA` + Flyway V37.)
> - **NO new `WorkflowState` / `AllowedAction` / `WorkflowEventType`.** Runs are started through the **existing** `WorkflowCommandService.submit` path — no bespoke create seam.
> - **Reuse, do not reinvent:** the JIRA JQL machinery (`JiraRealAdapter.pollNewTickets` + `/rest/api/3/search`), the capability-gated skip pattern (`TicketSourceSubticketService.createIfCapable`), the submit path (`WorkflowController.submit` / FE `useSubmitWorkflow`), the `ProjectSelector` filter component, and the `OperatorQueue`/`OperatorFilterSidebar` list+filter view are all already built. This story wires them together for a new surface.

## Story

As an operator standing up governed runs,
I want to browse candidate JIRA tickets filtered by assignee and components and pick which ones to start,
so that I can pull a focused slice of my backlog into governance interactively, instead of polling everything updated-since.

## Acceptance Criteria

1. **Given** the `TicketSourceAdapter` port (story 3-32), **Then** a new method `List<TicketSummary> queryTickets(TicketQuery query)` is added, and `TicketSourceCapabilities` gains `supportsTicketQuery` (default `false`, appended as the **6th** boolean field). **JIRA** (`JiraRealAdapter` + `JiraMockAdapter`) implements it JQL-backed and reports `true` via `jiraDefaults()`; **Linear / GitLab-stub** keep `false` (their `queryTickets` throws `UnsupportedOperationException` — never invoked because the surface is capability-gated). `TicketQuery` and `TicketSummary` are **new neutral records in `domain.integration.ticketsource`** (no vendor type crosses the port — the `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` rule). Verified by the JIRA mock↔real parity contract + capability test.
2. **Given** the JIRA impl, **Then** `queryTickets` maps the filter to JQL (`assignee = "…" AND component in ("…","…") AND status = "…" ORDER BY updated DESC`) bounded by `limit` (→ `maxResults`); **an empty/absent filter field is OMITTED from the JQL** (never rendered as a match-all clause), and **every user-supplied value is escaped** (reuse `escapeJqlString`) — no JQL injection. Results map through the existing `toTicket(...)` → `TicketSummary{ticketRef, title, summary}` (title = JIRA `summary` field; summary = ADF-extracted `description`, **nullable/blank-tolerant** — a JIRA ticket with no description must not crash the mapping).
3. **Given** a REST intake surface, **Then** `GET /api/v1/projects/{projectId}/ticket-query?assignee=&components=&state=&limit=` (operationId `queryProjectTickets`) lists candidate tickets for the project's ticket source; a connector whose `supportsTicketQuery=false` (or a project with no resolvable ticket source) returns a **typed `404` ProblemDetails (`TICKET_QUERY_NOT_SUPPORTED`), never a 5xx** (capability-gated). `components` is a repeated/CSV multi-valued `@RequestParam List<String>`. OpenAPI snapshot + `schema.d.ts` regenerate (**NOT byte-identical**) and the `queryProjectTickets` operationId assertion is added to `OpenApiSnapshotContractTest`. **[AMENDED by code review, 2026-07-10 — approved by Alex]** The 200 response is a `CandidateTicketPage` **envelope** `{tickets, total, truncated}`, **not** a direct array: a bare array cannot distinguish a complete browse from one capped by `limit`, so the operator could never tell they were seeing a partial backlog. An upstream failure maps by `IntegrationFailureCategory` to a typed **`503 TICKET_QUERY_SOURCE_UNAVAILABLE` (retryable)** or **`502 TICKET_QUERY_SOURCE_FAILED` (non-retryable)** — never the opaque 500 an uncaught `TicketSourceAdapterException` used to produce.
4. **Given** selection, **Then** the operator selects one or several listed tickets and starts each as a governed run through the **existing** `WorkflowCommandService.submit` path (via the existing `POST /api/v1/workflows/submit-workflow` endpoint — **no bespoke create seam**); each submit is **independent + idempotency-keyed** (its own minted key — the batch-submission posture; one row's failure does not abort the others).
5. **Given** the FE, **Then** a new intake/browse view renders the candidate list with **assignee + component** filter controls (reusing the 3c-9 `ProjectSelector` for project scope and the `OperatorFilterSidebar` `CheckboxGroup`/fieldset pattern for multi-select) and a per-row "start run" action (reusing `useSubmitWorkflow`). URL-owned filters follow the TanStack `validateSearch`-parse + spread-on-every-nav discipline. `schema.d.ts` is regenerated **first** (before any FE code touches the new types).
6. **Given** accessibility + FE traps, **Then** the view is **axe-clean** (WCAG 2.1 AA via `expectNoA11yViolations`), covered by **Vitest** (filter controls drive the query params; results render; a `supportsTicketQuery=false`/404 project hides the surface; selection submits a run), and honors the **react-refresh-no-fn-export** (helpers in sibling `.ts`), **`useLiveAnnouncement` one-commit-lag** (`waitFor` in tests), **`validateSearch`-strips-unparsed-param**, **wire-sends-`null`-not-`undefined`** (`!= null` guards), and **vitest-cross-file-router-mock** conventions.
7. **Given** redaction, **Then** queried ticket titles/summaries carry the same content posture as any exposed `ticketRef` — **ids / lengths / `MdcKeys.sanitizeForLog` only in logs**; the JQL string, filter values, and ticket free-text are never logged in full, and the credential/token is never logged (the JIRA adapter already redacts on egress).
8. **Given** tests, **Then** coverage asserts: `queryTickets` maps filters → JQL with **omitted-field handling + escaping**; `supportsTicketQuery` defaults (`jiraDefaults`=true, `linearDefaults`/`noCreation`=false); a `supportsTicketQuery=false` project's endpoint returns `404` (not 5xx, not the run-start path); mock↔real JIRA parity for `queryTickets`; selection submits a run via the **existing** submit path; OpenAPI/`schema.d.ts` drift green with the new operationId; FE Vitest + axe; the `org.dradgo.application.integration.ticketsource` package holds its **≥0.80 line** jacoco floor.

## Tasks / Subtasks

- [x] **Task 1 — New neutral port types `TicketQuery` + `TicketSummary`** (AC: #1, #2)
  - [x] Create `domain/integration/ticketsource/TicketQuery.java` — `record TicketQuery(String assignee, List<String> components, String state, int limit)`. Compact constructor: `assignee`/`state` **nullable** (blank → treat as absent); `components` defensively copied to an unmodifiable `List` (null → empty); `limit` must be `> 0` (throw `IllegalArgumentException` otherwise) and clamp/validate against a max (recommend `MAX_LIMIT = 200`). Mirror `TicketRef`/`Ticket` compact-constructor style.
  - [x] Create `domain/integration/ticketsource/TicketSummary.java` — `record TicketSummary(TicketRef ticketRef, String title, String summary)`. Use the **neutral `TicketRef`** (NOT `String`), and make `summary` **nullable/blank-tolerant** (a JIRA ticket with an empty description is legal). **Do NOT reuse `org.dradgo.application.runner.TicketSummary`** — that is the context-bundle type (wrong package, `String` ref, and rejects blank title/summary). See Reconciliation R1.
  - [x] Update the ArchUnit remediation string in `ArchitectureRuleCatalog.java` (the `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` rule, ~line 846) to list the two new neutral records among the allowed port types. No other rule edit (placement under `domain.integration.ticketsource` is already covered).

- [x] **Task 2 — Add `supportsTicketQuery` capability flag + port method** (AC: #1)
  - [x] Add `queryTickets(TicketQuery query)` to `application/integration/ticketsource/TicketSourceAdapter.java`, slotted **right after `pollNewTickets`** (its closest structural sibling). Javadoc it as an **optional operation gated on `getCapabilities().supportsTicketQuery()`** — consumers MUST check the flag before calling (mirror the `createSubticket`/`buildSourceTicketUrl` javadoc contract).
  - [x] Extend `domain/integration/ticketsource/TicketSourceCapabilities.java` from 5 → **6** booleans, appending `supportsTicketQuery` **last** (record-component fan-out pattern). Thread it through the **only three** direct construction sites: `noCreation(...)` (add trailing `false`), `linearDefaults()` (add `false`), `jiraDefaults()` (add `true`). Grep confirms **no raw `new TicketSourceCapabilities(...)` outside these three factory bodies** — every other caller goes through a factory, so the arity change is contained.
  - [x] Implement `queryTickets` as a throwing default/override in the non-query adapters — `LinearRealAdapter`, `LinearMockAdapter`, `GitLabTicketSourceStubAdapter`: `throw new UnsupportedOperationException("queryTickets not supported for <kind>")` (never reached — the surface is capability-gated). Do **not** add it to the repo-host or Sentry ports (out of scope).

- [x] **Task 3 — JIRA `queryTickets` JQL impl** (AC: #1, #2, #7)
  - [x] `JiraRealAdapter.queryTickets(TicketQuery)` — build JQL from the query, **omitting absent/blank fields** (do not render match-all clauses): `assignee = "<escaped>"`, `component in ("<esc>","<esc>")`, `status = "<escaped>"`, joined with ` AND `, suffixed `ORDER BY updated DESC`. **Escape every user value with `escapeJqlString` (JiraRealAdapter ~line 623)** — JQL injection guard. POST `/rest/api/3/search` with `maxResults = query.limit()` (mirror the `pollNewTickets` request-body construction ~lines 210-236 and `ISSUE_FIELDS`), route through the shared `execute(...)`/`classify(...)` error ladder, map each issue via the existing `toTicket(...)` (~lines 718-747) then project `Ticket → TicketSummary(ticket.ticketRef(), ticket.title(), ticket.summary())`. No client-side re-filter needed beyond what JQL enforces (unlike `pollNewTickets`' minute-truncation guard — a browse query has no `since` boundary).
  - [x] `JiraMockAdapter.queryTickets(TicketQuery)` — deterministic, no network: synthesize `TicketSummary`s from the registered HAPPY scenario refs (mirror `pollNewTickets` ~lines 93-108), apply an in-memory filter approximating assignee/component/state where feasible, cap at `limit`. Keep it usable under `jira-mock` and sufficient for the parity contract.
  - [x] Both JIRA adapters' `getCapabilities()` already return `jiraDefaults()` — after Task 2 that advertises `supportsTicketQuery=true` automatically.

- [x] **Task 4 — Capability-gated application service** (AC: #3, #4)
  - [x] New `application/integration/ticketsource/TicketQueryService.java` (`@Service`) mirroring `TicketSourceSubticketService`. Method e.g. `List<TicketSummary> queryCandidateTickets(String projectReference, TicketQuery query)`: load the `Project` (via `ProjectManagementService.getProject(projectReference)` or the project repository), resolve the adapter via **`ProjectConnectorResolver.findTicketSource(project)`** (the **non-throwing** `Optional` variant — a miss is not a 500), then **check `adapter.getCapabilities().supportsTicketQuery()`**. On absent adapter OR capability off → throw a typed `DomainException(TICKET_QUERY_NOT_SUPPORTED)` (benign, mapped to 404 — mirror the subticket "skip when unsupported" posture, but surfaced as a typed error since this is a direct user request). On supported → return `adapter.queryTickets(query)`.
  - [x] The service is the ONLY caller of `queryTickets` (the port's caller-access doc restricts direct port calls to application services — CLI/REST must route through this service, not the adapter).
  - [x] Structured logging at entry/exit + the capability-skip branch (WARN) — ids/lengths only (Task 9).

- [x] **Task 5 — `TICKET_QUERY_NOT_SUPPORTED` DomainErrorCode (3-site fan-out)** (AC: #3)
  - [x] Add `TICKET_QUERY_NOT_SUPPORTED` to `domain/registry/DomainErrorCode.java`. Map it in `adapters/rest/ProblemDetailsCatalog.java` to **HTTP 404** (title e.g. "Ticket query not supported", non-retryable). Add its wire type URI to the `problemTypeUris` map in `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (the `new-DomainErrorCode → three sites` trap; `ProblemDetailsCoverageFoundationContract` auto-covers the catalog↔enum alignment). **Confirm with Alex** whether to add a new code vs. reuse an existing one (OQ-2) — 3i-1 set the precedent of adding connector-specific codes after confirmation.

- [x] **Task 6 — REST endpoint `GET /api/v1/projects/{projectId}/ticket-query`** (AC: #3)
  - [x] Add to `adapters/rest/ProjectController.java` (mirror `getProject` path-var + `OperatorController.listOperatorRuns` query-param idiom): `@GetMapping("/{projectId}/ticket-query")`, `@Operation(operationId = "queryProjectTickets", ...)`, params `@RequestParam(required=false) String assignee`, `@RequestParam(required=false) List<String> components`, `@RequestParam(required=false) String state`, `@RequestParam(required=false, defaultValue="50") int limit`. Build a `TicketQuery`, delegate to `TicketQueryService`, map to a new response DTO, return the **direct array** (list endpoints return no envelope here).
  - [x] New response DTO `adapters/rest/CandidateTicketResponse.java` (springdoc `@Schema` record + static `from(TicketSummary)`) — name it distinctly from `TicketSummary` to avoid the cross-package name shadow. Fields: `ticketRef`, `title`, nullable `summary`. Keep the controller thin (REST_CONTROLLERS_STAY_THIN ArchUnit rule) — all resolution in the service.
  - [x] Regenerate the OpenAPI snapshot: `scripts/regen-openapi.sh` (or `.ps1`) → `-Dopenapi.snapshot.write=true` writes `deliveryline-backend/src/main/resources/openapi/openapi.json`; review + commit. Add the `queryProjectTickets` operationId assertion to the project block in `OpenApiSnapshotContractTest.java` (~lines 114-122).

- [x] **Task 7 — CLI parity** (AC: #3)
  - [x] New CLI command under `adapters/cli/` (e.g. `TicketQueryCommands` with `@CommandGroup(name="tickets", prefix="deliveryline tickets")`, `@Command(name="query")` → `deliveryline tickets query`). Mind the **Spring Shell 4.0.2 prefix quirk** (the registered path is `prefix + " " + name`; `@CommandGroup.name` is help-only). Inject the same `TicketQueryService` in-process (**CLI calls the app service directly, not REST**). Options mirror `OperatorCommands.status`: `--project (required)`, `--assignee`, `--components` (repeatable/CSV), `--state`, `--limit`, `--format text|json`, `--correlation-id`, `--verbose`. Reuse `WorkflowCommandOutputs`, `WorkflowCliExitStatusExceptionMapper`, correlation-id MDC scope.
  - [x] Add/extend a CLI-registration IT (mirror `OperatorCliCommandRegistrationIT`) asserting `deliveryline tickets query` registers without colliding with `deliveryline submit`/`deliveryline operator …`.

- [x] **Task 8 — Frontend intake/browse view** (AC: #5, #6)
  - [x] **FIRST**: after the backend endpoint lands and `openapi.json` is regenerated, run `npm run generate-api` in `deliveryline-frontend/` to refresh `src/lib/api/schema.d.ts`; verify `npm run check:api` is green. Do this BEFORE writing any FE code that references the new types (the OpenAPI-regen→FE-client-drift cascade).
  - [x] New route `src/routes/intake/index.tsx` (a new top-level segment, mirroring `routes/operator/queue.tsx`): `validateSearch` **explicitly parses+re-emits** every filter key (`projectId`, `assignee`, `components`, `state`) or TanStack strips it; `loaderDeps`+`loader` warm the query; `handleFiltersChange` re-navigates with the **full** search object spread. Add a nav link in `src/features/workflows/AppShell.tsx` (mirror `QueueHomeLink`/`ProjectsLink`).
  - [x] New feature dir `src/features/intake/`: `IntakeBrowse.tsx` (list + filter sidebar), reuse `ProjectSelector` (project scope), an `OperatorFilterSidebar`-style `CheckboxGroup`/fieldset for `components` multi-select + a labelled text input for `assignee`. Per-row "start run" reuses `useSubmitWorkflow` (mints a per-attempt idempotency key, one independent submit per row). Query wiring: a new `queryOptions`/query fn calling `apiClient.GET('/api/v1/projects/{projectId}/ticket-query', ...)` via `unwrap`; a **404 → "not supported" hides the surface** (query `error`/empty gates the view — do NOT hardcode `kind === 'jira'` in the FE).
  - [x] **Traps:** put all non-JSX helpers/view-models in sibling `.ts` files (react-refresh `only-export-components` + `--max-warnings=0`); guard wire `null` with `!= null` / `?? undefined` before building route params; use `useLiveAnnouncement` for the result-count announcement (`announcements.ts` vocabulary).

- [x] **Task 9 — Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at: `TicketQueryService` entry/exit + capability-skip (WARN); each `JiraRealAdapter.queryTickets` external call (INFO start + outcome, WARN on classified failure); the REST controller entry/success; every `TicketSourceAdapterException` raise site.
  - [x] Parameterized logging only (`log.info("...", arg1, arg2)`) — never concatenation.
  - [x] Levels: `INFO` normal lifecycle (query received/resolved, result count), `WARN` recoverable anomalies (capability-off skip, classified connectivity failure, empty result), `ERROR` only unhandled failures. `DEBUG` for hot-path detail.
  - [x] Context keys: `correlationId`, `projectPublicId`, plus **counts/lengths only** for the query (e.g. `componentCount`, `resultCount`, `limit`) via `MdcKeys.sanitizeForLog(...)`. **NEVER** log the raw JQL, filter values, ticket titles/summaries, or the JIRA token/Basic-auth header.
  - [x] Pin the new log lines with a focused `OutputCaptureExtension`/list-appender test at the expected level per new branch (esp. the capability-skip WARN and the result-count INFO).

- [x] **Task 10 — Tests + docs** (AC: #8)
  - [x] `TicketSourceCapabilitiesTest` — add `supportsTicketQuery` assertions to the `jiraDefaults` (true), `linearDefaults` (false), and `noCreation` (false) cases. (No auto "count-the-flags" drift test exists — these manual assertions are the pin.)
  - [x] JIRA `queryTickets` mock↔real parity in `JiraTicketSourceParityFoundationContract` (stub `/rest/api/3/search`; assert both mock and real return the same `TicketSummary` shape). `JiraRealAdapterUnitTest.querySearchesByJqlAndMapsResults` (mirror `pollSearchesByJqlAndMapsResults` ~lines 192-208; assert the **JQL string** — omitted fields absent, values escaped, `maxResults`). `JiraMockAdapterUnitTest` query case.
  - [x] `TicketQueryService` unit test: supported → results; unsupported-capability / no-adapter → `TICKET_QUERY_NOT_SUPPORTED` (not a 500, not the submit path called).
  - [x] REST: `ProjectController` slice/IT for `queryProjectTickets` (200 with array; 404 ProblemDetails for a `supportsTicketQuery=false` project). `OpenApiSnapshotContractTest` green with the new operationId. If a new `DomainErrorCode` is added, `ProblemDetailsCoverageFoundationContract` + placeholder stay green.
  - [x] FE Vitest (`src/features/intake/__tests__/`): mock `@tanstack/react-router` per the cross-file convention; MSW-serve the ticket-query endpoint; assert filter controls drive query params, results render, 404 hides the surface, selection triggers the submit mutation; announcement asserted under `waitFor`; **axe-clean** via `expectNoA11yViolations`.
  - [x] Keep `org.dradgo.application.integration.ticketsource` at its **≥0.80 line** jacoco floor (the new `TicketQueryService` lands here); the `adapters…jira` query code rides the 0.75 bundle floor.
  - [x] Docs: add `ticket query` / `intake browse` vocabulary to `docs/glossary.md` (justify against NFR43 — minimize new concepts); append a JIRA-intake note to `docs/integrations/ticket-source-extension-contract.md`; note the new `supportsTicketQuery` capability in `docs/adr/0007-ticket-source-abstraction.md` (or the connector-resolution ADR).

### Review Findings

<!-- 2026-07-10 bmad-code-review (Opus 4.8 [1m]). Three layers: Blind Hunter (diff-only), Edge Case Hunter (diff + project), Acceptance Auditor (diff + spec). All 8 ACs verified MET; R1-R5 honored. 3 decision-needed, 5 patch, 1 defer, 3 dismissed. -->

**Decisions resolved with Alex (2026-07-10, AskUserQuestion) — each became a patch:**

- [x] [Review][Patch] JIRA browse failures escape as an opaque, mislabeled 500 — `TicketSourceAdapterException extends RuntimeException`, is not caught by `TicketQueryService` or `ProjectController`, and has no `@ExceptionHandler` in `ProblemDetailsMapper`. It falls through to `@ExceptionHandler(Exception.class)` (`ProblemDetailsMapper.java:343`) and renders as `500 INTERNAL_ERROR` with `retryable=false`. An expired per-project JIRA token (401) — the most common operational failure — becomes an undiagnosable internal error, and a transient 429/5xx/timeout is reported as non-retryable, the opposite of the truth. The exception's own Javadoc names the broken contract: *"The application service is responsible for converting this to the appropriate `DomainException`."* This endpoint is the **first REST surface to synchronously invoke a ticket-source adapter**, so the exposure is newly introduced by this story. **RESOLVED → category-mapped typed error:** catch in `TicketQueryService`; `NETWORK_API_FAILURE` maps to a new `TICKET_QUERY_SOURCE_UNAVAILABLE` (503, retryable=true), every other category to a new `TICKET_QUERY_SOURCE_FAILED` (502, retryable=false). Two new `DomainErrorCode`s, each through the 3-site fan-out. Add `@ApiResponses` entries + failure-path tests. [TicketQueryService.java; ProblemDetailsCatalog.java; DomainErrorCode.java; registry-api-schema-placeholders.json]
- [x] [Review][Patch] One malformed or permission-restricted issue aborts the entire browse page — `JiraRealAdapter.java:300-304` iterates `issues` calling `toTicketSummary(toTicket(issue))` with no per-issue guard. `toTicket` calls `requireText(fields,"summary")` (line 814) and `parseInstant(requireText(...))` (824-825), which throw `SYNC_FAILURE` on the first issue whose `summary` is hidden by the instance's field-level permission scheme or whose `created`/`updated` is absent/unparseable. That single issue takes down the whole page. The only degraded-issue fixture (`ISSUE_NO_DESCRIPTION_JSON`) still supplies `summary`, `created`, and `updated`, so the missing-title / missing-timestamp boundary is untested. **RESOLVED → skip-and-warn in browse only:** guard the per-issue map in `queryTickets`, count skips, log at WARN (`skippedUnmappableIssues={} of={}` — counts only, never ticket text, per AC7). `pollNewTickets` deliberately keeps its fail-fast batch semantics; comment the divergence (browse is interactive, poll is a retriable batch). Add fixtures for the missing-summary and unparseable-timestamp boundaries. [JiraRealAdapter.java:300-304]
- [x] [Review][Patch] Silent truncation at `limit` — no signal that results were capped. `JiraRealAdapter.queryTickets` sets `maxResults = query.limit()`, does no paging, and never reads the response's `total` field, which JIRA does return and which `pollNewTickets` already consumes. A browse matching 400 tickets and one matching exactly 50 render identically. **RESOLVED → plumb `total` through the port:** `queryTickets` returns a new `TicketQueryResult(List<TicketSummary> tickets, int total)`; the REST response becomes an envelope `{tickets, total, truncated}`. **This is a deliberate, Alex-approved deviation from AC3's "return the direct array (list endpoints return no envelope here)"** — update the AC text accordingly. Fan-out: port `TicketSourceAdapter`, `JiraRealAdapter`, `JiraMockAdapter`, the three throwing overrides (`LinearRealAdapter`, `LinearMockAdapter`, `GitLabTicketSourceStubAdapter`), `TicketQueryService`, `ProjectController` + response DTO, `WorkflowCommandOutputs` (CLI text/json renderers), `ticket-query.v1.schema.json`, `JiraTicketSourceParityFoundationContract`, `openapi.json` regen, `schema.d.ts` regen, `IntakeBrowse` (truncation hint).
- [x] [Review][Patch] Mojibake regression — a UTF-8 `⇒` re-corrupted to double-encoded `в‡’` [deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProjectController.java:571]
- [x] [Review][Patch] `CandidateTicket` schema declares no required fields, forcing phantom-null defensiveness in the FE [deliveryline-backend/src/main/java/org/dradgo/adapters/rest/CandidateTicketResponse.java:20-22]
- [x] [Review][Patch] `parseCsv` does not de-duplicate — `?components=a,a` renders duplicate React keys and duplicate DOM ids (a11y label ambiguity) [deliveryline-frontend/src/routes/intake/index.tsx:32-40]
- [x] [Review][Patch] `components` list size is unbounded — an arbitrarily large operator-supplied set renders an unbounded JQL string [deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketQuery.java:67-78]
- [x] [Review][Patch] `requireLimitInRange` Javadoc rationale is false for the upper bound, and domain/adapter limit contracts diverge (domain clamps, REST/CLI reject 400) [deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProjectController.java:645-654]
- [x] [Review][Defer] Vendor misnomer: a JIRA ticket ref is submitted through the `linearTicketReference` wire field [deliveryline-frontend/src/features/intake/IntakeBrowse.tsx] — deferred, pre-existing

## Dev Notes

### Central Reconciliations (live bindings win over the epic AC text)

- **R1 — `TicketSummary` is a NEW neutral record, not the runner one.** An `org.dradgo.application.runner.TicketSummary(String ticketRef, String title, String summary)` already exists — but it is the **context-bundle** projection (used by `TicketSummaryProvider`/`ContextBundleService`/`RunnerBroker`), it uses a `String` ref, and its compact constructor **rejects blank `title`/`summary`** — which would throw on a real JIRA ticket with an empty description. Create a **new** `domain/integration/ticketsource/TicketSummary(TicketRef ticketRef, String title, String summary)` (neutral `TicketRef`, nullable/blank-tolerant `summary`) so the port stays vendor-neutral (`TICKET_SOURCE_TYPES_MUST_NOT_LEAK`) and browse never crashes. The epic's `TicketSummary{ticketRef, title, summary}` names the **shape**, not the existing runner class. Name the REST DTO distinctly (`CandidateTicketResponse`) to avoid a same-simple-name import shadow. [Source: `application/runner/TicketSummary.java`; `domain/integration/ticketsource/Ticket.java`]
- **R2 — `supportsTicketQuery` is a capability flag only — NO Flyway, NO ConnectorKind.** It is the 6th boolean on the `TicketSourceCapabilities` record; it never appears in a DB CHECK. 3i-1 already landed `ConnectorKind.JIRA` + Flyway V37. Adding the field is a **3-factory edit** (`noCreation`→`false`, `linearDefaults`→`false`, `jiraDefaults`→`true`) — grep confirms no raw `new TicketSourceCapabilities(...)` outside those three bodies. There is **no** reflective capability-drift contract test, so the `TicketSourceCapabilitiesTest` assertions ARE the pin — add them or the flag is unguarded. [Source: `domain/integration/ticketsource/TicketSourceCapabilities.java`; `TicketSourceCapabilitiesTest.java`]
- **R3 — Capability gating returns a typed 404, not a benign silent skip.** The subticket precedent (`TicketSourceSubticketService.createIfCapable`) *silently* skips when `supportsTicketCreation` is off (it's a background side-effect). Here the caller is a **direct user browse request**, so surface it as a typed `DomainException(TICKET_QUERY_NOT_SUPPORTED)` → 404 ProblemDetails (never a 5xx). Resolve the adapter via `ProjectConnectorResolver.findTicketSource(project)` — the **non-throwing `Optional`** variant — so "no adapter" and "capability off" both funnel to the same clean 404. The FE hides the intake surface by catching that 404 (do not hardcode connector kind in the FE). [Source: `application/integration/ticketsource/TicketSourceSubticketService.java`; `application/project/ProjectConnectorResolver.java:106-112`]
- **R4 — Run-start reuses the existing submit path, not a new seam.** Each per-row "start run" is a single `WorkflowCommandService.submit(SubmitWorkflowCommand)` — reuse the existing `POST /api/v1/workflows/submit-workflow` endpoint + the FE `useSubmitWorkflow` mutation, minting a per-attempt idempotency key so each row is independent (the batch-submission posture; one failure doesn't abort the rest). `SubmitWorkflowCommand(actorIdentity, actorType, idempotencyKey, correlationId, linearTicketReference=<ticketRef>, projectReference=<projectId>)` — note `linearTicketReference` is the generic origin ref despite the legacy name. **Do not** add a new submit endpoint or command. [Source: `application/workflow/WorkflowCommandService.java:163`; `adapters/rest/WorkflowController.java:957-974`; FE `hooks/useSubmitWorkflow.ts`]
- **R5 — JQL is built by omission + escaping.** `queryTickets` mirrors `pollNewTickets`' `/rest/api/3/search` POST, but the JQL is assembled from only the **present** filter fields (an absent assignee/state/component clause is simply not emitted — never `assignee is not EMPTY` or an unbounded match), always bounded by `maxResults = limit`, `ORDER BY updated DESC`. Every user value passes `escapeJqlString` — the filters are operator-supplied, so this is an injection boundary. Reuse `execute(...)`/`classify(...)`/`toTicket(...)` unchanged. [Source: `adapters/integration/ticketsource/jira/JiraRealAdapter.java:210-258, 623-625, 718-747`]

### Source tree components to touch

- **New (backend main):** `domain/integration/ticketsource/TicketQuery.java`, `TicketSummary.java`; `application/integration/ticketsource/TicketQueryService.java`; `adapters/rest/CandidateTicketResponse.java`; `adapters/cli/TicketQueryCommands.java`; (`domain/registry/DomainErrorCode.java` gets `TICKET_QUERY_NOT_SUPPORTED` — edit).
- **Edit (backend main):** `application/integration/ticketsource/TicketSourceAdapter.java` (+`queryTickets`); `domain/integration/ticketsource/TicketSourceCapabilities.java` (6th flag + 3 factories); `adapters/integration/ticketsource/jira/JiraRealAdapter.java` + `JiraMockAdapter.java` (impl); `adapters/integration/ticketsource/linear/LinearRealAdapter.java` + `LinearMockAdapter.java` + `adapters/integration/ticketsource/gitlab/GitLabTicketSourceStubAdapter.java` (throwing override); `adapters/rest/ProjectController.java` (+endpoint); `adapters/rest/ProblemDetailsCatalog.java` (+code); `openapi/openapi.json` (regen).
- **Edit (backend test):** `TicketSourceCapabilitiesTest`, `JiraTicketSourceParityFoundationContract`, `JiraRealAdapterUnitTest`, `JiraMockAdapterUnitTest`, `OpenApiSnapshotContractTest` (operationId), `registry-api-schema-placeholders.json` (problemTypeUris), `ArchitectureRuleCatalog.java` (remediation string), a new `TicketQueryServiceTest`, a `ProjectController` query slice/IT, CLI registration IT.
- **New (FE):** `src/routes/intake/index.tsx`; `src/features/intake/` (view + sibling `.ts` helpers + `__tests__/`); a query-options module. **Edit (FE):** `src/lib/api/schema.d.ts` (regen), `src/features/workflows/AppShell.tsx` (nav link), possibly `src/lib/a11y/announcements.ts` (intake result vocabulary).

### Reuse (do NOT reinvent)

- **Port + neutral types:** `TicketSourceAdapter`, `TicketSourceCapabilities`, `Ticket`, `TicketRef`; `ProjectConnectorResolver.findTicketSource`.
- **JIRA machinery to mirror:** `JiraRealAdapter.pollNewTickets` (JQL + `/rest/api/3/search` + paging), `execute`/`classify`/`escapeJqlString`/`toTicket`; `JiraMockAdapter.pollNewTickets` + scenario map.
- **Capability-gated service shape:** `TicketSourceSubticketService.createIfCapable`.
- **Submit path:** `WorkflowController.submit` / `WorkflowCommandService.submit` / FE `useSubmitWorkflow`; batch posture ref `WorkflowBatchSubmissionService`.
- **REST idioms:** `OperatorController.listOperatorRuns` (`@RequestParam List<String>`), `ProjectController.getProject` (path var), `ProjectResponse`/`from(...)` DTO shape.
- **CLI idioms:** `OperatorCommands.status` (grouping/prefix quirk, `--format`), `WorkflowCommands.submit`.
- **FE:** `ProjectSelector`, `OperatorQueue`/`OperatorFilterSidebar` (`CheckboxGroup` fieldset), `routes/operator/queue.tsx` (validateSearch), `useSubmitWorkflow`, `useLiveAnnouncement`, `test/a11y/axe.ts`, `OperatorQueue.test.tsx` (router-mock + axe harness).

### Testing standards summary

- Surefire runs `*Test` (excludes tags architecture|integration|contract|known-failure); Testcontainers/Spring-context tests are `*IT` under Failsafe — name any query IT `*IT` or it leaks into Windows Surefire and reds CI.
- Mock JIRA HTTP with `MockRestServiceServer.bindTo(builder)` (repo convention — no WireMock/MockWebServer).
- ArchUnit `@ArchTest`s and `OpenApiSnapshotContractTest` (`@Tag("contract")`) run in **Failsafe** — verify there. `@ConfigurationProperties` unaffected (no new `deliveryline.*` keys this story).
- Coverage: `org.dradgo.application.integration.ticketsource` carries a named **0.80 line** floor (new `TicketQueryService` lands here); the `adapters…jira` query code rides the 0.75 bundle.
- FE: Vitest `vitest run`, `vitest-axe` (`expectNoA11yViolations`, WCAG 2.1 AA tags), MSW with `onUnhandledRequest:'error'`, `--max-warnings=0` on lint; regen `schema.d.ts` first and keep `check:api` green.

### Logging Requirements (project-wide standard)

Every story leaves the touched services observable enough to debug a production incident without re-deploying (enforced via the "Logging instrumentation" task).

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **Where (min surface):** `TicketQueryService` (entry/exit + capability-skip WARN), `JiraRealAdapter.queryTickets` (external call INFO + WARN-on-classified-failure), the REST controller (entry/success), each `TicketSourceAdapterException` raise.
- **Required context keys:** `correlationId`, `projectPublicId`, and **counts/lengths only** (`componentCount`, `resultCount`, `limit`) — via `MdcKeys.sanitizeForLog(...)`.
- **Forbidden in output:** the JQL string, raw filter values (assignee/components/state), ticket titles/summaries, the JIRA token / Basic-auth header. Ids / lengths / counts only.
- **Test contract:** pin new log lines with `OutputCaptureExtension`/list-appender.

### Project Structure Notes

- New neutral records live in `org.dradgo.domain.integration.ticketsource` (co-located with `Ticket`/`TicketRef`); JIRA impl stays in `adapters.integration.ticketsource.jira` (no `ADAPTER_PACKAGE_LAYOUT` edit). Vendor `RestClient`/DTOs never cross the port.
- `TicketQueryService` in `application.integration.ticketsource` keeps the app layer infra-free (`APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE`). The controller stays thin (`REST_CONTROLLERS_STAY_THIN`).
- FE view lives in `src/features/intake/`; non-JSX helpers in sibling `.ts` (react-refresh). New route `src/routes/intake/index.tsx` (do not hand-edit `routeTree.gen.ts` — it's generated).
- No new `WorkflowState`/`WorkflowEventType`/`AllowedAction`; one new `DomainErrorCode` (`TICKET_QUERY_NOT_SUPPORTED`, pending OQ-2 confirmation); no Flyway; no new `ConnectorKind`.

### Open Questions (non-blocking — recommended answers baked in)

- **OQ-1 (capability-off HTTP shape):** recommend **404 `TICKET_QUERY_NOT_SUPPORTED`** (typed ProblemDetails) so the FE cleanly hides the surface by catching it, vs. a 200-with-empty (masks the distinction) or 400 (implies a bad request). Confirm 404 vs 422.
- **OQ-2 (new DomainErrorCode vs reuse):** recommend a **new** `TICKET_QUERY_NOT_SUPPORTED` (distinct from `UNSUPPORTED_CONNECTOR_KIND`, which means "no adapter for kind" — a different condition). 3i-1 added connector-specific codes after confirming with Alex; confirm here too (the 3-site fan-out is cheap and semantically clearer).
- **OQ-3 (assignee semantics):** the `assignee` filter value is passed to JQL as-is (escaped). JIRA Cloud deprecated username filtering in favor of `accountId`. Recommend documenting that the operator supplies an `accountId` (or email, which JIRA resolves) — the value is opaque to us. Confirm whether a `currentUser()` convenience is wanted (out of scope otherwise).
- **OQ-4 (CLI command group):** recommend a new `deliveryline tickets query` group (prefix quirk handled) vs. folding under `deliveryline operator`. Confirm the verb/group naming with Alex (glossary/NFR43 vocabulary).
- **OQ-5 (route placement):** recommend a new top-level `/intake` route (mirrors `/operator/queue`) vs. nesting under `/projects/$projectId`. Confirm the IA with the UX pattern.

### References

- [Source: `_bmad-output/planning-artifacts/epic-03i-connector-expansion.md#Story 3i-2`] — ACs 1–8, Cross-Cutting Notes, FR81.
- [Source: `_bmad-output/implementation-artifacts/3i-1-jira-ticket-source.md`] — the JIRA adapter this story extends (JQL machinery, capabilities, parity contract, `jiraDefaults`).
- [Source: `_bmad-output/implementation-artifacts/3g-1-ticket-origin-snapshot-and-read-model.md`] — the `TicketSummary{ticketRef,title,summary}` read shape + the `TicketSourceCapabilities` record-fan-out precedent (5th flag `supportsSourceTicketUrl`).
- [Source: `docs/adr/0007-ticket-source-abstraction.md`] — opaque neutral types, capability-gated optional operations, `TICKET_SOURCE_TYPES_MUST_NOT_LEAK`.
- [Source: `docs/integrations/ticket-source-extension-contract.md`] — per-method contract, redaction-on-egress.
- [Source: `application/integration/ticketsource/TicketSourceSubticketService.java`] — the capability-gated service pattern.
- [Source: `adapters/rest/OperatorController.java:53-119`, `adapters/rest/ProjectController.java`] — the GET-with-multi-valued-query-param + path-var REST idioms.
- [Source: `src/routes/operator/queue.tsx`, `src/features/workflows/OperatorQueue.tsx`, `src/features/projects/components/ProjectSelector.tsx`, `src/features/workflows/hooks/useSubmitWorkflow.ts`] — the FE list+filter+submit substrate.

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context) — `claude-opus-4-8[1m]`

### Debug Log References

- `ProjectControllerContractTest.queryProjectTicketsRejectsOutOfRangeLimitWith400` first returned **500**, not 400. Root cause: `@Validated` on `ProjectController` routes `@Min`/`@Max` on a `@RequestParam` through the AOP `MethodValidationPostProcessor`, which raises `ConstraintViolationException` — a type `ProblemDetailsMapper` has no handler for. (The mapper handles `HandlerMethodValidationException`, which Spring only raises when the class is *not* an AOP method-validation proxy.) Replaced bean validation with an explicit `requireLimitInRange` guard throwing `INVALID_COMMAND_PAYLOAD`, mirroring the sibling `requireNonBlankIdempotencyKey` in the same class. `TicketQuery`'s `IllegalArgumentException` is now a pure domain backstop, unreachable from REST/CLI.
- `IntakeBrowse.test.tsx` — two tests failed with `ReferenceError: ResizeObserver is not defined` once a component checkbox rendered. Radix's `Checkbox` measures its indicator via `useSize` → `ResizeObserver`, absent in jsdom. Added the stub `OperatorQueue.test.tsx` already installs.
- **Mid-run the working tree was reset**: a concurrent process stashed this story's work as `3i-2-wip-preserve` and committed two unrelated 3h-4 fixes (`59e4634` em-dash mojibake restore, `cc95710` spotless reflow). Recovered via `git stash apply`; the single conflict (`ProjectController.java`) was resolved in favour of the committed em-dash/spotless formatting, with this story's endpoint re-layered on top. The stash also re-injected CP1251 mojibake on 12 comment lines adjacent to my hunks — repaired. Verified afterwards: `ProjectController.java` is +91/−1 vs HEAD, contains zero mojibake, and `sprint-status.yaml` is a clean 1-line diff.

### Completion Notes List

**Delivered (all 10 tasks, all 8 ACs).** `queryTickets` + `supportsTicketQuery` on the ticket-source port (JIRA-only, JQL-backed); capability-gated `TicketQueryService`; typed `TICKET_QUERY_NOT_SUPPORTED` → 404; `GET /api/v1/projects/{projectId}/ticket-query`; `deliveryline tickets query` CLI; `/intake` FE browse with per-row governed run-start.

**Open questions resolved with Alex (AskUserQuestion):**
- **OQ-1 + OQ-2** → new `DomainErrorCode.TICKET_QUERY_NOT_SUPPORTED` mapped to **404** (3-site fan-out), not a reuse of `UNSUPPORTED_CONNECTOR_KIND`/400.
- **OQ-3** → `assignee` stays an **opaque escaped passthrough** (accountId or resolvable email). No `currentUser()` sentinel: it would carve a hole in the escape-everything injection boundary.
- **OQ-4** → new CLI group `deliveryline tickets query` (`prefix="deliveryline tickets"`).
- **OQ-5** → top-level FE route `/intake` (mirrors `/operator/queue`).

**Findings worth carrying forward:**
1. **`JiraTicketSourceParityFoundationContract` was inert.** Story 3i-1 authored it but never registered it in `FoundationGateVerificationTest`, and its `*FoundationContract` name matches no Surefire/Failsafe include pattern — so it had **never executed**. Registered as Contract #27. Doing so immediately exposed a **broken fixture in 3i-1's own test**: `verifyConnectivityProbeIsReachableAndAuthenticatedInBoth` stubbed `/rest/api/3/project/search` with an empty `values` array, which the adapter *correctly* reports as `authenticated=false` ("no reachable projects visible"). Fixed the fixture to return one visible project. Both 3i-1's and 3i-2's parity assertions now actually run.
2. **R1 confirmed, and load-bearing.** Reusing `application.runner.TicketSummary` for the REST DTO would have tripped `REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER` (that rule bans `org.dradgo.application.runner..` from `adapters.rest`), on top of the blank-summary throw the story predicted.
3. **`ProjectConnectorResolver.bindTicketSourceCredential` was a non-issue.** `CredentialBoundTicketSourceAdapter.withProjectCredential` returns a new instance of the same adapter class, so the credential-bound view inherits `queryTickets` for free — no decorator to update.
4. **Design gap the story did not anticipate:** the `components` filter has **no closed vocabulary** to enumerate (JIRA components are per-project strings, and `CandidateTicket` does not carry them), so the operator-queue `CheckboxGroup`-over-a-fixed-enum pattern does not transfer. Implemented as the same accessible `<fieldset>`/`<legend>` + labelled-checkbox pattern over an **operator-defined** option set: type a component to add it, uncheck to remove. Documented in `IntakeFilterSidebar.tsx`.
5. **`actorIdentity` is deliberately NOT a URL search param.** It is operator PII; a URL carries it into browser history, referrer headers, and proxy access logs. It lives in component state. The other four filters are URL-owned.

**Redaction posture (AC7).** The JQL string, the filter values (assignee/components/state), and ticket titles/summaries never reach a log at any level — counts and booleans only (`assigneeFiltered`, `componentCount`, `stateFiltered`, `limit`, `resultCount`). Pinned by three list-appender tests: `JiraRealAdapterUnitTest.queryLogsCountsAndFlagsButNeverTheJqlOrFilterValuesOrTicketText`, `TicketQueryServiceTest.{capabilitySkipLogsAtWarnAndNeverLogsFilterValues, successPathLogsResultCountButNeverTicketText}`, and `ProjectControllerContractTest.queryProjectTicketsNeverLogsFilterValuesOrTicketText`.

**No new** `WorkflowState` / `AllowedAction` / `WorkflowEventType` / `ConnectorKind` / `FailureCategory`; **no Flyway migration**; **no bespoke create seam** (run-start reuses `POST /api/v1/workflows/submit-workflow`). One new `DomainErrorCode`.

**Verification (all green — re-run after the 2026-07-10 code-review patches):**
- Backend Surefire: **1781 / 0 failures** (16 skipped) — was 1764 pre-review, 1733 pre-story.
- Backend Failsafe: **1008 tests / 0**, incl. `ArchitectureBoundaryTest` 60/60, `RegistryContractTest` 23/23, `OpenApiSnapshotContractTest` (new `queryProjectTickets` operationId assertion), `TicketQueryCliCommandRegistrationIT`.
- Foundation gate (`-Pfoundation-gate`): **70 / 0**, including Contract #27. Contract #27's delegate-run was **empirically verified** to execute the new `aCappedPageReportsTruncationInBoth` parity test (a deliberately sabotaged assertion turned the gate red and named the method; then reverted) — the class matches no Surefire/Failsafe include pattern, so the delegate is its only execution path.
- Spotless clean · Checkstyle **0 violations** · SpotBugs pass.
- Frontend: `vitest` **1340 / 0** (126 files), `tsc -b` clean (typechecks tests, unlike `--noEmit`), `eslint --max-warnings=0` clean, `prettier --check` clean, `check:api` in sync, `check:a11y` 4/0, `check:routes` 9/0. Intake view is axe-clean (WCAG 2.1 AA).
- `openapi.json` regenerated: **+169 / −0**; `schema.d.ts` **+118 / −0**. `CandidateTicket` now declares `required: [ticketRef, title]`; new `CandidateTicketPage`; endpoint documents 200/400/404/502/503.
- Mojibake scan: **zero** double-encoded codepoints across backend main + test (the two remaining Cyrillic hits are pre-existing and intentional — `EnvelopeCredentialCipherTest`'s unicode fixture).

### Code-review outcomes (2026-07-10)

Three review layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 8 ACs verified MET, R1–R5 honored, no JQL-injection defect. 3 decision-needed + 5 patch findings, all resolved and applied; 1 deferred; 3 dismissed as noise.

The three findings that mattered:
1. **A mojibake regression at `ProjectController.java:571`** — a UTF-8 `⇒` re-corrupted to double-encoded `в‡’`, almost certainly injected by the mid-run `git stash apply` recovery. The Debug Log's claim that the file "contains zero mojibake" was **false**. It survived a fully green verification run because it sits in a `//` comment, not an `@Operation` summary, so `OpenApiSnapshotContractTest` never saw it. Fixed; it was the only such line in the backend.
2. **Every JIRA failure rendered as an opaque `500 INTERNAL_ERROR, retryable=false`** — this endpoint is the first REST surface to call a ticket-source adapter synchronously, and `TicketSourceAdapterException`'s own Javadoc says the application service must translate it. It didn't. An expired token was undiagnosable; a transient 429 was labelled non-retryable. Now category-mapped to 503/502.
3. **One permission-restricted issue killed the whole browse page** — `requireText(fields,"summary")` throws inside an unguarded map loop, and JIRA field-level security routinely hides `summary` from a browsing account. Now skipped-and-warned (browse only; `pollNewTickets` keeps fail-fast batch semantics).

Plus: silent truncation at `limit` (the response discarded JIRA's `total`) → `TicketQueryResult` + REST envelope; unmarked-required schema fields forcing `?? ''` in the FE; a non-deduplicating `parseCsv` yielding duplicate DOM ids from `?components=a,a`; an unbounded `components` set rendering an unbounded JQL string; and a `requireLimitInRange` Javadoc whose stated rationale was false for the upper bound (the constructor clamps there rather than throwing).

**Deferred:** the `linearTicketReference` wire-field misnomer now carries JIRA refs — pre-existing contract, recorded in `deferred-work.md`.

Uncommitted.

### File List

**New — backend main**
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketQuery.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketSummary.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketQueryResult.java` (code review — carries `total` + `truncated`)
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketQueryService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/CandidateTicketResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/CandidateTicketPageResponse.java` (code review — the page envelope)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/TicketQueryCommands.java`
- `deliveryline-backend/src/main/resources/schemas/cli/ticket-query.v1.schema.json`

**Modified — backend main**
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapter.java` (+`queryTickets`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketSourceCapabilities.java` (6th flag + 3 factories)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/jira/JiraRealAdapter.java` (JQL browse)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/jira/JiraMockAdapter.java` (deterministic browse)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/LinearRealAdapter.java` (throwing override)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/LinearMockAdapter.java` (throwing override)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/gitlab/GitLabTicketSourceStubAdapter.java` (throwing override)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (+`TICKET_QUERY_NOT_SUPPORTED`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (→ 404, non-retryable)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProjectController.java` (+`queryProjectTickets`, +`requireLimitInRange`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java` (+`renderTicketQueryText/Json`)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated, additive)

**New — backend test**
- `deliveryline-backend/src/test/java/org/dradgo/domain/integration/ticketsource/TicketQueryTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/ticketsource/TicketQueryServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/TicketQueryCliCommandRegistrationIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/TicketQueryJsonSchemaContractTest.java`

**Modified — backend test**
- `deliveryline-backend/src/test/java/org/dradgo/domain/integration/ticketsource/TicketSourceCapabilitiesTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/ticketsource/jira/JiraRealAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/ticketsource/jira/JiraMockAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProjectControllerContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectConnectorResolverTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/JiraTicketSourceParityFoundationContract.java` (+3i-2 parity; fixed inert 3i-1 fixture)
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java` (+Contract #27)
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`

**New — frontend**
- `deliveryline-frontend/src/routes/intake/index.tsx`
- `deliveryline-frontend/src/features/intake/IntakeBrowse.tsx`
- `deliveryline-frontend/src/features/intake/IntakeFilterSidebar.tsx`
- `deliveryline-frontend/src/features/intake/intakeView.ts`
- `deliveryline-frontend/src/features/intake/intakeView.test.ts`
- `deliveryline-frontend/src/features/intake/__tests__/IntakeBrowse.test.tsx`
- `deliveryline-frontend/src/lib/queryKeys/intakeKeys.ts`

**Modified — frontend**
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated)
- `deliveryline-frontend/src/lib/api/queryOptions.ts` (+`candidateTicketsQueryOptions`)
- `deliveryline-frontend/src/lib/a11y/announcements.ts` (+intake vocabulary)
- `deliveryline-frontend/src/features/workflows/AppShell.tsx` (+`IntakeLink`)
- `deliveryline-frontend/src/features/workflows/hooks/useSubmitWorkflow.ts` (+optional `projectReference`)

**Docs**
- `docs/glossary.md` (+`ticket query`, +`intake browse`)
- `docs/integrations/ticket-source-extension-contract.md` (+`queryTickets` contract, +capability factories note)
- `docs/adr/0007-ticket-source-abstraction.md` (+story 3i-2 section)

**Process**
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/3i-2-filtered-ticket-intake-browse.md`

## Change Log

| Date | Change |
| --- | --- |
| 2026-07-10 | `ready-for-dev → in-progress`: 4 open questions resolved with Alex (new 404 error code; opaque assignee; `deliveryline tickets query`; top-level `/intake`). |
| 2026-07-10 | Tasks 1–3: neutral `TicketQuery`/`TicketSummary` records, `supportsTicketQuery` capability (6th flag, 3 factories), `queryTickets` port method, JIRA JQL browse (build-by-omission + escaped values), throwing overrides for Linear/GitLab. |
| 2026-07-10 | Tasks 4–6: capability-gated `TicketQueryService`; `TICKET_QUERY_NOT_SUPPORTED` 3-site fan-out → 404; `GET /projects/{id}/ticket-query` + `CandidateTicketResponse`; OpenAPI snapshot regenerated (+119/−0). |
| 2026-07-10 | Task 7: `deliveryline tickets query` CLI + `ticket-query.v1` JSON schema + registration IT. |
| 2026-07-10 | Task 8: `/intake` route + `IntakeBrowse`/`IntakeFilterSidebar`; per-row independent idempotency-keyed submit; 404 hides the surface; axe-clean. |
| 2026-07-10 | Task 9: structured logging (counts/flags only) pinned by four list-appender tests. |
| 2026-07-10 | Task 10: tests + docs. Registered the previously-inert `JiraTicketSourceParityFoundationContract` as foundation Contract #27, which exposed and fixed a broken connectivity fixture inherited from 3i-1. |
| 2026-07-10 | Recovered the working tree after a concurrent process stashed this story's WIP (`3i-2-wip-preserve`) to land two unrelated 3h-4 commits; resolved the `ProjectController.java` conflict in favour of the committed em-dash/spotless formatting and repaired 12 re-injected mojibake lines. |
| 2026-07-10 | `in-progress → review`: full verification green (Surefire 1764/0, Failsafe 1002, foundation gate 53/0, ArchUnit 60/60, FE 1332/0, all lint/format/a11y/api-drift gates). |
| 2026-07-10 | **Code review (3 adversarial layers).** 8 patches applied: mojibake regression at `ProjectController.java:571`; category-mapped `TICKET_QUERY_SOURCE_UNAVAILABLE` (503, retryable) / `TICKET_QUERY_SOURCE_FAILED` (502) replacing an opaque 500; per-issue skip-and-warn in `queryTickets`; `TicketQueryResult` + `CandidateTicketPage` envelope surfacing `total`/`truncated` (**approved AC3 deviation**); `CandidateTicket.ticketRef`/`title` marked required; `parseCsv` de-duplication; `TicketQuery.MAX_COMPONENTS` bound; corrected `requireLimitInRange` Javadoc. Re-verified green: Surefire 1781/0, Failsafe 1008/0, foundation gate 70/0, FE 1340/0. |
