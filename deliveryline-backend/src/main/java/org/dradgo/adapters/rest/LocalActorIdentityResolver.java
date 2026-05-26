package org.dradgo.adapters.rest;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Story 2.13 — resolves the actor identity carried on REST mutation commands.
 *
 * <p>When the {@code X-Actor-Identity} request header carries a non-blank value it wins verbatim;
 * otherwise the resolver falls back to the configured {@code
 * deliveryline.security.local-actor-identity} property (default {@code local-operator}). The header
 * is OPTIONAL — {@link #resolve(String)} never returns {@code null} and never raises an
 * authentication error (RBAC is audit-only per architecture line 256, and the MVP runs on a
 * localhost-only safe-by-default bind).
 *
 * <p>Story 2.13 round-4 D-R4-1: the prior "WARN-then-fallback" handling for unsafe header values
 * (oversize / control-char / Unicode FORMAT-category / surrogate / private-use / unassigned) is
 * split out into {@link #requireSafe(String)}, which fails closed with typed {@link
 * DomainErrorCode#INVALID_COMMAND_PAYLOAD} 400. The controller calls {@code requireSafe} BEFORE
 * {@code resolve} on the three mutation endpoints so a hostile or misconfigured caller cannot
 * silently land in the property-fallback identity (which would otherwise mask the rejection in the
 * audit trail). {@code resolve} now handles ONLY the legitimate "header absent OR blank" case.
 *
 * <p>Emission policy: {@code DEBUG} on every resolve indicating whether the header or the property
 * fallback supplied the value. The property value itself is treated as low-sensitivity operator
 * metadata (it stamps the {@code actor_identity} column on persisted commands) so it is safe to log
 * at {@code DEBUG} but not at {@code INFO}.
 *
 * <p>Story 2.13 review P7: emits a one-shot {@code WARN} at construction if the configured fallback
 * resolved to blank/empty or unsafe, so operators notice the misconfiguration rather than silently
 * falling back to {@link #DEFAULT_LOCAL_ACTOR_IDENTITY}. Mirrors the {@code
 * ApprovalReviewerRoleResolver} D2 pattern from story 2.9.
 */
@Component
public class LocalActorIdentityResolver {

  /** Hard-coded ultimate fallback for completely-unconfigured environments. */
  public static final String DEFAULT_LOCAL_ACTOR_IDENTITY = "local-operator";

  /** Inherited from the pre-2.13 DTO's {@code @Size(max=128)} on {@code actorIdentity}. */
  public static final int MAX_ACTOR_IDENTITY_LENGTH = 128;

  /** Header name surfaced in {@code details.header} on unsafe-value rejections. */
  public static final String ACTOR_IDENTITY_HEADER = "X-Actor-Identity";

  static final String UNSAFE_REASON_OVERSIZE = "oversize";
  static final String UNSAFE_REASON_CONTROL_CHAR = "control_char";
  static final String UNSAFE_REASON_FORMAT_CATEGORY = "format_category";
  static final String UNSAFE_REASON_DISALLOWED_CODEPOINT = "disallowed_codepoint";

  private static final Logger log = LoggerFactory.getLogger(LocalActorIdentityResolver.class);

  private final String configuredFallback;
  private final boolean fallbackBlank;
  private final String fallbackIdentity;

  public LocalActorIdentityResolver(
      @Value("${deliveryline.security.local-actor-identity:" + DEFAULT_LOCAL_ACTOR_IDENTITY + "}")
          String fallbackIdentity) {
    this.configuredFallback = fallbackIdentity == null ? "" : fallbackIdentity;
    // Story 2.13 round-4 P-R4-6: String.strip() is Unicode-aware (handles NBSP, en/em spaces,
    // ideographic space) where String.trim() only strips ASCII whitespace ≤ U+0020.
    String trimmed = this.configuredFallback.strip();
    boolean blank = trimmed.isEmpty();
    // Story 2.13 round-3 review P7: defense-in-depth on the property fallback. The configured value
    // is stamped verbatim into the actor_identity audit column when the X-Actor-Identity header is
    // omitted, so an oversized/control-char/bidi-spoofed property value would otherwise poison
    // audit logs without ever passing through requireSafe.
    if (!blank && unsafeReason(trimmed) != null) {
      this.fallbackBlank = true;
      this.fallbackIdentity = DEFAULT_LOCAL_ACTOR_IDENTITY;
    } else {
      this.fallbackBlank = blank;
      this.fallbackIdentity = blank ? DEFAULT_LOCAL_ACTOR_IDENTITY : trimmed;
    }
  }

  @PostConstruct
  void warnOnBlankFallback() {
    if (fallbackBlank) {
      log.warn(
          "deliveryline.security.local-actor-identity resolved blank or unsafe; falling back to {}."
              + " Override the property explicitly in application.yaml for production deployments.",
          DEFAULT_LOCAL_ACTOR_IDENTITY);
    }
  }

  /**
   * Story 2.13 round-4 D-R4-1: fail-closed guard for unsafe {@code X-Actor-Identity} header values.
   * Invoked by the controller BEFORE {@link #resolve(String)} on every mutation endpoint so that a
   * hostile or misconfigured caller cannot silently fall back to the property-fallback identity
   * (which would mask the rejection in the audit trail).
   *
   * <p>Header absent OR blank is NOT unsafe — it is the legitimate "use the property fallback" path
   * and remains a no-op here.
   *
   * @throws DomainException with code {@link DomainErrorCode#INVALID_COMMAND_PAYLOAD} when the
   *     supplied (non-blank) value exceeds {@link #MAX_ACTOR_IDENTITY_LENGTH} chars or contains a
   *     control / FORMAT / SURROGATE / PRIVATE_USE / UNASSIGNED code point.
   */
  public void requireSafe(String suppliedHeaderValue) {
    if (suppliedHeaderValue == null) {
      return;
    }
    String trimmed = suppliedHeaderValue.strip();
    if (trimmed.isEmpty()) {
      return;
    }
    String reason = unsafeReason(trimmed);
    if (reason == null) {
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("header", ACTOR_IDENTITY_HEADER);
    details.put("reason", reason);
    throw new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Unsafe " + ACTOR_IDENTITY_HEADER + " header value: " + reason,
        details);
  }

  /**
   * Resolve the actor identity for a single request.
   *
   * <p>Pre-2.13-round-4 this method also enforced the length / charset gates; D-R4-1 split that
   * behaviour into the fail-closed {@link #requireSafe(String)} guard, leaving this method with the
   * narrower "use the trimmed header value if present, else fall back to the property" contract.
   *
   * @param suppliedHeaderValue value from the {@code X-Actor-Identity} header (may be {@code null}
   *     or blank).
   * @return non-null, non-blank actor identity to stamp on the application command.
   */
  public String resolve(String suppliedHeaderValue) {
    if (suppliedHeaderValue != null) {
      String trimmed = suppliedHeaderValue.strip();
      if (!trimmed.isEmpty()) {
        log.debug("actor identity resolved source=header");
        return trimmed;
      }
    }
    log.debug("actor identity resolved source=property value={}", fallbackIdentity);
    return fallbackIdentity;
  }

  private static String unsafeReason(String value) {
    if (value.length() > MAX_ACTOR_IDENTITY_LENGTH) {
      return UNSAFE_REASON_OVERSIZE;
    }
    int i = 0;
    while (i < value.length()) {
      int cp = value.codePointAt(i);
      if (Character.isISOControl(cp)) {
        return UNSAFE_REASON_CONTROL_CHAR;
      }
      int type = Character.getType(cp);
      if (type == Character.FORMAT) {
        return UNSAFE_REASON_FORMAT_CATEGORY;
      }
      if (type == Character.SURROGATE
          || type == Character.PRIVATE_USE
          || type == Character.UNASSIGNED) {
        return UNSAFE_REASON_DISALLOWED_CODEPOINT;
      }
      i += Character.charCount(cp);
    }
    return null;
  }
}
