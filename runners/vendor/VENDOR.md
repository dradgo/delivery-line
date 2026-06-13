# Vendored third-party trees (`runners/vendor/`)

Static third-party content vendored into the repo so the runner images can `COPY` it at
build time with **no `git` and no network** — present in both the production
(`INSTALL_*_CLI=true`) and the offline/mock (`INSTALL_*_CLI=false`) builds the conformance
ITs + CI `runner-image-compat` line exercise. See story 3a-7 and the
[vendor-vs-clone decision](../../_bmad-output/implementation-artifacts/3a-7-superpowers-skills-in-runner-images.md).

## `superpowers/` — obra/superpowers skills (story 3a-7)

| Field | Value |
|-------|-------|
| Source | <https://github.com/obra/superpowers> |
| Pinned commit | `f2cbfbefebbfef77321e4c9abc9e949826bea9d7` |
| Release tag | `v5.1.0` (annotated-tag object `ecbd610fce16d5faabcea997f17031129589b572` → that commit) |
| License | MIT (see `superpowers/LICENSE`) — permits vendoring/redistribution |
| Vendored on | 2026-06-13 |
| Contents | the upstream working tree at the pinned commit **minus `.git/`** (skills, docs, assets, hooks, scripts, plugin manifests) |

The agent-relevant payload is `superpowers/skills/<name>/SKILL.md` (14 skills at the pinned
commit). Both runner Dockerfiles `COPY` this tree into the image and expose its `skills/`
subdir on each agent's skills-discovery path (codex `~/.agents/skills/superpowers`, claude
`~/.claude/skills/superpowers`). The pinned commit is surfaced as `ENV SUPERPOWERS_PIN` and
asserted by each entrypoint's `--self-test`.

### Re-vendor / update procedure (bump the pin)

```bash
# 1. Pick the new commit (or resolve a release tag to its commit):
git ls-remote https://github.com/obra/superpowers.git refs/tags/<tag>   # -> tag-object sha
NEWPIN=<commit-sha>          # the COMMIT the tag points to, not the tag object

# 2. Re-vendor the tree minus .git into runners/vendor/superpowers/:
tmp=$(mktemp -d); git clone --no-checkout https://github.com/obra/superpowers.git "$tmp/sp"
git -C "$tmp/sp" checkout "$NEWPIN"
rm -rf runners/vendor/superpowers && mkdir -p runners/vendor/superpowers
( cd "$tmp/sp" && tar --exclude=./.git -cf - . ) | ( cd runners/vendor/superpowers && tar -xf - )

# 3. Bump ARG SUPERPOWERS_PIN in BOTH Dockerfiles (mirror rule — same PR), update this
#    note + both README size tables, then rebuild + self-test BOTH images:
docker build -f runners/codex/Dockerfile  --build-arg INSTALL_CODEX_CLI=false  -t deliveryline/codex-runner:it .
docker run --rm deliveryline/codex-runner:it --self-test     # claude twin too
```

> Per the `RUNNER_CONTRACT.md` mirror rule, a re-vendor edits **both** runner Dockerfiles +
> entrypoints + READMEs in the same PR. Keep the pin a **commit SHA**, never a floating branch.
