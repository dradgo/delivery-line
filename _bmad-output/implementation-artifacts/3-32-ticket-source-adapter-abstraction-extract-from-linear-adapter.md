# Story 3.32: TicketSourceAdapter Abstraction (Extract from LinearAdapter)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **Story type:** Backend architectural refactor (epic-3b). **No wire/REST/CLI surface change is in scope** — this extracts a vendor-neutral `TicketSourceAdapter` port from the existing Linear-shaped `LinearAdapter` (story 1.14) so a future ticket source (JIRA, GitHub Issues, GitLab Issues) is a one-interface, one-contract add. The existing Linear behavior must be **byte-for-byte preserved** (parity tests prove it).
>
> **⚠ READ THE RECONCILIATIONS FIRST.** The ACs below are copied verbatim from `epic-03-agent-execution.md §Story 3.32`. They describe an *idealized* port shape that **does not match the live code** in five load-bearing ways. The live contract is authoritative. Each ⚠ RECONCILE marker points at the live truth; the full reconciliation table is in Dev Notes §"The six reconciliations that prevent disasters". **Do not implement the AC text literally where a ⚠ marker contradicts it.**

## Story

As an architect preparing for future ticket-source extension (JIRA, GitHub Issues, GitLab Issues, Asana),
I want the existing `LinearAdapter` port (story 1.14) renamed/refactored to a vendor-neutral `TicketSourceAdapter` interface — with `LinearMockAdapter` + `LinearRealAdapter` becoming concrete implementations alongside future `JiraAdapter`/etc. — plus a documented extension contract,
so that adding a new ticket source in a future version requires implementing one interface against one documented contract, not refactoring a Linear-shaped port.

## Acceptance Criteria

> ACs verbatim from the epic. **⚠ RECONCILE** markers point at the live contract the AC text predates; the live contract wins — see Dev Notes.

