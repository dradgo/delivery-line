#!/usr/bin/env bash
# Story 2.24 AC16 — regenerate the frontend's redaction-policy.generated.json
# from the canonical runner-contracts source. Trap T7 — keep this simple; no
# Docker, no platform shims. The contract test in deliveryline-runner-contracts
# pins drift between the two files.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_path="$repo_root/deliveryline-runner-contracts/src/main/resources/runner-contracts/redaction-policy.json"
target_path="$repo_root/deliveryline-frontend/src/lib/sanitization/redaction-policy.generated.json"

if [[ ! -f "$source_path" ]]; then
  echo "ERROR: canonical policy missing at $source_path" >&2
  exit 1
fi

cp "$source_path" "$target_path"
if command -v sha256sum >/dev/null 2>&1; then
  sha=$(sha256sum "$target_path" | awk '{print $1}')
else
  sha=$(shasum -a 256 "$target_path" | awk '{print $1}')
fi
echo "Copied redaction-policy.json -> $target_path"
echo "sha256: $sha"
