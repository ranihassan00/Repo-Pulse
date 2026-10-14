# RepoPulse

Developer intelligence built from GitHub history. RepoPulse imports public repositories, stores analysis snapshots in PostgreSQL, and identifies files that change frequently.

**Current milestone:** Spring Boot backend, GitHub imports, repository/PR summaries, and maintenance hotspots. React, PR risk scoring, OAuth, webhooks, and cloud deployment are upcoming milestones—not implemented features. See the [roadmap](docs/roadmap.md).

## Run with Docker

Requires Docker Engine with Compose (Docker Desktop on Windows/macOS).

1. Copy `.env.example` to `.env` and choose a local database password.
2. Optionally set `GITHUB_TOKEN` in `.env`. It is used only by the server. Do not commit it.
3. From the repository root, run:

```sh
docker compose up --build
```

The API listens at `http://localhost:8080`. Check `GET /actuator/health` for readiness. Database storage persists in a named Docker volume. `docker compose down` retains it; `docker compose down -v` deliberately deletes it.

The service is **local development only** and has no user authentication yet. Run one backend instance; startup recovery marks abandoned import attempts failed. Keep the Compose API binding on loopback until OAuth and authorization are implemented.

## Import your first repository

PowerShell:

```powershell
$repo = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/repositories/import `
  -ContentType application/json -Body '{"owner":"octocat","name":"Hello-World"}'
Invoke-RestMethod "http://localhost:8080/api/repositories/$($repo.id)/hotspots"
```

macOS/Linux:

```sh
curl -X POST http://localhost:8080/api/repositories/import \
  -H 'Content-Type: application/json' \
  -d '{"owner":"octocat","name":"Hello-World"}'
curl http://localhost:8080/api/repositories/1/hotspots
```

Use the returned repository `id` in subsequent URLs. Imports are synchronous and may take up to three minutes of GitHub fetch time. A failed refresh preserves the last successful snapshot. Reimporting does not inflate the visible counts.

## What a hotspot means

For each file, RepoPulse counts distinct sampled commits, additions/deletions, and commit subjects containing whole-word bug-fix terms. It sorts by touches, changed lines, then path. Bug-fix-message matches are a heuristic; they do not establish that the file caused a bug. A hotspot is not yet a PR risk score.

Imports sample up to 100 commits from a captured default-branch head and up to 100 recently updated PRs. Each response includes coverage limits and incompleteness flags. Contributor counts describe sampled authors, not all-time contributors. Renames retain their previous-path metadata but are not joined into a single file history. PR metadata can change during an import; it is an observation at import time, not an atomic snapshot of GitHub.

Large imports may exceed GitHub's unauthenticated rate limit because file details require a request per commit. A server-side token increases the available quota. Rate-limited imports return 503 and retry information when available; retry after that period. The token is not OAuth and doesn't add login functionality.

## Develop and test

Requires a JDK 21 installation. The Maven wrapper downloads Maven 3.9.11 automatically.

```sh
cd backend
./mvnw verify
```

On Windows use `./mvnw.cmd verify`. Tests start a temporary **real PostgreSQL** process via embedded-postgres and mock only GitHub/network boundaries. They do not need a real GitHub token or Docker. Run tests as a normal user: PostgreSQL refuses to start as root. Linux runners need standard PostgreSQL native runtime libraries.

To run the API directly, provide `DATABASE_URL` (a PostgreSQL JDBC URL), `DATABASE_USER`, and `DATABASE_PASSWORD`, then run `./mvnw spring-boot:run`. Compose keeps its database internal; use your own local PostgreSQL instance for this mode.

GitHub Actions runs the tests, packages the application, builds its container, and checks container startup. An optional manual workflow input (or `[live-smoke]` in a push commit message) enables a live public-repository smoke test, including reimport and restart persistence.

## Structure

```text
backend/     Spring Boot API, GitHub client, analysis, migrations, tests
docs/        API reference, design, plan, roadmap
.github/     Continuous integration
compose.yaml Local API + PostgreSQL
```

Read [the API reference](docs/api.md) for endpoints and error responses. The [design](docs/superpowers/specs/2026-10-14-repopulse-design.md) explains how this foundation leads to explainable PR scoring.

Dependency references: [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [GitHub commit API](https://docs.github.com/en/rest/commits/commits#get-a-commit).
