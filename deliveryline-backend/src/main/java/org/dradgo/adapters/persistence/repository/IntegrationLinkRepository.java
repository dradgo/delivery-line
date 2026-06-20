package org.dradgo.adapters.persistence.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.IntegrationLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationLinkRepository extends JpaRepository<IntegrationLinkEntity, Long> {

  // join fetch the workflowRun: IntegrationLinkEntityMapper.toDomain dereferences
  // workflowRun.publicId, and these reads are called from non-OSIV, non-@Transactional callers
  // (the RunnerWorkerPool thread) where a lazy proxy would otherwise throw
  // LazyInitializationException once the per-query session closes.
  @Query(
      """
		select integrationLink
		from IntegrationLinkEntity integrationLink
		  join fetch integrationLink.workflowRun
		where integrationLink.publicId = :publicId
		""")
  Optional<IntegrationLinkEntity> findByPublicId(@Param("publicId") String publicId);

  @Query(
      """
		select integrationLink
		from IntegrationLinkEntity integrationLink
		  join fetch integrationLink.workflowRun
		where integrationLink.integrationType = :integrationType
		  and integrationLink.externalRef = :externalRef
		  and integrationLink.archivedAt is null
		  and integrationLink.syncStatus <> 'superseded'
		""")
  Optional<IntegrationLinkEntity> findActiveByTypeAndExternalRef(
      @Param("integrationType") String integrationType, @Param("externalRef") String externalRef);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
		select integrationLink
		from IntegrationLinkEntity integrationLink
		where integrationLink.integrationType = :integrationType
		  and integrationLink.externalRef = :externalRef
		  and integrationLink.archivedAt is null
		  and integrationLink.syncStatus <> 'superseded'
		""")
  Optional<IntegrationLinkEntity> findActiveByTypeAndExternalRefForUpdate(
      @Param("integrationType") String integrationType, @Param("externalRef") String externalRef);

  // A run can carry MORE THAN ONE active link (a `linear` ticket link + a `github_pr` link once the
  // pr-output stage pushes a PR). The port contract is "return the LINEAR variant by convention",
  // so
  // order linear first (CASE) and return a List the adapter takes the first of — never an Optional
  // from a no-LIMIT query, which throws NonUniqueResultException on the 2-link run (the 500 that
  // hit
  // `listRuns`). Mirrors the List-take-first pattern of findActiveByTypeAndWorkflowRunPublicId.
  @Query(
      """
		select integrationLink
		from IntegrationLinkEntity integrationLink
		  join fetch integrationLink.workflowRun workflowRun
		where workflowRun.publicId = :workflowRunPublicId
		  and integrationLink.archivedAt is null
		  and integrationLink.syncStatus <> 'superseded'
		order by case when integrationLink.integrationType = 'linear' then 0 else 1 end asc,
		         integrationLink.createdAt asc
		""")
  java.util.List<IntegrationLinkEntity> findActiveByWorkflowRunPublicIdLinearFirst(
      @Param("workflowRunPublicId") String workflowRunPublicId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
		select integrationLink
		from IntegrationLinkEntity integrationLink
		where integrationLink.integrationType = :integrationType
		  and integrationLink.workflowRun.publicId = :workflowRunPublicId
		  and integrationLink.archivedAt is null
		  and integrationLink.syncStatus <> 'superseded'
		order by integrationLink.createdAt asc
		""")
  Optional<IntegrationLinkEntity> findActiveByTypeAndWorkflowRunForUpdate(
      @Param("integrationType") String integrationType,
      @Param("workflowRunPublicId") String workflowRunPublicId);

  // Story 3b-5 — NON-locking twin of findActiveByTypeAndWorkflowRunForUpdate, typed to a single
  // integration type (github_pr) for the artifact-read projection: a read path must not take the
  // PESSIMISTIC_WRITE lock the enrich/sync writers hold. Returns a List (adapter takes the first by
  // createdAt) so a transient supersede race never surfaces as NonUniqueResultException.
  @Query(
      """
		select integrationLink
		from IntegrationLinkEntity integrationLink
		where integrationLink.integrationType = :integrationType
		  and integrationLink.workflowRun.publicId = :workflowRunPublicId
		  and integrationLink.archivedAt is null
		  and integrationLink.syncStatus <> 'superseded'
		order by integrationLink.createdAt asc
		""")
  java.util.List<IntegrationLinkEntity> findActiveByTypeAndWorkflowRunPublicId(
      @Param("integrationType") String integrationType,
      @Param("workflowRunPublicId") String workflowRunPublicId);

  @Query(
      """
		select max(integrationLink.lastSyncAt)
		from IntegrationLinkEntity integrationLink
		where integrationLink.integrationType = :integrationType
		  and integrationLink.archivedAt is null
		  and integrationLink.syncStatus <> 'superseded'
		""")
  Optional<OffsetDateTime> findMaxLastSyncAtForType(
      @Param("integrationType") String integrationType);

  @Modifying
  @Query(
      """
		update IntegrationLinkEntity integrationLink
		   set integrationLink.lastSyncAt = :lastSyncAt
		 where integrationLink.integrationType = :integrationType
		   and integrationLink.externalRef = :externalRef
		   and integrationLink.archivedAt is null
		   and integrationLink.syncStatus <> 'superseded'
		""")
  int touchLastSyncAtByTypeAndExternalRef(
      @Param("integrationType") String integrationType,
      @Param("externalRef") String externalRef,
      @Param("lastSyncAt") OffsetDateTime lastSyncAt);
}
