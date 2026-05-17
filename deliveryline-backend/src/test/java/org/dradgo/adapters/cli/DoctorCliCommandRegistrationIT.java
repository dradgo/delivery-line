package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.stream.Collectors;
import org.dradgo.application.diagnostics.DoctorService;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.shell.core.autoconfigure.CommandRegistryAutoConfiguration;
import org.springframework.shell.core.autoconfigure.SpringShellAutoConfiguration;
import org.springframework.shell.core.command.Command;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.annotation.CommandGroup;

class DoctorCliCommandRegistrationIT {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final RedactionPolicyService redaction =
      new RedactionPolicyService(new DataClassificationService());

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CommandRegistryAutoConfiguration.class, SpringShellAutoConfiguration.class))
          .withBean(
              DoctorCommands.class,
              () ->
                  new DoctorCommands(
                      mock(DoctorService.class),
                      new DoctorReportRenderer(mapper, redaction),
                      () -> "01964c38-1c45-7000-8000-000000000000"));

  @Test
  void deliverylinePrefixedDoctorCommandIsRegisteredInTheRuntimeShellRegistry() {
    contextRunner.run(
        context -> {
          CommandRegistry registry = context.getBean(CommandRegistry.class);
          Set<String> commandNames =
              registry.getCommands().stream().map(Command::getName).collect(Collectors.toSet());

          assertTrue(
              commandNames.contains("deliveryline doctor"),
              () -> "registered commands: " + commandNames);
        });
  }

  @Test
  void doctorCommandsCarryTheExpectedGroupMetadata() {
    CommandGroup group = DoctorCommands.class.getAnnotation(CommandGroup.class);
    assertNotNull(group);
    assertEquals("doctor", group.name());
    assertEquals("deliveryline", group.prefix());
  }
}
