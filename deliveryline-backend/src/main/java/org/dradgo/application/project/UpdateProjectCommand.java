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
    // Story 3h-1 (AC2) — per-project build-validation config, editable here. buildStageEnabled
    // full-replaces (default false clears); buildCommand null/blank clears the command.
    boolean buildStageEnabled,
    String buildCommand,
    // Story 3h-2 (AC2) — per-project lint-validation config, editable here. lintStageEnabled
    // full-replaces (default false clears); lintCommands null/empty clears all lint commands.
    boolean lintStageEnabled,
    java.util.List<String> lintCommands,
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
        false,
        null,
        false,
        null,
        actorIdentity);
  }

  /**
   * Story 3h-1 back-compat overload for the pre-3h 9-arg shape (canonical through {@code
   * stepRunnerKinds}) — defaults the build-config AND lint-config fields to {@code (false, null)}.
   * Keeps existing 9-arg callers (tests) compiling unchanged.
   */
  public UpdateProjectCommand(
      String name,
      String repositoryUrl,
      String ticketSourceKind,
      String repoHostKind,
      boolean openspecEnabled,
      String runnerKind,
      String reviewerModelKind,
      java.util.Map<String, String> stepRunnerKinds,
      String actorIdentity) {
    this(
        name,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        runnerKind,
        reviewerModelKind,
        stepRunnerKinds,
        false,
        null,
        false,
        null,
        actorIdentity);
  }

  /**
   * Story 3h-2 back-compat overload for the pre-3h-2 11-arg shape (canonical through {@code
   * buildCommand}) — defaults the lint-config fields to {@code (false, null)}. Keeps existing
   * 11-arg callers (build update tests) compiling unchanged.
   */
  public UpdateProjectCommand(
      String name,
      String repositoryUrl,
      String ticketSourceKind,
      String repoHostKind,
      boolean openspecEnabled,
      String runnerKind,
      String reviewerModelKind,
      java.util.Map<String, String> stepRunnerKinds,
      boolean buildStageEnabled,
      String buildCommand,
      String actorIdentity) {
    this(
        name,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        runnerKind,
        reviewerModelKind,
        stepRunnerKinds,
        buildStageEnabled,
        buildCommand,
        false,
        null,
        actorIdentity);
  }
}
