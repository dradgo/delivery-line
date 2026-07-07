package org.dradgo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictWritePort;
import org.dradgo.application.integration.conflict.spi.NewIntegrationConflict;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceSubticketService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.queue.RunnerWorkerPool;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.workflow.DomainResult;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.commands.WorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowRunStatePort;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.registry.AllowedAction;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

final class ArchitectureRuleCatalog {

  static final String ARCHITECTURE_TAG = "architecture";

  private static final String DOMAIN_PACKAGE = "org.dradgo.domain..";
  private static final String APPLICATION_PACKAGE = "org.dradgo.application..";
  private static final String ADAPTERS_PACKAGE = "org.dradgo.adapters..";
  private static final String CLI_ADAPTER_PACKAGE = "org.dradgo.adapters.cli..";
  private static final String REST_ADAPTER_PACKAGE = "org.dradgo.adapters.rest..";
  private static final String PERSISTENCE_ADAPTER_PACKAGE = "org.dradgo.adapters.persistence..";
  private static final String FILES_ADAPTER_PACKAGE = "org.dradgo.adapters.files..";
  private static final String RUNNER_ADAPTER_PACKAGE = "org.dradgo.adapters.runner..";
  private static final String INTEGRATION_ADAPTER_PACKAGE = "org.dradgo.adapters.integration..";
  private static final String DIAGNOSTICS_ADAPTER_PACKAGE = "org.dradgo.adapters.diagnostics..";
  // Story 3.9 — system-git CLI adapter slice (CliGitAdapter implements the GitCommandPort SPI).
  private static final String GIT_ADAPTER_PACKAGE = "org.dradgo.adapters.git..";
  // Story 3h-1 — build-command adapter slice (ProcessBuildCommandAdapter implements
  // BuildCommandPort).
  private static final String BUILD_ADAPTER_PACKAGE = "org.dradgo.adapters.build..";
  private static final String REPOSITORY_WORKSPACE_PACKAGE =
      "org.dradgo.application.runner.workspace..";
  private static final String INFRASTRUCTURE_PACKAGE = "org.dradgo.infrastructure..";
  private static final String SECURITY_PACKAGE = "org.dradgo.application.security..";
  private static final String PERSISTENCE_ENTITY_PACKAGE =
      "org.dradgo.adapters.persistence.entity..";
  private static final String PERSISTENCE_REPOSITORY_PACKAGE =
      "org.dradgo.adapters.persistence.repository..";
  private static final String PERSISTENCE_MAPPER_PACKAGE =
      "org.dradgo.adapters.persistence.mapper..";

  private static final Pattern SENSITIVE_PATTERN_NAME =
      Pattern.compile(".*(SECRET|TOKEN|PASSWORD|AUTHORIZATION|CREDENTIAL|PRIVATE_KEY|API_KEY).*");
  private static final List<String> SENSITIVE_PREDICATE_NAMES =
      List.of("redactIfSensitive", "looksSecretLikeKey", "looksHighEntropy", "alreadyRedacted");

  static final ArchRule LAYERED_BOUNDARIES =
      namedRule(
          "package layers must respect domain, application, adapter, and infrastructure boundaries",
          "Remediation: keep domain independent, route business flow through application, and prevent any layer from reaching into adapters or infrastructure directly.",
          layeredArchitecture()
              .consideringAllDependencies()
              .withOptionalLayers(true)
              .layer("Domain")
              .definedBy(DOMAIN_PACKAGE)
              .layer("Application")
              .definedBy(APPLICATION_PACKAGE)
              .layer("Adapters")
              .definedBy(ADAPTERS_PACKAGE)
              .layer("Infrastructure")
              .definedBy(INFRASTRUCTURE_PACKAGE)
              .whereLayer("Domain")
              .mayOnlyBeAccessedByLayers("Application", "Adapters", "Infrastructure")
              .whereLayer("Application")
              .mayOnlyBeAccessedByLayers("Adapters", "Infrastructure")
              .whereLayer("Adapters")
              .mayNotBeAccessedByAnyLayer()
              .whereLayer("Infrastructure")
              .mayNotBeAccessedByAnyLayer());

  static final ArchRule ADAPTER_PACKAGE_LAYOUT =
      namedRule(
          "adapter classes must stay inside the reserved adapter package layout",
          "Remediation: move adapter code under adapters.cli, adapters.rest, adapters.persistence, adapters.files, adapters.runner, adapters.integration, adapters.diagnostics, adapters.git, or adapters.build.",
          classes()
              .that()
              .resideInAPackage(ADAPTERS_PACKAGE)
              .should()
              .resideInAnyPackage(
                  CLI_ADAPTER_PACKAGE,
                  REST_ADAPTER_PACKAGE,
                  PERSISTENCE_ADAPTER_PACKAGE,
                  FILES_ADAPTER_PACKAGE,
                  RUNNER_ADAPTER_PACKAGE,
                  INTEGRATION_ADAPTER_PACKAGE,
                  DIAGNOSTICS_ADAPTER_PACKAGE,
                  GIT_ADAPTER_PACKAGE,
                  BUILD_ADAPTER_PACKAGE));

  static final ArchRule DOMAIN_PACKAGE_MUST_EXIST =
      namedRule(
          "domain package must contain production code",
          "Remediation: keep the domain layer present so architecture rules cannot vacuously pass against an empty package.",
          classes()
              .that()
              .resideInAPackage(DOMAIN_PACKAGE)
              .should(bePresent())
              .allowEmptyShould(false));

  static final ArchRule APPLICATION_PACKAGE_MUST_EXIST =
      namedRule(
          "application package must contain production code",
          "Remediation: keep the application layer present so architecture rules cannot vacuously pass against an empty package.",
          classes()
              .that()
              .resideInAPackage(APPLICATION_PACKAGE)
              .should(bePresent())
              .allowEmptyShould(false));

  static final ArchRule ADAPTERS_PACKAGE_MUST_EXIST =
      namedRule(
          "adapter package must contain production code",
          "Remediation: keep at least one production adapter package present so architecture rules cannot vacuously pass against an empty adapter layer.",
          classes()
              .that()
              .resideInAPackage(ADAPTERS_PACKAGE)
              .should(bePresent())
              .allowEmptyShould(false));

  static final ArchRule ADAPTER_SLICES_MUST_BE_FREE_OF_CYCLES =
      namedRule(
          "adapter slices must be free of cycles",
          "Remediation: route shared behavior through application services or ports instead of creating adapter-to-adapter cycles.",
          slices().matching("org.dradgo.adapters.(*)..").should().beFreeOfCycles());

  static final ArchRule ADAPTER_SLICES_MUST_NOT_DEPEND_ON_EACH_OTHER =
      namedRule(
          "adapter slices must not depend on each other",
          "Remediation: keep each adapter slice (cli, rest, persistence, files, runner, integration) independent; route shared behavior through application services or ports instead of cross-adapter imports.",
          slices().matching("org.dradgo.adapters.(*)..").should().notDependOnEachOther());

