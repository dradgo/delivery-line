package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.SpecificationArtifact;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.ContextBundle;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.SpecHistoryEntry;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 2.8 AC7/AC8/AC9/AC10: Mockito-driven coverage of the three spec-inspection methods plus the
 * structured-log surface they emit. Mirrors the {@code ArtifactLoggingContractTest} ListAppender
 * pattern so future refactors cannot silently delete the entry/exit logs.
 */
class WorkflowInspectionServiceSpecTest {

  private static final String RUN_ID = "run_specinsp1234";
  private static final String SPEC_ART_V1 = "art_spec00000001";
  private static final String SPEC_ART_V2 = "art_spec00000002";
  private static final String SPEC_ART_V3 = "art_spec00000003";
  private static final String APPROVAL_V1 = "apr_v1_rejected1";
  private static final String APPROVAL_V2 = "apr_v2_approved1";
  private static final String PLAN_ART = "art_plan00000001";
  private static final String REX_ID = "rex_specinsp1234";
  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);

  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowEventReadPort events = mock(WorkflowEventReadPort.class);
  private final ArtifactRecordPort artifacts = mock(ArtifactRecordPort.class);
  private final ApprovalReadPort approvals = mock(ApprovalReadPort.class);
  private final IntegrationLinkService links = mock(IntegrationLinkService.class);
  private final RedactionPolicyService redaction = mock(RedactionPolicyService.class);
  private final RecoveryService recovery = mock(RecoveryService.class);
  private final RunnerExecutionRecordPort runnerExecutions = mock(RunnerExecutionRecordPort.class);
  private final RunnerScratchStore scratch = mock(RunnerScratchStore.class);
  private final org.dradgo.application.clarification.spi.ClarificationReadPort clarifications =
      mock(org.dradgo.application.clarification.spi.ClarificationReadPort.class);

  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          runs,
          events,
          artifacts,
          mock(org.dradgo.application.artifact.spi.ArtifactPayloadStore.class),
          approvals,
          links,
          redaction,
          recovery,
          runnerExecutions,
          scratch,
          clarifications,
          mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
          org.dradgo.application.runner.RunnerProperties.defaults(),
          org.dradgo.application.runner.RunnerWorkerPoolProperties.defaults(),
          mock(SplitProposalReadPort.class));

  private final ListAppender<ILoggingEvent> serviceAppender =
      attachListAppender(WorkflowInspectionService.class);

  @AfterEach
  void detachAppenders() {
    Logger logger = (Logger) LoggerFactory.getLogger(WorkflowInspectionService.class);
    logger.detachAppender(serviceAppender);
  }

  // =============================================================================================
  // getCurrentApprovedSpec
  // =============================================================================================

  @Test
  void getCurrentApprovedSpecReturnsEmptyWhenNoApprovedSpec() {
    when(approvals.findLatestApprovedForArtifactLineage(RUN_ID, "spec"))
        .thenReturn(Optional.empty());

    Optional<SpecificationArtifact> result = service.getCurrentApprovedSpec(RUN_ID);

    assertTrue(result.isEmpty());
    assertTrue(
        serviceAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage()
                            .contains("getCurrentApprovedSpec entry workflowRunId=" + RUN_ID)),
        "INFO entry log must be emitted");
    assertTrue(
        serviceAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("noApprovedSpec=true")),
        "INFO success log must surface noApprovedSpec=true");
  }

  @Test
  void getCurrentApprovedSpecReturnsLatestApproved() {
    ApprovalSnapshot approval =
        approvalRow(APPROVAL_V2, SPEC_ART_V2, 2, ApprovalSnapshot.DECISION_APPROVED, null);
    when(approvals.findLatestApprovedForArtifactLineage(RUN_ID, "spec"))
        .thenReturn(Optional.of(approval));
    when(artifacts.findByPublicId(SPEC_ART_V2))
        .thenReturn(Optional.of(specSnapshot(SPEC_ART_V2, 2, SPEC_ART_V1)));

    Optional<SpecificationArtifact> result = service.getCurrentApprovedSpec(RUN_ID);

    assertTrue(result.isPresent());
    SpecificationArtifact spec = result.get();
    assertEquals(SPEC_ART_V2, spec.id());
    assertEquals(2, spec.version());
    assertEquals(SPEC_ART_V1, spec.parentArtifactId());
  }

  @Test
  void getCurrentApprovedSpecRejectsBadPublicId() {
    DomainException error =
        assertThrows(DomainException.class, () -> service.getCurrentApprovedSpec("not-a-run-id"));
    assertEquals(DomainErrorCode.INVALID_ID_PREFIX, error.errorCode());
  }

  // =============================================================================================
  // getSpecHistory
  // =============================================================================================

  @Test
  void getSpecHistoryOrdersAscendingVersionWithApprovedRejectedAndPending() {
    ArtifactRecordSnapshot v1 = specSnapshot(SPEC_ART_V1, 1, null);
    ArtifactRecordSnapshot v2 = specSnapshot(SPEC_ART_V2, 2, SPEC_ART_V1);
    ArtifactRecordSnapshot v3 = specSnapshot(SPEC_ART_V3, 3, SPEC_ART_V2);
    when(artifacts.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of(v1, v2, v3));

    ApprovalSnapshot rejection =
        approvalRow(
            APPROVAL_V1, SPEC_ART_V1, 1, ApprovalSnapshot.DECISION_REJECTED, "missing_scope");
    ApprovalSnapshot approval =
        approvalRow(APPROVAL_V2, SPEC_ART_V2, 2, ApprovalSnapshot.DECISION_APPROVED, null);
    when(approvals.listByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of(rejection, approval));

    List<SpecHistoryEntry> history = service.getSpecHistory(RUN_ID);

    assertEquals(3, history.size());
    assertEquals(1, history.get(0).spec().version());
    assertEquals("rejected", history.get(0).decision());
    assertEquals("missing_scope", history.get(0).rejectionTaxonomy());
    assertEquals("product_owner", history.get(0).reviewerRole());
    assertEquals(2, history.get(1).spec().version());
    assertEquals("approved", history.get(1).decision());
    assertNull(history.get(1).rejectionTaxonomy());
    assertEquals(3, history.get(2).spec().version());
    assertEquals("pending", history.get(2).decision());
    assertNull(history.get(2).reviewerRole());
    assertNull(history.get(2).rejectionTaxonomy());
    assertNull(history.get(2).decidedAt());

    assertTrue(
        serviceAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage()
                            .contains("getSpecHistory success workflowRunId=" + RUN_ID)
                        && e.getFormattedMessage().contains("historyLength=3")),
        "INFO success log must surface historyLength");
  }

  @Test
  void getSpecHistoryRejectsDuplicateDecisionsForTheSameSpecVersion() {
    ArtifactRecordSnapshot v1 = specSnapshot(SPEC_ART_V1, 1, null);
    when(artifacts.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec")).thenReturn(List.of(v1));

    ApprovalSnapshot rejection =
        approvalRow(
            APPROVAL_V1, SPEC_ART_V1, 1, ApprovalSnapshot.DECISION_REJECTED, "missing_scope");
    ApprovalSnapshot approval =
        approvalRow("apr_v1_approved2", SPEC_ART_V1, 1, ApprovalSnapshot.DECISION_APPROVED, null);
    when(approvals.listByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of(rejection, approval));

    DomainException error =
        assertThrows(DomainException.class, () -> service.getSpecHistory(RUN_ID));

    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
    assertEquals(SPEC_ART_V1, error.details().get("artifactId"));
    assertEquals(1, error.details().get("artifactVersion"));
  }

  // =============================================================================================
  // getContextBundleForArtifact
  // =============================================================================================

  @Test
  void getContextBundleForArtifactReturnsScratchBytesWhenAvailable() {
    when(artifacts.findByPublicId(SPEC_ART_V2))
        .thenReturn(Optional.of(specSnapshot(SPEC_ART_V2, 2, SPEC_ART_V1)));
    when(artifacts.findRunnerExecutionIdForArtifact(SPEC_ART_V2)).thenReturn(Optional.of(REX_ID));
    when(runnerExecutions.findByPublicId(REX_ID))
        .thenReturn(
            Optional.of(
                new RunnerExecutionSnapshot(
                    REX_ID,
                    RUN_ID,
                    RunnerStage.INVESTIGATION,
                    RunnerExecutionStatus.COMPLETED,
                    3,
                    T0,
                    T0.plusHours(1),
                    null,
                    T0.plusMinutes(10),
                    T0,
                    null)));
    byte[] scratchBytes = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
    when(scratch.tryReadContextBundle(REX_ID)).thenReturn(Optional.of(scratchBytes));

    Optional<ContextBundle> result = service.getContextBundleForArtifact(SPEC_ART_V2);

    assertTrue(result.isPresent());
    assertEquals(REX_ID, result.get().runnerExecutionId());
    assertEquals(3, result.get().contextBundleVersion());
    assertEquals(
        new String(scratchBytes, StandardCharsets.UTF_8),
        new String(result.get().redactedPayload(), StandardCharsets.UTF_8));
    assertTrue(
        serviceAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("source=scratch")
                        && e.getFormattedMessage().contains("bundleByteLength=")),
        "INFO success log must surface source=scratch + bundleByteLength");
  }

  @Test
  void getContextBundleForArtifactReturnsImplementationStageBundleForExecutionArtifact() {
    // AC9(f)/AC6 — FR55 inspection resolves an implementation-stage (EXECUTION) artifact through
    // the artifact→runner-execution link and returns the persisted implementation-stage bundle
    // VERBATIM, including the story-3.10 fields (approvedImplementationPlanReference +
    // repositoryBranchRef). Resolution is by the runner-execution link, independent of the new
    // fields — the service code is unchanged (Decision D7), so they surface automatically.
    when(artifacts.findByPublicId(PLAN_ART)).thenReturn(Optional.of(planSnapshot(PLAN_ART, 1)));
    when(artifacts.findRunnerExecutionIdForArtifact(PLAN_ART)).thenReturn(Optional.of(REX_ID));
    when(runnerExecutions.findByPublicId(REX_ID))
        .thenReturn(
            Optional.of(
                new RunnerExecutionSnapshot(
                    REX_ID,
                    RUN_ID,
                    RunnerStage.EXECUTION,
                    RunnerExecutionStatus.COMPLETED,
                    4,
                    T0,
                    T0.plusHours(1),
                    null,
                    T0.plusMinutes(10),
                    T0,
                    null)));
    String implementationStageBundle =
        "{\"schemaVersion\":1,\"approvedImplementationPlanReference\":{\"artifactId\":\""
            + PLAN_ART
            + "\"},\"repositoryBranchRef\":\"deliveryline/DL-310/stage-exec1234\"}";
    byte[] scratchBytes = implementationStageBundle.getBytes(StandardCharsets.UTF_8);
    when(scratch.tryReadContextBundle(REX_ID)).thenReturn(Optional.of(scratchBytes));

    Optional<ContextBundle> result = service.getContextBundleForArtifact(PLAN_ART);

    assertTrue(result.isPresent());
    assertEquals(REX_ID, result.get().runnerExecutionId());
    assertEquals(RunnerStage.EXECUTION, result.get().stage());
    assertEquals(4, result.get().contextBundleVersion());
    // Verbatim scratch bytes — the new implementation-stage fields surface with no service change.
    String returned = new String(result.get().redactedPayload(), StandardCharsets.UTF_8);
    assertEquals(implementationStageBundle, returned);
    assertTrue(returned.contains("approvedImplementationPlanReference"));
    assertTrue(returned.contains("repositoryBranchRef"));
  }

  @Test
  void getContextBundleForArtifactReturnsEmptyWhenScratchEvicted() {
    when(artifacts.findByPublicId(SPEC_ART_V2))
        .thenReturn(Optional.of(specSnapshot(SPEC_ART_V2, 2, SPEC_ART_V1)));
    when(artifacts.findRunnerExecutionIdForArtifact(SPEC_ART_V2)).thenReturn(Optional.of(REX_ID));
    when(runnerExecutions.findByPublicId(REX_ID))
        .thenReturn(
            Optional.of(
                new RunnerExecutionSnapshot(
                    REX_ID,
                    RUN_ID,
                    RunnerStage.INVESTIGATION,
                    RunnerExecutionStatus.COMPLETED,
                    3,
                    T0,
                    T0.plusHours(1),
                    null,
                    T0.plusMinutes(10),
                    T0,
                    null)));
    when(scratch.tryReadContextBundle(REX_ID)).thenReturn(Optional.empty());

    Optional<ContextBundle> result = service.getContextBundleForArtifact(SPEC_ART_V2);

    assertTrue(
        result.isEmpty(),
        "Scratch eviction must surface as Optional.empty() — no current-state recomposition (AC7 fidelity)");
    assertTrue(
        serviceAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("reason=bundleNotPersisted")),
        "WARN log must surface reason=bundleNotPersisted when scratch is evicted");
  }

  @Test
  void getContextBundleForArtifactReturnsEmptyWhenArtifactMissing() {
    when(artifacts.findByPublicId(SPEC_ART_V2)).thenReturn(Optional.empty());

    Optional<ContextBundle> result = service.getContextBundleForArtifact(SPEC_ART_V2);

    assertTrue(result.isEmpty());
    assertTrue(
        serviceAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("reason=artifactNotFound")),
        "WARN log must surface artifactNotFound reason");
  }

  @Test
  void getContextBundleForArtifactReturnsEmptyWhenRunnerExecutionLinkMissing() {
    when(artifacts.findByPublicId(SPEC_ART_V2))
        .thenReturn(Optional.of(specSnapshot(SPEC_ART_V2, 2, SPEC_ART_V1)));
    when(artifacts.findRunnerExecutionIdForArtifact(SPEC_ART_V2)).thenReturn(Optional.empty());

    Optional<ContextBundle> result = service.getContextBundleForArtifact(SPEC_ART_V2);

    assertTrue(result.isEmpty());
  }

  @Test
  void getContextBundleForArtifactReturnsEmptyWhenRunnerExecutionBelongsToAnotherRun() {
    when(artifacts.findByPublicId(SPEC_ART_V2))
        .thenReturn(Optional.of(specSnapshot(SPEC_ART_V2, 2, SPEC_ART_V1)));
    when(artifacts.findRunnerExecutionIdForArtifact(SPEC_ART_V2)).thenReturn(Optional.of(REX_ID));
    when(runnerExecutions.findByPublicId(REX_ID))
        .thenReturn(
            Optional.of(
                new RunnerExecutionSnapshot(
                    REX_ID,
                    "run_other1234",
                    RunnerStage.INVESTIGATION,
                    RunnerExecutionStatus.COMPLETED,
                    3,
                    T0,
                    T0.plusHours(1),
                    null,
                    T0.plusMinutes(10),
                    T0,
                    null)));

    Optional<ContextBundle> result = service.getContextBundleForArtifact(SPEC_ART_V2);

    assertTrue(result.isEmpty());
    assertTrue(
        serviceAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("reason=runnerExecutionRunMismatch")),
        "WARN log must surface reason=runnerExecutionRunMismatch when the artifact event points at another run");
  }

  // =============================================================================================
  // Helpers
  // =============================================================================================

  private static ApprovalSnapshot approvalRow(
      String publicId, String artifactId, int version, String decision, String rejectionTaxonomy) {
    return new ApprovalSnapshot(
        publicId,
        RUN_ID,
        artifactId,
        version,
        version,
        "human-pm",
        ActorType.HUMAN,
        "product_owner",
        decision,
        decision.equals("rejected") ? "rejected reason" : null,
        rejectionTaxonomy,
        T0.plusHours(version));
  }

  private static ArtifactRecordSnapshot specSnapshot(
      String publicId, int version, String parentArtifactId) {
    return new ArtifactRecordSnapshot(
        publicId,
        RUN_ID,
        ArtifactType.SPEC,
        version,
        parentArtifactId,
        DataClassification.SHAREABLE_REDACTED,
        "spec.md",
        null,
        null,
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        T0.plusMinutes(version));
  }

  private static ArtifactRecordSnapshot planSnapshot(String publicId, int version) {
    return new ArtifactRecordSnapshot(
        publicId,
        RUN_ID,
        ArtifactType.IMPLEMENTATION_PLAN,
        version,
        null,
        DataClassification.SHAREABLE_REDACTED,
        "implementation-plan.md",
        null,
        null,
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        T0.plusMinutes(version));
  }

  private static ListAppender<ILoggingEvent> attachListAppender(Class<?> loggerClass) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }
}
