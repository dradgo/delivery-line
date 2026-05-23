package org.dradgo.application.artifact;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;

/**
 * Typed projection over the {@code artifacts} table for rows whose {@code artifact_type = 'spec'}
 * (story 2.8 AC1). Lives in the application layer; carries only domain types and never touches
 * persistence entities.
 *
 * <p>Construct via {@link #fromSnapshot(ArtifactRecordSnapshot)} so the {@code SPEC} invariant is
 * enforced at the projection boundary — passing a non-SPEC snapshot throws {@link DomainException}
 * with {@link DomainErrorCode#INTERNAL_ERROR} rather than silently coercing a foreign artifact type
 * into a spec view.
 *
 * @param id artifact public id ({@code art_…})
 * @param workflowRunId owning workflow run public id ({@code run_…})
 * @param version positive monotonic version within the {@code (workflowRunId, SPEC)} lineage
 * @param parentArtifactId predecessor spec version's public id, or {@code null} when this is v1
 * @param payloadRef relative storage reference (typically {@code spec.md})
 * @param checksum {@code "{algorithm}:{value}"} when {@link #status()} is {@code AVAILABLE},
 *     otherwise {@code null}
 * @param status current artifact lifecycle status
 * @param classification effective data classification (defaults to {@link
 *     DataClassification#SHAREABLE_REDACTED} per registry)
 * @param createdAt the spec row's persistence-side creation timestamp ({@code created_at} on the
 *     {@code artifacts} table). Required at the projection boundary — the persistence mapper now
 *     populates {@code created_at} from the entity, so a {@code null} reaching this constructor
 *     indicates either a regression in the mapper or a test fixture using a legacy pre-{@code
 *     createdAt} {@link ArtifactRecordSnapshot} overload. Either way the projection refuses to
 *     silently propagate the {@code null}.
 */
public record SpecificationArtifact(
    String id,
    String workflowRunId,
    int version,
    String parentArtifactId,
    String payloadRef,
    String checksum,
    ArtifactStatus status,
    DataClassification classification,
    OffsetDateTime createdAt) {

  public SpecificationArtifact {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(classification, "classification");
    Objects.requireNonNull(createdAt, "createdAt");
    if (version <= 0) {
      throw new IllegalArgumentException("version must be positive");
    }
  }

  /**
   * Projects the supplied {@code ArtifactRecordSnapshot} into a {@code SpecificationArtifact}.
   *
   * @throws DomainException with {@link DomainErrorCode#INTERNAL_ERROR} when the snapshot's
   *     artifact type is anything other than {@link ArtifactType#SPEC} — the projection refuses to
   *     alias non-spec rows so callers cannot accidentally treat a plan or PR-output row as a spec.
   */
  public static SpecificationArtifact fromSnapshot(ArtifactRecordSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (snapshot.artifactType() != ArtifactType.SPEC) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("artifactId", snapshot.publicId());
      details.put("actualArtifactType", snapshot.artifactType().value());
      details.put("expectedArtifactType", ArtifactType.SPEC.value());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "SpecificationArtifact projection refused non-spec snapshot",
          details);
    }
    String checksumComposite = null;
    if (snapshot.checksumAlgorithm() != null && snapshot.checksumValue() != null) {
      checksumComposite = snapshot.checksumAlgorithm() + ":" + snapshot.checksumValue();
    }
    return new SpecificationArtifact(
        snapshot.publicId(),
        snapshot.workflowRunId(),
        snapshot.version(),
        snapshot.parentArtifactId(),
        snapshot.storageRef(),
        checksumComposite,
        snapshot.status(),
        snapshot.classification(),
        snapshot.createdAt());
  }
}
