package org.dradgo.application.integration.conflict;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.integration.conflict.ConflictReconciliationSuggester.SuggestedDecision;
import org.dradgo.application.integration.conflict.spi.ConflictListQuery;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictWritePort;
import org.dradgo.application.integration.conflict.spi.UnresolvedConflictCount;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 4.17 (AC6) — the read surface for detected integration conflicts. {@code
 * listUnresolvedConflicts} returns rows where {@code resolved_at IS NULL} (archived-excluded,
 * newest-first) matching an optional filter. There is NO REST endpoint here — story 4.18 owns
 * {@code GET /api/v1/integration-conflicts} and the OpenAPI schema; this is a service method other
 * backend code (and 4.18's controller) call. Bad filter values raise {@code
 * INVALID_COMMAND_PAYLOAD} with {@code details} (Reconciliation 11 — no new DomainErrorCode).
 */
@Service
public class IntegrationConflictService {

  private static final Logger log = LoggerFactory.getLogger(IntegrationConflictService.class);

  /** Default page size for {@code listConflicts} when {@code limit} is omitted. */
  static final int DEFAULT_LIMIT = 50;

  /** Hard ceiling on the page size (mirrors {@code AuditQueryService.MAX_PAGE_SIZE}). */
  static final int MAX_PAGE_SIZE = 200;

  private final IntegrationConflictReadPort readPort;
  private final IntegrationConflictWritePort writePort;
  private final ConflictReconciliationSuggester reconciliationSuggester;

  public IntegrationConflictService(IntegrationConflictReadPort readPort) {
    this(readPort, null, new ConflictReconciliationSuggester());
  }

  @Autowired
  public IntegrationConflictService(
      IntegrationConflictReadPort readPort,
      IntegrationConflictWritePort writePort,
      ConflictReconciliationSuggester reconciliationSuggester) {
    this.readPort = Objects.requireNonNull(readPort, "readPort");
    this.writePort = writePort;
    this.reconciliationSuggester =
        Objects.requireNonNull(reconciliationSuggester, "reconciliationSuggester");
  }

  @Transactional(readOnly = true)
  public List<ConflictSummary> listUnresolvedConflicts(ConflictFilter filter) {
    Objects.requireNonNull(filter, "filter");
    validate(filter);
    List<ConflictSummary> summaries = readPort.listUnresolved(filter);
    log.info(
        "listUnresolvedConflicts returned={} categoryFilter={} integrationTypeFilter={}",
        summaries.size(),
        filter.conflictCategory(),
        filter.integrationType());
    return summaries;
  }

  @Transactional(readOnly = true)
  public Optional<ConflictResolutionView> findConflictForResolution(String conflictPublicId) {
    Objects.requireNonNull(conflictPublicId, "conflictPublicId");
    return readPort.findByPublicId(conflictPublicId);
  }

  /**
   * Story 4.18 (AC2) — the keyset-paginated conflict list for {@code GET
   * /api/v1/integration-conflicts}. Validates + resolves the filter, decodes the opaque cursor,
   * clamps the limit, fetches {@code pageSize + 1} to detect a next page, and bundles the page with
   * the global unresolved/resolved counts (independent of the filter, so stable across pages).
   */
  @Transactional(readOnly = true)
  public ConflictListResult listConflicts(ConflictFilter filter) {
    Objects.requireNonNull(filter, "filter");
    validate(filter);
    int pageSize = clampLimit(filter.limit());
    CursorKeyset cursor = decodeCursor(filter.cursor());
    Double sinceSeconds =
        filter.timeSince() == null ? null : filter.timeSince().toMillis() / 1000.0d;
    ConflictListQuery query =
        new ConflictListQuery(
            blankToNull(filter.conflictCategory()),
            blankToNull(filter.integrationType()),
            blankToNull(filter.ticketReference()),
            blankToNull(filter.workflowRunId()),
            sinceSeconds,
            filter.resolved(),
            cursor.detectedAt(),
            cursor.conflictId(),
            pageSize + 1);
    List<ConflictSummary> fetched = readPort.listConflicts(query);
    boolean hasMore = fetched.size() > pageSize;
    List<ConflictSummary> kept = hasMore ? new ArrayList<>(fetched.subList(0, pageSize)) : fetched;
    String nextCursor = hasMore ? encodeCursor(kept.get(kept.size() - 1)) : null;

    List<UnresolvedConflictCount> counts = readPort.countUnresolvedByCategoryAndIntegration();
    Map<String, Long> byCategory = new LinkedHashMap<>();
    Map<String, Long> byIntegration = new LinkedHashMap<>();
    long totalUnresolved = 0L;
    for (UnresolvedConflictCount count : counts) {
      totalUnresolved += count.count();
      byCategory.merge(count.conflictCategory(), count.count(), Long::sum);
      // Coarse integration tag (mirrors IntegrationConflictMetricsBinder + AC2's linear|github wire
      // vocabulary): github_pr → github, linear → linear, else unknown.
      byIntegration.merge(integrationTag(count.integrationType()), count.count(), Long::sum);
    }
    long totalResolved = readPort.countResolved();
    log.info(
        "listConflicts returned={} totalUnresolved={} totalResolved={} nextCursorPresent={}"
            + " resolvedFilter={} categoryFilter={} integrationTypeFilter={} runFilterPresent={}",
        kept.size(),
        totalUnresolved,
        totalResolved,
        nextCursor != null,
        filter.resolved(),
        filter.conflictCategory(),
        filter.integrationType(),
        filter.workflowRunId() != null);
    return new ConflictListResult(
        kept, totalUnresolved, totalResolved, byCategory, byIntegration, nextCursor);
  }

