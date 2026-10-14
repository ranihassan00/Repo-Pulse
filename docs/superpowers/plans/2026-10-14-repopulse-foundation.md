# RepoPulse Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import a public GitHub repository into PostgreSQL and expose reproducible, sample-aware hotspot and PR summaries.

**Architecture:** A single Spring Boot application separates GitHub fetching, import coordination, snapshot persistence, and analysis. Fetch data outside transactions, then publish an immutable import snapshot atomically; retain the previous successful snapshot when a refresh fails.

**Tech Stack:** Java, Spring Boot, Maven wrapper, PostgreSQL, Flyway, JUnit, Testcontainers, Docker Compose, GitHub Actions. Verify compatible supported versions from official documentation before pinning them.

**Spec:** ../specs/2026-10-14-repopulse-design.md

## Global Constraints

- Import the latest 100 default-branch commits and latest 100 PRs ordered by update time; show coverage and truncation.
- Accept owner/repo components and use a fixed GitHub API origin. Reject private repositories.
- Network calls occur outside database transactions. A failed refresh preserves the previous successful snapshot.
- Prevent concurrent imports for the same repository. Mark abandoned running imports failed on restart.
- Rank hotspots by touch count descending, changed lines descending, then path ascending.
- Match whole-word fix, fixes, fixed, bug, bugs, regression, or regressions in commit subjects, case insensitive.
- Keep tokens server-side and outside Git. Bind local service access to localhost.
- Tests use mocked GitHub responses and real PostgreSQL integration tests; no real token required.
- React, scoring, OAuth, webhooks, and cloud deployment remain documented future milestones.

## Review Focus

- A repository renamed or requested with different casing must retain one canonical GitHub identity (Tasks 1 and 3).
- A refresh failing on its last page must not replace the visible successful snapshot (Task 3).
- Missing GitHub author identity and repeated file entries must not inflate contributors or touches (Tasks 2 and 4).
- GitHub pagination links must not send authorization to another origin (Task 2).
- Restart and competing requests must not leave a repository permanently locked or publish two active imports (Tasks 1 and 3).

## File boundaries

backend/src/main/java/com/repopulse/ contains RepoPulseApplication plus github/, imports/, repositories/, hotspots/, and api/ packages. Use constructor injection and DTO records; keep each component in a focused file. Corresponding tests live under backend/src/test/java/com/repopulse/. Database migrations live under backend/src/main/resources/db/migration/. Root files provide Compose, environment examples, documentation, and CI.

## Task 1: Application and snapshot persistence

**Files:** backend/pom.xml, backend/mvnw, backend/mvnw.cmd, backend/.mvn/wrapper/maven-wrapper.properties, backend/src/main/java/com/repopulse/RepoPulseApplication.java, repositories/SnapshotStore.java, repositories/SnapshotModels.java, backend/src/main/resources/application.yml, db/migration/V1__repository_snapshots.sql, backend/src/test/java/com/repopulse/repositories/SnapshotStoreTest.java, .gitignore.

**Interfaces:** SnapshotModels defines RepositoryMetadata(long githubId, String owner, String name, String defaultBranch), Author(Long githubId, String name, String email), FileChange(String path, String previousPath, String status, long additions, long deletions), CommitData(String sha, String subject, Instant committedAt, Author author, List<FileChange> files), PullRequestData(long githubId, int number, String title, String state, String url, Instant updatedAt), and Coverage(String headSha, int commitLimit, int prLimit, int commitCount, int prCount, boolean commitsCapped, boolean prsCapped, boolean filesIncomplete, Instant oldestCommitAt, Instant newestCommitAt). SnapshotStore exposes beginImport(RepositoryMetadata): UUID, publish(UUID, ImportedSnapshot): RepositorySummary, fail(UUID, String): void, and recoverAbandonedImports(): int. ImportedSnapshot combines metadata, commits, PRs, and coverage. RepositorySummary includes local repository ID, metadata, coverage, sampled contributor count, and latest run status.

