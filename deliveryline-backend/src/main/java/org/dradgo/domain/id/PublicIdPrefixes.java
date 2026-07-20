package org.dradgo.domain.id;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RegistryValue;

public enum PublicIdPrefixes implements RegistryValue {
  WORKFLOW_RUN("workflowRun", "run_", "ck_workflow_runs_public_id_format"),
  WORKFLOW_EVENT("workflowEvent", "evt_", "ck_workflow_events_public_id_format"),
  ARTIFACT("artifact", "art_", "ck_artifacts_public_id_format"),
  ARTIFACT_OPERATION("artifactOperation", "op_", "ck_artifact_operations_public_id_format"),
  APPROVAL("approval", "apr_", "ck_approvals_public_id_format"),
  CLARIFICATION("clarification", "clr_", "ck_clarifications_public_id_format"),
  RUNNER_EXECUTION("runnerExecution", "rex_", "ck_runner_executions_public_id_format"),
  INTEGRATION_LINK("integrationLink", "ilk_", "ck_integration_links_public_id_format"),
  RECOVERY_ACTION("recoveryAction", "rcv_", "ck_recovery_actions_public_id_format"),
  IDEMPOTENCY_RECORD("idempotencyRecord", "idm_", "ck_idempotency_records_public_id_format"),
  BATCH_SUBMISSION("batchSubmission", "bat_", "ck_batch_submissions_public_id_format"),
  // Story 3c-2 (AC3) — public_id prefixes for the V17 projects / project_credentials tables.
  PROJECT("project", "prj_", "ck_projects_public_id_format"),
  PROJECT_CREDENTIAL("projectCredential", "cred_", "ck_project_credentials_public_id_format"),
  // Story 3d-1 (AC3) — public_id prefix for the V19 step_reviews advisory-verdict table.
  REVIEW("review", "rev_", "ck_step_reviews_public_id_format"),
  // Story 3d-7 (FR69, AC3) — public_id prefix for the V24 provider_usage_snapshots table (the
  // per-credential, NON-SECRET provider 5h/weekly usage/limit snapshot).
  PROVIDER_USAGE_SNAPSHOT(
      "providerUsageSnapshot", "pul_", "ck_provider_usage_snapshots_public_id_format"),
  // Story 3e-2 (AC6) — public_id prefix for the V25 spec_clarification_acknowledgements side-store
  // (structured spec-runner acknowledgements read by the clarification sweep).
  SPEC_CLARIFICATION_ACKNOWLEDGEMENT(
      "specClarificationAcknowledgement",
      "sca_",
      "ck_spec_clarification_acknowledgements_public_id_format"),
  // Story 3f-4 (AC3) — public_id prefix for the V29 split_proposals table (the advisory LLM
  // decomposition proposal persisted at the spec/review gate; one open proposal per run).
  SPLIT_PROPOSAL("splitProposal", "splprop_", "ck_split_proposals_public_id_format"),
  // Story 3f-4 (AC4/R3) — public_id prefix for the V29 split_proposal_feedback table (the
  // redacted re-propose operator feedback, materialized by reference into the context bundle).
  SPLIT_PROPOSAL_FEEDBACK(
      "splitProposalFeedback", "splfb_", "ck_split_proposal_feedback_public_id_format"),
  // Story 4.17 (AC3) — public_id prefix for the V36 integration_conflicts table (the detected
  // internal-vs-external integration-drift conflict rows written by the conflict-detection sweep;
  // one unresolved row per (link, category)).
  INTEGRATION_CONFLICT("integrationConflict", "icf_", "ck_integration_conflicts_public_id_format"),
  // Story 4.15 (AC2) — public_id prefix for the V45 artifact_drift_detected table (the detected
  // DB/file artifact-drift rows written by the drift-detection sweep; one unresolved row per
  // (category, artifact/operation)). The V45 migration MUST create a public_id CHECK with this
  // exact
  // constraint name or RegistryContractTest fails.
  ARTIFACT_DRIFT_DETECTED("artifactDrift", "adr_", "ck_artifact_drift_detected_public_id_format"),
  // Story 3m-2 (AC2/AC3/AC4) — public_id prefixes for the V48 configurable-workflow tables. Each
  // constraintName() must exactly equal the migration's format-CHECK name so both
  // extractPublicIdPrefixesFromSql() and FlywaySchemaContractTest's table-derived
  // ck_<table>_public_id_format probe resolve the same DB constraint (hence the override uses the
  // FULL table-derived name, NOT the story's shortened ck_wf_step_overrides_public_id_format).
  WORKFLOW_DEFINITION("workflowDefinition", "wfd_", "ck_workflow_definitions_public_id_format"),
  WORKFLOW_DEFINITION_STEP(
      "workflowDefinitionStep", "wfs_", "ck_workflow_definition_steps_public_id_format"),
  WORKFLOW_STEP_OVERRIDE(
      "workflowStepOverride", "wso_", "ck_workflow_definition_step_overrides_public_id_format");

