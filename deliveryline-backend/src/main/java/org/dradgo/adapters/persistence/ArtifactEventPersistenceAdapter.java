package org.dradgo.adapters.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.application.artifact.ArtifactEventRecord;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ArtifactEventPersistenceAdapter implements ArtifactEventPort {

	private static final Logger log = LoggerFactory.getLogger(ArtifactEventPersistenceAdapter.class);

	private final WorkflowEventWritePort workflowEventWritePort;

	public ArtifactEventPersistenceAdapter(WorkflowEventWritePort workflowEventWritePort) {
		this.workflowEventWritePort = workflowEventWritePort;
	}

	@Override
	public void append(ArtifactEventRecord eventRecord) {
		String publicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
		workflowEventWritePort.append(new WorkflowEventRecord(
			publicId,
			eventRecord.workflowRunId(),
			eventRecord.eventType(),
			null,
			null,
			eventRecord.actorIdentity(),
			eventRecord.actorType(),
			eventRecord.reason(),
			eventRecord.failureCategory(),
			false,
			eventRecord.createdAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : eventRecord.createdAt(),
			eventRecord.details()));
		log.info("artifact event appended eventId={} workflowRunId={} eventType={} actorIdentity={} failureCategory={}",
			publicId, eventRecord.workflowRunId(), eventRecord.eventType().value(),
			eventRecord.actorIdentity(),
			eventRecord.failureCategory() == null ? null : eventRecord.failureCategory().value());
	}
}
