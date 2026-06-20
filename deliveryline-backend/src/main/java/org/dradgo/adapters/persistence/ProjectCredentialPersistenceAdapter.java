package org.dradgo.adapters.persistence;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ProjectCredentialEntity;
import org.dradgo.adapters.persistence.mapper.ProjectCredentialEntityMapper;
import org.dradgo.adapters.persistence.repository.ProjectCredentialRepository;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.ProjectCredentialRecordPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.ProjectCredential;
import org.dradgo.domain.registry.ConnectorRole;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3c-5 — persistence-adapter implementation of {@link ProjectCredentialRecordPort}. Mirrors
 * {@code IntegrationLinkPersistenceAdapter}: {@code PublicIdPrefixes.require} on insert, {@code
 * saveAndFlush}, and a {@code catch (DataIntegrityViolationException)} that maps V17 constraint
 * names to typed {@link DomainException}s.
 *
 * <p><strong>Secret-hostile.</strong> Logs the credential {@code public_id}, the {@code project_id}
 * (sanitized via {@link MdcKeys#sanitizeForLog}), and the {@code role} only — never the {@code
 * ciphertext}, {@code key_id}, or any decrypted material.
 */
@Component
public class ProjectCredentialPersistenceAdapter implements ProjectCredentialRecordPort {

  private static final Logger log =
      LoggerFactory.getLogger(ProjectCredentialPersistenceAdapter.class);

  private final ProjectCredentialRepository repository;
  private final ProjectCredentialEntityMapper mapper;

  public ProjectCredentialPersistenceAdapter(
      ProjectCredentialRepository repository, ProjectCredentialEntityMapper mapper) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProjectCredential> findActive(String projectPublicId, ConnectorRole role) {
    validateLookupArgs(projectPublicId, role);
    return repository
        .findByProjectIdAndConnectorRoleAndArchivedAtIsNull(projectPublicId, role.value())
        .map(mapper::toDomain);
  }

  @Override
  @Transactional
  public int archiveActive(String projectPublicId, ConnectorRole role, OffsetDateTime archivedAt) {
    validateLookupArgs(projectPublicId, role);
    Objects.requireNonNull(archivedAt, "archivedAt");
    return repository.archiveActive(projectPublicId, role.value(), archivedAt);
  }

  @Override
  @Transactional
  public ProjectCredential insert(ProjectCredential newRow) {
    Objects.requireNonNull(newRow, "newRow");
    PublicIdPrefixes.require(newRow.publicId(), PublicIdPrefixes.PROJECT_CREDENTIAL);

    ProjectCredentialEntity entity = new ProjectCredentialEntity();
    entity.setPublicId(newRow.publicId());
    entity.setProjectId(newRow.projectPublicId());
    entity.setConnectorRole(newRow.role());
    entity.setCiphertext(newRow.ciphertext());
    entity.setKeyId(newRow.keyId());
    entity.setAlgo(newRow.algo());
    entity.setCreatedAt(newRow.createdAt());
    entity.setArchivedAt(newRow.archivedAt());

    ProjectCredentialEntity persisted;
    try {
      persisted = repository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException violation) {
      throw mapViolation(newRow, violation);
    }
    log.info(
        "persisting project_credential publicId={} projectId={} role={}",
        persisted.getPublicId(),
        MdcKeys.sanitizeForLog(newRow.projectPublicId()),
        newRow.role().value());
    return mapper.toDomain(persisted);
  }

  private static void validateLookupArgs(String projectPublicId, ConnectorRole role) {
    if (projectPublicId == null || projectPublicId.isBlank()) {
      throw new IllegalArgumentException("projectPublicId must be non-blank");
    }
    Objects.requireNonNull(role, "role");
  }

  private DomainException mapViolation(
      ProjectCredential newRow, DataIntegrityViolationException cause) {
    String message =
        cause.getMostSpecificCause() == null ? null : cause.getMostSpecificCause().getMessage();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("projectId", newRow.projectPublicId());
    details.put("credentialId", newRow.publicId());
    details.put("role", newRow.role().value());

    if (message != null && message.contains("fk_project_credentials_projects")) {
      details.put("constraint", "fk_project_credentials_projects");
      details.put("reason", "project_not_found");
      log.warn(
          "project_credential insert rejected: unknown project projectId={} role={} constraint=fk_project_credentials_projects",
          MdcKeys.sanitizeForLog(newRow.projectPublicId()),
          newRow.role().value());
      return new DomainException(
          DomainErrorCode.PROJECT_NOT_FOUND,
          "No project found for project_credentials insert: " + newRow.projectPublicId(),
          details);
    }
    if (message != null && message.contains("uq_project_credentials_project_role")) {
      details.put("constraint", "uq_project_credentials_project_role");
      details.put("reason", "active_credential_conflict");
      log.warn(
          "project_credential insert conflict projectId={} role={} cause={}",
          MdcKeys.sanitizeForLog(newRow.projectPublicId()),
          newRow.role().value(),
          cause.getMostSpecificCause().getClass().getSimpleName());
      return new DomainException(
          DomainErrorCode.CONCURRENT_TRANSITION_CONFLICT,
          "Concurrent active credential for project/role",
          details);
    }
    if (message != null && message.contains("uq_project_credentials_public_id")) {
      // A cred_<32hex> public_id collision is an internal ID-generation defect, not a concurrent
      // active-credential write — surface it as INTERNAL_ERROR so it is never mistaken for a
      // retriable conflict.
      details.put("constraint", "uq_project_credentials_public_id");
      details.put("reason", "credential_public_id_collision");
      log.error(
          "project_credential insert failed: public_id collision projectId={} role={} constraint=uq_project_credentials_public_id",
          MdcKeys.sanitizeForLog(newRow.projectPublicId()),
          newRow.role().value());
      return new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "project_credentials insert failed: public_id collision",
          details,
          cause);
    }
    details.put("reason", "project_credential_constraint_violation");
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR, "project_credentials insert failed", details, cause);
  }
}
