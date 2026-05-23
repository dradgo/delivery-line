package org.dradgo.application.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;

class SpecificationArtifactTest {

  private static final OffsetDateTime CREATED_AT =
      OffsetDateTime.of(2026, 5, 23, 10, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void fromSnapshotProjectsSpecRow() {
    ArtifactRecordSnapshot snapshot =
        specSnapshot("art_spec1234", "run_abcd1234", 2, "sha-256", "deadbeef");

    SpecificationArtifact projection = SpecificationArtifact.fromSnapshot(snapshot);

    assertEquals("art_spec1234", projection.id());
    assertEquals("run_abcd1234", projection.workflowRunId());
    assertEquals(2, projection.version());
    assertEquals("art_parent12", projection.parentArtifactId());
    assertEquals("spec.md", projection.payloadRef());
    assertEquals("sha-256:deadbeef", projection.checksum());
    assertEquals(ArtifactStatus.AVAILABLE, projection.status());
    assertEquals(DataClassification.SHAREABLE_REDACTED, projection.classification());
    assertEquals(CREATED_AT, projection.createdAt());
  }

  @Test
  void fromSnapshotPassesThroughNullChecksumWhenAlgorithmAndValueAbsent() {
    ArtifactRecordSnapshot snapshot = specSnapshot("art_spec5678", "run_abcd1234", 1, null, null);

    SpecificationArtifact projection = SpecificationArtifact.fromSnapshot(snapshot);

    assertNull(projection.checksum());
  }

  @Test
  void fromSnapshotRefusesNonSpecArtifactType() {
    ArtifactRecordSnapshot snapshot =
        new ArtifactRecordSnapshot(
            "art_plan1234",
            "run_abcd1234",
            ArtifactType.IMPLEMENTATION_PLAN,
            1,
            null,
            DataClassification.SHAREABLE_REDACTED,
            "implementation-plan.md",
            null,
            null,
            null,
            null,
            ArtifactStatus.AVAILABLE,
            null,
            false,
            CREATED_AT);

    DomainException error =
        assertThrows(DomainException.class, () -> SpecificationArtifact.fromSnapshot(snapshot));

    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
    assertEquals("art_plan1234", error.details().get("artifactId"));
    assertEquals("implementationPlan", error.details().get("actualArtifactType"));
    assertEquals("spec", error.details().get("expectedArtifactType"));
  }

  @Test
  void recordRefusesNullCoreFields() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SpecificationArtifact(
                null,
                "run_abcd1234",
                1,
                null,
                "spec.md",
                null,
                ArtifactStatus.AVAILABLE,
                DataClassification.SHAREABLE_REDACTED,
                CREATED_AT));
  }

  @Test
  void recordRefusesNonPositiveVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SpecificationArtifact(
                "art_spec1234",
                "run_abcd1234",
                0,
                null,
                "spec.md",
                null,
                ArtifactStatus.AVAILABLE,
                DataClassification.SHAREABLE_REDACTED,
                CREATED_AT));
  }

  @Test
  void fromSnapshotRejectsNullSnapshot() {
    assertNotNull(
        assertThrows(NullPointerException.class, () -> SpecificationArtifact.fromSnapshot(null)));
  }

  private static ArtifactRecordSnapshot specSnapshot(
      String publicId,
      String workflowRunId,
      int version,
      String checksumAlgorithm,
      String checksumValue) {
    return new ArtifactRecordSnapshot(
        publicId,
        workflowRunId,
        ArtifactType.SPEC,
        version,
        "art_parent12",
        DataClassification.SHAREABLE_REDACTED,
        "spec.md",
        checksumAlgorithm,
        checksumValue,
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        CREATED_AT);
  }
}