- [ ] Verify official dependency documentation, select compatible supported versions, and record pins in pom.xml and wrapper properties. Check Java and Docker availability before choosing executable validation commands.
- [ ] Write PostgreSQL integration tests: duplicate GitHub repository IDs resolve to one repository; (snapshot, SHA) and (commit, path) are unique; a second running import conflicts; recovery marks a running import failed. Run `./mvnw -Dtest=SnapshotStoreTest test` (Windows: `./mvnw.cmd`); confirm the tests initially fail because persistence is missing.
- [ ] Implement schema and store: repositories, import_runs, commits, file_changes, pull_requests, author fields, coverage fields, and a successful-snapshot pointer. Use a partial unique index for one running import per repository. Scope snapshot records to a run, store times as timestamptz, and persist nullable author IDs. Canonical GitHub ID is repository identity; owner/name are mutable metadata.
- [ ] Implement short transactions for begin, publish, fail, and startup recovery. Publish records and advance the repository pointer in the same transaction. Preserve history for future scoring; current reads use only the successful pointer. Define the initial application as a single-instance service so startup recovery does not affect another worker.
- [ ] Run SnapshotStoreTest against PostgreSQL and commit the tested foundation.

## Task 2: Bounded GitHub reader

**Files:** github/GitHubClient.java, github/GitHubDtos.java, github/GitHubException.java, imports/ImportedSnapshot.java, github/GitHubClientTest.java and test resources under github/fixtures/.

**Interfaces:** GitHubClient.repository(String owner, String name): RepositoryMetadata; GitHubClient.fetchSnapshot(RepositoryMetadata): ImportedSnapshot. GitHubException carries a safe error category and optional retry timestamp, never response bodies or tokens. The transport base URL can be replaced only in test configuration; production uses https://api.github.com.

- [ ] Add fixture tests for paginated commits/PRs/files, captured head SHA passed to commit queries, empty repository, absent author login, private repository rejection, 404, rate-limited 403/429, server errors, off-origin pagination, malformed payload, and truncated changed-file lists. Assert caps of 100 commits/100 PRs and truthful coverage. Run GitHubClientTest and confirm failures.
- [ ] Implement validated repository lookup and JSON mapping. Fetch commits using the captured head SHA, PRs using state=all and updated-descending ordering. Derive contributors from sampled commit authors rather than fetching all-history totals.
- [ ] Follow validated same-origin pagination within caps. Inspect continuation metadata when exactly at a cap. Limit changed-file pagination to GitHub's documented maximum and mark incomplete coverage when upstream limits or aggregate counts indicate missing files. Deduplicate file paths per commit. Preserve rename metadata without joining path histories.
- [ ] Add connection timeout of 5 seconds, per-request timeout of 20 seconds, and an overall import deadline of 180 seconds. Stop on rate limiting and expose safe retry metadata; do not retry indefinitely. Disable off-origin redirects and never log authorization headers.
- [ ] Run GitHubClientTest, check all fixtures, and commit the client.

## Task 3: Import coordinator and repository API

**Files:** imports/ImportService.java, imports/ImportRecovery.java, repositories/RepositoryQueryService.java, api/RepositoryController.java, api/ApiExceptionHandler.java, imports/ImportServiceTest.java, api/RepositoryControllerTest.java.

**Interfaces:** ImportService.importRepository(String owner, String name): RepositorySummary. RepositoryQueryService.list(): List<RepositorySummary>, get(long id): RepositorySummary, and pullRequests(long id, int page, int size): PullRequestPage. PullRequestPage returns items, zero-based page, size, and total count. Controller exposes the exact routes in the spec.

