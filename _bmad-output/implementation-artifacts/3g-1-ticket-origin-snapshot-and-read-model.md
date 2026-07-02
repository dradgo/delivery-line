# Story 3g.1: Ticket-Origin Snapshot + Read Model

Status: done

<!-- 2026-07-02 bmad-create-story context-engine pass (Opus 4.8 [1m]). Target sprint key: 3g-1-ticket-origin-snapshot-and-read-model. This is the FIRST Epic 3g story, so sprint-status marks epic-3g in-progress. Source: epic-03g-provenance-token-accounting.md + sprint-change-proposal-2026-06-29-epics-3g-3l.md. Delivers FR73 backend (origin/title visibility); 3g-2 consumes it on the FE. -->

> **READ FIRST — this is a pure additive READ-MODEL + connector-URL story.** No new `WorkflowState`, `AllowedAction`, `WorkflowEventType`, `DomainErrorCode`, or Flyway migration. Do **not** build any FE here (that is 3g-2), do **not** touch token accounting (that is 3g-3/3g-4), and do **not** add a `workflow_runs` column.
>
> **CRITICAL PREMISE CORRECTION (the epic text is imprecise here — trust this):** the originating ticket **`title` is ALREADY persisted** into `integration_links.external_metadata` at run creation (see `IntegrationLinkService.buildExternalMetadata` writing the `title` key, redacted with `SHAREABLE_REDACTED`) and is already read back by `IntegrationLinkTicketSummaryProvider` for the context bundle. It is **not** "fetched then discarded." What is genuinely missing is: (1) a **`url` key** in `external_metadata` (no connector produces a source-ticket URL today), and (2) the **read models** (`WorkflowSummaryResponse`, `WorkflowDetailResponse.LinkedTicket`) never read `title`/`url` back out — they only read `externalRef`. THIS story closes (1) and (2). Do not re-plumb the title-write persistence that already works.

## Story

As an authorized user scanning the run review queue or opening a run,
I want each run to carry its originating ticket's title and a link back to the source ticket,
so that I can see *what* the run is and *where it came from* without decoding a bare `ticketRef`.

## Acceptance Criteria

1. **Given** run creation (where the linked `integration_link` is written via `IntegrationLinkService.linkTicket` / `linkTicketWithinTransaction`), **Then** `external_metadata` carries a `title` key (already written today — preserve it) **and a new `url` key** holding the connector-built source-ticket URL, both **snapshotted once at link time** — immutable (never live-resolved on read), adding **no** new `workflow_runs` column and **no** Flyway migration (`external_metadata` is an additive JSON map). A connector that cannot build a URL, or a run with no linked ticket, leaves `url` absent/`null`; pre-3g rows (already linked, no `url` key) read `null` for `url` (parity).
2. **Given** the `TicketSourceAdapter` port, **Then** it gains a capability-gated source-ticket-URL builder: `TicketSourceCapabilities` gains `supportsSourceTicketUrl` and the port gains `Optional<String> buildSourceTicketUrl(TicketRef ref)`. `LinearRealAdapter` + `LinearMockAdapter` report `true` and build the issue URL (mock returns a deterministic stub URL); the always-on `GitLabTicketSourceStubAdapter` reports `false` and its builder returns `Optional.empty()`. Consumers **must** check `supportsSourceTicketUrl()` before calling (mirrors the `createSubticket`/`supportsTicketCreation` pattern from 3f-1). The port returns a plain `Optional<String>` — **no vendor type crosses the port** (`TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT`).
3. **Given** the read models, **Then** `WorkflowRunSummaryView` → `WorkflowSummaryResponse` gains a **nullable** `ticketTitle` (appended at the END of the field list), and `LinkedTicketView` → `WorkflowDetailResponse.LinkedTicket` gains **nullable** `title` + `url`; all three are sourced from the **already-persisted** `external_metadata` (`title`, `url` keys). The active-link read that already runs per-run is **widened in place** to carry title+url — **no second per-run query** (no N+1 regression in `listRuns`). OpenAPI snapshot + `schema.d.ts` regenerate (NOT byte-identical). No field is required; an unlinked or pre-3g run serializes all three as `null`.
4. **Given** the summary exact-field contract test (the `containsExactlyInAnyOrder` guard over `WorkflowSummaryResponse.fieldNames()` in `WorkflowReadEndpointsContractTest`), **Then** the expected-field set is updated to include `ticketTitle`, or the new field silently reds CI-only (the documented summary-exact-field trap).
5. **Given** redaction (story 1.10), **Then** the `title` retains its existing write-side redaction (it already passes `RedactionPolicyService.redact(..., SHAREABLE_REDACTED)` inside the link flow) and the new `url` is written through the **same** redaction pass so a URL carrying secret-shaped query material is sanitized; read-back re-uses the stored (already-redacted) values without a fresh redaction pass, matching the `ticketRef` "trusted persisted value, sanitize only at log sites" posture. The title/URL are **never logged in full** — ids / lengths / `MdcKeys.sanitizeForLog` only; the URL builder logs no token/query material.
6. **Given** tests, **Then** coverage asserts: the `url` snapshot **persists at link creation** and is immutable thereafter; the title snapshot is preserved; summary carries `ticketTitle` and detail carries `LinkedTicket.title`/`url` sourced from `external_metadata`; **unlinked / pre-3g parity** (no `url` key, no link → all three `null`, contract test green); **URL capability fallback** (a `false`-capability connector → `buildSourceTicketUrl` empty → `url == null`, builder never mis-called); the exact-field contract test passes with `ticketTitle`; log-safety (no full title/URL in logs); `application.*` coverage stays at or above the committed gate (≥80%).

