package org.dradgo.application.runner.workspace.spi;

import java.util.Objects;

/**
 * Project-owned SPI record (story 3a-2 AC6) — a single entry of the depth-bounded top-level tree
 * listing produced by {@link GitCommandPort#listTopLevelTree}. {@code path} is workspace-relative
 * (mount-relative, never a host absolute path — Trap T7); {@code type} discriminates a file leaf
 * from a (possibly depth-truncated) directory.
 */
public record RepoTreeEntry(String path, Type type) {

  public RepoTreeEntry {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path must be non-blank");
    }
    Objects.requireNonNull(type, "type");
  }

  /** Tree-entry kind. {@link #value()} matches the {@code repoTreeEntry.type} schema enum. */
  public enum Type {
    FILE("file"),
    DIR("dir");

    private final String value;

    Type(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }
}
