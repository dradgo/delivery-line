package org.dradgo.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.integration.linear.LinearAutoIngestProperties;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.NewIntegrationLink;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

class IntegrationLoggingContractTest {

  private static final String RUN_ID = "run_log12345678";
  private static final String TICKET_REF = "LIN-101";
  private static final String IDEMPOTENCY_KEY = "linkTicket:run_log12345678:LIN-101";
  private static final ActorContext ACTOR =
      new ActorContext("amelia@local", ActorType.HUMAN, "corr-log");

  private final ListAppender<ILoggingEvent> serviceAppender =
      attachListAppender(IntegrationLinkService.class);
  private final ListAppender<ILoggingEvent> pollingAppender =
      attachListAppender(LinearPollingHost.class);

  @AfterEach
  void detachAppenders() {
    detach(IntegrationLinkService.class, serviceAppender);
    detach(LinearPollingHost.class, pollingAppender);
  }

  @Test
  void linkTicketHappyPathEmitsEntryAndSuccessLogsWithContext() {
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    TicketSourceAdapter linearAdapter = mock(TicketSourceAdapter.class);
    IdempotencyService idempotencyService = mock(IdempotencyService.class);
    RedactionPolicyService redactionService = mock(RedactionPolicyService.class);
    IntegrationLinkService service =
        new IntegrationLinkService(
            port,
            linearAdapter,
            idempotencyService,
            redactionService,
            gitHubAdapterProvider(null),
            mock(WorkflowEventWritePort.class),
            callthroughTemplate());

    when(idempotencyService.checkAndReserve(
            eq(IDEMPOTENCY_KEY), anyString(), eq(ACTOR.actorIdentity()), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.empty());
    when(linearAdapter.fetchTicketByReference(TicketRef.of(TICKET_REF)))
        .thenReturn(Optional.of(sampleTicket()));
    // Story 3g-1 — capability-gated URL resolution runs on the happy path; the URL is never logged.
    when(linearAdapter.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());
    when(linearAdapter.buildSourceTicketUrl(any())).thenReturn(Optional.empty());
    when(redactionService.redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value())))
        .thenReturn(sampleRedactionResult());
    when(port.insert(any(NewIntegrationLink.class)))
        .thenReturn(sampleLink("ilk_log12345678", RUN_ID));

