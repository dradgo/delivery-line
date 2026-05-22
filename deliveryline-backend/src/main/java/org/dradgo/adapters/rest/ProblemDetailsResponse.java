package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OpenAPI model for DeliveryLine's RFC 9457 error payload plus governed extension fields.
 *
 * <p>The runtime payload is produced by {@link ProblemDetailsMapper}. The {@code details} extension
 * is intentionally loose because some errors emit an object while validation failures emit an array
 * of field-error entries.
 */
@Schema(
    name = "ProblemDetailsResponse",
    description = "RFC 9457 Problem Details payload with DeliveryLine extension fields.")
public record ProblemDetailsResponse(
    @Schema(
            format = "uri",
            example = "https://deliveryline.local/problems/run-not-found",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String type,
    @Schema(example = "Workflow run not found", requiredMode = Schema.RequiredMode.REQUIRED)
        String title,
    @Schema(example = "404", requiredMode = Schema.RequiredMode.REQUIRED) int status,
    @Schema(
            example = "Workflow run not found: run_abc123",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String detail,
    @Schema(
            example = "/api/v1/workflows/run_abc123/events",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String instance,
    @Schema(example = "RUN_NOT_FOUND", requiredMode = Schema.RequiredMode.REQUIRED) String code,
    @Schema(example = "false", requiredMode = Schema.RequiredMode.REQUIRED) boolean retryable,
    @Schema(
            nullable = true,
            example = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            description = "Echoed correlation ID when present on the request or MDC.")
        String correlationId,
    @Schema(
            nullable = true,
            description =
                "Domain-specific extension payload. Validation errors emit an array of field-error "
                    + "objects; other domain errors typically emit an object.")
        Object details) {}
