package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@Transactional
class WorkflowRunLineagePersistenceIT {

  @Autowired private WorkflowRunCreatePort createPort;
  @Autowired private WorkflowRunReadPort readPort;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void childRunRoundTripsThroughCreatePortSnapshotAndParentFinder() {
    String parent = PublicIdPrefixes.WORKFLOW_RUN.next();
    String child = PublicIdPrefixes.WORKFLOW_RUN.next();
    createPort.create(parent, WorkflowState.INBOX, null);

    WorkflowRunSnapshot childSnapshot = createPort.create(child, WorkflowState.INBOX, null, parent);

    assertThat(childSnapshot.parentRunId()).isEqualTo(parent);
    assertThat(readPort.findByPublicId(child).orElseThrow().parentRunId()).isEqualTo(parent);
    assertThat(readPort.findByParentRunId(parent).stream().map(WorkflowRunSnapshot::publicId))
        .containsExactly(child);
  }

  @Test
  void parentRunIdForeignKeyRejectsDanglingParentRows() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "insert into workflow_runs (public_id, current_state, parent_run_id) values (?, 'Inbox', 'run_missing_parent')",
                    PublicIdPrefixes.WORKFLOW_RUN.next()))
        .hasMessageContaining("fk_workflow_runs_parent_run");
  }

  @Test
  void parentRunIdCheckRejectsSelfParentRows() {
    String self = PublicIdPrefixes.WORKFLOW_RUN.next();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "insert into workflow_runs (public_id, current_state, parent_run_id) values (?, 'Inbox', ?)",
                    self,
                    self))
        .hasMessageContaining("ck_workflow_runs_parent_run_not_self");
  }
}
