package org.dradgo.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.integration.ticketsource.CommentResult;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 3c-3 (AC9) — fast unit coverage for {@link ProjectConnectorResolver}. Plain unit (no
 * {@code @SpringBootTest}/Testcontainers, Surefire-safe): the resolver is constructed with
 * hand-built {@code List}s of fake adapters declaring {@code LINEAR}/{@code GITHUB}/{@code GITLAB}.
 */
class ProjectConnectorResolverTest {

  // ---- kind -> adapter resolution (AC1/AC2) ----

  @Test
  void resolvesEachRegisteredKindToItsDeclaringAdapter() {
    FakeTicketSource linearTs = new FakeTicketSource(ConnectorKind.LINEAR);
    FakeTicketSource gitlabTs = new FakeTicketSource(ConnectorKind.GITLAB);
    FakeRepoHost githubRh = new FakeRepoHost(ConnectorKind.GITHUB);
    FakeRepoHost gitlabRh = new FakeRepoHost(ConnectorKind.GITLAB);
    ProjectConnectorResolver resolver =
        resolver(List.of(linearTs, gitlabTs), List.of(githubRh, gitlabRh));

    assertThat(resolver.resolveTicketSource(project(ConnectorKind.LINEAR, ConnectorKind.GITHUB)))
        .isSameAs(linearTs);
    assertThat(resolver.resolveTicketSource(project(ConnectorKind.GITLAB, ConnectorKind.GITHUB)))
        .isSameAs(gitlabTs);
    assertThat(resolver.resolveRepositoryHost(project(ConnectorKind.LINEAR, ConnectorKind.GITHUB)))
        .isSameAs(githubRh);
    assertThat(resolver.resolveRepositoryHost(project(ConnectorKind.LINEAR, ConnectorKind.GITLAB)))
        .isSameAs(gitlabRh);
  }

  // ---- Story 3c-7 (AC4 / R3) — findTicketSource: probe-before-resolve, never throws ----

  @Test
  void findTicketSourceReturnsAdapterWhenKindRegistered() {
    FakeTicketSource linearTs = new FakeTicketSource(ConnectorKind.LINEAR);
    ProjectConnectorResolver resolver = resolver(List.of(linearTs), List.of());

    assertThat(resolver.findTicketSource(project(ConnectorKind.LINEAR, ConnectorKind.GITHUB)))
        .contains(linearTs);
  }

  @Test
  void findTicketSourceReturnsEmptyWhenKindUnregisteredInsteadOfThrowing() {
    // R3 — the Linear completion-sync path relies on this NOT throwing UNSUPPORTED_CONNECTOR_KIND
    // so
    // a no-adapter context preserves the SKIPPED_NO_LINEAR_PROFILE skip.
    ProjectConnectorResolver resolver =
        resolver(List.of(new FakeTicketSource(ConnectorKind.GITLAB)), List.of());

    assertThat(resolver.findTicketSource(project(ConnectorKind.LINEAR, ConnectorKind.GITHUB)))
        .isEmpty();
  }

  // ---- unsupported kind (AC3) ----

