package org.dradgo.adapters.persistence;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.workflow.spi.OperatorRunAggregate;
import org.dradgo.application.workflow.spi.OperatorRunQuery;
import org.dradgo.application.workflow.spi.OperatorRunReadPort;
import org.dradgo.application.workflow.spi.OperatorRunRowSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 4.1 (AC2/AC6/Reconciliation 3/5) — the {@code adapters.persistence} implementation of
 * {@link OperatorRunReadPort}. A dedicated adapter (not folded into {@code
 * WorkflowRunPersistenceAdapter}) so the operator fleet join-query stays isolated and no existing
 * read adapter's constructor fans out.
 *
 * <p>The fleet row is a JOIN/derivation: {@code workflow_runs} LEFT JOIN LATERAL its latest event
 * (transition time / actor / intervention marker), its latest {@code Failed} transition (failure
 * category), its earliest event ({@code MIN(created_at)}), and its active typed {@code
 * integration_links} (linear ticket + github PR). Each lateral rides {@code
 * idx_workflow_events_workflow_run_id_created_at} / {@code
 * idx_integration_links_run_id_type_created_at} so the whole scan uses {@code
 * idx_workflow_runs_current_state_created_at} at the top — no new index for correctness (story 4.1
 * Reconciliation 8).
 *
 * <p>Every time comparison is computed server-side ({@code now() - make_interval(...)}) so the
 * result is clock-drift-free (story 4.1 Reconciliation 4), mirroring {@code
 * RunnerExecutionPersistenceAdapter.loadQueueCounts}. Verify on real Postgres, not mocks — the
 * correlated Failed-transition lateral and the {@code intervention_marker} predicate only exercise
 * on PG.
 */
@Component
public class OperatorRunPersistenceAdapter implements OperatorRunReadPort {

  private static final Logger log = LoggerFactory.getLogger(OperatorRunPersistenceAdapter.class);

  // Shared per-run derivation. The predicate WHERE (below) references these columns; both the
  // aggregate and the row query embed this CTE so the two surfaces derive facts identically.
  private static final String RUN_FACTS_CTE =
      """
      with run_facts as (
        select
          r.public_id             as run_id,
          r.current_state         as current_state,
          r.escalation_marker_set as escalation_marker,
          le.created_at           as last_transition_at,
          le.actor_identity       as actor_identity,
          coalesce(le.intervention_marker, false) as last_intervention_marker,
          fe.failure_category     as failure_category,
          oe.oldest_event_at      as oldest_event_at,
          lk.external_ref         as linked_ticket_ref,
          gh.external_ref         as linked_pr_ref
        from workflow_runs r
        left join lateral (
          select e.created_at, e.actor_identity, e.intervention_marker
            from workflow_events e
           where e.workflow_run_id = r.id and e.archived_at is null
           order by e.created_at desc, e.id desc
           limit 1
        ) le on true
        left join lateral (
          select e.failure_category
            from workflow_events e
           where e.workflow_run_id = r.id and e.archived_at is null
             and e.resulting_state = 'Failed' and e.prior_state is not null
           order by e.created_at desc, e.id desc
           limit 1
        ) fe on true
        left join lateral (
          select min(e.created_at) as oldest_event_at
            from workflow_events e
           where e.workflow_run_id = r.id and e.archived_at is null
        ) oe on true
        left join lateral (
          select l.external_ref
            from integration_links l
           where l.workflow_run_id = r.id and l.integration_type = 'linear'
             and l.archived_at is null and l.sync_status <> 'superseded'
           order by l.created_at desc, l.id desc
           limit 1
        ) lk on true
        left join lateral (
          select l.external_ref
            from integration_links l
           where l.workflow_run_id = r.id and l.integration_type = 'github_pr'
             and l.archived_at is null and l.sync_status <> 'superseded'
           order by l.created_at desc, l.id desc
           limit 1
        ) gh on true
        where (:includeArchived or r.archived_at is null)
      ),
      matched as (
        select f.* from run_facts f
        where (
            (:selFailed     and f.current_state = 'Failed')
         or (:selOrphaned   and f.current_state = 'Failed' and f.failure_category = 'orphan')
         or (:selTakenover  and f.current_state = 'TakenOver')
         or (:selStalled    and f.current_state in ('Investigating','Executing','WaitingForManualExecution')
                            and f.last_transition_at is not null
                            and f.last_transition_at < now() - make_interval(secs => :stallSecs))
         or (:selOverridden and f.last_intervention_marker = true
                            and f.current_state not in ('Completed','Failed','Reconciled'))
        )
        and (:sinceSecs::double precision is null
             or (f.last_transition_at is not null
                 and f.last_transition_at >= now() - make_interval(secs => :sinceSecs)))
      )
      """;

  // byState grouping carries total (sum of counts) + oldest-entry (min of per-group mins) so the
  // whole aggregate is one CTE evaluation; a separate byFailureCategory query runs only when the
  // filter includes failed/orphaned.
  private static final String AGGREGATE_BY_STATE_SQL =
      RUN_FACTS_CTE
          + """
          select current_state as state, count(*) as cnt, min(oldest_event_at) as grp_oldest
            from matched
           group by current_state
          """;

