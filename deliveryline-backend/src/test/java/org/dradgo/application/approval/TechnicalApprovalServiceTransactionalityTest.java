package org.dradgo.application.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.RejectImplementationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3.20/3.21 (Trap T4) pin — both {@link TechnicalApprovalService#acceptImplementation} and
 * {@link TechnicalApprovalService#rejectImplementation} MUST declare {@code Propagation.MANDATORY}
 * so they only ever execute inside the outer {@code WorkflowCommandService} {@code @Transactional}
 * boundary. A {@code REQUIRES_NEW} (or a missing annotation defaulting to {@code REQUIRED}) would
 * persist the approval row + events even when the surrounding transition rolls back, breaking the
 * all-or-nothing invariant. Pinned reflectively so the contract survives refactors without a full
 * Spring context boot.
 */
class TechnicalApprovalServiceTransactionalityTest {

  @Test
  void acceptImplementationDeclaresMandatoryPropagation() throws NoSuchMethodException {
    assertMandatory(
        TechnicalApprovalService.class.getMethod(
            "acceptImplementation", AcceptImplementationCommand.class));
  }

  @Test
  void rejectImplementationDeclaresMandatoryPropagation() throws NoSuchMethodException {
    assertMandatory(
        TechnicalApprovalService.class.getMethod(
            "rejectImplementation", RejectImplementationCommand.class));
  }

  private static void assertMandatory(Method method) {
    Transactional transactional = method.getAnnotation(Transactional.class);
    assertThat(transactional)
        .as("%s must be annotated @Transactional", method.getName())
        .isNotNull();
    assertThat(transactional.propagation())
        .as("%s must use Propagation.MANDATORY (Trap T4)", method.getName())
        .isEqualTo(Propagation.MANDATORY);
  }
}
