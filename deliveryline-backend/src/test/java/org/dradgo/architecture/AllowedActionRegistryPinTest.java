package org.dradgo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.domain.registry.AllowedAction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 2.9 AC11 — registry value pin for {@link AllowedAction#APPROVE_SPEC}. {@code
 * ApprovalService} is the canonical executor for this action; story 2.14 will ship the full
 * state×role → action-set logic via {@code WorkflowInspectionService.getAllowedActions}. Until then
 * this regression pin guards against a silent rename of the registry value.
 *
 * <p>Kept as a focused single-assertion test (rather than extending an existing
 * RegistryContractTest) because none of the existing architecture tests cover {@link
 * AllowedAction}'s wire value contract.
 */
@Tag(ArchitectureRuleCatalog.ARCHITECTURE_TAG)
class AllowedActionRegistryPinTest {

  @Test
  void approveSpecWireValueIsPinned() {
    assertThat(AllowedAction.APPROVE_SPEC.value()).isEqualTo("approve_spec");
  }

  @Test
  void acceptImplementationWireValueIsPinned() {
    // Story 3.20 AC12 — TechnicalApprovalService is the canonical executor for this action; the
    // developer-role WAITING_FOR_REVIEW matrix branch returns it. Guard against a silent rename of
    // the registry value (lockstep with allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.ACCEPT_IMPLEMENTATION.value()).isEqualTo("accept_implementation");
  }

  @Test
  void rejectImplementationWireValueIsPinned() {
    // Story 3.21 AC9 — TechnicalApprovalService.rejectImplementation is the canonical executor; the
    // developer-role WAITING_FOR_REVIEW matrix branch returns it alongside accept_implementation.
    // Guard against a silent rename (lockstep with allowed-actions.placeholder.json +
    // RegistryContractTest).
    assertThat(AllowedAction.REJECT_IMPLEMENTATION.value()).isEqualTo("reject_implementation");
  }

  @Test
  void takeoverWorkflowWireValueIsPinned() {
    // Story 3.22 AC9 — DeveloperTakeoverService is the canonical executor; the developer-role
    // WAITING_FOR_REVIEW matrix branch returns it alongside accept/reject. Guard against a silent
    // rename (lockstep with allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.TAKEOVER_WORKFLOW.value()).isEqualTo("takeover_workflow");
  }

  @Test
  void obtainManualBundleWireValueIsPinned() {
    // Story 3d-3 AC7 — surfaced in the WAITING_FOR_MANUAL_EXECUTION matrix branch for
    // workflow_owner;
    // honored by the GET …/manual-bundle endpoint in 3d-4. Guard against a silent rename (lockstep
    // with allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.OBTAIN_MANUAL_BUNDLE.value()).isEqualTo("obtain_manual_bundle");
  }

  @Test
  void submitManualArtifactWireValueIsPinned() {
    // Story 3d-3 AC7 — surfaced in the WAITING_FOR_MANUAL_EXECUTION matrix branch for
    // workflow_owner;
    // honored by the POST …/manual-artifact endpoint in 3d-4. Guard against a silent rename
    // (lockstep
    // with allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.SUBMIT_MANUAL_ARTIFACT.value()).isEqualTo("submit_manual_artifact");
  }

  @Test
  void acceptClarificationWireValueIsPinned() {
    // Story 3e-2 AC1 — WorkflowCommandService.acceptClarification -> markAccepted is the canonical
    // executor; surfaced in the WAITING_FOR_SPEC_APPROVAL reviewer-role matrix alongside
    // answer_clarification. Guard against a silent rename (lockstep with
    // allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.ACCEPT_CLARIFICATION.value()).isEqualTo("accept_clarification");
  }

  @Test
  void regenerateSpecWireValueIsPinned() {
    // Story 3e-2 AC2 — WorkflowCommandService.regenerateSpecWithClarifications (transition then
    // reuse retrySpecGeneration) is the canonical executor; surfaced in the
    // WAITING_FOR_SPEC_APPROVAL reviewer-role matrix. Guard against a silent rename (lockstep with
    // allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.REGENERATE_SPEC.value())
        .isEqualTo("regenerate_spec_with_clarifications");
  }

