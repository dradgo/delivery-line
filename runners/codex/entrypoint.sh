#!/bin/sh
# Story 3.3 — Codex runner entrypoint (runner-contracts v1).
#
# File-based runner <-> backend contract (story 3.1 AC8, --network=none):
#   read   /workspace/input/context-bundle.v1.json   (read-only mount)
#   invoke the Codex CLI with the extracted ticket / spec / feedback inputs
#   write  /workspace/output/runner-result.v1.json   (read-write mount)
#   write  /workspace/logs/runner.stdout|.stderr     (read-write mount)
#   exit   0 on success; documented non-zero codes per failure mode (see README).
#
# Story 3a-3 — subscription-first auth. When CODEX_AUTH_JSON is present the
# entrypoint materializes it into $CODEX_HOME/auth.json (mode 0600, via
# runner.mjs materialize-auth) and skips the OPENAI_API_KEY export; a malformed /
# empty CODEX_AUTH_JSON writes a schema-valid failure result and exits 21 (the
# new auth-family code, distinct from exit 20 "no credential present"). The
# API-key path (CODEX_API_KEY / OPENAI_API_KEY) is unchanged when no subscription
# credential is supplied.
#
# The filenames above are the LIVE contract from the backend (verified against
# LocalRunnerWorkspaceStore: CONTEXT_BUNDLE_FILENAME=context-bundle.v1.json,
# RUNNER_RESULT_FILENAME=runner-result.v1.json). The adapter treats a missing
# result file as RUNNER_CRASH regardless of exit code, so we ALWAYS write the
# result file before exiting 0.
#
# Mount paths are overridable via env (DELIVERYLINE_INPUT_DIR / _OUTPUT_DIR /
# _LOGS_DIR) purely so the entrypoint is unit-testable without a container; in
# production the backend mounts the /workspace/* constants.
set -eu

INPUT_DIR="${DELIVERYLINE_INPUT_DIR:-/workspace/input}"
OUTPUT_DIR="${DELIVERYLINE_OUTPUT_DIR:-/workspace/output}"
LOGS_DIR="${DELIVERYLINE_LOGS_DIR:-/workspace/logs}"

BUNDLE_FILE="$INPUT_DIR/context-bundle.v1.json"
RESULT_FILE="$OUTPUT_DIR/runner-result.v1.json"
STDOUT_LOG="$LOGS_DIR/runner.stdout"
STDERR_LOG="$LOGS_DIR/runner.stderr"

NODE_BIN="${NODE_BIN:-node}"
RUNNER_LIB="${DELIVERYLINE_RUNNER_LIB:-/opt/deliveryline/lib/runner.mjs}"
CODEX_CLI_BIN="${CODEX_CLI_BIN:-codex}"
EXPECTED_CODEX_VERSION="${CODEX_CLI_VERSION:-<unset>}"
# Story 3a-6 — agent-side OpenSpec CLI (present in production; mocked in the offline
# build). --self-test asserts its presence + version pin (read from OPENSPEC_VERSION).
OPENSPEC_CLI_BIN="${OPENSPEC_CLI_BIN:-openspec}"
EXPECTED_OPENSPEC_VERSION="${OPENSPEC_VERSION:-<unset>}"
# Story 3a-7 — vendored obra/superpowers skills, discoverable by Codex at startup. Codex
# auto-scans ~/.agents/skills/; the image symlinks ~/.agents/skills/superpowers -> the
# vendored skills/ dir. --self-test asserts the dir resolves + reports the pin (SUPERPOWERS_PIN).
SUPERPOWERS_SKILLS_DIR="${SUPERPOWERS_SKILLS_DIR:-${HOME:-/home/codex}/.agents/skills/superpowers}"
EXPECTED_SUPERPOWERS_PIN="${SUPERPOWERS_PIN:-<unset>}"

