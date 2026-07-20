package org.dradgo.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.project.ProjectConnectivityService.CheckResult;
import org.dradgo.application.project.ProjectConnectivityService.CheckStatus;
import org.dradgo.application.project.ProjectConnectivityService.TestConnectionResult;
import org.dradgo.application.security.CredentialCipherException;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Test;

/** Story 3c-8 (AC3/R1/R5) — fast unit coverage for {@link ProjectConnectivityService}. */
class ProjectConnectivityServiceTest {

  private static final String PROJECT_ID = "prj_acme0001";

  private final ProjectStore store = mock(ProjectStore.class);
  private final ProjectConnectorResolver resolver = mock(ProjectConnectorResolver.class);
  private final TicketSourceAdapter ticketSource = mock(TicketSourceAdapter.class);
  private final RepositoryHostAdapter repoHost = mock(RepositoryHostAdapter.class);
  private final ProjectConnectivityService service =
      new ProjectConnectivityService(store, resolver);

  private static Project project(String repositoryUrl) {
    return new Project(
        PROJECT_ID,
        "Acme",
        "acme",
        ProjectStatus.ACTIVE,
        repositoryUrl,
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-21T00:00:00Z"),
        null);
  }

  private void wireResolvers(Project project) {
    when(store.findByPublicId(PROJECT_ID)).thenReturn(Optional.of(project));
    when(resolver.resolveTicketSource(project)).thenReturn(ticketSource);
    when(resolver.resolveRepositoryHost(project)).thenReturn(repoHost);
    when(resolver.resolveConnectorSecret(any(), any())).thenReturn(Optional.empty());
  }

  private static CheckResult find(TestConnectionResult result, String check) {
    return result.checks().stream().filter(c -> c.check().equals(check)).findFirst().orElseThrow();
  }

  @Test
  void happyPathReturnsThreePasses() {
    Project project = project("https://github.com/acme/widgets");
    wireResolvers(project);
    when(ticketSource.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());
    when(repoHost.getCapabilities()).thenReturn(RepositoryHostCapabilities.githubDefaults());
    when(ticketSource.verifyConnectivity(nullable(String.class)))
        .thenReturn(ConnectivityResult.ok("authenticated"));
    when(repoHost.verifyConnectivity(nullable(RepositoryRef.class), nullable(String.class)))
        .thenReturn(ConnectivityResult.ok("reachable + authenticated"));

    TestConnectionResult result = service.testConnection(PROJECT_ID);

    assertThat(find(result, "repository_reachable").status()).isEqualTo(CheckStatus.PASS);
    assertThat(find(result, "ticket_source_auth").status()).isEqualTo(CheckStatus.PASS);
    assertThat(find(result, "repository_host_auth").status()).isEqualTo(CheckStatus.PASS);
  }

  @Test
  void degradedCapabilitiesSkipChecksNotFail() {
    Project project = project("https://github.com/acme/widgets");
    wireResolvers(project);
    when(ticketSource.getCapabilities())
        .thenReturn(TicketSourceCapabilities.noCreation(false, false, false));
    when(repoHost.getCapabilities())
        .thenReturn(new RepositoryHostCapabilities(false, false, false, false, false, false));

    TestConnectionResult result = service.testConnection(PROJECT_ID);

    assertThat(find(result, "repository_reachable").status()).isEqualTo(CheckStatus.SKIPPED);
    assertThat(find(result, "ticket_source_auth").status()).isEqualTo(CheckStatus.SKIPPED);
    assertThat(find(result, "repository_host_auth").status()).isEqualTo(CheckStatus.SKIPPED);
  }

  @Test
  void unreachableRepoHostYieldsFailWithSafeDetail() {
    Project project = project("https://github.com/acme/widgets");
    wireResolvers(project);
    when(ticketSource.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());
    when(repoHost.getCapabilities()).thenReturn(RepositoryHostCapabilities.githubDefaults());
    when(ticketSource.verifyConnectivity(nullable(String.class)))
        .thenReturn(ConnectivityResult.ok("ok"));
    when(repoHost.verifyConnectivity(nullable(RepositoryRef.class), nullable(String.class)))
        .thenReturn(ConnectivityResult.unreachable("github: host unreachable"));

    TestConnectionResult result = service.testConnection(PROJECT_ID);

    CheckResult reachable = find(result, "repository_reachable");
    assertThat(reachable.status()).isEqualTo(CheckStatus.FAIL);
    assertThat(reachable.detail()).doesNotContainIgnoringCase("token");
    assertThat(find(result, "repository_host_auth").status()).isEqualTo(CheckStatus.FAIL);
  }

