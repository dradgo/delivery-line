package org.dradgo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalDevelopmentContractTest {

  private static final Path REPO_ROOT = findRepoRoot();
  private static final Path BACKEND_MODULE = REPO_ROOT.resolve("deliveryline-backend");

  private static Path findRepoRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("docker-compose.yml"))
          && Files.exists(candidate.resolve(".mvn"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "Could not locate repo root from " + Path.of("").toAbsolutePath());
  }

  @Test
  void dockerComposeFileDefinesExpectedPostgresContract() throws IOException {
    String composeFile = Files.readString(REPO_ROOT.resolve("docker-compose.yml"));

    assertTrue(composeFile.contains("postgres:17"));
    assertTrue(composeFile.contains("deliveryline-postgres-data"));
    assertTrue(composeFile.contains("POSTGRES_DB: deliveryline"));
    assertTrue(composeFile.contains("POSTGRES_USER: deliveryline"));
    assertTrue(composeFile.contains("POSTGRES_PASSWORD"));
    assertTrue(composeFile.contains("${POSTGRES_HOST_PORT:-5432}:5432"));
    assertTrue(composeFile.contains("healthcheck:"));
  }

  @Test
  void dockerComposeRunnerImagesAreProfileGatedBuildTargets() throws IOException {
    String composeFile = Files.readString(REPO_ROOT.resolve("docker-compose.yml"));

    // Both runner images are declared as build targets (stories 3.3 / 3.4)...
    assertTrue(composeFile.contains("codex-runner:"));
    assertTrue(composeFile.contains("claude-runner:"));
    assertTrue(composeFile.contains("dockerfile: runners/codex/Dockerfile"));
    assertTrue(composeFile.contains("dockerfile: runners/claude/Dockerfile"));

    // ...but BOTH are gated behind the "runners" profile so Spring Boot's
    // docker-compose autoconfig (`docker compose up --wait`) does not try to run
    // the one-shot runners — they exit non-zero and would abort app startup
    // (doctor-smoke / jar-smoke). Profiles are the only per-service exclusion the
    // compose lifecycle honors; build them by name or with `--profile runners`.
    int profileGates = composeFile.split("profiles: \\[\"runners\"\\]", -1).length - 1;
    assertEquals(
        2,
        profileGates,
        "both codex-runner and claude-runner must be gated behind the \"runners\" profile");
  }

  @Test
  void envExampleDocumentsPostgresPasswordAndPortContract() throws IOException {
    String envExample = Files.readString(REPO_ROOT.resolve(".env.example"));

    assertTrue(envExample.contains("POSTGRES_PASSWORD="));
    assertTrue(envExample.contains("POSTGRES_HOST_PORT=5432"));
  }

  @Test
  void localProfileUsesDockerComposeStartOnlyWithoutDatasourceUrlDuplication() throws IOException {
    String applicationLocal =
        Files.readString(BACKEND_MODULE.resolve("src/main/resources/application-local.yml"));

    assertTrue(applicationLocal.contains("lifecycle-management: start-only"));
    assertFalse(applicationLocal.contains("spring.docker.compose.file"));
    assertFalse(applicationLocal.contains("\n      file:"));
    assertFalse(applicationLocal.contains("spring.datasource.url"));
    assertFalse(applicationLocal.contains("jdbc:postgresql://"));
  }

  @Test
  void backendPomConfiguresSpringBootRunFromRepoRoot() throws IOException {
    String backendPom = Files.readString(BACKEND_MODULE.resolve("pom.xml"));
    int dockerComposeDependency =
        backendPom.indexOf("<artifactId>spring-boot-docker-compose</artifactId>");
    assertTrue(dockerComposeDependency >= 0);
    int dockerComposeDependencyEnd = backendPom.indexOf("</dependency>", dockerComposeDependency);
    assertTrue(dockerComposeDependencyEnd >= 0);

    assertTrue(
        backendPom.contains(
            "<workingDirectory>${maven.multiModuleProjectDirectory}</workingDirectory>"));
    assertTrue(backendPom.contains("<excludeDockerCompose>false</excludeDockerCompose>"));
    assertFalse(
        backendPom
            .substring(dockerComposeDependency, dockerComposeDependencyEnd)
            .contains("<optional>true</optional>"));
  }

  @Test
  void unifiedComposeAdrDocumentsSingleFileDecision() throws IOException {
    String adr =
        Files.readString(
            REPO_ROOT.resolve("docs").resolve("adr").resolve("0001-unified-compose.md"));

    assertTrue(adr.contains("docker-compose.yml"));
    assertTrue(adr.toLowerCase().contains("supersed"));
    assertFalse(Files.exists(REPO_ROOT.resolve("docker-compose.observability.yml")));
  }
}
