package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, Long> {

	Optional<WorkflowRunEntity> findByPublicId(String publicId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select workflowRun from WorkflowRunEntity workflowRun where workflowRun.publicId = :publicId")
	Optional<WorkflowRunEntity> findByPublicIdForUpdate(@Param("publicId") String publicId);

	boolean existsByPublicId(String publicId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update WorkflowRunEntity workflowRun
		set workflowRun.currentState = :currentState,
			workflowRun.version = workflowRun.version + 1
		where workflowRun.publicId = :publicId
		  and workflowRun.version = :expectedVersion
		""")
	int updateCurrentState(
		@Param("publicId") String publicId,
		@Param("currentState") String currentState,
		@Param("expectedVersion") Long expectedVersion
	);
}
