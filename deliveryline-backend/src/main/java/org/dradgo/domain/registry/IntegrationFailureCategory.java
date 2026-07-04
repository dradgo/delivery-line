package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Classification axis for integration-layer (Linear, GitHub) sync/link failures. Separate from
 * {@link FailureCategory} which is runner-scoped — integration failures are about external API
 * conversations, not about runner-execution lifecycle.
 *
 * <p>Wire form is the {@code value()} (snake_case). Renaming an enum constant must keep the wire
 * value identical or it is a wire-breaking change. The values are referenced by AC6 of story 1.14.
 */
public enum IntegrationFailureCategory implements RegistryValue {
  SYNC_FAILURE("sync_failure"),
  LINK_FAILURE("link_failure"),
  STATE_CONFLICT("state_conflict"),
  NETWORK_API_FAILURE("network_api_failure"),

  // GitHub-specific categories (story 3.13 Decision D1; the full set is enumerated by story 3.14
  // AC7 so the real adapter needn't re-touch this enum). Wire values are snake_case and are
  // wire-breaking to rename — they were named to match story 3.14 AC7 the first time.
  GITHUB_REPO_NOT_FOUND("github_repo_not_found"),
  GITHUB_PR_NOT_FOUND("github_pr_not_found"),
  GITHUB_PERMISSION_DENIED("github_permission_denied"),
  GITHUB_RATE_LIMITED("github_rate_limited"),
  GITHUB_BRANCH_PROTECTED("github_branch_protected"),
  GITHUB_AUTH_FAILED("github_auth_failed"),
  GITHUB_NETWORK_FAILURE("github_network_failure"),
  GITHUB_API_VERSION_INCOMPATIBLE("github_api_version_incompatible"),

  // Git-CLI workspace categories (story 3.9 Decision D4). These classify a `git push` rejection or
  // failure from the host-side RepositoryWorkspaceService/CliGitAdapter — distinct from the
  // GitHub_* values above, which classify the GitHub REST adapter's API conversation. Wire values
  // are snake_case and wire-breaking to rename. ADDITIVE/safe: the enum is auto-derived into
  // DomainRegistry, is NOT in the api-schema-placeholder manifest, and has NO SQL CHECK (confirmed
  // story 3.13) — so adding these requires no openapi/schema.d.ts regen (contrast DomainErrorCode,
  // which IS three-sites). GIT_BRANCH_PROTECTION_VIOLATION is the push-time git rejection and is
  // deliberately distinct from the adapter's PR-create-time GITHUB_BRANCH_PROTECTED.
  GIT_PUSH_REJECTED("git_push_rejected"),
  GIT_BRANCH_PROTECTION_VIOLATION("git_branch_protection_violation"),
  GIT_NETWORK_FAILURE("git_network_failure"),
  GIT_AUTH_FAILED("git_auth_failed"),
  // Server-side receive-pack rejection of a push that creates/updates a `.github/workflows/**`
  // file when the push token lacks the `workflow` scope (classic PAT) / Workflows:write (App).
  // Distinct from GIT_AUTH_FAILED so the operator sees the exact remediation (grant the scope)
  // rather than a generic auth failure, and NON-RETRYABLE — a plain retry with the same token
  // hits the identical wall. GitHub's message: "refusing to allow a Personal Access Token to
  // create or update workflow `.github/workflows/build.yml` without `workflow` scope".
  GIT_WORKFLOW_SCOPE_MISSING("git_workflow_scope_missing");

  private static final Map<String, IntegrationFailureCategory> LOOKUP =
      RegistryParsers.index(values());

  private final String value;

  IntegrationFailureCategory(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static IntegrationFailureCategory fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static IntegrationFailureCategory fromValue(String rawValue, String field) {
    return RegistryParsers.parse("IntegrationFailureCategory", rawValue, field, LOOKUP);
  }

  public static IntegrationFailureCategory fromNullableValue(String rawValue, String field) {
    return RegistryParsers.parseNullable("IntegrationFailureCategory", rawValue, field, LOOKUP);
  }
}
