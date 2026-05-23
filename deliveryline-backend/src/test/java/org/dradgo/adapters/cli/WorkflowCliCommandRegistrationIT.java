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
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.shell.core.autoconfigure.CommandRegistryAutoConfiguration;
import org.springframework.shell.core.autoconfigure.SpringShellAutoConfiguration;
import org.springframework.shell.core.command.Command;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.CommandGroup;

class WorkflowCliCommandRegistrationIT {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CommandRegistryAutoConfiguration.class, SpringShellAutoConfiguration.class))
          .withBean(
              WorkflowCommands.class,
              () ->
                  new WorkflowCommands(
                      mock(WorkflowCommandService.class),
                      mock(WorkflowInspectionService.class),
                      new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules()),
                      () -> false,
                      () -> "01964c38-1c45-7000-8000-000000000000",
                      () -> "01964c38-1c45-7000-8000-000000000001",
                      new IdempotencyKeyValidator(),
                      mock(RecoveryService.class)));

  @Test
  void deliverylinePrefixedWorkflowCommandsAreRegisteredInTheRuntimeShellRegistry() {
    contextRunner.run(
        context -> {
          CommandRegistry registry = context.getBean(CommandRegistry.class);
          Set<String> commandNames =
              registry.getCommands().stream().map(Command::getName).collect(Collectors.toSet());

          assertTrue(
              commandNames.contains("deliveryline submit"),
              () -> "registered commands: " + commandNames);
          assertTrue(
              commandNames.contains("deliveryline status"),
              () -> "registered commands: " + commandNames);
          assertTrue(
              commandNames.contains("deliveryline history"),
              () -> "registered commands: " + commandNames);
          assertTrue(
              commandNames.contains("deliveryline retry"),
              () -> "registered commands: " + commandNames);
        });
  }

  @Test
  void workflowCommandsCarryTheExpectedGroupAndPositionalArgumentMetadata() throws Exception {
    CommandGroup group = WorkflowCommands.class.getAnnotation(CommandGroup.class);
    assertNotNull(group);
    assertEquals("workflow", group.name());
    assertEquals("deliveryline", group.prefix());

    Argument statusArgument = method("status").getParameters()[0].getAnnotation(Argument.class);
    Argument historyArgument = method("history").getParameters()[0].getAnnotation(Argument.class);

    assertNotNull(statusArgument);
    assertEquals(0, statusArgument.index());
    assertNotNull(historyArgument);
    assertEquals(0, historyArgument.index());
  }

  private static Method method(String methodName) throws Exception {
    // Story 2.8 added `--include-context-bundle` (5th arg) on status; story 1.19 added `--verbose`
    // (4th arg) on status/history. The reflective lookup signature must match the current shape.
    return switch (methodName) {
      case "status" ->
          WorkflowCommands.class.getMethod(
              "status", String.class, String.class, String.class, boolean.class, boolean.class);
      case "history" ->
          WorkflowCommands.class.getMethod(
              "history", String.class, String.class, String.class, String.class, boolean.class);
      default -> throw new IllegalArgumentException("Unknown method: " + methodName);
    };
  }
}
