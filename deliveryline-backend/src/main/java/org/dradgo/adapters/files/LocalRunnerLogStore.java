package org.dradgo.adapters.files;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.runner.RunnerLogReference;
import org.dradgo.application.runner.spi.RunnerLogStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Story 3.6 — filesystem implementation of {@link RunnerLogStore} rooted at {@code
 * {deliveryline.home}/runner-logs/{rex}/}.
 *
 * <p>Deliberately a SEPARATE root from {@code runner-work/} (Trap T7) so story-3.2's workspace
 * cleanup never deletes the durable, 60-day redacted log (AC5/NFR31). Containment + atomic-write
 * patterns mirror {@link LocalRunnerWorkspaceStore}: the {@code rex_} prefix is validated via
 * {@link PublicIdPrefixes}; resolved paths must remain under the runner-logs root; writes use a
 * temp-file + atomic rename so a partial write can never expose a truncated log.
 *
 * <p>The bytes handed in are already redacted by {@code RunnerLogCaptureService} — this store
 * performs NO credential detection and never sees the raw streams (AC8/Trap T1).
 */
@Component
public class LocalRunnerLogStore implements RunnerLogStore {

  private static final Logger log = LoggerFactory.getLogger(LocalRunnerLogStore.class);

  static final String RUNNER_LOGS_ROOT_SUBDIR = "runner-logs";
  static final String STDOUT_FILENAME = "runner.stdout";
  static final String STDERR_FILENAME = "runner.stderr";
  private static final String TEMP_SUFFIX = ".tmp";

