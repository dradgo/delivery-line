package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.dradgo.runnercontracts.ValidationContext;
import org.dradgo.runnercontracts.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.DockerClientFactory;

/**
 * Story 3.3 Task 8 / AC4 — contract-conformance test for the {@code deliveryline/codex-runner}
 * image. Builds the image once with {@code --build-arg INSTALL_CODEX_CLI=false} (so a deterministic
 * MOCK Codex CLI is baked in — NO real Codex API call; that is story 3.8), then runs the image once
 * per artifact variant against the existing valid {@code context-bundle.v1} fixture and asserts the
 * produced {@code /workspace/output/runner-result.v1.json} validates against the runner-contracts
 * v1 schema via {@link RunnerContractValidator}.
 *
 * <p>Filenames are the LIVE backend contract (verified in {@code LocalRunnerWorkspaceStore}): the
 * adapter writes the bundle as {@code context-bundle.v1.json} and reads the result from {@code
 * runner-result.v1.json} — a result under any other name reads back as RUNNER_CRASH.
 *
 * <p>Tagged {@code docker-runner-it} + gated by {@link EnabledIfDockerAvailable}: it needs a live
 * Docker daemon + a built image, so it is opt-in via the Docker-tier CI on Linux runners (mirrors
 * stories 3.1/3.2) and is excluded from the no-Docker PR tier. Real Codex-API execution is story
 * 3.8.
 */
@Tag("docker-runner-it")
@EnabledIfDockerAvailable
class CodexRunnerImageConformanceIT {

  private static final String IMAGE_TAG = "deliveryline/codex-runner:conformance-it";
  // Sentinel "secret" injected as the provider key. The negative-log assertion proves it never
  // reaches runner.stdout/.stderr (or the result document).
  private static final String SECRET_SENTINEL = "sk-codex-CONFORMANCE-SENTINEL-9f3a2b";
  // Story 3a-3 — sentinel embedded INSIDE the subscription auth.json content. The negative-leak
  // assertion proves it never reaches container logs, the captured runner.stdout/.stderr, or the
  // result document — only its file (cleaned on exit) ever holds it.
  private static final String AUTH_SENTINEL = "oauth-SUBSCRIPTION-SENTINEL-7a1b2c";
  private static final String CODEX_AUTH_JSON_FIXTURE =
      "{\"tokens\":{\"access_token\":\""
          + AUTH_SENTINEL
          + "\"},\"account_id\":\"acct_conformance\"}";
  private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^([A-Za-z]):[/\\\\](.*)$");

  private static DockerClient docker;
  private static Path repoRoot;
  private static Path bundleFixture;
  private static Path validFixtures;

  private String containerIdToCleanup;

