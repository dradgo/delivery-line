package org.dradgo.application.integration.ticketsource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dradgo.domain.integration.ticketsource.CommentResult;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;

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
   * Declare which optional operations this ticket source supports (story 3.32 AC3). Consuming
   * services gate optional calls (e.g. comment write-back) on the relevant capability flag.
   */
  TicketSourceCapabilities getCapabilities();
}
