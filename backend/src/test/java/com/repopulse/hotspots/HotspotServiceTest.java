package com.repopulse.hotspots;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static com.repopulse.repositories.SnapshotModels.*;
import static org.assertj.core.api.Assertions.*;

class HotspotServiceTest {
    final HotspotService service=new HotspotService(null);
    final Instant time=Instant.parse("2026-01-01T00:00:00Z");
    CommitData commit(String sha,String subject,int day,FileChange... files){return new CommitData(sha,subject,time.plusSeconds(day*86400L),new Author(1L,"Ada",null),List.of(files));}
    FileChange file(String path,long additions,long deletions){return new FileChange(path,null,"modified",additions,deletions);}
    @Test void ranksTouchesBeforeLinesAndUsesStableTieBreaks() {
        var result=service.calculate(List.of(
            commit("a","fix login",1,file("A",1,1),file("B",100,100),file("C",1,1),file("D",1,1)),
            commit("b","refactor",2,file("A",1,1),file("B",100,100)),
            commit("c","docs",3,file("A",1,1))));
        assertThat(result).extracting(Hotspot::path).containsExactly("A","B","C","D");
        assertThat(result.getFirst().touchCount()).isEqualTo(3);
        assertThat(result.getFirst().additions()).isEqualTo(3);
        assertThat(result.getFirst().lastChangedAt()).isEqualTo(time.plusSeconds(3*86400));
    }
    @Test void countsEachCommitAndPathOnce() {
        var c=commit("a","FIX login bug",1,file("A",4,2),file("A",4,2));
        var result=service.calculate(List.of(c,c));
        assertThat(result.getFirst().touchCount()).isEqualTo(1);
        assertThat(result.getFirst().additions()).isEqualTo(4);
        assertThat(result.getFirst().bugFixMessageMatches()).isEqualTo(1);
    }
    @Test void matchesWholeWordsInSubjectOnly() {
        var result=service.calculate(List.of(
            commit("a","prefix handling",1,file("A",1,0)), commit("b","debug output",1,file("A",1,0)),
            commit("c","refactor\nfix bug",1,file("A",1,0)), commit("d","Fixed regression",1,file("A",1,0)),
            commit("e","BUGS",1,file("A",1,0)), commit("f","fixes failure",1,file("A",1,0))));
        assertThat(result.getFirst().touchCount()).isEqualTo(6);
        assertThat(result.getFirst().bugFixMessageMatches()).isEqualTo(3);
    }
    @Test void emptyHistoryHasNoHotspots(){assertThat(service.calculate(List.of())).isEmpty();}
}
