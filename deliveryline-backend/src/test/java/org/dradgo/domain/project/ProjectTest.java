package org.dradgo.domain.project;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Test;

class ProjectTest {

  private static final String VALID_PUBLIC_ID = "prj_demo1234";
  private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-06-20T00:00:00Z");

  private static Project newProject(
      String publicId, String name, String slug, ProjectStatus status, ConnectorKind ticketKind) {
    return new Project(
        publicId,
        name,
        slug,
        status,
        null,
        ticketKind,
        ConnectorKind.GITHUB,
        false,
        CREATED_AT,
        null);
  }

  @Test
  void constructsWithValidFieldsAndToleratesNullableColumns() {
    Project project =
        assertDoesNotThrow(
            () ->
                newProject(
                    VALID_PUBLIC_ID, "Demo", "demo", ProjectStatus.ACTIVE, ConnectorKind.LINEAR));
    assertEquals(VALID_PUBLIC_ID, project.publicId());
    assertEquals(ProjectStatus.ACTIVE, project.status());
    assertEquals(ConnectorKind.LINEAR, project.ticketSourceKind());
    assertEquals(ConnectorKind.GITHUB, project.repoHostKind());
    assertNull(project.repositoryUrl());
    assertNull(project.archivedAt());
  }

  @Test
  void rejectsBlankName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            newProject(VALID_PUBLIC_ID, "  ", "demo", ProjectStatus.ACTIVE, ConnectorKind.LINEAR));
  }

  @Test
  void rejectsBlankSlug() {
    assertThrows(
        IllegalArgumentException.class,
        () -> newProject(VALID_PUBLIC_ID, "Demo", "", ProjectStatus.ACTIVE, ConnectorKind.LINEAR));
  }

  @Test
  void rejectsNullStatus() {
    assertThrows(
        NullPointerException.class,
        () -> newProject(VALID_PUBLIC_ID, "Demo", "demo", null, ConnectorKind.LINEAR));
  }

  @Test
  void rejectsNullTicketSourceKind() {
    assertThrows(
        NullPointerException.class,
        () -> newProject(VALID_PUBLIC_ID, "Demo", "demo", ProjectStatus.ACTIVE, null));
  }

  @Test
  void rejectsNullRepoHostKind() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Project(
                VALID_PUBLIC_ID,
                "Demo",
                "demo",
                ProjectStatus.ACTIVE,
                null,
                ConnectorKind.LINEAR,
                null,
                false,
                CREATED_AT,
                null));
  }

  @Test
  void rejectsNullCreatedAt() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Project(
                VALID_PUBLIC_ID,
                "Demo",
                "demo",
                ProjectStatus.ACTIVE,
                null,
                ConnectorKind.LINEAR,
                ConnectorKind.GITHUB,
                false,
                null,
                null));
  }

  @Test
  void rejectsPublicIdWithWrongPrefix() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                newProject(
                    "cred_demo1234", "Demo", "demo", ProjectStatus.ACTIVE, ConnectorKind.LINEAR));
    assertEquals(DomainErrorCode.INVALID_ID_PREFIX, error.errorCode());
  }

  @Test
  void projectCredentialPrefixIsRegisteredAndAcceptsCredId() {
    // Positive exercise of the cred_ / PROJECT_CREDENTIAL prefix tuple — the Project record only
    // validates prj_ ids, so cred_ would otherwise appear solely as a negative case above.
    assertDoesNotThrow(
        () -> PublicIdPrefixes.require("cred_demo1234", PublicIdPrefixes.PROJECT_CREDENTIAL));
  }
}
