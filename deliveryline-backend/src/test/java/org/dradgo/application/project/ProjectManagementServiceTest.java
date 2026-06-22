package org.dradgo.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.RunnerKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Story 3c-8 (AC1/AC4/AC6) — fast unit coverage for {@link ProjectManagementService}. */
class ProjectManagementServiceTest {

  private final ProjectStore store = org.mockito.Mockito.mock(ProjectStore.class);
  private final ProjectManagementService service = new ProjectManagementService(store);

  private static Project project(String publicId, String slug, ProjectStatus status) {
    return new Project(
        publicId,
        "Acme",
        slug,
        status,
        "https://github.com/acme/widgets",
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-21T00:00:00Z"),
        null);
  }

  @Test
  void createProjectGeneratesPrjIdActiveStatusAndParsedKinds() {
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    CreateProjectCommand command =
        new CreateProjectCommand(
            "Acme",
            "acme",
            "https://github.com/acme/widgets",
            "linear",
            "github",
            true,
            null,
            "alex");

    Project created = service.createProject(command);

    ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
    verify(store).insert(captor.capture());
    Project inserted = captor.getValue();
    assertThat(inserted.publicId()).startsWith("prj_");
    assertThat(inserted.status()).isEqualTo(ProjectStatus.ACTIVE);
    assertThat(inserted.ticketSourceKind()).isEqualTo(ConnectorKind.LINEAR);
    assertThat(inserted.repoHostKind()).isEqualTo(ConnectorKind.GITHUB);
    assertThat(inserted.openspecEnabled()).isTrue();
    assertThat(inserted.createdAt()).isNotNull();
    assertThat(created.slug()).isEqualTo("acme");
  }

  @Test
  void createProjectBlankRepositoryUrlNormalizesToNull() {
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    service.createProject(
        new CreateProjectCommand("Acme", "acme", "  ", "linear", "github", false, null, "alex"));

    ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
    verify(store).insert(captor.capture());
    assertThat(captor.getValue().repositoryUrl()).isNull();
  }

  @Test
  void createProjectParsesRunnerKindOverride() {
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Project created =
        service.createProject(
            new CreateProjectCommand(
                "Acme", "acme", null, "linear", "github", false, "manual", null, "alex"));

    assertThat(created.runnerKind()).isEqualTo(RunnerKind.MANUAL);
  }

  @Test
  void createProjectRejectsUnknownRunnerKind() {
    assertThatThrownBy(
            () ->
                service.createProject(
                    new CreateProjectCommand(
                        "Acme", "acme", null, "linear", "github", false, "bogus", null, "alex")))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.UNKNOWN_REGISTRY_VALUE);
    verify(store, never()).insert(any());
  }

  @Test
  void createProjectUnknownKindThrows() {
    assertThatThrownBy(
            () ->
                service.createProject(
                    new CreateProjectCommand(
                        "Acme", "acme", null, "bitbucket", "github", false, null, "alex")))
        .isInstanceOf(DomainException.class);
    verify(store, never()).insert(any());
  }

  @Test
  void createProjectWithReservedDefaultSlugRejected() {
    assertThatThrownBy(
            () ->
                service.createProject(
                    new CreateProjectCommand(
                        "Mine",
                        DefaultProjectSeeder.DEFAULT_PROJECT_SLUG,
                        null,
                        "linear",
                        "github",
                        false,
                        null,
                        "alex")))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.PROJECT_SLUG_CONFLICT);
    verify(store, never()).insert(any());
  }

  @Test
  void getProjectMissingThrowsProjectNotFound() {
    when(store.findByPublicId("prj_missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getProject("prj_missing"))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.PROJECT_NOT_FOUND);
  }

  @Test
  void updateProjectPreservesSlugStatusAndCreatedAt() {
    Project existing = project("prj_acme0001", "acme", ProjectStatus.DISABLED);
    when(store.findByPublicId("prj_acme0001")).thenReturn(Optional.of(existing));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    service.updateProject(
        "prj_acme0001",
        new UpdateProjectCommand(
            "Renamed", "https://github.com/x/y", "github", "linear", true, "alex"));

    ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
    verify(store).update(captor.capture());
    Project mutated = captor.getValue();
    assertThat(mutated.name()).isEqualTo("Renamed");
    assertThat(mutated.slug()).isEqualTo("acme"); // immutable
    assertThat(mutated.status()).isEqualTo(ProjectStatus.DISABLED); // not changed by update
    assertThat(mutated.createdAt()).isEqualTo(existing.createdAt());
    assertThat(mutated.ticketSourceKind()).isEqualTo(ConnectorKind.GITHUB);
    assertThat(mutated.repoHostKind()).isEqualTo(ConnectorKind.LINEAR);
    assertThat(mutated.openspecEnabled()).isTrue();
  }

  @Test
  void updateProjectChangesRunnerKindOverride() {
    Project existing = project("prj_acme0001", "acme", ProjectStatus.ACTIVE);
    when(store.findByPublicId("prj_acme0001")).thenReturn(Optional.of(existing));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Project updated =
        service.updateProject(
            "prj_acme0001",
            new UpdateProjectCommand("Acme", null, "linear", "github", false, "claude", "alex"));

    assertThat(updated.runnerKind()).isEqualTo(RunnerKind.CLAUDE);
  }

  @Test
  void disableProjectSetsDisabledStatus() {
    Project existing = project("prj_acme0001", "acme", ProjectStatus.ACTIVE);
    when(store.findByPublicId("prj_acme0001")).thenReturn(Optional.of(existing));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    service.disableProject("prj_acme0001");

    ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
    verify(store).update(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo(ProjectStatus.DISABLED);
  }

  @Test
  void enableProjectSetsActiveStatus() {
    Project existing = project("prj_acme0001", "acme", ProjectStatus.DISABLED);
    when(store.findByPublicId("prj_acme0001")).thenReturn(Optional.of(existing));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    service.enableProject("prj_acme0001");

    ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
    verify(store).update(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo(ProjectStatus.ACTIVE);
  }

  @Test
  void disableDefaultProjectIsRejected() {
    Project defaultProject =
        project(
            DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID,
            DefaultProjectSeeder.DEFAULT_PROJECT_SLUG,
            ProjectStatus.ACTIVE);
    when(store.findByPublicId(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID))
        .thenReturn(Optional.of(defaultProject));

    assertThatThrownBy(() -> service.disableProject(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    verify(store, never()).update(any());
  }

  @Test
  void allowedActionsActiveNonDefaultIncludesDisableNotEnable() {
    List<String> actions =
        ProjectManagementService.allowedActionsFor(
            project("prj_acme0001", "acme", ProjectStatus.ACTIVE));
    assertThat(actions).containsExactly("edit", "disable", "set_credential", "test_connection");
  }

  @Test
  void allowedActionsActiveDefaultOmitsDisable() {
    List<String> actions =
        ProjectManagementService.allowedActionsFor(
            project(
                DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID,
                DefaultProjectSeeder.DEFAULT_PROJECT_SLUG,
                ProjectStatus.ACTIVE));
    assertThat(actions).containsExactly("edit", "set_credential", "test_connection");
  }

  @Test
  void allowedActionsDisabledOffersEnableNotDisable() {
    List<String> actions =
        ProjectManagementService.allowedActionsFor(
            project("prj_acme0001", "acme", ProjectStatus.DISABLED));
    assertThat(actions).containsExactly("edit", "enable", "set_credential", "test_connection");
  }
}
