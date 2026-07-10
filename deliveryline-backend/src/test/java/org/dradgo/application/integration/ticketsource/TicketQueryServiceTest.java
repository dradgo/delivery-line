package org.dradgo.application.integration.ticketsource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectManagementService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.ticketsource.TicketQuery;
import org.dradgo.domain.integration.ticketsource.TicketQueryResult;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.integration.ticketsource.TicketSummary;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3i-2 (AC3/AC8) — the capability gate in front of {@code TicketSourceAdapter.queryTickets}.
 */
class TicketQueryServiceTest {

  private static final String PROJECT_ID = "prj_default0001";

  private final ProjectManagementService projectManagementService =
      mock(ProjectManagementService.class);
  private final ProjectConnectorResolver resolver = mock(ProjectConnectorResolver.class);
  private final TicketQueryService service =
      new TicketQueryService(projectManagementService, resolver);

  @Test
  void supportedAdapterDelegatesAndReturnsResults() {
    Project project = project();
    TicketSourceAdapter adapter = mock(TicketSourceAdapter.class);
    when(projectManagementService.getProject(PROJECT_ID)).thenReturn(project);
    when(resolver.findTicketSource(project)).thenReturn(Optional.of(adapter));
    when(adapter.getCapabilities()).thenReturn(TicketSourceCapabilities.jiraDefaults());
    TicketQuery query = new TicketQuery("acct-1", List.of("billing"), "To Do", 25);
    TicketQueryResult expected =
        TicketQueryResult.complete(
            List.of(new TicketSummary(TicketRef.of("PROJ-1"), "Fix rounding", null)));
    when(adapter.queryTickets(query)).thenReturn(expected);

    assertThat(service.queryCandidateTickets(PROJECT_ID, query)).isEqualTo(expected);
    verify(adapter).queryTickets(query);
  }

  /** The service passes the source's total through untouched — it must not recompute it. */
  @Test
  void truncatedResultPassesTheSourceTotalThroughUnchanged() {
    TicketSourceAdapter adapter = supportedAdapter();
    when(adapter.queryTickets(any()))
        .thenReturn(
            new TicketQueryResult(
                List.of(new TicketSummary(TicketRef.of("PROJ-1"), "Fix rounding", null)), 412));

    TicketQueryResult result = service.queryCandidateTickets(PROJECT_ID, TicketQuery.unfiltered());

    assertThat(result.tickets()).hasSize(1);
    assertThat(result.total()).isEqualTo(412);
    assertThat(result.truncated()).isTrue();
  }

  /** Capability off => typed 404 code, and the port is never called. */
  @Test
  void capabilityOffRaisesTicketQueryNotSupportedAndNeverCallsThePort() {
    Project project = project();
    TicketSourceAdapter adapter = mock(TicketSourceAdapter.class);
    when(projectManagementService.getProject(PROJECT_ID)).thenReturn(project);
    when(resolver.findTicketSource(project)).thenReturn(Optional.of(adapter));
    // Linear advertises everything EXCEPT supportsTicketQuery.
    when(adapter.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());

    assertThatThrownBy(() -> service.queryCandidateTickets(PROJECT_ID, TicketQuery.unfiltered()))
        .isInstanceOf(DomainException.class)
        .extracting(failure -> ((DomainException) failure).errorCode())
        .isEqualTo(DomainErrorCode.TICKET_QUERY_NOT_SUPPORTED);

    verify(adapter, never()).queryTickets(any());
  }

  /** No adapter registered for the project's kind funnels to the SAME clean 404, not a 400/500. */
  @Test
  void noResolvableAdapterRaisesTicketQueryNotSupported() {
    Project project = project();
    when(projectManagementService.getProject(PROJECT_ID)).thenReturn(project);
    when(resolver.findTicketSource(project)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.queryCandidateTickets(PROJECT_ID, TicketQuery.unfiltered()))
        .isInstanceOf(DomainException.class)
        .extracting(failure -> ((DomainException) failure).errorCode())
        .isEqualTo(DomainErrorCode.TICKET_QUERY_NOT_SUPPORTED);
  }

