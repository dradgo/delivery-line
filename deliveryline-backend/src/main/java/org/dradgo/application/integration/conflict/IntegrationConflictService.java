package org.dradgo.application.integration.conflict;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictWritePort;
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

  private final IntegrationConflictReadPort readPort;
  private final IntegrationConflictWritePort writePort;

  public IntegrationConflictService(IntegrationConflictReadPort readPort) {
    this(readPort, null);
  }

  @Autowired
  public IntegrationConflictService(
      IntegrationConflictReadPort readPort, IntegrationConflictWritePort writePort) {
    this.readPort = Objects.requireNonNull(readPort, "readPort");
    this.writePort = writePort;
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
}
