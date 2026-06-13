#!/bin/sh
# Story 3.4 — Claude runner entrypoint (runner-contracts v1). The Claude twin of
# story 3.3's Codex entrypoint: same workspace conventions, same artifact-variant
# emission, same exit-code table, same least-privilege posture. See the shared
# runners/RUNNER_CONTRACT.md for every convention both runners obey.
#
# File-based runner <-> backend contract (story 3.1 AC8, --network=none):
#   read   /workspace/input/context-bundle.v1.json   (read-only mount)
#   invoke the Claude Code CLI with the extracted ticket / spec / feedback inputs
#   write  /workspace/output/runner-result.v1.json   (read-write mount)
#   write  /workspace/logs/runner.stdout|.stderr     (read-write mount)
#   exit   0 on success; documented non-zero codes per failure mode (see README).
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
CLAUDE_CLI_BIN="${CLAUDE_CLI_BIN:-claude}"
EXPECTED_CLAUDE_VERSION="${CLAUDE_CLI_VERSION:-<unset>}"
# Story 3a-6 — agent-side OpenSpec CLI (present in production; mocked in the offline
# build). --self-test asserts its presence + version pin (read from OPENSPEC_VERSION).
OPENSPEC_CLI_BIN="${OPENSPEC_CLI_BIN:-openspec}"
EXPECTED_OPENSPEC_VERSION="${OPENSPEC_VERSION:-<unset>}"
# Story 3a-7 — vendored obra/superpowers skills on Claude Code's personal-skills discovery dir.
# The image symlinks ~/.claude/skills/superpowers -> the vendored skills/ dir (mirrors codex,
# only the discovery path differs). --self-test asserts the dir resolves + reports the pin.
SUPERPOWERS_SKILLS_DIR="${SUPERPOWERS_SKILLS_DIR:-${HOME:-/home/claude}/.claude/skills/superpowers}"
EXPECTED_SUPERPOWERS_PIN="${SUPERPOWERS_PIN:-<unset>}"

