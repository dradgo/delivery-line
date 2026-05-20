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
import org.junit.jupiter.api.Test;

/**
 * Story 1.23 — branch-protection helper config smoke test.
 *
 * <p>Parses {@code scripts/ci/configure-branch-protection.sh} and asserts that {@code
 * "foundation-gate"} appears in the source-of-truth contexts array between the {@code #
 * REQUIRED_CHECKS_START} and {@code # REQUIRED_CHECKS_END} marker lines. Tagged
 * {@code @Tag("contract")} only — intentionally NOT {@code @Tag("foundation-gate")} because we want
 * a silent removal of the required check to fail the {@code backend-contract-tests} tier BEFORE the
 * {@code foundation-gate} tier runs. That defense-in-depth ordering surfaces the regression early
 * in CI.
 *
 * <p>The path resolves filesystem-relative to repo root, so the test traverses upward from the
 * {@code deliveryline-backend/} CWD.
 */
@Tag("contract")
class BranchProtectionConfigSmokeContractTest {

  private static final Path HELPER_SCRIPT =
      Path.of("..", "scripts", "ci", "configure-branch-protection.sh");
  private static final Pattern QUOTED_ITEM = Pattern.compile("\"([^\"]+)\"");

  @Test
  void foundationGateAppearsInTheRequiredChecksContextsList() throws IOException {
    if (!Files.isRegularFile(HELPER_SCRIPT)) {
      fail(
          "branch-protection helper missing at "
              + HELPER_SCRIPT.toAbsolutePath()
              + " — story 1.23 Task 10 introduces this script");
      return;
    }
    String content = Files.readString(HELPER_SCRIPT);
    int start = content.indexOf("# REQUIRED_CHECKS_START");
    int end = content.indexOf("# REQUIRED_CHECKS_END");
    assertTrue(start >= 0, () -> "missing `# REQUIRED_CHECKS_START` marker in " + HELPER_SCRIPT);
    assertTrue(
        end > start,
        () -> "missing `# REQUIRED_CHECKS_END` marker AFTER start in " + HELPER_SCRIPT);

    String block = content.substring(start, end);
    List<String> contexts = new ArrayList<>();
    Matcher matcher = QUOTED_ITEM.matcher(block);
    while (matcher.find()) {
      contexts.add(matcher.group(1));
    }
    assertFalse(
        contexts.isEmpty(),
        () ->
            "REQUIRED_CHECKS block parsed zero quoted contexts — script format changed "
                + "incompatibly with the smoke test parser");
    assertTrue(
        contexts.contains("foundation-gate"),
        () ->
            "foundation-gate is NOT listed in REQUIRED_CHECKS of "
                + HELPER_SCRIPT
                + " — silently removed? Parsed contexts: "
                + contexts);
  }
}
