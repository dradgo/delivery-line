package org.dradgo.domain.project;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectRunnerStep;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.PushMode;
import org.dradgo.domain.registry.RunnerKind;

/**
 * Domain aggregate for a configured project, mirroring the V17 {@code projects} columns. This is a
 * pure domain value object (config aggregate), not a JPA entity — the persistence adapter/entity +
 * {@code PersistedRegistryValues} field-context wrappers land when rows are first read/written
 * through the application (3c-6 default-project seed; 3c-8 CRUD).
 *
 * <p>Invariants follow the {@code TicketRef}/{@code RepositoryRef} value-object precedent: blank /
 * null guards throw {@link IllegalArgumentException} / {@link NullPointerException} synchronously
 * to the caller. The {@code publicId} guard routes through {@link PublicIdPrefixes#require} (the
 * established prefix-validation path, which throws a typed {@code
 * DomainException(INVALID_ID_PREFIX)}). User-facing slug/name validation → typed errors happens at
 * the REST boundary in 3c-8, not here.
 */
public record Project(
    String publicId,
    String name,
    String slug,
    ProjectStatus status,
    String repositoryUrl, // nullable
    ConnectorKind ticketSourceKind,
    ConnectorKind repoHostKind,
    boolean openspecEnabled,
    // Story 3d-1 (AC4/AC7) — per-project reviewer-model binding (ADR 0026). reviewerModelKind is a
    // nullable opaque String (NULL = "no reviewer", current behavior); its authoritative validation
    // is the ProjectConnectorResolver at execution time (3d-2, DD-1), so it carries no typed enum
    // here — only the blank-if-set invariant below. reviewerGatingEnabled is created off and is
    // read
    // by NO progression logic in this epic (ADR 0026 Decision 3).
    String reviewerModelKind, // nullable
    boolean reviewerGatingEnabled,
    // Story 3d-3 (AC1, ADR 0024) — per-project runner-kind override. NULL = "no override, use the
    // global per-stage RunnerProperties kind" (R7 parity: the default project seeds NULL). When set
    // to MANUAL the dispatch chokepoint parks the run in WaitingForManualExecution instead of
    // enqueuing a container. Resolved by ProjectRuntimeConfigResolver.resolveRunnerKind. No
    // invariant
    // guard — null is valid; a non-null value is one of the RunnerKind registry values (CHECK in
    // V20).
    RunnerKind runnerKind, // nullable
    OffsetDateTime createdAt,
    OffsetDateTime archivedAt, // nullable
    // Story 3e-4 (AC3) — per-STEP runner mapping (resolves more specifically than the single
    // runnerKind override above, which is kept as the project-wide default). An empty map = "no
    // per-step mapping" (the 3d-3-parity hot path); a present (step → kind) overrides that one step
    // only. Defensive-copied to an unmodifiable map (null → empty) by the compact constructor; the
    // child rows live in project_runner_kinds (V26), loaded with the project by the persistence
    // adapter. NEVER null after construction.
    Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds,
    // Story 3h-1 (AC2, FR75) — per-project build-validation config (ADR 0030). buildCommand is a
    // nullable opaque String (NULL / blank-cleared = "no build command", BUILD skipped) mirroring
    // reviewerModelKind — its authoritative validation is ProjectRuntimeConfigResolver at execution
    // time, so it carries no typed value here beyond the blank-if-set invariant below.
    // buildStageEnabled mirrors openspecEnabled: a plain opt-in flag, default false ⇒ pre-3h parity
    // (BUILD skipped entirely). Detached POJO fields — safe on the worker thread (no lazy proxy).
    String buildCommand, // nullable
    boolean buildStageEnabled,
    // Story 3h-2 (AC2, FR76) — per-project lint-validation config (ADR 0030). lintCommands is a
    // nullable list of CPU linter commands (null / empty = "no lint commands", LINT skipped);
    // defensive-copied to an unmodifiable list (null → empty) by the compact constructor, NEVER
    // null
    // after construction. lintStageEnabled mirrors buildStageEnabled: a plain opt-in flag, default
    // false ⇒ pre-3h-2 parity (LINT skipped entirely). Detached POJO fields — safe on the worker
    // thread (no lazy proxy).
    List<String> lintCommands,
    boolean lintStageEnabled,
    // Story 3h-4 (AC1, FR78) — per-project delivery config (ADR 0030, Decision 6). pushMode is a
    // non-null PushMode enum {AUTO, MANUAL, APPROVE}, default AUTO ⇒ push inline exactly as pre-3h
    // delivery (the run never parks). MANUAL/APPROVE park the run at WaitingForDelivery instead of
    // pushing. Stored as a CHECK-constrained text column (ck_projects_push_mode), NOT free-text
    // like
    // reviewerModelKind — the entity getter parses it via PushMode.fromValue (fail-fast).
    // autoCreatePullRequest is a plain boolean, default TRUE (unlike build/lint's default false) ⇒
    // pre-3h parity (a PR is created wherever the push fires). Detached POJO fields — safe on the
    // worker thread (no lazy proxy).
    PushMode pushMode,
    boolean autoCreatePullRequest) {

  /**
   * Back-compat constructor for the pre-3e-4 13-arg shape — defaults {@code stepRunnerKinds} to an
   * empty map. Keeps the seeder, default-project, and the many existing {@code new Project(...)}
   * call sites (3d-3 parity, no per-step mapping) compiling unchanged; only the
   * create/update/persistence paths that actually carry a per-step map use the full constructor (R2
   * fan-out discipline).
   */
  public Project(
      String publicId,
      String name,
      String slug,
      ProjectStatus status,
      String repositoryUrl,
      ConnectorKind ticketSourceKind,
      ConnectorKind repoHostKind,
      boolean openspecEnabled,
      String reviewerModelKind,
      boolean reviewerGatingEnabled,
      RunnerKind runnerKind,
      OffsetDateTime createdAt,
      OffsetDateTime archivedAt) {
    this(
        publicId,
        name,
        slug,
        status,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        reviewerModelKind,
        reviewerGatingEnabled,
        runnerKind,
        createdAt,
        archivedAt,
        Map.of());
  }

  /**
   * Story 3h-1 back-compat constructor for the pre-3h 14-arg shape (canonical through {@code
   * stepRunnerKinds}) — defaults the two new build-stage fields to {@code (null, false)} ⇒ BUILD
   * skipped (pre-3h parity). Keeps the many existing 14-arg {@code new Project(...)} call sites
   * (tests, resolver fixtures) compiling unchanged; only the create/update/persistence/seed paths
   * that actually carry build config use the full 16-arg constructor.
   */
  public Project(
      String publicId,
      String name,
      String slug,
      ProjectStatus status,
      String repositoryUrl,
      ConnectorKind ticketSourceKind,
      ConnectorKind repoHostKind,
      boolean openspecEnabled,
      String reviewerModelKind,
      boolean reviewerGatingEnabled,
      RunnerKind runnerKind,
      OffsetDateTime createdAt,
      OffsetDateTime archivedAt,
      Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds) {
    this(
        publicId,
        name,
        slug,
        status,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        reviewerModelKind,
        reviewerGatingEnabled,
        runnerKind,
        createdAt,
        archivedAt,
        stepRunnerKinds,
        null,
        false);
  }

  /**
   * Story 3h-2 back-compat constructor for the pre-3h-2 16-arg shape (canonical through {@code
   * buildStageEnabled}) — defaults the two new lint-stage fields to {@code (empty, false)} ⇒ LINT
   * skipped (pre-3h-2 parity). Keeps the existing 16-arg {@code new Project(...)} call sites (build
   * create/update/seed paths, tests, resolver fixtures) compiling unchanged; only the paths that
   * actually carry lint config use the full 18-arg constructor.
   */
  public Project(
      String publicId,
      String name,
      String slug,
      ProjectStatus status,
      String repositoryUrl,
      ConnectorKind ticketSourceKind,
      ConnectorKind repoHostKind,
      boolean openspecEnabled,
      String reviewerModelKind,
      boolean reviewerGatingEnabled,
      RunnerKind runnerKind,
      OffsetDateTime createdAt,
      OffsetDateTime archivedAt,
      Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds,
      String buildCommand,
      boolean buildStageEnabled) {
    this(
        publicId,
        name,
        slug,
        status,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        reviewerModelKind,
        reviewerGatingEnabled,
        runnerKind,
        createdAt,
        archivedAt,
        stepRunnerKinds,
        buildCommand,
        buildStageEnabled,
        List.of(),
        false);
  }

  /**
   * Story 3h-4 back-compat constructor for the pre-3h-4 18-arg shape (canonical through {@code
   * lintStageEnabled}) — defaults the two new delivery fields to {@code (PushMode.AUTO, true)} ⇒
   * push inline + create a PR (pre-3h delivery parity). Keeps the existing 18-arg {@code new
   * Project(...)} call sites (lint create/update/seed paths, tests, resolver fixtures) compiling
   * unchanged; only the paths that actually carry delivery config use the full 20-arg constructor.
   */
  public Project(
      String publicId,
      String name,
      String slug,
      ProjectStatus status,
      String repositoryUrl,
      ConnectorKind ticketSourceKind,
      ConnectorKind repoHostKind,
      boolean openspecEnabled,
      String reviewerModelKind,
      boolean reviewerGatingEnabled,
      RunnerKind runnerKind,
      OffsetDateTime createdAt,
      OffsetDateTime archivedAt,
      Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds,
      String buildCommand,
      boolean buildStageEnabled,
      List<String> lintCommands,
      boolean lintStageEnabled) {
    this(
        publicId,
        name,
        slug,
        status,
        repositoryUrl,
        ticketSourceKind,
        repoHostKind,
        openspecEnabled,
        reviewerModelKind,
        reviewerGatingEnabled,
        runnerKind,
        createdAt,
        archivedAt,
        stepRunnerKinds,
        buildCommand,
        buildStageEnabled,
        lintCommands,
        lintStageEnabled,
        PushMode.AUTO,
        true);
  }

  public Project {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.PROJECT);
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Project name must be non-blank");
    }
    if (slug == null || slug.isBlank()) {
      throw new IllegalArgumentException("Project slug must be non-blank");
    }
    Objects.requireNonNull(status, "Project status must not be null");
    Objects.requireNonNull(ticketSourceKind, "Project ticketSourceKind must not be null");
    Objects.requireNonNull(repoHostKind, "Project repoHostKind must not be null");
    // A set-but-blank reviewer kind is a misconfiguration; null is the valid "no reviewer" value.
    if (reviewerModelKind != null && reviewerModelKind.isBlank()) {
      throw new IllegalArgumentException("Project reviewerModelKind must be non-blank when set");
    }
    // Story 3h-1 — a set-but-blank build command is a misconfiguration; null is the valid "no build
    // command" value (BUILD skipped). Mirrors the reviewerModelKind blank-if-set invariant.
    if (buildCommand != null && buildCommand.isBlank()) {
      throw new IllegalArgumentException("Project buildCommand must be non-blank when set");
    }
    Objects.requireNonNull(createdAt, "Project createdAt must not be null");
    // Null-safe + immutable: an empty map is the canonical "no per-step mapping" value. Map.copyOf
    // rejects null keys/values, so a malformed map fails fast at construction.
    stepRunnerKinds = stepRunnerKinds == null ? Map.of() : Map.copyOf(stepRunnerKinds);
    // Story 3h-2 — null-safe + immutable: an empty list is the canonical "no lint commands" value.
    // List.copyOf rejects null elements, so a malformed list fails fast at construction (mirrors
    // stepRunnerKinds).
    lintCommands = lintCommands == null ? List.of() : List.copyOf(lintCommands);
    // Story 3h-4 — a null pushMode coerces to the AUTO default (push inline, never park). Mirrors
    // the RunnerProperties.DeliveryMode null-coalesce; keeps the pre-3h delivery behavior for any
    // caller that constructs a Project without an explicit push mode.
    pushMode = pushMode == null ? PushMode.AUTO : pushMode;
  }
}
