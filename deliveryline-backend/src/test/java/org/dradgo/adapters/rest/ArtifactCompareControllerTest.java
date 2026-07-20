package org.dradgo.adapters.rest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.dradgo.application.compare.ArtifactSummary;
import org.dradgo.application.compare.ChangeKind;
import org.dradgo.application.compare.DeltaSummary;
import org.dradgo.application.compare.MarkdownChangeBlock;
import org.dradgo.application.compare.RevisionDelta;
import org.dradgo.application.compare.RevisionDeltaService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 4.19 (AC7/AC8/AC10) — thin-adapter contract for {@code GET
 * /api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}}. The controller mocks the service; delta
 * computation is exercised in the service unit test + real-PG IT. Problem Details assertions pin
 * {@code code} + status only (never human text).
 */
@WebMvcTest(controllers = ArtifactCompareController.class)
class ArtifactCompareControllerTest {

  private static final String PATH = "/api/v1/artifacts/art_aaaa1111/compare/art_bbbb2222";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private RevisionDeltaService revisionDeltaService;

  private static RevisionDelta specFixture() {
    return new RevisionDelta(
        "spec",
        new ArtifactSummary(
            1, OffsetDateTime.parse("2026-07-15T00:00:00Z"), "dev", "SHA-256:abc123abc123"),
        new ArtifactSummary(
            2, OffsetDateTime.parse("2026-07-15T01:00:00Z"), "reviewer", "SHA-256:def456def456"),
        new DeltaSummary(1, 0, 0, 1),
        List.of(new MarkdownChangeBlock("Design", ChangeKind.MODIFIED, "old body", "new body")),
        false,
        null);
  }

  @Test
  void returnsTypedDeltaShapeOn200() throws Exception {
    when(revisionDeltaService.computeDelta(eq("art_aaaa1111"), eq("art_bbbb2222")))
        .thenReturn(specFixture());

    mockMvc
        .perform(get(PATH).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.artifactType").value("spec"))
        .andExpect(jsonPath("$.revisionA.version").value(1))
        .andExpect(jsonPath("$.revisionA.producedByActor").value("dev"))
        .andExpect(jsonPath("$.revisionA.checksum").value("SHA-256:abc123abc123"))
        .andExpect(jsonPath("$.revisionB.version").value(2))
        .andExpect(jsonPath("$.summary.changedRegionCount").value(1))
        .andExpect(jsonPath("$.summary.modifiedCount").value(1))
        .andExpect(jsonPath("$.noMeaningfulDiff").value(false))
        .andExpect(jsonPath("$.linkedDiffReferences").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.changes[0].blockType").value("markdown"))
        .andExpect(jsonPath("$.changes[0].changeKind").value("modified"))
        .andExpect(jsonPath("$.changes[0].sectionPath").value("Design"))
        .andExpect(jsonPath("$.changes[0].priorText").value("old body"))
        .andExpect(jsonPath("$.changes[0].currentText").value("new body"));
  }

  @Test
  void lineageMismatchSurfaces400() throws Exception {
    when(revisionDeltaService.computeDelta(eq("art_aaaa1111"), eq("art_bbbb2222")))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ARTIFACT_LINEAGE_MISMATCH,
                "not one lineage",
                Map.of("artifactIdA", "art_aaaa1111", "artifactIdB", "art_bbbb2222")));

    mockMvc
        .perform(get(PATH).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("ARTIFACT_LINEAGE_MISMATCH"))
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void malformedIdSurfaces400() throws Exception {
    when(revisionDeltaService.computeDelta(eq("art_aaaa1111"), eq("art_bbbb2222")))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_ID_PREFIX, "bad id", Map.of("value", "art_bbbb2222")));

    mockMvc
        .perform(get(PATH).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ID_PREFIX"))
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void unknownArtifactSurfaces404() throws Exception {
    when(revisionDeltaService.computeDelta(eq("art_aaaa1111"), eq("art_bbbb2222")))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
                "no such artifact",
                Map.of("artifactId", "art_bbbb2222")));

    mockMvc
        .perform(get(PATH).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("ARTIFACT_RECORD_NOT_FOUND"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void unavailablePayloadSurfaces503() throws Exception {
    when(revisionDeltaService.computeDelta(eq("art_aaaa1111"), eq("art_bbbb2222")))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
                "payload unavailable",
                Map.of("artifactId", "art_aaaa1111")));

    mockMvc
        .perform(get(PATH).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("ARTIFACT_PAYLOAD_UNAVAILABLE"))
        .andExpect(jsonPath("$.status").value(503));
  }
}
