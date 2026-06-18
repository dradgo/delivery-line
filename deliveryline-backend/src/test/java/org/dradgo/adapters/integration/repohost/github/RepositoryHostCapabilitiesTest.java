package org.dradgo.adapters.integration.repohost.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Story 3.33 (AC3/AC10/AC11) — capability declaration parity. Both the mock and real GitHub
 * implementations of {@link RepositoryHostAdapter} declare the same GitHub capability set ({@link
 * RepositoryHostCapabilities#githubDefaults()} — all five {@code true}). GitHub supports every
 * optional feature today, so there is no live unconditional consumer to drive a capability-skip
 * (story 3.33 R5/OQ-5); the contract is therefore the capability DECLARATION, asserted identically
 * in both implementations (the foundation contract pins the same invariant).
 */
class RepositoryHostCapabilitiesTest {

  @Test
  void mockAdapterDeclaresGithubCapabilities() {
    RepositoryHostAdapter mock = new GitHubMockAdapter(new GitHubMockScenarioRegistry());
    assertEquals(
        RepositoryHostCapabilities.githubDefaults(),
        mock.getCapabilities(),
        "mock must declare the GitHub capability set");
  }

  @Test
  void realAdapterDeclaresGithubCapabilities() {
    RepositoryHostAdapter real =
        new GitHubRealAdapter(
            RestClient.create(),
            GitHubProperties.defaults(),
            new RedactionPolicyService(new DataClassificationService()));
    assertEquals(
        RepositoryHostCapabilities.githubDefaults(),
        real.getCapabilities(),
        "real must declare the GitHub capability set");
  }

  @Test
  void githubDefaultsDeclaresEveryOptionalFeatureSupported() {
    RepositoryHostCapabilities caps = RepositoryHostCapabilities.githubDefaults();
    assertTrue(caps.supportsDraftPullRequests(), "GitHub supports draft PRs");
    assertTrue(caps.supportsPullRequestComments(), "GitHub supports PR comments");
    assertTrue(caps.supportsBranchProtection(), "GitHub supports branch protection");
    assertTrue(caps.supportsForkPushes(), "GitHub supports fork pushes");
    assertTrue(caps.supportsRequiredStatusChecks(), "GitHub supports required status checks");
  }
}
