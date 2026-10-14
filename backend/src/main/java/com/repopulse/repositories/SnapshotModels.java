package com.repopulse.repositories;

import java.time.Instant;
import java.util.List;

public final class SnapshotModels {
    private SnapshotModels() {}
    public record RepositoryMetadata(long githubId, String owner, String name, String defaultBranch) {}
    public record Author(Long githubId, String name, String email) {}
    public record FileChange(String path, String previousPath, String status, long additions, long deletions) {}
    public record CommitData(String sha, String subject, Instant committedAt, Author author, List<FileChange> files) {}
    public record PullRequestData(long githubId, int number, String title, String state, String url, Instant updatedAt) {}
    public record Coverage(String headSha, int commitLimit, int prLimit, int commitCount, int prCount,
        boolean commitsCapped, boolean prsCapped, boolean filesIncomplete, Instant oldestCommitAt, Instant newestCommitAt) {}
    public record RepositorySummary(long id, RepositoryMetadata metadata, Coverage coverage,
        long sampledContributorCount, String latestRunStatus) {}
    public record PullRequestPage(List<PullRequestData> items, int page, int size, int totalCount) {}
}
