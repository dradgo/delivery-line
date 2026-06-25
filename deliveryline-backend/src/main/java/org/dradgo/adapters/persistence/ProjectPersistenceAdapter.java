package org.dradgo.adapters.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ProjectEntity;
import org.dradgo.adapters.persistence.entity.ProjectRunnerKindEntity;
import org.dradgo.adapters.persistence.mapper.ProjectEntityMapper;
import org.dradgo.adapters.persistence.repository.ProjectRepository;
import org.dradgo.adapters.persistence.repository.ProjectRunnerKindRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectRunnerStep;
import org.dradgo.domain.registry.RunnerKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3c-6 — JPA implementation of the application-owned {@link ProjectStore} port (the {@code
 * application-cannot-import-adapters} rule: the seeder + resolver reach the DB only through the
 * port). The FIRST occupant of project persistence; mirrors the sibling {@code
 * WorkflowRunPersistenceAdapter}/{@code IntegrationLink} persistence stack.
 *
 * <p>Reads are non-locking and run in the ambient (or no) transaction; writes are
 * {@code @Transactional}. {@link #insert} lets the DB assign {@code id}/{@code created_at} via
 * {@code saveAndFlush}, then maps the persisted row back so the returned aggregate carries the real
 * {@code createdAt}. A slug/public-id uniqueness violation surfaces as the Spring {@code
 * DataIntegrityViolationException} the seeder's concurrent-startup backstop catches (AC5).
 */
@Component
public class ProjectPersistenceAdapter implements ProjectStore {

  private static final Logger log = LoggerFactory.getLogger(ProjectPersistenceAdapter.class);

  private final ProjectRepository projectRepository;
  private final ProjectRunnerKindRepository projectRunnerKindRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final ProjectEntityMapper projectEntityMapper;

  public ProjectPersistenceAdapter(
      ProjectRepository projectRepository,
      ProjectRunnerKindRepository projectRunnerKindRepository,
      WorkflowRunRepository workflowRunRepository,
      ProjectEntityMapper projectEntityMapper) {
    this.projectRepository = projectRepository;
    this.projectRunnerKindRepository = projectRunnerKindRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.projectEntityMapper = projectEntityMapper;
  }

  @Override
  public Optional<Project> findBySlug(String slug) {
    return projectRepository.findBySlug(slug).map(this::toDomainWithSteps);
  }

  @Override
  public Optional<Project> findByPublicId(String publicId) {
    return projectRepository.findByPublicId(publicId).map(this::toDomainWithSteps);
  }

  @Override
  public List<Project> findAll() {
    return projectRepository.findAllByOrderByCreatedAtAsc().stream()
        .map(this::toDomainWithSteps)
        .toList();
  }

  @Override
  @Transactional
  public Project insert(Project project) {
    try {
      ProjectEntity saved =
          projectRepository.saveAndFlush(projectEntityMapper.toNewEntity(project));
      // Per-step rows reference projects.public_id (FK); insert only after the project row flushed.
      replaceStepRunnerKinds(saved.getPublicId(), project.stepRunnerKinds());
      return projectEntityMapper.toDomain(saved, project.stepRunnerKinds());
    } catch (DataIntegrityViolationException violation) {
      throw mapViolation(project, violation);
    }
  }

  @Override
  @Transactional
  public Project update(Project project) {
    ProjectEntity entity =
        projectRepository
            .findByPublicId(project.publicId())
            .orElseThrow(
                () -> {
                  log.warn(
                      "project update rejected: unknown projectId={}",
                      MdcKeys.sanitizeForLog(project.publicId()));
                  Map<String, Object> details = new LinkedHashMap<>();
                  details.put("projectId", project.publicId());
                  return new DomainException(
                      DomainErrorCode.PROJECT_NOT_FOUND,
                      "No project found for update: " + project.publicId(),
                      details);
                });
    projectEntityMapper.applyEditableColumns(entity, project);
    try {
      ProjectEntity saved = projectRepository.saveAndFlush(entity);
      // Story 3e-4 (AC6) — full-replace semantics: the submitted map is authoritative.
      replaceStepRunnerKinds(saved.getPublicId(), project.stepRunnerKinds());
      return projectEntityMapper.toDomain(saved, project.stepRunnerKinds());
    } catch (DataIntegrityViolationException violation) {
      throw mapViolation(project, violation);
    }
  }

  /** Map the entity plus its {@code project_runner_kinds} child rows into the domain aggregate. */
  private Project toDomainWithSteps(ProjectEntity entity) {
    return projectEntityMapper.toDomain(entity, loadStepRunnerKinds(entity.getPublicId()));
  }

  /**
   * Load the per-step rows for a project, ordered by {@code step} value (the repository's {@code
   * OrderByStepAsc}). The resulting iteration order is deterministic but alphabetical by step — NOT
   * the request's original insertion order — and is what {@code ProjectResponse} surfaces on the
   * wire.
   */
  private Map<ProjectRunnerStep, RunnerKind> loadStepRunnerKinds(String projectPublicId) {
    Map<ProjectRunnerStep, RunnerKind> map = new LinkedHashMap<>();
    for (ProjectRunnerKindEntity row :
        projectRunnerKindRepository.findByProjectIdOrderByStepAsc(projectPublicId)) {
      map.put(row.getStep(), row.getRunnerKind());
    }
    return map;
  }

  /**
   * Story 3e-4 (AC6) — full-replace the per-step rows for a project: delete the existing set then
   * insert the submitted map (empty map → zero rows). Both run in the caller's
   * {@code @Transactional} (insert/update), so the delete+insert is atomic.
   */
  private void replaceStepRunnerKinds(
      String projectPublicId, Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds) {
    projectRunnerKindRepository.deleteByProjectId(projectPublicId);
    if (stepRunnerKinds == null || stepRunnerKinds.isEmpty()) {
      return;
    }
    List<ProjectRunnerKindEntity> rows = new ArrayList<>();
    stepRunnerKinds.forEach(
        (step, kind) -> rows.add(new ProjectRunnerKindEntity(projectPublicId, step, kind)));
    projectRunnerKindRepository.saveAllAndFlush(rows);
  }

  @Override
  @Transactional
  public int backfillNullProjectIds(String defaultProjectPublicId) {
    return workflowRunRepository.backfillNullProjectIds(defaultProjectPublicId);
  }

  @Override
  public Optional<String> findProjectIdForRun(String runPublicId) {
    return workflowRunRepository.findProjectIdByPublicId(runPublicId);
  }

  /**
   * Story 3c-8 (AC4) — translate a V17 {@code projects} constraint violation to a typed {@link
   * DomainException} so the REST create/update path returns a meaningful 4xx instead of an opaque
   * 500. Mirrors {@code ProjectCredentialPersistenceAdapter.mapViolation} exactly (constraint-name
   * switch, {@link MdcKeys#sanitizeForLog} on logged ids, never logs a secret — projects carry
   * none). A {@code uq_projects_slug} collision is the user-facing {@code PROJECT_SLUG_CONFLICT}; a
   * {@code uq_projects_public_id} collision is an internal id-generation defect; an unknown
   * violation chains the cause into {@code INTERNAL_ERROR}.
   */
  private DomainException mapViolation(Project project, DataIntegrityViolationException cause) {
    String message =
        cause.getMostSpecificCause() == null ? null : cause.getMostSpecificCause().getMessage();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("projectId", project.publicId());
    details.put("slug", project.slug());

    if (message != null && message.contains("uq_projects_slug")) {
      details.put("constraint", "uq_projects_slug");
      details.put("reason", "slug_conflict");
      log.warn(
          "project write conflict: duplicate slug projectId={} slug={} constraint=uq_projects_slug",
          MdcKeys.sanitizeForLog(project.publicId()),
          MdcKeys.sanitizeForLog(project.slug()));
      return new DomainException(
          DomainErrorCode.PROJECT_SLUG_CONFLICT,
          "A project already exists with slug " + project.slug(),
          details);
    }
    if (message != null && message.contains("uq_projects_public_id")) {
      // A prj_<...> public_id collision is an internal ID-generation defect, not a user error.
      details.put("constraint", "uq_projects_public_id");
      details.put("reason", "public_id_collision");
      log.error(
          "project write failed: public_id collision projectId={} constraint=uq_projects_public_id",
          MdcKeys.sanitizeForLog(project.publicId()));
      return new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "projects write failed: public_id collision",
          details,
          cause);
    }
    details.put("reason", "project_constraint_violation");
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR, "projects write failed", details, cause);
  }
}