PROMPT_FILE=""
# Story 3a-3 (AC4) — path of the materialized subscription auth.json (set only when
# CODEX_AUTH_JSON is resolved); the cleanup trap removes it on exit.
CODEX_AUTH_FILE=""
cleanup() {
  if [ -n "$PROMPT_FILE" ] && [ -f "$PROMPT_FILE" ]; then
    rm -f "$PROMPT_FILE" 2>/dev/null || true
  fi
  # Defense-in-depth: drop the materialized auth.json on EXIT/INT/TERM (the
  # container is ephemeral anyway). rm -f is a silent no-op in API-key mode.
  if [ -n "${CODEX_AUTH_FILE:-}" ]; then
    rm -f "$CODEX_AUTH_FILE" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# Structured, parseable diagnostics to the container's own stderr. NEVER prints
# secret values, auth headers, or full bundle payloads — names + counts only.
log() {
  _level="$1"
  shift
  if [ -n "${DELIVERYLINE_CORRELATION_ID:-}" ]; then
    printf '[codex-runner] [%s] [cid=%s] %s\n' "$_level" "$DELIVERYLINE_CORRELATION_ID" "$*" >&2
  else
    printf '[codex-runner] [%s] %s\n' "$_level" "$*" >&2
  fi
}

# ---- Story 3a-8 — opt-in OpenSpec authoring layer (gated on DELIVERYLINE_RUNNER_OPENSPEC) ----
# Default OFF. When off, NONE of these functions are called, so the legacy path is
# byte-identical (no scaffold, no prompt delta, no openspec/ folder, no new log lines).
# Every step is ADDITIVE + best-effort: an OpenSpec failure NEVER fails the stage, changes
# an exit code, or adds a failure category (Trap T-ADDITIVE-NEVER-BLOCKS). The read-only
# stages still write ONLY to stdout (Trap T-READONLY-NO-REPO-WRITE); only pr-output mutates
# the repo. Raw helper output is sent to the container stderr (captured by the conformance
# IT) so it never collides with the agent's truncating runner.stdout/.stderr redirects.
openspec_enabled() {
  [ "${DELIVERYLINE_RUNNER_OPENSPEC:-}" = "true" ]
}

# Slug-sanitize a token to a filesystem-safe [a-z0-9_-] form (lowercase; collapse runs of
# other chars to '-'; trim leading/trailing '-'). GNU sed on the debian base (\{1,\} == +).
openspec_slug() {
  printf '%s' "$1" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -e 's/[^a-z0-9_-]\{1,\}/-/g' -e 's/^-\{1,\}//' -e 's/-\{1,\}$//'
}

# Deterministic change-id (AC3): <slug(ticketRef)>-<short slug(workflowRunId)>, identical
# across all three stages of a run. Falls back to "change" when ticketRef is absent.
openspec_change_id() {
  _ticket_slug="$(openspec_slug "${DL_TICKET_REF:-}")"
  [ -n "$_ticket_slug" ] || _ticket_slug="change"
  _run_slug="$(openspec_slug "${DL_WORKFLOW_RUN_ID:-}" | cut -c1-16)"
  # cut can re-introduce a trailing '-', and an absent runId leaves "<ticket>-"; trim it so
  # the change-id is always a clean token (e.g. "change" rather than "change-").
  printf '%s-%s' "$_ticket_slug" "$_run_slug" | sed -e 's/-\{1,\}$//'
}

# Authoring instructions appended to a READ-ONLY stage's PROMPT_INSTRUCTION (the agent still
# writes only to stdout; its fenced stdout becomes the existing per-stage artifact — no new
# artifact channel, D6). Emitted via command substitution (trailing newline stripped).
openspec_prompt_delta() {
  _stage="$1"
  _change_id="$2"
  case "$_stage" in
    spec)
      cat <<EOF

OPENSPEC AUTHORING (OpenSpec change-id: ${_change_id}). In ADDITION to the specification above, append OpenSpec change artifacts to standard output using this EXACT fence convention — a line "=== FILE: <relpath> ===" immediately before each file's content:
=== FILE: proposal.md ===
<why this change is needed and WHAT changes, in OpenSpec proposal form>
=== FILE: specs/<capability>/spec.md ===
<requirement deltas in OpenSpec ADDED/MODIFIED/REMOVED format>
Replace <capability> with a short kebab-case capability name. Do NOT modify, create, or delete any files on disk — emit everything to standard output only.
EOF
      ;;
    implementationPlan)
      cat <<EOF

OPENSPEC AUTHORING (OpenSpec change-id: ${_change_id}). In ADDITION to the plan above, append OpenSpec change artifacts to standard output using this EXACT fence convention — a line "=== FILE: <relpath> ===" immediately before each file's content:
=== FILE: design.md ===
<the technical design / HOW, extending the approved proposal>
=== FILE: tasks.md ===
<an ordered, checkbox task list to implement the change>
Do NOT modify, create, or delete any files on disk — emit everything to standard output only.
EOF
      ;;
  esac
}

# Split one carried artifact (read by referencePath from the read-only input mount) into the
# change folder. A missing/empty carried artifact -> WARN + assemble what is present (AC5).
openspec_split_carried() {
  _ref_path="$1"
  _change_dir="$2"
  _label="$3"
  if [ -z "$_ref_path" ]; then
    log WARN "openspec: no $_label artifact carried forward (referencePath empty) — assembling without it"
    return 0
  fi
  _artifact_file="$INPUT_DIR/$_ref_path"
  if [ ! -f "$_artifact_file" ]; then
    log WARN "openspec: $_label artifact not present at the carried referencePath — assembling without it"
    return 0
  fi
  if "$NODE_BIN" "$RUNNER_LIB" split-fenced --in "$_artifact_file" --change-dir "$_change_dir" >&2; then
    log INFO "openspec: $_label artifact split into change folder"
  else
    log WARN "openspec: $_label artifact did not split cleanly (best-effort; shipping code)"
  fi
}

