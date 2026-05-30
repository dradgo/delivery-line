package org.dradgo.adapters.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.mockito.ArgumentCaptor;

class DefaultDockerEngineGatewayTest {

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void formatHostPathForDockerConvertsWindowsDrivePathForDockerDesktop() {
    assertThat(
            DefaultDockerEngineGateway.formatHostPathForDocker(
                Path.of("C:\\Users\\pc\\runner-work")))
        .isEqualTo("/c/Users/pc/runner-work");
  }

  @Test
  @SuppressWarnings("unchecked")
  void createContainerAppliesEnvironmentViaWithEnv() {
    // Story 3.5 AC3/Trap T8: env is applied through docker-java withEnv (not the CLI). The
    // KEY=VALUE
    // pair lands in Config.Env; this proves the gateway forwards spec.environment() as withEnv
    // args.
    DockerClient client = mock(DockerClient.class);
    CreateContainerCmd cmd = mock(CreateContainerCmd.class);
    CreateContainerResponse response = mock(CreateContainerResponse.class);
    when(client.createContainerCmd(anyString())).thenReturn(cmd);
    when(cmd.withHostConfig(any())).thenReturn(cmd);
    when(cmd.withLabels(any())).thenReturn(cmd);
    when(cmd.withEnv(anyList())).thenReturn(cmd);
    when(cmd.exec()).thenReturn(response);
    when(response.getId()).thenReturn("cid-123");

    DefaultDockerEngineGateway gateway = new DefaultDockerEngineGateway(client);
    CreateContainerSpec spec =
        new CreateContainerSpec(
            "alpine:3.20", List.of(), "none", Map.of(), Map.of("CODEX_API_KEY", "sk-value"));

    String id = gateway.createContainer(spec);

    assertThat(id).isEqualTo("cid-123");
    ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
    verify(cmd).withEnv(envCaptor.capture());
    assertThat(envCaptor.getValue()).containsExactly("CODEX_API_KEY=sk-value");
  }

  @Test
  void createContainerOmitsWithEnvWhenNoEnvironment() {
    DockerClient client = mock(DockerClient.class);
    CreateContainerCmd cmd = mock(CreateContainerCmd.class);
    CreateContainerResponse response = mock(CreateContainerResponse.class);
    when(client.createContainerCmd(anyString())).thenReturn(cmd);
    when(cmd.withHostConfig(any())).thenReturn(cmd);
    when(cmd.withLabels(any())).thenReturn(cmd);
    when(cmd.exec()).thenReturn(response);
    when(response.getId()).thenReturn("cid-456");

    DefaultDockerEngineGateway gateway = new DefaultDockerEngineGateway(client);
    // 4-arg back-compat constructor → empty env map.
    CreateContainerSpec spec = new CreateContainerSpec("alpine:3.20", List.of(), "none", Map.of());

    gateway.createContainer(spec);

    verify(cmd, never()).withEnv(anyList());
  }
}
