package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.WorkflowRunEntityMapper;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(OutputCaptureExtension.class)
class WorkflowRunPersistenceAdapterTest {
  @Test
  void createThreadsParentRunIdIntoNewEntityAndSnapshot(CapturedOutput output) {
    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    WorkflowRunPersistenceAdapter adapter =
        new WorkflowRunPersistenceAdapter(
            repository, new WorkflowRunEntityMapper(), mock(NamedParameterJdbcTemplate.class));
    when(repository.saveAndFlush(any(WorkflowRunEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    adapter.create("run_child1234", WorkflowState.INBOX, "prj_default", "run_parent1234");

    verify(repository)
        .saveAndFlush(
            org.mockito.ArgumentMatchers.argThat(
                entity -> "run_parent1234".equals(entity.getParentRunId())));
    assertTrue(
        output.getOut().contains("creating child run run_child1234 under parent run_parent1234"));
  }

  @Test
  void updateCurrentStateTranslatesMissingRunIntoStableRunNotFoundError() {
    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    WorkflowRunPersistenceAdapter adapter =
        new WorkflowRunPersistenceAdapter(
            repository, new WorkflowRunEntityMapper(), mock(NamedParameterJdbcTemplate.class));

    when(repository.updateCurrentState("run_missing1234", WorkflowState.EXECUTING.value(), 4L))
        .thenReturn(0);
    when(repository.existsByPublicId("run_missing1234")).thenReturn(false);

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> adapter.updateCurrentState("run_missing1234", WorkflowState.EXECUTING, 4L));

    assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
    assertEquals("run_missing1234", error.details().get("runId"));
  }

  @Test
  void updateCurrentStateKeepsOptimisticLockFailuresForExistingRuns() {
    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    WorkflowRunPersistenceAdapter adapter =
        new WorkflowRunPersistenceAdapter(
            repository, new WorkflowRunEntityMapper(), mock(NamedParameterJdbcTemplate.class));

    when(repository.updateCurrentState("run_conflict1234", WorkflowState.EXECUTING.value(), 7L))
        .thenReturn(0);
    when(repository.existsByPublicId("run_conflict1234")).thenReturn(true);

    assertThrows(
        OptimisticLockingFailureException.class,
        () -> adapter.updateCurrentState("run_conflict1234", WorkflowState.EXECUTING, 7L));
  }
}
