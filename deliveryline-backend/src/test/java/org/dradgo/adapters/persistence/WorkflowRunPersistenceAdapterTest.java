package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.dradgo.adapters.persistence.mapper.WorkflowRunEntityMapper;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class WorkflowRunPersistenceAdapterTest {

	@Test
	void updateCurrentStateTranslatesMissingRunIntoStableRunNotFoundError() {
		WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
		WorkflowRunPersistenceAdapter adapter = new WorkflowRunPersistenceAdapter(
			repository,
			new WorkflowRunEntityMapper());

		when(repository.updateCurrentState("run_missing1234", WorkflowState.EXECUTING.value(), 4L)).thenReturn(0);
		when(repository.existsByPublicId("run_missing1234")).thenReturn(false);

		DomainException error = assertThrows(
			DomainException.class,
			() -> adapter.updateCurrentState("run_missing1234", WorkflowState.EXECUTING, 4L));

		assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
		assertEquals("run_missing1234", error.details().get("runId"));
	}

	@Test
	void updateCurrentStateKeepsOptimisticLockFailuresForExistingRuns() {
		WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
		WorkflowRunPersistenceAdapter adapter = new WorkflowRunPersistenceAdapter(
			repository,
			new WorkflowRunEntityMapper());

		when(repository.updateCurrentState("run_conflict1234", WorkflowState.EXECUTING.value(), 7L)).thenReturn(0);
		when(repository.existsByPublicId("run_conflict1234")).thenReturn(true);

		assertThrows(
			OptimisticLockingFailureException.class,
			() -> adapter.updateCurrentState("run_conflict1234", WorkflowState.EXECUTING, 7L));
	}
}