1. **Given** the `application.integration.ticketsource` package, **Then** the generic `TicketSourceAdapter` interface exists with vendor-neutral domain-shaped methods: `fetchTicketByReference(TicketRef ref) → Optional<Ticket>`, `pollNewTickets(Instant since) → List<Ticket>`, `commentOnTicket(TicketRef ref, String body) → CommentResult`, `getCapabilities() → TicketSourceCapabilities` — all parameters and return types use vendor-neutral domain models defined in `domain.integration.ticketsource`. **⚠ RECONCILE:** the live port (`LinearAdapter`, `application/integration/linear/LinearAdapter.java`) has `fetchTicketByReference(String)`, `pollNewTickets(Instant)`, and **`void postGovernedRunComment(String ticketRef, GovernedRunComment summary)`** — there is **no** `commentOnTicket(TicketRef, String) → CommentResult`. The comment method carries an idempotency **fingerprint** + `DataClassification` + `runPublicId` (story 3.16 AC3), and an ArchUnit rule is pinned to its exact signature. **Keep the governed-comment shape** — do not flatten it to a raw `String body`. See Dev Notes §R1.
2. **Given** the existing `LinearAdapter` port from story 1.14, **Then** it is renamed/refactored such that `LinearAdapter` is no longer a separate port; the existing `LinearMockAdapter` + `LinearRealAdapter` (stories 1.14, 3.16) now implement `TicketSourceAdapter` directly. A migration note in `docs/adr/0007-ticket-source-abstraction.md` records the change. **(ADR number 0007 is free — existing ADRs are 0001–0004, 0019–0023.)**
3. **Given** capability detection (since not all ticket sources support all operations — e.g., some may not support comment posting via API), **Then** `TicketSourceCapabilities` exposes typed booleans: `supportsCommentOnTicket`, `supportsPolling`, `supportsTicketStateUpdates`, etc. Consuming services (story 3.16 Linear completion sync) check `capabilities.supportsCommentOnTicket` before invoking — if false, the operation gracefully degrades (e.g., logs a `linear.completionSyncSkipped` event with reason `ticket_source_does_not_support_comments`). **⚠ RECONCILE:** `WorkflowOrchestrationService.syncCompletionToLinear` (line ~1083–1090) resolves the adapter then posts unconditionally. Insert the capability gate **after** adapter resolution, **before** the post; return a new `SyncCompletionOutcome.SKIPPED_NO_COMMENT_CAPABILITY` and emit a **structured WARN log** (`event=linear.completionSyncSkipped reason=ticket_source_does_not_support_comments`) — prefer a log line over a new `WorkflowEventType` to avoid the registry/fixture fan-out (see Dev Notes §R6 + memory [[new-workfloweventtype-fixture-sites]]).
4. **Given** vendor-neutral domain types in `domain.integration.ticketsource`, **Then** `TicketRef`, `Ticket`, `CommentResult`, `TicketSourceCapabilities` are defined with no Linear-specific fields (no GraphQL IDs, no Linear team URLs); vendor-specific data lives only inside the implementing adapter and is mapped to the neutral domain types at the port boundary. **⚠ RECONCILE:** `LinearTicket` carries `status` (display name) **and `statusId` (Linear GraphQL workflow-state UUID)**. `statusId` is the **auto-ingest gating key** consumed by `LinearPollingHost` + `LinearAutoIngestProperties` (story 3a.5). A strictly-neutral `Ticket` cannot expose a GraphQL UUID. See Dev Notes §R3 — this is **OQ-1, the #1 decision**; do not silently drop `statusId` or you break 3a.5 auto-ingest.
5. **Given** Spring profile-based wiring, **Then** `application.yml` `deliveryline.integration.ticket-source.kind=linear` (default) selects the implementation; future kinds (`jira`, `github-issues`, `gitlab-issues`) plug in by adding a new implementation + a profile entry — no other code changes required. **⚠ RECONCILE:** selection today is via mutually-exclusive Spring **profiles** (`linear-mock` / `linear-real`), asserted in `LinearConfiguration.assertExclusiveLinearProfile`. Linear config lives under `deliveryline.linear.*`. **Add** the `kind` selector key (default `linear`); **keep** the profile mechanism + `deliveryline.linear.*` keys (renaming them is an ops-breaking change out of scope). See Dev Notes §R4 (OQ-2).
6. **Given** a documented extension contract `docs/integrations/ticket-source-extension-contract.md`, **Then** it specifies: every method's expected behavior, error classification per `IntegrationFailureCategory`, idempotency guarantees, redaction-on-egress requirements (any text sent to the source must pass through `RedactionPolicyService`), capability declaration, configuration-key conventions (`deliveryline.integration.ticket-source.{kind}.*`), and testing requirements (must implement parity test against the same fixture scenarios used for Linear). Mirror the structure of the existing `docs/integrations/linear-completion-sync.md`.
7. **Given** ArchUnit boundary, **Then** `TicketSourceAdapter` lives in `application.integration.ticketsource`; concrete implementations live in `adapters.integration.ticketsource.{kind}` (e.g., `adapters.integration.ticketsource.linear`); domain types live in `domain.integration.ticketsource`; ArchUnit asserts no vendor-specific types (Linear DTOs, JIRA REST types) leak through the port — verified by a per-vendor leak-detection test. **⚠ RECONCILE:** generalize the existing `LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT` + `ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT` rules (in `architecture/ArchitectureRuleCatalog.java`) to the new package/type/method names; ArchUnit runs in **Failsafe**, not Surefire (memory [[archunit-runs-in-failsafe-not-surefire]]).
8. **Given** existing consumers (story 3.16 `WorkflowOrchestrationService.syncCompletionToLinear`, CLI `deliveryline submit` from story 1.15, runner intake polling from story 1.14), **Then** they are refactored to depend on `TicketSourceAdapter` (not `LinearAdapter`) — names like `syncCompletionToLinear` may remain for now (renaming is a follow-up cosmetic story) but the type dependency is on the abstraction. **⚠ RECONCILE:** the live consumers are `IntegrationLinkService` (field inject), `WorkflowOrchestrationService` (`ObjectProvider<LinearAdapter>`), and `LinearPollingHost` (field inject). The CLI `submit` does **not** touch the adapter directly — it goes through `WorkflowCommandService` → `IntegrationLinkService`. See Dev Notes §"Source-tree map".
9. **Given** the foundation gate (story 1.23) widening, **Then** the gate now asserts: `TicketSourceAdapter` interface exists, current Linear implementations satisfy it, capability declaration is honored by consumers (a contract test injects a mock adapter declaring `supportsCommentOnTicket=false` and asserts the completion sync skips gracefully). Add a new `*FoundationContract` + a `@Nested` delegate in `FoundationGateVerificationTest` (mirror the `GitHubMockVsRealParityFoundationContract` pattern — Contract #11).
10. **Given** the test suite, **Then** tests cover: existing Linear scenarios pass against `TicketSourceAdapter` interface (refactor preserves behavior), capability-driven graceful degradation (commentOnTicket skipped when capability=false), config-driven implementation selection (switching `deliveryline.integration.ticket-source.kind` activates a different implementation), no vendor-specific type leakage at port boundary (ArchUnit assertion), extension-contract documentation completeness (all required sections present, link-checked).

## Tasks / Subtasks

> **Decision gate:** OQ-1 (statusId on neutral `Ticket`) and OQ-2 (kind-selector vs profile) materially change the shape below. The tasks encode the **recommended** resolutions (OQ-1 → keep an opaque, nullable `sourceStatus`/`sourceStatusId` documented as a vendor-opaque token; OQ-2 → add `kind` selector, keep profiles). If Alex chooses differently, adjust Tasks 1, 3, 6 accordingly. **Confirm OQ-1/OQ-2 before mass file moves.**

- [x] **Task 1 — Vendor-neutral domain types in `domain.integration.ticketsource` (AC1, AC4)**
  - [x] Create package `org.dradgo.domain.integration.ticketsource`. (New package tree; `domain.integration` does not exist yet. Records only — domain must stay framework-free per ArchUnit `domain_must_be_framework_free`.)
  - [x] `TicketRef` — thin value record wrapping the external reference string (e.g. `record TicketRef(String value)`), validated non-blank in the compact constructor, with a `static TicketRef of(String)` factory. This replaces the bare `String ticketRef` threaded through every signature.
  - [x] `Ticket` — neutral projection: `ticketRef` (TicketRef), `title`, `summary`, `authorIdentity`, `createdAt`, `updatedAt`, `labels` (immutable `Map<String,String>`), and (per OQ-1 recommended) a nullable **opaque** `sourceStatus` (display name, logs only) + nullable opaque `sourceStatusId` (vendor status token, **documented as not interpreted by neutral consumers** — only the implementing adapter/polling host reads it). Preserve `LinearTicket`'s nullable + immutable-labels semantics exactly.
  - [x] `CommentResult` — replaces the current `void` return on the comment method. Carry an outcome enum (`POSTED` / `SKIPPED_DUPLICATE`) so callers can observe idempotency-replay (satisfies AC1's `CommentResult` while preserving the fingerprint contract). Keep `GovernedRunComment` as the comment payload type (move it to this package, vendor-neutral already).
  - [x] `TicketSourceCapabilities` — record of typed booleans: `supportsCommentOnTicket`, `supportsPolling`, `supportsTicketStateUpdates` (extend as the contract doc enumerates). Provide a `linearDefaults()`-style factory for the Linear capability set (Linear supports all three today).
  - [x] `TicketSourceAdapterException` — rename of `LinearAdapterException`; keep `IntegrationFailureCategory failureCategory()` accessor + both constructors. Move to this package (or `application.integration.ticketsource` — keep co-located with the port; see ArchUnit note in Task 5).
- [x] **Task 2 — `TicketSourceAdapter` port in `application.integration.ticketsource` (AC1)**
  - [x] Create the interface from `LinearAdapter`, renamed, in `org.dradgo.application.integration.ticketsource`. Methods: `Optional<Ticket> fetchTicketByReference(TicketRef ref)`, `List<Ticket> pollNewTickets(Instant since)`, `CommentResult postGovernedRunComment(TicketRef ref, GovernedRunComment summary)`, `TicketSourceCapabilities getCapabilities()`. Port the Javadoc (not-found = empty, fingerprint idempotency, redaction-already-applied, "only IntegrationLinkService + polling host may call directly").
  - [x] **Decision flagged (OQ-3):** AC1 literally wants a `commentOnTicket(TicketRef, String body) → CommentResult`. Recommended: keep `postGovernedRunComment(TicketRef, GovernedRunComment) → CommentResult` and treat AC1 as satisfied-in-spirit (the governed payload is the richer, contract-bearing form; a raw-string comment would lose the fingerprint + classification the system depends on). Do **not** add a second raw-string method unless Alex requires literal AC compliance.
- [x] **Task 3 — Concrete implementations move to `adapters.integration.ticketsource.linear` (AC2, AC5)**
  - [x] Move `LinearMockAdapter`, `LinearRealAdapter`, `LinearMockScenario`, `LinearMockScenarioRegistry`, `LinearTicketFixtureDocument` from `org.dradgo.adapters.integration.linear` → `org.dradgo.adapters.integration.ticketsource.linear`. Update package declarations + all imports (main + test).
  - [x] Both adapters now `implements TicketSourceAdapter`. Map Linear GraphQL responses → neutral `Ticket` at the boundary (the existing `toLinearTicket` JsonNode parsing in `LinearRealAdapter` becomes `toTicket`, populating opaque `sourceStatusId` from `issue.state.id`). Keep `@Profile("linear-mock")` / `@Profile("linear-real")`.
  - [x] Implement `getCapabilities()` returning the Linear capability set (`supportsCommentOnTicket=true`, `supportsPolling=true`, `supportsTicketStateUpdates=true`).
  - [x] `postGovernedRunComment` returns `CommentResult.POSTED` / `CommentResult.SKIPPED_DUPLICATE` (mock + real both surface the idempotency-replay no-op as `SKIPPED_DUPLICATE` instead of silent void).
  - [x] Keep `LinearProperties`, `LinearAutoIngestProperties`, `LinearConfiguration`, `LinearPollingHost`, `LinearCompletionSyncConfiguration` where they are (Linear-impl-specific; profile-gated). Update their type references (`LinearAdapter` → `TicketSourceAdapter`) and any `ticket.statusId()` → `ticket.sourceStatusId()` reads in `LinearPollingHost` auto-ingest gating.
- [x] **Task 4 — Refactor consumers to the abstraction (AC8)**
  - [x] `IntegrationLinkService` — change field type `LinearAdapter linearAdapter` → `TicketSourceAdapter ticketSourceAdapter` (rename optional; type dep is mandatory). Update `linkTicket` to pass `TicketRef.of(externalRef)` and consume neutral `Ticket`. Keep method names (`linkTicket`, `findActiveLinearTicketLink`) — cosmetic rename is a follow-up (AC8).
  - [x] `WorkflowOrchestrationService` — change `ObjectProvider<LinearAdapter>` → `ObjectProvider<TicketSourceAdapter>`. Update `syncCompletionToLinear` adapter call to pass `TicketRef` + consume `CommentResult` (log `SKIPPED_DUPLICATE` at INFO as an idempotent replay). Method name `syncCompletionToLinear` stays.
  - [x] `LinearPollingHost` — change `LinearAdapter linearAdapter` → `TicketSourceAdapter`; `pollNewTickets` now returns `List<Ticket>`; auto-ingest gating reads `ticket.sourceStatusId()`.
  - [x] CLI `WorkflowCommands.syncCompletion` — no type change (delegates to `syncCompletionToLinear`); verify it still compiles.
- [x] **Task 5 — ArchUnit rule generalization (AC7)**
  - [x] In `architecture/ArchitectureRuleCatalog.java`: rename/generalize `LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT` → assert `org.dradgo.application.integration.ticketsource..` does not depend on Linear SDK / `com.linear..` / `linear.api..` / `org.springframework.web.client..` / `org.springframework.http.client..`. Keep `GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT` untouched (3.33 owns the GitHub equivalent).
  - [x] Update `ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT` to target `TicketSourceAdapter.postGovernedRunComment(TicketRef.class, GovernedRunComment.class)` (the method-signature args change from `String` to `TicketRef`).
  - [x] Add rules: `TicketSourceAdapter` resides in `application.integration.ticketsource`; concrete `*Adapter` impls reside in `adapters.integration.ticketsource..`; neutral domain types reside in `domain.integration.ticketsource`. Update the layered/slice rules if they enumerate `adapters.integration.linear` explicitly (`adapter_slices_must_not_depend_on_each_other`).
  - [x] Register each new `@ArchTest` in `ArchitectureBoundaryTest`. **Verify via Failsafe** (`failsafe:integration-test -Dit.test='**/architecture/**/*Test'`), not `mvnw test` — ArchUnit is excluded from Surefire (memory [[archunit-runs-in-failsafe-not-surefire]]).
- [x] **Task 6 — Capability-driven completion-sync degradation (AC3)**
  - [x] In `WorkflowOrchestrationService.syncCompletionToLinear`, after `adapter = ...provider.getIfAvailable()` and the null-check (line ~1090), before the post: `if (!adapter.getCapabilities().supportsCommentOnTicket()) { log.warn("event=linear.completionSyncSkipped reason=ticket_source_does_not_support_comments workflowRunId={} ...", ...); return SyncCompletionOutcome.SKIPPED_NO_COMMENT_CAPABILITY; }`.
  - [x] Add `SKIPPED_NO_COMMENT_CAPABILITY` to the `SyncCompletionOutcome` enum. Update CLI `WorkflowCommands.syncCompletion` outcome→message mapping to handle the new value.
  - [x] **Do NOT add a new `WorkflowEventType`** for the skip (memory [[new-workfloweventtype-fixture-sites]] — it fans out to RegistryContractTest + two fixture files). A structured WARN log line is the audit record, consistent with the other `SKIPPED_*` outcomes which emit no event. (If Alex requires an auditable event, that is OQ-4.)
  - [x] **Add `@ConfigurationProperties` test-yaml coverage** if any new validated config is introduced (memory [[validated-config-needs-test-yaml]] — `src/test/resources/application.yml` shadows, not merges).
- [x] **Task 7 — Config selector key (AC5)**
  - [x] Add `deliveryline.integration.ticket-source.kind` (default `linear`) — a new `@ConfigurationProperties("deliveryline.integration.ticket-source")` record (`TicketSourceProperties`) with a `kind` field. Keep `deliveryline.linear.*` as the linear-kind config block.
  - [x] **Recommended (OQ-2):** validate the `kind` value is consistent with the active Linear profile in `LinearConfiguration` (or a new `TicketSourceConfiguration`) — fail-fast at boot if `kind=jira` but only the Linear impl is on the classpath. Do not rip out the `@Profile` mechanism (it is load-bearing: bean gating + `assertExclusiveLinearProfile`).
  - [x] Document the key in `application.yml` (commented) + the extension-contract doc. Add to test yaml if validated (memory [[validated-config-needs-test-yaml]]).
- [x] **Task 8 — ADR + extension-contract doc (AC2, AC6)**
  - [x] `docs/adr/0007-ticket-source-abstraction.md` — follow the `0004-spec-stage-orchestration.md` format (Status/Driver/Context/Decision/Alternatives Considered/Consequences/References). Record: the rename, the OQ-1 `sourceStatusId` opaque-token decision, the OQ-2 kind-selector-keeps-profiles decision, and the OQ-3 keep-governed-comment-shape decision.
  - [x] `docs/integrations/ticket-source-extension-contract.md` — mirror `linear-completion-sync.md` headings. Sections: intro, per-method expected behavior, error classification per `IntegrationFailureCategory`, idempotency guarantees (fingerprint), redaction-on-egress (`RedactionPolicyService`), capability declaration, config-key conventions (`deliveryline.integration.ticket-source.{kind}.*`), testing requirements (parity test against the Linear fixture scenarios). Internal doc links must resolve (story 1.22 AC8 link-check CI).
- [x] **Task 9 — Tests: refactor + new coverage (AC10)**
  - [x] Update all existing Linear tests for the new package/type names: `LinearMockAdapterUnitTest`, `LinearRealAdapterUnitTest`, `LinearScenarioContractTest` (→ the parity contract), `IntegrationProfileWiringContractTest`, `IntegrationLinkServiceUnitTest`, `WorkflowOrchestrationServiceTest`, `LinearPollingHostTest`, `CompletionSyncOrchestrationIT`, `WorkflowCommandsSyncCompletionTest`. Behavior assertions unchanged — only types/packages/imports move (proves AC10 "refactor preserves behavior").
  - [x] New test: capability-driven skip — inject a `TicketSourceAdapter` mock with `supportsCommentOnTicket=false`; assert `syncCompletionToLinear` returns `SKIPPED_NO_COMMENT_CAPABILITY`, posts nothing, and logs `linear.completionSyncSkipped` (use `OutputCaptureExtension`/list-appender).
  - [x] New test: config-driven selection — assert `kind=linear` (+ profile) activates the Linear impl; a fail-fast assertion when `kind` mismatches the available impl.
  - [x] Verify `*IT`/`*ContractTest` naming routes to Failsafe (memory [[springboot-testcontainers-test-must-be-IT]], [[docker-it-needs-exact-docker-runner-it-tag]]).
- [x] **Task 10 — Foundation-gate widening (AC9)**
  - [x] New `org.dradgo.foundation.TicketSourceAbstractionFoundationContract` (suffix `FoundationContract`, **not** `*Test` — Launcher-API-discovered only, `@Tag("foundation-gate")`). Mirror `GitHubMockVsRealParityFoundationContract`: assert (a) `TicketSourceAdapter.class.isAssignableFrom(LinearMockAdapter.class)` + `LinearRealAdapter.class`; (b) happy-path read returns a neutral `Ticket` in both mock + (MockRestServiceServer-stubbed) real; (c) a classified failure surfaces the same `IntegrationFailureCategory` in both; (d) a mock declaring `supportsCommentOnTicket=false` makes `syncCompletionToLinear` skip gracefully.
  - [x] Add a `@Nested Contract13TicketSourceAbstraction` to `FoundationGateVerificationTest` delegating via `FoundationGateAssertions.delegateRunAssertGreen("3.32", "org.dradgo.foundation.TicketSourceAbstractionFoundationContract")`. Failure messages must start with `[story 3.32]`.
  - [x] Run `mvn verify -Pfoundation-gate` to confirm (memory [[verify-ci-fixes-in-clean-env]] — reproduce in a clean env / Linux before claiming green).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback, **capability-skip**), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `ticketRef`, sanitized). Use MDC where the framework supports it (the completion-sync path already opens `MdcKeys` scopes); otherwise pass as parameters.
  - [x] Never log secrets, payload bytes, raw tokens, or full PII. The Linear API token, comment body bytes, and ticket free-text must stay out of logs — reuse `MdcKeys.sanitizeForLog(ticketRef)` as the existing code does.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use a list-appender or `OutputCaptureExtension`) — specifically the new `linear.completionSyncSkipped` WARN.

