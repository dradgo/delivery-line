package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.ProjectEntity;
import org.dradgo.domain.project.Project;
import org.springframework.stereotype.Component;

/**
 * Story 3c-6 — entity &harr; domain translation for {@code projects}. Explicit (no MapStruct) to
 * match the {@code IntegrationLinkEntityMapper}/{@code WorkflowRunEntityMapper} pattern.
 *
 * <p>{@code toNewEntity} leaves {@code id} DB-generated but stamps {@code created_at} from the
 * domain {@code createdAt} (Hibernate does not refresh an entity after {@code saveAndFlush}, so a
 * DB-defaulted {@code insertable=false} column would read back null and trip the {@code Project}
 * record's non-null {@code createdAt} guard). The raw enum text is written through the entity's
 * typed setters (which call {@code RegistryValue.value()}), keeping the single registry-string
 * source.
 */
@Component
public class ProjectEntityMapper {

  public Project toDomain(ProjectEntity entity) {
    return new Project(
        entity.getPublicId(),
        entity.getName(),
        entity.getSlug(),
        entity.getStatus(),
        entity.getRepositoryUrl(),
        entity.getTicketSourceKind(),
        entity.getRepoHostKind(),
        entity.isOpenspecEnabled(),
        entity.getCreatedAt(),
        entity.getArchivedAt());
  }

  public ProjectEntity toNewEntity(Project project) {
    ProjectEntity entity = new ProjectEntity();
    entity.setPublicId(project.publicId());
    entity.setName(project.name());
    entity.setSlug(project.slug());
    entity.setStatus(project.status());
    entity.setRepositoryUrl(project.repositoryUrl());
    entity.setTicketSourceKind(project.ticketSourceKind());
    entity.setRepoHostKind(project.repoHostKind());
    entity.setOpenspecEnabled(project.openspecEnabled());
    entity.setCreatedAt(project.createdAt());
    entity.setArchivedAt(project.archivedAt());
    return entity;
  }
}