  @BeforeAll
  static void buildImage() throws Exception {
    docker = DockerClientFactory.instance().client();
    repoRoot = locateRepoRoot();
    bundleFixture =
        repoRoot.resolve(
            "deliveryline-runner-contracts/src/test/resources/fixtures/valid/"
                + "context-bundle.v1.valid.json");
    validFixtures =
        repoRoot.resolve("deliveryline-runner-contracts/src/test/resources/fixtures/valid");
    assertThat(Files.exists(bundleFixture)).as("context-bundle fixture present").isTrue();

    // Build the image via the docker CLI (same invocation as `docker compose build`), baking the
    // deterministic MOCK Codex CLI (AC4). The CLI honors .dockerignore + the repo-root build
    // context
    // exactly like production; docker-java's buildImageCmd tarball handling of an out-of-cwd
    // Dockerfile is brittle, so we keep only the container RUNS on docker-java (below).
    ProcessBuilder builder =
        new ProcessBuilder(
            "docker",
            "build",
            "-f",
            "runners/codex/Dockerfile",
            "--build-arg",
            "INSTALL_CODEX_CLI=false",
            "--build-arg",
            "IMAGE_VERSION=conformance-it",
            "-t",
            IMAGE_TAG,
            ".");
    builder.directory(repoRoot.toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String buildOutput =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int buildExit = process.waitFor();
    assertThat(buildExit).as("docker build exit code; output=%n%s", buildOutput).isZero();
  }

  @AfterEach
  void cleanUpContainer() {
    if (containerIdToCleanup != null) {
      try {
        docker.removeContainerCmd(containerIdToCleanup).withForce(true).exec();
      } catch (RuntimeException ignored) {
        // best-effort
      }
      containerIdToCleanup = null;
    }
  }

  @Test
  void selfTestExitsZeroAndPrintsSummary() throws Exception {
    var created = docker.createContainerCmd(IMAGE_TAG).withCmd("--self-test").exec();
    containerIdToCleanup = created.getId();
    docker.startContainerCmd(created.getId()).exec();
    int exit =
        docker
            .waitContainerCmd(created.getId())
            .exec(new WaitContainerResultCallback())
            .awaitStatusCode();
    assertThat(exit).as("--self-test exit code").isZero();

    // Story 3a-6 (AC3) — the self-test must report the agent-side OpenSpec CLI. The summary goes to
    // the container's stdout; capture it and assert the openspec line is present. A
    // missing/mismatched
    // openspec would have already failed the self-test (exit != 0), so this pins the line in CI.
    String selfTestOutput = captureContainerLogs(created.getId());
    assertThat(selfTestOutput)
        .as("self-test summary must report the openspec CLI bin + version")
        .contains("openspec bin:")
        .contains("openspec version:");

    // Story 3a-7 (AC4) — the self-test must report the vendored obra/superpowers skills. The skills
    // are COPY'd in (offline-safe, no mock), so a missing dir / dangling symlink / empty tree would
    // already have failed the self-test (exit != 0); this pins the summary line + a non-zero skill
    // count in CI.
    assertThat(selfTestOutput)
        .as("self-test summary must report the vendored superpowers skills")
        .contains("superpowers:")
        .contains("skills at");

    // JDK+Maven toolchain: the self-test must report both, and a missing binary would already
    // have failed the self-test (exit != 0). Pins the summary lines in CI.
    assertThat(selfTestOutput)
        .as("self-test summary must report the JDK toolchain")
        .contains("java version:");
    assertThat(selfTestOutput)
        .as("self-test summary must report the Maven toolchain")
        .contains("maven version:");
  }

  @ParameterizedTest(name = "stage {0} -> artifactType {1}")
  @CsvSource({
    "spec-investigation, spec",
    "implementation-plan, implementationPlan",
    "pr-output, prOutput",
  })
  void producesSchemaConformantResultPerArtifactVariant(String storyStage, String expectedType)
      throws Exception {
    Path work = Files.createTempDirectory("codex-conformance-");
    Path input = Files.createDirectories(work.resolve("input"));
    Path output = Files.createDirectories(work.resolve("output"));
    Path logs = Files.createDirectories(work.resolve("logs"));
    // The image runs as the non-root user codex:1001; the bind-mounted rw dirs must be writable by
    // that uid. (Production: the backend must mount writable workspace dirs for the runner uid —
    // documented in runners/codex/README.md as a story-3.8 integration note.)
    makeRunnerReadable(input);
    makeWorldWritable(output);
    makeWorldWritable(logs);
    Files.copy(bundleFixture, input.resolve("context-bundle.v1.json"));

    var created =
        docker
            .createContainerCmd(IMAGE_TAG)
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(
                        new Bind(
                            dockerHostPath(input), new Volume("/workspace/input"), AccessMode.ro),
                        new Bind(
                            dockerHostPath(output), new Volume("/workspace/output"), AccessMode.rw),
                        new Bind(
                            dockerHostPath(logs), new Volume("/workspace/logs"), AccessMode.rw))
                    .withNetworkMode("none")) // story 3.1 AC8 file-based contract
            .withEnv("CODEX_API_KEY=" + SECRET_SENTINEL, "DELIVERYLINE_RUNNER_STAGE=" + storyStage)
            .exec();
    containerIdToCleanup = created.getId();
    docker.startContainerCmd(created.getId()).exec();
    int exit =
        docker
            .waitContainerCmd(created.getId())
            .exec(new WaitContainerResultCallback())
            .awaitStatusCode();
    assertThat(exit).as("entrypoint exit code for stage %s", storyStage).isZero();

    // AC2(e): result lands at the contract filename the adapter reads back.
    Path resultFile = output.resolve("runner-result.v1.json");
    assertThat(Files.exists(resultFile))
        .as("runner-result.v1.json present for stage %s", storyStage)
        .isTrue();

    byte[] resultBytes = Files.readAllBytes(resultFile);
    ValidationResult validation =
        new RunnerContractValidator()
            .validate(ValidationTarget.RUNNER_RESULT, resultBytes, ValidationContext.defaults());
    assertThat(validation.valid())
        .as(
            "runner-result.v1 schema-valid for stage %s; errors=%s",
            storyStage, validation.errors())
        .isTrue();

    // AC3: artifactType is driven by the stage.
    String resultJson = new String(resultBytes, java.nio.charset.StandardCharsets.UTF_8);
    assertThat(resultJson).contains("\"artifactType\": \"" + expectedType + "\"");
    assertGoldenVariantShape(resultJson, expectedType);
    if ("spec".equals(expectedType)) {
      assertThat(Files.exists(output.resolve("artifacts/run_abcd1234/spec.md")))
          .as("spec contentReference payload is materialized")
          .isTrue();
    }

    // Logging task negative assertion: the injected secret VALUE never reaches the captured logs.
    String stdoutLog = readIfExists(logs.resolve("runner.stdout"));
    String stderrLog = readIfExists(logs.resolve("runner.stderr"));
    assertThat(stdoutLog)
        .as("runner.stdout must not leak the secret")
        .doesNotContain(SECRET_SENTINEL);
    assertThat(stderrLog)
        .as("runner.stderr must not leak the secret")
        .doesNotContain(SECRET_SENTINEL);
    assertThat(resultJson)
        .as("runner-result.v1.json must not leak the secret")
        .doesNotContain(SECRET_SENTINEL);
  }

