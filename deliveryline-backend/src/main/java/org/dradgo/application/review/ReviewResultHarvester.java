package org.dradgo.application.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.review.spi.DuplicateStepReviewException;
import org.dradgo.application.review.spi.StepReviewWritePort;
import org.dradgo.application.review.spi.StepReviewWritePort.NewStepReview;
import org.dradgo.application.runner.ContextBundleService;
import org.dradgo.application.runner.ExecutionSubStage;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.ReviewOutcome;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.dradgo.runnercontracts.ValidationContext;
import org.dradgo.runnercontracts.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3d-2 (AC2/AC4/AC6/AC7, Task 5) — harvests an advisory reviewer's {@code review-result.v1}
 * verdict into {@code step_reviews} and finalizes the reviewer {@code runner_executions} row.
 * Delegated to from {@code RunnerBroker.onResult} for {@link RunnerStage#REVIEW} executions (DD-1:
 * the harvest is the reviewer-execution home in {@code application}).
 *
 * <p>Distinct from the normal runner harvest: a REVIEW result writes NO {@code artifacts} row
 * (DD-2) and NEVER transitions the run. The run stays {@code WaitingForReview} throughout — the
 * reviewer is a {@code runner_executions} lifecycle, not a run-state change. Every failure mode
 * (invalid contract, runner-self-reported failure, persistence fault) degrades gracefully (AC6):
 * the reviewer execution is marked failed, NO verdict is written (its absence + the failed reviewer
 * execution is the panel's "unavailable" state), and the run remains fully human-reviewable.
 */
@Component
public class ReviewResultHarvester {

  private static final Logger log = LoggerFactory.getLogger(ReviewResultHarvester.class);

  private static final int MAX_REVIEW_RESULT_BYTES = 256 * 1024;

  private final RunnerContractValidator contractValidator;
  // Plain instance (not an injected bean) — the codebase has no shared ObjectMapper bean; mirrors
  // ContextBundleService / WorkflowInspectionService.
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final StepReviewWritePort stepReviewWritePort;
  private final RedactionPolicyService redactionPolicyService;
  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final ContextBundleService contextBundleService;
  private final RunnerExecutionService runnerExecutionService;
  private final RunnerProperties runnerProperties;
  // Story 3d-2 (code-review D3 + F1 residual) — the verdict insert + the reviewer-execution
  // finalize
  // are wrapped in ONE programmatic tx so a crash/race never leaves a persisted verdict beside a
  // still-RUNNING reviewer execution. PROPAGATION_REQUIRES_NEW (not REQUIRED): the harvest CAN run
  // under an ambient per-item poll tx; a duplicate insert (V21 unique hit) rolls back THIS tx only
  // —
  // with REQUIRED it would mark the ambient poll tx rollback-only and the poll commit would then
  // fail with UnexpectedRollbackException for an advisory side-effect. REQUIRES_NEW suspends the
  // ambient tx and isolates the verdict write entirely.
  //
  // DEADLOCK DEPENDENCY (2026-07-01): this REQUIRES_NEW re-locks the runner_executions row
  // (recordCompleted/recordFailed → findByPublicIdForUpdate) on a SECOND pooled connection. It is
  // only deadlock-free because the ambient poll tx does NOT hold this row's lock across the
  // harvest:
  // the completed-container raw-output capture (RunnerExecutionPersistenceAdapter.recordRawOutput)
  // is PROPAGATION_REQUIRES_NEW and releases before the harvest. Do NOT make recordRawOutput join
  // the ambient tx (REQUIRED) — that reintroduces a self-deadlock on the single scheduler thread
  // (see SplitProposalHarvestPollDeadlockIT).
  private final TransactionTemplate reviewVerdictTransactionTemplate;

  // Story 3d-2 (code-review F1 residual) — the duplicate-verdict catch finalizes the reviewer
  // execution in its OWN REQUIRES_NEW tx so the pessimistic recordCompleted always has a fresh,
  // active, un-poisoned transaction (never the just-rolled-back verdict tx, never a poisoned/absent
  // ambient tx). Its catch swallows all faults (AC6 — a duplicate/late second opinion never strands
  // the run).
  private final TransactionTemplate finalizeCompletedTransactionTemplate;

  // Story 3d-2 (code-review 3rd round) — degrade() records the reviewer failure via a guarded
  // pessimistic transition (recordFailed -> markFailed -> findByPublicIdForUpdate). That FOR UPDATE
  // lock REQUIRES an active transaction, and onResult runs on the worker thread with NO ambient tx
  // (RunnerBroker.recordFailedBestEffort documents the same: "the worker has none, so open one").
  // Without this wrap the bare recordFailed throws, degrade()'s catch swallows it (the run is never
  // stranded — AC6), but the reviewer execution row is never marked FAILED, so the verdict endpoint
  // reports `pending` forever instead of `unavailable` + reason (AC2/AC6 "the failure is
  // recorded").
  // REQUIRES_NEW (own isolated tx) so a degrade is never affected by — and never poisons — any
  // ambient tx, mirroring the verdict/finalize templates above.
  private final TransactionTemplate recordFailedTransactionTemplate;

  public ReviewResultHarvester(
      RunnerContractValidator contractValidator,
      StepReviewWritePort stepReviewWritePort,
      RedactionPolicyService redactionPolicyService,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      ContextBundleService contextBundleService,
      RunnerExecutionService runnerExecutionService,
      RunnerProperties runnerProperties,
      PlatformTransactionManager transactionManager) {
    this.contractValidator = contractValidator;
    this.stepReviewWritePort = stepReviewWritePort;
    this.redactionPolicyService = redactionPolicyService;
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
    this.contextBundleService = contextBundleService;
    this.runnerExecutionService = runnerExecutionService;
    this.runnerProperties = runnerProperties;
    this.reviewVerdictTransactionTemplate = new TransactionTemplate(transactionManager);
    this.reviewVerdictTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.finalizeCompletedTransactionTemplate = new TransactionTemplate(transactionManager);
    this.finalizeCompletedTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.recordFailedTransactionTemplate = new TransactionTemplate(transactionManager);
    this.recordFailedTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Harvest a REVIEW result. Returns a metrics outcome label ({@code "success"} when a verdict was
   * persisted, {@code "failure"} when the reviewer degraded). NEVER throws into the caller for a
   * reviewer fault — graceful degradation is structural (AC6).
   */
  public String harvest(String runnerExecutionId, String workflowRunId, byte[] payloadBytes) {
    ValidationContext context =
        ValidationContext.builder().maxPayloadBytes(MAX_REVIEW_RESULT_BYTES).build();
    ValidationResult validation =
        contractValidator.validate(ValidationTarget.REVIEW_RESULT, payloadBytes, context);
    if (!validation.valid()) {
      return degrade(
          runnerExecutionId,
          workflowRunId,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          "review result failed contract validation (errors=" + validation.errors().size() + ")");
    }

    JsonNode parsed;
    try {
      parsed = objectMapper.readTree(payloadBytes);
    } catch (java.io.IOException error) {
      return degrade(
          runnerExecutionId,
          workflowRunId,
          FailureCategory.RUNNER_MALFORMED_OUTPUT,
          "review result is not valid JSON");
    }

    JsonNode failureCategoryNode = parsed.get("failureCategory");
    if (failureCategoryNode != null
        && failureCategoryNode.isTextual()
        && !failureCategoryNode.asText().isBlank()) {
      // The reviewer self-reported a failure (provider error, timeout) — degrade, no verdict.
      FailureCategory category = parseFailureCategory(failureCategoryNode.asText());
      return degrade(
          runnerExecutionId,
          workflowRunId,
          category,
          "reviewer self-reported failure: " + failureCategoryNode.asText());
    }

    ReviewOutcome outcome;
    try {
      outcome =
          ReviewOutcome.fromValue(parsed.path("outcome").asText(null), "review-result.outcome");
    } catch (RuntimeException badOutcome) {
      return degrade(
          runnerExecutionId,
          workflowRunId,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          "review result carried an unparseable outcome");
    }

    // Story 3d-2 (code-review D1) — reuse the reviewed artifact PINNED on the reviewer execution at
    // enqueue, so the verdict references the exact artifact the reviewer saw. Only when no pin
    // exists
    // (a reviewer enqueued before V22, or an enqueue-time resolve failure) do we fall back to
    // re-deriving from the run. Never pin the FK from runner-supplied input (DD-3).
    String reviewedPublicId;
    int reviewedVersion;
    ArtifactType reviewedType;
    Optional<org.dradgo.application.runner.spi.RunnerExecutionRecordPort.ReviewedArtifactPin> pin =
        runnerExecutionService.findReviewedArtifactPin(runnerExecutionId);
    if (pin.isPresent()) {
      reviewedPublicId = pin.get().artifactPublicId();
      reviewedVersion = pin.get().version();
      try {
        reviewedType =
            ArtifactType.fromValue(
                pin.get().artifactType(), "runner_executions.reviewed_artifact_type");
      } catch (RuntimeException badType) {
        return degrade(
            runnerExecutionId,
            workflowRunId,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            "pinned reviewed-artifact type unparseable");
      }
      log.debug(
          "review harvest using enqueue pin run={} reviewerExec={} reviewedArtifactId={} v={}",
          workflowRunId,
          runnerExecutionId,
          reviewedPublicId,
          reviewedVersion);
    } else {
      ArtifactRecordSnapshot reviewedArtifact;
      try {
        // Fallback: re-derive the reviewed artifact + version from the run; never pin the FK from
        // runner-supplied input. Story 3e-3 (code-review D1) — use the EXECUTION-only resolver
        // here,
        // not the spec-inclusive compose-time one: with the pin lost we can no longer tell a
        // spec-phase reviewer from an execution reviewer, and re-deriving the spec tier could
        // silently return a plan (if the run advanced) and mis-attribute the producer. A spec
        // reviewer whose pin was lost degrades cleanly instead.
        reviewedArtifact = contextBundleService.resolveExecutionReviewedArtifact(workflowRunId);
      } catch (DomainException missing) {
        return degrade(
            runnerExecutionId,
            workflowRunId,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            "no reviewed artifact resolvable at harvest: " + missing.errorCode().value());
      }
      reviewedPublicId = reviewedArtifact.publicId();
      reviewedVersion = reviewedArtifact.version();
      reviewedType = reviewedArtifact.artifactType();
    }

    String reviewerIdentity = resolveReviewerIdentity(workflowRunId);
    String producerIdentity = resolveProducerIdentity(workflowRunId, reviewedType);

    // AC7 — redact the rationale before persistence. The verdict text never reaches step_reviews or
    // any log line un-redacted.
    String rationale = textOrNull(parsed, "rationale");
    String redactedRationale =
        rationale == null
            ? null
            : redactionPolicyService
                .redact(rationale, DataClassification.SHAREABLE_REDACTED.value())
                .sanitizedText();

    String reviewPublicId = PublicIdPrefixes.REVIEW.next();
    NewStepReview newStepReview =
        new NewStepReview(
            reviewPublicId,
            workflowRunId,
            runnerExecutionId,
            reviewedPublicId,
            reviewedVersion,
            outcome,
            redactedRationale,
            reviewerIdentity,
            producerIdentity);
    // D3 — insert the verdict AND finalize the reviewer execution in ONE transaction (both join the
    // PROPAGATION_REQUIRES_NEW tx this template opens, isolated from any ambient poll tx) so a
    // crash/race never leaves a persisted verdict beside a still-RUNNING reviewer execution, and a
    // duplicate-insert rollback never poisons the caller's tx. The finalize is a RAW
    // recordCompleted
    // here: if the
    // row is already terminal (a concurrent harvest/degrade) it throws ILLEGAL_TRANSITION, the tx
    // rolls the verdict back too (the concurrent path owns the outcome) and we treat it as a benign
    // no-op below — never a degrade.
    StepReviewSnapshot snapshot;
    try {
      snapshot =
          reviewVerdictTransactionTemplate.execute(
              status -> {
                StepReviewSnapshot inserted = stepReviewWritePort.insert(newStepReview);
                runnerExecutionService.recordCompleted(runnerExecutionId);
                return inserted;
              });
    } catch (DuplicateStepReviewException duplicate) {
      // A duplicate/late harvest of the SAME reviewer execution (recovery replay / concurrent
      // harvest) — the insert hit the V21 unique index and the tx rolled back. The verdict the
      // first
      // delivery persisted stands; treat this re-delivery as a benign idempotent no-op, NOT a
      // contract-violation degrade. Finalize best-effort (separate tx) so a duplicate that races
      // ahead of the first finalize leaves no RUNNING row.
      finalizeCompleted(runnerExecutionId, workflowRunId);
      log.info(
          "step_review already persisted for run={} reviewerExec={} — idempotent no-op",
          workflowRunId,
          runnerExecutionId);
      return "success";
    } catch (DomainException terminalRace) {
      if (terminalRace.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
        // The reviewer execution was already terminal when the atomic finalize ran; the tx rolled
        // the verdict back. A concurrent harvest/degrade owns the outcome — benign, not a degrade.
        log.info(
            "reviewer execution already terminal at atomic finalize run={} reviewerExec={} —"
                + " idempotent no-op",
            workflowRunId,
            runnerExecutionId);
        return "success";
      }
      return degrade(
          runnerExecutionId,
          workflowRunId,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          "verdict persistence failed: " + terminalRace.errorCode().value());
    } catch (RuntimeException persistError) {
      return degrade(
          runnerExecutionId,
          workflowRunId,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          "verdict persistence failed: " + persistError.getClass().getSimpleName());
    }
    log.info(
        "persisted step_review {} outcome={} run={} reviewerExec={}",
        snapshot.publicId(),
        snapshot.outcome().value(),
        workflowRunId,
        runnerExecutionId);
    if (snapshot.selfReview()) {
      log.info(
          "self-review detected run={} reviewerExec={} model={}",
          workflowRunId,
          runnerExecutionId,
          reviewerIdentity);
    }
    return "success";
  }

  /**
   * Record the reviewer execution failed and leave the run untouched (AC6) — a failed second
   * opinion never strands a run. Best-effort: an already-terminal row (race) is tolerated.
   */
  private String degrade(
      String runnerExecutionId, String workflowRunId, FailureCategory category, String reason) {
    log.warn(
        "reviewer run failed for run {} reviewerExec={} category={} reason={} — run remains"
            + " human-reviewable",
        workflowRunId,
        runnerExecutionId,
        category.value(),
        reason);
    try {
      // REQUIRES_NEW so the guarded pessimistic transition has an active, isolated tx on the
      // worker thread (no ambient tx exists here) — without it the FOR UPDATE lock has no
      // transaction and the failure is never recorded.
      recordFailedTransactionTemplate.executeWithoutResult(
          status -> runnerExecutionService.recordFailed(runnerExecutionId, category));
    } catch (DomainException terminalRace) {
      // Honour the AC6 structural guarantee: a reviewer fault NEVER throws into the caller. An
      // already-terminal row (race) is expected; any other domain fault is logged, not propagated —
      // a failed second opinion must never strand the run.
      if (terminalRace.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
        log.info(
            "reviewer execution already terminal at degrade run={} reviewerExec={}",
            workflowRunId,
            runnerExecutionId);
      } else {
        log.warn(
            "could not mark reviewer execution failed run={} reviewerExec={} code={} — run remains"
                + " human-reviewable",
            workflowRunId,
            runnerExecutionId,
            terminalRace.errorCode().value());
      }
    } catch (RuntimeException unexpected) {
      log.warn(
          "unexpected fault marking reviewer execution failed run={} reviewerExec={} cause={} — run"
              + " remains human-reviewable",
          workflowRunId,
          runnerExecutionId,
          unexpected.getClass().getSimpleName());
    }
    return "failure";
  }

  private void finalizeCompleted(String runnerExecutionId, String workflowRunId) {
    // AC6 (code-review re-review 2026-06-22 + F1 residual) — NEVER throw into the caller.
    // finalizeCompleted runs from the duplicate-verdict catch, AFTER the REQUIRES_NEW verdict tx
    // rolled back. Run recordCompleted in its OWN REQUIRES_NEW tx so the pessimistic FOR UPDATE
    // always has a fresh, active, un-poisoned transaction — never the just-rolled-back verdict tx,
    // never a poisoned/absent ambient tx (which would throw UnexpectedRollbackException /
    // no-active-transaction). An already-terminal row is the expected race; any other fault is
    // logged, not propagated — a duplicate/late second opinion must never strand the run (the
    // verdict the first delivery persisted stands), mirroring degrade()'s structural guarantee.
    try {
      finalizeCompletedTransactionTemplate.executeWithoutResult(
          status -> runnerExecutionService.recordCompleted(runnerExecutionId));
    } catch (DomainException terminalRace) {
      if (terminalRace.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
        log.info(
            "reviewer execution already terminal at completion run={} reviewerExec={}",
            workflowRunId,
            runnerExecutionId);
      } else {
        log.warn(
            "could not finalize reviewer execution after duplicate verdict run={} reviewerExec={}"
                + " code={} — run remains human-reviewable",
            workflowRunId,
            runnerExecutionId,
            terminalRace.errorCode().value());
      }
    } catch (RuntimeException unexpected) {
      log.warn(
          "unexpected fault finalizing reviewer execution after duplicate verdict run={}"
              + " reviewerExec={} cause={} — run remains human-reviewable",
          workflowRunId,
          runnerExecutionId,
          unexpected.getClass().getSimpleName());
    }
  }

  /** Reviewer model identity (kind + image tag) for provenance + self-review (AC4). */
  private String resolveReviewerIdentity(String workflowRunId) {
    Optional<RunnerKind> reviewerKind =
        projectRuntimeConfigResolver.resolveReviewerKind(workflowRunId);
    return reviewerKind.map(this::identityFor).orElse(null);
  }

  /**
   * Producer model identity: the model that produced the reviewed artifact (deterministic —
   * runner_executions carries no kind column, DD-6).
   *
   * <ul>
   *   <li>A {@code prOutput} / {@code implementationPlan} reviewed artifact is an EXECUTION-stage
   *       output; its kind is the per-project/global EXECUTION kind for the matching sub-stage
   *       (3d-2, unchanged).
   *   <li>Story 3e-3 — a {@code spec} reviewed artifact (the spec-phase reviewer) was produced by
   *       the INVESTIGATION stage; its kind is the per-project/global INVESTIGATION kind. Using the
   *       EXECUTION kind here would mis-attribute the producer and could spuriously flag (or miss)
   *       a same-model self-review at the spec gate (AC5).
   * </ul>
   */
  private String resolveProducerIdentity(String workflowRunId, ArtifactType reviewedType) {
    try {
      RunnerKind producerKind;
      if (reviewedType == ArtifactType.SPEC) {
        producerKind =
            projectRuntimeConfigResolver.resolveRunnerKind(
                workflowRunId, RunnerStage.INVESTIGATION);
      } else {
        ExecutionSubStage subStage =
            reviewedType == ArtifactType.PR_OUTPUT
                ? ExecutionSubStage.PR_OUTPUT
                : ExecutionSubStage.IMPLEMENTATION_PLAN;
        producerKind =
            projectRuntimeConfigResolver.resolveRunnerKind(
                workflowRunId, RunnerStage.EXECUTION, subStage);
      }
      return identityFor(producerKind);
    } catch (RuntimeException unresolved) {
      log.warn(
          "producer identity unresolved run={} reviewedType={} cause={}",
          workflowRunId,
          reviewedType.value(),
          unresolved.getClass().getSimpleName());
      return null;
    }
  }

  private String identityFor(RunnerKind kind) {
    if (kind == null) {
      return null;
    }
    try {
      return kind.value() + ":" + runnerProperties.docker().imageTagFor(kind);
    } catch (RuntimeException noTag) {
      // imageTagFor can fail for an unconfigured/MANUAL kind; the kind alone is still a valid
      // self-review signal.
      return kind.value();
    }
  }

  private FailureCategory parseFailureCategory(String raw) {
    try {
      return FailureCategory.fromValue(raw, "review-result.failureCategory");
    } catch (RuntimeException unknown) {
      return FailureCategory.RUNNER_CONTRACT_VIOLATION;
    }
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode child = node.get(field);
    return child != null && child.isTextual() && !child.asText().isBlank() ? child.asText() : null;
  }
}