## Tasks / Subtasks

- [x] **Task 1 — Add the capability flag + URL-builder port method** (AC: 2)
  - [x] Extend `domain/integration/ticketsource/TicketSourceCapabilities.java` from 4 booleans to 5 with `supportsSourceTicketUrl`. Update `linearDefaults()` (→ `true`) and the `noCreation(...)` factory (add the flag → `false`) plus **every** existing `new TicketSourceCapabilities(...)` construction site so the source stays green (the record-component fan-out pattern). *(Only the two factories construct the record directly — no external fan-out.)*
  - [x] Add `Optional<String> buildSourceTicketUrl(TicketRef ref)` to `application/integration/ticketsource/TicketSourceAdapter.java`. Javadoc it as an **optional operation guarded by `getCapabilities().supportsSourceTicketUrl()`**, returning `Optional.empty()` when unsupported. Return type is a plain `Optional<String>` — no vendor type.
  - [x] If `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` (ArchUnit) needs adjustment, only widen it to permit the new method's `Optional<String>`/`TicketRef` shape — do not loosen it generally. *(No adjustment needed — `Optional<String>`/`TicketRef` are already-permitted neutral shapes; rule verified green in Failsafe.)*

- [x] **Task 2 — Implement the builder in all three adapters** (AC: 2)
  - [x] `LinearRealAdapter`: report `supportsSourceTicketUrl=true`; build the real Linear issue URL from the `TicketRef` (gated on the existing `TICKET_REF_PATTERN`; workspace-agnostic deep link `https://linear.app/issue/<identifier>` — no workspace slug is available from the ref/config, documented in code; no tokens/query secrets). Return `Optional.empty()` if the ref cannot form a URL.
  - [x] `LinearMockAdapter`: report `true`; return a **deterministic** stub URL (`https://linear.mock/issue/<ref>`, distinct host from real) — no randomness, no wall-clock.
  - [x] `GitLabTicketSourceStubAdapter`: report `false`; `buildSourceTicketUrl` returns `Optional.empty()`.

