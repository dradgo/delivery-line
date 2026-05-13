package org.dradgo.adapters.integration.linear;

import java.util.Objects;
import org.dradgo.domain.registry.IntegrationFailureCategory;

/**
 * Single deterministic behaviour for a Linear mock ticket. Keyed by {@code ticketRef} in
 * {@link LinearMockScenarioRegistry}. Mirrors the 1-13 {@code MockRunnerScenario} shape; the only
 * non-fixture-driven entries are {@link Behaviour#NOT_FOUND} (no fixture) and the adversarial
 * test-only branches ({@link Behaviour#RATE_LIMITED}, {@link Behaviour#NETWORK_FAILURE},
 * {@link Behaviour#AUTH_FAILURE}, {@link Behaviour#MALFORMED_RESPONSE}) which simulate failure
 * paths without loading a fixture body.
 *
 * <p>{@code fixtureResource} is a classpath path (e.g.,
 * {@code "linear-fixtures/linear-bug-fix.json"}) — production fixtures live under
 * {@code src/main/resources/linear-fixtures/} and adversarial-failure markers live under
 * {@code src/test/resources/linear-fixtures/} so the latter never leak into the
 * {@code demo}/{@code local} runtime classpath.
 *
 * <p>{@code expectedFailureCategory} is the {@link IntegrationFailureCategory} the
 * application/contract tests expect to surface when the scenario fires — {@code null} for
 * happy-path scenarios.
 */
public record LinearMockScenario(
	String ticketRef,
	Behaviour behaviour,
	String fixtureResource,
	IntegrationFailureCategory expectedFailureCategory
) {

	public LinearMockScenario {
		Objects.requireNonNull(ticketRef, "ticketRef");
		Objects.requireNonNull(behaviour, "behaviour");
		if (behaviour == Behaviour.HAPPY) {
			Objects.requireNonNull(fixtureResource,
				"HAPPY scenarios must reference a fixture resource (ticketRef=" + ticketRef + ")");
		}
	}

	public enum Behaviour {
		/** Loads the fixture JSON and returns it as a {@link LinearTicket}. */
		HAPPY,

		/** {@code fetchTicketByReference} returns {@link java.util.Optional#empty()} (AC7 path). */
		NOT_FOUND,

		/** Simulates HTTP 429 on the real adapter; mock throws a runtime tagged with the category. */
		RATE_LIMITED,

		/** Simulates timeout / connection refused; mock throws a runtime tagged with the category. */
		NETWORK_FAILURE,

		/** Simulates HTTP 401/403; mock throws a runtime tagged with the category. */
		AUTH_FAILURE,

		/** Simulates a GraphQL response that fails to deserialize to the expected shape. */
		MALFORMED_RESPONSE
	}
}
