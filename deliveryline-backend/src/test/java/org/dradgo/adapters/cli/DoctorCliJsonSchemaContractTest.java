package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Result;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
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

class DoctorCliJsonSchemaContractTest {

  private static final String SCHEMA_LOCATION =
      "classpath:schemas/cli/doctor-report.v1.schema.json";

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final RedactionPolicyService redaction =
      new RedactionPolicyService(new DataClassificationService());
  private final DoctorReportRenderer renderer = new DoctorReportRenderer(mapper, redaction);

  private final SchemaRegistry schemaRegistry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  @Test
  void allPassReportValidatesAgainstSchema() {
    assertSchemaValid(renderer.renderJson(allPass()));
  }

  @Test
  void singleFailReportValidatesAgainstSchema() {
    assertSchemaValid(renderer.renderJson(oneFail()));
  }

  @Test
  void allSkipReportValidatesAgainstSchema() {
    assertSchemaValid(renderer.renderJson(allSkip()));
  }

  @Test
  void schemaVersionIsLocked() {
    String json = renderer.renderJson(allPass());
    assertThat(json).contains("\"schemaVersion\":1");
  }

  @Test
  void renderedJsonHasNoExtraTopLevelKeys() throws Exception {
    String json = renderer.renderJson(oneFail());
    List<String> keys = new java.util.ArrayList<>();
    mapper.readTree(json).fieldNames().forEachRemaining(keys::add);
    assertThat(keys).containsOnly("schemaVersion", "generatedAt", "overallStatus", "checks");
  }

  private void assertSchemaValid(String json) {
    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(json, InputFormat.JSON, true);
    assertThat(validation.getErrors())
        .as(() -> "Schema validation errors: " + validation.getErrors())
        .isEmpty();
  }

  private DiagnosticsReport allPass() {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("javaVersion", "21");
    return new DiagnosticsReport(
        1,
        OffsetDateTime.parse("2026-05-14T10:00:00Z"),
        DiagnosticsStatus.PASS,
        List.of(
            new DiagnosticsCheck(
                "java-version", DiagnosticsStatus.PASS, "Java 21", null, null, details)));
  }

  private DiagnosticsReport oneFail() {
    return new DiagnosticsReport(
        1,
        OffsetDateTime.parse("2026-05-14T10:01:00Z"),
        DiagnosticsStatus.FAIL,
        List.of(
            new DiagnosticsCheck(
                "postgres-connectivity",
                DiagnosticsStatus.FAIL,
                "Postgres unreachable",
                "Run docker compose up.",
                "DOCTOR_POSTGRES_UNREACHABLE",
                Map.of())));
  }

  private DiagnosticsReport allSkip() {
    return new DiagnosticsReport(
        1,
        OffsetDateTime.parse("2026-05-14T10:02:00Z"),
        DiagnosticsStatus.PASS,
        List.of(
            new DiagnosticsCheck(
                "runner-image-availability",
                DiagnosticsStatus.SKIP,
                "Runner image availability check populated in story 3.1",
                null,
                null,
                Map.of()),
            new DiagnosticsCheck(
                "frontend-asset-presence",
                DiagnosticsStatus.SKIP,
                "Frontend asset check populated in story 2.28",
                null,
                null,
                Map.of())));
  }
}
