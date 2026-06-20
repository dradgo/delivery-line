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

## References

- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.32`] — ACs 1–10 (and §3.33 for the symmetric GitHub sibling).
- `docs/integrations/ticket-source-extension-contract.md` — the documented extension contract for new ticket sources.
- `docs/integrations/linear-completion-sync.md` — the completion-sync flow this story must not regress.
- `docs/adr/0008-repository-host-abstraction.md` — the symmetric repository-host sibling.
- `docs/adr/0013-credential-encryption.md` — Epic-3c credential-encryption primitive that builds on this per-project connector abstraction (story 3c-4).
- `docs/adr/0004-spec-stage-orchestration.md` — ADR format followed here.
