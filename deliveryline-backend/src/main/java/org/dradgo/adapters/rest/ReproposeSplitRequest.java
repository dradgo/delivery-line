package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Story 3f-4 (AC4) — body for POST /split/repropose: the operator's free-text feedback that re-runs
 * the proposal call. The feedback is materialized BY REFERENCE (redacted, durable store) — never
 * inlined into the context bundle JSON (R3).
 */
@Schema(name = "ReproposeSplitRequest")
public record ReproposeSplitRequest(
    @Schema(
            description = "Free-text feedback steering the re-proposal.",
            example = "Split the persistence layer out as its own subtask.")
        @NotBlank(message = "feedbackText must not be blank")
        String feedbackText) {}
