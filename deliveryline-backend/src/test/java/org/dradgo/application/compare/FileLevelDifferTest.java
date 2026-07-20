package org.dradgo.application.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Story 4.19 (AC5, AC10) — unit coverage for the unified-diff file-level differ. */
class FileLevelDifferTest {

  private final FileLevelDiffer differ = new DefaultFileLevelDiffer();

  private static String fooDiff(int adds, int removes) {
    StringBuilder sb = new StringBuilder();
    sb.append("diff --git a/src/Foo.java b/src/Foo.java\n");
    sb.append("index abc1234..def5678 100644\n");
    sb.append("--- a/src/Foo.java\n");
    sb.append("+++ b/src/Foo.java\n");
    sb.append("@@ -1,2 +1,3 @@\n");
    sb.append(" context line\n");
    for (int i = 0; i < removes; i++) {
      sb.append("-removed ").append(i).append('\n');
    }
    for (int i = 0; i < adds; i++) {
      sb.append("+added ").append(i).append('\n');
    }
    return sb.toString();
  }

  @Test
  void bothDiffsAbsentYieldsNoChanges() {
    assertThat(differ.diff(null, null)).isEmpty();
    assertThat(differ.diff("", "  ")).isEmpty();
  }

  @Test
  void fileOnlyInCurrentIsReportedAddedWithCounts() {
    List<FileChangeBlock> blocks = differ.diff(null, fooDiff(3, 1));

    assertThat(blocks).hasSize(1);
    FileChangeBlock block = blocks.get(0);
    assertThat(block.filePath()).isEqualTo("src/Foo.java");
    assertThat(block.changeKind()).isEqualTo(ChangeKind.ADDED);
    assertThat(block.addedLines()).isEqualTo(3);
    assertThat(block.removedLines()).isEqualTo(1);
  }

  @Test
  void fileOnlyInPriorIsReportedRemoved() {
    List<FileChangeBlock> blocks = differ.diff(fooDiff(2, 2), null);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).changeKind()).isEqualTo(ChangeKind.REMOVED);
    assertThat(blocks.get(0).addedLines()).isEqualTo(2);
    assertThat(blocks.get(0).removedLines()).isEqualTo(2);
  }

  @Test
  void sameFileDifferentCountsIsModifiedWithCurrentCounts() {
    List<FileChangeBlock> blocks = differ.diff(fooDiff(1, 1), fooDiff(5, 2));

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).changeKind()).isEqualTo(ChangeKind.MODIFIED);
    assertThat(blocks.get(0).addedLines()).isEqualTo(5);
    assertThat(blocks.get(0).removedLines()).isEqualTo(2);
  }

  @Test
  void sameFileSameCountsIsNotADelta() {
    assertThat(differ.diff(fooDiff(3, 1), fooDiff(3, 1))).isEmpty();
  }

  @Test
  void deletedFileTargetingDevNullUsesPriorPath() {
    String deletion =
        "diff --git a/src/Old.java b/src/Old.java\n"
            + "deleted file mode 100644\n"
            + "--- a/src/Old.java\n"
            + "+++ /dev/null\n"
            + "@@ -1,2 +0,0 @@\n"
            + "-gone one\n"
            + "-gone two\n";

    List<FileChangeBlock> blocks = differ.diff(null, deletion);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).filePath()).isEqualTo("src/Old.java");
    assertThat(blocks.get(0).changeKind()).isEqualTo(ChangeKind.ADDED);
    assertThat(blocks.get(0).removedLines()).isEqualTo(2);
  }

  @Test
  void contentLinesResemblingFileHeadersAreCountedNotMisparsed() {
    // Inside a hunk, a removed source line "-- keep" serializes as "--- keep" and an added
    // "++ x" as "+++ x"; these are CONTENT and must be counted, not treated as file headers.
    String diff =
        "diff --git a/db/V1.sql b/db/V1.sql\n"
            + "--- a/db/V1.sql\n"
            + "+++ b/db/V1.sql\n"
            + "@@ -1,2 +1,2 @@\n"
            + " context\n"
            + "--- removed sql comment\n"
            + "+++ added sql comment\n";

    List<FileChangeBlock> blocks = differ.diff(null, diff);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).filePath()).isEqualTo("db/V1.sql");
    assertThat(blocks.get(0).addedLines()).isEqualTo(1);
    assertThat(blocks.get(0).removedLines()).isEqualTo(1);
  }

  @Test
  void multipleFilesAreSortedByPath() {
    String multi =
        fooDiff(1, 0)
            + "diff --git a/src/Abc.java b/src/Abc.java\n"
            + "--- a/src/Abc.java\n"
            + "+++ b/src/Abc.java\n"
            + "@@ -0,0 +1,1 @@\n"
            + "+new\n";

    List<FileChangeBlock> blocks = differ.diff(null, multi);

    assertThat(blocks)
        .extracting(FileChangeBlock::filePath)
        .containsExactly("src/Abc.java", "src/Foo.java");
  }
}
