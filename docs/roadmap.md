# Roadmap and resume alignment

RepoPulse is being built in stages. A resume entry should describe completed or actively implemented capabilities accurately.

| Resume point | Foundation milestone | Next deliverable |
| --- | --- | --- |
| Full-stack repository analytics | Spring Boot API, persisted GitHub data, commit/file statistics | React + TypeScript dashboard and frontend tests |
| PR risk-scoring engine | Historical file activity and change-size inputs | Versioned deterministic score, factor breakdown, PR-head analysis |
| OAuth and webhooks | Optional server-side development token only | GitHub OAuth, repository authorization, signature-verified webhooks, durable jobs |
| Docker, tests, CI/CD, cloud | Docker Compose, JUnit/PostgreSQL tests, GitHub Actions CI | Deployment workflow and cloud hosting |

## Next: dashboard and risk scoring

Build repository selection, import status, hotspot table, and PR detail views. Define exact scoring weights and thresholds before implementation. Store factor contributions, algorithm version, input coverage, PR head SHA, and historical cutoff so each result can be explained and reproduced. Scores prioritize review; they are not calibrated probabilities of a defect.

## Then: authenticated event-driven analysis

OAuth provides identity and repository access. Webhooks verify the raw request signature and deduplicate GitHub delivery IDs before enqueuing analysis. Authenticate and authorize every user-facing repository endpoint before public hosting.

## Deployment

Choose AWS or Azure and a budget, then add managed PostgreSQL, HTTPS, secret storage, migrations, health monitoring, and a deployment pipeline. Current GitHub Actions is continuous integration, not a deployed cloud service.

## Later

Co-change relationships, dependency/impact analysis, and optional LLM explanations of calculated factors. LLMs do not determine the risk score.
