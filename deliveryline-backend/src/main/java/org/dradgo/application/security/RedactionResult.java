package org.dradgo.application.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.dradgo.domain.registry.DataClassification;

public record RedactionResult(
	String sanitizedText,
	JsonNode sanitizedJson,
	DataClassification claimedClassification,
	DataClassification effectiveClassification,
	boolean redacted,
	Set<RedactionCategory> detectedCategories
) {
}
