package org.dradgo.application.security;

public enum RedactionCategory {
  LINEAR_API_KEY("[REDACTED_LINEAR_API_KEY]"),
  GITHUB_TOKEN("[REDACTED_GITHUB_TOKEN]"),
  SSH_PRIVATE_KEY("[REDACTED_SSH_PRIVATE_KEY]"),
  SSH_PUBLIC_KEY("[REDACTED_SSH_PUBLIC_KEY]"),
  AUTHORIZATION_HEADER("[REDACTED_AUTHORIZATION_HEADER]"),
  /**
   * Story 2.24 AC13(c) — `Idempotency-Key: ...` HTTP header captured in free-text request-shape
   * logs / exports. The existing {@code ProblemDetailsMapper} already sanitizes idempotency keys in
   * 4xx error bodies; this category closes the gap for any other surface that logs or exports raw
   * request shapes.
   */
  IDEMPOTENCY_KEY("[REDACTED_IDEMPOTENCY_KEY]"),
  /**
   * Story 2.24 AC13(a) — non-SSH PEM private-key blocks (EC, DSA, ENCRYPTED, generic PKCS#8
   * `PRIVATE KEY`). The existing {@link #SSH_PRIVATE_KEY} category stays in place for the
   * OPENSSH/RSA OpenSSH variants which have distinct placeholders and a longer-standing fixture
   * set.
   */
  PEM_PRIVATE_KEY("[REDACTED_PEM_PRIVATE_KEY]"),
  /**
   * Story 2.24 AC13(a) — a {@code CERTIFICATE} block paired with a sibling private-key block in the
   * same payload. Standalone {@code CERTIFICATE} blocks remain visible (public material); this
   * category covers the leak-risk pairing.
   */
  PEM_CERTIFICATE_WITH_PRIVATE_KEY("[REDACTED_PEM_CERTIFICATE_WITH_PRIVATE_KEY]"),
  QUERY_SECRET("[REDACTED_QUERY_SECRET]"),
  ENV_VALUE("[REDACTED_ENV_VALUE]"),
  SECRET_FIELD("[REDACTED_SECRET_FIELD]"),
  LOCAL_PATH("[REDACTED_LOCAL_PATH]"),
  ENVIRONMENT_BLOCK("[REDACTED_ENVIRONMENT_BLOCK]");

  private final String placeholder;

  RedactionCategory(String placeholder) {
    this.placeholder = placeholder;
  }

  public String placeholder() {
    return placeholder;
  }
}
