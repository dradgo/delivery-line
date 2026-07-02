package org.dradgo.adapters.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.adapters.persistence.entity.RunnerExecutionEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

class RunnerExecutionEntityMapperTest {

  private static final RunnerExecutionEntityMapper MAPPER = new RunnerExecutionEntityMapper();

  private static RunnerExecutionEntity baseEntity() {
    RunnerExecutionEntity entity = new RunnerExecutionEntity();
    entity.setPublicId("rex_mappertest001");
    entity.setWorkflowRun(WorkflowRunEntity.create("run_mappertest0001", WorkflowState.EXECUTING));
    entity.setStage(RunnerStage.INVESTIGATION);
    entity.setStatus(RunnerExecutionStatus.RUNNING);
    entity.setContextBundleVersion(1);
    return entity;
  }

  @Test
  void mapsTheThreeV31TokenColumnsOntoTheSnapshot() {
    // Story 3g-3 (FR74) — the read-path mapper passes the three token columns through to the
    // snapshot end-to-end.
    RunnerExecutionEntity entity = baseEntity();
    entity.setInputTokens(1200);
    entity.setOutputTokens(800);
    entity.setTotalTokens(2000);

    RunnerExecutionSnapshot snapshot = MAPPER.toSnapshot(entity);

    assertThat(snapshot.inputTokens()).isEqualTo(1200);
    assertThat(snapshot.outputTokens()).isEqualTo(800);
    assertThat(snapshot.totalTokens()).isEqualTo(2000);
  }

  @Test
  void mapsNullTokenColumnsAsNullOnTheSnapshot() {
    // Null parity — a pre-3g / no-usage row carries NULL token columns (never 0).
    RunnerExecutionSnapshot snapshot = MAPPER.toSnapshot(baseEntity());

    assertThat(snapshot.inputTokens()).isNull();
    assertThat(snapshot.outputTokens()).isNull();
    assertThat(snapshot.totalTokens()).isNull();
  }
}