  // byFailureCategory characterizes the CURRENTLY-Failed fleet (AC2), so it counts only rows whose
  // current_state is 'Failed' — otherwise a TakenOver/Stalled/Overridden run that was PREVIOUSLY
  // Failed (fe carries its historical failure_category) would inflate the histogram (review 4.1).
  private static final String AGGREGATE_BY_FAILURE_SQL =
      RUN_FACTS_CTE
          + """
          select failure_category as category, count(*) as cnt
            from matched
           where current_state = 'Failed' and failure_category is not null
           group by failure_category
          """;

  // The signifier is derived server-side from the matched predicate (same expressions as the CTE),
  // so the CLI renderer needs no matched-token and cannot mislabel an active overridden run as
  // STALLED (review 4.1). Precedence: ORPHANED > FAILED > TAKENOVER > STALLED > OVERRIDDEN > state.
  private static final String LIST_ROWS_SQL =
      RUN_FACTS_CTE
          + """
          select run_id, current_state, failure_category, last_transition_at, actor_identity,
                 linked_ticket_ref, linked_pr_ref, escalation_marker, oldest_event_at,
                 case
                   when current_state = 'Failed' and failure_category = 'orphan' then 'ORPHANED'
                   when current_state = 'Failed' then 'FAILED'
                   when current_state = 'TakenOver' then 'TAKENOVER'
                   when current_state in ('Investigating','Executing','WaitingForManualExecution')
                        and last_transition_at is not null
                        and last_transition_at < now() - make_interval(secs => :stallSecs)
                     then 'STALLED'
                   when last_intervention_marker = true
                        and current_state not in ('Completed','Failed','Reconciled') then 'OVERRIDDEN'
                   else upper(current_state)
                 end as signifier
            from matched
           order by last_transition_at desc nulls last, run_id desc
           limit :limit
          """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public OperatorRunPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  @Transactional(readOnly = true)
  public OperatorRunAggregate loadOperatorRunAggregate(OperatorRunQuery query) {
    Objects.requireNonNull(query, "query");
    if (query.matchesNothing()) {
      return OperatorRunAggregate.empty();
    }
    MapSqlParameterSource params = predicateParams(query);

    Map<String, Integer> countsByState = new LinkedHashMap<>();
    int[] total = {0};
    OffsetDateTime[] oldest = {null};
    jdbcTemplate.query(
        AGGREGATE_BY_STATE_SQL,
        params,
        (RowCallbackHandler)
            rs -> {
              String state = rs.getString("state");
              int cnt = rs.getInt("cnt");
              countsByState.merge(state, cnt, Integer::sum);
              total[0] += cnt;
              OffsetDateTime grpOldest = rs.getObject("grp_oldest", OffsetDateTime.class);
              if (grpOldest != null && (oldest[0] == null || grpOldest.isBefore(oldest[0]))) {
                oldest[0] = grpOldest;
              }
            });

    Map<String, Integer> countsByFailure = new LinkedHashMap<>();
    if (query.includesFailureCategoryHistogram()) {
      jdbcTemplate.query(
          AGGREGATE_BY_FAILURE_SQL,
          params,
          (RowCallbackHandler)
              rs ->
                  countsByFailure.merge(rs.getString("category"), rs.getInt("cnt"), Integer::sum));
    }

    log.debug(
        "loadOperatorRunAggregate total={} states={} failureCategories={} oldestEntryPresent={}",
        total[0],
        countsByState.size(),
        countsByFailure.size(),
        oldest[0] != null);
    return new OperatorRunAggregate(total[0], countsByState, countsByFailure, oldest[0]);
  }

  @Override
  @Transactional(readOnly = true)
  public List<OperatorRunRowSnapshot> listOperatorRuns(OperatorRunQuery query, int limit) {
    Objects.requireNonNull(query, "query");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    if (query.matchesNothing()) {
      return List.of();
    }
    MapSqlParameterSource params = predicateParams(query).addValue("limit", limit);
    List<OperatorRunRowSnapshot> rows =
        jdbcTemplate.query(
            LIST_ROWS_SQL,
            params,
            (rs, rowNum) ->
                new OperatorRunRowSnapshot(
                    rs.getString("run_id"),
                    rs.getString("current_state"),
                    rs.getString("failure_category"),
                    rs.getObject("last_transition_at", OffsetDateTime.class),
                    rs.getString("actor_identity"),
                    rs.getString("linked_ticket_ref"),
                    rs.getString("linked_pr_ref"),
                    rs.getBoolean("escalation_marker"),
                    rs.getObject("oldest_event_at", OffsetDateTime.class),
                    rs.getString("signifier")));
    log.debug("listOperatorRuns returned={} limit={}", rows.size(), limit);
    return new ArrayList<>(rows);
  }

  private static MapSqlParameterSource predicateParams(OperatorRunQuery query) {
    Duration stallWindow =
        query.stallWindow() == null ? Duration.ofSeconds(1) : query.stallWindow();
    Double sinceSecs =
        query.sinceWindow() == null ? null : query.sinceWindow().toMillis() / 1000.0d;
    return new MapSqlParameterSource()
        .addValue("selFailed", query.selectFailed())
        .addValue("selStalled", query.selectStalled())
        .addValue("selOrphaned", query.selectOrphaned())
        .addValue("selTakenover", query.selectTakenover())
        .addValue("selOverridden", query.selectOverridden())
        .addValue("stallSecs", stallWindow.toMillis() / 1000.0d)
        .addValue("sinceSecs", sinceSecs)
        .addValue("includeArchived", query.includeArchived());
  }
}
