package com.repopulse.imports;
import com.repopulse.github.GitHubClient;
import com.repopulse.github.GitHubException;
import com.repopulse.repositories.SnapshotStore;
import com.repopulse.repositories.SnapshotModels.RepositorySummary;
import org.springframework.stereotype.Service;

@Service
public class ImportService {
    private final GitHubClient github;
    private final SnapshotStore store;
    public ImportService(GitHubClient github,SnapshotStore store){this.github=github;this.store=store;}
    public RepositorySummary importRepository(String owner,String name) {
        GitHubClient.validate(owner,name);
        var repo=github.repository(owner,name);
        var run=store.beginImport(repo);
        try {return store.publish(run,github.fetchSnapshot(repo));}
        catch(RuntimeException e) {
            store.fail(run,e instanceof GitHubException?e.getMessage():"Import failed; previous snapshot preserved");
            throw e;
        }
    }
}
