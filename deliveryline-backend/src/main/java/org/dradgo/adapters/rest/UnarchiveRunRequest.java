package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/unarchive} (story 3d-8).
 * Symmetric twin of {@link ArchiveRunRequest}; {@code reason} is <strong>optional</strong> on un-
 * hide (re-surfacing a run rarely needs justification), recorded on the {@code workflow.unarchived}
 * event when supplied.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UnarchiveRunRequest(
    @Schema(description = "Why this run is being un-hidden (optional).", nullable = true)
        @Size(max = 512)
        String reason) {}