## Dev Notes

This is a **backend-only** structural refactor. The entire surface is `deliveryline-backend/` + `docs/`. **No `openapi.json` / REST DTO / frontend / wire-enum change is in scope** — the abstraction is purely internal to the application/adapters boundary. The success bar is: *every existing Linear behavior is preserved byte-for-byte (parity tests prove it) and a future ticket source is a one-interface add.*

### The six reconciliations that prevent disasters

1. **R1 — The comment method is governed, not a raw string (AC1).** Live: `void postGovernedRunComment(String ticketRef, GovernedRunComment summary)`. `GovernedRunComment` carries `runPublicId`, a SHA-256 **fingerprint** (idempotency, story 3.16 AC3), the pre-redacted `body`, and a `DataClassification`. The real adapter embeds the fingerprint as an HTML-comment marker and scans existing comments to no-op a re-post. **Do not** collapse this to `commentOnTicket(TicketRef, String body)` — you would destroy the idempotency + classification contract and break `LinearScenarioContractTest` + the ArchUnit method-signature rule. Keep the governed shape; change only the param type (`String`→`TicketRef`) and return type (`void`→`CommentResult`). [Source: `application/integration/linear/LinearAdapter.java#48-54`, `GovernedRunComment.java`]
2. **R2 — `TicketRef` is net-new; everything is `String` today.** The port, `LinearTicket.ticketRef`, `IntegrationLink.externalRef`, the CLI `--ticket` option, and the polling watermark all use the bare `String` (e.g. `"LIN-123"`). Introduce a thin `TicketRef` value record and thread it through the port + the two direct callers (`IntegrationLinkService`, `LinearPollingHost`) + the completion-sync composer. **Persistence (`integration_links.external_ref`) stays a `String`** — map `TicketRef.value()` at the persistence boundary; do not migrate the DB column.
3. **R3 — `statusId` is Linear-specific BUT load-bearing for auto-ingest (AC4) — OQ-1, the #1 decision.** `LinearTicket.statusId` is the Linear GraphQL workflow-state UUID. `LinearPollingHost` + `LinearAutoIngestProperties.statusIds` gate auto-ingest on it (story 3a.5). AC4 forbids "GraphQL IDs" on the neutral `Ticket`. A naive strip breaks 3a.5. **Recommended resolution:** the neutral `Ticket` carries a nullable **opaque** `sourceStatusId` (+ `sourceStatus` display name) explicitly documented as a *vendor-opaque status token not interpreted by neutral consumers* — only the Linear impl produces it and only the Linear-specific `LinearPollingHost` reads it. This is a defensible reading of "no GraphQL IDs" (an opaque string token ≠ a typed GraphQL DTO) and keeps the refactor mechanical. The strict alternative — modelling a neutral `TicketStatus` and moving auto-ingest gating fully inside the Linear adapter — is larger and risks behavior drift. **Confirm with Alex before mass moves (OQ-1).** [Source: `LinearTicket.java#20-36`, `LinearAutoIngestProperties`, `LinearPollingHost`]
4. **R4 — Selection is profile-based today, not `kind`-based (AC5) — OQ-2.** `linear-mock` / `linear-real` profiles are mutually exclusive (`LinearConfiguration.assertExclusiveLinearProfile`), beans are `@Profile`-gated, and `linearRestClient` only exists under `linear-real`. AC5 wants a `deliveryline.integration.ticket-source.kind` selector. **Recommended:** add the `kind` key as the *documented* selector and *validate it against the active profile* (fail-fast on mismatch) — **keep** the profile mechanism and the existing `deliveryline.linear.*` keys. Renaming `deliveryline.linear.*` → `deliveryline.integration.ticket-source.linear.*` is an ops-breaking `.env`/deploy change and is **out of scope** (note it as a future cosmetic migration in the ADR). [Source: `infrastructure/config/LinearConfiguration.java`, `application/integration/linear/LinearProperties.java`, `application.yml`]
5. **R5 — ADR number 0007 is correct and free.** Existing ADRs: 0001–0004, then a gap, then 0019–0023. AC2's prescribed `0007` does not collide. Follow the `0004-spec-stage-orchestration.md` format. The parallel story 3.33 (RepositoryHostAdapter) claims `0008`.
6. **R6 — Prefer a log line over a new `WorkflowEventType` for the skip (AC3).** AC3's `linear.completionSyncSkipped` "event" — the other `SyncCompletionOutcome.SKIPPED_*` branches emit **no** `WorkflowEventType`, only a WARN log + the returned enum. Adding a real event type fans out to `RegistryContractTest` + `workflow-event-types.fixture.json` + the fixture-stream enum (memory [[new-workfloweventtype-fixture-sites]]). **Recommended:** emit a structured WARN log (`event=linear.completionSyncSkipped reason=ticket_source_does_not_support_comments`) and return the new `SKIPPED_NO_COMMENT_CAPABILITY` enum — no new event type. If Alex wants an auditable workflow event, that is OQ-4 and accept the fan-out. [Source: `WorkflowOrchestrationService.java#1037-1115`, `SyncCompletionOutcome` enum]

