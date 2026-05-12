package org.dradgo.application.runner.spi;

import java.util.Optional;
import org.dradgo.application.runner.RunnerDispatchAck;
import org.dradgo.application.runner.RunnerDispatchRequest;
import org.dradgo.application.runner.RunnerPollStatus;

/**
 * Application-owned runner lifecycle port. Implemented by adapters under {@code adapters.runner}
 * (mock in Epic 1, Docker in Epic 3). Docker types (process handles, container ids, log streams)
 * must stay behind the adapter implementation — none of them leak through this surface.
 */
public interface RunnerAdapter {

	/**
	 * Non-blocking dispatch. Returns an ack carrying the adapter-side handle (mock scenario id,
	 * Docker container id, etc.) for later correlation. Implementations must be idempotent on
	 * {@code request.runnerExecutionId()}: re-dispatch with the same id either no-ops or returns
	 * the prior ack.
	 */
	RunnerDispatchAck dispatch(RunnerDispatchRequest request);

	RunnerPollStatus poll(String runnerExecutionId);

	/**
	 * Returns the runner result bytes if the result file exists. Caller validates the bytes via
	 * {@code RunnerContractValidator} — this port never validates.
	 */
	Optional<byte[]> tryReadResult(String runnerExecutionId);

	/**
	 * Best-effort cancellation. Idempotent: cancelling a completed or unknown id must not throw.
	 */
	void cancel(String runnerExecutionId);
}