# pr-output ONLY: scaffold the idiomatic openspec/ skeleton, reconstruct
# openspec/changes/<id>/ from the two carried artifacts, then structurally validate. Every
# step is best-effort; the agent (invoked afterwards) implements tasks.md and the openspec/
# folder is committed alongside the code by the existing PR machinery.
openspec_assemble_proutput() {
  _repo="$1"
  _change_id="$2"
  _change_dir="$_repo/openspec/changes/$_change_id"
  log INFO "openspec assemble start changeId=$_change_id repoDir=$_repo"
  # Pre-baked skeleton fallback (AC4b). `openspec init` selects a tool INTERACTIVELY with no
  # verified non-interactive flag (Task 0 spike — Trap T-INIT-NONINTERACTIVE), so we lay the
  # idiomatic openspec/ + AGENTS.md down ourselves rather than risk a headless hang.
  mkdir -p "$_change_dir" 2>/dev/null \
    || log WARN "openspec: could not create $_change_dir (best-effort)"
  if [ ! -f "$_repo/openspec/AGENTS.md" ]; then
    {
      printf '# OpenSpec\n\n'
      printf 'OpenSpec change proposals authored by the DeliveryLine delivery pipeline.\n'
      printf 'Each changes/<id>/ folder carries proposal.md, specs/, design.md, tasks.md.\n'
      printf 'See https://openspec.pro for the conventions.\n'
    } >"$_repo/openspec/AGENTS.md" 2>/dev/null \
      || log WARN "openspec: could not write AGENTS.md (best-effort)"
  fi
  openspec_split_carried "${DL_SPEC_REF_PATH:-}" "$_change_dir" "spec"
  openspec_split_carried "${DL_PLAN_REF_PATH:-}" "$_change_dir" "implementation-plan"
  # Structural guard (best-effort). Real `openspec validate` needs cwd=repo; the offline mock
  # ignores args+cwd and exits 0. A non-zero result is logged WARN, never fatal (AC5).
  if command -v "$OPENSPEC_CLI_BIN" >/dev/null 2>&1; then
    if (cd "$_repo" && "$OPENSPEC_CLI_BIN" validate "$_change_id") >&2; then
      log INFO "openspec validate ok changeId=$_change_id"
      OPENSPEC_VALIDATE_NOTE="validate ok"
    else
      log WARN "openspec validate reported issues (best-effort; shipping code) changeId=$_change_id"
      OPENSPEC_VALIDATE_NOTE="validate reported issues"
    fi
  else
    log WARN "openspec CLI not on PATH; skipping validate (best-effort) changeId=$_change_id"
    OPENSPEC_VALIDATE_NOTE="validate skipped (CLI absent)"
  fi
  log INFO "openspec assemble complete changeId=$_change_id"
}

print_help() {
  cat <<'EOF'
deliveryline/codex-runner — runner-contracts v1 entrypoint

Usage (normally invoked by the backend DockerRunnerAdapter with the workspace mounts):
  entrypoint.sh                         run a stage (stage resolved from --stage / env / bundle)
  entrypoint.sh --stage=<stage>         force the stage (spec-investigation|implementation-plan|pr-output)
  entrypoint.sh --self-test             verify image health (doctor / CI); no network, no API call
  entrypoint.sh --simulate-failure=M    test-only failure injection (M=timeout|crash|contract_violation)
  entrypoint.sh --help                  this message

Stage tokens (mapped to runner-result artifactType):
  spec-investigation | investigation | spec        -> spec
  implementation-plan | plan | implementationPlan  -> implementationPlan
  pr-output | execution | prOutput                 -> prOutput

Environment:
  CODEX_AUTH_JSON                  subscription auth.json content (PREFERRED, cost-saving path;
                                   materialized to $CODEX_HOME/auth.json mode 0600; value never logged)
  CODEX_API_KEY / OPENAI_API_KEY   agent-provider API key fallback (first present wins; value never logged)
  CODEX_HOME                       Codex home dir for subscription auth.json (default $HOME/.codex)
  CODEX_BASE_URL                   optional API base URL override
  DELIVERYLINE_RUNNER_STAGE        stage when --stage is not passed (production injection seam)
  DELIVERYLINE_CORRELATION_ID      optional correlation id prepended to log lines
EOF
}

# Map a raw stage token to a runner-result.v1 artifactType. Accepts the three
# story-named artifact stages AND the RunnerStage enum aliases (investigation /
# execution). Returns non-zero on an unknown token.
map_stage() {
  case "$1" in
    spec | spec-investigation | investigation)
      echo "spec"
      ;;
    implementationPlan | implementation-plan | plan)
      echo "implementationPlan"
      ;;
    prOutput | pr-output | execution)
      echo "prOutput"
      ;;
    review)
      # Story 3d-2 — advisory reviewer stage. Emits a review-result.v1 verdict (NOT a
      # runner-result.v1 artifact); see the `review` arm in lib/runner.mjs commandBuild.
      echo "review"
      ;;
    *)
      return 1
      ;;
  esac
}

ensure_output_and_logs() {
  # output + logs are read-write mounts in production; create-if-missing keeps
  # local (non-container) test runs working. input is NEVER created/written.
  mkdir -p "$OUTPUT_DIR" "$LOGS_DIR" 2>/dev/null || true
  : >"$STDOUT_LOG" 2>/dev/null || true
  : >"$STDERR_LOG" 2>/dev/null || true
}

