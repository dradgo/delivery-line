package org.dradgo.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.integration.repohost.RepositoryHostProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceProperties;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
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
 * Story 3c-6 (AC1/AC2/AC5/AC9) — real-Postgres integration coverage for {@link
 * DefaultProjectSeeder}: the {@code default} project is seeded at startup from the global config
 * (values mirror the bound properties), no {@code project_credentials} row is written (R4), the
 * seeder is idempotent on restart (one {@code default} row), and the backfill binds existing
 * null-{@code project_id} runs to {@code prj_default}. Named {@code *IT} so Failsafe (Docker tier)
 * runs it and Surefire's no-Docker fast tier excludes it
 * ([[springboot-testcontainers-test-must-be-IT]]).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@Transactional
class DefaultProjectSeederIT {

  @Autowired private DefaultProjectSeeder seeder;
  @Autowired private ProjectStore projectStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowProperties workflowProperties;
  @Autowired private TicketSourceProperties ticketSourceProperties;
  @Autowired private RepositoryHostProperties repositoryHostProperties;
  @Autowired private RunnerProperties runnerProperties;

  @Test
  void seedsDefaultProjectFromGlobalConfigAtStartup() {
    // The seeder fired on ApplicationReadyEvent at context startup.
    Optional<Project> seeded = projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG);
    assertThat(seeded).isPresent();
    Project project = seeded.get();
    assertThat(project.publicId()).isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID);
    assertThat(project.name()).isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_NAME);
    assertThat(project.status()).isEqualTo(ProjectStatus.ACTIVE);

    // Field values mirror the bound global config (the seed source).
    assertThat(project.repositoryUrl()).isEqualTo(workflowProperties.repos().url());
    assertThat(project.ticketSourceKind())
        .isEqualTo(ConnectorKind.fromValue(ticketSourceProperties.kind(), "test.ticket"));
    assertThat(project.repoHostKind())
        .isEqualTo(ConnectorKind.fromValue(repositoryHostProperties.kind(), "test.repo"));
    assertThat(project.openspecEnabled()).isEqualTo(runnerProperties.openSpecEnabled());
    assertThat(project.createdAt()).isNotNull();
  }

  @Test
  void writesNoProjectCredentialsRow() {
    Integer credentialRows =
        jdbcTemplate.queryForObject("select count(*) from project_credentials", Integer.class);
    assertThat(credentialRows).isZero();
  }

  @Test
  void seederIsIdempotentOnRestart() {
    // Re-invoking the listener method must not duplicate the default project (slug short-circuit).
    seeder.seedDefaultProjectOnStartup();

    Integer defaultRows =
        jdbcTemplate.queryForObject(
            "select count(*) from projects where slug = ?",
            Integer.class,
            DefaultProjectSeeder.DEFAULT_PROJECT_SLUG);
    assertThat(defaultRows).isEqualTo(1);
  }

  @Test
  void backfillsExistingNullProjectIdRunsToDefault() {
    String runPublicId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, project_id) values (?, ?, null)",
        runPublicId,
        "Inbox");

    seeder.seedDefaultProjectOnStartup();

    String boundProjectId =
        jdbcTemplate.queryForObject(
            "select project_id from workflow_runs where public_id = ?", String.class, runPublicId);
    assertThat(boundProjectId).isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID);
  }
}
