package org.dradgo.contract;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 1.23 AC7 — collectively the fixture corpus exercises every {@code artifactVariant}.
 *
 * <p>Scans every fixture under {@code fixture-event-streams/} for {@code details.artifactVariant}
 * occurrences and asserts the observed set equals {@code {spec, implementationPlan, prOutput}}
 * exactly. Missing variants fail (party-mode finding #2 — Epic 2's Artifact Review Panel
 * generalizes from day one); surprise new variants also fail (would silently extend the contract).
 */
@Tag("contract")
@Tag("foundation-gate")
class FixtureEventStreamArtifactVariantCoverageContractTest {

  private static final Path FIXTURE_ROOT =
      Path.of("src", "test", "resources", "fixture-event-streams");
  private static final Set<String> EXPECTED_VARIANTS =
      Set.of("spec", "implementationPlan", "prOutput");

  @Test
  void fixtureCorpusCoversEveryArtifactVariantExactlyOnce() throws IOException {
    if (!Files.isDirectory(FIXTURE_ROOT)) {
      fail(
          "[story 1.23] fixture-event-streams directory missing at "
              + FIXTURE_ROOT.toAbsolutePath());
      return;
    }

    ObjectMapper mapper = new ObjectMapper();
    Set<String> observed = new LinkedHashSet<>();
    List<String> violations = new ArrayList<>();

    List<Path> fixtures = listFixtureJson();
    if (fixtures.isEmpty()) {
      fail(
          "[story 1.23] no fixture .json files found under "
              + FIXTURE_ROOT.toAbsolutePath());
      return;
    }

    for (Path fixture : fixtures) {
      JsonNode root = mapper.readTree(fixture.toFile());
      JsonNode events = root.path("events");
      if (!events.isArray()) {
        continue;
      }
      for (JsonNode event : events) {
        JsonNode variant = event.path("details").path("artifactVariant");
        if (variant.isTextual()) {
          observed.add(variant.asText());
        }
      }
    }

    for (String required : EXPECTED_VARIANTS) {
      if (!observed.contains(required)) {
        violations.add("expected artifact variant '" + required + "' is not present in any fixture");
      }
    }
    for (String surprise : observed) {
      if (!EXPECTED_VARIANTS.contains(surprise)) {
        violations.add(
            "fixture corpus contains unexpected artifactVariant '"
                + surprise
                + "' — extend the contract before adding new variants");
      }
    }

    if (!violations.isEmpty()) {
      fail(
          "[story 1.23] artifact variant coverage violations ("
              + violations.size()
              + "): "
              + String.join("; ", violations));
    }
  }

  private static List<Path> listFixtureJson() throws IOException {
    try (Stream<Path> stream = Files.walk(FIXTURE_ROOT)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .filter(p -> !p.startsWith(FIXTURE_ROOT.resolve("schema")))
          .sorted()
          .toList();
    }
  }
}
