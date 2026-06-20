package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.project.ProjectCredentialRecordPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.ProjectCredential;
import org.dradgo.domain.registry.ConnectorRole;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3c-5 (AC10) — real-Postgres round-trip for {@link ProjectCredentialPersistenceAdapter} (the
 * {@link ProjectCredentialRecordPort} impl): insert &rarr; findActive; archive frees the V17
 * partial unique slot so a rotated re-insert of the same {@code (project, role)} succeeds; a
 * two-active insert is rejected as a conflict; an unknown {@code project_id} maps the FK violation
 * to {@code PROJECT_NOT_FOUND}; and the one-active enforcement is a <em>partial</em> unique index
 * (in {@code pg_indexes}, not {@code pg_constraint}). Named {@code *IT} so Failsafe runs it
 * ([[springboot-testcontainers-test-must-be-IT]]).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@Transactional
class ProjectCredentialPersistenceAdapterIT {

  private static final AtomicLong SALT = new AtomicLong();

  @Autowired private ProjectCredentialRecordPort recordPort;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void insertRoundTripsThroughFindActive() {
    String projectId = seedProject();
    ProjectCredential inserted =
        recordPort.insert(newCredential(projectId, ConnectorRole.TICKET_SOURCE, "cipher-1"));

    Optional<ProjectCredential> active =
        recordPort.findActive(projectId, ConnectorRole.TICKET_SOURCE);
    assertThat(active).isPresent();
    assertThat(active.get().publicId()).isEqualTo(inserted.publicId());
    assertThat(active.get().role()).isEqualTo(ConnectorRole.TICKET_SOURCE);
    assertThat(active.get().keyId()).isEqualTo("mk_testkeyid");
    assertThat(active.get().archivedAt()).isNull();
    assertThat(new String(active.get().ciphertext(), StandardCharsets.UTF_8)).isEqualTo("cipher-1");
  }

  @Test
  void findActiveIsEmptyForAnUnconfiguredRole() {
    String projectId = seedProject();
    recordPort.insert(newCredential(projectId, ConnectorRole.TICKET_SOURCE, "cipher-1"));

    assertThat(recordPort.findActive(projectId, ConnectorRole.REPO_HOST)).isEmpty();
  }

  @Test
  void archiveFreesThePartialUniqueSlotForARotatedReinsert() {
    String projectId = seedProject();
    ProjectCredential first =
        recordPort.insert(newCredential(projectId, ConnectorRole.TICKET_SOURCE, "cipher-old"));

    int archived =
        recordPort.archiveActive(
            projectId, ConnectorRole.TICKET_SOURCE, OffsetDateTime.now(ZoneOffset.UTC));
    assertThat(archived).isEqualTo(1);

    // Re-inserting the same (project, role) now succeeds because the active slot was freed.
    ProjectCredential second =
        recordPort.insert(newCredential(projectId, ConnectorRole.TICKET_SOURCE, "cipher-new"));

    Optional<ProjectCredential> active =
        recordPort.findActive(projectId, ConnectorRole.TICKET_SOURCE);
    assertThat(active).isPresent();
    assertThat(active.get().publicId()).isEqualTo(second.publicId());
    assertThat(active.get().publicId()).isNotEqualTo(first.publicId());
    assertThat(new String(active.get().ciphertext(), StandardCharsets.UTF_8))
        .isEqualTo("cipher-new");
  }

  @Test
  void secondActiveInsertWithoutArchiveIsRejectedAsConflict() {
    String projectId = seedProject();
    recordPort.insert(newCredential(projectId, ConnectorRole.TICKET_SOURCE, "cipher-1"));

    assertThatThrownBy(
            () ->
                recordPort.insert(
                    newCredential(projectId, ConnectorRole.TICKET_SOURCE, "cipher-2")))
        .isInstanceOf(DomainException.class)
        .satisfies(
            error ->
                assertThat(((DomainException) error).errorCode())
                    .isEqualTo(DomainErrorCode.CONCURRENT_TRANSITION_CONFLICT));
  }

  @Test
  void insertAgainstUnknownProjectMapsFkViolationToProjectNotFound() {
    assertThatThrownBy(
            () ->
                recordPort.insert(
                    newCredential("prj_nosuchproject01", ConnectorRole.REPO_HOST, "cipher-x")))
        .isInstanceOf(DomainException.class)
        .satisfies(
            error ->
                assertThat(((DomainException) error).errorCode())
                    .isEqualTo(DomainErrorCode.PROJECT_NOT_FOUND));
  }

  @Test
  void oneActivePerRoleIsEnforcedByAPartialUniqueIndexNotAConstraint() {
    String indexDef =
        jdbcTemplate.queryForObject(
            "select indexdef from pg_indexes where indexname = ?",
            String.class,
            "uq_project_credentials_project_role");
    assertThat(indexDef).isNotNull();
    assertThat(indexDef).contains("UNIQUE");
    assertThat(indexDef).contains("archived_at IS NULL");

    // It is an index, not a unique CONSTRAINT (pg_constraint contype='u').
    Integer constraintCount =
        jdbcTemplate.queryForObject(
            "select count(*) from pg_constraint where conname = ? and contype = 'u'",
            Integer.class,
            "uq_project_credentials_project_role");
    assertThat(constraintCount).isZero();
  }

  private String seedProject() {
    String projectId = "prj_credit" + Long.toHexString(SALT.incrementAndGet()) + nano();
    jdbcTemplate.update(
        """
        insert into projects
          (public_id, name, slug, status, ticket_source_kind, repo_host_kind, openspec_enabled)
        values (?, ?, ?, 'active', 'linear', 'github', false)
        """,
        projectId,
        "Cred IT",
        "cred-it-" + Long.toHexString(SALT.incrementAndGet()) + nano());
    return projectId;
  }

  private static ProjectCredential newCredential(
      String projectId, ConnectorRole role, String cipherText) {
    return new ProjectCredential(
        PublicIdPrefixes.PROJECT_CREDENTIAL.next(),
        projectId,
        role,
        cipherText.getBytes(StandardCharsets.UTF_8),
        "mk_testkeyid",
        "AES-256-GCM",
        OffsetDateTime.now(ZoneOffset.UTC),
        null);
  }

  private static String nano() {
    return Long.toHexString(System.nanoTime());
  }
}
