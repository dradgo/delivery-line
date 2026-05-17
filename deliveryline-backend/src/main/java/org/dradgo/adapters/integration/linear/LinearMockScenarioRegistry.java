package org.dradgo.adapters.integration.linear;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-memory deterministic registry of {@link LinearMockScenario} entries keyed by {@code
 * ticketRef}. Production-classpath fixtures live under {@code src/main/resources/linear-fixtures/};
 * tests may register adversarial scenarios via {@link #register(LinearMockScenario)} (cleanup with
 * {@link #clearTestScenarios()}).
 *
 * <p>Profile-activated by {@code linear-mock} so the bean only loads when the mock Linear stack is
 * wired (mirrors {@code MockRunnerScenarioRegistry}).
 *
 * <p>Determinism note: fixture JSON files contain absolute {@code createdAt}/{@code updatedAt}
 * instants — no wall-clock arithmetic happens inside the registry. Tests that need a different
 * temporal layout should register a custom scenario with its own fixture.
 */
@Component
@Profile("linear-mock")
public class LinearMockScenarioRegistry {

  private static final Logger log = LoggerFactory.getLogger(LinearMockScenarioRegistry.class);

  private static final String CLASSPATH_PREFIX = "linear-fixtures/";

  /** Stable ticket references used by the three production fixtures (AC8). */
  public static final String TICKET_FEATURE_LOW_RISK = "LIN-101";

  public static final String TICKET_BUG_FIX = "LIN-102";
  public static final String TICKET_DOCS = "LIN-103";

  /** Stable ticket references for documented adversarial test scenarios (registered by tests). */
  public static final String TICKET_NOT_FOUND = "LIN-NOT-FOUND";

  public static final String TICKET_RATE_LIMITED = "LIN-RATE-LIMITED";
  public static final String TICKET_NETWORK_FAILURE = "LIN-NETWORK-FAILURE";
  public static final String TICKET_AUTH_FAILURE = "LIN-AUTH-FAILURE";
  public static final String TICKET_MALFORMED = "LIN-MALFORMED";

  private static final String TEST_TICKET_PREFIX = "TEST-";

  private final Map<String, LinearMockScenario> scenarios = new LinkedHashMap<>();
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public LinearMockScenarioRegistry() {
    registerDefault(
        TICKET_FEATURE_LOW_RISK,
        LinearMockScenario.Behaviour.HAPPY,
        "linear-feature-low-risk.json",
        null);
    registerDefault(
        TICKET_BUG_FIX, LinearMockScenario.Behaviour.HAPPY, "linear-bug-fix.json", null);
    registerDefault(TICKET_DOCS, LinearMockScenario.Behaviour.HAPPY, "linear-docs.json", null);
  }

  private void registerDefault(
      String ticketRef,
      LinearMockScenario.Behaviour behaviour,
      String fixtureFilename,
      IntegrationFailureCategory expected) {
    String resource = fixtureFilename == null ? null : CLASSPATH_PREFIX + fixtureFilename;
    scenarios.put(ticketRef, new LinearMockScenario(ticketRef, behaviour, resource, expected));
  }

  public void register(LinearMockScenario scenario) {
    Objects.requireNonNull(scenario, "scenario");
    scenarios.put(scenario.ticketRef(), scenario);
  }

  public Optional<LinearMockScenario> find(String ticketRef) {
    return Optional.ofNullable(scenarios.get(ticketRef));
  }

  public Map<String, LinearMockScenario> all() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(scenarios));
  }

  /**
   * Loads the fixture JSON for a {@link LinearMockScenario.Behaviour#HAPPY} scenario and returns
   * the deserialized {@link LinearTicket}. Throws {@link IllegalStateException} for scenarios
   * without a fixture (callers must check the behaviour first).
   */
  public LinearTicket loadHappyFixture(LinearMockScenario scenario) {
    Objects.requireNonNull(scenario, "scenario");
    if (scenario.behaviour() != LinearMockScenario.Behaviour.HAPPY) {
      throw new IllegalStateException(
          "loadHappyFixture only supports HAPPY scenarios (ticketRef="
              + scenario.ticketRef()
              + ")");
    }
    String resource = scenario.fixtureResource();
    try (InputStream stream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing Linear mock fixture: " + resource);
      }
      LinearTicketFixtureDocument document =
          objectMapper.readValue(stream, LinearTicketFixtureDocument.class);
      return document.toLinearTicket();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to parse Linear mock fixture " + resource, error);
    }
  }

  /**
   * Drops any scenario whose ticketRef begins with {@code TEST-} — the conventional prefix for
   * test-registered scenarios. Built-in fixtures (LIN-101/102/103) are preserved.
   */
  public void clearTestScenarios() {
    int before = scenarios.size();
    scenarios.entrySet().removeIf(entry -> entry.getKey().startsWith(TEST_TICKET_PREFIX));
    int removed = before - scenarios.size();
    if (removed > 0) {
      log.debug("LinearMockScenarioRegistry cleared {} test scenarios", removed);
    }
  }
}
