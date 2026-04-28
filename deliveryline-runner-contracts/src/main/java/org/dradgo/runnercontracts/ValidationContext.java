package org.dradgo.runnercontracts;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record ValidationContext(
	Set<String> knownRunnerExecutionIds,
	Set<String> observedRunnerExecutionIds,
	int maxPayloadBytes
) {

	public static final int DEFAULT_MAX_PAYLOAD_BYTES = 2048;

	public ValidationContext {
		Objects.requireNonNull(knownRunnerExecutionIds, "knownRunnerExecutionIds");
		Objects.requireNonNull(observedRunnerExecutionIds, "observedRunnerExecutionIds");
		if (maxPayloadBytes <= 0) {
			throw new IllegalArgumentException("maxPayloadBytes must be positive");
		}
		knownRunnerExecutionIds = Set.copyOf(knownRunnerExecutionIds);
		observedRunnerExecutionIds = Set.copyOf(observedRunnerExecutionIds);
	}

	public static ValidationContext defaults() {
		return new ValidationContext(Set.of(), Set.of(), DEFAULT_MAX_PAYLOAD_BYTES);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private final Set<String> knownRunnerExecutionIds = new LinkedHashSet<>();
		private final Set<String> observedRunnerExecutionIds = new LinkedHashSet<>();
		private int maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES;

		private Builder() {
		}

		public Builder addKnownRunnerExecutionId(String runnerExecutionId) {
			knownRunnerExecutionIds.add(runnerExecutionId);
			return this;
		}

		public Builder addObservedRunnerExecutionId(String runnerExecutionId) {
			observedRunnerExecutionIds.add(runnerExecutionId);
			return this;
		}

		public Builder maxPayloadBytes(int maxPayloadBytes) {
			this.maxPayloadBytes = maxPayloadBytes;
			return this;
		}

		public ValidationContext build() {
			return new ValidationContext(knownRunnerExecutionIds, observedRunnerExecutionIds, maxPayloadBytes);
		}
	}
}
