package org.dradgo.application.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

class ArtifactApplicationSeamContractTest {

  @Test
  void artifactServicesExposeTheStoryRequiredApplicationSurface() throws Exception {
    Class<?> artifactService = Class.forName("org.dradgo.application.artifact.ArtifactService");
    Class<?> artifactOperationService =
        Class.forName("org.dradgo.application.artifact.ArtifactOperationService");
    Class<?> artifactReconciliationService =
        Class.forName("org.dradgo.application.artifact.ArtifactReconciliationService");

    assertTrue(artifactService.isAnnotationPresent(Service.class));
    assertTrue(artifactOperationService.isAnnotationPresent(Service.class));
    assertTrue(artifactReconciliationService.isAnnotationPresent(Service.class));

    assertNotNull(
        artifactOperationService.getDeclaredMethod(
            "createDraft",
            String.class,
            Class.forName("org.dradgo.domain.registry.ArtifactType"),
            String.class,
            Class.forName("org.dradgo.application.artifact.ActorContext")));
    assertEquals(1, countMethodsNamed(artifactOperationService, "recordOperation"));
    assertNotNull(findMethod(artifactOperationService, "markAvailable", 4));
    assertNotNull(findMethod(artifactOperationService, "markFailed", 4));
    assertNotNull(findMethod(artifactOperationService, "newVersion", 3));
    assertNotNull(findMethod(artifactService, "isApprovalEligible", 1));
    assertTrue(
        exposesReconciliationScan(artifactReconciliationService),
        () ->
            "ArtifactReconciliationService methods were "
                + Arrays.stream(artifactReconciliationService.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.joining(", ")));
  }

  @Test
  void artifactSpiContractsExistForPersistenceAndPayloadBoundaries() throws Exception {
    Map<String, Class<?>> requiredPorts =
        Map.of(
            "artifact record port",
                Class.forName("org.dradgo.application.artifact.spi.ArtifactRecordPort"),
            "artifact operation port",
                Class.forName("org.dradgo.application.artifact.spi.ArtifactOperationPort"),
            "artifact payload store",
                Class.forName("org.dradgo.application.artifact.spi.ArtifactPayloadStore"),
            "artifact event port",
                Class.forName("org.dradgo.application.artifact.spi.ArtifactEventPort"),
            "artifact runner execution port",
                Class.forName("org.dradgo.application.artifact.spi.ArtifactRunnerExecutionPort"),
            "artifact workflow run state port",
                Class.forName("org.dradgo.application.artifact.spi.ArtifactWorkflowRunStatePort"));

    for (Map.Entry<String, Class<?>> entry : requiredPorts.entrySet()) {
      assertTrue(entry.getValue().isInterface(), () -> entry.getKey() + " must be an interface");
    }

    Class<?> payloadStore = requiredPorts.get("artifact payload store");
    assertNotNull(
        payloadStore.getDeclaredMethod("readBytes", String.class),
        "ArtifactPayloadStore.readBytes(String) is required for approval-eligibility checksum recompute");

    Class<?> operationPort = requiredPorts.get("artifact operation port");
    assertNotNull(
        operationPort.getDeclaredMethod("findPendingOlderThan", java.time.Duration.class),
        "ArtifactOperationPort.findPendingOlderThan(Duration) is required so reconciliation runs the "
            + "staleness comparison DB-side instead of mixing JVM-derived thresholds with DB created_at");
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

  private boolean exposesReconciliationScan(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .map(Method::getName)
        .map(name -> name.toLowerCase(java.util.Locale.ROOT))
        .anyMatch(
            name ->
                name.contains("stale")
                    || name.contains("orphan")
                    || name.contains("reconciliation"));
  }
}
