package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.ValidationContext;
import org.dradgo.runnercontracts.ValidationError;
import org.dradgo.runnercontracts.ValidationErrorCode;
import org.dradgo.runnercontracts.ValidationResult;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Foundation contract #5 — every {@link RunnerContractValidator} fixture in the
 * {@code deliveryline-runner-contracts} test corpus passes (for {@code valid/}) or fails with the
 * expected {@link ValidationErrorCode} (for {@code invalid/}).
 *
 * <p>Story 1.23 AC2 #5 calls for an {@code .expected-error} sidecar convention; the project
 * already maintains an equivalent manifest at
 * {@code fixture-expectations.json}. Per Open Clarification #2 ("use the existing convention"),
 * the contract reads that manifest rather than introducing a parallel sidecar format.
 *
 * <p>Fixture paths resolve filesystem-relative to the {@code deliveryline-backend/} CWD —
 * Maven runs Failsafe with that as the working directory, so {@code ../deliveryline-runner-contracts/...}
 * reaches the sibling module's test resources without requiring a {@code test-jar} dependency.
 */
@Tag("foundation-gate")
class RunnerContractFixturesFoundationContract {

  private static final Path RUNNER_FIXTURE_ROOT =
      Path.of("..", "deliveryline-runner-contracts", "src", "test", "resources", "fixtures");

  private static final Path VALID_FIXTURE_DIRECTORY = RUNNER_FIXTURE_ROOT.resolve("valid");
  private static final Path INVALID_FIXTURE_DIRECTORY = RUNNER_FIXTURE_ROOT.resolve("invalid");
  private static final Path EXPECTATIONS_MANIFEST =
      RUNNER_FIXTURE_ROOT.resolve("fixture-expectations.json");

