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
    CODEX_CLI_BIN=codex \
    NODE_BIN=node \
    DELIVERYLINE_RUNNER_LIB=/opt/deliveryline/lib/runner.mjs

WORKDIR /opt/deliveryline

# Copy the contract surface (entrypoint + node helper) and the test-only mock.
COPY runners/codex/entrypoint.sh /opt/deliveryline/entrypoint.sh
COPY runners/codex/lib/runner.mjs /opt/deliveryline/lib/runner.mjs
COPY runners/codex/test/mock-codex.sh /opt/deliveryline/test/mock-codex.sh

# Install the Codex CLI (production) OR bake the deterministic mock (test build),
# make the contract scripts executable, and create the unprivileged runtime user
# — all in one layer to keep the image lean (AC7 non-root, AC9 layer budget).
RUN set -eu; \
    if [ "${INSTALL_CODEX_CLI}" = "true" ]; then \
      # The real Codex CLI (Rust) needs a system trust store for TLS to the OpenAI /
      # ChatGPT endpoints; node:22-slim ships none ("no native root CA certificates
      # found"). The mock build needs no egress, so it stays cert-free + offline.
      apt-get update; \
      apt-get install -y --no-install-recommends ca-certificates; \
      update-ca-certificates; \
      npm install -g "@openai/codex@${CODEX_CLI_VERSION}"; \
      npm cache clean --force; \
    else \
      install -m 0755 /opt/deliveryline/test/mock-codex.sh /usr/local/bin/codex; \
    fi; \
    chmod +x /opt/deliveryline/entrypoint.sh /opt/deliveryline/lib/runner.mjs; \
    groupadd --gid 1001 codex; \
    useradd --uid 1001 --gid 1001 --create-home --shell /usr/sbin/nologin codex; \
    rm -rf /var/lib/apt/lists/*

# Least privilege (AC7): unprivileged user, no network by default (set by the
# backend at create time), secrets consumed from env and never echoed.
USER codex

# Exec form so signals (timeout/kill from story 3.2) reach the entrypoint directly.
ENTRYPOINT ["/opt/deliveryline/entrypoint.sh"]