- [x] **Task 3 — Snapshot the `url` into `external_metadata` at link time** (AC: 1, 5)
  - [x] In `IntegrationLinkService`, at the point the source `Ticket` is fetched through the already-resolved `TicketSourceAdapter` (the fetch path shared by `linkTicket` and `linkTicketWithinTransaction`), resolve the URL **capability-gated** via new `resolveSourceTicketUrl(TicketRef)`: `capabilities.supportsSourceTicketUrl() ? adapter.buildSourceTicketUrl(ref).orElse(null) : null`.
  - [x] Change `buildExternalMetadata(Ticket)` → `buildExternalMetadata(Ticket, String sourceTicketUrl)`; keep the existing keys; add the `url` key **only when non-null** (pinned in a test — pre-3g parity and no-URL rows read identically). Threaded from **both** call sites.
  - [x] The `url` flows through the **existing** `redactionPolicyService.redact(rawMetadata, SHAREABLE_REDACTED)` pass — no separate redaction call. 64KB ceiling unaffected.
  - [x] Did **not** touch the GitHub twin (`buildGitHubExternalMetadata`).

- [x] **Task 4 — Widen the read models (source title+url from external_metadata)** (AC: 3)
  - [x] Widened the **single active-link read** via a new `IntegrationLinkRecordPort.findActiveTicketOriginByWorkflowRun` → `TicketOriginProjection(integrationType, externalRef, syncStatus, externalMetadata)` (same `findActiveByWorkflowRunPublicIdLinearFirst` query — one read per run, no N+1) surfaced as `IntegrationLinkService.findActiveTicketOriginView` (decodes `title`/`url`). Both `listRuns` (summary) and the detail `toLinkedTicket` consume it.
  - [x] `WorkflowRunSummaryView`: added nullable `ticketTitle`. `LinkedTicketView`: added nullable `title` + `url` (+ 3-arg convenience ctor for legacy call-sites).
  - [x] `WorkflowSummaryResponse`: added nullable `ticketTitle` **at the END**; updated `from(...)`. `WorkflowDetailResponse.LinkedTicket`: added nullable `title` + `url`; updated `from(LinkedTicketView)`.
  - [x] Regenerated the OpenAPI snapshot and the FE types; committed `openapi.json` + `schema.d.ts` (`check:api` green).

- [x] **Task 5 — Update the summary exact-field contract test** (AC: 4)
  - [x] Added the literal `"ticketTitle"` to the `containsExactlyInAnyOrder` block in `WorkflowReadEndpointsContractTest`.

- [x] **Task 6 — Tests** (AC: 1–6)
  - [x] `TicketSourceCapabilities` defaults asserted (`linearDefaults` → true, `noCreation`/GitLab stub → false).
  - [x] `LinearMockAdapter.buildSourceTicketUrl` deterministic + non-empty; `GitLabTicketSourceStubAdapter` returns empty; false-capability adapter never called (asserted in `IntegrationLinkServiceUnitTest`).
  - [x] `LinearRealAdapter.buildSourceTicketUrl` maps a `TicketRef` to the expected URL + empty on unformable ref, no token/query material.
  - [x] `IntegrationLinkService`: persisted metadata contains `title` (preserved) + `url` (new); false-capability → no `url` key + builder never called; redaction pass still runs over the widened map.
  - [x] Read-model round-trip: summary exposes `ticketTitle`, detail exposes `LinkedTicket.title`/`url`; unlinked & pre-3g parity (all `null`); N+1 guard verifies exactly one origin read per run and no second `findActiveLinkByWorkflowRun`. Real-PG `WorkflowInspectionRunnerQueueIT` green.
  - [x] `WorkflowReadEndpointsContractTest` green with `ticketTitle`; `OpenApiSnapshotContractTest` green after regen.
  - [x] Log-safety: focused list-appender test asserting `originUrlSnapshotted=true` is logged while the URL string never reaches the log surface.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Extended `linkTicket`/`linkTicketWithinTransaction` success logs with an `originUrlSnapshotted={boolean}` flag (never the URL); URL builders log `present={bool}` at DEBUG with `MdcKeys.sanitizeForLog(ref)` only.
  - [x] Parameterized logging throughout; existing INFO/WARN levels + correlation/context keys preserved.
  - [x] Never logs the full title or source URL (asserted by the log-safety test).

### Review Findings

<!-- 2026-07-02 bmad-code-review (Opus 4.8 [1m]): 3 adversarial layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor) over the uncommitted diff. 9 unified findings → 1 decision-needed, 4 patch, 4 dismissed. Decision resolved (source a workspace slug) → all 5 patches applied 2026-07-02; affected unit tests green (107 run / 0 fail). -->

