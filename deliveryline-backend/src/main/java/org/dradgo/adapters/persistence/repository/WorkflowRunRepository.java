package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, Long> {

	Optional<WorkflowRunEntity> findByPublicId(String publicId);
}
