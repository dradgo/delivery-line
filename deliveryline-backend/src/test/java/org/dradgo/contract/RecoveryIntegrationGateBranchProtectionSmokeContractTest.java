package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Story 4.25 (AC6) — branch-protection smoke for the recovery-integration required check.
 *
 * <p>Parses BOTH branch-protection helper scripts ({@code
 * scripts/ci/configure-branch-protection.sh} and {@code .ps1}) and asserts that {@code
 * "recovery-integration-gate"} appears in the source-of-truth contexts array between the {@code #
 * REQUIRED_CHECKS_START} and {@code # REQUIRED_CHECKS_END} marker lines — so the recovery-scenario
 * tier's always-runs aggregator cannot be silently dropped from the required-checks list (which
 * would un-block recovery-path PRs from merging with a red recovery-scenario run). Both scripts are
 * guarded because they are independent operator entry points (the {@code .ps1} is the Windows
 * path); dropping the check from either would leave a hole. Mirrors {@link
 * BranchProtectionConfigSmokeContractTest} (the {@code foundation-gate} guard), including the
 * {@code @Tag("contract")}-only tagging: it runs in the {@code backend-contract-tests} tier BEFORE
 * the gate tiers, so a silent removal surfaces early — and NOT {@code @Tag("recovery-integration")}
 * (this is a plain filesystem-parse test that needs no Docker/Testcontainers).
 */
@Tag("contract")
class RecoveryIntegrationGateBranchProtectionSmokeContractTest {

  // Matches both shell double-quoted ("foundation-gate") and PowerShell single-quoted
  // ('foundation-gate') array items so one parser covers both helper scripts.
  private static final Pattern QUOTED_ITEM = Pattern.compile("\"([^\"]+)\"|'([^']+)'");

  @ParameterizedTest(name = "recovery-integration-gate is a required check in {0}")
  @ValueSource(strings = {"configure-branch-protection.sh", "configure-branch-protection.ps1"})
  void recoveryIntegrationGateAppearsInTheRequiredChecksContextsList(String scriptName)
      throws IOException {
    Path script = Path.of("..", "scripts", "ci", scriptName);
    if (!Files.isRegularFile(script)) {
      fail(
          "branch-protection helper missing at "
              + script.toAbsolutePath()
              + " — story 1.23 Task 10 introduces this script");
      return;
    }
    String content = Files.readString(script);
    int start = content.indexOf("# REQUIRED_CHECKS_START");
    int end = content.indexOf("# REQUIRED_CHECKS_END");
    assertTrue(start >= 0, () -> "missing `# REQUIRED_CHECKS_START` marker in " + script);
    assertTrue(
        end > start, () -> "missing `# REQUIRED_CHECKS_END` marker AFTER start in " + script);

    String block = content.substring(start, end);
    List<String> contexts = new ArrayList<>();
    Matcher matcher = QUOTED_ITEM.matcher(block);
    while (matcher.find()) {
      // group(1) = double-quoted (shell); group(2) = single-quoted (PowerShell).
      contexts.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }
    assertFalse(
        contexts.isEmpty(),
        () ->
            "REQUIRED_CHECKS block parsed zero quoted contexts — script format changed "
                + "incompatibly with the smoke test parser: "
                + script);
    assertTrue(
        contexts.contains("recovery-integration-gate"),
        () ->
            "recovery-integration-gate is NOT listed in REQUIRED_CHECKS of "
                + script
                + " — silently removed? A recovery-path PR could then merge with a red "
                + "recovery-scenario run. Parsed contexts: "
                + contexts);
  }
}
