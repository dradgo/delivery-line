package org.dradgo.application.compare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Story 4.19 (AC5, Reconciliation 8) — default unified-diff file-level differ. Pure (java-only); no
 * Spring / persistence dependency.
 *
 * <p>Parses each side's unified diff into a per-file {@code [addedLines, removedLines]} map (path
 * taken from the {@code b/…} side of the {@code diff --git} / {@code +++} header, or the {@code
 * a/…} side for a {@code /dev/null} target), then unions the two maps. A file only in B is {@code
 * added}, only in A is {@code removed}, in both with differing counts is {@code modified}; a file
 * in both with identical counts is not a delta and is skipped.
 */
final class DefaultFileLevelDiffer implements FileLevelDiffer {

  @Override
  public List<FileChangeBlock> diff(String priorDiff, String currentDiff) {
    Map<String, int[]> a = parse(priorDiff);
    Map<String, int[]> b = parse(currentDiff);

    TreeSet<String> paths = new TreeSet<>();
    paths.addAll(a.keySet());
    paths.addAll(b.keySet());

    List<FileChangeBlock> blocks = new ArrayList<>();
    for (String path : paths) {
      int[] countsA = a.get(path);
      int[] countsB = b.get(path);
      if (countsA != null && countsB != null) {
        if (countsA[0] == countsB[0] && countsA[1] == countsB[1]) {
          continue; // same file, same counts — no revision delta.
        }
        blocks.add(new FileChangeBlock(path, ChangeKind.MODIFIED, countsB[0], countsB[1]));
      } else if (countsB != null) {
        blocks.add(new FileChangeBlock(path, ChangeKind.ADDED, countsB[0], countsB[1]));
      } else {
        blocks.add(new FileChangeBlock(path, ChangeKind.REMOVED, countsA[0], countsA[1]));
      }
    }
    return blocks;
  }

  private static Map<String, int[]> parse(String diff) {
    Map<String, int[]> files = new LinkedHashMap<>();
    if (diff == null || diff.isBlank()) {
      return files;
    }
    String pendingA = null;
    String pendingB = null;
    int[] counts = null;
    // Once a hunk (@@) starts, +/- lines are CONTENT, so a content line whose own text begins with
    // "--- " / "+++ " (e.g. a removed SQL comment "-- x" → diff line "--- x") must be counted, not
    // mistaken for a file header. Headers only appear before the first hunk of a file; a new
    // "diff --git" resets us back out of hunk mode for the next file.
    boolean inHunk = false;
    for (String line : diff.split("\n", -1)) {
      if (line.startsWith("diff --git ")) {
        String[] gitPaths = extractGitPaths(line.substring("diff --git ".length()));
        pendingA = gitPaths[0];
        pendingB = gitPaths[1];
        counts = files.computeIfAbsent(canonical(pendingA, pendingB), key -> new int[2]);
        inHunk = false;
      } else if (line.startsWith("@@")) {
        inHunk = true;
      } else if (!inHunk && line.startsWith("--- ")) {
        pendingA = stripDiffPath(line.substring(4));
        counts = files.computeIfAbsent(canonical(pendingA, pendingB), key -> new int[2]);
      } else if (!inHunk && line.startsWith("+++ ")) {
        pendingB = stripDiffPath(line.substring(4));
        counts = files.computeIfAbsent(canonical(pendingA, pendingB), key -> new int[2]);
      } else if (line.startsWith("+")) {
        if (counts != null) {
          counts[0]++;
        }
      } else if (line.startsWith("-")) {
        if (counts != null) {
          counts[1]++;
        }
      }
    }
    return files;
  }

  /** {@code "a/foo b/bar"} → {@code ["foo", "bar"]}; tolerant of a missing {@code " b/"}. */
  private static String[] extractGitPaths(String header) {
    int split = header.indexOf(" b/");
    if (split < 0) {
      return new String[] {stripDiffPath(header), null};
    }
    return new String[] {
      stripDiffPath(header.substring(0, split)), stripDiffPath(header.substring(split + 1))
    };
  }

  /** Strips an {@code a/} or {@code b/} prefix and any trailing {@code \t}-separated metadata. */
  private static String stripDiffPath(String raw) {
    String value = raw.strip();
    int tab = value.indexOf('\t');
    if (tab >= 0) {
      value = value.substring(0, tab).strip();
    }
    if ("/dev/null".equals(value)) {
      return "/dev/null";
    }
    if (value.startsWith("a/") || value.startsWith("b/")) {
      return value.substring(2);
    }
    return value;
  }

  /** Prefer the {@code b/} (new) side unless it is {@code /dev/null} (a deletion). */
  private static String canonical(String aPath, String bPath) {
    if (bPath != null && !"/dev/null".equals(bPath)) {
      return bPath;
    }
    if (aPath != null && !"/dev/null".equals(aPath)) {
      return aPath;
    }
    return bPath != null ? bPath : aPath;
  }
}
