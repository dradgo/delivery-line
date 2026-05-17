package org.dradgo.adapters.integration.linear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.dradgo.application.integration.linear.LinearTicket;
import org.junit.jupiter.api.Test;

/**
 * Deterministic Linear fixture library exit-gate (story 1.14 Task 7). Confirms every
 * production-classpath {@code linear-fixtures/*.json} loads and parses cleanly to a {@link
 * LinearTicket}, and that the three AC8-required ticket categories (feature, bug fix,
 * documentation) are represented exactly once each.
 */
class LinearScenarioContractTest {

  @Test
  void everyDefaultScenarioHasAClasspathFixtureResource() {
    LinearMockScenarioRegistry registry = new LinearMockScenarioRegistry();
    Map<String, LinearMockScenario> all = registry.all();
    assertEquals(
        3, all.size(), "AC8: registry must declare exactly the three production scenarios");
    for (LinearMockScenario scenario : all.values()) {
      assertEquals(
          LinearMockScenario.Behaviour.HAPPY,
          scenario.behaviour(),
          () -> "production scenario " + scenario.ticketRef() + " must be HAPPY");
      assertNotNull(
          scenario.fixtureResource(),
          () -> "HAPPY scenario " + scenario.ticketRef() + " must declare a fixtureResource");
      try (InputStream stream =
          Thread.currentThread()
              .getContextClassLoader()
              .getResourceAsStream(scenario.fixtureResource())) {
        assertNotNull(
            stream,
            () ->
                "missing classpath fixture for "
                    + scenario.ticketRef()
                    + " at "
                    + scenario.fixtureResource());
      } catch (Exception error) {
        throw new AssertionError("failed to read fixture for " + scenario.ticketRef(), error);
      }
    }
  }

  @Test
  void everyFixtureParsesToValidLinearTicket() {
    LinearMockScenarioRegistry registry = new LinearMockScenarioRegistry();
    for (LinearMockScenario scenario : registry.all().values()) {
      LinearTicket ticket = registry.loadHappyFixture(scenario);
      assertEquals(
          scenario.ticketRef(),
          ticket.ticketRef(),
          () -> "fixture ticketRef must match registry key for " + scenario.ticketRef());
      assertFalse(ticket.title().isBlank());
      assertFalse(ticket.summary().isBlank());
      assertFalse(ticket.authorIdentity().isBlank());
      assertNotNull(ticket.createdAt());
      assertNotNull(ticket.updatedAt());
      assertNotNull(ticket.labels());
    }
  }

  @Test
  void ac8RequiresFeatureBugAndDocsCategoriesReachableFromTheLibrary() {
    // AC8: at least three fixture tickets representing (a) bounded low-risk feature,
    // (b) bug fix, (c) documentation. Each carries a "type" label that we use as the
    // observable signal.
    LinearMockScenarioRegistry registry = new LinkedTypeRegistry();
    Set<String> types = new LinkedHashSet<>();
    registry
        .all()
        .values()
        .forEach(
            scenario -> {
              LinearTicket ticket = registry.loadHappyFixture(scenario);
              String type = ticket.labels().get("type");
              if (type != null) {
                types.add(type);
              }
            });
    assertTrue(types.contains("feature"), "AC8 missing feature ticket: " + types);
    assertTrue(types.contains("bug"), "AC8 missing bug-fix ticket: " + types);
    assertTrue(types.contains("docs"), "AC8 missing documentation ticket: " + types);
  }

  @Test
  void ticketRefsUseStableDeterministicReferences() {
    LinearMockScenarioRegistry registry = new LinearMockScenarioRegistry();
    Set<String> refs = registry.all().keySet();
    assertTrue(refs.contains(LinearMockScenarioRegistry.TICKET_FEATURE_LOW_RISK));
    assertTrue(refs.contains(LinearMockScenarioRegistry.TICKET_BUG_FIX));
    assertTrue(refs.contains(LinearMockScenarioRegistry.TICKET_DOCS));
  }

  /** Convenience subclass to make {@code loadHappyFixture} test-visible without exposing extras. */
  private static final class LinkedTypeRegistry extends LinearMockScenarioRegistry {}
}
