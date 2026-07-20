package org.dradgo.adapters.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.dradgo.domain.registry.WorkflowState;

@Entity
@Table(name = "workflow_runs")
public class WorkflowRunEntity {

  public static WorkflowRunEntity create(String publicId, WorkflowState currentState) {
    return create(publicId, currentState, null);
  }

  // Story 3c-6 (AC2) — bind the run to a project at insert. projectId is nullable here only so the
  // pre-3c-6 test fixtures (which never set it) keep compiling; the production create path
  // (WorkflowCommandService.submitInternal) always resolves the default project first and passes a
  // non-null prj_default.
  public static WorkflowRunEntity create(
      String publicId, WorkflowState currentState, String projectId) {
    return create(publicId, currentState, projectId, null);
  }

  public static WorkflowRunEntity create(
      String publicId, WorkflowState currentState, String projectId, String parentRunId) {
    WorkflowRunEntity entity = new WorkflowRunEntity();
    entity.publicId = publicId;
    entity.currentState = Objects.requireNonNull(currentState, "currentState").value();
    entity.projectId = projectId;
    entity.parentRunId = parentRunId;
    return entity;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @Column(name = "current_state", nullable = false)
  private String currentState;

  // Story 3c-6 (AC2) — V17 added workflow_runs.project_id (nullable text FK -> projects.public_id).
  // Mapped here so new runs bind to the resolved (default) project at insert and the backfill
  // UPDATE
  // can target it. Still nullable at the column level (legacy rows backfilled at startup).
  @Column(name = "project_id")
  private String projectId;

  @Column(name = "parent_run_id")
  private String parentRunId;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "archived_at")
  private OffsetDateTime archivedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version = 0L;

  // Story 2.10 / V7: counter of spec-rejection loops on this run. Increments on every
  // successful ApprovalService.rejectSpec. Drives the escalation-marker threshold check.
  @Column(name = "spec_rejection_loop_count", nullable = false)
  private int specRejectionLoopCount = 0;

  // Story 2.10 / V7: informational marker set once the spec-rejection loop counter crosses the
  // configured escalation threshold. Cleared manually by an operator (Epic 4 handler).
  @Column(name = "escalation_marker_set", nullable = false)
  private boolean escalationMarkerSet = false;

  // Story 3.21 / V13: counter of implementation-rejection loops on this run. Increments on every
  // successful TechnicalApprovalService.rejectImplementation. Drives the same escalation-marker
  // threshold check as the spec-rejection loop (the marker is shared — Decision D5).
  @Column(name = "implementation_rejection_loop_count", nullable = false)
  private int implementationRejectionLoopCount = 0;

  // Story 3m-2 (AC6, ADR 0036) — the run INSTANCE cursor (V48). workflow_definition_id is the
  // snapshotted definition (set ONCE at run start, then read-only); current_step_index is the walk
  // position. Both nullable ⇒ a null-definition run never reads the cursor and is byte-identical to
  // pre-3m. DORMANT in 3m-2 (always null); the write path (the run-start snapshot + the
  // EXECUTING->EXECUTING advance) is 3m-3.
  //
  // ⚠️ 3m-3 clobber note ([[token-usage-clobbered-by-terminal-transition]]): this entity does
  // full-row UPDATEs (no @DynamicUpdate). Advancing current_step_index IN-BAND with the state
  // transition (same entity/tx, as here) is safe; if 3m-3 ever writes the cursor from a separate
  // REQUIRES_NEW path, a stale full-row UPDATE would null it — add @DynamicUpdate or a dedicated
  // JDBC cursor port then (the V44 failure-classification precedent).
  @Column(name = "workflow_definition_id")
  private Long workflowDefinitionId;

  @Column(name = "current_step_index")
  private Integer currentStepIndex;

  public Long getId() {
    return id;
  }

  public String getPublicId() {
    return publicId;
  }

  public void setPublicId(String publicId) {
    this.publicId = publicId;
  }

  public String getProjectId() {
    return projectId;
  }

  public String getParentRunId() {
    return parentRunId;
  }

  public void setProjectId(String projectId) {
    this.projectId = projectId;
  }

  public WorkflowState getCurrentState() {
    return PersistedRegistryValues.workflowRunState(currentState);
  }

  void setCurrentState(WorkflowState currentState) {
    this.currentState = Objects.requireNonNull(currentState, "currentState").value();
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(OffsetDateTime archivedAt) {
    this.archivedAt = archivedAt;
  }

  public Long getVersion() {
    return version;
  }

  public int getSpecRejectionLoopCount() {
    return specRejectionLoopCount;
  }

  void setSpecRejectionLoopCount(int specRejectionLoopCount) {
    this.specRejectionLoopCount = specRejectionLoopCount;
  }

  public boolean isEscalationMarkerSet() {
    return escalationMarkerSet;
  }

  void setEscalationMarkerSet(boolean escalationMarkerSet) {
    this.escalationMarkerSet = escalationMarkerSet;
  }

  public int getImplementationRejectionLoopCount() {
    return implementationRejectionLoopCount;
  }

  void setImplementationRejectionLoopCount(int implementationRejectionLoopCount) {
    this.implementationRejectionLoopCount = implementationRejectionLoopCount;
  }

  public Long getWorkflowDefinitionId() {
    return workflowDefinitionId;
  }

  public Integer getCurrentStepIndex() {
    return currentStepIndex;
  }
}
