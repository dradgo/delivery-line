# Story 1.1: Initialize Maven Multi-Module Project Scaffold

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **foundation developer**,
I want **the DeliveryLine Maven multi-module project initialized with backend, frontend, and runner-contracts modules plus root-level directories**,
so that **every subsequent story has a coherent build graph and stable package/module boundaries to work within**.

## Acceptance Criteria

1. **Given** a clean working directory, **When** the scaffold is generated per AR1 via Spring Initializr (Java 21, Maven, jar packaging, group `org.dradgo`, artifact `deliveryline`), **Then** the resulting project includes Spring Web, Data JPA, PostgreSQL driver, Flyway, Validation, Actuator, Docker Compose support, and Testcontainers dependencies.
2. **Given** the generated parent POM, **When** Spring Shell is available from Initializr, **Then** it is included as a dependency; **Otherwise** `org.springframework.shell:spring-shell-starter` is added manually with a compatible Spring Shell BOM.
3. **Given** the root POM, **When** `./mvnw clean install` runs, **Then** three Maven modules build successfully with artifact IDs `deliveryline-backend`, `deliveryline-frontend` (Vite stub — real React wiring ships later in Epic 2), and `deliveryline-runner-contracts`.
4. **Given** the root directory, **Then** `runners/` (with `codex/` and `claude/` subfolders containing placeholder `Dockerfile` + `entrypoint.sh` + `README.md`), `infra/observability/`, `scripts/`, `docs/`, and `.github/workflows/` directories exist per the architecture project structure.
5. **Given** the backend module, **Then** the base package is `org.dradgo` with skeleton subpackages `domain`, `application`, `adapters`, `infrastructure` (empty subdirectories at minimum, populated by later stories).
6. **Given** `.gitignore` at the root, **Then** it excludes Maven target dirs, Node `node_modules/` and `dist/`, IDE files (`.idea/`, `.vscode/`), OS artifacts (`.DS_Store`, `Thumbs.db`), `.env` files, and local runtime state directories.
7. **Given** `.env.example` at the root, **Then** it documents placeholder names for `LINEAR_API_KEY`, `GITHUB_TOKEN`, `DELIVERYLINE_HOME`, and Docker Compose overrides — with no real secrets.

## Tasks / Subtasks

- [x] **Task 1: Pre-flight checks** (AC: 1)
  - [x] Verify `java -version` reports **21** (Temurin/Adoptium recommended). If not, install Java 21 before continuing.
  - [x] Verify `mvn -v` (or planned `./mvnw -v` after generation) reports **≥ 3.9.4** — the minimum for Spring Boot 4.x.
  - [x] Verify a clean working directory: no pre-existing `pom.xml`, `deliveryline-*/`, or `runners/` at the project root.

- [x] **Task 2: Scaffold via Spring Initializr** (AC: 1, 2)
  - [x] **Preferred — Spring CLI:** `spring init deliveryline --type=maven-project --language=java --java-version=21 --boot-version=4.0.6 --packaging=jar --group-id=org.dradgo --artifact-id=deliveryline --name=deliveryline --description="Governed local-first agent delivery workflow" --dependencies=web,data-jpa,postgresql,flyway,validation,actuator,docker-compose,testcontainers`
  - [x] **Fallback A — direct download (no CLI required, cross-platform):** `curl -o deliveryline.zip "https://start.spring.io/starter.zip?type=maven-project&language=java&javaVersion=21&bootVersion=4.0.6&packaging=jar&groupId=org.dradgo&artifactId=deliveryline&name=deliveryline&description=Governed+local-first+agent+delivery+workflow&dependencies=web,data-jpa,postgresql,flyway,validation,actuator,docker-compose,testcontainers"` then unzip. ← **used in this implementation** (no Spring CLI on dev machine).
  - [x] **Fallback B — web UI:** `https://start.spring.io/` with the same parameters; download zip; unzip.
  - [x] **Pin Spring Boot version 4.0.6:** Spring Boot **4.0.6** is the locked baseline (per architecture § Initialization Command). Append `&bootVersion=4.0.6` to the `start.spring.io/starter.zip` URL (or `--boot-version=4.0.6` to the Spring CLI). Verify generated `pom.xml` `<parent><version>4.0.6</version>`.
  - [x] **Spring Boot 4 starter renames to expect:** `spring-boot-starter-web` is now `spring-boot-starter-webmvc`; per-starter `-test` variants (`-actuator-test`, `-flyway-test`, `-data-jpa-test`, `-validation-test`, `-webmvc-test`) replace the old umbrella `spring-boot-starter-test`. Treat as expected.
  - [x] Verify the generated `pom.xml` declares all 8 documented starter dependencies (under their Boot 4 names where applicable).
  - [x] **Spring Shell — manual add (Spring Shell isn't yet on Initializr's Boot 4 dropdown):** add `<dependency><groupId>org.springframework.shell</groupId><artifactId>spring-shell-starter</artifactId></dependency>` to the **backend** POM and import `<dependency><groupId>org.springframework.shell</groupId><artifactId>spring-shell-dependencies</artifactId><version>4.0.2</version><type>pom</type><scope>import</scope></dependency>` in the root `<dependencyManagement>`. Spring Shell **4.0.2** (released 2026-04-24) is the first stable Boot 4-compatible release.
  - [x] **Preserve the Maven Wrapper:** kept `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/` at the root.

