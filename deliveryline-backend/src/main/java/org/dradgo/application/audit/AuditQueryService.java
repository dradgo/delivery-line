package org.dradgo.application.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.dradgo.application.audit.spi.AuditEventPageSnapshot;
import org.dradgo.application.audit.spi.AuditEventQuery;
import org.dradgo.application.audit.spi.AuditEventReadPort;
import org.dradgo.application.audit.spi.AuditEventRowSnapshot;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 4.3 (FR29) — the read-only audit-history query service. {@code queryByRun(runId, filter)}
 * returns one run's governed events; {@code queryByTicket(ticketRef, filter)} returns events across
 * ALL runs linked to a ticket (including retried runs — story 4.3 Reconciliation 7). Both apply the
 * {@code event-type}/{@code actor}/{@code since}/{@code until}/{@code limit}/{@code cursor} filter,
 * redact event content, and keyset-paginate over {@code (created_at DESC, id DESC)}.
 *
 * <p><b>Thin adapters, fat service (AC9).</b> ALL validation lives here so the CLI {@code
 * AuditCommands} and the REST {@code AuditController} stay parse-and-delegate. The service composes
 * the {@link AuditEventReadPort} (the persistence-facing read seam) and applies redaction + cursor
 * encoding; no JPA/native query lives here.
 *
 * <p><b>Redaction (AC7 / Reconciliation 10).</b> {@code reason} is redacted via {@link
 * RedactionPolicyService#redact(String, String)} with claim {@code shareable-redacted} then
 * control-char-guarded (NET-NEW — the existing read surfaces pass {@code reason} raw). {@code
 * details} is server-key-stripped + value-redacted before {@code correlationId}/{@code artifactId}
 * are extracted (they are NOT columns — they live in {@code details}, Reconciliation 1/2). No event
 * carries a {@code local-only} classification, so the {@code [CONTENT REDACTED]} opacity clause of
 * AC7 is a documented no-op with no event source today.
 *
 * <p><b>Result records ({@link AuditQueryResult}/{@link AuditEventRow}/{@link AuditQueryFilter})
 * are nested here in {@code application.audit}</b> so BOTH the CLI adapter and the REST controller
 * can import them; the SPI snapshots ({@code application.audit.spi}) are never imported by adapters
 * (Reconciliation 13).
 */
@Service
public class AuditQueryService {

  private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

  /**
   * Hard ceiling on the page size (mirrors {@code WorkflowInspectionService.MAX_LIST_PAGE_SIZE}).
   */
  static final int MAX_PAGE_SIZE = 200;

  private final AuditEventReadPort auditEventReadPort;
  private final RedactionPolicyService redactionPolicyService;

  public AuditQueryService(
      AuditEventReadPort auditEventReadPort, RedactionPolicyService redactionPolicyService) {
    this.auditEventReadPort = Objects.requireNonNull(auditEventReadPort, "auditEventReadPort");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
  }

  /**
   * Query the audit history of a single run. Validates the {@code run_} prefix (400 {@code
   * INVALID_ID_PREFIX}) and existence (404 {@code RUN_NOT_FOUND}) before reading (story 4.3
   * AC1/AC6).
   */
  @Transactional(readOnly = true)
  public AuditQueryResult queryByRun(String workflowRunId, AuditQueryFilter filter) {
    Objects.requireNonNull(filter, "filter");
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    if (!auditEventReadPort.runExists(workflowRunId)) {
      throw runNotFound(workflowRunId);
    }
    return execute(workflowRunId, filter, Scope.RUN);
  }

  /**
   * Query the audit history across all runs linked to a ticket. An unknown ticket (zero links) is a
   * valid EMPTY result, not a 404 (story 4.3 AC3). A blank ticket ref is rejected.
   */
  @Transactional(readOnly = true)
  public AuditQueryResult queryByTicket(String ticketRef, AuditQueryFilter filter) {
    Objects.requireNonNull(filter, "filter");
    if (ticketRef == null || ticketRef.isBlank()) {
      throw invalidTicketRef(ticketRef);
    }
    return execute(ticketRef.trim(), filter, Scope.TICKET);
  }

  private enum Scope {
    RUN,
    TICKET
  }

  private AuditQueryResult execute(String scopeRef, AuditQueryFilter filter, Scope scope) {
    long start = System.nanoTime();
    Set<String> eventTypes = resolveEventTypes(filter.eventTypeTokens());
    String actor = blankToNull(filter.actor());
    validateTimeRange(filter.since(), filter.until());
    int pageSize = clampLimit(filter.limit());
    CursorKeyset cursor = decodeCursor(filter.cursor());
    log.info(
        "audit query entry scope={} ref={} eventTypes={} actorPresent={} since={} until={} limit={}"
            + " cursorPresent={}",
        scope,
        MdcKeys.sanitizeForLog(scopeRef),
        eventTypes,
        actor != null,
        filter.since(),
        filter.until(),
        pageSize,
        cursor.active());

    // Fetch pageSize+1 so a full extra row signals a next page; drop the extra and encode the last
    // KEPT row's keyset as nextCursor. totalCount is the FULL filtered count (cursor/limit
    // independent), so it is stable across pages.
    AuditEventQuery query =
        new AuditEventQuery(
            scopeRef,
            eventTypes,
            actor,
            filter.since(),
            filter.until(),
            cursor.createdAt(),
            cursor.id(),
            pageSize + 1);
    log.debug("audit query dispatching port read scope={} limitPlusOne={}", scope, pageSize + 1);
    AuditEventPageSnapshot page =
        scope == Scope.RUN
            ? auditEventReadPort.listByRun(query)
            : auditEventReadPort.listByTicket(query);

    List<AuditEventRowSnapshot> fetched = page.rows();
    boolean hasMore = fetched.size() > pageSize;
    List<AuditEventRowSnapshot> kept = hasMore ? fetched.subList(0, pageSize) : fetched;
    String nextCursor = hasMore ? encodeCursor(kept.get(kept.size() - 1)) : null;

    List<AuditEventRow> events = new ArrayList<>(kept.size());
    for (AuditEventRowSnapshot row : kept) {
      events.add(toRow(row));
    }
    AuditQueryResult result = new AuditQueryResult(events, page.totalCount(), nextCursor);
    log.info(
        "audit query success scope={} totalCount={} returnedRows={} nextCursorPresent={}"
            + " durationMs={}",
        scope,
        result.totalCount(),
        events.size(),
        nextCursor != null,
        (System.nanoTime() - start) / 1_000_000L);
    return result;
  }

  // ---- filter resolution -------------------------------------------------------------------

  private Set<String> resolveEventTypes(List<String> tokens) {
    if (tokens == null || tokens.isEmpty()) {
      return Set.of();
    }
    LinkedHashSet<String> resolved = new LinkedHashSet<>();
    for (String token : tokens) {
      if (token == null || token.isBlank()) {
        continue;
      }
      String trimmed = token.trim();
      WorkflowEventType type = matchEventType(trimmed);
      if (type == null) {
        throw invalidEventType(trimmed);
      }
      resolved.add(type.value());
    }
    return resolved;
  }

  private static WorkflowEventType matchEventType(String token) {
    for (WorkflowEventType candidate : WorkflowEventType.values()) {
      if (candidate.value().equals(token)) {
        return candidate;
      }
    }
    return null;
  }

  private void validateTimeRange(OffsetDateTime since, OffsetDateTime until) {
    if (since != null && until != null && since.isAfter(until)) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("since", since.toString());
      details.put("until", until.toString());
      log.warn("audit query invalid time range since={} until={}", since, until);
      throw new DomainException(
          DomainErrorCode.INVALID_TIME_RANGE, "--since must not be after --until", details);
    }
  }

  private static int clampLimit(int limit) {
    return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
  }

  // ---- cursor codec (opaque base64url of <createdAt>|<id>) --------------------------------

  private record CursorKeyset(OffsetDateTime createdAt, Long id) {
    static CursorKeyset inactive() {
      return new CursorKeyset(null, null);
    }

    boolean active() {
      return id != null;
    }
  }

  private CursorKeyset decodeCursor(String rawCursor) {
    if (rawCursor == null || rawCursor.isBlank()) {
      return CursorKeyset.inactive();
    }
    String decoded;
    try {
      decoded = new String(Base64.getUrlDecoder().decode(rawCursor.trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException badBase64) {
      throw invalidCursor(rawCursor);
    }
    int separator = decoded.indexOf('|');
    if (separator < 0) {
      throw invalidCursor(rawCursor);
    }
    String timestampPart = decoded.substring(0, separator);
    String idPart = decoded.substring(separator + 1);
    try {
      OffsetDateTime createdAt = OffsetDateTime.parse(timestampPart);
      long id = Long.parseLong(idPart);
      return new CursorKeyset(createdAt, id);
    } catch (RuntimeException malformed) {
      throw invalidCursor(rawCursor);
    }
  }

  private String encodeCursor(AuditEventRowSnapshot row) {
    String raw = row.createdAt().toString() + "|" + row.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  // ---- row mapping + redaction -------------------------------------------------------------

  private AuditEventRow toRow(AuditEventRowSnapshot snapshot) {
    JsonNode sanitizedDetails = sanitizedDetails(snapshot.details());
    return new AuditEventRow(
        snapshot.eventId(),
        // event_type is surfaced as the RAW stored wire string (not re-parsed through
        // WorkflowEventType.fromValue). This is a read over append-only HISTORY and event_type is
        // the
        // one enum column with no DB CHECK constraint, so a legacy/renamed value must not throw and
        // poison the whole page (mirrors parseDetails' per-row resilience). The --event-type FILTER
        // still validates tokens against the registry in resolveEventTypes.
        snapshot.eventType(),
        snapshot.workflowRunId(),
        snapshot.actorIdentity(),
        ActorType.fromValue(snapshot.actorType(), "actorType"),
        snapshot.createdAt(),
        snapshot.priorState() == null
            ? null
            : WorkflowState.fromValue(snapshot.priorState(), "priorState"),
        snapshot.resultingState() == null
            ? null
            : WorkflowState.fromValue(snapshot.resultingState(), "resultingState"),
        snapshot.failureCategory() == null
            ? null
            : FailureCategory.fromValue(snapshot.failureCategory(), "failureCategory"),
        redactReason(snapshot.reason()),
        extractDetailString(sanitizedDetails, WorkflowEventDetailKeys.CORRELATION_ID),
        extractDetailString(sanitizedDetails, WorkflowEventDetailKeys.ARTIFACT_ID));
  }

  /**
   * Strip server-only detail keys then value-redact (mirrors {@code
   * WorkflowInspectionService.sanitizeWireDetails}). Returns the sanitized JSON so {@code
   * correlationId}/{@code artifactId} can be read post-redaction (Reconciliation 10). {@code null}
   * when there are no details.
   */
  private JsonNode sanitizedDetails(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    Map<String, Object> stripped = new LinkedHashMap<>(raw);
    for (String serverOnlyKey : WorkflowEventDetailKeys.SERVER_ONLY_KEYS) {
      stripped.remove(serverOnlyKey);
    }
    return redactionPolicyService
        .redact(stripped, DataClassification.SHAREABLE_REDACTED.value())
        .sanitizedJson();
  }

  private static String extractDetailString(JsonNode details, String key) {
    if (details == null || !details.isObject()) {
      return null;
    }
    JsonNode node = details.get(key);
    if (node == null || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    if (text == null || text.isBlank()) {
      return null;
    }
    return stripControlChars(text);
  }

  /**
   * Redact {@code reason} via the redaction policy (NET-NEW — Reconciliation 10) then strip control
   * characters so a newline/CR in a reason cannot inject a spurious CLI line or JSON control byte.
   */
  private String redactReason(String rawReason) {
    if (rawReason == null) {
      return null;
    }
    String sanitized =
        redactionPolicyService
            .redact(rawReason, DataClassification.SHAREABLE_REDACTED.value())
            .sanitizedText();
    return stripControlChars(sanitized);
  }

  private static String stripControlChars(String value) {
    if (value == null) {
      return null;
    }
    // Java's \p{Cntrl} is ASCII-only ([\x00-\x1F\x7F]); also strip the Unicode line terminators
    // NEL (U+0085), LINE SEPARATOR (U+2028) and PARAGRAPH SEPARATOR (U+2029) so a crafted reason
    // cannot inject a spurious line into the grep-safe one-line-per-event CLI text output.
    return value.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]", " ");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // ---- typed errors ------------------------------------------------------------------------

  private DomainException invalidEventType(String eventType) {
    List<String> allowed = new ArrayList<>();
    for (WorkflowEventType value : WorkflowEventType.values()) {
      allowed.add(value.value());
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("eventType", eventType);
    details.put("allowed", allowed);
    log.warn(
        "audit query invalid event-type token eventType={}", MdcKeys.sanitizeForLog(eventType));
    return new DomainException(
        DomainErrorCode.INVALID_AUDIT_FILTER, "Unknown --event-type value: " + eventType, details);
  }

  private DomainException invalidCursor(String cursor) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("cursor", cursor);
    log.warn("audit query invalid cursor cursor={}", MdcKeys.sanitizeForLog(cursor));
    return new DomainException(
        DomainErrorCode.INVALID_AUDIT_FILTER, "Malformed audit-query cursor", details);
  }

  private DomainException invalidTicketRef(String ticketRef) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("ticketRef", ticketRef);
    log.warn("audit query blank ticket ref ticketRef={}", MdcKeys.sanitizeForLog(ticketRef));
    return new DomainException(
        DomainErrorCode.INVALID_AUDIT_FILTER, "Ticket reference must not be blank", details);
  }

  private DomainException runNotFound(String workflowRunId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("workflowRunId", workflowRunId);
    log.warn("audit query run not found workflowRunId={}", MdcKeys.sanitizeForLog(workflowRunId));
    return new DomainException(
        DomainErrorCode.RUN_NOT_FOUND, "No workflow run for id " + workflowRunId, details);
  }

  // ---- adapter-facing result records (importable by CLI + REST — Reconciliation 13) --------

  /**
   * Story 4.3 (AC1) — the typed audit query result. {@code events} is the redacted, keyset-ordered
   * page; {@code totalCount} is the FULL filtered count (independent of {@code limit}); {@code
   * nextCursor} is the opaque keyset token for the next page, or {@code null} on the last page.
   */
  public record AuditQueryResult(List<AuditEventRow> events, long totalCount, String nextCursor) {}

  /**
   * Story 4.3 (AC1) — one flat audit event row. Nullable reference fields ({@code priorState},
   * {@code resultingState}, {@code failureCategory}, {@code reason}, {@code correlationId}, {@code
   * linkedArtifactId}) are plain nullable references (repo convention). {@code eventId} = event
   * {@code public_id} ({@code evt_...}); {@code workflowRunId} = the run's {@code public_id}
   * ({@code run_...}), REQUIRED per row because by-ticket spans multiple runs. {@code timestamp} =
   * {@code created_at}. {@code reason} is REDACTED. {@code correlationId}/{@code linkedArtifactId}
   * are derived from the redacted {@code details} ({@code correlationId}/{@code artifactId}).
   *
   * <p>{@code eventType} is the RAW stored wire string, NOT re-parsed through {@code
   * WorkflowEventType} — a read over append-only history must tolerate a legacy/out-of-registry
   * {@code event_type} (the one enum column with no DB CHECK) without failing the whole page.
   */
  public record AuditEventRow(
      String eventId,
      String eventType,
      String workflowRunId,
      String actorIdentity,
      ActorType actorType,
      OffsetDateTime timestamp,
      WorkflowState priorState,
      WorkflowState resultingState,
      FailureCategory failureCategory,
      String reason,
      String correlationId,
      String linkedArtifactId) {}

  /**
   * Story 4.3 (AC2) — the raw parsed filter handed from the CLI/REST adapter to the service.
   * Carries un-resolved inputs (raw event-type tokens, raw actor, ISO time bounds, requested limit,
   * opaque cursor); ALL resolution/validation happens inside the service so adapters stay thin
   * (AC9).
   *
   * @param eventTypeTokens raw {@code --event-type} tokens (empty disables the filter)
   * @param actor the {@code --actor} exact-match value, or {@code null}/blank to disable
   * @param since ISO-8601 absolute lower bound (inclusive), or {@code null}
   * @param until ISO-8601 absolute upper bound (inclusive), or {@code null}
   * @param limit the requested page size (clamped to {@code [1,200]} in the service)
   * @param cursor the opaque keyset cursor from a prior response's {@code nextCursor}, or {@code
   *     null}/blank for the first page
   */
  public record AuditQueryFilter(
      List<String> eventTypeTokens,
      String actor,
      OffsetDateTime since,
      OffsetDateTime until,
      int limit,
      String cursor) {}
}
