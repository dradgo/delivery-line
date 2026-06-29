package org.dradgo.application.workflow;

/**
 * Story 3f-4 — the insert payload for a new {@code open} split proposal. The {@code proposalJson}
 * is the ALREADY-REDACTED {@code {"subtasks":[...],"dependencies":[...]}} document (the harvester
 * redacts before calling the write port — AC7). Identity fields are the backend-derived reviewer +
 * producer model identities (3d-2 provenance); either may be null when unresolved.
 */
public record NewSplitProposal(
    String publicId,
    String workflowRunId,
    String reviewedArtifactId,
    Integer reviewedArtifactVersion,
    int loopCount,
    String proposalJson,
    String reviewerModelIdentity,
    String producerModelIdentity) {}
