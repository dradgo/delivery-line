# syntax=docker/dockerfile:1
#
# Story 3.4 — deliveryline/claude-runner image (the Claude twin of story 3.3's
# deliveryline/codex-runner; mirror, do not re-derive).
#
# A runner container that conforms to runner-contracts v1: it reads the input
# bundle the backend bind-mounts read-only at /workspace/input, invokes the
# Claude Code CLI, and writes a runner-result.v1 document to the read-write
# /workspace/output mount (plus raw logs to /workspace/logs). The backend's
# DockerRunnerAdapter launches it with --network=none; see runners/claude/README.md
# and the shared runners/RUNNER_CONTRACT.md.
#
# Build context is the REPO ROOT (so the entrypoint/lib/test files resolve):
#   docker build -f runners/claude/Dockerfile -t deliveryline/claude-runner:latest .
# or via compose:
#   docker compose build claude-runner
#
# Single-stage rationale (documented deviation from the "multi-stage" task hint,
# see README "Base-image choice"): the Claude Code CLI is an interpreted
# npm-global package that needs the same Node runtime at run time, so a
# builder/runtime split yields no size win and risks clobbering the base image's
# /usr/local/bin node/npm symlinks. We keep layers lean instead (npm cache cleaned).

ARG BASE_IMAGE=node:22-slim
FROM ${BASE_IMAGE}

# --- build args --------------------------------------------------------------
# Claude Code CLI version pin — NO floating "latest". Confirm the current
# published version with `npm view @anthropic-ai/claude-code version` before
# bumping (see README "Claude Code CLI version pinning + upgrade procedure").
# 2.1.149 is the `stable` dist-tag at authoring time (latest was 2.1.158).
ARG CLAUDE_CLI_VERSION=2.1.149
# OpenSpec CLI version pin (story 3a-6) — NO floating "latest". Confirm the
# current published version with `npm view @fission-ai/openspec version` before
# bumping (see README "OpenSpec version pinning + upgrade procedure"). OpenSpec
# is a pure-JS npm-global (binary `openspec`, requires Node >=20.19 — satisfied
# by node:22-slim, no base-image change), installed in the SAME npm layer as the
# Claude CLI (real install in production; a deterministic mock in the offline build).
ARG OPENSPEC_VERSION=1.4.1
# obra/superpowers skills pin (story 3a-7) — a vendored COMMIT SHA, NO floating "main".
# The skills are vendored into runners/vendor/superpowers (tag v5.1.0 → this commit; see
# runners/vendor/VENDOR.md) and COPY'd into BOTH builds (no git, no network — unlike a
# clone). --self-test reports this pin; re-vendor + bump per the README "superpowers pin"
# section (mirror runners/codex/Dockerfile — same PR).
ARG SUPERPOWERS_PIN=f2cbfbefebbfef77321e4c9abc9e949826bea9d7
# Image version label for CI (override: --build-arg IMAGE_VERSION=<ci-tag>).
ARG IMAGE_VERSION=latest
# When false, skip the real npm install and bake the deterministic mock CLI used
# by the contract-conformance test (AC8). Production builds keep this true.
ARG INSTALL_CLAUDE_CLI=true

LABEL org.opencontainers.image.title="deliveryline-claude-runner" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.description="DeliveryLine Claude runner (runner-contracts v1, file-based, --network=none)" \
      org.opencontainers.image.source="https://github.com/deliveryline/deliveryline"

# Surface the pin to the entrypoint (used by --self-test version reporting) and
# fix the helper/bin locations the entrypoint resolves by default.
ENV CLAUDE_CLI_VERSION=${CLAUDE_CLI_VERSION} \
    OPENSPEC_VERSION=${OPENSPEC_VERSION} \
    SUPERPOWERS_PIN=${SUPERPOWERS_PIN} \
    CLAUDE_CLI_BIN=claude \
    NODE_BIN=node \
    DELIVERYLINE_RUNNER_LIB=/opt/deliveryline/lib/runner.mjs

