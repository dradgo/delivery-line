package org.dradgo.adapters.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ProviderUsageSnapshotEntity;
import org.dradgo.adapters.persistence.repository.ProviderUsageSnapshotRepository;
import org.dradgo.application.runner.ProviderUsageSnapshotView;
import org.dradgo.application.runner.ProviderUsageSnapshotView.UsageWindow;
import org.dradgo.application.runner.spi.ProviderUsageSnapshotReadPort;
import org.dradgo.application.runner.spi.ProviderUsageSnapshotWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3d-7 (FR69, AC3/AC5) — persistence adapter over the V24 {@code provider_usage_snapshots}
 * table, implementing both the write and read SPI ports. Mirrors {@code
 * BatchSubmissionPersistenceAdapter}: own {@code REQUIRED} transaction per call; {@code
 * DataIntegrityViolationException} on the unique public_id mapped to a typed {@code
 * INTERNAL_ERROR}.
 *
 * <p>NON-SECRET by construction (Trap T1): only window numbers, timestamps, and the non-secret
 * account label cross this boundary — never a token/key/account secret.
 */
@Component
public class ProviderUsageSnapshotPersistenceAdapter
    implements ProviderUsageSnapshotWritePort, ProviderUsageSnapshotReadPort {

  private static final Logger log =
      LoggerFactory.getLogger(ProviderUsageSnapshotPersistenceAdapter.class);

  private static final String UQ_PUBLIC_ID_CONSTRAINT = "uq_provider_usage_snapshots_public_id";

  private final ProviderUsageSnapshotRepository repository;

  public ProviderUsageSnapshotPersistenceAdapter(ProviderUsageSnapshotRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public OffsetDateTime insert(NewProviderUsageSnapshot snapshot) {
    ProviderUsageSnapshotEntity entity = new ProviderUsageSnapshotEntity();
    entity.setPublicId(snapshot.publicId());
    entity.setWorkflowRunId(snapshot.workflowRunId());
    entity.setRunnerExecutionId(snapshot.runnerExecutionId());
    entity.setAccountReference(snapshot.accountReference());
    entity.setSignalState(snapshot.signalState());
    entity.setFiveHourUsedFraction(toBigDecimal(snapshot.fiveHourUsedFraction()));
    entity.setFiveHourUsed(snapshot.fiveHourUsed());
    entity.setFiveHourLimit(snapshot.fiveHourLimit());
    entity.setFiveHourResetsAt(snapshot.fiveHourResetsAt());
    entity.setWeeklyUsedFraction(toBigDecimal(snapshot.weeklyUsedFraction()));
    entity.setWeeklyUsed(snapshot.weeklyUsed());
    entity.setWeeklyLimit(snapshot.weeklyLimit());
    entity.setWeeklyResetsAt(snapshot.weeklyResetsAt());
    entity.setAsOf(snapshot.asOf());
    try {
      ProviderUsageSnapshotEntity saved = repository.saveAndFlush(entity);
      return saved.getCreatedAt();
    } catch (DataIntegrityViolationException violation) {
      throw mapIntegrityViolation(snapshot.publicId(), violation);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProviderUsageSnapshotView> findLatestByWorkflowRunId(String workflowRunId) {
    return repository
        .findFirstByWorkflowRunIdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(workflowRunId)
        .map(ProviderUsageSnapshotPersistenceAdapter::toView);
  }

  @Override
  @Transactional(readOnly = true)
  public SignalStateCounts countActiveBySignalState() {
    return new SignalStateCounts(
        repository.countByArchivedAtIsNullAndSignalState("available"),
        repository.countByArchivedAtIsNullAndSignalState("not_exposed"));
  }

  private static ProviderUsageSnapshotView toView(ProviderUsageSnapshotEntity entity) {
    return new ProviderUsageSnapshotView(
        entity.getPublicId(),
        entity.getWorkflowRunId(),
        entity.getRunnerExecutionId(),
        entity.getAccountReference(),
        entity.getSignalState(),
        new UsageWindow(
            toDouble(entity.getFiveHourUsedFraction()),
            entity.getFiveHourUsed(),
            entity.getFiveHourLimit(),
            entity.getFiveHourResetsAt()),
        new UsageWindow(
            toDouble(entity.getWeeklyUsedFraction()),
            entity.getWeeklyUsed(),
            entity.getWeeklyLimit(),
            entity.getWeeklyResetsAt()),
        entity.getAsOf(),
        entity.getCreatedAt());
  }

  private static BigDecimal toBigDecimal(Double value) {
    // Guard against NaN/Infinity, which would make BigDecimal.valueOf throw and drop the whole
    // snapshot; a non-finite fraction is simply not persisted (review 2026-06-24).
    if (value == null || !Double.isFinite(value)) {
      return null;
    }
    return BigDecimal.valueOf(value);
  }

  private static Double toDouble(BigDecimal value) {
    return value == null ? null : value.doubleValue();
  }

  private DomainException mapIntegrityViolation(
      String publicId, DataIntegrityViolationException violation) {
    String message = violation.getMostSpecificCause().getMessage();
    if (message != null && message.contains(UQ_PUBLIC_ID_CONSTRAINT)) {
      log.error(
          "provider-usage snapshot write public-id collision publicId={} constraint={}",
          publicId,
          UQ_PUBLIC_ID_CONSTRAINT);
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("source", "db_unique_constraint");
      details.put("constraintName", UQ_PUBLIC_ID_CONSTRAINT);
      return new DomainException(
          DomainErrorCode.INTERNAL_ERROR, "Provider usage snapshot public-id collision", details);
    }
    log.error(
        "provider-usage snapshot write integrity-violation publicId={} cause={}",
        publicId,
        message);
    throw violation;
  }
}
