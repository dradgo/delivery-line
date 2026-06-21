package org.dradgo.adapters.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

  @Test
  @SuppressWarnings("unchecked")
  void followContainerLogsSplitsFramesIntoLinesAcrossFrameBoundaries() throws Exception {
    // Story 3d-5 — the riskiest gateway logic: frame payloads split into lines, a partial line
    // straddling two frames is emitted once intact, and the final unterminated line flushes on
    // completion. Exercised without real Docker by capturing the ResultCallback and feeding frames.
    DockerClient client = mock(DockerClient.class);
    LogContainerCmd cmd = mock(LogContainerCmd.class);
    when(client.logContainerCmd(anyString())).thenReturn(cmd);
    when(cmd.withStdOut(anyBoolean())).thenReturn(cmd);
    when(cmd.withStdErr(anyBoolean())).thenReturn(cmd);
    when(cmd.withFollowStream(anyBoolean())).thenReturn(cmd);
    when(cmd.withTimestamps(anyBoolean())).thenReturn(cmd);
    when(cmd.withTail(anyInt())).thenReturn(cmd);

    DefaultDockerEngineGateway gateway = new DefaultDockerEngineGateway(client);
    List<String> lines = new ArrayList<>();
    AtomicBoolean ended = new AtomicBoolean();

    AutoCloseable handle =
        gateway.followContainerLogs(
            "cid-789", (stream, line) -> lines.add(stream + ":" + line), () -> ended.set(true));

    ArgumentCaptor<ResultCallback<Frame>> callbackCaptor =
        ArgumentCaptor.forClass(ResultCallback.class);
    verify(cmd).exec(callbackCaptor.capture());
    ResultCallback<Frame> callback = callbackCaptor.getValue();

    callback.onNext(new Frame(StreamType.STDOUT, "hello ".getBytes(StandardCharsets.UTF_8)));
    callback.onNext(
        new Frame(StreamType.STDOUT, "world\npartial".getBytes(StandardCharsets.UTF_8)));
    callback.onNext(new Frame(StreamType.STDERR, "err line\n".getBytes(StandardCharsets.UTF_8)));
    callback.onComplete();

    // "hello world" assembled across two frames; "err line" from stderr; "partial" flushed at end.
    assertThat(lines).containsExactly("stdout:hello world", "stderr:err line", "stdout:partial");
    assertThat(ended).isTrue();
    handle.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  void followContainerLogsDecodesMultiByteUtf8SplitAcrossFrames() throws Exception {
    // Story 3d-5 review (P2) — a UTF-8 codepoint whose bytes straddle two frames must be decoded
    // ONCE at the line boundary, not per-frame (per-frame decoding yields U+FFFD mojibake). The
    // euro sign U+20AC is 0xE2 0x82 0xAC; split it across the frame boundary.
    DockerClient client = mock(DockerClient.class);
    LogContainerCmd cmd = mock(LogContainerCmd.class);
    when(client.logContainerCmd(anyString())).thenReturn(cmd);
    when(cmd.withStdOut(anyBoolean())).thenReturn(cmd);
    when(cmd.withStdErr(anyBoolean())).thenReturn(cmd);
    when(cmd.withFollowStream(anyBoolean())).thenReturn(cmd);
    when(cmd.withTimestamps(anyBoolean())).thenReturn(cmd);
    when(cmd.withTail(anyInt())).thenReturn(cmd);

    DefaultDockerEngineGateway gateway = new DefaultDockerEngineGateway(client);
    List<String> lines = new ArrayList<>();

    gateway.followContainerLogs("cid-utf8", (stream, line) -> lines.add(line), () -> {});

    ArgumentCaptor<ResultCallback<Frame>> callbackCaptor =
        ArgumentCaptor.forClass(ResultCallback.class);
    verify(cmd).exec(callbackCaptor.capture());
    ResultCallback<Frame> callback = callbackCaptor.getValue();

    // Frame 1 ends mid-codepoint (first byte of '€'); frame 2 carries the remaining two bytes.
    callback.onNext(
        new Frame(StreamType.STDOUT, new byte[] {'p', 'r', 'i', 'c', 'e', ' ', (byte) 0xE2}));
    callback.onNext(new Frame(StreamType.STDOUT, new byte[] {(byte) 0x82, (byte) 0xAC, '\n'}));

    assertThat(lines).containsExactly("price €");
  }
}
