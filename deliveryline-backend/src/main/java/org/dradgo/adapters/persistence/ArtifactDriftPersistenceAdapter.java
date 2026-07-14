package org.dradgo.adapters.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

  // Story 4.16 — single-row lookup by adr_ public id. Returns EVEN resolved rows (no resolved_at
  // filter) so the repair coordinator can distinguish DRIFT_NOT_FOUND from DRIFT_ALREADY_RESOLVED.
  // Archived rows are excluded (treated as not found).
  private static final String FIND_BY_PUBLIC_ID_SQL =
      """
      select d.public_id            as drift_id,
             d.workflow_run_id      as workflow_run_id,
             d.artifact_id          as artifact_id,
             d.artifact_operation_id as artifact_operation_id,
             d.drift_category       as drift_category,
             d.detected_at          as detected_at,
             d.last_known_state::text as last_known_state,
             d.resolved_at          as resolved_at
        from artifact_drift_detected d
       where d.public_id = :driftId
         and d.archived_at is null
      """;

  // Story 4.16 — resolve one UNRESOLVED, non-archived drift. resolved_by_action_id FKs
  // recovery_actions(public_id) RESTRICT, so the recovery_actions row must already be inserted.
  private static final String RESOLVE_DRIFT_SQL =
      """
      update artifact_drift_detected
         set resolved_at = :resolvedAt,
             resolved_by_action_id = :actionId
       where public_id = :driftId
         and resolved_at is null
         and archived_at is null
      """;

  // Story 4.16 — refresh an UNRESOLVED drift's snapshot without resolving it (reVerifyChecksum
  // still-mismatched path).
  private static final String UPDATE_LAST_KNOWN_STATE_SQL =
      """
      update artifact_drift_detected
         set last_known_state = cast(:lastKnownState as jsonb)
       where public_id = :driftId
         and resolved_at is null
         and archived_at is null
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
                    rs.getString("last_known_state"),
                    // Definitionally null — this query hard-filters resolved_at IS NULL.
                    null));
    log.debug("listUnresolved (drift) returned={}", rows.size());
    return new ArrayList<>(rows);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<DriftRow> findByPublicId(String driftId) {
    Objects.requireNonNull(driftId, "driftId");
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("driftId", driftId);
    List<DriftRow> rows =
        jdbcTemplate.query(
            FIND_BY_PUBLIC_ID_SQL,
            params,
            (rs, rowNum) ->
                new DriftRow(
                    rs.getString("drift_id"),
                    rs.getString("workflow_run_id"),
                    rs.getString("artifact_id"),
                    rs.getString("artifact_operation_id"),
                    PersistedRegistryValues.artifactDriftCategory(rs.getString("drift_category")),
                    toInstant(rs.getObject("detected_at", OffsetDateTime.class)),
                    rs.getString("last_known_state"),
                    toInstant(rs.getObject("resolved_at", OffsetDateTime.class))));
    return rows.stream().findFirst();
  }

  @Override
  @Transactional
  public boolean resolveDrift(String driftId, String resolvedByActionPublicId, Instant resolvedAt) {
    Objects.requireNonNull(driftId, "driftId");
    Objects.requireNonNull(resolvedByActionPublicId, "resolvedByActionPublicId");
    Objects.requireNonNull(resolvedAt, "resolvedAt");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("driftId", driftId)
            .addValue("actionId", resolvedByActionPublicId)
            // Bind an OffsetDateTime at UTC directly (NOT Timestamp.from) so the ::timestamptz
            // column is not tz-shifted by a JVM-vs-PG-session offset mismatch
            // ([[jdbc-timestamp-to-timestamptz-tz-shift]]).
            .addValue("resolvedAt", OffsetDateTime.ofInstant(resolvedAt, ZoneOffset.UTC));
    int updated = jdbcTemplate.update(RESOLVE_DRIFT_SQL, params);
    if (updated > 0) {
      log.info(
          "resolving artifact_drift_detected driftId={} resolvedByActionId={}",
          driftId,
          resolvedByActionPublicId);
    } else {
      log.warn("resolveDrift no-op driftId={} (already resolved / archived / absent)", driftId);
    }
    return updated > 0;
  }

  @Override
  @Transactional
  public boolean updateLastKnownState(String driftId, String lastKnownStateJson) {
    Objects.requireNonNull(driftId, "driftId");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("driftId", driftId)
            .addValue("lastKnownState", lastKnownStateJson == null ? "{}" : lastKnownStateJson);
    int updated = jdbcTemplate.update(UPDATE_LAST_KNOWN_STATE_SQL, params);
    log.debug("updateLastKnownState driftId={} updated={}", driftId, updated > 0);
    return updated > 0;
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
