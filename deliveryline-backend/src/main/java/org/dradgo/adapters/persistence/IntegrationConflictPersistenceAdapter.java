package org.dradgo.adapters.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.dradgo.application.integration.conflict.ConflictFilter;
import org.dradgo.application.integration.conflict.ConflictSummary;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictScanPort;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictWritePort;
import org.dradgo.application.integration.conflict.spi.IntegrationLinkScanRow;
import org.dradgo.application.integration.conflict.spi.NewIntegrationConflict;
import org.dradgo.application.integration.conflict.spi.UnresolvedConflictCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 4.17 — {@code adapters.persistence} implementation of the three conflict ports (scan /
 * write / read) over {@code NamedParameterJdbcTemplate}. A dedicated adapter (not folded into
 * {@code IntegrationLinkPersistenceAdapter}) so no existing adapter's constructor fans out,
 * mirroring {@code OperatorRunPersistenceAdapter}'s pure-jdbc, entity-free shape (story 4.1).
 *
 * <p>The write uses {@code INSERT … ON CONFLICT DO NOTHING} against the {@code
 * uq_integration_conflicts_unresolved} partial-unique index so a standing conflict never poisons
 * the caller's transaction. Verify on real Postgres (Testcontainers), not mocks — the partial-index
 * inference, the {@code ::text}/{@code ::jsonb} casts, and {@code pg_advisory_xact_lock} only
 * exercise on PG.
 */
