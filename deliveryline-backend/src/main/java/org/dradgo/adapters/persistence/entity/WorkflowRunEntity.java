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
    WorkflowRunEntity entity = new WorkflowRunEntity();
    entity.publicId = publicId;
    entity.currentState = Objects.requireNonNull(currentState, "currentState").value();
    return entity;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @Column(name = "current_state", nullable = false)
  private String currentState;

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

  public Long getId() {
    return id;
  }

  public String getPublicId() {
    return publicId;
  }

  public void setPublicId(String publicId) {
    this.publicId = publicId;
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
}
