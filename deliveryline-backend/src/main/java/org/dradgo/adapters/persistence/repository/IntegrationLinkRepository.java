package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.dradgo.adapters.persistence.entity.IntegrationLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationLinkRepository extends JpaRepository<IntegrationLinkEntity, Long> {

	Optional<IntegrationLinkEntity> findByPublicId(String publicId);

	@Query("""
		select integrationLink
		from IntegrationLinkEntity integrationLink
		where integrationLink.integrationType = :integrationType
		  and integrationLink.externalRef = :externalRef
		  and integrationLink.archivedAt is null
		  and integrationLink.syncStatus <> 'superseded'
		""")
	Optional<IntegrationLinkEntity> findActiveByTypeAndExternalRef(
		@Param("integrationType") String integrationType,
		@Param("externalRef") String externalRef
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select integrationLink
		from IntegrationLinkEntity integrationLink
		where integrationLink.integrationType = :integrationType
		  and integrationLink.externalRef = :externalRef
		  and integrationLink.archivedAt is null
		  and integrationLink.syncStatus <> 'superseded'
		""")
	Optional<IntegrationLinkEntity> findActiveByTypeAndExternalRefForUpdate(
		@Param("integrationType") String integrationType,
		@Param("externalRef") String externalRef
	);

	@Query("""
		select integrationLink
		from IntegrationLinkEntity integrationLink
		where integrationLink.workflowRun.publicId = :workflowRunPublicId
		  and integrationLink.archivedAt is null
		  and integrationLink.syncStatus <> 'superseded'
		order by integrationLink.integrationType asc, integrationLink.createdAt asc
		""")
	Optional<IntegrationLinkEntity> findFirstActiveByWorkflowRunPublicId(
		@Param("workflowRunPublicId") String workflowRunPublicId
	);
}
