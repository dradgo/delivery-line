package org.dradgo.adapters.persistence;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.springframework.stereotype.Component;

/**
 * Adapts {@link RunnerExecutionEventPort} onto the canonical {@link WorkflowEventWritePort}. The
 * port restricts the type whitelist to {@link WorkflowEventType#RUNNER_STARTED}, {@link
 * WorkflowEventType#RUNNER_FAILED}, and {@link WorkflowEventType#RECOVERY_RECONCILED} so callers
 * cannot accidentally append {@code WORKFLOW_STATE_CHANGED} (which must go through {@code
 * WorkflowTransitionService}).
 */
@Component
public class RunnerExecutionEventPersistenceAdapter implements RunnerExecutionEventPort {

  private static final java.util.Set<WorkflowEventType> ALLOWED_EVENT_TYPES =
      java.util.EnumSet.of(
          WorkflowEventType.RUNNER_STARTED,
          WorkflowEventType.RUNNER_FAILED,
          WorkflowEventType.RECOVERY_RECONCILED);

  private final WorkflowEventWritePort workflowEventWritePort;

  public RunnerExecutionEventPersistenceAdapter(WorkflowEventWritePort workflowEventWritePort) {
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
  }

  @Override
  public void append(
      String workflowRunPublicId,
      WorkflowEventType eventType,
      ActorContext actor,
      String reason,
      FailureCategory failureCategory,
      OffsetDateTime createdAt,
      Map<String, Object> details) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(details, "details");
    if (!ALLOWED_EVENT_TYPES.contains(eventType)) {
      throw new IllegalArgumentException(
          "RunnerExecutionEventPort only accepts "
              + ALLOWED_EVENT_TYPES
              + " but received "
              + eventType.value());
    }
    Map<String, Object> safeDetails = new LinkedHashMap<>(details);
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            workflowRunPublicId,
            eventType,
            null,
            null,
            actor.actorIdentity(),
            actor.actorType(),
            reason,
            failureCategory,
            false,
            createdAt,
            safeDetails));
  }
}
