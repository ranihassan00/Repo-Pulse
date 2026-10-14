package com.repopulse.hotspots;
import com.repopulse.repositories.SnapshotStore;
import com.repopulse.repositories.SnapshotModels.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
@Service
public class HotspotService {
    private static final Pattern BUG_WORD=Pattern.compile("\\b(?:fix|fixes|fixed|bug|bugs|regression|regressions)\\b",Pattern.CASE_INSENSITIVE);
    private final SnapshotStore store;
    public HotspotService(SnapshotStore store){this.store=store;}
    public record HotspotResponse(List<Hotspot> items,Coverage coverage) {}
    public HotspotResponse forRepository(long id){var snapshot=store.readSnapshot(id);return new HotspotResponse(calculate(snapshot.commits()),snapshot.coverage());}
    public List<Hotspot> calculate(List<CommitData> commits) {
        Map<String,Accumulator> totals=new HashMap<>(); Set<String> seenCommits=new HashSet<>();
        for(var c:commits) {
            if(!seenCommits.add(c.sha()))continue;
            boolean bug=BUG_WORD.matcher(c.subject().split("\\R",2)[0]).find(); Set<String> seenPaths=new HashSet<>();
            for(var f:c.files()) {
                if(!seenPaths.add(f.path()))continue;
                var a=totals.computeIfAbsent(f.path(),key->new Accumulator());
                a.touches++;a.additions+=f.additions();a.deletions+=f.deletions();if(bug)a.matches++;
                if(a.last==null||c.committedAt().isAfter(a.last))a.last=c.committedAt();
            }
        }
        return totals.entrySet().stream().map(e->{var a=e.getValue();return new Hotspot(e.getKey(),a.touches,a.additions,a.deletions,a.matches,a.last);})
            .sorted(Comparator.comparingLong(Hotspot::touchCount).reversed()
                .thenComparing(Comparator.comparingLong((Hotspot h)->h.additions()+h.deletions()).reversed()).thenComparing(Hotspot::path)).toList();
    }
    private static class Accumulator {long touches,additions,deletions,matches;Instant last;}
}
