# Story 3a.5: Scheduled Linear Auto-Ingest — Poll-Driven Run Creation

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **Workflow Owner / operator**,
I want **the scheduled Linear poll (`LinearPollingHost`) to auto-create a governed run for each ticket whose Linear issue workflow-state ID is in a configured allow-list (e.g. the "Ready for Planning" state id) — via the same `WorkflowCommandService.submit` the CLI/REST use**,
so that **low-risk tickets enter the workflow with no CLI (turning the Epic-1 watcher into an opt-in auto-ingest intake) and feed 3a-1's spec auto-dispatch for a fully hands-off Linear-ticket → real-spec → review flow**.

## Context & current behavior to change

`LinearPollingHost.pollLinearInternal()` is a **watcher** today: for every polled ticket it calls `IntegrationLinkRecordPort.touchLastSyncAtByTypeAndExternalRef(...)` to refresh `last_sync_at` on **already-active** links and advances its watermark. Its own Javadoc (lines 32–35) states: _"The polling loop does **not** create new integration links — ingestion happens via CLI submit (story 1.15)."_ This story adds the **create path**: for eligible tickets with no existing active link, call `WorkflowCommandService.submit` (the exact command the CLI/REST already use), gated behind a default-off opt-in flag so the watcher behavior is **byte-identical when the flag is off**.

**Eligibility is the ticket's Linear issue workflow-state ID.** The operator configures an allow-list of issue workflow-state **IDs** (UUIDs, e.g. the id of `"Ready for Planning"`); only tickets currently in one of those states are ingested. This requires the poll to **fetch the issue state id (and name for logs)**, which it does not do today — see Task 1.

> **Linear status model (read before implementing).** DeliveryLine ingests Linear **issues** (`issues(filter: IssueFilter)`), whose status is the per-team **issue workflow state** (`issue.state { id, name, type }`, configured under **Team → Settings → Workflow**) — NOT the workspace-level **Project status** (`issue.project.status`). The five Linear issue-state **types** are `backlog / unstarted / started / completed / canceled`; the **name** is the operator's custom label (e.g. `"Ready for Planning"`, typically bucketed under `unstarted`); the **id** is a stable UUID. We gate on the **id** (UUID — robust against Linear-side renames; obtained once via the `workflowStates` GraphQL query) and **log the name** for readability. (Resolved 2026-06-07: gate on issue state, by id, not project status.) **Caveat:** workflow-state ids are **per-team** in Linear — the same-named status in two teams has two different ids; if polling more than one team, list every relevant id.

This is **upstream of story 3a-1**: a created run lands in `Inbox`; if `deliveryline.runner.spec-stage.auto-dispatch=true`, `submitInternal` already calls `WorkflowOrchestrationService.dispatchSpecGeneration` inside the same transaction, advancing `Inbox → Investigating` and dispatching the real spec runner. No new wiring to 3a-1 is required — submit already carries it.

## Acceptance Criteria

1. **Feature gate (default OFF, behavior-preserving).** A new opt-in flag `deliveryline.linear.auto-ingest.enabled` (default **`false`**) controls auto-ingest. When **`false`** (or absent), `pollLinearInternal()` behavior is **byte-identical to today** — touch-only watcher, no `submit` call, no new log lines, identical watermark advancement. The flag is **OPTIONAL + UNVALIDATED** (no bean-validation on the new property) so no `@SpringBootTest` context fails on a missing property and `src/test/resources/application.yml` needs **no** mirroring entry (per `[[validated-config-needs-test-yaml]]`).

2. **Eligibility = issue workflow-state ID allow-list (never the whole workspace).** When enabled, a ticket is **eligible for ingest only if** its Linear issue workflow-state id (`issue.state.id`) is in a configured allow-list `deliveryline.linear.auto-ingest.status-ids` (a list of workflow-state **UUIDs**, e.g. `["e7f3...-uuid"]`). Matching is **exact** on the id. Tickets whose state id is not in the list are **touched** (existing path) but **not** submitted. The eligibility check is applied **in addition to** the 3a-4 team/project poll scoping (which already bounds what the poll returns at the GraphQL layer — and which also bounds the per-team scope of the ids). **An enabled flag with an empty `status-ids` allow-list ingests NOTHING** (fail-safe — never auto-ingest every polled ticket) and logs a single WARN per poll batch that auto-ingest is enabled but no eligibility state ids are configured. A ticket whose state id is unknown/`null` (state absent from the response) is treated as ineligible.

3. **Idempotent submit, deterministic key.** For each eligible ticket with **no existing active integration link** (checked via `IntegrationLinkRecordPort.findActiveByTypeAndExternalRef("linear", ticketRef)`), build a `SubmitWorkflowCommand` with `actorType = SYSTEM`, a stable system `actorIdentity` (e.g. `"linear-auto-ingest"`), `correlationId = null` (see Trap T-FINGERPRINT-DRIFT), `linearTicketReference = ticketRef`, and a **deterministic idempotency key derived solely from the ticket identity** (so re-polls **and** JVM restarts cannot double-create — replay handled by 1.12). Call `workflowCommandService.submit(command)`. Tickets that **already have an active link** keep **touch-only** (AR18) and are **not** submitted.

4. **Failure isolation (per-ticket, watermark-safe).** A submit failure for one ticket (typed `DomainException` — e.g. `INTEGRATION_LINK_CONFLICT`, `LINEAR_TICKET_NOT_FOUND`, `IDEMPOTENCY_KEY_CONFLICT` — or any unexpected `RuntimeException`) logs **WARN** with the classified `DomainErrorCode` (or exception simple-name) and the `ticketRef`, and **does not** abort the batch, skip remaining tickets, or change the existing touch/cursor semantics. Auto-ingest is layered **on top of** the existing best-effort per-ticket touch loop; the watermark (`lastPollAt`) advances **exactly as it does today** (cursor preserved on touch-failure, advanced to `max(updatedAt)` otherwise). Auto-ingest outcomes do **not** influence cursor advancement.

