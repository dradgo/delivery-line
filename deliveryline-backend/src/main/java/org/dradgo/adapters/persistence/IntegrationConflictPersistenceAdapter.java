package org.dradgo.adapters.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.integration.conflict.ConflictFilter;
import org.dradgo.application.integration.conflict.ConflictResolutionView;
import org.dradgo.application.integration.conflict.ConflictSummary;
import org.dradgo.application.integration.conflict.spi.ConflictListQuery;
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

  // Story 4.6 code review (P3) — per-run reconcile advisory lock. The two-int
  // pg_advisory_xact_lock(classifier, hashtext(runId)) form serializes reconciles on the SAME run
  // (closing the D1 last-conflict count->transition race) while different runs never contend.
  // 0x5243 == "RC" (reconcile), distinct from the ICON sweep key so the locks never collide.
  private static final int RECONCILE_RUN_LOCK_CLASSIFIER = 0x5243;

  private static final String LOCK_RUN_FOR_RECONCILE_SQL =
      "select pg_advisory_xact_lock(:classifier, hashtext(:runId))";

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

  // Story 4.18 (AC2) — the keyset-paginated read for GET /api/v1/integration-conflicts.
  // Three-valued
  // `resolved` filter; the (detected_at DESC, id DESC) cursor uses a public_id → id subquery for
  // the
  // tiebreak so the opaque cursor need not carry the numeric id. Mirrors LIST_UNRESOLVED_SQL's
  // filter axes but may include resolved rows.
  private static final String LIST_CONFLICTS_SQL =
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
       where c.archived_at is null
         and (cast(:resolvedFilter as boolean) is null
              or (cast(:resolvedFilter as boolean) = false and c.resolved_at is null)
              or (cast(:resolvedFilter as boolean) = true and c.resolved_at is not null))
         and (cast(:category as text) is null or c.conflict_category = :category)
         and (cast(:integrationType as text) is null or l.integration_type = :integrationType)
         and (cast(:ticketRef as text) is null or l.external_ref = :ticketRef)
         and (cast(:runId as text) is null or c.workflow_run_id = :runId)
         and (cast(:sinceSecs as double precision) is null
              or c.detected_at >= now() - make_interval(secs => :sinceSecs))
         and (cast(:cursorDetectedAt as timestamptz) is null
              or c.detected_at < cast(:cursorDetectedAt as timestamptz)
              or (c.detected_at = cast(:cursorDetectedAt as timestamptz)
                  and c.id < (select ic.id from integration_conflicts ic
                               where ic.public_id = :cursorConflictId)))
       order by c.detected_at desc, c.id desc
       limit :limit
      """;

  private static final String COUNT_RESOLVED_SQL =
      """
      select count(*) as cnt
        from integration_conflicts
       where resolved_at is not null
         and archived_at is null
      """;

  // Story 4.18 (AC1) — grouped unresolved-conflict count for a page of run ids (one query, no N+1).
  private static final String UNRESOLVED_COUNT_BY_RUN_SQL =
      """
      select c.workflow_run_id as run_id,
             count(*)          as cnt
        from integration_conflicts c
       where c.resolved_at is null
         and c.archived_at is null
         and c.workflow_run_id in (:runIds)
       group by c.workflow_run_id
      """;

  private static final String FIND_BY_PUBLIC_ID_SQL =
      """
      select c.public_id                  as conflict_id,
             c.integration_link_id        as integration_link_id,
             c.workflow_run_id            as workflow_run_id,
             c.conflict_category          as conflict_category,
             l.integration_type           as integration_type,
             l.external_ref               as external_ref,
             c.resolved_at                as resolved_at,
             c.external_state_snapshot::text as external_state_snapshot,
             c.internal_state_snapshot::text as internal_state_snapshot
        from integration_conflicts c
        left join integration_links l on l.public_id = c.integration_link_id
       where c.public_id = :conflictId
         and c.archived_at is null
      """;

  private static final String MARK_RESOLVED_SQL =
      """
      update integration_conflicts
         set resolved_at = :resolvedAt,
             resolved_by_action_id = :recoveryActionId
       where public_id = :conflictId
         and resolved_at is null
         and archived_at is null
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
  public Optional<ConflictResolutionView> findByPublicId(String conflictPublicId) {
    Objects.requireNonNull(conflictPublicId, "conflictPublicId");
    List<ConflictResolutionView> rows =
        jdbcTemplate.query(
            FIND_BY_PUBLIC_ID_SQL,
            new MapSqlParameterSource("conflictId", conflictPublicId),
            (rs, rowNum) ->
                new ConflictResolutionView(
                    rs.getString("conflict_id"),
                    rs.getString("workflow_run_id"),
                    rs.getString("integration_link_id"),
                    rs.getString("integration_type"),
                    rs.getString("conflict_category"),
                    rs.getString("external_ref"),
                    rs.getObject("resolved_at", OffsetDateTime.class),
                    rs.getString("external_state_snapshot"),
                    rs.getString("internal_state_snapshot")));
    return rows.stream().findFirst();
  }

  @Override
  @Transactional
  public boolean markResolved(
      String conflictPublicId, String recoveryActionPublicId, Instant resolvedAt) {
    Objects.requireNonNull(conflictPublicId, "conflictPublicId");
    Objects.requireNonNull(recoveryActionPublicId, "recoveryActionPublicId");
    Objects.requireNonNull(resolvedAt, "resolvedAt");
    int updated =
        jdbcTemplate.update(
            MARK_RESOLVED_SQL,
            new MapSqlParameterSource()
                .addValue("conflictId", conflictPublicId)
                .addValue("recoveryActionId", recoveryActionPublicId)
                .addValue("resolvedAt", Timestamp.from(resolvedAt)));
    return updated > 0;
  }

  @Override
  public void lockRunForReconcile(String workflowRunId) {
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    // Joins the caller's (reconcile prep) transaction so the lock is held until that tx
    // commits/rolls back. NOT @Transactional here — a REQUIRES_NEW would release it immediately;
    // it relies on the ambient MANDATORY tx opened by
    // IntegrationConflictService.lockRunForReconcile.
    jdbcTemplate.queryForObject(
        LOCK_RUN_FOR_RECONCILE_SQL,
        new MapSqlParameterSource()
            .addValue("classifier", RECONCILE_RUN_LOCK_CLASSIFIER)
            .addValue("runId", workflowRunId),
        Object.class);
    log.debug("reconcile per-run advisory lock acquired workflowRunId={}", workflowRunId);
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

  @Override
  @Transactional(readOnly = true)
  public List<ConflictSummary> listConflicts(ConflictListQuery query) {
    Objects.requireNonNull(query, "query");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("resolvedFilter", query.resolved())
            .addValue("category", query.conflictCategory())
            .addValue("integrationType", query.integrationType())
            .addValue("ticketRef", query.ticketReference())
            .addValue("runId", query.workflowRunId())
            .addValue("sinceSecs", query.sinceSeconds())
            // Bind the OffsetDateTime directly (pgjdbc maps it to timestamptz by instant), matching
            // AuditEventPersistenceAdapter's cursor bind. A java.sql.Timestamp here would be sent
            // in
            // the JVM default zone and re-interpreted in the PG session zone, shifting the keyset
            // anchor when the two zones differ (masked by the UTC-only Testcontainers env).
            .addValue("cursorDetectedAt", query.cursorDetectedAt())
            .addValue("cursorConflictId", query.cursorConflictId())
            .addValue("limit", query.limit());
    List<ConflictSummary> rows =
        jdbcTemplate.query(
            LIST_CONFLICTS_SQL,
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
    log.debug("listConflicts returned={} limit={}", rows.size(), query.limit());
    return new ArrayList<>(rows);
  }

  @Override
  @Transactional(readOnly = true)
  public long countResolved() {
    Long count =
        jdbcTemplate.queryForObject(COUNT_RESOLVED_SQL, new MapSqlParameterSource(), Long.class);
    return count == null ? 0L : count;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, Integer> unresolvedCountByRun(Collection<String> workflowRunIds) {
    if (workflowRunIds == null || workflowRunIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    jdbcTemplate.query(
        UNRESOLVED_COUNT_BY_RUN_SQL,
        new MapSqlParameterSource("runIds", workflowRunIds),
        rs -> {
          counts.put(rs.getString("run_id"), rs.getInt("cnt"));
        });
    return counts;
  }

  private static java.time.Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
