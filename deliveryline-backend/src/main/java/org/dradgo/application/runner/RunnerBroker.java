package org.dradgo.application.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.artifact.RecordArtifactOperationCommand;
import org.dradgo.application.artifact.RecordArtifactOperationResult;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.spi.RecoverableRunnerAdapter;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.runner.workspace.RepositoryContextSummary;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.application.runner.workspace.spi.GitCommandException;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactOperationType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.dradgo.runnercontracts.ValidationContext;
import org.dradgo.runnercontracts.ValidationError;
import org.dradgo.runnercontracts.ValidationErrorCode;
import org.dradgo.runnercontracts.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns runner lifecycle: idempotent {@link #dispatch dispatch} with replay; result handling with
 * the AC5 workflow-state split; scheduled {@link #scanForTimeouts timeout scan}; startup {@link
 * #recoverOnStartup orphan recovery}.
 *
 * <p>Registered as {@code @Component} (not {@code @Service}) so the "application services must end
 * in Service" architecture rule stays clean while the story-fixed bean name remains {@code
 * RunnerBroker}.
 */
@Component
public class RunnerBroker {

  private static final Logger log = LoggerFactory.getLogger(RunnerBroker.class);

  // Strong digest used when promoting an ingested SPEC artifact to `available` (must be one of
  // ArtifactChecksum.ALLOWED_ALGORITHMS).
  private static final String SPEC_CHECKSUM_ALGORITHM = "SHA-256";

  private static final List<RunnerExecutionStatus> ACTIVE_STATUSES =
      List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING);

  private static final List<RunnerExecutionStatus> ALL_STATUSES =
      List.of(RunnerExecutionStatus.values());

  private final RunnerExecutionRecordPort recordPort;
  private final RunnerExecutionEventPort eventPort;
  private final RunnerExecutionService executionService;
  private final ContextBundleService contextBundleService;
  private final IdempotencyService idempotencyService;
  private final WorkflowTransitionService workflowTransitionService;
  private final ArtifactOperationService artifactOperationService;
  private final RunnerAdapter runnerAdapter;
  private final RunnerScratchStore scratchStore;
  private final RunnerContractValidator contractValidator;
  private final RunnerProperties runnerProperties;
  private final RunnerSecretScanService runnerSecretScanService;
  // Story 3.9 (Decision D0/OQ-2) — nullable repository-workspace seam. ObjectProvider-injected so
  // the package-private test ctor stays stable (the ~700 fast tests pass null and are unaffected).
  // When present, handleSuccess commits+pushes+opens the PR for a repo-backed run before
  // completing.
  private final RepositoryWorkspaceService repositoryWorkspaceService;
  // Story 3a-1 (Task 2 / Trap T2) — spec-stage orchestration callback resolved LAZILY through a
  // Supplier so the broker↔orchestration constructor cycle is broken (orchestration depends on the
  // broker for dispatch; the broker calls back here for the spec-ready transition). The @Autowired
  // ctor wires an ObjectProvider::getIfAvailable supplier — resolving eagerly in the ctor would
  // re-enter the in-creation orchestration bean (BeanCurrentlyInCreationException). Supplies null
  // in
  // the package-private test ctors and lean contexts, so mock/no-orchestration dispatches are
  // unchanged: handleSuccess only auto-advances when the resolved value is present AND the terminal
  // execution was an INVESTIGATION-stage spec artifact.
  private final java.util.function.Supplier<
          org.dradgo.application.workflow.WorkflowOrchestrationService>
      workflowOrchestrationServiceSupplier;
  // Story 3.10 (OQ-1) — resolves the run's Linear ticketRef for the EXECUTION-stage deterministic
  // branch (story 3.9 AC2) so prepareWorkspace checks out the right branch and captureAndPush
  // pushes
  // it. Always present in production (unconditional bean shared with ContextBundleService); null in
  // the package-private test ctors, where the repo seam is also null so the EXECUTION repo branch
  // is
  // never entered. Resolution is best-effort: a null/failed lookup falls back to a no-ticket branch
  // (WARN) rather than failing the dormant seam.
  private final TicketSummaryProvider ticketSummaryProvider;
  // Story 3.15 (Task 7) — the durable github_pr linkage writer, resolved LAZILY through a Supplier
  // (same pattern as the orchestration callback): the @Autowired ctor wires an
  // ObjectProvider::getIfAvailable supplier. Supplies null in the package-private test ctors and
  // lean contexts, so the linkage seam stays a byte-identical no-op there. The broker is the sole
  // caller of linkGitHubPr (Trap T12) — it never touches the SPI/adapter directly.
  private final java.util.function.Supplier<
          org.dradgo.application.integration.IntegrationLinkService>
      integrationLinkServiceSupplier;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate dispatchTransactionTemplate;
  private final TransactionTemplate perItemTransactionTemplate;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public RunnerBroker(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerExecutionService executionService,
      ContextBundleService contextBundleService,
      IdempotencyService idempotencyService,
      WorkflowTransitionService workflowTransitionService,
      ArtifactOperationService artifactOperationService,
      RunnerAdapter runnerAdapter,
      RunnerScratchStore scratchStore,
      RunnerContractValidator contractValidator,
      RunnerProperties runnerProperties,
      RunnerSecretScanService runnerSecretScanService,
      PlatformTransactionManager transactionManager,
      // Story 3.9 Trap T4 — ObjectProvider keeps the package-private test ctor stable; resolves to
      // null when no RepositoryWorkspaceService bean exists (mock/lean contexts).
      org.springframework.beans.factory.ObjectProvider<RepositoryWorkspaceService>
          repositoryWorkspaceServiceProvider,
      // Story 3a-1 Trap T2 — ObjectProvider breaks the broker↔orchestration constructor cycle.
      org.springframework.beans.factory.ObjectProvider<
              org.dradgo.application.workflow.WorkflowOrchestrationService>
          workflowOrchestrationServiceProvider,
      // Story 3.10 (OQ-1) — unconditional bean (no ObjectProvider needed); resolves the EXECUTION
      // ticketRef for the deterministic branch.
      TicketSummaryProvider ticketSummaryProvider,
      // Story 3.15 (Task 7) — the durable github_pr linkage writer, resolved lazily so the dormant
      // seam stays a no-op where the bean is absent (lean/mock contexts).
      org.springframework.beans.factory.ObjectProvider<
              org.dradgo.application.integration.IntegrationLinkService>
          integrationLinkServiceProvider) {
    this(
        recordPort,
        eventPort,
        executionService,
        contextBundleService,
        idempotencyService,
        workflowTransitionService,
        artifactOperationService,
        runnerAdapter,
        scratchStore,
        contractValidator,
        runnerProperties,
        runnerSecretScanService,
        requiredTemplate(transactionManager),
        requiresNewTemplate(transactionManager),
        Clock.systemUTC(),
        repositoryWorkspaceServiceProvider.getIfAvailable(),
        // Lazy: resolved at handleSuccess time, never during construction (Trap T2 — avoids the
        // BeanCurrentlyInCreation cycle since orchestration depends on this broker).
        (java.util.function.Supplier<org.dradgo.application.workflow.WorkflowOrchestrationService>)
            workflowOrchestrationServiceProvider::getIfAvailable,
        ticketSummaryProvider,
        (java.util.function.Supplier<org.dradgo.application.integration.IntegrationLinkService>)
            integrationLinkServiceProvider::getIfAvailable);
  }

  RunnerBroker(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerExecutionService executionService,
      ContextBundleService contextBundleService,
      IdempotencyService idempotencyService,
      WorkflowTransitionService workflowTransitionService,
      ArtifactOperationService artifactOperationService,
      RunnerAdapter runnerAdapter,
      RunnerScratchStore scratchStore,
      RunnerContractValidator contractValidator,
      RunnerProperties runnerProperties,
      RunnerSecretScanService runnerSecretScanService,
      TransactionTemplate dispatchTransactionTemplate,
      TransactionTemplate perItemTransactionTemplate,
      Clock clock) {
    this(
        recordPort,
        eventPort,
        executionService,
        contextBundleService,
        idempotencyService,
        workflowTransitionService,
        artifactOperationService,
        runnerAdapter,
        scratchStore,
        contractValidator,
        runnerProperties,
        runnerSecretScanService,
        dispatchTransactionTemplate,
        perItemTransactionTemplate,
        clock,
        null,
        () -> null,
        null,
        () -> null);
  }

  // Story 3.9 test ctor (repo seam, no orchestration) — delegates to the full ctor below.
  RunnerBroker(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerExecutionService executionService,
      ContextBundleService contextBundleService,
      IdempotencyService idempotencyService,
      WorkflowTransitionService workflowTransitionService,
      ArtifactOperationService artifactOperationService,
      RunnerAdapter runnerAdapter,
      RunnerScratchStore scratchStore,
      RunnerContractValidator contractValidator,
      RunnerProperties runnerProperties,
      RunnerSecretScanService runnerSecretScanService,
      TransactionTemplate dispatchTransactionTemplate,
      TransactionTemplate perItemTransactionTemplate,
      Clock clock,
      RepositoryWorkspaceService repositoryWorkspaceService) {
    this(
        recordPort,
        eventPort,
        executionService,
        contextBundleService,
        idempotencyService,
        workflowTransitionService,
        artifactOperationService,
        runnerAdapter,
        scratchStore,
        contractValidator,
        runnerProperties,
        runnerSecretScanService,
        dispatchTransactionTemplate,
        perItemTransactionTemplate,
        clock,
        repositoryWorkspaceService,
        () -> null,
        null,
        () -> null);
  }

  RunnerBroker(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerExecutionService executionService,
      ContextBundleService contextBundleService,
      IdempotencyService idempotencyService,
      WorkflowTransitionService workflowTransitionService,
      ArtifactOperationService artifactOperationService,
      RunnerAdapter runnerAdapter,
      RunnerScratchStore scratchStore,
      RunnerContractValidator contractValidator,
      RunnerProperties runnerProperties,
      RunnerSecretScanService runnerSecretScanService,
      TransactionTemplate dispatchTransactionTemplate,
      TransactionTemplate perItemTransactionTemplate,
      Clock clock,
      RepositoryWorkspaceService repositoryWorkspaceService,
      java.util.function.Supplier<org.dradgo.application.workflow.WorkflowOrchestrationService>
          workflowOrchestrationServiceSupplier,
      TicketSummaryProvider ticketSummaryProvider,
      java.util.function.Supplier<org.dradgo.application.integration.IntegrationLinkService>
          integrationLinkServiceSupplier) {
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.eventPort = Objects.requireNonNull(eventPort, "eventPort");
    this.executionService = Objects.requireNonNull(executionService, "executionService");
    this.contextBundleService =
        Objects.requireNonNull(contextBundleService, "contextBundleService");
    this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService");
    this.workflowTransitionService =
        Objects.requireNonNull(workflowTransitionService, "workflowTransitionService");
    this.artifactOperationService =
        Objects.requireNonNull(artifactOperationService, "artifactOperationService");
    this.runnerAdapter = Objects.requireNonNull(runnerAdapter, "runnerAdapter");
    this.scratchStore = Objects.requireNonNull(scratchStore, "scratchStore");
    this.contractValidator = Objects.requireNonNull(contractValidator, "contractValidator");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.runnerSecretScanService =
        Objects.requireNonNull(runnerSecretScanService, "runnerSecretScanService");
    this.dispatchTransactionTemplate =
        Objects.requireNonNull(dispatchTransactionTemplate, "dispatchTransactionTemplate");
    this.perItemTransactionTemplate =
        Objects.requireNonNull(perItemTransactionTemplate, "perItemTransactionTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.repositoryWorkspaceService = repositoryWorkspaceService;
    this.workflowOrchestrationServiceSupplier =
        workflowOrchestrationServiceSupplier == null
            ? () -> null
            : workflowOrchestrationServiceSupplier;
    this.ticketSummaryProvider = ticketSummaryProvider;
    this.integrationLinkServiceSupplier =
        integrationLinkServiceSupplier == null ? () -> null : integrationLinkServiceSupplier;
    this.objectMapper = new ObjectMapper();
  }

  // Story 3a-1 test ctor (repo seam + a resolved orchestration instance) — wraps the instance in a
  // Supplier and delegates to the Supplier-based ctor above. Lets unit tests pass a mock
  // orchestration directly without constructing an ObjectProvider.
  RunnerBroker(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerExecutionService executionService,
      ContextBundleService contextBundleService,
      IdempotencyService idempotencyService,
      WorkflowTransitionService workflowTransitionService,
      ArtifactOperationService artifactOperationService,
      RunnerAdapter runnerAdapter,
      RunnerScratchStore scratchStore,
      RunnerContractValidator contractValidator,
      RunnerProperties runnerProperties,
      RunnerSecretScanService runnerSecretScanService,
      TransactionTemplate dispatchTransactionTemplate,
      TransactionTemplate perItemTransactionTemplate,
      Clock clock,
      RepositoryWorkspaceService repositoryWorkspaceService,
      org.dradgo.application.workflow.WorkflowOrchestrationService workflowOrchestrationService) {
    this(
        recordPort,
        eventPort,
        executionService,
        contextBundleService,
        idempotencyService,
        workflowTransitionService,
        artifactOperationService,
        runnerAdapter,
        scratchStore,
        contractValidator,
        runnerProperties,
        runnerSecretScanService,
        dispatchTransactionTemplate,
        perItemTransactionTemplate,
        clock,
        repositoryWorkspaceService,
        () -> workflowOrchestrationService,
        null,
        () -> null);
  }

  private static TransactionTemplate requiredTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    return template;
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  // =====================================================================
  // dispatch
  // =====================================================================

  public RunnerDispatchResult dispatch(
      String workflowRunId, RunnerStage stage, String idempotencyKey, ActorContext actor) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(actor, "actor");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }

    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String reservedRexId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, reservedRexId);
    try {
      int nextContextBundleVersion = recordPort.nextContextBundleVersion(workflowRunId, stage);
      String fingerprint = dispatchFingerprint(workflowRunId, stage, nextContextBundleVersion);

      ReservationOutcome reservation =
          idempotencyService.checkAndReserve(
              idempotencyKey, "RunnerBroker.dispatch", actor.actorIdentity(), fingerprint);

      if (reservation.decision() == ReservationDecision.REPLAY) {
        String priorRexId = reservation.resultRef();
        RunnerExecutionSnapshot prior =
            recordPort
                .findByPublicId(priorRexId)
                .orElseThrow(() -> idempotencyRecordLost(idempotencyKey, priorRexId));
        log.info(
            "dispatch replay workflowRunId={} stage={} runnerExecutionId={} idempotencyKey={}",
            workflowRunId,
            stage.value(),
            priorRexId,
            idempotencyKey);
        return new RunnerDispatchResult.Replayed(toHandle(prior));
      }

      ExecutionConstraints constraints =
          new ExecutionConstraints(runnerProperties.timeoutFor(stage), false);

      // Story 3a-2 (AC7, Decision D2) + story 3.10 (AC2/AC5, Task 4) — for the INVESTIGATION AND
      // EXECUTION stages, when the profile-gated repository-workspace seam is present AND a single
      // repo is configured, resolve the repositoryRef now (cheap, no I/O). The clone + summarize
      // below happen inside the try so a git failure routes through the same FAILED idempotency +
      // dispatch-failure path (OQ-5). When the service is absent or no repo resolves, the summary
      // stays null and the dispatch path is byte-for-byte unchanged (no clone, no extra bundle
      // fields, dormant seam).
      boolean repoContextStage =
          stage == RunnerStage.INVESTIGATION || stage == RunnerStage.EXECUTION;
      String resolvedRepositoryRef =
          (repoContextStage && repositoryWorkspaceService != null)
              ? repositoryWorkspaceService.resolveConfiguredRepositoryRef().orElse(null)
              : null;

      ContextBundle bundle;
      // Story 3.12 (AC1 / Task 5) — the EXECUTION dispatch derives its sub-stage during bundle
      // composition (below) and reuses it to resolve the per-sub-stage runner kind for the dispatch
      // request. Null for INVESTIGATION (the spec stage has no execution sub-stage).
      ExecutionSubStage executionSubStage = null;
      RepositoryContextSummary repositoryContextSummary = null;
      // Story 3.10 — carried out of the try so the dispatch request (below) can mount
      // /workspace/repo
      // + push the deterministic branch for the EXECUTION (pr-output) path. Null for INVESTIGATION
      // and no-repo dispatches, keeping those requests byte-identical to today.
      String resolvedTicketRef = null;
      String repositoryBranchRef = null;
      try {
        if (resolvedRepositoryRef != null) {
          // Materialize-then-compose (Decision D1): clone the workspace (idempotent — the later
          // DockerRunnerAdapter clone reuses this {rex}/repo, Trap T8/T14) and summarize it BEFORE
          // composing the bundle so real repo content can be embedded. For EXECUTION the run's
          // ticketRef drives the deterministic branch (story 3.9 AC2) so captureAndPush pushes it;
          // the spec stage neither pushes nor opens a PR (null ticketRef -> no-ticket branch).
          if (stage == RunnerStage.EXECUTION) {
            resolvedTicketRef = resolveExecutionTicketRef(workflowRunId);
          }
          RepositoryWorkspaceService.RepositoryMount mount =
              repositoryWorkspaceService.prepareWorkspace(
                  workflowRunId,
                  stage,
                  reservedRexId,
                  resolvedTicketRef,
                  null,
                  resolvedRepositoryRef);
          repositoryContextSummary =
              repositoryWorkspaceService.summarize(
                  mount, RepositoryWorkspaceService.configMappingVersion(resolvedRepositoryRef));
          repositoryBranchRef = mount.branch();
          log.info(
              "dispatch repo-context resolved workflowRunId={} stage={} repositoryRef={} branchRef={} contextBundleVersion={}",
              workflowRunId,
              stage.value(),
              resolvedRepositoryRef,
              repositoryBranchRef,
              nextContextBundleVersion);
        } else if (repoContextStage) {
          log.info(
              "dispatch repo-context skipped workflowRunId={} stage={} reason={}",
              workflowRunId,
              stage.value(),
              repositoryWorkspaceService == null ? "no_workspace_service" : "no_repo_resolved");
        }

        // Story 3a-1 (AC1c) — the spec-investigation stage assembles its bundle via the dedicated
        // createForSpecInvestigation composition (null approved-spec, prior spec-rejection
        // feedback, prior spec versions — story 2.8 baseline; story 3a-2 additionally embeds the
        // nullable repo-context summary). The EXECUTION stage (story 3.10) derives the sub-stage
        // from run state (Decision D4) and composes the sub-stage-aware + repo-aware bundle.
        if (stage == RunnerStage.INVESTIGATION) {
          bundle =
              contextBundleService.createForSpecInvestigation(
                  workflowRunId,
                  reservedRexId,
                  nextContextBundleVersion,
                  constraints,
                  DataClassification.SHAREABLE_REDACTED,
                  actor,
                  repositoryContextSummary);
        } else {
          executionSubStage = contextBundleService.deriveExecutionSubStage(workflowRunId);
          log.info(
              "dispatch execution sub-stage derived workflowRunId={} subStage={} repoContextPresent={}",
              workflowRunId,
              executionSubStage,
              repositoryContextSummary != null);
          bundle =
              contextBundleService.create(
                  workflowRunId,
                  stage,
                  reservedRexId,
                  nextContextBundleVersion,
                  constraints,
                  DataClassification.SHAREABLE_REDACTED,
                  actor,
                  executionSubStage,
                  repositoryContextSummary,
                  repositoryBranchRef);
        }
      } catch (GitCommandException gitError) {
        // OQ-5 — clone/summarize failure during composition fails the dispatch (never degrades to a
        // no-repo bundle); orchestration then drives the run to Failed. Surface as a
        // DomainException
        // on the existing dispatch bundle-rejection path. Reuse INTERNAL_ERROR (no new error code,
        // dodging the three-sites manifest fan-out); the git category is carried in details.
        idempotencyService.complete(idempotencyKey, reservedRexId, IdempotencyRecordStatus.FAILED);
        log.warn(
            "dispatch repo-context preparation failed workflowRunId={} stage={} runnerExecutionId={} gitFailureCategory={}",
            workflowRunId,
            stage.value(),
            reservedRexId,
            gitError.failureCategory().value());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", workflowRunId);
        details.put("runnerExecutionId", reservedRexId);
        details.put("stage", stage.value());
        details.put("repositoryRef", resolvedRepositoryRef);
        details.put("gitFailureCategory", gitError.failureCategory().value());
        throw new DomainException(
            DomainErrorCode.INTERNAL_ERROR,
            "Repository workspace preparation failed during dispatch",
            details);
      } catch (DomainException error) {
        idempotencyService.complete(idempotencyKey, reservedRexId, IdempotencyRecordStatus.FAILED);
        log.warn(
            "dispatch context-bundle rejected workflowRunId={} stage={} runnerExecutionId={} errorCode={}",
            workflowRunId,
            stage.value(),
            reservedRexId,
            error.errorCode().value());
        throw error;
      } catch (RuntimeException unexpected) {
        // Story 3a-2 review — the repo-context prep (clone + summarize) now runs inside this try
        // after the idempotency key is reserved. Any unchecked failure that is NOT a
        // GitCommandException or DomainException (e.g. an NPE in the workspace seam) must still
        // complete the reservation FAILED, otherwise the record is stranded RESERVED and a later
        // same-key dispatch replays to a lost rex. Complete FAILED, then rethrow as INTERNAL_ERROR
        // preserving the original cause.
        idempotencyService.complete(idempotencyKey, reservedRexId, IdempotencyRecordStatus.FAILED);
        log.error(
            "dispatch failed unexpectedly during bundle composition workflowRunId={} stage={} runnerExecutionId={}",
            workflowRunId,
            stage.value(),
            reservedRexId,
            unexpected);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", workflowRunId);
        details.put("runnerExecutionId", reservedRexId);
        details.put("stage", stage.value());
        throw new DomainException(
            DomainErrorCode.INTERNAL_ERROR,
            "Unexpected failure during context-bundle composition",
            details,
            unexpected);
      }

      // Story 3.2 AC8 (OQ-4): emit RUNNER_STARTED only when the active adapter does NOT take
      // over the dispatch-event family. Docker adapter takes over via RUNNER_DISPATCHED emitted
      // post-ack (below) so the audit trail stays single-source.
      boolean adapterTakesOverDispatchEvent =
          runnerAdapter instanceof RecoverableRunnerAdapter recoverable
              && recoverable.emitsDispatchedAfterAck();
      RunnerExecutionSnapshot inserted =
          dispatchTransactionTemplate.execute(
              status -> {
                RunnerExecutionSnapshot row =
                    recordPort.insertPending(
                        reservedRexId, workflowRunId, stage, nextContextBundleVersion, constraints);
                if (!adapterTakesOverDispatchEvent) {
                  Map<String, Object> details = new LinkedHashMap<>();
                  details.put("runnerExecutionId", row.publicId());
                  details.put("stage", stage.value());
                  details.put("contextBundleVersion", row.contextBundleVersion());
                  details.put("timeoutAt", row.timeoutAt().toString());
                  details.put("idempotencyKey", idempotencyKey);
                  eventPort.append(
                      workflowRunId,
                      WorkflowEventType.RUNNER_STARTED,
                      actor,
                      "runner_dispatched",
                      null,
                      OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
                      details);
                }
                return row;
              });

      idempotencyService.complete(idempotencyKey, reservedRexId, IdempotencyRecordStatus.COMPLETED);
      java.nio.file.Path bundlePath =
          scratchStore.writeContextBundle(reservedRexId, bundle.redactedPayload());

      // Story 3a-1 (AC10) — resolve the runner-image kind per stage (spec-investigation honors
      // deliveryline.runner.spec-stage.kind). Story 3.12 (AC1 / Task 5, closes 3.11 OQ-4) — for the
      // EXECUTION stage resolve the kind from the derived sub-stage so the pr-output sub-stage
      // honors
      // deliveryline.runner.implementation-stage.kind while implementation-plan honors
      // plan-stage.kind
      // (both default codex; they MUST stay equal so the story-3.5 secret-scan, keyed on
      // kindForStage(EXECUTION)=plan-stage.kind, stays consistent — see kindForExecutionSubStage).
      // Story 3a-2 (AC7, Trap T14) + story 3.10 (Task 4) — thread the resolved repositoryRef so
      // DockerRunnerAdapter mounts /workspace/repo and idempotently reuses the broker's clone, plus
      // the EXECUTION ticketRef so the adapter checks out the same deterministic branch the broker
      // prepared and captureAndPush pushes it. Both null for every no-repo dispatch (and ticketRef
      // null for INVESTIGATION), keeping those requests byte-identical to today.
      org.dradgo.domain.registry.RunnerKind dispatchKind =
          stage == RunnerStage.EXECUTION
              ? runnerProperties.kindForExecutionSubStage(executionSubStage)
              : runnerProperties.kindForStage(stage);
      RunnerDispatchRequest request =
          new RunnerDispatchRequest(
              reservedRexId,
              workflowRunId,
              stage,
              dispatchKind,
              bundlePath,
              constraints,
              bundle.effectiveClassification(),
              resolvedRepositoryRef,
              resolvedTicketRef);
      RunnerDispatchAck ack = runnerAdapter.dispatch(request);

      // Story 3.2 AC8: emit RUNNER_DISPATCHED on the docker path (replaces the legacy
      // RUNNER_STARTED). Mock path keeps the existing RUNNER_STARTED event (emitted above inside
      // the dispatchTransactionTemplate) — see OQ-4. Path discriminated by adapter ref prefix so
      // the broker stays adapter-type-agnostic.
      String runnerDispatchedEventPublicId =
          appendRunnerDispatchedEventIfDocker(
              workflowRunId, reservedRexId, request.runnerKind(), ack, actor);

      log.info(
          "dispatch ok workflowRunId={} stage={} runnerExecutionId={} contextBundleVersion={} adapterRef={}",
          workflowRunId,
          stage.value(),
          reservedRexId,
          nextContextBundleVersion,
          ack.adapterRef());
      return new RunnerDispatchResult.Dispatched(
          toHandle(inserted), ack, runnerDispatchedEventPublicId);
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 3.10 (OQ-1) — best-effort resolution of the run's Linear ticketRef for the
   * EXECUTION-stage deterministic branch. Returns null when no provider is wired (the
   * package-private test ctors) or when the lookup yields nothing / throws — the caller then
   * prepares a no-ticket branch (logged WARN) rather than failing the dormant seam. Never logs the
   * ticket payload.
   */
  private String resolveExecutionTicketRef(String workflowRunId) {
    if (ticketSummaryProvider == null) {
      return null;
    }
    try {
      TicketSummary ticket = ticketSummaryProvider.fetchByWorkflowRun(workflowRunId);
      String ticketRef = ticket == null ? null : ticket.ticketRef();
      if (ticketRef == null || ticketRef.isBlank()) {
        log.warn(
            "dispatch execution ticketRef unresolved workflowRunId={} reason=no_ticket_ref",
            workflowRunId);
        return null;
      }
      return ticketRef;
    } catch (RuntimeException resolutionFailure) {
      log.warn(
          "dispatch execution ticketRef resolution failed workflowRunId={} cause={}",
          workflowRunId,
          resolutionFailure.getClass().getSimpleName());
      return null;
    }
  }

  // =====================================================================
  // onResult
  // =====================================================================

  public void onResult(String runnerExecutionId, byte[] payloadBytes) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Objects.requireNonNull(payloadBytes, "payloadBytes");
    if (payloadBytes.length == 0) {
      throw new IllegalArgumentException("payloadBytes must not be empty");
    }

    RunnerExecutionSnapshot row =
        recordPort
            .findByPublicId(runnerExecutionId)
            .orElseThrow(() -> runnerExecutionNotFound(runnerExecutionId));
    String workflowRunId = row.workflowRunPublicId();
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {

      // AC5 split (late-result branch): a row that has already been marked TIMED_OUT or ORPHANED
      // cannot legally transition further. A result arriving now is, by definition, late — emit
      // RUNNER_FAILED with runner_late_result and forward artifact references via
      // ArtifactOperationService (which already marks them late_or_stale per 1.12 AC10), but
      // do NOT change the workflow-run state. Operator-driven recovery owns that path.
      if (row.status() == RunnerExecutionStatus.TIMED_OUT
          || row.status() == RunnerExecutionStatus.ORPHANED) {
        handleLateResult(runnerExecutionId, workflowRunId, row, payloadBytes);
        return;
      }

      ValidationContext validationContext =
          buildResultValidationContext(workflowRunId, runnerExecutionId);
      ValidationResult result =
          contractValidator.validate(
              ValidationTarget.RUNNER_RESULT, payloadBytes, validationContext);

      if (!result.valid()) {
        FailureCategory category = classifyValidationFailure(result.errors());
        handleFailedValidation(
            runnerExecutionId, workflowRunId, row, category, result, payloadBytes);
        return;
      }

      JsonNode parsed;
      try {
        parsed = objectMapper.readTree(payloadBytes);
      } catch (java.io.IOException error) {
        handleFailedValidation(
            runnerExecutionId,
            workflowRunId,
            row,
            FailureCategory.RUNNER_MALFORMED_OUTPUT,
            new ValidationResult(
                false,
                List.of(
                    new ValidationError(
                        ValidationErrorCode.JSON_PARSE_FAILED, "$", error.getMessage()))),
            payloadBytes);
        return;
      }

      JsonNode failureCategoryNode = parsed.get("failureCategory");
      if (failureCategoryNode != null
          && failureCategoryNode.isTextual()
          && !failureCategoryNode.asText().isBlank()) {
        handleNonZeroExit(runnerExecutionId, workflowRunId, failureCategoryNode.asText());
        return;
      }

      handleSuccess(runnerExecutionId, workflowRunId, row, parsed);
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private void handleSuccess(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      JsonNode parsed) {
    JsonNode artifactRefs = parsed.path("artifactReferences");
    if (!artifactRefs.isArray() || artifactRefs.isEmpty()) {
      handleFailedValidation(
          runnerExecutionId,
          workflowRunId,
          row,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          new ValidationResult(
              false,
              List.of(
                  new ValidationError(
                      ValidationErrorCode.SCHEMA_VALIDATION_FAILED,
                      "/artifactReferences",
                      "missing or empty"))),
          new byte[] {0});
      return;
    }

    // Story 3a-1 (AC7 / Trap T6): do NOT mint a fresh random correlationId. Thread the originating
    // correlationId from the MDC scope established by the dispatching command when present, so the
    // artifact events and the spec-ready transition below share it; fall back to a deterministic
    // execution-scoped id otherwise (the async poller thread carries no originating correlationId,
    // and persisting it on the runner-execution row would require a migration this story forbids).
    String correlationId = resolveOutcomeCorrelationId(runnerExecutionId);

    // Story 3a-1 (AC8) review patch: validate EVERY referenced artifact type against the stage's
    // allowed set BEFORE ingesting any of them. The per-ref guard previously sat INSIDE the ingest
    // loop, so an earlier valid artifact was recorded and only THEN a later ref's type mismatch
    // drove the run to FAILED — leaving a half-ingested artifact lineage on a failed run.
    // Pre-scanning
    // keeps the contract check atomic with respect to the artifact writes below.
    // INVESTIGATION -> spec; EXECUTION -> implementationPlan / prOutput; any other type is a
    // runner-side contract violation routed to Failed (the broker previously accepted any type).
    for (JsonNode ref : artifactRefs) {
      ArtifactType refType =
          ArtifactType.fromValue(
              ref.path("artifactType").asText(), "runner_result.artifactReferences.artifactType");
      if (!allowedArtifactTypesForStage(row.stage()).contains(refType)) {
        handleArtifactTypeMismatch(runnerExecutionId, workflowRunId, row, refType);
        return;
      }
    }

    boolean artifactIngestionFailed = false;
    // Story 3.12 (AC3 / Decision D3) — capture the ingested prOutput artifact id so the pr-output
    // enrichment below can update its payload with the actual captureAndPush refs (follow-on
    // update,
    // keyed by artifact id; does not reorder the shared spec/plan ingest path). Stays null on every
    // non-pr-output result.
    String prOutputArtifactId = null;
    for (JsonNode ref : artifactRefs) {
      String typeValue = ref.path("artifactType").asText();
      ArtifactType artifactType =
          ArtifactType.fromValue(typeValue, "runner_result.artifactReferences.artifactType");
      Optional<ArtifactPayload> maybePayload = artifactPayload(runnerExecutionId, ref);
      if (maybePayload.isEmpty()) {
        String contentReference = ref.path("contentReference").asText(null);
        handleFailedValidation(
            runnerExecutionId,
            workflowRunId,
            row,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            new ValidationResult(
                false,
                List.of(
                    new ValidationError(
                        ValidationErrorCode.PATH_TRAVERSAL_DETECTED,
                        "/artifactReferences/contentReference",
                        "unreadable or escaping reference: " + contentReference))),
            new byte[] {0});
        return;
      }
      ArtifactPayload payload = maybePayload.get();
      String idempotencyKey =
          "runner-result:" + runnerExecutionId + ":" + ref.path("artifactId").asText();
      RecordArtifactOperationCommand command =
          new RecordArtifactOperationCommand(
              workflowRunId,
              artifactType,
              ArtifactOperationType.CREATE,
              idempotencyKey,
              payload.payloadRef(),
              payload.bytes(),
              "system",
              org.dradgo.domain.registry.ActorType.SYSTEM,
              correlationId,
              runnerExecutionId);
      RecordArtifactOperationResult opResult = artifactOperationService.recordOperation(command);
      if (opResult.isFailure()) {
        log.warn(
            "onResult artifact-record failed runnerExecutionId={} artifactType={} reason={}",
            runnerExecutionId,
            typeValue,
            opResult.failure());
        artifactIngestionFailed = true;
        break;
      }
      if (artifactType == ArtifactType.PR_OUTPUT) {
        prOutputArtifactId = opResult.artifact().publicId();
      }
      // Spec-stage availability wiring — promote the freshly-ingested SPEC artifact to `available`
      // (checksum + storageRef stamped) so the human spec-approval gate accepts it. Without this
      // the
      // artifact stays `pending` and ArtifactService.isApprovalEligible (which requires
      // status=AVAILABLE) rejects approval with ARTIFACT_PAYLOAD_UNAVAILABLE — the reviewer can see
      // the spec but never approve it. The auto-advance to WaitingForSpecApproval (Decision D1)
      // still
      // fires on ingest regardless; this only makes the resulting artifact approval-eligible.
      // Scoped
      // to SPEC: the plan/pr-output stages auto-advance with no human approval gate, so they keep
      // the
      // deliberate `pending` ingest behaviour.
      if (artifactType == ArtifactType.SPEC) {
        markSpecArtifactAvailable(opResult, payload.bytes(), correlationId);
      }
    }

    if (artifactIngestionFailed) {
      // The runner produced a schema-valid result but at least one referenced artifact
      // could not be ingested. AC5 classifies this as a runner-side contract violation:
      // runner_contract_violation drives workflow run to FAILED.
      executionService.recordFailed(runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION);
      appendRunnerFailedEvent(
          workflowRunId,
          runnerExecutionId,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          "artifact_ingestion_failed",
          ActorContext.SYSTEM);
      driveWorkflowFailed(
          workflowRunId,
          runnerExecutionId,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          "artifact ingestion failed");
      return;
    }

    // Story 3.5 AC4/AC5: post-execution workspace secret scan. Runs AFTER artifact harvest and
    // immediately BEFORE recordCompleted — a leaked-secret execution must NEVER reach the completed
    // branch. Two detectors (known-shape via RedactionPolicyService + mandatory literal-substring
    // of
    // the injected provider key). On a hit: record the execution FAILED with runner_secret_leak,
    // emit RUNNER_FAILED with leakedFile + category names (never a value), quarantine the workspace
    // (Trap T10), and return. Per AC4 this does NOT drive the workflow-run state (the
    // EXECUTING→FAILED
    // transition allowlist is deliberately narrow — story 1.13 — and leak handling is scoped to the
    // execution + event + quarantine); operator-driven recovery owns the run-state path.
    // Story 3.5 review note (defaultKind coupling): the scan re-resolves the injected key for
    // runnerProperties.docker().defaultKind(), which is the SAME source the dispatch path uses when
    // building the RunnerDispatchRequest (see :288 above) — so the substring detector compares
    // against exactly the key this dispatch injected. This holds ONLY because dispatch is currently
    // hardwired to defaultKind(); RunnerExecutionSnapshot carries no runnerKind (the runner_kind
    // column is deferred to story 3-19/4-9 per 3.1 OQ-7). When per-run kind selection lands
    // (stories
    // 3.3/3.4), resolve the kind from the execution row (or the deliveryline.runnerKind container
    // label) here instead of defaultKind() so the detector cannot compare against the wrong key.
    // Story 3a-1 (AC10) — resolve the scanned key against the SAME kind the dispatch injected
    // (kindForStage), not the unconditional docker().defaultKind(). For an INVESTIGATION-stage run
    // whose configured spec-stage.kind differs from defaultKind, the 3.5 secret detector must look
    // for the key that was actually injected, otherwise it would scan for the wrong provider key.
    org.dradgo.domain.registry.RunnerKind runnerKind = runnerProperties.kindForStage(row.stage());
    RunnerSecretScanService.ScanOutcome secretScan =
        runnerSecretScanService.scanWorkspace(
            runnerExecutionId, runnerKind, row.stage(), workflowRunId);
    if (secretScan.leakDetected()) {
      // Story 3.5 review patch: quarantine FIRST (best-effort) so the leaked workspace is preserved
      // even if the terminal transition below races, and so a duplicate onResult re-entering this
      // branch re-asserts the marker before the guarded recordFailed short-circuits. A marker-write
      // failure must NOT prevent recording the leak as FAILED — log and continue.
      try {
        runnerSecretScanService.quarantine(
            runnerExecutionId,
            "leakedFile="
                + secretScan.leakedFile()
                + " categories="
                + secretScan.detectedCategories());
      } catch (RuntimeException quarantineFailure) {
        log.error(
            "onResult secret-leak quarantine-marker write failed runnerExecutionId={} workflowRunId={} leakedFile={}",
            runnerExecutionId,
            workflowRunId,
            secretScan.leakedFile(),
            quarantineFailure);
      }
      // Story 3.2a AC9 parity: a duplicate onResult (recovery scratch-replay / concurrent harvest)
      // on an already-terminal row re-runs the scan and reaches here again; recordFailed then
      // throws
      // ILLEGAL_TRANSITION. Guard it exactly like the recordCompleted gate below so re-entry is a
      // no-op instead of an uncaught error and the RUNNER_FAILED leak event fires at most once.
      try {
        executionService.recordFailed(runnerExecutionId, FailureCategory.RUNNER_SECRET_LEAK);
      } catch (DomainException raceTerminal) {
        if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
          throw raceTerminal;
        }
        log.info(
            "onResult secret-leak skip runnerExecutionId={} reason=already_terminal errorCode={}",
            runnerExecutionId,
            raceTerminal.errorCode().value());
        return;
      }
      appendRunnerSecretLeakEvent(
          workflowRunId,
          runnerExecutionId,
          secretScan.leakedFile(),
          secretScan.detectedCategories());
      log.warn(
          "onResult secret-leak runnerExecutionId={} workflowRunId={} leakedFile={} categories={}",
          runnerExecutionId,
          workflowRunId,
          secretScan.leakedFile(),
          secretScan.detectedCategories());
      return;
    }

    // Story 3.9 (Decision D0 / OQ-2): if this run had a repo workspace, commit the runner-produced
    // changes, push the branch, and open/update the PR BEFORE marking the execution completed. A
    // push rejection surfaces as a typed GitCommandException (AC7) which we map onto the EXISTING
    // runner-failure transition path (RUNNER_FAILED + driveWorkflowFailed) — never auto-rebasing,
    // force-pushing, or retrying (T8). captureAndPush is a no-op (returns empty) for non-repo runs,
    // and repositoryWorkspaceService is null in the fast-tier unit ctor, so mock/no-repo dispatches
    // are byte-for-byte unchanged. (Re-homing the failure transition to
    // WorkflowOrchestrationService
    // is story 3a-1, OQ-2.)
    // Story 3.12 (AC2 / Trap T4): STOP discarding the captureAndPush return — its
    // RepositoryPushOutcome (commitSha + branchRef + prRef) is the source of the ACTUAL git/GitHub
    // state the pr-output enrichment + ref-validation below compare against. Empty for non-repo /
    // clean-worktree runs (the no-repo dispatch is byte-identical to today).
    Optional<RepositoryWorkspaceService.RepositoryPushOutcome> pushOutcome = Optional.empty();
    if (repositoryWorkspaceService != null) {
      try {
        pushOutcome = repositoryWorkspaceService.captureAndPush(runnerExecutionId);
      } catch (GitCommandException pushFailure) {
        log.warn(
            "onResult git push rejected runnerExecutionId={} workflowRunId={} category={}",
            runnerExecutionId,
            workflowRunId,
            pushFailure.failureCategory().value());
        try {
          executionService.recordFailed(
              runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION);
        } catch (DomainException raceTerminal) {
          if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
            throw raceTerminal;
          }
          log.info(
              "onResult git-push-failed skip runnerExecutionId={} reason=already_terminal",
              runnerExecutionId);
          return;
        }
        appendGitPushFailedEvent(workflowRunId, runnerExecutionId, pushFailure);
        driveWorkflowFailed(
            workflowRunId,
            runnerExecutionId,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            "git push rejected: " + pushFailure.failureCategory().value());
        return;
      }
    }

    // Story 3.12 (AC1 / Task 3) — derive the EXECUTION sub-stage ONCE here (reused by the pr-output
    // validation/enrichment below AND by the success delegation at the bottom). Null for
    // non-EXECUTION
    // stages. The sub-stage is run-level (driven by "an approved plan exists") and stable
    // mid-execution, so a single derivation is authoritative for both uses.
    ExecutionSubStage executionSubStage =
        row.stage() == RunnerStage.EXECUTION
            ? contextBundleService.deriveExecutionSubStage(workflowRunId)
            : null;

    // Story 3.12 (AC3/AC9, Task 3) — for the pr-output sub-stage: validate the runner-reported
    // branch/commitSha/prReference against the documented formats (AC9 — untrusted runner output,
    // story 2.24), then against the actual captureAndPush state (AC3 drift), then enrich the
    // ingested
    // prOutput artifact with the actual refs. Anchored AFTER captureAndPush (a push failure already
    // routed to Failed above) and BEFORE recordCompleted, so a validation/drift failure fails a
    // still-RUNNING execution via the existing contract-violation drive — exactly like the git-push
    // path — instead of advancing to WaitingForReview.
    if (executionSubStage == ExecutionSubStage.PR_OUTPUT
        && !validateAndEnrichPrOutput(
            runnerExecutionId,
            workflowRunId,
            row,
            parsed,
            pushOutcome,
            prOutputArtifactId,
            correlationId)) {
      return; // already drove the run to Failed via the contract-violation path
    }

    // Story 3.2a AC9: gate the RUNNER_COMPLETED append on the row ACTUALLY transitioning. A
    // duplicate onResult (recovery scratch-replay re-entering, or a concurrent harvest) on an
    // already-completed row throws ILLEGAL_TRANSITION here — skip the append so the lifecycle event
    // is emitted at most once per completion.
    try {
      executionService.recordCompleted(runnerExecutionId);
    } catch (DomainException raceTerminal) {
      if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw raceTerminal;
      }
      log.info(
          "onResult complete skip runnerExecutionId={} reason=already_terminal errorCode={}",
          runnerExecutionId,
          raceTerminal.errorCode().value());
      return;
    }
    // Story 3.2 AC8: emit RUNNER_COMPLETED so the happy-path success has a dedicated audit-trail
    // entry instead of being inferred from the workflow-state transition only. Exit code is not
    // surfaced on the success path (the runner contract considers any payload with valid
    // artifactReferences a successful completion); operators trace failures via RUNNER_FAILED.
    appendRunnerCompletedEvent(workflowRunId, runnerExecutionId, null);
    log.info(
        "onResult success runnerExecutionId={} workflowRunId={} artifactCount={}",
        runnerExecutionId,
        workflowRunId,
        artifactRefs.size());

    // Story 3a-1 (AC2/AC3 — the central gap): once the spec artifact for an INVESTIGATION-stage
    // execution is available + the execution is COMPLETED, delegate the terminal outcome to
    // WorkflowOrchestrationService so it auto-advances Investigating -> WaitingForSpecApproval. The
    // broker harvests the result + owns runner-lifecycle events; orchestration owns the workflow
    // state transition (AC9 / Trap T10). Guarded by stage so non-spec stages are unaffected, and by
    // a present orchestration bean so the package-private test ctors / lean contexts are unchanged.
    if (row.stage() == RunnerStage.INVESTIGATION) {
      org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
          workflowOrchestrationServiceSupplier.get();
      if (orchestration != null) {
        orchestration.onSpecStageSucceeded(workflowRunId, runnerExecutionId, correlationId);
      }
    } else if (row.stage() == RunnerStage.EXECUTION) {
      // Story 3.11 / 3.12 (AC2/AC3/AC5 — the central gap): the EXECUTION twin of the INVESTIGATION
      // branch. Once the implementation-plan OR pr-output artifact is ingested + the execution is
      // COMPLETED (and any captureAndPush above succeeded + the pr-output refs validated — anchored
      // AFTER both so a push/validation failure routes to Failed instead of advancing), delegate
      // the
      // ready auto-advance (Executing -> WaitingForReview) to WorkflowOrchestrationService via the
      // SAME lazy supplier (the broker↔orchestration cycle is already broken, Trap T2 — no new
      // wiring). The artifact stays `pending` — markAvailable is unwired system-wide
      // ([[markavailable-has-no-production-caller]]); the transition fires on successful INGEST per
      // Decision D1, exactly as the spec stage does. The sub-stage was derived once above.
      org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
          workflowOrchestrationServiceSupplier.get();
      if (executionSubStage == ExecutionSubStage.IMPLEMENTATION_PLAN) {
        if (orchestration != null) {
          orchestration.onPlanStageSucceeded(workflowRunId, runnerExecutionId, correlationId);
        }
      } else if (executionSubStage == ExecutionSubStage.PR_OUTPUT) {
        // Story 3.12 (AC5) — the pr-output twin: auto-advance Executing -> WaitingForReview with
        // reason pr_output_ready. This closes the absorbing-state `else` branch story 3.11 left.
        if (orchestration != null) {
          orchestration.onPrOutputStageSucceeded(workflowRunId, runnerExecutionId, correlationId);
        }
      } else {
        log.info(
            "onResult execution success not auto-advanced runnerExecutionId={} workflowRunId={} "
                + "subStage={} reason=unknown_substage",
            runnerExecutionId,
            workflowRunId,
            executionSubStage);
      }
    }
  }

  /**
   * Story 3a-1 (AC8) — the artifact types a dispatching stage is permitted to emit. INVESTIGATION
   * (spec stage) may only produce a {@code spec}; EXECUTION may produce an {@code
   * implementationPlan} or {@code prOutput}. (OQ-5: the mapping lives in the broker, where the
   * result is parsed.)
   */
  private static java.util.Set<ArtifactType> allowedArtifactTypesForStage(RunnerStage stage) {
    return switch (stage) {
      case INVESTIGATION -> java.util.EnumSet.of(ArtifactType.SPEC);
      case EXECUTION ->
          java.util.EnumSet.of(ArtifactType.IMPLEMENTATION_PLAN, ArtifactType.PR_OUTPUT);
    };
  }

  /**
   * Story 3a-1 (AC8) — record the execution failed, emit a precise {@code RUNNER_FAILED} carrying
   * the {@code RUNNER_ARTIFACT_TYPE_MISMATCH} typed surface (the REST/inspection error code) plus
   * the expected vs actual artifact type, and drive the run to Failed via the existing
   * runner-contract-violation path. Never logs the artifact payload.
   */
  private void handleArtifactTypeMismatch(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      ArtifactType actualType) {
    java.util.Set<ArtifactType> allowed = allowedArtifactTypesForStage(row.stage());
    log.warn(
        "onResult artifact-type mismatch runnerExecutionId={} workflowRunId={} stage={} "
            + "expectedTypes={} actualType={} errorCode={}",
        runnerExecutionId,
        workflowRunId,
        row.stage().value(),
        allowed.stream().map(ArtifactType::value).toList(),
        actualType.value(),
        DomainErrorCode.RUNNER_ARTIFACT_TYPE_MISMATCH.value());
    // Story 3a-1 review patch: a duplicate/late result for an already-terminal execution must not
    // re-emit RUNNER_FAILED or re-drive the FAILED transition (at-most-once failure semantics,
    // mirroring the guarded secret-leak and git-push paths). The first call already recorded it;
    // previously only recordFailed was guarded while the event append + driveWorkflowFailed below
    // ran unconditionally, producing duplicate failure events on result replay.
    if (isTerminal(row.status())) {
      log.debug(
          "onResult artifact-type mismatch ignored for already-terminal execution "
              + "runnerExecutionId={} status={}",
          runnerExecutionId,
          row.status());
      return;
    }
    executionService.recordFailed(runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(org.dradgo.domain.registry.WorkflowEventDetailKeys.WORKFLOW_RUN_ID, workflowRunId);
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("failureCategory", FailureCategory.RUNNER_CONTRACT_VIOLATION.value());
    details.put("errorCode", DomainErrorCode.RUNNER_ARTIFACT_TYPE_MISMATCH.value());
    details.put("stage", row.stage().value());
    details.put("expectedArtifactTypes", allowed.stream().map(ArtifactType::value).toList());
    details.put("actualArtifactType", actualType.value());
    details.put("reason", "runner_artifact_type_mismatch");
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_FAILED,
        ActorContext.SYSTEM,
        "runner_artifact_type_mismatch",
        FailureCategory.RUNNER_CONTRACT_VIOLATION,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
    driveWorkflowFailed(
        workflowRunId,
        runnerExecutionId,
        FailureCategory.RUNNER_CONTRACT_VIOLATION,
        "runner artifact type mismatch: expected "
            + allowed.stream().map(ArtifactType::value).toList()
            + " got "
            + actualType.value());
  }

  // Story 3.12 (AC9) — documented format patterns for the UNTRUSTED runner-reported pr-output refs
  // (story 2.24). diffReference is out of scope (no source on RepositoryPushOutcome). A canonical
  // GitHub PR URL or the system's own PR-<n> shorthand are both accepted for prReference.
  private static final java.util.regex.Pattern PR_OUTPUT_BRANCH_PATTERN =
      java.util.regex.Pattern.compile("^[a-zA-Z0-9._/-]+$");
  private static final java.util.regex.Pattern PR_OUTPUT_COMMIT_SHA_PATTERN =
      java.util.regex.Pattern.compile("^[0-9a-f]{7,40}$");
  private static final java.util.regex.Pattern PR_OUTPUT_PR_REF_SHORTHAND_PATTERN =
      java.util.regex.Pattern.compile("^PR-\\d+$");
  private static final java.util.regex.Pattern PR_OUTPUT_PR_REF_URL_PATTERN =
      java.util.regex.Pattern.compile(
          "^https://github\\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/pull/\\d+$");

  /**
   * Story 3.12 (AC3/AC9, Task 3) — for the pr-output sub-stage: validate the runner-reported
   * branch/commitSha/prReference against the documented formats (AC9 — untrusted runner output),
   * then against the actual {@code captureAndPush} state (AC3 drift), then enrich the ingested
   * prOutput artifact with the actual refs (Decision D3 — follow-on UPDATE keyed by artifact id).
   * Returns {@code true} to continue the success path; {@code false} when it has already driven the
   * run to {@code Failed} via the existing contract-violation path (the caller then returns without
   * completing the execution).
   *
   * <p>Never logs payload bytes or diffs; branch / commitSha / PR ref are non-secret git/GitHub
   * identifiers.
   */
  private boolean validateAndEnrichPrOutput(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      JsonNode parsed,
      Optional<RepositoryWorkspaceService.RepositoryPushOutcome> pushOutcome,
      String prOutputArtifactId,
      String correlationId) {
    JsonNode prRef = prOutputArtifactReference(parsed);
    String reportedBranch = textOrNull(prRef, "branch");
    String reportedCommitSha = textOrNull(prRef, "commitSha");
    String reportedPrReference = textOrNull(prRef, "prReference");

    // AC9 — reject malformed runner-reported refs before trusting them for drift/enrichment.
    String formatError =
        prOutputFormatError(reportedBranch, reportedCommitSha, reportedPrReference);
    if (formatError != null) {
      log.warn(
          "onResult pr-output ref format invalid runnerExecutionId={} workflowRunId={} field={} "
              + "errorCode={}",
          runnerExecutionId,
          workflowRunId,
          formatError,
          DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED.value());
      handlePrOutputContractFailure(
          runnerExecutionId,
          workflowRunId,
          row,
          DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED,
          "runner_output_validation_failed",
          "malformed runner-reported pr-output ref: " + formatError);
      return false;
    }

    if (pushOutcome.isPresent()) {
      RepositoryWorkspaceService.RepositoryPushOutcome actual = pushOutcome.get();
      // AC3 — the runner-reported values MUST match the actual git/GitHub state captureAndPush
      // produced (only compared when an actual value is present; prRef may be null — no repo ref).
      String driftField =
          prOutputDriftField(
              reportedBranch,
              reportedCommitSha,
              reportedPrReference,
              actual.branchRef(),
              actual.commitSha(),
              actual.prRef());
      if (driftField != null) {
        log.warn(
            "onResult pr-output ref drift runnerExecutionId={} workflowRunId={} field={} "
                + "errorCode={}",
            runnerExecutionId,
            workflowRunId,
            driftField,
            DomainErrorCode.RUNNER_PR_REF_DRIFT.value());
        handlePrOutputContractFailure(
            runnerExecutionId,
            workflowRunId,
            row,
            DomainErrorCode.RUNNER_PR_REF_DRIFT,
            "runner_pr_ref_drift",
            "runner-reported pr-output ref drifted from actual git/GitHub state: " + driftField);
        return false;
      }
      // Decision D3 — enrich the ingested prOutput artifact with the ACTUAL refs (follow-on
      // UPDATE).
      enrichPrOutputArtifact(
          runnerExecutionId, workflowRunId, prOutputArtifactId, prRef, actual, correlationId);
      // Story 3.15 (Task 7) — promote the dormant linkage seam: write the durable github_pr
      // integration_links row + integration.linked event from the AUTHORITATIVE captureAndPush refs
      // (never the untrusted runner-reported string — Trap T1). Best-effort: a linkage-only failure
      // must not unwind the committed runner outcome or block WaitingForReview.
      linkGitHubPrBestEffort(runnerExecutionId, workflowRunId, actual, correlationId);
    } else {
      log.debug(
          "onResult pr-output enrichment skipped runnerExecutionId={} workflowRunId={} "
              + "reason=no_push_outcome",
          runnerExecutionId,
          workflowRunId);
    }

    return true;
  }

  /**
   * Story 3.15 (Task 7) — write the durable {@code github_pr} integration_links row + the {@code
   * integration.linked} event from the authoritative {@code captureAndPush} outcome. Best-effort: a
   * linkage failure is logged and swallowed (it must not unwind the committed runner outcome or
   * block the {@code WaitingForReview} advance — mirrors the enrichment block); cross-run conflict
   * / repo-mismatch surface as {@code WARN} with the typed error code. No-op when no PR was created
   * (a no-repo dispatch returns a null {@code prRef}) or the linkage bean is absent (lean/mock
   * contexts). The broker is the sole caller of {@code linkGitHubPr} (Trap T12).
   */
  private void linkGitHubPrBestEffort(
      String runnerExecutionId,
      String workflowRunId,
      RepositoryWorkspaceService.RepositoryPushOutcome actual,
      String correlationId) {
    if (actual.prRef() == null) {
      log.debug(
          "onResult pr-output linkage skipped runnerExecutionId={} workflowRunId={} reason=no_pr_ref",
          runnerExecutionId,
          workflowRunId);
      return;
    }
    org.dradgo.application.integration.IntegrationLinkService linkService =
        integrationLinkServiceSupplier.get();
    if (linkService == null) {
      log.debug(
          "onResult pr-output linkage skipped runnerExecutionId={} workflowRunId={} "
              + "reason=no_link_service",
          runnerExecutionId,
          workflowRunId);
      return;
    }
    try {
      // repoRef is not carried on RepositoryPushOutcome; linkGitHubPr resolves it via the GitHub
      // adapter, and story 3.9 already enforced repo-compat at workspace prep (AC4 is
      // defense-in-depth). The idempotency key is supplied for signature fidelity — idempotency
      // comes from the same-run/same-ref no-op plus the V6 unique index.
      String idempotencyKey = "linkGitHubPr:" + workflowRunId + ":" + actual.prRef();
      linkService.linkGitHubPr(
          workflowRunId,
          actual.prRef(),
          null,
          actual.branchRef(),
          actual.commitSha(),
          new ActorContext("system", org.dradgo.domain.registry.ActorType.SYSTEM, correlationId),
          idempotencyKey);
      log.info(
          "onResult pr-output linked runnerExecutionId={} workflowRunId={} prRef={}",
          runnerExecutionId,
          workflowRunId,
          MdcKeys.sanitizeForLog(actual.prRef()));
    } catch (DomainException error) {
      log.warn(
          "onResult pr-output linkage failed runnerExecutionId={} workflowRunId={} errorCode={} "
              + "reason={}",
          runnerExecutionId,
          workflowRunId,
          error.errorCode().value(),
          error.getMessage());
    } catch (RuntimeException error) {
      log.warn(
          "onResult pr-output linkage failed runnerExecutionId={} workflowRunId={} cause={}",
          runnerExecutionId,
          workflowRunId,
          error.toString());
    }
  }

  private static JsonNode prOutputArtifactReference(JsonNode parsed) {
    for (JsonNode ref : parsed.path("artifactReferences")) {
      if (ArtifactType.PR_OUTPUT.value().equals(ref.path("artifactType").asText())) {
        return ref;
      }
    }
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private static String textOrNull(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    return (value == null || value.isBlank()) ? null : value;
  }

  /**
   * AC9 — returns the first malformed field name, or {@code null} when all present refs are valid.
   */
  private static String prOutputFormatError(String branch, String commitSha, String prReference) {
    if (branch != null && !PR_OUTPUT_BRANCH_PATTERN.matcher(branch).matches()) {
      return "branch";
    }
    if (commitSha != null && !PR_OUTPUT_COMMIT_SHA_PATTERN.matcher(commitSha).matches()) {
      return "commitSha";
    }
    if (prReference != null
        && !PR_OUTPUT_PR_REF_SHORTHAND_PATTERN.matcher(prReference).matches()
        && !PR_OUTPUT_PR_REF_URL_PATTERN.matcher(prReference).matches()) {
      return "prReference";
    }
    return null;
  }

  /** AC3 — returns the first drifted field name, or {@code null} when reported == actual. */
  private static String prOutputDriftField(
      String reportedBranch,
      String reportedCommitSha,
      String reportedPrReference,
      String actualBranch,
      String actualCommitSha,
      String actualPrRef) {
    if (actualBranch != null && reportedBranch != null && !actualBranch.equals(reportedBranch)) {
      return "branch";
    }
    if (actualCommitSha != null
        && reportedCommitSha != null
        && !actualCommitSha.equals(reportedCommitSha)) {
      return "commitSha";
    }
    if (actualPrRef != null
        && reportedPrReference != null
        && !actualPrRef.equals(reportedPrReference)) {
      return "prReference";
    }
    return null;
  }

  /**
   * Promote a freshly-ingested SPEC artifact to {@code available} so the human spec-approval gate
   * ({@code ArtifactService.isApprovalEligible}, which requires {@code status=AVAILABLE} plus a
   * populated checksum + storageRef) accepts it. The checksum is computed over the SAME payload
   * bytes the broker handed to {@code recordOperation}, and the storageRef is the canonical
   * location the payload store reported writing them to — so {@code markAvailable}'s
   * payload-vs-checksum verification is guaranteed to match. Runs inside the poller's per-item
   * transaction alongside the ingest, so the artifact is available before the spec-ready transition
   * is observable.
   *
   * <p>{@code storageRef} is {@code null} on an idempotent replay (a duplicate {@code onResult}
   * that did not re-write the payload); in that case the artifact was already made available by the
   * original ingest and there is nothing to re-stamp ({@code markAvailable} is itself idempotent).
   */
  private void markSpecArtifactAvailable(
      RecordArtifactOperationResult opResult, byte[] payloadBytes, String correlationId) {
    String storageRef = opResult.storageRef();
    if (storageRef == null) {
      return;
    }
    String checksumHex =
        ArtifactChecksum.digestHex(SPEC_CHECKSUM_ALGORITHM, payloadBytes)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "JVM does not provide required digest algorithm: "
                            + SPEC_CHECKSUM_ALGORITHM));
    artifactOperationService.markAvailable(
        opResult.artifact().publicId(),
        new ArtifactChecksum(SPEC_CHECKSUM_ALGORITHM, checksumHex),
        storageRef,
        new ActorContext("system", org.dradgo.domain.registry.ActorType.SYSTEM, correlationId));
  }

  /**
   * Story 3.12 (AC3 / Decision D3) — enrich the already-ingested prOutput artifact with the actual
   * branch/commitSha/prReference via a follow-on {@code UPDATE} keyed by the ingested artifact id.
   * Best-effort: a failure is logged and swallowed (it must not unwind the committed runner outcome
   * or block the {@code WaitingForReview} advance — the artifact simply keeps the runner-reported
   * values; markAvailable is unwired [[markavailable-has-no-production-caller]]).
   */
  private void enrichPrOutputArtifact(
      String runnerExecutionId,
      String workflowRunId,
      String prOutputArtifactId,
      JsonNode prRef,
      RepositoryWorkspaceService.RepositoryPushOutcome actual,
      String correlationId) {
    if (prOutputArtifactId == null || !prRef.isObject()) {
      log.warn(
          "onResult pr-output enrichment skipped runnerExecutionId={} workflowRunId={} "
              + "reason=no_ingested_artifact",
          runnerExecutionId,
          workflowRunId);
      return;
    }
    try {
      com.fasterxml.jackson.databind.node.ObjectNode enriched = prRef.deepCopy();
      // Story 3.12 review patch — only overwrite with a present actual ref; a null branch/commitSha
      // (e.g. a clean-worktree push outcome) must not clobber the runner-reported value with JSON
      // null (symmetric with the prReference guard below).
      if (actual.branchRef() != null) {
        enriched.put("branch", actual.branchRef());
      }
      if (actual.commitSha() != null) {
        enriched.put("commitSha", actual.commitSha());
      }
      if (actual.prRef() != null) {
        enriched.put("prReference", actual.prRef());
      }
      byte[] enrichedBytes = objectMapper.writeValueAsBytes(enriched);
      String payloadRef = prRef.path("artifactId").asText("prOutput") + ".json";
      RecordArtifactOperationCommand command =
          new RecordArtifactOperationCommand(
              workflowRunId,
              ArtifactType.PR_OUTPUT,
              ArtifactOperationType.UPDATE,
              "runner-result-enrich:" + runnerExecutionId,
              payloadRef,
              enrichedBytes,
              "system",
              org.dradgo.domain.registry.ActorType.SYSTEM,
              correlationId,
              runnerExecutionId);
      RecordArtifactOperationResult result = artifactOperationService.recordOperation(command);
      if (result.isFailure()) {
        log.warn(
            "onResult pr-output enrichment record failed runnerExecutionId={} artifactId={}",
            runnerExecutionId,
            prOutputArtifactId);
        return;
      }
      log.info(
          "onResult pr-output artifact enriched runnerExecutionId={} workflowRunId={} artifactId={} "
              + "branch={} commitSha={} prReference={}",
          runnerExecutionId,
          workflowRunId,
          result.artifact().publicId(),
          actual.branchRef(),
          actual.commitSha(),
          actual.prRef());
    } catch (RuntimeException | java.io.IOException error) {
      // Story 3.12 review patch — enrichment is best-effort and runs BEFORE recordCompleted; an
      // uncaught RuntimeException (Jackson / artifact-service) would escape onResult and strand the
      // execution RUNNING (re-harvested). Swallow ALL failures so the committed runner outcome and
      // the WaitingForReview advance are never unwound by an enrichment-only error. DomainException
      // extends RuntimeException, so it is covered here.
      log.warn(
          "onResult pr-output enrichment failed runnerExecutionId={} artifactId={} cause={}",
          runnerExecutionId,
          prOutputArtifactId,
          error.toString());
    }
  }

  /**
   * Story 3.12 (AC3/AC9 / Decision D5) — record the execution failed, emit a precise {@code
   * RUNNER_FAILED} carrying the typed pr-output error code, and drive the run to {@code Failed} via
   * the existing runner-contract-violation path (the broker stays the failure-drive owner — the new
   * ArchUnit success-advance rule is scoped to {@code onPrOutputStageSucceeded} only).
   * At-most-once: a duplicate/late result for an already-terminal execution is a no-op. Never logs
   * the payload.
   */
  private void handlePrOutputContractFailure(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      DomainErrorCode errorCode,
      String reasonCode,
      String reason) {
    if (isTerminal(row.status())) {
      log.debug(
          "onResult pr-output contract failure ignored for already-terminal execution "
              + "runnerExecutionId={} status={}",
          runnerExecutionId,
          row.status());
      return;
    }
    executionService.recordFailed(runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(org.dradgo.domain.registry.WorkflowEventDetailKeys.WORKFLOW_RUN_ID, workflowRunId);
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("failureCategory", FailureCategory.RUNNER_CONTRACT_VIOLATION.value());
    details.put("errorCode", errorCode.value());
    details.put("stage", row.stage().value());
    details.put("subStage", ExecutionSubStage.PR_OUTPUT.name());
    details.put("reason", reasonCode);
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_FAILED,
        ActorContext.SYSTEM,
        reasonCode,
        FailureCategory.RUNNER_CONTRACT_VIOLATION,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
    driveWorkflowFailed(
        workflowRunId, runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION, reason);
  }

  /**
   * Story 3a-1 (AC7 / Trap T6) — resolve the correlationId for a runner outcome. Prefers the
   * originating correlationId from the current MDC scope; falls back to a stable execution-scoped
   * id so the artifact events + spec-ready transition share one non-random correlationId.
   */
  private static String resolveOutcomeCorrelationId(String runnerExecutionId) {
    String fromMdc = org.slf4j.MDC.get(MdcKeys.CORRELATION_ID);
    if (fromMdc != null && !fromMdc.isBlank()) {
      return fromMdc;
    }
    return "rex-" + runnerExecutionId;
  }

  /**
   * Last path segment of a relative reference (e.g. {@code "spec/v1.json"} → {@code "v1.json"}).
   */
  private static String leafFilename(String reference) {
    String normalized = reference.replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    return slash < 0 ? normalized : normalized.substring(slash + 1);
  }

  private Optional<ArtifactPayload> artifactPayload(String runnerExecutionId, JsonNode ref) {
    String contentReference = ref.path("contentReference").asText(null);
    if (contentReference != null && !contentReference.isBlank()) {
      return scratchStore
          .tryReadArtifactContent(runnerExecutionId, contentReference)
          .map(bytes -> new ArtifactPayload(leafFilename(contentReference), bytes));
    }
    JsonNode artifactId = ref.path("artifactId");
    if (!artifactId.isTextual() || artifactId.asText().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new ArtifactPayload(artifactId.asText() + ".json", objectMapper.writeValueAsBytes(ref)));
    } catch (java.io.IOException error) {
      log.warn(
          "artifact payload serialization failed runnerExecutionId={} artifactId={} cause={}",
          runnerExecutionId,
          artifactId.asText(),
          error.toString());
      return Optional.empty();
    }
  }

  private record ArtifactPayload(String payloadRef, byte[] bytes) {}

  /**
   * A result arriving for a row that is already in a non-result-bearing terminal state ({@code
   * TIMED_OUT} or {@code ORPHANED}) is, by AC5, classified as {@code runner_late_result}: emit a
   * precise {@code RUNNER_FAILED} event, route any artifact references via {@link
   * ArtifactOperationService} for {@code late_or_stale} marking (story 1.12 AC10), and
   * intentionally leave the workflow-run state alone.
   */
  private void handleLateResult(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      byte[] payloadBytes) {
    ValidationResult validation =
        contractValidator.validate(
            ValidationTarget.RUNNER_RESULT,
            payloadBytes,
            buildResultValidationContext(workflowRunId, runnerExecutionId));
    log.warn(
        "onResult late result runnerExecutionId={} workflowRunId={} status={} payloadBytes={}",
        runnerExecutionId,
        workflowRunId,
        row.status().value(),
        payloadBytes.length);

    // Best-effort artifact harvest: parse the payload, route each artifactReference through
    // ArtifactOperationService. ArtifactOperationService's isTimedOut check tags artifacts as
    // late_or_stale automatically. We tolerate parse / schema failure here — the row is
    // already terminal, the event has been emitted, and the workflow-state path stays clean.
    if (validation.valid()) {
      try {
        JsonNode parsed = objectMapper.readTree(payloadBytes);
        JsonNode artifactRefs = parsed.path("artifactReferences");
        if (artifactRefs.isArray() && !artifactRefs.isEmpty()) {
          String correlationId = UUID.randomUUID().toString();
          for (JsonNode ref : artifactRefs) {
            try {
              Optional<ArtifactPayload> maybePayload = artifactPayload(runnerExecutionId, ref);
              if (maybePayload.isEmpty()) {
                continue;
              }
              ArtifactPayload payload = maybePayload.get();
              String typeValue = ref.path("artifactType").asText();
              ArtifactType artifactType =
                  ArtifactType.fromValue(
                      typeValue, "runner_result.artifactReferences.artifactType");
              String idempotencyKey =
                  "runner-result-late:" + runnerExecutionId + ":" + ref.path("artifactId").asText();
              RecordArtifactOperationCommand command =
                  new RecordArtifactOperationCommand(
                      workflowRunId,
                      artifactType,
                      ArtifactOperationType.CREATE,
                      idempotencyKey,
                      payload.payloadRef(),
                      payload.bytes(),
                      "system",
                      org.dradgo.domain.registry.ActorType.SYSTEM,
                      correlationId,
                      runnerExecutionId);
              artifactOperationService.recordOperation(command);
            } catch (DomainException error) {
              log.warn(
                  "late-result artifact harvest skipped runnerExecutionId={} cause={}",
                  runnerExecutionId,
                  error.getMessage());
            }
          }
        }
      } catch (java.io.IOException error) {
        log.warn(
            "late-result artifact harvest skipped runnerExecutionId={} reason=malformed_payload cause={}",
            runnerExecutionId,
            error.toString());
      }
    } else {
      log.warn(
          "late-result payload rejected before artifact harvest runnerExecutionId={} errorCount={}",
          runnerExecutionId,
          validation.errors().size());
    }

    executionService.recordFailed(runnerExecutionId, FailureCategory.RUNNER_LATE_RESULT);
    appendRunnerFailedEvent(
        workflowRunId,
        runnerExecutionId,
        FailureCategory.RUNNER_LATE_RESULT,
        "runner_late_result",
        ActorContext.SYSTEM);
  }

  private void handleNonZeroExit(
      String runnerExecutionId, String workflowRunId, String failureCategoryValue) {
    FailureCategory category =
        FailureCategory.fromValue(failureCategoryValue, "runner_result.failureCategory");
    FailureCategory effectiveCategory =
        category == FailureCategory.RUNNER_NON_ZERO_EXIT
            ? category
            : FailureCategory.RUNNER_NON_ZERO_EXIT;
    executionService.recordFailed(runnerExecutionId, effectiveCategory);
    appendRunnerFailedEvent(
        workflowRunId,
        runnerExecutionId,
        effectiveCategory,
        "runner_non_zero_exit",
        ActorContext.SYSTEM);
    driveWorkflowFailed(
        workflowRunId, runnerExecutionId, effectiveCategory, "runner non-zero exit");
    log.warn(
        "onResult non-zero exit runnerExecutionId={} workflowRunId={} reportedCategory={} effectiveCategory={}",
        runnerExecutionId,
        workflowRunId,
        category.value(),
        effectiveCategory.value());
  }

  private void handleFailedValidation(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      FailureCategory category,
      ValidationResult result,
      byte[] payloadBytes) {
    boolean alreadyTerminal = isTerminal(row.status());
    if (!alreadyTerminal) {
      executionService.recordFailed(runnerExecutionId, category);
    }
    appendRunnerFailedEvent(
        workflowRunId,
        runnerExecutionId,
        category,
        alreadyTerminal ? "runner_late_or_duplicate" : "runner_result_rejected",
        ActorContext.SYSTEM);

    // AC5 split: only contract-violation / non-zero-exit / crash / timeout drive workflow state.
    // runner_malformed_output, runner_duplicate_result, runner_late_result do NOT change workflow
    // state.
    if (category == FailureCategory.RUNNER_CONTRACT_VIOLATION
        || category == FailureCategory.RUNNER_NON_ZERO_EXIT
        || category == FailureCategory.RUNNER_CRASH
        || category == FailureCategory.RUNNER_TIMEOUT) {
      driveWorkflowFailed(workflowRunId, runnerExecutionId, category, "runner result rejected");
    }
    log.warn(
        "onResult validation failed runnerExecutionId={} workflowRunId={} category={} errorCount={} payloadBytes={}",
        runnerExecutionId,
        workflowRunId,
        category.value(),
        result.errors().size(),
        payloadBytes.length);
  }

  /**
   * Build the ValidationContext for a runner result: every existing runner_execution row in the
   * workflow run contributes its public id to {@code knownRunnerExecutionIds}; rows whose state
   * indicates a prior result was already accepted by the broker contribute to {@code
   * observedRunnerExecutionIds} so the validator can flag a 2nd arrival as {@code
   * DUPLICATE_RUNNER_EXECUTION_ID}.
   */
  private ValidationContext buildResultValidationContext(
      String workflowRunId, String runnerExecutionId) {
    ValidationContext.Builder builder = ValidationContext.builder();
    builder.addKnownRunnerExecutionId(runnerExecutionId);
    List<RunnerExecutionSnapshot> peers =
        recordPort.findByWorkflowRunPublicIdAndStatusIn(workflowRunId, ALL_STATUSES);
    for (RunnerExecutionSnapshot peer : peers) {
      builder.addKnownRunnerExecutionId(peer.publicId());
      if (priorResultReceived(peer)) {
        builder.addObservedRunnerExecutionId(peer.publicId());
      }
    }
    return builder.build();
  }

  /**
   * True iff the row's terminal state implies a result payload was previously processed by the
   * broker — i.e., either a successful result ({@code COMPLETED}) or a failed result whose failure
   * category indicates the result file did arrive (validation rejection, non-zero-exit, malformed
   * JSON, duplicate, or late). Categories that mean "no result file ever arrived" ({@code
   * RUNNER_CRASH}, {@code RUNNER_TIMEOUT}, {@code ORPHAN}) intentionally return {@code false} so a
   * subsequent late arrival can be classified by the late-result path rather than as a duplicate.
   */
  private static boolean priorResultReceived(RunnerExecutionSnapshot snapshot) {
    if (snapshot.status() == RunnerExecutionStatus.COMPLETED) {
      return true;
    }
    if (snapshot.status() != RunnerExecutionStatus.FAILED) {
      return false;
    }
    FailureCategory category = snapshot.failureCategory();
    if (category == null) {
      return false;
    }
    return switch (category) {
      case RUNNER_CONTRACT_VIOLATION,
          RUNNER_NON_ZERO_EXIT,
          RUNNER_MALFORMED_OUTPUT,
          RUNNER_DUPLICATE_RESULT,
          RUNNER_LATE_RESULT,
          // Story 3.5: a secret-leak failure means a result WAS harvested + scanned then rejected,
          // so a subsequent arrival is a duplicate (not a fresh result).
          RUNNER_SECRET_LEAK ->
          true;
      case RUNNER_CRASH, RUNNER_TIMEOUT, ORPHAN -> false;
    };
  }

  private FailureCategory classifyValidationFailure(List<ValidationError> errors) {
    for (ValidationError error : errors) {
      if (error.code() == ValidationErrorCode.JSON_PARSE_FAILED) {
        return FailureCategory.RUNNER_MALFORMED_OUTPUT;
      }
      if (error.code() == ValidationErrorCode.DUPLICATE_RUNNER_EXECUTION_ID) {
        return FailureCategory.RUNNER_DUPLICATE_RESULT;
      }
    }
    return FailureCategory.RUNNER_CONTRACT_VIOLATION;
  }

  /**
   * Story 3.2 AC8: emit {@code runner.dispatched} on the docker path after the adapter's dispatch
   * ack returns. The mock path's {@code emitsDispatchedAfterAck()} returns {@code false}, so this
   * is a no-op under {@code runners.mock}.
   */
  private String appendRunnerDispatchedEventIfDocker(
      String workflowRunId,
      String runnerExecutionId,
      org.dradgo.domain.registry.RunnerKind runnerKind,
      RunnerDispatchAck ack,
      ActorContext actor) {
    if (!(runnerAdapter instanceof RecoverableRunnerAdapter recoverable)
        || !recoverable.emitsDispatchedAfterAck()) {
      return null;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("runnerKind", runnerKind.value());
    recoverable
        .findContainerIdFor(runnerExecutionId)
        .ifPresent(id -> details.put("containerId", id));
    String image = runnerProperties.docker().imageTagFor(runnerKind);
    if (image != null && !image.isBlank()) {
      details.put("image", RunnerProperties.Docker.redactImageTag(image));
    }
    details.put(
        "dispatchedAt", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).toString());
    // Story 3.2a AC10: capture the appended event's public id so the dispatch result can surface it
    // back to RecoveryService as the retry audit anchor.
    String dispatchedEventPublicId =
        eventPort.append(
            workflowRunId,
            WorkflowEventType.RUNNER_DISPATCHED,
            actor,
            "runner_dispatched",
            null,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            details);
    log.info(
        "RUNNER_DISPATCHED appended workflowRunId={} runnerExecutionId={} runnerKind={} adapterRef={} eventId={}",
        workflowRunId,
        runnerExecutionId,
        runnerKind.value(),
        ack.adapterRef(),
        dispatchedEventPublicId);
    return dispatchedEventPublicId;
  }

  /** Story 3.2 AC8: emit {@code runner.completed} after a successful happy-path result ingest. */
  private void appendRunnerCompletedEvent(
      String workflowRunId, String runnerExecutionId, Integer exitCode) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(org.dradgo.domain.registry.WorkflowEventDetailKeys.WORKFLOW_RUN_ID, workflowRunId);
    details.put("runnerExecutionId", runnerExecutionId);
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverable) {
      recoverable
          .findContainerIdFor(runnerExecutionId)
          .ifPresent(id -> details.put("containerId", id));
    }
    if (exitCode != null) {
      details.put("exitCode", exitCode);
    }
    addCapturedLogDetails(details, runnerExecutionId);
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_COMPLETED,
        ActorContext.SYSTEM,
        "runner_completed",
        null,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
    log.info(
        "RUNNER_COMPLETED appended workflowRunId={} runnerExecutionId={}",
        workflowRunId,
        runnerExecutionId);
  }

  private void appendRunnerFailedEvent(
      String workflowRunId,
      String runnerExecutionId,
      FailureCategory category,
      String reason,
      ActorContext actor) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(org.dradgo.domain.registry.WorkflowEventDetailKeys.WORKFLOW_RUN_ID, workflowRunId);
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("failureCategory", category.value());
    details.put("reason", reason);
    addCapturedLogDetails(details, runnerExecutionId);
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_FAILED,
        actor,
        reason,
        category,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
  }

  /**
   * Story 3.9 AC7 (OQ-2) — emit the {@code git.pushFailed} signal for a rejected push. Reuses the
   * existing {@code RUNNER_FAILED} event type (a dedicated {@code git.pushFailed} WorkflowEventType
   * would be a schema-gated change — the workflow-event-types contract fixture + response schema —
   * which story 3.6 Trap T6 deliberately avoided; the precise git classification is carried in the
   * details instead). {@code details} carries the git {@code IntegrationFailureCategory} and the
   * {@code git.pushFailed} marker — never a token, auth URL, or branch-protection internals.
   */
  private void appendGitPushFailedEvent(
      String workflowRunId, String runnerExecutionId, GitCommandException pushFailure) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(org.dradgo.domain.registry.WorkflowEventDetailKeys.WORKFLOW_RUN_ID, workflowRunId);
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("event", "git.pushFailed");
    details.put("failureCategory", FailureCategory.RUNNER_CONTRACT_VIOLATION.value());
    details.put("gitFailureCategory", pushFailure.failureCategory().value());
    details.put("reason", "git_push_rejected");
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_FAILED,
        ActorContext.SYSTEM,
        "git_push_rejected",
        FailureCategory.RUNNER_CONTRACT_VIOLATION,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
  }

  /**
   * Story 3.6 AC6 / Trap T6 — enrich the existing {@code RUNNER_COMPLETED} / {@code RUNNER_FAILED}
   * event details with the durable redacted-log capture metrics (metadata ONLY — never content,
   * never a secret value). The Docker adapter persisted these onto the row at container exit
   * (recordRawOutput) before the result reached the broker, so a fresh read of the row surfaces
   * them. Rows captured before this story, or runners that produced no logs, leave the columns null
   * and no keys are added.
   */
  private void addCapturedLogDetails(Map<String, Object> details, String runnerExecutionId) {
    recordPort
        .findByPublicId(runnerExecutionId)
        .ifPresent(
            snapshot -> {
              if (snapshot.redactionCount() != null) {
                details.put(
                    org.dradgo.domain.registry.WorkflowEventDetailKeys.REDACTION_COUNT,
                    snapshot.redactionCount());
              }
              if (snapshot.rawOutputByteSize() != null) {
                details.put(
                    org.dradgo.domain.registry.WorkflowEventDetailKeys.RAW_OUTPUT_BYTE_SIZE,
                    snapshot.rawOutputByteSize());
              }
              if (snapshot.rawOutputClassification() != null) {
                details.put(
                    org.dradgo.domain.registry.WorkflowEventDetailKeys.RAW_OUTPUT_CLASSIFICATION,
                    snapshot.rawOutputClassification().value());
              }
            });
  }

  /**
   * Story 3.5 AC4 — emit {@code RUNNER_FAILED} for a post-execution secret leak. {@code details}
   * carries {@code failureCategory=runner_secret_leak}, {@code leakedFile} (relative path), and
   * {@code detectedCategories} (category NAMES only; a substring-only hit is labelled {@code
   * injected_provider_key}). NEVER a secret value.
   */
  private void appendRunnerSecretLeakEvent(
      String workflowRunId,
      String runnerExecutionId,
      String leakedFile,
      List<String> detectedCategories) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("failureCategory", FailureCategory.RUNNER_SECRET_LEAK.value());
    details.put("reason", "runner_secret_leak");
    details.put("leakedFile", leakedFile);
    details.put("detectedCategories", List.copyOf(detectedCategories));
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_FAILED,
        ActorContext.SYSTEM,
        "runner_secret_leak",
        FailureCategory.RUNNER_SECRET_LEAK,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
  }

  private void driveWorkflowFailed(
      String workflowRunId, String runnerExecutionId, FailureCategory category, String reason) {
    TransitionActor transitionActor = RunnerActorTranslator.toTransitionActor(ActorContext.SYSTEM);
    try {
      workflowTransitionService.transition(
          workflowRunId,
          WorkflowState.FAILED,
          transitionActor,
          reason,
          "runner-broker:" + runnerExecutionId + ":" + category.value(),
          category,
          Map.of("runnerExecutionId", runnerExecutionId));
    } catch (DomainException error) {
      if (error.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
        log.warn(
            "driveWorkflowFailed swallowed ILLEGAL_TRANSITION workflowRunId={} runnerExecutionId={} reason={}",
            workflowRunId,
            runnerExecutionId,
            error.getMessage());
        return;
      }
      throw error;
    }
  }

  // =====================================================================
  // scanForTimeouts
  // =====================================================================

  public int scanForTimeouts() {
    List<RunnerExecutionSnapshot> stale =
        recordPort.findStaleByStatusInAndTimeoutAtBefore(
            ACTIVE_STATUSES, Duration.ZERO, runnerProperties.timeoutScanBatchSize());
    log.info("scanForTimeouts start candidates={}", stale.size());
    int flipped = 0;
    for (RunnerExecutionSnapshot snapshot : stale) {
      try {
        Boolean updated =
            perItemTransactionTemplate.execute(status -> processSingleTimeout(snapshot));
        if (Boolean.TRUE.equals(updated)) {
          flipped++;
        }
      } catch (Exception error) {
        log.error(
            "scanForTimeouts item failed runnerExecutionId={} cause={}",
            snapshot.publicId(),
            error.toString());
      }
    }
    log.info("scanForTimeouts done candidates={} flipped={}", stale.size(), flipped);
    return flipped;
  }

  private boolean processSingleTimeout(RunnerExecutionSnapshot snapshot) {
    String runnerExecutionId = snapshot.publicId();
    Optional<RunnerExecutionSnapshot> fresh = recordPort.findByPublicId(runnerExecutionId);
    if (fresh.isEmpty() || isTerminal(fresh.get().status())) {
      return false;
    }
    // Heartbeat-race guard: re-validate timeout_at against now() inside the per-item
    // transaction. A heartbeat that bumped timeout_at after the initial scan must not be
    // clobbered to TIMED_OUT.
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    OffsetDateTime freshTimeoutAt = fresh.get().timeoutAt();
    if (freshTimeoutAt == null || !freshTimeoutAt.isBefore(now)) {
      log.info(
          "scanForTimeouts skipping runnerExecutionId={} reason=heartbeat_extended_deadline timeoutAt={} now={}",
          runnerExecutionId,
          freshTimeoutAt,
          now);
      return false;
    }
    // Story 3.2 AC1: when the active adapter exposes the recovery sub-interface (the docker
    // path), issue a graceful stop + kill-after-grace BEFORE flipping the row to TIMED_OUT. The
    // mock adapter implements terminate as a no-op-y cancel(), so this stays safe under
    // runners.mock. Trap T1 — the heartbeat-race guard above runs FIRST.
    RecoverableRunnerAdapter.TerminationOutcome terminationOutcome =
        RecoverableRunnerAdapter.TerminationOutcome.UNKNOWN;
    Optional<String> containerId = Optional.empty();
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverable) {
      containerId = recoverable.findContainerIdFor(runnerExecutionId);
      try {
        terminationOutcome = recoverable.terminate(runnerExecutionId, Duration.ofSeconds(10L));
        log.info(
            "scanForTimeouts terminate runnerExecutionId={} outcome={}",
            runnerExecutionId,
            terminationOutcome);
      } catch (RuntimeException terminateError) {
        log.error(
            "scanForTimeouts terminate threw unexpectedly runnerExecutionId={} cause={}",
            runnerExecutionId,
            terminateError.toString());
      }
    }
    // Story 3.2a code-review (2026-05-29): gate the TIMED_OUT flip on the row ACTUALLY
    // transitioning, mirroring recordCompleted/recordOrphaned. The isTerminal pre-check above is a
    // non-locking read; a concurrent onResult/recovery can move the row terminal between that read
    // and recordTimedOut. recordTimedOut re-reads under a write lock and throws ILLEGAL_TRANSITION
    // if the row is already terminal — catch it and skip the event + workflow-fail so a
    // scan-vs-completion race cannot emit a duplicate runner.timeout or double-fail the run. The
    // terminate() above already ran (best-effort stop of a past-deadline container); only the
    // bookkeeping is gated.
    try {
      executionService.recordTimedOut(runnerExecutionId);
    } catch (DomainException raceTerminal) {
      if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw raceTerminal;
      }
      log.info(
          "scanForTimeouts timeout skip runnerExecutionId={} reason=already_terminal errorCode={}",
          runnerExecutionId,
          raceTerminal.errorCode().value());
      return false;
    }
    appendRunnerTimeoutEvent(
        snapshot.workflowRunPublicId(),
        runnerExecutionId,
        containerId.orElse(null),
        terminationOutcome,
        freshTimeoutAt);
    driveWorkflowFailed(
        snapshot.workflowRunPublicId(),
        runnerExecutionId,
        FailureCategory.RUNNER_TIMEOUT,
        "runner timeout");
    return true;
  }

  private void appendRunnerTimeoutEvent(
      String workflowRunId,
      String runnerExecutionId,
      String containerId,
      RecoverableRunnerAdapter.TerminationOutcome terminationOutcome,
      OffsetDateTime timeoutAt) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    if (containerId != null) {
      details.put("containerId", containerId);
    }
    details.put("failureCategory", FailureCategory.RUNNER_TIMEOUT.value());
    if (timeoutAt != null) {
      details.put("timeoutAt", timeoutAt.toString());
    }
    details.put("terminationOutcome", terminationOutcome.name());
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_TIMEOUT,
        ActorContext.SYSTEM,
        "runner_timeout",
        FailureCategory.RUNNER_TIMEOUT,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
  }

  // =====================================================================
  // scanForStaleExecutions — heartbeat-stale warn + orphan flip (story 3.2 AC3)
  // =====================================================================

  /**
   * Story 3.2 AC3: scheduled scan that emits {@code runner.heartbeatStale} once per stale-window
   * for rows past {@code 1 × stage_timeout} but inside {@code 2 × stage_timeout}, and flips rows
   * past {@code 2 × stage_timeout} to {@code orphaned} with {@code runner.orphaned}.
   *
   * <p>The {@code heartbeat_stale_emitted_at} column gates re-emission (Trap T4 / OQ-1). The orphan
   * flip does NOT drive workflow state — Epic 4 recovery decides reconcile vs. fail-forward (AC3
   * sub-bullet (c)).
   */
  public int scanForStaleExecutions() {
    log.info("scanForStaleExecutions start");
    int heartbeatWarnings = 0;
    int orphanFlips = 0;
    int batchSize = runnerProperties.timeoutScanBatchSize();
    Duration baselineWindow = runnerProperties.timeoutFor(RunnerStage.INVESTIGATION);
    for (RunnerStage stage : RunnerStage.values()) {
      Duration stageTimeout = runnerProperties.timeoutFor(stage);
      Duration orphanThreshold = runnerProperties.staleThresholdFor(stage);
      // Phase 1 — heartbeat-stale WARN per row (idempotent on heartbeat_stale_emitted_at).
      // Story 3.2a AC2: the query is stage-scoped so the per-stage LIMIT cannot be exhausted by a
      // backlog of another stage (no cross-stage starvation).
      List<RunnerExecutionSnapshot> heartbeatStaleCandidates =
          recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
              ACTIVE_STATUSES, stage, stageTimeout, batchSize);
      for (RunnerExecutionSnapshot snapshot : heartbeatStaleCandidates) {
        try {
          Boolean emitted =
              perItemTransactionTemplate.execute(
                  status -> processHeartbeatStale(snapshot, orphanThreshold));
          if (Boolean.TRUE.equals(emitted)) {
            heartbeatWarnings++;
          }
        } catch (Exception error) {
          log.error(
              "scanForStaleExecutions heartbeat-stale item failed runnerExecutionId={} cause={}",
              snapshot.publicId(),
              error.toString());
        }
      }
      // Phase 2 — orphan flip per row past the orphan threshold (also stage-scoped, AC2).
      List<RunnerExecutionSnapshot> orphanCandidates =
          recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
              ACTIVE_STATUSES, stage, orphanThreshold, batchSize);
      for (RunnerExecutionSnapshot snapshot : orphanCandidates) {
        try {
          Boolean flipped =
              perItemTransactionTemplate.execute(status -> processStaleOrphan(snapshot));
          if (Boolean.TRUE.equals(flipped)) {
            orphanFlips++;
          }
        } catch (Exception error) {
          log.error(
              "scanForStaleExecutions orphan item failed runnerExecutionId={} cause={}",
              snapshot.publicId(),
              error.toString());
        }
      }
      // Best-effort progress signal across stages.
      log.debug(
          "scanForStaleExecutions stage progress stage={} stageTimeout={} orphanThreshold={} baseline={}",
          stage.value(),
          stageTimeout,
          orphanThreshold,
          baselineWindow);
    }
    log.info(
        "scanForStaleExecutions done heartbeatWarnings={} orphanFlips={}",
        heartbeatWarnings,
        orphanFlips);
    return heartbeatWarnings + orphanFlips;
  }

  private boolean processHeartbeatStale(
      RunnerExecutionSnapshot snapshot, Duration orphanThreshold) {
    String runnerExecutionId = snapshot.publicId();
    Optional<RunnerExecutionSnapshot> fresh = recordPort.findByPublicId(runnerExecutionId);
    if (fresh.isEmpty() || isTerminal(fresh.get().status())) {
      return false;
    }
    RunnerExecutionSnapshot current = fresh.get();
    if (current.heartbeatStaleEmittedAt() != null) {
      return false;
    }
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    OffsetDateTime orphanCutoff = now.minus(orphanThreshold).withOffsetSameInstant(ZoneOffset.UTC);
    OffsetDateTime lastActivity = current.lastActivityAt();
    // Story 3.2a AC9: handle a null last_activity_at explicitly rather than silently conflating it
    // with "past the orphan threshold". A live row should always carry last_activity_at (set on
    // insert); a null here is anomalous, so we skip the heartbeat-stale emission and surface the
    // anomaly distinctly instead of guessing the row is orphan-eligible.
    if (lastActivity == null) {
      log.warn(
          "scanForStaleExecutions heartbeat-stale skip runnerExecutionId={} reason=null_last_activity_at",
          runnerExecutionId);
      return false;
    }
    if (lastActivity.isBefore(orphanCutoff)) {
      // Past the orphan threshold — orphan phase will handle it, do not double-emit.
      return false;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("lastActivityAt", lastActivity.toString());
    details.put("reason", "heartbeat_stale");
    eventPort.append(
        current.workflowRunPublicId(),
        WorkflowEventType.RUNNER_HEARTBEAT_STALE,
        ActorContext.SYSTEM,
        "heartbeat_stale",
        null,
        now,
        details);
    recordPort.markHeartbeatStaleEmitted(runnerExecutionId, now);
    log.warn(
        "RUNNER_HEARTBEAT_STALE appended runnerExecutionId={} workflowRunId={} lastActivityAt={}",
        runnerExecutionId,
        current.workflowRunPublicId(),
        lastActivity);
    return true;
  }

  private boolean processStaleOrphan(RunnerExecutionSnapshot snapshot) {
    String runnerExecutionId = snapshot.publicId();
    Optional<RunnerExecutionSnapshot> fresh = recordPort.findByPublicId(runnerExecutionId);
    if (fresh.isEmpty() || isTerminal(fresh.get().status())) {
      return false;
    }
    RunnerExecutionSnapshot current = fresh.get();
    // Story 3.2a AC9: gate the RUNNER_ORPHANED append on the row ACTUALLY transitioning. The
    // isTerminal pre-check above is a non-locking read; a concurrent poll/recovery can move the row
    // terminal between that read and this guarded transition. recordOrphaned re-reads under a write
    // lock and throws ILLEGAL_TRANSITION if the row is already terminal — catch it and skip the
    // append so a scan-vs-recovery race cannot emit a duplicate lifecycle event.
    try {
      executionService.recordOrphaned(runnerExecutionId);
    } catch (DomainException raceTerminal) {
      if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw raceTerminal;
      }
      log.info(
          "scanForStaleExecutions orphan skip runnerExecutionId={} reason=already_terminal errorCode={}",
          runnerExecutionId,
          raceTerminal.errorCode().value());
      return false;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("failureCategory", FailureCategory.ORPHAN.value());
    details.put("reason", "lease_expired");
    if (current.lastActivityAt() != null) {
      details.put("lastActivityAt", current.lastActivityAt().toString());
    }
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverable) {
      recoverable
          .findContainerIdFor(runnerExecutionId)
          .ifPresent(id -> details.put("containerId", id));
    }
    eventPort.append(
        snapshot.workflowRunPublicId(),
        WorkflowEventType.RUNNER_ORPHANED,
        ActorContext.SYSTEM,
        "lease_expired",
        FailureCategory.ORPHAN,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
    log.warn(
        "RUNNER_ORPHANED appended runnerExecutionId={} workflowRunId={} lastActivityAt={}",
        runnerExecutionId,
        snapshot.workflowRunPublicId(),
        current.lastActivityAt());
    return true;
  }

  // =====================================================================
  // pollActiveExecutions — heartbeat + crash + result-harvest path
  // =====================================================================

  /**
   * Walk active ({@code pending}/{@code running}) runner executions and consult {@link
   * RunnerAdapter#poll(String)} for each. Drives {@code last_activity_at} forward on heartbeat
   * touches, surfaces {@code runner_crash} (and other adapter-reported failure categories) per AC5,
   * and harvests a present result file via {@link #onResult} so the happy-path can complete without
   * external triggers.
   */
  public int pollActiveExecutions() {
    int batchSize = runnerProperties.timeoutScanBatchSize();
    List<RunnerExecutionSnapshot> active =
        recordPort.findActiveStatuses(ACTIVE_STATUSES, batchSize);
    log.info("pollActiveExecutions start candidates={}", active.size());
    int processed = 0;
    for (RunnerExecutionSnapshot snapshot : active) {
      try {
        Boolean updated = perItemTransactionTemplate.execute(status -> processSinglePoll(snapshot));
        if (Boolean.TRUE.equals(updated)) {
          processed++;
        }
      } catch (Exception error) {
        log.error(
            "pollActiveExecutions item failed runnerExecutionId={} cause={}",
            snapshot.publicId(),
            error.toString());
      }
    }
    log.info("pollActiveExecutions done candidates={} processed={}", active.size(), processed);
    return processed;
  }

  private boolean processSinglePoll(RunnerExecutionSnapshot snapshot) {
    String runnerExecutionId = snapshot.publicId();
    RunnerPollStatus pollStatus = runnerAdapter.poll(runnerExecutionId);
    return switch (pollStatus) {
      case RunnerPollStatus.Running ignored -> {
        executionService.touchActivity(
            runnerExecutionId, runnerProperties.staleThresholdFor(snapshot.stage()));
        yield true;
      }
      case RunnerPollStatus.Unknown ignored -> false;
      case RunnerPollStatus.HeartbeatTouched heartbeat -> {
        try {
          executionService.touchActivity(
              runnerExecutionId,
              heartbeat.activityTimestamp(),
              runnerProperties.staleThresholdFor(snapshot.stage()));
          log.info(
              "poll heartbeat advanced runnerExecutionId={} activityTimestamp={}",
              runnerExecutionId,
              heartbeat.activityTimestamp());
          yield true;
        } catch (DomainException error) {
          if (error.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
            log.warn(
                "poll heartbeat ignored on terminal runnerExecutionId={} cause={}",
                runnerExecutionId,
                error.getMessage());
            yield false;
          }
          throw error;
        }
      }
      case RunnerPollStatus.Failed failed -> {
        handlePollFailure(snapshot, failed.failureCategory());
        yield true;
      }
      case RunnerPollStatus.Completed ignored -> harvestResultFromAdapter(snapshot);
    };
  }

  private void handlePollFailure(RunnerExecutionSnapshot snapshot, FailureCategory category) {
    String runnerExecutionId = snapshot.publicId();
    String workflowRunId = snapshot.workflowRunPublicId();
    executionService.recordFailed(runnerExecutionId, category);
    appendRunnerFailedEvent(
        workflowRunId, runnerExecutionId, category, "runner_poll_failure", ActorContext.SYSTEM);
    // AC5 split: only the four "result-bearing or process-level" categories drive workflow state.
    if (category == FailureCategory.RUNNER_CRASH
        || category == FailureCategory.RUNNER_TIMEOUT
        || category == FailureCategory.RUNNER_CONTRACT_VIOLATION
        || category == FailureCategory.RUNNER_NON_ZERO_EXIT) {
      driveWorkflowFailed(
          workflowRunId, runnerExecutionId, category, "runner poll reported " + category.value());
    }
    log.warn(
        "poll failure runnerExecutionId={} workflowRunId={} category={}",
        runnerExecutionId,
        workflowRunId,
        category.value());
  }

  private boolean harvestResultFromAdapter(RunnerExecutionSnapshot snapshot) {
    Optional<byte[]> result = runnerAdapter.tryReadResult(snapshot.publicId());
    if (result.isEmpty()) {
      return false;
    }
    onResult(snapshot.publicId(), result.get());
    return true;
  }

  // =====================================================================
  // recoverOnStartup
  // =====================================================================

  public int recoverOnStartup() {
    int batchSize = runnerProperties.recovery().batchSize();
    List<RunnerExecutionSnapshot> active =
        recordPort.findActiveStatuses(ACTIVE_STATUSES, batchSize);
    log.info("recoverOnStartup start candidates={}", active.size());
    int handled = 0;
    for (RunnerExecutionSnapshot snapshot : active) {
      try {
        Boolean processed = perItemTransactionTemplate.execute(status -> processOrphan(snapshot));
        if (Boolean.TRUE.equals(processed)) {
          handled++;
        }
      } catch (Exception error) {
        log.error(
            "recoverOnStartup item failed runnerExecutionId={} cause={}",
            snapshot.publicId(),
            error.toString());
      }
    }
    log.info("recoverOnStartup done candidates={} handled={}", active.size(), handled);
    return handled;
  }

  private boolean processOrphan(RunnerExecutionSnapshot snapshot) {
    String runnerExecutionId = snapshot.publicId();
    // Trap T6: scratch-replay branch FIRST so a mock-runner happy-path that completed during JVM
    // downtime resumes via the scratch leaf file, exactly as story 1.13 / 3.1 defined.
    Optional<byte[]> maybeResult = scratchStore.tryReadRunnerResult(runnerExecutionId);
    if (maybeResult.isPresent()) {
      ValidationContext context =
          ValidationContext.builder().addKnownRunnerExecutionId(runnerExecutionId).build();
      ValidationResult validation =
          contractValidator.validate(ValidationTarget.RUNNER_RESULT, maybeResult.get(), context);
      if (validation.valid()) {
        onResult(runnerExecutionId, maybeResult.get());
        log.info("recoverOnStartup resumed runnerExecutionId={} via=scratch", runnerExecutionId);
        return true;
      }
    }

    // Story 3.2 AC4 / Trap T5: docker recovery probe SECOND — only when the active adapter
    // exposes the RecoverableRunnerAdapter sub-interface AND returns a non-empty handle. Mock
    // returns empty so this branch is a no-op under runners.mock.
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverable) {
      Optional<RunnerPollStatus> recovered = recoverable.recoverHandle(runnerExecutionId);
      if (recovered.isPresent()) {
        RunnerPollStatus status = recovered.get();
        switch (status) {
          case RunnerPollStatus.Running ignored -> {
            // Review decision D2: re-arm the lease on recovery. AC4 literally says "no row change"
            // for a recovered-running container, but its pre-crash timeout_at may already be
            // elapsed after a long broker outage, so the next scanForTimeouts would immediately
            // kill a genuinely-alive container. Refresh last_activity_at to now so the recovered
            // container gets a fresh stage window. (Documented deviation from AC4.)
            OffsetDateTime nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            try {
              executionService.touchActivity(
                  runnerExecutionId, nowUtc, runnerProperties.staleThresholdFor(snapshot.stage()));
            } catch (DomainException terminalRow) {
              // touchActivity on terminal row — next pollActiveExecutions tick handles it.
            }
            log.info(
                "recoverOnStartup resumed runnerExecutionId={} via=docker_probe status=running rearmed=true",
                runnerExecutionId);
            return false;
          }
          case RunnerPollStatus.HeartbeatTouched heartbeat -> {
            OffsetDateTime nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            // P12: clamp a future-dated engine StartedAt / filesystem mtime to the broker clock so
            // the deadline is not over-extended past now (engine/FS clocks may run ahead).
            OffsetDateTime activity = heartbeat.activityTimestamp();
            OffsetDateTime clamped = activity.isAfter(nowUtc) ? nowUtc : activity;
            try {
              executionService.touchActivity(
                  runnerExecutionId, clamped, runnerProperties.staleThresholdFor(snapshot.stage()));
            } catch (DomainException ignored) {
              // touchActivity on terminal row — ignore, the next pollActiveExecutions tick handles
              // it.
            }
            log.info(
                "recoverOnStartup resumed runnerExecutionId={} via=docker_probe status=heartbeat",
                runnerExecutionId);
            return false;
          }
          case RunnerPollStatus.Completed ignored -> {
            boolean harvested = harvestResultFromAdapter(snapshot);
            log.info(
                "recoverOnStartup harvested runnerExecutionId={} via=docker_probe harvested={}",
                runnerExecutionId,
                harvested);
            return harvested;
          }
          case RunnerPollStatus.Failed failed -> {
            handlePollFailure(snapshot, failed.failureCategory());
            log.info(
                "recoverOnStartup failed runnerExecutionId={} via=docker_probe category={}",
                runnerExecutionId,
                failed.failureCategory().value());
            return true;
          }
          case RunnerPollStatus.Unknown ignored -> {
            // Fall through to the orphan flip.
          }
        }
      } else {
        log.warn(
            "recoverOnStartup docker probe no-container-found runnerExecutionId={} workflowRunId={}",
            runnerExecutionId,
            snapshot.workflowRunPublicId());
      }
    }

    // Final fallback: flip to orphaned + emit RUNNER_ORPHANED (and keep RECOVERY_RECONCILED for
    // the recovery-axis audit per AC8 Trap T10 — they are complementary).
    // Story 3.2a AC9: gate the dual append on the row actually transitioning so a concurrent
    // scan-vs-recovery race (or a scratch result landing between the read above and here) cannot
    // append duplicate RUNNER_ORPHANED / RECOVERY_RECONCILED events.
    try {
      executionService.recordOrphaned(runnerExecutionId);
    } catch (DomainException raceTerminal) {
      if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw raceTerminal;
      }
      log.info(
          "recoverOnStartup orphan skip runnerExecutionId={} reason=already_terminal errorCode={}",
          runnerExecutionId,
          raceTerminal.errorCode().value());
      return false;
    }
    Map<String, Object> orphanDetails = new LinkedHashMap<>();
    orphanDetails.put("runnerExecutionId", runnerExecutionId);
    orphanDetails.put("failureCategory", FailureCategory.ORPHAN.value());
    orphanDetails.put("reason", "broker_restart_orphan");
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverableForId) {
      recoverableForId
          .findContainerIdFor(runnerExecutionId)
          .ifPresent(id -> orphanDetails.put("containerId", id));
    }
    eventPort.append(
        snapshot.workflowRunPublicId(),
        WorkflowEventType.RUNNER_ORPHANED,
        ActorContext.SYSTEM,
        "broker_restart_orphan",
        FailureCategory.ORPHAN,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        orphanDetails);
    Map<String, Object> recoveryDetails = new LinkedHashMap<>();
    recoveryDetails.put("runnerExecutionId", runnerExecutionId);
    recoveryDetails.put("failureCategory", FailureCategory.ORPHAN.value());
    recoveryDetails.put("reason", "broker_restart_orphan");
    eventPort.append(
        snapshot.workflowRunPublicId(),
        WorkflowEventType.RECOVERY_RECONCILED,
        ActorContext.SYSTEM,
        "broker_restart_orphan",
        FailureCategory.ORPHAN,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        recoveryDetails);
    log.warn(
        "recoverOnStartup orphaned runnerExecutionId={} workflowRunId={}",
        runnerExecutionId,
        snapshot.workflowRunPublicId());
    return true;
  }

  // =====================================================================
  // helpers
  // =====================================================================

  private RunnerExecutionHandle toHandle(RunnerExecutionSnapshot snapshot) {
    return new RunnerExecutionHandle(
        snapshot.publicId(),
        snapshot.workflowRunPublicId(),
        snapshot.stage(),
        snapshot.status(),
        snapshot.timeoutAt());
  }

  private boolean isTerminal(RunnerExecutionStatus status) {
    return RunnerExecutionStateMachine.isTerminal(status);
  }

  private static String dispatchFingerprint(
      String workflowRunId, RunnerStage stage, int contextBundleVersion) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String composed = workflowRunId + "|" + stage.value() + "|" + contextBundleVersion;
      digest.update(composed.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available in JDK 21", error);
    }
  }

  private static DomainException runnerExecutionNotFound(String runnerExecutionId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    return new DomainException(
        DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND,
        "Runner execution not found: " + runnerExecutionId,
        details);
  }

  private static DomainException idempotencyRecordLost(String idempotencyKey, String resultRef) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", idempotencyKey);
    details.put("resultRef", resultRef);
    return new DomainException(
        DomainErrorCode.IDEMPOTENCY_RECORD_LOST,
        "Idempotency replay pointed at missing runner execution: " + resultRef,
        details);
  }
}
