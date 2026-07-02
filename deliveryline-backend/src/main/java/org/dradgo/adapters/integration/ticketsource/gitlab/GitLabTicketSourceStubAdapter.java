package org.dradgo.adapters.integration.ticketsource.gitlab;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.domain.integration.ticketsource.CommentResult;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.registry.ConnectorKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Documented stub proving the per-project connector seam (story 3c-3 AC8). Declares {@code
 * connectorKind() == GITLAB} so the {@code ProjectConnectorResolver} kind&rarr;adapter map
 * genuinely contains a second ticket-source entry beside the active vendor — making per-kind
 * resolution a real lookup, not a degenerate single-entry pass-through.
 *
 * <p>It is <strong>always-on</strong> (a plain {@code @Component} with no {@code @Profile}) so it
 * is present in every context; single-injection consumers stay unambiguous because the active
 * vendor adapter carries {@code @Primary}. Every operation is a safe no-op (empty results) and it
 * reports a <strong>deliberately degraded</strong> capability set ({@code
 * supportsCommentOnTicket=false}) so AC4's capability-driven degradation path is exercised by a
 * real registered kind. A real GitLab ticket-source implementation is post-pilot — this class
 * imports no vendor SDK (nothing to leak).
 */
@Component
public class GitLabTicketSourceStubAdapter implements TicketSourceAdapter {

  private static final Logger log = LoggerFactory.getLogger(GitLabTicketSourceStubAdapter.class);

  @Override
  public ConnectorKind connectorKind() {
    return ConnectorKind.GITLAB;
  }

  @Override
  public Optional<Ticket> fetchTicketByReference(TicketRef ref) {
    Objects.requireNonNull(ref, "ref");
    log.info("gitlab stub: fetchTicketByReference is a documented no-op ticketRef={}", ref.value());
    return Optional.empty();
  }

  @Override
  public List<Ticket> pollNewTickets(Instant since) {
    Objects.requireNonNull(since, "since");
    log.info("gitlab stub: pollNewTickets is a documented no-op since={}", since);
    return List.of();
  }

  @Override
  public CommentResult postGovernedRunComment(TicketRef ref, GovernedRunComment summary) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(summary, "summary");
    // supportsCommentOnTicket=false, so capability-gating consumers never reach here; if a
    // non-gating caller does, the no-op SKIPPED_DUPLICATE keeps the contract honest (never writes).
    log.info(
        "gitlab stub: postGovernedRunComment is a documented no-op ticketRef={} runPublicId={}",
        ref.value(),
        summary.runPublicId());
    return CommentResult.SKIPPED_DUPLICATE;
  }

  @Override
  public CreateSubticketResult createSubticket(TicketRef parentRef, SubticketDraft draft) {
    Objects.requireNonNull(parentRef, "parentRef");
    Objects.requireNonNull(draft, "draft");
    throw new UnsupportedOperationException("GitLab ticket creation is not supported");
  }

  @Override
  public Optional<String> buildSourceTicketUrl(TicketRef ref) {
    Objects.requireNonNull(ref, "ref");
    // supportsSourceTicketUrl=false (via noCreation) — capability-gating callers never reach here;
    // return empty so a non-gating caller still degrades safely (never fabricates a URL).
    return Optional.empty();
  }

  @Override
  public TicketSourceCapabilities getCapabilities() {
    // Deliberately degraded — all optional operations unsupported (exercises AC4 degradation).
    return TicketSourceCapabilities.noCreation(false, false, false);
  }

  @Override
  public ConnectivityResult verifyConnectivity(String credentialOverride) {
    // Documented stub (story 3c-8): a degraded-but-OK deterministic result. The connection-test
    // service marks this connector's check `skipped` from its degraded capability set before this
    // is ever rendered as pass/fail.
    log.info("gitlab stub: verifyConnectivity is a documented no-op (degraded connector)");
    return ConnectivityResult.ok("gitlab stub: connectivity probe is a documented no-op");
  }
}
