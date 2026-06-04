package org.dradgo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.application.security.RedactionCategory;
import org.junit.jupiter.api.Test;

/**
 * Story 3.7 (AC4 / AC5 / Decision D6 / Trap T6) — fast-tier guard that the hand-authored Logstash
 * pipeline ({@code infra/observability/logstash/pipelines/deliveryline.conf}) stays in parity with
 * the Java redaction taxonomy.
 *
 * <p>The heavy round-trip "does Logstash actually strip a source-missed secret" assertion is the
 * Docker-tier {@code *IT}; THIS test runs in the fast tier and catches the cheap-but-critical
 * drift: if a {@link RedactionCategory} is added to the backend without a matching second-pass
 * pattern in the pipeline, this fails. It also pins the classification drop filter (AC4).
 *
 * <p><b>Engine fidelity (review pass 3).</b> Logstash {@code mutate { gsub }} compiles its patterns
 * with JRuby/Joni (Ruby {@link java.util.regex regular-expression} semantics), NOT {@code
 * java.util.regex}. The two differ in ways that silently break redaction yet pass a naive Java
 * test: Ruby replacement backreferences are {@code \1} (Java uses {@code $1}); Ruby's
 * dot-matches-newline flag is {@code (?m)} (Java uses {@code (?s)}, which is a {@code RegexpError}
 * in Ruby); and Ruby {@code ^}/{@code $} are always line-anchored (Java needs {@code (?m)}).
 *
 * <ul>
 *   <li>{@link #gsubUsesLogstashRubyRegexSyntaxNotJava()} lints the conf directly — it rejects Java
 *       {@code $n} backreferences and the Java-only {@code (?s)} flag, the exact drift that a third
 *       review pass found had slipped past two prior passes.
 *   <li>{@link #everySourceMissedSecretIsStrippedByTheExtractedGsubChain()} parses the {@code gsub}
 *       array, <em>translates each Ruby pattern/replacement into its Java equivalent</em>, then
 *       runs the full chain over a known-secret fixture per category — so a pattern that does not
 *       match its secret (or fails to compile) fails the build in the fast tier, validating the
 *       behavior Logstash will actually exhibit rather than a Java look-alike.
 * </ul>
 */
class LogstashRedactionParityTest {

  private static final String PIPELINE_RELATIVE_PATH =
      "infra/observability/logstash/pipelines/deliveryline.conf";

  /** A leading inline-flag group, e.g. {@code (?i)} or {@code (?im)}, at the start of a pattern. */
  private static final Pattern LEADING_FLAGS = Pattern.compile("^\\(\\?([a-z]+)\\)");

  /** A Java-style {@code $1} backreference — invalid in a Logstash/Ruby gsub replacement. */
  private static final Pattern JAVA_BACKREF = Pattern.compile("\\$\\d");

  @Test
  void everyRedactionCategoryHasAMatchingLogstashPlaceholder() throws IOException {
    String pipeline = readPipeline();
    for (RedactionCategory category : RedactionCategory.values()) {
      assertThat(pipeline)
          .as(
              "Logstash second-pass redaction must cover RedactionCategory."
                  + category.name()
                  + " ("
                  + category.placeholder()
                  + ") — drift from SensitivePayloadAnalyzer is Trap T6")
          .contains(category.placeholder());
    }
  }

  @Test
  void pipelineDropsLocalOnlyClassifiedDocuments() throws IOException {
    String pipeline = readPipeline();
    assertThat(pipeline.replaceAll("\\s+", " "))
        .as("AC4 — local-only documents must be dropped before indexing")
        .contains("if [classification] == \"local-only\" { drop {} }");
  }

  @Test
  void pipelineDeclaresBothIngestPathsAndElasticsearchOutput() throws IOException {
    String pipeline = readPipeline();
    assertThat(pipeline).contains("port => 5044"); // AC3a TCP/JSON
    assertThat(pipeline).contains("/ingest/runner-logs/"); // AC3b D5 file ingest
    assertThat(pipeline).contains("elasticsearch {");
  }

