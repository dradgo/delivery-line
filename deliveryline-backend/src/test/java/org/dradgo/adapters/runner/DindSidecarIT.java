package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.dradgo.adapters.runner.docker.DefaultDockerEngineGateway;
import org.dradgo.adapters.runner.docker.DindSidecarService;
import org.dradgo.application.runner.TestcontainersProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * DinD Testcontainers Task 8 — end-to-end conformance for the per-run dockerd sidecar against a
 * live Docker daemon. Proves the full seam Tasks 5-7 build: {@link DindSidecarService#provision}
 * boots a privileged {@code docker:<pin>-dind} sidecar on a per-run bridge network (aliased {@code
 * dind}), an independent client container joined to that network reaches the sidecar's daemon over
 * {@code DOCKER_HOST=tcp://dind:2375} (so a real Testcontainers-driven runner would too), and
 * {@link DindSidecarService#teardown} leaves neither the sidecar container nor the network behind.
 *
 * <p>Tagged {@code docker-runner-it} + gated by {@link EnabledIfDockerAvailable}: it needs a live
 * Docker daemon that can run a PRIVILEGED container (Docker-in-Docker), so it is opt-in via the
 * Docker-tier CI on Linux runners and is excluded from the no-Docker PR tier. The two {@code
 * docker:*} images are pulled in {@link #pullImages()} because the gateway uses a raw {@code
 * DockerClient} that does not auto-pull.
 */
@Tag("docker-runner-it")
@EnabledIfDockerAvailable
class DindSidecarIT {

  private static final String REX_ID = "rex_dindit000000001";
  private static final String DIND_LABEL_KEY = "deliveryline.dind";
  private static final TestcontainersProperties PROPS = TestcontainersProperties.defaults();
  // A CLI-only docker image on the same major pin as the dind sidecar — the client probe container.
  private static final String CLI_IMAGE = "docker:27-cli";

  private static DockerClient docker;
  private static DefaultDockerEngineGateway gateway;
  private static DindSidecarService service;

  @BeforeAll
  static void pullImages() throws Exception {
    docker = DockerClientFactory.instance().client();
    gateway = new DefaultDockerEngineGateway(docker);
    service = new DindSidecarService(gateway, PROPS, Clock.systemUTC());
    // The adapter/gateway path never auto-pulls (raw DockerClient) — pre-pull both images so
    // provision's createContainer + the probe's createContainer resolve locally.
    docker
        .pullImageCmd(PROPS.dindImage())
        .exec(new PullImageResultCallback())
        .awaitCompletion(300, TimeUnit.SECONDS);
    docker
        .pullImageCmd(CLI_IMAGE)
        .exec(new PullImageResultCallback())
        .awaitCompletion(180, TimeUnit.SECONDS);
  }

  @Test
  void provisionExposesReachableDaemonThenTeardownLeavesNothing() {
    DindSidecarService.DindHandle handle = null;
    String probeContainerId = null;
    try {
      handle = service.provision(REX_ID);

      // The runner env the adapter injects — the contract a real Testcontainers run relies on.
      assertThat(handle.runnerEnv())
          .containsEntry("DOCKER_HOST", "tcp://dind:2375")
          .containsEntry("TESTCONTAINERS_HOST_OVERRIDE", "dind")
          .containsEntry("TESTCONTAINERS_RYUK_DISABLED", "true");

      // A client container joined to the per-run network must reach the sidecar's daemon by the
      // "dind" alias — `docker version` exits 0 ONLY if the daemon on tcp://dind:2375 answered
      // (the Client+Server blocks both print). This is the runner-reaches-sidecar proof.
      var probe =
          docker
              .createContainerCmd(CLI_IMAGE)
              .withHostConfig(HostConfig.newHostConfig().withNetworkMode(handle.networkName()))
              .withEnv("DOCKER_HOST=tcp://dind:2375")
              .withCmd("version")
              .exec();
      probeContainerId = probe.getId();
      docker.startContainerCmd(probeContainerId).exec();
      int probeExit =
          docker
              .waitContainerCmd(probeContainerId)
              .exec(new WaitContainerResultCallback())
              .awaitStatusCode();
      assertThat(probeExit)
          .as("client `docker version` over tcp://dind:2375 on the per-run network")
          .isZero();
    } finally {
      if (probeContainerId != null) {
        try {
          docker.removeContainerCmd(probeContainerId).withForce(true).exec();
        } catch (RuntimeException ignored) {
          // best-effort
        }
      }
      if (handle != null) {
        service.teardown(REX_ID, handle.networkName(), handle.sidecarContainerId());
      }
    }

    // Teardown left nothing: no sidecar container and no network carrying this run's dind label.
    assertThat(gateway.listContainersByLabel(DIND_LABEL_KEY, "rex_").stream())
        .as("no dind sidecar container remains for %s after teardown", REX_ID)
        .noneMatch(c -> REX_ID.equals(c.runnerExecutionId()));
    assertThat(gateway.listNetworksByLabel(DIND_LABEL_KEY).stream())
        .as("no per-run dind network remains for %s after teardown", REX_ID)
        .noneMatch(n -> REX_ID.equals(n.labelValue()));
  }
}
