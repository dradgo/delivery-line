package org.dradgo.adapters.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.PushMode;
import org.dradgo.domain.registry.RunnerKind;

/**
 * Story 3c-6 — JPA mapping for the V17 {@code projects} table; the FIRST occupant of project
 * persistence (3c-2/3c-3 deliberately shipped no Project read/write side). Mirrors the live {@code
 * WorkflowRunEntity}/{@code IntegrationLinkEntity} convention exactly:
 *
 * <ul>
 *   <li>{@code id} is a {@code bigserial} surrogate ({@code @GeneratedValue(IDENTITY)}); the {@code
 *       prj_} prefix lives on {@code public_id}, never on the PK.
 *   <li>the enum-like {@code status}/{@code ticket_source_kind}/{@code repo_host_kind} columns are
 *       stored raw {@code text} and parsed at the getter through the {@link
 *       PersistedRegistryValues} {@code project*} wrappers (AC7 — fail fast on an unknown DB
 *       value), mirroring {@link WorkflowRunEntity#getCurrentState()}.
 *   <li>{@code created_at} is DB-defaulted ({@code insertable=false, updatable=false}); the
 *       seeder's domain {@code createdAt} is ignored on insert and re-read from the row after
 *       flush.
 * </ul>
 *
 * <p>The {@code project_credentials} table stays UNMAPPED here — the encrypted credential store is
 * 3c-5; the default project writes zero credential rows (3c-6 R4).
 */