5. **Boundary (drive intake via the application command port only).** `LinearPollingHost` (infrastructure) drives ingest through the application service `WorkflowCommandService` (command port) and the already-injected `IntegrationLinkRecordPort` (existing dependency, used for the active-link pre-check) — **no** orchestration/repo logic in the host (AR11), **no** import of `org.dradgo.adapters..` (`[[application-cannot-import-adapters]]` — the host stays at the composition seam, calling application beans only). No direct `LinearAdapter` create/link calls (linking stays owned by `IntegrationLinkService`, invoked transitively inside `submit`).

6. **Observability.** The existing `polling_batch` INFO line gains the auto-ingest counters `ingested={} skipped={} ineligible={}` (in addition to the existing `touched/skipped/touchFailures`); per-ingest INFO logs the created `runId` + resulting state + `ticketRef` + `statusName` + `statusId` + `actorType`; per-ineligible is DEBUG (high cardinality) carrying `ticketRef` + observed `statusName`/`statusId`. The Linear **API token is never logged**; `ticketRef`, `actorType`, `runId`, and the issue **status name + id** are non-secret and MAY be logged (3a-4 convention). When the flag is **off**, no new log lines appear (AC1).

7. **Scope posture (config + poll query + status fields + host + tests).** This adds the issue-state read needed to gate on it: a new `state { id name }` selection on the poll query (and the fetch query for parity), two new nullable fields on `LinearTicket` (`statusId` for gating, `status` name for logs) threaded through the real-adapter JSON mapper, a new config record, and the `LinearPollingHost` branch. Explicitly **NO**: Flyway migration, REST/OpenAPI/`schema.d.ts` change, new `DomainErrorCode`/`IntegrationFailureCategory`/registry value, change to `WorkflowCommandService`/`IntegrationLinkService` production code, or behavioral change to `LinearMockAdapter` intake (it gains the new fields on its constructed tickets only to satisfy the `LinearTicket` arity — fixtures may default them). The new `LinearTicket` fields are **nullable** and **additive** (appended at END of the record) to minimize the construction fan-out (5 sites — see Task 2).

8. **Tests (unit, Mockito + ListAppender).** `LinearPollingHostTest` (new) constructs the host with mocks (`LinearAdapter`, `IntegrationLinkRecordPort`, `UuidV7Generator`, `WorkflowCommandService`, the new properties, fixed `Clock`) and covers: (a) **eligible state id + no active link → submits once** with `actorType=SYSTEM`, the deterministic key, and `correlationId=null`; (b) **re-poll of the same eligible ticket → no second submit** (active-link pre-check now returns present → touch-only) **and** the deterministic key is stable across calls (assert the captured key equals the first); (c) **ineligible state id (not in allow-list) → touch-only, no submit**; (d) **flag disabled → byte-identical legacy path** (no submit, no new log lines, `verifyNoInteractions(workflowCommandService)`); (e) **one submit failure does not abort the batch** (a thrown `DomainException` on ticket 2 of 3 → tickets 1 and 3 still processed, WARN logged); (f) **watermark preserved/advanced** exactly as today regardless of ingest outcome; (g) **enabled + empty status-ids → ingests nothing + single WARN**; (h) **null/unknown state id → ineligible**. New log fields pinned with `ListAppender` assertions at the expected level. Plus: `LinearRealAdapterUnitTest` poll/fetch tests updated for the `state` mapping (assert `state.id`/`state.name` are parsed into `LinearTicket.statusId`/`status`); the new `LinearTicket` arity propagated to the 5 construction sites.

## Tasks / Subtasks

