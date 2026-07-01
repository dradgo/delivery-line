package org.dradgo.application.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.ContextBundleService;
import org.dradgo.application.runner.ExecutionSubStage;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.SplitProposalWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
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
 * Story 3f-4 (R1, Task 4) — harvests a split-mode reviewer's {@code split-proposal.v1}
 * decomposition into {@code split_proposals} and finalizes the reviewer {@code runner_executions}
 * row. Delegated to from {@code RunnerBroker.onResult} for a {@link RunnerStage#REVIEW} execution
 * whose idempotency key marks it split-mode ({@code split-proposal:…}); a NON-split REVIEW result
 * still routes to {@code ReviewResultHarvester}. The split proposal is NOT a verdict, so {@code
 * step_reviews} is untouched.
 *
 * <p>Sibling of {@code ReviewResultHarvester}: VALIDATE → PARSE → CHECK self-reported failure →
 * RESOLVE reviewed artifact + identities → REDACT → supersede-then-insert the {@code open} proposal
 * + finalize the execution, ALL in one REQUIRES_NEW tx (isolates a unique-index conflict; see the
 * field comment — the poll path must NOT hold this row's lock across the harvest, or the
 * REQUIRES_NEW re-lock self-deadlocks). Every failure mode degrades gracefully (R5): the execution
 * is marked failed, NO proposal row is written (its absence + the failed execution is the panel's
 * "unavailable" state), and the gate is never blocked.
 */
@Component
public class SplitProposalHarvester {

  private static final Logger log = LoggerFactory.getLogger(SplitProposalHarvester.class);

  private static final int MAX_SPLIT_PROPOSAL_BYTES = 256 * 1024;

  private final RunnerContractValidator contractValidator;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SplitProposalReadPort splitProposalReadPort;
  private final SplitProposalWritePort splitProposalWritePort;
  private final RedactionPolicyService redactionPolicyService;
  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final ContextBundleService contextBundleService;
  private final RunnerExecutionService runnerExecutionService;
  private final RunnerProperties runnerProperties;
  // The proposal insert + the reviewer-execution finalize ride ONE REQUIRES_NEW tx (mirrors
  // ReviewResultHarvester) so a crash never leaves a persisted proposal beside a still-RUNNING
  // execution, and a unique-index conflict rolls back THIS tx only (never poisons the ambient poll
  // tx). REQUIRES_NEW is only safe because the poll path no longer holds a PESSIMISTIC_WRITE lock
  // on
  // this runner_executions row across the harvest: the completed-container log/raw-output capture
  // (RunnerExecutionService.recordRawOutput) runs in its OWN REQUIRES_NEW tx and releases before
  // the
  // harvest — see RunnerExecutionPersistenceAdapter.recordRawOutput. Were the ambient poll tx to
  // hold that row lock, recordFailed/recordCompleted here (findByPublicIdForUpdate on the SAME row,
  // second connection) would self-deadlock on it and hang forever, wedging the single scheduler
  // thread so the timeout/stale reaper could never run.
  private final TransactionTemplate persistTransactionTemplate;
  private final TransactionTemplate degradeTransactionTemplate;

  public SplitProposalHarvester(
      RunnerContractValidator contractValidator,
      SplitProposalReadPort splitProposalReadPort,
      SplitProposalWritePort splitProposalWritePort,
      RedactionPolicyService redactionPolicyService,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      ContextBundleService contextBundleService,
      RunnerExecutionService runnerExecutionService,
      RunnerProperties runnerProperties,
      PlatformTransactionManager transactionManager) {
    this.contractValidator = contractValidator;
    this.splitProposalReadPort = splitProposalReadPort;
    this.splitProposalWritePort = splitProposalWritePort;
    this.redactionPolicyService = redactionPolicyService;
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
    this.contextBundleService = contextBundleService;
    this.runnerExecutionService = runnerExecutionService;
    this.runnerProperties = runnerProperties;
    this.persistTransactionTemplate = new TransactionTemplate(transactionManager);
    this.persistTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.degradeTransactionTemplate = new TransactionTemplate(transactionManager);
    this.degradeTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Harvest a split-proposal result. Returns {@code "success"} when a proposal was persisted,
   * {@code "failure"} when the proposal degraded. NEVER throws into the caller (R5).
   */
  public String harvest(String runnerExecutionId, String workflowRunId, byte[] payloadBytes) {
    // R5 / review 2026-06-29: this harvester MUST be total. RunnerBroker.onResult has NO catch
    // around harvest(...) (only a finally), so any RuntimeException escaping here strands the
    // reviewer execution RUNNING until the timeout scan reaps it. Wrap the whole body and degrade
    // on any unexpected fault — notably the reviewer-identity / reviewed-artifact / loop-count
    // resolver reads, which are not otherwise guarded against RuntimeException.
    try {
      ValidationContext context =
          ValidationContext.builder().maxPayloadBytes(MAX_SPLIT_PROPOSAL_BYTES).build();
      ValidationResult validation =
          contractValidator.validate(ValidationTarget.SPLIT_PROPOSAL, payloadBytes, context);
      if (!validation.valid()) {
        return degrade(
            runnerExecutionId,
            workflowRunId,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            "split proposal failed contract validation (errors="
                + validation.errors().size()
                + ")");
      }

      JsonNode parsed;
      try {
        parsed = objectMapper.readTree(payloadBytes);
      } catch (java.io.IOException error) {
        return degrade(
            runnerExecutionId,
            workflowRunId,
            FailureCategory.RUNNER_MALFORMED_OUTPUT,
            "split proposal is not valid JSON");
      }

      JsonNode failureCategoryNode = parsed.get("failureCategory");
      if (failureCategoryNode != null
          && failureCategoryNode.isTextual()
          && !failureCategoryNode.asText().isBlank()) {
        return degrade(
            runnerExecutionId,
            workflowRunId,
            parseFailureCategory(failureCategoryNode.asText()),
            "split runner self-reported failure: " + failureCategoryNode.asText());
      }

      JsonNode subtasksNode = parsed.path("subtasks");
      if (!subtasksNode.isArray() || subtasksNode.isEmpty()) {
        return degrade(
            runnerExecutionId,
            workflowRunId,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            "split proposal carried no subtasks");
      }

      // Resolve the reviewed artifact (spec-inclusive — split fires at the spec gate too). The
      // split
      // dispatch is enqueued directly (no reviewed-artifact pin), so re-derive from the run.
      ArtifactRecordSnapshot reviewed;
      try {
        reviewed = contextBundleService.resolveReviewedArtifact(workflowRunId);
      } catch (DomainException missing) {
        return degrade(
            runnerExecutionId,
            workflowRunId,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            "no reviewed artifact resolvable at split harvest: " + missing.errorCode().value());
      }

      String reviewerIdentity = resolveReviewerIdentity(workflowRunId);
      String producerIdentity = resolveProducerIdentity(workflowRunId, reviewed.artifactType());

      // Build + redact the {subtasks, dependencies} payload (AC7) before persistence/egress.
      ObjectNode proposalNode = objectMapper.createObjectNode();
      proposalNode.set("subtasks", subtasksNode);
      proposalNode.set(
          "dependencies", retainValidDependencies(subtasksNode, parsed.path("dependencies")));
      String redactedProposalJson;
      try {
        redactedProposalJson =
            redactionPolicyService
                .redact(
                    objectMapper.writeValueAsString(proposalNode),
                    DataClassification.SHAREABLE_REDACTED.value())
                .sanitizedText();
      } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException redactError) {
        return degrade(
            runnerExecutionId,
            workflowRunId,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            "split proposal redaction/serialization failed: "
                + redactError.getClass().getSimpleName());
      }

      int loopCount = splitProposalReadPort.currentSplitProposalLoopCount(workflowRunId);
      NewSplitProposal newProposal =
          new NewSplitProposal(
              PublicIdPrefixes.SPLIT_PROPOSAL.next(),
              workflowRunId,
              reviewed.publicId(),
              reviewed.version(),
              loopCount,
              redactedProposalJson,
              reviewerIdentity,
              producerIdentity);

      boolean selfReview = reviewerIdentity != null && reviewerIdentity.equals(producerIdentity);
      if (selfReview) {
        log.info(
            "split self-review detected run={} reviewerExec={} model={}",
            workflowRunId,
            runnerExecutionId,
            reviewerIdentity);
      }

      try {
        persistTransactionTemplate.executeWithoutResult(
            status -> {
              // supersede-then-insert (one tx) so the partial unique index never sees two open
              // rows.
              splitProposalWritePort.supersedeOpenForRun(workflowRunId);
              splitProposalWritePort.insertOpen(newProposal);
              runnerExecutionService.recordCompleted(runnerExecutionId);
            });
      } catch (DomainException terminalRace) {
        if (terminalRace.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
          // The reviewer execution was already terminal at persist, so the REQUIRES_NEW tx rolled
          // back (the insert was undone). If a PRIOR harvest already persisted the open proposal
          // this
          // is a genuine idempotent no-op; but if NOTHING persisted (e.g. a concurrent timeout scan
          // flipped the execution between the row-read and persist) we must NOT falsely report
          // success and silently discard a valid proposal — degrade instead (review 2026-06-29).
          if (splitProposalReadPort.findOpenForRun(workflowRunId).isPresent()) {
            log.info(
                "split reviewer execution already terminal at persist run={} reviewerExec={} —"
                    + " idempotent no-op (proposal already persisted)",
                workflowRunId,
                runnerExecutionId);
            return "success";
          }
          return degrade(
              runnerExecutionId,
              workflowRunId,
              FailureCategory.RUNNER_CONTRACT_VIOLATION,
              "split proposal persist rolled back at terminal-execution race (no proposal persisted)");
        }
        return degrade(
            runnerExecutionId,
            workflowRunId,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            "split proposal persistence failed: " + terminalRace.errorCode().value());
      } catch (RuntimeException persistError) {
        return degrade(
            runnerExecutionId,
            workflowRunId,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            "split proposal persistence failed: " + persistError.getClass().getSimpleName());
      }
      log.info(
          "persisted split_proposals {} run={} reviewerExec={} subtaskCount={} loopCount={} selfReview={}",
          newProposal.publicId(),
          workflowRunId,
          runnerExecutionId,
          subtasksNode.size(),
          loopCount,
          selfReview);
      return "success";
    } catch (RuntimeException unexpected) {
      return degrade(
          runnerExecutionId,
          workflowRunId,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          "unexpected fault harvesting split proposal: " + unexpected.getClass().getSimpleName());
    }
  }

  /**
   * Drop dependency edges that don't reference a retained subtask ordinal, self-edges, and
   * duplicates: a malformed subtask is dropped upstream while an edge pointing at it would survive,
   * persisting a dangling edge that 3f-5 would turn into a bogus {@code run_dependencies} row
   * (review 2026-06-29).
   */
  private com.fasterxml.jackson.databind.node.ArrayNode retainValidDependencies(
      JsonNode subtasksNode, JsonNode dependenciesNode) {
    com.fasterxml.jackson.databind.node.ArrayNode retained = objectMapper.createArrayNode();
    if (!dependenciesNode.isArray()) {
      return retained;
    }
    java.util.Set<Integer> subtaskOrdinals = new java.util.HashSet<>();
    for (JsonNode subtask : subtasksNode) {
      JsonNode ordinal = subtask.get("ordinal");
      if (ordinal != null && ordinal.isInt()) {
        subtaskOrdinals.add(ordinal.asInt());
      }
    }
    java.util.Set<String> seenEdges = new java.util.HashSet<>();
    for (JsonNode dependency : dependenciesNode) {
      JsonNode from = dependency.get("fromOrdinal");
      JsonNode to = dependency.get("toOrdinal");
      if (from == null || to == null || !from.isInt() || !to.isInt()) {
        continue;
      }
      int fromOrdinal = from.asInt();
      int toOrdinal = to.asInt();
      if (fromOrdinal == toOrdinal
          || !subtaskOrdinals.contains(fromOrdinal)
          || !subtaskOrdinals.contains(toOrdinal)
          || !seenEdges.add(fromOrdinal + "->" + toOrdinal)) {
        continue;
      }
      retained.add(dependency);
    }
    return retained;
  }

  private String degrade(
      String runnerExecutionId, String workflowRunId, FailureCategory category, String reason) {
    log.warn(
        "split proposal degraded run={} reviewerExec={} category={} reason={} — gate not blocked",
        workflowRunId,
        runnerExecutionId,
        category.value(),
        reason);
    try {
      degradeTransactionTemplate.executeWithoutResult(
          status -> runnerExecutionService.recordFailed(runnerExecutionId, category));
    } catch (DomainException terminalRace) {
      if (terminalRace.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
        log.info(
            "split reviewer execution already terminal at degrade run={} reviewerExec={}",
            workflowRunId,
            runnerExecutionId);
      } else {
        log.warn(
            "could not mark split reviewer execution failed run={} reviewerExec={} code={}",
            workflowRunId,
            runnerExecutionId,
            terminalRace.errorCode().value());
      }
    } catch (RuntimeException unexpected) {
      log.warn(
          "unexpected fault marking split reviewer execution failed run={} reviewerExec={} cause={}",
          workflowRunId,
          runnerExecutionId,
          unexpected.getClass().getSimpleName());
    }
    return "failure";
  }

  private String resolveReviewerIdentity(String workflowRunId) {
    return projectRuntimeConfigResolver
        .resolveReviewerKind(workflowRunId)
        .map(this::identityFor)
        .orElse(null);
  }

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
          "split producer identity unresolved run={} reviewedType={} cause={}",
          workflowRunId,
          reviewedType == null ? "null" : reviewedType.value(),
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
      return kind.value();
    }
  }

  private FailureCategory parseFailureCategory(String raw) {
    try {
      return FailureCategory.fromValue(raw, "split-proposal.failureCategory");
    } catch (RuntimeException unknown) {
      return FailureCategory.RUNNER_CONTRACT_VIOLATION;
    }
  }
}
