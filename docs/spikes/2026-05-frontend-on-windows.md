# Spike: Frontend-on-Windows Tooling

**Owner:** Dana (QA) + Elena (Junior Dev)
**Status:** Running in parallel with Story 2.1
**Relationship to Story 2.1:** Parallel risk-discovery (not a prerequisite — downgraded
                  per `sprint-change-proposal-2026-05-19-followup.md`)
**Triggered by:** Epic 1 retrospective (2026-05-19), action A4;
                  sprint-change proposal 2026-05-19;
                  follow-up sprint-change proposal 2026-05-19 (downgrade rationale)
**Output:** Findings + recommended configuration; folded into 2.1 AC9 mid-flight,
                  or spawns a follow-up story if a blocker surfaces that 2.1 cannot absorb

## Why this spike exists

Epic 1 story 1.17 (Supported Environment Matrix) surfaced that Windows + Linux need
different Docker strategies, shell scripting, and probe logic. Earlier stories had
already hardened Linux-only assumptions; the Windows doctor-smoke job was collapsed
to Ubuntu-only as a pragmatic unblock.

Epic 2 introduces a *new* tooling tier — Node + npm + Vite + frontend-maven-plugin
— which has its own Windows-specific surprises (line endings, path lengths,
file-locking, dev-server ports). If we discover those in story 2-15 or later, we're
6+ stories deep into Linux-only assumptions. The fix is to discover them *before*
story 2.1 ships, codify the findings into 2.1's ACs (AC7/AC8/AC9 per
sprint-change-proposal-2026-05-19.md), and prevent the pattern from repeating.

## Questions to answer

The spike is complete when each of these has a documented answer:

### Q1 — frontend-maven-plugin Node bundling on Windows

> **Sequencing note (per follow-up sprint-change):** Q1 requires the frontend module
> Story 2.1 creates — there is no `deliveryline-frontend/pom.xml` to invoke until 2.1
> lands its scaffold. Q1 therefore runs **against the in-flight 2.1 branch** (not before
> 2.1 starts). Q2–Q5 below have no such dependency and can run any time. If Q1 surfaces
> a blocker (e.g., `frontend-maven-plugin` doesn't work on Windows), the resolution
> options stay the same as the original spike charter: absorb into 2.1, descope Windows
> from 2.1 with formal sprint-change, or replace the offending tool.

Does `frontend-maven-plugin` (or chosen equivalent) reliably download and execute
Node 20.19+ / 22.12+ on a fresh `windows-latest` GitHub Actions runner without
admin privileges or PowerShell execution-policy tweaks?

- Run `mvn -pl deliveryline-frontend clean install` on a fresh Windows runner
  (against the Story 2.1 branch once scaffolded)
- Verify Node binary is downloaded to module-local cache (NOT global PATH)
- Verify npm scripts execute via the bundled Node (no system Node interference)
- Document any execution-policy / PATH / antivirus surprises

### Q2 — Line endings + .gitattributes

Do CRLF / LF differences corrupt the React build output, snapshot tests, or any
generated files committed back to git?

- Clone the repo on Windows with default `core.autocrlf=true`
- Run `npm ci && npm run build` — verify generated lockfile and dist output
  don't differ from Linux baseline
- Determine the right `.gitattributes` policy:
  `* text=auto eol=lf` + `*.bat *.cmd text eol=crlf` is the recommended starting
  point; confirm it works for `package-lock.json`, `tsconfig.json`, source files,
  and any future snapshot fixture files (Vitest snapshots are sensitive to EOL)
- Document the `.gitattributes` declaration that 2.1 AC9 should ship

### Q3 — node_modules path length

Does any transitive dependency (likely React + Vite + shadcn/ui + tooling toolchain)
produce a path longer than `MAX_PATH=260` characters when nested under a typical
Windows user directory (e.g., `C:\Users\<long-name>\Documents\Personal\ai-hackaton-1\deliveryline-frontend\node_modules\...`)?

- Install full dep tree on Windows
- Find the longest path:
  `Get-ChildItem -Recurse | Sort-Object { $_.FullName.Length } -Descending | Select -First 5`
- If any exceed 260 chars, document mitigations:
  - Option A: Enable Win10+ long-paths support (registry + manifest) — requires
    user opt-in, document in `frontend/README.md`
  - Option B: Move the project root closer to drive root (`C:\dev\dl\`)
  - Option C: Replace the offending transitive dep
- Pick a recommended mitigation; 2.1 AC9 ships it

### Q4 — Vite dev-server port + proxy

Does `npm run dev` (Vite dev server on port 5173, proxying `/api/*` to Spring Boot
on `localhost:8080`) work identically on Windows PowerShell, Windows Git Bash,
Ubuntu, and macOS?

- Start backend (`mvn spring-boot:run`) on each OS
- Start frontend (`npm run dev`) — verify dev server binds, HMR works, proxy
  forwards `/api/*` to backend
- Verify a known port conflict path (something already on 5173) produces a
  documented error, not a silent fall-through
- Document the `PORT` env override mechanism + add it to AC9c

### Q5 — File-locking + HMR

Does Vite's file-watching HMR survive Windows' file-locking semantics during a
production-typical session (edit, save, HMR-reload, repeat)?

- Run a 5-minute interactive session on Windows: edit `src/App.tsx`, save,
  observe HMR — repeat 10+ times
- Verify no `EBUSY` / file-locked errors, no manual dev-server restarts required
- Document any antivirus interference patterns observed

## Time box

This is a tooling-discovery spike, not a feature delivery. Time-box: one focused
work session (target: 2-3 hours of active investigation + 30 min report writing).
If any question exceeds this, document the partial finding + open issue and
proceed — better to ship 4-of-5 answers than block on perfection.

## Acceptance criteria (spike-completion)

Spike is *done* when:

- [ ] All 5 questions have a documented answer (even "no surprises found" is a
      valid answer if backed by evidence)
- [ ] `.gitattributes` declaration drafted and pasted into this report's findings section
- [ ] Recommended mitigations for any blockers found are explicit and actionable
- [ ] Story 2.1 ACs (AC7/AC8/AC9) can be authored against the spike findings
      without further investigation
- [ ] If any *blocker* is found (e.g., frontend-maven-plugin breaks on Windows),
      this report explicitly recommends one of:
      (a) absorb the blocker into 2.1 scope,
      (b) descope Windows from 2.1 with formal sprint-change,
      (c) replace the offending tool (e.g., switch frontend-maven-plugin →
          eirslett-frontend-maven-plugin or exec-maven-plugin)

## Findings

*(populated during spike execution — leave blank until investigation starts)*

### Q1 findings:

### Q2 findings:

### Q3 findings:

### Q4 findings:

### Q5 findings:

## Recommended `.gitattributes`

*(populated during spike)*

```gitattributes
# Drafted in Q2; copy this block into deliveryline-frontend/.gitattributes
```

## Recommended `package.json` scripts / Vite config notes

*(populated during spike)*

## Recommended `frontend/README.md` Windows section

*(populated during spike)*

## Outstanding follow-ups for story 2.1

*(populated during spike — items that 2.1 should explicitly absorb into its AC body)*