  static final ArchRule DOMAIN_MUST_BE_FRAMEWORK_FREE =
      namedRule(
          "domain classes must stay framework-free and isolated from adapters and infrastructure",
          "Remediation: move Spring, JPA, Jackson, servlet, and shell concerns into adapters or application services.",
          noClasses()
              .that()
              .resideInAPackage(DOMAIN_PACKAGE)
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "org.springframework..",
                  "jakarta.persistence..",
                  "jakarta.servlet.http..",
                  "com.fasterxml.jackson..",
                  "org.dradgo.adapters..",
                  "org.dradgo.infrastructure.."));

  static final ArchRule APPLICATION_AND_DOMAIN_MUST_NOT_DEPEND_ON_PERSISTENCE_TYPES =
      namedRule(
          "application and domain code must not depend on persistence entities or repositories",
          "Remediation: introduce application-owned ports and persistence mappers so only adapters.persistence knows JPA entities and repositories.",
          noClasses()
              .that()
              .resideInAnyPackage(APPLICATION_PACKAGE, DOMAIN_PACKAGE)
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(PERSISTENCE_ENTITY_PACKAGE, PERSISTENCE_REPOSITORY_PACKAGE));

  static final ArchRule APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE =
      namedRule(
          "application code must not depend directly on infrastructure classes",
          "Remediation: keep application logic on domain types plus application-owned ports; move infrastructure concerns behind adapters or injected framework services.",
          noClasses()
              .that()
              .resideInAPackage(APPLICATION_PACKAGE)
              .should()
              .dependOnClassesThat()
              .resideInAPackage(INFRASTRUCTURE_PACKAGE));

  static final ArchRule REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_PERSISTENCE_OR_EXTERNAL_ADAPTERS =
      namedRule(
          "REST and CLI adapters must stay thin and avoid direct persistence or external adapter dependencies",
          "Remediation: keep transport adapters translating requests into application commands instead of reaching into persistence, filesystem, runner, or integration adapter implementations.",
          noClasses()
              .that()
              .resideInAnyPackage(CLI_ADAPTER_PACKAGE, REST_ADAPTER_PACKAGE)
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  PERSISTENCE_ADAPTER_PACKAGE,
                  FILES_ADAPTER_PACKAGE,
                  RUNNER_ADAPTER_PACKAGE,
                  INTEGRATION_ADAPTER_PACKAGE));

  /**
   * Story 2.13 AC8 — REST controllers stay thin. They may parse headers/path/body, construct
   * application command records, invoke {@code workflowCommandService.*} / {@code
   * workflowInspectionService.*}, and map results into response DTOs. They must NOT reach into
   * application-layer SPI ports (workflow / clarification / artifact / runner) — those are
   * persistence-facing and meant to be consumed by application services only. Combined with the
   * existing {@link #REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_PERSISTENCE_OR_EXTERNAL_ADAPTERS} rule
   * this confines transports to {@code application.* (non-spi)} + {@code domain.*} + framework
   * types.
   */
  static final ArchRule REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER =
      namedRule(
          "REST controllers must stay thin: no direct depend on application SPI ports, persistence, or runner adapters",
          "Remediation: route workflow orchestration through the application service surface (WorkflowCommandService / WorkflowInspectionService / ApprovalService / ClarificationService / ArtifactOperationService) instead of reaching past it into adapter-facing ports.",
          noClasses()
              .that()
              .resideInAPackage(REST_ADAPTER_PACKAGE)
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "org.dradgo.application.workflow.spi..",
                  "org.dradgo.application.clarification.spi..",
                  "org.dradgo.application.artifact.spi..",
                  "org.dradgo.application.runner..",
                  PERSISTENCE_ADAPTER_PACKAGE,
                  RUNNER_ADAPTER_PACKAGE));

  static final ArchRule REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_JPA_ENTITIES =
      namedRule(
          "REST and CLI adapters must not directly reference JPA entity types",
          "Remediation: translate JPA entities into application-facing snapshots/records inside the persistence adapter; transport adapters consume those records, never the @Entity types.",
          noClasses()
              .that()
              .resideInAnyPackage(CLI_ADAPTER_PACKAGE, REST_ADAPTER_PACKAGE)
              .should()
              .dependOnClassesThat()
              .areAnnotatedWith(Entity.class));

  static final ArchRule REST_CONTROLLERS_MUST_BE_NAMED_AS_CONTROLLERS =
      namedRule(
          "REST controller classes must end in Controller and stay under adapters.rest",
          "Remediation: name HTTP transport classes *Controller and keep them under org.dradgo.adapters.rest.",
          classes()
              .that()
              .areAnnotatedWith(RestController.class)
              .should()
              .resideInAPackage(REST_ADAPTER_PACKAGE)
              .andShould(haveSimpleNameEndingWithAny("Controller"))
              .allowEmptyShould(false));

  static final ArchRule REST_CONTROLLER_SUFFIX_REQUIRES_REST_CONTROLLER_ANNOTATION =
      namedRule(
          "classes named *Controller must be @RestController and live under adapters.rest",
          "Remediation: if a class is named *Controller, annotate it @RestController and place it under org.dradgo.adapters.rest; otherwise rename it to avoid the convention.",
          classes()
              .that()
              .haveSimpleNameEndingWith("Controller")
              .and()
              .resideInAPackage("org.dradgo..")
              .should()
              .beAnnotatedWith(RestController.class)
              .andShould()
              .resideInAPackage(REST_ADAPTER_PACKAGE));

  static final ArchRule SPRING_SHELL_COMMANDS_MUST_BE_PLURALIZED =
      namedRule(
          "Spring Shell command adapters must end in Commands and stay under adapters.cli",
          "Remediation: rename Spring Shell transport classes to *Commands and keep them under org.dradgo.adapters.cli.",
          classes()
              .that()
              .areAnnotatedWith(CommandGroup.class)
              .should()
              .resideInAPackage(CLI_ADAPTER_PACKAGE)
              .andShould(haveSimpleNameEndingWithAny("Commands")));

  static final ArchRule SHELL_COMMANDS_SUFFIX_REQUIRES_COMMAND_GROUP_ANNOTATION =
      namedRule(
          "classes named *Commands must be @CommandGroup and live under adapters.cli",
          "Remediation: if a class is named *Commands, annotate it @CommandGroup and place it under org.dradgo.adapters.cli; otherwise rename it to avoid the convention.",
          classes()
              .that()
              .haveSimpleNameEndingWith("Commands")
              .and()
              .resideInAPackage("org.dradgo..")
              .should()
              .beAnnotatedWith(CommandGroup.class)
              .andShould()
              .resideInAPackage(CLI_ADAPTER_PACKAGE));

  static final ArchRule APPLICATION_COMMAND_DTOS_MUST_BE_SINGULAR =
      namedRule(
          "application command DTOs must end in Command and stay under application",
          "Remediation: keep application command DTOs under org.dradgo.application and name them with the singular *Command suffix.",
          classes()
              .that()
              .implement(WorkflowCommand.class)
              .and()
              .areNotInterfaces()
              .should()
              .resideInAPackage(APPLICATION_PACKAGE)
              .andShould(haveSimpleNameEndingWithAny("Command"))
              .allowEmptyShould(false));

  static final ArchRule PERSISTENCE_ENTITIES_MUST_BE_NAMED_AS_ENTITIES =
      namedRule(
          "persistence entities must end in Entity and stay under adapters.persistence.entity",
          "Remediation: keep JPA entity types under org.dradgo.adapters.persistence.entity and use the *Entity suffix.",
          classes()
              .that()
              .areAnnotatedWith(Entity.class)
              .should()
              .resideInAPackage(PERSISTENCE_ENTITY_PACKAGE)
              .andShould(haveSimpleNameEndingWithAny("Entity"))
              .allowEmptyShould(false));

  static final ArchRule PERSISTENCE_MAPPERS_MUST_BE_LOCATION_QUALIFIED =
      namedRule(
          "persistence mappers must stay under adapters.persistence.mapper and use Mapper or Mappers suffixes (and at least one mapper must exist)",
          "Remediation: place persistence translation classes under org.dradgo.adapters.persistence.mapper and name them *Mapper or *Mappers; ensure each persistence aggregate has an explicit mapper class.",
          classes()
              .that()
              .resideInAPackage(PERSISTENCE_MAPPER_PACKAGE)
              .should(haveSimpleNameEndingWithAny("Mapper", "Mappers"))
              .allowEmptyShould(false));

  static final ArchRule APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES =
      namedRule(
          "application services must end in Service or Orchestrator",
          "Remediation: keep application service beans under org.dradgo.application and use the *Service suffix for service classes, or *Orchestrator for cross-service coordinators.",
          classes()
              .that()
              .resideInAPackage(APPLICATION_PACKAGE)
              .and()
              .areAnnotatedWith(Service.class)
              .should(haveSimpleNameEndingWithAny("Service", "Orchestrator")));

  static final ArchRule APPLICATION_RESULTS_MUST_USE_RESULT_OR_OUTCOME_SUFFIX =
      namedRule(
          "application use-case results must end in Result or Outcome",
          "Remediation: name application-facing result carriers with *Result or *Outcome so call sites stay explicit.",
          classes()
              .that()
              .implement(DomainResult.class)
              .and()
              .areNotInterfaces()
              .should(haveSimpleNameEndingWithAny("Result", "Outcome"))
              .allowEmptyShould(false));

  static final ArchRule ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE =
      namedRule(
          "workflow state changes must go through WorkflowTransitionService",
          "Remediation: route every workflow state mutation through WorkflowTransitionService and keep submit bootstrap on the create path only.",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(WorkflowTransitionService.class.getName())
              .should()
              .callMethod(WorkflowRunEntity.class, "setCurrentState", WorkflowState.class)
              .orShould()
              .callMethod(
                  WorkflowRunStatePort.class,
                  "updateCurrentState",
                  String.class,
                  WorkflowState.class,
                  Long.class));

  /**
   * Story 3a-1 (AC9) — the spec-stage success auto-advance ({@code Investigating ->
   * WaitingForSpecApproval}) is owned solely by {@link WorkflowOrchestrationService}. Its {@code
   * onSpecStageSucceeded} callback may be invoked only by the {@link RunnerBroker} result-harvest
   * seam, so no other service can react to a runner success outcome by driving workflow state.
   *
   * <p>OQ-2 (minimal reconciliation): the broker's pre-existing FAILURE drive ({@code
   * driveWorkflowFailed -> WorkflowTransitionService}) is the documented exception — this rule is
   * scoped to the SUCCESS auto-advance. The complementary {@link
   * #ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE} keeps every state mutation routed
   * through {@code WorkflowTransitionService}.
   */
  static final ArchRule ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_SPEC_RUNNER_SUCCESS =
      namedRule(
          "only WorkflowOrchestrationService may auto-advance workflow state on a spec runner success",
          "Remediation: route the spec-stage success auto-advance through WorkflowOrchestrationService.onSpecStageSucceeded, invoked only by the RunnerBroker result-harvest seam (story 3a-1 AC9). The broker failure-drive is the documented OQ-2 exception.",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(WorkflowOrchestrationService.class.getName())
              .and()
              .doNotHaveFullyQualifiedName(RunnerBroker.class.getName())
              .should()
              .callMethod(
                  WorkflowOrchestrationService.class,
                  "onSpecStageSucceeded",
                  String.class,
                  String.class,
                  String.class));

  /**
   * Story 3.17b (AC3b / D4) — the queue is the only entry to runner dispatch. Only {@link
   * WorkflowOrchestrationService} (the 6 orchestration dispatch methods), {@link RecoveryService}
   * (the recovery retry), and the {@link RunnerWorkerPool} may {@code enqueue}. Restricting it
   * keeps a stray service from injecting work that bypasses the in-flight guards + backpressure.
   */
  static final ArchRule ONLY_ORCHESTRATION_RECOVERY_AND_WORKER_POOL_MAY_ENQUEUE =
      namedRule(
          "only WorkflowOrchestrationService, RecoveryService and RunnerWorkerPool may enqueue runner executions",
          "Remediation: route runner dispatch through RunnerExecutionQueue.enqueue from the orchestration/recovery callers only (story 3.17b AC3b).",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(WorkflowOrchestrationService.class.getName())
              .and()
              .doNotHaveFullyQualifiedName(RecoveryService.class.getName())
              .and()
              .doNotHaveFullyQualifiedName(RunnerWorkerPool.class.getName())
              .should()
              .callMethod(
                  RunnerExecutionQueue.class,
                  "enqueue",
                  String.class,
                  RunnerStage.class,
                  String.class,
                  ActorContext.class,
                  int.class));

  /**
   * Story 3.17b (AC3b / D4) — only the {@link RunnerWorkerPool} worker loop may {@code dequeue} a
   * leased row. No other class leases work off the queue.
   */
  static final ArchRule ONLY_WORKER_POOL_MAY_DEQUEUE =
      namedRule(
          "only RunnerWorkerPool may dequeue runner executions",
          "Remediation: leasing queued executions is the worker pool's job — call RunnerExecutionQueue.dequeue only from RunnerWorkerPool (story 3.17b AC3b).",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(RunnerWorkerPool.class.getName())
              .should()
              .callMethod(RunnerExecutionQueue.class, "dequeue", String.class));

  /**
   * Story 3.17b (AC3 / D4 / Trap T1) — the relocated dispatch body ({@link
   * RunnerBroker#executeQueuedDispatch}) runs ONLY on the worker loop. No caller may invoke it
   * directly, so there is no synchronous direct-dispatch path that bypasses the queue.
   */
  static final ArchRule ONLY_WORKER_POOL_MAY_RUN_QUEUED_DISPATCH =
      namedRule(
          "only RunnerWorkerPool may run the relocated queued-dispatch body",
          "Remediation: RunnerBroker.executeQueuedDispatch is the worker-loop action — invoke it only from RunnerWorkerPool, never as a synchronous direct dispatch (story 3.17b AC3).",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(RunnerWorkerPool.class.getName())
              .should()
              .callMethod(
                  RunnerBroker.class, "executeQueuedDispatch", RunnerExecutionSnapshot.class));

  /**
   * Story 3.17b review (D4) — the legacy synchronous {@link RunnerBroker#dispatch} is retained for
   * unit tests only. No PRODUCTION class may call it: the queue ({@code enqueue} + {@code
   * executeQueuedDispatch} on the worker) is the only dispatch entry. ArchUnit scans the production
   * classes, so this rule passing means there are zero production callers — strengthening AC3
   * beyond the {@link #ONLY_WORKER_POOL_MAY_RUN_QUEUED_DISPATCH} restriction (which alone left the
   * deprecated synchronous {@code dispatch} reachable).
   */
  static final ArchRule NO_PRODUCTION_CALLER_MAY_INVOKE_LEGACY_SYNCHRONOUS_DISPATCH =
      namedRule(
          "no production class may invoke the legacy synchronous RunnerBroker.dispatch",
          "Remediation: route every dispatch through RunnerExecutionQueue.enqueue; RunnerBroker.dispatch is the deprecated synchronous path kept for tests only (story 3.17b AC3 / review D4).",
          noClasses()
              .should()
              .callMethod(
                  RunnerBroker.class,
                  "dispatch",
                  String.class,
                  RunnerStage.class,
                  String.class,
                  ActorContext.class));

  /**
   * Story 4.17 (AC9) — only the {@code application.integration.conflict} package may WRITE the
   * {@code integration_conflicts} table. The write crosses {@link
   * IntegrationConflictWritePort#insertIfAbsent}, so the detection service ({@code
   * application.integration.conflict.IntegrationConflictDetectionService}) is the only class that
   * may call it; the {@code @Scheduled} trigger in {@code infrastructure.config} only delegates to
   * the service and the persistence adapter merely IMPLEMENTS the port (callMethod matches the
   * interface owner, so the adapter's own body does not trip this — the
   * archunit-callmethod-matches-interface-owner lesson).
   */
  static final ArchRule ONLY_CONFLICT_PACKAGE_MAY_WRITE_INTEGRATION_CONFLICTS =
      namedRule(
          "only application.integration.conflict may write integration_conflicts via the write port",
          "Remediation: route every integration_conflicts insert through IntegrationConflictDetectionService in application.integration.conflict; the infrastructure @Scheduled trigger only delegates, and no other package may call IntegrationConflictWritePort.insertIfAbsent (story 4.17 AC9).",
          noClasses()
              .that()
              .resideOutsideOfPackage("org.dradgo.application.integration.conflict..")
              .should()
              .callMethod(
                  IntegrationConflictWritePort.class,
                  "insertIfAbsent",
                  NewIntegrationConflict.class));

  /**
   * Story 3.11 (AC9) — the plan-stage twin of {@link
   * #ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_SPEC_RUNNER_SUCCESS}. The plan-stage success auto-advance
   * ({@code Executing -> WaitingForReview}) is owned solely by {@link
   * WorkflowOrchestrationService}; its {@code onPlanStageSucceeded} callback may be invoked only by
   * the {@link RunnerBroker} result-harvest seam, so no other service can react to a runner success
   * outcome by driving workflow state. The broker's pre-existing FAILURE drive remains the
   * documented OQ-2 exception (this rule, like its spec twin, is scoped to the SUCCESS
   * auto-advance).
   */
  static final ArchRule ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_PLAN_RUNNER_SUCCESS =
      namedRule(
          "only WorkflowOrchestrationService may auto-advance workflow state on a plan runner success",
          "Remediation: route the plan-stage success auto-advance through WorkflowOrchestrationService.onPlanStageSucceeded, invoked only by the RunnerBroker result-harvest seam (story 3.11 AC9). The broker failure-drive is the documented OQ-2 exception.",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(WorkflowOrchestrationService.class.getName())
              .and()
              .doNotHaveFullyQualifiedName(RunnerBroker.class.getName())
              .should()
              .callMethod(
                  WorkflowOrchestrationService.class,
                  "onPlanStageSucceeded",
                  String.class,
                  String.class,
                  String.class));

  /**
   * Story 3.12 (AC5) — the pr-output twin of {@link
   * #ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_PLAN_RUNNER_SUCCESS}. The pr-output-stage success
   * auto-advance ({@code Executing -> WaitingForReview}, reason {@code pr_output_ready}) is owned
   * solely by {@link WorkflowOrchestrationService}; its {@code onPrOutputStageSucceeded} callback
   * may be invoked only by the {@link RunnerBroker} result-harvest seam. The broker's pre-existing
   * FAILURE drive (including the new ref-drift / ref-validation -> Failed path, Decision D5)
   * remains the documented OQ-2 exception — this rule, like its spec/plan twins, is scoped to the
   * SUCCESS auto-advance.
   */
  static final ArchRule ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_PR_OUTPUT_RUNNER_SUCCESS =
      namedRule(
          "only WorkflowOrchestrationService may auto-advance workflow state on a pr-output runner success",
          "Remediation: route the pr-output-stage success auto-advance through WorkflowOrchestrationService.onPrOutputStageSucceeded, invoked only by the RunnerBroker result-harvest seam (story 3.12 AC5). The broker failure-drive is the documented OQ-2 exception.",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(WorkflowOrchestrationService.class.getName())
              .and()
              .doNotHaveFullyQualifiedName(RunnerBroker.class.getName())
              .should()
              .callMethod(
                  WorkflowOrchestrationService.class,
                  "onPrOutputStageSucceeded",
                  String.class,
                  String.class,
                  String.class));

  /**
   * Story 3.16 (AC9) plus story 3f-1: governed ticket-source comments have two canonical write-back
   * paths only: completion sync in {@link WorkflowOrchestrationService}, and the adapter-internal
   * parent-link comment posted as part of Linear sub-ticket creation. Runner CLIs, REST
   * controllers, persistence, and other services must not call the comment operation directly.
   */
  static final ArchRule ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT =
      namedRule(
          "only canonical completion-sync and subticket adapter paths may post a Linear comment",
          "Remediation: route completion write-back through WorkflowOrchestrationService.syncCompletionToLinear and source sub-ticket parent-link comments through TicketSourceSubticketService -> TicketSourceAdapter.createSubticket. Runner CLIs, controllers, persistence, and other components must never call TicketSourceAdapter.postGovernedRunComment directly.",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(WorkflowOrchestrationService.class.getName())
              .and()
              .doNotHaveFullyQualifiedName("org.dradgo.adapters.cli.WorkflowCommands")
              .and()
              .doNotHaveFullyQualifiedName(
                  "org.dradgo.adapters.integration.ticketsource.linear.LinearRealAdapter")
              .should()
              .callMethod(
                  TicketSourceAdapter.class,
                  "postGovernedRunComment",
                  TicketRef.class,
                  GovernedRunComment.class));

  static final ArchRule ONLY_SUBTICKET_SERVICE_MAY_CREATE_SOURCE_SUBTICKET =
      namedRule(
          "only TicketSourceSubticketService may call source sub-ticket creation",
          "Remediation: route split sub-ticket creation through TicketSourceSubticketService so capability gating, idempotency, and log-safety stay centralized. Controllers, CLIs, runner code, and persistence adapters must not call TicketSourceAdapter.createSubticket directly.",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(TicketSourceSubticketService.class.getName())
              .should()
              .callMethod(
                  TicketSourceAdapter.class,
                  "createSubticket",
                  TicketRef.class,
                  SubticketDraft.class));

  static final ArchRule ARTIFACT_WRITES_MUST_GO_THROUGH_ARTIFACT_OPERATION_SERVICE =
      namedRule(
          "artifact writes must go through ArtifactOperationService once artifact storage lands",
          "Remediation: when story 1.12 introduces LocalArtifactStore or equivalent, route approval-eligible file writes through application.artifact service boundaries only.",
          classes()
              .that()
              .doNotHaveFullyQualifiedName(
                  "org.dradgo.application.artifact.ArtifactOperationService")
              .should(notCallFutureArtifactWriteMethods()));

  static final ArchRule APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL =
      namedRule(
          "ApprovalService must live under application.approval and stay free of persistence/adapter dependencies",
          "Remediation: keep ApprovalService in org.dradgo.application.approval (story 2.9 trap T4). The service orchestrates the approval pipeline via application-owned ports + domain types — JPA entity types or any other adapter import would re-introduce the cross-layer dependency LAYERED_BOUNDARIES forbids.",
          classes()
              .that()
              .haveFullyQualifiedName("org.dradgo.application.approval.ApprovalService")
              .should()
              .resideInAPackage("org.dradgo.application.approval..")
              .andShould()
              .onlyDependOnClassesThat()
              .resideInAnyPackage(
                  "java..",
                  "org.slf4j..",
                  "org.springframework.beans.factory.annotation..",
                  "org.springframework.stereotype..",
                  "org.springframework.transaction.annotation..",
                  "org.dradgo.application..",
                  "org.dradgo.domain..")
              .allowEmptyShould(false));

  static final ArchRule TECHNICAL_APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL =
      namedRule(
          "TechnicalApprovalService must live under application.approval and stay free of persistence/adapter dependencies",
          "Remediation: keep TechnicalApprovalService in org.dradgo.application.approval (story 3.20 trap T4, sibling of ApprovalService). The service orchestrates the technical-approval pipeline via application-owned ports + domain types — JPA entity types or any other adapter import would re-introduce the cross-layer dependency LAYERED_BOUNDARIES forbids.",
          classes()
              .that()
              .haveFullyQualifiedName("org.dradgo.application.approval.TechnicalApprovalService")
              .should()
              .resideInAPackage("org.dradgo.application.approval..")
              .andShould()
              .onlyDependOnClassesThat()
              .resideInAnyPackage(
                  "java..",
                  "org.slf4j..",
                  "org.springframework.beans.factory.annotation..",
                  "org.springframework.stereotype..",
                  "org.springframework.transaction.annotation..",
                  "org.dradgo.application..",
                  "org.dradgo.domain..")
              .allowEmptyShould(false));

  /**
   * Story 3.18 (AC10): WorkflowBatchSubmissionService stays in {@code application.workflow} and
   * composes the single-submit command model via application-owned ports + domain types — never
   * reaching into adapter/persistence/runner types. Same shape as the approval boundary rule, with
   * {@code jakarta.validation} (payload validation) and {@code org.springframework.transaction}
   * (the independent-transaction template that keeps best-effort idempotency writes isolated) added
   * to the allow-list.
   */
  static final ArchRule BATCH_SUBMISSION_SERVICE_LIVES_IN_APPLICATION_WORKFLOW =
      namedRule(
          "WorkflowBatchSubmissionService must live under application.workflow and stay free of persistence/adapter dependencies",
          "Remediation: keep WorkflowBatchSubmissionService in org.dradgo.application.workflow (story 3.18 AC10). It orchestrates batch submission by composing WorkflowCommandService.submit + IdempotencyService via application-owned ports and domain types — any adapter/persistence/runner import would re-introduce the cross-layer dependency LAYERED_BOUNDARIES forbids.",
          classes()
              .that()
              .haveFullyQualifiedName(
                  "org.dradgo.application.workflow.WorkflowBatchSubmissionService")
              .should()
              .resideInAPackage("org.dradgo.application.workflow..")
              .andShould()
              .onlyDependOnClassesThat()
              .resideInAnyPackage(
                  "java..",
                  "jakarta.validation..",
                  "org.slf4j..",
                  "org.springframework.beans.factory.annotation..",
                  "org.springframework.stereotype..",
                  "org.springframework.transaction..",
                  "org.dradgo.application..",
                  "org.dradgo.domain..")
              .allowEmptyShould(false));

  /**
   * Story 2.11 trap T7 sibling rule: ClarificationService stays in {@code
   * application.clarification} and never reaches into adapter/persistence types. Same shape as the
   * approval boundary rule.
   */
  static final ArchRule CLARIFICATION_SERVICE_LIVES_IN_APPLICATION_CLARIFICATION =
      namedRule(
          "ClarificationService must live under application.clarification and stay free of persistence/adapter dependencies",
          "Remediation: keep ClarificationService in org.dradgo.application.clarification (story 2.11 trap T7). The service orchestrates the clarification pipeline via application-owned ports + domain types — JPA entity types or any other adapter import would re-introduce the cross-layer dependency LAYERED_BOUNDARIES forbids.",
          classes()
              .that()
              .haveFullyQualifiedName("org.dradgo.application.clarification.ClarificationService")
              .should()
              .resideInAPackage("org.dradgo.application.clarification..")
              .andShould()
              .onlyDependOnClassesThat()
              .resideInAnyPackage(
                  "java..",
                  "org.slf4j..",
                  "org.springframework.beans.factory.annotation..",
                  "org.springframework.stereotype..",
                  "org.springframework.transaction.annotation..",
                  "org.dradgo.application..",
                  "org.dradgo.domain..")
              .allowEmptyShould(false));

  /**
   * Story 3.17a (AC6 / D4) sibling rule: the RunnerExecutionQueue substrate lives under {@code
   * application.runner.queue} and reaches persistence only through the {@code
   * application.runner.spi} ports — never {@code adapters.*}/{@code infrastructure.*} (Trap T11).
   * Mirror of {@link #TECHNICAL_APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL}. The
   * caller-restriction rules (who may call enqueue/dequeue) are deferred to story 3.17b where the
   * callers are wired — in 3.17a the queue has no production caller so a restriction rule would be
   * vacuous.
   */
  static final ArchRule RUNNER_EXECUTION_QUEUE_LIVES_IN_APPLICATION_RUNNER_QUEUE =
      namedRule(
          "RunnerExecutionQueue must live under application.runner.queue and stay free of persistence/adapter dependencies",
          "Remediation: keep RunnerExecutionQueue + the worker pool / signal in org.dradgo.application.runner.queue (story 3.17a AC6 / 3.17b). The package depends on application-owned SPI ports + domain types + framework lifecycle/scheduling/tx primitives — a JPA entity import or any adapters/infrastructure import would re-introduce the cross-layer dependency LAYERED_BOUNDARIES forbids (Trap T11).",
          classes()
              .that()
              .resideInAPackage("org.dradgo.application.runner.queue..")
              .should()
              .resideInAPackage("org.dradgo.application.runner.queue..")
              .andShould()
              .onlyDependOnClassesThat()
              .resideInAnyPackage(
                  "java..",
                  "org.slf4j..",
                  "org.springframework.beans.factory.annotation..",
                  "org.springframework.stereotype..",
                  "org.springframework.transaction.annotation..",
                  // Story 3.17b — the worker pool is a SmartLifecycle (context) running a
                  // ThreadPoolTaskExecutor (scheduling); enqueue fires a post-commit NOTIFY via the
                  // transaction.support synchronization API. All are framework primitives, never
                  // adapters/persistence/infrastructure — the cross-layer ban (Trap T11) is intact.
                  "org.springframework.context..",
                  "org.springframework.scheduling..",
                  "org.springframework.transaction.support..",
                  "org.dradgo.application..",
                  "org.dradgo.domain..")
              .allowEmptyShould(false));

  /**
   * Story 2.12 trap T8 sibling rule: ClarificationLifecycleService +
   * ClarificationLifecycleOrchestrator stay in {@code application.clarification} and never reach
   * into adapter/persistence types. Mirror of {@link
   * #CLARIFICATION_SERVICE_LIVES_IN_APPLICATION_CLARIFICATION}.
   */
  static final ArchRule CLARIFICATION_LIFECYCLE_LIVES_IN_APPLICATION_CLARIFICATION =
      namedRule(
          "ClarificationLifecycleService + ClarificationLifecycleOrchestrator must live under application.clarification and stay free of persistence/adapter dependencies",
          "Remediation: keep ClarificationLifecycleService + ClarificationLifecycleOrchestrator in org.dradgo.application.clarification (story 2.12 trap T8). Each orchestrates the lifecycle pipeline via application-owned ports + domain types; JPA entity imports would re-introduce the cross-layer dependency LAYERED_BOUNDARIES forbids.",
          classes()
              .that()
              .haveFullyQualifiedName(
                  "org.dradgo.application.clarification.ClarificationLifecycleService")
              .or()
              .haveFullyQualifiedName(
                  "org.dradgo.application.clarification.ClarificationLifecycleOrchestrator")
              .should()
              .resideInAPackage("org.dradgo.application.clarification..")
              .andShould()
              .onlyDependOnClassesThat()
              .resideInAnyPackage(
                  "java..",
                  "org.slf4j..",
                  "org.springframework.beans.factory.annotation..",
                  "org.springframework.stereotype..",
                  "org.springframework.transaction.annotation..",
                  "org.dradgo.application..",
                  "org.dradgo.domain..")
              .allowEmptyShould(false));

  static final ArchRule SPECIFICATION_ARTIFACT_PROJECTION_LIVES_IN_APPLICATION_ARTIFACT =
      namedRule(
          "SpecificationArtifact projection must live under application.artifact and stay free of persistence/adapter dependencies",
          "Remediation: keep SpecificationArtifact in org.dradgo.application.artifact (story 2.8 AC1). The projection is a domain-shaped read view; routing it through adapters or persistence types would re-introduce the cross-layer dependency the existing LAYERED_BOUNDARIES rule forbids.",
          classes()
              .that()
              .haveFullyQualifiedName("org.dradgo.application.artifact.SpecificationArtifact")
              .should()
              .resideInAPackage("org.dradgo.application.artifact..")
              .andShould()
              .onlyDependOnClassesThat()
              .resideInAnyPackage(
                  "java..", "org.dradgo.application.artifact..", "org.dradgo.domain..")
              .allowEmptyShould(false));

  static final ArchRule RECOVERY_SERVICE_IS_SCOPE_PROTECTED =
      namedRule(
          "RecoveryService must expose only the Epic-1 baseline plus story-4.5 public method signatures",
          "Remediation: later Epic 4 stories will add rerun/reconcile/pause/classifyFailure. Story "
              + "4.5 added resume (widening this guard in lockstep — Reconciliation 2). Changing "
              + "RecoveryService's public surface without an Epic-4 story is a scope violation — "
              + "update the story and this guard together.",
          classes()
              .that()
              .haveFullyQualifiedName("org.dradgo.application.recovery.RecoveryService")
              .should(
                  exposeOnlyPublicMethodSignatures(
                      methodSignature(
                          "retry", String.class, String.class, ActorContext.class, String.class),
                      // Story 4.5 — resume mirrors retry's positional signature.
                      methodSignature(
                          "resume", String.class, String.class, ActorContext.class, String.class),
                      methodSignature("describeFailure", String.class))));

  // Story 3.22 (Trap T1): DeveloperTakeoverService is the SIBLING of RecoveryService (not a third
  // method on it). Pin its public surface to exactly takeoverWorkflow so a future story cannot
  // silently widen it without an explicit scope decision (mirrors
  // RECOVERY_SERVICE_IS_SCOPE_PROTECTED).
  static final ArchRule DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED =
      namedRule(
          "DeveloperTakeoverService must expose only the takeoverWorkflow public method signature",
          "Remediation: DeveloperTakeoverService is the canonical executor for the takeover action "
              + "(story 3.22). Adding another public method is a scope change — update the story and "
              + "this guard together.",
          classes()
              .that()
              .haveFullyQualifiedName("org.dradgo.application.recovery.DeveloperTakeoverService")
              .should(
                  exposeOnlyPublicMethodSignatures(
                      methodSignature(
                          "takeoverWorkflow",
                          org.dradgo.application.workflow.commands.TakeoverWorkflowCommand
                              .class))));

  static final ArchRule TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT =
      namedRule(
          "vendor-specific ticket-source types must not leak through the application.integration.ticketsource port",
          "Remediation: keep Linear GraphQL DTOs, vendor SDK types, and HTTP-client surface inside adapters.integration.ticketsource.{kind}; the application port "
              + "may only depend on neutral domain-shaped records (Ticket, TicketRef, CommentResult, TicketSourceCapabilities, GovernedRunComment, SubticketDraft, CreateSubticketResult). Story 1.14 AC1 / story 3.32 AC7 / story 3f-1 invariant.",
          noClasses()
              .that()
              .resideInAPackage("org.dradgo.application.integration.ticketsource..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "com.linear..",
                  "linear.api..",
                  "org.springframework.web.client..",
                  "org.springframework.http.client.."));

  static final ArchRule TICKET_SOURCE_ADAPTER_PORT_RESIDES_IN_APPLICATION =
      namedRule(
          "the TicketSourceAdapter port resides in application.integration.ticketsource",
          "Remediation: keep the vendor-neutral TicketSourceAdapter port (story 3.32 AC7) in org.dradgo.application.integration.ticketsource; vendor implementations live under adapters.integration.ticketsource.{kind}.",
          classes()
              .that()
              .areAssignableTo(TicketSourceAdapter.class)
              .and()
              .areInterfaces()
              .should()
              .resideInAPackage("org.dradgo.application.integration.ticketsource.."));

  static final ArchRule TICKET_SOURCE_IMPLS_RESIDE_IN_ADAPTERS_TICKETSOURCE =
      namedRule(
          "concrete TicketSourceAdapter implementations reside in adapters.integration.ticketsource",
          "Remediation: keep vendor TicketSourceAdapter implementations (LinearMockAdapter/LinearRealAdapter and future kinds) under org.dradgo.adapters.integration.ticketsource.{kind} (story 3.32 AC7).",
          classes()
              .that()
              .areAssignableTo(TicketSourceAdapter.class)
              .and()
              .areNotInterfaces()
              .should()
              .resideInAPackage("org.dradgo.adapters.integration.ticketsource.."));

  static final ArchRule REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT =
      namedRule(
          "host-specific REST/SDK types must not leak through the application.integration.repohost port",
          "Remediation: keep GitHub REST DTOs, the org.kohsuke.github SDK, and HTTP-client surface inside adapters.integration.repohost.{kind}; the application port "
              + "may only depend on neutral domain-shaped records (Repository, PullRequest, Branch, RepositoryRef, PullRequestRef, CommentResult, RepositoryHostCapabilities). Story 3.13 AC1/AC11 / story 3.33 AC7 invariant.",
          noClasses()
              .that()
              .resideInAPackage("org.dradgo.application.integration.repohost..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "org.kohsuke.github..",
                  "com.github..",
                  "org.springframework.web.client..",
                  "org.springframework.http.client.."));

  static final ArchRule REPOSITORY_HOST_ADAPTER_PORT_RESIDES_IN_APPLICATION =
      namedRule(
          "the RepositoryHostAdapter port resides in application.integration.repohost",
          "Remediation: keep the vendor-neutral RepositoryHostAdapter port (story 3.33 AC7) in org.dradgo.application.integration.repohost; host implementations live under adapters.integration.repohost.{kind}.",
          classes()
              .that()
              .areAssignableTo(RepositoryHostAdapter.class)
              .and()
              .areInterfaces()
              .should()
              .resideInAPackage("org.dradgo.application.integration.repohost.."));

  static final ArchRule REPOSITORY_HOST_IMPLS_RESIDE_IN_ADAPTERS_REPOHOST =
      namedRule(
          "concrete RepositoryHostAdapter implementations reside in adapters.integration.repohost",
          "Remediation: keep host RepositoryHostAdapter implementations (GitHubMockAdapter/GitHubRealAdapter and future kinds) under org.dradgo.adapters.integration.repohost.{kind} (story 3.33 AC7).",
          classes()
              .that()
              .areAssignableTo(RepositoryHostAdapter.class)
              .and()
              .areNotInterfaces()
              .should()
              .resideInAPackage("org.dradgo.adapters.integration.repohost.."));

  static final ArchRule REPOSITORY_WORKSPACE_SERVICE_SCOPE =
      namedRule(
          "RepositoryWorkspaceService (application.runner.workspace) must reach git only via the GitCommandPort SPI, never adapters",
          "Remediation: keep RepositoryWorkspaceService on the GitCommandPort SPI + GitHubAdapter port + RunnerSecretsService/RunnerWorkspaceStore/RedactionPolicyService; never import CliGitAdapter or any org.dradgo.adapters.. / git-library type. Story 3.9 AC13 / Trap T1 invariant.",
          noClasses()
              .that()
              .resideInAPackage(REPOSITORY_WORKSPACE_PACKAGE)
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage("org.dradgo.adapters..", "org.eclipse.jgit.."));

  static final ArchRule CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY =
      namedRule(
          "credential-detection regex catalogs and predicates may only live in application.security",
          "Remediation: delegate credential detection and redaction heuristics to application.security instead of reimplementing them in adapters or other layers."
              + " The runner-contracts module is exempt: RunnerContractValidator legitimately owns the runner-result schema's secret-pattern guard and cannot move into application.security without a circular module dependency.",
          classes()
              .that()
              .resideOutsideOfPackage(SECURITY_PACKAGE)
              .and()
              .resideOutsideOfPackage("org.dradgo.runnercontracts..")
              .should(notDeclareSensitiveDetectionArtifacts()));

  /**
   * Story 3.6 AC8 — raw (unredacted) runner stdout/stderr is referenced only transiently inside
   * {@code adapters.runner..} during capture. The sole sanctioned readers of the raw workspace log
   * streams are {@link RunnerWorkspaceStore#readRawStdoutForCapture(String)} / {@link
   * RunnerWorkspaceStore#readRawStderrForCapture(String)}, and only the Docker adapter (which hands
   * them straight to {@code RunnerLogCaptureService} for redaction before any persist) may call
   * them. No application/domain/other-adapter class may touch the raw streams; everywhere else
   * consumes the redacted {@code RunnerLogReference}/{@code CapturedLogs} produced via {@code
   * application.security}. This pairs with {@link
   * #CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY} (the redacted form is produced only
   * there).
   */
  static final ArchRule RAW_RUNNER_OUTPUT_READS_STAY_IN_RUNNER_ADAPTER =
      namedRule(
          "raw runner stdout/stderr may only be read inside adapters.runner during capture",
          "Remediation: the only sanctioned reader of RunnerWorkspaceStore.readRaw*ForCapture is the"
              + " Docker runner adapter, which redacts via application.security before any persist."
              + " Consume the redacted RunnerLogReference/CapturedLogs instead of the raw streams.",
          methods()
              .that()
              .areDeclaredIn(RunnerWorkspaceStore.class)
              .and()
              .haveNameMatching("readRaw(Stdout|Stderr)ForCapture")
              .should()
              .onlyBeCalled()
              .byClassesThat()
              .resideInAPackage(RUNNER_ADAPTER_PACKAGE));

  static final ArchRule RAW_RUNNER_OUTPUT_FIELDS_STAY_OUT_OF_APPLICATION =
      namedRule(
          "raw runner stdout/stderr fields may only be declared in adapters.runner",
          "Remediation: raw runner output must stay method-local. Do not add rawStdout/rawStderr/rawRunnerOutput fields outside the Docker adapter capture path.",
          fields()
              .that()
              .haveNameMatching("(?i).*(raw.*(stdout|stderr|runner.*output)|runner.*raw.*output).*")
              .should()
              .beDeclaredInClassesThat()
              .resideInAPackage(RUNNER_ADAPTER_PACKAGE)
              .allowEmptyShould(true));

  /**
   * Story 2.14 AC9 — backend-authoritative allowed-action derivation must live in exactly one place
   * (UX-DR12). The state×role → action-set matrix is encoded inside {@code
   * WorkflowInspectionService.getAllowedActions}; no other application service or transport adapter
   * may reference the {@link AllowedAction} enum constants. The {@code adapters.rest} layer is
   * exempted only for {@code AllowedActionsResponse} (and any nested records/helpers — {@code
   * AllowedActionsResponse$Inner}), which performs the wire mapping from the typed enum list to
   * {@code List<String>} via the static {@code from(...)} factory. Inner records of {@code
   * WorkflowInspectionService} (e.g. {@code AllowedActionsView}) are exempted via the same
   * inner-class regex {@code (\\$.*)?}.
   */
  /**
   * Story 3.1 AC6 + Task 5 — the Docker runner adapter must NEVER reach into the artifact
   * application slice. Artifact ingestion belongs to {@code RunnerBroker.onResult} (via {@code
   * ArtifactOperationService}); the adapter is a pure transport for the result bytes. Scope is
   * {@code adapters.runner..} only — the broker (in {@code application.runner}) is allowed to call
   * into {@code application.artifact} (story 3.1 trap T6).
   */
  static final ArchRule DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION =
      namedRule(
          "Docker runner adapter slice must not depend on application.artifact",
          "Remediation: keep DockerRunnerAdapter a transport (returns result bytes to the broker). All artifact ingest goes through RunnerBroker.onResult → ArtifactOperationService.",
          noClasses()
              .that()
              .resideInAPackage(RUNNER_ADAPTER_PACKAGE)
              .should()
              .dependOnClassesThat()
              .resideInAPackage("org.dradgo.application.artifact.."));

  /**
   * Story 3.1 trap T8 — Docker client types ({@code com.github.dockerjava.api.model.*} and
   * relatives) must stay confined to the {@code adapters.runner.docker..} subpackage where the
   * {@code DockerEngineGateway} wrapper lives. Any other class under {@code adapters.runner..} that
   * reaches into docker-java types breaks the boundary that lets us upgrade the Docker client
   * library without touching the rest of the slice.
   */
  static final ArchRule ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY =
      namedRule(
          "Docker-java model types must stay behind the adapters.runner.docker gateway",
          "Remediation: route Docker actions through DockerEngineGateway (project-owned CreateContainerSpec + ContainerState records). docker-java imports outside adapters.runner.docker.. break the trap-T8 boundary.",
          noClasses()
              .that()
              .resideInAPackage(RUNNER_ADAPTER_PACKAGE)
              .and()
              .resideOutsideOfPackage("org.dradgo.adapters.runner.docker..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage("com.github.dockerjava.."));

  /**
   * Story 3.5 AC10 — {@code RunnerSecretsService} (the only class that resolves agent-provider key
   * VALUES from the environment) may be depended on ONLY from the runner application slice ({@code
   * application.runner..} — the broker + scan service) and the runner adapter slice ({@code
   * adapters.runner..} — {@code DockerRunnerAdapter} resolves and injects). No controller, REST
   * adapter, or other application service may resolve secrets directly. (The epic text names {@code
   * adapters.runner.docker..}, but the actual consumer {@code DockerRunnerAdapter} lives in {@code
   * adapters.runner}, so the adapter-side allowance is the whole {@code adapters.runner..} slice;
   * docker-java types staying behind the gateway is covered separately by {@link
   * #ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY}.)
   */
  static final ArchRule RUNNER_SECRETS_SERVICE_SCOPE =
      namedRule(
          "RunnerSecretsService may only be depended on from application.runner or adapters.runner",
          "Remediation: resolve runner secrets only inside org.dradgo.application.runner.. (RunnerBroker / RunnerSecretScanService) or org.dradgo.adapters.runner.. (DockerRunnerAdapter). Any other caller — a controller, REST adapter, or unrelated application service — must NOT resolve provider keys directly (story 3.5 AC10).",
          noClasses()
              .that()
              .resideOutsideOfPackage("org.dradgo.application.runner..")
              .and()
              .resideOutsideOfPackage("org.dradgo.adapters.runner..")
              .should()
              .dependOnClassesThat()
              .haveFullyQualifiedName("org.dradgo.application.runner.RunnerSecretsService"));

  static final ArchRule ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE =
      namedRule(
          "AllowedAction enum constants may only be referenced from WorkflowInspectionService (application) and AllowedActionsResponse (adapters.rest)",
          "Remediation: route all state×role → action-set derivation through WorkflowInspectionService.getAllowedActions (story 2.14 AC9). Only AllowedActionsResponse may translate the typed enum list to the wire-string list.",
          noClasses()
              .that()
              .resideInAnyPackage(APPLICATION_PACKAGE, ADAPTERS_PACKAGE)
              .and()
              .haveNameNotMatching(
                  "org\\.dradgo\\.application\\.workflow\\.WorkflowInspectionService(\\$.*)?")
              .and()
              .haveNameNotMatching(
                  "org\\.dradgo\\.adapters\\.rest\\.AllowedActionsResponse(\\$.*)?")
              .should()
              .dependOnClassesThat()
              .haveFullyQualifiedName(AllowedAction.class.getName()));

  /**
   * Story 3.19 (AC10) — the runner-queue typed views ({@code
   * WorkflowInspectionService.RunnerQueueStatus} + {@code .WorkerStatus}, nested in {@code
   * application.workflow}) may be referenced ONLY from {@code WorkflowInspectionService} (the
   * producer), the REST {@code RunnerQueueStatusResponse} translator, and the CLI {@code workers
   * status} surface ({@code WorkerCommands} + the {@code WorkflowCommandOutputs} renderer). Mirror
   * of {@link #ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE}. Enforces "all
   * queue-status reading goes through {@code getRunnerQueueStatus}": the Prometheus exporter
   * deliberately reads via the {@code RunnerExecutionRecordPort} read port (AC10 permits "service
   * OR read port") and so never references these view types — keeping it OUT of the allow-list
   * below.
   *
   * <p>The CLI consumer + renderer are in the allow-list (unlike the AllowedAction mirror, where
   * the CLI consumes the already-translated REST response): the {@code workers status} command
   * calls the inspection service directly and the typed view is its return type, exactly as {@code
   * WorkflowCommandOutputs} already references {@code WorkflowStatusView}/{@code
   * WorkflowHistoryView}.
   */
  static final ArchRule RUNNER_QUEUE_STATUS_VIEWS_REFERENCED_ONLY_BY_INSPECTION_AND_TRANSPORTS =
      namedRule(
          "RunnerQueueStatus / WorkerStatus may only be referenced from WorkflowInspectionService (application), RunnerQueueStatusResponse (adapters.rest), and the CLI workers-status surface (WorkerCommands + WorkflowCommandOutputs)",
          "Remediation: route all runner-queue-status reading through WorkflowInspectionService.getRunnerQueueStatus (story 3.19 AC10). Prometheus/other readers must use the RunnerExecutionRecordPort read port, not the typed view; only the REST response translates the typed view to the wire shape.",
          noClasses()
              .that()
              .resideInAnyPackage(APPLICATION_PACKAGE, ADAPTERS_PACKAGE)
              .and()
              .haveNameNotMatching(
                  "org\\.dradgo\\.application\\.workflow\\.WorkflowInspectionService(\\$.*)?")
              .and()
              .haveNameNotMatching(
                  "org\\.dradgo\\.adapters\\.rest\\.RunnerQueueStatusResponse(\\$.*)?")
              .and()
              .haveNameNotMatching("org\\.dradgo\\.adapters\\.cli\\.WorkerCommands(\\$.*)?")
              .and()
              .haveNameNotMatching("org\\.dradgo\\.adapters\\.cli\\.WorkflowCommandOutputs(\\$.*)?")
              .should()
              .dependOnClassesThat()
              .haveNameMatching(
                  "org\\.dradgo\\.application\\.workflow\\.WorkflowInspectionService\\$(RunnerQueueStatus|WorkerStatus)"));

  /**
   * Story 4.1 (AC9) — the operator fleet views ({@code
   * WorkflowInspectionService.OperatorRunSummary} + {@code .OperatorRunRow}, nested in {@code
   * application.workflow}) may be referenced ONLY from {@code WorkflowInspectionService} (the
   * producer), the CLI {@code OperatorCommands} consumer, and the {@code WorkflowCommandOutputs}
   * renderer. Mirror of {@link
   * #RUNNER_QUEUE_STATUS_VIEWS_REFERENCED_ONLY_BY_INSPECTION_AND_TRANSPORTS}. Keeps the {@code
   * operator status} read on the single {@code getOperatorRunSummary} seam and the SPI-layer
   * snapshots ({@code OperatorRunAggregate}/{@code OperatorRunRowSnapshot}) — which stay in {@code
   * application.workflow.spi} — out of the CLI (story 4.1 Reconciliation 2). Story 4.2 adds the
   * REST translator ({@code OperatorController} + {@code OperatorRunSummaryResponse}/{@code
   * OperatorRunRowResponse}), so the allow-list is widened to admit those {@code adapters.rest}
   * classes (mirroring the 3.19 runner-queue-status shape). The SPI-layer snapshots ({@code
   * OperatorRunAggregate}/{@code OperatorRunRowSnapshot}) stay out of both the CLI and REST.
   */
  static final ArchRule OPERATOR_RUN_VIEWS_REFERENCED_ONLY_BY_INSPECTION_AND_CLI =
      namedRule(
          "OperatorRunSummary / OperatorRunRow may only be referenced from WorkflowInspectionService (application), the CLI operator-status surface (OperatorCommands + WorkflowCommandOutputs), and the REST operator surface (OperatorController + OperatorRunSummaryResponse + OperatorRunRowResponse)",
          "Remediation: route all operator fleet-view reading through WorkflowInspectionService.getOperatorRunSummary (story 4.1 AC9). The CLI/REST must not reach the SPI snapshots (OperatorRunAggregate/OperatorRunRowSnapshot) — those stay in application.workflow.spi.",
          noClasses()
              .that()
              .resideInAnyPackage(APPLICATION_PACKAGE, ADAPTERS_PACKAGE)
              .and()
              .haveNameNotMatching(
                  "org\\.dradgo\\.application\\.workflow\\.WorkflowInspectionService(\\$.*)?")
              .and()
              .haveNameNotMatching("org\\.dradgo\\.adapters\\.cli\\.OperatorCommands(\\$.*)?")
              .and()
              .haveNameNotMatching("org\\.dradgo\\.adapters\\.cli\\.WorkflowCommandOutputs(\\$.*)?")
              .and()
              .haveNameNotMatching("org\\.dradgo\\.adapters\\.rest\\.OperatorController(\\$.*)?")
              .and()
              .haveNameNotMatching(
                  "org\\.dradgo\\.adapters\\.rest\\.OperatorRunSummaryResponse(\\$.*)?")
              .and()
              .haveNameNotMatching(
                  "org\\.dradgo\\.adapters\\.rest\\.OperatorRunRowResponse(\\$.*)?")
              .should()
              .dependOnClassesThat()
              .haveNameMatching(
                  "org\\.dradgo\\.application\\.workflow\\.WorkflowInspectionService\\$(OperatorRunSummary|OperatorRunRow)"));

  /**
   * Story 4.3 (AC11) — the audit query result records ({@code AuditQueryService.AuditQueryResult} +
   * {@code .AuditEventRow}, nested in {@code application.audit}) may be referenced ONLY from the
   * producer service, the CLI {@code AuditCommands} + {@code WorkflowCommandOutputs} renderer, and
   * the REST {@code AuditController} + {@code AuditQueryResponse}. Mirror of {@link
   * #OPERATOR_RUN_VIEWS_REFERENCED_ONLY_BY_INSPECTION_AND_CLI}. Keeps the audit read on the single
   * {@code AuditQueryService} seam and the SPI-layer snapshots ({@code
   * AuditEventPageSnapshot}/{@code AuditEventRowSnapshot}) — which stay in {@code
   * application.audit.spi} — out of the CLI/REST (story 4.3 Reconciliation 13). {@code
   * AuditQueryFilter} is the un-restricted input record (the adapters build it), so it is
   * intentionally NOT part of this rule.
   */
  static final ArchRule AUDIT_QUERY_RESULT_VIEWS_REFERENCED_ONLY_BY_SERVICE_CLI_REST =
      namedRule(
          "AuditQueryResult / AuditEventRow may only be referenced from AuditQueryService (application), the CLI audit surface (AuditCommands + WorkflowCommandOutputs), and the REST audit surface (AuditController + AuditQueryResponse)",
          "Remediation: route all audit-history reading through AuditQueryService.queryByRun/queryByTicket (story 4.3 AC11). The CLI/REST must not reach the SPI snapshots (AuditEventPageSnapshot/AuditEventRowSnapshot) — those stay in application.audit.spi.",
          noClasses()
              .that()
              .resideInAnyPackage(APPLICATION_PACKAGE, ADAPTERS_PACKAGE)
              .and()
              .haveNameNotMatching("org\\.dradgo\\.application\\.audit\\.AuditQueryService(\\$.*)?")
              .and()
              .haveNameNotMatching("org\\.dradgo\\.adapters\\.cli\\.AuditCommands(\\$.*)?")
              .and()
              .haveNameNotMatching("org\\.dradgo\\.adapters\\.cli\\.WorkflowCommandOutputs(\\$.*)?")
              .and()
              .haveNameNotMatching("org\\.dradgo\\.adapters\\.rest\\.AuditController(\\$.*)?")
              .and()
              .haveNameNotMatching("org\\.dradgo\\.adapters\\.rest\\.AuditQueryResponse(\\$.*)?")
              .should()
              .dependOnClassesThat()
              .haveNameMatching(
                  "org\\.dradgo\\.application\\.audit\\.AuditQueryService\\$(AuditQueryResult|AuditEventRow)"));

  /**
   * Story 4.3 (AC11 / Reconciliation 13) — the audit read-seam SPI snapshots ({@code
   * AuditEventPageSnapshot}/{@code AuditEventRowSnapshot}/{@code AuditEventQuery}, in {@code
   * application.audit.spi}) are persistence-facing; the CLI/REST transports must consume the
   * redacted {@code AuditQueryResult}/{@code AuditEventRow} from {@code AuditQueryService} instead.
   * Mirrors the intent of {@link
   * #REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER} but scoped to the audit SPI
   * and extended to CLI adapters.
   */
  static final ArchRule AUDIT_SPI_SNAPSHOTS_NOT_IMPORTED_BY_ADAPTERS =
      namedRule(
          "adapters.cli / adapters.rest must not depend on application.audit.spi snapshots",
          "Remediation: consume the redacted AuditQueryResult/AuditEventRow from AuditQueryService; the SPI snapshots stay behind the read seam (story 4.3 Reconciliation 13).",
          noClasses()
              .that()
              .resideInAnyPackage(CLI_ADAPTER_PACKAGE, REST_ADAPTER_PACKAGE)
              .should()
              .dependOnClassesThat()
              .resideInAPackage("org.dradgo.application.audit.spi.."));

  private ArchitectureRuleCatalog() {}

  private static ArchRule namedRule(String name, String remediationHint, ArchRule rule) {
    return rule.as(name).because(remediationHint);
  }

  private static ArchCondition<JavaClass> haveSimpleNameEndingWithAny(String... suffixes) {
    String description = "have simple name ending with " + String.join(" or ", suffixes);
    return new ArchCondition<>(description) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        String simpleName = item.getSimpleName();
        boolean matches = java.util.Arrays.stream(suffixes).anyMatch(simpleName::endsWith);
        if (!matches) {
          events.add(SimpleConditionEvent.violated(item, item.getName() + " must " + description));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> bePresent() {
    return new ArchCondition<>("be present") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        events.add(SimpleConditionEvent.satisfied(item, item.getName() + " is present"));
      }
    };
  }

  private static ArchCondition<JavaClass> exposeOnlyPublicMethods(String... allowed) {
    Set<String> allowedSet = new LinkedHashSet<>(Arrays.asList(allowed));
    String description = "expose only the public methods " + allowedSet;
    return new ArchCondition<>(description) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        Collection<JavaMethod> methods = item.getMethods();
        for (JavaMethod method : methods) {
          if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
            continue;
          }
          String methodName = method.getName();
          if ("<init>".equals(methodName)) {
            continue;
          }
          if (method.getOwner().getName().equals("java.lang.Object")) {
            continue;
          }
          if (!allowedSet.contains(methodName)) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    item.getName()
                        + " exposes disallowed public method '"
                        + methodName
                        + "'; allowed="
                        + allowedSet
                        + " (Epic-4 scope creep — see Javadoc on RecoveryService)"));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass> exposeOnlyPublicMethodSignatures(String... allowed) {
    Set<String> allowedSet = new LinkedHashSet<>(Arrays.asList(allowed));
    String description = "expose only the public method signatures " + allowedSet;
    return new ArchCondition<>(description) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        Collection<JavaMethod> methods = item.getMethods();
        for (JavaMethod method : methods) {
          if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
            continue;
          }
          String methodName = method.getName();
          if ("<init>".equals(methodName)) {
            continue;
          }
          if (method.getOwner().getName().equals("java.lang.Object")) {
            continue;
          }
          String actualSignature = methodSignature(method);
          if (!allowedSet.contains(actualSignature)) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    item.getName()
                        + " exposes disallowed public signature '"
                        + actualSignature
                        + "'; allowed="
                        + allowedSet
                        + " (Epic-4 scope creep — see Javadoc on RecoveryService)"));
          }
        }
      }
    };
  }

  private static String methodSignature(String methodName, Class<?>... parameterTypes) {
    StringBuilder out = new StringBuilder(methodName).append('(');
    for (int index = 0; index < parameterTypes.length; index++) {
      if (index > 0) {
        out.append(',');
      }
      out.append(parameterTypes[index].getName());
    }
    return out.append(')').toString();
  }

  private static String methodSignature(JavaMethod method) {
    StringBuilder out = new StringBuilder(method.getName()).append('(');
    List<JavaClass> parameterTypes = method.getRawParameterTypes();
    for (int index = 0; index < parameterTypes.size(); index++) {
      if (index > 0) {
        out.append(',');
      }
      out.append(parameterTypes.get(index).getName());
    }
    return out.append(')').toString();
  }

  private static ArchCondition<JavaClass> notDeclareSensitiveDetectionArtifacts() {
    return new ArchCondition<>(
        "avoid local credential-detection regex catalogs or predicate helpers") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaField field : item.getFields()) {
          boolean patternField = field.getRawType().getName().equals(Pattern.class.getName());
          if (patternField && SENSITIVE_PATTERN_NAME.matcher(field.getName()).matches()) {
            events.add(
                SimpleConditionEvent.violated(
                    field,
                    item.getName()
                        + " declares sensitive regex field "
                        + field.getName()
                        + " outside application.security"));
          }
        }
        for (JavaMethod method : item.getMethods()) {
          String normalized = method.getName().toLowerCase(Locale.ROOT);
          boolean sensitivePredicate =
              SENSITIVE_PREDICATE_NAMES.stream()
                  .map(name -> name.toLowerCase(Locale.ROOT))
                  .anyMatch(normalized::equals);
          if (sensitivePredicate) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    item.getName()
                        + " declares credential-detection helper "
                        + method.getName()
                        + " outside application.security"));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass> notCallFutureArtifactWriteMethods() {
    return new ArchCondition<>("avoid direct calls to future artifact write implementations") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        item.getMethodCallsFromSelf()
            .forEach(
                call -> {
                  String targetOwner = call.getTarget().getOwner().getName();
                  String targetName = call.getName();
                  if (targetOwner.endsWith("LocalArtifactStore") && targetName.equals("write")) {
                    events.add(
                        SimpleConditionEvent.violated(
                            call,
                            item.getName() + " calls " + targetOwner + ".write(..) directly"));
                  }
                });
      }
    };
  }
}
