package org.dradgo.application.integration.linear;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound configuration for the Linear integration subsystem (story 1.14 Task 4).
 *
 * <p>{@code apiToken} is sourced from the {@code LINEAR_API_TOKEN} environment variable via
 * standard Spring property placeholder resolution — never hardcoded. The accessor is marked
 * {@link JsonIgnore} so accidental serialization (Actuator/management endpoints, logging
 * frameworks that walk POJOs) cannot leak the token; {@link #toString()} is overridden to redact.
 *
 * <p>Lives in {@code application.integration.linear} (not {@code infrastructure.config}) so the
 * "application must not depend on infrastructure" ArchUnit rule stays clean — the
 * {@code LinearRealAdapter} reads its config from this record. Mirrors the
 * {@code application.runner.RunnerProperties} pattern from story 1.13.
 *
 * <p>Polling defaults to a 60-second interval per AC9 ("default 60s"). The {@code polling.enabled}
 * flag (default true) lets ops keep the real adapter wired but mute the scheduler when no
 * upstream consumer requires intake — the scheduler bean is `@ConditionalOnProperty` against
 * this flag.
 */
@ConfigurationProperties("deliveryline.linear")
public record LinearProperties(
	String apiToken,
	String baseUrl,
	long pollIntervalMs,
	int pollBatchSize,
	Timeout timeout,
	double staleThresholdMultiplier,
	Polling polling
) {

	public LinearProperties {
		baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.linear.app/graphql" : baseUrl;
		if (pollIntervalMs <= 0L) {
			throw new IllegalArgumentException(
				"deliveryline.linear.poll-interval-ms must be positive: " + pollIntervalMs);
		}
		if (pollBatchSize <= 0) {
			throw new IllegalArgumentException(
				"deliveryline.linear.poll-batch-size must be positive: " + pollBatchSize);
		}
		if (staleThresholdMultiplier <= 0.0d) {
			throw new IllegalArgumentException(
				"deliveryline.linear.stale-threshold-multiplier must be positive: " + staleThresholdMultiplier);
		}
		timeout = timeout == null ? Timeout.defaults() : timeout;
		polling = polling == null ? Polling.defaults() : polling;
	}

	public static LinearProperties defaults() {
		return new LinearProperties(
			null,
			"https://api.linear.app/graphql",
			60_000L,
			50,
			Timeout.defaults(),
			2.0d,
			Polling.defaults());
	}

	@JsonIgnore
	@Override
	public String apiToken() {
		return apiToken;
	}

	@Override
	public String toString() {
		return "LinearProperties{baseUrl=" + baseUrl
			+ ", pollIntervalMs=" + pollIntervalMs
			+ ", pollBatchSize=" + pollBatchSize
			+ ", timeout=" + timeout
			+ ", staleThresholdMultiplier=" + staleThresholdMultiplier
			+ ", polling=" + polling
			+ ", apiToken=<redacted>}";
	}

	public record Timeout(long connectMs, long readMs) {

		public Timeout {
			if (connectMs <= 0L) {
				throw new IllegalArgumentException(
					"deliveryline.linear.timeout.connect-ms must be positive: " + connectMs);
			}
			if (readMs <= 0L) {
				throw new IllegalArgumentException(
					"deliveryline.linear.timeout.read-ms must be positive: " + readMs);
			}
		}

		public static Timeout defaults() {
			return new Timeout(5_000L, 30_000L);
		}
	}

	public record Polling(boolean enabled) {

		public static Polling defaults() {
			return new Polling(true);
		}
	}
}
