package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.FailureClassificationView;
import org.dradgo.application.workflow.spi.FailureClassificationRecord;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowRunFailureClassificationPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureTaxonomyValue;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Story 4.9 (AC9/AC6) — unit tests for {@link
 * WorkflowInspectionService#getFailureClassification(String)}: current triple from the port, the
 * prior chain reconstructed from the {@code recovery.failureClassified} events (most-recent-first),
 * display labels via {@code FailureTaxonomyValue.displayLabel()} (the {@code (deprecated)} affix
 * path itself is pinned by {@code FailureTaxonomyValueTest}'s synthetic seam — the shipped registry
 * has zero deprecated values), and the unwired-port guard.
 */
class WorkflowInspectionServiceFailureClassificationTest {

  private static final String RUN = "run_classifyview1";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-12T12:00:00Z");

  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowEventReadPort events = mock(WorkflowEventReadPort.class);
  private final WorkflowRunFailureClassificationPort classificationPort =
      mock(WorkflowRunFailureClassificationPort.class);

  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          runs,
          events,
          mock(ArtifactRecordPort.class),
          mock(org.dradgo.application.artifact.spi.ArtifactPayloadStore.class),
          mock(org.dradgo.application.approval.spi.ApprovalReadPort.class),
          mock(IntegrationLinkService.class),
          new RedactionPolicyService(new DataClassificationService()),
          mock(RecoveryService.class),
          mock(RunnerExecutionRecordPort.class),
          mock(RunnerScratchStore.class),
          mock(org.dradgo.application.clarification.spi.ClarificationReadPort.class),
          mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
          org.dradgo.application.runner.RunnerProperties.defaults(),
          org.dradgo.application.runner.RunnerWorkerPoolProperties.defaults(),
          mock(SplitProposalReadPort.class));

  @Test
  void classifiedRunSurfacesCurrentTripleAndPriorChainMostRecentFirst() {
    service.setFailureClassificationPort(classificationPort);
    when(classificationPort.findClassification(RUN))
        .thenReturn(
            Optional.of(
                new FailureClassificationRecord(
                    FailureTaxonomyValue.AGENT_EXECUTION_FAILURE, NOW, "alex")));
    // Three classify events: two priors + the latest (which mirrors the current column value).
    when(events.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                classifiedEvent("evt_cls-1", "specification_gap", NOW.minusHours(2), "amelia"),
                classifiedEvent("evt_cls-2", "context_gap", NOW.minusHours(1), "alex"),
                classifiedEvent("evt_cls-3", "agent_execution_failure", NOW, "alex")));

    FailureClassificationView view = service.getFailureClassification(RUN);

    assertThat(view.currentTaxonomyValue()).isEqualTo("agent_execution_failure");
    // Active value → label == wire value (a deprecated value would carry the affix; see
    // FailureTaxonomyValueTest.displayLabelSeamAffixesDeprecatedForSyntheticDeprecatedValue).
    assertThat(view.currentDisplayLabel()).isEqualTo("agent_execution_failure");
    assertThat(view.deprecated()).isFalse();
    assertThat(view.deprecatedReplacementValue()).isNull();
    assertThat(view.classifiedAt()).isEqualTo(NOW);
    assertThat(view.classifiedBy()).isEqualTo("alex");

    // AC9 — "classified as X (previously Y at Z)": priors exclude the latest event and render
    // most-recent-first.
    assertThat(view.priorClassifications()).hasSize(2);
    assertThat(view.priorClassifications().get(0).taxonomyValue()).isEqualTo("context_gap");
    assertThat(view.priorClassifications().get(0).displayLabel()).isEqualTo("context_gap");
    assertThat(view.priorClassifications().get(0).classifiedAt()).isEqualTo(NOW.minusHours(1));
    assertThat(view.priorClassifications().get(0).classifiedBy()).isEqualTo("alex");
    assertThat(view.priorClassifications().get(1).taxonomyValue()).isEqualTo("specification_gap");
    assertThat(view.priorClassifications().get(1).classifiedBy()).isEqualTo("amelia");
  }

  @Test
  void neverClassifiedRunReturnsNullCurrentFieldsAndEmptyPriors() {
    service.setFailureClassificationPort(classificationPort);
    when(classificationPort.findClassification(RUN)).thenReturn(Optional.empty());
    when(events.listByWorkflowRunPublicId(RUN, null)).thenReturn(List.of());

    FailureClassificationView view = service.getFailureClassification(RUN);

    assertThat(view.currentTaxonomyValue()).isNull();
    assertThat(view.currentDisplayLabel()).isNull();
    assertThat(view.deprecated()).isFalse();
    assertThat(view.deprecatedReplacementValue()).isNull();
    assertThat(view.classifiedAt()).isNull();
    assertThat(view.classifiedBy()).isNull();
    assertThat(view.priorClassifications()).isEmpty();
  }

  @Test
  void singleClassificationYieldsNoPriors() {
    service.setFailureClassificationPort(classificationPort);
    when(classificationPort.findClassification(RUN))
        .thenReturn(
            Optional.of(
                new FailureClassificationRecord(
                    FailureTaxonomyValue.REVIEW_REJECTION, NOW, "alex")));
    when(events.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(List.of(classifiedEvent("evt_cls-1", "review_rejection", NOW, "alex")));

    FailureClassificationView view = service.getFailureClassification(RUN);

    assertThat(view.currentTaxonomyValue()).isEqualTo("review_rejection");
    assertThat(view.priorClassifications()).isEmpty();
  }

  @Test
  void nonClassifyEventsAreIgnoredInThePriorChain() {
    service.setFailureClassificationPort(classificationPort);
    when(classificationPort.findClassification(RUN))
        .thenReturn(
            Optional.of(
                new FailureClassificationRecord(FailureTaxonomyValue.CONTEXT_GAP, NOW, "alex")));
    when(events.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                stateChangedEvent("evt_state-1"),
                classifiedEvent("evt_cls-1", "specification_gap", NOW.minusHours(1), "amelia"),
                stateChangedEvent("evt_state-2"),
                classifiedEvent("evt_cls-2", "context_gap", NOW, "alex")));

    FailureClassificationView view = service.getFailureClassification(RUN);

    assertThat(view.priorClassifications()).hasSize(1);
    assertThat(view.priorClassifications().get(0).taxonomyValue()).isEqualTo("specification_gap");
  }

  @Test
  void unwiredPortThrowsInternalErrorNotNullPointer() {
    assertThatThrownBy(() -> service.getFailureClassification(RUN))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.INTERNAL_ERROR));
  }

  private static WorkflowEventRecord classifiedEvent(
      String eventId, String taxonomyValue, OffsetDateTime createdAt, String actorIdentity) {
    return new WorkflowEventRecord(
        eventId,
        RUN,
        WorkflowEventType.RECOVERY_FAILURE_CLASSIFIED,
        WorkflowState.FAILED,
        WorkflowState.FAILED,
        actorIdentity,
        ActorType.HUMAN,
        null,
        null,
        true,
        createdAt,
        Map.of("taxonomyValue", taxonomyValue));
  }

  private static WorkflowEventRecord stateChangedEvent(String eventId) {
    return new WorkflowEventRecord(
        eventId,
        RUN,
        WorkflowEventType.WORKFLOW_STATE_CHANGED,
        WorkflowState.EXECUTING,
        WorkflowState.FAILED,
        "system",
        ActorType.SYSTEM,
        "failed",
        null,
        false,
        NOW.minusHours(3),
        Map.of());
  }
}
