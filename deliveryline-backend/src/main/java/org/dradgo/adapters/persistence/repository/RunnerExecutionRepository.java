package org.dradgo.adapters.persistence.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.dradgo.adapters.persistence.entity.RunnerExecutionEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunnerExecutionRepository extends JpaRepository<RunnerExecutionEntity, Long> {

	Optional<RunnerExecutionEntity> findByPublicId(String publicId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select runnerExecution
		from RunnerExecutionEntity runnerExecution
		where runnerExecution.publicId = :publicId
		""")
	Optional<RunnerExecutionEntity> findByPublicIdForUpdate(@Param("publicId") String publicId);

	Optional<RunnerExecutionEntity> findFirstByWorkflowRunPublicIdAndStageOrderByContextBundleVersionDesc(
		String workflowRunPublicId,
		String stage
	);

	List<RunnerExecutionEntity> findByWorkflowRunPublicIdAndStatusIn(
		String workflowRunPublicId,
		List<String> statuses
	);

	@Query("""
		select runnerExecution
		from RunnerExecutionEntity runnerExecution
		where runnerExecution.status in :statuses
		  and runnerExecution.timeoutAt < :cutoff
		order by runnerExecution.timeoutAt asc
		""")
	List<RunnerExecutionEntity> findStaleByStatusInAndTimeoutAtBefore(
		@Param("statuses") List<String> statuses,
		@Param("cutoff") OffsetDateTime cutoff,
		Limit limit
	);

	List<RunnerExecutionEntity> findByStatusInOrderByCreatedAtAsc(List<String> statuses, Limit limit);
}