  /**
   * Review pass 3 — guard the engine-syntax drift directly. Logstash gsub runs on JRuby/Joni, so a
   * Java {@code $1} replacement is emitted literally (the secret is over-redacted but the
   * surrounding text is corrupted) and a Java-only {@code (?s)} flag is a {@code RegexpError} that
   * breaks the pattern (or the whole pipeline). A naive Java parity test cannot see either; this
   * lint can.
   */
  @Test
  void gsubUsesLogstashRubyRegexSyntaxNotJava() throws IOException {
    List<RawGsub> chain = extractRawGsubChain(readPipeline());
    assertThat(chain).as("the conf must declare a non-trivial gsub redaction chain").isNotEmpty();

    for (RawGsub gsub : chain) {
      assertThat(JAVA_BACKREF.matcher(gsub.replacement()).find())
          .as(
              "Logstash gsub replacement '%s' uses a Java $n backreference; JRuby/Joni requires \\n"
                  + " (a $n is emitted literally, mangling the redacted line)",
              gsub.replacement())
          .isFalse();

      Matcher lead = LEADING_FLAGS.matcher(gsub.regex());
      if (lead.find()) {
        assertThat(lead.group(1))
            .as(
                "Logstash gsub pattern '%s' uses the Java-only (?s) flag; JRuby/Joni uses (?m) for"
                    + " dot-matches-newline and raises RegexpError on (?s)",
                gsub.regex())
            .doesNotContain("s");
      }
    }
  }

  /**
   * The real T6 drift gate. Parse the {@code gsub} substitution chain out of the conf, translate
   * each Ruby pattern/replacement to its Java equivalent, run the WHOLE chain (in document order)
   * over a known-secret fixture per {@link RedactionCategory}, and assert the secret is actually
   * replaced by that category's placeholder. A removed/broken/typo'd pattern now fails here, in the
   * fast tier — not only in the single-category Docker-tier IT.
   */
  @Test
  void everySourceMissedSecretIsStrippedByTheExtractedGsubChain() throws IOException {
    List<Gsub> chain = compileChain(extractRawGsubChain(readPipeline()));
    assertThat(chain).as("the conf must declare a non-trivial gsub redaction chain").isNotEmpty();

    Map<RedactionCategory, Fixture> fixtures = sourceMissedFixtures();
    assertThat(fixtures.keySet())
        .as(
            "every RedactionCategory needs a non-vacuous parity fixture — a new category without one"
                + " would otherwise slip past this gate")
        .containsExactlyInAnyOrder(RedactionCategory.values());

    for (RedactionCategory category : RedactionCategory.values()) {
      Fixture fixture = fixtures.get(category);
      String redacted = applyChain(chain, fixture.raw());
      assertThat(redacted)
          .as(
              "the Logstash second pass must redact a source-missed "
                  + category.name()
                  + " to "
                  + category.placeholder()
                  + " — drift/broken pattern is Trap T6")
          .contains(category.placeholder())
          .doesNotContain(fixture.sensitive());
    }
  }

