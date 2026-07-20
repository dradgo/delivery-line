package org.dradgo.application.workflow.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.workflow.ci.CiReadModelResolver.CiRunReadModel;
import org.dradgo.application.workflow.spi.CiRunView;
import org.dradgo.application.workflow.spi.CiStatusPort;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Story 3h-5 (AC3) — the CI detail read model, including the defensive ciChecksEnforced probe. */
class CiReadModelResolverTest {

  private CiStatusPort ciStatusPort;
  private ProjectRuntimeConfigResolver runtimeResolver;
  private ProjectConnectorResolver connectorResolver;
  private RepositoryHostAdapter adapter;
  private CiReadModelResolver resolver;

  @BeforeEach
  void setUp() {
    ciStatusPort = mock(CiStatusPort.class);
    runtimeResolver = mock(ProjectRuntimeConfigResolver.class);
    connectorResolver = mock(ProjectConnectorResolver.class);
    adapter = mock(RepositoryHostAdapter.class);
    Project project = mock(Project.class);
    when(runtimeResolver.resolveForRun(anyString())).thenReturn(project);
    when(connectorResolver.resolveRepositoryHost(any())).thenReturn(adapter);
    resolver = new CiReadModelResolver(ciStatusPort, runtimeResolver, connectorResolver);
  }

  @Test
  void surfacesLiveCiColumnsAndChecksEnforced() {
    when(ciStatusPort.readCiView("run_1"))
        .thenReturn(Optional.of(new CiRunView("failure", "sha-1", 2)));
    when(adapter.getCapabilities()).thenReturn(RepositoryHostCapabilities.githubDefaults());

    CiRunReadModel model = resolver.resolve("run_1");
    assertThat(model.ciStatus()).isEqualTo("failure");
    assertThat(model.ciHeadSha()).isEqualTo("sha-1");
    assertThat(model.ciFixLoopCount()).isEqualTo(2);
    assertThat(model.ciChecksEnforced()).isTrue();
  }

  @Test
  void neverPushedRunHasNullStatusAndZeroLoopCount() {
    when(ciStatusPort.readCiView("run_1")).thenReturn(Optional.empty());
    when(adapter.getCapabilities())
        .thenReturn(new RepositoryHostCapabilities(true, true, true, true, false, true));

    CiRunReadModel model = resolver.resolve("run_1");
    assertThat(model.ciStatus()).isNull();
    assertThat(model.ciHeadSha()).isNull();
    assertThat(model.ciFixLoopCount()).isZero();
    assertThat(model.ciChecksEnforced()).isFalse();
  }

  @Test
  void checksEnforcedDefaultsFalseWhenCapabilityProbeThrows() {
    when(ciStatusPort.readCiView("run_1"))
        .thenReturn(Optional.of(new CiRunView("pending", "sha-1", 0)));
    when(adapter.getCapabilities()).thenThrow(new RuntimeException("boom"));

    CiRunReadModel model = resolver.resolve("run_1");
    assertThat(model.ciStatus()).isEqualTo("pending");
    assertThat(model.ciChecksEnforced()).isFalse();
  }

  @Test
  void emptyReadModelIsNeutral() {
    CiRunReadModel empty = CiRunReadModel.empty();
    assertThat(empty.ciStatus()).isNull();
    assertThat(empty.ciHeadSha()).isNull();
    assertThat(empty.ciFixLoopCount()).isZero();
    assertThat(empty.ciChecksEnforced()).isFalse();
  }
}
