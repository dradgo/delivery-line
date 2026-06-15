package org.dradgo.adapters.persistence;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.dradgo.adapters.persistence.mapper.WorkflowEventEntityMapper;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkflowEventPersistenceAdapter
    implements WorkflowEventWritePort, WorkflowEventReadPort {

  private final WorkflowEventRepository workflowEventRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowEventEntityMapper workflowEventEntityMapper;

  public WorkflowEventPersistenceAdapter(
      WorkflowEventRepository workflowEventRepository,
      WorkflowRunRepository workflowRunRepository,
      WorkflowEventEntityMapper workflowEventEntityMapper) {
    this.workflowEventRepository = workflowEventRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.workflowEventEntityMapper = workflowEventEntityMapper;
  }

  @Override
  public void append(WorkflowEventRecord eventRecord) {
    var workflowRun =
        workflowRunRepository
            .findByPublicId(eventRecord.workflowRunPublicId())
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.RUN_NOT_FOUND,
                        "Workflow run not found while appending event: "
                            + eventRecord.workflowRunPublicId(),
                        Map.of("runId", eventRecord.workflowRunPublicId())));
    workflowEventRepository.saveAndFlush(
        workflowEventEntityMapper.toEntity(eventRecord, workflowRun));
  }

  // Read methods are wrapped in a readOnly transaction so the lazy `workflowRun` association on
  // WorkflowEventEntity stays attached to the JPA session through the `.map(toRecord)` call.
  // Without this, Spring Data's per-method implicit tx closes when the repository call returns,
  // detaching the entity — the mapper's read of workflowRun.publicId then trips
  // LazyInitializationException. The bug was latent because the only IT coverage exercised
  // empty-result paths (precondition rejections) where the mapper was never invoked.
  @Override
  @Transactional(readOnly = true)
  public Optional<WorkflowEventRecord> findLatestByWorkflowRunPublicId(String workflowRunPublicId) {
    return workflowEventRepository
        .findFirstLatestByWorkflowRunPublicId(workflowRunPublicId)
        .map(workflowEventEntityMapper::toRecord);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<WorkflowEventRecord> findLatestTransitionToState(
      String workflowRunPublicId, org.dradgo.domain.registry.WorkflowState targetState) {
    return workflowEventRepository
        .findLatestTransitionToState(
            workflowRunPublicId,
            Objects.requireNonNull(targetState, "targetState").value(),
            org.springframework.data.domain.PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .map(workflowEventEntityMapper::toRecord);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<WorkflowEventRecord> findLatestFailureEvent(String workflowRunPublicId) {
    return workflowEventRepository
        .findFirstLatestFailureEvent(workflowRunPublicId)
        .map(workflowEventEntityMapper::toRecord);
  }

  @Override
  public Optional<String> findLatestCorrelationId(String workflowRunPublicId) {
    // Pushes the newest-first non-null/non-blank correlationId scan to the database, with
    // LIMIT 1 so the DB stops at the first match — no in-memory walk, no event-count cap.
    // PostgreSQL-native (the project's only production target). Replaces a prior cap of 100
    // events that could miss the latest stored correlationId on long-lived runs. (review F528)
    return workflowEventRepository.findLatestCorrelationIdInDetails(workflowRunPublicId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<WorkflowEventRecord> listByWorkflowRunPublicId(
      String workflowRunPublicId, OffsetDateTime sinceInclusive) {
    // Ask for HISTORY_CEILING + 1 so we can detect "exceeds the ceiling" without re-querying.
    PageRequest page = PageRequest.of(0, HISTORY_CEILING + 1);
    List<WorkflowEventEntity> entities =
        sinceInclusive == null
            ? workflowEventRepository.findByWorkflowRunPublicIdOrderByCreatedAtAscIdAsc(
                workflowRunPublicId, page)
            : workflowEventRepository
                .findByWorkflowRunPublicIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
                    workflowRunPublicId, sinceInclusive, page);
    if (entities.size() > HISTORY_CEILING) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("runId", workflowRunPublicId);
      details.put("ceiling", HISTORY_CEILING);
      throw new DomainException(
          DomainErrorCode.HISTORY_TOO_LARGE,
          "Run history exceeds " + HISTORY_CEILING + " events; pass --since to narrow.",
          details);
    }
    List<WorkflowEventRecord> records = new ArrayList<>(entities.size());
    for (WorkflowEventEntity entity : entities) {
      records.add(workflowEventEntityMapper.toRecord(entity));
    }
    return records;
  }
}