- [x] [Review][Patch] (was Decision — resolved 2026-07-02: source a workspace slug) Workspace-agnostic Linear deep link may not resolve — FIXED: added optional unvalidated `LinearProperties.workspaceSlug` (3a.4 append-at-END pattern), `LinearRealAdapter.buildSourceTicketUrl` now builds `https://linear.app/<slug>/issue/<identifier>` and returns `Optional.empty()` when no slug is configured; Javadoc corrected. [LinearRealAdapter.java / LinearProperties.java] NOTE: `WorkflowDetailResponse.java:151` `@Schema(example=...)` still shows the workspace-less form — left as a cosmetic follow-up to avoid an OpenAPI-snapshot regen cascade.
- [x] [Review][Patch] No end-to-end test asserts `title`/`url` are decoded from `external_metadata` — FIXED: added two unit tests in `IntegrationLinkServiceUnitTest` that stub the record port with real JSON bytes and assert the decoded `TicketOriginView.title()`/`url()` (and null parity for pre-3g metadata), exercising the real `parseExternalMetadata`/`metadataString` path. [IntegrationLinkServiceUnitTest.java]
- [x] [Review][Patch] `LinearMockAdapter.buildSourceTicketUrl` logged the raw `ref.value()` — FIXED: now wrapped in `MdcKeys.sanitizeForLog(...)`, matching `LinearRealAdapter`. [LinearMockAdapter.java]
- [x] [Review][Patch] Shared `encodeMetadata` warn message misattributed origin-read failures — FIXED: message generalized to caller-agnostic `encodeMetadata metadata_serialize_failed`. [IntegrationLinkPersistenceAdapter.java]
- [x] [Review][Patch] `findActiveTicketOriginView` parsed the metadata JSON twice per run — FIXED: parse once via `parseExternalMetadata`, then pull both keys via `metadataString`. [IntegrationLinkService.java]

## Dev Notes

### The real shape of this story (read before coding)

The originating title is **already snapshotted and redacted** into `integration_links.external_metadata` at link time — the epic's "fetched then discarded" framing is about the *read model*, not persistence. Verify this first (`IntegrationLinkService.buildExternalMetadata` writes the `title` key; `IntegrationLinkTicketSummaryProvider` reads it back). Your net-new persistence work is a single additive JSON key (`url`); the bulk of the story is **surfacing** the already-stored `title` (plus the new `url`) through two read models. This is deliberately the family's lightest, convention-setting story — the additive-DTO + exact-field-contract-test + nullable-read-model discipline you establish here is reused by 3h–3l.

### Source-tree components to touch (with line anchors, verify before editing)

- **Capabilities / port (Task 1–2):**
  - `domain/integration/ticketsource/TicketSourceCapabilities.java:19` — record (4 booleans today: `supportsCommentOnTicket`, `supportsPolling`, `supportsTicketStateUpdates`, `supportsTicketCreation`); `linearDefaults()` `:38`, `noCreation(...)` `:26`.
  - `application/integration/ticketsource/TicketSourceAdapter.java` — port; existing gated method precedent `createSubticket` `:83` (+ its "guarded by `supportsTicketCreation()`" Javadoc); `getCapabilities()` `:89`. ArchUnit rule doc at `:20`.
  - `adapters/integration/ticketsource/linear/LinearRealAdapter.java` (`@Profile("linear-real")`, `getCapabilities()` `:414`), `.../linear/LinearMockAdapter.java` (`@Profile("linear-mock")`, `getCapabilities()` `:209`), `.../gitlab/GitLabTicketSourceStubAdapter.java` (always-on `@Component`, `getCapabilities()` `:80`, throws `UnsupportedOperationException` for unsupported ops `:76`).
  - `domain/integration/ticketsource/Ticket.java` carries **no** URL field — do **not** add one; build the URL from the `TicketRef` in the adapter and thread it as a param (below), to avoid a `Ticket` record fan-out.
