package com.repopulse.repositories;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.repopulse.repositories.SnapshotModels.*;
@Service
public class RepositoryQueryService {
    private final SnapshotStore store;
    public RepositoryQueryService(SnapshotStore store){this.store=store;}
    public List<RepositorySummary> list(){return store.list();}
    public RepositorySummary get(long id){return store.get(id);}
    public PullRequestPage pullRequests(long id,int page,int size) {
        if(page<0||size<1||size>100) throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        var all=store.readSnapshot(id).pullRequests().stream().sorted(Comparator.comparing(PullRequestData::updatedAt).reversed()
            .thenComparing(Comparator.comparingInt(PullRequestData::number).reversed())).toList();
        int from=(int)Math.min((long)page*size,all.size());
        return new PullRequestPage(all.subList(from,Math.min(from+size,all.size())),page,size,all.size());
    }
}
