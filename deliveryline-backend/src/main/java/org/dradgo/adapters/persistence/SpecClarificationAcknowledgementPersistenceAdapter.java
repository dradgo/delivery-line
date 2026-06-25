package org.dradgo.adapters.persistence;

import java.util.List;
import org.dradgo.adapters.persistence.entity.SpecClarificationAcknowledgementEntity;
import org.dradgo.adapters.persistence.repository.SpecClarificationAcknowledgementRepository;
import org.dradgo.application.clarification.SpecClarificationAcknowledgement;
import org.dradgo.application.clarification.spi.SpecClarificationAcknowledgementReadPort;
import org.dradgo.application.clarification.spi.SpecClarificationAcknowledgementWritePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence adapter for the V25 {@code spec_clarification_acknowledgements} side-store (story
 * 3e-2). Implements both the read SPI (consumed by the clarification sweep) and the write SPI
 * (consumed at broker ingest). The write join the caller's transaction ({@code
 * Propagation.REQUIRED}) so the acknowledgement rows commit/roll back with the spec ingest; the
 * broker pre-flights the existence probe so {@code insert} never flushes a UNIQUE conflict.
 */
@Component
public class SpecClarificationAcknowledgementPersistenceAdapter
    implements SpecClarificationAcknowledgementReadPort, SpecClarificationAcknowledgementWritePort {

  private final SpecClarificationAcknowledgementRepository repository;

  public SpecClarificationAcknowledgementPersistenceAdapter(
      SpecClarificationAcknowledgementRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<SpecClarificationAcknowledgement> findBySpecArtifactPublicId(
      String specArtifactPublicId) {
    return repository.findBySpecArtifactIdAndArchivedAtIsNull(specArtifactPublicId).stream()
        .map(
            entity ->
                new SpecClarificationAcknowledgement(
                    entity.getSpecArtifactId(), entity.getQuestionId(), entity.isAddressed()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsBySpecArtifactPublicIdAndQuestionId(
      String specArtifactPublicId, String questionId) {
    return repository.existsBySpecArtifactIdAndQuestionId(specArtifactPublicId, questionId);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void insert(
      String publicId, String specArtifactPublicId, String questionId, boolean addressed) {
    SpecClarificationAcknowledgementEntity entity = new SpecClarificationAcknowledgementEntity();
    entity.setPublicId(publicId);
    entity.setSpecArtifactId(specArtifactPublicId);
    entity.setQuestionId(questionId);
    entity.setAddressed(addressed);
    repository.save(entity);
  }
}
