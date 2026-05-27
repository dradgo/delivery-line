package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsVersionStamp;

/**
 * Wire shape for the version stamp portion of {@code GET
 * /api/v1/workflows/{workflowRunId}/allowed-actions} (story 2.14, AC5). The three nullable fields
 * are intentional — see TRAP 2 in the story spec:
 *
 * <ul>
 *   <li>{@code currentSpecArtifactVersion} — null when no spec exists for the run yet (Inbox /
 *       Planned / Investigating before first draft).
 *   <li>{@code currentContextBundleVersion} — null when no spec exists or when the latest spec's
 *       linked runner execution has no available context bundle (CLI/seed artifacts).
 *   <li>{@code lastEventId} — null only on the unreachable edge of a run with zero events.
 * </ul>
 */
@Schema(
    name = "AllowedActionsVersionStamp",
    description =
        "Version stamp the UI echoes back on mutations so stale UI surfaces a typed version-"
            + "mismatch error.")
// Review P3: pin null-field serialization explicitly. The wire contract documents the three
// nullable fields as present-as-null (not absent) so generated TypeScript clients (`field?:
// number | null`) get `null` rather than `undefined`. A future global Jackson config flip to
// NON_NULL would otherwise silently change the response shape without breaking the local test.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AllowedActionsVersionStampResponse(
    @Schema(
            description = "Current workflow state (wire-string form).",
            example = "WaitingForSpecApproval",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String workflowState,
    @Schema(
            description =
                "Version of the LATEST spec artifact (any approval status); null if none.",
            example = "1",
            nullable = true)
        Integer currentSpecArtifactVersion,
    @Schema(
            description =
                "Context-bundle version of the latest spec's runner execution; null if absent.",
            example = "1",
            nullable = true)
        Integer currentContextBundleVersion,
    @Schema(
            description = "Public id of the most recent workflow event; null on event-less runs.",
            example = "evt_abc123",
            nullable = true)
        String lastEventId) {

  public static AllowedActionsVersionStampResponse from(AllowedActionsVersionStamp stamp) {
    return new AllowedActionsVersionStampResponse(
        stamp.workflowState(),
        stamp.currentSpecArtifactVersion(),
        stamp.currentContextBundleVersion(),
        stamp.lastEventId());
  }
}
