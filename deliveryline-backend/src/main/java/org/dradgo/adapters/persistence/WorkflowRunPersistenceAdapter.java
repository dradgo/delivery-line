package org.dradgo.adapters.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.WorkflowRunEntityMapper;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.workflow.spi.WorkflowRunArchivePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.application.workflow.spi.WorkflowRunStatePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkflowRunPersistenceAdapter
    implements WorkflowRunReadPort,
        WorkflowRunCreatePort,
        WorkflowRunStatePort,
        WorkflowRunArchivePort,
        WorkflowRunRejectionLoopPort {

  private static final Logger log = LoggerFactory.getLogger(WorkflowRunPersistenceAdapter.class);
  private static final String INCREMENT_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set spec_rejection_loop_count = spec_rejection_loop_count + 1
       where public_id = :publicId
       returning spec_rejection_loop_count
      """;
  private static final String INCREMENT_IMPLEMENTATION_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set implementation_rejection_loop_count = implementation_rejection_loop_count + 1
       where public_id = :publicId
       returning implementation_rejection_loop_count
      """;
  // Story 3f-4 — the split-proposal re-propose loop twin (drives a DISTINCT dispatch idempotency
  // key per attempt + the shared escalation marker).
  private static final String INCREMENT_SPLIT_PROPOSAL_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set split_proposal_loop_count = split_proposal_loop_count + 1
       where public_id = :publicId
       returning split_proposal_loop_count
      """;
  // Story 3h-1 — the build-validation auto-fix loop twin (distinct dispatch idempotency key per
  // attempt + the cap check; shares the escalation marker with the other loops).
  private static final String INCREMENT_BUILD_FIX_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set build_fix_loop_count = build_fix_loop_count + 1
       where public_id = :publicId
       returning build_fix_loop_count
      """;
  private static final String MARK_ESCALATION_SQL =
      // RETURNING an int literal (not the boolean escalation_marker_set column) so the int row
      // mapper below reads cleanly — a returned row means this call just-flipped the marker, zero
      // rows means it was already set. Reading the boolean column via getInt throws "Bad value for
      // type int : t" on Postgres (story 3.21 — first real-DB exercise of this escalation path).
      """
      update workflow_runs
         set escalation_marker_set = true
       where public_id = :publicId
         and escalation_marker_set = false
       returning 1
      """;

  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowRunEntityMapper workflowRunEntityMapper;
  private final NamedParameterJdbcTemplate jdbcTemplate;

  public WorkflowRunPersistenceAdapter(
      WorkflowRunRepository workflowRunRepository,
      WorkflowRunEntityMapper workflowRunEntityMapper,
      NamedParameterJdbcTemplate jdbcTemplate) {
    this.workflowRunRepository = workflowRunRepository;
    this.workflowRunEntityMapper = workflowRunEntityMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<WorkflowRunSnapshot> findByPublicId(String publicId) {
    return workflowRunRepository.findByPublicId(publicId).map(workflowRunEntityMapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public List<WorkflowRunSnapshot> findByParentRunId(String parentRunId) {
    return workflowRunRepository.findByParentRunIdOrderByCreatedAtDescIdDesc(parentRunId).stream()
        .map(workflowRunEntityMapper::toSnapshot)
        .toList();
  }

  // Story 3f-8 (AC1) — the reconciliation-sweep discovery query. Read-only; the sweep then
  // re-invokes
  // the idempotent 3f-7 rollup (its own REQUIRES_NEW tx) per returned parent. Limit clamped to >=
  // 1.
  @Override
  @Transactional(readOnly = true)
  public List<WorkflowRunSnapshot> findStrandedSplitParents(int limit) {
    Pageable page = PageRequest.of(0, Math.max(1, limit));
    return workflowRunRepository
        .findStrandedSplitParents(
            WorkflowState.SPLIT.value(), WorkflowState.COMPLETED.value(), page)
        .stream()
        .map(workflowRunEntityMapper::toSnapshot)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<WorkflowRunSnapshot> listRuns(
      WorkflowState stateFilter, boolean includeArchived, int limit, String projectId) {
    Pageable page = PageRequest.of(0, limit);
    List<WorkflowRunEntity> entities =
        workflowRunRepository.listRunsFiltered(
            stateFilter == null ? null : stateFilter.value(), includeArchived, projectId, page);
    return entities.stream().map(workflowRunEntityMapper::toSnapshot).toList();
  }

  // Story 3d-8 (FR67, AC1/AC4) — the run-archive write seam. Mirrors
  // IntegrationLinkPersistenceAdapter.markArchived: load-by-public-id semantics (RUN_NOT_FOUND when
  // absent) over a bulk marker update that leaves current_state + version untouched. The governed
  // workflow.archived / workflow.unarchived event append is the caller's (WorkflowArchiveService)
  // concern and shares this transaction (REQUIRED propagation).
  @Override
  @Transactional
  public void markArchived(String workflowRunPublicId, java.time.Instant archivedAt) {
    java.util.Objects.requireNonNull(archivedAt, "archivedAt");
    int updated =
        workflowRunRepository.archiveIfNotArchived(
            workflowRunPublicId,
            java.time.OffsetDateTime.ofInstant(archivedAt, java.time.ZoneOffset.UTC));
    requireSingleArchiveWrite(workflowRunPublicId, updated);
    log.info("workflow_run archived publicId={} archivedAt={}", workflowRunPublicId, archivedAt);
  }

  @Override
  @Transactional
  public void clearArchived(String workflowRunPublicId) {
    int updated = workflowRunRepository.clearArchivedIfArchived(workflowRunPublicId);
    requireSingleArchiveWrite(workflowRunPublicId, updated);
    log.info("workflow_run unarchived publicId={}", workflowRunPublicId);
  }

  // A conditional archive/clear that affects 0 rows is either a genuinely-missing run
  // (RUN_NOT_FOUND)
  // or a run whose marker was concurrently flipped between the caller's snapshot read and this
  // write
  // (a lost race → ARCHIVE_NOT_APPLICABLE — the same code the caller's precondition check throws).
  private void requireSingleArchiveWrite(String workflowRunPublicId, int updated) {
    if (updated == 1) {
      return;
    }
    if (!workflowRunRepository.existsByPublicId(workflowRunPublicId)) {
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    throw new DomainException(
        DomainErrorCode.ARCHIVE_NOT_APPLICABLE,
        "Workflow run archive state changed concurrently: " + workflowRunPublicId,
        Map.of("runId", workflowRunPublicId, "reason", "concurrent_modification"));
  }

  @Override
  public WorkflowRunSnapshot create(
      String publicId, WorkflowState initialState, String projectId, String parentRunId) {
    if (parentRunId != null) {
      log.info("creating child run {} under parent {}", publicId, parentRunId);
    }
    return workflowRunEntityMapper.toSnapshot(
        workflowRunRepository.saveAndFlush(
            workflowRunEntityMapper.toNewEntity(publicId, initialState, projectId, parentRunId)));
  }

  @Override
  public void updateCurrentState(String publicId, WorkflowState targetState, Long expectedVersion) {
    if (expectedVersion == null) {
      throw new OptimisticLockingFailureException(
          "Workflow run state update is missing an optimistic-lock version for " + publicId);
    }
    int updated =
        workflowRunRepository.updateCurrentState(publicId, targetState.value(), expectedVersion);
    if (updated != 1) {
      if (!workflowRunRepository.existsByPublicId(publicId)) {
        throw new DomainException(
            DomainErrorCode.RUN_NOT_FOUND,
            "Workflow run not found: " + publicId,
            Map.of("runId", publicId));
      }
      throw new OptimisticLockingFailureException(
          "Workflow run state update lost optimistic lock for " + publicId);
    }
  }

  @Override
  public int incrementAndReadLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadLoopCount workflowRunNotFound publicId={}",
          workflowRunPublicId,
          workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadLoopCount publicId={} newLoopCount={}", workflowRunPublicId, newCount);
    return newCount;
  }

  @Override
  public int incrementAndReadImplementationLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_IMPLEMENTATION_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadImplementationLoopCount workflowRunNotFound publicId={}",
          workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadImplementationLoopCount publicId={} newLoopCount={}",
        workflowRunPublicId,
        newCount);
    return newCount;
  }

  @Override
  public int incrementAndReadSplitProposalLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_SPLIT_PROPOSAL_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadSplitProposalLoopCount workflowRunNotFound publicId={}",
          workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadSplitProposalLoopCount publicId={} newLoopCount={}",
        workflowRunPublicId,
        newCount);
    return newCount;
  }

  @Override
  public int incrementAndReadBuildFixLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_BUILD_FIX_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadBuildFixLoopCount workflowRunNotFound publicId={}", workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadBuildFixLoopCount publicId={} newLoopCount={}",
        workflowRunPublicId,
        newCount);
    return newCount;
  }

  @Override
  public int markEscalationOnce(String workflowRunPublicId) {
    Integer flipped =
        jdbcTemplate.query(
            MARK_ESCALATION_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (flipped == null && !workflowRunRepository.existsByPublicId(workflowRunPublicId)) {
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    int rows = flipped == null ? 0 : 1;
    log.debug(
        "markEscalationOnce publicId={} rowsAffected={} (1=just-flipped, 0=already-set)",
        workflowRunPublicId,
        rows);
    return rows;
  }

  @Override
  public boolean isEscalationMarkerSet(String workflowRunPublicId) {
    return workflowRunRepository
        .findByPublicId(workflowRunPublicId)
        .map(WorkflowRunEntity::isEscalationMarkerSet)
        .orElse(false);
  }

  private static MapSqlParameterSource params(String workflowRunPublicId) {
    return new MapSqlParameterSource("publicId", workflowRunPublicId);
  }
}
