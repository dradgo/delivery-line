package org.dradgo.application.security;

import java.util.Set;
import org.dradgo.domain.registry.DataClassification;

public record ClassificationAssessment(
	DataClassification claimedClassification,
	DataClassification effectiveClassification,
	boolean redactionRequired,
	Set<RedactionCategory> detectedCategories
) {
}
