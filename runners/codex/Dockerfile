# syntax=docker/dockerfile:1
#
# Story 3.3 — deliveryline/codex-runner image.
#
# A runner container that conforms to runner-contracts v1: it reads the input
# bundle the backend bind-mounts read-only at /workspace/input, invokes the
# Codex CLI, and writes a runner-result.v1 document to the read-write
# /workspace/output mount (plus raw logs to /workspace/logs). The backend's
# DockerRunnerAdapter launches it with --network=none; see runners/codex/README.md.
#
# Build context is the REPO ROOT (so the entrypoint/lib/test files resolve):
#   docker build -f runners/codex/Dockerfile -t deliveryline/codex-runner:latest .
# or via compose:
#   docker compose build codex-runner
#
# Single-stage rationale (documented deviation from the "multi-stage" task hint,
# see README "Base-image choice"): the Codex CLI is an interpreted npm-global
# package that needs the same Node runtime at run time, so a builder/runtime
# split yields no size win and risks clobbering the base image's /usr/local/bin
# node/npm symlinks. We keep layers lean instead (npm cache cleaned).

ARG BASE_IMAGE=node:22-slim
FROM ${BASE_IMAGE}

# --- build args --------------------------------------------------------------
# Codex CLI version pin — NO floating "latest". Confirm the current published
# version with `npm view @openai/codex version` before bumping (see README
# "Codex CLI version pinning + upgrade procedure").
ARG CODEX_CLI_VERSION=0.135.0
# OpenSpec CLI version pin (story 3a-6) — NO floating "latest". Confirm the
# current published version with `npm view @fission-ai/openspec version` before
# bumping (see README "OpenSpec version pinning + upgrade procedure"). OpenSpec
# is a pure-JS npm-global (binary `openspec`, requires Node >=20.19 — satisfied
# by node:22-slim, no base-image change), installed in the SAME npm layer as the
# Codex CLI (real install in production; a deterministic mock in the offline build).
ARG OPENSPEC_VERSION=1.4.1
# obra/superpowers skills pin (story 3a-7) — a vendored COMMIT SHA, NO floating "main".
# The skills are vendored into runners/vendor/superpowers (tag v5.1.0 → this commit; see
# runners/vendor/VENDOR.md) and COPY'd into BOTH builds (no git, no network — unlike a
# clone). --self-test reports this pin; re-vendor + bump per the README "superpowers pin"
# section (mirror runners/claude/Dockerfile — same PR).
ARG SUPERPOWERS_PIN=f2cbfbefebbfef77321e4c9abc9e949826bea9d7
# Image version label for CI (override: --build-arg IMAGE_VERSION=<ci-tag>).
ARG IMAGE_VERSION=latest
# When false, skip the real npm install and bake the deterministic mock CLI used
# by the contract-conformance test (AC4). Production builds keep this true.
ARG INSTALL_CODEX_CLI=true

LABEL org.opencontainers.image.title="deliveryline-codex-runner" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.description="DeliveryLine Codex runner (runner-contracts v1, file-based, --network=none)" \
      org.opencontainers.image.source="https://github.com/deliveryline/deliveryline"

# Surface the pin to the entrypoint (used by --self-test version reporting) and
# fix the helper/bin locations the entrypoint resolves by default.
ENV CODEX_CLI_VERSION=${CODEX_CLI_VERSION} \
    OPENSPEC_VERSION=${OPENSPEC_VERSION} \
    SUPERPOWERS_PIN=${SUPERPOWERS_PIN} \
    CODEX_CLI_BIN=codex \
    NODE_BIN=node \
    DELIVERYLINE_RUNNER_LIB=/opt/deliveryline/lib/runner.mjs \
    DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE=/opt/deliveryline/test/provider-usage.mock.json \
    DELIVERYLINE_USAGE_MOCK_FILE=/opt/deliveryline/test/usage.mock.json

WORKDIR /opt/deliveryline

# Copy the contract surface (entrypoint + node helper) and the test-only mock.
COPY runners/codex/entrypoint.sh /opt/deliveryline/entrypoint.sh
COPY runners/codex/lib/runner.mjs /opt/deliveryline/lib/runner.mjs
COPY runners/codex/test/mock-codex.sh /opt/deliveryline/test/mock-codex.sh
COPY runners/codex/test/mock-openspec.sh /opt/deliveryline/test/mock-openspec.sh

# Story 3a-7 — vendored obra/superpowers skills (pinned commit; see runners/vendor/VENDOR.md).
# COPY (not git clone): present in BOTH the production and the offline/mock build, no `git`
# apt layer, no build-time network — so --self-test's skills assertion is green offline. Staged
# under /opt; the RUN below moves it into the codex HOME + wires the ~/.agents/skills symlink
# (after useradd creates /home/codex). --chown=1001:1001 = the codex uid/gid created below.
COPY --chown=1001:1001 runners/vendor/superpowers /opt/deliveryline/vendor/superpowers

