#!/usr/bin/env bash
# scripts/install-git-hooks.sh — DeliveryLine optional git-hook installer (story 2.30).
#
# Installs an OPT-IN pre-commit hook that runs a fast backend format + style
# check (Spotless + Checkstyle) before each commit. SpotBugs is deliberately
# excluded — its effort=Max analysis is too slow for a commit hook.
#
# This is recommended but NOT required: nothing installs or runs the hook for
# you. CI's format-static-checks tier is the real gate. See docs/setup-local.md.
#
# Usage:
#   scripts/install-git-hooks.sh              install (or refresh) the pre-commit hook
#   scripts/install-git-hooks.sh --uninstall  remove the DeliveryLine pre-commit hook
#   scripts/install-git-hooks.sh --force      replace a non-DeliveryLine pre-commit
#                                             hook (the existing one is backed up first)
#
# Bypass the hook for a single commit with:  git commit --no-verify

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! REPO_ROOT="$(git -C "${SCRIPT_DIR}/.." rev-parse --show-toplevel 2>/dev/null)"; then
  echo "error: could not resolve the git worktree root from ${SCRIPT_DIR}/.." >&2
  exit 1
fi
if ! HOOKS_DIR="$(git -C "${REPO_ROOT}" rev-parse --path-format=absolute --git-path hooks 2>/dev/null)"; then
  echo "error: could not resolve the active Git hooks directory via 'git rev-parse --git-path hooks'." >&2
  exit 1
fi
PRE_COMMIT="${HOOKS_DIR}/pre-commit"
MARKER="# DeliveryLine-managed pre-commit hook (story 2.30)"

UNINSTALL=0
FORCE=0
for arg in "$@"; do
  case "${arg}" in
    --uninstall) UNINSTALL=1 ;;
    --force) FORCE=1 ;;
    *) echo "error: unknown option '${arg}' (expected --uninstall or --force)" >&2; exit 1 ;;
  esac
done

mkdir -p "${HOOKS_DIR}"

if [ "${UNINSTALL}" -eq 1 ]; then
  if [ -f "${PRE_COMMIT}" ] && grep -qF "${MARKER}" "${PRE_COMMIT}"; then
    rm -f "${PRE_COMMIT}"
    echo "Removed the DeliveryLine pre-commit hook."
  else
    echo "No DeliveryLine pre-commit hook installed — nothing to do."
  fi
  exit 0
fi

# Non-destructive: never clobber a hook we did not write without --force.
if [ -f "${PRE_COMMIT}" ] && ! grep -qF "${MARKER}" "${PRE_COMMIT}"; then
  if [ "${FORCE}" -eq 1 ]; then
    cp "${PRE_COMMIT}" "${PRE_COMMIT}.backup"
    echo "Existing pre-commit hook backed up to ${PRE_COMMIT}.backup"
  else
    echo "error: a non-DeliveryLine pre-commit hook already exists at ${PRE_COMMIT}." >&2
    echo "       Re-run with --force to back it up and replace it." >&2
    exit 1
  fi
fi

cat > "${PRE_COMMIT}" <<'HOOK'
#!/usr/bin/env bash
# DeliveryLine-managed pre-commit hook (story 2.30)
# Installed by scripts/install-git-hooks.sh — do not edit by hand; re-run the
# installer to refresh it, or scripts/install-git-hooks.sh --uninstall to remove.
# Runs a fast backend format + style check before each commit.
# Bypass once:  git commit --no-verify
set -euo pipefail
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "${REPO_ROOT}"
echo "[pre-commit] Spotless + Checkstyle (deliveryline-backend)…"
MVNW="./mvnw"
if [ ! -x "${MVNW}" ]; then MVNW="sh ./mvnw"; fi
if ! ${MVNW} -B -ntp -q -pl deliveryline-backend -am spotless:check checkstyle:check; then
  echo "[pre-commit] FAILED — run './mvnw spotless:apply' to auto-fix formatting," >&2
  echo "[pre-commit]          then address any remaining Checkstyle violations and re-stage." >&2
  echo "[pre-commit] To commit without this check: git commit --no-verify" >&2
  exit 1
fi
echo "[pre-commit] OK"
HOOK

chmod +x "${PRE_COMMIT}"
echo "Installed the DeliveryLine pre-commit hook at ${PRE_COMMIT}"
echo "It runs Spotless + Checkstyle before each commit. The target hook path came from 'git rev-parse --git-path hooks'."
echo "Bypass once with 'git commit --no-verify'."
