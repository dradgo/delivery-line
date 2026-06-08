package org.dradgo.adapters.runner.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import java.time.Duration;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.DockerHostPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Story 3.1 Task 6 — Spring wiring for the Docker runner adapter slice. Profile-gated to {@code
 * runners.docker} (trap T10): contributors without a local Docker daemon can boot the fast tier
 * without the client trying to probe {@code unix:///var/run/docker.sock} on startup.
 *
 * <p>{@link DockerClient} construction picks up {@code DOCKER_HOST} from the environment by default
 * (matching Testcontainers' {@code DockerClientFactory} behavior on Windows / macOS via Docker
 * Desktop).
 *
 * <p>Both beans use {@link ConditionalOnMissingBean} so slice tests that stub {@link
 * DockerEngineGateway} (e.g. {@code DockerRunnerProfileWiringContractTest}) win without dragging
 * the real Docker client into the slice.
 */
@Configuration
@Profile("runners.docker")
class DockerConfiguration {

  @Bean
  @ConditionalOnMissingBean(DockerClient.class)
  DockerClient dockerClient(RunnerProperties runnerProperties) {
    DockerClientConfig clientConfig =
        DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    Duration responseTimeout =
        max(
            runnerProperties.docker().containerCreateTimeout(),
            runnerProperties.docker().containerStartTimeout());
    DockerHttpClient httpClient =
        new ApacheDockerHttpClient.Builder()
            .dockerHost(clientConfig.getDockerHost())
            .sslConfig(clientConfig.getSSLConfig())
            .responseTimeout(responseTimeout)
            .build();
    return DockerClientImpl.getInstance(clientConfig, httpClient);
  }

  @Bean
  @ConditionalOnMissingBean(DockerEngineGateway.class)
  DockerEngineGateway dockerEngineGateway(DockerClient dockerClient) {
    return new DefaultDockerEngineGateway(dockerClient);
  }

  /**
   * Story 3.2: expose the engine gateway under the {@link DockerHostPort} application port so the
   * {@code RunnerWorkspaceCleanupJob} can stay in {@code application.runner} without crossing the
   * Adapters-mayNotBeAccessedByAnyLayer architectural boundary. {@link ConditionalOnMissingBean}
   * keeps slice tests that stub the port in control.
   *
   * <p>{@link Primary}: the {@code dockerEngineGateway} bean's instance ({@link
   * DefaultDockerEngineGateway}) also implements {@link DockerHostPort}, so a by-type {@code
   * DockerHostPort} injection would otherwise see two candidates ({@code dockerEngineGateway} +
   * {@code dockerHostPort}) and fail with {@code NoUniqueBeanDefinitionException}. The
   * {@code @ConditionalOnMissingBean(DockerHostPort.class)} guard above can't prevent this because
   * it's evaluated against the gateway bean's declared return type ({@code DockerEngineGateway}),
   * which does not expose {@code DockerHostPort}. Marking this canonical port bean primary resolves
   * the ambiguity; a slice test supplying its own {@code DockerHostPort} suppresses this bean
   * entirely (so the {@code @Primary} never competes there).
   */
  @Bean
  @Primary
  @ConditionalOnMissingBean(DockerHostPort.class)
  DockerHostPort dockerHostPort(DockerEngineGateway gateway) {
    return (DockerHostPort) gateway;
  }

  private static Duration max(Duration left, Duration right) {
    return left.compareTo(right) >= 0 ? left : right;
  }
}
