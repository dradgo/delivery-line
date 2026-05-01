package org.dradgo.application.workflow;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.application.workflow.commands.WorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowCommandService {

	private final WorkflowRunRepository workflowRunRepository;
	private final WorkflowEventRepository workflowEventRepository;
	private final WorkflowTransitionService workflowTransitionService;
	private final Validator validator;

	public WorkflowCommandService(
		WorkflowRunRepository workflowRunRepository,
		WorkflowEventRepository workflowEventRepository,
		WorkflowTransitionService workflowTransitionService,
		Validator validator
	) {
		this.workflowRunRepository = workflowRunRepository;
		this.workflowEventRepository = workflowEventRepository;
		this.workflowTransitionService = workflowTransitionService;
		this.validator = validator;
	}

	@Transactional
	public SubmitWorkflowResult submit(SubmitWorkflowCommand command) {
		// Initial creation has no prior state, so submit cannot route through
		// WorkflowTransitionService.transition(prior -> target). It is the documented
		// exception to the "do not bypass WorkflowTransitionService" scope-discipline rule.
		validate(command);

		WorkflowRunEntity workflowRun = new WorkflowRunEntity();
		workflowRun.setPublicId(PublicIdPrefixes.WORKFLOW_RUN.next());
		workflowRun.setCurrentState(WorkflowState.INBOX);
		workflowRun = workflowRunRepository.saveAndFlush(workflowRun);

		WorkflowEventEntity event = new WorkflowEventEntity();
		event.setPublicId(PublicIdPrefixes.WORKFLOW_EVENT.next());
		event.setWorkflowRun(workflowRun);
		event.setEventType(WorkflowEventType.WORKFLOW_STATE_CHANGED);
		event.setPriorState(null);
		event.setResultingState(WorkflowState.INBOX);
		event.setActorIdentity(command.actorIdentity());
		event.setActorType(command.actorType());
		event.setReason("workflow submitted");
		event.setInterventionMarker(false);
		event.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
		event.getDetails().putAll(baseDetails(command));
		event.getDetails().put("linearTicketReference", command.linearTicketReference());
		workflowEventRepository.saveAndFlush(event);

		return new SubmitWorkflowResult(
			workflowRun.getPublicId(),
			WorkflowState.INBOX,
			normalizeOptional(command.correlationId()));
	}

	@Transactional
	public WorkflowStateChangeResult approveSpec(ApproveSpecCommand command) {
		validate(command);
		transition(
			command.workflowRunId(),
			WorkflowState.EXECUTING,
			command,
			"approve specification",
			Map.of(
				"artifactId", command.artifactId(),
				"artifactVersion", command.artifactVersion(),
				"contextVersion", command.contextVersion()));
		return new WorkflowStateChangeResult(
			command.workflowRunId(),
			WorkflowState.EXECUTING,
			normalizeOptional(command.correlationId()));
	}

	@Transactional
	public WorkflowStateChangeResult rejectSpec(RejectSpecCommand command) {
		validate(command);
		transition(
			command.workflowRunId(),
			WorkflowState.INVESTIGATING,
			command,
			command.reasonText(),
			Map.of(
				"artifactId", command.artifactId(),
				"artifactVersion", command.artifactVersion(),
				"contextVersion", command.contextVersion()));
		return new WorkflowStateChangeResult(
			command.workflowRunId(),
			WorkflowState.INVESTIGATING,
			normalizeOptional(command.correlationId()));
	}

	@Transactional
	public WorkflowStateChangeResult retryWorkflow(RetryWorkflowCommand command) {
		validate(command);
		transition(
			command.workflowRunId(),
			WorkflowState.EXECUTING,
			command,
			fallbackReason(command.reasonText(), "retry workflow"),
			Map.of());
		return new WorkflowStateChangeResult(
			command.workflowRunId(),
			WorkflowState.EXECUTING,
			normalizeOptional(command.correlationId()));
	}

	@Transactional
	public WorkflowStateChangeResult takeoverWorkflow(TakeoverWorkflowCommand command) {
		validate(command);
		transition(
			command.workflowRunId(),
			WorkflowState.TAKEN_OVER,
			command,
			fallbackReason(command.reasonText(), "take over workflow"),
			Map.of());
		return new WorkflowStateChangeResult(
			command.workflowRunId(),
			WorkflowState.TAKEN_OVER,
			normalizeOptional(command.correlationId()));
	}

	private void transition(
		String workflowRunId,
		WorkflowState targetState,
		WorkflowCommand command,
		String reason,
		Map<String, Object> extraDetails
	) {
		workflowTransitionService.transition(
			workflowRunId,
			targetState,
			new TransitionActor(command.actorIdentity(), command.actorType()),
			reason,
			command.idempotencyKey(),
			commandDetails(command, extraDetails));
	}

	private void validate(WorkflowCommand command) {
		Set<ConstraintViolation<WorkflowCommand>> violations = validator.validate(command);
		if (violations.isEmpty()) {
			return;
		}

		List<Map<String, Object>> fieldErrors = new ArrayList<>();
		violations.stream()
			.sorted(
				Comparator.comparing((ConstraintViolation<WorkflowCommand> violation) -> violation.getPropertyPath().toString())
					.thenComparing(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()))
			.forEach(violation -> {
				Map<String, Object> fieldError = new LinkedHashMap<>();
				fieldError.put("field", violation.getPropertyPath().toString());
				fieldError.put("code", violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName());
				fieldError.put("rejectedValue", violation.getInvalidValue());
				fieldError.put("message", violation.getMessage());
				fieldErrors.add(fieldError);
			});

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("commandType", command.commandType());
		details.put("fieldErrors", fieldErrors);
		throw new DomainException(
			DomainErrorCode.INVALID_COMMAND_PAYLOAD,
			"Invalid command payload for " + command.commandType(),
			details);
	}

	private Map<String, Object> baseDetails(WorkflowCommand command) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("commandType", command.commandType());
		details.put("idempotencyKey", command.idempotencyKey());
		String correlationId = normalizeOptional(command.correlationId());
		if (correlationId != null) {
			details.put("correlationId", correlationId);
		}
		return details;
	}

	private Map<String, Object> commandDetails(WorkflowCommand command, Map<String, Object> extraDetails) {
		// Canonical envelope keys (commandType, idempotencyKey, correlationId) win over
		// caller-supplied extraDetails so a future caller cannot accidentally clobber them.
		Map<String, Object> details = new LinkedHashMap<>(extraDetails);
		details.putAll(baseDetails(command));
		return details;
	}

	private String normalizeOptional(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	// Retry/Takeover commands accept an optional reasonText; when omitted we substitute a
	// stable system-supplied fallback so the audit trail always carries a non-empty reason.
	// This is intentional: requiring reasonText on retry/takeover would be a UX regression
	// for the operator-recovery happy path. RejectSpecCommand keeps @NotBlank because spec
	// rejection is an explicit operator decision that should always carry justification.
	private String fallbackReason(String reason, String fallback) {
		String normalized = normalizeOptional(reason);
		return normalized == null ? fallback : normalized;
	}
}
