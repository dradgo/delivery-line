package org.dradgo.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.dradgo.application.integration.github.GitHubAdapter;
import org.dradgo.application.integration.github.GitHubPullRequest;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.NewIntegrationLink;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

class IntegrationLinkServiceUnitTest {

  private static final String RUN_ID = "run_unit12345678";
  private static final String OTHER_RUN_ID = "run_unitOTHER123";
  private static final String TICKET_REF = "LIN-101";
  private static final String IDEMPOTENCY_KEY = "linkTicket:run_unit12345678:LIN-101";
  private static final ActorContext ACTOR =
      new ActorContext("amelia@local", ActorType.HUMAN, "corr-1");

  // Story 3.15 — GitHub PR linkage fixtures (canonical owner/repo#n external_ref, Trap T1).
  private static final String GITHUB_TYPE = "github_pr";
  private static final String PR_REF = "octo/hello#42";
  private static final String OTHER_PR_REF = "octo/hello#43";
  private static final String REPO_REF = "octo/hello";
  private static final String BRANCH = "feature/x";
  private static final String COMMIT_SHA = "a1b2c3d4e5f6a7b8c9d0";
  private static final String GH_KEY = "linkGitHubPr:run_unit12345678:octo/hello#42";

  private IntegrationLinkRecordPort port;
  private TicketSourceAdapter linearAdapter;
  private IdempotencyService idempotencyService;
  private RedactionPolicyService redactionService;
  private GitHubAdapter gitHubAdapter;
  private WorkflowEventWritePort workflowEventWritePort;
  private IntegrationLinkService service;

