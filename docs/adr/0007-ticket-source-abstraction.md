# ADR 0007 — TicketSourceAdapter Abstraction (Extract from LinearAdapter)

**Status:** Accepted (2026-06-17)
**Driver:** Story 3.32 — extract a vendor-neutral `TicketSourceAdapter` port from the Linear-shaped `LinearAdapter` (story 1.14) so a future ticket source (JIRA, GitHub Issues, GitLab Issues, Asana) is a one-interface, one-contract add rather than a Linear-shaped-port refactor. Prerequisite-sibling of story 3.33 (`RepositoryHostAdapter` from `GitHubAdapter`, ADR 0008) and a blocker for Epic 3c per-project connector resolution (3c-3).

## Context

The Epic-1 ticket integration was built Linear-first: a single `LinearAdapter` port with `LinearTicket`/`GovernedRunComment`/`LinearAdapterException` types, two profile-activated implementations (`LinearMockAdapter`/`LinearRealAdapter`), and consumers (`IntegrationLinkService`, `WorkflowOrchestrationService`, `LinearPollingHost`) typed against the Linear port. Adding a second ticket source would have meant refactoring a Linear-shaped port in place — exactly the churn Epic 3c wants to avoid.

The acceptance criteria as written in `epic-03-agent-execution.md §Story 3.32` describe an *idealized* greenfield port. The **live contract is authoritative** and diverges in load-bearing ways that this ADR records as decisions.

## Decision

**1. The port becomes vendor-neutral.** `LinearAdapter` → `application.integration.ticketsource.TicketSourceAdapter`; `LinearTicket` → `domain.integration.ticketsource.Ticket`; `GovernedRunComment` → `domain.integration.ticketsource.GovernedRunComment`; `LinearAdapterException` → `application.integration.ticketsource.TicketSourceAdapterException`. New neutral types `TicketRef`, `CommentResult`, `TicketSourceCapabilities` live in `domain.integration.ticketsource`. `LinearMockAdapter`/`LinearRealAdapter` move to `adapters.integration.ticketsource.linear` and implement the neutral port. Behavior is preserved byte-for-byte (parity tests prove it).

**2. `sourceStatusId` is an opaque, nullable vendor token (OQ-1).** `LinearTicket.statusId` is the Linear GraphQL workflow-state UUID and the auto-ingest gating key read by `LinearPollingHost` + `LinearAutoIngestProperties` (story 3a.5). AC4 forbids "GraphQL IDs" on the neutral type. The neutral `Ticket` carries a nullable **opaque** `sourceStatusId` (+ `sourceStatus` display name, logs only) documented as a *vendor-opaque status token not interpreted by neutral consumers* — only the Linear implementation produces it and only the Linear-specific `LinearPollingHost` reads it. An opaque string token is a defensible reading of "no GraphQL IDs" (it is not a typed GraphQL DTO), and it keeps the refactor mechanical with no auto-ingest behavior drift. The strict alternative — a neutral `TicketStatus` with auto-ingest gating moved fully inside the Linear adapter — was rejected as larger and behavior-drift-prone.

**3. Selection adds a `kind` key but keeps Spring profiles (OQ-2).** A new `deliveryline.integration.ticket-source.kind` selector (default `linear`) is the *documented* selector; `TicketSourceProperties` normalizes it and `LinearConfiguration` fail-fasts at boot when `kind` names a kind with no implementation on the classpath. The load-bearing bean gating remains the mutually-exclusive `linear-mock`/`linear-real` Spring profiles, and the `deliveryline.linear.*` config keys are unchanged. Renaming those keys to `deliveryline.integration.ticket-source.linear.*` is an ops-breaking `.env`/deploy change and is intentionally **out of scope** (a future cosmetic migration).

**4. The comment method keeps its governed shape (OQ-3).** AC1 literally wants `commentOnTicket(TicketRef, String body) → CommentResult`. The live method is `postGovernedRunComment(ticketRef, GovernedRunComment)` carrying a SHA-256 idempotency **fingerprint** + `DataClassification` (story 3.16 AC3). Flattening to a raw `String body` would destroy the idempotency + classification contract the system depends on. The port keeps `CommentResult postGovernedRunComment(TicketRef, GovernedRunComment)` — the param type changes (`String` → `TicketRef`) and the return changes (`void` → `CommentResult`, surfacing the idempotency-replay no-op as `SKIPPED_DUPLICATE`). No raw-string method is added.

