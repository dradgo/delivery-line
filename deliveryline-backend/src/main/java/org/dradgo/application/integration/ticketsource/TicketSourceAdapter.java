package org.dradgo.application.integration.ticketsource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.domain.integration.ticketsource.CommentResult;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.registry.ConnectorKind;

/**
 * Application-owned, vendor-neutral port for a ticket-source integration (story 3.32 — extracted
 * from the Linear-shaped {@code LinearAdapter} of story 1.14). The port carries only domain-shaped
 * methods — vendor-specific transport (GraphQL/REST DTOs, auth tokens, HTTP clients) must not leak
 * through. Verified by the {@code TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule.
 *
 * <p>Concrete implementations are vendor-specific and profile-activated and live under {@code
 * org.dradgo.adapters.integration.ticketsource.{kind}} (today: {@code ...ticketsource.linear} with
 * {@code LinearMockAdapter} / {@code LinearRealAdapter}). A future ticket source (JIRA, GitHub
 * Issues, GitLab Issues) is a one-interface, one-contract add — see {@code
 * docs/integrations/ticket-source-extension-contract.md}.
 *
 * <p>Only {@code IntegrationLinkService} and the vendor-specific polling host bean may call this
 * port directly — CLI / REST / persistence must go through {@code IntegrationLinkService} (mirrors
 * the "only {@code RunnerBroker} may call {@code RunnerAdapter.dispatch}" pattern from story 1.13).
 */
public interface TicketSourceAdapter {

  /**
   * Declare which {@link ConnectorKind} this adapter serves — the key {@code
   * ProjectConnectorResolver} selects on (story 3c-3). {@link ConnectorKind} is a {@code
   * domain.registry} type, so returning it through the port introduces no vendor leak (satisfies
   * {@code TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT}).
   */
  ConnectorKind connectorKind();

  /**
   * Look up a ticket by its external reference (e.g., {@code "LIN-123"}). Returns empty when the
   * ticket does not exist at the source — the adapter does not throw on "not found". The command
   * layer translates an empty result to a typed {@code LINEAR_TICKET_NOT_FOUND} domain exception
   * (story 1.14 AC7).
   *
   * <p>Throws — via runtime exceptions classified by category — on network/auth/state failures.
   * Callers must wrap the call site and translate to {@code IntegrationFailureCategory}.
   */
  Optional<Ticket> fetchTicketByReference(TicketRef ref);

  /**
   * Return tickets whose {@code updatedAt} is strictly after {@code since}, ordered ascending by
   * {@code updatedAt}. Adapter-side max batch size applies; callers should iterate with the highest
   * returned {@code updatedAt} as the next {@code since}. Returns an empty list when no new tickets
   * are present.
   */
  List<Ticket> pollNewTickets(Instant since);

  /**
   * Best-effort write-back of a governed-run summary to the source ticket. Idempotent on {@code
   * (ref, summary.runPublicId(), summary.fingerprint())} — re-posting the same fingerprint is a
   * no-op and returns {@link CommentResult#SKIPPED_DUPLICATE} (story 3.16 AC3 idempotency
   * requirement); a fresh write returns {@link CommentResult#POSTED}. The {@code summary.body()} is
   * expected to have already been through the redaction policy; the adapter does not redact.
   *
   * <p>Optional operation: consumers must check {@link
   * TicketSourceCapabilities#supportsCommentOnTicket()} before invoking and gracefully degrade when
   * the source does not support comment posting.
   */
  CommentResult postGovernedRunComment(TicketRef ref, GovernedRunComment summary);

  /**
   * Optional operation: create a source-system child/sub-ticket under {@code parentRef}. Consumers
   * must check {@link TicketSourceCapabilities#supportsTicketCreation()} before invoking; adapters
   * that do not advertise the capability may throw {@link UnsupportedOperationException}.
   *
   * <p>The {@code draft} text fields are expected to have already passed the redaction/content
   * policy. The adapter must use {@code draft.idempotencyKey()} as its replay key and return the
   * same child ref on replay when the external source state is discoverable.
   */
  CreateSubticketResult createSubticket(TicketRef parentRef, SubticketDraft draft);

  /**
   * Optional operation (story 3g-1): build a stable link-back URL to the originating ticket from
   * its {@link TicketRef}. Consumers MUST check {@link
   * TicketSourceCapabilities#supportsSourceTicketUrl()} before invoking; adapters that do not
   * advertise the capability return {@link Optional#empty()}. The URL is derived purely from the
   * {@code ref} (no network call, no auth) so it can be snapshotted into the run's origin metadata
   * at link time. Returns {@link Optional#empty()} when the ref cannot form a URL. The result is a
   * plain {@link String} — no vendor type crosses the port ({@code
   * TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT}) — and MUST NOT embed tokens or query secrets.
   */
  Optional<String> buildSourceTicketUrl(TicketRef ref);

  /**
   * Declare which optional operations this ticket source supports (story 3.32 AC3). Consuming
   * services gate optional calls (e.g. comment write-back) on the relevant capability flag.
   */
  TicketSourceCapabilities getCapabilities();

  /**
   * Story 3c-8 (AC3 / R1, P1) — a lightweight authenticated reachability probe for the project
   * test-connection surface. Performs at most one cheap authenticated call (e.g. a {@code viewer}
   * lookup) and returns a secret-free {@link ConnectivityResult} — it MUST classify network/auth
   * failures into the result rather than throwing a vendor exception across the port. Mock / stub
   * adapters return a deterministic reachable + authenticated result.
   *
   * @param credentialOverride the project-scoped stored credential to authenticate the probe with;
   *     when {@code null}/blank the adapter falls back to its host-env credential (AC3). The value
   *     is a secret — it is used only for this one call and MUST NOT be logged, returned, or
   *     persisted.
   */
  ConnectivityResult verifyConnectivity(String credentialOverride);
}