- [x] **Task 3: Convert to multi-module** (AC: 3)
  - [x] Changed root `pom.xml` packaging from `jar` to `pom` and added `<modules>` declaring all three sub-modules.
  - [x] Moved generated `src/` and the original `<dependencies>` block into `deliveryline-backend/` with its own `pom.xml` inheriting from the root.
  - [x] Created `deliveryline-frontend/pom.xml` — `<modelVersion>4.0.0</modelVersion>`, `<parent>` referencing the root (groupId `org.dradgo`, artifactId `deliveryline`, relativePath `../pom.xml`), `<artifactId>deliveryline-frontend</artifactId>`, `<packaging>pom</packaging>`.
  - [x] Created `deliveryline-runner-contracts/pom.xml` — same minimum shape; `<packaging>pom</packaging>`.
  - [x] Ran `./mvnw clean install` from root — green build across all 4 reactor modules.
  - [x] **Footgun reminder:** root packaging was correctly flipped to `pom` on first attempt.

- [x] **Task 4: Backend YAML config + smoke-test guard** (AC: 1, 3)
  - [x] Renamed `deliveryline-backend/src/main/resources/application.properties` → `application.yml` with single `spring.application.name: deliveryline` placeholder. Real config lands in story 1.3.
  - [x] Annotated `DeliveryLineApplicationTests` with `@Disabled("enabled in story 1.3 once Flyway V1 + Testcontainers wiring ships")` — preserves the file, keeps `./mvnw clean install` green on a fresh clone.

- [x] **Task 5: Root-level directories** (AC: 4)
  - [x] `runners/codex/` with placeholders: `Dockerfile` (`FROM scratch`), `entrypoint.sh` (`#!/bin/sh`), `README.md` ("Codex runner image — populated in story 3.3").
  - [x] `runners/claude/` with the same placeholder set; README points to story 3.4.
  - [x] `infra/observability/.gitkeep`.
  - [x] `scripts/.gitkeep`.
  - [x] `docs/` already existed (BMad artifacts present); no `.gitkeep` needed.
  - [x] `.github/workflows/.gitkeep` (`ci.yml` ships in story 1.21).

- [x] **Task 6: Backend package skeleton** (AC: 5)
  - [x] Under `deliveryline-backend/src/main/java/org/dradgo/`, created empty subpackages: `domain/`, `application/`, `adapters/`, `infrastructure/`, each with `.gitkeep`.
  - [x] Initializr generated the main class at `org.dradgo.deliveryline.DeliverylineApplication` (nested under artifactId, lowercase 'l') — moved up to `org.dradgo.DeliveryLineApplication` and CamelCase-renamed; same treatment applied to test classes (`DeliveryLineApplicationTests`, `TestDeliveryLineApplication`, `TestcontainersConfiguration`).
  - [x] Confirmed backend POM `<groupId>` = `org.dradgo`, `<artifactId>` = `deliveryline-backend`; root POM `<artifactId>` = `deliveryline`.

