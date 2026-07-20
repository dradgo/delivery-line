package org.dradgo.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 4.28 — durable guard that the {@code RECOVERY_SERVICE_IS_SCOPE_PROTECTED} ArchUnit lock
 * (story 1.18 AC11) stays lifted, that its sibling {@code
 * DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED} stays in place (AC8), and that the governing ADR
 * exists with its required sections.
 *
 * <p>This is a <strong>reflection</strong> meta-test, not an ArchUnit {@code .check(fixture)} test:
 * a deleted rule constant cannot be referenced, so the sibling {@code
 * ArchitectureDiagnosticMetaTest} "fire a rule against an invalid fixture" pattern cannot prove a
 * rule's <em>absence</em>. Instead we inspect the declared fields of the two owning classes. A
 * future contributor re-adding the tripwire (or accidentally deleting the sibling lock) fails here
 * with a message pointing at ADR 0033.
 *
 * <p>Tagged {@code @Tag(ARCHITECTURE_TAG)} so it co-locates with the other architecture tests and
 * runs in the Failsafe architecture slice (a removed {@code @ArchTest} is not re-checked by {@code
 * mvnw test}); the reflection + file assertions here run anywhere.
 */
@Tag(ArchitectureRuleCatalog.ARCHITECTURE_TAG)
class RecoveryServiceScopeLiftMetaTest {

  private static final String ADR_RELATIVE_PATH = "docs/adr/0033-recovery-service-scope-lift.md";

  @Test
  void recoveryServiceScopeProtectedRuleConstantIsRemovedFromTheCatalog() {
    assertFalse(
        hasDeclaredField(ArchitectureRuleCatalog.class, "RECOVERY_SERVICE_IS_SCOPE_PROTECTED"),
        "The RECOVERY_SERVICE_IS_SCOPE_PROTECTED rule was lifted by story 4.28 and must stay"
            + " removed from ArchitectureRuleCatalog — RecoveryService's recovery surface is now"
            + " governed by docs/adr/0033-recovery-service-scope-lift.md, not an ArchUnit tripwire."
            + " Do not re-add it; add a recovery method by following ADR 0033 section (e) instead.");
  }

  @Test
  void recoveryServiceScopeProtectedArchTestRegistrationIsRemovedFromTheBoundaryTest() {
    assertFalse(
        hasDeclaredField(ArchitectureBoundaryTest.class, "recovery_service_is_scope_protected"),
        "The recovery_service_is_scope_protected @ArchTest registration was lifted by story 4.28"
            + " and must stay removed from ArchitectureBoundaryTest (see"
            + " docs/adr/0033-recovery-service-scope-lift.md).");
  }

  @Test
  void developerTakeoverSiblingLockRemainsProtected() {
    // AC8 regression guard: the lift is narrowly targeted at RecoveryService only. The sibling
    // DeveloperTakeoverService lock must NOT be collaterally removed.
    assertTrue(
        hasDeclaredField(
            ArchitectureRuleCatalog.class, "DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED"),
        "Story 4.28 AC8 lifts ONLY the RecoveryService lock. The sibling"
            + " DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED rule constant must remain in"
            + " ArchitectureRuleCatalog (see docs/adr/0033-recovery-service-scope-lift.md §Alt 3).");
    assertTrue(
        hasDeclaredField(
            ArchitectureBoundaryTest.class, "developer_takeover_service_is_scope_protected"),
        "Story 4.28 AC8: the developer_takeover_service_is_scope_protected @ArchTest registration"
            + " must remain in ArchitectureBoundaryTest.");
  }

  @Test
  void adr0033ExistsAndContainsAllRequiredSections() {
    Path adr = resolveAdr();
    if (adr == null) {
      fail(
          "ADR 0033 not found. Story 4.28 AC2/AC5 requires "
              + ADR_RELATIVE_PATH
              + " to exist (searched from working dir "
              + Paths.get("").toAbsolutePath()
              + " upward).");
    }

    String body;
    try {
      body = Files.readString(adr);
    } catch (IOException e) {
      fail("Could not read ADR 0033 at " + adr + ": " + e.getMessage());
      return;
    }

    // AC2 requires the governance to document sections (a)–(e); house format requires the standard
    // Context/Decision/Consequences headings.
    for (String heading : new String[] {"## Context", "## Decision", "## Consequences"}) {
      assertTrue(
          body.contains(heading),
          "ADR 0033 must contain the '" + heading + "' section (house ADR format).");
    }
    // Anchor on the bold line-start markers (`**(a)` … `**(e)`) rather than the bare `(a)`
    // substring:
    // a bare `contains("(a)")` would false-pass on incidental prose (e.g. the "process (d)/(e)"
    // mention in Consequences) even if the real governance subsection were deleted.
    for (String marker : new String[] {"**(a)", "**(b)", "**(c)", "**(d)", "**(e)"}) {
      assertTrue(
          body.contains(marker),
          "ADR 0033 must document scope subsection '"
              + marker
              + "' per story 4.28 AC2 (what-was-protected / what-changed / what-is-now-allowed /"
              + " what-is-still-not-allowed / how-to-add-a-method).");
    }
  }

  private static boolean hasDeclaredField(Class<?> owner, String fieldName) {
    return Arrays.stream(owner.getDeclaredFields()).map(Field::getName).anyMatch(fieldName::equals);
  }

  /**
   * Resolves the repo-root-relative ADR path regardless of whether the test's working directory is
   * the backend module or the repository root, by walking up from the working directory.
   */
  private static Path resolveAdr() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve(ADR_RELATIVE_PATH);
      if (Files.exists(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
