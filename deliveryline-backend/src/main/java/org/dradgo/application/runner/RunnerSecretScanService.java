package org.dradgo.application.runner;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.spi.WorkspaceScanFile;
import org.dradgo.application.security.RedactionCategory;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3.5 AC4/AC5 — post-execution workspace secret scan. Runs two detectors over every regular
 * file under {@code input/}, {@code output/}, {@code logs/}:
 *
 * <ol>
 *   <li>{@link RedactionPolicyService} for the <em>known</em> secret shapes (GitHub/Linear/auth
 *       header/env block/PEM/SSH) — the single sanctioned detection engine (Trap T7).
 *   <li>a <b>mandatory</b> exact-substring containment check against the literal agent-provider key
 *       value(s) this dispatch injected. The provider keys are arbitrary strings that match NO
 *       {@link RedactionCategory} regex, so pattern scanning alone would not catch this story's own
 *       secrets. A literal value compare is NOT a credential <em>pattern</em>, so the {@code
 *       CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY} rule still holds (Trap T7).
 * </ol>
 *
 * <p>This service lives in {@code application.runner} so it may depend on {@link
 * RunnerSecretsService} (ArchUnit AC10 confines that dependency to {@code application.runner..} +
 * {@code adapters.runner.docker..}). It exposes no secret value in logs, events, or its {@link
 * ScanOutcome}: a substring-only hit is labelled with the synthetic category {@code
 * injected_provider_key}, never the value.
 */
@Service
public class RunnerSecretScanService {

  private static final Logger log = LoggerFactory.getLogger(RunnerSecretScanService.class);

  // Lenient, read-only mapper used solely to recognize + parse an authored JSON artifact for
  // structural secret analysis (never for credential detection — that stays in the sanctioned
  // RedactionPolicyService engine, Trap T7). Default strict config: only well-formed JSON parses.
  private static final com.fasterxml.jackson.databind.ObjectMapper SCAN_JSON_MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /**
   * Synthetic category name for a literal injected-provider-key substring hit (never the value).
   */
  static final String INJECTED_PROVIDER_KEY_CATEGORY = "injected_provider_key";

  /**
   * The fuzzy / context-dependent redaction categories that are suppressed when scanning content
   * that merely <em>echoes</em> the target repo rather than being a runner-authored artifact: the
   * cloned {@code repo/} working tree (EXECUTION stage) AND the agent's own transcript logs ({@code
   * logs/runner.stdout} / {@code logs/runner.stderr}, which re-print whatever files the agent
   * {@code cat}s/{@code sed}s from that repo). These heuristic shapes (loose {@code KEY=value} /
   * high-entropy values, {@code "password": …} fields, {@code ?token=…} query params, file paths,
   * env-var blocks) match ordinary code/config and produced a stream of bogus {@code
   * runner_secret_leak} failures (FIN-14 / FIN-16 from repo files; the {@code
   * leakedFile=logs/runner.stderr categories=[SECRET_FIELD]} run from a transcript echo of a
   * committed {@code password: …} line) that failed otherwise-successful runs. The precise
   * detectors still run over these files: the mandatory injected-provider-key substring check (the
   * secret WE inject) plus the strongly-structured credential SHAPES (GitHub / Linear tokens, SSH
   * &amp; PEM private keys, Authorization headers) — those are unambiguous secrets in any context
   * and stay leak-worthy even in committed code or a transcript.
   */
  private static final java.util.Set<RedactionCategory> FUZZY_HEURISTIC_CATEGORIES =
      java.util.EnumSet.of(
          RedactionCategory.ENV_VALUE,
          RedactionCategory.ENVIRONMENT_BLOCK,
          RedactionCategory.SECRET_FIELD,
          RedactionCategory.QUERY_SECRET,
          RedactionCategory.LOCAL_PATH);

  /** Workspace-relative prefix of cloned-repo working-tree files (see {@code REPO_SUBDIR}). */
  private static final String REPO_RELATIVE_PREFIX = "repo/";

  /**
   * Workspace-relative paths of the agent's raw transcript streams. These are not runner-authored
   * artifacts — they capture the agent's stdout/stderr, which echoes arbitrary target-repo content
   * the agent inspects, so they false-positive the {@link #FUZZY_HEURISTIC_CATEGORIES} exactly like
   * the {@code repo/} working tree. The precise detectors still run over them.
   */
  private static final java.util.Set<String> TRANSCRIPT_LOG_RELATIVE_PATHS =
      java.util.Set.of("logs/runner.stdout", "logs/runner.stderr");

