package org.dradgo.adapters.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.dradgo.application.runner.RunnerLogReference;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DataClassification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story 3.6 — {@link LocalRunnerLogStore} writes the redacted streams under a SEPARATE {@code
 * runner-logs/} root (Trap T7), is containment-guarded by the {@code rex_} prefix, and reports a
 * byte size that is the sum of both written streams.
 */
class LocalRunnerLogStoreTest {

  private static final String REX = "rex_logstore000001";

  @TempDir Path home;

  private LocalRunnerLogStore store() {
    return new LocalRunnerLogStore(home.toAbsolutePath().toString());
  }

  @Test
  void writePersistsBothRedactedStreamsUnderASeparateRunnerLogsRoot() throws Exception {
    byte[] stdout =
        "redacted stdout [REDACTED_AUTHORIZATION_HEADER]\n".getBytes(StandardCharsets.UTF_8);
    byte[] stderr = "redacted stderr\n".getBytes(StandardCharsets.UTF_8);

    RunnerLogReference reference = store().write(REX, stdout, stderr);

    Path dir = home.resolve("runner-logs").resolve(REX);
    assertThat(Files.isRegularFile(dir.resolve("runner.stdout"))).isTrue();
    assertThat(Files.isRegularFile(dir.resolve("runner.stderr"))).isTrue();
    // SEPARATE root from runner-work/ so story-3.2 cleanup never reaps it (Trap T7 / AC5).
    assertThat(dir.toString()).contains("runner-logs");
    assertThat(dir.toString()).doesNotContain("runner-work");
    assertThat(reference.byteSize()).isEqualTo((long) stdout.length + stderr.length);
    assertThat(reference.classification()).isEqualTo(DataClassification.LOCAL_ONLY);
    // The store never sees raw content — it only persisted what it was handed.
    assertThat(Files.readString(dir.resolve("runner.stdout")))
        .contains("[REDACTED_AUTHORIZATION_HEADER]");
  }

  @Test
  void findReturnsByteSizeForAPreviouslyWrittenDirectoryAndEmptyOtherwise() {
    LocalRunnerLogStore store = store();
    assertThat(store.find(REX)).isEmpty();

    store.write(REX, "abc".getBytes(StandardCharsets.UTF_8), "de".getBytes(StandardCharsets.UTF_8));

    Optional<RunnerLogReference> found = store.find(REX);
    assertThat(found).isPresent();
    assertThat(found.get().byteSize()).isEqualTo(5L);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void writeRejectsPreexistingSymlinkedRexDirectory() throws Exception {
    Path root = home.resolve("runner-logs");
    Files.createDirectories(root);
    Path outside = Files.createDirectory(home.resolve("outside-logs"));
    Files.createSymbolicLink(root.resolve(REX), outside);

    assertThatThrownBy(
            () -> store().write(REX, "safe".getBytes(StandardCharsets.UTF_8), new byte[0]))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void writeRejectsANonRunnerExecutionPrefix() {
    assertThatThrownBy(() -> store().write("art_not_a_rex_id", new byte[0], new byte[0]))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void writeTreatsNullStreamsAsEmpty() {
    RunnerLogReference reference = store().write(REX, null, null);
    assertThat(reference.byteSize()).isZero();
    assertThat(Files.exists(home.resolve("runner-logs").resolve(REX).resolve("runner.stdout")))
        .isTrue();
  }
}
