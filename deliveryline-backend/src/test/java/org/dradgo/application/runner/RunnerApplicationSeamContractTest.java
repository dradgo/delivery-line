package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Mirrors {@code ArtifactApplicationSeamContractTest}: pins the story-required public surface for
 * the runner application slice so accidental signature drift fails CI.
 */
class RunnerApplicationSeamContractTest {

  @Test
  void runnerServicesExposeTheStoryRequiredApplicationSurface() throws Exception {
    Class<?> broker = Class.forName("org.dradgo.application.runner.RunnerBroker");
    Class<?> executionService =
        Class.forName("org.dradgo.application.runner.RunnerExecutionService");
    Class<?> contextBundleService =
        Class.forName("org.dradgo.application.runner.ContextBundleService");

    assertTrue(hasSpringStereotype(broker), "RunnerBroker must be a Spring stereotype bean");
    assertTrue(executionService.isAnnotationPresent(Service.class));
    assertTrue(contextBundleService.isAnnotationPresent(Service.class));

    assertEquals(1, countMethodsNamed(broker, "dispatch"));
    assertEquals(1, countMethodsNamed(broker, "onResult"));
    assertEquals(1, countMethodsNamed(broker, "scanForTimeouts"));
    assertEquals(1, countMethodsNamed(broker, "recoverOnStartup"));

    assertEquals(1, countMethodsNamed(executionService, "markRunning"));
    assertEquals(1, countMethodsNamed(executionService, "recordCompleted"));
    assertEquals(1, countMethodsNamed(executionService, "recordFailed"));
    assertEquals(1, countMethodsNamed(executionService, "recordTimedOut"));
    assertEquals(1, countMethodsNamed(executionService, "recordOrphaned"));
    // touchActivity has two intentional overloads: a 2-arg (id, staleWindow) convenience for
    // poll-driven "running" pings, and a 3-arg (id, activityAt, staleWindow) for heartbeat
    // payloads that carry an explicit timestamp. Both are wired by RunnerBroker.processSinglePoll.
    assertNotNull(findMethod(executionService, "touchActivity", 2));
    assertNotNull(findMethod(executionService, "touchActivity", 3));

    assertNotNull(findMethod(contextBundleService, "create", 7));
  }

  @Test
  void runnerSpiContractsExistForPersistenceAdapterAndExternalRunnerBoundaries() throws Exception {
    Map<String, Class<?>> requiredPorts =
        Map.of(
            "runner adapter", Class.forName("org.dradgo.application.runner.spi.RunnerAdapter"),
            "runner execution record port",
                Class.forName("org.dradgo.application.runner.spi.RunnerExecutionRecordPort"),
            "runner execution event port",
                Class.forName("org.dradgo.application.runner.spi.RunnerExecutionEventPort"),
            "ticket summary provider",
                Class.forName("org.dradgo.application.runner.spi.TicketSummaryProvider"));

    for (Map.Entry<String, Class<?>> entry : requiredPorts.entrySet()) {
      assertTrue(entry.getValue().isInterface(), () -> entry.getKey() + " must be an interface");
    }

    Class<?> runnerAdapter = requiredPorts.get("runner adapter");
    assertNotNull(
        runnerAdapter.getDeclaredMethod(
            "dispatch", Class.forName("org.dradgo.application.runner.RunnerDispatchRequest")));
    assertNotNull(runnerAdapter.getDeclaredMethod("poll", String.class));
    assertNotNull(runnerAdapter.getDeclaredMethod("tryReadResult", String.class));
    assertNotNull(runnerAdapter.getDeclaredMethod("cancel", String.class));

    Class<?> recordPort = requiredPorts.get("runner execution record port");
    assertNotNull(findMethod(recordPort, "insertPending", 5));
    assertNotNull(findMethod(recordPort, "transitionToRunning", 2));
    assertNotNull(findMethod(recordPort, "markFailed", 3));
    assertNotNull(findMethod(recordPort, "markTimedOut", 2));
    assertNotNull(findMethod(recordPort, "markOrphaned", 2));
  }

  @Test
  void runnerResultTypesAreSealedAndNamedAsResults() throws Exception {
    Class<?> dispatchResult = Class.forName("org.dradgo.application.runner.RunnerDispatchResult");
    assertTrue(dispatchResult.isSealed(), "RunnerDispatchResult must be sealed");

    Class<?> pollStatus = Class.forName("org.dradgo.application.runner.RunnerPollStatus");
    assertTrue(pollStatus.isSealed(), "RunnerPollStatus must be sealed");

    Class<?>[] dispatchPermitted = dispatchResult.getPermittedSubclasses();
    assertEquals(
        4,
        dispatchPermitted.length,
        "Dispatched + Replayed + Queued (story 3.17b) + Parked (story 3d-3) are the only permitted"
            + " RunnerDispatchResult subtypes");
  }

  private boolean hasSpringStereotype(Class<?> type) {
    for (Annotation annotation : type.getAnnotations()) {
      Class<? extends Annotation> annotationType = annotation.annotationType();
      if (annotationType == Service.class || annotationType == Component.class) {
        return true;
      }
    }
    return false;
  }

  private long countMethodsNamed(Class<?> type, String methodName) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(method -> method.getName().equals(methodName))
        .count();
  }

  private Method findMethod(Class<?> type, String methodName, int parameterCount) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(method -> method.getName().equals(methodName))
        .filter(method -> method.getParameterCount() == parameterCount)
        .findFirst()
        .orElse(null);
  }
}
