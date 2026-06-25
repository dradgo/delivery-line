package org.dradgo.application.clarification.spi;

/**
 * Application-owned write SPI over the V25 {@code spec_clarification_acknowledgements} side-store
 * (story 3e-2). Written at broker ingest of a spec result that carried structured acknowledgements.
 *
 * <p>The {@link #existsBySpecArtifactPublicIdAndQuestionId} pre-flight probe is the broker's dedup
 * guard against the {@code (spec_artifact_id, question_id)} UNIQUE: probing BEFORE insert means a
 * re-harvest of the same result never flushes a conflicting INSERT into the shared broker
 * transaction (3e-1's session-poison trap — a flushed conflict poisons the Hibernate session and
 * strands the completed run, and catching the translated error does NOT heal it).
 */
public interface SpecClarificationAcknowledgementWritePort {

  /** True when a row already exists for {@code (specArtifactPublicId, questionId)}. */
  boolean existsBySpecArtifactPublicIdAndQuestionId(String specArtifactPublicId, String questionId);

  /**
   * Insert one acknowledgement row. The caller MUST pre-flight {@link
   * #existsBySpecArtifactPublicIdAndQuestionId} and de-dup the batch by questionId first so this
   * never flushes a UNIQUE conflict.
   */
  void insert(String publicId, String specArtifactPublicId, String questionId, boolean addressed);
}
