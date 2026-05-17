package org.dradgo.application.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.springframework.stereotype.Service;

@Service
public class RedactionPolicyService {

  private final DataClassificationService classificationService;

  public RedactionPolicyService(DataClassificationService classificationService) {
    this.classificationService = classificationService;
  }

  public RedactionResult redact(String payload, String claimedClassificationValue) {
    return toResult(classificationService.analyze(payload, claimedClassificationValue));
  }

  public RedactionResult redact(Map<String, Object> payload, String claimedClassificationValue) {
    return toResult(classificationService.analyze(payload, claimedClassificationValue));
  }

  public RedactionResult redact(JsonNode payload, String claimedClassificationValue) {
    return toResult(classificationService.analyze(payload, claimedClassificationValue));
  }

  public RedactionResult redactForExport(String payload, String claimedClassificationValue) {
    RedactionResult result = redact(payload, claimedClassificationValue);
    assertExportable(result);
    return result;
  }

  public RedactionResult redactForExport(
      Map<String, Object> payload, String claimedClassificationValue) {
    RedactionResult result = redact(payload, claimedClassificationValue);
    assertExportable(result);
    return result;
  }

  public RedactionResult redactForExport(JsonNode payload, String claimedClassificationValue) {
    RedactionResult result = redact(payload, claimedClassificationValue);
    assertExportable(result);
    return result;
  }

  private void assertExportable(RedactionResult result) {
    if (result.effectiveClassification() == DataClassification.LOCAL_ONLY) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("effectiveClassification", result.effectiveClassification().value());
      details.put(
          "claimedClassification",
          result.claimedClassification() == null ? null : result.claimedClassification().value());
      throw new DomainException(
          DomainErrorCode.EXPORT_CLASSIFICATION_VIOLATION,
          "Export blocked because payload remains local-only after policy evaluation",
          details);
    }
  }

  private RedactionResult toResult(SensitivePayloadAnalyzer.SensitiveAnalysis analysis) {
    return new RedactionResult(
        analysis.sanitizedText(),
        analysis.sanitizedJson(),
        analysis.claimedClassification(),
        analysis.effectiveClassification(),
        analysis.redacted(),
        analysis.detectedCategories());
  }
}
