package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.dradgo.application.diagnostics.DiagnosticsReport;
import org.dradgo.application.diagnostics.DoctorRunRequest;
import org.dradgo.application.diagnostics.DoctorService;
import org.dradgo.application.diagnostics.spi.DoctorProbePort;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class DoctorRedactionContractTest {

  private static final String FIXTURE_ROOT = "redaction-fixtures/";
  private static final String MANIFEST_RESOURCE = FIXTURE_ROOT + "fixtures-manifest.json";

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final RedactionPolicyService redaction =
      new RedactionPolicyService(new DataClassificationService());
  private final DoctorReportRenderer renderer = new DoctorReportRenderer(objectMapper, redaction);

  @TestFactory
  Stream<DynamicTest> doctorJsonPipelineRedactsSecretFixtureCorpus() throws IOException {
    FixtureManifest manifest = readManifest();
    return manifest.fixtures().stream()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture.file(),
                    () -> {
                      String raw = readResource(FIXTURE_ROOT + fixture.file());
                      DoctorProbePort probes = mock(DoctorProbePort.class);
                      when(probes.probePostgresConnectivity())
                          .thenReturn(
                              ProbeResult.fail(
                                  raw,
                                  DomainErrorCode.DOCTOR_POSTGRES_UNREACHABLE.value(),
                                  Map.of("fixture", raw)));
                      DoctorService service =
                          new DoctorService(
                              probes,
                              redaction,
                              Clock.fixed(Instant.parse("2026-05-14T10:00:00Z"), ZoneOffset.UTC));

                      DiagnosticsReport report =
                          service.runDiagnostics(
                              new DoctorRunRequest(
                                  Set.of(DoctorService.CHECK_POSTGRES_CONNECTIVITY),
                                  Set.of(),
                                  "corr-redaction"));
                      String json = renderer.renderJson(report);

                      assertThat(json).contains(fixture.placeholder());
                      for (String forbiddenSnippet : fixture.forbiddenSnippets()) {
                        assertThat(json).doesNotContain(forbiddenSnippet);
                      }
                    }));
  }

  private FixtureManifest readManifest() throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing fixture manifest " + MANIFEST_RESOURCE);
      }
      return objectMapper.readValue(stream, FixtureManifest.class);
    }
  }

  private String readResource(String resource) throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing fixture " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  record FixtureManifest(List<FixtureEntry> fixtures) {}

  record FixtureEntry(
      String file,
      String placeholder,
      String minimumClassification,
      List<String> forbiddenSnippets) {}
}
