package org.dradgo.application.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public record WorkflowProperties(Bot bot, Map<String, Repo> repos) {

  public WorkflowProperties {
    bot = bot == null ? Bot.empty() : bot;
    repos = repos == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(repos));
  }

  public static WorkflowProperties defaults() {
    return new WorkflowProperties(Bot.empty(), Map.of());
  }

  /**
   * Per-repository clone tuning for {@code repoKey}, falling back to {@link Repo#defaults()}
   * (shallow {@code --depth 1}, no sparse paths) when no override is configured (AC5).
   */
  public Repo repoFor(String repoKey) {
    Repo configured = repoKey == null ? null : repos.get(repoKey);
    return configured == null ? Repo.defaults() : configured;
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

  /** Per-repository shallow/sparse clone tuning (AC5). */
  public record Repo(int cloneDepth, List<String> sparsePaths) {

    public static final int DEFAULT_CLONE_DEPTH = 1;

    public Repo {
      cloneDepth = cloneDepth <= 0 ? DEFAULT_CLONE_DEPTH : cloneDepth;
      sparsePaths = sparsePaths == null ? List.of() : List.copyOf(sparsePaths);
    }

    public static Repo defaults() {
      return new Repo(DEFAULT_CLONE_DEPTH, List.of());
    }

    public boolean sparseEnabled() {
      return !sparsePaths.isEmpty();
    }
  }
}
