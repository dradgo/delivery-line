package org.dradgo.application.runner.spi;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Host-side layout of a runner workspace. Produced by {@link RunnerWorkspaceStore#prepare(String)};
 * consumed by the Docker runner adapter as the source of truth for bind-mount paths.
 *
 * <p>The three sub-directories map to the container-side mount points {@code /workspace/input},
 * {@code /workspace/output}, {@code /workspace/logs} (story 3.1 OQ-4 + AC2.c). All paths are
 * absolute and canonical ({@link Path#toAbsolutePath()}+{@code normalize()}) so whichever Docker
 * client receives them does not need to reinterpret host-path semantics.
 */
public record WorkspaceLayout(Path root, Path input, Path output, Path logs) {

  public WorkspaceLayout {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(logs, "logs");
  }
}
