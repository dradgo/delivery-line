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
import org.dradgo.application.integration.linear.GovernedRunComment;
import org.dradgo.application.integration.linear.LinearAdapter;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.workflow.DomainResult;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.commands.WorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowRunStatePort;
import org.dradgo.domain.registry.AllowedAction;
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
          "Remediation: move adapter code under adapters.cli, adapters.rest, adapters.persistence, adapters.files, adapters.runner, adapters.integration, adapters.diagnostics, or adapters.git.",
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
                  GIT_ADAPTER_PACKAGE));

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
   * Story 3.16 (AC9) — Linear is intake + completion-sync only: the merge-ready write-back is the
   * sole path that posts to Linear. {@code LinearAdapter.postGovernedRunComment} may be called only
   * from {@link WorkflowOrchestrationService} (its {@code syncCompletionToLinear} is the composer)
   * and the {@code WorkflowCommands} CLI ({@code sync-completion} manual retry) — runner CLIs and
   * every other component are forbidden, preserving the narrow boundary. (In practice the CLI calls
   * {@code syncCompletionToLinear}, not {@code postGovernedRunComment} directly, so {@code
   * WorkflowOrchestrationService} is the only actual caller; the CLI is allow-listed per AC9 so a
   * future direct call from {@code sync-completion} would not trip the rule.)
   */
  static final ArchRule ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT =
      namedRule(
          "only WorkflowOrchestrationService and the sync-completion CLI may post a Linear comment",
          "Remediation: route any Linear write-back through WorkflowOrchestrationService.syncCompletionToLinear (story 3.16 AC9). Runner CLIs and other components must never call LinearAdapter.postGovernedRunComment — Linear stays intake + completion-sync only.",
          noClasses()
              .that()
              .doNotHaveFullyQualifiedName(WorkflowOrchestrationService.class.getName())
              .and()
              .doNotHaveFullyQualifiedName("org.dradgo.adapters.cli.WorkflowCommands")
              .should()
              .callMethod(
                  LinearAdapter.class,
                  "postGovernedRunComment",
                  String.class,
                  GovernedRunComment.class));

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
          "Remediation: keep RunnerExecutionQueue + QueuedRunnerExecution in org.dradgo.application.runner.queue (story 3.17a AC6). The queue enqueues/dequeues via application-owned SPI ports + domain types — a JPA entity import or any adapters/infrastructure import would re-introduce the cross-layer dependency LAYERED_BOUNDARIES forbids (Trap T11).",
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
          "RecoveryService must expose only the Epic-1 baseline public method signatures",
          "Remediation: Epic 4 will add resume/rerun/reconcile/pause/classifyFailure/takeover. "
              + "Changing RecoveryService's public surface without an Epic-4 story is a scope "
              + "violation — update the story and this guard together.",
          classes()
              .that()
              .haveFullyQualifiedName("org.dradgo.application.recovery.RecoveryService")
              .should(
                  exposeOnlyPublicMethodSignatures(
                      methodSignature(
                          "retry", String.class, String.class, ActorContext.class, String.class),
                      methodSignature("describeFailure", String.class))));

  static final ArchRule LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT =
      namedRule(
          "Linear-specific GraphQL types must not leak through the application.integration.linear port",
          "Remediation: keep Linear GraphQL DTOs, Linear SDK types, and HTTP-client surface inside adapters.integration.linear; the application port "
              + "may only depend on domain-shaped records (LinearTicket, GovernedRunComment, IntegrationLink). Story 1.14 AC1 invariant.",
          noClasses()
              .that()
              .resideInAPackage("org.dradgo.application.integration..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "com.linear..",
                  "linear.api..",
                  "org.springframework.web.client..",
                  "org.springframework.http.client.."));

  static final ArchRule GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT =
      namedRule(
          "GitHub-specific REST/SDK types must not leak through the application.integration.github port",
          "Remediation: keep GitHub REST DTOs, the org.kohsuke.github SDK, and HTTP-client surface inside adapters.integration.github; the application port "
              + "may only depend on domain-shaped records (GitHubRepository, GitHubPullRequest, GitHubBranch). Story 3.13 AC1/AC11 invariant.",
          noClasses()
              .that()
              .resideInAPackage("org.dradgo.application.integration..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "org.kohsuke.github..",
                  "com.github..",
                  "org.springframework.web.client..",
                  "org.springframework.http.client.."));

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