  private static final Set<PosixFilePermission> OWNER_ONLY_DIR_PERMS =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);
  private static final Set<PosixFilePermission> OWNER_ONLY_FILE_PERMS =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private final Path runnerLogsRoot;

  public LocalRunnerLogStore(@Value("${deliveryline.home}") String deliverylineHome) {
    if (deliverylineHome == null || deliverylineHome.trim().isEmpty()) {
      throw new IllegalArgumentException("deliveryline.home must be configured");
    }
    Path normalized = Path.of(deliverylineHome.trim()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(normalized);
      Path home = normalized.toRealPath();
      Path logsRoot = home.resolve(RUNNER_LOGS_ROOT_SUBDIR);
      Files.createDirectories(logsRoot);
      this.runnerLogsRoot = logsRoot.toAbsolutePath().normalize().toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "runner-logs root could not be created or resolved", error);
    }
  }

  @Override
  public RunnerLogReference write(
      String runnerExecutionId, byte[] redactedStdout, byte[] redactedStderr) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    byte[] stdout = redactedStdout == null ? new byte[0] : redactedStdout;
    byte[] stderr = redactedStderr == null ? new byte[0] : redactedStderr;
    Path dir = resolveLogDir(runnerExecutionId);
    try {
      createWithPerms(dir, OWNER_ONLY_DIR_PERMS);
      ensureRealDirectoryInsideRoot(dir, runnerExecutionId);
      Path stdoutTemp = writeTemp(dir, STDOUT_FILENAME, stdout, runnerExecutionId);
      Path stderrTemp = writeTemp(dir, STDERR_FILENAME, stderr, runnerExecutionId);
      try {
        commitTemp(stdoutTemp, dir.resolve(STDOUT_FILENAME).normalize(), runnerExecutionId);
        commitTemp(stderrTemp, dir.resolve(STDERR_FILENAME).normalize(), runnerExecutionId);
      } catch (IOException | RuntimeException commitFailure) {
        Files.deleteIfExists(stdoutTemp);
        Files.deleteIfExists(stderrTemp);
        Files.deleteIfExists(dir.resolve(STDOUT_FILENAME).normalize());
        Files.deleteIfExists(dir.resolve(STDERR_FILENAME).normalize());
        throw commitFailure;
      }
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed to persist redacted runner logs for " + runnerExecutionId, error);
    }
    long byteSize = (long) stdout.length + (long) stderr.length;
    log.info(
        "persisting redacted runner logs runnerExecutionId={} byteSize={}",
        runnerExecutionId,
        byteSize);
    return new RunnerLogReference(dir.toString(), byteSize, DataClassification.LOCAL_ONLY, 0);
  }

  @Override
  public Optional<RunnerLogReference> find(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path dir = resolveLogDir(runnerExecutionId);
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    long byteSize = readableFileSize(dir, STDOUT_FILENAME) + readableFileSize(dir, STDERR_FILENAME);
    return Optional.of(
        new RunnerLogReference(dir.toString(), byteSize, DataClassification.LOCAL_ONLY, 0));
  }

  public Path runnerLogsRoot() {
    return runnerLogsRoot;
  }

  private long readableFileSize(Path dir, String filename) {
    Path target = dir.resolve(filename).normalize();
    if (!target.startsWith(dir) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
      return 0L;
    }
    try {
      return Files.size(target);
    } catch (IOException error) {
      log.warn(
          "runner log size read failure runnerExecutionId={} filename={} cause={}",
          dir.getFileName(),
          filename,
          error.toString());
      return 0L;
    }
  }

  private void atomicWrite(Path dir, String filename, byte[] bytes, String runnerExecutionId)
      throws IOException {
    Path tempTarget = writeTemp(dir, filename, bytes, runnerExecutionId);
    commitTemp(tempTarget, dir.resolve(filename).normalize(), runnerExecutionId);
  }

  private Path writeTemp(Path dir, String filename, byte[] bytes, String runnerExecutionId)
      throws IOException {
    Path target = dir.resolve(filename).normalize();
    if (!target.startsWith(dir)) {
      throw pathTraversal(runnerExecutionId, filename);
    }
    Path tempTarget;
    try {
      tempTarget =
          Files.createTempFile(
              dir, target.getFileName().toString() + ".", TEMP_SUFFIX, ownerOnlyFileAttributes());
    } catch (UnsupportedOperationException unsupportedPosixAttributes) {
      tempTarget = Files.createTempFile(dir, target.getFileName().toString() + ".", TEMP_SUFFIX);
    }
    setPosixPermissionsIfSupported(tempTarget, OWNER_ONLY_FILE_PERMS);
    Files.write(tempTarget, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    return tempTarget;
  }

  private void commitTemp(Path tempTarget, Path target, String runnerExecutionId)
      throws IOException {
    if (!target.startsWith(tempTarget.getParent())) {
      throw pathTraversal(runnerExecutionId, target.getFileName().toString());
    }
    try {
      Files.move(
          tempTarget, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException atomicUnsupported) {
      Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
    }
    setPosixPermissionsIfSupported(target, OWNER_ONLY_FILE_PERMS);
    try (FileChannel channel = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException ignored) {
      // Directory fsync unsupported on some platforms (Windows); atomic rename already gives
      // best-available durability.
    }
  }

  private Path resolveLogDir(String runnerExecutionId) {
    Path target = runnerLogsRoot.resolve(runnerExecutionId).normalize();
    if (!target.startsWith(runnerLogsRoot)) {
      throw pathTraversal(runnerExecutionId, "");
    }
    if (Files.isSymbolicLink(target)) {
      throw pathTraversal(runnerExecutionId, "");
    }
    return target;
  }

  private void ensureRealDirectoryInsideRoot(Path dir, String runnerExecutionId)
      throws IOException {
    if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw pathTraversal(runnerExecutionId, "");
    }
    Path real = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (!real.startsWith(runnerLogsRoot)) {
      throw pathTraversal(runnerExecutionId, "");
    }
  }

  private static void createWithPerms(Path dir, Set<PosixFilePermission> permissions)
      throws IOException {
    Files.createDirectories(dir);
    setPosixPermissionsIfSupported(dir, permissions);
  }

  private static void setPosixPermissionsIfSupported(
      Path path, Set<PosixFilePermission> permissions) throws IOException {
    try {
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException ignored) {
      // POSIX views unavailable (Windows); rely on host ACL.
    }
  }

  @SuppressWarnings("unchecked")
  private static FileAttribute<Set<PosixFilePermission>>[] ownerOnlyFileAttributes() {
    try {
      return new FileAttribute[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE_PERMS)};
    } catch (UnsupportedOperationException ignored) {
      return new FileAttribute[0];
    }
  }

  private static DomainException pathTraversal(String runnerExecutionId, String filename) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("filename", filename);
    details.put("reason", "path_traversal");
    return new DomainException(
        DomainErrorCode.ARTIFACT_INVALID_FILENAME,
        "Resolved runner-logs path escapes deliveryline.home",
        details);
  }
}
