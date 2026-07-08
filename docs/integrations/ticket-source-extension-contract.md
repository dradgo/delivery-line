# Ticket-Source Extension Contract

This document specifies the contract a new **ticket source** (JIRA, GitHub Issues, GitLab Issues, Asana, …) must satisfy to plug into DeliveryLine. The Linear integration is the reference implementation; a new source is a one-interface, one-contract add — implement `org.dradgo.application.integration.ticketsource.TicketSourceAdapter` against this contract, add a `kind` selector value, and a Spring profile.

Background and the decisions behind the abstraction live in `../adr/0007-ticket-source-abstraction.md`. The completion-sync flow that consumes the comment-posting capability is described in `linear-completion-sync.md`.

## The port

`TicketSourceAdapter` is the vendor-neutral application-owned port. It carries only domain-shaped types from `org.dradgo.domain.integration.ticketsource` (`Ticket`, `TicketRef`, `CommentResult`, `TicketSourceCapabilities`, `GovernedRunComment`) — vendor transport types (GraphQL/REST DTOs, SDK types, HTTP clients) must **not** leak through. The `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` ArchUnit rule enforces this (verified in Failsafe).

Only `IntegrationLinkService` and the vendor-specific polling host bean may call the port directly. CLI / REST / persistence go through `IntegrationLinkService`.

## Per-method expected behavior

### `Optional<Ticket> fetchTicketByReference(TicketRef ref)`

- Look up a ticket by its external reference (e.g. `LIN-123`). Return `Optional.empty()` when the ticket does not exist at the source — **do not throw on not-found** (the command layer translates empty → a typed `LINEAR_TICKET_NOT_FOUND` domain exception).
- Throw `TicketSourceAdapterException` (classified, see below) on network/auth/state failures.
- Map the vendor response onto a neutral `Ticket` at the boundary: `ticketRef`, `title`, `summary`, `authorIdentity` (an identity string, never a vendor user DTO), `createdAt`, `updatedAt`, immutable `labels`, and the nullable opaque `sourceStatus`/`sourceStatusId`.

### `List<Ticket> pollNewTickets(Instant since)`

- Return tickets whose `updatedAt` is strictly after `since`, **ordered ascending by `updatedAt`** (callers advance their watermark to `max(updatedAt)`, which must be monotonic). Apply the adapter's own paging internally. Return an empty list when there is nothing new.
- A source that does not support polling declares `supportsPolling=false`; the polling host must not be wired for it.

### `CommentResult postGovernedRunComment(TicketRef ref, GovernedRunComment summary)`

- Best-effort write-back of a governed run summary to the source ticket.
- **Optional operation:** consumers must check `getCapabilities().supportsCommentOnTicket()` first and degrade gracefully when `false` (the completion sync logs `event=linear.completionSyncSkipped` and returns `SKIPPED_NO_COMMENT_CAPABILITY`).
- Return `CommentResult.POSTED` on a fresh write and `CommentResult.SKIPPED_DUPLICATE` on an idempotency replay (see Idempotency).

### `TicketSourceCapabilities getCapabilities()`

- Declare which optional operations the source supports: `supportsCommentOnTicket`, `supportsPolling`, `supportsTicketStateUpdates`. Consuming services gate optional calls on these flags.

## Error classification

Every failure surfaced through the port is a `TicketSourceAdapterException` carrying an `org.dradgo.domain.registry.IntegrationFailureCategory` — never an HTTP status code or vendor error envelope. Map vendor failures onto the categories the reference Linear adapter uses:

| Condition | `IntegrationFailureCategory` |
| --- | --- |
| auth (401/403) | `LINK_FAILURE` |
| rate-limit (429), 5xx, network I/O | `NETWORK_API_FAILURE` |
| malformed / unexpected response, validation error, serialization | `SYNC_FAILURE` |
| unexpected status / conflicting state | `STATE_CONFLICT` |

The adapter does **not** retry; it surfaces typed failures for the recovery layer to decide policy.

## Idempotency guarantees

`postGovernedRunComment` is idempotent on `(ref, summary.runPublicId(), summary.fingerprint())`. `GovernedRunComment.fingerprint` is a stable SHA-256 marker; the implementation embeds it in the comment body (the reference Linear adapter writes an HTML-comment marker `<!-- deliveryline:run=<runPublicId> fp=<fingerprint> -->`) and scans existing comments to no-op a re-post. A re-post of the same fingerprint returns `CommentResult.SKIPPED_DUPLICATE`; a fresh post returns `CommentResult.POSTED`.

