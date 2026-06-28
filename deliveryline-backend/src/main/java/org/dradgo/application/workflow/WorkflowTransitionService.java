package org.dradgo.application.workflow;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunStatePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkflowTransitionService {

  private static final Logger log = LoggerFactory.getLogger(WorkflowTransitionService.class);

  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 256;

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowRunStatePort workflowRunStatePort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final WorkflowTransitionTable transitionTable;
  private final TransactionTemplate transactionTemplate;
  private final WorkflowTransitionConcurrencyProbe concurrencyProbe;
  // Story 3.16 (Task 2) — the completion-sync collaborators. WorkflowOrchestrationService depends
  // on
  // THIS service, so it must be injected lazily (ObjectProvider, resolved at afterCommit time,
  // never
  // eagerly in the ctor) to break the cycle ([[broker-orchestration-lazy-supplier]], Fact 6).
  private final ObjectProvider<WorkflowOrchestrationService> orchestrationProvider;
  // Story 3f-3 (AC6) — the dependency-release collaborator. It depends on THIS service, so it is
  // injected lazily (ObjectProvider, resolved at afterCommit time) to break the cycle, mirroring
  // orchestrationProvider above.
  private final ObjectProvider<RunDependencyReleaseService> dependencyReleaseProvider;
  private final WorkflowProperties workflowProperties;

  public WorkflowTransitionService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowRunStatePort workflowRunStatePort,
      WorkflowEventWritePort workflowEventWritePort,
      PlatformTransactionManager transactionManager,
      ObjectProvider<WorkflowTransitionConcurrencyProbe> concurrencyProbeProvider,
      ObjectProvider<WorkflowOrchestrationService> orchestrationProvider,
      ObjectProvider<RunDependencyReleaseService> dependencyReleaseProvider,
      WorkflowProperties workflowProperties) {
    this.workflowRunReadPort = workflowRunReadPort;
    this.workflowRunStatePort = workflowRunStatePort;
    this.workflowEventWritePort = workflowEventWritePort;
    this.transitionTable = WorkflowTransitionTable.defaultTable();
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.concurrencyProbe =
        concurrencyProbeProvider.getIfAvailable(WorkflowTransitionConcurrencyProbe::noop);
    this.orchestrationProvider = orchestrationProvider;
    this.dependencyReleaseProvider = dependencyReleaseProvider;
    this.workflowProperties = workflowProperties;
  }

  public void transition(
      String runId,
      WorkflowState targetState,
      TransitionActor actor,
      String reason,
      String idempotencyKey) {
    transition(runId, targetState, actor, reason, idempotencyKey, null, Map.of());
  }

  public void transition(
      String runId,
      WorkflowState targetState,
      TransitionActor actor,
      String reason,
      String idempotencyKey,
      FailureCategory failureCategory) {
    transition(runId, targetState, actor, reason, idempotencyKey, failureCategory, Map.of());
  }

  public void transition(
      String runId,
      WorkflowState targetState,
      TransitionActor actor,
      String reason,
      String idempotencyKey,
      Map<String, Object> eventDetails) {
    transition(runId, targetState, actor, reason, idempotencyKey, null, eventDetails);
  }

  public void transition(
      String runId,
      WorkflowState targetState,
      TransitionActor actor,
      String reason,
      String idempotencyKey,
      FailureCategory failureCategory,
      Map<String, Object> eventDetails) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(targetState, "targetState");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(eventDetails, "eventDetails");
    if (idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
    if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "idempotencyKey exceeds max length " + MAX_IDEMPOTENCY_KEY_LENGTH);
    }

    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            doTransition(
                runId, targetState, actor, reason, idempotencyKey, failureCategory, eventDetails);
            registerCompletionSyncHookIfApplicable(runId, targetState);
            registerDependencyReleaseHookIfApplicable(runId, targetState);
          });
    } catch (OptimisticLockingFailureException exception) {
      throw concurrentConflict(runId, targetState, idempotencyKey, exception);
    }
  }

  /**
   * Story 3.16 (AC3/AC4, Task 2) — when this transition commits a run into {@code Completed} and
   * completion-sync is enabled, register a post-commit {@link TransactionSynchronization} whose
   * {@code afterCommit} resolves {@link WorkflowOrchestrationService} lazily (Fact 6) and posts the
   * merge-ready summary back to the source Linear ticket. Registered inside the transition's
   * transaction (so it fires only on a genuine commit); the hook runs AFTER commit, so the {@code
   * Completed} transition is already durable and a post failure cannot roll it back (AC4 is
   * structural, not just try/catch). Fires for ANY committed {@code Completed} transition — the
   * production driver ({@code TechnicalApprovalService.acceptImplementation}) lands in story 3.20;
   * until then tests drive a {@code WaitingForReview → Completed} transition directly.
   */
  private void registerCompletionSyncHookIfApplicable(String runId, WorkflowState targetState) {
    if (targetState != WorkflowState.COMPLETED) {
      return;
    }
    if (!workflowProperties.linearCompletionSync().enabled()) {
      log.debug(
          "completion-sync hook not registered (linear-completion-sync.enabled=false) workflowRunId={}",
          runId);
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      // No active synchronization (e.g. a direct non-transactional call path) — nothing to hook.
      log.warn(
          "completion-sync hook not registered (no active transaction synchronization) workflowRunId={}",
          runId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            WorkflowOrchestrationService orchestration = orchestrationProvider.getIfAvailable();
            if (orchestration == null) {
              log.warn(
                  "completion-sync hook fired but WorkflowOrchestrationService is unavailable; "
                      + "skipping workflowRunId={}",
                  runId);
              return;
            }
            log.info("completion-sync hook fired workflowRunId={} (post-commit)", runId);
            try {
              orchestration.syncCompletionToLinear(runId);
            } catch (RuntimeException error) {
              // afterCommit cannot roll back the (already committed) Completed transition; the sync
              // is best-effort (AC4) so any escape is swallowed with a WARN.
              log.warn(
                  "completion-sync hook swallowed an error (completion intact) workflowRunId={} "
                      + "cause={}",
                  runId,
                  error.getClass().getSimpleName());
            }
          }
        });
  }

  /**
   * Story 3f-3 (AC6, Task 6) — when this transition commits a run into {@code Completed}, register
   * a post-commit {@link TransactionSynchronization} that releases any dependency-blocked
   * dependents (runs parked in {@code WaitingForDependencies} whose every prerequisite is now
   * {@code Completed}). The hook resolves {@link RunDependencyReleaseService} lazily and runs AFTER
   * commit, so the {@code Completed} transition is already durable and a release failure cannot
   * roll it back (best-effort, AC6). Unlike completion-sync this is NOT gated on a feature flag:
   * dependency release is core gating behavior.
   */
  private void registerDependencyReleaseHookIfApplicable(String runId, WorkflowState targetState) {
    if (targetState != WorkflowState.COMPLETED) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      log.warn(
          "dependency-release hook not registered (no active transaction synchronization) workflowRunId={}",
          runId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            RunDependencyReleaseService release = dependencyReleaseProvider.getIfAvailable();
            if (release == null) {
              log.warn(
                  "dependency-release hook fired but RunDependencyReleaseService is unavailable; "
                      + "skipping workflowRunId={}",
                  runId);
              return;
            }
            log.info("dependency-release hook fired completedRunId={} (post-commit)", runId);
            try {
              release.releaseDependentsOf(runId, null);
            } catch (RuntimeException error) {
              // afterCommit cannot roll back the (already committed) Completed transition; release
              // is best-effort (AC6) so any escape is swallowed with a WARN.
              log.warn(
                  "dependency-release hook swallowed an error (completion intact) completedRunId={} cause={}",
                  runId,
                  error.getClass().getSimpleName());
            }
          }
        });
  }

  private void doTransition(
      String runId,
      WorkflowState targetState,
      TransitionActor actor,
      String reason,
      String idempotencyKey,
      FailureCategory failureCategory,
      Map<String, Object> eventDetails) {
    var workflowRun =
        workflowRunReadPort.findByPublicId(runId).orElseThrow(() -> runNotFound(runId));
    if (workflowRun.archivedAt() != null) {
      throw archivedRunRejected(runId, targetState);
    }
    concurrencyProbe.afterRunLoaded(runId);

    WorkflowState priorState = workflowRun.currentState();
    transitionTable.assertTransitionAllowed(
        runId, priorState, targetState, failureCategory, reason);

    workflowRunStatePort.updateCurrentState(runId, targetState, workflowRun.requiredVersion());

    Map<String, Object> details = new LinkedHashMap<>(eventDetails);
    // Caller-supplied eventDetails apply first; the service-supplied idempotencyKey is
    // the canonical key and wins over any value already present in eventDetails.
    details.put("idempotencyKey", idempotencyKey);
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            runId,
            WorkflowEventType.WORKFLOW_STATE_CHANGED,
            priorState,
            targetState,
            actor.identity(),
            actor.type(),
            reason,
            failureCategory,
            targetState == WorkflowState.TAKEN_OVER || targetState == WorkflowState.RECONCILED,
            OffsetDateTime.now(ZoneOffset.UTC),
            details));
  }

  private DomainException runNotFound(String runId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", runId);
    return new DomainException(
        DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + runId, details);
  }

  private DomainException archivedRunRejected(String runId, WorkflowState targetState) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", runId);
    details.put("targetState", targetState.value());
    details.put("reason", "run_archived");
    return new DomainException(
        DomainErrorCode.ILLEGAL_TRANSITION,
        "Workflow run is archived and cannot be transitioned: " + runId,
        details);
  }

  private DomainException concurrentConflict(
      String runId, WorkflowState targetState, String idempotencyKey, Exception cause) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", runId);
    details.put("targetState", targetState.value());
    details.put("idempotencyKey", idempotencyKey);
    return new DomainException(
        DomainErrorCode.CONCURRENT_TRANSITION_CONFLICT,
        "Concurrent workflow transition conflict for run " + runId,
        details,
        cause);
  }

  public record TransitionActor(String identity, ActorType type) {

    public TransitionActor {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(type, "type");
      if (identity.isBlank()) {
        throw new IllegalArgumentException("identity must not be blank");
      }
    }
  }

  public interface WorkflowTransitionConcurrencyProbe {

    void afterRunLoaded(String runPublicId);

    static WorkflowTransitionConcurrencyProbe noop() {
      return runPublicId -> {};
    }
  }
}
