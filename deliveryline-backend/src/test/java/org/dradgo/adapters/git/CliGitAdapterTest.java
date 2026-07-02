package org.dradgo.adapters.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.dradgo.application.runner.workspace.spi.GitCommandException;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story 3.9 AC7 — unit coverage for the git-push stderr → {@link IntegrationFailureCategory}
 * classification ladder. Pure (no git process), so it runs in the fast tier on every platform.
 */
class CliGitAdapterTest {

  @Test
  void protectedBranchSignalMapsToBranchProtectionViolation() {
    assertThat(
            CliGitAdapter.classifyPushFailure(
                "remote: error: GH006: Protected branch update failed for refs/heads/main"))
        .isEqualTo(IntegrationFailureCategory.GIT_BRANCH_PROTECTION_VIOLATION);
    assertThat(CliGitAdapter.classifyPushFailure("required status check \"ci\" is expected"))
        .isEqualTo(IntegrationFailureCategory.GIT_BRANCH_PROTECTION_VIOLATION);
  }

  @Test
  void authSignalMapsToAuthFailed() {
    assertThat(CliGitAdapter.classifyPushFailure("fatal: Authentication failed for 'https://...'"))
        .isEqualTo(IntegrationFailureCategory.GIT_AUTH_FAILED);
    assertThat(CliGitAdapter.classifyPushFailure("remote: Permission denied to user."))
        .isEqualTo(IntegrationFailureCategory.GIT_AUTH_FAILED);
    assertThat(CliGitAdapter.classifyPushFailure("The requested URL returned error: 403"))
        .isEqualTo(IntegrationFailureCategory.GIT_AUTH_FAILED);
    assertThat(
            CliGitAdapter.classifyPushFailure("remote: HTTP Basic: Access denied\n403 Forbidden"))
        .isEqualTo(IntegrationFailureCategory.GIT_AUTH_FAILED);
  }

  @Test
  void nonFastForwardRejectionIsNotMisreadAsAuthWhenPathDigitsContain403() {
    // A rejected push echoes the remote path; an incidental "403"/"401" in a JUnit @TempDir name
    // (e.g. /tmp/junit-3679854037.../remoteBare) must not be mistaken for an HTTP auth status.
    assertThat(
            CliGitAdapter.classifyPushFailure(
                "To /tmp/junit-3679854037697941104/remoteBare\n"
                    + " ! [rejected]        branch -> branch (fetch first)\n"
                    + "error: failed to push some refs to '/tmp/junit-3679854037697941104/remoteBare'"))
        .isEqualTo(IntegrationFailureCategory.GIT_PUSH_REJECTED);
  }

  @Test
  void networkSignalMapsToNetworkFailure() {
    assertThat(
            CliGitAdapter.classifyPushFailure(
                "fatal: unable to access ... Could not resolve host: github.com"))
        .isEqualTo(IntegrationFailureCategory.GIT_NETWORK_FAILURE);
    assertThat(
            CliGitAdapter.classifyPushFailure(
                "ssh: connect to host github.com port 22: Connection timed out"))
        .isEqualTo(IntegrationFailureCategory.GIT_NETWORK_FAILURE);
  }

  @Test
  void nonFastForwardRejectionMapsToPushRejected() {
    assertThat(
            CliGitAdapter.classifyPushFailure(
                " ! [rejected]        main -> main (non-fast-forward)\nhint: Updates were rejected"))
        .isEqualTo(IntegrationFailureCategory.GIT_PUSH_REJECTED);
    assertThat(CliGitAdapter.classifyPushFailure("hint: fetch first"))
        .isEqualTo(IntegrationFailureCategory.GIT_PUSH_REJECTED);
  }

  @Test
  void blankOrUnknownOutputDefaultsToPushRejected() {
    assertThat(CliGitAdapter.classifyPushFailure(null))
        .isEqualTo(IntegrationFailureCategory.GIT_PUSH_REJECTED);
    assertThat(CliGitAdapter.classifyPushFailure(""))
        .isEqualTo(IntegrationFailureCategory.GIT_PUSH_REJECTED);
  }

  @Test
  void gitCommandTimeoutIsEnforcedBeforeWaitingForEndOfStream(@TempDir Path tempDir)
      throws Exception {
    Path git = tempDir.resolve(isWindows() ? "git.cmd" : "git");
    if (isWindows()) {
      Files.writeString(git, "@echo off\r\nping -n 6 127.0.0.1 > nul\r\n", StandardCharsets.UTF_8);
    } else {
      Files.writeString(git, "#!/bin/sh\nsleep 5\n", StandardCharsets.UTF_8);
      git.toFile().setExecutable(true);
    }
    CliGitAdapter adapter =
        new CliGitAdapter(
            new RedactionPolicyService(new DataClassificationService()), git.toString(), 1, 1);

    assertThatThrownBy(() -> adapter.currentBranch(tempDir))
        .isInstanceOf(GitCommandException.class)
        .hasMessageContaining("timed out");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
