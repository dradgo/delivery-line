package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.adapters.persistence.mapper.IdempotencyRecordEntityMapper;
import org.dradgo.adapters.persistence.repository.IdempotencyRecordRepository;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.junit.jupiter.api.Test;

class IdempotencyPersistenceAdapterTest {

  @Test
  void markCompletedTranslatesMissingRecordIntoStableGovernedError() {
    IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    IdempotencyPersistenceAdapter adapter =
        new IdempotencyPersistenceAdapter(repository, new IdempotencyRecordEntityMapper());

    when(repository.findWithLockByKey("idem-missing-1234567890")).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                adapter.markCompleted(
                    "idem-missing-1234567890",
                    "run_submit1234",
                    IdempotencyRecordStatus.COMPLETED,
                    OffsetDateTime.now()));

    assertEquals("IDEMPOTENCY_RECORD_LOST", error.errorCode().name());
    assertEquals("idem-missing-1234567890", error.details().get("idempotencyKey"));
  }
}
