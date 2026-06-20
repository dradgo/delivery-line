package org.dradgo.application.project;

import java.util.Optional;
import org.dradgo.domain.project.Project;

/**
 * At-use-time per-project credential lookup (story 3c-3 AC6). 3c-3 ships only the
 * <strong>seam</strong>: there is no implementation bean today, so {@code ProjectConnectorResolver}
 * resolves this port lazily and adapters fall back to their existing host-env secret path ({@code
 * runnerSecretsService} / {@code GITHUB_TOKEN}). The encrypted store backing it lands in story
 * 3c-5, after the 3c-4 security-review gate, and introduces its own typed connector-role enum (do
 * not invent one here — {@code role} stays a {@code String} for now).
 *
 * <p>Contract: secrets are returned for <strong>immediate use only</strong> and must never be
 * retained in a field, logged, or written to an event/artifact/export by the caller.
 */
public interface ProjectCredentialSource {

  /**
   * Resolve the secret bound to {@code (project, role)} for immediate connector use, or {@link
   * Optional#empty()} when none is configured. {@code role} is the connector role (e.g. {@code
   * "ticket_source"} / {@code "repo_host"}); the typed role enum is 3c-5's to introduce.
   */
  Optional<String> resolveSecret(Project project, String role);
}
