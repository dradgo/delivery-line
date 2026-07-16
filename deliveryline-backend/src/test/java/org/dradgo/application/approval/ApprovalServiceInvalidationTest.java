package org.dradgo.application.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.approval.spi.ApprovalWritePort;
import org.dradgo.application.artifact.ArtifactService;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.workflow.SpecRejectionEscalationThresholdProvider;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.registry.ActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Story 4.7 (AC7 / Reconciliation 2) — unit coverage for {@link
 * ApprovalService#invalidateCurrentApproval}: it reads the run's current approved artifact of the
 * given type, invalidates it via {@link ApprovalWritePort#markInvalidated}, and returns the
 * invalidated apr public id — or no-ops (empty) when there is no current approval to invalidate.
 */
class ApprovalServiceInvalidationTest {

  private static final String RUN = "run_inval12345";
  private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-07-11T12:00:00Z");

  private ApprovalWritePort approvalWritePort;
  private ApprovalReadPort approvalReadPort;
  private ApprovalService service;

  @BeforeEach
  void setUp() {
    approvalWritePort = mock(ApprovalWritePort.class);
    approvalReadPort = mock(ApprovalReadPort.class);
    service =
        new ApprovalService(
            mock(ArtifactRecordPort.class),
            mock(ArtifactService.class),
            approvalWritePort,
            approvalReadPort,
            mock(WorkflowEventWritePort.class),
            mock(WorkflowTransitionService.class),
            mock(org.dradgo.application.approval.ApprovalVersionBinder.class),
            mock(WorkflowRunRejectionLoopPort.class),
            mock(SpecRejectionEscalationThresholdProvider.class),
            mock(WorkflowOrchestrationService.class),
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC));
  }

  @Test
  void invalidatesCurrentApprovalAndReturnsItsPublicId() {
    when(approvalReadPort.findLatestApprovedForArtifactLineage(RUN, "spec"))
        .thenReturn(Optional.of(approvedSnapshot("apr_spec_1")));
    when(approvalWritePort.markInvalidated(
            eq("apr_spec_1"), eq("superseded_by_rerun_from_step"), any()))
        .thenReturn(true);

    Optional<String> result =
        service.invalidateCurrentApproval(RUN, "spec", "superseded_by_rerun_from_step");

    assertTrue(result.isPresent());
    assertEquals("apr_spec_1", result.get());
    verify(approvalWritePort)
        .markInvalidated(eq("apr_spec_1"), eq("superseded_by_rerun_from_step"), any());
  }

  @Test
  void returnsEmptyWhenNoCurrentApprovalToInvalidate() {
    when(approvalReadPort.findLatestApprovedForArtifactLineage(RUN, "implementationPlan"))
        .thenReturn(Optional.empty());

    Optional<String> result =
        service.invalidateCurrentApproval(
            RUN, "implementationPlan", "superseded_by_rerun_from_step");

    assertTrue(result.isEmpty());
    verify(approvalWritePort, never()).markInvalidated(any(), any(), any());
  }

  @Test
  void returnsEmptyWhenTheRowWasAlreadyInvalidated() {
    // A second rerun on the same run: markInvalidated finds the row already invalidated (rows=0).
    when(approvalReadPort.findLatestApprovedForArtifactLineage(RUN, "spec"))
        .thenReturn(Optional.of(approvedSnapshot("apr_spec_1")));
    when(approvalWritePort.markInvalidated(eq("apr_spec_1"), any(), any())).thenReturn(false);

    Optional<String> result =
        service.invalidateCurrentApproval(RUN, "spec", "superseded_by_rerun_from_step");

    assertFalse(result.isPresent());
  }

  @Test
  void invalidateApprovalForArtifactInvalidatesTheArtifactVersionsApprovalAndReturnsItsId() {
    // Story 4.16a [Review D3] — version-specific: keyed on the artifact public id (one version).
    when(approvalReadPort.findLatestApprovedForArtifact("art_spec0001"))
        .thenReturn(Optional.of(approvedSnapshotForArtifact("apr_spec_1", "art_spec0001")));
    when(approvalWritePort.markInvalidated(
            eq("apr_spec_1"), eq("superseded_by_lineage_termination"), any()))
        .thenReturn(true);

    Optional<String> result =
        service.invalidateApprovalForArtifact("art_spec0001", "superseded_by_lineage_termination");

    assertTrue(result.isPresent());
    assertEquals("apr_spec_1", result.get());
    verify(approvalWritePort)
        .markInvalidated(eq("apr_spec_1"), eq("superseded_by_lineage_termination"), any());
  }

  @Test
  void invalidateApprovalForArtifactNoOpsWhenTheArtifactHasNoCurrentApproval() {
    when(approvalReadPort.findLatestApprovedForArtifact("art_spec0002"))
        .thenReturn(Optional.empty());

    Optional<String> result =
        service.invalidateApprovalForArtifact("art_spec0002", "superseded_by_lineage_termination");

    assertTrue(result.isEmpty());
    verify(approvalWritePort, never()).markInvalidated(any(), any(), any());
  }

  @Test
  void invalidateApprovalForArtifactReturnsEmptyWhenTheRowWasAlreadyInvalidated() {
    when(approvalReadPort.findLatestApprovedForArtifact("art_spec0001"))
        .thenReturn(Optional.of(approvedSnapshotForArtifact("apr_spec_1", "art_spec0001")));
    when(approvalWritePort.markInvalidated(eq("apr_spec_1"), any(), any())).thenReturn(false);

    Optional<String> result =
        service.invalidateApprovalForArtifact("art_spec0001", "superseded_by_lineage_termination");

    assertFalse(result.isPresent());
  }

  @Test
  void restoreInvalidatedApprovalDelegatesToClearInvalidationAndReturnsWhetherARowWasRestored() {
    // [Review D1] Dispatch-failure compensation restores the approval it invalidated in the prep
    // tx.
    when(approvalWritePort.clearInvalidation("apr_spec_1")).thenReturn(true);

    assertTrue(service.restoreInvalidatedApproval("apr_spec_1"));
    verify(approvalWritePort).clearInvalidation("apr_spec_1");
  }

  @Test
  void restoreInvalidatedApprovalReturnsFalseWhenTheRowWasAlreadyValid() {
    // Idempotent: a row that is not currently invalidated (rows=0) restores to false.
    when(approvalWritePort.clearInvalidation("apr_spec_1")).thenReturn(false);

    assertFalse(service.restoreInvalidatedApproval("apr_spec_1"));
  }

  private static ApprovalSnapshot approvedSnapshot(String publicId) {
    return approvedSnapshotForArtifact(publicId, "art_spec");
  }

  private static ApprovalSnapshot approvedSnapshotForArtifact(String publicId, String artifactId) {
    return new ApprovalSnapshot(
        publicId,
        RUN,
        artifactId,
        1,
        1,
        "alex",
        ActorType.HUMAN,
        "product_reviewer",
        ApprovalSnapshot.DECISION_APPROVED,
        null,
        null,
        FIXED_NOW);
  }
}
