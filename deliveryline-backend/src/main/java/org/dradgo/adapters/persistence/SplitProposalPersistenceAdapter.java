package org.dradgo.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.workflow.NewSplitProposal;
import org.dradgo.application.workflow.SplitDependencyView;
import org.dradgo.application.workflow.SplitProposalView;
import org.dradgo.application.workflow.SplitSubtaskView;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.SplitProposalWritePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC adapter for the split-proposal store (story 3f-4). Persists the advisory decomposition into
 * {@code split_proposals} (one {@code open} per run, enforced by the partial unique index) and the
 * redacted re-propose feedback into {@code split_proposal_feedback}. No JPA entity: the
 * lifecycle/loop_count/payload shape and the supersede-then-insert flow are plain SQL. The decoded
 * read views ({@link SplitProposalView}) live in {@code application.workflow}, not {@code .spi}
 * (REST-stays-thin pin). Statements execute immediately on the connection (no JPA flush trap —
 * supersede-then-insert in one tx never leaves two {@code open} rows).
 */
@Component
public class SplitProposalPersistenceAdapter
    implements SplitProposalReadPort, SplitProposalWritePort {

  private static final Logger log = LoggerFactory.getLogger(SplitProposalPersistenceAdapter.class);

  private static final String INSERT_OPEN_SQL =
      """
      insert into split_proposals (
          public_id, workflow_run_id, reviewed_artifact_id, reviewed_artifact_version,
          status, loop_count, proposal_json, reviewer_model_identity, producer_model_identity)
      values (
          :publicId, :workflowRunId, :reviewedArtifactId, :reviewedArtifactVersion,
          'open', :loopCount, :proposalJson, :reviewerModelIdentity, :producerModelIdentity)
      """;

  private static final String SUPERSEDE_OPEN_SQL =
      """
      update split_proposals set status = 'superseded'
       where workflow_run_id = :workflowRunId and status = 'open'
      """;

  private static final String DISMISS_OPEN_SQL =
      """
      update split_proposals set status = 'dismissed'
       where workflow_run_id = :workflowRunId and status = 'open'
      """;

  private static final String SELECT_COLUMNS =
      """
      select public_id, workflow_run_id, reviewed_artifact_id, reviewed_artifact_version,
             status, loop_count, proposal_json, reviewer_model_identity, producer_model_identity,
             created_at
        from split_proposals
      """;

  private static final String FIND_OPEN_SQL =
      SELECT_COLUMNS + " where workflow_run_id = :workflowRunId and status = 'open'";

  private static final String FIND_LATEST_SQL =
      SELECT_COLUMNS
          + """
           where workflow_run_id = :workflowRunId
           order by case when status = 'open' then 0 else 1 end, created_at desc, id desc
           limit 1
          """;

  private static final String HAS_OPEN_SQL =
      """
      select count(*) from split_proposals
       where workflow_run_id = :workflowRunId and status = 'open'
      """;

  private static final String INSERT_FEEDBACK_SQL =
      """
      insert into split_proposal_feedback (public_id, runner_execution_id, feedback_text)
      values (:publicId, :runnerExecutionId, :feedbackText)
      """;

  private static final String FIND_FEEDBACK_REF_SQL =
      """
      select public_id from split_proposal_feedback
       where runner_execution_id = :runnerExecutionId
       order by created_at desc, id desc
       limit 1
      """;

  private static final String CURRENT_LOOP_COUNT_SQL =
      "select split_proposal_loop_count from workflow_runs where public_id = :workflowRunId";

  private final NamedParameterJdbcTemplate jdbcTemplate;
  // Plain instance (not an injected bean) — the codebase has no shared ObjectMapper bean; mirrors
  // ContextBundleService / ReviewResultHarvester. Used only for the proposal_json tree round-trip.
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SplitProposalPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void insertOpen(NewSplitProposal proposal) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("publicId", proposal.publicId())
            .addValue("workflowRunId", proposal.workflowRunId())
            .addValue("reviewedArtifactId", proposal.reviewedArtifactId())
            .addValue("reviewedArtifactVersion", proposal.reviewedArtifactVersion())
            .addValue("loopCount", proposal.loopCount())
            .addValue("proposalJson", proposal.proposalJson())
            .addValue("reviewerModelIdentity", proposal.reviewerModelIdentity())
            .addValue("producerModelIdentity", proposal.producerModelIdentity());
    jdbcTemplate.update(INSERT_OPEN_SQL, params);
    log.info(
        "split_proposals open inserted splitProposalId={} workflowRunId={}",
        proposal.publicId(),
        proposal.workflowRunId());
  }

  @Override
  public int supersedeOpenForRun(String workflowRunId) {
    int updated =
        jdbcTemplate.update(
            SUPERSEDE_OPEN_SQL, new MapSqlParameterSource("workflowRunId", workflowRunId));
    log.info("split_proposals supersede workflowRunId={} updated={}", workflowRunId, updated);
    return updated;
  }

  @Override
  public int dismissOpenForRun(String workflowRunId) {
    int updated =
        jdbcTemplate.update(
            DISMISS_OPEN_SQL, new MapSqlParameterSource("workflowRunId", workflowRunId));
    log.info("split_proposals dismiss workflowRunId={} updated={}", workflowRunId, updated);
    return updated;
  }

  @Override
  public void insertFeedback(String publicId, String runnerExecutionId, String feedbackText) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("publicId", publicId)
            .addValue("runnerExecutionId", runnerExecutionId)
            .addValue("feedbackText", feedbackText);
    jdbcTemplate.update(INSERT_FEEDBACK_SQL, params);
    log.info(
        "split_proposal_feedback inserted feedbackId={} runnerExecutionId={}",
        publicId,
        runnerExecutionId);
  }

  @Override
  public boolean hasOpenForRun(String workflowRunId) {
    Integer count =
        jdbcTemplate.queryForObject(
            HAS_OPEN_SQL, new MapSqlParameterSource("workflowRunId", workflowRunId), Integer.class);
    return count != null && count > 0;
  }

  @Override
  public Optional<SplitProposalView> findOpenForRun(String workflowRunId) {
    return jdbcTemplate
        .query(FIND_OPEN_SQL, new MapSqlParameterSource("workflowRunId", workflowRunId), this::map)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<SplitProposalView> findLatestForRun(String workflowRunId) {
    return jdbcTemplate
        .query(
            FIND_LATEST_SQL, new MapSqlParameterSource("workflowRunId", workflowRunId), this::map)
        .stream()
        .findFirst();
  }

  @Override
  public int currentSplitProposalLoopCount(String workflowRunId) {
    Integer count =
        jdbcTemplate.queryForObject(
            CURRENT_LOOP_COUNT_SQL,
            new MapSqlParameterSource("workflowRunId", workflowRunId),
            Integer.class);
    return count == null ? 0 : count;
  }

  @Override
  public Optional<String> findFeedbackReferenceId(String runnerExecutionId) {
    return jdbcTemplate
        .query(
            FIND_FEEDBACK_REF_SQL,
            new MapSqlParameterSource("runnerExecutionId", runnerExecutionId),
            (rs, rowNum) -> rs.getString("public_id"))
        .stream()
        .findFirst();
  }

  private SplitProposalView map(ResultSet rs, int rowNum) throws SQLException {
    String reviewer = rs.getString("reviewer_model_identity");
    String producer = rs.getString("producer_model_identity");
    boolean selfReview = reviewer != null && reviewer.equals(producer);
    Integer reviewedVersion = (Integer) rs.getObject("reviewed_artifact_version");
    java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
    String proposalJson = rs.getString("proposal_json");
    List<SplitSubtaskView> subtasks = new ArrayList<>();
    List<SplitDependencyView> dependencies = new ArrayList<>();
    decodeProposalJson(proposalJson, subtasks, dependencies);
    return new SplitProposalView(
        rs.getString("public_id"),
        rs.getString("workflow_run_id"),
        rs.getString("status"),
        rs.getInt("loop_count"),
        rs.getString("reviewed_artifact_id"),
        reviewedVersion,
        reviewer,
        producer,
        selfReview,
        List.copyOf(subtasks),
        List.copyOf(dependencies),
        createdAt == null ? null : createdAt.toInstant());
  }

  // Decode the redacted {"subtasks":[...],"dependencies":[...]} payload. Best-effort: a malformed
  // payload (should not happen post-harvest) yields empty lists rather than a 500 on a read path.
  private void decodeProposalJson(
      String proposalJson,
      List<SplitSubtaskView> subtasks,
      List<SplitDependencyView> dependencies) {
    if (proposalJson == null || proposalJson.isBlank()) {
      return;
    }
    try {
      JsonNode root = objectMapper.readTree(proposalJson);
      JsonNode subtasksNode = root.path("subtasks");
      if (subtasksNode.isArray()) {
        for (JsonNode s : subtasksNode) {
          subtasks.add(
              new SplitSubtaskView(
                  s.path("ordinal").asInt(),
                  s.path("title").asText(""),
                  s.path("scope").asText("")));
        }
      }
      JsonNode dependenciesNode = root.path("dependencies");
      if (dependenciesNode.isArray()) {
        for (JsonNode d : dependenciesNode) {
          dependencies.add(
              new SplitDependencyView(d.path("fromOrdinal").asInt(), d.path("toOrdinal").asInt()));
        }
      }
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException decodeError) {
      log.warn(
          "split_proposals proposal_json decode failed (returning empty decomposition) cause={}",
          decodeError.getClass().getSimpleName());
    }
  }
}