PROMPT_FILE=""
cleanup() {
  if [ -n "$PROMPT_FILE" ] && [ -f "$PROMPT_FILE" ]; then
    rm -f "$PROMPT_FILE" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# Structured, parseable diagnostics to the container's own stderr. NEVER prints
# secret values, auth headers, or full bundle payloads — names + counts only.
log() {
  _level="$1"
  shift
  if [ -n "${DELIVERYLINE_CORRELATION_ID:-}" ]; then
    printf '[claude-runner] [%s] [cid=%s] %s\n' "$_level" "$DELIVERYLINE_CORRELATION_ID" "$*" >&2
  else
    printf '[claude-runner] [%s] %s\n' "$_level" "$*" >&2
  fi
}

print_help() {
  cat <<'EOF'
deliveryline/claude-runner — runner-contracts v1 entrypoint

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

Authentication (first present wins; value never logged):
  CLAUDE_CODE_OAUTH_TOKEN          Claude Pro/Max subscription token (preferred — `claude setup-token`)
  ANTHROPIC_API_KEY                Anthropic API key (per-token billing fallback)

Claude configuration (read from env, never baked into the image — AC4):
  CLAUDE_MODEL                     model name (exported to the CLI as ANTHROPIC_MODEL)
  CLAUDE_MAX_TOKENS                max output tokens (exported as CLAUDE_CODE_MAX_OUTPUT_TOKENS)
  CLAUDE_PROMPT_TEMPLATE           path to an optional prompt template (under a mounted dir)
  CLAUDE_EXEC_ARGS                 CLI command token seam (finalized in story 3.8)

Environment (shared with the Codex runner — see runners/RUNNER_CONTRACT.md):
  DELIVERYLINE_RUNNER_STAGE        stage when --stage is not passed (production injection seam)
  DELIVERYLINE_CORRELATION_ID      optional correlation id prepended to log lines
EOF
}

# Map a raw stage token to a runner-result.v1 artifactType. Accepts the three
# story-named artifact stages AND the RunnerStage enum aliases (investigation /
# execution). Returns non-zero on an unknown token. IDENTICAL to the Codex
# runner (shared contract — runners/RUNNER_CONTRACT.md).
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
  if ! command -v "$CLAUDE_CLI_BIN" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: claude CLI '$CLAUDE_CLI_BIN' not found on PATH"
    exit 1
  fi
  _claude_version="$("$CLAUDE_CLI_BIN" --version 2>/dev/null || echo '')"
  set -- $_claude_version
  _claude_version_token="${1:-}"
  if [ "$_claude_version_token" != "$EXPECTED_CLAUDE_VERSION" ]; then
    echo "SELF-TEST FAIL: claude version '${_claude_version:-<unknown>}' does not match expected pin $EXPECTED_CLAUDE_VERSION"
    exit 1
  fi
  # Story 3a-6 — agent-side OpenSpec CLI must be present + match the pin (exact-token
  # compare, mirroring this entrypoint's claude version check above). In production this
  # is the real `openspec`; in the offline build it is the baked mock reporting the pin.
  if ! command -v "$OPENSPEC_CLI_BIN" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: openspec CLI '$OPENSPEC_CLI_BIN' not found on PATH"
    exit 1
  fi
  _openspec_version="$("$OPENSPEC_CLI_BIN" --version 2>/dev/null || echo '')"
  set -- $_openspec_version
  _openspec_version_token="${1:-}"
  if [ "$_openspec_version_token" != "$EXPECTED_OPENSPEC_VERSION" ]; then
    echo "SELF-TEST FAIL: openspec version '${_openspec_version:-<unknown>}' does not match expected pin $EXPECTED_OPENSPEC_VERSION"
    exit 1
  fi
  # Story 3a-7 — the vendored obra/superpowers skills must resolve on Claude's discovery dir.
  # `[ -d ]` follows the symlink, so it is false for BOTH a missing dir AND a dangling symlink;
  # then require a FLOOR of SKILL.md files. The pinned tree ships 14 skills; the floor (10) catches
  # a gross truncation without pinning the exact count, which a legitimate re-vendor may change.
  # Vendored via COPY (offline-safe), so this passes in the offline build the conformance IT
  # exercises — no mock (mirrors the codex entrypoint).
  if [ ! -d "$SUPERPOWERS_SKILLS_DIR" ]; then
    echo "SELF-TEST FAIL: superpowers skills not found / symlink unresolved at $SUPERPOWERS_SKILLS_DIR"
    exit 1
  fi
  _superpowers_count="$(find -L "$SUPERPOWERS_SKILLS_DIR" -maxdepth 2 -name SKILL.md 2>/dev/null | wc -l | tr -d ' ')"
  if [ "${_superpowers_count:-0}" -lt 10 ]; then
    echo "SELF-TEST FAIL: only ${_superpowers_count:-0} SKILL.md under superpowers skills dir $SUPERPOWERS_SKILLS_DIR (expected >= 10; pinned tree ships 14)"
    exit 1
  fi
  echo "deliveryline/claude-runner self-test: OK"
  echo "  entrypoint:     reachable"
  echo "  node:           $("$NODE_BIN" --version)"
  echo "  runner helper:  $RUNNER_LIB"
  echo "  claude bin:     $(command -v "$CLAUDE_CLI_BIN")"
  echo "  claude version: ${_claude_version:-<unknown>} (expected pin: $EXPECTED_CLAUDE_VERSION)"
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
PROMPT_FILE="$LOGS_DIR/claude.prompt"
if [ -n "${CLAUDE_PROMPT_TEMPLATE:-}" ] && [ -f "$CLAUDE_PROMPT_TEMPLATE" ]; then
  if ! PREPARE_OUT="$("$NODE_BIN" "$RUNNER_LIB" prepare --bundle "$BUNDLE_FILE" --prompt-out "$PROMPT_FILE" --template "$CLAUDE_PROMPT_TEMPLATE")"; then
    log ERROR "failed to parse input bundle or prompt template"
    exit 11
  fi
else
  if ! PREPARE_OUT="$("$NODE_BIN" "$RUNNER_LIB" prepare --bundle "$BUNDLE_FILE" --prompt-out "$PROMPT_FILE")"; then
    log ERROR "failed to parse input bundle (not readable / not valid JSON)"
    exit 11
  fi
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

# 4. resolve auth (story 3.5 contract). Claude supports TWO credential modes;
#    the first present wins. Value is NEVER logged — only the variable NAME +
#    presence. Subscription mode (CLAUDE_CODE_OAUTH_TOKEN — `claude setup-token`,
#    Pro/Max billing) is preferred over API mode (ANTHROPIC_API_KEY, per-token
#    billing). Both names are already what the CLI reads from env, so no remap.
AUTH_KEY=""
AUTH_KEY_VAR=""
if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
  AUTH_KEY="$CLAUDE_CODE_OAUTH_TOKEN"
  AUTH_KEY_VAR="CLAUDE_CODE_OAUTH_TOKEN"
elif [ -n "${ANTHROPIC_API_KEY:-}" ]; then
  AUTH_KEY="$ANTHROPIC_API_KEY"
  AUTH_KEY_VAR="ANTHROPIC_API_KEY"
fi
if [ -z "$AUTH_KEY" ] && [ "${DELIVERYLINE_RUNNER_SKIP_AUTH:-}" != "true" ]; then
  log ERROR "no Claude credential present — set CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY (value is never logged)"
  "$NODE_BIN" "$RUNNER_LIB" build-failure \
    --bundle "$BUNDLE_FILE" \
    --stage "$ARTIFACT_TYPE" \
    --category runner_non_zero_exit \
    --summary "No Claude credential present" \
    --out "$RESULT_FILE" >/dev/null || true
  exit 20
fi
if [ -n "$AUTH_KEY_VAR" ]; then
  log INFO "auth resolved from variable name=$AUTH_KEY_VAR (value redacted)"
  # The credential is already exported in the container env under the name the
  # CLI expects (CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY); re-export the
  # resolved one explicitly without logging the value.
  export "$AUTH_KEY_VAR"="$AUTH_KEY"
else
  log WARN "no auth key present — proceeding under DELIVERYLINE_RUNNER_SKIP_AUTH=true (mock/test only)"
fi

# 4b. Claude-specific configuration (AC4): read from env, passed through to the
#     CLI, NEVER baked into the image as constants. Logged by NAME + presence.
if [ -n "${CLAUDE_MODEL:-}" ]; then
  export ANTHROPIC_MODEL="$CLAUDE_MODEL"
  log INFO "claude model override active (variable name=CLAUDE_MODEL)"
fi
if [ -n "${CLAUDE_MAX_TOKENS:-}" ]; then
  export CLAUDE_CODE_MAX_OUTPUT_TOKENS="$CLAUDE_MAX_TOKENS"
  log INFO "claude max-tokens override active (variable name=CLAUDE_MAX_TOKENS)"
fi
if [ -n "${CLAUDE_PROMPT_TEMPLATE:-}" ]; then
  if [ -f "$CLAUDE_PROMPT_TEMPLATE" ]; then
    log INFO "claude prompt-template configured (variable name=CLAUDE_PROMPT_TEMPLATE present=true)"
  else
    log WARN "CLAUDE_PROMPT_TEMPLATE set but no file at the configured path (name only; value never logged)"
  fi
fi

# 5. invoke the Claude Code CLI; capture raw stdout/stderr to the logs mount.
#    Story 3.8 — the headless, non-interactive argument vector:
#      - `-p` (print) subcommand: non-interactive mode, prompt on stdin (overridable
#        via CLAUDE_EXEC_ARGS for forward-compat).
#      - --dangerously-skip-permissions: a headless run has no TTY to answer Claude
#        Code's tool-permission / folder-trust prompts; the CONTAINER is the sandbox
#        (non-root `claude` user, backend-imposed network policy, read-only input).
#        This is the Claude analog of the Codex --dangerously-bypass-* flag. Claude
#        Code refuses this flag when running as root — the image runs as the
#        unprivileged `claude` user (Dockerfile USER claude), so it is permitted.
#      - working root: when the backend mounted the linked repository, Claude is run
#        with /workspace/repo as its cwd (Claude Code has no -C flag; the cwd IS the
#        project root) so it analyses the real tree. No mount → run in place.
#    The model is passed via ANTHROPIC_MODEL (exported above from CLAUDE_MODEL), so no
#    --model flag is needed. CLAUDE_EXEC_ARGS stays a single shell-safe token.
CLAUDE_SUBCOMMAND="${CLAUDE_EXEC_ARGS:--p}"
case "$CLAUDE_SUBCOMMAND" in
  *[!A-Za-z0-9_./:=,-]*)
    log ERROR "CLAUDE_EXEC_ARGS contains unsupported characters; configure a single safe command token"
    "$NODE_BIN" "$RUNNER_LIB" build-failure \
      --bundle "$BUNDLE_FILE" \
      --stage "$ARTIFACT_TYPE" \
      --category runner_non_zero_exit \
      --summary "Claude command configuration is invalid" \
      --out "$RESULT_FILE" >/dev/null || true
    exit 2
    ;;
