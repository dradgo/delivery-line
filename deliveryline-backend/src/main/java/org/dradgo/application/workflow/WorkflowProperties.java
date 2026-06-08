package org.dradgo.application.workflow;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validated configuration for the {@code deliveryline.workflow.*} namespace (story 3.9 OQ-6,
 * Decision D8).
 *
 * <p>Like {@link org.dradgo.application.integration.github.GitHubProperties}, the compact
 * constructor <strong>normalizes-with-defaults and never throws</strong> so the bean binds
 * profile-neutrally in every {@code @SpringBootTest} tier even when no {@code
 * deliveryline.workflow.*} keys are present (memory: {@code validated-config-needs-test-yaml} —
 * normalize-never-throw dodges the trap, and the keys are still mirrored into both {@code
 * application.yml} files for discoverability, Trap T2). The bean is registered via
 * {@code @EnableConfigurationProperties} on {@code WorkflowConfiguration} (the
 * application-must-not-depend-on-infrastructure rule keeps the
 * {@code @EnableConfigurationProperties} annotation on the infrastructure class, not here).
 *
 * <p>The pre-existing {@code deliveryline.workflow.spec-rejection-escalation-threshold} stays owned
 * by {@code SpecRejectionEscalationThresholdProvider} via {@code @Value}; this record binds only
 * the {@code repos} + {@code bot} sub-trees and ignores the threshold key (unknown-field-tolerant).
 */
@ConfigurationProperties("deliveryline.workflow")
public record WorkflowProperties(Bot bot, RepoConfig repos) {

  public WorkflowProperties {
    bot = bot == null ? Bot.empty() : bot;
    repos = repos == null ? RepoConfig.empty() : repos;
  }

  public static WorkflowProperties defaults() {
    return new WorkflowProperties(Bot.empty(), RepoConfig.empty());
  }

  /**
   * {@code deliveryline-bot} service-account git identity (Decision D8). Raw values are nullable so
   * the doctor probe (AC15) can distinguish "operator explicitly configured an identity" (PASS)
   * from "relying on the built-in default" ({@code DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED} WARN). The
   * git commit path always resolves a usable identity via {@link #effectiveName()} / {@link
   * #effectiveEmail()}, so a real run can proceed even when unconfigured.
   */
  public record Bot(String name, String email) {

    public static final String DEFAULT_NAME = "DeliveryLine Bot";
    public static final String DEFAULT_EMAIL = "deliveryline-bot@dradgo.org";

    public Bot {
      name = (name == null || name.isBlank()) ? null : name.trim();
      email = (email == null || email.isBlank()) ? null : email.trim();
    }

    public static Bot empty() {
      return new Bot(null, null);
    }

    public String effectiveName() {
      return name == null ? DEFAULT_NAME : name;
    }

    public String effectiveEmail() {
      return email == null ? DEFAULT_EMAIL : email;
    }

    /** True only when the operator explicitly configured BOTH a bot name and email. */
    public boolean isExplicitlyConfigured() {
      return name != null && email != null;
    }
  }

  /**
   * Single configured pilot repository (1:1; a real Linear↔GitHub mapping is deferred to
   * 3.32/3.33). Bound from {@code deliveryline.workflow.repos.*} for easy IDE override. {@code url}
   * accepts an {@code owner/repo} slug, an HTTPS clone URL, or an SSH SCP URL — all normalized to
   * the {@code owner/repo} reference the GitHub adapter resolves ({@link #repositoryRef()}). The
   * clone itself always uses the API-derived HTTPS URL + the host PAT, so the SSH form is accepted
   * as notation only (no SSH transport). {@code cloneDepth}/{@code sparsePaths} are the AC5 clone
   * knobs.
   */
  public record RepoConfig(String url, int cloneDepth, List<String> sparsePaths) {

    public static final int DEFAULT_CLONE_DEPTH = 1;

    public RepoConfig {
      url = (url == null || url.isBlank()) ? null : url.trim();
      cloneDepth = cloneDepth <= 0 ? DEFAULT_CLONE_DEPTH : cloneDepth;
      sparsePaths = sparsePaths == null ? List.of() : List.copyOf(sparsePaths);
    }

    public static RepoConfig empty() {
      return new RepoConfig(null, DEFAULT_CLONE_DEPTH, List.of());
    }

    /** Single-repo config from just a {@code url} (owner/repo, HTTPS, or SSH form), depth 1. */
    public static RepoConfig of(String url) {
      return new RepoConfig(url, DEFAULT_CLONE_DEPTH, List.of());
    }

    public boolean sparseEnabled() {
      return !sparsePaths.isEmpty();
    }

    /**
     * The {@code owner/repo} reference derived from {@link #url()}, or {@code null} when no repo is
     * configured. Accepts {@code owner/repo}, {@code https://host/owner/repo(.git)}, and {@code
     * git@host:owner/repo(.git)} forms; the actual clone transport stays HTTPS + PAT regardless.
     */
    public String repositoryRef() {
      if (url == null) {
        return null;
      }
      String ref = url;
      if (ref.endsWith(".git")) {
        ref = ref.substring(0, ref.length() - ".git".length());
      }
      int scheme = ref.indexOf("://");
      if (scheme >= 0) {
        // https://host/owner/repo or ssh://git@host/owner/repo — strip scheme + host.
        String afterHost = ref.substring(scheme + 3);
        int slash = afterHost.indexOf('/');
        ref = slash >= 0 ? afterHost.substring(slash + 1) : afterHost;
      } else {
        int colon = ref.indexOf(':');
        if (colon >= 0) {
          // scp-like git@host:owner/repo — strip user@host.
          ref = ref.substring(colon + 1);
        }
      }
      while (ref.startsWith("/")) {
        ref = ref.substring(1);
      }
      return ref.isBlank() ? null : ref;
    }
  }
}