- [x] **Task 1 — Fetch issue state: poll query + adapter mapping** (AC: 2, 7)
  - [x] In `src/main/resources/graphql/linear/poll-tickets-since.graphql`, add a `state { id name }` selection to the issue node (alongside `labels`, `creator`). Keep the `$filter`/paging unchanged. Add the same `state { id name }` to `fetch-ticket-by-reference.graphql` for parity (so the new `LinearTicket` fields are populated on both read paths, not just poll).
  - [x] In `LinearRealAdapter`, in the shared JSON→`LinearTicket` mapper (~lines 443–445, the single `new LinearTicket(...)` site), extract both: `String statusId = textOrNull(issue.path("state").path("id"))` and `String statusName = textOrNull(issue.path("state").path("name"))` (`isMissingNode`-guarded → `null` when absent), and pass them as the new trailing args. (This one mapper feeds both poll and fetch.)
  - [x] Do NOT add a server-side `state` filter to the poll `IssueFilter` (eligibility is decided in the host so the watcher's polled set is unchanged when auto-ingest is off — AC1). Note the server-side `state: { id: { in: [...] } }` option as a future efficiency optimization in Dev Notes only.

- [x] **Task 2 — `LinearTicket`: add nullable `statusId` + `status` (name) fields** (AC: 7)
  - [x] Append `String status` (the state **name**, for logs) and `String statusId` (the state **id**, for gating) at the **END** of the `LinearTicket` record component list (after `labels`). Leave both **nullable** (no `requireNonNull`) so existing fixture/test construction can pass `null`; keep the existing `labels` defaulting logic. Add Javadoc: `statusId` = Linear issue workflow-state UUID (gating key, stable across renames); `status` = its display name (e.g. `"Ready for Planning"`), logs only. Both `null` when the source response omits `state`.
  - [x] Update all **5** `new LinearTicket(...)` construction sites for the two new trailing args (T-LINEARTICKET-FANOUT):
    - `adapters/integration/linear/LinearRealAdapter.java` (the mapper — Task 1 sets the real values).
    - `adapters/integration/linear/LinearTicketFixtureDocument.java` (mock fixture builder — pass fixture status/statusId if present, else `null`).
    - `test/.../application/integration/IntegrationLinkServiceUnitTest.java` (1 site).
    - `test/.../application/integration/IntegrationLoggingContractTest.java` (2 sites).
  - [x] If any test asserts full-`LinearTicket` equality, update the expected value for the new components.

- [x] **Task 3 — New opt-in config: `deliveryline.linear.auto-ingest.*`** (AC: 1, 2, 7)
  - [x] Create `application/integration/linear/LinearAutoIngestProperties.java`: a `@ConfigurationProperties("deliveryline.linear.auto-ingest")` record `LinearAutoIngestProperties(boolean enabled, java.util.List<String> statusIds)`. Compact constructor defaults `statusIds` to an **immutable empty list** when null (mirror `LinearProperties`' nested-record null-guard) and normalizes each entry (strip, drop blanks). **Do NOT** add bean validation — absence is the valid default (`enabled=false`, `statusIds=[]`), so the test `application.yml` needs no mirroring entry (T-VALIDATED-CONFIG / `[[validated-config-needs-test-yaml]]`). (Relaxed-binding: the YAML key `status-ids` maps to the `statusIds` component.)
  - [x] Register it in `infrastructure/config/LinearConfiguration.java` by extending the annotation to `@EnableConfigurationProperties({LinearProperties.class, LinearAutoIngestProperties.class})`.
  - [x] Add commented placeholders under `deliveryline.linear:` in `src/main/resources/application.yml`:
    ```yaml
    # Story 3a.5 — OPTIONAL opt-in auto-ingest. When enabled, the poll auto-creates a governed run
    # (via the same submit the CLI/REST use) for each ticket whose Linear ISSUE WORKFLOW-STATE ID
    # (issue.state.id, configured under Team → Settings → Workflow) is in `status-ids` AND that has no
    # existing active link. Get the ids via the `workflowStates` GraphQL query (UUIDs are per-team +
    # stable across renames). Default OFF ⇒ the poll stays a watcher. An enabled flag with an EMPTY
    # status-ids list ingests NOTHING (never the whole workspace).
    # auto-ingest:
    #   enabled: false
    #   status-ids: []      # e.g. ["e7f3...-uuid"] — Linear issue workflow-state IDs to ingest (e.g. "Ready for Planning")
    ```
  - [x] Add a commented `DELIVERYLINE_LINEAR_AUTOINGEST_ENABLED=` placeholder to `.env.example` mirroring the 3a-4 `DELIVERYLINE_LINEAR_TEAMKEY`/`PROJECTID` doc style (relaxed-binding: `auto-ingest.enabled` ← `DELIVERYLINE_LINEAR_AUTOINGEST_ENABLED`). (A list like `status-ids` is awkward via a single env var; document that it is set in YAML, or as indexed env entries `DELIVERYLINE_LINEAR_AUTOINGEST_STATUSIDS_0=...` if needed.)

- [x] **Task 4 — Thread the new deps into `LinearPollingHost`** (AC: 5, 7)
  - [x] Add `WorkflowCommandService workflowCommandService` and `LinearAutoIngestProperties autoIngestProperties` as fields. Inject through **both** constructors: the `@Autowired` public ctor (currently 3-arg) **and** the package-private `Clock`-overload ctor used by tests. **Update the `this(...)` delegation** so the public ctor passes the new deps down to the package-private one (T-CTOR-FANOUT — two ctors, one delegates; see `[[two-public-constructors-need-autowired]]` for why `@Autowired` must stay on the wiring ctor). `Objects.requireNonNull` both new deps.
  - [x] No new ArchUnit package; `LinearPollingHost` already lives in `infrastructure.config` and may depend on `application..` (only `application ↛ infrastructure` is forbidden — `application_must_not_depend_on_infrastructure`). Confirm **no** `org.dradgo.adapters..` import is introduced (AC5).

- [x] **Task 5 — Add the auto-ingest branch to `pollLinearInternal()`** (AC: 1, 2, 3, 4, 6)
  - [x] Keep the existing touch loop intact. Inside the per-ticket loop body (after the touch attempt, so a touch failure still preserves the cursor), when `autoIngestProperties.enabled()` is true, evaluate eligibility + active-link and conditionally submit. Suggested structure (counters declared alongside `touched/skipped/touchFailures`):
    ```java
    if (autoIngestProperties.enabled()) {
      if (!isEligible(ticket)) {           // state id not in allow-list (or null/empty list)
        ineligible++;
        log.debug("linear_real auto_ingest_ineligible ticketRef={} statusName={} statusId={}",
            ticket.ticketRef(), ticket.status(), ticket.statusId());
      } else if (integrationLinkRecordPort
          .findActiveByTypeAndExternalRef(INTEGRATION_TYPE_LINEAR, ticket.ticketRef())
          .isPresent()) {
        // AR18 — already linked (CLI/REST/prior auto-ingest); touch-only, do not re-submit.
        skipped++;                          // or a dedicated alreadyLinked counter
      } else {
        try {
          SubmitWorkflowResult result =
              workflowCommandService.submit(buildAutoIngestCommand(ticket));
          ingested++;
          log.info(
              "linear_real auto_ingest_created ticketRef={} statusName={} statusId={} runId={} state={} actorType={}",
              ticket.ticketRef(), ticket.status(), ticket.statusId(), result.workflowRunId(),
              result.currentState().value(), ActorType.SYSTEM.value());
        } catch (DomainException de) {
          log.warn("linear_real auto_ingest_failed ticketRef={} code={}",
              ticket.ticketRef(), de.errorCode().value());          // per-ticket isolation
        } catch (RuntimeException re) {
          log.warn("linear_real auto_ingest_failed ticketRef={} cause={}",
              ticket.ticketRef(), re.getClass().getSimpleName());
        }
      }
    }
    ```
    (`DomainException.errorCode()` returns the `DomainErrorCode`; `.value()` is the string code — confirmed against `domain/DomainException.java:33`.)
  - [x] `isEligible(LinearTicket)`: `enabled && !statusIds.isEmpty() && ticket.statusId() != null && statusIdAllowListSet.contains(ticket.statusId())`. Precompute the allow-list as a `Set<String>` once (ctor or per-batch) for O(1) membership. With an **empty** allow-list, eligibility is always false → nothing ingested; emit a single WARN per batch (AC2). (Gate on the **id**; the name is for logs only.)
  - [x] `buildAutoIngestCommand(LinearTicket)`: `new SubmitWorkflowCommand("linear-auto-ingest", ActorType.SYSTEM, autoIngestKey(ticket.ticketRef()), null, ticket.ticketRef())`. **`correlationId` MUST be `null`** (T-FINGERPRINT-DRIFT).
  - [x] `autoIngestKey(String ticketRef)`: deterministic, ticket-identity-only. e.g. `"ai-" + sha256Hex("linear-auto-ingest|" + ticketRef)` (3 + 64 hex = 67 chars; all chars satisfy `IdempotencyKeyValidator`'s opaque pattern `[A-Za-z0-9-]{16,128}`). Reuse the SHA-256 hex pattern already in `IntegrationLinkService.computeFingerprint` / `WorkflowCommandFingerprintFactory`. Same `ticketRef` ⇒ same key ⇒ a re-poll/restart maps to the same idempotency record (1.12 replay), preventing double-create. **Note:** the key is derived from `ticketRef` only (NOT status) — so a ticket re-ingested after a status change still maps to the same key, which is correct (one run per ticket).
  - [x] Extend the `polling_batch` INFO line with `ingested={} skipped={} ineligible={}` (keep all existing fields/positions; only **append**).

- [x] **Task 6 — Tests: `LinearPollingHostTest` (new, Mockito)** (AC: 8)
  - [x] New `src/test/java/org/dradgo/infrastructure/config/LinearPollingHostTest.java`. Construct the host via the package-private `Clock` ctor with mocked deps and a fixed `Clock`. Configure `status-ids = ["state-ready-uuid"]` and build `LinearTicket` fixtures with eligible (`statusId="state-ready-uuid"`, `status="Ready for Planning"`), ineligible (`statusId="state-backlog-uuid"`), and null-statusId variants.
  - [x] Cover AC8 (a)–(h). Use `ArgumentCaptor<SubmitWorkflowCommand>`; assert `actorType()==SYSTEM`, `correlationId()==null`, `linearTicketReference()==ticketRef`, and that the captured `idempotencyKey()` is identical across two poll cycles. For disabled-flag: `verifyNoInteractions(workflowCommandService)` + no `auto_ingest_*` log line. For failure isolation: `thenThrow(new DomainException(INTEGRATION_LINK_CONFLICT, ...))` on the 2nd ticket; verify 1st+3rd still submit. For active-link short-circuit: `findActiveByTypeAndExternalRef` returns present ⇒ `verify(workflowCommandService, never()).submit(any())`, touch still happens. Pin new log fields with `ListAppender` (pattern: `LinearRealAdapterUnitTest` lines 536–550).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J parameterized logs only (no concatenation). INFO for `auto_ingest_created` (per ingest) + the extended `polling_batch` summary; WARN for `auto_ingest_failed` (per-ticket isolation) and the enabled-but-no-status-ids case; DEBUG for `auto_ingest_ineligible` (high cardinality).
  - [x] Carry non-secret context: `ticketRef`, `statusName`, `statusId`, `runId`, `state`, `actorType`, `code`, batch counters. The poll already runs under a generated `correlationId` MDC scope (`pollLinear()` lines 129–135) — auto-ingest logs inherit it. **Never** log `apiToken`, ticket payload bodies, or PII.
  - [x] Pin the new log fields with at least one `ListAppender`/`OutputCaptureExtension` assertion (covered by Task 6).

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback; parameterized; no `System.out`/`printStackTrace`.
- **Where to log:** per-ingest INFO (created run + status name/id), per-failure WARN (classified `DomainErrorCode`), batch-summary INFO (counters), enabled-but-misconfigured WARN, per-ineligible DEBUG. Existing watcher logs (`polling_batch`, `polling_touch_failed`, `polling_cursor_preserved`, `polling_failed`) stay **unchanged** except for the appended counters on `polling_batch`.
- **Required context keys** (via the existing MDC `correlationId` scope + structured params): `ticketRef`, `statusName`, `statusId`, `runId`, `state`, `actorType`, `code`, `ingested`/`skipped`/`ineligible`.
- **Forbidden:** `apiToken`, ticket payload bodies, PII. (Status names/ids and labels are non-secret config-matched identifiers and are safe to log.)
- **Test contract:** new logging surfaces pinned by `ListAppender` so refactors can't silently delete them.

## Dev Notes

### The change in one sentence

Add an **opt-in create path** to the existing Linear poll watcher: fetch each issue's state id+name, and when `deliveryline.linear.auto-ingest.enabled=true`, for each polled ticket whose `issue.state.id` is in the configured allow-list AND that has no active link, call the **existing** `WorkflowCommandService.submit` with a `SYSTEM` actor and a **ticket-deterministic idempotency key**; everything else (touch, watermark, mock intake) is untouched.

### Linear status: issue state by ID, not project status (decided)

DeliveryLine polls **issues**. The gate is the **issue workflow-state id** (`issue.state.id`, a UUID) — configured per team under **Team → Settings → Workflow**, NOT the workspace **Project status** the operator first looked at. We gate on the **id** (not the name) so a Linear-side rename of "Ready for Planning" does not silently break ingestion (decided 2026-06-07). The poll currently fetches no state at all (`poll-tickets-since.graphql` selects only `identifier/title/description/createdAt/updatedAt/creator/labels`), so Task 1 adds `state { id name }` and Task 2 carries `statusId` (gating) + `status` name (logs) on `LinearTicket`. The five Linear issue-state **types** (`backlog/unstarted/started/completed/canceled`) are NOT what we match — we match the configured **id** set. **Getting the ids:** run the `workflowStates(first:250){ nodes{ id name type team{ key } } }` GraphQL query against `https://api.linear.app/graphql` with the API token in the raw `Authorization` header (personal API key — no `Bearer ` prefix, matching `LinearConfiguration`'s interceptor). **Ids are per-team** — if you ever poll more than one team, list every relevant id. If coarse type-based safety is ever wanted (e.g. "never ingest completed/canceled"), the fetched `state.type` is available for a guard — out of scope here.

### Why this rides existing plumbing — REUSE, do not rebuild

| Capability | Location | Use |
|---|---|---|
| Poll loop + watermark + per-ticket touch | `infrastructure/config/LinearPollingHost.java` (`pollLinearInternal`, lines 138–200) | Add the auto-ingest branch **inside** the existing per-ticket loop; leave touch/cursor logic intact. |
| The JSON→`LinearTicket` mapper | `adapters/integration/linear/LinearRealAdapter.java:443-445` | One mapper feeds poll+fetch; extract `state.id` + `state.name` here. |
| The submit command path (run create + initial event + Linear link + 3a-1 auto-dispatch, all in one tx) | `application/workflow/WorkflowCommandService.submit` (124–127, `submitInternal` 163–238) | Call it verbatim. |
| The command record | `application/workflow/commands/SubmitWorkflowCommand.java` | `(actorIdentity, actorType, idempotencyKey, correlationId, linearTicketReference)`. |
| Idempotency replay (deterministic key ⇒ replay, not double-create) | `application/idempotency/IdempotencyService.checkAndReserve` + `IdempotencyKeyValidator` (opaque key `[A-Za-z0-9-]{16,128}`) | Deterministic per-ticket key ⇒ COMPLETED replay returns the existing run, no side effects. |
| Active-link pre-check | `IntegrationLinkRecordPort.findActiveByTypeAndExternalRef("linear", ref)` (already injected) | Skip submit for already-linked tickets (AR18). |
| 3a-4 team/project poll scoping | `LinearRealAdapter.buildPollFilter` GraphQL `$filter` | Already bounds the polled set (and the per-team scope of the state ids); the status-id allow-list narrows further in-Java. |
| Config registration | `infrastructure/config/LinearConfiguration` (`@EnableConfigurationProperties`) | Register the new `LinearAutoIngestProperties`. |
| ListAppender log-test harness | `LinearRealAdapterUnitTest` lines 536–550 | Copy for the new host test. |

### Idempotency: two layers, and why both matter

1. **Active-link pre-check (cheap, steady-state):** once auto-ingest creates a run+link for a ticket, the *next* poll sees an active link and falls to touch-only — `submit` is never re-called for that ticket. CLI/REST-created links also suppress auto-ingest of the same ticket (AR18).
2. **Deterministic idempotency key (correctness backstop):** for the narrow race where two poll cycles overlap, the link isn't yet visible, or two app instances poll the same workspace, the deterministic key (keyed on `ticketRef` only) makes a second `submit` a **REPLAY** rather than a duplicate. `submit`'s fingerprint is `(commandType, actorIdentity, actorType, correlationId, linearTicketReference)` (`WorkflowCommandFingerprintFactory` 19–26) — all stable for a fixed ticket **iff** `correlationId` is stable (hence Trap T-FINGERPRINT-DRIFT). Status is deliberately NOT in the key/fingerprint, so a status change does not mint a second run.

### Transaction & failure semantics

`pollLinearInternal()` is **not** `@Transactional`; each `workflowCommandService.submit(...)` opens its **own** transaction (Spring proxy on the application bean). Per-ticket submits commit/roll back independently — the failure isolation AC4 wants. A failed submit rolls back its run+link, the per-ticket `catch` logs WARN, the loop continues. The watermark advances on the **touch** outcome only (today's logic), independent of ingest results.

### Known limitation — deterministic-key + terminal failure (document, don't fix)

Because the key is deterministic and never regenerated, a submit that **fails terminally** (idempotency record flips to `FAILED`) **poisons** that ticket's key: subsequent re-polls that find no link re-attempt submit, hit `IdempotencyService` → `priorAttemptFailed` → `IDEMPOTENCY_KEY_CONFLICT`, and log WARN without ingesting. In steady state this is rare (fail-and-leave-no-link paths: ticket deleted between poll and in-tx fetch → `LINEAR_TICKET_NOT_FOUND`; an uncaught cross-run link race → `INTEGRATION_LINK_CONFLICT`; a transient adapter error during the in-tx fetch). This is the **accepted trade-off** of the proposal's "deterministic key" requirement — operators fall back to CLI submit (fresh key) for a poisoned ticket. Per-ticket failure isolation keeps it from affecting others. Optionally log the prior-FAILED conflict at a calmer level. Note in Completion Notes / `deferred-work.md`.

### Traps (do NOT step on these)

- **T-FINGERPRINT-DRIFT — `correlationId` MUST be `null` in the command.** `WorkflowCommandFingerprintFactory` hashes `correlationId`. Passing the per-poll generated MDC `correlationId` changes the fingerprint every poll → on re-poll `IdempotencyService` sees the same key with a **different fingerprint** → `IDEMPOTENCY_KEY_CONFLICT` (`IdempotencyService` 74–76). Pass `null`; the per-poll correlation still rides the MDC scope for log correlation.
- **T-LINEARTICKET-FANOUT — adding record components.** `String status` + `String statusId` appended to `LinearTicket` break every `new LinearTicket(...)` (5 sites: real-adapter mapper, `LinearTicketFixtureDocument`, `IntegrationLinkServiceUnitTest`, `IntegrationLoggingContractTest` ×2). Append at END + update all 5 (`[[docker-adapter-ctor-dep-fans-out]]` pattern). Keep both nullable so non-real paths can pass `null`.
- **T-STATUS-NULL — state may be absent.** Map a missing `state`/`id`/`name` node to `null` (not `""`), and treat `null` `statusId` as ineligible. Never NPE on `ticket.statusId()`/`ticket.status()`.
- **T-CTOR-FANOUT — two constructors.** `LinearPollingHost` has a public `@Autowired` ctor delegating to a package-private `Clock`-overload ctor (tests). Add the new deps to **both**, keep `@Autowired` on the wiring ctor (`[[two-public-constructors-need-autowired]]`).
- **T-VALIDATED-CONFIG — keep `LinearAutoIngestProperties` unvalidated.** A validated field forces a matching entry in `src/test/resources/application.yml` (it shadows, not merges) or the `@SpringBootTest` tier fails at startup (`[[validated-config-needs-test-yaml]]`). Defaults (`enabled=false`, `statusIds=[]`) bind cleanly when absent.
- **T-DEFAULT-OFF-BYTE-IDENTICAL — flag off ⇒ no behavioral/log delta.** Gate the whole branch on `enabled()`; when off, emit no `auto_ingest_*` lines. The poll query now also selects `state { id name }` for the watcher — harmless (extra unused fields); confirm existing watcher contracts (`IntegrationLoggingContractTest`, `IntegrationProfileWiringContractTest`) and `LinearRealAdapterUnitTest` body-matchers (they assert `variables.filter.*`, not the selection set) stay green.
- **T-NO-WHOLE-WORKSPACE — empty allow-list ingests nothing.** `enabled=true` + `statusIds=[]` must NOT ingest every polled ticket; eligibility is false on an empty list; WARN once per batch.
- **T-AR18-EXISTING-LINK — already-linked ⇒ touch-only.** Always run the active-link pre-check before submit so CLI/REST/prior-auto-ingest links suppress re-submit; otherwise every poll throws `INTEGRATION_LINK_CONFLICT` for linked tickets.
- **T-STATUS-ID-PER-TEAM — ids are team-scoped, and must be obtained from the API.** The allow-list matches `issue.state.id` (UUID) exactly. Ids are **per-team** (the same status name in another team has a different id) and are not shown in the Linear UI — get them via the `workflowStates` GraphQL query. Gating on id (not name) is deliberate so a Linear rename does not break ingestion; the trade-off is opacity (mitigated by logging `statusName` alongside `statusId`). A wrong/stale id silently stops ingestion — the per-batch `ineligible` counter + DEBUG line (`statusName`/`statusId`) make it diagnosable.
- **T-BOUNDARY — application beans only.** Drive ingest through `WorkflowCommandService` (+ the already-injected `IntegrationLinkRecordPort`). No `adapters..` imports, no direct `LinearAdapter`/link creation in the host (AR11, `[[application-cannot-import-adapters]]`).
- **T-GATES — run gates via PowerShell, not Bash** (`[[rtk-hook-only-matches-bash]]`). Unit-tested change (Mockito host test, no Docker): `mvnw -pl deliveryline-backend test` (fast tier) + `spotless:check` + `checkstyle:check`. No Testcontainers/Docker tier needed.

### Validation / scope posture

- **No** Flyway (max V11 unchanged). **No** REST/OpenAPI/`schema.d.ts`. **No** new `DomainErrorCode`/`IntegrationFailureCategory`/registry value (`new-domainerrorcode-three-sites` does not apply). **No** ArchUnit-relevant new package. Surface: 2 GraphQL resources (+`state{id name}`), `LinearTicket` (+nullable `status` + `statusId`) + its 5 ctor sites, the real-adapter mapper, a new properties record, a `@EnableConfigurationProperties` edit, the `LinearPollingHost` branch + ctor threading, the new host test, and config docs.

### Project Structure Notes

```
deliveryline-backend/src/
├── main/resources/graphql/linear/
│   ├── poll-tickets-since.graphql                      (MODIFIED — + state { id name })
│   └── fetch-ticket-by-reference.graphql               (MODIFIED — + state { id name }, parity)
├── main/java/org/dradgo/application/integration/linear/
│   ├── LinearTicket.java                               (MODIFIED — + nullable String status + String statusId, END)
│   └── LinearAutoIngestProperties.java                 (NEW — @ConfigurationProperties("deliveryline.linear.auto-ingest"))
├── main/java/org/dradgo/adapters/integration/linear/
│   ├── LinearRealAdapter.java                          (MODIFIED — map state.id + state.name into LinearTicket)
│   └── LinearTicketFixtureDocument.java                (MODIFIED — pass status/statusId args, fixture value or null)
├── main/java/org/dradgo/infrastructure/config/
│   ├── LinearConfiguration.java                        (MODIFIED — @EnableConfigurationProperties += LinearAutoIngestProperties)
│   └── LinearPollingHost.java                          (MODIFIED — +2 ctor deps, auto-ingest branch, extended batch log)
├── main/resources/application.yml                      (MODIFIED — commented auto-ingest.enabled/status-ids placeholders)
└── test/java/org/dradgo/
    ├── infrastructure/config/LinearPollingHostTest.java        (NEW — Mockito + ListAppender, AC8 a–h)
    ├── adapters/integration/linear/LinearRealAdapterUnitTest.java (MODIFIED — status mapping + arity)
    └── application/integration/{IntegrationLinkServiceUnitTest,IntegrationLoggingContractTest}.java (MODIFIED — LinearTicket arity)
.env.example                                            (MODIFIED — commented DELIVERYLINE_LINEAR_AUTOINGEST_ENABLED)
```

### References

- [Source: deliveryline-backend/src/main/resources/graphql/linear/poll-tickets-since.graphql] — poll selection to extend with `state { id name }`.
- [Source: deliveryline-backend/src/main/resources/graphql/linear/fetch-ticket-by-reference.graphql] — fetch selection (parity).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearRealAdapter.java:443-481] — the JSON→`LinearTicket` mapper + `extractLabels` parallel to mirror for state id/name.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearTicket.java] — record to extend (+nullable `status` + `statusId`); 5 construction sites fan out.
- [Reference: Linear GraphQL API — `workflowStates(first:N){ nodes{ id name type team{ key } } }` against https://api.linear.app/graphql, raw `Authorization` header for a personal API key] — how the operator obtains the workflow-state ids for the `status-ids` allow-list.
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearPollingHost.java:24-55,138-200] — watcher Javadoc + per-ticket touch loop/watermark to extend.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:124-238] — `submit` / `submitInternal`: run create + event + `linkTicketWithinTransaction` + 3a-1 `dispatchSpecGeneration`, one tx.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/SubmitWorkflowCommand.java] — command record + constraints.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/idempotency/IdempotencyService.java:34-90] — reservation/replay + fingerprint-mismatch → `IDEMPOTENCY_KEY_CONFLICT` (T-FINGERPRINT-DRIFT).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/idempotency/IdempotencyKeyValidator.java:14-39] — opaque key rule the deterministic key must satisfy.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/idempotency/WorkflowCommandFingerprintFactory.java:19-26] — submit fingerprint fields.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/spi/IntegrationLinkRecordPort.java:33-84] — `findActiveByTypeAndExternalRef` (active-link pre-check) + `touchLastSyncAtByTypeAndExternalRef` (existing).
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearConfiguration.java:29-31] — `@EnableConfigurationProperties(LinearProperties.class)` to extend.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java:98-160] — 3a-1 `dispatchSpecGeneration` gated by `deliveryline.runner.spec-stage.auto-dispatch` (downstream consumer).
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-07.md §"New Story 3a-5"] — originating change proposal (AC-shape, deps, AR8/AR18).
- [Source: _bmad-output/implementation-artifacts/3a-4-linear-intake-workspace-team-scoping.md] — prior story: poll scoping, unvalidated-optional config, ListAppender test, `[[rtk-hook-only-matches-bash]]` gate posture, T-CTOR-FANOUT precedent.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/integration/linear/LinearRealAdapterUnitTest.java:536-550] — ListAppender harness to copy.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story; bmad-dev-story

