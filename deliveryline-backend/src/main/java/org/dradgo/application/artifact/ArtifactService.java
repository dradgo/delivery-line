package org.dradgo.application.artifact;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
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

  /**
   * Story 4.19 (AC1/AC2/AC6, Reconciliation 1) — the Compare Mode read seam. Loads an artifact by
   * public id and its redaction-ready payload bytes, gating exactly as the existing artifact reads
   * do so the compare endpoint surfaces the same typed Problem Details:
   *
   * <ul>
   *   <li>malformed id → {@code INVALID_ID_PREFIX} (400) via the prefix guard;
   *   <li>no such artifact → {@code ARTIFACT_RECORD_NOT_FOUND} (404);
   *   <li>{@code LOCAL_ONLY} classification → refused as {@code ARTIFACT_RECORD_NOT_FOUND} (404,
   *       opacity — mirrors {@code WorkflowInspectionService.getArtifactDetail});
   *   <li>not {@code AVAILABLE}, missing storageRef/checksum, or unreadable/empty payload → {@code
   *       ARTIFACT_PAYLOAD_UNAVAILABLE} (its live 503 mapping).
   * </ul>
   *
   * <p>Reuses the two ports {@code ArtifactService} already injects; {@code RevisionDeltaService}
   * reaches persistence only through here so its collaborator surface stays {@code ArtifactService}
   * + {@code RedactionPolicyService} (AC9).
   */
  public ArtifactCompareSource loadCompareSource(String artifactId) {
    return MdcKeys.withKey(
        MdcKeys.ARTIFACT_ID, artifactId, () -> loadCompareSourceInternal(artifactId));
  }

  /**
   * Story 4.19 (Reconciliation 2) — lightweight snapshot lookup by public id used ONLY for the
   * lineage parent-chain walk in {@code RevisionDeltaService}. Deliberately NOT availability-gated:
   * an ancestor on the chain may be archived/failed/superseded yet must still resolve so the walk
   * can prove connectivity. Returns {@link Optional#empty()} when the id resolves to no row.
   */
  public Optional<ArtifactRecordSnapshot> findSnapshot(String artifactId) {
    return artifactRecordPort.findByPublicId(artifactId);
  }

  private ArtifactCompareSource loadCompareSourceInternal(String artifactId) {
    PublicIdPrefixes.require(artifactId, PublicIdPrefixes.ARTIFACT);
    ArtifactRecordSnapshot snapshot =
        artifactRecordPort
            .findByPublicId(artifactId)
            .orElseThrow(() -> artifactRecordNotFound(artifactId));
    // LOCAL_ONLY opacity — a local-only artifact must never be served as shareable compare content;
    // report it as not-found (mirrors WorkflowInspectionService.getArtifactDetail's guard).
    if (snapshot.classification() == DataClassification.LOCAL_ONLY) {
      log.warn(
          "loadCompareSource classification reject artifactId={} classification=local-only",
          artifactId);
      throw artifactRecordNotFound(artifactId);
    }
    if (snapshot.status() != ArtifactStatus.AVAILABLE) {
      log.warn(
          "loadCompareSource status reject artifactId={} status={}",
          artifactId,
          snapshot.status().value());
      throw artifactPayloadUnavailable(artifactId);
    }
    if (snapshot.storageRef() == null
        || snapshot.storageRef().isBlank()
        || snapshot.checksumValue() == null
        || snapshot.checksumValue().isBlank()) {
      log.warn(
          "loadCompareSource metadata gate reject artifactId={} reason=missingStorageRefOrChecksum",
          artifactId);
      throw artifactPayloadUnavailable(artifactId);
    }
    Optional<byte[]> bytes = artifactPayloadStore.readBytes(snapshot.storageRef());
    if (bytes.isEmpty() || bytes.get().length == 0) {
      log.warn(
          "loadCompareSource payload unreadable artifactId={} storageRef={}",
          artifactId,
          snapshot.storageRef());
      throw artifactPayloadUnavailable(artifactId);
    }
    String producedByActor =
        artifactRecordPort.findProducingActorForArtifact(artifactId).orElse(null);
    log.debug(
        "loadCompareSource ok artifactId={} artifactType={} version={} payloadLength={}",
        artifactId,
        snapshot.artifactType().value(),
        snapshot.version(),
        bytes.get().length);
    return new ArtifactCompareSource(snapshot, bytes.get(), producedByActor);
  }

  private static DomainException artifactRecordNotFound(String artifactId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", artifactId);
    return new DomainException(
        DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
        "Artifact record not found: " + artifactId,
        details);
  }

  private static DomainException artifactPayloadUnavailable(String artifactId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", artifactId);
    return new DomainException(
        DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
        "Artifact payload unavailable for " + artifactId,
        details);
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
