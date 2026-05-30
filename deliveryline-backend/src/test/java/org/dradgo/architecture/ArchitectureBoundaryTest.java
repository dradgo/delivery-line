package org.dradgo.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;

@Tag(ArchitectureRuleCatalog.ARCHITECTURE_TAG)
@AnalyzeClasses(
    packages = "org.dradgo",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureBoundaryTest {

  @ArchTest static final ArchRule layered_boundaries = ArchitectureRuleCatalog.LAYERED_BOUNDARIES;

  @ArchTest
  static final ArchRule adapter_package_layout = ArchitectureRuleCatalog.ADAPTER_PACKAGE_LAYOUT;

  @ArchTest
  static final ArchRule domain_package_must_exist =
      ArchitectureRuleCatalog.DOMAIN_PACKAGE_MUST_EXIST;

  @ArchTest
  static final ArchRule application_package_must_exist =
      ArchitectureRuleCatalog.APPLICATION_PACKAGE_MUST_EXIST;

  @ArchTest
  static final ArchRule adapters_package_must_exist =
      ArchitectureRuleCatalog.ADAPTERS_PACKAGE_MUST_EXIST;

  @ArchTest
  static final ArchRule adapter_slices_must_be_free_of_cycles =
      ArchitectureRuleCatalog.ADAPTER_SLICES_MUST_BE_FREE_OF_CYCLES;

  @ArchTest
  static final ArchRule adapter_slices_must_not_depend_on_each_other =
      ArchitectureRuleCatalog.ADAPTER_SLICES_MUST_NOT_DEPEND_ON_EACH_OTHER;

  @ArchTest
  static final ArchRule domain_must_be_framework_free =
      ArchitectureRuleCatalog.DOMAIN_MUST_BE_FRAMEWORK_FREE;

  @ArchTest
  static final ArchRule application_and_domain_must_not_depend_on_persistence_types =
      ArchitectureRuleCatalog.APPLICATION_AND_DOMAIN_MUST_NOT_DEPEND_ON_PERSISTENCE_TYPES;

  @ArchTest
  static final ArchRule application_must_not_depend_on_infrastructure =
      ArchitectureRuleCatalog.APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE;

  @ArchTest
  static final ArchRule rest_and_cli_adapters_must_not_touch_persistence_or_external_adapters =
      ArchitectureRuleCatalog.REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_PERSISTENCE_OR_EXTERNAL_ADAPTERS;

  @ArchTest
  static final ArchRule rest_and_cli_adapters_must_not_touch_jpa_entities =
      ArchitectureRuleCatalog.REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_JPA_ENTITIES;

  @ArchTest
  static final ArchRule rest_controllers_stay_thin_and_avoid_spi_or_persistence_or_runner =
      ArchitectureRuleCatalog.REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER;

  @ArchTest
  static final ArchRule rest_controllers_must_be_named_as_controllers =
      ArchitectureRuleCatalog.REST_CONTROLLERS_MUST_BE_NAMED_AS_CONTROLLERS;

  @ArchTest
  static final ArchRule rest_controller_suffix_requires_rest_controller_annotation =
      ArchitectureRuleCatalog.REST_CONTROLLER_SUFFIX_REQUIRES_REST_CONTROLLER_ANNOTATION;

  @ArchTest
  static final ArchRule spring_shell_commands_must_be_pluralized =
      ArchitectureRuleCatalog.SPRING_SHELL_COMMANDS_MUST_BE_PLURALIZED;

  @ArchTest
  static final ArchRule shell_commands_suffix_requires_command_group_annotation =
      ArchitectureRuleCatalog.SHELL_COMMANDS_SUFFIX_REQUIRES_COMMAND_GROUP_ANNOTATION;

  @ArchTest
  static final ArchRule application_command_dtos_must_be_singular =
      ArchitectureRuleCatalog.APPLICATION_COMMAND_DTOS_MUST_BE_SINGULAR;

  @ArchTest
  static final ArchRule persistence_entities_must_be_named_as_entities =
      ArchitectureRuleCatalog.PERSISTENCE_ENTITIES_MUST_BE_NAMED_AS_ENTITIES;

  @ArchTest
  static final ArchRule persistence_mappers_must_be_location_qualified =
      ArchitectureRuleCatalog.PERSISTENCE_MAPPERS_MUST_BE_LOCATION_QUALIFIED;

  @ArchTest
  static final ArchRule application_services_must_be_named_as_services =
      ArchitectureRuleCatalog.APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES;

  @ArchTest
  static final ArchRule application_results_must_use_result_or_outcome_suffix =
      ArchitectureRuleCatalog.APPLICATION_RESULTS_MUST_USE_RESULT_OR_OUTCOME_SUFFIX;

  @ArchTest
  static final ArchRule workflow_state_changes_must_go_through_transition_service =
      ArchitectureRuleCatalog.ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE;

  @ArchTest
  static final ArchRule credential_detection_must_stay_in_application_security =
      ArchitectureRuleCatalog.CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY;

  @ArchTest
  static final ArchRule artifact_writes_must_go_through_artifact_operation_service =
      ArchitectureRuleCatalog.ARTIFACT_WRITES_MUST_GO_THROUGH_ARTIFACT_OPERATION_SERVICE;

  @ArchTest
  static final ArchRule linear_types_must_not_leak_through_port =
      ArchitectureRuleCatalog.LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT;

  @ArchTest
  static final ArchRule recovery_service_is_scope_protected =
      ArchitectureRuleCatalog.RECOVERY_SERVICE_IS_SCOPE_PROTECTED;

  @ArchTest
  static final ArchRule specification_artifact_projection_lives_in_application_artifact =
      ArchitectureRuleCatalog.SPECIFICATION_ARTIFACT_PROJECTION_LIVES_IN_APPLICATION_ARTIFACT;

  @ArchTest
  static final ArchRule approval_service_lives_in_application_approval =
      ArchitectureRuleCatalog.APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL;

  @ArchTest
  static final ArchRule clarification_service_lives_in_application_clarification =
      ArchitectureRuleCatalog.CLARIFICATION_SERVICE_LIVES_IN_APPLICATION_CLARIFICATION;

  @ArchTest
  static final ArchRule clarification_lifecycle_lives_in_application_clarification =
      ArchitectureRuleCatalog.CLARIFICATION_LIFECYCLE_LIVES_IN_APPLICATION_CLARIFICATION;

  @ArchTest
  static final ArchRule allowed_action_derivation_lives_only_in_workflow_inspection_service =
      ArchitectureRuleCatalog.ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE;

  @ArchTest
  static final ArchRule docker_runner_adapter_must_not_depend_on_artifact_application =
      ArchitectureRuleCatalog.DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION;

  @ArchTest
  static final ArchRule adapters_runner_docker_types_stay_behind_gateway =
      ArchitectureRuleCatalog.ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY;

  @ArchTest
  static final ArchRule runner_secrets_service_scope =
      ArchitectureRuleCatalog.RUNNER_SECRETS_SERVICE_SCOPE;
}
