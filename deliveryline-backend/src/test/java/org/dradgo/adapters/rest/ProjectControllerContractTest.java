package org.dradgo.adapters.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.project.CreateProjectCommand;
import org.dradgo.application.project.ProjectConnectivityService;
import org.dradgo.application.project.ProjectConnectivityService.CheckResult;
import org.dradgo.application.project.ProjectConnectivityService.CheckStatus;
import org.dradgo.application.project.ProjectConnectivityService.TestConnectionResult;
import org.dradgo.application.project.ProjectCredentialService;
import org.dradgo.application.project.ProjectManagementService;
import org.dradgo.application.security.CredentialCipherException;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ConnectorRole;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.RunnerKind;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 3c-8 (AC9) — per-endpoint contract test for {@code ProjectController}. Covers the CRUD
 * round-trip surfaces, write-only credential handling (no secret in any response), idempotency
 * (replay returns the same result without re-executing), error mapping (slug conflict 409,
 * not-found 404, master-key 503, unsupported kind 400), and the connection-test per-check shape.
 *
 * <p>Assertions on {@code code} / {@code status} / machine-readable {@code details} only — never on
 * human {@code title} / {@code detail} text.
 */
@WebMvcTest(controllers = ProjectController.class)
class ProjectControllerContractTest {

  private static final String PROJECT_ID = "prj_acme0001";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProjectManagementService projectManagementService;
  @MockitoBean private ProjectConnectivityService projectConnectivityService;
  @MockitoBean private ProjectCredentialService projectCredentialService;
  @MockitoBean private IdempotencyService idempotencyService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;

  private static Project project(String publicId, String slug, ProjectStatus status) {
    return new Project(
        publicId,
        "Acme Widgets",
        slug,
        status,
        "https://github.com/acme/widgets",
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-21T00:00:00Z"),
        null);
  }

  @Test
  void listProjectsReturnsBareArray() throws Exception {
    when(projectManagementService.listProjects())
        .thenReturn(List.of(project(PROJECT_ID, "acme-widgets", ProjectStatus.ACTIVE)));
    when(projectCredentialService.isConfigured(anyString(), any())).thenReturn(false);

    mockMvc
        .perform(get("/api/v1/projects").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].id").value(PROJECT_ID))
        .andExpect(jsonPath("$[0].slug").value("acme-widgets"))
        .andExpect(jsonPath("$[0].status").value("active"));
  }

