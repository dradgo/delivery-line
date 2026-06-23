package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
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
    @NotNull
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Runner-result-shaped JSON (runner-result.v1) the operator produced.")
        JsonNode result,
    @Schema(
            description =
                "Optional map of contentReference -> base64-encoded artifact bytes (e.g. a spec's"
                    + " markdown), materialized into scratch before ingest.",
            nullable = true)
        Map<String, String> artifactContents) {}