- [ ] Write tests for invalid/slash-containing owner/name (400), private repository rejection (400), unknown repository (404), competing import (409), upstream rate limit (503 with Retry-After when available), other upstream failure (502), and sanitized error responses. Test page >= 0 and size 1..100, default size 20.
- [ ] Add integration tests importing the same fixture twice with equal visible counts; force a final-page fetch failure and verify the previous snapshot remains visible. Test mixed-case requests and canonical GitHub ID reuse after a rename. Run ImportServiceTest and RepositoryControllerTest and confirm failures.
- [ ] Implement orchestration: validate input, resolve metadata, acquire the database run slot, fetch outside transactions, publish on success, and fail the run on error. Implement recovery on application startup. Document that a failed metadata lookup cannot create a repository run before identity is known.
- [ ] Add repository/PR reads from successful snapshots, separate latest-run status from visible-snapshot coverage, and use stable PR ordering by updated time descending then number descending. Count contributors by GitHub ID where available, otherwise normalized email, otherwise exact author name; explain fallback ambiguity in the API documentation.
- [ ] Run the import/API suites and commit the working endpoints.

## Task 4: Explainable hotspot metrics

**Files:** hotspots/HotspotService.java, hotspots/Hotspot.java, api/HotspotController.java, hotspots/HotspotServiceTest.java, hotspots/HotspotIntegrationTest.java.

**Interfaces:** HotspotService.forRepository(long id): HotspotResponse; HotspotResponse includes List<Hotspot> and Coverage. Hotspot includes path, touchCount, additions, deletions, bugFixMessageMatches, and lastChangedAt. Endpoint: GET /api/repositories/{id}/hotspots.

- [ ] Write tests where file A is touched by 3 commits and file B by 2: A ranks first regardless of B's larger diff; equal touches break by additions+deletions then path. Assert duplicate file entries do not add touches, empty history yields an empty list, and missing repository returns 404.
- [ ] Add heuristic tests: `FIX login bug` matches once; `prefix handling` and `debug output` do not; matching text only in the message body does not count. Assert bug-match counts never exceed touch counts and timestamps use the most recent matching file change.
- [ ] Run hotspot tests to confirm failures, then implement aggregation from the visible snapshot. Return sample coverage with every response and use long numeric sums. Avoid high/low defect labels and probability language.
- [ ] Run unit and PostgreSQL hotspot integration tests, verify the fixture's exact metrics, and commit.

## Task 5: Reproducible development, CI, and GitHub publication

**Files:** backend/Dockerfile, compose.yaml, .env.example, .github/workflows/backend-ci.yml, README.md, docs/api.md, docs/roadmap.md.

**Interfaces:** `docker compose up --build` starts PostgreSQL and the API; GET http://localhost:8080/actuator/health returns health. `backend/mvnw verify` runs the backend suite. Compose publishes the API only on 127.0.0.1; the backend binds to container interfaces internally. Keep database access internal to Compose and document host-development connection settings separately.

- [ ] Add the backend image and PostgreSQL service with a persistent named volume, health check, environment-based credentials, and loopback-only API publication. Keep .env ignored; example values contain no real credentials. Configure Actuator to expose minimal health only.
- [ ] Add GitHub Actions on push and pull_request using a supported Java runtime, Maven cache, and a Docker-capable runner for PostgreSQL Testcontainers; execute `./mvnw --batch-mode verify`. Pin action versions and give the workflow read-only repository permission.
- [ ] Write setup instructions, example requests, sample limits, error semantics, troubleshooting, and a roadmap that maps each resume bullet to implemented versus planned features. Explain OAuth is distinct from the optional development API token.
- [ ] Run the full suite once, validate Compose configuration, and smoke-test import, saved PRs, contributors, hotspots, restart persistence, and duplicate-safe reimport. Use controlled fixture data for exact assertions; perform a public-repository smoke test if connectivity allows. Record unavailable checks honestly rather than marking them passed.
- [ ] Inspect staged changes for secrets and unintended files. Check whether ranihassan00/repopulse exists; create it privately if absent through an available authorized GitHub mechanism. Preserve existing remote content if found. Push verified commits, inspect workflow results, and report the repository link and actual validation results. If account authorization is unavailable, finish the local deliverable and explain the specific publication blocker.

## Execution choice

Recommend native execution in this chat: the client, persistence, and API share DTOs and transaction behavior, so one implementer can keep those interfaces consistent. Use the execution skill's review workflow when implementation is complete. Keep progress explanations tied to the component being built and what its tests establish.
