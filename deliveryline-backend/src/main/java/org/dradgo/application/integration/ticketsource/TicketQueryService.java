package org.dradgo.application.integration.ticketsource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectManagementService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.ticketsource.TicketQuery;
import org.dradgo.domain.integration.ticketsource.TicketQueryResult;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3i-2 (FR81) — capability-gated application seam for the filtered candidate-ticket browse.
 * The <strong>only</strong> caller of {@code TicketSourceAdapter.queryTickets}: the REST controller
 * and the CLI both route through this service rather than touching the port directly.
 *
 * <p><strong>Gating posture differs from {@link TicketSourceSubticketService}.</strong> That
 * service <em>silently skips</em> when the connector lacks {@code supportsTicketCreation}, because
 * sub-ticket creation is a background side-effect of a split and a missing capability must not fail
 * the run. Here the caller is a direct, foreground operator request: "browse this project's
 * tickets" has no meaningful degraded answer, and returning an empty list would misreport an
 * unsupported connector as an empty backlog. So both the no-adapter and the capability-off cases
 * funnel to one typed {@link DomainErrorCode#TICKET_QUERY_NOT_SUPPORTED} (HTTP 404) that the UI
 * catches to hide the intake surface — never a 5xx, and never a hardcoded connector-kind check in
 * the client.
 *
 * <p>Resolution uses the non-throwing {@link ProjectConnectorResolver#findTicketSource} so a
 * project whose kind has no adapter registered in this context degrades to the same clean 404
 * rather than raising {@code UNSUPPORTED_CONNECTOR_KIND} (a 400, which would imply the operator
 * sent a bad request).
 *
 * <p><strong>Upstream failures are translated here, not leaked.</strong> This browse is the first
 * synchronous REST path into a ticket-source adapter, so it is the first place a {@link
 * TicketSourceAdapterException} can reach an HTTP response. That exception's own contract states
 * that "the application service is responsible for converting this to the appropriate {@code
 * DomainException}" — left uncaught it falls through to {@code @ExceptionHandler(Exception.class)}
 * and renders as an opaque {@code 500 INTERNAL_ERROR, retryable=false}. We map its {@link
 * IntegrationFailureCategory} instead, so an unreachable source is a retryable 503 and a bad
 * credential or malformed payload is a non-retryable 502.
 */
@Service
public class TicketQueryService {

  private static final Logger log = LoggerFactory.getLogger(TicketQueryService.class);

  private final ProjectManagementService projectManagementService;
  private final ProjectConnectorResolver projectConnectorResolver;

  public TicketQueryService(
      ProjectManagementService projectManagementService,
      ProjectConnectorResolver projectConnectorResolver) {
    this.projectManagementService =
        Objects.requireNonNull(projectManagementService, "projectManagementService");
    this.projectConnectorResolver =
        Objects.requireNonNull(projectConnectorResolver, "projectConnectorResolver");
  }

  /**
   * Browse candidate tickets for {@code projectReference}'s ticket source. The returned {@link
   * TicketQueryResult} carries the source's total match count alongside the page, so the caller can
   * tell the operator whether they are looking at the whole backlog or a truncated slice of it.
   *
   * @throws DomainException {@code PROJECT_NOT_FOUND} when the project does not exist; {@code
   *     TICKET_QUERY_NOT_SUPPORTED} when its ticket source has no registered adapter or does not
   *     advertise {@code supportsTicketQuery}; {@code TICKET_QUERY_SOURCE_UNAVAILABLE} (retryable)
   *     when the source could not be reached; {@code TICKET_QUERY_SOURCE_FAILED} when it answered
   *     unusably.
   */
  public TicketQueryResult queryCandidateTickets(String projectReference, TicketQuery query) {
    Objects.requireNonNull(projectReference, "projectReference");
    Objects.requireNonNull(query, "query");
    Project project = projectManagementService.getProject(projectReference);
    // Counts/flags only — the filter values and the resulting JQL never reach the log (AC7).
    log.info(
        "ticket_query start projectId={} assigneeFiltered={} componentCount={} stateFiltered={} limit={}",
        project.publicId(),
        query.hasAssignee(),
        query.components().size(),
        query.hasState(),
        query.limit());

    TicketSourceAdapter adapter =
        projectConnectorResolver
            .findTicketSource(project)
            .orElseThrow(() -> notSupported(project, "no_ticket_source"));
    if (!adapter.getCapabilities().supportsTicketQuery()) {
      throw notSupported(project, "query_not_supported");
    }

    TicketQueryResult result;
    try {
      result = adapter.queryTickets(query);
    } catch (TicketSourceAdapterException e) {
      throw sourceFailure(project, e);
    }
    log.info(
        "ticket_query resolved projectId={} connectorKind={} resultCount={} total={} truncated={}",
        project.publicId(),
        project.ticketSourceKind().value(),
        result.tickets().size(),
        result.total(),
        result.truncated());
    return result;
  }

  /**
   * Translate an adapter failure into a typed, correctly-classified {@link DomainException}.
   *
   * <p>Only {@code NETWORK_API_FAILURE} is retryable: the source was unreachable or answered
   * transiently (timeout, connection reset, 429, 5xx), so the identical request may succeed later.
   * Every other category means the source answered and the answer was unusable — an expired or
   * insufficiently-scoped credential ({@code LINK_FAILURE}), a response we could not map ({@code
   * SYNC_FAILURE}), or a source-side state conflict. Retrying those cannot help, and telling the
   * client otherwise invites a hot loop against a credential that will never start working.
   *
   * <p>The exception's message may quote a source response, so it is <strong>never</strong> logged
   * or copied into the ProblemDetails detail. Category, project id and connector kind only (AC7).
   */
  private static DomainException sourceFailure(Project project, TicketSourceAdapterException e) {
    IntegrationFailureCategory category = e.failureCategory();
    boolean unreachable = category == IntegrationFailureCategory.NETWORK_API_FAILURE;
    DomainErrorCode code =
        unreachable
            ? DomainErrorCode.TICKET_QUERY_SOURCE_UNAVAILABLE
            : DomainErrorCode.TICKET_QUERY_SOURCE_FAILED;
    log.warn(
        "ticket_query source_failure projectId={} connectorKind={} failureCategory={} retryable={}",
        project.publicId(),
        project.ticketSourceKind().value(),
        category.value(),
        unreachable);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("projectId", project.publicId());
    details.put("connectorKind", project.ticketSourceKind().value());
    details.put("failureCategory", category.value());
    return new DomainException(
        code,
        "Ticket source for project " + project.publicId() + " could not answer the browse",
        details,
        e);
  }

  private static DomainException notSupported(Project project, String reason) {
    log.warn(
        "ticket_query unsupported projectId={} connectorKind={} reason={}",
        project.publicId(),
        project.ticketSourceKind().value(),
        reason);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("projectId", project.publicId());
    details.put("connectorKind", project.ticketSourceKind().value());
    details.put("reason", reason);
    return new DomainException(
        DomainErrorCode.TICKET_QUERY_NOT_SUPPORTED,
        "Ticket source for project "
            + project.publicId()
            + " does not support a filtered ticket query",
        details);
  }
}
