package org.dradgo.adapters.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.dradgo.application.runner.TestcontainersProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DindSidecarServiceTest {

  private static final String REX = "rex_dind0000000001";
  private static final String NET = "deliveryline-net-rex_dind0000000001";

  private DockerEngineGateway gateway;
  private DindSidecarService service;

  @BeforeEach
  void setUp() {
    gateway = mock(DockerEngineGateway.class);
    service =
        new DindSidecarService(
            gateway,
            new TestcontainersProperties("docker:27-dind", 2147483648L, Duration.ofSeconds(60)),
            Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC),
            millis -> {
              // no-op: the readiness loop is iteration-bounded, not clock-driven, so the
              // "never healthy" case must not spend real wall-clock time sleeping.
            });
  }

  private ContainerState health(String status) {
    return new ContainerState("running", null, NET, List.of(), Map.of(), null, status);
  }

  @Test
  void provisionCreatesNetworkAndHealthySidecarAndReturnsRunnerEnv() {
    when(gateway.createNetwork(eq(NET), any())).thenReturn("net123");
    when(gateway.createContainer(any())).thenReturn("dind123");
    when(gateway.inspectContainer("dind123")).thenReturn(health("healthy"));

    DindSidecarService.DindHandle handle = service.provision(REX);

    assertThat(handle.networkName()).isEqualTo(NET);
    assertThat(handle.sidecarContainerId()).isEqualTo("dind123");
    assertThat(handle.runnerEnv())
        .containsEntry("DOCKER_HOST", "tcp://dind:2375")
        .containsEntry("TESTCONTAINERS_HOST_OVERRIDE", "dind")
        .containsEntry("TESTCONTAINERS_RYUK_DISABLED", "true");
    // sidecar spec asserted: privileged, alias dind, image, healthcheck, TLS-off env.
    org.mockito.ArgumentCaptor<CreateContainerSpec> spec =
        org.mockito.ArgumentCaptor.forClass(CreateContainerSpec.class);
    verify(gateway).createContainer(spec.capture());
    assertThat(spec.getValue().privileged()).isTrue();
    assertThat(spec.getValue().networkAliases()).containsExactly("dind");
    assertThat(spec.getValue().networkMode()).isEqualTo(NET);
    assertThat(spec.getValue().image()).isEqualTo("docker:27-dind");
    assertThat(spec.getValue().environment()).containsEntry("DOCKER_TLS_CERTDIR", "");
    assertThat(spec.getValue().healthcheck()).isNotNull();
    verify(gateway).startContainer("dind123");
  }

  @Test
  void provisionThrowsAndCleansUpWhenSidecarNeverBecomesHealthy() {
    when(gateway.createNetwork(eq(NET), any())).thenReturn("net123");
    when(gateway.createContainer(any())).thenReturn("dind123");
    when(gateway.inspectContainer("dind123")).thenReturn(health("starting")); // never healthy

    assertThatThrownBy(() -> service.provision(REX))
        .isInstanceOf(DindSidecarService.DindProvisionException.class);

    // partial cleanup: sidecar removed, network removed.
    verify(gateway).removeContainer(eq("dind123"), anyBoolean());
    verify(gateway).removeNetwork(NET);
  }

  @Test
  void teardownRemovesSidecarThenNetworkIdempotently() {
    service.teardown(REX, NET, "dind123");
    verify(gateway).removeContainer("dind123", true);
    verify(gateway).removeNetwork(NET);
    // idempotent: a second call with nulls does not throw
    service.teardown(REX, NET, null);
    verify(gateway, times(1)).removeContainer("dind123", true);
  }
}
