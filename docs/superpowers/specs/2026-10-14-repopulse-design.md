# RepoPulse: repository intelligence foundation

Status: approved by the user; foundation implementation in progress.

## Purpose and resume alignment

RepoPulse helps developers understand maintenance hotspots and potentially risky pull requests through calculated repository metrics. Java code computes the metrics and scores; an optional future LLM only explains those results.

The product roadmap directly covers the four resume claims:

1. Full-stack engineering analytics: Spring Boot and PostgreSQL import and persist GitHub data; a React + TypeScript dashboard displays repository statistics, hotspots, and PR analysis.
2. PR risk engine: deterministic, versioned scoring based on change size, historical file activity, sensitive paths, and test changes, with individual factor contributions.
3. GitHub OAuth and webhooks: authenticated access and verified events trigger repository and PR analysis.
4. Delivery infrastructure: Docker, automated backend/frontend tests, GitHub Actions CI, and a later cloud deployment.

The first milestone implements the data foundation and hotspots. Later roadmap entries must not be described as already implemented in project documentation.

## Architecture

Use one Git repository with backend/, frontend/ (introduced with the dashboard), docs/, and .github/workflows/. The backend is one modular Spring Boot application, using Maven and PostgreSQL with Flyway migrations. Keep GitHub transport, import orchestration, persistence, and analysis separate. No microservices, Redis, or LLM dependency in this milestone.

The initial service is for local development and binds to localhost. Public deployment requires authentication and resource controls first. An optional server-side GitHub token comes from an environment variable; never commit or return it to clients.

## First milestone: public repository import

Accept owner/repo, not arbitrary remote URLs. Validate each component and use a fixed GitHub API origin. Reject private repositories in this milestone, even if the optional token could read them.

Import repository metadata, the latest 100 default-branch commits, each commit's changed-file details, and the latest 100 PRs ordered by update time. Follow pagination within these limits. Contributors in the first milestone are unique authors observed in the imported commits, not a claim of all historical contributors. Preserve null GitHub identities using commit author metadata.

Fetch changed-file pages for each commit. Record any upstream truncation or exhausted cap; never present incomplete file history as complete. PR metadata does not include complete PR diffs until the scoring milestone.

Capture the default-branch head SHA at import start and use it for the commit sample. Store repository GitHub ID, commit SHA, PR GitHub ID/number, author identities, file path/status, additions, deletions, and timestamps. Treat rename paths as separate paths initially and document this limitation.

Persist import runs with running/succeeded/failed status, captured head, requested limits, observed counts, completeness flags, timestamps, and a sanitized error. Network calls occur outside database transactions. Publish a complete snapshot transactionally only after successful fetching; a failed refresh leaves the previous successful snapshot available. Repository identity and natural-key constraints prevent duplicate data on reimport. Prevent concurrent imports for the same repository.

An application restart marks abandoned running imports failed. Begin with a synchronous bounded import request; durable background jobs belong to a later milestone. Bound upstream calls with timeouts and stop on rate limits rather than sleeping through a long retry loop.

## API contract

- POST /api/repositories/import accepts {"owner":"...","name":"..."}; returns the successful repository snapshot summary and import coverage.
- GET /api/repositories lists imported repositories and latest import status.
- GET /api/repositories/{id} returns saved metadata and sampled commit, PR, and contributor counts.
- GET /api/repositories/{id}/hotspots returns ranked file metrics and snapshot coverage.
- GET /api/repositories/{id}/pull-requests returns saved PR metadata with pagination.
- GET /actuator/health provides a minimal health response.

Use structured error responses for invalid input (400), unknown local or remote repository (404), an import already running (409), upstream rate limiting (503 with retry information when available), and other upstream failures (502). Do not expose upstream tokens, raw response bodies, or stack traces.

## Hotspot analysis

For each path, count distinct imported commits touching it and sum additions and deletions. Rank by touch count descending, then changed lines descending, then path ascending. Return raw metrics, most recent change, and sample boundaries rather than inventing calibrated risk categories.

Flag commit subjects containing whole-word fix, fixes, fixed, bug, bugs, regression, or regressions (case insensitive). Report the number of matching commits touching each path as bug-fix-message matches. This is a message heuristic, not evidence that the file caused a defect.

Do not equate a frequently changed file with a defective file. The dashboard and API must show that the results describe the imported sample, not full repository history. Empty repositories produce a valid empty snapshot and hotspot list.

## Later milestones and interfaces preserved now

### Dashboard and deterministic PR scoring

React + TypeScript displays repository selection, import status, sample coverage, hotspot rankings, and PR detail. Fetch PR diff metadata and compare it with historical data available before the PR analysis cutoff. Avoid using later commits as historical evidence. Persist score version, input coverage, factor contributions, and analyzed head SHA so a score is reproducible.

The initial scoring specification will define and test exact weights and thresholds before implementation. Scores are prioritization heuristics, not probabilities of failure. Test-file presence does not prove adequate test coverage. Missing inputs must be visible rather than treated as zero risk.

### OAuth and webhooks

GitHub OAuth adds user identity and access control with state validation, server-side token storage, and minimal scopes. Verify webhook signatures against raw request bytes, deduplicate delivery IDs, and process supported PR events asynchronously. Enforce repository authorization on all user-facing endpoints. Define job durability and retries before hosting the service publicly.

### Deployment and optional advanced analysis

Deploy frontend, API, and managed PostgreSQL after authentication and operational limits are implemented. Add environment-based secrets, HTTPS, database migrations, logs, and health checks. Choose the cloud provider and budget at that milestone. Optional explanations, co-change relationships, and dependency graphs follow the core product.

## Development and verification

Provide Docker Compose for PostgreSQL and the backend, a Maven wrapper, an example environment file containing placeholders, and setup instructions. Pin compatible supported dependency versions during implementation using official documentation.

JUnit tests cover deterministic hotspot rankings, heuristic boundaries, invalid repository input, duplicate imports, empty repositories, pagination/truncation, rate limiting, failed-refresh snapshot preservation, and concurrent imports. Mock GitHub responses; tests must not require a real token. PostgreSQL integration tests verify migrations, uniqueness, and transaction behavior against PostgreSQL. GitHub Actions runs the backend suite; add frontend tests when the frontend exists.

Acceptance: import a public fixture repository, retrieve persisted PR and contributor summaries and correctly ranked hotspots, restart the application and retrieve the same snapshot, and reimport without duplicate metrics. Document any environment-dependent checks that could not run.

## Repository publication

The user created and selected the public repository ranihassan00/Repo-Pulse during implementation. Use that repository, preserving its main branch. Publish the implementation through a feature branch and pull request.
