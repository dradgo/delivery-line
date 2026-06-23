package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Story 3d-4 (AC2 / Open Decision #3) — request body for {@code POST
 * /{workflowRunId}/manual-artifact}. The {@code result} is the runner-result-shaped JSON the {@code
 * ContractValidator} validates verbatim (true validation parity — the operator submits exactly what
 * an automated runner would write, no bypass / no parallel weaker schema). Boundary validation is
 * deliberately minimal ({@code result} non-null); the authoritative validation is the {@code
 * ContractValidator} in {@code ManualArtifactSubmissionService}.
 *
 * <p>{@code artifactContents} optionally carries the bytes (base64) for any {@code
 * contentReference}-keyed artifact the operator produced (e.g. a spec's markdown), materialized
 * into scratch before ingest so the broker's content read resolves. Keyed by the {@code
 * contentReference} string used in {@code result}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ManualArtifactSubmissionRequest(
    // Typed as a free-form Object (deserialized by the active Jackson 3 HTTP converter into a
    // Map/List tree) rather than a Jackson-2 JsonNode: the app runs on Spring Boot 4 / Jackson 3,
    // whose converter cannot construct a com.fasterxml.jackson.databind.JsonNode, so a JsonNode
    // field made the endpoint 500 on every request (review follow-up 2026-06-23). The controller
    // re-serializes this tree to bytes; the authoritative runner-contract validation runs in the
    // service.
    @NotNull
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Runner-result-shaped JSON (runner-result.v1) the operator produced.")
        Object result,
    @Schema(
            description =
                "Optional map of contentReference -> base64-encoded artifact bytes (e.g. a spec's"
                    + " markdown), materialized into scratch before ingest.",
            nullable = true)
        Map<String, String> artifactContents) {}