  @Test
  void allValidFixturesPassAndAllInvalidFixturesReportTheirExpectedTypedErrorCode()
      throws IOException {
    List<String> violations = new ArrayList<>();
    if (!Files.isDirectory(VALID_FIXTURE_DIRECTORY)) {
      fail(
          FoundationGateAssertions.tagged(
              "1.6",
              "valid fixture directory missing at "
                  + VALID_FIXTURE_DIRECTORY.toAbsolutePath()));
      return;
    }
    if (!Files.isDirectory(INVALID_FIXTURE_DIRECTORY)) {
      fail(
          FoundationGateAssertions.tagged(
              "1.6",
              "invalid fixture directory missing at "
                  + INVALID_FIXTURE_DIRECTORY.toAbsolutePath()));
      return;
    }
    if (!Files.isRegularFile(EXPECTATIONS_MANIFEST)) {
      fail(
          FoundationGateAssertions.tagged(
              "1.6",
              "fixture-expectations.json missing at " + EXPECTATIONS_MANIFEST.toAbsolutePath()));
      return;
    }

    RunnerContractValidator validator = new RunnerContractValidator();

    // --- valid corpus: every file must validate cleanly. ---
    List<Path> validFixtures = listJsonFiles(VALID_FIXTURE_DIRECTORY);
    if (validFixtures.isEmpty()) {
      violations.add("valid/ directory contains zero JSON fixtures — silent corpus");
    }
    for (Path fixture : validFixtures) {
      ValidationTarget target = targetFor(fixture);
      ValidationResult result = validator.validateFixture(target, fixture);
      if (!result.valid()) {
        violations.add(
            "valid fixture "
                + fixture.getFileName()
                + " reported errors: "
                + summarize(result.errors()));
      }
    }

    // --- invalid corpus: every file must be enumerated in the manifest AND fail with the
    // expected error code. The silent-fixture invariant fails the gate if an invalid/ fixture
    // exists without a manifest entry. ---
    ObjectMapper mapper = new ObjectMapper();
    JsonNode manifest = mapper.readTree(EXPECTATIONS_MANIFEST.toFile());
    JsonNode invalidEntries = manifest.path("invalidFixtures");
    if (!invalidEntries.isArray() || invalidEntries.isEmpty()) {
      violations.add("fixture-expectations.json invalidFixtures is missing or empty");
    }

    Set<Path> manifestPaths = new LinkedHashSet<>();
    for (JsonNode entry : invalidEntries) {
      String pathFromManifest = entry.path("path").asText("");
      String targetName = entry.path("target").asText("");
      String expectedCode = entry.path("expectedCode").asText("");
      if (pathFromManifest.isEmpty() || targetName.isEmpty() || expectedCode.isEmpty()) {
        violations.add(
            "manifest entry missing path/target/expectedCode: " + entry.toString());
        continue;
      }
      Path fixture = resolveManifestPath(pathFromManifest);
      manifestPaths.add(fixture.toAbsolutePath().normalize());
      if (!Files.isRegularFile(fixture)) {
        violations.add(
            "manifest references missing fixture: "
                + fixture
                + " (resolved from manifest path "
                + pathFromManifest
                + ")");
        continue;
      }
      ValidationTarget target;
      try {
        target = ValidationTarget.valueOf(targetName);
      } catch (IllegalArgumentException exception) {
        violations.add(
            "manifest entry references unknown ValidationTarget "
                + targetName
                + " for "
                + fixture.getFileName());
        continue;
      }
      ValidationErrorCode expected;
      try {
        expected = ValidationErrorCode.valueOf(expectedCode);
      } catch (IllegalArgumentException exception) {
        violations.add(
            "manifest entry references unknown ValidationErrorCode "
                + expectedCode
                + " for "
                + fixture.getFileName());
        continue;
      }
      ValidationContext context = contextFor(expected, fixture);
      ValidationResult result = validator.validateFixture(target, fixture, context);
      if (result.valid()) {
        violations.add(
            "invalid fixture "
                + fixture.getFileName()
                + " unexpectedly passed validation");
        continue;
      }
      boolean found =
          result.errors().stream().anyMatch(err -> err.code() == expected);
      if (!found) {
        violations.add(
            "invalid fixture "
                + fixture.getFileName()
                + " did not report expected code "
                + expected
                + " — got: "
                + summarize(result.errors()));
      }
    }

    // Silent-fixture invariant: every file under invalid/ must be enumerated in the manifest.
    for (Path file : listJsonFiles(INVALID_FIXTURE_DIRECTORY)) {
      Path normalized = file.toAbsolutePath().normalize();
      if (!manifestPaths.contains(normalized)) {
        violations.add(
            "invalid/ fixture "
                + file.getFileName()
                + " is not enumerated in fixture-expectations.json — silent fixtures hide"
                + " contract drift");
      }
    }

    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.6",
              "RunnerContractValidator fixture sweep failed ("
                  + violations.size()
                  + " issue"
                  + (violations.size() == 1 ? "" : "s")
                  + "): "
                  + String.join("; ", violations)));
    }
  }

  private static Path resolveManifestPath(String manifestPath) {
    // Manifest stores paths like "src/test/resources/fixtures/invalid/foo.json" (relative to the
    // runner-contracts module). Resolve against RUNNER_FIXTURE_ROOT by stripping the
    // "src/test/resources/fixtures/" prefix, falling back to the raw path otherwise.
    String prefix = "src/test/resources/fixtures/";
    String tail = manifestPath.startsWith(prefix) ? manifestPath.substring(prefix.length()) : manifestPath;
    return RUNNER_FIXTURE_ROOT.resolve(tail);
  }

  private static List<Path> listJsonFiles(Path directory) throws IOException {
    try (Stream<Path> stream = Files.list(directory)) {
      List<Path> result = new ArrayList<>();
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .sorted()
          .forEach(result::add);
      return result;
    }
  }

  private static final Pattern RUNNER_EXECUTION_ID_FIELD =
      Pattern.compile("\"runnerExecutionId\"\\s*:\\s*\"([^\"]+)\"");

  private static ValidationContext contextFor(ValidationErrorCode expected, Path fixture)
      throws IOException {
    return switch (expected) {
      case FILE_TOO_LARGE -> ValidationContext.builder().maxPayloadBytes(64).build();
      case DUPLICATE_RUNNER_EXECUTION_ID ->
          ValidationContext.builder()
              .maxPayloadBytes(1_000_000)
              .addObservedRunnerExecutionId(extractRunnerExecutionId(fixture))
              .build();
      case STALE_METADATA ->
          ValidationContext.builder()
              .maxPayloadBytes(1_000_000)
              .addKnownRunnerExecutionId("rex_known_from_context")
              .build();
      default -> ValidationContext.builder().maxPayloadBytes(1_000_000).build();
    };
  }

  private static String extractRunnerExecutionId(Path fixture) throws IOException {
    String content = Files.readString(fixture);
    Matcher matcher = RUNNER_EXECUTION_ID_FIELD.matcher(content);
    return matcher.find() ? matcher.group(1) : "rex_unknown";
  }

  private static ValidationTarget targetFor(Path fixture) {
    String name = fixture.getFileName().toString();
    return name.startsWith("context-bundle") ? ValidationTarget.CONTEXT_BUNDLE : ValidationTarget.RUNNER_RESULT;
  }

  private static String summarize(List<ValidationError> errors) {
    if (errors.isEmpty()) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < errors.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      ValidationError err = errors.get(i);
      sb.append(err.code()).append('@').append(err.path());
    }
    return sb.append(']').toString();
  }
}
