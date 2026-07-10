package org.dradgo.application.project;

/**
 * Story 3c-8 (AC1/AC5) — application command to create a project. A plain record (NOT a sealed
 * {@code WorkflowCommand} variant — mirrors the {@code SubmitBatchCommand} "not-a-WorkflowCommand"
 * precedent), so it carries no workflow-run identity and is fingerprinted directly via the generic
 * {@code IdempotencyService}.
 *
 * <p>The connector {@code *Kind} fields are the raw wire strings ({@code linear}/{@code github}/…);
 * {@link ProjectManagementService} parses them through {@code ConnectorKind.fromValue} so a bad
 * value surfaces as a typed 400. Carries the idempotency key + actor identity for the create
 * fingerprint (story 1.9); it never carries a credential.
 */
public record CreateProjectCommand(
    String name,
    String slug,
    String repositoryUrl,
    String ticketSourceKind,
    String repoHostKind,
    boolean openspecEnabled,
    String runnerKind,
    // Story 3e-4 (AC6) — optional per-step runner mapping (raw wire strings step → kind); the
    // service parses them through ProjectRunnerStep/RunnerKind.fromValue so a bad value is a typed
    // 400, never a 500. Null/empty = no per-step mapping.
    java.util.Map<String, String> stepRunnerKinds,
    // Story 3h-1 (AC2) — per-project build-validation config. buildStageEnabled defaults false ⇒
    // BUILD skipped (pre-3h parity); buildCommand is a nullable raw wire string (blank cleared to
    // null / no build). No enum validation needed (mirrors openspecEnabled + reviewerModelKind).
    boolean buildStageEnabled,
    String buildCommand,
    // Story 3h-2 (AC2) — per-project lint-validation config. lintStageEnabled defaults false ⇒ LINT
    // skipped (pre-3h-2 parity); lintCommands is a nullable raw wire list (null/empty = no lint).
    // No
    // enum validation needed (mirrors buildStageEnabled/buildCommand).
    boolean lintStageEnabled,
    java.util.List<String> lintCommands,
    // Story 3h-4 (AC1) — per-project delivery config. pushMode is a nullable raw wire string
    // ({@code auto}/{@code manual}/{@code approve}); the service parses it through
    // ProjectManagementService.parsePushMode (default AUTO on null). autoCreatePullRequest defaults
    // TRUE ⇒ a PR is created (pre-3h parity). No enum validation on the boolean.
    String pushMode,
    boolean autoCreatePullRequest,
    String idempotencyKey,
    String actorIdentity) {
  public CreateProjectCommand(
      String name,
      String slug,
      String repositoryUrl,
      String ticketSourceKind,
      String repoHostKind,
      boolean openspecEnabled,
      String idempotencyKey,
      String actorIdentity) {
    this(
        name,
        slug,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        null,
        null,
        false,
        null,
        false,
        null,
        null,
        true,
        idempotencyKey,
        actorIdentity);
  }

  /**
   * Story 3h-4 back-compat overload for the pre-3h-4 14-arg shape (canonical through {@code
   * lintCommands}) — defaults the delivery fields to {@code (null pushMode ⇒ AUTO, true)}. Keeps
   * existing 14-arg callers (lint create tests) compiling unchanged.
   */
  public CreateProjectCommand(
      String name,
      String slug,
      String repositoryUrl,
      String ticketSourceKind,
      String repoHostKind,
      boolean openspecEnabled,
      String runnerKind,
      java.util.Map<String, String> stepRunnerKinds,
      boolean buildStageEnabled,
      String buildCommand,
      boolean lintStageEnabled,
      java.util.List<String> lintCommands,
      String idempotencyKey,
      String actorIdentity) {
    this(
        name,
        slug,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        runnerKind,
        stepRunnerKinds,
        buildStageEnabled,
        buildCommand,
        lintStageEnabled,
        lintCommands,
        null,
        true,
        idempotencyKey,
        actorIdentity);
  }

  /**
   * Story 3h-1 back-compat overload for the pre-3h 10-arg shape (canonical through {@code
   * stepRunnerKinds}) — defaults the build-config AND lint-config fields to {@code (false, null)}.
   * Keeps existing 10-arg callers (tests) compiling unchanged.
   */
  public CreateProjectCommand(
      String name,
      String slug,
      String repositoryUrl,
      String ticketSourceKind,
      String repoHostKind,
      boolean openspecEnabled,
      String runnerKind,
      java.util.Map<String, String> stepRunnerKinds,
      String idempotencyKey,
      String actorIdentity) {
    this(
        name,
        slug,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        runnerKind,
        stepRunnerKinds,
        false,
        null,
        false,
        null,
        null,
        true,
        idempotencyKey,
        actorIdentity);
  }

  /**
   * Story 3h-2 back-compat overload for the pre-3h-2 12-arg shape (canonical through {@code
   * buildCommand}) — defaults the lint-config fields to {@code (false, null)}. Keeps existing
   * 12-arg callers (build create tests) compiling unchanged.
   */
  public CreateProjectCommand(
      String name,
      String slug,
      String repositoryUrl,
      String ticketSourceKind,
      String repoHostKind,
      boolean openspecEnabled,
      String runnerKind,
      java.util.Map<String, String> stepRunnerKinds,
      boolean buildStageEnabled,
      String buildCommand,
      String idempotencyKey,
      String actorIdentity) {
    this(
        name,
        slug,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        runnerKind,
        stepRunnerKinds,
        buildStageEnabled,
        buildCommand,
        false,
        null,
        null,
        true,
        idempotencyKey,
        actorIdentity);
  }
}