### Debug Log References

- Gates run via PowerShell (RTK hook only corrupts the Bash tool — `[[rtk-hook-only-matches-bash]]`), all offline (`-o`):
  - Targeted: `mvnw -pl deliveryline-backend test -Dtest=LinearPollingHostTest,LinearRealAdapterUnitTest,IntegrationLoggingContractTest,IntegrationLinkServiceUnitTest` → **45/0/0** (LinearPollingHostTest 10, LinearRealAdapterUnitTest 19, IntegrationLinkServiceUnitTest 11, IntegrationLoggingContractTest 5).
  - Full fast tier: `mvnw -pl deliveryline-backend test` → **807/0/0, 11 skipped** (no regressions; baseline ~781 + new host tests).
  - `mvnw -pl deliveryline-backend spotless:check checkstyle:check` → spotless 503 files clean, **0 Checkstyle violations** (one `spotless:apply` run to wrap the new Javadoc/log lines).
  - No Docker/Testcontainers tier — this is a Mockito unit-tested change (T-GATES); no schema/Flyway/REST/OpenAPI touched.

### Completion Notes List

- **Implemented the opt-in auto-ingest create path** on `LinearPollingHost` exactly per the AC matrix. When `deliveryline.linear.auto-ingest.enabled=true`, the per-ticket loop (after the existing touch attempt) evaluates eligibility on `issue.state.id` against the configured allow-list, runs the active-link pre-check (`findActiveByTypeAndExternalRef`), and calls the **existing** `WorkflowCommandService.submit` with `actorType=SYSTEM`, `actorIdentity="linear-auto-ingest"`, `correlationId=null` (T-FINGERPRINT-DRIFT), and a deterministic `"ai-"+sha256Hex("linear-auto-ingest|"+ticketRef)` idempotency key.
- **AC1 byte-identical-when-off resolved by branching the `polling_batch` log:** the extended `ingested/skipped/ineligible` counters are only appended when the flag is on; when off the batch line is the verbatim Epic-1 shape and no `auto_ingest_*` lines are emitted (verified by `disabledFlagKeepsLegacyWatcherPath` asserting `verifyNoInteractions(commandService)` + no `auto_ingest`/`ingested=` substrings). This is a slight, deliberate divergence from AC6's literal "the line gains the counters" to honor AC1's stronger byte-identical guarantee.
- **AC2 fail-safe:** `enabled=true` + empty `status-ids` ingests nothing and emits exactly one WARN (`auto_ingest_no_status_ids`) per batch.
- **AC4 failure isolation:** `DomainException` → WARN with `code={errorCode.value()}`; any other `RuntimeException` → WARN with `cause={simpleName}`; the batch never aborts. `catch (DomainException)` precedes `catch (RuntimeException)` since `DomainException extends RuntimeException`.
- **Config (`LinearAutoIngestProperties`)** is OPTIONAL + UNVALIDATED with an immutable-empty-list default + per-entry strip/blank-drop (mirrors `LinearProperties`); registered via `@EnableConfigurationProperties({LinearProperties.class, LinearAutoIngestProperties.class})`. No test `application.yml` mirror needed (`[[validated-config-needs-test-yaml]]`).
- **T-CTOR-FANOUT:** both `LinearPollingHost` constructors took the 2 new deps; `@Autowired` stays on the wiring ctor which delegates to the package-private `Clock`-overload (`[[two-public-constructors-need-autowired]]`). The one external construction site (`IntegrationLoggingContractTest`, 2 occurrences) updated with a `mock(WorkflowCommandService.class)` + `LinearAutoIngestProperties.defaults()`.
- **T-LINEARTICKET-FANOUT:** `status` + `statusId` appended at END of `LinearTicket` (both nullable); all 5 ctor sites updated (real-adapter mapper, `LinearTicketFixtureDocument`, `IntegrationLinkServiceUnitTest`, `IntegrationLoggingContractTest` ×2). `LinearRealAdapter.toLinearTicket` maps `state.id`/`state.name` via a new `textOrNull` helper (missing/null/blank → `null`, never NPE).
- **Boundary (AC5):** no `org.dradgo.adapters..` import added to the host; ingest is driven through `WorkflowCommandService` + the already-injected `IntegrationLinkRecordPort` only. `IntegrationProfileWiringContractTest` stays green because it gates `LinearPollingHost` off via `polling.enabled=false`, so the new ctor dep is never resolved there.
- **Known limitation (documented, not fixed):** the deterministic key poisons a ticket whose submit fails terminally (idempotency record → FAILED) — subsequent re-polls with no link hit `IDEMPOTENCY_KEY_CONFLICT` and WARN without ingesting; operators fall back to CLI submit (fresh key). This is the accepted trade-off of the proposal's deterministic-key requirement. Rare in steady state; per-ticket isolation contains it.
- **Recommend** `bmad-code-review` with a different LLM; the poll change is exercised at the real GraphQL layer only through the unit MockRestServiceServer tests — a WSL2/Linux fast-tier re-run + (optionally) a live-workspace smoke of the new `state { id name }` selection are advisable before pilot (`[[wsl-linux-ci-reproduction]]`, `[[verify-ci-fixes-in-clean-env]]`).

