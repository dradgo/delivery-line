package org.dradgo.adapters.persistence;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftReadPort;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftWritePort;
import org.dradgo.application.artifact.reconciliation.spi.DriftQuery;
import org.dradgo.application.artifact.reconciliation.spi.DriftRecordRequest;
import org.dradgo.application.artifact.reconciliation.spi.DriftRow;
import org.dradgo.application.artifact.reconciliation.spi.UnresolvedDriftCount;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 4.15 — {@code adapters.persistence} implementation of the drift write + read ports over
 * {@code NamedParameterJdbcTemplate}. A dedicated adapter (not folded into an existing artifact
 * adapter) so no existing adapter's constructor fans out, mirroring {@code
 * IntegrationConflictPersistenceAdapter}'s pure-jdbc, entity-free shape (story 4.17).
 *
 * <p>The write uses {@code INSERT … ON CONFLICT DO NOTHING} against the {@code
 * uq_artifact_drift_detected_active} partial-unique (NULLS NOT DISTINCT) index so a standing drift
 * never poisons the caller's transaction. Verify on real Postgres (Testcontainers), not mocks — the
 * partial-index inference, the {@code ::jsonb} cast, and the NULLS-NOT-DISTINCT dedup only exercise
 * on PG.
 */
@Component
public class ArtifactDriftPersistenceAdapter
    implements ArtifactDriftWritePort, ArtifactDriftReadPort {

  private static final Logger log = LoggerFactory.getLogger(ArtifactDriftPersistenceAdapter.class);

  // Partial-index inference (the WHERE mirrors uq_artifact_drift_detected_active) so DO NOTHING
  // fires only for an existing UNRESOLVED, non-archived drift of the same (category, artifact,
  // operation). NULLS NOT DISTINCT on the index collapses the NULL target column.
  private static final String INSERT_IF_ABSENT_SQL =
      """
      insert into artifact_drift_detected
        (public_id, workflow_run_id, artifact_id, artifact_operation_id, drift_category,
         last_known_state, detected_at)
      values (:publicId, :runId, :artifactId, :operationId, :category,
         cast(:lastKnownState as jsonb), :detectedAt)
      on conflict (drift_category, artifact_id, artifact_operation_id)
         where resolved_at is null and archived_at is null
      do nothing
      """;

  private static final String LIST_UNRESOLVED_SQL =
      """
      select d.public_id            as drift_id,
             d.workflow_run_id      as workflow_run_id,
             d.artifact_id          as artifact_id,
             d.artifact_operation_id as artifact_operation_id,
             d.drift_category       as drift_category,
             d.detected_at          as detected_at,
             d.last_known_state::text as last_known_state
        from artifact_drift_detected d
       where d.resolved_at is null
         and d.archived_at is null
         and (cast(:category as text) is null or d.drift_category = :category)
         and (cast(:runId as text) is null or d.workflow_run_id = :runId)
         and (cast(:sinceSecs as double precision) is null
              or d.detected_at >= now() - make_interval(secs => :sinceSecs))
         and (cast(:ticketRef as text) is null or exists (
              select 1
                from integration_links il
                join workflow_runs r on r.id = il.workflow_run_id
               where r.public_id = d.workflow_run_id
                 and il.integration_type = 'linear'
                 and il.external_ref = :ticketRef
                 and il.archived_at is null))
       order by d.detected_at desc, d.id desc
       limit :limit
      """;

  private static final String COUNT_UNRESOLVED_SQL =
      """
      select d.drift_category as drift_category,
             count(*)         as cnt
        from artifact_drift_detected d
       where d.resolved_at is null and d.archived_at is null
       group by d.drift_category
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public ArtifactDriftPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  @Transactional
  public boolean recordIfAbsent(DriftRecordRequest request) {
    Objects.requireNonNull(request, "request");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("publicId", request.publicId())
            .addValue("runId", request.workflowRunId())
            .addValue("artifactId", request.artifactId())
            .addValue("operationId", request.artifactOperationId())
            .addValue("category", request.driftCategory())
            .addValue(
                "lastKnownState",
                request.lastKnownStateJson() == null ? "{}" : request.lastKnownStateJson())
            .addValue("detectedAt", Timestamp.from(request.detectedAt()));
    int inserted = jdbcTemplate.update(INSERT_IF_ABSENT_SQL, params);
    if (inserted > 0) {
      log.info(
          "persisting artifact_drift_detected driftId={} driftCategory={} artifactId={} operationId={}",
          request.publicId(),
          request.driftCategory(),
          request.artifactId(),
          request.artifactOperationId());
    } else {
      log.debug(
          "artifact-drift INSERT skipped (dedup) driftCategory={} artifactId={} operationId={}",
          request.driftCategory(),
          request.artifactId(),
          request.artifactOperationId());
    }
    return inserted > 0;
  }

  @Override
  @Transactional(readOnly = true)
  public List<DriftRow> listUnresolved(DriftQuery query) {
    Objects.requireNonNull(query, "query");
    Double sinceSecs = query.timeSince() == null ? null : query.timeSince().toMillis() / 1000.0d;
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("category", query.driftCategory())
            .addValue("runId", query.workflowRunId())
            .addValue("ticketRef", query.ticketReference())
            .addValue("sinceSecs", sinceSecs)
            .addValue("limit", query.limit());
    List<DriftRow> rows =
        jdbcTemplate.query(
            LIST_UNRESOLVED_SQL,
            params,
            (rs, rowNum) ->
                new DriftRow(
                    rs.getString("drift_id"),
                    rs.getString("workflow_run_id"),
                    rs.getString("artifact_id"),
                    rs.getString("artifact_operation_id"),
                    PersistedRegistryValues.artifactDriftCategory(rs.getString("drift_category")),
                    toInstant(rs.getObject("detected_at", OffsetDateTime.class)),
                    rs.getString("last_known_state")));
    log.debug("listUnresolved (drift) returned={}", rows.size());
    return new ArrayList<>(rows);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UnresolvedDriftCount> countUnresolvedByCategory() {
    List<UnresolvedDriftCount> counts =
        jdbcTemplate.query(
            COUNT_UNRESOLVED_SQL,
            new MapSqlParameterSource(),
            (rs, rowNum) ->
                new UnresolvedDriftCount(
                    PersistedRegistryValues.artifactDriftCategory(rs.getString("drift_category")),
                    rs.getLong("cnt")));
    return new ArrayList<>(counts);
  }

  private static java.time.Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
