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
}
