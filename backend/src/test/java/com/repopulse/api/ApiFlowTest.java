package com.repopulse.api;

import com.repopulse.github.*;
import com.repopulse.imports.ImportedSnapshot;
import com.repopulse.repositories.SnapshotStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import static com.repopulse.repositories.SnapshotModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApiFlowTest {
    static final EmbeddedPostgres PG;
    static {try{PG=EmbeddedPostgres.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
    }
    @Autowired MockMvc mvc;
    @Autowired SnapshotStore store;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GitHubClient github;
    static final RepositoryMetadata REPO=new RepositoryMetadata(77,"ada","demo","main");
    @BeforeEach void resetDatabase(){jdbc.execute("TRUNCATE repositories,import_runs,commits,file_changes,pull_requests RESTART IDENTITY CASCADE");}
    @AfterAll static void stop() throws Exception {PG.close();}
    ImportedSnapshot fixture(){
        var t=Instant.parse("2026-01-01T00:00:00Z");
        return new ImportedSnapshot(REPO,List.of(new CommitData("abc","fix login",t,new Author(null,"Ada","ada@example.test"),
            List.of(new FileChange("Auth.java",null,"modified",4,2)))),
            List.of(new PullRequestData(12,1,"Login","open","https://github.com/ada/demo/pull/1",t)),
            new Coverage("abc",100,100,1,1,false,false,false,t,t));
    }
    void stub(){when(github.repository("ada","demo")).thenReturn(REPO);when(github.fetchSnapshot(REPO)).thenReturn(fixture());}
    void importOk() throws Exception {mvc.perform(post("/api/repositories/import").contentType("application/json").content("{\"owner\":\"ada\",\"name\":\"demo\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.coverage.commitCount").value(1));}
    @Test void importReadHotspotsAndReimportUsePersistedSnapshot() throws Exception {
        stub();importOk();importOk();
        mvc.perform(get("/api/repositories")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/repositories/1")).andExpect(status().isOk()).andExpect(jsonPath("$.sampledContributorCount").value(1));
        mvc.perform(get("/api/repositories/1/hotspots")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].path").value("Auth.java"))
            .andExpect(jsonPath("$.items[0].touchCount").value(1)).andExpect(jsonPath("$.items[0].bugFixMessageMatches").value(1));
        mvc.perform(get("/api/repositories/1/pull-requests")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].number").value(1));
    }
    @Test void failedFetchPreservesOldSnapshotAndReturnsSafeError() throws Exception {
        stub();importOk();when(github.fetchSnapshot(REPO)).thenThrow(new GitHubException(502,"GitHub request failed",null));
        mvc.perform(post("/api/repositories/import").contentType("application/json").content("{\"owner\":\"ada\",\"name\":\"demo\"}"))
            .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("github_error"));
        mvc.perform(get("/api/repositories/1")).andExpect(jsonPath("$.latestRunStatus").value("failed")).andExpect(jsonPath("$.coverage.commitCount").value(1));
    }
    @Test void rejectsInvalidInputMissingRepositoriesAndInvalidPagination() throws Exception {
        mvc.perform(post("/api/repositories/import").contentType("application/json").content("{\"owner\":\"../evil\",\"name\":\"demo\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/repositories/import").contentType("application/json").content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(get("/api/repositories/999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/999/hotspots")).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/1/pull-requests?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/repositories/1/pull-requests?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/repositories/1/pull-requests?page=wrong")).andExpect(status().isBadRequest());
    }
    @Test void rejectsConcurrentImportsAndReturnsRetryHeader() throws Exception {
        stub();store.beginImport(REPO);
        mvc.perform(post("/api/repositories/import").contentType("application/json").content("{\"owner\":\"ada\",\"name\":\"demo\"}"))
            .andExpect(status().isConflict());
        store.recoverAbandonedImports();
        when(github.fetchSnapshot(REPO)).thenThrow(new GitHubException(503,"GitHub rate limit reached", "60"));
        mvc.perform(post("/api/repositories/import").contentType("application/json").content("{\"owner\":\"ada\",\"name\":\"demo\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","60"));
    }
    @Test void unexpectedExceptionDoesNotExposeSensitiveMessage() throws Exception {
        when(github.repository(anyString(),anyString())).thenThrow(new RuntimeException("token=do-not-expose"));
        mvc.perform(post("/api/repositories/import").contentType("application/json").content("{\"owner\":\"ada\",\"name\":\"demo\"}"))
            .andExpect(status().isInternalServerError()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-expose"))));
    }
    @Test void emptyImportReturnsEmptyHotspots() throws Exception {
        when(github.repository("ada","demo")).thenReturn(REPO);
        when(github.fetchSnapshot(REPO)).thenReturn(new ImportedSnapshot(REPO,List.of(),List.of(),new Coverage(null,100,100,0,0,false,false,false,null,null)));
        mvc.perform(post("/api/repositories/import").contentType("application/json").content("{\"owner\":\"ada\",\"name\":\"demo\"}")) .andExpect(status().isOk());
        mvc.perform(get("/api/repositories/1/hotspots")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }
}
