package org.dradgo.adapters.integration.repohost.github;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class GitHubScenarioRegistryLoggingContractTest {

  private GitHubMockScenarioRegistry registry;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    appender = attachListAppender(GitHubMockScenarioRegistry.class);
    registry = new GitHubMockScenarioRegistry();
  }

  @AfterEach
  void detach() {
    detach(GitHubMockScenarioRegistry.class, appender);
  }

  @Test
  void missingHappyFixtureLogsWarnBeforeThrowing() {
    GitHubMockScenario missing =
        new GitHubMockScenario(
            "TEST-MISSING",
            GitHubMockScenario.Behaviour.HAPPY,
            "github-fixtures/does-not-exist.json",
            null);

    assertThrows(IllegalStateException.class, () -> registry.loadHappyFixture(missing));

    boolean found =
        appender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .anyMatch(
                event ->
                    event.getFormattedMessage().contains("fixture_missing")
                        && event.getFormattedMessage().contains("does-not-exist.json"));
    assertTrue(found, "missing fixture path must be logged at WARN before failing");
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
