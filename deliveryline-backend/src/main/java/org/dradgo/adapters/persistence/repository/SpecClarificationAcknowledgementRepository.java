package org.dradgo.adapters.persistence.repository;

import java.util.List;
import org.dradgo.adapters.persistence.entity.SpecClarificationAcknowledgementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the V25 {@code spec_clarification_acknowledgements} side-store (story
 * 3e-2). Reads enforce {@code archived_at IS NULL}; the {@code existsBy...} probe is the broker's
 * pre-flight dedup guard against the (spec_artifact_id, question_id) UNIQUE so a re-harvest never
 * flushes a conflicting insert into the shared broker transaction.
 */
public interface SpecClarificationAcknowledgementRepository
    extends JpaRepository<SpecClarificationAcknowledgementEntity, Long> {

  List<SpecClarificationAcknowledgementEntity> findBySpecArtifactIdAndArchivedAtIsNull(
      String specArtifactId);

  boolean existsBySpecArtifactIdAndQuestionId(String specArtifactId, String questionId);
}
