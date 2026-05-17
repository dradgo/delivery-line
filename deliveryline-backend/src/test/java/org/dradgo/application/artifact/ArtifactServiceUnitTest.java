package org.dradgo.application.artifact;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.junit.jupiter.api.Test;

class ArtifactServiceUnitTest {

  @Test
  void approvalEligibilityRequiresAvailableChecksumReadablePayloadAndNoArchiveMarker() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
    ArtifactService service = new ArtifactService(artifactRecordPort, payloadStore);
    byte[] payload = "spec body".getBytes(StandardCharsets.UTF_8);
    String checksum = sha256Hex(payload);
    ArtifactRecordSnapshot artifact =
        artifact(
            "art_eligible1234",
            ArtifactStatus.AVAILABLE,
            "artifacts/run_ready1234/art_eligible1234/v1/spec.md",
            "SHA-256",
            checksum,
            null);

    when(artifactRecordPort.findByPublicId("art_eligible1234")).thenReturn(Optional.of(artifact));
    when(payloadStore.readBytes("artifacts/run_ready1234/art_eligible1234/v1/spec.md"))
        .thenReturn(Optional.of(payload));

    assertTrue(service.isApprovalEligible("art_eligible1234"));
  }

  @Test
  void approvalEligibilityFailsClosedWhenPayloadIsUnreadableOrArtifactNotAvailable() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
    ArtifactService service = new ArtifactService(artifactRecordPort, payloadStore);
    ArtifactRecordSnapshot unreadable =
        artifact(
            "art_unreadable1234",
            ArtifactStatus.AVAILABLE,
            "artifacts/run_ready1234/art_unreadable1234/v1/spec.md",
            "SHA-256",
            "abc123",
            null);
    ArtifactRecordSnapshot pending =
        artifact(
            "art_pending1234",
            ArtifactStatus.PENDING,
            "artifacts/run_ready1234/art_pending1234/v1/spec.md",
            null,
            null,
            null);

    when(artifactRecordPort.findByPublicId("art_unreadable1234"))
        .thenReturn(Optional.of(unreadable));
    when(artifactRecordPort.findByPublicId("art_pending1234")).thenReturn(Optional.of(pending));
    when(payloadStore.readBytes("artifacts/run_ready1234/art_unreadable1234/v1/spec.md"))
        .thenReturn(Optional.empty());

    assertFalse(service.isApprovalEligible("art_unreadable1234"));
    assertFalse(service.isApprovalEligible("art_pending1234"));
  }

  @Test
  void approvalEligibilityRejectsEmptyPayloadEvenWithMatchingMetadata() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
    ArtifactService service = new ArtifactService(artifactRecordPort, payloadStore);
    byte[] empty = new byte[0];
    ArtifactRecordSnapshot artifact =
        artifact(
            "art_empty1234",
            ArtifactStatus.AVAILABLE,
            "artifacts/run_ready1234/art_empty1234/v1/spec.md",
            "SHA-256",
            sha256Hex(empty),
            null);

    when(artifactRecordPort.findByPublicId("art_empty1234")).thenReturn(Optional.of(artifact));
    when(payloadStore.readBytes("artifacts/run_ready1234/art_empty1234/v1/spec.md"))
        .thenReturn(Optional.of(empty));

    assertFalse(service.isApprovalEligible("art_empty1234"));
  }

  @Test
  void approvalEligibilityRejectsPayloadWhoseChecksumDoesNotMatchStoredValue() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
    ArtifactService service = new ArtifactService(artifactRecordPort, payloadStore);
    byte[] payload = "spec body".getBytes(StandardCharsets.UTF_8);
    byte[] mutated = "spec body!".getBytes(StandardCharsets.UTF_8);
    ArtifactRecordSnapshot artifact =
        artifact(
            "art_drift1234",
            ArtifactStatus.AVAILABLE,
            "artifacts/run_ready1234/art_drift1234/v1/spec.md",
            "SHA-256",
            sha256Hex(payload),
            null);

    when(artifactRecordPort.findByPublicId("art_drift1234")).thenReturn(Optional.of(artifact));
    when(payloadStore.readBytes("artifacts/run_ready1234/art_drift1234/v1/spec.md"))
        .thenReturn(Optional.of(mutated));

    assertFalse(service.isApprovalEligible("art_drift1234"));
  }

  @Test
  void approvalEligibilityRejectsUnknownChecksumAlgorithm() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
    ArtifactService service = new ArtifactService(artifactRecordPort, payloadStore);
    byte[] payload = "spec body".getBytes(StandardCharsets.UTF_8);
    ArtifactRecordSnapshot artifact =
        artifact(
            "art_badalgo1234",
            ArtifactStatus.AVAILABLE,
            "artifacts/run_ready1234/art_badalgo1234/v1/spec.md",
            "NOT-A-REAL-ALGORITHM",
            "abc123",
            null);

    when(artifactRecordPort.findByPublicId("art_badalgo1234")).thenReturn(Optional.of(artifact));
    when(payloadStore.readBytes("artifacts/run_ready1234/art_badalgo1234/v1/spec.md"))
        .thenReturn(Optional.of(payload));

    assertFalse(service.isApprovalEligible("art_badalgo1234"));
  }

  private String sha256Hex(byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available on every supported JRE", error);
    }
  }

  private ArtifactRecordSnapshot artifact(
      String artifactId,
      ArtifactStatus status,
      String storageRef,
      String checksumAlgorithm,
      String checksumValue,
      OffsetDateTime archivedAt) {
    return new ArtifactRecordSnapshot(
        artifactId,
        "run_ready1234",
        ArtifactType.SPEC,
        1,
        null,
        DataClassification.SHAREABLE_REDACTED,
        storageRef,
        checksumAlgorithm,
        checksumValue,
        status,
        archivedAt == null ? null : archivedAt.withOffsetSameInstant(ZoneOffset.UTC));
  }
}
