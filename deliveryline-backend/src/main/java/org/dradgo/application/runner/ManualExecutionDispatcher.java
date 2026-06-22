package org.dradgo.application.runner;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3d-3 (AC3/AC6, ADR 0024) — parks a runner step dispatched under the {@code manual} runner
 * kind. Instead of enqueuing container work (the {@link
 * org.dradgo.application.runner.queue.RunnerExecutionQueue} path), it composes + writes the same
 * runner-contracts input bundle, records an {@code awaiting_manual} {@code runner_executions} row,
 * appends a {@code manual.executionRequested} event, and transitions the run to {@code
 * WaitingForManualExecution} — no container launched, no queue row, no worker slot occupied (AC6).
 *
 * <p><strong>Why a dedicated service (R2 / Open Decision #2):</strong> both dispatch chokepoints
 * ({@code WorkflowOrchestrationService.enqueueDispatch} + {@code RecoveryService}) inject this
 * exactly as they inject {@link org.dradgo.application.runner.queue.RunnerExecutionQueue}. Story
 * 3.17b deliberately dropped {@code WorkflowOrchestrationService}'s {@code RunnerBroker} dependency
 * to break the orchestration↔broker cycle; this dispatcher (not orchestration) holds the one-way
 * {@code dispatcher → broker} edge for bundle composition, so the cycle stays broken.
 *
 * <p><strong>Transaction posture:</strong> {@code @Transactional} (REQUIRED). When the
 * orchestration chokepoint calls it the park joins the active submit/approve tx (the row insert +
 * bundle write + event + transition commit or roll back together — Task 3). When {@code
 * RecoveryService} calls it (it enqueues OUTSIDE a tx by design) the park opens its own tx. A
 * compose/write failure throws a {@code DomainException} and unwinds the whole park — no orphaned
 * {@code awaiting_manual} row, no half-event ([[story-3-17b-queue-activation-seams]]: the caller-tx
 * park is simpler than the worker path, which has no ambient tx).
 *
 * <p>{@code @Component} (not {@code @Service}) keeps the {@code *Dispatcher} name clean under the
 * {@code APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES} ArchUnit rule, mirroring {@code
 * RunnerExecutionQueue} / {@code RunnerBroker}.
 */
@Component
public class ManualExecutionDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ManualExecutionDispatcher.class);

  private final RunnerExecutionRecordPort recordPort;
  private final RunnerExecutionEventPort eventPort;
  private final RunnerBroker runnerBroker;
  private final RunnerScratchStore scratchStore;
  private final WorkflowTransitionService workflowTransitionService;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public ManualExecutionDispatcher(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerBroker runnerBroker,
      RunnerScratchStore scratchStore,
      WorkflowTransitionService workflowTransitionService) {
    this(
        recordPort,
        eventPort,
        runnerBroker,
        scratchStore,
        workflowTransitionService,
        Clock.systemUTC());
  }

  // Package-private clock-injecting overload for deterministic tests (no Clock bean is published).
  ManualExecutionDispatcher(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerBroker runnerBroker,
      RunnerScratchStore scratchStore,
      WorkflowTransitionService workflowTransitionService,
      Clock clock) {
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.eventPort = Objects.requireNonNull(eventPort, "eventPort");
    this.runnerBroker = Objects.requireNonNull(runnerBroker, "runnerBroker");
    this.scratchStore = Objects.requireNonNull(scratchStore, "scratchStore");
    this.workflowTransitionService =
        Objects.requireNonNull(workflowTransitionService, "workflowTransitionService");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Park the run for manual execution. Ordered so a failure rolls the whole submit/approve back:
   * mint {@code rex_} id → insert {@code awaiting_manual} row → compose + write the bundle (eager)
   * → append {@code manual.executionRequested} → transition to {@code WaitingForManualExecution}.
   *
   * @return a {@link RunnerDispatchResult.Parked} carrying the {@code awaiting_manual} handle + the
   *     {@code manual.executionRequested} event id
   */
  @Transactional
  public RunnerDispatchResult park(String workflowRunId, RunnerStage stage, ActorContext actor) {
    return park(workflowRunId, stage, null, actor);
  }

  @Transactional
  public RunnerDispatchResult park(
      String workflowRunId, RunnerStage stage, String idempotencyKey, ActorContext actor) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(actor, "actor");

    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      String runnerExecutionId = PublicIdPrefixes.RUNNER_EXECUTION.next();
      log.info(
          "recording awaiting_manual runner execution {} for run {}",
          runnerExecutionId,
          workflowRunId);
      RunnerExecutionSnapshot snapshot =
          recordPort.insertAwaitingManualAllocated(
              runnerExecutionId, workflowRunId, stage, idempotencyKey);
      int contextBundleVersion = snapshot.contextBundleVersion();

      // Eager compose + write in the caller's tx — a clone/compose failure throws and unwinds the
      // whole park (Task 3). No adapter dispatch: a `manual` kind never launches a container.
      runnerBroker.composeAndWriteManualBundle(
          workflowRunId, stage, runnerExecutionId, contextBundleVersion, actor);

      String manualEventPublicId;
      try {
        manualEventPublicId = appendManualRequestedEvent(workflowRunId, runnerExecutionId, actor);

        workflowTransitionService.transition(
            workflowRunId,
            WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
            new TransitionActor(actor.actorIdentity(), actor.actorType()),
            "manual_execution_requested",
            "manual-park:" + runnerExecutionId);
      } catch (RuntimeException | Error parkFailure) {
        scratchStore.deleteContextBundle(runnerExecutionId);
        throw parkFailure;
      }

      log.info(
          "parked run for manual execution workflowRunId={} runnerExecutionId={} stage={} manualEventId={}",
          workflowRunId,
          runnerExecutionId,
          stage.value(),
          manualEventPublicId);

      RunnerExecutionHandle handle =
          new RunnerExecutionHandle(
              snapshot.publicId(),
              workflowRunId,
              stage,
              RunnerExecutionStatus.AWAITING_MANUAL,
              snapshot.timeoutAt());
      return new RunnerDispatchResult.Parked(handle, manualEventPublicId);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private String appendManualRequestedEvent(
      String workflowRunId, String runnerExecutionId, ActorContext actor) {
    Map<String, Object> details = new LinkedHashMap<>();
    // Existing allow-listed detail keys only (no new WorkflowEventDetailKeys — R3/AC4). Stage is
    // recoverable from the runner_executions row; never log/emit bundle bytes or secrets.
    details.put(WorkflowEventDetailKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    details.put(WorkflowEventDetailKeys.WORKFLOW_RUN_ID, workflowRunId);
    details.put(WorkflowEventDetailKeys.RUNNER_KIND, RunnerKind.MANUAL.value());
    return eventPort.append(
        workflowRunId,
        WorkflowEventType.MANUAL_EXECUTION_REQUESTED,
        actor,
        "manual_execution_requested",
        null,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
  }
}
