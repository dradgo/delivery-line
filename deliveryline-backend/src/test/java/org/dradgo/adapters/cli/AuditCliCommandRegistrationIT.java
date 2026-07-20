package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.stream.Collectors;
import org.dradgo.application.audit.AuditQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.shell.core.autoconfigure.CommandRegistryAutoConfiguration;
import org.springframework.shell.core.autoconfigure.SpringShellAutoConfiguration;
import org.springframework.shell.core.command.Command;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.annotation.CommandGroup;

/**
 * Story 4.3 (AC4) — pins the EXACT registered command name {@code deliveryline audit query}. Spring
 * Shell 4.0.2 composes {@code groupPrefix + " " + @Command.name} (the group {@code name} is
 * help-categorization only — {@code OperatorCliCommandRegistrationIT}), so the group prefix carries
 * {@code "deliveryline audit"} and the command name is {@code "query"}.
 */
class AuditCliCommandRegistrationIT {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CommandRegistryAutoConfiguration.class, SpringShellAutoConfiguration.class))
          .withBean(
              AuditCommands.class,
              () ->
                  new AuditCommands(
                      mock(AuditQueryService.class),
                      new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules()),
                      mock(CliInteractivityDetector.class),
                      () -> "01964c38-1c45-7000-8000-000000000000"));

  @Test
  void auditQueryIsRegisteredUnderTheDeliverylineAuditPath() {
    contextRunner.run(
        context -> {
          CommandRegistry registry = context.getBean(CommandRegistry.class);
          Set<String> commandNames =
              registry.getCommands().stream().map(Command::getName).collect(Collectors.toSet());

          assertTrue(
              commandNames.contains("deliveryline audit query"),
              () -> "registered commands: " + commandNames);
        });
  }

  @Test
  void auditCommandsCarryTheExpectedGroupPrefix() {
    CommandGroup group = AuditCommands.class.getAnnotation(CommandGroup.class);
    assertNotNull(group);
    assertEquals("audit", group.name());
    assertEquals("deliveryline audit", group.prefix());
  }
}
