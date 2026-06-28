package org.dradgo.application.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.RunDependencyPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns run-dependency declaration rules and the gated-dispatch decision (story 3f-3, FR71).
 * Persistence only stores edges ({@link RunDependencyPort}); all preconditions, cycle policy, and
 * the {@code WaitingForDependencies} parking transition live here.
 *
 * <p>A dependent may be declared only while it is pre-execution ({@code Inbox} or {@code
 * WaitingForDependencies}); a prerequisite must not yet have started or finished ({@code
 * Executing}, {@code Completed}, {@code Failed}, {@code TakenOver}, {@code Reconciled}, {@code
 * Paused}, or {@code Split} are all rejected). Cycles are rejected before insertion with {@link
 * DomainErrorCode#RUN_DEPENDENCY_CYCLE}.
 */
@Service
public class RunDependencyService {

  private static final Logger log = LoggerFactory.getLogger(RunDependencyService.class);

  // AC3 — a prerequisite must be pre-execution at declaration time. These states mean it has
  // already started, finished, been intervened on, or decomposed, so a new edge to it is rejected.
  private static final java.util.Set<WorkflowState> DISALLOWED_PREREQUISITE_STATES =
      java.util.EnumSet.of(
          WorkflowState.EXECUTING,
          WorkflowState.COMPLETED,
          WorkflowState.FAILED,
          WorkflowState.TAKEN_OVER,
          WorkflowState.RECONCILED,
          WorkflowState.PAUSED,
          WorkflowState.SPLIT);

  // AC3 — a dependent may only gain dependencies while it is still pre-dispatch.
  private static final java.util.Set<WorkflowState> ALLOWED_DEPENDENT_STATES =
      java.util.EnumSet.of(WorkflowState.INBOX, WorkflowState.WAITING_FOR_DEPENDENCIES);

  private final RunDependencyPort runDependencyPort;
  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowTransitionService workflowTransitionService;
  private final WorkflowOrchestrationService workflowOrchestrationService;

  public RunDependencyService(
      RunDependencyPort runDependencyPort,
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowTransitionService workflowTransitionService,
      WorkflowOrchestrationService workflowOrchestrationService) {
    this.runDependencyPort = runDependencyPort;
    this.workflowRunReadPort = workflowRunReadPort;
    this.workflowTransitionService = workflowTransitionService;
    this.workflowOrchestrationService = workflowOrchestrationService;
  }

  /**
   * Declare that {@code command.runId()} depends on each prerequisite in {@code
   * command.dependsOnRunIds()}. Validates ids, state preconditions, and acyclicity; persists the
   * edges idempotently; then parks the dependent in {@code WaitingForDependencies} if it has unmet
   * prerequisites. Returns the resulting graph view.
   */
  @Transactional
  public RunDependencyGraphView declareDependencies(DeclareRunDependenciesCommand command) {
    String runId = PublicIdPrefixes.require(command.runId(), PublicIdPrefixes.WORKFLOW_RUN);
    List<String> prerequisites = canonicalizePrerequisites(runId, command.dependsOnRunIds());
    log.info(
        "run-dependency declaration received workflowRunId={} dependsOnCount={} actorIdentity={}",
        runId,
        prerequisites.size(),
        command.actorIdentity());

    // Serialize against concurrent declarations and the release resolver before reading any state
    // (review 3f-3 D1/D2): makes the check-then-insert cycle guard atomic and closes the
    // declaration-vs-completion window that could otherwise strand the dependent.
    runDependencyPort.lockDependencyGraph();

    WorkflowRunSnapshot dependent = requireRun(runId);
    if (dependent.archivedAt() != null) {
      throw rejected(
          runId, "run_archived", "Cannot declare dependencies for an archived run: " + runId);
    }
    if (!ALLOWED_DEPENDENT_STATES.contains(dependent.currentState())) {
      throw rejected(
          runId,
          "dependent_not_pre_execution",
          "Run "
              + runId
              + " is "
              + dependent.currentState().value()
              + "; dependencies can only be declared while it is Inbox or WaitingForDependencies");
    }

    for (String dependsOnRunId : prerequisites) {
      WorkflowRunSnapshot prerequisite = requireRun(dependsOnRunId);
      if (DISALLOWED_PREREQUISITE_STATES.contains(prerequisite.currentState())) {
        throw rejected(
            runId,
            "prerequisite_not_pre_execution",
            "Prerequisite "
                + dependsOnRunId
                + " is "
                + prerequisite.currentState().value()
                + "; it has already started or finished and cannot become a new dependency");
      }
      if (runDependencyPort.wouldCreateCycle(runId, dependsOnRunId)) {
        log.warn(
            "run-dependency declaration rejected (cycle) workflowRunId={} dependsOnRunId={}",
            runId,
            dependsOnRunId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", runId);
        details.put("dependsOnRunId", dependsOnRunId);
        details.put("reason", "cycle_detected");
        throw new DomainException(
            DomainErrorCode.RUN_DEPENDENCY_CYCLE,
            "Declaring that "
                + runId
                + " depends on "
                + dependsOnRunId
                + " would introduce a cycle",
            details);
      }
    }

    runDependencyPort.addDependencies(runId, prerequisites);

    List<BlockedDependencyView> blockedOn = runDependencyPort.findBlockedOn(runId);
    if (!blockedOn.isEmpty() && dependent.currentState() == WorkflowState.INBOX) {
      // AC5 — a newly-declared run with unmet prerequisites parks instead of dispatching. A run
      // already in WaitingForDependencies stays parked (no duplicate transition on replay).
      parkInWaitingForDependencies(runId, command, blockedOn.size());
    } else {
      log.info(
          "run-dependency declaration left run unparked workflowRunId={} currentState={} blockedOnCount={}",
          runId,
          dependent.currentState().value(),
          blockedOn.size());
    }
    log.info(
        "run-dependency declaration persisted workflowRunId={} prerequisiteCount={} blockedOnCount={}",
        runId,
        prerequisites.size(),
        blockedOn.size());
    return runDependencyPort.graphView(runId);
  }

  /**
   * Gated-dispatch seam (story 3f-3 Task 5; consumed by 3f-5 split commit). Given a run that
   * already has its dependency edges declared, dispatch spec generation when it has no unmet
   * prerequisites, otherwise park it in {@code WaitingForDependencies}. Does not change top-level
   * submit semantics for no-dependency runs — callers use this only for dependency-gated runs.
   */
  @Transactional
  public GatedDispatchOutcome dispatchWhenUnblocked(String runId, String correlationId) {
    PublicIdPrefixes.require(runId, PublicIdPrefixes.WORKFLOW_RUN);
    // Same advisory lock as declaration so a prerequisite added concurrently cannot slip between
    // the blockedOn read and the dispatch/park decision (review 3f-3 D1/D2).
    runDependencyPort.lockDependencyGraph();
    WorkflowRunSnapshot run = requireRun(runId);
    // Gated dispatch only ever acts on a still-pre-execution run. A run that has already been
    // dispatched, finished, or otherwise moved past Inbox/WaitingForDependencies is left untouched
    // so we never attempt an illegal <state> -> Investigating transition (review 3f-3 P1).
    if (!ALLOWED_DEPENDENT_STATES.contains(run.currentState())) {
      log.info(
          "gated dispatch skipped (run not pre-execution) workflowRunId={} currentState={} correlationId={}",
          runId,
          run.currentState().value(),
          correlationId);
      return GatedDispatchOutcome.SKIPPED;
    }
    List<BlockedDependencyView> blockedOn = runDependencyPort.findBlockedOn(runId);
    if (blockedOn.isEmpty()) {
      log.info(
          "gated dispatch proceeding (no unmet prerequisites) workflowRunId={} correlationId={}",
          runId,
          correlationId);
      workflowOrchestrationService.dispatchSpecGeneration(runId, correlationId);
      return GatedDispatchOutcome.DISPATCHED;
    }
    if (run.currentState() == WorkflowState.INBOX) {
      parkInWaitingForDependencies(
          runId,
          new DeclareRunDependenciesCommand(
              runId, List.of(), "system", ActorType.SYSTEM, parkKey(runId), correlationId),
          blockedOn.size());
    }
    log.info(
        "gated dispatch parked (unmet prerequisites) workflowRunId={} blockedOnCount={} correlationId={}",
        runId,
        blockedOn.size(),
        correlationId);
    return GatedDispatchOutcome.PARKED;
  }

  /** Read-model assembly for the {@code GET .../dependencies} endpoint and workflow detail. */
  public RunDependencyGraphView graphView(String runId) {
    PublicIdPrefixes.require(runId, PublicIdPrefixes.WORKFLOW_RUN);
    requireRun(runId);
    return runDependencyPort.graphView(runId);
  }

  private void parkInWaitingForDependencies(
      String runId, DeclareRunDependenciesCommand command, int blockedOnCount) {
    workflowTransitionService.transition(
        runId,
        WorkflowState.WAITING_FOR_DEPENDENCIES,
        new TransitionActor(
            command.actorIdentity() == null ? "system" : command.actorIdentity(),
            command.actorType() == null ? ActorType.SYSTEM : command.actorType()),
        "waiting_for_dependencies",
        parkKey(runId));
    log.info(
        "run parked in WaitingForDependencies workflowRunId={} blockedOnCount={}",
        runId,
        blockedOnCount);
  }

  private static String parkKey(String runId) {
    // Deterministic so a declaration replay or repeated gated-dispatch does not append a duplicate
    // Inbox -> WaitingForDependencies transition. Parking happens at most once per run.
    return "wait-deps:" + runId;
  }

  private List<String> canonicalizePrerequisites(String runId, List<String> rawDependsOnRunIds) {
    if (rawDependsOnRunIds == null || rawDependsOnRunIds.isEmpty()) {
      throw rejected(runId, "empty_dependency_list", "At least one dependsOnRunId is required");
    }
    LinkedHashSet<String> deduped = new LinkedHashSet<>();
    for (String raw : rawDependsOnRunIds) {
      if (raw == null || raw.isBlank()) {
        throw rejected(runId, "blank_dependency_id", "dependsOnRunIds must not contain blanks");
      }
      String dependsOnRunId = raw.trim();
      PublicIdPrefixes.require(dependsOnRunId, PublicIdPrefixes.WORKFLOW_RUN);
      if (dependsOnRunId.equals(runId)) {
        // Self-edge: rejected before any DB write (the ck_run_dependencies_not_self CHECK is the
        // backstop). Distinct from the transitive RUN_DEPENDENCY_CYCLE case.
        throw rejected(runId, "self_dependency", "A run cannot depend on itself: " + runId);
      }
      deduped.add(dependsOnRunId);
    }
    return new ArrayList<>(deduped);
  }

  private WorkflowRunSnapshot requireRun(String runId) {
    return workflowRunReadPort
        .findByPublicId(runId)
        .orElseThrow(
            () ->
                new DomainException(
                    DomainErrorCode.RUN_NOT_FOUND,
                    "Workflow run not found: " + runId,
                    Map.of("runId", runId)));
  }

  private static DomainException rejected(String runId, String reason, String message) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", runId);
    details.put("reason", reason);
    return new DomainException(DomainErrorCode.INVALID_COMMAND_PAYLOAD, message, details);
  }

  /** Outcome of {@link #dispatchWhenUnblocked(String, String)}. */
  public enum GatedDispatchOutcome {
    DISPATCHED,
    PARKED,
    /** The run was past pre-execution (already dispatched/finished); gated dispatch did nothing. */
    SKIPPED
  }
}
