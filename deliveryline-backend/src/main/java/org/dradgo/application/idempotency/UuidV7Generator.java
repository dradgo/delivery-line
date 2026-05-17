package org.dradgo.application.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class UuidV7Generator {

	private final Clock clock;

	public UuidV7Generator() {
		this.clock = Clock.systemUTC();
	}

	public String generate() {
		byte[] value = new byte[16];
		ThreadLocalRandom.current().nextBytes(value);
		long timestamp = Instant.now(clock).toEpochMilli();
		value[0] = (byte) (timestamp >>> 40);
		value[1] = (byte) (timestamp >>> 32);
		value[2] = (byte) (timestamp >>> 24);
		value[3] = (byte) (timestamp >>> 16);
		value[4] = (byte) (timestamp >>> 8);
		value[5] = (byte) timestamp;
		value[6] = (byte) ((value[6] & 0x0F) | 0x70);
		value[8] = (byte) ((value[8] & 0x3F) | 0x80);
		return new UUID(toLong(value, 0), toLong(value, 8)).toString();
	}

	/**
	 * Parse a supplied UUID string of any version, returning {@link Optional#empty()} when the
	 * value is null, blank, longer than the canonical UUID length, or fails {@link UUID#fromString}
	 * parsing. Caller is responsible for stripping log-injection control characters before passing
	 * the value into MDC; this helper validates structure only.
	 */
	public static Optional<String> tryParse(String value) {
		if (value == null) {
			return Optional.empty();
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty() || trimmed.length() != 36) {
			return Optional.empty();
		}
		try {
			UUID.fromString(trimmed);
			return Optional.of(trimmed.toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}

	private long toLong(byte[] value, int offset) {
		long result = 0L;
		for (int index = offset; index < offset + 8; index++) {
			result = (result << 8) | (value[index] & 0xFFL);
		}
		return result;
	}
}