  /**
   * One known secret per category that source-side redaction (story 3.6) is assumed to have missed.
   * Each is fed through the FULL extracted gsub chain; {@code sensitive} is the raw fragment that
   * must NOT survive.
   */
  private static Map<RedactionCategory, Fixture> sourceMissedFixtures() {
    Map<RedactionCategory, Fixture> fixtures = new EnumMap<>(RedactionCategory.class);
    fixtures.put(
        RedactionCategory.LINEAR_API_KEY,
        new Fixture("api key lin_api_abcdefghij0123456789 here", "lin_api_"));
    fixtures.put(
        RedactionCategory.GITHUB_TOKEN,
        new Fixture("token ghp_abcdefghij0123456789AB leaked", "ghp_"));
    fixtures.put(
        RedactionCategory.SSH_PRIVATE_KEY,
        new Fixture(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n"
                + "-----END OPENSSH PRIVATE KEY-----",
            "b3BlbnNzaC1rZXktdjEAAAAA"));
    fixtures.put(
        RedactionCategory.SSH_PUBLIC_KEY,
        new Fixture("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAdeadbeef host", "AAAAC3NzaC1lZDI1NTE5"));
    fixtures.put(
        RedactionCategory.AUTHORIZATION_HEADER,
        new Fixture("Authorization: Bearer abc.def.ghijklmnop", "abc.def.ghijklmnop"));
    fixtures.put(
        RedactionCategory.IDEMPOTENCY_KEY,
        new Fixture("Idempotency-Key: 11111111-2222-3333-4444", "11111111-2222-3333-4444"));
    fixtures.put(
        RedactionCategory.PEM_PRIVATE_KEY,
        new Fixture(
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEAdeadbeef\n"
                + "-----END RSA PRIVATE KEY-----",
            "MIIEpAIBAAKCAQEAdeadbeef"));
    fixtures.put(
        RedactionCategory.PEM_CERTIFICATE_WITH_PRIVATE_KEY,
        new Fixture(
            "-----BEGIN CERTIFICATE-----\nMIICcertbody\n-----END CERTIFICATE-----\n"
                + "-----BEGIN PRIVATE KEY-----\nMIIKkeybody\n-----END PRIVATE KEY-----",
            "MIIKkeybody"));
    fixtures.put(
        RedactionCategory.QUERY_SECRET,
        new Fixture(
            "GET https://api.example/x?token=supersecretvalue123 200", "supersecretvalue123"));
    fixtures.put(
        RedactionCategory.ENV_VALUE, new Fixture("MY_SECRET=topsecretvalue", "topsecretvalue"));
    fixtures.put(
        RedactionCategory.SECRET_FIELD,
        new Fixture("{\"password\":\"hunter2plaintext\"}", "hunter2plaintext"));
    fixtures.put(
        RedactionCategory.LOCAL_PATH,
        new Fixture("workspace at /home/alice/projects/secret-repo cloned", "/home/alice"));
    fixtures.put(
        RedactionCategory.ENVIRONMENT_BLOCK,
        new Fixture("Environment:\nFOO=barvalue\nBAZ=quxvalue\n", "barvalue"));
    return fixtures;
  }

  private static String applyChain(List<Gsub> chain, String input) {
    String out = input;
    for (Gsub gsub : chain) {
      out = gsub.pattern().matcher(out).replaceAll(gsub.replacement());
    }
    return out;
  }

  /** Compile each raw (Ruby) gsub into a Java-equivalent (pattern, replacement) pair. */
  private static List<Gsub> compileChain(List<RawGsub> raw) {
    List<Gsub> chain = new ArrayList<>();
    for (RawGsub gsub : raw) {
      chain.add(
          new Gsub(compileRubyAsJava(gsub.regex()), rubyReplacementToJava(gsub.replacement())));
    }
    return chain;
  }

  /**
   * Compile a JRuby/Joni (Ruby) regex as the closest {@code java.util.regex} equivalent: Ruby
   * {@code ^}/{@code $} are always line-anchored, so {@link Pattern#MULTILINE} is always set; Ruby
   * {@code (?m)} means dot-matches-newline, mapping to {@link Pattern#DOTALL}; {@code (?i)} maps to
   * {@link Pattern#CASE_INSENSITIVE}. The leading inline-flag group is stripped before compiling so
   * the (Java-incompatible) Ruby flag spelling does not reach {@link Pattern#compile}.
   */
  private static Pattern compileRubyAsJava(String rubyRegex) {
    int flags = Pattern.MULTILINE; // Ruby ^/$ are always line boundaries
    String body = rubyRegex;
    Matcher lead = LEADING_FLAGS.matcher(body);
    if (lead.find()) {
      String f = lead.group(1);
      if (f.contains("i")) {
        flags |= Pattern.CASE_INSENSITIVE;
      }
      if (f.contains("m")) {
        flags |= Pattern.DOTALL; // Ruby (?m) == dot matches newline
      }
      body = body.substring(lead.end());
    }
    return Pattern.compile(body, flags);
  }