run_self_test() {
  log INFO "self-test start"
  if ! command -v "$NODE_BIN" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: node runtime '$NODE_BIN' not found on PATH"
    exit 1
  fi
  if [ ! -f "$RUNNER_LIB" ]; then
    echo "SELF-TEST FAIL: runner helper missing at $RUNNER_LIB"
    exit 1
  fi
  if ! command -v "$CODEX_CLI_BIN" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: codex CLI '$CODEX_CLI_BIN' not found on PATH"
    exit 1
  fi
  _codex_version="$("$CODEX_CLI_BIN" --version 2>/dev/null || echo '')"
  case "$_codex_version" in
    *"$EXPECTED_CODEX_VERSION"*) ;;
    *)
      echo "SELF-TEST FAIL: codex version '${_codex_version:-<unknown>}' does not match expected pin $EXPECTED_CODEX_VERSION"
      exit 1
      ;;
  esac
  # Story 3a-6 — agent-side OpenSpec CLI must be present + match the pin (substring
  # match, mirroring the codex version compare above). In production this is the real
  # `openspec`; in the offline build it is the baked mock-openspec.sh reporting the pin.
  if ! command -v "$OPENSPEC_CLI_BIN" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: openspec CLI '$OPENSPEC_CLI_BIN' not found on PATH"
    exit 1
  fi
  _openspec_version="$("$OPENSPEC_CLI_BIN" --version 2>/dev/null || echo '')"
  case "$_openspec_version" in
    *"$EXPECTED_OPENSPEC_VERSION"*) ;;
    *)
      echo "SELF-TEST FAIL: openspec version '${_openspec_version:-<unknown>}' does not match expected pin $EXPECTED_OPENSPEC_VERSION"
      exit 1
      ;;
  esac
  # Story 3a-7 — the vendored obra/superpowers skills must resolve on Codex's discovery path.
  # `[ -d ]` follows the symlink, so it is false for BOTH a missing dir AND a dangling symlink;
  # then require a FLOOR of SKILL.md files so an empty/half-copied tree also fails. The pinned
  # tree ships 14 skills; the floor (10) catches a gross truncation without pinning the exact
  # count, which a legitimate re-vendor may change. Vendored via COPY (offline-safe), so this is
  # green in the offline build the conformance IT exercises — no mock.
  if [ ! -d "$SUPERPOWERS_SKILLS_DIR" ]; then
    echo "SELF-TEST FAIL: superpowers skills not found / symlink unresolved at $SUPERPOWERS_SKILLS_DIR"
    exit 1
  fi
  _superpowers_count="$(find -L "$SUPERPOWERS_SKILLS_DIR" -maxdepth 2 -name SKILL.md 2>/dev/null | wc -l | tr -d ' ')"
  if [ "${_superpowers_count:-0}" -lt 10 ]; then
    echo "SELF-TEST FAIL: only ${_superpowers_count:-0} SKILL.md under superpowers skills dir $SUPERPOWERS_SKILLS_DIR (expected >= 10; pinned tree ships 14)"
    exit 1
  fi
  echo "deliveryline/codex-runner self-test: OK"
  echo "  entrypoint:     reachable"
  echo "  node:           $("$NODE_BIN" --version)"
  echo "  runner helper:  $RUNNER_LIB"
  echo "  codex bin:      $(command -v "$CODEX_CLI_BIN")"
  echo "  codex version:  ${_codex_version:-<unknown>} (expected pin: $EXPECTED_CODEX_VERSION)"
  echo "  openspec bin:   $(command -v "$OPENSPEC_CLI_BIN")"
  echo "  openspec version: ${_openspec_version:-<unknown>} (expected pin: $EXPECTED_OPENSPEC_VERSION)"
  echo "  superpowers:    $_superpowers_count skills at $SUPERPOWERS_SKILLS_DIR (pin $EXPECTED_SUPERPOWERS_PIN)"
  echo "  mounts:         input=$INPUT_DIR output=$OUTPUT_DIR logs=$LOGS_DIR"
  echo "  mount status:   input=$([ -d "$INPUT_DIR" ] && echo present || echo absent) output=$([ -d "$OUTPUT_DIR" ] && echo present || echo absent) logs=$([ -d "$LOGS_DIR" ] && echo present || echo absent)"
  echo "  network:        none (file-based contract; no API call in self-test)"
  log INFO "self-test ok"
  exit 0
}

