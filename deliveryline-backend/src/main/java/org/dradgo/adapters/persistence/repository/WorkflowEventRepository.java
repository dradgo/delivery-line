package org.dradgo.adapters.persistence.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowEventRepository extends JpaRepository<WorkflowEventEntity, Long> {

	@Query("""
		select event from WorkflowEventEntity event
		where event.workflowRun.publicId = :publicId
		  and event.archivedAt is null
		order by event.createdAt desc, event.id desc
		""")
	List<WorkflowEventEntity> findLatestByWorkflowRunPublicId(
		@Param("publicId") String publicId,
		Pageable pageable);

	@Query("""
		select event from WorkflowEventEntity event
		where event.workflowRun.publicId = :publicId
		  and event.archivedAt is null
		order by event.createdAt asc, event.id asc
		""")
	List<WorkflowEventEntity> findByWorkflowRunPublicIdOrderByCreatedAtAscIdAsc(
		@Param("publicId") String publicId,
		Pageable pageable);

	@Query("""
		select event from WorkflowEventEntity event
		where event.workflowRun.publicId = :publicId
		  and event.archivedAt is null
		  and event.createdAt >= :sinceInclusive
		order by event.createdAt asc, event.id asc
		""")
	List<WorkflowEventEntity> findByWorkflowRunPublicIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
		@Param("publicId") String publicId,
		@Param("sinceInclusive") OffsetDateTime sinceInclusive,
		Pageable pageable);

	default Optional<WorkflowEventEntity> findFirstLatestByWorkflowRunPublicId(String publicId) {
		List<WorkflowEventEntity> top = findLatestByWorkflowRunPublicId(
			publicId,
			org.springframework.data.domain.PageRequest.of(0, 1));
		return top.isEmpty() ? Optional.empty() : Optional.of(top.get(0));
	}
}