  @Test
  void createProjectReturns201WithAllowedActionsAndCredentialPresenceNoSecret() throws Exception {
    when(projectManagementService.createProject(any()))
        .thenReturn(project(PROJECT_ID, "acme-widgets", ProjectStatus.ACTIVE));
    when(projectCredentialService.isConfigured(eq(PROJECT_ID), eq(ConnectorRole.TICKET_SOURCE)))
        .thenReturn(true);
    when(projectCredentialService.isConfigured(eq(PROJECT_ID), eq(ConnectorRole.REPO_HOST)))
        .thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Acme Widgets\",\"slug\":\"acme-widgets\","
                        + "\"repositoryUrl\":\"https://github.com/acme/widgets\","
                        + "\"ticketSourceKind\":\"linear\",\"repoHostKind\":\"github\","
                        + "\"openspecEnabled\":false,\"buildStageEnabled\":false,\"lintStageEnabled\":false}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(PROJECT_ID))
        .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.hasItem("disable")))
        .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.hasItem("edit")))
        .andExpect(jsonPath("$.credentials[0].role").value("ticket_source"))
        .andExpect(jsonPath("$.credentials[0].status").value("configured"))
        .andExpect(jsonPath("$.credentials[1].role").value("repo_host"))
        .andExpect(jsonPath("$.credentials[1].status").value("not_configured"))
        .andExpect(jsonPath("$.credentials[2].role").value("reviewer"))
        .andExpect(jsonPath("$.credentials[2].status").value("not_configured"))
        .andExpect(jsonPath("$.secret").doesNotExist());
  }

  @Test
  void createProjectThreadsRunnerKindAndReturnsIt() throws Exception {
    Project manualProject =
        new Project(
            PROJECT_ID,
            "Acme Widgets",
            "acme-widgets",
            ProjectStatus.ACTIVE,
            null,
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            RunnerKind.MANUAL,
            OffsetDateTime.parse("2026-06-21T00:00:00Z"),
            null);
    when(projectManagementService.createProject(any())).thenReturn(manualProject);
    when(projectCredentialService.isConfigured(anyString(), any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Acme Widgets\",\"slug\":\"acme-widgets\","
                        + "\"ticketSourceKind\":\"linear\",\"repoHostKind\":\"github\","
                        + "\"openspecEnabled\":false,\"buildStageEnabled\":false,\"lintStageEnabled\":false,\"runnerKind\":\"manual\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.runnerKind").value("manual"));

    org.mockito.ArgumentCaptor<CreateProjectCommand> command =
        org.mockito.ArgumentCaptor.forClass(CreateProjectCommand.class);
    verify(projectManagementService).createProject(command.capture());
    org.assertj.core.api.Assertions.assertThat(command.getValue().runnerKind()).isEqualTo("manual");
  }

  @Test
  void createProjectThreadsStepRunnerKindsAndReturnsThem() throws Exception {
    Project withSteps =
        new Project(
            PROJECT_ID,
            "Acme Widgets",
            "acme-widgets",
            ProjectStatus.ACTIVE,
            null,
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            null,
            OffsetDateTime.parse("2026-06-21T00:00:00Z"),
            null,
            Map.of(
                org.dradgo.domain.registry.ProjectRunnerStep.SPEC, RunnerKind.CODEX,
                org.dradgo.domain.registry.ProjectRunnerStep.PR_OUTPUT, RunnerKind.MANUAL));
    when(projectManagementService.createProject(any())).thenReturn(withSteps);
    when(projectCredentialService.isConfigured(anyString(), any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Acme Widgets\",\"slug\":\"acme-widgets\","
                        + "\"ticketSourceKind\":\"linear\",\"repoHostKind\":\"github\","
                        + "\"openspecEnabled\":false,\"buildStageEnabled\":false,\"lintStageEnabled\":false,"
                        + "\"stepRunnerKinds\":{\"spec\":\"codex\",\"prOutput\":\"manual\"}}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.stepRunnerKinds.spec").value("codex"))
        .andExpect(jsonPath("$.stepRunnerKinds.prOutput").value("manual"));

    org.mockito.ArgumentCaptor<CreateProjectCommand> command =
        org.mockito.ArgumentCaptor.forClass(CreateProjectCommand.class);
    verify(projectManagementService).createProject(command.capture());
    org.assertj.core.api.Assertions.assertThat(command.getValue().stepRunnerKinds())
        .containsEntry("spec", "codex")
        .containsEntry("prOutput", "manual");
  }

  @Test
  void createProjectWithIdempotencyKeyReservesExecutesAndCompletes() throws Exception {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(projectManagementService.createProject(any()))
        .thenReturn(project(PROJECT_ID, "acme-widgets", ProjectStatus.ACTIVE));
    when(projectCredentialService.isConfigured(anyString(), any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/projects")
                .header("Idempotency-Key", "create-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Acme Widgets\",\"slug\":\"acme-widgets\","
                        + "\"ticketSourceKind\":\"linear\",\"repoHostKind\":\"github\","
                        + "\"openspecEnabled\":false,\"buildStageEnabled\":false,\"lintStageEnabled\":false}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(PROJECT_ID));

    verify(projectManagementService, times(1)).createProject(any());
    verify(idempotencyService).complete(eq("create-key-1"), eq(PROJECT_ID), any());
  }

  @Test
  void createProjectIdempotentReplayReturnsExistingWithoutReExecuting() throws Exception {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, PROJECT_ID));
    when(projectManagementService.getProject(eq(PROJECT_ID)))
        .thenReturn(project(PROJECT_ID, "acme-widgets", ProjectStatus.ACTIVE));
    when(projectCredentialService.isConfigured(anyString(), any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/projects")
                .header("Idempotency-Key", "create-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Acme Widgets\",\"slug\":\"acme-widgets\","
                        + "\"ticketSourceKind\":\"linear\",\"repoHostKind\":\"github\","
                        + "\"openspecEnabled\":false,\"buildStageEnabled\":false,\"lintStageEnabled\":false}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(PROJECT_ID));

    verify(projectManagementService, never()).createProject(any());
  }

  @Test
  void createProjectDuplicateSlugReturns409() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("slug", "acme-widgets");
    when(projectManagementService.createProject(any()))
        .thenThrow(
            new DomainException(DomainErrorCode.PROJECT_SLUG_CONFLICT, "slug taken", details));

    mockMvc
        .perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Acme\",\"slug\":\"acme-widgets\","
                        + "\"ticketSourceKind\":\"linear\",\"repoHostKind\":\"github\","
                        + "\"openspecEnabled\":false,\"buildStageEnabled\":false,\"lintStageEnabled\":false}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("PROJECT_SLUG_CONFLICT"));
  }

  @Test
  void getProjectNotFoundReturns404() throws Exception {
    when(projectManagementService.getProject(eq(PROJECT_ID)))
        .thenThrow(new DomainException(DomainErrorCode.PROJECT_NOT_FOUND, "no project", Map.of()));

    mockMvc
        .perform(get("/api/v1/projects/{id}", PROJECT_ID).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
  }

  @Test
  void updateProjectReturns200() throws Exception {
    when(projectManagementService.updateProject(eq(PROJECT_ID), any()))
        .thenReturn(project(PROJECT_ID, "acme-widgets", ProjectStatus.ACTIVE));
    when(projectCredentialService.isConfigured(anyString(), any())).thenReturn(false);

    mockMvc
        .perform(
            put("/api/v1/projects/{id}", PROJECT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Renamed\",\"ticketSourceKind\":\"linear\","
                        + "\"repoHostKind\":\"github\",\"openspecEnabled\":true,\"buildStageEnabled\":false,\"lintStageEnabled\":false}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(PROJECT_ID));
  }

  @Test
  void updateProjectSurfacesReviewerModelKind() throws Exception {
    Project bound =
        new Project(
            PROJECT_ID,
            "Acme Widgets",
            "acme-widgets",
            ProjectStatus.ACTIVE,
            "https://github.com/acme/widgets",
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            "claude",
            false,
            null,
            OffsetDateTime.parse("2026-06-21T00:00:00Z"),
            null);
    when(projectManagementService.updateProject(eq(PROJECT_ID), any())).thenReturn(bound);
    when(projectCredentialService.isConfigured(anyString(), any())).thenReturn(false);

    mockMvc
        .perform(
            put("/api/v1/projects/{id}", PROJECT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Renamed\",\"ticketSourceKind\":\"linear\","
                        + "\"repoHostKind\":\"github\",\"openspecEnabled\":true,\"buildStageEnabled\":false,\"lintStageEnabled\":false,"
                        + "\"reviewerModelKind\":\"claude\"}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewerModelKind").value("claude"));
  }

  @Test
  void disableDefaultProjectReturns400() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("reason", "default_project_immutable");
    when(projectManagementService.disableProject(eq("prj_default")))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_COMMAND_PAYLOAD, "default cannot be disabled", details));

    mockMvc
        .perform(
            post("/api/v1/projects/{id}/disable", "prj_default").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.reason").value("default_project_immutable"));
  }

  @Test
  void enableProjectReturns200() throws Exception {
    when(projectManagementService.enableProject(eq(PROJECT_ID)))
        .thenReturn(project(PROJECT_ID, "acme-widgets", ProjectStatus.ACTIVE));
    when(projectCredentialService.isConfigured(anyString(), any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/projects/{id}/enable", PROJECT_ID).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(PROJECT_ID))
        .andExpect(jsonPath("$.status").value("active"));
  }

  @Test
  void setCredentialReturnsNonSecretConfirmation() throws Exception {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(projectCredentialService.setCredential(
            eq(PROJECT_ID), eq(ConnectorRole.TICKET_SOURCE), eq("lin_secret_value")))
        .thenReturn("cred_abc123");

    mockMvc
        .perform(
            put("/api/v1/projects/{id}/credentials/{role}", PROJECT_ID, "ticket_source")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"lin_secret_value\"}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ticket_source"))
        .andExpect(jsonPath("$.status").value("configured"))
        .andExpect(jsonPath("$.credentialId").value("cred_abc123"))
        .andExpect(jsonPath("$.secret").doesNotExist());

    verify(projectCredentialService, times(1))
        .setCredential(eq(PROJECT_ID), eq(ConnectorRole.TICKET_SOURCE), eq("lin_secret_value"));
    verify(idempotencyService).complete(eq("key-1"), eq("cred_abc123"), any());
  }

  @Test
  void setCredentialReplayReturnsSameCredentialWithoutReExecuting() throws Exception {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, "cred_abc123"));

    mockMvc
        .perform(
            put("/api/v1/projects/{id}/credentials/{role}", PROJECT_ID, "ticket_source")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"lin_secret_value\"}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.credentialId").value("cred_abc123"));

    verify(projectCredentialService, never()).setCredential(anyString(), any(), anyString());
  }

  @Test
  void setCredentialMissingIdempotencyKeyReturns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/projects/{id}/credentials/{role}", PROJECT_ID, "ticket_source")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"x\"}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  @Test
  void setCredentialBadRoleReturns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/projects/{id}/credentials/{role}", PROJECT_ID, "not_a_role")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"x\"}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());

    verify(projectCredentialService, never()).setCredential(anyString(), any(), anyString());
  }

  @Test
  void setCredentialMasterKeyAbsentReturns503() throws Exception {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(projectCredentialService.setCredential(anyString(), any(), anyString()))
        .thenThrow(new IllegalStateException("master key absent"));

    mockMvc
        .perform(
            put("/api/v1/projects/{id}/credentials/{role}", PROJECT_ID, "repo_host")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"x\"}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("CREDENTIAL_MASTER_KEY_UNCONFIGURED"));

    verify(idempotencyService).complete(eq("key-1"), eq(null), any());
  }

  @Test
  void setCredentialNeverEmitsTheSecretToLogs() throws Exception {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(projectCredentialService.setCredential(
            eq(PROJECT_ID), eq(ConnectorRole.TICKET_SOURCE), eq("lin_secret_value")))
        .thenReturn("cred_abc123");

    ListAppender<ILoggingEvent> logs = attachRootAppender();
    try {
      mockMvc
          .perform(
              put("/api/v1/projects/{id}/credentials/{role}", PROJECT_ID, "ticket_source")
                  .header("Idempotency-Key", "key-1")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"secret\":\"lin_secret_value\"}")
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk());
    } finally {
      detachRootAppender(logs);
    }
    assertNoLogContains(logs, "lin_secret_value");
  }

  @Test
  void cipherFailureMapsTo500AndNeverEmitsTheSecretToLogs() throws Exception {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(projectCredentialService.setCredential(anyString(), any(), anyString()))
        .thenThrow(new CredentialCipherException("decrypt failed"));

    ListAppender<ILoggingEvent> logs = attachRootAppender();
    try {
      mockMvc
          .perform(
              put("/api/v1/projects/{id}/credentials/{role}", PROJECT_ID, "repo_host")
                  .header("Idempotency-Key", "key-1")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"secret\":\"rh_secret_value\"}")
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    } finally {
      detachRootAppender(logs);
    }
    assertNoLogContains(logs, "rh_secret_value");
    verify(idempotencyService).complete(eq("key-1"), eq(null), any());
  }

  private static ListAppender<ILoggingEvent> attachRootAppender() {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    return appender;
  }

  private static void detachRootAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
  }

  private static void assertNoLogContains(ListAppender<ILoggingEvent> logs, String secret) {
    for (ILoggingEvent event : logs.list) {
      org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
          .as("no log line at any level may contain the credential plaintext")
          .doesNotContain(secret);
    }
  }

  @Test
  void testConnectionReturnsPerCheckResults() throws Exception {
    TestConnectionResult result =
        new TestConnectionResult(
            List.of(
                new CheckResult("repository_reachable", CheckStatus.PASS, "ok"),
                new CheckResult("ticket_source_auth", CheckStatus.SKIPPED, "degraded"),
                new CheckResult(
                    "repository_host_auth", CheckStatus.FAIL, "authentication failed")));
    when(projectConnectivityService.testConnection(eq(PROJECT_ID))).thenReturn(result);

    mockMvc
        .perform(
            post("/api/v1/projects/{id}/test-connection", PROJECT_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checks[0].check").value("repository_reachable"))
        .andExpect(jsonPath("$.checks[0].status").value("pass"))
        .andExpect(jsonPath("$.checks[1].status").value("skipped"))
        .andExpect(jsonPath("$.checks[2].status").value("fail"));
  }

  @Test
  void testConnectionUnsupportedKindReturns400() throws Exception {
    when(projectConnectivityService.testConnection(eq(PROJECT_ID)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND, "no adapter", Map.of()));

    mockMvc
        .perform(
            post("/api/v1/projects/{id}/test-connection", PROJECT_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONNECTOR_KIND"));
  }
}