@Component
public class IntegrationConflictPersistenceAdapter
    implements IntegrationConflictScanPort,
        IntegrationConflictWritePort,
        IntegrationConflictReadPort {

  private static final Logger log =
      LoggerFactory.getLogger(IntegrationConflictPersistenceAdapter.class);

  // Sweep-wide advisory lock key, transaction-scoped (auto-released on commit). 0x49434F4E ==
  // "ICON" — distinct from RDEP (0x52444550) so the two sweeps never contend on the same key.
  private static final long CONFLICT_SWEEP_ADVISORY_LOCK_KEY = 0x49434F4EL;

  private static final String ACQUIRE_LOCK_SQL = "select pg_advisory_xact_lock(:lockKey)";

  // Keyset-paginated by l.id (the raw monotonic PK) so the sweep advances past a single batch
  // across ticks instead of re-selecting the same oldest window every tick (bare-LIMIT starvation).
  private static final String SCAN_ACTIVE_LINKS_SQL =
      """
      select l.public_id             as integration_link_id,
             r.public_id             as workflow_run_id,
             l.integration_type      as integration_type,
             l.external_ref          as external_ref,
             l.external_metadata::text as external_metadata,
             l.project_id            as project_id,
             r.current_state         as current_state,
             l.id                    as link_seq
        from integration_links l
        join workflow_runs r on r.id = l.workflow_run_id
       where l.integration_type = :integrationType
         and l.archived_at is null
         and l.sync_status <> 'superseded'
         and l.id > :afterSeq
       order by l.id asc
       limit :batchLimit
      """;

  private static final String SNAPSHOT_BASELINE_SQL =
      """
      update integration_links
         set external_metadata = cast(:metadata as jsonb)
       where public_id = :publicId
         and archived_at is null
         and sync_status <> 'superseded'
      """;

  // Partial-index inference (the WHERE mirrors uq_integration_conflicts_unresolved) so DO NOTHING
  // fires only for an existing UNRESOLVED, non-archived conflict of the same (link, category).
  private static final String INSERT_IF_ABSENT_SQL =
      """
      insert into integration_conflicts
        (public_id, integration_link_id, workflow_run_id, conflict_category,
         internal_state_snapshot, external_state_snapshot, detected_at)
      values (:publicId, :linkId, :runId, :category,
         cast(:internalSnapshot as jsonb), cast(:externalSnapshot as jsonb), :detectedAt)
      on conflict (integration_link_id, conflict_category)
         where resolved_at is null and archived_at is null
      do nothing
      """;

  private static final String LIST_UNRESOLVED_SQL =
      """
      select c.public_id          as conflict_id,
             c.integration_link_id as integration_link_id,
             c.workflow_run_id     as workflow_run_id,
             c.conflict_category   as conflict_category,
             l.integration_type    as integration_type,
             l.external_ref        as external_ref,
             c.detected_at         as detected_at
        from integration_conflicts c
        left join integration_links l on l.public_id = c.integration_link_id
       where c.resolved_at is null
         and c.archived_at is null
         and (cast(:category as text) is null or c.conflict_category = :category)
         and (cast(:integrationType as text) is null or l.integration_type = :integrationType)
         and (cast(:ticketRef as text) is null or l.external_ref = :ticketRef)
         and (cast(:runId as text) is null or c.workflow_run_id = :runId)
         and (cast(:sinceSecs as double precision) is null
              or c.detected_at >= now() - make_interval(secs => :sinceSecs))
       order by c.detected_at desc, c.id desc
      """;

  private static final String COUNT_UNRESOLVED_SQL =
      """
      select c.conflict_category as conflict_category,
             l.integration_type  as integration_type,
             count(*)            as cnt
        from integration_conflicts c
        left join integration_links l on l.public_id = c.integration_link_id
       where c.resolved_at is null and c.archived_at is null
       group by c.conflict_category, l.integration_type
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public IntegrationConflictPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public void acquireSweepLock() {
    // Joins the caller's (sweep) transaction so the lock is held for the whole sweep and released
    // on
    // that transaction's commit. NOT @Transactional here — a REQUIRES_NEW would release it at once.
    jdbcTemplate.queryForObject(
        ACQUIRE_LOCK_SQL,
        new MapSqlParameterSource("lockKey", CONFLICT_SWEEP_ADVISORY_LOCK_KEY),
        Object.class);
    log.debug("integration-conflict SWEEP advisory lock acquired");
  }

  @Override
  public List<IntegrationLinkScanRow> scanActiveLinksByType(
      String integrationType, int batchLimit, long afterSeq) {
    if (integrationType == null || integrationType.isBlank()) {
      throw new IllegalArgumentException("integrationType must be non-blank");
    }
    if (batchLimit <= 0) {
      throw new IllegalArgumentException("batchLimit must be positive");
    }
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("integrationType", integrationType)
            .addValue("batchLimit", batchLimit)
            .addValue("afterSeq", afterSeq);
    List<IntegrationLinkScanRow> rows =
        jdbcTemplate.query(
            SCAN_ACTIVE_LINKS_SQL,
            params,
            (rs, rowNum) -> {
              String metadata = rs.getString("external_metadata");
              return new IntegrationLinkScanRow(
                  rs.getString("integration_link_id"),
                  rs.getString("workflow_run_id"),
                  rs.getString("integration_type"),
                  rs.getString("external_ref"),
                  metadata == null ? new byte[0] : metadata.getBytes(StandardCharsets.UTF_8),
                  rs.getString("project_id"),
                  rs.getString("current_state"),
                  rs.getLong("link_seq"));
            });
    log.debug(
        "integration-conflict SCAN integrationType={} returned={} batchLimit={} afterSeq={}",
        integrationType,
        rows.size(),
        batchLimit,
        afterSeq);
    return new ArrayList<>(rows);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean snapshotExternalMetadataBaseline(
      String integrationLinkPublicId, byte[] externalMetadata) {
    Objects.requireNonNull(integrationLinkPublicId, "integrationLinkPublicId");
    String metadataJson =
        externalMetadata == null || externalMetadata.length == 0
            ? "{}"
            : new String(externalMetadata, StandardCharsets.UTF_8);
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("publicId", integrationLinkPublicId)
            .addValue("metadata", metadataJson);
    int updated = jdbcTemplate.update(SNAPSHOT_BASELINE_SQL, params);
    if (updated > 0) {
      log.info(
          "integration-conflict baseline snapshotted integrationLinkId={}",
          integrationLinkPublicId);
    }
    return updated > 0;
  }

  @Override
  @Transactional
  public boolean insertIfAbsent(NewIntegrationConflict request) {
    Objects.requireNonNull(request, "request");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("publicId", request.publicId())
            .addValue("linkId", request.integrationLinkPublicId())
            .addValue("runId", request.workflowRunPublicId())
            .addValue("category", request.conflictCategory())
            .addValue(
                "internalSnapshot",
                request.internalStateSnapshot() == null ? "{}" : request.internalStateSnapshot())
            .addValue(
                "externalSnapshot",
                request.externalStateSnapshot() == null ? "{}" : request.externalStateSnapshot())
            .addValue("detectedAt", Timestamp.from(request.detectedAt()));
    int inserted = jdbcTemplate.update(INSERT_IF_ABSENT_SQL, params);
    if (inserted > 0) {
      log.info(
          "persisting integration_conflict conflictId={} conflictCategory={} integrationLinkId={}",
          request.publicId(),
          request.conflictCategory(),
          request.integrationLinkPublicId());
    } else {
      log.debug(
          "integration-conflict INSERT skipped (dedup) conflictCategory={} integrationLinkId={}",
          request.conflictCategory(),
          request.integrationLinkPublicId());
    }
    return inserted > 0;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConflictSummary> listUnresolved(ConflictFilter filter) {
    Objects.requireNonNull(filter, "filter");
    Double sinceSecs = filter.timeSince() == null ? null : filter.timeSince().toMillis() / 1000.0d;
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("category", filter.conflictCategory())
            .addValue("integrationType", filter.integrationType())
            .addValue("ticketRef", filter.ticketReference())
            .addValue("runId", filter.workflowRunId())
            .addValue("sinceSecs", sinceSecs);
    List<ConflictSummary> rows =
        jdbcTemplate.query(
            LIST_UNRESOLVED_SQL,
            params,
            (rs, rowNum) ->
                new ConflictSummary(
                    rs.getString("conflict_id"),
                    rs.getString("integration_link_id"),
                    rs.getString("workflow_run_id"),
                    rs.getString("conflict_category"),
                    rs.getString("integration_type"),
                    rs.getString("external_ref"),
                    toInstant(rs.getObject("detected_at", OffsetDateTime.class))));
    log.debug("listUnresolved returned={} ", rows.size());
    return new ArrayList<>(rows);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UnresolvedConflictCount> countUnresolvedByCategoryAndIntegration() {
    List<UnresolvedConflictCount> counts =
        jdbcTemplate.query(
            COUNT_UNRESOLVED_SQL,
            new MapSqlParameterSource(),
            (rs, rowNum) ->
                new UnresolvedConflictCount(
                    rs.getString("conflict_category"),
                    rs.getString("integration_type"),
                    rs.getLong("cnt")));
    return new ArrayList<>(counts);
  }

  private static java.time.Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