  @Test
  void rejectsTicketSourceKindWithNoRegisteredAdapter() {
    ProjectConnectorResolver resolver =
        resolver(List.of(new FakeTicketSource(ConnectorKind.LINEAR)), List.of());

    assertThatThrownBy(
            () -> resolver.resolveTicketSource(project(ConnectorKind.GITLAB, ConnectorKind.GITHUB)))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND))
        .satisfies(e -> assertThat(e.getMessage()).contains("gitlab").contains("ticket-source"));
  }

  @Test
  void rejectsRepositoryHostKindWithNoRegisteredAdapter() {
    ProjectConnectorResolver resolver =
        resolver(List.of(), List.of(new FakeRepoHost(ConnectorKind.GITHUB)));

    assertThatThrownBy(
            () ->
                resolver.resolveRepositoryHost(project(ConnectorKind.LINEAR, ConnectorKind.GITLAB)))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND))
        .satisfies(e -> assertThat(e.getMessage()).contains("gitlab").contains("repository-host"));
  }

  // ---- duplicate-kind fail-fast at construction (AC9) ----

  @Test
  void failsFastWhenTwoAdaptersClaimTheSameTicketSourceKind() {
    assertThatThrownBy(
            () ->
                resolver(
                    List.of(
                        new FakeTicketSource(ConnectorKind.LINEAR),
                        new FakeTicketSource(ConnectorKind.LINEAR)),
                    List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate")
        .hasMessageContaining("linear");
  }

  @Test
  void failsFastWhenTwoAdaptersClaimTheSameRepositoryHostKind() {
    assertThatThrownBy(
            () ->
                resolver(
                    List.of(),
                    List.of(
                        new FakeRepoHost(ConnectorKind.GITHUB),
                        new FakeRepoHost(ConnectorKind.GITHUB))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate")
        .hasMessageContaining("github");
  }

  // ---- capability-degradation parity (AC4) ----

  @Test
  void returnsResolvedAdapterUntouchedSoDegradedCapabilitiesPassThrough() {
    FakeTicketSource degradedTs =
        new FakeTicketSource(
            ConnectorKind.GITLAB, new TicketSourceCapabilities(false, false, false));
    FakeRepoHost degradedRh =
        new FakeRepoHost(
            ConnectorKind.GITLAB,
            new RepositoryHostCapabilities(false, false, false, false, false));
    ProjectConnectorResolver resolver = resolver(List.of(degradedTs), List.of(degradedRh));

    TicketSourceAdapter ts =
        resolver.resolveTicketSource(project(ConnectorKind.GITLAB, ConnectorKind.GITLAB));
    RepositoryHostAdapter rh =
        resolver.resolveRepositoryHost(project(ConnectorKind.GITLAB, ConnectorKind.GITLAB));

    assertThat(ts).isSameAs(degradedTs);
    assertThat(ts.getCapabilities().supportsCommentOnTicket()).isFalse();
    assertThat(rh).isSameAs(degradedRh);
    assertThat(rh.getCapabilities().supportsPullRequestComments()).isFalse();
  }

  // ---- project-scoped repo mismatch (AC5) ----

  @Test
  void assertRepositoryRefMatchesProjectRaisesMismatchOnDifferentRef() {
    ProjectConnectorResolver resolver = resolver(List.of(), List.of());
    Project bound = projectWithRepo("https://github.com/octo/hello.git");

    assertThatThrownBy(() -> resolver.assertRepositoryRefMatchesProject(bound, "octo/other"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH));
  }

  @Test
  void assertRepositoryRefMatchesProjectPassesOnMatchingRef() {
    ProjectConnectorResolver resolver = resolver(List.of(), List.of());
    Project bound = projectWithRepo("git@github.com:octo/hello.git");

    assertThatCode(() -> resolver.assertRepositoryRefMatchesProject(bound, "octo/hello"))
        .doesNotThrowAnyException();
  }

  @Test
  void assertRepositoryRefMatchesProjectIsNoOpWhenProjectHasNoBinding() {
    ProjectConnectorResolver resolver = resolver(List.of(), List.of());
    Project unbound = projectWithRepo(null);

    assertThatCode(() -> resolver.assertRepositoryRefMatchesProject(unbound, "anything/at-all"))
        .doesNotThrowAnyException();
  }

  @Test
  void assertRepositoryRefMatchesProjectNormalizesTheRequestedRefForComparison() {
    // Review hardening: the requested side is now normalized too, so a URL / .git / trailing-slash
    // form of the same repo is accepted rather than falsely raising LINEAR_GITHUB_REPO_MISMATCH.
    ProjectConnectorResolver resolver = resolver(List.of(), List.of());
    Project bound = projectWithRepo("octo/hello");

    assertThatCode(
            () ->
                resolver.assertRepositoryRefMatchesProject(
                    bound, "https://github.com/octo/hello.git"))
        .doesNotThrowAnyException();
    assertThatCode(() -> resolver.assertRepositoryRefMatchesProject(bound, "octo/hello/"))
        .doesNotThrowAnyException();
  }

  @Test
  void assertRepositoryRefMatchesProjectIsCaseInsensitive() {
    // Review hardening: host repo identity is case-folding (GitHub treats Octo/Hello ==
    // octo/hello),
    // so a case-variant request must not raise a mismatch.
    ProjectConnectorResolver resolver = resolver(List.of(), List.of());
    Project bound = projectWithRepo("https://github.com/Octo/Hello.git");

    assertThatCode(() -> resolver.assertRepositoryRefMatchesProject(bound, "octo/hello"))
        .doesNotThrowAnyException();
  }

  // ---- credential seam (AC6) ----

  @Test
  void resolveConnectorSecretReturnsEmptyWhenNoCredentialSourceBeanPresent() {
    ProjectConnectorResolver resolver = resolver(List.of(), List.of());

    assertThat(
            resolver.resolveConnectorSecret(
                project(ConnectorKind.LINEAR, ConnectorKind.GITHUB), "ticket_source"))
        .isEmpty();
  }

  @Test
  void resolveConnectorSecretDelegatesToCredentialSourceWhenPresentWithoutLoggingTheSecret() {
    Project project = project(ConnectorKind.LINEAR, ConnectorKind.GITHUB);
    ProjectCredentialSource source = mock(ProjectCredentialSource.class);
    when(source.resolveSecret(project, "ticket_source")).thenReturn(Optional.of("s3cr3t-token"));
    @SuppressWarnings("unchecked")
    ObjectProvider<ProjectCredentialSource> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(source);
    ProjectConnectorResolver resolver =
        new ProjectConnectorResolver(List.of(), List.of(), provider);

    Logger logger = (Logger) LoggerFactory.getLogger(ProjectConnectorResolver.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThat(resolver.resolveConnectorSecret(project, "ticket_source"))
          .contains("s3cr3t-token");
      assertThat(appender.list)
          .as("the returned secret must never reach a log line")
          .noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("s3cr3t-token"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void resolverRetainsNoPlaintextCredentialState() {
    ProjectConnectorResolver resolver = resolver(List.of(), List.of());
    // No instance field may hold a secret value: only the two kind maps + the ObjectProvider seam.
    for (Field field : ProjectConnectorResolver.class.getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      Class<?> type = field.getType();
      assertThat(
              CharSequence.class.isAssignableFrom(type)
                  || type == byte[].class
                  || type == char[].class)
          .as("field %s must not be able to hold plaintext credential state", field.getName())
          .isFalse();
    }
    // Sanity: still resolvable after the reflective inspection.
    assertThat(
            resolver.resolveConnectorSecret(
                project(ConnectorKind.LINEAR, ConnectorKind.GITHUB), "repo_host"))
        .isEmpty();
  }

  // ---- logging instrumentation ----

  @Test
  void emitsStructuredLogsForConstructionResolutionAndFailureBranches() {
    Logger logger = (Logger) LoggerFactory.getLogger(ProjectConnectorResolver.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      ProjectConnectorResolver resolver =
          resolver(List.of(new FakeTicketSource(ConnectorKind.LINEAR)), List.of());
      resolver.resolveTicketSource(project(ConnectorKind.LINEAR, ConnectorKind.GITHUB));
      assertThatThrownBy(
              () ->
                  resolver.resolveTicketSource(project(ConnectorKind.GITLAB, ConnectorKind.GITHUB)))
          .isInstanceOf(DomainException.class);
      assertThatThrownBy(
              () ->
                  resolver.assertRepositoryRefMatchesProject(
                      projectWithRepo("octo/hello"), "octo/other"))
          .isInstanceOf(DomainException.class);

      assertThat(appender.list)
          .anySatisfy(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.INFO);
                assertThat(e.getFormattedMessage())
                    .contains("ProjectConnectorResolver constructed");
              })
          .anySatisfy(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.INFO);
                assertThat(e.getFormattedMessage()).contains("resolveTicketSource");
              })
          .anySatisfy(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("unsupported connector kind");
              })
          .anySatisfy(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("repo mismatch");
              });
    } finally {
      logger.detachAppender(appender);
    }
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static ProjectConnectorResolver resolver(
      List<TicketSourceAdapter> ticketSources, List<RepositoryHostAdapter> repoHosts) {
    return new ProjectConnectorResolver(ticketSources, repoHosts, emptyCredentialProvider());
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<ProjectCredentialSource> emptyCredentialProvider() {
    ObjectProvider<ProjectCredentialSource> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }

  private static Project project(ConnectorKind ticketSourceKind, ConnectorKind repoHostKind) {
    return new Project(
        "prj_test01",
        "Test Project",
        "test-project",
        ProjectStatus.ACTIVE,
        null,
        ticketSourceKind,
        repoHostKind,
        false,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }

  private static Project projectWithRepo(String repositoryUrl) {
    return new Project(
        "prj_test01",
        "Test Project",
        "test-project",
        ProjectStatus.ACTIVE,
        repositoryUrl,
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }

  /** Minimal fake {@link TicketSourceAdapter} declaring a kind + capabilities; ops are no-ops. */
  private static final class FakeTicketSource implements TicketSourceAdapter {
    private final ConnectorKind kind;
    private final TicketSourceCapabilities capabilities;

    private FakeTicketSource(ConnectorKind kind) {
      this(kind, TicketSourceCapabilities.linearDefaults());
    }

    private FakeTicketSource(ConnectorKind kind, TicketSourceCapabilities capabilities) {
      this.kind = kind;
      this.capabilities = capabilities;
    }

    @Override
    public ConnectorKind connectorKind() {
      return kind;
    }

    @Override
    public Optional<Ticket> fetchTicketByReference(TicketRef ref) {
      return Optional.empty();
    }

    @Override
    public List<Ticket> pollNewTickets(Instant since) {
      return List.of();
    }

    @Override
    public CommentResult postGovernedRunComment(TicketRef ref, GovernedRunComment summary) {
      return CommentResult.SKIPPED_DUPLICATE;
    }

    @Override
    public TicketSourceCapabilities getCapabilities() {
      return capabilities;
    }

    @Override
    public ConnectivityResult verifyConnectivity(String credentialOverride) {
      return ConnectivityResult.ok("fake: ok");
    }
  }

  /** Minimal fake {@link RepositoryHostAdapter} declaring a kind + capabilities; ops are no-ops. */
  private static final class FakeRepoHost implements RepositoryHostAdapter {
    private final ConnectorKind kind;
    private final RepositoryHostCapabilities capabilities;

    private FakeRepoHost(ConnectorKind kind) {
      this(kind, RepositoryHostCapabilities.githubDefaults());
    }

    private FakeRepoHost(ConnectorKind kind, RepositoryHostCapabilities capabilities) {
      this.kind = kind;
      this.capabilities = capabilities;
    }

    @Override
    public ConnectorKind connectorKind() {
      return kind;
    }

    @Override
    public Optional<Repository> getRepositoryByRef(RepositoryRef ref) {
      return Optional.empty();
    }

    @Override
    public Optional<PullRequest> getPullRequestByRef(PullRequestRef ref) {
      return Optional.empty();
    }

    @Override
    public Optional<Branch> getBranchByRef(RepositoryRef repo, String branchName) {
      return Optional.empty();
    }

    @Override
    public PullRequest createPullRequest(
        RepositoryRef repo, String sourceBranch, String targetBranch, String title, String body) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE, "fake: not implemented");
    }

    @Override
    public PullRequest updatePullRequest(PullRequestRef ref, String body) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE, "fake: not implemented");
    }

    @Override
    public org.dradgo.domain.integration.repohost.CommentResult commentOnPullRequest(
        PullRequestRef ref, String body) {
      return org.dradgo.domain.integration.repohost.CommentResult.SKIPPED_DUPLICATE;
    }

    @Override
    public RepositoryHostCapabilities getCapabilities() {
      return capabilities;
    }

    @Override
    public ConnectivityResult verifyConnectivity(RepositoryRef repo, String credentialOverride) {
      return ConnectivityResult.ok("fake: ok");
    }
  }
}