  @BeforeEach
  void setUp() {
    port = org.mockito.Mockito.mock(IntegrationLinkRecordPort.class);
    linearAdapter = org.mockito.Mockito.mock(TicketSourceAdapter.class);
    idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    redactionService = org.mockito.Mockito.mock(RedactionPolicyService.class);
    gitHubAdapter = org.mockito.Mockito.mock(GitHubAdapter.class);
    workflowEventWritePort = org.mockito.Mockito.mock(WorkflowEventWritePort.class);
    service =
        new IntegrationLinkService(
            port,
            linearAdapter,
            idempotencyService,
            redactionService,
            gitHubAdapterProvider(gitHubAdapter),
            workflowEventWritePort,
            callthroughTemplate());
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<GitHubAdapter> gitHubAdapterProvider(GitHubAdapter adapter) {
    ObjectProvider<GitHubAdapter> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(adapter);
    return provider;
  }

  @Test
  void happyPathReservesFetchesLocksRedactsInsertsAndCompletes() {
    when(idempotencyService.checkAndReserve(
            eq(IDEMPOTENCY_KEY), anyString(), eq(ACTOR.actorIdentity()), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    Ticket ticket = sampleTicket();
    when(linearAdapter.fetchTicketByReference(TicketRef.of(TICKET_REF)))
        .thenReturn(Optional.of(ticket));
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
    verify(linearAdapter, never()).fetchTicketByReference(any());
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
    verify(linearAdapter, never()).fetchTicketByReference(any());
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
    when(linearAdapter.fetchTicketByReference(TicketRef.of(TICKET_REF)))
        .thenReturn(Optional.empty());

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
  void blankTicketRefRejectedBeforeReservation() {
    // Story 3.32 review finding — a blank (non-null) ticketRef must fail fast at method entry,
    // BEFORE the idempotency reservation, so no RESERVED record is left dangling (mirrors
    // linkGitHubPr's up-front non-blank guard). The old bare-String path passed blanks through to
    // the adapter; TicketRef.of(...) now rejects them, so the guard must precede checkAndReserve.
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.linkTicket(RUN_ID, "   ", ACTOR, IDEMPOTENCY_KEY));

    assertTrue(error.getMessage().contains("non-blank"));
    verify(idempotencyService, never())
        .checkAndReserve(anyString(), anyString(), anyString(), anyString());
    verify(linearAdapter, never()).fetchTicketByReference(any());
    verify(port, never()).findActiveByTypeAndExternalRefForUpdate(anyString(), anyString());
  }

  @Test
  void blankTicketRefRejectedBeforePortLookupWithinTransaction() {
    // Same up-front guard on the in-transaction variant — the guard must fire before the
    // pessimistic-lock port lookup (and well before TicketRef.of(...)), so a blank input never
    // touches the DB.
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.linkTicketWithinTransaction(RUN_ID, "", ACTOR));

    assertTrue(error.getMessage().contains("non-blank"));
    verify(port, never()).findActiveByTypeAndExternalRefForUpdate(anyString(), anyString());
    verify(linearAdapter, never()).fetchTicketByReference(any());
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
    verify(linearAdapter, never()).fetchTicketByReference(any());
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
    verify(linearAdapter, never()).fetchTicketByReference(any());
    verify(idempotencyService).complete(IDEMPOTENCY_KEY, null, IdempotencyRecordStatus.FAILED);
    verify(port, never()).insert(any());
  }

  @Test
  void adapterFailurePropagatesAsIntegrationLinkConflictCarryingCategory() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(port.findActiveByTypeAndExternalRefForUpdate("linear", TICKET_REF))
        .thenReturn(Optional.empty());
    when(linearAdapter.fetchTicketByReference(TicketRef.of(TICKET_REF)))
        .thenThrow(
            new TicketSourceAdapterException(
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
    when(linearAdapter.fetchTicketByReference(TicketRef.of(TICKET_REF)))
        .thenReturn(Optional.of(sampleTicket()));
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
    when(linearAdapter.fetchTicketByReference(TicketRef.of(TICKET_REF)))
        .thenReturn(Optional.of(sampleTicket()));
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

  // ===================================================================================
  // Story 3.15 — linkGitHubPr / syncGitHubPr / assertArtifactPrLinkMatches
  // ===================================================================================

  @Test
  void linkGitHubPrHappyPathInsertsGithubRowAndAppendsIntegrationLinkedEvent() {
    when(gitHubAdapter.getPullRequestByRef(PR_REF)).thenReturn(Optional.of(samplePullRequest()));
    when(port.findActiveByTypeAndExternalRefForUpdate(GITHUB_TYPE, PR_REF))
        .thenReturn(Optional.empty());
    when(port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, RUN_ID))
        .thenReturn(Optional.empty());
    when(redactionService.redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value())))
        .thenReturn(sampleRedactionResult());
    when(port.insert(any(NewIntegrationLink.class)))
        .thenReturn(sampleGithubLink("ilk_gh00000000001", RUN_ID, IntegrationSyncStatus.LINKED));

    IntegrationLink result =
        service.linkGitHubPr(RUN_ID, PR_REF, REPO_REF, BRANCH, COMMIT_SHA, ACTOR, GH_KEY);

    assertEquals("ilk_gh00000000001", result.publicId());
    ArgumentCaptor<NewIntegrationLink> captor = ArgumentCaptor.forClass(NewIntegrationLink.class);
    verify(port).insert(captor.capture());
    assertEquals(GITHUB_TYPE, captor.getValue().integrationType());
    assertEquals(PR_REF, captor.getValue().externalRef());
    assertEquals(RUN_ID, captor.getValue().workflowRunPublicId());

    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(event.capture());
    assertEquals(WorkflowEventType.INTEGRATION_LINKED, event.getValue().eventType());
    assertEquals(RUN_ID, event.getValue().workflowRunPublicId());
    assertEquals(PR_REF, event.getValue().details().get("githubPrReference"));
    assertEquals("open", event.getValue().details().get("prState"));
    assertEquals(42, event.getValue().details().get("prNumber"));
  }

  @Test
  void linkGitHubPrPassesShareableRedactedMetadataWithAllReconstructionFields() {
    when(gitHubAdapter.getPullRequestByRef(PR_REF)).thenReturn(Optional.of(samplePullRequest()));
    when(port.findActiveByTypeAndExternalRefForUpdate(GITHUB_TYPE, PR_REF))
        .thenReturn(Optional.empty());
    when(port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, RUN_ID))
        .thenReturn(Optional.empty());
    when(redactionService.redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value())))
        .thenReturn(sampleRedactionResult());
    when(port.insert(any(NewIntegrationLink.class)))
        .thenReturn(sampleGithubLink("ilk_gh00000000001", RUN_ID, IntegrationSyncStatus.LINKED));

    service.linkGitHubPr(RUN_ID, PR_REF, REPO_REF, BRANCH, COMMIT_SHA, ACTOR, GH_KEY);

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(redactionService)
        .redact(captor.capture(), eq(DataClassification.SHAREABLE_REDACTED.value()));
    Map<String, Object> sent = captor.getValue();
    assertEquals(REPO_REF, sent.get("repositoryFullName"));
    assertEquals(BRANCH, sent.get("branch"));
    assertEquals(COMMIT_SHA, sent.get("commitSha"));
    assertEquals(42, sent.get("prNumber"));
    assertEquals("open", sent.get("prState"));
    assertTrue(sent.containsKey("prUrl"));
  }

  @Test
  void linkGitHubPrCrossRunDoubleLinkRaisesIntegrationLinkConflict() {
    when(gitHubAdapter.getPullRequestByRef(PR_REF)).thenReturn(Optional.of(samplePullRequest()));
    when(port.findActiveByTypeAndExternalRefForUpdate(GITHUB_TYPE, PR_REF))
        .thenReturn(
            Optional.of(
                sampleGithubLink("ilk_other00000001", OTHER_RUN_ID, IntegrationSyncStatus.LINKED)));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.linkGitHubPr(RUN_ID, PR_REF, REPO_REF, BRANCH, COMMIT_SHA, ACTOR, GH_KEY));

    assertEquals(DomainErrorCode.INTEGRATION_LINK_CONFLICT, error.errorCode());
    assertEquals(OTHER_RUN_ID, error.details().get("existingRunPublicId"));
    verify(port, never()).insert(any());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void linkGitHubPrRepoMismatchRaisesLinearGithubRepoMismatch() {
    when(gitHubAdapter.getPullRequestByRef(PR_REF)).thenReturn(Optional.of(samplePullRequest()));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.linkGitHubPr(
                    RUN_ID, PR_REF, "other/repo", BRANCH, COMMIT_SHA, ACTOR, GH_KEY));

    assertEquals(DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH, error.errorCode());
    verify(port, never()).insert(any());
  }

  @Test
  void linkGitHubPrSamePrSameRunIsIdempotentNoOp() {
    IntegrationLink existing =
        sampleGithubLink("ilk_existing00001", RUN_ID, IntegrationSyncStatus.LINKED);
    when(gitHubAdapter.getPullRequestByRef(PR_REF)).thenReturn(Optional.of(samplePullRequest()));
    when(port.findActiveByTypeAndExternalRefForUpdate(GITHUB_TYPE, PR_REF))
        .thenReturn(Optional.of(existing));

    IntegrationLink result =
        service.linkGitHubPr(RUN_ID, PR_REF, REPO_REF, BRANCH, COMMIT_SHA, ACTOR, GH_KEY);

    assertEquals("ilk_existing00001", result.publicId());
    verify(port, never()).insert(any());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void linkGitHubPrDifferentPrSameRunSupersedesPriorThenInserts() {
    IntegrationLink prior =
        sampleGithubLink("ilk_prior00000001", RUN_ID, OTHER_PR_REF, IntegrationSyncStatus.LINKED);
    when(gitHubAdapter.getPullRequestByRef(PR_REF)).thenReturn(Optional.of(samplePullRequest()));
    when(port.findActiveByTypeAndExternalRefForUpdate(GITHUB_TYPE, PR_REF))
        .thenReturn(Optional.empty());
    when(port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, RUN_ID))
        .thenReturn(Optional.of(prior));
    when(redactionService.redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value())))
        .thenReturn(sampleRedactionResult());
    when(port.insert(any(NewIntegrationLink.class)))
        .thenReturn(sampleGithubLink("ilk_new000000001", RUN_ID, IntegrationSyncStatus.LINKED));

    IntegrationLink result =
        service.linkGitHubPr(RUN_ID, PR_REF, REPO_REF, BRANCH, COMMIT_SHA, ACTOR, GH_KEY);

    assertEquals("ilk_new000000001", result.publicId());
    verify(port).updateSyncStatus("ilk_prior00000001", IntegrationSyncStatus.SUPERSEDED, null);
    verify(port).insert(any(NewIntegrationLink.class));
  }

  @Test
  void linkGitHubPrPrNotFoundRaisesConflictCarryingCategory() {
    when(gitHubAdapter.getPullRequestByRef(PR_REF)).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.linkGitHubPr(RUN_ID, PR_REF, REPO_REF, BRANCH, COMMIT_SHA, ACTOR, GH_KEY));

    assertEquals(DomainErrorCode.INTEGRATION_LINK_CONFLICT, error.errorCode());
    assertEquals(
        IntegrationFailureCategory.GITHUB_PR_NOT_FOUND.value(),
        error.details().get("failureCategory"));
    verify(port, never()).insert(any());
  }

  @Test
  void syncGitHubPrRefreshesPrStateAndLastSyncAt() {
    IntegrationLink active =
        sampleGithubLink("ilk_sync00000001", RUN_ID, IntegrationSyncStatus.LINKED);
    when(port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, RUN_ID))
        .thenReturn(Optional.of(active));
    when(gitHubAdapter.getPullRequestByRef(PR_REF))
        .thenReturn(Optional.of(samplePullRequest("merged")));
    when(gitHubAdapter.getBranchByRef(REPO_REF, BRANCH)).thenReturn(Optional.empty());
    when(redactionService.redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value())))
        .thenReturn(sampleRedactionResult());
    when(port.updateExternalMetadataAndSync(
            eq("ilk_sync00000001"), any(byte[].class), eq(IntegrationSyncStatus.SYNCED), any()))
        .thenReturn(sampleGithubLink("ilk_sync00000001", RUN_ID, IntegrationSyncStatus.SYNCED));

    IntegrationLink result = service.syncGitHubPr(RUN_ID);

    assertEquals(IntegrationSyncStatus.SYNCED, result.syncStatus());
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(redactionService)
        .redact(captor.capture(), eq(DataClassification.SHAREABLE_REDACTED.value()));
    assertEquals("merged", captor.getValue().get("prState"));
  }

  @Test
  void syncGitHubPrPrNotFoundMarksLinkFailed() {
    IntegrationLink active =
        sampleGithubLink("ilk_sync00000001", RUN_ID, IntegrationSyncStatus.LINKED);
    when(port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, RUN_ID))
        .thenReturn(Optional.of(active));
    when(gitHubAdapter.getPullRequestByRef(PR_REF)).thenReturn(Optional.empty());
    when(port.updateSyncStatus("ilk_sync00000001", IntegrationSyncStatus.FAILED, null))
        .thenReturn(sampleGithubLink("ilk_sync00000001", RUN_ID, IntegrationSyncStatus.FAILED));

    IntegrationLink result = service.syncGitHubPr(RUN_ID);

    assertEquals(IntegrationSyncStatus.FAILED, result.syncStatus());
    verify(port).updateSyncStatus("ilk_sync00000001", IntegrationSyncStatus.FAILED, null);
    verify(port, never()).updateExternalMetadataAndSync(anyString(), any(), any(), any());
  }

  @Test
  void syncGitHubPrRecoversAFailedLinkBeforeSyncing() {
    // A prior sync routed the link to FAILED on a transient adapter failure; the fetch now
    // succeeds.
    // syncGitHubPr must recover FAILED → LINKED before the → SYNCED write, otherwise
    // updateExternalMetadataAndSync would throw ILLEGAL_TRANSITION (FAILED → SYNCED is illegal) and
    // the link could never re-sync.
    IntegrationLink active =
        sampleGithubLink("ilk_sync00000001", RUN_ID, IntegrationSyncStatus.FAILED);
    when(port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, RUN_ID))
        .thenReturn(Optional.of(active));
    when(gitHubAdapter.getPullRequestByRef(PR_REF))
        .thenReturn(Optional.of(samplePullRequest("open")));
    when(gitHubAdapter.getBranchByRef(REPO_REF, BRANCH)).thenReturn(Optional.empty());
    when(port.updateSyncStatus("ilk_sync00000001", IntegrationSyncStatus.LINKED, null))
        .thenReturn(sampleGithubLink("ilk_sync00000001", RUN_ID, IntegrationSyncStatus.LINKED));
    when(redactionService.redact(any(Map.class), eq(DataClassification.SHAREABLE_REDACTED.value())))
        .thenReturn(sampleRedactionResult());
    when(port.updateExternalMetadataAndSync(
            eq("ilk_sync00000001"), any(byte[].class), eq(IntegrationSyncStatus.SYNCED), any()))
        .thenReturn(sampleGithubLink("ilk_sync00000001", RUN_ID, IntegrationSyncStatus.SYNCED));

    IntegrationLink result = service.syncGitHubPr(RUN_ID);

    assertEquals(IntegrationSyncStatus.SYNCED, result.syncStatus());
    InOrder inOrder = inOrder(port);
    inOrder.verify(port).updateSyncStatus("ilk_sync00000001", IntegrationSyncStatus.LINKED, null);
    inOrder
        .verify(port)
        .updateExternalMetadataAndSync(
            eq("ilk_sync00000001"), any(byte[].class), eq(IntegrationSyncStatus.SYNCED), any());
  }

  @Test
  void assertArtifactPrLinkMatchesPassesWhenArtifactRefMatchesLink() {
    when(port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, RUN_ID))
        .thenReturn(
            Optional.of(
                sampleGithubLink("ilk_match00000001", RUN_ID, IntegrationSyncStatus.LINKED)));

    service.assertArtifactPrLinkMatches(RUN_ID, PR_REF);
  }

  @Test
  void assertArtifactPrLinkMatchesRaisesArtifactPrLinkMismatchOnDrift() {
    when(port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, RUN_ID))
        .thenReturn(
            Optional.of(
                sampleGithubLink("ilk_match00000001", RUN_ID, IntegrationSyncStatus.LINKED)));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.assertArtifactPrLinkMatches(RUN_ID, "PR-99"));

    assertEquals(DomainErrorCode.ARTIFACT_PR_LINK_MISMATCH, error.errorCode());
    assertEquals("artifact_pr_reference_drifted", error.details().get("reason"));
  }

  @Test
  void assertArtifactPrLinkMatchesFailsClosedWhenNoActiveLink() {
    when(port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, RUN_ID))
        .thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.assertArtifactPrLinkMatches(RUN_ID, PR_REF));

    assertEquals(DomainErrorCode.ARTIFACT_PR_LINK_MISMATCH, error.errorCode());
    assertEquals("no_active_github_pr_link", error.details().get("reason"));
  }

  private static GitHubPullRequest samplePullRequest() {
    return samplePullRequest("open");
  }

  private static GitHubPullRequest samplePullRequest(String state) {
    return new GitHubPullRequest(
        PR_REF,
        REPO_REF,
        42,
        BRANCH,
        state,
        "https://github.com/octo/hello/pull/42",
        Instant.parse("2026-05-01T10:00:00Z"));
  }

  private static IntegrationLink sampleGithubLink(
      String publicId, String workflowRunPublicId, IntegrationSyncStatus status) {
    return sampleGithubLink(publicId, workflowRunPublicId, PR_REF, status);
  }

  private static IntegrationLink sampleGithubLink(
      String publicId,
      String workflowRunPublicId,
      String externalRef,
      IntegrationSyncStatus status) {
    Instant now = Instant.parse("2026-04-25T10:00:00Z");
    return new IntegrationLink(
        publicId, workflowRunPublicId, "github_pr", externalRef, status, now, now, null);
  }

  // ===== Story 3b-5 — github_pr link view for the artifact-read prState projection =====

  @Test
  void findActiveGitHubPrLinkViewParsesPrStateFromMetadata() {
    when(port.findActiveTicketSummaryByTypeAndWorkflowRun(GITHUB_TYPE, RUN_ID))
        .thenReturn(
            Optional.of(
                new IntegrationLinkRecordPort.TicketSummaryProjection(
                    PR_REF,
                    "{\"prState\":\"open\",\"prNumber\":42}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));

    Optional<IntegrationLinkService.GitHubPrLinkView> view =
        service.findActiveGitHubPrLinkView(RUN_ID);

    assertTrue(view.isPresent());
    assertEquals(PR_REF, view.get().prReference());
    assertEquals("open", view.get().prState());
  }

  @Test
  void findActiveGitHubPrLinkViewNullStateWhenMetadataLacksPrState() {
    when(port.findActiveTicketSummaryByTypeAndWorkflowRun(GITHUB_TYPE, RUN_ID))
        .thenReturn(
            Optional.of(
                new IntegrationLinkRecordPort.TicketSummaryProjection(
                    PR_REF,
                    "{\"prNumber\":42}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));

    Optional<IntegrationLinkService.GitHubPrLinkView> view =
        service.findActiveGitHubPrLinkView(RUN_ID);

    assertTrue(view.isPresent());
    assertEquals(PR_REF, view.get().prReference());
    org.junit.jupiter.api.Assertions.assertNull(view.get().prState());
  }

  @Test
  void findActiveGitHubPrLinkViewEmptyWhenNoActiveGitHubLink() {
    when(port.findActiveTicketSummaryByTypeAndWorkflowRun(GITHUB_TYPE, RUN_ID))
        .thenReturn(Optional.empty());

    assertTrue(service.findActiveGitHubPrLinkView(RUN_ID).isEmpty());
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
