package org.dradgo.application.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3f-5 (AC8 / V30) — the {@code internal_subtask} child→parent-ticket linkage seam. Pins the
 * V30 uniqueness re-typing: MANY internal-only children of one parent legally share the parent's
 * external_ref (the cross-run uniqueness index now exempts {@code internal_subtask}), while the V1
 * per-run uniqueness still makes a replay of the SAME child an idempotent no-op.
 *
 * <p>{@code @Transactional} supplies the ambient tx {@link
 * IntegrationLinkService#linkSplitChildTicket} requires ({@link
 * org.springframework.transaction.annotation.Propagation#MANDATORY}); rows roll back per test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@Transactional
class IntegrationLinkSplitChildIT {

  private static final String PARENT_TICKET = "LIN-PARENT-3f5";
  private static final ActorContext ACTOR = new ActorContext("alex", ActorType.HUMAN, "corr-3f5");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationLinkService integrationLinkService;
  @Autowired private WorkflowRunCreatePort createPort;

  private String newChild() {
    String id = PublicIdPrefixes.WORKFLOW_RUN.next();
    createPort.create(id, WorkflowState.INBOX, null);
    return id;
  }

  private int internalSubtaskLinkCount(String runId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from integration_links il join workflow_runs r on il.workflow_run_id = r.id"
                + " where r.public_id = ? and il.integration_type = 'internal_subtask'",
            Integer.class,
            runId);
    return count == null ? 0 : count;
  }

  @Test
  void manyInternalOnlyChildrenShareOneParentTicketRef() {
    String childA = newChild();
    String childB = newChild();

    IntegrationLink linkA =
        integrationLinkService.linkSplitChildTicket(childA, PARENT_TICKET, ACTOR);
    IntegrationLink linkB =
        integrationLinkService.linkSplitChildTicket(childB, PARENT_TICKET, ACTOR);

    // Both children link to the SAME parent ref under the internal_subtask type (V30 exemption from
    // the cross-run uniqueness index) — no INTEGRATION_LINK_CONFLICT.
    assertThat(linkA.externalRef()).isEqualTo(PARENT_TICKET);
    assertThat(linkB.externalRef()).isEqualTo(PARENT_TICKET);
    assertThat(linkA.publicId()).isNotEqualTo(linkB.publicId());
    assertThat(internalSubtaskLinkCount(childA)).isEqualTo(1);
    assertThat(internalSubtaskLinkCount(childB)).isEqualTo(1);
  }

  @Test
  void replayOfTheSameChildIsAnIdempotentNoOp() {
    String child = newChild();

    IntegrationLink first =
        integrationLinkService.linkSplitChildTicket(child, PARENT_TICKET, ACTOR);
    IntegrationLink replay =
        integrationLinkService.linkSplitChildTicket(child, PARENT_TICKET, ACTOR);

    // The V1 per-run uniqueness makes a replay return the existing row, never a second insert.
    assertThat(replay.publicId()).isEqualTo(first.publicId());
    assertThat(internalSubtaskLinkCount(child)).isEqualTo(1);
  }
}
