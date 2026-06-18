package org.dradgo.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.dradgo.application.integration.ticketsource.TicketSourceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Story 3.32 AC5 / AC10 — config-driven ticket-source selection. The {@code
 * deliveryline.integration.ticket-source.kind} key is the documented selector; {@link
 * TicketSourceProperties} normalizes it and {@link LinearConfiguration} fail-fasts at boot when the
 * configured kind has no implementation on the classpath (only {@code linear} ships today). The
 * load-bearing bean gating remains the Spring {@code linear-mock}/{@code linear-real} profiles
 * (covered by {@code IntegrationProfileWiringContractTest}).
 */
class TicketSourceConfigurationTest {

  private static Environment noProfilesEnvironment() {
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[0]);
    return environment;
  }

  @Test
  void kindDefaultsToLinearWhenUnsetOrBlank() {
    assertEquals(TicketSourceProperties.KIND_LINEAR, new TicketSourceProperties(null).kind());
    assertEquals(TicketSourceProperties.KIND_LINEAR, new TicketSourceProperties("  ").kind());
    assertEquals(TicketSourceProperties.KIND_LINEAR, TicketSourceProperties.defaults().kind());
    assertTrue(TicketSourceProperties.defaults().isLinear());
  }

  @Test
  void kindIsNormalizedToLowerCaseAndStripped() {
    TicketSourceProperties properties = new TicketSourceProperties("  Linear ");
    assertEquals("linear", properties.kind());
    assertTrue(properties.isLinear());
  }

  @Test
  void linearKindBootsWithoutFailing() {
    assertDoesNotThrow(
        () -> new LinearConfiguration(noProfilesEnvironment(), TicketSourceProperties.defaults()));
    assertDoesNotThrow(
        () ->
            new LinearConfiguration(noProfilesEnvironment(), new TicketSourceProperties("linear")));
  }

  @Test
  void unsupportedKindFailsFastAtBoot() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                new LinearConfiguration(
                    noProfilesEnvironment(), new TicketSourceProperties("jira")));
    assertTrue(
        error.getMessage().contains("ticket-source.kind=jira"),
        () -> "expected a fail-fast naming the unsupported kind, was: " + error.getMessage());
  }
}
