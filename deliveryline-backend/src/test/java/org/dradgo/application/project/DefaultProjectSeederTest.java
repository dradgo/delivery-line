package org.dradgo.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.repohost.RepositoryHostProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceProperties;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Story 3c-6 (AC1/AC2/AC5) — fast unit coverage for {@link DefaultProjectSeeder} (the {@link
 * ProjectStore} mocked): seed-from-global-config field mapping, idempotent skip when already
 * present, the concurrent-startup {@code DataIntegrityViolationException} backstop, and that the
 * (idempotent) backfill always runs. The real-DB no-credential-row / restart-parity / null-run
 * backfill assertions live in {@code DefaultProjectSeederIT}.
 */
class DefaultProjectSeederTest {

  private final ProjectStore projectStore = mock(ProjectStore.class);

  private DefaultProjectSeeder seeder(WorkflowProperties workflow, RunnerProperties runner) {
    return new DefaultProjectSeeder(
        workflow,
        new TicketSourceProperties("linear"),
        new RepositoryHostProperties("github"),
        runner,
        projectStore);
  }

  @Test
  void seedsDefaultFromGlobalConfigWhenAbsentThenBackfills() {
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.empty());
    when(projectStore.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(projectStore.backfillNullProjectIds(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID))
        .thenReturn(7);

    seeder(
            new WorkflowProperties(
                WorkflowProperties.Bot.empty(),
                WorkflowProperties.RepoConfig.of("octo/hello"),
                WorkflowProperties.LinearCompletionSync.defaults()),
            RunnerProperties.defaults())
        .seedDefaultProjectOnStartup();

    ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
    verify(projectStore).insert(captor.capture());
    Project seeded = captor.getValue();
    assertThat(seeded.publicId()).isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID);
    assertThat(seeded.slug()).isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG);
    assertThat(seeded.name()).isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_NAME);
    assertThat(seeded.status()).isEqualTo(ProjectStatus.ACTIVE);
    assertThat(seeded.repositoryUrl()).isEqualTo("octo/hello");
    assertThat(seeded.ticketSourceKind()).isEqualTo(ConnectorKind.LINEAR);
    assertThat(seeded.repoHostKind()).isEqualTo(ConnectorKind.GITHUB);
    assertThat(seeded.openspecEnabled()).isFalse();

    verify(projectStore).backfillNullProjectIds(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID);
  }

  @Test
  void skipsSeedWhenDefaultAlreadyPresentButStillBackfills() {
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(existingDefault()));

    seeder(WorkflowProperties.defaults(), RunnerProperties.defaults())
        .seedDefaultProjectOnStartup();

    verify(projectStore, never()).insert(any());
    verify(projectStore).backfillNullProjectIds(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID);
  }

  @Test
  void toleratesConcurrentSeedRaceAndStillBackfills() {
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.empty());
    when(projectStore.insert(any()))
        .thenThrow(new DataIntegrityViolationException("uq_projects_slug"));

    // Must not propagate the race; the backfill still runs.
    seeder(WorkflowProperties.defaults(), RunnerProperties.defaults())
        .seedDefaultProjectOnStartup();

    verify(projectStore).backfillNullProjectIds(eq(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID));
  }

  @Test
  void logsSeedAndBackfillBranches() {
    ListAppender<ILoggingEvent> appender = attachAppender();
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.empty());
    when(projectStore.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(projectStore.backfillNullProjectIds(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID))
        .thenReturn(3);

    seeder(
            new WorkflowProperties(
                WorkflowProperties.Bot.empty(),
                WorkflowProperties.RepoConfig.of("octo/hello"),
                WorkflowProperties.LinearCompletionSync.defaults()),
            RunnerProperties.defaults())
        .seedDefaultProjectOnStartup();

    List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(messages)
        .anyMatch(m -> m.contains("seeding default project from global config"))
        .anyMatch(m -> m.contains("backfilled 3 runs to default project"));
    // Secrets are never logged.
    assertThat(messages).noneMatch(m -> m.contains("API_TOKEN") || m.contains("GITHUB_TOKEN"));
  }

  @Test
  void logsSkipBranchWhenAlreadyPresent() {
    ListAppender<ILoggingEvent> appender = attachAppender();
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(existingDefault()));

    seeder(WorkflowProperties.defaults(), RunnerProperties.defaults())
        .seedDefaultProjectOnStartup();

    assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(m -> m.contains("default project already present, skipping seed"));
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(DefaultProjectSeeder.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.setLevel(Level.INFO);
    logger.addAppender(appender);
    return appender;
  }

  private static Project existingDefault() {
    return new Project(
        DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID,
        DefaultProjectSeeder.DEFAULT_PROJECT_NAME,
        DefaultProjectSeeder.DEFAULT_PROJECT_SLUG,
        ProjectStatus.ACTIVE,
        null,
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }
}
