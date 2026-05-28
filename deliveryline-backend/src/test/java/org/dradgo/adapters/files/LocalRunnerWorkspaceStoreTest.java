package org.dradgo.adapters.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.WorkspaceLayout;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.RunnerKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story 3.1 Task 1 — unit tests for {@link LocalRunnerWorkspaceStore}.
 *
 * <p>Mirrors the focused-test pattern from {@code LocalRunnerScratchStoreTest}: prepare creates the
 * three subdirs (with 0700 on POSIX), prepare is idempotent, writeInputBundle is atomic and lands
 * under {@code input/}, tryReadResult honors a missing file with {@code Optional.empty()}, and the
 * containment guard rejects rex-ids whose registered prefix is wrong.
 */
class LocalRunnerWorkspaceStoreTest {

  @TempDir Path tempHome;

  private static final String REX_ID = "rex_dr1234567890";

  private LocalRunnerWorkspaceStore store;

  @BeforeEach
  void setUp() {
    store = new LocalRunnerWorkspaceStore(tempHome.toAbsolutePath().toString());
  }

  @Test
  void prepareCreatesAllThreeSubdirs() throws IOException {
    WorkspaceLayout layout = store.prepare(REX_ID);

    assertThat(Files.isDirectory(layout.root())).isTrue();
    assertThat(Files.isDirectory(layout.input())).isTrue();
    assertThat(Files.isDirectory(layout.output())).isTrue();
    assertThat(Files.isDirectory(layout.logs())).isTrue();
    assertThat(layout.input().getFileName().toString()).isEqualTo("input");
    assertThat(layout.output().getFileName().toString()).isEqualTo("output");
    assertThat(layout.logs().getFileName().toString()).isEqualTo("logs");
  }

  @Test
  void prepareHonorsConfiguredWorkspaceRoot() throws IOException {
    Path customRoot = tempHome.resolve("custom-runner-work");
    Files.createDirectories(customRoot);
    LocalRunnerWorkspaceStore customStore =
        new LocalRunnerWorkspaceStore(tempHome.toAbsolutePath().toString(), customRoot);

    WorkspaceLayout layout = customStore.prepare(REX_ID);

    // The store canonicalizes via toRealPath(), so on Windows the JUnit @TempDir short-path
    // (RUNNER~1) resolves to the long form (runneradmin). Compare against the canonicalized
    // form on both sides.
    Path expectedRoot = customRoot.toRealPath().resolve(REX_ID);
    assertThat(layout.root()).isEqualTo(expectedRoot);
    assertThat(Files.isDirectory(customRoot.resolve(REX_ID).resolve("input"))).isTrue();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void prepareSetsOwnerOnlyPermissionsOnPosix() throws IOException {
    WorkspaceLayout layout = store.prepare(REX_ID);
    Set<PosixFilePermission> rootPerms = Files.getPosixFilePermissions(layout.root());
    assertThat(rootPerms)
        .containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
  }

  @Test
  void prepareIsIdempotent() {
    WorkspaceLayout first = store.prepare(REX_ID);
    Path canary = first.input().resolve("seen");
    try {
      Files.writeString(canary, "still-here");
    } catch (IOException e) {
      throw new AssertionError(e);
    }

    WorkspaceLayout second = store.prepare(REX_ID);

    assertThat(second.root()).isEqualTo(first.root());
    assertThat(Files.exists(canary)).isTrue();
  }

  @Test
  void writeInputBundleLandsAtVersionedFilename() throws IOException {
    WorkspaceLayout layout = store.prepare(REX_ID);
    byte[] bytes = "{\"schemaVersion\":1}".getBytes();

    Path written = store.writeInputBundle(REX_ID, bytes);

    // OQ-4: filename is versioned to match scratch leaf + schema $id.
    assertThat(written.getFileName().toString()).isEqualTo("context-bundle.v1.json");
    assertThat(written.getParent()).isEqualTo(layout.input());
    assertThat(Files.readAllBytes(written)).isEqualTo(bytes);
  }

  @Test
  void tryReadResultReturnsEmptyWhenFileMissing() {
    store.prepare(REX_ID);
    assertThat(store.tryReadResult(REX_ID)).isEmpty();
  }

  @Test
  void tryReadResultReturnsBytesWhenFilePresent() throws IOException {
    WorkspaceLayout layout = store.prepare(REX_ID);
    Path result = layout.output().resolve("runner-result.v1.json");
    Files.writeString(result, "{\"schemaVersion\":1,\"artifactReferences\":[]}");

    assertThat(store.tryReadResult(REX_ID))
        .hasValueSatisfying(bytes -> assertThat(new String(bytes)).contains("artifactReferences"));
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void tryReadResultRejectsSymlinkedResultFile() throws IOException {
    WorkspaceLayout layout = store.prepare(REX_ID);
    Path secret =
        tempHome.resolve("runner-scratch").resolve(REX_ID).resolve("context-bundle.v1.json");
    Files.createDirectories(secret.getParent());
    Files.writeString(secret, "private bundle");
    Files.createSymbolicLink(layout.output().resolve("runner-result.v1.json"), secret);

    assertThat(store.tryReadResult(REX_ID)).isEmpty();
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void prepareRejectsSymlinkedWorkspaceComponent() throws IOException {
    Path outside = Files.createDirectory(tempHome.resolve("outside-workspace"));
    Path root = tempHome.resolve("runner-work").resolve(REX_ID);
    Files.createDirectories(root.getParent());
    Files.createSymbolicLink(root, outside);

    assertThatThrownBy(() -> store.prepare(REX_ID)).isInstanceOf(DomainException.class);
  }

  @Test
  void resolveOutputRootIsEmptyBeforePrepare() {
    assertThat(store.resolveOutputRoot(REX_ID)).isEmpty();
  }

  @Test
  void resolveOutputRootReturnsHostPathAfterPrepare() {
    WorkspaceLayout layout = store.prepare(REX_ID);
    assertThat(store.resolveOutputRoot(REX_ID)).contains(layout.output());
  }

  @Test
  void containmentGuardRejectsMismatchedPrefix() {
    // PublicIdPrefixes guard fires for any non-rex_ public id.
    assertThatThrownBy(() -> store.prepare("run_dr1234567890")).isInstanceOf(DomainException.class);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void writeInputBundleIsAtomic() throws IOException {
    WorkspaceLayout layout = store.prepare(REX_ID);
    byte[] bytes = "ok".getBytes();
    store.writeInputBundle(REX_ID, bytes);

    // Atomic write leaves no .tmp leftover.
    try (var stream = Files.list(layout.input())) {
      assertThat(stream).noneSatisfy(p -> assertThat(p.getFileName().toString()).endsWith(".tmp"));
    }
  }

  @SuppressWarnings("unused")
  private static RunnerProperties dockerProperties(Path workspaceRoot) {
    return new RunnerProperties(
        2.0d,
        java.util.Map.of(),
        10_000L,
        50,
        60_000L,
        5_000L,
        RunnerProperties.Recovery.defaults(),
        RunnerProperties.Mock.defaults(),
        RunnerProperties.Scheduling.defaults(),
        new RunnerProperties.Docker(
            RunnerKind.CODEX,
            java.util.Map.of(
                RunnerKind.CODEX,
                "deliveryline/codex-runner:latest",
                RunnerKind.CLAUDE,
                "deliveryline/claude-runner:latest"),
            workspaceRoot,
            24L,
            3_600_000L,
            java.time.Duration.ofSeconds(30L),
            java.time.Duration.ofSeconds(30L)));
  }
}
