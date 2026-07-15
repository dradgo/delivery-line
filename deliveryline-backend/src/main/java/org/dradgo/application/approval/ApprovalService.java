package org.dradgo.application.approval;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.approval.spi.ApprovalWritePort;
import org.dradgo.application.approval.spi.ApprovalWritePort.NewApproval;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactService;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.SpecRejectionEscalationThresholdProvider;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical executor for the {@link org.dradgo.domain.registry.AllowedAction#APPROVE_SPEC} and
 * {@link org.dradgo.domain.registry.AllowedAction#REJECT_SPEC} actions (stories 2.9 + 2.10).
 *
 * <p>Orchestrates the four-step approval pipeline inside the caller's transaction (the outer {@link
 * org.dradgo.application.workflow.WorkflowCommandService#approveSpec @Transactional} boundary):
 *
 * <ol>
 *   <li>Version-binding check ({@code APPROVAL_VERSION_MISMATCH}) — surfaced BEFORE the eligibility
 *       check so a reviewer with a stale version learns about it first.
 *   <li>Eligibility check via {@link ArtifactService#isApprovalEligible} ({@code
 *       ARTIFACT_PAYLOAD_UNAVAILABLE} on false).
 *   <li>Insert the approval row via {@link ApprovalWritePort}.
 *   <li>Append the {@code approval.approved} event (separate from the {@code workflow.stateChanged}
 *       event that the transition service appends on its own), then call {@link
 *       WorkflowTransitionService#transition} for the {@code WaitingForSpecApproval → Executing}
 *       hop.
 * </ol>
 *
 * <p><strong>No {@code @Transactional} annotation on the public method.</strong> Adding one
 * (especially with {@code REQUIRES_NEW} propagation) would create a nested boundary that would NOT
 * roll the approval row back when the surrounding transition fails — trap T4. The transactionality
 * integration test {@code ApprovalServiceTransactionalityTest} pins the rollback shape.
 */
@Service
public class ApprovalService {

  private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

  private final ArtifactRecordPort artifactRecordPort;
  private final ArtifactService artifactService;
  private final ApprovalWritePort approvalWritePort;
  // Story 4.7 (Reconciliation 2) — resolve the current approved apr before invalidating it.
  private final ApprovalReadPort approvalReadPort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final WorkflowTransitionService workflowTransitionService;
  private final ApprovalVersionBinder approvalVersionBinder;
  private final WorkflowRunRejectionLoopPort workflowRunRejectionLoopPort;
  private final SpecRejectionEscalationThresholdProvider escalationThresholdProvider;
  // Story 3a-1 (Task 5 / AC5) — re-dispatch the spec runner after a rejection re-enters
  // Investigating.
  private final org.dradgo.application.workflow.WorkflowOrchestrationService
      workflowOrchestrationService;
  private final Clock clock;

  @Autowired
  public ApprovalService(
      ArtifactRecordPort artifactRecordPort,
      ArtifactService artifactService,
      ApprovalWritePort approvalWritePort,
      ApprovalReadPort approvalReadPort,
      WorkflowEventWritePort workflowEventWritePort,
      WorkflowTransitionService workflowTransitionService,
      ApprovalVersionBinder approvalVersionBinder,
      WorkflowRunRejectionLoopPort workflowRunRejectionLoopPort,
      SpecRejectionEscalationThresholdProvider escalationThresholdProvider,
      org.dradgo.application.workflow.WorkflowOrchestrationService workflowOrchestrationService) {
    this(
        artifactRecordPort,
        artifactService,
        approvalWritePort,
        approvalReadPort,
        workflowEventWritePort,
        workflowTransitionService,
        approvalVersionBinder,
        workflowRunRejectionLoopPort,
        escalationThresholdProvider,
        workflowOrchestrationService,
        Clock.systemUTC());
  }

  // Visible-for-tests constructor: lets unit tests inject a fixed Clock so decidedAt assertions
  // are deterministic without resorting to time-windowed greater-than checks.
  ApprovalService(
      ArtifactRecordPort artifactRecordPort,
      ArtifactService artifactService,
      ApprovalWritePort approvalWritePort,
      ApprovalReadPort approvalReadPort,
      WorkflowEventWritePort workflowEventWritePort,
      WorkflowTransitionService workflowTransitionService,
      ApprovalVersionBinder approvalVersionBinder,
      WorkflowRunRejectionLoopPort workflowRunRejectionLoopPort,
      SpecRejectionEscalationThresholdProvider escalationThresholdProvider,
      org.dradgo.application.workflow.WorkflowOrchestrationService workflowOrchestrationService,
      Clock clock) {
    this.artifactRecordPort = artifactRecordPort;
    this.artifactService = artifactService;
    this.approvalWritePort = approvalWritePort;
    this.approvalReadPort = approvalReadPort;
    this.workflowEventWritePort = workflowEventWritePort;
    this.workflowTransitionService = workflowTransitionService;
    this.approvalVersionBinder = approvalVersionBinder;
    this.workflowRunRejectionLoopPort = workflowRunRejectionLoopPort;
    this.escalationThresholdProvider = escalationThresholdProvider;
    this.workflowOrchestrationService = workflowOrchestrationService;
    this.clock = clock;
  }

  /**
   * Trap T4 / D3 pin: {@code Propagation.MANDATORY} guarantees this method ALWAYS executes inside
   * the outer {@link
   * org.dradgo.application.workflow.WorkflowCommandService#approveSpec @Transactional} boundary. A
   * direct invocation without an outer transaction fails fast with {@code
   * IllegalTransactionStateException} instead of silently committing the approval row and event
   * then rolling back only the transition. Joining the outer transaction is the contract; starting
   * a new one (or running with no transaction) is a programming error.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ApprovalResult approveSpec(ApproveSpecCommand command) {
    PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(command.artifactId(), PublicIdPrefixes.ARTIFACT);

    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    String priorArtifactId = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, command.artifactId());
    try {
      log.info(
          "approveSpec entry workflowRunId={} artifactId={} expectedArtifactVersion={} expectedContextBundleVersion={} reviewerRole={} actorIdentity={} actorType={}",
          command.workflowRunId(),
          command.artifactId(),
          command.artifactVersion(),
          command.contextVersion(),
          command.reviewerRole(),
          command.actorIdentity(),
          command.actorType().value());

      ArtifactRecordSnapshot artifact =
          artifactRecordPort
              .findByPublicId(command.artifactId())
              .orElseThrow(() -> artifactNotFound(command));

      // Review batch 1 P1: defense-in-depth — refuse to approve a non-SPEC artifact under the
      // spec-approval path. The composite FK on the approvals table does not enforce artifact_type;
      // without this guard a caller could approve an implementationPlan / prOutput artifact and
      // silently advance the run to EXECUTING.
      if (artifact.artifactType() != ArtifactType.SPEC) {
        throw artifactTypeInvalid(command, artifact);
      }

      // Review batch 1 P2: defense-in-depth — refuse to approve an artifact whose owning workflow
      // run does not match the command's workflowRunId. Composite FKs are independent (artifact_id
      // and workflow_run_id are separately FK-checked); without this guard a caller could approve
      // a foreign run's artifact and trigger the wrong run's transition.
      if (!artifact.workflowRunId().equals(command.workflowRunId())) {
        throw artifactRunMismatch(command, artifact);
      }

      // AC4: version-binding check FIRST (trap T3). Stale artifact version OR stale context bundle
      // version is a reviewer error that must surface before any payload-eligibility detail.
      int currentArtifactVersion = artifact.version();
      int currentContextBundleVersion =
          approvalVersionBinder.resolveCurrentContextBundleVersion(command.artifactId());
      if (!approvalVersionBinder.versionsMatch(
          command.artifactVersion(),
          currentArtifactVersion,
          command.contextVersion(),
          currentContextBundleVersion)) {
        throw versionMismatch(command, currentArtifactVersion, currentContextBundleVersion);
      }

      // AC5: eligibility check (payload bytes match the persisted checksum, etc.).
      if (!artifactService.isApprovalEligible(command.artifactId())) {
        throw artifactPayloadUnavailable(command);
      }

      // AC3: persist the approval row.
      OffsetDateTime decidedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
      String approvalPublicId = PublicIdPrefixes.APPROVAL.next();
      String priorApprovalId = MdcKeys.beginScope(MdcKeys.APPROVAL_ID, approvalPublicId);
      try {
        ApprovalSnapshot persisted =
            approvalWritePort.insert(
                new NewApproval(
                    approvalPublicId,
                    command.workflowRunId(),
                    command.artifactId(),
                    command.artifactVersion(),
                    command.contextVersion(),
                    command.actorIdentity(),
                    command.actorType(),
                    command.reviewerRole(),
                    ApprovalSnapshot.DECISION_APPROVED,
                    command.reason(),
                    null,
                    decidedAt,
                    command.idempotencyKey()));

        Map<String, Object> approvalEventDetails = approvalEventDetails(command, persisted);

        // AC6: append the approval.approved event INSIDE the surrounding transaction.
        workflowEventWritePort.append(
            new WorkflowEventRecord(
                PublicIdPrefixes.WORKFLOW_EVENT.next(),
                command.workflowRunId(),
                WorkflowEventType.APPROVAL_APPROVED,
                null,
                null,
                command.actorIdentity(),
                command.actorType(),
                "specification approved",
                null,
                false,
                decidedAt,
                approvalEventDetails));

        // AC6: WaitingForSpecApproval -> Executing. The transition service appends the
        // workflow.stateChanged event on its own; ApprovalService MUST NOT append a second one.
        log.info(
            "approveSpec transition workflowRunId={} from=WaitingForSpecApproval to=Executing",
            command.workflowRunId());
        workflowTransitionService.transition(
            command.workflowRunId(),
            WorkflowState.EXECUTING,
            new TransitionActor(command.actorIdentity(), command.actorType()),
            "approve specification",
            command.idempotencyKey(),
            transitionEventDetails(command, persisted));

        // Story 3.11 (AC1 / Task 3 / Trap T3) — dispatch the implementation-plan runner AFTER the
        // WaitingForSpecApproval->Executing transition, symmetric to rejectSpec's
        // retrySpecGeneration call. This runs inside approveSpec's MANDATORY transaction (the outer
        // WorkflowCommandService.approveSpec tx), so a dispatch failure rolls back the approval row
        // + event + transition (all-or-nothing). dispatchPlanGeneration NEVER re-transitions (T1):
        // the run is already Executing. No-op when plan-stage.auto-dispatch=false (the shared test
        // profile), so the fast tier's approveSpec tests stay byte-identical to today.
        workflowOrchestrationService.dispatchPlanGeneration(
            command.workflowRunId(), normalizeOptional(command.correlationId()));

        log.info(
            "approveSpec success approvalId={} workflowRunId={} artifactId={} artifactVersion={} contextBundleVersion={} reviewerRole={} resultingState={}",
            persisted.publicId(),
            persisted.workflowRunId(),
            persisted.artifactId(),
            persisted.artifactVersion(),
            persisted.contextBundleVersion(),
            persisted.reviewerRole(),
            WorkflowState.EXECUTING.value());

        return new ApprovalResult(
            persisted.publicId(),
            persisted.workflowRunId(),
            persisted.artifactId(),
            persisted.artifactVersion(),
            persisted.contextBundleVersion(),
            persisted.reviewerRole(),
            persisted.decidedAt(),
            WorkflowState.EXECUTING,
            normalizeOptional(command.correlationId()));
      } catch (DomainException domainError) {
        if (domainError.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
          // Trap T6: propagating ILLEGAL_TRANSITION rolls back the outer transaction so the
          // just-inserted approval row + appended event disappear. Logged at WARN here so the
          // signal is visible without the caller assuming an unexpected failure.
          log.warn(
              "approveSpec rejected ILLEGAL_TRANSITION approvalId={} workflowRunId={} cause={}",
              approvalPublicId,
              command.workflowRunId(),
              domainError.getMessage());
        }
        throw domainError;
      } finally {
        MdcKeys.endScope(MdcKeys.APPROVAL_ID, priorApprovalId);
      }
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactId);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  /**
   * Story 2.10: spec rejection writer. Mirrors {@link #approveSpec} but: (a) decision is {@code
   * rejected} with a structured {@link org.dradgo.domain.registry.RejectionTaxonomy} tag (FR9 /
   * AR34a); (b) the run's {@code spec_rejection_loop_count} is atomically incremented and the
   * {@code escalation_marker_set} flips once per run when the counter crosses the configured
   * threshold (FR13 — exposes unresolved loops without terminating the workflow); (c) transition
   * lands in {@link WorkflowState#INVESTIGATING} (not EXECUTING); (d) per OQ-3, eligibility is
   * <strong>not</strong> checked — rejecting a stale-but-unavailable artifact is a valid PM
   * decision. {@code Propagation.MANDATORY} is identical to approveSpec — direct invocation outside
   * the outer {@code WorkflowCommandService.rejectSpec @Transactional} fails fast.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ApprovalResult rejectSpec(RejectSpecCommand command) {
    PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(command.artifactId(), PublicIdPrefixes.ARTIFACT);

    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    String priorArtifactId = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, command.artifactId());
    try {
      log.info(
          "rejectSpec entry workflowRunId={} artifactId={} expectedArtifactVersion={} expectedContextBundleVersion={} reviewerRole={} taggedFeedback={} actorIdentity={} actorType={}",
          command.workflowRunId(),
          command.artifactId(),
          command.artifactVersion(),
          command.contextVersion(),
          command.reviewerRole(),
          command.taggedFeedback().value(),
          command.actorIdentity(),
          command.actorType().value());

      ArtifactRecordSnapshot artifact =
          artifactRecordPort
              .findByPublicId(command.artifactId())
              .orElseThrow(() -> artifactNotFoundReject(command));

      // Defense-in-depth: refuse to reject a non-SPEC artifact under the spec-reject path
      // (symmetric to approveSpec — review-batch-1 patch).
      if (artifact.artifactType() != ArtifactType.SPEC) {
        throw artifactTypeInvalidReject(command, artifact);
      }
      // Defense-in-depth: refuse to reject another run's artifact (symmetric to approveSpec).
      if (!artifact.workflowRunId().equals(command.workflowRunId())) {
        throw artifactRunMismatchReject(command, artifact);
      }

      // AC3: version-binding BEFORE any other write (trap T3 / mirror approveSpec). Per OQ-3 we
      // intentionally do NOT run isApprovalEligible here — rejecting a stale-but-unavailable
      // artifact is a valid PM decision (the runner may have crashed mid-write; the reviewer is
      // explicitly rejecting the partial output).
      int currentArtifactVersion = artifact.version();
      int currentContextBundleVersion =
          approvalVersionBinder.resolveCurrentContextBundleVersion(command.artifactId());
      if (!approvalVersionBinder.versionsMatch(
          command.artifactVersion(),
          currentArtifactVersion,
          command.contextVersion(),
          currentContextBundleVersion)) {
        throw versionMismatchReject(command, currentArtifactVersion, currentContextBundleVersion);
      }

      // Role-scoping (Decision D4): a product spec rejection must carry a product-taxonomy value.
      // The V13 migration widened the shared rejection-taxonomy DB CHECK to the 8-value union, so
      // the database no longer rejects a developer value on a spec rejection — enforce the product
      // subset here, symmetric to TechnicalApprovalService.rejectImplementation's developer-subset
      // guard (code-review finding 2026-06-14).
      if (!command.taggedFeedback().isProductValue()) {
        throw nonProductTaxonomy(command);
      }

      // AC2: persist the rejection row (decision=rejected + rejection_taxonomy populated).
      OffsetDateTime decidedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
      String approvalPublicId = PublicIdPrefixes.APPROVAL.next();
      String priorApprovalId = MdcKeys.beginScope(MdcKeys.APPROVAL_ID, approvalPublicId);
      try {
        ApprovalSnapshot persisted =
            approvalWritePort.insert(
                new NewApproval(
                    approvalPublicId,
                    command.workflowRunId(),
                    command.artifactId(),
                    command.artifactVersion(),
                    command.contextVersion(),
                    command.actorIdentity(),
                    command.actorType(),
                    command.reviewerRole(),
                    ApprovalSnapshot.DECISION_REJECTED,
                    command.reasonText(),
                    command.taggedFeedback().value(),
                    decidedAt,
                    command.idempotencyKey()));

        // AC4: atomically increment the run's spec_rejection_loop_count and read the new value
        // (the persistence adapter clears the persistence context after the @Modifying UPDATE).
        int newLoopCount =
            workflowRunRejectionLoopPort.incrementAndReadLoopCount(command.workflowRunId());

        // AC4: append the approval.rejected event INSIDE the same transaction.
        workflowEventWritePort.append(
            new WorkflowEventRecord(
                PublicIdPrefixes.WORKFLOW_EVENT.next(),
                command.workflowRunId(),
                WorkflowEventType.APPROVAL_REJECTED,
                null,
                null,
                command.actorIdentity(),
                command.actorType(),
                "specification rejected",
                null,
                false,
                decidedAt,
                rejectEventDetails(command, persisted, newLoopCount)));

        // AC5: escalation marker — flip false->true at most once per run, emit
        // escalation.required exactly once on the flip (trap T5). Short-circuit the UPDATE when
        // the marker is already set so we don't waste a JDBC round-trip per subsequent rejection.
        int threshold = escalationThresholdProvider.get();
        boolean escalationMarkerNow = false;
        if (newLoopCount >= threshold) {
          if (workflowRunRejectionLoopPort.isEscalationMarkerSet(command.workflowRunId())) {
            // Marker already set on a prior rejection in this loop; counter still advances but no
            // duplicate escalation event (AC5 idempotency).
            escalationMarkerNow = true;
          } else {
            int flipped = workflowRunRejectionLoopPort.markEscalationOnce(command.workflowRunId());
            if (flipped == 1) {
              escalationMarkerNow = true;
              workflowEventWritePort.append(
                  new WorkflowEventRecord(
                      PublicIdPrefixes.WORKFLOW_EVENT.next(),
                      command.workflowRunId(),
                      WorkflowEventType.ESCALATION_REQUIRED,
                      null,
                      null,
                      command.actorIdentity(),
                      command.actorType(),
                      "spec rejection loop threshold exceeded",
                      null,
                      false,
                      decidedAt,
                      escalationEventDetails(command, threshold, newLoopCount)));
              log.warn(
                  "rejectSpec escalation marker raised workflowRunId={} loopCount={} threshold={}",
                  command.workflowRunId(),
                  newLoopCount,
                  threshold);
            } else {
              // Race: another transaction flipped the marker between our isEscalationMarkerSet
              // check and our markEscalationOnce call. Treat as "already set" — the other tx
              // already emitted the escalation event (AC5 idempotency holds at the DB level).
              escalationMarkerNow = true;
              log.debug(
                  "rejectSpec escalation marker concurrent-flip workflowRunId={} loopCount={} threshold={}",
                  command.workflowRunId(),
                  newLoopCount,
                  threshold);
            }
          }
        }

        // AC4: WaitingForSpecApproval -> Investigating. The transition service appends
        // workflow.stateChanged on its own (do NOT append a second one here).
        log.info(
            "rejectSpec transition workflowRunId={} from=WaitingForSpecApproval to=Investigating",
            command.workflowRunId());
        workflowTransitionService.transition(
            command.workflowRunId(),
            WorkflowState.INVESTIGATING,
            new TransitionActor(command.actorIdentity(), command.actorType()),
            "reject specification",
            command.idempotencyKey(),
            transitionEventDetailsReject(command, persisted, newLoopCount, escalationMarkerNow));

        // Story 3a-1 (AC5 / Task 5 / Trap T3) — re-dispatch the spec runner AFTER the
        // WaitingForSpecApproval->Investigating transition. This runs inside rejectSpec's MANDATORY
        // transaction, so a dispatch failure rolls back the rejection row + counter + transition
        // (all-or-nothing). retrySpecGeneration re-dispatches ONLY — it never re-transitions (T8):
        // the run is already Investigating and its bumped spec_rejection_loop_count makes the
        // idempotency key distinct from the prior attempt. No-op when
        // spec-stage.auto-dispatch=false.
        workflowOrchestrationService.retrySpecGeneration(
            command.workflowRunId(), normalizeOptional(command.correlationId()));

        log.info(
            "rejectSpec success approvalId={} workflowRunId={} artifactId={} artifactVersion={} contextBundleVersion={} reviewerRole={} taggedFeedback={} loopCount={} escalationMarker={} resultingState={}",
            persisted.publicId(),
            persisted.workflowRunId(),
            persisted.artifactId(),
            persisted.artifactVersion(),
            persisted.contextBundleVersion(),
            persisted.reviewerRole(),
            command.taggedFeedback().value(),
            newLoopCount,
            escalationMarkerNow,
            WorkflowState.INVESTIGATING.value());

        return new ApprovalResult(
            persisted.publicId(),
            persisted.workflowRunId(),
            persisted.artifactId(),
            persisted.artifactVersion(),
            persisted.contextBundleVersion(),
            persisted.reviewerRole(),
            persisted.decidedAt(),
            WorkflowState.INVESTIGATING,
            normalizeOptional(command.correlationId()));
      } catch (DomainException domainError) {
        if (domainError.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
          // Mirror approveSpec trap T6 logging — propagating ILLEGAL_TRANSITION rolls back the
          // outer transaction so the approvals row, the events, and the counter increment all
          // disappear together.
          log.warn(
              "rejectSpec rejected ILLEGAL_TRANSITION approvalId={} workflowRunId={} cause={}",
              approvalPublicId,
              command.workflowRunId(),
              domainError.getMessage());
        }
        throw domainError;
      } finally {
        MdcKeys.endScope(MdcKeys.APPROVAL_ID, priorApprovalId);
      }
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactId);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  /**
   * Story 4.7 (AC7 / Reconciliation 2) — invalidate the run's current approved artifact of the
   * given type (rerun-from-step supersession). Reads the current approved apr via {@link
   * ApprovalReadPort#findLatestApprovedForArtifactLineage} and, when present, flips its {@code
   * invalidated_at}/{@code invalidated_reason} so {@code getCurrentApprovedSpec} returns null until
   * the re-run stage produces a new artifact version that is re-approved. Keeps the approval WRITE
   * inside the approval package (write-boundary spirit, mirror 4.6's IntegrationConflictService).
   *
   * <p>{@code Propagation.MANDATORY} joins the caller's {@code RecoveryService.rerunFromStep} prep
   * transaction so the invalidation commits (or rolls back) atomically with the transition + the
   * recovery event + the recovery_actions row.
   *
   * @return the invalidated apr public id, or {@code Optional.empty()} when the run has no current
   *     approved artifact of that type (nothing to invalidate — the run was never approved at that
   *     stage)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<String> invalidateCurrentApproval(
      String workflowRunId, String artifactType, String reason) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      Optional<ApprovalSnapshot> current =
          approvalReadPort.findLatestApprovedForArtifactLineage(workflowRunId, artifactType);
      if (current.isEmpty()) {
        log.info(
            "approval invalidateCurrentApproval no-op workflowRunId={} artifactType={} reason=no_current_approval",
            workflowRunId,
            artifactType);
        return Optional.empty();
      }
      String approvalPublicId = current.get().publicId();
      boolean flipped =
          approvalWritePort.markInvalidated(
              approvalPublicId, reason, OffsetDateTime.now(clock).toInstant());
      log.info(
          "approval invalidateCurrentApproval workflowRunId={} artifactType={} approvalId={} reason={} flipped={}",
          workflowRunId,
          artifactType,
          approvalPublicId,
          reason,
          flipped);
      return flipped ? Optional.of(approvalPublicId) : Optional.empty();
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  /**
   * Story 4.22 (AC5) — the READ HALF of {@link #invalidateCurrentApproval}: resolve the public id
   * of the run's current approved artifact of the given type WITHOUT flipping it. Backs the
   * non-mutating rerun-from-step preview ({@code RecoveryService.previewRerunFromStep}) so the
   * Decision Bar can show which approval a rerun WOULD invalidate before the operator confirms.
   * Keeps the approval read inside the approval package (no {@code ApprovalReadPort} leak into
   * {@code RecoveryService}, mirroring the write-boundary discipline). Pure read — no
   * {@code @Transactional} (single {@link ApprovalReadPort} query; adding a nested boundary would
   * violate trap T4's no-annotation rule for this service).
   *
   * @return the {@code apr_…} id of the current approved artifact of that type, or {@code
   *     Optional.empty()} when the run has no current approval at that stage (nothing would be
   *     invalidated)
   */
  public Optional<String> findCurrentApprovalId(String workflowRunId, String artifactType) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    return approvalReadPort
        .findLatestApprovedForArtifactLineage(workflowRunId, artifactType)
        .map(ApprovalSnapshot::publicId);
  }

  /**
   * Story 4.7 [Review D1] — restore (un-invalidate) an approval that a rerun-from-step invalidated,
   * compensating a post-commit runner re-enqueue failure so the run is not stranded with a
   * destroyed approval. Keeps the approval WRITE inside the approval package (write-boundary
   * spirit, mirror {@link #invalidateCurrentApproval}). Idempotent at the DB level ({@code
   * invalidated_at IS NOT NULL} guard).
   *
   * @param approvalPublicId {@code apr_…} of the approval to restore (from the rerun's {@code
   *     invalidatedApprovalIds})
   * @return {@code true} when a row was restored; {@code false} when it was already valid / not
   *     found
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public boolean restoreInvalidatedApproval(String approvalPublicId) {
    String priorApprovalId = MdcKeys.beginScope(MdcKeys.APPROVAL_ID, approvalPublicId);
    try {
      boolean cleared = approvalWritePort.clearInvalidation(approvalPublicId);
      log.info(
          "approval restoreInvalidatedApproval approvalId={} cleared={}",
          approvalPublicId,
          cleared);
      return cleared;
    } finally {
      MdcKeys.endScope(MdcKeys.APPROVAL_ID, priorApprovalId);
    }
  }

  private DomainException versionMismatch(
      ApproveSpecCommand command, int currentArtifactVersion, int currentContextBundleVersion) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("expectedArtifactVersion", command.artifactVersion());
    details.put("currentArtifactVersion", currentArtifactVersion);
    details.put("expectedContextBundleVersion", command.contextVersion());
    details.put("currentContextBundleVersion", currentContextBundleVersion);
    details.put("artifactId", command.artifactId());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "approveSpec rejected APPROVAL_VERSION_MISMATCH workflowRunId={} artifactId={} expectedArtifactVersion={} currentArtifactVersion={} expectedContextBundleVersion={} currentContextBundleVersion={}",
        command.workflowRunId(),
        command.artifactId(),
        command.artifactVersion(),
        currentArtifactVersion,
        command.contextVersion(),
        currentContextBundleVersion);
    return new DomainException(
        DomainErrorCode.APPROVAL_VERSION_MISMATCH,
        "Approval rejected: artifact version is stale",
        details);
  }

  private DomainException artifactPayloadUnavailable(ApproveSpecCommand command) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("reason", "not_approval_eligible");
    log.warn(
        "approveSpec rejected ARTIFACT_PAYLOAD_UNAVAILABLE workflowRunId={} artifactId={} reason=not_approval_eligible",
        command.workflowRunId(),
        command.artifactId());
    return new DomainException(
        DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
        "Artifact payload is not approval-eligible: " + command.artifactId(),
        details);
  }

  private DomainException artifactNotFound(ApproveSpecCommand command) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "approveSpec rejected ARTIFACT_RECORD_NOT_FOUND workflowRunId={} artifactId={}",
        command.workflowRunId(),
        command.artifactId());
    return new DomainException(
        DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
        "Artifact not found: " + command.artifactId(),
        details);
  }

  private DomainException artifactTypeInvalid(
      ApproveSpecCommand command, ArtifactRecordSnapshot artifact) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("actualArtifactType", artifact.artifactType().value());
    details.put("expectedArtifactType", ArtifactType.SPEC.value());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "approveSpec rejected INVALID_COMMAND_PAYLOAD workflowRunId={} artifactId={} actualArtifactType={} expectedArtifactType={} reason=non_spec_artifact",
        command.workflowRunId(),
        command.artifactId(),
        artifact.artifactType().value(),
        ArtifactType.SPEC.value());
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "approveSpec called against a non-spec artifact",
        details);
  }

  private DomainException artifactRunMismatch(
      ApproveSpecCommand command, ArtifactRecordSnapshot artifact) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("artifactWorkflowRunId", artifact.workflowRunId());
    details.put("commandWorkflowRunId", command.workflowRunId());
    log.warn(
        "approveSpec rejected INVALID_COMMAND_PAYLOAD workflowRunId={} artifactId={} artifactWorkflowRunId={} reason=artifact_run_mismatch",
        command.workflowRunId(),
        command.artifactId(),
        artifact.workflowRunId());
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Artifact does not belong to the supplied workflow run",
        details);
  }

  private DomainException versionMismatchReject(
      RejectSpecCommand command, int currentArtifactVersion, int currentContextBundleVersion) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("expectedArtifactVersion", command.artifactVersion());
    details.put("currentArtifactVersion", currentArtifactVersion);
    details.put("expectedContextBundleVersion", command.contextVersion());
    details.put("currentContextBundleVersion", currentContextBundleVersion);
    details.put("artifactId", command.artifactId());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "rejectSpec rejected APPROVAL_VERSION_MISMATCH workflowRunId={} artifactId={} expectedArtifactVersion={} currentArtifactVersion={} expectedContextBundleVersion={} currentContextBundleVersion={}",
        command.workflowRunId(),
        command.artifactId(),
        command.artifactVersion(),
        currentArtifactVersion,
        command.contextVersion(),
        currentContextBundleVersion);
    return new DomainException(
        DomainErrorCode.APPROVAL_VERSION_MISMATCH,
        "Rejection rejected: artifact version is stale",
        details);
  }

  private DomainException artifactNotFoundReject(RejectSpecCommand command) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "rejectSpec rejected ARTIFACT_RECORD_NOT_FOUND workflowRunId={} artifactId={}",
        command.workflowRunId(),
        command.artifactId());
    return new DomainException(
        DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
        "Artifact not found: " + command.artifactId(),
        details);
  }

  private DomainException artifactTypeInvalidReject(
      RejectSpecCommand command, ArtifactRecordSnapshot artifact) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("actualArtifactType", artifact.artifactType().value());
    details.put("expectedArtifactType", ArtifactType.SPEC.value());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "rejectSpec rejected INVALID_COMMAND_PAYLOAD workflowRunId={} artifactId={} actualArtifactType={} expectedArtifactType={} reason=non_spec_artifact",
        command.workflowRunId(),
        command.artifactId(),
        artifact.artifactType().value(),
        ArtifactType.SPEC.value());
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "rejectSpec called against a non-spec artifact",
        details);
  }

  private DomainException artifactRunMismatchReject(
      RejectSpecCommand command, ArtifactRecordSnapshot artifact) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("artifactWorkflowRunId", artifact.workflowRunId());
    details.put("commandWorkflowRunId", command.workflowRunId());
    log.warn(
        "rejectSpec rejected INVALID_COMMAND_PAYLOAD workflowRunId={} artifactId={} artifactWorkflowRunId={} reason=artifact_run_mismatch",
        command.workflowRunId(),
        command.artifactId(),
        artifact.workflowRunId());
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Artifact does not belong to the supplied workflow run",
        details);
  }

  private DomainException nonProductTaxonomy(RejectSpecCommand command) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("workflowRunId", command.workflowRunId());
    details.put("reviewerRole", command.reviewerRole());
    details.put("taggedFeedback", command.taggedFeedback().value());
    details.put("reason", "spec_rejection_requires_product_taxonomy");
    log.warn(
        "rejectSpec rejected INVALID_COMMAND_PAYLOAD workflowRunId={} artifactId={} reviewerRole={} taggedFeedback={} reason=spec_rejection_requires_product_taxonomy",
        command.workflowRunId(),
        command.artifactId(),
        command.reviewerRole(),
        command.taggedFeedback().value());
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Spec rejection must carry a product-taxonomy value",
        details);
  }

  private static Map<String, Object> rejectEventDetails(
      RejectSpecCommand command, ApprovalSnapshot persisted, int specRejectionLoopCount) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("approvalId", persisted.publicId());
    details.put("artifactId", persisted.artifactId());
    details.put("artifactVersion", persisted.artifactVersion());
    details.put("contextBundleVersion", persisted.contextBundleVersion());
    details.put("reviewerRole", persisted.reviewerRole());
    details.put("taggedFeedback", command.taggedFeedback().value());
    details.put("specRejectionLoopCount", specRejectionLoopCount);
    details.put("idempotencyKey", command.idempotencyKey());
    String correlationId = normalizeOptional(command.correlationId());
    if (correlationId != null) {
      details.put("correlationId", correlationId);
    }
    return details;
  }

  private static Map<String, Object> escalationEventDetails(
      RejectSpecCommand command, int threshold, int specRejectionLoopCount) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("reason", "spec_rejection_loop_threshold_exceeded");
    details.put("specRejectionLoopCount", specRejectionLoopCount);
    details.put("threshold", threshold);
    details.put("idempotencyKey", command.idempotencyKey());
    String correlationId = normalizeOptional(command.correlationId());
    if (correlationId != null) {
      details.put("correlationId", correlationId);
    }
    return details;
  }

  private static Map<String, Object> transitionEventDetailsReject(
      RejectSpecCommand command,
      ApprovalSnapshot persisted,
      int specRejectionLoopCount,
      boolean escalationMarker) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("approvalId", persisted.publicId());
    details.put("artifactId", persisted.artifactId());
    details.put("artifactVersion", persisted.artifactVersion());
    details.put("contextBundleVersion", persisted.contextBundleVersion());
    details.put("reviewerRole", persisted.reviewerRole());
    details.put("taggedFeedback", command.taggedFeedback().value());
    details.put("specRejectionLoopCount", specRejectionLoopCount);
    details.put("escalationMarker", escalationMarker);
    details.put("commandType", command.commandType());
    details.put("idempotencyKey", command.idempotencyKey());
    String correlationId = normalizeOptional(command.correlationId());
    if (correlationId != null) {
      details.put("correlationId", correlationId);
    }
    return details;
  }

  private static Map<String, Object> approvalEventDetails(
      ApproveSpecCommand command, ApprovalSnapshot persisted) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("approvalId", persisted.publicId());
    details.put("artifactId", persisted.artifactId());
    details.put("artifactVersion", persisted.artifactVersion());
    details.put("contextBundleVersion", persisted.contextBundleVersion());
    details.put("reviewerRole", persisted.reviewerRole());
    details.put("idempotencyKey", command.idempotencyKey());
    String correlationId = normalizeOptional(command.correlationId());
    if (correlationId != null) {
      details.put("correlationId", correlationId);
    }
    return details;
  }

  private static Map<String, Object> transitionEventDetails(
      ApproveSpecCommand command, ApprovalSnapshot persisted) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("approvalId", persisted.publicId());
    details.put("artifactId", persisted.artifactId());
    details.put("artifactVersion", persisted.artifactVersion());
    details.put("contextBundleVersion", persisted.contextBundleVersion());
    details.put("reviewerRole", persisted.reviewerRole());
    details.put("commandType", command.commandType());
    details.put("idempotencyKey", command.idempotencyKey());
    String correlationId = normalizeOptional(command.correlationId());
    if (correlationId != null) {
      details.put("correlationId", correlationId);
    }
    return details;
  }

  private static String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
