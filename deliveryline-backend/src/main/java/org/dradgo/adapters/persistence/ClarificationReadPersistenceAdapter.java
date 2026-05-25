package org.dradgo.adapters.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ClarificationEntity;
import org.dradgo.adapters.persistence.mapper.ClarificationEntityMapper;
import org.dradgo.adapters.persistence.repository.ClarificationRepository;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleSnapshot;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link ClarificationReadPort} implementation (story 2.11). Every query inherits the
 * repository's {@code archived_at IS NULL} filter so the application layer never observes
 * tombstoned clarifications. Status-grouped ordering is pinned in the JPQL itself (trap T11).
 */
@Component
public class ClarificationReadPersistenceAdapter implements ClarificationReadPort {

  private static final Logger log =
      LoggerFactory.getLogger(ClarificationReadPersistenceAdapter.class);

  private final ClarificationRepository clarificationRepository;
  private final ClarificationEntityMapper clarificationEntityMapper;

  public ClarificationReadPersistenceAdapter(
      ClarificationRepository clarificationRepository,
      ClarificationEntityMapper clarificationEntityMapper) {
    this.clarificationRepository = clarificationRepository;
    this.clarificationEntityMapper = clarificationEntityMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Clarification> findByPublicId(String clarificationPublicId) {
    Optional<ClarificationEntity> row =
        clarificationRepository.findByPublicIdAndArchivedAtIsNull(clarificationPublicId);
    if (row.isEmpty()) {
      log.debug("clarification read miss clarificationId={}", clarificationPublicId);
      return Optional.empty();
    }
    Clarification projection = clarificationEntityMapper.toProjection(row.get());
    log.debug(
        "clarification read hit clarificationId={} workflowRunId={} artifactId={} status={}",
        projection.publicId(),
        projection.workflowRunId(),
        projection.artifactId(),
        projection.status());
    return Optional.of(projection);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Clarification> listByWorkflowRunId(String workflowRunPublicId) {
    log.info("clarification list-by-run entry workflowRunId={}", workflowRunPublicId);
    List<ClarificationEntity> rows =
        clarificationRepository.findByWorkflowRunPublicIdOrdered(workflowRunPublicId);
    List<Clarification> projections = new ArrayList<>(rows.size());
    for (ClarificationEntity row : rows) {
      projections.add(clarificationEntityMapper.toProjection(row));
    }
    log.info(
        "clarification list-by-run exit workflowRunId={} count={}",
        workflowRunPublicId,
        projections.size());
    return List.copyOf(projections);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ClarificationLifecycleSnapshot> findLifecycleSnapshotByPublicId(
      String clarificationPublicId) {
    Optional<ClarificationEntity> row =
        clarificationRepository.findByPublicIdAndArchivedAtIsNull(clarificationPublicId);
    if (row.isEmpty()) {
      log.debug("clarification lifecycle-snapshot miss clarificationId={}", clarificationPublicId);
      return Optional.empty();
    }
    ClarificationLifecycleSnapshot snapshot =
        clarificationEntityMapper.toLifecycleSnapshot(row.get());
    log.debug(
        "clarification lifecycle-snapshot hit clarificationId={} workflowRunId={} status={}",
        snapshot.publicId(),
        snapshot.workflowRunId(),
        snapshot.status());
    return Optional.of(snapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public int countPendingByWorkflowRun(String workflowRunPublicId) {
    long count =
        clarificationRepository.countPendingByWorkflowRunPublicId(workflowRunPublicId);
    log.debug(
        "clarification count-pending workflowRunId={} pendingCount={}",
        workflowRunPublicId,
        count);
    if (count > Integer.MAX_VALUE) {
      throw new IllegalStateException(
          "Pending clarification count overflow for run " + workflowRunPublicId);
    }
    return (int) count;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Clarification> listByArtifactId(String artifactPublicId) {
    log.info("clarification list-by-artifact entry artifactId={}", artifactPublicId);
    List<ClarificationEntity> rows =
        clarificationRepository.findByArtifactPublicIdOrdered(artifactPublicId);
    List<Clarification> projections = new ArrayList<>(rows.size());
    for (ClarificationEntity row : rows) {
      projections.add(clarificationEntityMapper.toProjection(row));
    }
    log.info(
        "clarification list-by-artifact exit artifactId={} count={}",
        artifactPublicId,
        projections.size());
    return List.copyOf(projections);
  }
}
