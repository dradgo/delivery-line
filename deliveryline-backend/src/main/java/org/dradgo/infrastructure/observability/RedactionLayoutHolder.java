package org.dradgo.infrastructure.observability;

import java.util.concurrent.atomic.AtomicBoolean;
import org.dradgo.application.security.RedactionPolicyService;

/**
 * Static holder that bridges Logback (which instantiates converters reflectively, outside the
 * Spring container) and {@link RedactionPolicyService} (a Spring-managed singleton).
 *
 * <p>Wired at application startup by {@link RedactionLayoutInitializer}. Log lines emitted
 * before the holder is wired — Spring boot banners during startup, and any log line emitted
 * during shutdown after {@code @PreDestroy} clears the holder — are routed through a
 * fail-closed placeholder ({@code [redaction-pending]}) so credentials in shutdown-window
 * Hikari/JPA logs cannot leak. This is the resolution recorded for D1 in the story 1.19
 * code review (2026-05-16).
 */
public final class RedactionLayoutHolder {

	private static volatile RedactionPolicyService service;
	private static final String FALLBACK = "[redaction-failed]";
	private static final String COLD_START = "[redaction-pending]";
	private static final String CLAIMED_CLASSIFICATION = "shareable-redacted";
	private static final AtomicBoolean diagnosticEmitted = new AtomicBoolean(false);

	private RedactionLayoutHolder() {
		throw new AssertionError("no instances");
	}

	public static void setRedactionService(RedactionPolicyService redactionService) {
		service = redactionService;
	}

	/**
	 * Run the supplied raw text through {@link RedactionPolicyService}. On any failure, fall
	 * back to a single safe literal so a logging-layer failure can never propagate into the
	 * request path. If the service has not been wired yet (cold start) OR has been cleared
	 * (shutdown), return {@link #COLD_START} so unredacted secrets can never reach stdout.
	 */
	public static String redact(String rawMessage) {
		if (rawMessage == null || rawMessage.isEmpty()) {
			return rawMessage;
		}
		RedactionPolicyService local = service;
		if (local == null) {
			return COLD_START;
		}
		try {
			return local.redact(rawMessage, CLAIMED_CLASSIFICATION).sanitizedText();
		} catch (RuntimeException e) {
			emitDiagnosticOnce(e);
			return FALLBACK;
		}
	}

	private static void emitDiagnosticOnce(RuntimeException e) {
		if (diagnosticEmitted.compareAndSet(false, true)) {
			System.err.println("[redaction-layout] " + e.getClass().getName() + ": " + e.getMessage());
		}
	}

	/** Test-only reset hook so contract tests can assert cold-start behaviour. */
	public static void clearForTesting() {
		service = null;
		diagnosticEmitted.set(false);
	}

	/**
	 * Test-only snapshot of the current service reference so tests can capture-and-restore the
	 * holder around their setUp/tearDown, avoiding cross-test pollution when one test overrides
	 * the holder. See P16 of the story 1.19 review.
	 */
	public static RedactionPolicyService currentForTesting() {
		return service;
	}
}
