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
    CLAUDE_CLI_BIN=claude \
    NODE_BIN=node \
    DELIVERYLINE_RUNNER_LIB=/opt/deliveryline/lib/runner.mjs

WORKDIR /opt/deliveryline

# Copy the contract surface (entrypoint + node helper) and the test-only mock.
COPY runners/claude/entrypoint.sh /opt/deliveryline/entrypoint.sh
COPY runners/claude/lib/runner.mjs /opt/deliveryline/lib/runner.mjs
COPY runners/claude/test/mock-claude.sh /opt/deliveryline/test/mock-claude.sh

# Install the Claude Code CLI (production) OR bake the deterministic mock (test
# build), make the contract scripts executable, and create the unprivileged
# runtime user — all in one layer to keep the image lean (AC2 non-root, AC9
# layer budget).
RUN set -eu; \
    if [ "${INSTALL_CLAUDE_CLI}" = "true" ]; then \
      npm install -g "@anthropic-ai/claude-code@${CLAUDE_CLI_VERSION}"; \
      npm cache clean --force; \
    else \
      install -m 0755 /opt/deliveryline/test/mock-claude.sh /usr/local/bin/claude; \
    fi; \
    chmod +x /opt/deliveryline/entrypoint.sh /opt/deliveryline/lib/runner.mjs; \
    groupadd --gid 1001 claude; \
    useradd --uid 1001 --gid 1001 --create-home --shell /usr/sbin/nologin claude; \
    rm -rf /var/lib/apt/lists/*

# Least privilege (AC2): unprivileged user, no network by default (set by the
# backend at create time), secrets consumed from env and never echoed. uid/gid
# 1001 because node:22-slim already occupies 1000 with its own `node` user.
USER claude

# Exec form so signals (timeout/kill from story 3.2) reach the entrypoint directly.
ENTRYPOINT ["/opt/deliveryline/entrypoint.sh"]
