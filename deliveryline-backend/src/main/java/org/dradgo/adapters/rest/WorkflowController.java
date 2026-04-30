package org.dradgo.adapters.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

	private final WorkflowCommandService workflowCommandService;

	public WorkflowController(WorkflowCommandService workflowCommandService) {
		this.workflowCommandService = workflowCommandService;
	}

	@PostMapping(
		value = "/submit-workflow",
		consumes = MediaType.APPLICATION_JSON_VALUE,
		produces = MediaType.APPLICATION_JSON_VALUE)
	public SubmitWorkflowResponse submit(
		@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 256) String idempotencyKey,
		@Valid @RequestBody SubmitWorkflowRequest request
	) {
		return SubmitWorkflowResponse.from(workflowCommandService.submit(new SubmitWorkflowCommand(
			request.actorIdentity(),
			request.actorType(),
			idempotencyKey,
			request.correlationId(),
			request.linearTicketReference())));
	}

	@PostMapping(
		value = "/{workflowRunId}/approve-spec",
		consumes = MediaType.APPLICATION_JSON_VALUE,
		produces = MediaType.APPLICATION_JSON_VALUE)
	public WorkflowStateChangeResponse approveSpec(
		@PathVariable String workflowRunId,
		@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 256) String idempotencyKey,
		@Valid @RequestBody ApproveSpecRequest request
	) {
		return WorkflowStateChangeResponse.from(workflowCommandService.approveSpec(new ApproveSpecCommand(
			workflowRunId,
			request.artifactId(),
			request.artifactVersion(),
			request.contextVersion(),
			request.actorIdentity(),
			request.actorType(),
			idempotencyKey,
			request.correlationId())));
	}

	@PostMapping(
		value = "/{workflowRunId}/reject-spec",
		consumes = MediaType.APPLICATION_JSON_VALUE,
		produces = MediaType.APPLICATION_JSON_VALUE)
	public WorkflowStateChangeResponse rejectSpec(
		@PathVariable String workflowRunId,
		@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 256) String idempotencyKey,
		@Valid @RequestBody RejectSpecRequest request
	) {
		return WorkflowStateChangeResponse.from(workflowCommandService.rejectSpec(new RejectSpecCommand(
			workflowRunId,
			request.artifactId(),
			request.artifactVersion(),
			request.contextVersion(),
			request.actorIdentity(),
			request.actorType(),
			idempotencyKey,
			request.correlationId(),
			request.reasonText())));
	}

	@PostMapping(
		value = "/{workflowRunId}/retry-workflow",
		consumes = MediaType.APPLICATION_JSON_VALUE,
		produces = MediaType.APPLICATION_JSON_VALUE)
	public WorkflowStateChangeResponse retry(
		@PathVariable String workflowRunId,
		@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 256) String idempotencyKey,
		@Valid @RequestBody RetryWorkflowRequest request
	) {
		return WorkflowStateChangeResponse.from(workflowCommandService.retryWorkflow(new RetryWorkflowCommand(
			workflowRunId,
			request.actorIdentity(),
			request.actorType(),
			idempotencyKey,
			request.correlationId(),
			request.reasonText())));
	}

	@PostMapping(
		value = "/{workflowRunId}/takeover-workflow",
		consumes = MediaType.APPLICATION_JSON_VALUE,
		produces = MediaType.APPLICATION_JSON_VALUE)
	public WorkflowStateChangeResponse takeover(
		@PathVariable String workflowRunId,
		@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 256) String idempotencyKey,
		@Valid @RequestBody TakeoverWorkflowRequest request
	) {
		return WorkflowStateChangeResponse.from(workflowCommandService.takeoverWorkflow(new TakeoverWorkflowCommand(
			workflowRunId,
			request.actorIdentity(),
			request.actorType(),
			idempotencyKey,
			request.correlationId(),
			request.reasonText())));
	}
}
