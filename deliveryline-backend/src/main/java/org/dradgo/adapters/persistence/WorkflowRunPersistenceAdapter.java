package org.dradgo.adapters.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.WorkflowRunEntityMapper;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
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
  public List<WorkflowRunSnapshot> listRuns(WorkflowState stateFilter, int limit) {
    Pageable page = PageRequest.of(0, limit);
    List<WorkflowRunEntity> entities =
        stateFilter == null
            ? workflowRunRepository.findAllByOrderByCreatedAtDescIdDesc(page)
            : workflowRunRepository.findByCurrentStateOrderByCreatedAtDescIdDesc(
                stateFilter.value(), page);
    return entities.stream().map(workflowRunEntityMapper::toSnapshot).toList();
  }

  @Override
  public WorkflowRunSnapshot create(String publicId, WorkflowState initialState, String projectId) {
    return workflowRunEntityMapper.toSnapshot(
        workflowRunRepository.saveAndFlush(
            workflowRunEntityMapper.toNewEntity(publicId, initialState, projectId)));
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