esac
# Assemble the argv with `set --` (no eval, no word-splitting of untrusted values).
set -- "$CLAUDE_SUBCOMMAND" --dangerously-skip-permissions
CLAUDE_REPO_DIR="${DELIVERYLINE_REPO_DIR:-/workspace/repo}"
log INFO "claude invocation start bin=$CLAUDE_CLI_BIN subcommand=$CLAUDE_SUBCOMMAND repoDir=$CLAUDE_REPO_DIR argCount=$#"
set +e
if [ -d "$CLAUDE_REPO_DIR" ]; then
  # Run with the repo as cwd (Claude Code's project root). The subshell isolates the
  # cd; every mount path used after this is absolute, so the rest of the script is
  # unaffected. exec lets the redirections + cwd reach the CLI and $? be its exit.
  ( cd "$CLAUDE_REPO_DIR" && exec "$CLAUDE_CLI_BIN" "$@" ) \
    <"$PROMPT_FILE" >"$STDOUT_LOG" 2>"$STDERR_LOG"
else
  "$CLAUDE_CLI_BIN" "$@" <"$PROMPT_FILE" >"$STDOUT_LOG" 2>"$STDERR_LOG"
fi
CLAUDE_RC=$?
set -e
log INFO "claude invocation finished rc=$CLAUDE_RC stdoutBytes=$(wc -c <"$STDOUT_LOG" 2>/dev/null | tr -d ' ' || echo 0)"
if [ "$CLAUDE_RC" -ne 0 ]; then
  log ERROR "claude CLI exited non-zero rc=$CLAUDE_RC (see runner.stderr)"
  "$NODE_BIN" "$RUNNER_LIB" build-failure \
    --bundle "$BUNDLE_FILE" \
    --stage "$ARTIFACT_TYPE" \
    --category runner_non_zero_exit \
    --summary "Claude CLI exited non-zero" \
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
