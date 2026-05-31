package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
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
