package org.dradgo.application.compare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story 4.19 (AC3) — default ATX-heading markdown section differ. Pure (java-only); no Spring /
 * persistence dependency.
 *
 * <p>Sections are split on ATX headings ({@code #}..{@code ######}); each section's {@code
 * sectionPath} is the heading trail (a level-N heading nested under its nearest lower-level
 * ancestors, joined by {@code " > "}). Content before the first heading is the preamble section
 * with an empty {@code sectionPath} (emitted only when non-blank). Headings inside fenced code
 * blocks ({@code ```} / {@code ~~~}) are ignored.
 */
final class DefaultMarkdownSectionDiffer implements MarkdownSectionDiffer {

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
  private static final Pattern BLANK_RUN = Pattern.compile("(?m)\\n{2,}");

  @Override
  public List<MarkdownChangeBlock> diff(String priorMarkdown, String currentMarkdown) {
    Map<String, String> a = split(priorMarkdown);
    Map<String, String> b = split(currentMarkdown);

    List<String> order = new ArrayList<>(a.keySet());
    for (String key : b.keySet()) {
      if (!a.containsKey(key)) {
        order.add(key);
      }
    }

    List<MarkdownChangeBlock> blocks = new ArrayList<>();
    for (String path : order) {
      boolean inA = a.containsKey(path);
      boolean inB = b.containsKey(path);
      if (inA && !inB) {
        blocks.add(new MarkdownChangeBlock(path, ChangeKind.REMOVED, a.get(path), null));
      } else if (!inA) {
        blocks.add(new MarkdownChangeBlock(path, ChangeKind.ADDED, null, b.get(path)));
      } else if (!normalize(a.get(path)).equals(normalize(b.get(path)))) {
        blocks.add(new MarkdownChangeBlock(path, ChangeKind.MODIFIED, a.get(path), b.get(path)));
      }
    }
    return blocks;
  }

  @Override
  public boolean isWhitespaceOnlyDifference(String priorMarkdown, String currentMarkdown) {
    return normalize(priorMarkdown).equals(normalize(currentMarkdown));
  }

  private static Map<String, String> split(String markdown) {
    Map<String, String> sections = new LinkedHashMap<>();
    if (markdown == null || markdown.isBlank()) {
      return sections;
    }
    List<Integer> levels = new ArrayList<>();
    List<String> titles = new ArrayList<>();
    String currentPath = "";
    StringBuilder body = new StringBuilder();
    // Track WHICH fence marker opened the block; only the same marker ("```" or "~~~") closes it,
    // so
    // a "```"-opened fence is not spuriously closed by a "~~~" content line (which would leak the
    // rest of the document — including its headings — into the fence and drop those sections).
    String fenceMarker = null;
    for (String line : markdown.split("\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
        String marker = trimmed.startsWith("```") ? "```" : "~~~";
        if (fenceMarker == null) {
          fenceMarker = marker; // open
        } else if (trimmed.startsWith(fenceMarker)) {
          fenceMarker = null; // close on the matching marker only
        }
        body.append(line).append('\n');
        continue;
      }
      Matcher heading = fenceMarker != null ? null : HEADING.matcher(line);
      if (heading != null && heading.matches()) {
        flush(sections, currentPath, body);
        body.setLength(0);
        int level = heading.group(1).length();
        String title = heading.group(2).strip();
        while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
          levels.remove(levels.size() - 1);
          titles.remove(titles.size() - 1);
        }
        levels.add(level);
        titles.add(title);
        currentPath = String.join(" > ", titles);
      } else {
        body.append(line).append('\n');
      }
    }
    flush(sections, currentPath, body);
    return sections;
  }

  private static void flush(Map<String, String> sections, String path, StringBuilder body) {
    String content = body.toString().strip();
    if (path.isEmpty() && content.isBlank()) {
      return;
    }
    sections.merge(path, content, (existing, next) -> existing + "\n" + next);
  }

  /** Whitespace-insensitive form for the modified check: per-line strip + blank-run collapse. */
  private static String normalize(String section) {
    if (section == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (String line : section.split("\n", -1)) {
      sb.append(line.strip()).append('\n');
    }
    return BLANK_RUN.matcher(sb.toString()).replaceAll("\n").strip();
  }
}