- [x] **Task 7: Root .gitignore** (AC: 6)
  - [x] Augmented Initializr-generated `.gitignore` with: **Maven** (`target/`, `*.class`, `dependency-reduced-pom.xml`); **Node** (`node_modules/`, `dist/`, `.vite/`, `coverage/`); **IDE** (`.idea/`, `.vscode/`, `*.iml`); **OS** (`.DS_Store`, `Thumbs.db`, `Desktop.ini`); **Env** (`.env`, `.env.local`); **Runtime state** (`runner-work/`, `runner-logs/`, `artifacts/`, `deliveryline-data/` — default `DELIVERYLINE_HOME` subpaths).

- [x] **Task 8: Root .env.example** (AC: 7)
  - [x] Header comment + per-line variable documentation: `LINEAR_API_KEY`, `GITHUB_TOKEN`, `DELIVERYLINE_HOME`, `POSTGRES_HOST_PORT=5432`, plus reserved comment block for story 1.2 / 3.7 ELK + Prometheus + Grafana port overrides. No real secrets.

- [x] **Task 9: Root docs**
  - [x] Replaced Initializr `README.md` with one-liner `# DeliveryLine — see docs/`.
  - [x] Deleted Initializr-generated `HELP.md`.
  - [x] Created empty `AGENTS.md` placeholder; story 1.20 will populate.

- [x] **Task 10: Build verification** (AC: 1, 2, 3)
  - [x] `./mvnw clean install` from project root — **BUILD SUCCESS** in 57.263s (root + 3 modules all green; backend took 50.373s most of which was first-time Spring Boot 4.0.6 + Spring Shell 4.0.2 dependency downloads).
  - [x] `./mvnw -pl deliveryline-backend dependency:tree` — confirmed all 8 starters + Spring Shell 4.0.2 + Testcontainers stack present.
  - [x] `./mvnw -pl deliveryline-backend package` (covered by `clean install`) — `deliveryline-backend/target/deliveryline-backend-0.0.1-SNAPSHOT.jar` is a Spring Boot repackaged executable jar with `BOOT-INF/` layout.
  - [x] `java -jar deliveryline-backend/target/deliveryline-backend-0.0.1-SNAPSHOT.jar` — Spring Boot 4.0.6 banner displayed, Spring Data JPA bootstrapped, Tomcat 11.0.21 started on port 8080, then **APPLICATION FAILED TO START** with the predicted `Failed to configure a DataSource: 'url' attribute is not specified` (Hikari/Flyway expected; PostgreSQL connection wiring lands in story 1.2; Flyway V1 migrations land in story 1.3). Failure is JDBC/DataSource, **not** packaging — confirms executable jar correctness.

## Dev Notes

This is the **first story of Epic 1** and lays the foundation contract for every subsequent story. There is no prior story to learn from — this story establishes the patterns. Follow the architecture document strictly; do not invent module names, dependencies, or directory layouts beyond what is documented.

**Critical scope discipline:**
- This story is **scaffolding only** — no business logic, no schema, no actual runner code. Just the Maven multi-module structure + root-level directories + skeleton packages + .gitignore + .env.example.
- The Vite/React frontend wiring (story 2.1), the runner-contracts schemas (story 1.6), the Codex/Claude runner Dockerfiles (stories 3.3 + 3.4), the Flyway V1 schema (story 1.3), and CI workflows (story 1.21) are all explicitly **deferred to their own stories**. This story only creates the placeholder modules + directories that those future stories will populate.
- **Do not over-build.** Resist the temptation to add Flyway migration files, write any controllers, or create config classes — those are explicitly later stories. The acceptance criteria are exactly the scope.

**Foundation-gate awareness.** Story 1.23 establishes a CI gate that becomes a required status check on `main` after Epic 1 closes. That gate verifies foundation contracts (state-transition table, Flyway V1, central registries, runner schema v1, etc.) — **none of which exist yet**. This story only lays the structural foundation those contracts will live in. Do not attempt to satisfy story 1.23's gate from here.

**Tech stack pinned by architecture.**
- Java 21 (Temurin/Adoptium recommended)
- Maven 3.9.4+ (Boot 4 baseline) — Maven Wrapper (`./mvnw`) bundled by Initializr; CI relies on it
- **Spring Boot 4.0.6** (locked) — note the Boot 4 starter renames: `spring-boot-starter-webmvc` (was `-web`), per-starter `-test` variants (`-actuator-test`, `-flyway-test`, etc.) replacing the old umbrella `spring-boot-starter-test`
- Spring Web (as `-webmvc`), Data JPA, PostgreSQL driver, Flyway, Validation, Actuator, Docker Compose support, Testcontainers — all 8 declared as Initializr starter dependencies
- **Spring Shell 4.0.2** (first stable Boot 4-compatible release, 2026-04-24) — added manually with `spring-shell-dependencies:4.0.2` BOM imported in root `<dependencyManagement>`; CLI commands ship in story 1.15

