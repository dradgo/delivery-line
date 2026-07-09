package org.dradgo.adapters.rest;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.dradgo.domain.registry.DomainErrorCode;
import org.springframework.http.HttpStatus;

public final class ProblemDetailsCatalog {

  private static final String PROBLEM_TYPE_URI_PREFIX = "https://deliveryline.local/problems/";

  private static final Map<DomainErrorCode, ProblemDetailsMetadata> METADATA = createMetadata();

  private ProblemDetailsCatalog() {}

  public static ProblemDetailsMetadata metadataFor(DomainErrorCode code) {
    ProblemDetailsMetadata metadata = METADATA.get(code);
    if (metadata == null) {
      throw new IllegalStateException("No Problem Details metadata registered for " + code.value());
    }
    return metadata;
  }

  public static Map<DomainErrorCode, ProblemDetailsMetadata> metadataByCode() {
    return METADATA;
  }

  public record ProblemDetailsMetadata(
      HttpStatus status, String title, boolean retryable, String typeUri) {}

  private static Map<DomainErrorCode, ProblemDetailsMetadata> createMetadata() {
    EnumMap<DomainErrorCode, ProblemDetailsMetadata> metadata =
        new EnumMap<>(DomainErrorCode.class);
    register(
        metadata,
        DomainErrorCode.ILLEGAL_TRANSITION,
        HttpStatus.CONFLICT,
        "Illegal transition",
        false);
    register(
        metadata,
        DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        HttpStatus.CONFLICT,
        "Idempotency key conflict",
        true);
    register(
        metadata,
        DomainErrorCode.MISSING_IDEMPOTENCY_KEY,
        HttpStatus.BAD_REQUEST,
        "Missing idempotency key",
        false);
    register(
        metadata,
        DomainErrorCode.INVALID_IDEMPOTENCY_KEY,
        HttpStatus.BAD_REQUEST,
        "Invalid idempotency key",
        false);
    register(
        metadata,
        DomainErrorCode.STALE_IDEMPOTENCY_RESERVATION,
        HttpStatus.CONFLICT,
        "Stale idempotency reservation",
        true);
    register(
        metadata,
        DomainErrorCode.IDEMPOTENCY_RESERVATION_EXHAUSTED,
        HttpStatus.CONFLICT,
        "Idempotency reservation exhausted",
        true);
    register(
        metadata,
        DomainErrorCode.IDEMPOTENCY_RECORD_LOST,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Idempotency record lost",
        false);
    register(
        metadata,
        DomainErrorCode.EXPORT_CLASSIFICATION_VIOLATION,
        HttpStatus.CONFLICT,
        "Export classification violation",
        false);
    register(
        metadata,
        DomainErrorCode.APPROVAL_VERSION_MISMATCH,
        HttpStatus.CONFLICT,
        "Approval version mismatch",
        false);
    register(
        metadata,
        DomainErrorCode.CLARIFICATION_ARTIFACT_VERSION_MISMATCH,
        HttpStatus.CONFLICT,
        "Clarification artifact version mismatch",
        true);
    register(
        metadata,
        DomainErrorCode.CLARIFICATION_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Clarification not found",
        false);
    register(
        metadata,
        DomainErrorCode.CLARIFICATION_TERMINAL_STATE,
        HttpStatus.CONFLICT,
        "Clarification is in a terminal state",
        false);
    register(
        metadata,
        DomainErrorCode.ILLEGAL_CLARIFICATION_TRANSITION,
        HttpStatus.CONFLICT,
        "Illegal clarification transition",
        false);
    register(
        metadata,
        DomainErrorCode.CONCURRENT_TRANSITION_CONFLICT,
        HttpStatus.CONFLICT,
        "Concurrent transition conflict",
        true);
    register(
        metadata,
        DomainErrorCode.RUNNER_TIMEOUT,
        HttpStatus.GATEWAY_TIMEOUT,
        "Runner timeout",
        true);
    register(
        metadata,
        DomainErrorCode.RUNNER_CONTRACT_VIOLATION,
        HttpStatus.BAD_GATEWAY,
        "Runner contract violation",
        false);
    register(
        metadata,
        DomainErrorCode.LINEAR_TICKET_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Linear ticket not found",
        false);
    register(
        metadata,
        DomainErrorCode.INTEGRATION_LINK_CONFLICT,
        HttpStatus.CONFLICT,
        "Integration link conflict",
        false);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Artifact payload unavailable",
        true);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_LINEAGE_ALREADY_EXISTS,
        HttpStatus.CONFLICT,
        "Artifact lineage already exists",
        false);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Artifact record not found",
        false);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_OPERATION_CONFLICT,
        HttpStatus.CONFLICT,
        "Artifact operation conflict",
        true);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_INVALID_FILENAME,
        HttpStatus.BAD_REQUEST,
        "Artifact payload reference is invalid",
        false);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
        HttpStatus.CONFLICT,
        "Artifact state transition not allowed",
        false);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
        HttpStatus.CONFLICT,
        "Artifact checksum mismatch",
        false);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_WORKFLOW_RUN_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Workflow run not found for artifact operation",
        false);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_RUNNER_EXECUTION_TIMED_OUT,
        HttpStatus.CONFLICT,
        "Runner execution timed out",
        false);
    register(
        metadata,
        DomainErrorCode.ARTIFACT_OPERATION_INTENT_CONFLICT,
        HttpStatus.CONFLICT,
        "Artifact operation intent conflict",
        false);
    register(
        metadata,
        DomainErrorCode.WORKFLOW_RUN_TERMINAL,
        HttpStatus.CONFLICT,
        "Workflow run is terminal",
        false);
    register(
        metadata,
        DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Runner execution not found",
        false);
    register(
        metadata,
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        HttpStatus.BAD_REQUEST,
        "Invalid command payload",
        false);
    register(
        metadata,
        DomainErrorCode.INVALID_TIME_RANGE,
        HttpStatus.BAD_REQUEST,
        "Invalid time range",
        false);
    register(
        metadata,
        DomainErrorCode.HISTORY_TOO_LARGE,
        HttpStatus.BAD_REQUEST,
        "Workflow history exceeds inspection ceiling",
        false);
    register(
        metadata,
        DomainErrorCode.INTERNAL_ERROR,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Internal error",
        false);
    register(
        metadata,
        DomainErrorCode.UNKNOWN_REGISTRY_VALUE,
        HttpStatus.BAD_REQUEST,
        "Unknown registry value",
        false);
    register(
        metadata,
        DomainErrorCode.INVALID_ID_PREFIX,
        HttpStatus.BAD_REQUEST,
        "Invalid public ID prefix",
        false);
    register(
        metadata,
        DomainErrorCode.RUN_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Workflow run not found",
        false);
    register(
        metadata,
        DomainErrorCode.UNKNOWN_ACTOR_ROLE,
        HttpStatus.BAD_REQUEST,
        "Unknown actor role",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_POSTGRES_UNREACHABLE,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Postgres unreachable",
        true);
    register(
        metadata,
        DomainErrorCode.DOCTOR_FLYWAY_FAILED,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Flyway migration failed",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_REST_BIND_UNAVAILABLE,
        HttpStatus.SERVICE_UNAVAILABLE,
        "REST bind unavailable",
        true);
    register(
        metadata,
        DomainErrorCode.DOCTOR_DOCKER_MISSING,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Docker missing",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_CONFIG_PERMISSIONS_UNSAFE,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Unsafe configuration permissions",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Unsupported environment",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_ARTIFACT_DIR_UNWRITABLE,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Artifact directory unwritable",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Runner provider secret missing",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_GITHUB_AUTH_FAILED,
        HttpStatus.SERVICE_UNAVAILABLE,
        "GitHub authentication failed",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_GITHUB_TOKEN_MISSING,
        HttpStatus.SERVICE_UNAVAILABLE,
        "GitHub token missing",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_JIRA_AUTH_FAILED,
        HttpStatus.SERVICE_UNAVAILABLE,
        "JIRA authentication failed",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_JIRA_TOKEN_MISSING,
        HttpStatus.SERVICE_UNAVAILABLE,
        "JIRA token missing",
        false);
    register(
        metadata,
        DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH,
        HttpStatus.CONFLICT,
        "Linear ticket and GitHub repository do not reconcile",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_GIT_MISSING,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Git command-line tool missing",
        false);
    register(
        metadata,
        DomainErrorCode.DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Git bot identity unconfigured",
        false);
    register(
        metadata,
        DomainErrorCode.RETRY_NOT_APPLICABLE,
        HttpStatus.CONFLICT,
        "Retry not applicable",
        false);
    // Story 3d-4 (AC6) — the wrong-state gate for the manual-bundle / manual-artifact endpoints:
    // the run is not in WaitingForManualExecution (no parked awaiting_manual runner execution).
    // Mirror RETRY_NOT_APPLICABLE's non-retryable CONFLICT (409) mapping.
    register(
        metadata,
        DomainErrorCode.MANUAL_EXECUTION_NOT_APPLICABLE,
        HttpStatus.CONFLICT,
        "Manual execution not applicable",
        false);
    // Story 3a-1 (AC8) — a runner emitting an artifact type the dispatching stage does not expect
    // is a runner-side contract violation; mirror RUNNER_CONTRACT_VIOLATION's BAD_GATEWAY mapping.
    register(
        metadata,
        DomainErrorCode.RUNNER_ARTIFACT_TYPE_MISMATCH,
        HttpStatus.BAD_GATEWAY,
        "Runner artifact type mismatch",
        false);
    // Story 3.7 (AC10) — observability low-memory is a doctor WARN, mirroring the other doctor
    // advisories' SERVICE_UNAVAILABLE mapping (it never blocks normal operation).
    register(
        metadata,
        DomainErrorCode.DOCTOR_OBSERVABILITY_LOW_MEMORY,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Observability host memory low",
        false);
    // Story 3.12 (AC3) — a pr-output runner whose reported refs disagree with the actual git/GitHub
    // state is a runner-side contract violation; mirror RUNNER_CONTRACT_VIOLATION's BAD_GATEWAY.
    register(
        metadata,
        DomainErrorCode.RUNNER_PR_REF_DRIFT,
        HttpStatus.BAD_GATEWAY,
        "Runner PR reference drift",
        false);
    // Story 3.12 (AC9) — a pr-output runner emitting malformed ref strings is a runner-side
    // contract
    // violation; mirror RUNNER_CONTRACT_VIOLATION's BAD_GATEWAY mapping.
    register(
        metadata,
        DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED,
        HttpStatus.BAD_GATEWAY,
        "Runner output validation failed",
        false);
    // Story 3.15 (AC5) — an artifact whose PR reference has drifted from the durable
    // integration_links linkage is a non-retryable conflict; mirror INTEGRATION_LINK_CONFLICT's
    // CONFLICT mapping.
    register(
        metadata,
        DomainErrorCode.ARTIFACT_PR_LINK_MISMATCH,
        HttpStatus.CONFLICT,
        "Artifact PR link mismatch",
        false);
    // Story 3.16 (AC8) — a malformed Linear completion-sync template is a configuration defect that
    // fails context boot; mirror INVALID_COMMAND_PAYLOAD's BAD_REQUEST + non-retryable mapping.
    register(
        metadata,
        DomainErrorCode.INVALID_COMPLETION_TEMPLATE,
        HttpStatus.BAD_REQUEST,
        "Invalid completion-sync template",
        false);
    // Story 3.21 (AC7) — a rejection missing its developer taxonomy is a malformed reviewer
    // decision; mirror INVALID_COMMAND_PAYLOAD's BAD_REQUEST + non-retryable mapping.
    register(
        metadata,
        DomainErrorCode.MISSING_REJECTION_TAXONOMY,
        HttpStatus.BAD_REQUEST,
        "Missing rejection taxonomy",
        false);
    // Story 3.17a (AC4) — queue backpressure is transient: the cap may free up as workers drain the
    // queue. Mirror RUNNER_TIMEOUT's retryable mapping, but as SERVICE_UNAVAILABLE (503, capacity)
    // rather than GATEWAY_TIMEOUT.
    register(
        metadata,
        DomainErrorCode.RUNNER_QUEUE_FULL,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Runner queue is full",
        true);
    // Story 3.23 (AC5 / R4) — a non-`developer` reviewer role on the accept-implementation (and
    // 3.24 reject-implementation) endpoint is a malformed reviewer decision caught at the
    // controller
    // boundary; mirror INVALID_COMMAND_PAYLOAD's BAD_REQUEST + non-retryable mapping.
    register(
        metadata,
        DomainErrorCode.INVALID_REVIEWER_ROLE_FOR_ENDPOINT,
        HttpStatus.BAD_REQUEST,
        "Invalid reviewer role for endpoint",
        false);
    // Story 3.24 (AC4 / R4) — a taggedFeedback that is a valid RejectionTaxonomy but a product
    // value
    // (not in the developer subset) on the reject-implementation endpoint is a malformed reviewer
    // decision caught at the controller boundary; mirror INVALID_COMMAND_PAYLOAD's BAD_REQUEST +
    // non-retryable mapping.
    register(
        metadata,
        DomainErrorCode.INVALID_REJECTION_TAXONOMY,
        HttpStatus.BAD_REQUEST,
        "Invalid rejection taxonomy",
        false);
    // Story 3c-2 (AC5) — project-domain codes registered ahead of their 3c-3/3c-8 throw sites.
    register(
        metadata,
        DomainErrorCode.PROJECT_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Project not found",
        false);
    register(
        metadata,
        DomainErrorCode.PROJECT_SLUG_CONFLICT,
        HttpStatus.CONFLICT,
        "Project slug conflict",
        false);
    register(
        metadata,
        DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND,
        HttpStatus.BAD_REQUEST,
        "Unsupported connector kind",
        false);
    // Story 3c-4 (AC3) — credential master-key fail-fast guard. 503 + non-retryable, mirroring the
    // other infrastructure-missing startup faults (DOCTOR_GITHUB_TOKEN_MISSING /
    // DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED). The type URI auto-derives.
    register(
        metadata,
        DomainErrorCode.CREDENTIAL_MASTER_KEY_UNCONFIGURED,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Credential master key unconfigured",
        false);
    // Story 3c-10 (AC3) — a misconfigured project surfaced by the doctor `projects` probe is a WARN
    // advisory (never blocks normal operation); mirror the other doctor advisories'
    // SERVICE_UNAVAILABLE + non-retryable mapping (DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED /
    // DOCTOR_OBSERVABILITY_LOW_MEMORY). The type URI auto-derives.
    register(
        metadata,
        DomainErrorCode.DOCTOR_PROJECT_CONFIG_INCOMPLETE,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Project configuration incomplete",
        false);
    // Story 3d-1 (AC6) — a reviewer verdict requested for a project with no reviewer model bound is
    // a config-absent advisory; mirror the other "config-absent advisory" codes'
    // SERVICE_UNAVAILABLE
    // + non-retryable mapping. The type URI auto-derives. Registered ahead of its 3d-2 throw site.
    register(
        metadata,
        DomainErrorCode.REVIEWER_MODEL_NOT_CONFIGURED,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Reviewer model not configured",
        false);
    // Story 3d-8 (AC9 / R3) — archiving an already-archived run, or un-archiving a not-archived
    // run,
    // is a precondition mismatch on the run's archive marker; mirror RETRY_NOT_APPLICABLE's
    // CONFLICT + non-retryable mapping. The type URI auto-derives.
    register(
        metadata,
        DomainErrorCode.ARCHIVE_NOT_APPLICABLE,
        HttpStatus.CONFLICT,
        "Archive not applicable",
        false);
    // Story 3e-2 (review D1) — regenerating a spec with zero `accepted` clarifications to
    // incorporate is a precondition mismatch; mirror ARCHIVE_NOT_APPLICABLE's CONFLICT +
    // non-retryable mapping. The type URI auto-derives.
    register(
        metadata,
        DomainErrorCode.REGENERATE_NOT_APPLICABLE,
        HttpStatus.CONFLICT,
        "Regenerate not applicable",
        false);
    // Story 3f-3 (AC4) — declaring a run dependency edge that would introduce a cycle in the
    // run-dependency DAG is a malformed declaration, not a transient fault; mirror
    // ILLEGAL_TRANSITION's CONFLICT + non-retryable mapping. The type URI auto-derives.
    register(
        metadata,
        DomainErrorCode.RUN_DEPENDENCY_CYCLE,
        HttpStatus.CONFLICT,
        "Run dependency cycle",
        false);
    // Story 3f-7 (AC5) — a recursive split deeper than the configured cap (without the
    // allowDeepSplit override) is a malformed request, not a transient fault; mirror
    // RUN_DEPENDENCY_CYCLE's CONFLICT + non-retryable mapping. The type URI auto-derives.
    register(
        metadata,
        DomainErrorCode.SPLIT_DEPTH_LIMIT_EXCEEDED,
        HttpStatus.CONFLICT,
        "Split depth limit exceeded",
        false);
    // Story 4.3 (AC2) — a malformed audit-history filter (unknown event-type token, undecodable
    // cursor, mutually-exclusive scope) is a bad request, not a transient fault; mirror
    // INVALID_COMMAND_PAYLOAD's BAD_REQUEST + non-retryable mapping. The type URI auto-derives.
    register(
        metadata,
        DomainErrorCode.INVALID_AUDIT_FILTER,
        HttpStatus.BAD_REQUEST,
        "Invalid audit filter",
        false);
    // Story 4.5 (AC3) — resuming a run that is not Paused (or a Paused run with no derivable prior
    // executing state) is a precondition mismatch on the run's state, not a transient fault; mirror
    // RETRY_NOT_APPLICABLE's CONFLICT (409) + non-retryable mapping. The type URI auto-derives.
    register(
        metadata,
        DomainErrorCode.RESUME_NOT_APPLICABLE,
        HttpStatus.CONFLICT,
        "Resume not applicable",
        false);
    register(
        metadata,
        DomainErrorCode.RECONCILE_NOT_APPLICABLE,
        HttpStatus.CONFLICT,
        "Reconcile not applicable",
        false);
    register(
        metadata,
        DomainErrorCode.MISSING_RECONCILIATION_DECISION,
        HttpStatus.BAD_REQUEST,
        "Missing reconciliation decision",
        false);
    register(
        metadata,
        DomainErrorCode.INVALID_RECONCILIATION_DECISION,
        HttpStatus.BAD_REQUEST,
        "Invalid reconciliation decision",
        false);
    register(
        metadata,
        DomainErrorCode.CONFLICT_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        "Integration conflict not found",
        false);
    register(
        metadata,
        DomainErrorCode.CONFLICT_ALREADY_RESOLVED,
        HttpStatus.CONFLICT,
        "Integration conflict already resolved",
        false);

    if (!metadata.keySet().equals(java.util.EnumSet.allOf(DomainErrorCode.class))) {
      throw new IllegalStateException("ProblemDetailsCatalog must map every DomainErrorCode");
    }
    return Collections.unmodifiableMap(metadata);
  }

  private static void register(
      EnumMap<DomainErrorCode, ProblemDetailsMetadata> metadata,
      DomainErrorCode code,
      HttpStatus status,
      String title,
      boolean retryable) {
    metadata.put(
        code,
        new ProblemDetailsMetadata(
            status, title, retryable, PROBLEM_TYPE_URI_PREFIX + toUriSlug(code)));
  }

  private static String toUriSlug(DomainErrorCode code) {
    return code.value().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }
}
