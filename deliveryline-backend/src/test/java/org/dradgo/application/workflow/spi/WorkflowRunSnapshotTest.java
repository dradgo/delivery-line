package org.dradgo.application.workflow.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

class WorkflowRunSnapshotTest {

  @Test
  void requiredVersionFailsClosedWhenTheSnapshotCarriesNoOptimisticLockValue() throws Exception {
    WorkflowRunSnapshot snapshot =
        new WorkflowRunSnapshot("run_missingversion1234", WorkflowState.INBOX, null, null, 0, false);
    Method method = WorkflowRunSnapshot.class.getMethod("requiredVersion");

    InvocationTargetException error =
        assertThrows(InvocationTargetException.class, () -> method.invoke(snapshot));

    DomainException cause = (DomainException) error.getCause();
    assertEquals(DomainErrorCode.INTERNAL_ERROR, cause.errorCode());
    assertEquals("run_missingversion1234", cause.details().get("runId"));
  }
}