### Source-tree map (exact paths — what moves where)

**Port + neutral types (today → after):**
- `application/integration/linear/LinearAdapter.java` → `application/integration/ticketsource/TicketSourceAdapter.java` (renamed interface).
- `application/integration/linear/LinearTicket.java` → `domain/integration/ticketsource/Ticket.java` (neutral; opaque `sourceStatusId`).
- `application/integration/linear/GovernedRunComment.java` → `domain/integration/ticketsource/GovernedRunComment.java` (already neutral).
- `application/integration/linear/LinearAdapterException.java` → `TicketSourceAdapterException.java` (co-locate with port or in domain pkg).
- **New:** `domain/integration/ticketsource/{TicketRef, CommentResult, TicketSourceCapabilities}.java`.

**Implementations (today → after):**
- `adapters/integration/linear/{LinearMockAdapter, LinearRealAdapter, LinearMockScenario, LinearMockScenarioRegistry, LinearTicketFixtureDocument}.java` → `adapters/integration/ticketsource/linear/` (same files, new package, now `implements TicketSourceAdapter`).
- GraphQL query resources `graphql/linear/*.graphql` — keep (Linear-impl-specific).

**Stays in place (Linear-impl-specific, profile-gated — update type refs only):**
- `application/integration/linear/{LinearProperties, LinearAutoIngestProperties}.java` (config records — Linear's `{kind}` block).
- `infrastructure/config/{LinearConfiguration, LinearPollingHost, LinearCompletionSyncConfiguration}.java`.

**Consumers (type dep `LinearAdapter` → `TicketSourceAdapter`; method names stay):**
- `application/integration/IntegrationLinkService.java` (field inject — `fetchTicketByReference`).
- `application/workflow/WorkflowOrchestrationService.java` (`ObjectProvider<…>` — `postGovernedRunComment` in `syncCompletionToLinear`; add capability gate + `SKIPPED_NO_COMMENT_CAPABILITY`).
- `infrastructure/config/LinearPollingHost.java` (field inject — `pollNewTickets`; reads `sourceStatusId` for auto-ingest).
- `adapters/cli/WorkflowCommands.java` (`sync-completion` — delegates, no direct adapter dep; verify outcome-mapping handles the new enum).

**Architecture + foundation:**
- `architecture/ArchitectureRuleCatalog.java` (generalize `LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT` + `ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT`; add ticketsource package rules) + `architecture/ArchitectureBoundaryTest.java` (register `@ArchTest`s).
- `foundation/TicketSourceAbstractionFoundationContract.java` (new) + `foundation/FoundationGateVerificationTest.java` (new `@Nested`) + `foundation/FoundationGateAssertions.java` (reuse `delegateRunAssertGreen`).
- Pattern to mirror: `foundation/GitHubMockVsRealParityFoundationContract.java`.

**Docs:**
- `docs/adr/0007-ticket-source-abstraction.md` (new — format per `docs/adr/0004-spec-stage-orchestration.md`).
- `docs/integrations/ticket-source-extension-contract.md` (new — structure per `docs/integrations/linear-completion-sync.md`).

### Architecture & test conventions (must obey)

- **Layered ArchUnit:** `domain ← application ← adapters`; application **cannot** import `org.dradgo.adapters..` (memory [[application-cannot-import-adapters]]). Neutral domain types in `domain.integration.ticketsource` must be framework-free (records only).
- **ArchUnit runs in Failsafe, not Surefire** — a new `@ArchTest` reports 0 under `mvnw test`; verify via `failsafe:integration-test -Dit.test='**/architecture/**/*Test'` (memory [[archunit-runs-in-failsafe-not-surefire]]).
- **Test naming:** Surefire runs `*Test` (excludes `*ContractTest`, `*IT`, `architecture/**`, `*FoundationContract` is name-mismatched on purpose); Failsafe runs `*IT`/`*ContractTest`/`architecture/**`. `@SpringBootTest`+Testcontainers tests must be `*IT` (memory [[springboot-testcontainers-test-must-be-IT]]).
- **Foundation gate:** `mvn verify -Pfoundation-gate`; the gate skips JaCoCo-check (`jacoco.check.skip=true`). `*FoundationContract` classes are discovered only via the Launcher API from `FoundationGateVerificationTest` — they must NOT match `*Test`. Failure messages start with `[story 3.32]` (story 1.23 AC1).
- **JaCoCo:** the gate is **BUNDLE-level only** (LINE ≥ 0.75, BRANCH ≥ 0.55 in `pom.xml`) — there are no per-package rules today. The epic's "extend thresholds to `application.integration.ticketsource` at 80%/90%" (epic §coverage AC, story 2.27 AC10 / story 3.35) is **owned by the coverage/test-extension story, not 3.32** — do not add per-package `<rule>` blocks here; just keep the bundle gate green.
- **`@ConfigurationProperties` test yaml:** any new validated property requires `src/test/resources/application.yml` to set it (it shadows, not merges — memory [[validated-config-needs-test-yaml]]).
- **Profile-gated bean depended on by an unconditional `@Service`:** `IntegrationLinkService` (unconditional) injects the adapter directly — the adapter interface is unconditional but impls are profile-gated; the `test` profile activates `linear-mock`. Don't break this (memory [[unconditional-service-needs-profile-gate]]).
- **Build pitfalls:** the Bash tool is RTK-corrupted — use native file tools + PowerShell (memory [[rtk-hook-only-matches-bash]]). If you touch any `runner-contracts` schema (you should not), `.m2` staleness bites (memory [[runner-contracts-schema-stale-in-m2]]). Reproduce CI green in a clean env before claiming done (memory [[verify-ci-fixes-in-clean-env]]).
- **Commits:** omit the Co-Authored-By Claude trailer (memory [[commit-no-claude-coauthor]]).

### Logging Requirements (project-wide standard)

Every story leaves touched services observable enough to debug a production incident without re-deploying (enforced via the "Logging instrumentation" task).

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **New surface this story:** the capability-skip branch in `syncCompletionToLinear` → `WARN` `event=linear.completionSyncSkipped reason=ticket_source_does_not_support_comments` with `workflowRunId` + sanitized `ticketRef`. The `CommentResult.SKIPPED_DUPLICATE` replay → `INFO` (idempotent no-op, not an error).
- **Required context keys** (MDC where available — the completion-sync path already opens `MdcKeys` scopes): `correlationId`, `workflowRunId`, sanitized `ticketRef`.
- **Forbidden in output:** the Linear API token, comment body bytes, ticket free-text, raw PII. Use `MdcKeys.sanitizeForLog(...)`.
- **Test contract:** pin the new `linear.completionSyncSkipped` WARN with a focused list-appender/`OutputCaptureExtension` assertion.

### Project Structure Notes

- New package trees `org.dradgo.domain.integration.ticketsource` and `org.dradgo.application.integration.ticketsource` + `org.dradgo.adapters.integration.ticketsource.linear`. The package-rename is the highest-churn part — let the IDE/`git mv` + import-rewrite drive it, then fix ArchUnit + tests.
- This refactor is a **prerequisite-sibling** of story 3.33 (RepositoryHostAdapter from GitHubAdapter) — same shape, GitHub side. Keep the two abstractions symmetric (capabilities record, leak rule, parity foundation contract, extension-contract doc, ADR 0008) so a future reader sees one pattern, not two.

### References

- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.32`] — ACs 1–10 (and §3.33 for the symmetric GitHub sibling).
- [Source: `docs/adr/0004-spec-stage-orchestration.md`] — ADR format to follow for 0007.
- [Source: `docs/integrations/linear-completion-sync.md`] — extension-contract doc structure to mirror; also the canonical description of the completion-sync flow this story must not regress.
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearAdapter.java`] — the live port (3 methods, governed-comment contract).
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearTicket.java#20-36`] — `status`/`statusId` Linear-specific fields (R3/OQ-1).
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java#1037-1137`] — `syncCompletionToLinear` + `SyncCompletionOutcome` (capability-gate insertion point, AC3).
- [Source: `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java#460-619`] — `ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT` + `LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT` (AC7 rules to generalize).
- [Source: `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java` + `GitHubMockVsRealParityFoundationContract.java`] — foundation-contract + parity-test pattern (AC9).
- [Source: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/IntegrationFailureCategory.java`] — failure-classification enum the port's exceptions carry.
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionPolicyService.java`] — redaction-on-egress (AC6 contract requirement).
- [Source: `deliveryline-backend/pom.xml#395-494`] — JaCoCo BUNDLE gate (no per-package rules; threshold extension owned elsewhere).