  /**
   * Translate a Ruby gsub replacement to Java {@link Matcher#replaceAll} syntax: Ruby {@code \1}
   * backreferences become Java {@code $1}; any literal {@code $} or {@code \} is escaped so it is
   * not reinterpreted by the Java replacement engine.
   */
  private static String rubyReplacementToJava(String rubyReplacement) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < rubyReplacement.length(); i++) {
      char c = rubyReplacement.charAt(i);
      if (c == '\\'
          && i + 1 < rubyReplacement.length()
          && Character.isDigit(rubyReplacement.charAt(i + 1))) {
        out.append('$').append(rubyReplacement.charAt(i + 1));
        i++;
      } else if (c == '$') {
        out.append("\\$"); // literal $ — escape for Java replacement
      } else if (c == '\\') {
        out.append("\\\\"); // literal backslash — escape for Java replacement
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * Parse the {@code mutate { gsub => [ ... ] }} array into raw (field, pattern, replacement)
   * string triples exactly as written in the conf — no engine translation. The conf single-quotes
   * patterns so backslashes are literal; comments ({@code # ...}) and the always-{@code "message"}
   * field token are handled by the tokenizer.
   */
  private static List<RawGsub> extractRawGsubChain(String pipeline) {
    int arrayStart = pipeline.indexOf("gsub => [");
    assertThat(arrayStart).as("conf must declare a gsub array").isGreaterThanOrEqualTo(0);
    List<String> tokens = tokenizeUntilArrayClose(pipeline, arrayStart + "gsub => [".length());
    assertThat(tokens.size() % 3)
        .as("gsub array must be triples of field, pattern, replacement")
        .isZero();

    List<RawGsub> chain = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i += 3) {
      assertThat(tokens.get(i))
          .as("every gsub triple targets the message field")
          .isEqualTo("message");
      chain.add(new RawGsub(tokens.get(i + 1), tokens.get(i + 2)));
    }
    return chain;
  }

  /**
   * Scan from {@code start} collecting quoted tokens (either {@code '...'} or {@code "..."}) until
   * the first top-level {@code ]} that closes the array. A token's opening quote is the only thing
   * that closes it, so the {@code "} chars inside the single-quoted JSON SECRET_FIELD pattern and
   * the {@code ]} chars inside regex character classes are consumed as token content, never
   * mistaken for structure. {@code #} outside a token starts a comment to end-of-line.
   */
  private static List<String> tokenizeUntilArrayClose(String text, int start) {
    List<String> tokens = new ArrayList<>();
    int i = start;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == ']') {
        break; // top-level close of the gsub array
      }
      if (c == '#') {
        int nl = text.indexOf('\n', i);
        i = (nl < 0) ? text.length() : nl + 1;
        continue;
      }
      if (c == '\'' || c == '"') {
        int close = text.indexOf(c, i + 1);
        assertThat(close).as("unterminated quoted token in gsub array").isGreaterThan(i);
        tokens.add(text.substring(i + 1, close));
        i = close + 1;
        continue;
      }
      i++;
    }
    return tokens;
  }

  /** A compiled (Java-equivalent) gsub step. */
  private record Gsub(Pattern pattern, String replacement) {}

  /** A raw gsub step exactly as written in the conf (Ruby syntax, untranslated). */
  private record RawGsub(String regex, String replacement) {}

  private record Fixture(String raw, String sensitive) {}

  private static String readPipeline() throws IOException {
    Path file = locatePipeline();
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  /** Walk up from the module working directory to the repo root to find the infra pipeline file. */
  private static Path locatePipeline() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
      Path candidate = dir.resolve(PIPELINE_RELATIVE_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Could not locate " + PIPELINE_RELATIVE_PATH + " from " + System.getProperty("user.dir"));
  }
}
