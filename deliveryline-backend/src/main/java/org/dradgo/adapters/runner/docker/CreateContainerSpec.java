package org.dradgo.adapters.runner.docker;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Project-owned, Docker-free description of a container the gateway should create.
 *
 * <p>Story 3.1 trap T8: this record (plus {@link ContainerState}) is the boundary. The Docker-java
 * model types ({@code com.github.dockerjava.api.model.HostConfig}, {@code Bind}, etc.) must NOT
 * leak past {@link DockerEngineGateway}; an ArchUnit rule pins this.
 */
public record CreateContainerSpec(
    String image,
    List<BindMount> binds,
    String networkMode,
    Map<String, String> labels,
    Map<String, String> environment,
    List<String> securityOpts) {

  public CreateContainerSpec {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException("image must not be blank");
    }
    binds = binds == null ? List.of() : List.copyOf(binds);
    Objects.requireNonNull(networkMode, "networkMode");
    labels = labels == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(labels));
    // Story 3.5 AC3: runtime env-var injection surface (agent-provider key). Defaults to an empty
    // map and is never null. Values are applied to the container's Config.Env by the gateway via
    // docker-java withEnv — never written to a workspace file and never serialized into the context
    // bundle (Trap T8/T3). ContainerState deliberately carries NO env field, so reading container
    // state back never re-exposes a secret.
    environment = environment == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(environment));
    // Docker security options (e.g. "seccomp=unconfined") applied to the HostConfig by the gateway.
    // Defaults to empty (the locked-down posture); a non-empty list is required for real Codex
    // read-only stages whose bubblewrap sandbox must create an unprivileged user namespace.
    securityOpts = securityOpts == null ? List.of() : List.copyOf(securityOpts);
  }

  /**
   * Back-compat constructor for the lifecycle/label call sites that inject no environment (the mock
   * path and the pre-3.5 lifecycle tests). Delegates to the canonical constructor with an empty env
   * map and no security options.
   */
  public CreateContainerSpec(
      String image, List<BindMount> binds, String networkMode, Map<String, String> labels) {
    this(image, binds, networkMode, labels, Map.of(), List.of());
  }

  /**
   * Back-compat constructor for the story-3.5 call sites that inject env but no security options.
   * Delegates to the canonical constructor with an empty security-opts list.
   */
  public CreateContainerSpec(
      String image,
      List<BindMount> binds,
      String networkMode,
      Map<String, String> labels,
      Map<String, String> environment) {
    this(image, binds, networkMode, labels, environment, List.of());
  }

  /** Single bind-mount entry. {@code readOnly} maps directly to Docker's bind {@code :ro} flag. */
  public record BindMount(Path hostPath, String containerPath, boolean readOnly) {

    public BindMount {
      Objects.requireNonNull(hostPath, "hostPath");
      if (containerPath == null || containerPath.isBlank()) {
        throw new IllegalArgumentException("containerPath must not be blank");
      }
    }
  }
}