  /**
   * Story 4.18 (AC3) — the typed detail for {@code GET /api/v1/integration-conflicts/{conflictId}}:
   * the {@link ConflictResolutionView} (both snapshots + resolvedAt) plus the per-category
   * safety-ranked {@link SuggestedDecision} options. {@code Optional.empty()} when the conflict
   * does not exist (the controller maps that to {@code CONFLICT_NOT_FOUND} 404).
   */
  @Transactional(readOnly = true)
  public Optional<ConflictDetail> getConflictDetail(String conflictPublicId) {
    Objects.requireNonNull(conflictPublicId, "conflictPublicId");
    return readPort
        .findByPublicId(conflictPublicId)
        .map(
            view -> {
              IntegrationConflictCategory category =
                  IntegrationConflictCategory.fromNullableValue(
                      view.conflictCategory(), "conflictCategory");
              return new ConflictDetail(view, reconciliationSuggester.suggestFor(category));
            });
  }

  /**
   * Story 4.18 (AC1) — grouped unresolved-conflict count per run (one query) for the operator-queue
   * indicator. Runs with zero unresolved conflicts are absent from the map.
   */
  @Transactional(readOnly = true)
  public Map<String, Integer> unresolvedCountByRun(Collection<String> workflowRunIds) {
    if (workflowRunIds == null || workflowRunIds.isEmpty()) {
      return Map.of();
    }
    return readPort.unresolvedCountByRun(workflowRunIds);
  }