### File List

**Modified — production:**
- `deliveryline-backend/src/main/resources/graphql/linear/poll-tickets-since.graphql` — `+ state { id name }`
- `deliveryline-backend/src/main/resources/graphql/linear/fetch-ticket-by-reference.graphql` — `+ state { id name }` (parity)
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearTicket.java` — `+ nullable String status + String statusId` (END) + Javadoc
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearRealAdapter.java` — map `state.id`/`state.name` into `LinearTicket` + `textOrNull` helper
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearTicketFixtureDocument.java` — optional `status`/`statusId` JSON fields + arity
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearConfiguration.java` — `@EnableConfigurationProperties += LinearAutoIngestProperties`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearPollingHost.java` — 2 new ctor deps + precomputed allow-list `Set`, auto-ingest branch in `pollLinearInternal`, branched `polling_batch` log, `isEligible`/`buildAutoIngestCommand`/`autoIngestKey`/`sha256Hex` helpers
- `deliveryline-backend/src/main/resources/application.yml` — commented `auto-ingest.enabled`/`status-ids` placeholders
- `.env.example` — commented `DELIVERYLINE_LINEAR_AUTOINGEST_ENABLED` placeholder

**New — production:**
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearAutoIngestProperties.java` — `@ConfigurationProperties("deliveryline.linear.auto-ingest")` record (unvalidated)

