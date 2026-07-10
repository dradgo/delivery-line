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
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.project.Project;
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

  // Strong digest used when promoting an ingested runner-produced artifact (spec / implementation
  // plan / pr-output) to `available` (must be one of ArtifactChecksum.ALLOWED_ALGORITHMS).
  private static final String ARTIFACT_CHECKSUM_ALGORITHM = "SHA-256";

  // The runner-result contract's default cap (ValidationContext.DEFAULT_MAX_PAYLOAD_BYTES = 2 KB)
  // is far too small for real result payloads: an implementationPlan result embeds the full plan
  // as a steps[] array, and a pr-output result carries branch/commit/PR metadata — both routinely
  // exceed 2 KB and were being rejected as FILE_TOO_LARGE -> RUNNER_CONTRACT_VIOLATION. Raise the
  // result cap to 2 MB (the largest single embedded payload is the pr-output diff, itself bounded
  // to PR_OUTPUT_DIFF_MAX_BYTES = 1 MB, so 2 MB leaves comfortable headroom for surrounding JSON).
  private static final int RUNNER_RESULT_MAX_PAYLOAD_BYTES = 2 * 1_048_576;

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
  // Story 3h-1 (AC3) — the pre-review build gate, resolved LAZILY through a Supplier so the
  // broker↔build-stage constructor cycle is broken (BuildStageService reaches the broker's tail via
  // a continuation lambda; the broker calls tryGateBehindBuild here). Supplies null in the
  // package-private test ctors and lean contexts, so mock/no-build dispatches run the inline tail
  // unchanged: handleSuccess gates behind BUILD only when the resolved value is present AND the
  // terminal execution was a PR_OUTPUT sub-stage with build config + a workspace.
  private final java.util.function.Supplier<org.dradgo.application.workflow.BuildStageService>
      buildStageServiceSupplier;
  // Story 3h-2 (AC3) — the pre-review lint gate, resolved LAZILY via optional SETTER injection (an
  // ObjectProvider) so NONE of the broker's ctor sites (incl. the ~5 test ctors) change — the
  // WorkflowInspectionService optional-setter precedent. Supplies null in lean contexts, so
  // mock/no-lint dispatches run the tail unchanged. The broker↔lint-stage edge is one-way
  // (LintStageService reaches the tail via a continuation lambda; the broker calls
  // tryGateBehindLint
  // here), so there is no construction cycle to break.
  private java.util.function.Supplier<org.dradgo.application.workflow.LintStageService>
      lintStageServiceSupplier = () -> null;
  // Story 3h-4 (AC2) — the pre-review delivery gate, resolved LAZILY via optional SETTER injection
  // (an ObjectProvider) so NONE of the broker's ctor sites change — the same lint-gate precedent.
  // Supplies null in lean contexts, so mock/no-delivery-gate dispatches run the delivery tail
  // unchanged (push inline). The broker↔delivery-gate edge is one-way (the gate only resolves the
  // push mode and decides park-vs-pass; it runs no command and does not reach the broker back), so
  // there is no construction cycle to break.
  private java.util.function.Supplier<org.dradgo.application.workflow.DeliveryGateService>
      deliveryGateServiceSupplier = () -> null;
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
  // Story 3e-1 — the spec-runner question handler (CREATE half of the clarification loop), resolved
  // LAZILY through a Supplier (same pattern as the orchestration/integration callbacks): the
  // @Autowired ctor wires an ObjectProvider::getIfAvailable supplier. Supplies null in the
  // package-private test ctors and lean contexts, so the clarification create seam stays a
  // byte-identical no-op there. The broker NEVER touches ClarificationWritePort directly — it
  // delegates the whole per-result batch here (keeps the broker free of a clarification-write dep).
  private final java.util.function.Supplier<
          org.dradgo.application.clarification.ClarificationIngestService>
      clarificationIngestServiceSupplier;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate dispatchTransactionTemplate;
  private final TransactionTemplate perItemTransactionTemplate;
  private final Clock clock;

  // Story 3.19 (AC5) — dispatch/completion instrumentation. Optional SETTER injection (not a ctor
  // arg) so none of the five constructors — nor the four external test call sites — change: tests
  // keep the default no-op SimpleMeterRegistry (metrics go nowhere), production gets the Prometheus
  // registry wired by Spring. Meters named with DOTS (Prometheus underscores them, Reconciliation
  // 5).
  private io.micrometer.core.instrument.MeterRegistry meterRegistry =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    if (meterRegistry != null) {
      this.meterRegistry = meterRegistry;
    }
  }

  // Story 3c-7 (AC2/AC5) — the per-run config resolver, used to repoint the bundle repo-ref + the
  // dispatch OpenSpec flag off the global config onto the run's Project (default-project fallback).
  // Optional SETTER injection (mirroring meterRegistry above) so none of the five telescoping
  // constructors — nor any of their unit-test call sites — change. In PRODUCTION Spring injects the
  // always-present application.project @Component; where it is absent (the lean fast-tier broker
  // unit ctors) the use sites fall back to the pre-3c global path, keeping those tests
  // byte-identical
  // (the resolver depends on ProjectStore, NOT on this broker — no DI cycle, contrast
  // [[broker-orchestration-lazy-supplier]]).
  private org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setProjectRuntimeConfigResolver(
      org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver) {
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
  }

  // Story 3d-2 (Task 5) — the advisory-reviewer verdict harvester. Optional SETTER injection
  // (mirroring projectRuntimeConfigResolver) so none of the broker's telescoping constructors —
  // nor their unit-test call sites — change. Present in production (the application.review
  // @Component); absent in the lean broker unit ctors, where a REVIEW result would never arrive.
  private org.dradgo.application.review.ReviewResultHarvester reviewResultHarvester;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setReviewResultHarvester(
      org.dradgo.application.review.ReviewResultHarvester reviewResultHarvester) {
    this.reviewResultHarvester = reviewResultHarvester;
  }

  // Story 3f-4 (R1) — the advisory split-proposal harvester. Same optional-setter pattern as the
  // reviewer verdict harvester: a split-mode REVIEW result (idempotency key `split-proposal:…`)
  // routes here instead of ReviewResultHarvester, so step_reviews is never written for a split.
  private org.dradgo.application.workflow.SplitProposalHarvester splitProposalHarvester;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSplitProposalHarvester(
      org.dradgo.application.workflow.SplitProposalHarvester splitProposalHarvester) {
    this.splitProposalHarvester = splitProposalHarvester;
  }

  // Story 3f-4 (R3) — read the durable split-feedback reference id at compose time so the split
  // review bundle carries a {referenceId, kind:'split.feedback'} entry. Optional setter (absent in
  // lean unit ctors, where no split dispatch is composed).
  private org.dradgo.application.workflow.spi.SplitProposalReadPort splitProposalReadPort;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSplitProposalReadPort(
      org.dradgo.application.workflow.spi.SplitProposalReadPort splitProposalReadPort) {
    this.splitProposalReadPort = splitProposalReadPort;
  }

  // Story 3d-7 (FR69, AC3) — the per-credential provider usage/limit snapshot writer. Optional
  // SETTER injection (mirroring reviewResultHarvester) so none of the broker's telescoping
  // constructors — nor their unit-test call sites — change. Present in production (the
  // application.persistence @Component); absent (null) in the lean broker unit ctors, where
  // captureProviderUsage is a byte-identical no-op (legacy parity, AC7).
  private org.dradgo.application.runner.spi.ProviderUsageSnapshotWritePort
      providerUsageSnapshotWritePort;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setProviderUsageSnapshotWritePort(
      org.dradgo.application.runner.spi.ProviderUsageSnapshotWritePort
          providerUsageSnapshotWritePort) {
    this.providerUsageSnapshotWritePort = providerUsageSnapshotWritePort;
  }

  // Story 3.19 (AC5) — count a dispatch + record its duration (histogram, tagged stage), on the
  // successful-dispatch path. Exception-safe.
  private void recordDispatchMetrics(
      io.micrometer.core.instrument.Timer.Sample sample, RunnerStage stage) {
    try {
      sample.stop(
          meterRegistry.timer(
              "deliveryline.runner.dispatch.duration",
              "stage",
              stage == null ? "unknown" : stage.value()));
      meterRegistry.counter("deliveryline.runner.dispatched.count").increment();
    } catch (RuntimeException ignored) {
      // never let instrumentation break the dispatch path
    }
  }

  // Story 3.19 (AC5) — record the runner-completion outcome counter, tagged by stage + outcome.
  // Exception-safe: a metrics failure must never affect result processing.
  private void recordCompletion(RunnerStage stage, String outcome) {
    try {
      meterRegistry
          .counter(
              "deliveryline.runner.completed.count",
              "stage",
              stage == null ? "unknown" : stage.value(),
              "outcome",
              outcome)
          .increment();
    } catch (RuntimeException ignored) {
      // never let instrumentation break the dispatch/result path
    }
  }

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
          integrationLinkServiceProvider,
      // Story 3e-1 — the spec-runner question handler. ObjectProvider keeps the package-private
      // test
      // ctors stable; resolves to null when the bean is absent (lean/mock contexts) → no-op ingest.
      org.springframework.beans.factory.ObjectProvider<
              org.dradgo.application.clarification.ClarificationIngestService>
          clarificationIngestServiceProvider,
      // Story 3h-1 (AC3) — the pre-review build gate. ObjectProvider keeps the package-private test
      // ctors stable; resolves to null when the bean is absent (lean/mock contexts) → inline tail.
      org.springframework.beans.factory.ObjectProvider<
              org.dradgo.application.workflow.BuildStageService>
          buildStageServiceProvider) {
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
            integrationLinkServiceProvider::getIfAvailable,
        // Lazy: resolved at handleSuccess time. No cycle (the ingest service does not depend on the
        // broker), but kept lazy for ctor-stability symmetry with the other callbacks.
        (java.util.function.Supplier<
                org.dradgo.application.clarification.ClarificationIngestService>)
            clarificationIngestServiceProvider::getIfAvailable,
        // Story 3h-1 — lazy: resolved at handleSuccess time (breaks the broker↔build-stage cycle).
        (java.util.function.Supplier<org.dradgo.application.workflow.BuildStageService>)
            buildStageServiceProvider::getIfAvailable);
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
        () -> null,
        () -> null,
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
        () -> null,
        () -> null,
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
          integrationLinkServiceSupplier,
      java.util.function.Supplier<org.dradgo.application.clarification.ClarificationIngestService>
          clarificationIngestServiceSupplier,
      java.util.function.Supplier<org.dradgo.application.workflow.BuildStageService>
          buildStageServiceSupplier) {
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
    this.clarificationIngestServiceSupplier =
        clarificationIngestServiceSupplier == null
            ? () -> null
            : clarificationIngestServiceSupplier;
    this.buildStageServiceSupplier =
        buildStageServiceSupplier == null ? () -> null : buildStageServiceSupplier;
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Story 3h-2 (AC3) — optional setter injection of the lint gate (via an {@link
   * org.springframework.beans.factory.ObjectProvider} so the resolution is lazy + absent-tolerant).
   * Keeps every broker ctor site untouched; production Spring wires the {@code LintStageService}
   * bean, lean test contexts leave the default {@code () -> null} (no lint gate ⇒ tail unchanged).
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLintStageServiceProvider(
      org.springframework.beans.factory.ObjectProvider<
              org.dradgo.application.workflow.LintStageService>
          provider) {
    this.lintStageServiceSupplier = provider::getIfAvailable;
  }

  /**
   * Story 3h-4 (AC2) — optional setter injection of the delivery gate (via an {@link
   * org.springframework.beans.factory.ObjectProvider} so the resolution is lazy + absent-tolerant).
   * Keeps every broker ctor site untouched; production Spring wires the {@code DeliveryGateService}
   * bean, lean test contexts leave the default {@code () -> null} (no delivery gate ⇒ push inline).
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setDeliveryGateServiceProvider(
      org.springframework.beans.factory.ObjectProvider<
              org.dradgo.application.workflow.DeliveryGateService>
          provider) {
    this.deliveryGateServiceSupplier = provider::getIfAvailable;
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
        () -> null,
        () -> null,
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

  /**
   * @deprecated Story 3.17b — the legacy synchronous direct-dispatch path. Superseded by the queue:
   *     production code enqueues via {@link RunnerExecutionQueue} and the worker pool runs {@link
   *     #executeQueuedDispatch}. Retained for existing unit tests only — the ArchUnit rule {@code
   *     NO_PRODUCTION_CALLER_MAY_INVOKE_LEGACY_SYNCHRONOUS_DISPATCH} pins it to zero production
   *     callers (review D4). Do not call from production code.
   */
  @Deprecated
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
      // Story 3c-7 (AC2) — repoint to the run's Project (default-project fallback); byte-identical
      // for a single-project deployment. Mirrors the queue path in composeQueuedBundle.
      String resolvedRepositoryRef =
          (repoContextStage && repositoryWorkspaceService != null)
              ? resolveBundleRepositoryRef(workflowRunId)
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
      // Story 3b.1 (AC1) — thread the resolved sub-stage (null for INVESTIGATION, derived only in
      // the EXECUTION branch) so DockerRunnerAdapter emits implementation-plan / pr-output instead
      // of the coarse execution token. Deprecated sync path; kept consistent with the queue path.
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
              resolvedTicketRef,
              executionSubStage,
              // Story 3c-7 (AC2) — per-run OpenSpec opt-in resolved from the run's Project.
              resolveDispatchOpenSpec(workflowRunId));
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
  // executeQueuedDispatch — story 3.17b worker-pool dispatch (relocated dispatch body)
  // =====================================================================

  /**
   * Story 3.17b (AC1/AC3/AC4) — the relocated synchronous portion of {@link #dispatch}, run by the
   * {@link org.dradgo.application.runner.queue.RunnerWorkerPool} worker thread off a row the queue
   * already leased (status {@code running}, {@code worker_id}/{@code dispatched_at} stamped by
   * {@code dequeue}). It reconstructs the originating {@link ActorContext} + idempotency key from
   * the V14 carriage columns, reserves idempotency at worker time (preserving today's replay
   * semantics), composes the (repo-cloning, stateful) context bundle on this bounded thread
   * (Decision D2 — this is what makes "bounded resource use" real), dispatches to the adapter, and
   * emits the same {@code RUNNER_STARTED}/{@code RUNNER_DISPATCHED} audit events the synchronous
   * path did.
   *
   * <p>The dequeued running row REPLACES the synchronous path's fresh {@code insertPending} (AC1 —
   * exactly one row per execution). The result harvest ({@code onResult} + {@code
   * pollActiveExecutions}) is unchanged (R2/Trap T2): this method only covers ENQUEUE→DISPATCH.
   *
   * <p><b>Async failure (the queue-model semantic change).</b> The bundle is composed here, not in
   * the caller's transaction, so a clone/compose failure can no longer unwind the submit/approve
   * commit. Instead it drives the run to {@code Failed} via the same {@link #driveWorkflowFailed}
   * machinery {@code onResult} uses — mirroring a runner failure.
   *
   * <p>Restricted to the worker pool by an ArchUnit caller rule (AC3b/D4). MDC restores {@code
   * correlationId}/{@code workflowRunId}/{@code runnerExecutionId} for the dispatch duration (AC4).
   */
  public void executeQueuedDispatch(RunnerExecutionSnapshot leased) {
    Objects.requireNonNull(leased, "leased");
    String runnerExecutionId = leased.publicId();
    String workflowRunId = leased.workflowRunPublicId();
    RunnerStage stage = leased.stage();
    int contextBundleVersion = leased.contextBundleVersion();
    ActorContext actor = reconstructActor(leased);
    String idempotencyKey = leased.idempotencyKey();
    // Story 3d-2 (AC6) — a REVIEW execution is advisory and NEVER on the run's critical path: a
    // compose/dispatch failure degrades the reviewer execution (markFailed) but must NOT drive the
    // run to Failed. The run stays human-reviewable; the absence of a step_reviews verdict (with
    // the
    // failed reviewer runner_executions row) is the "review unavailable" state the panel renders.
    boolean isReview = stage == RunnerStage.REVIEW;

    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    String priorCorrelationMdc = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, leased.correlationId());
    try {
      log.info(
          "executeQueuedDispatch start runnerExecutionId={} workflowRunId={} stage={} queueAttempt={}",
          runnerExecutionId,
          workflowRunId,
          stage.value(),
          leased.queueAttemptCount());

      // Reset the stale clock to dispatch time: the row's last_activity_at/timeout_at were set at
      // enqueue, which may be much earlier than now if the row sat in the queue. The container the
      // adapter is about to start runs from HERE, so the stale/timeout scans must measure from now.
      // The worker runs with NO ambient transaction (unlike the synchronous dispatch path, which
      // ran inside the caller's submit/approve tx). Every persistence write below is therefore
      // wrapped in dispatchTransactionTemplate (PROPAGATION_REQUIRED → opens a fresh tx here); the
      // disk/adapter I/O (writeContextBundle / adapter.dispatch) deliberately stays OUTSIDE a tx so
      // a
      // slow dispatch never holds a DB connection.
      OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
      dispatchTransactionTemplate.executeWithoutResult(
          status ->
              recordPort.touchActivity(runnerExecutionId, now, runnerProperties.timeoutFor(stage)));

      // Reserve idempotency at worker-dispatch time (Decision: preserve today's replay semantics).
      // A REPLAY means a prior execution for this key already ran — this leased row is a duplicate
      // (normally prevented by the callers' QUEUED-aware in-flight guards). Retire it without
      // dispatching so it leaves ACTIVE_STATUSES and never reaches the adapter.
      if (idempotencyKey != null && !idempotencyKey.isBlank()) {
        String fingerprint = dispatchFingerprint(workflowRunId, stage, contextBundleVersion);
        ReservationOutcome reservation;
        try {
          reservation =
              idempotencyService.checkAndReserve(
                  idempotencyKey, "RunnerBroker.dispatch", actor.actorIdentity(), fingerprint);
        } catch (DomainException reservationConflict) {
          // Story 3d-2 (AC6) — a re-enqueued reviewer over the SAME producing execution reuses the
          // idempotency key "review:<rex>" but carries a fresh contextBundleVersion ⇒ a different
          // dispatch fingerprint. A prior COMPLETED/FAILED reservation then resolves to an
          // IDEMPOTENCY_KEY_CONFLICT (or stale/exhausted) HERE — outside the compose/dispatch try
          // blocks below. For a REVIEW execution, degrade the reviewer (mark its row failed)
          // instead
          // of letting the conflict escape the worker and strand the leased REVIEW row RUNNING: a
          // failed/duplicate second opinion never strands or blocks the run. Non-review stages keep
          // today's propagating behavior.
          if (isReview) {
            log.warn(
                "reviewer run failed (idempotency conflict) for run {} reviewerExec={} code={} — run"
                    + " remains human-reviewable",
                workflowRunId,
                runnerExecutionId,
                reservationConflict.errorCode().value());
            recordFailedBestEffort(runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION);
            return;
          }
          throw reservationConflict;
        }
        if (reservation.decision() == ReservationDecision.REPLAY) {
          log.warn(
              "executeQueuedDispatch duplicate leased row retired runnerExecutionId={} workflowRunId={} priorRunnerExecutionId={}",
              runnerExecutionId,
              workflowRunId,
              reservation.resultRef());
          retireDuplicateLeasedRow(runnerExecutionId);
          return;
        }
      }

      ExecutionConstraints constraints =
          new ExecutionConstraints(runnerProperties.timeoutFor(stage), false);
      // Story 3f-4 (R1) — a split-mode REVIEW dispatch (idempotency key `split-proposal:…`)
      // composes
      // a split bundle (splitProposalRequested=true + the by-reference feedback). Resolve the
      // feedback reference id from the durable store keyed by THIS execution (null for an initial
      // request / when the port is absent in a lean ctor).
      boolean splitProposalRequested =
          org.dradgo.application.workflow.SplitProposalService.isSplitDispatch(idempotencyKey);
      String splitFeedbackReferenceId =
          (splitProposalRequested && splitProposalReadPort != null)
              ? splitProposalReadPort.findFeedbackReferenceId(runnerExecutionId).orElse(null)
              : null;
      ComposedDispatch composed;
      try {
        composed =
            composeQueuedBundle(
                runnerExecutionId,
                workflowRunId,
                stage,
                contextBundleVersion,
                constraints,
                actor,
                splitProposalRequested,
                splitFeedbackReferenceId);
      } catch (DomainException compositionError) {
        // Async failure path: there is no caller transaction to unwind. Complete the reservation
        // FAILED (so a later same-key enqueue can re-reserve), then drive the run to Failed.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
          idempotencyService.complete(
              idempotencyKey, runnerExecutionId, IdempotencyRecordStatus.FAILED);
        }
        FailureCategory category = FailureCategory.RUNNER_CONTRACT_VIOLATION;
        recordFailedBestEffort(runnerExecutionId, category);
        if (isReview) {
          // Story 3d-2 (AC6) — graceful degrade: record the reviewer execution failed, leave the
          // run untouched (it stays WaitingForReview, fully human-reviewable). A failed second
          // opinion never strands a run.
          log.warn(
              "reviewer run failed (composition) for run {} reviewerExec={} errorCode={} — run remains human-reviewable",
              workflowRunId,
              runnerExecutionId,
              compositionError.errorCode().value());
          return;
        }
        log.warn(
            "executeQueuedDispatch composition failed -> Failed runnerExecutionId={} workflowRunId={} stage={} errorCode={}",
            runnerExecutionId,
            workflowRunId,
            stage.value(),
            compositionError.errorCode().value());
        driveWorkflowFailed(
            workflowRunId,
            runnerExecutionId,
            category,
            "runner dispatch composition failed: " + compositionError.errorCode().value());
        return;
      }

      // Mock path keeps the RUNNER_STARTED audit event the synchronous insertPending tx emitted;
      // the docker path emits RUNNER_DISPATCHED post-ack (below). The row already exists (running),
      // so there is no insert — just the event.
      boolean adapterTakesOverDispatchEvent =
          runnerAdapter instanceof RecoverableRunnerAdapter recoverable
              && recoverable.emitsDispatchedAfterAck();
      if (!adapterTakesOverDispatchEvent) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runnerExecutionId", runnerExecutionId);
        details.put("stage", stage.value());
        details.put("contextBundleVersion", contextBundleVersion);
        if (idempotencyKey != null) {
          details.put("idempotencyKey", idempotencyKey);
        }
        dispatchTransactionTemplate.executeWithoutResult(
            status ->
                eventPort.append(
                    workflowRunId,
                    WorkflowEventType.RUNNER_STARTED,
                    actor,
                    "runner_dispatched",
                    null,
                    OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
                    details));
      }

      // Story 3.17b review (D1): the dispatch I/O below (bundle write + adapter.dispatch + post-ack
      // RUNNER_DISPATCHED write) runs with NO caller transaction to unwind it — unlike the
      // synchronous path, where an adapter throw rolled back the reservation + row inside the
      // caller's submit/approve tx. A failure here must therefore mirror the composition-failure
      // branch above: mark the reservation FAILED (so a later same-key enqueue can re-reserve
      // rather
      // than REPLAY-retire forever), record the run failed, and drive the workflow to Failed —
      // otherwise the row is stranded `running` behind a poisoned COMPLETED key. The reservation is
      // completed COMPLETED only AFTER a successful dispatch so it reflects true success.
      try {
        java.nio.file.Path bundlePath =
            scratchStore.writeContextBundle(runnerExecutionId, composed.bundle().redactedPayload());
        org.dradgo.domain.registry.RunnerKind dispatchKind;
        if (isReview) {
          // Story 3d-2 (DD-1/DD-7) — the reviewer kind is per-project, resolved via
          // resolveReviewerKind (NOT the global kindForStage, which throws for REVIEW). A binding
          // that became unresolvable/misconfigured since enqueue throws here and is caught by the
          // dispatch-failure branch below, which degrades the reviewer (does NOT fail the run).
          dispatchKind =
              projectRuntimeConfigResolver
                  .resolveReviewerKind(workflowRunId)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "reviewer binding resolved empty at dispatch for run "
                                  + workflowRunId));
        } else {
          dispatchKind =
              projectRuntimeConfigResolver != null
                  ? projectRuntimeConfigResolver.resolveRunnerKind(
                      workflowRunId, stage, composed.executionSubStage())
                  : (stage == RunnerStage.EXECUTION
                      ? runnerProperties.kindForExecutionSubStage(composed.executionSubStage())
                      : runnerProperties.kindForStage(stage));
        }
        // Story 3b.1 (AC1) — queue/production path: carry composed.executionSubStage() (null for
        // INVESTIGATION) so the adapter selects implementation-plan / pr-output. This is the exact
        // wire field that was wrong on run_ae258… (execution → always prOutput).
        RunnerDispatchRequest request =
            new RunnerDispatchRequest(
                runnerExecutionId,
                workflowRunId,
                stage,
                dispatchKind,
                bundlePath,
                constraints,
                composed.bundle().effectiveClassification(),
                composed.resolvedRepositoryRef(),
                composed.resolvedTicketRef(),
                composed.executionSubStage(),
                // Story 3c-7 (AC2) — per-run OpenSpec opt-in resolved from the run's Project.
                resolveDispatchOpenSpec(workflowRunId));
        // Story 3.19 (AC5) — time the adapter dispatch (histogram, tagged stage) + count
        // dispatches.
        io.micrometer.core.instrument.Timer.Sample dispatchSample =
            io.micrometer.core.instrument.Timer.start(meterRegistry);
        RunnerDispatchAck ack = runnerAdapter.dispatch(request);
        recordDispatchMetrics(dispatchSample, stage);
        // Docker path's RUNNER_DISPATCHED is a JPA write — wrap it in a tx (the worker has no
        // ambient one). Mock path appended RUNNER_STARTED above and this is a no-op.
        dispatchTransactionTemplate.executeWithoutResult(
            status ->
                appendRunnerDispatchedEventIfDocker(
                    workflowRunId, runnerExecutionId, request.runnerKind(), ack, actor));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
          idempotencyService.complete(
              idempotencyKey, runnerExecutionId, IdempotencyRecordStatus.COMPLETED);
        }
        log.info(
            "executeQueuedDispatch ok workflowRunId={} stage={} runnerExecutionId={} contextBundleVersion={} adapterRef={}",
            workflowRunId,
            stage.value(),
            runnerExecutionId,
            contextBundleVersion,
            ack.adapterRef());
      } catch (RuntimeException dispatchError) {
        // Async failure path (no caller tx to unwind): mark the reservation FAILED, record the run
        // failed, and drive the workflow to Failed — mirrors the composition-failure branch so an
        // adapter/disk fault never strands a `running` row behind a COMPLETED idempotency key.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
          idempotencyService.complete(
              idempotencyKey, runnerExecutionId, IdempotencyRecordStatus.FAILED);
        }
        FailureCategory category = FailureCategory.RUNNER_CRASH;
        recordFailedBestEffort(runnerExecutionId, category);
        if (isReview) {
          // Story 3d-2 (AC6) — graceful degrade: a reviewer dispatch fault never strands the run.
          log.warn(
              "reviewer run failed (dispatch) for run {} reviewerExec={} cause={} — run remains human-reviewable",
              workflowRunId,
              runnerExecutionId,
              dispatchError.getClass().getSimpleName());
          return;
        }
        log.warn(
            "executeQueuedDispatch dispatch failed -> Failed runnerExecutionId={} workflowRunId={} stage={} cause={}",
            runnerExecutionId,
            workflowRunId,
            stage.value(),
            dispatchError.toString());
        driveWorkflowFailed(
            workflowRunId,
            runnerExecutionId,
            category,
            "runner dispatch failed: " + dispatchError.getClass().getSimpleName());
      }
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationMdc);
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 3c-7 (AC2) — the bundle/clone repository ref for a run, read from the run's Project via
   * {@link org.dradgo.application.project.ProjectRuntimeConfigResolver#resolveRepositoryRef} (the
   * {@code default}-project fallback returns the same {@code owner/repo} the global path returned,
   * so a single-project deployment is byte-identical, AC7 parity). Falls back to the global
   * single-repo config only in the lean broker unit contexts where no resolver is wired.
   */
  private String resolveBundleRepositoryRef(String workflowRunId) {
    if (projectRuntimeConfigResolver == null) {
      String ref = repositoryWorkspaceService.resolveConfiguredRepositoryRef().orElse(null);
      log.info(
          "bundle repo-ref resolved workflowRunId={} projectId={} repoRef={} source=global_fallback",
          workflowRunId,
          "none",
          ref);
      return ref;
    }
    // Story 3c-7 review (P4) — resolve the Project once so the dispatch-decision log carries
    // project.publicId() for FR63 traceability (no extra resolve vs the prior resolveRepositoryRef
    // call, which resolved the run's Project internally); the ref derivation is identical
    // (normalizeRepositoryUrl of the project's repositoryUrl, exactly resolveRepositoryRef's body).
    Project project = projectRuntimeConfigResolver.resolveForRun(workflowRunId);
    String ref = RepositoryRef.normalizeRepositoryUrl(project.repositoryUrl());
    log.info(
        "bundle repo-ref resolved workflowRunId={} projectId={} repoRef={} source=run_project",
        workflowRunId,
        project.publicId(),
        ref);
    return ref;
  }

  /**
   * Story 3c-7 (AC2 / R2) — the per-run OpenSpec opt-in resolved from the run's Project via {@link
   * org.dradgo.application.project.ProjectRuntimeConfigResolver#resolveOpenSpecEnabled} and
   * threaded onto the {@link RunnerDispatchRequest} so {@code DockerRunnerAdapter} emits {@code
   * DELIVERYLINE_RUNNER_OPENSPEC} from the request, not the global property. The {@code default}
   * project is seeded from {@code deliveryline.runner.openspec.enabled}, so a single-project
   * deployment is byte-identical (flag off ⇒ no env var). Falls back to the global property only in
   * the lean broker unit contexts where no resolver is wired.
   */
  private boolean resolveDispatchOpenSpec(String workflowRunId) {
    if (projectRuntimeConfigResolver == null) {
      boolean openspec = runnerProperties.openSpecEnabled();
      log.info(
          "openspec resolved workflowRunId={} projectId={} openspec={} source=global_fallback",
          workflowRunId,
          "none",
          openspec);
      return openspec;
    }
    // Story 3c-7 review (P4) — resolve the Project once so the log carries project.publicId()
    // (FR63);
    // openspec value is identical to resolveOpenSpecEnabled (project.openspecEnabled()).
    Project project = projectRuntimeConfigResolver.resolveForRun(workflowRunId);
    boolean openspec = project.openspecEnabled();
    log.info(
        "openspec resolved workflowRunId={} projectId={} openspec={} source=run_project",
        workflowRunId,
        project.publicId(),
        openspec);
    return openspec;
  }

  /**
   * Story 3d-3 (AC3 / R2) — compose the runner-contracts input bundle for a step parked under the
   * {@code manual} runner kind and write it to the scratch store, returning the on-disk bundle path
   * so {@code 3d-4} can serve it. This reuses the exact compose+write the deprecated synchronous
   * {@code dispatch} body did (clone the workspace, build the redacted bundle, persist it), but
   * stops BEFORE any adapter/image lookup — a {@code manual} kind never launches a container.
   *
   * <p>Runs EAGERLY in the caller's transaction (the dispatcher's {@code @Transactional} / the
   * submit/approve tx), unlike the worker path's lazy compose — so a clone/compose failure
   * propagates as a {@link DomainException} and rolls the whole park (row insert + event +
   * transition) back. One-way edge: {@code ManualExecutionDispatcher → RunnerBroker} (compose
   * only); the broker never depends back on the dispatcher (R2 — the broker↔orchestration cycle
   * stays broken).
   *
   * @return the path to the written redacted context bundle
   */
  public java.nio.file.Path composeAndWriteManualBundle(
      String workflowRunId,
      RunnerStage stage,
      String runnerExecutionId,
      int contextBundleVersion,
      ActorContext actor) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(actor, "actor");
    ExecutionConstraints constraints =
        new ExecutionConstraints(runnerProperties.timeoutFor(stage), false);
    // Manual-bundle path (3d-3/3d-4) is never a split dispatch — pass split-mode off.
    ComposedDispatch composed =
        composeQueuedBundle(
            runnerExecutionId,
            workflowRunId,
            stage,
            contextBundleVersion,
            constraints,
            actor,
            false,
            null);
    java.nio.file.Path bundlePath =
        scratchStore.writeContextBundle(runnerExecutionId, composed.bundle().redactedPayload());
    log.info(
        "composeAndWriteManualBundle wrote bundle workflowRunId={} runnerExecutionId={} stage={} contextBundleVersion={} bundlePath={}",
        workflowRunId,
        runnerExecutionId,
        stage.value(),
        contextBundleVersion,
        bundlePath);
    return bundlePath;
  }

  /** Worker-dispatch composition result carried out of {@link #composeQueuedBundle}. */
  private record ComposedDispatch(
      ContextBundle bundle,
      ExecutionSubStage executionSubStage,
      String resolvedRepositoryRef,
      String resolvedTicketRef) {}

  /**
   * Story 3.17b — the repo-resolve + bundle-compose portion of the relocated dispatch body (the
   * worker analog of {@link #dispatch}'s lines that materialize the workspace and compose the
   * bundle). A clone/compose failure is surfaced as a {@link DomainException} so the worker can
   * drive the run to Failed (there is no caller transaction to unwind in the async model).
   */
  private ComposedDispatch composeQueuedBundle(
      String runnerExecutionId,
      String workflowRunId,
      RunnerStage stage,
      int contextBundleVersion,
      ExecutionConstraints constraints,
      ActorContext actor,
      boolean splitProposalRequested,
      String splitFeedbackReferenceId) {
    boolean repoContextStage = stage == RunnerStage.INVESTIGATION || stage == RunnerStage.EXECUTION;
    String resolvedRepositoryRef =
        (repoContextStage && repositoryWorkspaceService != null)
            ? resolveBundleRepositoryRef(workflowRunId)
            : null;
    ExecutionSubStage executionSubStage = null;
    RepositoryContextSummary repositoryContextSummary = null;
    String resolvedTicketRef = null;
    String repositoryBranchRef = null;
    try {
      if (resolvedRepositoryRef != null) {
        if (stage == RunnerStage.EXECUTION) {
          resolvedTicketRef = resolveExecutionTicketRef(workflowRunId);
        }
        RepositoryWorkspaceService.RepositoryMount mount =
            repositoryWorkspaceService.prepareWorkspace(
                workflowRunId,
                stage,
                runnerExecutionId,
                resolvedTicketRef,
                null,
                resolvedRepositoryRef);
        repositoryContextSummary =
            repositoryWorkspaceService.summarize(
                mount, RepositoryWorkspaceService.configMappingVersion(resolvedRepositoryRef));
        repositoryBranchRef = mount.branch();
        log.info(
            "executeQueuedDispatch repo-context resolved workflowRunId={} stage={} repositoryRef={} branchRef={} contextBundleVersion={}",
            workflowRunId,
            stage.value(),
            resolvedRepositoryRef,
            repositoryBranchRef,
            contextBundleVersion);
      } else if (repoContextStage) {
        log.info(
            "executeQueuedDispatch repo-context skipped workflowRunId={} stage={} reason={}",
            workflowRunId,
            stage.value(),
            repositoryWorkspaceService == null ? "no_workspace_service" : "no_repo_resolved");
      }

      ContextBundle bundle;
      if (stage == RunnerStage.INVESTIGATION) {
        bundle =
            contextBundleService.createForSpecInvestigation(
                workflowRunId,
                runnerExecutionId,
                contextBundleVersion,
                constraints,
                DataClassification.SHAREABLE_REDACTED,
                actor,
                repositoryContextSummary);
      } else if (stage == RunnerStage.REVIEW) {
        // Story 3d-2 (Task 4) — the advisory reviewer reviews an existing WaitingForReview output
        // artifact; read-only, no repo workspace, no execution sub-stage. createForReview derives
        // the reviewed artifact and references its already-redacted content.
        bundle =
            contextBundleService.createForReview(
                workflowRunId,
                runnerExecutionId,
                contextBundleVersion,
                constraints,
                DataClassification.SHAREABLE_REDACTED,
                actor,
                splitProposalRequested,
                splitFeedbackReferenceId);
      } else {
        executionSubStage = contextBundleService.deriveExecutionSubStage(workflowRunId);
        log.info(
            "executeQueuedDispatch execution sub-stage derived workflowRunId={} subStage={} repoContextPresent={}",
            workflowRunId,
            executionSubStage,
            repositoryContextSummary != null);
        bundle =
            contextBundleService.create(
                workflowRunId,
                stage,
                runnerExecutionId,
                contextBundleVersion,
                constraints,
                DataClassification.SHAREABLE_REDACTED,
                actor,
                executionSubStage,
                repositoryContextSummary,
                repositoryBranchRef);
      }
      return new ComposedDispatch(
          bundle, executionSubStage, resolvedRepositoryRef, resolvedTicketRef);
    } catch (GitCommandException gitError) {
      log.warn(
          "executeQueuedDispatch repo-context preparation failed workflowRunId={} stage={} runnerExecutionId={} gitFailureCategory={}",
          workflowRunId,
          stage.value(),
          runnerExecutionId,
          gitError.failureCategory().value());
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("workflowRunId", workflowRunId);
      details.put("runnerExecutionId", runnerExecutionId);
      details.put("stage", stage.value());
      details.put("repositoryRef", resolvedRepositoryRef);
      details.put("gitFailureCategory", gitError.failureCategory().value());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Repository workspace preparation failed during dispatch",
          details);
    }
  }

  private ActorContext reconstructActor(RunnerExecutionSnapshot leased) {
    String identity =
        leased.actorIdentity() == null || leased.actorIdentity().isBlank()
            ? "system"
            : leased.actorIdentity();
    org.dradgo.domain.registry.ActorType actorType =
        leased.actorType() == null
            ? org.dradgo.domain.registry.ActorType.SYSTEM
            : org.dradgo.domain.registry.ActorType.fromValue(
                leased.actorType(), "runner_executions.actor_type");
    return new ActorContext(identity, actorType, leased.correlationId());
  }

  private void retireDuplicateLeasedRow(String runnerExecutionId) {
    try {
      // Guarded transition needs an ambient tx; the worker has none, so open one.
      dispatchTransactionTemplate.executeWithoutResult(
          status -> executionService.recordOrphaned(runnerExecutionId));
    } catch (DomainException ignored) {
      // Already terminal (a concurrent scan/recovery beat us) — nothing to retire.
      log.debug(
          "executeQueuedDispatch duplicate retire no-op runnerExecutionId={} errorCode={}",
          runnerExecutionId,
          ignored.errorCode().value());
    }
  }

  private void recordFailedBestEffort(String runnerExecutionId, FailureCategory category) {
    try {
      // Guarded transition needs an ambient tx; the worker has none, so open one.
      dispatchTransactionTemplate.executeWithoutResult(
          status -> executionService.recordFailed(runnerExecutionId, category));
    } catch (DomainException alreadyTerminal) {
      log.debug(
          "executeQueuedDispatch recordFailed no-op runnerExecutionId={} errorCode={}",
          runnerExecutionId,
          alreadyTerminal.errorCode().value());
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
    // Story 3.19 (AC5) — completion outcome recorded once at exit (the branches below set it). A
    // late result is bucketed "late_result"; the handleSuccess artifactRefs-empty sub-branch routes
    // to failure internally and is the one case counted "success" (a known minor approximation for
    // an observability counter, documented here).
    String completionOutcome = null;
    try {

      // AC5 split (late-result branch): a row that has already been marked TIMED_OUT or ORPHANED
      // cannot legally transition further. A result arriving now is, by definition, late — emit
      // RUNNER_FAILED with runner_late_result and forward artifact references via
      // ArtifactOperationService (which already marks them late_or_stale per 1.12 AC10), but
      // do NOT change the workflow-run state. Operator-driven recovery owns that path.
      if (row.status() == RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER) {
        log.info(
            "onResult ignored runnerExecutionId={} workflowRunId={} reason=cancelled_for_takeover",
            runnerExecutionId,
            workflowRunId);
        completionOutcome = "late_result";
        return;
      }
      if (row.status() == RunnerExecutionStatus.TIMED_OUT
          || row.status() == RunnerExecutionStatus.ORPHANED) {
        handleLateResult(runnerExecutionId, workflowRunId, row, payloadBytes);
        completionOutcome = "late_result";
        return;
      }

      // Story 3d-2 (Task 5) — a REVIEW execution harvests its verdict into step_reviews (DD-2: no
      // artifacts row) and NEVER transitions the run (DD-9). All reviewer failure modes degrade
      // gracefully inside the harvester (AC6) — never failing the run.
      if (row.stage() == RunnerStage.REVIEW) {
        // Story 3f-4 (R1) — a split-mode REVIEW execution (idempotency key `split-proposal:…`)
        // emits a split-proposal.v1 decomposition, NOT a verdict: route it to the split harvester
        // so step_reviews is never written for a split. Distinguished by the durable key prefix
        // pinned at enqueue (no new runner stage). Non-split REVIEW results keep the 3d-2 path.
        boolean splitMode =
            org.dradgo.application.workflow.SplitProposalService.isSplitDispatch(
                row.idempotencyKey());
        if (splitMode) {
          if (splitProposalHarvester == null) {
            log.warn(
                "split-proposal result received with no harvester wired runnerExecutionId={} workflowRunId={}",
                runnerExecutionId,
                workflowRunId);
            recordFailedBestEffort(runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION);
            completionOutcome = "failure";
            return;
          }
          completionOutcome =
              splitProposalHarvester.harvest(runnerExecutionId, workflowRunId, payloadBytes);
          return;
        }
        if (reviewResultHarvester == null) {
          // Defensive: a REVIEW result with no harvester wired (lean unit ctor) — record failed so
          // the row leaves ACTIVE_STATUSES without touching the run.
          log.warn(
              "review result received with no harvester wired runnerExecutionId={} workflowRunId={}",
              runnerExecutionId,
              workflowRunId);
          recordFailedBestEffort(runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION);
          completionOutcome = "failure";
          return;
        }
        completionOutcome =
            reviewResultHarvester.harvest(runnerExecutionId, workflowRunId, payloadBytes);
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
        completionOutcome = "failure";
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
        completionOutcome = "failure";
        return;
      }

      JsonNode failureCategoryNode = parsed.get("failureCategory");
      if (failureCategoryNode != null
          && failureCategoryNode.isTextual()
          && !failureCategoryNode.asText().isBlank()) {
        handleNonZeroExit(runnerExecutionId, workflowRunId, failureCategoryNode.asText());
        completionOutcome = "failure";
        return;
      }

      handleSuccess(runnerExecutionId, workflowRunId, row, parsed);
      completionOutcome = "success";
    } finally {
      if (completionOutcome != null) {
        recordCompletion(row.stage(), completionOutcome);
      }
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  // Story 3d-7 (FR69, AC3) — read the OPTIONAL normalizedOutput.providerUsage metadata and persist
  // a
  // per-credential, NON-SECRET usage snapshot. The broker is the FIRST consumer of
  // normalizedOutput,
  // so this read is tolerant of absence (legacy/default runner → no row, AC7) and of the writer
  // being
  // unwired (lean unit ctors → no-op). Best-effort: any failure is logged WARN and swallowed so a
  // successfully-executed run is never unwound by a snapshot-write error. NEVER logs a token — only
  // the non-secret account label + window counts.
  private void captureProviderUsage(
      String workflowRunId, String runnerExecutionId, JsonNode parsed) {
    org.dradgo.application.runner.spi.ProviderUsageSnapshotWritePort writePort =
        providerUsageSnapshotWritePort;
    if (writePort == null) {
      return;
    }
    JsonNode usage = parsed.path("normalizedOutput").path("providerUsage");
    if (usage.isMissingNode() || !usage.isObject()) {
      return;
    }
    try {
      String signalState = usage.path("signalState").asText(null);
      String accountReference = usage.path("accountLabel").asText(null);
      if (signalState == null || accountReference == null || accountReference.isBlank()) {
        log.warn(
            "onResult providerUsage missing signalState/accountLabel runnerExecutionId={} "
                + "workflowRunId={} — skipping snapshot",
            runnerExecutionId,
            workflowRunId);
        return;
      }
      // Review 2026-06-24: reject any signalState outside the runner-contract enum rather than
      // letting it reach (and trip) the DB CHECK as a swallowed DataIntegrityViolation.
      if (!"available".equals(signalState) && !"not_exposed".equals(signalState)) {
        log.warn(
            "onResult providerUsage unrecognized signalState={} runnerExecutionId={} "
                + "workflowRunId={} — skipping snapshot",
            MdcKeys.sanitizeForLog(signalState),
            runnerExecutionId,
            workflowRunId);
        return;
      }
      // Window numbers are only meaningful when the provider exposed a signal; for not_exposed we
      // persist no window values so a contradictory "not_exposed + numbers" row can never exist.
      boolean available = "available".equals(signalState);
      JsonNode fiveHour =
          available
              ? usage.path("fiveHour")
              : com.fasterxml.jackson.databind.node.NullNode.getInstance();
      JsonNode weekly =
          available
              ? usage.path("weekly")
              : com.fasterxml.jackson.databind.node.NullNode.getInstance();
      // Review 2026-06-24 (#332): asOf is REQUIRED by the runner-contract, but JSON-schema
      // `format: date-time` is annotation-only (not asserted), so a present-but-unparseable asOf
      // slips past validation and yields a null instant here. An available snapshot with no
      // provenance timestamp is a provider-reported value the story forbids surfacing —
      // skip-persist
      // it rather than store a reading with no as-of stamp. not_exposed legitimately carries no
      // window numbers and a null asOf is tolerated there (a documented absence, not a reading).
      java.time.OffsetDateTime asOf = providerUsageInstant(usage, "asOf");
      if (available && asOf == null) {
        log.warn(
            "onResult providerUsage available signal missing asOf timestamp runnerExecutionId={} "
                + "workflowRunId={} — skipping snapshot (no provenance)",
            runnerExecutionId,
            workflowRunId);
        return;
      }
      String snapshotId = org.dradgo.domain.id.PublicIdPrefixes.PROVIDER_USAGE_SNAPSHOT.next();
      writePort.insert(
          new org.dradgo.application.runner.spi.ProviderUsageSnapshotWritePort
              .NewProviderUsageSnapshot(
              snapshotId,
              workflowRunId,
              runnerExecutionId,
              accountReference,
              signalState,
              providerUsageDouble(fiveHour, "usedFraction"),
              providerUsageInt(fiveHour, "used"),
              providerUsageInt(fiveHour, "limit"),
              providerUsageInstant(fiveHour, "resetsAt"),
              providerUsageDouble(weekly, "usedFraction"),
              providerUsageInt(weekly, "used"),
              providerUsageInt(weekly, "limit"),
              providerUsageInstant(weekly, "resetsAt"),
              asOf));
      log.info(
          "onResult provider-usage snapshot persisted runnerExecutionId={} workflowRunId={} "
              + "snapshotId={} signalState={} accountReference={}",
          runnerExecutionId,
          workflowRunId,
          snapshotId,
          signalState,
          accountReference);
    } catch (RuntimeException error) {
      log.warn(
          "onResult provider-usage snapshot capture failed (best-effort) runnerExecutionId={} "
              + "workflowRunId={} cause={}",
          runnerExecutionId,
          workflowRunId,
          MdcKeys.sanitizeForLog(error.toString()));
    }
  }

  // Story 3g-3 (FR74) — capture the OPTIONAL per-execution token counts emitted on
  // normalizedOutput.usage and persist them onto the runner_executions row (V31 columns) via the
  // metadata-only recordTokenUsage write. Distinct from providerUsage (rolling subscription window,
  // separate table): these are this-execution input/output/total token counts on the row itself.
  // Tolerant of absence (legacy/default runner / no-usage / command-only run → no key, AC4) and of
  // a malformed block (each field sanitized independently to null; a non-object is skipped). Each
  // count is persisted exactly as reported — totalTokens is NEVER synthesized from input+output.
  // Best-effort: any failure is logged WARN and swallowed so a successfully-executed run is never
  // unwound by a token-capture error. Token COUNTS are non-secret numeric governed data and are
  // safe to log; the raw agent payload is NEVER logged.
  private void captureTokenUsage(String workflowRunId, String runnerExecutionId, JsonNode parsed) {
    JsonNode usage = parsed.path("normalizedOutput").path("usage");
    if (usage.isMissingNode() || !usage.isObject()) {
      return;
    }
    try {
      Integer inputTokens = tokenCount(usage, "inputTokens");
      Integer outputTokens = tokenCount(usage, "outputTokens");
      Integer totalTokens = tokenCount(usage, "totalTokens");
      if (inputTokens == null && outputTokens == null && totalTokens == null) {
        log.warn(
            "onResult token-usage present but no usable counts runnerExecutionId={} "
                + "workflowRunId={} — skipping write",
            runnerExecutionId,
            workflowRunId);
        return;
      }
      recordPort.recordTokenUsage(runnerExecutionId, inputTokens, outputTokens, totalTokens);
      log.info(
          "onResult token-usage persisted runnerExecutionId={} workflowRunId={} input={} "
              + "output={} total={}",
          runnerExecutionId,
          workflowRunId,
          inputTokens,
          outputTokens,
          totalTokens);
    } catch (RuntimeException error) {
      log.warn(
          "onResult token-usage capture failed (best-effort) runnerExecutionId={} "
              + "workflowRunId={} cause={}",
          runnerExecutionId,
          workflowRunId,
          MdcKeys.sanitizeForLog(error.toString()));
    }
  }

  // usedFraction is a 0..1 ratio; drop non-finite or out-of-range values rather than persisting a
  // fabricated/contradictory figure (symmetric with the runner's sanitizeUsageWindow). Review
  // 2026-06-24.
  private static Double providerUsageDouble(JsonNode window, String field) {
    JsonNode node = window.path(field);
    if (!node.isNumber()) {
      return null;
    }
    double value = node.doubleValue();
    return (Double.isFinite(value) && value >= 0.0d && value <= 1.0d) ? value : null;
  }

  // Story 3g-3 — a non-negative int reader for token counts: reuses the shared providerUsageInt
  // parsing (int/long/integral-double, rejects fractional + out-of-range) then drops a NEGATIVE
  // count to null (the runner-contract asserts minimum:0, but a hostile/garbage payload could still
  // carry -1; AC4 sanitizes each field independently rather than rejecting the block wholesale).
  private static Integer tokenCount(JsonNode usage, String field) {
    Integer value = providerUsageInt(usage, field);
    return (value != null && value >= 0) ? value : null;
  }

  private static Integer providerUsageInt(JsonNode window, String field) {
    JsonNode node = window.path(field);
    if (node.isInt() || node.isLong()) {
      long value = node.longValue();
      // Reject out-of-int-range longs instead of silently truncating via intValue(). Review
      // 2026-06-24.
      return (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) ? (int) value : null;
    }
    // Tolerate an integral double (e.g. 62.0) from a lenient serializer; reject fractional or
    // out-of-range/non-finite values rather than truncating.
    if (node.isNumber()) {
      double value = node.doubleValue();
      if (Double.isFinite(value)
          && value == Math.rint(value)
          && value >= Integer.MIN_VALUE
          && value <= Integer.MAX_VALUE) {
        return (int) value;
      }
    }
    return null;
  }

  private static java.time.OffsetDateTime providerUsageInstant(JsonNode window, String field) {
    String raw = window.path(field).asText(null);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return java.time.OffsetDateTime.parse(raw);
    } catch (java.time.format.DateTimeParseException ignored) {
      // Runner emits ISO-8601; a non-parseable value is dropped rather than failing the capture.
      return null;
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
    // Story 3b-5 (AC1/AC3) — the prOutput unified diff resolved from scratch ONCE in the loop and
    // re-used at the enrich (v2) write site, so the highest-version artifact the reviewer reads
    // carries it without a second scratch read. Stays null for non-pr-output results.
    String prOutputResolvedDiff = null;
    for (JsonNode ref : artifactRefs) {
      String typeValue = ref.path("artifactType").asText();
      ArtifactType artifactType =
          ArtifactType.fromValue(typeValue, "runner_result.artifactReferences.artifactType");
      // Story 3b-5 (AC1/AC3) — resolve the prOutput unified diff from scratch HERE (the scratch dir
      // is ephemeral; read-time resolution is impossible) and embed it in the persisted payload at
      // BOTH write sites (this in-loop v1 + the enriched v2 UPDATE). resolveImplementationArtifact
      // reads the highest version, so the diff must be in both. Empty/absent scratch omits the diff
      // gracefully (today's runners emit only the diffReference pointer, not the file — OQ-1), and
      // the read path degrades to an empty-diff panel.
      String resolvedDiff = null;
      if (artifactType == ArtifactType.PR_OUTPUT) {
        resolvedDiff =
            resolvePrOutputDiff(
                    runnerExecutionId, workflowRunId, ref.path("artifactId").asText(null), ref)
                .orElse(null);
        prOutputResolvedDiff = resolvedDiff;
      }
      Optional<ArtifactPayload> maybePayload =
          artifactPayload(runnerExecutionId, ref, resolvedDiff);
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
      // Story 3e-2 (AC5/R3) — GRAFT a re-produced artifact onto its prior lineage. When the run
      // already has an active lineage for THIS artifact type, route through UPDATE
      // (createNextVersion
      // sets parentArtifactId = prior head) instead of minting a FRESH lineage (CREATE) that the
      // domain rejects with ARTIFACT_LINEAGE_ALREADY_EXISTS. The FIRST artifact of a type (no prior
      // lineage) stays CREATE.
      //
      // Originally spec-only (the spec-regeneration loop needed lineageArtifactIds(v2) to contain
      // v1
      // for the clarification sweep). Generalized to ALL artifact types so a rejection-driven
      // RE-EXECUTION of the implementationPlan / prOutput stage advances the lineage to v2 rather
      // than failing ingestion on a duplicate CREATE (the implementation-rejection re-plan loop was
      // otherwise unshippable). Type-specific post-update work stays gated downstream: the
      // clarification sweep + spec payload-ref canonicalization only run for SPEC in
      // ArtifactOperationService, so a non-spec UPDATE is a plain version advance.
      //
      // Story 3e-2 (review P1) — the CREATE-vs-UPDATE decision is computed from MUTABLE state
      // (hasActiveLineage), but the recordOperation replay key includes operation_type. On a
      // re-harvest of THIS result (at-least-once delivery, or the best-effort markArtifactAvailable
      // -failed re-mark path below), hasActiveLineage has flipped false->true because this
      // execution's own artifact is now recorded — so a naive recompute would pick UPDATE, the
      // replay would MISS the stored CREATE row, and createNextVersion would mint a SPURIOUS v2.
      // Guard by REUSING the operation_type a prior harvest already recorded for this idempotency
      // key; only the FIRST harvest (no recorded op) computes the type fresh, so it is idempotent.
      ArtifactOperationType operationType =
          artifactOperationService
              .recordedOperationType(workflowRunId, artifactType, idempotencyKey)
              .orElseGet(
                  () ->
                      artifactOperationService.hasActiveLineage(workflowRunId, artifactType)
                          ? ArtifactOperationType.UPDATE
                          : ArtifactOperationType.CREATE);
      if (operationType == ArtifactOperationType.UPDATE) {
        log.info(
            "onResult grafting re-produced artifact onto prior lineage artifactType={} runnerExecutionId={} workflowRunId={}",
            typeValue,
            runnerExecutionId,
            workflowRunId);
      }
      RecordArtifactOperationCommand command =
          new RecordArtifactOperationCommand(
              workflowRunId,
              artifactType,
              operationType,
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
      // Availability wiring — promote the freshly-ingested runner artifact to `available` (checksum
      // + storageRef stamped) so the approval-eligibility gate accepts it. Without this the
      // artifact
      // stays `pending` and ArtifactService.isApprovalEligible (which requires status=AVAILABLE)
      // rejects approval with ARTIFACT_PAYLOAD_UNAVAILABLE — the reviewer can see the artifact but
      // never accept it. The auto-advance (Decision D1) still fires on ingest regardless; this only
      // makes the resulting artifact approval-eligible.
      // Story 3b-3: widened from SPEC-only to also cover IMPLEMENTATION_PLAN and PR_OUTPUT so the
      // execution-stage acceptImplementation gate (isApprovalEligible = AVAILABLE) can fire — this
      // supersedes 3a-9's Decision-D1 `pending`-on-ingest posture for the two execution-stage
      // types.
      // NOTE (THE TRAP): a real PR_OUTPUT with a GitHub push is later enriched
      // (validateAndEnrichPrOutput -> enrichPrOutputArtifact) which createNextVersions a new
      // PENDING
      // v2; that enriched head is marked available separately inside enrichPrOutputArtifact. This
      // in-loop mark covers v1 (the no-enrich cases: plan, no-push pr-output, and every mock-runner
      // result where captureAndPush returns empty).
      // Story 3e-2 (AC6) — persist the rebuilt spec's structured clarificationAcknowledgements
      // BEFORE markArtifactAvailable, because markAvailable triggers the clarification sweep
      // SYNCHRONOUSLY and the sweep reads these rows by the spec artifactId. Best-effort + dedup +
      // pre-flight probe (shared-tx poison trap) inside the ingest service. EXECUTION-stage results
      // carry no spec acknowledgements.
      if (artifactType == ArtifactType.SPEC && row.stage() == RunnerStage.INVESTIGATION) {
        ingestSpecAcknowledgements(runnerExecutionId, workflowRunId, opResult, ref, correlationId);
      }
      if (artifactType == ArtifactType.SPEC
          || artifactType == ArtifactType.IMPLEMENTATION_PLAN
          || artifactType == ArtifactType.PR_OUTPUT) {
        // Story 3b-3 review patch — availability marking is best-effort and runs inside
        // handleSuccess BEFORE recordCompleted (:1539); an uncaught RuntimeException (e.g. a
        // DomainException from markAvailable's checksum/state verification, or the digest-missing
        // IllegalStateException) would escape onResult — whose outer try has only a finally, no
        // catch (:1224) — and strand the execution RUNNING (re-harvested). Swallow ALL failures so
        // a
        // successfully-executed run is never unwound by an availability-marking error; the artifact
        // simply stays `pending` (not approval-eligible) until a re-harvest re-marks it. Mirrors
        // the
        // enrich twin's guard (:2028) which documents this exact failure mode.
        try {
          markArtifactAvailable(opResult, payload.bytes(), correlationId);
        } catch (RuntimeException error) {
          log.warn(
              "onResult artifact availability marking failed (best-effort) runnerExecutionId={} "
                  + "workflowRunId={} artifactId={} cause={}",
              runnerExecutionId,
              workflowRunId,
              opResult.artifact().publicId(),
              error.toString());
        }
      }

      // Story 3e-1 (AC4/AC5) — CREATE half of the clarification loop. At the spec (INVESTIGATION)
      // stage ONLY, turn the result's specArtifact.questions into `open` clarifications pinned to
      // the just-ingested spec artifact + version. Positioned AFTER the SPEC markArtifactAvailable
      // block so the artifact exists + is available, and delegated to a best-effort helper so a
      // creation failure NEVER unwinds the completed run — the run stays correctly advanced to
      // WaitingForSpecApproval with the spec available, and the clarifications simply re-create on
      // a
      // re-harvest (deterministic idempotency key). EXECUTION-stage results carry no spec
      // questions.
      if (artifactType == ArtifactType.SPEC && row.stage() == RunnerStage.INVESTIGATION) {
        ingestSpecClarifications(runnerExecutionId, workflowRunId, opResult, ref, correlationId);
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
    // (Trap T10), drive the run to FAILED, and return. The leak previously stopped at the
    // execution + event + quarantine on the theory that operator-driven recovery owned the
    // run-state path — but nothing ever moved the run, so it sat in EXECUTING forever: the timeout
    // scan skips a terminal execution row and RecommendationService offers no actions for a
    // non-FAILED run. Driving FAILED here is what opens the recovery path it already classifies as
    // a risky-retry category.
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
      driveWorkflowFailed(
          workflowRunId,
          runnerExecutionId,
          FailureCategory.RUNNER_SECRET_LEAK,
          "runner_secret_leak");
      log.warn(
          "onResult secret-leak runnerExecutionId={} workflowRunId={} leakedFile={} categories={}",
          runnerExecutionId,
          workflowRunId,
          secretScan.leakedFile(),
          secretScan.detectedCategories());
      return;
    }

    // Story 3d-7 (FR69, AC3) — capture the OPTIONAL post-execution provider usage/limit snapshot
    // emitted on normalizedOutput.providerUsage. Runs AFTER the secret scan (review 2026-06-24) so
    // a
    // leaked/quarantined execution never persists a snapshot row. Best-effort + tolerant of
    // absence:
    // a legacy/default result without the field is a byte-identical no-op (AC7), and a
    // snapshot-write
    // failure NEVER unwinds the successfully-ingested run (the method swallows all
    // RuntimeExceptions).
    captureProviderUsage(workflowRunId, runnerExecutionId, parsed);

    // Story 3g-3 (FR74, AC3/AC4) — capture the OPTIONAL per-execution token counts emitted on
    // normalizedOutput.usage onto the row (V31 columns). Same best-effort positioning as
    // captureProviderUsage: AFTER the secret scan, tolerant of absence (byte-identical no-op for a
    // legacy/no-usage result), and it NEVER unwinds a successfully-ingested run (swallows all
    // RuntimeExceptions).
    captureTokenUsage(workflowRunId, runnerExecutionId, parsed);

    // Story 3.12 (AC1 / Task 3) — derive the EXECUTION sub-stage ONCE (drives the build-gate
    // decision below + the deferred/inline delivery tail). Null for non-EXECUTION stages. The
    // sub-stage is run-level (driven by "an approved plan exists") and stable mid-execution.
    ExecutionSubStage executionSubStage =
        row.stage() == RunnerStage.EXECUTION
            ? contextBundleService.deriveExecutionSubStage(workflowRunId)
            : null;

    // Story 3h-1 (AC3) — when the build gate applies to a PR_OUTPUT success, defer the delivery
    // tail
    // (captureAndPush + WaitingForReview + reviewer enqueue) behind a backend-side BUILD run:
    // BuildStageService reserves a BUILD execution row + registers the afterCommit build hook, and
    // the tail runs (via the continuation below) only on BUILD success. When the gate does not
    // apply
    // (disabled / no command / no workspace / non-pr-output / no build service) the tail runs
    // INLINE
    // exactly where captureAndPush fired pre-3h (byte-identical). 3h-4 later moves this behind the
    // WaitingForDelivery gate.
    final String prOutputArtifactIdFinal = prOutputArtifactId;
    final String prOutputResolvedDiffFinal = prOutputResolvedDiff;
    final ExecutionSubStage executionSubStageFinal = executionSubStage;
    // The INLINE delivery step (captureAndPush + validate/enrich + recordCompleted + auto-advance),
    // captured as a lambda so the BUILD, LINT, and DELIVERY gates can chain in front of it. In
    // `auto` push mode this fires exactly where captureAndPush did pre-3h (byte-identical).
    Runnable deliverInline =
        () ->
            completeExecutionTailAndAdvance(
                runnerExecutionId,
                workflowRunId,
                row,
                parsed,
                correlationId,
                prOutputArtifactIdFinal,
                prOutputResolvedDiffFinal,
                executionSubStageFinal);
    // Story 3h-4 (AC2) — the DELIVERY gate sits BETWEEN the lint gate and the inline delivery: in
    // `auto` push mode (or no gate / no workspace) it is a pass-through that runs deliverInline; in
    // `manual`/`approve` mode it finalizes the producing PR_OUTPUT rex and PARKS the run at
    // WaitingForDelivery instead of pushing (approve_delivery later resumes/records). This is the
    // chain's new innermost step: build -> lint -> DELIVERY -> deliver.
    Runnable tail =
        () ->
            tryDeliveryGateOrDeliver(
                workflowRunId,
                runnerExecutionId,
                correlationId,
                row,
                executionSubStageFinal,
                deliverInline);
    // Story 3h-2 (AC3) — the LINT gate chains AFTER the BUILD gate onto the SAME tail: BUILD's
    // success continuation is not the tail itself but tryLintGateOrTail, which runs the
    // backend-side
    // lint (parking at WaitingForLintApproval on a critical finding) and only runs the tail when
    // lint
    // produces no critical finding (or the lint gate does not apply). Post-3h-4 the tail it runs is
    // the DELIVERY gate (which itself decides push-vs-park), not the raw inline delivery.
    Runnable afterBuild =
        () ->
            tryLintGateOrTail(
                workflowRunId, runnerExecutionId, correlationId, row, executionSubStageFinal, tail);

    org.dradgo.application.workflow.BuildStageService buildStageService =
        buildStageServiceSupplier.get();
    boolean gatedBehindBuild = false;
    if (buildStageService != null
        && row.stage() == RunnerStage.EXECUTION
        && executionSubStage == ExecutionSubStage.PR_OUTPUT) {
      gatedBehindBuild =
          buildStageService.tryGateBehindBuild(
              workflowRunId, runnerExecutionId, correlationId, afterBuild);
    }
    if (!gatedBehindBuild) {
      afterBuild.run();
    }
  }

  /**
   * Story 3h-2 (AC3/AC4) — the LINT gate seam: for a PR_OUTPUT success (post-BUILD), reserve a LINT
   * execution + defer the backend-side lint run behind the tail. When the lint gate applies it
   * takes ownership of the tail (running it only on no-critical findings, else parking at
   * WaitingForLintApproval); when it does not apply (disabled / no commands / no workspace /
   * non-pr-output / no lint service) the tail runs directly (byte-identical to pre-3h-2).
   */
  private void tryLintGateOrTail(
      String workflowRunId,
      String runnerExecutionId,
      String correlationId,
      RunnerExecutionSnapshot row,
      ExecutionSubStage executionSubStage,
      Runnable tail) {
    org.dradgo.application.workflow.LintStageService lintStageService =
        lintStageServiceSupplier.get();
    boolean gatedBehindLint = false;
    if (lintStageService != null
        && row.stage() == RunnerStage.EXECUTION
        && executionSubStage == ExecutionSubStage.PR_OUTPUT) {
      gatedBehindLint =
          lintStageService.tryGateBehindLint(workflowRunId, runnerExecutionId, correlationId, tail);
    }
    if (!gatedBehindLint) {
      tail.run();
    }
  }

  /**
   * Story 3h-4 (AC2/AC4) — the DELIVERY gate seam: for a PR_OUTPUT success (post-BUILD, post-LINT),
   * resolve the run's push mode. In {@code auto} mode (or when no delivery-gate bean is wired, or
   * there is no workspace/repoRef to deliver) it is a pass-through — the inline delivery runs
   * exactly where {@code captureAndPush} fired pre-3h. In {@code manual}/{@code approve} mode the
   * gate finalizes the producing execution and PARKS the run at {@code WaitingForDelivery} (taking
   * ownership of the tail — {@code approve_delivery} later performs the push or records the manual
   * delivery). The gate applies only to a PR_OUTPUT EXECUTION success, mirroring the build/lint
   * gates.
   */
  private void tryDeliveryGateOrDeliver(
      String workflowRunId,
      String runnerExecutionId,
      String correlationId,
      RunnerExecutionSnapshot row,
      ExecutionSubStage executionSubStage,
      Runnable deliverInline) {
    org.dradgo.application.workflow.DeliveryGateService deliveryGateService =
        deliveryGateServiceSupplier.get();
    boolean gatedBehindDelivery = false;
    if (deliveryGateService != null
        && row.stage() == RunnerStage.EXECUTION
        && executionSubStage == ExecutionSubStage.PR_OUTPUT) {
      gatedBehindDelivery =
          deliveryGateService.tryGateBehindDelivery(
              workflowRunId, runnerExecutionId, correlationId, deliverInline);
    }
    if (!gatedBehindDelivery) {
      deliverInline.run();
    }
  }

  /**
   * Story 3h-2 (AC5; code-review 2026-07-06, Decision 4 P1) — resume the delivery tail after an
   * operator {@code approve_lint} dismissed the lint gate. Re-derivable from {@code
   * (workflowRunId)} alone (the in-memory tail continuation is long gone by approval time): resolve
   * the producing PR_OUTPUT execution, push its workspace, ENRICH the ingested pr-output artifact
   * with the authoritative push refs + diff via the SAME {@link #enrichPrOutputArtifact} + {@link
   * #linkGitHubPrBestEffort} helpers the pass path uses (one seam, two callers) — so the advisory
   * reviewer reads an enriched/reviewable artifact rather than the diff-less v1
   * ([[proutput-advisory-review-missing-diff]]) — finalize the producing execution, and enqueue the
   * reviewer. The {@code WaitingForLintApproval -> WaitingForReview} transition is owned by {@code
   * LintApprovalService.approveLint} (synchronous, in the command tx); by the time this runs the
   * run is already {@code WaitingForReview}. Idempotent — {@code captureAndPush} self-gates on
   * "workspace exists + uncommitted changes", the enrich UPDATE is keyed per rex, the finalize
   * tolerates an already-terminal row, and the reviewer enqueue is idempotency-keyed — so a
   * replayed approval neither double-pushes nor double-enqueues (AC8). Called post-commit by {@code
   * LintApprovalService}.
   */
  public void resumeDeliveryTailFromGate(String workflowRunId, String correlationId) {
    resumeDeliveryTailFromGate(workflowRunId, correlationId, false);
  }

  public void resumeDeliveryTailFromGateOrThrow(String workflowRunId, String correlationId) {
    resumeDeliveryTailFromGate(workflowRunId, correlationId, true);
  }

  private void resumeDeliveryTailFromGate(
      String workflowRunId, String correlationId, boolean failOnPushFailure) {
    Optional<RunnerExecutionSnapshot> prOutput =
        recordPort.findLatestByWorkflowRunPublicIdAndStage(workflowRunId, RunnerStage.EXECUTION);
    if (prOutput.isEmpty()) {
      log.warn(
          "resumeDeliveryTailFromGate found no producing EXECUTION execution workflowRunId={} — "
              + "cannot resume",
          workflowRunId);
      return;
    }
    String prOutputRunnerExecutionId = prOutput.get().publicId();

    // captureAndPush — best-effort. The run has already left the lint gate (approve_lint
    // transitioned
    // it to WaitingForReview in-tx) and WaitingForReview has no ->Failed edge, so a push rejection
    // is
    // logged (operator/reconciliation owns an unpushed branch) rather than driving the run Failed.
    Optional<RepositoryWorkspaceService.RepositoryPushOutcome> pushOutcome = Optional.empty();
    if (repositoryWorkspaceService != null) {
      try {
        pushOutcome = repositoryWorkspaceService.captureAndPush(prOutputRunnerExecutionId);
      } catch (GitCommandException pushFailure) {
        if (failOnPushFailure) {
          throw pushFailure;
        }
        log.warn(
            "resumeDeliveryTailFromGate git push rejected workflowRunId={} runnerExecutionId={} "
                + "category={} (proceeding to review)",
            workflowRunId,
            prOutputRunnerExecutionId,
            pushFailure.failureCategory().value());
      }
    }

    // Decision 4 (P1) — enrich the ingested pr-output artifact with the AUTHORITATIVE
    // captureAndPush
    // refs + diff, exactly as the pass path does (validateAndEnrichPrOutput ->
    // enrichPrOutputArtifact
    // + linkGitHubPrBestEffort). The in-memory runner result is gone by approve time, so the
    // enrichment base (prRef) + artifact id are re-derived from the persisted latest pr-output
    // artifact. Only runs when captureAndPush produced authoritative refs (a changes-bearing push);
    // a clean/no-op push leaves the artifact as-is (no Fail drive — the run already left the gate).
    if (pushOutcome.isPresent()) {
      ResolvedPrOutputArtifact resolved = resolvePrOutputArtifactForResume(workflowRunId);
      if (resolved != null) {
        RepositoryWorkspaceService.RepositoryPushOutcome actual = pushOutcome.get();
        String effectiveDiff =
            actual.diff() != null && !actual.diff().isBlank()
                ? boundPrOutputDiff(
                    actual.diff(), prOutputRunnerExecutionId, workflowRunId, resolved.artifactId())
                : null;
        enrichPrOutputArtifact(
            prOutputRunnerExecutionId,
            workflowRunId,
            resolved.artifactId(),
            resolved.prRef(),
            actual,
            effectiveDiff,
            correlationId);
        linkGitHubPrBestEffort(prOutputRunnerExecutionId, workflowRunId, actual, correlationId);
      } else {
        log.warn(
            "resumeDeliveryTailFromGate could not resolve the pr-output artifact to enrich "
                + "workflowRunId={} — advancing to review without enrichment",
            workflowRunId);
      }
    }

    // Finalize the producing PR_OUTPUT execution (it was finalized at park; a terminal row is a
    // no-op). Idempotent.
    try {
      executionService.recordCompleted(prOutputRunnerExecutionId);
    } catch (DomainException alreadyTerminal) {
      if (alreadyTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw alreadyTerminal;
      }
      log.info(
          "resumeDeliveryTailFromGate producing execution already terminal runnerExecutionId={}",
          prOutputRunnerExecutionId);
    }

    // Enqueue the reviewer over the now-enriched artifact. The run is ALREADY WaitingForReview
    // (approve_lint transitioned it in-tx), so this only enqueues — no transition here.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        workflowOrchestrationServiceSupplier.get();
    if (orchestration != null) {
      orchestration.enqueueReviewerAfterLintApproval(
          workflowRunId, prOutputRunnerExecutionId, correlationId);
    } else {
      log.warn(
          "resumeDeliveryTailFromGate cannot enqueue reviewer (no orchestration bean) "
              + "workflowRunId={}",
          workflowRunId);
    }
  }

  /**
   * Story 3h-4 (AC4, Decision 4) — the MANUAL-mode twin of {@link #resumeDeliveryTailFromGate}:
   * advance a run the operator delivered OUT-OF-BAND (approve_delivery under {@code manual} push
   * mode) WITHOUT touching git. This is the {@code ingestManualResult}
   * "advance-without-side-effect" pattern: the backend pushed NOTHING (the operator pushed by
   * hand), so there is no captureAndPush / enrich / PR-create — only the reviewer enqueue over the
   * already-ingested pr-output artifact. The {@code WaitingForDelivery -> WaitingForReview}
   * transition + the {@code delivery.recordedManually} audit event are owned by {@code
   * DeliveryApprovalService.approveDelivery} (synchronous, in the command tx); by the time this
   * runs post-commit the run is already {@code WaitingForReview}. Idempotent — the finalize
   * tolerates an already-terminal producing row and the reviewer enqueue is idempotency-keyed, so a
   * replayed approve neither re-finalizes nor double-enqueues. Called post-commit by {@code
   * DeliveryApprovalService}.
   *
   * <p>The advisory reviewer degrades best-effort without a backend push (the documented
   * manual-push limitation, consistent with 3h-5 AC4: "manual push = the backend pushed nothing, so
   * there is nothing of ours to read").
   */
  public void recordManualDeliveryAndEnqueueReviewer(String workflowRunId, String correlationId) {
    Optional<RunnerExecutionSnapshot> prOutput =
        recordPort.findLatestByWorkflowRunPublicIdAndStage(workflowRunId, RunnerStage.EXECUTION);
    if (prOutput.isEmpty()) {
      log.warn(
          "recordManualDeliveryAndEnqueueReviewer found no producing EXECUTION execution "
              + "workflowRunId={} — cannot enqueue reviewer",
          workflowRunId);
      return;
    }
    String prOutputRunnerExecutionId = prOutput.get().publicId();

    // Finalize the producing PR_OUTPUT execution (it was finalized at gate park; a terminal row is
    // a
    // no-op). Idempotent. NEVER captureAndPush / createPullRequest — the delivery was out-of-band.
    try {
      executionService.recordCompleted(prOutputRunnerExecutionId);
    } catch (DomainException alreadyTerminal) {
      if (alreadyTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw alreadyTerminal;
      }
      log.info(
          "recordManualDeliveryAndEnqueueReviewer producing execution already terminal "
              + "runnerExecutionId={}",
          prOutputRunnerExecutionId);
    }

    log.info(
        "recordManualDeliveryAndEnqueueReviewer workflowRunId={} runnerExecutionId={} "
            + "(manual push — no git, enqueue reviewer only)",
        workflowRunId,
        prOutputRunnerExecutionId);
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        workflowOrchestrationServiceSupplier.get();
    if (orchestration != null) {
      orchestration.enqueueReviewerAfterLintApproval(
          workflowRunId, prOutputRunnerExecutionId, correlationId);
    } else {
      log.warn(
          "recordManualDeliveryAndEnqueueReviewer cannot enqueue reviewer (no orchestration bean) "
              + "workflowRunId={}",
          workflowRunId);
    }
  }

  /**
   * Story 3h-2 (code-review 2026-07-06) — re-derive the pr-output artifact id + enrichment base
   * (prRef) for the {@code approve_lint} resume from persisted state (the in-memory runner result
   * is gone). Returns {@code null} when no pr-output artifact exists; falls back to an empty prRef
   * object when the body is missing/unparseable (enrichment still writes the authoritative refs +
   * diff — only the runner-authored fields like a PR title are then absent).
   */
  private ResolvedPrOutputArtifact resolvePrOutputArtifactForResume(String workflowRunId) {
    Optional<org.dradgo.application.artifact.ArtifactRecordSnapshot> latest =
        artifactOperationService.findLatestArtifact(workflowRunId, ArtifactType.PR_OUTPUT);
    if (latest.isEmpty()) {
      return null;
    }
    String artifactId = latest.get().publicId();
    JsonNode prRef = objectMapper.createObjectNode();
    Optional<byte[]> body = artifactOperationService.readArtifactBytes(latest.get().storageRef());
    if (body.isPresent() && body.get().length > 0) {
      try {
        JsonNode parsed = objectMapper.readTree(body.get());
        if (parsed.isObject()) {
          prRef = parsed;
        }
      } catch (java.io.IOException parseFailure) {
        log.warn(
            "resumeDeliveryTailFromGate pr-output artifact body unparseable workflowRunId={} "
                + "artifactId={} — enriching a minimal ref",
            workflowRunId,
            artifactId);
      }
    }
    return new ResolvedPrOutputArtifact(artifactId, prRef);
  }

  private record ResolvedPrOutputArtifact(String artifactId, JsonNode prRef) {}

  /**
   * Story 3h-1 (Task 6, AC3) — the delivery tail extracted so a build gate can precede it:
   * captureAndPush (with git-push-failure handling) + pr-output validate/enrich + recordCompleted +
   * RUNNER_COMPLETED + the stage auto-advance (onSpecStageSucceeded / onPlanStageSucceeded /
   * onPrOutputStageSucceeded). Runs INLINE where captureAndPush fired pre-3h (byte-identical) when
   * the build gate does not apply, or as the deferred continuation invoked by {@code
   * BuildStageService} on BUILD success when it does. Package-private so BuildStageService's
   * continuation (a broker-side lambda) can reach it; 3h-4 later moves this seam behind the
   * WaitingForDelivery gate — keep it a single clean call site.
   */
  void completeExecutionTailAndAdvance(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      JsonNode parsed,
      String correlationId,
      String prOutputArtifactId,
      String prOutputResolvedDiff,
      ExecutionSubStage executionSubStage) {
    JsonNode artifactRefs = parsed.path("artifactReferences");
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
            prOutputResolvedDiff,
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
   * Story 3e-1 (AC4/AC5) — best-effort creation of {@code open} clarifications from a spec result's
   * {@code specArtifact.questions}. Delegates the whole batch to {@link ClarificationIngestService}
   * (resolved lazily so the broker carries no eager clarification-write dependency). Reads the
   * questions off the SAME {@code ref} JsonNode the artifact was ingested from; an absent/empty
   * array is a no-op. Swallows ALL {@link RuntimeException}s (including a missing-bean / no-ambient
   * transaction case and any per-batch failure) so a creation problem NEVER unwinds the completed
   * run — the run stays advanced to {@code WaitingForSpecApproval} with the spec available and the
   * clarifications simply re-create on a re-harvest (deterministic idempotency key). Mirrors the
   * SPEC {@code markArtifactAvailable} best-effort guard rationale.
   */
  private void ingestSpecClarifications(
      String runnerExecutionId,
      String workflowRunId,
      RecordArtifactOperationResult opResult,
      JsonNode ref,
      String correlationId) {
    JsonNode questionsNode = ref.path("questions");
    if (!questionsNode.isArray() || questionsNode.isEmpty()) {
      return;
    }
    org.dradgo.application.clarification.ClarificationIngestService ingestService =
        clarificationIngestServiceSupplier.get();
    if (ingestService == null) {
      log.warn(
          "spec clarifications present but ClarificationIngestService is not wired — skipping "
              + "(no-op) runnerExecutionId={} workflowRunId={} questionCount={}",
          runnerExecutionId,
          workflowRunId,
          questionsNode.size());
      return;
    }
    try {
      java.util.List<org.dradgo.application.clarification.ClarificationIngestService.RaisedQuestion>
          questions = new java.util.ArrayList<>();
      for (JsonNode question : questionsNode) {
        String questionId = question.path("questionId").asText(null);
        String questionText = question.path("questionText").asText(null);
        if (questionId != null && questionText != null) {
          questions.add(
              new org.dradgo.application.clarification.ClarificationIngestService.RaisedQuestion(
                  questionId, questionText));
        }
      }
      if (questions.isEmpty()) {
        return;
      }
      ingestService.createOpenFromSpec(
          workflowRunId,
          opResult.artifact().publicId(),
          opResult.artifact().version(),
          questions,
          runnerExecutionId,
          correlationId);
    } catch (RuntimeException error) {
      log.warn(
          "spec clarification ingest failed (best-effort — run stays advanced, re-creates on "
              + "re-harvest) runnerExecutionId={} workflowRunId={} artifactId={} cause={}",
          runnerExecutionId,
          workflowRunId,
          opResult.artifact().publicId(),
          error.toString());
    }
  }

  /**
   * Story 3e-2 (AC6/R5) — persist a rebuilt spec's structured {@code clarificationAcknowledgements}
   * into the V25 side-store so the synchronous clarification sweep (inside {@code markAvailable},
   * run AFTER this) can read them by spec artifactId. Best-effort sibling of {@link
   * #ingestSpecClarifications}: a persist failure NEVER unwinds the completed run (the
   * acknowledgements re-persist on re-harvest via the deterministic {@code (artifactId,
   * questionId)} uniqueness + pre-flight probe). Delegated to {@code ClarificationIngestService}
   * (the same lazy supplier as the questions path) so the broker grows no new clarification-write
   * dependency.
   */
  private void ingestSpecAcknowledgements(
      String runnerExecutionId,
      String workflowRunId,
      RecordArtifactOperationResult opResult,
      JsonNode ref,
      String correlationId) {
    JsonNode acknowledgementsNode = ref.path("clarificationAcknowledgements");
    if (!acknowledgementsNode.isArray() || acknowledgementsNode.isEmpty()) {
      return;
    }
    org.dradgo.application.clarification.ClarificationIngestService ingestService =
        clarificationIngestServiceSupplier.get();
    if (ingestService == null) {
      log.warn(
          "spec acknowledgements present but ClarificationIngestService is not wired — skipping "
              + "(no-op) runnerExecutionId={} workflowRunId={} acknowledgementCount={}",
          runnerExecutionId,
          workflowRunId,
          acknowledgementsNode.size());
      return;
    }
    try {
      java.util.List<
              org.dradgo.application.clarification.ClarificationIngestService.RaisedAcknowledgement>
          acknowledgements = new java.util.ArrayList<>();
      for (JsonNode acknowledgement : acknowledgementsNode) {
        String questionId = acknowledgement.path("questionId").asText(null);
        JsonNode addressedNode = acknowledgement.path("addressed");
        if (questionId != null && addressedNode.isBoolean()) {
          acknowledgements.add(
              new org.dradgo.application.clarification.ClarificationIngestService
                  .RaisedAcknowledgement(questionId, addressedNode.asBoolean()));
        }
      }
      if (acknowledgements.isEmpty()) {
        return;
      }
      ingestService.persistSpecClarificationAcknowledgements(
          opResult.artifact().publicId(), acknowledgements, correlationId);
    } catch (RuntimeException error) {
      log.warn(
          "spec acknowledgement ingest failed (best-effort — run stays advanced, re-persists on "
              + "re-harvest) runnerExecutionId={} workflowRunId={} artifactId={} cause={}",
          runnerExecutionId,
          workflowRunId,
          opResult.artifact().publicId(),
          error.toString());
    }
  }

  /**
   * Story 3d-4 (AC2/AC4 / R2) — ingest an operator-submitted manual artifact for a parked {@code
   * WaitingForManualExecution} run. This is the manual twin of {@link #handleSuccess}'s success
   * tail: it reuses the SAME artifact-ingest + {@code markAvailable} + {@code recordCompleted} +
   * orchestration auto-advance collaborators, but deliberately SKIPS the workspace-coupled steps —
   * the story-3.5 post-execution {@code runnerSecretScanService.scanWorkspace} and the EXECUTION
   * pr-output {@code repositoryWorkspaceService.captureAndPush}/enrich — because a manual park
   * launches no container and materializes no workspace (R2). The caller ({@code
   * ManualArtifactSubmissionService}) has already run the SAME {@code ContractValidator} the broker
   * runs in {@link #onResult} plus the stage→artifact-type rule, so by the time we reach here the
   * payload is schema-valid and type-correct; validation is the caller's gate (AC5), so this method
   * never short-circuits the run to Failed.
   *
   * <p>Runs INLINE in the caller's request transaction (R9): {@code recordCompleted} finalizes the
   * parked {@code awaiting_manual} row to {@code COMPLETED} (legal since the R1 state-machine
   * widening) and the orchestration callbacks transition the run OUT of {@code
   * WaitingForManualExecution} into the stage-appropriate post-step state ({@code
   * WaitingForSpecApproval} / {@code WaitingForReview}) over the OUT edges 3d-3 declared. Any
   * mid-sequence failure rolls the whole submission back (the run stays parked, AC4/AC5).
   *
   * @param operatorActor the resolved OPERATOR identity (HUMAN), stamped onto the artifact
   *     provenance and the {@code manual.artifactSubmitted} event — never {@code SYSTEM}.
   */
  public void ingestManualResult(
      String runnerExecutionId, byte[] payloadBytes, ActorContext operatorActor) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Objects.requireNonNull(payloadBytes, "payloadBytes");
    Objects.requireNonNull(operatorActor, "operatorActor");
    RunnerExecutionSnapshot row =
        recordPort
            .findByPublicId(runnerExecutionId)
            .orElseThrow(() -> runnerExecutionNotFound(runnerExecutionId));
    String workflowRunId = row.workflowRunPublicId();
    JsonNode parsed;
    try {
      parsed = objectMapper.readTree(payloadBytes);
    } catch (java.io.IOException error) {
      // The caller validated the payload with the ContractValidator before reaching here, so a
      // parse failure is a programming error, not operator input.
      throw new IllegalStateException(
          "manual artifact payload was not valid JSON after contract validation", error);
    }
    String correlationId =
        operatorActor.correlationId() != null && !operatorActor.correlationId().isBlank()
            ? operatorActor.correlationId()
            : resolveOutcomeCorrelationId(runnerExecutionId);

    JsonNode artifactRefs = parsed.path("artifactReferences");
    for (JsonNode ref : artifactRefs) {
      ArtifactType artifactType =
          ArtifactType.fromValue(
              ref.path("artifactType").asText(), "runner_result.artifactReferences.artifactType");
      // Story 3d-4 (R8) — for a manual prOutput we do NOT re-run captureAndPush (no workspace); we
      // resolve any operator-provided diff bytes the submission wrote to scratch, else omit the
      // diff
      // gracefully (the read path degrades to an empty-diff panel, exactly as for an automated
      // mock).
      String resolvedDiff = null;
      if (artifactType == ArtifactType.PR_OUTPUT) {
        resolvedDiff =
            resolvePrOutputDiff(
                    runnerExecutionId, workflowRunId, ref.path("artifactId").asText(null), ref)
                .orElse(null);
      }
      Optional<ArtifactPayload> maybePayload =
          artifactPayload(runnerExecutionId, ref, resolvedDiff);
      if (maybePayload.isEmpty()) {
        // A spec ref's contentReference did not resolve in scratch (the submission must write the
        // artifact content before ingest). Roll back: the run stays parked + resubmittable (AC5).
        throw manualIngestFailure(
            workflowRunId, runnerExecutionId, "manual artifact content unreadable");
      }
      ArtifactPayload payload = maybePayload.get();
      String idempotencyKey =
          "manual-result:" + runnerExecutionId + ":" + ref.path("artifactId").asText();
      RecordArtifactOperationCommand command =
          new RecordArtifactOperationCommand(
              workflowRunId,
              artifactType,
              ArtifactOperationType.CREATE,
              idempotencyKey,
              payload.payloadRef(),
              payload.bytes(),
              operatorActor.actorIdentity(),
              operatorActor.actorType(),
              correlationId,
              runnerExecutionId);
      RecordArtifactOperationResult opResult = artifactOperationService.recordOperation(command);
      if (opResult.isFailure()) {
        log.warn(
            "manual artifact-record failed workflowRunId={} runnerExecutionId={} artifactType={} reason={}",
            workflowRunId,
            runnerExecutionId,
            artifactType.value(),
            opResult.failure());
        throw manualIngestFailure(
            workflowRunId, runnerExecutionId, "manual artifact ingest failed");
      }
      // Availability wiring — promote the freshly-ingested artifact to `available` so the
      // downstream
      // approval-eligibility gate accepts the manual artifact identically to an automated one (R7).
      if (artifactType == ArtifactType.SPEC
          || artifactType == ArtifactType.IMPLEMENTATION_PLAN
          || artifactType == ArtifactType.PR_OUTPUT) {
        // Story 3d-4 re-review (2026-06-23) — unlike the automated handleSuccess swallow
        // (RunnerBroker.java:~1639), the manual path has NO re-harvest to later re-mark a
        // stuck-`pending` artifact. Let an availability-marking failure PROPAGATE:
        // ingestManualResult
        // runs inside the submission @Transactional, so the throw rolls the whole tx back to parked
        // +
        // resubmittable (AC5) rather than committing a run that advanced with an unavailable
        // artifact
        // the downstream approval-eligibility gate would silently reject.
        markArtifactAvailable(opResult, payload.bytes(), correlationId);
      }
    }

    // Finalize the parked awaiting_manual row to COMPLETED (legal since R1). NOT guarded for
    // ILLEGAL_TRANSITION — the caller's applicability gate + idempotency guarantee a single parked
    // row, and a stray double should surface, not silently no-op.
    executionService.recordCompleted(runnerExecutionId);
    appendManualArtifactSubmittedEvent(workflowRunId, runnerExecutionId, operatorActor);

    ExecutionSubStage executionSubStage =
        row.stage() == RunnerStage.EXECUTION
            ? contextBundleService.deriveExecutionSubStage(workflowRunId)
            : null;
    log.info(
        "manual artifact accepted workflowRunId={} runnerExecutionId={} stage={} subStage={}",
        workflowRunId,
        runnerExecutionId,
        row.stage().value(),
        executionSubStage);

    // Auto-advance OUT of WaitingForManualExecution via the SAME orchestration callbacks the
    // automated success tail uses (the OUT edges are legal from WaitingForManualExecution — 3d-3).
    if (row.stage() == RunnerStage.INVESTIGATION) {
      org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
          workflowOrchestrationServiceSupplier.get();
      if (orchestration != null) {
        orchestration.onSpecStageSucceeded(workflowRunId, runnerExecutionId, correlationId);
      }
    } else if (row.stage() == RunnerStage.EXECUTION) {
      org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
          workflowOrchestrationServiceSupplier.get();
      if (executionSubStage == ExecutionSubStage.IMPLEMENTATION_PLAN) {
        if (orchestration != null) {
          orchestration.onPlanStageSucceeded(workflowRunId, runnerExecutionId, correlationId);
        }
      } else if (executionSubStage == ExecutionSubStage.PR_OUTPUT) {
        if (orchestration != null) {
          orchestration.onPrOutputStageSucceeded(workflowRunId, runnerExecutionId, correlationId);
        }
      } else {
        log.info(
            "manual artifact success not auto-advanced workflowRunId={} runnerExecutionId={} "
                + "subStage={} reason=unknown_substage",
            workflowRunId,
            runnerExecutionId,
            executionSubStage);
      }
    }
  }

  private DomainException manualIngestFailure(
      String workflowRunId, String runnerExecutionId, String message) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(org.dradgo.domain.registry.WorkflowEventDetailKeys.WORKFLOW_RUN_ID, workflowRunId);
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("reason", "manual_artifact_ingest_failed");
    return new DomainException(DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED, message, details);
  }

  private void appendManualArtifactSubmittedEvent(
      String workflowRunId, String runnerExecutionId, ActorContext operatorActor) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(org.dradgo.domain.registry.WorkflowEventDetailKeys.WORKFLOW_RUN_ID, workflowRunId);
    details.put("runnerExecutionId", runnerExecutionId);
    eventPort.append(
        workflowRunId,
        WorkflowEventType.MANUAL_ARTIFACT_SUBMITTED,
        operatorActor,
        "manual_artifact_submitted",
        null,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
    log.info(
        "manual.artifactSubmitted appended workflowRunId={} runnerExecutionId={} actorIdentity={}",
        workflowRunId,
        runnerExecutionId,
        operatorActor.actorIdentity());
  }

  /**
   * Story 3a-1 (AC8) — the artifact types a dispatching stage is permitted to emit. INVESTIGATION
   * (spec stage) may only produce a {@code spec}; EXECUTION may produce an {@code
   * implementationPlan} or {@code prOutput}. (OQ-5: the mapping lives in the broker, where the
   * result is parsed.)
   *
   * <p>Public (story 3d-4 review) so {@code ManualArtifactSubmissionService} — relocated to {@code
   * application.workflow} as a REST/CLI-facing command service (the thin-controller boundary
   * forbids REST adapters from reaching into {@code application.runner}) — validates an
   * operator-submitted artifact's type against the SAME stage rule the automated path uses.
   */
  public static java.util.Set<ArtifactType> allowedArtifactTypesForStage(RunnerStage stage) {
    return switch (stage) {
      case INVESTIGATION -> java.util.EnumSet.of(ArtifactType.SPEC);
      case EXECUTION ->
          java.util.EnumSet.of(ArtifactType.IMPLEMENTATION_PLAN, ArtifactType.PR_OUTPUT);
      // Story 3d-2 (DD-2) — the reviewer emits NO artifacts-table artifact: its verdict is
      // harvested
      // directly into step_reviews (advisory metadata ABOUT an existing artifact, not a new
      // reviewable artifact). The REVIEW harvest branches before artifactOperationService, so this
      // empty set is a belt-and-braces guard — any artifactReference on a review result is
      // rejected.
      case REVIEW -> java.util.EnumSet.noneOf(ArtifactType.class);
      // Story 3h-1 (AC1) — BUILD is command-only and runs backend-side; it emits NO artifacts-table
      // artifact (same as REVIEW). BUILD never flows through onResult/handleSuccess in the
      // backend-side model, so this empty set is a belt-and-braces guard.
      case BUILD -> java.util.EnumSet.noneOf(ArtifactType.class);
      // Story 3h-2 (AC1) — LINT is command-only and runs backend-side; it emits NO artifacts-table
      // artifact (same as BUILD/REVIEW). LINT never flows through onResult/handleSuccess in the
      // backend-side model, so this empty set is a belt-and-braces guard.
      case LINT -> java.util.EnumSet.noneOf(ArtifactType.class);
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
   * Story 3.12 (AC3/AC9, Task 3) — for the pr-output sub-stage: enrich the ingested prOutput
   * artifact with the AUTHORITATIVE refs (Decision D3 — follow-on UPDATE keyed by artifact id).
   *
   * <p>The BACKEND owns git, not the runner: {@code captureAndPush} prepares the workspace + branch
   * before the container, then commits/pushes/opens the PR after it exits. The runner only edits
   * files in the mount and reports PLACEHOLDER git refs it cannot know (a {@code codex/<rex>}
   * branch it never created, a sha256-of-output "commit", and an {@code artifacts/<run>/pr.json}
   * path for prReference). So when a push outcome is present it is authoritative — we enrich + link
   * from it (the only consumers of these refs) and do NOT gate on the runner's self-report, which
   * is guaranteed to differ in branch/commit/prRef and need not match the documented prReference
   * format. The AC9 format check (untrusted runner output) is retained ONLY for the no-push path
   * (e.g. the offline mock runner with no repo), where the runner's self-report is all we have.
   *
   * <p>Returns {@code true} to continue the success path; {@code false} when it has already driven
   * the run to {@code Failed} via the existing contract-violation path (the caller then returns
   * without completing the execution).
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
      String resolvedDiff,
      String correlationId) {
    JsonNode prRef = prOutputArtifactReference(parsed);

    if (pushOutcome.isPresent()) {
      // captureAndPush is AUTHORITATIVE (the backend owns git) — trust it and ignore the runner's
      // placeholder self-reported refs. Decision D3 — enrich the ingested prOutput artifact with
      // the
      // ACTUAL refs (follow-on UPDATE).
      RepositoryWorkspaceService.RepositoryPushOutcome actual = pushOutcome.get();
      // Story 3g follow-up (proutput-advisory-review-missing-diff) — prefer the AUTHORITATIVE diff
      // captured by captureAndPush (the backend owns git) over the runner's scratch diffReference
      // (which today's runners never write). Embedding it lets the OFFLINE pr-output advisory
      // reviewer inspect the changes instead of failing "not reviewable". Bounded to the same caps
      // as
      // the scratch path; falls back to the scratch-resolved diff when captureAndPush captured
      // none.
      String effectiveDiff =
          actual.diff() != null && !actual.diff().isBlank()
              ? boundPrOutputDiff(
                  actual.diff(), runnerExecutionId, workflowRunId, prOutputArtifactId)
              : resolvedDiff;
      enrichPrOutputArtifact(
          runnerExecutionId,
          workflowRunId,
          prOutputArtifactId,
          prRef,
          actual,
          effectiveDiff,
          correlationId);
      // Story 3.15 (Task 7) — promote the dormant linkage seam: write the durable github_pr
      // integration_links row + integration.linked event from the AUTHORITATIVE captureAndPush refs
      // (never the untrusted runner-reported string — Trap T1). Best-effort: a linkage-only failure
      // must not unwind the committed runner outcome or block WaitingForReview.
      linkGitHubPrBestEffort(runnerExecutionId, workflowRunId, actual, correlationId);
    } else if (repositoryWorkspaceService != null) {
      // Repo-backed run, but captureAndPush returned EMPTY — the backend owns git and found a CLEAN
      // worktree, i.e. the runner produced NO committable changes. There is therefore no real
      // branch/commit/PR; the runner only self-reported PLACEHOLDER refs (a {@code codex/<rex>}
      // branch it never created, a sha256-of-output "commit", an {@code artifacts/<run>/pr.json}
      // prReference). Format-validating those placeholders here yields a MISLEADING "malformed
      // runner-reported pr-output ref: prReference" that points at the wrong thing — the real cause
      // is "the agent changed nothing" (typically a mock/no-op runner image, or a task that
      // genuinely produced no edits). Fail with that actual cause so the operator looks at the
      // runner, not a phantom ref-format bug. Still a contract violation (a pr-output execution
      // must
      // produce a PR); reuses RUNNER_OUTPUT_VALIDATION_FAILED — no new error code.
      //
      // NOTE (code-review 2026-06-19, accept-as-benign): RepositoryWorkspaceService.captureAndPush
      // returns a bare Optional.empty() for TWO reasons — reason=clean_worktree (the case above)
      // AND
      // reason=no_repo_workspace (resolveRepositoryDir empty — only on abnormal workspace loss,
      // e.g.
      // the run's temp workspace gone across a restart). For a repo-backed pr-output run the
      // workspace is always present by the time captureAndPush runs, so in normal operation an
      // empty
      // outcome ⇒ clean worktree. If the no-workspace edge ever fires, captureAndPush has already
      // logged "captureAndPush no-op reason=no_repo_workspace" at WARN
      // (RepositoryWorkspaceService),
      // which is the diagnostic that distinguishes the two empties during incident triage.
      log.warn(
          "onResult pr-output no changes produced runnerExecutionId={} workflowRunId={} "
              + "reason=clean_worktree errorCode={}",
          runnerExecutionId,
          workflowRunId,
          DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED.value());
      handlePrOutputContractFailure(
          runnerExecutionId,
          workflowRunId,
          row,
          DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED,
          "runner_output_no_changes",
          "runner produced no repository changes (clean worktree); no pull request to create");
      return false;
    } else {
      // No repo workspace at all (the offline mock runner / no-repo unit context): the runner's
      // self-reported refs are genuinely all we have, so AC9 still validates their documented
      // format
      // before ingest.
      String reportedBranch = textOrNull(prRef, "branch");
      String reportedCommitSha = textOrNull(prRef, "commitSha");
      String reportedPrReference = textOrNull(prRef, "prReference");
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
      linkService.linkPullRequest(
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

  /**
   * Promote a freshly-ingested runner-produced artifact (spec / implementation plan / pr-output) to
   * {@code available} so the approval-eligibility gate ({@code ArtifactService.isApprovalEligible},
   * which requires {@code status=AVAILABLE} plus a populated checksum + storageRef) accepts it. The
   * checksum is computed over the SAME payload bytes the broker handed to {@code recordOperation},
   * and the storageRef is the canonical location the payload store reported writing them to — so
   * {@code markAvailable}'s payload-vs-checksum verification is guaranteed to match. Runs inside
   * the poller's per-item transaction alongside the ingest, so the artifact is available before the
   * stage-ready transition is observable.
   *
   * <p>{@code storageRef} is {@code null} on an idempotent replay (a duplicate {@code onResult}
   * that did not re-write the payload); in that case the artifact was already made available by the
   * original ingest and there is nothing to re-stamp ({@code markAvailable} is itself idempotent).
   *
   * <p>Story 3b-3 generalized this from the spec-only path to all three runner artifact types; the
   * enriched PR_OUTPUT head (a new PENDING version produced by {@code enrichPrOutputArtifact}) is
   * marked available by a second call from inside that method.
   */
  private void markArtifactAvailable(
      RecordArtifactOperationResult opResult, byte[] payloadBytes, String correlationId) {
    String storageRef = opResult.storageRef();
    if (storageRef == null) {
      return;
    }
    String checksumHex =
        ArtifactChecksum.digestHex(ARTIFACT_CHECKSUM_ALGORITHM, payloadBytes)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "JVM does not provide required digest algorithm: "
                            + ARTIFACT_CHECKSUM_ALGORITHM));
    artifactOperationService.markAvailable(
        opResult.artifact().publicId(),
        new ArtifactChecksum(ARTIFACT_CHECKSUM_ALGORITHM, checksumHex),
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
      String resolvedDiff,
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
      // Story 3b-5 (AC3) — carry the diff resolved at ingest onto the enriched v2 head (prRef
      // itself
      // holds only the diffReference pointer), so the highest-version artifact the reviewer reads
      // never loses the diff. Null (absent scratch / no-push) leaves the v2 payload diff-less.
      if (resolvedDiff != null) {
        enriched.put("diff", resolvedDiff);
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
      // Story 3b-3 (AC2 — THE TRAP): the enrich UPDATE above createNextVersions a NEW PENDING v2
      // (ArtifactOperationService.createOrAdvanceArtifact UPDATE = createNextVersion), so the
      // highest-version prOutput — the one resolveImplementationArtifact selects for the reviewer —
      // is now PENDING again. Mark THAT enriched head available (checksum over the enriched bytes,
      // storageRef from the enrich result) so acceptImplementation's isApprovalEligible=AVAILABLE
      // gate can fire. Still best-effort: a marking failure stays inside this try/catch and must
      // not
      // unwind the committed runner outcome or block the WaitingForReview advance.
      markArtifactAvailable(result, enrichedBytes, correlationId);
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
    return artifactPayload(runnerExecutionId, ref, null);
  }

  /**
   * Story 3b-5 (AC1) — when {@code resolvedPrOutputDiff} is non-null (a prOutput whose diff
   * resolved in scratch), embed it as a {@code diff} field in the serialized payload alongside the
   * runner-reported refs. The runner ref carries only the ephemeral {@code diffReference} pointer,
   * so this is the one place the diff bytes enter the persisted v1 payload. Null leaves the payload
   * byte-identical to the pre-3b-5 behaviour (spec/plan, and any prOutput with no resolvable diff).
   */
  private Optional<ArtifactPayload> artifactPayload(
      String runnerExecutionId, JsonNode ref, String resolvedPrOutputDiff) {
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
      JsonNode payloadNode = ref;
      if (resolvedPrOutputDiff != null) {
        com.fasterxml.jackson.databind.node.ObjectNode withDiff = ref.deepCopy();
        withDiff.put("diff", resolvedPrOutputDiff);
        payloadNode = withDiff;
      }
      return Optional.of(
          new ArtifactPayload(
              artifactId.asText() + ".json", objectMapper.writeValueAsBytes(payloadNode)));
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

  // Story 3b-5 (AC2) — storage guardrails for the embedded prOutput diff. The line cap mirrors the
  // frontend PR_DIFF_MAX_LINES (5000); the byte ceiling is an absolute backstop against a
  // pathological single-line mega-diff. The frontend caps AGAIN at render
  // (SafeUnifiedDiffRenderer),
  // so this is purely the storage guardrail.
  private static final int PR_OUTPUT_DIFF_MAX_LINES = 5000;
  private static final int PR_OUTPUT_DIFF_MAX_BYTES = 1_000_000;
  private static final String PR_OUTPUT_DIFF_TRUNCATION_MARKER =
      "... diff truncated by deliveryline storage cap ...";

  /**
   * Story 3b-5 (AC1/AC2) — resolve the runner-reported {@code diffReference} to unified-diff bytes
   * in scratch, cap it to the storage guardrails, and return it for embedding in the persisted
   * prOutput payload. Returns {@link Optional#empty()} (graceful — never fails ingest) when the
   * reference is blank or the scratch file is absent/evicted; today's runners emit only the
   * pointer, not the file (OQ-1), so the absent case is the common one until the runner two-phase
   * contract writes the diff. Never logs diff CONTENT — sizes/counts only.
   */
  private Optional<String> resolvePrOutputDiff(
      String runnerExecutionId, String workflowRunId, String artifactId, JsonNode prRef) {
    String diffReference = textOrNull(prRef, "diffReference");
    if (diffReference == null) {
      return Optional.empty();
    }
    Optional<byte[]> maybeBytes =
        scratchStore.tryReadArtifactContent(runnerExecutionId, diffReference);
    if (maybeBytes.isEmpty()) {
      log.warn(
          "onResult prOutput diffReference not found in scratch (graceful skip) "
              + "runnerExecutionId={} workflowRunId={} artifactId={} diffReference={}",
          runnerExecutionId,
          workflowRunId,
          artifactId,
          diffReference);
      return Optional.empty();
    }
    byte[] raw = maybeBytes.get();
    String diff = new String(raw, StandardCharsets.UTF_8);
    if (diff.isBlank()) {
      // A present-but-empty/whitespace-only scratch file carries no diff to render. Treat it
      // identically to an absent scratch read (return empty, embed no `diff` field) so the two
      // "no diff" paths persist identical payload bytes/checksum — the read path would blank an
      // embedded "" back to null anyway, so embedding it only diverges the stored bytes.
      log.warn(
          "onResult prOutput diffReference resolved to blank content (graceful skip) "
              + "runnerExecutionId={} workflowRunId={} artifactId={} diffReference={}",
          runnerExecutionId,
          workflowRunId,
          artifactId,
          diffReference);
      return Optional.empty();
    }
    return Optional.of(boundPrOutputDiff(diff, runnerExecutionId, workflowRunId, artifactId));
  }

  /**
   * Story 3b-5 — bound a raw prOutput unified diff to the embed caps (max lines/bytes + truncation
   * marker) so the embedded payload stays within the runner-result size cap. Shared by the scratch
   * diffReference path ({@link #resolvePrOutputDiff}) and the AUTHORITATIVE captureAndPush diff
   * path (the pr-output enrichment) so both embed identically bounded content.
   */
  private String boundPrOutputDiff(
      String rawDiff, String runnerExecutionId, String workflowRunId, String artifactId) {
    String diff = rawDiff;
    int originalLines = countLines(diff);
    boolean truncated = false;
    if (originalLines > PR_OUTPUT_DIFF_MAX_LINES) {
      diff = firstLines(diff, PR_OUTPUT_DIFF_MAX_LINES) + "\n" + PR_OUTPUT_DIFF_TRUNCATION_MARKER;
      truncated = true;
    }
    if (diff.getBytes(StandardCharsets.UTF_8).length > PR_OUTPUT_DIFF_MAX_BYTES) {
      diff =
          truncateToBytes(diff, PR_OUTPUT_DIFF_MAX_BYTES) + "\n" + PR_OUTPUT_DIFF_TRUNCATION_MARKER;
      truncated = true;
    }
    int storedBytes = diff.getBytes(StandardCharsets.UTF_8).length;
    if (truncated) {
      log.warn(
          "onResult prOutput diff truncated runnerExecutionId={} workflowRunId={} artifactId={} "
              + "originalLines={} storedBytes={}",
          runnerExecutionId,
          workflowRunId,
          artifactId,
          originalLines,
          storedBytes);
    }
    log.info(
        "onResult prOutput diff resolved runnerExecutionId={} workflowRunId={} artifactId={} "
            + "lineCount={} byteSize={}",
        runnerExecutionId,
        workflowRunId,
        artifactId,
        Math.min(originalLines, PR_OUTPUT_DIFF_MAX_LINES),
        storedBytes);
    return diff;
  }

  private static int countLines(String value) {
    if (value.isEmpty()) {
      return 0;
    }
    int lines = 1;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) == '\n') {
        lines++;
      }
    }
    return lines;
  }

  /** First {@code maxLines} lines of {@code value}, terminators stripped from the cut point. */
  private static String firstLines(String value, int maxLines) {
    int count = 0;
    for (int idx = 0; idx < value.length(); idx++) {
      if (value.charAt(idx) == '\n') {
        count++;
        if (count == maxLines) {
          return value.substring(0, idx);
        }
      }
    }
    return value;
  }

  /** UTF-8 byte-bounded prefix of {@code value}, dropping any partial trailing multibyte char. */
  private static String truncateToBytes(String value, int maxBytes) {
    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
    if (utf8.length <= maxBytes) {
      return value;
    }
    java.nio.charset.CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.IGNORE);
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(utf8, 0, maxBytes);
    java.nio.CharBuffer decoded = java.nio.CharBuffer.allocate(maxBytes);
    decoder.decode(buffer, decoded, true);
    decoded.flip();
    return decoded.toString();
  }

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
   *
   * <p>Public (story 3d-4 review) so {@code ManualArtifactSubmissionService} — relocated to {@code
   * application.workflow} as a REST/CLI-facing command service (the thin-controller boundary
   * forbids REST adapters from reaching into {@code application.runner}) — validates an
   * operator-submitted result with the IDENTICAL context the broker builds for an automated result
   * (AC2 — no parallel weaker validation).
   */
  public ValidationContext buildResultValidationContext(
      String workflowRunId, String runnerExecutionId) {
    ValidationContext.Builder builder =
        ValidationContext.builder().maxPayloadBytes(RUNNER_RESULT_MAX_PAYLOAD_BYTES);
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
          RUNNER_SECRET_LEAK,
          // Story 3h-1 (AC5) — a build failure means the PR_OUTPUT runner DID produce a result that
          // was harvested + built (then failed the gate), so a subsequent arrival is a duplicate.
          RUNNER_BUILD_FAILED ->
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
      // Phase 3 (story 3.17b AC5 / D6) — worker-crash lease reclamation. A worker thread that died
      // mid-dispatch leaves a LEASED row (worker_id set, dispatched_at stamped) whose container may
      // never have started; its dispatched_at + last_activity_at age past the orphan threshold with
      // no heartbeat. Route it through the SAME recordOrphaned + runner.orphaned path (do NOT fork
      // a
      // second orphan path). A worker-less queued row is never returned by this finder, so a row
      // that is correctly waiting is never reclaimed. Phase 2's last_activity_at scan already
      // covers
      // these rows, but this explicit leased predicate makes the AC5 guarantee precise + testable.
      List<RunnerExecutionSnapshot> leasedStaleCandidates =
          recordPort.findLeasedStaleByStageAndDispatchedAtBefore(stage, orphanThreshold, batchSize);
      for (RunnerExecutionSnapshot snapshot : leasedStaleCandidates) {
        try {
          Boolean flipped =
              perItemTransactionTemplate.execute(status -> processStaleOrphan(snapshot));
          if (Boolean.TRUE.equals(flipped)) {
            orphanFlips++;
          }
        } catch (Exception error) {
          log.error(
              "scanForStaleExecutions leased-lease item failed runnerExecutionId={} cause={}",
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
