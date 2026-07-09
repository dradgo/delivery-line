# Story 3i.2: Filtered Ticket-Intake Browse (queryTickets by assignee + components)

Status: ready-for-dev

<!-- 2026-07-08 bmad-create-story context-engine pass (Opus 4.8 [1m]). Target sprint key: 3i-2-filtered-ticket-intake-browse. Epic 3i already in-progress (3i-1 done). Source: epic-03i-connector-expansion.md#Story 3i-2 + the 3i-1 done story. Delivers FR81 (filtered ticket intake by assignee + components). This is the FIRST REST + CLI + FE surface in Epic 3i (3i-1 was backend-only) — mind the OpenAPI/schema.d.ts regen cascade and the FE traps. -->

> **READ FIRST — what this story is and is NOT.**
> - It **adds a new read capability** to the ticket-source port: `List<TicketSummary> queryTickets(TicketQuery)` + a `supportsTicketQuery` capability flag (default `false`; **only JIRA flips it true** this story). Plus a REST endpoint, CLI command, and FE intake/browse view that lists candidate JIRA tickets and lets the operator start a governed run per selected ticket.
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
3. **Given** a REST intake surface, **Then** `GET /api/v1/projects/{projectId}/ticket-query?assignee=&components=&state=&limit=` (operationId `queryProjectTickets`) lists candidate tickets for the project's ticket source; a connector whose `supportsTicketQuery=false` (or a project with no resolvable ticket source) returns a **typed `404` ProblemDetails (`TICKET_QUERY_NOT_SUPPORTED`), never a 5xx** (capability-gated). `components` is a repeated/CSV multi-valued `@RequestParam List<String>`. OpenAPI snapshot + `schema.d.ts` regenerate (**NOT byte-identical**) and the `queryProjectTickets` operationId assertion is added to `OpenApiSnapshotContractTest`.
4. **Given** selection, **Then** the operator selects one or several listed tickets and starts each as a governed run through the **existing** `WorkflowCommandService.submit` path (via the existing `POST /api/v1/workflows/submit-workflow` endpoint — **no bespoke create seam**); each submit is **independent + idempotency-keyed** (its own minted key — the batch-submission posture; one row's failure does not abort the others).
5. **Given** the FE, **Then** a new intake/browse view renders the candidate list with **assignee + component** filter controls (reusing the 3c-9 `ProjectSelector` for project scope and the `OperatorFilterSidebar` `CheckboxGroup`/fieldset pattern for multi-select) and a per-row "start run" action (reusing `useSubmitWorkflow`). URL-owned filters follow the TanStack `validateSearch`-parse + spread-on-every-nav discipline. `schema.d.ts` is regenerated **first** (before any FE code touches the new types).
6. **Given** accessibility + FE traps, **Then** the view is **axe-clean** (WCAG 2.1 AA via `expectNoA11yViolations`), covered by **Vitest** (filter controls drive the query params; results render; a `supportsTicketQuery=false`/404 project hides the surface; selection submits a run), and honors the **react-refresh-no-fn-export** (helpers in sibling `.ts`), **`useLiveAnnouncement` one-commit-lag** (`waitFor` in tests), **`validateSearch`-strips-unparsed-param**, **wire-sends-`null`-not-`undefined`** (`!= null` guards), and **vitest-cross-file-router-mock** conventions.
7. **Given** redaction, **Then** queried ticket titles/summaries carry the same content posture as any exposed `ticketRef` — **ids / lengths / `MdcKeys.sanitizeForLog` only in logs**; the JQL string, filter values, and ticket free-text are never logged in full, and the credential/token is never logged (the JIRA adapter already redacts on egress).
8. **Given** tests, **Then** coverage asserts: `queryTickets` maps filters → JQL with **omitted-field handling + escaping**; `supportsTicketQuery` defaults (`jiraDefaults`=true, `linearDefaults`/`noCreation`=false); a `supportsTicketQuery=false` project's endpoint returns `404` (not 5xx, not the run-start path); mock↔real JIRA parity for `queryTickets`; selection submits a run via the **existing** submit path; OpenAPI/`schema.d.ts` drift green with the new operationId; FE Vitest + axe; the `org.dradgo.application.integration.ticketsource` package holds its **≥0.80 line** jacoco floor.

## Tasks / Subtasks

- [ ] **Task 1 — New neutral port types `TicketQuery` + `TicketSummary`** (AC: #1, #2)
  - [ ] Create `domain/integration/ticketsource/TicketQuery.java` — `record TicketQuery(String assignee, List<String> components, String state, int limit)`. Compact constructor: `assignee`/`state` **nullable** (blank → treat as absent); `components` defensively copied to an unmodifiable `List` (null → empty); `limit` must be `> 0` (throw `IllegalArgumentException` otherwise) and clamp/validate against a max (recommend `MAX_LIMIT = 200`). Mirror `TicketRef`/`Ticket` compact-constructor style.
  - [ ] Create `domain/integration/ticketsource/TicketSummary.java` — `record TicketSummary(TicketRef ticketRef, String title, String summary)`. Use the **neutral `TicketRef`** (NOT `String`), and make `summary` **nullable/blank-tolerant** (a JIRA ticket with an empty description is legal). **Do NOT reuse `org.dradgo.application.runner.TicketSummary`** — that is the context-bundle type (wrong package, `String` ref, and rejects blank title/summary). See Reconciliation R1.
  - [ ] Update the ArchUnit remediation string in `ArchitectureRuleCatalog.java` (the `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` rule, ~line 846) to list the two new neutral records among the allowed port types. No other rule edit (placement under `domain.integration.ticketsource` is already covered).

- [ ] **Task 2 — Add `supportsTicketQuery` capability flag + port method** (AC: #1)
  - [ ] Add `queryTickets(TicketQuery query)` to `application/integration/ticketsource/TicketSourceAdapter.java`, slotted **right after `pollNewTickets`** (its closest structural sibling). Javadoc it as an **optional operation gated on `getCapabilities().supportsTicketQuery()`** — consumers MUST check the flag before calling (mirror the `createSubticket`/`buildSourceTicketUrl` javadoc contract).
  - [ ] Extend `domain/integration/ticketsource/TicketSourceCapabilities.java` from 5 → **6** booleans, appending `supportsTicketQuery` **last** (record-component fan-out pattern). Thread it through the **only three** direct construction sites: `noCreation(...)` (add trailing `false`), `linearDefaults()` (add `false`), `jiraDefaults()` (add `true`). Grep confirms **no raw `new TicketSourceCapabilities(...)` outside these three factory bodies** — every other caller goes through a factory, so the arity change is contained.
  - [ ] Implement `queryTickets` as a throwing default/override in the non-query adapters — `LinearRealAdapter`, `LinearMockAdapter`, `GitLabTicketSourceStubAdapter`: `throw new UnsupportedOperationException("queryTickets not supported for <kind>")` (never reached — the surface is capability-gated). Do **not** add it to the repo-host or Sentry ports (out of scope).

- [ ] **Task 3 — JIRA `queryTickets` JQL impl** (AC: #1, #2, #7)
  - [ ] `JiraRealAdapter.queryTickets(TicketQuery)` — build JQL from the query, **omitting absent/blank fields** (do not render match-all clauses): `assignee = "<escaped>"`, `component in ("<esc>","<esc>")`, `status = "<escaped>"`, joined with ` AND `, suffixed `ORDER BY updated DESC`. **Escape every user value with `escapeJqlString` (JiraRealAdapter ~line 623)** — JQL injection guard. POST `/rest/api/3/search` with `maxResults = query.limit()` (mirror the `pollNewTickets` request-body construction ~lines 210-236 and `ISSUE_FIELDS`), route through the shared `execute(...)`/`classify(...)` error ladder, map each issue via the existing `toTicket(...)` (~lines 718-747) then project `Ticket → TicketSummary(ticket.ticketRef(), ticket.title(), ticket.summary())`. No client-side re-filter needed beyond what JQL enforces (unlike `pollNewTickets`' minute-truncation guard — a browse query has no `since` boundary).
  - [ ] `JiraMockAdapter.queryTickets(TicketQuery)` — deterministic, no network: synthesize `TicketSummary`s from the registered HAPPY scenario refs (mirror `pollNewTickets` ~lines 93-108), apply an in-memory filter approximating assignee/component/state where feasible, cap at `limit`. Keep it usable under `jira-mock` and sufficient for the parity contract.
  - [ ] Both JIRA adapters' `getCapabilities()` already return `jiraDefaults()` — after Task 2 that advertises `supportsTicketQuery=true` automatically.

- [ ] **Task 4 — Capability-gated application service** (AC: #3, #4)
  - [ ] New `application/integration/ticketsource/TicketQueryService.java` (`@Service`) mirroring `TicketSourceSubticketService`. Method e.g. `List<TicketSummary> queryCandidateTickets(String projectReference, TicketQuery query)`: load the `Project` (via `ProjectManagementService.getProject(projectReference)` or the project repository), resolve the adapter via **`ProjectConnectorResolver.findTicketSource(project)`** (the **non-throwing** `Optional` variant — a miss is not a 500), then **check `adapter.getCapabilities().supportsTicketQuery()`**. On absent adapter OR capability off → throw a typed `DomainException(TICKET_QUERY_NOT_SUPPORTED)` (benign, mapped to 404 — mirror the subticket "skip when unsupported" posture, but surfaced as a typed error since this is a direct user request). On supported → return `adapter.queryTickets(query)`.
  - [ ] The service is the ONLY caller of `queryTickets` (the port's caller-access doc restricts direct port calls to application services — CLI/REST must route through this service, not the adapter).
  - [ ] Structured logging at entry/exit + the capability-skip branch (WARN) — ids/lengths only (Task 9).

- [ ] **Task 5 — `TICKET_QUERY_NOT_SUPPORTED` DomainErrorCode (3-site fan-out)** (AC: #3)
  - [ ] Add `TICKET_QUERY_NOT_SUPPORTED` to `domain/registry/DomainErrorCode.java`. Map it in `adapters/rest/ProblemDetailsCatalog.java` to **HTTP 404** (title e.g. "Ticket query not supported", non-retryable). Add its wire type URI to the `problemTypeUris` map in `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (the `new-DomainErrorCode → three sites` trap; `ProblemDetailsCoverageFoundationContract` auto-covers the catalog↔enum alignment). **Confirm with Alex** whether to add a new code vs. reuse an existing one (OQ-2) — 3i-1 set the precedent of adding connector-specific codes after confirmation.

- [ ] **Task 6 — REST endpoint `GET /api/v1/projects/{projectId}/ticket-query`** (AC: #3)
  - [ ] Add to `adapters/rest/ProjectController.java` (mirror `getProject` path-var + `OperatorController.listOperatorRuns` query-param idiom): `@GetMapping("/{projectId}/ticket-query")`, `@Operation(operationId = "queryProjectTickets", ...)`, params `@RequestParam(required=false) String assignee`, `@RequestParam(required=false) List<String> components`, `@RequestParam(required=false) String state`, `@RequestParam(required=false, defaultValue="50") int limit`. Build a `TicketQuery`, delegate to `TicketQueryService`, map to a new response DTO, return the **direct array** (list endpoints return no envelope here).
  - [ ] New response DTO `adapters/rest/CandidateTicketResponse.java` (springdoc `@Schema` record + static `from(TicketSummary)`) — name it distinctly from `TicketSummary` to avoid the cross-package name shadow. Fields: `ticketRef`, `title`, nullable `summary`. Keep the controller thin (REST_CONTROLLERS_STAY_THIN ArchUnit rule) — all resolution in the service.
  - [ ] Regenerate the OpenAPI snapshot: `scripts/regen-openapi.sh` (or `.ps1`) → `-Dopenapi.snapshot.write=true` writes `deliveryline-backend/src/main/resources/openapi/openapi.json`; review + commit. Add the `queryProjectTickets` operationId assertion to the project block in `OpenApiSnapshotContractTest.java` (~lines 114-122).

- [ ] **Task 7 — CLI parity** (AC: #3)
  - [ ] New CLI command under `adapters/cli/` (e.g. `TicketQueryCommands` with `@CommandGroup(name="tickets", prefix="deliveryline tickets")`, `@Command(name="query")` → `deliveryline tickets query`). Mind the **Spring Shell 4.0.2 prefix quirk** (the registered path is `prefix + " " + name`; `@CommandGroup.name` is help-only). Inject the same `TicketQueryService` in-process (**CLI calls the app service directly, not REST**). Options mirror `OperatorCommands.status`: `--project (required)`, `--assignee`, `--components` (repeatable/CSV), `--state`, `--limit`, `--format text|json`, `--correlation-id`, `--verbose`. Reuse `WorkflowCommandOutputs`, `WorkflowCliExitStatusExceptionMapper`, correlation-id MDC scope.
  - [ ] Add/extend a CLI-registration IT (mirror `OperatorCliCommandRegistrationIT`) asserting `deliveryline tickets query` registers without colliding with `deliveryline submit`/`deliveryline operator …`.

- [ ] **Task 8 — Frontend intake/browse view** (AC: #5, #6)
  - [ ] **FIRST**: after the backend endpoint lands and `openapi.json` is regenerated, run `npm run generate-api` in `deliveryline-frontend/` to refresh `src/lib/api/schema.d.ts`; verify `npm run check:api` is green. Do this BEFORE writing any FE code that references the new types (the OpenAPI-regen→FE-client-drift cascade).
  - [ ] New route `src/routes/intake/index.tsx` (a new top-level segment, mirroring `routes/operator/queue.tsx`): `validateSearch` **explicitly parses+re-emits** every filter key (`projectId`, `assignee`, `components`, `state`) or TanStack strips it; `loaderDeps`+`loader` warm the query; `handleFiltersChange` re-navigates with the **full** search object spread. Add a nav link in `src/features/workflows/AppShell.tsx` (mirror `QueueHomeLink`/`ProjectsLink`).
  - [ ] New feature dir `src/features/intake/`: `IntakeBrowse.tsx` (list + filter sidebar), reuse `ProjectSelector` (project scope), an `OperatorFilterSidebar`-style `CheckboxGroup`/fieldset for `components` multi-select + a labelled text input for `assignee`. Per-row "start run" reuses `useSubmitWorkflow` (mints a per-attempt idempotency key, one independent submit per row). Query wiring: a new `queryOptions`/query fn calling `apiClient.GET('/api/v1/projects/{projectId}/ticket-query', ...)` via `unwrap`; a **404 → "not supported" hides the surface** (query `error`/empty gates the view — do NOT hardcode `kind === 'jira'` in the FE).
  - [ ] **Traps:** put all non-JSX helpers/view-models in sibling `.ts` files (react-refresh `only-export-components` + `--max-warnings=0`); guard wire `null` with `!= null` / `?? undefined` before building route params; use `useLiveAnnouncement` for the result-count announcement (`announcements.ts` vocabulary).

- [ ] **Task 9 — Logging instrumentation** (cross-cutting; required on every story)
  - [ ] SLF4J structured logs at: `TicketQueryService` entry/exit + capability-skip (WARN); each `JiraRealAdapter.queryTickets` external call (INFO start + outcome, WARN on classified failure); the REST controller entry/success; every `TicketSourceAdapterException` raise site.
  - [ ] Parameterized logging only (`log.info("...", arg1, arg2)`) — never concatenation.
  - [ ] Levels: `INFO` normal lifecycle (query received/resolved, result count), `WARN` recoverable anomalies (capability-off skip, classified connectivity failure, empty result), `ERROR` only unhandled failures. `DEBUG` for hot-path detail.
  - [ ] Context keys: `correlationId`, `projectPublicId`, plus **counts/lengths only** for the query (e.g. `componentCount`, `resultCount`, `limit`) via `MdcKeys.sanitizeForLog(...)`. **NEVER** log the raw JQL, filter values, ticket titles/summaries, or the JIRA token/Basic-auth header.
  - [ ] Pin the new log lines with a focused `OutputCaptureExtension`/list-appender test at the expected level per new branch (esp. the capability-skip WARN and the result-count INFO).

- [ ] **Task 10 — Tests + docs** (AC: #8)
  - [ ] `TicketSourceCapabilitiesTest` — add `supportsTicketQuery` assertions to the `jiraDefaults` (true), `linearDefaults` (false), and `noCreation` (false) cases. (No auto "count-the-flags" drift test exists — these manual assertions are the pin.)
  - [ ] JIRA `queryTickets` mock↔real parity in `JiraTicketSourceParityFoundationContract` (stub `/rest/api/3/search`; assert both mock and real return the same `TicketSummary` shape). `JiraRealAdapterUnitTest.querySearchesByJqlAndMapsResults` (mirror `pollSearchesByJqlAndMapsResults` ~lines 192-208; assert the **JQL string** — omitted fields absent, values escaped, `maxResults`). `JiraMockAdapterUnitTest` query case.
  - [ ] `TicketQueryService` unit test: supported → results; unsupported-capability / no-adapter → `TICKET_QUERY_NOT_SUPPORTED` (not a 500, not the submit path called).
  - [ ] REST: `ProjectController` slice/IT for `queryProjectTickets` (200 with array; 404 ProblemDetails for a `supportsTicketQuery=false` project). `OpenApiSnapshotContractTest` green with the new operationId. If a new `DomainErrorCode` is added, `ProblemDetailsCoverageFoundationContract` + placeholder stay green.
  - [ ] FE Vitest (`src/features/intake/__tests__/`): mock `@tanstack/react-router` per the cross-file convention; MSW-serve the ticket-query endpoint; assert filter controls drive query params, results render, 404 hides the surface, selection triggers the submit mutation; announcement asserted under `waitFor`; **axe-clean** via `expectNoA11yViolations`.
  - [ ] Keep `org.dradgo.application.integration.ticketsource` at its **≥0.80 line** jacoco floor (the new `TicketQueryService` lands here); the `adapters…jira` query code rides the 0.75 bundle floor.
  - [ ] Docs: add `ticket query` / `intake browse` vocabulary to `docs/glossary.md` (justify against NFR43 — minimize new concepts); append a JIRA-intake note to `docs/integrations/ticket-source-extension-contract.md`; note the new `supportsTicketQuery` capability in `docs/adr/0007-ticket-source-abstraction.md` (or the connector-resolution ADR).

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

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
