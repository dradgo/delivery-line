package org.dradgo.adapters.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.LogGrowthObservation;
import org.dradgo.application.runner.spi.RawRunnerLog;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.spi.WorkspaceLayout;
import org.dradgo.application.runner.spi.WorkspaceScanFile;
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
  // Story 3.9 AC4 / Decision D3 — the cloned-repo working tree, sibling of input/output/logs under
  // the same {rex}/ root so the existing recursive deleteWorkspace reaps it for free (AC11).
  private static final String REPO_SUBDIR = "repo";
  // Story 3.9 — the VCS metadata directory inside the cloned `repo/` working tree. Excluded from
  // the
  // secret scan (see readFilesForSecretScan): its stock sample hooks / pack objects are not
  // runner-authored and the sample hooks false-positive the ENV_VALUE redaction heuristic.
  private static final String GIT_METADATA_DIRNAME = ".git";
  private static final String CONTEXT_BUNDLE_FILENAME = "context-bundle.v1.json";
  private static final String RUNNER_RESULT_FILENAME = "runner-result.v1.json";
  private static final String HEARTBEAT_TOUCH_FILENAME = "heartbeat.touch";
  private static final String RUNNER_STDOUT_FILENAME = "runner.stdout";
  // Story 3.6 Trap T5: the stderr counterpart of runner.stdout — net-new (the store only had stdout
  // for the heartbeat log-growth observation). AC2 captures BOTH streams.
  private static final String RUNNER_STDERR_FILENAME = "runner.stderr";
  // Story 3.6 OQ-6: cap a single raw stream read so a runaway runner log cannot OOM the capture
  // path (the 3.5 review flagged unbounded readAllBytes). 8 MiB is far above any realistic
  // diagnostic log; the overflow is truncated with a marker rather than dropped.
  public static final int MAX_RAW_LOG_CAPTURE_BYTES_FOR_TEST = 8 * 1024 * 1024;
  private static final long MAX_RAW_LOG_CAPTURE_BYTES = MAX_RAW_LOG_CAPTURE_BYTES_FOR_TEST;
  private static final int TRUNCATED_TRAILING_GUARD_BYTES = 4096;
  private static final String TEMP_SUFFIX = ".tmp";
  private static final String QUARANTINE_MARKER_FILENAME = ".quarantine";
  private static final List<String> SECRET_SCAN_SUBDIRS =
      List.of(INPUT_SUBDIR, OUTPUT_SUBDIR, LOGS_SUBDIR, REPO_SUBDIR);

  private static final Set<PosixFilePermission> OWNER_ONLY_DIR_PERMS =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);
  private static final Set<PosixFilePermission> RUNNER_READABLE_INPUT_DIR_PERMS =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_EXECUTE);
  private static final Set<PosixFilePermission> RUNNER_WRITABLE_DIR_PERMS =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE);
  private static final Set<PosixFilePermission> RUNNER_READABLE_FILE_PERMS =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ);

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
      createWithPerms(input, RUNNER_READABLE_INPUT_DIR_PERMS);
      createWithPerms(output, RUNNER_WRITABLE_DIR_PERMS);
      createWithPerms(logs, RUNNER_WRITABLE_DIR_PERMS);
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
  public Path prepareRepositoryDir(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path root = resolveWorkspaceRoot(runnerExecutionId);
    Path repo = root.resolve(REPO_SUBDIR).normalize();
    if (!repo.startsWith(root)) {
      throw pathTraversal(runnerExecutionId, REPO_SUBDIR);
    }
    try {
      // The {rex}/ root may not exist yet if prepareRepositoryDir is called before prepare();
      // create
      // it owner-only first, then the repo/ dir with runner-writable perms (mirrors output/).
      createWithOwnerOnlyPerms(root);
      createWithPerms(repo, RUNNER_WRITABLE_DIR_PERMS);
      validateDirectory(root, root);
      validateDirectory(repo, root);
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed to prepare runner repo workspace for " + runnerExecutionId, error);
    }
    log.info("workspace repo dir prepared runnerExecutionId={} repo={}", runnerExecutionId, repo);
    return repo;
  }

  @Override
  public Optional<Path> resolveRepositoryDir(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path repo = subdirPath(runnerExecutionId, REPO_SUBDIR);
    if (!Files.isDirectory(repo, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    return Optional.of(repo);
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
      setPosixPermissionsIfSupported(target, RUNNER_READABLE_FILE_PERMS);
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
  public Optional<byte[]> tryReadArtifactContent(
      String runnerExecutionId, String relativeReference) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    if (relativeReference == null || relativeReference.isBlank()) {
      return Optional.empty();
    }
    Path requested = Path.of(relativeReference);
    if (requested.isAbsolute()) {
      return Optional.empty();
    }
    Path outputDir = subdirPath(runnerExecutionId, OUTPUT_SUBDIR);
    Path target = outputDir.resolve(requested).normalize();
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
      if (!real.startsWith(realOutput) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      return Optional.of(Files.readAllBytes(target));
    } catch (IOException error) {
      log.warn(
          "tryReadArtifactContent io failure runnerExecutionId={} reference={} cause={}",
          runnerExecutionId,
          relativeReference,
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

  @Override
  public Optional<OffsetDateTime> tryReadHeartbeatTouch(String runnerExecutionId) {
    Path outputDir = subdirPath(runnerExecutionId, OUTPUT_SUBDIR);
    Path target = outputDir.resolve(HEARTBEAT_TOUCH_FILENAME).normalize();
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
      FileTime modified = Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS);
      // Trap T3: collapse FileTime to OffsetDateTime at MILLISECOND precision in UTC — keep the
      // heartbeat compare monotonic across filesystems with different native precision (some report
      // ns, some ms). truncatedTo(MILLIS) actually delivers the documented monotonicity (a bare
      // toInstant() would preserve ns and re-introduce cross-FS jitter).
      return Optional.of(
          OffsetDateTime.ofInstant(
              modified.toInstant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
    } catch (IOException error) {
      log.warn(
          "tryReadHeartbeatTouch io failure runnerExecutionId={} cause={}",
          runnerExecutionId,
          error.toString());
      return Optional.empty();
    }
  }

  @Override
  public Optional<LogGrowthObservation> observeLogGrowth(String runnerExecutionId) {
    Path logsDir = subdirPath(runnerExecutionId, LOGS_SUBDIR);
    Path target = logsDir.resolve(RUNNER_STDOUT_FILENAME).normalize();
    if (!target.startsWith(logsDir)) {
      return Optional.empty();
    }
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try {
      if (Files.isSymbolicLink(target)) {
        return Optional.empty();
      }
      Path realLogs = logsDir.toRealPath(LinkOption.NOFOLLOW_LINKS);
      Path real = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!real.startsWith(realLogs)) {
        return Optional.empty();
      }
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      BasicFileAttributes attrs =
          Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      // Trap T3: ms-precision truncation (mirrors tryReadHeartbeatTouch) for cross-FS monotonicity.
      OffsetDateTime modified =
          OffsetDateTime.ofInstant(
              attrs.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
      return Optional.of(new LogGrowthObservation(attrs.size(), modified));
    } catch (IOException error) {
      log.warn(
          "observeLogGrowth io failure runnerExecutionId={} cause={}",
          runnerExecutionId,
          error.toString());
      return Optional.empty();
    }
  }

  @Override
  public Optional<RawRunnerLog> readRawStdoutForCapture(String runnerExecutionId) {
    return readRawLogForCapture(runnerExecutionId, RUNNER_STDOUT_FILENAME);
  }

  @Override
  public Optional<RawRunnerLog> readRawStderrForCapture(String runnerExecutionId) {
    return readRawLogForCapture(runnerExecutionId, RUNNER_STDERR_FILENAME);
  }

  /**
   * Story 3.6 AC2 / Trap T5 / OQ-6 — read a raw workspace log file, decoded lossy-UTF-8 and capped
   * at {@link #MAX_RAW_LOG_CAPTURE_BYTES}. Same containment + symlink-escape guards as the other
   * read methods. Returns empty only for a missing / escaping / unreadable file.
   */
  private Optional<RawRunnerLog> readRawLogForCapture(String runnerExecutionId, String filename) {
    Path logsDir = subdirPath(runnerExecutionId, LOGS_SUBDIR);
    Path target = logsDir.resolve(filename).normalize();
    if (!target.startsWith(logsDir)) {
      return Optional.empty();
    }
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try {
      if (Files.isSymbolicLink(target)) {
        return Optional.empty();
      }
      Path realLogs = logsDir.toRealPath(LinkOption.NOFOLLOW_LINKS);
      Path real = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!real.startsWith(realLogs)) {
        return Optional.empty();
      }
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      try (FileChannel channel =
          FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        long size = channel.size();
        boolean truncated = size > MAX_RAW_LOG_CAPTURE_BYTES;
        int limit = (int) Math.min(size, MAX_RAW_LOG_CAPTURE_BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(limit);
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
          // keep reading until the bounded buffer is full or EOF
        }
        byte[] bytes = buffer.array();
        int length = buffer.position();
        if (!truncated) {
          return Optional.of(
              new RawRunnerLog(new String(bytes, 0, length, StandardCharsets.UTF_8), false));
        }
        int guardedLength = Math.max(0, length - TRUNCATED_TRAILING_GUARD_BYTES);
        String text = new String(bytes, 0, guardedLength, StandardCharsets.UTF_8);
        log.warn(
            "raw runner log truncated for capture runnerExecutionId={} filename={} sourceBytes={} cap={}",
            runnerExecutionId,
            filename,
            size,
            MAX_RAW_LOG_CAPTURE_BYTES);
        return Optional.of(
            new RawRunnerLog(
                text + "\n[TRUNCATED " + (size - MAX_RAW_LOG_CAPTURE_BYTES) + " bytes over cap]\n",
                true));
      }
    } catch (IOException error) {
      log.warn(
          "readRawLogForCapture io failure runnerExecutionId={} filename={} cause={}",
          runnerExecutionId,
          filename,
          error.toString());
      return Optional.empty();
    }
  }

  @Override
  public void deleteWorkspace(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path root = resolveWorkspaceRoot(runnerExecutionId);
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      log.warn(
          "deleteWorkspace skipped runnerExecutionId={} reason=missing_workspace_dir",
          runnerExecutionId);
      return;
    }
    try {
      Path realWorkspaceRoot = workspaceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
      Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!realRoot.startsWith(realWorkspaceRoot)) {
        throw pathTraversal(runnerExecutionId, "");
      }
      walkAndDelete(root);
      log.info("workspace deleted runnerExecutionId={} root={}", runnerExecutionId, root);
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed to delete runner workspace for " + runnerExecutionId, error);
    }
  }

  private static void walkAndDelete(Path root) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            deleteClearingReadOnly(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
              throw exc;
            }
            deleteClearingReadOnly(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Story 3.9 — delete {@code path}, first clearing any read-only bit. A cloned git repo's pack /
   * object files are written read-only by git; on Windows {@link Files#delete} then fails with
   * {@code AccessDeniedException}. Clearing the read-only flag (a no-op on typical POSIX
   * filesystems where directory perms govern deletion) keeps the recursive workspace reap (AC11)
   * cross-platform.
   */
  private static void deleteClearingReadOnly(Path path) throws IOException {
    try {
      Files.delete(path);
    } catch (java.nio.file.AccessDeniedException denied) {
      path.toFile().setWritable(true, false);
      Files.delete(path);
    }
  }

  /** Story 3.2: enumerate {@code rex_*} subdirectories under the workspace root for AC5 (k). */
  @Override
  public java.util.List<Path> listWorkspaceSubdirectories() {
    java.util.List<Path> out = new java.util.ArrayList<>();
    if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) {
      return out;
    }
    try (java.util.stream.Stream<Path> entries = Files.list(workspaceRoot)) {
      entries.forEach(
          entry -> {
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                && entry.getFileName().toString().startsWith("rex_")) {
              out.add(entry);
            }
          });
    } catch (IOException error) {
      log.warn("listWorkspaceSubdirectories io failure cause={}", error.toString());
    }
    return out;
  }

  /**
   * Story 3.5 AC4 — enumerate regular files under {@code input/}, {@code output/}, {@code logs/}
   * and return relative path + UTF-8 text. Binary files (strict-decode failure) are skipped with a
   * WARN (OQ-6); symlinks and escaping paths are skipped. Trap T7: bytes-only — no detection here.
   */
  @Override
  public List<WorkspaceScanFile> readFilesForSecretScan(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path root = resolveWorkspaceRoot(runnerExecutionId);
    List<WorkspaceScanFile> out = new ArrayList<>();
    for (String subdir : SECRET_SCAN_SUBDIRS) {
      Path dir = subdirPath(runnerExecutionId, subdir);
      if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      try {
        Path realDir = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Files.walkFileTree(
            dir,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path subdir, BasicFileAttributes attrs) {
                // Story 3.9 added the cloned `repo/` working tree to the secret scan, but a real
                // clone carries a `.git/` directory of VCS internals the runner never authored —
                // stock sample hooks, pack objects, config. Scanning those for "did the runner leak
                // the injected provider key" is out of scope AND false-positives: a pristine
                // `.git/hooks/fsmonitor-watchman.sample` trips the ENV_VALUE redaction heuristic,
                // recording a bogus runner_secret_leak that freezes the run forever (the leak path
                // never advances workflow state). Prune `.git/` so only runner-authored
                // working-tree
                // files are scanned. (Cheap win too: skips the entire pack/object tree.)
                if (GIT_METADATA_DIRNAME.equals(subdir.getFileName().toString())) {
                  return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                collectScanFile(runnerExecutionId, root, realDir, file, out);
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn(
                    "secret scan file skipped runnerExecutionId={} reason=visit_failed",
                    runnerExecutionId);
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (IOException error) {
        log.warn(
            "secret scan subdir walk failure runnerExecutionId={} subdir={} cause={}",
            runnerExecutionId,
            subdir,
            error.toString());
      }
    }
    log.info(
        "secret scan files enumerated runnerExecutionId={} fileCount={}",
        runnerExecutionId,
        out.size());
    return out;
  }

  private void collectScanFile(
      String runnerExecutionId,
      Path workspaceRootDir,
      Path realSubdir,
      Path file,
      List<WorkspaceScanFile> out) {
    try {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      // Containment: the file's real path must remain inside the (real) subdirectory.
      Path realFile = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!realFile.startsWith(realSubdir)) {
        log.warn(
            "secret scan file skipped runnerExecutionId={} reason=escapes_workspace",
            runnerExecutionId);
        return;
      }
      byte[] bytes = Files.readAllBytes(file);
      String text = strictUtf8(bytes);
      if (text == null) {
        log.warn(
            "binary file skipped in secret scan runnerExecutionId={} relativePath={}",
            runnerExecutionId,
            relativePathOf(workspaceRootDir, file));
        return;
      }
      out.add(new WorkspaceScanFile(relativePathOf(workspaceRootDir, file), text));
    } catch (IOException error) {
      log.warn(
          "secret scan file read failure runnerExecutionId={} cause={}",
          runnerExecutionId,
          error.toString());
    }
  }

  /** Strict UTF-8 decode; returns {@code null} when the bytes are not valid UTF-8 (binary). */
  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException notText) {
      return null;
    }
  }

  private static String relativePathOf(Path workspaceRootDir, Path file) {
    return workspaceRootDir.relativize(file).toString().replace('\\', '/');
  }

  /**
   * Story 3.5 AC4 / Trap T10 — write the {@code .quarantine} marker so cleanup preserves the dir.
   */
  @Override
  public Path writeQuarantineMarker(String runnerExecutionId, String reason) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path root = resolveWorkspaceRoot(runnerExecutionId);
    Path marker = root.resolve(QUARANTINE_MARKER_FILENAME).normalize();
    if (!marker.startsWith(root)) {
      throw pathTraversal(runnerExecutionId, QUARANTINE_MARKER_FILENAME);
    }
    String body =
        "quarantined-at="
            + OffsetDateTime.now(ZoneOffset.UTC)
            + "\nreason="
            + (reason == null ? "" : reason)
            + "\n";
    try {
      Files.createDirectories(root);
      Files.writeString(marker, body, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed to write quarantine marker for runner workspace " + runnerExecutionId, error);
    }
    log.warn("workspace quarantined runnerExecutionId={} marker={}", runnerExecutionId, marker);
    return marker;
  }

  /** Story 3.5 AC4 / Trap T10 — true when the workspace carries a {@code .quarantine} marker. */
  @Override
  public boolean isQuarantined(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Path root = resolveWorkspaceRoot(runnerExecutionId);
    Path marker = root.resolve(QUARANTINE_MARKER_FILENAME).normalize();
    if (!marker.startsWith(root)) {
      return false;
    }
    return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS);
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
    createWithPerms(dir, OWNER_ONLY_DIR_PERMS);
  }

  private static void createWithPerms(Path dir, Set<PosixFilePermission> permissions)
      throws IOException {
    Files.createDirectories(dir);
    // Best-effort POSIX permission tightening. On Windows / any FileStore without
    // POSIX views the call throws UnsupportedOperationException; we swallow it because the host
    // ACL already restricts access to the running user (the workspace lives under the
    // deliveryline.home of the JVM owner), and the unit-test surface skips the permission
    // assertion on Windows via @DisabledOnOs(OS.WINDOWS).
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