run_simulate_failure() {
  _mode="$1"
  if [ "${DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE:-}" != "true" ]; then
    log ERROR "simulate-failure requested but DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE != true — refusing (production image)"
    exit 2
  fi
  ensure_output_and_logs
  case "$_mode" in
    timeout)
      log WARN "simulate-failure=timeout — sleeping past any plausible deadline (broker timeout path should kill this)"
      sleep 86400
      # Should be killed by the backend timeout path long before this.
      exit 0
      ;;
    crash)
      log WARN "simulate-failure=crash — exiting non-zero with NO result file (drives RUNNER_CRASH classification)"
      exit 50
      ;;
    contract_violation)
      log WARN "simulate-failure=contract_violation — writing a schema-invalid result file"
      "$NODE_BIN" "$RUNNER_LIB" build-invalid --out "$RESULT_FILE"
      # Result file present but invalid: the adapter sees Completed, the broker's
      # RunnerContractValidator then flags runner_contract_violation.
      exit 0
      ;;
    *)
      log ERROR "unknown simulate-failure mode: $_mode (expected timeout|crash|contract_violation)"
      exit 2
      ;;
  esac
}

# ---- argument parsing -------------------------------------------------------
MODE="run"
STAGE_ARG=""
SIMULATE_MODE=""
for arg in "$@"; do
  case "$arg" in
    --self-test) MODE="self-test" ;;
    --help | -h) MODE="help" ;;
    --simulate-failure=*) SIMULATE_MODE="${arg#*=}" ;;
    --stage=*) STAGE_ARG="${arg#*=}" ;;
    *) log WARN "ignoring unrecognized argument: $arg" ;;
  esac
done

if [ "$MODE" = "help" ]; then
  print_help
  exit 0
fi

if [ "$MODE" = "self-test" ]; then
  run_self_test
fi

if [ -n "$SIMULATE_MODE" ]; then
  run_simulate_failure "$SIMULATE_MODE"
fi

# ---- normal run -------------------------------------------------------------
ensure_output_and_logs
log INFO "run start input=$INPUT_DIR output=$OUTPUT_DIR logs=$LOGS_DIR"

# 1. input bundle must exist (read-only mount).
if [ ! -f "$BUNDLE_FILE" ]; then
  log ERROR "input bundle not found at $BUNDLE_FILE"
  exit 10
fi

# 2. parse the bundle + extract the prompt (node — no jq on the slim base).
PROMPT_FILE="$LOGS_DIR/codex.prompt"
if ! PREPARE_OUT="$("$NODE_BIN" "$RUNNER_LIB" prepare --bundle "$BUNDLE_FILE" --prompt-out "$PROMPT_FILE")"; then
  log ERROR "failed to parse input bundle (not readable / not valid JSON)"
  exit 11
fi
# PREPARE_OUT contains shell-quoted DL_* assignments emitted by runner.mjs.
eval "$PREPARE_OUT"
log INFO "bundle parsed schemaVersion=$DL_SCHEMA_VERSION workflowRunId=$DL_WORKFLOW_RUN_ID runnerExecutionId=$DL_RUNNER_EXECUTION_ID"

# 2b. fail fast on an unsupported schema version (documented exit code).
if [ "$DL_SCHEMA_VERSION" != "1" ]; then
  log ERROR "unsupported context-bundle schemaVersion=$DL_SCHEMA_VERSION (this image speaks v1 only)"
  exit 12
fi

# 3. resolve the stage: --stage arg > env > optional bundle.stage field.
RAW_STAGE=""
if [ -n "$STAGE_ARG" ]; then
  RAW_STAGE="$STAGE_ARG"
elif [ -n "${DELIVERYLINE_RUNNER_STAGE:-}" ]; then
  RAW_STAGE="$DELIVERYLINE_RUNNER_STAGE"
elif [ -n "$DL_BUNDLE_STAGE" ]; then
  RAW_STAGE="$DL_BUNDLE_STAGE"
fi
if [ -z "$RAW_STAGE" ]; then
  log ERROR "no stage resolved — pass --stage=, set DELIVERYLINE_RUNNER_STAGE, or include bundle.stage"
  exit 13
fi
if ! ARTIFACT_TYPE="$(map_stage "$RAW_STAGE")"; then
  log ERROR "unknown stage token: $RAW_STAGE"
  exit 13
fi
log INFO "stage resolved raw=$RAW_STAGE artifactType=$ARTIFACT_TYPE"