    service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY);

    assertContains(serviceAppender.list, Level.INFO, "linkTicket entry");
    assertContains(serviceAppender.list, Level.INFO, "workflowRunId=" + RUN_ID);
    assertContains(serviceAppender.list, Level.INFO, "linkTicket success");
    assertContains(serviceAppender.list, Level.INFO, "integrationLinkPublicId=ilk_log12345678");
    assertTrue(
        serviceAppender.list.stream()
            .noneMatch(event -> event.getFormattedMessage().contains(IDEMPOTENCY_KEY)),
        "idempotency keys must not reach the log surface");
    assertTrue(
        serviceAppender.list.stream()
            .noneMatch(event -> event.getFormattedMessage().contains(TICKET_REF)),
        "raw external ticket references must not reach the log surface");
  }

  @Test
  void linkTicketLogsOriginUrlPresenceFlagButNeverTheUrlItself() {
    // Story 3g-1 (AC5) — the success log records only a boolean presence flag; the source URL
    // (which may carry sensitive query material) is never written to the log surface.
    String sourceUrl = "https://linear.app/issue/LIN-101";
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    TicketSourceAdapter linearAdapter = mock(TicketSourceAdapter.class);
    IdempotencyService idempotencyService = mock(IdempotencyService.class);
    RedactionPolicyService redactionService = mock(RedactionPolicyService.class);
    IntegrationLinkService service =
        new IntegrationLinkService(
            port,
            linearAdapter,
            idempotencyService,
            redactionService,
            gitHubAdapterProvider(null),
            mock(WorkflowEventWritePort.class),
            callthroughTemplate());

    when(idempotencyService.checkAndReserve(
            eq(IDEMPOTENCY_KEY), anyString(), eq(ACTOR.actorIdentity()), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.empty());
    when(linearAdapter.fetchTicketByReference(TicketRef.of(TICKET_REF)))
        .thenReturn(Optional.of(sampleTicket()));
    when(linearAdapter.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());
    when(linearAdapter.buildSourceTicketUrl(any())).thenReturn(Optional.of(sourceUrl));
    when(redactionService.redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value())))
        .thenReturn(sampleRedactionResult());
    when(port.insert(any(NewIntegrationLink.class)))
        .thenReturn(sampleLink("ilk_log12345678", RUN_ID));

    service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY);

    assertContains(serviceAppender.list, Level.INFO, "originUrlSnapshotted=true");
    assertTrue(
        serviceAppender.list.stream()
            .noneMatch(event -> event.getFormattedMessage().contains(sourceUrl)),
        "the source ticket URL must never reach the log surface");
  }

  @Test
  void linkTicketTicketNotFoundEmitsWarnWithoutInsert() {
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    TicketSourceAdapter linearAdapter = mock(TicketSourceAdapter.class);
    IdempotencyService idempotencyService = mock(IdempotencyService.class);
    RedactionPolicyService redactionService = mock(RedactionPolicyService.class);
    IntegrationLinkService service =
        new IntegrationLinkService(
            port,
            linearAdapter,
            idempotencyService,
            redactionService,
            gitHubAdapterProvider(null),
            mock(WorkflowEventWritePort.class),
            callthroughTemplate());

    when(idempotencyService.checkAndReserve(
            eq(IDEMPOTENCY_KEY), anyString(), eq(ACTOR.actorIdentity()), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.empty());
    when(linearAdapter.fetchTicketByReference(TicketRef.of(TICKET_REF)))
        .thenReturn(Optional.empty());

    try {
      service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY);
    } catch (DomainException expected) {
      // log assertion below
    }

    assertContains(serviceAppender.list, Level.WARN, "linkTicket ticket_not_found");
    assertTrue(
        serviceAppender.list.stream()
            .noneMatch(event -> event.getFormattedMessage().contains(TICKET_REF)),
        "raw external ticket references must not reach the log surface");
  }

  @Test
  void pollingFailureEmitsWarnWithCategoryAndPreservesCursor() {
    TicketSourceAdapter linearAdapter = mock(TicketSourceAdapter.class);
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-13T12:00:00Z"), ZoneOffset.UTC);
    LinearPollingHost host =
        new LinearPollingHost(
            linearAdapter,
            port,
            new org.dradgo.application.idempotency.UuidV7Generator(),
            mock(WorkflowCommandService.class),
            LinearAutoIngestProperties.defaults(),
            clock);
    when(linearAdapter.pollNewTickets(Instant.parse("2026-05-13T12:00:00Z")))
        .thenThrow(
            new TicketSourceAdapterException(
                IntegrationFailureCategory.NETWORK_API_FAILURE, "rate limited"));

    host.pollLinear();

    assertContains(pollingAppender.list, Level.WARN, "linear_real polling_failed");
    assertContains(pollingAppender.list, Level.WARN, "category=network_api_failure");
    assertTrue(
        host.lastPollAt().equals(Instant.parse("2026-05-13T12:00:00Z")),
        "poll failure must preserve the existing cursor");

    // Story 1.19 backfill: LinearPollingHost.pollLinear stamps a fresh correlationId per
    // cycle. Each captured event MUST carry the correlationId MDC key.
    ILoggingEvent pollingFailure =
        pollingAppender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("linear_real polling_failed"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        pollingFailure.getMDCPropertyMap().get("correlationId") != null,
        "correlationId MDC key must be stamped on every polling log line");
  }

  @Test
  void pollingTouchFailurePreservesCursor() {
    TicketSourceAdapter linearAdapter = mock(TicketSourceAdapter.class);
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-13T12:00:00Z"), ZoneOffset.UTC);
    LinearPollingHost host =
        new LinearPollingHost(
            linearAdapter,
            port,
            new org.dradgo.application.idempotency.UuidV7Generator(),
            mock(WorkflowCommandService.class),
            LinearAutoIngestProperties.defaults(),
            clock);
    Ticket ticket =
        new Ticket(
            TicketRef.of("LIN-102"),
            "Add retry",
            "Persist touch path",
            "dev@example.com",
            Instant.parse("2026-05-12T08:00:00Z"),
            Instant.parse("2026-05-13T12:05:00Z"),
            Map.of(),
            null,
            null);
    when(linearAdapter.pollNewTickets(Instant.parse("2026-05-13T12:00:00Z")))
        .thenReturn(List.of(ticket));
    when(port.touchLastSyncAtByTypeAndExternalRef("linear", "LIN-102", ticket.updatedAt()))
        .thenThrow(new IllegalStateException("db unavailable"));

    host.pollLinear();

    assertTrue(
        host.lastPollAt().equals(Instant.parse("2026-05-13T12:00:00Z")),
        "touch failure must preserve the existing cursor");
    assertContains(pollingAppender.list, Level.WARN, "linear_real polling_cursor_preserved");
  }

  @Test
  void seedWatermarkFallsBackToSafeFloorWhenLookupFails() {
    TicketSourceAdapter linearAdapter = mock(TicketSourceAdapter.class);
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-13T12:00:00Z"), ZoneOffset.UTC);
    LinearPollingHost host =
        new LinearPollingHost(
            linearAdapter,
            port,
            new org.dradgo.application.idempotency.UuidV7Generator(),
            mock(WorkflowCommandService.class),
            LinearAutoIngestProperties.defaults(),
            clock);
    when(port.findMaxLastSyncAtForType("linear"))
        .thenThrow(new IllegalStateException("db unavailable"));

    host.seedWatermark();

    assertTrue(
        host.lastPollAt().equals(Instant.EPOCH), "seed failure must fall back to the safe floor");
    assertContains(pollingAppender.list, Level.WARN, "linear_real polling_watermark seed_failed");
    verifyNoInteractions(linearAdapter);
  }

  @Test
  void linkGitHubPrHappyPathEmitsEntryAndSuccessLogsWithContext() {
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    RepositoryHostAdapter gitHubAdapter = mock(RepositoryHostAdapter.class);
    IntegrationLinkService service = githubService(port, gitHubAdapter);

    when(gitHubAdapter.getPullRequestByRef(PullRequestRef.of("octo/hello#42")))
        .thenReturn(Optional.of(samplePullRequest("open")));
    when(port.findActiveByTypeAndExternalRefForUpdate("github_pr", "octo/hello#42"))
        .thenReturn(Optional.empty());
    when(port.findActiveByTypeAndWorkflowRunForUpdate("github_pr", RUN_ID))
        .thenReturn(Optional.empty());
    when(port.insert(any(NewIntegrationLink.class)))
        .thenReturn(sampleGithubLink("ilk_log000000001"));

    service.linkPullRequest(
        RUN_ID, "octo/hello#42", "octo/hello", "feature/x", "abc1234", ACTOR, "k");

    assertContains(serviceAppender.list, Level.INFO, "linkGitHubPr entry");
    assertContains(serviceAppender.list, Level.INFO, "linkGitHubPr success");
    assertContains(serviceAppender.list, Level.INFO, "integrationLinkPublicId=ilk_log000000001");
  }

  @Test
  void linkGitHubPrCrossRunConflictEmitsWarn() {
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    RepositoryHostAdapter gitHubAdapter = mock(RepositoryHostAdapter.class);
    IntegrationLinkService service = githubService(port, gitHubAdapter);

    when(gitHubAdapter.getPullRequestByRef(PullRequestRef.of("octo/hello#42")))
        .thenReturn(Optional.of(samplePullRequest("open")));
    when(port.findActiveByTypeAndExternalRefForUpdate("github_pr", "octo/hello#42"))
        .thenReturn(Optional.of(sampleGithubLink("ilk_other0000001", "run_otherRUN1234")));

    try {
      service.linkPullRequest(
          RUN_ID, "octo/hello#42", "octo/hello", "feature/x", "abc1234", ACTOR, "k");
    } catch (DomainException expected) {
      // log assertion below
    }

    assertContains(serviceAppender.list, Level.WARN, "linkGitHubPr cross_run_conflict");
  }

  @Test
  void assertArtifactPrLinkMatchesDriftEmitsWarn() {
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    IntegrationLinkService service = githubService(port, mock(RepositoryHostAdapter.class));
    when(port.findActiveByTypeAndWorkflowRunForUpdate("github_pr", RUN_ID))
        .thenReturn(Optional.of(sampleGithubLink("ilk_drift0000001")));

    try {
      service.assertArtifactPrLinkMatches(RUN_ID, "PR-99");
    } catch (DomainException expected) {
      // log assertion below
    }

    assertContains(serviceAppender.list, Level.WARN, "assertArtifactPrLinkMatches mismatch");
  }

  private static IntegrationLinkService githubService(
      IntegrationLinkRecordPort port, RepositoryHostAdapter gitHubAdapter) {
    TicketSourceAdapter linearAdapter = mock(TicketSourceAdapter.class);
    IdempotencyService idempotencyService = mock(IdempotencyService.class);
    RedactionPolicyService redactionService = mock(RedactionPolicyService.class);
    when(redactionService.redact(any(Map.class), any())).thenReturn(sampleRedactionResult());
    return new IntegrationLinkService(
        port,
        linearAdapter,
        idempotencyService,
        redactionService,
        gitHubAdapterProvider(gitHubAdapter),
        mock(WorkflowEventWritePort.class),
        callthroughTemplate());
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<RepositoryHostAdapter> gitHubAdapterProvider(
      RepositoryHostAdapter adapter) {
    ObjectProvider<RepositoryHostAdapter> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(adapter);
    return provider;
  }

  private static PullRequest samplePullRequest(String state) {
    return new PullRequest(
        PullRequestRef.of("octo/hello#42"),
        RepositoryRef.of("octo/hello"),
        42,
        "feature/x",
        state,
        false,
        "https://github.com/octo/hello/pull/42",
        Instant.parse("2026-05-01T10:00:00Z"));
  }

  private static IntegrationLink sampleGithubLink(String publicId) {
    return sampleGithubLink(publicId, RUN_ID);
  }

  private static IntegrationLink sampleGithubLink(String publicId, String runId) {
    Instant now = Instant.parse("2026-04-25T10:00:00Z");
    return new IntegrationLink(
        publicId,
        runId,
        "github_pr",
        "octo/hello#42",
        IntegrationSyncStatus.LINKED,
        now,
        now,
        null);
  }

  private static void assertContains(List<ILoggingEvent> events, Level level, String fragment) {
    assertTrue(
        events.stream()
            .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment)),
        "expected " + level + " log containing '" + fragment + "'; events=" + events);
  }

  private static Ticket sampleTicket() {
    return new Ticket(
        TicketRef.of(TICKET_REF),
        "Add caching",
        "Bounded feature for the worker pool",
        "dev@example.com",
        Instant.parse("2026-04-20T08:00:00Z"),
        Instant.parse("2026-04-21T08:00:00Z"),
        Map.of("type", "feature"),
        null,
        null);
  }

  private static IntegrationLink sampleLink(String publicId, String workflowRunPublicId) {
    Instant now = Instant.parse("2026-04-25T10:00:00Z");
    return new IntegrationLink(
        publicId,
        workflowRunPublicId,
        "linear",
        TICKET_REF,
        IntegrationSyncStatus.LINKED,
        now,
        now,
        null);
  }

  private static RedactionResult sampleRedactionResult() {
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    return new RedactionResult(
        null,
        mapper.createObjectNode().put("title", "Add caching").put("summary", "Bounded feature"),
        DataClassification.SHAREABLE_REDACTED,
        DataClassification.SHAREABLE_REDACTED,
        false,
        java.util.Set.of());
  }

  private static ListAppender<ILoggingEvent> attachListAppender(Class<?> loggerClass) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
    logger.detachAppender(appender);
  }

  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              org.springframework.transaction.support.TransactionCallback<?> callback =
                  invocation.getArgument(0);
              return callback.doInTransaction(null);
            });
    return template;
  }
}
