# Story 3i.1: JIRA Ticket Source (kind=jira) — Linear-parity connector

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a pilot operator whose tickets live in JIRA,
I want JIRA to be a first-class ticket source at parity with Linear — fetch, comment, sub-ticket creation, state read, and a link-out URL,
so that I can run governed workflows on real JIRA tickets without changing how the rest of the system behaves.

## Acceptance Criteria

1. **Given** the `TicketSourceAdapter` port (story 3-32), **Then** a new impl declares `connectorKind()=ConnectorKind.JIRA` and implements `fetchTicketByReference`, `postGovernedRunComment`, `createSubticket` (a JIRA sub-task under the parent issue), a ticket-state read (populated on the returned `Ticket` — see Reconciliation R2), the 3g-1 `buildSourceTicketUrl` (the JIRA issue browse URL for a `TicketRef`), and `verifyConnectivity`; a `jira`-kind project resolves to it via the 3c-3 `ProjectConnectorResolver` (keyed on `connectorKind()`, **not** `@Primary`).
2. **Given** `getCapabilities`, **Then** it reports the real JIRA `TicketSourceCapabilities` set: `supportsCommentOnTicket=true`, `supportsTicketCreation=true`, `supportsSourceTicketUrl=true`, `supportsTicketStateUpdates=true` (JIRA populates `sourceStatus`/`sourceStatusId`), and `supportsPolling=true` — verified by a capability contract test.
3. **Given** the `ConnectorKind` registry, **Then** `JIRA("jira")` is added (enum value + Flyway connector-kind CHECK widening at the next-free Flyway head — the `V18`/GITLAB precedent — re-add **both** `ck_projects_ticket_source_kind` and `ck_projects_repo_host_kind` with `jira`); replay-safe; and the three `RegistryContractTest` alignment legs (enum → `DomainRegistry.connectorKinds()`, both DB CHECKs, and the `connectorKinds` API placeholder) plus `FlywaySchemaContractTest` stay green.
4. **Given** the 3c-5 encrypted credential store, **Then** the JIRA per-project secret (API token, plus account email if used) is stored write-only encrypted under `ConnectorRole.TICKET_SOURCE`, never exposed on read, and passes the redaction posture — the **two-gates** trap: the fixture-manifest gate **and** the hardcoded `SensitivePayloadAnalyzer` corpus (mirrored into `runner-contracts/redaction-policy.json`); nothing secret is logged (ids/lengths only).
5. **Given** `verifyConnectivity` (3c-8), **Then** the JIRA adapter probes auth + project reachability (`GET /rest/api/3/myself` + a project lookup) and returns a secret-free `ConnectivityResult`; an unreachable/unauthorized instance yields `unreachable`/`unauthenticated` (never a thrown exception across the port, never a 5xx).
6. **Given** the doctor probe pattern (3c-10), **Then** a `jira-auth` doctor probe is added and **every hardcoded `checksRun` assertion is incremented** (the fan-out trap): `DoctorService.STATIC_ORDER`, the `DoctorLoggingContractTest` `checksRun=18`→`19` literal + its all-probe stub block, and the five all-probe stub blocks in `DoctorServiceTest` are all updated.
7. **Given** idempotency + parent-link, **Then** `createSubticket` is keyed on `draft.idempotencyKey()` (the 3f-1 contract) via a marker scan so a replayed split does not double-create (`replay=true`), and posts a parent-link back-reference through the adapter's own `postGovernedRunComment` (the 3f-1 shape).
8. **Given** tests, **Then** coverage asserts: capability contract drift; `fetchTicketByReference` / `postGovernedRunComment` / `createSubticket` happy-paths + classified-failure parity (mock ↔ real); ticket-URL build; `ConnectorKind`/Flyway drift; connectivity probe (reachable + auth-fail + unreachable); credential redaction over the JIRA corpus; doctor `checksRun` fan-out; and the `org.dradgo.application.integration.ticketsource` package holds its **≥0.80 line** jacoco floor.

## Tasks / Subtasks

