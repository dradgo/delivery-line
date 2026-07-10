package org.dradgo.adapters.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.OffsetDateTime;
import org.dradgo.adapters.persistence.entity.ProjectEntity;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Story 3d-1 review patch — read-path robustness for {@code reviewer_model_kind}. DD-1 leaves the
 * column without a DB CHECK, so a blank value is persistable; the {@link Project} record's
 * non-blank-if-set invariant would otherwise make such a row (and any {@code findAll} over it)
 * un-readable. {@link ProjectEntityMapper#toDomain} coerces blank to {@code null} ("no reviewer").
 */
class ProjectEntityMapperTest {

  private final ProjectEntityMapper mapper = new ProjectEntityMapper();

  private static ProjectEntity baseEntity() {
    ProjectEntity entity = new ProjectEntity();
    entity.setPublicId("prj_abcd1234");
    entity.setName("Demo");
    entity.setSlug("demo");
    entity.setStatus(ProjectStatus.ACTIVE);
    entity.setTicketSourceKind(ConnectorKind.LINEAR);
    entity.setRepoHostKind(ConnectorKind.GITHUB);
    entity.setOpenspecEnabled(false);
    entity.setReviewerGatingEnabled(false);
    // Story 3h-4 — push_mode is NOT NULL default 'auto' in the DB; a persisted row always carries a
    // value, so the mapper test's in-memory row must set it too (the getter fail-fast-parses it).
    entity.setPushMode(org.dradgo.domain.registry.PushMode.AUTO);
    entity.setAutoCreatePullRequest(true);
    entity.setCreatedAt(OffsetDateTime.parse("2026-06-21T00:00:00Z"));
    return entity;
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", "   "})
  void blankReviewerModelKindReadsBackAsNullRatherThanThrowing(String blank) {
    ProjectEntity entity = baseEntity();
    entity.setReviewerModelKind(blank);

    Project project = mapper.toDomain(entity);

    assertThat(project.reviewerModelKind()).isNull();
  }

  @Test
  void nullReviewerModelKindStaysNull() {
    ProjectEntity entity = baseEntity();
    entity.setReviewerModelKind(null);

    assertThat(mapper.toDomain(entity).reviewerModelKind()).isNull();
  }

  @Test
  void setReviewerModelKindRoundTrips() {
    ProjectEntity entity = baseEntity();
    entity.setReviewerModelKind("claude-opus");

    Project project = mapper.toDomain(entity);

    assertThat(project.reviewerModelKind()).isEqualTo("claude-opus");
    assertThatCode(() -> mapper.toDomain(entity)).doesNotThrowAnyException();
  }
}