**5. Capability-driven degradation is a log + outcome, not a new event (OQ-4).** `TicketSourceCapabilities.supportsCommentOnTicket` gates the completion-sync write-back: a source declaring `false` makes `WorkflowOrchestrationService.syncCompletionToLinear` skip gracefully with a structured WARN (`event=linear.completionSyncSkipped reason=ticket_source_does_not_support_comments`) and the new `SyncCompletionOutcome.SKIPPED_NO_COMMENT_CAPABILITY`. No new `WorkflowEventType` is added — consistent with the other `SKIPPED_*` outcomes, and avoiding the `RegistryContractTest` + fixture fan-out.

## Alternatives Considered

### Alt 1 — Strictly-neutral `Ticket` with no status token (model a `TicketStatus`)

Move auto-ingest gating fully inside the Linear adapter and expose a neutral `TicketStatus` value.

**Rejected.** Larger blast radius and real behavior-drift risk for story 3a.5 auto-ingest, for no neutrality win that an opaque token does not already provide. The opaque `sourceStatusId` keeps the refactor mechanical and the parity tests honest.

### Alt 2 — Replace Spring profiles with the `kind` property as the only selector

Rip out `@Profile("linear-mock")`/`@Profile("linear-real")` and rename `deliveryline.linear.*` → `deliveryline.integration.ticket-source.linear.*`.

**Rejected.** The profile mechanism is load-bearing (bean gating + `assertExclusiveLinearProfile`), and renaming the config keys is an ops-breaking `.env`/deploy change out of scope for an internal refactor. The `kind` key is added as a validated selector alongside profiles.

### Alt 3 — Add a literal `commentOnTicket(TicketRef, String)` to match AC1 verbatim

Keep the governed method and add a raw-string sibling.

**Rejected.** A second, weaker path that loses the fingerprint + classification contract; AC1 is satisfied-in-spirit by the richer governed method.

## Consequences

### Positive