- [ ] **Task 1 — `ConnectorKind.JIRA` + Flyway + registry drift** (AC: #3)
  - [ ] Add `JIRA("jira")` to `domain/registry/ConnectorKind.java` (after `GITLAB`). `DomainRegistry.connectorKinds()` auto-derives from `values()` — no edit there.
  - [ ] Add migration `db/migration/V34__widen_connector_kind_to_jira.sql` mirroring `V18__widen_connector_kind_to_gitlab.sql`: drop + re-add **both** `ck_projects_ticket_source_kind` and `ck_projects_repo_host_kind` with `check (... in ('linear','github','gitlab','jira'))`. **RE-CONFIRM the head number at implementation** — memory flags `V33` as the highest on this branch's disk but `3h-2` claims `V34` on an unmerged branch (Flyway cross-branch-collision trap); if `V34` is taken by then, take the next free.
  - [ ] Update the API placeholder `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` key `connectorKinds` → add `"jira"`.
  - [ ] Extend `FlywaySchemaContractTest` with a `jira`-accepted insert probe (optional; the existing `bogus`-rejected probe already passes). Confirm `flywayMigrateIsReplaySafeAndChecksumStable()` and `RegistryContractTest.projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest()` are green (three-way alignment: enum, both CHECKs, placeholder).
- [ ] **Task 2 — Config + wiring + kind-selector generalization** (AC: #1)
  - [ ] Add `application/integration/jira/JiraProperties.java` (`@ConfigurationProperties("deliveryline.jira")`) mirroring `LinearProperties`: `baseUrl`, `email` (account email for basic-auth), `apiToken` (with `@JsonIgnore` accessor + redacting `toString()`), `timeout`, and a `CREDENTIAL_OVERRIDE_ATTRIBUTE` constant. Keep it framework-light in the `application` layer (not `infrastructure`).
  - [ ] Add `infrastructure/config/JiraConfiguration.java` mirroring `LinearConfiguration`: `@Bean("jiraRestClient") @Profile("jira-real")` `RestClient` with connect/read timeouts and a request interceptor that reads the token at request-time and prefers the per-request `CREDENTIAL_OVERRIDE_ATTRIBUTE` (3c-8). Assert mutual exclusion of `jira-mock`/`jira-real`. JIRA Cloud auth = HTTP Basic `email:apiToken` (Base64) — build the header inside the interceptor; **never log it**.
  - [ ] **Generalize the ticket-source `kind` fail-fast (deferred-work #132).** Today `LinearConfiguration` constructor runs `assertSupportedTicketSourceKind` unconditionally and throws unless `kind==linear`, so a `kind=jira` deployment cannot boot. Move the "is this kind backed by a classpath impl?" check out of `LinearConfiguration` into a connector-neutral seam that validates the configured kind against the set of registered `TicketSourceAdapter` beans (i.e. the kinds `ProjectConnectorResolver` indexes), OR minimally widen the supported set to include `jira`. Recommended: connector-agnostic validation so the next connector needs no edit here. (See OQ-1.)
  - [ ] Register `jira-real`/`jira-mock` in `application.yml` profile docs; add `deliveryline.jira.*` keys.
- [ ] **Task 3 — `JiraRealAdapter`** (AC: #1, #2, #5, #7)
  - [ ] New `adapters/integration/ticketsource/jira/JiraRealAdapter.java`, `@Component @Profile("jira-real")` implementing `TicketSourceAdapter`. **Do NOT add `@Primary`** (see Reconciliation R1) — per-project resolution keys on `connectorKind()`; a second `@Primary` would collide with `LinearRealAdapter` for the single-injection `LinearPollingHost`. Constructor takes `@Qualifier("jiraRestClient") RestClient` + `JiraProperties`.
  - [ ] Implement against JIRA REST v3: `fetchTicketByReference` → `GET /rest/api/3/issue/{key}` (map to neutral `Ticket`: `ticketRef`, `title`=summary field, `summary`=description text, `authorIdentity`=reporter accountId/email string — never a vendor user DTO, `createdAt`/`updatedAt`, `labels`, and nullable `sourceStatus`=`fields.status.name` + `sourceStatusId`=`fields.status.id` as the opaque vendor token). Return `Optional.empty()` on 404 — **do not throw on not-found**.
  - [ ] `pollNewTickets(since)` → JQL `updated > "…" ORDER BY updated ASC`, ascending by `updatedAt`, internal paging, empty list when nothing new. (Wiring a `JiraPollingHost` bean is **out of scope** for 3i-1 — the Linear host is Linear-specific; interactive intake is 3i-2. The method must still work for the parity/capability contract.)
  - [ ] `postGovernedRunComment` → `POST /rest/api/3/issue/{key}/comment`; embed the `<!-- deliveryline:run=<runPublicId> fp=<fingerprint> -->` marker (mirror `LinearRealAdapter.fingerprintMarker`), scan existing comments first, return `SKIPPED_DUPLICATE` on a marker hit else `POSTED`.
  - [ ] `createSubticket` → `POST /rest/api/3/issue` with `issuetype=Sub-task` + `parent={parentKey}`; idempotency via a `<!-- deliveryline:subticket key=<idempotencyKey> child=<childRef> -->` marker scan on the parent (mirror `LinearRealAdapter.createSubticketGuarded`), returning the existing child with `replay=true` on a hit; after create, post the parent-link back-reference through `postGovernedRunComment` with fingerprint `"subticket:"+draft.idempotencyKey()` and `DataClassification.SHAREABLE_REDACTED`.
  - [ ] `buildSourceTicketUrl` → `Optional.of(properties.baseUrl() + "/browse/" + ref.value())` gated on the `^[A-Z][A-Z0-9_]*-[0-9]+$` ref pattern; `Optional.empty()` when unmatched or `baseUrl` unset. Pure derivation — no network, no secret, never logged.
  - [ ] `getCapabilities()` → the JIRA set (AC2). Add a `TicketSourceCapabilities.jiraDefaults()` factory (or reuse the 5-arg constructor).
  - [ ] `verifyConnectivity(credentialOverride)` → `GET /rest/api/3/myself` then a project lookup; fold `TicketSourceAdapterException` categories into `ConnectivityResult` (LINK_FAILURE→`unauthenticated`, NETWORK_API_FAILURE→`unreachable`, default→`new ConnectivityResult(true,false,…)`). Never throws across the port.
  - [ ] Error classification: funnel every HTTP call through a shared `execute(...)` that maps 401/403→`LINK_FAILURE`, 429/5xx/`ResourceAccessException`→`NETWORK_API_FAILURE`, malformed/validation→`SYNC_FAILURE`, unexpected/conflict→`STATE_CONFLICT`, wrapped in `TicketSourceAdapterException`. **No retry** — surface typed failures.
- [ ] **Task 4 — `JiraMockAdapter` parity twin** (AC: #1, #8)
  - [ ] New `adapters/integration/ticketsource/jira/JiraMockAdapter.java`, `@Component @Profile("jira-mock")` (no `@Primary`), deterministic, no network — mirror `LinearMockAdapter` (deterministic `child = parent-<ordinal>` refs, deterministic mock URL, `SKIPPED_DUPLICATE`/`POSTED` by in-memory marker, `ConnectivityResult.ok`).
- [ ] **Task 5 — Credential store + redaction two-gates** (AC: #4)
  - [ ] JIRA per-project secret rides `ProjectCredentialService.setCredential(projectPublicId, ConnectorRole.TICKET_SOURCE, plaintext)` — **no schema change** (`ciphertext` is opaque). Store the API token (and email if per-project) as the plaintext; the connectivity/adapter path decrypts on-stack and passes it as the `CREDENTIAL_OVERRIDE_ATTRIBUTE`. `baseUrl` is **non-secret deployment config** on `JiraProperties`, so `buildSourceTicketUrl` never decrypts. (See OQ-2.)
  - [ ] **Gate A** — add fixture `src/test/resources/redaction-fixtures/project-credential-jira-token.json` (a fake Atlassian token, e.g. `ATATT3xFfGF0-FAKE…`) + a `fixtures-manifest.json` entry (`placeholder`, `minimumClassification`, `forbiddenSnippets`). `RedactionAdversarialFoundationContract` fails if the file is unlisted.
  - [ ] **Gate B** — Atlassian API tokens have **no stable public prefix**, so do **not** add a vendor regex; instead ensure the credential JSON secret key(s) are covered by `SensitivePayloadAnalyzer.SECRET_FIELD_NAMES` (add `jiraApiToken`/the chosen key if not already covered by `token`/`apiKey`) and mirror any change into `runner-contracts/redaction-policy.json` (`RedactionPolicyParityContractTest` asserts JSON↔Java equality). Only add a `RedactionCategory.JIRA_API_TOKEN` if a reliable pattern is chosen.
- [ ] **Task 6 — Doctor `jira-auth` probe + checksRun fan-out** (AC: #6)
  - [ ] `application/diagnostics/spi/DoctorProbePort.java` → add `ProbeResult probeJiraAuth();`.
  - [ ] `adapters/diagnostics/DoctorProbeAdapter.java` → implement it (profile-gate first: PASS-not-applicable with **no** network call when `jira-real` inactive; presence-only details; typed `DomainErrorCode` on failure; never log the token). Add a package-private `(Environment, RestClient jiraRestClient, JiraProperties)` test-seam constructor mirroring the github-auth seam.
  - [ ] `application/diagnostics/DoctorService.java` → add `CHECK_JIRA_AUTH="jira-auth"` constant, a `STATIC_ORDER` entry (this is what makes the count 18→19), a `case CHECK_JIRA_AUTH -> probes.probeJiraAuth();` switch arm, and an optional `REMEDIATION` entry.
  - [ ] Fan-out (do not miss any): `DoctorLoggingContractTest.java:90` `checksRun=18`→`19` + add a `when(probes.probeJiraAuth())…` stub in its all-probe block; add a `probeJiraAuth()` stub to `DoctorServiceTest.stubAllProbesPass()` **and** its four other inline all-probe stub blocks (else Mockito returns null → NPE). `DoctorServiceTest`'s `hasSize(STATIC_ORDER.size())` auto-tracks.
  - [ ] New `DoctorJiraProbeTest` modeled on `DoctorGitHubProbeTest` (MockRestServiceServer; PASS-not-applicable / token-missing / 200 / 401).
- [ ] **Task 7 — Tests** (AC: #8)
  - [ ] `TicketSourceCapabilitiesTest` → add a `jiraDefaults()` flag-set assertion.
  - [ ] Extend / mirror `TicketSourceAbstractionFoundationContract` for the JIRA mock↔real pair (happy read → neutral `Ticket`; a classified failure surfaces the same `IntegrationFailureCategory` in both). Mock HTTP with `MockRestServiceServer.bindTo(builder)` (NOT WireMock/MockWebServer). Testcontainers-backed tests are named `*IT` (Failsafe), never `*Test`.
  - [ ] Adapter unit tests in `org.dradgo.adapters.integration.ticketsource.jira` (`JiraRealAdapterUnitTest`, `JiraMockAdapterUnitTest`) covering fetch/comment/subticket happy + replay, URL build, connectivity (reachable/auth-fail/unreachable), and the error-classification map.
  - [ ] Keep the `org.dradgo.application.integration.ticketsource` package at its jacoco **≥0.80 line** floor (new application-layer JIRA config/props land here; the `adapters…jira` package is governed by the 0.75 bundle floor only, per 3c-11 D1).
- [ ] **Task 8 — ArchUnit + docs + ADR** (AC: #1, #3)
  - [ ] `ArchitectureRuleCatalog.TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` — if the JIRA adapter introduces a vendor SDK package, add its prefix to the banned list (keep `RestClient`/JIRA-DTO types inside `adapters.integration.ticketsource.jira`). Placement (`adapters.integration.ticketsource.jira`) is already covered by `ADAPTER_PACKAGE_LAYOUT` (`integration` slice) and `TICKET_SOURCE_IMPLS_RESIDE_IN_ADAPTERS_TICKETSOURCE` — no edit.
  - [ ] Add `JiraRealAdapter`'s FQN to the `ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT` exception list (the real adapter self-posts the parent-link comment from `createSubticket`, exactly as `LinearRealAdapter` is exempted).
  - [ ] Docs: append a JIRA note to `docs/adr/0007-ticket-source-abstraction.md` (second real `TicketSourceAdapter` kind) and `docs/integrations/ticket-source-extension-contract.md`; add `jira` + `bug promotion`-adjacent vocabulary check to `docs/glossary.md` (`ConnectorKind` is the connector registry); add the `jira-auth` row to the (already-stale) `docs/cli/doctor.md` check-list; a connector-onboarding note for JIRA credentials.
- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException`/`TicketSourceAdapterException` raise site, every external SPI call (each JIRA REST call, the credential decrypt, the doctor probe), and every idempotency-replay branch (comment `SKIPPED_DUPLICATE`, subticket `replay=true`).
  - [ ] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [ ] Levels: `INFO` for normal lifecycle (fetch/comment/subticket start+outcome, connectivity resolution, transitions), `WARN` for recoverable anomalies (replay/duplicate, connectivity fail, capability-degraded skip), `ERROR` only for unhandled failures. `DEBUG` for hot-path detail.
  - [ ] Every log carries relevant context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, plus the entity's public id (`ticketRef`/`projectPublicId`) — sanitized via `MdcKeys.sanitizeForLog(...)`. **Never** log the JIRA token, Basic-auth header, ciphertext, key id, comment body bytes, or ticket free-text (ids/lengths only).
  - [ ] Pin the new log lines with a focused test (`OutputCaptureExtension` / list-appender) at the expected level per new branch.

## Dev Notes

### Central Reconciliations (live bindings win over the epic AC text)

- **R1 — JIRA adapters are NOT `@Primary`.** `LinearRealAdapter`/`LinearMockAdapter` are `@Component @Primary @Profile(...)` because the single-injection `LinearPollingHost` (`@Profile("linear-real")`, injects one `TicketSourceAdapter`) relies on `@Primary`. Per-project resolution (3c-3) is done by `ProjectConnectorResolver`, which indexes `List<TicketSourceAdapter>` into an `EnumMap` keyed by each adapter's `connectorKind()` — it does **not** consult `@Primary`. Marking the JIRA real adapter `@Primary` would make a `linear-real`+`jira-real` co-activated deployment fail with `NoUniqueBeanDefinitionException` at the polling-host injection. So: `@Component @Profile("jira-real")` **without** `@Primary`. The epic's phrase "resolved via the 3c-3 `@Primary` resolution" is imprecise — resolution is registry/`connectorKind()`-driven. [Source: `application/project/ProjectConnectorResolver.java`; `infrastructure/config/LinearPollingHost.java:81-107`]
- **R2 — There is no separate "ticket-state read" port method.** The port carries state on the neutral `Ticket` via nullable `sourceStatus` (display name) + `sourceStatusId` (opaque vendor token). AC1's "ticket-state read" is satisfied by `fetchTicketByReference` populating those from `fields.status`, with `supportsTicketStateUpdates=true` advertising it. There is **no** state-**write** method (JIRA workflow transitions are out of scope for 3i-1). [Source: ADR 0007 Decision 2; `domain/integration/ticketsource/Ticket.java`]
- **R3 — `createSubticket` is 2-arg `(TicketRef parentRef, SubticketDraft draft)`.** The "5-arg" memory note refers to the *repository-host* `createPullRequest`, not this port. Title/description ride inside the 7-field `SubticketDraft`; the durable dedup key is `draft.idempotencyKey()`. The only legal caller is `TicketSourceSubticketService` (capability-gated on `supportsTicketCreation`) — no new caller, and the `ONLY_SUBTICKET_SERVICE_MAY_CREATE_SOURCE_SUBTICKET` ArchUnit rule is unchanged. [Source: `TicketSourceAdapter.java`; `application/integration/ticketsource/TicketSourceSubticketService.java`]
- **R4 — The global `deliveryline.integration.ticket-source.kind` selector is a 3.32 single-active legacy** superseded by per-project resolution, but `LinearConfiguration`'s constructor still hard-fails any `kind` other than `linear`. Task 2 must generalize that assertion or `kind=jira` cannot boot. This is exactly deferred-work note #132 ("add a profile-vs-kind cross-check when the second kind lands"). [Source: `infrastructure/config/LinearConfiguration.java:59-69`; `deferred-work.md:132`]
- **R5 — Flyway head is contested.** `V33` is the highest on this branch's disk, but `3h-2` (unmerged) claims `V34`, and `4-1/4-3/4-15/4-17` claim `V35/V36` on other unmerged branches. Take the next-free head at implementation time; do not assume `V34` is free (Flyway cross-branch-collision trap). [Source: memory `flyway-v31-cross-branch-collision`; `db/migration/`]

### Source tree components to touch

- **New:** `domain/integration/ticketsource/TicketSourceCapabilities.java` (`jiraDefaults()` factory); `application/integration/jira/JiraProperties.java`; `infrastructure/config/JiraConfiguration.java`; `adapters/integration/ticketsource/jira/JiraRealAdapter.java` + `JiraMockAdapter.java`; `db/migration/V34__widen_connector_kind_to_jira.sql`; `redaction-fixtures/project-credential-jira-token.json`; tests (`JiraRealAdapterUnitTest`, `JiraMockAdapterUnitTest`, `DoctorJiraProbeTest`).
- **Edit:** `domain/registry/ConnectorKind.java`; `application/diagnostics/spi/DoctorProbePort.java`; `adapters/diagnostics/DoctorProbeAdapter.java`; `application/diagnostics/DoctorService.java`; `application/security/SensitivePayloadAnalyzer.java` (+ `runner-contracts/redaction-policy.json`) if a field name is added; `infrastructure/config/LinearConfiguration.java` (generalize the kind fail-fast); `architecture/ArchitectureRuleCatalog.java` (comment allow-list + any vendor-package ban); the doctor drift tests + `RegistryContractTest` placeholder + `FlywaySchemaContractTest`; `TicketSourceCapabilitiesTest`; `TicketSourceAbstractionFoundationContract`; docs.

### Reuse (do NOT reinvent)

- **Port & neutral types:** `application/integration/ticketsource/TicketSourceAdapter.java`; `domain/integration/ticketsource/{Ticket,TicketRef,CommentResult,GovernedRunComment,SubticketDraft,CreateSubticketResult,TicketSourceCapabilities}`; `application/integration/ConnectivityResult.java`; `application/integration/ticketsource/TicketSourceAdapterException.java` + `domain/registry/IntegrationFailureCategory`.
- **Reference impl to mirror byte-for-byte in structure:** `adapters/integration/ticketsource/linear/LinearRealAdapter.java` (marker idempotency, error classification, credential-override interceptor, URL build) and `LinearMockAdapter.java`; config `infrastructure/config/LinearConfiguration.java` + `application/integration/linear/LinearProperties.java`.
- **Resolution/credential/connectivity:** `application/project/ProjectConnectorResolver.java`, `ProjectCredentialService.java`, `ProjectConnectivityService.java`; `application/security/{CredentialCipher,EncryptedSecret}` (no schema change).
- **Do NOT copy the stub shape:** `adapters/integration/ticketsource/gitlab/GitLabTicketSourceStubAdapter.java` is the *degraded* precedent — JIRA is a **real** impl (all capabilities true, real HTTP).

### Testing standards summary

- Surefire runs `*Test` (excludes tags architecture|integration|contract|known-failure); Testcontainers/Spring-context tests are `*IT` under Failsafe — name any JIRA IT `*IT` or it leaks into Windows Surefire and reds CI.
- Mock external HTTP with `org.springframework.test.web.client.MockRestServiceServer` bound to a `RestClient.builder()` (the repo convention; no WireMock/MockWebServer).
- ArchUnit `@ArchTest`s run in **Failsafe**, not Surefire — verify boundary/rule changes there. `@ConfigurationProperties` validation needs the test `application.yml` updated for new `deliveryline.jira.*` keys.
- Coverage: `org.dradgo.application.integration.ticketsource` carries a named **0.80 line** package floor; the new `adapters…jira` package rides the 0.75 bundle floor.

### Logging Requirements (project-wide standard)

Every story leaves the touched services observable enough to debug a production incident without re-deploying (enforced via the "Logging instrumentation" task).

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **Where (min surface):** each `JiraRealAdapter` public method (`INFO` entry + outcome; `WARN` on classified failure / duplicate-replay / capability-degraded; `ERROR` on unexpected), the doctor probe, and the credential decrypt.
- **Required context keys:** `correlationId`, `workflowRunId`, `idempotencyKey`, `ticketRef`, `projectPublicId` — via `MdcKeys.sanitizeForLog(...)`.
- **Forbidden in output:** JIRA API token, Basic-auth header, ciphertext, key id, comment body bytes, ticket free-text. Pass through the redaction/classification path before logging (ids/lengths only).
- **Test contract:** pin new log lines with `OutputCaptureExtension`/list-appender.

### Project Structure Notes

- JIRA impls live under `org.dradgo.adapters.integration.ticketsource.jira` (the `integration` adapter slice — no `ADAPTER_PACKAGE_LAYOUT` edit). Vendor DTOs/`RestClient` stay in that package (the `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` rule bans them from the port package).
- `JiraProperties` sits in `application.integration.jira` (framework-light), `JiraConfiguration` in `infrastructure.config` — mirroring the Linear split so `APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE` stays green.
- No FE, no OpenAPI/`schema.d.ts` change in 3i-1 (the intake/query REST surface is **3i-2**). No new `WorkflowState`/`WorkflowEventType`/`AllowedAction`. New `DomainErrorCode`: none (reuse `UNSUPPORTED_CONNECTOR_KIND` for a miss).

### Open Questions (non-blocking — recommended answers baked in)

- **OQ-1 (kind-selector generalization):** generalize `assertSupportedTicketSourceKind` to validate against the resolver's registered kinds (connector-agnostic, recommended) vs. a minimal `{linear, jira}` widen. Recommend the connector-agnostic form so 3i-3/future connectors need no edit. Confirm with Alex whether the global `kind` selector should be retired outright in favor of per-project resolution.
- **OQ-2 (credential shape):** `baseUrl` as **deployment-level** `JiraProperties.base-url` (recommended, mirrors `deliveryline.linear.*`, keeps `buildSourceTicketUrl` secret-free) vs. per-project (a new `projects.jira_base_url` column — heavier, multi-project multi-instance only). And: is the per-project secret just the **API token**, or **token+email** (if operators use distinct JIRA accounts per project)? Recommend token-only per-project override + deployment-level email, revisit if pilots need per-project accounts.
- **OQ-3 (`@Primary` / co-activation):** confirm the pilot runs one real ticket-source profile per deployment for now; the `@Primary`-free JIRA adapter already supports `linear-real`+`jira-real` co-activation for true per-project multi-vendor if/when needed.
- **OQ-4 (Flyway head):** re-confirm the next-free `V` number against merged state at implementation (`V34` may be taken by 3h-2).

### References

- [Source: `_bmad-output/planning-artifacts/epic-03i-connector-expansion.md#Story 3i-1`] — ACs 1–8, Cross-Cutting Notes, FR80.
- [Source: `docs/adr/0007-ticket-source-abstraction.md`] — the abstraction decisions (opaque `sourceStatusId`, governed comment, `kind` selector).
- [Source: `docs/integrations/ticket-source-extension-contract.md`] — per-method contract, error classification, idempotency, redaction-on-egress.
- [Source: `docs/adr/0013-credential-encryption.md`] — 3c-4/3c-5 envelope credential store.
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md#Deferred from: code review of story-3-32`] — line 132, the profile-vs-kind cross-check owed when a second kind lands.
- [Source: prior stories `3-32-*`, `3c-3-*`, `3f-1-*`, `3g-1-*` (`3g-1-ticket-origin-snapshot-and-read-model.md`)] — the substrates this story extends.

## Dev Agent Record

### Agent Model Used

<!-- populated by dev-story -->

### Debug Log References

### Completion Notes List

- Ultimate context engine analysis completed — comprehensive developer guide created.

### File List