**New — test:**
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/config/LinearPollingHostTest.java` — Mockito + ListAppender, AC8 (a)–(h)

**Modified — test:**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/linear/LinearRealAdapterUnitTest.java` — `state` mapping assertions on poll + fetch (+ `assertNull` import)
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLoggingContractTest.java` — `LinearTicket` arity (2 sites) + `LinearPollingHost` 6-arg ctor (2 sites) + imports
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLinkServiceUnitTest.java` — `LinearTicket` arity (1 site)

## Change Log

| Date | Change |
|---|---|
| 2026-06-08 | bmad-dev-story `ready-for-dev → in-progress → review`: implemented opt-in Linear auto-ingest (8 ACs + Logging). Poll now fetches `issue.state{id name}`; `LinearPollingHost` auto-creates a governed run via existing `WorkflowCommandService.submit` for eligible, unlinked tickets behind the default-off `deliveryline.linear.auto-ingest.enabled` flag. Gates green via PowerShell: fast tier 807/0/0/11skip, spotless clean, checkstyle 0. No Flyway/REST/OpenAPI/schema/DomainErrorCode. |

### Review Findings

_bmad-code-review 2026-06-08 — 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor). Triage: 0 decision-needed, 2 patch, 4 defer, 5 dismissed. Acceptance Auditor verified all 8 ACs Met; the HIGH below is an AC4 failure-isolation gap the Auditor missed and the Edge Case Hunter caught (confirmed against source). **Both patches FIXED 2026-06-08** — verified via PowerShell ([[rtk-hook-only-matches-bash]]): targeted tests `LinearPollingHostTest,LinearRealAdapterUnitTest,IntegrationLoggingContractTest,IntegrationLinkServiceUnitTest` 45/0/0, spotless clean (one `spotless:apply` to reflow the new comment), checkstyle 0 violations. `review → done`._

