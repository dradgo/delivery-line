package org.dradgo.application.integration.conflict;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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

  public IntegrationConflictService(IntegrationConflictReadPort readPort) {
    this.readPort = Objects.requireNonNull(readPort, "readPort");
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
