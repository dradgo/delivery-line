package org.dradgo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.infrastructure.observability.RedactingMessageConverter;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 1.19 AC6: defense-in-depth check at the four call surfaces flagged in the architecture
 * "Consistency Drift Prevention" rule. Each surface MUST log only its permitted allow-listed
 * fields — never the full context bundle / Authorization header / file contents / artifact bytes.
 *
 * <p>Rewritten under P1 of the story 1.19 review (the previous version asserted on empty appender
 * lists and could not fail). The current test emits forbidden payloads through the same logger
 * names the production surfaces use, routes the captured event through
 * {@link RedactingMessageConverter} (the same converter wired into {@code logback-spring.xml}),
 * and asserts the rendered output does NOT contain the raw forbidden substring. This pins the
 * AC5 safety net for the AC6 surfaces — call-site discipline must be re-verified by the
 * follow-up audit recorded under D5 of the same review.
 */
class LoggingForbiddenPayloadContractTest {

	private static RedactionPolicyService priorService;
	private RedactingMessageConverter converter;
	private ListAppender<ILoggingEvent> brokerAppender;
	private ListAppender<ILoggingEvent> linearAppender;
	private ListAppender<ILoggingEvent> doctorAppender;
	private ListAppender<ILoggingEvent> artifactAppender;

	@BeforeAll
	static void wireHolder() {
		priorService = RedactionLayoutHolder.currentForTesting();
		RedactionLayoutHolder.setRedactionService(
			new RedactionPolicyService(new DataClassificationService()));
	}

	@AfterAll
	static void unwireHolder() {
		if (priorService == null) {
			RedactionLayoutHolder.clearForTesting();
		} else {
			RedactionLayoutHolder.setRedactionService(priorService);
		}
	}

	@BeforeEach
	void setUp() {
		converter = new RedactingMessageConverter();
		converter.start();
		brokerAppender = attach("org.dradgo.application.runner.RunnerBroker");
		linearAppender = attach("org.dradgo.adapters.integration.linear.LinearRealAdapter");
		doctorAppender = attach("org.dradgo.adapters.diagnostics.DoctorProbeAdapter");
		artifactAppender = attach("org.dradgo.adapters.files.LocalArtifactStore");
	}

	@AfterEach
	void tearDown() {
		detach("org.dradgo.application.runner.RunnerBroker", brokerAppender);
		detach("org.dradgo.adapters.integration.linear.LinearRealAdapter", linearAppender);
		detach("org.dradgo.adapters.diagnostics.DoctorProbeAdapter", doctorAppender);
		detach("org.dradgo.adapters.files.LocalArtifactStore", artifactAppender);
		converter.stop();
	}

	@Test
	void runnerBrokerDispatchDoesNotEmitBearerTokenInPayloadAfterRedaction() {
		// Use a secret-shaped substring (Bearer + key) so the redaction layer fires. The actual
		// AC6 call-site-discipline check — that RunnerBroker never logs the bundle JSON at all —
		// must be re-verified by the D5 audit; the policy can only scrub patterns it recognises.
		String forbidden = "Authorization: Bearer sk-AABBCCDDEEFF112233445566778899";
		emit("org.dradgo.application.runner.RunnerBroker",
			"dispatch payload bundle={}", forbidden);
		assertRenderedScrubsForbiddenSubstring(brokerAppender, forbidden, "Bearer sk-AABBCCDDEEFF");
	}

	@Test
	void linearRealAdapterDoesNotEmitAuthorizationHeaderAfterRedaction() {
		String forbidden = "Authorization: Bearer sk-AABBCCDDEEFF112233445566778899";
		emit("org.dradgo.adapters.integration.linear.LinearRealAdapter",
			"http request {}", forbidden);
		assertRenderedScrubsForbiddenSubstring(linearAppender, forbidden, "Bearer sk-AABBCCDDEEFF");
	}

	@Test
	void doctorProbeAdapterDoesNotEmitEnvVarSecretsAfterRedaction() {
		String forbidden = "LINEAR_API_KEY=lin_api_aaaabbbbccccddddeeeeffff";
		emit("org.dradgo.adapters.diagnostics.DoctorProbeAdapter",
			"probe env {}", forbidden);
		assertRenderedScrubsForbiddenSubstring(doctorAppender, forbidden, "lin_api_aaaabbbbccccdddd");
	}

	@Test
	void localArtifactStoreDoesNotEmitEmbeddedSecretInArtifactPayloadAfterRedaction() {
		// Synthetic payload that embeds a recognised secret pattern inside artifact-like bytes.
		// Raw PEM block recognition is policy work owned by story 1.10 follow-up.
		String forbidden = "artifact-data api_key=lin_api_aaaabbbbccccddddeeeeffff embedded";
		emit("org.dradgo.adapters.files.LocalArtifactStore",
			"artifact payload={}", forbidden);
		assertRenderedScrubsForbiddenSubstring(artifactAppender, forbidden, "lin_api_aaaabbbbccccdddd");
	}

	private void emit(String loggerName, String pattern, String arg) {
		LoggerFactory.getLogger(loggerName).info(pattern, arg);
	}

	private void assertRenderedScrubsForbiddenSubstring(
		ListAppender<ILoggingEvent> appender,
		String fullPayload,
		String forbiddenSubstring
	) {
		assertThat(appender.list)
			.as("at least one log event must have been captured for this surface")
			.isNotEmpty();
		for (ILoggingEvent event : appender.list) {
			String rendered = converter.convert(event);
			assertThat(rendered)
				.as("post-redaction rendered log line must NOT contain forbidden substring "
					+ "[" + forbiddenSubstring + "] for event " + event.getFormattedMessage())
				.doesNotContain(forbiddenSubstring);
			assertThat(rendered)
				.as("post-redaction rendered log line must NOT contain raw payload")
				.doesNotContain(fullPayload);
		}
	}

	private static ListAppender<ILoggingEvent> attach(String loggerName) {
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		((Logger) LoggerFactory.getLogger(loggerName)).addAppender(appender);
		return appender;
	}

	private static void detach(String loggerName, ListAppender<ILoggingEvent> appender) {
		((Logger) LoggerFactory.getLogger(loggerName)).detachAppender(appender);
	}
}
