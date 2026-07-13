package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.shell.core.autoconfigure.CommandRegistryAutoConfiguration;
import org.springframework.shell.core.autoconfigure.SpringShellAutoConfiguration;
import org.springframework.shell.core.command.Command;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;

/**
 * Story 4.1 (AC1/Reconciliation 10) — pins the EXACT registered command name {@code deliveryline
 * operator status}. Spring Shell 4.0.2 composes {@code groupPrefix + " " + @Command.name} (the
 * group {@code name} is help-categorization only), so the group prefix carries {@code "deliveryline
 * operator"}. Mirrors {@code WorkflowCliCommandRegistrationIT}.
 */
class OperatorCliCommandRegistrationIT {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CommandRegistryAutoConfiguration.class, SpringShellAutoConfiguration.class))
          .withBean(
              OperatorCommands.class,
              () ->
                  new OperatorCommands(
                      mock(WorkflowInspectionService.class),
                      new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules()),
                      mock(CliInteractivityDetector.class),
                      mock(RecoveryService.class),
                      new IdempotencyKeyValidator(),
                      new LocalActorIdentityResolver("local-operator"),
                      () -> "01964c38-1c45-7000-8000-000000000000",
                      () -> "01964c38-1c45-7000-8000-000000000001"));

  @Test
  void operatorStatusIsRegisteredUnderTheDeliverylineOperatorPath() {
    contextRunner.run(
        context -> {
          CommandRegistry registry = context.getBean(CommandRegistry.class);
          Set<String> commandNames =
              registry.getCommands().stream().map(Command::getName).collect(Collectors.toSet());

          assertTrue(
              commandNames.contains("deliveryline operator status"),
              () -> "registered commands: " + commandNames);
          // Story 4.4 (AC3) — the deep-dive diagnose command registers under the same group prefix.
          assertTrue(
              commandNames.contains("deliveryline operator diagnose"),
              () -> "registered commands: " + commandNames);
          // Story 4.10 (AC6) — the mutating resume command registers under the same group prefix.
          assertTrue(
              commandNames.contains("deliveryline operator resume"),
              () -> "registered commands: " + commandNames);
          // Story 4.11 (AC6) — the mutating reconcile command registers under the same group
          // prefix.
          assertTrue(
              commandNames.contains("deliveryline operator reconcile"),
              () -> "registered commands: " + commandNames);
          // Story 4.12 (AC6) — the mutating rerun-from-step command registers under the same group
          // prefix.
          assertTrue(
              commandNames.contains("deliveryline operator rerun-from-step"),
              () -> "registered commands: " + commandNames);
          // Story 4.13 (AC6) — the mutating pause command registers under the same group prefix.
          assertTrue(
              commandNames.contains("deliveryline operator pause"),
              () -> "registered commands: " + commandNames);
        });
  }

  @Test
  void operatorCommandsCarryTheExpectedGroupPrefixAndOptionMetadata() throws Exception {
    CommandGroup group = OperatorCommands.class.getAnnotation(CommandGroup.class);
    assertNotNull(group);
    assertEquals("operator", group.name());
    assertEquals("deliveryline operator", group.prefix());

    Option stateOption = method().getParameters()[0].getAnnotation(Option.class);
    assertNotNull(stateOption);
    assertEquals("state", stateOption.longName());
  }

  private static Method method() throws Exception {
    return OperatorCommands.class.getMethod(
        "status", String.class, String.class, String.class, int.class, String.class, boolean.class);
  }
}
