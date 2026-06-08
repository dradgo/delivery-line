package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the single-repo {@code deliveryline.workflow.repos} binding + url→ref logic.
 */
class WorkflowPropertiesTest {

  @Test
  void repositoryRefNormalizesEveryAcceptedUrlForm() {
    assertThat(ref("dradgo/delivery-line")).isEqualTo("dradgo/delivery-line");
    assertThat(ref("dradgo/delivery-line.git")).isEqualTo("dradgo/delivery-line");
    assertThat(ref("https://github.com/dradgo/delivery-line.git"))
        .isEqualTo("dradgo/delivery-line");
    assertThat(ref("https://github.com/dradgo/delivery-line")).isEqualTo("dradgo/delivery-line");
    assertThat(ref("git@github.com:dradgo/delivery-line.git")).isEqualTo("dradgo/delivery-line");
    assertThat(ref("ssh://git@github.com/dradgo/delivery-line.git"))
        .isEqualTo("dradgo/delivery-line");
  }

  @Test
  void repositoryRefIsNullWhenUrlAbsentOrBlank() {
    assertThat(WorkflowProperties.RepoConfig.empty().repositoryRef()).isNull();
    assertThat(WorkflowProperties.RepoConfig.of(null).repositoryRef()).isNull();
    assertThat(WorkflowProperties.RepoConfig.of("   ").repositoryRef()).isNull();
  }

  @Test
  void cloneKnobsNormalizeWithDefaults() {
    WorkflowProperties.RepoConfig cfg =
        new WorkflowProperties.RepoConfig("dradgo/delivery-line", 0, null);
    assertThat(cfg.cloneDepth()).isEqualTo(1);
    assertThat(cfg.sparsePaths()).isEmpty();
    assertThat(cfg.sparseEnabled()).isFalse();
  }

  @Test
  void defaultsExposeAnEmptyRepoConfig() {
    assertThat(WorkflowProperties.defaults().repos().repositoryRef()).isNull();
  }

  private static String ref(String url) {
    return WorkflowProperties.RepoConfig.of(url).repositoryRef();
  }
}