WORKDIR /opt/deliveryline

# Copy the contract surface (entrypoint + node helper) and the test-only mock.
COPY runners/claude/entrypoint.sh /opt/deliveryline/entrypoint.sh
COPY runners/claude/lib/runner.mjs /opt/deliveryline/lib/runner.mjs
COPY runners/claude/test/mock-claude.sh /opt/deliveryline/test/mock-claude.sh
COPY runners/claude/test/mock-openspec.sh /opt/deliveryline/test/mock-openspec.sh

# Story 3a-7 — vendored obra/superpowers skills (pinned commit; see runners/vendor/VENDOR.md).
# Mirror of the codex runner — COPY (not git clone): present in BOTH the production and the
# offline/mock build, no `git` apt layer, no build-time network, so --self-test's skills
# assertion is green offline. Staged under /opt; the RUN below moves it into the claude HOME +
# exposes it on Claude's skills-discovery dir. --chown=1001:1001 = the claude uid/gid below.
COPY --chown=1001:1001 runners/vendor/superpowers /opt/deliveryline/vendor/superpowers

# Install the Claude Code CLI + the agent-side OpenSpec CLI (production) OR bake
# their deterministic mocks (test build), make the contract scripts executable,
# and create the unprivileged runtime user — all in one layer to keep the image
# lean (AC2 non-root, AC9 layer budget; story 3a-6 adds openspec to this layer).
RUN set -eu; \
    if [ "${INSTALL_CLAUDE_CLI}" = "true" ]; then \
      # The real Claude Code CLI needs a system trust store for TLS to the Anthropic
      # endpoints; node:22-slim ships none. The mock build needs no egress, so it
      # stays cert-free + offline.
      apt-get update; \
      apt-get install -y --no-install-recommends ca-certificates; \
      update-ca-certificates; \
      npm install -g "@anthropic-ai/claude-code@${CLAUDE_CLI_VERSION}"; \
      # Story 3a-6 — agent-side tooling: pure-JS OpenSpec CLI, pinned, same layer.
      npm install -g "@fission-ai/openspec@${OPENSPEC_VERSION}"; \
      npm cache clean --force; \
    else \
      install -m 0755 /opt/deliveryline/test/mock-claude.sh /usr/local/bin/claude; \
      install -m 0755 /opt/deliveryline/test/mock-openspec.sh /usr/local/bin/openspec; \
    fi; \
    chmod +x /opt/deliveryline/entrypoint.sh /opt/deliveryline/lib/runner.mjs; \
    groupadd --gid 1001 claude; \
    useradd --uid 1001 --gid 1001 --create-home --shell /usr/sbin/nologin claude; \
    # Story 3a-7 — place the vendored superpowers tree in the claude HOME and expose its
    # skills/ dir on Claude Code's personal-skills discovery dir (~/.claude/skills/; the
    # offline filesystem target — the marketplace/plugin route needs network, out of scope).
    # `superpowers -> .../skills` mirrors the codex symlink. Owned claude:1001. BOTH branches.
    mkdir -p /home/claude/.claude /home/claude/.claude/skills; \
    mv /opt/deliveryline/vendor/superpowers /home/claude/.claude/superpowers; \
    ln -s /home/claude/.claude/superpowers/skills /home/claude/.claude/skills/superpowers; \
    chown -R claude:claude /home/claude/.claude; \
    rm -rf /var/lib/apt/lists/*

# Least privilege (AC2): unprivileged user, no network by default (set by the
# backend at create time), secrets consumed from env and never echoed. uid/gid
# 1001 because node:22-slim already occupies 1000 with its own `node` user.
USER claude

# Exec form so signals (timeout/kill from story 3.2) reach the entrypoint directly.
ENTRYPOINT ["/opt/deliveryline/entrypoint.sh"]
