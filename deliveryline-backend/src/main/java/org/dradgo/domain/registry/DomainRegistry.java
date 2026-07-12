package org.dradgo.domain.registry;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.dradgo.domain.id.PublicIdPrefixes;

/**
 * Authoritative catalog of every registry-backed value set. Every accessor returns an unmodifiable
 * view over a snapshot taken at class init; callers cannot mutate the static state.
 */
public final class DomainRegistry {

  private static final Set<String> WORKFLOW_STATES = valuesOf(WorkflowState.values());
  private static final Set<String> ACTOR_TYPES = valuesOf(ActorType.values());
  private static final Set<String> ALLOWED_ACTIONS = valuesOf(AllowedAction.values());
  private static final Set<String> DOMAIN_ERROR_CODES = valuesOf(DomainErrorCode.values());
  private static final Set<String> RUNNER_EXECUTION_STATUSES =
      valuesOf(RunnerExecutionStatus.values());
  private static final Set<String> IDEMPOTENCY_RECORD_STATUSES =
      valuesOf(IdempotencyRecordStatus.values());
  private static final Set<String> ARTIFACT_OPERATION_STATUSES =
      valuesOf(ArtifactOperationStatus.values());
  private static final Set<String> ARTIFACT_OPERATION_TYPES =
      valuesOf(ArtifactOperationType.values());
  private static final Set<String> ARTIFACT_STATUSES = valuesOf(ArtifactStatus.values());
  private static final Set<String> ARTIFACT_TYPES = valuesOf(ArtifactType.values());
  private static final Set<String> DATA_CLASSIFICATIONS = valuesOf(DataClassification.values());
  private static final Set<String> FAILURE_CATEGORIES = valuesOf(FailureCategory.values());
  private static final Set<String> FAILURE_TAXONOMY_VALUES =
      valuesOf(FailureTaxonomyValue.values());
  private static final Set<String> INTEGRATION_FAILURE_CATEGORIES =
      valuesOf(IntegrationFailureCategory.values());
  private static final Set<String> INTEGRATION_CONFLICT_CATEGORIES =
      valuesOf(IntegrationConflictCategory.values());
  private static final Set<String> RECONCILIATION_DECISIONS =
      valuesOf(ReconciliationDecision.values());
  private static final Set<String> SAFE_RERUN_STEPS = valuesOf(SafeRerunStep.values());
  private static final Set<String> INTEGRATION_SYNC_STATUSES =
      valuesOf(IntegrationSyncStatus.values());
  private static final Set<String> RUNNER_SCHEMA_VERSIONS = valuesOf(RunnerSchemaVersion.values());
  private static final Set<String> RUNNER_STAGES = valuesOf(RunnerStage.values());
  private static final Set<String> RUNNER_KINDS = valuesOf(RunnerKind.values());
  private static final Set<String> WORKFLOW_EVENT_TYPES = valuesOf(WorkflowEventType.values());
  private static final Set<String> PROJECT_STATUSES = valuesOf(ProjectStatus.values());
  private static final Set<String> PROJECT_RUNNER_STEPS = valuesOf(ProjectRunnerStep.values());
  private static final Set<String> CONNECTOR_KINDS = valuesOf(ConnectorKind.values());
  private static final Set<String> REVIEW_OUTCOMES = valuesOf(ReviewOutcome.values());
  private static final Map<String, String> PUBLIC_ID_PREFIXES = PublicIdPrefixes.prefixMap();

  private DomainRegistry() {}

  public static Set<String> workflowStates() {
    return WORKFLOW_STATES;
  }

  public static Set<String> actorTypes() {
    return ACTOR_TYPES;
  }

  public static Set<String> allowedActions() {
    return ALLOWED_ACTIONS;
  }

  public static Set<String> domainErrorCodes() {
    return DOMAIN_ERROR_CODES;
  }

  public static Set<String> runnerExecutionStatuses() {
    return RUNNER_EXECUTION_STATUSES;
  }

  public static Set<String> idempotencyRecordStatuses() {
    return IDEMPOTENCY_RECORD_STATUSES;
  }

  public static Set<String> artifactOperationStatuses() {
    return ARTIFACT_OPERATION_STATUSES;
  }

  public static Set<String> artifactOperationTypes() {
    return ARTIFACT_OPERATION_TYPES;
  }

  public static Set<String> artifactStatuses() {
    return ARTIFACT_STATUSES;
  }

  public static Set<String> artifactTypes() {
    return ARTIFACT_TYPES;
  }

  public static Set<String> dataClassifications() {
    return DATA_CLASSIFICATIONS;
  }

  public static Set<String> failureCategories() {
    return FAILURE_CATEGORIES;
  }

  public static Set<String> failureTaxonomyValues() {
    return FAILURE_TAXONOMY_VALUES;
  }

  public static Set<String> integrationFailureCategories() {
    return INTEGRATION_FAILURE_CATEGORIES;
  }

  public static Set<String> integrationConflictCategories() {
    return INTEGRATION_CONFLICT_CATEGORIES;
  }

  public static Set<String> reconciliationDecisions() {
    return RECONCILIATION_DECISIONS;
  }

  public static Set<String> safeRerunSteps() {
    return SAFE_RERUN_STEPS;
  }

  public static Set<String> integrationSyncStatuses() {
    return INTEGRATION_SYNC_STATUSES;
  }

  public static Set<String> runnerSchemaVersions() {
    return RUNNER_SCHEMA_VERSIONS;
  }

  public static Set<String> runnerStages() {
    return RUNNER_STAGES;
  }

  public static Set<String> runnerKinds() {
    return RUNNER_KINDS;
  }

  public static Set<String> workflowEventTypes() {
    return WORKFLOW_EVENT_TYPES;
  }

  public static Set<String> projectStatuses() {
    return PROJECT_STATUSES;
  }

  public static Set<String> projectRunnerSteps() {
    return PROJECT_RUNNER_STEPS;
  }

  public static Set<String> connectorKinds() {
    return CONNECTOR_KINDS;
  }

  public static Set<String> reviewOutcomes() {
    return REVIEW_OUTCOMES;
  }

  public static Map<String, String> publicIdPrefixes() {
    return PUBLIC_ID_PREFIXES;
  }

  private static Set<String> valuesOf(RegistryValue[] values) {
    LinkedHashSet<String> set =
        Arrays.stream(values)
            .map(RegistryValue::value)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return Collections.unmodifiableSet(set);
  }
}
