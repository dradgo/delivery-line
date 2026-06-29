package org.dradgo.application.workflow;

import java.time.Instant;
import java.util.List;

/**
 * Story 3f-4 — a persisted advisory split proposal, decoded from a {@code split_proposals} row for
 * read surfaces (GET /split-proposal, the Split Proposal Panel). The {@code subtasks} + {@code
 * dependencies} are decoded from the redacted {@code proposal_json}. {@code selfReview} is derived
 * (reviewer identity == producer identity, 3d-2 provenance). A read-view record (REST maps it
 * directly), so it lives in {@code application.workflow}, not {@code application.workflow.spi}
 * (REST-stays-thin ArchUnit pin — see story 3f-3 reconciliations).
 */
public record SplitProposalView(
    String publicId,
    String workflowRunId,
    String status,
    int loopCount,
    String reviewedArtifactId,
    Integer reviewedArtifactVersion,
    String reviewerModelIdentity,
    String producerModelIdentity,
    boolean selfReview,
    List<SplitSubtaskView> subtasks,
    List<SplitDependencyView> dependencies,
    Instant createdAt) {

  /** Lifecycle status wire values (mirror the ck_split_proposals_status CHECK). */
  public static final String STATUS_OPEN = "open";

  public static final String STATUS_SUPERSEDED = "superseded";
  public static final String STATUS_DISMISSED = "dismissed";
  public static final String STATUS_APPROVED = "approved";

  public boolean isOpen() {
    return STATUS_OPEN.equals(status);
  }
}
