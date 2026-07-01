package org.dradgo.application.project;

/**
 * Story 3c-8 (AC1) — application command to edit a project's mutable configuration (name /
 * repository url / connector kinds / OpenSpec flag). The {@code slug} and {@code publicId} are NOT
 * editable: the slug is the stable human handle and the public id is identity, so update is keyed
 * on the path {@code publicId} and never reassigns either. A plain record (NOT a {@code
 * WorkflowCommand}); update is not idempotency-gated (only create + credential-set are, AC5), so it
 * carries no idempotency key.
 */
public record UpdateProjectCommand(
    String name,
    String repositoryUrl,
    String ticketSourceKind,
    String repoHostKind,
    boolean openspecEnabled,
    String runnerKind,
    // Story 3d-2 — optional advisory-reviewer model binding (raw wire string). null/blank clears
    // the binding; a non-blank value is validated to a non-MANUAL RunnerKind by the service.
    String reviewerModelKind,
    // Story 3e-4 (AC6) — optional per-step runner mapping (raw wire strings), full-replace on
    // update.
    java.util.Map<String, String> stepRunnerKinds,
    String actorIdentity) {
  public UpdateProjectCommand(
      String name,
      String repositoryUrl,
      String ticketSourceKind,
      String repoHostKind,
      boolean openspecEnabled,
      String actorIdentity) {
    this(
        name,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        null,
        null,
        null,
        actorIdentity);
  }
}