@Entity
@Table(name = "projects")
public class ProjectEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "slug", nullable = false)
  private String slug;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "repository_url")
  private String repositoryUrl;

  @Column(name = "ticket_source_kind", nullable = false)
  private String ticketSourceKind;

  @Column(name = "repo_host_kind", nullable = false)
  private String repoHostKind;

  @Column(name = "openspec_enabled", nullable = false)
  private boolean openspecEnabled;

  // Story 3d-1 (AC4) — per-project reviewer-model binding. reviewer_model_kind is nullable opaque
  // text (NULL = no reviewer); reviewer_gating_enabled defaults false and is read by no gating
  // logic
  // in Epic 3d. Neither column has a registry-parsed getter — reviewer_model_kind has no DB CHECK
  // (DD-1) and is validated by the resolver at execution time (3d-2).
  @Column(name = "reviewer_model_kind")
  private String reviewerModelKind;

  @Column(name = "reviewer_gating_enabled", nullable = false)
  private boolean reviewerGatingEnabled;

  // Story 3h-1 (AC2, FR75) — per-project build-validation config (V33). build_command is nullable
  // opaque text (NULL = no build command, BUILD skipped) mirroring reviewer_model_kind — no DB
  // CHECK
  // (validated by the resolver at execution time). build_stage_enabled mirrors openspec_enabled:
  // NOT NULL, default false ⇒ pre-3h parity.
  @Column(name = "build_command")
  private String buildCommand;

  @Column(name = "build_stage_enabled", nullable = false)
  private boolean buildStageEnabled;

  // Story 3h-2 (AC2, FR76) — per-project lint-validation config (V34). lint_commands is nullable
  // opaque text: the lint command list serialized newline-delimited (NULL/empty = no lint commands,
  // LINT skipped) — mirrors build_command's opaque-text posture, no DB CHECK. lint_stage_enabled
  // mirrors build_stage_enabled: NOT NULL, default false ⇒ pre-3h-2 parity.
  @Column(name = "lint_commands")
  private String lintCommands;

  @Column(name = "lint_stage_enabled", nullable = false)
  private boolean lintStageEnabled;

  // Story 3d-3 (AC1) — per-project runner-kind override. Nullable opaque text (NULL = no override);
  // the V20 CHECK pins a non-null value to the RunnerKind value set, so the getter parses through
  // RunnerKind.fromValue (fail fast on an unknown DB value, mirroring the status getter).
  @Column(name = "runner_kind")
  private String runnerKind;

  // Story 3h-4 (AC1, FR78) — per-project delivery config (V38). push_mode is NOT NULL text
  // defaulting
  // 'auto', CHECK-constrained (ck_projects_push_mode) to the PushMode value set — so the getter
  // parses through PushMode.fromValue (fail fast on an unknown DB value, mirroring getRunnerKind()
  // and the status getter). auto_create_pull_request mirrors build_stage_enabled: NOT NULL boolean,
  // but default TRUE (pre-3h delivery created a PR).
  @Column(name = "push_mode", nullable = false)
  private String pushMode;

  @Column(name = "auto_create_pull_request", nullable = false)
  private boolean autoCreatePullRequest;

  // Stamped from the domain createdAt at insert (updatable=false: created_at is immutable). NOT
  // insertable=false: Hibernate does not refresh an entity after saveAndFlush, so a DB-defaulted
  // column would read back null in-memory and trip the Project record's non-null createdAt guard.
  // The V17 `default now()` remains a fallback for raw-SQL inserts (tests).
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "archived_at")
  private OffsetDateTime archivedAt;

  // Task 4 (DinD Testcontainers sidecar) — per-project opt-in for a dockerd sidecar during a run
  // (V40). NOT NULL boolean, default false ⇒ pre-task-4 parity (no sidecar), mirrors
  // openspec_enabled/build_stage_enabled/lint_stage_enabled.
  @Column(name = "testcontainers_enabled", nullable = false)
  private boolean testcontainersEnabled;

  public Long getId() {
    return id;
  }

  public String getPublicId() {
    return publicId;
  }

  public void setPublicId(String publicId) {
    this.publicId = publicId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public ProjectStatus getStatus() {
    return PersistedRegistryValues.projectStatus(status);
  }

  public void setStatus(ProjectStatus status) {
    this.status = Objects.requireNonNull(status, "status").value();
  }

  public String getRepositoryUrl() {
    return repositoryUrl;
  }

  public void setRepositoryUrl(String repositoryUrl) {
    this.repositoryUrl = repositoryUrl;
  }

  public ConnectorKind getTicketSourceKind() {
    return PersistedRegistryValues.projectTicketSourceKind(ticketSourceKind);
  }

  public void setTicketSourceKind(ConnectorKind ticketSourceKind) {
    this.ticketSourceKind = Objects.requireNonNull(ticketSourceKind, "ticketSourceKind").value();
  }

  public ConnectorKind getRepoHostKind() {
    return PersistedRegistryValues.projectRepoHostKind(repoHostKind);
  }

  public void setRepoHostKind(ConnectorKind repoHostKind) {
    this.repoHostKind = Objects.requireNonNull(repoHostKind, "repoHostKind").value();
  }

  public boolean isOpenspecEnabled() {
    return openspecEnabled;
  }

  public void setOpenspecEnabled(boolean openspecEnabled) {
    this.openspecEnabled = openspecEnabled;
  }

  public String getReviewerModelKind() {
    return reviewerModelKind;
  }

  public void setReviewerModelKind(String reviewerModelKind) {
    this.reviewerModelKind = reviewerModelKind;
  }

  public boolean isReviewerGatingEnabled() {
    return reviewerGatingEnabled;
  }

  public void setReviewerGatingEnabled(boolean reviewerGatingEnabled) {
    this.reviewerGatingEnabled = reviewerGatingEnabled;
  }

  public String getBuildCommand() {
    return buildCommand;
  }

  public void setBuildCommand(String buildCommand) {
    this.buildCommand = buildCommand;
  }

  public boolean isBuildStageEnabled() {
    return buildStageEnabled;
  }

  public void setBuildStageEnabled(boolean buildStageEnabled) {
    this.buildStageEnabled = buildStageEnabled;
  }

  public String getLintCommands() {
    return lintCommands;
  }

  public void setLintCommands(String lintCommands) {
    this.lintCommands = lintCommands;
  }

  public boolean isLintStageEnabled() {
    return lintStageEnabled;
  }

  public void setLintStageEnabled(boolean lintStageEnabled) {
    this.lintStageEnabled = lintStageEnabled;
  }

  public RunnerKind getRunnerKind() {
    return runnerKind == null ? null : RunnerKind.fromValue(runnerKind, "projects.runner_kind");
  }

  public void setRunnerKind(RunnerKind runnerKind) {
    this.runnerKind = runnerKind == null ? null : runnerKind.value();
  }

  public PushMode getPushMode() {
    return PushMode.fromValue(pushMode, "projects.push_mode");
  }

  public void setPushMode(PushMode pushMode) {
    this.pushMode = Objects.requireNonNull(pushMode, "pushMode").value();
  }

  public boolean isAutoCreatePullRequest() {
    return autoCreatePullRequest;
  }

  public void setAutoCreatePullRequest(boolean autoCreatePullRequest) {
    this.autoCreatePullRequest = autoCreatePullRequest;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(OffsetDateTime archivedAt) {
    this.archivedAt = archivedAt;
  }

  public boolean isTestcontainersEnabled() {
    return testcontainersEnabled;
  }

  public void setTestcontainersEnabled(boolean testcontainersEnabled) {
    this.testcontainersEnabled = testcontainersEnabled;
  }
}