**Module shape.**
- `deliveryline-backend` — the Spring Boot application. Contains everything except runner-contracts schemas (story 1.6) and frontend (Epic 2). Base package `org.dradgo` with empty subpackages `domain`, `application`, `adapters`, `infrastructure`.
- `deliveryline-frontend` — placeholder Maven module (`<packaging>pom</packaging>`) for Epic 2's Vite React TypeScript wiring (story 2.1).
- `deliveryline-runner-contracts` — placeholder Maven module (`<packaging>pom</packaging>`) for runner JSON schemas + validator (story 1.6).

**Cross-OS .gitignore + .env.example.** The project will run on Windows / macOS / Linux per the supported-environment matrix from story 1.17. Make `.gitignore` patterns work across all three (`.DS_Store` for macOS, `Thumbs.db` for Windows, no Linux-specific patterns needed). `.env.example` contains placeholder names + comments only — no real secrets.

### Project Structure Notes

The architecture document (`_bmad-output/planning-artifacts/architecture.md`) prescribes the complete project tree. Key alignments:

- **Root paths:** `pom.xml` (this story), `README.md` (this story — one-line pointer), `AGENTS.md` (this story — empty placeholder), `.gitignore` (AC6), `.env.example` (AC7), `docker-compose.yml` (story 1.2 — DO NOT create here), `.github/workflows/ci.yml` (story 1.21 — directory created here per AC4, but `ci.yml` itself ships in story 1.21).
- **Module roots:** `deliveryline-backend/`, `deliveryline-frontend/`, `deliveryline-runner-contracts/` per AC3.
- **Operational dirs:** `runners/codex/`, `runners/claude/`, `infra/observability/`, `scripts/`, `docs/`, `.github/workflows/` per AC4.
- **Backend package roots:** `org.dradgo.{domain,application,adapters,infrastructure}` per AC5.

**⚠️ Architecture-document drift to be aware of:**

Two sections of `architecture.md` use the unprefixed name `backend/` rather than the canonical `deliveryline-backend/`:
1. § *Complete Project Directory Structure* (around line 905) shows `backend/` as the module folder.
2. § *Project Structure ADR Summary* — Path Mapping (around lines 1227–1273) references `backend/application/...`, `backend/adapters/...`, etc.

These are **stale residue from before the project was renamed to DeliveryLine**. The **canonical names are the artifact-id form** (`deliveryline-backend`, `deliveryline-frontend`, `deliveryline-runner-contracts`) per AR3 (epics.md:176), AC3 of this story, and architecture.md lines 1189 + 1577–1589.

A separate stale reference: architecture.md § *Complete Project Directory Structure* shows `docker-compose.observability.yml` as a separate file — this was consolidated into a single `docker-compose.yml` per the unified-compose decision (story 1.2 + AR25 reversal). Not this story's concern.

### References

- [Source: `_bmad-output/planning-artifacts/architecture.md` — § Starter Template Evaluation] — Spring Initializr command, dependency list, multi-module structure, module artifact IDs.
- [Source: `_bmad-output/planning-artifacts/architecture.md` — § Initialization Command (line 193)] — verbatim Spring Initializr command (post-rename to `org.dradgo` + `deliveryline`, post-Boot 4.0.6 pin).
- [Source: `_bmad-output/planning-artifacts/architecture.md` — § Complete Project Directory Structure (line 903)] — full directory tree. **See drift note above.**
- [Source: `_bmad-output/planning-artifacts/architecture.md` — § Project Structure ADR Summary (line 1119)] — rationale for Maven multi-module choice. **See drift note above.**
- [Source: `_bmad-output/planning-artifacts/architecture.md` — § Java Package Organization (line 662)] — backend package boundaries.
- [Source: `_bmad-output/planning-artifacts/epics.md` — § Epic 1 — AR1, AR2, AR3 (lines 174–176)] — Spring Boot 4.0.6 pin, Vite React TypeScript scaffolding deferred to Epic 2, Maven multi-module root with three artifact IDs.
- [Source: `_bmad-output/planning-artifacts/epics.md` — § Foundation gate (line 365)] — Epic 1 includes story 1.23 foundation-gate verification that gates Epic 2/3/4. This story lays the structural foundation only.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) — `claude-opus-4-7[1m]`.