  @Test
  void subscriptionAuthMaterializesAuthJsonWithoutLeakingTheValue() throws Exception {
    // Story 3a-3 (AC10) — subscription scenario: inject CODEX_AUTH_JSON (auth.json content carrying
    // a sentinel) INSTEAD of an API key. Asserts the run completes (exit 0, schema-valid result),
    // the entrypoint's subscription materialize log line is present in the container stderr, and
    // the
    // auth.json sentinel leaks into NONE of the container logs / captured runner.stdout|.stderr /
    // result. File existence + 0600 perms + cleanup are covered by the runner.mjs/entrypoint unit
    // tier (no live container fs inspection needed). mock-codex.sh is unchanged — it ignores env +
    // auth files, so a leak could only come from the entrypoint or runner.mjs, which this pins.
    Path work = Files.createTempDirectory("codex-conformance-subscription-");
    Path input = Files.createDirectories(work.resolve("input"));
    Path output = Files.createDirectories(work.resolve("output"));
    Path logs = Files.createDirectories(work.resolve("logs"));
    makeRunnerReadable(input);
    makeWorldWritable(output);
    makeWorldWritable(logs);
    Files.copy(bundleFixture, input.resolve("context-bundle.v1.json"));

    var created =
        docker
            .createContainerCmd(IMAGE_TAG)
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(
                        new Bind(
                            dockerHostPath(input), new Volume("/workspace/input"), AccessMode.ro),
                        new Bind(
                            dockerHostPath(output), new Volume("/workspace/output"), AccessMode.rw),
                        new Bind(
                            dockerHostPath(logs), new Volume("/workspace/logs"), AccessMode.rw))
                    .withNetworkMode("none")) // story 3.1 AC8 file-based contract
            // Subscription credential ONLY — no CODEX_API_KEY / OPENAI_API_KEY (proves the
            // subscription-first branch is taken, not the API-key fallback).
            .withEnv(
                "CODEX_AUTH_JSON=" + CODEX_AUTH_JSON_FIXTURE,
                "DELIVERYLINE_RUNNER_STAGE=spec-investigation")
            .exec();
    containerIdToCleanup = created.getId();
    docker.startContainerCmd(created.getId()).exec();
    int exit =
        docker
            .waitContainerCmd(created.getId())
            .exec(new WaitContainerResultCallback())
            .awaitStatusCode();
    assertThat(exit).as("subscription-auth entrypoint exit code").isZero();

