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
        DomainErrorCode.RETRY_NOT_APPLICABLE,
        HttpStatus.CONFLICT,
        "Retry not applicable",
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
