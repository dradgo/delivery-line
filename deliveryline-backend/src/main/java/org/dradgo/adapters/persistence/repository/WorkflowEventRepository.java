package org.dradgo.adapters.persistence.repository;

import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowEventRepository extends JpaRepository<WorkflowEventEntity, Long> {
}
