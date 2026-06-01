package org.dradgo.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * GitHub integration subsystem wiring (story 3.13 Task 4). Mirrors {@code LinearConfiguration}'s
 * mock/real exclusivity guard.
 *
 * <p>Asserts that {@code github-mock} and {@code github-real} are mutually exclusive (the guard
 * reads only the active profile names, so it works now even though {@code github-real} — the real
 * REST adapter + {@code GitHubProperties} validated config — does not exist until story 3.14). No
 * {@code GitHubProperties} binding and no {@code gitHubRestClient} bean are defined here: this
 * story ships the mock + port only, and adds NO validated configuration (so {@code
 * src/test/resources/application.yml} needs no matching keys — see memory {@code
 * validated-config-needs-test-yaml}).
 */
@Configuration
public class GitHubConfiguration {

  static final String MOCK_PROFILE = "github-mock";
  static final String REAL_PROFILE = "github-real";

  public GitHubConfiguration(Environment environment) {
    assertExclusiveGitHubProfile(environment);
  }

  private static void assertExclusiveGitHubProfile(Environment environment) {
    boolean mockActive = isProfileActive(environment, MOCK_PROFILE);
    boolean realActive = isProfileActive(environment, REAL_PROFILE);
    if (mockActive && realActive) {
      throw new IllegalStateException(
          "GitHub profile conflict: "
              + MOCK_PROFILE
              + " and "
              + REAL_PROFILE
              + " are mutually exclusive but both are active. Activate exactly one.");
    }
  }

  private static boolean isProfileActive(Environment environment, String profile) {
    for (String active : environment.getActiveProfiles()) {
      if (active.equals(profile)) {
        return true;
      }
    }
    return false;
  }
}