# 4. resolve auth (story 3.5 + 3a-3 contract). SUBSCRIPTION-FIRST (mirrors the
#    Claude runner, story 3.4): if CODEX_AUTH_JSON is present, materialize it into
#    $CODEX_HOME/auth.json — Codex reads ChatGPT/Pro subscription credentials from
#    a FILE, not an env var — and DO NOT export an API key. Otherwise fall back to
#    the unchanged API-key path (first present of CODEX_API_KEY then OPENAI_API_KEY).
#    The value is NEVER logged — only the variable NAME + presence (+ path/mode for
#    subscription). resolution preference = the order configured in
#    deliveryline.runner.secret-env-names.codex (RunnerSecretsService injects the
#    first-present value under its own name, so the container sees exactly one).
AUTH_KEY_VAR=""
if [ -n "${CODEX_AUTH_JSON:-}" ]; then
  # --- subscription mode ---
  # Guard $HOME under set -u: an unset/empty HOME would otherwise abort the script
  # with an opaque unbound-variable error (no documented exit code, no result file).
  CODEX_HOME="${CODEX_HOME:-${HOME:-/home/codex}/.codex}"
  export CODEX_HOME
  CODEX_AUTH_FILE="$CODEX_HOME/auth.json"
  AUTH_KEY_VAR="CODEX_AUTH_JSON"
  # runner.mjs owns the JSON validation + atomic 0600 write (the slim base has no
  # jq). It reads the value from env (never argv) and never prints it. A malformed
  # /empty credential — or an unwritable CODEX_HOME — exits non-zero here → schema-valid
  # failure result + exit 21, never RUNNER_CRASH.
  if ! "$NODE_BIN" "$RUNNER_LIB" materialize-auth --out "$CODEX_AUTH_FILE"; then
    log ERROR "failed to materialize CODEX_AUTH_JSON into auth.json (name only; value never logged)"
    "$NODE_BIN" "$RUNNER_LIB" build-failure \
      --bundle "$BUNDLE_FILE" \
      --stage "$ARTIFACT_TYPE" \
      --category runner_non_zero_exit \
      --summary "Codex subscription auth.json could not be materialized (malformed/empty value or unwritable CODEX_HOME)" \
      --out "$RESULT_FILE" >/dev/null || true
    exit 21
  fi
  log INFO "auth resolved from variable name=CODEX_AUTH_JSON mode=subscription path=$CODEX_AUTH_FILE (value redacted)"
  # Subscription mode: do NOT export OPENAI_API_KEY — the CLI authenticates from auth.json.
else
  # --- API-key mode (byte-for-byte the story 3.5 path) ---
  AUTH_KEY=""
  if [ -n "${CODEX_API_KEY:-}" ]; then
    AUTH_KEY="$CODEX_API_KEY"
    AUTH_KEY_VAR="CODEX_API_KEY"
  elif [ -n "${OPENAI_API_KEY:-}" ]; then
    AUTH_KEY="$OPENAI_API_KEY"
    AUTH_KEY_VAR="OPENAI_API_KEY"
  fi
  if [ -z "$AUTH_KEY" ] && [ "${DELIVERYLINE_RUNNER_SKIP_AUTH:-}" != "true" ]; then
    # AC6: only the absence of ALL configured credentials fails; the message names
    # the PREFERRED variable (CODEX_AUTH_JSON), with the API key as the fallback.
    log ERROR "no agent-provider credential present — set CODEX_AUTH_JSON (subscription, preferred) or an agent-provider API key (value is never logged)"
    "$NODE_BIN" "$RUNNER_LIB" build-failure \
      --bundle "$BUNDLE_FILE" \
      --stage "$ARTIFACT_TYPE" \
      --category runner_non_zero_exit \
      --summary "No agent-provider key present" \
      --out "$RESULT_FILE" >/dev/null || true
    exit 20
  fi
  if [ -n "$AUTH_KEY_VAR" ]; then
    log INFO "auth resolved from variable name=$AUTH_KEY_VAR (value redacted)"
    # The Codex CLI reads OPENAI_API_KEY / OPENAI_BASE_URL; export without logging.
    export OPENAI_API_KEY="$AUTH_KEY"
  else
    log WARN "no auth key present — proceeding under DELIVERYLINE_RUNNER_SKIP_AUTH=true (mock/test only)"
  fi
fi
if [ -n "${CODEX_BASE_URL:-}" ]; then
  export OPENAI_BASE_URL="$CODEX_BASE_URL"
  log INFO "codex base url override active (variable name=CODEX_BASE_URL)"
fi

# 5. invoke the Codex CLI; capture raw stdout/stderr to the logs mount.
#    Story 3.8 — headless, non-interactive, STAGE-AWARE invocation:
#      - `exec` subcommand (overridable via CODEX_EXEC_ARGS for forward-compat). `codex
#        exec` is non-interactive by design, so there is no approval TTY to satisfy —
#        the --sandbox policy is the only lever over what the agent may do.
#      - --skip-git-repo-check: a stage may run with NO repo mount, or against a
#        freshly-cloned worktree the CLI does not recognize as trusted.
#      - --sandbox <mode>: governs whether the agent may MUTATE the repo. The design /
#        plan stages (spec, implementationPlan) run `read-only` — they ANALYSE the tree
#        and emit a specification / plan to stdout for HUMAN APPROVAL, and must not
#        change any files. Only the pr-output stage runs `danger-full-access` to
#        actually implement the approved change. (The container is itself the outer
#        sandbox: unprivileged user, backend network policy, read-only input mount.)
#      - -C <repo>: run Codex with the mounted repo as its working root.
#    A stage-specific INSTRUCTION is prepended to the ticket prompt on stdin so the
#    agent knows whether to DESIGN (read-only) or IMPLEMENT.
CODEX_SUBCOMMAND="${CODEX_EXEC_ARGS:-exec}"
case "$CODEX_SUBCOMMAND" in
  *[!A-Za-z0-9_./:=,-]*)
    log ERROR "CODEX_EXEC_ARGS contains unsupported characters; configure a single safe command token"
    "$NODE_BIN" "$RUNNER_LIB" build-failure \
      --bundle "$BUNDLE_FILE" \
      --stage "$ARTIFACT_TYPE" \
      --category runner_non_zero_exit \
      --summary "Codex command configuration is invalid" \
      --out "$RESULT_FILE" >/dev/null || true
    exit 2
    ;;
