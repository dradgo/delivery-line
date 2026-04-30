package org.dradgo.domain.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PublicIdPrefixesTest {

	private static final Pattern WORKFLOW_RUN_PATTERN = Pattern.compile("^run_[A-Za-z0-9_-]{4,64}$");
	private static final Pattern WORKFLOW_EVENT_PATTERN = Pattern.compile("^evt_[A-Za-z0-9_-]{4,64}$");

	@Test
	void nextProducesAValueMatchingTheRegisteredPrefixAndSuffixPattern() {
		String workflowRunId = PublicIdPrefixes.WORKFLOW_RUN.next();
		assertTrue(
			WORKFLOW_RUN_PATTERN.matcher(workflowRunId).matches(),
			"WORKFLOW_RUN.next() must match " + WORKFLOW_RUN_PATTERN.pattern() + " but was: " + workflowRunId);

		String workflowEventId = PublicIdPrefixes.WORKFLOW_EVENT.next();
		assertTrue(
			WORKFLOW_EVENT_PATTERN.matcher(workflowEventId).matches(),
			"WORKFLOW_EVENT.next() must match " + WORKFLOW_EVENT_PATTERN.pattern() + " but was: " + workflowEventId);
	}

	@Test
	void nextProducesA32CharSuffixDerivedFromAUuid() {
		String workflowRunId = PublicIdPrefixes.WORKFLOW_RUN.next();
		String suffix = workflowRunId.substring(PublicIdPrefixes.WORKFLOW_RUN.prefix().length());
		assertEquals(32, suffix.length(), "UUID-derived suffix must be 32 chars (UUID hex without dashes)");
	}

	@Test
	void nextProducesDistinctValuesAcrossInvocations() {
		String first = PublicIdPrefixes.WORKFLOW_RUN.next();
		String second = PublicIdPrefixes.WORKFLOW_RUN.next();
		assertNotEquals(first, second, "Sequential next() calls must produce distinct ids");
	}

	@Test
	void requireAcceptsAValueProducedByNext() {
		String workflowRunId = PublicIdPrefixes.WORKFLOW_RUN.next();
		assertSame(
			workflowRunId,
			PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN));
	}
}