  private final RunnerWorkspaceStore workspaceStore;
  private final RedactionPolicyService redactionPolicyService;
  private final RunnerSecretsService runnerSecretsService;

  public RunnerSecretScanService(
      RunnerWorkspaceStore workspaceStore,
      RedactionPolicyService redactionPolicyService,
      RunnerSecretsService runnerSecretsService) {
    this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
    this.runnerSecretsService =
        Objects.requireNonNull(runnerSecretsService, "runnerSecretsService");
  }

  /**
   * Outcome of a workspace scan. On a leak, {@link #leakedFile()} is the workspace-relative path of
   * the first offending file and {@link #detectedCategories()} carries category NAMES only (never
   * values).
   */
  public record ScanOutcome(
      boolean leakDetected, String leakedFile, List<String> detectedCategories) {

    public ScanOutcome {
      detectedCategories = detectedCategories == null ? List.of() : List.copyOf(detectedCategories);
    }

    public static ScanOutcome clean() {
      return new ScanOutcome(false, null, List.of());
    }
  }

  /**
   * Scan the workspace for this execution. Returns {@link ScanOutcome#clean()} when no detector
   * fires, otherwise a leak outcome naming the first offending file + detected category names.
   */
  public ScanOutcome scanWorkspace(
      String runnerExecutionId, RunnerKind runnerKind, RunnerStage stage, String workflowRunId) {
    Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
    Objects.requireNonNull(runnerKind, "runnerKind");
    Objects.requireNonNull(stage, "stage");

    // The cloned repo/ working tree is scanned only for a read-write (EXECUTION) stage, where the
    // runner authors changes that are later pushed. A read-only INVESTIGATION stage cannot modify
    // the pristine clone, so scanning third-party upstream content would only false-positive the
    // fuzzy redaction heuristics (e.g. ENV_VALUE on innocuous key=value config) — the precise
    // detectors that matter (injected-key substring, known credential shapes) still run over the
    // runner's own input/output/logs.
    boolean includeRepoWorkingTree = stage == RunnerStage.EXECUTION;
    List<WorkspaceScanFile> files =
        workspaceStore.readFilesForSecretScan(runnerExecutionId, includeRepoWorkingTree);
    Set<String> injectedValues =
        resolveInjectedValues(runnerExecutionId, runnerKind, stage, workflowRunId);

    log.info(
        "runner secret scan start runnerExecutionId={} workflowRunId={} runnerKind={} fileCount={} injectedValueCount={}",
        runnerExecutionId,
        workflowRunId,
        runnerKind.value(),
        files.size(),
        injectedValues.size());

    for (WorkspaceScanFile file : files) {
      Set<String> categories = new LinkedHashSet<>();
      // Files that merely ECHO the target repo — the cloned repo/ working tree and the agent's own
      // transcript streams (runner.stdout/stderr, which re-print whatever the agent cat/seds) — are
      // real source/config, not runner-authored artifacts. The fuzzy heuristic categories
      // (ENV_VALUE / SECRET_FIELD etc.) false-positive on them (e.g. a committed `password: …`
      // line), so suppress those and keep only the precise detectors (structured credential shapes
      // below + the injected-key substring (ii)). The runner's own input/output (and any other
      // logs/ file) keep the full strict scan.
      boolean echoesRepoContent =
          file.relativePath().startsWith(REPO_RELATIVE_PREFIX)
              || TRANSCRIPT_LOG_RELATIVE_PATHS.contains(file.relativePath());

      // (i) known-shape detection via the sanctioned engine. An authored JSON artifact (e.g. the
      // runner's output/runner-result.v1.json) is analyzed STRUCTURALLY: the fuzzy YAML/env
      // heuristics false-positive on natural-language PROSE inside string VALUES — the
      // security-conscious plan text "…without password:" / "password=<invalid>" that quarantined
      // an otherwise-clean run (run_009f4595) captured the JSON `","` / a literal placeholder as
      // the "secret value". Structural analysis pardons the fuzzy categories on VALUES only, while
      // the secret-named-KEY check and the precise credential shapes (tokens, PEM/SSH keys, auth
      // headers) still fire — so a real leak is still caught. Non-JSON content (config lines,
      // transcripts, source) and repo/transcript echoes keep the raw-text scan unchanged.
      com.fasterxml.jackson.databind.JsonNode structuredJson =
          echoesRepoContent ? null : tryParseJsonContainer(file.text());
      if (structuredJson != null) {
        RedactionResult result =
            redactionPolicyService.redactStructured(
                structuredJson, DataClassification.LOCAL_ONLY.value(), FUZZY_HEURISTIC_CATEGORIES);
        for (RedactionCategory category : result.detectedCategories()) {
          categories.add(category.name());
        }
      } else {
        RedactionResult result =
            redactionPolicyService.redact(file.text(), DataClassification.LOCAL_ONLY.value());
        for (RedactionCategory category : result.detectedCategories()) {
          if (echoesRepoContent && FUZZY_HEURISTIC_CATEGORIES.contains(category)) {
            continue;
          }
          categories.add(category.name());
        }
      }

      // (ii) mandatory literal substring containment of THIS dispatch's injected provider key(s).
      for (String value : injectedValues) {
        if (file.text().contains(value)) {
          categories.add(INJECTED_PROVIDER_KEY_CATEGORY);
          break;
        }
      }

      if (!categories.isEmpty()) {
        List<String> categoryNames = List.copyOf(categories);
        log.warn(
            "runner secret leak detected runnerExecutionId={} workflowRunId={} file={} categories={}",
            runnerExecutionId,
            workflowRunId,
            file.relativePath(),
            categoryNames);
        return new ScanOutcome(true, file.relativePath(), categoryNames);
      }
    }

    log.info(
        "runner secret scan clean runnerExecutionId={} workflowRunId={} fileCount={}",
        runnerExecutionId,
        workflowRunId,
        files.size());
    return ScanOutcome.clean();
  }

