package org.dradgo.adapters.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ApprovalEntity;
import org.dradgo.adapters.persistence.mapper.ApprovalEntityMapper;
import org.dradgo.adapters.persistence.repository.ApprovalRepository;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link ApprovalReadPort} implementation (story 2.8). Every query inherits the
 * repository's {@code archived_at IS NULL} filter so the application layer never observes
 * tombstoned approvals (trap T7).
 */
@Component
public class ApprovalReadPersistenceAdapter implements ApprovalReadPort {

  private static final Logger log = LoggerFactory.getLogger(ApprovalReadPersistenceAdapter.class);

  private final ApprovalRepository approvalRepository;
  private final ApprovalEntityMapper approvalEntityMapper;

  public ApprovalReadPersistenceAdapter(
      ApprovalRepository approvalRepository, ApprovalEntityMapper approvalEntityMapper) {
    this.approvalRepository = approvalRepository;
    this.approvalEntityMapper = approvalEntityMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ApprovalSnapshot> findLatestApprovedForArtifactLineage(
      String workflowRunPublicId, String artifactType) {
    log.info(
        "approval read latest-approved entry workflowRunId={} artifactType={}",
        workflowRunPublicId,
        artifactType);
    List<ApprovalEntity> rows =
        approvalRepository.findLatestApprovedForArtifactLineage(workflowRunPublicId, artifactType);
    if (rows.isEmpty()) {
      log.info(
          "approval read latest-approved miss workflowRunId={} artifactType={}",
          workflowRunPublicId,
          artifactType);
      return Optional.empty();
    }
    ApprovalSnapshot snapshot = approvalEntityMapper.toSnapshot(rows.get(0));
    log.info(
        "approval read latest-approved hit workflowRunId={} artifactType={} approvalId={} artifactId={} artifactVersion={}",
        workflowRunPublicId,
        artifactType,
        snapshot.publicId(),
        snapshot.artifactId(),
        snapshot.artifactVersion());
    return Optional.of(snapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ApprovalSnapshot> findLatestApprovedForArtifact(String artifactPublicId) {
    log.info("approval read latest-approved-for-artifact entry artifactId={}", artifactPublicId);
    List<ApprovalEntity> rows = approvalRepository.findLatestApprovedForArtifact(artifactPublicId);
    if (rows.isEmpty()) {
      log.info("approval read latest-approved-for-artifact miss artifactId={}", artifactPublicId);
      return Optional.empty();
    }
    ApprovalSnapshot snapshot = approvalEntityMapper.toSnapshot(rows.get(0));
    log.info(
        "approval read latest-approved-for-artifact hit artifactId={} approvalId={} artifactVersion={}",
        artifactPublicId,
        snapshot.publicId(),
        snapshot.artifactVersion());
    return Optional.of(snapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ApprovalSnapshot> listByWorkflowRunAndArtifactType(
      String workflowRunPublicId, String artifactType) {
    log.info(
        "approval list-by-type entry workflowRunId={} artifactType={}",
        workflowRunPublicId,
        artifactType);
    List<ApprovalEntity> rows =
        approvalRepository.listByWorkflowRunAndArtifactType(workflowRunPublicId, artifactType);
    List<ApprovalSnapshot> snapshots = new ArrayList<>(rows.size());
    for (ApprovalEntity row : rows) {
      snapshots.add(approvalEntityMapper.toSnapshot(row));
    }
    log.info(
        "approval list-by-type exit workflowRunId={} artifactType={} count={}",
        workflowRunPublicId,
        artifactType,
        snapshots.size());
    return List.copyOf(snapshots);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ApprovalSnapshot> listRejectionsByWorkflowRunAndArtifactType(
      String workflowRunPublicId, String artifactType) {
    log.info(
        "approval list-rejections entry workflowRunId={} artifactType={}",
        workflowRunPublicId,
        artifactType);
    List<ApprovalEntity> rows =
        approvalRepository.listRejectionsByWorkflowRunAndArtifactType(
            workflowRunPublicId, artifactType);
    List<ApprovalSnapshot> snapshots = new ArrayList<>(rows.size());
    for (ApprovalEntity row : rows) {
      snapshots.add(approvalEntityMapper.toSnapshot(row));
    }
    log.info(
        "approval list-rejections exit workflowRunId={} artifactType={} count={}",
        workflowRunPublicId,
        artifactType,
        snapshots.size());
    return List.copyOf(snapshots);
  }
}