- **Snapshot write (Task 3):**
  - `application/integration/IntegrationLinkService.java` — `linkTicket` `:146` (@Transactional) and `linkTicketWithinTransaction` `:294` (`Propagation.MANDATORY`, the one the submit flow calls from `WorkflowCommandService:290`). Ticket is fetched through the already-in-hand `TicketSourceAdapter` (Javadoc `:61`; fetch ~`:332`). `buildExternalMetadata(Ticket)` `:836` (writes `title` `:838`, `summary`, `authorIdentity`, `labels`, `ticketCreatedAt`, `ticketUpdatedAt`). Redaction wrapper at `:234–236` and `:346–348`.
  - Persistence adapter `adapters/persistence/IntegrationLinkPersistenceAdapter.java`: `insert` `:85`, 64KB ceiling `EXTERNAL_METADATA_MAX_BYTES=65_535` `:56`, `decodeMetadata` `:359`. Metadata-bearing read projections `findActiveTicketSummaryByWorkflowRun` `:199` / `...ByTypeAndWorkflowRun` `:213` → `TicketSummaryProjection(externalRef, externalMetadata)`.
  - JPA entity `adapters/persistence/entity/IntegrationLinkEntity.java:54` — `external_metadata` is `@JdbcTypeCode(SqlTypes.JSON) Map<String,Object>`. **No DDL / Flyway needed** for a new JSON key.
- **Read models (Task 4):**
  - `application/workflow/WorkflowInspectionService.java`: `WorkflowRunSummaryView` `:2584` (built in `listRuns` `:1421`; `ticketRef` sourced `:1414` via `findActiveLinkByWorkflowRun(...).map(IntegrationLink::externalRef)`), `LinkedTicketView` `:2563` (`integrationType, externalRef, syncStatus`), `toLinkedTicket(IntegrationLink)` `:2415`, detail builder wiring `:1264`.
  - `adapters/rest/WorkflowSummaryResponse.java:13` (field order matters — append `ticketTitle` last per the "widen at END" comment `:59`; `from(...)` `:66`). `adapters/rest/WorkflowDetailResponse.java` — nested `LinkedTicket` `:133`, its `from(LinkedTicketView)` `:135`, outer wiring `:91`.
  - **Structural note:** `IntegrationLink` (domain, `application/integration/IntegrationLink.java`) does **not** carry `external_metadata`. To surface title/url, widen the active-link read to a metadata-bearing shape (reuse `TicketSummaryProjection`, or add a small projection carrying `integrationType, externalRef, syncStatus, title, url`). **Keep it to the one read `listRuns`/detail already perform per run — no second query.**
- **Contract test (Task 5):** `src/test/java/org/dradgo/adapters/rest/WorkflowReadEndpointsContractTest.java:185` — `containsExactlyInAnyOrder(...)` over the summary DTO `fieldNames()`.
- **OpenAPI / FE regen (Task 4):** snapshot `deliveryline-backend/src/main/resources/openapi/openapi.json`; drift test `OpenApiSnapshotContractTest.java` (write flag `-Dopenapi.snapshot.write=true`); FE types `deliveryline-frontend/src/lib/api/schema.d.ts`; `npm run generate-api` (package.json `:19`) then `check:api` (`:20`). **No FE component work here** — regen the types so 3g-2 can consume them.

### Anti-patterns to avoid (disaster prevention)

- **Do NOT** add a `workflow_runs` column or a Flyway migration — the origin lives in the existing `external_metadata` JSON map (AC1). (The next-free Flyway head `V31` is also contested by a stale `target/classes` artifact — irrelevant here since you add no migration, but do not "claim V31.")
- **Do NOT** re-implement the title write — it already exists and is already redacted. Only add the `url` key.
- **Do NOT** live-resolve the title/URL on read (calling the adapter from the read path). The origin is a **snapshot at creation** — read models read `external_metadata` only. This is the locked origin posture (offline-safe, immutable).
- **Do NOT** add a second per-run query in `listRuns` for the title — widen the existing active-link read. A naive `findActiveTicketSummaryByWorkflowRun` *in addition to* `findActiveLinkByWorkflowRun` doubles queue queries (N+1).
- **Do NOT** let a vendor type cross `TicketSourceAdapter` — return `Optional<String>`.
- **Do NOT** call `buildSourceTicketUrl` without checking `supportsSourceTicketUrl()` first (the false-capability adapters may throw or return empty; the caller contract mirrors `createSubticket`).
- **Do NOT** render/store the original ticket **body** or the initiating **prompt** — origin depth is locked to **title + ref + link only** (that surface is 3g-2; you only expose the fields).