esac
CODEX_REPO_DIR="${DELIVERYLINE_REPO_DIR:-/workspace/repo}"
# Stage-aware posture: design/plan stages are READ-ONLY (produce an artifact for human
# approval); only pr-output mutates the repo. CODEX_SANDBOX overrides the default.
case "$ARTIFACT_TYPE" in
  prOutput)
    CODEX_SANDBOX="${CODEX_SANDBOX:-danger-full-access}"
    PROMPT_INSTRUCTION="OPERATING MODE: IMPLEMENTATION. You are the execution stage of a governed delivery pipeline. Implement the change described below in the repository at ${CODEX_REPO_DIR}, following the approved specification when provided. Make the necessary file changes, then summarise what you changed on standard output."
    ;;
  implementationPlan)
    CODEX_SANDBOX="${CODEX_SANDBOX:-read-only}"
    PROMPT_INSTRUCTION="OPERATING MODE: IMPLEMENTATION PLAN (read-only). You are a planning stage of a governed delivery pipeline. Analyse the repository at ${CODEX_REPO_DIR} and produce a concrete, ordered IMPLEMENTATION PLAN (the steps required) for the ticket below. Do NOT modify, create, or delete any files. Output the plan as Markdown on standard output only."
    ;;
  review)
    # Story 3d-2 — advisory reviewer (read-only). Reviews the produced output under /workspace/input
    # against the approved spec and ends with a parseable verdict marker (lib/runner.mjs review arm
    # reads `VERDICT: pass|concern|fail`; absent a marker defaults to `concern`). No repo changes.
    CODEX_SANDBOX="${CODEX_SANDBOX:-read-only}"
    PROMPT_INSTRUCTION="OPERATING MODE: ADVISORY REVIEW (read-only). You are an independent reviewer in a governed delivery pipeline. The output artifact produced for the ticket below is available under /workspace/input alongside the approved specification. Review it for correctness and completeness against the specification. Do NOT modify, create, or delete any files. After your assessment, end your response with EXACTLY one line and nothing after it: 'VERDICT: pass' (no blocking concerns), 'VERDICT: concern' (non-blocking concerns the human should weigh), or 'VERDICT: fail' (a blocking defect)."
    ;;
  *)
    CODEX_SANDBOX="${CODEX_SANDBOX:-read-only}"
    PROMPT_INSTRUCTION="OPERATING MODE: SPECIFICATION (read-only). You are the investigation stage of a governed delivery pipeline. Analyse the repository at ${CODEX_REPO_DIR} and write a DESIGN SPECIFICATION for the ticket below: WHAT should change and WHY, the modules / files affected, and the implementation approach. Do NOT modify, create, or delete any files — a human reviews and approves this specification before any implementation happens. Output the specification as Markdown on standard output only."
    ;;
esac
# Story 3a-8 — opt-in OpenSpec authoring layer (default OFF). Flag off => none of this runs
# (byte-identical legacy path). Flag on => augment the read-only stage prompts with the fence
# convention, and at pr-output assemble + validate the change folder BEFORE the agent runs so
# the agent can implement its tasks.md.
if openspec_enabled; then
  OPENSPEC_CHANGE_ID="$(openspec_change_id)"
  log INFO "openspec enabled changeId=$OPENSPEC_CHANGE_ID stage=$ARTIFACT_TYPE"
  case "$ARTIFACT_TYPE" in
    spec | implementationPlan)
      PROMPT_INSTRUCTION="${PROMPT_INSTRUCTION}$(openspec_prompt_delta "$ARTIFACT_TYPE" "$OPENSPEC_CHANGE_ID")"
      ;;
    prOutput)
      if [ -d "$CODEX_REPO_DIR" ]; then
        openspec_assemble_proutput "$CODEX_REPO_DIR" "$OPENSPEC_CHANGE_ID"
        PROMPT_INSTRUCTION="${PROMPT_INSTRUCTION} Additionally, an OpenSpec change folder has been prepared at openspec/changes/${OPENSPEC_CHANGE_ID}/ — implement its tasks.md against the repository and leave the openspec/ folder in place so it is committed alongside your code changes."
      else
        log WARN "openspec: pr-output has no repo mount at $CODEX_REPO_DIR — skipping assembly (best-effort)"
      fi
      ;;
  esac
