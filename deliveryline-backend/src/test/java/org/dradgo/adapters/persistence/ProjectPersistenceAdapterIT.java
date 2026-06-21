package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3c-6 (AC7/AC8/AC9) — real-Postgres round-trip for {@link ProjectPersistenceAdapter} (the
 * {@link ProjectStore} port impl): insert &rarr; findBySlug/findByPublicId, the getter-side {@code
 * PersistedRegistryValues.project*} parsing of non-default enum text ({@code disabled}/{@code
 * gitlab}), and {@code findProjectIdForRun} (bound run, null-{@code project_id} run, unknown run
 * all resolve correctly). The bogus-DB-value fail-fast is covered directly by {@code
 * RegistryContractTest} (the {@code ck_projects_*} CHECKs forbid inserting an out-of-set value to
 * exercise it here). Named {@code *IT} so Failsafe runs it
 * ([[springboot-testcontainers-test-must-be-IT]]).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@Transactional
class ProjectPersistenceAdapterIT {

  @Autowired private ProjectStore projectStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void insertRoundTripsThroughFindBySlugAndPublicId() {
    String publicId = "prj_itrt" + suffix();
    String slug = "it-roundtrip-" + suffix();
    Project inserted =
        projectStore.insert(
            new Project(
                publicId,
                "IT Round Trip",
                slug,
                ProjectStatus.ACTIVE,
                "octo/it-repo",
                ConnectorKind.LINEAR,
                ConnectorKind.GITHUB,
                true,
                null,
                false,
                OffsetDateTime.now(ZoneOffset.UTC),
                null));

    // DB-assigned createdAt comes back on the inserted aggregate.
    assertThat(inserted.createdAt()).isNotNull();

    Project bySlug = projectStore.findBySlug(slug).orElseThrow();
    Project byPublicId = projectStore.findByPublicId(publicId).orElseThrow();
    assertThat(bySlug.publicId()).isEqualTo(publicId);
    assertThat(byPublicId.slug()).isEqualTo(slug);
    assertThat(byPublicId.repositoryUrl()).isEqualTo("octo/it-repo");
    assertThat(byPublicId.ticketSourceKind()).isEqualTo(ConnectorKind.LINEAR);
    assertThat(byPublicId.repoHostKind()).isEqualTo(ConnectorKind.GITHUB);
    assertThat(byPublicId.openspecEnabled()).isTrue();
    assertThat(byPublicId.status()).isEqualTo(ProjectStatus.ACTIVE);
  }

  @Test
  void getterSideRegistryParsingHandlesDisabledStatusAndGitlabKinds() {
    String publicId = "prj_itnd" + suffix();
    String slug = "it-nondefault-" + suffix();
    projectStore.insert(
        new Project(
            publicId,
            "IT Non Default",
            slug,
            ProjectStatus.DISABLED,
            null,
            ConnectorKind.GITLAB,
            ConnectorKind.GITLAB,
            false,
            null,
            false,
            OffsetDateTime.now(ZoneOffset.UTC),
            null));

    Project loaded = projectStore.findByPublicId(publicId).orElseThrow();
    assertThat(loaded.status()).isEqualTo(ProjectStatus.DISABLED);
    assertThat(loaded.ticketSourceKind()).isEqualTo(ConnectorKind.GITLAB);
    assertThat(loaded.repoHostKind()).isEqualTo(ConnectorKind.GITLAB);
    assertThat(loaded.repositoryUrl()).isNull();
  }

