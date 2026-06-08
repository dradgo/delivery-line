package org.dradgo.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.integration.linear.LinearAdapter;
import org.dradgo.application.integration.linear.LinearAdapterException;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.NewIntegrationLink;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

class IntegrationLinkServiceUnitTest {

  private static final String RUN_ID = "run_unit12345678";
  private static final String OTHER_RUN_ID = "run_unitOTHER123";
  private static final String TICKET_REF = "LIN-101";
  private static final String IDEMPOTENCY_KEY = "linkTicket:run_unit12345678:LIN-101";
  private static final ActorContext ACTOR =
      new ActorContext("amelia@local", ActorType.HUMAN, "corr-1");

  private IntegrationLinkRecordPort port;
  private LinearAdapter linearAdapter;
  private IdempotencyService idempotencyService;
  private RedactionPolicyService redactionService;
  private IntegrationLinkService service;

  @BeforeEach
  void setUp() {
    port = org.mockito.Mockito.mock(IntegrationLinkRecordPort.class);
    linearAdapter = org.mockito.Mockito.mock(LinearAdapter.class);
    idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    redactionService = org.mockito.Mockito.mock(RedactionPolicyService.class);
    service =
        new IntegrationLinkService(
            port, linearAdapter, idempotencyService, redactionService, callthroughTemplate());
  }

  @Test
  void happyPathReservesFetchesLocksRedactsInsertsAndCompletes() {
    when(idempotencyService.checkAndReserve(
            eq(IDEMPOTENCY_KEY), anyString(), eq(ACTOR.actorIdentity()), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    LinearTicket ticket = sampleTicket();
    when(linearAdapter.fetchTicketByReference(TICKET_REF)).thenReturn(Optional.of(ticket));
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.empty());
    when(redactionService.redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value())))
        .thenReturn(sampleRedactionResult());

    IntegrationLink inserted = sampleLink("ilk_inserted000001", RUN_ID);
    when(port.insert(any(NewIntegrationLink.class))).thenReturn(inserted);

