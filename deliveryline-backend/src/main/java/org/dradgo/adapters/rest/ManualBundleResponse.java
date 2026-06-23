package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Base64;
import org.dradgo.application.workflow.WorkflowInspectionService.ManualBundleLookupResult;

/**
 * Story 3d-4 (AC1) — response body for {@code GET /{workflowRunId}/manual-bundle}. Mirrors the
 * available/unavailable discipline of the artifact-keyed context-bundle read: when {@code
 * available} is true the redacted bundle bytes are base64-encoded in {@code bundleBase64} (Open
 * Decision #4 — binary-safe across the JSON boundary) alongside the {@code contextBundleVersion};
 * when false the typed {@code unavailableReason} (e.g. {@code bundleNotPersisted}) explains why,
 * without a 500. The bytes are {@code SHAREABLE_REDACTED} — never the unredacted payload.
 */
public record ManualBundleResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "run_abc123")
        String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "rex_abc123")
        String runnerExecutionId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "true") boolean available,
    @Schema(
            description =
                "Reason the bundle is unavailable (e.g. bundleNotPersisted); null when available.",
            example = "bundleNotPersisted",
            nullable = true)
        String unavailableReason,
    @Schema(
            description = "Context-bundle version; null when unavailable.",
            example = "1",
            nullable = true)
        Integer contextBundleVersion,
    @Schema(
            description =
                "Base64-encoded redacted runner-contracts input bundle; null when unavailable.",
            nullable = true)
        String bundleBase64) {

  public static ManualBundleResponse from(ManualBundleLookupResult result) {
    if (result.available()) {
      return new ManualBundleResponse(
          result.workflowRunId(),
          result.runnerExecutionId(),
          true,
          null,
          result.bundle().contextBundleVersion(),
          Base64.getEncoder().encodeToString(result.bundle().redactedPayload()));
    }
    return new ManualBundleResponse(
        result.workflowRunId(), result.runnerExecutionId(), false, result.reason(), null, null);
  }
}