  /** A missing project keeps its own typed code — the gate does not swallow it. */
  @Test
  void unknownProjectPropagatesProjectNotFound() {
    when(projectManagementService.getProject(PROJECT_ID))
        .thenThrow(new DomainException(DomainErrorCode.PROJECT_NOT_FOUND, "nope"));

    assertThatThrownBy(() -> service.queryCandidateTickets(PROJECT_ID, TicketQuery.unfiltered()))
        .isInstanceOf(DomainException.class)
        .extracting(failure -> ((DomainException) failure).errorCode())
        .isEqualTo(DomainErrorCode.PROJECT_NOT_FOUND);
  }

  /** AC7/Task 9 — the capability-skip branch logs at WARN; filter values never appear. */
  @Test
  void capabilitySkipLogsAtWarnAndNeverLogsFilterValues() {
    Project project = project();
    TicketSourceAdapter adapter = mock(TicketSourceAdapter.class);
    when(projectManagementService.getProject(PROJECT_ID)).thenReturn(project);
    when(resolver.findTicketSource(project)).thenReturn(Optional.of(adapter));
    when(adapter.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());

    Logger logger = (Logger) LoggerFactory.getLogger(TicketQueryService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThatThrownBy(
              () ->
                  service.queryCandidateTickets(
                      PROJECT_ID,
                      new TicketQuery("secret-account", List.of("secret-comp"), null, 5)))
          .isInstanceOf(DomainException.class);

      assertThat(appender.list)
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("ticket_query unsupported")
                      && event.getFormattedMessage().contains("reason=query_not_supported"));
      assertThat(appender.list)
          .noneMatch(
              event ->
                  event.getFormattedMessage().contains("secret-account")
                      || event.getFormattedMessage().contains("secret-comp"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  /**
   * Code-review D1 — an unreachable source is a RETRYABLE 503 code, not an opaque 500. Before this
   * translation the adapter exception fell through to {@code @ExceptionHandler(Exception.class)}.
   */
  @Test
  void networkFailureBecomesRetryableSourceUnavailable() {
    TicketSourceAdapter adapter = supportedAdapter();
    when(adapter.queryTickets(any()))
        .thenThrow(
            new TicketSourceAdapterException(
                IntegrationFailureCategory.NETWORK_API_FAILURE, "connection reset"));

    assertThatThrownBy(() -> service.queryCandidateTickets(PROJECT_ID, TicketQuery.unfiltered()))
        .isInstanceOf(DomainException.class)
        .extracting(failure -> ((DomainException) failure).errorCode())
        .isEqualTo(DomainErrorCode.TICKET_QUERY_SOURCE_UNAVAILABLE);
  }

  /**
   * An expired/insufficient credential is the most likely production failure on this endpoint. It
   * must be a NON-retryable 502 — retrying a dead token forever cannot help.
   */
  @Test
  void linkFailureBecomesNonRetryableSourceFailed() {
    TicketSourceAdapter adapter = supportedAdapter();
    when(adapter.queryTickets(any()))
        .thenThrow(
            new TicketSourceAdapterException(IntegrationFailureCategory.LINK_FAILURE, "401"));

    assertThatThrownBy(() -> service.queryCandidateTickets(PROJECT_ID, TicketQuery.unfiltered()))
        .isInstanceOf(DomainException.class)
        .extracting(failure -> ((DomainException) failure).errorCode())
        .isEqualTo(DomainErrorCode.TICKET_QUERY_SOURCE_FAILED);
  }

  /** A response we cannot map is the source's fault, not the network's. Non-retryable 502. */
  @Test
  void syncFailureBecomesNonRetryableSourceFailed() {
    TicketSourceAdapter adapter = supportedAdapter();
    when(adapter.queryTickets(any()))
        .thenThrow(
            new TicketSourceAdapterException(
                IntegrationFailureCategory.SYNC_FAILURE, "missing required field: summary"));

    assertThatThrownBy(() -> service.queryCandidateTickets(PROJECT_ID, TicketQuery.unfiltered()))
        .isInstanceOf(DomainException.class)
        .extracting(failure -> ((DomainException) failure).errorCode())
        .isEqualTo(DomainErrorCode.TICKET_QUERY_SOURCE_FAILED);
  }

  /** The original adapter exception is preserved as the cause — the stack trace is not lost. */
  @Test
  void sourceFailurePreservesTheAdapterExceptionAsCause() {
    TicketSourceAdapter adapter = supportedAdapter();
    TicketSourceAdapterException cause =
        new TicketSourceAdapterException(IntegrationFailureCategory.NETWORK_API_FAILURE, "timeout");
    when(adapter.queryTickets(any())).thenThrow(cause);

    assertThatThrownBy(() -> service.queryCandidateTickets(PROJECT_ID, TicketQuery.unfiltered()))
        .isInstanceOf(DomainException.class)
        .hasCause(cause);
  }

  /**
   * AC7 on the failure branch: the adapter's message may quote a source response body, so it must
   * never reach the log. Category, project id and connector kind only.
   */
  @Test
  void sourceFailureLogsCategoryAtWarnButNeverTheAdapterMessageOrFilterValues() {
    TicketSourceAdapter adapter = supportedAdapter();
    when(adapter.queryTickets(any()))
        .thenThrow(
            new TicketSourceAdapterException(
                IntegrationFailureCategory.LINK_FAILURE, "token sk-secret-leaked rejected"));

    Logger logger = (Logger) LoggerFactory.getLogger(TicketQueryService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThatThrownBy(
              () ->
                  service.queryCandidateTickets(
                      PROJECT_ID,
                      new TicketQuery("secret-account", List.of("secret-comp"), null, 5)))
          .isInstanceOf(DomainException.class);

      assertThat(appender.list)
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("ticket_query source_failure")
                      && event.getFormattedMessage().contains("failureCategory=link_failure")
                      && event.getFormattedMessage().contains("retryable=false"));
      assertThat(appender.list)
          .noneMatch(
              event ->
                  event.getFormattedMessage().contains("sk-secret-leaked")
                      || event.getFormattedMessage().contains("secret-account")
                      || event.getFormattedMessage().contains("secret-comp"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  /** Task 9 — the success path logs a result count at INFO, never the ticket free-text. */
  @Test
  void successPathLogsResultCountButNeverTicketText() {
    Project project = project();
    TicketSourceAdapter adapter = mock(TicketSourceAdapter.class);
    when(projectManagementService.getProject(PROJECT_ID)).thenReturn(project);
    when(resolver.findTicketSource(project)).thenReturn(Optional.of(adapter));
    when(adapter.getCapabilities()).thenReturn(TicketSourceCapabilities.jiraDefaults());
    when(adapter.queryTickets(any()))
        .thenReturn(
            TicketQueryResult.complete(
                List.of(
                    new TicketSummary(TicketRef.of("PROJ-1"), "Secret headline", "Secret body"))));

    Logger logger = (Logger) LoggerFactory.getLogger(TicketQueryService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      service.queryCandidateTickets(PROJECT_ID, TicketQuery.unfiltered());

      assertThat(appender.list)
          .anyMatch(
              event ->
                  event.getLevel() == Level.INFO
                      && event.getFormattedMessage().contains("ticket_query resolved")
                      && event.getFormattedMessage().contains("resultCount=1"));
      assertThat(appender.list)
          .noneMatch(
              event ->
                  event.getFormattedMessage().contains("Secret headline")
                      || event.getFormattedMessage().contains("Secret body"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  /** A resolvable, query-capable adapter wired to {@link #PROJECT_ID}. */
  private TicketSourceAdapter supportedAdapter() {
    Project project = project();
    TicketSourceAdapter adapter = mock(TicketSourceAdapter.class);
    when(projectManagementService.getProject(PROJECT_ID)).thenReturn(project);
    when(resolver.findTicketSource(project)).thenReturn(Optional.of(adapter));
    when(adapter.getCapabilities()).thenReturn(TicketSourceCapabilities.jiraDefaults());
    return adapter;
  }

  private static Project project() {
    return new Project(
        PROJECT_ID,
        "Default",
        "default",
        ProjectStatus.ACTIVE,
        null,
        ConnectorKind.JIRA,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }
}
