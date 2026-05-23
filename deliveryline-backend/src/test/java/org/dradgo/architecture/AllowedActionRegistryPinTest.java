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
}
