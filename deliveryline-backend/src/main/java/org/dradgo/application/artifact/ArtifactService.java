package org.dradgo.application.artifact;

import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.registry.ArtifactStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ArtifactService {

  private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

  private final ArtifactRecordPort artifactRecordPort;
  private final ArtifactPayloadStore artifactPayloadStore;

  public ArtifactService(
      ArtifactRecordPort artifactRecordPort, ArtifactPayloadStore artifactPayloadStore) {
    this.artifactRecordPort = artifactRecordPort;
    this.artifactPayloadStore = artifactPayloadStore;
  }

  public boolean isApprovalEligible(String artifactId) {
    return MdcKeys.withKey(
        MdcKeys.ARTIFACT_ID, artifactId, () -> isApprovalEligibleInternal(artifactId));
  }

  private boolean isApprovalEligibleInternal(String artifactId) {
    Optional<ArtifactRecordSnapshot> maybeArtifact =
        artifactRecordPort
            .findByPublicId(artifactId)
            .filter(artifact -> artifact.status() == ArtifactStatus.AVAILABLE)
            .filter(
                artifact ->
                    artifact.checksumAlgorithm() != null && !artifact.checksumAlgorithm().isBlank())
            .filter(
                artifact -> artifact.checksumValue() != null && !artifact.checksumValue().isBlank())
            .filter(artifact -> artifact.archivedAt() == null)
            .filter(artifact -> artifact.storageRef() != null && !artifact.storageRef().isBlank());
    if (maybeArtifact.isEmpty()) {
      log.info("isApprovalEligible=false artifactId={} reason=metadataGateRejected", artifactId);
      return false;
    }
    ArtifactRecordSnapshot artifact = maybeArtifact.get();
    Optional<byte[]> bytes = artifactPayloadStore.readBytes(artifact.storageRef());
    if (bytes.isEmpty()) {
      log.warn(
          "isApprovalEligible=false artifactId={} storageRef={} reason=payloadUnreadable",
          artifactId,
          artifact.storageRef());
      return false;
    }
    byte[] payload = bytes.get();
    if (payload.length == 0) {
      log.warn(
          "isApprovalEligible=false artifactId={} storageRef={} reason=emptyPayload",
          artifactId,
          artifact.storageRef());
      return false;
    }
    String canonical = ArtifactChecksum.canonicalAlgorithm(artifact.checksumAlgorithm());
    if (canonical == null || !ArtifactChecksum.ALLOWED_ALGORITHMS.contains(canonical)) {
      log.warn(
          "isApprovalEligible=false artifactId={} checksumAlgorithm={} reason=unknownAlgorithm",
          artifactId,
          artifact.checksumAlgorithm());
      return false;
    }
    Optional<String> recomputed;
    try {
      recomputed = ArtifactChecksum.digestHex(artifact.checksumAlgorithm(), payload);
    } catch (IllegalStateException jvmError) {
      log.warn(
          "isApprovalEligible=false artifactId={} checksumAlgorithm={} reason=digestFailed cause={}",
          artifactId,
          artifact.checksumAlgorithm(),
          jvmError.getMessage());
      return false;
    }
    if (recomputed.isEmpty()) {
      log.warn(
          "isApprovalEligible=false artifactId={} checksumAlgorithm={} reason=unknownAlgorithm",
          artifactId,
          artifact.checksumAlgorithm());
      return false;
    }
    boolean match = recomputed.get().equalsIgnoreCase(artifact.checksumValue());
    if (!match) {
      log.warn(
          "isApprovalEligible=false artifactId={} checksumAlgorithm={} reason=checksumMismatch payloadLength={}",
          artifactId,
          artifact.checksumAlgorithm(),
          payload.length);
    } else {
      log.info(
          "isApprovalEligible=true artifactId={} checksumAlgorithm={} payloadLength={}",
          artifactId,
          artifact.checksumAlgorithm(),
          payload.length);
    }
    return match;
  }
}
