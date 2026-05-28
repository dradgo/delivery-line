package org.dradgo.adapters.files;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.spi.WorkspaceLayout;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Filesystem implementation of {@link RunnerWorkspaceStore} for the configured runner workspace
 * tree, normally {@code {deliveryline.home}/runner-work/{rex_id}/{input,output,logs}/}.
 *
 * <p>Containment guards mirror {@link LocalRunnerScratchStore}: the {@code rex_} prefix is
 * validated via {@link PublicIdPrefixes}; resolved paths must remain under {@code workspace root};
 * reads use {@link LinkOption#NOFOLLOW_LINKS} so a symlink swap cannot pull bytes out of the
 * workspace boundary.
 */
@Component
public class LocalRunnerWorkspaceStore implements RunnerWorkspaceStore {

  private static final Logger log = LoggerFactory.getLogger(LocalRunnerWorkspaceStore.class);

  private static final String INPUT_SUBDIR = "input";
  private static final String OUTPUT_SUBDIR = "output";
  private static final String LOGS_SUBDIR = "logs";
  private static final String CONTEXT_BUNDLE_FILENAME = "context-bundle.v1.json";
  private static final String RUNNER_RESULT_FILENAME = "runner-result.v1.json";
  private static final String TEMP_SUFFIX = ".tmp";

  private static final Set<PosixFilePermission> OWNER_ONLY_DIR_PERMS =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);

  private final Path deliverylineHome;
  private final Path workspaceRoot;

  @org.springframework.beans.factory.annotation.Autowired
  public LocalRunnerWorkspaceStore(
      @Value("${deliveryline.home}") String deliverylineHome, RunnerProperties runnerProperties) {
    this(
        deliverylineHome,
        runnerProperties == null
            ? RunnerProperties.Docker.defaults().workspaceRoot()
            : runnerProperties.docker().workspaceRoot());
  }

  public LocalRunnerWorkspaceStore(@Value("${deliveryline.home}") String deliverylineHome) {
    this(deliverylineHome, RunnerProperties.Docker.defaults().workspaceRoot());
  }

  LocalRunnerWorkspaceStore(String deliverylineHome, Path configuredWorkspaceRoot) {
    if (deliverylineHome == null || deliverylineHome.trim().isEmpty()) {
      throw new IllegalArgumentException("deliveryline.home must be configured");
    }
    if (configuredWorkspaceRoot == null) {
      throw new IllegalArgumentException("workspaceRoot must be configured");
    }
    Path normalized = Path.of(deliverylineHome.trim()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(normalized);
      this.deliverylineHome = normalized.toRealPath();
      Path workspace =
          configuredWorkspaceRoot.isAbsolute()
              ? configuredWorkspaceRoot
              : this.deliverylineHome.resolve(configuredWorkspaceRoot);
      Files.createDirectories(workspace);
      this.workspaceRoot = workspace.toAbsolutePath().normalize().toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "runner workspace root could not be created or resolved", error);
    }
  }

  @Override
  public WorkspaceLayout prepare(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path root = resolveWorkspaceRoot(runnerExecutionId);
    Path input = root.resolve(INPUT_SUBDIR);
    Path output = root.resolve(OUTPUT_SUBDIR);
    Path logs = root.resolve(LOGS_SUBDIR);
    try {
      createWithOwnerOnlyPerms(root);
      createWithOwnerOnlyPerms(input);
      createWithOwnerOnlyPerms(output);
      createWithOwnerOnlyPerms(logs);
      validateDirectory(root, root);
      validateDirectory(input, root);
      validateDirectory(output, root);
      validateDirectory(logs, root);
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed to prepare runner workspace for " + runnerExecutionId, error);
    }
    log.info("workspace prepared runnerExecutionId={} root={}", runnerExecutionId, root);
    return new WorkspaceLayout(root, input, output, logs);
  }

  @Override
  public Path writeInputBundle(String runnerExecutionId, byte[] bundleBytes) {
    if (bundleBytes == null || bundleBytes.length == 0) {
      throw new IllegalArgumentException("bundleBytes must not be empty");
    }
    Path inputDir = subdirPath(runnerExecutionId, INPUT_SUBDIR);
    Path target = inputDir.resolve(CONTEXT_BUNDLE_FILENAME).normalize();
    if (!target.startsWith(inputDir)) {
      throw pathTraversal(runnerExecutionId, CONTEXT_BUNDLE_FILENAME);
    }
    Path tempTarget = target.resolveSibling(target.getFileName().toString() + TEMP_SUFFIX);
    try {
      Files.createDirectories(target.getParent());
      Files.write(tempTarget, bundleBytes);
      try {
        Files.move(
            tempTarget,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException atomicUnsupported) {
        Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
      }
      try (FileChannel dir = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
        dir.force(true);
      } catch (IOException ignored) {
        // Directory fsync unsupported on some platforms (Windows); atomic rename already gives
        // best-available durability.
      }
    } catch (IOException error) {
      try {
        Files.deleteIfExists(tempTarget);
      } catch (IOException ignored) {
        // best-effort
      }
      throw new IllegalStateException(
          "Failed to write input bundle for runner workspace " + runnerExecutionId, error);
    }
    log.info(
        "input bundle written runnerExecutionId={} bytes={}",
        runnerExecutionId,
        bundleBytes.length);
    return target;
  }

  @Override
  public Optional<byte[]> tryReadResult(String runnerExecutionId) {
    Path outputDir = subdirPath(runnerExecutionId, OUTPUT_SUBDIR);
    Path target = outputDir.resolve(RUNNER_RESULT_FILENAME).normalize();
    if (!target.startsWith(outputDir)) {
      return Optional.empty();
    }
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try {
      if (Files.isSymbolicLink(target)) {
        return Optional.empty();
      }
      Path realOutput = outputDir.toRealPath(LinkOption.NOFOLLOW_LINKS);
      Path real = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!real.startsWith(realOutput)) {
        return Optional.empty();
      }
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      return Optional.of(Files.readAllBytes(target));
    } catch (IOException error) {
      log.warn(
          "tryReadResult io failure runnerExecutionId={} cause={}",
          runnerExecutionId,
          error.toString());
      return Optional.empty();
    }
  }

  @Override
  public Optional<Path> resolveOutputRoot(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path outputDir = subdirPath(runnerExecutionId, OUTPUT_SUBDIR);
    if (!Files.isDirectory(outputDir, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    return Optional.of(outputDir);
  }

  public Path deliverylineHome() {
    return deliverylineHome;
  }

  public Path workspaceRoot() {
    return workspaceRoot;
  }

  private Path resolveWorkspaceRoot(String runnerExecutionId) {
    Path target = workspaceRoot.resolve(runnerExecutionId).normalize();
    if (!target.startsWith(workspaceRoot)) {
      throw pathTraversal(runnerExecutionId, "");
    }
    return target;
  }

  private Path subdirPath(String runnerExecutionId, String subdir) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path target = resolveWorkspaceRoot(runnerExecutionId).resolve(subdir).normalize();
    if (!target.startsWith(workspaceRoot)) {
      throw pathTraversal(runnerExecutionId, subdir);
    }
    return target;
  }

  private static void validateDirectory(Path dir, Path workspaceExecutionRoot) throws IOException {
    if (Files.isSymbolicLink(dir)) {
      throw pathTraversal(
          workspaceExecutionRoot.getFileName().toString(), dir.getFileName().toString());
    }
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Expected runner workspace directory: " + dir);
    }
    Path realRoot = workspaceExecutionRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    Path realDir = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (!realDir.startsWith(realRoot)) {
      throw pathTraversal(
          workspaceExecutionRoot.getFileName().toString(), dir.getFileName().toString());
    }
  }

  private static void createWithOwnerOnlyPerms(Path dir) throws IOException {
    Files.createDirectories(dir);
    // Best-effort POSIX permission tightening (mode 0700). On Windows / any FileStore without
    // POSIX views the call throws UnsupportedOperationException; we swallow it because the host
    // ACL already restricts access to the running user (the workspace lives under the
    // deliveryline.home of the JVM owner), and the unit-test surface skips the permission
    // assertion on Windows via @DisabledOnOs(OS.WINDOWS).
    try {
      Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR_PERMS);
    } catch (UnsupportedOperationException ignored) {
      // POSIX views unavailable (Windows); rely on host ACL.
    }
  }

  private static DomainException pathTraversal(String runnerExecutionId, String filename) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("filename", filename);
    details.put("reason", "path_traversal");
    return new DomainException(
        DomainErrorCode.ARTIFACT_INVALID_FILENAME,
        "Resolved workspace path escapes deliveryline.home",
        details);
  }
}