  private static int clampLimit(Integer limit) {
    int requested = limit == null ? DEFAULT_LIMIT : limit;
    return Math.min(Math.max(requested, 1), MAX_PAGE_SIZE);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String integrationTag(String integrationType) {
    if (ConflictIntegrationTypes.GITHUB_PR.equals(integrationType)) {
      return "github";
    }
    if (ConflictIntegrationTypes.LINEAR.equals(integrationType)) {
      return ConflictIntegrationTypes.LINEAR;
    }
    return "unknown";
  }

  // ---- cursor codec (opaque base64url of <detectedAt>|<conflictPublicId>) --------------------

  private record CursorKeyset(OffsetDateTime detectedAt, String conflictId) {
    static CursorKeyset inactive() {
      return new CursorKeyset(null, null);
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
      throw invalidFilter("cursor", rawCursor);
    }
    int separator = decoded.indexOf('|');
    if (separator < 0) {
      throw invalidFilter("cursor", rawCursor);
    }
    try {
      OffsetDateTime detectedAt = OffsetDateTime.parse(decoded.substring(0, separator));
      String conflictId = decoded.substring(separator + 1);
      if (conflictId.isBlank()) {
        throw invalidFilter("cursor", rawCursor);
      }
      return new CursorKeyset(detectedAt, conflictId);
    } catch (java.time.format.DateTimeParseException malformed) {
      throw invalidFilter("cursor", rawCursor);
    }
  }

  private String encodeCursor(ConflictSummary last) {
    OffsetDateTime detectedAt = last.detectedAt().atOffset(java.time.ZoneOffset.UTC);
    String raw = detectedAt + "|" + last.conflictId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Marks the conflict resolved inside the caller's ambient transaction ({@code MANDATORY} so a
   * standalone-tx resolve cannot break the reconcile's atomicity — Task 3 / Reconciliation 4). The
   * atomic concurrent-resolve guard is {@code markResolved}'s {@code WHERE resolved_at IS NULL}
   * clause: 0 rows affected → {@code CONFLICT_ALREADY_RESOLVED}. Story 4.6 code review (P2): the
   * post-write re-read was dead weight — the caller already holds the pre-resolve {@code
   * ConflictResolutionView} and discarded the returned one — so this returns void and skips the
   * redundant {@code findByPublicId}.
   */
  /**
   * Story 4.6 code review (P3) — serialize concurrent reconciles on the same run. Takes the per-run
   * advisory lock inside the caller's ambient ({@code MANDATORY}) transaction so the {@code
   * RecoveryService} prep tx's "is this the last unresolved conflict?" read and the resulting
   * terminal transition are atomic against a sibling reconcile on another conflict of the same run.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockRunForReconcile(String workflowRunId) {
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    if (writePort == null) {
      throw new IllegalStateException(
          "IntegrationConflictWritePort is required to lock a run for reconcile");
    }
    writePort.lockRunForReconcile(workflowRunId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void resolveConflict(
      String conflictPublicId,
      String workflowRunId,
      String recoveryActionPublicId,
      Instant resolvedAt) {
    Objects.requireNonNull(conflictPublicId, "conflictPublicId");
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(recoveryActionPublicId, "recoveryActionPublicId");
    Objects.requireNonNull(resolvedAt, "resolvedAt");
    if (writePort == null) {
      throw new IllegalStateException(
          "IntegrationConflictWritePort is required to resolve conflicts");
    }
    boolean updated = writePort.markResolved(conflictPublicId, recoveryActionPublicId, resolvedAt);
    if (!updated) {
      throw new DomainException(
          DomainErrorCode.CONFLICT_ALREADY_RESOLVED,
          "Integration conflict is already resolved: " + conflictPublicId,
          Map.of("conflictId", conflictPublicId, "runId", workflowRunId));
    }
  }

  private static void validate(ConflictFilter filter) {
    if (filter.conflictCategory() != null && !filter.conflictCategory().isBlank()) {
      // fromValue raises DomainException(INVALID_ARGUMENT-shaped) already, but we want a
      // filter-specific INVALID_COMMAND_PAYLOAD with the offending field — validate explicitly.
      if (!isKnown(filter.conflictCategory())) {
        throw invalidFilter("conflictCategory", filter.conflictCategory());
      }
    }
    if (filter.integrationType() != null && !filter.integrationType().isBlank()) {
      String type = filter.integrationType();
      if (!ConflictIntegrationTypes.LINEAR.equals(type)
          && !ConflictIntegrationTypes.GITHUB_PR.equals(type)) {
        throw invalidFilter("integrationType", type);
      }
    }
    if (filter.timeSince() != null && filter.timeSince().isNegative()) {
      throw invalidFilter("timeSince", filter.timeSince().toString());
    }
  }

  private static boolean isKnown(String category) {
    for (IntegrationConflictCategory value : IntegrationConflictCategory.values()) {
      if (value.value().equals(category)) {
        return true;
      }
    }
    return false;
  }

  private static DomainException invalidFilter(String field, String value) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("field", field);
    details.put("value", value);
    details.put("reason", "invalid_conflict_filter");
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Invalid integration-conflict filter value for " + field,
        details);
  }

  /**
   * Story 4.18 (AC2) — the {@code listConflicts} result: the keyset-ordered page ({@code
   * conflicts}, newest first), the FILTER-INDEPENDENT global {@code totalUnresolved} / {@code
   * totalResolved} counts + their unresolved-by-category / by-integration breakdowns (mirroring the
   * metrics gauge), and the opaque {@code nextCursor} for the next page ({@code null} on the last
   * page). Nested here (not in {@code .spi}) so the REST controller can map it without tripping the
   * thin-controller pin.
   */
  public record ConflictListResult(
      List<ConflictSummary> conflicts,
      long totalUnresolved,
      long totalResolved,
      Map<String, Long> totalUnresolvedByCategory,
      Map<String, Long> totalUnresolvedByIntegration,
      String nextCursor) {}

  /**
   * Story 4.18 (AC3) — the {@code getConflictDetail} result: the {@link ConflictResolutionView}
   * (both state snapshots + resolvedAt) plus the per-category safety-ranked reconciliation
   * suggestions.
   */
  public record ConflictDetail(
      ConflictResolutionView view, List<SuggestedDecision> suggestedDecisions) {}
}
