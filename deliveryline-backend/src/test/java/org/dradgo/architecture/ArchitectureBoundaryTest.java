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

  // Story 3a-1 (AC9) — only WorkflowOrchestrationService auto-advances on a spec runner success.
  @ArchTest
  static final ArchRule only_orchestration_auto_advances_on_spec_runner_success =
      ArchitectureRuleCatalog.ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_SPEC_RUNNER_SUCCESS;

  // Story 3.17b (AC3b) — only orchestration/recovery/worker-pool may enqueue runner executions.
  @ArchTest
  static final ArchRule only_orchestration_recovery_and_worker_pool_may_enqueue =
      ArchitectureRuleCatalog.ONLY_ORCHESTRATION_RECOVERY_AND_WORKER_POOL_MAY_ENQUEUE;

  // Story 3.17b (AC3b) — only the worker pool may dequeue a leased runner execution.
  @ArchTest
  static final ArchRule only_worker_pool_may_dequeue =
      ArchitectureRuleCatalog.ONLY_WORKER_POOL_MAY_DEQUEUE;

  // Story 3.17b (AC3) — only the worker pool may run the relocated queued-dispatch body.
  @ArchTest
  static final ArchRule only_worker_pool_may_run_queued_dispatch =
      ArchitectureRuleCatalog.ONLY_WORKER_POOL_MAY_RUN_QUEUED_DISPATCH;

  // Story 3.17b review (D4) — no production class may call the deprecated synchronous dispatch
  // path.
  @ArchTest
  static final ArchRule no_production_caller_may_invoke_legacy_synchronous_dispatch =
      ArchitectureRuleCatalog.NO_PRODUCTION_CALLER_MAY_INVOKE_LEGACY_SYNCHRONOUS_DISPATCH;

  // Story 3.11 (AC9) — only WorkflowOrchestrationService auto-advances on a plan runner success.
  @ArchTest
  static final ArchRule only_orchestration_auto_advances_on_plan_runner_success =
      ArchitectureRuleCatalog.ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_PLAN_RUNNER_SUCCESS;

  // Story 3.12 (AC5) — only WorkflowOrchestrationService auto-advances on a pr-output runner
  // success.
  @ArchTest
  static final ArchRule only_orchestration_auto_advances_on_pr_output_runner_success =
      ArchitectureRuleCatalog.ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_PR_OUTPUT_RUNNER_SUCCESS;

  // Story 3.16 (AC9) — only WorkflowOrchestrationService + the sync-completion CLI may post a
  // Linear comment (Linear stays intake + completion-sync only).
  @ArchTest
  static final ArchRule only_orchestration_and_cli_may_post_linear_comment =
      ArchitectureRuleCatalog.ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT;

  @ArchTest
  static final ArchRule only_subticket_service_may_create_source_subticket =
      ArchitectureRuleCatalog.ONLY_SUBTICKET_SERVICE_MAY_CREATE_SOURCE_SUBTICKET;

  @ArchTest
  static final ArchRule credential_detection_must_stay_in_application_security =
      ArchitectureRuleCatalog.CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY;

  @ArchTest
  static final ArchRule raw_runner_output_reads_stay_in_runner_adapter =
      ArchitectureRuleCatalog.RAW_RUNNER_OUTPUT_READS_STAY_IN_RUNNER_ADAPTER;

  @ArchTest
  static final ArchRule raw_runner_output_fields_stay_out_of_application =
      ArchitectureRuleCatalog.RAW_RUNNER_OUTPUT_FIELDS_STAY_OUT_OF_APPLICATION;

  @ArchTest
  static final ArchRule artifact_writes_must_go_through_artifact_operation_service =
      ArchitectureRuleCatalog.ARTIFACT_WRITES_MUST_GO_THROUGH_ARTIFACT_OPERATION_SERVICE;

  @ArchTest
  static final ArchRule ticket_source_types_must_not_leak_through_port =
      ArchitectureRuleCatalog.TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT;

  @ArchTest
  static final ArchRule ticket_source_adapter_port_resides_in_application =
      ArchitectureRuleCatalog.TICKET_SOURCE_ADAPTER_PORT_RESIDES_IN_APPLICATION;

  @ArchTest
  static final ArchRule ticket_source_impls_reside_in_adapters_ticketsource =
      ArchitectureRuleCatalog.TICKET_SOURCE_IMPLS_RESIDE_IN_ADAPTERS_TICKETSOURCE;

  @ArchTest
  static final ArchRule repohost_types_must_not_leak_through_port =
      ArchitectureRuleCatalog.REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT;

  @ArchTest
  static final ArchRule repository_host_adapter_port_resides_in_application =
      ArchitectureRuleCatalog.REPOSITORY_HOST_ADAPTER_PORT_RESIDES_IN_APPLICATION;

  @ArchTest
  static final ArchRule repository_host_impls_reside_in_adapters_repohost =
      ArchitectureRuleCatalog.REPOSITORY_HOST_IMPLS_RESIDE_IN_ADAPTERS_REPOHOST;

  @ArchTest
  static final ArchRule repository_workspace_service_scope =
      ArchitectureRuleCatalog.REPOSITORY_WORKSPACE_SERVICE_SCOPE;

  // Story 4.28 lifted RECOVERY_SERVICE_IS_SCOPE_PROTECTED (governed by
  // docs/adr/0033-recovery-service-scope-lift.md). Its @ArchTest registration was removed here;
  // RecoveryServiceScopeLiftMetaTest guards that neither the rule nor this field returns. The
  // sibling developer_takeover registration below stays (AC8).

  @ArchTest
  static final ArchRule developer_takeover_service_is_scope_protected =
      ArchitectureRuleCatalog.DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED;

  @ArchTest
  static final ArchRule specification_artifact_projection_lives_in_application_artifact =
      ArchitectureRuleCatalog.SPECIFICATION_ARTIFACT_PROJECTION_LIVES_IN_APPLICATION_ARTIFACT;

  @ArchTest
  static final ArchRule approval_service_lives_in_application_approval =
      ArchitectureRuleCatalog.APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL;

  @ArchTest
  static final ArchRule technical_approval_service_lives_in_application_approval =
      ArchitectureRuleCatalog.TECHNICAL_APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL;

  // Story 3.18 (AC10) — batch submission service stays in application.workflow, no adapter deps.
  @ArchTest
  static final ArchRule batch_submission_service_lives_in_application_workflow =
      ArchitectureRuleCatalog.BATCH_SUBMISSION_SERVICE_LIVES_IN_APPLICATION_WORKFLOW;

  @ArchTest
  static final ArchRule clarification_service_lives_in_application_clarification =
      ArchitectureRuleCatalog.CLARIFICATION_SERVICE_LIVES_IN_APPLICATION_CLARIFICATION;

  // Story 3.17a (AC6) — RunnerExecutionQueue substrate stays in application.runner.queue.
  @ArchTest
  static final ArchRule runner_execution_queue_lives_in_application_runner_queue =
      ArchitectureRuleCatalog.RUNNER_EXECUTION_QUEUE_LIVES_IN_APPLICATION_RUNNER_QUEUE;

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

  @ArchTest
  static final ArchRule runner_queue_status_views_referenced_only_by_inspection_and_transports =
      ArchitectureRuleCatalog
          .RUNNER_QUEUE_STATUS_VIEWS_REFERENCED_ONLY_BY_INSPECTION_AND_TRANSPORTS;

  @ArchTest
  static final ArchRule operator_run_views_referenced_only_by_inspection_and_cli =
      ArchitectureRuleCatalog.OPERATOR_RUN_VIEWS_REFERENCED_ONLY_BY_INSPECTION_AND_CLI;

  @ArchTest
  static final ArchRule audit_query_result_views_referenced_only_by_service_cli_rest =
      ArchitectureRuleCatalog.AUDIT_QUERY_RESULT_VIEWS_REFERENCED_ONLY_BY_SERVICE_CLI_REST;

  @ArchTest
  static final ArchRule audit_spi_snapshots_not_imported_by_adapters =
      ArchitectureRuleCatalog.AUDIT_SPI_SNAPSHOTS_NOT_IMPORTED_BY_ADAPTERS;

  @ArchTest
  static final ArchRule only_conflict_package_may_write_integration_conflicts =
      ArchitectureRuleCatalog.ONLY_CONFLICT_PACKAGE_MAY_WRITE_INTEGRATION_CONFLICTS;
}
