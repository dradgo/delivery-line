package org.dradgo.application.idempotency.spi;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.IdempotencyRecordStatus;

public record IdempotencyRecordSnapshot(
    String key,
    String commandFingerprint,
    IdempotencyRecordStatus status,
    String resultRef,
    OffsetDateTime createdAt) {}
