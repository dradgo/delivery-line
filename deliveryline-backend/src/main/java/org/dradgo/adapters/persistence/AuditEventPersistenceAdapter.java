package org.dradgo.adapters.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.audit.spi.AuditEventPageSnapshot;
import org.dradgo.application.audit.spi.AuditEventQuery;
import org.dradgo.application.audit.spi.AuditEventReadPort;
import org.dradgo.application.audit.spi.AuditEventRowSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 4.3 (AC1/AC3/AC8) — the {@code adapters.persistence} implementation of {@link
 * AuditEventReadPort}. A dedicated adapter (not folded into an existing read adapter) so the audit
 * cross-run join-query stays isolated and no existing adapter's constructor fans out.
 *
 * <p><b>Keyset pagination (story 4.3 Reconciliation 8).</b> Both queries order {@code created_at
 * DESC, id DESC} — {@code id} (bigserial) is the monotonic tiebreaker because {@code created_at}
 * collides within a JVM tick and its index is non-unique. The service passes {@code pageSize + 1}
 * as {@code limit} so a full extra row signals a next page; the keyset predicate for page N+1 is
 * {@code (created_at, id) < (cursorCreatedAt, cursorId)}. A separate {@code COUNT(*)} yields the
 * full-set {@code totalCount} (cursor/limit independent).
 *
 * <p><b>by-run</b> scopes on {@code wr.public_id} and rides the V1 {@code
 * idx_workflow_events_workflow_run_id_created_at}. <b>by-ticket</b> scopes on {@code
 * we.workflow_run_id IN (DISTINCT integration_links.workflow_run_id WHERE external_ref=? AND
 * integration_type='linear')} — WITHOUT the active-only filter so superseded links to earlier
 * retried runs are included (story 4.3 Reconciliation 6/7) — and rides the new (V35) {@code
 * idx_integration_links_external_ref_type} + {@code idx_workflow_events_event_type_created_at}.
 *
 * <p>{@code correlationId}/{@code linkedArtifactId} are NOT columns; they live in {@code details}
 * (JSONB). The row is projected with the RAW {@code details} text and reparsed here into a map —
 * the service sanitizes it (strip server-only keys + redact) before extracting those two fields
 * (story 4.3 Reconciliation 1/2/10). Verify on real Postgres — the keyset tuple comparison and the
 * cross-run {@code IN (subquery)} only behave correctly on PG.
 */
@Component
public class AuditEventPersistenceAdapter implements AuditEventReadPort {

  private static final Logger log = LoggerFactory.getLogger(AuditEventPersistenceAdapter.class);

  // A placeholder that never matches a real event_type keeps the `in (:eventTypes)` bind valid when
  // the filter is inactive; NamedParameterJdbcTemplate expands the IN list at parse time, so it
  // must
  // be non-empty even when :hasEventTypes=false short-circuits the OR.
  private static final List<String> NO_EVENT_TYPE_FILTER = List.of("__no_event_type_filter__");

  private static final String ROW_COLUMNS =
      """
      select we.id                as id,
             we.public_id         as event_id,
             we.event_type        as event_type,
             wr.public_id         as run_id,
             we.actor_identity    as actor_identity,
             we.actor_type        as actor_type,
             we.prior_state       as prior_state,
             we.resulting_state   as resulting_state,
             we.failure_category  as failure_category,
             we.reason            as reason,
             we.created_at        as created_at,
             we.details::text     as details_json
        from workflow_events we
        join workflow_runs wr on wr.id = we.workflow_run_id
      """;

  private static final String COUNT_HEAD =
      """
      select count(*)
        from workflow_events we
        join workflow_runs wr on wr.id = we.workflow_run_id
      """;

  private static final String BY_RUN_SCOPE =
      " where wr.public_id = :scopeRef and we.archived_at is null";

  // No active-only (archived_at is null / sync_status <> 'superseded') filter on integration_links:
  // we WANT superseded links pointing at earlier retried runs of the ticket (story 4.3
  // Reconciliation 7).
  private static final String BY_TICKET_SCOPE =
      """
       where we.archived_at is null
         and we.workflow_run_id in (
           select distinct l.workflow_run_id
             from integration_links l
            where l.external_ref = :scopeRef
              and l.integration_type = 'linear'
         )
      """;

