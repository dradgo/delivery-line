package org.dradgo.application.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.artifact.ArtifactCompareSource;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/** Story 4.19 (AC1, AC2, AC6, AC10) — unit coverage for the compare orchestration. */
@ExtendWith(MockitoExtension.class)
class RevisionDeltaServiceTest {

  @Mock private ArtifactService artifactService;
  @Mock private RedactionPolicyService redactionPolicyService;

  private RevisionDeltaService service() {
    return new RevisionDeltaService(artifactService, redactionPolicyService);
  }

  private void stubIdentityRedaction() {
    when(redactionPolicyService.redact(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                new RedactionResult(
                    invocation.getArgument(0),
                    null,
                    DataClassification.SHAREABLE_REDACTED,
                    DataClassification.SHAREABLE_REDACTED,
                    false,
                    Set.of()));
  }

  private static ArtifactRecordSnapshot snap(
      String id, String run, ArtifactType type, int version, String parent, String checksum) {
    return new ArtifactRecordSnapshot(
        id,
        run,
        type,
        version,
        parent,
        DataClassification.SHAREABLE_REDACTED,
        "ref/" + id,
        "SHA-256",
        checksum,
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        OffsetDateTime.parse("2026-07-15T00:00:00Z"));
  }

  private static ArtifactCompareSource source(
      ArtifactRecordSnapshot snapshot, String payload, String actor) {
    return new ArtifactCompareSource(snapshot, payload.getBytes(StandardCharsets.UTF_8), actor);
  }

  @Test
  void computesSpecSectionDeltaHonouringAbDirection() {
    stubIdentityRedaction();
    ArtifactRecordSnapshot a =
        snap("art_a1", "run_1", ArtifactType.SPEC, 1, null, "aaaaaaaaaaaaaa1");
    ArtifactRecordSnapshot b =
        snap("art_b2", "run_1", ArtifactType.SPEC, 2, "art_a1", "aaaaaaaaaaaaaa2");
    when(artifactService.loadCompareSource("art_a1"))
        .thenReturn(source(a, "# Intro\n\nold", "dev"));
    when(artifactService.loadCompareSource("art_b2"))
        .thenReturn(source(b, "# Intro\n\nnew\n\n# New\n\nadded", "reviewer"));
    when(artifactService.findSnapshot("art_a1")).thenReturn(Optional.of(a));

    RevisionDelta delta = service().computeDelta("art_a1", "art_b2");

    assertThat(delta.artifactType()).isEqualTo("spec");
    assertThat(delta.noMeaningfulDiff()).isFalse();
    assertThat(delta.linkedDiffReferences()).isNull();
    assertThat(delta.changes())
        .extracting(ChangeBlock::changeKind)
        .containsExactlyInAnyOrder(ChangeKind.MODIFIED, ChangeKind.ADDED);
    // A/B direction preserved (no auto-swap).
    assertThat(delta.revisionA().version()).isEqualTo(1);
    assertThat(delta.revisionA().producedByActor()).isEqualTo("dev");
    assertThat(delta.revisionB().version()).isEqualTo(2);
    assertThat(delta.revisionB().producedByActor()).isEqualTo("reviewer");
    assertThat(delta.revisionB().checksum()).isEqualTo("SHA-256:aaaaaaaaaaaa");
    assertThat(delta.summary().changedRegionCount()).isEqualTo(2);
    assertThat(delta.summary().addedCount()).isEqualTo(1);
    assertThat(delta.summary().modifiedCount()).isEqualTo(1);
  }

  @Test
  void equalChecksumShortCircuitsToNoMeaningfulDiff() {
    ArtifactRecordSnapshot a = snap("art_a1", "run_1", ArtifactType.SPEC, 1, null, "same-checksum");
    ArtifactRecordSnapshot b =
        snap("art_b2", "run_1", ArtifactType.SPEC, 2, "art_a1", "same-checksum");
    when(artifactService.loadCompareSource("art_a1")).thenReturn(source(a, "whatever", "dev"));
    when(artifactService.loadCompareSource("art_b2")).thenReturn(source(b, "whatever", "dev"));
    when(artifactService.findSnapshot("art_a1")).thenReturn(Optional.of(a));

    RevisionDelta delta = service().computeDelta("art_a1", "art_b2");

    assertThat(delta.noMeaningfulDiff()).isTrue();
    assertThat(delta.changes()).isEmpty();
    assertThat(delta.summary().changedRegionCount()).isZero();
  }

  @Test
  void specSectionReorderIsNotClaimedAsNoMeaningfulDiff() {
    // Same sections, reordered — the heading-aligned differ emits no block, but reordering IS a
    // meaningful change, so noMeaningfulDiff must be false (not the old changes.isEmpty()
    // shortcut).
    ArtifactRecordSnapshot a = snap("art_a1", "run_1", ArtifactType.SPEC, 1, null, "c1");
    ArtifactRecordSnapshot b = snap("art_b2", "run_1", ArtifactType.SPEC, 2, "art_a1", "c2");
    when(artifactService.loadCompareSource("art_a1"))
        .thenReturn(source(a, "# A\n\naa\n\n# B\n\nbb", "dev"));
    when(artifactService.loadCompareSource("art_b2"))
        .thenReturn(source(b, "# B\n\nbb\n\n# A\n\naa", "dev"));
    when(artifactService.findSnapshot("art_a1")).thenReturn(Optional.of(a));

    RevisionDelta delta = service().computeDelta("art_a1", "art_b2");

    assertThat(delta.changes()).isEmpty();
    assertThat(delta.noMeaningfulDiff()).isFalse();
  }

  @Test
  void bothUnparseablePlanPayloadsAreNotClaimedAsNoMeaningfulDiff() {
    // Two DIFFERENT but malformed plan payloads both parse to an empty step list; a swallowed parse
    // failure must not masquerade as equivalence.
    ArtifactRecordSnapshot a =
        snap("art_a1", "run_1", ArtifactType.IMPLEMENTATION_PLAN, 1, null, "c1");
    ArtifactRecordSnapshot b =
        snap("art_b2", "run_1", ArtifactType.IMPLEMENTATION_PLAN, 2, "art_a1", "c2");
    when(artifactService.loadCompareSource("art_a1")).thenReturn(source(a, "not json {{{", "dev"));
    when(artifactService.loadCompareSource("art_b2"))
        .thenReturn(source(b, "also broken ]]]", "dev"));
    when(artifactService.findSnapshot("art_a1")).thenReturn(Optional.of(a));

    RevisionDelta delta = service().computeDelta("art_a1", "art_b2");

    assertThat(delta.changes()).isEmpty();
    assertThat(delta.noMeaningfulDiff()).isFalse();
  }

  @Test
  void identicalPlanStepsWithDifferingChecksumIsNoMeaningfulDiff() {
    // Same steps, different non-step JSON fields ⇒ differing checksums but zero step blocks and
    // both
    // payloads parse — a genuine no-meaningful-diff.
    ArtifactRecordSnapshot a =
        snap("art_a1", "run_1", ArtifactType.IMPLEMENTATION_PLAN, 1, null, "c1");
    ArtifactRecordSnapshot b =
        snap("art_b2", "run_1", ArtifactType.IMPLEMENTATION_PLAN, 2, "art_a1", "c2");
    when(artifactService.loadCompareSource("art_a1"))
        .thenReturn(source(a, "{\"steps\":[\"build\"],\"x\":1}", "dev"));
    when(artifactService.loadCompareSource("art_b2"))
        .thenReturn(source(b, "{\"steps\":[\"build\"],\"x\":2}", "dev"));
    when(artifactService.findSnapshot("art_a1")).thenReturn(Optional.of(a));

    RevisionDelta delta = service().computeDelta("art_a1", "art_b2");

    assertThat(delta.changes()).isEmpty();
    assertThat(delta.noMeaningfulDiff()).isTrue();
  }

  @Test
  void rejectsDifferentRunLineageAsMismatch() {
    ArtifactRecordSnapshot a = snap("art_a1", "run_1", ArtifactType.SPEC, 1, null, "c1");
    ArtifactRecordSnapshot b = snap("art_b1", "run_2", ArtifactType.SPEC, 1, null, "c2");
    when(artifactService.loadCompareSource("art_a1")).thenReturn(source(a, "x", "dev"));
    when(artifactService.loadCompareSource("art_b1")).thenReturn(source(b, "y", "dev"));

    assertThatThrownBy(() -> service().computeDelta("art_a1", "art_b1"))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.ARTIFACT_LINEAGE_MISMATCH);
              assertThat(ex.details()).containsEntry("reason", "different_run_or_type");
            });
  }

  @Test
  void rejectsDisconnectedParentChainAsMismatch() {
    ArtifactRecordSnapshot a = snap("art_a1", "run_1", ArtifactType.SPEC, 1, null, "c1");
    ArtifactRecordSnapshot b =
        snap("art_b3", "run_1", ArtifactType.SPEC, 3, "art_x2", "c2"); // disjoint lineage
    ArtifactRecordSnapshot x2 = snap("art_x2", "run_1", ArtifactType.SPEC, 2, null, "cx");
    when(artifactService.loadCompareSource("art_a1")).thenReturn(source(a, "x", "dev"));
    when(artifactService.loadCompareSource("art_b3")).thenReturn(source(b, "y", "dev"));
    when(artifactService.findSnapshot("art_x2")).thenReturn(Optional.of(x2));

    assertThatThrownBy(() -> service().computeDelta("art_a1", "art_b3"))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.ARTIFACT_LINEAGE_MISMATCH);
              assertThat(ex.details()).containsEntry("reason", "not_on_parent_chain");
            });
  }

  @Test
  void redactionIsAppliedToEveryTextFieldOnServe() {
    when(redactionPolicyService.redact(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                new RedactionResult(
                    "[REDACTED]",
                    null,
                    DataClassification.SHAREABLE_REDACTED,
                    DataClassification.SHAREABLE_REDACTED,
                    true,
                    Set.of()));
    ArtifactRecordSnapshot a = snap("art_a1", "run_1", ArtifactType.SPEC, 1, null, "c1");
    ArtifactRecordSnapshot b = snap("art_b2", "run_1", ArtifactType.SPEC, 2, "art_a1", "c2");
    when(artifactService.loadCompareSource("art_a1"))
        .thenReturn(source(a, "# Sec\n\nsecret alpha", "dev"));
    when(artifactService.loadCompareSource("art_b2"))
        .thenReturn(source(b, "# Sec\n\nsecret beta", "dev"));
    when(artifactService.findSnapshot("art_a1")).thenReturn(Optional.of(a));

    RevisionDelta delta = service().computeDelta("art_a1", "art_b2");

    MarkdownChangeBlock block = (MarkdownChangeBlock) delta.changes().get(0);
    assertThat(block.priorText()).isEqualTo("[REDACTED]");
    assertThat(block.currentText()).isEqualTo("[REDACTED]");
    verify(redactionPolicyService, atLeastOnce())
        .redact(
            anyString(),
            org.mockito.ArgumentMatchers.eq(DataClassification.SHAREABLE_REDACTED.value()));
  }

  @Test
  void prOutputDeltaPopulatesLinkedDiffReferences() {
    // No redaction stub: file-level blocks carry only path + counts (no free-text to redact).
    ArtifactRecordSnapshot a = snap("art_a1", "run_1", ArtifactType.PR_OUTPUT, 1, null, "c1");
    ArtifactRecordSnapshot b = snap("art_b2", "run_1", ArtifactType.PR_OUTPUT, 2, "art_a1", "c2");
    String diffB =
        "diff --git a/src/A.java b/src/A.java\n--- a/src/A.java\n+++ b/src/A.java\n@@ -1 +1,2 @@\n test\n+more\n";
    when(artifactService.loadCompareSource("art_a1"))
        .thenReturn(source(a, "{\"diff\":\"\"}", "dev"));
    when(artifactService.loadCompareSource("art_b2"))
        .thenReturn(source(b, "{\"diff\":" + jsonString(diffB) + "}", "dev"));
    when(artifactService.findSnapshot("art_a1")).thenReturn(Optional.of(a));

    RevisionDelta delta = service().computeDelta("art_a1", "art_b2");

    assertThat(delta.artifactType()).isEqualTo("prOutput");
    assertThat(delta.linkedDiffReferences()).containsExactly("art_a1", "art_b2");
    assertThat(delta.changes()).hasSize(1);
    FileChangeBlock file = (FileChangeBlock) delta.changes().get(0);
    assertThat(file.filePath()).isEqualTo("src/A.java");
    assertThat(file.changeKind()).isEqualTo(ChangeKind.ADDED);
  }

  @Test
  void pinsCompletionLogAndLineageMismatchWarn() {
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      // completion log
      stubIdentityRedaction();
      ArtifactRecordSnapshot a = snap("art_a1", "run_1", ArtifactType.SPEC, 1, null, "c1");
      ArtifactRecordSnapshot b = snap("art_b2", "run_1", ArtifactType.SPEC, 2, "art_a1", "c2");
      when(artifactService.loadCompareSource("art_a1")).thenReturn(source(a, "# S\n\nold", "dev"));
      when(artifactService.loadCompareSource("art_b2")).thenReturn(source(b, "# S\n\nnew", "dev"));
      when(artifactService.findSnapshot("art_a1")).thenReturn(Optional.of(a));

      service().computeDelta("art_a1", "art_b2");

      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).contains("computeDelta success");
                assertThat(event.getFormattedMessage()).contains("changedRegionCount=");
              });

      // WARN on lineage mismatch
      ArtifactRecordSnapshot c = snap("art_c1", "run_9", ArtifactType.SPEC, 1, null, "c9");
      when(artifactService.loadCompareSource("art_c1")).thenReturn(source(c, "z", "dev"));
      assertThatThrownBy(() -> service().computeDelta("art_a1", "art_c1"))
          .isInstanceOf(DomainException.class);
      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("computeDelta lineage mismatch");
              });
    } finally {
      detachAppender(appender);
    }
  }

  private static String jsonString(String raw) {
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RevisionDeltaService.class);
    logger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RevisionDeltaService.class);
    logger.detachAppender(appender);
    logger.setLevel(null);
  }
}
