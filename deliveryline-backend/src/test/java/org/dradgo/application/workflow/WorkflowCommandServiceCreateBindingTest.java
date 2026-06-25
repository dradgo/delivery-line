package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.approval.ApprovalService;
import org.dradgo.application.approval.TechnicalApprovalService;
import org.dradgo.application.clarification.ClarificationService;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.WorkflowCommandFingerprintFactory;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.project.DefaultProjectSeeder;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Story 3c-6 (AC2) — create-binding coverage for {@link WorkflowCommandService#submitInternal} (all
 * collaborators mocked, exercised directly via reflection like {@code
 * WorkflowCommandServiceReplayRefTest}): a submitted run binds {@code project_id = prj_default},
 * and a submit with no default project present is rejected with {@code PROJECT_NOT_FOUND}.
 */
class WorkflowCommandServiceCreateBindingTest {

  private WorkflowRunCreatePort workflowRunCreatePort;
  private WorkflowRunReadPort workflowRunReadPort;
  private ProjectStore projectStore;
  private WorkflowCommandService service;

  @BeforeEach
  void setUp() {
    workflowRunCreatePort = mock(WorkflowRunCreatePort.class);
    workflowRunReadPort = mock(WorkflowRunReadPort.class);
    projectStore = mock(ProjectStore.class);
    service =
        new WorkflowCommandService(
            workflowRunReadPort,
            workflowRunCreatePort,
            mock(WorkflowEventWritePort.class),
            mock(WorkflowTransitionService.class),
            mock(Validator.class),
            mock(PlatformTransactionManager.class),
            mock(IdempotencyService.class),
            mock(IdempotencyKeyValidator.class),
            mock(WorkflowCommandFingerprintFactory.class),
            mock(IntegrationLinkService.class),
            mock(ApprovalService.class),
            mock(TechnicalApprovalService.class),
            mock(ClarificationService.class),
            mock(org.dradgo.application.clarification.ClarificationLifecycleService.class),
            mock(ClarificationReadPort.class),
            mock(WorkflowOrchestrationService.class),
            mock(org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort.class),
            projectStore);
  }

  @Test
  void submitBindsNewRunToDefaultProject() {
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(defaultProject()));
    when(workflowRunCreatePort.create(
            anyString(),
            eq(WorkflowState.INBOX),
            eq(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID)))
        .thenAnswer(invocation -> snapshot(invocation.getArgument(0)));
    when(workflowRunReadPort.findByPublicId(anyString())).thenReturn(Optional.empty());

    ReflectionTestUtils.invokeMethod(service, "submitInternal", command());

    ArgumentCaptor<String> projectIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(workflowRunCreatePort)
        .create(anyString(), eq(WorkflowState.INBOX), projectIdCaptor.capture());
    assertThat(projectIdCaptor.getValue())
        .isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID);
  }

  @Test
  void submitWithoutDefaultProjectRaisesProjectNotFound() {
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "submitInternal", command()))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.PROJECT_NOT_FOUND));
    verify(workflowRunCreatePort, org.mockito.Mockito.never()).create(any(), any(), any());
  }

  @Test
  void submitBindsToExplicitProjectResolvedBySlug() {
    Project acme =
        new Project(
            "prj_acme0001",
            "Acme",
            "acme",
            ProjectStatus.ACTIVE,
            "acme/widgets",
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            true,
            null,
            false,
            null,
            OffsetDateTime.parse("2026-06-20T00:00:00Z"),
            null);
    when(projectStore.findBySlug("acme")).thenReturn(Optional.of(acme));
    when(workflowRunCreatePort.create(anyString(), eq(WorkflowState.INBOX), eq("prj_acme0001")))
        .thenAnswer(invocation -> snapshot(invocation.getArgument(0)));
    when(workflowRunReadPort.findByPublicId(anyString())).thenReturn(Optional.empty());

    ReflectionTestUtils.invokeMethod(service, "submitInternal", commandWithProject("acme"));

    ArgumentCaptor<String> projectIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(workflowRunCreatePort)
        .create(anyString(), eq(WorkflowState.INBOX), projectIdCaptor.capture());
    assertThat(projectIdCaptor.getValue()).isEqualTo("prj_acme0001");
    // Explicit reference takes the slug branch — the default slug is never consulted.
    verify(projectStore, org.mockito.Mockito.never())
        .findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG);
  }

  @Test
  void submitBindsToExplicitProjectResolvedByPublicId() {
    Project acme =
        new Project(
            "prj_acme0001",
            "Acme",
            "acme",
            ProjectStatus.ACTIVE,
            "acme/widgets",
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            true,
            null,
            false,
            null,
            OffsetDateTime.parse("2026-06-20T00:00:00Z"),
            null);
    when(projectStore.findByPublicId("prj_acme0001")).thenReturn(Optional.of(acme));
    when(workflowRunCreatePort.create(anyString(), eq(WorkflowState.INBOX), eq("prj_acme0001")))
        .thenAnswer(invocation -> snapshot(invocation.getArgument(0)));
    when(workflowRunReadPort.findByPublicId(anyString())).thenReturn(Optional.empty());

    ReflectionTestUtils.invokeMethod(service, "submitInternal", commandWithProject("prj_acme0001"));

    verify(workflowRunCreatePort).create(anyString(), eq(WorkflowState.INBOX), eq("prj_acme0001"));
    // The `prj_` prefix routes to findByPublicId, not findBySlug.
    verify(projectStore, org.mockito.Mockito.never()).findBySlug("prj_acme0001");
  }

  @Test
  void submitWithUnknownExplicitProjectReferenceRaisesProjectNotFound() {
    when(projectStore.findBySlug("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    service, "submitInternal", commandWithProject("ghost")))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.PROJECT_NOT_FOUND));
    verify(workflowRunCreatePort, org.mockito.Mockito.never()).create(any(), any(), any());
  }

  private static SubmitWorkflowCommand command() {
    return new SubmitWorkflowCommand(
        "alex", ActorType.HUMAN, "idem-3c6-001", "corr-3c6-001", "DL-1");
  }

  private static SubmitWorkflowCommand commandWithProject(String projectReference) {
    return new SubmitWorkflowCommand(
        "alex", ActorType.HUMAN, "idem-3c7-001", "corr-3c7-001", "DL-1", projectReference);
  }

  private static WorkflowRunSnapshot snapshot(String publicId) {
    return new WorkflowRunSnapshot(publicId, WorkflowState.INBOX, null, 0L, 0, false);
  }

  private static Project defaultProject() {
    return new Project(
        DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID,
        DefaultProjectSeeder.DEFAULT_PROJECT_NAME,
        DefaultProjectSeeder.DEFAULT_PROJECT_SLUG,
        ProjectStatus.ACTIVE,
        "octo/hello",
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }
}
