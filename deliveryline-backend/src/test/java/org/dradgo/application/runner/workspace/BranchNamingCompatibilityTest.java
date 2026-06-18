package org.dradgo.application.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Story 3.33 (AC6/AC11) — the deterministic branch-naming convention from story 3.9 AC2 ({@code
 * RepositoryWorkspaceService.branchName}) must produce strings valid across repository hosts. It
 * uses {@code /} separators and sanitizes the ticket slug to {@code [A-Za-z0-9._-]}, so it is valid
 * on GitHub AND satisfies the stricter Bitbucket constraint that a branch name contains no {@code
 * ':'} (Bitbucket forbids {@code :} in branch names). This test pins both invariants so a future
 * host adapter can rely on the documented cross-host-compatible convention.
 */
class BranchNamingCompatibilityTest {

  /** GitHub-valid: git ref shape the convention emits ({@code owner/slug/stage-xxxxxxxx}). */
  private static final Pattern GITHUB_VALID = Pattern.compile("^[A-Za-z0-9._/-]+$");

  /** Bitbucket-compatible: same shape AND explicitly {@code :}-free. */
  private static final Pattern BITBUCKET_COMPATIBLE = Pattern.compile("^[A-Za-z0-9._/-]+$");

  // Inputs include a ticket ref carrying a ':' and spaces — the sanitizer must strip them so the
  // emitted branch is Bitbucket-compatible (':'-free); a blank ref falls back to "no-ticket".
  @ParameterizedTest
  @ValueSource(strings = {"DEL-9", "FIN-1234", "feature: do a thing", "weird/ref.name", "  "})
  void branchNameIsValidOnGithubAndBitbucket(String ticketRef) {
    String branch = RepositoryWorkspaceService.branchName(ticketRef, "run_abcdef12345678");

    assertThat(branch).startsWith("deliveryline/");
    assertThat(branch).doesNotContain(":");
    assertThat(GITHUB_VALID.matcher(branch).matches())
        .as("branch '%s' must be a GitHub-valid ref", branch)
        .isTrue();
    assertThat(BITBUCKET_COMPATIBLE.matcher(branch).matches())
        .as("branch '%s' must be a Bitbucket-compatible (':'-free) ref", branch)
        .isTrue();
  }

  @Test
  void deterministicForSameInputs() {
    assertThat(RepositoryWorkspaceService.branchName("DEL-9", "run_abcdef12345678"))
        .isEqualTo(RepositoryWorkspaceService.branchName("DEL-9", "run_abcdef12345678"));
  }
}