    IntegrationLink result = service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY);

    assertEquals("ilk_inserted000001", result.publicId());
    verify(port).insert(any(NewIntegrationLink.class));
    verify(redactionService)
        .redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value()));
    verify(idempotencyService)
        .complete(
            "linkTicket:run_unit12345678:LIN-101",
            "ilk_inserted000001",
            IdempotencyRecordStatus.COMPLETED);
  }

  @Test
  void replayReturnsPriorRowWithoutSideEffects() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, "ilk_prior00000001"));
    IntegrationLink prior = sampleLink("ilk_prior00000001", RUN_ID);
    when(port.findByPublicId("ilk_prior00000001")).thenReturn(Optional.of(prior));

    IntegrationLink result = service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY);

    assertEquals("ilk_prior00000001", result.publicId());
    verify(linearAdapter, never()).fetchTicketByReference(anyString());
    verify(port, never()).insert(any());
    verify(idempotencyService, never()).complete(anyString(), anyString(), any());
  }

  @Test
  void replayWithNullResultRefRaisesTerminalFailureKeyConflict() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, null));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    assertEquals("prior_attempt_failed_terminally", error.details().get("reason"));
    verify(linearAdapter, never()).fetchTicketByReference(anyString());
  }

  @Test
  void replayWithMissingRecordRaisesInternalError() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, "ilk_gone00000001"));
    when(port.findByPublicId("ilk_gone00000001")).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY));

    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
  }

  @Test
  void ticketNotFoundCompletesReservationAsFailedAndRaisesLinearTicketNotFound() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(linearAdapter.fetchTicketByReference(TICKET_REF)).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY));

    assertEquals(DomainErrorCode.LINEAR_TICKET_NOT_FOUND, error.errorCode());
    verify(idempotencyService)
        .complete(eq(IDEMPOTENCY_KEY), eq(null), eq(IdempotencyRecordStatus.FAILED));
    verify(port, never()).insert(any());
  }

  @Test
  void sameRunReLinkIsAnIdempotentNoOpReturningExisting() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    IntegrationLink existing = sampleLink("ilk_existing00001", RUN_ID);
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.of(existing));

    IntegrationLink result = service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY);

    assertEquals("ilk_existing00001", result.publicId());
    verify(linearAdapter, never()).fetchTicketByReference(anyString());
    verify(port, never()).insert(any());
    verify(idempotencyService)
        .complete(IDEMPOTENCY_KEY, "ilk_existing00001", IdempotencyRecordStatus.COMPLETED);
  }

  @Test
  void crossRunConflictRaisesIntegrationLinkConflictAndMarksReservationFailed() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    IntegrationLink other = sampleLink("ilk_other000000001", OTHER_RUN_ID);
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.of(other));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY));

    assertEquals(DomainErrorCode.INTEGRATION_LINK_CONFLICT, error.errorCode());
    assertEquals(OTHER_RUN_ID, error.details().get("existingRunPublicId"));
    verify(linearAdapter, never()).fetchTicketByReference(anyString());
    verify(idempotencyService).complete(IDEMPOTENCY_KEY, null, IdempotencyRecordStatus.FAILED);
    verify(port, never()).insert(any());
  }

  @Test
  void adapterFailurePropagatesAsIntegrationLinkConflictCarryingCategory() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.empty());
    when(linearAdapter.fetchTicketByReference(TICKET_REF))
        .thenThrow(
            new LinearAdapterException(
                IntegrationFailureCategory.NETWORK_API_FAILURE, "rate limited"));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY));

    assertEquals(DomainErrorCode.INTEGRATION_LINK_CONFLICT, error.errorCode());
    assertEquals(
        IntegrationFailureCategory.NETWORK_API_FAILURE.value(),
        error.details().get("failureCategory"));
    verify(idempotencyService).complete(IDEMPOTENCY_KEY, null, IdempotencyRecordStatus.FAILED);
  }

  @Test
  void redactionAppliesShareableRedactedClassification() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(linearAdapter.fetchTicketByReference(TICKET_REF)).thenReturn(Optional.of(sampleTicket()));
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.empty());
    when(redactionService.redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value())))
        .thenReturn(sampleRedactionResult());
    when(port.insert(any(NewIntegrationLink.class)))
        .thenReturn(sampleLink("ilk_inserted000001", RUN_ID));

    service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY);

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(redactionService)
        .redact(captor.capture(), eq(DataClassification.SHAREABLE_REDACTED.value()));
    Map<String, Object> sent = captor.getValue();
    assertTrue(sent.containsKey("title"));
    assertTrue(sent.containsKey("summary"));
    assertTrue(sent.containsKey("labels"));
    assertTrue(sent.containsKey("authorIdentity"));
  }

  @Test
  void insertPayloadContainsBytesAndPropagatesPublicId() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(linearAdapter.fetchTicketByReference(TICKET_REF)).thenReturn(Optional.of(sampleTicket()));
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.empty());
    when(redactionService.redact(any(Map.class), anyString())).thenReturn(sampleRedactionResult());
    when(port.insert(any(NewIntegrationLink.class)))
        .thenReturn(sampleLink("ilk_inserted000001", RUN_ID));

    service.linkTicket(RUN_ID, TICKET_REF, ACTOR, IDEMPOTENCY_KEY);

    ArgumentCaptor<NewIntegrationLink> captor = ArgumentCaptor.forClass(NewIntegrationLink.class);
    verify(port).insert(captor.capture());
    NewIntegrationLink sent = captor.getValue();
    assertEquals("linear", sent.integrationType());
    assertEquals(TICKET_REF, sent.externalRef());
    assertEquals(RUN_ID, sent.workflowRunPublicId());
    assertNotNull(sent.externalMetadata());
    assertTrue(sent.externalMetadata().length > 0);
  }

  @Test
  void fingerprintIsStableAndIntegrationTypeSensitive() {
    String a = IntegrationLinkService.computeFingerprint("linear", "LIN-1", "run_a");
    String b = IntegrationLinkService.computeFingerprint("linear", "LIN-1", "run_a");
    String differentRun = IntegrationLinkService.computeFingerprint("linear", "LIN-1", "run_b");
    String differentType = IntegrationLinkService.computeFingerprint("github_pr", "LIN-1", "run_a");
    assertEquals(a, b);
    assertEquals(64, a.length()); // SHA-256 hex string
    assertTrue(!a.equals(differentRun));
    assertTrue(!a.equals(differentType));
  }

  private static LinearTicket sampleTicket() {
    return new LinearTicket(
        TICKET_REF,
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
    ObjectMapper mapper = new ObjectMapper();
    return new RedactionResult(
        null,
        mapper.createObjectNode().put("title", "Add caching").put("summary", "Bounded feature"),
        DataClassification.SHAREABLE_REDACTED,
        DataClassification.SHAREABLE_REDACTED,
        false,
        java.util.Set.of());
  }

  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = org.mockito.Mockito.mock(TransactionTemplate.class);
    org.mockito.Mockito.when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              org.springframework.transaction.support.TransactionCallback<?> callback =
                  invocation.getArgument(0);
              return callback.doInTransaction(null);
            });
    return template;
  }
}
