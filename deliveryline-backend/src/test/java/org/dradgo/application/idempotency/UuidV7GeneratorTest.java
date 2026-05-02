package org.dradgo.application.idempotency;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

	private final UuidV7Generator generator = new UuidV7Generator();
	private final IdempotencyKeyValidator validator = new IdempotencyKeyValidator();

	@Test
	void generatedKeyHasRfc9562V7Layout() {
		String generated = generator.generate();
		UUID parsed = UUID.fromString(generated);

		assertAll(
			() -> assertEquals(7, parsed.version(), "version nibble must be 7"),
			() -> assertEquals(2, parsed.variant(), "variant bits must be 10")
		);
	}

	@Test
	void generatedKeyEncodesCurrentTimestampInHigh48Bits() {
		long before = Instant.now().toEpochMilli();
		String generated = generator.generate();
		long after = Instant.now().toEpochMilli();

		long timestamp = (UUID.fromString(generated).getMostSignificantBits() >>> 16) & 0xFFFFFFFFFFFFL;
		assertTrue(
			timestamp >= before && timestamp <= after,
			() -> "expected timestamp in [" + before + "," + after + "] but was " + timestamp);
	}

	@Test
	void generatedKeysPassIdempotencyKeyValidator() {
		for (int i = 0; i < 32; i++) {
			String generated = generator.generate();
			assertEquals(generated, validator.requireValid(generated));
		}
	}

	@Test
	void v5UuidIsAcceptedViaOpaqueRule() {
		// AC9 says "UUIDv4, UUIDv7, OR the governed opaque-string rule";
		// non-v4/v7 UUIDs match the opaque pattern and are accepted.
		String v5 = "f47ac10b-58cc-5372-a567-0e02b2c3d479";
		assertEquals(v5, validator.requireValid(v5));
	}

	@Test
	void uppercaseUuidIsCanonicalizedToLowercase() {
		String upper = "F47AC10B-58CC-7372-A567-0E02B2C3D479";
		assertEquals(upper.toLowerCase(), validator.requireValid(upper));
	}
}
