package org.dradgo.application.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Story 4.19 (AC3, AC10) — unit coverage for the ATX-heading markdown section differ. */
class MarkdownSectionDifferTest {

  private final MarkdownSectionDiffer differ = new DefaultMarkdownSectionDiffer();

  @Test
  void identicalBodiesYieldNoChanges() {
    String md = "# Intro\n\nHello world\n\n# Design\n\nDetails here";
    assertThat(differ.diff(md, md)).isEmpty();
  }

  @Test
  void addedSectionOnlyInBIsReportedAdded() {
    String a = "# Intro\n\nHello";
    String b = "# Intro\n\nHello\n\n# Extra\n\nBrand new";

    List<MarkdownChangeBlock> blocks = differ.diff(a, b);

    assertThat(blocks).hasSize(1);
    MarkdownChangeBlock block = blocks.get(0);
    assertThat(block.sectionPath()).isEqualTo("Extra");
    assertThat(block.changeKind()).isEqualTo(ChangeKind.ADDED);
    assertThat(block.priorText()).isNull();
    assertThat(block.currentText()).isEqualTo("Brand new");
  }

  @Test
  void removedSectionOnlyInAIsReportedRemoved() {
    String a = "# Intro\n\nHello\n\n# Extra\n\nGone soon";
    String b = "# Intro\n\nHello";

    List<MarkdownChangeBlock> blocks = differ.diff(a, b);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).sectionPath()).isEqualTo("Extra");
    assertThat(blocks.get(0).changeKind()).isEqualTo(ChangeKind.REMOVED);
    assertThat(blocks.get(0).priorText()).isEqualTo("Gone soon");
    assertThat(blocks.get(0).currentText()).isNull();
  }

  @Test
  void bodyChangeInSharedSectionIsReportedModified() {
    String a = "# Design\n\nThe old approach";
    String b = "# Design\n\nThe new approach";

    List<MarkdownChangeBlock> blocks = differ.diff(a, b);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).changeKind()).isEqualTo(ChangeKind.MODIFIED);
    assertThat(blocks.get(0).priorText()).isEqualTo("The old approach");
    assertThat(blocks.get(0).currentText()).isEqualTo("The new approach");
  }

  @Test
  void whitespaceOnlyDifferenceIsNotModified() {
    String a = "# Design\n\nLine one\nLine two";
    String b = "# Design\n\n   Line one   \n\n\nLine two\n";

    assertThat(differ.diff(a, b)).isEmpty();
  }

  @Test
  void nestedHeadingProducesTrailSectionPath() {
    String a = "# Design\n\ntop\n\n## Edge Cases\n\nold edge";
    String b = "# Design\n\ntop\n\n## Edge Cases\n\nnew edge";

    List<MarkdownChangeBlock> blocks = differ.diff(a, b);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).sectionPath()).isEqualTo("Design > Edge Cases");
    assertThat(blocks.get(0).changeKind()).isEqualTo(ChangeKind.MODIFIED);
  }

  @Test
  void headingsInsideFencedCodeAreNotSectionBoundaries() {
    String a = "# Code\n\n```\n# not a heading\nold\n```";
    String b = "# Code\n\n```\n# not a heading\nnew\n```";

    List<MarkdownChangeBlock> blocks = differ.diff(a, b);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).sectionPath()).isEqualTo("Code");
  }

  @Test
  void mismatchedFenceMarkersDoNotSwallowFollowingHeadings() {
    // A "```"-opened fence containing a "~~~" line must NOT be closed by that "~~~"; otherwise the
    // trailing "```" re-opens a fence and the "# B" heading is swallowed into it.
    String md = "# A\n\n```\ncode\n~~~ inside code\nmore\n```\n\n# B\n\nbody";

    assertThat(differ.diff(md, md)).isEmpty();
    // Same body vs a version with a changed "# B" section is picked up as a real section (not
    // lost).
    String changed = "# A\n\n```\ncode\n~~~ inside code\nmore\n```\n\n# B\n\nchanged body";
    List<MarkdownChangeBlock> blocks = differ.diff(md, changed);
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).sectionPath()).isEqualTo("B");
    assertThat(blocks.get(0).changeKind()).isEqualTo(ChangeKind.MODIFIED);
  }

  @Test
  void whitespaceOnlyDifferenceReportedByPredicateButReorderIsNot() {
    String base = "# A\n\nbody a\n\n# B\n\nbody b";
    String whitespace = "# A\n\n   body a   \n\n\n# B\n\nbody b\n";
    String reordered = "# B\n\nbody b\n\n# A\n\nbody a";

    assertThat(differ.isWhitespaceOnlyDifference(base, whitespace)).isTrue();
    assertThat(differ.isWhitespaceOnlyDifference(base, reordered)).isFalse();
    // A reorder aligns by heading, so diff() itself still emits no block — the predicate is what
    // lets the caller avoid mislabeling it as "no meaningful diff".
    assertThat(differ.diff(base, reordered)).isEmpty();
  }

  @Test
  void preamblePardonedWhenBlankButCapturedWhenPresent() {
    String a = "intro preamble\n\n# Body\n\nsame";
    String b = "changed preamble\n\n# Body\n\nsame";

    List<MarkdownChangeBlock> blocks = differ.diff(a, b);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).sectionPath()).isEmpty();
    assertThat(blocks.get(0).changeKind()).isEqualTo(ChangeKind.MODIFIED);
  }
}
