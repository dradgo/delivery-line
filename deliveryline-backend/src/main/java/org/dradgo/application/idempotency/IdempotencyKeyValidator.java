package org.dradgo.application.idempotency;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyValidator {

  private static final int MAX_KEY_LENGTH = 128;
  private static final Pattern OPAQUE_KEY_PATTERN = Pattern.compile("[A-Za-z0-9-]{16,128}");
  private static final Pattern UUID_SHAPE_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  public String requireValid(String rawKey) {
    if (rawKey == null || rawKey.isBlank()) {
      throw missingKey();
    }
    if (rawKey.length() > MAX_KEY_LENGTH) {
      throw invalidKey(rawKey);
    }
    if (UUID_SHAPE_PATTERN.matcher(rawKey).matches()) {
      // Canonicalize to lowercase so the same logical UUID submitted with
      // mixed casing does not create two distinct reservations under the
      // byte-exact `key` unique constraint. Non-v4/v7 UUIDs (v1/v3/v5/v6)
      // are accepted via the opaque-string rule per AC9 ("UUIDv4, UUIDv7,
      // or the governed opaque-string rule").
      return rawKey.toLowerCase(Locale.ROOT);
    }
    if (OPAQUE_KEY_PATTERN.matcher(rawKey).matches()) {
      return rawKey;
    }
    throw invalidKey(rawKey);
  }

  public DomainException missingKeyException() {
    return missingKey();
  }

  private DomainException missingKey() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", null);
    return new DomainException(
        DomainErrorCode.MISSING_IDEMPOTENCY_KEY, "Missing idempotency key", details);
  }

  private DomainException invalidKey(String rawKey) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", rawKey);
    details.put("rule", "[A-Za-z0-9-]{16,128} or UUIDv4/UUIDv7");
    return new DomainException(
        DomainErrorCode.INVALID_IDEMPOTENCY_KEY, "Invalid idempotency key", details);
  }
}
