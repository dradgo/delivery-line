package org.dradgo.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.integration.repohost.github.GitHubMockAdapter;
import org.dradgo.adapters.integration.repohost.gitlab.GitLabRepositoryHostStubAdapter;
import org.dradgo.adapters.integration.ticketsource.gitlab.GitLabTicketSourceStubAdapter;
import org.dradgo.adapters.integration.ticketsource.linear.LinearMockAdapter;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3c-3 (AC9) — wiring IT proving that under the default mock profiles the {@link
 * ProjectConnectorResolver}'s kind&rarr;adapter maps contain the active vendor kind
 * <strong>plus</strong> the always-on {@code GITLAB} stub, and that single-injection consumers
 * still boot (so the {@code @Primary} on the active vendor adapter resolved the ambiguity
 * introduced by the always-on stub beans). Named {@code *IT} so it runs under Failsafe
 * (Testcontainers), never the no-Docker Surefire tier.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
class ProjectConnectorResolverWiringIT {

  @Autowired private ProjectConnectorResolver resolver;

  // Single-injection consumers: their successful construction is the proof that @Primary keeps the
  // single TicketSourceAdapter / RepositoryHostAdapter injection points unambiguous despite the
  // always-on GITLAB stub beans.
  @Autowired private IntegrationLinkService integrationLinkService;
  @Autowired private RepositoryWorkspaceService repositoryWorkspaceService;

  @Test
  void resolverMapsContainActiveVendorKindPlusGitlabStub() {
    assertThat(resolver.resolveTicketSource(project(ConnectorKind.LINEAR, ConnectorKind.GITHUB)))
        .isInstanceOf(LinearMockAdapter.class);
    assertThat(resolver.resolveTicketSource(project(ConnectorKind.GITLAB, ConnectorKind.GITHUB)))
        .isInstanceOf(GitLabTicketSourceStubAdapter.class);
    assertThat(resolver.resolveRepositoryHost(project(ConnectorKind.LINEAR, ConnectorKind.GITHUB)))
        .isInstanceOf(GitHubMockAdapter.class);
    assertThat(resolver.resolveRepositoryHost(project(ConnectorKind.LINEAR, ConnectorKind.GITLAB)))
        .isInstanceOf(GitLabRepositoryHostStubAdapter.class);
  }

  @Test
  void singleInjectionConsumersBootWithStubsPresent() {
    assertThat(integrationLinkService).isNotNull();
    assertThat(repositoryWorkspaceService).isNotNull();
  }

  private static Project project(ConnectorKind ticketSourceKind, ConnectorKind repoHostKind) {
    return new Project(
        "prj_wiring01",
        "Wiring Project",
        "wiring-project",
        ProjectStatus.ACTIVE,
        null,
        ticketSourceKind,
        repoHostKind,
        false,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }
}