## Open Questions / Decisions for Alex

1. **OQ-1 (BLOCKING — confirm before mass file moves) — `statusId` on the neutral `Ticket` (AC4 vs story 3a.5).** `LinearTicket.statusId` (Linear GraphQL workflow-state UUID) is the auto-ingest gating key. AC4 forbids "GraphQL IDs" on the neutral type. **Recommended:** keep it as a nullable **opaque** `sourceStatusId` documented as a vendor-opaque token (only the Linear impl + `LinearPollingHost` read it). Alternative: model a neutral `TicketStatus` and move auto-ingest gating fully inside the Linear adapter (larger, behavior-drift risk). **Decision needed.**
2. **OQ-2 — selection mechanism (AC5).** Add a `deliveryline.integration.ticket-source.kind` selector while **keeping** Spring profiles + `deliveryline.linear.*` config keys (recommended, minimal churn), or fully migrate selection to the `kind` property + rename config keys (ops-breaking, larger)? **Recommended: keep profiles, add `kind` as a validated selector.**
3. **OQ-3 — comment method shape (AC1).** Keep `postGovernedRunComment(TicketRef, GovernedRunComment) → CommentResult` (recommended — preserves the fingerprint + classification contract), or also add a literal `commentOnTicket(TicketRef, String body)` to match AC1 verbatim (risks a second, weaker path)? **Recommended: keep the governed method only.**
4. **OQ-4 — skip audit (AC3).** Is a structured WARN log sufficient for the `supportsCommentOnTicket=false` skip (recommended — consistent with other `SKIPPED_*` outcomes, avoids the `WorkflowEventType` registry/fixture fan-out), or is an auditable workflow event required? **Recommended: log-only.**

