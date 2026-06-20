package org.dradgo.adapters.persistence;

import java.util.Optional;
import org.dradgo.adapters.persistence.mapper.ProjectEntityMapper;
import org.dradgo.adapters.persistence.repository.ProjectRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.domain.project.Project;
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

  private final ProjectRepository projectRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final ProjectEntityMapper projectEntityMapper;

  public ProjectPersistenceAdapter(
      ProjectRepository projectRepository,
      WorkflowRunRepository workflowRunRepository,
      ProjectEntityMapper projectEntityMapper) {
    this.projectRepository = projectRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.projectEntityMapper = projectEntityMapper;
  }

  @Override
  public Optional<Project> findBySlug(String slug) {
    return projectRepository.findBySlug(slug).map(projectEntityMapper::toDomain);
  }

  @Override
  public Optional<Project> findByPublicId(String publicId) {
    return projectRepository.findByPublicId(publicId).map(projectEntityMapper::toDomain);
  }

  @Override
  @Transactional
  public Project insert(Project project) {
    return projectEntityMapper.toDomain(
        projectRepository.saveAndFlush(projectEntityMapper.toNewEntity(project)));
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
}
