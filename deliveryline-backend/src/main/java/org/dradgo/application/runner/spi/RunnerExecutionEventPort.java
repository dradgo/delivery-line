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

  /**
   * Append a runner-lifecycle event and return the public id ({@code evt_…}) assigned to the new
   * row. Story 3.2a AC10 needs the {@code runner.dispatched} id surfaced back to {@link
   * org.dradgo.application.recovery.RecoveryService} so the retry result can anchor the audit trail
   * to the new dispatch; callers that do not need the id may ignore the return value.
   */
  String append(
      String workflowRunPublicId,
      WorkflowEventType eventType,
      ActorContext actor,
      String reason,
      FailureCategory failureCategory,
      OffsetDateTime createdAt,
      Map<String, Object> details);
}
