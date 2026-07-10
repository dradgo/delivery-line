package org.dradgo.application.integration.repohost;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound selector for the active repository-host kind (story 3.33 AC5 / OQ-4).
 *
 * <p>{@code kind} names the host implementation that backs the {@link RepositoryHostAdapter} port —
 * {@code github} today; future kinds ({@code bitbucket}, {@code gitlab}, {@code gitea}, {@code
 * azure-devops}) plug in by adding a new implementation under {@code
 * adapters.integration.repohost.{kind}} plus a profile entry. The {@code kind} key is the
 * <em>documented</em> selector; the load-bearing bean gating remains the existing
 * mutually-exclusive Spring profiles ({@code github-mock} / {@code github-real}) — {@code
 * GitHubConfiguration} fail-fasts at boot when {@code kind} names a host with no implementation on
 * the classpath (today, anything other than {@code github}). Renaming the existing {@code
 * deliveryline.github.*} config keys to {@code deliveryline.integration.repo-host.github.*} is an
 * ops-breaking change intentionally left out of scope (recorded as a future cosmetic migration in
 * ADR 0008).
 *
 * <p>Deliberately a thin, normalized record: {@code kind} defaults to {@code "github"} when absent
 * or blank, and is lower-cased/stripped so a stray {@code " GitHub "} from a quoted YAML/env entry
 * still matches. The default keeps every {@code @SpringBootTest} context green without a test-yaml
 * mirror entry (mirrors {@code GitHubProperties}' normalize-never-throw posture).
 */
@ConfigurationProperties("deliveryline.integration.repo-host")
public record RepositoryHostProperties(String kind) {

  /** The default kind shipped today. */
  public static final String KIND_GITHUB = "github";

  /** Story 3i-3 (FR82) — the second real repository host: Bitbucket Cloud. */
  public static final String KIND_BITBUCKET = "bitbucket";

  public RepositoryHostProperties {
    kind = (kind == null || kind.isBlank()) ? KIND_GITHUB : kind.strip().toLowerCase(Locale.ROOT);
  }

  public static RepositoryHostProperties defaults() {
    return new RepositoryHostProperties(KIND_GITHUB);
  }

  public boolean isGithub() {
    return KIND_GITHUB.equals(kind);
  }

  public boolean isBitbucket() {
    return KIND_BITBUCKET.equals(kind);
  }

  /** True when {@code kind} names a repository host with an implementation on the classpath. */
  public boolean isSupported() {
    return isGithub() || isBitbucket();
  }
}
