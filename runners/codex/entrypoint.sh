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
  echo "deliveryline/codex-runner self-test: OK"
  echo "  entrypoint:     reachable"
  echo "  node:           $("$NODE_BIN" --version)"
  echo "  runner helper:  $RUNNER_LIB"
  echo "  codex bin:      $(command -v "$CODEX_CLI_BIN")"
  echo "  codex version:  ${_codex_version:-<unknown>} (expected pin: $EXPECTED_CODEX_VERSION)"
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
  *)
    CODEX_SANDBOX="${CODEX_SANDBOX:-read-only}"
    PROMPT_INSTRUCTION="OPERATING MODE: SPECIFICATION (read-only). You are the investigation stage of a governed delivery pipeline. Analyse the repository at ${CODEX_REPO_DIR} and write a DESIGN SPECIFICATION for the ticket below: WHAT should change and WHY, the modules / files affected, and the implementation approach. Do NOT modify, create, or delete any files — a human reviews and approves this specification before any implementation happens. Output the specification as Markdown on standard output only."
    ;;
esac
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
if ! "$NODE_BIN" "$RUNNER_LIB" build \
  --bundle "$BUNDLE_FILE" \
  --stage "$ARTIFACT_TYPE" \
  --summary-file "$STDOUT_LOG" \
  --out "$RESULT_FILE" >/dev/null; then
  log ERROR "failed to build/write runner-result.v1.json"
  exit 40
fi

log INFO "run complete result=$RESULT_FILE artifactType=$ARTIFACT_TYPE"
exit 0
