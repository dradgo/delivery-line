package org.dradgo.application.artifact;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.FailureCategory;

public record ArtifactRecordSnapshot(
    String publicId,
    String workflowRunId,
    ArtifactType artifactType,
    int version,
    String parentArtifactId,
    DataClassification classification,
    String storageRef,
    String checksumAlgorithm,
    String checksumValue,
    FailureCategory failureCategory,
    String failureReason,
    ArtifactStatus status,
    OffsetDateTime archivedAt,
    boolean lineageRecovery) {
  /**
   * Convenience constructor that zeroes {@code failureCategory} and {@code failureReason}.
   *
   * <p><strong>Footgun:</strong> when used on a real read path it silently discards persisted
   * failure metadata. The persistence-mapper read path ({@link
   * org.dradgo.adapters.persistence.mapper.ArtifactEntityMapper}) must keep calling the canonical
   * 13-arg constructor.
   *
   * <p>Prefer {@link #withoutFailureMetadata} in test code so the omission of failure metadata is
   * named and intentional.
   *
   * @deprecated Will be removed once all in-tree callers migrate to {@link
   *     #withoutFailureMetadata}; new code must not use this constructor.
   */
  @Deprecated(forRemoval = true)
  public ArtifactRecordSnapshot(
      String publicId,
      String workflowRunId,
      ArtifactType artifactType,
      int version,
      String parentArtifactId,
      DataClassification classification,
      String storageRef,
      String checksumAlgorithm,
      String checksumValue,
      ArtifactStatus status,
      OffsetDateTime archivedAt) {
    this(
        publicId,
        workflowRunId,
        artifactType,
        version,
        parentArtifactId,
        classification,
        storageRef,
        checksumAlgorithm,
        checksumValue,
        null,
        null,
        status,
        archivedAt,
        false);
  }

  /**
   * Builds a snapshot whose failure metadata is intentionally absent.
   *
   * <p>Use only when the caller is provably outside any failure-bearing read path — tests that
   * exercise success or pending flows, fixtures that drive non-failed lineage seeds, etc. The
   * persistence-mapper read path must keep calling the canonical 14-arg constructor so failure
   * metadata stored on the entity is never silently discarded.
   */
  @Deprecated(forRemoval = false)
  public static ArtifactRecordSnapshot withoutFailureMetadata(
      String publicId,
      String workflowRunId,
      ArtifactType artifactType,
      int version,
      String parentArtifactId,
      DataClassification classification,
      String storageRef,
      String checksumAlgorithm,
      String checksumValue,
      ArtifactStatus status,
      OffsetDateTime archivedAt) {
    return new ArtifactRecordSnapshot(
        publicId,
        workflowRunId,
        artifactType,
        version,
        parentArtifactId,
        classification,
        storageRef,
        checksumAlgorithm,
        checksumValue,
        null,
        null,
        status,
        archivedAt,
        false);
  }
}
