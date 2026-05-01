package org.dradgo.adapters.cli;

import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.shell.core.command.ExitStatus;
import org.springframework.shell.core.command.exit.ExitStatusExceptionMapper;
import org.springframework.stereotype.Component;

@Component(WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
public class WorkflowCliExitStatusExceptionMapper implements ExitStatusExceptionMapper {

	public static final String BEAN_NAME = "workflowCliExitStatusExceptionMapper";

	private static final Logger LOG = LoggerFactory.getLogger(WorkflowCliExitStatusExceptionMapper.class);
	private static final String GENERIC_INTERNAL_ERROR_DETAIL = "An unexpected internal error occurred.";

	@Override
	public ExitStatus apply(Exception exception) {
		if (exception instanceof DomainException domainException) {
			return new ExitStatus(exitCodeFor(domainException.errorCode()), format(domainException.errorCode(), domainException.getMessage()));
		}
		LOG.error("Unexpected exception escaped CLI command; surfacing INTERNAL_ERROR", exception);
		return new ExitStatus(401, format(DomainErrorCode.INTERNAL_ERROR, GENERIC_INTERNAL_ERROR_DETAIL));
	}

	private int exitCodeFor(DomainErrorCode errorCode) {
		return switch (errorCode) {
			case IDEMPOTENCY_KEY_CONFLICT, CONCURRENT_TRANSITION_CONFLICT, APPROVAL_VERSION_MISMATCH -> 201;
			case RUNNER_TIMEOUT, RUNNER_CONTRACT_VIOLATION, ARTIFACT_PAYLOAD_UNAVAILABLE -> 301;
			case DOCTOR_POSTGRES_UNREACHABLE, DOCTOR_FLYWAY_FAILED, DOCTOR_REST_BIND_UNAVAILABLE, DOCTOR_DOCKER_MISSING,
					DOCTOR_CONFIG_PERMISSIONS_UNSAFE, DOCTOR_UNSUPPORTED_ENVIRONMENT, DOCTOR_ARTIFACT_DIR_UNWRITABLE,
					INTERNAL_ERROR -> 401;
			default -> 101;
		};
	}

	private String format(DomainErrorCode errorCode, String detail) {
		return "[" + errorCode.value() + "] " + detail;
	}
}
