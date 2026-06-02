package org.dradgo.application.diagnostics;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dradgo.application.diagnostics.spi.DoctorProbePort;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DoctorService {

  private static final Logger log = LoggerFactory.getLogger(DoctorService.class);

  public static final int SCHEMA_VERSION = 1;

  public static final String CHECK_JAVA_VERSION = "java-version";
  public static final String CHECK_SPRING_PROFILE = "spring-profile";
  public static final String CHECK_POSTGRES_CONNECTIVITY = "postgres-connectivity";
  public static final String CHECK_FLYWAY_STATE = "flyway-state";
  public static final String CHECK_ARTIFACT_DIRECTORY = "artifact-directory";
  public static final String CHECK_CONFIG_FILE_PERMISSIONS = "config-file-permissions";
  public static final String CHECK_DOCKER_AVAILABILITY = "docker-availability";
  public static final String CHECK_RUNNER_IMAGE_AVAILABILITY = "runner-image-availability";
  public static final String CHECK_RUNNER_SECRETS = "runner-secrets";
  public static final String CHECK_REST_BIND_ADDRESS = "rest-bind-address";
  public static final String CHECK_FRONTEND_ASSET_PRESENCE = "frontend-asset-presence";
  public static final String CHECK_SUPPORTED_ENVIRONMENT = "supported-environment";
  public static final String CHECK_GITHUB_AUTH = "github-auth";

  public static final List<String> STATIC_ORDER =
      List.of(
          CHECK_JAVA_VERSION,
          CHECK_SPRING_PROFILE,
          CHECK_POSTGRES_CONNECTIVITY,
          CHECK_FLYWAY_STATE,
          CHECK_ARTIFACT_DIRECTORY,
          CHECK_CONFIG_FILE_PERMISSIONS,
          CHECK_DOCKER_AVAILABILITY,
          CHECK_RUNNER_IMAGE_AVAILABILITY,
          CHECK_RUNNER_SECRETS,
          CHECK_REST_BIND_ADDRESS,
          CHECK_FRONTEND_ASSET_PRESENCE,
          CHECK_SUPPORTED_ENVIRONMENT,
          CHECK_GITHUB_AUTH);

  private static final Map<String, String> REMEDIATION =
      Map.ofEntries(
          Map.entry(
              CHECK_POSTGRES_CONNECTIVITY,
              "Run 'docker compose up -d postgres' and re-check, or verify spring.datasource.url credentials."),
          Map.entry(
              CHECK_FLYWAY_STATE,
              "Inspect 'mvn flyway:info' output and resolve pending or failed migrations."),
          Map.entry(
              CHECK_ARTIFACT_DIRECTORY,
              "Ensure the artifact root directory exists and is writable."),
          Map.entry(
              CHECK_CONFIG_FILE_PERMISSIONS,
              "Run 'chmod 600 .env' (and similar) to remove world-readable bits."),
          Map.entry(
              CHECK_DOCKER_AVAILABILITY,
              "Start Docker Desktop (or 'systemctl start docker') if you intend to run real runners in Epic 3."),
          Map.entry(
              CHECK_RUNNER_SECRETS,
              "Set the agent-provider API key(s) in .env — see .env.example (CODEX_API_KEY/OPENAI_API_KEY"
                  + " for Codex, CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY for Claude) — then restart and re-check."),
          Map.entry(
              CHECK_REST_BIND_ADDRESS,
              "Bind server.address to a loopback (e.g. 127.0.0.1) or free the configured port."),
          Map.entry(
              CHECK_SPRING_PROFILE,
              "Set spring.profiles.active=local in application-local.yml (or pass --spring.profiles.active=local)."),
          Map.entry(
              CHECK_GITHUB_AUTH,
              "Set a valid GITHUB_TOKEN (PAT with repo + pull_requests:write scope) in .env — see"
                  + " docs/adr/0021-github-write-scope.md — then restart and re-check. This check only"
                  + " runs under the github-real profile."),
          Map.entry(
              CHECK_SUPPORTED_ENVIRONMENT,
              "To run DeliveryLine on this OS+shell combination, see docs/supported-environments.md for currently"
                  + " supported combinations — this combination is not tested and may require contributions to the"
                  + " scripts under `scripts/`."));

  private static final String SHAREABLE_REDACTED = DataClassification.SHAREABLE_REDACTED.value();

  private final DoctorProbePort probes;
  private final RedactionPolicyService redaction;
  private final Clock clock;

  @Autowired
  public DoctorService(DoctorProbePort probes, RedactionPolicyService redaction) {
    this(probes, redaction, Clock.systemUTC());
  }

  public DoctorService(DoctorProbePort probes, RedactionPolicyService redaction, Clock clock) {
    this.probes = probes;
    this.redaction = redaction;
    this.clock = clock;
  }

  public DiagnosticsReport runDiagnostics(DoctorRunRequest request) {
    DoctorRunRequest resolved = request == null ? DoctorRunRequest.all() : request;
    log.info(
        "doctor diagnostics started correlationId={} requestedChecks={}",
        resolved.correlationId(),
        describeRequestedChecks(resolved));
    long startNanos = System.nanoTime();

    validateSubsetRequest(resolved);

    List<String> effectiveOrder = effectiveOrder(resolved);
    List<DiagnosticsCheck> checks = new ArrayList<>(effectiveOrder.size());
    for (String name : effectiveOrder) {
      if (resolved.exclude().contains(name)) {
        checks.add(
            redactCheck(
                new DiagnosticsCheck(
                    name, DiagnosticsStatus.SKIP, "Excluded via --exclude", null, null, Map.of())));
        continue;
      }
      ProbeResult result = runSingleProbe(name);
      checks.add(toDiagnosticsCheck(name, result));
    }

    DiagnosticsStatus overall = aggregate(checks);
    DiagnosticsReport report =
        new DiagnosticsReport(SCHEMA_VERSION, OffsetDateTime.now(clock), overall, checks);

    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    log.info(
        "doctor diagnostics finished correlationId={} overallStatus={} checksRun={} durationMs={}",
        resolved.correlationId(),
        overall,
        checks.size(),
        elapsedMs);
    return report;
  }

  private void validateSubsetRequest(DoctorRunRequest request) {
    if (!request.only().isEmpty() && !request.exclude().isEmpty()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("rule", "--only and --exclude are mutually exclusive");
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "--only and --exclude are mutually exclusive",
          details);
    }
    Set<String> known = new LinkedHashSet<>(STATIC_ORDER);
    Set<String> requested = new LinkedHashSet<>();
    requested.addAll(request.only());
    requested.addAll(request.exclude());
    for (String name : requested) {
      if (!known.contains(name)) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("unknownCheck", name);
        details.put("knownChecks", STATIC_ORDER);
        throw new DomainException(
            DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Unknown doctor check name: " + name, details);
      }
    }
  }

  private List<String> effectiveOrder(DoctorRunRequest request) {
    if (request.only().isEmpty()) {
      return STATIC_ORDER;
    }
    List<String> retained = new ArrayList<>(STATIC_ORDER.size());
    for (String name : STATIC_ORDER) {
      if (request.only().contains(name)) {
        retained.add(name);
      }
    }
    return Collections.unmodifiableList(retained);
  }

  private ProbeResult runSingleProbe(String name) {
    try {
      return switch (name) {
        case CHECK_JAVA_VERSION -> probes.probeJavaVersion();
        case CHECK_SPRING_PROFILE -> probes.probeSpringProfiles();
        case CHECK_POSTGRES_CONNECTIVITY -> probes.probePostgresConnectivity();
        case CHECK_FLYWAY_STATE -> probes.probeFlywayState();
        case CHECK_ARTIFACT_DIRECTORY -> probes.probeArtifactDirectory();
        case CHECK_CONFIG_FILE_PERMISSIONS -> probes.probeConfigFilePermissions();
        case CHECK_DOCKER_AVAILABILITY -> probes.probeDockerAvailability();
        case CHECK_RUNNER_IMAGE_AVAILABILITY ->
            ProbeResult.skip("Runner image availability check populated in story 3.1");
        case CHECK_RUNNER_SECRETS -> probes.probeRunnerSecrets();
        case CHECK_REST_BIND_ADDRESS -> probes.probeRestBindAddress();
        case CHECK_FRONTEND_ASSET_PRESENCE ->
            ProbeResult.skip("Frontend asset check populated in story 2.28");
        case CHECK_SUPPORTED_ENVIRONMENT -> probes.probeSupportedEnvironment();
        case CHECK_GITHUB_AUTH -> probes.probeGitHubAuth();
        default -> ProbeResult.skip("Unknown check: " + name);
      };
    } catch (RuntimeException re) {
      log.warn("doctor probe '{}' raised an unexpected exception; surfacing as FAIL", name, re);
      Map<String, String> details = new LinkedHashMap<>();
      details.put("probeException", re.getClass().getSimpleName());
      return new ProbeResult(
          DiagnosticsStatus.FAIL,
          "Probe '" + name + "' raised an unexpected exception",
          DomainErrorCode.INTERNAL_ERROR.value(),
          details);
    }
  }

  private DiagnosticsCheck toDiagnosticsCheck(String name, ProbeResult result) {
    String remediation =
        result.status() == DiagnosticsStatus.FAIL ? remediationFor(name, result.details()) : null;
    return redactCheck(
        new DiagnosticsCheck(
            name,
            result.status(),
            result.summary(),
            remediation,
            result.errorCode(),
            result.details()));
  }

  private String remediationFor(String name, Map<String, String> details) {
    if (CHECK_ARTIFACT_DIRECTORY.equals(name)) {
      String artifactRoot = details.get("artifactRoot");
      if (artifactRoot != null && !artifactRoot.isBlank()) {
        return "Ensure the artifact root directory exists and is writable: " + artifactRoot + ".";
      }
    }
    if (CHECK_SUPPORTED_ENVIRONMENT.equals(name)) {
      String os = details.get("os");
      String shell = details.get("shell");
      if (os != null && !os.isBlank() && shell != null && !shell.isBlank()) {
        return "To run DeliveryLine on "
            + os
            + "+"
            + shell
            + ", see docs/supported-environments.md for currently supported combinations —"
            + " this combination is not tested and may require contributions to the scripts"
            + " under `scripts/`.";
      }
    }
    return REMEDIATION.get(name);
  }

  private DiagnosticsCheck redactCheck(DiagnosticsCheck raw) {
    String summary = redactString(raw.summary());
    String remediation = redactString(raw.remediation());
    Map<String, String> details = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : raw.details().entrySet()) {
      details.put(entry.getKey(), redactString(entry.getValue()));
    }
    return new DiagnosticsCheck(
        raw.name(), raw.status(), summary, remediation, raw.errorCode(), details);
  }

  private String redactString(String value) {
    if (value == null) {
      return null;
    }
    RedactionResult result = redaction.redact(value, SHAREABLE_REDACTED);
    return result.sanitizedText();
  }

  private DiagnosticsStatus aggregate(List<DiagnosticsCheck> checks) {
    boolean anyWarn = false;
    boolean anyPass = false;
    for (DiagnosticsCheck check : checks) {
      switch (check.status()) {
        case FAIL -> {
          return DiagnosticsStatus.FAIL;
        }
        case WARN -> anyWarn = true;
        case PASS -> anyPass = true;
        case SKIP -> {
          /* no-op */
        }
      }
    }
    if (anyWarn) {
      return DiagnosticsStatus.WARN;
    }
    if (anyPass) {
      return DiagnosticsStatus.PASS;
    }
    return DiagnosticsStatus.PASS;
  }

  private String describeRequestedChecks(DoctorRunRequest request) {
    if (!request.only().isEmpty()) {
      return "only=" + request.only();
    }
    if (!request.exclude().isEmpty()) {
      return "exclude=" + request.exclude();
    }
    return "all";
  }
}
