package org.dradgo.application.runner;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.registry.FailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin application service over {@link RunnerExecutionRecordPort}. The state-machine guard
 * lives in the persistence adapter (mirroring 1.12 C4 / R4); this service is the named seam
 * call sites use rather than touching the port directly.
 */
@Service
public class RunnerExecutionService {

	private static final Logger log = LoggerFactory.getLogger(RunnerExecutionService.class);

	private final RunnerExecutionRecordPort recordPort;
	private final Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public RunnerExecutionService(RunnerExecutionRecordPort recordPort) {
		this(recordPort, Clock.systemUTC());
	}

	RunnerExecutionService(RunnerExecutionRecordPort recordPort, Clock clock) {
		this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public RunnerExecutionSnapshot markRunning(String runnerExecutionId) {
		OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
		RunnerExecutionSnapshot updated = recordPort.transitionToRunning(runnerExecutionId, now);
		log.info("markRunning runnerExecutionId={} status={}", runnerExecutionId, updated.status().value());
		return updated;
	}

	public RunnerExecutionSnapshot touchActivity(String runnerExecutionId, Duration staleTimeoutWindow) {
		OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
		return touchActivity(runnerExecutionId, now, staleTimeoutWindow);
	}

	public RunnerExecutionSnapshot touchActivity(
		String runnerExecutionId,
		OffsetDateTime activityAt,
		Duration staleTimeoutWindow
	) {
		Objects.requireNonNull(activityAt, "activityAt");
		Objects.requireNonNull(staleTimeoutWindow, "staleTimeoutWindow");
		return recordPort.touchActivity(runnerExecutionId, activityAt, staleTimeoutWindow);
	}

	public RunnerExecutionSnapshot recordCompleted(String runnerExecutionId) {
		OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
		RunnerExecutionSnapshot updated = recordPort.markCompleted(runnerExecutionId, now);
		log.info("recordCompleted runnerExecutionId={} status={}", runnerExecutionId, updated.status().value());
		return updated;
	}

	public RunnerExecutionSnapshot recordFailed(String runnerExecutionId, FailureCategory failureCategory) {
		Objects.requireNonNull(failureCategory, "failureCategory");
		OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
		RunnerExecutionSnapshot updated = recordPort.markFailed(runnerExecutionId, failureCategory, now);
		log.warn("recordFailed runnerExecutionId={} status={} failureCategory={}",
			runnerExecutionId, updated.status().value(), failureCategory.value());
		return updated;
	}

	public RunnerExecutionSnapshot recordTimedOut(String runnerExecutionId) {
		OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
		RunnerExecutionSnapshot updated = recordPort.markTimedOut(runnerExecutionId, now);
		log.warn("recordTimedOut runnerExecutionId={} status={}", runnerExecutionId, updated.status().value());
		return updated;
	}

	public RunnerExecutionSnapshot recordOrphaned(String runnerExecutionId) {
		OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
		RunnerExecutionSnapshot updated = recordPort.markOrphaned(runnerExecutionId, now);
		log.warn("recordOrphaned runnerExecutionId={} status={}", runnerExecutionId, updated.status().value());
		return updated;
	}
}
