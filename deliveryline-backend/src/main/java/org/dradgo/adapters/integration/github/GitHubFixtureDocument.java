package org.dradgo.adapters.integration.github;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.dradgo.application.integration.github.GitHubBranch;
import org.dradgo.application.integration.github.GitHubPullRequest;
import org.dradgo.application.integration.github.GitHubRepository;

/**
 * Wire-form of a GitHub mock fixture JSON file (kept project-internal — not shared with the runner
 * contracts module). Bundles a repository, its single open PR, and the PR's source branch (AC3).
 * Conforms to {@code schemas/github-fixture-mock.v1.schema.json}.
 *
 * <p>Adapter-only translation type: not exposed through the {@link GitHubRepository}/{@link
 * GitHubPullRequest}/{@link GitHubBranch} port surface. Mirrors {@code LinearTicketFixtureDocument}
 * ({@code @JsonCreator}, ISO-8601 parsing).
 */
final class GitHubFixtureDocument {

  private final RepositoryDocument repository;
  private final PullRequestDocument pullRequest;
  private final BranchDocument branch;

  @JsonCreator
  GitHubFixtureDocument(
      @JsonProperty("repository") RepositoryDocument repository,
      @JsonProperty("pullRequest") PullRequestDocument pullRequest,
      @JsonProperty("branch") BranchDocument branch) {
    this.repository = repository;
    this.pullRequest = pullRequest;
    this.branch = branch;
  }

  GitHubFixture toDomain() {
    if (repository == null || pullRequest == null || branch == null) {
      throw new IllegalStateException(
          "GitHub mock fixture must declare repository, pullRequest, and branch");
    }
    return new GitHubFixture(repository.toDomain(), pullRequest.toDomain(), branch.toDomain());
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

    GitHubRepository toDomain() {
      return new GitHubRepository(repoRef, fullName, defaultBranch, url);
    }
  }

  static final class PullRequestDocument {
    private final String prRef;
    private final String repoRef;
    private final int number;
    private final String sourceBranch;
    private final String state;
    private final String url;
    private final String createdAt;

    @JsonCreator
    PullRequestDocument(
        @JsonProperty("prRef") String prRef,
        @JsonProperty("repoRef") String repoRef,
        @JsonProperty("number") int number,
        @JsonProperty("sourceBranch") String sourceBranch,
        @JsonProperty("state") String state,
        @JsonProperty("url") String url,
        @JsonProperty("createdAt") String createdAt) {
      this.prRef = prRef;
      this.repoRef = repoRef;
      this.number = number;
      this.sourceBranch = sourceBranch;
      this.state = state;
      this.url = url;
      this.createdAt = createdAt;
    }

    GitHubPullRequest toDomain() {
      return new GitHubPullRequest(
          prRef, repoRef, number, sourceBranch, state, url, parseInstant("createdAt", createdAt));
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

    GitHubBranch toDomain() {
      return new GitHubBranch(repoRef, name, headSha);
    }
  }

  private static Instant parseInstant(String field, String raw) {
    if (raw == null) {
      throw new IllegalStateException("GitHub mock fixture missing " + field);
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException error) {
      throw new IllegalStateException(
          "GitHub mock fixture has invalid ISO-8601 in " + field + ": " + raw, error);
    }
  }
}
