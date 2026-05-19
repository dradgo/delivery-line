package org.dradgo.foundation;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.launcher.listeners.TestExecutionSummary.Failure;

/**
 * Helper utilities for {@code FoundationGateVerificationTest}.
 *
 * <p>{@link #tagged(String, String)} prefixes failure messages with the originating Epic-1 story
 * reference so the CI log immediately points at the broken contract.
 *
 * <p>{@link #delegateRunAssertGreen(String, String)} uses the JUnit Platform Launcher API to
 * delegate-run an existing source-of-truth test class (referenced by fully qualified name) and
 * fails fast on any reported failure. This avoids re-authoring the contract assertions inside the
 * foundation gate (per story 1.23 anti-patterns).
 */
final class FoundationGateAssertions {

  private FoundationGateAssertions() {}

  static String tagged(String storyRef, String detail) {
    return "[story " + storyRef + "] " + detail;
  }

  static void delegateRunAssertGreen(String storyRef, String fullyQualifiedTestClass) {
    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(fullyQualifiedTestClass))
            .build();
    try (LauncherSession session = LauncherFactory.openSession()) {
      Launcher launcher = session.getLauncher();
      SummaryGeneratingListener listener = new SummaryGeneratingListener();
      launcher.registerTestExecutionListeners(listener);
      launcher.execute(request);
      TestExecutionSummary summary = listener.getSummary();
      List<Failure> failures = summary.getFailures();
      if (!failures.isEmpty()) {
        // Disambiguate Testcontainers / Docker daemon failures from real
        // contract regressions. Re-prefix as `[env]` instead of `[story X.Y]`
        // so triage knows to look at infrastructure, not at the contract.
        boolean envFailure =
            failures.stream().anyMatch(f -> isContainerLaunchFailure(f.getException()));
        String details =
            failures.stream()
                .map(
                    f ->
                        f.getTestIdentifier().getDisplayName()
                            + " -> "
                            + f.getException().getClass().getSimpleName()
                            + ": "
                            + f.getException().getMessage())
                .collect(Collectors.joining(" | "));
        if (envFailure) {
          Assertions.fail(
              "[env] Testcontainers could not start a container while running "
                  + fullyQualifiedTestClass
                  + " - Docker daemon unreachable or unhealthy. This is NOT a foundation-contract"
                  + " regression. Details: "
                  + details);
        }
        Assertions.fail(
            tagged(
                storyRef,
                "delegate-run reported "
                    + failures.size()
                    + " failure(s) in "
                    + fullyQualifiedTestClass
                    + ": "
                    + details));
      }
      if (summary.getTestsFoundCount() == 0) {
        Assertions.fail(
            tagged(
                storyRef,
                "delegate-run found zero tests in "
                    + fullyQualifiedTestClass
                    + " - class missing, package-private outside reach, or filtered out by an"
                    + " engine"));
      }
      if (summary.getTestsStartedCount() == 0 || summary.getTestsSucceededCount() == 0) {
        Assertions.fail(
            tagged(
                storyRef,
                "delegate-run did not execute at least one passing test in "
                    + fullyQualifiedTestClass
                    + " - the contract may be skipped, disabled, or filtered out"));
      }
    }
  }

  /**
   * Walks the exception cause chain and matches Testcontainers/Docker-startup failures by class
   * name so we don't have to take a compile-time dependency on Testcontainers in this helper.
   * Matches {@code ContainerLaunchException}, {@code DockerClientException}, and the IO-failure
   * subtypes Testcontainers wraps daemon-down errors in.
   */
  private static boolean isContainerLaunchFailure(Throwable t) {
    Throwable cursor = t;
    while (cursor != null) {
      String name = cursor.getClass().getName();
      if (name.contains("ContainerLaunchException")
          || name.contains("DockerClientException")
          || name.contains("ContainerFetchException")
          || name.contains("DockerException")) {
        return true;
      }
      String message = cursor.getMessage();
      if (message != null
          && (message.contains("Could not find a valid Docker environment")
              || message.contains("Cannot connect to the Docker daemon"))) {
        return true;
      }
      cursor = cursor.getCause();
    }
    return false;
  }
}
