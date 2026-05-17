package org.dradgo.application.integration.linear;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application-owned port for the Linear ticket-source integration. The port carries only
 * domain-shaped methods — Linear-specific transport (GraphQL DTOs, auth tokens, HTTP clients) must
 * not leak through. Verified by the {@code LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule
 * (story 1.14 Task 7).
 *
 * <p>Two implementations exist, profile-activated:
 *
 * <ul>
 *   <li>{@code LinearMockAdapter} ({@code @Profile("linear-mock")}) — deterministic,
 *       fixture-backed, zero network calls. Default in {@code test}; opt-in for {@code local} /
 *       {@code demo}.
 *   <li>{@code LinearRealAdapter} ({@code @Profile("linear-real")}) — Linear GraphQL polling +
 *       comment-posting. Opt-in only; never in any default profile group.
 * </ul>
 *
 * <p>Only {@code IntegrationLinkService} and the polling host bean may call this port directly —
 * CLI / REST / persistence must go through {@code IntegrationLinkService} (mirrors the "only {@code
 * RunnerBroker} may call {@code RunnerAdapter.dispatch}" pattern from story 1.13).
 */
public interface LinearAdapter {

  /**
   * Look up a Linear ticket by its external reference (e.g., {@code "LIN-123"}). Returns empty when
   * the ticket does not exist at the source — the adapter does not throw on "not found". The
   * command layer translates an empty result to a typed {@code LINEAR_TICKET_NOT_FOUND} domain
   * exception (story 1.14 AC7).
   *
   * <p>Throws — via runtime exceptions classified by category — on network/auth/state failures.
   * Callers must wrap the call site and translate to {@code IntegrationFailureCategory} per AC6.
   */
  Optional<LinearTicket> fetchTicketByReference(String ticketRef);

  /**
   * Return Linear tickets whose {@code updatedAt} is strictly after {@code since}, ordered
   * ascending by {@code updatedAt}. Adapter-side max batch size applies; callers should iterate
   * with the highest returned {@code updatedAt} as the next {@code since}. Returns an empty list
   * when no new tickets are present.
   */
  List<LinearTicket> pollNewTickets(Instant since);

  /**
   * Best-effort write-back of a governed-run summary to the source ticket. Idempotent on {@code
   * (ticketRef, summary.runPublicId(), summary.fingerprint())} — re-posting the same fingerprint is
   * a no-op (AC3 idempotency requirement). The {@code summary.body()} is expected to have already
   * been through the redaction policy; the adapter does not redact.
   */
  void postGovernedRunComment(String ticketRef, GovernedRunComment summary);
}
