package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.RunnerExecutionEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.RunnerExecutionEntityMapper;
import org.dradgo.adapters.persistence.repository.RunnerExecutionRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class RunnerExecutionPersistenceAdapterManualTest {

  private static final String RUN_ID = "run_manualversion01";

  @Test
  void allocatedInsertLocksRunBeforeSelectingNextBundleVersion() {
    RunnerExecutionRepository executionRepository = mock(RunnerExecutionRepository.class);
    WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
    RunnerExecutionEntityMapper mapper = mock(RunnerExecutionEntityMapper.class);
    WorkflowRunEntity run = WorkflowRunEntity.create(RUN_ID, WorkflowState.EXECUTING);
    RunnerExecutionEntity prior = new RunnerExecutionEntity();
    prior.setContextBundleVersion(6);
    when(runRepository.findByPublicIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
    when(executionRepository.findFirstByWorkflowRunPublicIdAndStageOrderByContextBundleVersionDesc(
            RUN_ID, RunnerStage.EXECUTION.value()))
        .thenReturn(Optional.of(prior));
    when(executionRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    when(mapper.toSnapshot(any())).thenReturn(mock(RunnerExecutionSnapshot.class));
    RunnerExecutionPersistenceAdapter adapter =
        new RunnerExecutionPersistenceAdapter(
            executionRepository,
            runRepository,
            mapper,
            mock(NamedParameterJdbcTemplate.class),
            Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC));

    adapter.insertAwaitingManualAllocated(
        "rex_manualversion01", RUN_ID, RunnerStage.EXECUTION, "idem-manual-version-1");

    InOrder order = inOrder(runRepository, executionRepository);
    order.verify(runRepository).findByPublicIdForUpdate(RUN_ID);
    order
        .verify(executionRepository)
        .findFirstByWorkflowRunPublicIdAndStageOrderByContextBundleVersionDesc(
            RUN_ID, RunnerStage.EXECUTION.value());
  }

  @Test
  void pinReviewedArtifactStoresTheTripleAndFindReadsItBack() {
    // Story 3d-2 (code-review D1) — the reviewed-artifact pin: pinReviewedArtifact writes
    // (id, version, type) onto the reviewer row; findReviewedArtifactPin reads it back. (Column
    // existence/types are pinned by FlywaySchemaContractTest; this pins the adapter mapping logic.)
    RunnerExecutionRepository executionRepository = mock(RunnerExecutionRepository.class);
    WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
    RunnerExecutionEntityMapper mapper = mock(RunnerExecutionEntityMapper.class);
    RunnerExecutionEntity reviewer = new RunnerExecutionEntity();
    reviewer.setPublicId("rex_reviewerpin01");
    when(executionRepository.findByPublicIdForUpdate("rex_reviewerpin01"))
        .thenReturn(Optional.of(reviewer));
    when(executionRepository.findByPublicId("rex_reviewerpin01")).thenReturn(Optional.of(reviewer));
    when(executionRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    RunnerExecutionPersistenceAdapter adapter =
        new RunnerExecutionPersistenceAdapter(
            executionRepository,
            runRepository,
            mapper,
            mock(NamedParameterJdbcTemplate.class),
            Clock.systemUTC());

    // Before pinning, no pin is present.
    org.junit.jupiter.api.Assertions.assertTrue(
        adapter.findReviewedArtifactPin("rex_reviewerpin01").isEmpty());

    adapter.pinReviewedArtifact("rex_reviewerpin01", "art_pinned0001", 3, "prOutput");

    org.junit.jupiter.api.Assertions.assertEquals(
        "art_pinned0001", reviewer.getReviewedArtifactId());
    org.junit.jupiter.api.Assertions.assertEquals(3, reviewer.getReviewedArtifactVersion());
    org.junit.jupiter.api.Assertions.assertEquals("prOutput", reviewer.getReviewedArtifactType());

    Optional<org.dradgo.application.runner.spi.RunnerExecutionRecordPort.ReviewedArtifactPin> pin =
        adapter.findReviewedArtifactPin("rex_reviewerpin01");
    org.junit.jupiter.api.Assertions.assertTrue(pin.isPresent());
    org.junit.jupiter.api.Assertions.assertEquals("art_pinned0001", pin.get().artifactPublicId());
    org.junit.jupiter.api.Assertions.assertEquals(3, pin.get().version());
    org.junit.jupiter.api.Assertions.assertEquals("prOutput", pin.get().artifactType());
  }

  @Test
  void allocatedInsertRejectsRunAlreadyWaitingForManualBeforeVersionSelection() {
    RunnerExecutionRepository executionRepository = mock(RunnerExecutionRepository.class);
    WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
    WorkflowRunEntity run =
        WorkflowRunEntity.create(RUN_ID, WorkflowState.WAITING_FOR_MANUAL_EXECUTION);
    when(runRepository.findByPublicIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
    RunnerExecutionPersistenceAdapter adapter =
        new RunnerExecutionPersistenceAdapter(
            executionRepository,
            runRepository,
            mock(RunnerExecutionEntityMapper.class),
            mock(NamedParameterJdbcTemplate.class),
            Clock.systemUTC());

    assertThatThrownBy(
            () ->
                adapter.insertAwaitingManualAllocated(
                    "rex_manualversion02", RUN_ID, RunnerStage.EXECUTION, null))
        .isInstanceOf(DomainException.class);

    verify(executionRepository, never())
        .findFirstByWorkflowRunPublicIdAndStageOrderByContextBundleVersionDesc(any(), any());
  }
}
