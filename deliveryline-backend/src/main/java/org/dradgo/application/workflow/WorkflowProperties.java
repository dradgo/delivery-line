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
public record WorkflowProperties(
    Bot bot, RepoConfig repos, LinearCompletionSync linearCompletionSync) {

  public WorkflowProperties {
    bot = bot == null ? Bot.empty() : bot;
    repos = repos == null ? RepoConfig.empty() : repos;
    linearCompletionSync =
        linearCompletionSync == null ? LinearCompletionSync.defaults() : linearCompletionSync;
  }

  public static WorkflowProperties defaults() {
    return new WorkflowProperties(Bot.empty(), RepoConfig.empty(), LinearCompletionSync.defaults());
  }

  /**
   * Story 3.16 (AC7/AC8) — Linear completion-sync opt-out + summary-template config bound from
   * {@code deliveryline.workflow.linear-completion-sync.*}. The post-commit hook on a {@code
   * COMPLETED} transition (story 3.16 Task 2) checks {@link #enabled()} before firing, and the
   * service renders {@link #template()} into the comment body.
   *
   * <p>Like {@link Bot}/{@link RepoConfig}, the compact constructor
   * <strong>normalizes-with-defaults and never throws</strong> (house rule, story 3.9 D8): a {@code
   * null}/blank template falls back to {@link #DEFAULT_TEMPLATE} so the bean binds
   * profile-neutrally even when no keys are present (memory: {@code
   * validated-config-needs-test-yaml}). Template <em>validity</em> (required/known placeholders) is
   * NOT enforced here — a dedicated startup validator bean ({@code
   * LinearCompletionSyncConfiguration}) throws {@code INVALID_COMPLETION_TEMPLATE} when {@code
   * enabled} and the template is malformed (story 3.16 D4); enforcing it in the compact ctor would
   * break the normalize-never-throw contract every {@code @SpringBootTest} tier relies on.
   */
  public record LinearCompletionSync(boolean enabled, String template) {

    /**
     * Default merge-ready summary template (story 3.16 AC3c). All placeholders are members of the
     * known set ({@link
     * org.dradgo.application.workflow.LinearCompletionTemplate#KNOWN_PLACEHOLDERS}) and it carries
     * every required placeholder, so the default profile + every {@code @SpringBootTest} tier boots
     * without tripping the startup validator.
     */
    public static final String DEFAULT_TEMPLATE =
        "DeliveryLine governed run `{runId}` completed: PR `{prUrl}` ready for merge. "
            + "Spec: `{specSummary}` (v{specVersion}). "
            + "Reviewers: PM `{pmReviewer}`, Dev `{devReviewer}`. "
            + "Cycle time: `{durationFormatted}`.";

    public LinearCompletionSync {
      template = (template == null || template.isBlank()) ? DEFAULT_TEMPLATE : template.trim();
    }

    /** Enabled-by-default (AC7) with the documented default template. */
    public static LinearCompletionSync defaults() {
      return new LinearCompletionSync(true, DEFAULT_TEMPLATE);
    }
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
      // Story 3c-3 — the owner/repo normalization is shared with the per-project binding check
      // (ProjectConnectorResolver, AC5) via RepositoryRef.normalizeRepositoryUrl. The url field is
      // already trim/blank-normalized by the compact ctor, so delegating is byte-identical.
      return org.dradgo.domain.integration.repohost.RepositoryRef.normalizeRepositoryUrl(url);
    }
  }
}
