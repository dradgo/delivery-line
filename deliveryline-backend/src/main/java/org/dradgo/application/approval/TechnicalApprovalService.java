package org.dradgo.application.approval;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.application.approval.spi.ApprovalWritePort;
import org.dradgo.application.approval.spi.ApprovalWritePort.NewApproval;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactService;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
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
 * Story 3.20 — canonical executor for the {@link
 * org.dradgo.domain.registry.AllowedAction#ACCEPT_IMPLEMENTATION} action: a developer accepting a
 * merge-ready implementation artifact ({@code implementationPlan} or {@code prOutput}). The
 * technical-approval twin of {@link ApprovalService#approveSpec} — same version-binding-first
 * ordering, same {@link ApprovalWritePort} → {@code approval.approved} event → {@link
 * WorkflowTransitionService#transition} sequence in one {@code MANDATORY}-propagation transaction
 * (FR16), differing only in artifact-type guard, reviewer role ({@code developer}, FR46), and the
 * artifact-type-driven transition target:
 *
 * <ul>
 *   <li>{@code prOutput} → {@link WorkflowState#COMPLETED} (the merge-ready handoff). The
 *       story-3.16 Linear completion-sync hook auto-fires post-commit for ANY {@code → COMPLETED}
 *       transition — this service NEVER calls Linear directly (Trap T6).
 *   <li>{@code implementationPlan} → {@link WorkflowState#EXECUTING}, then {@link
 *       WorkflowOrchestrationService#dispatchImplementation} enqueues the pr-output runner INSIDE
 *       the same transaction (Trap T5 — dispatch AFTER the transition, never before).
 * </ul>
 *
 * <p>FR21 separation (product vs technical acceptance) is preserved at the data layer by the {@code
 * reviewer_role} column ({@code developer} here vs {@code product_reviewer} for spec approval) plus
 * the artifact type; {@code WorkflowInspectionService.getRunSummary} surfaces the two states as
 * distinct typed fields.
 *
 * <p><strong>No {@code @Transactional} on the class.</strong> The public method uses {@code
 * Propagation.MANDATORY} so it ALWAYS joins the outer {@link
 * org.dradgo.application.workflow.WorkflowCommandService#acceptImplementation @Transactional}
 * boundary — a {@code REQUIRES_NEW} boundary would not roll the approval row back when the
 * surrounding transition fails (Trap T4). ArchUnit pins no {@code REQUIRES_NEW} here.
 */
@Service
public class TechnicalApprovalService {

  private static final Logger log = LoggerFactory.getLogger(TechnicalApprovalService.class);

  private static final String TRANSITION_REASON = "accept implementation";

  private final ArtifactRecordPort artifactRecordPort;
  private final ArtifactService artifactService;
  private final ApprovalWritePort approvalWritePort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final WorkflowTransitionService workflowTransitionService;
  private final WorkflowOrchestrationService workflowOrchestrationService;
  private final IntegrationLinkService integrationLinkService;
  private final ApprovalVersionBinder approvalVersionBinder;
  private final Clock clock;

  @Autowired
  public TechnicalApprovalService(
      ArtifactRecordPort artifactRecordPort,
      ArtifactService artifactService,
      ApprovalWritePort approvalWritePort,
      WorkflowEventWritePort workflowEventWritePort,
      WorkflowTransitionService workflowTransitionService,
      WorkflowOrchestrationService workflowOrchestrationService,
      IntegrationLinkService integrationLinkService,
      ApprovalVersionBinder approvalVersionBinder) {
    this(
        artifactRecordPort,
        artifactService,
        approvalWritePort,
        workflowEventWritePort,
        workflowTransitionService,
        workflowOrchestrationService,
        integrationLinkService,
        approvalVersionBinder,
        Clock.systemUTC());
  }

  // Visible-for-tests constructor: lets unit tests inject a fixed Clock so decidedAt assertions are
  // deterministic (mirrors ApprovalService).
  TechnicalApprovalService(
      ArtifactRecordPort artifactRecordPort,
      ArtifactService artifactService,
      ApprovalWritePort approvalWritePort,
      WorkflowEventWritePort workflowEventWritePort,
      WorkflowTransitionService workflowTransitionService,
      WorkflowOrchestrationService workflowOrchestrationService,
      IntegrationLinkService integrationLinkService,
      ApprovalVersionBinder approvalVersionBinder,
      Clock clock) {
    this.artifactRecordPort = artifactRecordPort;
    this.artifactService = artifactService;
    this.approvalWritePort = approvalWritePort;
    this.workflowEventWritePort = workflowEventWritePort;
    this.workflowTransitionService = workflowTransitionService;
    this.workflowOrchestrationService = workflowOrchestrationService;
    this.integrationLinkService = integrationLinkService;
    this.approvalVersionBinder = approvalVersionBinder;
    this.clock = clock;
  }

  /**
   * Trap T4 pin: {@code Propagation.MANDATORY} guarantees this method ALWAYS executes inside the
   * outer {@link
   * org.dradgo.application.workflow.WorkflowCommandService#acceptImplementation @Transactional}
   * boundary. A direct invocation without an outer transaction fails fast with {@code
   * IllegalTransactionStateException} instead of silently committing the approval row + event then
   * rolling back only the transition.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ApprovalResult acceptImplementation(AcceptImplementationCommand command) {
    PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(command.artifactId(), PublicIdPrefixes.ARTIFACT);

    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    String priorArtifactId = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, command.artifactId());
    try {
      ArtifactRecordSnapshot artifact =
          artifactRecordPort
              .findByPublicId(command.artifactId())
              .orElseThrow(() -> artifactNotFound(command));

      log.info(
          "acceptImplementation entry workflowRunId={} artifactId={} artifactType={} expectedArtifactVersion={} expectedContextBundleVersion={} reviewerRole={} actorIdentity={} actorType={}",
          command.workflowRunId(),
          command.artifactId(),
          artifact.artifactType().value(),
          command.artifactVersion(),
          command.contextVersion(),
          command.reviewerRole(),
          command.actorIdentity(),
          command.actorType().value());

      // AC2 type guard: technical approval accepts ONLY implementationPlan / prOutput. A spec
      // artifact must go through the product-approval path (ApprovalService.approveSpec). The
      // composite FK on the approvals table does not enforce artifact_type, so without this guard a
      // caller could accept a spec and silently advance the run.
      ArtifactType artifactType = artifact.artifactType();
      if (artifactType == ArtifactType.SPEC) {
        throw artifactTypeInvalid(command, artifact);
      }
      // Defense-in-depth: refuse to accept another run's artifact (artifact_id and workflow_run_id
      // are independently FK-checked; mirror ApprovalService.approveSpec).
      if (!artifact.workflowRunId().equals(command.workflowRunId())) {
        throw artifactRunMismatch(command, artifact);
      }

      // AC5 version-binding FIRST (Trap T3): a stale artifact version OR stale context-bundle
      // version is a reviewer error that must surface before any eligibility / PR-link detail.
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

      // AC6 eligibility: payload bytes match the persisted checksum, etc.
      if (!artifactService.isApprovalEligible(command.artifactId())) {
        throw artifactPayloadUnavailable(command);
      }

      // AC6 PR-link gate — prOutput ONLY (an implementationPlan has no PR yet). OQ-1 (Alex): the
      // artifact's true prRef lives only in its payload JSON, so the pilot gate degrades to "an
      // active github_pr link must exist", routed through the canonical assertArtifactPrLinkMatches
      // so a future story can tighten it to a true artifact-vs-link comparison in one place.
      if (artifactType == ArtifactType.PR_OUTPUT) {
        assertPrLinkPresentAndMatches(command);
      }

      // AC3 persist the approval row.
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

        // AC7/AC9 append the approval.approved event INSIDE the surrounding transaction. The
        // transition service appends the workflow.stateChanged event on its own.
        workflowEventWritePort.append(
            new WorkflowEventRecord(
                PublicIdPrefixes.WORKFLOW_EVENT.next(),
                command.workflowRunId(),
                WorkflowEventType.APPROVAL_APPROVED,
                null,
                null,
                command.actorIdentity(),
                command.actorType(),
                "implementation accepted",
                null,
                false,
                decidedAt,
                approvalEventDetails(command, persisted)));

        WorkflowState resultingState =
            transitionAndDispatch(command, persisted, artifactType, decidedAt);

        log.info(
            "acceptImplementation success approvalId={} workflowRunId={} artifactId={} artifactType={} artifactVersion={} contextBundleVersion={} reviewerRole={} resultingState={}",
            persisted.publicId(),
            persisted.workflowRunId(),
            persisted.artifactId(),
            artifactType.value(),
            persisted.artifactVersion(),
            persisted.contextBundleVersion(),
            persisted.reviewerRole(),
            resultingState.value());

        return new ApprovalResult(
            persisted.publicId(),
            persisted.workflowRunId(),
            persisted.artifactId(),
            persisted.artifactVersion(),
            persisted.contextBundleVersion(),
            persisted.reviewerRole(),
            persisted.decidedAt(),
            resultingState,
            normalizeOptional(command.correlationId()));
      } catch (DomainException domainError) {
        if (domainError.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
          // Trap T4/AC11: propagating ILLEGAL_TRANSITION rolls back the outer transaction so the
          // just-inserted approval row + appended event disappear (no orphan row, no orphan event).
          log.warn(
              "acceptImplementation rejected ILLEGAL_TRANSITION approvalId={} workflowRunId={} cause={}",
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
   * AC7/AC8 — branch the transition target on artifact type and (for the implementation-plan
   * branch) dispatch the pr-output runner AFTER the {@code → EXECUTING} transition (Trap T5).
   * prOutput lands the run in {@code COMPLETED} (the 3.16 Linear hook fires post-commit; do NOT
   * call it here).
   */
  private WorkflowState transitionAndDispatch(
      AcceptImplementationCommand command,
      ApprovalSnapshot persisted,
      ArtifactType artifactType,
      OffsetDateTime decidedAt) {
    Map<String, Object> transitionDetails = transitionEventDetails(command, persisted);
    TransitionActor actor = new TransitionActor(command.actorIdentity(), command.actorType());
    if (artifactType == ArtifactType.PR_OUTPUT) {
      log.info(
          "acceptImplementation transition workflowRunId={} from=WaitingForReview to=Completed",
          command.workflowRunId());
      workflowTransitionService.transition(
          command.workflowRunId(),
          WorkflowState.COMPLETED,
          actor,
          TRANSITION_REASON,
          command.idempotencyKey(),
          transitionDetails);
      return WorkflowState.COMPLETED;
    }
    // IMPLEMENTATION_PLAN
    log.info(
        "acceptImplementation transition workflowRunId={} from=WaitingForReview to=Executing",
        command.workflowRunId());
    workflowTransitionService.transition(
        command.workflowRunId(),
        WorkflowState.EXECUTING,
        actor,
        TRANSITION_REASON,
        command.idempotencyKey(),
        transitionDetails);
    // Trap T5: dispatch the pr-output runner AFTER the transition (it derives the sub-stage against
    // the now-Executing run). Inside this MANDATORY transaction, so a dispatch failure rolls back
    // the approval row + event + transition. No-op when implementation-stage.auto-dispatch=false.
    log.info(
        "acceptImplementation dispatchImplementation workflowRunId={} correlationId={}",
        command.workflowRunId(),
        normalizeOptional(command.correlationId()));
    workflowOrchestrationService.dispatchImplementation(
        command.workflowRunId(), normalizeOptional(command.correlationId()));
    return WorkflowState.EXECUTING;
  }

  /**
   * AC6 PR-link gate for {@code prOutput}. OQ-1 pilot decision: resolve the active {@code
   * github_pr} link and pass its canonical {@code external_ref} to {@link
   * IntegrationLinkService#assertArtifactPrLinkMatches}. With no active link the gate fails closed
   * with {@code ARTIFACT_PR_LINK_MISMATCH}.
   */
  private void assertPrLinkPresentAndMatches(AcceptImplementationCommand command) {
    IntegrationLink activeLink =
        integrationLinkService
            .findActiveGitHubPrLink(command.workflowRunId())
            .orElseThrow(() -> prLinkMissing(command));
    integrationLinkService.assertArtifactPrLinkMatches(
        command.workflowRunId(), activeLink.externalRef());
  }

  private DomainException prLinkMissing(AcceptImplementationCommand command) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("workflowRunId", command.workflowRunId());
    details.put("artifactId", command.artifactId());
    details.put("reason", "no_active_github_pr_link");
    log.warn(
        "acceptImplementation rejected ARTIFACT_PR_LINK_MISMATCH workflowRunId={} artifactId={} reason=no_active_github_pr_link",
        command.workflowRunId(),
        command.artifactId());
    return new DomainException(
        DomainErrorCode.ARTIFACT_PR_LINK_MISMATCH,
        "No active github_pr link to bind the prOutput approval against",
        details);
  }

  private DomainException versionMismatch(
      AcceptImplementationCommand command,
      int currentArtifactVersion,
      int currentContextBundleVersion) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("expectedArtifactVersion", command.artifactVersion());
    details.put("currentArtifactVersion", currentArtifactVersion);
    details.put("expectedContextBundleVersion", command.contextVersion());
    details.put("currentContextBundleVersion", currentContextBundleVersion);
    details.put("artifactId", command.artifactId());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "acceptImplementation rejected APPROVAL_VERSION_MISMATCH workflowRunId={} artifactId={} expectedArtifactVersion={} currentArtifactVersion={} expectedContextBundleVersion={} currentContextBundleVersion={}",
        command.workflowRunId(),
        command.artifactId(),
        command.artifactVersion(),
        currentArtifactVersion,
        command.contextVersion(),
        currentContextBundleVersion);
    return new DomainException(
        DomainErrorCode.APPROVAL_VERSION_MISMATCH,
        "Technical approval rejected: artifact version is stale",
        details);
  }

  private DomainException artifactPayloadUnavailable(AcceptImplementationCommand command) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("reason", "not_approval_eligible");
    log.warn(
        "acceptImplementation rejected ARTIFACT_PAYLOAD_UNAVAILABLE workflowRunId={} artifactId={} reason=not_approval_eligible",
        command.workflowRunId(),
        command.artifactId());
    return new DomainException(
        DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
        "Artifact payload is not approval-eligible: " + command.artifactId(),
        details);
  }

  private DomainException artifactNotFound(AcceptImplementationCommand command) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "acceptImplementation rejected ARTIFACT_RECORD_NOT_FOUND workflowRunId={} artifactId={}",
        command.workflowRunId(),
        command.artifactId());
    return new DomainException(
        DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
        "Artifact not found: " + command.artifactId(),
        details);
  }

  private DomainException artifactTypeInvalid(
      AcceptImplementationCommand command, ArtifactRecordSnapshot artifact) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("artifactType", artifact.artifactType().value());
    details.put("reason", "technical_approval_requires_implementation_artifact");
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "acceptImplementation rejected INVALID_COMMAND_PAYLOAD workflowRunId={} artifactId={} artifactType={} reason=technical_approval_requires_implementation_artifact",
        command.workflowRunId(),
        command.artifactId(),
        artifact.artifactType().value());
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "acceptImplementation called against a non-implementation artifact",
        details);
  }

  private DomainException artifactRunMismatch(
      AcceptImplementationCommand command, ArtifactRecordSnapshot artifact) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", command.artifactId());
    details.put("artifactWorkflowRunId", artifact.workflowRunId());
    details.put("commandWorkflowRunId", command.workflowRunId());
    log.warn(
        "acceptImplementation rejected INVALID_COMMAND_PAYLOAD workflowRunId={} artifactId={} artifactWorkflowRunId={} reason=artifact_run_mismatch",
        command.workflowRunId(),
        command.artifactId(),
        artifact.workflowRunId());
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Artifact does not belong to the supplied workflow run",
        details);
  }

  private static Map<String, Object> approvalEventDetails(
      AcceptImplementationCommand command, ApprovalSnapshot persisted) {
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
      AcceptImplementationCommand command, ApprovalSnapshot persisted) {
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
