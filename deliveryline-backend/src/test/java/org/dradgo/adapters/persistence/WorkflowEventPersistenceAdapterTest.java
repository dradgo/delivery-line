package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.mapper.WorkflowEventEntityMapper;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

class WorkflowEventPersistenceAdapterTest {

  @Test
  void appendTranslatesMissingRunIntoStableRunNotFoundError() {
    WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
    WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    WorkflowEventEntityMapper mapper = new WorkflowEventEntityMapper();
    WorkflowEventPersistenceAdapter adapter =
        new WorkflowEventPersistenceAdapter(workflowEventRepository, workflowRunRepository, mapper);

    when(workflowRunRepository.findByPublicId("run_missing1234")).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                adapter.append(
                    new WorkflowEventRecord(
                        "evt_missing1234",
                        "run_missing1234",
                        WorkflowEventType.WORKFLOW_STATE_CHANGED,
                        WorkflowState.INBOX,
                        WorkflowState.EXECUTING,
                        "alex",
                        ActorType.HUMAN,
                        "transition",
                        null,
                        false,
                        OffsetDateTime.now(),
                        Map.of("idempotencyKey", "idem-1234567890"))));

    assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
    assertEquals("run_missing1234", error.details().get("runId"));
  }
}