### Debug Log References

- **Spring Boot version deviation (HALT/decision):** Spring Initializr's current default is **Spring Boot 4.0.6**, not 3.x as originally specified by AC1 and architecture. HALTed at Task 2 to surface the deviation. User chose **Option 2: adopt Spring Boot 4.0.6 and update architecture/epics/story**. Architecture § *Initialization Command* and epics.md AR1 updated to pin `--boot-version=4.0.6` + Maven 3.9.4+ baseline + note Boot 4 starter renames.
- **Spring Shell compatibility check:** Maven Central queried via `https://search.maven.org/solrsearch` and `https://repo.maven.apache.org/maven2/org/springframework/shell/spring-shell-dependencies/maven-metadata.xml` — confirmed Spring Shell **4.0.2** GA released 2026-04-24 (latest at time of implementation), with its own `spring-shell-dependencies:4.0.2` BOM. Imported in root `<dependencyManagement>`.
- **No Spring CLI on the dev machine:** scaffold downloaded via Fallback A (`curl -o /tmp/deliveryline-init.zip "https://start.spring.io/starter.zip?...&bootVersion=4.0.6"`).

### Completion Notes List

- ✅ **AC1** — generated POM declares Spring Boot 4.0.6 parent + all 8 documented dependencies. Verified via `./mvnw -pl deliveryline-backend dependency:tree`. Boot 4 starter rename `-web` → `-webmvc` confirmed.
- ✅ **AC2** — Spring Shell **4.0.2** added manually (not yet on Initializr's Boot 4 dropdown); `spring-shell-dependencies:4.0.2` BOM imported in root POM `<dependencyManagement>` so the starter resolves without a hardcoded version in the backend POM.
- ✅ **AC3** — `./mvnw clean install` green across all 4 reactor modules in 57.263s. Backend produces a Spring Boot repackaged executable jar (`deliveryline-backend/target/deliveryline-backend-0.0.1-SNAPSHOT.jar`).
- ✅ **AC4** — `runners/codex/{Dockerfile,entrypoint.sh,README.md}`, `runners/claude/{Dockerfile,entrypoint.sh,README.md}`, `infra/observability/.gitkeep`, `scripts/.gitkeep`, `docs/` (pre-existing), `.github/workflows/.gitkeep` all present at root.
- ✅ **AC5** — main class lives at `org.dradgo.DeliveryLineApplication` (Initializr generated it nested at `org.dradgo.deliveryline.DeliverylineApplication` with lowercase 'l'; moved up + CamelCase-renamed). Empty subpackages `org/dradgo/{domain,application,adapters,infrastructure}` present, each with `.gitkeep`.
- ✅ **AC6** — `.gitignore` augmented with Maven, Node, IDE, OS, env, runtime-state-dir entries on top of Initializr defaults.
- ✅ **AC7** — `.env.example` documents `LINEAR_API_KEY`, `GITHUB_TOKEN`, `DELIVERYLINE_HOME`, `POSTGRES_HOST_PORT=5432`, plus reserved comment block for story 1.2 / 3.7 port overrides; no real secrets.
- **Versions pinned:** Spring Boot **4.0.6** (parent POM), Spring Shell **4.0.2** (BOM in root `dependencyManagement`), Java 21.0.8, Maven 3.9.14 via wrapper. `<spring-shell.version>4.0.2</spring-shell.version>` exposed as a property for future bumps.
- **Initializr-generated ****`compose.yaml`**** discarded** at root — story 1.2 owns the real `docker-compose.yml`. `HELP.md` deleted per Task 9. Initializr-generated `application.properties` rewritten as `application.yml` per Task 4.
- **Smoke-jar boot result:** `java -jar deliveryline-backend/target/deliveryline-backend-0.0.1-SNAPSHOT.jar` started Spring Boot 4.0.6 (banner shown), initialized Spring Data JPA scan, started Tomcat 11.0.21 on port 8080, then failed exactly as predicted by Task 10 — `Failed to configure a DataSource: 'url' attribute is not specified` (Hikari/Flyway expected; PostgreSQL connection wiring lands in story 1.2; Flyway V1 migrations land in story 1.3). Failure is JDBC/DataSource, not packaging — confirms executable jar correctness.
- **Smoke-test guard:** `DeliveryLineApplicationTests` annotated with `@Disabled("enabled in story 1.3 once Flyway V1 + Testcontainers wiring ships")` so a fresh-clone `./mvnw clean install` stays green pre-story-1.3.
- **Architecture-doc drift unresolved:** the `backend/` vs `deliveryline-backend/` drift in architecture.md §§ *Complete Project Directory Structure* and *Project Structure ADR Summary* (line 905, 1227–1273) was left as-is — flagged in story *Project Structure Notes*. Resolving the architecture document's stale paths is a doc-cleanup task best handled outside this story's scope.

### File List

**Created (project-root-relative):**

- `pom.xml` (root parent POM, packaging=pom, Spring Boot 4.0.6 parent, Spring Shell 4.0.2 BOM in dependencyManagement)
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitattributes` (preserved from Initializr)
- `README.md` (one-line pointer — `# DeliveryLine — see docs/`)
- `AGENTS.md` (empty placeholder; story 1.20 will populate)
- `.gitignore` (rewritten with augmentations on top of Initializr base)
- `.env.example`
- `deliveryline-backend/pom.xml`
- `deliveryline-backend/src/main/java/org/dradgo/DeliveryLineApplication.java`
- `deliveryline-backend/src/main/java/org/dradgo/{domain,application,adapters,infrastructure}/.gitkeep` (×4)
- `deliveryline-backend/src/main/resources/application.yml`
- `deliveryline-backend/src/test/java/org/dradgo/DeliveryLineApplicationTests.java` (with `@Disabled` per Task 4)
- `deliveryline-backend/src/test/java/org/dradgo/TestDeliveryLineApplication.java`
- `deliveryline-backend/src/test/java/org/dradgo/TestcontainersConfiguration.java`
- `deliveryline-frontend/pom.xml` (packaging=pom)
- `deliveryline-runner-contracts/pom.xml` (packaging=pom)
- `runners/codex/Dockerfile`, `runners/codex/entrypoint.sh`, `runners/codex/README.md`
- `runners/claude/Dockerfile`, `runners/claude/entrypoint.sh`, `runners/claude/README.md`
- `infra/observability/.gitkeep`
- `scripts/.gitkeep`
- `.github/workflows/.gitkeep`

**Deleted (Initializr-generated, replaced by story scope):**

- `HELP.md` (Initializr-generated; content superseded by `docs/`)
- `compose.yaml` (Initializr Docker Compose support generated this stub; the real `docker-compose.yml` ships in story 1.2)

**Modified (planning artifacts updated to reflect Spring Boot 4.0.6 decision):**

- `_bmad-output/planning-artifacts/architecture.md` — § *Initialization Command* updated to pin `--boot-version=4.0.6` + Maven 3.9.4+ + Spring Shell 4.0.2 BOM.
- `_bmad-output/planning-artifacts/epics.md` — AR1 updated to pin Spring Boot 4.0.6 + Spring Shell 4.0.2.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — story 1-1 status promoted ready-for-dev → in-progress → review.

### Change Log

- **2026-04-26** — Story implemented end-to-end. Spring Boot version updated from "3.x" (planning baseline) to **4.0.6** (locked) per user decision when Initializr's current default was discovered to be 4.x. Spring Shell **4.0.2** added manually (no Initializr Boot 4 entry yet). Maven multi-module reactor green across all 4 modules; smoke-jar boot confirms executable packaging is correct. Architecture and epics updated to reflect the Boot 4.0.6 baseline. Status: ready-for-dev → review.

### Review Findings

- [x] [Review][Patch] `mvnw` is committed without the executable bit, so `./mvnw clean install` will fail on Unix-like systems [mvnw:1]
- [x] [Review][Patch] POSIX shell placeholders are not committed as Unix-safe scripts for future container use [.gitattributes:1]
- [x] [Review][Patch] Testcontainers uses floating `postgres:latest`, making test behavior non-deterministic [deliveryline-backend/src/test/java/org/dradgo/TestcontainersConfiguration.java:15]
- [x] [Review][Patch] `.gitignore` leaves common `.env.*` variants trackable even though the story requires env files to be excluded [.gitignore:57]