    // The entrypoint's structured diagnostics go to the CONTAINER's stderr stream (the log()
    // helper writes to >&2), not the captured runner.stderr mount (which holds only the Codex CLI's
    // own output). Capture the container's combined stdout+stderr to assert the materialize line.
    String containerLogs = captureContainerLogs(created.getId());

    Path resultFile = output.resolve("runner-result.v1.json");
    assertThat(Files.exists(resultFile)).as("runner-result.v1.json present").isTrue();
    byte[] resultBytes = Files.readAllBytes(resultFile);
    ValidationResult validation =
        new RunnerContractValidator()
            .validate(ValidationTarget.RUNNER_RESULT, resultBytes, ValidationContext.defaults());
    assertThat(validation.valid())
        .as("subscription runner-result.v1 schema-valid; errors=%s", validation.errors())
        .isTrue();
    String resultJson = new String(resultBytes, StandardCharsets.UTF_8);

    // Auth mode logged by NAME + mode only — never the value.
    assertThat(containerLogs)
        .as("entrypoint must log the subscription materialize line by name")
        .contains("name=CODEX_AUTH_JSON")
        .contains("mode=subscription");

    // Negative-leak: the auth.json sentinel must not appear anywhere observable.
    String stdoutLog = readIfExists(logs.resolve("runner.stdout"));
    String stderrLog = readIfExists(logs.resolve("runner.stderr"));
    assertThat(containerLogs)
        .as("container logs must not leak the auth.json value")
        .doesNotContain(AUTH_SENTINEL);
    assertThat(stdoutLog)
        .as("runner.stdout must not leak the auth.json value")
        .doesNotContain(AUTH_SENTINEL);
    assertThat(stderrLog)
        .as("runner.stderr must not leak the auth.json value")
        .doesNotContain(AUTH_SENTINEL);
    assertThat(resultJson)
        .as("runner-result.v1.json must not leak the auth.json value")
        .doesNotContain(AUTH_SENTINEL);
  }

  // ---- Story 3a-8 — opt-in OpenSpec authoring layer ----

  /** Outcome of a stage run with a writable {@code /workspace/repo} mount. */
  private record OpenSpecRun(Path repo, Path output, String containerLogs, int exit) {}

  /**
   * Runs one stage against the valid fixture with a writable {@code /workspace/repo} mount (so the
   * pr-output authoring layer has somewhere to assemble the change folder), optionally opting into
   * the OpenSpec layer via {@code DELIVERYLINE_RUNNER_OPENSPEC=true}. Returns the repo dir (for
   * post-run inspection), the output dir, the captured container logs, and the exit code.
   */
  private OpenSpecRun runWithRepoMount(String stage, boolean openspecOn) throws Exception {
    Path work = Files.createTempDirectory("codex-openspec-");
    Path input = Files.createDirectories(work.resolve("input"));
    Path output = Files.createDirectories(work.resolve("output"));
    Path logs = Files.createDirectories(work.resolve("logs"));
    Path repo = Files.createDirectories(work.resolve("repo"));
    makeRunnerReadable(input);
    makeWorldWritable(output);
    makeWorldWritable(logs);
    makeWorldWritable(repo);
    Files.copy(bundleFixture, input.resolve("context-bundle.v1.json"));

    var cmd =
        docker
            .createContainerCmd(IMAGE_TAG)
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(
                        new Bind(
                            dockerHostPath(input), new Volume("/workspace/input"), AccessMode.ro),
                        new Bind(
                            dockerHostPath(output), new Volume("/workspace/output"), AccessMode.rw),
                        new Bind(
                            dockerHostPath(logs), new Volume("/workspace/logs"), AccessMode.rw),
                        new Bind(
                            dockerHostPath(repo), new Volume("/workspace/repo"), AccessMode.rw))
                    .withNetworkMode("none"));
    if (openspecOn) {
      cmd.withEnv(
          "CODEX_API_KEY=" + SECRET_SENTINEL,
          "DELIVERYLINE_RUNNER_STAGE=" + stage,
          "DELIVERYLINE_RUNNER_OPENSPEC=true");
    } else {
      cmd.withEnv("CODEX_API_KEY=" + SECRET_SENTINEL, "DELIVERYLINE_RUNNER_STAGE=" + stage);
    }
    var created = cmd.exec();
    containerIdToCleanup = created.getId();
    docker.startContainerCmd(created.getId()).exec();
    int exit =
        docker
            .waitContainerCmd(created.getId())
            .exec(new WaitContainerResultCallback())
            .awaitStatusCode();
    return new OpenSpecRun(repo, output, captureContainerLogs(created.getId()), exit);
  }

  @Test
  void openSpecFlagOnAssemblesChangeFolderAtPrOutput() throws Exception {
    // AC2/AC4: flag on + pr-output assembles openspec/changes/<id>/ into the delivered repo, runs
    // the (mock) validate, and STILL exits 0 with a schema-valid result (additive-never-blocks).
    OpenSpecRun run = runWithRepoMount("pr-output", true);
    assertThat(run.exit()).as("flag-on pr-output still exits 0 (additive never blocks)").isZero();
    assertThat(Files.exists(run.output().resolve("runner-result.v1.json")))
        .as("result still produced")
        .isTrue();
    assertThat(Files.exists(run.repo().resolve("openspec/AGENTS.md")))
        .as("openspec AGENTS.md skeleton authored into the repo")
        .isTrue();
    Path changes = run.repo().resolve("openspec/changes");
    assertThat(Files.isDirectory(changes)).as("openspec/changes/ created").isTrue();
    try (var stream = Files.list(changes)) {
      assertThat(stream.filter(Files::isDirectory).count())
          .as("exactly one change-id folder authored")
          .isEqualTo(1L);
    }
    assertThat(run.containerLogs())
        .as("assembly + (mock) validate were invoked")
        .contains("openspec assemble start")
        .contains("openspec validate ok");
  }

  @Test
  void openSpecFlagOffLeavesRepoUntouchedAtPrOutput() throws Exception {
    // AC1/T-FLAG-OFF-BYTE-IDENTICAL: without the flag, pr-output authors NO openspec/ folder and
    // never enters the authoring layer — the legacy path is byte-identical.
    OpenSpecRun run = runWithRepoMount("pr-output", false);
    assertThat(run.exit()).as("flag-off pr-output exits 0").isZero();
    assertThat(Files.exists(run.output().resolve("runner-result.v1.json")))
        .as("result still produced")
        .isTrue();
    assertThat(Files.exists(run.repo().resolve("openspec")))
        .as("flag OFF authors NO openspec/ folder (byte-identical legacy path)")
        .isFalse();
    assertThat(run.containerLogs())
        .as("flag OFF never enters the authoring layer")
        .doesNotContain("openspec enabled");
  }

  @Test
  void openSpecReadOnlyStageNeverWritesRepo() throws Exception {
    // AC3/T-READONLY-NO-REPO-WRITE: on a read-only stage the authoring layer is active (prompt
    // augmented) but the change artifacts go to STDOUT only — the repo must stay untouched.
    OpenSpecRun run = runWithRepoMount("spec-investigation", true);
    assertThat(run.exit()).as("flag-on read-only stage exits 0").isZero();
    assertThat(run.containerLogs())
        .as("authoring layer is active on the read-only stage")
        .contains("openspec enabled");
    assertThat(Files.exists(run.repo().resolve("openspec")))
        .as("read-only stage emits to stdout only — repo must stay untouched")
        .isFalse();
  }

  private static String captureContainerLogs(String containerId) throws Exception {
    StringBuilder sb = new StringBuilder();
    docker
        .logContainerCmd(containerId)
        .withStdOut(true)
        .withStdErr(true)
        .withTailAll()
        .exec(
            new ResultCallback.Adapter<Frame>() {
              @Override
              public void onNext(Frame frame) {
                sb.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
              }
            })
        .awaitCompletion();
    return sb.toString();
  }

  private static String readIfExists(Path path) throws Exception {
    return Files.exists(path)
        ? Files.readString(path, java.nio.charset.StandardCharsets.UTF_8)
        : "";
  }

  private static void makeWorldWritable(Path dir) {
    try {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
    } catch (UnsupportedOperationException | java.io.IOException ignored) {
      // Non-POSIX filesystem (Windows): Docker Desktop bind-mount uid mapping is permissive there.
    }
  }

  private static void makeRunnerReadable(Path dir) {
    try {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (UnsupportedOperationException | java.io.IOException ignored) {
      // Non-POSIX filesystem (Windows): Docker Desktop bind-mount uid mapping is permissive there.
    }
  }

  private static void assertGoldenVariantShape(String resultJson, String expectedType)
      throws Exception {
    String goldenName =
        switch (expectedType) {
          case "spec" -> "runner-result.v1.spec.valid.json";
          case "implementationPlan" -> "runner-result.v1.implementation-plan.valid.json";
          case "prOutput" -> "runner-result.v1.pr-output.valid.json";
          default -> throw new IllegalArgumentException("unexpected artifact type " + expectedType);
        };
    String golden = Files.readString(validFixtures.resolve(goldenName), StandardCharsets.UTF_8);
    switch (expectedType) {
      case "spec" -> {
        assertThat(resultJson).contains("\"contentReference\"");
        assertThat(golden).contains("\"contentReference\"");
      }
      case "implementationPlan" -> {
        assertThat(resultJson).contains("\"steps\"").contains("\"contextReferences\"");
        assertThat(golden).contains("\"steps\"").contains("\"contextReferences\"");
      }
      case "prOutput" -> {
        assertThat(resultJson)
            .contains("\"branch\"")
            .contains("\"commitSha\"")
            .contains("\"prReference\"")
            .contains("\"diffReference\"");
        assertThat(golden)
            .contains("\"branch\"")
            .contains("\"commitSha\"")
            .contains("\"prReference\"")
            .contains("\"diffReference\"");
      }
      default -> throw new IllegalArgumentException("unexpected artifact type " + expectedType);
    }
  }

  /** Format a host path for a docker-java {@link Bind} (Windows drive → {@code /c/...}). */
  private static String dockerHostPath(Path path) {
    String normalized = path.toAbsolutePath().normalize().toString();
    Matcher matcher = WINDOWS_DRIVE_PATH.matcher(normalized);
    if (!matcher.matches()) {
      return normalized;
    }
    return "/"
        + matcher.group(1).toLowerCase(Locale.ROOT)
        + "/"
        + matcher.group(2).replace('\\', '/');
  }

  private static Path locateRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      if (Files.exists(dir.resolve("runners/codex/Dockerfile"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "could not locate repo root (runners/codex/Dockerfile) from "
            + Path.of("").toAbsolutePath());
  }
}
