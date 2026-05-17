package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.IdempotencyRecordEntity;
import org.dradgo.application.idempotency.spi.IdempotencyRecordSnapshot;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyRecordEntityMapper {

  public IdempotencyRecordSnapshot toSnapshot(IdempotencyRecordEntity entity) {
    return new IdempotencyRecordSnapshot(
        entity.getKey(),
        entity.getCommandFingerprint(),
        entity.getStatus(),
        entity.getResultRef(),
        entity.getCreatedAt());
  }
}
