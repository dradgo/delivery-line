package org.dradgo.adapters.persistence;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.mapper.IdempotencyRecordEntityMapper;
import org.dradgo.adapters.persistence.repository.IdempotencyRecordRepository;
import org.dradgo.application.idempotency.spi.IdempotencyRecordPort;
import org.dradgo.application.idempotency.spi.IdempotencyRecordSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyPersistenceAdapter implements IdempotencyRecordPort {

  private final IdempotencyRecordRepository repository;
  private final IdempotencyRecordEntityMapper mapper;

  public IdempotencyPersistenceAdapter(
      IdempotencyRecordRepository repository, IdempotencyRecordEntityMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Override
  public boolean tryReserve(
      String publicId,
      String key,
      String commandType,
      String actorIdentity,
      String commandFingerprint,
      IdempotencyRecordStatus status) {
    return repository.tryReserve(
            publicId, key, commandType, actorIdentity, commandFingerprint, status.value())
        == 1;
  }

  @Override
  public Optional<IdempotencyRecordSnapshot> findWithLockByKey(String key) {
    return repository.findWithLockByKey(key).map(mapper::toSnapshot);
  }

  @Override
  public boolean isReservationStale(String key, Duration threshold) {
    return repository.isReservationStale(key, threshold.toSeconds()).orElse(false);
  }

  @Override
  public void markCompleted(
      String key, String resultRef, IdempotencyRecordStatus status, OffsetDateTime completedAt) {
    var record =
        repository
            .findWithLockByKey(key)
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.IDEMPOTENCY_RECORD_LOST,
                        "Idempotency record disappeared for key " + key,
                        Map.of("idempotencyKey", key)));
    record.setResultRef(resultRef);
    record.setStatus(status);
    record.setCompletedAt(completedAt);
    repository.save(record);
  }
}
