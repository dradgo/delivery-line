package org.dradgo.adapters.integration.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import org.dradgo.adapters.integration.repohost.gitlab.GitLabRepositoryHostStubAdapter;
import org.dradgo.adapters.integration.ticketsource.gitlab.GitLabTicketSourceStubAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3c-3 (AC8/AC4) — the documented GITLAB stub adapters declare {@code connectorKind() ==
 * GITLAB}, report a deliberately degraded capability set, return safe empties on reads, and take a
 * typed not-implemented path on the two non-{@code Optional} write methods.
 */
class GitLabStubAdaptersTest {

  private final GitLabTicketSourceStubAdapter ticketSource = new GitLabTicketSourceStubAdapter();
  private final GitLabRepositoryHostStubAdapter repoHost = new GitLabRepositoryHostStubAdapter();

  @Test
  void ticketSourceStubDeclaresGitlabKindAndDegradedCapabilities() {
    assertThat(ticketSource.connectorKind()).isEqualTo(ConnectorKind.GITLAB);
    assertThat(ticketSource.getCapabilities().supportsCommentOnTicket()).isFalse();
    assertThat(ticketSource.getCapabilities().supportsPolling()).isFalse();
    assertThat(ticketSource.getCapabilities().supportsTicketStateUpdates()).isFalse();
  }

  @Test
  void ticketSourceStubReadsAreSafeEmptiesAndCommentIsNoOp() {
    assertThat(ticketSource.fetchTicketByReference(TicketRef.of("GL-1"))).isEmpty();
    assertThat(ticketSource.pollNewTickets(Instant.EPOCH)).isEmpty();
    assertThat(
            ticketSource.postGovernedRunComment(
                TicketRef.of("GL-1"),
                new org.dradgo.domain.integration.ticketsource.GovernedRunComment(
                    "run_demo01",
                    "fp-1",
                    "body",
                    org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED)))
        .isEqualTo(org.dradgo.domain.integration.ticketsource.CommentResult.SKIPPED_DUPLICATE);
  }

  @Test
  void repoHostStubDeclaresGitlabKindAndDegradedCapabilities() {
    assertThat(repoHost.connectorKind()).isEqualTo(ConnectorKind.GITLAB);
    assertThat(repoHost.getCapabilities().supportsPullRequestComments()).isFalse();
    assertThat(repoHost.getCapabilities().supportsDraftPullRequests()).isFalse();
  }

  @Test
  void repoHostStubReadsAreSafeEmptiesAndCommentIsNoOp() {
    assertThat(repoHost.getRepositoryByRef(RepositoryRef.of("octo/repo"))).isEmpty();
    assertThat(repoHost.getPullRequestByRef(PullRequestRef.of("octo/repo#1"))).isEmpty();
    assertThat(repoHost.getBranchByRef(RepositoryRef.of("octo/repo"), "main")).isEmpty();
    assertThat(repoHost.commentOnPullRequest(PullRequestRef.of("octo/repo#1"), "body"))
        .isEqualTo(org.dradgo.domain.integration.repohost.CommentResult.SKIPPED_DUPLICATE);
  }

  @Test
  void repoHostStubWritesTakeTypedNotImplementedPath() {
    assertThatThrownBy(
            () ->
                repoHost.createPullRequest(
                    RepositoryRef.of("octo/repo"), "feature", "main", "t", "b"))
        .isInstanceOf(RepositoryHostAdapterException.class)
        .satisfies(
            e ->
                assertThat(((RepositoryHostAdapterException) e).failureCategory())
                    .isEqualTo(IntegrationFailureCategory.SYNC_FAILURE));
    assertThatThrownBy(() -> repoHost.updatePullRequest(PullRequestRef.of("octo/repo#1"), "b"))
        .isInstanceOf(RepositoryHostAdapterException.class);
  }

  @Test
  void stubsReturnDegradedButOkConnectivityProbe() {
    // Story 3c-8 — verifyConnectivity is a deterministic degraded-but-OK no-op; the
    // ProjectConnectivityService skips these checks from the degraded capability set, never
    // failing.
    assertThat(ticketSource.verifyConnectivity(null).reachable()).isTrue();
    assertThat(ticketSource.verifyConnectivity(null).authenticated()).isTrue();
    assertThat(repoHost.verifyConnectivity(RepositoryRef.of("octo/repo"), null).reachable())
        .isTrue();
    assertThat(repoHost.verifyConnectivity(null, null).authenticated()).isTrue();
  }

  @Test
  void stubLogsDocumentedNoOpSoMisroutedProjectIsObvious() {
    Logger logger = (Logger) LoggerFactory.getLogger(GitLabTicketSourceStubAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      ticketSource.fetchTicketByReference(TicketRef.of("GL-9"));
      assertThat(appender.list)
          .anySatisfy(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.INFO);
                assertThat(e.getFormattedMessage()).contains("gitlab stub");
              });
    } finally {
      logger.detachAppender(appender);
    }
  }
}
