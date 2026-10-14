CREATE TABLE repositories (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 github_id BIGINT NOT NULL UNIQUE,
 owner TEXT NOT NULL, name TEXT NOT NULL, default_branch TEXT NOT NULL,
 successful_run UUID
);
CREATE TABLE import_runs (
 id UUID PRIMARY KEY, repository_id BIGINT NOT NULL REFERENCES repositories(id),
 status TEXT NOT NULL CHECK(status IN ('running','succeeded','failed')),
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ,
 error TEXT, coverage JSONB, snapshot JSONB,
 commit_limit INT NOT NULL DEFAULT 100, pr_limit INT NOT NULL DEFAULT 100
);
ALTER TABLE repositories ADD CONSTRAINT successful_run_fk FOREIGN KEY(successful_run) REFERENCES import_runs(id);
CREATE UNIQUE INDEX one_running_import ON import_runs(repository_id) WHERE status='running';
CREATE INDEX repository_runs ON import_runs(repository_id, started_at DESC);
CREATE TABLE commits (
 run_id UUID NOT NULL REFERENCES import_runs(id), sha TEXT NOT NULL,
 subject TEXT NOT NULL, committed_at TIMESTAMPTZ NOT NULL,
 author_github_id BIGINT, author_name TEXT, author_email TEXT,
 PRIMARY KEY(run_id, sha)
);
CREATE TABLE file_changes (
 run_id UUID NOT NULL, commit_sha TEXT NOT NULL, path TEXT NOT NULL,
 previous_path TEXT, status TEXT NOT NULL,
 additions BIGINT NOT NULL CHECK(additions>=0), deletions BIGINT NOT NULL CHECK(deletions>=0),
 PRIMARY KEY(run_id, commit_sha, path),
 FOREIGN KEY(run_id, commit_sha) REFERENCES commits(run_id, sha)
);
CREATE TABLE pull_requests (
 run_id UUID NOT NULL REFERENCES import_runs(id), github_id BIGINT NOT NULL,
 number INT NOT NULL, title TEXT NOT NULL, state TEXT NOT NULL, url TEXT NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(run_id, github_id), UNIQUE(run_id, number)
);