# Install the Codex CLI + the agent-side OpenSpec CLI (production) OR bake their
# deterministic mocks (test build), make the contract scripts executable, and
# create the unprivileged runtime user — all in one layer to keep the image lean
# (AC7 non-root, AC9 layer budget; story 3a-6 adds openspec to this same layer).
RUN set -eu; \
    if [ "${INSTALL_CODEX_CLI}" = "true" ]; then \
      # The real Codex CLI (Rust) needs a system trust store for TLS to the OpenAI /
      # ChatGPT endpoints; node:22-slim ships none ("no native root CA certificates
      # found"). The mock build needs no egress, so it stays cert-free + offline.
      # Agent-side exploration tooling: `ripgrep` (Codex's default file/content search —
      # it reaches for `rg` first, falling back to slow, non-.gitignore-aware `find`/`grep`
      # without it), `git` (the cloned /workspace/repo carries a real `.git`, so
      # status/diff/log/ls-files/grep work — notably `git diff` for self-review at
      # pr-output), and `jq` (the agent reaches for it to inspect the JSON context-bundle /
      # review inputs under /workspace/input; without it, it degrades to fragile shell reads
      # of the JSON — the runner's own bundle parsing uses node, so jq is purely agent-side).
      # Real build only; the mock/echo agent does not explore.
      apt-get update; \
      apt-get install -y --no-install-recommends ca-certificates git ripgrep jq; \
      update-ca-certificates; \
      npm install -g "@openai/codex@${CODEX_CLI_VERSION}"; \
      # Story 3a-6 — agent-side tooling: pure-JS OpenSpec CLI, pinned, same layer.
      npm install -g "@fission-ai/openspec@${OPENSPEC_VERSION}"; \
      npm cache clean --force; \
    else \
      install -m 0755 /opt/deliveryline/test/mock-codex.sh /usr/local/bin/codex; \
      install -m 0755 /opt/deliveryline/test/mock-openspec.sh /usr/local/bin/openspec; \
      # Story 3d-7 — bake the deterministic provider-usage signal ONLY into the offline/mock
      # build (DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE points here). runner.mjs reads it and emits
      # the `available` shape so the capture->persist->surface happy path is covered offline + in
      # the conformance ITs. Production builds never create this file, so the real path correctly
      # emits the `not_exposed` marker (spike outcome) — never a fabricated number. NO secret here.
      printf '%s\n' '{"signalState":"available","fiveHour":{"usedFraction":0.62,"used":62,"limit":100,"resetsAt":"2030-01-01T05:00:00Z"},"weekly":{"usedFraction":0.18,"used":126,"limit":700,"resetsAt":"2030-01-06T00:00:00Z"}}' > /opt/deliveryline/test/provider-usage.mock.json; \
      # Story 3g-3 — bake the deterministic per-execution TOKEN usage ONLY into the offline/mock
      # build (DELIVERYLINE_USAGE_MOCK_FILE points here). runner.mjs reads it and emits the
      # normalizedOutput.usage block so the capture->persist happy path is covered offline + in the
      # conformance ITs. Production builds never create this file, so the real path omits `usage`
      # entirely (the honest "not reported" state) — never a fabricated count. Token counts are
      # non-secret numeric governed data; NO secret here.
      printf '%s\n' '{"inputTokens":1200,"outputTokens":800,"totalTokens":2000}' > /opt/deliveryline/test/usage.mock.json; \
    fi; \
    chmod +x /opt/deliveryline/entrypoint.sh /opt/deliveryline/lib/runner.mjs; \
    groupadd --gid 1001 codex; \
    useradd --uid 1001 --gid 1001 --create-home --shell /usr/sbin/nologin codex; \
    # Story 3a-7 — place the vendored superpowers tree in the codex HOME and expose its
    # skills/ dir on Codex's auto-discovery path (~/.agents/skills/). Codex scans
    # ~/.agents/skills/ at startup, so `superpowers -> .../skills` makes the 14 skills
    # discoverable. Owned codex:1001 (least privilege). Runs in BOTH build branches.
    mkdir -p /home/codex/.codex /home/codex/.agents/skills; \
    mv /opt/deliveryline/vendor/superpowers /home/codex/.codex/superpowers; \
    ln -s /home/codex/.codex/superpowers/skills /home/codex/.agents/skills/superpowers; \
    chown -R codex:codex /home/codex/.codex /home/codex/.agents; \
    rm -rf /var/lib/apt/lists/*

# Least privilege (AC7): unprivileged user, no network by default (set by the
# backend at create time), secrets consumed from env and never echoed.
USER codex

# Exec form so signals (timeout/kill from story 3.2) reach the entrypoint directly.
ENTRYPOINT ["/opt/deliveryline/entrypoint.sh"]