## Redaction on egress

Any text sent to the source must already have passed through `org.dradgo.application.security.RedactionPolicyService` — the adapter does **not** redact. `GovernedRunComment.body` is pre-redacted by the composing service (`WorkflowOrchestrationService.syncCompletionToLinear` claims `shareable-full`). Never log secrets, raw tokens, comment body bytes, or ticket free-text; sanitize references with `MdcKeys.sanitizeForLog(...)`.

## Capability declaration

Return a `TicketSourceCapabilities` describing the source. The Linear reference returns `TicketSourceCapabilities.linearDefaults()` (all three `true`). A source lacking comment-posting must return `supportsCommentOnTicket=false` so the completion sync degrades gracefully rather than failing.

## Configuration-key conventions

- `deliveryline.integration.ticket-source.kind` selects the active kind (default `linear`). `LinearConfiguration` (or the new source's configuration) fail-fasts at boot if `kind` names a kind with no implementation on the classpath.
- Vendor-specific config lives under `deliveryline.integration.ticket-source.{kind}.*` for new sources. The existing Linear keys remain under `deliveryline.linear.*` (renaming them is an ops-breaking change out of scope — see ADR 0007).
- Bean gating is by Spring profile (the Linear reference uses mutually-exclusive `linear-mock`/`linear-real`). Keep vendor config records framework-light and out of the layered-boundary path (`application.integration.{kind}`, not `infrastructure`).

## Testing requirements

A new source must:

- Implement a **parity test** that drives the mock and real implementations through the same fixture scenarios used for Linear (happy read returns a neutral `Ticket`; a classified failure surfaces the same `IntegrationFailureCategory` in both). Mirror `TicketSourceAbstractionFoundationContract` (foundation Contract #14).
- Cover capability-driven degradation: a source declaring `supportsCommentOnTicket=false` makes `syncCompletionToLinear` skip with `SKIPPED_NO_COMMENT_CAPABILITY` and emit the `linear.completionSyncSkipped` WARN.
- Cover config-driven selection: the configured `kind` (+ profile) activates the right implementation, and a `kind` with no implementation fails fast at boot.
- Honor the test-naming conventions: `@SpringBootTest`+Testcontainers tests are `*IT` (Failsafe); ArchUnit `@ArchTest`s run in Failsafe, not Surefire.

## Worked example: JIRA (story 3i-1)

JIRA is the first non-Linear connector to exercise this contract end-to-end (`ConnectorKind.JIRA`,
`kind=jira`, `adapters.integration.ticketsource.jira`). It is a **real** implementation (all
capabilities true, real JIRA REST v3 HTTP) — the opposite of the degraded `gitlab` stub. Notes for
the next author:

- **Not `@Primary`.** Resolution keys on `connectorKind()`; a second `@Primary` collides with
  `LinearRealAdapter` for the single-injection polling host when both real profiles co-activate.
- **Vendor body format lives behind the port.** JIRA comment/description bodies are ADF; the
  `<!-- deliveryline:... -->` markers are embedded in an ADF text node and scanned back by
  extracting comment text. The neutral `Ticket`/`CommentResult`/`CreateSubticketResult` shapes are
  unchanged — no ADF type crosses the port.
- **State is read-only on the neutral `Ticket`.** `sourceStatus` (name) + `sourceStatusId` (opaque
  `fields.status.id`) populate from `fields.status`; there is no state-write.
- **Auth is deployment + per-project.** HTTP Basic `email:apiToken`; the email is deployment-level
  (`deliveryline.jira.email`) and the token is the per-project write-only credential under
  `ConnectorRole.TICKET_SOURCE`, preferred via the `CREDENTIAL_OVERRIDE_ATTRIBUTE` at request time.
- **Doctor + redaction fan-out.** A `jira-auth` doctor probe + `DOCTOR_JIRA_{TOKEN_MISSING,AUTH_FAILED}`
  codes, and a `project-credential-jira-token.json` redaction fixture (the Atlassian token rides a
  `SECRET_FIELD`-covered key — Atlassian tokens have no stable prefix, so no vendor regex).

## References

- `../adr/0007-ticket-source-abstraction.md` — the abstraction decision record (incl. the JIRA section).
- `linear-completion-sync.md` — the completion-sync flow and security posture.
