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
    String image, List<BindMount> binds, String networkMode, Map<String, String> labels) {

  public CreateContainerSpec {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException("image must not be blank");
    }
    binds = binds == null ? List.of() : List.copyOf(binds);
    Objects.requireNonNull(networkMode, "networkMode");
    labels = labels == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(labels));
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