- A future ticket source is a one-interface, one-contract add: implement `TicketSourceAdapter` against `docs/integrations/ticket-source-extension-contract.md`, add a `kind` + profile.
- Linear behavior is preserved byte-for-byte; the parity foundation contract (Contract #14) drives the mock + real adapters through equivalent scenarios.
- Symmetric with story 3.33's `RepositoryHostAdapter` (ADR 0008) — a future reader sees one abstraction pattern, not two.

### Negative

- The package-rename is the highest-churn part of the story (~30 files across main + test). It is mechanical, but the ArchUnit `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` rule and the parity tests are the guardrails that keep it honest.
- `sourceStatusId` being opaque means a future strictly-typed status model is still possible but deferred — neutral consumers must not interpret the token.

### Neutral

- `deliveryline.linear.*` config keys and the `linear-mock`/`linear-real` profiles are unchanged; the only net-new config is the optional `deliveryline.integration.ticket-source.kind` selector (default `linear`).
- The `IntegrationLinkService` method names (`linkTicket`, `findActiveLinearTicketLink`) and `WorkflowOrchestrationService.syncCompletionToLinear` keep their Linear-flavored names — a cosmetic rename is a follow-up, not this story.

## Story 3i-1 — JIRA is the second real `TicketSourceAdapter` kind (FR80)

JIRA (`ConnectorKind.JIRA`, `kind=jira`) is added at Linear parity: `JiraRealAdapter` /
`JiraMockAdapter` under `adapters.integration.ticketsource.jira`, `@Profile("jira-real")` /
`@Profile("jira-mock")` — **not `@Primary`** (per-project resolution keys on `connectorKind()`, so a
second `@Primary` would collide with `LinearRealAdapter` for the single-injection `LinearPollingHost`
when both real profiles co-activate). It implements the full capability set
(`TicketSourceCapabilities.jiraDefaults()`) against JIRA REST v3 (`/rest/api/3/issue/{key}`,
`/comment`, sub-task `/issue`, `/myself`). Ticket workflow-state rides the neutral
`Ticket.sourceStatus` / `sourceStatusId` (opaque `fields.status.id`) — there is no state-**write**
(JIRA transitions are out of scope). Comment/description bodies are Atlassian Document Format (ADF);
the `<!-- deliveryline:... -->` idempotency markers are embedded in an ADF text node and scanned back
by extracting comment text.

The story also **generalized the story-3.32 ticket-source `kind` fail-fast** (deferred-work #132):
`LinearConfiguration` previously hard-failed any `kind != linear`, so a `kind=jira` deployment could
not boot. It now validates the configured `kind` against the registered `ConnectorKind` set
(connector-agnostic — a new enum value needs no edit here); per-project resolution remains the
load-bearing selector.

Onboarding a JIRA project: store the per-project API token write-only under
`ConnectorRole.TICKET_SOURCE` (opaque ciphertext, no schema change); set deployment-level
`deliveryline.jira.base-url` (non-secret) and `deliveryline.jira.email`; activate the `jira-real`
profile and set `deliveryline.integration.ticket-source.kind: jira`. JIRA Cloud auth is HTTP Basic
`email:apiToken` assembled at request time (never logged). The doctor `jira-auth` probe verifies it.

## Story 3i-2 — `supportsTicketQuery`: the sixth capability flag (FR81)

The port gains an optional read, `TicketQueryResult queryTickets(TicketQuery)` — a filtered browse
for *candidate* tickets (assignee / components / state, bounded by `limit`) — gated on a new sixth
`TicketSourceCapabilities` flag, `supportsTicketQuery`. Only JIRA advertises it today (JQL-backed);
Linear and the GitLab stub report `false` and throw `UnsupportedOperationException` rather than
returning an empty result, which would misreport an unsupported connector as an empty backlog.

The flag is **in-code only**: it never reaches a DB `CHECK`, so there is no Flyway migration and no
new `ConnectorKind` (3i-1 already landed `JIRA` + V37). Adding it is a three-factory edit
(`noCreation`/`linearDefaults` → `false`, `jiraDefaults` → `true`).

### The result is a page, not a list (code review, 2026-07-10)

`queryTickets` returns `TicketQueryResult{tickets, total}` rather than a bare list. A browse is a
*bounded page*, so a bare list cannot distinguish a complete answer from a truncated one: a filter
matching 400 tickets and one matching exactly `limit` render identically, and the operator gets no
signal to narrow the filter. `total` counts matches at the source; `truncated()` is
`total > tickets.size()`, which is also true when a ticket was skipped because the source hid a
required field from the browsing account. Both cases answer the operator's real question — *am I
seeing everything?* The REST surface mirrors this as a `CandidateTicketPage` envelope, deviating from
the list endpoints' usual bare-array convention for exactly this reason.

### Adapter failures are translated, never leaked (code review, 2026-07-10)

`TicketSourceAdapterException` is a plain `RuntimeException` carrying an `IntegrationFailureCategory`.
Because the browse is the **first synchronous REST path into a ticket-source adapter**, it is also the
first place that exception could reach an HTTP response — where, unhandled, it rendered as an opaque
`500 INTERNAL_ERROR, retryable=false`. The application service now honors the contract the exception's
own Javadoc states and maps the category: `NETWORK_API_FAILURE` → `TICKET_QUERY_SOURCE_UNAVAILABLE`
(503, **retryable** — the source was unreachable and the same request may later succeed); every other
category → `TICKET_QUERY_SOURCE_FAILED` (502, **non-retryable** — the source answered, but with an
expired credential or an unmappable payload, and retrying cannot help). Any future adapter-backed
foreground read must translate the same way.

Two design points worth preserving:

- **`TicketQuery` / `TicketSummary` are new neutral records** in `domain.integration.ticketsource`.
  In particular `TicketSummary` is *not* the pre-existing `application.runner.TicketSummary` (the
  context-bundle projection): that one carries a bare `String` ref and rejects a blank summary, which
  would throw on a real JIRA ticket with no description. It also lives in a package the REST adapters
  are forbidden to import (`REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER`).
- **The capability gate surfaces as a typed 404, not a silent skip.** `TicketSourceSubticketService`
  skips silently when `supportsTicketCreation` is off, because sub-ticket creation is a background
  side-effect. A browse is a direct operator request with no meaningful degraded answer, so
  `TicketQueryService` raises `TICKET_QUERY_NOT_SUPPORTED` (HTTP 404, non-retryable) for both the
  capability-off and the no-adapter case. The intake UI hides the surface by catching that code —
  never by checking the connector kind, so a future browsable connector lights it up for free.

The vendor query is assembled **by omission**: an absent filter field contributes no clause at all
(never a match-all predicate), and every operator-supplied value is escaped for the vendor dialect —
the filters are an injection boundary.

## References

- [Source: `_bmad-output/planning-artifacts/epic-03i-connector-expansion.md#Story 3i-2`] — FR81 filtered ticket-intake browse, ACs 1–8.
- [Source: `_bmad-output/planning-artifacts/epic-03i-connector-expansion.md#Story 3i-1`] — FR80 JIRA ticket source, ACs 1–8.
- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.32`] — ACs 1–10 (and §3.33 for the symmetric GitHub sibling).
- `docs/integrations/ticket-source-extension-contract.md` — the documented extension contract for new ticket sources.
- `docs/integrations/linear-completion-sync.md` — the completion-sync flow this story must not regress.
- `docs/adr/0008-repository-host-abstraction.md` — the symmetric repository-host sibling.
- `docs/adr/0013-credential-encryption.md` — Epic-3c credential-encryption primitive that builds on this per-project connector abstraction (story 3c-4).
- `docs/adr/0004-spec-stage-orchestration.md` — ADR format followed here.