### Testing standards summary

- Backend: JUnit 5 + Mockito + AssertJ (unit); Testcontainers `*IT` for real-Postgres persistence (name integration tests `*IT`, not `*Test`, or they leak into Windows Surefire). Adapter HTTP tests use `MockRestServiceServer` (see `LinearRealAdapter` tests). `application.*` JaCoCo gate ≥80%.
- Run `spotless:apply` before pushing Java; ArchUnit runs in **Failsafe** (a new `@ArchTest` is not exercised by `mvnw test` — verify via the failsafe/verify tier). SpotBugs `EI_EXPOSE` on any new record exposing a mutable field.
- **`runner-contracts` install trap does NOT apply to 3g-1** (no contract change here — that's 3g-3). But the OpenAPI snapshot + `schema.d.ts` **must** be regenerated together (the OpenAPI-regen → FE-client-drift cascade) or `check:api`/the snapshot test reds.
- Verify CI-affecting changes in a clean env / WSL2 Linux where Docker-backed ITs matter (local green ≠ CI green).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - `IntegrationLinkService` link path → `INFO` on entry/success (already present — extend to note whether a `url` was snapshotted, as a boolean/`present` flag, never the URL itself), `WARN` on conflict/adapter failure (already present).
  - The URL builder in each adapter → `DEBUG`/`INFO` "built source url for ticketRef={} present={}" — **never** the URL string.
  - Read-model population → no new log surface required (read path).
- **Required context keys:** `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `integrationLinkPublicId`.
- **Forbidden in log output:** full ticket title, full source URL, secrets/tokens, raw PII. Use `MdcKeys.sanitizeForLog(...)` for `ticketRef` at log sites (existing posture).
- **Test contract:** new logging surfaces pinned by at least one focused list-appender / `OutputCaptureExtension` test.

### Project Structure Notes

- Port lives in `application.integration.ticketsource`; adapters in `adapters.integration.ticketsource.{linear|gitlab}` (existing ArchUnit boundary — do not move). Domain records in `domain.integration.ticketsource`.
- REST DTOs in `adapters.rest`; read views in `application.workflow.WorkflowInspectionService` (nested records). No new packages.
- No new module, no new Maven dependency.

### References

- [Source: _bmad-output/planning-artifacts/epic-03g-provenance-token-accounting.md#Story 3g-1: Ticket-Origin Snapshot + Read Model]
- [Source: _bmad-output/planning-artifacts/epic-03g-provenance-token-accounting.md#Cross-Cutting Notes] (origin posture locked: title + ref + link-out only, snapshot-at-creation; summary exact-field trap; two OpenAPI regen points)
- [Source: _bmad-output/planning-artifacts/prd.md#FR73]
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-29-epics-3g-3l.md] (Epic 3g section)
- [Source: 3f-1-ticket-source-subticket-creation-capability.md] (precedent: capability-flag fan-out + gated port method + no-vendor-leak + false-capability caller pattern)
- Seams: `IntegrationLinkService.java:146,294,836`; `WorkflowInspectionService.java:2563,2584,2415,1264,1414`; `WorkflowSummaryResponse.java:13,59,66`; `WorkflowDetailResponse.java:91,133`; `TicketSourceCapabilities.java:19,38`; `TicketSourceAdapter.java:83,89`; `WorkflowReadEndpointsContractTest.java:185`; `IntegrationLinkPersistenceAdapter.java:56,199`.

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context) — bmad-dev-story workflow, 2026-07-02.

### Debug Log References

- Backend Surefire (full): 1450 run / 0 fail / 12 skipped.
- Failsafe (Docker/real-PG): `OpenApiSnapshotContractTest` + `WorkflowReadEndpointsContractTest` + `IntegrationLoggingContractTest` (18), `ArchitectureBoundaryTest` (57, incl. `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT`), `WorkflowCliJsonSchemaContractTest` (9), `TicketSourceAbstractionFoundationContract` (5, `-Pfoundation-gate`), `WorkflowInspectionRunnerQueueIT` (4, real Postgres — end-to-end validates the new `findActiveTicketOriginByWorkflowRun` projection). All green.
- OpenAPI snapshot regenerated via `integration-test -Dit.test=OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true` (contract-tagged → Failsafe tier), then re-verified without the write flag.
- FE: `npm run generate-api` + `check:api` green; `npm run build` (tsc+vite) exit 0.
- `spotless:apply` applied.

### Completion Notes List

- **Premise honored:** the origin `title` was already persisted/redacted into `external_metadata` — only the additive `url` key was net-new persistence. No new `WorkflowState`/`AllowedAction`/`WorkflowEventType`/`DomainErrorCode`/Flyway migration; no `workflow_runs` column.
- **URL design decision:** `buildSourceTicketUrl` is a pure (no-network) function of `TicketRef`. `LinearRealAdapter` builds the workspace-agnostic deep link `https://linear.app/issue/<identifier>` gated on the existing `TICKET_REF_PATTERN` (an unmatched ref → `Optional.empty()` satisfies the "unformable ref" case). No workspace slug is available from the ref or config (`LinearProperties.baseUrl` is the GraphQL endpoint), documented inline. Mock uses a distinct host (`https://linear.mock/issue/<ref>`) so a mock-sourced origin URL is never mistaken for a live link.
- **No N+1:** rather than a second per-run query, added `findActiveTicketOriginByWorkflowRun` on the record port (reusing the same `findActiveByWorkflowRunPublicIdLinearFirst` query as the existing summary read) carrying `integrationType + externalRef + syncStatus + externalMetadata` so BOTH the summary (`ticketTitle`) and detail (`LinkedTicket.title/url`) surfaces are served by ONE read per run. Pinned by a call-count assertion in `WorkflowInspectionServiceClarificationStatusTest`.
- **Mock-hygiene fix:** `resolveSourceTicketUrl` calls `linearAdapter.getCapabilities()`, which returns `null` on an unstubbed Mockito mock. Added default stubs in the two link-path unit test setups (`IntegrationLinkServiceUnitTest` `@BeforeEach`, `IntegrationLoggingContractTest` happy path) — plain mocks (no strict `MockitoExtension`), so unused defaults are harmless.
- `FakeTicketSource` (in `ProjectConnectorResolverTest`) implements the port, so it gained the new method.

### File List

**Backend — main (production):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketSourceCapabilities.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/LinearRealAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/LinearMockAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/gitlab/GitLabTicketSourceStubAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/spi/IntegrationLinkRecordPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/IntegrationLinkPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowSummaryResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowDetailResponse.java`
- `deliveryline-backend/src/main/resources/openapi/openapi.json` *(regenerated)*

**Backend — test:**
- `deliveryline-backend/src/test/java/org/dradgo/domain/integration/ticketsource/TicketSourceCapabilitiesTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/gitlab/GitLabStubAdaptersTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/ticketsource/linear/LinearMockAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/ticketsource/linear/LinearRealAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLinkServiceUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectConnectorResolverTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceClarificationStatusTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/WorkflowReadEndpointsContractTest.java`

**Frontend:**
- `deliveryline-frontend/src/lib/api/schema.d.ts` *(regenerated)*

## Change Log

| Date | Version | Description | Author |
| ---- | ------- | ----------- | ------ |
| 2026-07-02 | 0.1 | Implemented FR73 backend: capability-gated `TicketSourceAdapter.buildSourceTicketUrl` (Linear real/mock true, GitLab stub false), snapshotted the origin `url` into `integration_links.external_metadata` at link time (reusing the SHAREABLE_REDACTED pass), and surfaced `ticketTitle` (summary) + `LinkedTicket.title`/`url` (detail) by widening the single per-run active-link read (new `findActiveTicketOriginByWorkflowRun` projection — no N+1). Updated the summary exact-field contract test; regenerated OpenAPI snapshot + `schema.d.ts`. Status → review. | Amelia (Opus 4.8) |
