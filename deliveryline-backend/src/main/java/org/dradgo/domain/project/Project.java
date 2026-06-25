package org.dradgo.domain.project;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectRunnerStep;
import org.dradgo.domain.registry.ProjectStatus;
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
    Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds) {

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
    Objects.requireNonNull(createdAt, "Project createdAt must not be null");
    // Null-safe + immutable: an empty map is the canonical "no per-step mapping" value. Map.copyOf
    // rejects null keys/values, so a malformed map fails fast at construction.
    stepRunnerKinds = stepRunnerKinds == null ? Map.of() : Map.copyOf(stepRunnerKinds);
  }
}