- [x] [Review][Patch] **(HIGH) Active-link pre-check `findActiveByTypeAndExternalRef` is outside the per-ticket failure-isolation boundary — a transient DB exception there aborts the whole batch (AC4 violation)** [`deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearPollingHost.java:251-253`] — **FIXED:** the eligible branch now opens a single `try` that wraps BOTH the active-link pre-check AND the `submit`; the existing `catch (DomainException)` / `catch (RuntimeException)` now isolate a pre-check read failure too (logged `auto_ingest_failed`, batch continues, watermark unaffected). The touch call (`:220-239`) and `submit()` were already wrapped; this closed the one DB-backed read left outside the boundary, contradicting AC4 and the code's own comment at `:242-243`.
- [x] [Review][Patch] **(LOW) Duplicate `skipped={}` key in the enabled-branch `polling_batch` log line** [`deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearPollingHost.java:287`] — **FIXED:** the second `skipped={}` (the AR18 already-linked counter) renamed to `alreadyLinkedSkipped={}`, eliminating the structured-log key collision. Minor, intentional deviation from AC6's literal `skipped={}` text — AC6's observability intent is preserved and the duplicate key was objectively wrong for log parsing.
- [x] [Review][Defer] **Submit / pre-check failures are absent from the batch-summary counters (no `failed` bucket)** [`LinearPollingHost.java:269-297`] — deferred, AC6 does not mandate a failure counter; per-ticket WARN logs already record failures. `ingested + autoIngestSkipped + ineligible` therefore under-counts the eligible-and-attempted population.
- [x] [Review][Defer] **`auto_ingest_no_status_ids` WARN repeats every poll interval on a stuck cursor** [`LinearPollingHost.java:202-209`] — deferred, spec-compliant ("single WARN per poll batch"); on a touch-failure-preserved cursor the same window re-polls indefinitely → unbounded WARN volume for a persistently-misconfigured + flaky deployment.
- [x] [Review][Defer] **Partial Linear state (`name` present, `id` missing/blank) → ticket silently ineligible, diagnosable only at DEBUG** [`LinearRealAdapter.java` state mapping + `LinearPollingHost.isEligible:326-331`] — deferred, rare partial-response edge; a genuinely-active ticket whose `id` was dropped is indistinguishable at INFO/WARN from a backlog ticket and is never ingested. The DEBUG `auto_ingest_ineligible` line does carry both fields.
- [x] [Review][Defer] **Review-diff scope hygiene: shared `application.yml` / `.env.example` carry unrelated working-tree drift** [`deliveryline-backend/src/main/resources/application.yml`, `.env.example`] — deferred, commit-hygiene caveat (not a code defect). The 3a-5 review diff swept in non-3a-5 uncommitted changes (`frontend.fail-on-missing-bundle`, `integration.repos` rewording, `linear.api-token`); ensure the eventual 3a-5 commit stages only the auto-ingest placeholder lines, not the other drift.