  /**
   * Mirrors the V1 SQL CHECK shape: {@code <prefix>[A-Za-z0-9_-]{4,64}}. The full public_id
   * therefore matches {@code ^<prefix>_[A-Za-z0-9_-]{4,64}$} (the trailing underscore is part of
   * the prefix string).
   */
  private static final Pattern SUFFIX_PATTERN = Pattern.compile("[A-Za-z0-9_-]{4,64}");

  private final String alias;
  private final String prefix;
  private final String constraintName;

  PublicIdPrefixes(String alias, String prefix, String constraintName) {
    this.alias = alias;
    this.prefix = prefix;
    this.constraintName = constraintName;
  }

  static {
    // Reject prefix-of-prefix relationships: a future `run_v2_` would silently shadow `run_`.
    PublicIdPrefixes[] all = values();
    for (PublicIdPrefixes a : all) {
      for (PublicIdPrefixes b : all) {
        if (a != b && a.prefix.startsWith(b.prefix)) {
          throw new IllegalStateException(
              "PublicIdPrefixes invariant: '"
                  + a.prefix
                  + "' starts with another prefix '"
                  + b.prefix
                  + "'");
        }
      }
    }
  }

  public String alias() {
    return alias;
  }

  public String prefix() {
    return prefix;
  }

  public String constraintName() {
    return constraintName;
  }

  @Override
  public String value() {
    return prefix;
  }

  public static Map<String, String> prefixMap() {
    Map<String, String> values = new LinkedHashMap<>();
    for (PublicIdPrefixes prefix : values()) {
      values.put(prefix.alias(), prefix.prefix());
    }
    return Collections.unmodifiableMap(values);
  }

  public static PublicIdPrefixes fromPublicId(String publicId) {
    if (publicId == null) {
      throw missingPublicId();
    }
    if (publicId.isEmpty()) {
      throw blankPublicId(publicId);
    }
    for (PublicIdPrefixes prefix : values()) {
      if (publicId.startsWith(prefix.prefix())) {
        String suffix = publicId.substring(prefix.prefix().length());
        if (!SUFFIX_PATTERN.matcher(suffix).matches()) {
          throw malformedPublicId(publicId, prefix);
        }
        return prefix;
      }
    }
    throw invalidPrefix(publicId, null);
  }

  public static String require(String publicId, PublicIdPrefixes expected) {
    PublicIdPrefixes actual = fromPublicId(publicId);
    if (actual != expected) {
      throw invalidPrefix(publicId, expected);
    }
    return publicId;
  }

  public String format(String suffix) {
    if (suffix == null || !SUFFIX_PATTERN.matcher(suffix).matches()) {
      throw malformedPublicId(prefix + (suffix == null ? "" : suffix), this);
    }
    return prefix + suffix;
  }

  public String next() {
    return format(UUID.randomUUID().toString().replace("-", ""));
  }

  private static DomainException invalidPrefix(String publicId, PublicIdPrefixes expected) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("registry", "PublicIdPrefixes");
    details.put("value", publicId);
    details.put("reason", "unknown_or_mismatched_prefix");
    if (expected != null) {
      details.put("expectedPrefix", expected.prefix());
    }

    return new DomainException(
        DomainErrorCode.INVALID_ID_PREFIX,
        "Unknown or mismatched public ID prefix: " + publicId,
        details);
  }

  private static DomainException missingPublicId() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("registry", "PublicIdPrefixes");
    details.put("value", null);
    details.put("reason", "null_value");
    return new DomainException(
        DomainErrorCode.INVALID_ID_PREFIX,
        "Missing (null) public ID — public_id values are required, never null",
        details);
  }

  private static DomainException blankPublicId(String publicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("registry", "PublicIdPrefixes");
    details.put("value", publicId);
    details.put("reason", "empty_value");
    return new DomainException(
        DomainErrorCode.INVALID_ID_PREFIX,
        "Empty public ID — public_id values must carry a registered prefix and a 4-64 char suffix",
        details);
  }

  private static DomainException malformedPublicId(String publicId, PublicIdPrefixes prefix) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("registry", "PublicIdPrefixes");
    details.put("value", publicId);
    details.put("expectedPrefix", prefix.prefix());
    details.put("reason", "malformed_suffix");
    details.put("suffixPattern", SUFFIX_PATTERN.pattern());
    return new DomainException(
        DomainErrorCode.INVALID_ID_PREFIX,
        "Public ID '"
            + publicId
            + "' has prefix '"
            + prefix.prefix()
            + "' but suffix does not match "
            + SUFFIX_PATTERN.pattern(),
        details);
  }
}