  private static final String FILTERS =
      """
         and (:hasEventTypes = false or we.event_type in (:eventTypes))
         and (:actor::text is null or we.actor_identity = :actor)
         and (:since::timestamptz is null or we.created_at >= :since)
         and (:until::timestamptz is null or we.created_at <= :until)
      """;

  private static final String CURSOR =
      """
         and (:hasCursor = false
              or we.created_at < :cursorTs::timestamptz
              or (we.created_at = :cursorTs::timestamptz and we.id < :cursorId))
      """;

  private static final String ORDER_LIMIT = " order by we.created_at desc, we.id desc limit :limit";

  private static final String RUN_EXISTS_SQL =
      "select exists(select 1 from workflow_runs where public_id = :runId)";

  // A private mapper: this app does not expose a Spring ObjectMapper bean (WorkflowCommandOutputs
  // falls back to a fresh one for the same reason), and parsing a details JSONB object into a
  // String→Object map needs no custom modules — so we own a plain instance rather than inject one.
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public AuditEventPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  @Transactional(readOnly = true)
  public AuditEventPageSnapshot listByRun(AuditEventQuery query) {
    return page(query, BY_RUN_SCOPE, "listByRun");
  }

  @Override
  @Transactional(readOnly = true)
  public AuditEventPageSnapshot listByTicket(AuditEventQuery query) {
    return page(query, BY_TICKET_SCOPE, "listByTicket");
  }

  @Override
  @Transactional(readOnly = true)
  public boolean runExists(String workflowRunPublicId) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            RUN_EXISTS_SQL, new MapSqlParameterSource("runId", workflowRunPublicId), Boolean.class);
    return Boolean.TRUE.equals(exists);
  }

  private AuditEventPageSnapshot page(AuditEventQuery query, String scope, String method) {
    Objects.requireNonNull(query, "query");
    if (query.limit() <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    MapSqlParameterSource params = predicateParams(query);

    String rowsSql = ROW_COLUMNS + scope + FILTERS + CURSOR + ORDER_LIMIT;
    List<AuditEventRowSnapshot> rows = jdbcTemplate.query(rowsSql, params, rowMapper);

    String countSql = COUNT_HEAD + scope + FILTERS;
    Long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
    long totalCount = total == null ? 0L : total;

    log.debug(
        "{} returned={} totalCount={} limit={}", method, rows.size(), totalCount, query.limit());
    return new AuditEventPageSnapshot(rows, totalCount);
  }

  private static MapSqlParameterSource predicateParams(AuditEventQuery query) {
    List<String> eventTypes =
        query.hasEventTypeFilter() ? List.copyOf(query.eventTypes()) : NO_EVENT_TYPE_FILTER;
    return new MapSqlParameterSource()
        .addValue("scopeRef", query.scopeRef())
        .addValue("hasEventTypes", query.hasEventTypeFilter())
        .addValue("eventTypes", eventTypes)
        .addValue("actor", query.actorIdentity())
        .addValue("since", query.sinceInclusive())
        .addValue("until", query.untilInclusive())
        .addValue("hasCursor", query.hasCursor())
        .addValue("cursorTs", query.cursorCreatedAt())
        .addValue("cursorId", query.cursorId())
        .addValue("limit", query.limit());
  }

  private final RowMapper<AuditEventRowSnapshot> rowMapper =
      (rs, rowNum) ->
          new AuditEventRowSnapshot(
              rs.getLong("id"),
              rs.getString("event_id"),
              rs.getString("event_type"),
              rs.getString("run_id"),
              rs.getString("actor_identity"),
              rs.getString("actor_type"),
              rs.getString("prior_state"),
              rs.getString("resulting_state"),
              rs.getString("failure_category"),
              rs.getString("reason"),
              rs.getObject("created_at", OffsetDateTime.class),
              parseDetails(rs.getString("details_json")));

  private Map<String, Object> parseDetails(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return OBJECT_MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      // A malformed details blob must not fail the whole read — the derived correlationId /
      // linkedArtifactId simply become null for this row. Never log the raw JSON (may carry
      // secrets); only the parse error type.
      log.warn(
          "failed to parse workflow_events.details as JSON; treating as empty details: {}",
          error.getClass().getSimpleName());
      return Map.of();
    }
  }
}