## Open-Question Resolutions (Alex, 2026-06-17)

All four OQs were confirmed to the **recommended** option before any mass file moves:

- **OQ-1 (statusId):** opaque nullable `sourceStatusId` (+ `sourceStatus` display name) on the neutral `Ticket`; only the Linear impl + `LinearPollingHost` interpret it.
- **OQ-2 (kind selector):** add `deliveryline.integration.ticket-source.kind` (default `linear`), validated against the active profile; **keep** the `linear-mock`/`linear-real` profiles + `deliveryline.linear.*` keys.
- **OQ-3 (comment shape):** keep `postGovernedRunComment(TicketRef, GovernedRunComment) → CommentResult` only; no raw-string method.
- **OQ-4 (skip audit):** structured WARN log (`event=linear.completionSyncSkipped`) + new `SyncCompletionOutcome.SKIPPED_NO_COMMENT_CAPABILITY`; no new `WorkflowEventType`.

## Dev Agent Record

### Context Reconciliation

- **Foundation contract number:** the story Task 10 said `Contract13TicketSourceAbstraction`, but Contract #13 is already taken by story 3.19 (Runner-queue inspection). Implemented as **Contract #14** (`Contract14TicketSourceAbstraction`) — the next free number.
- **`SubmitBatchCommand`** matched the type grep only via a `linearTicketReferences` field comment (bare `String`); it is not coupled to the moved types and needed no change.
- **`Ticket.ticketRef()` returns `TicketRef`** (R2): threaded `.value()` at every persistence/log/String boundary (`IntegrationLinkService`, `LinearPollingHost`, mock `PostedComment` keeps `String`).
- **Capability gate placement:** inserted in `syncCompletionToLinear` after adapter resolution + null-check, before the post; returns `SKIPPED_NO_COMMENT_CAPABILITY`. Mocked adapters in `WorkflowOrchestrationServiceTest` now stub `getCapabilities()` (else the gate NPEs).
- **ArchUnit:** generalized `LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT` → `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` (now scoped to `application.integration.ticketsource..`), retargeted `ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT` to `(TicketRef, GovernedRunComment)`, added port/impl residence rules; `GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT` left untouched (3.33 owns it).

