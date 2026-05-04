package org.dradgo.application.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.time.Duration;
import org.dradgo.application.idempotency.spi.IdempotencyRecordPort;
import org.junit.jupiter.api.Test;

class IdempotencyRecordPortContractTest {

	@Test
	void staleReservationCheckUsesDurationInsteadOfPrimitiveMinutes() {
		Method method = java.util.Arrays.stream(IdempotencyRecordPort.class.getMethods())
			.filter(candidate -> candidate.getName().equals("isReservationStale"))
			.findFirst()
			.orElseThrow();

		assertEquals(2, method.getParameterCount());
		assertEquals(String.class, method.getParameterTypes()[0]);
		assertEquals(Duration.class, method.getParameterTypes()[1]);
		assertNotNull(method.getAnnotation(Deprecated.class) == null);
	}
}
