package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.dradgo.application.workflow.SplitProposalStatusView;
import org.dradgo.application.workflow.SplitProposalView;

/**
 * Story 3f-4 (AC6) — the advisory split-proposal read model served by GET /split-proposal and
 * returned by the three split actions. {@code state} ∈ {@code none|pending|available|unavailable};
 * {@code proposal} is non-null only when {@code available}.
 */
@Schema(name = "SplitProposalResponse")
public record SplitProposalResponse(
    @Schema(
            description = "Proposal channel state.",
            allowableValues = {"none", "pending", "available", "unavailable"},
            example = "available")
        String state,
    @Schema(description = "The run's re-propose loop counter.", example = "0") int loopCount,
    @Schema(description = "The current proposal (only when state=available).")
        SplitProposalPayload proposal) {

  static SplitProposalResponse from(SplitProposalStatusView view) {
    return new SplitProposalResponse(
        view.state(), view.loopCount(), SplitProposalPayload.from(view.proposal()));
  }

  /** The decomposition itself (subtasks + suggested dependency edges) + provenance. */
  @Schema(name = "SplitProposalPayload")
  public record SplitProposalPayload(
      @Schema(example = "splprop_abc123") String splitProposalId,
      @Schema(example = "open") String status,
      List<SplitSubtaskPayload> subtasks,
      List<SplitDependencyPayload> dependencies,
      @Schema(example = "art_spec123") String reviewedArtifactId,
      @Schema(example = "1") Integer reviewedArtifactVersion,
      @Schema(example = "claude:latest") String reviewerModelIdentity,
      @Schema(example = "claude:latest") String producerModelIdentity,
      @Schema(
              description = "True when the proposing model equals the reviewed-artifact producer.",
              example = "false")
          boolean selfReview) {

    static SplitProposalPayload from(SplitProposalView v) {
      if (v == null) {
        return null;
      }
      return new SplitProposalPayload(
          v.publicId(),
          v.status(),
          v.subtasks() == null
              ? List.of()
              : v.subtasks().stream().map(SplitSubtaskPayload::from).toList(),
          v.dependencies() == null
              ? List.of()
              : v.dependencies().stream().map(SplitDependencyPayload::from).toList(),
          v.reviewedArtifactId(),
          v.reviewedArtifactVersion(),
          v.reviewerModelIdentity(),
          v.producerModelIdentity(),
          v.selfReview());
    }
  }

  /** One proposed subtask. */
  @Schema(name = "SplitSubtaskPayload")
  public record SplitSubtaskPayload(
      @Schema(example = "1") int ordinal,
      @Schema(example = "Extract the auth module") String title,
      @Schema(example = "Move the auth handlers into a dedicated module.") String scope) {

    static SplitSubtaskPayload from(org.dradgo.application.workflow.SplitSubtaskView v) {
      return new SplitSubtaskPayload(v.ordinal(), v.title(), v.scope());
    }
  }

  /** One suggested ordering edge (subtask {@code fromOrdinal} depends on {@code toOrdinal}). */
  @Schema(name = "SplitDependencyPayload")
  public record SplitDependencyPayload(
      @Schema(example = "2") int fromOrdinal, @Schema(example = "1") int toOrdinal) {

    static SplitDependencyPayload from(org.dradgo.application.workflow.SplitDependencyView v) {
      return new SplitDependencyPayload(v.fromOrdinal(), v.toOrdinal());
    }
  }
}
