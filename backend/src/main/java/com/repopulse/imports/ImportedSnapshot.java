package com.repopulse.imports;
import java.util.List;
import com.repopulse.repositories.SnapshotModels.*;
public record ImportedSnapshot(RepositoryMetadata metadata, List<CommitData> commits,
    List<PullRequestData> pullRequests, Coverage coverage) {}
