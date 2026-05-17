package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.diagnostics.DiagnosticsCheck;
import org.dradgo.application.diagnostics.DiagnosticsReport;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.junit.jupiter.api.Test;

class DoctorReportRendererTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final RedactionPolicyService redaction =
      new RedactionPolicyService(new DataClassificationService());
  private final DoctorReportRenderer renderer = new DoctorReportRenderer(mapper, redaction);

  @Test
  void renderTextEmitsPerCheckLineAndOverall() {
    DiagnosticsReport report =
        sampleReport(
            new DiagnosticsCheck(
                "java-version", DiagnosticsStatus.PASS, "Java 21", null, null, Map.of()),
            new DiagnosticsCheck(
                "postgres-connectivity",
                DiagnosticsStatus.FAIL,
                "Postgres unreachable",
                "Run 'docker compose up -d postgres' and re-check.",
                "DOCTOR_POSTGRES_UNREACHABLE",
                Map.of()));

    String text = renderer.renderText(report);

    assertThat(text).contains("java-version: PASS Java 21");
    assertThat(text).contains("postgres-connectivity: FAIL Postgres unreachable");
    assertThat(text).contains("  remediation: Run 'docker compose up -d postgres' and re-check.");
    assertThat(text).contains("overall: FAIL");
  }

  @Test
  void renderTextOmitsAnsiColors() {
    DiagnosticsReport report =
        sampleReport(
            new DiagnosticsCheck(
                "java-version", DiagnosticsStatus.PASS, "Java 21", null, null, Map.of()));

    String text = renderer.renderText(report);
    assertThat(text).doesNotContain("[");
  }

  @Test
  void renderJsonIncludesSchemaVersionAndCheckShape() throws Exception {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("javaVendor", "Eclipse Adoptium");
    DiagnosticsReport report =
        sampleReport(
            new DiagnosticsCheck(
                "java-version", DiagnosticsStatus.PASS, "Java 21", null, null, details));

    String json = renderer.renderJson(report);
    JsonNode payload = mapper.readTree(json);

    assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(payload.get("overallStatus").asText()).isEqualTo("PASS");
    JsonNode firstCheck = payload.get("checks").get(0);
    assertThat(firstCheck.get("name").asText()).isEqualTo("java-version");
    assertThat(firstCheck.get("status").asText()).isEqualTo("PASS");
    assertThat(firstCheck.get("summary").asText()).isEqualTo("Java 21");
    assertThat(firstCheck.has("remediation")).isFalse();
    assertThat(firstCheck.has("errorCode")).isFalse();
    assertThat(firstCheck.get("details").get("javaVendor").asText()).isEqualTo("Eclipse Adoptium");
  }

  @Test
  void renderJsonRedactsSecretsInDetails() throws Exception {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("authHeader", "Authorization: Bearer ghp_aaaaaaaaaaaaaaaaaaaa");
    DiagnosticsReport report =
        sampleReport(
            new DiagnosticsCheck(
                "postgres-connectivity",
                DiagnosticsStatus.FAIL,
                "boom",
                "Run docker compose up.",
                "DOCTOR_POSTGRES_UNREACHABLE",
                details));

    String json = renderer.renderJson(report);
    assertThat(json).doesNotContain("ghp_aaaaaaaaaaaaaaaaaaaa");
  }

  private DiagnosticsReport sampleReport(DiagnosticsCheck... checks) {
    DiagnosticsStatus overall = computeOverall(checks);
    return new DiagnosticsReport(
        1, OffsetDateTime.parse("2026-05-14T10:00:00Z"), overall, List.of(checks));
  }

  private DiagnosticsStatus computeOverall(DiagnosticsCheck... checks) {
    for (DiagnosticsCheck c : checks) {
      if (c.status() == DiagnosticsStatus.FAIL) {
        return DiagnosticsStatus.FAIL;
      }
    }
    for (DiagnosticsCheck c : checks) {
      if (c.status() == DiagnosticsStatus.WARN) {
        return DiagnosticsStatus.WARN;
      }
    }
    return DiagnosticsStatus.PASS;
  }
}
