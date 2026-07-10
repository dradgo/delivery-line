package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.stream.Collectors;
import org.dradgo.application.integration.ticketsource.TicketQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.shell.core.autoconfigure.CommandRegistryAutoConfiguration;
import org.springframework.shell.core.autoconfigure.SpringShellAutoConfiguration;
import org.springframework.shell.core.command.Command;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.annotation.CommandGroup;

/**
 * Story 3i-2 (AC3) — pins the EXACT registered command name {@code deliveryline tickets query}.
 * Spring Shell 4.0.2 composes {@code groupPrefix + " " + @Command.name} (the group {@code name} is
 * help-categorization only), so the group prefix carries {@code "deliveryline tickets"} and the
 * command name is {@code "query"}. Also asserts the new group does not collide with the existing
 * {@code deliveryline submit} / {@code deliveryline operator …} / {@code deliveryline audit …}
 * paths.
 */
class TicketQueryCliCommandRegistrationIT {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CommandRegistryAutoConfiguration.class, SpringShellAutoConfiguration.class))
          .withBean(
              TicketQueryCommands.class,
              () ->
                  new TicketQueryCommands(
                      mock(TicketQueryService.class),
                      new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules()),
                      mock(CliInteractivityDetector.class),
                      () -> "01964c38-1c45-7000-8000-000000000000"));

  @Test
  void ticketsQueryIsRegisteredUnderTheDeliverylineTicketsPath() {
    contextRunner.run(
        context -> {
          CommandRegistry registry = context.getBean(CommandRegistry.class);
          Set<String> commandNames =
              registry.getCommands().stream().map(Command::getName).collect(Collectors.toSet());

          assertTrue(
              commandNames.contains("deliveryline tickets query"),
              () -> "registered commands: " + commandNames);
          // The new group must not shadow the bare `deliveryline query` path.
          assertTrue(
              !commandNames.contains("deliveryline query"),
              () -> "registered commands: " + commandNames);
        });
  }

  @Test
  void ticketQueryCommandsCarryTheExpectedGroupPrefix() {
    CommandGroup group = TicketQueryCommands.class.getAnnotation(CommandGroup.class);
    assertNotNull(group);
    assertEquals("tickets", group.name());
    assertEquals("deliveryline tickets", group.prefix());
  }
}