fi
# Story 3e — OPEN CLARIFYING QUESTIONS directive (SPEC/investigation stage ONLY). Twin of the
# claude entrypoint's block: the 3e-1 loop already PARSES a ```clarifications fence out of the
# agent stdout (lib/runner.mjs splitClarificationsFence) and the backend INGESTS it into `open`
# clarifications, but nothing told the model to emit it, so real spec runs produced no open
# questions. This teaches the exact fence convention the runner parses. Optional + advisory: an
# unambiguous spec omits the block and a malformed/absent block never blocks delivery
# (T-ADDITIVE-NEVER-BLOCKS), so the no-question path stays byte-identical to pre-3e. Built via
# printf single-quoted args so the literal ```fence``` survives (double-quoted backticks would be
# command substitution); appended to PROMPT_INSTRUCTION which is prepended to the prompt below.
if [ "$ARTIFACT_TYPE" = spec ]; then
  CLARIFICATIONS_DIRECTIVE="$(
    printf '%s\n' \
      '' \
      '--- OPEN CLARIFYING QUESTIONS (optional) ---' \
      'If, while writing this specification, you identify genuinely open questions that a human' \
      'reviewer must decide — ambiguous requirements, unstated constraints, or conflicting signals' \
      'the specification cannot responsibly resolve on its own — raise them so the reviewer can' \
      'answer them. Append them as the VERY LAST thing in your response, as a fenced block in' \
      'EXACTLY this form (a single JSON array, one object per question, between the fences):' \
      '```clarifications' \
      '[{"questionId": "Q-001", "questionText": "..."}]' \
      '```' \
      'Use short stable ids (Q-001, Q-002, ...). Include ONLY real open questions; if the' \
      'specification is unambiguous and complete, OMIT the block entirely. The block is advisory' \
      'and never blocks delivery.'
  )"
  PROMPT_INSTRUCTION="${PROMPT_INSTRUCTION}
${CLARIFICATIONS_DIRECTIVE}"
fi
# Assemble the argv with `set --` (no eval, no word-splitting of untrusted values).
set -- "$CODEX_SUBCOMMAND" --skip-git-repo-check --sandbox "$CODEX_SANDBOX"
if [ -d "$CODEX_REPO_DIR" ]; then
  set -- "$@" -C "$CODEX_REPO_DIR"
fi
# Optional model pin (deliveryline.runner via DELIVERYLINE_CODEX_MODEL / CODEX_MODEL); omit → CLI default.
CODEX_MODEL="${CODEX_MODEL:-${DELIVERYLINE_CODEX_MODEL:-}}"
if [ -n "$CODEX_MODEL" ]; then
  set -- "$@" --model "$CODEX_MODEL"
fi
log INFO "codex invocation start bin=$CODEX_CLI_BIN subcommand=$CODEX_SUBCOMMAND stage=$ARTIFACT_TYPE sandbox=$CODEX_SANDBOX repoDir=$CODEX_REPO_DIR argCount=$#"
# Prepend the stage instruction to the ticket prompt on stdin. $? after the pipeline is
# the Codex exit status (the last command), which is what we capture.
set +e
{ printf '%s\n\n' "$PROMPT_INSTRUCTION"; cat "$PROMPT_FILE"; } \
  | "$CODEX_CLI_BIN" "$@" >"$STDOUT_LOG" 2>"$STDERR_LOG"
CODEX_RC=$?
set -e
log INFO "codex invocation finished rc=$CODEX_RC stdoutBytes=$(wc -c <"$STDOUT_LOG" 2>/dev/null | tr -d ' ' || echo 0)"
if [ "$CODEX_RC" -ne 0 ]; then
  log ERROR "codex CLI exited non-zero rc=$CODEX_RC (see runner.stderr)"
  "$NODE_BIN" "$RUNNER_LIB" build-failure \
    --bundle "$BUNDLE_FILE" \
    --stage "$ARTIFACT_TYPE" \
    --category runner_non_zero_exit \
    --summary "Codex CLI exited non-zero" \
    --out "$RESULT_FILE" >/dev/null || true
  exit 30
fi

# 6. build the schema-conformant runner-result.v1 document.
log INFO "building runner-result.v1 artifactType=$ARTIFACT_TYPE result=$RESULT_FILE"
set -- "$NODE_BIN" "$RUNNER_LIB" build \
  --bundle "$BUNDLE_FILE" \
  --stage "$ARTIFACT_TYPE" \
  --summary-file "$STDOUT_LOG" \
  --auth-var "${AUTH_KEY_VAR:-}" \
  --out "$RESULT_FILE"
# Story 3a-8 (AC5/D4): surface the OpenSpec validate outcome in the result summary, ONLY on
# flag-on pr-output (flag-off => no extra arg => byte-identical build invocation).
if openspec_enabled && [ "$ARTIFACT_TYPE" = prOutput ] && [ -n "${OPENSPEC_VALIDATE_NOTE:-}" ]; then
  set -- "$@" --openspec-note "$OPENSPEC_VALIDATE_NOTE"
fi
if ! "$@" >/dev/null; then
  log ERROR "failed to build/write runner-result.v1.json"
  exit 40
fi

log INFO "run complete result=$RESULT_FILE artifactType=$ARTIFACT_TYPE"
exit 0
