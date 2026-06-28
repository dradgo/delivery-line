package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/workflows/{workflowRunId}/dependencies} (story 3f-3, AC9).
 * Declares that the path run depends on each id in {@code dependsOnRunIds}. The list must be
 * non-empty and every entry non-blank; the service additionally enforces public-id prefix,
 * self-edge rejection, state preconditions, and acyclicity.
 */
@Schema(name = "DeclareRunDependenciesRequest")
public record DeclareRunDependenciesRequest(
    @Schema(
            description = "Prerequisite run public ids this run must wait for.",
            example = "[\"run_prereq1\", \"run_prereq2\"]")
        @NotEmpty(message = "dependsOnRunIds must not be empty")
        List<@NotBlank(message = "dependsOnRunIds must not contain blanks") String>
            dependsOnRunIds) {}
