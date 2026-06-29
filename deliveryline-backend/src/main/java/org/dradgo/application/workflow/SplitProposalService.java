package org.dradgo.application.workflow;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.queue.QueuedRunnerExecution;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.SplitProposalCommandSet.DeclineSplitCommand;
import org.dradgo.application.workflow.SplitProposalCommandSet.ReproposeSplitCommand;
import org.dradgo.application.workflow.SplitProposalCommandSet.RequestSplitCommand;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.SplitProposalWritePort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3f-4 — the advisory split-proposal channel. Drives the three operator actions ({@code
 * request_split} / {@code repropose_split} / {@code continue_as_single}) at the spec/review gate.
 * It produces and iterates an advisory LLM proposal ONLY: it creates no child runs, no sub-tickets,
 * no dependency edges, and never moves the parent out of its gate or into {@code Split} (all that
 * is 3f-5). The parent stays parked at {@code WaitingForSpecApproval} / {@code WaitingForReview}
 * throughout.
 *
 * <p>The proposal rides the 3d-2 {@code RunnerStage.REVIEW} DISPATCH substrate (R1) but is NOT a
 * verdict: the reviewer execution is distinguished as split-mode by its idempotency-key prefix
 * {@code split-proposal:<runId>:<loopCount>} — durable on {@code runner_executions}, readable at
 * both compose ({@code ContextBundleService.createForReview}) and harvest ({@code
 * RunnerBroker.onResult} → {@code SplitProposalHarvester}). No new runner stage, workflow state,
 * event type, or transition edge (R2).
 *
 * <p>Reviewer binding gates GENERATION, not the gate (R5): an unbound project degrades {@code
 * request}/{@code repropose} to "unavailable" without enqueuing — the gate is never blocked.
 */
@Service
public class SplitProposalService {

  private static final Logger log = LoggerFactory.getLogger(SplitProposalService.class);

