package org.dradgo.application.runner.spi;

import java.time.OffsetDateTime;
import java.util.Map;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;

/**
 * Workflow-event append helper scoped to runner-lifecycle events only.
 *
 * <p>Permitted event types: {@link WorkflowEventType#RUNNER_STARTED}, {@link
 * WorkflowEventType#RUNNER_FAILED}, {@link WorkflowEventType#RECOVERY_RECONCILED}. Implementations
 * MUST reject any other type with an {@link IllegalArgumentException}; the authoritative
 * workflow-state path for {@code WORKFLOW_STATE_CHANGED} is {@code WorkflowTransitionService}.
 */
public interface RunnerExecutionEventPort {

  void append(
      String workflowRunPublicId,
      WorkflowEventType eventType,
      ActorContext actor,
      String reason,
      FailureCategory failureCategory,
      OffsetDateTime createdAt,
      Map<String, Object> details);
}
