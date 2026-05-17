package org.dradgo.adapters.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.dradgo.application.diagnostics.DiagnosticsCheck;
import org.dradgo.application.diagnostics.DiagnosticsReport;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DoctorReportRenderer {

  private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private static final String SHAREABLE_REDACTED = DataClassification.SHAREABLE_REDACTED.value();

  private final ObjectMapper objectMapper;
  private final RedactionPolicyService redaction;

  @Autowired
  public DoctorReportRenderer(
      ObjectProvider<ObjectMapper> objectMapperProvider, RedactionPolicyService redaction) {
    this(
        objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules()),
        redaction);
  }

  public DoctorReportRenderer(ObjectMapper objectMapper, RedactionPolicyService redaction) {
    this.objectMapper = objectMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.redaction = redaction;
  }

  public String renderText(DiagnosticsReport report) {
    StringBuilder builder = new StringBuilder();
    for (DiagnosticsCheck check : report.checks()) {
      builder
          .append(check.name())
          .append(": ")
          .append(check.status().name())
          .append(' ')
          .append(safeText(check.summary()))
          .append('\n');
      if (check.remediation() != null && !check.remediation().isBlank()) {
        builder.append("  remediation: ").append(safeText(check.remediation())).append('\n');
      }
    }
    builder.append("overall: ").append(report.overallStatus().name());
    return builder.toString();
  }

  public String renderJson(DiagnosticsReport report) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", report.schemaVersion());
    payload.put("generatedAt", ISO_UTC.format(report.generatedAt()));
    payload.put("overallStatus", report.overallStatus().name());

    List<Map<String, Object>> checks = new ArrayList<>(report.checks().size());
    for (DiagnosticsCheck check : report.checks()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("name", check.name());
      node.put("status", check.status().name());
      node.put("summary", safeText(check.summary()));
      if (check.remediation() != null && !check.remediation().isBlank()) {
        node.put("remediation", safeText(check.remediation()));
      }
      if (check.errorCode() != null && !check.errorCode().isBlank()) {
        node.put("errorCode", check.errorCode());
      }
      if (check.details() != null && !check.details().isEmpty()) {
        Map<String, String> redactedDetails = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : check.details().entrySet()) {
          redactedDetails.put(entry.getKey(), safeText(entry.getValue()));
        }
        node.put("details", redactedDetails);
      }
      checks.add(node);
    }
    payload.put("checks", checks);

    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException error) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("format", "json");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Failed to serialize diagnostics report as JSON",
          details,
          error);
    }
  }

  public static DiagnosticsStatus exitGate(DiagnosticsStatus overall) {
    return overall == null ? DiagnosticsStatus.PASS : overall;
  }

  private String safeText(String value) {
    if (value == null || value.isBlank()) {
      return value == null ? "" : value;
    }
    RedactionResult result = redaction.redact(value, SHAREABLE_REDACTED);
    return result.sanitizedText() == null ? value : result.sanitizedText();
  }

  private static String normalizeFormat(String format) {
    return format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
  }
}
