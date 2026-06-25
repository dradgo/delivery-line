package org.dradgo.application.clarification.spi;

import java.util.List;
import org.dradgo.application.clarification.SpecClarificationAcknowledgement;

/**
 * Application-owned read SPI over the V25 {@code spec_clarification_acknowledgements} side-store
 * (story 3e-2). Backs {@code ClarificationLifecycleOrchestrator.sweepAfterSpecRebuild}, which reads
 * the structured acknowledgements for the just-available spec artifact (the sweep sees only the
 * artifact, not the runner result). Implementations MUST filter {@code archived_at IS NULL}.
 */
public interface SpecClarificationAcknowledgementReadPort {

  /**
   * All non-archived acknowledgements the spec runner emitted for the given spec artifact (by
   * public id). Returns an empty list when the rebuilt spec acknowledged nothing — every accepted
   * clarification then supersedes (not-addressed).
   */
  List<SpecClarificationAcknowledgement> findBySpecArtifactPublicId(String specArtifactPublicId);
}
