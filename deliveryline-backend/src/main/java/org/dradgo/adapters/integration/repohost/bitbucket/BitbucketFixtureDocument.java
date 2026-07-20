package org.dradgo.adapters.integration.repohost.bitbucket;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryRef;

/**
 * Wire-form of a Bitbucket mock fixture JSON file (kept project-internal — not shared with the
 * runner contracts module). Bundles a repository, its single open PR, and the PR's source branch
 * (story 3i-3 / FR82). Bitbucket twin of {@code GitHubFixtureDocument}.
 *
 * <p>Adapter-only translation type: not exposed through the {@link Repository}/{@link
 * PullRequest}/{@link Branch} port surface. The JSON keeps neutral {@code repoRef}/{@code prRef}
 * string fields; they are wrapped into the neutral {@link RepositoryRef}/{@link PullRequestRef}
 * value records here.
 */
final class BitbucketFixtureDocument {

  private final RepositoryDocument repository;
  private final PullRequestDocument pullRequest;
  private final BranchDocument branch;

  @JsonCreator
  BitbucketFixtureDocument(
      @JsonProperty("repository") RepositoryDocument repository,
      @JsonProperty("pullRequest") PullRequestDocument pullRequest,
      @JsonProperty("branch") BranchDocument branch) {
    this.repository = repository;
    this.pullRequest = pullRequest;
    this.branch = branch;
  }

  BitbucketFixture toDomain() {
    if (repository == null || pullRequest == null || branch == null) {
      throw new IllegalStateException(
          "Bitbucket mock fixture must declare repository, pullRequest, and branch");
    }
    return new BitbucketFixture(repository.toDomain(), pullRequest.toDomain(), branch.toDomain());
  }

  static final class RepositoryDocument {
    private final String repoRef;
    private final String fullName;
    private final String defaultBranch;
    private final String url;

    @JsonCreator
    RepositoryDocument(
        @JsonProperty("repoRef") String repoRef,
        @JsonProperty("fullName") String fullName,
        @JsonProperty("defaultBranch") String defaultBranch,
        @JsonProperty("url") String url) {
      this.repoRef = repoRef;
      this.fullName = fullName;
      this.defaultBranch = defaultBranch;
      this.url = url;
    }

    Repository toDomain() {
      return new Repository(RepositoryRef.of(repoRef), fullName, defaultBranch, url);
    }
  }

  static final class PullRequestDocument {
    private final String prRef;
    private final String repoRef;
    private final int number;
    private final String sourceBranch;
    private final String state;
    private final boolean merged;
    private final String url;
    private final String createdAt;

    @JsonCreator
    PullRequestDocument(
        @JsonProperty("prRef") String prRef,
        @JsonProperty("repoRef") String repoRef,
        @JsonProperty("number") int number,
        @JsonProperty("sourceBranch") String sourceBranch,
        @JsonProperty("state") String state,
        @JsonProperty("merged") Boolean merged,
        @JsonProperty("url") String url,
        @JsonProperty("createdAt") String createdAt) {
      this.prRef = prRef;
      this.repoRef = repoRef;
      this.number = number;
      this.sourceBranch = sourceBranch;
      this.state = state;
      // Optional in the fixture JSON — the happy fixtures are open PRs (merged absent → false).
      this.merged = Boolean.TRUE.equals(merged);
      this.url = url;
      this.createdAt = createdAt;
    }

    PullRequest toDomain() {
      return new PullRequest(
          PullRequestRef.of(prRef),
          RepositoryRef.of(repoRef),
          number,
          sourceBranch,
          state,
          merged,
          url,
          parseInstant("createdAt", createdAt));
    }
  }

  static final class BranchDocument {
    private final String repoRef;
    private final String name;
    private final String headSha;

    @JsonCreator
    BranchDocument(
        @JsonProperty("repoRef") String repoRef,
        @JsonProperty("name") String name,
        @JsonProperty("headSha") String headSha) {
      this.repoRef = repoRef;
      this.name = name;
      this.headSha = headSha;
    }

    Branch toDomain() {
      return new Branch(RepositoryRef.of(repoRef), name, headSha);
    }
  }

  private static Instant parseInstant(String field, String raw) {
    if (raw == null) {
      throw new IllegalStateException("Bitbucket mock fixture missing " + field);
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException error) {
      throw new IllegalStateException(
          "Bitbucket mock fixture has invalid ISO-8601 in " + field + ": " + raw, error);
    }
  }
}
