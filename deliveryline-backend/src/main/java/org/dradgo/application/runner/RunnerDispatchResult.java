package org.dradgo.application.runner;

import java.util.Objects;

public sealed interface RunnerDispatchResult {

	RunnerExecutionHandle handle();

	default boolean isReplay() {
		return this instanceof Replayed;
	}

	record Dispatched(RunnerExecutionHandle handle, RunnerDispatchAck ack) implements RunnerDispatchResult {

		public Dispatched {
			Objects.requireNonNull(handle, "handle");
			Objects.requireNonNull(ack, "ack");
		}
	}

	record Replayed(RunnerExecutionHandle handle) implements RunnerDispatchResult {

		public Replayed {
			Objects.requireNonNull(handle, "handle");
		}
	}
}