### Completion Notes

- Extracted vendor-neutral `TicketSourceAdapter` port (`application.integration.ticketsource`) + neutral domain types (`domain.integration.ticketsource`: `TicketRef`, `Ticket`, `CommentResult`, `TicketSourceCapabilities`, `GovernedRunComment`) + `TicketSourceAdapterException` from the Linear-shaped `LinearAdapter` (story 1.14). `LinearMockAdapter`/`LinearRealAdapter` (+ scenario/registry/fixture) moved to `adapters.integration.ticketsource.linear` and implement the neutral port; Linear behavior is preserved byte-for-byte (parity tests + foundation Contract #14 prove it).
- Comment method keeps the governed shape; `void` → `CommentResult` (mock + real surface the idempotency replay as `SKIPPED_DUPLICATE`).
- Capability-driven completion-sync degradation: `SKIPPED_NO_COMMENT_CAPABILITY` + `event=linear.completionSyncSkipped` WARN (no new `WorkflowEventType`); CLI outcome mapping handles the new value.
- New `deliveryline.integration.ticket-source.kind` selector (`TicketSourceProperties`, default `linear`) with boot-time fail-fast for an unsupported kind; profiles + `deliveryline.linear.*` keys unchanged.
- ADR `docs/adr/0007-ticket-source-abstraction.md` + extension contract `docs/integrations/ticket-source-extension-contract.md` authored.
- ✅ Resolved review finding [Patch]: blank `ticketRef` up-front non-blank guard. Alex confirmed Option A (reject as a validation error via `IllegalArgumentException` at method entry, before `checkAndReserve`/port lookup — mirrors `linkGitHubPr`). Applied to both `linkTicket` + `linkTicketWithinTransaction`; +2 RED→GREEN unit tests; full Surefire 1034 passed / 0 failures, Spotless clean.

### Verification

- `mvnw test` (full Surefire unit tier): **1032 passed, 0 failures, 12 skipped**.
- ArchUnit `ArchitectureBoundaryTest` (Failsafe): **54 rules green** (incl. generalized leak rule + 2 new ticket-source residence rules).
- Non-Docker contract tests (Failsafe): `IntegrationProfileWiringContractTest`, `LinearScenarioContractTest`, `IntegrationLoggingContractTest`, `LoggingForbiddenPayloadContractTest` — green.
- `mvnw verify -Pfoundation-gate` (Docker up): **all 20 contracts green, incl. Contract #14** (TicketSource abstraction parity + capability skip, `[story 3.32]`).
- `CompletionSyncOrchestrationIT` (Testcontainers): green (post-commit completion sync still records the governed comment through the capability gate).
- Spotless applied; backend main + test sources compile clean from a `clean` build. No `openapi.json` / wire / frontend change.

### File List

**New (main):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketRef.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/Ticket.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/CommentResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketSourceCapabilities.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/GovernedRunComment.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapterException.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceProperties.java`

**Moved (main, `adapters.integration.linear` → `adapters.integration.ticketsource.linear`, retypes to the neutral port):**
- `.../adapters/integration/ticketsource/linear/LinearMockAdapter.java`
- `.../adapters/integration/ticketsource/linear/LinearRealAdapter.java`
- `.../adapters/integration/ticketsource/linear/LinearMockScenario.java`
- `.../adapters/integration/ticketsource/linear/LinearMockScenarioRegistry.java`
- `.../adapters/integration/ticketsource/linear/LinearTicketFixtureDocument.java`

**Deleted (main, rewritten under the new packages):**
- `.../application/integration/linear/LinearAdapter.java`
- `.../application/integration/linear/LinearTicket.java`
- `.../application/integration/linear/GovernedRunComment.java`
- `.../application/integration/linear/LinearAdapterException.java`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearPollingHost.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearConfiguration.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java`
- `deliveryline-backend/src/main/resources/application.yml`

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/foundation/TicketSourceAbstractionFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/config/TicketSourceConfigurationTest.java`

**Moved (test, → `adapters.integration.ticketsource.linear`):**
- `.../adapters/integration/ticketsource/linear/LinearMockAdapterUnitTest.java`
- `.../adapters/integration/ticketsource/linear/LinearRealAdapterUnitTest.java`
- `.../adapters/integration/ticketsource/linear/LinearScenarioContractTest.java`
- `.../adapters/integration/ticketsource/linear/IntegrationProfileWiringContractTest.java`

**Modified (test):**
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLinkServiceUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowOrchestrationServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/CompletionSyncOrchestrationIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/config/LinearPollingHostTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/observability/LoggingForbiddenPayloadContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/IntegrationLinkGitHubPrFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java`

**New (docs):**
- `docs/adr/0007-ticket-source-abstraction.md`
- `docs/integrations/ticket-source-extension-contract.md`

### Review Findings (bmad-code-review 2026-06-17)

Adversarial review over the working-tree diff (41 files: 12 new + 5 renamed + 4 deleted + 20 modified, incl. untracked port/domain/foundation/docs). Three layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor. Acceptance Auditor confirmed **all 10 ACs MET** (AC10 test-execution not independently re-run) and all four OQ resolutions honored. Triage: **0 decision-needed, 4 patch, 3 defer, 6 dismissed**.

**Patch:**

- [x] [Review][Patch] **FIXED** Capability probe sits outside the best-effort try/catch and is unguarded for a null return — `getCapabilities()` can break the documented "this method NEVER throws" invariant for a future non-Linear adapter (shipped Linear adapters return a constant, so no current trigger) [WorkflowOrchestrationService.java:1154] — guarded the probe in try/catch (thrown → POST_FAILED) + null-capabilities treated as skip.
- [x] [Review][Patch] **FIXED (Alex 2026-06-17 → Option A: up-front IAE)** Blank (non-null) `ticketRef` previously threw `IllegalArgumentException` from `TicketRef.of(...)` AFTER the idempotency reservation, escaping the `completeInIndependentTransaction(...FAILED)` cleanup that the old bare-`String` not-found path reached — dangling RESERVED record + unclassified exception [IntegrationLinkService.java:203, :312]. Resolution: added an up-front non-blank guard (`throw new IllegalArgumentException("linearTicketReference must be non-blank")`) at the entry of both `linkTicket` and `linkTicketWithinTransaction`, BEFORE `checkAndReserve` / the pessimistic-lock port lookup — mirrors the existing `linkGitHubPr` guard, closing the dangling-RESERVED window and keeping the two ticket methods symmetric with the GitHub one. Blank is already upstream-blocked by `@NotBlank` (REST `SubmitWorkflowRequest` + `SubmitWorkflowCommand`), so no observable regression. Two new RED→GREEN unit tests pin that the guard fires before reservation/port lookup (`IntegrationLinkServiceUnitTest#blankTicketRefRejectedBeforeReservation`, `#blankTicketRefRejectedBeforePortLookupWithinTransaction`).
- [x] [Review][Patch] **FIXED** `TicketSourceProperties.kind` is lower-cased without `Locale.ROOT` — under a Turkish default locale a non-default `kind` containing `I` (e.g. a quoted `"LINEAR"`) maps to `lınear`, fails `isLinear()`, and trips the boot fail-fast on otherwise-valid config [TicketSourceProperties.java:30] — added `Locale.ROOT`.
- [x] [Review][Patch] **FIXED** Mock dedup keys on `(ticketRef, fingerprint)` while the real adapter's marker keys on `(ticketRef, runPublicId, fingerprint)` — fidelity-only divergence from the documented "mirrors the real adapter" contract; behaviorally equivalent today because the fingerprint basis already includes `runId` [LinearMockAdapter.java:112-119] — added `runPublicId` to the dedup predicate + Javadoc.

**Deferred (pre-existing / forward-looking):**

- [x] [Review][Defer] `kind` fail-fast validates the kind value but not profile-vs-kind consistency; `kind=linear` with no active `linear-*` profile passes the assertion then fails with an opaque Spring no-bean error [LinearConfiguration.java assertSupportedTicketSourceKind] — deferred: forward-looking (only the `linear` kind exists today, no mismatch possible; unconditional consumers already fail-fast at startup)
- [x] [Review][Defer] Blank (non-null) `sourceStatusId` bypasses the null-only auto-ingest eligibility guard [LinearPollingHost.java:339-343] — deferred: pre-existing gate semantics carried over verbatim; requires an implausible blank-id + blank-allow-list double-misconfig
- [x] [Review][Defer] Poll watermark trusts positional-last (`tickets.get(size-1)`) assuming the adapter returns ascending-sorted-by-`updatedAt`; a future non-sorting `TicketSourceAdapter` could regress the watermark [LinearPollingHost.java:204] — deferred: pre-existing pattern, only the element type changed (`LinearTicket`→`Ticket`)

**Dismissed as noise (6):** mock dedup read-then-add race (test-only double, no concurrent caller); `SKIPPED_DUPLICATE` logged/returned as POSTED + no switch-default (intentional & documented idempotent-replay, `result=` field disambiguates); `TicketSourceProperties` no test-yaml mirror (no validation annotations + benign default — verified harmless); `supportsTicketStateUpdates` declared-but-ungated (matches spec scope); leak ArchUnit rule not covering `domain.integration.ticketsource` (domain is already protected by the layered domain→adapters rule); ADR-list doc nit omitting 0006 (story Dev Notes only, non-shipped).

### Review Findings (bmad-code-review 2026-06-18 — re-review)

Second adversarial pass over the working-tree diff (38 backend source/test files, ~3.4k lines incl. untracked port/domain/foundation). Three layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor. Acceptance Auditor returned a **full PASS — all 10 ACs MET, all 4 OQ resolutions + 6 reconciliations honored, no violations** (verified the prior round's 4 patches held: probe guard, up-front blank-`ticketRef` IAE, `Locale.ROOT`, mock dedup `runPublicId` key). Triage: **0 decision-needed, 1 patch, 0 new defer, 5 dismissed**. Most hunter findings re-surfaced items already triaged on 2026-06-17 (see prior subsection).

**Patch:**

- [x] [Review][Patch] **FIXED** `TicketSourceProperties` Javadoc overstates the boot guard and names a non-existent class — the class doc says "`TicketSourceConfiguration` fail-fasts at boot when `kind` disagrees with the available implementation profile", but (a) there is no `TicketSourceConfiguration` class — the guard lives in `LinearConfiguration.assertSupportedTicketSourceKind`; (b) the guard only rejects a `kind` that is not `linear` (`!isLinear()`), it does NOT cross-check `kind` against the active `linear-mock`/`linear-real` profile. Fix: correct the class reference to `LinearConfiguration` and describe the actual behavior ("rejects a `kind` with no implementation on the classpath"), matching the accurate doc already on `LinearConfiguration`. Doc-only; no runtime change. [TicketSourceProperties.java:12-15] (blind+edge)

**Deferred (already captured — no new entry):**

- [x] [Review][Defer] No `kind`↔profile boot cross-check (`kind=linear` with no active `linear-*` profile boots with an unbacked port) — already deferred in the 2026-06-17 round (see prior subsection + deferred-work.md "code review of 3-32 (2026-06-17)"); the Javadoc patch above documents this gap accurately rather than re-asserting a cross-check that does not exist. [LinearConfiguration.java:59] — already deferred, pre-existing

**Dismissed as noise (5):** capability-probe throw routed to `recordSyncFailure` emits a `linear.completionSyncFailed` event though nothing was posted (defensible — a thrown `getCapabilities()` is a genuine adapter error; the prior-round guard upholds never-throws); `SKIPPED_DUPLICATE` mapped to `POSTED` at the orchestrator (intentional & documented — the write-back is durably present, `result=` field disambiguates); mock dedup "contract drift" vs real adapter (no concrete divergence — marker embeds `runPublicId`, Auditor confirms faithful mirror); mock dedup non-atomic read-then-add under concurrency (test-only double, no concurrent identical-key caller — same dismissal as 2026-06-17); `kind` exotic-whitespace/unicode bypass (false positive — `String.strip()` already removes Unicode whitespace incl. tab; `SKIPPED_NO_COMMENT_CAPABILITY` emits no `WorkflowEventType` — by design per OQ-4 to avoid registry/fixture fan-out, AC3 MET).

## Change Log

| Date | Change |
| --- | --- |
| 2026-06-17 | Implemented story 3.32 — extracted vendor-neutral `TicketSourceAdapter` abstraction from `LinearAdapter`; all 4 OQs resolved to recommended; ArchUnit + foundation Contract #14 + parity/capability tests green; status → review. |
| 2026-06-17 | Addressed code review findings — 1 item resolved: blank `ticketRef` up-front non-blank guard (Alex → Option A) in `linkTicket`/`linkTicketWithinTransaction` before reservation; +2 unit tests. Full Surefire 1034 passed / 0 failures. |
| 2026-06-18 | Re-review (bmad-code-review) — Acceptance Auditor full PASS (all 10 ACs MET, prior 4 patches verified holding). 1 net-new doc-only patch fixed: corrected `TicketSourceProperties` Javadoc (named non-existent `TicketSourceConfiguration` + overstated guard as a kind↔profile cross-check). 5 dismissed. Status → done. |

---
**Ultimate context-engine analysis completed — comprehensive developer guide created.**
