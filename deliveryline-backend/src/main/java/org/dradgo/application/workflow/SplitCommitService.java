package org.dradgo.application.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.integration.ticketsource.TicketSourceSubticketOutcome;
import org.dradgo.application.integration.ticketsource.TicketSourceSubticketService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.workflow.SplitCommitResult.SubtaskCommitOutcome;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.SplitProposalWritePort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3f-5 — commits an advisory split proposal (the back half of the split flow 3f-4 produced).
 *
 * <p><strong>Best-effort + NON-transactional (R1, the 3-18 trap verbatim).</strong> {@link #commit}
 * is deliberately NOT {@code @Transactional}: each per-subtask seam ({@code
 * workflowRunCreatePort.create}, {@code runDependencyService.declareDependencies}, the parent
 * {@code transition}) is itself {@code @Transactional(REQUIRED)}, so an ambient batch transaction
 * would let one subtask's rollback-marked failure poison the whole fan-out. With no ambient
 * transaction each unit opens its own physical transaction via a {@code REQUIRES_NEW} {@link
 * TransactionTemplate} (mirrors {@code WorkflowBatchSubmissionService.requiresNewTemplate}); one
 * subtask's failure rolls back only that subtask.
 *
 * <p><strong>Two passes (R2).</strong> {@code run_dependencies} FKs reference sibling child {@code
 * public_id}s, so all child runs are minted first (Pass A, building an {@code ordinal → childRunId}
 * map), then all dependency edges are declared and every child is gated-dispatched (Pass B). A
 * Pass-A failure simply drops that ordinal; a Pass-B edge referencing a dropped ordinal is skipped.
 *
 * <p><strong>Parent decomposition (R6).</strong> Only when ≥1 child was created, in one {@code
 * REQUIRES_NEW} transaction: CAS the proposal {@code open → approved} (a replay finds 0 and
 * short-circuits), transition the parent {@code WaitingForSpecApproval/WaitingForReview → Split}
 * (the 3f-2 into-edge), and append a separate {@code workflow.split} event carrying {@code
 * childRunIds}. Zero children ⇒ an explicit aborted result, parent + proposal untouched (R7).
 */
@Service
public class SplitCommitService {

  private static final Logger log = LoggerFactory.getLogger(SplitCommitService.class);

  private static final String FIELD_SEPARATOR = "\u0000";

  private final WorkflowRunReadPort workflowRunReadPort;
  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final SplitProposalReadPort splitProposalReadPort;
  private final SplitProposalWritePort splitProposalWritePort;
  private final TicketSourceSubticketService ticketSourceSubticketService;
  private final IntegrationLinkService integrationLinkService;
  private final WorkflowRunCreatePort workflowRunCreatePort;
  private final RunDependencyService runDependencyService;
  private final WorkflowTransitionService workflowTransitionService;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final IdempotencyService idempotencyService;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final TransactionTemplate independentTransactionTemplate;

  public SplitCommitService(
      WorkflowRunReadPort workflowRunReadPort,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      SplitProposalReadPort splitProposalReadPort,
      SplitProposalWritePort splitProposalWritePort,
      TicketSourceSubticketService ticketSourceSubticketService,
      IntegrationLinkService integrationLinkService,
      WorkflowRunCreatePort workflowRunCreatePort,
      RunDependencyService runDependencyService,
      WorkflowTransitionService workflowTransitionService,
      WorkflowEventWritePort workflowEventWritePort,
      IdempotencyService idempotencyService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      PlatformTransactionManager transactionManager) {
    this.workflowRunReadPort = workflowRunReadPort;
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
    this.splitProposalReadPort = splitProposalReadPort;
    this.splitProposalWritePort = splitProposalWritePort;
    this.ticketSourceSubticketService = ticketSourceSubticketService;
    this.integrationLinkService = integrationLinkService;
    this.workflowRunCreatePort = workflowRunCreatePort;
    this.runDependencyService = runDependencyService;
    this.workflowTransitionService = workflowTransitionService;
    this.workflowEventWritePort = workflowEventWritePort;
    this.idempotencyService = idempotencyService;
    this.idempotencyKeyValidator = idempotencyKeyValidator;
    this.independentTransactionTemplate = requiresNewTemplate(transactionManager);
  }

  /**
   * Commit the run's current open split proposal. Idempotent under {@code Idempotency-Key}: a
   * replayed approve neither double-creates children/sub-tickets nor double-decomposes the parent.
   */
  public SplitCommitResult commit(ApproveSplitCommand command) {
    String runId = PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
    idempotencyKeyValidator.requireValid(command.idempotencyKey());
    return commitInternal(command, runId);
  }

  private SplitCommitResult commitInternal(ApproveSplitCommand command, String runId) {
    // Resolve the proposal id up-front (any status) so a replay/reconcile keys on the same
    // per-proposal dedup key and short-circuits WITHOUT re-validating the gate (after decomposition
    // the parent is no longer at it).
    SplitProposalView latest =
        splitProposalReadPort.findLatestForRun(runId).orElseThrow(() -> noOpenProposal(runId));
    String proposalId = latest.publicId();

    // Idempotent reconcile (P5 / Edge-F2): if this proposal is already approved, OR the parent
    // already has children (a prior attempt minted them — under a different client key or
    // concurrently), do NOT re-create. Reconcile from durable state instead of double-fanning-out.
    List<WorkflowRunSnapshot> existingChildren = workflowRunReadPort.findByParentRunId(runId);
    if (SplitProposalView.STATUS_APPROVED.equals(latest.status()) || !existingChildren.isEmpty()) {
      return reconcile(command, runId, proposalId, latest.status(), existingChildren);
    }

    // First attempt — validate ALL preconditions BEFORE reserving so a precondition failure never
    // poisons the idempotency key (a clean retry stays possible — the FAILED-key wedge fix,
    // Edge-F2).
    WorkflowRunSnapshot run = requireRun(runId);
    requireGateState(runId, run.currentState());
    SplitProposalView proposal =
        splitProposalReadPort.findOpenForRun(runId).orElseThrow(() -> noOpenProposal(runId));
    // P0 — reject a cyclic dependency graph fail-fast (BEFORE Pass A) so no children are minted;
    // the
    // 3f-4 harvester filters self-edges/dangling/duplicates but NOT transitive cycles.
    requireAcyclicDependencies(runId, proposalId, proposal.dependencies());

    // P7 — claim the proposal on the DETERMINISTIC per-proposal key (AC6:
    // split-commit:<run>:<prop>)
    // so two concurrent approves under different client Idempotency-Keys serialize: the loser waits
    // on the in-flight reservation and replays the winner's result rather than double-fanning-out.
    String dedupKey = splitCommitKey(runId, proposalId);
    String fingerprint = fingerprintFor(runId, command.actorIdentity());
    IdempotencyService.ReservationOutcome reservation =
        independentTransactionTemplate.execute(
            ignored ->
                idempotencyService.checkAndReserve(
                    dedupKey,
                    ApproveSplitCommand.class.getSimpleName(),
                    command.actorIdentity(),
                    fingerprint));
    if (reservation != null
        && reservation.decision() == IdempotencyService.ReservationDecision.REPLAY) {
      String statusAfterRace =
          splitProposalReadPort.findLatestForRun(runId).map(SplitProposalView::status).orElse(null);
      return reconcile(
          command,
          runId,
          proposalId,
          statusAfterRace,
          workflowRunReadPort.findByParentRunId(runId));
    }

    try {
      SplitCommitResult result = process(command, runId, run, proposal);
      independentTransactionTemplate.executeWithoutResult(
          ignored ->
              idempotencyService.complete(dedupKey, runId, IdempotencyRecordStatus.COMPLETED));
      return result;
    } catch (RuntimeException error) {
      completeFailed(dedupKey, error);
      throw error;
    }
  }

  /**
   * Reconstruct the commit result from durable state for an idempotent replay / reconcile — the
   * fan-out already happened (same proposal already approved, or the parent already has children).
   * Re-creates nothing. The per-subtask created/internal split is not durably keyed to the parent
   * result, so each surviving child is reported as a created child (best-effort, deferred W1).
   */
  private SplitCommitResult reconcile(
      ApproveSplitCommand command,
      String runId,
      String proposalId,
      String proposalStatus,
      List<WorkflowRunSnapshot> existingChildren) {
    log.warn(
        "split commit idempotent replay workflowRunId={} idempotencyKey={}",
        runId,
        MdcKeys.sanitizeForLog(command.idempotencyKey()));
    List<String> childRunIds =
        existingChildren.stream().map(WorkflowRunSnapshot::publicId).toList();
    boolean decomposed =
        SplitProposalView.STATUS_APPROVED.equals(proposalStatus) && !childRunIds.isEmpty();
    List<SubtaskCommitOutcome> outcomes = new ArrayList<>(childRunIds.size());
    int ordinal = 1;
    for (String childRunId : childRunIds) {
      outcomes.add(SubtaskCommitOutcome.created(ordinal++, childRunId, null));
    }
    return new SplitCommitResult(
        runId,
        proposalId,
        decomposed,
        decomposed
            ? SplitCommitResult.OUTCOME_DECOMPOSED
            : SplitCommitResult.OUTCOME_ABORTED_NO_CHILDREN,
        childRunIds,
        outcomes);
  }

  private SplitCommitResult process(
      ApproveSplitCommand command,
      String runId,
      WorkflowRunSnapshot run,
      SplitProposalView proposal) {
    String priorCorrelation = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, command.correlationId());
    String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, runId);
    try {
      String proposalId = proposal.publicId();

      List<SplitSubtaskView> subtasks =
          proposal.subtasks().stream()
              .sorted(Comparator.comparingInt(SplitSubtaskView::ordinal))
              .toList();
      log.info(
          "split commit entry workflowRunId={} splitProposalId={} subtaskCount={} actorIdentity={}",
          runId,
          proposalId,
          subtasks.size(),
          MdcKeys.sanitizeForLog(command.actorIdentity()));

      Project project = projectRuntimeConfigResolver.resolveForRun(runId);
      String parentProjectId = project.publicId();
      TicketRef parentTicketRef = resolveParentTicketRef(runId);
      ActorContext actor =
          new ActorContext(command.actorIdentity(), command.actorType(), command.correlationId());

      // ---------- Pass A — mint child runs (sub-ticket or internal-only), building ordinal->child.
      Map<Integer, String> ordinalToChild = new LinkedHashMap<>();
      List<SubtaskCommitOutcome> outcomes = new ArrayList<>(subtasks.size());
      for (SplitSubtaskView subtask : subtasks) {
        outcomes.add(
            createChildForSubtask(
                command,
                runId,
                proposalId,
                parentProjectId,
                parentTicketRef,
                project,
                subtask,
                actor,
                ordinalToChild));
      }

      int createdCount = ordinalToChild.size();

      // ---------- Pass B — declare dependency edges (R4 direction) then gated-dispatch every
      // child.
      dispatchPassB(command, runId, proposalId, proposal.dependencies(), ordinalToChild);

      // ---------- Parent decomposition (R6) — only when ≥1 child was created.
      List<String> childRunIds = new ArrayList<>(ordinalToChild.values());
      boolean parentDecomposed = false;
      if (createdCount >= 1) {
        parentDecomposed = decomposeParent(command, run, proposalId, childRunIds);
      } else {
        log.warn(
            "split aborted no children parentRunId={} splitProposalId={} subtaskCount={}",
            runId,
            proposalId,
            subtasks.size());
      }

      log.info(
          "split commit finish workflowRunId={} splitProposalId={} createdCount={} parentDecomposed={}",
          runId,
          proposalId,
          createdCount,
          parentDecomposed);
      return new SplitCommitResult(
          runId,
          proposalId,
          parentDecomposed,
          parentDecomposed
              ? SplitCommitResult.OUTCOME_DECOMPOSED
              : SplitCommitResult.OUTCOME_ABORTED_NO_CHILDREN,
          childRunIds,
          outcomes);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelation);
    }
  }

  private SubtaskCommitOutcome createChildForSubtask(
      ApproveSplitCommand command,
      String runId,
      String proposalId,
      String parentProjectId,
      TicketRef parentTicketRef,
      Project project,
      SplitSubtaskView subtask,
      ActorContext actor,
      Map<Integer, String> ordinalToChild) {
    int ordinal = subtask.ordinal();
    try {
      // Sub-ticket creation is capability-gated and idempotent under the per-ordinal key (the
      // Linear
      // adapter's marker scan replays to the existing child sub-ticket). With no parent ticket ref
      // there is nothing to base a sub-ticket on, so the child links internal-only by definition.
      TicketSourceSubticketOutcome ticketOutcome;
      if (parentTicketRef != null) {
        SubticketDraft draft =
            new SubticketDraft(
                runId,
                proposalId,
                proposalId + "#" + ordinal,
                ordinal,
                subtask.title(),
                subtask.scope(),
                subticketKey(runId, proposalId, ordinal));
        ticketOutcome =
            ticketSourceSubticketService.createSubticketIfSupported(
                project, parentTicketRef, draft);
      } else {
        ticketOutcome = TicketSourceSubticketOutcome.noTicketSource();
      }

      boolean created = ticketOutcome.status() == TicketSourceSubticketOutcome.Status.CREATED;
      String childTicketRef =
          created ? ticketOutcome.result().orElseThrow().childRef().value() : null;

      // Mint the child run + establish its ticket linkage in ONE REQUIRES_NEW transaction so a link
      // failure rolls back only this child (best-effort isolation, R1).
      String childRunId =
          independentTransactionTemplate.execute(
              ignored -> {
                String childId = PublicIdPrefixes.WORKFLOW_RUN.next();
                workflowRunCreatePort.create(childId, WorkflowState.INBOX, parentProjectId, runId);
                if (created) {
                  integrationLinkService.linkSplitChildTicket(
                      childId, childTicketRef, subtask.title(), subtask.scope(), actor);
                } else if (parentTicketRef != null) {
                  integrationLinkService.linkSplitChildTicket(
                      childId, parentTicketRef.value(), subtask.title(), subtask.scope(), actor);
                }
                return childId;
              });

      ordinalToChild.put(ordinal, childRunId);
      if (created) {
        log.info(
            "split subtask created parentRunId={} splitProposalId={} ordinal={} childRunId={} childTicketRef={}",
            runId,
            proposalId,
            ordinal,
            childRunId,
            MdcKeys.sanitizeForLog(childTicketRef));
        return SubtaskCommitOutcome.created(ordinal, childRunId, childTicketRef);
      }
      log.info(
          "split subtask internal_only parentRunId={} splitProposalId={} ordinal={} childRunId={} ticketOutcome={}",
          runId,
          proposalId,
          ordinal,
          childRunId,
          ticketOutcome.status());
      return SubtaskCommitOutcome.internalOnly(ordinal, childRunId);
    } catch (RuntimeException error) {
      // Best-effort: one subtask's failure must not poison the rest (R1). Swallow + record failed.
      log.warn(
          "split subtask failed parentRunId={} splitProposalId={} ordinal={} cause={}",
          runId,
          proposalId,
          ordinal,
          error.getClass().getSimpleName());
      return SubtaskCommitOutcome.failed(ordinal, error.getClass().getSimpleName());
    }
  }

  private void dispatchPassB(
      ApproveSplitCommand command,
      String runId,
      String proposalId,
      List<SplitDependencyView> dependencies,
      Map<Integer, String> ordinalToChild) {
    // Map proposal edges {fromOrdinal,toOrdinal} to prerequisite ordinals per dependent ordinal.
    // The
    // schema + SplitDependencyView javadoc PIN the direction: fromOrdinal DEPENDS ON toOrdinal
    // (R4).
    Map<Integer, List<Integer>> prereqOrdinalsByOrdinal = new LinkedHashMap<>();
    for (SplitDependencyView edge : dependencies) {
      prereqOrdinalsByOrdinal
          .computeIfAbsent(edge.fromOrdinal(), key -> new ArrayList<>())
          .add(edge.toOrdinal());
    }
    // P4 — Pass B is itself TWO phases: declare ALL edges first, THEN dispatch ALL children. A
    // single
    // declare-then-dispatch loop could dispatch a low-ordinal prerequisite (synchronously advancing
    // it
    // past pre-execution) before a higher-ordinal dependent declared its edge, making the
    // dependent's
    // declareDependencies fail. P3 — each per-child step is best-effort: one child's failure is
    // logged
    // and skipped, never aborting the whole fan-out (mirrors Pass A's per-subtask isolation, R1).
    Set<String> edgeDeclarationFailed = new HashSet<>();
    for (Map.Entry<Integer, String> entry : ordinalToChild.entrySet()) {
      int ordinal = entry.getKey();
      String childId = entry.getValue();
      // R4 — fromOrdinal DEPENDS ON toOrdinal: the child at `ordinal` is the dependent, its
      // prerequisites are the children at the mapped toOrdinals (skipping any that failed Pass A).
      List<String> prereqChildIds = new ArrayList<>();
      for (int prereqOrdinal : prereqOrdinalsByOrdinal.getOrDefault(ordinal, List.of())) {
        String prereqChildId = ordinalToChild.get(prereqOrdinal);
        if (prereqChildId == null) {
          log.warn(
              "split dependency edge skipped (prerequisite subtask not created) parentRunId={} splitProposalId={} dependentOrdinal={} prerequisiteOrdinal={}",
              runId,
              proposalId,
              ordinal,
              prereqOrdinal);
          continue;
        }
        prereqChildIds.add(prereqChildId);
      }
      if (prereqChildIds.isEmpty()) {
        continue;
      }
      try {
        runDependencyService.declareDependencies(
            new DeclareRunDependenciesCommand(
                childId,
                prereqChildIds,
                command.actorIdentity(),
                command.actorType(),
                dependencyDeclareKey(runId, proposalId, ordinal),
                command.correlationId()));
      } catch (RuntimeException error) {
        // Best-effort: one edge's failure must not abort the fan-out. The dependent is left
        // un-dispatched below so it cannot run ahead of its (now un-wired) prerequisites (R4).
        edgeDeclarationFailed.add(childId);
        log.warn(
            "split dependency declaration failed parentRunId={} splitProposalId={} dependentOrdinal={} cause={}",
            runId,
            proposalId,
            ordinal,
            error.getClass().getSimpleName());
      }
    }

    for (Map.Entry<Integer, String> entry : ordinalToChild.entrySet()) {
      int ordinal = entry.getKey();
      String childId = entry.getValue();
      if (edgeDeclarationFailed.contains(childId)) {
        log.warn(
            "split child left undispatched (edge declaration failed) parentRunId={} splitProposalId={} ordinal={} childRunId={}",
            runId,
            proposalId,
            ordinal,
            childId);
        continue;
      }
      try {
        RunDependencyService.GatedDispatchOutcome outcome =
            runDependencyService.dispatchWhenUnblocked(childId, command.correlationId());
        log.info(
            "split child dispatch decision parentRunId={} splitProposalId={} ordinal={} childRunId={} outcome={}",
            runId,
            proposalId,
            ordinal,
            childId,
            outcome);
      } catch (RuntimeException error) {
        log.warn(
            "split child dispatch failed parentRunId={} splitProposalId={} ordinal={} childRunId={} cause={}",
            runId,
            proposalId,
            ordinal,
            childId,
            error.getClass().getSimpleName());
      }
    }
  }

  private TicketRef resolveParentTicketRef(String runId) {
    // The parent run's source ticket is its active integration link (the `linear` variant by
    // convention); absent for a run with no linked ticket (then every subtask is internal-only with
    // no ref). Deliberately the NON-LOCKING lookup: commit() runs with no ambient transaction (R1),
    // so a SELECT … FOR UPDATE variant would throw "No active transaction"; this is a plain read.
    Optional<IntegrationLink> parentLink =
        integrationLinkService.findActiveLinkByWorkflowRun(runId);
    return parentLink.map(link -> TicketRef.of(link.externalRef())).orElse(null);
  }

  private boolean decomposeParent(
      ApproveSplitCommand command,
      WorkflowRunSnapshot run,
      String proposalId,
      List<String> childRunIds) {
    String runId = run.publicId();
    WorkflowState gateState = run.currentState();
    return Boolean.TRUE.equals(
        independentTransactionTemplate.execute(
            ignored -> {
              // (i) CAS the proposal open -> approved. A replay finds 0 (already approved) and
              // short-circuits the transition + event (defense-in-depth replay guard, R6).
              int flipped = splitProposalWritePort.approveOpenForRun(runId);
              if (flipped == 0) {
                log.info(
                    "split decomposition replay (proposal already approved) parentRunId={} splitProposalId={}",
                    runId,
                    proposalId);
                return Boolean.TRUE;
              }
              // (ii) transition the parent into the non-terminal Split state (3f-2 into-edge). A
              // replay that somehow reaches here finds the parent already Split and the table
              // rejects
              // the SPLIT -> SPLIT edge — swallow as a no-op.
              try {
                workflowTransitionService.transition(
                    runId,
                    WorkflowState.SPLIT,
                    new TransitionActor(command.actorIdentity(), command.actorType()),
                    "approve_split",
                    splitCommitKey(runId, proposalId));
              } catch (DomainException illegal) {
                if (illegal.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
                  // P6 — only treat the rejected into-edge as an idempotent no-op if the parent is
                  // ACTUALLY already in Split (a genuine replay). Otherwise the parent left its
                  // gate
                  // for some other state mid-fan-out and reporting decomposed=true would be a lie.
                  WorkflowState live =
                      workflowRunReadPort
                          .findByPublicId(runId)
                          .map(WorkflowRunSnapshot::currentState)
                          .orElse(null);
                  if (live == WorkflowState.SPLIT) {
                    log.info(
                        "split decomposition transition no-op (parent already Split) parentRunId={} splitProposalId={}",
                        runId,
                        proposalId);
                    return Boolean.TRUE;
                  }
                  log.error(
                      "split decomposition rejected: parent left its gate during fan-out (liveState={}) parentRunId={} splitProposalId={}",
                      live == null ? "null" : live.value(),
                      runId,
                      proposalId);
                  throw illegal;
                }
                throw illegal;
              }
              // (iii) append the workflow.split event carrying the allow-listed childRunIds.
              Map<String, Object> details = new LinkedHashMap<>();
              details.put(WorkflowEventDetailKeys.CHILD_RUN_IDS, List.copyOf(childRunIds));
              workflowEventWritePort.append(
                  new WorkflowEventRecord(
                      PublicIdPrefixes.WORKFLOW_EVENT.next(),
                      runId,
                      WorkflowEventType.SPLIT,
                      gateState,
                      WorkflowState.SPLIT,
                      command.actorIdentity(),
                      command.actorType(),
                      "approve_split",
                      null,
                      false,
                      OffsetDateTime.now(ZoneOffset.UTC),
                      details));
              log.info(
                  "split parent decomposed parentRunId={} splitProposalId={} childCount={}",
                  runId,
                  proposalId,
                  childRunIds.size());
              return Boolean.TRUE;
            }));
  }

  /**
   * P0 — reject a split proposal whose dependency edges form a transitive cycle, BEFORE Pass A
   * mints any children. {@code fromOrdinal} depends on {@code toOrdinal} (R4); a cycle would
   * otherwise only surface mid-Pass-B (when {@code declareDependencies} throws {@code
   * RUN_DEPENDENCY_CYCLE}), after children were already minted and dispatched.
   */
  private void requireAcyclicDependencies(
      String runId, String proposalId, List<SplitDependencyView> dependencies) {
    if (dependencies == null || dependencies.isEmpty()) {
      return;
    }
    Map<Integer, List<Integer>> adjacency = new LinkedHashMap<>();
    for (SplitDependencyView edge : dependencies) {
      adjacency.computeIfAbsent(edge.fromOrdinal(), key -> new ArrayList<>()).add(edge.toOrdinal());
    }
    Set<Integer> visiting = new HashSet<>();
    Set<Integer> done = new HashSet<>();
    for (Integer node : adjacency.keySet()) {
      if (hasCycle(node, adjacency, visiting, done)) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", runId);
        details.put("splitProposalId", proposalId);
        details.put("reason", "dependency_cycle");
        log.warn(
            "split rejected dependency cycle parentRunId={} splitProposalId={}", runId, proposalId);
        throw new DomainException(
            DomainErrorCode.RUN_DEPENDENCY_CYCLE,
            "Split proposal dependency edges form a cycle; cannot approve",
            details);
      }
    }
  }

  private boolean hasCycle(
      Integer node,
      Map<Integer, List<Integer>> adjacency,
      Set<Integer> visiting,
      Set<Integer> done) {
    if (done.contains(node)) {
      return false;
    }
    if (!visiting.add(node)) {
      return true;
    }
    for (Integer next : adjacency.getOrDefault(node, List.of())) {
      if (hasCycle(next, adjacency, visiting, done)) {
        return true;
      }
    }
    visiting.remove(node);
    done.add(node);
    return false;
  }

  private void completeFailed(String idempotencyKey, RuntimeException original) {
    try {
      independentTransactionTemplate.executeWithoutResult(
          ignored ->
              idempotencyService.complete(idempotencyKey, null, IdempotencyRecordStatus.FAILED));
    } catch (RuntimeException completionError) {
      original.addSuppressed(completionError);
    }
    log.error(
        "split commit failed idempotencyKey={} cause={}",
        MdcKeys.sanitizeForLog(idempotencyKey),
        original.getClass().getSimpleName());
  }

  private void requireGateState(String runId, WorkflowState state) {
    if (state != WorkflowState.WAITING_FOR_SPEC_APPROVAL
        && state != WorkflowState.WAITING_FOR_REVIEW) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("runId", runId);
      details.put("reason", "not_at_split_gate");
      details.put("state", state == null ? "null" : state.value());
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "approve_split is only available at WaitingForSpecApproval or WaitingForReview (was "
              + (state == null ? "null" : state.value())
              + ")",
          details);
    }
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

  private DomainException noOpenProposal(String runId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", runId);
    details.put("reason", "no_open_split_proposal");
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "No open split proposal to approve for run " + runId,
        details);
  }

  static String subticketKey(String runId, String proposalId, int ordinal) {
    return "split:" + runId + ":" + proposalId + ":" + ordinal;
  }

  static String splitCommitKey(String runId, String proposalId) {
    return "split-commit:" + runId + ":" + proposalId;
  }

  static String dependencyDeclareKey(String runId, String proposalId, int ordinal) {
    return "split-dep:" + runId + ":" + proposalId + ":" + ordinal;
  }

  private String fingerprintFor(String runId, String actorIdentity) {
    MessageDigest digest = newDigest();
    digest.update(runId.getBytes(StandardCharsets.UTF_8));
    digest.update(FIELD_SEPARATOR.getBytes(StandardCharsets.UTF_8));
    digest.update((actorIdentity == null ? "" : actorIdentity).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  private MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available in JDK 21", exception);
    }
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }
}