  @Test
  void findProjectIdForRunResolvesBoundNullAndUnknownRuns() {
    String publicId = "prj_itfp" + suffix();
    String slug = "it-findpid-" + suffix();
    projectStore.insert(
        new Project(
            publicId,
            "IT Find ProjectId",
            slug,
            ProjectStatus.ACTIVE,
            null,
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            OffsetDateTime.now(ZoneOffset.UTC),
            null));

    String boundRun = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, project_id) values (?, ?, ?)",
        boundRun,
        "Inbox",
        publicId);
    String nullRun = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, project_id) values (?, ?, null)",
        nullRun,
        "Inbox");

    assertThat(projectStore.findProjectIdForRun(boundRun)).contains(publicId);
    assertThat(projectStore.findProjectIdForRun(nullRun)).isEmpty();
    assertThat(projectStore.findProjectIdForRun("run_doesnotexist01")).isEmpty();
  }

  @Test
  void findAllReturnsInsertedProjectsCreationOrdered() {
    String publicIdA = "prj_itfa" + suffix();
    String publicIdB = "prj_itfb" + suffix();
    projectStore.insert(
        new Project(
            publicIdA,
            "IT FindAll A",
            "it-findall-a-" + suffix(),
            ProjectStatus.ACTIVE,
            null,
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            OffsetDateTime.now(ZoneOffset.UTC),
            null));
    projectStore.insert(
        new Project(
            publicIdB,
            "IT FindAll B",
            "it-findall-b-" + suffix(),
            ProjectStatus.DISABLED,
            null,
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            OffsetDateTime.now(ZoneOffset.UTC),
            null));

    List<String> ids = projectStore.findAll().stream().map(Project::publicId).toList();
    assertThat(ids).contains(publicIdA, publicIdB);
    // The two we inserted are creation-ordered relative to each other (A before B).
    assertThat(ids.indexOf(publicIdA)).isLessThan(ids.indexOf(publicIdB));
  }

  @Test
  void updateMutatesEditableColumnsAndPreservesCreatedAt() {
    String publicId = "prj_itup" + suffix();
    Project inserted =
        projectStore.insert(
            new Project(
                publicId,
                "IT Update",
                "it-update-" + suffix(),
                ProjectStatus.ACTIVE,
                "octo/before",
                ConnectorKind.LINEAR,
                ConnectorKind.GITHUB,
                false,
                null,
                false,
                OffsetDateTime.now(ZoneOffset.UTC),
                null));

    Project mutated =
        new Project(
            publicId,
            "IT Update Renamed",
            inserted.slug(),
            ProjectStatus.DISABLED,
            "octo/after",
            ConnectorKind.GITHUB,
            ConnectorKind.LINEAR,
            true,
            null,
            false,
            inserted.createdAt(),
            null);
    projectStore.update(mutated);

    Project loaded = projectStore.findByPublicId(publicId).orElseThrow();
    assertThat(loaded.name()).isEqualTo("IT Update Renamed");
    assertThat(loaded.status()).isEqualTo(ProjectStatus.DISABLED);
    assertThat(loaded.repositoryUrl()).isEqualTo("octo/after");
    assertThat(loaded.ticketSourceKind()).isEqualTo(ConnectorKind.GITHUB);
    assertThat(loaded.repoHostKind()).isEqualTo(ConnectorKind.LINEAR);
    assertThat(loaded.openspecEnabled()).isTrue();
    assertThat(loaded.createdAt()).isEqualTo(inserted.createdAt());
  }

  @Test
  void updateUnknownProjectThrowsProjectNotFound() {
    Project ghost =
        new Project(
            "prj_itghost" + suffix(),
            "Ghost",
            "it-ghost-" + suffix(),
            ProjectStatus.ACTIVE,
            null,
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            OffsetDateTime.now(ZoneOffset.UTC),
            null);
    assertThatThrownBy(() -> projectStore.update(ghost))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.PROJECT_NOT_FOUND);
  }

  @Test
  void duplicateSlugInsertMapsToProjectSlugConflict() {
    String slug = "it-dupe-" + suffix();
    projectStore.insert(
        new Project(
            "prj_itd1" + suffix(),
            "Dupe One",
            slug,
            ProjectStatus.ACTIVE,
            null,
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            OffsetDateTime.now(ZoneOffset.UTC),
            null));

    assertThatThrownBy(
            () ->
                projectStore.insert(
                    new Project(
                        "prj_itd2" + suffix(),
                        "Dupe Two",
                        slug,
                        ProjectStatus.ACTIVE,
                        null,
                        ConnectorKind.LINEAR,
                        ConnectorKind.GITHUB,
                        false,
                        null,
                        false,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.PROJECT_SLUG_CONFLICT);
  }

  private static String suffix() {
    return Long.toHexString(System.nanoTime());
  }
}
