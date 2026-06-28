package org.dradgo.adapters.persistence;

import java.util.List;
import org.dradgo.application.workflow.BlockedDependencyView;
import org.dradgo.application.workflow.spi.RunDependencyPort;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * JDBC adapter for the run-dependency DAG (story 3f-3). Stores directed edges {@code run_id ->
 * depends_on_run_id} in the {@code run_dependencies} join table and answers reachability / blocked
 * queries by joining to {@code workflow_runs.current_state}. No JPA entity is used: the table has
 * no {@code public_id} (the composite PK is the identity) and the cycle probe is a recursive CTE.
 */
@Component
public class RunDependencyPersistenceAdapter implements RunDependencyPort {

  private static final Logger log = LoggerFactory.getLogger(RunDependencyPersistenceAdapter.class);

  private static final String INSERT_EDGE_SQL =
      """
      insert into run_dependencies (run_id, depends_on_run_id)
      values (:runId, :dependsOnRunId)
      on conflict (run_id, depends_on_run_id) do nothing
      """;

  private static final String FIND_PREREQUISITES_SQL =
      """
      select rd.depends_on_run_id as run_id, wr.current_state as state
        from run_dependencies rd
        join workflow_runs wr on wr.public_id = rd.depends_on_run_id
       where rd.run_id = :runId
       order by rd.depends_on_run_id
      """;

  private static final String FIND_DEPENDENTS_SQL =
      """
      select rd.run_id as run_id, wr.current_state as state
        from run_dependencies rd
        join workflow_runs wr on wr.public_id = rd.run_id
       where rd.depends_on_run_id = :runId
       order by rd.run_id
      """;

  // The 'Completed' gate is bound from WorkflowState.COMPLETED.value() (review 3f-3 P3) so the
  // blocked/release SQL can never silently diverge from the enum if the wire value ever changes.
  private static final String FIND_BLOCKED_ON_SQL =
      """
      select rd.depends_on_run_id as run_id, wr.current_state as state
        from run_dependencies rd
        join workflow_runs wr on wr.public_id = rd.depends_on_run_id
       where rd.run_id = :runId
         and wr.current_state <> :completedState
       order by rd.depends_on_run_id
      """;

  private static final String ALL_PREREQUISITES_COMPLETED_SQL =
      """
      select not exists (
        select 1
          from run_dependencies rd
          join workflow_runs wr on wr.public_id = rd.depends_on_run_id
         where rd.run_id = :runId
           and wr.current_state <> :completedState
      )
      """;

  // Single application-wide advisory lock key for the run-dependency graph. Transaction-scoped, so
  // it auto-releases on commit/rollback. 0x52444550 == "RDEP". Declaration and the release resolver
  // both take it, serializing graph mutations (review 3f-3 D1/D2).
  private static final long DEPENDENCY_GRAPH_ADVISORY_LOCK_KEY = 0x52444550L;

  private static final String LOCK_DEPENDENCY_GRAPH_SQL = "select pg_advisory_xact_lock(:lockKey)";

  // Cycle probe: starting from the proposed prerequisite, walk the "depends on" edges transitively.
  // If the proposed dependent is reachable, it already (transitively) blocks the prerequisite, so
  // adding runId -> dependsOnRunId would close a cycle.
  private static final String WOULD_CREATE_CYCLE_SQL =
      """
      with recursive reachable(node) as (
        select depends_on_run_id
          from run_dependencies
         where run_id = :dependsOnRunId
        union
        select rd.depends_on_run_id
          from run_dependencies rd
          join reachable r on rd.run_id = r.node
      )
      select exists (select 1 from reachable where node = :runId)
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public RunDependencyPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void addDependencies(String runId, List<String> dependsOnRunIds) {
    if (dependsOnRunIds == null || dependsOnRunIds.isEmpty()) {
      return;
    }
    SqlParameterSource[] batch =
        dependsOnRunIds.stream()
            .map(
                dependsOn ->
                    (SqlParameterSource)
                        new MapSqlParameterSource()
                            .addValue("runId", runId)
                            .addValue("dependsOnRunId", dependsOn))
            .toArray(SqlParameterSource[]::new);
    int[] inserted = jdbcTemplate.batchUpdate(INSERT_EDGE_SQL, batch);
    int newEdges = 0;
    for (int rows : inserted) {
      newEdges += rows;
    }
    log.info(
        "run-dependency edges persisted workflowRunId={} declaredCount={} newEdgeCount={}",
        runId,
        dependsOnRunIds.size(),
        newEdges);
  }

  @Override
  public List<BlockedDependencyView> findPrerequisites(String runId) {
    return jdbcTemplate.query(
        FIND_PREREQUISITES_SQL, new MapSqlParameterSource("runId", runId), this::mapRow);
  }

  @Override
  public List<BlockedDependencyView> findDependents(String runId) {
    return jdbcTemplate.query(
        FIND_DEPENDENTS_SQL, new MapSqlParameterSource("runId", runId), this::mapRow);
  }

  @Override
  public List<BlockedDependencyView> findBlockedOn(String runId) {
    return jdbcTemplate.query(
        FIND_BLOCKED_ON_SQL,
        new MapSqlParameterSource("runId", runId)
            .addValue("completedState", WorkflowState.COMPLETED.value()),
        this::mapRow);
  }

  @Override
  public boolean allPrerequisitesCompleted(String runId) {
    Boolean result =
        jdbcTemplate.queryForObject(
            ALL_PREREQUISITES_COMPLETED_SQL,
            new MapSqlParameterSource("runId", runId)
                .addValue("completedState", WorkflowState.COMPLETED.value()),
            Boolean.class);
    return Boolean.TRUE.equals(result);
  }

  @Override
  public void lockDependencyGraph() {
    jdbcTemplate.queryForObject(
        LOCK_DEPENDENCY_GRAPH_SQL,
        new MapSqlParameterSource("lockKey", DEPENDENCY_GRAPH_ADVISORY_LOCK_KEY),
        Object.class);
  }

  @Override
  public boolean wouldCreateCycle(String runId, String dependsOnRunId) {
    Boolean result =
        jdbcTemplate.queryForObject(
            WOULD_CREATE_CYCLE_SQL,
            new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("dependsOnRunId", dependsOnRunId),
            Boolean.class);
    return Boolean.TRUE.equals(result);
  }

  private BlockedDependencyView mapRow(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new BlockedDependencyView(
        rs.getString("run_id"),
        WorkflowState.fromValue(rs.getString("state"), "run_dependencies.current_state"));
  }
}
