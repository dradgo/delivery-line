package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.Optional;
import org.dradgo.adapters.integration.ticketsource.linear.LinearMockAdapter;
import org.dradgo.adapters.integration.ticketsource.linear.LinearMockScenario;
import org.dradgo.adapters.integration.ticketsource.linear.LinearMockScenarioRegistry;
import org.dradgo.adapters.integration.ticketsource.linear.LinearRealAdapter;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.integration.linear.LinearProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Foundation contract #14 (story 3.32 AC9) — the {@code TicketSourceAdapter} abstraction. The
 * Linear mock + real adapters implement the same vendor-neutral {@link TicketSourceAdapter} port;
 * this contract asserts (a) both implementations satisfy the port, (b) a happy read returns a
 * neutral {@link Ticket} in both (the real one against {@link MockRestServiceServer}-stubbed HTTP),
 * (c) a classified failure surfaces the same {@link IntegrationFailureCategory} in both, and (d) a
 * source declaring {@code supportsCommentOnTicket=false} makes {@code syncCompletionToLinear} skip
 * gracefully (capability-driven degradation, AC3). Mirrors {@code
 * GitHubMockVsRealParityFoundationContract} (Contract #11).
 */
@Tag("foundation-gate")
class TicketSourceAbstractionFoundationContract {

  private static final String BASE_URL = "https://api.linear.app/graphql";

  private final LinearMockAdapter mock = new LinearMockAdapter(new LinearMockScenarioRegistry());

  @Test
  void bothAdaptersImplementTheSamePort() {
    assertTrue(
        TicketSourceAdapter.class.isAssignableFrom(LinearMockAdapter.class),
        tag("LinearMockAdapter must implement TicketSourceAdapter"));
    assertTrue(
        TicketSourceAdapter.class.isAssignableFrom(LinearRealAdapter.class),
        tag("LinearRealAdapter must implement TicketSourceAdapter"));
  }

  @Test
  void happyReadReturnsNeutralTicketInBoth() {
    Optional<Ticket> mockTicket =
        mock.fetchTicketByReference(
            TicketRef.of(LinearMockScenarioRegistry.TICKET_FEATURE_LOW_RISK));
    assertTrue(mockTicket.isPresent(), tag("mock happy read present"));
    assertInstanceOf(Ticket.class, mockTicket.get());

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(ISSUE_RESPONSE, MediaType.APPLICATION_JSON));
    Optional<Ticket> realTicket = harness.adapter.fetchTicketByReference(TicketRef.of("LIN-501"));
    assertTrue(realTicket.isPresent(), tag("real happy read present"));
    assertInstanceOf(Ticket.class, realTicket.get());
    assertEquals("LIN-501", realTicket.get().ticketRef().value(), tag("real ticketRef mapped"));
    harness.server.verify();
  }

  @Test
  void classifiedFailureSurfacesSameCategoryInBoth() {
    LinearMockScenarioRegistry registry = new LinearMockScenarioRegistry();
    registry.register(
        new LinearMockScenario(
            "TEST-RATE",
            LinearMockScenario.Behaviour.RATE_LIMITED,
            null,
            IntegrationFailureCategory.NETWORK_API_FAILURE));
    LinearMockAdapter rateLimitedMock = new LinearMockAdapter(registry);
    IntegrationFailureCategory mockCategory =
        assertThrows(
                TicketSourceAdapterException.class,
                () -> rateLimitedMock.fetchTicketByReference(TicketRef.of("TEST-RATE")))
            .failureCategory();

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    IntegrationFailureCategory realCategory =
        assertThrows(
                TicketSourceAdapterException.class,
                () -> harness.adapter.fetchTicketByReference(TicketRef.of("LIN-429")))
            .failureCategory();

    assertEquals(
        IntegrationFailureCategory.NETWORK_API_FAILURE, mockCategory, tag("mock category"));
    assertEquals(mockCategory, realCategory, tag("mock and real classify the same scenario alike"));
    harness.server.verify();
  }

  @Test
  void completionSyncSkipsGracefullyWhenSourceDeclaresNoCommentCapability() {
    // AC9 — inject a source declaring supportsCommentOnTicket=false and assert the completion sync
    // skips gracefully: no post, no event, the typed SKIPPED_NO_COMMENT_CAPABILITY outcome.
    TicketSourceAdapter incapable = mock(TicketSourceAdapter.class);
    when(incapable.getCapabilities()).thenReturn(new TicketSourceCapabilities(false, true, true));

    WorkflowEventWritePort eventWritePort = mock(WorkflowEventWritePort.class);
    WorkflowOrchestrationService service = serviceWith(incapable, eventWritePort);

    WorkflowOrchestrationService.SyncCompletionOutcome outcome =
        service.syncCompletionToLinear("run_foundation12");

    assertSame(
        WorkflowOrchestrationService.SyncCompletionOutcome.SKIPPED_NO_COMMENT_CAPABILITY,
        outcome,
        tag("incapable source must yield SKIPPED_NO_COMMENT_CAPABILITY"));
    verify(incapable, never())
        .postGovernedRunComment(any(TicketRef.class), any(GovernedRunComment.class));
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void verifyConnectivityProbeIsReachableAndAuthenticatedInBoth() {
    // Story 3c-8 (R1) — the net-new probe is present + deterministic on the mock and authenticates
    // cleanly on the real adapter against a stubbed 200 viewer response.
    ConnectivityResult mockResult = mock.verifyConnectivity(null);
    assertTrue(mockResult.reachable() && mockResult.authenticated(), tag("mock probe ok"));

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess("{\"data\":{\"viewer\":{\"id\":\"u1\"}}}", MediaType.APPLICATION_JSON));
    ConnectivityResult realResult = harness.adapter.verifyConnectivity(null);
    assertTrue(realResult.reachable() && realResult.authenticated(), tag("real probe ok"));
    harness.server.verify();
  }

  // -------- helpers ----------------------------------------------------------------------------

  private static WorkflowOrchestrationService serviceWith(
      TicketSourceAdapter adapter, WorkflowEventWritePort eventWritePort) {
    // Story 3c-7 — per-project ticket-source resolution replaces the global ObjectProvider seam.
    org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver =
        mock(org.dradgo.application.project.ProjectRuntimeConfigResolver.class);
    org.dradgo.application.project.ProjectConnectorResolver projectConnectorResolver =
        mock(org.dradgo.application.project.ProjectConnectorResolver.class);
    org.dradgo.domain.project.Project project =
        new org.dradgo.domain.project.Project(
            "prj_default0001",
            "Default",
            "default",
            org.dradgo.domain.registry.ProjectStatus.ACTIVE,
            null,
            org.dradgo.domain.registry.ConnectorKind.LINEAR,
            org.dradgo.domain.registry.ConnectorKind.GITHUB,
            false,
            null,
            false,
            null,
            java.time.OffsetDateTime.parse("2026-06-20T00:00:00Z"),
            null);
    when(projectRuntimeConfigResolver.resolveForRun(any())).thenReturn(project);
    when(projectConnectorResolver.findTicketSource(any())).thenReturn(Optional.of(adapter));

    IntegrationLinkService integrationLinkService = mock(IntegrationLinkService.class);
    when(integrationLinkService.findActiveLinearTicketLink("run_foundation12"))
        .thenReturn(Optional.of(linearLink()));

    RedactionPolicyService redaction = mock(RedactionPolicyService.class);
    when(redaction.redact(any(String.class), eq(DataClassification.SHAREABLE_FULL.value())))
        .thenAnswer(
            inv ->
                new RedactionResult(
                    inv.getArgument(0),
                    null,
                    DataClassification.SHAREABLE_FULL,
                    DataClassification.SHAREABLE_FULL,
                    false,
                    java.util.Set.of()));

    return new WorkflowOrchestrationService(
        mock(org.dradgo.application.runner.queue.RunnerExecutionQueue.class),
        mock(org.dradgo.application.runner.ManualExecutionDispatcher.class),
        mock(org.dradgo.application.workflow.WorkflowTransitionService.class),
        mock(org.dradgo.application.workflow.spi.WorkflowRunReadPort.class),
        mock(org.dradgo.application.runner.spi.RunnerExecutionRecordPort.class),
        org.dradgo.application.runner.RunnerProperties.defaults(),
        mock(org.dradgo.application.runner.ContextBundleService.class),
        projectRuntimeConfigResolver,
        projectConnectorResolver,
        redaction,
        WorkflowProperties.defaults(),
        integrationLinkService,
        mock(org.dradgo.application.artifact.spi.ArtifactRecordPort.class),
        mock(org.dradgo.application.approval.spi.ApprovalReadPort.class),
        mock(WorkflowEventReadPort.class),
        eventWritePort);
  }

  private static IntegrationLink linearLink() {
    return new IntegrationLink(
        "ilk_foundation01",
        "run_foundation12",
        "linear",
        "LIN-77",
        IntegrationSyncStatus.LINKED,
        Instant.parse("2026-06-02T10:00:00Z"),
        null,
        null);
  }

  private RealHarness realHarness() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    LinearProperties properties =
        new LinearProperties(
            "ln_parity_token",
            BASE_URL,
            60_000L,
            50,
            new LinearProperties.Timeout(5_000L, 30_000L),
            2.0d,
            new LinearProperties.Polling(true),
            null,
            null);
    return new RealHarness(server, new LinearRealAdapter(builder.build(), properties));
  }

  private static String tag(String detail) {
    return FoundationGateAssertions.tagged("3.32", "TicketSource abstraction: " + detail);
  }

  private static final String ISSUE_RESPONSE =
      """
      {
        "data": {
          "issues": {
            "nodes": [{
              "identifier": "LIN-501",
              "title": "Add caching layer",
              "description": "Bounded feature with low risk",
              "createdAt": "2026-05-01T10:00:00Z",
              "updatedAt": "2026-05-02T12:30:00Z",
              "creator": { "email": "dev@example.com", "displayName": "Dev" },
              "labels": { "nodes": [{ "name": "feature" }] },
              "state": { "id": "state-ready-uuid", "name": "Ready for Planning" }
            }]
          }
        }
      }
      """;

  /** Plain holder (public fields) so call-sites read {@code harness.server} / {@code .adapter}. */
  private static final class RealHarness {
    private final MockRestServiceServer server;
    private final LinearRealAdapter adapter;

    private RealHarness(MockRestServiceServer server, LinearRealAdapter adapter) {
      this.server = server;
      this.adapter = adapter;
    }
  }
}
