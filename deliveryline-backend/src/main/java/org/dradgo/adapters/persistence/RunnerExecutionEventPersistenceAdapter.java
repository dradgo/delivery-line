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
 * port restricts the type whitelist to the runner-lifecycle + recovery-reconcile event family so
 * callers cannot accidentally append {@code WORKFLOW_STATE_CHANGED} (which must go through {@code
 * WorkflowTransitionService}).
 *
 * <p>Story 3.2a: the whitelist is extended to the five story-3.2 lifecycle event types ({@code
 * RUNNER_DISPATCHED}, {@code RUNNER_HEARTBEAT_STALE}, {@code RUNNER_TIMEOUT}, {@code
 * RUNNER_ORPHANED}, {@code RUNNER_COMPLETED}). Before this fix the broker emitted these types
 * through this port but the whitelist still only allowed the three legacy types — so a real docker
 * boot (where the broker actually emits {@code RUNNER_DISPATCHED}/etc.) would throw {@link
 * IllegalArgumentException}. Existing broker unit tests mock this port, so the gap was latent until
 * the story-3.2a docker contract/IT surface exercised the real adapter.
 */
@Component
public class RunnerExecutionEventPersistenceAdapter implements RunnerExecutionEventPort {

  private static final java.util.Set<WorkflowEventType> ALLOWED_EVENT_TYPES =
      java.util.EnumSet.of(
          WorkflowEventType.RUNNER_STARTED,
          WorkflowEventType.RUNNER_FAILED,
          WorkflowEventType.RECOVERY_RECONCILED,
          WorkflowEventType.RUNNER_DISPATCHED,
          WorkflowEventType.RUNNER_HEARTBEAT_STALE,
          WorkflowEventType.RUNNER_TIMEOUT,
          WorkflowEventType.RUNNER_ORPHANED,
          WorkflowEventType.RUNNER_COMPLETED,
          // Story 3.17a — RunnerExecutionQueue.enqueue appends runner.queued through this port.
          WorkflowEventType.RUNNER_QUEUED);

  private final WorkflowEventWritePort workflowEventWritePort;

  public RunnerExecutionEventPersistenceAdapter(WorkflowEventWritePort workflowEventWritePort) {
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
  }

  @Override
  public String append(
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
    String eventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            eventPublicId,
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
    return eventPublicId;
  }
}