  @Test
  void requestSplitWireValueIsPinned() {
    // Story 3f-4 AC1 — SplitProposalService.request is the canonical executor; surfaced as an
    // advisory overlay at the WAITING_FOR_SPEC_APPROVAL / WAITING_FOR_REVIEW gate when no open
    // proposal exists. Guard against a silent rename (lockstep with
    // allowed-actions.placeholder.json
    // + RegistryContractTest).
    assertThat(AllowedAction.REQUEST_SPLIT.value()).isEqualTo("request_split");
  }

  @Test
  void declineSplitWireValueIsPinned() {
    // Story 3f-4 AC1/AC5 — SplitProposalService.decline ("continue as one ticket") is the canonical
    // executor; surfaced when an open proposal exists. NOTE the wire value is continue_as_single
    // (not decline_split). Guard against a silent rename (lockstep with
    // allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.DECLINE_SPLIT.value()).isEqualTo("continue_as_single");
  }

  @Test
  void reproposeSplitWireValueIsPinned() {
    // Story 3f-4 AC1/AC4 — SplitProposalService.repropose is the canonical executor; surfaced when
    // an open proposal exists. Guard against a silent rename (lockstep with
    // allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.REPROPOSE_SPLIT.value()).isEqualTo("repropose_split");
  }

  @Test
  void approveSplitWireValueIsPinned() {
    // Story 3f-5 AC1 — SplitCommitService.commit is the canonical executor; surfaced alongside
    // repropose_split/continue_as_single when an open proposal exists at the gate role. Guard
    // against a silent rename (lockstep with allowed-actions.placeholder.json +
    // RegistryContractTest).
    assertThat(AllowedAction.APPROVE_SPLIT.value()).isEqualTo("approve_split");
  }

  @Test
  void resumeWorkflowWireValueIsPinned() {
    // Story 4.5 AC9 — RecoveryService.resume (routing through
    // WorkflowCommandService.resumeWorkflow) is the canonical executor; surfaced ONLY at PAUSED for
    // the workflow_owner gate role. Guard against a silent rename (lockstep with
    // allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.RESUME_WORKFLOW.value()).isEqualTo("resume_workflow");
  }

  @Test
  void rerunFromStepWireValueIsPinned() {
    // Story 4.7 AC10 — RecoveryService.rerunFromStep (routing through
    // WorkflowCommandService.rerunFromStepWorkflow) is the canonical executor; surfaced for the
    // workflow_owner gate role at FAILED + WAITING_FOR_REVIEW (states with a legal rerun edge).
    // Guard against a silent rename (lockstep with allowed-actions.placeholder.json +
    // RegistryContractTest).
    assertThat(AllowedAction.RERUN_FROM_STEP.value()).isEqualTo("rerun_from_step");
  }

  @Test
  void pauseWorkflowWireValueIsPinned() {
    // Story 4.8 AC10 — RecoveryService.pause (routing through
    // WorkflowCommandService.pauseWorkflow) is the canonical executor; surfaced for the
    // workflow_owner gate role at every PAUSABLE_SOURCE_STATES arm (Investigating,
    // WaitingForSpecApproval, Executing, WaitingForReview, WaitingForManualExecution,
    // WaitingForLintApproval, WaitingForDelivery, Failed). Guard against a silent rename (lockstep
    // with allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.PAUSE_WORKFLOW.value()).isEqualTo("pause_workflow");
  }

  @Test
  void classifyFailureWireValueIsPinned() {
    // Story 4.9 AC11 — RecoveryService.classifyFailure (a pure metadata operation, no transition)
    // is the canonical executor; surfaced ONLY at FAILED for the workflow_owner gate role. Guard
    // against a silent rename (lockstep with allowed-actions.placeholder.json +
    // RegistryContractTest).
    assertThat(AllowedAction.CLASSIFY_FAILURE.value()).isEqualTo("classify_failure");
  }

  @Test
  void enterCompareModeWireValueIsPinned() {
    // Story 4.20 AC9 — Compare Mode is a read-only FE inspection surface (story-4.19 compare
    // endpoint); there is NO backend executor. Surfaced via appendCompareOverlay for the
    // reviewing/inspecting roles at WAITING_FOR_SPEC_APPROVAL / WAITING_FOR_REVIEW / FAILED /
    // PAUSED, and the FE re-gates on the concrete artifact version>1. Guard against a silent
    // rename (lockstep with allowed-actions.placeholder.json + RegistryContractTest).
    assertThat(AllowedAction.ENTER_COMPARE_MODE.value()).isEqualTo("enter_compare_mode");
  }
}
