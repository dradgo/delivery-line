package org.dradgo.adapters.persistence;

import java.util.Map;
import org.dradgo.application.artifact.spi.ArtifactRunnerExecutionPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ArtifactRunnerExecutionPersistenceAdapter implements ArtifactRunnerExecutionPort {

  private static final Logger log =
      LoggerFactory.getLogger(ArtifactRunnerExecutionPersistenceAdapter.class);

  private static final String STATUS_FIELD = "runner_executions.status";
  private static final String STATUS_QUERY =
      "select status from runner_executions where public_id = ?";

  private final JdbcTemplate jdbcTemplate;

  public ArtifactRunnerExecutionPersistenceAdapter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public boolean isTimedOut(String runnerExecutionId) {
    String rawStatus;
    try {
      rawStatus = jdbcTemplate.queryForObject(STATUS_QUERY, String.class, runnerExecutionId);
    } catch (EmptyResultDataAccessException missing) {
      log.warn(
          "isTimedOut rejected: runner execution row not found runnerExecutionId={}",
          runnerExecutionId);
      throw new DomainException(
          DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND,
          "Runner execution not found: " + runnerExecutionId,
          Map.of("runnerExecutionId", runnerExecutionId),
          missing);
    }
    RunnerExecutionStatus status = RunnerExecutionStatus.fromValue(rawStatus, STATUS_FIELD);
    boolean timedOut = status == RunnerExecutionStatus.TIMED_OUT;
    log.debug(
        "isTimedOut runnerExecutionId={} status={} timedOut={}",
        runnerExecutionId,
        status.value(),
        timedOut);
    return timedOut;
  }
}
