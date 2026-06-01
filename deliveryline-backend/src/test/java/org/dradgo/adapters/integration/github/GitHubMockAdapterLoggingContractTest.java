package org.dradgo.adapters.integration.github;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins the story 3.13 Logging-instrumentation contract: the simulated-anomaly and
 * idempotency-replay branches emit WARN log lines (so downstream refactors cannot silently delete
 * them), and no PR body content is ever logged. Mirrors the {@code ListAppender} house style from
 * {@code RunnerLoggingContractTest}.
 */
class GitHubMockAdapterLoggingContractTest {

  private static final String SECRET_BODY = "super-secret-pr-body-do-not-log";

  private GitHubMockScenarioRegistry registry;
  private GitHubMockAdapter adapter;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    registry = new GitHubMockScenarioRegistry();
    adapter = new GitHubMockAdapter(registry);
    appender = attachListAppender(GitHubMockAdapter.class);
  }

  @AfterEach
  void detach() {
    detach(GitHubMockAdapter.class, appender);
  }

  @Test
  void rateLimitedBranchLogsWarn() {
    registry.register(
        new GitHubMockScenario(
            GitHubMockScenarioRegistry.REF_PR_RATE_LIMITED,
            GitHubMockScenario.Behaviour.RATE_LIMITED,
            null,
            IntegrationFailureCategory.GITHUB_RATE_LIMITED));
    try {
      adapter.commentOnPullRequest(GitHubMockScenarioRegistry.REF_PR_RATE_LIMITED, "body");
    } catch (RuntimeException expected) {
      // expected — we only care about the emitted log line.
    }
    assertContainsLogAt(Level.WARN, "resolution=simulated_failure");
    assertContainsLogAt(Level.WARN, "category=github_rate_limited");
  }

  @Test
  void conflictBranchLogsWarn() {
    registry.register(
        new GitHubMockScenario(
            GitHubMockScenarioRegistry.REF_PR_CONFLICT,
            GitHubMockScenario.Behaviour.CONFLICT,
            null,
            null));
    adapter.getPullRequestByRef(GitHubMockScenarioRegistry.REF_PR_CONFLICT);
    assertContainsLogAt(Level.WARN, "resolution=conflict");
  }

  @Test
  void idempotencyReplayBranchesLogWarnAndNeverLogBodyContent() {
    adapter.createPullRequest(
        GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK, "feature/x", "title", SECRET_BODY);
    adapter.createPullRequest(
        GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK, "feature/x", "title", SECRET_BODY);
    adapter.commentOnPullRequest(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK, SECRET_BODY);
    adapter.commentOnPullRequest(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK, SECRET_BODY);

    assertContainsLogAt(Level.WARN, "create_pull_request");
    assertContainsLogAt(Level.WARN, "comment_on_pull_request");
    assertContainsLogAt(Level.WARN, "resolution=idempotent_replay");

    // The PR body must never reach the logs (refs / counts / fingerprints only).
    boolean leaked =
        appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(SECRET_BODY));
    assertTrue(!leaked, "PR body content must never be logged");
  }

  @Test
  void updatePullRequestNotFoundLogsWarn() {
    try {
      adapter.updatePullRequest("PR-NEVER-SEEDED", "body");
    } catch (RuntimeException expected) {
      // expected — we only care about the emitted log line.
    }
    assertContainsLogAt(Level.WARN, "update_pull_request");
    assertContainsLogAt(Level.WARN, "category=github_pr_not_found");
  }

  private void assertContainsLogAt(Level level, String fragment) {
    List<ILoggingEvent> events = appender.list;
    boolean found =
        events.stream()
            .filter(event -> event.getLevel() == level)
            .anyMatch(event -> event.getFormattedMessage().contains(fragment));
    assertTrue(
        found,
        () ->
            "Expected "
                + level
                + " log containing \""
                + fragment
                + "\" but saw: "
                + events.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList());
  }

  private static ListAppender<ILoggingEvent> attachListAppender(Class<?> loggerClass) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    return appender;
  }

  private static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
    logger.detachAppender(appender);
    appender.stop();
  }
}
