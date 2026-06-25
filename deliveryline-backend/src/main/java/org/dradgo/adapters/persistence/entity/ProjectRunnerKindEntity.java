package org.dradgo.adapters.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.dradgo.domain.registry.ProjectRunnerStep;
import org.dradgo.domain.registry.RunnerKind;

/**
 * Story 3e-4 (AC3) — JPA mapping for the V26 {@code project_runner_kinds} child/association table:
 * the per-step runner binding for a project. Unlike the core tables this is a pure mapping row with
 * a composite primary key {@code (project_id, step)} — no surrogate {@code id}/{@code public_id},
 * no retention pair (there is nothing to address by public id or sweep; the row IS its coordinate).
 *
 * <p>Like the sibling entities, the registry-like {@code step}/{@code runner_kind} columns are
 * stored raw {@code text} and parsed at the getter through the {@link PersistedRegistryValues}
 * {@code projectRunnerKind*} wrappers (fail fast on an unknown DB value), mirroring {@code
 * ProjectEntity#getRunnerKind()}.
 */
@Entity
@Table(name = "project_runner_kinds")
@IdClass(ProjectRunnerKindEntity.Key.class)
public class ProjectRunnerKindEntity {

  @Id
  @Column(name = "project_id", nullable = false)
  private String projectId;

  @Id
  @Column(name = "step", nullable = false)
  private String step;

  @Column(name = "runner_kind", nullable = false)
  private String runnerKind;

  public ProjectRunnerKindEntity() {}

  public ProjectRunnerKindEntity(String projectId, ProjectRunnerStep step, RunnerKind runnerKind) {
    this.projectId = projectId;
    this.step = Objects.requireNonNull(step, "step").value();
    this.runnerKind = Objects.requireNonNull(runnerKind, "runnerKind").value();
  }

  public String getProjectId() {
    return projectId;
  }

  public void setProjectId(String projectId) {
    this.projectId = projectId;
  }

  public ProjectRunnerStep getStep() {
    return PersistedRegistryValues.projectRunnerKindStep(step);
  }

  public void setStep(ProjectRunnerStep step) {
    this.step = Objects.requireNonNull(step, "step").value();
  }

  public RunnerKind getRunnerKind() {
    return PersistedRegistryValues.projectRunnerKindRunnerKind(runnerKind);
  }

  public void setRunnerKind(RunnerKind runnerKind) {
    this.runnerKind = Objects.requireNonNull(runnerKind, "runnerKind").value();
  }

  /**
   * Composite-key class for {@code @IdClass} — field names + types mirror the {@code @Id} fields.
   */
  public static class Key implements Serializable {
    private String projectId;
    private String step;

    public Key() {}

    public Key(String projectId, String step) {
      this.projectId = projectId;
      this.step = step;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key key)) {
        return false;
      }
      return Objects.equals(projectId, key.projectId) && Objects.equals(step, key.step);
    }

    @Override
    public int hashCode() {
      return Objects.hash(projectId, step);
    }
  }
}