  /**
   * Re-resolve the literal value(s) this dispatch injected so the substring detector has something
   * to compare against. Reads the Spring Environment per call (no caching — AC9). If the secret is
   * absent at scan time (e.g. rotated to blank mid-run), degrade to known-shape detection only with
   * a WARN — never let a resolution failure mask the rest of the scan.
   */
  private Set<String> resolveInjectedValues(
      String runnerExecutionId, RunnerKind runnerKind, RunnerStage stage, String workflowRunId) {
    Set<String> values = new LinkedHashSet<>();
    try {
      for (String value :
          runnerSecretsService.resolveSecretsForRunner(runnerKind, stage, workflowRunId).values()) {
        if (value != null && !value.isBlank()) {
          values.add(value);
        }
      }
    } catch (DomainException missing) {
      if (missing.errorCode() != DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING) {
        throw missing;
      }
      log.warn(
          "runner secret scan substring-check degraded runnerExecutionId={} reason=injected_secret_absent_at_scan_time",
          runnerExecutionId);
    }
    return values;
  }

  /**
   * Parse {@code text} as a JSON object/array so an authored JSON artifact can be scanned
   * structurally (prose in string VALUES is not misread as a credential). Returns {@code null} when
   * the content is not a JSON container — a bare string, a config line ({@code DB_PASSWORD=…}), a
   * JSONL transcript, or source — so those keep the raw-text scan (and its fuzzy detection)
   * unchanged. Gated on a leading {@code '{'}/{@code '['} so ordinary text is never probed, and
   * tolerant of malformed input (a parse failure returns {@code null}).
   */
  private com.fasterxml.jackson.databind.JsonNode tryParseJsonContainer(String text) {
    if (text == null) {
      return null;
    }
    String trimmed = text.stripLeading();
    if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
      return null;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode node = SCAN_JSON_MAPPER.readTree(text);
      return (node != null && node.isContainerNode()) ? node : null;
    } catch (com.fasterxml.jackson.core.JacksonException parseFailure) {
      return null;
    }
  }

  /**
   * Trap T10 — preserve a leaked-secret workspace for diagnostics by writing the {@code
   * .quarantine} marker the cleanup job honors. {@code reason} carries the relative file path +
   * category names only (never a value).
   */
  public void quarantine(String runnerExecutionId, String reason) {
    workspaceStore.writeQuarantineMarker(runnerExecutionId, reason);
  }
}