  static final String DISPATCH_KEY_PREFIX = "split-proposal:";
  private static final List<RunnerExecutionStatus> ACTIVE_STATUSES =
      List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING);

  private final WorkflowRunReadPort workflowRunReadPort;
  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final WorkflowOrchestrationService orchestrationService;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final SplitProposalReadPort splitProposalReadPort;
  private final SplitProposalWritePort splitProposalWritePort;
  private final WorkflowRunRejectionLoopPort workflowRunLoopPort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final SplitProposalEscalationThresholdProvider escalationThresholdProvider;
  private final RedactionPolicyService redactionPolicyService;
  private final ComplexTicketFlowProperties complexTicketFlowProperties;

  public SplitProposalService(
      WorkflowRunReadPort workflowRunReadPort,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      WorkflowOrchestrationService orchestrationService,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      SplitProposalReadPort splitProposalReadPort,
      SplitProposalWritePort splitProposalWritePort,
      WorkflowRunRejectionLoopPort workflowRunLoopPort,
      WorkflowEventWritePort workflowEventWritePort,
      SplitProposalEscalationThresholdProvider escalationThresholdProvider,
      RedactionPolicyService redactionPolicyService,
      ComplexTicketFlowProperties complexTicketFlowProperties) {
    this.workflowRunReadPort = workflowRunReadPort;
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
    this.orchestrationService = orchestrationService;
    this.runnerExecutionRecordPort = runnerExecutionRecordPort;
    this.splitProposalReadPort = splitProposalReadPort;
    this.splitProposalWritePort = splitProposalWritePort;
    this.workflowRunLoopPort = workflowRunLoopPort;
    this.workflowEventWritePort = workflowEventWritePort;
    this.escalationThresholdProvider = escalationThresholdProvider;
    this.redactionPolicyService = redactionPolicyService;
    this.complexTicketFlowProperties = complexTicketFlowProperties;
  }

  /** The dispatch idempotency key marking a split-mode REVIEW execution. */
  public static String splitDispatchKey(String workflowRunId, int loopCount) {
    return DISPATCH_KEY_PREFIX + workflowRunId + ":" + loopCount;
  }

  /** True when the execution's idempotency key marks it as a split-mode REVIEW dispatch. */
  public static boolean isSplitDispatch(String idempotencyKey) {
    return idempotencyKey != null && idempotencyKey.startsWith(DISPATCH_KEY_PREFIX);
  }

  @Transactional
  public SplitProposalStatusView request(RequestSplitCommand command) {
    log.info(
        "split request entry workflowRunId={} actorIdentity={} idempotencyKey={}",
        command.workflowRunId(),
        command.actorIdentity(),
        command.idempotencyKey());
    WorkflowRunSnapshot run = requireGateRun(command.workflowRunId());

    // Story 3f-7 (AC5) — recursive-split depth cap. Evaluated at the TOP of request(), BEFORE the
    // reviewer-bound check and BEFORE any enqueue/LLM dispatch (3f-4 AC1), by walking parentRunId
    // from this run to the lineage root (root = depth 0). A run already at or beyond the configured
    // cap is refused HERE with SPLIT_DEPTH_LIMIT_EXCEEDED unless the operator supplied the
    // allowDeepSplit override. The rejection stays at the top (error precedence preserved), but the
    // governed-history override event is NOT appended yet: it is recorded only on the proceed path
    // below, so a replay (already-open proposal), a degrade (reviewer unbound), or an in-flight
    // no-op never pollutes the audit with a duplicate/phantom override (review 2026-06-29,
    // Decision 1). A null result means within the cap; non-null carries the bypass to record.
    DeepSplitOverride deepSplitOverride = evaluateSplitDepthCap(command, run);

    // Idempotent: a replayed request with an already-open proposal is a no-op (the action is only
    // surfaced when none is open).
    Optional<SplitProposalView> open =
        splitProposalReadPort.findOpenForRun(command.workflowRunId());
    if (open.isPresent()) {
      log.info(
          "split request no-op (open proposal already exists) workflowRunId={} splitProposalId={}",
          command.workflowRunId(),
          open.get().publicId());
      return SplitProposalStatusView.available(open.get());
    }

    if (!reviewerBound(command.workflowRunId())) {
      log.warn(
          "split request degraded (no reviewer model bound) workflowRunId={} — gate not blocked",
          command.workflowRunId());
      return SplitProposalStatusView.unavailable(
          splitProposalReadPort.currentSplitProposalLoopCount(command.workflowRunId()));
    }

    // Idempotent during the pending window: a replayed request while a split dispatch is still in
    // flight must NOT enqueue a second dispatch (it would also double-bump the loop counter).
    if (hasActiveSplitDispatch(command.workflowRunId())) {
      int activeLoop = splitProposalReadPort.currentSplitProposalLoopCount(command.workflowRunId());
      log.info(
          "split request no-op (dispatch already in flight) workflowRunId={} loopCount={}",
          command.workflowRunId(),
          activeLoop);
      return SplitProposalStatusView.pending(activeLoop);
    }

    // Bump the loop counter so EACH attempt — including a re-request after a decline or a degraded
    // attempt — gets a DISTINCT dispatch idempotency key. A reused key replays to a no-op at the
    // worker, stranding the operator at a dead button (review 2026-06-29, Decision 1). Escalation
    // stays a re-propose concept: request does NOT call maybeFlipEscalation.
    int loopCount =
        workflowRunLoopPort.incrementAndReadSplitProposalLoopCount(command.workflowRunId());
    // Record the deep-split override ONLY now that the request actually proceeds to enqueue — same
    // @Transactional as the dispatch, so it commits or rolls back atomically with the split attempt
    // (review 2026-06-29, Decision 1).
    if (deepSplitOverride != null) {
      recordDeepSplitOverride(command, deepSplitOverride.depth(), deepSplitOverride.maxDepth());
    }
    enqueueSplitDispatch(command.workflowRunId(), loopCount, command.correlationId());
    log.info(
        "split request enqueued workflowRunId={} loopCount={} state={}",
        command.workflowRunId(),
        loopCount,
        SplitProposalStatusView.STATE_PENDING);
    return SplitProposalStatusView.pending(loopCount);
  }

  @Transactional
  public SplitProposalStatusView repropose(ReproposeSplitCommand command) {
    log.info(
        "split repropose entry workflowRunId={} actorIdentity={} idempotencyKey={}",
        command.workflowRunId(),
        command.actorIdentity(),
        command.idempotencyKey());
    WorkflowRunSnapshot run = requireGateRun(command.workflowRunId());
    if (command.feedbackText() == null || command.feedbackText().isBlank()) {
      throw invalidPayload(command.workflowRunId(), "feedbackText must not be blank");
    }

    // Re-propose iterates an EXISTING open proposal: reject when none is open so a stale UI /
    // direct
    // API call cannot silently climb the loop counter toward the escalation threshold with no
    // proposal ever reviewed (review 2026-06-29).
    if (splitProposalReadPort.findOpenForRun(command.workflowRunId()).isEmpty()) {
      throw invalidPayload(command.workflowRunId(), "no open split proposal to re-propose");
    }

    if (!reviewerBound(command.workflowRunId())) {
      log.warn(
          "split repropose degraded (no reviewer model bound) workflowRunId={} — gate not blocked",
          command.workflowRunId());
      return SplitProposalStatusView.unavailable(
          splitProposalReadPort.currentSplitProposalLoopCount(command.workflowRunId()));
    }

    // Supersede the prior open proposal BEFORE enqueuing the new dispatch — supersede-then-insert
    // in
    // ONE transaction so the partial unique index never sees two open rows
    // ([[caught-idempotency-conflict-poisons-shared-tx]]).
    int superseded = splitProposalWritePort.supersedeOpenForRun(command.workflowRunId());

    // Bump the loop counter for a DISTINCT dispatch idempotency key per attempt (R3).
    int newLoopCount =
        workflowRunLoopPort.incrementAndReadSplitProposalLoopCount(command.workflowRunId());

    QueuedRunnerExecution queued =
        enqueueSplitDispatch(command.workflowRunId(), newLoopCount, command.correlationId());

    // Materialize the operator feedback BY REFERENCE (R3): redact, then persist keyed by the
    // reviewer execution that carries the dispatch. The compose reads it back to add a
    // {referenceId, kind:'split.feedback'} priorFeedbackReferences entry — never inlined.
    String redactedFeedback =
        redactionPolicyService
            .redact(command.feedbackText(), DataClassification.SHAREABLE_REDACTED.value())
            .sanitizedText();
    splitProposalWritePort.insertFeedback(
        PublicIdPrefixes.SPLIT_PROPOSAL_FEEDBACK.next(),
        queued.runnerExecutionPublicId(),
        redactedFeedback);

    maybeFlipEscalation(command, newLoopCount);

    log.info(
        "split repropose enqueued workflowRunId={} loopCount={} supersededOpen={} reviewerExec={}",
        command.workflowRunId(),
        newLoopCount,
        superseded,
        queued.runnerExecutionPublicId());
    return SplitProposalStatusView.pending(newLoopCount);
  }

  @Transactional
  public SplitProposalStatusView decline(DeclineSplitCommand command) {
    log.info(
        "split decline entry workflowRunId={} actorIdentity={} idempotencyKey={}",
        command.workflowRunId(),
        command.actorIdentity(),
        command.idempotencyKey());
    requireGateRun(command.workflowRunId());
    // P3 (review 2026-06-29): you cannot decline a proposal that does not exist yet. The open row
    // is created at HARVEST, not at request — so a decline issued WHILE a split dispatch is in
    // flight (no open proposal) would silently dismiss 0 rows and then be overwritten by the
    // harvester's insert (the rejected proposal "resurrects"). Reject instead: the operator waits
    // for the proposal, then declines it. (The UI never offers decline mid-dispatch post-P2; this
    // closes the out-of-band API path.)
    if (splitProposalReadPort.findOpenForRun(command.workflowRunId()).isEmpty()
        && hasActiveSplitDispatch(command.workflowRunId())) {
      throw invalidPayload(
          command.workflowRunId(),
          "a split proposal is still being generated; wait for it before declining");
    }
    int dismissed = splitProposalWritePort.dismissOpenForRun(command.workflowRunId());
    log.info(
        "split decline done workflowRunId={} dismissedOpen={}", command.workflowRunId(), dismissed);
    return SplitProposalStatusView.none(
        splitProposalReadPort.currentSplitProposalLoopCount(command.workflowRunId()));
  }

  /** GET /split-proposal — the current proposal + state + loopCount (no mutation). */
  public SplitProposalStatusView proposalView(String workflowRunId) {
    if (workflowRunId != null) {
      PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    }
    if (workflowRunReadPort.findByPublicId(workflowRunId).isEmpty()) {
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunId,
          Map.of("runId", workflowRunId));
    }
    int loopCount = splitProposalReadPort.currentSplitProposalLoopCount(workflowRunId);
    Optional<SplitProposalView> open = splitProposalReadPort.findOpenForRun(workflowRunId);
    if (open.isPresent()) {
      return SplitProposalStatusView.available(open.get());
    }
    if (hasActiveSplitDispatch(workflowRunId)) {
      return SplitProposalStatusView.pending(loopCount);
    }
    if (!reviewerBound(workflowRunId)) {
      return SplitProposalStatusView.unavailable(loopCount);
    }
    return SplitProposalStatusView.none(loopCount);
  }

  private QueuedRunnerExecution enqueueSplitDispatch(
      String workflowRunId, int loopCount, String correlationId) {
    // Route through the orchestration service: it is the single allowed enqueue seam (ArchUnit
    // only_orchestration_recovery_and_worker_pool_may_enqueue). The split-mode marker is the
    // dispatch idempotency key, minted here.
    return orchestrationService.enqueueSplitProposalDispatch(
        workflowRunId, splitDispatchKey(workflowRunId, loopCount), correlationId);
  }

  private boolean reviewerBound(String workflowRunId) {
    try {
      return projectRuntimeConfigResolver.hasReviewerBinding(workflowRunId);
    } catch (RuntimeException resolveError) {
      log.warn(
          "split reviewer-binding resolution failed workflowRunId={} cause={} — treating as unbound",
          workflowRunId,
          resolveError.toString());
      return false;
    }
  }

  private boolean hasActiveSplitDispatch(String workflowRunId) {
    for (RunnerExecutionSnapshot row :
        runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, ACTIVE_STATUSES)) {
      if (row.stage() == RunnerStage.REVIEW && isSplitDispatch(row.idempotencyKey())) {
        return true;
      }
    }
    return false;
  }

  private void maybeFlipEscalation(ReproposeSplitCommand command, int newLoopCount) {
    int threshold = escalationThresholdProvider.get();
    if (newLoopCount < threshold) {
      return;
    }
    if (workflowRunLoopPort.isEscalationMarkerSet(command.workflowRunId())) {
      return;
    }
    int flipped = workflowRunLoopPort.markEscalationOnce(command.workflowRunId());
    if (flipped != 1) {
      // Concurrent flip already emitted the escalation event (shared marker idempotency).
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("threshold", threshold);
    details.put("loopCount", newLoopCount);
    details.put("loop", "split_proposal");
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            command.workflowRunId(),
            WorkflowEventType.ESCALATION_REQUIRED,
            null,
            null,
            command.actorIdentity(),
            command.actorType(),
            "split-proposal re-propose loop threshold exceeded",
            null,
            false,
            OffsetDateTime.now(),
            details));
    log.warn(
        "split escalation marker raised workflowRunId={} loopCount={} threshold={}",
        command.workflowRunId(),
        newLoopCount,
        threshold);
  }

  /** An at-or-beyond-cap split the operator explicitly authorized via {@code allowDeepSplit}. */
  private record DeepSplitOverride(int depth, int maxDepth) {}

  /**
   * Story 3f-7 (AC5) — evaluate the recursive-split depth cap. Throws {@code
   * SPLIT_DEPTH_LIMIT_EXCEEDED} when the run is at or beyond {@code max-split-depth} without the
   * {@code allowDeepSplit} override (rejection stays at the top of {@code request()} so error
   * precedence is unchanged). Returns a {@link DeepSplitOverride} to be recorded in the governed
   * history by the caller — but only once the request actually proceeds to enqueue, so a replay,
   * degrade, or in-flight no-op never appends a duplicate/phantom override event (review
   * 2026-06-29, Decision 1). Returns {@code null} when the run is within the cap.
   */
  private DeepSplitOverride evaluateSplitDepthCap(
      RequestSplitCommand command, WorkflowRunSnapshot run) {
    int maxDepth = complexTicketFlowProperties.maxSplitDepth();
    int depth = computeSplitDepth(run, maxDepth);
    if (depth < maxDepth) {
      return null;
    }
    if (!command.allowDeepSplit()) {
      log.warn(
          "split refused (depth limit) workflowRunId={} depth={} max={}",
          command.workflowRunId(),
          depth,
          maxDepth);
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("runId", command.workflowRunId());
      details.put("currentDepth", depth);
      details.put("maxDepth", maxDepth);
      details.put("reason", "depth_limit_exceeded");
      throw new DomainException(
          DomainErrorCode.SPLIT_DEPTH_LIMIT_EXCEEDED,
          "Split depth "
              + depth
              + " is at or beyond the configured maximum "
              + maxDepth
              + "; supply allowDeepSplit to override",
          details);
    }
    return new DeepSplitOverride(depth, maxDepth);
  }

  /**
   * Compute the run's split depth = number of ancestors reached by walking {@code parentRunId}
   * (root run = depth 0). Non-locking reads (request() runs in a normal REQUIRED tx). The walk is
   * bounded to {@code maxDepth + 1} hops so a malformed/cyclic lineage chain cannot spin — that is
   * already past the cap, so reporting the bound is sufficient for the rejection decision.
   */
  private int computeSplitDepth(WorkflowRunSnapshot run, int maxDepth) {
    int depth = 0;
    String parentId = run.parentRunId();
    int safety = maxDepth + 1;
    while (parentId != null && safety-- > 0) {
      depth++;
      Optional<WorkflowRunSnapshot> parent = workflowRunReadPort.findByPublicId(parentId);
      if (parent.isEmpty()) {
        break;
      }
      parentId = parent.get().parentRunId();
    }
    return depth;
  }

  /**
   * Record an operator's deliberate bypass of the split depth cap as a governed-history event
   * (interventionMarker = true, no state change) so the audit reflects who overrode the safety cap.
   */
  private void recordDeepSplitOverride(RequestSplitCommand command, int depth, int maxDepth) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(WorkflowEventDetailKeys.DEEP_SPLIT_OVERRIDE, true);
    details.put(WorkflowEventDetailKeys.SPLIT_DEPTH, depth);
    details.put(WorkflowEventDetailKeys.MAX_SPLIT_DEPTH, maxDepth);
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            command.workflowRunId(),
            WorkflowEventType.SPLIT_DEPTH_OVERRIDE,
            null,
            null,
            command.actorIdentity(),
            command.actorType(),
            "deep_split_override",
            null,
            true,
            OffsetDateTime.now(),
            details));
    log.warn(
        "deep-split override applied workflowRunId={} depth={} max={} actorIdentity={}",
        command.workflowRunId(),
        depth,
        maxDepth,
        command.actorIdentity());
  }

  private WorkflowRunSnapshot requireGateRun(String workflowRunId) {
    if (workflowRunId != null) {
      PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    }
    WorkflowRunSnapshot run =
        workflowRunReadPort
            .findByPublicId(workflowRunId)
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.RUN_NOT_FOUND,
                        "Workflow run not found: " + workflowRunId,
                        Map.of("runId", workflowRunId)));
    WorkflowState state = run.currentState();
    if (state != WorkflowState.WAITING_FOR_SPEC_APPROVAL
        && state != WorkflowState.WAITING_FOR_REVIEW) {
      throw invalidPayload(
          workflowRunId,
          "split actions are only available at WaitingForSpecApproval or WaitingForReview (was "
              + (state == null ? "null" : state.value())
              + ")");
    }
    return run;
  }

  private DomainException invalidPayload(String workflowRunId, String message) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunId);
    details.put("reason", message);
    return new DomainException(DomainErrorCode.INVALID_COMMAND_PAYLOAD, message, details);
  }
}
