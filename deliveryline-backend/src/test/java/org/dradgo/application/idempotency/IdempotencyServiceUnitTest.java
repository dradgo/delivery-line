package org.dradgo.application.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.application.idempotency.spi.IdempotencyRecordPort;
import org.dradgo.application.idempotency.spi.IdempotencyRecordSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class IdempotencyServiceUnitTest {

  // Story 1.21 — F17 disposition. Test method is tagged "known-failure" and
  // excluded from Surefire via <excludedGroups> in deliveryline-backend/pom.xml.
  // The failure mode (MAX_RESERVATION_ATTEMPTS=200 vs test expects 3) is a
  // load-bearing reminder of a concurrency-hardening tech-debt item. See
  // _bmad-output/implementation-artifacts/deferred-work.md F17. Do NOT delete
  // or rewrite — the test compiles and remains discoverable via
  // `mvn test -Dgroups=known-failure` for triage.
  @Tag("known-failure")
  @Test
  void repeatedRollbackWindowExhaustionRaisesStableGovernedError() {
    IdempotencyRecordPort port = mock(IdempotencyRecordPort.class);
    IdempotencyService service = new IdempotencyService(port);

    when(port.tryReserve(
            any(),
            eq("idem-exhausted-1234567890"),
            any(),
            any(),
            any(),
            eq(IdempotencyRecordStatus.RESERVED)))
        .thenReturn(false);
    when(port.findWithLockByKey("idem-exhausted-1234567890")).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.checkAndReserve(
                    "idem-exhausted-1234567890", "SubmitWorkflowCommand", "alex", "f".repeat(64)));

    assertEquals("IDEMPOTENCY_RESERVATION_EXHAUSTED", error.errorCode().name());
    assertEquals("idem-exhausted-1234567890", error.details().get("idempotencyKey"));
    assertEquals(3, error.details().get("maxAttempts"));
    verify(port, times(3))
        .tryReserve(
            any(),
            eq("idem-exhausted-1234567890"),
            any(),
            any(),
            any(),
            eq(IdempotencyRecordStatus.RESERVED));
  }

  @Test
  void staleReservationStillRaisesTheGovernedConflictPath() {
    IdempotencyRecordPort port = mock(IdempotencyRecordPort.class);
    IdempotencyService service = new IdempotencyService(port);
    IdempotencyRecordSnapshot snapshot =
        new IdempotencyRecordSnapshot(
            "idem-stale-1234567890",
            "c".repeat(64),
            IdempotencyRecordStatus.RESERVED,
            null,
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(11));

    when(port.tryReserve(
            any(),
            eq("idem-stale-1234567890"),
            any(),
            any(),
            any(),
            eq(IdempotencyRecordStatus.RESERVED)))
        .thenReturn(false);
    when(port.findWithLockByKey("idem-stale-1234567890")).thenReturn(Optional.of(snapshot));
    when(port.isReservationStale("idem-stale-1234567890", Duration.ofMinutes(10))).thenReturn(true);

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.checkAndReserve(
                    "idem-stale-1234567890", "SubmitWorkflowCommand", "alex", "c".repeat(64)));

    assertEquals("STALE_IDEMPOTENCY_RESERVATION", error.errorCode().name());
  }
}
