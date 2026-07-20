package org.dradgo.application.artifact.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactEventRecord;
import org.dradgo.application.artifact.ArtifactOperationSnapshot;
import org.dradgo.application.artifact.ArtifactReconciliationService.RepairActionHint;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftWritePort;
import org.dradgo.application.artifact.reconciliation.spi.DriftRecordRequest;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DriftCategory;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ArtifactDriftDetectionServiceUnitTest {

  private static final String STORAGE_REF = "artifacts/run/art/v1/spec.md";
  private static final byte[] PAYLOAD = "hello world".getBytes(StandardCharsets.UTF_8);
  private static final String GOOD_CHECKSUM =
      ArtifactChecksum.digestHex("SHA-256", PAYLOAD).orElseThrow();

  private final ArtifactOperationPort operationPort = mock(ArtifactOperationPort.class);
  private final ArtifactRecordPort recordPort = mock(ArtifactRecordPort.class);
  private final ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
  private final ArtifactDriftWritePort writePort = mock(ArtifactDriftWritePort.class);
  private final ArtifactEventPort eventPort = mock(ArtifactEventPort.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private ArtifactDriftDetectionService service() {
    return service(100);
  }

  private ArtifactDriftDetectionService service(int batchLimit) {
    return new ArtifactDriftDetectionService(
        operationPort,
        recordPort,
        payloadStore,
        writePort,
        eventPort,
        new ArtifactDriftDetectionProperties(false, 900_000L, batchLimit, 0L),
        meterRegistry,
        Clock.fixed(Instant.parse("2026-07-13T10:00:00Z"), ZoneOffset.UTC),
        Duration.ofMinutes(15),
        callthroughTemplate());
  }

  @Test
  void orphanOperationIsRecordedEmittedAndCounted() {
    when(operationPort.findPendingOlderThan(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of(staleOperation()));
    when(recordPort.findAvailableCreatedBefore(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of());
    when(writePort.recordIfAbsent(any())).thenReturn(true);

    DriftScanResult result = service().detectDrift();

    ArgumentCaptor<DriftRecordRequest> request = ArgumentCaptor.forClass(DriftRecordRequest.class);
    verify(writePort).recordIfAbsent(request.capture());
    assertEquals(DriftCategory.ORPHAN_OPERATION.value(), request.getValue().driftCategory());
    assertEquals("op_stale1234", request.getValue().artifactOperationId());
    assertEquals(null, request.getValue().artifactId());

    ArgumentCaptor<ArtifactEventRecord> event = ArgumentCaptor.forClass(ArtifactEventRecord.class);
    verify(eventPort).append(event.capture());
    assertEquals(WorkflowEventType.ARTIFACT_DRIFT_DETECTED, event.getValue().eventType());
    assertEquals("run_stale1234", event.getValue().workflowRunId());
    assertEquals(FailureCategory.ORPHAN, event.getValue().failureCategory());
    assertEquals(
        DriftCategory.ORPHAN_OPERATION.value(), event.getValue().details().get("driftCategory"));
    assertEquals("op_stale1234", event.getValue().details().get("operationId"));

    assertEquals(1, result.orphanCount());
    assertEquals(
        1.0,
        meterRegistry
            .counter(
                ArtifactDriftDetectionService.DRIFT_DETECTED_COUNTER,
                "category",
                DriftCategory.ORPHAN_OPERATION.value())
            .count());
  }

  @Test
  void missingPayloadIsRecorded() {
    when(operationPort.findPendingOlderThan(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of());
    when(recordPort.findAvailableCreatedBefore(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of(availableArtifact(GOOD_CHECKSUM)));
    when(payloadStore.isReadable(STORAGE_REF)).thenReturn(false);
    when(writePort.recordIfAbsent(any())).thenReturn(true);

    DriftScanResult result = service().detectDrift();

    ArgumentCaptor<DriftRecordRequest> request = ArgumentCaptor.forClass(DriftRecordRequest.class);
    verify(writePort).recordIfAbsent(request.capture());
    assertEquals(DriftCategory.MISSING_PAYLOAD.value(), request.getValue().driftCategory());
    assertEquals("art_avail1234", request.getValue().artifactId());
    assertEquals(null, request.getValue().artifactOperationId());
    assertEquals(1, result.missingCount());
  }

  @Test
  void checksumMismatchIsRecorded() {
    when(operationPort.findPendingOlderThan(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of());
    when(recordPort.findAvailableCreatedBefore(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of(availableArtifact("deadbeefdeadbeef")));
    when(payloadStore.isReadable(STORAGE_REF)).thenReturn(true);
    when(payloadStore.readBytes(STORAGE_REF)).thenReturn(Optional.of(PAYLOAD));
    when(writePort.recordIfAbsent(any())).thenReturn(true);

    DriftScanResult result = service().detectDrift();

    ArgumentCaptor<DriftRecordRequest> request = ArgumentCaptor.forClass(DriftRecordRequest.class);
    verify(writePort).recordIfAbsent(request.capture());
    assertEquals(DriftCategory.CHECKSUM_MISMATCH.value(), request.getValue().driftCategory());
    assertEquals(1, result.checksumCount());
  }

  @Test
  void matchingChecksumRecordsNoDrift() {
    when(operationPort.findPendingOlderThan(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of());
    when(recordPort.findAvailableCreatedBefore(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of(availableArtifact(GOOD_CHECKSUM)));
    when(payloadStore.isReadable(STORAGE_REF)).thenReturn(true);
    when(payloadStore.readBytes(STORAGE_REF)).thenReturn(Optional.of(PAYLOAD));

    DriftScanResult result = service().detectDrift();

    verify(writePort, never()).recordIfAbsent(any());
    assertEquals(0, result.checksumCount());
    assertEquals(0, result.missingCount());
  }

  @Test
  void unsupportedStoredAlgorithmIsWarnNotChecksumMismatch() {
    ArtifactRecordSnapshot legacy =
        ArtifactRecordSnapshot.withoutFailureMetadata(
            "art_legacy1234",
            "run_legacy1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.SHAREABLE_REDACTED,
            STORAGE_REF,
            "MD5", // disallowed algorithm — digestHex returns empty (OQ-2)
            "anyvalue",
            ArtifactStatus.AVAILABLE,
            null);
    when(operationPort.findPendingOlderThan(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of());
    when(recordPort.findAvailableCreatedBefore(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of(legacy));
    when(payloadStore.isReadable(STORAGE_REF)).thenReturn(true);
    when(payloadStore.readBytes(STORAGE_REF)).thenReturn(Optional.of(PAYLOAD));

    ListAppender<ILoggingEvent> appender = attachAppender();
    DriftScanResult result = service().detectDrift();
    detachAppender(appender);

    verify(writePort, never()).recordIfAbsent(any());
    assertEquals(0, result.checksumCount());
    assertTrue(
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("unverifiable checksum algorithm")),
        "expected a WARN 'unverifiable checksum algorithm' line for the disallowed stored algorithm");
  }

  @Test
  void dedupSkipEmitsNoEventAndDoesNotCount() {
    when(operationPort.findPendingOlderThan(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of(staleOperation()));
    when(recordPort.findAvailableCreatedBefore(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of());
    when(writePort.recordIfAbsent(any())).thenReturn(false); // standing drift already recorded

    DriftScanResult result = service().detectDrift();

    verify(eventPort, never()).append(any());
    assertEquals(0, result.orphanCount());
    assertEquals(
        0.0,
        meterRegistry
            .counter(
                ArtifactDriftDetectionService.DRIFT_DETECTED_COUNTER,
                "category",
                DriftCategory.ORPHAN_OPERATION.value())
            .count());
  }

  @Test
  void fullBatchFlagsBatchLimitHit() {
    when(operationPort.findPendingOlderThan(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of());
    when(recordPort.findAvailableCreatedBefore(
            any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(List.of(availableArtifact(GOOD_CHECKSUM), availableArtifact(GOOD_CHECKSUM)));
    when(payloadStore.isReadable(STORAGE_REF)).thenReturn(true);
    when(payloadStore.readBytes(STORAGE_REF)).thenReturn(Optional.of(PAYLOAD));

    DriftScanResult result = service(2).detectDrift();

    assertTrue(
        result.batchLimitHit(), "a full batch must flag batchLimitHit (no silent truncation)");
  }

  @Test
  void fullAvailableBatchAdvancesKeysetCursorNextTick() {
    when(operationPort.findPendingOlderThan(any(), anyInt(), any(), any())).thenReturn(List.of());
    ArtifactRecordSnapshot a1 = availableArtifactWithId("art_aaaa1111", GOOD_CHECKSUM);
    ArtifactRecordSnapshot a2 = availableArtifactWithId("art_bbbb2222", GOOD_CHECKSUM);
    when(recordPort.findAvailableCreatedBefore(any(), anyInt(), any(), any()))
        .thenReturn(List.of(a1, a2)) // tick 1: full batch of 2 (limit 2) → cursor advances to a2
        .thenReturn(List.of()); // tick 2: tail
    when(payloadStore.isReadable(STORAGE_REF)).thenReturn(true);
    when(payloadStore.readBytes(STORAGE_REF)).thenReturn(Optional.of(PAYLOAD));

    ArtifactDriftDetectionService svc = service(2);
    svc.detectDrift();
    svc.detectDrift();

    ArgumentCaptor<String> afterPublicId = ArgumentCaptor.forClass(String.class);
    verify(recordPort, times(2))
        .findAvailableCreatedBefore(any(), anyInt(), any(), afterPublicId.capture());
    assertEquals(
        null, afterPublicId.getAllValues().get(0), "tick 1 starts from the oldest (null cursor)");
    assertEquals(
        "art_bbbb2222",
        afterPublicId.getAllValues().get(1),
        "tick 2 continues strictly after the last row of the full batch (keyset cursor)");
  }

  @Test
  void newDriftEmitsWarnLine() {
    when(operationPort.findPendingOlderThan(any(), anyInt(), any(), any()))
        .thenReturn(List.of(staleOperation()));
    when(recordPort.findAvailableCreatedBefore(any(), anyInt(), any(), any()))
        .thenReturn(List.of());
    when(writePort.recordIfAbsent(any())).thenReturn(true);

    ListAppender<ILoggingEvent> appender = attachAppender();
    service().detectDrift();
    detachAppender(appender);

    assertTrue(
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("artifact drift detected")
                        && e.getFormattedMessage()
                            .contains(DriftCategory.ORPHAN_OPERATION.value())),
        "expected a WARN 'artifact drift detected' line carrying driftCategory for a new drift row");
  }

  @Test
  void dedupSkipEmitsDebugLine() {
    when(operationPort.findPendingOlderThan(any(), anyInt(), any(), any()))
        .thenReturn(List.of(staleOperation()));
    when(recordPort.findAvailableCreatedBefore(any(), anyInt(), any(), any()))
        .thenReturn(List.of());
    when(writePort.recordIfAbsent(any())).thenReturn(false); // standing drift already recorded

    ListAppender<ILoggingEvent> appender = attachAppender();
    service().detectDrift();
    detachAppender(appender);

    assertTrue(
        appender.list.stream()
            .anyMatch(
                e -> e.getLevel() == Level.DEBUG && e.getFormattedMessage().contains("dedup-skip")),
        "expected a DEBUG 'dedup-skip' line for an already-recorded standing drift (never WARN)");
  }

  @Test
  void repairActionHintMapsPerCategory() {
    assertEquals(
        RepairActionHint.MARK_FAILED_OR_COMPLETE,
        RepairActionHint.forCategory(DriftCategory.ORPHAN_OPERATION));
    assertEquals(
        RepairActionHint.RESTORE_FROM_BACKUP_OR_MARK_UNAVAILABLE,
        RepairActionHint.forCategory(DriftCategory.MISSING_PAYLOAD));
    assertEquals(
        RepairActionHint.RE_VERIFY_OR_MARK_CORRUPTED,
        RepairActionHint.forCategory(DriftCategory.CHECKSUM_MISMATCH));
  }

  private static ArtifactOperationSnapshot staleOperation() {
    return new ArtifactOperationSnapshot(
        "op_stale1234",
        "run_stale1234",
        "art_stale1234",
        "create",
        ArtifactOperationStatus.PENDING,
        "idem-stale-123",
        null,
        null,
        OffsetDateTime.parse("2026-07-13T09:00:00Z"));
  }

  private static ArtifactRecordSnapshot availableArtifact(String checksumValue) {
    return ArtifactRecordSnapshot.withoutFailureMetadata(
        "art_avail1234",
        "run_avail1234",
        ArtifactType.SPEC,
        1,
        null,
        DataClassification.SHAREABLE_REDACTED,
        STORAGE_REF,
        "SHA-256",
        checksumValue,
        ArtifactStatus.AVAILABLE,
        null);
  }

  private static ArtifactRecordSnapshot availableArtifactWithId(
      String publicId, String checksumValue) {
    return ArtifactRecordSnapshot.withoutFailureMetadata(
        publicId,
        "run_avail1234",
        ArtifactType.SPEC,
        1,
        null,
        DataClassification.SHAREABLE_REDACTED,
        STORAGE_REF,
        "SHA-256",
        checksumValue,
        ArtifactStatus.AVAILABLE,
        OffsetDateTime.parse("2026-07-13T08:00:00Z"));
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(ArtifactDriftDetectionService.class);
    // DEBUG so the dedup-skip line (DEBUG, never WARN) reaches the appender too.
    logger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(ArtifactDriftDetectionService.class);
    logger.detachAppender(appender);
    logger.setLevel(null);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any(TransactionCallback.class)))
        .thenAnswer(
            invocation -> ((TransactionCallback) invocation.getArgument(0)).doInTransaction(null));
    return template;
  }
}