  @Test
  void ticketSourceUnauthenticatedYieldsFail() {
    Project project = project("https://github.com/acme/widgets");
    wireResolvers(project);
    when(ticketSource.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());
    when(repoHost.getCapabilities()).thenReturn(RepositoryHostCapabilities.githubDefaults());
    when(ticketSource.verifyConnectivity(nullable(String.class)))
        .thenReturn(ConnectivityResult.unauthenticated("linear: authentication failed"));
    when(repoHost.verifyConnectivity(nullable(RepositoryRef.class), nullable(String.class)))
        .thenReturn(ConnectivityResult.ok("ok"));

    TestConnectionResult result = service.testConnection(PROJECT_ID);
    assertThat(find(result, "ticket_source_auth").status()).isEqualTo(CheckStatus.FAIL);
  }

  @Test
  void noRepositoryConfiguredSkipsReachableButStillChecksAuth() {
    Project project = project(null);
    wireResolvers(project);
    when(ticketSource.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());
    when(repoHost.getCapabilities()).thenReturn(RepositoryHostCapabilities.githubDefaults());
    when(ticketSource.verifyConnectivity(nullable(String.class)))
        .thenReturn(ConnectivityResult.ok("ok"));
    when(repoHost.verifyConnectivity(nullable(RepositoryRef.class), nullable(String.class)))
        .thenReturn(ConnectivityResult.ok("authenticated"));

    TestConnectionResult result = service.testConnection(PROJECT_ID);

    assertThat(find(result, "repository_reachable").status()).isEqualTo(CheckStatus.SKIPPED);
    assertThat(find(result, "repository_host_auth").status()).isEqualTo(CheckStatus.PASS);
  }

  @Test
  void tamperedRepoCredentialYieldsFailNotException() {
    Project project = project("https://github.com/acme/widgets");
    when(store.findByPublicId(PROJECT_ID)).thenReturn(Optional.of(project));
    when(resolver.resolveTicketSource(project)).thenReturn(ticketSource);
    when(resolver.resolveRepositoryHost(project)).thenReturn(repoHost);
    when(ticketSource.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());
    when(repoHost.getCapabilities()).thenReturn(RepositoryHostCapabilities.githubDefaults());
    when(ticketSource.verifyConnectivity(nullable(String.class)))
        .thenReturn(ConnectivityResult.ok("ok"));
    when(resolver.resolveConnectorSecret(eq(project), eq("ticket_source")))
        .thenReturn(Optional.empty());
    when(resolver.resolveConnectorSecret(eq(project), eq("repo_host")))
        .thenThrow(new CredentialCipherException("decrypt failed"));

    TestConnectionResult result = service.testConnection(PROJECT_ID);

    assertThat(find(result, "repository_reachable").status()).isEqualTo(CheckStatus.FAIL);
    assertThat(find(result, "repository_host_auth").status()).isEqualTo(CheckStatus.FAIL);
    assertThat(find(result, "repository_host_auth").detail()).doesNotContainIgnoringCase("secret");
  }

  @Test
  void resolvedStoredCredentialIsPassedToTheProbe() {
    Project project = project("https://github.com/acme/widgets");
    when(store.findByPublicId(PROJECT_ID)).thenReturn(Optional.of(project));
    when(resolver.resolveTicketSource(project)).thenReturn(ticketSource);
    when(resolver.resolveRepositoryHost(project)).thenReturn(repoHost);
    when(ticketSource.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());
    when(repoHost.getCapabilities()).thenReturn(RepositoryHostCapabilities.githubDefaults());
    when(resolver.resolveConnectorSecret(eq(project), eq("ticket_source")))
        .thenReturn(Optional.of("ts_secret"));
    when(resolver.resolveConnectorSecret(eq(project), eq("repo_host")))
        .thenReturn(Optional.of("rh_secret"));
    when(ticketSource.verifyConnectivity(nullable(String.class)))
        .thenReturn(ConnectivityResult.ok("ok"));
    when(repoHost.verifyConnectivity(nullable(RepositoryRef.class), nullable(String.class)))
        .thenReturn(ConnectivityResult.ok("ok"));

    service.testConnection(PROJECT_ID);

    // The stored secret resolved for each role is handed to that connector's probe (not discarded).
    verify(ticketSource).verifyConnectivity("ts_secret");
    verify(repoHost).verifyConnectivity(any(RepositoryRef.class), eq("rh_secret"));
  }

  @Test
  void missingProjectThrowsProjectNotFound() {
    when(store.findByPublicId("prj_missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.testConnection("prj_missing"))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.PROJECT_NOT_FOUND);
  }

  @Test
  void unsupportedConnectorKindPropagatesAs400() {
    Project project = project("https://github.com/acme/widgets");
    when(store.findByPublicId(PROJECT_ID)).thenReturn(Optional.of(project));
    when(resolver.resolveTicketSource(project))
        .thenThrow(
            new DomainException(
                DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND, "no adapter", java.util.Map.of()));

    assertThatThrownBy(() -> service.testConnection(PROJECT_ID))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND);
  }
}
