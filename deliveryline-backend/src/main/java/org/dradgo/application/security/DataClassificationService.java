package org.dradgo.application.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DataClassificationService {

	private final SensitivePayloadAnalyzer analyzer = new SensitivePayloadAnalyzer();

	public ClassificationAssessment assess(String payload, String claimedClassificationValue) {
		return toAssessment(analyzer.analyzeText(payload, claimedClassificationValue));
	}

	public ClassificationAssessment assess(Map<String, Object> payload, String claimedClassificationValue) {
		return toAssessment(analyzer.analyzeStructured(payload, claimedClassificationValue));
	}

	public ClassificationAssessment assess(JsonNode payload, String claimedClassificationValue) {
		return toAssessment(analyzer.analyzeStructured(payload, claimedClassificationValue));
	}

	SensitivePayloadAnalyzer.SensitiveAnalysis analyze(String payload, String claimedClassificationValue) {
		return analyzer.analyzeText(payload, claimedClassificationValue);
	}

	SensitivePayloadAnalyzer.SensitiveAnalysis analyze(Map<String, Object> payload, String claimedClassificationValue) {
		return analyzer.analyzeStructured(payload, claimedClassificationValue);
	}

	SensitivePayloadAnalyzer.SensitiveAnalysis analyze(JsonNode payload, String claimedClassificationValue) {
		return analyzer.analyzeStructured(payload, claimedClassificationValue);
	}

	private ClassificationAssessment toAssessment(SensitivePayloadAnalyzer.SensitiveAnalysis analysis) {
		return new ClassificationAssessment(
			analysis.claimedClassification(),
			analysis.effectiveClassification(),
			analysis.redacted(),
			analysis.detectedCategories());
	}
}
