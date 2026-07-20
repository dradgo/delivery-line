package org.dradgo.adapters.runner.docker;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.runner.TestcontainersProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Owns the per-run DinD sidecar lifecycle (adapters.runner.docker so it can build
 * CreateContainerSpec and call the gateway directly — it cannot live in application.* under the
 * ArchUnit boundary). One per-run user-defined bridge network + one privileged docker:dind sidecar
 * aliased "dind"; the runner joins the network and drives the sidecar's daemon via DOCKER_HOST.
 *
 * <p>Profile-gated to {@code runners.docker}, mirroring {@link
 * org.dradgo.adapters.runner.DockerRunnerAdapter} and {@code DockerConfiguration}: its {@link
 * DockerEngineGateway} collaborator only exists under that profile, so registering this bean in the
 * fast/mock tiers would fail context load with an unsatisfied dependency. Under {@code
 * runners.mock} it is absent and {@code DockerRunnerAdapter}'s optional setter leaves the sidecar
 * seam null (dispatch behaves as if testcontainers were off).
 */
@Component
@Profile("runners.docker")
public class DindSidecarService {

  private static final Logger log = LoggerFactory.getLogger(DindSidecarService.class);

  static final String NETWORK_NAME_PREFIX = "deliveryline-net-";
  static final String DIND_LABEL_KEY = "deliveryline.dind";
  static final String DIND_ALIAS = "dind";
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

  private final DockerEngineGateway gateway;
  private final TestcontainersProperties properties;
  private final Clock clock;
  private final Sleeper sleeper;

  /**
   * Spring-facing constructor — mirrors {@link org.dradgo.adapters.runner.MockRunnerAdapter} and
   * the other runner components: NO {@link Clock} parameter (there is no {@code Clock} bean in the
   * context), so it defaults {@link Clock#systemUTC()} internally. {@code @Autowired} is required
   * because the class declares more than one constructor (Spring cannot otherwise pick one and
   * falls back to a nonexistent no-arg ctor).
   */
  @Autowired
  public DindSidecarService(DockerEngineGateway gateway, TestcontainersProperties properties) {
    this(gateway, properties, Clock.systemUTC());
  }

  /** Test seam — explicit {@link Clock}. */
  public DindSidecarService(
      DockerEngineGateway gateway, TestcontainersProperties properties, Clock clock) {
    this(
        gateway,
        properties,
        clock,
        millis -> {
          try {
            Thread.sleep(millis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }

  DindSidecarService(
      DockerEngineGateway gateway,
      TestcontainersProperties properties,
      Clock clock,
      Sleeper sleeper) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    if (properties.readinessTimeout().compareTo(POLL_INTERVAL) < 0) {
      // Misconfiguration surfacer (story 3h-5 review): the health poll is iteration-bounded at
      // max(1, readinessTimeout/POLL_INTERVAL), so a small-but-positive readiness under the 2s poll
      // interval collapses to a SINGLE health check and provisioning can time out even when the
      // sidecar would become healthy shortly after. Flag it rather than silently under-polling.
      log.warn(
          "dind readiness timeout {} is shorter than the health poll interval {} — the sidecar will"
              + " get only one health check before timing out; raise"
              + " deliveryline.runner.testcontainers.readiness-timeout to at least the poll interval",
          properties.readinessTimeout(),
          POLL_INTERVAL);
    }
  }

  public record DindHandle(
      String networkName, String sidecarContainerId, Map<String, String> runnerEnv) {}

  public static final class DindProvisionException extends RuntimeException {
    public DindProvisionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  interface Sleeper {
    void sleep(long millis);
  }

  public DindHandle provision(String rexId) {
    Objects.requireNonNull(rexId, "rexId");
    String networkName = NETWORK_NAME_PREFIX + rexId;
    String sidecarId = null;
    try {
      gateway.createNetwork(networkName, Map.of(DIND_LABEL_KEY, rexId));
      sidecarId = gateway.createContainer(sidecarSpec(rexId, networkName));
      gateway.startContainer(sidecarId);
      awaitHealthy(sidecarId);
      Map<String, String> runnerEnv =
          Map.of(
              "DOCKER_HOST",
              "tcp://" + DIND_ALIAS + ":2375",
              "TESTCONTAINERS_HOST_OVERRIDE",
              DIND_ALIAS,
              "TESTCONTAINERS_RYUK_DISABLED",
              "true");
      log.info("dind provisioned rex={} network={} sidecar={}", rexId, networkName, sidecarId);
      return new DindHandle(networkName, sidecarId, runnerEnv);
    } catch (RuntimeException failure) {
      teardown(rexId, networkName, sidecarId);
      throw new DindProvisionException("failed to provision dind sidecar for " + rexId, failure);
    }
  }

  private CreateContainerSpec sidecarSpec(String rexId, String networkName) {
    CreateContainerSpec.Healthcheck hc =
        new CreateContainerSpec.Healthcheck(
            List.of("CMD-SHELL", "docker -H tcp://localhost:2375 version"),
            POLL_INTERVAL,
            Duration.ofSeconds(3),
            30);
    return new CreateContainerSpec(
        properties.dindImage(),
        List.of(),
        networkName,
        Map.of(DIND_LABEL_KEY, rexId),
        Map.of("DOCKER_TLS_CERTDIR", ""),
        List.of(),
        true,
        List.of(DIND_ALIAS),
        properties.memoryBytes(),
        hc);
  }

  /**
   * Polls the sidecar's health status until it reports {@code "healthy"}, throwing on {@code
   * "unhealthy"} or once the poll budget is exhausted.
   *
   * <p>Deliberately ITERATION-BOUNDED rather than deadline-based against {@link #clock}: a deadline
   * computed from {@code clock.instant()} and compared against {@code clock.millis()} never
   * advances under a {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} test clock, which
   * would spin forever when paired with a no-op {@link Sleeper}. The poll budget is derived from
   * {@link TestcontainersProperties#readinessTimeout()} instead, so the loop always terminates
   * after a bounded number of iterations regardless of clock behavior; the real {@link Sleeper}
   * still paces production polls by {@link #POLL_INTERVAL}.
   */
  private void awaitHealthy(String sidecarId) {
    int maxPolls =
        (int) Math.max(1, properties.readinessTimeout().toMillis() / POLL_INTERVAL.toMillis());
    for (int attempt = 0; attempt < maxPolls; attempt++) {
      String health = gateway.inspectContainer(sidecarId).healthStatus();
      if ("healthy".equals(health)) {
        return;
      }
      if ("unhealthy".equals(health)) {
        throw new IllegalStateException("dind sidecar reported unhealthy: " + sidecarId);
      }
      // Skip the pace-sleep on the final iteration — the loop is about to exit and throw, so a
      // sleep here only wastes one POLL_INTERVAL on the timeout path.
      if (attempt < maxPolls - 1) {
        sleeper.sleep(POLL_INTERVAL.toMillis());
      }
    }
    throw new IllegalStateException(
        "dind sidecar not healthy within " + properties.readinessTimeout() + ": " + sidecarId);
  }

  public void teardown(String rexId, String networkName, String sidecarContainerId) {
    if (sidecarContainerId != null) {
      try {
        gateway.removeContainer(sidecarContainerId, true);
      } catch (RuntimeException e) {
        log.warn(
            "dind teardown sidecar rm best-effort failure rex={} cause={}", rexId, e.toString());
      }
    }
    if (networkName != null) {
      try {
        gateway.removeNetwork(networkName);
      } catch (RuntimeException e) {
        log.warn(
            "dind teardown network rm best-effort failure rex={} cause={}", rexId, e.toString());
      }
    }
  }
}
