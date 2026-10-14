# API reference

Base URL: http://localhost:8080. JSON request and response bodies. Local development only; no authentication yet.

| Method | Route | Result |
| --- | --- | --- |
| POST | /api/repositories/import | Import `{"owner":"octocat","name":"Hello-World"}` and return a repository summary |
| GET | /api/repositories | Imported repositories and their latest run status |
| GET | /api/repositories/{id} | Metadata, visible-snapshot coverage, sampled contributor count |
| GET | /api/repositories/{id}/hotspots | `items` containing path, touchCount, additions, deletions, bugFixMessageMatches, lastChangedAt; plus coverage |
| GET | /api/repositories/{id}/pull-requests?page=0&size=20 | `items`, zero-based page, size, totalCount; newest updated PR first |
| GET | /actuator/health | Minimal health status |

PR page sizes are 1–100; negative pages and malformed parameters return 400. Beyond the last page, items is empty. Repository IDs are local database IDs, not GitHub IDs.

## Coverage

`headSha` identifies the captured default-branch head (null for empty repositories). `commitLimit` and `prLimit` are 100. `commitCount` and `prCount` are actual imported counts. `commitsCapped` and `prsCapped` indicate additional history beyond the sample. `filesIncomplete` indicates a file-page cap or a mismatch between file sums and GitHub's aggregate change totals. `oldestCommitAt` and `newestCommitAt` bound observed commit timestamps, not the complete repository lifetime.

A new repository without a successful import has null coverage and no visible snapshot. Snapshot endpoints return 404 until one exists. After a failed refresh, the repository summary can show latestRunStatus=failed while coverage and analysis still refer to the prior successful snapshot.

Contributors use GitHub author ID when present, otherwise normalized email, otherwise the exact name. Different identities can describe the same person; a name fallback can conflate people. No all-time contributor claim is made.

## Errors

Responses have `{"code":"...","message":"..."}`. Tokens, raw GitHub bodies, SQL diagnostics, and stack traces are never returned.

| HTTP | Meaning |
| --- | --- |
| 400 | Invalid request, invalid pagination, or private repository |
| 404 | Repository/resource or successful snapshot not found |
| 409 | An import is already running for the canonical repository |
| 502 | GitHub failure, malformed data, fetch timeout, or unsafe pagination |
| 503 | GitHub rate limit; Retry-After included when available |
| 500 | Unexpected internal failure |

An import that fails before repository metadata resolves has no run record because its canonical identity is unknown. All subsequent attempts are recorded. The network fetch has a 180-second deadline and a 20-second per-request timeout; database publication follows in a short transaction.
