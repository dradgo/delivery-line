package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;

/**
 * Story 2.13 round-3 review follow-up: focused unit tests for {@link LocalActorIdentityResolver}.
 *
 * <p>Slice tests across the controller use a {@code Mockito.mock(...)} stub that only mirrors the
 * trim-and-fallback path, hiding the resolver's real length / control-char / Unicode-FORMAT gates.
 * These tests exercise the production behaviour directly so a future refactor that weakens the
 * audit-safe sanitisation surfaces as a regression here rather than only in the live HTTP slice.
 *
 * <p>Story 2.13 round-4 D-R4-1: behaviour split between {@link
 * LocalActorIdentityResolver#requireSafe(String)} (fail-closed; throws {@link
 * DomainErrorCode#INVALID_COMMAND_PAYLOAD} on unsafe values) and {@link
 * LocalActorIdentityResolver#resolve(String)} (no-throw fallback for null/blank only). Tests below
 * pin both halves.
 *
 * <p>Non-printable fixture characters are constructed via {@code Character.toString(int)} on
 * explicit code-point literals so the source stays readable and copy-paste safe (no inline literal
 * control bytes; no {@code \\u}-escapes that the Java source-translation stage would expand into
 * real control characters in the source file).
 */
class LocalActorIdentityResolverTest {

  private static final String FALLBACK = "local-operator";

  // Code points referenced below — declared as ints so the source contains no literal control
  // bytes or bidi-spoofing format characters.
  private static final int LF = 0x000A; // line feed
  private static final int NEL = 0x0085; // C1 next-line control
  private static final int DEL = 0x007F; // delete
  private static final int RLO = 0x202E; // right-to-left override (bidi spoofing)
  private static final int ZWJ = 0x200D; // zero-width joiner

  @Test
  void blankHeaderFallsBackToConfiguredIdentity() {
    LocalActorIdentityResolver resolver = new LocalActorIdentityResolver(FALLBACK);
    assertThat(resolver.resolve("   ")).isEqualTo(FALLBACK);
    assertThat(resolver.resolve("")).isEqualTo(FALLBACK);
    assertThat(resolver.resolve(null)).isEqualTo(FALLBACK);
  }

  @Test
  void blankHeaderPassesRequireSafe() {
    LocalActorIdentityResolver resolver = new LocalActorIdentityResolver(FALLBACK);
    assertThatCode(() -> resolver.requireSafe(null)).doesNotThrowAnyException();
    assertThatCode(() -> resolver.requireSafe("")).doesNotThrowAnyException();
    assertThatCode(() -> resolver.requireSafe("   ")).doesNotThrowAnyException();
  }

  @Test
  void oversizeHeaderRequireSafeThrowsInvalidCommandPayload() {
    LocalActorIdentityResolver resolver = new LocalActorIdentityResolver(FALLBACK);
    String oversize = "a".repeat(LocalActorIdentityResolver.MAX_ACTOR_IDENTITY_LENGTH + 1);
    assertThatThrownBy(() -> resolver.requireSafe(oversize))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
              assertThat(ex.details())
                  .containsEntry("header", LocalActorIdentityResolver.ACTOR_IDENTITY_HEADER)
                  .containsEntry("reason", LocalActorIdentityResolver.UNSAFE_REASON_OVERSIZE);
            });
  }

  @Test
  void controlCharHeaderRequireSafeThrowsInvalidCommandPayload() {
    LocalActorIdentityResolver resolver = new LocalActorIdentityResolver(FALLBACK);
    // C0 control (LF) — log-injection vector.
    assertThatThrownBy(() -> resolver.requireSafe("alex" + Character.toString(LF) + "imposter"))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
              assertThat(ex.details())
                  .containsEntry("reason", LocalActorIdentityResolver.UNSAFE_REASON_CONTROL_CHAR);
            });
    // C1 control (NEL).
    assertThatThrownBy(() -> resolver.requireSafe("alex" + Character.toString(NEL) + "suffix"))
        .isInstanceOf(DomainException.class);
    // DEL.
    assertThatThrownBy(() -> resolver.requireSafe("alex" + Character.toString(DEL) + "suffix"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void unicodeFormatCharHeaderRequireSafeThrowsInvalidCommandPayload() {
    LocalActorIdentityResolver resolver = new LocalActorIdentityResolver(FALLBACK);
    // RIGHT-TO-LEFT OVERRIDE — bidi spoofing vector.
    assertThatThrownBy(() -> resolver.requireSafe("alex" + Character.toString(RLO) + "imposter"))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
              assertThat(ex.details())
                  .containsEntry(
                      "reason", LocalActorIdentityResolver.UNSAFE_REASON_FORMAT_CATEGORY);
            });
    // ZERO WIDTH JOINER.
    assertThatThrownBy(() -> resolver.requireSafe("alex" + Character.toString(ZWJ) + "imposter"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void safeHeaderRequireSafePasses() {
    LocalActorIdentityResolver resolver = new LocalActorIdentityResolver(FALLBACK);
    assertThatCode(() -> resolver.requireSafe("alex")).doesNotThrowAnyException();
    assertThatCode(() -> resolver.requireSafe("  alex  ")).doesNotThrowAnyException();
    assertThatCode(() -> resolver.requireSafe("alex@example.com")).doesNotThrowAnyException();
  }

  @Test
  void safeHeaderIsTrimmedAndReturnedVerbatim() {
    LocalActorIdentityResolver resolver = new LocalActorIdentityResolver(FALLBACK);
    assertThat(resolver.resolve("  alex  ")).isEqualTo("alex");
    assertThat(resolver.resolve("alex@example.com")).isEqualTo("alex@example.com");
  }

  @Test
  void blankConfiguredPropertyFallsBackToDefaultLocalActorIdentity() {
    LocalActorIdentityResolver resolver = new LocalActorIdentityResolver("   ");
    assertThat(resolver.resolve(null))
        .isEqualTo(LocalActorIdentityResolver.DEFAULT_LOCAL_ACTOR_IDENTITY);
  }

  @Test
  void unsafeConfiguredPropertyFallsBackToDefaultLocalActorIdentity() {
    // Round-3 review P7 follow-up: the constructor runs the configured property through the same
    // safety predicate as requireSafe, so an operator-typoed property carrying a control-char or
    // oversized value doesn't bypass audit-safe sanitisation.
    LocalActorIdentityResolver controlChar =
        new LocalActorIdentityResolver("alex" + Character.toString(LF) + "imposter");
    assertThat(controlChar.resolve(null))
        .isEqualTo(LocalActorIdentityResolver.DEFAULT_LOCAL_ACTOR_IDENTITY);

    String oversize = "a".repeat(LocalActorIdentityResolver.MAX_ACTOR_IDENTITY_LENGTH + 1);
    LocalActorIdentityResolver oversizeResolver = new LocalActorIdentityResolver(oversize);
    assertThat(oversizeResolver.resolve(null))
        .isEqualTo(LocalActorIdentityResolver.DEFAULT_LOCAL_ACTOR_IDENTITY);
  }
}
